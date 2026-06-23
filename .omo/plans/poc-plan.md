# 2-Week POC Plan — Medication Reminder (Meds-Only, Android Focus)

**Date:** June 21, 2026 (rev. Android pivot, post-approval)
**Repo:** `/home/rob/dev/taskrx` (greenfield, only `docs/` exists)
**Owner:** Solo + AI agents
**Window:** 10 working days
**Target:** Validate the chosen stack can deliver offline-first, time-critical med reminders end-to-end on **Android** (Pixel 8 physical device).

**Revision note (June 21, 2026):** User has Pixel 8 + Android, no iPhone, no Apple Developer account. Plan pivoted from iOS to Android. Architecture stack is unchanged (RN + Expo + Convex + Clerk + Notifee + expo-sqlite works on both). What's different: notification permissions (Android 13+ POST_NOTIFICATIONS, Android 14+ SCHEDULE_EXACT_ALARM), Doze mode handling, no iOS 64-limit, deploy via EAS Android build + adb sideload (no TestFlight).

---

## 1. Goal

Answer one question with evidence:

> **Can the React Native + Expo + Convex + Clerk + Notifee + expo-sqlite stack reliably deliver offline-first medication reminders that fire on time, even when the app is closed, and stay in sync across devices?**

Everything in this plan either contributes to answering that question or is a precondition for it. Nothing else.

---

## 2. POC Scope

### In Scope

| Capability | Why it's in |
|---|---|
| Clerk auth (email + magic link) | Proves the identity boundary Convex will use for per-user data isolation |
| Add medication (RxNorm lookup) | Proves external drug API integration via Convex Actions |
| Simple schedule (daily @ times, or interval hours) | Proves the schedule engine that drives notifications |
| Local notifications via Notifee (Android only) | Proves the critical path: time-accurate reminders when app is closed |
| Dose logging (taken / skipped / missed) | Proves the data model + the most safety-critical interaction |
| Local SQLite as source of truth | Proves offline-first works |
| Background sync to Convex (foreground + reconnect) | Proves cross-device sync |
| Realtime read on second device | Proves Convex's realtime story |
| EAS Build → sideloadable Android APK | Proves the deploy path works |

### Out of Scope (deferred to v2)

Drug interactions · Pill image/shape picker · Caregiver alerts · Refill tracking · Health metrics · PDF reports · Task manager · Web app · iOS · RRULE (use simple recurring only) · Time zones (single TZ for POC) · Multi-med (>3 meds) · Dual-mode UI · Gamification · HIPAA-grade audit logs · Push-when-offline (use local notifications only).

> Anything in the research doc that's not in the "In Scope" table is explicitly deferred. Do not scope-creep.

---

## 3. Success Criteria (POC is DONE = all boxes checkable)

- [ ] Magic-link sign-in completes; user ID persists across app restarts
- [ ] User can search "metformin" → RxNorm returns matches → user selects one
- [ ] User can save medication with schedule ("daily at 09:00" or "every 8 hours")
- [ ] Medication persists in local SQLite across app restart
- [ ] Local notification fires at scheduled time on a **physical Android device (Pixel 8)**, app **closed**, app **not in foreground**, surviving Doze mode + battery optimization
- [ ] Tapping notification opens the dose logging screen pre-filled with the right med + scheduled time
- [ ] Marking dose "taken" writes a `dose_logs` row in local SQLite
- [ ] When online, `dose_logs` rows sync to Convex (visible in Convex dashboard)
- [ ] When offline (airplane mode), dose logs accumulate in `sync_queue`; when back online, they sync within 60s
- [ ] Same user logged in on Device B sees the medication + dose log within 10s of Device A's action
- [ ] Adding 30 meds with daily schedules: rolling scheduler keeps notifications firing without dropping any
- [ ] EAS Build produces a sideloadable Android APK that installs and runs on the Pixel 8
- [ ] Code is in git with clean, atomic commits; `README.md` documents local run + deploy

---

## 4. Tech Stack (locked, per research)

