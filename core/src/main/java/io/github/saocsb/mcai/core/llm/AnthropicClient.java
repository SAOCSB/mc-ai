package io.github.saocsb.mcai.core.llm;

import java.net.http.HttpClient;
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
 *
 * <p>Streaming: SSE events {@code content_block_delta} with {@code text_delta} (visible text) or
 * {@code thinking_delta} (reasoning progress), {@code message_delta} (stop_reason, usage),
 * {@code error} (fail). Non-text blocks (thinking, fallback, ...) are ignored. stop_reason "max_tokens" =>
 * truncated; "refusal" => refused. Retries like {@link OpenAiCompatibleClient}, also on 529.
 */
public final class AnthropicClient implements LlmClient {
	public static final String API_VERSION = "2026-10-21";
	public static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

	public AnthropicClient(ProviderConfig config, HttpClient http) {
		throw new UnsupportedOperationException("TODO");
	}

	@Override
	public CompletableFuture<LlmResponse> complete(LlmRequest request, LlmProgressListener listener) {
		throw new UnsupportedOperationException("TODO");
	}
}
