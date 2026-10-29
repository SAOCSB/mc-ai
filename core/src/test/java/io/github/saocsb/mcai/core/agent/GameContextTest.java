package io.github.saocsb.mcai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameContextTest {
	private static GameContext context(long dayTime, List<String> others) {
		return new GameContext("Steve", "minecraft:overworld", "minecraft:plains", 120, 64, -30, "north", dayTime, "clear",
				"survival", "minecraft:diamond_sword", 64, -64, 320, 300000, others);
	}

	@Test
	void rendersTheDocumentedExample() {
		assertEquals("""
				[game context]
				player: Steve (survival) at x=120 y=64 z=-30, facing north, holding minecraft:diamond_sword
				world: minecraft:overworld, biome minecraft:plains, time 6000 (noon), weather clear
				build space: local y=0 is world y=64; you can use local y from -128 to 256; max 300000 blocks
				other players online: Alex, Bob
				[/game context]""", context(6000, List.of("Alex", "Bob")).render());
	}

	@Test
	void otherPlayersLineOmittedWhenNone() {
		String empty = context(6000, List.of()).render();
		assertFalse(empty.contains("other players"));
		assertTrue(empty.endsWith("max 300000 blocks\n[/game context]"));
		assertFalse(context(6000, null).render().contains("other players"));
	}

	@Test
	void buildSpaceLineUsesLocalOffsets() {
		GameContext c = new GameContext("Alex", "minecraft:the_nether", "minecraft:nether_wastes", 0, 70, 0, "east", 18000,
				"clear", "creative", "minecraft:air", 70, 0, 127, 5000, List.of());
		String rendered = c.render();
		assertTrue(rendered.contains("player: Alex (creative) at x=0 y=70 z=0, facing east, holding minecraft:air\n"));
		assertTrue(rendered.contains("build space: local y=0 is world y=70; you can use local y from -70 to 57; max 5000 blocks\n"));
		assertTrue(rendered.contains("time 18000 (midnight)"));
	}

	@ParameterizedTest
	@CsvSource({
			"0, 0, dawn", "999, 999, dawn", "1000, 1000, morning", "4999, 4999, morning", "5000, 5000, noon",
			"6999, 6999, noon", "7000, 7000, afternoon", "10999, 10999, afternoon", "11000, 11000, sunset",
			"12999, 12999, sunset", "13000, 13000, night", "16999, 16999, night", "17000, 17000, midnight",
			"18999, 18999, midnight", "19000, 19000, night", "22999, 22999, night", "23000, 23000, dawn",
			"23999, 23999, dawn", "30000, 6000, noon", "-1000, 23000, dawn"})
	void timeLabels(long dayTime, long shown, String label) {
		assertTrue(context(dayTime, List.of()).render().contains("time " + shown + " (" + label + "), weather clear"));
	}
}
