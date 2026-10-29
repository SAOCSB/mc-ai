package io.github.saocsb.mcai.core.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds prompts. The system prompt is the resource {@code /mcai/prompt/system.md} with placeholders
 * {@code {{AI_NAME}}}, {@code {{MAX_BLOCKS}}}, {@code {{COMMANDS}}} (a sentence describing whether and which
 * commands are allowed) and {@code {{EXTRA}}} (extra instructions or empty) substituted. It contains no
 * per-request data so providers can cache it.
 */
public final class PromptBuilder {
	static final String RESOURCE = "/mcai/prompt/system.md";

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");
	private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s.*");
	private static final String EXTRA_LINE = "{{EXTRA}}";

	private static volatile String template;

	private PromptBuilder() {
	}

	/**
	 * Loads the template (UTF-8, cached) and substitutes the placeholders. When {@code extraInstructions} is
	 * blank, a line consisting only of {@code {{EXTRA}}} is removed, together with the markdown heading directly
	 * above it when that heading introduces nothing else, and the blank lines around it are collapsed.
	 *
	 * @param allowedCommands whitelisted command roots; empty list or {@code commandsEnabled == false} means
	 *                        the prompt tells the model world-action functions are disabled
	 * @throws IllegalStateException if the resource is missing
	 */
	public static String systemPrompt(String aiName, int maxBlocks, boolean commandsEnabled, List<String> allowedCommands,
			String extraInstructions) {
		return render(template(), aiName, maxBlocks, commandsSentence(commandsEnabled, allowedCommands), extraInstructions);
	}

	/** The user turn: {@code context.render()}, a blank line, then {@code "<playerName>: <message>"}. */
	public static String userTurn(GameContext context, String message) {
		return context.render() + "\n\n" + speakerLine(context.playerName(), message);
	}

	/**
	 * Feedback after a failed script, asking for a complete corrected script in one js block and no other
	 * chat text. States the attempt number ("Attempt 2 of 3"), the error verbatim in a fenced text block, and
	 * (for truncation) asks for a shorter script using loops/primitives; (for unknown block ids) reminds the model
	 * to use valid Java Edition 26.x ids.
	 */
	public static String repairTurn(String error, int attempt, int maxAttempts) {
		String err = error == null || error.isBlank() ? "(no error message)" : ResponseParser.normalizeNewlines(error).strip();
		String fence = "`".repeat(Math.max(3, longestRun(err, '`') + 1));
		String lower = err.toLowerCase(Locale.ROOT);
		StringBuilder sb = new StringBuilder(err.length() + 600);
		sb.append("Your build script did not work. Attempt ").append(attempt).append(" of ").append(maxAttempts)
				.append(".\n\nError:\n").append(fence).append("text\n").append(err).append('\n').append(fence).append("\n\n");
		if (lower.contains("cut off") || lower.contains("truncat") || lower.contains("token limit")) {
			sb.append("Your reply was too long. Write a much shorter script: use loops and the shape functions ")
					.append("(fill, hollow, walls, line, cylinder, sphere, dome, polygon...) instead of placing blocks ")
					.append("one by one, and keep comments short.\n\n");
		}
		if (lower.contains("unknown or invalid block") || lower.contains("unknown block") || lower.contains("invalid block id")) {
			sb.append("Use only valid Minecraft Java Edition 26.x block ids and block states (for example ")
					.append("minecraft:oak_planks or minecraft:oak_stairs[facing=north]); use a suggested id where one is given.\n\n");
		}
		if (lower.contains("placed no blocks")) {
			sb.append("The script must actually call the building functions (block, fill, ...) when it runs; ")
					.append("defining functions without calling them places nothing.\n\n");
		}
		sb.append("Reply with ONE complete, corrected ```js code block containing the whole script ")
				.append("(including any placement() and setName() calls) and no chat text before or after it.");
		return sb.toString();
	}