| Layer | Choice | Version pin | Reason |
|---|---|---|---|
| Mobile | React Native + Expo (Android only) | Expo SDK 53 (latest stable as of plan date) | Web story available later, Notifee config plugin, expo-sqlite built-in |
| Navigation | expo-router | latest | File-based, de facto Expo standard |
| Auth | Clerk | `@clerk/clerk-expo` | Dedicated Expo SDK, prebuilt UI, JWT for Convex |
| Backend | Convex | 1.x | Realtime native, Actions for RxNorm, scheduled functions |
| Local DB | expo-sqlite | built-in | Source of truth on device |
| Notifications | Notifee (Android) | latest | Richest local-notification API in RN; supports Android exact alarms + AlarmManager |
| Drug data | RxNorm REST | free, no key | Required by POC scope |
| Deploy | EAS Build (Android APK) + adb sideload | latest | Sideload path; no Play Store / Apple Developer needed for POC |
| Dev client | `expo-dev-client` | latest | Needed for Notifee (not in Expo Go) |
| Language | TypeScript strict | everywhere | No `any` in shared code |
| Repo | npm workspaces | monorepo | `apps/mobile`, `packages/convex`, `packages/shared` |

**Explicitly NOT in POC stack:** Supabase, Tailwind, Sentry, PostHog, RxDB, WatermelonDB, Firebase. Add later only if POC proves a need. **Also not in POC:** iOS (deferred to v2), web (deferred to v2), TestFlight (no Apple account).

---

## 5. Architecture

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
│  └──────────────────────────────┘  │         │                              │
│           │                         │         └──────────────┬───────────────┘
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

**Data flow rules (do not violate):**

1. **Local SQLite is the source of truth on device.** All reads/writes go through repos that hit SQLite first.
2. **Convex is the source of truth in the cloud.** Sync pushes local changes up and pulls remote changes in.
3. **The notification scheduler reads from SQLite, never from Convex.** This is why reminders fire offline.
4. **Schedule computation is pure + deterministic.** Given a med config + current time, it returns the next N dose times. No DB calls inside the engine.
5. **Sync queue is append-only.** Failed syncs stay in the queue with an attempt count; they don't get lost.

---

## 6. Data Model (POC subset)

### 6.1 Local SQLite (`apps/mobile/src/db/migrations/001_init.sql`)

```sql
CREATE TABLE medications (
  id              TEXT PRIMARY KEY,            -- uuid v4
  user_id         TEXT NOT NULL,                -- Clerk user id
  name            TEXT NOT NULL,                -- display name ("Metformin")
  rxcui           TEXT,                         -- RxNorm CUI (nullable; user might add a vitamin)
  dosage          TEXT,                         -- free text ("500mg", "2 tablets")
  schedule_type   TEXT NOT NULL CHECK (schedule_type IN ('daily', 'interval')),
  schedule_times  TEXT NOT NULL,                -- JSON: daily=["09:00","21:00"] | interval={"hours":8,"startAt":<ts>}
  active          INTEGER NOT NULL DEFAULT 1,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  sync_status     TEXT NOT NULL DEFAULT 'pending' CHECK (sync_status IN ('pending','synced','error'))
);
CREATE INDEX idx_medications_user ON medications(user_id);

CREATE TABLE dose_logs (
  id              TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL,
  medication_id   TEXT NOT NULL REFERENCES medications(id) ON DELETE CASCADE,
  scheduled_time  INTEGER NOT NULL,             -- unix ms
  taken_at        INTEGER,                     -- unix ms, nullable
  status          TEXT NOT NULL CHECK (status IN ('pending','taken','skipped','missed')),
  notes           TEXT,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  sync_status     TEXT NOT NULL DEFAULT 'pending'
);
CREATE INDEX idx_dose_logs_medication ON dose_logs(medication_id);
CREATE INDEX idx_dose_logs_user_time ON dose_logs(user_id, scheduled_time);

CREATE TABLE sync_queue (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  entity_type     TEXT NOT NULL CHECK (entity_type IN ('medication','dose_log')),
  entity_id       TEXT NOT NULL,
  operation       TEXT NOT NULL CHECK (operation IN ('create','update','delete')),
  payload         TEXT NOT NULL,                -- JSON snapshot at time of enqueue
  attempts        INTEGER NOT NULL DEFAULT 0,
  last_error      TEXT,
  created_at      INTEGER NOT NULL
);
CREATE INDEX idx_sync_queue_created ON sync_queue(created_at);
```

### 6.2 Convex Schema (`packages/convex/convex/schema.ts`)

