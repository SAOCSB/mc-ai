package io.github.saocsb.mcai.core.preview;

import io.github.saocsb.mcai.core.blueprint.Blueprint;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BlueprintRendererTest {
	static {
		System.setProperty("java.awt.headless", "true");
	}

	/** Rendered samples land here (core/build/preview when run by Gradle). */
	private static final Path OUT = Path.of(System.getProperty("mcai.preview.dir", "build/preview"));

	@Test
	void demoSheetHasExpectedSizeAndContent() throws Exception {
		BufferedImage img = BlueprintRenderer.renderSheet(DemoScene.build(), "Demo scene", "unit test");
		assertEquals(BlueprintRenderer.SHEET_WIDTH, img.getWidth());
		assertEquals(BlueprintRenderer.SHEET_HEIGHT, img.getHeight());
		// The large isometric panel is full of coloured voxels.
		Stats big = stats(img, 40, 120, 960, 760);
		assertTrue(big.colors > 60, "distinct colours in iso panel: " + big.colors);
		assertTrue(big.nonBackground > 960 * 760 / 5, "non-background pixels: " + big.nonBackground);
		BlueprintRenderer.writePng(img, OUT.resolve("demo-sheet.png"));
		assertTrue(Files.size(OUT.resolve("demo-sheet.png")) > 10_000);
	}

	@Test
	void everySingleViewDrawsSomething() {
		VoxelMap scene = DemoScene.build();
		for (BlueprintRenderer.View v : BlueprintRenderer.View.values()) {
			BufferedImage img = BlueprintRenderer.renderIsometric(scene, v, 400, 300);
			assertTrue(stats(img, 0, 0, 400, 300).nonBackground > 400 * 300 / 6, v.name());
		}
		assertTrue(stats(BlueprintRenderer.renderFront(scene, 400, 300), 0, 0, 400, 300).nonBackground > 15_000);
		assertTrue(stats(BlueprintRenderer.renderPlan(scene, 400, 300), 0, 0, 400, 300).nonBackground > 15_000);
	}

	@Test
	void singleBlockIsCentredAndShaded() {
		VoxelMap m = new VoxelMap();
		m.set(0, 0, 0, "minecraft:stone");
		BufferedImage img = BlueprintRenderer.renderIsometric(m, BlueprintRenderer.View.SOUTH_EAST, 200, 200);
		int top = img.getRGB(100, 100 - 12) & 0xffffff;
		int left = img.getRGB(100 - 10, 100 + 10) & 0xffffff;
		int right = img.getRGB(100 + 10, 100 + 10) & 0xffffff;
		// Top lit, left (south) medium, right (east) darkest, all grey.
		assertTrue(lum(top) > lum(left) && lum(left) > lum(right), hex(top) + " " + hex(left) + " " + hex(right));
	}

	@Test
	void airAndInvisibleBlocksAreNotDrawn() {
		VoxelMap m = new VoxelMap();
		m.set(0, 0, 0, "minecraft:air");
		m.set(1, 0, 0, "barrier");
		BufferedImage img = BlueprintRenderer.renderIsometric(m, BlueprintRenderer.View.SOUTH_EAST, 120, 120);
		BufferedImage none = BlueprintRenderer.renderIsometric(new VoxelMap(), BlueprintRenderer.View.SOUTH_EAST, 120, 120);
		for (int y = 0; y < 120; y++) {
			for (int x = 0; x < 120; x++) {
				assertEquals(none.getRGB(x, y), img.getRGB(x, y));
			}
		}
		BufferedImage sheet = BlueprintRenderer.renderSheet(new VoxelMap(), "empty", null);
		assertEquals(BlueprintRenderer.SHEET_WIDTH, sheet.getWidth());
	}

	@Test
	void hiddenInteriorIsCulledSoHugeBuildsRenderQuickly() {
		// ~300k blocks: a 68^3 solid cube minus nothing, plus noise terrain around it.
		VoxelMap m = new VoxelMap();
		for (int x = 0; x < 68; x++) {
			for (int y = 0; y < 64; y++) {
				for (int z = 0; z < 68; z++) {
					m.set(x, y, z, (x + y + z) % 7 == 0 ? "minecraft:stone_bricks" : "minecraft:stone");
				}
			}
		}
		assertTrue(m.size() >= 295_000);
		long t0 = System.nanoTime();
		BufferedImage img = BlueprintRenderer.renderSheet(m, "big cube", null);
		long ms = (System.nanoTime() - t0) / 1_000_000;
		assertTrue(ms < 20_000, "render took " + ms + " ms");
		assertTrue(stats(img, 40, 120, 960, 760).nonBackground > 100_000);
	}

	@Test
	void coloursFollowTheTableAndHeuristics() {
		BlockStyle stairs = BlockStyle.of("minecraft:oak_stairs[facing=east,half=bottom]");
		BlockStyle planks = BlockStyle.of("oak_planks");
		assertEquals(planks.ns, stairs.ns);
		assertEquals(BlockStyle.Kind.STAIRS, stairs.kind);
		assertEquals(BlockStyle.of("stone_bricks").ns, BlockStyle.of("minecraft:stone_brick_wall").ns);
		assertEquals(BlockStyle.of("nether_bricks").ns, BlockStyle.of("nether_brick_fence").ns);
		assertEquals(BlockStyle.of("quartz_block").ns, BlockStyle.of("quartz_slab[type=top]").ns);
		assertEquals(BlockStyle.of("oxidized_cut_copper").ns, BlockStyle.of("waxed_oxidized_cut_copper_stairs").ns);
		assertEquals(BlockStyle.of("red_wool").ns, BlockStyle.of("red_carpet").ns);
		assertTrue(BlockStyle.of("glass").alpha < 128);
		assertTrue(BlockStyle.of("lime_stained_glass_pane").alpha < 255);
		assertTrue(BlockStyle.of("oak_leaves").alpha < 255 && BlockStyle.of("oak_leaves").alpha > 180);
		assertEquals(BlockStyle.Kind.INVISIBLE, BlockStyle.of("minecraft:air").kind);
		// Grass: green top, brown sides. Logs: end grain on top unless the axis is horizontal.
		BlockStyle grass = BlockStyle.of("grass_block");
		assertTrue(((grass.up >> 8) & 0xff) > ((grass.up >> 16) & 0xff));
		BlockStyle log = BlockStyle.of("spruce_log[axis=x]");
		assertNotEquals(log.ew, log.ns);
		// Unknown ids get a stable pastel; modded wood keeps a wood colour.
		assertEquals(BlockStyle.of("mymod:weird_thing").ns, BlockStyle.of("mymod:weird_thing").ns);
		assertEquals(BlockStyle.of("cherry_planks").ns, BlockStyle.of("cherry_bookshelf_thing").ns);
		assertTrue(BlockColors.tableSize() >= 200, "table entries: " + BlockColors.tableSize());
		assertEquals("oak_stairs", BlockStyle.barePath(" Minecraft:Oak_Stairs[facing=north]{x:1} "));
	}

	@Test
	void fenceArmsConnectToNeighbours() {
		BlockStyle fence = BlockStyle.of("oak_fence");
		int[] out = new int[54];
		assertEquals(1, fence.boxes(null, null, null, null, out));
		assertEquals(5, fence.boxes(null, null, fence, fence, out));
		BlockStyle pane = BlockStyle.of("glass_pane");
		// A straight run is one sheet spanning the block; a corner is a post with two arms.
		assertEquals(1, pane.boxes(null, null, pane, BlockStyle.of("stone"), out));
		assertEquals(0, out[0]);
		assertEquals(16, out[3]);
		assertEquals(3, pane.boxes(pane, null, null, pane, out));
	}

	@Test
	void blueprintRendersOnceGeometryIsAvailable() throws Exception {
		Blueprint bp;
		try {
			bp = new Blueprint(10_000, 64);
		} catch (UnsupportedOperationException e) {
			assumeTrue(false, "Blueprint not implemented on this branch yet");
			return;
		}
		DemoScene.build(bp);
		BufferedImage img = BlueprintRenderer.renderSheet(bp, "Blueprint demo");
		assertEquals(BlueprintRenderer.SHEET_WIDTH, img.getWidth());
		assertTrue(stats(img, 40, 120, 960, 760).colors > 60);
		BlueprintRenderer.writePng(img, OUT.resolve("blueprint-sheet.png"));
	}

	@Test
	void fallbackExtractorTakesJsBlocksOnly() {
		String reply = """
				<think>plan the tower</think>
				Here is your **tower**!
				```json
				{"not": "code"}
				```
				```js
				fill(0,0,0,4,10,4,'stone');
				```
				Enjoy.
				~~~javascript
				setName('Tower');
				""";
		PreviewMain.Extracted ex = PreviewMain.extractFallback(reply);
		assertEquals("fill(0,0,0,4,10,4,'stone');\n\nsetName('Tower');", ex.script().strip());
		assertTrue(ex.unterminated());
		assertTrue(ex.chat().contains("tower") && !ex.chat().contains("plan the"));
		assertNull(PreviewMain.extractFallback("no code here").script());
	}

	@Test
	void cliDemoWritesPngAndJson() throws Exception {
		Path png = OUT.resolve("cli-demo.png");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
		int code = PreviewMain.run(new String[] {"--demo", png.toString()}, out, out);
		String json = bytes.toString(StandardCharsets.UTF_8);
		assertEquals(0, code, json);
		assertTrue(json.contains("\"ok\": true") && json.contains("\"palette\""), json);
		assertTrue(Files.size(png) > 10_000);
	}

	@Test
	void cliReportsMissingScriptInReply() throws Exception {
		Path reply = Files.createTempFile("reply", ".md");
		Files.writeString(reply, "Sorry, I can't build that.\n```json\n{}\n```\n");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
		int code = PreviewMain.run(new String[] {reply.toString(), OUT.resolve("none.png").toString()}, out, out);
		assertEquals(PreviewMain.EXIT_SCRIPT, code);
		String json = bytes.toString(StandardCharsets.UTF_8);
		assertTrue(json.contains("\"ok\": false") && json.contains("\"error\": \"no build script"), json);
		for (String key : new String[] {"name", "blocks", "bounds", "palette", "actions", "logs", "elapsedMillis", "placement"}) {
			assertTrue(json.contains("\"" + key + "\":"), key + " missing: " + json);
		}
		Files.deleteIfExists(reply);
	}

	private record Stats(int nonBackground, int colors) {
	}

	private static Stats stats(BufferedImage img, int x0, int y0, int w, int h) {
		int bg = img.getRGB(x0 + 2, y0 + 2);
		int non = 0;
		Set<Integer> colors = new HashSet<>();
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				int c = img.getRGB(x, y);
				if (c != bg) {
					non++;
				}
				colors.add(c);
			}
		}
		return new Stats(non, colors.size());
	}

	private static double lum(int rgb) {
		return 0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff);
	}

	private static String hex(int rgb) {
		return String.format("#%06x", rgb);
	}
}
