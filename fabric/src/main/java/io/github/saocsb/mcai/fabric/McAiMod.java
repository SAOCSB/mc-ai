package io.github.saocsb.mcai.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Mod entrypoint: wires Fabric events to the {@link AiService} of the running server. Everything is server-side,
 * so vanilla clients can join.
 */
public final class McAiMod implements ModInitializer {
	public static final String MOD_ID = "mcai";
	public static final Logger LOGGER = LoggerFactory.getLogger("MC-AI");
	private static final String CONFIG_FILE = "mcai.json";

	private static volatile AiService service;

	/** The service of the running server, or null before SERVER_STARTED / after SERVER_STOPPING. */
	public static AiService service() {
		return service;
	}

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> AiCommands.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Path config = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
			try {
				service = AiService.start(server, config);
			} catch (RuntimeException e) {
				LOGGER.error("MC-AI failed to start", e);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			AiService current = service;
			service = null;
			if (current != null) {
				current.shutdown();
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			AiService current = service;
			if (current != null) {
				current.tick();
			}
		});
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, boundChatType) -> {
			AiService current = service;
			// Chat produced by the AI's own commands (Fabric fires this event for /say and similar run as the
			// player) must never start another AI request.
			if (current == null || CommandRunner.isExecuting()) {
				return true;
			}
			try {
				return ChatTrigger.onChat(current, sender, message.signedContent());
			} catch (RuntimeException e) {
				LOGGER.error("MC-AI chat trigger failed", e);
				return true;
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			AiService current = service;
			if (current != null) {
				current.onDisconnect(handler.player.getUUID());
			}
		});
	}
}
