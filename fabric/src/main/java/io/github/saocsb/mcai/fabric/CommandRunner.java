package io.github.saocsb.mcai.fabric;

import io.github.saocsb.mcai.core.config.ModConfig;
import io.github.saocsb.mcai.core.placement.Transform;
import io.github.saocsb.mcai.core.script.WorldAction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runs a script's {@link WorldAction}s as vanilla commands, subject to the server's command policy
 * ({@code commands} section of the config): actions must be enabled, the root command whitelisted (never
 * {@code execute} or {@code function}), {@code @a/@e/@r/@n} selectors only with {@code allowSelectors}, and at
 * most {@code maxPerRequest} commands run. Commands run as the player, at the configured permission level, with
 * their chat output suppressed. Server thread only.
 */
public final class CommandRunner {
	private static final Logger LOGGER = LoggerFactory.getLogger("MC-AI");
	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?");
	private static final Pattern ITEM = Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?(\\[.*])?", Pattern.DOTALL);
	private static final Pattern SELECTOR = Pattern.compile("@[aenr](?![A-Za-z0-9_])");
	private static final Set<String> NEVER_ALLOWED = Set.of("execute", "function");
	private static final Set<String> TIME_WORDS = Set.of("day", "noon", "night", "midnight");
	private static final Set<String> WEATHER_WORDS = Set.of("clear", "rain", "thunder");
	private static final int SHOWN_LENGTH = 60;

	/**
	 * What happened to a list of actions.
	 *
	 * @param ok       commands that reported success
	 * @param failed   commands that ran but failed (parse or execution error)
	 * @param blocked  commands rejected by validation or policy, as shown to the player
	 * @param disabled actions skipped because world actions are disabled
	 */
	public record Report(int ok, int failed, List<String> blocked, int disabled) {
		public static final Report EMPTY = new Report(0, 0, List.of(), 0);

		public int executed() {
			return ok + failed;
		}
	}

	private CommandRunner() {
	}

	/**
	 * Executes {@code actions} for {@code player} in {@code level} (the build's dimension); local coordinates are
	 * mapped with {@code transform}.
	 */
	public static Report run(ServerPlayer player, ServerLevel level, List<WorldAction> actions, Transform transform,
			ModConfig.Commands policy) {
		if (actions.isEmpty()) {
			return Report.EMPTY;
		}
		if (!policy.enabled) {
			return new Report(0, 0, List.of(), actions.size());
		}
		MinecraftServer server = level.getServer();
		CommandSourceStack base = player.createCommandSourceStack()
				.withLevel(level)
				.withPermission(permissions(policy.permissionLevel))
				.withSuppressedOutput();
		int ok = 0;
		int failed = 0;
		List<String> blocked = new ArrayList<>();
		for (WorldAction action : actions) {
			String command = toCommand(action, transform);
			if (!isValid(action) || check(command, policy) != null || ok + failed >= policy.maxPerRequest) {
				blocked.add(shorten(command));
				continue;
			}
			boolean[] success = {false};
			CommandSourceStack source = base.withCallback((succeeded, result) -> success[0] |= succeeded);
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
		return new Report(ok, failed, List.copyOf(blocked), 0);
	}

	/**
	 * Checks a command string against the policy (ignoring {@code enabled} and the per-request limit).
	 *
	 * @return null when allowed, otherwise a short English reason
	 */
	public static String check(String command, ModConfig.Commands policy) {
		String cmd = stripSlash(command);
		if (cmd.isEmpty()) {
			return "empty command";
		}
		if (cmd.indexOf('\n') >= 0 || cmd.indexOf('\r') >= 0) {
			return "multi-line command";
		}
		String root = cmd.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		if (root.startsWith("minecraft:")) {
			root = root.substring("minecraft:".length());
		}
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
		if (!policy.allowSelectors && SELECTOR.matcher(cmd).find()) {
			return "@a/@e/@r/@n selectors are not allowed";
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
