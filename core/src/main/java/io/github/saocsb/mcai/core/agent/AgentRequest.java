package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.script.ScriptLimits;

/**
 * Everything {@link AiAgent#handle} needs for one player message.
 *
 * @param systemPrompt     from {@link PromptBuilder#systemPrompt}
 * @param maxHistory       history messages to include (see {@link Conversation#view})
 * @param maxRepairs       repair round-trips allowed after the first reply (0 = none)
 * @param seed             seed for the script's random functions
 */
public record AgentRequest(Conversation conversation, String systemPrompt, GameContext context, String message,
		ScriptLimits limits, int maxHistory, int maxRepairs, BlockValidator validator, AgentListener listener, long seed) {
}
