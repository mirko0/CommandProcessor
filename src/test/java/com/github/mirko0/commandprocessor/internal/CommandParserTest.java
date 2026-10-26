package com.github.mirko0.commandprocessor.internal;

import com.github.mirko0.commandprocessor.CommandCategory;
import com.github.mirko0.commandprocessor.QCommandBase;
import com.github.mirko0.commandprocessor.annotations.Category;
import com.github.mirko0.commandprocessor.annotations.CommandEvent;
import com.github.mirko0.commandprocessor.annotations.CommandHandler;
import com.github.mirko0.commandprocessor.annotations.CommandInfo;
import com.github.mirko0.commandprocessor.annotations.CommandOption;
import com.github.mirko0.commandprocessor.annotations.Group;
import com.github.mirko0.commandprocessor.annotations.SubCommandGroupInfo;
import com.github.mirko0.commandprocessor.annotations.SubCommandHandler;
import com.github.mirko0.commandprocessor.definitions.CommandDefinition;
import com.github.mirko0.commandprocessor.definitions.SubcommandDefinition;
import com.github.mirko0.commandprocessor.example.PingCommand;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything the parser has ever got wrong, as tests.
 *
 * <p>No Discord token and no network: JDA's command-data builders construct standalone, which is
 * what makes the whole parser testable.
 */
class CommandParserTest {

    private static SlashCommandData dataOf(CommandDefinition definition) {
        return (SlashCommandData) definition.getCommandData();
    }

    // ------------------------------------------------------------------ groups

    @Nested
    @DisplayName("subcommand groups")
    class Groups {

        @CommandInfo(name = "logs", description = "Logs.",
                groups = @SubCommandGroupInfo(name = "channel", description = "Channel config"))
        static class ThreeInOneGroup implements QCommandBase {
            @Group("channel") @SubCommandHandler(name = "set", description = "Set it.")
            void set(@CommandEvent SlashCommandInteractionEvent e) { }

            @Group("channel") @SubCommandHandler(name = "clear", description = "Clear it.")
            void clear(@CommandEvent SlashCommandInteractionEvent e) { }

            @Group("channel") @SubCommandHandler(name = "show", description = "Show it.")
            void show(@CommandEvent SlashCommandInteractionEvent e) { }
        }

        /**
         * The group data used to be rebuilt and re-added per member, so JDA rejected the command
         * with "Cannot have multiple subcommand groups with the same name" and the exception took
         * the entire registration down with it.
         */
        @Test
        void parse_severalSubcommandsInOneGroup_addsTheGroupOnce() {
            CommandDefinition definition = CommandParser.parseCommand(new ThreeInOneGroup());

            List<SubcommandGroupData> groups = dataOf(definition).getSubcommandGroups();
            assertAll(
                    () -> assertEquals(1, groups.size(), "one group"),
                    () -> assertEquals(3, groups.get(0).getSubcommands().size(), "all three members"),
                    () -> assertEquals(3, definition.getGroups().get("channel").getSubcommands().size()));
        }

        @Test
        void parse_groupedSubcommand_recordsItsGroupName() {
            CommandDefinition definition = CommandParser.parseCommand(new ThreeInOneGroup());

            SubcommandDefinition sub = definition.getGroups().get("channel").getSubcommands().get(0);
            assertEquals("channel", sub.getGroupName());
        }

        @CommandInfo(name = "orphan", description = "Orphan.")
        static class UndeclaredGroup implements QCommandBase {
            @Group("nope") @SubCommandHandler(name = "sub", description = "Sub.")
            void sub(@CommandEvent SlashCommandInteractionEvent e) { }
        }

        @Test
        void parse_subcommandInUndeclaredGroup_isSkippedNotRegistered() {
            CommandDefinition definition = CommandParser.parseCommand(new UndeclaredGroup());

            assertAll(
                    () -> assertTrue(dataOf(definition).getSubcommandGroups().isEmpty()),
                    () -> assertTrue(definition.getSubcommands().isEmpty()));
        }
    }

    // -------------------------------------------------------------- discovery

    @Nested
    @DisplayName("handler discovery")
    class Discovery {

