# POC Summary — TaskRx Medication Reminder (Android)

> **Status:** Code-complete and statically verified. Device verification pending.
> **Tag:** `poc-v0.1.0` (local, annotated)
> **Final commit:** `1aae75d`
> **Commits on `main`:** 15

---

## What we built

Over 10 days we built a code-complete Android proof-of-concept for an offline-first medication reminder app. The POC validates whether the React Native + Expo + Convex + Clerk + Notifee + expo-sqlite stack can deliver time-critical reminders that fire when the app is closed and stay in sync across devices. All code paths compile, lint, and unit-test cleanly; the only remaining validation is on-device testing on a Pixel 8.

---

## Key features delivered

- **Clerk magic-link auth** with expo-router `(auth)` / `(app)` groups and JWT-forwarding to Convex
- **Medication add flow** with RxNorm search, dosage input, and daily/interval schedule picker
- **Local SQLite source of truth** for medications, dose logs, and sync queue
- **Pure schedule engine** (shared package) computing upcoming doses deterministically
- **Notifee local notifications** on Android with exact-alarm scheduling, notification channel, and tap-to-log handler
- **Dose logging** (taken / skipped / missed) with 7-day history
- **Convex sync** via sync worker + realtime pull hooks, with conflict resolution and sync-status badge
- **Debug tooling** for notifications and 30-med stress-test seeding
- **EAS build pipeline** with preview/production profiles and helper scripts

---

## Stack

| Layer | Choice | Reason |
|---|---|---|
| Mobile | React Native + Expo SDK 53 | File-based routing, Notifee plugin, built-in SQLite |
| Navigation | expo-router | De facto Expo standard |
| Auth | Clerk (`@clerk/clerk-expo`) | Prebuilt UI, JWT for Convex |
| Backend | Convex 1.x | Realtime native, Actions for RxNorm |
| Local DB | expo-sqlite | Offline source of truth |
| Notifications | Notifee (Android) | Exact alarms, AlarmManager, Doze survival |
| Drug data | RxNorm REST | Free, no API key |
| Build | EAS Build (Android) | Cloud builds + sideload APK |
| Language | TypeScript strict | No `any` in shared code |
| Repo | npm workspaces | `apps/mobile`, `packages/convex`, `packages/shared` |

---

## Architecture

```
┌────────────────────────────────────┐         ┌──────────────────────────────┐
│  Expo App (Android)                 │         │ Convex Backend               │
│                                    │         │                              │
│  ┌──────────────────────────────┐  │         │ Schema:                      │
│  │ expo-sqlite (source of      │  │         │  - medications               │
│  │  truth on device)            │  │         │  - doseLogs                  │
│  │  - medications               │  │         │  - userDevices               │
│  │  - dose_logs                 │  │         │                              │
│  │  - sync_queue                │  │         │ Functions:                   │
│  └──────────────────────────────┘  │         │  - medications.{query,...}   │
│           │                         │         │  - doseLogs.{query,...}      │
│           ▼                         │         │  - rxnorm.search (Action)    │
│  ┌──────────────────────────────┐  │         │                              │
│  │ scheduleEngine.ts            │  │         │ Clerk JWT verifier:          │
│  │  - computes next N doses     │  │         │  - enforces userId on all    │
│  │  - rolling scheduler        │  │         │    reads/writes              │
│  └──────────────────────────────┘  │         └──────────────┬───────────────┘
│           │                         │                        │
│           ▼                         │                        │
│  ┌──────────────────────────────┐  │                        │
│  │ Notifee (local notifications)│  │                        │
│  │  - reads next 64 doses       │  │                        │
│  │  - schedules via OS          │  │                        │
│  └──────────────────────────────┘  │                        │
│           │                         │                        │
│           ▼                         │                        │
│  ┌──────────────────────────────┐  │  HTTPS (Convex client)  │
│  │ syncWorker.ts                │──┼────────────────────────▶
│  │  - drains sync_queue         │  │                        │
│  │  - retries with backoff      │  │◀─────── realtime ──────│
│  │  - listens for connectivity  │  │                        │
│  └──────────────────────────────┘  │                        │
│                                    │                        │
│  Clerk (auth) ─────────────────────┼────────────────────────▶
│  (JWT attached to Convex requests) │                        │
└────────────────────────────────────┘
```

