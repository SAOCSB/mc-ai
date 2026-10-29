package io.github.saocsb.mcai.core.script;

import io.github.saocsb.mcai.core.blueprint.Blueprint;
import io.github.saocsb.mcai.core.placement.PlacementSpec;

import java.util.List;

/**
 * Output of a successful build-script run.
 *
 * @param name    display name set via {@code setName(...)}, or null
 * @param logs    lines from log()/print()/console.log(), truncated to the limit
 */
public record ScriptResult(Blueprint blueprint, PlacementSpec placement, String name, List<WorldAction> actions,
		List<String> logs, long elapsedMillis) {
}
