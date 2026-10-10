# Spec: Native Kotlin on Android, Convex on our own server

> Status: draft, 2026-10-09. Supersedes nothing yet; the Expo app on `main`
> stays the working app until phase 6 says otherwise.

## Problem Statement

The app works. The POC gate passed on a Pixel 8 Pro, and the fused timeline,
adherence, and refill features have landed on top of it. Two things about the
stack it runs on are no longer what the owner wants:

1. **The backend runs on someone else's server.** Convex Cloud holds every
   Reminder and every Reminder Instance — health data — on infrastructure the
   owner does not control.
2. **The app reaches Android through two layers of translation.** Everything
   that makes this app trustworthy is an Android primitive: `setAlarmClock`,
   WorkManager, notification channels, the boot receiver, the exact-alarm
   permission lifecycle. Today each one is reached through React Native, then
   through `react-native-notify-kit`, and several of the hardest bugs in
   `docs/poc-results.md` lived in that gap rather than in the app.

## Solution

- **Android app:** rewrite in Kotlin with Jetpack Compose, calling the Android
  APIs directly. No React Native, no notify-kit.
- **Backend:** keep Convex, but **self-host it** (open-source `convex-backend`,
  Docker) with **Postgres** as its storage. The functions in
  `packages/convex/convex/` stay TypeScript and move over almost unchanged —
  Convex functions are always TypeScript, self-hosted or not.
- **Sync client:** the official Convex Android library,
  `dev.convex:android-convexmobile` (0.8.0 at time of writing), with Clerk
  sign-in through `clerk-convex-kotlin`.

What does **not** change: the domain (`CONTEXT.md`), the ADRs, offline-first
with SQLite as the on-device source of truth, the two notification Tiers, the
conflict rules, Clerk as the identity provider.

### Why not a hand-written server

Considered and rejected (2026-10-09): a Rust + Postgres server. It meets "own
the server" equally, but Convex currently provides realtime pull, auth
verification, and the function runtime for free, and rebuilding sync by hand is
where the bugs would be. Self-hosting gets ownership of the box and the data
without that cost. If Convex itself ever becomes the problem, the sync seam in
the Kotlin app (phase 5) is the one place a replacement would plug in.

## Order of work

Each phase ends with something verified. Nothing later starts until the phase
before it is green.

The core idea: **the Kotlin app is built local-only first.** The app is
offline-first by design, so it is fully useful with no server. That proves the
part that matters most — reminders fire — before any sync work begins.

Phase 1 (self-hosting) is independent of the Kotlin work and is done first
because it is small and de-risks the backend using the *existing*, known-good
Expo app as the test client.

### Phase 0 — Housekeeping

- GitHub becomes `origin`; Forgejo is no longer used. Issues move to GitHub.
- Tag the current tip `archive/expo-rn-v0` and push branch `archive/expo-rn`,
  the same way the .NET line was preserved.
- Commit or revert the pending `opencode.jsonc` change.

### Phase 1 — Self-host Convex on Postgres

- `infra/convex/docker-compose.yml`: `convex-backend`, `convex-dashboard`,
  `postgres`, behind HTTPS (reverse proxy).
- Secrets in the server's environment, never in the repo: Postgres password,
  instance secret, admin key, `CLERK_JWT_ISSUER_DOMAIN`.
- Deploy the existing functions with `npx convex deploy` pointed at the
  self-hosted URL and admin key.
- **Gate:** point the current Expo app at the self-hosted URL and re-run the
  signed-in harness cases (`--auth`, case 12). They must match their Convex
  Cloud verdicts. Back up and restore Postgres once, on purpose, and confirm the
  data comes back.
- Where it runs (decided 2026-10-09): this development machine first, then a
  Hostinger VPS in Docker for live testing. The compose file is the same for
  both; only the domain, TLS, and secrets differ.

### Phase 2 — Kotlin project and the domain port

- New Gradle project at `apps/android/`, modules:
  - `:core` — pure Kotlin/JVM, no Android imports. The port of
    `packages/shared/src`: domain, schedule + horizon, timeline projection,
    sweep planner, adherence, refill (projection, planner, reconcile), parser,
    conflict rules, permission-state, local-day.
  - `:app` — Android, Compose.
- **Tests move with the code.** Every vitest case in `packages/shared` gets a
  JUnit counterpart. The 100% coverage gate carries over (Kover) on the same
  files the vitest gate lists today.
- Time zones: `java.time` replaces hand-rolled date math. The DST cases in
  `local-day` tests must still pass, unchanged in meaning.
- **Gate:** `./gradlew :core:test :core:koverVerify` green; case count at or
  above the vitest count for the ported files.

### Phase 3 — Storage and notifications (local-only app)

