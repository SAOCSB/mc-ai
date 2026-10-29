package io.github.saocsb.mcai.core.script;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.saocsb.mcai.core.script.ScriptTestSupport.ENGINE;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.SEED;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.failure;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.limits;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.log;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.logs;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.withTimeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Time budget, cancellation, recursion, block/span limits and error messages. */
class ScriptEngineLimitsTest {
	private static ScriptException timeout(String script) {
		long start = System.nanoTime();
		ScriptException e = failure(ScriptException.Kind.TIMEOUT, script, withTimeout(300));
		long millis = (System.nanoTime() - start) / 1_000_000;
		assertTrue(millis < 3000, "timeout took " + millis + " ms");
		assertTrue(e.getMessage().startsWith("TimeoutError: script ran longer than 300 ms"), e.getMessage());
		return e;
	}

	@Nested
	@Timeout(30)
	class Timeouts {
		@Test
		void infiniteLoop() {
			ScriptException e = timeout("let i = 0;\nwhile (true) { i++; }");
			assertEquals("TimeoutError: script ran longer than 300 ms (line 2: while (true) { i++; })", e.getMessage());
			assertEquals(2, e.line());
		}

		@Test
		void tryCatchFinallyCannotSwallowTheTimeout() {
			ScriptException e = timeout("""
					while (true) {
						try { while (true) {} }
						catch (e) { log('caught ' + e); }
						finally { log('finally'); }
					}
					""");
			assertEquals(List.of(), e.logs());
		}

		@Test
		void busyLoopCallingBlock() {
			timeout("for (;;) { block(0, 0, 0, 'stone'); block(1, 0, 0, 'dirt'); }");
		}

		@Test
		void loopInsideCallbacks() {
			timeout("[1, 2, 3].forEach(() => { while (true) {} });");
			timeout("[3, 1, 2].sort((a, b) => { for (;;) {} });");
			timeout("JSON.stringify({toJSON() { while (true) {} }});");
			timeout("log({get x() { while (true) {} }});");
			timeout("'aaaa'.replace(/a/g, () => { while (true) {} });");
		}

		@Test
		void catastrophicRegexBacktracking() {
			// the regex engine counts instructions; its backtracking stack may hit the memory budget first
			long start = System.nanoTime();
			ScriptException e = failure("/^(a+)+$/.test('a'.repeat(40) + 'b');", withTimeout(300));
			long millis = (System.nanoTime() - start) / 1_000_000;
			assertTrue(e.kind() == ScriptException.Kind.TIMEOUT || e.kind() == ScriptException.Kind.LIMIT, e.getMessage());
			assertTrue(millis < 3000, "took " + millis + " ms");
		}

		@Test
		void loopInsideEval() {
			timeout("eval('while (true) {}')");
			timeout("new Function('for (;;) {}')()");
		}
	}

	@Nested
	@Timeout(30)
	class Cancellation {
		@Test
		void interruptStopsARunningScriptQuickly() throws Exception {
			CountDownLatch started = new CountDownLatch(1);
			AtomicReference<Throwable> result = new AtomicReference<>();
			AtomicBoolean interruptedAfter = new AtomicBoolean();
			Thread worker = new Thread(() -> {
				started.countDown();
				try {
					ENGINE.run("while (true) { try { for (;;) {} } catch (e) {} }", ScriptLimits.defaults(), SEED);
				} catch (Throwable t) {
					result.set(t);
				}
				interruptedAfter.set(Thread.currentThread().isInterrupted());
			});
			worker.start();
			assertTrue(started.await(5, TimeUnit.SECONDS));
			Thread.sleep(200);
			long start = System.nanoTime();
			worker.interrupt();
			worker.join(5000);
			long millis = (System.nanoTime() - start) / 1_000_000;
			assertFalse(worker.isAlive());
			assertTrue(millis < 1000, "cancellation took " + millis + " ms");
			ScriptException e = assertInstanceOf(ScriptException.class, result.get());
			assertEquals(ScriptException.Kind.CANCELLED, e.kind());
			assertEquals(ScriptEngine.CANCELLED_MESSAGE, e.getMessage());
			assertTrue(interruptedAfter.get(), "the interrupt flag is kept");
		}

