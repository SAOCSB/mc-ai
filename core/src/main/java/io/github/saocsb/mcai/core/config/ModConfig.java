package io.github.saocsb.mcai.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Root of {@code config/mcai.json} (Gson-mapped; field names are the JSON keys). */
public final class ModConfig {
	public int configVersion = 1;
	/** Active provider id (key of {@link #providers}). */
	public String provider = ProviderPresets.DEFAULT_PROVIDER;
	public Map<String, ProviderSettings> providers = new LinkedHashMap<>();
	/** Optional HTTP proxy for LLM requests as "host:port"; blank = JVM default proxy settings. */
	public String proxy = "";
	/** "auto" (per player's client language), "zh_cn" or "en_us". */
	public String language = "auto";
	/** Name shown in chat for the AI. */
	public String aiName = "AI";
	/** Chat messages starting with one of these (case-insensitive) are sent to the AI. */
	public List<String> chatPrefixes = new ArrayList<>(List.of("@ai"));
	/** Whether the triggering chat message is still shown to everyone. */
	public boolean showTriggerMessages = true;
	/** "all" = everyone sees AI replies; "requester" = only the player who asked. */
	public String replyVisibility = "all";
	/** Conversation turns (user + assistant messages) remembered per player. */
	public int maxHistoryMessages = 12;
	/** Times the AI may fix a failing build script before giving up. */
	public int maxRepairAttempts = 2;
	public int cooldownSeconds = 3;
	public int maxConcurrentRequests = 4;
	/** Extra instructions appended to the system prompt (server flavour, rules...). */
	public String extraInstructions = "";
	public Build build = new Build();
	public Commands commands = new Commands();
	public Permissions permissions = new Permissions();

	public static final class Build {
		public int maxBlocks = 300_000;
		public int maxSpan = 384;
		public int scriptTimeoutMillis = 10_000;
		public int blocksPerTick = 2_000;
		/** Soft per-tick time budget for block placement, in milliseconds. */
		public int maxTickMillis = 20;
		public int frontDistance = 3;
		/** Never overwrite blocks that have block entities (chests, furnaces, signs...). */
		public boolean protectBlockEntities = true;
		/** Completed builds remembered per player for /ai undo. */
		public int undoHistory = 5;
		/** Builds larger than this need /ai confirm (0 = never ask). */
		public int confirmAboveBlocks = 100_000;
		public boolean particles = true;
		/** Recompute connecting shapes (fences, panes, stairs, walls) after placing. */
		public boolean updateShapes = true;
	}

	public static final class Commands {
		public boolean enabled = true;
		/** Permission level AI-issued commands run with (2 = game master; summon/give need it). */
		public int permissionLevel = 2;
		/** Allow @a/@e/@r/@n selectors in AI commands (@s and @p are always allowed). */
		public boolean allowSelectors = false;
		public int maxPerRequest = 32;
		public List<String> whitelist = new ArrayList<>(List.of(
				"summon", "give", "time", "weather", "effect", "particle", "playsound", "title", "tellraw",
				"say", "xp", "experience", "enchant", "tp", "teleport"));
	}

	public static final class Permissions {
		/** Permission level needed to talk to the AI. */
		public int useLevel = 0;
		/** Permission level needed for /ai reload and /ai config. */
		public int adminLevel = 2;
	}
}
