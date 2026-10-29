package io.github.saocsb.mcai.core.llm;

import java.util.concurrent.CompletableFuture;

/**
 * A chat-completion client. Implementations are thread-safe and stateless across requests.
 * Cancelling the returned future aborts the HTTP exchange.
 * The future fails with {@link LlmException} for provider/network errors. It completes on an HTTP client or
 * common-pool thread, so callers should hop to their own executor for heavy work.
 */
public interface LlmClient {
	CompletableFuture<LlmResponse> complete(LlmRequest request, LlmProgressListener listener);
}
