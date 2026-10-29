package io.github.saocsb.mcai.core.blueprint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockIdsTest {
	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			" Stone | minecraft:stone",
			"stone | minecraft:stone",
			"minecraft:stone | minecraft:stone",
			"MINECRAFT:Oak_Planks | minecraft:oak_planks",
			"mymod:Custom_Block | mymod:custom_block",
			"mymod:sub/path.v2-x | mymod:sub/path.v2-x",
			":stone | minecraft:stone",
			"oak_stairs[ facing = north ] | minecraft:oak_stairs[facing=north]",
			"oak_stairs[facing=north, half=top] | minecraft:oak_stairs[facing=north,half=top]",
			"oak_stairs [facing=north] | minecraft:oak_stairs[facing=north]",
			"stone[] | minecraft:stone",
			"stone [ ] | minecraft:stone",
			"chest {Lock:1b} | minecraft:chest{Lock:1b}",
	})
	void normalizesIdAndState(String raw, String expected) {
		assertEquals(expected, BlockIds.normalize(raw));
	}

	@Test
	void trimsAndRemovesAnyWhitespaceInState() {
		assertEquals("minecraft:oak_stairs[facing=north,half=top]", BlockIds.normalize("\n Oak_Stairs[\tfacing=north ,\nhalf=top\t]\r\n"));
	}

	@Test
	void keepsNbtVerbatim() {
		assertEquals("minecraft:chest[facing=south]{Items:[{Slot:0b, id:\"minecraft:apple\", count:1}]}",
				BlockIds.normalize(" Chest[ facing = south ]{Items:[{Slot:0b, id:\"minecraft:apple\", count:1}]} "));
		assertEquals("minecraft:oak_sign[rotation=4]{front_text:{messages:['\"Hello World\"','\"\"','\"\"','\"\"']}}",
				BlockIds.normalize("oak_sign[rotation=4] {front_text:{messages:['\"Hello World\"','\"\"','\"\"','\"\"']}}"));
		// Brackets inside quoted NBT strings do not count.
		assertEquals("minecraft:chest{CustomName:'\"[}{]\"'}", BlockIds.normalize("chest{CustomName:'\"[}{]\"'}"));
		assertEquals("minecraft:chest{CustomName:\"a \\\" ]\"}", BlockIds.normalize("chest{CustomName:\"a \\\" ]\"}"));
	}

	@Test
	void keepsWhitespaceInsideQuotedStateValues() {
		assertEquals("minecraft:diamond_sword[custom_name=\"My Sword\",enchantments={sharpness:5}]",
				BlockIds.normalize("diamond_sword[ custom_name=\"My Sword\", enchantments={sharpness: 5} ]"));
	}

	@Test
	void normalizeIsIdempotent() {
		String[] samples = {"stone", "oak_stairs[ facing = north ]", "chest[facing=south]{Items:[{Slot:0b}]}", "mymod:A"};
		for (String sample : samples) {
			String once = BlockIds.normalize(sample);
			assertEquals(once, BlockIds.normalize(once));
		}
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {
			"   ", "\t\n",
			"#minecraft:logs", "/setblock ~ ~ ~ stone", " #logs",
			"stone[", "stone]", "stone[facing=north", "stone[a=b]]", "stone[a=b}",
			"stone{a:1", "stone{a:1}}", "stone{a:[1}", "stone{a:\"unterminated}",
			"stone[a=b]x", "stone{a:1}x", "stone{a:1}[facing=north]", "stone[a=b][c=d]",
			"[facing=north]", "{a:1}", "minecraft:", "oak planks", "a:b:c", "stone!", "\"stone\"", "st one[a=b]",
	})
	void rejectsMalformed(String raw) {
		assertThrows(IllegalArgumentException.class, () -> BlockIds.normalize(raw));
	}

	@Test
	void idsLongerThan4096CharactersAreRejected() {
		String longest = "mymod:" + "a".repeat(BlockIds.MAX_LENGTH - 6);
		assertEquals(4096, BlockIds.MAX_LENGTH);
		assertEquals(longest, BlockIds.normalize(longest));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> BlockIds.normalize(longest + "a"));
		assertEquals("block id is too long (4097 characters, limit 4096)", e.getMessage());
		assertThrows(IllegalArgumentException.class, () -> BlockIds.normalize("stone{a:\"" + "x".repeat(100_000) + "\"}"));
		// without a namespace the input fits but the normalized id (with "minecraft:") would not
		String path = "a".repeat(BlockIds.MAX_LENGTH - 5);
		e = assertThrows(IllegalArgumentException.class, () -> BlockIds.normalize(path));
		assertEquals("block id is too long (4101 characters, limit 4096)", e.getMessage());
		// so every normalized id can be normalized again
		String fits = "a".repeat(BlockIds.MAX_LENGTH - 10);
		assertEquals(BlockIds.normalize(fits), BlockIds.normalize(BlockIds.normalize(fits)));
		assertFalse(BlockIds.isAir("air" + " ".repeat(5000)));
	}

	@Test
	void malformedMessageNamesTheInput() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> BlockIds.normalize("stone[facing=north"));
		assertTrue(e.getMessage().contains("stone[facing=north"), e.getMessage());
	}

	@ParameterizedTest
	@ValueSource(strings = {"air", "AIR", " Air ", "minecraft:air", "Minecraft:Air", "cave_air", "minecraft:cave_air",
			"void_air", "minecraft:void_air", "VOID_AIR", "air[]", "minecraft:air{}"})
	void recognizesAir(String id) {
		assertTrue(BlockIds.isAir(id), id);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"stone", "minecraft:stone", "airship", "minecraft:air_block", "stairs", "oak_stairs",
			"mymod:air", "air[", "#air", "fair", "chair"})
	void rejectsNonAir(String id) {
		assertFalse(BlockIds.isAir(id), id);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"minecraft:chest[facing=south]{Items:[]} | minecraft:chest",
			"minecraft:stone | minecraft:stone",
			"minecraft:chest{Lock:1b} | minecraft:chest",
			"mymod:thing[a=b] | mymod:thing",
			"stone | minecraft:stone",
	})
	void baseIdStripsStateAndNbt(String normalized, String expected) {
		assertEquals(expected, BlockIds.baseId(normalized));
	}
}
