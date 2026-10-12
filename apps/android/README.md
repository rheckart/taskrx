# TaskRx for Android (Kotlin)

The Kotlin app. It replaced the Expo app, which is preserved on the `archive/expo-rn` branch. Plan: `docs/specs/kotlin-and-self-hosted-convex.md`.
Two Gradle modules:

- `:core` - pure JVM. Every domain rule (parser, schedule, adherence, refill, sweep, notification
  delivery, sync conflict, retry policy, the Convex call shapes). Gated at 100% line and branch
  coverage by Kover. A rule never lives in `:app`.
- `:app` - the Android application: Room storage, notifications, Compose screens, sign-in and sync.

Requirements: JDK 17, the Android SDK (`sdk.dir` in `local.properties`), `compileSdk` 36, `minSdk` 26
(the Convex client library requires 26).

## Build

    ./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
    ./gradlew :app:assembleRelease

## Run

    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am start -n com.taskrx.mobile/.MainActivity

`applicationId` is `com.taskrx.mobile`, the same as the old Expo build, so the Android notification
channels (`medication_reminders`, `task_reminders`) and their per-user overrides carry over.

## Test

    ./gradlew :core:test :core:koverVerify :app:testDebugUnitTest :app:assembleDebug

This is the gate. `:app` tests are JVM unit tests (Robolectric, in-memory Room); nothing needs a
device or emulator. They never load the Convex native library: Robolectric runs
`TestTaskRxApplication`, which builds a local-only graph whatever `local.properties` says.

## Configuration

Two values, read at build time from `apps/android/local.properties` (gitignored) or, failing that,
from environment variables of the same name, into `BuildConfig`:

    CONVEX_URL=http://10.0.2.2:3220
    CLERK_PUBLISHABLE_KEY=pk_test_...

- `CONVEX_URL` - the Convex deployment. `http://10.0.2.2:3220` is the host machine's self-hosted
  backend (`infra/convex`, port 3220) as seen from the Android emulator. For a physical phone use the
  machine's LAN IP and see "Point the app at it" in `infra/convex/README.md` (it applies here
  too), or `adb reverse tcp:3220 tcp:3220` and `http://127.0.0.1:3220`. Release builds refuse
  cleartext HTTP; only the debug manifest allows it, so a release build needs an `https://` URL.
- `CLERK_PUBLISHABLE_KEY` - the same Clerk application the Expo app used
  (it was `EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY` in the old app). Its instance needs a JWT template
  named `convex` (the deployment's `auth.config.ts` expects `applicationID: "convex"`) and email
  code as a sign-in strategy.

If either is empty the build is **local-only**: no sign-in screen, no sync, one fixed local user,
drug search says it needs a connection. Both must be set for sign-in and sync. Changing them needs a
rebuild; a stale build is the usual cause of "it is still offline".

## Sign-in and sync

- Email passcode sign-in and sign-up (`ui/auth`), over the Clerk Android SDK (`auth/ClerkAuthGateway`).
  Signed out with a key configured, the only screen is sign-in; signed in, the day. The header carries
  the sync badge (synced / pending / syncing / offline / error) and the Today screen a Sign out.
- The Clerk user id is the local `userId` and the Convex `subject`; local rows written signed out
  (user `local-user`) are not pushed.
- Convex is given the Clerk `convex` JWT template token (`auth/ConvexAuth.kt`). The published
  `com.clerk:clerk-convex-kotlin` was not used: it requests the default session token, which has no
  `aud: convex` claim.
- Push (`sync/PushWorker`): drains `sync_queue` oldest first, on foreground and when connectivity
  returns; one drain at a time; backoff 1s to 30s; dead-letter on the fifth failure.
- Pull (`sync/PullSync`): live subscriptions to `reminders.list` and `reminderInstances.list`,
  merged by `:core`'s conflict rules (the local row wins ties; a local Completed is never overwritten).
- Drug search calls the server's `rxnorm.search` action (`sync/ConvexDrugSearch`).
- Everything the sync code needs from the Convex library goes through `sync/ConvexGateway`, which the
  tests fake.

Real sign-in needs a person with an inbox for the emailed code; it is checked by hand.

## Device harness (debug builds only)

`scripts/notification-harness.sh` drives a debug build over adb through the hidden deep link
`taskrx:///harness/<action>/<id>/<delayMs>/<nonce>` and verifies from OS state (`dumpsys alarm`,
`dumpsys notification`, logcat). The deep link and the code behind it exist only in debug builds
(`app/src/debug`); `scripts/verify-harness-guards.sh` checks the script reports a missing device as
one. The harness works signed out against the local user. Its signed-in cases (8, 9, 12, 13) report
`SIGNED OUT` until someone signs in on the device. Start from a fresh install or `pm clear`.
