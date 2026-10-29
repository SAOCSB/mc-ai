package io.github.saocsb.mcai.core.agent;

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
 * <li>Removes {@code <think>...</think>} / {@code <thinking>...</thinking>} sections (and an unclosed one at the start).</li>
 * <li>Fenced code blocks with ``` or ~~~ (fence length >= 3, closing fence at least as long). Blocks tagged
 *     js, javascript, mcai, build, or untagged blocks are script; blocks tagged anything else (json, text, ...)
 *     are dropped from both chat and script.</li>
 * <li>If the reply ends inside an open fence, the partial code is still returned as script and
 *     {@code unterminatedCode} is true.</li>
 * <li>Chat text: what remains, trimmed, with 3+ newlines collapsed to 2 and markdown emphasis markers
 *     ({@code **}, {@code __}, leading {@code #} heading marks) removed because Minecraft chat shows them literally.</li>
 * </ul>
 *
 * <p>Fences follow CommonMark: the fence line may be indented by up to 3 spaces, the info string after the fence
 * starts with the language tag (case-insensitive; anything after the first word is ignored), and a backtick
 * fence's info string may not contain backticks. Inline {@code `code`} spans stay in the chat text.
 */
public final class ResponseParser {
	/** Language tags (lower case) whose blocks are build scripts; "" is an untagged block. */
	static final Set<String> SCRIPT_TAGS = Set.of("", "js", "javascript", "mcai", "build");

	private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<(think|thinking)>.*?</\\1\\s*>");
	private static final Pattern THINK_OPEN_AT_START = Pattern.compile("(?is)^\\s*<(think|thinking)>");
	private static final Pattern THINK_ORPHAN_CLOSE = Pattern.compile("(?is)</(think|thinking)\\s*>");
	private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}(?:[ \\t]+|$)");
	private static final Pattern HEADING_CLOSE = Pattern.compile("[ \\t]+#+[ \\t]*$");
	private static final Pattern BOLD_STARS = Pattern.compile("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*");
	private static final Pattern BOLD_UNDERSCORES = Pattern.compile(
			"(?<![\\p{L}\\p{N}_])__(?=\\S)(.+?)(?<=\\S)__(?![\\p{L}\\p{N}_])");
	private static final Pattern BLANK_RUN = Pattern.compile("\n{3,}");
	private static final char CODE_SPAN_MARK = '\uE000';
	private static final Pattern CODE_SPAN_REF = Pattern.compile(CODE_SPAN_MARK + "(\\d+)" + CODE_SPAN_MARK);

	private ResponseParser() {
	}

	/** An opening code fence: fence character, run length, indentation (0-3), lower-case tag, script or not. */
	record Fence(char ch, int length, int indent, String tag, boolean script) {
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
				} else {
					chat.append(line).append('\n');
				}
			} else if (closesFence(open, line)) {
				if (open.script()) {
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
		if (unterminated && open.script()) {
			scripts.add(code.toString());
		}
		String script = scripts.isEmpty() ? null : String.join("\n", scripts);
		return new ParsedResponse(cleanChat(chat.toString()), script, unterminated);
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

	/** Replaces every fenced code block (fences included; an unclosed one runs to the end) with {@code replacement}. */
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

	/** Parses a line as an opening fence, or returns null. */
	static Fence openingFence(String line) {
		int indent = 0;
		while (indent < line.length() && line.charAt(indent) == ' ') {
			indent++;
		}
		if (indent > 3 || indent >= line.length()) {
			return null;
		}
		char ch = line.charAt(indent);
		if (ch != '`' && ch != '~') {
			return null;
		}
		int end = indent;
		while (end < line.length() && line.charAt(end) == ch) {
			end++;
		}
		int length = end - indent;
		if (length < 3) {
			return null;
		}
		String info = line.substring(end).strip();
		if (ch == '`' && info.indexOf('`') >= 0) {
			return null;
		}
		String tag = info.isEmpty() ? "" : info.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		return new Fence(ch, length, indent, tag, SCRIPT_TAGS.contains(tag));
	}

	/** True when {@code line} closes the open fence (same character, at least as long, nothing else). */
	static boolean closesFence(Fence open, String line) {
		int indent = 0;
		while (indent < line.length() && line.charAt(indent) == ' ') {
			indent++;
		}
		if (indent > 3) {
			return false;
		}
		int end = indent;
		while (end < line.length() && line.charAt(end) == open.ch()) {
			end++;
		}
		return end - indent >= open.length() && line.substring(end).isBlank();
	}

	static String normalizeNewlines(String text) {
		return text.indexOf('\r') < 0 ? text : text.replace("\r\n", "\n").replace('\r', '\n');
	}

	private static String stripThinking(String text) {
		String result = THINK_BLOCK.matcher(text).replaceAll("");
		if (THINK_OPEN_AT_START.matcher(result).find()) {
			// Never closed: everything after the opening tag is reasoning.
			return "";
		}
		Matcher orphan = THINK_ORPHAN_CLOSE.matcher(result);
		if (orphan.find()) {
			// A closing tag without an opening one: the reasoning started at the very beginning.
			result = result.substring(orphan.end());
		}
		return result;
	}

	private static String stripIndent(String line, int indent) {
		int i = 0;
		while (i < indent && i < line.length() && line.charAt(i) == ' ') {
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
	private static String stripEmphasis(String text) {
		if (text.indexOf("**") < 0 && text.indexOf("__") < 0) {
			return text;
		}
		List<String> spans = new ArrayList<>();
		StringBuilder masked = new StringBuilder(text.length());
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c != '`') {
				masked.append(c);
				i++;
				continue;
			}
			int runEnd = i;
			while (runEnd < text.length() && text.charAt(runEnd) == '`') {
				runEnd++;
			}
			int close = findBacktickRun(text, runEnd, runEnd - i);
			if (close < 0) {
				masked.append(text, i, runEnd);
				i = runEnd;
			} else {
				int end = close + (runEnd - i);
				spans.add(text.substring(i, end));
				masked.append(CODE_SPAN_MARK).append(spans.size() - 1).append(CODE_SPAN_MARK);
				i = end;
			}
		}
		String result = BOLD_STARS.matcher(masked).replaceAll("$1");
		result = BOLD_UNDERSCORES.matcher(result).replaceAll("$1");
		if (spans.isEmpty()) {
			return result;
		}
		Matcher m = CODE_SPAN_REF.matcher(result);
		StringBuilder restored = new StringBuilder(result.length());
		while (m.find()) {
			m.appendReplacement(restored, Matcher.quoteReplacement(spans.get(Integer.parseInt(m.group(1)))));
		}
		m.appendTail(restored);
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
			int end = i;
			while (end < text.length() && text.charAt(end) == '`') {
				end++;
			}
			if (end - i == length) {
				return i;
			}
			i = end;
		}
		return -1;
	}
}
