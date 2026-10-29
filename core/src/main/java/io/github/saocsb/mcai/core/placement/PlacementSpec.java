package io.github.saocsb.mcai.core.placement;

/**
 * Placement requested by a build script via {@code placement({...})}.
 *
 * @param distance        gap in blocks between the player and the structure in FRONT mode (>= 0)
 * @param replacePrevious undo the player's previous build before placing this one
 * @param absX            world anchor for ABSOLUTE mode (null otherwise)
 */
public record PlacementSpec(PlacementMode mode, int distance, GroundMode ground, boolean replacePrevious,
		Integer absX, Integer absY, Integer absZ) {
	public static final int DEFAULT_DISTANCE = 3;

	public static PlacementSpec defaults() {
		return new PlacementSpec(PlacementMode.FRONT, DEFAULT_DISTANCE, GroundMode.PLAYER, false, null, null, null);
	}
}
