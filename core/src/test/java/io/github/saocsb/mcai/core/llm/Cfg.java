package io.github.saocsb.mcai.core.llm;

import java.util.Map;

/** Mutable builder of {@link ProviderConfig} for tests. */
final class Cfg {
	static final String KEY = "sk-test-secret-0123456789";

	String id = "test";
	ApiStyle style = ApiStyle.OPENAI;
	String baseUrl;
	String apiKey = KEY;
	String model = "test-model";
	int maxTokens = 1000;
	String maxTokensField = "max_tokens";
	Double temperature;
	String effort;
	boolean stream = true;
	int timeoutSeconds = 20;
	Map<String, String> extraHeaders = Map.of();
	Map<String, Object> extraBody = Map.of();
	boolean echoReasoningContent;

	static Cfg openai(String baseUrl) {
		Cfg c = new Cfg();
		c.baseUrl = baseUrl;
		return c;
	}

	static Cfg anthropic(String baseUrl) {
		Cfg c = openai(baseUrl);
		c.style = ApiStyle.ANTHROPIC;
		return c;
	}

	Cfg stream(boolean value) {
		stream = value;
		return this;
	}

	ProviderConfig build() {
		return new ProviderConfig(id, style, baseUrl, apiKey, model, maxTokens, maxTokensField, temperature, effort,
				stream, timeoutSeconds, extraHeaders, extraBody, echoReasoningContent);
	}

	/** A client with retry delays scaled down 100x. */
	HttpLlmClient client() {
		return client(0.01);
	}

	HttpLlmClient client(double backoffScale) {
		ProviderConfig config = build();
		return style == ApiStyle.OPENAI
				? new OpenAiCompatibleClient(config, Http.CLIENT, backoffScale)
				: new AnthropicClient(config, Http.CLIENT, backoffScale);
	}

	/** One shared HTTP client, as the mod would use. */
	static final class Http {
		static final java.net.http.HttpClient CLIENT = LlmClients.newHttpClient(null);

		private Http() {
		}
	}
}
