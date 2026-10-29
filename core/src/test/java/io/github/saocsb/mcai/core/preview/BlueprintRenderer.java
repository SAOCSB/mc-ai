package io.github.saocsb.mcai.core.preview;

import io.github.saocsb.mcai.core.blueprint.Blueprint;
import io.github.saocsb.mcai.core.blueprint.Bounds;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a blueprint to PNG-ready images for eyeballing what a build script produced.
 *
 * <p>Views: an isometric projection from any of the four diagonals (orthographic, 30 degrees
 * elevation, 2:1 pixel ratio), a front elevation (from the south, looking north, +x to the right)
 * and a top plan (north up, the +z front at the bottom). {@link #renderSheet} composes them with a
 * caption and a palette legend.
 *
 * <p>Voxels are drawn with the painter's algorithm: cells sorted by depth (exact for grid cells under
 * an orthographic view), sub-boxes of partial blocks ordered within their cell. Faces touching an
 * opaque full neighbour are culled, so a block whose three visible-side neighbours are opaque costs
 * nothing, and large solid builds render quickly. Glass, ice and water are alpha-blended; leaves are
 * slightly translucent. Headless-safe; uses only java.awt and javax.imageio.
 */
public final class BlueprintRenderer {
	/** Diagonal the isometric camera looks from (it always looks down at 30 degrees). */
	public enum View {
		SOUTH_EAST("from south-east"), SOUTH_WEST("from south-west"), NORTH_WEST("from north-west"),
		NORTH_EAST("from north-east");

		final String label;

		View(String label) {
			this.label = label;
		}
	}

	public static final int SHEET_WIDTH = 1600;
	public static final int SHEET_HEIGHT = 1200;
	/** Largest on-screen cube edge in pixels. */
	static final double MAX_CUBE = 24;
	/** Smallest cube edge; builds that do not fit even at 1 px shrink further (down to 0.25) rather than clip. */
	static final double MIN_CUBE = 0.25;
	/** Projected vertical edge / half the projected cube width, for a 30 degree elevation. */
	private static final double ELEV = Math.sqrt(1.5);

	private static final Color SHEET_BG = new Color(0xE9ECEF);
	private static final Color PANEL_BG = new Color(0xDCE3EA);
	private static final Color PANEL_BORDER = new Color(0xC3CCD6);
	private static final Color INK = new Color(0x1F2328);
	private static final Color MUTED = new Color(0x5B6570);
	private static final Color ACCENT = new Color(0xC2412D);
	private static final Color OUTLINE = new Color(0, 0, 0, 46);
	private static final Color OUTLINE_SOFT = new Color(0, 0, 0, 22);

	private static final double SHADE_TOP = 1.0;
	private static final double SHADE_LEFT = 0.80;
	private static final double SHADE_RIGHT = 0.63;

	private static boolean textBroken;

	private BlueprintRenderer() {
	}

	// ---------------------------------------------------------------- public API

	/** The full preview sheet ({@value #SHEET_WIDTH}x{@value #SHEET_HEIGHT}) for a blueprint. */
	public static BufferedImage renderSheet(Blueprint blueprint, String title) {
		return renderSheet(VoxelSource.of(blueprint), title, null);
	}

