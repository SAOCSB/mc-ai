package io.github.saocsb.mcai.core.script;

import io.github.saocsb.mcai.core.placement.GroundMode;
import io.github.saocsb.mcai.core.placement.PlacementMode;
import io.github.saocsb.mcai.core.placement.PlacementSpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.saocsb.mcai.core.script.ScriptTestSupport.failure;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.limits;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.log;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.logs;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** placement(), world actions and logging. */
class ScriptEngineActionsTest {
	private static void assertTypeError(String script, String message) {
		ScriptException e = failure(ScriptException.Kind.RUNTIME, script);
		assertTrue(e.getMessage().startsWith("TypeError: " + message + " (line "), e.getMessage());
	}

	private static List<WorldAction> actions(String script) {
		return run(script).actions();
	}

	@Nested
	class Placement {
		@Test
		void defaultsWhenNotCalled() {
			assertEquals(PlacementSpec.defaults(), run("block(0,0,0,'stone')").placement());
		}

		@Test
		void parsesAllOptions() {
			assertEquals(new PlacementSpec(PlacementMode.CENTER, 10, GroundMode.SURFACE, true, null, null, null),
					run("placement({mode: 'Center', distance: 9.6, ground: 'SURFACE', replace: 1})").placement());
			assertEquals(new PlacementSpec(PlacementMode.ABSOLUTE, 3, GroundMode.PLAYER, false, 100, -60, null),
					run("placement({mode: 'absolute', x: 100.4, y: -60, z: undefined})").placement());
			assertEquals(new PlacementSpec(PlacementMode.PREVIOUS, 3, GroundMode.PLAYER, true, null, null, null),
					run("placement({mode: 'previous', replace: true})").placement());
			assertEquals(new PlacementSpec(PlacementMode.FRONT, 0, GroundMode.PLAYER, false, null, null, null),
					run("placement({distance: 0})").placement());
		}

		@Test
		void lastCallWins() {
			assertEquals(new PlacementSpec(PlacementMode.ORIGIN, 3, GroundMode.PLAYER, false, null, null, null),
					run("placement({mode: 'center', distance: 20, ground: 'surface'}); placement({mode: 'origin'})").placement());
		}

		@Test
		void stringShorthandSetsTheMode() {
			assertEquals(PlacementMode.CENTER, run("placement('center')").placement().mode());
		}

		@Test
		void unknownKeysAreLoggedAndIgnored() {
			ScriptResult r = run("placement({mode: 'origin', foo: 1, rotate: 90})");
			assertEquals(PlacementMode.ORIGIN, r.placement().mode());
			assertEquals(List.of("placement(): ignoring unknown option 'foo'", "placement(): ignoring unknown option 'rotate'"),
					r.logs());
		}

		@Test
		void anchorIsOnlyKeptForAbsoluteMode() {
			ScriptResult r = run("placement({mode: 'center', x: 5})");
			assertEquals(new PlacementSpec(PlacementMode.CENTER, 3, GroundMode.PLAYER, false, null, null, null), r.placement());
			assertEquals(List.of("placement(): x, y and z are only used with mode 'absolute'"), r.logs());
		}

		@Test
		void invalidValues() {
			assertTypeError("placement({mode: 'behind'})",
					"placement(): unknown mode 'behind' (valid: 'front', 'center', 'origin', 'previous', 'absolute')");
			assertTypeError("placement({mode: 3})",
					"placement(): unknown mode 3 (valid: 'front', 'center', 'origin', 'previous', 'absolute')");
			assertTypeError("placement({ground: 'sky'})", "placement(): unknown ground 'sky' (valid: 'player', 'surface')");
			assertTypeError("placement({distance: 65})", "placement(): distance must be between 0 and 64, got 65");
			assertTypeError("placement({distance: -1})", "placement(): distance must be between 0 and 64, got -1");
			assertTypeError("placement({distance: 'far'})", "placement(): distance must be a finite number");
			assertTypeError("placement({mode: 'absolute', x: 'here'})", "placement(): x must be a finite number");
			assertTypeError("placement([1])", "placement(): expected an options object such as {mode: 'center'}, got array");
			assertTypeError("placement()", "placement(): expected 1 arguments (options), got 0");
		}
	}

