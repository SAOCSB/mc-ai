package io.github.saocsb.mcai.core.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestTest {
	private static final List<String> BLOCKS = new ArrayList<>();

	static {
		for (String path : List.of(
				"air", "stone", "granite", "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite",
				"grass_block", "dirt", "coarse_dirt", "cobblestone", "oak_planks", "spruce_planks", "birch_planks",
				"jungle_planks", "acacia_planks", "dark_oak_planks", "cherry_planks", "bamboo_planks", "oak_log", "spruce_log",
				"oak_stairs", "dark_oak_stairs", "stone_stairs", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks",
				"stone_brick_stairs", "stone_brick_wall", "bricks", "brick_stairs", "sand", "red_sand", "gravel", "glass",
				"glass_pane", "white_wool", "red_wool", "quartz_block", "smooth_quartz", "quartz_pillar", "calcite",
				"gold_block", "iron_block", "diamond_block", "emerald_block", "lapis_block", "redstone_block", "netherrack",
				"obsidian", "glowstone", "sea_lantern", "lantern", "torch", "wall_torch", "candle", "cake", "barrel", "chest",
				"crafting_table", "furnace", "bookshelf", "ladder", "rail", "water", "lava", "ice", "packed_ice", "snow_block",
				"terracotta", "white_concrete", "black_concrete", "purpur_block", "end_stone", "prismarine", "sponge",
				"oak_fence", "oak_door", "iron_bars", "hay_block", "pumpkin", "melon", "tnt", "beacon", "anvil", "bell")) {
			BLOCKS.add("minecraft:" + path);
		}
	}

	@Test
	void levenshteinDistances() {
		assertEquals(0, Suggest.levenshtein("", ""));
		assertEquals(3, Suggest.levenshtein("abc", ""));
		assertEquals(3, Suggest.levenshtein("", "abc"));
		assertEquals(3, Suggest.levenshtein("kitten", "sitting"));
		assertEquals(3, Suggest.levenshtein("sitting", "kitten"));
		assertEquals(1, Suggest.levenshtein("oak_plank", "oak_planks"));
		assertEquals(2, Suggest.levenshtein("flaw", "lawn"));
		assertEquals(0, Suggest.levenshtein("same", "same"));
	}

	@Test
	void missingPluralSuggestsTheRealBlockFirst() {
		List<String> result = Suggest.closest("oak_plank", BLOCKS, 5);
		assertEquals("minecraft:oak_planks", result.get(0));
		assertTrue(result.contains("minecraft:dark_oak_planks"));
		assertEquals("minecraft:stone_bricks", Suggest.closest("stone_brick", BLOCKS, 3).get(0));
	}

	@Test
	void exactMatchIgnoresCaseAndNamespace() {
		assertEquals("minecraft:stone", Suggest.closest("MINECRAFT:Stone", BLOCKS, 3).get(0));
		assertEquals("minecraft:stone", Suggest.closest("stone", BLOCKS, 3).get(0));
		assertEquals("minecraft:glass", Suggest.closest(" Glass ", BLOCKS, 1).get(0));
		assertEquals("minecraft:oak_stairs", Suggest.closest("oak_stairs[facing=north]", BLOCKS, 1).get(0));
		assertEquals(List.of("stone"), Suggest.closest("minecraft:stone", List.of("stone", "STONE_X"), 1));
	}

	@Test
	void typosAreRankedByDistance() {
		assertEquals("minecraft:diamond_block", Suggest.closest("diamon_block", BLOCKS, 3).get(0));
		assertEquals("minecraft:glowstone", Suggest.closest("glowston", BLOCKS, 3).get(0));
		assertEquals("minecraft:cobblestone", Suggest.closest("cobble_stone", BLOCKS, 3).get(0));
		assertEquals("minecraft:obsidian", Suggest.closest("obsidan", BLOCKS, 3).get(0));
	}

	@Test
	void unrelatedWordsFindNothingAbsurd() {
		List<String> marble = Suggest.closest("marble", BLOCKS, 5);
		for (String s : marble) {
			String path = s.substring("minecraft:".length());
			assertTrue(Suggest.levenshtein("marble", path) <= 3 || path.contains("marble"), s);
		}
		assertFalse(marble.contains("minecraft:air"));
		assertFalse(marble.contains("minecraft:stone"));
		assertEquals(List.of(), Suggest.closest("xyzzy_qwerty", BLOCKS, 5));
		assertEquals(List.of(), Suggest.closest("skyscraper", BLOCKS, 5));
	}

	@Test
	void shortCandidatesInsideTheInputAreNotSuggested() {
		// "oak_stair" contains "air", but air is no help here.
		List<String> result = Suggest.closest("oak_stair", BLOCKS, 10);
		assertEquals("minecraft:oak_stairs", result.get(0));
		assertFalse(result.contains("minecraft:air"));
		// A long enough candidate inside the input still counts.
		assertEquals("minecraft:stone_bricks", Suggest.closest("stone_bricks_block", BLOCKS, 1).get(0));
	}

	@Test
	void respectsMaxAndEdgeCases() {
		assertEquals(2, Suggest.closest("planks", BLOCKS, 2).size());
		assertEquals(List.of(), Suggest.closest("stone", BLOCKS, 0));
		assertEquals(List.of(), Suggest.closest("", BLOCKS, 5));
		assertEquals(List.of(), Suggest.closest(null, BLOCKS, 5));
		assertEquals(List.of(), Suggest.closest("stone", List.of(), 5));
		List<String> withDuplicates = new ArrayList<>(List.of("minecraft:stone", "minecraft:stone", "minecraft:stones"));
		assertEquals(List.of("minecraft:stone", "minecraft:stones"), Suggest.closest("stone", withDuplicates, 5));
	}

	@Test
	void containmentRanksAboveCloserTypos() {
		// "planks" is inside every *_planks id; they come before unrelated ids at a small distance.
		List<String> result = Suggest.closest("planks", BLOCKS, 20);
		assertTrue(result.size() >= 8);
		for (int i = 0; i < 8; i++) {
			assertTrue(result.get(i).endsWith("_planks"), result.toString());
		}
		// Shorter matches first within the tier.
		assertEquals("minecraft:oak_planks", result.get(0));
	}
}
