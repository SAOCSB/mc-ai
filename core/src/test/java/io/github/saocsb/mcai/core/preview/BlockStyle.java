package io.github.saocsb.mcai.core.preview;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How one block string is drawn: colours per world face direction, opacity and a simplified shape
 * made of axis-aligned boxes in 1/16 block units. States are read only for shape (stairs facing,
 * slab type, door facing, log axis...); NBT is ignored.
 */
final class BlockStyle {
	enum Kind {
		/** Not drawn (air, barrier, light, structure_void). */
		INVISIBLE, FULL, BOX, SLAB, STAIRS, FENCE, WALL, PANE, PANEL, GATE, TRAPDOOR, SNOW, VINE
	}

	private static final Set<String> INVISIBLE = Set.of("air", "cave_air", "void_air", "barrier", "light",
			"structure_void", "moving_piston");
	private static final Set<String> PLANTS = Set.of("poppy", "dandelion", "blue_orchid", "allium", "azure_bluet",
			"oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "peony",
			"torchflower", "pitcher_plant", "short_grass", "tall_grass", "grass", "fern", "large_fern", "wheat",
			"carrots", "potatoes", "beetroots", "melon_stem", "pumpkin_stem", "nether_wart", "kelp", "kelp_plant",
			"seagrass", "tall_seagrass", "spore_blossom", "small_dripleaf", "big_dripleaf", "cave_vines",
			"cave_vines_plant", "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant",
			"nether_sprouts", "amethyst_cluster", "pointed_dripstone", "azalea", "flowering_azalea", "brown_mushroom",
			"red_mushroom", "cactus_flower", "open_eyeblossom", "closed_eyeblossom", "short_dry_grass",
			"tall_dry_grass", "sweet_berry_bush", "cocoa", "torchflower_crop", "pitcher_crop", "mangrove_propagule");

	final String id;
	final String path;
	final Map<String, String> state;
	final Kind kind;
	/** BOX: x0 y0 z0 x1 y1 z1. PANEL: from, to (along the wall), y0, y1, thickness. */
	final int[] dims;
	/** RGB of faces pointing up, north/south and east/west. */
	final int up, ns, ew;
	final int alpha;
	/** The colour came from name heuristics or a hash, not the table: the id may be misspelt or modded. */
	final boolean guessed;

	private BlockStyle(String id, String path, Map<String, String> state, Kind kind, int[] dims, int up, int ns,
			int ew, int alpha, boolean guessed) {
		this.id = id;
		this.path = path;
		this.state = state;
		this.kind = kind;
		this.dims = dims;
		this.up = up;
		this.ns = ns;
		this.ew = ew;
		this.alpha = alpha;
		this.guessed = guessed;
	}

	/** Full opaque cube: hides the faces of neighbours that touch it. */
	boolean occluder() {
		return kind == Kind.FULL && alpha == 255;
	}

	boolean visible() {
		return kind != Kind.INVISIBLE;
	}

	/** Fences, walls and panes: arms reach toward matching neighbours. */
	boolean connecting() {
		return kind == Kind.FENCE || kind == Kind.WALL || kind == Kind.PANE;
	}

	/** The id without namespace, state or NBT: {@code minecraft:oak_stairs[facing=east]} -> {@code oak_stairs}. */
	static String barePath(String id) {
		String s = id.trim();
		int cut = s.length();
		int b = s.indexOf('[');
		int c = s.indexOf('{');
		if (b >= 0) {
			cut = b;
		}
		if (c >= 0 && c < cut) {
			cut = c;
		}
		s = s.substring(0, cut).trim();
		int colon = s.indexOf(':');
		return (colon >= 0 ? s.substring(colon + 1) : s).toLowerCase(Locale.ROOT);
	}

	/** Block type for legends: the bare path, prefixed with its namespace unless that is minecraft. */
	static String typeName(String id) {
		String path = barePath(id);
		String s = id.trim();
		int colon = s.indexOf(':');
		int b = s.indexOf('[');
		int c = s.indexOf('{');
		if (colon > 0 && (b < 0 || colon < b) && (c < 0 || colon < c)) {
			String ns = s.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			if (!ns.equals("minecraft")) {
				return ns + ":" + path;
			}
		}
		return path;
	}

