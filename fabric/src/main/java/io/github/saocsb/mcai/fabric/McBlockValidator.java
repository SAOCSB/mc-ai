package io.github.saocsb.mcai.fabric;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.saocsb.mcai.core.agent.BlockValidator;
import io.github.saocsb.mcai.core.agent.InvalidBlock;
import io.github.saocsb.mcai.core.blueprint.BlockIds;
import io.github.saocsb.mcai.core.util.Suggest;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Validates block strings with the game's {@code /setblock} parser ({@link BlockStateParser}, NBT allowed) against
 * a feature-filtered block registry lookup, and caches successful parses for {@link BuildPlanner}.
 *
 * <p>Server policy on top of the registry:
 * <ul>
 * <li>Game-master blocks (command, structure, jigsaw and test blocks) and the configured {@code build.deniedBlocks}
 *     are rejected as "not allowed on this server": {@link #validate} reports them and {@link #result} returns null.</li>
 * <li>Block-entity data is kept only for plain storage and decoration blocks ({@link #NBT_ALLOWED}); for everything
 *     else (signs, lecterns, spawners, ...) the NBT is dropped, and so is data that mentions commands, click events,
 *     dialogs or entity/block-entity data anywhere (e.g. a book or spawn egg in a chest), so scripts cannot plant
 *     click commands or op-only data.</li>
 * </ul>
 * Thread-safe: the agent calls {@link #validate} from worker threads (registry reads only).
 */
public final class McBlockValidator implements BlockValidator {
	/** Reason given for blocks the server refuses. */
	public static final String NOT_ALLOWED = "not allowed on this server";
	private static final int MAX_CACHE = 8192;
	private static final int SUGGESTIONS = 3;
	/** Unknown ids per call that get suggestions (each one scans every block id). */
	static final int MAX_SUGGESTED = 32;
	/** Block entity types whose NBT scripts may set: containers and decorations without commands or op-only data. */
	static final List<BlockEntityType<?>> NBT_ALLOWED = List.of(BlockEntityTypes.CHEST, BlockEntityTypes.TRAPPED_CHEST,
			BlockEntityTypes.BARREL, BlockEntityTypes.SHULKER_BOX, BlockEntityTypes.HOPPER, BlockEntityTypes.DISPENSER,
			BlockEntityTypes.DROPPER, BlockEntityTypes.FURNACE, BlockEntityTypes.BLAST_FURNACE, BlockEntityTypes.SMOKER,
			BlockEntityTypes.BREWING_STAND, BlockEntityTypes.BANNER, BlockEntityTypes.DECORATED_POT,
			BlockEntityTypes.CHISELED_BOOKSHELF);
	/** Never kept in block-entity data, at any depth (lower case; matched case-insensitively). */
	static final List<String> NBT_FORBIDDEN_WORDS = List.of("click_event", "clickevent", "run_command", "show_dialog",
			"entity_data", "blockentitytag", "entitytag", "command_block", "spawndata", "spawnpotentials");
	/** A {@code command} key (command blocks, minecarts, click events). */
	private static final Pattern COMMAND_KEY = Pattern.compile("\\bcommand[\"']?\\s*:", Pattern.CASE_INSENSITIVE);

	private final HolderLookup<Block> blocks;
	private final Map<String, BlockStateParser.BlockResult> cache = new ConcurrentHashMap<>();
	private volatile List<String> allIds;
	private volatile Set<String> denied = Set.of();

	/** @param blocks e.g. {@code level.holderLookup(Registries.BLOCK)} */
	public McBlockValidator(HolderLookup<Block> blocks) {
		this.blocks = blocks;
	}

	/** Sets the refused block ids ({@code build.deniedBlocks}; {@code minecraft:} optional, case-insensitive). */
	public void setDeniedBlocks(Collection<String> ids) {
		Set<String> set = new HashSet<>();
		if (ids != null) {
			for (String id : ids) {
				if (id != null && !id.isBlank()) {
					String value = id.trim().toLowerCase(Locale.ROOT);
					set.add(value.indexOf(':') < 0 ? "minecraft:" + value : value);
				}
			}
		}
		denied = Set.copyOf(set);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Close valid ids are suggested for the first {@value #MAX_SUGGESTED} unknown ids only (a palette can hold
	 * thousands of ids); later ones get an empty suggestion list.
	 *
	 * @throws CancellationException if the calling thread is interrupted (the request was cancelled)
	 */
	@Override
	public List<InvalidBlock> validate(Collection<String> normalizedBlockIds) {
		List<InvalidBlock> invalid = new ArrayList<>();
		int suggested = 0;
		for (String id : normalizedBlockIds) {
			if (Thread.currentThread().isInterrupted()) {
				throw new CancellationException("block validation interrupted");
			}
			Parsed parsed = parse(id);
			if (parsed.reason() != null) {
				List<String> suggestions = suggested++ < MAX_SUGGESTED
						? Suggest.closest(baseId(id), allIds(), SUGGESTIONS) : List.of();
				invalid.add(new InvalidBlock(id, parsed.reason(), suggestions));
			} else if (isDenied(parsed.result().blockState())) {
				invalid.add(new InvalidBlock(id, NOT_ALLOWED, List.of()));
			}
		}
		return invalid;
	}

	/** The (cached) parse result with disallowed NBT removed, or null if the id is invalid or refused. */
	public BlockStateParser.BlockResult result(String id) {
		BlockStateParser.BlockResult result = parse(id).result();
		return result == null || isDenied(result.blockState()) ? null : result;
	}

	/** Whether the server refuses this block: a game-master block or one of {@code build.deniedBlocks}. */
	public boolean isDenied(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof GameMasterBlock) {
			return true;
		}
		Set<String> ids = denied;
		if (ids.isEmpty()) {
			return false;
		}
		Identifier key = BuiltInRegistries.BLOCK.getKey(block);
		return key != null && ids.contains(key.toString());
	}

	/** Whether scripts may set block-entity data for this block (its block entity type is in {@link #NBT_ALLOWED}). */
	public static boolean keepsNbt(BlockState state) {
		for (BlockEntityType<?> type : NBT_ALLOWED) {
			if (type.isValid(state)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether block-entity data (as SNBT) carries anything a script must not plant: click events, commands, dialogs,
	 * entity or block-entity data (spawn eggs, command block items), spawner data. Matched as text anywhere,
	 * so nested items (shulker boxes in chests) are covered too.
	 */
	static boolean hasForbiddenData(String snbt) {
		String lower = snbt.toLowerCase(Locale.ROOT);
		for (String word : NBT_FORBIDDEN_WORDS) {
			if (lower.contains(word)) {
				return true;
			}
		}
		return COMMAND_KEY.matcher(snbt).find();
	}

	/** Parses and caches {@code id}: either a (sanitized) result or the parser's reason for rejecting it. */
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
		if (result.nbt() != null && (!keepsNbt(result.blockState()) || hasForbiddenData(result.nbt().toString()))) {
			result = new BlockStateParser.BlockResult(result.blockState(), result.properties(), null);
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
