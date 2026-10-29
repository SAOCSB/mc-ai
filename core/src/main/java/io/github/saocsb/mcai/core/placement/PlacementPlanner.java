package io.github.saocsb.mcai.core.placement;

import io.github.saocsb.mcai.core.blueprint.Bounds;

/** Computes the world {@link Transform} for a blueprint. Pure function; see {@link PlacementMode}. */
public final class PlacementPlanner {
	/** Looks up the terrain surface Y (first free block above ground) for a world column. */
	@FunctionalInterface
	public interface SurfaceHeight {
		int surfaceY(int worldX, int worldZ);
	}

	private PlacementPlanner() {
	}

	/**
	 * Rotation that turns the local frame so the player looks toward local north:
	 * NORTH -> NONE, EAST -> CLOCKWISE_90, SOUTH -> CLOCKWISE_180, WEST -> COUNTERCLOCKWISE_90.
	 */
	public static Rotation rotationFor(Facing playerFacing) {
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * Plans the transform.
	 *
	 * <ul>
	 * <li>FRONT: the local point (bounds.centerX(), 0, bounds.maxZ() + 1 + distance) maps to the player's block position.</li>
	 * <li>CENTER: (bounds.centerX(), 0, bounds.centerZ()) maps to the player's block position.</li>
	 * <li>ORIGIN: (0, 0, 0) maps to the player's block position.</li>
	 * <li>PREVIOUS: returns {@code previous} unchanged; falls back to FRONT when it is null.</li>
	 * <li>ABSOLUTE: rotation NONE, origin = (absX, absY, absZ); missing coordinates default to the player's block position.</li>
	 * </ul>
	 * For FRONT/CENTER/ORIGIN the origin Y is {@code pose.groundY()} with {@link GroundMode#PLAYER}, or
	 * {@code surface.surfaceY(...)} of the world column under the local point (bounds.centerX(), bounds.centerZ())
	 * with {@link GroundMode#SURFACE}.
	 *
	 * @param bounds   blueprint bounds (non-null; callers skip empty blueprints)
	 * @param previous the player's previous build transform in the same dimension, or null
	 */
	public static Transform plan(PlacementSpec spec, Bounds bounds, PlayerPose pose, Transform previous, SurfaceHeight surface) {
		throw new UnsupportedOperationException("TODO");
	}
}
