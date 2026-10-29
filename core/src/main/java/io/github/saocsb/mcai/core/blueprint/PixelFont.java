package io.github.saocsb.mcai.core.blueprint;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A 5x7 pixel font for building text out of blocks.
 *
 * <p>Supports A-Z (lower case is upper-cased), 0-9, space and {@code . , ! ? - + : ' " / ( ) = _ # & * < >}
 * and a heart {@code ♥}. Unsupported characters render as a hollow 5x7 box.
 */
public final class PixelFont {
	public static final int GLYPH_WIDTH = 5;
	public static final int GLYPH_HEIGHT = 7;
	/** Columns of empty space between glyphs (before scaling). */
	public static final int SPACING = 1;
	/** Rows of empty space between lines (before scaling). */
	private static final int LINE_GAP = 2;

	/** Glyph rows, top first; bit 4 is the leftmost column. */
	private static final Map<Character, byte[]> GLYPHS = new HashMap<>();
	private static final byte[] FALLBACK = rows("#####", "#...#", "#...#", "#...#", "#...#", "#...#", "#####");

	static {
		glyph('A', ".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
		glyph('B', "####.", "#...#", "#...#", "####.", "#...#", "#...#", "####.");
		glyph('C', ".###.", "#...#", "#....", "#....", "#....", "#...#", ".###.");
		glyph('D', "###..", "#..#.", "#...#", "#...#", "#...#", "#..#.", "###..");
		glyph('E', "#####", "#....", "#....", "####.", "#....", "#....", "#####");
		glyph('F', "#####", "#....", "#....", "####.", "#....", "#....", "#....");
		glyph('G', ".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####");
		glyph('H', "#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
		glyph('I', ".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###.");
		glyph('J', "..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##..");
		glyph('K', "#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#");
		glyph('L', "#....", "#....", "#....", "#....", "#....", "#....", "#####");
		glyph('M', "#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#");
		glyph('N', "#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#");
		glyph('O', ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
		glyph('P', "####.", "#...#", "#...#", "####.", "#....", "#....", "#....");
		glyph('Q', ".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#");
		glyph('R', "####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#");
		glyph('S', ".####", "#....", "#....", ".###.", "....#", "....#", "####.");
		glyph('T', "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..");
		glyph('U', "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
		glyph('V', "#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#..");
		glyph('W', "#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#.");
		glyph('X', "#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#");
		glyph('Y', "#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#..");
		glyph('Z', "#####", "....#", "...#.", "..#..", ".#...", "#....", "#####");
		glyph('0', ".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###.");
		glyph('1', "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###.");
		glyph('2', ".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####");
		glyph('3', "#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###.");
		glyph('4', "...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#.");
		glyph('5', "#####", "#....", "####.", "....#", "....#", "#...#", ".###.");
		glyph('6', "..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###.");
		glyph('7', "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...");
		glyph('8', ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###.");
		glyph('9', ".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##..");
		glyph(' ', ".....", ".....", ".....", ".....", ".....", ".....", ".....");
		glyph('.', ".....", ".....", ".....", ".....", ".....", ".##..", ".##..");
		glyph(',', ".....", ".....", ".....", ".....", ".##..", "..#..", ".#...");
		glyph('!', "..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#..");
		glyph('?', ".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#..");
		glyph('-', ".....", ".....", ".....", "#####", ".....", ".....", ".....");
		glyph('+', ".....", "..#..", "..#..", "#####", "..#..", "..#..", ".....");
		glyph(':', ".....", ".##..", ".##..", ".....", ".##..", ".##..", ".....");
		glyph('\'', ".##..", "..#..", ".#...", ".....", ".....", ".....", ".....");
		glyph('"', ".#.#.", ".#.#.", ".#.#.", ".....", ".....", ".....", ".....");
		glyph('/', ".....", "....#", "...#.", "..#..", ".#...", "#....", ".....");
		glyph('(', "...#.", "..#..", ".#...", ".#...", ".#...", "..#..", "...#.");
		glyph(')', ".#...", "..#..", "...#.", "...#.", "...#.", "..#..", ".#...");
		glyph('=', ".....", ".....", "#####", ".....", "#####", ".....", ".....");
		glyph('_', ".....", ".....", ".....", ".....", ".....", ".....", "#####");
		glyph('#', ".#.#.", ".#.#.", "#####", ".#.#.", "#####", ".#.#.", ".#.#.");
		glyph('&', ".##..", "#..#.", "#.#..", ".#...", "#.#.#", "#..#.", ".##.#");
		glyph('*', ".....", "..#..", "#.#.#", ".###.", "#.#.#", "..#..", ".....");
		glyph('<', "...#.", "..#..", ".#...", "#....", ".#...", "..#..", "...#.");
		glyph('>', ".#...", "..#..", "...#.", "....#", "...#.", "..#..", ".#...");
		glyph('♥', ".....", ".#.#.", "#####", "#####", ".###.", "..#..", ".....");
	}

	private PixelFont() {
	}

	/**
	 * Draws text as a vertical wall in the X/Y plane at depth z, readable from the +z side (the
	 * viewer looks toward -z): characters advance along +x, row 0 of each glyph is the top.
	 * (x, y, z) is the bottom-left corner of the first character. Each pixel becomes a
	 * scale x scale square. Newlines ('\n') start a new line below (line height = (7+2)*scale).
	 * Carriage returns are ignored; characters outside the BMP count as one unsupported character.
	 *
	 * @return the width in blocks of the longest line
	 * @throws IllegalArgumentException if text or id is null, id is blank, or scale < 1
	 */
	public static int draw(BlockSink sink, String text, int x, int y, int z, String id, int scale) {
		Objects.requireNonNull(sink, "sink");
		if (text == null) {
			throw new IllegalArgumentException("text must not be null");
		}
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("block id must not be " + (id == null ? "null" : "blank"));
		}
		checkScale(scale);
		int widest = 0;
		int line = 0;
		int column = 0;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '\r') {
				continue;
			}
			if (cp == '\n') {
				widest = Math.max(widest, lineWidth(column, scale));
				line++;
				column = 0;
				continue;
			}
			byte[] glyph = glyphFor(cp);
			int left = x + column * (GLYPH_WIDTH + SPACING) * scale;
			int bottom = y - line * (GLYPH_HEIGHT + LINE_GAP) * scale;
			for (int row = 0; row < GLYPH_HEIGHT; row++) {
				int bits = glyph[row];
				if (bits == 0) {
					continue;
				}
				int rowBottom = bottom + (GLYPH_HEIGHT - 1 - row) * scale;
				for (int col = 0; col < GLYPH_WIDTH; col++) {
					if ((bits & (1 << (GLYPH_WIDTH - 1 - col))) != 0) {
						int px = left + col * scale;
						for (int dy = 0; dy < scale; dy++) {
							for (int dx = 0; dx < scale; dx++) {
								sink.set(px + dx, rowBottom + dy, z, id);
							}
						}
					}
				}
			}
			column++;
		}
		return Math.max(widest, lineWidth(column, scale));
	}

	/**
	 * Width in blocks that {@link #draw} would use for a single line (no newlines) at the given scale.
	 * If the string contains newlines, the widest line counts.
	 */
	public static int width(String line, int scale) {
		checkScale(scale);
		int widest = 0;
		int column = 0;
		for (int i = 0; i < line.length(); ) {
			int cp = line.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '\n') {
				widest = Math.max(widest, lineWidth(column, scale));
				column = 0;
			} else if (cp != '\r') {
				column++;
			}
		}
		return Math.max(widest, lineWidth(column, scale));
	}

	/** True if the character has a real glyph (not the fallback box). */
	public static boolean supports(char c) {
		return GLYPHS.containsKey(upper(c));
	}

	private static byte[] glyphFor(int codePoint) {
		if (codePoint > Character.MAX_VALUE) {
			return FALLBACK;
		}
		return GLYPHS.getOrDefault(upper((char) codePoint), FALLBACK);
	}

	private static char upper(char c) {
		return c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c;
	}

	private static int lineWidth(int characters, int scale) {
		return characters == 0 ? 0 : (characters * (GLYPH_WIDTH + SPACING) - SPACING) * scale;
	}

	private static void checkScale(int scale) {
		if (scale < 1) {
			throw new IllegalArgumentException("scale must be >= 1, got " + scale);
		}
	}

	private static void glyph(char c, String... rows) {
		GLYPHS.put(c, rows(rows));
	}

	private static byte[] rows(String... rows) {
		if (rows.length != GLYPH_HEIGHT) {
			throw new IllegalStateException("glyph needs " + GLYPH_HEIGHT + " rows");
		}
		byte[] bits = new byte[GLYPH_HEIGHT];
		for (int r = 0; r < GLYPH_HEIGHT; r++) {
			if (rows[r].length() != GLYPH_WIDTH) {
				throw new IllegalStateException("glyph row needs " + GLYPH_WIDTH + " columns: " + rows[r]);
			}
			for (int c = 0; c < GLYPH_WIDTH; c++) {
				if (rows[r].charAt(c) == '#') {
					bits[r] |= (byte) (1 << (GLYPH_WIDTH - 1 - c));
				}
			}
		}
		return bits;
	}
}
