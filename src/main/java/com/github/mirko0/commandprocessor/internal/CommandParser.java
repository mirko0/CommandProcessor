package com.github.mirko0.commandprocessor.internal;

import com.github.mirko0.commandprocessor.QCommandBase;
import com.github.mirko0.commandprocessor.annotations.AutoCompleteHandler;
import com.github.mirko0.commandprocessor.annotations.Category;
import com.github.mirko0.commandprocessor.annotations.CommandHandler;
import com.github.mirko0.commandprocessor.annotations.CommandInfo;
import com.github.mirko0.commandprocessor.annotations.CommandOption;
import com.github.mirko0.commandprocessor.annotations.Group;
import com.github.mirko0.commandprocessor.annotations.SubCommandGroupInfo;
import com.github.mirko0.commandprocessor.annotations.SubCommandHandler;
import com.github.mirko0.commandprocessor.definitions.AutoCompleteDefinition;
import com.github.mirko0.commandprocessor.definitions.CommandDefinition;
import com.github.mirko0.commandprocessor.definitions.SubcommandDefinition;
import com.github.mirko0.commandprocessor.definitions.SubcommandGroupDefinition;
import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns an annotated command class into a {@link CommandDefinition}.
 *
 * <p><strong>Internal.</strong> Not part of the public API and may change in any release.
 */
public final class CommandParser {

    private static final Logger log = LoggerFactory.getLogger(CommandParser.class);

    /**
     * Discord's limits on the text it will accept.
     *
     * <p>Anything longer is trimmed with a warning rather than left to throw. JDA validates these
     * when the command data is built, and that exception propagates out of the whole registration -
     * so one over-long subcommand description used to stop every command in the bot from being
     * registered.
     */
    private static final int MAX_NAME = 32;
    private static final int MAX_DESCRIPTION = 100;
    private static final int MAX_CHOICES = 25;

    /** Substituted when a description is missing, because JDA refuses to build a command without one. */
    private static final String MISSING_DESCRIPTION = "No description set.";

    /** Option types Discord accepts choices on. */
    private static final Set<OptionType> CHOICEABLE =
            EnumSet.of(OptionType.STRING, OptionType.INTEGER, OptionType.NUMBER);

    private CommandParser() {
    }

    /**
     * Parses a command class.
     *
     * @param  command the command instance
     * @return its definition, or null when the class carries no {@link CommandInfo}
     */
    public static CommandDefinition parseCommand(QCommandBase command) {
        Class<?> clazz = command.getClass();
        CommandInfo info = Annotations.find(clazz, CommandInfo.class);
        if (info == null) return null;

        String origin = clazz.getSimpleName();
        String cmdName = trim(info.name().toLowerCase(Locale.ROOT), MAX_NAME, "Command name", origin);
        String cmdDescription = description(info.description(), "Command '" + cmdName + "'", origin);

        CommandDefinition cmdDef = new CommandDefinition();
        cmdDef.setName(cmdName);
        cmdDef.setInstance(command);
        if (!info.guildId().isEmpty() && info.guildId().isBlank()) {
            // Blank would otherwise read as "no guild" and register a guild command globally.
            throw new IllegalArgumentException("Command '" + cmdName + "' (" + clazz.getName()
                    + ") declares a blank guildId. Leave it out for a global command.");
        }
        cmdDef.setGuildId(info.guildId());
        cmdDef.setNsfw(info.nsfw());
        cmdDef.setLocalizedNames(info.localizedNames());
        cmdDef.setLocalizedDescriptions(info.localizedDescriptions());
        cmdDef.setDescription(cmdDescription);
        cmdDef.setPermissions(info.permissions());
        cmdDef.setAliases(aliases(info, cmdName, origin));
        cmdDef.setCategoryId(categoryOf(clazz, info));

        cmdDef.setCommandData(buildCommandData(info, cmdName, cmdDescription));

        List<Method> methods = Annotations.handlerMethods(clazz);
        OptionData[] defOptions = parseBaseHandler(cmdDef, methods, origin);
        boolean hadSubCommands = parseSubcommands(cmdDef, info, methods, origin);
        cmdDef.setAutoCompletes(parseAutoCompleteHandlers(methods, origin));
        warnAboutAutoCompleteGaps(cmdDef, origin);

        if (!hadSubCommands && defOptions != null) {
            slashData(cmdDef).addOptions(defOptions);
        } else if (hadSubCommands && cmdDef.getHandlerMethod() != null) {
            // Discord never delivers a bare invocation of a command that has subcommands, so the
            // base handler and its options are unreachable. Say so rather than let it look wired up.
            log.warn("Command '{}' ({}) declares both @CommandHandler and @SubCommandHandler. "
                            + "Discord will never invoke the base handler, so it and its options are ignored.",
                    cmdDef.getName(), origin);
        }

        return cmdDef;
    }

