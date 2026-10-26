package com.github.mirko0.commandprocessor.example;

import com.github.mirko0.commandprocessor.QCommandBase;
import com.github.mirko0.commandprocessor.annotations.Category;
import com.github.mirko0.commandprocessor.annotations.CommandEvent;
import com.github.mirko0.commandprocessor.annotations.CommandHandler;
import com.github.mirko0.commandprocessor.annotations.CommandInfo;
import com.github.mirko0.commandprocessor.annotations.CommandOption;
import com.github.mirko0.commandprocessor.annotations.Group;
import com.github.mirko0.commandprocessor.annotations.SubCommandGroupInfo;
import com.github.mirko0.commandprocessor.annotations.SubCommandHandler;
import com.github.mirko0.commandprocessor.enumeration.Response;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;

/**
 * A worked example of every shape the library supports.
 *
 * <p>Lives in the test sources on purpose: shipping it in the published jar would put a
 * {@code QCommandBase} into consumers' auto-complete and, under a component scan, into their bot.
 */
@Category("utility")
@CommandInfo(name = "ping", description = "Pong!", aliases = {"latency"},
        groups = @SubCommandGroupInfo(name = "test", description = "Test group"))
public class PingCommand implements QCommandBase {

    // A command with subcommands never receives a bare invocation, so there is no @CommandHandler
    // here. Declaring one alongside subcommands is warned about at registration.

    @SubCommandHandler(name = "pong", description = "Can you pong?", defaultResponse = Response.EPHEMERAL)
    public void pong(@CommandEvent SlashCommandInteractionEvent event) {
        event.getHook().editOriginal("Pong!").queue();
    }

    @Group("test")
    @SubCommandHandler(name = "echo", description = "Say something back.", order = 1)
    public void echo(@CommandEvent SlashCommandInteractionEvent event,
                     @CommandOption(type = OptionType.STRING, name = "message", required = true,
                             description = "What to say back.")
                     String message) {
        event.reply(message).setEphemeral(true).queue();
    }

    @Group("test")
    @SubCommandHandler(name = "count", description = "Count to a number.", order = 2)
    public void count(@CommandEvent SlashCommandInteractionEvent event,
                      @CommandOption(type = OptionType.INTEGER, name = "upto",
                              description = "How high to count.", minValue = 1, maxValue = 100)
                      Long upTo) {
        event.reply(String.valueOf(upTo == null ? 10 : upTo)).setEphemeral(true).queue();
    }
}
