package io.github.saocsb.mcai.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * The world states a build replaced, in placement order, so the build can be reverted.
 * Positions are packed with {@link BlockPos#asLong(int, int, int)}. Not thread-safe (server thread only).
 */
public final class UndoRecord {
	private final String name;
	private final ServerLevel level;
	private long[] positions;
	private BlockState[] states;
	private int size;

	public UndoRecord(String name, ServerLevel level, int expectedSize) {
		this.name = name;
		this.level = level;
		int capacity = Math.max(16, expectedSize);
		this.positions = new long[capacity];
		this.states = new BlockState[capacity];
	}

	/** Records the state that was at {@code pos} before the build changed it. */
	public void add(BlockPos pos, BlockState previous) {
		if (size == positions.length) {
			int capacity = positions.length + (positions.length >> 1) + 16;
			positions = Arrays.copyOf(positions, capacity);
			states = Arrays.copyOf(states, capacity);
		}
		positions[size] = pos.asLong();
		states[size] = previous;
		size++;
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
		return states[i];
	}

	/** A record holding only the first {@code count} entries (used when an undo is interrupted). */
	public UndoRecord prefix(int count) {
		int n = Math.max(0, Math.min(count, size));
		UndoRecord copy = new UndoRecord(name, level, n);
		System.arraycopy(positions, 0, copy.positions, 0, n);
		System.arraycopy(states, 0, copy.states, 0, n);
		copy.size = n;
		return copy;
	}
}
