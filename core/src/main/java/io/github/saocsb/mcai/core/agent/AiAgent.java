package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.llm.ChatMessage;
import io.github.saocsb.mcai.core.llm.LlmClient;
import io.github.saocsb.mcai.core.llm.LlmRequest;
import io.github.saocsb.mcai.core.llm.LlmResponse;
import io.github.saocsb.mcai.core.script.ScriptException;
import io.github.saocsb.mcai.core.script.ScriptLimits;
import io.github.saocsb.mcai.core.script.ScriptResult;
import io.github.saocsb.mcai.core.script.ScriptRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.LongSupplier;

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
 *     or invalid palette entries from the validator (message lists each id, reason and suggestions).</li>
 * <li>On error with repairs left: status REPAIRING, append the assistant reply + {@link PromptBuilder#repairTurn}
 *     to the request messages (not to the conversation) and call again. Otherwise return the failure.</li>
 * <li>On success or final failure, append to the conversation exactly two messages: the user turn (just the
 *     player's message text, prefixed with "{playerName}: ", without the context block) and the final assistant
 *     reply (the last reply received; on success the one whose script worked).</li>
 * </ol>
 * LLM exceptions complete the future exceptionally with the {@link io.github.saocsb.mcai.core.llm.LlmException}
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

	static final String TRUNCATED_ERROR = "Your reply was cut off (output token limit) before the script ended.";
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

	/** Read access to a result's blueprint; a seam so tests can run without a real {@code Blueprint}. */
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
		};

		boolean isEmpty(ScriptResult result);

		List<String> palette(ScriptResult result);
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

	private static Throwable unwrap(Throwable t) {
		Throwable e = t;
		while ((e instanceof CompletionException || e instanceof ExecutionException) && e.getCause() != null) {
			e = e.getCause();
		}
		return e;
	}

	private static String nonBlank(String reply) {
		return reply == null || reply.isBlank() ? "(empty reply)" : reply;
	}

	private record Check(ScriptResult result, String error) {
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
				finish(new AgentOutcome(firstChat, null, FAILURE_REFUSED, attempts));
			} else if (parsed.script() == null) {
				if (first && !parsed.chatText().isEmpty()) {
					finish(new AgentOutcome(firstChat, null, null, attempts));
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

		/** Runs on the script executor: script, then the empty check, then block validation. */
		private Check check(String script) {
			ScriptResult result;
			try {
				result = scripts.run(script, limits, req.seed());
			} catch (ScriptException e) {
				if (e.kind() == ScriptException.Kind.CANCELLED) {
					throw new CancellationException(e.getMessage());
				}
				String message = e.getMessage();
				return new Check(null, message == null || message.isBlank() ? e.kind().name() : message);
			}
			if (result == null) {
				throw new IllegalStateException("ScriptRunner returned no result");
			}
			if ((result.actions() == null || result.actions().isEmpty()) && inspector.isEmpty(result)) {
				return new Check(null, EMPTY_RESULT_ERROR);
			}
			List<String> palette = inspector.palette(result);
			if (palette != null && !palette.isEmpty()) {
				List<InvalidBlock> invalid = validator.validate(palette);
				if (invalid != null && !invalid.isEmpty()) {
					return new Check(null, invalidBlocksMessage(invalid));
				}
			}
			return new Check(result, null);
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
					onError(check.error());
				} else {
					finish(new AgentOutcome(firstChat, check.result(), null, attempts));
				}
			} catch (Throwable t) {
				fail(t);
			}
		}

		private void onError(String error) {
			if (attempts >= maxAttempts) {
				finish(new AgentOutcome(firstChat, null, error, attempts));
				return;
			}
			int attempt = attempts + 1;
			status(AgentStatus.REPAIRING, attempt, maxAttempts, shortError(error));
			messages.add(ChatMessage.assistant(nonBlank(lastReply)));
			messages.add(ChatMessage.user(PromptBuilder.repairTurn(error, attempt, maxAttempts)));
			callLlm();
		}

		private void finish(AgentOutcome outcome) {
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
					conversation.add(ChatMessage.assistant(nonBlank(lastReply)));
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
