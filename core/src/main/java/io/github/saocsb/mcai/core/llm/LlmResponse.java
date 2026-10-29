package io.github.saocsb.mcai.core.llm;

/**
 * Final result of a completion.
 *
 * @param text       the visible assistant text (reasoning/thinking content excluded)
 * @param stopReason provider stop/finish reason as reported (e.g. "end_turn", "stop", "length"), or
 *                   {@link #INCOMPLETE_STREAM}
 * @param truncated  output stopped because of the token limit, or the stream ended early
 * @param refused    the model declined (Anthropic stop_reason "refusal", OpenAI finish_reason "content_filter")
 */
public record LlmResponse(String text, String stopReason, String model, int inputTokens, int outputTokens,
		boolean truncated, boolean refused) {
	/**
	 * Stop reason of a streamed response that ended without its terminal event (OpenAI style: {@code [DONE]} or a
	 * finish_reason; Anthropic: {@code message_stop} or a stop_reason). Such a response is also {@code truncated}.
	 */
	public static final String INCOMPLETE_STREAM = "incomplete_stream";
}