        abstract static class Base implements QCommandBase {
            @SubCommandHandler(name = "inherited", description = "On the base class.", order = 5)
            void inherited(@CommandEvent SlashCommandInteractionEvent e) { }
        }

        @CommandInfo(name = "child", description = "Child.")
        static class Child extends Base {
            @SubCommandHandler(name = "zulu", description = "Last by name, first by order.", order = -1)
            void zulu(@CommandEvent SlashCommandInteractionEvent e) { }

            @SubCommandHandler(name = "alpha", description = "First by name.")
            void alpha(@CommandEvent SlashCommandInteractionEvent e) { }
        }

        /** getDeclaredMethods() alone never saw a handler declared on a superclass. */
        @Test
        void parse_handlerOnSuperclass_isRegistered() {
            List<String> names = subcommandNames(CommandParser.parseCommand(new Child()));
            assertTrue(names.contains("inherited"), () -> "expected the inherited handler in " + names);
        }

        /** Reflection gives no order, so without a tie-break the listing changed between builds. */
        @Test
        void parse_subcommands_areOrderedByOrderThenName() {
            assertEquals(List.of("zulu", "alpha", "inherited"),
                    subcommandNames(CommandParser.parseCommand(new Child())));
        }

        @CommandInfo(name = "base", description = "Base.")
        static class Real implements QCommandBase {
            @CommandHandler void run(@CommandEvent SlashCommandInteractionEvent e) { }
        }

        /** Stands in for a Spring CGLIB proxy: a generated subclass of the real command. */
        static class Proxy extends Real { }

        @Test
        void parse_proxiedSubclass_stillFindsCommandInfo() {
            CommandDefinition definition = CommandParser.parseCommand(new Proxy());
            assertAll(
                    () -> assertNotNull(definition, "a proxied bean used to parse to null and vanish"),
                    () -> assertEquals("base", definition.getName()));
        }

        @Test
        void parse_classWithoutCommandInfo_returnsNull() {
            assertNull(CommandParser.parseCommand(new QCommandBase() { }));
        }

        private List<String> subcommandNames(CommandDefinition definition) {
            return dataOf(definition).getSubcommands().stream()
                    .map(net.dv8tion.jda.api.interactions.commands.build.SubcommandData::getName)
                    .toList();
        }
    }

    // ----------------------------------------------------------------- limits

    @Nested
    @DisplayName("Discord's limits")
    class Limits {

        private static final String LONG_DESCRIPTION =
                "This description is deliberately far longer than the one hundred characters that "
                        + "Discord is willing to accept, so the parser has to trim it.";

        @CommandInfo(name = "messy", description = "")
        static class Messy implements QCommandBase {
            @SubCommandHandler(name = "long", description = LONG_DESCRIPTION)
            void longOne(@CommandEvent SlashCommandInteractionEvent e,
                         @CommandOption(type = OptionType.STRING, name = "optional",
                                 description = "Optional, declared first.") String optional,
                         @CommandOption(type = OptionType.STRING, name = "needed", required = true,
                                 description = "Required, declared second.",
                                 choices = {"a:Label A", "b:Label: with colon"}) String needed) { }
        }

        @Test
        void parse_blankCommandDescription_isSubstitutedRatherThanThrowing() {
            CommandDefinition definition = CommandParser.parseCommand(new Messy());
            assertFalse(dataOf(definition).getDescription().isBlank());
        }

        @Test
        void parse_subcommandDescriptionOver100Chars_isTrimmed() {
            CommandDefinition definition = CommandParser.parseCommand(new Messy());
            assertEquals(100, dataOf(definition).getSubcommands().get(0).getDescription().length());
        }

        /** JDA throws when a required option follows an optional one; that killed registration. */
        @Test
        void parse_requiredOptionAfterOptional_isMovedInFront() {
            List<OptionData> options = dataOf(CommandParser.parseCommand(new Messy()))
                    .getSubcommands().get(0).getOptions();
            assertEquals(List.of("needed", "optional"),
                    options.stream().map(OptionData::getName).toList());
        }

