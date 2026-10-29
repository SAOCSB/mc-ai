package io.github.saocsb.mcai.core.blueprint;

import io.github.saocsb.mcai.core.util.Suggest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic replacement candidates for block ids the game rejected (typical LLM misspellings such as
 * {@code stone_brick} or {@code oak_planks_stairs}). Pure string rules: the caller validates the candidates
 * against the registry and uses the first valid one.
 */
public final class BlockFixer {
	/**
	 * Known wrong path -> correct path (paths without namespace; a value may carry its own block state). Applied to
	 * {@code minecraft:} ids only. Every key is not a block in 26.3 and every value is one.
	 */
	static final Map<String, String> ALIASES = Map.ofEntries(
			Map.entry("stone_brick", "stone_bricks"),
			Map.entry("stone_bricks_stairs", "stone_brick_stairs"),
			Map.entry("stone_bricks_slab", "stone_brick_slab"),
			Map.entry("brick", "bricks"),
			Map.entry("brick_block", "bricks"),
			Map.entry("bricks_stairs", "brick_stairs"),
			Map.entry("quartz_block_stairs", "quartz_stairs"),
			Map.entry("deepslate_stairs", "cobbled_deepslate_stairs"),
			Map.entry("deepslate_wall", "cobbled_deepslate_wall"),
			Map.entry("smooth_stone_stairs", "stone_stairs"),
			Map.entry("grass", "short_grass"),
			Map.entry("grass_path", "dirt_path"),
			Map.entry("glass_panes", "glass_pane"),
			Map.entry("white_glass_pane", "white_stained_glass_pane"),
			Map.entry("glass_block", "glass"),
			Map.entry("stained_glass", "white_stained_glass"),
			Map.entry("stained_glass_pane", "white_stained_glass_pane"),
			Map.entry("concrete", "white_concrete"),
			Map.entry("concrete_powder", "white_concrete_powder"),
			Map.entry("wool", "white_wool"),
			Map.entry("carpet", "white_carpet"),
			Map.entry("planks", "oak_planks"),
			Map.entry("log", "oak_log"),
			Map.entry("leaves", "oak_leaves"),
			Map.entry("copper", "copper_block"),
			Map.entry("iron_fence", "iron_bars"),
			Map.entry("cobblestone_fence", "cobblestone_wall"),
			Map.entry("stone_fence", "stone_brick_wall"),
			Map.entry("polished_stone", "smooth_stone"),
			Map.entry("marble", "smooth_quartz"),
			Map.entry("hanging_lantern", "lantern[hanging=true]"),
			Map.entry("wall_lantern", "lantern"),
			Map.entry("chain", "iron_chain"));

	/** Suffixes that name a block family; a fuzzy suggestion may only replace an id of the same family. */
	static final List<String> FAMILY_SUFFIXES = List.of("_stairs", "_slab", "_wall", "_fence", "_fence_gate", "_pane",
			"_door", "_trapdoor", "_button", "_planks", "_log", "_wood", "_leaves", "_bricks", "_glass");

	/** Largest Levenshtein distance between the invalid path and a fuzzy suggestion's path. */
	static final int MAX_SUGGESTION_DISTANCE = 2;

	private static final String COLOURS = "white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple"
			+ "|blue|brown|green|red|black";

	/** Structural rewrites (pattern, replacement), tried in order after the alias map. */
	private static final List<Rule> RULES = List.of(
			// red_glass -> red_stained_glass, red_glass_pane -> red_stained_glass_pane
			new Rule("^(" + COLOURS + ")_glass(_pane)?$", "$1_stained_glass$2"),
			// oak_planks_stairs -> oak_stairs (also the singular oak_plank_stairs)
			new Rule("^(\\w+)_planks?_(stairs|slab|fence|fence_gate|door|trapdoor|button|pressure_plate)$", "$1_$2"),
			// stone_bricks_wall -> stone_brick_wall, bricks_slab -> brick_slab
			new Rule("^((?:\\w+_)?)bricks_(stairs|slab|wall|fence)$", "$1brick_$2"),
			// deepslate_tiles_stairs -> deepslate_tile_stairs
			new Rule("^(\\w+)_tiles_(stairs|slab|wall)$", "$1_tile_$2"),
			// quartz_block_stairs -> quartz_stairs
			new Rule("^(\\w+)_block_(stairs|slab)$", "$1_$2"),
			// singular / plural slips
			new Rule("^(\\w+)_brick$", "$1_bricks"),
			new Rule("^(\\w+)_bricks$", "$1_brick"),
			new Rule("^(\\w+)_plank$", "$1_planks"),
			new Rule("^(\\w+)_tile$", "$1_tiles"),
			new Rule("^(\\w+)_stair$", "$1_stairs"),
			new Rule("^(\\w+)_(slab|wall|fence|fence_gate|pane|door|trapdoor|button|pressure_plate|log)s$", "$1_$2"));

	private BlockFixer() {
	}

	private record Rule(Pattern pattern, String replacement) {
		Rule(String regex, String replacement) {
			this(Pattern.compile(regex), replacement);
		}

		String apply(String path) {
			Matcher m = pattern.matcher(path);
			return m.matches() ? m.replaceFirst(replacement) : null;
		}
	}

	/** A normalized block string split into {@code namespace:path}, {@code [state]} ("" if none) and {@code {nbt}} ("" if none). */
	private record Parts(String namespace, String path, String state, String nbt) {
		/** Splits the output of {@link BlockIds#normalize} (which always carries a namespace). */
		static Parts of(String normalized) {
			String base = BlockIds.baseId(normalized);
			String rest = normalized.substring(base.length());
			String state = "";
			if (rest.startsWith("[")) {
				int end = closingBracket(rest);
				state = rest.substring(0, end + 1);
				rest = rest.substring(end + 1);
			}
			int colon = base.indexOf(':');
			return new Parts(base.substring(0, colon), base.substring(colon + 1), state, rest);
		}

