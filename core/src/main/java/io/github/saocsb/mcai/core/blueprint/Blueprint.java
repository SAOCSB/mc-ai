package io.github.saocsb.mcai.core.blueprint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * A sparse voxel structure produced by a build script, in local coordinates.
 *
 * <p>Block ids are normalized with {@link BlockIds#normalize(String)} and stored once in a palette;
 * positions map to palette indices. Placing a block at an occupied position overwrites it.
 * Iteration order is insertion order of the first placement at each position.
 *
 * <p>Limits: at most {@code maxBlocks} distinct positions, every axis span at most {@code maxSpan},
 * and every coordinate within +/-{@link #COORD_LIMIT}. Violations throw {@link BlueprintLimitException}
 * with a message suitable for showing to the LLM (it names the limit and the offending value).
 * Messages start with {@code "LimitError: "}. A failed {@link #set} leaves the blueprint unchanged.
 * Not thread-safe.
 */
public final class Blueprint implements BlockSink {
	/** Absolute bound for any local coordinate. */
	public static final int COORD_LIMIT = 30_000;

	private static final int OFFSET = 1 << 15;
	private static final int MASK = 0xFFFF;
	private static final int REMOVED = -1;
	private static final int RAW_CACHE_LIMIT = 4096;

	/** Visitor for {@link #forEach(Visitor)}. */
	@FunctionalInterface
	public interface Visitor {
		void accept(int x, int y, int z, String blockId);
	}

	private final int maxBlocks;
	private final int maxSpan;

	/** Packed position -> slot in {@link #keys}/{@link #blocks}. */
	private final LongIntMap index = new LongIntMap();
	/** Packed positions in insertion order (slots of removed entries hold stale keys). */
	private long[] keys = new long[64];
	/** Palette index per slot, or {@link #REMOVED}. */
	private int[] blocks = new int[64];
	private int slots;
	private int size;

	private final ArrayList<String> palette = new ArrayList<>();
	private final HashMap<String, Integer> paletteIndex = new HashMap<>();
	/** Live entries per palette index. */
	private int[] usage = new int[16];
	private final HashMap<String, Integer> rawIndex = new HashMap<>();
	private String lastRaw;
	private int lastIndex;

	private int minX, minY, minZ, maxX, maxY, maxZ;
	/** True when removals may have shrunk the real bounds (the stored ones are then a superset). */
	private boolean boundsStale;
	private int iterating;

	/**
	 * @param maxBlocks maximum number of distinct positions (>= 1)
	 * @param maxSpan   maximum size along each axis (>= 1)
	 */
	public Blueprint(int maxBlocks, int maxSpan) {
		if (maxBlocks < 1) {
			throw new IllegalArgumentException("maxBlocks must be >= 1, got " + maxBlocks);
		}
		if (maxSpan < 1) {
			throw new IllegalArgumentException("maxSpan must be >= 1, got " + maxSpan);
		}
		this.maxBlocks = maxBlocks;
		this.maxSpan = maxSpan;
	}

	/** {@inheritDoc} Throws {@link IllegalArgumentException} for blank ids and {@link BlueprintLimitException} on limits. */
	@Override
	public void set(int x, int y, int z, String blockId) {
		int block = paletteIndexOfRaw(blockId);
		checkCoordinate('x', x);
		checkCoordinate('y', y);
		checkCoordinate('z', z);
		long key = pack(x, y, z);
		int slot = index.get(key);
		if (slot != LongIntMap.MISSING) {
			int old = blocks[slot];
			if (old != block) {
				usage[old]--;
				usage[block]++;
				blocks[slot] = block;
			}
			return;
		}
		if (size >= maxBlocks) {
			throw new BlueprintLimitException("LimitError: build exceeds the " + maxBlocks + "-block limit");
		}
		if (size == 0) {
			minX = maxX = x;
			minY = maxY = y;
			minZ = maxZ = z;
			boundsStale = false;
		} else {
			checkSpan(x, y, z);
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
		}
		if (slots == keys.length) {
			int capacity = keys.length + (keys.length >> 1);
			keys = Arrays.copyOf(keys, capacity);
			blocks = Arrays.copyOf(blocks, capacity);
		}
		keys[slots] = key;
		blocks[slots] = block;
		index.put(key, slots);
		slots++;
		size++;
		usage[block]++;
	}

	/** The normalized block id at a position, or {@code null} if nothing was placed there. */
	public String get(int x, int y, int z) {
		if (!inRange(x) || !inRange(y) || !inRange(z)) {
			return null;
		}
		int slot = index.get(pack(x, y, z));
		return slot == LongIntMap.MISSING ? null : palette.get(blocks[slot]);
	}

	/** Removes the entry at a position (not the same as placing air). Returns true if one existed. */
	public boolean remove(int x, int y, int z) {
		if (!inRange(x) || !inRange(y) || !inRange(z)) {
			return false;
		}
		int slot = index.remove(pack(x, y, z));
		if (slot == LongIntMap.MISSING) {
			return false;
		}
		usage[blocks[slot]]--;
		blocks[slot] = REMOVED;
		size--;
		if (x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ) {
			boundsStale = true;
		}
		if (iterating == 0) {
			if (size == 0) {
				slots = 0;
			} else if (slots >= 64 && (slots - size) * 2 > slots) {
				compact();
			}
		}
		return true;
	}

	/** Number of distinct positions (air placements included). */
	public int size() {
		return size;
	}

	public boolean isEmpty() {
		return size() == 0;
	}

	/** Bounding box of all entries (air included), or {@code null} when empty. */
	public Bounds bounds() {
		if (size == 0) {
			return null;
		}
		if (boundsStale) {
			recomputeBounds();
		}
		return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/**
	 * Distinct normalized block ids in first-use order. Only ids that are still present are listed
	 * (an id fully replaced or removed disappears).
	 */
	public List<String> palette() {
		ArrayList<String> live = new ArrayList<>(palette.size());
		for (int i = 0; i < palette.size(); i++) {
			if (usage[i] > 0) {
				live.add(palette.get(i));
			}
		}
		return Collections.unmodifiableList(live);
	}

	/**
	 * Visits every entry in insertion order. The visitor may overwrite existing positions; entries
	 * added during the visit are not visited, and removed ones are skipped if not yet visited.
	 */
	public void forEach(Visitor visitor) {
		iterating++;
		try {
			int end = slots;
			for (int i = 0; i < end; i++) {
				int block = blocks[i];
				if (block != REMOVED) {
					long key = keys[i];
					visitor.accept(unpackX(key), unpackY(key), unpackZ(key), palette.get(block));
				}
			}
		} finally {
			iterating--;
		}
	}

	/** Replaces every occurrence of palette entry {@code from} with {@code to} (both normalized first). Returns positions changed. */
	public int replaceAll(String from, String to) {
		String source = BlockIds.normalize(from);
		String target = BlockIds.normalize(to);
		Integer sourceIndex = paletteIndex.get(source);
		if (sourceIndex == null || usage[sourceIndex] == 0 || source.equals(target)) {
			return 0;
		}
		int s = sourceIndex;
		int t = paletteIndexOfNormalized(target);
		int changed = 0;
		for (int i = 0; i < slots; i++) {
			if (blocks[i] == s) {
				blocks[i] = t;
				changed++;
			}
		}
		usage[s] -= changed;
		usage[t] += changed;
		return changed;
	}

	public int maxBlocks() {
		return maxBlocks;
	}

	public int maxSpan() {
		return maxSpan;
	}

	private int paletteIndexOfRaw(String raw) {
		if (raw != null && raw.equals(lastRaw)) {
			return lastIndex;
		}
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("block id must not be " + (raw == null ? "null" : "blank"));
		}
		Integer cached = rawIndex.get(raw);
		int result;
		if (cached != null) {
			result = cached;
		} else {
			result = paletteIndexOfNormalized(BlockIds.normalize(raw));
			if (rawIndex.size() < RAW_CACHE_LIMIT) {
				rawIndex.put(raw, result);
			}
		}
		lastRaw = raw;
		lastIndex = result;
		return result;
	}

	private int paletteIndexOfNormalized(String normalized) {
		Integer existing = paletteIndex.get(normalized);
		if (existing != null) {
			return existing;
		}
		int created = palette.size();
		palette.add(normalized);
		paletteIndex.put(normalized, created);
		if (created == usage.length) {
			usage = Arrays.copyOf(usage, created * 2);
		}
		return created;
	}

	private static boolean inRange(int c) {
		return c >= -COORD_LIMIT && c <= COORD_LIMIT;
	}

	private static void checkCoordinate(char axis, int c) {
		if (!inRange(c)) {
			throw new BlueprintLimitException("LimitError: " + axis + " coordinate " + c + " is outside +/-" + COORD_LIMIT);
		}
	}

	private void checkSpan(int x, int y, int z) {
		if (spanFits(x, y, z)) {
			return;
		}
		if (boundsStale) {
			recomputeBounds();
			if (spanFits(x, y, z)) {
				return;
			}
		}
		int spanX = Math.max(maxX, x) - Math.min(minX, x) + 1;
		int spanY = Math.max(maxY, y) - Math.min(minY, y) + 1;
		int spanZ = Math.max(maxZ, z) - Math.min(minZ, z) + 1;
		char axis = spanX > maxSpan ? 'x' : spanY > maxSpan ? 'y' : 'z';
		int span = axis == 'x' ? spanX : axis == 'y' ? spanY : spanZ;
		throw new BlueprintLimitException("LimitError: build spans " + span + " blocks along " + axis + " (limit " + maxSpan + ")");
	}

	private boolean spanFits(int x, int y, int z) {
		return Math.max(maxX, x) - Math.min(minX, x) < maxSpan
				&& Math.max(maxY, y) - Math.min(minY, y) < maxSpan
				&& Math.max(maxZ, z) - Math.min(minZ, z) < maxSpan;
	}

	private void recomputeBounds() {
		boolean first = true;
		for (int i = 0; i < slots; i++) {
			if (blocks[i] == REMOVED) {
				continue;
			}
			long key = keys[i];
			int x = unpackX(key);
			int y = unpackY(key);
			int z = unpackZ(key);
			if (first) {
				minX = maxX = x;
				minY = maxY = y;
				minZ = maxZ = z;
				first = false;
			} else {
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minY = Math.min(minY, y);
				maxY = Math.max(maxY, y);
				minZ = Math.min(minZ, z);
				maxZ = Math.max(maxZ, z);
			}
		}
		boundsStale = false;
	}

	private void compact() {
		int live = 0;
		for (int i = 0; i < slots; i++) {
			if (blocks[i] != REMOVED) {
				keys[live] = keys[i];
				blocks[live] = blocks[i];
				index.put(keys[live], live);
				live++;
			}
		}
		slots = live;
	}

	private static long pack(int x, int y, int z) {
		return ((long) (x + OFFSET) << 32) | ((long) (y + OFFSET) << 16) | (z + OFFSET);
	}

	private static int unpackX(long key) {
		return (int) (key >>> 32) - OFFSET;
	}

	private static int unpackY(long key) {
		return ((int) (key >>> 16) & MASK) - OFFSET;
	}

	private static int unpackZ(long key) {
		return ((int) key & MASK) - OFFSET;
	}
}