See [docs/architecture.md](./architecture.md) for full data-flow rules and data model.

---

## What's verified (autonomous checks)

| Check | Command | Result |
|---|---|---|
| TypeScript strict | `npm run typecheck` | ✅ Pass |
| Lint | `npm run lint` | ✅ Pass |
| Unit tests | `npm run -w packages/shared test` | ✅ 23/23 pass |
| Metro dev server | `cd apps/mobile && npx expo start` | ✅ Reaches "Metro waiting" |
| Git history | `git log --oneline` | ✅ 15 clean conventional commits |

---

## What's pending user action

- [ ] **Day 6 notification tests** on Pixel 8 — see [docs/poc-notification-tests.md](./poc-notification-tests.md)
- [ ] **Day 8 stress tests** — 30-med exact-alarm + offline-first sync — see [docs/poc-stress-tests.md](./poc-stress-tests.md)
- [ ] **Day 9 EAS build + sideload** — build preview APK and install on Pixel 8
- [ ] **Clerk + Convex account setup** — create accounts and populate `.env`
- [ ] **Deploy Convex backend** — `npx convex dev` or `npx convex deploy`

---

## Commits

| Day | Commit | Message |
|---|---|---|
| Day 1 | `b534bfa` | chore: scaffold monorepo |
| Day 2 | `371522e` | feat(mobile): wire Clerk auth with expo-router groups |
| Day 2 | `9886e40` | feat(convex): add schema, Clerk JWT, stubbed functions |
| Day 3 | `f16ec92` | feat(convex): implement RxNorm search Action |
| Day 3 | `b7d0420` | feat(mobile): add medication UI flow with SQLite repo |
| Day 4 | `a314f56` | feat(mobile): wire Notifee + Android notification scheduler |
| Day 4 | `91e054b` | fix(shared): reach 100% schedule engine coverage |
| Day 5 | `7d405cd` | feat(mobile): dose logging flow (repo + UI + history) |
| Day 6 | `08936b8` | chore(mobile): Day 6 — EAS config, debug APK build, notification test script |
| Day 7 | `baf76a9` | feat(convex): implement medications/doseLogs mutations with localId mapping |
| Day 7 | `f8b6e0a` | feat(mobile): sync worker, pull hooks, conflict resolution |
| Day 7 | `10031b1` | feat(ui): sync status badge and local-first reads |
| Day 8 | `a2bda14` | chore(poc): Day 8 — stress test scripts |
| Day 9 | `ebcddc5` | chore(poc): Day 9 — UX polish + production build prep + docs |
| Day 9 | `1aae75d` | fix(lint): remove unnecessary type assertion in home screen |

---

## Known limitations

- **iOS:** deferred to v2; no Apple Developer account needed for POC
- **Web:** deferred to v2
- **Drug interactions:** stubbed for v2 (RxNorm search only)
- **Task manager:** deferred to v2
- **Push notifications:** local notifications only for POC; push for caregiver alerts deferred
- **Time zones:** single time zone assumed for POC
- **Multi-med stress:** code paths exist; physical verification pending
- **Production deploy:** Play Store upload script is a stub

---

## Next steps

1. Run the Day 6 notification test script on a Pixel 8: [docs/poc-notification-tests.md](./poc-notification-tests.md)
2. Run the Day 8 stress test script: [docs/poc-stress-tests.md](./poc-stress-tests.md)
3. Build and sideload the preview APK: `./apps/mobile/scripts/build-production-apk.sh`
4. Review go/no-go decisions: [docs/post-poc-decisions.md](./post-poc-decisions.md)
5. If device tests pass, greenlight MVP build per [docs/task-med-reminder-app-research (2).md](./task-med-reminder-app-research%20(2).md)

---

## Verdict

**Autonomous checks: PASS.** The codebase is complete, typed, linted, tested, and ready for device validation.

**Device verification: PENDING.** The single open question — whether Notifee exact alarms fire reliably on a closed/Doze Pixel 8 — requires the user-run tests in `docs/poc-notification-tests.md` and `docs/poc-stress-tests.md`.
