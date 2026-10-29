package io.github.saocsb.mcai.core.script;

import java.util.List;

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
	private final List<String> logs;

	public ScriptException(Kind kind, String message, int line) {
		this(kind, message, line, List.of());
	}

	/**
	 * @param logs lines the script logged before it failed (shown to the model with the error)
	 */
	public ScriptException(Kind kind, String message, int line, List<String> logs) {
		super(message);
		this.kind = kind;
		this.line = line;
		this.logs = List.copyOf(logs);
	}

	public Kind kind() {
		return kind;
	}

	/** 1-based line number in the original script, or -1 when unknown. */
	public int line() {
		return line;
	}

	/** Lines from log()/print()/console.log() before the failure, truncated like {@link ScriptResult#logs()}. */
	public List<String> logs() {
		return logs;
	}
}
