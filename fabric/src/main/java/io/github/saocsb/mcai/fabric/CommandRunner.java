package io.github.saocsb.mcai.fabric;

import io.github.saocsb.mcai.core.config.ModConfig;
import io.github.saocsb.mcai.core.placement.Transform;
import io.github.saocsb.mcai.core.script.WorldAction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a script's {@link WorldAction}s as vanilla commands, subject to the server's command policy
 * ({@code commands} section of the config). Every command must:
 * <ul>
 * <li>be one line, with a whitelisted root (never {@code execute} or {@code function});</li>
 * <li>carry no block-entity, spawner or command data (TileEntityData, block_entity_data, command blocks,
 *     {@code Command:} keys, run_command or show_dialog click actions, SpawnData...), no block from
 *     {@code build.deniedBlocks}, and not summon a command block or spawner minecart;</li>
 * <li>unless {@code allowSelectors} is set, target only the requester: {@code @s}, {@code @s[...]} or a bare
 *     {@code @p}; no other selector, no other online player's name, no UUID, and {@code tp}/{@code teleport} only
 *     as {@code tp <x> <y> <z>} or {@code tp @s ...}.</li>
 * </ul>
 * At most {@code maxPerRequest} commands run per request, and summons (the {@code summon()} action or a raw
 * {@code summon} command) farther than {@code build.maxDistance} from the player are skipped. Commands run as the
 * player, at the configured permission level, with their chat output suppressed; {@code time} and {@code weather}
 * run in the overworld (26.3 keeps the world clock there).
 *
 * <p>A {@link WorldAction.Wait} splits the list: {@link #run} executes the actions before it and returns the rest
 * with the delay in {@link Report#remaining()} / {@link Report#waitTicks()}, for the caller to schedule.
 * Server thread only.
 */
public final class CommandRunner {
	private static final Logger LOGGER = LoggerFactory.getLogger("MC-AI");
	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?");
	private static final Pattern ITEM = Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?(\\[.*])?", Pattern.DOTALL);
	/** An entity selector: '@' and one letter not followed by another identifier character. */
	private static final Pattern SELECTOR = Pattern.compile("@([A-Za-z])(?![A-Za-z0-9_])");
	/** What {@link java.util.UUID#fromString} accepts, as the game's entity argument does. */
	private static final Pattern UUID_TOKEN = Pattern.compile(
			"[0-9a-fA-F]{1,8}-[0-9a-fA-F]{1,4}-[0-9a-fA-F]{1,4}-[0-9a-fA-F]{1,4}-[0-9a-fA-F]{1,12}");
	private static final Pattern COORDINATE = Pattern.compile("[~^]([-+]?(\\d+(\\.\\d*)?|\\.\\d+))?|[-+]?(\\d+(\\.\\d*)?|\\.\\d+)");
	/** A {@code command} NBT/JSON key (command blocks, minecarts, click events). */
	private static final Pattern COMMAND_KEY = Pattern.compile("\\bcommand[\"']?\\s*:", Pattern.CASE_INSENSITIVE);
	/** Never allowed anywhere in an AI command (lower case; matched case-insensitively). */
	private static final List<String> FORBIDDEN_WORDS = List.of("tileentitydata", "block_entity_data", "blockentitytag",
			"command_block", "allow_op_features", "spawndata", "spawnpotentials", "spawner_minecart", "run_command",
			"show_dialog");
	private static final Set<String> DENIED_ENTITIES = Set.of("command_block_minecart", "spawner_minecart");
	private static final Set<String> NEVER_ALLOWED = Set.of("execute", "function");
	private static final Set<String> TELEPORT = Set.of("tp", "teleport");
	/** Commands that act on the world clock or weather, which live in the overworld. */
	private static final Set<String> OVERWORLD_COMMANDS = Set.of("time", "weather");
	private static final Set<String> TIME_WORDS = Set.of("day", "noon", "night", "midnight");
	private static final Set<String> WEATHER_WORDS = Set.of("clear", "rain", "thunder");
	private static final int SHOWN_LENGTH = 60;
	/** Longest honoured wait: one hour. */
	private static final int MAX_WAIT_TICKS = 20 * 3600;

	/** Nesting depth of {@link #run} (server thread only); chat fired meanwhile comes from AI commands. */
	private static int executing;

	/**
	 * What happened to a list of actions.
	 *
	 * @param ok        commands that reported success
	 * @param failed    commands that ran but failed (parse or execution error)
	 * @param blocked   commands rejected by validation or policy, as shown to the player
	 * @param disabled  actions skipped because world actions are disabled
	 * @param remaining actions after a wait, to run {@code waitTicks} ticks later (empty when done)
	 * @param waitTicks the delay before {@code remaining}
	 */
	public record Report(int ok, int failed, List<String> blocked, int disabled, List<WorldAction> remaining,
			int waitTicks) {
		public static final Report EMPTY = new Report(0, 0, List.of(), 0, List.of(), 0);

		public int executed() {
			return ok + failed;
		}
	}

	/**
	 * Actions split at the first wait.
	 *
	 * @param now       the actions to run now (waits removed)
	 * @param waitTicks delay before {@code later}, 0 when {@code later} is empty
	 * @param later     the actions after the wait, waits included
	 */
	public record Batch(List<WorldAction> now, int waitTicks, List<WorldAction> later) {
	}

	private CommandRunner() {
	}

	/** True while AI commands are being executed (chat events fired then must not trigger the AI). */
	public static boolean isExecuting() {
		return executing > 0;
	}

	/**
	 * Executes the actions of {@code actions} up to the first wait for {@code player} in {@code level} (the build's
	 * dimension); local coordinates are mapped with {@code transform}.
	 *
	 * @param alreadyExecuted commands this request already ran in earlier batches (counts toward maxPerRequest)
	 */
	public static Report run(ServerPlayer player, ServerLevel level, List<WorldAction> actions, Transform transform,
			ModConfig.Commands policy, ModConfig.Build build, int alreadyExecuted) {
		if (actions.isEmpty()) {
			return Report.EMPTY;
		}
		if (!policy.enabled) {
			int count = (int) actions.stream().filter(a -> !(a instanceof WorldAction.Wait)).count();
			return new Report(0, 0, List.of(), count, List.of(), 0);
		}
		Batch batch = split(actions);
		MinecraftServer server = level.getServer();
		CommandSourceStack base = player.createCommandSourceStack()
				.withLevel(level)
				.withPermission(permissions(policy.permissionLevel))
				.withSuppressedOutput();
		CommandSourceStack inOverworld = base.withLevel(server.overworld());
		List<String> others = otherPlayers(server, player);
		BlockPos at = player.blockPosition();
		int ok = 0;
		int failed = 0;
		List<String> blocked = new ArrayList<>();
		executing++;
		try {
			for (WorldAction action : batch.now()) {
				String command = toCommand(action, transform);
				boolean tooFar = action instanceof WorldAction.Summon s
						? horizontalDistance(transform.worldX(s.x(), s.z()), transform.worldZ(s.x(), s.z()), at) > build.maxDistance
						: summonDistance(command, player.getX(), player.getZ()) > build.maxDistance;
				if (tooFar || !isValid(action) || check(command, policy, others, build.deniedBlocks) != null
						|| alreadyExecuted + ok + failed >= policy.maxPerRequest) {
					blocked.add(shorten(command));
					continue;
				}
				boolean[] success = {false};
				CommandSourceStack source = (OVERWORLD_COMMANDS.contains(root(command)) ? inOverworld : base)
						.withCallback((succeeded, result) -> success[0] |= succeeded);
				LOGGER.info("AI command for {}: /{}", player.getScoreboardName(), command);
				try {
					server.getCommands().performPrefixedCommand(source, command);
				} catch (RuntimeException e) {
					LOGGER.warn("AI command failed: /{}", command, e);
				}
				if (success[0]) {
					ok++;
				} else {
					failed++;
				}
			}
		} finally {
			executing--;
		}
		return new Report(ok, failed, List.copyOf(blocked), 0, batch.later(), batch.waitTicks());
	}

	/** Splits {@code actions} at the first wait that has a positive delay and actions after it. */
	public static Batch split(List<WorldAction> actions) {
		List<WorldAction> now = new ArrayList<>();
		for (int i = 0; i < actions.size(); i++) {
			WorldAction action = actions.get(i);
			if (action instanceof WorldAction.Wait wait) {
				int ticks = waitTicks(wait.seconds());
				if (ticks > 0 && i + 1 < actions.size()) {
					return new Batch(List.copyOf(now), ticks, List.copyOf(actions.subList(i + 1, actions.size())));
				}
			} else {
				now.add(action);
			}
		}
		return new Batch(List.copyOf(now), 0, List.of());
	}

	/** {@code round(seconds * 20)}, clamped to 0..one hour (NaN and negative values give 0). */
	public static int waitTicks(double seconds) {
		if (!(seconds > 0)) {
			return 0;
		}
		return (int) Math.min(MAX_WAIT_TICKS, Math.round(seconds * 20));
	}

	/**
	 * Checks a command string against the policy (ignoring {@code enabled} and the per-request limit), with no other
	 * players online and no denied blocks.
	 *
	 * @return null when allowed, otherwise a short English reason
	 */
	public static String check(String command, ModConfig.Commands policy) {
		return check(command, policy, List.of(), List.of());
	}

	/**
	 * Checks a command string against the policy (ignoring {@code enabled} and the per-request limit).
	 *
	 * @param otherPlayers names of the other online players (never valid targets without {@code allowSelectors})
	 * @param deniedBlocks {@code build.deniedBlocks}; commands mentioning one are refused
	 * @return null when allowed, otherwise a short English reason
	 */
	public static String check(String command, ModConfig.Commands policy, Collection<String> otherPlayers,
			Collection<String> deniedBlocks) {
		String cmd = stripSlash(command == null ? "" : command);
		if (cmd.isEmpty()) {
			return "empty command";
		}
		for (int i = 0; i < cmd.length(); i++) {
			char c = cmd.charAt(i);
			if (c == '\n' || c == '\r') {
				return "multi-line command";
			}
			if (Character.isISOControl(c)) {
				return "control characters";
			}
		}
		String[] parts = cmd.split("\\s+");
		String root = root(cmd);
		if (NEVER_ALLOWED.contains(root)) {
			return "'" + root + "' is never allowed";
		}
		boolean whitelisted = false;
		if (policy.whitelist != null) {
			for (String allowed : policy.whitelist) {
				if (allowed != null && stripSlash(allowed).equalsIgnoreCase(root)) {
					whitelisted = true;
					break;
				}
			}
		}
		if (!whitelisted) {
			return "'" + root + "' is not whitelisted";
		}
		String lower = cmd.toLowerCase(Locale.ROOT);
		for (String word : FORBIDDEN_WORDS) {
			if (lower.contains(word)) {
				return "'" + word + "' is not allowed in AI commands";
			}
		}
		if (COMMAND_KEY.matcher(cmd).find()) {
			return "command data is not allowed in AI commands";
		}
		String deniedBlock = mentionedBlock(lower, deniedBlocks);
		if (deniedBlock != null) {
			return "'" + deniedBlock + "' is not allowed on this server";
		}
		if (root.equals("summon") && parts.length > 1 && DENIED_ENTITIES.contains(stripNamespace(lower(parts[1])))) {
			return "'" + lower(parts[1]) + "' cannot be summoned";
		}
		if (!policy.allowSelectors) {
			return targetProblem(cmd, root, parts, otherPlayers);
		}
		return null;
	}

	/** Without allowSelectors: whatever lets the command reach someone other than the requester, or null. */
	private static String targetProblem(String cmd, String root, String[] parts, Collection<String> otherPlayers) {
		Matcher selector = SELECTOR.matcher(cmd);
		while (selector.find()) {
			char kind = Character.toLowerCase(selector.group(1).charAt(0));
			boolean arguments = selector.end() < cmd.length() && cmd.charAt(selector.end()) == '[';
			if (kind == 's' || (kind == 'p' && !arguments)) {
				continue;
			}
			return "selector '" + selector.group() + (arguments ? "[...]" : "") + "' is not allowed (only @s and @p)";
		}
		Set<String> names = new HashSet<>();
		if (otherPlayers != null) {
			for (String name : otherPlayers) {
				if (name != null && !name.isBlank()) {
					names.add(name.toLowerCase(Locale.ROOT));
				}
			}
		}
		for (int i = 1; i < parts.length; i++) {
			String token = stripQuotes(parts[i]);
			if (names.contains(token.toLowerCase(Locale.ROOT))) {
				return "other players cannot be targeted";
			}
			if (UUID_TOKEN.matcher(token).matches()) {
				return "UUID targets are not allowed";
			}
		}
		if (TELEPORT.contains(root)) {
			boolean self = parts.length > 1 && (parts[1].equals("@s") || parts[1].startsWith("@s["));
			boolean coordinates = parts.length == 4 && isCoordinate(parts[1]) && isCoordinate(parts[2])
					&& isCoordinate(parts[3]);
			if (!self && !coordinates) {
				return "'" + root + "' may only move you (" + root + " <x> <y> <z> or " + root + " @s ...)";
			}
		}
		return null;
	}

	/** The first id of {@code deniedBlocks} that appears as a whole block id in {@code lowerCommand}, or null. */
	private static String mentionedBlock(String lowerCommand, Collection<String> deniedBlocks) {
		if (deniedBlocks == null) {
			return null;
		}
		for (String entry : deniedBlocks) {
			if (entry == null || entry.isBlank()) {
				continue;
			}
			String id = entry.trim().toLowerCase(Locale.ROOT);
			int colon = id.indexOf(':');
			String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
			String path = colon < 0 ? id : id.substring(colon + 1);
			String body = namespace.equals("minecraft") ? "(?:minecraft:)?" + Pattern.quote(path) : Pattern.quote(id);
			if (Pattern.compile("(?<![a-z0-9_.:/-])" + body + "(?![a-z0-9_.:/-])").matcher(lowerCommand).find()) {
				return id;
			}
		}
		return null;
	}

	/** The command an action stands for (without the leading slash). */
	public static String toCommand(WorldAction action, Transform transform) {
		return switch (action) {
			case WorldAction.Summon s -> {
				String pos = String.format(Locale.ROOT, "%.1f %d %.1f", transform.worldX(s.x(), s.z()) + 0.5,
						transform.worldY(s.y()), transform.worldZ(s.x(), s.z()) + 0.5);
				String nbt = s.nbt() == null || s.nbt().isBlank() ? "" : " " + s.nbt().trim();
				yield "summon " + lowerId(s.entityId()) + " " + pos + nbt;
			}
			case WorldAction.Give g -> "give @s " + lowerItem(g.itemId()) + " " + clamp(g.count(), 1, 6400);
			case WorldAction.SetTime t -> "time set " + lower(t.value());
			case WorldAction.SetWeather w -> "weather " + lower(w.value());
			case WorldAction.Effect e -> "effect give @s " + lowerId(e.effectId()) + " " + clamp(e.seconds(), 1, 1_000_000)
					+ " " + clamp(e.amplifier(), 0, 255);
			case WorldAction.Command c -> stripSlash(c.command() == null ? "" : c.command());
			case WorldAction.Wait w -> "";
		};
	}

	/** Validates ids, NBT and values of an action (the policy is checked separately). */
	public static boolean isValid(WorldAction action) {
		return switch (action) {
			case WorldAction.Summon s -> ID.matcher(lowerId(s.entityId())).matches()
					&& !DENIED_ENTITIES.contains(stripNamespace(lowerId(s.entityId())))
					&& (s.nbt() == null || s.nbt().isBlank() || s.nbt().trim().startsWith("{"));
			case WorldAction.Give g -> ITEM.matcher(lowerItem(g.itemId())).matches();
			case WorldAction.SetTime t -> isTime(lower(t.value()));
			case WorldAction.SetWeather w -> WEATHER_WORDS.contains(lower(w.value()));
			case WorldAction.Effect e -> ID.matcher(lowerId(e.effectId())).matches();
			case WorldAction.Command c -> c.command() != null && !stripSlash(c.command()).isEmpty();
			case WorldAction.Wait w -> w.seconds() >= 0;
		};
	}

	/** The permission set for an op level 0..4 (out-of-range values are clamped). */
	static LevelBasedPermissionSet permissions(int level) {
		return LevelBasedPermissionSet.forLevel(PermissionLevel.byId(Math.max(0, Math.min(4, level))));
	}

	/** Horizontal distance (rounded up) from a world column to a block position. */
	static int horizontalDistance(int x, int z, BlockPos to) {
		double dx = (double) x - to.getX();
		double dz = (double) z - to.getZ();
		return (int) Math.min(Integer.MAX_VALUE, Math.ceil(Math.sqrt(dx * dx + dz * dz)));
	}

	/**
	 * How far (horizontally, rounded up) a raw {@code summon} command spawns its entity from a source at
	 * {@code (x, z)}. Local {@code ^} coordinates count with the length of the whole offset. 0 for other commands, a
	 * summon without a position (it spawns at the source) and coordinates that don't parse (the game rejects those).
	 */
	public static int summonDistance(String command, double x, double z) {
		String cmd = stripSlash(command == null ? "" : command);
		String[] parts = cmd.split("\\s+");
		if (!root(cmd).equals("summon") || parts.length < 5) {
			return 0;
		}
		double[] offset = new double[3];
		boolean local = false;
		for (int i = 0; i < 3; i++) {
			String token = parts[i + 2];
			if (!isCoordinate(token)) {
				return 0;
			}
			char first = token.charAt(0);
			boolean relative = first == '~' || first == '^';
			local |= first == '^';
			double value = relative && token.length() == 1 ? 0 : Double.parseDouble(relative ? token.substring(1) : token);
			offset[i] = relative || i == 1 ? value : value - (i == 0 ? x : z);
		}
		double distance = local ? Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1] + offset[2] * offset[2])
				: Math.sqrt(offset[0] * offset[0] + offset[2] * offset[2]);
		return (int) Math.min(Integer.MAX_VALUE, Math.ceil(distance));
	}

	private static List<String> otherPlayers(MinecraftServer server, ServerPlayer requester) {
		List<String> names = new ArrayList<>();
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other != requester && !other.getUUID().equals(requester.getUUID())) {
				names.add(other.getScoreboardName());
			}
		}
		return names;
	}

	/** The lower-case command name without a namespace. */
	private static String root(String command) {
		String cmd = stripSlash(command);
		int space = 0;
		while (space < cmd.length() && !Character.isWhitespace(cmd.charAt(space))) {
			space++;
		}
		return stripNamespace(cmd.substring(0, space).toLowerCase(Locale.ROOT));
	}

	private static boolean isCoordinate(String token) {
		return COORDINATE.matcher(token).matches();
	}

	private static String stripQuotes(String token) {
		int start = 0;
		int end = token.length();
		while (start < end && (token.charAt(start) == '"' || token.charAt(start) == '\'')) {
			start++;
		}
		while (end > start && (token.charAt(end - 1) == '"' || token.charAt(end - 1) == '\'')) {
			end--;
		}
		return token.substring(start, end);
	}

	private static String stripNamespace(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}

	private static boolean isTime(String value) {
		if (TIME_WORDS.contains(value)) {
			return true;
		}
		try {
			int ticks = Integer.parseInt(value);
			return ticks >= 0 && ticks <= 24_000;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static String stripSlash(String command) {
		String cmd = command.trim();
		return cmd.startsWith("/") ? cmd.substring(1).trim() : cmd;
	}

	private static String lower(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private static String lowerId(String id) {
		return lower(id);
	}

	/** Lower-cases the item id but keeps component values (e.g. custom names) as written. */
	private static String lowerItem(String item) {
		if (item == null) {
			return "";
		}
		String trimmed = item.trim();
		int bracket = trimmed.indexOf('[');
		return bracket < 0 ? lower(trimmed) : lower(trimmed.substring(0, bracket)) + trimmed.substring(bracket);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static String shorten(String command) {
		String oneLine = command.replace('\n', ' ').replace('\r', ' ');
		return "/" + (oneLine.length() > SHOWN_LENGTH ? oneLine.substring(0, SHOWN_LENGTH) + "..." : oneLine);
	}
}
