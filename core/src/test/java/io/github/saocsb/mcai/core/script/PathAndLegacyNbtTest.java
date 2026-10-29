package io.github.saocsb.mcai.core.script;

import io.github.saocsb.mcai.core.blueprint.Blueprint;
import org.junit.jupiter.api.Test;

import static io.github.saocsb.mcai.core.script.ScriptTestSupport.failure;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.log;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathAndLegacyNbtTest {
	@Test
	void pathJoinsConsecutivePointsWithoutGaps() {
		Blueprint bp = run("path([[0,0,0],[4,0,0],[4,4,0]], 'stone');").blueprint();
		for (int x = 0; x <= 4; x++) {
			assertEquals("minecraft:stone", bp.get(x, 0, 0), "x=" + x);
		}
		for (int y = 0; y <= 4; y++) {
			assertEquals("minecraft:stone", bp.get(4, y, 0), "y=" + y);
		}
		assertEquals(9, bp.size());
	}

	@Test
	void pathAcceptsObjectsAndReturnsTheBlockCountWithoutDoubleCountingJoints() {
		assertEquals("9", log("log(path([{x:0,y:0,z:0},{x:4,y:0,z:0},{x:4,y:4,z:0}], 'stone'));"));
	}

	@Test
	void pathRadiusMakesABeam() {
		Blueprint bp = run("path([[0,5,0],[10,5,0]], 'stone', 1);").blueprint();
		assertNotNull(bp.get(5, 6, 0));
		assertNotNull(bp.get(5, 5, 1));
	}

	@Test
	void pathValidatesItsArguments() {
		assertTrue(failure("path([[0,0,0]], 'stone');").getMessage().contains("at least 2 points"));
		assertTrue(failure("path([[0,0],[1,1]], 'stone');").getMessage().contains("points[0] must be [x, y, z]"));
		assertTrue(failure("path('nope', 'stone');").getMessage().contains("points must be an array"));
	}

	@Test
	void summonRejectsPre1215EquipmentNbt() {
		String msg = failure("summon('zombie', 8, 0, 0, '{ArmorItems:[{},{},{},{id:\"minecraft:iron_helmet\",Count:1b}]}');")
				.getMessage();
		assertTrue(msg.contains("ArmorItems") && msg.contains("equipment:{"), msg);
		assertTrue(failure("summon('zombie', 8, 0, 0, '{equipment:{mainhand:{id:\"minecraft:iron_sword\",Count:1b}}}');")
				.getMessage().contains("'Count'"));
	}

	@Test
	void summonAcceptsTheCurrentEquipmentFormat() {
		ScriptResult result = run("summon('zombie', 8, 0, 0, '{PersistenceRequired:1b,equipment:{head:{id:\"minecraft:iron_helmet\",count:1}}}');");
		assertEquals(1, result.actions().size());
		assertNull(ScriptSession.legacyEntityKey("{CustomName:\"Bob\",count:1}"));
	}
}
