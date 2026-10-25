# TaskRx

Offline-first medication and task reminders for Android. A native Kotlin app
(Jetpack Compose, Room, AlarmManager, WorkManager) syncs to a self-hosted
Convex backend on Postgres, with Clerk for sign-in.

[![Android](https://img.shields.io/badge/platform-Android-green)](./apps/android)

The stack has changed twice. A .NET MAUI + ASP.NET Core line is preserved on
`archive/dotnet-maui`. The React Native + Expo app that followed it is preserved
on `archive/expo-rn`, together with `packages/shared`, the TypeScript domain
package the Kotlin `:core` module was ported from. The reasoning for the move to
Kotlin and self-hosting is in
[docs/specs/kotlin-and-self-hosted-convex.md](./docs/specs/kotlin-and-self-hosted-convex.md).

## Repo layout

```
.
├── apps/
│   └── android/          # Kotlin app (Gradle). See its README.
│       ├── core/         #   :core, pure JVM: every domain rule, 100% covered by Kover
│       ├── app/          #   :app, Android: Room, notifications, Compose, sign-in, sync
│       └── scripts/      #   adb notification harness and its guard check
├── packages/
│   └── convex/           # Convex functions (TypeScript): schema, reminders, instances, RxNorm
├── infra/
│   └── convex/           # Self-hosted Convex + Postgres (docker compose, backup, restore)
├── docs/
│   ├── architecture.md
│   ├── CHANGELOG.md
│   ├── adr/              # Architecture decision records
│   ├── specs/            # Design specs
│   ├── poc-results.md    # Device evidence for the POC criteria
│   ├── poc-notification-tests.md
│   └── poc-stress-tests.md
├── CONTEXT.md            # Domain language
└── README.md
```

## Prerequisites

- Node.js 18 or later (22 LTS recommended) and npm. Workspaces are used.
- JDK 17 and the Android SDK (`compileSdk` 36) for the Kotlin app.
- Docker, to run the self-hosted Convex backend.
- A physical Android device or an emulator. For the device harness use an image
  without Play Store (`google_apis`, not `google_apis_playstore`); the harness
  needs `adb shell cmd appops`, which Play Store images restrict.
- A Clerk application for sign-in. The app also works local-only without one.

## Setup

```bash
npm install
cp .env.example .env     # Clerk issuer domain and Convex deploy key, for deploying functions
```

Run the backend: see [infra/convex/README.md](./infra/convex/README.md). Build and run the
app: see [apps/android/README.md](./apps/android/README.md). The app reads `CONVEX_URL` and
`CLERK_PUBLISHABLE_KEY` from `apps/android/local.properties`; with either empty it builds
local-only.

## Verification gates

From the repo root:

```bash
npm run verify
```

This runs typecheck, lint, the `packages/convex` tests, and the harness guard check
(`apps/android/scripts/verify-harness-guards.sh`).

For the Android app:

```bash
cd apps/android && ./gradlew :core:test :core:koverVerify :app:testDebugUnitTest :app:assembleDebug
```

`:core:koverVerify` enforces 100% line and branch coverage on `:core`. The `:app` tests are JVM
unit tests (Robolectric, in-memory Room); no device is needed.

## Device harness

`apps/android/scripts/notification-harness.sh` drives a debug build over adb through a hidden
`taskrx:///harness` deep link and checks the result against OS state (`dumpsys alarm`,
`dumpsys notification`, logcat). It writes JSON evidence to `.omo/evidence/`.

```bash
apps/android/scripts/notification-harness.sh --list
apps/android/scripts/notification-harness.sh --all
```

`--all` runs the nine local-only cases. The rig rules (app signed out, cases 3 and 5 run
separately) are in [docs/poc-results.md](./docs/poc-results.md).

## Architecture

See [docs/architecture.md](./docs/architecture.md) for the data flow, the local schema, sync,
and the notification design. The domain language is in [CONTEXT.md](./CONTEXT.md).

## Contributing

- Use conventional commits.
- Do not commit `.env` files, `local.properties`, or secrets.
- Do not add `USE_EXACT_ALARM` to the manifest. It is a Play-policy liability for medication apps.
- Keep the notification primitive `AlarmManager.setAlarmClock` for doses. The choice is deliberate
  and OEM-resilient (see docs/architecture.md).
- Keep the channel ids `medication_reminders` and `task_reminders`. Android keys the user's
  overrides to them.
- Do not put Android imports in `apps/android/core`.

## License

MIT. See [LICENSE](LICENSE).
