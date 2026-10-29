package io.github.saocsb.mcai.core.blueprint;

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
 * Not thread-safe.
 */
public final class Blueprint implements BlockSink {
	/** Absolute bound for any local coordinate. */
	public static final int COORD_LIMIT = 30_000;

	/** Visitor for {@link #forEach(Visitor)}. */
	@FunctionalInterface
	public interface Visitor {
		void accept(int x, int y, int z, String blockId);
	}

	public Blueprint(int maxBlocks, int maxSpan) {
		throw new UnsupportedOperationException("TODO");
	}

	/** {@inheritDoc} Throws {@link IllegalArgumentException} for blank ids and {@link BlueprintLimitException} on limits. */
	@Override
	public void set(int x, int y, int z, String blockId) {
		throw new UnsupportedOperationException("TODO");
	}

	/** The normalized block id at a position, or {@code null} if nothing was placed there. */
	public String get(int x, int y, int z) {
		throw new UnsupportedOperationException("TODO");
	}

	/** Removes the entry at a position (not the same as placing air). Returns true if one existed. */
	public boolean remove(int x, int y, int z) {
		throw new UnsupportedOperationException("TODO");
	}

	/** Number of distinct positions (air placements included). */
	public int size() {
		throw new UnsupportedOperationException("TODO");
	}

	public boolean isEmpty() {
		return size() == 0;
	}

	/** Bounding box of all entries (air included), or {@code null} when empty. */
	public Bounds bounds() {
		throw new UnsupportedOperationException("TODO");
	}

	/** Distinct normalized block ids in first-use order. */
	public List<String> palette() {
		throw new UnsupportedOperationException("TODO");
	}

	/** Visits every entry in insertion order. */
	public void forEach(Visitor visitor) {
		throw new UnsupportedOperationException("TODO");
	}

	/** Replaces every occurrence of palette entry {@code from} with {@code to} (both normalized first). Returns positions changed. */
	public int replaceAll(String from, String to) {
		throw new UnsupportedOperationException("TODO");
	}

	public int maxBlocks() {
		throw new UnsupportedOperationException("TODO");
	}

	public int maxSpan() {
		throw new UnsupportedOperationException("TODO");
	}
}
