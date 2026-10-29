package io.github.saocsb.mcai.fabric.test;

import io.github.saocsb.mcai.core.agent.InvalidBlock;
import io.github.saocsb.mcai.core.blueprint.Blueprint;
import io.github.saocsb.mcai.core.config.ModConfig;
import io.github.saocsb.mcai.core.placement.Rotation;
import io.github.saocsb.mcai.core.placement.Transform;
import io.github.saocsb.mcai.core.script.WorldAction;
import io.github.saocsb.mcai.fabric.BuildJob;
import io.github.saocsb.mcai.fabric.BuildPlanner;
import io.github.saocsb.mcai.fabric.CommandRunner;
import io.github.saocsb.mcai.fabric.McAiMod;
import io.github.saocsb.mcai.fabric.McBlockValidator;
import io.github.saocsb.mcai.fabric.UndoRecord;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.List;

/** Server game tests for the Minecraft adapter. All blocks stay inside the default empty 8x8x8 structure, above y = 0. */
public class McAiGameTest {
	@GameTest
	public void serviceStartsWithTheServer(GameTestHelper helper) {
		// Covers config loading (Gson), the script engine and the LLM client setup in a real server.
		helper.assertTrue(McAiMod.service() != null, "AiService should be running after SERVER_STARTED");
		helper.assertTrue(McAiMod.service().config() != null, "config loaded");
		helper.succeed();
	}

	@GameTest
	public void validatorAcceptsKnownBlocksAndRejectsUnknown(GameTestHelper helper) {
		McBlockValidator validator = validator(helper);
		List<InvalidBlock> invalid = validator.validate(List.of("stone", "oak_stairs[facing=north]", "notablock"));
		helper.assertValueEqual(invalid.size(), 1, "number of invalid blocks");
		helper.assertValueEqual(invalid.get(0).blockId(), "notablock", "rejected block id");
		helper.assertTrue(validator.result("oak_stairs[facing=north]") != null, "stairs should be cached after validation");
		helper.assertTrue(validator.result("stone[facing=north]") == null, "stone has no facing property");
		helper.succeed();
	}

	@GameTest
	public void buildPlacesBlocksAndRotatesStates(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Blueprint blueprint = new Blueprint(1_000, 64);
		blueprint.set(0, 0, 0, "oak_stairs[facing=north]");
		for (int y = 0; y < 3; y++) {
			blueprint.set(2, y, 0, "stone");
		}
		// Local (0,0,0) -> relative (3,1,3); with CLOCKWISE_90, local (2,y,0) -> relative (3,1+y,5).
		Transform transform = transformAt(helper, new BlockPos(3, 1, 3), Rotation.CLOCKWISE_90);
		BuildJob job = BuildPlanner.plan(level, blueprint, transform, "test", validator(helper), null, List.of());
		helper.assertValueEqual(job.total(), 4, "planned entries");
		job.runToCompletion(true);
		helper.assertValueEqual(job.placed(), 4, "placed blocks");

		BlockState stairs = level.getBlockState(world(transform, 0, 0, 0));
		helper.assertTrue(stairs.getBlock() == Blocks.OAK_STAIRS, "stairs expected at the local origin");
		helper.assertValueEqual(stairs.getValue(StairBlock.FACING), Direction.EAST, "stairs facing after a clockwise turn");
		for (int y = 0; y < 3; y++) {
			helper.assertTrue(level.getBlockState(world(transform, 2, y, 0)).getBlock() == Blocks.STONE,
					"stone expected at local (2," + y + ",0)");
		}
		helper.succeed();
	}

	@GameTest
	public void undoRestoresPreviousBlocks(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Blueprint blueprint = new Blueprint(1_000, 64);
		for (int x = 0; x < 2; x++) {
			for (int y = 0; y < 2; y++) {
				for (int z = 0; z < 2; z++) {
					blueprint.set(x, y, z, "cobblestone");
				}
			}
		}
		Transform transform = transformAt(helper, new BlockPos(2, 1, 2), Rotation.NONE);
		BuildJob job = BuildPlanner.plan(level, blueprint, transform, "cube", validator(helper), null, List.of());
		job.runToCompletion(true);
		helper.assertTrue(level.getBlockState(world(transform, 1, 1, 1)).getBlock() == Blocks.COBBLESTONE, "cube was built");

		UndoRecord record = job.record();
		helper.assertValueEqual(record.size(), 8, "recorded previous states");
		BuildJob.undo(record, null).runToCompletion(true);
		for (int x = 0; x < 2; x++) {
			for (int y = 0; y < 2; y++) {
				for (int z = 0; z < 2; z++) {
					helper.assertTrue(level.getBlockState(world(transform, x, y, z)).isAir(),
							"air expected after undo at local (" + x + "," + y + "," + z + ")");
				}
			}
		}
		helper.succeed();
	}

