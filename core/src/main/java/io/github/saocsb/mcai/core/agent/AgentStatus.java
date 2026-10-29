package io.github.saocsb.mcai.core.agent;

/** Progress stages reported to the player while a request is processed. */
public enum AgentStatus {
	/** Waiting for / receiving the model's reply. args: [charsReceived (Integer)] */
	THINKING,
	/** The reply stream has entered a code block. args: [linesOfCode (Integer)] */
	WRITING_SCRIPT,
	/** Running the build script. args: [] */
	RUNNING_SCRIPT,
	/** The script failed; asking the model to fix it. args: [attempt (Integer), maxAttempts (Integer), shortError (String)] */
	REPAIRING
}
