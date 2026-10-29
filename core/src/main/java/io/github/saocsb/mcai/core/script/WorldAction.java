package io.github.saocsb.mcai.core.script;

/**
 * A non-block action requested by a build script; executed by the Minecraft adapter after the
 * blocks are placed, subject to the server's command whitelist. Coordinates are local.
 */
public sealed interface WorldAction {
	/** Spawns an entity at a local block position (adapter uses the block centre). nbt may be null. */
	record Summon(String entityId, int x, int y, int z, String nbt) implements WorldAction {
	}

	/** Gives items to the requesting player. */
	record Give(String itemId, int count) implements WorldAction {
	}

	/** value is one of day, noon, night, midnight, or a tick count 0..24000 as a string. */
	record SetTime(String value) implements WorldAction {
	}

	/** value is one of clear, rain, thunder. */
	record SetWeather(String value) implements WorldAction {
	}

	/** Gives a status effect to the requesting player. */
	record Effect(String effectId, int seconds, int amplifier) implements WorldAction {
	}

	/** A raw vanilla command without the leading slash, run as the requesting player. */
	record Command(String command) implements WorldAction {
	}

	/** Pauses the action list: the actions after it run {@code seconds} later (e.g. a grace period before mobs spawn). */
	record Wait(double seconds) implements WorldAction {
	}
}
