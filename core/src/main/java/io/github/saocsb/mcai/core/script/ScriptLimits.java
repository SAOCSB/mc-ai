package io.github.saocsb.mcai.core.script;

/**
 * Resource limits for one build-script run.
 *
 * @param maxBlocks     max distinct block positions in the blueprint
 * @param maxSpan       max size of the blueprint along any axis
 * @param timeoutMillis wall-clock budget for running the script
 * @param maxActions    max world actions (summon/give/time/weather/effect/command/wait)
 * @param maxStackDepth max JavaScript call depth (recursion guard)
 * @param maxLogLines   max lines kept from log()/print()/console.log()
 */
public record ScriptLimits(int maxBlocks, int maxSpan, long timeoutMillis, int maxActions, int maxStackDepth, int maxLogLines) {
	public static ScriptLimits defaults() {
		return new ScriptLimits(300_000, 384, 10_000, 32, 400, 200);
	}
}
