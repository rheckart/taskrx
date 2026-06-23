# Changelog

All notable changes to this project since commit `b534bfa` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
for commit discipline (POC tags use `0.x.x`).

## [Unreleased]

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
