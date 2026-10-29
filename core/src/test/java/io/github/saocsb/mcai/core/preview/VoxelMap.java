package io.github.saocsb.mcai.core.preview;

import io.github.saocsb.mcai.core.blueprint.BlockSink;
import io.github.saocsb.mcai.core.blueprint.Blueprint;
import io.github.saocsb.mcai.core.blueprint.Bounds;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal map-backed voxel set for renderer tests and demo scenes. Unlike {@link Blueprint} it
 * does no id normalization and enforces no limits; a later {@link #set} overwrites.
 */
public final class VoxelMap implements BlockSink, VoxelSource {
	private final Map<Long, String> blocks = new LinkedHashMap<>();
	private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
	private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

	@Override
	public void set(int x, int y, int z, String blockId) {
		blocks.put(key(x, y, z), blockId);
		minX = Math.min(minX, x);
		minY = Math.min(minY, y);
		minZ = Math.min(minZ, z);
		maxX = Math.max(maxX, x);
		maxY = Math.max(maxY, y);
		maxZ = Math.max(maxZ, z);
	}

	public String get(int x, int y, int z) {
		return blocks.get(key(x, y, z));
	}

	public int size() {
		return blocks.size();
	}

	@Override
	public void forEach(Blueprint.Visitor visitor) {
		for (Map.Entry<Long, String> e : blocks.entrySet()) {
			long k = e.getKey();
			visitor.accept(unpack(k, 42), unpack(k, 21), unpack(k, 0), e.getValue());
		}
	}

	@Override
	public Bounds bounds() {
		return blocks.isEmpty() ? null : new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/** Solid cuboid, corners inclusive in any order. */
	public void fill(int x1, int y1, int z1, int x2, int y2, int z2, String id) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					set(x, y, z, id);
				}
			}
		}
	}

	private static long key(int x, int y, int z) {
		return ((long) (x + (1 << 20)) << 42) | ((long) (y + (1 << 20)) << 21) | (z + (1 << 20));
	}

	private static int unpack(long key, int shift) {
		return (int) ((key >>> shift) & ((1 << 21) - 1)) - (1 << 20);
	}
}
