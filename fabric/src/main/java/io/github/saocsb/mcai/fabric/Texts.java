package io.github.saocsb.mcai.fabric;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** Chat components used by the mod: a gold {@code [<aiName>]} prefix followed by coloured text. */
public final class Texts {
	private Texts() {
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
