package io.github.saocsb.mcai.core.placement;

/**
 * Local-to-world mapping: {@code world = origin + rotation(local)}, with y unrotated
 * ({@code worldY = originY + localY}).
 */
public record Transform(int originX, int originY, int originZ, Rotation rotation) {
	public int worldX(int x, int z) {
		return originX + rotation.rotateX(x, z);
	}

	public int worldY(int y) {
		return originY + y;
	}

	public int worldZ(int x, int z) {
		return originZ + rotation.rotateZ(x, z);
	}

	public Transform withOriginY(int y) {
		return new Transform(originX, y, originZ, rotation);
	}
}
