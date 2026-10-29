package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.script.ScriptResult;

import java.util.List;

/**
 * Final result of a request.
 *
 * @param chatText chat text of the first reply ("" if none)
 * @param result   successful script result to build, or null (pure chat, or failure)
 * @param failure  user-facing reason when a script was attempted but could not be made to work
 *                 (last script error, invalid blocks, refusal, truncation), else null
 * @param attempts number of LLM calls made (1 + repairs)
 * @param warnings player-facing notes about changes the agent made to a successful result's blueprint, e.g.
 *                 {@code "auto-fixed block 'stone_brick' -> 'stone_bricks'"} or
 *                 {@code "removed 12 blocks with unknown id 'fancy_pillar'"}; never null, empty for failures and
 *                 pure chat
 */
public record AgentOutcome(String chatText, ScriptResult result, String failure, int attempts, List<String> warnings) {
	public AgentOutcome {
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
	}

	/** An outcome without warnings. */
	public AgentOutcome(String chatText, ScriptResult result, String failure, int attempts) {
		this(chatText, result, failure, attempts, List.of());
	}
}
