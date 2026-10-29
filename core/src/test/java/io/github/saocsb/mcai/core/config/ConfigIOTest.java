package io.github.saocsb.mcai.core.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigIOTest {
	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("config").resolve("mcai.json");
	}

	private void write(String json) throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), json, StandardCharsets.UTF_8);
	}

	@Test
	void defaultsHaveAllPresets() {
		ModConfig c = ConfigIO.defaults();
		assertEquals(ProviderPresets.all().keySet(), c.providers.keySet());
		assertEquals("deepseek", c.provider);
		assertEquals(List.of("@ai"), c.chatPrefixes);
	}

	@Test
	void missingFileIsCreatedAndRoundTrips() throws IOException {
		ModConfig loaded = ConfigIO.load(file());
		assertTrue(Files.exists(file()));
		String written = Files.readString(file());
		assertEquals(ConfigIO.toJson(ConfigIO.defaults()), written);
		assertEquals(ProviderPresets.all().keySet(), loaded.providers.keySet());
		assertTrue(written.contains("\n  \"provider\": \"deepseek\""), "pretty printed");
		assertTrue(written.contains("\"temperature\": null"), "nulls kept for discoverability");
		assertTrue(written.contains("\"HTTP-Referer\": \"https://github.com/SAOCSB/mc-ai\""), "no HTML escaping");

		ModConfig again = ConfigIO.load(file());
		assertEquals(written, ConfigIO.toJson(again));
		try (var files = Files.list(file().getParent())) {
			assertEquals(1, files.count(), "temp files left behind");
		}
	}

	@Test
	void unchangedFileIsNotRewritten() throws IOException {
		ConfigIO.load(file());
		FileTime old = FileTime.fromMillis(1_000_000_000L);
		Files.setLastModifiedTime(file(), old);
		ConfigIO.load(file());
		assertEquals(old, Files.getLastModifiedTime(file()));
	}

	@Test
	void missingPresetsAreAddedWithoutClobberingUserEntries() throws IOException {
		write("""
				{
				  "provider": "mine",
				  "providers": {
				    "mine": {"apiStyle": "openai", "baseUrl": "http://my.host/v1", "model": "my-model", "apiKey": "k"},
				    "deepseek": {"apiKey": "sk-user", "model": "deepseek-v4-flash"},
				    "anthropic": null,
				    "openai": {"effort": "high", "extraBody": {"thinking_budget": 1024, "ratio": 0.5}}
				  },
				  "aiName": "Builder"
				}
				""");
		ModConfig c = ConfigIO.load(file());
		assertEquals("mine", c.provider);
		assertEquals("Builder", c.aiName);
		assertEquals("mine", c.providers.keySet().iterator().next());
		assertTrue(c.providers.keySet().containsAll(ProviderPresets.all().keySet()));

		ProviderSettings mine = c.providers.get("mine");
		assertEquals("http://my.host/v1", mine.baseUrl);
		assertEquals("my-model", mine.model);

		ProviderSettings deepseek = c.providers.get("deepseek");
		assertEquals("sk-user", deepseek.apiKey);
		assertEquals("deepseek-v4-flash", deepseek.model);
		assertEquals("https://api.deepseek.com/v1", deepseek.baseUrl, "missing fields come from the preset");
		assertEquals("DEEPSEEK_API_KEY", deepseek.apiKeyEnv);

		assertEquals("claude-opus-5-5", c.providers.get("anthropic").model, "null entry replaced by the preset");
		ProviderSettings openai = c.providers.get("openai");
		assertEquals("high", openai.effort);
		assertEquals("gpt-6.1-sol", openai.model);
		assertEquals(1024L, openai.extraBody.get("thinking_budget"));
		assertEquals(0.5, openai.extraBody.get("ratio"));

		String written = Files.readString(file());
		assertTrue(written.contains("\"thinking_budget\": 1024,"), written);
		assertTrue(written.contains("\"sk-user\""));
	}

	@Test
	void clampsAndNormalizes() throws IOException {
		write("""
				{
				  "provider": "nope",
				  "providers": {"custom": {"timeoutSeconds": 1, "maxTokens": -5, "extraHeaders": null, "extraBody": null, "apiStyle": "weird"}},
				  "language": "ZH_CN",
				  "replyVisibility": "everyone",
				  "chatPrefixes": [],
				  "maxHistoryMessages": 1000,
				  "maxRepairAttempts": -1,
				  "cooldownSeconds": 99999,
				  "maxConcurrentRequests": 0,
				  "proxy": null,
				  "build": {"maxBlocks": 0, "maxSpan": 1, "scriptTimeoutMillis": 999999, "blocksPerTick": 0,
				            "maxTickMillis": 1000, "frontDistance": 100, "undoHistory": 99, "confirmAboveBlocks": -1},
				  "commands": {"permissionLevel": 9, "maxPerRequest": 5000, "whitelist": ["give", null, " "]},
				  "permissions": {"useLevel": -1, "adminLevel": 7}
				}
				""");
		ModConfig c = ConfigIO.load(file());
		assertEquals("deepseek", c.provider);
		assertEquals("zh_cn", c.language);
		assertEquals("all", c.replyVisibility);
		assertEquals(List.of("@ai"), c.chatPrefixes);
		assertEquals(100, c.maxHistoryMessages);
		assertEquals(0, c.maxRepairAttempts);
		assertEquals(3600, c.cooldownSeconds);
		assertEquals(1, c.maxConcurrentRequests);
		assertEquals("", c.proxy);
		assertEquals(1, c.build.maxBlocks);
		assertEquals(8, c.build.maxSpan);
		assertEquals(120_000, c.build.scriptTimeoutMillis);
		assertEquals(1, c.build.blocksPerTick);
		assertEquals(200, c.build.maxTickMillis);
		assertEquals(64, c.build.frontDistance);
		assertEquals(50, c.build.undoHistory);
		assertEquals(0, c.build.confirmAboveBlocks);
		assertEquals(4, c.commands.permissionLevel);
		assertEquals(1000, c.commands.maxPerRequest);
		assertEquals(List.of("give"), c.commands.whitelist);
		assertEquals(0, c.permissions.useLevel);
		assertEquals(4, c.permissions.adminLevel);
		ProviderSettings custom = c.providers.get("custom");
		assertEquals(10, custom.timeoutSeconds);
		assertEquals(0, custom.maxTokens);
		assertEquals("openai", custom.apiStyle);
		assertEquals(Map.of(), custom.extraHeaders);
		assertEquals(Map.of(), custom.extraBody);

		assertEquals(c.build.maxSpan, ConfigIO.load(file()).build.maxSpan, "normalized values were written back");
	}

	@Test
	void nullSectionsGetDefaults() throws IOException {
		write("{\"build\": null, \"commands\": null, \"permissions\": null, \"providers\": null, \"chatPrefixes\": null,"
				+ " \"language\": null, \"aiName\": \"\"}");
		ModConfig c = ConfigIO.load(file());
		assertEquals(300_000, c.build.maxBlocks);
		assertTrue(c.commands.whitelist.contains("summon"));
		assertEquals(2, c.permissions.adminLevel);
		assertEquals(ProviderPresets.all().keySet(), c.providers.keySet());
		assertEquals(List.of("@ai"), c.chatPrefixes);
		assertEquals("auto", c.language);
		assertEquals("AI", c.aiName);
	}

	@Test
	void invalidJsonLeavesFileUntouched() throws IOException {
		String broken = "{\n  \"provider\": \"deepseek\",\n  \"maxHistoryMessages\": 12,,\n}";
		write(broken);
		byte[] before = Files.readAllBytes(file());
		IOException e = assertThrows(IOException.class, () -> ConfigIO.load(file()));
		assertTrue(e.getMessage().contains("mcai.json"), e.getMessage());
		assertTrue(e.getMessage().contains("line 3"), e.getMessage());
		assertArrayEquals(before, Files.readAllBytes(file()));

		write("{\"maxHistoryMessages\": \"lots\"}");
		assertThrows(IOException.class, () -> ConfigIO.load(file()));
		write("[1, 2]");
		assertThrows(IOException.class, () -> ConfigIO.load(file()));
		write("{\"a\": 1} // comment");
		assertThrows(IOException.class, () -> ConfigIO.load(file()));
		assertEquals("{\"a\": 1} // comment", Files.readString(file()));
	}

	@Test
	void emptyFileGetsDefaults() throws IOException {
		write("  \n");
		ModConfig c = ConfigIO.load(file());
		assertEquals("deepseek", c.provider);
		assertEquals(ConfigIO.toJson(ConfigIO.defaults()), Files.readString(file()));
	}

	@Test
	void saveReplacesAtomicallyAndKeepsUnknownProviders() throws IOException {
		ModConfig c = ConfigIO.defaults();
		ProviderSettings extra = new ProviderSettings();
		extra.model = "x";
		extra.extraBody.put("drop_me", null);
		c.providers.put("extra", extra);
		ConfigIO.save(file(), c);
		c.aiName = "Second";
		ConfigIO.save(file(), c);
		JsonObject json = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
		assertEquals("Second", json.get("aiName").getAsString());
		JsonObject loadedExtra = json.getAsJsonObject("providers").getAsJsonObject("extra");
		assertTrue(loadedExtra.getAsJsonObject("extraBody").has("drop_me"));
		ModConfig loaded = ConfigIO.load(file());
		assertTrue(loaded.providers.get("extra").extraBody.containsKey("drop_me"));
		assertNull(loaded.providers.get("extra").extraBody.get("drop_me"));
		assertFalse(Files.readString(file()).contains("\\u003c"));
	}

	@Test
	void presetMapsAreMergedKeyByKey() throws IOException {
		write("""
				{
				  "providers": {
				    "deepseek": {"extraBody": {"top_k": 5}},
				    "openrouter": {"extraHeaders": {"X-Title": "My Server"}},
				    "qwen": {"extraBody": {"enable_thinking": false}},
				    "moonshot": {"extraBody": null}
				  }
				}
				""");
		ModConfig c = ConfigIO.load(file());
		Map<String, Object> deepseek = c.providers.get("deepseek").extraBody;
		assertEquals(5L, deepseek.get("top_k"));
		assertEquals(Map.of("type", "disabled"), deepseek.get("thinking"), "preset key kept next to the user's");
		Map<String, String> headers = c.providers.get("openrouter").extraHeaders;
		assertEquals("My Server", headers.get("X-Title"), "the user's value wins");
		assertEquals("https://github.com/SAOCSB/mc-ai", headers.get("HTTP-Referer"));
		assertEquals(Map.of("enable_thinking", false), c.providers.get("qwen").extraBody);
		assertEquals(Map.of(), c.providers.get("moonshot").extraBody);

		write("{\"providers\": {\"deepseek\": {\"extraBody\": {\"thinking\": null}}}}");
		ProviderSettings optOut = ConfigIO.load(file()).providers.get("deepseek");
		assertTrue(optOut.extraBody.containsKey("thinking"));
		assertNull(optOut.extraBody.get("thinking"), "an explicit null still removes the preset's field");
	}

	@Test
	void buildSafetyOptionsAreNormalized() throws IOException {
		ModConfig defaults = ConfigIO.defaults();
		assertEquals(160, defaults.build.maxDistance);
		assertEquals(ModConfig.DEFAULT_DENIED_BLOCKS, defaults.build.deniedBlocks);
		assertTrue(defaults.build.deniedBlocks.containsAll(List.of("command_block", "structure_block", "jigsaw", "spawner",
				"trial_spawner")));
		assertFalse(defaults.commands.whitelist.contains("particle"));
		assertFalse(defaults.commands.whitelist.contains("say"));

		write("{\"build\": {\"maxDistance\": 1, \"deniedBlocks\": [\"tnt\", \" \", null]}}");
		ModConfig small = ConfigIO.load(file());
		assertEquals(8, small.build.maxDistance);
		assertEquals(List.of("tnt"), small.build.deniedBlocks);
		write("{\"build\": {\"maxDistance\": 100000, \"deniedBlocks\": null}}");
		ModConfig big = ConfigIO.load(file());
		assertEquals(4096, big.build.maxDistance);
		assertEquals(ModConfig.DEFAULT_DENIED_BLOCKS, big.build.deniedBlocks);
	}

	@Test
	void versionOneWhitelistsLoseParticleAndSay() throws IOException {
		write("{\"configVersion\": 1, \"commands\": {\"whitelist\": [\"summon\", \"particle\", \"SAY\", \"/say\", \"tp\"]}}");
		ModConfig migrated = ConfigIO.load(file());
		assertEquals(List.of("summon", "tp"), migrated.commands.whitelist);
		assertEquals(ConfigIO.CONFIG_VERSION, migrated.configVersion);
		assertTrue(Files.readString(file()).contains("\"configVersion\": 2"));

		write("{\"configVersion\": 2, \"commands\": {\"whitelist\": [\"say\", \"particle\"]}}");
		assertEquals(List.of("say", "particle"), ConfigIO.load(file()).commands.whitelist, "re-added later: kept");
	}

	@Test
	void failedWriteBackDoesNotFailTheLoad() throws IOException {
		write("{\"aiName\": \"Bob\", \"maxHistoryMessages\": 1000}");
		String before = Files.readString(file());
		ModConfig c = ConfigIO.load(file(), (path, config) -> {
			throw new IOException("read-only file system");
		});
		assertEquals("Bob", c.aiName);
		assertEquals(100, c.maxHistoryMessages, "normalized even though it could not be saved");
		assertEquals(before, Files.readString(file()));
		ModConfig missing = ConfigIO.load(dir.resolve("missing").resolve("x.json"), (path, config) -> {
			throw new IOException("disk full");
		});
		assertEquals("deepseek", missing.provider, "a missing file still gives the defaults");
		assertFalse(Files.exists(dir.resolve("missing")));
	}
}
