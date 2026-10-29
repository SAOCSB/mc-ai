package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.llm.LlmClient;
import io.github.saocsb.mcai.core.script.ScriptRunner;

import java.util.concurrent.Executor;

/**
 * Orchestrates one player request:
 * <ol>
 * <li>messages = conversation.view(maxHistory) + user turn ({@link PromptBuilder#userTurn}); call the LLM,
 *     forwarding progress as THINKING / WRITING_SCRIPT statuses (WRITING_SCRIPT once the partial text contains an
 *     opening code fence; throttled to 1/s).</li>
 * <li>Parse with {@link ResponseParser}. First reply: deliver non-empty chat text via {@code onChat}.</li>
 * <li>If refused: outcome with failure, no repair. If no script: outcome with result null and failure null.</li>
 * <li>Run the script on {@code scriptExecutor} (status RUNNING_SCRIPT). Errors are: unterminated/truncated
 *     reply, {@link io.github.saocsb.mcai.core.script.ScriptException}, an empty blueprint with no world actions,
 *     or invalid palette entries from the validator (message lists each id, reason and suggestions).</li>
 * <li>On error with repairs left: status REPAIRING, append the assistant reply + {@link PromptBuilder#repairTurn}
 *     to the request messages (not to the conversation) and call again. Otherwise return the failure.</li>
 * <li>On success or final failure, append to the conversation exactly two messages: the user turn (just the
 *     player's message text, prefixed with "{playerName}: ", without the context block) and the final assistant
 *     reply (the last reply received; on success the one whose script worked).</li>
 * </ol>
 * LLM exceptions complete the future exceptionally with the {@link io.github.saocsb.mcai.core.llm.LlmException}
 * (unwrapped from CompletionException); nothing is added to the conversation then.
 */
public final class AiAgent {
	public AiAgent(LlmClient client, ScriptRunner scripts, Executor scriptExecutor) {
		throw new UnsupportedOperationException("TODO");
	}

	public AgentTask handle(AgentRequest request) {
		throw new UnsupportedOperationException("TODO");
	}
}
