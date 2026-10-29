package io.github.saocsb.mcai.fabric;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sends chat lines that start with a configured prefix (default {@code @ai}) to the AI. The prefix matches
 * case-insensitively when followed by the end of the line, whitespace, punctuation or any non-ASCII character
 * ({@code "@ai build"}, {@code "@AI, hi"}, {@code "@ai建个房子"}), but not {@code "@aim"}.
 */
public final class ChatTrigger {
	private static final String SEPARATORS = ",:，：、";

	private ChatTrigger() {
	}

	/**
	 * Handles a chat message (ALLOW_CHAT_MESSAGE, server thread). Never blocks: the request starts at the end of
	 * the tick.
	 *
	 * @return whether the message is still broadcast
	 */
	public static boolean onChat(AiService service, ServerPlayer sender, String content) {
		List<String> prefixes = service.config().chatPrefixes;
		Match match = match(content, prefixes);
		if (match == null) {
			return true;
		}
		if (match.message().isEmpty()) {
			service.runNextTick(() -> service.emptyMessageHint(sender, match.prefix()));
		} else {
			service.runNextTick(() -> service.ask(sender, match.message()));
		}
		return service.config().showTriggerMessages;
	}

	/** A matched prefix and the message after it (leading separators removed). */
	public record Match(String prefix, String message) {
	}

	/** Matches {@code content} against the prefixes (longest first); null when none applies. */
	public static Match match(String content, List<String> prefixes) {
		if (content == null || prefixes == null) {
			return null;
		}
		String text = content.stripLeading();
		List<String> sorted = new ArrayList<>();
		for (String prefix : prefixes) {
			if (prefix != null && !prefix.isBlank()) {
				sorted.add(prefix.trim());
			}
		}
		sorted.sort(Comparator.comparingInt(String::length).reversed());
		for (String prefix : sorted) {
			if (!text.regionMatches(true, 0, prefix, 0, prefix.length())) {
				continue;
			}
			if (text.length() > prefix.length() && !isBoundary(text.charAt(prefix.length()))) {
				continue;
			}
			return new Match(prefix, stripSeparators(text.substring(prefix.length())));
		}
		return null;
	}

	private static boolean isBoundary(char c) {
		return c >= 128 || (!Character.isLetterOrDigit(c) && c != '_');
	}

	private static String stripSeparators(String rest) {
		int i = 0;
		while (i < rest.length() && (Character.isWhitespace(rest.charAt(i)) || Character.isSpaceChar(rest.charAt(i))
				|| SEPARATORS.indexOf(rest.charAt(i)) >= 0)) {
			i++;
		}
		return rest.substring(i).trim();
	}
}
