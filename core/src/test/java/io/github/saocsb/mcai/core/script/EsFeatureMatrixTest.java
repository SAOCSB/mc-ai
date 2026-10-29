package io.github.saocsb.mcai.core.script;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.github.saocsb.mcai.core.script.ScriptTestSupport.ENGINE;
import static io.github.saocsb.mcai.core.script.ScriptTestSupport.SEED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Which JavaScript features build scripts can use (Rhino 1.9.2, ES6 mode, plus the engine's source
 * rewrites). Each snippet logs "ok" when the feature works. Unsupported features are asserted too, so
 * a Rhino upgrade that adds them shows up here.
 */
class EsFeatureMatrixTest {
	static Stream<Arguments> supported() {
		return Stream.of(
				Arguments.of("let/const", "let a = 1; const b = 2; { let a = 5; } ok(a + b === 3);"),
				Arguments.of("const inside loop body (rewritten to let)", "const s = []; for (let i = 0; i < 3; i++) { const y = i * 2; s.push(y); } ok(s.join() === '0,2,4');"),
				Arguments.of("uninitialized let inside loop", "const s = []; for (let i = 0; i < 3; i++) { let y; if (i === 1) y = 5; s.push(y); } ok(s.join() === ',5,');"),
				Arguments.of("arrow functions", "ok([1, 2, 3].map(x => x * 2).join() === '2,4,6');"),
				Arguments.of("arrow functions keep this", "const o = {v: 1, f() { return [1].map(() => this.v)[0]; }}; ok(o.f() === 1);"),
				Arguments.of("template literals", "const n = 3; ok(`a${n + 1}b` === 'a4b' && `x\ny`.length === 3);"),
				Arguments.of("tagged templates / String.raw", "ok(String.raw`a\\n` === 'a\\\\n');"),
				Arguments.of("array destructuring", "const [a, , b = 7] = [1, 2]; ok(a === 1 && b === 7);"),
				Arguments.of("object destructuring", "const {x, y: why, z = 3} = {x: 1, y: 2}; ok(x + why + z === 6);"),
				Arguments.of("destructuring parameters", "function f({a, b} = {}, [c] = [3]) { return a + b + c; } ok(f({a: 1, b: 2}) === 6);"),
				Arguments.of("destructuring swap", "let a = 1, b = 2; [a, b] = [b, a]; ok(a === 2 && b === 1);"),
				Arguments.of("default parameters", "function f(a, b = a * 2) { return a + b; } ok(f(1) === 3);"),
				Arguments.of("rest parameters", "function f(first, ...rest) { return rest.length; } ok(f(1, 2, 3) === 2);"),
				Arguments.of("spread in arrays", "ok([...[1, 2], 3].length === 3 && [...'ab'].length === 2);"),
				Arguments.of("spread in objects", "const o = {...{a: 1}, b: 2}; ok(o.a + o.b === 3);"),
				Arguments.of("spread in calls (rewritten to apply)", "const p = [1, 5, 3]; const v = {k: {f(a, b) { return this === v.k && a + b; }}}; ok(Math.max(...p) === 5 && v.k.f(...[1, 2]) === 3);"),
				Arguments.of("for...of with let/const", "let s = 0; for (const v of [1, 2, 3]) s += v; for (let c of 'ab') s += c.length; ok(s === 8);"),
				Arguments.of("for...of with destructuring", "let s = 0; for (const [k, v] of [[1, 2], [3, 4]]) s += k * v; ok(s === 14);"),
				Arguments.of("for...in with const", "let s = ''; for (const k in {a: 1, b: 2}) s += k; ok(s === 'ab');"),
				Arguments.of("Map / Set", "const m = new Map([[1, 'a']]); m.set(2, 'b'); const s = new Set([1, 1, 2]); let n = 0; for (const [k] of m) n += k; ok(m.get(2) === 'b' && s.size === 2 && n === 3);"),
				Arguments.of("WeakMap / Symbol", "const w = new WeakMap(); const k = {}; w.set(k, 1); ok(w.get(k) === 1 && typeof Symbol.iterator === 'symbol');"),
				Arguments.of("generators", "function* g() { yield 1; yield 2; } let s = 0; for (const v of g()) s += v; ok(s === 3);"),
				Arguments.of("Array.from / Array.of", "ok(Array.from({length: 3}, (_, i) => i).join() === '0,1,2' && Array.of(7).length === 1);"),
				Arguments.of("Array.prototype.fill/find/findIndex/includes", "ok(new Array(3).fill(0).join() === '0,0,0' && [1, 2, 3].find(x => x > 1) === 2 && [1, 2].findIndex(x => x === 2) === 1 && [1, 2].includes(2));"),
				Arguments.of("Array.prototype.flat/flatMap/at/findLast", "ok([[1], [2]].flat().length === 2 && [1].flatMap(x => [x, x]).length === 2 && [1, 2, 3].at(-1) === 3 && [1, 2, 3].findLast(x => x < 3) === 2);"),
				Arguments.of("Math.hypot/sign/trunc/cbrt/log2", "ok(Math.hypot(3, 4) === 5 && Math.sign(-2) === -1 && Math.trunc(1.7) === 1 && Math.cbrt(27) === 3 && Math.log2(8) === 3);"),
				Arguments.of("String padStart/padEnd/repeat/startsWith/includes", "ok('5'.padStart(3, '0') === '005' && 'a'.padEnd(2, 'b') === 'ab' && 'ab'.repeat(2) === 'abab' && 'abc'.startsWith('a') && 'abc'.includes('b'));"),
				Arguments.of("String replaceAll/trimStart/at", "ok('aXbX'.replaceAll('X', '-') === 'a-b-' && ' a'.trimStart() === 'a' && 'abc'.at(-1) === 'c');"),
				Arguments.of("exponent operator", "let x = 2; x **= 3; ok(2 ** 10 === 1024 && x === 8);"),
				Arguments.of("Object.entries/values/assign/fromEntries", "ok(Object.entries({a: 1})[0][1] === 1 && Object.values({a: 1, b: 2}).length === 2 && Object.assign({}, {a: 1}).a === 1 && Object.fromEntries([['a', 1]]).a === 1);"),
				Arguments.of("shorthand properties and methods", "const a = 1; const o = {a, m() { return 2; }, get g() { return 3; }}; ok(o.a + o.m() + o.g === 6);"),
				Arguments.of("computed property names", "const k = 'x'; ok(({[k + 1]: 1}).x1 === 1);"),
				Arguments.of("optional chaining / nullish coalescing", "const o = {}; let n = null; n ??= 4; ok(o?.a?.b === undefined && (null ?? 5) === 5 && n === 4);"),
				Arguments.of("numeric separators / Number.isInteger", "ok(1_000 === 1000 && Number.isInteger(5) && Number.EPSILON > 0);"),
				Arguments.of("block-level function declarations", "{ function q() { return 1; } } ok(q() === 1);"),
				Arguments.of("labels, break and continue", "let n = 0; outer: for (let i = 0; i < 3; i++) { for (let j = 0; j < 3; j++) { if (j === 1) continue outer; n++; } } ok(n === 3);"),
				Arguments.of("JSON", "ok(JSON.stringify({a: [1, 'x']}) === '{\"a\":[1,\"x\"]}' && JSON.parse('[1,2]')[1] === 2);"),
				Arguments.of("regular expressions", "ok(/(\\d+)-(\\d+)/.exec('10-20')[2] === '20' && 'a,b'.split(/,/).length === 2);"),
				Arguments.of("Promise object exists", "ok(typeof Promise === 'function');"),
				Arguments.of("Object.groupBy", "ok(Object.groupBy([1, 2, 3], x => x % 2 ? 'odd' : 'even').odd.length === 2);"),
				Arguments.of("arguments object", "function f() { return Array.from(arguments).length + Array.prototype.slice.call(arguments, 1).length + arguments.length; } ok(f(1, 2, 3) === 8);"),
				Arguments.of("Array.from on array-like objects", "ok(Array.from({length: 2, 0: 'a', 1: 'b'}).join('') === 'ab');"),
				Arguments.of("Date and Math.random (seeded)", "ok(typeof Date.now() === 'number' && Math.random() < 1);"),
				Arguments.of("strict mode", "'use strict'; let threw = false; try { undeclaredVar = 1; } catch (e) { threw = true; } ok(threw);"));
	}

