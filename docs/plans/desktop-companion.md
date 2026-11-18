# Desktop Companion: TaskRx on the Omarchy desktop

Status: in progress. Written 2026-11-16 from a planning conversation. Decisions: ADR 0016
(the desktop is a Companion), ADR 0017 (Rust on Rune, `:core` is the reference), ADR 0018
(an Instance's id is derived from its Occurrence).

The desktop shows and answers. The phone delivers. The phone and the server change first,
because two writers are unsafe until one Occurrence can only ever be one Instance.

## Steps

- [x] Verify the ground. Each check can change ADR 0017, so nothing desktop-side is built
      before all three pass:
  - [x] The Convex Rust client runs one query against the self-hosted deployment (`convex`
        0.10.4: a query and a subscription reach `reminders:list` and return the server's
        "Not authenticated". Real data waits on the Clerk token)
  - [x] A Clerk Public OAuth app (PKCE) issues a token that Convex accepts with a second
        `auth.config.ts` entry, and whose subject is the same Clerk user id (the ID token,
        client id `pZBo7hPNxnn4yT04`; from Rust it reads the phone's 27 reminders)
  - [x] Rune opens a window on Hyprland, takes typed text, and reads and writes the
        Wayland clipboard (`cca5df6`, typed by hand. IME untested and not a v1 need)
- [ ] One Instance per Occurrence (ADR 0018):
  - [x] `instanceIdFor(reminderLocalId, scheduledTime)` in `:core`: UUID v5 (SHA-1) under a
        fixed namespace, gated by Kover. `java.util.UUID.nameUUIDFromBytes` is v3 (MD5), so
        it is not used
  - [x] Every mint on the phone uses it: the answer screen, batch answers, + Dose, the
        sweep and Backlog
  - [x] The answer screen loads the Instance already at the moment, whatever its status,
        instead of minting a second pending one (issue #21)
  - [x] Server merge: a person's answer is not overwritten by a later sweep verdict, and
        the phone's conflict rule takes a remote Skip or Snooze over its own Missed so
        the two converge
  - [x] Supply is spent by the server inside the completing mutation, not by a Reminder
        update that the last writer wins. Deploy the server before the phone build
  - [x] Deploy `packages/convex` to the VPS (2026-11-17: the server code of `60ba5d4` on
        `main`, which includes `mailbox-forwards`, so the forwarded-notice poll stayed;
        same 34 functions)
  - [x] Release the phone build (v0.11.0, 2026-11-17; installed over 0.10.0 by ADB first:
        signs in, syncs, alarms set. A dose answered on it is still to be watched)
- [x] Golden fixtures: a `:core` task writes JSON cases for every rule the desktop ports
      (list in ADR 0017). The files are checked in, and the Gradle gate fails when
      regenerated output differs from them.
  - [x] The mechanism, inside `:core:test` (fails on a changed, missing or orphaned file)
  - [x] `instance-id` (Rust reproduces all 8 with `Uuid::new_v5`), `schedule` (92 cases,
        four zones), `server-schedule` (the writer moved into `:core` as `scheduleToServer`)
  - [x] `domain` lifecycles, pause and stop (137 cases; the transition message is not part of
        the contract)
  - [x] `tasks`, `timeline`, `batch`, `asneeded`, `snooze`, `undo`, `sweep` (`today`, 58
        cases over one week of data; `answer`, 46), and `time` (`local-day`, 47)
  - [x] Move the Tasks tab's assembly out of `:app`'s `TodayViewModel` into `:core`, with
        fixtures: the stored task rows older than the fortnight (a run-out snooze read as
        missed), the one invented row at a task's latest moment, and which rows offer Undo
        (`assembleTasksTab`, `taskWindow`, `undoableInstanceIds`; in `today`, 5 cases)
  - [x] Move the Convex document decoders (`ServerDocs.toPulled`) and the write call shapes
        (`Push.kt`'s callers in `SyncPayloads`) into `:core` with fixtures, including
        resolving an instance's Convex `reminderId` to the parent's `localId`
        (`ServerDocs.parentLocalIds`; `server-docs` 19 cases, `push` 21)
  - [x] `backlog`, `catchup`, `refill` projection (`backlog` 5, `catch-up` 5, `refill` 22)
  - [x] `parser` (157: every phrase the parser tests read, and kind and name corrections),
        `notices` (112)
- [ ] Phone catch-up (ADR 0016):
  - [x] A periodic WorkManager pull (15 min) that reschedules (`BackgroundPullWorker`; a pulled
        answer now re-arms too. Unverified on a device until a build carrying it is installed)
  - [x] A fire-time check before a dose posts: short timeout, fails open (`FireTimeCheck`,
        `FireGate`, 4 s; `DoseBackup` posts if the process dies mid-check). Harness cases 15-18
        pass on an API 34 emulator against a local backend: answered elsewhere stays quiet (461 ms
        cold, on the same machine as the server, so not the phone's number), offline and a slow
        server post at the bound, and a SIGKILL mid-check is posted by the backup. On the phone
        against the VPS (0.13.0, 2026-11-18 08:30): one dose `Open in 2587 ms`, but the one before
        it `TimedOut` on a wake from a frozen process (the connection took about 6 s back), and
        the second waited for the first: the bound was per dose. Now per moment, one shared read
        (case 19). The 6 s was the Convex connection: Android 17 cuts a background app's network
        5 s after it leaves the foreground (`dumpsys netpolicy`: `blocked=APP_BACKGROUND`), the
        Clerk "local-error" bursts land within 100 ms of each cut, and on a wake the connection's
        reconnect races the firewall and then backs off. The check now reads over HTTP with
        retries (`MomentReader`). The API 34 emulator has no such firewall, so the harness cannot
        reproduce the idle wake. Still to see: an idle-wake dose on the phone answered inside the
        bound (`read of … attempt N`)
- [ ] Desktop, `apps/desktop`:
  - [ ] Cargo workspace: `taskrx-desktop-core` (rules, Convex, screen state) and the Rune crate
        (`taskrx-desktop-core` exists; `cargo test` is the third gate in `CLAUDE.md`)
  - [x] Rules ported to Rust against the fixtures: all fourteen files, 779 cases, none
        pending (2026-11-18). `tests/golden.rs` reads them in place and fails on any file or
        call it does not handle:
    - [x] `instance-id`, `local-day` (jiff, zones bundled), `server-schedule`, `server-docs`
          (119 cases)
    - [x] `schedule` (the Occurrence engine, pauses, overrides; 92 cases)
    - [x] `domain` (the lifecycles, pauses, moves; 137 cases)
    - [x] `today`, `answer` (the timeline, the Tasks tab, batches, the sweep, undo, as-needed,
          snooze; 109 cases)
    - [x] `backlog`, `catch-up`, `refill` (32 cases; each catch-up case now names its call)
    - [x] `parser` (157 cases)
    - [x] `notices` (112 cases; the date reader's digit-boundary lookaround through
          `fancy-regex`, Java's ASCII classes spelled out)
    - [x] `push` (21 cases; call arguments are a wire type whose only number is a float64,
          so an Int64 cannot reach a `v.number()` validator)
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
