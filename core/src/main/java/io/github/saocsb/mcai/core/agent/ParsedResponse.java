package io.github.saocsb.mcai.core.agent;

/**
 * An assistant reply split into chat text and build script.
 *
 * @param chatText          text outside code blocks, trimmed, with blank-line runs collapsed (may be empty)
 * @param script            JavaScript to run (all JS code blocks joined with a newline), or null if none
 * @param unterminatedCode  a code fence was opened but never closed (typically a truncated reply)
 */
public record ParsedResponse(String chatText, String script, boolean unterminatedCode) {
}
