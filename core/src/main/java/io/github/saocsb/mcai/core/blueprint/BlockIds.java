package io.github.saocsb.mcai.core.blueprint;

/**
 * Block-id string helpers shared by the script engine and the Minecraft adapter.
 *
 * <p>A block string is {@code [namespace:]path[ [state] ][ {nbt} ]}, e.g.
 * {@code minecraft:chest[facing=south]{Items:[...]}}.
 */
public final class BlockIds {
	private static final String MINECRAFT = "minecraft:";

	private BlockIds() {
	}

	/**
	 * Canonical form: trimmed; the id part lower-cased and prefixed with {@code minecraft:} when it has
	 * no namespace; whitespace removed inside the {@code [...]} state; NBT kept verbatim. Leading
	 * {@code '#'} or {@code '/'} characters are not accepted (IllegalArgumentException).
	 * Examples: {@code " Stone "} -> {@code minecraft:stone};
	 * {@code "oak_stairs[ facing = north ]"} -> {@code minecraft:oak_stairs[facing=north]}.
	 *
	 * <p>Details: the id must be a valid resource location after lower-casing (namespace
	 * {@code [a-z0-9_.-]}, path {@code [a-z0-9_./-]}; an empty namespace as in {@code ":stone"} means
	 * {@code minecraft}). Whitespace between the id, the state and the NBT is dropped, and an empty
	 * state {@code []} is dropped. Brackets and braces must balance (quoted strings are skipped) and the
	 * state must come before the NBT. Whitespace inside quoted strings in the state is kept.
	 *
	 * @throws IllegalArgumentException if blank or malformed (unbalanced brackets, empty id)
	 */
	public static String normalize(String raw) {
		if (raw == null) {
			throw new IllegalArgumentException("block id is null");
		}
		String s = raw.trim();
		if (s.isEmpty()) {
			throw new IllegalArgumentException("block id is blank");
		}
		char first = s.charAt(0);
		if (first == '#' || first == '/') {
			throw new IllegalArgumentException("block id must not start with '" + first + "': " + raw);
		}
		int n = s.length();
		int i = 0;
		while (i < n && s.charAt(i) != '[' && s.charAt(i) != '{') {
			i++;
		}
		StringBuilder out = new StringBuilder(n + MINECRAFT.length());
		appendId(out, s.substring(0, i).trim(), raw);
		if (i < n && s.charAt(i) == '[') {
			int end = matchingClose(s, i, raw);
			int mark = out.length();
			appendWithoutWhitespace(out, s, i, end + 1);
			if (out.length() - mark == 2) {
				out.setLength(mark);
			}
			i = skipWhitespace(s, end + 1);
		}
		if (i < n && s.charAt(i) == '{') {
			int end = matchingClose(s, i, raw);
			out.append(s, i, end + 1);
			i = end + 1;
		}
		if (i < n) {
			throw new IllegalArgumentException("unexpected '" + s.charAt(i) + "' at position " + i + " in block id: " + raw);
		}
		return out.toString();
	}

	/** True for air, cave_air and void_air (with or without namespace, normalized or not). */
	public static boolean isAir(String blockId) {
		if (blockId == null || !containsAirIgnoreCase(blockId)) {
			return false;
		}
		switch (blockId) {
			case "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "air", "cave_air", "void_air" -> {
				return true;
			}
			default -> {
			}
		}
		String base;
		try {
			base = baseId(normalize(blockId));
		} catch (IllegalArgumentException e) {
			return false;
		}
		return base.equals("minecraft:air") || base.equals("minecraft:cave_air") || base.equals("minecraft:void_air");
	}

	/**
	 * The {@code namespace:path} part of a normalized block string (no state, no NBT). A string without a
	 * namespace gets {@code minecraft:}; no other normalization is applied.
	 */
	public static String baseId(String normalized) {
		int end = normalized.length();
		for (int i = 0; i < end; i++) {
			char c = normalized.charAt(i);
			if (c == '[' || c == '{') {
				end = i;
				break;
			}
		}
		String base = normalized.substring(0, end);
		return base.indexOf(':') >= 0 ? base : MINECRAFT + base;
	}

	private static void appendId(StringBuilder out, String id, String raw) {
		if (id.isEmpty()) {
			throw new IllegalArgumentException("empty block id: " + raw);
		}
		int colon = id.indexOf(':');
		String namespace = colon < 0 ? "" : id.substring(0, colon);
		String path = colon < 0 ? id : id.substring(colon + 1);
		if (path.isEmpty()) {
			throw new IllegalArgumentException("empty block id: " + raw);
		}
		if (namespace.isEmpty()) {
			namespace = "minecraft";
		}
		for (int i = 0; i < namespace.length(); i++) {
			char c = Character.toLowerCase(namespace.charAt(i));
			if (!validNamespaceChar(c)) {
				throw new IllegalArgumentException("invalid character '" + namespace.charAt(i) + "' in block id: " + raw);
			}
			out.append(c);
		}
		out.append(':');
		for (int i = 0; i < path.length(); i++) {
			char c = Character.toLowerCase(path.charAt(i));
			if (!validNamespaceChar(c) && c != '/') {
				throw new IllegalArgumentException("invalid character '" + path.charAt(i) + "' in block id: " + raw);
			}
			out.append(c);
		}
	}

	private static boolean validNamespaceChar(char c) {
		return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
	}

	/** Index of the bracket that closes the one at {@code open}, skipping quoted strings. */
	private static int matchingClose(String s, int open, String raw) {
		char[] stack = new char[8];
		int depth = 0;
		char quote = 0;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (quote != 0) {
				if (c == '\\') {
					i++;
				} else if (c == quote) {
					quote = 0;
				}
				continue;
			}
			switch (c) {
				case '"', '\'' -> quote = c;
				case '[', '{' -> {
					if (depth == stack.length) {
						stack = java.util.Arrays.copyOf(stack, depth * 2);
					}
					stack[depth++] = c == '[' ? ']' : '}';
				}
				case ']', '}' -> {
					if (stack[--depth] != c) {
						throw new IllegalArgumentException("mismatched '" + c + "' at position " + i + " in block id: " + raw);
					}
					if (depth == 0) {
						return i;
					}
				}
				default -> {
				}
			}
		}
		throw new IllegalArgumentException(quote != 0
				? "unterminated string in block id: " + raw
				: "unbalanced brackets in block id: " + raw);
	}

	private static void appendWithoutWhitespace(StringBuilder out, String s, int from, int to) {
		char quote = 0;
		for (int i = from; i < to; i++) {
			char c = s.charAt(i);
			if (quote != 0) {
				out.append(c);
				if (c == '\\' && i + 1 < to) {
					out.append(s.charAt(++i));
				} else if (c == quote) {
					quote = 0;
				}
			} else if (c == '"' || c == '\'') {
				quote = c;
				out.append(c);
			} else if (!Character.isWhitespace(c)) {
				out.append(c);
			}
		}
	}

	private static int skipWhitespace(String s, int i) {
		while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
			i++;
		}
		return i;
	}

	private static boolean containsAirIgnoreCase(String s) {
		for (int i = 0, end = s.length() - 2; i < end; i++) {
			if ((s.charAt(i) | 0x20) == 'a' && (s.charAt(i + 1) | 0x20) == 'i' && (s.charAt(i + 2) | 0x20) == 'r') {
				return true;
			}
		}
		return false;
	}
}
