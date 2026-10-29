package io.github.saocsb.mcai.core.placement;

import java.util.Locale;

/** Which world Y local y = 0 maps to (ignored for ABSOLUTE and PREVIOUS). */
public enum GroundMode {
	/** The block layer the player stands in (feet level). */
	PLAYER,
	/** The terrain surface (first free block above the ground) at the build's centre column. */
	SURFACE;

	public static GroundMode parse(String s) {
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
