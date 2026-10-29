package io.github.saocsb.mcai.core.config;

import java.util.Map;

/**
 * Built-in provider presets written into a fresh config. Ids: openai, anthropic, deepseek, qwen,
 * qwen_intl, moonshot, zhipu, siliconflow, openrouter, gemini, volcengine, ollama, lmstudio, custom.
 */
public final class ProviderPresets {
	/** Preset used by a fresh config. */
	public static final String DEFAULT_PROVIDER = "deepseek";

	private ProviderPresets() {
	}

	/** Fresh copies of all presets, in display order (LinkedHashMap). */
	public static Map<String, ProviderSettings> all() {
		throw new UnsupportedOperationException("TODO");
	}
}
