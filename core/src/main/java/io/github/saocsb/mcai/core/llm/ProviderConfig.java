package io.github.saocsb.mcai.core.llm;

import java.util.Map;

/**
 * Resolved settings for one LLM provider.
 *
 * @param id                   preset/provider id, e.g. "deepseek" (used in messages only)
 * @param baseUrl              OPENAI: up to but excluding "/chat/completions" (e.g. "https://api.deepseek.com/v1");
 *                             ANTHROPIC: up to but excluding "/v1/messages" (e.g. "https://api.anthropic.com")
 * @param maxTokens            output token cap; 0 = do not send a cap (provider default)
 * @param maxTokensField       OPENAI only: "max_tokens" or "max_completion_tokens"
 * @param temperature          OPENAI only; null = omit (never sent to ANTHROPIC: API version 2026-10-21 rejects it)
 * @param effort               ANTHROPIC: output_config.effort ("low".."max"); OPENAI: sent as reasoning_effort when non-null
 * @param timeoutSeconds       time allowed for the response headers, and the longest silence while reading the body
 * @param extraHeaders         extra HTTP headers (never logged)
 * @param extraBody            extra top-level JSON fields merged into the request body (values: String/Number/Boolean/Map/List);
 *                             they override generated fields, and a null value removes the field
 * @param echoReasoningContent OPENAI only: every assistant message carries {@code "reasoning_content": " "}
 *                             (DeepSeek V4 thinking mode rejects multi-turn requests without it)
 */
public record ProviderConfig(String id, ApiStyle apiStyle, String baseUrl, String apiKey, String model,
		int maxTokens, String maxTokensField, Double temperature, String effort, boolean stream,
		int timeoutSeconds, Map<String, String> extraHeaders, Map<String, Object> extraBody,
		boolean echoReasoningContent) {

	/** Same as the canonical constructor with {@code echoReasoningContent = false}. */
	public ProviderConfig(String id, ApiStyle apiStyle, String baseUrl, String apiKey, String model,
			int maxTokens, String maxTokensField, Double temperature, String effort, boolean stream,
			int timeoutSeconds, Map<String, String> extraHeaders, Map<String, Object> extraBody) {
		this(id, apiStyle, baseUrl, apiKey, model, maxTokens, maxTokensField, temperature, effort, stream,
				timeoutSeconds, extraHeaders, extraBody, false);
	}
}
