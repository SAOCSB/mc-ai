package io.github.saocsb.mcai.core.placement;

/** Horizontal cardinal direction (x = east, z = south, like Minecraft). */
public enum Facing {
	NORTH(0, -1),
	EAST(1, 0),
	SOUTH(0, 1),
	WEST(-1, 0);

	public final int dx;
	public final int dz;

	Facing(int dx, int dz) {
		this.dx = dx;
		this.dz = dz;
	}

	/**
	 * Nearest cardinal direction for a Minecraft yaw in degrees (0 = south, 90 = west,
	 * 180 = north, -90/270 = east); any real value is accepted.
	 */
	public static Facing fromYaw(float yaw) {
		int quadrant = Math.floorMod((int) Math.floor(yaw / 90.0 + 0.5), 4);
		return switch (quadrant) {
			case 0 -> SOUTH;
			case 1 -> WEST;
			case 2 -> NORTH;
			default -> EAST;
		};
	}

	public String lowerName() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