		@Test
		void interruptedThreadDoesNotStartTheScript() throws Exception {
			ScriptException e = CompletableFuture.supplyAsync(() -> {
				Thread.currentThread().interrupt();
				try {
					ENGINE.run("block(0, 0, 0, 'stone')", ScriptLimits.defaults(), SEED);
					return null;
				} catch (ScriptException ex) {
					return ex;
				} finally {
					Thread.interrupted();
				}
			}).get(5, TimeUnit.SECONDS);
			assertNotNull(e);
			assertEquals(ScriptException.Kind.CANCELLED, e.kind());
		}
	}

	@Nested
	@Timeout(30)
	class Recursion {
		@Test
		void deepRecursionIsALimitError() {
			ScriptException e = failure(ScriptException.Kind.LIMIT, "function f(n) {\n  return f(n + 1) + 1;\n}\nf(0);");
			assertEquals("LimitError: recursion too deep (max call depth 400) (line 2: return f(n + 1) + 1;)", e.getMessage());
		}

		@Test
		void tailCallRecursionIsALimitError() {
			ScriptException e = failure(ScriptException.Kind.LIMIT, "function f() { return f(); }\nf();");
			assertEquals("LimitError: recursion too deep (max call depth 400) (line 1: function f() { return f(); })", e.getMessage());
			assertTrue(failure(ScriptException.Kind.LIMIT, "const g = n => g(n + 1);\ng(0);").getMessage().startsWith("LimitError: recursion too deep"));
		}

		@Test
		void recursionErrorCannotBeCaught() {
			ScriptException e = failure(ScriptException.Kind.LIMIT,
					"function f() { try { f(); } catch (e) { log('caught'); } finally { log('finally'); } }\nf();");
			assertTrue(e.getMessage().startsWith("LimitError: recursion too deep (max call depth 400)"), e.getMessage());
			assertEquals(List.of(), e.logs());
		}

		@Test
		void recursionThroughNativeCallbacksIsALimitError() {
			ScriptException e = failure(ScriptException.Kind.LIMIT, "function f() { [0].forEach(f); }\nf();");
			assertTrue(e.getMessage().startsWith("LimitError: recursion too deep (max call depth 400)"), e.getMessage());
			ScriptException e2 = failure(ScriptException.Kind.LIMIT, "const o = { toString() { return String(o); } };\nString(o);");
			assertTrue(e2.getMessage().startsWith("LimitError: recursion too deep"), e2.getMessage());
		}

		@Test
		void moderateRecursionWorks() {
			assertEquals("6765", log("function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }\nlog(fib(20));"));
			assertEquals("300", log("function depth(n) { return n === 0 ? 0 : 1 + depth(n - 1); }\nlog(depth(300));"));
		}

		@Test
		void customDepthLimit() {
			ScriptLimits l = limits(1000, 64, 5000, 5, 50, 50);
			ScriptException e = failure(ScriptException.Kind.LIMIT, "function d(n) { return n === 0 ? 0 : 1 + d(n - 1); } d(100);", l);
			assertTrue(e.getMessage().startsWith("LimitError: recursion too deep (max call depth 50)"), e.getMessage());
		}
	}

	@Nested
	class BlueprintLimits {
		@Test
		void blockLimit() {
			ScriptLimits l = limits(100, 64, 5000, 5, 100, 50);
			ScriptException e = failure(ScriptException.Kind.LIMIT, "block(0, 0, 0, 'stone');\ntry { fill(0, 0, 0, 9, 9, 9, 'stone'); } catch (e) { log('caught'); }", l);
			assertEquals("LimitError: build exceeds the 100-block limit (line 2: try { fill(0, 0, 0, 9, 9, 9, 'stone'); } catch (e) { log('caught'); })",
					e.getMessage());
			assertEquals(List.of(), e.logs());
		}

