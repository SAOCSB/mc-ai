package io.github.saocsb.mcai.core.llm;

import java.util.concurrent.CompletableFuture;

/**
 * A chat-completion client. Implementations are thread-safe and stateless across requests.
 * Cancelling the returned future aborts the HTTP exchange.
 * The future fails with {@link LlmException} for provider/network errors.
 */
public interface LlmClient {
	CompletableFuture<LlmResponse> complete(LlmRequest request, LlmProgressListener listener);
}
