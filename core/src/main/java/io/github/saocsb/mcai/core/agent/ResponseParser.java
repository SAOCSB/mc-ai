package io.github.saocsb.mcai.core.agent;

/**
 * Splits assistant replies into chat text and a build script.
 *
 * <ul>
 * <li>Removes {@code <think>...</think>} / {@code <thinking>...</thinking>} sections (and an unclosed one at the start).</li>
 * <li>Fenced code blocks with ``` or ~~~ (fence length >= 3, closing fence at least as long). Blocks tagged
 *     js, javascript, mcai, build, or untagged blocks are script; blocks tagged anything else (json, text, ...)
 *     are dropped from both chat and script.</li>
 * <li>If the reply ends inside an open fence, the partial code is still returned as script and
 *     {@code unterminatedCode} is true.</li>
 * <li>Chat text: what remains, trimmed, with 3+ newlines collapsed to 2 and markdown emphasis markers
 *     ({@code **}, {@code __}, leading {@code #} heading marks) removed because Minecraft chat shows them literally.</li>
 * </ul>
 */
public final class ResponseParser {
	private ResponseParser() {
	}

	public static ParsedResponse parse(String reply) {
		throw new UnsupportedOperationException("TODO");
	}
}
