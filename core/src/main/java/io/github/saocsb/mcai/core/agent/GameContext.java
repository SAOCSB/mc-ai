package io.github.saocsb.mcai.core.agent;

/**
 * Snapshot of the requesting player's situation, sent to the LLM with every message.
 *
 * @param dayTime    world time of day in ticks (0..23999; 0 = sunrise, 6000 = noon, 13000 = night, 18000 = midnight)
 * @param weather    "clear", "rain" or "thunder"
 * @param heldItem   main-hand item id, or "minecraft:air"
 * @param groundY    world Y that local y = 0 maps to by default
 * @param minBuildY  lowest buildable world Y (inclusive)
 * @param maxBuildY  highest buildable world Y (inclusive)
 * @param maxBlocks  block budget for one build
 * @param otherPlayers names of other online players (may be empty)
 */
public record GameContext(String playerName, String dimension, String biome, int x, int y, int z, String facing,
		long dayTime, String weather, String gameMode, String heldItem, int groundY, int minBuildY, int maxBuildY,
		int maxBlocks, java.util.List<String> otherPlayers) {

	/**
	 * Compact multi-line text block for the prompt, e.g.
	 * <pre>
	 * [game context]
	 * player: Steve (survival) at x=120 y=64 z=-30, facing north, holding minecraft:diamond_sword
	 * world: minecraft:overworld, biome minecraft:plains, time 6000 (noon), weather clear
	 * build space: local y=0 is world y=64; you can use local y from -128 to 256; max 300000 blocks
	 * other players online: Alex, Bob
	 * [/game context]
	 * </pre>
	 * (time label: dawn/morning/noon/afternoon/sunset/night/midnight; "other players" line omitted when none.)
	 * Lines are separated by {@code \n}; there is no trailing newline. {@code dayTime} is taken modulo 24000.
	 */
	public String render() {
		long time = Math.floorMod(dayTime, 24000L);
		StringBuilder sb = new StringBuilder(256);
		sb.append("[game context]\n");
		sb.append("player: ").append(playerName).append(" (").append(gameMode).append(") at x=").append(x)
				.append(" y=").append(y).append(" z=").append(z).append(", facing ").append(facing)
				.append(", holding ").append(heldItem).append('\n');
		sb.append("world: ").append(dimension).append(", biome ").append(biome).append(", time ").append(time)
				.append(" (").append(timeLabel(time)).append("), weather ").append(weather).append('\n');
		sb.append("build space: local y=0 is world y=").append(groundY).append("; you can use local y from ")
				.append(minBuildY - groundY).append(" to ").append(maxBuildY - groundY).append("; max ")
				.append(maxBlocks).append(" blocks\n");
		if (otherPlayers != null && !otherPlayers.isEmpty()) {
			sb.append("other players online: ").append(String.join(", ", otherPlayers)).append('\n');
		}
		sb.append("[/game context]");
		return sb.toString();
	}

	/** Coarse label for a time of day in ticks (0..23999). */
	static String timeLabel(long time) {
		long t = Math.floorMod(time, 24000L);
		if (t < 1000) {
			return "dawn";
		} else if (t < 5000) {
			return "morning";
		} else if (t < 7000) {
			return "noon";
		} else if (t < 11000) {
			return "afternoon";
		} else if (t < 13000) {
			return "sunset";
		} else if (t < 17000) {
			return "night";
		} else if (t < 19000) {
			return "midnight";
		} else if (t < 23000) {
			return "night";
		}
		return "dawn";
	}
}
