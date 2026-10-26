# Command Processor

Annotation-driven slash commands for [JDA](https://github.com/discord-jda/JDA). Define a command as
a class, annotate it, and the library builds the `CommandData`, registers it, and routes the
interaction to your method with the options already bound to typed parameters.

No registry list to maintain. No `switch` on command names. No dependencies of its own — JDA is
`compileOnly`, so you keep control of the version.

---

## Install

Published to your local Maven repository with:

```bash
./gradlew publishToMavenLocal
```

**Gradle**

```groovy
repositories { mavenCentral(); mavenLocal() }

dependencies {
    implementation 'com.github.mirko0:command-processor:0.5.0'
    implementation 'net.dv8tion:JDA:6.2.0'   // yours, not the library's
}
```

**Maven**

```xml
<dependency>
    <groupId>com.github.mirko0</groupId>
    <artifactId>command-processor</artifactId>
    <version>0.5.0</version>
</dependency>
```

---

## A command

```java
@CommandInfo(name = "ping", description = "Pong!")
public class PingCommand implements QCommandBase {

    @CommandHandler(defaultResponse = Response.EPHEMERAL)
    public void execute(@CommandEvent SlashCommandInteractionEvent event) {
        event.getHook().editOriginal("Gateway: " + event.getJDA().getGatewayPing() + "ms").queue();
    }
}
```

With options, subcommands and a group:

```java
@CommandInfo(name = "logs", description = "Configure logging.",
        guildId = "1218884960992235540",
        groups = {@SubCommandGroupInfo(name = "channel", description = "Where logs go")})
public class LogsCommand implements QCommandBase {

    @Group("channel")
    @SubCommandHandler(name = "set", description = "Post logs to a channel.",
            defaultResponse = Response.EPHEMERAL, order = 1)
    public void set(@CommandEvent SlashCommandInteractionEvent event,
                    @CommandOption(type = OptionType.CHANNEL, name = "channel", required = true,
                            description = "Where to post.")
                    GuildChannelUnion channel) {
        ...
    }

    @Group("channel")
    @SubCommandHandler(name = "clear", description = "Stop posting logs.", order = 2)
    public void clear(@CommandEvent SlashCommandInteractionEvent event) { ... }
}
```

---

## Wiring

**Sharded** — one call does everything:

```java
CommandProcessor.builder()
        .commands(commands)
        .categories(List.of(
                CommandCategory.of("music", "Music").withEmoji("🎵").withOrder(1),
                CommandCategory.of("moderation", "Moderation").withEmoji("🔨").withOrder(2)))
        .messageCommands(MessageCommandConfig.withPrefix("!"))   // optional
        .build()
        .registerAll(shardManager);
```

The constructors still work for the simple cases; the builder is the way in once categories or
message commands are involved.

`categories` describes the groups `/help` sorts commands into — see [docs/HELP.md](docs/HELP.md).
`messageCommands` turns on chat-triggered commands — see
[docs/MESSAGE-COMMANDS.md](docs/MESSAGE-COMMANDS.md); leave it out and nothing is built or attached.

**Single JDA instance:**

```java
CommandProcessor processor = new CommandProcessor(commands, false);
processor.registerGlobalCommands(jda);
processor.registerGuildCommands(jda);
processor.registerListener(jda);
```

Register **after** the shards are ready — a guild whose shard has not connected cannot be resolved,
and its guild-scoped commands are skipped with a warning.

Do **not** call `registerGlobalCommands` once per shard. Global commands belong to the application,
not to a shard, so one call registers them everywhere; the `ShardManager` overload picks a shard for
you.

**Guilds from configuration.** `@CommandInfo(guildId = ...)` is a compile-time constant. To choose
guilds at runtime, give the builder a `GuildScopeResolver`; it is asked for every command as it
registers, and its answer replaces the declared guild:

```java
CommandProcessor.builder()
        .commands(commands)
        .guildScope(GuildScopeResolver.byCategory(Map.of("admin", config.staffGuildIds())))
        // or by command name:   GuildScopeResolver.byCommand(Map.of("debug", List.of(testGuild)))
        // or any lambda:        command -> config.guildsFor(command.getGuildId())
        .build();
```

Commands the resolver does not map keep what they declared; an empty list makes a command global.
The declared `guildId` reaches the resolver as written, so it can be a config key such as
`guildId = "staff"`. A command can be scoped to several guilds, and every command for a guild goes
in that guild's single registration request. An id that is not a snowflake after resolution skips
that command with an error rather than registering it globally.

With Spring, the command list is every bean implementing the marker interface:

```java
@Autowired private List<QCommandBase> commands;
```

`helpCommand(false)` — or the second constructor argument — turns off the built-in `/help`. Pass it
when you provide your own, since two commands cannot share a name.

---

## Option types

Options are bound positionally by `OptionType`. **The parameter must be declared as the type the
processor passes**, or the reflective call fails at runtime — the command then errors every time the
option is supplied and works every time it is omitted.

| `OptionType` | Parameter type |
|---|---|
| `STRING`, `SUB_COMMAND`, `SUB_COMMAND_GROUP`, `UNKNOWN` | `String` |
| `INTEGER` | `Long` |
| `NUMBER` | `Double` |
| `BOOLEAN` | `Boolean` |
| `USER` | `User` |
| `CHANNEL` | `GuildChannelUnion` |
| `ROLE` | `Role` |
| `MENTIONABLE` | `IMentionable` |
| `ATTACHMENT` | `Message.Attachment` |

**An option the user did not supply arrives as `null`**, so every option parameter must be a
reference type — a primitive throws on unboxing — and every optional one must be null-checked.

Choices are `"<value>:<label>"` and must contain exactly one colon; with more, the whole string
becomes both value and label and the library warns. On `INTEGER` and `NUMBER` options the value must
be a number (`"7:One week"`).

Ranges and lengths are enforced by Discord before the handler runs:

```java
@CommandOption(type = OptionType.INTEGER, name = "amount", description = "How many.",
        minValue = 1, maxValue = 100) Long amount,
@CommandOption(type = OptionType.STRING, name = "reason", description = "Why.",
        minLength = 3, maxLength = 512) String reason
```

`minValue` / `maxValue` apply to `INTEGER` and `NUMBER` (whole numbers on `INTEGER`, within
±(2<sup>53</sup> − 1)); `minLength` / `maxLength` apply to `STRING`, within 0–6000. Leave them out for
no limit. A limit on the wrong type, out of range, or with min above max is not dropped — the
handler relies on it — so that command fails registration with an error naming the option, and the
rest still register.

---

## Behaviour worth knowing

- **`defaultResponse`** decides whether the library acknowledges the interaction before your handler
  runs. `DEFER` and `EPHEMERAL` defer, so the handler must use `event.getHook()`. `NONE` — the
  default — means the handler acknowledges.
- **Subcommands are ordered** by `@SubCommandHandler(order = ...)` then by name, so the listing is
  stable across builds. Reflection gives no inherent order.
- **Handlers may be inherited.** Discovery walks the class hierarchy; an override replaces the method
  it overrides.
- **A command with subcommands has no base handler.** Discord never delivers a bare invocation, so
  `@CommandHandler` and its options are ignored — the library warns if both are present.
- **Guild registration replaces every command in that guild.** `updateCommands()` is a full `PUT`.
- **Over-long names and descriptions are trimmed with a warning**; a blank description is replaced
  with a placeholder and logged as an error, so one bad command does not stop the rest from
  registering. A command that fails to parse entirely is skipped, not fatal. The same applies to an
  option with no name, more than 25 choices, choices on a type that cannot have them, and a required
  option declared after an optional one.
- **`@Category` groups commands for `/help`**, and `permissions` decides who is shown a command.
  See [docs/HELP.md](docs/HELP.md).
- **Aliases are not registered with Discord** — a slash command has one name. They are used by
  `/help` lookup and display.
- **`autoComplete = true` needs an `@AutoCompleteHandler`** on the same class to answer it. Discord
  allows three seconds and no deferral. See [docs/AUTOCOMPLETE.md](docs/AUTOCOMPLETE.md).
- **Message commands are off** unless a `MessageCommandConfig` is supplied. Reading message content
  needs the privileged `MESSAGE_CONTENT` intent unless every trigger is mention-prefixed. See
  [docs/MESSAGE-COMMANDS.md](docs/MESSAGE-COMMANDS.md).

---

## Localization

```java
@CommandInfo(name = "ping", description = "Pong!",
        localizedNames = @LocalizedEntry(locale = DiscordLocale.GERMAN, value = "klingeln"),
        localizedDescriptions = @LocalizedEntry(locale = DiscordLocale.GERMAN, value = "Pong!"))
```

Available on commands, subcommands, groups and options.

---

## Documentation

| Document | What it covers |
|---|---|
| [docs/HELP.md](docs/HELP.md) | Categories, the built-in help command, permission filtering, aliases |
| [docs/AUTOCOMPLETE.md](docs/AUTOCOMPLETE.md) | Supplying suggestions for an option as the user types |
| [docs/MESSAGE-COMMANDS.md](docs/MESSAGE-COMMANDS.md) | The optional prefix / prefixless message-command feature |
| [docs/CODE-STYLE.md](docs/CODE-STYLE.md) | Conventions for this repository: API surface, no Lombok, deprecation policy, javadoc, size budgets |
| [docs/AUDIT.md](docs/AUDIT.md) | Known defects and structural observations, with severity and reproduction |

---

## Requirements

Java 17+, JDA 6.x. slf4j-api arrives through JDA and is the only thing used beyond the JDK.

---

## Upgrading

**0.5.0** adds `minValue` / `maxValue` / `minLength` / `maxLength` on `@CommandOption`, numeric
choice values on `INTEGER` / `NUMBER` options, and `Builder.guildScope(GuildScopeResolver)` with
`CommandDefinition.getGuildIds()`. No breaking changes. Two commands that could never register now
fail loudly at startup instead: numeric options with text choices (Discord rejected the whole
request) and a non-numeric or blank `guildId` (the guild lookup threw).

**0.3.0** adds message commands and `CommandProcessor.builder()`. No breaking changes; the feature
is inert unless a `MessageCommandConfig` is passed.

**0.2.0** adds autocomplete dispatch — see [docs/AUTOCOMPLETE.md](docs/AUTOCOMPLETE.md). No breaking
changes; an option already declaring `autoComplete = true` now warns at registration if nothing
answers it, where before it silently showed an empty list.

### To 0.1.0

Breaking, all mechanical:

- `…commandprocessor.utils` is now `…commandprocessor.internal` and is **not** public API.
- `…commandprocessor.integrated.HelpCommand` is now `…commandprocessor.help.HelpCommand`.
- `CommandProcessor` is `final`; its constructors take `Collection<? extends QCommandBase>`.
- `CommandDefinition.getSubcommands()` and `getGroups()` return unmodifiable collections.

Deprecated but still working, removal planned for 1.0.0: `CommandInfo.contextType` (use
`contexts`), `CommandInfo.integrationType` (use `integrationTypes`), `CommandInfo.moduleId` (use
`@Category`), `CommandDefinition.setNSFW` (use `setNsfw`), `CommandDefinition.getModuleId` /
`setModuleId` (use `getCategoryId` / `setCategoryId`).

[docs/AUDIT.md](docs/AUDIT.md) lists everything that changed and why.
