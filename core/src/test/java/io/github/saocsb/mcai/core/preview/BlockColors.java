package io.github.saocsb.mcai.core.preview;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Approximate average texture colours of Minecraft Java blocks, for previews only.
 *
 * <p>Lookup works on the bare path ({@code oak_stairs}, no namespace, state or NBT): exact table
 * entry, then the base material of shaped variants ({@code stone_brick_wall -> stone_bricks},
 * {@code oak_slab -> oak_planks}), then substring heuristics, then a deterministic pastel.
 */
final class BlockColors {
	/** A block colour: side and top RGB, opacity 0..255, and whether it was guessed from the name. */
	record Rgb(int side, int top, int alpha, boolean guessed) {
		Rgb guess() {
			return new Rgb(side, top, alpha, true);
		}

		Rgb darker(double f) {
			return new Rgb(scale(side, f), scale(top, f), alpha, guessed);
		}

		private static int scale(int rgb, double f) {
			int r = (int) Math.round(((rgb >> 16) & 0xff) * f);
			int g = (int) Math.round(((rgb >> 8) & 0xff) * f);
			int b = (int) Math.round((rgb & 0xff) * f);
			return (r << 16) | (g << 8) | b;
		}
	}

	static final List<String> DYES = List.of("light_blue", "light_gray", "white", "orange", "magenta", "yellow",
			"lime", "pink", "gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

	// Wood species, longer names first so "dark_oak" wins over "oak".
	static final List<String> WOODS = List.of("dark_oak", "pale_oak", "mangrove", "cherry", "bamboo", "crimson",
			"warped", "spruce", "birch", "jungle", "acacia", "oak");

	// Shape suffixes whose colour comes from the base material, longest first.
	private static final List<String> SUFFIXES = List.of("_hanging_sign", "_pressure_plate", "_shulker_box",
			"_fence_gate", "_trapdoor", "_stairs", "_button", "_banner", "_carpet", "_candle", "_fence", "_slab",
			"_wall", "_door", "_pane", "_sign", "_bed", "_bars", "_grate", "_bulb", "_lantern", "_chain");

	private static final Map<String, Rgb> TABLE = new HashMap<>();

	// name side-colour [top-colour] [a=opacity%]
	private static final String DATA = """
			stone 7e7e7e
			cobblestone 7f7f7f
			mossy_cobblestone 6e775e
			smooth_stone 9e9e9e
			stone_bricks 7a797a
			mossy_stone_bricks 737969
			cracked_stone_bricks 767576
			chiseled_stone_bricks 777677
			granite 956755
			polished_granite 9a6a59
			diorite bcbcbc
			polished_diorite c0c1c2
			andesite 888888
			polished_andesite 848685
			deepslate 505052
			cobbled_deepslate 4d4d50
			polished_deepslate 484849
			deepslate_bricks 464647
			cracked_deepslate_bricks 404041
			deepslate_tiles 363637
			cracked_deepslate_tiles 343435
			chiseled_deepslate 363636
			reinforced_deepslate 505350
			tuff 6c6d66
			polished_tuff 626864
			tuff_bricks 62665f
			chiseled_tuff 595e57
			chiseled_tuff_bricks 6f736a
			calcite dfe0dc
			dripstone_block 866b5c
			pointed_dripstone 866b5c
			bricks 966153
			mud_bricks 89674f
			packed_mud 8e6a4f
			mud 3c393c
			clay a0a6b3
			resin_block d9681a
			resin_bricks c5571b
			sandstone d8cb9b e0d6aa
			smooth_sandstone e0d6aa
			cut_sandstone d9ce9f
			chiseled_sandstone d8ca9b
			red_sandstone ba631d b5621f
			smooth_red_sandstone b5621f
			cut_red_sandstone bd651f
			chiseled_red_sandstone b7601b
			sand dbcfa3
			suspicious_sand d6c99c
			red_sand be6621
			gravel 837f7e
			suspicious_gravel 817c7a
			dirt 866043
			coarse_dirt 77553b
			rooted_dirt 90674c
			podzol 7a5836 5b3f18
			mycelium 816e64 6f6369
			grass_block 7d6a43 6aa53f
			dirt_path 8c6c3c 947a41
			farmland 866043 6f4a2b
			snow_block f9fefe
			snow f9fefe
			powder_snow f8fdfd
			ice 91b7fd a=55
			frosted_ice 8cb4f8 a=55
			packed_ice 8db4fa
			blue_ice 74a7fd
			water 3f76e4 a=55
			bubble_column 3f76e4 a=55
			lava cf5c14
			obsidian 0f0a18
			crying_obsidian 200a3c
			bedrock 555555
			netherrack 612626
			nether_bricks 2c151a
			cracked_nether_bricks 28141a
			chiseled_nether_bricks 2f171c
			red_nether_bricks 450709
			nether_wart_block 720202
			warped_wart_block 167779
			nether_gold_ore 73362a
			nether_quartz_ore 75413e
			soul_sand 513e32
			soul_soil 4b392e
			basalt 505156 595858
			polished_basalt 636264 7a797b
			smooth_basalt 48484e
			blackstone 2a2429
			polished_blackstone 353038
			polished_blackstone_bricks 302a31
			cracked_polished_blackstone_bricks 2c2529
			chiseled_polished_blackstone 352f37
			gilded_blackstone 372a26
			magma_block 8e3f1f
			crimson_nylium 6b2a2a 821f1f
			warped_nylium 5a3f3f 2b7265
			end_stone dbde9e
			end_stone_bricks dae0a2
			purpur_block a97da9
			purpur_pillar ab81ab
			prismarine 639c97
			prismarine_bricks 63ab9e
			dark_prismarine 335b4b
			sea_lantern acc7be
			glowstone ab8354
			shroomlight f09246
			quartz_block ebe5de
			smooth_quartz ebe5de
			quartz_bricks eae5dd
			quartz_pillar ebe6e0
			chiseled_quartz_block e7e2da
			terracotta 985e43
			coal_ore 696969
			iron_ore 88817a
			copper_ore 7c7d78
			gold_ore 91856a
			redstone_ore 8c6d6d
			emerald_ore 6c8873
			lapis_ore 636e84
			diamond_ore 798d8c
			coal_block 100f0f
			iron_block dcdcdc
			gold_block f6d03d
			diamond_block 62ede4
			emerald_block 2acb57
			lapis_block 1f438c
			redstone_block af1805
			netherite_block 423d3f
			ancient_debris 5f4038
			copper_block c06b4f
			exposed_copper a17d67
			weathered_copper 6c996e
			oxidized_copper 52a284
			cut_copper bf6a50
			exposed_cut_copper 9b7a65
			weathered_cut_copper 6d916b
			oxidized_cut_copper 4f9986
			raw_iron_block a6876b
			raw_gold_block dda92e
			raw_copper_block 9a694f
			amethyst_block 8561bf
			budding_amethyst 8460ba
			amethyst_cluster a07fd8
			lantern e6aa50
			soul_lantern 5fa0aa
			torch e8c35a
			soul_torch 6ec8d2
			redstone_torch c83c28
			copper_torch 8ed29a
			redstone_lamp 8c5a32
			jack_o_lantern d69834 c57617
			pumpkin c67618 c57617
			carved_pumpkin c67618 c57617
			ochre_froglight faf5ce
			verdant_froglight e5f4e4
			pearlescent_froglight f5f0ef
			end_rod e6ded2
			lightning_rod c06b4f
			beacon 75dcd7 a=85
			conduit 9f8b6f
			campfire 8c6a40 d0782a
			soul_campfire 6a5a48 50b4c0
			fire e0781e a=70
			soul_fire 32c8dc a=70
			oak_planks a2824e
			spruce_planks 735532
			birch_planks c0af79
			jungle_planks a07351
			acacia_planks a85a32
			dark_oak_planks 432b14
			mangrove_planks 763631
			cherry_planks e2b2ac
			pale_oak_planks e4dad8
			bamboo_planks c2ad51
			bamboo_mosaic beaa4e
			crimson_planks 653046
			warped_planks 2b6963
			oak_log 6d5533 97794a
			spruce_log 3a2510 6c5030
			birch_log d8d7d2 c1b387
			jungle_log 554319 976d47
			acacia_log 676157 965837
			dark_oak_log 3c2e1a 412c16
			mangrove_log 544229 66302a
			cherry_log 36212c b98d89
			pale_oak_log 5a514f e0d4cf
			crimson_stem 5c191d 6e3048
			warped_stem 3a3a4d 355d5a
			bamboo_block 7f903a 8a9a44
			stripped_oak_log b19056 a8875a
			stripped_spruce_log 735934 6a5030
			stripped_birch_log c4b076 bea67a
			stripped_jungle_log ab8454 a47c51
			stripped_acacia_log ae5c3b a55b37
			stripped_dark_oak_log 483824 4a3523
			stripped_mangrove_log 77362f 6e3531
			stripped_cherry_log d7918f d39e9b
			stripped_pale_oak_log f5eeec ede2df
			stripped_crimson_stem 893a5a 7e3a56
			stripped_warped_stem 3a9796 34827f
			stripped_bamboo_block c0ab4f b9a14a
			oak_leaves 4b8a2c a=88
			spruce_leaves 3d613d a=88
			birch_leaves 6a9a3c a=88
			jungle_leaves 3a8f1c a=88
			acacia_leaves 4f8e20 a=88
			dark_oak_leaves 3b7a1e a=88
			mangrove_leaves 5e8f30 a=88
			cherry_leaves e6adc2 a=88
			azalea_leaves 5a752c a=88
			flowering_azalea_leaves 6f7b3f a=88
			pale_oak_leaves a0a596 a=88
			short_grass 5f9a3a
			tall_grass 5f9a3a
			grass 5f9a3a
			fern 568f36
			large_fern 568f36
			bush 5f9a3a
			dead_bush 6b4f29
			poppy c8302a
			dandelion f2d22a
			blue_orchid 2aa2d8
			allium b464e0
			azure_bluet dfe6ea
			red_tulip d0342a
			orange_tulip e07a24
			white_tulip e6ece6
			pink_tulip e6a0c8
			oxeye_daisy e8e8dc
			cornflower 4664d6
			lily_of_the_valley ebebeb
			wither_rose 2a2a1e
			sunflower f2c21e
			lilac c08ad0
			rose_bush c8302a
			peony e6b4dc
			torchflower e88a2a
			pitcher_plant 6aa0c8
			pink_petals f0a8c8
			wildflowers f0d850
			leaf_litter 8a6a3a
			sugar_cane 82b45a
			bamboo 5d8f20
			cactus 587d26
			vine 3f7a1e
			glow_lichen 708a7a
			lily_pad 2a7a2a
			moss_block 596d2d
			moss_carpet 596d2d
			pale_moss_block 6b7069
			pale_moss_carpet 6b7069
			azalea 5f7a2e
			flowering_azalea 7a7a48
			kelp 3a7a2a
			seagrass 3a7a2a
			tall_seagrass 3a7a2a
			sweet_berry_bush 3f6a2e
			wheat c8b04a
			carrots 6aa02a
			potatoes 6aa02a
			beetroots 7a9a3a
			brown_mushroom 9a7556
			red_mushroom d02f2f
			red_mushroom_block c82e2d
			brown_mushroom_block 956f51
			mushroom_stem cbc4b9
			hay_block a68b0c a68826
			melon 6f911e
			sponge c3c04a
			wet_sponge abb546
			dried_kelp_block 323b29
			tube_coral_block 3157cf
			brain_coral_block cf5b9f
			bubble_coral_block a51aa2
			fire_coral_block a4232f
			horn_coral_block d8c842
			dead_tube_coral_block 827b77
			dead_brain_coral_block 7c7571
			dead_bubble_coral_block 847d79
			dead_fire_coral_block 857e7a
			dead_horn_coral_block 857e7a
			crafting_table 7a4f2f a3825a
			furnace 6e6d6d
			blast_furnace 6c6b6a
			smoker 6b5a47
			chest a2712b
			trapped_chest a2712b
			ender_chest 283737
			barrel 86643a
			bookshelf 755e3b a2824e
			chiseled_bookshelf 7a603d a2824e
			lectern a2824e
			cartography_table 685036
			fletching_table c3b07d
			smithing_table 3b3a46
			loom 9b8a6a
			stonecutter 7b7672
			grindstone 8c8c8c
			anvil 444444
			chipped_anvil 444444
			damaged_anvil 444444
			cauldron 4a4a4a
			water_cauldron 4a4a4a
			composter 774c27
			hopper 3f3f3f
			jukebox 5a3b28
			note_block 5a3b28
			tnt 8e3e35 b25a4f
			target e2aa9d
			beehive b48f4f
			bee_nest c0963f
			honey_block fbb934 a=75
			honeycomb_block e5941e
			slime_block 6fc05b a=75
			scaffolding aa8349
			ladder 7c6138
			iron_bars 888b87
			chain 3c4150
			iron_chain 3c4150
			iron_door c2c1c1
			iron_trapdoor c8c8c8
			light_weighted_pressure_plate f6d03d
			heavy_weighted_pressure_plate dcdcdc
			bell f0c838
			flower_pot 7c4435
			decorated_pot 8c5240
			cake f0e6dc
			candle e6dcc4
			shulker_box 8b5f8b
			enchanting_table 302034 7a2b30
			brewing_stand 7a6a50
			end_portal_frame 5b7861 778d72
			spawner 24343e
			trial_spawner 3a4048
			vault 3a4048
			crafter 6e6d6d
			observer 626262
			piston 6e6e6e a68b5a
			sticky_piston 6e6e6e 74a35a
			dispenser 6e6d6d
			dropper 6e6d6d
			daylight_detector 826e54
			redstone_wire af1805
			repeater a0a0a0
			comparator a0a0a0
			lever 7a6a50
			tripwire_hook 7a6a50
			rail 7d7363
			powered_rail 9a7a3a
			detector_rail 7e6a5a
			activator_rail 7a5a4c
			glass c8dce6 a=30
			tinted_glass 2c262e a=75
			cobweb e6e6e6 a=45
			nether_portal 6a1eca a=60
			end_portal 0a0f14
			end_gateway 0a0f14
			dragon_egg 0c0910
			skeleton_skull d0d0d0
			wither_skeleton_skull 2a2a2a
			zombie_head 4a7a3a
			player_head 6a4a3a
			creeper_head 5aa04a
			dragon_head 1e1e1e
			piglin_head d7a28e
			lodestone 777879
			respawn_anchor 2a1a4a
			sculk 0c1e24
			sculk_catalyst 1e3a3a
			sculk_sensor 0c4a5a
			sculk_shrieker c6cdb0
			sea_pickle 6a7a2a
			turtle_egg e0dcc6
			sniffer_egg 8a4a3a
			mangrove_roots 4a3b26
			muddy_mangrove_roots 463b30
			hanging_roots 9a6e52
			creaking_heart 5a514f
			heavy_core 3f4247
			copper_grate c06b4f
			""";

	// Dye colour families, in DYES order.
	private static final int[] WOOL = {0x3aafd9, 0x8e8e86, 0xe9ecec, 0xf07613, 0xbd44b3, 0xf8c527, 0x70b919, 0xed8dac,
			0x3e4447, 0x158991, 0x792aac, 0x35399d, 0x724728, 0x546d1b, 0xa02722, 0x141519};
	private static final int[] CONCRETE = {0x2389c6, 0x7d7d73, 0xcfd5d6, 0xe06100, 0xa9309f, 0xf1af15, 0x5ea818,
			0xd5658e, 0x36393d, 0x157788, 0x641f9c, 0x2c2e8f, 0x603b1f, 0x495b24, 0x8e2020, 0x080a0f};
	private static final int[] CONCRETE_POWDER = {0x4ab4d5, 0x9a9a94, 0xe1e3e3, 0xe3831f, 0xc053b8, 0xe8c736,
			0x7dbd29, 0xe499b5, 0x4c5154, 0x24939d, 0x8337b1, 0x4649a6, 0x7d5435, 0x61772c, 0xa83632, 0x191a1f};
	private static final int[] TERRACOTTA = {0x716c89, 0x876a61, 0xd1b2a1, 0xa15325, 0x95586c, 0xba8523, 0x677534,
			0xa14e4e, 0x392a23, 0x575b5b, 0x764656, 0x4a3b5b, 0x4d3323, 0x4c532a, 0x8f3d2e, 0x251610};
	private static final int[] GLAZED = {0x5ea4d0, 0x90a6a7, 0xbcd4ca, 0x9a935b, 0xd064bf, 0xeac058, 0xa2c537,
			0xeb9ab5, 0x535a5d, 0x34767d, 0x6d3098, 0x2f408b, 0x776a55, 0x758e43, 0xb53b35, 0x431e20};
	private static final int[] STAINED_GLASS = {0x6699d8, 0x999999, 0xffffff, 0xd87f33, 0xb24cd8, 0xe5e533,
			0x7fcc19, 0xf27fa5, 0x4c4c4c, 0x4c7f99, 0x7f3fb2, 0x334cb2, 0x664c33, 0x667f33, 0x993333, 0x191919};

	static {
		for (String line : DATA.split("\n")) {
			String[] p = line.strip().split("\\s+");
			if (p.length < 2) {
				continue;
			}
			int side = Integer.parseInt(p[1], 16);
			int top = side;
			int alpha = 255;
			for (int i = 2; i < p.length; i++) {
				if (p[i].startsWith("a=")) {
					alpha = Math.round(Integer.parseInt(p[i].substring(2)) * 2.55f);
				} else {
					top = Integer.parseInt(p[i], 16);
				}
			}
			TABLE.put(p[0], new Rgb(side, top, alpha, false));
		}
		for (int i = 0; i < DYES.size(); i++) {
			String d = DYES.get(i);
			put(d, WOOL[i], 255);
			put(d + "_wool", WOOL[i], 255);
			put(d + "_concrete", CONCRETE[i], 255);
			put(d + "_concrete_powder", CONCRETE_POWDER[i], 255);
			put(d + "_terracotta", TERRACOTTA[i], 255);
			put(d + "_glazed_terracotta", GLAZED[i], 255);
			put(d + "_stained_glass", STAINED_GLASS[i], 120);
		}
	}

	private BlockColors() {
	}

	private static void put(String name, int rgb, int alpha) {
		TABLE.put(name, new Rgb(rgb, rgb, alpha, false));
	}

	/** Number of explicit table entries (generated dye variants included). */
	static int tableSize() {
		return TABLE.size();
	}

	/** True if the bare path has an explicit table entry. */
	static boolean known(String path) {
		return TABLE.containsKey(path);
	}

	/** Colour for a bare block path (no namespace, state or NBT). Never null. */
	static Rgb lookup(String path) {
		String p = path.toLowerCase(Locale.ROOT);
		Rgb rgb = TABLE.get(p);
		if (rgb != null) {
			return rgb;
		}
		p = p.replace("_wall_torch", "_torch").replace("wall_torch", "torch").replace("_wall_sign", "_sign")
				.replace("_wall_hanging_sign", "_hanging_sign").replace("_wall_banner", "_banner")
				.replace("_wall_head", "_head").replace("_wall_skull", "_skull").replace("_wall_fan", "_fan");
		for (String prefix : List.of("waxed_", "infested_", "potted_")) {
			if (p.startsWith(prefix)) {
				p = prefix.equals("potted_") ? "flower_pot" : p.substring(prefix.length());
			}
		}
		if (p.endsWith("_wood")) {
			p = p.substring(0, p.length() - 5) + "_log";
		} else if (p.endsWith("_hyphae")) {
			p = p.substring(0, p.length() - 7) + "_stem";
		}
		rgb = TABLE.get(p);
		if (rgb != null) {
			return rgb;
		}
		for (String suffix : SUFFIXES) {
			if (p.endsWith(suffix) && p.length() > suffix.length()) {
				String base = p.substring(0, p.length() - suffix.length());
				for (String candidate : new String[] {base + "_planks", base + "s", base + "_block", base}) {
					rgb = TABLE.get(candidate);
					if (rgb != null) {
						// Doors and trapdoors a shade darker so they stand out in walls of the same wood.
						return suffix.equals("_door") || suffix.equals("_trapdoor") ? rgb.darker(0.78) : rgb;
					}
				}
				break;
			}
		}
		rgb = TABLE.get(p + "s");
		if (rgb != null) {
			// A near miss such as stone_brick or oak_plank: likely meant the plural id.
			return rgb.guess();
		}
		rgb = heuristic(p);
		return rgb != null ? rgb.guess() : pastel(path);
	}

	private static Rgb heuristic(String p) {
		if (p.endsWith("_ore")) {
			return TABLE.get(p.startsWith("deepslate") ? "deepslate" : p.startsWith("nether") ? "netherrack" : "stone");
		}
		for (String dye : DYES) {
			if (p.startsWith(dye + "_")) {
				String family = p.contains("concrete_powder") ? "_concrete_powder"
						: p.contains("concrete") ? "_concrete"
						: p.contains("glazed") ? "_glazed_terracotta"
						: p.contains("terracotta") ? "_terracotta"
						: p.contains("glass") ? "_stained_glass" : "_wool";
				return TABLE.get(dye + family);
			}
		}
		if (p.contains("copper")) {
			return TABLE.get(p.contains("oxidized") ? "oxidized_copper" : p.contains("weathered") ? "weathered_copper"
					: p.contains("exposed") ? "exposed_copper" : "copper_block");
		}
		for (String wood : WOODS) {
			if (p.contains(wood)) {
				boolean nether = wood.equals("crimson") || wood.equals("warped");
				if (p.contains("leaves")) {
					return TABLE.getOrDefault(wood + "_leaves", TABLE.get("oak_leaves"));
				}
				if (p.contains("sapling") || p.contains("propagule")) {
					return TABLE.get("short_grass");
				}
				if (p.contains("log") || p.contains("wood") || p.contains("stem") || p.contains("hyphae")) {
					String log = wood + (nether ? "_stem" : wood.equals("bamboo") ? "_block" : "_log");
					return TABLE.get(p.contains("stripped") ? "stripped_" + log : log);
				}
				if (!wood.equals("bamboo") || p.contains("planks") || p.contains("door") || p.contains("sign")
						|| p.contains("fence") || p.contains("raft")) {
					return TABLE.get(wood + "_planks");
				}
			}
		}
		String[][] keywords = {
				{"gold", "gold_block"}, {"iron", "iron_block"}, {"diamond", "diamond_block"},
				{"emerald", "emerald_block"}, {"lapis", "lapis_block"}, {"redstone", "redstone_block"},
				{"netherite", "netherite_block"}, {"amethyst", "amethyst_block"}, {"quartz", "quartz_block"},
				{"red_sandstone", "red_sandstone"}, {"sandstone", "sandstone"}, {"prismarine", "prismarine"},
				{"purpur", "purpur_block"}, {"blackstone", "blackstone"}, {"deepslate", "deepslate"},
				{"tuff", "tuff"}, {"red_nether", "red_nether_bricks"}, {"nether_brick", "nether_bricks"},
				{"end_stone", "end_stone_bricks"}, {"mud_brick", "mud_bricks"}, {"resin", "resin_bricks"},
				{"brick", "bricks"}, {"basalt", "basalt"}, {"calcite", "calcite"}, {"granite", "granite"},
				{"diorite", "diorite"}, {"andesite", "andesite"}, {"cobble", "cobblestone"}, {"_ore", "stone"},
				{"stone", "stone"}, {"glass", "glass"}, {"ice", "ice"}, {"snow", "snow_block"},
				{"leaves", "oak_leaves"}, {"planks", "oak_planks"}, {"log", "oak_log"}, {"wood", "oak_planks"},
				{"sand", "sand"}, {"dirt", "dirt"}, {"mud", "mud"}, {"grass", "short_grass"},
				{"tulip", "red_tulip"}, {"flower", "poppy"}, {"mushroom", "red_mushroom"},
				{"coral", "brain_coral_block"}, {"wool", "white_wool"}, {"carpet", "white_wool"},
				{"terracotta", "terracotta"}, {"concrete", "light_gray_concrete"}, {"lantern", "lantern"},
				{"torch", "torch"}, {"lamp", "redstone_lamp"}, {"rail", "rail"}, {"water", "water"},
				{"lava", "lava"}, {"obsidian", "obsidian"}, {"nether", "netherrack"}, {"moss", "moss_block"},
				{"vine", "vine"}, {"bamboo", "bamboo"}, {"honey", "honey_block"}, {"slime", "slime_block"},
				{"bookshelf", "bookshelf"}, {"sapling", "short_grass"}, {"plant", "short_grass"},
				{"bush", "short_grass"}, {"roots", "mangrove_roots"}, {"fern", "fern"}, {"skull", "skeleton_skull"},
				{"head", "player_head"}, {"shulker", "shulker_box"}, {"candle", "candle"},
		};
		for (String[] kw : keywords) {
			if (p.contains(kw[0])) {
				return TABLE.get(kw[1]);
			}
		}
		return null;
	}

	/** Deterministic soft colour for unknown ids, so distinct unknown blocks stay distinguishable. */
	static Rgb pastel(String id) {
		int h = id.hashCode() * 0x9E3779B1;
		float hue = ((h >>> 8) & 0xffff) / 65536f;
		int rgb = java.awt.Color.HSBtoRGB(hue, 0.45f, 0.88f) & 0xffffff;
		return new Rgb(rgb, rgb, 255, true);
	}
}
