package io.github.saocsb.mcai.core.blueprint;

/**
 * Seeded, deterministic gradient noise (Perlin or simplex), for organic terrain and decoration.
 * Output is in [-1, 1], continuous, and 0 at integer lattice points is NOT required.
 * Same seed + same input => same output.
 */
public final class Noise {
	public Noise(long seed) {
		throw new UnsupportedOperationException("TODO");
	}

	public double noise2(double x, double z) {
		throw new UnsupportedOperationException("TODO");
	}

	public double noise3(double x, double y, double z) {
		throw new UnsupportedOperationException("TODO");
	}
}
