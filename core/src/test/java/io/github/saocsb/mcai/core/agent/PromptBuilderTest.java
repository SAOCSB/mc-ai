package io.github.saocsb.mcai.core.agent;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {
	private static final String ENABLED = "World-action functions are enabled. Commands allowed on this server: "
			+ "summon, give, time. summon/give/time/weather/effect only work if their command is in that list.";
	private static final String DISABLED = "World-action functions (summon, give, time, weather, effect, command) are "
			+ "disabled on this server; do not use them.";

	@Test
	void resourceHasEveryPlaceholder() throws Exception {
		try (InputStream in = PromptBuilder.class.getResourceAsStream(PromptBuilder.RESOURCE)) {
			assertNotNull(in);
			String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			for (String p : List.of("{{AI_NAME}}", "{{MAX_BLOCKS}}", "{{COMMANDS}}", "{{EXTRA}}")) {
				assertTrue(template.contains(p), p);
			}
		}
	}

	@Test
	void systemPromptSubstitutesEverything() {
		String prompt = PromptBuilder.systemPrompt("Builder Bot", 300000, true, List.of("summon", "give", "time"),
				"Always build with cherry wood.");
		assertFalse(prompt.contains("{{"), prompt);
		assertTrue(prompt.contains("Builder Bot"));
		assertTrue(prompt.contains("300000"));
		assertFalse(prompt.contains("300,000"));
		assertTrue(prompt.contains(ENABLED));
		assertTrue(prompt.contains("Always build with cherry wood."));
		assertEquals(prompt, PromptBuilder.systemPrompt("Builder Bot", 300000, true, List.of("summon", "give", "time"),
				"Always build with cherry wood."));
	}

	@Test
	void systemPromptWithoutExtraHasNoLeftovers() {
		for (String extra : Arrays.asList("", "   \n ", null)) {
			String prompt = PromptBuilder.systemPrompt("AI", 1000, false, List.of(), extra);
			assertFalse(prompt.contains("{{"), prompt);
			assertFalse(prompt.contains("\n\n\n"), prompt);
			assertTrue(prompt.contains(DISABLED));
			assertEquals(prompt.strip(), prompt);
		}
	}

	@Test
	void commandsSentence() {
		assertEquals(ENABLED, PromptBuilder.commandsSentence(true, List.of("summon", " give ", "", "time", "give")));
		assertEquals(DISABLED, PromptBuilder.commandsSentence(false, List.of("summon")));
		assertEquals(DISABLED, PromptBuilder.commandsSentence(true, List.of()));
		assertEquals(DISABLED, PromptBuilder.commandsSentence(true, List.of(" ")));
		assertEquals(DISABLED, PromptBuilder.commandsSentence(true, null));
	}

	@Test
	void blankExtraRemovesASectionThatOnlyHeldIt() {
		String template = "Intro {{AI_NAME}}.\n\n## Server rules\n\n{{EXTRA}}\n\n## Output\nUse js.\n";
		assertEquals("Intro Bob.\n\n## Output\nUse js.", PromptBuilder.render(template, "Bob", 1, "C", ""));
		assertEquals("Intro Bob.\n\n## Server rules\n\nNo lava.\n\n## Output\nUse js.",
				PromptBuilder.render(template, "Bob", 1, "C", "  No lava.\r\n"));
	}

	@Test
	void blankExtraAtTheEnd() {
		String template = "Intro.\n\n{{COMMANDS}}\n\n# Extra\n{{EXTRA}}\n";
		assertEquals("Intro.\n\ncmds", PromptBuilder.render(template, "A", 1, "cmds", null));
		assertEquals("Intro.\n\nB", PromptBuilder.render("Intro.\n\n{{EXTRA}}\n\n\nB", "A", 1, "c", " "));
		assertEquals("Intro.", PromptBuilder.render("Intro.\n\n  {{EXTRA}}  \n", "A", 1, "c", ""));
	}

	@Test
	void blankExtraKeepsHeadingsThatIntroduceOtherText() {
		String template = "## Rules\nBe nice.\n\n{{EXTRA}}\n\nEnd.";
		assertEquals("## Rules\nBe nice.\n\nEnd.", PromptBuilder.render(template, "A", 1, "c", ""));
		assertEquals("Hello  there", PromptBuilder.render("Hello {{EXTRA}} there", "A", 1, "c", ""));
	}

	@Test
	void substitutedValuesAreLiteral() {
		assertEquals("$1 \\ {{EXTRA}} 12 {{UNKNOWN}}",
				PromptBuilder.render("{{AI_NAME}} {{MAX_BLOCKS}} {{UNKNOWN}}", "$1 \\ {{EXTRA}}", 12, "c", "x"));
	}

	@Test
	void userTurn() {
		GameContext context = AiAgentTest.context();
		assertEquals(context.render() + "\n\nSteve: build a tower", PromptBuilder.userTurn(context, "  build a tower\n"));
		assertEquals("Steve: hi", PromptBuilder.speakerLine("Steve", " hi "));
	}

	@Test
	void repairTurnBasics() {
		String error = "ReferenceError: \"foo\" is not defined. (line 3: foo();)";
		String turn = PromptBuilder.repairTurn(error, 2, 3);
		assertTrue(turn.contains("Attempt 2 of 3"), turn);
		assertTrue(turn.contains("```text\n" + error + "\n```"), turn);
		assertTrue(turn.contains("ONE complete"), turn);
		assertTrue(turn.contains("```js"), turn);
		assertTrue(turn.contains("no chat text"), turn);
		assertFalse(turn.contains("much shorter"));
		assertFalse(turn.contains("26.x"));
	}

	@Test
	void repairTurnForTruncation() {
		String turn = PromptBuilder.repairTurn(AiAgent.TRUNCATED_ERROR, 3, 3);
		assertTrue(turn.contains("Attempt 3 of 3"));
		assertTrue(turn.contains(AiAgent.TRUNCATED_ERROR));
		assertTrue(turn.contains("much shorter"));
		assertTrue(turn.contains("loops"));
	}

	@Test
	void repairTurnForUnknownBlocks() {
		String turn = PromptBuilder.repairTurn(AiAgent.invalidBlocksMessage(List.of(
				new InvalidBlock("minecraft:stone_brick", "unknown block", List.of("minecraft:stone_bricks")))), 2, 2);
		assertTrue(turn.contains("- minecraft:stone_brick: unknown block (did you mean: minecraft:stone_bricks?)"));
		assertTrue(turn.contains("valid Minecraft Java Edition 26.x block ids"));
	}

	@Test
	void repairTurnForEmptyResult() {
		assertTrue(PromptBuilder.repairTurn(AiAgent.EMPTY_RESULT_ERROR, 2, 3).contains("defining functions without calling them"));
	}

	@Test
	void repairTurnUsesALongerFenceWhenTheErrorHasBackticks() {
		String error = "SyntaxError: unexpected token (line 1: ```js)";
		String turn = PromptBuilder.repairTurn(error, 2, 3);
		assertTrue(turn.contains("````text\n" + error + "\n````"), turn);
	}

	@Test
	void repairTurnWithBlankError() {
		assertTrue(PromptBuilder.repairTurn(null, 2, 3).contains("(no error message)"));
		assertTrue(PromptBuilder.repairTurn("a\r\nb", 2, 3).contains("```text\na\nb\n```"));
	}
}
