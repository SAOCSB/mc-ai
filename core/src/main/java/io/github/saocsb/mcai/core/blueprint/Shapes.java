package io.github.saocsb.mcai.core.blueprint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
 *
 * <p>Each position is written at most once per call (except overlapping parts of thick lines),
 * in layers from the bottom up (thick lines: along the line). Negative radii count as 0; NaN or
 * infinite radii, radii above {@link #MAX_RADIUS}, polygon outlines longer than {@link #MAX_OUTLINE}
 * and null or blank ids throw {@link IllegalArgumentException}. Shapes are written block by block, so
 * a {@link Blueprint} sink stops an oversized shape early with its limit exception.
 */
public final class Shapes {
	/** Largest accepted radius. */
	public static final double MAX_RADIUS = 65_536;
	/** Largest accepted polygon outline length in blocks. */
	public static final int MAX_OUTLINE = 1 << 22;

	private Shapes() {
	}

	/** Half-width of the row at offsets (dy, dz): the row covers dx in [-w, w], or nothing when w < 0. */
	@FunctionalInterface
	private interface HalfWidth {
		int at(int dy, int dz);
	}

	/** Solid cuboid. */
	public static void fill(BlockSink sink, int x1, int y1, int z1, int x2, int y2, int z2, String id) {
		check(sink, id);
		int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
		int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
		for (long y = Math.min(y1, y2), maxY = Math.max(y1, y2); y <= maxY; y++) {
			for (long z = minZ; z <= maxZ; z++) {
				row(sink, minX, maxX, (int) y, (int) z, id);
			}
		}
	}

	/** Cuboid shell: 4 walls + floor + ceiling, 1 thick; interior untouched. */
	public static void hollow(BlockSink sink, int x1, int y1, int z1, int x2, int y2, int z2, String id) {
		check(sink, id);
		int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
		for (long y = minY; y <= maxY; y++) {
			if (y == minY || y == maxY) {
				fill(sink, x1, (int) y, z1, x2, (int) y, z2, id);
			} else {
				rectangle(sink, x1, z1, x2, z2, (int) y, id);
			}
		}
	}

	/** The 4 vertical side walls of a cuboid (no floor/ceiling). */
	public static void walls(BlockSink sink, int x1, int y1, int z1, int x2, int y2, int z2, String id) {
		check(sink, id);
		for (long y = Math.min(y1, y2), maxY = Math.max(y1, y2); y <= maxY; y++) {
			rectangle(sink, x1, z1, x2, z2, (int) y, id);
		}
	}

	/** The 12 edges of a cuboid. */
	public static void frame(BlockSink sink, int x1, int y1, int z1, int x2, int y2, int z2, String id) {
		check(sink, id);
		int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
		int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
		int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
		for (long y = minY; y <= maxY; y++) {
			if (y == minY || y == maxY) {
				rectangle(sink, minX, minZ, maxX, maxZ, (int) y, id);
			} else {
				sink.set(minX, (int) y, minZ, id);
				if (maxX != minX) {
					sink.set(maxX, (int) y, minZ, id);
				}
				if (maxZ != minZ) {
					sink.set(minX, (int) y, maxZ, id);
					if (maxX != minX) {
						sink.set(maxX, (int) y, maxZ, id);
					}
				}
			}
		}
	}

	/**
	 * Straight 3D line between two block positions (both endpoints included, no gaps: consecutive
	 * blocks share a face, edge or corner). With {@code radius > 0} every line block is expanded to a
	 * solid sphere of that radius (a thick beam).
	 *
	 * <p>The thin line has {@code max(|dx|,|dy|,|dz|) + 1} blocks; step i is at
	 * {@code start + round(d * i / n)} (halves rounded up). A thick line is exactly the union of the
	 * spheres; each line block only writes the part of its sphere that the previous block's sphere
	 * did not cover.
	 */
	public static void line(BlockSink sink, int x1, int y1, int z1, int x2, int y2, int z2, String id, double radius) {
		check(sink, id);
		double r = radius(radius, "line");
		long dx = (long) x2 - x1, dy = (long) y2 - y1, dz = (long) z2 - z1;
		long n = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
		if (r == 0) {
			for (long i = 0; i <= n; i++) {
				sink.set(lerp(x1, dx, i, n), lerp(y1, dy, i, n), lerp(z1, dz, i, n), id);
			}
			return;
		}
		double r2 = (r + 0.5) * (r + 0.5);
		int reach = (int) Math.floor(r + 0.5);
		HalfWidth ball = (oy, oz) -> sphereHalfWidth(r2, (long) oy * oy + (long) oz * oz);
		int px = x1, py = y1, pz = z1;
		for (long i = 0; i <= n; i++) {
			int x = lerp(x1, dx, i, n), y = lerp(y1, dy, i, n), z = lerp(z1, dz, i, n);
			int sx = x - px, sy = y - py, sz = z - pz;
			for (int oy = -reach; oy <= reach; oy++) {
				for (int oz = -reach; oz <= reach; oz++) {
					int w = ball.at(oy, oz);
					if (w < 0) {
						continue;
					}
					if (i == 0) {
						row(sink, x - w, x + w, y + oy, z + oz, id);
						continue;
					}
					// Skip offsets o whose position was covered by the previous sphere (o + step inside the ball).
					int previous = ball.at(oy + sy, oz + sz);
					if (previous < 0) {
						row(sink, x - w, x + w, y + oy, z + oz, id);
					} else {
						int coveredLo = -previous - sx, coveredHi = previous - sx;
						row(sink, x - w, x + Math.min(w, coveredLo - 1), y + oy, z + oz, id);
						row(sink, x + Math.max(-w, coveredHi + 1), x + w, y + oy, z + oz, id);
					}
				}
			}
			px = x;
			py = y;
			pz = z;
		}
	}

	public static void sphere(BlockSink sink, int cx, int cy, int cz, double r, String id, boolean hollow) {
		check(sink, id);
		double radius = radius(r, "sphere");
		ball(sink, cx, cy, cz, radius, id, hollow, false);
	}

	/**
	 * Upper half of a sphere: blocks with y >= cy. A hollow dome is the hollow sphere's shell cut at
	 * cy, so it is open at the bottom.
	 */
	public static void dome(BlockSink sink, int cx, int cy, int cz, double r, String id, boolean hollow) {
		check(sink, id);
		double radius = radius(r, "dome");
		ball(sink, cx, cy, cz, radius, id, hollow, true);
	}

	/**
	 * Axis-aligned ellipsoid: a block belongs to it iff
	 * {@code dx²/(rx+0.5)² + dy²/(ry+0.5)² + dz²/(rz+0.5)² <= 1}. A radius of 0 makes it one block
	 * thick along that axis.
	 */
	public static void ellipsoid(BlockSink sink, int cx, int cy, int cz, double rx, double ry, double rz, String id, boolean hollow) {
		check(sink, id);
		double ax = radius(rx, "ellipsoid") + 0.5, ay = radius(ry, "ellipsoid") + 0.5, az = radius(rz, "ellipsoid") + 0.5;
		double ax2 = ax * ax, ay2 = ay * ay, az2 = az * az;
		int reachY = (int) Math.floor(ay), reachZ = (int) Math.floor(az);
		HalfWidth hw = (dy, dz) -> {
			double rest = (double) dy * dy / ay2 + (double) dz * dz / az2;
			if (rest > 1.0) {
				return -1;
			}
			int w = (int) Math.floor(ax * Math.sqrt(1.0 - rest));
			while ((double) (w + 1) * (w + 1) / ax2 + (double) dy * dy / ay2 + (double) dz * dz / az2 <= 1.0) {
				w++;
			}
			while (w >= 0 && (double) w * w / ax2 + (double) dy * dy / ay2 + (double) dz * dz / az2 > 1.0) {
				w--;
			}
			return w;
		};
		rows(sink, cx, cy, cz, -reachY, reachY, reachZ, hw, hollow, true, id);
	}

	/** Vertical cylinder occupying y .. y+height-1. Hollow = side wall only (open top/bottom). */
	public static void cylinder(BlockSink sink, int cx, int y, int cz, double r, int height, String id, boolean hollow) {
		check(sink, id);
		double radius = radius(r, "cylinder");
		height(height, "cylinder");
		double r2 = (radius + 0.5) * (radius + 0.5);
		int reach = (int) Math.floor(radius + 0.5);
		HalfWidth hw = (dy, dz) -> dy < 0 || dy >= height ? -1 : sphereHalfWidth(r2, (long) dz * dz);
		rows(sink, cx, y, cz, 0, height - 1, reach, hw, hollow, false, id);
	}

	/**
	 * Vertical cone with base radius r at y, shrinking linearly to a point at y+height-1. Hollow = surface only.
	 * Layer k has radius {@code r * (height-1-k) / (height-1)} (r when height is 1). The hollow shell
	 * includes the base disc.
	 */
	public static void cone(BlockSink sink, int cx, int y, int cz, double r, int height, String id, boolean hollow) {
		check(sink, id);
		double radius = radius(r, "cone");
		height(height, "cone");
		double[] layerR2 = new double[height];
		for (int k = 0; k < height; k++) {
			double rk = height == 1 ? radius : radius * (height - 1 - k) / (height - 1);
			layerR2[k] = (rk + 0.5) * (rk + 0.5);
		}
		int reach = (int) Math.floor(radius + 0.5);
		HalfWidth hw = (dy, dz) -> dy < 0 || dy >= height ? -1 : sphereHalfWidth(layerR2[dy], (long) dz * dz);
		rows(sink, cx, y, cz, 0, height - 1, reach, hw, hollow, true, id);
	}

	/**
	 * Stepped square pyramid: layer k (0-based from y) is the square |dx|,|dz| <= half-k, for
	 * k = 0..half. Hollow = only the outer surface of each layer (plus the full bottom layer).
	 */
	public static void pyramid(BlockSink sink, int cx, int y, int cz, int half, String id, boolean hollow) {
		check(sink, id);
		if (half < 0) {
			throw new IllegalArgumentException("pyramid(): half must be >= 0, got " + half);
		}
		HalfWidth hw = (dy, dz) -> {
			int side = half - dy;
			return dy < 0 || dy > half || Math.abs(dz) > side ? -1 : side;
		};
		rows(sink, cx, y, cz, 0, half, half, hw, hollow, true, id);
	}

	/**
	 * Horizontal torus (ring) centred at (cx,cy,cz): major radius R (centre of tube), minor radius r.
	 * A block belongs to it iff {@code (sqrt(dx²+dz²) - R)² + dy² <= (r+0.5)²}.
	 */
	public static void torus(BlockSink sink, int cx, int cy, int cz, double majorR, double minorR, String id) {
		check(sink, id);
		double major = radius(majorR, "torus");
		double minor = radius(minorR, "torus");
		double r2 = (minor + 0.5) * (minor + 0.5);
		int reachY = (int) Math.floor(minor + 0.5);
		int reach = (int) Math.floor(major + minor + 0.5);
		for (int dy = -reachY; dy <= reachY; dy++) {
			for (int dz = -reach; dz <= reach; dz++) {
				for (int dx = -reach; dx <= reach; dx++) {
					if (inTorus(dx, dy, dz, major, r2)) {
						sink.set(cx + dx, cy + dy, cz + dz, id);
					}
				}
			}
		}
	}

	/**
	 * Vertical prism whose footprint is the polygon {@code points} (list of [x, z] pairs, at least 3,
	 * implicitly closed), occupying y .. y+height-1. A block column is inside if its centre is inside
	 * the polygon (even-odd rule) or it lies on an edge. Hollow = only the polygon outline walls.
	 *
	 * <p>"On an edge" means on the edge as drawn by {@link #line} (a gap-free 2D line), so the outline
	 * is closed even along diagonal edges.
	 */
	public static void polygon(BlockSink sink, List<int[]> points, int y, int height, String id, boolean hollow) {
		check(sink, id);
		height(height, "polygon");
		if (points == null || points.size() < 3) {
			throw new IllegalArgumentException("polygon(): needs at least 3 points, got " + (points == null ? 0 : points.size()));
		}
		int count = points.size();
		int[] xs = new int[count], zs = new int[count];
		for (int i = 0; i < count; i++) {
			int[] p = points.get(i);
			if (p == null || p.length < 2) {
				throw new IllegalArgumentException("polygon(): point " + i + " must be an [x, z] pair");
			}
			xs[i] = p[0];
			zs[i] = p[1];
		}
		long[] outline = outlineCells(xs, zs);
		int minZ = Arrays.stream(zs).min().orElseThrow(), maxZ = Arrays.stream(zs).max().orElseThrow();
		// Spans per footprint row, computed during the first layer (so a rejecting sink fails early).
		List<int[]> rowSpans = new ArrayList<>();
		int cursor = 0;
		for (long layer = 0; layer < height; layer++) {
			int wy = (int) (y + layer);
			for (long z = minZ; z <= maxZ; z++) {
				int[] spans;
				if (layer == 0) {
					int start = cursor;
					while (cursor < outline.length && cellZ(outline[cursor]) == z) {
						cursor++;
					}
					int[] edgeCells = new int[cursor - start];
					for (int k = 0; k < edgeCells.length; k++) {
						edgeCells[k] = cellX(outline[start + k]);
					}
					spans = spans(xs, zs, (int) z, edgeCells, !hollow);
					rowSpans.add(spans);
				} else {
					spans = rowSpans.get((int) (z - minZ));
				}
				for (int k = 0; k < spans.length; k += 2) {
					row(sink, spans[k], spans[k + 1], wy, (int) z, id);
				}
			}
		}
	}

	/** The polygon's edges drawn like {@link #line}, as packed (z, x) cells sorted by z then x, without duplicates. */
	private static long[] outlineCells(int[] xs, int[] zs) {
		int count = xs.length;
		long total = 0;
		for (int i = 0; i < count; i++) {
			int j = (i + 1) % count;
			total += Math.max(1, Math.max(Math.abs((long) xs[j] - xs[i]), Math.abs((long) zs[j] - zs[i])));
		}
		if (total > MAX_OUTLINE) {
			throw new IllegalArgumentException("polygon(): outline is too long (" + total + " blocks, max " + MAX_OUTLINE + ")");
		}
		long[] cells = new long[(int) total];
		int used = 0;
		for (int i = 0; i < count; i++) {
			int j = (i + 1) % count;
			long dx = (long) xs[j] - xs[i], dz = (long) zs[j] - zs[i];
			long n = Math.max(Math.abs(dx), Math.abs(dz));
			// Steps 0..n-1: the end point is the next edge's start.
			for (long s = 0; s < Math.max(n, 1); s++) {
				cells[used++] = cell(lerp(xs[i], dx, s, n), lerp(zs[i], dz, s, n));
			}
		}
		Arrays.sort(cells);
		int distinct = 0;
		for (int i = 0; i < used; i++) {
			if (distinct == 0 || cells[i] != cells[distinct - 1]) {
				cells[distinct++] = cells[i];
			}
		}
		return Arrays.copyOf(cells, distinct);
	}

	private static long cell(int x, int z) {
		return ((long) z << 32) | ((long) x - Integer.MIN_VALUE);
	}

	private static int cellZ(long cell) {
		return (int) (cell >> 32);
	}

	private static int cellX(long cell) {
		return (int) ((cell & 0xFFFFFFFFL) + Integer.MIN_VALUE);
	}

	/**
	 * Sorted, disjoint, non-adjacent [from, to] intervals (flattened) covering the outline cells of row z
	 * and, with {@code interior}, the cells whose centre is inside the polygon (even-odd rule).
	 */
	private static int[] spans(int[] xs, int[] zs, int z, int[] edgeCells, boolean interior) {
		int count = interior ? xs.length : 0;
		double[] crossings = new double[count];
		int found = 0;
		for (int i = 0; i < count; i++) {
			int j = (i + 1) % count;
			if ((zs[i] > z) != (zs[j] > z)) {
				crossings[found++] = xs[i] + (double) ((long) (z - zs[i]) * ((long) xs[j] - xs[i])) / ((long) zs[j] - zs[i]);
			}
		}
		Arrays.sort(crossings, 0, found);
		List<long[]> intervals = new ArrayList<>();
		for (int k = 0; k + 1 < found; k += 2) {
			long from = (long) Math.ceil(crossings[k]), to = (long) Math.floor(crossings[k + 1]);
			if (from <= to) {
				intervals.add(new long[] {from, to});
			}
		}
		for (int x : edgeCells) {
			intervals.add(new long[] {x, x});
		}
		intervals.sort((a, b) -> Long.compare(a[0], b[0]));
		long[] merged = new long[intervals.size() * 2];
		int m = 0;
		for (long[] interval : intervals) {
			if (m > 0 && interval[0] <= merged[m - 1] + 1) {
				merged[m - 1] = Math.max(merged[m - 1], interval[1]);
			} else {
				merged[m++] = interval[0];
				merged[m++] = interval[1];
			}
		}
		int[] result = new int[m];
		for (int i = 0; i < m; i++) {
			result[i] = (int) merged[i];
		}
		return result;
	}

	private static void ball(BlockSink sink, int cx, int cy, int cz, double r, String id, boolean hollow, boolean upperHalf) {
		double r2 = (r + 0.5) * (r + 0.5);
		int reach = (int) Math.floor(r + 0.5);
		HalfWidth hw = (dy, dz) -> sphereHalfWidth(r2, (long) dy * dy + (long) dz * dz);
		rows(sink, cx, cy, cz, upperHalf ? 0 : -reach, reach, reach, hw, hollow, true, id);
	}

	/**
	 * Writes a shape made of x-rows symmetric about cx. {@code hw} must return -1 for every row outside
	 * the shape (also beyond the scanned range, for the neighbour tests). Hollow keeps blocks with a
	 * neighbour outside: along x, along z, and along y when {@code vertical} is set.
	 */
	private static void rows(BlockSink sink, int cx, int cy, int cz, int dyFrom, int dyTo, int reachZ, HalfWidth hw,
			boolean hollow, boolean vertical, String id) {
		for (int dy = dyFrom; dy <= dyTo; dy++) {
			for (int dz = -reachZ; dz <= reachZ; dz++) {
				int w = hw.at(dy, dz);
				if (w < 0) {
					continue;
				}
				int y = cy + dy, z = cz + dz;
				if (!hollow) {
					row(sink, cx - w, cx + w, y, z, id);
					continue;
				}
				int inner = Math.min(hw.at(dy, dz - 1), hw.at(dy, dz + 1));
				if (vertical) {
					inner = Math.min(inner, Math.min(hw.at(dy - 1, dz), hw.at(dy + 1, dz)));
				}
				if (inner < 0) {
					row(sink, cx - w, cx + w, y, z, id);
				} else if (inner >= w) {
					sink.set(cx - w, y, z, id);
					if (w > 0) {
						sink.set(cx + w, y, z, id);
					}
				} else {
					row(sink, cx - w, cx - inner - 1, y, z, id);
					row(sink, cx + inner + 1, cx + w, y, z, id);
				}
			}
		}
	}

	/** Largest w with {@code w² + rest <= r2}, or -1 when even w = 0 is outside. */
	private static int sphereHalfWidth(double r2, long rest) {
		if (rest > r2) {
			return -1;
		}
		int w = (int) Math.sqrt(r2 - rest);
		while ((double) ((long) (w + 1) * (w + 1) + rest) <= r2) {
			w++;
		}
		while (w >= 0 && (double) ((long) w * w + rest) > r2) {
			w--;
		}
		return w;
	}

	private static boolean inTorus(int dx, int dy, int dz, double major, double r2) {
		double t = Math.sqrt((double) dx * dx + (double) dz * dz) - major;
		return t * t + (double) dy * dy <= r2;
	}

	/** The perimeter of a horizontal rectangle at height y, each block once. */
	private static void rectangle(BlockSink sink, int x1, int z1, int x2, int z2, int y, String id) {
		int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
		int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
		row(sink, minX, maxX, y, minZ, id);
		for (long z = (long) minZ + 1; z < maxZ; z++) {
			sink.set(minX, y, (int) z, id);
			if (maxX != minX) {
				sink.set(maxX, y, (int) z, id);
			}
		}
		if (maxZ != minZ) {
			row(sink, minX, maxX, y, maxZ, id);
		}
	}

	/** Blocks fromX..toX (inclusive; nothing when fromX > toX) at (y, z). */
	private static void row(BlockSink sink, int fromX, int toX, int y, int z, String id) {
		for (long x = fromX; x <= toX; x++) {
			sink.set((int) x, y, z, id);
		}
	}

	/** {@code start + round(delta * i / n)} with halves rounded up; {@code start} when n is 0. */
	private static int lerp(int start, long delta, long i, long n) {
		return n == 0 ? start : (int) (start + Math.floorDiv(2 * delta * i + n, 2 * n));
	}

	private static void check(BlockSink sink, String id) {
		Objects.requireNonNull(sink, "sink");
		if (id == null) {
			throw new IllegalArgumentException("block id must not be null");
		}
		if (id.isBlank()) {
			throw new IllegalArgumentException("block id must not be blank");
		}
	}

	private static double radius(double r, String fn) {
		if (!Double.isFinite(r)) {
			throw new IllegalArgumentException(fn + "(): radius must be a finite number, got " + r);
		}
		if (r > MAX_RADIUS) {
			throw new IllegalArgumentException(fn + "(): radius " + r + " is too large (max " + (int) MAX_RADIUS + ")");
		}
		return Math.max(r, 0);
	}

	private static void height(int height, String fn) {
		if (height < 0) {
			throw new IllegalArgumentException(fn + "(): height must be >= 0, got " + height);
		}
	}
}
