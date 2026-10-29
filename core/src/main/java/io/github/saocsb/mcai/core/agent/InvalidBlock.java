package io.github.saocsb.mcai.core.agent;

import java.util.List;

/** A palette entry the game rejected, with the parser's reason and close valid ids. */
public record InvalidBlock(String blockId, String reason, List<String> suggestions) {
}
