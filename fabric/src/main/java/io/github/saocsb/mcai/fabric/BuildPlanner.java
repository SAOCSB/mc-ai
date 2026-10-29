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

	private BuildPlanner() {
	}

	/** The player's block position, cardinal facing and ground level. */
	public static PlayerPose pose(ServerPlayer player) {
		BlockPos pos = player.blockPosition();
		return new PlayerPose(pos.getX(), pos.getY(), pos.getZ(), Facing.fromYaw(player.getYRot()), GameContexts.groundY(player));
	}

	/** Terrain surface lookup: first free block above the highest non-air block. */
	public static PlacementPlanner.SurfaceHeight surface(ServerLevel level) {
		return (x, z) -> level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
	}

	/**
	 * Plans the transform for a blueprint (an empty blueprint is treated as a single block at the local origin,
	 * so world actions still get sensible coordinates).
	 *
	 * @param previous the player's previous build transform in the same dimension, or null
	 */
	public static Transform transform(PlacementSpec spec, Blueprint blueprint, PlayerPose pose, Transform previous,
			ServerLevel level) {
		Bounds bounds = blueprint == null || blueprint.isEmpty() ? new Bounds(0, 0, 0, 0, 0, 0) : blueprint.bounds();
		return PlacementPlanner.plan(spec == null ? PlacementSpec.defaults() : spec, bounds, pose, previous, surface(level));
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
	 * @param owner the requesting player, or null
	 */
	public static BuildJob plan(ServerLevel level, Blueprint blueprint, Transform transform, String name,
			McBlockValidator validator, UUID owner, List<WorldAction> actions) {
		Rotation rotation = toMinecraft(transform.rotation());
		List<String> ids = blueprint.palette();
		Map<String, Integer> index = new HashMap<>();
		BlockInput[] inputs = new BlockInput[ids.size()];
		byte[] category = new byte[ids.size()];
		for (int i = 0; i < ids.size(); i++) {
			String id = ids.get(i);
			index.put(id, i);
			BlockStateParser.BlockResult result = validator.result(id);
			if (result == null) {
				category[i] = INVALID;
				continue;
			}
			BlockState rotated = result.blockState().rotate(rotation);
			inputs[i] = new BlockInput(rotated, rotateProperties(result.properties().keySet(), rotation, rotated), result.nbt());
			category[i] = rotated.isAir() ? AIR : rotated.canOcclude() ? SOLID : OTHER;
		}

		Collector collector = new Collector(level, transform, index, category, blueprint.size());
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

	/** Collects world positions and sort keys: category (8 bits) | y key (24 bits) | sequence (32 bits). */
	private static final class Collector implements Blueprint.Visitor {
		final ServerLevel level;
		final Transform transform;
		final Map<String, Integer> index;
		final byte[] category;
		final int minY;
		final int maxY;
		final long[] keys;
		final long[] positions;
		final int[] palette;
		int count;
		int skipped;

		Collector(ServerLevel level, Transform transform, Map<String, Integer> index, byte[] category, int size) {
			this.level = level;
			this.transform = transform;
			this.index = index;
			this.category = category;
			this.minY = level.getMinY();
			this.maxY = level.getMaxY();
			this.keys = new long[size];
			this.positions = new long[size];
			this.palette = new int[size];
		}

		@Override
		public void accept(int x, int y, int z, String blockId) {
			Integer paletteIndex = index.get(blockId);
			int wx = transform.worldX(x, z);
			int wy = transform.worldY(y);
			int wz = transform.worldZ(x, z);
			if (paletteIndex == null || category[paletteIndex] == INVALID || level.isOutsideBuildHeight(wy)
					|| wx < -WORLD_LIMIT || wx >= WORLD_LIMIT || wz < -WORLD_LIMIT || wz >= WORLD_LIMIT) {
				skipped++;
				return;
			}
			byte c = category[paletteIndex];
			long yKey = c == AIR ? maxY - wy : wy - minY;
			int seq = count++;
			positions[seq] = BlockPos.asLong(wx, wy, wz);
			palette[seq] = paletteIndex;
			keys[seq] = ((long) c << 56) | (yKey << 32) | seq;
		}
	}
}