	/**
	 * The full preview sheet: large isometric from the south-east, small isometric from the north-west,
	 * front elevation, top plan, caption and palette legend.
	 *
	 * @param subtitle optional extra caption line (seed, timings...), may be null
	 */
	public static BufferedImage renderSheet(VoxelSource source, String title, String subtitle) {
		Scene sc = new Scene(source);
		BufferedImage img = new BufferedImage(SHEET_WIDTH, SHEET_HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		try {
			g.setColor(SHEET_BG);
			g.fillRect(0, 0, SHEET_WIDTH, SHEET_HEIGHT);
			drawHeader(g, sc, title, subtitle, new Rectangle(16, 8, SHEET_WIDTH - 32, 60));

			int top = 74;
			int gap = 10;
			int leftW = 1020;
			int legendH = 236;
			Rectangle big = new Rectangle(12, top, leftW, SHEET_HEIGHT - top - legendH - gap - 12);
			Rectangle legend = new Rectangle(12, big.y + big.height + gap, leftW, legendH);
			int rx = big.x + leftW + gap;
			int rw = SHEET_WIDTH - rx - 12;
			int rh = (SHEET_HEIGHT - top - 12 - 2 * gap) / 3;
			Rectangle nw = new Rectangle(rx, top, rw, rh);
			Rectangle front = new Rectangle(rx, top + rh + gap, rw, rh);
			Rectangle plan = new Rectangle(rx, top + 2 * (rh + gap), rw, SHEET_HEIGHT - 12 - (top + 2 * (rh + gap)));

			drawIsoPanel(g, sc, View.SOUTH_EAST, big, "ISOMETRIC  " + View.SOUTH_EAST.label + " (front +z visible)");
			drawIsoPanel(g, sc, View.NORTH_WEST, nw, "ISOMETRIC  " + View.NORTH_WEST.label + " (back)");
			Rectangle c = panel(g, front, "FRONT ELEVATION  from the south, looking north");
			drawFront(g, sc, c);
			g.setClip(null);
			c = panel(g, plan, "TOP PLAN  north up, front (south) down");
			drawPlan(g, sc, c);
			g.setClip(null);
			c = panel(g, legend, "PALETTE  (by block count)");
			drawLegend(g, sc, c);
			g.setClip(null);
		} finally {
			g.dispose();
		}
		return img;
	}

	/** A single isometric view on a plain background. */
	public static BufferedImage renderIsometric(VoxelSource source, View view, int width, int height) {
		boolean south = view == View.SOUTH_EAST || view == View.SOUTH_WEST;
		return single(width, height, (g, sc, r) -> drawIso(g, sc, view, r, south), source);
	}

	/** A single front elevation (looking north from the south). */
	public static BufferedImage renderFront(VoxelSource source, int width, int height) {
		return single(width, height, BlueprintRenderer::drawFront, source);
	}

	/** A single top-down plan (north up). */
	public static BufferedImage renderPlan(VoxelSource source, int width, int height) {
		return single(width, height, BlueprintRenderer::drawPlan, source);
	}

	public static void writePng(BufferedImage image, Path out) throws IOException {
		Path parent = out.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		if (!ImageIO.write(image, "png", out.toFile())) {
			throw new IOException("no PNG writer available");
		}
	}

	private interface Painter {
		void paint(Graphics2D g, Scene sc, Rectangle r);
	}

	private static BufferedImage single(int width, int height, Painter painter, VoxelSource source) {
		Scene sc = new Scene(source);
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		try {
			g.setColor(PANEL_BG);
			g.fillRect(0, 0, width, height);
			painter.paint(g, sc, new Rectangle(0, 0, width, height));
		} finally {
			g.dispose();
		}
		return img;
	}

	// ---------------------------------------------------------------- scene

	/** Voxels loaded from a source: positions, a style per distinct id, and a position index. */
	static final class Scene {
		final List<BlockStyle> styleList = new ArrayList<>();
		private final Map<String, Integer> styleIds = new HashMap<>();
		BlockStyle[] styles = new BlockStyle[0];
		int count;
		int[] xs = new int[256], ys = new int[256], zs = new int[256], st = new int[256];
		final LongIntMap index = new LongIntMap(256);
		/** Non-air entries per block type (bare path, namespace kept unless minecraft), with a representative style. */
		final Map<String, int[]> pathCounts = new LinkedHashMap<>();
		final Map<String, BlockStyle> pathStyle = new HashMap<>();
		int airCount;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		final Bounds sourceBounds;

		Scene(VoxelSource source) {
			sourceBounds = source.bounds();
			source.forEach(this::add);
			styles = styleList.toArray(new BlockStyle[0]);
		}

		private void add(int x, int y, int z, String id) {
			if (id == null || id.isBlank()) {
				return;
			}
			if (BlockStyle.isAir(id)) {
				airCount++;
				return;
			}
			Integer sid = styleIds.get(id);
			if (sid == null) {
				sid = styleList.size();
				BlockStyle style = BlockStyle.of(id);
				styleList.add(style);
				styleIds.put(id, sid);
				pathStyle.putIfAbsent(BlockStyle.typeName(id), style);
			}
			BlockStyle style = styleList.get(sid);
			pathCounts.computeIfAbsent(BlockStyle.typeName(id), k -> new int[1])[0]++;
			if (!style.visible()) {
				return;
			}
			long key = pack(x, y, z);
			int existing = index.get(key);
			if (existing >= 0) {
				st[existing] = sid;
				return;
			}
			if (count == xs.length) {
				int n = count * 2;
				xs = Arrays.copyOf(xs, n);
				ys = Arrays.copyOf(ys, n);
				zs = Arrays.copyOf(zs, n);
				st = Arrays.copyOf(st, n);
			}
			xs[count] = x;
			ys[count] = y;
			zs[count] = z;
			st[count] = sid;
			index.put(key, count);
			count++;
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
			maxZ = Math.max(maxZ, z);
		}

		/** Non-air entries, invisible ones (barrier, light...) included. */
		int blockCount() {
			int n = 0;
			for (int[] c : pathCounts.values()) {
				n += c[0];
			}
			return n;
		}

		BlockStyle styleAt(int x, int y, int z) {
			int i = index.get(pack(x, y, z));
			return i < 0 ? null : styles[st[i]];
		}

		boolean occluder(int x, int y, int z) {
			int i = index.get(pack(x, y, z));
			return i >= 0 && styles[st[i]].occluder();
		}

		/** Whether the face of a full cell of style {@code s} toward (x, y, z) is hidden by that neighbour. */
		boolean hides(BlockStyle s, int x, int y, int z) {
			int i = index.get(pack(x, y, z));
			if (i < 0) {
				return false;
			}
			BlockStyle n = styles[st[i]];
			return n.occluder() || (n == s && n.kind == BlockStyle.Kind.FULL);
		}

		static long pack(int x, int y, int z) {
			return ((long) ((x + (1 << 20)) & 0x1FFFFF) << 42) | ((long) ((y + (1 << 20)) & 0x1FFFFF) << 21)
					| ((z + (1 << 20)) & 0x1FFFFF);
		}
	}

	/** Open-addressing long -> int map (-1 = absent); keys never equal Long.MIN_VALUE. */
	static final class LongIntMap {
		private static final long EMPTY = Long.MIN_VALUE;
		private long[] keys;
		private int[] vals;
		private int size;
		private int mask;

		LongIntMap(int expected) {
			int cap = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
			alloc(cap);
		}

		private void alloc(int cap) {
			keys = new long[cap];
			Arrays.fill(keys, EMPTY);
			vals = new int[cap];
			mask = cap - 1;
		}

		int get(long k) {
			int i = mix(k) & mask;
			while (true) {
				long kk = keys[i];
				if (kk == k) {
					return vals[i];
				}
				if (kk == EMPTY) {
					return -1;
				}
				i = (i + 1) & mask;
			}
		}

		void put(long k, int v) {
			if ((size + 1) * 2 > keys.length) {
				long[] ok = keys;
				int[] ov = vals;
				alloc(keys.length * 2);
				size = 0;
				for (int i = 0; i < ok.length; i++) {
					if (ok[i] != EMPTY) {
						put(ok[i], ov[i]);
					}
				}
			}
			int i = mix(k) & mask;
			while (true) {
				if (keys[i] == EMPTY) {
					keys[i] = k;
					vals[i] = v;
					size++;
					return;
				}
				if (keys[i] == k) {
					vals[i] = v;
					return;
				}
				i = (i + 1) & mask;
			}
		}

		private static int mix(long k) {
			k ^= k >>> 33;
			k *= 0xff51afd7ed558ccdL;
			k ^= k >>> 33;
			k *= 0xc4ceb9fe1a85ec53L;
			k ^= k >>> 33;
			return (int) k;
		}
	}

	// ---------------------------------------------------------------- isometric

	/** World -> view mapping: the camera always sits at view (+u, +v, +w); +u is drawn right, +w left. */
	private static double viewU(View v, double x, double z) {
		return switch (v) {
			case SOUTH_EAST -> x;
			case NORTH_WEST -> -x;
			case NORTH_EAST -> -z;
			case SOUTH_WEST -> z;
		};
	}

	private static double viewW(View v, double x, double z) {
		return switch (v) {
			case SOUTH_EAST -> z;
			case NORTH_WEST -> -z;
			case NORTH_EAST -> x;
			case SOUTH_WEST -> -x;
		};
	}

	private static void drawIsoPanel(Graphics2D g, Scene sc, View view, Rectangle r, String title) {
		Rectangle c = panel(g, r, title);
		drawIso(g, sc, view, c, view == View.SOUTH_EAST || view == View.SOUTH_WEST);
		g.setClip(null);
	}

	static void drawIso(Graphics2D g, Scene sc, View view, Rectangle r, boolean frontLabel) {
		if (sc.count == 0) {
			drawEmpty(g, r);
			return;
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		// Fit: project the bounding box corners at unit scale.
		double x0 = sc.minX, x1 = sc.maxX + 1, y0 = sc.minY, y1 = sc.maxY + 1, z0 = sc.minZ, z1 = sc.maxZ + 1;
		double minSX = Double.MAX_VALUE, maxSX = -Double.MAX_VALUE, minSY = Double.MAX_VALUE, maxSY = -Double.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			double x = (i & 1) == 0 ? x0 : x1, y = (i & 2) == 0 ? y0 : y1, z = (i & 4) == 0 ? z0 : z1;
			double u = viewU(view, x, z), w = viewW(view, x, z);
			double sx = u - w, sy = (u + w) * 0.5 - y * ELEV;
			minSX = Math.min(minSX, sx);
			maxSX = Math.max(maxSX, sx);
			minSY = Math.min(minSY, sy);
			maxSY = Math.max(maxSY, sy);
		}
		int margin = 26;
		double a = Math.min((r.width - 2.0 * margin) / (maxSX - minSX), (r.height - 2.0 * margin) / (maxSY - minSY));
		a = Math.max(MIN_CUBE / ELEV, Math.min(MAX_CUBE / ELEV, a));
		Iso iso = new Iso(a, r.x + r.width / 2.0 - a * (minSX + maxSX) / 2, r.y + r.height / 2.0 - a * (minSY + maxSY) / 2);

		drawFootprint(g, sc, view, iso);

		// Visible voxels, bucketed by cell depth (u + v + w of the cell's min corner).
		int[] ud = worldStep(view, true);
		int[] wd = worldStep(view, false);
		int n = sc.count;
		int[] key = new int[n];
		int[] sel = new int[n];
		int m = 0;
		int minKey = Integer.MAX_VALUE, maxKey = Integer.MIN_VALUE;
		for (int i = 0; i < n; i++) {
			int x = sc.xs[i], y = sc.ys[i], z = sc.zs[i];
			BlockStyle s = sc.styles[sc.st[i]];
			// Every ray toward the camera leaves the cell through its top, +u or +w face.
			if (sc.hides(s, x, y + 1, z) && sc.hides(s, x + ud[0], y, z + ud[1]) && sc.hides(s, x + wd[0], y, z + wd[1])) {
				continue;
			}
			int k = cellU(view, x, z) + y + cellW(view, x, z);
			key[m] = k;
			sel[m++] = i;
			minKey = Math.min(minKey, k);
			maxKey = Math.max(maxKey, k);
		}
		int[] order = countingSort(sel, key, m, minKey, maxKey);

		IsoColors colors = new IsoColors(sc.styles, view);
		boolean outlines = a * ELEV >= 6;
		int[] boxes = new int[9 * 6];
		double[] vb = new double[9 * 6];
		int[] boxOrder = new int[9];
		for (int oi = 0; oi < m; oi++) {
			int i = order[oi];
			int x = sc.xs[i], y = sc.ys[i], z = sc.zs[i];
			int sid = sc.st[i];
			BlockStyle s = sc.styles[sid];
			int nb = boxesFor(sc, s, x, y, z, boxes);
			for (int b = 0; b < nb; b++) {
				int o = b * 6;
				double bx0 = x + boxes[o] / 16.0, by0 = y + boxes[o + 1] / 16.0, bz0 = z + boxes[o + 2] / 16.0;
				double bx1 = x + boxes[o + 3] / 16.0, by1 = y + boxes[o + 4] / 16.0, bz1 = z + boxes[o + 5] / 16.0;
				double ua = viewU(view, bx0, bz0), ub = viewU(view, bx1, bz1);
				double wa = viewW(view, bx0, bz0), wb = viewW(view, bx1, bz1);
				vb[o] = Math.min(ua, ub);
				vb[o + 3] = Math.max(ua, ub);
				vb[o + 1] = by0;
				vb[o + 4] = by1;
				vb[o + 2] = Math.min(wa, wb);
				vb[o + 5] = Math.max(wa, wb);
			}
			orderBoxes(vb, nb, boxOrder);
			double cu1 = cellU(view, x, z) + 1, cw1 = cellW(view, x, z) + 1;
			// A face on the cell boundary is hidden by an opaque cube next door, or by an identical block
			// whose mirrored face coincides with it: true when the box spans the whole cell along that
			// axis, and for the arms of fences, walls and panes (connections are symmetric).
			BlockStyle nTop = sc.styleAt(x, y + 1, z);
			BlockStyle nU = sc.styleAt(x + ud[0], y, z + ud[1]);
			BlockStyle nW = sc.styleAt(x + wd[0], y, z + wd[1]);
			boolean topHidden = nTop != null && (nTop.occluder() || (nTop == s && s.kind == BlockStyle.Kind.FULL));
			boolean uOpaque = nU != null && nU.occluder(), wOpaque = nW != null && nW.occluder();
			boolean connecting = s.connecting();
			for (int bi = 0; bi < nb; bi++) {
				int o = boxOrder[bi] * 6;
				double u0 = vb[o], v0 = vb[o + 1], w0 = vb[o + 2], u1 = vb[o + 3], v1 = vb[o + 4], w1 = vb[o + 5];
				boolean drawTop = !(boxes[o + 4] == 16 && topHidden);
				boolean drawU = !(u1 == cu1 && (uOpaque || (nU == s && (connecting || u1 - u0 == 1))));
				boolean drawW = !(w1 == cw1 && (wOpaque || (nW == s && (connecting || w1 - w0 == 1))));
				boolean outline = outlines && Math.min(Math.min(u1 - u0, w1 - w0), v1 - v0) * a * ELEV >= 3.5;
				Color line = s.alpha < 255 ? OUTLINE_SOFT : OUTLINE;
				if (drawW) {
					iso.quad(g, colors.left[sid], outline ? line : null, u0, v0, w1, u1, v0, w1, u1, v1, w1, u0, v1, w1);
				}
				if (drawU) {
					iso.quad(g, colors.right[sid], outline ? line : null, u1, v0, w0, u1, v0, w1, u1, v1, w1, u1, v1, w0);
				}
				if (drawTop) {
					iso.quad(g, colors.top[sid], outline ? line : null, u0, v1, w0, u1, v1, w0, u1, v1, w1, u0, v1, w1);
				}
			}
		}
		if (frontLabel) {
			drawFrontLabel(g, sc, view, iso);
		}
		drawCompass(g, view, r.x + 44, r.y + r.height - 34);
		label(g, String.format(Locale.ROOT, "cube %.1f px", a * ELEV), r.x + r.width - 4, r.y + r.height - 6, 11,
				false, MUTED, 1);
	}

	private static int boxesFor(Scene sc, BlockStyle s, int x, int y, int z, int[] out) {
		BlockStyle n = null, so = null, w = null, e = null;
		if (s.kind == BlockStyle.Kind.FENCE || s.kind == BlockStyle.Kind.WALL || s.kind == BlockStyle.Kind.PANE) {
			n = sc.styleAt(x, y, z - 1);
			so = sc.styleAt(x, y, z + 1);
			w = sc.styleAt(x - 1, y, z);
			e = sc.styleAt(x + 1, y, z);
		}
		return s.boxes(n, so, w, e, out);
	}

	/**
	 * Orders the boxes of one cell back to front: box A goes before B when A cannot hide B (A ends
	 * before B starts on some view axis) while B can hide A.
	 */
	private static void orderBoxes(double[] vb, int nb, int[] order) {
		for (int i = 0; i < nb; i++) {
			order[i] = i;
		}
		if (nb < 2) {
			return;
		}
		boolean[] used = new boolean[nb];
		for (int slot = 0; slot < nb; slot++) {
			int pick = -1;
			for (int c = 0; c < nb && pick < 0; c++) {
				if (used[c]) {
					continue;
				}
				boolean blocked = false;
				for (int d = 0; d < nb && !blocked; d++) {
					if (d != c && !used[d] && behind(vb, d, c) && !behind(vb, c, d)) {
						blocked = true;
					}
				}
				if (!blocked) {
					pick = c;
				}
			}
			if (pick < 0) {
				for (int c = 0; c < nb; c++) {
					if (!used[c]) {
						pick = c;
						break;
					}
				}
			}
			used[pick] = true;
			order[slot] = pick;
		}
	}

	/** Box a ends at or before box b starts on some view axis, so a can never hide b. */
	private static boolean behind(double[] vb, int a, int b) {
		int oa = a * 6, ob = b * 6;
		return vb[oa + 3] <= vb[ob] || vb[oa + 4] <= vb[ob + 1] || vb[oa + 5] <= vb[ob + 2];
	}

	/** World (dx, dz) of the cell next door along view +u (u=true) or +w. */
	private static int[] worldStep(View v, boolean u) {
		return switch (v) {
			case SOUTH_EAST -> u ? new int[] {1, 0} : new int[] {0, 1};
			case NORTH_WEST -> u ? new int[] {-1, 0} : new int[] {0, -1};
			case NORTH_EAST -> u ? new int[] {0, -1} : new int[] {1, 0};
			case SOUTH_WEST -> u ? new int[] {0, 1} : new int[] {-1, 0};
		};
	}

	private static int cellU(View v, int x, int z) {
		return switch (v) {
			case SOUTH_EAST -> x;
			case NORTH_WEST -> -x - 1;
			case NORTH_EAST -> -z - 1;
			case SOUTH_WEST -> z;
		};
	}

	private static int cellW(View v, int x, int z) {
		return switch (v) {
			case SOUTH_EAST -> z;
			case NORTH_WEST -> -z - 1;
			case NORTH_EAST -> x;
			case SOUTH_WEST -> -x - 1;
		};
	}

	/** Stable counting sort of {@code sel[0..m)} by {@code key}. */
	private static int[] countingSort(int[] sel, int[] key, int m, int minKey, int maxKey) {
		int[] out = new int[m];
		if (m == 0) {
			return out;
		}
		int[] start = new int[maxKey - minKey + 2];
		for (int i = 0; i < m; i++) {
			start[key[i] - minKey + 1]++;
		}
		for (int i = 1; i < start.length; i++) {
			start[i] += start[i - 1];
		}
		for (int i = 0; i < m; i++) {
			out[start[key[i] - minKey]++] = sel[i];
		}
		return out;
	}

	/** Orthographic 2:1 projection: screen = origin + (u - w) * a, (u + w) * a/2 - v * a * ELEV. */
	private static final class Iso {
		final double a, b, c, ox, oy;
		final int[] px = new int[4], py = new int[4];

		Iso(double a, double ox, double oy) {
			this.a = a;
			this.b = a / 2;
			this.c = a * ELEV;
			this.ox = ox;
			this.oy = oy;
		}

		int sx(double u, double w) {
			return (int) Math.round(ox + (u - w) * a);
		}

		int sy(double u, double v, double w) {
			return (int) Math.round(oy + (u + w) * b - v * c);
		}

		void quad(Graphics2D g, Color fill, Color line, double... p) {
			for (int k = 0; k < 4; k++) {
				px[k] = sx(p[k * 3], p[k * 3 + 2]);
				py[k] = sy(p[k * 3], p[k * 3 + 1], p[k * 3 + 2]);
			}
			g.setColor(fill);
			g.fillPolygon(px, py, 4);
			if (line != null) {
				g.setColor(line);
				g.drawPolygon(px, py, 4);
			}
		}
	}

	/** Face colours per style for one view: top, left (+w) and right (+u) faces. */
	private static final class IsoColors {
		final Color[] top, left, right;

		IsoColors(BlockStyle[] styles, View view) {
			top = new Color[styles.length];
			left = new Color[styles.length];
			right = new Color[styles.length];
			boolean uIsEastWest = view == View.SOUTH_EAST || view == View.NORTH_WEST;
			for (int i = 0; i < styles.length; i++) {
				BlockStyle s = styles[i];
				top[i] = shade(s.up, SHADE_TOP, s.alpha);
				right[i] = shade(uIsEastWest ? s.ew : s.ns, SHADE_RIGHT, s.alpha);
				left[i] = shade(uIsEastWest ? s.ns : s.ew, SHADE_LEFT, s.alpha);
			}
		}
	}

	private static Color shade(int rgb, double f, int alpha) {
		int r = (int) Math.min(255, Math.round(((rgb >> 16) & 0xff) * f));
		int g = (int) Math.min(255, Math.round(((rgb >> 8) & 0xff) * f));
		int b = (int) Math.min(255, Math.round((rgb & 0xff) * f));
		return new Color(r, g, b, alpha);
	}

	/** Shadowed footprint of the bounding box at its lowest layer, with a light grid and a FRONT marker. */
	private static void drawFootprint(Graphics2D g, Scene sc, View view, Iso iso) {
		double y = sc.minY;
		double[][] corners = {{sc.minX, sc.minZ}, {sc.maxX + 1, sc.minZ}, {sc.maxX + 1, sc.maxZ + 1}, {sc.minX, sc.maxZ + 1}};
		int[] px = new int[4], py = new int[4];
		for (int k = 0; k < 4; k++) {
			double u = viewU(view, corners[k][0], corners[k][1]), w = viewW(view, corners[k][0], corners[k][1]);
			px[k] = iso.sx(u, w);
			py[k] = iso.sy(u, y, w);
		}
		g.setColor(new Color(0, 0, 0, 24));
		g.fillPolygon(px, py, 4);
		int step = 1;
		while (step * iso.a < 7) {
			step = step == 1 ? 5 : step * 2;
		}
		g.setColor(new Color(0, 0, 0, 16));
		for (int gx = sc.minX; gx <= sc.maxX + 1; gx += step) {
			line(g, iso, view, gx, sc.minZ, gx, sc.maxZ + 1, y);
		}
		for (int gz = sc.minZ; gz <= sc.maxZ + 1; gz += step) {
			line(g, iso, view, sc.minX, gz, sc.maxX + 1, gz, y);
		}
		g.setColor(new Color(0, 0, 0, 60));
		g.drawPolygon(px, py, 4);
	}

	/** "FRONT" just outside the middle of the footprint's south (+z) edge. */
	private static void drawFrontLabel(Graphics2D g, Scene sc, View view, Iso iso) {
		double y = sc.minY;
		double mx = (sc.minX + sc.maxX + 1) / 2.0, mz = sc.maxZ + 1, cz = (sc.minZ + sc.maxZ + 1) / 2.0;
		double eu = viewU(view, mx, mz), ew = viewW(view, mx, mz), cu = viewU(view, mx, cz), cw = viewW(view, mx, cz);
		int ex = iso.sx(eu, ew), ey = iso.sy(eu, y, ew);
		double dx = ex - iso.sx(cu, cw), dy = ey - iso.sy(cu, y, cw), len = Math.max(1e-9, Math.hypot(dx, dy));
		dx /= len;
		dy /= len;
		int lx = (int) Math.round(ex + dx * 14), ly = (int) Math.round(ey + dy * 14);
		int align = dx < -0.3 ? 1 : dx > 0.3 ? -1 : 0;
		g.setColor(ACCENT);
		g.fillOval(ex - 3, ey - 3, 6, 6);
		label(g, "FRONT (south, +z)", lx, ly + (dy > 0 ? 10 : -2), 12, true, ACCENT, align);
	}

	private static void line(Graphics2D g, Iso iso, View view, double xa, double za, double xb, double zb, double y) {
		double ua = viewU(view, xa, za), wa = viewW(view, xa, za), ub = viewU(view, xb, zb), wb = viewW(view, xb, zb);
		g.drawLine(iso.sx(ua, wa), iso.sy(ua, y, wa), iso.sx(ub, wb), iso.sy(ub, y, wb));
	}

	/** Compass rose showing where N/E/S/W point in this view; S is the build's front. */
	private static void drawCompass(Graphics2D g, View view, int cx, int cy) {
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Stroke old = g.getStroke();
		g.setColor(new Color(255, 255, 255, 140));
		g.fillOval(cx - 40, cy - 25, 80, 50);
		String[] names = {"N", "E", "S", "W"};
		int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
		for (int k = 0; k < 4; k++) {
			double u = viewU(view, dirs[k][0], dirs[k][1]), w = viewW(view, dirs[k][0], dirs[k][1]);
			double dx = u - w, dy = (u + w) * 0.5;
			double len = Math.hypot(dx, dy);
			dx /= len;
			dy /= len;
			boolean front = k == 2;
			g.setStroke(new BasicStroke(front ? 2.2f : 1.4f));
			g.setColor(front ? ACCENT : MUTED);
			g.drawLine(cx, cy, (int) Math.round(cx + dx * 24), (int) Math.round(cy + dy * 15));
			label(g, names[k], (int) Math.round(cx + dx * 33), (int) Math.round(cy + dy * 22) + 4, 11, true,
					front ? ACCENT : INK, 0);
		}
		g.setStroke(old);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
	}

	// ---------------------------------------------------------------- orthographic views

	/** Front elevation: looking north from the south; screen x = world x, screen y = -y. Depth darkens. */
	static void drawFront(Graphics2D g, Scene sc, Rectangle r) {
		drawOrtho(g, sc, r, true);
	}

	/** Top plan: looking down, north up; screen x = world x, screen y = world z. Lower layers darken. */
	static void drawPlan(Graphics2D g, Scene sc, Rectangle r) {
		drawOrtho(g, sc, r, false);
	}

	private static void drawOrtho(Graphics2D g, Scene sc, Rectangle r, boolean front) {
		if (sc.count == 0) {
			drawEmpty(g, r);
			return;
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		int cols = sc.maxX - sc.minX + 1;
		int rows = front ? sc.maxY - sc.minY + 1 : sc.maxZ - sc.minZ + 1;
		int padL = 44, padR = front ? 34 : 14, padT = 8, padB = 22;
		double availW = r.width - padL - padR, availH = r.height - padT - padB;
		double cell = Math.max(MIN_CUBE, Math.min(MAX_CUBE, Math.min(availW / cols, availH / rows)));
		double ox = r.x + padL + (availW - cols * cell) / 2;
		double oy = r.y + padT + (availH - rows * cell) / 2;
		// Screen position of world coordinate (h, vert): h is x; vert is y (front, flipped) or z (plan).
		double top = front ? sc.maxY + 1 : sc.minZ;
		double sign = front ? -1 : 1;

		g.setColor(new Color(0, 0, 0, 18));
		g.fillRect((int) Math.round(ox), (int) Math.round(oy), (int) Math.round(cols * cell), (int) Math.round(rows * cell));

		int n = sc.count;
		int[] key = new int[n];
		int[] sel = new int[n];
		int m = 0;
		for (int i = 0; i < n; i++) {
			int x = sc.xs[i], y = sc.ys[i], z = sc.zs[i];
			BlockStyle s = sc.styles[sc.st[i]];
			// Hidden behind (front) or under (plan) an opaque cube or an identical translucent one.
			if (front ? sc.hides(s, x, y, z + 1) : sc.hides(s, x, y + 1, z)) {
				continue;
			}
			key[m] = front ? z - sc.minZ : y - sc.minY;
			sel[m++] = i;
		}
		int depthRange = front ? sc.maxZ - sc.minZ : sc.maxY - sc.minY;
		int[] order = countingSort(sel, key, m, 0, depthRange);
		boolean outlines = cell >= 6;
		int[] boxes = new int[9 * 6];
		int[] idx = new int[9];
		int axis = front ? 5 : 4;
		Color[] colorCache = new Color[sc.styles.length * (depthRange + 1)];
		for (int oi = 0; oi < m; oi++) {
			int i = order[oi];
			int x = sc.xs[i], y = sc.ys[i], z = sc.zs[i];
			int sid = sc.st[i];
			BlockStyle s = sc.styles[sid];
			int nb = boxesFor(sc, s, x, y, z, boxes);
			// Back to front within the cell: insertion sort by the far edge along the viewing axis.
			for (int b = 0; b < nb; b++) {
				int j = b;
				while (j > 0 && boxes[idx[j - 1] * 6 + axis] > boxes[b * 6 + axis]) {
					idx[j] = idx[j - 1];
					j--;
				}
				idx[j] = b;
			}
			int depth = front ? z - sc.minZ : y - sc.minY;
			int ck = sid * (depthRange + 1) + depth;
			Color fill = colorCache[ck];
			if (fill == null) {
				double f = depthRange == 0 ? 1 : 0.52 + 0.48 * depth / depthRange;
				// Translucent blocks a little more opaque than in the isometric view: flat views have no shading cues.
				fill = colorCache[ck] = shade(front ? s.ns : s.up, f, Math.max(s.alpha, 110));
			}
			for (int bi = 0; bi < nb; bi++) {
				int o = idx[bi] * 6;
				double h0 = x + boxes[o] / 16.0, h1 = x + boxes[o + 3] / 16.0;
				double v0 = front ? y + boxes[o + 1] / 16.0 : z + boxes[o + 2] / 16.0;
				double v1 = front ? y + boxes[o + 4] / 16.0 : z + boxes[o + 5] / 16.0;
				int sx0 = (int) Math.round(ox + (h0 - sc.minX) * cell);
				int sx1 = (int) Math.round(ox + (h1 - sc.minX) * cell);
				int sya = (int) Math.round(oy + (v0 - top) * sign * cell);
				int syb = (int) Math.round(oy + (v1 - top) * sign * cell);
				int sy0 = Math.min(sya, syb), sy1 = Math.max(sya, syb);
				if (sx1 <= sx0 || sy1 <= sy0) {
					// Keep thin parts (posts, panes seen edge-on) visible as at least one pixel.
					sx1 = Math.max(sx1, sx0 + 1);
					sy1 = Math.max(sy1, sy0 + 1);
				}
				g.setColor(fill);
				g.fillRect(sx0, sy0, sx1 - sx0, sy1 - sy0);
				if (outlines && sx1 - sx0 >= 4 && sy1 - sy0 >= 4) {
					g.setColor(s.alpha < 255 ? OUTLINE_SOFT : OUTLINE);
					g.drawRect(sx0, sy0, sx1 - sx0 - 1, sy1 - sy0 - 1);
				}
			}
		}

		// Axis labels.
		int left = (int) Math.round(ox), right = (int) Math.round(ox + cols * cell);
		int upper = (int) Math.round(oy), lower = (int) Math.round(oy + rows * cell);
		label(g, "x " + sc.minX, left, lower + 15, 11, false, MUTED, -1);
		label(g, sc.maxX + " x→", right, lower + 15, 11, false, MUTED, 1);
		if (front) {
			label(g, "y " + sc.maxY, left - 4, upper + 10, 11, false, MUTED, 1);
			label(g, "y " + sc.minY, left - 4, lower - 2, 11, false, MUTED, 1);
			if (sc.minY <= 0 && sc.maxY >= 0) {
				// Ground layer: y = 0 is the layer the player's feet are in; mark its bottom edge.
				int gy = (int) Math.round(oy + (0 - top) * sign * cell);
				Stroke old = g.getStroke();
				g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {6f, 4f}, 0f));
				g.setColor(new Color(0x2E7D32));
				g.drawLine(left, gy, right + 6, gy);
				g.setStroke(old);
				label(g, "y=0", right + 8, gy + 4, 10, true, new Color(0x2E7D32), -1);
			}
		} else {
			label(g, "z " + sc.minZ + " N", left - 4, upper + 10, 11, false, MUTED, 1);
			label(g, "z " + sc.maxZ + " S", left - 4, lower - 2, 11, false, ACCENT, 1);
			label(g, "FRONT", (left + right) / 2, lower + 15, 11, true, ACCENT, 0);
		}
	}

