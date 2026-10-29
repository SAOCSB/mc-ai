package io.github.saocsb.mcai.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Anthropic Messages API client (raw HTTP; the mod avoids bundling the Java SDK and its dependencies).
 *
 * <p>Headers: {@code x-api-key}, {@code anthropic-version: 2026-10-21}, {@code content-type: application/json}.
 * Body: {@code {"model", "max_tokens", "system":[{"type":"text","text":...,"cache_control":{"type":"ephemeral"}}],
 * "messages":[...], "output_config":{"effort":...}?, "stream"?, ...extraBody}}. No temperature/top_p/top_k
 * (rejected on 2026-10-21). Thinking is left at the model default (adaptive on current models).
 * When the base URL host is {@code api.anthropic.com}, adds {@code "fallbacks":"default"} with header
 * {@code anthropic-beta: server-side-fallback-2026-07-01} so safety-classifier refusals are retried server-side.
 * Other hosts (Anthropic-compatible gateways) get {@code anthropic-version: 2023-06-01} and no fallbacks.
 * {@code max_tokens} defaults to 16000 (32000 when streaming) when {@link ProviderConfig#maxTokens()} is 0.
 *
 * <p>Streaming: SSE events {@code content_block_delta} with {@code text_delta} (visible text) or
 * {@code thinking_delta} (reasoning progress), {@code message_delta} (stop_reason, usage),
 * {@code error} (fail). Non-text blocks (thinking, fallback, ...) are ignored. stop_reason "max_tokens" or
 * "model_context_window_exceeded" => truncated; "refusal" => refused, with text = {@code stop_details.explanation}
 * or "" (the partial output that was declined is dropped, streamed or not). A stream that ends without
 * {@code message_stop} or a stop_reason is reported as truncated with stop reason
 * {@link LlmResponse#INCOMPLETE_STREAM}. Input tokens include cache reads and writes. Retries like
 * {@link OpenAiCompatibleClient}, also on 529.
 */
public final class AnthropicClient extends HttpLlmClient implements LlmClient {
	public static final String API_VERSION = "2026-10-21";
	public static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
	/** Version sent to Anthropic-compatible third-party endpoints. */
	static final String COMPAT_API_VERSION = "2023-06-01";
	static final String OFFICIAL_HOST = "api.anthropic.com";
	static final int DEFAULT_MAX_TOKENS = 16_000;
	static final int DEFAULT_MAX_TOKENS_STREAMING = 32_000;

	private final boolean official;

	/** @param http shared client, ideally from {@link LlmClients#newHttpClient} */
	public AnthropicClient(ProviderConfig config, HttpClient http) {
		this(config, http, 1.0);
	}

	/** @param backoffScale multiplies retry delays (tests use small values) */
	AnthropicClient(ProviderConfig config, HttpClient http, double backoffScale) {
		super(config, http, backoffScale, endpoint(config.baseUrl()));
		this.official = isOfficialHost(config.baseUrl());
	}

	@Override
	public CompletableFuture<LlmResponse> complete(LlmRequest request, LlmProgressListener listener) {
		return execute(request, listener);
	}

	/**
	 * {@code baseUrl + "/v1/messages"} (or {@code + "/messages"} when the base already ends with /v1); a query string
	 * stays at the end.
	 */
	static String endpoint(String baseUrl) {
		return endpoint(baseUrl, base -> base.endsWith("/v1/messages") ? base
				: base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages");
	}

	/** Whether the base URL points at Anthropic's own API (which gets the current version and fallbacks). */
	static boolean isOfficialHost(String baseUrl) {
		try {
			String h = URI.create(stripTrailingSlashes(baseUrl)).getHost();
			return OFFICIAL_HOST.equalsIgnoreCase(h);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	@Override
	JsonObject requestBody(LlmRequest request) {
		JsonObject body = new JsonObject();
		body.addProperty("model", config.model());
		int maxTokens = config.maxTokens() > 0 ? config.maxTokens()
				: config.stream() ? DEFAULT_MAX_TOKENS_STREAMING : DEFAULT_MAX_TOKENS;
		body.addProperty("max_tokens", maxTokens);
		if (!isBlank(request.systemPrompt())) {
			JsonObject block = new JsonObject();
			block.addProperty("type", "text");
			block.addProperty("text", request.systemPrompt());
			JsonObject cache = new JsonObject();
			cache.addProperty("type", "ephemeral");
			block.add("cache_control", cache);
			JsonArray system = new JsonArray();
			system.add(block);
			body.add("system", system);
		}
		JsonArray messages = new JsonArray();
		for (ChatMessage m : mergeMessages(request.messages())) {
			JsonObject message = new JsonObject();
			message.addProperty("role", role(m.role()));
			message.addProperty("content", m.content());
			messages.add(message);
		}
		body.add("messages", messages);
		if (!isBlank(config.effort())) {
			JsonObject output = new JsonObject();
			output.addProperty("effort", config.effort().trim());
			body.add("output_config", output);
		}
		if (official) {
			body.addProperty("fallbacks", "default");
		}
		if (config.stream()) {
			body.addProperty("stream", true);
		}
		mergeExtraBody(body);
		return body;
	}

	@Override
	void addProtocolHeaders(HttpRequest.Builder builder) {
		if (!isBlank(config.apiKey())) {
			builder.header("x-api-key", config.apiKey().trim());
		}
		builder.header("anthropic-version", official ? API_VERSION : COMPAT_API_VERSION);
		if (official) {
			builder.header("anthropic-beta", FALLBACK_BETA);
		}
	}

	@Override
	String mergeExtraHeader(String name, String value) {
		if (official && name.equalsIgnoreCase("anthropic-beta")
				&& Arrays.stream(value.split(",")).map(String::trim).noneMatch(FALLBACK_BETA::equals)) {
			return value.isBlank() ? FALLBACK_BETA : value + "," + FALLBACK_BETA;
		}
		return value;
	}

	@Override
	LlmResponse parseResponse(JsonObject json) {
		ThinkFilter filter = new ThinkFilter();
		JsonArray content = Json.arr(json, "content");
		if (content != null) {
			for (JsonElement element : content) {
				if (element.isJsonObject() && "text".equals(Json.str(element.getAsJsonObject(), "type"))) {
					filter.append(Json.str(element.getAsJsonObject(), "text"));
				}
			}
		}
		String stopReason = Json.str(json, "stop_reason");
		boolean refused = "refusal".equals(stopReason);
		String text = refused ? refusalText(Json.str(Json.obj(json, "stop_details"), "explanation")) : filter.finalText();
		JsonObject usage = Json.obj(json, "usage");
		String model = Json.str(json, "model");
		return new LlmResponse(text, stopReason, isBlank(model) ? config.model() : model,
				inputTokens(usage, 0), Json.integer(usage, "output_tokens", 0), isTruncation(stopReason), refused);
	}

	/** The text of a refused response: the explanation, never the partial output that was declined. */
	private static String refusalText(String explanation) {
		return isBlank(explanation) ? "" : explanation;
	}

	private static boolean isTruncation(String stopReason) {
		return "max_tokens".equals(stopReason) || "model_context_window_exceeded".equals(stopReason);
	}

	/** input_tokens plus cache writes and reads, or {@code fallback} if the usage has no input_tokens. */
	private static int inputTokens(JsonObject usage, int fallback) {
		if (usage == null || !usage.has("input_tokens")) {
			return fallback;
		}
		long sum = (long) Json.integer(usage, "input_tokens", 0)
				+ Json.integer(usage, "cache_creation_input_tokens", 0)
				+ Json.integer(usage, "cache_read_input_tokens", 0);
		return (int) Math.min(Integer.MAX_VALUE, sum);
	}

	@Override
	StreamState newStreamState(LlmProgressListener listener) {
		return new Stream(listener);
	}

	private final class Stream extends StreamState {
		private String model;
		private String stopReason;
		private String explanation;
		private int inputTokens;
		private int outputTokens;
		private boolean messageStopped;

		Stream(LlmProgressListener listener) {
			super(listener);
		}

		@Override
		boolean onEvent(String event, String data) {
			JsonObject json = Json.parseObject(data.trim());
			if (json == null) {
				if ("error".equals(event)) {
					throw new LlmException("Error from " + host + ": " + extractErrorMessage(data), -1, false);
				}
				return false;
			}
			String type = Json.str(json, "type");
			if (type == null) {
				type = event;
			}
			switch (type) {
				case "message_start" -> {
					JsonObject message = Json.obj(json, "message");
					String m = Json.str(message, "model");
					if (!isBlank(m)) {
						model = m;
					}
					usage(Json.obj(message, "usage"));
				}
				case "content_block_start" -> {
					JsonObject block = Json.obj(json, "content_block");
					if ("text".equals(Json.str(block, "type"))) {
						String initial = Json.str(block, "text");
						if (initial != null && !initial.isEmpty()) {
							addText(initial);
							progress();
						}
					}
				}
				case "content_block_delta" -> {
					JsonObject delta = Json.obj(json, "delta");
					String deltaType = Json.str(delta, "type");
					if ("text_delta".equals(deltaType)) {
						addText(Json.str(delta, "text"));
						progress();
					} else if ("thinking_delta".equals(deltaType)) {
						addReasoning(Json.str(delta, "thinking"));
						progress();
					}
				}
				case "message_delta" -> {
					JsonObject delta = Json.obj(json, "delta");
					String stop = Json.str(delta, "stop_reason");
					if (stop != null) {
						stopReason = stop;
					}
					String why = Json.str(Json.obj(delta, "stop_details"), "explanation");
					if (!isBlank(why)) {
						explanation = why;
					}
					usage(Json.obj(json, "usage"));
				}
				case "message_stop" -> {
					messageStopped = true;
					return true;
				}
				case "error" -> {
					LlmException error = embeddedError(json);
					throw error != null ? error : new LlmException("Error from " + host + ": unknown error", -1, false);
				}
				default -> {
					// ping, content_block_stop and future event types
				}
			}
			return false;
		}

		private void usage(JsonObject usage) {
			if (usage == null) {
				return;
			}
			inputTokens = inputTokens(usage, inputTokens);
			outputTokens = Json.integer(usage, "output_tokens", outputTokens);
		}

		@Override
		LlmResponse finish() {
			boolean refused = "refusal".equals(stopReason);
			String result = refused ? refusalText(explanation) : text.finalText();
			boolean complete = messageStopped || stopReason != null;
			return new LlmResponse(result, complete ? stopReason : LlmResponse.INCOMPLETE_STREAM,
					isBlank(model) ? config.model() : model, inputTokens, outputTokens,
					!complete || isTruncation(stopReason), refused);
		}
	}
}
