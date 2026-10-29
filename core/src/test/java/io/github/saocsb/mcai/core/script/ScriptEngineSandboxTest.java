package io.github.saocsb.mcai.core.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.WrapFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.github.saocsb.mcai.core.script.ScriptTestSupport.ENGINE;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.SEED;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.entries;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.failure;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.log;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Attempts to reach Java or the host from a script must fail. */
class ScriptEngineSandboxTest {
	@ParameterizedTest
	@ValueSource(strings = {"java", "javax", "org", "com", "edu", "net", "Packages", "JavaAdapter", "JavaImporter",
			"importPackage", "importClass", "getClass", "load", "loadClass", "Continuation", "Script", "JavaException",
			"With", "Call", "isXMLName", "readFile", "runCommand", "spawn", "sync", "quit", "XML", "Intl", "Proxy",
			"ArrayBuffer", "SharedArrayBuffer", "DataView", "Uint8Array", "Float64Array", "require", "setTimeout"})
	void hostGlobalsAreAbsent(String name) {
		assertEquals("undefined", log("log(typeof " + name + ")"));
		assertEquals("false", log("log(Object.getOwnPropertyNames(this).includes('" + name + "'))"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"java.lang.System.exit(1)",
			"new java.io.File('/etc/passwd')",
			"Packages.java.lang.Runtime.getRuntime().exec('ls')",
			"String(java)",
			"Function('return Packages')()",
			"eval('java.lang.System')",
			"new Function('return javax')()",
			"importPackage(java.io)",
			"JavaImporter(java.io)",
			"({}).constructor.constructor('return java')()",
	})
	void javaAccessIsAReferenceError(String script) {
		ScriptException e = failure(ScriptException.Kind.RUNTIME, script);
		assertTrue(e.getMessage().startsWith("ReferenceError: "), e.getMessage());
	}

	@Test
	void theGlobalObjectIsReachableButHasNoJava() {
		assertEquals("true undefined undefined undefined", log("""
				const g = ({}).constructor.constructor('return this')();
				log(g === this.constructor.constructor('return this')(), typeof g.Packages, typeof g.java, typeof g.getClass);
				"""));
		assertEquals("true", log("log(globalThis === Function('return this')())"));
	}

	@Test
	void apiFunctionsExposeNoJavaMembers() {
		assertEquals("undefined undefined undefined undefined undefined undefined", log(
				"log(typeof block.getClass, typeof block.class, typeof block['class'], typeof block.wait, typeof block.notify, typeof block.hashCode)"));
		assertEquals("true true true", log("log(block.__proto__ === Function.prototype, block.constructor === Function, Object.getPrototypeOf(console) === Object.prototype)"));
		assertEquals("false", log("const s = String(block) + String(console.log) + Object.getOwnPropertyNames(block).join(); log(/java|mozilla|lambda/i.test(s))"));
		assertEquals("undefined", log("log(block.__proto__.constructor('return typeof java')())"));
		assertEquals("ok", log("const f = block.bind(null); f(0, 0, 0, 'stone'); block.call(null, 1, 0, 0, 'stone'); block.apply(null, [2, 0, 0, 'stone']); log('ok')"));
	}

	@Test
	void errorObjectsExposeNoJava() {
		String out = log("""
				let info = [];
				try { null.x; } catch (e) {
					info.push(typeof e.rhinoException, typeof e.javaException, String(e.stack));
				}
				try { block(0, 0, 0); } catch (e) {
					info.push(typeof e.rhinoException, typeof e.javaException, String(e.stack));
				}
				info.push(String(new Error('x').stack));
				log(info.join('|').replace(/\\n/g, ' '));
				""");
		assertTrue(out.startsWith("undefined|undefined|"), out);
		assertTrue(out.contains("|undefined|undefined|"), out);
		assertFalse(out.matches("(?is).*(java|mozilla|org\\.).*"), out);
	}

	@Test
	void stackTraceHooksExposeNoJava() {
		String out = log("""
				let parts = [];
				Error.prepareStackTrace = (err, frames) => {
					for (const f of frames) {
						parts.push(typeof f, String(f));
						for (const m of ['getThis', 'getFunction', 'getTypeName', 'getFileName', 'getFunctionName']) {
							try { const v = f[m] ? f[m]() : undefined; parts.push(m + '=' + typeof v + ':' + String(v)); } catch (e) { parts.push(m + '!' + e.name); }
						}
					}
					return 'custom';
				};
				const e = new Error('x');
				parts.push(String(e.stack));
				const o = {};
				if (typeof Error.captureStackTrace === 'function') { Error.captureStackTrace(o); parts.push(String(o.stack)); }
				log(parts.join('|'));
				""");
		assertFalse(out.matches("(?is).*(java\\.|mozilla|org\\.|class ).*"), out);
	}

	@Test
	void reflectionStyleTricksFindNothing() {
		assertEquals("ok", log("""
				const seen = new Set();
				const bad = [];
				function walk(o, path, depth) {
					if (o === null || (typeof o !== 'object' && typeof o !== 'function') || seen.has(o) || depth > 4) return;
					seen.add(o);
					for (const k of Object.getOwnPropertyNames(o)) {
						if (/^(java|javax|Packages|getClass|JavaAdapter|JavaImporter|importPackage|importClass)$/.test(k)) bad.push(path + '.' + k);
						let v;
						try { v = o[k]; } catch (e) { continue; }
						walk(v, path + '.' + k, depth + 1);
					}
					walk(Object.getPrototypeOf(o), path + '.__proto__', depth + 1);
				}
				walk(this, 'global', 0);
				log(bad.length === 0 ? 'ok' : bad.join());
				"""));
	}

	@Test
	void classShutterViolationIsARuntimeError() throws Exception {
		// Rhino's standard wrapping consults the ClassShutter, which denies every class
		ScriptException e = org.junit.jupiter.api.Assertions.assertThrows(ScriptException.class,
				() -> ENGINE.run("const f = leak();\nlog(f.getPath());", ScriptLimits.defaults(), SEED, scope ->
						ScriptableObject.defineProperty(scope, "leak", new LambdaFunction(scope, "leak", 0,
										(cx, s, thisObj, args) -> new WrapFactory().wrap(cx, s, new File("/etc/passwd"), (Class<?>) null)),
								ScriptableObject.DONTENUM)));
		assertEquals(ScriptException.Kind.RUNTIME, e.kind());
		assertTrue(e.getMessage().startsWith("Error: Access to Java class \"java.io.File\" is prohibited."), e.getMessage());
		assertEquals(1, e.line());
		// the engine's own wrap factory refuses Java objects before the ClassShutter is even asked
		ScriptException e2 = org.junit.jupiter.api.Assertions.assertThrows(ScriptException.class,
				() -> ENGINE.run("leak();", ScriptLimits.defaults(), SEED, scope ->
						ScriptableObject.defineProperty(scope, "leak", new LambdaFunction(scope, "leak", 0,
										(cx, s, thisObj, args) -> Context.javaToJS(new File("/etc/passwd"), s)),
								ScriptableObject.DONTENUM)));
		assertEquals(ScriptException.Kind.RUNTIME, e2.kind());
		assertTrue(e2.getMessage().startsWith("Error: Java objects are not accessible to scripts (line 1:"), e2.getMessage());
		// the error can be caught by the script and does not leak the object
		ScriptResult r = ENGINE.run("let v = 'none';\ntry { v = leak(); } catch (err) { log(err.name); }\nlog(typeof v);", ScriptLimits.defaults(), SEED,
				scope -> ScriptableObject.defineProperty(scope, "leak", new LambdaFunction(scope, "leak", 0,
								(cx, s, thisObj, args) -> Context.javaToJS(new File("/etc/passwd"), s)),
						ScriptableObject.DONTENUM));
		assertEquals("string", r.logs().get(r.logs().size() - 1));
	}

	@Test
	void hostFunctionsAreNotConstructors() {
		ScriptException e = failure(ScriptException.Kind.RUNTIME, "new block(0, 0, 0, 'stone')");
		assertTrue(e.getMessage().startsWith("TypeError: "), e.getMessage());
		assertEquals("undefined", log("log(typeof block.prototype)"));
	}

	@Test
	void runsDoNotShareState() {
		run("""
				Object.prototype.polluted = 1;
				Array.prototype.push = function () { return 'hacked'; };
				Math.PI = 3;
				globalThis.leftover = 42;
				block = function () {};
				""");
		assertEquals("undefined 1 3.141592653589793 undefined function 1", log(
				"const a = []; log(typeof ({}).polluted, a.push(1), Math.PI, typeof leftover, typeof block, (block(0, 0, 0, 'stone'), get(0, 0, 0) ? 1 : 0))"));
	}

	@Test
	void parallelRunsAreIndependent() throws Exception {
		String script = """
				for (let i = 0; i < 2000; i++) block(randInt(0, 40), randInt(0, 40), randInt(0, 40), pick(['stone', 'dirt', 'glass']));
				setName('run ' + randInt(0, 1000000));
				log(random());
				""";
		List<List<String>> expected = new ArrayList<>();
		for (int seed = 0; seed < 8; seed++) {
			ScriptResult r = ScriptTestSupport.run(script, ScriptLimits.defaults(), seed);
			expected.add(List.of(String.valueOf(entries(r.blueprint())), r.name(), String.valueOf(r.logs())));
		}
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Future<List<String>>> futures = new ArrayList<>();
			for (int round = 0; round < 3; round++) {
				for (int seed = 0; seed < 8; seed++) {
					long s = seed;
					futures.add(pool.submit(() -> {
						ScriptResult r = ENGINE.run(script, ScriptLimits.defaults(), s);
						return List.of(String.valueOf(entries(r.blueprint())), r.name(), String.valueOf(r.logs()));
					}));
				}
			}
			for (int i = 0; i < futures.size(); i++) {
				assertEquals(expected.get(i % 8), futures.get(i).get());
			}
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void runningInsideAnActiveContextIsRejected() {
		try (Context ignored = Context.enter()) {
			org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
					() -> ENGINE.run("log(1)", ScriptLimits.defaults(), SEED));
		}
		assertEquals("1", log("log(1)"));
	}
}
