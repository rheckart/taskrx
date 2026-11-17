# The desktop is Rust on Rune, and Kotlin `:core` stays the reference

The desktop Companion (ADR 0016) is written in Rust, drawn with Rune, and lives at
`apps/desktop` as a Cargo workspace in this repository. Rust is chosen for Convex's official
Rust client, which gives live subscriptions. The cost is that the rules the desktop needs
exist in a second language, so `:core` is declared the reference and the Rust port must
reproduce it.

## The shape

- **`taskrx-desktop-core`** (toolkit-free): the ported rules, the Convex connection and the
  screen state. Everything that can be tested is here.
- **The Rune crate** only draws that state and forwards input. It holds no rule.
- **Golden fixtures.** A `:core` test task writes input→output cases as JSON, run at a fixed
  TZ with the DST cases included. `cargo test` must reproduce every one exactly. A rule
  changed in Kotlin without the Rust following fails a gate. Each v1 screen brings its
  packages:
  - Today: `schedule` (`nextOccurrences` with pauses, overrides, monthly clamping),
    `timeline`, `tasks` (`sectionTasks`), `domain` (`InstanceLifecycle`,
    `ReminderLifecycle`), `batch`, `asneeded`, `snooze`, `undo`, `sweep` (display only).
  - Add and edit: `parser`, plus `domain` for pause and stop.
  - Backlog and Catch up: `backlog`, `catchup`.
  - Proposals: `notices`. The date reader is meant to have one implementation, so this is
    a second one, held to the first only by these fixtures.
  - Answering a dose: `refill` (supply spending) and the Instance id derivation (ADR 0018).
- **Sign-in** is a browser handoff. The intended route is a Clerk OAuth application marked
  Public, which uses PKCE (Clerk's documented route for native apps), with a loopback
  redirect. Clerk issues its access tokens as JWTs. They will probably not carry the phone's
  `aud: convex`, so `auth.config.ts` likely gains a second entry for the desktop's client id.
  The identity's subject must still be the Clerk user id `getCurrentUserId` compares against.
- **Packaging:** a PKGBUILD and a `.desktop` entry install it into the app launcher. The
  app follows the Omarchy accent from `~/.local/state/omarchy/current/theme/colors.toml`
  (watched live) and asks `hyprctl` for the rounding, as Omarchy's own apps do.
- **Gates:** `cd apps/desktop && cargo test` becomes a third verification gate in
  `CLAUDE.md` once the crate exists, and the fixture-writing task joins the Gradle gate.

## Considered Options

**Compose Desktop on `:core` was rejected** in favour of a native app that sits with
Omarchy's own. It would have reused every rule unchanged, and that is the main thing given up.

**C++/Qt, as Omarchy's apps are built, was rejected for Rust.** Both port the rules. Rust
also has an official Convex client, while C++ would have polled the HTTP API.

**Qt Quick through cxx-qt, Slint, GTK4 and iced** were the UI alternatives. Rune was chosen
knowing it is **pre-release** (its README: "Pin a rev of `dev`"; it takes no pull requests,
only issues) and draws its own text. Its README describes text input, clipboard and
accessibility only for the web target, where a DOM mirror carries them. Native input methods,
the Wayland clipboard and AT-SPI are not stated and have to be proven. Keeping it to a drawing layer
over a toolkit-free crate means only that layer is lost if Rune stalls.

**Kotlin/Native `:core` linked as a C library** would keep one implementation. It needs
`java.time` (about 20 files) migrated to kotlinx-datetime and `:core` made a Multiplatform
module. Not chosen for v1, but it remains the way to retire the port if drift becomes costly.

## Open questions

- Whether a Clerk OAuth access token passes Convex's verification with a second
  `auth.config.ts` entry (issuer, audience, subject). Prove it with one query before
  building on it.
- Whether the Convex Rust client works against the self-hosted backend's version.
- Rune on Hyprland: text input, IME, clipboard and accessibility. Check these on the first screen.
