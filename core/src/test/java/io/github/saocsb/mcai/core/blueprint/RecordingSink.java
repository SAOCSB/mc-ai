package io.github.saocsb.mcai.core.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test sink that records every write. */
final class RecordingSink implements BlockSink {
	record Pos(int x, int y, int z) {
		Pos offset(int dx, int dy, int dz) {
			return new Pos(x + dx, y + dy, z + dz);
		}
	}

	final List<Pos> writes = new ArrayList<>();
	final Map<Pos, String> blocks = new LinkedHashMap<>();

	@Override
	public void set(int x, int y, int z, String blockId) {
		Pos p = new Pos(x, y, z);
		writes.add(p);
		blocks.put(p, blockId);
	}

	Set<Pos> positions() {
		return new LinkedHashSet<>(blocks.keySet());
	}

	int writeCount() {
		return writes.size();
	}

	boolean hasDuplicateWrites() {
		return writes.size() != blocks.size();
	}

	/** True if the y coordinate never decreases over the write sequence. */
	boolean writtenBottomUp() {
		for (int i = 1; i < writes.size(); i++) {
			if (writes.get(i).y() < writes.get(i - 1).y()) {
				return false;
			}
		}
		return true;
	}
}
