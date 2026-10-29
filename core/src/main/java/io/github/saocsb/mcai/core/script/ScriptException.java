package io.github.saocsb.mcai.core.script;

/**
 * A build script failed. {@link #getMessage()} is written for the LLM to fix the script: it names the
 * error, the 1-based line number when known, and the offending source line, e.g.
 * {@code TypeError: Cannot read property "x" of undefined (line 12: const p = pts[i].x;)}.
 */
public class ScriptException extends Exception {
	public enum Kind {
		/** The script does not parse. */
		SYNTAX,
		/** A JavaScript error was thrown while running (including bad arguments to API functions). */
		RUNTIME,
		/** The wall-clock budget was exceeded. */
		TIMEOUT,
		/** A block/size/action/stack limit was exceeded. */
		LIMIT,
		/** The run was cancelled (thread interrupted). */
		CANCELLED
	}

	private final Kind kind;
	private final int line;

	public ScriptException(Kind kind, String message, int line) {
		super(message);
		this.kind = kind;
		this.line = line;
	}

	public Kind kind() {
		return kind;
	}

	/** 1-based line number in the original script, or -1 when unknown. */
	public int line() {
		return line;
	}
}
