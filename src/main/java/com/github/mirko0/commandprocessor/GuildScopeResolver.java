package com.github.mirko0.commandprocessor;

import com.github.mirko0.commandprocessor.definitions.CommandDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Decides at runtime which guilds a command is registered in.
 *
 * <p>{@link com.github.mirko0.commandprocessor.annotations.CommandInfo#guildId() CommandInfo.guildId}
 * is a compile-time constant, so a bot that reads its staff or test guild from configuration cannot
 * use it. A resolver is consulted for every command as it is registered, after parsing, and its
 * answer replaces the declared scope:
 *
 * <pre>{@code
 * CommandProcessor.builder()
 *         .commands(commands)
 *         .guildScope(GuildScopeResolver.byCategory(Map.of(
 *                 "admin", config.getStaffGuildIds())))
 *         .build();
 * }</pre>
 *
 * <p>The command handed in carries what was declared, in {@link CommandDefinition#getGuildIds()},
 * exactly as written — so {@code guildId} can also hold a key the resolver looks up, such as
 * {@code guildId = "staff"}.
 *
 * <p>Every id returned must be a guild snowflake. A command whose resolved scope contains anything
 * else — including a blank id, the usual sign of an unset config value — or whose resolver throws,
 * is reported and skipped rather than registered globally by accident; the other commands are
 * unaffected.
 *
 * <p>Implementations are called on the thread that constructs the processor, or that calls
 * {@link CommandProcessor#register}, and need not be thread-safe beyond that.
 *
 * @since 0.5.0
 */
@FunctionalInterface
public interface GuildScopeResolver {

    /**
     * The guilds {@code command} should be registered in.
     *
     * @param  command the parsed command; its {@link CommandDefinition#getGuildIds()} is the
     *                 declared scope
     * @return the guild ids to register it in — empty registers it globally, and null keeps the
     *         declared scope
     */
    @Nullable
    Collection<String> resolve(@NotNull CommandDefinition command);

    /**
     * Keeps whatever each command declared. The default.
     *
     * @return a resolver that changes nothing
     */
    @NotNull
    static GuildScopeResolver declared() {
        return CommandDefinition::getGuildIds;
    }

    /**
     * Scopes commands by name, keeping the declared scope of any command not in the map.
     *
     * @param  guildsByCommand guild ids keyed by command name, matched case-insensitively; an empty
     *                         collection makes that command global. Copied, so later changes to
     *                         the map are not seen
     * @return the resolver
     * @throws NullPointerException     if {@code guildsByCommand} or any key or value is null
     * @throws IllegalArgumentException if two keys differ only in case
     */
    @NotNull
    static GuildScopeResolver byCommand(@NotNull Map<String, ? extends Collection<String>> guildsByCommand) {
        Map<String, Collection<String>> byName = lowercaseKeys(guildsByCommand, "guildsByCommand");
        return command -> byName.get(command.getName());
    }

    /**
     * Scopes commands by {@link com.github.mirko0.commandprocessor.annotations.Category category},
     * keeping the declared scope of any command whose category is not in the map.
     *
     * @param  guildsByCategory guild ids keyed by category id, matched case-insensitively; an empty
     *                          collection makes that category global. Copied, so later changes to
     *                          the map are not seen
     * @return the resolver
     * @throws NullPointerException     if {@code guildsByCategory} or any key or value is null
     * @throws IllegalArgumentException if two keys differ only in case
     */
    @NotNull
    static GuildScopeResolver byCategory(@NotNull Map<String, ? extends Collection<String>> guildsByCategory) {
        Map<String, Collection<String>> byCategory = lowercaseKeys(guildsByCategory, "guildsByCategory");
        return command -> byCategory.get(command.getCategoryId());
    }

    private static Map<String, Collection<String>> lowercaseKeys(
            Map<String, ? extends Collection<String>> map, String name) {
        Objects.requireNonNull(map, name);
        Map<String, Collection<String>> copy = new HashMap<>();
        map.forEach((key, guildIds) -> {
            Objects.requireNonNull(key, name + " key");
            Objects.requireNonNull(guildIds, name + " value for " + key);
            String normalised = key.trim().toLowerCase(Locale.ROOT);
            if (copy.put(normalised, List.copyOf(guildIds)) != null) {
                throw new IllegalArgumentException(name + " has more than one entry for '" + normalised
                        + "' once case is ignored.");
            }
        });
        return Map.copyOf(copy);
    }
}
