package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.script.ScriptApi;
import io.github.saocsb.mcai.core.script.ScriptEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits assistant replies into chat text and a build script.
 *
 * <ul>
 * <li>Removes {@code <think>...</think>} / {@code <thinking>...</thinking>} sections (and an unclosed one at the
 *     start). A closing tag without an opening one removes the text before it as reasoning, unless that text
 *     contains a code fence; then only the tag is removed.</li>
 * <li>Fenced code blocks with ``` or ~~~ (fence length >= 3, closing fence at least as long). Blocks tagged
 *     js, javascript, mcai or build are script. Untagged blocks are script only when they call an API function
 *     (a name from {@link ScriptEngine#API_FUNCTIONS} as a whole word, followed by optional spaces and
 *     {@code (}). Every other block (json, text, an untagged block of prose...) is dropped from both chat and
 *     script.</li>
 * <li>If the reply ends inside an open fence, the partial code is still returned as script (when the block is
 *     script) and {@code unterminatedCode} is true.</li>
 * <li>Chat text: what remains, trimmed, with 3+ newlines collapsed to 2 and markdown emphasis markers
 *     ({@code **}, {@code __}, leading {@code #} heading marks) removed because Minecraft chat shows them literally.</li>
 * </ul>
 *
 * <p>Fences are found more leniently than CommonMark, because models do not always follow it: an opening or
 * closing fence line may be indented by any amount of spaces and tabs (code lines lose up to that much
 * indentation), and a backtick fence may also open in the middle of a line after chat text
 * ({@code Here you go: ```js}); the text before it stays chat. A backtick run that is closed again on the same
 * line is an inline code span, not a fence. The info string after the fence starts with the language tag
 * (case-insensitive; anything after the first word is ignored), and a backtick fence's info string may not
 * contain backticks. Inline {@code `code`} spans stay in the chat text.
 */
public final class ResponseParser {
	/** Language tags (lower case) whose blocks are always build scripts. */
	static final Set<String> SCRIPT_TAGS = Set.of("js", "javascript", "mcai", "build");

