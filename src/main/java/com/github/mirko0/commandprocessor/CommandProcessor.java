package com.github.mirko0.commandprocessor;

import com.github.mirko0.commandprocessor.definitions.CommandDefinition;
import com.github.mirko0.commandprocessor.help.HelpCommand;
import com.github.mirko0.commandprocessor.help.HelpInteractionListener;
import com.github.mirko0.commandprocessor.help.HelpModel;
import com.github.mirko0.commandprocessor.definitions.MessageCommandDefinition;
import com.github.mirko0.commandprocessor.internal.CategoryRegistry;
import com.github.mirko0.commandprocessor.internal.CommandParser;
import com.github.mirko0.commandprocessor.internal.GuildScopes;
import com.github.mirko0.commandprocessor.internal.TriggerIndex;
import com.github.mirko0.commandprocessor.message.MessageCommandConfig;
import com.github.mirko0.commandprocessor.message.MessageCommandHandler;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parses annotated command classes, registers them with Discord, and dispatches interactions to
 * them.
 *
 * <p>Typical use on a sharded bot:
 *
 * <pre>{@code
 * new CommandProcessor(commands, true, List.of(
 *         CommandCategory.of("music", "Music").withEmoji("🎵").withOrder(1),
 *         CommandCategory.of("moderation", "Moderation").withOrder(2)))
 *     .registerAll(shardManager);
 * }</pre>
 *
 * <p>Thread-safety: build on one thread, then read from any. The command map is concurrent, and the
 * dispatcher is only created once every command has been parsed.
 *
 * @since 0.0.1
 */
public final class CommandProcessor {
    private static final Logger log = LoggerFactory.getLogger(CommandProcessor.class);

    private final Map<String, CommandDefinition> commandMap = new ConcurrentHashMap<>();

    private final SlashCommandHandler slashCommandHandler;
    private final HelpInteractionListener helpListener;
    private final MessageCommandHandler messageCommandHandler;
    private final MessageCommandConfig messageCommandConfig;
    private final TriggerIndex triggerIndex;
    private final GuildScopeResolver guildScope;

    /**
     * Assigned after every command has been parsed, because it is derived from them.
     *
     * <p>Not final: the help model is handed a supplier of this field and has to be built first, so
     * that the help command itself is one of the commands parsed. Volatile for safe publication —
     * the dispatcher reads it from JDA event threads.
     */
    private volatile CategoryRegistry categories;

    /**
     * Creates a processor with the built-in help command and no declared categories.
     *
     * @param  commands the commands to register; not modified
     * @throws NullPointerException if {@code commands} is null
     */
    public CommandProcessor(@NotNull Collection<? extends QCommandBase> commands) {
        this(commands, true, List.of());
    }

    /**
     * Creates a processor with no declared categories.
     *
     * @param  commands    the commands to register; not modified
     * @param  helpCommand whether to add the built-in {@code /help}
     * @throws NullPointerException if {@code commands} is null
     */
    public CommandProcessor(@NotNull Collection<? extends QCommandBase> commands, boolean helpCommand) {
        this(commands, helpCommand, List.of());
    }

    /**
     * Creates a processor.
     *
     * @param  commands    the commands to register; <strong>not modified</strong>
     * @param  helpCommand whether to add the built-in {@code /help}. Pass {@code false} if you
     *                     provide your own — two commands cannot share a name
     * @param  categories  the categories commands refer to with
     *                     {@link com.github.mirko0.commandprocessor.annotations.Category @Category};
     *                     an id used by a command but not declared here still forms a category,
     *                     with a warning
     * @throws NullPointerException if {@code commands} is null
     * @since  0.1.0
     */
    public CommandProcessor(@NotNull Collection<? extends QCommandBase> commands,
                            boolean helpCommand,
                            @Nullable Collection<CommandCategory> categories) {
        this(commands, helpCommand, categories, null);
    }

