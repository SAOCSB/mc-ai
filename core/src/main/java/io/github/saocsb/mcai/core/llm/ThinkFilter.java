package io.github.saocsb.mcai.core.llm;

import java.util.regex.Pattern;

/**
 * Separates {@code <think>...</think>} reasoning from visible text, fed incrementally. A think block at the
 * start (after whitespace) is routed to reasoning while it streams, even when the tags are split across
 * chunks; {@link #finalText()} also removes later think blocks and anything before an orphan
 * {@code </think>} (servers that drop the opening tag). Not thread-safe.
 */
final class ThinkFilter {
	private static final String OPEN = "<think>";
	private static final String CLOSE = "</think>";
	private static final Pattern THINK_BLOCK = Pattern.compile("(?s)<think>.*?</think>\\s*");

	private enum State {
		LEADING,
		THINK,
		TEXT
	}

	private final StringBuilder visible = new StringBuilder();
	private final StringBuilder pending = new StringBuilder();
	private State state = State.LEADING;
	private boolean skipWhitespace;
	private int reasoningChars;

	void append(String chunk) {
		if (chunk == null || chunk.isEmpty()) {
			return;
		}
		String s = chunk;
		if (!pending.isEmpty()) {
			s = pending + chunk;
			pending.setLength(0);
		}
		int i = 0;
		while (i < s.length()) {
			switch (state) {
				case LEADING -> {
					int j = i;
					while (j < s.length() && Character.isWhitespace(s.charAt(j))) {
						j++;
					}
					if (j == s.length()) {
						pending.append(s, i, s.length());
						return;
					}
					if (s.startsWith(OPEN, j)) {
						state = State.THINK;
						i = j + OPEN.length();
					} else if (OPEN.startsWith(s.substring(j))) {
						pending.append(s, i, s.length());
						return;
					} else {
						state = State.TEXT;
					}
				}
				case THINK -> {
					int close = s.indexOf(CLOSE, i);
					if (close >= 0) {
						reasoningChars += close - i;
						i = close + CLOSE.length();
						state = State.TEXT;
						skipWhitespace = true;
					} else {
						int hold = partialTagSuffix(s, i, CLOSE);
						reasoningChars += s.length() - i - hold;
						pending.append(s, s.length() - hold, s.length());
						return;
					}
				}
				case TEXT -> {
					if (skipWhitespace) {
						while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
							i++;
						}
						if (i == s.length()) {
							return;
						}
						skipWhitespace = false;
					}
					visible.append(s, i, s.length());
					return;
				}
			}
		}
	}

	/** Visible characters so far (held-back partial tags excluded). */
	int visibleLength() {
		return visible.length();
	}

	/** Visible text so far. */
	String visibleText() {
		return visible.toString();
	}

	/** Characters routed to reasoning so far. */
	int reasoningChars() {
		return reasoningChars;
	}

	/** Flushes held-back input and returns the final visible text. */
	String finalText() {
		if (!pending.isEmpty()) {
			if (state == State.THINK) {
				reasoningChars += pending.length();
			} else if (!skipWhitespace) {
				visible.append(pending);
			}
			pending.setLength(0);
		}
		String text = visible.toString();
		if (text.contains(CLOSE)) {
			text = THINK_BLOCK.matcher(text).replaceAll("");
			int orphan = text.lastIndexOf(CLOSE);
			if (orphan >= 0) {
				text = text.substring(orphan + CLOSE.length()).stripLeading();
			}
		}
		return text;
	}

	/** Length of the longest suffix of {@code s[from..]} that is a proper prefix of {@code tag}. */
	private static int partialTagSuffix(String s, int from, String tag) {
		for (int len = Math.min(tag.length() - 1, s.length() - from); len > 0; len--) {
			if (s.regionMatches(s.length() - len, tag, 0, len)) {
				return len;
			}
		}
		return 0;
	}
}
