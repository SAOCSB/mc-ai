package io.github.saocsb.mcai.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI Chat Completions client (also DeepSeek, Qwen/DashScope, Kimi, GLM, SiliconFlow, OpenRouter,
 * Gemini's OpenAI endpoint, Ollama, LM Studio, Volcengine Ark...).
 *
 * <p>Request: {@code {"model", "messages":[{"role":"system"...}, ...], <maxTokensField>, "temperature"?,
 * "reasoning_effort"?, "stream"?, ...extraBody}}; header {@code Authorization: Bearer <key>} (omitted when
 * the key is blank, for local servers). Consecutive same-role messages are merged and blank ones dropped;
 * with {@link ProviderConfig#echoReasoningContent()} assistant messages carry {@code "reasoning_content": " "}.
 * Streaming parses SSE {@code data:} lines until {@code [DONE]} (or the end of the stream; a stream that ends
 * without {@code [DONE]} or a finish_reason is reported as truncated with stop reason
 * {@link LlmResponse#INCOMPLETE_STREAM}), accumulating {@code choices[0].delta.content}; {@code delta.reasoning_content} / {@code delta.reasoning} /
 * {@code delta.reasoning_details[].text} count as reasoning progress only. Visible text has
 * {@code <think>...</think>} blocks removed (a leading one is routed to reasoning while streaming).
 * finish_reason "length" => truncated; "content_filter" or a {@code refusal} => refused (text = the refusal).
 *
 * <p>Retries up to 2 times on retryable failures before any response body was consumed, with backoff
 * (honouring Retry-After up to 20 s). Error bodies {@code {"error":{"message":...}}} are surfaced in the
 * exception message. A streamed response that falls silent for {@code timeoutSeconds} fails as timed out.
 */
public final class OpenAiCompatibleClient extends HttpLlmClient implements LlmClient {
	static final String PATH = "/chat/completions";

	/** @param http shared client, ideally from {@link LlmClients#newHttpClient} */
	public OpenAiCompatibleClient(ProviderConfig config, HttpClient http) {
		this(config, http, 1.0);
	}

	/** @param backoffScale multiplies retry delays (tests use small values) */
	OpenAiCompatibleClient(ProviderConfig config, HttpClient http, double backoffScale) {
		super(config, http, backoffScale, endpoint(config.baseUrl()));
	}

	@Override
	public CompletableFuture<LlmResponse> complete(LlmRequest request, LlmProgressListener listener) {
		return execute(request, listener);
	}

	/** {@code baseUrl + "/chat/completions"}, unless the base already ends with it; a query string stays at the end. */
	static String endpoint(String baseUrl) {
		return endpoint(baseUrl, base -> base.endsWith(PATH) ? base : base + PATH);
	}

	@Override
	JsonObject requestBody(LlmRequest request) {
		JsonObject body = new JsonObject();
		body.addProperty("model", config.model());
		JsonArray messages = new JsonArray();
		if (!isBlank(request.systemPrompt())) {
			messages.add(message("system", request.systemPrompt()));
		}
		for (ChatMessage m : mergeMessages(request.messages())) {
			JsonObject message = message(role(m.role()), m.content());
			if (m.role() == ChatMessage.Role.ASSISTANT && config.echoReasoningContent()) {
				message.addProperty("reasoning_content", " ");
			}
			messages.add(message);
		}
		body.add("messages", messages);
		if (config.maxTokens() > 0) {
			String field = isBlank(config.maxTokensField()) ? "max_tokens" : config.maxTokensField().trim();
			body.addProperty(field, config.maxTokens());
		}
		if (config.temperature() != null) {
			body.addProperty("temperature", config.temperature());
		}
		if (!isBlank(config.effort())) {
			body.addProperty("reasoning_effort", config.effort().trim());
		}
		if (config.stream()) {
			body.addProperty("stream", true);
		}
		mergeExtraBody(body);
		return body;
	}

	private static JsonObject message(String role, String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content);
		return message;
	}

	@Override
	void addProtocolHeaders(HttpRequest.Builder builder) {
		if (!isBlank(config.apiKey())) {
			builder.header("Authorization", "Bearer " + config.apiKey().trim());
		}
	}

	@Override
	LlmResponse parseResponse(JsonObject json) {
		JsonArray choices = Json.arr(json, "choices");
		if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
			throw new LlmException("Unexpected response from " + host + ": no choices", -1, false);
		}
		JsonObject choice = choices.get(0).getAsJsonObject();
		JsonObject message = Json.obj(choice, "message");
		String finish = Json.str(choice, "finish_reason");
		ThinkFilter filter = new ThinkFilter();
		filter.append(message == null ? "" : contentText(message.get("content")));
		String text = filter.finalText();
		String refusal = Json.str(message, "refusal");
		boolean refused = "content_filter".equals(finish) || refusal != null && !refusal.isEmpty();
		if (refusal != null && !refusal.isBlank()) {
			text = refusal;
		}
		JsonObject usage = Json.obj(json, "usage");
		return new LlmResponse(text, finish, modelOf(Json.str(json, "model")),
				Json.integer(usage, "prompt_tokens", 0), Json.integer(usage, "completion_tokens", 0),
				"length".equals(finish), refused);
	}

	private String modelOf(String reported) {
		return isBlank(reported) ? config.model() : reported;
	}

	/** Text of a content field: a string, or an array of parts whose text parts are joined. */
	static String contentText(JsonElement content) {
		if (content == null || content.isJsonNull()) {
			return "";
		}
		if (content.isJsonPrimitive()) {
			return content.getAsString();
		}
		if (content.isJsonArray()) {
			StringBuilder sb = new StringBuilder();
			for (JsonElement part : content.getAsJsonArray()) {
				if (part.isJsonPrimitive()) {
					sb.append(part.getAsString());
				} else if (part.isJsonObject()) {
					String type = Json.str(part.getAsJsonObject(), "type");
					String text = Json.str(part.getAsJsonObject(), "text");
					if (text != null && (type == null || type.equals("text") || type.equals("output_text"))) {
						sb.append(text);
					}
				}
			}
			return sb.toString();
		}
		return "";
	}

	/** reasoning_content ?? reasoning ?? joined reasoning_details[].text (providers send one of them, OpenRouter two). */
	private static String reasoningOf(JsonObject delta) {
		String r = Json.str(delta, "reasoning_content");
		if (!isEmpty(r)) {
			return r;
		}
		r = Json.str(delta, "reasoning");
		if (!isEmpty(r)) {
			return r;
		}
		JsonArray details = Json.arr(delta, "reasoning_details");
		if (details == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (JsonElement d : details) {
			if (d.isJsonObject()) {
				String text = Json.str(d.getAsJsonObject(), "text");
				if (text != null) {
					sb.append(text);
				}
			}
		}
		return sb.toString();
	}

	private static boolean isEmpty(String s) {
		return s == null || s.isEmpty();
	}

	@Override
	StreamState newStreamState(LlmProgressListener listener) {
		return new Stream(listener);
	}

	private final class Stream extends StreamState {
		private final StringBuilder refusal = new StringBuilder();
		private String finishReason;
		private String model;
		private int inputTokens;
		private int outputTokens;
		private boolean done;

		Stream(LlmProgressListener listener) {
			super(listener);
		}

		@Override
		boolean onEvent(String event, String data) {
			String trimmed = data.trim();
			if (trimmed.equals("[DONE]")) {
				done = true;
				return true;
			}
			if ("error".equals(event)) {
				JsonObject json = Json.parseObject(trimmed);
				LlmException error = json == null ? null : embeddedError(json);
				throw error != null ? error : new LlmException("Error from " + host + ": " + extractErrorMessage(trimmed), -1, false);
			}
			if (trimmed.isEmpty()) {
				return false;
			}
			JsonElement parsed = Json.parse(trimmed);
			if (parsed != null) {
				if (parsed.isJsonObject()) {
					chunk(parsed.getAsJsonObject());
				}
				return false;
			}
			// several data lines without separating blank lines
			for (String part : trimmed.split("\n")) {
				String p = part.trim();
				if (p.equals("[DONE]")) {
					done = true;
					return true;
				}
				JsonObject json = Json.parseObject(p);
				if (json != null) {
					chunk(json);
				}
			}
			return false;
		}

		private void chunk(JsonObject json) {
			LlmException error = embeddedError(json);
			if (error != null) {
				throw error;
			}
			String m = Json.str(json, "model");
			if (!isBlank(m)) {
				model = m;
			}
			JsonObject usage = Json.obj(json, "usage");
			if (usage != null) {
				inputTokens = Json.integer(usage, "prompt_tokens", inputTokens);
				outputTokens = Json.integer(usage, "completion_tokens", outputTokens);
			}
			JsonArray choices = Json.arr(json, "choices");
			if (choices == null) {
				return;
			}
			for (JsonElement element : choices) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject choice = element.getAsJsonObject();
				if (Json.integer(choice, "index", 0) != 0) {
					continue;
				}
				JsonObject delta = Json.obj(choice, "delta");
				if (delta == null) {
					delta = Json.obj(choice, "message");
				}
				boolean changed = false;
				if (delta != null) {
					String reasoning = reasoningOf(delta);
					if (!isEmpty(reasoning)) {
						addReasoning(reasoning);
						changed = true;
					}
					String content = contentText(delta.get("content"));
					if (!content.isEmpty()) {
						addText(content);
						changed = true;
					}
					String r = Json.str(delta, "refusal");
					if (!isEmpty(r)) {
						refusal.append(r);
						changed = true;
					}
				}
				String finish = Json.str(choice, "finish_reason");
				if (!isEmpty(finish)) {
					finishReason = finish;
				}
				if (changed) {
					progress();
				}
			}
		}

		@Override
		LlmResponse finish() {
			String visible = text.finalText();
			boolean hasRefusal = !refusal.isEmpty();
			String result = hasRefusal && !refusal.toString().isBlank() ? refusal.toString() : visible;
			boolean complete = done || finishReason != null;
			return new LlmResponse(result, complete ? finishReason : LlmResponse.INCOMPLETE_STREAM, modelOf(model),
					inputTokens, outputTokens, !complete || "length".equals(finishReason),
					hasRefusal || "content_filter".equals(finishReason));
		}
	}
}
