package io.github.saocsb.mcai.core.blueprint;

/** Thrown when a build script exceeds the configured block-count or size limits. */
public class BlueprintLimitException extends RuntimeException {
	public BlueprintLimitException(String message) {
		super(message);
	}
}