	@Nested
	class Actions {
		@Test
		void summon() {
			assertEquals(List.of(new WorldAction.Summon("minecraft:zombie", 1, 2, -3, null),
							new WorldAction.Summon("villager", 0, 0, 0, "{CustomName:'\"Bob\"',NoAI:1b}"),
							new WorldAction.Summon("pig", 0, 1, 0, null)),
					actions("summon('minecraft:zombie', 1.2, 2, -3); summon(' Villager ', 0, 0, 0, `{CustomName:'\"Bob\"',NoAI:1b}`); summon('pig', 0, 1, 0, null)"));
			assertTypeError("summon('zombie', 0, 0)", "summon(): expected 4 arguments (entityId, x, y, z), got 3");
			assertTypeError("summon('big zombie', 0, 0, 0)", "summon(): invalid entity id 'big zombie'");
			assertTypeError("summon('', 0, 0, 0)", "summon(): invalid entity id ''");
			assertTypeError("summon(5, 0, 0, 0)", "summon(): entity id must be a string, got number 5");
			assertTypeError("summon('zombie', 0, 0, 0, {NoAI: 1})", "summon(): nbt must be an SNBT string such as '{NoAI:1b}', got object");
			assertTypeError("summon('zombie', 0, 0, 0, 'NoAI:1b')", "summon(): nbt must be an SNBT compound such as '{NoAI:1b}', got 'NoAI:1b'");
		}

		@Test
		void give() {
			assertEquals(List.of(new WorldAction.Give("diamond", 1), new WorldAction.Give("minecraft:torch", 64),
							new WorldAction.Give("diamond_sword[enchantments={sharpness:5}]", 1),
							new WorldAction.Give("written_book[written_book_content={title:\"My Book\",author:\"AI\"}]", 2)),
					actions("give('diamond'); give('minecraft:torch', 64); give('diamond_sword[enchantments={sharpness:5}]');"
							+ " give('written_book[written_book_content={title:\"My Book\",author:\"AI\"}]', 2)"));
			assertTypeError("give('diamond', 0)", "give(): count must be between 1 and 6400, got 0");
			assertTypeError("give('diamond', 6401)", "give(): count must be between 1 and 6400, got 6401");
			assertTypeError("give('diamond', 'lots')", "give(): count must be a finite number");
			assertTypeError("give('dia mond')", "give(): invalid item id 'dia mond'");
			assertTypeError("give('sword[sharpness')", "give(): invalid item id 'sword[sharpness'");
			assertTypeError("give()", "give(): expected 1 arguments (itemId), got 0");
		}

		@Test
		void time() {
			assertEquals(List.of(new WorldAction.SetTime("day"), new WorldAction.SetTime("midnight"),
							new WorldAction.SetTime("6000"), new WorldAction.SetTime("0"), new WorldAction.SetTime("24000")),
					actions("time('day'); time(' MIDNIGHT '); time(6000.4); time(0); time(24000)"));
			assertTypeError("time('dawn')", "time(): expected 'day', 'noon', 'night', 'midnight' or ticks 0..24000, got 'dawn'");
			assertTypeError("time(24001)", "time(): expected 'day', 'noon', 'night', 'midnight' or ticks 0..24000, got 24001");
			assertTypeError("time(-1)", "time(): expected 'day', 'noon', 'night', 'midnight' or ticks 0..24000, got -1");
		}

		@Test
		void weather() {
			assertEquals(List.of(new WorldAction.SetWeather("clear"), new WorldAction.SetWeather("rain"),
					new WorldAction.SetWeather("thunder")), actions("weather('clear'); weather('Rain'); weather('thunder')"));
			assertTypeError("weather('snow')", "weather(): expected 'clear', 'rain' or 'thunder', got 'snow'");
			assertTypeError("weather(1)", "weather(): expected 'clear', 'rain' or 'thunder', got 1");
		}

		@Test
		void effect() {
			assertEquals(List.of(new WorldAction.Effect("speed", 30, 0), new WorldAction.Effect("minecraft:night_vision", 600, 0),
							new WorldAction.Effect("jump_boost", 10, 2)),
					actions("effect('speed'); effect('minecraft:night_vision', 600); effect('jump_boost', 10, 2)"));
			assertTypeError("effect('speed', 0)", "effect(): seconds must be between 1 and 1000000, got 0");
			assertTypeError("effect('speed', 10, 256)", "effect(): amplifier must be between 0 and 255, got 256");
			assertTypeError("effect('speed boost')", "effect(): invalid effect id 'speed boost'");
		}

		@Test
		void command() {
			assertEquals(List.of(new WorldAction.Command("say hello"), new WorldAction.Command("particle minecraft:heart ~ ~2 ~")),
					actions("command('/say hello'); command('  particle minecraft:heart ~ ~2 ~ ')"));
			assertTypeError("command('/')", "command(): the command is empty");
			assertTypeError("command('say a\\nsay b')", "command(): the command must be a single line without control characters");
			assertTypeError("command(5)", "command(): cmd must be a string, got number 5");
		}

