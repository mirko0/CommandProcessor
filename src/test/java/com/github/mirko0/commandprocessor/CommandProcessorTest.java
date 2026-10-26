package com.github.mirko0.commandprocessor;

import com.github.mirko0.commandprocessor.annotations.Category;
import com.github.mirko0.commandprocessor.annotations.CommandEvent;
import com.github.mirko0.commandprocessor.annotations.CommandHandler;
import com.github.mirko0.commandprocessor.annotations.CommandInfo;
import com.github.mirko0.commandprocessor.annotations.CommandOption;
import com.github.mirko0.commandprocessor.example.PingCommand;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandProcessorTest {

    @CommandInfo(name = "alpha", description = "Alpha.")
    @Category("utility")
    static class Alpha implements QCommandBase {
        @CommandHandler void run(@CommandEvent SlashCommandInteractionEvent e) { }
    }

    @CommandInfo(name = "beta", description = "Beta.", guildId = "999")
    static class Beta implements QCommandBase {
        @CommandHandler void run(@CommandEvent SlashCommandInteractionEvent e) { }
    }

    /** Two classes claiming the same command name. */
    @CommandInfo(name = "alpha", description = "A different alpha.")
    static class AlphaClash implements QCommandBase {
        @CommandHandler void run(@CommandEvent SlashCommandInteractionEvent e) { }
    }

    /**
     * The constructor used to do {@code commands.add(helpCommand)}, which throws on an immutable
     * list and, under Spring, mutates a collection the container owns.
     */
    @Test
    void construct_withImmutableList_doesNotThrow() {
        assertDoesNotThrow(() -> new CommandProcessor(List.of(new Alpha()), true));
    }

    @Test
    void construct_doesNotModifyTheCallersList() {
        List<QCommandBase> given = new ArrayList<>(List.of(new Alpha()));

        new CommandProcessor(given, true);
        assertEquals(1, given.size(), "the help command was added to the caller's list");
    }

    /** The built-in help command was unregisterable: an option with an empty name. */
    @Test
    void construct_withBuiltInHelp_registersIt() {
        CommandProcessor processor = new CommandProcessor(List.of(new Alpha()), true);
        assertTrue(processor.getCommandMap().containsKey("help"),
                () -> "expected /help in " + processor.getCommandMap().keySet());
    }

    @Test
    void construct_withoutBuiltInHelp_leavesTheNameFree() {
        CommandProcessor processor = new CommandProcessor(List.of(new Alpha()), false);
        assertFalse(processor.getCommandMap().containsKey("help"));
    }

    @Test
    void construct_nullCommands_throwsNamingTheParameter() {
        NullPointerException thrown =
                assertThrows(NullPointerException.class, () -> new CommandProcessor(null, false));
        assertTrue(String.valueOf(thrown.getMessage()).contains("commands"));
    }

    @Test
    void register_duplicateCommandName_keepsTheFirst() {
        CommandProcessor processor =
                new CommandProcessor(List.of(new Alpha(), new AlphaClash()), false);

        assertAll(
                () -> assertEquals(1, processor.getCommandMap().size()),
                () -> assertEquals("Alpha.", processor.getCommandMap().get("alpha").getDescription()));
    }

    @Test
    void getCommandMap_isUnmodifiable() {
        CommandProcessor processor = new CommandProcessor(List.of(new Alpha()), false);
        assertThrows(UnsupportedOperationException.class, () -> processor.getCommandMap().clear());
    }

    @Test
    void getCategories_includesDeclaredAndDiscoveredOnes() {
        CommandProcessor processor = new CommandProcessor(
                List.of(new Alpha(), new Beta()), false,
                List.of(CommandCategory.of("utility", "Utility").withOrder(1)));

        List<String> ids = processor.getCategories().stream().map(CommandCategory::id).toList();
        assertAll(
                () -> assertTrue(ids.contains("utility"), () -> ids.toString()),
                () -> assertTrue(ids.contains(CommandCategory.DEFAULT_ID), () -> ids.toString()));
    }

    @Test
    void getCategories_areSortedByOrderThenName() {
        CommandProcessor processor = new CommandProcessor(
                List.of(new Alpha()), false,
                List.of(CommandCategory.of("zebra", "Zebra").withOrder(1),
                        CommandCategory.of("apple", "Apple").withOrder(2)));

        List<String> ids = processor.getCategories().stream().map(CommandCategory::id).toList();
        assertTrue(ids.indexOf("zebra") < ids.indexOf("apple"), () -> ids.toString());
    }

    @CommandInfo(name = "broken", description = "Broken.")
    static class Broken implements QCommandBase {
        @CommandHandler
        void run(@CommandEvent SlashCommandInteractionEvent e,
                 @CommandOption(type = OptionType.STRING, name = "", description = "No name.")
                 String bad) { }
    }

    /** One malformed command used to abort the constructor and take every other command with it. */
    @Test
    void register_commandThatCannotBeFullyParsed_doesNotStopTheOthers() {
        CommandProcessor processor = new CommandProcessor(List.of(new Broken(), new Alpha()), false);
        assertTrue(processor.getCommandMap().containsKey("alpha"));
    }

    @Test
    void register_classWithoutCommandInfo_isSkipped() {
        CommandProcessor processor = new CommandProcessor(List.of(new QCommandBase() { }), false);
        assertTrue(processor.getCommandMap().isEmpty());
    }

    @CommandInfo(name = "bounded", description = "Inverted range.")
    static class BadRange implements QCommandBase {
        @CommandHandler
        void run(@CommandEvent SlashCommandInteractionEvent e,
                 @CommandOption(type = OptionType.INTEGER, name = "count", description = "Count.",
                         minValue = 5, maxValue = 1) Long count) { }
    }

    /** An unusable bound is refused, not dropped — but only that command pays for it. */
    @Test
    void register_commandWithAnUnusableRange_isSkippedAndTheOthersRegister() {
        CommandProcessor processor = new CommandProcessor(List.of(new BadRange(), new Alpha()), false);
        assertAll(
                () -> assertFalse(processor.getCommandMap().containsKey("bounded")),
                () -> assertTrue(processor.getCommandMap().containsKey("alpha")));
    }

    @Test
    void builder_noGuildScope_keepsTheDeclaredGuild() {
        CommandProcessor processor = CommandProcessor.builder()
                .commands(List.of(new Alpha(), new Beta())).helpCommand(false).build();
        assertAll(
                () -> assertEquals(Set.of("999"), processor.getCommandMap().get("beta").getGuildIds()),
                () -> assertFalse(processor.getCommandMap().get("alpha").isGuildScoped()));
    }

    @Test
    void builder_guildScopeByCategory_scopesThatCategorysCommands() {
        CommandProcessor processor = CommandProcessor.builder()
                .commands(List.of(new Alpha(), new Beta()))
                .helpCommand(false)
                .guildScope(GuildScopeResolver.byCategory(Map.of("Utility", List.of("111", "222"))))
                .build();
        assertAll(
                () -> assertEquals(Set.of("111", "222"), processor.getCommandMap().get("alpha").getGuildIds()),
                () -> assertEquals(Set.of("999"), processor.getCommandMap().get("beta").getGuildIds(),
                        "a command outside the map keeps what it declared"));
    }

    @Test
    void builder_guildScopeByCommand_replacesTheDeclaredGuild() {
        CommandProcessor processor = CommandProcessor.builder()
                .commands(List.of(new Beta()))
                .helpCommand(false)
                .guildScope(GuildScopeResolver.byCommand(Map.of("beta", List.of("111"))))
                .build();
        assertEquals(Set.of("111"), processor.getCommandMap().get("beta").getGuildIds());
    }

    @Test
    void builder_guildScopeByCommand_emptyListMakesTheCommandGlobal() {
        CommandProcessor processor = CommandProcessor.builder()
                .commands(List.of(new Beta()))
                .helpCommand(false)
                .guildScope(GuildScopeResolver.byCommand(Map.of("beta", List.of())))
                .build();
        assertFalse(processor.getCommandMap().get("beta").isGuildScoped());
    }

    @Test
    void builder_resolverThatThrows_skipsOnlyThatCommand() {
        CommandProcessor processor = CommandProcessor.builder()
                .commands(List.of(new Alpha(), new Beta()))
                .helpCommand(false)
                .guildScope(command -> {
                    if (command.getName().equals("beta")) throw new IllegalStateException("config missing");
                    return null;
                })
                .build();
        assertAll(
                () -> assertTrue(processor.getCommandMap().containsKey("alpha")),
                () -> assertFalse(processor.getCommandMap().containsKey("beta")));
    }

    @Test
    void construct_sampleCommand_parsesEndToEnd() {
        CommandProcessor processor = new CommandProcessor(List.of(new PingCommand()), false);
        assertAll(
                () -> assertEquals(1, processor.getCommandMap().size()),
                () -> assertEquals("utility", processor.getCommandMap().get("ping").getCategoryId()),
                () -> assertEquals(List.of("latency"), processor.getCommandMap().get("ping").getAliases()));
    }
}
