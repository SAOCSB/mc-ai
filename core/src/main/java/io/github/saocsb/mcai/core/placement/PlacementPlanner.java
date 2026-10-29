package io.github.saocsb.mcai.core.placement;

import io.github.saocsb.mcai.core.blueprint.Bounds;

import java.util.Objects;

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
		return switch (playerFacing) {
			case NORTH -> Rotation.NONE;
			case EAST -> Rotation.CLOCKWISE_90;
			case SOUTH -> Rotation.CLOCKWISE_180;
			case WEST -> Rotation.COUNTERCLOCKWISE_90;
		};
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
	 * <p>A null spec means {@link PlacementSpec#defaults()}, a null mode FRONT, a null ground PLAYER, and a
	 * negative distance 0. SURFACE without a {@code surface} lookup falls back to {@code pose.groundY()}.
	 *
	 * @param bounds   blueprint bounds (non-null; callers skip empty blueprints)
	 * @param previous the player's previous build transform in the same dimension, or null
	 */
	public static Transform plan(PlacementSpec spec, Bounds bounds, PlayerPose pose, Transform previous, SurfaceHeight surface) {
		Objects.requireNonNull(bounds, "bounds");
		Objects.requireNonNull(pose, "pose");
		if (spec == null) {
			spec = PlacementSpec.defaults();
		}
		PlacementMode mode = spec.mode() == null ? PlacementMode.FRONT : spec.mode();
		if (mode == PlacementMode.PREVIOUS) {
			if (previous != null) {
				return previous;
			}
			mode = PlacementMode.FRONT;
		}
		if (mode == PlacementMode.ABSOLUTE) {
			return new Transform(
					spec.absX() != null ? spec.absX() : pose.blockX(),
					spec.absY() != null ? spec.absY() : pose.blockY(),
					spec.absZ() != null ? spec.absZ() : pose.blockZ(),
					Rotation.NONE);
		}
		int anchorX, anchorZ;
		switch (mode) {
			case FRONT -> {
				anchorX = bounds.centerX();
				anchorZ = bounds.maxZ() + 1 + Math.max(0, spec.distance());
			}
			case CENTER -> {
				anchorX = bounds.centerX();
				anchorZ = bounds.centerZ();
			}
			default -> {
				anchorX = 0;
				anchorZ = 0;
			}
		}
		Rotation rotation = rotationFor(pose.facing());
		int originX = pose.blockX() - rotation.rotateX(anchorX, anchorZ);
		int originZ = pose.blockZ() - rotation.rotateZ(anchorX, anchorZ);
		Transform transform = new Transform(originX, pose.groundY(), originZ, rotation);
		if (spec.ground() == GroundMode.SURFACE && surface != null) {
			int columnX = transform.worldX(bounds.centerX(), bounds.centerZ());
			int columnZ = transform.worldZ(bounds.centerX(), bounds.centerZ());
			transform = transform.withOriginY(surface.surfaceY(columnX, columnZ));
		}
		return transform;
	}
}