		@Test
		void blockLimitCountsDistinctPositions() {
			ScriptLimits l = limits(100, 64, 5000, 5, 100, 50);
			ScriptResult r = ScriptTestSupport.run("for (let i = 0; i < 1000; i++) fill(0, 0, 0, 9, 9, 0, i % 2 ? 'stone' : 'dirt');", l);
			assertEquals(100, r.blueprint().size());
		}

		@Test
		void tooManyDifferentBlockIds() {
			ScriptException e = failure(ScriptException.Kind.LIMIT,
					"for (let i = 0; i < 5000; i++)\n  block(0, 0, 0, 'stone_bricks[variant=' + i + ']');");
			assertEquals("LimitError: too many different block ids (limit 4096) (line 2: block(0, 0, 0, 'stone_bricks[variant=' + i + ']');)",
					e.getMessage());
			assertTrue(failure(ScriptException.Kind.RUNTIME, "block(0, 0, 0, 'mymod:' + 'a'.repeat(5000))").getMessage()
					.startsWith("TypeError: block(): invalid block id (5006 characters long)"));
		}

		@Test
		void spanLimit() {
			ScriptLimits l = limits(10_000, 50, 5000, 5, 100, 50);
			ScriptException e = failure(ScriptException.Kind.LIMIT, "block(0, 0, 0, 'stone');\nblock(60, 0, 0, 'stone');", l);
			assertTrue(e.getMessage().startsWith("LimitError: build spans 61 blocks along x (limit 50) (line 2:"), e.getMessage());
		}

		@Test
		void oversizedShapesFailBeforeDrawing() {
			ScriptException e = failure(ScriptException.Kind.LIMIT, "sphere(0, 0, 0, 1000, 'stone')");
			assertTrue(e.getMessage().startsWith("LimitError: sphere(): r 1000 makes the shape wider than the maximum span of 384 blocks"),
					e.getMessage());
			assertTrue(failure(ScriptException.Kind.LIMIT, "torus(0, 0, 0, 300, 2, 'stone')").getMessage().startsWith("LimitError: torus():"));
			assertTrue(failure(ScriptException.Kind.LIMIT, "cylinder(0, 0, 0, 2, 1000, 'stone')").getMessage()
					.startsWith("LimitError: cylinder(): height 1000 exceeds the maximum span of 384 blocks"));
			assertTrue(failure(ScriptException.Kind.LIMIT, "pyramid(0, 0, 0, 200, 'stone')").getMessage().startsWith("LimitError: pyramid():"));
			assertTrue(failure(ScriptException.Kind.LIMIT, "polygon([[0, 0], [500, 0], [0, 10]], 0, 1, 'stone')").getMessage()
					.startsWith("LimitError: polygon(): the footprint is 501 blocks wide"));
			assertTrue(failure(ScriptException.Kind.LIMIT, "text('A', 0, 0, 0, 'stone', 1000)").getMessage().startsWith("LimitError: text():"));
			assertTrue(failure(ScriptException.Kind.LIMIT, "fill(0, 0, 0, 1000, 0, 0, 'stone')").getMessage().startsWith("LimitError: build spans"));
		}

		@Test
		void largestShapeThatFitsIsAccepted() {
			ScriptLimits l = limits(300_000, 21, 5000, 5, 100, 50);
			ScriptResult r = ScriptTestSupport.run("sphere(0, 0, 0, 10.4, 'stone')", l);
			assertEquals(21, r.blueprint().bounds().sizeX());
		}
	}

	@Nested
	@Timeout(60)
	class NativeGuardsAndMemory {
		private void assertLimit(String script, String messageStart) {
			long start = System.nanoTime();
			ScriptException e = failure(ScriptException.Kind.LIMIT, script);
			long millis = (System.nanoTime() - start) / 1_000_000;
			assertTrue(e.getMessage().startsWith(messageStart), e.getMessage());
			assertTrue(millis < 3000, "took " + millis + " ms");
		}

