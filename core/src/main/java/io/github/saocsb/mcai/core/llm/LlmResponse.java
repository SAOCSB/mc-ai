package io.github.saocsb.mcai.core.llm;

/**
 * Final result of a completion.
 *
 * @param text       the visible assistant text (reasoning/thinking content excluded)
 * @param stopReason provider stop/finish reason as reported (e.g. "end_turn", "stop", "length")
 * @param truncated  output stopped because of the token limit
 * @param refused    the model declined (Anthropic stop_reason "refusal", OpenAI finish_reason "content_filter")
 */
public record LlmResponse(String text, String stopReason, String model, int inputTokens, int outputTokens,
		boolean truncated, boolean refused) {
}
