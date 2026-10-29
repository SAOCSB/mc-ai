package io.github.saocsb.mcai.core.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static io.github.saocsb.mcai.core.script.ScriptTestSupport.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Realistic scripts and throughput. Timings are printed so they show up in the test output. */
class ScriptPerformanceTest {
	/** An Eiffel-Tower-like lattice tower, written the way an LLM typically writes build scripts. */
	static final String EIFFEL_TOWER = """
			// Eiffel-Tower-like lattice tower, written the way an LLM typically writes build scripts.
			setName('Eiffel Tower');
			placement({ mode: 'front', distance: 6, ground: 'surface' });

			const H = 120;                 // total height
			const BASE = 26;               // half width of the footprint at the ground
			const IRON = 'iron_block';
			const BARS = 'iron_bars';
			const DARK = 'gray_concrete';
			const LIGHT = 'light_gray_concrete';

			// Half width of the tower at height y: a concave curve, wide at the base, narrow at the top.
			function halfWidth(y) {
				const t = y / H;
				return Math.max(1, Math.round(BASE * Math.pow(1 - t, 1.8) + 1.5));
			}

			// Four corner legs, each a thick column that follows the curve.
			const legs = [[-1, -1], [1, -1], [-1, 1], [1, 1]];
			for (let y = 0; y < H - 10; y++) {
				const w = halfWidth(y);
				const thickness = y < 40 ? 3 : (y < 80 ? 2 : 1);
				for (const [sx, sz] of legs) {
					for (let dx = 0; dx < thickness; dx++) {
						for (let dz = 0; dz < thickness; dz++) {
							block(sx * (w - dx), y, sz * (w - dz), IRON);
						}
					}
				}
			}

			// Diagonal lattice bracing on all four faces, every 6 blocks.
			function brace(y0, y1) {
				const w0 = halfWidth(y0), w1 = halfWidth(y1);
				for (const s of [-1, 1]) {
					// faces along x (z = +-w)
					line(-w0, y0, s * w0, w1, y1, s * w1, DARK);
					line(w0, y0, s * w0, -w1, y1, s * w1, DARK);
					// faces along z (x = +-w)
					line(s * w0, y0, -w0, s * w1, y1, w1, DARK);
					line(s * w0, y0, w0, s * w1, y1, -w1, DARK);
				}
			}
			for (let y = 0; y + 6 < H - 12; y += 6) {
				brace(y, y + 6);
			}

			// Arches between the legs at the bottom (parabolic).
			for (const s of [-1, 1]) {
				const span = halfWidth(0) - 3;
				for (let x = -span; x <= span; x++) {
					const t = x / span;
					const archY = Math.round(14 * (1 - t * t));
					block(x, archY, s * (halfWidth(archY) - 1), LIGHT);
					block(s * (halfWidth(archY) - 1), archY, x, LIGHT);
				}
			}

			// Platforms (decks) with railings.
			const decks = [
				{ y: 18, overhang: 1 },
				{ y: 46, overhang: 1 },
				{ y: 92, overhang: 0 },
			];
			for (const deck of decks) {
				const w = halfWidth(deck.y) + deck.overhang;
				fill(-w, deck.y, -w, w, deck.y, w, 'smooth_stone');
				walls(-w, deck.y + 1, -w, w, deck.y + 1, w, BARS);
				// a ring of lanterns on the deck corners
				for (const [sx, sz] of legs) {
					block(sx * w, deck.y + 2, sz * w, 'lantern');
				}
			}

			// Hollow core with stairs-like ladder inside the upper part.
			for (let y = 46; y < H - 10; y++) {
				const w = Math.max(1, Math.floor(halfWidth(y) / 3));
				for (let i = -w; i <= w; i++) {
					block(i, y, -w, BARS);
					block(i, y, w, BARS);
					block(-w, y, i, BARS);
					block(w, y, i, BARS);
				}
				if (y % 4 === 0) {
					block(0, y, 0, 'ladder[facing=north]');
				}
			}

			// Spire on top.
			const topY = H - 10;
			cylinder(0, topY, 0, 1, 6, IRON);
			line(0, topY + 6, 0, 0, H, 0, 'lightning_rod');
			sphere(0, topY + 3, 0, 2, 'glass', true);
			block(0, H, 0, 'sea_lantern');

			// Ornamental rings using trigonometry around the first deck.
			const ringR = halfWidth(18) + 3;
			for (let a = 0; a < 360; a += 4) {
				const rad = a * Math.PI / 180;
				const x = Math.round(ringR * Math.cos(rad));
				const z = Math.round(ringR * Math.sin(rad));
				block(x, 17, z, 'chain[axis=y]');
			}

			// Garden around the base with a bit of randomness.
			const flowers = ['poppy', 'dandelion', 'blue_orchid', 'allium', 'oxeye_daisy'];
			const gardenR = BASE + 6;
			for (let x = -gardenR; x <= gardenR; x++) {
				for (let z = -gardenR; z <= gardenR; z++) {
					const d = Math.hypot(x, z);
					if (d > BASE + 2 && d <= gardenR) {
						block(x, -1, z, 'grass_block');
						if (random() < 0.15) {
							block(x, 0, z, pick(flowers));
						}
					}
				}
			}

			// Paths from the four sides to the centre.
			for (const [sx, sz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
				for (let i = BASE - 4; i <= gardenR; i++) {
					for (let o = -1; o <= 1; o++) {
						const x = sx !== 0 ? sx * i : o;
						const z = sz !== 0 ? sz * i : o;
						block(x, -1, z, 'gravel');
					}
				}
			}

			// A sign in front.
			text('PARIS', -14, 1, gardenR + 3, 'gold_block');

			const stats = { height: H + 1, base: BASE * 2 + 1 };
			log('built tower', stats);
			weather('clear');
			time('noon');
			summon('firework_rocket', 0, H + 2, 0);
			""";