		@Test
		void waitPausesTheActionList() {
			assertEquals(List.of(new WorldAction.Give("iron_sword", 1), new WorldAction.Wait(5),
					new WorldAction.Summon("zombie", 8, 0, 0, null), new WorldAction.Wait(0.5), new WorldAction.Wait(600)),
					actions("give('iron_sword'); wait(5); summon('zombie', 8, 0, 0); wait(0.5); wait(600)"));
			assertTypeError("wait(0)", "wait(): seconds must be greater than 0 and at most 600, got 0");
			assertTypeError("wait(-1)", "wait(): seconds must be greater than 0 and at most 600, got -1");
			assertTypeError("wait(600.5)", "wait(): seconds must be greater than 0 and at most 600, got 600.5");
			assertTypeError("wait(NaN)", "wait(): seconds must be a finite number");
			assertTypeError("wait(Infinity)", "wait(): seconds must be a finite number");
			assertTypeError("wait('5')", "wait(): seconds must be a finite number");
			assertTypeError("wait()", "wait(): expected 1 arguments (seconds), got 0");
		}

		@Test
		void waitCountsTowardTheActionLimit() {
			ScriptLimits l = limits(1000, 64, 5000, 2, 100, 50);
			ScriptException e = failure(ScriptException.Kind.LIMIT, "wait(1);\nwait(2);\nwait(3);", l);
			assertEquals("LimitError: wait(): too many world actions (max 2) (line 3: wait(3);)", e.getMessage());
		}

		@Test
		void actionsKeepTheirOrder() {
			assertEquals(List.of(new WorldAction.SetWeather("clear"), new WorldAction.Give("cake", 1),
					new WorldAction.SetTime("noon")), actions("weather('clear'); give('cake'); time('noon')"));
		}

		@Test
		void tooManyActionsIsALimitError() {
			ScriptLimits l = limits(1000, 64, 5000, 2, 100, 50);
			ScriptException e = failure(ScriptException.Kind.LIMIT, "time('day');\ntime('noon');\ntry { time('night'); } catch (e) { log('caught'); }", l);
			assertEquals("LimitError: time(): too many world actions (max 2) (line 3: try { time('night'); } catch (e) { log('caught'); })",
					e.getMessage());
			assertEquals(List.of(), e.logs());
		}
	}

	@Nested
	class Logging {
		@Test
		void joinsArgumentsWithSpaces() {
			assertEquals("a 1 2.5 true null undefined", log("log('a', 1, 2.5, true, null, undefined)"));
			assertEquals("", log("log()"));
		}

		@Test
		void formatsNumbersLikeJavaScript() {
			assertEquals("3 -0.5 1e+21 0.1 NaN Infinity 100", log("print(3, -0.5, 1e21, 0.1, NaN, Infinity, 10 * 10)"));
		}

		@Test
		void formatsObjectsAsJson() {
			assertEquals("{\"a\":1,\"b\":[1,\"x\",null]} [1,2] [] {}", log("log({a: 1, b: [1, 'x', null]}, [1, 2], [], {})"));
			assertEquals("[[0,0],[1,2]]", log("const pts = []; pts.push([0, 0]); pts.push([1, 2]); log(pts)"));
		}

		@Test
		void formatsSpecialValues() {
			assertEquals("[function block] [function f] Error: boom TypeError: bad",
					log("function f() {} log(block, f, new Error('boom'), new TypeError('bad'))"));
			assertEquals("[object Object]", log("const o = {}; o.self = o; log(o)"));
			assertEquals("Symbol(s)", log("log(Symbol('s'))"));
		}

		@Test
		void consoleMethodsAndPrint() {
			assertEquals(List.of("l", "i", "w", "e", "p"),
					logs("console.log('l'); console.info('i'); console.warn('w'); console.error('e'); print('p');"));
		}

		@Test
		void multiLineMessagesBecomeSeveralLines() {
			assertEquals(List.of("a", "b"), logs("log('a\\nb')"));
		}

		@Test
		void truncatesAtTheLimit() {
			ScriptLimits l = limits(1000, 64, 5000, 5, 100, 3);
			ScriptResult r = run("for (let i = 0; i < 10; i++) log('line', i);", l);
			assertEquals(List.of("line 0", "line 1", "line 2", "... (log truncated)"), r.logs());
		}

		@Test
		void veryLongLinesAreShortened() {
			String line = log("log('x'.repeat(5000))");
			assertEquals("x".repeat(1000) + "... (4000 more characters)", line);
		}

		@Test
		void logsAreKeptWhenTheScriptFails() {
			ScriptException e = failure(ScriptException.Kind.RUNTIME, "log('before');\nconsole.warn({n: 1});\nnull.x;");
			assertEquals(List.of("before", "{\"n\":1}"), e.logs());
		}
	}
}
