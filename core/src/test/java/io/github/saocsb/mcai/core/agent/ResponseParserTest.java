package io.github.saocsb.mcai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseParserTest {
	private static ParsedResponse parse(String reply) {
		return ResponseParser.parse(reply);
	}

	@Test
	void plainChat() {
		assertEquals(new ParsedResponse("Hello there!", null, false), parse("  Hello there!  \n"));
	}

	@Test
	void nullAndEmpty() {
		assertEquals(new ParsedResponse("", null, false), parse(null));
		assertEquals(new ParsedResponse("", null, false), parse(""));
		assertEquals(new ParsedResponse("", null, false), parse(" \n\n "));
	}

	@Test
	void scriptWithTextBeforeAndAfter() {
		ParsedResponse p = parse("Sure!\n```js\nfill(0, 0, 0, 1, 1, 1, 'stone');\nblock(0, 2, 0, 'torch');\n```\nEnjoy!");
		assertEquals("Sure!\nEnjoy!", p.chatText());
		assertEquals("fill(0, 0, 0, 1, 1, 1, 'stone');\nblock(0, 2, 0, 'torch');", p.script());
		assertFalse(p.unterminatedCode());
	}

	@Test
	void onlyACodeBlockGivesEmptyChat() {
		assertEquals(new ParsedResponse("", "a();", false), parse("```js\na();\n```"));
		assertEquals(new ParsedResponse("", "a();", false), parse("\n\n```js\na();\n```\n\n"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"js", "javascript", "JS", "JavaScript", "mcai", "build", "", "js title=\"tower.js\"",
			"javascript {.numberLines}", " js"})
	void scriptLanguageTags(String tag) {
		ParsedResponse p = parse("Here:\n```" + tag + "\nbuild();\n```\nDone");
		assertEquals("build();", p.script());
		assertEquals("Here:\nDone", p.chatText());
	}

	@ParameterizedTest
	@ValueSource(strings = {"json", "text", "bash", "python", "java", "txt", "jsonc", "sh"})
	void otherLanguagesAreDropped(String tag) {
		ParsedResponse p = parse("Config:\n```" + tag + "\n{\"a\": 1}\n```\nThat is all.");
		assertNull(p.script());
		assertEquals("Config:\nThat is all.", p.chatText());
		assertFalse(p.unterminatedCode());
	}

	@Test
	void tildeFences() {
		ParsedResponse p = parse("Hi\n~~~js\nfill();\n~~~\nBye");
		assertEquals("fill();", p.script());
		assertEquals("Hi\nBye", p.chatText());
	}

	@Test
	void longerFencesMayContainShorterOnes() {
		ParsedResponse p = parse("````js\nconst s = `\n```\n`;\n````");
		assertEquals("const s = `\n```\n`;", p.script());
		assertFalse(p.unterminatedCode());
	}

	@Test
	void closingFenceMustBeAtLeastAsLong() {
		assertEquals("a\n```\nb", parse("````js\na\n```\nb\n````").script());
		assertEquals("a", parse("```js\na\n`````\nafter").script());
		assertEquals("after", parse("```js\na\n`````\nafter").chatText());
	}

	@Test
	void closingFenceMustUseTheSameCharacter() {
		assertEquals("a\n~~~\nb", parse("```js\na\n~~~\nb\n```").script());
	}

	@Test
	void closingFenceMayHaveTrailingSpacesButNoText() {
		assertEquals("a", parse("```js\na\n```   \nchat").script());
		ParsedResponse p = parse("```js\na\n``` not a close\nb\n```");
		assertEquals("a\n``` not a close\nb", p.script());
	}

	@Test
	void fencesIndentedUpToThreeSpaces() {
		ParsedResponse p = parse("Steps:\n   ```js\n   fill();\n     inner();\n  short();\n   ```\nok");
		assertEquals("fill();\n  inner();\nshort();", p.script());
		assertEquals("Steps:\nok", p.chatText());
		assertEquals("x();", parse(" ```js\nx();\n ```").script());
	}

	@Test
	void fourSpacesIsNotAFence() {
		ParsedResponse p = parse("    ```js\n    x();\n    ```");
		assertNull(p.script());
		assertFalse(p.unterminatedCode());
	}

	@Test
	void backtickInInfoStringIsNotAFence() {
		ParsedResponse p = parse("```a`b\nstill chat");
		assertNull(p.script());
		assertEquals("```a`b\nstill chat", p.chatText());
	}

	@Test
	void crlfLineEndings() {
		ParsedResponse p = parse("Hi\r\n```js\r\nfill();\r\nblock();\r\n```\r\nBye\r\n");
		assertEquals("fill();\nblock();", p.script());
		assertEquals("Hi\nBye", p.chatText());
	}

	@Test
	void multipleScriptBlocksAreJoined() {
		ParsedResponse p = parse("First:\n```js\na();\n```\nThen some JSON:\n```json\n{}\n```\nAnd:\n```javascript\nb();\nc();\n```");
		assertEquals("a();\nb();\nc();", p.script());
		assertEquals("First:\nThen some JSON:\nAnd:", p.chatText());
	}

	@Test
	void unterminatedScriptIsReturnedPartially() {
		ParsedResponse p = parse("Here:\n```js\nfill(0, 0, 0,\n  1, 1");
		assertEquals("fill(0, 0, 0,\n  1, 1", p.script());
		assertEquals("Here:", p.chatText());
		assertTrue(p.unterminatedCode());
		assertEquals("a();", parse("```js\na();\n").script());
		assertEquals("", parse("```js").script());
		assertTrue(parse("```js").unterminatedCode());
	}

	@Test
	void unterminatedNonScriptBlock() {
		ParsedResponse p = parse("Data:\n```json\n{\"a\":");
		assertNull(p.script());
		assertTrue(p.unterminatedCode());
		assertEquals("Data:", p.chatText());
	}

	@Test
	void thinkSectionsAreRemoved() {
		assertEquals(new ParsedResponse("OK", "a();", false), parse("<think>I should build\n```js\nx();\n```\n</think>\nOK\n```js\na();\n```"));
		assertEquals("OK", parse("<thinking>plan</thinking>OK").chatText());
		assertEquals("OK", parse("<THINK>plan</THINK>\n\nOK").chatText());
		assertEquals("Before\n\nafter", parse("Before\n<think>middle</think>\n\nafter").chatText());
	}

	@Test
	void unclosedThinkAtStartIsRemoved() {
		assertEquals(new ParsedResponse("", null, false), parse("  <think>still reasoning about ```js\nfill();"));
		assertEquals("Hello", parse("reasoning without an opening tag</think>\nHello").chatText());
	}

	@Test
	void inlineCodeIsKept() {
		assertEquals("Use `fill()` and `**kwargs` or `__init__`, ok?", parse("Use `fill()` and `**kwargs` or `__init__`, ok?").chatText());
	}

	@Test
	void markdownEmphasisAndHeadingsAreRemoved() {
		String reply = "# Plan\n**Done!** Here is __your__ house.\n## Details ##\nA * B and snake_case and 2 * 3\n### \nend";
		assertEquals("Plan\nDone! Here is your house.\nDetails\nA * B and snake_case and 2 * 3\n\nend", parse(reply).chatText());
	}

	@Test
	void emphasisSpanningInlineCode() {
		assertEquals("Use `fill` for walls", parse("**Use `fill` for walls**").chatText());
	}

	@Test
	void unpairedMarkersStay() {
		assertEquals("2**10 is big", parse("2**10 is big").chatText());
		assertEquals("my__var stays", parse("my__var stays").chatText());
	}

	@Test
	void chineseText() {
		assertEquals("好的！我来帮你建一座塔。", parse("好的！我来帮你建一座**塔**。").chatText());
		assertEquals("这是 重点 内容", parse("这是 __重点__ 内容").chatText());
		assertEquals("「城堡」建好了！\n\n输入 /ai undo 可撤销。", parse("「城堡」建好了！\n\n\n\n输入 /ai undo 可撤销。").chatText());
	}

	@Test
	void blankLineRunsCollapse() {
		assertEquals("A\n\nB", parse("A\n\n\n\nB").chatText());
		assertEquals("A\n\nB", parse("A\n  \n \t\n\nB").chatText());
		assertEquals("Here:\n\nEnjoy!", parse("Here:\n\n```js\na();\n```\n\nEnjoy!").chatText());
	}

	@Test
	void hasCodeBlock() {
		assertTrue(ResponseParser.hasCodeBlock("a\n```js\nx\n```"));
		assertTrue(ResponseParser.hasCodeBlock("~~~\nx"));
		assertFalse(ResponseParser.hasCodeBlock("inline `code` only"));
		assertFalse(ResponseParser.hasCodeBlock(null));
	}

	@Test
	void replaceCodeBlocks() {
		assertEquals("Hi\n[code]\nmid\n[code]\nend",
				ResponseParser.replaceCodeBlocks("Hi\n```js\na();\n```\nmid\n```json\n{}\n```\nend", "[code]"));
		assertEquals("Hi\n[code]", ResponseParser.replaceCodeBlocks("Hi\n```js\na();\nb(", "[code]"));
		assertEquals("no code", ResponseParser.replaceCodeBlocks("no code", "[code]"));
	}

	@Test
	void openingFenceDetails() {
		ResponseParser.Fence f = ResponseParser.openingFence("  ````JavaScript title");
		assertEquals(new ResponseParser.Fence('`', 4, 2, "javascript", true), f);
		assertNull(ResponseParser.openingFence("``"));
		assertNull(ResponseParser.openingFence(""));
		assertFalse(ResponseParser.openingFence("~~~text").script());
	}
}
