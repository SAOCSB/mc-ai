package io.github.saocsb.mcai.core.agent;

/**
 * Follows a growing (streamed) reply and counts the lines written inside script code blocks, scanning each
 * complete line only once. Untagged blocks count too: whether they are scripts is only known once they are
 * complete. Not thread-safe.
 */
final class FenceTracker {
	private int pos;
	private ResponseParser.Fence open;
	private boolean seen;
	private int lines;

	/** True once a script code block has been opened. */
	boolean writing() {
		return seen;
	}

	/**
	 * Feeds the reply received so far.
	 *
	 * @return -1 until a script code block has been opened, then the number of code lines so far (the line
	 *         currently being written included)
	 */
	int update(String partial) {
		if (partial.length() < pos) {
			pos = 0;
			open = null;
			seen = false;
			lines = 0;
		}
		int nl;
		while ((nl = partial.indexOf('\n', pos)) >= 0) {
			int end = nl > pos && partial.charAt(nl - 1) == '\r' ? nl - 1 : nl;
			consume(partial.substring(pos, end));
			pos = nl + 1;
		}
		if (!seen) {
			return -1;
		}
		int pending = 0;
		if (open != null && open.maybeScript() && pos < partial.length()) {
			String tail = partial.substring(pos).strip();
			if (!tail.isEmpty() && tail.charAt(0) != open.ch()) {
				pending = 1;
			}
		}
		return lines + pending;
	}

	private void consume(String line) {
		if (open == null) {
			ResponseParser.Fence fence = ResponseParser.openingFence(line);
			if (fence != null) {
				open = fence;
				seen |= fence.maybeScript();
			}
		} else if (ResponseParser.closesFence(open, line)) {
			open = null;
		} else if (open.maybeScript()) {
			lines++;
		}
	}
}