    /** Every {@link AutoCompleteHandler} on the class, in a stable order. */
    private static List<AutoCompleteDefinition> parseAutoCompleteHandlers(List<Method> methods, String origin) {
        List<AutoCompleteDefinition> handlers = new ArrayList<>();

        for (Method method : methods) {
            AutoCompleteHandler anno = method.getAnnotation(AutoCompleteHandler.class);
            if (anno == null) continue;

            if (anno.option().isBlank()) {
                log.error("@AutoCompleteHandler on {}.{} names no option, so it can never be matched. "
                        + "It is ignored.", origin, method.getName());
                continue;
            }
            if (!Collection.class.isAssignableFrom(method.getReturnType())) {
                log.error("@AutoCompleteHandler {}.{} returns {}; it must return a Collection of "
                                + "Choice, String or Number. It is ignored.",
                        origin, method.getName(), method.getReturnType().getSimpleName());
                continue;
            }

            Annotations.makeAccessible(method, origin);

            AutoCompleteDefinition definition = new AutoCompleteDefinition();
            definition.setOptionName(anno.option());
            definition.setSubcommandName(anno.subcommand());
            definition.setGroupName(anno.group());
            definition.setHandlerMethod(method);
            handlers.add(definition);
        }

        handlers.sort(Comparator.comparing(AutoCompleteDefinition::getOptionName)
                .thenComparingInt(handler -> -handler.specificity()));
        return handlers;
    }

    /**
     * Reports the two ways autocomplete silently does nothing.
     *
     * <p>An option marked {@code autoComplete} with no handler shows the user an empty dropdown that
     * spins and then gives up; a handler for an option that is not marked never runs. Neither
     * produces an error anywhere without this.
     */
    private static void warnAboutAutoCompleteGaps(CommandDefinition cmdDef, String origin) {
        Set<String> completable = completableOptionNames(cmdDef);
        Set<String> handled = new LinkedHashSet<>();
        cmdDef.getAutoCompletes().forEach(handler -> handled.add(handler.getOptionName()));

        for (String option : completable) {
            if (handled.contains(option)) continue;
            log.warn("Option '{}' of '/{}' ({}) is declared autoComplete = true but the class has no "
                            + "matching @AutoCompleteHandler. Discord will show an empty list.",
                    option, cmdDef.getName(), origin);
        }
        for (String option : handled) {
            if (completable.contains(option)) continue;
            log.warn("@AutoCompleteHandler for option '{}' on {} matches no option of '/{}' that is "
                            + "declared autoComplete = true, so it will never run.",
                    option, origin, cmdDef.getName());
        }
    }

    /** The names of every option on this command that asked for autocomplete. */
    private static Set<String> completableOptionNames(CommandDefinition cmdDef) {
        SlashCommandData data = slashData(cmdDef);
        Set<String> names = new LinkedHashSet<>();

        collectCompletable(data.getOptions(), names);
        data.getSubcommands().forEach(sub -> collectCompletable(sub.getOptions(), names));
        data.getSubcommandGroups().forEach(group -> group.getSubcommands()
                .forEach(sub -> collectCompletable(sub.getOptions(), names)));
        return names;
    }

    private static void collectCompletable(List<OptionData> options, Set<String> names) {
        options.stream().filter(OptionData::isAutoComplete).forEach(option -> names.add(option.getName()));
    }

    private static SlashCommandData slashData(CommandDefinition cmdDef) {
        return (SlashCommandData) cmdDef.getCommandData();
    }

    private static SlashCommandData buildCommandData(CommandInfo info, String cmdName, String cmdDescription) {
        SlashCommandData data = Commands.slash(cmdName, cmdDescription);
        data.setNSFW(info.nsfw());

        if (info.localizedNames().length > 0)
            data.setNameLocalizations(LocalizationParser.getLocalizedEntries(info.localizedNames()));
        if (info.localizedDescriptions().length > 0)
            data.setDescriptionLocalizations(LocalizationParser.getLocalizedEntries(info.localizedDescriptions()));

        applyContexts(data, info);

        if (info.permissions().length > 0) {
            data.setDefaultPermissions(DefaultMemberPermissions.enabledFor(info.permissions()));
        }
        return data;
    }

