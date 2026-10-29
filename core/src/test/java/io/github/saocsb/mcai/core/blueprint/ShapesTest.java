package io.github.saocsb.mcai.core.blueprint;

import io.github.saocsb.mcai.core.blueprint.RecordingSink.Pos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapesTest {
	private static final String ID = "minecraft:stone";

	@FunctionalInterface
	private interface Inside {
		boolean test(int x, int y, int z);
	}

	private static RecordingSink run(Consumer<BlockSink> shape) {
		RecordingSink sink = new RecordingSink();
		shape.accept(sink);
		return sink;
	}

	/** Brute force: every position of the box that passes the test. */
	private static Set<Pos> where(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Inside inside) {
		Set<Pos> out = new HashSet<>();
		for (int y = minY; y <= maxY; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					if (inside.test(x, y, z)) {
						out.add(new Pos(x, y, z));
					}
				}
			}
		}
		return out;
	}

	/** Positions of {@code solid} with at least one 6-neighbour outside it. */
	private static Set<Pos> shell(Set<Pos> solid) {
		Set<Pos> out = new HashSet<>();
		for (Pos p : solid) {
			if (!solid.contains(p.offset(1, 0, 0)) || !solid.contains(p.offset(-1, 0, 0))
					|| !solid.contains(p.offset(0, 1, 0)) || !solid.contains(p.offset(0, -1, 0))
					|| !solid.contains(p.offset(0, 0, 1)) || !solid.contains(p.offset(0, 0, -1))) {
				out.add(p);
			}
		}
		return out;
	}

	/** Positions of {@code solid} with at least one horizontal 4-neighbour outside it. */
	private static Set<Pos> ring(Set<Pos> solid) {
		Set<Pos> out = new HashSet<>();
		for (Pos p : solid) {
			if (!solid.contains(p.offset(1, 0, 0)) || !solid.contains(p.offset(-1, 0, 0))
					|| !solid.contains(p.offset(0, 0, 1)) || !solid.contains(p.offset(0, 0, -1))) {
				out.add(p);
			}
		}
		return out;
	}

	private static void assertClean(RecordingSink sink) {
		assertFalse(sink.hasDuplicateWrites(), "a position was written twice");
		assertTrue(sink.writtenBottomUp(), "writes are not bottom-up");
		for (String id : sink.blocks.values()) {
			assertEquals(ID, id);
		}
	}

	private static void assertSymmetric(Set<Pos> set, int cx, int cy, int cz, boolean vertical) {
		for (Pos p : set) {
			int dx = p.x() - cx, dy = p.y() - cy, dz = p.z() - cz;
			assertTrue(set.contains(new Pos(cx - dx, cy + dy, cz + dz)), "x mirror of " + p);
			assertTrue(set.contains(new Pos(cx + dx, cy + dy, cz - dz)), "z mirror of " + p);
			assertTrue(set.contains(new Pos(cx + dz, cy + dy, cz + dx)), "x/z swap of " + p);
			if (vertical) {
				assertTrue(set.contains(new Pos(cx + dx, cy - dy, cz + dz)), "y mirror of " + p);
				assertTrue(set.contains(new Pos(cx + dy, cy + dx, cz + dz)), "x/y swap of " + p);
			}
		}
	}

	// ---- cuboids ----

	private static final int[][] BOXES = {
			{0, 0, 0, 4, 3, 5}, {2, 7, -3, -4, 1, 2}, {0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 5, 0}, {0, 0, 0, 6, 0, 0},
			{0, 0, 0, 3, 3, 0}, {0, 0, 0, 1, 1, 1}, {-1, -1, -1, 1, 1, 1}, {5, 5, 5, 6, 9, 5},
	};

	@Test
	void fillIsTheSolidBox() {
		for (int[] b : BOXES) {
			RecordingSink sink = run(s -> Shapes.fill(s, b[0], b[1], b[2], b[3], b[4], b[5], ID));
			assertEquals(where(Math.min(b[0], b[3]), Math.min(b[1], b[4]), Math.min(b[2], b[5]),
					Math.max(b[0], b[3]), Math.max(b[1], b[4]), Math.max(b[2], b[5]), (x, y, z) -> true), sink.positions());
			assertClean(sink);
		}
		assertEquals(120, run(s -> Shapes.fill(s, 0, 0, 0, 3, 4, 5, ID)).writeCount());
	}

	@Test
	void hollowWallsAndFrameMatchTheirDefinitions() {
		for (int[] b : BOXES) {
			int minX = Math.min(b[0], b[3]), maxX = Math.max(b[0], b[3]);
			int minY = Math.min(b[1], b[4]), maxY = Math.max(b[1], b[4]);
			int minZ = Math.min(b[2], b[5]), maxZ = Math.max(b[2], b[5]);
			RecordingSink hollow = run(s -> Shapes.hollow(s, b[0], b[1], b[2], b[3], b[4], b[5], ID));
			assertEquals(where(minX, minY, minZ, maxX, maxY, maxZ,
					(x, y, z) -> x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ), hollow.positions());
			assertClean(hollow);

			RecordingSink walls = run(s -> Shapes.walls(s, b[0], b[1], b[2], b[3], b[4], b[5], ID));
			assertEquals(where(minX, minY, minZ, maxX, maxY, maxZ,
					(x, y, z) -> x == minX || x == maxX || z == minZ || z == maxZ), walls.positions());
			assertClean(walls);

			RecordingSink frame = run(s -> Shapes.frame(s, b[0], b[1], b[2], b[3], b[4], b[5], ID));
			assertEquals(where(minX, minY, minZ, maxX, maxY, maxZ, (x, y, z) -> {
				int onFaces = (x == minX || x == maxX ? 1 : 0) + (y == minY || y == maxY ? 1 : 0) + (z == minZ || z == maxZ ? 1 : 0);
				return onFaces >= 2;
			}), frame.positions());
			assertClean(frame);
		}
	}

	@Test
	void cuboidCounts() {
		assertEquals(5 * 5 * 5 - 3 * 3 * 3, run(s -> Shapes.hollow(s, 0, 0, 0, 4, 4, 4, ID)).writeCount());
		assertEquals(16 * 5, run(s -> Shapes.walls(s, 0, 0, 0, 4, 4, 4, ID)).writeCount());
		assertEquals(44, run(s -> Shapes.frame(s, 0, 0, 0, 4, 4, 4, ID)).writeCount());
		assertEquals(8, run(s -> Shapes.frame(s, 0, 0, 0, 1, 1, 1, ID)).writeCount());
	}

	@Test
	void cornersMayBeGivenInAnyOrder() {
		int[] a = {-2, 1, 3}, b = {4, 6, -1};
		List<Consumer<BlockSink>> variants = new ArrayList<>();
		for (int mask = 0; mask < 8; mask++) {
			int[] p = new int[3], q = new int[3];
			for (int axis = 0; axis < 3; axis++) {
				boolean swap = (mask & (1 << axis)) != 0;
				p[axis] = swap ? b[axis] : a[axis];
				q[axis] = swap ? a[axis] : b[axis];
			}
			variants.add(s -> {
				Shapes.fill(s, p[0], p[1], p[2], q[0], q[1], q[2], "a");
				Shapes.hollow(s, p[0], p[1] + 10, p[2], q[0], q[1] + 10, q[2], "b");
				Shapes.walls(s, p[0], p[1] + 20, p[2], q[0], q[1] + 20, q[2], "c");
				Shapes.frame(s, p[0], p[1] + 30, p[2], q[0], q[1] + 30, q[2], "d");
			});
		}
		RecordingSink reference = run(variants.get(0));
		for (Consumer<BlockSink> variant : variants) {
			assertEquals(reference.blocks, run(variant).blocks);
		}
	}

	// ---- lines ----

	@Test
	void lineIncludesEndpointsAndIsConnected() {
		Random random = new Random(99);
		for (int trial = 0; trial < 500; trial++) {
			int x1 = random.nextInt(41) - 20, y1 = random.nextInt(41) - 20, z1 = random.nextInt(41) - 20;
			int x2 = random.nextInt(41) - 20, y2 = random.nextInt(41) - 20, z2 = random.nextInt(41) - 20;
			RecordingSink sink = run(s -> Shapes.line(s, x1, y1, z1, x2, y2, z2, ID, 0));
			List<Pos> w = sink.writes;
			int n = Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)));
			assertEquals(n + 1, w.size());
			assertFalse(sink.hasDuplicateWrites());
			assertEquals(new Pos(x1, y1, z1), w.get(0));
			assertEquals(new Pos(x2, y2, z2), w.get(w.size() - 1));
			for (int i = 1; i < w.size(); i++) {
				Pos a = w.get(i - 1), b = w.get(i);
				int step = Math.max(Math.abs(a.x() - b.x()), Math.max(Math.abs(a.y() - b.y()), Math.abs(a.z() - b.z())));
				assertEquals(1, step, "gap between " + a + " and " + b);
			}
			// Reversing the direction gives a line of the same length.
			assertEquals(n + 1, run(s -> Shapes.line(s, x2, y2, z2, x1, y1, z1, ID, 0)).writeCount());
		}
	}

	@Test
	void straightAndDiagonalLines() {
		assertEquals(run(s -> Shapes.fill(s, 3, 2, 1, 3, 2, 9, ID)).positions(), run(s -> Shapes.line(s, 3, 2, 9, 3, 2, 1, ID, 0)).positions());
		Set<Pos> diagonal = new HashSet<>();
		for (int i = 0; i <= 5; i++) {
			diagonal.add(new Pos(i, -i, i));
		}
		assertEquals(diagonal, run(s -> Shapes.line(s, 0, 0, 0, 5, -5, 5, ID, 0)).positions());
		assertEquals(Set.of(new Pos(4, 4, 4)), run(s -> Shapes.line(s, 4, 4, 4, 4, 4, 4, ID, 0)).positions());
		assertEquals(Set.of(new Pos(4, 4, 4)), run(s -> Shapes.line(s, 4, 4, 4, 4, 4, 4, ID, -3)).positions());
		// A shallow line stays within half a block of the true line.
		for (Pos p : run(s -> Shapes.line(s, 0, 0, 0, 10, 3, 0, ID, 0)).writes) {
			assertTrue(Math.abs(p.y() - p.x() * 0.3) <= 0.5 + 1e-9, p.toString());
		}
	}

	private static Set<Pos> thickLineBruteForce(List<Pos> thin, double r) {
		Set<Pos> out = new HashSet<>();
		int reach = (int) Math.ceil(r + 0.5);
		double r2 = (r + 0.5) * (r + 0.5);
		for (Pos p : thin) {
			for (int dx = -reach; dx <= reach; dx++) {
				for (int dy = -reach; dy <= reach; dy++) {
					for (int dz = -reach; dz <= reach; dz++) {
						if (dx * dx + dy * dy + dz * dz <= r2) {
							out.add(p.offset(dx, dy, dz));
						}
					}
				}
			}
		}
		return out;
	}

	@Test
	void thickLineIsTheUnionOfSpheres() {
		int[][] lines = {{0, 0, 0, 12, 0, 0}, {0, 0, 0, 7, 7, 7}, {-3, 2, 5, 9, -4, 1}, {0, 0, 0, 0, 10, 1}, {1, 1, 1, 1, 1, 1},
				{0, 0, 0, 15, 4, -6}};
		double[] radii = {0.4, 0.5, 1, 1.5, 2, 2.7, 3, 4.2};
		for (int[] l : lines) {
			List<Pos> thin = run(s -> Shapes.line(s, l[0], l[1], l[2], l[3], l[4], l[5], ID, 0)).writes;
			for (double r : radii) {
				RecordingSink thick = run(s -> Shapes.line(s, l[0], l[1], l[2], l[3], l[4], l[5], ID, r));
				assertEquals(thickLineBruteForce(thin, r), thick.positions(), "line " + List.of(l[0], l[3]) + " r=" + r);
			}
		}
	}

	@Test
	void thickLineDoesNotRestampWholeSpheres() {
		double r = 3;
		int ball = run(s -> Shapes.sphere(s, 0, 0, 0, r, ID, false)).writeCount();
		int straight = run(s -> Shapes.line(s, 0, 0, 0, 40, 0, 0, ID, r)).writeCount();
		assertTrue(straight < 41 * ball / 3, straight + " writes for a straight beam");
		int diagonal = run(s -> Shapes.line(s, 0, 0, 0, 40, 40, 40, ID, r)).writeCount();
		assertTrue(diagonal < 41 * ball * 3 / 4, diagonal + " writes for a diagonal beam");
	}

	// ---- spheres ----

	private static Set<Pos> sphereSet(int cx, int cy, int cz, double r) {
		double rr = Math.max(r, 0);
		double r2 = (rr + 0.5) * (rr + 0.5);
		int reach = (int) Math.ceil(rr) + 1;
		return where(cx - reach, cy - reach, cz - reach, cx + reach, cy + reach, cz + reach, (x, y, z) -> {
			int dx = x - cx, dy = y - cy, dz = z - cz;
			return dx * dx + dy * dy + dz * dz <= r2;
		});
	}

	@ParameterizedTest
	@ValueSource(doubles = {0, 0.3, 0.5, 1, 1.5, 2, 2.3, 3, 4.5, 6, 7.7, 10})
	void sphereMatchesBruteForce(double r) {
		Set<Pos> solid = sphereSet(3, -2, 5, r);
		RecordingSink sink = run(s -> Shapes.sphere(s, 3, -2, 5, r, ID, false));
		assertEquals(solid, sink.positions());
		assertClean(sink);
		assertSymmetric(sink.positions(), 3, -2, 5, true);

		RecordingSink hollow = run(s -> Shapes.sphere(s, 3, -2, 5, r, ID, true));
		assertEquals(shell(solid), hollow.positions());
		assertTrue(solid.containsAll(hollow.positions()));
		assertClean(hollow);
		assertSymmetric(hollow.positions(), 3, -2, 5, true);
	}

	@Test
	void smallSphereCounts() {
		assertEquals(1, run(s -> Shapes.sphere(s, 0, 0, 0, 0, ID, false)).writeCount());
		assertEquals(1, run(s -> Shapes.sphere(s, 0, 0, 0, 0, ID, true)).writeCount());
		assertEquals(Set.of(new Pos(7, 8, 9)), run(s -> Shapes.sphere(s, 7, 8, 9, -4, ID, false)).positions());
		assertEquals(19, run(s -> Shapes.sphere(s, 0, 0, 0, 1, ID, false)).writeCount());
		assertEquals(18, run(s -> Shapes.sphere(s, 0, 0, 0, 1, ID, true)).writeCount());
		assertEquals(81, run(s -> Shapes.sphere(s, 0, 0, 0, 2, ID, false)).writeCount());
	}

	@ParameterizedTest
	@ValueSource(doubles = {0, 1, 2, 3.5, 5, 8})
	void domeIsTheUpperHalfOfTheSphere(double r) {
		for (boolean hollow : new boolean[] {false, true}) {
			Set<Pos> expected = new HashSet<>();
			for (Pos p : run(s -> Shapes.sphere(s, 1, 4, -2, r, ID, hollow)).positions()) {
				if (p.y() >= 4) {
					expected.add(p);
				}
			}
			RecordingSink dome = run(s -> Shapes.dome(s, 1, 4, -2, r, ID, hollow));
			assertEquals(expected, dome.positions());
			assertClean(dome);
		}
	}

	@Test
	void hollowDomeIsOpenAtTheBottom() {
		Set<Pos> dome = run(s -> Shapes.dome(s, 0, 0, 0, 5, ID, true)).positions();
		assertFalse(dome.contains(new Pos(0, 0, 0)));
		assertTrue(dome.contains(new Pos(5, 0, 0)));
		assertTrue(dome.contains(new Pos(0, 5, 0)));
	}

	private static Set<Pos> ellipsoidSet(int cx, int cy, int cz, double rx, double ry, double rz) {
		double ax = Math.max(rx, 0) + 0.5, ay = Math.max(ry, 0) + 0.5, az = Math.max(rz, 0) + 0.5;
		double ax2 = ax * ax, ay2 = ay * ay, az2 = az * az;
		int reach = (int) Math.ceil(Math.max(ax, Math.max(ay, az))) + 1;
		return where(cx - reach, cy - reach, cz - reach, cx + reach, cy + reach, cz + reach, (x, y, z) -> {
			int dx = x - cx, dy = y - cy, dz = z - cz;
			return (double) (dx * dx) / ax2 + (double) (dy * dy) / ay2 + (double) (dz * dz) / az2 <= 1.0;
		});
	}

	@ParameterizedTest
	@CsvSource({"3,2,5", "6,1,1", "0,4,0", "0,0,0", "2.5,3.7,1.2", "7,7,7", "1,8,3", "-1,2,2"})
	void ellipsoidMatchesBruteForce(double rx, double ry, double rz) {
		Set<Pos> solid = ellipsoidSet(-4, 10, 2, rx, ry, rz);
		RecordingSink sink = run(s -> Shapes.ellipsoid(s, -4, 10, 2, rx, ry, rz, ID, false));
		assertEquals(solid, sink.positions());
		assertClean(sink);
		RecordingSink hollow = run(s -> Shapes.ellipsoid(s, -4, 10, 2, rx, ry, rz, ID, true));
		assertEquals(shell(solid), hollow.positions());
		assertClean(hollow);
	}

	@Test
	void ellipsoidWithEqualRadiiIsASphere() {
		for (int r = 0; r <= 9; r++) {
			int radius = r;
			assertEquals(run(s -> Shapes.sphere(s, 0, 0, 0, radius, ID, false)).positions(),
					run(s -> Shapes.ellipsoid(s, 0, 0, 0, radius, radius, radius, ID, false)).positions(), "r=" + r);
		}
		// A zero radius makes a disc one block thick.
		Set<Pos> disc = run(s -> Shapes.ellipsoid(s, 0, 0, 0, 4, 0, 4, ID, false)).positions();
		assertTrue(disc.stream().allMatch(p -> p.y() == 0));
		assertEquals(run(s -> Shapes.cylinder(s, 0, 0, 0, 4, 1, ID, false)).positions(), disc);
	}

	// ---- cylinders, cones, pyramids ----

	private static Set<Pos> cylinderSet(int cx, int y0, int cz, double r, int h) {
		double rr = Math.max(r, 0);
		double r2 = (rr + 0.5) * (rr + 0.5);
		int reach = (int) Math.ceil(rr) + 1;
		return where(cx - reach, y0, cz - reach, cx + reach, y0 + h - 1, cz + reach, (x, y, z) -> {
			int dx = x - cx, dz = z - cz;
			return dx * dx + dz * dz <= r2;
		});
	}

	@ParameterizedTest
	@CsvSource({"0,5", "1,1", "2,3", "3.5,4", "6,2", "2.2,7", "-2,3"})
	void cylinderMatchesBruteForce(double r, int h) {
		Set<Pos> solid = cylinderSet(2, 64, -9, r, h);
		RecordingSink sink = run(s -> Shapes.cylinder(s, 2, 64, -9, r, h, ID, false));
		assertEquals(solid, sink.positions());
		assertClean(sink);
		assertSymmetric(sink.positions(), 2, 64, -9, false);
		RecordingSink hollow = run(s -> Shapes.cylinder(s, 2, 64, -9, r, h, ID, true));
		assertEquals(ring(solid), hollow.positions());
		assertClean(hollow);
	}

	@Test
	void cylinderEdgeCases() {
		assertEquals(Set.of(new Pos(0, 0, 0), new Pos(0, 1, 0), new Pos(0, 2, 0)),
				run(s -> Shapes.cylinder(s, 0, 0, 0, 0, 3, ID, false)).positions());
		assertEquals(9, run(s -> Shapes.cylinder(s, 0, 0, 0, 1, 1, ID, false)).writeCount());
		assertEquals(8, run(s -> Shapes.cylinder(s, 0, 0, 0, 1, 1, ID, true)).writeCount());
		assertEquals(0, run(s -> Shapes.cylinder(s, 0, 0, 0, 3, 0, ID, false)).writeCount());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Shapes.cylinder(new RecordingSink(), 0, 0, 0, 3, -1, ID, false));
		assertTrue(e.getMessage().contains("height"), e.getMessage());
	}

	private static Set<Pos> coneSet(int cx, int y0, int cz, double r, int h) {
		double rr = Math.max(r, 0);
		Set<Pos> out = new HashSet<>();
		int reach = (int) Math.ceil(rr) + 1;
		for (int k = 0; k < h; k++) {
			double rk = h == 1 ? rr : rr * (h - 1 - k) / (h - 1);
			double r2 = (rk + 0.5) * (rk + 0.5);
			int layer = y0 + k;
			out.addAll(where(cx - reach, layer, cz - reach, cx + reach, layer, cz + reach, (x, y, z) -> {
				int dx = x - cx, dz = z - cz;
				return dx * dx + dz * dz <= r2;
			}));
		}
		return out;
	}

	@ParameterizedTest
	@CsvSource({"3,4", "5,10", "0,3", "4,1", "2.5,6", "8,3", "6,0"})
	void coneMatchesBruteForce(double r, int h) {
		Set<Pos> solid = coneSet(0, 5, 0, r, h);
		RecordingSink sink = run(s -> Shapes.cone(s, 0, 5, 0, r, h, ID, false));
		assertEquals(solid, sink.positions());
		assertClean(sink);
		assertSymmetric(sink.positions(), 0, 5, 0, false);
		RecordingSink hollow = run(s -> Shapes.cone(s, 0, 5, 0, r, h, ID, true));
		assertEquals(shell(solid), hollow.positions());
		assertClean(hollow);
		if (h > 1) {
			long top = sink.positions().stream().filter(p -> p.y() == 5 + h - 1).count();
			assertEquals(1, top);
		}
	}

	@Test
	void pyramidLayersAndHollowSurface() {
		assertEquals(35, run(s -> Shapes.pyramid(s, 0, 0, 0, 2, ID, false)).writeCount());
		assertEquals(34, run(s -> Shapes.pyramid(s, 0, 0, 0, 2, ID, true)).writeCount());
		assertEquals(84, run(s -> Shapes.pyramid(s, 0, 0, 0, 3, ID, false)).writeCount());
		assertEquals(74, run(s -> Shapes.pyramid(s, 0, 0, 0, 3, ID, true)).writeCount());
		assertEquals(Set.of(new Pos(1, 2, 3)), run(s -> Shapes.pyramid(s, 1, 2, 3, 0, ID, true)).positions());
		for (int half = 0; half <= 8; half++) {
			int h = half;
			Set<Pos> solid = where(-h, 10, -h, h, 10 + h, h, (x, y, z) -> Math.max(Math.abs(x), Math.abs(z)) <= h - (y - 10));
			RecordingSink sink = run(s -> Shapes.pyramid(s, 0, 10, 0, h, ID, false));
			assertEquals(solid, sink.positions());
			assertClean(sink);
			RecordingSink hollow = run(s -> Shapes.pyramid(s, 0, 10, 0, h, ID, true));
			assertEquals(shell(solid), hollow.positions());
			assertClean(hollow);
		}
		assertThrows(IllegalArgumentException.class, () -> Shapes.pyramid(new RecordingSink(), 0, 0, 0, -1, ID, false));
	}

	// ---- torus ----

	@ParameterizedTest
	@CsvSource({"5,1", "8,2.5", "3,3", "10,0", "0,2", "12.5,1.5"})
	void torusMatchesBruteForce(double major, double minor) {
		double r2 = (minor + 0.5) * (minor + 0.5);
		int reach = (int) Math.ceil(major + minor) + 2;
		Set<Pos> expected = where(-reach, -reach, -reach, reach, reach, reach, (x, y, z) -> {
			double t = Math.sqrt((double) x * x + (double) z * z) - major;
			return t * t + (double) y * y <= r2;
		});
		RecordingSink sink = run(s -> Shapes.torus(s, 0, 0, 0, major, minor, ID));
		assertEquals(expected, sink.positions());
		assertClean(sink);
		assertSymmetric(sink.positions(), 0, 0, 0, false);
		if (major > minor + 1) {
			assertFalse(sink.positions().contains(new Pos(0, 0, 0)), "torus has a hole");
			assertTrue(sink.positions().contains(new Pos((int) Math.round(major), 0, 0)));
		}
	}

	// ---- polygon ----

	private static boolean evenOdd(int[][] pts, int px, int pz) {
		boolean inside = false;
		for (int i = 0, j = pts.length - 1; i < pts.length; j = i++) {
			int xi = pts[i][0], zi = pts[i][1], xj = pts[j][0], zj = pts[j][1];
			if ((zi > pz) != (zj > pz) && px < xi + (double) (pz - zi) * (xj - xi) / (zj - zi)) {
				inside = !inside;
			}
		}
		return inside;
	}

	private static Set<Pos> outline(int[][] pts, int y) {
		RecordingSink sink = new RecordingSink();
		for (int i = 0; i < pts.length; i++) {
			int[] a = pts[i], b = pts[(i + 1) % pts.length];
			Shapes.line(sink, a[0], y, a[1], b[0], y, b[1], ID, 0);
		}
		return sink.positions();
	}

	private static Set<Pos> polygonSet(int[][] pts, int y0, int h, boolean hollow) {
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (int[] p : pts) {
			minX = Math.min(minX, p[0]);
			maxX = Math.max(maxX, p[0]);
			minZ = Math.min(minZ, p[1]);
			maxZ = Math.max(maxZ, p[1]);
		}
		Set<Pos> out = new HashSet<>();
		for (int y = y0; y < y0 + h; y++) {
			Set<Pos> edge = outline(pts, y);
			out.addAll(edge);
			if (!hollow) {
				for (int z = minZ; z <= maxZ; z++) {
					for (int x = minX; x <= maxX; x++) {
						if (evenOdd(pts, x, z)) {
							out.add(new Pos(x, y, z));
						}
					}
				}
			}
		}
		return out;
	}

	private static List<int[]> list(int[][] pts) {
		return List.of(pts);
	}

	private static final int[][][] POLYGONS = {
			{{0, 0}, {4, 0}, {4, 4}, {0, 4}},
			{{0, 0}, {10, 0}, {0, 7}},
			{{0, 0}, {6, 0}, {6, 2}, {2, 2}, {2, 6}, {0, 6}},
			{{0, 0}, {10, 3}, {0, 6}, {3, 3}},
			{{0, 0}, {0, 0}, {5, 0}, {5, 5}, {3, 5}, {0, 5}},
			{{-7, -3}, {4, -8}, {9, 6}, {-2, 9}},
			{{0, 12}, {7, -10}, {-11, 4}, {11, 4}, {-7, -10}},
			{{0, -6}, {6, 0}, {0, 6}, {-6, 0}},
			{{0, 0}, {3, 1}, {1, 3}, {9, 9}, {2, 8}, {-4, 5}, {0, 2}},
	};

	@Test
	void polygonMatchesBruteForce() {
		for (int[][] pts : POLYGONS) {
			for (boolean hollow : new boolean[] {false, true}) {
				RecordingSink sink = run(s -> Shapes.polygon(s, list(pts), 3, 4, ID, hollow));
				assertEquals(polygonSet(pts, 3, 4, hollow), sink.positions(), java.util.Arrays.deepToString(pts) + " hollow=" + hollow);
				assertClean(sink);
			}
		}
	}

	@Test
	void polygonBasics() {
		int[][] square = POLYGONS[0];
		assertEquals(25 * 3, run(s -> Shapes.polygon(s, list(square), 0, 3, ID, false)).writeCount());
		assertEquals(16 * 3, run(s -> Shapes.polygon(s, list(square), 0, 3, ID, true)).writeCount());
		assertEquals(0, run(s -> Shapes.polygon(s, list(square), 0, 0, ID, false)).writeCount());
		// Winding direction does not matter.
		List<int[]> reversed = new ArrayList<>(list(POLYGONS[2]));
		java.util.Collections.reverse(reversed);
		assertEquals(run(s -> Shapes.polygon(s, list(POLYGONS[2]), 0, 1, ID, false)).positions(),
				run(s -> Shapes.polygon(s, reversed, 0, 1, ID, false)).positions());
		// Even-odd: the centre of a pentagram is outside.
		Set<Pos> star = run(s -> Shapes.polygon(s, list(POLYGONS[6]), 0, 1, ID, false)).positions();
		assertFalse(star.contains(new Pos(0, 0, 0)));
		assertTrue(star.contains(new Pos(0, 0, 9)));
	}

	@Test
	void hollowPolygonOutlineIsWatertight() {
		for (int[][] pts : POLYGONS) {
			Set<Pos> walls = run(s -> Shapes.polygon(s, list(pts), 0, 1, ID, true)).positions();
			Set<Pos> solid = run(s -> Shapes.polygon(s, list(pts), 0, 1, ID, false)).positions();
			assertTrue(solid.containsAll(walls));
			// Flood the outside (4-connected, like walking or water) and check it never reaches the interior.
			int minX = solid.stream().mapToInt(Pos::x).min().orElseThrow() - 1, maxX = solid.stream().mapToInt(Pos::x).max().orElseThrow() + 1;
			int minZ = solid.stream().mapToInt(Pos::z).min().orElseThrow() - 1, maxZ = solid.stream().mapToInt(Pos::z).max().orElseThrow() + 1;
			Set<Pos> outside = new HashSet<>();
			java.util.ArrayDeque<Pos> queue = new java.util.ArrayDeque<>(List.of(new Pos(minX, 0, minZ)));
			while (!queue.isEmpty()) {
				Pos p = queue.poll();
				if (p.x() < minX || p.x() > maxX || p.z() < minZ || p.z() > maxZ || walls.contains(p) || !outside.add(p)) {
					continue;
				}
				queue.add(p.offset(1, 0, 0));
				queue.add(p.offset(-1, 0, 0));
				queue.add(p.offset(0, 0, 1));
				queue.add(p.offset(0, 0, -1));
			}
			for (Pos p : solid) {
				if (!walls.contains(p)) {
					assertFalse(outside.contains(p), "outline leaks into " + p + " of " + java.util.Arrays.deepToString(pts));
				}
			}
		}
	}

	// ---- validation ----

	@Test
	void rejectsBadArguments() {
		RecordingSink s = new RecordingSink();
		List<Executable> nullIds = List.of(
				() -> Shapes.fill(s, 0, 0, 0, 1, 1, 1, null),
				() -> Shapes.hollow(s, 0, 0, 0, 1, 1, 1, null),
				() -> Shapes.walls(s, 0, 0, 0, 1, 1, 1, null),
				() -> Shapes.frame(s, 0, 0, 0, 1, 1, 1, null),
				() -> Shapes.line(s, 0, 0, 0, 1, 1, 1, null, 0),
				() -> Shapes.sphere(s, 0, 0, 0, 2, null, false),
				() -> Shapes.dome(s, 0, 0, 0, 2, null, false),
				() -> Shapes.ellipsoid(s, 0, 0, 0, 1, 2, 3, null, false),
				() -> Shapes.cylinder(s, 0, 0, 0, 2, 3, null, false),
				() -> Shapes.cone(s, 0, 0, 0, 2, 3, null, false),
				() -> Shapes.pyramid(s, 0, 0, 0, 2, null, false),
				() -> Shapes.torus(s, 0, 0, 0, 4, 1, null),
				() -> Shapes.polygon(s, list(POLYGONS[0]), 0, 1, null, false),
				() -> Shapes.fill(s, 0, 0, 0, 1, 1, 1, " "));
		for (Executable call : nullIds) {
			assertThrows(IllegalArgumentException.class, call);
		}
		assertThrows(IllegalArgumentException.class, () -> Shapes.cone(s, 0, 0, 0, 2, -3, ID, false));
		assertThrows(IllegalArgumentException.class, () -> Shapes.polygon(s, list(POLYGONS[0]), 0, -1, ID, false));
		assertThrows(IllegalArgumentException.class, () -> Shapes.polygon(s, List.of(new int[] {0, 0}, new int[] {1, 1}), 0, 1, ID, false));
		assertThrows(IllegalArgumentException.class, () -> Shapes.polygon(s, null, 0, 1, ID, false));
		assertThrows(IllegalArgumentException.class, () -> Shapes.polygon(s, List.of(new int[] {0, 0}, new int[] {1}, new int[] {2, 2}), 0, 1, ID, false));
		assertThrows(IllegalArgumentException.class, () -> Shapes.sphere(s, 0, 0, 0, Double.NaN, ID, false));
		assertThrows(IllegalArgumentException.class, () -> Shapes.line(s, 0, 0, 0, 1, 1, 1, ID, Double.POSITIVE_INFINITY));
		assertThrows(IllegalArgumentException.class, () -> Shapes.torus(s, 0, 0, 0, 1e9, 1, ID));
		assertThrows(IllegalArgumentException.class, () -> Shapes.polygon(s,
				List.of(new int[] {-2_000_000_000, 0}, new int[] {2_000_000_000, 0}, new int[] {0, 5}), 0, 1, ID, false));
		assertEquals(0, s.writeCount());
	}

	@Test
	void hugeShapesFailFastOnBlueprintLimits() {
		long start = System.nanoTime();
		assertThrows(BlueprintLimitException.class, () -> Shapes.fill(new Blueprint(300_000, 384), -30_000, 0, 0, 30_000, 10, 10, ID));
		assertThrows(BlueprintLimitException.class, () -> Shapes.sphere(new Blueprint(300_000, 384), 0, 0, 0, 5000, ID, false));
		assertThrows(BlueprintLimitException.class, () -> Shapes.sphere(new Blueprint(300_000, 384), 0, 0, 0, 190, ID, true));
		assertThrows(BlueprintLimitException.class, () -> Shapes.fill(new Blueprint(300_000, 384), 0, 0, 0, Integer.MAX_VALUE, 0, 0, ID));
		assertThrows(BlueprintLimitException.class, () -> Shapes.line(new Blueprint(300_000, 384), 0, 0, 0, 0, 0, 1000, ID, 40));
		assertThrows(BlueprintLimitException.class, () -> Shapes.polygon(new Blueprint(300_000, 384),
				List.of(new int[] {0, 0}, new int[] {1000, 0}, new int[] {0, 1000}), 0, 1, ID, false));
		assertTrue((System.nanoTime() - start) / 1_000_000 < 2000);
	}
}
