package io.github.saocsb.mcai.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in provider presets written into a fresh config. Ids, in display order: deepseek, qwen, qwen_intl,
 * moonshot, moonshot_intl, zhipu, zai, siliconflow, volcengine, openai, anthropic, gemini, openrouter, ollama,
 * lmstudio, custom. Model names change often; they are plain strings users can edit.
 */
public final class ProviderPresets {
	/** Preset used by a fresh config. */
	public static final String DEFAULT_PROVIDER = "deepseek";

	private ProviderPresets() {
	}

	/** Fresh copies of all presets, in display order (LinkedHashMap). */
	public static Map<String, ProviderSettings> all() {
		Map<String, ProviderSettings> map = new LinkedHashMap<>();

		// DeepSeek V4 thinks by default; thinking mode would require echoing reasoning_content on every
		// assistant turn (see ProviderSettings.echoReasoningContent), so the preset turns it off.
		ProviderSettings deepseek = openAi("https://api.deepseek.com/v1", "deepseek-v4-pro", "DEEPSEEK_API_KEY", 16384);
		deepseek.extraBody.put("thinking", new LinkedHashMap<>(Map.of("type", "disabled")));
		map.put("deepseek", deepseek);

		map.put("qwen", openAi("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.8-max", "DASHSCOPE_API_KEY", 16384));
		map.put("qwen_intl", openAi("https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "qwen3.8-max", "DASHSCOPE_API_KEY", 16384));

		ProviderSettings moonshot = openAi("https://api.moonshot.cn/v1", "kimi-k3", "MOONSHOT_API_KEY", 32768);
		moonshot.maxTokensField = "max_completion_tokens";
		map.put("moonshot", moonshot);
		ProviderSettings moonshotIntl = moonshot.copy();
		moonshotIntl.baseUrl = "https://api.moonshot.ai/v1";
		map.put("moonshot_intl", moonshotIntl);

		map.put("zhipu", openAi("https://open.bigmodel.cn/api/paas/v4", "glm-5.3", "ZHIPUAI_API_KEY", 16384));
		map.put("zai", openAi("https://api.z.ai/api/paas/v4", "glm-5.3", "ZAI_API_KEY", 16384));
		map.put("siliconflow", openAi("https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V4-Flash", "SILICONFLOW_API_KEY", 16384));
		map.put("volcengine", openAi("https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-2-1-pro-260628", "ARK_API_KEY", 16384));

		ProviderSettings openai = openAi("https://api.openai.com/v1", "gpt-6.1-sol", "OPENAI_API_KEY", 32000);
		openai.maxTokensField = "max_completion_tokens";
		openai.timeoutSeconds = 300;
		map.put("openai", openai);

		ProviderSettings anthropic = new ProviderSettings();
		anthropic.apiStyle = "anthropic";
		anthropic.baseUrl = "https://api.anthropic.com";
		anthropic.model = "claude-opus-5-5";
		anthropic.apiKeyEnv = "ANTHROPIC_API_KEY";
		anthropic.maxTokens = 32000;
		anthropic.effort = "medium";
		anthropic.timeoutSeconds = 300;
		map.put("anthropic", anthropic);

		// Gemini 3 always thinks and counts thinking against the output cap.
		map.put("gemini", openAi("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-pro-latest", "GEMINI_API_KEY", 32768));

		ProviderSettings openrouter = openAi("https://openrouter.ai/api/v1", "anthropic/claude-opus-5.5", "OPENROUTER_API_KEY", 32000);
		openrouter.extraHeaders.put("X-Title", "MC-AI");
		openrouter.extraHeaders.put("HTTP-Referer", "https://github.com/SAOCSB/mc-ai");
		map.put("openrouter", openrouter);

		map.put("ollama", openAi("http://localhost:11434/v1", "qwen3", "", 0));
		map.put("lmstudio", openAi("http://127.0.0.1:1234/v1", "local-model", "", 0));
		map.put("custom", openAi("", "", "", 8192));
		return map;
	}

	private static ProviderSettings openAi(String baseUrl, String model, String apiKeyEnv, int maxTokens) {
		ProviderSettings s = new ProviderSettings();
		s.apiStyle = "openai";
		s.baseUrl = baseUrl;
		s.model = model;
		s.apiKeyEnv = apiKeyEnv;
		s.maxTokens = maxTokens;
		s.maxTokensField = "max_tokens";
		s.stream = true;
		s.timeoutSeconds = 180;
		return s;
	}
}
