package io.github.saocsb.mcai.core.script;

import java.util.List;

/**
 * Names of the script API's global functions, without loading the script engine (and Rhino); see
 * {@link ScriptEngine#API_FUNCTIONS}.
 */
public final class ScriptApi {
	/** Every global function the script API defines. */
	public static final List<String> FUNCTIONS = List.of(
			"block", "fill", "hollow", "walls", "frame", "clear", "line", "sphere", "dome", "ellipsoid",
			"cylinder", "cone", "pyramid", "torus", "polygon", "text", "replace", "get",
			"random", "randInt", "pick", "noise",
			"placement", "setName",
			"summon", "give", "time", "weather", "effect", "command", "wait",
			"log", "print");

	private ScriptApi() {
	}
}