	// ---------------------------------------------------------------- sheet chrome

	private static Rectangle panel(Graphics2D g, Rectangle r, String title) {
		g.setClip(null);
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(PANEL_BG);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
		g.setColor(PANEL_BORDER);
		g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 10, 10);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
		label(g, title, r.x + 10, r.y + 17, 12, true, MUTED, -1);
		Rectangle content = new Rectangle(r.x + 4, r.y + 22, r.width - 8, r.height - 26);
		g.setClip(content);
		return content;
	}

	private static void drawHeader(Graphics2D g, Scene sc, String title, String subtitle, Rectangle r) {
		label(g, title == null || title.isBlank() ? "(untitled build)" : title, r.x, r.y + 26, 24, true, INK, -1);
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "%,d blocks", sc.blockCount()));
		if (sc.count > 0) {
			int w = sc.maxX - sc.minX + 1, h = sc.maxY - sc.minY + 1, d = sc.maxZ - sc.minZ + 1;
			sb.append(String.format(Locale.ROOT, "   ·   size %d × %d × %d (W×H×D = x×y×z)", w, h, d));
			sb.append(String.format(Locale.ROOT, "   ·   x %d..%d  y %d..%d  z %d..%d", sc.minX, sc.maxX, sc.minY,
					sc.maxY, sc.minZ, sc.maxZ));
		}
		sb.append(String.format(Locale.ROOT, "   ·   palette %d ids / %d block types", sc.styles.length,
				sc.pathCounts.size()));
		if (sc.airCount > 0) {
			sb.append(String.format(Locale.ROOT, "   ·   %,d air", sc.airCount));
		}
		label(g, sb.toString(), r.x, r.y + 48, 13, false, MUTED, -1);
		if (subtitle != null && !subtitle.isBlank()) {
			label(g, subtitle, r.x + r.width, r.y + 26, 12, false, MUTED, 1);
		}
		label(g, "y up · front faces +z (south) · +x = viewer's right (east)", r.x + r.width, r.y + 48, 12,
				false, MUTED, 1);
	}

	private static void drawLegend(Graphics2D g, Scene sc, Rectangle r) {
		List<Map.Entry<String, int[]>> entries = new ArrayList<>(sc.pathCounts.entrySet());
		entries.sort((p, q) -> Integer.compare(q.getValue()[0], p.getValue()[0]));
		int colW = 252, rowH = 17;
		int cols = Math.max(1, (r.width - 8) / colW);
		int rows = Math.max(1, (r.height - 6) / rowH);
		int capacity = cols * rows;
		boolean anyGuessed = false;
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		for (int k = 0; k < entries.size() && k < capacity; k++) {
			int cx = r.x + 8 + (k / rows) * colW;
			int cy = r.y + 4 + (k % rows) * rowH;
			if (k == capacity - 1 && entries.size() > capacity) {
				label(g, "+" + (entries.size() - capacity + 1) + " more", cx, cy + 12, 12, false, MUTED, -1);
				break;
			}
			Map.Entry<String, int[]> e = entries.get(k);
			BlockStyle s = sc.pathStyle.get(e.getKey());
			if (s.visible()) {
				g.setColor(shade(s.up, 1, 255));
				g.fillRect(cx, cy + 2, 13, 6);
				g.setColor(shade(s.ns, SHADE_LEFT, 255));
				g.fillRect(cx, cy + 8, 13, 6);
				if (s.alpha < 255) {
					g.setColor(new Color(255, 255, 255, 120));
					g.fillRect(cx + 7, cy + 2, 6, 12);
				}
			}
			g.setColor(new Color(0, 0, 0, 70));
			g.drawRect(cx, cy + 2, 13, 12);
			String text = e.getKey();
			if (s.guessed) {
				text += "  (?)";
				anyGuessed = true;
			}
			label(g, String.format(Locale.ROOT, "%,d", e.getValue()[0]), cx + 72, cy + 13, 12, true, INK, 1);
			label(g, ellipsize(g, text, colW - 86, 12), cx + 78, cy + 13, 12, false, INK, -1);
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
		if (anyGuessed) {
			label(g, "(?) colour guessed from the name: unknown, misspelt or modded id", r.x + r.width - 8,
					r.y + r.height - 6, 11, false, ACCENT, 1);
		}
		if (entries.isEmpty()) {
			label(g, "(empty)", r.x + 8, r.y + 16, 12, false, MUTED, -1);
		}
	}

	private static void drawEmpty(Graphics2D g, Rectangle r) {
		label(g, "(no visible blocks)", r.x + r.width / 2, r.y + r.height / 2, 14, false, MUTED, 0);
	}

	private static String ellipsize(Graphics2D g, String s, int maxW, int size) {
		try {
			FontMetrics fm = g.getFontMetrics(font(size, false));
			if (fm.stringWidth(s) <= maxW) {
				return s;
			}
			while (s.length() > 1 && fm.stringWidth(s + "…") > maxW) {
				s = s.substring(0, s.length() - 1);
			}
			return s + "…";
		} catch (RuntimeException | Error e) {
			return s;
		}
	}

	private static Font font(int size, boolean bold) {
		return new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size);
	}

	/**
	 * Draws text; align -1 = left, 0 = centred, 1 = right of x. Text is skipped (not fatal) on systems
	 * without usable fonts.
	 */
	private static void label(Graphics2D g, String s, int x, int y, int size, boolean bold, Color color, int align) {
		if (textBroken || s == null || s.isEmpty()) {
			return;
		}
		try {
			Object hint = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			Font f = font(size, bold);
			g.setFont(f);
			int w = align < 0 ? 0 : g.getFontMetrics(f).stringWidth(s);
			int tx = align < 0 ? x : align == 0 ? x - w / 2 : x - w;
			g.setColor(color);
			g.drawString(s, tx, y);
			if (hint != null) {
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, hint);
			}
		} catch (RuntimeException | Error e) {
			textBroken = true;
		}
	}
}
