package io.github.saocsb.mcai.fabric;

import io.github.saocsb.mcai.core.blueprint.Blueprint;
import io.github.saocsb.mcai.core.blueprint.Bounds;
import io.github.saocsb.mcai.core.placement.Facing;
import io.github.saocsb.mcai.core.placement.PlacementPlanner;
import io.github.saocsb.mcai.core.placement.PlacementSpec;
import io.github.saocsb.mcai.core.placement.PlayerPose;
import io.github.saocsb.mcai.core.placement.Transform;
import io.github.saocsb.mcai.core.script.WorldAction;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a {@link Blueprint} into a {@link BuildJob} (server thread): computes the world transform, rotates
 * block states to the player's facing and orders the entries so supports are placed before attachments.
 */
public final class BuildPlanner {
	private static final int WORLD_LIMIT = 30_000_000;
	private static final byte AIR = 0;
	private static final byte SOLID = 1;
	private static final byte OTHER = 2;
	private static final byte INVALID = -1;
	private static final byte NO_COMPANION = 0;
	private static final byte UPPER_HALF = 1;
	private static final byte BED_HEAD = 2;

	private BuildPlanner() {
	}

	/** The player's block position, cardinal facing and ground level. */
	public static PlayerPose pose(ServerPlayer player) {
		BlockPos pos = player.blockPosition();
		return new PlayerPose(pos.getX(), pos.getY(), pos.getZ(), Facing.fromYaw(player.getYRot()), GameContexts.groundY(player));
	}

	/**
	 * Terrain surface lookup: the first free block above the highest motion-blocking block or fluid, ignoring leaves
	 * (so trees and plants don't lift a build). Columns in unloaded or empty chunks report the world bottom; those
	 * give {@code fallbackY} instead.
	 */
	public static PlacementPlanner.SurfaceHeight surface(ServerLevel level, int fallbackY) {
		return (x, z) -> {
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			return y <= level.getMinY() ? fallbackY : y;
		};
	}

	/** The bounds of a blueprint, or a single block at the local origin for an empty (or missing) one. */
	public static Bounds bounds(Blueprint blueprint) {
		return blueprint == null || blueprint.isEmpty() ? new Bounds(0, 0, 0, 0, 0, 0) : blueprint.bounds();
	}

	/**
	 * Plans the transform for a blueprint (an empty blueprint is treated as a single block at the local origin,
	 * so world actions still get sensible coordinates).
	 *
	 * @param previous the player's previous build transform in the same dimension, or null
	 */
	public static Transform transform(PlacementSpec spec, Blueprint blueprint, PlayerPose pose, Transform previous,
			ServerLevel level) {
		return PlacementPlanner.plan(spec == null ? PlacementSpec.defaults() : spec, bounds(blueprint), pose, previous,
				surface(level, pose.groundY()));
	}

	/**
	 * Horizontal distance in blocks (rounded up) from world column ({@code x}, {@code z}) to the nearest column of
	 * {@code bounds} placed with {@code transform}; 0 when the column is inside the footprint.
	 */
	public static int horizontalDistance(Bounds bounds, Transform transform, int x, int z) {
		int ax = transform.worldX(bounds.minX(), bounds.minZ());
		int az = transform.worldZ(bounds.minX(), bounds.minZ());
		int bx = transform.worldX(bounds.maxX(), bounds.maxZ());
		int bz = transform.worldZ(bounds.maxX(), bounds.maxZ());
		long dx = Math.max(0, Math.max(Math.min(ax, bx) - (long) x, x - (long) Math.max(ax, bx)));
		long dz = Math.max(0, Math.max(Math.min(az, bz) - (long) z, z - (long) Math.max(az, bz)));
		return (int) Math.min(Integer.MAX_VALUE, (long) Math.ceil(Math.sqrt((double) dx * dx + (double) dz * dz)));
	}

	/** Minecraft's rotation with the same name. */
	public static Rotation toMinecraft(io.github.saocsb.mcai.core.placement.Rotation rotation) {
		return Rotation.valueOf(rotation.name());
	}

