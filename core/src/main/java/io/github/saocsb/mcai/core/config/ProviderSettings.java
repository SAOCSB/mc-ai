package io.github.saocsb.mcai.core.config;

import io.github.saocsb.mcai.core.llm.ApiStyle;
import io.github.saocsb.mcai.core.llm.ProviderConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
	/** Extra top-level request fields; they override generated ones, and a null value removes a field. */
	public Map<String, Object> extraBody = new LinkedHashMap<>();
	/**
	 * OpenAI-style only: send {@code "reasoning_content": " "} with every assistant message. Needed by DeepSeek V4
	 * when thinking is enabled, which rejects multi-turn requests whose assistant turns lack it.
	 */
	public boolean echoReasoningContent = false;

	/** Deep copy. */
	public ProviderSettings copy() {
		ProviderSettings c = new ProviderSettings();
		c.apiStyle = apiStyle;
		c.baseUrl = baseUrl;
		c.apiKey = apiKey;
		c.apiKeyEnv = apiKeyEnv;
		c.model = model;
		c.maxTokens = maxTokens;
		c.maxTokensField = maxTokensField;
		c.temperature = temperature;
		c.effort = effort;
		c.stream = stream;
		c.timeoutSeconds = timeoutSeconds;
		c.extraHeaders = extraHeaders == null ? null : new LinkedHashMap<>(extraHeaders);
		c.extraBody = extraBody == null ? null : deepCopyMap(extraBody, false);
		c.echoReasoningContent = echoReasoningContent;
		return c;
	}

	/**
	 * Resolves to an immutable {@link ProviderConfig}: unknown apiStyle -> openai; blank apiKey -> value of
	 * {@code env.apply(apiKeyEnv)} or {@code env.apply("MCAI_API_KEY")} (blank if none); trailing '/' removed
	 * from baseUrl; timeoutSeconds clamped to 10..900; maxTokens clamped to >= 0; null maps -> empty.
	 */
	public ProviderConfig resolve(String id, Function<String, String> env) {
		ApiStyle style = apiStyle != null && apiStyle.trim().toLowerCase(Locale.ROOT).equals("anthropic")
				? ApiStyle.ANTHROPIC : ApiStyle.OPENAI;
		String key = trim(apiKey);
		if (key.isEmpty() && env != null) {
			if (!trim(apiKeyEnv).isEmpty()) {
				key = trim(env.apply(apiKeyEnv.trim()));
			}
			if (key.isEmpty()) {
				key = trim(env.apply("MCAI_API_KEY"));
			}
		}
		String base = trim(baseUrl);
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		String field = trim(maxTokensField);
		String effortValue = trim(effort);
		Map<String, String> headers = new LinkedHashMap<>();
		if (extraHeaders != null) {
			extraHeaders.forEach((k, v) -> {
				if (k != null && v != null) {
					headers.put(k, v);
				}
			});
		}
		Map<String, Object> body = extraBody == null ? Map.of() : deepCopyMap(extraBody, true);
		return new ProviderConfig(id, style, base, key, trim(model), Math.max(0, maxTokens),
				field.isEmpty() ? "max_tokens" : field, temperature, effortValue.isEmpty() ? null : effortValue,
				stream, Math.clamp(timeoutSeconds, 10, 900), Collections.unmodifiableMap(headers), body,
				echoReasoningContent);
	}

	private static String trim(String s) {
		return s == null ? "" : s.trim();
	}

	private static Map<String, Object> deepCopyMap(Map<?, ?> map, boolean immutable) {
		Map<String, Object> copy = new LinkedHashMap<>();
		map.forEach((k, v) -> {
			if (k != null) {
				copy.put(k.toString(), deepCopyValue(v, immutable));
			}
		});
		return immutable ? Collections.unmodifiableMap(copy) : copy;
	}

	private static Object deepCopyValue(Object value, boolean immutable) {
		if (value instanceof Map<?, ?> map) {
			return deepCopyMap(map, immutable);
		}
		if (value instanceof List<?> list) {
			List<Object> copy = new ArrayList<>(list.size());
			for (Object item : list) {
				copy.add(deepCopyValue(item, immutable));
			}
			return immutable ? Collections.unmodifiableList(copy) : copy;
		}
		return value;
	}
}
