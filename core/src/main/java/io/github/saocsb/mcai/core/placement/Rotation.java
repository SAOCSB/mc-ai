package io.github.saocsb.mcai.core.placement;

/**
 * Rotation about the vertical axis, mirroring Minecraft's {@code Rotation} enum (same constant
 * names and the same meaning: clockwise when viewed from above, with x = east and z = south).
 */
public enum Rotation {
	NONE,
	CLOCKWISE_90,
	CLOCKWISE_180,
	COUNTERCLOCKWISE_90;

	/** X of the rotated horizontal vector. CLOCKWISE_90 maps (x, z) to (-z, x). */
	public int rotateX(int x, int z) {
		return switch (this) {
			case NONE -> x;
			case CLOCKWISE_90 -> -z;
			case CLOCKWISE_180 -> -x;
			case COUNTERCLOCKWISE_90 -> z;
		};
	}

	/** Z of the rotated horizontal vector. */
	public int rotateZ(int x, int z) {
		return switch (this) {
			case NONE -> z;
			case CLOCKWISE_90 -> x;
			case CLOCKWISE_180 -> -z;
			case COUNTERCLOCKWISE_90 -> -x;
		};
	}
}
