package io.github.saocsb.mcai.core.llm;

import java.util.List;

/**
 * A chat-completion request. Messages must start with a USER turn and alternate roles
 * (clients may merge consecutive same-role messages before sending).
 *
 * @param systemPrompt stable system prompt (sent first so providers can cache it)
 */
public record LlmRequest(String systemPrompt, List<ChatMessage> messages) {
}
