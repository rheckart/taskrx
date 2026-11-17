# Desktop Companion: TaskRx on the Omarchy desktop

Status: in progress. Written 2026-11-16 from a planning conversation. Decisions: ADR 0016
(the desktop is a Companion), ADR 0017 (Rust on Rune, `:core` is the reference), ADR 0018
(an Instance's id is derived from its Occurrence).

The desktop shows and answers. The phone delivers. The phone and the server change first,
because two writers are unsafe until one Occurrence can only ever be one Instance.

## Steps

- [ ] Verify the ground. Each check can change ADR 0017, so nothing desktop-side is built
      before all three pass:
  - [ ] The Convex Rust client runs one query against the self-hosted deployment
  - [ ] A Clerk Public OAuth app (PKCE) issues a token that Convex accepts with a second
        `auth.config.ts` entry, and whose subject is the same Clerk user id
  - [ ] Rune opens a window on Hyprland, takes typed text (and IME), and reads and writes the
        Wayland clipboard
- [ ] One Instance per Occurrence (ADR 0018):
  - [ ] `instanceIdFor(reminderLocalId, scheduledTime)` in `:core`: UUID v5 (SHA-1) under a
        fixed namespace, gated by Kover. `java.util.UUID.nameUUIDFromBytes` is v3 (MD5), so
        it is not used
  - [ ] Every mint on the phone uses it: the answer screen, batch answers, + Dose, the
        sweep and Backlog
  - [ ] The answer screen loads the Instance already at the moment, whatever its status,
        instead of minting a second pending one (issue #21)
  - [ ] Server merge: a person's answer is not overwritten by a later sweep verdict
  - [ ] Supply is spent by the server inside the completing mutation, not by a Reminder
        update that the last writer wins
- [ ] Golden fixtures: a `:core` task writes JSON cases for every rule the desktop ports
      (list in ADR 0017). The files are checked in, and the Gradle gate fails when
      regenerated output differs from them.
- [ ] Phone catch-up (ADR 0016):
  - [ ] A periodic WorkManager pull (15 min) that reschedules
  - [ ] A fire-time check before a dose posts: short timeout, fails open. Unverified until
        the new harness cases run on a device (offline posts, answered elsewhere stays
        silent, a slow server posts)
- [ ] Desktop, `apps/desktop`:
  - [ ] Cargo workspace: `taskrx-desktop-core` (rules, Convex, screen state) and the Rune crate
  - [ ] Rules ported to Rust against the fixtures
  - [ ] Sign-in through the browser, session token in the keyring
  - [ ] Today, Tasks, add and edit, Backlog, Catch up, Proposals
  - [ ] Omarchy theme (accent from `colors.toml`, watched; `hyprctl` rounding)
  - [ ] PKGBUILD and `.desktop` entry. `cargo test` becomes a gate in `CLAUDE.md`
- [ ] Docs: `architecture.md` moves the desktop out of Deferred, and the ADRs' open
      questions are closed

## Scope check

A Companion helps a commitment happen on time (answering where the person is sitting) and
records honestly what happened (the same Instances). It adjudicates nothing and measures
nothing new.
