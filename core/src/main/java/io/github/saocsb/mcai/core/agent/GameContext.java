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
	 */
	public String render() {
		throw new UnsupportedOperationException("TODO");
	}
}