	@Test
	@Timeout(30)
	void eiffelTowerBuildsQuicklyWithinLimits() {
		run(EIFFEL_TOWER); // warm up
		long start = System.nanoTime();
		ScriptResult r = run(EIFFEL_TOWER);
		long millis = (System.nanoTime() - start) / 1_000_000;
		System.out.println("[perf] eiffel tower: " + r.blueprint().size() + " blocks in " + millis + " ms (engine "
				+ r.elapsedMillis() + " ms)");
		assertTrue(millis < 2000, "took " + millis + " ms");
		assertEquals("Eiffel Tower", r.name());
		assertEquals(List.of("built tower {\"height\":121,\"base\":53}"), r.logs());
		assertEquals(3, r.actions().size());
		assertEquals(121, r.blueprint().bounds().sizeY() - 1);
		assertTrue(r.blueprint().size() > 5000, "blocks: " + r.blueprint().size());
		assertTrue(r.blueprint().palette().contains("minecraft:ladder[facing=north]"));
	}

	@Test
	@Timeout(60)
	void threeHundredThousandBlockCallsFitTheDefaultBudget() {
		String script = """
				for (let x = 0; x < 100; x++) {
					for (let y = 0; y < 60; y++) {
						for (let z = 0; z < 50; z++) {
							block(x, y, z, (x + y + z) % 3 === 0 ? 'stone' : 'oak_planks');
						}
					}
				}
				""";
		long start = System.nanoTime();
		ScriptResult r = run(script);
		long millis = (System.nanoTime() - start) / 1_000_000;
		System.out.println("[perf] 300000 block() calls: " + millis + " ms (engine " + r.elapsedMillis() + " ms)");
		assertEquals(300_000, r.blueprint().size());
		assertTrue(r.elapsedMillis() < ScriptLimits.defaults().timeoutMillis(), "took " + r.elapsedMillis() + " ms");
	}

	@Test
	@Timeout(60)
	void largeShapesAreFast() {
		long start = System.nanoTime();
		ScriptResult r = run("fill(0, 0, 0, 99, 59, 49, 'stone'); hollow(0, 0, 0, 99, 59, 49, 'glass'); replace(0, 0, 0, 99, 59, 49, 'stone', 'air');");
		long millis = (System.nanoTime() - start) / 1_000_000;
		System.out.println("[perf] fill + hollow + replace of 300000 blocks: " + millis + " ms");
		assertEquals(300_000, r.blueprint().size());
		assertTrue(millis < 5000, "took " + millis + " ms");
	}
}
