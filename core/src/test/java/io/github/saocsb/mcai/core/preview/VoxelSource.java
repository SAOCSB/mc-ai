package io.github.saocsb.mcai.core.preview;

import io.github.saocsb.mcai.core.blueprint.Blueprint;
import io.github.saocsb.mcai.core.blueprint.Bounds;

/**
 * A read-only voxel set the renderer can draw. It decouples {@link BlueprintRenderer} from
 * {@link Blueprint} so rendering can be tested with plain maps ({@link VoxelMap}).
 */
public interface VoxelSource {
	/** Visits every entry, air included. Ids may carry block states and NBT. */
	void forEach(Blueprint.Visitor visitor);

	/** Bounding box of all entries (air included), or null when empty. */
	Bounds bounds();

	/** Adapter for a script-produced blueprint. */
	static VoxelSource of(Blueprint blueprint) {
		return new VoxelSource() {
			@Override
			public void forEach(Blueprint.Visitor visitor) {
				blueprint.forEach(visitor);
			}

			@Override
			public Bounds bounds() {
				return blueprint.bounds();
			}
		};
	}
}
