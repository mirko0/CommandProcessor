package com.github.mirko0.commandprocessor.definitions;

import com.github.mirko0.commandprocessor.CommandCategory;
import com.github.mirko0.commandprocessor.QCommandBase;
import com.github.mirko0.commandprocessor.annotations.LocalizedEntry;
import com.github.mirko0.commandprocessor.enumeration.Response;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A parsed command: what was declared on the class, and the reflection handles needed to invoke it.
 *
 * <p>Built by the parser and read afterwards by the dispatcher and the help command. Treat an
 * instance handed to you as read-only — the setters exist for the parser, and mutating a definition
 * after registration changes what a live dispatcher sees.
 *
 * <p>Not thread-safe to write. Safe to read concurrently once registration has completed.
 *
 * @since 0.0.1
 */
public class CommandDefinition {

    private String name;
    private LocalizedEntry[] localizedNames = new LocalizedEntry[0];
    private String description;
    private LocalizedEntry[] localizedDescriptions = new LocalizedEntry[0];
    private List<String> aliases = List.of();
    private QCommandBase instance;
    private CommandData commandData;
    private Method handlerMethod;
    private Response defaultResponse;
    private List<SubcommandDefinition> subcommands = List.of();
    private Map<String, SubcommandGroupDefinition> groups = Map.of();
    private Set<String> guildIds = Set.of();
    private boolean nsfw;
    private String categoryId = CommandCategory.DEFAULT_ID;
    private List<AutoCompleteDefinition> autoCompletes = List.of();
    private EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);

    /** @return the command name as registered with Discord, lowercase */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalizedEntry[] getLocalizedNames() {
        return localizedNames.clone();
    }

    public void setLocalizedNames(LocalizedEntry[] localizedNames) {
        this.localizedNames = localizedNames == null ? new LocalizedEntry[0] : localizedNames.clone();
    }

    /** @return the description shown in Discord; never null once parsed */
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalizedEntry[] getLocalizedDescriptions() {
        return localizedDescriptions.clone();
    }

    public void setLocalizedDescriptions(LocalizedEntry[] localizedDescriptions) {
        this.localizedDescriptions = localizedDescriptions == null
                ? new LocalizedEntry[0] : localizedDescriptions.clone();
    }

    /**
     * Alternative names, not registered with Discord.
     *
     * @return an unmodifiable list, empty when none were declared
     * @since  0.1.0
     */
    @NotNull
    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    /** @return the command object the handler methods are invoked on */
    public QCommandBase getInstance() {
        return instance;
    }

    public void setInstance(QCommandBase instance) {
        this.instance = instance;
    }

    public CommandData getCommandData() {
        return commandData;
    }

    public void setCommandData(CommandData commandData) {
        this.commandData = commandData;
    }

    /** @return the base handler, or null when the command only has subcommands */
    @Nullable
    public Method getHandlerMethod() {
        return handlerMethod;
    }

    public void setHandlerMethod(Method handlerMethod) {
        this.handlerMethod = handlerMethod;
    }

    public Response getDefaultResponse() {
        return defaultResponse;
    }

    public void setDefaultResponse(Response defaultResponse) {
        this.defaultResponse = defaultResponse;
    }

    /** @return an unmodifiable list of ungrouped subcommands */
    @NotNull
    public List<SubcommandDefinition> getSubcommands() {
        return subcommands;
    }

    public void setSubcommands(List<SubcommandDefinition> subcommands) {
        this.subcommands = subcommands == null ? List.of() : List.copyOf(subcommands);
    }

    /** @return an unmodifiable map of subcommand groups, keyed by lowercase group name */
    @NotNull
    public Map<String, SubcommandGroupDefinition> getGroups() {
        return groups;
    }

    public void setGroups(Map<String, SubcommandGroupDefinition> groups) {
        this.groups = groups == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(groups));
    }

    /**
     * Every subcommand this command has, grouped and ungrouped, in listing order.
     *
     * @return a new list; empty when the command has no subcommands
     * @since  0.1.0
     */
    @NotNull
    public List<SubcommandDefinition> getAllSubcommands() {
        List<SubcommandDefinition> all = new ArrayList<>(subcommands);
        groups.values().forEach(group -> all.addAll(group.getSubcommands()));
        return all;
    }

    /**
     * @return the guild this command is registered to, or empty — never null — when it is global.
     *         When it is registered to several, the first of {@link #getGuildIds()}
     */
    @NotNull
    public String getGuildId() {
        return guildIds.isEmpty() ? "" : guildIds.iterator().next();
    }

    /** @param guildId the one guild to register in; null or blank makes the command global */
    public void setGuildId(String guildId) {
        setGuildIds(guildId == null ? List.of() : List.of(guildId));
    }

    /**
     * Every guild this command is registered to.
     *
     * @return an unmodifiable set in declaration order; empty when the command is global
     * @since  0.5.0
     */
    @NotNull
    public Set<String> getGuildIds() {
        return guildIds;
    }

    /**
     * @param guildIds the guilds to register in; trimmed, with blanks and duplicates dropped. Null
     *                 or empty makes the command global
     * @since 0.5.0
     */
    public void setGuildIds(Collection<String> guildIds) {
        Set<String> normalised = new LinkedHashSet<>();
        if (guildIds != null) {
            for (String guildId : guildIds) {
                if (guildId != null && !guildId.isBlank()) normalised.add(guildId.trim());
            }
        }
        this.guildIds = Collections.unmodifiableSet(normalised);
    }

    /**
     * @return true when the command is registered to specific guilds rather than globally
     * @since  0.1.0
     */
    public boolean isGuildScoped() {
        return !guildIds.isEmpty();
    }

    public boolean isNsfw() {
        return nsfw;
    }

    /**
     * @param nsfw whether the command is age-restricted
     * @since 0.1.0
     */
    public void setNsfw(boolean nsfw) {
        this.nsfw = nsfw;
    }

    /**
     * @param nsfw whether the command is age-restricted
     * @deprecated since 0.1.0, use {@link #setNsfw(boolean)} — {@code setNSFW} does not pair with
     *             {@link #isNsfw()} as a JavaBean accessor. Removal is planned for 1.0.0.
     */
    @Deprecated(since = "0.1.0", forRemoval = true)
    public void setNSFW(boolean nsfw) {
        setNsfw(nsfw);
    }

    /**
     * The category this command belongs to.
     *
     * @return a lowercase category id; {@link CommandCategory#DEFAULT_ID} when none was declared
     * @since  0.1.0
     */
    @NotNull
    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        String normalised = CommandCategory.normaliseId(categoryId);
        this.categoryId = normalised.isEmpty() ? CommandCategory.DEFAULT_ID : normalised;
    }

    /**
     * @return the category id
     * @deprecated since 0.1.0, use {@link #getCategoryId()}. Removal is planned for 1.0.0.
     */
    @Deprecated(since = "0.1.0", forRemoval = true)
    public String getModuleId() {
        return categoryId;
    }

    /**
     * @param moduleId the category id
     * @deprecated since 0.1.0, use {@link #setCategoryId(String)}. Removal is planned for 1.0.0.
     */
    @Deprecated(since = "0.1.0", forRemoval = true)
    public void setModuleId(String moduleId) {
        setCategoryId(moduleId);
    }

    /**
     * Permissions a member must hold to use this command.
     *
     * @return an unmodifiable set, empty when the command is open to everyone
     * @since  0.1.0
     */
    @NotNull
    public EnumSet<Permission> getPermissions() {
        return EnumSet.copyOf(permissions);
    }

    public void setPermissions(Permission[] permissions) {
        this.permissions = permissions == null || permissions.length == 0
                ? EnumSet.noneOf(Permission.class)
                : EnumSet.copyOf(Arrays.asList(permissions));
    }

    /**
     * The autocomplete handlers declared on this command.
     *
     * @return an unmodifiable list, empty when none were declared
     * @since  0.2.0
     */
    @NotNull
    public List<AutoCompleteDefinition> getAutoCompletes() {
        return autoCompletes;
    }

    public void setAutoCompletes(List<AutoCompleteDefinition> autoCompletes) {
        this.autoCompletes = autoCompletes == null ? List.of() : List.copyOf(autoCompletes);
    }

    /**
     * The handler that should answer for the option currently being completed.
     *
     * <p>The most specific match wins: an option name is only unique within a subcommand, so a
     * handler scoped to one subcommand takes precedence over a catch-all for the same option name.
     *
     * @param  group  the subcommand group of the invocation, or null
     * @param  sub    the subcommand of the invocation, or null
     * @param  option the focused option's name
     * @return the handler, or null when nothing matches
     * @since  0.2.0
     */
    @Nullable
    public AutoCompleteDefinition findAutoComplete(@Nullable String group, @Nullable String sub,
                                                   @Nullable String option) {
        AutoCompleteDefinition best = null;
        for (AutoCompleteDefinition candidate : autoCompletes) {
            if (!candidate.matches(group, sub, option)) continue;
            if (best == null || candidate.specificity() > best.specificity()) best = candidate;
        }
        return best;
    }

    @Override
    public String toString() {
        return "CommandDefinition[" + name + (isGuildScoped() ? "@" + String.join(",", guildIds) : "") + "]";
    }
}