	/** A call of a script API function; uses {@link ScriptApi} so that parsing does not load the script engine. */
	private static final Pattern API_CALL = Pattern.compile(
			"\\b(?:" + String.join("|", ScriptApi.FUNCTIONS) + ")[ \\t]*\\(");
	private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<(think|thinking)>.*?</\\1\\s*>");
	private static final Pattern THINK_OPEN_AT_START = Pattern.compile("(?is)^\\s*<(think|thinking)>");
	private static final Pattern THINK_ORPHAN_CLOSE = Pattern.compile("(?is)</(think|thinking)\\s*>");
	private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}(?:[ \\t]+|$)");
	private static final Pattern HEADING_CLOSE = Pattern.compile("[ \\t]+#+[ \\t]*$");
	private static final Pattern BOLD_STARS = Pattern.compile("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*");
	private static final Pattern BOLD_UNDERSCORES = Pattern.compile(
			"(?<![\\p{L}\\p{N}_])__(?=\\S)(.+?)(?<=\\S)__(?![\\p{L}\\p{N}_])");
	private static final Pattern BLANK_RUN = Pattern.compile("\n{3,}");
	/**
	 * Stands for one masked piece of text while emphasis is stripped. Every occurrence is masked, including the
	 * ones already in the reply, so the n-th mark always restores the n-th piece.
	 */
	private static final char MASK = '';

	private ResponseParser() {
	}

	/**
	 * An opening code fence.
	 *
	 * @param ch     fence character
	 * @param length length of the fence run
	 * @param indent whitespace characters before a fence that starts its line (0 for a fence after text)
	 * @param tag    lower-case language tag, "" when untagged
	 * @param lead   chat text before a fence that opens in the middle of a line, else ""
	 */
	record Fence(char ch, int length, int indent, String tag, String lead) {
		/** Tagged as a script: always run. */
		boolean script() {
			return SCRIPT_TAGS.contains(tag);
		}

		/** Tagged as a script or untagged (whether an untagged block is a script depends on its content). */
		boolean maybeScript() {
			return tag.isEmpty() || script();
		}
	}

	/** Parses one reply (CRLF or LF line endings); null or empty gives {@code ("", null, false)}. */
	public static ParsedResponse parse(String reply) {
		if (reply == null || reply.isEmpty()) {
			return new ParsedResponse("", null, false);
		}
		String text = stripThinking(normalizeNewlines(reply));
		if (text.endsWith("\n")) {
			text = text.substring(0, text.length() - 1);
		}
		StringBuilder chat = new StringBuilder();
		List<String> scripts = new ArrayList<>();
		StringBuilder code = null;
		boolean firstCodeLine = false;
		Fence open = null;
		for (String line : text.split("\n", -1)) {
			if (open == null) {
				Fence fence = openingFence(line);
				if (fence != null) {
					open = fence;
					code = new StringBuilder();
					firstCodeLine = true;
					if (!fence.lead().isBlank()) {
						chat.append(fence.lead()).append('\n');
					}
				} else {
					chat.append(line).append('\n');
				}
			} else if (closesFence(open, line)) {
				if (isScript(open, code)) {
					scripts.add(code.toString());
				}
				open = null;
				code = null;
			} else {
				if (!firstCodeLine) {
					code.append('\n');
				}
				firstCodeLine = false;
				code.append(stripIndent(line, open.indent()));
			}
		}
		boolean unterminated = open != null;
		if (unterminated && isScript(open, code)) {
			scripts.add(code.toString());
		}
		String script = scripts.isEmpty() ? null : String.join("\n", scripts);
		return new ParsedResponse(cleanChat(chat.toString()), script, unterminated);
	}

	/** True when a block with this fence and content is a build script. */
	static boolean isScript(Fence fence, CharSequence code) {
		return fence.script() || fence.tag().isEmpty() && callsApi(code);
	}

	/** True when the code calls a script API function ({@code fill(}, {@code block (}...). */
	static boolean callsApi(CharSequence code) {
		return API_CALL.matcher(code).find();
	}

	/** True when the text contains at least one fenced code block (closed or not). */
	static boolean hasCodeBlock(String text) {
		if (text == null) {
			return false;
		}
		for (String line : normalizeNewlines(text).split("\n", -1)) {
			if (openingFence(line) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Replaces every fenced code block (fences included; an unclosed one runs to the end) with {@code replacement};
	 * text before a fence on its line is kept on a line of its own.
	 */
	static String replaceCodeBlocks(String text, String replacement) {
		StringBuilder out = new StringBuilder(text.length());
		Fence open = null;
		for (String line : normalizeNewlines(text).split("\n", -1)) {
			if (open == null) {
				Fence fence = openingFence(line);
				if (!out.isEmpty()) {
					out.append('\n');
				}
				if (fence != null) {
					open = fence;
					if (!fence.lead().isBlank()) {
						out.append(fence.lead().stripTrailing()).append('\n');
					}
					out.append(replacement);
				} else {
					out.append(line);
				}
			} else if (closesFence(open, line)) {
				open = null;
			}
		}
		return out.toString();
	}

	/** Parses a line as an opening fence (at its start after any indentation, or after text), or returns null. */
	static Fence openingFence(String line) {
		int indent = indentation(line);
		if (indent >= line.length()) {
			return null;
		}
		char first = line.charAt(indent);
		if (first == '`' || first == '~') {
			Fence fence = fenceAt(line, indent, indent, "");
			if (fence != null) {
				return fence;
			}
		}
		// A backtick fence after text, e.g. "Here you go: ```js". Runs closed on the same line are code spans.
		int i = indent;
		while ((i = line.indexOf('`', i)) >= 0) {
			int end = runEnd(line, i, '`');
			int close = findBacktickRun(line, end, end - i);
			if (close >= 0) {
				i = close + (end - i);
			} else if (end - i >= 3 && i > indent) {
				return fenceAt(line, i, 0, line.substring(0, i));
			} else {
				i = end;
			}
		}
		return null;
	}

	/** The fence starting at {@code start}, or null when the run is too short or the info string is invalid. */
	private static Fence fenceAt(String line, int start, int indent, String lead) {
		char ch = line.charAt(start);
		int end = runEnd(line, start, ch);
		int length = end - start;
		if (length < 3) {
			return null;
		}
		String info = line.substring(end).strip();
		if (ch == '`' && info.indexOf('`') >= 0) {
			return null;
		}
		String tag = info.isEmpty() ? "" : info.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		return new Fence(ch, length, indent, tag, lead);
	}

	/** True when {@code line} closes the open fence (same character, at least as long, nothing else but whitespace). */
	static boolean closesFence(Fence open, String line) {
		int indent = indentation(line);
		int end = runEnd(line, indent, open.ch());
		return end - indent >= open.length() && line.substring(end).isBlank();
	}

	static String normalizeNewlines(String text) {
		return text.indexOf('\r') < 0 ? text : text.replace("\r\n", "\n").replace('\r', '\n');
	}

	/** Number of leading spaces and tabs. */
	private static int indentation(String line) {
		int i = 0;
		while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
			i++;
		}
		return i;
	}

	/** End (exclusive) of the run of {@code ch} starting at {@code from}. */
	private static int runEnd(String s, int from, char ch) {
		int end = from;
		while (end < s.length() && s.charAt(end) == ch) {
			end++;
		}
		return end;
	}

	private static String stripThinking(String text) {
		String result = THINK_BLOCK.matcher(text).replaceAll("");
		if (THINK_OPEN_AT_START.matcher(result).find()) {
			// Never closed: everything after the opening tag is reasoning.
			return "";
		}
		Matcher orphan = THINK_ORPHAN_CLOSE.matcher(result);
		if (orphan.find()) {
			// A closing tag without an opening one: the reasoning started at the very beginning, unless the text
			// before the tag has a code block (then the tag is more likely stray than a reasoning boundary).
			String before = result.substring(0, orphan.start());
			String after = result.substring(orphan.end());
			result = hasCodeBlock(before) ? before + after : after;
			result = THINK_ORPHAN_CLOSE.matcher(result).replaceAll("");
		}
		return result;
	}

	private static String stripIndent(String line, int indent) {
		int i = 0;
		while (i < indent && i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
			i++;
		}
		return line.substring(i);
	}

	private static String cleanChat(String raw) {
		StringBuilder sb = new StringBuilder(raw.length());
		for (String line : raw.split("\n", -1)) {
			String l = line.stripTrailing();
			Matcher heading = HEADING.matcher(l);
			if (heading.find()) {
				l = HEADING_CLOSE.matcher(l.substring(heading.end())).replaceFirst("");
				if (l.chars().allMatch(c -> c == '#')) {
					l = "";
				}
			}
			sb.append(l).append('\n');
		}
		String text = stripEmphasis(sb.toString());
		return BLANK_RUN.matcher(text).replaceAll("\n\n").strip();
	}

	/** Removes paired ** and __ markers outside inline code spans. */
	static String stripEmphasis(String text) {
		if (text.indexOf("**") < 0 && text.indexOf("__") < 0) {
			return text;
		}
		// Inline code spans and literal mask characters are each replaced by one mask character.
		List<String> masked = new ArrayList<>();
		StringBuilder sb = new StringBuilder(text.length());
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c == MASK) {
				masked.add(String.valueOf(MASK));
				sb.append(MASK);
				i++;
				continue;
			}
			if (c != '`') {
				sb.append(c);
				i++;
				continue;
			}
			int runEnd = runEnd(text, i, '`');
			int close = findBacktickRun(text, runEnd, runEnd - i);
			if (close < 0) {
				sb.append(text, i, runEnd);
				i = runEnd;
			} else {
				int end = close + (runEnd - i);
				masked.add(text.substring(i, end));
				sb.append(MASK);
				i = end;
			}
		}
		String result = BOLD_STARS.matcher(sb).replaceAll("$1");
		result = BOLD_UNDERSCORES.matcher(result).replaceAll("$1");
		if (masked.isEmpty()) {
			return result;
		}
		// The patterns only remove markers, so the masks are still there, in order.
		StringBuilder restored = new StringBuilder(result.length() + text.length() - sb.length());
		int next = 0;
		for (int j = 0; j < result.length(); j++) {
			char c = result.charAt(j);
			if (c == MASK) {
				restored.append(masked.get(next++));
			} else {
				restored.append(c);
			}
		}
		return restored.toString();
	}

	/** Start of the next run of exactly {@code length} backticks at or after {@code from}, or -1. */
	private static int findBacktickRun(String text, int from, int length) {
		int i = from;
		while (i < text.length()) {
			if (text.charAt(i) != '`') {
				i++;
				continue;
			}
			int end = runEnd(text, i, '`');
			if (end - i == length) {
				return i;
			}
			i = end;
		}
		return -1;
	}
}
