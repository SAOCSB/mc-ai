package io.github.saocsb.mcai.core.config;

import java.io.IOException;
import java.nio.file.Path;

/** Loads and saves {@link ModConfig} as pretty-printed JSON (UTF-8) with Gson. */
public final class ConfigIO {
	private ConfigIO() {
	}

	/**
	 * Loads the config, creating it with defaults (all presets) if missing. Missing presets are added,
	 * null sections/collections replaced with defaults, numbers clamped to sane ranges, and an unknown
	 * active provider reset to the default; the normalized result is written back so new options appear.
	 *
	 * @throws IOException if the file cannot be read/written or is not valid JSON (the file is then left untouched)
	 */
	public static ModConfig load(Path file) throws IOException {
		throw new UnsupportedOperationException("TODO");
	}

	/** Writes atomically (temp file + move). */
	public static void save(Path file, ModConfig config) throws IOException {
		throw new UnsupportedOperationException("TODO");
	}

	/** A default config with all presets. */
	public static ModConfig defaults() {
		throw new UnsupportedOperationException("TODO");
	}
}