    /**
     * Applies contexts and integration types, preferring the plural members.
     *
     * <p>The singular ones are deprecated and honoured only while the plural ones are empty, so a
     * command that sets both is not silently half-configured.
     */
    @SuppressWarnings({"deprecation", "removal"})
    private static void applyContexts(SlashCommandData data, CommandInfo info) {
        if (info.contexts().length > 0) {
            data.setContexts(info.contexts());
        } else if (info.contextType() != InteractionContextType.UNKNOWN) {
            data.setContexts(info.contextType());
        }

        if (info.integrationTypes().length > 0) {
            data.setIntegrationTypes(info.integrationTypes());
        } else if (info.integrationType() != IntegrationType.UNKNOWN) {
            data.setIntegrationTypes(info.integrationType());
        }
    }

    /** The category id, preferring {@code @Category} over the deprecated {@code moduleId}. */
    @SuppressWarnings({"deprecation", "removal"})
    private static String categoryOf(Class<?> clazz, CommandInfo info) {
        Category category = Annotations.find(clazz, Category.class);
        if (category != null && !category.value().isBlank()) return category.value();
        return info.moduleId();
    }

    /** Aliases, lowercased and de-duplicated, with the command's own name removed. */
    private static List<String> aliases(CommandInfo info, String cmdName, String origin) {
        List<String> aliases = new ArrayList<>();
        for (String raw : info.aliases()) {
            if (raw == null || raw.isBlank()) continue;
            String alias = trim(raw.trim().toLowerCase(Locale.ROOT), MAX_NAME, "Alias", origin);
            if (alias.equals(cmdName) || aliases.contains(alias)) continue;
            aliases.add(alias);
        }
        return aliases;
    }

    /**
     * Finds the base {@link CommandHandler} and returns its options.
     *
     * @return the base handler's options, or null when the class declares no base handler
     */
    private static OptionData[] parseBaseHandler(CommandDefinition cmdDef, List<Method> methods, String origin) {
        OptionData[] defOptions = null;

        for (Method method : methods) {
            CommandHandler handler = method.getAnnotation(CommandHandler.class);
            if (handler == null) continue;

            if (cmdDef.getHandlerMethod() != null) {
                // Previously the last one discovered silently won, and discovery order is not stable.
                log.warn("Command '{}' ({}) declares more than one @CommandHandler ('{}' and '{}'). "
                                + "Keeping '{}'; the other is ignored.",
                        cmdDef.getName(), origin, cmdDef.getHandlerMethod().getName(), method.getName(),
                        cmdDef.getHandlerMethod().getName());
                continue;
            }

            defOptions = parseOptions(method, cmdDef.getName(), origin);
            Annotations.makeAccessible(method, origin);
            cmdDef.setHandlerMethod(method);
            cmdDef.setDefaultResponse(handler.defaultResponse());
        }
        return defOptions;
    }

