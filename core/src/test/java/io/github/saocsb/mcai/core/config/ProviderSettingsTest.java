package io.github.saocsb.mcai.core.config;

import io.github.saocsb.mcai.core.llm.ApiStyle;
import io.github.saocsb.mcai.core.llm.ProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSettingsTest {
	private static Function<String, String> env(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return map::get;
	}

	@Test
	void apiKeyFallsBackToEnvironment() {
		ProviderSettings s = new ProviderSettings();
		s.apiKeyEnv = "DEEPSEEK_API_KEY";
		assertEquals("from-env", s.resolve("deepseek", env("DEEPSEEK_API_KEY", " from-env\n", "MCAI_API_KEY", "generic")).apiKey());
		assertEquals("generic", s.resolve("deepseek", env("MCAI_API_KEY", "generic")).apiKey());
		assertEquals("", s.resolve("deepseek", env()).apiKey());
		s.apiKey = " explicit ";
		assertEquals("explicit", s.resolve("deepseek", env("DEEPSEEK_API_KEY", "from-env")).apiKey());
		s.apiKey = null;
		s.apiKeyEnv = null;
		assertEquals("generic", s.resolve("x", env("MCAI_API_KEY", "generic")).apiKey());
	}

	@Test
	void resolveNormalizes() {
		ProviderSettings s = new ProviderSettings();
		s.apiStyle = "something";
		s.baseUrl = " https://api.example.com/v1// ";
		s.model = " m ";
		s.timeoutSeconds = 5;
		s.maxTokens = -3;
		s.maxTokensField = " ";
		s.effort = "  ";
		s.extraHeaders = null;
		s.extraBody = null;
		s.echoReasoningContent = true;
		ProviderConfig c = s.resolve("id", env());
		assertEquals("id", c.id());
		assertEquals(ApiStyle.OPENAI, c.apiStyle());
		assertEquals("https://api.example.com/v1", c.baseUrl());
		assertEquals("m", c.model());
		assertEquals(10, c.timeoutSeconds());
		assertEquals(0, c.maxTokens());
		assertEquals("max_tokens", c.maxTokensField());
		assertNull(c.effort());
		assertTrue(c.extraHeaders().isEmpty());
		assertTrue(c.extraBody().isEmpty());
		assertTrue(c.echoReasoningContent());

		s.apiStyle = " Anthropic ";
		s.timeoutSeconds = 10_000;
		assertEquals(ApiStyle.ANTHROPIC, s.resolve("id", env()).apiStyle());
		assertEquals(900, s.resolve("id", env()).timeoutSeconds());
	}

	@Test
	void resolvedMapsAreImmutableSnapshots() {
		ProviderSettings s = new ProviderSettings();
		s.extraHeaders.put("X-A", "1");
		Map<String, Object> nested = new LinkedHashMap<>(Map.of("type", "disabled"));
		s.extraBody.put("thinking", nested);
		s.extraBody.put("remove_me", null);
		ProviderConfig c = s.resolve("id", env());
		nested.put("type", "enabled");
		s.extraHeaders.put("X-B", "2");
		assertEquals(Map.of("type", "disabled"), c.extraBody().get("thinking"));
		assertTrue(c.extraBody().containsKey("remove_me"));
		assertEquals(Map.of("X-A", "1"), c.extraHeaders());
		assertThrows(UnsupportedOperationException.class, () -> c.extraBody().put("x", 1));
	}

	@Test
	void copyIsDeep() {
		ProviderSettings s = ProviderPresets.all().get("deepseek");
		List<Object> list = new ArrayList<>(List.of("a"));
		s.extraBody.put("stop", list);
		s.extraHeaders.put("X-A", "1");
		ProviderSettings c = s.copy();
		assertNotSame(s.extraBody, c.extraBody);
		@SuppressWarnings("unchecked")
		Map<String, Object> thinking = (Map<String, Object>) c.extraBody.get("thinking");
		thinking.put("type", "enabled");
		((List<Object>) c.extraBody.get("stop")).add("b");
		c.extraHeaders.put("X-B", "2");
		assertEquals(Map.of("type", "disabled"), s.extraBody.get("thinking"));
		assertEquals(List.of("a"), s.extraBody.get("stop"));
		assertEquals(Map.of("X-A", "1"), s.extraHeaders);
		assertEquals(s.model, c.model);
		assertEquals(s.maxTokens, c.maxTokens);
	}
}
