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
  - Answering a dose: the Instance id derivation (ADR 0018). Supply spending is not
    ported: the server spends it (ADR 0018), so its second copy is TypeScript, and
    `reminderInstances.test.ts` holds it to `supplyAfterDose`'s cases. `refill` is ported
    only for the projection the screens show.
- **Sign-in** is a browser handoff to the Clerk OAuth application "TaskRx Desktop": public
  (no secret), PKCE S256 required, no consent screen, redirect `http://127.0.0.1/callback`.
  Clerk matches a loopback redirect on any port, so the desktop listens on a port the OS
  picks. Scopes are `openid profile email offline_access`.
- **Convex is sent the OIDC ID token**, not the access token. The ID token has the phone's
  issuer and `sub` (the Clerk user id `getCurrentUserId` returns), and `aud` is the OAuth
  client id, which the second `auth.config.ts` entry (`CLERK_DESKTOP_CLIENT_ID`) checks. The
  access token (`typ: at+jwt`) has no `aud` and is not accepted. Both live 24 hours. A
  refresh-token grant returns a new ID token whose `exp` moves forward, and a new refresh
  token (the old one is rotated out), so the keyring holds only the latest refresh token and
  replaces it on every refresh.
- **The Rust client does not report a refused token.** With a token the server refuses,
  `set_auth` returns at once and a query has no answer after 30 seconds, with no error. So
  the desktop decodes `exp` itself and refreshes before it, and treats a query that has no
  answer within a timeout after sign-in as signed out.
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

- Settled 2026-11-17: the Convex Rust client (0.10.4) runs a query and a subscription
  against the self-hosted deployment with the Clerk ID token and reads the phone's rows.
- Settled 2026-11-17, Rune `dev` at `cca5df6` on Hyprland (Wayland), typed by a person:
  a window opens, typing works, Ctrl+V pastes text another program put on the Wayland
  clipboard, Ctrl+C puts the field's text there, and a rich copy offers `text/plain` and
  `text/html` (no RTF on Linux). IME composition is untested: fcitx5 here has only the US
  layout, and no composing input is a v1 need. Accessibility (AT-SPI) is still unchecked.
- Synthetic keys (`wtype`) go to whichever window Hyprland has focused, which is not always the
  one just asked for. Never send them to the live desktop unwatched. Rune's headless scripts
  are the way to drive it in tests.