```typescript
import { defineSchema, defineTable } from "convex/server";
import { v } from "convex/values";

export default defineSchema({
  medications: defineTable({
    userId: v.string(),                         // from Clerk JWT
    name: v.string(),
    rxcui: v.optional(v.string()),
    dosage: v.optional(v.string()),
    scheduleType: v.union(v.literal("daily"), v.literal("interval")),
    scheduleTimes: v.any(),                     // JSON; shape matches SQLite
    active: v.boolean(),
    createdAt: v.number(),
    updatedAt: v.number(),
    deletedAt: v.optional(v.number()),
  })
    .index("by_user", ["userId"])
    .index("by_user_active", ["userId", "active"]),

  doseLogs: defineTable({
    userId: v.string(),
    medicationId: v.id("medications"),
    scheduledTime: v.number(),
    takenAt: v.optional(v.number()),
    status: v.union(
      v.literal("pending"),
      v.literal("taken"),
      v.literal("skipped"),
      v.literal("missed")
    ),
    notes: v.optional(v.string()),
    createdAt: v.number(),
    updatedAt: v.number(),
  })
    .index("by_user", ["userId"])
    .index("by_medication", ["medicationId"])
    .index("by_user_time", ["userId", "scheduledTime"]),
});
```

### 6.3 Conflict resolution (POC)

