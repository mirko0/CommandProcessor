# Audit — Command Processor 0.0.4

Full read of every source file, plus a probe harness that runs the library against JDA 6.2.0 to
confirm the sharper claims rather than assert them. Findings that were reproduced are marked
**verified** and carry the output.

> **Status: all findings below were fixed in 0.1.0**, except where a row says otherwise. The
> document is kept as the record of what was wrong and why, because every one of these is now a test
> in `src/test/java` and the reasoning is what stops them coming back.

The four defects fixed in 0.0.4 — the `@Group` duplication, the deferred-handler error path,
unbounded text lengths, and `getDeclaredMethods()` — are not repeated here.

---

## Summary

| # | Severity | Finding |
|---|---|---|
| [A1](#a1) | ~~Critical~~ fixed 0.1.0 | The built-in `HelpCommand` cannot be registered and does nothing if it were |
| [A2](#a2) | ~~High~~ fixed 0.1.0 | The constructor mutates the caller's list — `List.of(...)` throws |
| [A3](#a3) | ~~High~~ fixed 0.1.0 | Option lookup uses the raw name; registration lowercases it |
| [A4](#a4) | ~~High~~ fixed 0.1.0 | Spring-proxied command beans are silently skipped |
| [A5](#a5) | ~~High~~ fixed 0.1.0 | `commandMap` is a `HashMap` read from event threads while `register` is public |
| [A6](#a6) | ~~Medium~~ fixed 0.1.0 | An unknown subcommand produces no response at all |
| [A7](#a7) | ~~Medium~~ fixed 0.1.0 | `InvocationTargetException` is logged instead of its cause |
| [A8](#a8) | ~~Medium~~ fixed 0.1.0 | `getCommandMap()` hands out mutable internal state |
| [A9](#a9) | ~~Medium~~ fixed 0.1.0 | `contextType` / `integrationType` are single-valued |
| [A10](#a10) | ~~Medium~~ fixed 0.1.0 | `guild.updateCommands()` replaces every command in that guild |
| [A11](#a11) | ~~Medium~~ fixed 0.1.0 | `setAccessible(true)` runs on every invocation |
| [A12](#a12) | ~~Low~~ fixed 0.1.0 | Dead state: `usingHelpCommand`, `SubcommandDefinition.groupName` |
| [A13](#a13) | ~~Low~~ fixed 0.1.0 | `setNSFW` / `isNsfw` breaks the JavaBean accessor pair |
| [A14](#a14) | ~~Low~~ fixed 0.1.0 | `example/` ships inside the published jar |
| [A15](#a15) | ~~Low~~ fixed 0.1.0 | No toolchain pin, no sources jar |
| [A16](#a16) | ~~Low~~ fixed 0.0.4 | README documents an API that does not exist |
| [A17](#a17) | ~~Low~~ fixed 0.1.0 | No tests |
| [A18](#a18) | ~~Low~~ fixed 0.1.0 | Choice count is not validated against Discord's limit of 25 |
| [A19](#a19) | ~~Low~~ fixed 0.1.0 | No `package-info.java`; annotations are not `@Documented` |

---

## Critical

### A1
**The built-in `HelpCommand` cannot be registered, and would do nothing if it could.** *Verified.*

`HelpCommand` declares an option with an empty name:

```java
@CommandOption(type = OptionType.STRING, name = "", description = "Command to show help for", ...)
String module
```

JDA rejects it while the command data is being built:

```
helpCommand: IllegalArgumentException: Name may not be empty
```

`CommandProcessor(List<QCommandBase>)` — the one-argument constructor — passes `helpCommand = true`,
so **every consumer using the default constructor hits this**. Before 0.0.4 it propagated out of the
constructor and stopped the application from starting. 0.0.4 catches per-command parse failures, so
it now logs an error and skips instead. That is better behaviour for an unrelated bad command and
worse here, because it turns a loud failure into a `/help` that silently does not exist.

The method body is also empty. With `defaultResponse = Response.EPHEMERAL` it defers and never
edits, so a user who ran it would watch "thinking…" until the interaction expired.

**Fix.** Either implement it — the data is right there in `commandMap`, grouped by
`CommandInfo.moduleId()`, which is what `moduleId` exists for — or delete it and default
`helpCommand` to `false`. Do not leave a third state where it is advertised and skipped. Whichever
way it goes, the empty option name must not survive: it is also the argument for validating option
names at parse time the way lengths are now validated.

---

## High

### A2
**The constructor mutates the list it is given.** *Verified.*

```java
if (helpCommand) commands.add(new HelpCommand(this));
```

```
immutable list: UnsupportedOperationException
```

`new CommandProcessor(List.of(cmd))` throws. Worse in a Spring application: `List<QCommandBase>` is
injected by the container, and adding to it modifies a collection the container owns.

**Fix.** Copy first — `List<QCommandBase> all = new ArrayList<>(commands);` — and never write to a
parameter. A library takes what it is given and does not change it.

### A3
**An option whose name contains an uppercase letter always resolves to `null`.**

`CommandParser` registers the option lowercased:

```java
new OptionData(anno.type(), anno.name().toLowerCase(), ...)
```

`SlashCommandHandler` looks it up raw:

```java
OptionMapping optionMapping = event.getOption(opt.name());
```

So `@CommandOption(name = "User")` is registered with Discord as `user`, the incoming interaction
carries `user`, and the lookup asks for `User` and gets nothing. The parameter is silently `null`
every single time — which reads exactly like an optional argument the user did not supply.

**Fix.** Lowercase at the lookup as well, or better, resolve the name once at parse time and store
it on the definition so the two can never disagree again. The second is the real fix: the same class
of bug is available anywhere the parser normalises something the dispatcher re-derives.

### A4
**A command bean whose class has been subclassed is silently skipped.** *Verified.*

```java
CommandInfo info = clazz.getAnnotation(CommandInfo.class);
if (info == null) return null;
```

`@CommandInfo` is not `@Inherited`, and `getAnnotation` on a subclass therefore returns `null`:

```
proxied subclass parses to: null
```

This matters because Spring hands you a subclass whenever a bean is CGLIB-proxied — `@Transactional`
on the command, an aspect that matches it, `@Async`, `@Cacheable`, some `@Configuration` shapes. The
command then registers nothing, `register()` skips it without a word, and there is no error anywhere
to explain why the slash command is missing.

**Fix, two parts.** Mark `@CommandInfo` (and `@SubCommandGroupInfo`) `@Inherited`, and walk up the
superclass chain when looking it up. Both, because `@Inherited` alone does not help an interface and
the walk alone does not help `getAnnotationsByType`. The handler-method discovery added in 0.0.4
already walks the hierarchy, so today the methods would be found on a proxy and the class annotation
would not — the two halves disagree.

### A5
**`commandMap` is a `HashMap` shared with the dispatcher, and `register` is public.**

`SlashCommandHandler` holds a reference to the same map and reads it from JDA event threads.
`register(List)` is public and can be called at any time. A `HashMap` resized during a concurrent
`get` can return `null` for a key that is present, or spin.

Even without a late `register`, the map is populated in the constructor and published to the handler
inside that same constructor — `this` escapes before construction finishes, so there is no
happens-before edge guaranteeing the dispatcher sees a fully built map.

**Fix.** `ConcurrentHashMap`, and build the map before constructing the handler rather than handing
the handler a map that is still being filled.

---

## Medium

### A6
**An unknown subcommand produces no response at all.**

```java
if (subDef != null) { ... }   // and no else
```

Discord shows "The application did not respond" after three seconds. It is reachable in practice:
Discord caches command definitions client-side, so a subcommand removed in a deployment is still
clickable for users until their client refreshes.

**Fix.** Log it, and reply with something. The same applies to `handleGroupSubcommand` when the group
is unknown.

### A7
**The real exception is not what gets logged.**

`method.invoke` throws `InvocationTargetException` wrapping whatever the handler threw.
`log.error("Command execution failed", e)` logs the wrapper — the cause is in the stack trace, but
the message line, which is what monitoring alerts on, says nothing useful.

**Fix.** Unwrap: `Throwable cause = e instanceof InvocationTargetException ite ? ite.getCause() : e;`
and log that, with the command name and the user id for context.

### A8
**`getCommandMap()` returns the live map.** A caller can clear it, and `HelpCommand` — the one
consumer — only needs to read. Return `Collections.unmodifiableMap(...)`.

### A9
**`contextType()` and `integrationType()` hold one value each.**

JDA's `setContexts` and `setIntegrationTypes` are varargs because a command is routinely valid in
more than one context. There is no way to express "guild, bot DM and private channel", which is what
the predecessor bot's commands used. `InteractionContextType.UNKNOWN` is being used as "unset",
which also means `UNKNOWN` cannot be passed deliberately.

**Fix.** `InteractionContextType[] contexts() default {};` and
`IntegrationType[] integrationTypes() default {};`, empty meaning "leave JDA's default". Keep the
old members, `@Deprecated(forRemoval = true)`, honoured when the new ones are empty.

### A10
**Guild registration replaces every command in that guild.**

`guild.updateCommands()` is a full `PUT`. Any guild-scoped command registered by anything else —
another `CommandProcessor`, a second bot feature, a manual registration — is deleted. This is
correct JDA behaviour and correct for a bot that owns its whole command set, but it is not
documented, and the first time someone finds out is when their commands vanish.

**Fix.** Say so in the javadoc of both guild methods.

### A11
**`method.setAccessible(true)` runs on every single invocation.** It is a security-manager and module
check each time, and it can throw `InaccessibleObjectException` at the worst possible moment —
during a user's command — rather than at startup.

**Fix.** Call it once in `CommandParser` when the definition is built, so a class the library cannot
reflect into fails loudly at registration.

---

## Low

### A12
**Dead state.** `CommandProcessor.usingHelpCommand` is assigned and never read.
`SubcommandDefinition.groupName` is never set by the parser, so the natural way to ask which group a
subcommand belongs to silently answers `null`. Either populate it or delete it.

### A13
**`setNSFW(boolean)` / `isNsfw()`** is not a JavaBean pair. Anything reflective — a serialiser, a
mapper, an IDE refactor — treats them as unrelated. Rename to `setNsfw`, keeping `setNSFW` as a
`@Deprecated` delegate.

### A14
**`example/PingCommand` is compiled into the published jar.** It appears in consumers'
auto-complete, it is dead weight, and it is a `QCommandBase` that a component scan could
conceivably pick up. Move it to `src/test/java`, or to a separate `samples` source set that is not
published.

### A15
**No toolchain pin and no sources jar.** The build compiles with whatever JDK runs Gradle, so the
class-file version of a published artifact depends on the machine that built it. There is a javadoc
jar but no sources jar, so consumers cannot step into the library in a debugger.

**Fix.** `java { toolchain { languageVersion = JavaLanguageVersion.of(17) } withSourcesJar() }` —
and pick the language level deliberately, because it is the floor for every consumer.

### A16
**The README documents an API that does not exist.** It names the artifact
`quartermaster-commands` at version `0.0.2` — the published artifact is `command-processor` — and
shows `new CommandProcessor(jda, commands)`, a constructor with no counterpart in the source. The
usage example also predates `registerAll`.

### A17
**No tests.** For a library whose entire job is reflection over annotations, the absence is
structural: every defect in this document and every one fixed in 0.0.4 is a test that would have
failed. The probe harness used for this audit is throwaway; a real suite belongs in
`src/test/java`, needs no Discord token, and would cover parsing, trimming, ordering, grouping and
dispatch routing.

### A18
**Choice count is not validated.** Discord allows 25 per option; JDA throws on the 26th. Same class
as the length limits fixed in 0.0.4 — warn and truncate, or fail with a message naming the option.

### A19
**No `package-info.java` anywhere, and no annotation is `@Documented`.** For a library, both are
part of the deliverable: the package javadoc is where "what is API and what is internal" gets
written down, and an annotation that is not `@Documented` does not appear on the elements it
annotates in generated javadoc.

---

## Structural observations

These are not defects; they are the things that will make the next feature awkward.

**There is no shared invocation path.** ~~`SlashCommandHandler.invoke` binds parameters by annotation
and calls the method. The message-command feature needs exactly the same thing with a different
event type. Extract a `ParameterBinder` before writing the second copy, not after.~~
**Done in 0.2.0**: `internal/ParameterBinder` is shared by the command and autocomplete dispatchers,
and is what the message-command dispatcher will bind through — see
[MESSAGE-COMMANDS.md](MESSAGE-COMMANDS.md) §6.

**`CommandParser` is the only place that knows what an annotation means, and it is 300 lines.** That
is acceptable while there is one command kind. With message commands it becomes two, and the file
should split along that line: a `SlashCommandParser` and a `MessageCommandParser` over a shared
`AnnotationSupport`.

**Nothing distinguishes API from internals.** `utils` and `definitions` are as public as
`CommandProcessor`, so every field of `CommandDefinition` is effectively part of the contract and
cannot change without a breaking release. Establish the boundary now, while there is one consumer —
see [CODE-STYLE.md](CODE-STYLE.md) §2.

**The definitions are mutable bean bags built by a parser that fills them field by field.** A
half-built definition is representable, and one escaped that way before 0.0.4 (a subcommand skipped
for an unknown group left `hadSubCommands` true). They want to be immutable, built through a
constructor or a builder, so "valid" is the only state that exists.


---

## What 0.1.0 changed

Every finding above is fixed. The ones worth knowing about as a consumer:

| Was | Now |
|---|---|
| `/help` unregisterable and empty | A real Components V2 help command — categories, select menus, paging, permission filtering. See [HELP.md](HELP.md) |
| `moduleId` — a bare string with no meaning | `@Category("music")` plus `CommandCategory` metadata, registered in one place |
| The constructor mutated the caller's list | It copies. `List.of(...)` and Spring-injected lists both work |
| Option names looked up raw, registered lowercased | Both lowercased. An option named `User` resolves again |
| Proxied command beans silently skipped | Annotation lookup walks the hierarchy; `@CommandInfo` and `@Category` are `@Inherited` |
| `HashMap` shared with the dispatcher | `ConcurrentHashMap`, built before the dispatcher exists |
| Unknown subcommand fell silent | Logged, and the user gets a reply |
| `InvocationTargetException` logged | The cause is logged, with command, user and guild |
| `getCommandMap()` returned live state | Unmodifiable view |
| One context and one integration type | `contexts()` / `integrationTypes()` arrays; the singular members deprecated |
| `setAccessible` on every invocation | Once, at registration, where a failure is visible |
| `setNSFW` / `isNsfw` | `setNsfw`; the old name deprecated and delegating |
| `example/` in the published jar | Moved to `src/test/java`, and exercised by the suite |
| No toolchain pin, no sources jar | `release = 17`, `withSourcesJar()` |
| No tests | 52, no token and no network |

**Breaking changes**, hence 0.1.0 rather than 0.0.5:

- `com.github.mirko0.commandprocessor.utils` is now `…​.internal`, and is not API.
- `com.github.mirko0.commandprocessor.integrated.HelpCommand` is now `…​.help.HelpCommand`.
- `CommandProcessor` is `final`, and its constructors take `Collection<? extends QCommandBase>`
  rather than `List<QCommandBase>`.
- `CommandDefinition.getSubcommands()` / `getGroups()` return unmodifiable collections.

Deprecated, still working, removal planned for 1.0.0: `CommandInfo.contextType`,
`CommandInfo.integrationType`, `CommandInfo.moduleId`, `CommandDefinition.setNSFW`,
`CommandDefinition.getModuleId` / `setModuleId`.


---

## Found after the audit

**A20 — `autoComplete` was registered and never dispatched.** *Fixed in 0.2.0.* `@CommandOption` set
the flag on the option, so Discord asked the bot for suggestions, and nothing in the library handled
`CommandAutoCompleteInteractionEvent`. The user saw a dropdown that spun and gave up. Three further
shapes JDA throws on were live in the parser at the same time — autocomplete on a type that cannot
carry choices, autocomplete alongside `choices`, and the missing option-name check — each of which
aborted the command being parsed. See [AUTOCOMPLETE.md](AUTOCOMPLETE.md) §6.

**A21 — Declared choices on numeric options were sent as text.** *Fixed, unreleased.*
`choices` was accepted on `INTEGER` and `NUMBER` options, but every choice was built as a string
`Command.Choice`. JDA does not check the value type; Discord does, and rejects the whole `PUT`, so
one such option took every other command in the same registration with it. Choice values are now
parsed to the option's type, and one that is not a number fails that command at parse time.
