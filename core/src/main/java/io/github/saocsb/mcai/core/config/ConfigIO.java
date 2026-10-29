package io.github.saocsb.mcai.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;
import com.google.gson.ToNumberPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads and saves {@link ModConfig} as pretty-printed JSON (UTF-8) with Gson. Parsing is strict JSON
 * (no comments); untyped numbers in {@code extraBody} keep integers as integers.
 */
public final class ConfigIO {
	/**
	 * Current {@link ModConfig#configVersion}. Version 2 removed {@code particle} and {@code say} from the default
	 * command whitelist; loading an older file removes them from its whitelist once.
	 */
	public static final int CONFIG_VERSION = 2;

	private static final System.Logger LOG = System.getLogger(ConfigIO.class.getName());
	/** Provider fields whose maps are merged key by key with a preset's (user keys win, missing preset keys are added). */
	private static final Set<String> MERGED_MAPS = Set.of("extraBody", "extraHeaders");
	/** Commands dropped from whitelists written before config version 2. */
	private static final Set<String> REMOVED_IN_V2 = Set.of("particle", "say", "/particle", "/say");

	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.serializeNulls()
			.setStrictness(Strictness.STRICT)
			.setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.create();
	private static final Set<String> LANGUAGES = Set.of("auto", "zh_cn", "en_us");
	private static final Set<String> VISIBILITIES = Set.of("all", "requester");

	private ConfigIO() {
	}

	/**
	 * Loads the config, creating it with defaults (all presets) if missing. Missing presets are added,
	 * null sections/collections replaced with defaults, numbers clamped to sane ranges, and an unknown
	 * active provider reset to the default; the normalized result is written back so new options appear.
	 * An empty file counts as missing. The file is only rewritten when the normalized text differs; a failed
	 * write-back is logged and does not fail the load.
	 *
	 * @throws IOException if the file cannot be read or is not valid JSON (the file is then left untouched)
	 */
	public static ModConfig load(Path file) throws IOException {
		return load(file, ConfigIO::save);
	}

	/** Writes a config file; {@link #save} outside tests. */
	@FunctionalInterface
	interface Saver {
		void save(Path file, ModConfig config) throws IOException;
	}

