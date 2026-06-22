# TaskRx

POC monorepo for a combined task + medication reminder app.

## Overview

This repository validates the React Native + Expo + Convex + Clerk + Notifee + expo-sqlite stack for offline-first, time-critical medication reminders on Android.

## Prerequisites

- Node.js >= 18
- npm (workspaces are used; do not use yarn/pnpm for this POC)
- Android emulator or physical Android device with USB debugging
- `adb` (for physical device deploys)

## Setup

```bash
npm install
```

Copy `.env.example` to `.env` and fill in real values:

```bash
cp .env.example .env
```

## Development

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

## Testing

Unit tests for the schedule engine live in `packages/shared/src/schedule/` and run with `vitest` (added on Day 4).

## Deploy

Build a preview Android APK via EAS:

```bash
cd apps/mobile
npx eas build --platform android --profile preview
```

Install the resulting APK on a Pixel 8 with `adb install`.

## Contributing

- Use conventional commits.
- Keep TypeScript strict (`noImplicitAny`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`).
- Do not commit `.env` files or secrets.
