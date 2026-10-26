package com.github.mirko0.commandprocessor.internal;

import com.github.mirko0.commandprocessor.GuildScopeResolver;
import com.github.mirko0.commandprocessor.definitions.CommandDefinition;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves which guilds each command belongs to, and groups commands into one request per guild.
 *
 * <p>The grouping is the part that has to be right. {@code guild.updateCommands()} is a full
 * {@code PUT}, so a guild that appeared under two keys — {@code "123"} and {@code " 123"}, say —
 * would get two requests and keep only whichever landed last.
 *
 * <p><strong>Internal.</strong> Not part of the public API and may change in any release.
 *
 * @since 0.5.0
 */
public final class GuildScopes {

    private static final Pattern SNOWFLAKE = Pattern.compile("[0-9]{1,20}");

    private GuildScopes() {
    }

    /**
     * Applies {@code resolver} to {@code command} and stores the validated result on it.
     *
     * @param  command  a freshly parsed command, not yet visible to the dispatcher
     * @param  resolver decides the scope; its null answer keeps the declared one
     * @throws IllegalArgumentException naming the command when a resolved id is not a snowflake
     */
    public static void apply(CommandDefinition command, GuildScopeResolver resolver) {
        Collection<String> resolved = resolver.resolve(command);
        if (resolved != null) {
            // A blank id is usually an unset config value. Dropping it could leave the list empty and
            // register a command meant for one guild everywhere.
            for (String guildId : resolved) {
                if (guildId == null || guildId.isBlank()) throw notAGuild(command, guildId);
            }
            command.setGuildIds(resolved);
        }

        for (String guildId : command.getGuildIds()) {
            // Left alone, JDA throws on the lookup during registerGuildCommands and every guild after
            // this one goes unregistered.
            if (!isSnowflake(guildId)) throw notAGuild(command, guildId);
        }
    }

    /**
     * Groups guild-scoped commands by guild, so each guild is sent exactly one request.
     *
     * @param  commands every registered command; global ones are ignored
     * @return the commands for each guild, in a stable order
     */
    public static Map<String, List<CommandData>> byGuild(Collection<CommandDefinition> commands) {
        Map<String, List<CommandData>> byGuild = new LinkedHashMap<>();
        for (CommandDefinition command : commands) {
            for (String guildId : command.getGuildIds()) {
                byGuild.computeIfAbsent(guildId, id -> new ArrayList<>()).add(command.getCommandData());
            }
        }
        return byGuild;
    }

    private static IllegalArgumentException notAGuild(CommandDefinition command, String guildId) {
        return new IllegalArgumentException("Command '" + command.getName() + "' ("
                + command.getInstance().getClass().getName() + ") is scoped to guild \"" + guildId
                + "\", which is not a guild id. Declare a numeric id in guildId, or resolve it "
                + "with a GuildScopeResolver.");
    }

    private static boolean isSnowflake(String id) {
        if (!SNOWFLAKE.matcher(id).matches()) return false;
        try {
            Long.parseUnsignedLong(id);
            return true;
        } catch (NumberFormatException overflow) {
            return false;
        }
    }
}
