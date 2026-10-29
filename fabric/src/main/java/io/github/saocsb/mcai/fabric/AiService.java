package io.github.saocsb.mcai.fabric;

import io.github.saocsb.mcai.core.agent.AgentListener;
import io.github.saocsb.mcai.core.agent.AgentOutcome;
import io.github.saocsb.mcai.core.agent.AgentRequest;
import io.github.saocsb.mcai.core.agent.AgentStatus;
import io.github.saocsb.mcai.core.agent.AgentTask;
import io.github.saocsb.mcai.core.agent.AiAgent;
import io.github.saocsb.mcai.core.agent.GameContext;
import io.github.saocsb.mcai.core.agent.PromptBuilder;
import io.github.saocsb.mcai.core.blueprint.Blueprint;
import io.github.saocsb.mcai.core.config.ConfigIO;
import io.github.saocsb.mcai.core.config.ModConfig;
import io.github.saocsb.mcai.core.config.ProviderSettings;
import io.github.saocsb.mcai.core.i18n.Messages;
import io.github.saocsb.mcai.core.llm.LlmClient;
import io.github.saocsb.mcai.core.llm.LlmClients;
import io.github.saocsb.mcai.core.llm.LlmException;
import io.github.saocsb.mcai.core.llm.ProviderConfig;
import io.github.saocsb.mcai.core.placement.PlacementSpec;
import io.github.saocsb.mcai.core.placement.Transform;
import io.github.saocsb.mcai.core.script.ScriptEngine;
import io.github.saocsb.mcai.core.script.ScriptLimits;
import io.github.saocsb.mcai.core.script.ScriptResult;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The mod's runtime for one server: configuration, the LLM agent, per-player sessions and the build queue.
 * Every public method must be called on the server thread; agent callbacks are moved there with
 * {@link MinecraftServer#execute}.
 */
public final class AiService implements BuildManager.Listener {
	private static final Logger LOGGER = LoggerFactory.getLogger("MC-AI");
	private static final int MAX_STACK_DEPTH = 400;
	private static final int MAX_LOG_LINES = 200;
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
	private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
	/** Re-send a request's status this often (ticks); the action bar fades after about three seconds. */
	private static final int STATUS_REFRESH_TICKS = 40;

	private final MinecraftServer server;
	private final Path configFile;
	private final ScriptEngine scripts = new ScriptEngine();
	private final ExecutorService workers;
	private final McBlockValidator validator;
	private final BuildManager builds;
	private final Map<UUID, PlayerSession> sessions = new HashMap<>();
	private final Set<AgentTask> activeTasks = new HashSet<>();
	private final Queue<Runnable> nextTick = new ConcurrentLinkedQueue<>();
	private ModConfig config;
	private ProviderConfig provider;
	private AiAgent agent;
	private String systemPrompt;
	private volatile boolean stopped;

	private AiService(MinecraftServer server, Path configFile, ModConfig config) {
		this.server = server;
		this.configFile = configFile;
		this.config = config;
		this.workers = Executors.newFixedThreadPool(Math.max(2, config.maxConcurrentRequests + 1), workerThreads());
		this.validator = new McBlockValidator(server.overworld().holderLookup(Registries.BLOCK));
		this.builds = new BuildManager(() -> this.config.build, this);
		rebuildClient();
	}

	/**
	 * Loads {@code configFile} (falling back to defaults, without overwriting the file, when it is unreadable) and
	 * starts the service. Called from SERVER_STARTED.
	 */
	public static AiService start(MinecraftServer server, Path configFile) {
		ModConfig config;
		try {
			config = ConfigIO.load(configFile);
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Could not load {}; using defaults until /ai reload succeeds", configFile, e);
			config = ConfigIO.defaults();
		}
		AiService service = new AiService(server, configFile, config);
		LOGGER.info("MC-AI ready: provider {}, model {}", service.provider.id(), service.provider.model());
		return service;
	}

	/** Cancels requests, drops queued builds and stops the worker pool. Called from SERVER_STOPPING. */
	public void shutdown() {
		stopped = true;
		for (PlayerSession session : sessions.values()) {
			if (session.task != null) {
				cancelQuietly(session.task);
				session.task = null;
			}
		}
		activeTasks.clear();
		builds.clear();
		workers.shutdownNow();
	}

	/** End of every server tick: deferred chat requests, then block placement. */
	public void tick() {
		Runnable task;
		while ((task = nextTick.poll()) != null) {
			try {
				task.run();
			} catch (RuntimeException e) {
				LOGGER.error("MC-AI deferred task failed", e);
			}
		}
		try {
			builds.tick();
		} catch (RuntimeException e) {
			LOGGER.error("MC-AI build tick failed", e);
		}
		refreshStatuses();
	}

	private void refreshStatuses() {
		int now = server.getTickCount();
		if (now % 10 != 0) {
			return;
		}
		for (PlayerSession session : sessions.values()) {
			if (session.task == null || session.status == null || now - session.statusTick < STATUS_REFRESH_TICKS) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
			if (player != null) {
				player.sendOverlayMessage(session.status);
				session.statusTick = now;
			}
		}
	}

	private void showStatus(ServerPlayer player, PlayerSession session, String text) {
		session.status = Texts.overlay(text);
		session.statusTick = server.getTickCount();
		player.sendOverlayMessage(session.status);
	}

	/** Runs {@code task} at the end of the current server tick (used by the chat trigger). */
	public void runNextTick(Runnable task) {
		nextTick.add(task);
	}

	public ModConfig config() {
		return config;
	}

	/** A player left: abort their running request (builds keep going). */
	public void onDisconnect(UUID playerId) {
		PlayerSession session = sessions.get(playerId);
		if (session != null) {
			cancelTask(session);
			session.pending = null;
		}
	}

	// ---- requests -------------------------------------------------------------------------------------------

	/** Sends a player's message to the AI, after permission, busy, cooldown, concurrency and API-key checks. */
	public void ask(ServerPlayer player, String message) {
		if (stopped) {
			return;
		}
		String text = message == null ? "" : message.trim();
		if (text.isEmpty()) {
			error(player, "error.empty_message", "/ai");
			return;
		}
		if (!canUse(player)) {
			error(player, "error.no_permission");
			return;
		}
		PlayerSession session = session(player.getUUID());
		if (session.busy()) {
			error(player, "error.busy");
			return;
		}
		long now = System.currentTimeMillis();
		long wait = session.lastRequestMillis + config.cooldownSeconds * 1000L - now;
		if (wait > 0) {
			error(player, "error.cooldown", (wait + 999) / 1000);
			return;
		}
		if (activeTasks.size() >= Math.max(1, config.maxConcurrentRequests)) {
			error(player, "error.server_busy", config.aiName);
			return;
		}
		if (provider.apiKey().isBlank() && !isLocal(provider.baseUrl())) {
			error(player, "error.no_api_key", provider.id());
			return;
		}

		UUID playerId = player.getUUID();
		long requestId = ++session.requestId;
		GameContext context = GameContexts.capture(player, config.build.maxBlocks);
		ScriptLimits limits = new ScriptLimits(config.build.maxBlocks, config.build.maxSpan, config.build.scriptTimeoutMillis,
				config.commands.maxPerRequest, MAX_STACK_DEPTH, MAX_LOG_LINES);
		AgentListener listener = new AgentListener() {
			@Override
			public void onChat(String chat) {
				onServerThread(() -> deliverChat(playerId, requestId, chat));
			}

			@Override
			public void onStatus(AgentStatus status, Object... args) {
				onServerThread(() -> deliverStatus(playerId, requestId, status, args));
			}
		};
		long seed = ((long) playerId.hashCode() << 32) ^ server.getTickCount();
		AgentRequest request = new AgentRequest(session.conversation, systemPrompt(), context, text, limits,
				config.maxHistoryMessages, config.maxRepairAttempts, validator, listener, seed);
		AgentTask task;
		try {
			task = agent.handle(request);
		} catch (RuntimeException e) {
			LOGGER.error("Could not start AI request", e);
			error(player, "error.internal", String.valueOf(e.getMessage()));
			return;
		}
		session.task = task;
		session.lastRequestMillis = now;
		activeTasks.add(task);
		LOGGER.info("{} -> {}: {}", player.getScoreboardName(), config.aiName, text);
		showStatus(player, session, tr(player, "status.thinking", config.aiName));
		task.future().whenComplete((outcome, failure) ->
				onServerThread(() -> finishRequest(playerId, task, outcome, failure)));
	}

	private void deliverChat(UUID playerId, long requestId, String text) {
		PlayerSession session = sessions.get(playerId);
		if (session == null || session.requestId != requestId || text == null || text.isBlank()) {
			return;
		}
		Component line = Texts.chat(config.aiName, text);
		if ("requester".equalsIgnoreCase(config.replyVisibility)) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				player.sendSystemMessage(line);
			}
		} else {
			server.getPlayerList().broadcastSystemMessage(line, false);
		}
	}

	private void deliverStatus(UUID playerId, long requestId, AgentStatus status, Object[] args) {
		PlayerSession session = sessions.get(playerId);
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (session == null || session.requestId != requestId || session.task == null || player == null) {
			return;
		}
		String name = config.aiName;
		String text = switch (status) {
			case THINKING -> {
				int chars = intArg(args, 0);
				yield chars > 0 ? tr(player, "status.thinking_chars", name, chars) : tr(player, "status.thinking", name);
			}
			case WRITING_SCRIPT -> tr(player, "status.writing", name, intArg(args, 0));
			case RUNNING_SCRIPT -> tr(player, "status.running");
			case REPAIRING -> tr(player, "status.repairing", name, intArg(args, 0), intArg(args, 1),
					args != null && args.length > 2 ? String.valueOf(args[2]) : "");
		};
		showStatus(player, session, text);
	}

	private void finishRequest(UUID playerId, AgentTask task, AgentOutcome outcome, Throwable failure) {
		activeTasks.remove(task);
		PlayerSession session = sessions.get(playerId);
		if (session == null || session.task != task) {
			return;
		}
		session.task = null;
		session.status = null;
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player == null) {
			return;
		}
		if (failure != null) {
			reportFailure(player, unwrap(failure));
		} else if (outcome == null) {
			return;
		} else if (outcome.failure() != null) {
			if (AiAgent.FAILURE_REFUSED.equals(outcome.failure())) {
				error(player, "error.refused");
			} else if (AiAgent.TRUNCATED_ERROR.equals(outcome.failure())) {
				error(player, "error.truncated");
			} else {
				error(player, "error.script_failed", outcome.failure());
			}
		} else if (outcome.result() != null) {
			for (String warning : outcome.warnings()) {
				info(player, "build.warning", warning);
			}
			try {
				handleResult(player, session, outcome.result());
			} catch (RuntimeException e) {
				LOGGER.error("Could not plan build for {}", player.getScoreboardName(), e);
				error(player, "error.internal", String.valueOf(e.getMessage()));
			}
		}
	}

	private void reportFailure(ServerPlayer player, Throwable failure) {
		if (failure instanceof CancellationException) {
			return;
		}
		if (failure instanceof LlmException e) {
			LOGGER.warn("LLM request failed for {}: {}", player.getScoreboardName(), e.getMessage());
			int status = e.statusCode();
			if (status == 401 || status == 403) {
				error(player, "error.llm_auth", String.valueOf(status));
			} else if (status == 429) {
				error(player, "error.llm_rate");
			} else if (status == -1 && isTimeout(e)) {
				error(player, "error.llm_timeout");
			} else {
				error(player, "error.llm", String.valueOf(e.getMessage()));
			}
			return;
		}
		LOGGER.error("AI request failed for {}", player.getScoreboardName(), failure);
		error(player, "error.internal", String.valueOf(failure.getMessage()));
	}

	private void handleResult(ServerPlayer player, PlayerSession session, ScriptResult result) {
		ServerLevel level = player.level();
		Blueprint blueprint = result.blueprint();
		PlacementSpec spec = withDefaultDistance(result.placement() == null ? PlacementSpec.defaults() : result.placement());
		Transform transform = BuildPlanner.transform(spec, blueprint, BuildPlanner.pose(player),
				session.previousIn(level.dimension()), level);
		if (blueprint == null || blueprint.isEmpty()) {
			if (!result.actions().isEmpty()) {
				reportActions(player, CommandRunner.run(player, level, result.actions(), transform, config.commands));
			}
			return;
		}
		String name = result.name() == null || result.name().isBlank() ? tr(player, "build.default_name") : result.name();
		BuildJob job = BuildPlanner.plan(level, blueprint, transform, name, validator, player.getUUID(), result.actions());
		int confirmAbove = config.build.confirmAboveBlocks;
		if (confirmAbove > 0 && job.total() > confirmAbove) {
			session.pending = new PlayerSession.PendingBuild(job, spec.replacePrevious());
			player.sendSystemMessage(Texts.clickable(config.aiName, tr(player, "build.confirm", name, job.total()), "/ai confirm"));
			return;
		}
		startBuild(player, session, job, spec.replacePrevious());
	}

	/** Applies {@code build.frontDistance} when the script left the distance at the script API default. */
	private PlacementSpec withDefaultDistance(PlacementSpec spec) {
		int configured = Math.max(0, config.build.frontDistance);
		if (spec.distance() != PlacementSpec.DEFAULT_DISTANCE || configured == PlacementSpec.DEFAULT_DISTANCE) {
			return spec;
		}
		return new PlacementSpec(spec.mode(), configured, spec.ground(), spec.replacePrevious(), spec.absX(), spec.absY(),
				spec.absZ());
	}

	private void startBuild(ServerPlayer player, PlayerSession session, BuildJob job, boolean replacePrevious) {
		if (replacePrevious) {
			builds.cancel(player.getUUID());
			UndoRecord previous = session.undo.poll();
			if (previous != null) {
				builds.enqueue(BuildJob.undo(previous, player.getUUID()).silent(true));
			}
		}
		int ahead = builds.enqueue(job);
		int seconds = (int) Math.max(1, Math.ceil(job.total() / (Math.max(1, config.build.blocksPerTick) * 20.0)));
		info(player, "build.start", job.name(), job.total(), seconds);
		if (ahead > 0) {
			info(player, "build.queued", ahead);
		}
	}

	// ---- build events ---------------------------------------------------------------------------------------

	@Override
	public void progress(BuildJob job) {
		ServerPlayer player = job.owner() == null ? null : server.getPlayerList().getPlayer(job.owner());
		if (player != null) {
			player.sendOverlayMessage(Texts.overlay(tr(player, "build.progress", job.name(), job.percent(), job.done(), job.total())));
		}
	}

	@Override
	public void finished(BuildJob job, boolean cancelled) {
		UUID owner = job.owner();
		PlayerSession session = owner == null ? null : sessions.get(owner);
		ServerPlayer player = owner == null ? null : server.getPlayerList().getPlayer(owner);
		if (job.kind() == BuildJob.Kind.UNDO) {
			if (cancelled && session != null) {
				session.pushUndo(job.remainingUndo(), config.build.undoHistory);
			} else if (!cancelled && player != null && !job.silent()) {
				success(player, "undo.done", job.name());
			}
			return;
		}
		if (!cancelled && player != null) {
			double seconds = Math.round(job.elapsedMillis() / 100.0) / 10.0;
			success(player, "build.done", job.name(), job.placed(), seconds);
			if (job.skipped() > 0) {
				info(player, "build.skipped", job.skipped());
			}
			if (!job.actions().isEmpty()) {
				reportActions(player, CommandRunner.run(player, job.level(), job.actions(), job.transform(), config.commands));
			}
		}
		if (session != null) {
			session.pushUndo(job.record(), config.build.undoHistory);
			if (job.hasStarted()) {
				session.lastTransform = job.transform();
				session.lastDimension = job.level().dimension();
			}
		}
	}

	private void reportActions(ServerPlayer player, CommandRunner.Report report) {
		if (report.executed() > 0) {
			info(player, "actions.summary", report.ok(), report.failed());
		}
		if (!report.blocked().isEmpty()) {
			error(player, "actions.blocked", String.join(", ", report.blocked()));
		}
		if (report.disabled() > 0) {
			info(player, "actions.disabled", report.disabled());
		}
	}

	// ---- player commands ------------------------------------------------------------------------------------

	/** Cancels the player's request, pending confirmation and builds. */
	public void stop(ServerPlayer player) {
		PlayerSession session = sessions.get(player.getUUID());
		boolean stoppedSomething = false;
		if (session != null) {
			stoppedSomething = cancelTask(session);
			if (session.pending != null) {
				session.pending = null;
				stoppedSomething = true;
			}
		}
		if (builds.cancel(player.getUUID()) > 0) {
			stoppedSomething = true;
		}
		info(player, stoppedSomething ? "build.stopped" : "build.nothing_to_stop");
	}

	/** Reverts the player's latest build (stopping a running one first, so it can be reverted too). */
	public void undo(ServerPlayer player) {
		PlayerSession session = session(player.getUUID());
		builds.cancel(player.getUUID());
		UndoRecord record = session.undo.poll();
		if (record == null) {
			info(player, "undo.nothing");
			return;
		}
		builds.enqueue(BuildJob.undo(record, player.getUUID()));
		info(player, "undo.start", record.name(), record.size());
	}

	/** Forgets the player's conversation. */
	public void clear(ServerPlayer player) {
		session(player.getUUID()).conversation.clear();
		success(player, "cmd.cleared", config.aiName);
	}

	/** Starts the build waiting for confirmation. */
	public void confirm(ServerPlayer player) {
		PlayerSession session = sessions.get(player.getUUID());
		if (session == null || session.pending == null) {
			info(player, "build.confirm_none");
			return;
		}
		PlayerSession.PendingBuild pending = session.pending;
		session.pending = null;
		startBuild(player, session, pending.job(), pending.replacePrevious());
	}

	/** Sends the help page. */
	public void help(CommandSourceStack source) {
		source.sendSystemMessage(Texts.plain(config.aiName, Messages.tr(lang(source), "cmd.help")));
	}

	/** Tells a player who typed only the chat prefix how to use it. */
	public void emptyMessageHint(ServerPlayer player, String prefix) {
		info(player, "error.empty_message", prefix);
	}

	public void status(CommandSourceStack source) {
		String lang = lang(source);
		String keyState = Messages.tr(lang, provider.apiKey().isBlank() ? "key.unset" : "key.set");
		source.sendSystemMessage(Texts.info(config.aiName, Messages.tr(lang, "cmd.status", provider.id(), provider.model(),
				keyState, activeTasks.size(), builds.size())));
	}

	// ---- admin commands -------------------------------------------------------------------------------------

	public void reload(CommandSourceStack source) {
		String lang = lang(source);
		ModConfig loaded;
		try {
			loaded = ConfigIO.load(configFile);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Reloading {} failed", configFile, e);
			source.sendFailure(Texts.error(config.aiName, Messages.tr(lang, "cmd.reload_failed", String.valueOf(e.getMessage()))));
			return;
		}
		config = loaded;
		rebuildClient();
		source.sendSystemMessage(Texts.success(config.aiName, Messages.tr(lang, "cmd.reloaded", provider.id(), provider.model())));
	}

	public void setProvider(CommandSourceStack source, String id) {
		String lang = lang(source);
		String key = id.trim();
		if (!config.providers.containsKey(key)) {
			source.sendFailure(Texts.error(config.aiName, Messages.tr(lang, "cmd.unknown_provider", key,
					String.join(", ", config.providers.keySet()))));
			return;
		}
		config.provider = key;
		saveAndRebuild(source, lang, "provider", key);
	}

	public void setModel(CommandSourceStack source, String model) {
		activeSettings().model = model.trim();
		saveAndRebuild(source, lang(source), "model", model.trim());
	}

	/**
	 * Sets the active provider's API key ({@code -} clears it). The key is never echoed: the reply shows whether a
	 * key (from the config or its environment variable) is now in effect.
	 */
	public void setKey(CommandSourceStack source, String key) {
		String lang = lang(source);
		String value = key.trim();
		activeSettings().apiKey = "-".equals(value) ? "" : value;
		saveAndRebuild(source, lang, "key", () -> Messages.tr(lang, provider.apiKey().isBlank() ? "key.unset" : "key.set"));
	}

	public void setBaseUrl(CommandSourceStack source, String baseUrl) {
		activeSettings().baseUrl = baseUrl.trim();
		saveAndRebuild(source, lang(source), "baseurl", baseUrl.trim());
	}

	private ProviderSettings activeSettings() {
		return config.providers.computeIfAbsent(config.provider, id -> new ProviderSettings());
	}

	private void saveAndRebuild(CommandSourceStack source, String lang, String key, String shownValue) {
		saveAndRebuild(source, lang, key, () -> shownValue);
	}

	/** Persists the config, rebuilds the LLM client and confirms the change ({@code shownValue} is read afterwards). */
	private void saveAndRebuild(CommandSourceStack source, String lang, String key, Supplier<String> shownValue) {
		rebuildClient();
		try {
			ConfigIO.save(configFile, config);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Saving {} failed", configFile, e);
			source.sendFailure(Texts.error(config.aiName, Messages.tr(lang, "error.internal", String.valueOf(e.getMessage()))));
		}
		source.sendSystemMessage(Texts.success(config.aiName, Messages.tr(lang, "cmd.config_set", key, shownValue.get())));
	}

	// ---- helpers --------------------------------------------------------------------------------------------

	/**
	 * Resolves the active provider and builds a new HTTP client, LLM client and agent (requests in flight keep
	 * the old ones). HTTP/1.1 is forced because some local model servers reject the HTTP/2 upgrade on plain http.
	 * Invalid provider settings do not throw: requests then fail with a readable error until fixed.
	 */
	private void rebuildClient() {
		ProviderSettings settings = config.providers.get(config.provider);
		provider = (settings == null ? new ProviderSettings() : settings).resolve(config.provider, System::getenv);
		HttpClient.Builder http = HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.version(HttpClient.Version.HTTP_1_1);
		ProxySelector proxy = proxySelector(config.proxy);
		if (proxy != null) {
			http.proxy(proxy);
		}
		LlmClient client;
		try {
			client = LlmClients.create(provider, http.build());
		} catch (RuntimeException e) {
			LOGGER.error("Invalid settings for LLM provider '{}'", provider.id(), e);
			String reason = "invalid settings for provider '" + provider.id() + "': " + e.getMessage();
			client = (request, listener) -> CompletableFuture.failedFuture(new LlmException(reason, -1, false, e));
		}
		agent = new AiAgent(client, scripts, workers);
		systemPrompt = null;
	}

	private String systemPrompt() {
		if (systemPrompt == null) {
			systemPrompt = PromptBuilder.systemPrompt(config.aiName, config.build.maxBlocks, config.commands.enabled,
					config.commands.whitelist, config.extraInstructions);
		}
		return systemPrompt;
	}

	private PlayerSession session(UUID playerId) {
		return sessions.computeIfAbsent(playerId, PlayerSession::new);
	}

	/** Cancels the session's running request; true if there was one. */
	private boolean cancelTask(PlayerSession session) {
		AgentTask task = session.task;
		if (task == null) {
			return false;
		}
		session.task = null;
		session.status = null;
		session.requestId++;
		activeTasks.remove(task);
		cancelQuietly(task);
		return true;
	}

	private static void cancelQuietly(AgentTask task) {
		try {
			task.cancel();
		} catch (RuntimeException e) {
			LOGGER.warn("Cancelling an AI request failed", e);
		}
	}

	/** Runs {@code task} on the server thread unless the service has stopped meanwhile. */
	private void onServerThread(Runnable task) {
		if (stopped) {
			return;
		}
		server.execute(() -> {
			if (!stopped && server.isSameThread()) {
				task.run();
			}
		});
	}

	/** Whether the player may talk to the AI ({@code permissions.useLevel}). */
	public boolean canUse(ServerPlayer player) {
		return hasLevel(player.createCommandSourceStack().permissions(), config.permissions.useLevel);
	}

	/** Whether a command source may run admin commands ({@code permissions.adminLevel}). */
	public boolean isAdmin(CommandSourceStack source) {
		return hasLevel(source.permissions(), config.permissions.adminLevel);
	}

	/** Op-level check: 0 = everyone, 1..4 = moderator, game master, admin, owner. */
	public static boolean hasLevel(PermissionSet permissions, int level) {
		return switch (Math.max(0, Math.min(4, level))) {
			case 0 -> true;
			case 1 -> permissions.hasPermission(Permissions.COMMANDS_MODERATOR);
			case 2 -> permissions.hasPermission(Permissions.COMMANDS_GAMEMASTER);
			case 3 -> permissions.hasPermission(Permissions.COMMANDS_ADMIN);
			default -> permissions.hasPermission(Permissions.COMMANDS_OWNER);
		};
	}

	String lang(ServerPlayer player) {
		return Messages.resolveLanguage(config.language, player.clientInformation().language());
	}

	String lang(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return player != null ? lang(player) : Messages.resolveLanguage(config.language, "en_us");
	}

	private String tr(ServerPlayer player, String key, Object... args) {
		return Messages.tr(lang(player), key, args);
	}

	private void info(ServerPlayer player, String key, Object... args) {
		player.sendSystemMessage(Texts.info(config.aiName, tr(player, key, args)));
	}

	private void success(ServerPlayer player, String key, Object... args) {
		player.sendSystemMessage(Texts.success(config.aiName, tr(player, key, args)));
	}

	private void error(ServerPlayer player, String key, Object... args) {
		player.sendSystemMessage(Texts.error(config.aiName, tr(player, key, args)));
	}

	private static int intArg(Object[] args, int index) {
		return args != null && args.length > index && args[index] instanceof Number n ? n.intValue() : 0;
	}

	private static Throwable unwrap(Throwable failure) {
		Throwable t = failure;
		while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
			t = t.getCause();
		}
		return t;
	}

	private static boolean isTimeout(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof HttpTimeoutException || t instanceof TimeoutException || t instanceof SocketTimeoutException) {
				return true;
			}
		}
		return false;
	}

	/** True for base URLs on this machine (local model servers need no API key). */
	static boolean isLocal(String baseUrl) {
		try {
			String host = URI.create(baseUrl.trim()).getHost();
			return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Parses {@code host:port} (an optional {@code http://} prefix is ignored); null when blank or invalid. */
	static ProxySelector proxySelector(String spec) {
		if (spec == null || spec.isBlank()) {
			return null;
		}
		String value = spec.trim().replaceFirst("^(?i)https?://", "");
		if (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		int colon = value.lastIndexOf(':');
		try {
			if (colon <= 0) {
				throw new IllegalArgumentException(spec);
			}
			String host = value.substring(0, colon);
			int port = Integer.parseInt(value.substring(colon + 1));
			return ProxySelector.of(new InetSocketAddress(host, port));
		} catch (RuntimeException e) {
			LOGGER.warn("Ignoring invalid proxy '{}' (expected host:port)", spec);
			return null;
		}
	}

	private static ThreadFactory workerThreads() {
		AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, "mcai-worker-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}
}
