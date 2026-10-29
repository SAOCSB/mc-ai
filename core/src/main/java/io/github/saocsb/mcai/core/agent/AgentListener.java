package io.github.saocsb.mcai.core.agent;

/** Callbacks from {@link AiAgent}; invoked on worker threads (adapters must hop to the game thread). */
public interface AgentListener {
	/**
	 * The assistant's chat text from the FIRST reply of a request, delivered as soon as that reply has been
	 * received and parsed (before the script runs). Not called when the text is empty. Replies to repair
	 * prompts never produce chat.
	 */
	void onChat(String text);

	/** Progress update; throttled by the agent to at most one THINKING/WRITING_SCRIPT update per second. */
	void onStatus(AgentStatus status, Object... args);
}
