# Categories and the built-in help command

`/help` lists every command the caller can actually use, grouped into categories, navigated with
select menus and buttons. Added automatically unless you turn it off.

---

## 1. Categories

A command declares which category it is in; the category itself is described once, centrally.

```java
@Category("music")
@CommandInfo(name = "play", description = "Play a track.")
public class PlayCommand implements QCommandBase { ... }
```

```java
new CommandProcessor(commands, true, List.of(
        CommandCategory.of("music", "Music")
                .withDescription("Play and control audio")
                .withEmoji("🎵")
                .withOrder(1),
        CommandCategory.of("moderation", "Moderation")
                .withEmoji("🔨")
                .withOrder(2)))
    .registerAll(shardManager);
```

`CommandCategory` is immutable; the `with*` methods return copies, so a category can be built as a
constant and reused.

| Member | Effect |
|---|---|
| `id` | What `@Category` refers to. Trimmed and lowercased, so `@Category("Music")` and `"music"` are the same category |
| `displayName` | The heading in the help menu |
| `description` | A line under the heading, and the select-menu option's description |
| `emoji` | Shown beside the name, in text and on the select option |
| `order` | Lower sorts first; ties break on display name, so the listing is always stable |

**An unregistered id still works.** A command referring to `"utility"` when nothing registered it
forms a category anyway, deriving `"Voice Moderation"` from `voice-moderation`, and a warning names
the command so you can go and register it properly. Never a silently missing command.

A command with no `@Category` falls into `general` (`CommandCategory.DEFAULT_ID`).

Where the categories themselves live is your choice — a constants class, an enum with a
`CommandCategory` on each constant, a Spring `@Bean` returning the list. The library only needs the
collection at construction time.

---

## 2. What a user sees

Three screens, all in one ephemeral message that gets edited in place.

**Root** — every category the caller can see, with a command count and description, plus a select
menu to open one. Paged past 25 categories.

**Category** — the commands in it, each with its aliases and description, plus a select menu to open
one and a **← All categories** button. Paged past 20 commands.

**Command** — full detail: description, aliases, required permissions, every subcommand with its
options spelled out as a usage line, and a note if it is guild-scoped. Buttons back to the category
and to the root.

`/help command:<name>` opens the command screen directly, matching on the command name **or** an
alias. An unknown name lands on the root rather than erroring — it is a shortcut, and a typo should
still show the help that was asked for.

---

## 3. Permission filtering

Two filters decide what a person sees.

**Where.** A command scoped to guilds — by `guildId` or a `GuildScopeResolver` — only appears in
those guilds. Listing it elsewhere advertises something that cannot be run.

**Who.** A command declaring `permissions` is listed only for a member holding *all* of them,
checked against the channel the help was opened in so a channel override is respected. A category
appears only when at least one of its commands survives both filters — so a moderation category
simply does not exist for a regular member.

In a DM there is no member and no permission model, so a command gated on a guild permission is not
offered there.

```java
@Category("moderation")
@CommandInfo(name = "ban", description = "Ban a member.",
        permissions = Permission.BAN_MEMBERS,
        aliases = "yeet")
public class BanCommand implements QCommandBase { ... }
```

> **This is a courtesy, not a security boundary.** Discord enforces default member permissions on
> the command itself; hiding it here only stops the menu advertising something the user cannot use.
> A command that must not run without a permission still checks it in its own handler.

---

## 4. Aliases

```java
@CommandInfo(name = "ping", description = "Pong!", aliases = {"latency", "pong"})
```

Discord has no alias concept — a slash command has exactly one name — so these are **not registered
with Discord**. They are used by `/help`'s lookup and shown on the command screen, and they are what
the planned message-command dispatcher will match on. Aliases are lowercased, de-duplicated, and the
command's own name is dropped if it appears among them.

---

## 5. Turning it off

```java
new CommandProcessor(commands, false);
```

Two commands cannot share a name, so pass `false` if you want your own `/help`. With `false` the
help listener is not registered either — nothing is attached and nothing is paid for.

You can still reuse the pieces: `HelpModel.viewFor(interaction)` answers "what may this person see"
and `HelpRenderer.render(view, id)` builds the same screens, both from your own command.

---

## 6. How it is built

- **Stateless.** Every screen is reconstructed from the component's custom id — `cph:<view>:<arg>:<page>`
  — and the selected value. Nothing is held per user, a help message left open for a week still
  works, and a restart does not strand it.
- **Namespaced.** Ids start with `cph:`; the listener ignores everything else, so it never collides
  with your own component router. Make sure your router ignores `cph:` in return.
- **Components V2** throughout: `Container`, `TextDisplay`, `Separator`, `ActionRow`. Ephemeral, so
  the buttons edit the caller's own message.
- **`HelpRenderer` is a pure function** of a view and an id — no JDA instance, no network, no state.
  That is what lets it be tested directly, and `HelpRendererTest` asserts the things Discord rejects
  a whole message for: duplicate custom ids, over-long select-option descriptions, over-long custom
  ids.
- **Every screen degrades.** A category or command that is no longer visible falls back to the root
  rather than rendering a blank panel; a page number past the end is clamped.