- Room over SQLite, same tables as `docs/architecture.md`: `reminders`,
  `reminder_instances`, `sync_queue` (with `dead_letter`). One fresh schema; no
  migration from the Expo database (no released build, no user data — same
  reasoning as the fused-timeline cutover).
- Notifications, direct to the platform:
  - Medication Tier: `AlarmManager.setAlarmClock`, budget 64.
  - Task Tier: WorkManager `OneTimeWorkRequest`, budget 32 (ADR 0007).
  - Diff-based reschedule with the same deterministic ids
    (`${reminderId}-${scheduledTime}`, `task-${reminderId}-${scheduledTime}`).
  - Channel ids `medication_reminders` and `task_reminders` kept **exactly** —
    Android keys the user's overrides to them.
  - Boot receiver re-arms both Tiers.
  - Exact-alarm permission lifecycle and the `POST_NOTIFICATIONS` prompt as in
    the permission model section; `USE_EXACT_ALARM` still never declared.
- `applicationId` stays `com.taskrx.mobile`, so the harness and channel ids
  carry over. The Kotlin build replaces the Expo build on the device.
- The missed sweep (Grace Period 2h, lookback 7 days, ADR 0005).
- **Gate:** unit tests for the reschedule diff and the sweep runner, same
  behaviours the vitest cases in `apps/mobile/src` cover.

### Phase 4 — Screens, then the device gate

- Compose screens matching today's routes: Today timeline, Add (phrase
  parser), Backlog, answer screen (from a notification tap), history, Reminder
  list / new, task new, adherence, refill, permission banner. Drug search
  waits for phase 5 (it needs the server's RxNorm action).
- Harness: the `taskrx:///harness` deep link and its actions, so
  `apps/mobile/scripts/notification-harness.sh` drives the Kotlin app with as
  few changes as possible. Move the script to `apps/android/scripts/`.
- **Gate:** the nine local-only harness cases pass on the Pixel 8 Pro, same
  rig rules as before (signed out; cases 3 and 5 run separately; case 5 needs a
  human to unlock). Evidence to `.omo/evidence/`.

### Phase 5 — Sign-in and sync

- Clerk Android SDK + `clerk-convex-kotlin`, giving a
  `ConvexClientWithAuth`.
- Push: drain `sync_queue` into the existing mutations, 5 attempts then
  dead-letter, backoff 1s→30s.
- Pull: `subscribe` to `reminders.list` and `reminderInstances.list`, apply
  reminders before instances (the reason is in the architecture doc's pull
  section), through the ported conflict rules.
- Field mapping: Convex `_id` / `_creationTime` and number types need explicit
  handling in Kotlin (called out in the library's docs). Pin it with tests
  using the library's JSON format.
- Drug search through the `rxnorm.search` action.
- **Gate:** the signed-in harness cases and case 12 pass against the
  self-hosted backend. All 13 POC criteria re-checked and recorded in
  `docs/poc-results.md`.

### Phase 6 — Retire the Expo app

- Delete `apps/mobile` and `packages/shared` from `main` (both preserved on
  `archive/expo-rn`). `packages/convex` stays.
- Update `README.md`, `docs/architecture.md`, `AGENTS.md`, `CLAUDE.md`, and the
  verification-gate commands. Retire `docs/POC-SUMMARY.md` and
  `docs/post-poc-decisions.md`, which are already stale.

## Carried over, not fixed here

- **Nothing consumes the supply.** A taken dose does not decrement
  `pillsRemaining` (see the 2026-08-14 changelog entry). Port the behaviour as
  it is; fix it as its own change, in Kotlin, after phase 4.
- **The priority field** still has no job (fused-timeline spec, Further Notes).

## Out of Scope

- iOS. A native Kotlin app gives up the shared-code path to iOS that React
  Native had. If iOS comes back, Kotlin Multiplatform for `:core` is the
  natural route — the module is kept free of Android imports so that stays
  possible.
- A web client.
- Replacing Convex. See "Why not a hand-written server".
- Data migration from Convex Cloud. There are no users; the self-hosted
  deployment starts empty.

## Open Questions

- **The VPS details.** Which domain or subdomain points at it, how TLS is
  issued, and where Postgres backups go. Needed before the move from this
  machine to the Hostinger VPS, not before phase 1 starts.
- **Min SDK.** Today's Expo default vs. raising it. Raising it simplifies the
  permission code paths.
- **The library is 0.x.** Pin the version, and read its changelog before each
  bump.
- **Self-hosted + Android client together** is not covered by any guide found
  on 2026-10-09. It should only need the deployment URL, but phase 5 should
  try a bare connection early rather than discover a problem at the end.

## References

- Convex Android client: https://docs.convex.dev/client/android
- Convex self-hosting: https://github.com/get-convex/convex-backend (self-hosted
  guide in `self-hosted/`)
- ADRs 0003 (two Tiers), 0005 (Grace Period, lookback), 0007 (task Tier on
  WorkManager)