    /**
     * Creates a processor, optionally with message commands.
     *
     * @param  commands        the commands to register; <strong>not modified</strong>
     * @param  helpCommand     whether to add the built-in {@code /help}
     * @param  categories      the categories commands refer to with {@code @Category}
     * @param  messageCommands turns message commands on and says how they are triggered; null
     *                         leaves the feature entirely off, attaching no listener and building
     *                         no trigger index
     * @throws NullPointerException if {@code commands} is null
     * @see    Builder
     * @since  0.3.0
     */
    public CommandProcessor(@NotNull Collection<? extends QCommandBase> commands,
                            boolean helpCommand,
                            @Nullable Collection<CommandCategory> categories,
                            @Nullable MessageCommandConfig messageCommands) {
        this(commands, helpCommand, categories, messageCommands, GuildScopeResolver.declared());
    }

    private CommandProcessor(Collection<? extends QCommandBase> commands,
                             boolean helpCommand,
                             Collection<CommandCategory> categories,
                             MessageCommandConfig messageCommands,
                             GuildScopeResolver guildScope) {
        Objects.requireNonNull(commands, "commands");
        this.guildScope = guildScope;

        // A copy: adding the help command to the caller's list throws on List.of(...) and, under
        // Spring, mutates a collection the container owns.
        List<QCommandBase> all = new ArrayList<>(commands);

        HelpModel helpModel = new HelpModel(() -> commandMap, () -> this.categories);
        if (helpCommand) all.add(new HelpCommand(helpModel));

        register(all);

        this.categories = CategoryRegistry.of(categories, commandMap.values());
        this.slashCommandHandler = new SlashCommandHandler(commandMap);
        this.helpListener = helpCommand ? new HelpInteractionListener(helpModel) : null;

        this.messageCommandConfig = messageCommands;
        this.triggerIndex = messageCommands == null
                ? TriggerIndex.empty()
                : TriggerIndex.of(all, messageCommands);
        this.messageCommandHandler = triggerIndex.isEmpty()
                ? null
                : new MessageCommandHandler(triggerIndex.triggers(), messageCommands);

        log.info("Command processor initialized with {} commands in {} categories{}",
                commandMap.size(), this.categories.all().size(),
                triggerIndex.isEmpty() ? "" : ", " + triggerIndex.triggers().size() + " message triggers");
    }

