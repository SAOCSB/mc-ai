package io.github.saocsb.mcai.core.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SourceRewriterTest {
	private static void assertRewrite(String expected, String source) {
		assertEquals(expected, SourceRewriter.rewrite(source));
	}

	@Test
	void constBecomesLet() {
		assertRewrite("let a = 1;\nfor (let x of xs) {}\nfor (let k in o) {}", "const a = 1;\nfor (const x of xs) {}\nfor (const k in o) {}");
		assertRewrite("let {a, b} = o; let [c] = d;", "const {a, b} = o; const [c] = d;");
	}

	@Test
	void bareLetGetsAnInitializer() {
		assertRewrite("let a = undefined;", "let a;");
		assertRewrite("let a = undefined, b = 2, c = undefined;", "let a, b = 2, c;");
		assertRewrite("let a = f(1, 2), b = undefined;", "let a = f(1, 2), b;");
		assertRewrite("let a = undefined\nfoo()", "let a\nfoo()");
		assertRewrite("{ let h = undefined }", "{ let h }");
		assertRewrite("for (let i; i < 3; i++) {}", "for (let i; i < 3; i++) {}");
		assertRewrite("let a\n= 5;", "let a\n= 5;");
		assertRewrite("let f = function () { let x = undefined; }, g = undefined;", "let f = function () { let x; }, g;");
	}

	@Test
	void stringsCommentsTemplatesAndRegexAreLeftAlone() {
		String src = "log('const a'); log(\"let b;\"); // const c\n/* const d; f(...x) */ log(`const ${'e'}`); const r = /const f(...x)/g;";
		assertRewrite(src.substring(0, src.lastIndexOf("const r")) + "let r = /const f(...x)/g;", src);
		assertRewrite("x = a / b / c; let y = 1;", "x = a / b / c; const y = 1;");
		assertRewrite("log(`${(() => { let z = 1; return z; })()}`)", "log(`${(() => { const z = 1; return z; })()}`)");
	}

	@Test
	void propertiesNamedConstAreLeftAlone() {
		assertRewrite("o.const = 1; x = {const: 2};", "o.const = 1; x = {const: 2};");
	}

	@Test
	void spreadCallsBecomeApply() {
		assertRewrite("Math.max.apply(Math, [...xs])", "Math.max(...xs)");
		assertRewrite("f.apply(void 0, [a, ...b, c])", "f(a, ...b, c)");
		assertRewrite("a.b.c.apply(a.b, [...xs,])", "a.b.c(...xs,)");
		assertRewrite("this.m.apply(this, [...xs])", "this.m(...xs)");
		assertRewrite("f.apply(void 0, [g.apply(void 0, [...a]), ...b])", "f(g(...a), ...b)");
		assertRewrite("block.apply(void 0, [...p, 'stone'])", "block(...p, 'stone')");
	}

	@Test
	void restParametersAndUnsupportedCalleesAreLeftAlone() {
		String[] unchanged = {
				"function f(...args) { return args; }",
				"let g = (...args) => args;",
				"let o = { m(...args) { return 1; } };",
				"new Foo(...args)",
				"f()(...args)",
				"a[0](...args)",
				"x?.(...args)",
				"f([...a], {...b})",
		};
		for (String src : unchanged) {
			assertSame(src, SourceRewriter.rewrite(src), src);
		}
		assertRewrite("f.apply(void 0, [...[1, 2]]).g(...xs)", "f(...[1, 2]).g(...xs)");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "block(0,0,0,'stone')", "'unterminated", "`unterminated ${", "/* open", "x = /[/]/; const y = 1",
			"const", "let", "f(...", ")))", "}}} const a = `${`${'x'}`}`;"})
	void neverChangesLineCount(String src) {
		assertEquals(src.split("\n", -1).length, SourceRewriter.rewrite(src).split("\n", -1).length);
	}

	@Test
	void randomInputKeepsLineCount() {
		String alphabet = "const let f(...x) {}[]`${}'\"/\\*\n;,.=ab1 ";
		Random random = new Random(7);
		for (int i = 0; i < 2000; i++) {
			StringBuilder sb = new StringBuilder();
			int len = random.nextInt(60);
			for (int j = 0; j < len; j++) {
				sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
			}
			String src = sb.toString();
			String out = SourceRewriter.rewrite(src);
			assertEquals(src.chars().filter(c -> c == '\n').count(), out.chars().filter(c -> c == '\n').count(), src);
		}
	}
}
