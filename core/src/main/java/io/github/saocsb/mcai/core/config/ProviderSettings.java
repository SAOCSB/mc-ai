package io.github.saocsb.mcai.core.config;

import io.github.saocsb.mcai.core.llm.ProviderConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** One provider entry in {@code config/mcai.json} (Gson-mapped; field names are the JSON keys). */
public final class ProviderSettings {
	/** "openai" or "anthropic". */
	public String apiStyle = "openai";
	public String baseUrl = "";
	public String apiKey = "";
	/** Environment variable consulted when {@link #apiKey} is blank, e.g. "DEEPSEEK_API_KEY". */
	public String apiKeyEnv = "";
	public String model = "";
	public int maxTokens = 8192;
	/** OpenAI-style only: "max_tokens" or "max_completion_tokens". */
	public String maxTokensField = "max_tokens";
	public Double temperature = null;
	public String effort = null;
	public boolean stream = true;
	public int timeoutSeconds = 180;
	public Map<String, String> extraHeaders = new LinkedHashMap<>();
	public Map<String, Object> extraBody = new LinkedHashMap<>();

	/** Deep copy. */
	public ProviderSettings copy() {
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * Resolves to an immutable {@link ProviderConfig}: unknown apiStyle -> openai; blank apiKey -> value of
	 * {@code env.apply(apiKeyEnv)} or {@code env.apply("MCAI_API_KEY")} (blank if none); trailing '/' removed
	 * from baseUrl; timeoutSeconds clamped to 10..900; maxTokens clamped to >= 0; null maps -> empty.
	 */
	public ProviderConfig resolve(String id, Function<String, String> env) {
		throw new UnsupportedOperationException("TODO");
	}
}
