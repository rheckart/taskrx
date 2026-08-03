# Changelog

All notable changes to this project since commit `b534bfa` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
for commit discipline (POC tags use `0.x.x`).

## [Unreleased]

### 2026-08-03 — Criteria 6 and 9 verified on the device

The last two `⏸️ Pending` POC criteria are now verified and, unlike the seven
the issue #7 round trip answered by hand, re-runnable.

- `feat(mobile): expose the signed-in harness actions` — six harness actions
  that read and write the real SQLite tables as the authenticated user, so the
  adb harness can drive the signed-in tree and not just the local-only one.
  Every one refuses without a Clerk subject rather than guessing at it.
- `feat(harness): add device cases 8 and 9 for criteria 6 and 9` — a real tap
  on a real notification, and an airplane-mode round trip measured from the
  Convex deployment rather than from the app's own `sync_status`. Not part of
  `--all`, which stays the local-only delivery baseline.
- `feat(notifications): log every notification-tap outcome` — a tap that went
  nowhere used to be completely silent.
- `fix(harness): verify the app process actually restarted` — `am kill`
  declines to kill a process Android still considers foreground, silently.

Three findings are recorded in `docs/poc-results.md` and **not** fixed here,
the first of which stops reminders reaching a real user:

- A fresh install never requests `POST_NOTIFICATIONS`; the only caller is a
  debug screen. Alarms fire and Android discards every notification.
- Answering a dose mints a duplicate `pending` instance beside the `completed`
  one, and both reach Convex. Adherence counts from these rows.
- After a notification fires, the app's SQLite writes fail with "database is
  locked", surviving a process restart.

### 2026-07-26 — Stack revisit (return to Convex + Expo) + Phase 1

The stack briefly moved to .NET MAUI + ASP.NET Core + PostgreSQL + Aspire and
back. The .NET line of work is preserved on the `archive/dotnet-maui` branch
(tag `archive/dotnet-maui-v0`); `main` continues the original Convex + Expo
target with the fixes below. Rationale in `.omo/plans/convex-expo-revisit.md`.

#### Phase 0 — Rescue + toolchain green

- `chore(archive): .NET MAUI + ASP.NET Core implementation (rescued WIP)` —
  Committed the entire uncommitted .NET tree (287 files across `src/TaskRx.Core`,
  `src/TaskRx.Data`, `apps/TaskRx.Server`, `apps/TaskRx.Maui`,
  `apps/TaskRx.AppHost`, `TaskRx.slnx`) to a new `archive/dotnet-maui` branch
  and tagged `archive/dotnet-maui-v0` before any restoration work. Includes the
  orphaned `src/TaskRx.Maui` project whose `AlarmManager.SetAlarmClock` pipeline
  the user had verified firing on a physical Android 17 device — that
  empirical result directly informed the Phase 1 AlarmType choice below.
- `chore(repo): restore shared tooling on main after .NET archive` —
  Restored `main` to the Expo POC tree (Expo SDK 56 / RN 0.85.3) and carried
  forward the non-.NET tooling assets (`AGENTS.md`, `docs/agents/`, `.claude/`,
  `.pi/`, `opencode.jsonc`, `skills-lock.json`, research doc).
- `fix(lint): resolve 8 pre-existing errors from post-POC commits` — Fixed the
  drift introduced by `b5c70d1` / `abab84a` / `cfec46e` after the POC summary
  was written (`docs/POC-SUMMARY.md` claimed lint was green; it was, at tag
  `poc-v0.1.0`, but has been broken since). `npm run typecheck`, `npm run lint`,
  and `npm run -w packages/shared test` (23/23) now all exit 0 on `main`.

#### Phase 1 Wave 1 — Deps + hygiene

- `fix(deps): remove duplicate @clerk/expo (unused) from mobile app` — Two
  major generations of the Clerk SDK were in `apps/mobile/package.json`
  (`@clerk/clerk-expo@^2.19.31` + `@clerk/expo@^3.5.3`). Grep confirmed 13
  imports of the former and 0 of the latter; removed the dead package.