		private void assertArrayLimit(String script, String label, String what, long length) {
			assertLimit(script, "LimitError: " + label + ": " + what + " " + length
					+ " elements, more than the limit of 2097152 (line 1:");
		}

		@Test
		void hugeArraysAreRejectedBeforeNativeLoops() {
			assertArrayLimit("new Array(1e9).fill(0)", "Array.prototype.fill()", "the array has", 1_000_000_000L);
			assertArrayLimit("const a = []; a.length = 5e6; a.forEach(x => x);", "Array.prototype.forEach()", "the array has", 5_000_000L);
			assertArrayLimit("const a = []; a[4294967294] = 1; String(a);", "Array.prototype.toString()", "the array has", 4_294_967_295L);
			assertArrayLimit("const a = new Array(1e8); try { a.includes(1); } catch (e) { log('caught'); }", "Array.prototype.includes()", "the array has", 100_000_000L);
			assertArrayLimit("Array.from({length: 1e9})", "Array.from()", "the array-like object has", 1_000_000_000L);
			assertArrayLimit("const a = []; a.length = 4294967295; const b = [...a];", "array iteration", "the array has", 4_294_967_295L);
			assertArrayLimit("const a = new Array(2e6).fill(0); a.concat(a);", "Array.prototype.concat()", "the result has", 4_000_000L);
			assertLimit("let d = [1]; for (let i = 0; i < 40; i++) d = [d, d]; d.flat(Infinity);",
					"LimitError: Array.prototype.flat(): the result has 2097153 elements");
		}

		@Test
		void arrayMethodsOnlyTakeArraysSoLengthGettersCannotLie() {
			ScriptException e = failure(ScriptException.Kind.RUNTIME, "[].map.call({length: 1e9}, x => x)");
			assertTrue(e.getMessage().startsWith("TypeError: Array.prototype.map() only works on arrays here;"
					+ " convert array-like values with Array.from() first (line 1:"), e.getMessage());
			assertEquals("1", log("let n = 0; const o = { get length() { return n++ ? 4294967295 : 1; } }; log(Array.from(o).length)"));
			assertEquals("3 2,3 6 3", log("""
					function f() { return [Array.from(arguments).length, Array.prototype.slice.call(arguments, 1).join(), [...arguments].reduce((s, v) => s + v, 0)]; }
					function g() { let n = 0; for (const v of arguments) n++; return n; }
					log(...f(1, 2, 3), g(4, 5, 6));
					"""));
			assertTrue(failure(ScriptException.Kind.RUNTIME, "function f() { Array.prototype.shift.call(arguments); } f(1)").getMessage()
					.startsWith("TypeError: Array.prototype.shift() cannot change an arguments object"));
		}

		@Test
		void hugeStringsAreRejected() {
			assertLimit("'abc'.repeat(1e8)",
					"LimitError: String.prototype.repeat(): a string of 300000000 characters exceeds the limit of 4194304");
			assertLimit("'x'.padStart(1e9, 'y')", "LimitError: String.prototype.padStart(): a string of 1000000000 characters");
			assertLimit("new Array(2e6).join('x'.repeat(100))", "LimitError: Array.prototype.join(): a string of ");
			assertLimit("let s = 'x'; for (let i = 0; i < 30; i++) s += s; s.indexOf('y');",
					"LimitError: String.prototype.indexOf(): a string of 1073741824 characters");
			assertLimit("let s = 'x'; for (let i = 0; i < 25; i++) s += s; JSON.parse(s);", "LimitError: JSON.parse(): a string of 33554432");
			assertLimit("let s = 'x'; for (let i = 0; i < 25; i++) s += s; /y/.test(s);", "LimitError: RegExp.prototype.test(): a string of 33554432");
			assertEquals("<string of 1073741824 characters>", log("let s = 'x'; for (let i = 0; i < 30; i++) s += s; log(s)"));
			assertTrue(failure(ScriptException.Kind.RUNTIME, "let s = 'x'; for (let i = 0; i < 30; i++) s += s; block(0, 0, 0, s)").getMessage()
					.startsWith("TypeError: block(): invalid block id (1073741824 characters long)"));
		}