	/** {@code "<playerName>: <message>"}, the form user turns are stored in the conversation. */
	static String speakerLine(String playerName, String message) {
		return playerName + ": " + (message == null ? "" : message.strip());
	}

	static String commandsSentence(boolean enabled, List<String> allowedCommands) {
		Set<String> commands = new LinkedHashSet<>();
		if (allowedCommands != null) {
			for (String c : allowedCommands) {
				if (c != null && !c.isBlank()) {
					commands.add(c.strip());
				}
			}
		}
		if (!enabled || commands.isEmpty()) {
			return "World-action functions (summon, give, time, weather, effect, command) are disabled on this server; "
					+ "do not use them.";
		}
		return "World-action functions are enabled. Commands allowed on this server: " + String.join(", ", commands)
				+ ". summon/give/time/weather/effect only work if their command is in that list. Commands run as the player"
				+ " at the player's position; target the player with @s, because other selectors (@a, @e, @p[...]) and"
				+ " player names are rejected unless the server allows them.";
	}

	/** Substitutes the placeholders in {@code template}; unknown placeholders are left as they are. */
	static String render(String template, String aiName, int maxBlocks, String commandsSentence, String extra) {
		String text = ResponseParser.normalizeNewlines(template);
		boolean blankExtra = extra == null || extra.isBlank();
		if (blankExtra) {
			text = removeExtraLines(text);
		}
		Map<String, String> values = Map.of(
				"AI_NAME", String.valueOf(aiName),
				"MAX_BLOCKS", Integer.toString(maxBlocks),
				"COMMANDS", commandsSentence,
				"EXTRA", blankExtra ? "" : ResponseParser.normalizeNewlines(extra).strip());
		Matcher m = PLACEHOLDER.matcher(text);
		StringBuilder out = new StringBuilder(text.length() + 256);
		while (m.find()) {
			String value = values.get(m.group(1));
			m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
		}
		m.appendTail(out);
		return out.toString().strip();
	}

	private static String removeExtraLines(String text) {
		List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
		for (int i = 0; i < lines.size(); i++) {
			if (!lines.get(i).strip().equals(EXTRA_LINE)) {
				continue;
			}
			int prev = i - 1;
			while (prev >= 0 && lines.get(prev).isBlank()) {
				prev--;
			}
			int next = i + 1;
			while (next < lines.size() && lines.get(next).isBlank()) {
				next++;
			}
			boolean sectionOnly = prev >= 0 && HEADING.matcher(lines.get(prev)).matches()
					&& (next >= lines.size() || HEADING.matcher(lines.get(next)).matches());
			int from = sectionOnly ? prev : i;
			lines.subList(from, i + 1).clear();
			int start = from;
			while (start > 0 && lines.get(start - 1).isBlank()) {
				start--;
			}
			int end = from;
			while (end < lines.size() && lines.get(end).isBlank()) {
				end++;
			}
			int keepBlank = start == 0 || end == lines.size() ? 0 : 1;
			if (end - start > keepBlank) {
				lines.subList(start + keepBlank, end).clear();
			}
			i = start - 1;
		}
		return String.join("\n", lines);
	}

	private static String template() {
		String t = template;
		if (t == null) {
			synchronized (PromptBuilder.class) {
				t = template;
				if (t == null) {
					t = load();
					template = t;
				}
			}
		}
		return t;
	}

	private static String load() {
		try (InputStream in = PromptBuilder.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Missing resource " + RESOURCE);
			}
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			if (text.startsWith("\uFEFF")) {
				text = text.substring(1);
			}
			return ResponseParser.normalizeNewlines(text);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + RESOURCE, e);
		}
	}

	private static int longestRun(String s, char c) {
		int best = 0;
		int run = 0;
		for (int i = 0; i < s.length(); i++) {
			run = s.charAt(i) == c ? run + 1 : 0;
			best = Math.max(best, run);
		}
		return best;
	}
}
