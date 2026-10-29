package io.github.saocsb.mcai.core.agent;

import java.util.Collection;
import java.util.List;

/** Checks block strings against the game's registry (implemented by the Minecraft adapter). */
@FunctionalInterface
public interface BlockValidator {
	BlockValidator ACCEPT_ALL = ids -> List.of();

	/** Returns the invalid entries (empty when all are valid). May be called from a worker thread. */
	List<InvalidBlock> validate(Collection<String> normalizedBlockIds);
}