- `feat(notifications): migrate from archived Notifee to react-native-notify-kit` —
  Invertase archived `@notifee/react-native` on 2026-04-07; the archived
  README now names `react-native-notify-kit` the drop-in maintained fork.
  Dev target RN 0.85.3 (== ours), Apache-2.0, TurboModules only. Fixes the
  killed-app trigger failure (upstream #1100), silent exact-alarm-denial
  drop, DST shift (#875), and pre-request-state (#1237) bugs. Public API
  100% compatible — swap the import path only.
- `fix(sync): dead-letter queue entries after MAX_ATTEMPTS` — SQLite
  migration `003_sync_queue_dead_letter.sql` adds `dead_letter INTEGER`
  column + index. `pending()` now filters dead-lettered rows so the worker
  no longer retries them forever; `deadLettered()` + `requeue(id)` expose
  the terminal set for inspection and manual recovery. Rows are NEVER
  deleted — a dropped medication dose log is unacceptable data loss.
- `refactor(sync): single tested source of truth for conflict resolution` —
  Consolidated the orphaned `apps/mobile/src/sync/conflict.ts` helpers
  (zero call sites; used `>` semantics) with the inline repo `upsertMany`
  logic (`>=` semantics) into `@taskrx/shared`. Picked the repo semantics
  as authoritative and covered them with 10 vitest cases including the
  safety-critical "locally-taken dose is never overwritten" rule.

#### Phase 1 Wave 2 — Notification core

- `fix(notifications): implement real exact-alarm permission lifecycle` —
  Replaced the hardcoded `Promise.resolve(true)` stub in
  `checkExactAlarmPermission()` (which meant the app literally could not
  detect a denial) with the real notify-kit `getNotificationSettings()`
  read. Extracted the derivation to `@taskrx/shared` with 11 vitest cases
  covering iOS null / Android < 12 NOT_SUPPORTED / Android 12+ ENABLED |
  DISABLED and the notify-kit #1237 fix (NOT_DETERMINED before request).
- `fix(notifications): use SET_ALARM_CLOCK with a global 64-dose diff-based horizon` —
  Highest-value single change in Phase 1.
  1. **AlarmType**: switched from `SET_EXACT_AND_ALLOW_WHILE_IDLE`
     (weaker; no OEM resilience) to `SET_ALARM_CLOCK` — the mechanism the
     user has empirically verified firing on a physical Android 17
     device (native MAUI on `archive/dotnet-maui`), and the only
     AlarmType that survives vendor aggressive-kill (Xiaomi MIUI, Oppo
     ColorOS, Huawei EMUI, Vivo FuntouchOS). Same primitive the stock
     Clock app uses. Deliberate tradeoff: persistent status-bar
     alarm-clock glyph while any trigger is pending — acceptable / arguably
     a feature for a medication app. Degrades to `setAndAllowWhileIdle`
     when exact-alarm permission is absent rather than dropping the
     trigger.
  2. **Horizon**: replaced per-medication 10-dose scheduling (which
     scaled to ~300 alarms at 30 meds and blew iOS's hard cap) with a
     **global** 64-dose priority-queue selector in `@taskrx/shared`
     (10 vitest cases).
  3. **Diff-based reschedule**: replaced `cancelAll()`-then-recreate in
     the foreground path with a diff — compute desired 64, read current
     via `notifee.getTriggerNotificationIds()`, cancel only stale,
     create only missing. Deterministic IDs make this idempotent; a
     second reschedule with unchanged data does nothing.

#### Phase 1 Wave 3 — Test harness

- `test(notifications): adb-driven exact-alarm/Doze/killed-app/boot/stress harness` —
  Codified the six manual test scenarios in `docs/poc-notification-tests.md`
  and `docs/poc-stress-tests.md` (which never ran and left 12 of 13 POC
  criteria pending) into an executable bash script at
  `apps/mobile/scripts/notification-harness.sh` and a hidden
  `apps/mobile/app/harness.tsx` deep-link route. Every case is
  agent-executable via adb (appops, deviceidle, force-stop, reboot,
  dumpsys, logcat) and emits JSON to `.omo/evidence/`. Auth gate has a
  documented security carve-out so `/harness` runs on a fresh install.

#### Phase 1 Wave 4 — Emulator regression gate

- `fix(android): bump compileSdkVersion 35 -> 36 for androidx.core 1.18.0` —
  Gradle failed on the transitive `androidx.core:1.18.0` requirement of
  compileSdk >= 36. Bumped compileSdk; `targetSdkVersion` stays at 34
  (runtime behaviour unchanged). APK builds cleanly on Node 26 + Expo
  SDK 56 + notify-kit New Arch (`BUILD SUCCESSFUL in 4m 25s`, 260MB
  debug APK).

### Phase 1 verification status

- All code fixes committed and passing static verification: `typecheck` 0,
  `lint` 0, `packages/shared` **54/54** vitest cases pass (23 schedule +
  10 conflict + 11 permission-state + 10 horizon).
- Debug APK builds and installs on the API 36 emulator. Notifee/notify-kit
  native side initializes at process start. Metro bundles cleanly (1986
  modules in 8s). Full evidence in
  `.omo/evidence/task-{3,5,12}-convex-expo-revisit.log`.
- The physical Android 17 device run is the authoritative test and is
  pending user action (plug in the device with USB debugging on, tap the
  discovered "TaskRx" server card in DevLauncher once, then
  `apps/mobile/scripts/notification-harness.sh --all`).

## [0.1.0] - POC

### Day 1 — Monorepo + Tooling

- `chore: scaffold monorepo (Day 1 of POC plan)` — Created npm workspaces for `apps/mobile`, `packages/convex`, and `packages/shared`; configured TypeScript strict, ESLint, Prettier, Husky, and lint-staged.

### Day 2 — Auth + Backend Skeleton

- `feat(mobile): wire Clerk auth with expo-router groups` — Added `@clerk/clerk-expo`, split routes into `(auth)` and `(app)` groups, and wired `useAuth()` for navigation gating.
- `feat(convex): add schema, Clerk JWT, stubbed functions` — Created Convex schema per plan §6.2, added Clerk JWT verifier, and stubbed `medications` / `doseLogs` functions.

### Day 3 — Medication Add Flow

- `feat(convex): implement RxNorm search Action (Day 3 POC)` — Implemented `rxnorm.search` Convex Action calling the NLM RxNorm approximate-term API.
- `feat(mobile): add medication UI flow with SQLite repo (Day 3 POC)` — Built medication search, add form with schedule picker, and local SQLite repo with sync-queue enqueueing.

### Day 4 — Notification Permissions + Schedule Engine

- `feat(mobile): wire Notifee + Android notification scheduler (Day 4 POC)` — Added Android notification permissions, reminder channel, and Notifee-based rolling scheduler.
- `fix(shared): reach 100% schedule engine coverage with v8 ignore` — Hardened schedule-engine unit tests to 100% coverage.

### Day 5 — Dose Logging + Local-Only Verification

- `feat(mobile): dose logging flow (repo + UI + history) — Day 5 POC` — Added dose-log repo, dose-log screen (take / skip / snooze), and 7-day history view.

### Day 6 — Android Notification End-to-End

- `chore(mobile): Day 6 — EAS config, debug APK build, notification test script` — Added `eas.json`, debug APK build script, and notification test documentation.

### Day 7 — Convex Sync (Background + Foreground)

- `feat(convex): implement medications/doseLogs mutations with localId mapping` — Added Convex mutations for medications and dose logs with local-ID mapping.
- `feat(mobile): sync worker, pull hooks, conflict resolution` — Implemented sync worker, realtime pull hooks, and last-write-wins conflict resolution.
- `feat(ui): sync status badge and local-first reads` — Added sync-status badge and switched reads to local-first SQLite.

### Day 8 — Android Exact-Alarm Stress + Offline-First Stress

- `chore(poc): Day 8 — stress test scripts (30-med exact-alarm + offline-first)` — Added stress-seed scripts and documentation for 30-med exact-alarm and offline-first scenarios.

### Day 9 — UX Polish + EAS Production Build Prep

- `feat(ui): polish — toasts, loading states, icon` — Added custom `Toast` + `useToast`, skeleton loading states, and generated app icon + splash screen.
- `chore(build): refine eas.json + production build script` — Added `production` profile, Play Store submit stub, runtime version policy, and production build/deploy scripts.
- `docs(poc): README + CHANGELOG + architecture + decision templates` — Rewrote README, added CHANGELOG, architecture doc, and post-POC decision/results templates.