		@Test
		void exponentialNativeWorkIsStopped() {
			ScriptLimits l = withTimeout(500);
			for (String script : new String[] {
					"let d = [1]; for (let i = 0; i < 40; i++) d = [d, d]; JSON.stringify(d);",
					"let d = [1]; for (let i = 0; i < 40; i++) d = [d, d]; String(d);",
					"let d = [1]; for (let i = 0; i < 40; i++) d = [d, d]; log(d);",
					"const a = new Array(100000).fill(0); a.forEach(() => a.forEach(() => 0));",
					"const a = Array.from({length: 2000000}, (_, i) => i); a.sort(() => 0); a.map(() => a.map(x => x));",
			}) {
				long start = System.nanoTime();
				ScriptException e = failure(script, l);
				long millis = (System.nanoTime() - start) / 1_000_000;
				assertTrue(e.kind() == ScriptException.Kind.TIMEOUT || e.kind() == ScriptException.Kind.LIMIT, e.getMessage());
				assertTrue(millis < 5000, script + " took " + millis + " ms");
			}
		}

		@Test
		void guardedBuiltInsStillWork() {
			assertEquals("0,0,0 6 3 4,6 1-2-3 3,2,1 [1,2,3] 1 true -2 2 010 abab x  1,2,3,4", log("""
					const a = [1, 2, 3];
					log(new Array(3).fill(0).join(), a.reduce((s, v) => s + v, 0), a.length, a.filter(v => v > 1).map(v => v * 2).join(),
						a.join('-'), [...a].reverse().join(), JSON.stringify(a.slice()), a.indexOf(2), a.includes(3), -[2].find(v => v),
						Array.from(new Set([1, 1, 2])).length, '10'.padStart(3, '0'), 'ab'.repeat(2), 'x'.padEnd(2), String([1, 2, [3, 4]]));
					"""));
			assertEquals("fill 1 true", log("log([].fill.name, [].fill.length, Array.prototype.forEach !== undefined)"));
			assertEquals("{\"a\":[1,2]} {\"a\":\"[1,2]\"} {\n \"x\": 1\n} b", String.join("\n", logs("""
					log(JSON.stringify({a: [1, 2]}), JSON.stringify({a: [1, 2]}, (k, v) => Array.isArray(v) && k ? JSON.stringify(v) : v),
						JSON.stringify({x: 1}, null, 1), 'abc'.replace(/a(b)c/, '$1'));
					""")));
			assertEquals("200000 200000", log("const a = new Array(200000).fill(1); log(a.length, a.reduce((s, v) => s + v, 0))"));
		}

		@Test
		void allocationBudget() {
			org.junit.jupiter.api.Assumptions.assumeTrue(AllocationMeter.currentThreadAllocatedBytes() >= 0);
			ScriptException e = failure(ScriptException.Kind.LIMIT, "while (true) {\n  const garbage = new Array(100000).fill(1);\n}");
			assertTrue(e.getMessage().startsWith("LimitError: the script allocated more than "
					+ (ScriptSession.allocationBudget() >> 20) + " MB of memory (line "), e.getMessage());
		}
	}

	@Nested
	class ErrorMessages {
		@Test
		void syntaxErrorOnFirstLine() {
			ScriptException e = failure(ScriptException.Kind.SYNTAX, "block(0, 0, 0, 'stone'");
			assertEquals(1, e.line());
			assertTrue(e.getMessage().startsWith("SyntaxError: "), e.getMessage());
			assertTrue(e.getMessage().endsWith(" (line 1: block(0, 0, 0, 'stone')"), e.getMessage());
		}

		@Test
		void syntaxErrorOnFifthLine() {
			ScriptException e = failure(ScriptException.Kind.SYNTAX, "// a comment\nconst a = 1;\nconst b = 2;\n\n    let = = 5;\nblock(0,0,0,'stone');");
			assertEquals(5, e.line());
			assertTrue(e.getMessage().endsWith(" (line 5: let = = 5;)"), e.getMessage());
		}