	/** Parses the {@code [k=v,...]} block state part (empty map if none). */
	static Map<String, String> parseState(String id) {
		Map<String, String> map = new HashMap<>();
		int b = id.indexOf('[');
		int c = id.indexOf('{');
		if (b < 0 || (c >= 0 && c < b)) {
			return map;
		}
		int e = id.indexOf(']', b);
		if (e < 0) {
			return map;
		}
		for (String kv : id.substring(b + 1, e).split(",")) {
			int eq = kv.indexOf('=');
			if (eq > 0) {
				map.put(kv.substring(0, eq).trim().toLowerCase(Locale.ROOT), kv.substring(eq + 1).trim().toLowerCase(Locale.ROOT));
			}
		}
		return map;
	}

	static boolean isAir(String id) {
		String p = barePath(id);
		return p.equals("air") || p.equals("cave_air") || p.equals("void_air");
	}

	static BlockStyle of(String id) {
		String path = barePath(id);
		Map<String, String> state = parseState(id);
		BlockColors.Rgb rgb = BlockColors.lookup(path);
		int up = rgb.top();
		int ns = rgb.side();
		int ew = rgb.side();
		String axis = state.get("axis");
		if (axis != null && rgb.top() != rgb.side()) {
			// Logs, pillars, basalt, hay: the end grain faces along the axis.
			up = axis.equals("y") ? rgb.top() : rgb.side();
			ns = axis.equals("z") ? rgb.top() : rgb.side();
			ew = axis.equals("x") ? rgb.top() : rgb.side();
		}
		Kind kind = Kind.FULL;
		int[] dims = null;
		if (INVISIBLE.contains(path)) {
			kind = Kind.INVISIBLE;
		} else if (path.endsWith("_stairs")) {
			kind = Kind.STAIRS;
		} else if (path.endsWith("_slab")) {
			kind = Kind.SLAB;
		} else if (path.endsWith("_fence_gate")) {
			kind = Kind.GATE;
		} else if (path.endsWith("_fence")) {
			kind = Kind.FENCE;
		} else if (path.endsWith("_wall")) {
			kind = Kind.WALL;
		} else if (path.endsWith("_pane") || path.endsWith("_bars")) {
			kind = Kind.PANE;
		} else if (path.endsWith("_trapdoor")) {
			kind = Kind.TRAPDOOR;
		} else if (path.endsWith("_door")) {
			kind = Kind.PANEL;
			dims = new int[] {0, 16, 0, 16, 3};
		} else if (path.equals("ladder")) {
			kind = Kind.PANEL;
			dims = new int[] {0, 16, 0, 16, 1};
		} else if (path.endsWith("wall_hanging_sign")) {
			kind = Kind.PANEL;
			dims = new int[] {1, 15, 2, 12, 2};
		} else if (path.endsWith("wall_sign")) {
			kind = Kind.PANEL;
			dims = new int[] {0, 16, 4, 12, 2};
		} else if (path.endsWith("wall_banner")) {
			kind = Kind.PANEL;
			dims = new int[] {1, 15, 0, 16, 2};
		} else if (path.endsWith("wall_torch")) {
			kind = Kind.PANEL;
			dims = new int[] {7, 9, 3, 13, 3};
		} else if (path.endsWith("_wall_head") || path.endsWith("_wall_skull")) {
			kind = Kind.PANEL;
			dims = new int[] {4, 12, 4, 12, 8};
		} else if (path.endsWith("_button") || path.equals("lever")) {
			String face = state.getOrDefault("face", "wall");
			boolean lever = path.equals("lever");
			if (face.equals("wall")) {
				kind = Kind.PANEL;
				dims = lever ? new int[] {5, 11, 4, 12, 3} : new int[] {5, 11, 6, 10, 2};
			} else {
				kind = Kind.BOX;
				dims = face.equals("ceiling") ? new int[] {5, 14, 6, 11, 16, 10} : new int[] {5, 0, 6, 11, 2, 10};
			}
		} else if (path.equals("tripwire_hook")) {
			kind = Kind.PANEL;
			dims = new int[] {6, 10, 2, 14, 3};
		} else if (path.equals("vine") || path.equals("glow_lichen") || path.equals("sculk_vein")
				|| path.equals("resin_clump")) {
			kind = Kind.VINE;
		} else if (path.equals("snow")) {
			kind = Kind.SNOW;
		} else if (path.endsWith("_carpet") || path.endsWith("rail") || path.endsWith("_pressure_plate")
				|| path.equals("redstone_wire") || path.equals("lily_pad") || path.equals("pink_petals")
				|| path.equals("wildflowers") || path.equals("leaf_litter") || path.equals("tripwire")
				|| path.equals("repeater") || path.equals("comparator")) {
			kind = Kind.BOX;
			dims = path.equals("repeater") || path.equals("comparator") ? new int[] {0, 0, 0, 16, 2, 16}
					: new int[] {0, 0, 0, 16, 1, 16};
		} else if (path.endsWith("torch")) {
			kind = Kind.BOX;
			dims = new int[] {7, 0, 7, 9, 10, 9};
		} else if (path.endsWith("lantern") && !path.equals("jack_o_lantern")) {
			kind = Kind.BOX;
			dims = new int[] {5, 0, 5, 11, 9, 11};
		} else if (path.endsWith("chain") || path.equals("end_rod") || path.equals("lightning_rod")) {
			kind = Kind.BOX;
			String a = axis != null ? axis : switch (state.getOrDefault("facing", "up")) {
				case "north", "south" -> "z";
				case "east", "west" -> "x";
				default -> "y";
			};
			dims = switch (a) {
				case "x" -> new int[] {0, 7, 7, 16, 9, 9};
				case "z" -> new int[] {7, 7, 0, 9, 9, 16};
				default -> new int[] {7, 0, 7, 9, 16, 9};
			};
		} else if (path.endsWith("candle")) {
			kind = Kind.BOX;
			dims = new int[] {7, 0, 7, 9, 6, 9};
		} else if (path.equals("flower_pot") || path.startsWith("potted_")) {
			kind = Kind.BOX;
			dims = new int[] {5, 0, 5, 11, 6, 11};
		} else if (path.endsWith("_bed")) {
			kind = Kind.BOX;
			dims = new int[] {0, 0, 0, 16, 9, 16};
		} else if (path.equals("chest") || path.equals("trapped_chest") || path.equals("ender_chest")) {
			kind = Kind.BOX;
			dims = new int[] {1, 0, 1, 15, 14, 15};
		} else if (path.endsWith("_head") || path.endsWith("_skull") || path.equals("heavy_core")) {
			kind = Kind.BOX;
			dims = new int[] {4, 0, 4, 12, 8, 12};
		} else if (path.equals("cake") || path.endsWith("_cake")) {
			kind = Kind.BOX;
			dims = new int[] {1, 0, 1, 15, 8, 15};
		} else if (path.endsWith("campfire")) {
			kind = Kind.BOX;
			dims = new int[] {0, 0, 0, 16, 7, 16};
		} else if (path.equals("enchanting_table")) {
			kind = Kind.BOX;
			dims = new int[] {0, 0, 0, 16, 12, 16};
		} else if (path.equals("daylight_detector")) {
			kind = Kind.BOX;
			dims = new int[] {0, 0, 0, 16, 6, 16};
		} else if (path.equals("stonecutter")) {
			kind = Kind.BOX;
			dims = new int[] {0, 0, 0, 16, 9, 16};
		} else if (path.equals("dirt_path") || path.equals("farmland")) {
			kind = Kind.BOX;
			dims = new int[] {0, 0, 0, 16, 15, 16};
		} else if (path.endsWith("anvil")) {
			kind = Kind.BOX;
			dims = new int[] {0, 0, 3, 16, 16, 13};
		} else if (path.equals("bell")) {
			kind = Kind.BOX;
			dims = new int[] {4, 2, 4, 12, 16, 12};
		} else if (path.equals("brewing_stand")) {
			kind = Kind.BOX;
			dims = new int[] {6, 0, 6, 10, 14, 10};
		} else if (path.equals("lectern")) {
			kind = Kind.BOX;
			dims = new int[] {0, 0, 0, 16, 14, 16};
		} else if (path.equals("conduit")) {
			kind = Kind.BOX;
			dims = new int[] {5, 5, 5, 11, 11, 11};
		} else if (path.endsWith("_hanging_sign")) {
			kind = Kind.BOX;
			dims = new int[] {1, 0, 7, 15, 10, 9};
		} else if (path.endsWith("_sign") || path.endsWith("_banner")) {
			kind = Kind.BOX;
			dims = new int[] {2, 0, 7, 14, 16, 9};
		} else if (path.equals("sea_pickle") || path.equals("turtle_egg") || path.equals("sniffer_egg")) {
			kind = Kind.BOX;
			dims = new int[] {4, 0, 4, 12, 7, 12};
		} else if (path.equals("cactus") || path.equals("decorated_pot")) {
			kind = Kind.BOX;
			dims = new int[] {1, 0, 1, 15, 16, 15};
		} else if (path.equals("sugar_cane") || path.equals("bamboo")) {
			kind = Kind.BOX;
			dims = new int[] {5, 0, 5, 11, 16, 11};
		} else if (isPlant(path)) {
			kind = Kind.BOX;
			dims = new int[] {4, 0, 4, 12, 13, 12};
		}
		return new BlockStyle(id, path, state, kind, dims, up, ns, ew, rgb.alpha(), rgb.guessed() && kind != Kind.INVISIBLE);
	}