- Last-write-wins by `updated_at`
- Soft deletes (`deletedAt`); never hard-delete on Convex side
- Dose logs: never overwrite a "taken" status remotely (they're append-only history)
- If conflict detected on a medication: keep latest `updated_at`; log to a `sync_conflicts` table for later review (POC can skip the table — just `console.warn`)

---

## 7. Implementation Timeline (10 working days)

> Each day shows **what gets built**, **which agent does it**, **what it depends on**, and **what proves it worked**.
> Agents marked `[parallel]` run in the same worktree-day and don't touch each other's files.

### Day 1 — Monorepo + Tooling (single agent, sequential)

**Why first:** everything else needs the scaffold.
**Agent:** build
**Work:**
- `npm init -w apps/mobile -w packages/convex -w packages/shared`
- `apps/mobile`: `npx create-expo-app@latest` then merge into monorepo; or use Expo's monorepo template
- Root `tsconfig.base.json` with strict mode; `package.json` scripts: `dev`, `build`, `typecheck`, `lint`
- ESLint + Prettier + `typescript-eslint` configured identically across workspaces
- `packages/shared` for shared types (medication, dose log shapes)
- `.gitignore`, `README.md` skeleton
- **Stop point:** `npm run typecheck` passes from root, mobile app boots in simulator

**Done when:** `npm run typecheck && npm run -w apps/mobile start` both succeed; commit on `main`.

---

### Day 2 — Auth + Backend Skeleton (2 agents, parallel)

**Agent A — Frontend auth (`apps/mobile`):**
- Install `@clerk/clerk-expo` + `expo-secure-store`
- `<ClerkProvider>` wrapping the app in root layout
- `SignInScreen` + `SignUpScreen` using Clerk prebuilt components
- `RootLayoutNav`: routes between `(auth)` group and `(app)` group based on `useAuth()`
- Configure Apple Sign-In (just for completeness — magic link is the primary path)
- Env: `EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY`

**Agent B — Backend (`packages/convex`):**
- `npx convex dev` init (creates `convex.json`, `convex/_generated/`)
- `convex/schema.ts` (per §6.2)
- `convex/auth.config.ts` with Clerk JWT verifier domain
- Stub `convex/medications.ts` and `convex/doseLogs.ts` with `query` + `mutation` (throw "not implemented" inside)
- Wire `@clerk/clerk-convex` integration in mobile app so `useQuery` calls go through with auth

**Integration check:** logged-in user on mobile can hit a stubbed `convex.medications.list` and get `[]` back. End-of-day demo: empty list returned for authenticated user.

---

### Day 3 — Medication Add Flow (sequential — RxNorm blocks UI)

**Agent A — UI (`apps/mobile/app/(app)/meds/`):**
- `MedSearchScreen.tsx`: text input → debounced (300ms) → `useAction(api.rxnorm.search)` → list of matches → tap to select
- `MedDetailScreen.tsx`: shows selected drug + form for dosage + schedule picker (radio: daily / interval; if daily, multi-time picker; if interval, hours + start time)
- `useMedicationsRepo()` hook returns the local SQLite-backed list (read-through)

**Agent B — Convex Action (`packages/convex/convex/rxnorm.ts`):**
- `searchByName(query: string)` → `fetch("https://rxnav.nlm.nih.gov/REST/approximateTerm.json?term=...")`
- Map RxNorm response to `{rxcui, name}[]`
- Return top 10 matches
- Wrap in `action()` so it can hit external HTTP without bundling

**Agent A — Local SQLite repo (`apps/mobile/src/db/repos/medications.ts`):**
- `init()` runs `001_init.sql` on first launch
- `create(med)`, `list(userId)`, `get(id)`, `update(id, patch)`, `softDelete(id)`
- All return after SQLite write; enqueue to `sync_queue` asynchronously (don't block UI on Convex)

**Done when:** user can add "metformin 500mg daily at 09:00 and 21:00" and see it in a list (local SQLite only; sync is Day 7).

---

### Day 4 — Notification Permissions + Schedule Engine

**Agent A — Schedule engine (`packages/shared/src/schedule/`):**
- Pure functions, no DB:
  - `nextNDaily(times: string[], fromMs: number, n: number): number[]`
  - `nextNInterval(intervalHours: number, startMs: number, fromMs: number, n: number): number[]`
  - `upcomingDosesFor(med, fromMs, n): { medicationId, scheduledTime }[]`
- 100% unit-tested (`vitest`) — this is the safety-critical logic
- Test cases: timezone-naive (POC), DST boundaries (note as future work), intervals crossing midnight

**Agent B — Notification setup (`apps/mobile/src/notifications/`):**
- Android manifest setup: declare `POST_NOTIFICATIONS` (Android 13+) + `SCHEDULE_EXACT_ALARM` (Android 14+) permissions; declare Notifee `AlarmManager` receiver
- `requestPermissions()` handles runtime POST_NOTIFICATIONS prompt + exact-alarm permission check (routes to system settings if denied)
- Create Android notification channel `medication_reminders` (importance HIGH, bypass DND for critical meds)
- `scheduleNotificationsFor(med, upcomingDoses)` → uses Notifee's `TriggerNotification` with timestamp trigger; under the hood Notifee uses `AlarmManager.setExactAndAllowWhileIdle` so reminders survive Doze
- `rescheduleAll(userId)` → reads all active meds, computes next N doses (cap at ~500 on Android), schedules them, and clears stale ones
- `notificationResponseHandler` → if user taps, navigate to `DoseLogScreen` with `medicationId` + `scheduledTime` in URL

**Done when:** scheduling 5 meds with daily schedules shows pending notifications in Notifee debug screen; tap handler logs the right payload; APK builds successfully with the required permissions.

---

### Day 5 — Dose Logging + Local-Only Verification

**Agent A — Dose log repo (`apps/mobile/src/db/repos/doseLogs.ts`):**
- `create({medicationId, scheduledTime})`, `markTaken(id, takenAt)`, `markSkipped(id, notes?)`, `markMissed(id)`
- `pendingForMedication(medicationId)` for the dose-log screen
- `dueOrOverdue(userId, nowMs)` → all doses whose `scheduledTime <= now` and `status = 'pending'`

**Agent B — Dose logging UI (`apps/mobile/app/(app)/dose/`):**
- `DoseLogScreen.tsx`: list of due/overdue doses → tap → action sheet (Take / Skip / Snooze 15m) → confirms → writes to SQLite
- `DoseHistoryScreen.tsx`: 7-day history view, sorted desc by `scheduled_time`

**Done when:** user marks 3 doses taken across 2 meds; closing & reopening the app shows the history persisted. **No sync yet.**

---

### Day 6 — Android Notification End-to-End on Physical Device

**Sequential — no parallel agent.** This is where the POC's biggest risk lives.

**Single agent work:**
- Build a development APK (`eas build --profile development --platform android`) — or local `npx expo run:android --variant release`
- Install on physical Pixel 8 via `adb install` or sideload URL
- Grant notification permission (POST_NOTIFICATIONS) + exact-alarm permission
- Add a med with `daily at (now + 2 minutes)`
- **Lock the device, leave the app closed**
- Verify notification fires at the exact minute
- Repeat for: app force-killed, phone on Do Not Disturb, phone in Low Power Mode, phone in Doze (overnight idle test if possible)
- Document each scenario's result in `docs/poc-notification-tests.md`
- If exact-alarm permission is denied, document the fallback (inexact alarms have ±15min jitter on Android 12+)

**This is a gate.** If local notifications don't fire reliably when the app is closed, the entire stack choice is wrong and we need to revisit before Day 7.

**Done when:** `docs/poc-notification-tests.md` shows ≥4/5 scenarios passing on a physical Pixel 8.

---

### Day 7 — Convex Sync (Background + Foreground)

**Agent A — Sync worker (`apps/mobile/src/sync/worker.ts`):**
- On app foreground + on `NetInfo` reconnect: drain `sync_queue`
- For each entry: call matching Convex mutation with payload
- On success: delete from queue, mark source row `sync_status = 'synced'`
- On error: increment `attempts`, exponential backoff (max 5 attempts), then mark `sync_status = 'error'` and keep in queue
- Heartbeat: ping Convex every 30s when foregrounded, fast-path the queue

**Agent B — Realtime merge (`apps/mobile/src/sync/pull.ts`):**
- `useMedications(userId)` hook: subscribes to `convex.medications.list`, mirrors into local SQLite (upsert by id, last-write-wins)
- `useDoseLogs(userId, since?)` hook: same pattern
- `useConvexSyncStatus()` → returns `'synced' | 'pending' | 'offline'` for UI badge

**Done when:** adding a med on Device A shows it on Device B within 10s. Disconnect Device B's network for 30s, dose log → reconnect → log syncs within 60s.

---

### Day 8 — Android Exact-Alarm Stress + Offline-First Stress

**Agent A — Stress test for exact-alarm reliability:**
- Seed 30 medications with `daily at <unique minute>` schedules (staggered across the day)
- Verify all 30 fire correctly over a single observation window (run with phone idle, app closed)
- Force device into Doze (`adb shell dumpsys deviceidle force-idle`) → verify next scheduled dose still fires
- Add a 31st med mid-test → confirm `rescheduleAll()` picks it up without dropping existing ones
- Test: kill app process via `adb shell am force-stop` → next dose still fires (AlarmManager persists)

**Agent B — Offline-first verification:**
- Airplane mode → add 3 meds → log 5 doses (mix of taken/skipped) → confirm all writes happen locally
- Restore network → within 60s confirm all 8 operations appear in Convex dashboard
- Verify sync queue is empty after sync

**Done when:** `docs/poc-stress-tests.md` documents both scenarios passing on Pixel 8.

---

### Day 9 — EAS Build (Android) + Sideload + Polish

**Agent A — Build pipeline:**
- `eas.json` with `development`, `preview`, `production` profiles (all Android-only)
- `eas build --platform android --profile preview` → produces a sideloadable APK (~10–15 min)
- `adb install` the APK on the Pixel 8
- Verify install + auth + add-med + dose-log + notification all work end-to-end on the production-style build (separate from the dev build, to confirm prod-like behavior)

**Agent B — Polish pass:**
- App icon (use Expo's `splash` and `icon` config — placeholder is fine for POC)
- Empty states with copy ("No medications yet — tap + to add your first")
- Error toasts on sync failures ("Couldn't sync — will retry")
- Loading states on RxNorm search

**Done when:** APK sideload + install + run + end-to-end flow works on Pixel 8.

---

### Day 10 — Documentation + Demo + Decision

**Single agent:**
- `README.md`: prerequisites, env vars, `npm run dev`, adb sideload instructions, architecture diagram
- `docs/poc-results.md`: success criteria checklist with actual results (filled in from Days 6–9)
- `docs/post-poc-decisions.md`: explicitly answer the questions from §11
- Record a 5-minute screen recording of the end-to-end flow for stakeholders
- Final commit + tag `poc-v0.1.0`

**Done when:** README is complete; `poc-results.md` shows every success-criteria box filled; demo recording exists; tag pushed.

---

## 8. Parallelization Map (for solo + agents)

The above is **what** gets built. This section is **how to parallelize**.

| Day | Parallel work? | Notes |
|---|---|---|
| 1 | No | Monorepo scaffold has too many cross-cutting decisions |
| 2 | **Yes — 2 agents** | Frontend auth ⊥ Backend skeleton (different files, no shared code yet) |
| 3 | **Yes — 2 agents** | UI ⊥ Convex Action (UI consumes stubbed action until both ready) |
| 4 | **Yes — 2 agents** | Pure schedule engine (TDD) ⊥ Notification setup |
| 5 | **Yes — 2 agents** | Dose log repo ⊥ Dose logging UI |
| 6 | **No — gate day** | Physical device + iOS permission flow needs undivided attention |
| 7 | **Yes — 2 agents** | Push sync worker ⊥ Pull realtime merge |
| 8 | **Yes — 2 agents** | 64-limit stress ⊥ Offline-first stress (different test setups) |
| 9 | **Yes — 2 agents** | EAS Build pipeline ⊥ Polish (build is the long pole; polish while waiting) |
| 10 | No | Single thread for docs + decisions |

**Solo workflow:** spawn each `[parallel]` block as **two `task()` calls with `run_in_background: true`**, collect both, then proceed. Never manually redo what a subagent was tasked with.

---

## 9. Risks & Mitigations

| # | Risk | Probability | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Clerk ↔ Convex JWT integration misconfigured | High | High | Day 2 priority; follow `@clerk/clerk-convex` official template literally; integration check before Day 3 |
| R2 | Local notifications don't fire reliably when app closed (Doze / battery optimization) | Medium | Critical | **Day 6 is a gate.** Verify on physical Pixel 8 before Day 7. If broken: check exact-alarm permission, white-list app from battery optimization, fallback to inexact alarms with explicit user warning |
| R3 | Android 14+ SCHEDULE_EXACT_ALARM auto-revoked / denied | Medium | High | Request permission at onboarding with clear UX; if denied, fall back to inexact alarms (`setAndAllowWhileIdle`) and document ±15min delivery window |
| R3b | Doze mode delays alarm by hours (idle devices) | Certain | Medium | Use `setExactAndAllowWhileIdle`; user can white-list app from battery optimization; document this limitation in onboarding |
| R4 | RxNorm API slow / rate-limited | Low | Low | Debounce + client-side cache of last 100 searches |
| R5 | EAS Android build first-time takes 10-15 min | Certain | Low | Start Day 9 build immediately; polish happens in parallel |
| R6 | Convex dev quota exhausted during POC | Low | Medium | Free tier is 40 deployments; stay under by batching mutations |
| R7 | Expo SDK 53 ↔ Notifee config plugin mismatch on Android | Low | High | Pin Notifee version per Expo SDK 53 docs at start of Day 4 |
| R8 | Background fetch not guaranteed on Android either | Certain | Medium | Documented limitation; foreground reschedule (AppState listener) is reliable path |
| R9 | Solo context-switching overhead | High | Medium | Parallel agent pattern (§8) limits to 2 concurrent threads max |
| R10 | Pixel 8 unavailable on Day 6 (battery dead, in use, etc.) | Medium | Critical | Have Android emulator on laptop as backup (less reliable but unblocks Days 4–5); confirm device available at start of Day 6 |
| R11 | Android exact-alarm permission silently revoked by OEM (Samsung, Xiaomi are aggressive) | Medium | High | Test on Pixel 8 (stock Android, friendlier); document OEM-specific guidance for v2 |

---

## 10. Verification Plan

### Per-Day Verification (built into each day)
Each day's "Done when" criterion is a verifiable check, not a vibe.

### End-of-POC Verification (Day 10)
Run all success-criteria checks (§3) and record results in `docs/poc-results.md`. Each box is either ✅ (with evidence: log line, screenshot, recording timestamp) or ❌ (with failure description and root cause).

### Demo Path (for stakeholders / future-self)
1. Open app on Pixel 8 (sideloaded APK)
2. Sign in via magic link
3. Search "metformin" → select → schedule daily 9am + 9pm
4. Schedule a dose 2 minutes from now → lock device
5. Notification fires → tap → mark taken
6. Open Convex dashboard → dose log row visible with matching `userId`
7. Open app on second device (same user) → med + dose log visible

---

## 11. Post-POC Decisions

After Day 10, explicitly answer (in `docs/post-poc-decisions.md`):

1. **Stack viable?** YES / NO — with evidence from `poc-results.md`
2. **Add iOS support?** Estimated +1 week for parity (notifee + Clerk + Convex all support iOS; main work is the iOS-specific notification flow + App Store submission)
3. **Add web app?** Estimated +2 weeks; defer until mobile proven
4. **Add drug interactions?** Estimated +2 weeks; needed for "Health Mode" differentiation
5. **Add task manager?** Estimated +3 weeks for TickTick-parity basic features
6. **Build full MVP per research doc (3-4 months)?** Decision based on POC results + user validation
7. **Pivot?** If anything in §9 R1/R2/R3 fires, evaluate alternatives (Expo Notifications, Supabase, Firebase)

---

## 12. Definition of Done

POC is complete when:
- All 13 success-criteria boxes in §3 are checkable with evidence
- `docs/poc-results.md` exists with per-criterion results
- Sideloaded APK is installed and working on the Pixel 8
- `docs/poc-notification-tests.md` and `docs/poc-stress-tests.md` exist
- Code is on `main` with conventional-commit history
- `README.md` documents local dev + APK sideload instructions
- `docs/post-poc-decisions.md` has explicit answers to §11
- Git tag `poc-v0.1.0` exists

---

## 13. Confirmations Already Given (June 21, 2026)

- [x] **Android-only for POC** (user has Pixel 8, no iPhone) — confirmed
- [x] **No Apple Developer account needed** — sideload path instead — confirmed
- [x] **Physical Android device (Pixel 8) available** — confirmed
- [x] **Clerk + Convex accounts** — need to be created; will set up during Day 1–2 — confirmed
- [x] **Plan approved** — start Day 1 — confirmed

### Additional setup needed before Day 6

- [ ] Expo account (free, needed for EAS Build) — create at https://expo.dev
- [ ] `adb` installed on dev machine (`brew install android-platform-tools` on macOS, `sudo apt install adb` on Linux)
- [ ] USB debugging enabled on Pixel 8 (Settings → About → tap Build Number 7× → Developer Options → USB debugging ON)
- [ ] Pixel 8 white-listed from battery optimization (Settings → Battery → Battery optimization → find app → "Don't optimize")

## 14. Day 10 Final Status

**Date completed:** June 22, 2026

**Final commit hash:** `1aae75d`

**Total commits on `main`:** 15

**Tag created:** `poc-v0.1.0` (annotated, local only — not pushed)

### What's done

- Monorepo scaffold with `apps/mobile`, `packages/convex`, `packages/shared` (15 commits, conventional style)
- Clerk auth wired with expo-router `(auth)` / `(app)` groups
- Convex schema, Clerk JWT verifier, RxNorm search Action, medication/doseLog mutations
- Medication add flow with RxNorm search, SQLite repo, schedule picker (daily + interval)
- Schedule engine in `packages/shared` with full unit-test coverage (23/23 tests pass)
- Notifee Android notification scheduler, permissions, channel, tap-to-dose-log handler
- Dose logging repo + UI + 7-day history
- Sync worker, realtime pull hooks, conflict resolution, sync status badge
- EAS config + build scripts (`build-production-apk.sh`, `deploy-production.sh`)
- Debug screens for notifications and stress-test seeding
- Complete docs: README, CHANGELOG, architecture, poc-results, post-poc-decisions, notification tests, stress tests
- Autonomous verification passes: `npm run typecheck`, `npm run lint`, `npm run -w packages/shared test` (23/23), Metro starts

### What's pending user action

- Day 6 physical-device notification tests on Pixel 8 (see `docs/poc-notification-tests.md`)
- Day 8 stress tests: 30-med exact-alarm + offline-first sync (see `docs/poc-stress-tests.md`)
- Day 9 EAS preview APK build + sideload + end-to-end verification on Pixel 8
- Clerk + Convex account setup and `.env` population
- Deploy Convex backend (`npx convex dev` / `npx convex deploy`)

### Autonomous POC verdict

The stack is **code-complete and statically verified**. All code paths compile, lint, and unit-test cleanly. The remaining risk — and the core question the POC was designed to answer — is whether local notifications fire reliably on a physical Pixel 8 when the app is closed/Doze. That can only be verified on device.

---

*Plan author: Sisyphus · 2026-06-21 · Day 10 completed 2026-06-22*