	static Stream<Arguments> unsupported() {
		return Stream.of(
				Arguments.of("classes", "class A { constructor(x) { this.x = x; } } ok(new A(1).x === 1);"),
				Arguments.of("class inheritance", "class A {} class B extends A {} ok(true);"),
				Arguments.of("async functions", "async function f() { return 1; } ok(true);"),
				Arguments.of("per-iteration let binding in for loops", "const fs = []; for (let i = 0; i < 3; i++) fs.push(() => i); ok(fs.map(f => f()).join() === '0,1,2');"),
				Arguments.of("spread in calls on computed callees", "const a = [[1, 2]]; function f(x, y) { return x + y; } const o = {g: () => f}; ok(o.g()(...a[0]) === 3);"),
				Arguments.of("spread in new expressions", "ok(new Array(...[3]).length === 3);"),
				Arguments.of("rest element in array destructuring", "const [first, ...others] = [1, 2, 3]; ok(others.length === 2);"),
				Arguments.of("rest properties in object destructuring", "const {a, ...rest} = {a: 1, b: 2}; ok(rest.b === 2);"),
				Arguments.of("Array.prototype methods on array-like objects", "ok([].map.call({length: 2, 0: 'a', 1: 'b'}, x => x).length === 2);"),
				Arguments.of("typed arrays", "ok(new Uint8Array(4).length === 4);"),
				Arguments.of("Proxy", "ok(new Proxy({}, {}) !== null);"),
				Arguments.of("const reassignment throws", "const z = 1; let threw = false; try { z = 2; } catch (e) { threw = true; } ok(threw);"));
	}

	private static String outcome(String snippet) {
		String script = snippet + "\nfunction ok(v) { log(v ? 'ok' : 'wrong'); }";
		try {
			ScriptResult r = ENGINE.run(script, ScriptLimits.defaults(), SEED);
			return String.join(",", r.logs());
		} catch (ScriptException e) {
			return e.kind() + ": " + e.getMessage();
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("supported")
	void supportedFeature(String name, String snippet) {
		assertEquals("ok", outcome(snippet), name);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("unsupported")
	void unsupportedFeature(String name, String snippet) {
		assertNotEquals("ok", outcome(snippet), name);
	}
}
