package io.github.saocsb.mcai.core.agent;

import java.util.concurrent.CompletableFuture;

/** A running request; {@link #cancel()} aborts the in-flight LLM call or script and completes the future exceptionally with CancellationException. */
public interface AgentTask {
	CompletableFuture<AgentOutcome> future();

	void cancel();
}
