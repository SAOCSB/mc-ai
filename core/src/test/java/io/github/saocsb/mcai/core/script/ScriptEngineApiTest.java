package io.github.saocsb.mcai.core.script;

import io.github.saocsb.mcai.core.blueprint.Blueprint;
import io.github.saocsb.mcai.core.blueprint.Noise;
import io.github.saocsb.mcai.core.blueprint.PixelFont;
import io.github.saocsb.mcai.core.blueprint.Shapes;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static io.github.saocsb.mcai.core.script.ScriptTestSupport.entries;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.failure;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.log;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.logs;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ScriptEngineApiTest {
	/** Runs the script and checks that it built exactly what {@code expected} builds with Shapes directly. */
	private static void assertBuilds(String script, Consumer<Blueprint> expected) {
		Blueprint want = new Blueprint(300_000, 384);
		expected.accept(want);
		Blueprint got = run(script).blueprint();
		assertEquals(entries(want), entries(got), script);
	}

	private static void assertTypeError(String script, String message) {
		ScriptException e = failure(ScriptException.Kind.RUNTIME, script);
		assertTrue(e.getMessage().startsWith("TypeError: " + message + " (line "), e.getMessage());
	}

	@Test
	void everyApiFunctionExists() {
		StringBuilder js = new StringBuilder("const missing = [];\n");
		for (String fn : ScriptEngine.API_FUNCTIONS) {
			js.append("if (typeof ").append(fn).append(" !== 'function') missing.push('").append(fn).append("');\n");
		}
		js.append("for (const m of ['log', 'info', 'warn', 'error']) if (typeof console[m] !== 'function') missing.push('console.' + m);\n");
		js.append("log(missing.length === 0 ? 'ok' : missing.join());");
		assertEquals("ok", log(js.toString()));
	}

	/** The API section of the prompt documents every function (print is an alias of log). */
	@Test
	void everyApiFunctionIsInThePrompt() throws IOException {
		String prompt;
		try (InputStream in = ScriptEngineApiTest.class.getResourceAsStream("/mcai/prompt/system.md")) {
			assumeTrue(in != null, "mcai/prompt/system.md is not on the classpath");
			prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		int start = prompt.indexOf("\n## API");
		int end = prompt.indexOf("\n## ", start + 1);
		assertTrue(start >= 0 && end > start, "no API section in the prompt");
		String api = prompt.substring(start, end);
		for (String fn : ScriptEngine.API_FUNCTIONS) {
			if (!fn.equals("print")) {
				assertTrue(Pattern.compile("\\b" + fn + "\\(").matcher(api).find(), fn + " is not in the prompt");
			}
		}
		assertTrue(ScriptEngine.API_FUNCTIONS.contains("wait"));
	}

	@Test
	void functionLengthsAreRequiredArgumentCounts() {
		assertEquals("4 7 6 5 1 0", log("log(block.length, fill.length, clear.length, sphere.length, give.length, log.length)"));
	}

	@Nested
	class Blocks {
		@Test
		void blockPlacesNormalizedIdAndReturnsUndefined() {
			ScriptResult r = run("const v = block(1, 2, 3, ' Stone '); log(String(v));");
			assertEquals(List.of("1,2,3=minecraft:stone"), entries(r.blueprint()));
			assertEquals(List.of("undefined"), r.logs());
		}

		@Test
		void blockKeepsStatesAndNbt() {
			ScriptResult r = run("block(0, 0, 0, 'oak_stairs[ facing = north ]'); block(1, 0, 0, 'chest[facing=south]{Items:[]}');");
			assertEquals(List.of("0,0,0=minecraft:oak_stairs[facing=north]", "1,0,0=minecraft:chest[facing=south]{Items:[]}"),
					entries(r.blueprint()));
		}

		@Test
		void coordinatesAreRoundedLikeMathRound() {
			ScriptResult r = run("block(1.5, -1.5, 2.4, 'stone'); block(-0.4, 0.49999, -2.5, 'dirt');");
			assertEquals(List.of("2,-1,2=minecraft:stone", "0,0,-2=minecraft:dirt"), entries(r.blueprint()));
		}

		@Test
		void laterCallsOverwrite() {
			ScriptResult r = run("block(0, 0, 0, 'stone'); fill(0, 0, 0, 1, 0, 0, 'dirt'); block(1, 0, 0, 'air');");
			assertEquals(List.of("0,0,0=minecraft:dirt", "1,0,0=minecraft:air"), entries(r.blueprint()));
		}

		@Test
		void concatenatedStringsAreAccepted() {
			ScriptResult r = run("const wood = 'oak'; block(0, 0, 0, wood + '_planks'); block(1, 0, 0, `${wood}_log[axis=y]`);");
			assertEquals(List.of("0,0,0=minecraft:oak_planks", "1,0,0=minecraft:oak_log[axis=y]"), entries(r.blueprint()));
		}

		@Test
		void blockArgumentErrors() {
			assertTypeError("block(0, 0, 0)", "block(): expected 4 arguments (x, y, z, id), got 3");
			assertTypeError("block(NaN, 0, 0, 'stone')", "block(): x must be a finite number");
			assertTypeError("block(0, Infinity, 0, 'stone')", "block(): y must be a finite number");
			assertTypeError("block(0, 0, '5', 'stone')", "block(): z must be a finite number");
			assertTypeError("block(0, 0, undefined, 'stone')", "block(): z must be a finite number");
			assertTypeError("block(0, 0, 0, '#logs')", "block(): invalid block id '#logs'");
			assertTypeError("block(0, 0, 0, 'oak_stairs[facing=north')", "block(): invalid block id 'oak_stairs[facing=north'");
			assertTypeError("block(0, 0, 0, 5)", "block(): invalid block id '5'");
			assertTypeError("block(0, 0, 0, undefined)", "block(): invalid block id 'undefined'");
			assertTypeError("block(0, 0, 0, '')", "block(): invalid block id ''");
		}

		@Test
		void extraArgumentsAreIgnored() {
			assertEquals(1, run("block(0, 0, 0, 'stone', 'extra', 7)").blueprint().size());
		}

		@Test
		void argumentErrorsCanBeCaught() {
			assertEquals("TypeError|fill(): expected 7 arguments (x1, y1, z1, x2, y2, z2, id), got 6",
					log("try { fill(0, 0, 0, 1, 1, 1) } catch (e) { log(e.name + '|' + e.message) }"));
		}

		@Test
		void fillReturnsVolumeWithCornersInAnyOrder() {
			ScriptResult r = run("log(fill(2, 3, 4, 0, 0, 0, 'stone'))");
			assertEquals(List.of("60"), r.logs());
			assertEquals(60, r.blueprint().size());
			assertTypeError("fill(0, 0, 0, 1, 1, 1)", "fill(): expected 7 arguments (x1, y1, z1, x2, y2, z2, id), got 6");
		}

		@Test
		void hollowWallsFrameClear() {
			assertEquals("98 80 44", log("log(hollow(0,0,0,4,4,4,'stone'), walls(10,0,0,14,4,4,'stone'), frame(20,0,0,24,4,4,'stone'))"));
			assertEquals("null", log("hollow(0,0,0,4,4,4,'stone'); log(get(2, 2, 2))"));
			ScriptResult r = run("fill(0,0,0,2,2,2,'stone'); log(clear(0,0,0,2,1,2)); log(get(1,1,1), get(1,2,1))");
			assertEquals(List.of("18", "air stone"), r.logs());
			assertBuilds("walls(0,0,0,3,2,5,'stone')", bp -> Shapes.walls(bp, 0, 0, 0, 3, 2, 5, "minecraft:stone"));
			assertBuilds("frame(0,0,0,3,2,5,'stone')", bp -> Shapes.frame(bp, 0, 0, 0, 3, 2, 5, "minecraft:stone"));
			assertTypeError("clear(0, 0, 0, 1, 1)", "clear(): expected 6 arguments (x1, y1, z1, x2, y2, z2), got 5");
		}

		@Test
		void line() {
			assertEquals("11", log("log(line(0, 0, 0, 10, 3, -2, 'stone'))"));
			assertBuilds("line(0, 0, 0, 10, 3, -2, 'stone')", bp -> Shapes.line(bp, 0, 0, 0, 10, 3, -2, "minecraft:stone", 0));
			assertBuilds("line(0, 0, 0, 12, 5, 0, 'stone', 1.5)", bp -> Shapes.line(bp, 0, 0, 0, 12, 5, 0, "minecraft:stone", 1.5));
			// a thick line counts each position once
			ScriptResult r = run("log(line(0, 0, 0, 20, 7, 3, 'stone', 2))");
			assertEquals(String.valueOf(r.blueprint().size()), r.logs().get(0));
			assertTypeError("line(0, 0, 0, 1, 1, 1, 'stone', 'big')", "line(): radius must be a finite number");
		}

		@Test
		void roundShapes() {
			assertBuilds("sphere(0, 10, 0, 3, 'glass')", bp -> Shapes.sphere(bp, 0, 10, 0, 3, "minecraft:glass", false));
			assertBuilds("sphere(0, 10, 0, 4.5, 'glass', true)", bp -> Shapes.sphere(bp, 0, 10, 0, 4.5, "minecraft:glass", true));
			assertBuilds("dome(1, 2, 3, 5, 'glass', 'yes')", bp -> Shapes.dome(bp, 1, 2, 3, 5, "minecraft:glass", true));
			assertBuilds("dome(1, 2, 3, 5, 'glass', 0)", bp -> Shapes.dome(bp, 1, 2, 3, 5, "minecraft:glass", false));
			assertBuilds("ellipsoid(0, 0, 0, 6, 3, 2, 'stone')", bp -> Shapes.ellipsoid(bp, 0, 0, 0, 6, 3, 2, "minecraft:stone", false));
			assertBuilds("ellipsoid(0, 0, 0, 6, 3, 2, 'stone', true)", bp -> Shapes.ellipsoid(bp, 0, 0, 0, 6, 3, 2, "minecraft:stone", true));
			assertBuilds("cylinder(0, 0, 0, 3, 5, 'stone')", bp -> Shapes.cylinder(bp, 0, 0, 0, 3, 5, "minecraft:stone", false));
			assertBuilds("cylinder(0, 0, 0, 3, 5, 'stone', true)", bp -> Shapes.cylinder(bp, 0, 0, 0, 3, 5, "minecraft:stone", true));
			assertBuilds("cone(0, 0, 0, 4, 6, 'stone')", bp -> Shapes.cone(bp, 0, 0, 0, 4, 6, "minecraft:stone", false));
			assertBuilds("pyramid(0, 0, 0, 4, 'sandstone', true)", bp -> Shapes.pyramid(bp, 0, 0, 0, 4, "minecraft:sandstone", true));
			assertBuilds("torus(0, 5, 0, 6, 1.5, 'stone')", bp -> Shapes.torus(bp, 0, 5, 0, 6, 1.5, "minecraft:stone"));
			assertEquals("1 1", log("log(sphere(0, 0, 0, 0, 'stone'), cylinder(5, 0, 5, 0, 1, 'stone'))"));
		}

		@Test
		void shapeCountsMatchBlocksWritten() {
			ScriptResult r = run("log(sphere(0,0,0,3,'stone') + cylinder(20,0,0,2,4,'stone') + pyramid(40,0,0,3,'stone') + torus(0,0,40,5,1,'stone'))");
			assertEquals(String.valueOf(r.blueprint().size()), r.logs().get(0));
		}

		@Test
		void shapeArgumentErrors() {
			assertTypeError("sphere(0, 0, 0, 3)", "sphere(): expected 5 arguments (cx, cy, cz, r, id), got 4");
			assertTypeError("cylinder(0, 0, 0, 3, -1, 'stone')", "cylinder(): height must not be negative, got -1");
			assertTypeError("pyramid(0, 0, 0, -2, 'stone')", "pyramid(): half must not be negative, got -2");
			assertTypeError("torus(0, 0, 0, 5, 'x', 'stone')", "torus(): r must be a finite number");
			assertTypeError("ellipsoid(0, 0, 0, 1, 2, 3)", "ellipsoid(): expected 7 arguments (cx, cy, cz, rx, ry, rz, id), got 6");
		}

		@Test
		void polygonAcceptsPairsAndObjects() {
			List<int[]> pts = List.of(new int[] {0, 0}, new int[] {8, 0}, new int[] {4, 6});
			assertBuilds("polygon([[0, 0], [8, 0], [4, 6]], 1, 3, 'stone')",
					bp -> Shapes.polygon(bp, pts, 1, 3, "minecraft:stone", false));
			assertBuilds("polygon([{x: 0, z: 0}, {x: 8, z: 0}, {x: 4.2, z: 5.6}], 1, 3, 'stone', true)",
					bp -> Shapes.polygon(bp, pts, 1, 3, "minecraft:stone", true));
			assertTypeError("polygon([[0, 0], [1, 1]], 0, 1, 'stone')", "polygon(): expected at least 3 points, got 2");
			assertTypeError("polygon('abc', 0, 1, 'stone')", "polygon(): points must be an array of [x, z] pairs or {x, z} objects");
			assertTypeError("polygon([[0, 0], [1, 1], 5], 0, 1, 'stone')", "polygon(): points[2] must be [x, z] or {x, z}");
			assertTypeError("polygon([[0, 0], [1, 1], [2, 'a']], 0, 1, 'stone')", "polygon(): points[2].z must be a finite number");
		}

		@Test
		void textReturnsWidth() {
			ScriptResult r = run("log(text('HI 2', 0, 0, 0, 'gold_block'))");
			assertEquals(List.of(String.valueOf(PixelFont.width("HI 2", 1))), r.logs());
			assertBuilds("text('Hi!', 1, 2, 3, 'stone', 2)", bp -> PixelFont.draw(bp, "Hi!", 1, 2, 3, "minecraft:stone", 2));
			assertBuilds("text(2024, 0, 0, 0, 'stone')", bp -> PixelFont.draw(bp, "2024", 0, 0, 0, "minecraft:stone", 1));
			assertTypeError("text('A', 0, 0, 0, 'stone', 0)", "text(): scale must be at least 1, got 0");
			assertTypeError("text(null, 0, 0, 0, 'stone')", "text(): str must be a string, got null");
		}

		@Test
		void replaceChangesOnlyMatchingBlocksInsideTheBox() {
			ScriptResult r = run("""
					fill(0, 0, 0, 4, 0, 0, 'stone');
					block(2, 0, 0, 'dirt');
					block(3, 0, 0, 'air');
					log(replace(0, 0, 0, 3, 0, 0, 'Stone', 'gold_block'));
					log(replace(0, 0, 0, 9, 9, 9, '*', 'glass'));
					log(replace(0, 0, 0, 9, 9, 9, 'diamond_block', 'glass'));
					""");
			assertEquals(List.of("2", "4", "0"), r.logs());
			assertEquals(List.of("0,0,0=minecraft:glass", "1,0,0=minecraft:glass", "2,0,0=minecraft:glass",
					"3,0,0=minecraft:air", "4,0,0=minecraft:glass"), entries(r.blueprint()));
			assertTypeError("replace(0, 0, 0, 1, 1, 1, 'stone')",
					"replace(): expected 8 arguments (x1, y1, z1, x2, y2, z2, from, to), got 7");
			assertTypeError("replace(0, 0, 0, 1, 1, 1, '#x', 'stone')", "replace(): invalid block id '#x'");
		}

		@Test
		void replaceMatchesShortAndLongIdsExactlyIncludingStates() {
			ScriptResult r = run("""
					block(0, 0, 0, 'oak_stairs[facing=north]');
					block(1, 0, 0, 'oak_stairs[facing=south]');
					block(2, 0, 0, 'oak_stairs');
					block(3, 0, 0, 'mymod:stone');
					block(4, 0, 0, 'stone');
					log(replace(0, 0, 0, 9, 0, 0, ' Oak_Stairs[ facing = north ]', 'gold_block'));
					log(replace(0, 0, 0, 9, 0, 0, 'minecraft:oak_stairs', 'iron_block'));
					log(replace(0, 0, 0, 9, 0, 0, 'minecraft:stone', 'dirt'));
					log(replace(0, 0, 0, 9, 0, 0, 'mymod:stone', 'glass'));
					""");
			assertEquals(List.of("1", "1", "1", "1"), r.logs());
			assertEquals(List.of("0,0,0=minecraft:gold_block", "1,0,0=minecraft:oak_stairs[facing=south]",
					"2,0,0=minecraft:iron_block", "3,0,0=minecraft:glass", "4,0,0=minecraft:dirt"), entries(r.blueprint()));
		}

		@Test
		void replaceRoundTripsWhatGetReturns() {
			ScriptResult r = run("""
					block(0, 0, 0, 'oak_stairs[facing=north]');
					block(1, 0, 0, 'cave_air');
					block(2, 0, 0, 'stone');
					clear(3, 0, 0, 3, 0, 0);
					log(replace(0, 0, 0, 9, 0, 0, get(0, 0, 0), 'spruce_stairs[facing=north]'));
					log(replace(0, 0, 0, 9, 0, 0, get(1, 0, 0), 'glass'));
					log(replace(0, 0, 0, 9, 0, 0, '*', 'dirt'));
					""");
			assertEquals(List.of("1", "2", "4"), r.logs());
			assertEquals(List.of("0,0,0=minecraft:dirt", "1,0,0=minecraft:dirt", "2,0,0=minecraft:dirt",
					"3,0,0=minecraft:dirt"), entries(r.blueprint()));
		}

		@Test
		void getReturnsShortIdsWithStates() {
			assertEquals("oak_stairs[facing=east] null null oak_stairs[facing=east]",
					log("block(1, 2, 3, 'oak_stairs[facing=east]'); log(get(1, 2, 3), get(0, 0, 0), get(1e12, 0, 0), get(0.6, 2.2, 2.5))"));
			assertEquals("stone|chest[facing=south]{Items:[]}|mymod:fancy_block[a=b]|air|air|air|air|null",
					log("""
							block(0, 0, 0, 'minecraft:stone');
							block(1, 0, 0, 'chest[facing=south]{Items:[]}');
							block(2, 0, 0, 'MyMod:Fancy_Block[ a = b ]');
							block(3, 0, 0, 'air');
							block(4, 0, 0, 'minecraft:cave_air');
							block(5, 0, 0, 'void_air');
							clear(6, 0, 0, 6, 0, 0);
							log([0, 1, 2, 3, 4, 5, 6, 7].map(x => String(get(x, 0, 0))).join('|'));
							"""));
			assertEquals("object", log("log(typeof get(0, 0, 0))"));
			assertTypeError("get(0, 0)", "get(): expected 3 arguments (x, y, z), got 2");
		}

		@Test
		void outOfRangeCoordinatesAreLimitErrors() {
			ScriptException e = failure(ScriptException.Kind.LIMIT, "block(40000, 0, 0, 'stone')");
			assertEquals("LimitError: block(): x = 40000 is outside the coordinate range -30000..30000 (line 1: block(40000, 0, 0, 'stone'))",
					e.getMessage());
		}
	}

	@Nested
	class Randomness {
		@Test
		void randomIsInUnitIntervalAndMathRandomIsTheSameGenerator() {
			assertEquals("true true", log("""
					let ok = true;
					for (let i = 0; i < 1000; i++) { const r = random(); if (!(r >= 0 && r < 1)) ok = false; }
					log(ok, Math.random === random);
					"""));
			assertEquals(logs("log(random(), random(), random())"), logs("log(random(), Math.random(), random())"));
		}

		@Test
		void randIntIsInclusive() {
			assertEquals("true true true", log("""
					const seen = new Set();
					let inRange = true;
					for (let i = 0; i < 2000; i++) { const v = randInt(-2, 3); seen.add(v); if (v < -2 || v > 3 || v !== Math.floor(v)) inRange = false; }
					log(inRange, seen.size === 6, randInt(7, 7) === 7);
					"""));
			assertTypeError("randInt(5, 3)", "randInt(): min (5) must not be greater than max (3)");
			assertTypeError("randInt(1)", "randInt(): expected 2 arguments (min, max), got 1");
			assertTypeError("randInt(0, NaN)", "randInt(): max must be a finite number");
		}

		@Test
		void pickReturnsAnElement() {
			assertEquals("true", log("const a = ['x', 'y', 'z']; let ok = true; for (let i = 0; i < 100; i++) if (!a.includes(pick(a))) ok = false; log(ok)"));
			assertTypeError("pick([])", "pick(): the array is empty");
			assertTypeError("pick('abc')", "pick(): expected an array, got string 'abc'");
			assertTypeError("pick()", "pick(): expected 1 arguments (array), got 0");
		}

		@Test
		void noiseMatchesTheSeededGenerator() {
			Noise noise = new Noise(ScriptTestSupport.SEED);
			String expected = ScriptSession.fmt(noise.noise2(0.3, 1.7)) + " " + ScriptSession.fmt(noise.noise3(0.3, 1.7, -2.2));
			assertEquals(expected, log("log(noise(0.3, 1.7), noise(0.3, 1.7, -2.2))"));
			assertEquals("true", log("let ok = true; for (let i = 0; i < 500; i++) { const n = noise(i / 7, i / 13, i / 3); if (n < -1 || n > 1) ok = false; } log(ok)"));
			assertTypeError("noise(1)", "noise(): expected 2 arguments (x, y), got 1");
			assertTypeError("noise(1, 2, 'z')", "noise(): z must be a finite number");
		}

		@Test
		void sameSeedSameResultDifferentSeedDifferentResult() {
			String script = """
					for (let i = 0; i < 200; i++) {
						block(randInt(0, 50), Math.floor(Math.random() * 20), Math.round(noise(i / 10, 0.5) * 20), pick(['stone', 'dirt', 'glass']));
					}
					log(random());
					""";
			ScriptResult a = run(script, ScriptLimits.defaults(), 1);
			ScriptResult b = run(script, ScriptLimits.defaults(), 1);
			ScriptResult c = run(script, ScriptLimits.defaults(), 2);
			assertEquals(entries(a.blueprint()), entries(b.blueprint()));
			assertEquals(a.logs(), b.logs());
			assertTrue(!entries(a.blueprint()).equals(entries(c.blueprint())));
			assertTrue(!a.logs().equals(c.logs()));
		}
	}

	@Nested
	class Results {
		@Test
		void defaults() {
			ScriptResult r = run("// nothing");
			assertTrue(r.blueprint().isEmpty());
			assertEquals(io.github.saocsb.mcai.core.placement.PlacementSpec.defaults(), r.placement());
			assertNull(r.name());
			assertEquals(List.of(), r.actions());
			assertEquals(List.of(), r.logs());
			assertTrue(r.elapsedMillis() >= 0);
		}

		@Test
		void returnValueIsIgnored() {
			assertEquals(1, run("block(0, 0, 0, 'stone'); return 5;").blueprint().size());
		}

		@Test
		void topLevelDeclarationsMayShadowApiNames() {
			ScriptResult r = run("""
					const text = "hi";
					let line = 3;
					fill(0, 0, 0, line, 0, 0, 'stone');
					function sphere(n) { return n * 2; }
					log(text, sphere(2));
					""");
			assertEquals(4, r.blueprint().size());
			assertEquals(List.of("hi 4"), r.logs());
			assertEquals(List.of("1"), run("const block = 1; log(block);").logs());
		}

		@Test
		void setName() {
			assertEquals("Eiffel Tower", run("setName('  Eiffel Tower  ')").name());
			assertEquals("x".repeat(64), run("setName('x'.repeat(100))").name());
			assertEquals("a b", run("setName('a\\nb')").name());
			assertNull(run("setName('   ')").name());
			assertEquals("second", run("setName('first'); setName('second')").name());
			assertTypeError("setName({})", "setName(): name must be a string, got object");
			assertTypeError("setName()", "setName(): expected 1 arguments (name), got 0");
		}
	}
}
