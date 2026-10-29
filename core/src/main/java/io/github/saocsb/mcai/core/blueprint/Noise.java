package io.github.saocsb.mcai.core.blueprint;

/**
 * Seeded, deterministic gradient noise (Perlin or simplex), for organic terrain and decoration.
 * Output is in [-1, 1], continuous, and 0 at integer lattice points is NOT required.
 * Same seed + same input => same output.
 *
 * <p>Implementation: Ken Perlin's improved noise with a permutation table shuffled by a SplitMix64
 * generator (so results do not depend on the JDK), 8 unit gradients in 2D and the 12 cube-edge
 * gradients in 3D, scaled to use most of the range and clamped to [-1, 1]. Features are about one
 * unit wide. Non-finite inputs return 0. Instances are immutable and thread-safe.
 */
public final class Noise {
	private static final double DIAGONAL = Math.sqrt(0.5);
	/** 1 / max of 2D Perlin noise with unit gradients (sqrt(2)/2). */
	private static final double SCALE_2D = Math.sqrt(2);
	private static final double SCALE_3D = 1.0;

	private final int[] perm = new int[512];

	public Noise(long seed) {
		int[] p = new int[256];
		for (int i = 0; i < 256; i++) {
			p[i] = i;
		}
		long state = seed;
		for (int i = 255; i > 0; i--) {
			state += 0x9E3779B97F4A7C15L;
			int j = (int) Long.remainderUnsigned(mix(state), i + 1);
			int t = p[i];
			p[i] = p[j];
			p[j] = t;
		}
		for (int i = 0; i < 512; i++) {
			perm[i] = p[i & 255];
		}
	}

	public double noise2(double x, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(z)) {
			return 0;
		}
		double fx = Math.floor(x), fz = Math.floor(z);
		int xi = (int) ((long) fx & 255), zi = (int) ((long) fz & 255);
		x -= fx;
		z -= fz;
		double u = fade(x), v = fade(z);
		int a = perm[xi] + zi, b = perm[xi + 1] + zi;
		double value = lerp(v,
				lerp(u, grad2(perm[a], x, z), grad2(perm[b], x - 1, z)),
				lerp(u, grad2(perm[a + 1], x, z - 1), grad2(perm[b + 1], x - 1, z - 1)));
		return clamp(value * SCALE_2D);
	}

	public double noise3(double x, double y, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
			return 0;
		}
		double fx = Math.floor(x), fy = Math.floor(y), fz = Math.floor(z);
		int xi = (int) ((long) fx & 255), yi = (int) ((long) fy & 255), zi = (int) ((long) fz & 255);
		x -= fx;
		y -= fy;
		z -= fz;
		double u = fade(x), v = fade(y), w = fade(z);
		int a = perm[xi] + yi, aa = perm[a] + zi, ab = perm[a + 1] + zi;
		int b = perm[xi + 1] + yi, ba = perm[b] + zi, bb = perm[b + 1] + zi;
		double value = lerp(w,
				lerp(v,
						lerp(u, grad3(perm[aa], x, y, z), grad3(perm[ba], x - 1, y, z)),
						lerp(u, grad3(perm[ab], x, y - 1, z), grad3(perm[bb], x - 1, y - 1, z))),
				lerp(v,
						lerp(u, grad3(perm[aa + 1], x, y, z - 1), grad3(perm[ba + 1], x - 1, y, z - 1)),
						lerp(u, grad3(perm[ab + 1], x, y - 1, z - 1), grad3(perm[bb + 1], x - 1, y - 1, z - 1))));
		return clamp(value * SCALE_3D);
	}

	private static double grad2(int hash, double x, double z) {
		return switch (hash & 7) {
			case 0 -> x;
			case 1 -> -x;
			case 2 -> z;
			case 3 -> -z;
			case 4 -> DIAGONAL * (x + z);
			case 5 -> DIAGONAL * (z - x);
			case 6 -> DIAGONAL * (x - z);
			default -> -DIAGONAL * (x + z);
		};
	}

	private static double grad3(int hash, double x, double y, double z) {
		int h = hash & 15;
		double u = h < 8 ? x : y;
		double v = h < 4 ? y : h == 12 || h == 14 ? x : z;
		return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
	}

	private static double fade(double t) {
		return t * t * t * (t * (t * 6 - 15) + 10);
	}

	private static double lerp(double t, double a, double b) {
		return a + t * (b - a);
	}

	private static double clamp(double v) {
		return v < -1 ? -1 : v > 1 ? 1 : v;
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
