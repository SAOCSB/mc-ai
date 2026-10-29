package io.github.saocsb.mcai.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * What a build changed, in placement order, so the build can be reverted: for each position the state that was
 * there before and the state the build left there. Positions are packed with {@link BlockPos#asLong(int, int, int)}.
 * The id is the id of the {@link BuildJob} that made the record. Not thread-safe (server thread only).
 */
public final class UndoRecord {
	private final long id;
	private final String name;
	private final ServerLevel level;
	private long[] positions;
	private BlockState[] previous;
	private BlockState[] placed;
	private int size;

	public UndoRecord(long id, String name, ServerLevel level, int expectedSize) {
		this.id = id;
		this.name = name;
		this.level = level;
		int capacity = Math.max(16, expectedSize);
		this.positions = new long[capacity];
		this.previous = new BlockState[capacity];
		this.placed = new BlockState[capacity];
	}

	/**
	 * Records a change at {@code pos}.
	 *
	 * @param before the state before the build changed it
	 * @param after  the state the build placed (updated with {@link #setLastPlaced} once known)
	 */
	public void add(BlockPos pos, BlockState before, BlockState after) {
		if (size == positions.length) {
			int capacity = positions.length + (positions.length >> 1) + 16;
			positions = Arrays.copyOf(positions, capacity);
			previous = Arrays.copyOf(previous, capacity);
			placed = Arrays.copyOf(placed, capacity);
		}
		positions[size] = pos.asLong();
		previous[size] = before;
		placed[size] = after;
		size++;
	}

	/** Replaces the placed state of the newest entry (read back from the world after placing). */
	public void setLastPlaced(BlockState after) {
		if (size > 0) {
			placed[size - 1] = after;
		}
	}

	/** The id of the build job that made this record. */
	public long id() {
		return id;
	}

	public String name() {
		return name;
	}

	public ServerLevel level() {
		return level;
	}

	public int size() {
		return size;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	/** Packed position of entry {@code i}. */
	public long position(int i) {
		return positions[i];
	}

	/** Recorded (previous) state of entry {@code i}. */
	public BlockState state(int i) {
		return previous[i];
	}

	/** The state the build placed at entry {@code i}. */
	public BlockState placed(int i) {
		return placed[i];
	}

	/** A record holding only the first {@code count} entries, with the same id (used when an undo is interrupted). */
	public UndoRecord prefix(int count) {
		int n = Math.max(0, Math.min(count, size));
		UndoRecord copy = new UndoRecord(id, name, level, n);
		System.arraycopy(positions, 0, copy.positions, 0, n);
		System.arraycopy(previous, 0, copy.previous, 0, n);
		System.arraycopy(placed, 0, copy.placed, 0, n);
		copy.size = n;
		return copy;
	}
}
