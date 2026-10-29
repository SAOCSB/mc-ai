package io.github.saocsb.mcai.core.agent;

import java.util.List;

/**
 * Builds prompts. The system prompt is the resource {@code /mcai/prompt/system.md} with placeholders
 * {@code {{AI_NAME}}}, {@code {{MAX_BLOCKS}}}, {@code {{COMMANDS}}} (a sentence describing whether and which
 * commands are allowed) and {@code {{EXTRA}}} (extra instructions or empty) substituted. It contains no
 * per-request data so providers can cache it.
 */
public final class PromptBuilder {
	private PromptBuilder() {
	}

	/**
	 * @param allowedCommands whitelisted command roots; empty list or {@code commandsEnabled == false} means
	 *                        the prompt tells the model world-action functions are disabled
	 */
	public static String systemPrompt(String aiName, int maxBlocks, boolean commandsEnabled, List<String> allowedCommands,
			String extraInstructions) {
		throw new UnsupportedOperationException("TODO");
	}

	/** The user turn: {@code context.render()}, a blank line, then {@code "<playerName>: <message>"}. */
	public static String userTurn(GameContext context, String message) {
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * Feedback after a failed script, asking for a complete corrected script in one js block and no other
	 * chat text. States the attempt number ("attempt 2 of 3"), the error, and (for truncation) asks for a
	 * shorter script using loops/primitives.
	 */
	public static String repairTurn(String error, int attempt, int maxAttempts) {
		throw new UnsupportedOperationException("TODO");
	}
}
