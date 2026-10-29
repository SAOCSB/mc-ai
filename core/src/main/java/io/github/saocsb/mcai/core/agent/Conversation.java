package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.llm.ChatMessage;

import java.util.List;

/**
 * Per-player chat history. Thread-safe (synchronized); the agent appends one user turn and one
 * assistant turn per completed exchange.
 */
public final class Conversation {
	public Conversation() {
		throw new UnsupportedOperationException("TODO");
	}

	public void add(ChatMessage message) {
		throw new UnsupportedOperationException("TODO");
	}

	public void clear() {
		throw new UnsupportedOperationException("TODO");
	}

	public int size() {
		throw new UnsupportedOperationException("TODO");
	}

	/** Full history copy. */
	public List<ChatMessage> messages() {
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * History prepared for a request: the last {@code maxMessages} messages, adjusted to start with a USER
	 * message; in every ASSISTANT message except the most recent one that contains a code block, each fenced
	 * code block is replaced by {@code "```js\n// (earlier script omitted)\n```"} to save tokens.
	 * Also drops stored history beyond {@code 2 * maxMessages} entries.
	 */
	public List<ChatMessage> view(int maxMessages) {
		throw new UnsupportedOperationException("TODO");
	}
}
