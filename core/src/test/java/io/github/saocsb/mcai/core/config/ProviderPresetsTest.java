package io.github.saocsb.mcai.core.config;

import io.github.saocsb.mcai.core.llm.ApiStyle;
import io.github.saocsb.mcai.core.llm.ProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderPresetsTest {
	@Test
	void idsInDisplayOrder() {
		assertEquals(List.of("deepseek", "qwen", "qwen_intl", "moonshot", "moonshot_intl", "zhipu", "zai", "siliconflow",
				"volcengine", "openai", "anthropic", "gemini", "openrouter", "ollama", "lmstudio", "custom"),
				List.copyOf(ProviderPresets.all().keySet()));
		assertTrue(ProviderPresets.all().containsKey(ProviderPresets.DEFAULT_PROVIDER));
	}

	@Test
	void freshCopiesEachCall() {
		ProviderSettings a = ProviderPresets.all().get("deepseek");
		a.extraBody.clear();
		assertNotSame(a, ProviderPresets.all().get("deepseek"));
		assertFalse(ProviderPresets.all().get("deepseek").extraBody.isEmpty());
	}

	@Test
	void commonDefaults() {
		ProviderPresets.all().forEach((id, s) -> {
			assertTrue(s.stream, id);
			assertNull(s.temperature, id);
			int expectedTimeout = id.equals("openai") || id.equals("anthropic") ? 300 : 180;
			assertEquals(expectedTimeout, s.timeoutSeconds, id);
			assertEquals(id.equals("anthropic") ? "anthropic" : "openai", s.apiStyle, id);
			assertFalse(s.echoReasoningContent, id);
		});
	}

	@Test
	void keyPresets() {
		Map<String, ProviderSettings> all = ProviderPresets.all();
		ProviderSettings deepseek = all.get("deepseek");
		assertEquals("https://api.deepseek.com/v1", deepseek.baseUrl);
		assertEquals("deepseek-v4-pro", deepseek.model);
		assertEquals(Map.of("type", "disabled"), deepseek.extraBody.get("thinking"));
		assertEquals("DEEPSEEK_API_KEY", deepseek.apiKeyEnv);
		assertEquals(16384, deepseek.maxTokens);
		assertEquals("max_tokens", deepseek.maxTokensField);

		ProviderSettings moonshot = all.get("moonshot");
		assertEquals("https://api.moonshot.cn/v1", moonshot.baseUrl);
		assertEquals("max_completion_tokens", moonshot.maxTokensField);
		assertEquals(32768, moonshot.maxTokens);
		assertEquals("https://api.moonshot.ai/v1", all.get("moonshot_intl").baseUrl);
		assertEquals("kimi-k3", all.get("moonshot_intl").model);

		ProviderSettings openai = all.get("openai");
		assertEquals("gpt-6.1-sol", openai.model);
		assertEquals("max_completion_tokens", openai.maxTokensField);
		assertEquals(32000, openai.maxTokens);

		ProviderSettings anthropic = all.get("anthropic");
		ProviderConfig config = anthropic.resolve("anthropic", k -> null);
		assertEquals(ApiStyle.ANTHROPIC, config.apiStyle());
		assertEquals("https://api.anthropic.com", config.baseUrl());
		assertEquals("claude-opus-5-5", config.model());
		assertEquals("medium", config.effort());
		assertEquals(32000, config.maxTokens());
		assertEquals("ANTHROPIC_API_KEY", anthropic.apiKeyEnv);

		ProviderSettings openrouter = all.get("openrouter");
		assertEquals("anthropic/claude-opus-5.5", openrouter.model);
		assertEquals("MC-AI", openrouter.extraHeaders.get("X-Title"));
		assertEquals("https://github.com/SAOCSB/mc-ai", openrouter.extraHeaders.get("HTTP-Referer"));

		assertEquals(0, all.get("ollama").maxTokens);
		assertEquals("http://localhost:11434/v1", all.get("ollama").baseUrl);
		assertEquals("qwen3", all.get("ollama").model);
		assertEquals("", all.get("ollama").apiKeyEnv);
		assertEquals(0, all.get("lmstudio").maxTokens);
		assertEquals("local-model", all.get("lmstudio").model);
		assertEquals("", all.get("custom").baseUrl);
		assertEquals("", all.get("custom").model);
		assertEquals("https://ark.cn-beijing.volces.com/api/v3", all.get("volcengine").baseUrl);
		assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", all.get("gemini").baseUrl);
	}
}
