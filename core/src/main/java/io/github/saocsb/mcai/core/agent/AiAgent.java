package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.blueprint.BlockFixer;
import io.github.saocsb.mcai.core.blueprint.BlockIds;
import io.github.saocsb.mcai.core.blueprint.Blueprint;
import io.github.saocsb.mcai.core.llm.ChatMessage;
import io.github.saocsb.mcai.core.llm.LlmClient;
import io.github.saocsb.mcai.core.llm.LlmRequest;
import io.github.saocsb.mcai.core.llm.LlmResponse;
import io.github.saocsb.mcai.core.script.ScriptException;
import io.github.saocsb.mcai.core.script.ScriptLimits;
import io.github.saocsb.mcai.core.script.ScriptResult;
import io.github.saocsb.mcai.core.script.ScriptRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

/**
 * Orchestrates one player request:
 * <ol>
 * <li>messages = conversation.view(maxHistory) + user turn ({@link PromptBuilder#userTurn}); call the LLM,
 *     forwarding progress as THINKING / WRITING_SCRIPT statuses (WRITING_SCRIPT once the partial text contains an
 *     opening code fence; throttled to 1/s).</li>
 * <li>Parse with {@link ResponseParser}. First reply: deliver non-empty chat text via {@code onChat}.</li>
 * <li>If refused: outcome with failure, no repair. If no script: outcome with result null and failure null.</li>
 * <li>Run the script on {@code scriptExecutor} (status RUNNING_SCRIPT). Errors are: unterminated/truncated
 *     reply, {@link io.github.saocsb.mcai.core.script.ScriptException}, an empty blueprint with no world actions,
 *     or invalid palette entries from the validator that could not be auto-fixed (message lists each such id,
 *     reason and suggestions).</li>
 * <li>On error with repairs left: status REPAIRING, append the assistant reply + {@link PromptBuilder#repairTurn}
 *     to the request messages (not to the conversation) and call again. Otherwise return the failure.</li>
 * <li>On success or final failure, append to the conversation exactly two messages: the user turn (just the
 *     player's message text, prefixed with "{playerName}: ", without the context block) and the final assistant
 *     reply: the last reply received, except after a success that needed repairs, where it is the first
 *     reply's chat text followed by the script that worked as a {@code ```js} block (so later turns see both
 *     what was said and what actually ran).</li>
 * </ol>
 *
 * <p>Invalid block ids (still on the script executor, after the validator rejected some palette entries):
 * <ol>
 * <li>Auto-fix: for each invalid id the candidates are {@link BlockFixer#candidates} followed by
 *     {@link BlockFixer#suggestionCandidates} of the validator's suggestions (same family, distance at most 2). All
 *     candidates are validated in one extra validator call; the first valid candidate per id replaces it in the
 *     blueprint ({@code replaceAll}) with the warning {@code "auto-fixed block 'x' -> 'y'"}.</li>
 * <li>Ids without a valid candidate go to the repair round-trip (the message lists only those). On the last
 *     attempt they are removed from the blueprint instead, with the warning
 *     {@code "removed N blocks with unknown id 'x'"} per id; the request then succeeds if a non-air block or a
 *     world action remains, and otherwise fails with the invalid-blocks message as before.</li>
 * </ol>
 * Warnings name ids without the {@code minecraft:} namespace and are returned in
 * {@link AgentOutcome#warnings()} of successful outcomes (in the validator's order, fixes before removals).
 *
 * <p>LLM exceptions complete the future exceptionally with the {@link io.github.saocsb.mcai.core.llm.LlmException}
 * (unwrapped from CompletionException); nothing is added to the conversation then.
 *
 * <p>Details:
 * <ul>
 * <li>A THINKING status with 0 characters is sent when the request starts. While the first reply streams in,
 *     THINKING carries text + reasoning characters received and WRITING_SCRIPT the code lines written so far;
 *     each kind is sent at most once per second (the first WRITING_SCRIPT immediately). During repair calls
 *     REPAIRING stands in for THINKING, so only WRITING_SCRIPT progress is sent.</li>
 * <li>REPAIRING args: attempt = number of the upcoming LLM call (2 for the first repair), maxAttempts =
 *     maxRepairs + 1, and the first line of the error cut to 100 characters.</li>
 * <li>A truncated reply or an unclosed code fence only counts as an error when the reply has a script. A first
 *     reply with neither chat text nor script, and a repair reply without a script, are errors too.</li>
 * <li>Refusals finish with failure {@link #FAILURE_REFUSED} and are recorded in the conversation like the
 *     other outcomes. Failures of the script runner other than {@link ScriptException}, of the validator or of
 *     the executor complete the future exceptionally, without a conversation update.</li>
 * <li>{@link AgentTask#cancel()} (or cancelling {@link AgentTask#future()}) cancels the in-flight LLM future,
 *     interrupts a running script, and completes the future with CancellationException. No listener callback
 *     starts after that, and the conversation is not changed.</li>
 * <li>Listener methods are never called while the agent holds a lock; exceptions they throw are logged and
 *     ignored.</li>
 * </ul>
 */
