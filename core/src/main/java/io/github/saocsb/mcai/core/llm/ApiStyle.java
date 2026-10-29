package io.github.saocsb.mcai.core.llm;

/** Wire protocol of a provider. */
public enum ApiStyle {
	/** OpenAI Chat Completions: POST {baseUrl}/chat/completions, Bearer auth. */
	OPENAI,
	/** Anthropic Messages API: POST {baseUrl}/v1/messages, x-api-key auth. */
	ANTHROPIC
}
