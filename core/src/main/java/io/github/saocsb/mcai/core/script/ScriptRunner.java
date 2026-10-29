package io.github.saocsb.mcai.core.script;

/** Runs a build script; implemented by {@link ScriptEngine} (an interface so callers can be tested with fakes). */
@FunctionalInterface
public interface ScriptRunner {
	ScriptResult run(String source, ScriptLimits limits, long seed) throws ScriptException;
}
