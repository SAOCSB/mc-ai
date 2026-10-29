package io.github.saocsb.mcai.core.llm;

/** Streaming progress callback; may be invoked from an HTTP worker thread, often. */
@FunctionalInterface
public interface LlmProgressListener {
	LlmProgressListener NONE = (textChars, reasoningChars, partialText) -> {
	};

	/**
	 * @param textChars      visible text characters received so far
	 * @param reasoningChars reasoning/thinking characters received so far (0 if the provider hides them)
	 * @param partialText    the visible text received so far
	 */
	void onProgress(int textChars, int reasoningChars, String partialText);
}
