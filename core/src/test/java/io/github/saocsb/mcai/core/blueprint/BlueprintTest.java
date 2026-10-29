package io.github.saocsb.mcai.core.blueprint;

import io.github.saocsb.mcai.core.blueprint.RecordingSink.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintTest {
	private static Blueprint blueprint() {
		return new Blueprint(300_000, 384);
	}

	private static List<String> entries(Blueprint bp) {
		List<String> out = new ArrayList<>();
		bp.forEach((x, y, z, id) -> out.add(x + "," + y + "," + z + "=" + id));
		return out;
	}

	@Test
	void startsEmpty() {
		Blueprint bp = blueprint();
		assertTrue(bp.isEmpty());
		assertEquals(0, bp.size());
		assertNull(bp.bounds());
		assertEquals(List.of(), bp.palette());
		assertNull(bp.get(0, 0, 0));
		assertEquals(300_000, bp.maxBlocks());
		assertEquals(384, bp.maxSpan());
	}

	@Test
	void rejectsNonPositiveLimits() {
		assertThrows(IllegalArgumentException.class, () -> new Blueprint(0, 10));
		assertThrows(IllegalArgumentException.class, () -> new Blueprint(10, 0));
	}

	@Test
	void normalizesIdsAndOverwrites() {
		Blueprint bp = blueprint();
		bp.set(1, 2, 3, " Stone ");
		assertEquals("minecraft:stone", bp.get(1, 2, 3));
		bp.set(1, 2, 3, "oak_stairs[ facing = north ]");
		assertEquals("minecraft:oak_stairs[facing=north]", bp.get(1, 2, 3));
		assertEquals(1, bp.size());
		assertNull(bp.get(1, 2, 4));
	}

	@Test
	void paletteListsLiveIdsInFirstUseOrder() {
		Blueprint bp = blueprint();
		bp.set(0, 0, 0, "stone");
		bp.set(1, 0, 0, "dirt");
		bp.set(2, 0, 0, "minecraft:stone");
		bp.set(3, 0, 0, "glass");
		bp.set(4, 0, 0, "STONE");
		assertEquals(List.of("minecraft:stone", "minecraft:dirt", "minecraft:glass"), bp.palette());
		// The only dirt block is overwritten: dirt leaves the palette.
		bp.set(1, 0, 0, "glass");
		assertEquals(List.of("minecraft:stone", "minecraft:glass"), bp.palette());
		// Coming back keeps its first-use slot.
		bp.set(9, 0, 0, "dirt");
		assertEquals(List.of("minecraft:stone", "minecraft:dirt", "minecraft:glass"), bp.palette());
		assertThrows(UnsupportedOperationException.class, () -> bp.palette().add("x"));
	}

	@Test
	void airCountsAsAnEntry() {
		Blueprint bp = blueprint();
		bp.set(0, 0, 0, "stone");
		bp.set(5, -2, 7, "air");
		assertEquals(2, bp.size());
		assertEquals("minecraft:air", bp.get(5, -2, 7));
		assertEquals(new Bounds(0, -2, 0, 5, 0, 7), bp.bounds());
		assertEquals(List.of("minecraft:stone", "minecraft:air"), bp.palette());
	}

	@Test
	void boundsTrackEveryAxis() {
		Blueprint bp = blueprint();
		bp.set(3, 4, 5, "stone");
		assertEquals(new Bounds(3, 4, 5, 3, 4, 5), bp.bounds());
		bp.set(-10, 20, 0, "stone");
		bp.set(7, -1, 50, "stone");
		assertEquals(new Bounds(-10, -1, 0, 7, 20, 50), bp.bounds());
		Bounds b = bp.bounds();
		assertEquals(18, b.sizeX());
		assertEquals(22, b.sizeY());
		assertEquals(51, b.sizeZ());
	}

	@Test
	void forEachVisitsInFirstPlacementOrder() {
		Blueprint bp = blueprint();
		bp.set(5, 0, 0, "stone");
		bp.set(-3, 1, 2, "dirt");
		bp.set(0, 0, 9, "glass");
		bp.set(5, 0, 0, "gold_block");
		assertEquals(List.of("5,0,0=minecraft:gold_block", "-3,1,2=minecraft:dirt", "0,0,9=minecraft:glass"), entries(bp));
	}

	@Test
	void forEachRoundTripsExtremeCoordinates() {
		Blueprint bp = new Blueprint(10, 100_000);
		int c = Blueprint.COORD_LIMIT;
		bp.set(-c, c, -c, "stone");
		bp.set(c, -c, c, "dirt");
		bp.set(0, 0, 0, "glass");
		assertEquals(List.of(-c + "," + c + "," + -c + "=minecraft:stone", c + "," + -c + "," + c + "=minecraft:dirt",
				"0,0,0=minecraft:glass"), entries(bp));
		assertEquals(new Bounds(-c, -c, -c, c, c, c), bp.bounds());
	}

	@Test
	void visitorMayOverwrite() {
		Blueprint bp = blueprint();
		Shapes.fill(bp, 0, 0, 0, 3, 0, 0, "stone");
		bp.forEach((x, y, z, id) -> bp.set(x, y, z, "dirt"));
		assertEquals(List.of("minecraft:dirt"), bp.palette());
		assertEquals(4, bp.size());
	}

	@Test
	void rejectsInvalidIds() {
		Blueprint bp = blueprint();
		assertThrows(IllegalArgumentException.class, () -> bp.set(0, 0, 0, null));
		assertThrows(IllegalArgumentException.class, () -> bp.set(0, 0, 0, ""));
		assertThrows(IllegalArgumentException.class, () -> bp.set(0, 0, 0, "  "));
		assertThrows(IllegalArgumentException.class, () -> bp.set(0, 0, 0, "stone["));
		assertThrows(IllegalArgumentException.class, () -> bp.set(0, 0, 0, "#logs"));
		assertTrue(bp.isEmpty());
		assertEquals(List.of(), bp.palette());
	}

	@Test
	void blockLimitNamesTheLimit() {
		Blueprint bp = new Blueprint(10, 384);
		Shapes.fill(bp, 0, 0, 0, 9, 0, 0, "stone");
		// Overwrites are fine at the limit.
		bp.set(0, 0, 0, "dirt");
		BlueprintLimitException e = assertThrows(BlueprintLimitException.class, () -> bp.set(10, 0, 0, "stone"));
		assertEquals("LimitError: build exceeds the 10-block limit", e.getMessage());
		assertEquals(10, bp.size());
		assertNull(bp.get(10, 0, 0));
		assertEquals(new Bounds(0, 0, 0, 9, 0, 0), bp.bounds());
	}

	@Test
	void spanLimitNamesAxisSpanAndLimit() {
		Blueprint bp = new Blueprint(1000, 5);
		bp.set(0, 0, 0, "stone");
		bp.set(4, 4, 4, "stone");
		BlueprintLimitException x = assertThrows(BlueprintLimitException.class, () -> bp.set(5, 0, 0, "stone"));
		assertEquals("LimitError: build spans 6 blocks along x (limit 5)", x.getMessage());
		BlueprintLimitException y = assertThrows(BlueprintLimitException.class, () -> bp.set(0, -3, 0, "stone"));
		assertEquals("LimitError: build spans 8 blocks along y (limit 5)", y.getMessage());
		BlueprintLimitException z = assertThrows(BlueprintLimitException.class, () -> bp.set(0, 0, 404, "stone"));
		assertEquals("LimitError: build spans 405 blocks along z (limit 5)", z.getMessage());
		assertEquals(2, bp.size());
		assertEquals(new Bounds(0, 0, 0, 4, 4, 4), bp.bounds());
	}

	@Test
	void spanLimitExampleFromTheDesign() {
		Blueprint bp = blueprint();
		bp.set(0, 0, 0, "stone");
		BlueprintLimitException e = assertThrows(BlueprintLimitException.class, () -> Shapes.fill(bp, 0, 0, 0, 411, 0, 0, "stone"));
		assertEquals("LimitError: build spans 385 blocks along x (limit 384)", e.getMessage());
	}

	@Test
	void coordinateLimit() {
		Blueprint bp = new Blueprint(10, 100_000);
		BlueprintLimitException e = assertThrows(BlueprintLimitException.class, () -> bp.set(40_000, 0, 0, "stone"));
		assertEquals("LimitError: x coordinate 40000 is outside +/-30000", e.getMessage());
		assertTrue(assertThrows(BlueprintLimitException.class, () -> bp.set(0, -30_001, 0, "stone")).getMessage()
				.contains("coordinate -30001 is outside +/-30000"));
		assertTrue(assertThrows(BlueprintLimitException.class, () -> bp.set(0, 0, Integer.MAX_VALUE, "stone")).getMessage()
				.contains("z coordinate"));
		bp.set(30_000, -30_000, 30_000, "stone");
		assertEquals("minecraft:stone", bp.get(30_000, -30_000, 30_000));
		assertNull(bp.get(40_000, 0, 0));
		assertFalse(bp.remove(40_000, 0, 0));
		assertEquals(1, bp.size());
	}

	@Test
	void removeKeepsEverythingConsistent() {
		Blueprint bp = blueprint();
		bp.set(0, 0, 0, "stone");
		bp.set(10, 5, -3, "dirt");
		bp.set(2, 1, 1, "glass");
		assertTrue(bp.remove(10, 5, -3));
		assertFalse(bp.remove(10, 5, -3));
		assertFalse(bp.remove(99, 0, 0));
		assertEquals(2, bp.size());
		assertNull(bp.get(10, 5, -3));
		assertEquals(new Bounds(0, 0, 0, 2, 1, 1), bp.bounds());
		assertEquals(List.of("minecraft:stone", "minecraft:glass"), bp.palette());
		assertEquals(List.of("0,0,0=minecraft:stone", "2,1,1=minecraft:glass"), entries(bp));
		// A removed position placed again counts as a new first placement.
		bp.set(10, 5, -3, "dirt");
		assertEquals(List.of("0,0,0=minecraft:stone", "2,1,1=minecraft:glass", "10,5,-3=minecraft:dirt"), entries(bp));
		assertEquals(new Bounds(0, 0, -3, 10, 5, 1), bp.bounds());
	}

	@Test
	void removingEverythingEmptiesTheBlueprint() {
		Blueprint bp = blueprint();
		bp.set(1, 1, 1, "stone");
		bp.set(2, 2, 2, "stone");
		assertTrue(bp.remove(1, 1, 1));
		assertTrue(bp.remove(2, 2, 2));
		assertTrue(bp.isEmpty());
		assertNull(bp.bounds());
		assertEquals(List.of(), bp.palette());
		assertEquals(List.of(), entries(bp));
		bp.set(-5, 0, 0, "dirt");
		assertEquals(new Bounds(-5, 0, 0, -5, 0, 0), bp.bounds());
		assertEquals(List.of("-5,0,0=minecraft:dirt"), entries(bp));
	}

	@Test
	void spanCheckUsesBoundsShrunkByRemovals() {
		Blueprint bp = new Blueprint(100, 5);
		bp.set(0, 0, 0, "stone");
		bp.set(4, 0, 0, "stone");
		bp.remove(0, 0, 0);
		// Real bounds are now x = 4..4, so x = 8 spans exactly 5.
		bp.set(8, 0, 0, "stone");
		assertEquals(new Bounds(4, 0, 0, 8, 0, 0), bp.bounds());
		assertThrows(BlueprintLimitException.class, () -> bp.set(3, 0, 0, "stone"));
	}

	@Test
	void removeAndReAddAtTheBlockLimit() {
		Blueprint bp = new Blueprint(3, 384);
		bp.set(0, 0, 0, "stone");
		bp.set(1, 0, 0, "stone");
		bp.set(2, 0, 0, "stone");
		bp.remove(1, 0, 0);
		bp.set(7, 0, 0, "stone");
		assertEquals(3, bp.size());
		assertThrows(BlueprintLimitException.class, () -> bp.set(8, 0, 0, "stone"));
	}

	@Test
	void manyRemovalsKeepOrderAndLookups() {
		Blueprint bp = blueprint();
		Random random = new Random(7);
		Map<String, String> expected = new LinkedHashMap<>();
		List<Pos> placed = new ArrayList<>();
		for (int i = 0; i < 5000; i++) {
			Pos p = new Pos(random.nextInt(60) - 30, random.nextInt(60) - 30, random.nextInt(60) - 30);
			String id = "minecraft:block_" + random.nextInt(20);
			bp.set(p.x(), p.y(), p.z(), id);
			String key = p.x() + "," + p.y() + "," + p.z();
			if (!expected.containsKey(key)) {
				placed.add(p);
			}
			expected.put(key, id);
		}
		Collections.shuffle(placed, random);
		for (int i = 0; i < placed.size() * 3 / 4; i++) {
			Pos p = placed.get(i);
			assertTrue(bp.remove(p.x(), p.y(), p.z()));
			expected.remove(p.x() + "," + p.y() + "," + p.z());
		}
		List<String> want = new ArrayList<>();
		expected.forEach((k, v) -> want.add(k + "=" + v));
		assertEquals(want, entries(bp));
		assertEquals(expected.size(), bp.size());
		for (Pos p : placed) {
			String id = expected.get(p.x() + "," + p.y() + "," + p.z());
			assertEquals(id, bp.get(p.x(), p.y(), p.z()));
		}
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
		for (String key : expected.keySet()) {
			int x = Integer.parseInt(key.substring(0, key.indexOf(',')));
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
		}
		assertEquals(minX, bp.bounds().minX());
		assertEquals(maxX, bp.bounds().maxX());
	}

	@Test
	void removeDuringForEachSkipsUnvisitedEntries() {
		Blueprint bp = blueprint();
		Shapes.fill(bp, 0, 0, 0, 99, 0, 0, "stone");
		List<Integer> visited = new ArrayList<>();
		bp.forEach((x, y, z, id) -> {
			visited.add(x);
			if (x % 2 == 0) {
				bp.remove(x + 1, 0, 0);
			}
		});
		assertEquals(50, visited.size());
		assertEquals(50, bp.size());
		assertEquals(50, entries(bp).size());
	}

	@Test
	void replaceAllChangesMatchingEntries() {
		Blueprint bp = blueprint();
		Shapes.fill(bp, 0, 0, 0, 4, 0, 0, "stone");
		bp.set(9, 0, 0, "dirt");
		assertEquals(5, bp.replaceAll("STONE", "minecraft:oak_planks"));
		assertEquals("minecraft:oak_planks", bp.get(0, 0, 0));
		assertEquals(List.of("minecraft:dirt", "minecraft:oak_planks"), bp.palette());
		assertEquals(0, bp.replaceAll("stone", "glass"));
		assertEquals(0, bp.replaceAll("dirt", " Dirt "));
		assertEquals(0, bp.replaceAll("gold_block", "glass"));
		assertEquals(1, bp.replaceAll("dirt", "oak_planks"));
		assertEquals(List.of("minecraft:oak_planks"), bp.palette());
		assertEquals(6, bp.size());
		assertEquals(new Bounds(0, 0, 0, 9, 0, 0), bp.bounds());
		// Entries keep their order and later sets of the replaced id still work.
		assertEquals("0,0,0=minecraft:oak_planks", entries(bp).get(0));
		bp.set(0, 0, 0, "stone");
		assertEquals(List.of("minecraft:stone", "minecraft:oak_planks"), bp.palette());
		assertThrows(IllegalArgumentException.class, () -> bp.replaceAll("stone[", "dirt"));
	}

	@Test
	void fills300kBlocksQuickly() {
		// Warm up so the timing reflects steady-state speed, not class loading.
		Shapes.fill(new Blueprint(300_000, 384), 0, 0, 0, 99, 29, 99, "stone");
		long start = System.nanoTime();
		Blueprint bp = new Blueprint(300_000, 384);
		Shapes.fill(bp, 0, 0, 0, 99, 29, 99, "stone");
		long fillMillis = (System.nanoTime() - start) / 1_000_000;
		assertEquals(300_000, bp.size());
		assertTrue(fillMillis < 2000, "300k fill took " + fillMillis + " ms");
		long[] count = {0};
		bp.forEach((x, y, z, id) -> count[0]++);
		assertEquals(300_000, count[0]);
		assertEquals(new Bounds(0, 0, 0, 99, 29, 99), bp.bounds());
		assertThrows(BlueprintLimitException.class, () -> bp.set(100, 0, 0, "stone"));
		long overwriteStart = System.nanoTime();
		Shapes.fill(bp, 0, 0, 0, 99, 29, 99, "dirt");
		assertEquals(300_000, bp.replaceAll("dirt", "glass"));
		assertEquals(List.of("minecraft:glass"), bp.palette());
		long totalMillis = (System.nanoTime() - overwriteStart) / 1_000_000;
		assertTrue(totalMillis < 2000, "300k overwrite + replace took " + totalMillis + " ms");
	}
}