	/** {@link #load(Path)} with the write-back done by {@code saver}. */
	static ModConfig load(Path file, Saver saver) throws IOException {
		String text;
		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		} catch (NoSuchFileException e) {
			text = null;
		}
		if (text != null && !text.isEmpty() && text.charAt(0) == '﻿') {
			text = text.substring(1);
		}
		ModConfig config = text == null || text.isBlank() ? null : parse(file, text);
		if (config == null) {
			config = defaults();
		}
		normalize(config);
		String normalized = toJson(config);
		if (text == null || !normalized.equals(text)) {
			try {
				saver.save(file, config);
			} catch (IOException | RuntimeException e) {
				LOG.log(System.Logger.Level.WARNING, "Could not write the normalized config back to " + file
						+ "; using it without saving", e);
			}
		}
		return config;
	}

	/** Writes atomically (temp file in the same directory + move). */
	public static void save(Path file, ModConfig config) throws IOException {
		Path target = file.toAbsolutePath();
		Path dir = target.getParent();
		if (dir != null) {
			Files.createDirectories(dir);
		}
		Path temp = Files.createTempFile(dir, target.getFileName().toString() + ".", ".tmp");
		try {
			Files.writeString(temp, toJson(config), StandardCharsets.UTF_8);
			try {
				Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	/** A default config with all presets. */
	public static ModConfig defaults() {
		ModConfig config = new ModConfig();
		config.providers = ProviderPresets.all();
		return config;
	}

	/**
	 * Parses the config. Fields missing from an entry whose id is a preset are taken from that preset,
	 * so {@code "deepseek": {"apiKey": "..."}} keeps the preset's URL and model. {@code extraBody} and
	 * {@code extraHeaders} are merged key by key: the user's keys win (a {@code null} value still removes a field)
	 * and preset keys the user did not mention are added.
	 *
	 * @return the config, or null for a JSON {@code null} document
	 */
	private static ModConfig parse(Path file, String text) throws IOException {
		try {
			JsonElement tree = GSON.fromJson(text, JsonElement.class);
			if (tree == null || tree.isJsonNull()) {
				return null;
			}
			if (!tree.isJsonObject()) {
				throw new JsonSyntaxException("expected a JSON object at the top level");
			}
			JsonElement providers = tree.getAsJsonObject().get("providers");
			if (providers != null && providers.isJsonObject()) {
				JsonObject entries = providers.getAsJsonObject();
				Map<String, ProviderSettings> presets = ProviderPresets.all();
				for (String id : List.copyOf(entries.keySet())) {
					ProviderSettings preset = presets.get(id);
					JsonElement user = entries.get(id);
					if (preset != null && user.isJsonObject()) {
						JsonObject merged = GSON.toJsonTree(preset).getAsJsonObject();
						for (Map.Entry<String, JsonElement> e : user.getAsJsonObject().entrySet()) {
							merged.add(e.getKey(), mergeField(e.getKey(), e.getValue(), merged.get(e.getKey())));
						}
						entries.add(id, merged);
					}
				}
			}
			return GSON.fromJson(tree, ModConfig.class);
		} catch (JsonParseException | IllegalStateException | ClassCastException | NumberFormatException e) {
			throw new IOException("Invalid JSON in " + file + ": " + rootMessage(e)
					+ " (fix the file, or delete it to regenerate the defaults)", e);
		}
	}

	/**
	 * The value of a user field merged over a preset's: for {@code extraBody}/{@code extraHeaders} objects the preset
	 * keys the user left out are added; every other field (and a non-object value) is the user's as written.
	 */
	private static JsonElement mergeField(String key, JsonElement user, JsonElement preset) {
		if (!MERGED_MAPS.contains(key) || !user.isJsonObject() || preset == null || !preset.isJsonObject()) {
			return user;
		}
		JsonObject map = user.getAsJsonObject().deepCopy();
		for (Map.Entry<String, JsonElement> e : preset.getAsJsonObject().entrySet()) {
			if (!map.has(e.getKey())) {
				map.add(e.getKey(), e.getValue());
			}
		}
		return map;
	}

	/** The JSON text {@link #save} writes. */
	static String toJson(ModConfig config) {
		return GSON.toJson(config) + "\n";
	}

	/** Fills in missing parts and clamps values in place (see {@link #load}). */
	static void normalize(ModConfig c) {
		int loadedVersion = c.configVersion;
		c.configVersion = CONFIG_VERSION;
		if (c.build == null) {
			c.build = new ModConfig.Build();
		}
		if (c.commands == null) {
			c.commands = new ModConfig.Commands();
		}
		if (c.permissions == null) {
			c.permissions = new ModConfig.Permissions();
		}

		Map<String, ProviderSettings> presets = ProviderPresets.all();
		Map<String, ProviderSettings> providers = new LinkedHashMap<>();
		if (c.providers != null) {
			c.providers.forEach((id, settings) -> {
				if (id == null || id.isBlank()) {
					return;
				}
				ProviderSettings s = settings != null ? settings : presets.get(id);
				if (s != null) {
					normalizeProvider(s);
					providers.put(id, s);
				}
			});
		}
		presets.forEach(providers::putIfAbsent);
		c.providers = providers;
		if (c.provider == null || !providers.containsKey(c.provider.trim())) {
			c.provider = ProviderPresets.DEFAULT_PROVIDER;
		} else {
			c.provider = c.provider.trim();
		}

		if (c.proxy == null) {
			c.proxy = "";
		}
		c.language = oneOf(c.language, LANGUAGES, "auto");
		if (c.aiName == null || c.aiName.isBlank()) {
			c.aiName = "AI";
		}
		c.chatPrefixes = nonBlank(c.chatPrefixes);
		if (c.chatPrefixes.isEmpty()) {
			c.chatPrefixes.add("@ai");
		}
		c.replyVisibility = oneOf(c.replyVisibility, VISIBILITIES, "all");
		c.maxHistoryMessages = Math.clamp(c.maxHistoryMessages, 0, 100);
		c.maxRepairAttempts = Math.clamp(c.maxRepairAttempts, 0, 5);
		c.cooldownSeconds = Math.clamp(c.cooldownSeconds, 0, 3600);
		c.maxConcurrentRequests = Math.clamp(c.maxConcurrentRequests, 1, 64);
		if (c.extraInstructions == null) {
			c.extraInstructions = "";
		}

		ModConfig.Build b = c.build;
		b.maxBlocks = Math.clamp(b.maxBlocks, 1, 5_000_000);
		b.maxSpan = Math.clamp(b.maxSpan, 8, 2048);
		b.scriptTimeoutMillis = Math.clamp(b.scriptTimeoutMillis, 500, 120_000);
		b.blocksPerTick = Math.clamp(b.blocksPerTick, 1, 100_000);
		b.maxTickMillis = Math.clamp(b.maxTickMillis, 1, 200);
		b.frontDistance = Math.clamp(b.frontDistance, 0, 64);
		b.undoHistory = Math.clamp(b.undoHistory, 0, 50);
		b.confirmAboveBlocks = Math.max(0, b.confirmAboveBlocks);
		b.maxDistance = Math.clamp(b.maxDistance, 8, 4096);
		b.deniedBlocks = b.deniedBlocks == null ? new ArrayList<>(ModConfig.DEFAULT_DENIED_BLOCKS) : nonBlank(b.deniedBlocks);

		ModConfig.Commands cmd = c.commands;
		cmd.permissionLevel = Math.clamp(cmd.permissionLevel, 0, 4);
		cmd.maxPerRequest = Math.clamp(cmd.maxPerRequest, 0, 1000);
		if (cmd.whitelist == null) {
			cmd.whitelist = new ModConfig.Commands().whitelist;
		} else {
			cmd.whitelist = nonBlank(cmd.whitelist);
			if (loadedVersion < 2) {
				cmd.whitelist.removeIf(command -> REMOVED_IN_V2.contains(command.toLowerCase(Locale.ROOT)));
			}
		}

		ModConfig.Permissions p = c.permissions;
		p.useLevel = Math.clamp(p.useLevel, 0, 4);
		p.adminLevel = Math.clamp(p.adminLevel, 0, 4);
	}

	private static void normalizeProvider(ProviderSettings s) {
		ProviderSettings d = new ProviderSettings();
		s.apiStyle = s.apiStyle == null || !s.apiStyle.trim().equalsIgnoreCase("anthropic") ? "openai" : "anthropic";
		if (s.baseUrl == null) {
			s.baseUrl = d.baseUrl;
		}
		if (s.apiKey == null) {
			s.apiKey = d.apiKey;
		}
		if (s.apiKeyEnv == null) {
			s.apiKeyEnv = d.apiKeyEnv;
		}
		if (s.model == null) {
			s.model = d.model;
		}
		if (s.maxTokensField == null || s.maxTokensField.isBlank()) {
			s.maxTokensField = d.maxTokensField;
		}
		s.maxTokens = Math.max(0, s.maxTokens);
		s.timeoutSeconds = Math.clamp(s.timeoutSeconds, 10, 900);
		if (s.extraHeaders == null) {
			s.extraHeaders = new LinkedHashMap<>();
		}
		if (s.extraBody == null) {
			s.extraBody = new LinkedHashMap<>();
		}
	}

	private static String oneOf(String value, Set<String> allowed, String fallback) {
		if (value == null) {
			return fallback;
		}
		String v = value.trim().toLowerCase(Locale.ROOT);
		return allowed.contains(v) ? v : fallback;
	}

	private static List<String> nonBlank(List<String> list) {
		List<String> out = new ArrayList<>();
		if (list != null) {
			for (String s : list) {
				if (s != null && !s.isBlank()) {
					out.add(s.trim());
				}
			}
		}
		return out;
	}

	private static String rootMessage(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && t.getCause() != t && t.getMessage() == null) {
			t = t.getCause();
		}
		String message = t.getMessage();
		return message == null ? e.getClass().getSimpleName() : message;
	}
}
