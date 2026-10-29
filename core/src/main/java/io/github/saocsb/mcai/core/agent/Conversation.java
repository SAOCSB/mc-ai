package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-player chat history. Thread-safe (synchronized on this instance); the agent appends one user turn and one
 * assistant turn per completed exchange.
 */
public final class Conversation {
	/** Replacement for code blocks in older assistant messages (see {@link #view}). */
	static final String ELIDED_CODE = "```js\n// (earlier script omitted)\n```";

	private final List<ChatMessage> messages = new ArrayList<>();

	public Conversation() {
	}

	public synchronized void add(ChatMessage message) {
		messages.add(Objects.requireNonNull(message, "message"));
	}

	public synchronized void clear() {
		messages.clear();
	}

	public synchronized int size() {
		return messages.size();
	}

	/** Full history copy. */
	public synchronized List<ChatMessage> messages() {
		return List.copyOf(messages);
	}

	/**
	 * History prepared for a request: the last {@code maxMessages} messages, adjusted to start with a USER
	 * message; in every ASSISTANT message except the most recent one that contains a code block, each fenced
	 * code block is replaced by {@code "```js\n// (earlier script omitted)\n```"} to save tokens.
	 * Also drops stored history beyond {@code 2 * maxMessages} entries.
	 * Stored messages are never modified; elided messages are new instances. {@code maxMessages <= 0} gives an
	 * empty view and clears the stored history.
	 */
	public synchronized List<ChatMessage> view(int maxMessages) {
		int max = Math.max(0, maxMessages);
		long keep = 2L * max;
		if (messages.size() > keep) {
			messages.subList(0, messages.size() - (int) keep).clear();
		}
		int start = Math.max(0, messages.size() - max);
		while (start < messages.size() && messages.get(start).role() != ChatMessage.Role.USER) {
			start++;
		}
		List<ChatMessage> window = messages.subList(start, messages.size());
		int keepCode = -1;
		for (int i = window.size() - 1; i >= 0; i--) {
			ChatMessage m = window.get(i);
			if (m.role() == ChatMessage.Role.ASSISTANT && ResponseParser.hasCodeBlock(m.content())) {
				keepCode = i;
				break;
			}
		}
		List<ChatMessage> view = new ArrayList<>(window.size());
		for (int i = 0; i < window.size(); i++) {
			ChatMessage m = window.get(i);
			if (i != keepCode && m.role() == ChatMessage.Role.ASSISTANT && ResponseParser.hasCodeBlock(m.content())) {
				view.add(ChatMessage.assistant(ResponseParser.replaceCodeBlocks(m.content(), ELIDED_CODE)));
			} else {
				view.add(m);
			}
		}
		return List.copyOf(view);
	}
}