public final class AiAgent {
	/** {@link AgentOutcome#failure()} when the model refused the request. */
	public static final String FAILURE_REFUSED = "refused";

	/** Repair feedback (and, after the last attempt, the outcome failure) for a reply cut off by the token limit. */
	public static final String TRUNCATED_ERROR = "Your reply was cut off (output token limit) before the script ended.";
	static final String EMPTY_RESULT_ERROR = "The script ran but placed no blocks and requested no actions.";
	static final String EMPTY_REPLY_ERROR = "Your reply was empty.";
	static final String NO_SCRIPT_ERROR = "Your reply did not contain a ```js code block with the script.";
	static final String INVALID_BLOCKS_HEADER = "Unknown or invalid block ids:";
	static final long STATUS_INTERVAL_NANOS = 1_000_000_000L;
	static final int SHORT_ERROR_LENGTH = 100;

	private static final System.Logger LOG = System.getLogger(AiAgent.class.getName());
	private static final AgentListener NO_LISTENER = new AgentListener() {
		@Override
		public void onChat(String text) {
		}

		@Override
		public void onStatus(AgentStatus status, Object... args) {
		}
	};

	/** Access to a result's blueprint; a seam so tests can run without a real {@code Blueprint}. */
	interface BlueprintInspector {
		BlueprintInspector DEFAULT = new BlueprintInspector() {
			@Override
			public boolean isEmpty(ScriptResult result) {
				return result.blueprint() == null || result.blueprint().isEmpty();
			}

			@Override
			public List<String> palette(ScriptResult result) {
				return result.blueprint() == null ? List.of() : result.blueprint().palette();
			}

			@Override
			public int replaceAll(ScriptResult result, String from, String to) {
				return result.blueprint() == null ? 0 : result.blueprint().replaceAll(from, to);
			}

			@Override
			public Map<String, Integer> removeAll(ScriptResult result, Set<String> blockIds) {
				Blueprint blueprint = result.blueprint();
				if (blueprint == null || blockIds.isEmpty()) {
					return Map.of();
				}
				Map<String, Integer> counts = new HashMap<>();
				IntStream.Builder xyz = IntStream.builder();
				blueprint.forEach((x, y, z, id) -> {
					if (blockIds.contains(id)) {
						counts.merge(id, 1, Integer::sum);
						xyz.add(x).add(y).add(z);
					}
				});
				int[] positions = xyz.build().toArray();
				for (int i = 0; i < positions.length; i += 3) {
					blueprint.remove(positions[i], positions[i + 1], positions[i + 2]);
				}
				return counts;
			}
		};

		boolean isEmpty(ScriptResult result);

		List<String> palette(ScriptResult result);

		/** Replaces every use of palette entry {@code from} with {@code to}; returns the positions changed. */
		int replaceAll(ScriptResult result, String from, String to);

