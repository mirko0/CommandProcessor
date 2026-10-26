package com.github.mirko0.commandprocessor.internal;

import com.github.mirko0.commandprocessor.GuildScopeResolver;
import com.github.mirko0.commandprocessor.QCommandBase;
import com.github.mirko0.commandprocessor.annotations.CommandEvent;
import com.github.mirko0.commandprocessor.annotations.CommandHandler;
import com.github.mirko0.commandprocessor.annotations.CommandInfo;
import com.github.mirko0.commandprocessor.definitions.CommandDefinition;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guild scoping: resolving each command's guilds, and grouping them into one request per guild.
 *
 * <p>Grouping matters because guild registration is a full {@code PUT}: a guild that received two
 * requests would keep only the commands in whichever arrived last.
 */
class GuildScopesTest {

    @CommandInfo(name = "alpha", description = "Alpha.")
    static class Alpha implements QCommandBase {
        @CommandHandler void run(@CommandEvent SlashCommandInteractionEvent e) { }
    }

    @CommandInfo(name = "beta", description = "Beta.", guildId = "222")
    static class Beta implements QCommandBase {
        @CommandHandler void run(@CommandEvent SlashCommandInteractionEvent e) { }
    }

    @CommandInfo(name = "gamma", description = "Gamma.", guildId = "staff")
    static class Gamma implements QCommandBase {
        @CommandHandler void run(@CommandEvent SlashCommandInteractionEvent e) { }
    }

    private static CommandDefinition scoped(QCommandBase command, GuildScopeResolver resolver) {
        CommandDefinition definition = CommandParser.parseCommand(command);
        GuildScopes.apply(definition, resolver);
        return definition;
    }

    private static List<String> names(List<CommandData> commands) {
        return commands.stream().map(CommandData::getName).toList();
    }

    @Test
    void apply_declaredResolver_keepsTheAnnotatedGuild() {
        assertEquals(Set.of("222"), scoped(new Beta(), GuildScopeResolver.declared()).getGuildIds());
    }

    @Test
    void apply_nullAnswer_keepsTheDeclaredScope() {
        assertEquals(Set.of("222"), scoped(new Beta(), command -> null).getGuildIds());
    }

    @Test
    void apply_emptyAnswer_makesTheCommandGlobal() {
        assertFalse(scoped(new Beta(), command -> List.of()).isGuildScoped());
    }

    @Test
    void apply_idsAreTrimmedAndDeduplicated() {
        CommandDefinition definition = scoped(new Alpha(), command -> List.of(" 111 ", "111", "333"));
        assertEquals(List.of("111", "333"), List.copyOf(definition.getGuildIds()));
    }

    /** An unset config value must not shrink the list to nothing and make the command global. */
    @Test
    void apply_blankResolvedId_throwsRatherThanGoingGlobal() {
        assertThrows(IllegalArgumentException.class, () -> scoped(new Beta(), command -> List.of(" ")));
    }

    @CommandInfo(name = "blank", description = "Blank guild.", guildId = "  ")
    static class BlankGuild implements QCommandBase {
        @CommandHandler void run(@CommandEvent SlashCommandInteractionEvent e) { }
    }

    @Test
    void parse_blankDeclaredGuildId_throwsRatherThanGoingGlobal() {
        assertThrows(IllegalArgumentException.class, () -> CommandParser.parseCommand(new BlankGuild()));
    }

    /** The declared value reaches the resolver as written, so it can be a key into configuration. */
    @Test
    void apply_placeholderGuildId_isResolvedThroughTheResolver() {
        Map<String, List<String>> config = Map.of("staff", List.of("444", "555"));
        CommandDefinition definition = scoped(new Gamma(),
                command -> config.get(command.getGuildId()));
        assertEquals(Set.of("444", "555"), definition.getGuildIds());
    }

    /** Left in, JDA throws on the guild lookup and every guild after this one goes unregistered. */
    @Test
    void apply_unresolvedPlaceholder_throwsNamingTheCommand() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> scoped(new Gamma(), GuildScopeResolver.declared()));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("'gamma'"), thrown::getMessage),
                () -> assertTrue(thrown.getMessage().contains(Gamma.class.getName()), thrown::getMessage),
                () -> assertTrue(thrown.getMessage().contains("\"staff\""), thrown::getMessage));
    }

    @Test
    void apply_negativeId_isNotASnowflake() {
        assertThrows(IllegalArgumentException.class, () -> scoped(new Alpha(), command -> List.of("-5")));
    }

    @Test
    void byGuild_commandInSeveralGuilds_isInEachGuildsRequest() {
        CommandDefinition alpha = scoped(new Alpha(), command -> List.of("111", "222"));
        CommandDefinition beta = scoped(new Beta(), GuildScopeResolver.declared());

        Map<String, List<CommandData>> byGuild = GuildScopes.byGuild(List.of(alpha, beta));
        assertAll(
                () -> assertEquals(Set.of("111", "222"), byGuild.keySet()),
                () -> assertEquals(List.of("alpha"), names(byGuild.get("111"))),
                () -> assertEquals(List.of("alpha", "beta"), names(byGuild.get("222"))));
    }

    /** One guild reached through an annotation and a resolver, spelled differently, is one request. */
    @Test
    void byGuild_sameGuildFromDifferentSources_isOneRequest() {
        CommandDefinition alpha = scoped(new Alpha(), command -> List.of(" 222"));
        CommandDefinition beta = scoped(new Beta(), GuildScopeResolver.declared());

        Map<String, List<CommandData>> byGuild = GuildScopes.byGuild(List.of(alpha, beta));
        assertAll(
                () -> assertEquals(1, byGuild.size(), () -> byGuild.keySet().toString()),
                () -> assertEquals(2, byGuild.get("222").size()));
    }

    @Test
    void byGuild_globalCommands_areLeftOut() {
        CommandDefinition alpha = scoped(new Alpha(), GuildScopeResolver.declared());
        assertTrue(GuildScopes.byGuild(List.of(alpha)).isEmpty());
    }

    @Test
    void byCommand_matchesTheCommandNameIgnoringCase() {
        CommandDefinition alpha = scoped(new Alpha(), GuildScopeResolver.byCommand(Map.of(" ALPHA", List.of("111"))));
        assertEquals(Set.of("111"), alpha.getGuildIds());
    }

    @Test
    void byCategory_keysDifferingOnlyInCase_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> GuildScopeResolver.byCategory(
                Map.of("Admin", List.of("1"), "admin", List.of("2"))));
    }
}
