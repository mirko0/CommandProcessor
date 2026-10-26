package com.github.mirko0.commandprocessor.help;

import com.github.mirko0.commandprocessor.CommandCategory;
import com.github.mirko0.commandprocessor.definitions.CommandDefinition;
import com.github.mirko0.commandprocessor.internal.CategoryRegistry;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.interactions.Interaction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What the help command is allowed to show a particular person, in a particular place.
 *
 * <p>Two filters, both of which matter:
 *
 * <ul>
 *   <li><strong>Where.</strong> A guild-scoped command only exists in its guild. Listing it
 *       anywhere else advertises something that cannot be run.</li>
 *   <li><strong>Who.</strong> A command declaring
 *       {@link com.github.mirko0.commandprocessor.annotations.CommandInfo#permissions()} is listed
 *       only for a member who holds all of them, checked against the channel the help was opened in
 *       so a channel override is respected. A category is listed only when at least one of its
 *       commands survives both filters.</li>
 * </ul>
 *
 * <p>This is a courtesy, not a security boundary. Discord enforces default member permissions on
 * the command itself; hiding it here only stops the help menu advertising something the user cannot
 * use. A command that must not run without a permission still checks it in its handler.
 *
 * @since 0.1.0
 */
public final class HelpModel {

    private final Supplier<Map<String, CommandDefinition>> commands;
    private final Supplier<CategoryRegistry> categories;

    /**
     * @param commands   the live command map; read lazily so the model can be built before
     *                   registration finishes
     * @param categories the category registry, likewise
     */
    public HelpModel(@NotNull Supplier<Map<String, CommandDefinition>> commands,
                     @NotNull Supplier<CategoryRegistry> categories) {
        this.commands = commands;
        this.categories = categories;
    }

    /** Everything visible to the caller of {@code interaction}, grouped and ordered. */
    @NotNull
    public View viewFor(@NotNull Interaction interaction) {
        Member member = interaction.getMember();
        Guild guild = interaction.getGuild();
        GuildChannel channel = interaction.getChannel() instanceof GuildChannel guildChannel
                ? guildChannel : null;

        List<CommandDefinition> visible = new ArrayList<>();
        for (CommandDefinition command : commands.get().values()) {
            if (isVisible(command, guild, member, channel)) visible.add(command);
        }
        visible.sort(Comparator.comparing(CommandDefinition::getName));

        Map<CommandCategory, List<CommandDefinition>> grouped = new LinkedHashMap<>();
        CategoryRegistry registry = categories.get();
        for (CommandCategory category : registry.all()) {
            List<CommandDefinition> inCategory = visible.stream()
                    .filter(command -> command.getCategoryId().equals(category.id()))
                    .toList();
            if (!inCategory.isEmpty()) grouped.put(category, inCategory);
        }
        return new View(grouped);
    }

    private boolean isVisible(CommandDefinition command, Guild guild, Member member, GuildChannel channel) {
        if (command.isGuildScoped() && (guild == null || !command.getGuildIds().contains(guild.getId()))) {
            return false;
        }

        EnumSet<Permission> required = command.getPermissions();
        if (required.isEmpty()) return true;

        // In a DM there is no member and no permission model; a command gated on a guild permission
        // cannot be used there, so it is not offered.
        if (member == null) return false;

        return channel == null
                ? member.hasPermission(required)
                : member.hasPermission(channel, required);
    }

    /**
     * The categories and commands one person can see, already filtered and ordered.
     *
     * @since 0.1.0
     */
    public static final class View {

        private final Map<CommandCategory, List<CommandDefinition>> byCategory;

        /** Package-private so the renderer's tests can build a view without a live interaction. */
        View(Map<CommandCategory, List<CommandDefinition>> byCategory) {
            this.byCategory = byCategory;
        }

        /** @return categories that have at least one visible command, in display order */
        @NotNull
        public List<CommandCategory> categories() {
            return List.copyOf(byCategory.keySet());
        }

        /** @return the visible commands in {@code category}, sorted by name */
        @NotNull
        public List<CommandDefinition> commandsIn(@NotNull CommandCategory category) {
            return byCategory.getOrDefault(category, List.of());
        }

        /** @return the category with this id, or null when it has nothing visible in it */
        @Nullable
        public CommandCategory category(@Nullable String id) {
            String normalised = CommandCategory.normaliseId(id);
            return byCategory.keySet().stream()
                    .filter(category -> category.id().equals(normalised))
                    .findFirst()
                    .orElse(null);
        }

        /** @return every visible command across all categories */
        @NotNull
        public List<CommandDefinition> allCommands() {
            return byCategory.values().stream().flatMap(List::stream).toList();
        }

        /** @return the total number of visible commands */
        public int commandCount() {
            return byCategory.values().stream().mapToInt(List::size).sum();
        }

        public boolean isEmpty() {
            return byCategory.isEmpty();
        }

        /**
         * Finds a visible command by name or alias.
         *
         * @param  query a command name or alias; case-insensitive, a leading {@code /} is ignored
         * @return the command, or null when there is no visible match
         */
        @Nullable
        public CommandDefinition find(@Nullable String query) {
            if (query == null) return null;
            String needle = query.trim().toLowerCase(Locale.ROOT);
            if (needle.startsWith("/")) needle = needle.substring(1);
            if (needle.isEmpty()) return null;

            // Names first: an alias must never shadow a real command name.
            for (CommandDefinition command : allCommands()) {
                if (command.getName().equals(needle)) return command;
            }
            for (CommandDefinition command : allCommands()) {
                if (command.getAliases().contains(needle)) return command;
            }
            return null;
        }

        /** @return the category a visible command belongs to, or null */
        @Nullable
        public CommandCategory categoryOf(@NotNull CommandDefinition command) {
            return category(command.getCategoryId());
        }
    }
}
