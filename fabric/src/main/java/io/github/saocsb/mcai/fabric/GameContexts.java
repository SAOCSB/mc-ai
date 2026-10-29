package io.github.saocsb.mcai.fabric;

import io.github.saocsb.mcai.core.agent.GameContext;
import io.github.saocsb.mcai.core.placement.Facing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/** Captures a {@link GameContext} for a player. Server thread only. */
public final class GameContexts {
	private static final long DAY_TICKS = 24_000L;

	private GameContexts() {
	}

	public static GameContext capture(ServerPlayer player, int maxBlocks) {
		ServerLevel level = player.level();
		BlockPos pos = player.blockPosition();
		String dimension = level.dimension().identifier().toString();
		String biome = level.getBiome(pos).unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");
		long dayTime = Math.floorMod(level.getOverworldClockTime(), DAY_TICKS);
		String weather = level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear";
		String gameMode = player.gameMode().getName();
		String heldItem = BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString();
		List<String> others = new ArrayList<>();
		for (ServerPlayer other : level.getServer().getPlayerList().getPlayers()) {
			if (other != player) {
				others.add(other.getName().getString());
			}
		}
		return new GameContext(player.getName().getString(), dimension, biome, pos.getX(), pos.getY(), pos.getZ(),
				Facing.fromYaw(player.getYRot()).lowerName(), dayTime, weather, gameMode, heldItem, groundY(player),
				level.getMinY(), level.getMaxY(), maxBlocks, List.copyOf(others), level.getDifficulty().getSerializedName());
	}

	/**
	 * World Y used as local y = 0 in "player" ground mode: the feet block when standing on something, otherwise
	 * the terrain surface below the player (MOTION_BLOCKING_NO_LEAVES heightmap, so tree tops don't count; the feet
	 * level if the surface is above them, e.g. in a cave, or if the column has no ground at all, e.g. over the void).
	 */
	public static int groundY(ServerPlayer player) {
		BlockPos pos = player.blockPosition();
		if (player.onGround()) {
			return pos.getY();
		}
		ServerLevel level = player.level();
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
		return surface <= level.getMinY() ? pos.getY() : Math.min(surface, pos.getY());
	}
}
