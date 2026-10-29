package io.github.saocsb.mcai.fabric.test;

import io.github.saocsb.mcai.core.agent.InvalidBlock;
import io.github.saocsb.mcai.core.blueprint.Blueprint;
import io.github.saocsb.mcai.core.blueprint.Bounds;
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
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
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
		helper.assertTrue(bedHead.getBlock() instanceof BedBlock, "bed head added one block north of the foot");
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
		helper.assertTrue(CommandRunner.check("particle flame ~ ~ ~ 1 1 1 0 100000", policy) != null,
				"particle is no longer whitelisted by default");
		helper.assertTrue(CommandRunner.check("say @ai build a tower", policy) != null, "say is no longer whitelisted by default");
		helper.succeed();
	}

	@GameTest
	public void validatorRefusesGameMasterAndDeniedBlocks(GameTestHelper helper) {
		McBlockValidator validator = validator(helper);
		List<InvalidBlock> invalid = validator.validate(List.of("minecraft:command_block{auto:1b,Command:\"op @p\"}",
				"minecraft:repeating_command_block", "minecraft:structure_block", "minecraft:jigsaw", "minecraft:spawner",
				"minecraft:trial_spawner", "minecraft:stone"));
		helper.assertValueEqual(invalid.size(), 6, "refused blocks");
		for (InvalidBlock block : invalid) {
			helper.assertValueEqual(block.reason(), McBlockValidator.NOT_ALLOWED, "reason for " + block.blockId());
		}
		helper.assertTrue(validator.result("minecraft:command_block") == null, "no parse result for a command block");
		helper.assertTrue(validator.result("minecraft:spawner") == null, "no parse result for a denied block");

		// Game-master blocks stay refused even with an empty deny list.
		validator.setDeniedBlocks(List.of());
		helper.assertTrue(validator.result("minecraft:chain_command_block") == null, "command blocks are always refused");
		helper.assertTrue(validator.result("minecraft:spawner") != null, "spawner allowed once removed from the list");

		ServerLevel level = helper.getLevel();
		Blueprint blueprint = new Blueprint(1_000, 64);
		blueprint.set(0, 0, 0, "minecraft:command_block{auto:1b,Command:\"op @p\"}");
		blueprint.set(1, 0, 0, "minecraft:stone");
		Transform transform = transformAt(helper, new BlockPos(2, 1, 2), Rotation.NONE);
		BuildJob job = BuildPlanner.plan(level, blueprint, transform, "cmd", validator(helper), null, List.of());
		job.runToCompletion(true);
		helper.assertTrue(level.getBlockState(world(transform, 0, 0, 0)).isAir(), "command block not placed");
		helper.assertTrue(level.getBlockState(world(transform, 1, 0, 0)).getBlock() == Blocks.STONE, "stone placed");
		helper.assertValueEqual(job.skipped(), 1, "the command block counts as skipped");
		helper.succeed();
	}

	@GameTest
	public void blockEntityDataIsOnlyKeptForStorageBlocks(GameTestHelper helper) {
		McBlockValidator validator = validator(helper);
		String sign = "minecraft:oak_sign{front_text:{has_glowing_text:1b,messages:['\"a\"','\"b\"','\"c\"','\"d\"']}}";
		String lectern = "minecraft:lectern{Book:{id:\"minecraft:written_book\",count:1}}";
		String chest = "minecraft:chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}";
		helper.assertValueEqual(validator.validate(List.of(sign, lectern, chest)).size(), 0, "all three are valid blocks");
		helper.assertTrue(validator.result(sign) != null && validator.result(sign).nbt() == null, "sign NBT dropped");
		helper.assertTrue(validator.result(lectern) != null && validator.result(lectern).nbt() == null, "lectern NBT dropped");
		helper.assertTrue(validator.result(chest) != null && validator.result(chest).nbt() != null, "chest NBT kept");
		helper.assertTrue(McBlockValidator.keepsNbt(Blocks.BARREL.defaultBlockState()), "barrel keeps NBT");
		helper.assertFalse(McBlockValidator.keepsNbt(Blocks.OAK_SIGN.defaultBlockState()), "sign never keeps NBT");

		ServerLevel level = helper.getLevel();
		Blueprint blueprint = new Blueprint(1_000, 64);
		blueprint.set(0, 0, 0, sign);
		blueprint.set(2, 0, 0, chest);
		Transform transform = transformAt(helper, new BlockPos(2, 1, 2), Rotation.NONE);
		BuildJob job = BuildPlanner.plan(level, blueprint, transform, "nbt", validator, null, List.of());
		job.runToCompletion(true);
		helper.assertTrue(level.getBlockState(world(transform, 0, 0, 0)).getBlock() == Blocks.OAK_SIGN, "sign placed without data");
		helper.assertTrue(level.getBlockEntity(world(transform, 2, 0, 0)) instanceof Container container
				&& !container.getItem(0).isEmpty(), "chest filled from its NBT");
		helper.succeed();
	}

	@GameTest
	public void commandPolicyRejectsEscalationAndOtherTargets(GameTestHelper helper) {
		ModConfig.Commands policy = new ModConfig.Commands();
		List<String> others = List.of("Alex");
		List<String> denied = ModConfig.DEFAULT_DENIED_BLOCKS;
		String[] rejected = {
				"summon falling_block ~ ~1 ~ {BlockState:{Name:\"minecraft:stone\"},TileEntityData:{Command:\"op @p\",auto:1b}}",
				"summon minecraft:falling_block ~ ~ ~ {BlockState:{Name:\"stone\"},block_entity_data:{id:\"sign\"}}",
				"summon command_block_minecart ~ ~ ~",
				"summon minecraft:spawner_minecart ~ ~ ~",
				"summon pig ~ ~ ~ {Passengers:[{id:\"minecraft:spawner_minecart\"}]}",
				"summon falling_block ~ ~ ~ {BlockState:{Name:\"minecraft:spawner\"}}",
				"give @s spawner 1",
				"give @s oak_sign[block_entity_data={id:\"sign\",front_text:{}}] 1",
				"tellraw @s {\"text\":\"x\",\"click_event\":{\"action\":\"run_command\",\"command\":\"/op me\"}}",
				"give Alex diamond 1",
				"effect give alex speed 10",
				"give @s[name=x] diamond 1 Alex",
				"effect give @p[limit=2] speed 10",
				"give @a diamond 1",
				"give 0-0-0-0-1 diamond 1",
				"tp @p ~ ~ ~",
				"tp Alex",
				"teleport @e[type=pig] ~ ~ ~",
				"tp ~ ~ ~ ~ ~",
		};
		for (String command : rejected) {
			helper.assertTrue(CommandRunner.check(command, policy, others, denied) != null, "must be rejected: " + command);
		}
		String[] allowed = {
				"give @s diamond 1",
				"effect give @p speed 10",
				"effect give @s[distance=..1] speed 10",
				"tp 10 64 -5",
				"tp ~ ~10 ~",
				"teleport @s ~ ~5 ~",
				"summon pig ~ ~ ~ {CustomName:\"Bob\"}",
		};
		for (String command : allowed) {
			helper.assertTrue(CommandRunner.check(command, policy, others, denied) == null, "must be allowed: " + command);
		}
		helper.assertFalse(CommandRunner.isValid(new WorldAction.Summon("command_block_minecart", 0, 0, 0, null)),
				"command block minecart is never a valid summon");

		policy.allowSelectors = true;
		helper.assertTrue(CommandRunner.check("teleport @e[type=pig] ~ ~ ~", policy, others, denied) == null,
				"allowSelectors permits other targets");
		helper.assertTrue(CommandRunner.check("give Alex diamond 1", policy, others, denied) == null, "names with allowSelectors");
		helper.assertTrue(CommandRunner.check("summon command_block_minecart ~ ~ ~", policy, others, denied) != null,
				"command data stays forbidden with allowSelectors");
		helper.succeed();
	}

	@GameTest
	public void waitSplitsWorldActions(GameTestHelper helper) {
		WorldAction a = new WorldAction.Command("tellraw @s \"a\"");
		WorldAction b = new WorldAction.Command("tellraw @s \"b\"");
		WorldAction c = new WorldAction.Command("tellraw @s \"c\"");
		CommandRunner.Batch first = CommandRunner.split(List.of(a, new WorldAction.Wait(1.5), b, new WorldAction.Wait(0), c));
		helper.assertValueEqual(first.now(), List.of(a), "actions before the wait");
		helper.assertValueEqual(first.waitTicks(), 30, "1.5 s in ticks");
		CommandRunner.Batch second = CommandRunner.split(first.later());
		helper.assertValueEqual(second.now(), List.of(b, c), "a zero wait does not split");
		helper.assertValueEqual(second.later(), List.of(), "nothing left");
		helper.assertValueEqual(CommandRunner.split(List.of(a, new WorldAction.Wait(5))).waitTicks(), 0, "trailing wait ignored");
		helper.succeed();
	}

	@GameTest
	public void buildDistanceIsMeasuredToTheNearestColumn(GameTestHelper helper) {
		Bounds bounds = new Bounds(0, 0, 0, 9, 5, 9);
		Transform transform = new Transform(100, 64, 100, Rotation.NONE);
		helper.assertValueEqual(BuildPlanner.horizontalDistance(bounds, transform, 95, 105), 5, "west of the footprint");
		helper.assertValueEqual(BuildPlanner.horizontalDistance(bounds, transform, 104, 104), 0, "inside the footprint");
		helper.assertValueEqual(BuildPlanner.horizontalDistance(bounds, transform, 112, 113), 5, "diagonal 3-4-5");
		Transform turned = new Transform(0, 64, 0, Rotation.CLOCKWISE_180);
		helper.assertValueEqual(BuildPlanner.horizontalDistance(bounds, turned, -9, -9), 0, "rotated footprint");
		helper.succeed();
	}

	@GameTest
	public void undoSkipsChangedPositionsAndKeepsDrops(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Blueprint blueprint = new Blueprint(1_000, 64);
		blueprint.set(0, 0, 0, "cobblestone");
		blueprint.set(1, 0, 0, "cobblestone");
		blueprint.set(2, 0, 0, "chest[facing=south]");
		blueprint.set(4, 0, 0, "oak_door[facing=south]");
		Transform transform = transformAt(helper, new BlockPos(1, 1, 3), Rotation.NONE);
		BuildJob job = BuildPlanner.plan(level, blueprint, transform, "undo", validator(helper), null, List.of());
		job.runToCompletion(true);
		helper.assertTrue(level.getBlockState(world(transform, 4, 1, 0)).getBlock() == Blocks.OAK_DOOR, "door top built");

		// A player replaces one block and fills the chest after the build.
		level.setBlock(world(transform, 0, 0, 0), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
		if (level.getBlockEntity(world(transform, 2, 0, 0)) instanceof Container chest) {
			chest.setItem(0, new ItemStack(Items.DIAMOND));
		} else {
			helper.fail("chest block entity expected");
		}

		BuildJob undo = BuildJob.undo(job.record(), null);
		undo.runToCompletion(true);
		helper.assertTrue(level.getBlockState(world(transform, 0, 0, 0)).getBlock() == Blocks.DIRT, "replaced block kept");
		helper.assertTrue(level.getBlockState(world(transform, 1, 0, 0)).isAir(), "unchanged block reverted");
		helper.assertTrue(level.getBlockState(world(transform, 2, 0, 0)).getBlock() == Blocks.CHEST, "chest with items kept");
		helper.assertTrue(level.getBlockState(world(transform, 4, 0, 0)).isAir(), "door bottom reverted");
		helper.assertTrue(level.getBlockState(world(transform, 4, 1, 0)).isAir(), "door top reverted");
		helper.assertValueEqual(undo.skipped(), 2, "skipped positions (dirt and the filled chest)");
		helper.assertItemEntityNotPresent(Items.OAK_DOOR);
		helper.succeed();
	}

	@GameTest
	public void companionHalvesReplaceScriptAir(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Blueprint blueprint = new Blueprint(1_000, 64);
		for (int x = 0; x <= 4; x++) {
			for (int y = 0; y <= 2; y++) {
				blueprint.set(x, y, 0, "air");
				blueprint.set(x, y, 1, "air");
			}
		}
		blueprint.set(0, 0, 1, "oak_door[facing=south]");
		blueprint.set(3, 0, 1, "red_bed[facing=north]");
		Transform transform = transformAt(helper, new BlockPos(1, 1, 3), Rotation.NONE);
		BuildJob job = BuildPlanner.plan(level, blueprint, transform, "clear-then-door", validator(helper), null, List.of());
		job.runToCompletion(true);
		BlockState top = level.getBlockState(world(transform, 0, 1, 1));
		helper.assertTrue(top.getBlock() == Blocks.OAK_DOOR, "upper door half added over the script's air");
		helper.assertValueEqual(top.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF), DoubleBlockHalf.UPPER, "door half");
		BlockState head = level.getBlockState(world(transform, 3, 0, 0));
		helper.assertTrue(head.getBlock() instanceof BedBlock, "bed head added over the script's air");
		helper.assertValueEqual(head.getValue(BlockStateProperties.BED_PART), BedPart.HEAD, "bed part");
		helper.succeed();
	}

	@GameTest
	public void doorPlacedInAWallGetsItsUpperHalf(GameTestHelper helper) {
		// The script pattern walls(...) then block(x, 0, z, door): the blueprint holds a wall block above the door.
		ServerLevel level = helper.getLevel();
		Blueprint blueprint = new Blueprint(1_000, 64);
		for (int x = 0; x <= 4; x++) {
			for (int y = 0; y <= 2; y++) {
				blueprint.set(x, y, 0, "minecraft:cobblestone");
			}
		}
		blueprint.set(2, 0, 0, "minecraft:oak_door[facing=south]");
		// A complete door written by the script is left exactly as written.
		blueprint.set(5, 0, 0, "minecraft:spruce_door[facing=south,half=lower]");
		blueprint.set(5, 1, 0, "minecraft:spruce_door[facing=south,half=upper]");
		Transform transform = transformAt(helper, new BlockPos(1, 1, 3), Rotation.NONE);
		BuildJob job = BuildPlanner.plan(level, blueprint, transform, "wall-door", validator(helper), null, List.of());
		helper.assertValueEqual(job.total(), 17, "one entry per position (the door top replaces the wall block)");
		job.runToCompletion(true);

		BlockState top = level.getBlockState(world(transform, 2, 1, 0));
		helper.assertTrue(top.getBlock() == Blocks.OAK_DOOR, "upper door half replaces the wall block above the door");
		helper.assertValueEqual(top.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF), DoubleBlockHalf.UPPER, "door half");
		helper.assertValueEqual(level.getBlockState(world(transform, 2, 0, 0)).getValue(BlockStateProperties.DOUBLE_BLOCK_HALF),
				DoubleBlockHalf.LOWER, "lower door half");
		helper.assertTrue(level.getBlockState(world(transform, 2, 2, 0)).getBlock() == Blocks.COBBLESTONE, "lintel kept");
		helper.assertTrue(level.getBlockState(world(transform, 1, 1, 0)).getBlock() == Blocks.COBBLESTONE, "wall kept");
		helper.assertValueEqual(level.getBlockState(world(transform, 5, 1, 0)).getValue(BlockStateProperties.DOUBLE_BLOCK_HALF),
				DoubleBlockHalf.UPPER, "explicit upper half kept");
		helper.succeed();
	}

	private static McBlockValidator validator(GameTestHelper helper) {
		McBlockValidator validator = new McBlockValidator(helper.getLevel().holderLookup(Registries.BLOCK));
		validator.setDeniedBlocks(ModConfig.DEFAULT_DENIED_BLOCKS);
		return validator;
	}

	private static Transform transformAt(GameTestHelper helper, BlockPos relative, Rotation rotation) {
		BlockPos origin = helper.absolutePos(relative);
		return new Transform(origin.getX(), origin.getY(), origin.getZ(), rotation);
	}

	private static BlockPos world(Transform transform, int x, int y, int z) {
		return new BlockPos(transform.worldX(x, z), transform.worldY(y), transform.worldZ(x, z));
	}
}
