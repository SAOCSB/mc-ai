package io.github.saocsb.mcai.core.script;

/**
 * Stops a running script. It is an {@link Error} because Rhino's interpreter never lets JavaScript
 * {@code catch} or {@code finally} blocks run for an Error, so a script cannot swallow a timeout,
 * a limit violation or a cancellation.
 */
final class ScriptAbort extends Error {
	private final ScriptException.Kind kind;
	private final int line;

	/**
	 * @param message full message, already prefixed with the error name (e.g. {@code "LimitError: ..."})
	 * @param line    1-based script line, or -1
	 */
	ScriptAbort(ScriptException.Kind kind, String message, int line) {
		super(message, null, false, false);
		this.kind = kind;
		this.line = line;
	}

	ScriptException.Kind kind() {
		return kind;
	}

	int line() {
		return line;
	}
}
