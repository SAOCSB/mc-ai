package io.github.saocsb.mcai.core.blueprint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockFixerTest {
	private static List<String> fix(String id) {
		return BlockFixer.candidates(id);
	}

	// --- Alias map ---

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"stone_brick | stone_bricks",
			"stone_bricks_stairs | stone_brick_stairs",
			"stone_bricks_slab | stone_brick_slab",
			"brick | bricks",
			"brick_block | bricks",
			"bricks_stairs | brick_stairs",
			"quartz_block_stairs | quartz_stairs",
			"deepslate_stairs | cobbled_deepslate_stairs",
			"deepslate_wall | cobbled_deepslate_wall",
			"smooth_stone_stairs | stone_stairs",
			"grass | short_grass",
			"grass_path | dirt_path",
			"glass_panes | glass_pane",
			"white_glass_pane | white_stained_glass_pane",
			"glass_block | glass",
			"stained_glass | white_stained_glass",
			"stained_glass_pane | white_stained_glass_pane",
			"concrete | white_concrete",
			"concrete_powder | white_concrete_powder",
			"wool | white_wool",
			"carpet | white_carpet",
			"planks | oak_planks",
			"log | oak_log",
			"leaves | oak_leaves",
			"copper | copper_block",
			"iron_fence | iron_bars",
			"cobblestone_fence | cobblestone_wall",
			"stone_fence | stone_brick_wall",
			"polished_stone | smooth_stone",
			"marble | smooth_quartz",
			"hanging_lantern | lantern[hanging=true]",
			"wall_lantern | lantern",
			"chain | iron_chain",
	})
	void everyAliasIsTheFirstCandidate(String from, String to) {
		assertEquals(to, BlockFixer.ALIASES.get(from));
		assertEquals("minecraft:" + to, fix("minecraft:" + from).get(0));
		assertEquals("minecraft:" + to, fix(from).get(0), "unnormalized input");
	}

	@Test
	void aliasMapSizeMatchesTheTestedEntries() {
		// Keep in sync with everyAliasIsTheFirstCandidate.
		assertEquals(33, BlockFixer.ALIASES.size());
		for (String key : BlockFixer.ALIASES.keySet()) {
			assertEquals(key, BlockIds.normalize(key).substring("minecraft:".length()), "alias key must be a plain path");
		}
	}

	@Test
	void aliasesApplyToTheMinecraftNamespaceOnly() {
		assertEquals(List.of(), fix("mymod:marble"));
		assertEquals(List.of(), fix("mymod:grass_path"));
		// Structural rules still apply in any namespace and keep it.
		assertEquals(List.of("mymod:stone_bricks"), fix("mymod:stone_brick"));
	}

	@Test
	void aliasWithItsOwnStateReplacesTheOriginalState() {
		assertEquals(List.of("minecraft:lantern[hanging=true]"), fix("minecraft:hanging_lantern"));
		assertEquals(List.of("minecraft:lantern[hanging=true]", "minecraft:hanging_lantern"),
				fix("minecraft:hanging_lantern[waterlogged=true]"));
	}

	@Test
	void aliasWithoutStateKeepsTheOriginalState() {
		assertEquals(List.of("minecraft:lantern[facing=north]", "minecraft:lantern", "minecraft:wall_lantern"),
				fix("minecraft:wall_lantern[facing=north]"));
		assertEquals(List.of("minecraft:iron_chain[axis=y]", "minecraft:iron_chain", "minecraft:chain"),
				fix("minecraft:chain[axis=y]"));
	}

	// --- Structural rules ---

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"oak_planks_stairs | oak_stairs",
			"oak_planks_slab | oak_slab",
			"spruce_planks_fence | spruce_fence",
			"dark_oak_planks_fence_gate | dark_oak_fence_gate",
			"birch_planks_door | birch_door",
			"jungle_planks_trapdoor | jungle_trapdoor",
			"acacia_planks_button | acacia_button",
			"cherry_planks_pressure_plate | cherry_pressure_plate",
			"pale_oak_plank_stairs | pale_oak_stairs",
	})
	void planksShapeRule(String from, String to) {
		assertEquals(List.of("minecraft:" + to), fix("minecraft:" + from));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			// <x>_brick <-> <x>_bricks
			"nether_brick | nether_bricks",
			"mud_brick | mud_bricks",
			"end_stone_brick | end_stone_bricks",
			"fancy_bricks | fancy_brick",
			// <x>_bricks_<shape> -> <x>_brick_<shape>
			"stone_bricks_wall | stone_brick_wall",
			"end_stone_bricks_slab | end_stone_brick_slab",
			"mossy_stone_bricks_stairs | mossy_stone_brick_stairs",
			"nether_bricks_fence | nether_brick_fence",
			"bricks_wall | brick_wall",
			"bricks_slab | brick_slab",
			// <x>_plank -> <x>_planks
			"oak_plank | oak_planks",
			"dark_oak_plank | dark_oak_planks",
			// <x>_tiles_<shape> -> <x>_tile_<shape>, <x>_tile -> <x>_tiles
			"deepslate_tiles_stairs | deepslate_tile_stairs",
			"deepslate_tiles_slab | deepslate_tile_slab",
			"deepslate_tiles_wall | deepslate_tile_wall",
			"deepslate_tile | deepslate_tiles",
			// <x>_block_<shape> -> <x>_<shape>
			"purpur_block_stairs | purpur_stairs",
			"quartz_block_slab | quartz_slab",
			// colour glass without "stained"
			"red_glass | red_stained_glass",
			"light_blue_glass_pane | light_blue_stained_glass_pane",
			// other singular / plural slips
			"oak_stair | oak_stairs",
			"oak_slabs | oak_slab",
			"cobblestone_walls | cobblestone_wall",
			"oak_fence_gates | oak_fence_gate",
			"spruce_logs | spruce_log",
	})
	void singularPluralAndShapeRules(String from, String to) {
		assertEquals(List.of("minecraft:" + to), fix("minecraft:" + from));
	}

	@Test
	void rulesKeepTheStateAndAddStatelessVariantsBeforeTheStrippedInput() {
		assertEquals(List.of("minecraft:spruce_stairs[facing=east,half=top]", "minecraft:spruce_stairs",
				"minecraft:spruce_planks_stairs"), fix("minecraft:spruce_planks_stairs[facing=east,half=top]"));
		assertEquals(List.of("minecraft:stone_bricks[facing=north]", "minecraft:stone_bricks", "minecraft:stone_brick"),
				fix("minecraft:stone_brick[facing=north]"));
	}

	@Test
	void nbtIsAlwaysKept() {
		assertEquals(List.of("minecraft:oak_planks{Foo:1b}"), fix("minecraft:planks{Foo:1b}"));
		assertEquals(List.of("minecraft:oak_log[facing=up]{A:\"x]\"}", "minecraft:oak_log{A:\"x]\"}",
				"minecraft:log{A:\"x]\"}"), fix("minecraft:log[facing=up]{A:\"x]\"}"));
	}

	@Test
	void quotedStateValuesAreParsed() {
		assertEquals(List.of("minecraft:oak_log[name=\"a]b\"]", "minecraft:oak_log", "minecraft:log"),
				fix("minecraft:log[name=\"a]b\"]"));
	}

	// --- Last resort: strip the state ---

	@Test
	void stateIsStrippedAsTheLastCandidate() {
		assertEquals(List.of("minecraft:oak_log"), fix("minecraft:oak_log[facing=north]"));
		assertEquals(List.of("minecraft:oak_stairs{a:1}"), fix("minecraft:oak_stairs[facing=up]{a:1}"));
		assertEquals(List.of("mymod:thing"), fix("mymod:thing[bad=1]"));
		List<String> lantern = fix("minecraft:hanging_lantern[waterlogged=true]");
		assertEquals("minecraft:hanging_lantern", lantern.get(lantern.size() - 1));
	}

	@Test
	void nothingIsStrippedWithoutAState() {
		assertEquals(List.of(), fix("minecraft:oak_log"));
		assertEquals(List.of(), fix("minecraft:fancy_pillar"));
		assertEquals(List.of(), fix("minecraft:chest{Lock:1b}"));
	}

	// --- General guarantees ---

	@Test
	void duplicatesAreRemoved() {
		// alias and the <x>_block_<shape> rule agree
		assertEquals(List.of("minecraft:quartz_stairs"), fix("minecraft:quartz_block_stairs"));
		// alias and the <x>_bricks_<shape> rule agree
		assertEquals(List.of("minecraft:stone_brick_stairs"), fix("minecraft:stone_bricks_stairs"));
		// alias and the plural rule agree
		assertEquals(List.of("minecraft:glass_pane"), fix("minecraft:glass_panes"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"minecraft:stone_brick", "minecraft:stone_brick[facing=north]", "minecraft:hanging_lantern[waterlogged=true]",
			"minecraft:oak_planks_stairs[facing=east,half=top]", "minecraft:log[facing=up]{A:1}", "minecraft:red_glass_pane",
			"minecraft:oak_log[axis=q]", "mymod:x_bricks", "minecraft:chain[axis=y]", "minecraft:glass_panes[north=true]",
	})
	void candidatesAreNormalizedDistinctAndNeverTheInput(String input) {
		List<String> candidates = fix(input);
		assertFalse(candidates.isEmpty());
		assertFalse(candidates.contains(input));
		assertEquals(candidates.size(), new HashSet<>(candidates).size());
		for (String c : candidates) {
			assertEquals(BlockIds.normalize(c), c);
		}
	}

	@Test
	void unnormalizedInputIsNormalizedFirst() {
		assertEquals(List.of("minecraft:stone_bricks[facing=north]", "minecraft:stone_bricks", "minecraft:stone_brick"),
				fix(" Stone_Brick[ facing = north ] "));
	}

	@Test
	void malformedInputGivesNoCandidates() {
		assertEquals(List.of(), fix(null));
		assertEquals(List.of(), fix(""));
		assertEquals(List.of(), fix("oak_log[facing=north"));
		assertEquals(List.of(), fix("#minecraft:logs"));
	}

	// --- Fuzzy suggestions ---

	@Test
	void sameFamilySuggestionWithinDistanceKeepsTheState() {
		assertEquals(List.of("minecraft:oak_stairs[facing=north]", "minecraft:oak_stairs"),
				BlockFixer.suggestionCandidates("minecraft:oak_stair[facing=north]", List.of("minecraft:oak_stairs")));
		assertEquals(List.of("minecraft:birch_stairs"),
				BlockFixer.suggestionCandidates("minecraft:brich_stairs", List.of("minecraft:birch_stairs")));
		assertEquals(List.of("minecraft:white_stained_glass"),
				BlockFixer.suggestionCandidates("minecraft:white_staned_glass", List.of("white_stained_glass")));
	}

	@Test
	void suggestionDistanceLimitIsTwo() {
		assertEquals(List.of("minecraft:birch_stairs"),
				BlockFixer.suggestionCandidates("minecraft:bxxch_stairs", List.of("minecraft:birch_stairs")));
		assertEquals(List.of(),
				BlockFixer.suggestionCandidates("minecraft:bxxxh_stairs", List.of("minecraft:birch_stairs")));
		assertEquals(List.of(),
				BlockFixer.suggestionCandidates("minecraft:crystal_stairs", List.of("minecraft:cut_copper_stairs")));
	}

	@Test
	void suggestionsFromAnotherFamilyAreNotUsed() {
		assertEquals(List.of(), BlockFixer.suggestionCandidates("minecraft:birch_wool", List.of("minecraft:birch_wood")));
		assertEquals(List.of(), BlockFixer.suggestionCandidates("minecraft:oak_slab", List.of("minecraft:oak_stairs")));
		assertEquals(List.of(), BlockFixer.suggestionCandidates("minecraft:oak_wood", List.of("minecraft:oak_door")));
		// No family suffix at all.
		assertEquals(List.of(), BlockFixer.suggestionCandidates("minecraft:glas", List.of("minecraft:glass")));
		assertEquals(List.of(), BlockFixer.suggestionCandidates("minecraft:stonee", List.of("minecraft:stone")));
	}

	@Test
	void singularAndPluralFormsOfTheFamilyMatch() {
		assertEquals(List.of("minecraft:oak_slab"),
				BlockFixer.suggestionCandidates("minecraft:oak_slabs", List.of("minecraft:oak_slab")));
		assertEquals(List.of("minecraft:oak_leaves[persistent=true]", "minecraft:oak_leaves"),
				BlockFixer.suggestionCandidates("minecraft:oak_leave[persistent=true]", List.of("minecraft:oak_leaves")));
		assertTrue(BlockFixer.sameFamily("stone_brick", "stone_bricks"));
		assertTrue(BlockFixer.sameFamily("oak_fences", "oak_fence"));
		assertFalse(BlockFixer.sameFamily("oak_fence", "oak_fence_gate"));
	}

	@Test
	void familyOfEverySuffix() {
		for (String suffix : BlockFixer.FAMILY_SUFFIXES) {
			assertEquals(suffix, BlockFixer.family("oak" + suffix));
		}
		assertEquals("_fence_gate", BlockFixer.family("oak_fence_gate"));
		assertEquals("_trapdoor", BlockFixer.family("oak_trapdoor"));
		assertNull(BlockFixer.family("glass"));
		assertNull(BlockFixer.family("_stairs"));
		assertNull(BlockFixer.family("stone"));
	}

	@Test
	void suggestionsInAnotherNamespaceAreNotUsed() {
		assertEquals(List.of(), BlockFixer.suggestionCandidates("minecraft:oak_stair", List.of("othermod:oak_stairs")));
		assertEquals(List.of("othermod:oak_stairs"),
				BlockFixer.suggestionCandidates("othermod:oak_stair", List.of("othermod:oak_stairs")));
	}

	@Test
	void suggestionOrderIsKeptAndStatelessVariantsComeLast() {
		assertEquals(List.of("minecraft:oak_stairs[half=top]", "minecraft:ok_stairs[half=top]", "minecraft:oak_stairs",
						"minecraft:ok_stairs"),
				BlockFixer.suggestionCandidates("minecraft:oka_stairs[half=top]",
						List.of("minecraft:dark_oak_stairs", "minecraft:oak_stairs", "minecraft:ok_stairs")));
		// The invalid path itself must end in a family suffix.
		assertEquals(List.of(), BlockFixer.suggestionCandidates("minecraft:oak_stairz", List.of("minecraft:oak_stairs")));
	}

	@Test
	void suggestionEqualToTheInputYieldsOnlyTheStatelessVariant() {
		assertEquals(List.of("minecraft:oak_stairs"),
				BlockFixer.suggestionCandidates("minecraft:oak_stairs[facing=up]", List.of("minecraft:oak_stairs")));
		assertEquals(List.of(), BlockFixer.suggestionCandidates("minecraft:oak_stairs", List.of("minecraft:oak_stairs")));
	}

	@Test
	void suggestionStateIsIgnoredAndTheInputStateUsed() {
		assertEquals(List.of("minecraft:oak_stairs[half=top]", "minecraft:oak_stairs"),
				BlockFixer.suggestionCandidates("minecraft:oak_stair[half=top]", List.of("oak_stairs[half=bottom]")));
	}

	@Test
	void badSuggestionInputsAreIgnored() {
		assertEquals(List.of(), BlockFixer.suggestionCandidates("minecraft:oak_stair", null));
		assertEquals(List.of(), BlockFixer.suggestionCandidates("minecraft:oak_stair", List.of()));
		assertEquals(List.of(), BlockFixer.suggestionCandidates(null, List.of("minecraft:oak_stairs")));
		List<String> mixed = new ArrayList<>(Arrays.asList(null, "#bad", "oak[", "minecraft:oak_stairs"));
		assertEquals(List.of("minecraft:oak_stairs"), BlockFixer.suggestionCandidates("minecraft:oak_stair", mixed));
	}
}
