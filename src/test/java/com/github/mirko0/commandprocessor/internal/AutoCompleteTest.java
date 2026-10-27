package com.github.mirko0.commandprocessor.internal;

import com.github.mirko0.commandprocessor.QCommandBase;
import com.github.mirko0.commandprocessor.annotations.AutoCompleteHandler;
import com.github.mirko0.commandprocessor.annotations.AutoCompleteValue;
import com.github.mirko0.commandprocessor.annotations.CommandEvent;
import com.github.mirko0.commandprocessor.annotations.CommandHandler;
import com.github.mirko0.commandprocessor.annotations.CommandInfo;
import com.github.mirko0.commandprocessor.annotations.CommandOption;
import com.github.mirko0.commandprocessor.annotations.SubCommandHandler;
import com.github.mirko0.commandprocessor.definitions.AutoCompleteDefinition;
import com.github.mirko0.commandprocessor.definitions.CommandDefinition;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCompleteTest {

    private static SlashCommandData dataOf(CommandDefinition definition) {
        return (SlashCommandData) definition.getCommandData();
    }

    // ------------------------------------------------------- parsing & scoping

    @CommandInfo(name = "play", description = "Play a track.")
    static class Play implements QCommandBase {
        @CommandHandler
        void play(@CommandEvent SlashCommandInteractionEvent e,
                  @CommandOption(type = OptionType.STRING, name = "track", description = "What to play.",
                          autoComplete = true)
                  String track) { }

        @AutoCompleteHandler(option = "track")
        List<String> tracks(@AutoCompleteValue String typed) {
            return List.of("a", "b");
        }
    }

    @Test
    void parse_autoCompleteHandler_isRecorded() {
        CommandDefinition definition = CommandParser.parseCommand(new Play());
        assertAll(
                () -> assertEquals(1, definition.getAutoCompletes().size()),
                () -> assertEquals("track", definition.getAutoCompletes().get(0).getOptionName()),
                () -> assertTrue(dataOf(definition).getOptions().get(0).isAutoComplete()));
    }

    /**
     * A command whose only options are its base handler's. The gap check used to run before those
     * options were added, so it read the command as having no option marked and warned that a
     * perfectly wired handler would never run.
     */
    @Test
    @DisplayName("a base handler's autocomplete option is not reported as unmarked")
    void parse_baseHandlerOption_noFalseWarning() {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true));
        try {
            CommandParser.parseCommand(new Play());
        } finally {
            System.setErr(original);
        }
        assertFalse(captured.toString().contains("will never run"), captured.toString());
    }

    @CommandInfo(name = "cfg", description = "Config.")
    static class Scoped implements QCommandBase {
        @SubCommandHandler(name = "set", description = "Set it.")
        void set(@CommandEvent SlashCommandInteractionEvent e,
                 @CommandOption(type = OptionType.STRING, name = "key", description = "Key.",
                         autoComplete = true) String key) { }

        @SubCommandHandler(name = "clear", description = "Clear it.")
        void clear(@CommandEvent SlashCommandInteractionEvent e,
                   @CommandOption(type = OptionType.STRING, name = "key", description = "Key.",
                           autoComplete = true) String key) { }

        @AutoCompleteHandler(option = "key")
        List<String> anyKey() { return List.of("any"); }

        @AutoCompleteHandler(option = "key", subcommand = "clear")
        List<String> clearKey() { return List.of("clear-only"); }
    }

    /** An option name is only unique within a subcommand, so the narrower handler must win. */
    @Test
    void findAutoComplete_severalMatches_prefersTheMostSpecific() {
        CommandDefinition definition = CommandParser.parseCommand(new Scoped());

        AutoCompleteDefinition forClear = definition.findAutoComplete(null, "clear", "key");
        AutoCompleteDefinition forSet = definition.findAutoComplete(null, "set", "key");
        assertAll(
                () -> assertEquals("clearKey", forClear.getHandlerMethod().getName()),
                () -> assertEquals("anyKey", forSet.getHandlerMethod().getName()));
    }

    @Test
    void findAutoComplete_optionWithNoHandler_returnsNull() {
        assertNull(CommandParser.parseCommand(new Scoped()).findAutoComplete(null, "set", "other"));
    }

    @Test
    void findAutoComplete_matchesCaseInsensitively() {
        assertNotNull(CommandParser.parseCommand(new Scoped()).findAutoComplete(null, "SET", "KEY"));
    }

    // ------------------------------------------------------ what Discord rejects

    @Nested
    @DisplayName("shapes Discord refuses")
    class Refused {

        @CommandInfo(name = "both", description = "Choices and autocomplete.")
        static class ChoicesAndAutoComplete implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.STRING, name = "pick", description = "Pick.",
                             autoComplete = true, choices = {"a:A", "b:B"})
                     String pick) { }
        }

        /** JDA throws "Cannot add choices to auto-complete options", aborting the command. */
        @Test
        void parse_choicesAndAutoComplete_keepsChoicesAndDropsAutoComplete() {
            CommandDefinition definition = CommandParser.parseCommand(new ChoicesAndAutoComplete());
            OptionData option = dataOf(definition).getOptions().get(0);
            assertAll(
                    () -> assertFalse(option.isAutoComplete()),
                    () -> assertEquals(2, option.getChoices().size()));
        }

        @CommandInfo(name = "wrong", description = "Autocomplete on a user option.")
        static class AutoCompleteOnUser implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.USER, name = "who", description = "Who.",
                             autoComplete = true)
                     net.dv8tion.jda.api.entities.User who) { }
        }

        /** JDA throws "Cannot enable auto-complete for options of type USER". */
        @Test
        void parse_autoCompleteOnUnsupportedType_isDisabledNotFatal() {
            CommandDefinition definition = CommandParser.parseCommand(new AutoCompleteOnUser());
            assertAll(
                    () -> assertNotNull(definition),
                    () -> assertFalse(dataOf(definition).getOptions().get(0).isAutoComplete()));
        }

        @CommandInfo(name = "badreturn", description = "Handler returning the wrong type.")
        static class WrongReturnType implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.STRING, name = "x", description = "X.",
                             autoComplete = true) String x) { }

            @AutoCompleteHandler(option = "x")
            String notACollection() { return "nope"; }
        }

        @Test
        void parse_handlerNotReturningACollection_isRejectedAtRegistration() {
            assertTrue(CommandParser.parseCommand(new WrongReturnType()).getAutoCompletes().isEmpty());
        }
    }

    // ------------------------------------------------------------- coercion

    @Nested
    @DisplayName("choice coercion")
    class Coercion {

        @Test
        void coerce_strings_becomeLabelAndValue() {
            List<Command.Choice> choices =
                    ChoiceCoercion.coerce(List.of("alpha", "beta"), OptionType.STRING, "test");
            assertAll(
                    () -> assertEquals(2, choices.size()),
                    () -> assertEquals("alpha", choices.get(0).getName()),
                    () -> assertEquals("alpha", choices.get(0).getAsString()));
        }

        /** Discord rejects a string value on an INTEGER option. */
        @Test
        void coerce_numericStringsOnAnIntegerOption_becomeNumericChoices() {
            List<Command.Choice> choices =
                    ChoiceCoercion.coerce(List.of("7", "42"), OptionType.INTEGER, "test");
            assertAll(
                    () -> assertEquals(2, choices.size()),
                    () -> assertEquals(7L, choices.get(0).getAsLong()),
                    () -> assertEquals("7", choices.get(0).getName()));
        }

        @Test
        void coerce_nonNumericOnAnIntegerOption_isDroppedNotThrown() {
            assertTrue(ChoiceCoercion.coerce(List.of("nope"), OptionType.INTEGER, "test").isEmpty());
        }

        @Test
        void coerce_numbers_useTheirNumericValue() {
            List<Command.Choice> choices =
                    ChoiceCoercion.coerce(List.of(1.5d), OptionType.NUMBER, "test");
            assertEquals(1.5d, choices.get(0).getAsDouble());
        }

        @Test
        void coerce_explicitChoices_arePassedThrough() {
            Command.Choice choice = new Command.Choice("Label", "value");
            List<Command.Choice> choices = ChoiceCoercion.coerce(List.of(choice), OptionType.STRING, "test");
            assertAll(
                    () -> assertEquals("Label", choices.get(0).getName()),
                    () -> assertEquals("value", choices.get(0).getAsString()));
        }

        @Test
        void coerce_moreThan25_isTruncatedToDiscordsLimit() {
            List<String> many = IntStream.range(0, 60).mapToObj(String::valueOf).toList();
            assertEquals(OptionData.MAX_CHOICES,
                    ChoiceCoercion.coerce(many, OptionType.STRING, "test").size());
        }

        /** A label is what the user reads, so trimming it costs nothing. */
        @Test
        void coerce_overLongLabelOnAnExplicitChoice_isTrimmed() {
            List<Command.Choice> choices = ChoiceCoercion.coerce(
                    List.of(9_999_999L), OptionType.INTEGER, "test");
            assertEquals("9999999", choices.get(0).getName());
        }

        /**
         * A value is what Discord sends back when the suggestion is picked, so a truncated one
         * resolves to nothing. Dropped rather than silently corrupted.
         */
        @Test
        void coerce_overLongValue_isDroppedRatherThanTruncated() {
            assertTrue(ChoiceCoercion.coerce(List.of("x".repeat(250)), OptionType.STRING, "test")
                    .isEmpty());
        }

        @Test
        void coerce_labelAtTheLimit_isKept() {
            String exact = "x".repeat(OptionData.MAX_CHOICE_NAME_LENGTH);
            assertEquals(1, ChoiceCoercion.coerce(List.of(exact), OptionType.STRING, "test").size());
        }

        @Test
        void coerce_nullOrWrongType_yieldsNothing() {
            assertAll(
                    () -> assertTrue(ChoiceCoercion.coerce(null, OptionType.STRING, "test").isEmpty()),
                    () -> assertTrue(ChoiceCoercion.coerce("not a collection", OptionType.STRING, "test")
                            .isEmpty()));
        }

        @Test
        void coerce_nullElements_areSkipped() {
            List<String> withNull = new java.util.ArrayList<>(List.of("a"));
            withNull.add(null);
            assertEquals(1, ChoiceCoercion.coerce(withNull, OptionType.STRING, "test").size());
        }
    }
}
