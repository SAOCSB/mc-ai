package io.github.saocsb.mcai.core.placement;

import io.github.saocsb.mcai.core.blueprint.Bounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementPlannerTest {
	/** Asymmetric on purpose: odd/even sizes and an off-centre local origin. */
	private static final Bounds BOUNDS = new Bounds(-3, 0, -6, 4, 5, 2);
	private static final int PX = 100, PY = 70, PZ = -200, GROUND = 69;
	private static final PlacementPlanner.SurfaceHeight NO_SURFACE = (x, z) -> {
		throw new AssertionError("surface lookup not expected");
	};

	private static PlayerPose pose(Facing facing) {
		return new PlayerPose(PX, PY, PZ, facing, GROUND);
	}

	private static PlacementSpec spec(PlacementMode mode, int distance, GroundMode ground) {
		return new PlacementSpec(mode, distance, ground, false, null, null, null);
	}

	/** Clockwise neighbour of a facing: the player's right-hand side. */
	private static Facing right(Facing f) {
		return switch (f) {
			case NORTH -> Facing.EAST;
			case EAST -> Facing.SOUTH;
			case SOUTH -> Facing.WEST;
			case WEST -> Facing.NORTH;
		};
	}

	/** World positions of every block in the bounds. */
	private static List<int[]> worldBlocks(Transform t, Bounds b) {
		List<int[]> out = new ArrayList<>();
		for (int x = b.minX(); x <= b.maxX(); x++) {
			for (int y = b.minY(); y <= b.maxY(); y++) {
				for (int z = b.minZ(); z <= b.maxZ(); z++) {
					out.add(new int[] {t.worldX(x, z), t.worldY(y), t.worldZ(x, z)});
				}
			}
		}
		return out;
	}

	@Test
	void rotationForEachFacing() {
		assertEquals(Rotation.NONE, PlacementPlanner.rotationFor(Facing.NORTH));
		assertEquals(Rotation.CLOCKWISE_90, PlacementPlanner.rotationFor(Facing.EAST));
		assertEquals(Rotation.CLOCKWISE_180, PlacementPlanner.rotationFor(Facing.SOUTH));
		assertEquals(Rotation.COUNTERCLOCKWISE_90, PlacementPlanner.rotationFor(Facing.WEST));
	}

	@ParameterizedTest
	@EnumSource(Facing.class)
	void rotationTurnsLocalNorthIntoTheFacing(Facing facing) {
		Rotation r = PlacementPlanner.rotationFor(facing);
		// Local -z (where the player looks) becomes the facing; local +x becomes the player's right.
		assertEquals(facing.dx, r.rotateX(0, -1));
		assertEquals(facing.dz, r.rotateZ(0, -1));
		assertEquals(right(facing).dx, r.rotateX(1, 0));
		assertEquals(right(facing).dz, r.rotateZ(1, 0));
	}

	@ParameterizedTest
	@EnumSource(Facing.class)
	void frontPutsTheWholeBoxAheadWithTheRequestedGap(Facing facing) {
		for (int distance : new int[] {0, 1, 3, 10}) {
			Transform t = PlacementPlanner.plan(spec(PlacementMode.FRONT, distance, GroundMode.PLAYER), BOUNDS, pose(facing), null, NO_SURFACE);
			assertEquals(PlacementPlanner.rotationFor(facing), t.rotation());
			assertEquals(GROUND, t.originY());
			Facing side = right(facing);
			int nearest = Integer.MAX_VALUE, leftMost = Integer.MAX_VALUE, rightMost = Integer.MIN_VALUE;
			for (int[] w : worldBlocks(t, BOUNDS)) {
				int ahead = (w[0] - PX) * facing.dx + (w[2] - PZ) * facing.dz;
				int lateral = (w[0] - PX) * side.dx + (w[2] - PZ) * side.dz;
				nearest = Math.min(nearest, ahead);
				leftMost = Math.min(leftMost, lateral);
				rightMost = Math.max(rightMost, lateral);
				assertTrue(ahead > 0, "block behind or beside the player");
			}
			// distance empty blocks between the player's block and the nearest face.
			assertEquals(distance + 1, nearest, facing + " distance " + distance);
			// Centred on the view line (floor centre for even widths): 8 wide -> 3 left, 4 right.
			assertEquals(BOUNDS.sizeX(), rightMost - leftMost + 1);
			assertTrue(Math.abs(leftMost + rightMost) <= 1, "not centred: " + leftMost + ".." + rightMost);
			assertEquals(0, lateralOf(t, BOUNDS.centerX(), BOUNDS.maxZ(), side));
			assertEquals(-3, leftMost);
			assertEquals(4, rightMost);
			// The front of the structure (local +z) faces the player.
			assertEquals(-facing.dx, t.worldX(0, 1) - t.worldX(0, 0));
			assertEquals(-facing.dz, t.worldZ(0, 1) - t.worldZ(0, 0));
			// Local +x is the player's right.
			assertEquals(side.dx, t.worldX(1, 0) - t.worldX(0, 0));
			assertEquals(side.dz, t.worldZ(1, 0) - t.worldZ(0, 0));
		}
	}

	private static int lateralOf(Transform t, int x, int z, Facing side) {
		return (t.worldX(x, z) - PX) * side.dx + (t.worldZ(x, z) - PZ) * side.dz;
	}

	@ParameterizedTest
	@EnumSource(Facing.class)
	void frontAnchorMapsToThePlayer(Facing facing) {
		Transform t = PlacementPlanner.plan(PlacementSpec.defaults(), BOUNDS, pose(facing), null, NO_SURFACE);
		int ax = BOUNDS.centerX(), az = BOUNDS.maxZ() + 1 + PlacementSpec.DEFAULT_DISTANCE;
		assertEquals(PX, t.worldX(ax, az));
		assertEquals(PZ, t.worldZ(ax, az));
		assertEquals(GROUND, t.worldY(0));
	}

	@Test
	void frontWorkedExampleFacingNorth() {
		// 5x5 footprint centred on x = 0 with z = -4..0; player at (0, 64, 0) looking north, gap 3.
		Bounds b = new Bounds(-2, 0, -4, 2, 3, 0);
		Transform t = PlacementPlanner.plan(PlacementSpec.defaults(), b, new PlayerPose(0, 64, 0, Facing.NORTH, 64), null, NO_SURFACE);
		assertEquals(new Transform(0, 64, -4, Rotation.NONE), t);
		// Nearest face at world z = -4: blocks z = -1, -2, -3 are the 3-block gap.
		assertEquals(-4, t.worldZ(0, b.maxZ()));
		assertEquals(-8, t.worldZ(0, b.minZ()));
	}

	@Test
	void negativeDistanceCountsAsZero() {
		Transform zero = PlacementPlanner.plan(spec(PlacementMode.FRONT, 0, GroundMode.PLAYER), BOUNDS, pose(Facing.EAST), null, NO_SURFACE);
		Transform negative = PlacementPlanner.plan(spec(PlacementMode.FRONT, -5, GroundMode.PLAYER), BOUNDS, pose(Facing.EAST), null, NO_SURFACE);
		assertEquals(zero, negative);
	}

	@ParameterizedTest
	@EnumSource(Facing.class)
	void centerPutsTheBoxCentreOnThePlayer(Facing facing) {
		Transform t = PlacementPlanner.plan(spec(PlacementMode.CENTER, 3, GroundMode.PLAYER), BOUNDS, pose(facing), null, NO_SURFACE);
		assertEquals(PX, t.worldX(BOUNDS.centerX(), BOUNDS.centerZ()));
		assertEquals(PZ, t.worldZ(BOUNDS.centerX(), BOUNDS.centerZ()));
		assertEquals(GROUND, t.originY());
		assertEquals(PlacementPlanner.rotationFor(facing), t.rotation());
		Facing side = right(facing);
		int minAhead = Integer.MAX_VALUE, maxAhead = Integer.MIN_VALUE;
		for (int[] w : worldBlocks(t, BOUNDS)) {
			int ahead = (w[0] - PX) * facing.dx + (w[2] - PZ) * facing.dz;
			minAhead = Math.min(minAhead, ahead);
			maxAhead = Math.max(maxAhead, ahead);
		}
		assertTrue(Math.abs(minAhead + maxAhead) <= 1, "not centred along the view: " + minAhead + ".." + maxAhead);
		assertEquals(0, lateralOf(t, BOUNDS.centerX(), BOUNDS.centerZ(), side));
	}

	@ParameterizedTest
	@EnumSource(Facing.class)
	void originPutsLocalZeroAtTheFeet(Facing facing) {
		Transform t = PlacementPlanner.plan(spec(PlacementMode.ORIGIN, 3, GroundMode.PLAYER), BOUNDS, pose(facing), null, NO_SURFACE);
		assertEquals(new Transform(PX, GROUND, PZ, PlacementPlanner.rotationFor(facing)), t);
		// One block "north" in local space is one block ahead of the player.
		assertEquals(PX + facing.dx, t.worldX(0, -1));
		assertEquals(PZ + facing.dz, t.worldZ(0, -1));
	}

	@Test
	void previousReusesTheLastTransform() {
		Transform previous = new Transform(1, 2, 3, Rotation.CLOCKWISE_180);
		PlacementSpec spec = new PlacementSpec(PlacementMode.PREVIOUS, 3, GroundMode.SURFACE, true, null, null, null);
		assertSame(previous, PlacementPlanner.plan(spec, BOUNDS, pose(Facing.WEST), previous, NO_SURFACE));
	}

	@ParameterizedTest
	@EnumSource(Facing.class)
	void previousWithoutHistoryFallsBackToFront(Facing facing) {
		PlacementSpec previous = new PlacementSpec(PlacementMode.PREVIOUS, 5, GroundMode.PLAYER, true, null, null, null);
		assertEquals(PlacementPlanner.plan(spec(PlacementMode.FRONT, 5, GroundMode.PLAYER), BOUNDS, pose(facing), null, NO_SURFACE),
				PlacementPlanner.plan(previous, BOUNDS, pose(facing), null, NO_SURFACE));
	}

	@Test
	void absoluteUsesWorldCoordinatesWithoutRotation() {
		PlacementSpec full = new PlacementSpec(PlacementMode.ABSOLUTE, 3, GroundMode.SURFACE, false, 10, -20, 30);
		assertEquals(new Transform(10, -20, 30, Rotation.NONE), PlacementPlanner.plan(full, BOUNDS, pose(Facing.SOUTH), null, NO_SURFACE));
		PlacementSpec partial = new PlacementSpec(PlacementMode.ABSOLUTE, 3, GroundMode.PLAYER, false, null, 80, null);
		assertEquals(new Transform(PX, 80, PZ, Rotation.NONE), PlacementPlanner.plan(partial, BOUNDS, pose(Facing.EAST), null, NO_SURFACE));
		PlacementSpec none = new PlacementSpec(PlacementMode.ABSOLUTE, 3, GroundMode.PLAYER, false, null, null, null);
		assertEquals(new Transform(PX, PY, PZ, Rotation.NONE), PlacementPlanner.plan(none, BOUNDS, pose(Facing.WEST), null, NO_SURFACE));
	}

	@ParameterizedTest
	@EnumSource(Facing.class)
	void surfaceGroundSamplesTheColumnUnderTheCentre(Facing facing) {
		for (PlacementMode mode : new PlacementMode[] {PlacementMode.FRONT, PlacementMode.CENTER, PlacementMode.ORIGIN}) {
			List<int[]> calls = new ArrayList<>();
			PlacementPlanner.SurfaceHeight surface = (x, z) -> {
				calls.add(new int[] {x, z});
				return 50 + Math.floorMod(x * 7 + z * 3, 13);
			};
			Transform t = PlacementPlanner.plan(spec(mode, 3, GroundMode.SURFACE), BOUNDS, pose(facing), null, surface);
			Transform flat = PlacementPlanner.plan(spec(mode, 3, GroundMode.PLAYER), BOUNDS, pose(facing), null, NO_SURFACE);
			assertEquals(1, calls.size());
			int cx = calls.get(0)[0], cz = calls.get(0)[1];
			assertEquals(flat.worldX(BOUNDS.centerX(), BOUNDS.centerZ()), cx);
			assertEquals(flat.worldZ(BOUNDS.centerX(), BOUNDS.centerZ()), cz);
			assertEquals(50 + Math.floorMod(cx * 7 + cz * 3, 13), t.originY());
			assertEquals(flat.withOriginY(t.originY()), t);
		}
	}

	@Test
	void defaultsAndMissingValues() {
		Transform defaults = PlacementPlanner.plan(PlacementSpec.defaults(), BOUNDS, pose(Facing.SOUTH), null, NO_SURFACE);
		assertEquals(defaults, PlacementPlanner.plan(null, BOUNDS, pose(Facing.SOUTH), null, NO_SURFACE));
		assertEquals(defaults, PlacementPlanner.plan(new PlacementSpec(null, 3, null, false, null, null, null), BOUNDS, pose(Facing.SOUTH), null, NO_SURFACE));
		// SURFACE without a lookup falls back to the player's ground.
		assertEquals(defaults, PlacementPlanner.plan(spec(PlacementMode.FRONT, 3, GroundMode.SURFACE), BOUNDS, pose(Facing.SOUTH), null, null));
	}
}
