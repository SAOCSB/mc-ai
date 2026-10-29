package io.github.saocsb.mcai.core.blueprint;

import java.util.Arrays;

/**
 * Open-addressing hash map from {@code long} keys to non-negative {@code int} values (linear probing,
 * backward-shift deletion, so there are no tombstones). {@link Long#MIN_VALUE} is reserved as the
 * empty marker and cannot be used as a key. Not thread-safe.
 */
final class LongIntMap {
	static final int MISSING = -1;
	private static final long EMPTY = Long.MIN_VALUE;
	private static final int MIN_CAPACITY = 16;

	private long[] keys;
	private int[] values;
	private int mask;
	private int size;
	private int resizeAt;

	LongIntMap() {
		this(MIN_CAPACITY);
	}

	LongIntMap(int expectedSize) {
		int capacity = MIN_CAPACITY;
		while (capacity < 2L * expectedSize && capacity < (1 << 30)) {
			capacity <<= 1;
		}
		allocate(capacity);
	}

	int size() {
		return size;
	}

	/** The value for {@code key}, or {@link #MISSING}. */
	int get(long key) {
		long[] k = keys;
		for (int i = slot(key); ; i = (i + 1) & mask) {
			long found = k[i];
			if (found == key) {
				return values[i];
			}
			if (found == EMPTY) {
				return MISSING;
			}
		}
	}

	/** Associates {@code value} (>= 0) with {@code key}; returns the previous value or {@link #MISSING}. */
	int put(long key, int value) {
		if (key == EMPTY) {
			throw new IllegalArgumentException("reserved key");
		}
		for (int i = slot(key); ; i = (i + 1) & mask) {
			long found = keys[i];
			if (found == key) {
				int previous = values[i];
				values[i] = value;
				return previous;
			}
			if (found == EMPTY) {
				keys[i] = key;
				values[i] = value;
				if (++size > resizeAt) {
					grow();
				}
				return MISSING;
			}
		}
	}

	/** Removes {@code key}; returns its value or {@link #MISSING}. */
	int remove(long key) {
		int i = slot(key);
		while (true) {
			long found = keys[i];
			if (found == EMPTY) {
				return MISSING;
			}
			if (found == key) {
				break;
			}
			i = (i + 1) & mask;
		}
		int removed = values[i];
		int gap = i;
		for (int j = (i + 1) & mask; keys[j] != EMPTY; j = (j + 1) & mask) {
			int home = slot(keys[j]);
			// Move the entry back into the gap unless its home lies cyclically in (gap, j].
			if (((j - home) & mask) >= ((j - gap) & mask)) {
				keys[gap] = keys[j];
				values[gap] = values[j];
				gap = j;
			}
		}
		keys[gap] = EMPTY;
		size--;
		return removed;
	}

	void clear() {
		Arrays.fill(keys, EMPTY);
		size = 0;
	}

	private int slot(long key) {
		long h = key * 0x9E3779B97F4A7C15L;
		h ^= h >>> 32;
		h ^= h >>> 16;
		return (int) h & mask;
	}

	private void allocate(int capacity) {
		keys = new long[capacity];
		Arrays.fill(keys, EMPTY);
		values = new int[capacity];
		mask = capacity - 1;
		resizeAt = capacity / 2;
	}

	private void grow() {
		long[] oldKeys = keys;
		int[] oldValues = values;
		allocate(oldKeys.length * 2);
		for (int i = 0; i < oldKeys.length; i++) {
			long key = oldKeys[i];
			if (key != EMPTY) {
				int j = slot(key);
				while (keys[j] != EMPTY) {
					j = (j + 1) & mask;
				}
				keys[j] = key;
				values[j] = oldValues[i];
			}
		}
	}
}
