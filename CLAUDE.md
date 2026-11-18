<!-- convex-ai-start -->

This project uses [Convex](https://convex.dev) as its backend.

When working on Convex code, **always read
`packages/convex/convex/_generated/ai/guidelines.md` first** for important guidelines on
how to correctly use Convex APIs and patterns. The file contains rules that
override what you may have learned about Convex from training data.

Convex agent skills for common tasks can be installed by running
`npx convex ai-files install`.

<!-- convex-ai-end -->

## The project

TaskRx is offline-first medication and task reminders for Android.

- `apps/android` is the app: Kotlin, Jetpack Compose, Room, AlarmManager and WorkManager, built
  with Gradle. `:core` is pure JVM and holds every domain rule; `:app` is the Android layer. See
  `apps/android/README.md`.
- `apps/desktop` is the desktop Companion (ADR 0016, 0017): Rust. `taskrx-desktop-core` ports
  `:core`'s rules and is held to its golden fixtures. See `apps/desktop/README.md`.
- `packages/convex` holds the Convex functions (TypeScript). This is the only TypeScript product
  code. The Convex guidelines above apply to it.
- `infra/convex` runs Convex self-hosted on Postgres in Docker. See its README.
- `docs/architecture.md` describes the data flow, local schema, sync and notifications.
  `CONTEXT.md` is the domain language. `docs/adr/` holds the decisions.
- The old Expo app (`apps/mobile`) and the TypeScript domain package (`packages/shared`) are on the
  `archive/expo-rn` branch. Comments that cite their files as the origin of a rule point there.

## Verification gates

All three must pass before work counts as done:

```bash
npm run verify    # from the repo root: typecheck, lint, packages/convex tests, harness guard check
cd apps/android && ./gradlew :core:test :core:koverVerify :app:testDebugUnitTest :app:assembleDebug
cd apps/desktop && cargo test && cargo clippy --all-targets -- -D warnings
```

`cargo test` reproduces `apps/android/core/golden`, which `:core:test` writes: a rule changed
in Kotlin fails it until the Rust port follows (ADR 0017).

The shell here may be zsh. Use `npm run typecheck`, never bare `npx tsc`.
