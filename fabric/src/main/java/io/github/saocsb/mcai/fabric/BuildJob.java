package io.github.saocsb.mcai.fabric;

import io.github.saocsb.mcai.core.placement.Transform;
import io.github.saocsb.mcai.core.script.WorldAction;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A block-placement task run a slice at a time by {@link BuildManager} on the server thread.
 *
 * <p>A BUILD job places precomputed entries (see {@link BuildPlanner}) and records, for every position it writes,
 * the state it replaced and the state it left in {@link #record()}. Positions outside the world border, under the
 * requester's spawn protection or (optionally) holding a block entity are skipped. Blocks are written with
 * {@link Block#UPDATE_CLIENTS} and {@link Block#UPDATE_SUPPRESS_DROPS}: clients are updated and connecting shapes
 * recomputed, but neighbours get no block updates (no falling sand, no popping torches mid-build). With
 * {@code updateShapes == false} builds add {@link Block#UPDATE_KNOWN_SHAPE} and keep states exactly as written.
 *
 * <p>An UNDO job restores an {@link UndoRecord} in reverse order, but only where the world still holds the block
 * the build placed (players may have replaced it since) and never over a container that holds items. It writes with
 * {@link #UNDO_FLAGS}, so neighbours are left alone and nothing drops (door or tall-flower halves, container
 * contents).
 */
public final class BuildJob {
	private static final Logger LOGGER = LoggerFactory.getLogger("MC-AI");
	private static final int RECENT = 4;
	private static final AtomicLong IDS = new AtomicLong();
	/** Flags for restoring blocks: no shape updates, no drops, no block-entity side effects. */
	static final int UNDO_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
			| Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

	public enum Kind {
		BUILD,
		UNDO
	}

	private final long id;
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
	private ServerPlayer requester;
	private int cursor;
	private int placed;
	private int skippedProtected;
	private int skippedRestricted;
	private int skippedChanged;
	private int skippedErrors;
	private long startMillis;
	private long endMillis;
	private int ticks;
	private boolean silent;
	private final List<BlockPos> recent = new ArrayList<>(RECENT);

	private BuildJob(long id, Kind kind, UUID owner, String name, ServerLevel level, Transform transform,
			List<WorldAction> actions, long[] positions, int[] paletteIndex, BlockInput[] palette, UndoRecord source,
			int skippedPlanning) {
		this.id = id;
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
		this.record = kind == Kind.BUILD ? new UndoRecord(id, name, level, total) : null;
	}

	/** A build job; {@code positions[i]} (packed) gets {@code palette[paletteIndex[i]]}. Used by {@link BuildPlanner}. */
	static BuildJob build(UUID owner, String name, ServerLevel level, Transform transform, List<WorldAction> actions,
			long[] positions, int[] paletteIndex, BlockInput[] palette, int skippedPlanning) {
		return new BuildJob(IDS.incrementAndGet(), Kind.BUILD, owner, name, level, transform, actions, positions,
				paletteIndex, palette, null, skippedPlanning);
	}

	/** A job that restores {@code record} (newest change first); it has the record's id. {@code owner} may be null (tests). */
	public static BuildJob undo(UndoRecord record, UUID owner) {
		return new BuildJob(record.id(), Kind.UNDO, owner, record.name(), record.level(), null, List.of(), null, null,
				null, record, 0);
	}

	/**
	 * The player whose spawn-protection rights apply to a BUILD job (null: not checked, e.g. in tests). Positions
	 * {@link net.minecraft.server.MinecraftServer#isUnderSpawnProtection} reports for this player are skipped.
	 */
	public BuildJob requester(ServerPlayer player) {
		this.requester = player;
		return this;
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
			if (!level.getWorldBorder().isWithinBounds(pos)
					|| requester != null && level.getServer().isUnderSpawnProtection(level, pos, requester)) {
				skippedRestricted++;
				return;
			}
			if (protectBlockEntities && level.getBlockEntity(pos) != null) {
				skippedProtected++;
				return;
			}
			BlockState previous = level.getBlockState(pos);
			record.add(pos, previous, previous);
			int flags = (updateShapes ? Block.UPDATE_CLIENTS : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)
					| Block.UPDATE_SUPPRESS_DROPS;
			try {
				input.place(level, pos, flags);
			} finally {
				record.setLastPlaced(level.getBlockState(pos));
			}
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
			if (!isStillPlaced(level.getBlockState(pos), source.placed(i)) || holdsItems(pos)) {
				skippedChanged++;
				return;
			}
			level.setBlock(pos, source.state(i), UNDO_FLAGS);
			placed++;
			remember(pos);
		} catch (RuntimeException e) {
			if (skippedErrors++ == 0) {
				LOGGER.warn("Failed to restore {} at {} for undo of '{}'", source.state(i), pos, name, e);
			}
		}
	}

	/**
	 * Whether an undo may restore a position: the world still holds the block the build placed there. The block is
	 * compared, not the full state, because connecting blocks, leaves, doors and the like change their own state
	 * after placement (shape updates, decay distance, opening); a different block means someone replaced it.
	 */
	static boolean isStillPlaced(BlockState current, BlockState placedState) {
		return placedState == null || current.getBlock() == placedState.getBlock();
	}

	/** True for a container block entity with items in it (never overwritten by an undo). */
	private boolean holdsItems(BlockPos pos) {
		return level.getBlockEntity(pos) instanceof Container container && !container.isEmpty();
	}

	private void remember(BlockPos pos) {
		if (recent.size() < RECENT) {
			recent.add(pos);
		} else if ((placed & 7) == 0) {
			recent.set(placed % RECENT, pos);
		}
	}

	/** Unique per BUILD job; an UNDO job has the id of the build it reverts. */
	public long id() {
		return id;
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

	/** Changes made so far by a BUILD job (null for UNDO). */
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

	/**
	 * Entries not written. BUILD: outside the build height or unparseable (planning), outside the world border or
	 * under spawn protection, protected block entities, errors. UNDO: positions changed since the build or holding
	 * items, errors.
	 */
	public int skipped() {
		return skippedPlanning + skippedProtected + skippedRestricted + skippedChanged + skippedErrors;
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
