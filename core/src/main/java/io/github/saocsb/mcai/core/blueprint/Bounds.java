package io.github.saocsb.mcai.core.blueprint;

/** Inclusive integer bounding box in local coordinates. */
public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	public int sizeX() {
		return maxX - minX + 1;
	}

	public int sizeY() {
		return maxY - minY + 1;
	}

	public int sizeZ() {
		return maxZ - minZ + 1;
	}

	/** Floor of the horizontal centre on the X axis. */
	public int centerX() {
		return Math.floorDiv(minX + maxX, 2);
	}

	/** Floor of the horizontal centre on the Z axis. */
	public int centerZ() {
		return Math.floorDiv(minZ + maxZ, 2);
	}
}