		@Test
		void syntaxErrorAtTheEndPointsAtTheLastLine() {
			ScriptException e = failure(ScriptException.Kind.SYNTAX, "for (let i = 0; i < 3; i++) {\n  block(i, 0, 0, 'stone');\n\n");
			assertEquals(2, e.line());
			assertTrue(e.getMessage().endsWith(" (line 2: block(i, 0, 0, 'stone');)"), e.getMessage());
		}

		@Test
		void syntaxErrorHints() {
			assertTrue(failure(ScriptException.Kind.SYNTAX, "class Tower {}").getMessage()
					.contains("classes are not supported"));
			assertTrue(failure(ScriptException.Kind.SYNTAX, "async function f() {}").getMessage()
					.contains("async functions are not supported"));
		}

		@Test
		void runtimeErrorOnFirstLine() {
			ScriptException e = failure(ScriptException.Kind.RUNTIME, "undefinedFunction(1, 2);");
			assertEquals("ReferenceError: \"undefinedFunction\" is not defined. (line 1: undefinedFunction(1, 2);)", e.getMessage());
			assertEquals(1, e.line());
		}

		@Test
		void runtimeErrorOnFifthLine() {
			ScriptException e = failure(ScriptException.Kind.RUNTIME,
					"const pts = [];\nfor (let i = 0; i < 3; i++) {\n  pts.push({x: i});\n}\n  const p = pts[5].x;\n");
			assertEquals("TypeError: Cannot read property \"x\" from undefined (line 5: const p = pts[5].x;)", e.getMessage());
			assertEquals(5, e.line());
		}

		@Test
		void errorInsideAFunctionReportsTheThrowingLine() {
			ScriptException e = failure(ScriptException.Kind.RUNTIME, "function f(n) {\n  return n.foo.bar;\n}\n\nf({});");
			assertEquals(2, e.line());
		}

		@Test
		void apiArgumentErrorsReportTheCallingLine() {
			ScriptException e = failure(ScriptException.Kind.RUNTIME, "\n\nfill(0, 0, 0, 1, 1, 1);");
			assertEquals("TypeError: fill(): expected 7 arguments (x1, y1, z1, x2, y2, z2, id), got 6 (line 3: fill(0, 0, 0, 1, 1, 1);)",
					e.getMessage());
		}

		@Test
		void thrownValues() {
			assertEquals("Error: boom (line 2: throw new Error('boom');)",
					failure(ScriptException.Kind.RUNTIME, "\nthrow new Error('boom');").getMessage());
			assertEquals("RangeError: too far (line 1: throw new RangeError('too far'))",
					failure(ScriptException.Kind.RUNTIME, "throw new RangeError('too far')").getMessage());
			assertEquals("Error: plain string (line 1: throw 'plain string')",
					failure(ScriptException.Kind.RUNTIME, "throw 'plain string'").getMessage());
			assertEquals("Error: {\"code\":7} (line 1: throw {code: 7})",
					failure(ScriptException.Kind.RUNTIME, "throw {code: 7}").getMessage());
		}

		@Test
		void errorsInEvalCodePointAtTheScriptLine() {
			ScriptException e = failure(ScriptException.Kind.RUNTIME, "const a = 1;\neval('\\n\\nnull.x');");
			assertEquals(2, e.line());
			assertTrue(e.getMessage().startsWith("TypeError: Cannot read property \"x\" from null (line 2:"), e.getMessage());
		}

		@Test
		void longSourceLinesAreTrimmedTo120Characters() {
			String line = "const veryLongName = [" + "1, ".repeat(60) + "]; undefinedThing();";
			ScriptException e = failure(ScriptException.Kind.RUNTIME, line);
			String suffix = e.getMessage().substring(e.getMessage().indexOf("(line 1: ") + 9);
			assertEquals(line.substring(0, 117) + "...)", suffix);
		}
	}
}