	@GameTest
	public void buildFixesLeavesDoorsAndBeds(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Blueprint blueprint = new Blueprint(1_000, 64);
		blueprint.set(0, 0, 0, "oak_leaves");
		blueprint.set(2, 0, 0, "oak_door[facing=south]");
		blueprint.set(4, 0, 2, "red_bed[facing=north]");
		// Already complete door: the planner must not add a second upper half or move anything.
		blueprint.set(0, 0, 2, "spruce_door[facing=south,half=lower]");
		blueprint.set(0, 1, 2, "spruce_door[facing=south,half=upper]");
		Transform transform = transformAt(helper, new BlockPos(2, 1, 3), Rotation.NONE);
		BuildJob job = BuildPlanner.plan(level, blueprint, transform, "fixups", validator(helper), null, List.of());
		helper.assertValueEqual(job.total(), 7, "planned entries (5 written + door top + bed head)");
		job.runToCompletion(true);

		BlockState leaves = level.getBlockState(world(transform, 0, 0, 0));
		helper.assertTrue(leaves.getBlock() == Blocks.OAK_LEAVES, "leaves placed");
		helper.assertTrue(leaves.getValue(BlockStateProperties.PERSISTENT), "leaves must be persistent");

		BlockState doorTop = level.getBlockState(world(transform, 2, 1, 0));
		helper.assertTrue(doorTop.getBlock() == Blocks.OAK_DOOR, "upper door half added");
		helper.assertValueEqual(doorTop.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF), DoubleBlockHalf.UPPER, "door half");
		helper.assertValueEqual(level.getBlockState(world(transform, 2, 0, 0)).getValue(BlockStateProperties.DOUBLE_BLOCK_HALF),
				DoubleBlockHalf.LOWER, "lower door half kept");

		BlockState bedHead = level.getBlockState(world(transform, 4, 0, 1));
		helper.assertTrue(bedHead.getBlock() == Blocks.RED_BED, "bed head added one block north of the foot");
		helper.assertValueEqual(bedHead.getValue(BlockStateProperties.BED_PART), BedPart.HEAD, "bed part");

		helper.assertTrue(level.getBlockState(world(transform, 0, 1, 2)).getBlock() == Blocks.SPRUCE_DOOR, "complete door kept");
		helper.succeed();
	}

	@GameTest
	public void commandPolicyRejectsUnsafeCommands(GameTestHelper helper) {
		ModConfig.Commands policy = new ModConfig.Commands();
		helper.assertTrue(CommandRunner.check("kill @s", policy) != null, "non-whitelisted command must be rejected");
		helper.assertTrue(CommandRunner.check("tp @e ~ ~10 ~", policy) != null, "@e selector must be rejected");
		helper.assertTrue(CommandRunner.check("execute run say hi", policy) != null, "execute is never allowed");
		helper.assertTrue(CommandRunner.check("/minecraft:summon pig ~ ~ ~", policy) == null, "whitelisted command must pass");

		String summon = CommandRunner.toCommand(new WorldAction.Summon("Pig", 1, 0, 2, null),
				new Transform(10, 64, 20, Rotation.CLOCKWISE_90));
		helper.assertValueEqual(summon, "summon pig 8.5 64 21.5", "summon command");
		helper.succeed();
	}

	private static McBlockValidator validator(GameTestHelper helper) {
		return new McBlockValidator(helper.getLevel().holderLookup(Registries.BLOCK));
	}

	private static Transform transformAt(GameTestHelper helper, BlockPos relative, Rotation rotation) {
		BlockPos origin = helper.absolutePos(relative);
		return new Transform(origin.getX(), origin.getY(), origin.getZ(), rotation);
	}

	private static BlockPos world(Transform transform, int x, int y, int z) {
		return new BlockPos(transform.worldX(x, z), transform.worldY(y), transform.worldZ(x, z));
	}
}
