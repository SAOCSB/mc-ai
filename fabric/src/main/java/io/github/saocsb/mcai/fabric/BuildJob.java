package io.github.saocsb.mcai.fabric;

import io.github.saocsb.mcai.core.placement.Transform;
import io.github.saocsb.mcai.core.script.WorldAction;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A block-placement task run a slice at a time by {@link BuildManager} on the server thread.
 *
 * <p>A BUILD job places precomputed entries (see {@link BuildPlanner}) and records every replaced state in
 * {@link #record()}. An UNDO job restores an {@link UndoRecord} in reverse order. Blocks are written with
 * {@link Block#UPDATE_CLIENTS}: clients are updated and connecting shapes recomputed, but neighbours get no
 * block updates (no falling sand, no popping torches mid-build). With {@code updateShapes == false} builds add
 * {@link Block#UPDATE_KNOWN_SHAPE} and keep states exactly as written.
 */
public final class BuildJob {
	private static final Logger LOGGER = LoggerFactory.getLogger("MC-AI");
	private static final int RECENT = 4;

	public enum Kind {
		BUILD,
		UNDO
	}

	private final Kind kind;
	private final UUID owner;
	private final String name;
	private final ServerLevel level;
	private final Transform transform;
	private final List<WorldAction> actions;

	private final long[] positions;
	private final int[] paletteIndex;
	private final BlockInput[] palette;
	private final UndoRecord record;

	private final UndoRecord source;

	private final int total;
	private final int skippedPlanning;
	private int cursor;
	private int placed;
	private int skippedProtected;
	private int skippedErrors;
	private long startMillis;
	private long endMillis;
	private int ticks;
	private boolean silent;
	private final List<BlockPos> recent = new ArrayList<>(RECENT);

	private BuildJob(Kind kind, UUID owner, String name, ServerLevel level, Transform transform, List<WorldAction> actions,
			long[] positions, int[] paletteIndex, BlockInput[] palette, UndoRecord source, int skippedPlanning) {
		this.kind = kind;
		this.owner = owner;
		this.name = name;
		this.level = level;
		this.transform = transform;
		this.actions = actions == null ? List.of() : List.copyOf(actions);
		this.positions = positions;
		this.paletteIndex = paletteIndex;
		this.palette = palette;
		this.source = source;
		this.skippedPlanning = skippedPlanning;
		this.total = kind == Kind.BUILD ? positions.length : source.size();
		this.record = kind == Kind.BUILD ? new UndoRecord(name, level, total) : null;
	}

	/** A build job; {@code positions[i]} (packed) gets {@code palette[paletteIndex[i]]}. Used by {@link BuildPlanner}. */
	static BuildJob build(UUID owner, String name, ServerLevel level, Transform transform, List<WorldAction> actions,
			long[] positions, int[] paletteIndex, BlockInput[] palette, int skippedPlanning) {
		return new BuildJob(Kind.BUILD, owner, name, level, transform, actions, positions, paletteIndex, palette, null,
				skippedPlanning);
	}

	/** A job that restores {@code record} (newest change first). {@code owner} may be null (tests). */
	public static BuildJob undo(UndoRecord record, UUID owner) {
		return new BuildJob(Kind.UNDO, owner, record.name(), record.level(), null, List.of(), null, null, null, record, 0);
	}

	/**
	 * Processes up to {@code maxEntries} entries, stopping early once {@code maxNanos} have elapsed.
	 *
	 * @param protectBlockEntities skip positions that hold a block entity (chests, signs...)
	 * @param updateShapes         let fences, panes, stairs and walls connect to their neighbours (BUILD jobs)
	 * @return entries processed
	 */
	public int step(int maxEntries, long maxNanos, boolean protectBlockEntities, boolean updateShapes) {
		if (startMillis == 0) {
			startMillis = System.currentTimeMillis();
		}
		recent.clear();
		long start = System.nanoTime();
		int processed = 0;
		while (cursor < total && processed < maxEntries) {
			if ((processed & 63) == 63 && System.nanoTime() - start >= maxNanos) {
				break;
			}
			if (kind == Kind.BUILD) {
				placeNext(protectBlockEntities, updateShapes);
			} else {
				restoreNext();
			}
			processed++;
		}
		if (cursor >= total && endMillis == 0) {
			endMillis = System.currentTimeMillis();
		}
		return processed;
	}

	/** Runs the whole job synchronously (game tests, small jobs). */
	public void runToCompletion(boolean protectBlockEntities) {
		while (!isDone()) {
			step(Integer.MAX_VALUE, Long.MAX_VALUE, protectBlockEntities, true);
		}
	}

	private void placeNext(boolean protectBlockEntities, boolean updateShapes) {
		BlockPos pos = BlockPos.of(positions[cursor]);
		BlockInput input = palette[paletteIndex[cursor]];
		cursor++;
		try {
			if (protectBlockEntities && level.getBlockEntity(pos) != null) {
				skippedProtected++;
				return;
			}
			BlockState previous = level.getBlockState(pos);
			record.add(pos, previous);
			input.place(level, pos, updateShapes ? Block.UPDATE_CLIENTS : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			placed++;
			remember(pos);
		} catch (RuntimeException e) {
			if (skippedErrors++ == 0) {
				LOGGER.warn("Failed to place {} at {} for build '{}'", input.getState(), pos, name, e);
			}
		}
	}

	private void restoreNext() {
		int i = total - 1 - cursor;
		BlockPos pos = BlockPos.of(source.position(i));
		cursor++;
		try {
			level.setBlock(pos, source.state(i), Block.UPDATE_CLIENTS);
			placed++;
			remember(pos);
		} catch (RuntimeException e) {
			if (skippedErrors++ == 0) {
				LOGGER.warn("Failed to restore {} at {} for undo of '{}'", source.state(i), pos, name, e);
			}
		}
	}

	private void remember(BlockPos pos) {
		if (recent.size() < RECENT) {
			recent.add(pos);
		} else if ((placed & 7) == 0) {
			recent.set(placed % RECENT, pos);
		}
	}

	public Kind kind() {
		return kind;
	}

	/** The requesting player, or null. */
	public UUID owner() {
		return owner;
	}

	public String name() {
		return name;
	}

	public ServerLevel level() {
		return level;
	}

	/** Local-to-world transform of a BUILD job (null for UNDO). */
	public Transform transform() {
		return transform;
	}

	/** World actions to run after a BUILD job completes. */
	public List<WorldAction> actions() {
		return actions;
	}

	/** States replaced so far by a BUILD job (null for UNDO). */
	public UndoRecord record() {
		return record;
	}

	/** For an UNDO job: the part of the source record not restored yet. */
	public UndoRecord remainingUndo() {
		return source == null ? null : source.prefix(total - cursor);
	}

	public int total() {
		return total;
	}

	/** Entries processed so far (placed or skipped). */
	public int done() {
		return cursor;
	}

	public boolean isDone() {
		return cursor >= total;
	}

	public boolean hasStarted() {
		return cursor > 0;
	}

	/** Blocks written. */
	public int placed() {
		return placed;
	}

	/** Entries not written: outside the build height or unparseable (planning), protected block entities, errors. */
	public int skipped() {
		return skippedPlanning + skippedProtected + skippedErrors;
	}

	public int percent() {
		return total == 0 ? 100 : (int) (100L * cursor / total);
	}

	/** Milliseconds from the first step to completion (or to now while running). */
	public long elapsedMillis() {
		if (startMillis == 0) {
			return 0;
		}
		return (endMillis == 0 ? System.currentTimeMillis() : endMillis) - startMillis;
	}

	/** Positions written during the last {@link #step} (a small sample, for particles). */
	public List<BlockPos> recentPositions() {
		return recent;
	}

	int nextTick() {
		return ++ticks;
	}

	/** Silent jobs send no start/progress/done messages (the undo half of a replacing build). */
	public boolean silent() {
		return silent;
	}

	public BuildJob silent(boolean value) {
		this.silent = value;
		return this;
	}
}
