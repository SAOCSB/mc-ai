package io.github.saocsb.mcai.core.script;

import io.github.saocsb.mcai.core.blueprint.Blueprint;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/** Shared helpers for the script engine tests. */
final class ScriptTestSupport {
	static final ScriptEngine ENGINE = new ScriptEngine();
	static final long SEED = 42L;

	private ScriptTestSupport() {
	}

	static ScriptResult run(String source) {
		return run(source, ScriptLimits.defaults());
	}

	static ScriptResult run(String source, ScriptLimits limits) {
		return run(source, limits, SEED);
	}

	static ScriptResult run(String source, ScriptLimits limits, long seed) {
		try {
			return ENGINE.run(source, limits, seed);
		} catch (ScriptException e) {
			return fail("script failed (" + e.kind() + "): " + e.getMessage() + "\nlogs: " + e.logs(), e);
		}
	}

	static ScriptException failure(String source) {
		return failure(source, ScriptLimits.defaults());
	}

	static ScriptException failure(String source, ScriptLimits limits) {
		return assertThrows(ScriptException.class, () -> ENGINE.run(source, limits, SEED));
	}

	static ScriptException failure(ScriptException.Kind kind, String source) {
		return failure(kind, source, ScriptLimits.defaults());
	}

	static ScriptException failure(ScriptException.Kind kind, String source, ScriptLimits limits) {
		ScriptException e = failure(source, limits);
		assertEquals(kind, e.kind(), () -> "unexpected kind, message: " + e.getMessage());
		return e;
	}

	/** Logs of a successful run. */
	static List<String> logs(String source) {
		return run(source).logs();
	}

	/** The single log line of a successful run. */
	static String log(String source) {
		List<String> logs = logs(source);
		assertEquals(1, logs.size(), () -> "expected one log line, got " + logs);
		return logs.get(0);
	}

	static ScriptLimits limits(int maxBlocks, int maxSpan, long timeoutMillis, int maxActions, int maxStackDepth,
			int maxLogLines) {
		return new ScriptLimits(maxBlocks, maxSpan, timeoutMillis, maxActions, maxStackDepth, maxLogLines);
	}

	static ScriptLimits withTimeout(long millis) {
		ScriptLimits d = ScriptLimits.defaults();
		return limits(d.maxBlocks(), d.maxSpan(), millis, d.maxActions(), d.maxStackDepth(), d.maxLogLines());
	}

	/** Every entry as "x,y,z=id", in insertion order. */
	static List<String> entries(Blueprint blueprint) {
		List<String> out = new ArrayList<>();
		blueprint.forEach((x, y, z, id) -> out.add(x + "," + y + "," + z + "=" + id));
		return out;
	}
}
