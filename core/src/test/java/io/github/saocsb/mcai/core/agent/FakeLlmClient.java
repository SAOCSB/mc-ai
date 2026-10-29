package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.llm.LlmClient;
import io.github.saocsb.mcai.core.llm.LlmProgressListener;
import io.github.saocsb.mcai.core.llm.LlmRequest;
import io.github.saocsb.mcai.core.llm.LlmResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Scripted {@link LlmClient}: each call takes the next behaviour; records every request. */
final class FakeLlmClient implements LlmClient {
	/** One received call; the test may drive its progress listener and complete its future. */
	static final class Call {
		final LlmRequest request;
		final LlmProgressListener listener;
		final CompletableFuture<LlmResponse> future = new CompletableFuture<>();

		Call(LlmRequest request, LlmProgressListener listener) {
			this.request = request;
			this.listener = listener;
		}

		void progress(int textChars, int reasoningChars, String partial) {
			listener.onProgress(textChars, reasoningChars, partial);
		}

		void reply(String text) {
			future.complete(response(text));
		}
	}

	@FunctionalInterface
	interface Behavior {
		void handle(Call call);
	}

	final List<Call> calls = new CopyOnWriteArrayList<>();
	private final ConcurrentLinkedDeque<Behavior> behaviors = new ConcurrentLinkedDeque<>();

	FakeLlmClient then(Behavior behavior) {
		behaviors.add(behavior);
		return this;
	}

	FakeLlmClient reply(String text) {
		return reply(response(text));
	}

	FakeLlmClient reply(LlmResponse response) {
		return then(c -> c.future.complete(response));
	}

	/** Completes from another thread after a delay. */
	FakeLlmClient replyAfter(long millis, String text) {
		return then(c -> CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS)
				.execute(() -> c.future.complete(response(text))));
	}

	FakeLlmClient fail(Throwable error) {
		return then(c -> c.future.completeExceptionally(error));
	}

	/** Leaves the call pending; the test completes it via {@link #awaitCall}. */
	FakeLlmClient hold() {
		return then(c -> {
		});
	}

	@Override
	public CompletableFuture<LlmResponse> complete(LlmRequest request, LlmProgressListener listener) {
		Call call = new Call(request, listener);
		calls.add(call);
		Behavior behavior = behaviors.poll();
		if (behavior == null) {
			throw new AssertionError("unexpected LLM call #" + calls.size());
		}
		behavior.handle(call);
		return call.future;
	}

	Call awaitCall(int index) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (calls.size() <= index) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("LLM call #" + (index + 1) + " never arrived");
			}
			Thread.sleep(2);
		}
		return calls.get(index);
	}

	static LlmResponse response(String text) {
		return new LlmResponse(text, "end_turn", "fake-model", 100, 50, false, false);
	}

	static LlmResponse truncated(String text) {
		return new LlmResponse(text, "max_tokens", "fake-model", 100, 4096, true, false);
	}

	static LlmResponse refused(String text) {
		return new LlmResponse(text, "refusal", "fake-model", 100, 5, false, true);
	}
}
