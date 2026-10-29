package io.github.saocsb.mcai.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkFilterTest {
	private static String filter(String... chunks) {
		ThinkFilter f = new ThinkFilter();
		for (String c : chunks) {
			f.append(c);
		}
		return f.finalText();
	}

	@Test
	void plainTextIsUntouched() {
		assertEquals("Hello <b>world</b>", filter("Hello ", "<b>world</b>"));
		assertEquals("  indented", filter("  ", "indented"));
	}

	@Test
	void stripsLeadingThinkBlock() {
		assertEquals("Answer", filter("<think>reasoning</think>\n\nAnswer"));
		assertEquals("Answer", filter("\n<think>\nreasoning\n</think>\nAnswer"));
	}

	@Test
	void handlesTagsSplitAcrossChunks() {
		ThinkFilter f = new ThinkFilter();
		for (String c : new String[] {"<", "thi", "nk>pon", "dering</th", "ink", ">", "\n\n", "Hel", "lo"}) {
			f.append(c);
			assertTrue(!f.visibleText().contains("pon") && !f.visibleText().contains("<"), f.visibleText());
		}
		assertEquals("Hello", f.finalText());
		assertEquals("pondering".length(), f.reasoningChars());
	}

	@Test
	void routesOpenThinkToReasoningWhileStreaming() {
		ThinkFilter f = new ThinkFilter();
		f.append("<think>still thinking");
		assertEquals("", f.visibleText());
		assertEquals("still thinking".length(), f.reasoningChars());
		assertEquals("", f.finalText());
	}

	@Test
	void notAThinkTag() {
		assertEquals("<thinking>x", filter("<thi", "nking>x"));
		assertEquals("<thi", filter("<thi"));
	}

	@Test
	void removesLaterBlocksAndOrphanCloseAtTheEnd() {
		assertEquals("A B", filter("A <think>x</think>B"));
		assertEquals("Answer", filter("reasoning without open tag</think>\nAnswer"));
	}
}