		String id(String newPath, String newState) {
			return namespace + ':' + newPath + newState + nbt;
		}
	}

	/**
	 * Replacement ids to try for a block id the game rejected, best first; all normalized, without duplicates and
	 * never the input itself. Empty when nothing applies or the input is not a valid block string.
	 *
	 * <p>Order: the alias map ({@code minecraft:} only), then the structural rules (uncoloured/colour glass,
	 * {@code <wood>_planks_<shape>}, {@code <x>_bricks_<shape>}, {@code <x>_tiles_<shape>}, {@code <x>_block_<shape>},
	 * singular/plural slips). Each replacement keeps the original block state unless it brings its own state
	 * ({@code hanging_lantern -> lantern[hanging=true]}); NBT is always kept. Then the same replacements without
	 * the original state, and finally the input itself without its state (covers invalid property names or
	 * values). The state-less variants are only added when the input has a state.
	 */
	public static List<String> candidates(String normalizedInvalidId) {
		String input;
		Parts parts;
		try {
			input = BlockIds.normalize(normalizedInvalidId);
			parts = Parts.of(input);
		} catch (IllegalArgumentException e) {
			return List.of();
		}
		Set<String> out = new LinkedHashSet<>();
		List<String> stateless = new ArrayList<>();
		for (String replacement : replacements(parts.namespace(), parts.path())) {
			int bracket = replacement.indexOf('[');
			if (bracket >= 0) {
				out.add(parts.id(replacement.substring(0, bracket), replacement.substring(bracket)));
			} else {
				out.add(parts.id(replacement, parts.state()));
				if (!parts.state().isEmpty()) {
					stateless.add(parts.id(replacement, ""));
				}
			}
		}
		out.addAll(stateless);
		if (!parts.state().isEmpty()) {
			out.add(parts.id(parts.path(), ""));
		}
		out.remove(input);
		return List.copyOf(out);
	}

	/**
	 * Fuzzy "did you mean" suggestions from the registry that are safe to apply without asking the model: same
	 * namespace, same block family as the invalid id ({@link #FAMILY_SUFFIXES}; singular/plural forms of the
	 * suffix count as the same family, e.g. {@code oak_stair} vs {@code _stairs}) and a Levenshtein distance of at
	 * most {@link #MAX_SUGGESTION_DISTANCE} between the paths (states ignored). Each accepted suggestion yields the
	 * suggestion with the invalid id's state and NBT, followed (after all of those) by the suggestion with the NBT
	 * only. All normalized, in suggestion order, without duplicates and never the input itself.
	 */
	public static List<String> suggestionCandidates(String normalizedInvalidId, List<String> suggestions) {
		if (suggestions == null || suggestions.isEmpty()) {
			return List.of();
		}
		String input;
		Parts parts;
		try {
			input = BlockIds.normalize(normalizedInvalidId);
			parts = Parts.of(input);
		} catch (IllegalArgumentException e) {
			return List.of();
		}
		Set<String> out = new LinkedHashSet<>();
		List<String> stateless = new ArrayList<>();
		for (String suggestion : suggestions) {
			Parts s;
			try {
				s = suggestion == null ? null : Parts.of(BlockIds.normalize(suggestion));
			} catch (IllegalArgumentException e) {
				s = null;
			}
			if (s == null || !s.namespace().equals(parts.namespace()) || !sameFamily(parts.path(), s.path())
					|| Suggest.levenshtein(parts.path(), s.path()) > MAX_SUGGESTION_DISTANCE) {
				continue;
			}
			out.add(parts.id(s.path(), parts.state()));
			if (!parts.state().isEmpty()) {
				stateless.add(parts.id(s.path(), ""));
			}
		}
		out.addAll(stateless);
		out.remove(input);
		return List.copyOf(out);
	}

	/** True when {@code suggestionPath} has a family suffix that {@code invalidPath} also has (in any singular/plural form). */
	static boolean sameFamily(String invalidPath, String suggestionPath) {
		String family = family(suggestionPath);
		if (family == null) {
			return false;
		}
		if (invalidPath.endsWith(family) || invalidPath.endsWith(family + "s")) {
			return true;
		}
		return family.endsWith("s") && invalidPath.endsWith(family.substring(0, family.length() - 1));
	}

	/** The family suffix a path ends with, or null. */
	static String family(String path) {
		for (String suffix : FAMILY_SUFFIXES) {
			if (path.endsWith(suffix) && path.length() > suffix.length()) {
				return suffix;
			}
		}
		return null;
	}

	/** Alias and rule replacements for a path, in order, possibly with their own state. */
	private static List<String> replacements(String namespace, String path) {
		List<String> list = new ArrayList<>(4);
		if (namespace.equals("minecraft")) {
			String alias = ALIASES.get(path);
			if (alias != null) {
				list.add(alias);
			}
		}
		for (Rule rule : RULES) {
			String replaced = rule.apply(path);
			if (replaced != null && !replaced.equals(path)) {
				list.add(replaced);
			}
		}
		return list;
	}

	/** Index of the ']' closing the '[' at index 0, skipping quoted strings. */
	private static int closingBracket(String s) {
		int depth = 0;
		char quote = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (quote != 0) {
				if (c == '\\') {
					i++;
				} else if (c == quote) {
					quote = 0;
				}
			} else if (c == '"' || c == '\'') {
				quote = c;
			} else if (c == '[' || c == '{') {
				depth++;
			} else if ((c == ']' || c == '}') && --depth == 0) {
				return i;
			}
		}
		throw new IllegalArgumentException("unbalanced state in block id: " + s);
	}
}