	private static boolean isPlant(String p) {
		return PLANTS.contains(p) || p.endsWith("_tulip") || p.endsWith("_sapling") || p.endsWith("bush")
				|| p.endsWith("_fungus") || p.endsWith("_coral") || p.endsWith("_coral_fan") || p.endsWith("_coral_wall_fan")
				|| p.endsWith("_bud") || p.equals("crimson_roots") || p.equals("warped_roots") || p.equals("hanging_roots");
	}

	/** True if a fence/wall/pane arm reaches toward this neighbour. */
	private boolean connectsTo(BlockStyle n) {
		if (n == null) {
			return false;
		}
		return switch (kind) {
			case FENCE -> n.kind == Kind.FENCE || n.kind == Kind.GATE || n.kind == Kind.FULL;
			case WALL -> n.kind == Kind.WALL || n.kind == Kind.GATE || n.kind == Kind.PANE || n.kind == Kind.FULL;
			case PANE -> n.kind == Kind.PANE || n.kind == Kind.WALL || n.kind == Kind.FULL;
			default -> false;
		};
	}

	/**
	 * Writes this block's boxes (6 ints each: x0 y0 z0 x1 y1 z1 in 0..16) into {@code out} and returns
	 * how many were written (at most 9). Horizontal neighbours (null when empty) decide which arms
	 * fences, walls and panes get.
	 */
	int boxes(BlockStyle north, BlockStyle south, BlockStyle west, BlockStyle east, int[] out) {
		String facing = state.getOrDefault("facing", "north");
		switch (kind) {
			case INVISIBLE:
				return 0;
			case FULL:
				return put(out, 0, 0, 0, 0, 16, 16, 16);
			case BOX:
				return put(out, 0, dims[0], dims[1], dims[2], dims[3], dims[4], dims[5]);
			case SLAB: {
				String type = state.getOrDefault("type", "bottom");
				return type.equals("double") ? put(out, 0, 0, 0, 0, 16, 16, 16)
						: type.equals("top") ? put(out, 0, 0, 8, 0, 16, 16, 16) : put(out, 0, 0, 0, 0, 16, 8, 16);
			}
			case STAIRS: {
				boolean top = state.getOrDefault("half", "bottom").equals("top");
				int n = top ? put(out, 0, 0, 8, 0, 16, 16, 16) : put(out, 0, 0, 0, 0, 16, 8, 16);
				int y0 = top ? 0 : 8;
				return n + switch (facing) {
					case "south" -> put(out, 1, 0, y0, 8, 16, y0 + 8, 16);
					case "east" -> put(out, 1, 8, y0, 0, 16, y0 + 8, 16);
					case "west" -> put(out, 1, 0, y0, 0, 8, y0 + 8, 16);
					default -> put(out, 1, 0, y0, 0, 16, y0 + 8, 8);
				};
			}
			case TRAPDOOR:
				if (state.getOrDefault("open", "false").equals("true")) {
					return panel(out, facing, 0, 16, 0, 16, 3);
				}
				return state.getOrDefault("half", "bottom").equals("top") ? put(out, 0, 0, 13, 0, 16, 16, 16)
						: put(out, 0, 0, 0, 0, 16, 3, 16);
			case PANEL:
				return panel(out, facing, dims[0], dims[1], dims[2], dims[3], dims[4]);
			case GATE:
				return facing.equals("east") || facing.equals("west") ? put(out, 0, 7, 5, 0, 9, 15, 16)
						: put(out, 0, 0, 5, 7, 16, 15, 9);
			case SNOW: {
				int layers = parseInt(state.get("layers"), 1, 1, 8);
				return put(out, 0, 0, 0, 0, 16, layers * 2, 16);
			}
			case VINE: {
				int n = 0;
				if (is("north")) {
					n += put(out, n, 0, 0, 0, 16, 16, 1);
				}
				if (is("south")) {
					n += put(out, n, 0, 0, 15, 16, 16, 16);
				}
				if (is("west")) {
					n += put(out, n, 0, 0, 0, 1, 16, 16);
				}
				if (is("east")) {
					n += put(out, n, 15, 0, 0, 16, 16, 16);
				}
				if (is("up")) {
					n += put(out, n, 0, 15, 0, 16, 16, 16);
				}
				if (is("down")) {
					n += put(out, n, 0, 0, 0, 16, 1, 16);
				}
				return n > 0 ? n : put(out, 0, 0, 0, 0, 16, 16, 1);
			}
			case FENCE: {
				int n = put(out, 0, 6, 0, 6, 10, 16, 10);
				for (int rail = 0; rail < 2; rail++) {
					int y0 = rail == 0 ? 6 : 12;
					int y1 = y0 + 3;
					if (connectsTo(north)) {
						n += put(out, n, 7, y0, 0, 9, y1, 6);
					}
					if (connectsTo(south)) {
						n += put(out, n, 7, y0, 10, 9, y1, 16);
					}
					if (connectsTo(west)) {
						n += put(out, n, 0, y0, 7, 6, y1, 9);
					}
					if (connectsTo(east)) {
						n += put(out, n, 10, y0, 7, 16, y1, 9);
					}
				}
				return n;
			}
			case WALL: {
				boolean cn = connectsTo(north), cs = connectsTo(south), cw = connectsTo(west), ce = connectsTo(east);
				int n;
				if (cn && cs && !cw && !ce) {
					return put(out, 0, 5, 0, 0, 11, 14, 16);
				} else if (cw && ce && !cn && !cs) {
					return put(out, 0, 0, 0, 5, 16, 14, 11);
				}
				n = put(out, 0, 4, 0, 4, 12, 16, 12);
				if (cn) {
					n += put(out, n, 5, 0, 0, 11, 14, 4);
				}
				if (cs) {
					n += put(out, n, 5, 0, 12, 11, 14, 16);
				}
				if (cw) {
					n += put(out, n, 0, 0, 5, 4, 14, 11);
				}
				if (ce) {
					n += put(out, n, 12, 0, 5, 16, 14, 11);
				}
				return n;
			}
			case PANE: {
				boolean cn = connectsTo(north), cs = connectsTo(south), cw = connectsTo(west), ce = connectsTo(east);
				// Straight runs as one sheet, so translucent panes do not show seams inside a block.
				if (!cn && !cs && (cw || ce)) {
					return put(out, 0, cw ? 0 : 7, 0, 7, ce ? 16 : 9, 16, 9);
				}
				if (!cw && !ce && (cn || cs)) {
					return put(out, 0, 7, 0, cn ? 0 : 7, 9, 16, cs ? 16 : 9);
				}
				int n = put(out, 0, 7, 0, 7, 9, 16, 9);
				if (connectsTo(north)) {
					n += put(out, n, 7, 0, 0, 9, 16, 7);
				}
				if (connectsTo(south)) {
					n += put(out, n, 7, 0, 9, 9, 16, 16);
				}
				if (connectsTo(west)) {
					n += put(out, n, 0, 0, 7, 7, 16, 9);
				}
				if (connectsTo(east)) {
					n += put(out, n, 9, 0, 7, 16, 16, 9);
				}
				return n;
			}
			default:
				return put(out, 0, 0, 0, 0, 16, 16, 16);
		}
	}

	private boolean is(String key) {
		return "true".equals(state.get(key));
	}

	/** A thin panel against the side opposite {@code facing} (doors, ladders, wall signs face away from their wall). */
	private static int panel(int[] out, String facing, int from, int to, int y0, int y1, int t) {
		return switch (facing) {
			case "south" -> put(out, 0, from, y0, 0, to, y1, t);
			case "east" -> put(out, 0, 0, y0, from, t, y1, to);
			case "west" -> put(out, 0, 16 - t, y0, from, 16, y1, to);
			default -> put(out, 0, from, y0, 16 - t, to, y1, 16);
		};
	}

	private static int put(int[] out, int slot, int x0, int y0, int z0, int x1, int y1, int z1) {
		int o = slot * 6;
		out[o] = x0;
		out[o + 1] = y0;
		out[o + 2] = z0;
		out[o + 3] = x1;
		out[o + 4] = y1;
		out[o + 5] = z1;
		return 1;
	}

	private static int parseInt(String s, int def, int min, int max) {
		if (s == null) {
			return def;
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(s)));
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
