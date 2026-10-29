package io.github.saocsb.mcai.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FenceTrackerTest {
	@Test
	void countsScriptLinesAsTheReplyGrows() {
		FenceTracker t = new FenceTracker();
		assertEquals(-1, t.update(""));
		assertEquals(-1, t.update("Sure! "));
		assertEquals(-1, t.update("Sure! Here:\n```js"));
		assertFalse(t.writing());
		assertEquals(0, t.update("Sure! Here:\n```js\n"));
		assertTrue(t.writing());
		assertEquals(1, t.update("Sure! Here:\n```js\nfill("));
		assertEquals(1, t.update("Sure! Here:\n```js\nfill();\n"));
		assertEquals(3, t.update("Sure! Here:\n```js\nfill();\n\nblock("));
		assertEquals(3, t.update("Sure! Here:\n```js\nfill();\n\nblock();\n``"));
		assertEquals(3, t.update("Sure! Here:\n```js\nfill();\n\nblock();\n```\nEnjoy\nmore chat\n"));
		assertEquals(4, t.update("Sure! Here:\n```js\nfill();\n\nblock();\n```\nEnjoy\nmore chat\n```js\nx();\n"));
	}

	@Test
	void nonScriptBlocksDoNotCount() {
		FenceTracker t = new FenceTracker();
		assertEquals(-1, t.update("```json\n{\n}\n```\n"));
		assertEquals(0, t.update("```json\n{\n}\n```\n~~~\n"));
	}

	@Test
	void crlfAndReset() {
		FenceTracker t = new FenceTracker();
		assertEquals(2, t.update("```js\r\na();\r\nb();\r\n"));
		assertEquals(-1, t.update("new"));
	}
}
