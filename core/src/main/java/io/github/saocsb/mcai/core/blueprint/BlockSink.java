package io.github.saocsb.mcai.core.blueprint;

/** Receives block placements in local (blueprint) coordinates. Implemented by {@link Blueprint}. */
@FunctionalInterface
public interface BlockSink {
	/**
	 * Places (or overwrites) one block.
	 *
	 * @param blockId block id with optional state and NBT, e.g. {@code "stone"} or
	 *                {@code "minecraft:oak_stairs[facing=north]"}; {@code "air"} clears.
	 */
	void set(int x, int y, int z, String blockId);
}