        @Test
        void parse_choiceWithExtraColon_fallsBackToValueAsLabel() {
            List<OptionData> options = dataOf(CommandParser.parseCommand(new Messy()))
                    .getSubcommands().get(0).getOptions();
            OptionData needed = options.get(0);
            assertAll(
                    () -> assertEquals(2, needed.getChoices().size()),
                    () -> assertEquals("Label A", needed.getChoices().get(0).getName()),
                    () -> assertEquals("b", needed.getChoices().get(1).getName()));
        }

        @CommandInfo(name = "unnamed", description = "Unnamed option.")
        static class UnnamedOption implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.STRING, name = "", description = "No name.")
                     String broken) { }
        }

        /** An empty option name made JDA throw "Name may not be empty" out of the constructor. */
        @Test
        void parse_optionWithNoName_isSkippedNotFatal() {
            CommandDefinition definition = CommandParser.parseCommand(new UnnamedOption());
            assertAll(
                    () -> assertNotNull(definition),
                    () -> assertTrue(dataOf(definition).getOptions().isEmpty()));
        }

        @CommandInfo(name = "toomany", description = "Too many choices.")
        static class TooManyChoices implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.STRING, name = "pick", description = "Pick.",
                             choices = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12",
                                     "13", "14", "15", "16", "17", "18", "19", "20", "21", "22",
                                     "23", "24", "25", "26", "27"})
                     String pick) { }
        }

        @Test
        void parse_over25Choices_isTruncatedToDiscordsLimit() {
            CommandDefinition definition = CommandParser.parseCommand(new TooManyChoices());
            assertEquals(25, dataOf(definition).getOptions().get(0).getChoices().size());
        }

        @CommandInfo(name = "wrongtype", description = "Choices on a user option.")
        static class ChoicesOnUser implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.USER, name = "who", description = "Who.",
                             choices = {"a:A"})
                     net.dv8tion.jda.api.entities.User who) { }
        }

        @Test
        void parse_choicesOnATypeThatCannotHaveThem_areIgnoredNotFatal() {
            CommandDefinition definition = CommandParser.parseCommand(new ChoicesOnUser());
            assertTrue(dataOf(definition).getOptions().get(0).getChoices().isEmpty());
        }
    }

    // ----------------------------------------------------------- constraints

    @Nested
    @DisplayName("option ranges, lengths and typed choices")
    class Constraints {

        @CommandInfo(name = "bounded", description = "Bounded options.")
        static class Bounded implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.INTEGER, name = "count", description = "Count.",
                             minValue = 1, maxValue = 10) Long count,
                     @CommandOption(type = OptionType.NUMBER, name = "ratio", description = "Ratio.",
                             minValue = 0.5) Double ratio,
                     @CommandOption(type = OptionType.STRING, name = "tag", description = "Tag.",
                             minLength = 2, maxLength = 32) String tag,
                     @CommandOption(type = OptionType.STRING, name = "note", description = "Note.",
                             minLength = 0) String note) { }
        }

        private OptionData option(String name) {
            return dataOf(CommandParser.parseCommand(new Bounded())).getOptions().stream()
                    .filter(option -> option.getName().equals(name))
                    .findFirst()
                    .orElseThrow();
        }

        @Test
        void parse_integerRange_isAppliedAsWholeNumbers() {
            OptionData count = option("count");
            assertAll(
                    () -> assertEquals(1L, count.getMinValue()),
                    () -> assertEquals(10L, count.getMaxValue()));
        }

        @Test
        void parse_numberMinimumOnly_leavesTheMaximumUnset() {
            OptionData ratio = option("ratio");
            assertAll(
                    () -> assertEquals(0.5, ratio.getMinValue()),
                    () -> assertNull(ratio.getMaxValue()));
        }

        @Test
        void parse_stringLength_isApplied() {
            OptionData tag = option("tag");
            assertAll(
                    () -> assertEquals(2, tag.getMinLength()),
                    () -> assertEquals(32, tag.getMaxLength()));
        }

        /** Discord accepts 0 as a minimum, but JDA refuses to set it, so it must not reach JDA. */
        @Test
        void parse_minLengthZero_isAcceptedAsNoMinimum() {
            assertNull(option("note").getMinLength());
        }

        @Test
        void parse_noConstraints_leavesTheOptionUnbounded() {
            OptionData message = dataOf(CommandParser.parseCommand(new PingCommand()))
                    .getSubcommandGroups().get(0).getSubcommands().stream()
                    .flatMap(sub -> sub.getOptions().stream())
                    .findFirst()
                    .orElseThrow();
            assertAll(
                    () -> assertNull(message.getMinLength()),
                    () -> assertNull(message.getMaxLength()));
        }

        @CommandInfo(name = "inverted", description = "Min above max.")
        static class InvertedRange implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.INTEGER, name = "count", description = "Count.",
                             minValue = 10, maxValue = 1) Long count) { }
        }

        @CommandInfo(name = "textrange", description = "Range on a string.")
        static class RangeOnString implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.STRING, name = "text", description = "Text.",
                             maxValue = 5) String text) { }
        }

        @CommandInfo(name = "fraction", description = "Fractional integer bound.")
        static class FractionalIntegerBound implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.INTEGER, name = "count", description = "Count.",
                             minValue = 1.5) Long count) { }
        }

        @CommandInfo(name = "huge", description = "Bound outside Discord's range.")
        static class HugeBound implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.NUMBER, name = "big", description = "Big.",
                             maxValue = 1e300) Double big) { }
        }

        @Test
        void parse_minValueAboveMaxValue_throwsNamingTheOption() {
            assertRejected(new InvertedRange(), "'count'", "greater than maxValue");
        }

        @Test
        void parse_rangeOnANonNumericOption_throws() {
            assertRejected(new RangeOnString(), "'text'", "INTEGER and NUMBER");
        }

        @Test
        void parse_fractionalBoundOnAnIntegerOption_throws() {
            assertRejected(new FractionalIntegerBound(), "'count'", "not a whole number");
        }

        @Test
        void parse_boundOutsideDiscordsRange_throws() {
            assertRejected(new HugeBound(), "'big'", "outside Discord's range");
        }

        @CommandInfo(name = "toolong", description = "Max length over 6000.")
        static class MaxLengthOverLimit implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.STRING, name = "text", description = "Text.",
                             maxLength = 6001) String text) { }
        }

        @CommandInfo(name = "zeromax", description = "Max length of zero.")
        static class MaxLengthZero implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.STRING, name = "text", description = "Text.",
                             maxLength = 0) String text) { }
        }

        @CommandInfo(name = "invertedlength", description = "Min length above max.")
        static class InvertedLength implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.STRING, name = "text", description = "Text.",
                             minLength = 10, maxLength = 5) String text) { }
        }

        @CommandInfo(name = "numberlength", description = "Length on an integer.")
        static class LengthOnInteger implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.INTEGER, name = "count", description = "Count.",
                             maxLength = 5) Long count) { }
        }

        @Test
        void parse_maxLengthOverDiscordsLimit_throws() {
            assertRejected(new MaxLengthOverLimit(), "'text'", "between 1 and 6000");
        }

        @Test
        void parse_maxLengthZero_throws() {
            assertRejected(new MaxLengthZero(), "'text'", "between 1 and 6000");
        }

        @Test
        void parse_minLengthAboveMaxLength_throws() {
            assertRejected(new InvertedLength(), "'text'", "greater than maxLength");
        }

        @Test
        void parse_lengthOnANonStringOption_throws() {
            assertRejected(new LengthOnInteger(), "'count'", "STRING options");
        }

        @CommandInfo(name = "numericchoices", description = "Choices on numeric options.")
        static class NumericChoices implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.INTEGER, name = "days", description = "Days.",
                             choices = {"1:One day", "7:One week"}) Long days,
                     @CommandOption(type = OptionType.NUMBER, name = "speed", description = "Speed.",
                             choices = {"0.5:Half", "2"}) Double speed) { }
        }

        /** Every choice used to be a string, which Discord rejects on a numeric option. */
        @Test
        void parse_integerChoices_carryIntegerValues() {
            List<Command.Choice> choices =
                    dataOf(CommandParser.parseCommand(new NumericChoices())).getOptions().get(0).getChoices();
            assertAll(
                    () -> assertEquals(OptionType.INTEGER, choices.get(0).getType()),
                    () -> assertEquals(7L, choices.get(1).getAsLong()),
                    () -> assertEquals("One week", choices.get(1).getName()));
        }

        @Test
        void parse_numberChoices_carryNumberValues() {
            List<Command.Choice> choices =
                    dataOf(CommandParser.parseCommand(new NumericChoices())).getOptions().get(1).getChoices();
            assertAll(
                    () -> assertEquals(OptionType.NUMBER, choices.get(0).getType()),
                    () -> assertEquals(0.5, choices.get(0).getAsDouble()),
                    () -> assertEquals("2", choices.get(1).getName()));
        }

        @CommandInfo(name = "badchoice", description = "Text choice on an integer.")
        static class TextChoiceOnInteger implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.INTEGER, name = "days", description = "Days.",
                             choices = {"week:One week"}) Long days) { }
        }

        @Test
        void parse_nonNumericChoiceOnAnIntegerOption_throws() {
            assertRejected(new TextChoiceOnInteger(), "'days'", "not a number");
        }

        @CommandInfo(name = "hugechoice", description = "Choice outside Discord's range.")
        static class HugeChoice implements QCommandBase {
            @CommandHandler
            void run(@CommandEvent SlashCommandInteractionEvent e,
                     @CommandOption(type = OptionType.NUMBER, name = "big", description = "Big.",
                             choices = {"Infinity:Forever"}) Double big) { }
        }

        /** JDA only range-checks addChoice(name, number); the Choice objects go through unchecked. */
        @Test
        void parse_numericChoiceOutsideDiscordsRange_throws() {
            assertRejected(new HugeChoice(), "'big'", "outside Discord's range");
        }

        private void assertRejected(QCommandBase command, String... expectedFragments) {
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> CommandParser.parseCommand(command));
            String message = thrown.getMessage();
            for (String fragment : expectedFragments) {
                assertTrue(message.contains(fragment), () -> "expected \"" + fragment + "\" in: " + message);
            }
            assertTrue(message.contains(command.getClass().getSimpleName()),
                    () -> "expected the class name in: " + message);
        }
    }

    // ------------------------------------------------------------- metadata

    @Nested
    @DisplayName("metadata")
    class Metadata {

        @Category("Moderation")
        @CommandInfo(name = "ban", description = "Ban a member.",
                aliases = {"Yeet", "ban", "  "},
                permissions = {Permission.BAN_MEMBERS, Permission.MODERATE_MEMBERS},
                contexts = {InteractionContextType.GUILD, InteractionContextType.BOT_DM},
                guildId = "123")
        static class Ban implements QCommandBase {
            @CommandHandler void run(@CommandEvent SlashCommandInteractionEvent e) { }
        }

        @Test
        void parse_category_isNormalisedToLowercase() {
            assertEquals("moderation", CommandParser.parseCommand(new Ban()).getCategoryId());
        }

        @Test
        void parse_noCategory_fallsBackToTheDefault() {
            assertEquals(CommandCategory.DEFAULT_ID,
                    CommandParser.parseCommand(new Discovery.Real()).getCategoryId());
        }

        @Test
        void parse_aliases_areLowercasedDeduplicatedAndExcludeTheCommandName() {
            assertEquals(List.of("yeet"), CommandParser.parseCommand(new Ban()).getAliases());
        }

        @Test
        void parse_permissions_areRecordedForTheHelpCommand() {
            assertEquals(java.util.EnumSet.of(Permission.BAN_MEMBERS, Permission.MODERATE_MEMBERS),
                    CommandParser.parseCommand(new Ban()).getPermissions());
        }

        @Test
        void parse_guildId_marksTheCommandGuildScoped() {
            assertTrue(CommandParser.parseCommand(new Ban()).isGuildScoped());
        }

        /** The singular contextType could only carry one; a command is often valid in several. */
        @Test
        void parse_severalContexts_areAllApplied() {
            assertEquals(2, dataOf(CommandParser.parseCommand(new Ban())).getContexts().size());
        }

        @Test
        void parse_sampleCommand_recordsSubcommandOptionNames() {
            CommandDefinition definition = CommandParser.parseCommand(new PingCommand());
            SubcommandDefinition echo = definition.getGroups().get("test").getSubcommands().stream()
                    .filter(sub -> sub.getName().equals("echo"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(List.of("message"), echo.getOptionNames());
        }
    }
}
