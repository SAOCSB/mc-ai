package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.script.ScriptResult;

/**
 * Final result of a request.
 *
 * @param chatText chat text of the first reply ("" if none)
 * @param result   successful script result to build, or null (pure chat, or failure)
 * @param failure  user-facing reason when a script was attempted but could not be made to work
 *                 (last script error, invalid blocks, refusal, truncation), else null
 * @param attempts number of LLM calls made (1 + repairs)
 */
public record AgentOutcome(String chatText, ScriptResult result, String failure, int attempts) {
}
