package io.github.saocsb.mcai.core.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared HTTP machinery of the clients: request building, async exchange with a line-based body reader,
 * SSE dispatch, idle watchdog, retries with backoff, error mapping and secret redaction.
 *
 * <p>Retries (at most {@link #MAX_RETRIES}) happen only before a successful response started: on connection
 * failures and on retryable statuses (408/409/429/5xx, or as {@code x-should-retry} says). A header timeout is
 * not retried (the provider may still be generating).
 */
abstract class HttpLlmClient {
	static final int MAX_RETRIES = 2;
	/** Longer server-requested delays are not waited for; the error is surfaced instead. */
	static final long MAX_RETRY_AFTER_MILLIS = 20_000;
	static final int MAX_ERROR_CHARS = 300;
	private static final int DEFAULT_TIMEOUT_SECONDS = 180;
	private static final int ERROR_BODY_CAP = 64 * 1024;
	private static final int BODY_CAP = 16 * 1024 * 1024;
	private static final Set<String> RETRYABLE_ERROR_TYPES = Set.of(
			"overloaded_error", "api_error", "rate_limit_error", "timeout_error", "server_error");

	final ProviderConfig config;
	private final HttpClient http;
	private final double backoffScale;
	private final URI endpoint;
	private final String endpointError;
	/** Host (and explicit port) used in messages. */
	final String host;
	private final List<String> secrets;

	HttpLlmClient(ProviderConfig config, HttpClient http, double backoffScale, String endpointUrl) {
		this.config = Objects.requireNonNull(config, "config");
		this.http = Objects.requireNonNull(http, "http");
		this.backoffScale = Math.max(0, backoffScale);
		URI uri = null;
		String error = null;
		String id = config.id() == null ? "?" : config.id();
		if (endpointUrl == null || endpointUrl.isBlank()) {
			error = "No base URL configured for provider '" + id + "'";
		} else {
			try {
				uri = URI.create(endpointUrl);
				String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
				if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
					error = "Invalid base URL for provider '" + id + "' (expected http(s)://host/...)";
					uri = null;
				}
			} catch (IllegalArgumentException e) {
				error = "Invalid base URL for provider '" + id + "'";
				uri = null;
			}
		}
		this.endpoint = uri;
		this.endpointError = error;
		this.host = uri == null ? "?" : uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
		this.secrets = collectSecrets(config);
	}

	/** The request body for this protocol. */
	abstract JsonObject requestBody(LlmRequest request);

	/** Adds authentication and protocol headers (extra headers are added afterwards). */
	abstract void addProtocolHeaders(HttpRequest.Builder builder);

	/** Parses a complete (non-streamed) successful response. */
	abstract LlmResponse parseResponse(JsonObject json);

	/** A fresh accumulator for a streamed response. */
	abstract StreamState newStreamState(LlmProgressListener listener);

	/** Lets a protocol combine a user extra header with its own value of the same header. */
	String mergeExtraHeader(String name, String value) {
		return value;
	}

	/** Sends the request; see {@link LlmClient#complete}. */
	final CompletableFuture<LlmResponse> execute(LlmRequest request, LlmProgressListener listener) {
		String id = config.id() == null ? "?" : config.id();
		if (endpointError != null) {
			return CompletableFuture.failedFuture(new LlmException(endpointError, -1, false));
		}
		if (config.model() == null || config.model().isBlank()) {
			return CompletableFuture.failedFuture(new LlmException("No model configured for provider '" + id + "'", -1, false));
		}
		HttpRequest httpRequest;
		try {
			httpRequest = buildHttpRequest(request);
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(new LlmException(
					redact("Invalid request settings for provider '" + id + "': " + describe(e)), -1, false));
		}
		Call call = new Call(httpRequest, listener == null ? LlmProgressListener.NONE : listener);
		call.start(0);
		return call.result;
	}

	/** Builds the HTTP request (package-private for tests). */
	HttpRequest buildHttpRequest(LlmRequest request) {
		if (endpoint == null) {
			throw new IllegalArgumentException(endpointError);
		}
		String body = Json.GSON.toJson(requestBody(request));
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
				.timeout(Duration.ofSeconds(timeoutSeconds()))
				.header("Content-Type", "application/json")
				.header("Accept", config.stream() ? "text/event-stream" : "application/json")
				.header("User-Agent", LlmClients.userAgent())
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		addProtocolHeaders(builder);
		if (config.extraHeaders() != null) {
			config.extraHeaders().forEach((name, value) -> {
				if (name != null && !name.isBlank() && value != null) {
					builder.setHeader(name.trim(), mergeExtraHeader(name.trim(), value));
				}
			});
		}
		return builder.build();
	}

	/** Messages with blank entries dropped and consecutive same-role messages joined by a blank line. */
	static List<ChatMessage> mergeMessages(List<ChatMessage> messages) {
		List<ChatMessage> merged = new ArrayList<>();
		if (messages == null) {
			return merged;
		}
		for (ChatMessage m : messages) {
			if (m == null || m.role() == null || m.content() == null || m.content().isBlank()) {
				continue;
			}
			if (!merged.isEmpty() && merged.getLast().role() == m.role()) {
				ChatMessage last = merged.removeLast();
				merged.add(new ChatMessage(m.role(), last.content() + "\n\n" + m.content()));
			} else {
				merged.add(m);
			}
		}
		return merged;
	}

	static String role(ChatMessage.Role role) {
		return role == ChatMessage.Role.ASSISTANT ? "assistant" : "user";
	}

	/** Merges {@link ProviderConfig#extraBody()} into the body: entries override, null values remove. */
	final void mergeExtraBody(JsonObject body) {
		Map<String, Object> extra = config.extraBody();
		if (extra == null) {
			return;
		}
		extra.forEach((key, value) -> {
			if (key == null) {
				return;
			}
			if (value == null) {
				body.remove(key);
			} else {
				body.add(key, Json.toJson(value));
			}
		});
	}

	static String stripTrailingSlashes(String url) {
		if (url == null) {
			return "";
		}
		String s = url.trim();
		int end = s.length();
		while (end > 0 && s.charAt(end - 1) == '/') {
			end--;
		}
		return s.substring(0, end);
	}

	static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	final int timeoutSeconds() {
		return config.timeoutSeconds() > 0 ? config.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;
	}

	static boolean isRetryableStatus(int status) {
		return status == 408 || status == 409 || status == 429 || status >= 500;
	}

	/** Server-requested delay from retry-after-ms / retry-after (seconds or HTTP date), or -1. */
	static long retryAfterMillis(HttpHeaders headers) {
		Optional<String> ms = headers.firstValue("retry-after-ms");
		if (ms.isPresent()) {
			try {
				double v = Double.parseDouble(ms.get().trim());
				if (!Double.isNaN(v)) {
					return Double.isInfinite(v) ? Long.MAX_VALUE : (long) v;
				}
			} catch (NumberFormatException ignored) {
				// fall through to retry-after
			}
		}
		Optional<String> ra = headers.firstValue("retry-after");
		if (ra.isPresent()) {
			String v = ra.get().trim();
			try {
				double seconds = Double.parseDouble(v);
				if (!Double.isNaN(seconds)) {
					return Double.isInfinite(seconds) ? Long.MAX_VALUE : (long) (seconds * 1000);
				}
			} catch (NumberFormatException e) {
				try {
					ZonedDateTime at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
					return Duration.between(Instant.now(), at.toInstant()).toMillis();
				} catch (DateTimeParseException ignored) {
					// unparseable: no server-requested delay
				}
			}
		}
		return -1;
	}

	/** Delay before retry number {@code attempt + 1}, scaled for tests. */
	final long retryDelayMillis(int attempt, long retryAfterMillis) {
		double millis;
		if (retryAfterMillis > 0 && retryAfterMillis <= MAX_RETRY_AFTER_MILLIS) {
			millis = retryAfterMillis;
		} else {
			millis = Math.min(500.0 * Math.pow(2, attempt), 8000.0) * (1 - 0.25 * ThreadLocalRandom.current().nextDouble());
		}
		return Math.max(0, Math.round(millis * backoffScale));
	}

	/**
	 * A short human-readable message from an error body: {@code error.message} (OpenAI/Anthropic style),
	 * {@code message}/{@code detail}, or the raw text (HTML tags stripped), truncated to 300 characters.
	 */
	static String extractErrorMessage(String body) {
		if (body == null || body.isBlank()) {
			return "(empty response)";
		}
		String trimmed = body.trim();
		String message = null;
		JsonElement element = Json.parse(trimmed);
		if (element != null) {
			message = findMessage(element);
		}
		if (message == null || message.isBlank()) {
			message = trimmed.startsWith("<") ? trimmed.replaceAll("<[^>]*>", " ") : trimmed;
		}
		return truncate(message.replaceAll("\\s+", " ").trim(), MAX_ERROR_CHARS);
	}

	private static String findMessage(JsonElement element) {
		if (element.isJsonArray()) {
			return element.getAsJsonArray().isEmpty() ? null : findMessage(element.getAsJsonArray().get(0));
		}
		if (!element.isJsonObject()) {
			return null;
		}
		JsonObject object = element.getAsJsonObject();
		JsonElement error = object.get("error");
		if (error != null && error.isJsonObject()) {
			JsonObject e = error.getAsJsonObject();
			for (String key : new String[] {"message", "msg", "detail"}) {
				String m = Json.str(e, key);
				if (!isBlank(m)) {
					return m;
				}
			}
			String type = Json.str(e, "type");
			if (!isBlank(type)) {
				return type;
			}
		} else if (error != null && error.isJsonPrimitive() && !error.getAsString().isBlank()) {
			return error.getAsString();
		}
		for (String key : new String[] {"message", "msg", "detail", "error_msg"}) {
			String m = Json.str(object, key);
			if (!isBlank(m)) {
				return m;
			}
		}
		return null;
	}

	/**
	 * An error embedded in a successful response or stream ({@code {"error":...}}), or null if there is none.
	 * Its status is the error's numeric HTTP-like code when present, else -1.
	 */
	final LlmException embeddedError(JsonObject object) {
		JsonElement error = object.get("error");
		if (error == null || error.isJsonNull() || error.isJsonPrimitive() && error.getAsString().isBlank()) {
			return null;
		}
		int status = -1;
		boolean retryable = false;
		if (error.isJsonObject()) {
			JsonObject e = error.getAsJsonObject();
			JsonElement code = e.get("code");
			if (code != null && code.isJsonPrimitive() && code.getAsJsonPrimitive().isNumber()) {
				int c = code.getAsInt();
				if (c >= 400 && c < 600) {
					status = c;
					retryable = isRetryableStatus(c);
				}
			}
			String type = Json.str(e, "type");
			if (type != null && RETRYABLE_ERROR_TYPES.contains(type)) {
				retryable = true;
			}
		}
		String message = findMessage(object);
		return new LlmException("Error from " + host + ": " + truncate(message == null ? "unknown error" : message, MAX_ERROR_CHARS),
				status, retryable);
	}

	static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "...";
	}

	static String describe(Throwable t) {
		String message = t.getMessage();
		return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
	}

	private static Throwable unwrap(Throwable t) {
		while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
			t = t.getCause();
		}
		return t;
	}

	private static List<String> collectSecrets(ProviderConfig config) {
		List<String> list = new ArrayList<>();
		if (config.apiKey() != null && config.apiKey().trim().length() >= 4) {
			list.add(config.apiKey().trim());
		}
		if (config.extraHeaders() != null) {
			config.extraHeaders().forEach((name, value) -> {
				String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
				boolean secret = n.contains("auth") || n.contains("key") || n.contains("token") || n.contains("secret");
				if (secret && value != null) {
					String v = value.trim();
					if (v.regionMatches(true, 0, "Bearer ", 0, 7)) {
						v = v.substring(7).trim();
					}
					if (v.length() >= 4) {
						list.add(v);
					}
				}
			});
		}
		return List.copyOf(list);
	}

	/** Replaces any configured secret in {@code s} with "***". */
	final String redact(String s) {
		if (s == null) {
			return null;
		}
		for (String secret : secrets) {
			s = s.replace(secret, "***");
		}
		return s;
	}

	private LlmException redacted(LlmException e) {
		String message = redact(e.getMessage());
		return Objects.equals(message, e.getMessage()) ? e
				: new LlmException(message, e.statusCode(), e.retryable(), e.getCause());
	}

	/** One {@link #execute} call: the result future and its attempts. */
	private final class Call {
		final CompletableFuture<LlmResponse> result = new CompletableFuture<>();
		final HttpRequest request;
		final LlmProgressListener listener;
		private volatile Exchange current;

		Call(HttpRequest request, LlmProgressListener listener) {
			this.request = request;
			this.listener = listener;
			result.whenComplete((response, error) -> {
				if (error != null) {
					Exchange exchange = current;
					if (exchange != null) {
						exchange.abort();
					}
				}
			});
		}

		void start(int attempt) {
			if (result.isDone()) {
				return;
			}
			Exchange exchange = new Exchange(this, attempt);
			current = exchange;
			exchange.send();
		}

		void retryOrFail(LlmException error, int attempt, long retryAfterMillis, boolean autoRetry) {
			if (autoRetry && attempt < MAX_RETRIES && !result.isDone()) {
				long delay = retryDelayMillis(attempt, retryAfterMillis);
				CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> start(attempt + 1));
			} else {
				result.completeExceptionally(redacted(error));
			}
		}
	}

	private enum Mode {
		SSE,
		JSON,
		ERROR
	}

	/** One HTTP attempt; also the line subscriber for its response body. */
	private final class Exchange implements Flow.Subscriber<String> {
		private final Call call;
		private final int attempt;
		private final AtomicBoolean finalized = new AtomicBoolean();
		private final StringBuilder body = new StringBuilder();
		private volatile CompletableFuture<HttpResponse<Void>> httpFuture;
		private volatile Flow.Subscription subscription;
		private volatile boolean headersReceived;
		private volatile boolean stopped;
		private volatile long lastActivity;
		private volatile int status;
		private volatile HttpHeaders headers;
		private volatile Mode mode;
		private volatile SseParser sse;
		private volatile StreamState stream;

		Exchange(Call call, int attempt) {
			this.call = call;
			this.attempt = attempt;
		}

		void send() {
			CompletableFuture<HttpResponse<Void>> future;
			try {
				future = http.sendAsync(call.request, this::onHeaders);
			} catch (RuntimeException e) {
				call.result.completeExceptionally(new LlmException(
						redact("Request to " + host + " failed: " + describe(e)), -1, false, e));
				return;
			}
			httpFuture = future;
			future.whenComplete((response, error) -> onExchangeComplete(error));
			if (call.result.isCompletedExceptionally()) {
				abort();
			}
		}

		private HttpResponse.BodySubscriber<Void> onHeaders(HttpResponse.ResponseInfo info) {
			status = info.statusCode();
			headers = info.headers();
			if (status >= 200 && status < 300) {
				String type = info.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
				boolean sseBody = type.contains("text/event-stream") || config.stream() && !type.contains("json");
				if (sseBody) {
					stream = newStreamState(call.listener);
					sse = new SseParser(this::onSseEvent);
					mode = Mode.SSE;
				} else {
					mode = Mode.JSON;
				}
			} else {
				mode = Mode.ERROR;
			}
			headersReceived = true;
			startWatchdog();
			return HttpResponse.BodySubscribers.fromLineSubscriber(this, s -> null, StandardCharsets.UTF_8, null);
		}

		@Override
		public void onSubscribe(Flow.Subscription s) {
			subscription = s;
			if (stopped || call.result.isCompletedExceptionally()) {
				s.cancel();
			} else {
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(String line) {
			lastActivity = System.nanoTime();
			if (stopped) {
				return;
			}
			try {
				if (mode == Mode.SSE) {
					sse.line(line);
				} else {
					appendBody(line);
				}
			} catch (LlmException e) {
				fail(e);
			} catch (RuntimeException e) {
				fail(new LlmException("Invalid response from " + host + ": " + describe(e), -1, false, e));
			}
		}

		@Override
		public void onError(Throwable throwable) {
			// reported through the exchange future (onExchangeComplete)
		}

		@Override
		public void onComplete() {
			finish(null);
		}

		private void appendBody(String line) {
			int cap = mode == Mode.ERROR ? ERROR_BODY_CAP : BODY_CAP;
			if (body.length() + line.length() + 1 > cap) {
				if (mode == Mode.JSON) {
					fail(new LlmException("Response from " + host + " is too large", -1, false));
				}
				return;
			}
			if (!body.isEmpty()) {
				body.append('\n');
			}
			body.append(line);
		}

		private void onSseEvent(String event, String data) {
			if (stopped) {
				return;
			}
			if (stream.onEvent(event, data)) {
				stopped = true;
				call.result.complete(stream.finish());
			}
		}

		private void onExchangeComplete(Throwable error) {
			if (error == null) {
				finish(null);
				return;
			}
			Throwable cause = unwrap(error);
			if (call.result.isDone() || stopped) {
				return;
			}
			if (!headersReceived) {
				networkFailure(cause);
			} else {
				finish(cause);
			}
		}

		/** Handles the end of the body (error = null) or a failure after the headers arrived. */
		private void finish(Throwable error) {
			if (!finalized.compareAndSet(false, true)) {
				return;
			}
			try {
				switch (mode) {
					case SSE -> {
						if (error != null) {
							fail(new LlmException("Connection to " + host + " was lost: " + describe(error), -1, true, error));
						} else {
							sse.finish();
							if (!stopped) {
								stopped = true;
								call.result.complete(stream.finish());
							}
						}
					}
					case JSON -> {
						if (error != null) {
							fail(new LlmException("Connection to " + host + " was lost: " + describe(error), -1, true, error));
						} else if (!stopped) {
							stopped = true;
							call.result.complete(parseBody(body.toString()));
						}
					}
					case ERROR -> {
						stopped = true;
						httpError(body.toString());
					}
				}
			} catch (LlmException e) {
				fail(e);
			} catch (RuntimeException e) {
				fail(new LlmException("Invalid response from " + host + ": " + describe(e), -1, false, e));
			} finally {
				body.setLength(0);
				body.trimToSize();
			}
		}

		private LlmResponse parseBody(String text) {
			JsonObject json = Json.parseObject(text.trim());
			if (json == null) {
				throw new LlmException("Invalid JSON from " + host + ": " + extractErrorMessage(text), -1, false);
			}
			LlmException error = embeddedError(json);
			if (error != null && !json.has("choices") && !json.has("content")) {
				throw error;
			}
			return parseResponse(json);
		}

		private void httpError(String errorBody) {
			HttpHeaders h = headers;
			String message = "HTTP " + status + " from " + host + ": " + extractErrorMessage(errorBody);
			boolean retryable = isRetryableStatus(status);
			String shouldRetry = h.firstValue("x-should-retry").orElse("").trim();
			if (shouldRetry.equalsIgnoreCase("true")) {
				retryable = true;
			} else if (shouldRetry.equalsIgnoreCase("false")) {
				retryable = false;
			}
			long retryAfter = retryAfterMillis(h);
			boolean autoRetry = retryable;
			if (retryAfter > MAX_RETRY_AFTER_MILLIS) {
				autoRetry = false;
				long seconds = retryAfter == Long.MAX_VALUE ? -1 : (retryAfter + 999) / 1000;
				if (seconds > 0) {
					message += " (retry after " + seconds + " s)";
				}
			}
			call.retryOrFail(new LlmException(message, status, retryable), attempt, retryAfter, autoRetry);
		}

		private void networkFailure(Throwable cause) {
			String message;
			boolean autoRetry = true;
			if (cause instanceof HttpConnectTimeoutException) {
				message = "Timed out connecting to " + host;
			} else if (cause instanceof HttpTimeoutException) {
				message = "Request to " + host + " timed out after " + timeoutSeconds() + " s";
				autoRetry = false;
			} else if (cause instanceof ConnectException) {
				message = "Could not connect to " + host + " (" + describe(cause) + ")";
			} else if (cause instanceof IOException) {
				message = "Network error talking to " + host + ": " + describe(cause);
			} else {
				call.result.completeExceptionally(new LlmException(
						redact("Request to " + host + " failed: " + describe(cause)), -1, false, cause));
				return;
			}
			call.retryOrFail(new LlmException(message, -1, true, cause), attempt, -1, autoRetry);
		}

		private void startWatchdog() {
			lastActivity = System.nanoTime();
			scheduleWatchdog(TimeUnit.SECONDS.toNanos(timeoutSeconds()));
		}

		private void scheduleWatchdog(long delayNanos) {
			CompletableFuture.delayedExecutor(delayNanos, TimeUnit.NANOSECONDS).execute(this::checkIdle);
		}

		private void checkIdle() {
			if (finalized.get()) {
				return;
			}
			long limit = TimeUnit.SECONDS.toNanos(timeoutSeconds());
			long idle = System.nanoTime() - lastActivity;
			if (idle >= limit) {
				fail(new LlmException("Response from " + host + " timed out (no data for " + timeoutSeconds() + " s)", -1, true));
			} else {
				scheduleWatchdog(limit - idle);
			}
		}

		private void fail(LlmException e) {
			stopped = true;
			call.result.completeExceptionally(redacted(e));
			abort();
		}

		void abort() {
			stopped = true;
			Flow.Subscription s = subscription;
			if (s != null) {
				s.cancel();
			}
			CompletableFuture<HttpResponse<Void>> f = httpFuture;
			if (f != null) {
				f.cancel(true);
			}
		}
	}
}
