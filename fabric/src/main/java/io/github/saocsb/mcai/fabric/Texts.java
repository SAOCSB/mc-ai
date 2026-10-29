package io.github.saocsb.mcai.fabric;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** Chat components used by the mod: a gold {@code [<aiName>]} prefix followed by coloured text. */
public final class Texts {
	/** Lines of AI chat shown per reply. */
	static final int MAX_CHAT_LINES = 8;
	/** Characters of AI chat shown per reply. */
	static final int MAX_CHAT_CHARS = 1500;
	private static final String ELLIPSIS = "…";

	private Texts() {
	}

	/**
	 * Makes model-written chat safe to broadcast: removes {@code §} formatting codes, control characters other than
	 * line feeds and bidirectional-override characters, trims the text, and keeps at most {@link #MAX_CHAT_LINES}
	 * lines and {@link #MAX_CHAT_CHARS} characters (appending "…" when something was cut).
	 */
	public static String sanitizeChat(String text) {
		if (text == null) {
			return "";
		}
		StringBuilder clean = new StringBuilder(Math.min(text.length(), MAX_CHAT_CHARS + 16));
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '§') {
				i++; // the code character after the section sign
				continue;
			}
			if (c == '\r') {
				if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
					continue;
				}
				c = '\n';
			}
			if (c != '\n' && (Character.isISOControl(c) || isBidiControl(c))) {
				continue;
			}
			clean.append(c);
		}
		String result = clean.toString().strip();
		boolean cut = false;
		int lineEnd = -1;
		for (int line = 0; line < MAX_CHAT_LINES; line++) {
			lineEnd = result.indexOf('\n', lineEnd + 1);
			if (lineEnd < 0) {
				break;
			}
		}
		if (lineEnd >= 0) {
			result = result.substring(0, lineEnd).stripTrailing();
			cut = true;
		}
		if (result.length() > MAX_CHAT_CHARS) {
			int end = MAX_CHAT_CHARS;
			if (Character.isHighSurrogate(result.charAt(end - 1))) {
				end--;
			}
			result = result.substring(0, end).stripTrailing();
			cut = true;
		}
		return cut ? result + ELLIPSIS : result;
	}

	private static boolean isBidiControl(char c) {
		return c == '؜' || c == '‎' || c == '‏' || (c >= '‪' && c <= '‮')
				|| (c >= '⁦' && c <= '⁩');
	}

	/** The gold {@code [name] } prefix. */
	public static MutableComponent prefix(String aiName) {
		return Component.literal("[" + aiName + "] ").withStyle(ChatFormatting.GOLD);
	}

	/** A chat line spoken by the AI: gold name, white text. */
	public static Component chat(String aiName, String text) {
		return prefix(aiName).append(Component.literal(text).withStyle(ChatFormatting.WHITE));
	}

	/** A neutral notice (progress, results). */
	public static Component info(String aiName, String text) {
		return prefix(aiName).append(Component.literal(text).withStyle(ChatFormatting.YELLOW));
	}

	/** A success notice. */
	public static Component success(String aiName, String text) {
		return prefix(aiName).append(Component.literal(text).withStyle(ChatFormatting.GREEN));
	}

	/** An error notice. */
	public static Component error(String aiName, String text) {
		return prefix(aiName).append(Component.literal(text).withStyle(ChatFormatting.RED));
	}

	/** Action-bar text (no prefix). */
	public static Component overlay(String text) {
		return Component.literal(text).withStyle(ChatFormatting.AQUA);
	}

	/** Plain multi-line text such as the help page. */
	public static Component plain(String aiName, String text) {
		return prefix(aiName).append(Component.literal(text).withStyle(ChatFormatting.GRAY));
	}

	/** A notice that runs {@code command} when clicked (e.g. "/ai confirm"). */
	public static Component clickable(String aiName, String text, String command) {
		MutableComponent body = Component.literal(text).withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE)
				.withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
		return prefix(aiName).append(body);
	}
}
