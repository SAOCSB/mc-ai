package io.github.saocsb.mcai.core.llm;

import java.net.http.HttpClient;

/** Creates clients for a {@link ProviderConfig}. */
public final class LlmClients {
	private LlmClients() {
	}

	public static LlmClient create(ProviderConfig config, HttpClient http) {
		return switch (config.apiStyle()) {
			case OPENAI -> new OpenAiCompatibleClient(config, http);
			case ANTHROPIC -> new AnthropicClient(config, http);
		};
	}
}
