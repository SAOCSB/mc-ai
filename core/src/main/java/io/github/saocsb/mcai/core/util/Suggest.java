package io.github.saocsb.mcai.core.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** "Did you mean" helpers for unknown block/item ids. */
public final class Suggest {
	private static final String MINECRAFT = "minecraft:";

	private Suggest() {
	}

	private record Scored(String candidate, String path, int tier, int distance) {
	}

	/**
	 * Up to {@code max} candidates closest to {@code input} (case-insensitive; namespace "minecraft:" ignored
	 * on both sides), best first. Ranking: exact path match, then candidates containing the input path or
	 * contained in it, then by Levenshtein distance; candidates with distance greater than
	 * max(2, inputPathLength / 2) are dropped unless they matched by containment.
	 *
	 * <p>Details: any block state or NBT suffix of the input ({@code [...]}, {@code {...}}) is ignored. A
	 * candidate counts as "contained in" the input only if it is at least half as long as the input (so
	 * {@code air} is not suggested for {@code oak_stair}). Within a tier candidates sort by distance, then
	 * length, then alphabetically. Candidates are returned verbatim, without duplicates; a null or blank
	 * input or {@code max <= 0} gives an empty list.
	 */
	public static List<String> closest(String input, Collection<String> candidates, int max) {
		if (input == null || candidates == null || max <= 0) {
			return List.of();
		}
		String query = path(input);
		int cut = indexOfAny(query, '[', '{');
		if (cut >= 0) {
			query = query.substring(0, cut).trim();
		}
		if (query.isEmpty()) {
			return List.of();
		}
		int threshold = Math.max(2, query.length() / 2);
		List<Scored> scored = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String candidate : candidates) {
			if (candidate == null || !seen.add(candidate)) {
				continue;
			}
			String path = path(candidate);
			int distance = levenshtein(query, path);
			int tier;
			if (path.equals(query)) {
				tier = 0;
			} else if (path.contains(query) || (query.contains(path) && path.length() * 2 >= query.length())) {
				tier = 1;
			} else if (distance <= threshold) {
				tier = 2;
			} else {
				continue;
			}
			scored.add(new Scored(candidate, path, tier, distance));
		}
		scored.sort(Comparator.comparingInt(Scored::tier)
				.thenComparingInt(Scored::distance)
				.thenComparingInt(s -> s.path().length())
				.thenComparing(Scored::path)
				.thenComparing(Scored::candidate));
		List<String> result = new ArrayList<>(Math.min(max, scored.size()));
		for (int i = 0; i < scored.size() && result.size() < max; i++) {
			result.add(scored.get(i).candidate());
		}
		return List.copyOf(result);
	}

	public static int levenshtein(String a, String b) {
		if (a.equals(b)) {
			return 0;
		}
		if (a.length() < b.length()) {
			String t = a;
			a = b;
			b = t;
		}
		int[] previous = new int[b.length() + 1];
		int[] current = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			current[0] = i;
			char ca = a.charAt(i - 1);
			for (int j = 1; j <= b.length(); j++) {
				int cost = ca == b.charAt(j - 1) ? 0 : 1;
				current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
			}
			int[] t = previous;
			previous = current;
			current = t;
		}
		return previous[b.length()];
	}

	private static String path(String id) {
		String lower = id.trim().toLowerCase(Locale.ROOT);
		return lower.startsWith(MINECRAFT) ? lower.substring(MINECRAFT.length()) : lower;
	}

	private static int indexOfAny(String s, char a, char b) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == a || c == b) {
				return i;
			}
		}
		return -1;
	}
}
