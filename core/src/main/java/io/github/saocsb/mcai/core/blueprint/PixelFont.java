package io.github.saocsb.mcai.core.blueprint;

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

	private PixelFont() {
	}

	/**
	 * Draws text as a vertical wall in the X/Y plane at depth z, readable from the +z side (the
	 * viewer looks toward -z): characters advance along +x, row 0 of each glyph is the top.
	 * (x, y, z) is the bottom-left corner of the first character. Each pixel becomes a
	 * scale x scale square. Newlines ('\n') start a new line below (line height = (7+2)*scale).
	 *
	 * @return the width in blocks of the longest line
	 */
	public static int draw(BlockSink sink, String text, int x, int y, int z, String id, int scale) {
		throw new UnsupportedOperationException("TODO");
	}

	/** Width in blocks that {@link #draw} would use for a single line (no newlines) at the given scale. */
	public static int width(String line, int scale) {
		throw new UnsupportedOperationException("TODO");
	}

	/** True if the character has a real glyph (not the fallback box). */
	public static boolean supports(char c) {
		throw new UnsupportedOperationException("TODO");
	}
}