	/**
	 * Converts a blueprint into a build job. Entries outside the build height (or the world border limit) and
	 * entries whose id no longer parses are skipped and counted in {@link BuildJob#skipped()}.
	 * Order: air (top down), then occluding blocks by y ascending, then everything else (torches, doors, panes,
	 * plants, liquids...) by y ascending; insertion order is kept within a layer.
	 *
	 * <p>Fix-ups for common script mistakes: leaves are made persistent (so they never decay), and a lower door /
	 * tall-plant half or a bed foot gets its other half added unless the script already put exactly that half there.
	 * The added half replaces whatever else the script left at that position (nothing, air after {@code clear()}, or
	 * a wall block after {@code walls()}), so there is one entry per position; only another two-part block's base
	 * half is never replaced.
	 *
	 * <p>Ids the validator refuses (denied blocks) are skipped like unknown ids; block-entity data the validator
	 * does not allow was already removed from its results.
	 *
	 * @param owner the requesting player, or null
	 */
	public static BuildJob plan(ServerLevel level, Blueprint blueprint, Transform transform, String name,
			McBlockValidator validator, UUID owner, List<WorldAction> actions) {
		Rotation rotation = toMinecraft(transform.rotation());
		List<String> ids = blueprint.palette();
		int n = ids.size();
		Map<String, Integer> index = new HashMap<>();
		// Slots n..2n-1 hold the companion (upper half / bed head) of palette entry i at n + i.
		BlockInput[] inputs = new BlockInput[2 * n];
		byte[] category = new byte[2 * n];
		byte[] companion = new byte[n];
		int[] companionDx = new int[n];
		int[] companionDz = new int[n];
		for (int i = 0; i < n; i++) {
			String id = ids.get(i);
			index.put(id, i);
			BlockStateParser.BlockResult result = validator.result(id);
			if (result == null) {
				category[i] = INVALID;
				continue;
			}
			BlockState local = result.blockState();
			Set<Property<?>> defined = new HashSet<>(result.properties().keySet());
			if (local.hasProperty(BlockStateProperties.PERSISTENT)) {
				local = local.setValue(BlockStateProperties.PERSISTENT, true);
				defined.add(BlockStateProperties.PERSISTENT);
			}
			BlockState rotated = local.rotate(rotation);
			Set<Property<?>> rotatedDefined = rotateProperties(defined, rotation, rotated);
			inputs[i] = new BlockInput(rotated, rotatedDefined, result.nbt());
			category[i] = rotated.isAir() ? AIR : rotated.canOcclude() ? SOLID : OTHER;

			if (rotated.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
					&& rotated.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
				companion[i] = UPPER_HALF;
				inputs[n + i] = companionInput(rotated.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
						rotatedDefined, BlockStateProperties.DOUBLE_BLOCK_HALF);
			} else if (rotated.hasProperty(BlockStateProperties.BED_PART) && local.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
					&& rotated.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
				// The head lies one block toward the bed's facing, in the script's (unrotated) frame.
				Direction facing = local.getValue(BlockStateProperties.HORIZONTAL_FACING);
				companion[i] = BED_HEAD;
				companionDx[i] = facing.getStepX();
				companionDz[i] = facing.getStepZ();
				inputs[n + i] = companionInput(rotated.setValue(BlockStateProperties.BED_PART, BedPart.HEAD), rotatedDefined,
						BlockStateProperties.BED_PART);
			}
			category[n + i] = inputs[n + i] == null ? INVALID : OTHER;
		}

		Collector collector = new Collector(level, blueprint, transform, index, inputs, category, companion, companionDx,
				companionDz, blueprint.size());
		blueprint.forEach(collector::claimCompanion);
		blueprint.forEach(collector);

		long[] keys = Arrays.copyOf(collector.keys, collector.count);
		Arrays.sort(keys);
		long[] positions = new long[keys.length];
		int[] palette = new int[keys.length];
		for (int i = 0; i < keys.length; i++) {
			int seq = (int) (keys[i] & 0xFFFFFFFFL);
			positions[i] = collector.positions[seq];
			palette[i] = collector.palette[seq];
		}
		return BuildJob.build(owner, name, level, transform, actions, positions, palette, inputs, collector.skipped);
	}

	private static BlockInput companionInput(BlockState state, Set<Property<?>> defined, Property<?> extra) {
		Set<Property<?>> properties = new HashSet<>(defined);
		properties.add(extra);
		return new BlockInput(state, properties, null);
	}

	/**
	 * The properties a script set explicitly, renamed for the rotation where the property is named after a
	 * horizontal direction (fence/pane/wall/mushroom sides), so {@link BlockInput} keeps the rotated values.
	 */
	static Set<Property<?>> rotateProperties(Set<Property<?>> defined, Rotation rotation, BlockState rotated) {
		if (rotation == Rotation.NONE || defined.isEmpty()) {
			return defined;
		}
		StateDefinition<Block, BlockState> definition = rotated.getBlock().getStateDefinition();
		Set<Property<?>> result = new HashSet<>();
		for (Property<?> property : defined) {
			Direction direction = Direction.byName(property.getName());
			if (direction != null && direction.getAxis().isHorizontal()) {
				Property<?> renamed = definition.getProperty(rotation.rotate(direction).getName());
				result.add(renamed != null ? renamed : property);
			} else {
				result.add(property);
			}
		}
		return result;
	}

	/** Packs local coordinates (each within +/-2^19) into one long, for the companion claims. */
	private static long packLocal(int x, int y, int z) {
		return ((long) (x + (1 << 19)) << 40) | ((long) (y + (1 << 19)) << 20) | (z + (1 << 19));
	}

	/**
	 * Collects world positions and sort keys: category (8 bits) | y key (24 bits) | sequence (32 bits).
	 *
	 * <p>Two passes over the blueprint: {@link #claimCompanion} decides which base blocks get their other half added
	 * and which positions those halves take over; {@link #accept} then emits one entry per position, with every added
	 * half right after its base (so it also sorts after it).
	 */
	private static final class Collector implements Blueprint.Visitor {
		final ServerLevel level;
		final Blueprint blueprint;
		final Transform transform;
		final Map<String, Integer> index;
		final BlockInput[] inputs;
		final byte[] category;
		final byte[] companion;
		final int[] companionDx;
		final int[] companionDz;
		final int paletteSize;
		final int minY;
		final int maxY;
		/** Companion position (packed local) -> base position (packed local) whose added half goes there. */
		final Map<Long, Long> claims = new HashMap<>();
		long[] keys;
		long[] positions;
		int[] palette;
		int count;
		int skipped;

