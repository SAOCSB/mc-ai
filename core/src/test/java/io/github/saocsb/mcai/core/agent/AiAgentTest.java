package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.llm.ChatMessage;
import io.github.saocsb.mcai.core.llm.LlmException;
import io.github.saocsb.mcai.core.llm.LlmRequest;
import io.github.saocsb.mcai.core.script.ScriptLimits;
import io.github.saocsb.mcai.core.script.WorldAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
				"survival", "minecraft:diamond_sword", 64, -64, 319, 300000, List.of("Alex"));
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
		assertEquals(List.of(ChatMessage.user("Steve: build a box"), ChatMessage.assistant("Fixed it:\n" + FIXED_REPLY)),
				conversation.messages());
	}

	@Test
	void invalidBlocksAreRepaired() throws Exception {
		llm.reply("```js\nfill(0,0,0,3,3,3,'stone_brick');\n```").reply("```js\nfill(0,0,0,3,3,3,'stone_bricks');\n```");
		runner.ok("minecraft:stone_brick", "minecraft:glass").ok("minecraft:stone_bricks", "minecraft:glass");
		List<List<String>> validated = new CopyOnWriteArrayList<>();
		BlockValidator validator = ids -> {
			validated.add(List.copyOf(ids));
			return ids.contains("minecraft:stone_brick")
					? List.of(new InvalidBlock("minecraft:stone_brick", "unknown block",
							List.of("minecraft:stone_bricks", "minecraft:stone")))
					: List.of();
		};

		AgentOutcome outcome = await(agent().handle(request("wall", 2, validator)));

		String error = "Unknown or invalid block ids:\n"
				+ "- minecraft:stone_brick: unknown block (did you mean: minecraft:stone_bricks, minecraft:stone?)";
		assertNotNull(outcome.result());
		assertEquals(2, outcome.attempts());
		assertEquals(List.of(List.of("minecraft:stone_brick", "minecraft:glass"),
				List.of("minecraft:stone_bricks", "minecraft:glass")), validated);
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
		assertEquals(List.of(ChatMessage.user("Steve: build"), ChatMessage.assistant("```js\nc();\n```")),
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

		assertEquals(new AgentOutcome("I can't help with that.", null, AiAgent.FAILURE_REFUSED, 1), outcome);
		assertEquals("refused", AiAgent.FAILURE_REFUSED);
		assertEquals(1, llm.calls.size());
		assertTrue(runner.sources.isEmpty());
		assertEquals(List.of("I can't help with that."), listener.chats());
		assertEquals(List.of(ChatMessage.user("Steve: something bad"), ChatMessage.assistant("I can't help with that.")),
				conversation.messages());
	}

	@Test
	void refusalWithScriptDoesNotRunIt() throws Exception {
		llm.reply(FakeLlmClient.refused(GOOD_REPLY));

		AgentOutcome outcome = await(agent().handle(request("tower", 2)));

		assertEquals(AiAgent.FAILURE_REFUSED, outcome.failure());
		assertTrue(runner.sources.isEmpty());
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
}