    /**
     * Starts building a processor.
     *
     * <p>Preferred over the constructors once more than the commands and the help flag are involved.
     *
     * @return a new builder
     * @since  0.3.0
     */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Registers the provided commands.
     *
     * <p>A command that cannot be parsed is reported and skipped. It used to take the constructor -
     * and so the whole application's startup - down with it, which meant one bad description
     * stopped every other command in the bot from existing.
     *
     * <p>Each command's guilds are decided here, by the {@link Builder#guildScope guild scope
     * resolver} when one was given; a command whose scope cannot be resolved is skipped the same way.
     *
     * @param commands the commands to register; not modified
     */
    public void register(@NotNull Collection<? extends QCommandBase> commands) {
        Objects.requireNonNull(commands, "commands");

        for (QCommandBase command : commands) {
            if (command == null) continue;
            try {
                CommandDefinition cmdDef = CommandParser.parseCommand(command);
                if (cmdDef == null) {
                    log.warn("{} implements QCommandBase but carries no @CommandInfo, so it was skipped.",
                            command.getClass().getName());
                    continue;
                }
                GuildScopes.apply(cmdDef, guildScope);

                CommandDefinition previous = commandMap.putIfAbsent(cmdDef.getName(), cmdDef);
                if (previous != null) {
                    log.warn("Two commands are named '{}' ({} and {}). Keeping the first; the second "
                                    + "is ignored.",
                            cmdDef.getName(),
                            previous.getInstance().getClass().getSimpleName(),
                            command.getClass().getSimpleName());
                }
            } catch (RuntimeException failure) {
                log.error("Could not register command {}; skipping it.",
                        command.getClass().getName(), failure);
            }
        }
    }

    /**
     * Registers the dispatcher, and the help listener when the built-in help command is enabled.
     *
     * @param jda the shard to listen on
     */
    public void registerListener(@NotNull JDA jda) {
        jda.addEventListener(slashCommandHandler);
        if (helpListener != null) jda.addEventListener(helpListener);
        if (messageCommandHandler != null) {
            jda.addEventListener(messageCommandHandler);
            triggerIndex.warnAboutIntents(jda, messageCommandConfig);
        }
    }

    /**
     * Registers the dispatcher, and the help listener when the built-in help command is enabled.
     *
     * @param shardManager the manager to listen on
     */
    public void registerListener(@NotNull ShardManager shardManager) {
        shardManager.addEventListener(slashCommandHandler);
        if (helpListener != null) shardManager.addEventListener(helpListener);
        if (messageCommandHandler != null) {
            shardManager.addEventListener(messageCommandHandler);
            shardManager.getShards().stream().findFirst()
                    .ifPresent(shard -> triggerIndex.warnAboutIntents(shard, messageCommandConfig));
        }
    }

    /**
     * Registers everything for a sharded bot: global commands, guild commands, and the listeners.
     *
     * <p>The one call to make from a sharded bot. Doing the three steps by hand invites the mistake
     * of pushing the global commands once per shard.
     *
     * <p>Call it after the shards are ready — a guild whose shard has not connected cannot be
     * resolved, and its guild-scoped commands are skipped with a warning.
     *
     * @param shardManager the shard manager
     * @since 0.0.4
     */
    public void registerAll(@NotNull ShardManager shardManager) {
        registerGlobalCommands(shardManager);
        registerGuildCommands(shardManager);
        registerListener(shardManager);
    }

    /**
     * Registers global commands, once.
     *
     * <p>Global commands belong to the application, not to a shard: the request is
     * {@code PUT /applications/{id}/commands} whichever shard sends it. Calling this per shard
     * therefore sends the same payload N times on every boot and achieves nothing after the first.
     *
     * @param shardManager the shard manager; one of its shards is used
     * @since 0.0.4
     */
    public void registerGlobalCommands(@NotNull ShardManager shardManager) {
        JDA shard = shardManager.getShards().stream().findFirst().orElse(null);
        if (shard == null) {
            log.warn("No shards available; global commands were not registered.");
            return;
        }
        registerGlobalCommands(shard);
    }

    /**
     * Registers global commands through one JDA instance.
     *
     * <p>This replaces the application's entire set of global commands. On a sharded bot call
     * {@link #registerGlobalCommands(ShardManager)} instead, or call this with a single shard —
     * never once per shard.
     *
     * @param jda the shard to send the request on
     */
    public void registerGlobalCommands(@NotNull JDA jda) {
        List<CommandData> globalCommands = commandMap.values().stream()
                .filter(definition -> !definition.isGuildScoped())
                .map(CommandDefinition::getCommandData)
                .toList();

        if (globalCommands.isEmpty()) {
            log.info("No global commands to register.");
            return;
        }

        jda.updateCommands().addCommands(globalCommands).queue(
                success -> log.info("Registered {} global command(s).", globalCommands.size()),
                error -> log.error("Failed to register global commands", error));
    }

    /**
     * Registers guild-specific commands.
     *
     * <p>Resolves each guild through the shard manager, which searches every shard — a guild-scoped
     * command is almost never on the shard doing the registering, so this is the overload a sharded
     * bot wants.
     *
     * <p><strong>This replaces every command registered to that guild</strong>, including any put
     * there by something other than this processor: {@code updateCommands()} is a full {@code PUT}.
     * Every command scoped to a guild, however its scope was decided, goes in that guild's one
     * request.
     *
     * @param shardManager the shard manager
     */
    public void registerGuildCommands(@NotNull ShardManager shardManager) {
        registerGuildCommands(null, shardManager);
    }

    /**
     * Registers guild-specific commands through one JDA instance.
     *
     * <p>Only finds guilds on that shard. On a sharded bot use
     * {@link #registerGuildCommands(ShardManager)}.
     *
     * <p><strong>This replaces every command registered to that guild.</strong>
     *
     * @param jda the shard whose guilds to register in
     */
    public void registerGuildCommands(@NotNull JDA jda) {
        registerGuildCommands(jda, null);
    }

    private void registerGuildCommands(JDA jda, ShardManager shardManager) {
        GuildScopes.byGuild(commandMap.values()).forEach((guildId, commands) -> {
            Guild guild = shardManager == null ? jda.getGuildById(guildId) : shardManager.getGuildById(guildId);
            if (guild == null) {
                log.warn("Guild {} not found, cannot register {} guild command(s). The bot is either not "
                                + "in that guild, or its shard is not ready yet - register guild commands "
                                + "after the shards are up.",
                        guildId, commands.size());
                return;
            }
            guild.updateCommands().addCommands(commands).queue(
                    success -> log.info("Registered {} command(s) in guild {} ({}).",
                            commands.size(), guild.getName(), guildId),
                    error -> log.error("Failed to register commands in guild {}", guildId, error));
        });
    }

    /**
     * Every registered command, keyed by name.
     *
     * @return an unmodifiable view; the underlying map is live, so it reflects later registrations
     */
    @NotNull
    public Map<String, CommandDefinition> getCommandMap() {
        return Collections.unmodifiableMap(commandMap);
    }

    /**
     * Every category in use, in display order.
     *
     * @return an unmodifiable list
     * @since  0.1.0
     */
    @NotNull
    public List<CommandCategory> getCategories() {
        return List.copyOf(categories.all());
    }

    /** @return the slash command dispatcher */
    @NotNull
    public SlashCommandHandler getSlashCommandHandler() {
        return slashCommandHandler;
    }

    /**
     * Every message-command trigger that was registered.
     *
     * @return an unmodifiable map keyed by trigger; empty when message commands are off
     * @since  0.3.0
     */
    @NotNull
    public Map<String, MessageCommandDefinition> getMessageTriggers() {
        return triggerIndex.triggers();
    }

    /**
     * Builds a {@link CommandProcessor}.
     *
     * @since 0.3.0
     */
    public static final class Builder {

        private Collection<? extends QCommandBase> commands = List.of();
        private boolean helpCommand = true;
        private Collection<CommandCategory> categories = List.of();
        private MessageCommandConfig messageCommands;
        private GuildScopeResolver guildScope = GuildScopeResolver.declared();

        private Builder() {
        }

        /**
         * @param  commands the commands to register; not modified
         * @return this builder
         */
        @NotNull
        public Builder commands(@NotNull Collection<? extends QCommandBase> commands) {
            this.commands = Objects.requireNonNull(commands, "commands");
            return this;
        }

        /**
         * @param  helpCommand whether to add the built-in {@code /help}; pass false when you provide
         *                     your own, since two commands cannot share a name
         * @return this builder
         */
        @NotNull
        public Builder helpCommand(boolean helpCommand) {
            this.helpCommand = helpCommand;
            return this;
        }

        /**
         * @param  categories the categories {@code @Category} refers to
         * @return this builder
         */
        @NotNull
        public Builder categories(@Nullable Collection<CommandCategory> categories) {
            this.categories = categories == null ? List.of() : categories;
            return this;
        }

        /**
         * Turns message commands on.
         *
         * @param  messageCommands how messages are matched; null leaves the feature off
         * @return this builder
         */
        @NotNull
        public Builder messageCommands(@Nullable MessageCommandConfig messageCommands) {
            this.messageCommands = messageCommands;
            return this;
        }

        /**
         * Decides each command's guilds at runtime, replacing what {@code @CommandInfo(guildId)}
         * declared.
         *
         * @param  guildScope the resolver; null keeps every command's declared scope
         * @return this builder
         * @see    GuildScopeResolver
         * @since  0.5.0
         */
        @NotNull
        public Builder guildScope(@Nullable GuildScopeResolver guildScope) {
            this.guildScope = guildScope == null ? GuildScopeResolver.declared() : guildScope;
            return this;
        }

        /** @return the processor */
        @NotNull
        public CommandProcessor build() {
            return new CommandProcessor(commands, helpCommand, categories, messageCommands, guildScope);
        }
    }
}
