# TaskRx

> **Status: POC (Phase 1 code-complete)** — Offline-first medication reminders for Android. Expo + Convex + Clerk + react-native-notify-kit + expo-sqlite. Physical-device verification pending (`docs/poc-results.md`).

The stack pivoted through .NET MAUI + ASP.NET Core + PostgreSQL earlier in July 2026 before returning to the original Convex + Expo target. That .NET line of work is preserved verbatim on the [`archive/dotnet-maui`](https://github.com/…/tree/archive/dotnet-maui) branch (tag `archive/dotnet-maui-v0`); this branch is the Convex + Expo continuation. Rationale for the pivot in [`.omo/plans/convex-expo-revisit.md`](./.omo/plans/convex-expo-revisit.md).

[![POC](https://img.shields.io/badge/status-POC-orange)](./docs/poc-results.md)
[![TypeScript](https://img.shields.io/badge/TypeScript-strict-blue)](./tsconfig.base.json)
[![Android](https://img.shields.io/badge/platform-Android-green)](./apps/mobile)

## Quick start

### Prerequisites

- Node.js — Node 22 LTS recommended; Node 26 verified to work with the current pinned Expo SDK 56 / RN 0.85.3.
- npm (workspaces are used; do not use yarn/pnpm for this POC).
- JDK 17 or 21 (react-native-notify-kit's validated Android baseline).
- Android SDK with `platforms;android-36` and `build-tools;36.0.0` installed. compileSdk targets 36, targetSdk stays at 34.
- Android emulator (`system-images;android-36;google_apis;x86_64` — NOT `google_apis_playstore`; the harness needs `adb shell cmd appops` which Play Store images restrict) OR a physical Android device with USB debugging enabled.
- Clerk + Convex accounts (free tiers).

### Install

```bash
npm install
```

Copy `.env.example` to `.env` and fill in real values:

```bash
cp .env.example .env
```

Required environment variables:

| Variable                            | Source           |
| ----------------------------------- | ---------------- |
| `EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY` | Clerk dashboard  |
| `EXPO_PUBLIC_CONVEX_URL`            | `npx convex dev` |

### Development

Start the Expo dev server:

```bash
npm run dev
```

Run TypeScript checks across all workspaces:

```bash
npm run typecheck
```

Run lint + format:

```bash
npm run lint
npm run format
```

Run unit tests (schedule engine, conflict resolution, permission state machine, global-horizon selector — 54 vitest cases):

```bash
npm run -w packages/shared test
```

## Architecture

```
┌────────────────────────────────────┐         ┌──────────────────────────────┐
│  Expo App (Android)                │         │ Convex Backend               │
│                                    │         │                              │
│  ┌──────────────────────────────┐  │         │ Schema:                      │
│  │ expo-sqlite (source of       │  │         │  - medications               │
│  │  truth on device)            │  │         │  - doseLogs                  │
│  │  - medications               │  │         │                              │
│  │  - dose_logs                 │  │         │ Functions:                   │
│  │  - sync_queue + dead_letter  │  │         │  - medications.{query,...}   │
│  └──────────────────────────────┘  │         │  - doseLogs.{query,...}      │
│           │                        │         │  - rxnorm.search (Action)    │
│           ▼                        │         │                              │
│  ┌──────────────────────────────┐  │         │ Clerk JWT verifier:          │
│  │ scheduleEngine + horizon     │  │         │  - enforces userId on all    │
│  │  (@taskrx/shared, pure TS)   │  │         │    reads/writes              │
│  │  - daily/interval schedules  │  │         └──────────────┬───────────────┘
│  │  - global 64-dose selector   │  │                        │
│  └──────────────────────────────┘  │                        │
│           │                        │                        │
│           ▼                        │                        │
│  ┌──────────────────────────────┐  │                        │
│  │ react-native-notify-kit      │  │                        │
│  │  - AlarmType.SET_ALARM_CLOCK │  │                        │
│  │  - diff-based reschedule     │  │                        │
│  │  - fallback on denied perm   │  │                        │
│  └──────────────────────────────┘  │                        │
│           │                        │                        │
│           ▼                        │  HTTPS (Convex client) │
│  ┌──────────────────────────────┐  │                        │
│  │ syncWorker.ts                │──┼───────────────────────▶│
│  │  - drains sync_queue         │  │                        │
│  │  - retries + dead-letter     │  │◀────── realtime ───────│
│  │  - listens for connectivity  │  │                        │
│  └──────────────────────────────┘  │                        │
│                                    │                        │
│  Clerk (auth) ─────────────────────┼───────────────────────▶│
│  (JWT attached to Convex requests) │                        │
└────────────────────────────────────┘
```

See [docs/architecture.md](./docs/architecture.md) for the full data-flow rules, data model, and the notification-primitive decision.

## Tech stack

| Layer         | Choice                                | Reason                                                                                               |
| ------------- | ------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| Mobile        | React Native 0.85.3 + Expo SDK 56     | File-based routing, config-plugin ecosystem, built-in SQLite. New Architecture (TurboModules) on.    |
| Navigation    | expo-router                           | De facto Expo standard.                                                                              |
| Auth          | Clerk (`@clerk/clerk-expo`)           | Prebuilt UI, JWT for Convex.                                                                         |
| Backend       | Convex 1.41+                          | Realtime native, Actions for RxNorm. Offline sync is hand-rolled (Convex's own is still unreleased). |
| Local DB      | expo-sqlite                           | Offline source of truth. Migrations 001–003, dead-letter column on sync_queue.                       |
| Notifications | react-native-notify-kit 10.5+         | Maintained fork of the archived Notifee (Invertase archived it 2026-04-07 and points here).          |
| Alarm type    | `AlarmType.SET_ALARM_CLOCK`           | OEM-resilient. Same primitive the stock Clock app uses. Renders the status-bar alarm-clock icon.     |
| Exact alarms  | `SCHEDULE_EXACT_ALARM` only           | `USE_EXACT_ALARM` deliberately NOT declared — not verifiably approved by Google Play for meds.       |
| Drug data     | RxNorm REST                           | Free, no API key.                                                                                    |
| Build         | Local Gradle (`assembleDebug`) or EAS | Debug build verified on emulator + Node 26 + JDK 17.                                                 |
| Language      | TypeScript strict                     | No `any` in shared code. 54 vitest tests in `@taskrx/shared`.                                        |
| Repo          | npm workspaces                        | `apps/mobile`, `packages/convex`, `packages/shared`                                                  |

## Repo layout

```
.
├── apps/
│   └── mobile/                         # Expo React Native app
│       ├── app/                        # expo-router screens
│       │   ├── (auth)/                 # sign-in / sign-up
│       │   ├── (app)/                  # authenticated app
│       │   └── harness.tsx             # hidden adb harness route
│       ├── plugins/
│       │   └── withAndroidNotificationPermissions.js
│       ├── scripts/
│       │   ├── notification-harness.sh # adb-driven test harness
│       │   └── build-*.sh              # EAS build helpers
│       └── src/
│           ├── components/             # Toast, Skeleton, SchedulePicker
│           ├── db/                     # SQLite repos + migrations (001–003)
│           ├── notifications/          # scheduler + permission lifecycle
│           └── sync/                   # sync worker + realtime pull hooks
├── packages/
│   ├── convex/                         # Convex schema, queries, mutations, RxNorm action
│   └── shared/                         # Pure TS domain: schedule engine + horizon + conflict + permission-state
├── docs/
│   ├── architecture.md
│   ├── CHANGELOG.md
│   ├── poc-results.md
│   ├── post-poc-decisions.md
│   ├── poc-notification-tests.md
│   └── poc-stress-tests.md
├── .omo/
│   └── plans/convex-expo-revisit.md    # Phase 1 execution plan
└── README.md
```

## Testing

**Unit tests** live in `packages/shared` and run with `vitest`. Coverage thresholds pin `schedule/engine.ts` to 100%.

```bash
npm run -w packages/shared test
```

**Notification harness** — an adb-driven bash script that verifies six device-behaviour cases (killed-app delivery, `SET_ALARM_CLOCK` primitive in use, Doze delivery, exact-alarm-denied fallback, boot reschedule, 30-med stress horizon). Requires the app installed on a device/emulator.

```bash
apps/mobile/scripts/notification-harness.sh --list
apps/mobile/scripts/notification-harness.sh --all
```

The harness invokes a hidden `/harness` deep-link route in the app; see the security carve-out in `app/_layout.tsx` (auth gate bypass) and the route implementation in `app/harness.tsx`. Report is emitted as JSON to `.omo/evidence/`.

## Build and deploy

### Local debug build

```bash
cd apps/mobile
npx expo prebuild --platform android --no-install
cd android
./gradlew assembleDebug
# APK at apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk (~260MB)
```

Verified building cleanly on Node 26 + JDK 17 + Android compileSdk 36.

### Install on a device

```bash
adb install -r apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk
adb reverse tcp:8081 tcp:8081     # so the emulator can reach host Metro
cd apps/mobile && npx expo start --dev-client
```

Then tap the discovered "TaskRx" server card in the DevLauncher once (per install) to hand control to the app.

### Preview APK via EAS (sideload)

```bash
./apps/mobile/scripts/build-production-apk.sh
```

### Production AAB (Play Store)

```bash
./apps/mobile/scripts/build-production-apk.sh --production
```

Play Store upload is stubbed in [deploy-production.sh](./apps/mobile/scripts/deploy-production.sh) — not wired.

## Contributing

- Use conventional commits.
- Keep TypeScript strict (`noImplicitAny`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`).
- Do not commit `.env` files or secrets.
- Do not add dependencies outside the POC stack without justification.
- Do not add `USE_EXACT_ALARM` to the manifest — it is a Play-policy liability for medication apps.
- Do not revert the notification `AlarmType` to `SET_EXACT_AND_ALLOW_WHILE_IDLE`; the `SET_ALARM_CLOCK` choice is deliberate and OEM-resilient.

## License

UNLICENSED — proprietary proof-of-concept.