		Collector(ServerLevel level, Blueprint blueprint, Transform transform, Map<String, Integer> index,
				BlockInput[] inputs, byte[] category, byte[] companion, int[] companionDx, int[] companionDz, int size) {
			this.level = level;
			this.blueprint = blueprint;
			this.transform = transform;
			this.index = index;
			this.inputs = inputs;
			this.category = category;
			this.companion = companion;
			this.companionDx = companionDx;
			this.companionDz = companionDz;
			this.paletteSize = companion.length;
			this.minY = level.getMinY();
			this.maxY = level.getMaxY();
			this.keys = new long[size];
			this.positions = new long[size];
			this.palette = new int[size];
		}

		/**
		 * First pass: a base block (lower door / tall-plant half, bed foot) claims its companion position unless the
		 * script put exactly the other half there, or another two-part block's base. The first claim wins.
		 */
		void claimCompanion(int x, int y, int z, String blockId) {
			Integer base = index.get(blockId);
			if (base == null || category[base] == INVALID || companion[base] == NO_COMPANION || !inWorld(x, y, z)) {
				return;
			}
			int cx = companionX(base, x);
			int cy = companionY(base, y);
			int cz = companionZ(base, z);
			String there = blueprint.get(cx, cy, cz);
			Integer other = there == null ? null : index.get(there);
			if (other != null && (companion[other] != NO_COMPANION || isOtherHalf(other, base))) {
				return;
			}
			claims.putIfAbsent(packLocal(cx, cy, cz), packLocal(x, y, z));
		}

		@Override
		public void accept(int x, int y, int z, String blockId) {
			long here = packLocal(x, y, z);
			if (claims.containsKey(here)) {
				return; // replaced by the other half of a door, bed or tall plant
			}
			Integer paletteIndex = index.get(blockId);
			if (paletteIndex == null || category[paletteIndex] == INVALID) {
				skipped++;
				return;
			}
			if (!add(x, y, z, paletteIndex) || companion[paletteIndex] == NO_COMPANION) {
				return;
			}
			int cx = companionX(paletteIndex, x);
			int cy = companionY(paletteIndex, y);
			int cz = companionZ(paletteIndex, z);
			Long claimer = claims.get(packLocal(cx, cy, cz));
			if (claimer != null && claimer == here) {
				add(cx, cy, cz, paletteSize + paletteIndex);
			}
		}

		/** Whether palette entry {@code other} is exactly the half that base entry {@code base} would add. */
		private boolean isOtherHalf(int other, int base) {
			if (inputs[other] == null || inputs[paletteSize + base] == null) {
				return false;
			}
			BlockState have = inputs[other].getState();
			if (have.getBlock() != inputs[paletteSize + base].getState().getBlock()) {
				return false;
			}
			if (companion[base] == UPPER_HALF) {
				return have.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
						&& have.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
			}
			return have.hasProperty(BlockStateProperties.BED_PART) && have.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
		}

		private int companionX(int base, int x) {
			return companion[base] == BED_HEAD ? x + companionDx[base] : x;
		}

		private int companionY(int base, int y) {
			return companion[base] == UPPER_HALF ? y + 1 : y;
		}

		private int companionZ(int base, int z) {
			return companion[base] == BED_HEAD ? z + companionDz[base] : z;
		}

		/** Whether a local position lands inside the build height and the world limit. */
		private boolean inWorld(int x, int y, int z) {
			int wx = transform.worldX(x, z);
			int wz = transform.worldZ(x, z);
			return !level.isOutsideBuildHeight(transform.worldY(y)) && wx >= -WORLD_LIMIT && wx < WORLD_LIMIT
					&& wz >= -WORLD_LIMIT && wz < WORLD_LIMIT;
		}

		private boolean add(int x, int y, int z, int paletteIndex) {
			if (!inWorld(x, y, z)) {
				skipped++;
				return false;
			}
			int wx = transform.worldX(x, z);
			int wy = transform.worldY(y);
			int wz = transform.worldZ(x, z);
			if (count == keys.length) {
				int capacity = Math.max(16, keys.length + (keys.length >> 1));
				keys = Arrays.copyOf(keys, capacity);
				positions = Arrays.copyOf(positions, capacity);
				palette = Arrays.copyOf(palette, capacity);
			}
			byte c = category[paletteIndex];
			long yKey = c == AIR ? maxY - wy : wy - minY;
			int seq = count++;
			positions[seq] = BlockPos.asLong(wx, wy, wz);
			palette[seq] = paletteIndex;
			keys[seq] = ((long) c << 56) | (yKey << 32) | seq;
			return true;
		}
	}
}