    /**
     * Builds every subcommand, grouped and ungrouped.
     *
     * @return true when the command declared at least one subcommand
     */
    private static boolean parseSubcommands(CommandDefinition cmdDef, CommandInfo info,
                                            List<Method> methods, String origin) {
        SlashCommandData data = slashData(cmdDef);
        Map<String, SubcommandGroupDefinition> groupDefMap = parseSubcommandGroups(info, origin);

        // One SubcommandGroupData per group, filled as its members are found and added to the command
        // exactly once at the end. Building and adding one per member made a group of two subcommands
        // add the same group name twice, which JDA rejects with
        // "Cannot have multiple subcommand groups with the same name" - taking the whole registration
        // down with it.
        Map<String, SubcommandGroupData> groupDataMap = new LinkedHashMap<>();

        List<SubcommandDefinition> subcommands = new ArrayList<>();
        boolean hadSubCommands = false;

        for (Method method : sortedSubcommandMethods(methods)) {
            SubCommandHandler subAnno = method.getAnnotation(SubCommandHandler.class);
            hadSubCommands = true;

            String subName = trim(subAnno.name().toLowerCase(Locale.ROOT), MAX_NAME, "Subcommand name", origin);
            String owner = cmdDef.getName() + " " + subName;
            String subDescription = description(subAnno.description(), "Subcommand '" + owner + "'", origin);

            OptionData[] options = parseOptions(method, owner, origin);
            Annotations.makeAccessible(method, origin);

            SubcommandDefinition subDef = new SubcommandDefinition();
            subDef.setName(subName);
            subDef.setDescription(subDescription);
            subDef.setLocalizedNames(subAnno.localizedNames());
            subDef.setLocalizedDescriptions(subAnno.localizedDescriptions());
            subDef.setHandlerMethod(method);
            subDef.setDefaultResponse(subAnno.defaultResponse());
            subDef.setOptionNames(Arrays.stream(options).map(OptionData::getName).toList());

            SubcommandData subData = new SubcommandData(subName, subDescription).addOptions(options);
            if (subAnno.localizedNames().length > 0)
                subData.setNameLocalizations(LocalizationParser.getLocalizedEntries(subAnno.localizedNames()));
            if (subAnno.localizedDescriptions().length > 0)
                subData.setDescriptionLocalizations(
                        LocalizationParser.getLocalizedEntries(subAnno.localizedDescriptions()));

            Group groupAnno = method.getAnnotation(Group.class);
            if (groupAnno == null) {
                subcommands.add(subDef);
                data.addSubcommands(subData);
                continue;
            }

            String groupName = groupAnno.value().trim().toLowerCase(Locale.ROOT);
            SubcommandGroupDefinition groupDef = groupDefMap.get(groupName);
            if (groupDef == null) {
                log.warn("Command '{}' ({}) subcommand '{}' is in group '{}', which is not declared in "
                                + "@CommandInfo(groups = ...). The subcommand is skipped.",
                        cmdDef.getName(), origin, subName, groupName);
                continue;
            }

            subDef.setGroupName(groupName);
            groupDef.getSubcommands().add(subDef);
            groupDataMap.computeIfAbsent(groupName, name -> buildGroupData(groupDef)).addSubcommands(subData);
        }

        if (!groupDataMap.isEmpty()) data.addSubcommandGroups(groupDataMap.values());

        cmdDef.setSubcommands(subcommands);
        cmdDef.setGroups(groupDefMap);
        return hadSubCommands;
    }

