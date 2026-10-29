package io.github.saocsb.mcai.core.placement;

import java.util.Locale;

/**
 * How a blueprint's local coordinates are anchored in the world. In every mode except
 * {@link #ABSOLUTE} the local frame is turned so that the player looks toward local -z (north),
 * local +x is the player's right, and the structure's front (+z / south side) faces the player.
 */
public enum PlacementMode {
	/** In front of the player: bounding box centred on the view line, nearest face {@code distance} blocks away. */
	FRONT,
	/** Bounding box centred (horizontally) on the player. */
	CENTER,
	/** Local (0, 0, 0) is the player's feet position. */
	ORIGIN,
	/** Same world transform as the player's previous build (for modifications). */
	PREVIOUS,
	/** Local coordinates are offsets from an absolute world position, without rotation. */
	ABSOLUTE;

	/** Parses a case-insensitive mode name; null for unknown names. */
	public static PlacementMode parse(String s) {
		if (s == null) {
			return null;
		}
		try {
			return valueOf(s.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
