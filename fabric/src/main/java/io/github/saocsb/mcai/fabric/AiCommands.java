package io.github.saocsb.mcai.fabric;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * The {@code /ai} command tree.
 *
 * <pre>
 * /ai                                           help
 * /ai &lt;message...&gt;                             talk to the AI
 * /ai help | stop | undo | clear | confirm | status
 * /ai reload                                     (admin)
 * /ai config provider|model|key|baseurl &lt;value&gt;  (admin)
 * </pre>
 *
 * Every literal also accepts a trailing greedy argument; when present, the whole line is sent to the AI instead,
 * so "/ai help me build a house" is a request rather than a syntax error.
 */
public final class AiCommands {
	private static final int OK = 1;

	@FunctionalInterface
	private interface PlayerAction {
		void run(AiService service, ServerPlayer player);
	}

	@FunctionalInterface
	private interface SourceAction {
		void run(AiService service, CommandSourceStack source);
	}

	private AiCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("ai")
				.requires(AiCommands::canUse)
				.executes(source(AiService::help))
				.then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(ctx -> ask(ctx, StringArgumentType.getString(ctx, "message"))))
				.then(subcommand("help", source(AiService::help)))
				.then(subcommand("stop", player(AiService::stop)))
				.then(subcommand("undo", player(AiService::undo)))
				.then(subcommand("clear", player(AiService::clear)))
				.then(subcommand("confirm", player(AiService::confirm)))
				.then(subcommand("status", source(AiService::status)))
				.then(subcommand("reload", source(AiService::reload)).requires(AiCommands::isAdmin))
				.then(config()));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> config() {
		return Commands.literal("config")
				.requires(AiCommands::isAdmin)
				.then(Commands.literal("provider")
						.then(Commands.argument("id", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(providerIds(), builder))
								.executes(ctx -> withSource(ctx, (service, source) ->
										service.setProvider(source, StringArgumentType.getString(ctx, "id"))))))
				.then(Commands.literal("model")
						.then(Commands.argument("value", StringArgumentType.greedyString())
								.executes(ctx -> withSource(ctx, (service, source) ->
										service.setModel(source, StringArgumentType.getString(ctx, "value"))))))
				.then(Commands.literal("key")
						.then(Commands.argument("value", StringArgumentType.greedyString())
								.executes(ctx -> withSource(ctx, (service, source) ->
										service.setKey(source, StringArgumentType.getString(ctx, "value"))))))
				.then(Commands.literal("baseurl")
						.then(Commands.argument("value", StringArgumentType.greedyString())
								.executes(ctx -> withSource(ctx, (service, source) ->
										service.setBaseUrl(source, StringArgumentType.getString(ctx, "value"))))))
				.then(rest("config"));
	}

	/** A literal that runs {@code action}, or sends "{@code name} rest..." to the AI when followed by more text. */
	private static LiteralArgumentBuilder<CommandSourceStack> subcommand(String name, Command<CommandSourceStack> action) {
		return Commands.literal(name).executes(action).then(rest(name));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> rest(String literal) {
		return Commands.argument("rest", StringArgumentType.greedyString())
				.executes(ctx -> ask(ctx, literal + " " + StringArgumentType.getString(ctx, "rest")));
	}

	private static Command<CommandSourceStack> player(PlayerAction action) {
		return ctx -> {
			ServerPlayer player = ctx.getSource().getPlayerOrException();
			AiService service = service(ctx.getSource());
			if (service == null) {
				return 0;
			}
			action.run(service, player);
			return OK;
		};
	}

	private static Command<CommandSourceStack> source(SourceAction action) {
		return ctx -> withSource(ctx, action);
	}

	private static int withSource(CommandContext<CommandSourceStack> ctx, SourceAction action) {
		AiService service = service(ctx.getSource());
		if (service == null) {
			return 0;
		}
		action.run(service, ctx.getSource());
		return OK;
	}

	private static int ask(CommandContext<CommandSourceStack> ctx, String message) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		AiService service = service(ctx.getSource());
		if (service == null) {
			return 0;
		}
		service.ask(player, message);
		return OK;
	}

	private static AiService service(CommandSourceStack source) {
		AiService service = McAiMod.service();
		if (service == null) {
			source.sendFailure(Component.literal("MC-AI is not running (see the server log)."));
		}
		return service;
	}

	private static List<String> providerIds() {
		AiService service = McAiMod.service();
		return service == null ? List.of() : List.copyOf(service.config().providers.keySet());
	}

	private static boolean canUse(CommandSourceStack source) {
		AiService service = McAiMod.service();
		return service == null || AiService.hasLevel(source.permissions(), service.config().permissions.useLevel);
	}

	private static boolean isAdmin(CommandSourceStack source) {
		AiService service = McAiMod.service();
		return service == null ? AiService.hasLevel(source.permissions(), 2) : service.isAdmin(source);
	}
}
