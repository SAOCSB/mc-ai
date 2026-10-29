package io.github.saocsb.mcai.core.blueprint;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LongIntMapTest {
	@Test
	void putGetRemove() {
		LongIntMap map = new LongIntMap();
		assertEquals(LongIntMap.MISSING, map.get(42));
		assertEquals(LongIntMap.MISSING, map.put(42, 7));
		assertEquals(7, map.get(42));
		assertEquals(7, map.put(42, 9));
		assertEquals(1, map.size());
		assertEquals(9, map.remove(42));
		assertEquals(LongIntMap.MISSING, map.remove(42));
		assertEquals(0, map.size());
		assertEquals(LongIntMap.MISSING, map.get(42));
	}

	@Test
	void rejectsReservedKey() {
		assertThrows(IllegalArgumentException.class, () -> new LongIntMap().put(Long.MIN_VALUE, 1));
	}

	@Test
	void matchesHashMapUnderRandomOperations() {
		Random random = new Random(1234);
		LongIntMap map = new LongIntMap();
		Map<Long, Integer> reference = new HashMap<>();
		for (int step = 0; step < 400_000; step++) {
			// A small key range forces long probe chains, collisions and wrap-around.
			long key = random.nextInt(5_000) * 65_536L - 7;
			switch (random.nextInt(3)) {
				case 0 -> {
					int value = random.nextInt(1_000_000);
					Integer previous = reference.put(key, value);
					assertEquals(previous == null ? LongIntMap.MISSING : previous, map.put(key, value));
				}
				case 1 -> {
					Integer previous = reference.remove(key);
					assertEquals(previous == null ? LongIntMap.MISSING : previous, map.remove(key));
				}
				default -> {
					Integer value = reference.get(key);
					assertEquals(value == null ? LongIntMap.MISSING : value, map.get(key));
				}
			}
			assertEquals(reference.size(), map.size());
		}
		for (Map.Entry<Long, Integer> entry : reference.entrySet()) {
			assertEquals(entry.getValue(), map.get(entry.getKey()));
		}
		map.clear();
		assertEquals(0, map.size());
		assertEquals(LongIntMap.MISSING, map.get(reference.keySet().iterator().next()));
	}
}
