package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.blueprint.BlockIds;
import io.github.saocsb.mcai.core.blueprint.Blueprint;
import io.github.saocsb.mcai.core.llm.ChatMessage;
import io.github.saocsb.mcai.core.llm.LlmException;
import io.github.saocsb.mcai.core.llm.LlmRequest;
import io.github.saocsb.mcai.core.script.ScriptLimits;
import io.github.saocsb.mcai.core.script.WorldAction;
import io.github.saocsb.mcai.core.util.Suggest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAgentTest {
	private static final String SYSTEM = "SYSTEM PROMPT";
	private static final long SEED = 42L;
	private static final long MS = 1_000_000L;
	private static final String GOOD_REPLY = "Sure, here is a tower!\n```js\nsetName('Tower');\n"
			+ "cylinder(0, 0, 0, 3, 10, 'stone_bricks');\n```\nEnjoy!";
	private static final String GOOD_SCRIPT = "setName('Tower');\ncylinder(0, 0, 0, 3, 10, 'stone_bricks');";
	private static final String FIXED_REPLY = "```js\nfill(0, 0, 0, 4, 4, 4, 'oak_planks');\n```";
	private static final String FIXED_SCRIPT = "fill(0, 0, 0, 4, 4, 4, 'oak_planks');";

	private final FakeLlmClient llm = new FakeLlmClient();
	private final FakeScriptRunner runner = new FakeScriptRunner();
	private final RecordingListener listener = new RecordingListener();
	private final Conversation conversation = new Conversation();
	private final AtomicLong clock = new AtomicLong();
	private ExecutorService pool;

	@AfterEach
	void shutdownPool() {
		if (pool != null) {
			pool.shutdownNow();
		}
	}

	static GameContext context() {
		return new GameContext("Steve", "minecraft:overworld", "minecraft:plains", 120, 64, -30, "north", 6000, "clear",
				"survival", "minecraft:diamond_sword", 64, -64, 319, 300000, List.of("Alex"), "normal");
	}

	private AiAgent agent(Executor executor) {
		return new AiAgent(llm, runner, executor, runner.inspector(), clock::get);
	}

	private AiAgent agent() {
		return agent(Runnable::run);
	}

	private AgentRequest request(String message, int maxRepairs, BlockValidator validator, int maxHistory) {
		return new AgentRequest(conversation, SYSTEM, context(), message, ScriptLimits.defaults(), maxHistory, maxRepairs,
				validator, listener, SEED);
	}

	private AgentRequest request(String message, int maxRepairs, BlockValidator validator) {
		return request(message, maxRepairs, validator, 12);
	}

	private AgentRequest request(String message, int maxRepairs) {
		return request(message, maxRepairs, BlockValidator.ACCEPT_ALL);
	}

	private static AgentOutcome await(AgentTask task) throws Exception {
		return task.future().get(5, TimeUnit.SECONDS);
	}

	private List<String> events() {
		return listener.events.stream().map(Object::toString).toList();
	}

	private List<ChatMessage> messagesOfCall(int index) {
		return llm.calls.get(index).request.messages();
	}

	@Test
	void pureChatReturnsChatOnlyAndRecordsTheExchange() throws Exception {
		llm.reply("Hi Steve! **How** can I help?");

		AgentOutcome outcome = await(agent().handle(request("hello", 2)));

		assertEquals(new AgentOutcome("Hi Steve! How can I help?", null, null, 1), outcome);
		assertEquals(List.of("THINKING[0]", "chat(Hi Steve! How can I help?)"), events());
		assertTrue(runner.sources.isEmpty());
		assertEquals(List.of(ChatMessage.user("Steve: hello"), ChatMessage.assistant("Hi Steve! **How** can I help?")),
				conversation.messages());
	}

	@Test
	void successfulScriptOnFirstTry() throws Exception {
		llm.reply(GOOD_REPLY);
		runner.ok("minecraft:stone_bricks", "minecraft:air");
		List<List<String>> validated = new CopyOnWriteArrayList<>();
		BlockValidator validator = ids -> {
			validated.add(List.copyOf(ids));
			return List.of();
		};

		AgentOutcome outcome = await(agent().handle(request("  build a tower ", 2, validator)));

		assertEquals("Sure, here is a tower!\nEnjoy!", outcome.chatText());
		assertNotNull(outcome.result());
		assertEquals("Tower", outcome.result().name());
		assertNull(outcome.failure());
		assertEquals(1, outcome.attempts());
		assertEquals(List.of(GOOD_SCRIPT), runner.sources);
		assertEquals(List.of(SEED), runner.seeds);
		assertEquals(List.of(ScriptLimits.defaults()), runner.limits);
		assertEquals(List.of(List.of("minecraft:stone_bricks", "minecraft:air")), validated);
		assertEquals(List.of("THINKING[0]", "chat(Sure, here is a tower!\nEnjoy!)", "RUNNING_SCRIPT[]"), events());
		assertEquals(List.of(ChatMessage.user("Steve: build a tower"), ChatMessage.assistant(GOOD_REPLY)),
				conversation.messages());

		LlmRequest sent = llm.calls.get(0).request;
		assertEquals(SYSTEM, sent.systemPrompt());
		assertEquals(List.of(ChatMessage.user(PromptBuilder.userTurn(context(), "  build a tower "))), sent.messages());
		assertTrue(sent.messages().get(0).content().startsWith("[game context]\n"));
		assertTrue(sent.messages().get(0).content().endsWith("[/game context]\n\nSteve: build a tower"));
	}

	@Test
	void codeOnlyReplyDeliversNoChat() throws Exception {
		llm.reply(FIXED_REPLY);
		runner.ok("minecraft:oak_planks");

		AgentOutcome outcome = await(agent().handle(request("box", 2)));

		assertEquals("", outcome.chatText());
		assertTrue(listener.chats().isEmpty());
		assertEquals(List.of(FIXED_SCRIPT), runner.sources);
	}

	@Test
	void scriptErrorIsRepaired() throws Exception {
		String bad = "Building!\n```js\nfoo();\n```";
		String error = "ReferenceError: \"foo\" is not defined. (line 1: foo();)";
		llm.reply(bad).reply("Fixed it:\n" + FIXED_REPLY);
		runner.error(error).ok("minecraft:oak_planks");

		AgentOutcome outcome = await(agent().handle(request("build a box", 2)));

		assertEquals("Building!", outcome.chatText());
		assertNotNull(outcome.result());
		assertNull(outcome.failure());
		assertEquals(2, outcome.attempts());
		assertEquals(List.of("foo();", FIXED_SCRIPT), runner.sources);
		assertEquals(List.of("THINKING[0]", "chat(Building!)", "RUNNING_SCRIPT[]", "REPAIRING[2, 3, " + error + "]",
				"RUNNING_SCRIPT[]"), events());

		ChatMessage userTurn = ChatMessage.user(PromptBuilder.userTurn(context(), "build a box"));
		assertEquals(List.of(userTurn), messagesOfCall(0));
		assertEquals(List.of(userTurn, ChatMessage.assistant(bad), ChatMessage.user(PromptBuilder.repairTurn(error, 2, 3))),
				messagesOfCall(1));
		assertTrue(messagesOfCall(1).get(2).content().contains(error));
		// After a repair the conversation keeps the first reply's chat plus the script that actually worked.
		assertEquals(List.of(ChatMessage.user("Steve: build a box"),
				ChatMessage.assistant("Building!\n\n```js\n" + FIXED_SCRIPT + "\n```")), conversation.messages());
		assertEquals(List.of(), outcome.warnings());
	}

	@Test
	void invalidBlocksAreRepaired() throws Exception {
		llm.reply("```js\nfill(0,0,0,3,3,3,'marble_pillar');\n```").reply("```js\nfill(0,0,0,3,3,3,'quartz_pillar');\n```");
		runner.ok("minecraft:marble_pillar", "minecraft:glass").ok("minecraft:quartz_pillar", "minecraft:glass");
		List<List<String>> validated = new CopyOnWriteArrayList<>();
		BlockValidator validator = ids -> {
			validated.add(List.copyOf(ids));
			return ids.contains("minecraft:marble_pillar")
					? List.of(new InvalidBlock("minecraft:marble_pillar", "unknown block",
							List.of("minecraft:quartz_pillar", "minecraft:stone")))
					: List.of();
		};

		AgentOutcome outcome = await(agent().handle(request("wall", 2, validator)));

		String error = "Unknown or invalid block ids:\n"
				+ "- minecraft:marble_pillar: unknown block (did you mean: minecraft:quartz_pillar, minecraft:stone?)";
		assertNotNull(outcome.result());
		assertEquals(2, outcome.attempts());
		assertEquals(List.of(), outcome.warnings());
		// No auto-fix candidates (no alias/rule, suggestions outside a block family), so no extra validator call.
		assertEquals(List.of(List.of("minecraft:marble_pillar", "minecraft:glass"),
				List.of("minecraft:quartz_pillar", "minecraft:glass")), validated);
		assertEquals(List.of(2, 3, "Unknown or invalid block ids:"), listener.statuses(AgentStatus.REPAIRING).get(0).args());
		ChatMessage repair = messagesOfCall(1).get(2);
		assertEquals(ChatMessage.user(PromptBuilder.repairTurn(error, 2, 3)), repair);
		assertTrue(repair.content().contains("Java Edition 26.x"));
	}

	@Test
	void truncatedReplyIsRepairedWithoutRunningIt() throws Exception {
		llm.reply(FakeLlmClient.truncated("Here:\n```js\nblock(0,0,0,'stone');\nblock(1,0,0,'stone');\n```"))
				.reply(FIXED_REPLY);
		runner.ok("minecraft:oak_planks");

		AgentOutcome outcome = await(agent().handle(request("castle", 2)));

		assertEquals(2, outcome.attempts());
		assertNotNull(outcome.result());
		assertEquals(List.of(FIXED_SCRIPT), runner.sources);
		assertEquals(List.of(2, 3, AiAgent.TRUNCATED_ERROR), listener.statuses(AgentStatus.REPAIRING).get(0).args());
		String repair = messagesOfCall(1).get(2).content();
		assertTrue(repair.contains(AiAgent.TRUNCATED_ERROR));
		assertTrue(repair.contains("much shorter"));
		assertTrue(repair.contains("loops"));
	}

	@Test
	void unterminatedCodeBlockIsTreatedAsTruncation() throws Exception {
		llm.reply("Here:\n```js\nblock(0,0,0,'stone');\nblock(1,0").reply(FIXED_REPLY);
		runner.ok("minecraft:oak_planks");

		AgentOutcome outcome = await(agent().handle(request("castle", 2)));

		assertEquals(2, outcome.attempts());
		assertEquals(List.of(FIXED_SCRIPT), runner.sources);
		assertEquals(List.of("chat(Here:)"), events().stream().filter(s -> s.startsWith("chat")).toList());
		assertTrue(messagesOfCall(1).get(2).content().contains(AiAgent.TRUNCATED_ERROR));
	}

	@Test
	void truncatedChatWithoutScriptIsPureChat() throws Exception {
		llm.reply(FakeLlmClient.truncated("Castles have a long history. In the"));

		AgentOutcome outcome = await(agent().handle(request("tell me about castles", 2)));

		assertEquals(new AgentOutcome("Castles have a long history. In the", null, null, 1), outcome);
	}

	@Test
	void emptyBlueprintWithoutActionsIsRepaired() throws Exception {
		llm.reply(GOOD_REPLY).reply(FIXED_REPLY);
		runner.ok().ok("minecraft:oak_planks");

		AgentOutcome outcome = await(agent().handle(request("tower", 2)));

		assertEquals(2, outcome.attempts());
		String repair = messagesOfCall(1).get(2).content();
		assertTrue(repair.contains(AiAgent.EMPTY_RESULT_ERROR));
		assertEquals("The script ran but placed no blocks and requested no actions.", AiAgent.EMPTY_RESULT_ERROR);
	}

	@Test
	void actionsWithoutBlocksSucceed() throws Exception {
		llm.reply("Making it day!\n```js\ntime('day');\n```");
		runner.okWithActions(List.of(new WorldAction.SetTime("day")));

		AgentOutcome outcome = await(agent().handle(request("make it day", 2)));

		assertNotNull(outcome.result());
		assertEquals(1, outcome.attempts());
		assertEquals(List.of(new WorldAction.SetTime("day")), outcome.result().actions());
	}

	@Test
	void repairsStopAfterMaxRepairs() throws Exception {
		llm.reply("Okay!\n```js\na();\n```").reply("```js\nb();\n```").reply("```js\nc();\n```");
		runner.error("E1").error("E2").error("E3: still broken\n    at line 3");

		AgentOutcome outcome = await(agent().handle(request("build", 2)));

		assertEquals(new AgentOutcome("Okay!", null, "E3: still broken\n    at line 3", 3), outcome);
		assertEquals(3, llm.calls.size());
		assertEquals(List.of("a();", "b();", "c();"), runner.sources);
		assertEquals(List.of("REPAIRING[2, 3, E1]", "REPAIRING[3, 3, E2]"),
				listener.statuses(AgentStatus.REPAIRING).stream().map(Object::toString).toList());
		assertEquals(List.of(ChatMessage.user("Steve: build"), ChatMessage.assistant(
				"```js\nc();\n```\n\n(Note: this script failed and nothing was built: E3: still broken)")),
				conversation.messages());
		List<ChatMessage> last = messagesOfCall(2);
		assertEquals(5, last.size());
		assertEquals(ChatMessage.assistant("Okay!\n```js\na();\n```"), last.get(1));
		assertEquals(ChatMessage.user(PromptBuilder.repairTurn("E1", 2, 3)), last.get(2));
		assertEquals(ChatMessage.assistant("```js\nb();\n```"), last.get(3));
		assertEquals(ChatMessage.user(PromptBuilder.repairTurn("E2", 3, 3)), last.get(4));
	}

	@Test
	void noRepairWhenMaxRepairsIsZero() throws Exception {
		llm.reply(GOOD_REPLY);
		runner.error("SyntaxError: missing ; (line 2: x y)");

		AgentOutcome outcome = await(agent().handle(request("tower", 0)));

		assertEquals(new AgentOutcome("Sure, here is a tower!\nEnjoy!", null, "SyntaxError: missing ; (line 2: x y)", 1),
				outcome);
		assertTrue(listener.statuses(AgentStatus.REPAIRING).isEmpty());
		assertEquals(2, conversation.size());
	}

	@Test
	void refusalFailsWithoutRepair() throws Exception {
		llm.reply(FakeLlmClient.refused("I can't help with that."));

		AgentOutcome outcome = await(agent().handle(request("something bad", 2)));

		// The declined output is not shown to the player.
		assertEquals(new AgentOutcome("", null, AiAgent.FAILURE_REFUSED, 1), outcome);
		assertEquals("refused", AiAgent.FAILURE_REFUSED);
		assertEquals(1, llm.calls.size());
		assertTrue(runner.sources.isEmpty());
		assertEquals(List.of(), listener.chats());
		assertEquals(List.of("THINKING[0]"), events());
		assertEquals(List.of(ChatMessage.user("Steve: something bad"), ChatMessage.assistant("I can't help with that.")),
				conversation.messages());
	}

	@Test
	void refusalWithScriptDoesNotRunIt() throws Exception {
		llm.reply(FakeLlmClient.refused(GOOD_REPLY));

		AgentOutcome outcome = await(agent().handle(request("tower", 2)));

		assertEquals(AiAgent.FAILURE_REFUSED, outcome.failure());
		assertEquals("", outcome.chatText());
		assertTrue(runner.sources.isEmpty());
		assertTrue(listener.chats().isEmpty());
	}

	@Test
	void refusalDuringRepairEndsTheRequest() throws Exception {
		llm.reply(GOOD_REPLY).reply(FakeLlmClient.refused(""));
		runner.error("E1");

		AgentOutcome outcome = await(agent().handle(request("tower", 3)));

		assertEquals(new AgentOutcome("Sure, here is a tower!\nEnjoy!", null, AiAgent.FAILURE_REFUSED, 2), outcome);
		assertEquals(List.of(ChatMessage.user("Steve: tower"), ChatMessage.assistant("(empty reply)")),
				conversation.messages());
	}

	@Test
	void llmExceptionReachesCallerUnwrapped() {
		LlmException error = new LlmException("HTTP 401 from api.example.com: invalid key", 401, false);
		llm.fail(error);

		AgentTask task = agent().handle(request("hi", 2));

		ExecutionException e = assertThrows(ExecutionException.class, () -> task.future().get(5, TimeUnit.SECONDS));
		assertSame(error, e.getCause());
		CompletionException ce = assertThrows(CompletionException.class, () -> task.future().join());
		assertSame(error, ce.getCause());
		assertEquals(0, conversation.size());
		assertEquals(List.of("THINKING[0]"), events());
	}

	@Test
	void llmExceptionWrappedByTheClientIsUnwrapped() {
		LlmException error = new LlmException("timeout", -1, true);
		llm.fail(new CompletionException(error));

		AgentTask task = agent().handle(request("hi", 2));

		ExecutionException e = assertThrows(ExecutionException.class, () -> task.future().get(5, TimeUnit.SECONDS));
		assertSame(error, e.getCause());
	}

	@Test
	void llmExceptionDuringRepairLeavesConversationAlone() {
		LlmException error = new LlmException("HTTP 429 from api.example.com: rate limited", 429, true);
		llm.reply(GOOD_REPLY).fail(error);
		runner.error("E1");

		AgentTask task = agent().handle(request("tower", 2));

		ExecutionException e = assertThrows(ExecutionException.class, () -> task.future().get(5, TimeUnit.SECONDS));
		assertSame(error, e.getCause());
		assertEquals(0, conversation.size());
		assertEquals(1, listener.statuses(AgentStatus.REPAIRING).size());
	}

	@Test
	void clientThrowingSynchronouslyFailsTheFuture() {
		IllegalStateException boom = new IllegalStateException("boom");
		llm.then(call -> {
			throw boom;
		});

		AgentTask task = agent().handle(request("hi", 2));

		ExecutionException e = assertThrows(ExecutionException.class, () -> task.future().get(5, TimeUnit.SECONDS));
		assertSame(boom, e.getCause());
		assertEquals(0, conversation.size());
	}

	@Test
	void unexpectedScriptRunnerFailureFailsTheFuture() {
		IllegalStateException bug = new IllegalStateException("engine bug");
		llm.reply(GOOD_REPLY);
		runner.then(source -> {
			throw bug;
		});

		AgentTask task = agent().handle(request("tower", 2));

		ExecutionException e = assertThrows(ExecutionException.class, () -> task.future().get(5, TimeUnit.SECONDS));
		assertSame(bug, e.getCause());
		assertEquals(0, conversation.size());
		assertEquals(1, llm.calls.size());
	}

	@Test
	void validatorFailureFailsTheFuture() {
		IllegalStateException bug = new IllegalStateException("registry not ready");
		llm.reply(GOOD_REPLY);
		runner.ok("minecraft:stone");

		AgentTask task = agent().handle(request("tower", 2, ids -> {
			throw bug;
		}));

		ExecutionException e = assertThrows(ExecutionException.class, () -> task.future().get(5, TimeUnit.SECONDS));
		assertSame(bug, e.getCause());
	}

	@Test
	void executorRejectionFailsTheFuture() {
		IllegalStateException rejected = new IllegalStateException("rejected");
		llm.reply(GOOD_REPLY);

		AgentTask task = new AiAgent(llm, runner, r -> {
			throw rejected;
		}, runner.inspector(), clock::get).handle(request("tower", 2));

		ExecutionException e = assertThrows(ExecutionException.class, () -> task.future().get(5, TimeUnit.SECONDS));
		assertSame(rejected, e.getCause());
	}

	@Test
	void cancelDuringLlmCall() throws Exception {
		llm.hold();
		AgentTask task = agent().handle(request("build", 2));
		FakeLlmClient.Call call = llm.awaitCall(0);
		int before = listener.events.size();

		task.cancel();

		assertTrue(task.future().isCancelled());
		assertThrows(CancellationException.class, () -> task.future().get(1, TimeUnit.SECONDS));
		assertTrue(call.future.isCancelled());
		clock.addAndGet(5000 * MS);
		call.progress(100, 0, "Sure\n```js\nfill();\n");
		call.reply(GOOD_REPLY);
		task.cancel();
		assertEquals(before, listener.events.size());
		assertTrue(runner.sources.isEmpty());
		assertEquals(1, llm.calls.size());
		assertEquals(0, conversation.size());
	}

	@Test
	void cancellingTheFutureDirectlyAbortsTheLlmCall() throws Exception {
		llm.hold();
		AgentTask task = agent().handle(request("build", 2));
		FakeLlmClient.Call call = llm.awaitCall(0);

		task.future().cancel(true);

		assertTrue(call.future.isCancelled());
		call.progress(10, 0, "Hi");
		assertEquals(List.of("THINKING[0]"), events());
		assertEquals(0, conversation.size());
	}

	@Test
	void cancelDuringScriptRunInterruptsIt() throws Exception {
		pool = Executors.newSingleThreadExecutor();
		llm.reply(GOOD_REPLY);
		runner.blockUntilInterrupted();
		AgentTask task = agent(pool).handle(request("build", 2));
		assertTrue(runner.blockingStarted.await(5, TimeUnit.SECONDS));
		int before = listener.events.size();

		task.cancel();

		assertTrue(runner.interrupted.await(5, TimeUnit.SECONDS));
		assertTrue(task.future().isCancelled());
		pool.shutdown();
		assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
		assertEquals(before, listener.events.size());
		assertEquals(List.of("THINKING[0]", "chat(Sure, here is a tower!\nEnjoy!)", "RUNNING_SCRIPT[]"), events());
		assertEquals(1, llm.calls.size());
		assertEquals(0, conversation.size());
	}

	@Test
	void cancelWhileScriptIsQueuedNeverRunsIt() throws Exception {
		List<Runnable> queued = new ArrayList<>();
		llm.reply(GOOD_REPLY);
		AgentTask task = agent(queued::add).handle(request("build", 2));
		assertEquals(1, queued.size());
		int before = listener.events.size();

		task.cancel();
		queued.get(0).run();

		assertTrue(task.future().isCancelled());
		assertTrue(runner.sources.isEmpty());
		assertEquals(before, listener.events.size());
		assertEquals(0, conversation.size());
	}

	@Test
	void cancelAfterCompletionHasNoEffect() throws Exception {
		llm.reply("Hello!");
		AgentTask task = agent().handle(request("hi", 2));
		AgentOutcome outcome = await(task);

		task.cancel();

		assertEquals(outcome, task.future().get());
		assertEquals(2, conversation.size());
	}

	@Test
	void progressStatusesAreThrottled() throws Exception {
		llm.hold();
		runner.ok("minecraft:stone");
		AgentTask task = agent().handle(request("build", 2));
		FakeLlmClient.Call call = llm.awaitCall(0);
		String head = "Sure, here:\n```js\n";

		clock.set(500 * MS);
		call.progress(5, 10, "");
		clock.set(1000 * MS);
		call.progress(20, 30, "Sure");
		clock.set(1200 * MS);
		call.progress(25, 30, "Sure, ");
		clock.set(1300 * MS);
		call.progress(40, 30, head);
		clock.set(1500 * MS);
		call.progress(60, 30, head + "a();\n");
		clock.set(2300 * MS);
		call.progress(80, 30, head + "a();\nb();\nc();\nd(");
		clock.set(2400 * MS);
		call.progress(90, 30, head + "a();\nb();\nc();\nd();\n");
		clock.set(3300 * MS);
		call.progress(95, 30, head + "a();\nb();\nc();\nd();\n");
		clock.set(9000 * MS);
		call.progress(120, 30, head + "a();\nb();\nc();\nd();\n```\nDone, enjoy the");
		call.reply(head + "a();\nb();\nc();\nd();\n```\nDone, enjoy the tower.");
		await(task);

		assertEquals(List.of("THINKING[0]", "THINKING[50]", "WRITING_SCRIPT[0]", "WRITING_SCRIPT[4]", "WRITING_SCRIPT[4]",
				"WRITING_SCRIPT[4]", "RUNNING_SCRIPT[]"), listener.statusStrings());
		for (RecordingListener.Event e : listener.statuses()) {
			e.args().forEach(a -> assertInstanceOf(Integer.class, a));
		}
	}

	@Test
	void thinkingProgressReportsTextPlusReasoningChars() throws Exception {
		llm.hold();
		AgentTask task = agent().handle(request("hi", 2));
		FakeLlmClient.Call call = llm.awaitCall(0);

		clock.set(1000 * MS);
		call.progress(0, 1234, "");
		clock.set(2500 * MS);
		call.progress(7, 2000, "Hello, ");
		call.reply("Hello, Steve!");
		await(task);

		assertEquals(List.of("THINKING[0]", "THINKING[1234]", "THINKING[2007]"), listener.statusStrings());
	}

	@Test
	void lateProgressAfterTheReplyIsIgnored() throws Exception {
		List<Runnable> queued = new ArrayList<>();
		llm.hold();
		runner.ok("minecraft:stone_bricks");
		AgentTask task = agent(queued::add).handle(request("build", 2));
		FakeLlmClient.Call call = llm.awaitCall(0);
		call.reply(GOOD_REPLY);

		clock.set(5000 * MS);
		call.progress(500, 0, GOOD_REPLY);
		queued.get(0).run();
		await(task);

		assertEquals(List.of("THINKING[0]", "RUNNING_SCRIPT[]"), listener.statusStrings());
	}

	@Test
	void repairCallsReportWritingButNotThinking() throws Exception {
		llm.reply("```js\nbad();\n```").hold();
		runner.error("E1").ok("minecraft:stone");
		AgentTask task = agent().handle(request("build", 2));
		FakeLlmClient.Call repair = llm.awaitCall(1);

		clock.set(10_000 * MS);
		repair.progress(0, 500, "");
		clock.set(10_100 * MS);
		repair.progress(10, 500, "```js\nok();\n");
		repair.reply("```js\nok();\n```");
		await(task);

		assertEquals(List.of("THINKING[0]", "RUNNING_SCRIPT[]", "REPAIRING[2, 3, E1]", "WRITING_SCRIPT[1]",
				"RUNNING_SCRIPT[]"), listener.statusStrings());
	}

	@Test
	void requestContainsHistoryContextAndRepairTurnsInOrder() throws Exception {
		conversation.add(ChatMessage.user("Steve: build a hut"));
		conversation.add(ChatMessage.assistant("Here you go!\n```js\nhut();\n```"));
		conversation.add(ChatMessage.user("Steve: add a door"));
		conversation.add(ChatMessage.assistant("Done!\n```js\ndoor();\n```"));
		conversation.add(ChatMessage.user("Steve: thanks"));
		conversation.add(ChatMessage.assistant("You're welcome!"));
		llm.reply("```js\nbad();\n```").reply("```js\ngood();\n```");
		runner.error("E1").ok("minecraft:stone");

		await(agent().handle(request("make it bigger", 2, BlockValidator.ACCEPT_ALL, 6)));

		ChatMessage userTurn = ChatMessage.user(PromptBuilder.userTurn(context(), "make it bigger"));
		List<ChatMessage> history = List.of(
				ChatMessage.user("Steve: build a hut"),
				ChatMessage.assistant("Here you go!\n```js\n// (earlier script omitted)\n```"),
				ChatMessage.user("Steve: add a door"),
				ChatMessage.assistant("Done!\n```js\ndoor();\n```"),
				ChatMessage.user("Steve: thanks"),
				ChatMessage.assistant("You're welcome!"));
		List<ChatMessage> first = new ArrayList<>(history);
		first.add(userTurn);
		assertEquals(first, messagesOfCall(0));
		List<ChatMessage> second = new ArrayList<>(first);
		second.add(ChatMessage.assistant("```js\nbad();\n```"));
		second.add(ChatMessage.user(PromptBuilder.repairTurn("E1", 2, 3)));
		assertEquals(second, messagesOfCall(1));
		for (int i = 0; i < second.size(); i++) {
			assertEquals(i % 2 == 0 ? ChatMessage.Role.USER : ChatMessage.Role.ASSISTANT, second.get(i).role());
		}
		assertTrue(userTurn.content().contains("[game context]"));

		List<ChatMessage> stored = conversation.messages();
		assertEquals(8, stored.size());
		assertEquals(ChatMessage.assistant("Here you go!\n```js\nhut();\n```"), stored.get(1));
		assertEquals(ChatMessage.user("Steve: make it bigger"), stored.get(6));
		assertEquals(ChatMessage.assistant("```js\ngood();\n```"), stored.get(7));
	}

	@Test
	void historyWindowStartsWithUserMessage() throws Exception {
		conversation.add(ChatMessage.user("Steve: one"));
		conversation.add(ChatMessage.assistant("first"));
		conversation.add(ChatMessage.user("Steve: two"));
		conversation.add(ChatMessage.assistant("second"));
		llm.reply("ok");

		await(agent().handle(request("three", 2, BlockValidator.ACCEPT_ALL, 3)));

		List<ChatMessage> sent = messagesOfCall(0);
		assertEquals(3, sent.size());
		assertEquals(ChatMessage.user("Steve: two"), sent.get(0));
		assertEquals(ChatMessage.assistant("second"), sent.get(1));
	}

	@Test
	void zeroHistorySendsOnlyTheUserTurn() throws Exception {
		conversation.add(ChatMessage.user("Steve: one"));
		conversation.add(ChatMessage.assistant("first"));
		llm.reply("ok");

		await(agent().handle(request("two", 2, BlockValidator.ACCEPT_ALL, 0)));

		assertEquals(List.of(ChatMessage.user(PromptBuilder.userTurn(context(), "two"))), messagesOfCall(0));
	}

	@Test
	void repairReplyWithoutScriptIsAnError() throws Exception {
		llm.reply("```js\nbad();\n```").reply("Sorry, I fixed it!").reply(FIXED_REPLY);
		runner.error("E1").ok("minecraft:oak_planks");

		AgentOutcome outcome = await(agent().handle(request("box", 3)));

		assertEquals(3, outcome.attempts());
		assertNotNull(outcome.result());
		assertTrue(listener.chats().isEmpty());
		assertEquals(List.of(3, 4, AiAgent.NO_SCRIPT_ERROR), listener.statuses(AgentStatus.REPAIRING).get(1).args());
		assertTrue(messagesOfCall(2).get(4).content().contains(AiAgent.NO_SCRIPT_ERROR));
	}

	@Test
	void emptyFirstReplyIsAnError() throws Exception {
		llm.reply("<think>hmm</think>").reply(FIXED_REPLY);
		runner.ok("minecraft:oak_planks");

		AgentOutcome outcome = await(agent().handle(request("box", 2)));

		assertEquals(2, outcome.attempts());
		assertEquals(ChatMessage.assistant("<think>hmm</think>"), messagesOfCall(1).get(1));
		assertTrue(messagesOfCall(1).get(2).content().contains(AiAgent.EMPTY_REPLY_ERROR));
	}

	@Test
	void blankReplyIsSentBackAsPlaceholder() throws Exception {
		llm.reply("").reply(FIXED_REPLY);
		runner.ok("minecraft:oak_planks");

		await(agent().handle(request("box", 2)));

		assertEquals(ChatMessage.assistant("(empty reply)"), messagesOfCall(1).get(1));
	}

	@Test
	void onlyTheFirstReplyProducesChat() throws Exception {
		llm.reply("First!\n```js\nbad();\n```").reply("Second chat\n" + FIXED_REPLY);
		runner.error("E1").ok("minecraft:oak_planks");

		AgentOutcome outcome = await(agent().handle(request("box", 2)));

		assertEquals(List.of("First!"), listener.chats());
		assertEquals("First!", outcome.chatText());
	}

	@Test
	void listenerExceptionsDoNotBreakThePipeline() throws Exception {
		llm.reply(GOOD_REPLY);
		runner.ok("minecraft:stone_bricks");
		AgentListener throwing = new AgentListener() {
			@Override
			public void onChat(String text) {
				throw new IllegalStateException("chat");
			}

			@Override
			public void onStatus(AgentStatus status, Object... args) {
				throw new IllegalStateException("status");
			}
		};

		AgentOutcome outcome = await(agent().handle(new AgentRequest(conversation, SYSTEM, context(), "tower",
				ScriptLimits.defaults(), 12, 2, BlockValidator.ACCEPT_ALL, throwing, SEED)));

		assertNotNull(outcome.result());
	}

	@Test
	void nullListenerValidatorAndLimitsAreTolerated() throws Exception {
		llm.reply(GOOD_REPLY);
		runner.ok("minecraft:stone_bricks");

		AgentOutcome outcome = await(agent().handle(new AgentRequest(conversation, SYSTEM, context(), "tower", null, 12, 2,
				null, null, SEED)));

		assertNotNull(outcome.result());
		assertEquals(List.of(ScriptLimits.defaults()), runner.limits);
	}

	@Test
	void asynchronousRepairLoopOnRealThreads() throws Exception {
		pool = Executors.newFixedThreadPool(2);
		llm.replyAfter(20, "Working on it\n```js\nbad();\n```").replyAfter(20, FIXED_REPLY);
		runner.error("E1").ok("minecraft:oak_planks");

		AgentOutcome outcome = await(agent(pool).handle(request("box", 2)));

		assertEquals(2, outcome.attempts());
		assertEquals("Working on it", outcome.chatText());
		assertEquals(List.of("THINKING[0]", "chat(Working on it)", "RUNNING_SCRIPT[]", "REPAIRING[2, 3, E1]",
				"RUNNING_SCRIPT[]"), events());
		assertEquals(2, conversation.size());
	}

	@Test
	void shortErrorKeepsFirstLineAndCutsTo100Chars() {
		assertEquals("TypeError: x", AiAgent.shortError("  TypeError: x\nmore\nlines"));
		assertEquals("", AiAgent.shortError(null));
		String longLine = "E".repeat(150);
		String cut = AiAgent.shortError(longLine);
		assertEquals(100, cut.length());
		assertTrue(cut.endsWith("..."));
		assertEquals("E".repeat(100), AiAgent.shortError("E".repeat(100)));
	}

	@Test
	void invalidBlocksMessageFormat() {
		String message = AiAgent.invalidBlocksMessage(List.of(
				new InvalidBlock("minecraft:stone_brick", "unknown block", List.of("minecraft:stone_bricks")),
				new InvalidBlock("minecraft:oak_stairs[facing=up]", "invalid value 'up' for property facing", List.of()),
				new InvalidBlock("minecraft:foo", " ", null)));
		assertEquals("Unknown or invalid block ids:\n"
				+ "- minecraft:stone_brick: unknown block (did you mean: minecraft:stone_bricks?)\n"
				+ "- minecraft:oak_stairs[facing=up]: invalid value 'up' for property facing\n"
				+ "- minecraft:foo: unknown block", message);
	}

	// --- Auto-fixing invalid block ids ---

	/**
	 * A registry-like validator: only the given ids (exact normalized strings, states included) are valid; every
	 * rejected id gets {@link Suggest#closest} suggestions over the valid base ids, like the Minecraft adapter. Each
	 * call's ids are recorded in {@code calls}.
	 */
	private static BlockValidator registry(List<List<String>> calls, String... valid) {
		Set<String> accepted = Set.of(valid);
		List<String> bases = Arrays.stream(valid).map(BlockIds::baseId).distinct().toList();
		return ids -> {
			calls.add(List.copyOf(ids));
			return ids.stream()
					.filter(id -> !accepted.contains(id))
					.map(id -> new InvalidBlock(id, "unknown block", Suggest.closest(id, bases, 3)))
					.toList();
		};
	}

	/** Places {@code count} blocks of {@code id} along +x at height 0, depth z. */
	private static void row(Blueprint blueprint, int z, String id, int count) {
		for (int x = 0; x < count; x++) {
			blueprint.set(x, 0, z, id);
		}
	}

	@Test
	void unknownBlocksAreAutoFixedWithoutARepairRoundTrip() throws Exception {
		Blueprint blueprint = new Blueprint(1000, 64);
		row(blueprint, 0, "stone_brick", 4);
		row(blueprint, 1, "glass", 2);
		String reply = "A wall!\n```js\nfill(0, 0, 0, 3, 0, 0, 'stone_brick');\n```";
		llm.reply(reply);
		runner.okBlueprint(blueprint);
		List<List<String>> validated = new CopyOnWriteArrayList<>();

		AgentOutcome outcome = await(agent().handle(request("wall", 2,
				registry(validated, "minecraft:stone_bricks", "minecraft:glass"))));

		assertEquals(1, outcome.attempts());
		assertNull(outcome.failure());
		assertSame(blueprint, outcome.result().blueprint());
		assertEquals(List.of("auto-fixed block 'stone_brick' -> 'stone_bricks'"), outcome.warnings());
		assertEquals(List.of("minecraft:glass", "minecraft:stone_bricks"), blueprint.palette());
		assertEquals("minecraft:stone_bricks", blueprint.get(3, 0, 0));
		assertEquals(6, blueprint.size());
		assertEquals(List.of(List.of("minecraft:stone_brick", "minecraft:glass"), List.of("minecraft:stone_bricks")),
				validated);
		assertEquals(1, llm.calls.size());
		assertEquals(List.of("THINKING[0]", "chat(A wall!)", "RUNNING_SCRIPT[]"), events());
		// No repair: the reply is stored as it was.
		assertEquals(List.of(ChatMessage.user("Steve: wall"), ChatMessage.assistant(reply)), conversation.messages());
	}

	@Test
	void autoFixValidatesAllCandidatesInOneCallAndKeepsBlockStates() throws Exception {
		Blueprint blueprint = new Blueprint(1000, 64);
		blueprint.set(0, 0, 0, "oak_planks_stairs[facing=east]");
		blueprint.set(1, 0, 0, "stone_brick[facing=north]");
		blueprint.set(2, 0, 0, "oak_log[facing=up]");
		blueprint.set(3, 0, 0, "brich_stairs[half=top]");
		blueprint.set(4, 0, 0, "glass");
		llm.reply(GOOD_REPLY);
		runner.okBlueprint(blueprint);
		List<List<String>> validated = new CopyOnWriteArrayList<>();
		BlockValidator validator = registry(validated, "minecraft:oak_stairs[facing=east]", "minecraft:stone_bricks",
				"minecraft:oak_log", "minecraft:birch_stairs[half=top]", "minecraft:glass");

		AgentOutcome outcome = await(agent().handle(request("tower", 2, validator)));

		assertEquals(1, outcome.attempts());
		assertEquals(2, validated.size());
		assertEquals(List.of(
				// oak_planks_stairs[facing=east]: rule with state, rule without state, input without state
				"minecraft:oak_stairs[facing=east]", "minecraft:oak_stairs", "minecraft:oak_planks_stairs",
				// stone_brick[facing=north]: alias with state, alias, input without state
				"minecraft:stone_bricks[facing=north]", "minecraft:stone_bricks", "minecraft:stone_brick",
				// oak_log[facing=up]: only the input without its invalid state
				"minecraft:oak_log",
				// brich_stairs[half=top]: input without state, then the registry's fuzzy suggestion (same family)
				"minecraft:brich_stairs", "minecraft:birch_stairs[half=top]", "minecraft:birch_stairs"),
				validated.get(1));
		assertEquals(List.of(
				"auto-fixed block 'oak_planks_stairs[facing=east]' -> 'oak_stairs[facing=east]'",
				"auto-fixed block 'stone_brick[facing=north]' -> 'stone_bricks'",
				"auto-fixed block 'oak_log[facing=up]' -> 'oak_log'",
				"auto-fixed block 'brich_stairs[half=top]' -> 'birch_stairs[half=top]'"), outcome.warnings());
		assertEquals("minecraft:oak_stairs[facing=east]", blueprint.get(0, 0, 0));
		assertEquals("minecraft:stone_bricks", blueprint.get(1, 0, 0));
		assertEquals("minecraft:oak_log", blueprint.get(2, 0, 0));
		assertEquals("minecraft:birch_stairs[half=top]", blueprint.get(3, 0, 0));
		assertEquals("minecraft:glass", blueprint.get(4, 0, 0));
	}

	@Test
	void partialAutoFixSendsOnlyTheRemainingIdsToRepair() throws Exception {
		Blueprint first = new Blueprint(1000, 64);
		row(first, 0, "stone_brick", 3);
		row(first, 1, "birch_wool", 2);
		Blueprint second = new Blueprint(1000, 64);
		row(second, 0, "stone_brick", 3);
		row(second, 1, "white_wool", 2);
		llm.reply("Here is your wall.\n```js\nwall('stone_brick', 'birch_wool');\n```")
				.reply("```js\nwall('stone_brick', 'white_wool');\n```");
		runner.okBlueprint(first).okBlueprint(second);
		List<List<String>> validated = new CopyOnWriteArrayList<>();
		String[] valid = {"minecraft:stone_bricks", "minecraft:white_wool", "minecraft:birch_wood"};

		AgentOutcome outcome = await(agent().handle(request("wall", 2, registry(validated, valid))));

		assertEquals(2, outcome.attempts());
		assertSame(second, outcome.result().blueprint());
		assertEquals(List.of("auto-fixed block 'stone_brick' -> 'stone_bricks'"), outcome.warnings());
		assertEquals(List.of("minecraft:white_wool", "minecraft:stone_bricks"), second.palette());
		// The repair lists only birch_wool; its suggestion birch_wood is another block family, so it is not applied.
		List<String> suggestions = Suggest.closest("minecraft:birch_wool", Arrays.asList(valid), 3);
		assertEquals("minecraft:birch_wood", suggestions.get(0));
		String error = AiAgent.invalidBlocksMessage(List.of(new InvalidBlock("minecraft:birch_wool", "unknown block",
				suggestions)));
		ChatMessage repair = messagesOfCall(1).get(2);
		assertEquals(ChatMessage.user(PromptBuilder.repairTurn(error, 2, 3)), repair);
		assertFalse(repair.content().contains("stone_brick"));
		assertEquals(List.of(2, 3, AiAgent.INVALID_BLOCKS_HEADER), listener.statuses(AgentStatus.REPAIRING).get(0).args());
		assertEquals(List.of(
				List.of("minecraft:stone_brick", "minecraft:birch_wool"), List.of("minecraft:stone_bricks"),
				List.of("minecraft:stone_brick", "minecraft:white_wool"), List.of("minecraft:stone_bricks")), validated);
		// The attempt that went to repair left its blueprint untouched.
		assertEquals(List.of("minecraft:stone_brick", "minecraft:birch_wool"), first.palette());
		assertEquals(List.of(ChatMessage.user("Steve: wall"),
				ChatMessage.assistant("Here is your wall.\n\n```js\nwall('stone_brick', 'white_wool');\n```")),
				conversation.messages());
	}

	@Test
	void unknownBlocksAreRemovedAfterTheLastRepairAttempt() throws Exception {
		Blueprint first = new Blueprint(1000, 64);
		row(first, 0, "fancy_pillar", 2);
		Blueprint last = new Blueprint(1000, 64);
		row(last, 0, "fancy_pillar", 3);
		row(last, 1, "stone_brick", 2);
		row(last, 2, "stone", 4);
		last.set(0, 5, 0, "glowing_thing");
		llm.reply("A temple!\n```js\ntemple();\n```").reply("```js\ntemple2();\n```");
		runner.okBlueprint(first).okBlueprint(last);
		List<List<String>> validated = new CopyOnWriteArrayList<>();

		AgentOutcome outcome = await(agent().handle(request("temple", 1,
				registry(validated, "minecraft:stone", "minecraft:stone_bricks"))));

		assertEquals(2, outcome.attempts());
		assertNull(outcome.failure());
		assertEquals("A temple!", outcome.chatText());
		assertSame(last, outcome.result().blueprint());
		assertEquals(List.of(
				"auto-fixed block 'stone_brick' -> 'stone_bricks'",
				"removed 3 blocks with unknown id 'fancy_pillar'",
				"removed 1 block with unknown id 'glowing_thing'"), outcome.warnings());
		assertEquals(6, last.size());
		assertEquals(List.of("minecraft:stone", "minecraft:stone_bricks"), last.palette());
		assertNull(last.get(0, 0, 0));
		assertNull(last.get(0, 5, 0));
		assertEquals("minecraft:stone_bricks", last.get(1, 0, 1));
		assertEquals(List.of("REPAIRING[2, 2, " + AiAgent.INVALID_BLOCKS_HEADER + "]"),
				listener.statuses(AgentStatus.REPAIRING).stream().map(Object::toString).toList());
		assertEquals(List.of(ChatMessage.user("Steve: temple"),
				ChatMessage.assistant("A temple!\n\n```js\ntemple2();\n```")), conversation.messages());
	}

	@Test
	void removalFallbackFailsWhenOnlyAirWouldRemain() throws Exception {
		Blueprint blueprint = new Blueprint(1000, 64);
		row(blueprint, 0, "air", 3);
		row(blueprint, 1, "fancy_pillar", 2);
		llm.reply(GOOD_REPLY);
		runner.okBlueprint(blueprint);

		AgentOutcome outcome = await(agent().handle(request("tower", 0,
				registry(new CopyOnWriteArrayList<>(), "minecraft:air"))));

		String error = AiAgent.invalidBlocksMessage(List.of(
				new InvalidBlock("minecraft:fancy_pillar", "unknown block", List.of())));
		assertEquals(new AgentOutcome("Sure, here is a tower!\nEnjoy!", null, error, 1, List.of()), outcome);
		assertEquals(List.of(ChatMessage.user("Steve: tower"), ChatMessage.assistant(GOOD_REPLY
				+ "\n\n(Note: this script failed and nothing was built: Unknown or invalid block ids:)")),
				conversation.messages());
	}

	@Test
	void removalFallbackSucceedsWhenWorldActionsRemain() throws Exception {
		Blueprint blueprint = new Blueprint(1000, 64);
		blueprint.set(0, 0, 0, "fancy_pillar");
		String reply = "Night time!\n```js\ntime('night');\nblock(0, 0, 0, 'fancy_pillar');\n```";
		llm.reply(reply);
		runner.okBlueprint(blueprint, new WorldAction.SetTime("night"));

		AgentOutcome outcome = await(agent().handle(request("night", 0, registry(new CopyOnWriteArrayList<>()))));

		assertNull(outcome.failure());
		assertEquals(1, outcome.attempts());
		assertTrue(blueprint.isEmpty());
		assertEquals(List.of(new WorldAction.SetTime("night")), outcome.result().actions());
		assertEquals(List.of("removed 1 block with unknown id 'fancy_pillar'"), outcome.warnings());
		assertTrue(listener.statuses(AgentStatus.REPAIRING).isEmpty());
		assertEquals(List.of(ChatMessage.user("Steve: night"), ChatMessage.assistant(reply)), conversation.messages());
	}

	@Test
	void autoFixWorksWithPaletteOnlyResults() throws Exception {
		llm.reply(GOOD_REPLY);
		runner.ok("minecraft:stone_brick", "minecraft:stone_bricks", "minecraft:mystery");

		AgentOutcome outcome = await(agent().handle(request("tower", 0,
				registry(new CopyOnWriteArrayList<>(), "minecraft:stone_bricks"))));

		assertEquals(List.of("auto-fixed block 'stone_brick' -> 'stone_bricks'",
				"removed 1 block with unknown id 'mystery'"), outcome.warnings());
		assertEquals(List.of("minecraft:stone_bricks"), runner.inspector().palette(outcome.result()));
	}

	@Test
	void validatorFailureDuringAutoFixFailsTheFuture() {
		IllegalStateException bug = new IllegalStateException("registry not ready");
		AtomicInteger calls = new AtomicInteger();
		llm.reply(GOOD_REPLY);
		runner.ok("minecraft:stone_brick");

		AgentTask task = agent().handle(request("tower", 2, ids -> {
			if (calls.getAndIncrement() == 0) {
				return List.of(new InvalidBlock("minecraft:stone_brick", "unknown block", List.of()));
			}
			throw bug;
		}));

		ExecutionException e = assertThrows(ExecutionException.class, () -> task.future().get(5, TimeUnit.SECONDS));
		assertSame(bug, e.getCause());
		assertEquals(0, conversation.size());
	}

	@Test
	void laterTurnsSeeTheChatAndTheScriptThatWorked() throws Exception {
		llm.reply("Building!\n```js\nfoo();\n```").reply("Oops, fixed:\n" + FIXED_REPLY).reply("Oak planks.");
		runner.error("E1").ok("minecraft:oak_planks");

		await(agent().handle(request("build a box", 2)));
		await(agent().handle(request("what is it made of?", 2)));

		List<ChatMessage> sent = messagesOfCall(2);
		assertEquals(ChatMessage.user("Steve: build a box"), sent.get(0));
		assertEquals(ChatMessage.assistant("Building!\n\n```js\n" + FIXED_SCRIPT + "\n```"), sent.get(1));
		ParsedResponse stored = ResponseParser.parse(sent.get(1).content());
		assertEquals("Building!", stored.chatText());
		assertEquals(FIXED_SCRIPT, stored.script());
	}

	@Test
	void repairedReplyFormat() {
		assertEquals("Hi!\n\n```js\nfoo();\n```", AiAgent.repairedReply("Hi!", "foo();"));
		assertEquals("```js\nfoo();\n```", AiAgent.repairedReply("", "foo();"));
		assertEquals("```js\nfoo();\n```", AiAgent.repairedReply(null, "foo();"));
		String script = "const s = `a`;\nlog('```');\nblock(0, 0, 0, 'stone');";
		String stored = AiAgent.repairedReply("Chat", script);
		assertEquals("Chat\n\n````js\n" + script + "\n````", stored);
		ParsedResponse parsed = ResponseParser.parse(stored);
		assertEquals("Chat", parsed.chatText());
		assertEquals(script, parsed.script());
		assertFalse(parsed.unterminatedCode());
	}

	@Test
	void warningTexts() {
		assertEquals("auto-fixed block 'stone_brick' -> 'lantern[hanging=true]'",
				AiAgent.autoFixedWarning("minecraft:stone_brick", "minecraft:lantern[hanging=true]"));
		assertEquals("auto-fixed block 'mymod:a' -> 'mymod:b'", AiAgent.autoFixedWarning("mymod:a", "mymod:b"));
		assertEquals("removed 1 block with unknown id 'foo'", AiAgent.removedWarning(1, "minecraft:foo"));
		assertEquals("removed 42 blocks with unknown id 'mymod:foo'", AiAgent.removedWarning(42, "mymod:foo"));
	}

	@Test
	void outcomeWarningsAreNeverNullAndImmutable() {
		assertEquals(List.of(), new AgentOutcome("", null, null, 1, null).warnings());
		assertEquals(List.of(), new AgentOutcome("", null, null, 1).warnings());
		assertEquals(new AgentOutcome("a", null, "f", 2), new AgentOutcome("a", null, "f", 2, List.of()));
		List<String> source = new ArrayList<>(List.of("w"));
		AgentOutcome outcome = new AgentOutcome("", null, null, 1, source);
		source.add("x");
		assertEquals(List.of("w"), outcome.warnings());
		assertThrows(UnsupportedOperationException.class, () -> outcome.warnings().add("y"));
	}

	// --- Limits on invalid ids, warnings and stored failures ---

	private static String[] validBlocks(int count, String... more) {
		List<String> ids = new ArrayList<>(IntStream.range(0, count).mapToObj(i -> "minecraft:block_" + i).toList());
		ids.addAll(List.of(more));
		return ids.toArray(String[]::new);
	}

	@Test
	void autoFixLooksAtTheFirst32DistinctInvalidIds() throws Exception {
		Blueprint first = new Blueprint(1000, 64);
		for (int i = 0; i < 40; i++) {
			first.set(i, 0, 0, "block_" + i + "[facing=up]");
		}
		llm.reply(GOOD_REPLY).reply(FIXED_REPLY);
		runner.okBlueprint(first).ok("minecraft:oak_planks");
		List<List<String>> validated = new CopyOnWriteArrayList<>();
		BlockValidator registry = registry(validated, validBlocks(40, "minecraft:oak_planks"));
		BlockValidator duplicating = ids -> {
			List<InvalidBlock> bad = new ArrayList<>(registry.validate(ids));
			bad.addAll(List.copyOf(bad));
			return bad;
		};

		AgentOutcome outcome = await(agent().handle(request("blocks", 1, duplicating)));

		assertEquals(2, outcome.attempts());
		assertNotNull(outcome.result());
		// One candidate (the id without its invalid state) for each of the first 32 distinct ids, in one call.
		assertEquals(IntStream.range(0, 32).mapToObj(i -> "minecraft:block_" + i).toList(), validated.get(1));
		String repair = messagesOfCall(1).get(2).content();
		for (int i = 32; i < 40; i++) {
			assertTrue(repair.contains("\n- minecraft:block_" + i + "[facing=up]: unknown block"), repair);
		}
		assertFalse(repair.contains("block_31[facing=up]"), repair);
		assertEquals(1, repair.split("block_35\\[facing=up]", -1).length - 1, "listed once");
		assertFalse(repair.contains("more unknown ids"), repair);
	}

	@Test
	void repairMessageListsAtMost20Ids() throws Exception {
		Blueprint first = new Blueprint(1000, 64);
		for (int i = 0; i < 50; i++) {
			first.set(i, 0, 0, "mymod:fancy_" + i);
		}
		llm.reply(GOOD_REPLY).reply(FIXED_REPLY);
		runner.okBlueprint(first).ok("minecraft:oak_planks");

		await(agent().handle(request("blocks", 1, registry(new CopyOnWriteArrayList<>(), "minecraft:oak_planks"))));

		String repair = messagesOfCall(1).get(2).content();
		for (int i = 0; i < 20; i++) {
			assertTrue(repair.contains("\n- mymod:fancy_" + i + ": unknown block\n"), repair);
		}
		assertFalse(repair.contains("mymod:fancy_20:"), repair);
		assertTrue(repair.contains("\n- mymod:fancy_19: unknown block\n... and 30 more unknown ids"), repair);
	}

	@Test
	void invalidBlocksMessageIsCappedAt20Ids() {
		List<InvalidBlock> invalid = IntStream.range(0, 23)
				.mapToObj(i -> new InvalidBlock("minecraft:x" + i, "unknown block", List.of())).toList();
		String message = AiAgent.invalidBlocksMessage(invalid);
		assertEquals(1 + 20 + 1, message.split("\n").length);
		assertTrue(message.endsWith("\n- minecraft:x19: unknown block\n... and 3 more unknown ids"), message);
		assertFalse(AiAgent.invalidBlocksMessage(invalid.subList(0, 20)).contains("more"));
	}

	@Test
	void warningsAreCappedAt10() throws Exception {
		Blueprint blueprint = new Blueprint(1000, 64);
		for (int i = 0; i < 15; i++) {
			blueprint.set(i, 0, 0, "block_" + i + "[facing=up]");
		}
		for (int i = 0; i < 5; i++) {
			blueprint.set(i, 1, 0, "mymod:fancy_" + i);
		}
		blueprint.set(0, 2, 0, "stone");
		llm.reply(GOOD_REPLY);
		runner.okBlueprint(blueprint);

		AgentOutcome outcome = await(agent().handle(request("blocks", 0,
				registry(new CopyOnWriteArrayList<>(), validBlocks(15, "minecraft:stone")))));

		assertNull(outcome.failure());
		List<String> expected = new ArrayList<>(IntStream.range(0, 10)
				.mapToObj(i -> "auto-fixed block 'block_" + i + "[facing=up]' -> 'block_" + i + "'").toList());
		expected.add("... and 10 more");
		assertEquals(expected, outcome.warnings());
		assertEquals("minecraft:block_14", blueprint.get(14, 0, 0));
		assertNull(blueprint.get(4, 1, 0));
		assertEquals(List.of("a", "b"), AiAgent.capWarnings(List.of("a", "b")));
	}

	@Test
	void interruptedCheckStopsBetweenSteps() throws Exception {
		pool = Executors.newSingleThreadExecutor();
		Blueprint blueprint = new Blueprint(1000, 64);
		blueprint.set(0, 0, 0, "stone_brick");
		llm.reply(GOOD_REPLY);
		runner.okBlueprint(blueprint);
		AtomicInteger calls = new AtomicInteger();
		BlockValidator validator = ids -> {
			calls.incrementAndGet();
			Thread.currentThread().interrupt();
			return List.of(new InvalidBlock("minecraft:stone_brick", "unknown block", List.of("minecraft:stone_bricks")));
		};

		AgentTask task = agent(pool).handle(request("wall", 2, validator));

		assertThrows(CancellationException.class, () -> task.future().get(5, TimeUnit.SECONDS));
		assertEquals(1, calls.get(), "no candidate validation after the interrupt");
		assertEquals("minecraft:stone_brick", blueprint.get(0, 0, 0));
		assertEquals(1, llm.calls.size());
		assertEquals(0, conversation.size());
	}

	@Test
	void failedReplyNoteFormat() {
		assertEquals("Here!\n\n(Note: this script failed and nothing was built: TypeError: x is not a function)",
				AiAgent.failedReply("Here!", "TypeError: x is not a function\n  more detail"));
		assertEquals("(empty reply)\n\n(Note: this script failed and nothing was built: E)", AiAgent.failedReply(" ", "E"));
		String note = AiAgent.failedReply("r", "E".repeat(500));
		assertEquals("r\n\n(Note: this script failed and nothing was built: " + "E".repeat(197) + "...)", note);
	}

	@Test
	void laterTurnsSeeThatTheFailedScriptBuiltNothing() throws Exception {
		llm.reply(GOOD_REPLY).reply("Okay.");
		runner.error("ReferenceError: \"foo\" is not defined. (line 1: foo();)");

		AgentOutcome outcome = await(agent().handle(request("tower", 0)));
		await(agent().handle(request("did it work?", 0)));

		assertNotNull(outcome.failure());
		ChatMessage stored = messagesOfCall(1).get(1);
		assertEquals(ChatMessage.assistant(GOOD_REPLY + "\n\n(Note: this script failed and nothing was built: "
				+ "ReferenceError: \"foo\" is not defined. (line 1: foo();))"), stored);
		assertEquals("Sure, here is a tower!\nEnjoy!\n\n(Note: this script failed and nothing was built: "
				+ "ReferenceError: \"foo\" is not defined. (line 1: foo();))", ResponseParser.parse(stored.content()).chatText());
	}
}
