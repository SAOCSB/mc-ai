package io.github.saocsb.mcai.core.blueprint;

import io.github.saocsb.mcai.core.blueprint.RecordingSink.Pos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixelFontTest {
	private static final String SUPPORTED = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,!?-+:'\"/()=_#&*<>♥";
	private static final String ID = "minecraft:gold_block";

	private static RecordingSink draw(String text, int scale) {
		RecordingSink sink = new RecordingSink();
		PixelFont.draw(sink, text, 0, 0, 0, ID, scale);
		return sink;
	}

	/** Glyph pixels as (column, row) with row 0 at the top, for a single character drawn at the origin. */
	private static Set<String> pixels(char c) {
		Set<String> out = new HashSet<>();
		for (Pos p : draw(String.valueOf(c), 1).positions()) {
			out.add(p.x() + "," + (PixelFont.GLYPH_HEIGHT - 1 - p.y()));
		}
		return out;
	}

	@Test
	void everySupportedCharacterHasAGlyph() {
		Map<Set<String>, Character> seen = new HashMap<>();
		for (char c : SUPPORTED.toCharArray()) {
			assertTrue(PixelFont.supports(c), "supports " + c);
			RecordingSink sink = draw(String.valueOf(c), 1);
			for (Pos p : sink.positions()) {
				assertTrue(p.x() >= 0 && p.x() < PixelFont.GLYPH_WIDTH, c + " pixel outside the glyph: " + p);
				assertTrue(p.y() >= 0 && p.y() < PixelFont.GLYPH_HEIGHT, c + " pixel outside the glyph: " + p);
				assertEquals(0, p.z());
			}
			assertFalse(sink.hasDuplicateWrites());
			if (c == ' ') {
				assertEquals(0, sink.writeCount());
				continue;
			}
			assertTrue(sink.writeCount() >= 1, "glyph for " + c + " is empty");
			Set<String> glyph = pixels(c);
			assertNotEquals(pixels('\u0001'), glyph, c + " looks like the fallback box");
			Character previous = seen.put(glyph, c);
			assertNull(previous, c + " has the same glyph as " + previous);
		}
	}

	@Test
	void lowerCaseUsesUpperCaseGlyphs() {
		for (char c = 'a'; c <= 'z'; c++) {
			assertTrue(PixelFont.supports(c));
			assertEquals(pixels(Character.toUpperCase(c)), pixels(c));
		}
		assertEquals(draw("Hello", 1).positions(), draw("HELLO", 1).positions());
	}

	@Test
	void unsupportedCharactersAreAHollowBox() {
		assertFalse(PixelFont.supports('~'));
		assertFalse(PixelFont.supports('é'));
		RecordingSink box = draw("~", 1);
		assertEquals(20, box.writeCount());
		for (Pos p : box.positions()) {
			assertTrue(p.x() == 0 || p.x() == 4 || p.y() == 0 || p.y() == 6, "not on the box outline: " + p);
		}
		// A character outside the BMP is one box, not two.
		assertEquals(PixelFont.GLYPH_WIDTH, PixelFont.draw(new RecordingSink(), "😀", 0, 0, 0, ID, 1));
		assertEquals(box.positions(), draw("😀", 1).positions());
	}

	@Test
	void readableFromTheFront() {
		// "L": the stem is on the left (min x) and the foot runs to the right (+x) along the bottom.
		Set<String> l = pixels('L');
		for (int row = 0; row < 7; row++) {
			assertTrue(l.contains("0," + row));
		}
		for (int col = 0; col < 5; col++) {
			assertTrue(l.contains(col + ",6"));
		}
		assertEquals(11, l.size());
		// "F": full top row, the bottom row only has the stem.
		Set<String> f = pixels('F');
		for (int col = 0; col < 5; col++) {
			assertTrue(f.contains(col + ",0"));
		}
		assertTrue(f.contains("0,6"));
		assertFalse(f.contains("4,6"));
		// Characters advance along +x: the "L" of "LI" comes first.
		Set<Pos> li = draw("LI", 1).positions();
		assertTrue(li.contains(new Pos(0, 6, 0)));
		assertFalse(li.contains(new Pos(6, 6, 0)), "I has no pixel in its first column");
	}

	@Test
	void drawsAtTheGivenCornerAndDepth() {
		RecordingSink sink = new RecordingSink();
		int width = PixelFont.draw(sink, "AB", 10, 64, -5, ID, 1);
		assertEquals(11, width);
		for (Pos p : sink.positions()) {
			assertEquals(-5, p.z());
			assertTrue(p.x() >= 10 && p.x() <= 20);
			assertTrue(p.y() >= 64 && p.y() <= 70);
		}
		assertTrue(sink.positions().contains(new Pos(10, 64, -5)), "A's bottom-left pixel");
		assertTrue(sink.positions().contains(new Pos(16, 70, -5)), "B's top-left pixel");
		assertEquals(ID, sink.blocks.values().iterator().next());
	}

	@Test
	void widthMatchesDraw() {
		assertEquals(0, PixelFont.width("", 1));
		assertEquals(5, PixelFont.width("A", 1));
		assertEquals(11, PixelFont.width("AB", 1));
		assertEquals(29, PixelFont.width("HELLO", 1));
		assertEquals(22, PixelFont.width("AB", 2));
		assertEquals(17, PixelFont.width("a b", 1));
		for (String s : new String[] {"", "A", "Hi!", "MC-AI 2026", "   ", "♥~x"}) {
			for (int scale = 1; scale <= 3; scale++) {
				assertEquals(PixelFont.width(s, scale), PixelFont.draw(new RecordingSink(), s, 0, 0, 0, ID, scale));
			}
		}
	}

	@Test
	void newlinesStartALineBelow() {
		RecordingSink sink = new RecordingSink();
		int width = PixelFont.draw(sink, "AB\nC", 0, 0, 0, ID, 1);
		assertEquals(11, width);
		Set<Pos> c = new HashSet<>();
		for (Pos p : sink.positions()) {
			if (p.y() < 0) {
				c.add(new Pos(p.x(), p.y() + 9, p.z()));
			}
		}
		assertEquals(draw("C", 1).positions(), c);
		assertTrue(sink.positions().stream().filter(p -> p.y() < 0).allMatch(p -> p.y() >= -9 && p.y() <= -3));
		assertEquals(17, PixelFont.draw(new RecordingSink(), "A\nABC\r\nAB", 0, 0, 0, ID, 1));
		assertEquals(17, PixelFont.width("A\nABC\r\nAB", 1));
		RecordingSink scaled = new RecordingSink();
		PixelFont.draw(scaled, "I\nI", 0, 0, 0, ID, 2);
		assertEquals(-18, scaled.positions().stream().mapToInt(Pos::y).min().orElseThrow());
	}

	@Test
	void scaleTurnsPixelsIntoSquares() {
		int single = draw("I", 1).writeCount();
		RecordingSink big = draw("I", 3);
		assertEquals(9 * single, big.writeCount());
		assertFalse(big.hasDuplicateWrites());
		assertEquals(0, big.positions().stream().mapToInt(Pos::y).min().orElseThrow());
		assertEquals(20, big.positions().stream().mapToInt(Pos::y).max().orElseThrow());
		assertEquals(3, big.positions().stream().mapToInt(Pos::x).min().orElseThrow());
		assertEquals(11, big.positions().stream().mapToInt(Pos::x).max().orElseThrow());
		for (Pos p : draw("I", 1).positions()) {
			for (int dx = 0; dx < 3; dx++) {
				for (int dy = 0; dy < 3; dy++) {
					assertTrue(big.positions().contains(new Pos(p.x() * 3 + dx, p.y() * 3 + dy, 0)));
				}
			}
		}
	}

	@Test
	void rejectsBadArguments() {
		RecordingSink sink = new RecordingSink();
		assertThrows(IllegalArgumentException.class, () -> PixelFont.draw(sink, "A", 0, 0, 0, ID, 0));
		assertThrows(IllegalArgumentException.class, () -> PixelFont.draw(sink, null, 0, 0, 0, ID, 1));
		assertThrows(IllegalArgumentException.class, () -> PixelFont.draw(sink, "A", 0, 0, 0, null, 1));
		assertThrows(IllegalArgumentException.class, () -> PixelFont.width("A", -1));
		assertEquals(0, sink.writeCount());
	}
}
