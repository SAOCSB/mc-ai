package io.github.saocsb.mcai.core.blueprint;

/**
 * Block-id string helpers shared by the script engine and the Minecraft adapter.
 *
 * <p>A block string is {@code [namespace:]path[ [state] ][ {nbt} ]}, e.g.
 * {@code minecraft:chest[facing=south]{Items:[...]}}.
 */
public final class BlockIds {
	private BlockIds() {
	}

	/**
	 * Canonical form: trimmed; the id part lower-cased and prefixed with {@code minecraft:} when it has
	 * no namespace; whitespace removed inside the {@code [...]} state; NBT kept verbatim. Leading
	 * {@code '#'} or {@code '/'} characters are not accepted (IllegalArgumentException).
	 * Examples: {@code " Stone "} -> {@code minecraft:stone};
	 * {@code "oak_stairs[ facing = north ]"} -> {@code minecraft:oak_stairs[facing=north]}.
	 *
	 * @throws IllegalArgumentException if blank or malformed (unbalanced brackets, empty id)
	 */
	public static String normalize(String raw) {
		throw new UnsupportedOperationException("TODO");
	}

	/** True for air, cave_air and void_air (with or without namespace, normalized or not). */
	public static boolean isAir(String blockId) {
		throw new UnsupportedOperationException("TODO");
	}

	/** The {@code namespace:path} part of a normalized block string (no state, no NBT). */
	public static String baseId(String normalized) {
		throw new UnsupportedOperationException("TODO");
	}
}
