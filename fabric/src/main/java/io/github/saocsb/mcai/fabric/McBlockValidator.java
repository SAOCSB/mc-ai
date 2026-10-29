package io.github.saocsb.mcai.fabric;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.saocsb.mcai.core.agent.BlockValidator;
import io.github.saocsb.mcai.core.agent.InvalidBlock;
import io.github.saocsb.mcai.core.blueprint.BlockIds;
import io.github.saocsb.mcai.core.util.Suggest;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates block strings with the game's {@code /setblock} parser ({@link BlockStateParser}, NBT allowed) against
 * a feature-filtered block registry lookup, and caches successful parses for {@link BuildPlanner}.
 * Thread-safe: the agent calls {@link #validate} from worker threads (registry reads only).
 */
public final class McBlockValidator implements BlockValidator {
	private static final int MAX_CACHE = 8192;
	private static final int SUGGESTIONS = 3;

	private final HolderLookup<Block> blocks;
	private final Map<String, BlockStateParser.BlockResult> cache = new ConcurrentHashMap<>();
	private volatile List<String> allIds;

	/** @param blocks e.g. {@code level.holderLookup(Registries.BLOCK)} */
	public McBlockValidator(HolderLookup<Block> blocks) {
		this.blocks = blocks;
	}

	@Override
	public List<InvalidBlock> validate(Collection<String> normalizedBlockIds) {
		List<InvalidBlock> invalid = new ArrayList<>();
		for (String id : normalizedBlockIds) {
			Parsed parsed = parse(id);
			if (parsed.reason() != null) {
				invalid.add(new InvalidBlock(id, parsed.reason(), Suggest.closest(baseId(id), allIds(), SUGGESTIONS)));
			}
		}
		return invalid;
	}

	/** The (cached) parse result, or null if the id is invalid. */
	public BlockStateParser.BlockResult result(String id) {
		return parse(id).result();
	}

	/** Parses and caches {@code id}: either a result or the parser's reason for rejecting it. */
	private Parsed parse(String id) {
		BlockStateParser.BlockResult cached = cache.get(id);
		if (cached != null) {
			return new Parsed(cached, null);
		}
		StringReader reader = new StringReader(id);
		BlockStateParser.BlockResult result;
		try {
			result = BlockStateParser.parseForBlock(blocks, reader, true);
		} catch (CommandSyntaxException e) {
			return new Parsed(null, e.getRawMessage().getString());
		}
		if (reader.canRead()) {
			return new Parsed(null, "unexpected text after the block: '" + id.substring(reader.getCursor()) + "'");
		}
		if (cache.size() >= MAX_CACHE) {
			cache.clear();
		}
		cache.put(id, result);
		return new Parsed(result, null);
	}

	private List<String> allIds() {
		List<String> ids = allIds;
		if (ids == null) {
			ids = blocks.listElementIds().map(key -> key.identifier().toString()).toList();
			allIds = ids;
		}
		return ids;
	}

	private static String baseId(String id) {
		try {
			return BlockIds.baseId(id);
		} catch (RuntimeException e) {
			return id;
		}
	}

	private record Parsed(BlockStateParser.BlockResult result, String reason) {
	}
}
