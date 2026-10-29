package io.github.saocsb.mcai.core.llm;

/**
 * Accumulates one streamed response: visible text (with {@code <think>} blocks split off), reasoning
 * progress, and progress callbacks. Driven by a single thread at a time.
 */
abstract class StreamState {
	final ThinkFilter text = new ThinkFilter();
	private final LlmProgressListener listener;
	private int providerReasoningChars;
	private String partial = "";

	StreamState(LlmProgressListener listener) {
		this.listener = listener == null ? LlmProgressListener.NONE : listener;
	}

	/**
	 * Handles one SSE event.
	 *
	 * @return true when this was the final event of the response
	 * @throws LlmException for an error event
	 */
	abstract boolean onEvent(String event, String data);

	/** Builds the result once the stream ended (normally or at the final event). */
	abstract LlmResponse finish();

	/** Adds visible content (may still contain think tags). */
	final void addText(String s) {
		if (s != null && !s.isEmpty()) {
			text.append(s);
		}
	}

	/** Adds provider-reported reasoning (reasoning_content, thinking deltas...). */
	final void addReasoning(String s) {
		if (s != null) {
			providerReasoningChars += s.length();
		}
	}

	/** Reports progress to the listener; listener failures are ignored. */
	final void progress() {
		if (partial.length() != text.visibleLength()) {
			partial = text.visibleText();
		}
		try {
			listener.onProgress(partial.length(), providerReasoningChars + text.reasoningChars(), partial);
		} catch (RuntimeException ignored) {
			// a faulty listener must not break the stream
		}
	}
}
