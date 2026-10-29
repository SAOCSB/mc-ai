package io.github.saocsb.mcai.core.script;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.saocsb.mcai.core.script.ScriptTestSupport.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The example programs in the system prompt must run unchanged. */
class PromptExamplesTest {
	/** Verbatim copy of the cottage example in mcai/prompt/system.md. */
	static final String COTTAGE = """
			// PLAN: cottage 9 wide (x 0..8) x 7 deep (z 0..6), walls 4 high, gable roof running east-west
			setName('Cottage');
			const W = 9, D = 7, H = 4;
			fill(0, -1, 0, W - 1, -1, D - 1, 'cobblestone');          // foundation
			walls(0, 0, 0, W - 1, H - 1, D - 1, 'oak_planks');
			for (const [x, z] of [[0, 0], [W - 1, 0], [0, D - 1], [W - 1, D - 1]])
			  fill(x, 0, z, x, H - 1, z, 'oak_log[axis=y]');           // corner posts
			block(4, 0, D - 1, 'oak_door[facing=south,half=lower]');   // door on the south (front) wall
			block(4, 1, D - 1, 'oak_door[facing=south,half=upper]');
			for (const x of [2, 6]) block(x, 1, D - 1, 'glass_pane');  // front windows
			for (let i = 0; i <= 3; i++) {                             // roof layers rising to the ridge
			  fill(-1, H + i, i - 1, W, H + i, i - 1, 'spruce_stairs[facing=south]'); // north slope
			  fill(-1, H + i, D - i, W, H + i, D - i, 'spruce_stairs[facing=north]'); // south slope
			  for (const x of [0, W - 1]) fill(x, H + i, i, x, H + i, D - 1 - i, 'oak_planks'); // gable ends
			}
			fill(-1, H + 3, 3, W, H + 3, 3, 'spruce_slab[type=bottom]');               // ridge
			block(4, H - 1, 3, 'lantern[hanging=true]');
			""";

	/** Verbatim copy of the lattice tower example in mcai/prompt/system.md. */
	static final String LATTICE_TOWER = """
			// PLAN: square tower, 4 legs curving in from half-width 13 to 1, cross-bracing every 6 layers, 2 platforms
			setName('Lattice Tower');
			const H = 60;
			const half = y => Math.round(12 * Math.pow(1 - y / H, 1.6)) + 1;
			const corners = [[1, 1], [1, -1], [-1, 1], [-1, -1]];
			for (let y = 0; y < H; y++) {
			  const a = half(y), b = half(y + 1);
			  for (const [sx, sz] of corners) line(sx * a, y, sz * a, sx * b, y + 1, sz * b, 'iron_block');
			  if (y % 6 === 0) {
			    walls(-a, y, -a, a, y, a, 'iron_bars');                // horizontal ring
			    const c = half(y + 6);
			    for (const s of [-1, 1]) {                             // X-bracing on all four faces
			      line(-a, y, s * a, c, y + 6, s * c, 'iron_bars');  line(a, y, s * a, -c, y + 6, s * c, 'iron_bars');
			      line(s * a, y, -a, s * c, y + 6, c, 'iron_bars');  line(s * a, y, a, s * c, y + 6, -c, 'iron_bars');
			    }
			  }
			}
			for (const y of [18, 40]) { const a = half(y) + 1; fill(-a, y, -a, a, y, a, 'smooth_stone_slab[type=bottom]'); }
			line(0, H, 0, 0, H + 8, 0, 'lightning_rod');
			""";

	@Test
	void cottage() {
		ScriptResult r = run(COTTAGE);
		assertEquals("Cottage", r.name());
		// the four corner posts get logs although the loop declares x and z with const
		for (String corner : new String[] {"0,1,0", "8,1,0", "0,1,6", "8,1,6"}) {
			String[] p = corner.split(",");
			assertEquals("minecraft:oak_log[axis=y]", r.blueprint().get(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), corner);
		}
		assertEquals("minecraft:glass_pane", r.blueprint().get(2, 1, 6));
		assertEquals("minecraft:glass_pane", r.blueprint().get(6, 1, 6));
		assertEquals("minecraft:lantern[hanging=true]", r.blueprint().get(4, 3, 3));
		assertEquals("minecraft:spruce_slab[type=bottom]", r.blueprint().get(4, 7, 3));
	}

	@Test
	void latticeTower() {
		ScriptResult r = run(LATTICE_TOWER);
		assertEquals("Lattice Tower", r.name());
		assertEquals("minecraft:lightning_rod", r.blueprint().get(0, 68, 0));
		// the bracing at every 6th layer uses a const declared inside the loop body; each layer must get its own value
		assertEquals("minecraft:iron_bars", r.blueprint().get(-13, 0, -13));
		int top = 54;
		int a = (int) Math.round(12 * Math.pow(1 - top / 60.0, 1.6)) + 1;
		assertEquals("minecraft:iron_bars", r.blueprint().get(-a, top, -a));
		int platform = (int) Math.round(12 * Math.pow(1 - 40 / 60.0, 1.6)) + 2;
		assertEquals("minecraft:smooth_stone_slab[type=bottom]", r.blueprint().get(platform, 40, platform));
		assertTrue(r.blueprint().size() > 1000, "blocks: " + r.blueprint().size());
	}

	/** When the prompt resource is on the classpath, every ```js block in it must run. */
	@Test
	void everyExampleInThePromptResourceRuns() throws IOException {
		List<String> scripts;
		try (InputStream in = PromptExamplesTest.class.getResourceAsStream("/mcai/prompt/system.md")) {
			assumeTrue(in != null, "mcai/prompt/system.md is not on the classpath");
			String prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			scripts = new ArrayList<>();
			Matcher m = Pattern.compile("```js\\n(.*?)```", Pattern.DOTALL).matcher(prompt);
			while (m.find()) {
				scripts.add(m.group(1));
			}
		}
		assertFalse(scripts.isEmpty(), "no ```js examples found");
		for (String script : scripts) {
			assertFalse(run(script).blueprint().isEmpty(), script);
		}
	}
}