    /**
     * The subcommand methods, in a stable order.
     *
     * <p>{@code Class#getDeclaredMethods()} makes no guarantee about ordering, so without this the
     * order Discord lists subcommands in could change between builds for no reason anybody could
     * see. Sorted by {@link SubCommandHandler#order()} and then by name.
     */
    private static List<Method> sortedSubcommandMethods(List<Method> methods) {
        return methods.stream()
                .filter(method -> method.isAnnotationPresent(SubCommandHandler.class))
                .sorted(Comparator
                        .comparingInt((Method method) -> method.getAnnotation(SubCommandHandler.class).order())
                        .thenComparing(method -> method.getAnnotation(SubCommandHandler.class).name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static SubcommandGroupData buildGroupData(SubcommandGroupDefinition groupDef) {
        SubcommandGroupData groupData = new SubcommandGroupData(groupDef.getName(), groupDef.getDescription());
        if (groupDef.getLocalizedNames() != null && groupDef.getLocalizedNames().length > 0)
            groupData.setNameLocalizations(LocalizationParser.getLocalizedEntries(groupDef.getLocalizedNames()));
        if (groupDef.getLocalizedDescriptions() != null && groupDef.getLocalizedDescriptions().length > 0)
            groupData.setDescriptionLocalizations(
                    LocalizationParser.getLocalizedEntries(groupDef.getLocalizedDescriptions()));
        return groupData;
    }

    /**
     * Parses subcommand groups from command info.
     */
    private static Map<String, SubcommandGroupDefinition> parseSubcommandGroups(CommandInfo info, String origin) {
        Map<String, SubcommandGroupDefinition> groupDefMap = new LinkedHashMap<>();
        for (SubCommandGroupInfo groupInfo : info.groups()) {
            String groupName = trim(groupInfo.name().trim().toLowerCase(Locale.ROOT), MAX_NAME,
                    "Subcommand group name", origin);

            SubcommandGroupDefinition groupDef = new SubcommandGroupDefinition();
            groupDef.setLocalizedNames(groupInfo.localizedNames());
            groupDef.setLocalizedDescriptions(groupInfo.localizedDescriptions());
            groupDef.setName(groupName);
            groupDef.setDescription(description(groupInfo.description(), "Group '" + groupName + "'", origin));
            groupDef.setSubcommands(new ArrayList<>());
            groupDefMap.put(groupName, groupDef);
        }
        return groupDefMap;
    }

    /**
     * Parses command options from method parameters.
     */
    private static OptionData[] parseOptions(Method method, String owner, String origin) {
        List<OptionData> options = new ArrayList<>();

        for (Parameter parameter : method.getParameters()) {
            CommandOption anno = parameter.getAnnotation(CommandOption.class);
            if (anno == null) continue;

            OptionData option = buildOption(anno, owner, origin);
            if (option != null) options.add(option);
        }

        return orderRequiredFirst(options, owner, origin);
    }

    /** @return the option, or null when it is unusable and has been skipped */
    private static OptionData buildOption(CommandOption anno, String owner, String origin) {
        String optionName = anno.name() == null ? "" : anno.name().trim().toLowerCase(Locale.ROOT);
        if (optionName.isEmpty()) {
            // JDA throws "Name may not be empty" from inside the OptionData constructor, which used
            // to abort the whole registration for one unfinished parameter.
            log.error("An option of '{}' ({}) has no name and has been skipped. Give every "
                    + "@CommandOption a name.", owner, origin);
            return null;
        }
        optionName = trim(optionName, MAX_NAME, "Option name", origin);

        OptionData optionData = new OptionData(
                anno.type(),
                optionName,
                description(anno.description(), "Option '" + optionName + "' of '" + owner + "'", origin),
                anno.required(),
                autoCompleteFor(anno, optionName, owner, origin));

        if (anno.localizedNames().length > 0)
            optionData.setNameLocalizations(LocalizationParser.getLocalizedEntries(anno.localizedNames()));
        if (anno.localizedDescriptions().length > 0)
            optionData.setDescriptionLocalizations(
                    LocalizationParser.getLocalizedEntries(anno.localizedDescriptions()));

        addChoices(optionData, anno, optionName, owner, origin);
        OptionConstraints.apply(optionData, anno, owner, origin);
        return optionData;
    }

    /**
     * Whether autocomplete can actually be enabled on this option.
     *
     * <p>Discord rejects autocomplete on any type that cannot carry choices, and rejects it
     * alongside a choice list — both as an {@code IllegalStateException} out of JDA, which used to
     * abort the whole command. Choices win when both are declared: they are explicit data, where
     * autocomplete is only a promise to supply some.
     */
    private static boolean autoCompleteFor(CommandOption anno, String optionName, String owner, String origin) {
        if (!anno.autoComplete()) return false;

        if (!CHOICEABLE.contains(anno.type())) {
            log.error("Option '{}' of '{}' ({}) asks for autoComplete but is of type {}. Discord only "
                            + "allows autocomplete on STRING, INTEGER and NUMBER. It has been disabled.",
                    optionName, owner, origin, anno.type());
            return false;
        }
        if (anno.choices().length > 0) {
            log.error("Option '{}' of '{}' ({}) declares both choices and autoComplete, which Discord "
                            + "does not allow. Autocomplete has been disabled and the choices kept.",
                    optionName, owner, origin);
            return false;
        }
        return true;
    }

    /**
     * Adds choices, refusing the two shapes Discord rejects outright.
     *
     * <p>Both used to throw from inside JDA and take the entire registration with them.
     */
    private static void addChoices(OptionData optionData, CommandOption anno,
                                   String optionName, String owner, String origin) {
        String[] declared = anno.choices();
        if (declared.length == 0 || optionData.isAutoComplete()) return;

        if (!CHOICEABLE.contains(anno.type())) {
            log.error("Option '{}' of '{}' ({}) declares {} choice(s) but is of type {}, which Discord "
                            + "does not accept choices on. They have been ignored.",
                    optionName, owner, origin, declared.length, anno.type());
            return;
        }

        if (declared.length > MAX_CHOICES) {
            log.warn("Option '{}' of '{}' ({}) declares {} choices; Discord allows {}. "
                            + "The extra {} have been dropped.",
                    optionName, owner, origin, declared.length, MAX_CHOICES, declared.length - MAX_CHOICES);
            declared = Arrays.copyOf(declared, MAX_CHOICES);
        }

        optionData.addChoices(parseChoices(declared, anno.type(), optionName, origin));
    }

    /**
     * Discord requires every required option before every optional one, and JDA throws if they are
     * declared the other way round - which used to stop the whole registration over a parameter
     * order that looks perfectly reasonable in Java. Reordered with a warning instead.
     */
    private static OptionData[] orderRequiredFirst(List<OptionData> options, String owner, String origin) {
        boolean seenOptional = false;
        boolean outOfOrder = false;
        for (OptionData option : options) {
            if (!option.isRequired()) seenOptional = true;
            else if (seenOptional) outOfOrder = true;
        }

        if (outOfOrder) {
            log.warn("Options of '{}' ({}) declare a required option after an optional one, which Discord "
                            + "does not allow. Required options have been moved in front; declare them first to "
                            + "control the order users see.",
                    owner, origin);
            // Stable, so the developer's relative order is kept within each group.
            options.sort(Comparator.comparing(option -> option.isRequired() ? 0 : 1));
        }
        return options.toArray(new OptionData[0]);
    }

    /**
     * Parses choice options for command arguments.
     */
    private static Command.Choice[] parseChoices(String[] choiceStrings, OptionType type,
                                                 String optionName, String origin) {
        return Arrays.stream(choiceStrings)
                .map(s -> {
                    String[] parts = s.split(":");
                    if (parts.length == 2) {
                        return typedChoice(parts[1], parts[0], type, optionName, origin);
                    }
                    if (parts.length > 2) {
                        // Falling through to value-as-label here is silent and very hard to spot:
                        // the handler receives the whole string as the value.
                        log.warn("Choice \"{}\" on option '{}' ({}) contains more than one ':'. A choice is "
                                        + "\"<value>:<label>\"; this one is registered as value and label \"{}\".",
                                s, optionName, origin, parts[0]);
                    }
                    return typedChoice(parts[0], parts[0], type, optionName, origin);
                })
                .toArray(Command.Choice[]::new);
    }

    /**
     * A choice whose value has the option's type.
     *
     * <p>A text value on an {@code INTEGER} or {@code NUMBER} option is not something JDA checks, but
     * Discord rejects it — and with it the entire {@code PUT}, so every other command in the same
     * registration went too. Refused here instead, naming the option.
     *
     * @throws IllegalArgumentException when a numeric option's value is not a number Discord accepts
     */
    private static Command.Choice typedChoice(String label, String value, OptionType type,
                                              String optionName, String origin) {
        if (type != OptionType.INTEGER && type != OptionType.NUMBER) return new Command.Choice(label, value);

        String where = "Choice \"" + value + "\" on " + type + " option '" + optionName + "' (" + origin + ")";
        double number;
        try {
            number = type == OptionType.INTEGER ? Long.parseLong(value.trim()) : Double.parseDouble(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(where + " is not a number. Numeric options need numeric "
                    + "choice values.", notANumber);
        }
        if (!Double.isFinite(number)
                || number < OptionData.MIN_NEGATIVE_NUMBER || number > OptionData.MAX_POSITIVE_NUMBER) {
            throw new IllegalArgumentException(where + " is outside Discord's range of "
                    + (long) OptionData.MIN_NEGATIVE_NUMBER + " to " + (long) OptionData.MAX_POSITIVE_NUMBER + ".");
        }
        return type == OptionType.INTEGER
                ? new Command.Choice(label, (long) number)
                : new Command.Choice(label, number);
    }

    /**
     * Trims {@code value} to Discord's limit, saying so when it had to.
     */
    private static String trim(String value, int max, String what, String origin) {
        if (value == null) return "";
        if (value.length() <= max) return value;

        String trimmed = value.substring(0, max);
        log.warn("{} \"{}\" in {} is {} characters, over Discord's limit of {}. Trimmed to \"{}\".",
                what, value, origin, value.length(), max, trimmed);
        return trimmed;
    }

    /**
     * A description Discord will accept.
     *
     * <p>A blank one makes JDA throw, and that exception used to take every other command in the bot
     * down with it. Substituted and reported instead, so the bot starts and the gap is obvious both
     * in the log and in Discord.
     */
    private static String description(String value, String what, String origin) {
        if (value == null || value.trim().isEmpty()) {
            log.error("{} in {} has no description. Discord requires one, so \"{}\" is being used. "
                    + "Set a description.", what, origin, MISSING_DESCRIPTION);
            return MISSING_DESCRIPTION;
        }
        return trim(value, MAX_DESCRIPTION, what + " description", origin);
    }
}
