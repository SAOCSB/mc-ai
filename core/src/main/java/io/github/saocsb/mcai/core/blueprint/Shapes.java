package io.github.saocsb.mcai.core.blueprint;

import java.util.List;

/**
 * Voxel geometry generators. Every method writes into a {@link BlockSink} and is deterministic.
 * All corner arguments are inclusive and may be given in any order (min/max are taken).
 * Radii and sizes are doubles where fractional values make sense; a radius {@code <= 0}
 * produces a single block at the centre for spheres/cylinders. Negative heights/sizes throw
 * {@link IllegalArgumentException}.
 *
 * <p>Membership tests use block centres: a block (x,y,z) belongs to a sphere of radius r centred
 * on (cx,cy,cz) iff {@code dx*dx+dy*dy+dz*dz <= (r+0.5)^2} where d is the integer offset — this
 * gives round-looking shapes at small radii. "Hollow" means a 1-block-thick shell: blocks inside
 * the shape that have at least one 6-neighbour outside the shape.
 */
public final class Shapes {
	private Shapes() {
	}

	/** Solid cuboid. */
	public static void fill(BlockSink sink, int x1, int y1, int z1, int x2, int y2, int z2, String id) {
		throw new UnsupportedOperationException("TODO");
	}

	/** Cuboid shell: 4 walls + floor + ceiling, 1 thick; interior untouched. */
	public static void hollow(BlockSink sink, int x1, int y1, int z1, int x2, int y2, int z2, String id) {
		throw new UnsupportedOperationException("TODO");
	}

	/** The 4 vertical side walls of a cuboid (no floor/ceiling). */
	public static void walls(BlockSink sink, int x1, int y1, int z1, int x2, int y2, int z2, String id) {
		throw new UnsupportedOperationException("TODO");
	}

	/** The 12 edges of a cuboid. */
	public static void frame(BlockSink sink, int x1, int y1, int z1, int x2, int y2, int z2, String id) {
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * Straight 3D line between two block positions (both endpoints included, no gaps: consecutive
	 * blocks share a face, edge or corner). With {@code radius > 0} every line block is expanded to a
	 * solid sphere of that radius (a thick beam).
	 */
	public static void line(BlockSink sink, int x1, int y1, int z1, int x2, int y2, int z2, String id, double radius) {
		throw new UnsupportedOperationException("TODO");
	}

	public static void sphere(BlockSink sink, int cx, int cy, int cz, double r, String id, boolean hollow) {
		throw new UnsupportedOperationException("TODO");
	}

	/** Upper half of a sphere: blocks with y >= cy. */
	public static void dome(BlockSink sink, int cx, int cy, int cz, double r, String id, boolean hollow) {
		throw new UnsupportedOperationException("TODO");
	}

	public static void ellipsoid(BlockSink sink, int cx, int cy, int cz, double rx, double ry, double rz, String id, boolean hollow) {
		throw new UnsupportedOperationException("TODO");
	}

	/** Vertical cylinder occupying y .. y+height-1. Hollow = side wall only (open top/bottom). */
	public static void cylinder(BlockSink sink, int cx, int y, int cz, double r, int height, String id, boolean hollow) {
		throw new UnsupportedOperationException("TODO");
	}

	/** Vertical cone with base radius r at y, shrinking linearly to a point at y+height-1. Hollow = surface only. */
	public static void cone(BlockSink sink, int cx, int y, int cz, double r, int height, String id, boolean hollow) {
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * Stepped square pyramid: layer k (0-based from y) is the square |dx|,|dz| <= half-k, for
	 * k = 0..half. Hollow = only the outer surface of each layer (plus the full bottom layer).
	 */
	public static void pyramid(BlockSink sink, int cx, int y, int cz, int half, String id, boolean hollow) {
		throw new UnsupportedOperationException("TODO");
	}

	/** Horizontal torus (ring) centred at (cx,cy,cz): major radius R (centre of tube), minor radius r. */
	public static void torus(BlockSink sink, int cx, int cy, int cz, double majorR, double minorR, String id) {
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * Vertical prism whose footprint is the polygon {@code points} (list of [x, z] pairs, at least 3,
	 * implicitly closed), occupying y .. y+height-1. A block column is inside if its centre is inside
	 * the polygon (even-odd rule) or it lies on an edge. Hollow = only the polygon outline walls.
	 */
	public static void polygon(BlockSink sink, List<int[]> points, int y, int height, String id, boolean hollow) {
		throw new UnsupportedOperationException("TODO");
	}
}
