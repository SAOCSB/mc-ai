package io.github.saocsb.mcai.core.util;

import java.util.Collection;
import java.util.List;

/** "Did you mean" helpers for unknown block/item ids. */
public final class Suggest {
	private Suggest() {
	}

	/**
	 * Up to {@code max} candidates closest to {@code input} (case-insensitive; namespace "minecraft:" ignored
	 * on both sides), best first. Ranking: exact path match, then candidates containing the input path or
	 * contained in it, then by Levenshtein distance; candidates with distance greater than
	 * max(2, inputPathLength / 2) are dropped unless they matched by containment.
	 */
	public static List<String> closest(String input, Collection<String> candidates, int max) {
		throw new UnsupportedOperationException("TODO");
	}

	public static int levenshtein(String a, String b) {
		throw new UnsupportedOperationException("TODO");
	}
}
