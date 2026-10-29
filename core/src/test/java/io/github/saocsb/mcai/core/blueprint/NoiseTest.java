package io.github.saocsb.mcai.core.blueprint;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoiseTest {
	@Test
	void staysWithinRangeAndUsesIt() {
		Noise noise = new Noise(42);
		Random random = new Random(1);
		double min2 = 1, max2 = -1, min3 = 1, max3 = -1, sum2 = 0, sum3 = 0;
		int samples = 200_000;
		for (int i = 0; i < samples; i++) {
			double x = (random.nextDouble() - 0.5) * 2000, y = (random.nextDouble() - 0.5) * 2000, z = (random.nextDouble() - 0.5) * 2000;
			double a = noise.noise2(x, z), b = noise.noise3(x, y, z);
			assertTrue(a >= -1 && a <= 1, "noise2 out of range: " + a);
			assertTrue(b >= -1 && b <= 1, "noise3 out of range: " + b);
			min2 = Math.min(min2, a);
			max2 = Math.max(max2, a);
			min3 = Math.min(min3, b);
			max3 = Math.max(max3, b);
			sum2 += a;
			sum3 += b;
		}
		assertTrue(max2 > 0.6 && min2 < -0.6, "noise2 range " + min2 + ".." + max2);
		assertTrue(max3 > 0.6 && min3 < -0.6, "noise3 range " + min3 + ".." + max3);
		assertTrue(Math.abs(sum2 / samples) < 0.05, "noise2 mean " + sum2 / samples);
		assertTrue(Math.abs(sum3 / samples) < 0.05, "noise3 mean " + sum3 / samples);
	}

	@Test
	void sameSeedSameValues() {
		Noise a = new Noise(-7), b = new Noise(-7);
		Random random = new Random(2);
		for (int i = 0; i < 10_000; i++) {
			double x = random.nextGaussian() * 100, y = random.nextGaussian() * 100, z = random.nextGaussian() * 100;
			assertEquals(a.noise2(x, z), b.noise2(x, z));
			assertEquals(a.noise3(x, y, z), b.noise3(x, y, z));
		}
	}

	@Test
	void differentSeedsDiffer() {
		Noise a = new Noise(1), b = new Noise(2);
		Random random = new Random(3);
		int differ2 = 0, differ3 = 0;
		int samples = 10_000;
		for (int i = 0; i < samples; i++) {
			double x = random.nextDouble() * 500, y = random.nextDouble() * 500, z = random.nextDouble() * 500;
			if (Math.abs(a.noise2(x, z) - b.noise2(x, z)) > 1e-6) {
				differ2++;
			}
			if (Math.abs(a.noise3(x, y, z) - b.noise3(x, y, z)) > 1e-6) {
				differ3++;
			}
		}
		assertTrue(differ2 > samples * 0.95, differ2 + " of " + samples);
		assertTrue(differ3 > samples * 0.95, differ3 + " of " + samples);
	}

	@Test
	void isContinuous() {
		Noise noise = new Noise(123);
		Random random = new Random(4);
		double step = 1e-3;
		for (int i = 0; i < 20_000; i++) {
			double x = (random.nextDouble() - 0.5) * 200, y = (random.nextDouble() - 0.5) * 200, z = (random.nextDouble() - 0.5) * 200;
			double dx = (random.nextDouble() - 0.5) * step, dy = (random.nextDouble() - 0.5) * step, dz = (random.nextDouble() - 0.5) * step;
			assertTrue(Math.abs(noise.noise2(x, z) - noise.noise2(x + dx, z + dz)) < 0.01);
			assertTrue(Math.abs(noise.noise3(x, y, z) - noise.noise3(x + dx, y + dy, z + dz)) < 0.01);
		}
		// Across lattice cell borders too.
		for (int k = -50; k <= 50; k++) {
			assertTrue(Math.abs(noise.noise2(k - 1e-9, 0.3) - noise.noise2(k + 1e-9, 0.3)) < 1e-6);
			assertTrue(Math.abs(noise.noise3(0.7, k - 1e-9, 0.3) - noise.noise3(0.7, k + 1e-9, 0.3)) < 1e-6);
		}
	}

	@Test
	void isSmoothAtUnitScale() {
		// Neighbouring blocks sampled at x / 16 should change gently, as the API docs suggest.
		Noise noise = new Noise(9);
		for (int x = -200; x < 200; x++) {
			assertTrue(Math.abs(noise.noise2(x / 16.0, 3.0) - noise.noise2((x + 1) / 16.0, 3.0)) < 0.25);
		}
	}

	@Test
	void nonFiniteInputGivesZero() {
		Noise noise = new Noise(5);
		assertEquals(0, noise.noise2(Double.NaN, 1));
		assertEquals(0, noise.noise3(1, Double.POSITIVE_INFINITY, 1));
		double far = noise.noise3(1e12, -1e15, 3.5);
		assertTrue(far >= -1 && far <= 1);
	}
}
