<!-- headroom:rtk-instructions -->

# RTK (Rust Token Killer) - Token-Optimized Commands

When running shell commands, **always prefix with `rtk`**. This reduces context
usage by 60-90% with zero behavior change. If rtk has no filter for a command,
it passes through unchanged — so it is always safe to use.

## Key Commands

```bash
# Git (59-80% savings)
rtk git status          rtk git diff            rtk git log

# Files & Search (60-75% savings)
rtk ls <path>           rtk read <file>         rtk grep <pattern>
rtk find <pattern>      rtk diff <file>

# Test (90-99% savings) — shows failures only
rtk pytest tests/       rtk cargo test          rtk test <cmd>

# Build & Lint (80-90% savings) — shows errors only
rtk tsc                 rtk lint                rtk cargo build
rtk prettier --check    rtk mypy                rtk ruff check

# Analysis (70-90% savings)
rtk err <cmd>           rtk log <file>          rtk json <file>
rtk summary <cmd>       rtk deps                rtk env

# GitHub (26-87% savings)
rtk gh pr view <n>      rtk gh run list         rtk gh issue list

# Infrastructure (85% savings)
rtk docker ps           rtk kubectl get         rtk docker logs <c>

# Package managers (70-90% savings)
rtk pip list            rtk pnpm install        rtk npm run <script>
```

## Rules

- In command chains, prefix each segment: `rtk git add . && rtk git commit -m "msg"`
- For debugging, use raw command without rtk prefix
- `rtk proxy <cmd>` runs command without filtering but tracks usage
<!-- /headroom:rtk-instructions -->

## The project

TaskRx is offline-first medication and task reminders for Android.

- `apps/android` is the app: Kotlin, Jetpack Compose, Room, AlarmManager and WorkManager, built with Gradle. `:core` is pure JVM (no Android imports) and holds every domain rule, gated at 100% coverage by Kover; `:app` is the Android layer. See `apps/android/README.md`.
- `packages/convex` holds the Convex functions (TypeScript), the only TypeScript product code.
- `infra/convex` runs Convex self-hosted on Postgres in Docker. See its README.
- `docs/architecture.md` describes the data flow, local schema, sync and notifications. `CONTEXT.md` is the domain language.
- The old Expo app (`apps/mobile`) and the TypeScript domain package (`packages/shared`) live on the `archive/expo-rn` branch. Code comments that cite one of their files as the origin of a rule mean that branch.

## Verification gates

```bash
npm run verify    # from the repo root: typecheck, lint, packages/convex tests, harness guard check
cd apps/android && ./gradlew :core:test :core:koverVerify :app:testDebugUnitTest :app:assembleDebug
```

Use `npm run typecheck`, never bare `npx tsc`, when the shell is zsh.

## Agent skills

### Issue tracker

Issues live on GitHub (`origin`, private `rheckart/taskrx`). `docs/agents/issue-tracker.md` still describes the retired Forgejo setup and is out of date.

### Triage labels

Uses the default label vocabulary (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

<!-- convex-ai-start -->

This project uses [Convex](https://convex.dev) as its backend.

When working on Convex code, **always read
`packages/convex/convex/_generated/ai/guidelines.md` first** for important
guidelines on how to correctly use Convex APIs and patterns. The file contains
rules that override what you may have learned about Convex from training data.

Convex agent skills for common tasks can be installed by running
`npx convex ai-files install`.

<!-- convex-ai-end -->