		/** Removes every position whose id is in {@code blockIds}; returns the positions removed per id. */
		Map<String, Integer> removeAll(ScriptResult result, Set<String> blockIds);
	}

	private final LlmClient client;
	private final ScriptRunner scripts;
	private final Executor scriptExecutor;
	private final BlueprintInspector inspector;
	private final LongSupplier nanoClock;

	public AiAgent(LlmClient client, ScriptRunner scripts, Executor scriptExecutor) {
		this(client, scripts, scriptExecutor, BlueprintInspector.DEFAULT, System::nanoTime);
	}

	AiAgent(LlmClient client, ScriptRunner scripts, Executor scriptExecutor, BlueprintInspector inspector,
			LongSupplier nanoClock) {
		this.client = Objects.requireNonNull(client, "client");
		this.scripts = Objects.requireNonNull(scripts, "scripts");
		this.scriptExecutor = Objects.requireNonNull(scriptExecutor, "scriptExecutor");
		this.inspector = Objects.requireNonNull(inspector, "inspector");
		this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
	}

	/** Starts processing a request; never throws for failures inside the pipeline (they complete the future). */
	public AgentTask handle(AgentRequest request) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(request.conversation(), "conversation");
		Objects.requireNonNull(request.context(), "context");
		Run run = new Run(request);
		run.start();
		return run;
	}

	/** First line of an error, cut to {@link #SHORT_ERROR_LENGTH} characters. */
	static String shortError(String error) {
		String line = error == null ? "" : error.strip();
		int nl = line.indexOf('\n');
		if (nl >= 0) {
			line = line.substring(0, nl).strip();
		}
		return line.length() <= SHORT_ERROR_LENGTH ? line : line.substring(0, SHORT_ERROR_LENGTH - 3) + "...";
	}

	/** {@code "Unknown or invalid block ids:\n- <id>: <reason> (did you mean: a, b?)"}, one line per entry. */
	static String invalidBlocksMessage(List<InvalidBlock> invalid) {
		StringBuilder sb = new StringBuilder(INVALID_BLOCKS_HEADER);
		for (InvalidBlock b : invalid) {
			String reason = b.reason() == null || b.reason().isBlank() ? "unknown block" : b.reason().strip();
			sb.append("\n- ").append(b.blockId()).append(": ").append(reason);
			if (b.suggestions() != null && !b.suggestions().isEmpty()) {
				sb.append(" (did you mean: ").append(String.join(", ", b.suggestions())).append("?)");
			}
		}
		return sb.toString();
	}

	/** {@code "auto-fixed block '<from>' -> '<to>'"} with ids shown by {@link #displayId}. */
	static String autoFixedWarning(String from, String to) {
		return "auto-fixed block '" + displayId(from) + "' -> '" + displayId(to) + "'";
	}

	/** {@code "removed <n> blocks with unknown id '<id>'"} ("1 block" for one). */
	static String removedWarning(int count, String id) {
		return "removed " + count + (count == 1 ? " block" : " blocks") + " with unknown id '" + displayId(id) + "'";
	}

	/** A block id for player-facing text: without the {@code minecraft:} namespace. */
	static String displayId(String id) {
		return id != null && id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : String.valueOf(id);
	}

	/**
	 * The assistant message stored after a success that needed repairs: the first reply's chat text, a blank line,
	 * then the working script in a {@code js} fence (longer than any backtick run in the script); only the fenced
	 * script when there is no chat text.
	 */
	static String repairedReply(String chatText, String script) {
		String code = script == null ? "" : script;
		int longest = 0;
		for (int i = 0, run = 0; i < code.length(); i++) {
			run = code.charAt(i) == '`' ? run + 1 : 0;
			longest = Math.max(longest, run);
		}
		String fence = "`".repeat(Math.max(3, longest + 1));
		String block = fence + "js\n" + code + "\n" + fence;
		return chatText == null || chatText.isBlank() ? block : chatText + "\n\n" + block;
	}

	private static String normalizeOrSelf(String id) {
		if (id == null) {
			return null;
		}
		try {
			return BlockIds.normalize(id);
		} catch (IllegalArgumentException e) {
			return id;
		}
	}

	private static Throwable unwrap(Throwable t) {
		Throwable e = t;
		while ((e instanceof CompletionException || e instanceof ExecutionException) && e.getCause() != null) {
			e = e.getCause();
		}
		return e;
	}

	/** The last lines the failed script logged, formatted for the repair prompt; null when it logged nothing. */
	static String logTail(List<String> logs) {
		if (logs == null || logs.isEmpty()) {
			return null;
		}
		int from = Math.max(0, logs.size() - 20);
		StringBuilder out = new StringBuilder("Script log before the error");
		out.append(from > 0 ? " (last 20 lines):" : ":");
		for (String line : logs.subList(from, logs.size())) {
			out.append('\n').append(line);
		}
		return out.toString();
	}

	private static String nonBlank(String reply) {
		return reply == null || reply.isBlank() ? "(empty reply)" : reply;
	}

	/** @param detail extra context for the model only (e.g. the script's log output), or null */
	private record Check(ScriptResult result, String error, String detail, List<String> warnings) {
		static Check failed(String error) {
			return new Check(null, error, null, List.of());
		}

		static Check failed(String error, String detail) {
			return new Check(null, error, detail, List.of());
		}
	}

	/** Progress state of one LLM call; guarded by the run's lock. */
	private static final class CallState {
		final FenceTracker fences = new FenceTracker();
		final boolean repair;
		boolean done;

		CallState(boolean repair) {
			this.repair = repair;
		}
	}

	/** One request in flight. */
	private final class Run implements AgentTask {
		private final AgentRequest req;
		private final AgentListener listener;
		private final BlockValidator validator;
		private final ScriptLimits limits;
		private final int maxAttempts;
		private final CompletableFuture<AgentOutcome> future = new CompletableFuture<>();
		/** Request messages: history view, user turn, then (assistant reply, repair turn) pairs. */
		private final List<ChatMessage> messages = new ArrayList<>();
		private final Object lock = new Object();

		// Guarded by lock.
		private boolean cancelled;
		private boolean finished;
		private CompletableFuture<LlmResponse> llmCall;
		private FutureTask<Check> scriptRun;
		private long lastThinking;
		private long lastWriting;
		private boolean thinkingSent;
		private boolean writingSent;

		// Pipeline state: touched by one stage at a time, handed over through futures and the executor.
		private volatile int attempts;
		private volatile String firstChat = "";
		private volatile String lastReply = "";
		/** The script most recently handed to the script executor. */
		private volatile String lastScript = "";

		Run(AgentRequest req) {
			this.req = req;
			this.listener = req.listener() != null ? req.listener() : NO_LISTENER;
			this.validator = req.validator() != null ? req.validator() : BlockValidator.ACCEPT_ALL;
			this.limits = req.limits() != null ? req.limits() : ScriptLimits.defaults();
			this.maxAttempts = Math.max(0, req.maxRepairs()) + 1;
			future.whenComplete((r, e) -> {
				if (future.isCancelled()) {
					abort();
				}
			});
		}

		@Override
		public CompletableFuture<AgentOutcome> future() {
			return future;
		}

		@Override
		public void cancel() {
			synchronized (lock) {
				if (cancelled || finished) {
					return;
				}
				cancelled = true;
			}
			future.cancel(false);
			abort();
		}

		private void abort() {
			CompletableFuture<LlmResponse> call;
			FutureTask<Check> run;
			synchronized (lock) {
				cancelled = true;
				call = llmCall;
				run = scriptRun;
				llmCall = null;
				scriptRun = null;
			}
			if (call != null) {
				call.cancel(true);
			}
			if (run != null) {
				run.cancel(true);
			}
		}

		void start() {
			try {
				messages.addAll(req.conversation().view(Math.max(0, req.maxHistory())));
				messages.add(ChatMessage.user(PromptBuilder.userTurn(req.context(), req.message())));
				synchronized (lock) {
					thinkingSent = true;
					lastThinking = nanoClock.getAsLong();
				}
				status(AgentStatus.THINKING, 0);
				callLlm();
			} catch (Throwable t) {
				fail(t);
			}
		}

		private void callLlm() {
			if (!active()) {
				return;
			}
			attempts++;
			LlmRequest request = new LlmRequest(req.systemPrompt(), List.copyOf(messages));
			CallState state = new CallState(attempts > 1);
			CompletableFuture<LlmResponse> call;
			try {
				call = client.complete(request, (textChars, reasoningChars, partialText) ->
						onProgress(state, textChars + reasoningChars, partialText));
				if (call == null) {
					throw new IllegalStateException("LlmClient returned no future");
				}
			} catch (Throwable t) {
				fail(t);
				return;
			}
			boolean cancelNow;
			synchronized (lock) {
				cancelNow = cancelled;
				if (!cancelNow) {
					llmCall = call;
				}
			}
			if (cancelNow) {
				call.cancel(true);
				return;
			}
			call.whenComplete((response, error) -> onLlmDone(call, state, response, error));
		}

		private void onProgress(CallState state, int chars, String partialText) {
			AgentStatus status;
			int arg;
			synchronized (lock) {
				if (cancelled || finished || state.done) {
					return;
				}
				long now = nanoClock.getAsLong();
				boolean writingDue = !writingSent || now - lastWriting >= STATUS_INTERVAL_NANOS;
				boolean thinkingDue = !state.repair && !state.fences.writing()
						&& (!thinkingSent || now - lastThinking >= STATUS_INTERVAL_NANOS);
				if (!writingDue && !thinkingDue) {
					return;
				}
				int lines = state.fences.update(partialText == null ? "" : partialText);
				if (lines >= 0) {
					if (!writingDue) {
						return;
					}
					writingSent = true;
					lastWriting = now;
					status = AgentStatus.WRITING_SCRIPT;
					arg = lines;
				} else {
					if (!thinkingDue) {
						return;
					}
					thinkingSent = true;
					lastThinking = now;
					status = AgentStatus.THINKING;
					arg = chars;
				}
			}
			status(status, arg);
		}

		private void onLlmDone(CompletableFuture<LlmResponse> call, CallState state, LlmResponse response,
				Throwable error) {
			synchronized (lock) {
				state.done = true;
				if (llmCall == call) {
					llmCall = null;
				}
				if (cancelled) {
					return;
				}
			}
			if (error != null) {
				fail(unwrap(error));
				return;
			}
			try {
				onReply(response);
			} catch (Throwable t) {
				fail(t);
			}
		}

		private void onReply(LlmResponse response) {
			String text = response == null || response.text() == null ? "" : response.text();
			boolean truncated = response != null && response.truncated();
			boolean refused = response != null && response.refused();
			lastReply = text;
			ParsedResponse parsed = ResponseParser.parse(text);
			boolean first = attempts == 1;
			if (first) {
				firstChat = parsed.chatText();
				if (!firstChat.isEmpty()) {
					chat(firstChat);
				}
			}
			if (refused) {
				finish(new AgentOutcome(firstChat, null, FAILURE_REFUSED, attempts, List.of()), nonBlank(lastReply));
			} else if (parsed.script() == null) {
				if (first && !parsed.chatText().isEmpty()) {
					finish(new AgentOutcome(firstChat, null, null, attempts, List.of()), nonBlank(lastReply));
				} else {
					onError(truncated ? TRUNCATED_ERROR : first ? EMPTY_REPLY_ERROR : NO_SCRIPT_ERROR);
				}
			} else if (truncated || parsed.unterminatedCode()) {
				onError(TRUNCATED_ERROR);
			} else {
				runScript(parsed.script());
			}
		}

		private void runScript(String script) {
			lastScript = script;
			status(AgentStatus.RUNNING_SCRIPT);
			FutureTask<Check> task = new FutureTask<>(() -> check(script)) {
				@Override
				protected void done() {
					onScriptDone(this);
				}
			};
			synchronized (lock) {
				if (cancelled) {
					return;
				}
				scriptRun = task;
			}
			try {
				scriptExecutor.execute(task);
			} catch (Throwable t) {
				synchronized (lock) {
					if (scriptRun == task) {
						scriptRun = null;
					}
				}
				fail(t);
			}
		}

		/** Runs on the script executor: script, then the empty check, then block validation and auto-fixing. */
		private Check check(String script) {
			ScriptResult result;
			try {
				result = scripts.run(script, limits, req.seed());
			} catch (ScriptException e) {
				if (e.kind() == ScriptException.Kind.CANCELLED) {
					throw new CancellationException(e.getMessage());
				}
				String message = e.getMessage();
				return Check.failed(message == null || message.isBlank() ? e.kind().name() : message, logTail(e.logs()));
			}
			if (result == null) {
				throw new IllegalStateException("ScriptRunner returned no result");
			}
			if (!hasActions(result) && inspector.isEmpty(result)) {
				return Check.failed(EMPTY_RESULT_ERROR);
			}
			List<String> palette = inspector.palette(result);
			if (palette == null || palette.isEmpty()) {
				return new Check(result, null, null, List.of());
			}
			List<InvalidBlock> invalid = validator.validate(palette);
			if (invalid == null || invalid.isEmpty()) {
				return new Check(result, null, null, List.of());
			}
			List<InvalidBlock> unfixed = new ArrayList<>();
			Map<String, String> fixes = findFixes(invalid, unfixed);
			boolean lastAttempt = attempts >= maxAttempts;
			if (!unfixed.isEmpty() && !lastAttempt) {
				return Check.failed(invalidBlocksMessage(unfixed));
			}
			List<String> warnings = new ArrayList<>();
			fixes.forEach((from, to) -> {
				if (inspector.replaceAll(result, from, to) > 0) {
					warnings.add(autoFixedWarning(from, to));
				}
			});
			if (!unfixed.isEmpty()) {
				Set<String> ids = new LinkedHashSet<>();
				for (InvalidBlock b : unfixed) {
					if (b.blockId() != null) {
						ids.add(normalizeOrSelf(b.blockId()));
					}
				}
				Map<String, Integer> removed = inspector.removeAll(result, ids);
				for (String id : ids) {
					int count = removed == null ? 0 : removed.getOrDefault(id, 0);
					if (count > 0) {
						warnings.add(removedWarning(count, id));
					}
				}
				if (!hasActions(result) && !hasSolidBlocks(result)) {
					return Check.failed(invalidBlocksMessage(unfixed));
				}
			}
			return new Check(result, null, null, warnings);
		}

		/**
		 * The first valid auto-fix candidate per invalid id (normalized invalid id -> replacement, in report order),
		 * found with one validator call over all candidates. Entries without a valid candidate go to {@code unfixed}.
		 */
		private Map<String, String> findFixes(List<InvalidBlock> invalid, List<InvalidBlock> unfixed) {
			Map<InvalidBlock, List<String>> options = new LinkedHashMap<>();
			Set<String> all = new LinkedHashSet<>();
			for (InvalidBlock b : invalid) {
				if (b == null) {
					continue;
				}
				String id = normalizeOrSelf(b.blockId());
				Set<String> candidates = new LinkedHashSet<>(BlockFixer.candidates(id));
				candidates.addAll(BlockFixer.suggestionCandidates(id, b.suggestions()));
				options.put(b, List.copyOf(candidates));
				all.addAll(candidates);
			}
			Set<String> rejected = new HashSet<>();
			if (!all.isEmpty()) {
				List<InvalidBlock> bad = validator.validate(List.copyOf(all));
				if (bad != null) {
					for (InvalidBlock b : bad) {
						if (b != null && b.blockId() != null) {
							rejected.add(normalizeOrSelf(b.blockId()));
						}
					}
				}
			}
			Map<String, String> fixes = new LinkedHashMap<>();
			options.forEach((b, candidates) -> {
				String fix = candidates.stream().filter(c -> !rejected.contains(c)).findFirst().orElse(null);
				if (fix == null) {
					unfixed.add(b);
				} else {
					fixes.putIfAbsent(normalizeOrSelf(b.blockId()), fix);
				}
			});
			return fixes;
		}

		private boolean hasActions(ScriptResult result) {
			return result.actions() != null && !result.actions().isEmpty();
		}

		private boolean hasSolidBlocks(ScriptResult result) {
			List<String> palette = inspector.palette(result);
			return palette != null && palette.stream().anyMatch(id -> !BlockIds.isAir(id));
		}

		private void onScriptDone(FutureTask<Check> task) {
			synchronized (lock) {
				if (scriptRun == task) {
					scriptRun = null;
				}
				if (cancelled) {
					return;
				}
			}
			if (task.isCancelled()) {
				fail(new CancellationException("script run cancelled"));
				return;
			}
			Check check;
			try {
				check = task.get();
			} catch (ExecutionException e) {
				fail(e.getCause() != null ? e.getCause() : e);
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail(e);
				return;
			} catch (Throwable t) {
				fail(t);
				return;
			}
			try {
				if (check.error() != null) {
					onError(check.error(), check.detail());
				} else {
					String stored = attempts > 1 ? repairedReply(firstChat, lastScript) : nonBlank(lastReply);
					finish(new AgentOutcome(firstChat, check.result(), null, attempts, check.warnings()), stored);
				}
			} catch (Throwable t) {
				fail(t);
			}
		}

		private void onError(String error) {
			onError(error, null);
		}

		private void onError(String error, String detail) {
			if (attempts >= maxAttempts) {
				finish(new AgentOutcome(firstChat, null, error, attempts, List.of()), nonBlank(lastReply));
				return;
			}
			int attempt = attempts + 1;
			status(AgentStatus.REPAIRING, attempt, maxAttempts, shortError(error));
			messages.add(ChatMessage.assistant(nonBlank(lastReply)));
			String feedback = detail == null ? error : error + "\n\n" + detail;
			messages.add(ChatMessage.user(PromptBuilder.repairTurn(feedback, attempt, maxAttempts)));
			callLlm();
		}

		/** Completes the request, recording the user turn and {@code assistantContent} in the conversation. */
		private void finish(AgentOutcome outcome, String assistantContent) {
			synchronized (lock) {
				if (cancelled || finished) {
					return;
				}
				finished = true;
			}
			Conversation conversation = req.conversation();
			try {
				synchronized (conversation) {
					conversation.add(ChatMessage.user(PromptBuilder.speakerLine(req.context().playerName(), req.message())));
					conversation.add(ChatMessage.assistant(assistantContent));
				}
			} catch (Throwable t) {
				future.completeExceptionally(t);
				return;
			}
			future.complete(outcome);
		}

		private void fail(Throwable error) {
			synchronized (lock) {
				if (cancelled || finished) {
					return;
				}
				finished = true;
			}
			future.completeExceptionally(error);
		}

		private boolean active() {
			synchronized (lock) {
				return !cancelled && !finished;
			}
		}

		private void chat(String text) {
			if (!active()) {
				return;
			}
			try {
				listener.onChat(text);
			} catch (RuntimeException e) {
				LOG.log(System.Logger.Level.WARNING, "AgentListener.onChat failed", e);
			}
		}

		private void status(AgentStatus status, Object... args) {
			if (!active()) {
				return;
			}
			try {
				listener.onStatus(status, args);
			} catch (RuntimeException e) {
				LOG.log(System.Logger.Level.WARNING, "AgentListener.onStatus failed", e);
			}
		}
	}
}
