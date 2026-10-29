package io.github.saocsb.mcai.core.preview;

import io.github.saocsb.mcai.core.blueprint.BlockSink;

/**
 * A fixed test scene for checking the renderer by eye: a small house with a sloped stair roof, glass
 * windows and a door, a gold sphere, a crenellated stone tower, a tree, a fence, a pond and lights.
 * Written against {@link BlockSink} with its own loops so it works without the geometry track.
 */
public final class DemoScene {
	private DemoScene() {
	}

	public static VoxelMap build() {
		VoxelMap m = new VoxelMap();
		build(m);
		return m;
	}

	public static void build(BlockSink s) {
		// Ground (one layer below the player's feet) with a pond.
		fill(s, -14, -1, -6, 21, -1, 12, "minecraft:grass_block");
		fill(s, 13, -1, 7, 18, -1, 10, "minecraft:water");
		fill(s, 4, -1, 7, 4, -1, 9, "minecraft:dirt_path");

		// House: x 0..8, z 0..6, front wall at z = 6.
		fill(s, 0, 0, 0, 8, 0, 6, "minecraft:cobblestone");
		for (int y = 1; y <= 4; y++) {
			for (int x = 0; x <= 8; x++) {
				for (int z = 0; z <= 6; z++) {
					boolean edgeX = x == 0 || x == 8, edgeZ = z == 0 || z == 6;
					if (edgeX && edgeZ) {
						s.set(x, y, z, "minecraft:oak_log[axis=y]");
					} else if (edgeX || edgeZ) {
						s.set(x, y, z, "minecraft:oak_planks");
					}
				}
			}
		}
		// Door and windows in the front; a glass block window on the east side.
		s.set(4, 1, 6, "minecraft:oak_door[facing=north,half=lower]");
		s.set(4, 2, 6, "minecraft:oak_door[facing=north,half=upper]");
		fill(s, 1, 2, 6, 2, 3, 6, "minecraft:glass_pane");
		fill(s, 6, 2, 6, 7, 3, 6, "minecraft:glass_pane");
		fill(s, 8, 2, 2, 8, 3, 4, "minecraft:glass");
		s.set(3, 3, 7, "minecraft:wall_torch[facing=south]");
		// Gables and roof: stairs climb toward a ridge along x at z = 3.
		for (int k = 0; k <= 3; k++) {
			for (int z = k; z <= 6 - k; z++) {
				s.set(0, 5 + k, z, "minecraft:oak_planks");
				s.set(8, 5 + k, z, "minecraft:oak_planks");
			}
			for (int x = -1; x <= 9; x++) {
				s.set(x, 5 + k, 7 - k, "minecraft:spruce_stairs[facing=north,half=bottom]");
				s.set(x, 5 + k, -1 + k, "minecraft:spruce_stairs[facing=south,half=bottom]");
			}
		}
		fill(s, -1, 8, 3, 9, 8, 3, "minecraft:spruce_planks");
		fill(s, -1, 9, 3, 9, 9, 3, "minecraft:spruce_slab[type=bottom]");
		fill(s, 6, 5, 1, 6, 10, 1, "minecraft:bricks");

		// Front yard fence with a lantern; tree; sphere on a pedestal.
		for (int x = -2; x <= 10; x++) {
			if (x != 4) {
				s.set(x, 0, 10, "minecraft:oak_fence");
			}
		}
		s.set(-2, 1, 10, "minecraft:lantern");
		fill(s, 11, 0, 3, 11, 4, 3, "minecraft:birch_log");
		sphere(s, 11, 6, 3, 2.3, "minecraft:birch_leaves");
		fill(s, 15, 0, 1, 17, 0, 3, "minecraft:polished_andesite");
		sphere(s, 16, 4, 2, 3, "minecraft:gold_block");

		// Tower: hollow stone-brick cylinder with windows, a floor and crenellations.
		int tx = -8, tz = 2, r = 3;
		for (int y = 0; y <= 11; y++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					double d = Math.sqrt(dx * dx + dz * dz);
					if (d <= r + 0.5 && d > r - 0.6) {
						s.set(tx + dx, y, tz + dz, y % 4 == 0 ? "minecraft:mossy_stone_bricks" : "minecraft:stone_bricks");
					}
				}
			}
		}
		s.set(tx, 5, tz + r, "minecraft:glass_pane");
		s.set(tx, 6, tz + r, "minecraft:glass_pane");
		s.set(tx + r, 8, tz, "minecraft:glass_pane");
		for (int dx = -r - 1; dx <= r + 1; dx++) {
			for (int dz = -r - 1; dz <= r + 1; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d <= r + 1.5) {
					s.set(tx + dx, 12, tz + dz, "minecraft:stone_brick_slab[type=top]");
				}
				if (d <= r + 1.5 && d > r + 0.4 && ((dx + dz) & 1) == 0) {
					s.set(tx + dx, 13, tz + dz, "minecraft:stone_brick_wall");
				}
			}
		}
		s.set(tx, 13, tz, "minecraft:oak_fence");
		s.set(tx, 14, tz, "minecraft:oak_fence");
		s.set(tx, 15, tz, "minecraft:oak_fence");
		s.set(tx + 1, 15, tz, "minecraft:red_wool");
		s.set(tx + 2, 15, tz, "minecraft:red_wool");
		s.set(tx + 1, 14, tz, "minecraft:red_wool");
		// Flowers.
		s.set(-1, 0, 8, "minecraft:poppy");
		s.set(0, 0, 8, "minecraft:dandelion");
		s.set(7, 0, 8, "minecraft:blue_orchid");
		s.set(8, 0, 8, "minecraft:oxeye_daisy");
	}

	private static void fill(BlockSink s, int x1, int y1, int z1, int x2, int y2, int z2, String id) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					s.set(x, y, z, id);
				}
			}
		}
	}

	private static void sphere(BlockSink s, int cx, int cy, int cz, double r, String id) {
		int ri = (int) Math.ceil(r);
		double lim = (r + 0.5) * (r + 0.5);
		for (int dx = -ri; dx <= ri; dx++) {
			for (int dy = -ri; dy <= ri; dy++) {
				for (int dz = -ri; dz <= ri; dz++) {
					if (dx * dx + dy * dy + dz * dz <= lim) {
						s.set(cx + dx, cy + dy, cz + dz, id);
					}
				}
			}
		}
	}
}
