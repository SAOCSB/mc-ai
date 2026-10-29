package io.github.saocsb.mcai.core.llm;

import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI Chat Completions client (also DeepSeek, Qwen/DashScope, Kimi, GLM, SiliconFlow, OpenRouter,
 * Gemini's OpenAI endpoint, Ollama, LM Studio, Volcengine Ark...).
 *
 * <p>Request: {@code {"model", "messages":[{"role":"system"...}, ...], <maxTokensField>, "temperature"?,
 * "reasoning_effort"?, "stream"?, ...extraBody}}; header {@code Authorization: Bearer <key>} (omitted when
 * the key is blank, for local servers). Streaming parses SSE {@code data:} lines until {@code [DONE]},
 * accumulating {@code choices[0].delta.content}; {@code delta.reasoning_content} / {@code delta.reasoning}
 * count as reasoning progress only. Visible text has any leading {@code <think>...</think>} block removed.
 * finish_reason "length" => truncated; "content_filter" => refused.
 *
 * <p>Retries up to 2 times on retryable failures before any response body was consumed, with backoff
 * (honouring Retry-After up to 20 s). Error bodies {@code {"error":{"message":...}}} are surfaced in the
 * exception message.
 */
public final class OpenAiCompatibleClient implements LlmClient {
	public OpenAiCompatibleClient(ProviderConfig config, HttpClient http) {
		throw new UnsupportedOperationException("TODO");
	}

	@Override
	public CompletableFuture<LlmResponse> complete(LlmRequest request, LlmProgressListener listener) {
		throw new UnsupportedOperationException("TODO");
	}
}
