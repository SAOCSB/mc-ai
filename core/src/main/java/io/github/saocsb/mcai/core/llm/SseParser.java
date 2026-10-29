package io.github.saocsb.mcai.core.llm;

/**
 * Incremental Server-Sent Events parser (WHATWG event-stream rules), fed one line at a time without its
 * terminator. Comment lines (":...") and {@code id}/{@code retry}/unknown fields are ignored; multi-line
 * {@code data:} values are joined with '\n'; a blank line dispatches the event. Not thread-safe.
 */
final class SseParser {
	/** Receives dispatched events. */
	@FunctionalInterface
	interface Listener {
		/**
		 * @param event the {@code event:} name, or "message" when none was given
		 * @param data  the joined {@code data:} lines
		 */
		void onEvent(String event, String data);
	}

	private final Listener listener;
	private final StringBuilder data = new StringBuilder();
	private boolean hasData;
	private String event;
	private boolean firstLine = true;

	SseParser(Listener listener) {
		this.listener = listener;
	}

	/** Processes one line (without its line terminator). */
	void line(String line) {
		if (firstLine) {
			firstLine = false;
			if (!line.isEmpty() && line.charAt(0) == '﻿') {
				line = line.substring(1);
			}
		}
		if (line.isEmpty()) {
			dispatch();
			return;
		}
		if (line.charAt(0) == ':') {
			return;
		}
		int colon = line.indexOf(':');
		String field = colon < 0 ? line : line.substring(0, colon);
		String value = "";
		if (colon >= 0) {
			int start = colon + 1;
			if (start < line.length() && line.charAt(start) == ' ') {
				start++;
			}
			value = line.substring(start);
		}
		switch (field) {
			case "event" -> event = value;
			case "data" -> {
				if (hasData) {
					data.append('\n');
				}
				data.append(value);
				hasData = true;
			}
			default -> {
				// id, retry and unknown fields are irrelevant here
			}
		}
	}

	/**
	 * Call at end of stream. Unlike the spec (which drops an unterminated event), a pending event is
	 * dispatched, since some servers omit the final blank line.
	 */
	void finish() {
		dispatch();
	}

	private void dispatch() {
		if (!hasData) {
			event = null;
			return;
		}
		String name = event == null || event.isEmpty() ? "message" : event;
		String value = data.toString();
		data.setLength(0);
		hasData = false;
		event = null;
		listener.onEvent(name, value);
	}
}
