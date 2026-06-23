# TaskRx

> **Status: POC** — This is a 10-day proof-of-concept, not a production app.

Offline-first medication reminders for Android, built to validate whether the React Native + Expo + Convex + Clerk + Notifee + expo-sqlite stack can deliver time-critical reminders that fire when the app is closed and stay in sync across devices.

[![POC](https://img.shields.io/badge/status-POC-orange)](./docs/poc-results.md)
[![TypeScript](https://img.shields.io/badge/TypeScript-strict-blue)](./tsconfig.base.json)
[![Android](https://img.shields.io/badge/platform-Android-green)](./apps/mobile)

## Quick start

### Prerequisites

- Node.js >= 18
- npm (workspaces are used; do not use yarn/pnpm for this POC)
- Android emulator or physical Android device with USB debugging
- `adb` (for physical device deploys)
- Expo account (free, required for EAS Build)
- Clerk + Convex accounts (free tiers)

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
| `CONVEX_URL`                        | `npx convex dev` |

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

Run unit tests:

```bash
npm run -w packages/shared test
```

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

See [docs/architecture.md](./docs/architecture.md) for the full data-flow rules and data model.

## Tech stack

| Layer         | Choice                      | Reason                                                     |
| ------------- | --------------------------- | ---------------------------------------------------------- |
| Mobile        | React Native + Expo SDK 53  | File-based routing, Notifee config plugin, built-in SQLite |
| Navigation    | expo-router                 | De facto Expo standard                                     |
| Auth          | Clerk (`@clerk/clerk-expo`) | Prebuilt UI, JWT for Convex                                |
| Backend       | Convex 1.x                  | Realtime native, Actions for RxNorm                        |
| Local DB      | expo-sqlite                 | Offline source of truth                                    |
| Notifications | Notifee (Android)           | Exact alarms, AlarmManager, Doze survival                  |
| Drug data     | RxNorm REST                 | Free, no API key                                           |
| Build         | EAS Build (Android)         | Cloud builds + sideload APK                                |
| Language      | TypeScript strict           | No `any` in shared code                                    |
| Repo          | npm workspaces              | `apps/mobile`, `packages/convex`, `packages/shared`        |

## Repo layout

```
.
├── apps/
│   └── mobile/                 # Expo React Native app
│       ├── app/                # expo-router screens
│       ├── assets/             # icon, splash, adaptive icons
│       ├── scripts/            # build helpers
│       ├── src/
│       │   ├── components/     # Toast, Skeleton, SchedulePicker
│       │   ├── db/             # SQLite repos + migrations
│       │   ├── notifications/  # Notifee scheduler + permissions
│       │   └── sync/           # sync worker + pull hooks
│       ├── app.json
│       └── eas.json
├── packages/
│   ├── convex/                 # Convex schema, functions, RxNorm action
│   └── shared/                 # Shared types + schedule engine (vitest)
├── docs/
│   ├── architecture.md
│   ├── CHANGELOG.md
│   ├── poc-results.md
│   ├── post-poc-decisions.md
│   ├── poc-notification-tests.md
│   └── poc-stress-tests.md
└── README.md
```

## Testing

Unit tests for the schedule engine live in `packages/shared/src/schedule/` and run with `vitest`.

```bash
npm run -w packages/shared test
```

End-to-end device tests are documented in:

- [docs/poc-notification-tests.md](./docs/poc-notification-tests.md)
- [docs/poc-stress-tests.md](./docs/poc-stress-tests.md)

## Build and deploy

### Preview APK (sideload)

```bash
./apps/mobile/scripts/build-production-apk.sh
```

This submits an EAS Build with the `preview` profile and prints the build URL. When it completes, download the APK and install it:

```bash
adb install taskrx-preview.apk
```

### Production AAB (Play Store)

```bash
./apps/mobile/scripts/build-production-apk.sh --production
```

This produces a signed AAB. Play Store upload is stubbed in [deploy-production.sh](./apps/mobile/scripts/deploy-production.sh) for future use.

## Contributing

- Use conventional commits.
- Keep TypeScript strict (`noImplicitAny`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`).
- Do not commit `.env` files or secrets.
- Do not add dependencies outside the POC stack without justification.

## License

UNLICENSED — proprietary proof-of-concept.
