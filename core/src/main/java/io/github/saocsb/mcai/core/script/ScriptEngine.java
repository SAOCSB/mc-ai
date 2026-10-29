package io.github.saocsb.mcai.core.script;

/**
 * Runs untrusted, LLM-written JavaScript build scripts in a Rhino sandbox and collects the result.
 *
 * <p>Sandbox: interpreted mode, safe standard objects only (no {@code Packages}, {@code java},
 * {@code importPackage}, {@code load}...), a ClassShutter that denies every Java class, a
 * wall-clock budget enforced via the instruction observer, a call-depth limit, and checks for
 * thread interruption (so a cancelled task stops). {@code Math.random} is replaced by the seeded
 * generator so runs are deterministic for a given seed.
 *
 * <p>The script is wrapped in a function, so top-level {@code const}/{@code let}/{@code function}
 * declarations may shadow API names (e.g. {@code const text = ...}); line numbers in errors still
 * refer to the original script. The global API is documented in {@code mcai/prompt/system.md}
 * (resource) and must match {@link #API_FUNCTIONS}.
 *
 * <p>Thread-safe: every {@link #run} uses its own Context and scope.
 */
public final class ScriptEngine {
	/** Every global function the script API defines (used by tests to keep the prompt in sync). */
	public static final java.util.List<String> API_FUNCTIONS = java.util.List.of(
			"block", "fill", "hollow", "walls", "frame", "clear", "line", "sphere", "dome", "ellipsoid",
			"cylinder", "cone", "pyramid", "torus", "polygon", "text", "replace", "get",
			"random", "randInt", "pick", "noise",
			"placement", "setName",
			"summon", "give", "time", "weather", "effect", "command",
			"log", "print");

	public ScriptEngine() {
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * Runs a script.
	 *
	 * @param source the JavaScript source (one script; may contain comments)
	 * @param seed   seed for random()/randInt()/pick()/noise()/Math.random
	 * @throws ScriptException on syntax/runtime errors, timeouts, limit violations or cancellation
	 */
	public ScriptResult run(String source, ScriptLimits limits, long seed) throws ScriptException {
		throw new UnsupportedOperationException("TODO");
	}
}
