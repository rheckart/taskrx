# POC Results

This document tracks the 13 success criteria from the POC plan §3. Each row shows the criterion, the expected result, the actual result, and a pass/fail verdict.

**Status:** 2026-07-30, Phase 1 gate **PASS**. The notification harness reports **7/7 on a physical Pixel 8 Pro (Android 17, API 37)** in a single end-to-end `--all` invocation (`exit 0`), taken against commit `a931bbf` with a clean working tree, closing criteria #5, #5a, #11 and #12. The remaining criteria are the auth / RxNorm / sync / cross-device ones, which need a signed-in Clerk + Convex session.

> **Baseline (2026-07-30).** All seven cases have been run against current
> `main` on the physical Pixel 8 Pro, in one sweep, and all seven passed.
> Evidence: `.omo/evidence/issue-5-baseline-all-cases.json`, which records the
> commit it was taken against in its `provenance` block. This supersedes the
> earlier standing note that no device run had been performed against current
> code.
>
> This baseline exists so that the fused-timeline work — which adds a second
> delivery Tier for tasks — can be checked against a known-good starting point.
> "Did this change break delivery?" is now an answerable question.
>
> Instrument defects were fixed to get here, one of them the cause of every
> historical case-5 ambiguity. See "Case 5 was measuring the wrong thing" below
> before reading any pre-2026-07-30 verdict in this file.

> **Write-path cutover checked against that baseline (2026-07-31, issue #6).**
> All seven cases pass again at commit `1bb8928` with a clean tree, on the same
> Pixel 8 Pro and the same OS build, and every verdict matches the baseline.
> The measurements match too, not just the pass/fail: case 6 `pending_count`
> 30 → 30, case 1 `exact_windows` 1 → 1, case 5 alarms before reboot 3 → 3.
> Evidence: `.omo/evidence/issue-6-cutover-verification.json`.
>
> Read as an answer to one narrow question — "did moving the device write path
> onto the unified Reminder tables break delivery?" — the answer is no. Do not
> read it more widely than that. The app runs **unauthenticated** under the
> harness (logcat: `[config] running local-only: clerk=false convex=false`),
> and every case drives `scheduleForReminder` with an in-memory reminder, so
> none of them touches SQLite, `rescheduleAll(userId)`, or any of the cutover
> code. What is verified is that the notification layer beneath it is
> undisturbed. This is the same caveat criterion #11 already carries.
>
> It took three invocations rather than one, and the reason is worth recording
> because it will recur: case 3 forces deep Doze by turning the screen off,
> which re-locks the keyguard, and a locked device cannot foreground the app —
> so cases 4, 5 and 6 reported UNREACHABLE behind it. That is the two-pass
> split the harness header documents under constraint 5, not a product finding.
>
> One instrument defect found and **not** fixed, so that the comparison ran on
> the same instrument as the baseline: when the device drops off USB, every
> `dumpsys` returns empty and the harness infers *device state* from that
> silence. It recorded "the device rebooted into the lock screen and was never
> unlocked within 300s" while `adb devices` was empty, and blamed the empty
> alarm dump on a renamed section header. A poll that cannot reach the device
> should report an unreachable *instrument*, not a device state. Same family as
> "Case 5 was measuring the wrong thing" below.

## Success criteria

| # | Criterion | Expected | Actual | Pass/Fail |
|---|---|---|---|---|
| 1 | Magic-link sign-in completes; user ID persists across app restarts | User can sign in and remains signed in after force-stop | Clerk `@clerk/clerk-expo` wired end-to-end incl. token cache; duplicate `@clerk/expo` package removed; no runtime device verification yet | ⏸️ Pending (device test) |
| 2 | User can search "metformin" → RxNorm returns matches → user selects one | RxNorm search returns relevant results | Convex Action `rxnorm.search` implemented and typed against the real NLM `approximateTerm.json` endpoint; UI debounces + guards stale responses | ⏸️ Pending (device test) |
| 3 | User can save medication with schedule ("daily at 09:00" or "every 8 hours") | Medication saved locally with correct schedule | SQLite repo + schedule picker implemented; 23 vitest cases on the schedule engine pass; no device verification yet | ⏸️ Pending (device test) |
| 4 | Medication persists in local SQLite across app restart | Medication list survives force-stop | `expo-sqlite` repo persists to device storage; migrations 001–003 apply cleanly on boot; no runtime restart test yet | ⏸️ Pending (device test) |
| 5 | Local notification fires at scheduled time on a physical Android device, app closed, not in foreground, surviving Doze + battery optimization | Notification fires within 1 minute of scheduled time | **VERIFIED on Pixel 8 Pro / Android 17 / API 37.** Uses `AlarmType.SET_ALARM_CLOCK`. Delivered with the app process dead (`am kill`, 0 processes, `stopped=false`) across **three independent runs: 34 ms, 94 ms, and 31 ms late** — all far inside the 1-minute budget. Full chain in logcat: `Start proc ... for broadcast {NotificationAlarmReceiver}` → `NotifeeHeadlessJS: launched taskId: 1` → notification posted with correct title + `medicationId`. Primitive confirmed at OS level, not inferred: `window=0`, `exactAllowReason=permission`, `whenElapsed == maxWhenElapsed`, and the alarm registered as the device's *next alarm clock* with a timestamp exactly matching the scheduled one. The Doze clause is verified in criterion 5a below. **Re-verified 2026-07-30 against `a931bbf`** in a 7/7 single `--all` run — evidence `.omo/evidence/issue-5-baseline-all-cases.json`. Earlier evidence: `.omo/evidence/task-13-all-cases-final.json`, `.omo/evidence/task-13-SUMMARY.md`, `task-13-cases-1-4.json`, `task-13-cases-6-2.json` (all taken before the alarm-scoping fix; their `exact_windows` counts are inflated by `dumpsys` history — see "Case 5 was measuring the wrong thing"). | ✅ **PASS** |
| 5a | *(sub-criterion)* Notification survives **deep Doze** specifically | Fires while device is in deep IDLE | **VERIFIED on Pixel 8 Pro / Android 17.** With the app process killed and the device on simulated battery, it entered deep `IDLE` with the alarm pending and dozed continuously — observed cycle `IDLE → IDLE_MAINTENANCE → IDLE → IDLE_MAINTENANCE → QUICK_DOZE_DELAY`, never returning to `ACTIVE` before the alarm fired — and the notification still posted. `dumpsys alarm` shows Doze imposes **zero deferral**: `policyWhenElapsed: requester=+2m22s657ms … device_idle=-1m21s983ms` (device_idle resolves earlier than the request) with `whenElapsed == maxWhenElapsed`. **Finding:** Android refuses to enter deep Doze at all while a wake-from-idle alarm is due within `min_time_to_alarm` (default **1 hour**) — `force-idle` reports `Unable to go deep idle; stopped at QUICK_DOZE_DELAY`. That refusal is itself evidence the alarm is classified wake-from-idle. **Accommodation:** `min_time_to_alarm` was lowered to 30s for this case only and stock constants restored afterwards (verified `device_idle_constants = null`); no battery-optimization exemption was granted. Evidence: `.omo/evidence/task-13-case-3-deep-idle.json`, `.omo/evidence/task-13-all-cases-final.json`. | ✅ **PASS** |
| 6 | Tapping notification opens the dose logging screen pre-filled with the right med + scheduled time | Correct medication and time shown | Tap handler + deep-link routing implemented (`src/notifications/tap-handler.ts`); notification data includes `medicationId` and `scheduledTime` for the dose-log deep link; no device verification yet | ⏸️ Pending (device test) |
| 7 | Marking dose "taken" writes a `dose_logs` row in local SQLite | Row exists with status `taken` | Dose log repo + UI implemented; 10 vitest cases on conflict resolution guarantee a locally `taken` dose is never overwritten by a remote pull; no device verification yet | ⏸️ Pending (device test) |
| 8 | When online, `dose_logs` rows sync to Convex (visible in Convex dashboard) | Row appears in Convex within 60s | Sync worker + Convex mutations implemented; requires deployed backend + device test | ⏸️ Pending (Day 7 / device test) |
| 9 | When offline (airplane mode), dose logs accumulate in `sync_queue`; when back online, they sync within 60s | Queue drains and rows appear in Convex | Sync queue + retry logic implemented; MAX_ATTEMPTS=5, exponential backoff to 30s cap. **Phase 1 fix**: dead-letter state (migration 003) ensures failed rows no longer retry forever; retained rows can be requeued via debug UI. Requires device test. | ⏸️ Pending (device test) |
| 10 | Same user logged in on Device B sees the medication + dose log within 10s of Device A's action | Realtime pull updates Device B | Realtime pull hooks implemented; requires two-device test | ⏸️ Pending (device test) |
| 11 | Adding 30 meds with daily schedules: rolling scheduler keeps notifications firing without dropping any | All 30 notifications fire in observation window | **Phase 1 fix**: scheduler now uses a **global 64-dose priority queue** across all active meds (`selectUpcomingDoses` in `@taskrx/shared`, 10 vitest cases), not 10 per med. Diff-based reschedule is idempotent. **VERIFIED on device** via harness case 6: 30 scheduled → `pending_count=30`, within the [1,64] cap, no drops. Evidence: `.omo/evidence/task-13-cases-6-2.json`. Note: this exercises harness-direct scheduling; the full `rescheduleAll(userId)` path from SQLite still needs a signed-in run. | ✅ **PASS** (cap + no-drop verified) |
| 12 | EAS Build produces a sideloadable Android APK that installs and runs on a physical device | APK installs and app launches | ✅ **VERIFIED on physical device.** `./gradlew assembleDebug` produces a 260MB debug APK (Node 26 + Expo SDK 56 + notify-kit New Arch, BUILD SUCCESSFUL in 4m 25s); installed and launched on the Pixel 8 Pro (Android 17) with Metro tunnelled over USB via `adb reverse tcp:8081 tcp:8081`. Entry bundle confirmed as `apps/mobile/index.ts` (1991 modules). EAS cloud build still unused. | ✅ **PASS** |
| 13 | Code is in git with clean, atomic commits; `README.md` documents local run + deploy | Conventional commits; README complete | ✅ Verified (autonomous) — 20+ conventional commits on `main` since POC tag; `archive/dotnet-maui` branch preserves the parallel .NET line of work; README rewritten to reflect the current stack (Expo SDK 56, react-native-notify-kit, SET_ALARM_CLOCK, harness script); CHANGELOG, architecture, and this document updated | ✅ Pass |

## Evidence log

### Phase 1 static verification (`.omo/evidence/task-{3,5,12}-*.log`)

| Check | Command | Result |
|---|---|---|
| TypeScript strict | `npm run typecheck` | ✅ exit 0 |
| Lint | `npm run lint` | ✅ exit 0 (after fixing 8 pre-existing errors from post-POC commits) |
| Unit tests | `npm run -w packages/shared test` | ✅ 54/54 pass (23 schedule engine + 10 conflict + 11 permission state + 10 global horizon) |
| Metro dev server | `npx expo start --dev-client` | ✅ Reaches "Metro waiting", bundles 1986 modules in 8s |
| Debug APK build | `./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL in 4m 25s (260MB APK, Node 26 + Expo SDK 56 + notify-kit New Arch) |
| APK install | `adb install -r app-debug.apk` | ✅ Success on emulator-5554 (Android 16, API 36) |
| Notify-kit boot receiver | Notifee `(RebootReceiver): Received reboot event` | ✅ Native init runs on process start |
| Git history | `git log --oneline` | ✅ Conventional commits on `main`; recovery point on `archive/dotnet-maui-v0` |

### Phase 1 blockers cleared

- ✅ `@notifee/react-native` (archived 2026-04-07) → `react-native-notify-kit@10.5.0` (drop-in fork).
- ✅ Duplicate Clerk SDK (`@clerk/expo` unused) removed.
- ✅ `checkExactAlarmPermission()` hardcoded stub → real `getNotificationSettings()` lifecycle.
- ✅ `AlarmType.SET_EXACT_AND_ALLOW_WHILE_IDLE` (no OEM resilience) → `SET_ALARM_CLOCK` (the primitive the user has verified working on Android 17).
- ✅ Per-medication 10-dose scheduler (blows iOS's 64-notification cap) → global 64 with diff-based reschedule.
- ✅ Sync queue never dead-lettered (retried forever) → migration 003 adds terminal state; rows retained.
- ✅ Orphaned `sync/conflict.ts` helpers with zero call sites → consolidated into `@taskrx/shared` with 10 vitest cases.
- ✅ `USE_EXACT_ALARM` (Play-policy liability for medication apps) removed from manifest.

### Day 6 — Physical device notification tests

See `docs/poc-notification-tests.md` for the (now-executable) test cases. The manual checklists are superseded by `apps/mobile/scripts/notification-harness.sh`.

| Scenario | Result | Notes |
|---|---|---|
| App closed, normal mode | ✅ Pass | Case 1 — posted with the app backgrounded then killed |
| Force-killed | ✅ Pass | Case 1 — `am kill`, `procs_after_kill=0`, `stopped=false`, `exact_windows=1` (2026-07-30; earlier runs read `2` off an unscoped `dumpsys` grep). Note: the harness deliberately does **not** use `am force-stop`; that sets the package `stopped=true` flag and Android then drops broadcasts to its receivers by OS contract, which is not an app defect |
| Do Not Disturb | ⏸️ Pending | Manual — harness verifies delivery only, not DND channel behaviour |
| Low Power Mode | ⏸️ Pending | Doze is verified (case 3), but battery-saver mode itself was not separately exercised |
| Doze mode | ✅ Pass | Case 3 — deep `IDLE` reached with the alarm pending, dozed continuously, notification still posted |
| Boot reschedule | ✅ Pass | Case 5 — `before=3 after=3`, receiver landing ~61s after the first unlock (2026-07-30; see "Case 5 was measuring the wrong thing") |
| Exact-alarm denied fallback | ✅ Pass | Case 4 — reported denied AND still delivered inexactly (`window=+33s718ms`) |
| `SET_ALARM_CLOCK` primitive in dumpsys | ✅ Pass | Case 2 — `window=0`, `exactAllowReason=permission`, registered as the device's next alarm clock with a matching timestamp |

### Day 8 — Stress tests

See `docs/poc-stress-tests.md`. Also superseded by the harness.

| Scenario | Result | Notes |
|---|---|---|
| 30-medication rolling horizon | ✅ Pass | Case 6 — `pending_count=30`, within the [1,64] cap, no drops |
| Doze force-idle | ✅ Pass | Case 3 — on 2026-07-30 deep IDLE was reached by **stepping**, with no `force-idle` fallback needed (both runs that day); the 2026-07-27 run had required `dumpsys deviceidle force-idle deep` after `step deep` stalled at `QUICK_DOZE_DELAY`. The fallback remains in the harness for when stepping stalls |
| Force-stop then next dose | ✅ Pass | Case 1 — via `am kill` (see the force-stop note in the Day 6 table) |
| Offline-first 3 meds + 5 doses | ⏸️ Pending | Not yet in harness — manual test |
| Restore network sync within 60s | ⏸️ Pending | Not yet in harness — manual test |

### Day 9 — Production-style build

| Check | Result | Notes |
|---|---|---|
| Local debug APK builds | ✅ Verified | `./gradlew assembleDebug` produces `app-debug.apk` (260MB) |
| APK installs on emulator | ✅ Verified | `emulator-5554` (Android 16, API 36) |
| APK installs on physical device | ✅ Verified | Pixel 8 Pro, Android 17 / API 37, serial 38220DLJG000JK — installed, launched, and ran the full 7-case harness (2026-07-30) |
| EAS preview APK builds | ⏸️ Pending | `./apps/mobile/scripts/build-production-apk.sh` (requires EAS account) |

## Summary

Verified on a physical Pixel 8 Pro (Android 17, API 37) on 2026-07-30 against
commit `a931bbf` with a clean working tree.
Full evidence: `.omo/evidence/issue-5-baseline-all-cases.json` (7/7, single
`--all` invocation, `exit 0`); historical narrative in
`.omo/evidence/task-13-SUMMARY.md`.

- **Passed: 4/13** — #5 (killed-app exact delivery, 31-94 ms across 3 runs, plus
  sub-criterion #5a deep-Doze survival), #11 (30-med horizon cap, no drops),
  #12 (APK builds + installs + runs on hardware), #13 (git + docs). The
  graceful-degradation contract (harness case 4) and the `SET_ALARM_CLOCK`
  primitive proven at OS level (harness case 2) are supporting evidence for #5,
  not separate criteria.
- **Pending: 9/13** — #1, #2, #3, #4, #6, #7, #8, #9, #10 all require a
  signed-in session (Clerk + Convex `.env`), which is orthogonal to the
  notification question Phase 1 set out to answer. Phase 1's gate was the
  notification stack, not these; they are Phase 2 work.
- **Inconclusive: 0** — deep-Doze survival (criterion 5a) is now **verified**;
  see that row for the observed doze cycle, the `dumpsys alarm` zero-deferral
  policy line, and the `min_time_to_alarm` accommodation it required.
- **Failed: 0**
- **Blockers:** none for the Phase 1 gate. The load-bearing question — does an
  exact `SET_ALARM_CLOCK` notification fire on Android 17 with the app killed,
  in deep Doze, and after a reboot — is answered **YES**, with the harness
  reporting **7/7 in a single end-to-end `--all` run (`exit 0`)** against
  `a931bbf` on 2026-07-30. The reboot leg of that question (case 5) is answered
  cleanly for the first time; see "Case 5 was measuring the wrong thing".

### Harness case tally

All seven measured in a single `--all` invocation on 2026-07-30 against commit
`a931bbf`, clean tree, Pixel 8 Pro / Android 17 / API 37, serial 38220DLJG000JK.
Evidence for every row: `.omo/evidence/issue-5-baseline-all-cases.json`.

| # | Case | Verdict | Measured | Detail |
|---|------|---------|----------|--------|
| 1 | killed_app_delivery | ✅ PASS | 2026-07-30 | posted after `am kill`; `procs_after_kill=0`, `stopped=false` |
| 2 | alarm_clock_primitive | ✅ PASS | 2026-07-30 | `window=0`, `exactAllowReason=permission`, registered as the device's next alarm clock with a matching timestamp |
| 3 | doze_deep_idle | ✅ PASS | 2026-07-30 | reached deep IDLE by stepping; cycle `IDLE→IDLE_MAINTENANCE→IDLE→IDLE_MAINTENANCE→QUICK_DOZE_DELAY`; never returned to ACTIVE before fire |
| 4 | exact_denied_degrades | ✅ PASS | 2026-07-30 | reported denied AND still delivered inexactly (`window=+33s718ms`) |
| 5 | boot_reschedule | ✅ PASS | 2026-07-30 | `before=3 after=3`, cross-checked against the OS per-uid tally (=3); all armed 15m+ out so none could have come due during the reboot; receiver landed ~61s after the first unlock |
| 6 | stress_horizon_cap | ✅ PASS | 2026-07-30 | 30 scheduled → `pending_count=30`, within [1,64] |
| 7 | weekly_schedule | ✅ PASS | 2026-07-30 | 3 alarms, all Mon/Wed/Fri 09:00 in America/New_York |

This is the first single-invocation 7/7. Case 7 had previously only ever passed
inside a partial `--case 4 --case 6 --case 7` invocation.

#### Case 5 was measuring the wrong thing

Case 5 had never cleanly passed. Every archived artifact recorded `after <
before` (`task-13-all-cases-final.json` 14→2; `task-13-case-5-boot.json` and
`harness-todo19-case-5-rerun.json` both 3→2), which tripped the `after >=
before` gate introduced in `ae455d0` and scored INCONCLUSIVE. The standing
reading was that a count cannot separate "the boot receiver under-restored"
from "those alarms legitimately fired during the reboot".

Neither reading was right. **The count was not counting pending alarms.**

`dumpsys alarm` prints the live alarm store first and then a run of history and
statistics sections — `Removal history`, `Allow while idle history`, `Next wake
from idle` — and those also carry `*walarm*:<pkg>` tags, for alarms that have
already fired or been cancelled. Every alarm helper grepped the whole dump.
Measured on the Pixel 8 Pro with exactly 3 alarms armed, the unscoped grep
counted **14** (3 pending + 1 `Next wake from idle` duplicate + 10
removal-history entries) while the OS's own `Pending alarms per uid` line read
`u0a221:3`.

That made the case unfalsifiable rather than merely noisy: **a reboot clears the
removal history**, so `after` came out structurally smaller than `before`
however perfectly the boot receiver did its job. The `after < before` pattern in
all three archived artifacts is that artifact, not a product signal — and the
tightened gate could never have been satisfied.

With alarm reads scoped to the pending portion of the dump, case 5 passed on the
first attempt at `before=1 after=1`.

It was then hardened further, because `before=1` is the weakest value that
can satisfy `after >= before` — the partial-restore loss the case exists to
catch ("armed 3, re-registered 2") cannot occur at a count of one. It now arms
`BOOT_ALARM_COUNT` (default 3) alarms, all 15m+ out so none can come due during
the reboot, which removes the "or they legitimately fired" reading entirely; a
missing alarm afterwards has one explanation and is reported as a product
defect rather than an ambiguity. It also asserts, before rebooting, that the
scoped count agrees with an independent parse of the OS's own per-uid tally —
if the two disagree the instrument is at fault and the case says so instead of
measuring with a broken ruler. In the recorded baseline all three signals
agree: `before=3 after=3`, per-uid tally 3. A partial restore was detectable
there and did not occur, which the `before=1` form could not have established.

The alarm-store parse is additionally checked once as a startup precondition.
An empty parse means the section whitelist no longer matches the Android build,
and would otherwise read as "zero alarms" and score product FAILs; the run now
aborts with exit 2 instead.

Cases 2 and 4 read alarm windows through the same helpers, so their pre-fix
verdicts were also taken with a contaminated instrument. Both were re-measured
and both still pass — case 2 now reports `window=0 (x1)` rather than `(x2)`, and
case 4's `+33s718ms` matches the pre-fix `+33s722ms`, confirming that particular
read had been genuine.

**Ten false-result bugs have now been found and fixed in the harness itself.**
Nine came out of the 2026-07-27/28 verification — a fixed 20s boot grace against
a boot receiver that actually lands at ~62-66s, a 60s wait against an inexact
alarm window of up to ~79s, `adb reverse` not surviving case 5's reboot, and
deep links into an app a previous case had killed being treated as "false"
rather than "unreachable". Each had reported a FAIL or INCONCLUSIVE for
behaviour that was correct. See `.omo/evidence/task-13-SUMMARY.md` for the full
list.

The tenth is the alarm-scoping defect above. It differs from the other nine in
direction: they reported environmental faults as product ones, which is noisy
but self-announcing, whereas this one made a safety-critical verdict
unobtainable and looked like a genuine product ambiguity for three runs.

## Reproducing the device run

1. Plug in the physical Android 17 device with USB debugging enabled; confirm `adb devices` shows it.
2. Install the debug APK: `adb -s <serial> install -r apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk` (or rebuild via `./gradlew assembleDebug`).

   `apps/mobile/android/local.properties` is not checked in, so a rebuild needs
   the SDK path in the environment: `ANDROID_HOME=~/Android/Sdk ./gradlew
   assembleDebug` from `apps/mobile/android`. Without it Gradle fails at
   evaluation with "SDK location not found", which reads like a project fault
   and is not one.

   Only **native** inputs need a rebuild — `apps/mobile/android/**`, `app.json`,
   or a dependency bump. JS changes do *not*: this is an Expo dev client and
   loads its JS from Metro, which is also why the harness anchors its provenance
   to the commit rather than to the binary.

   Do **not** decide by comparing the APK's mtime against commit dates — a
   change is typically built to verify it and committed minutes later, so the
   binary legitimately predates the commit that contains it. Just run
   `assembleDebug` and let Gradle's up-to-date check decide, or read the value
   out of the binary:

   ```bash
   $ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging <apk> | grep compileSdkVersion
   ```
3. Start Metro: `cd apps/mobile && npx expo start --dev-client`, then `adb -s <serial> reverse tcp:8081 tcp:8081`.
4. **Disable the device's screen lock for the duration.** Case 3 needs screen-off to reach deep Doze, and screen-off engages the keyguard, which prevents foregrounding the app for the next case. This is a test-rig setting, not a product requirement — restore your PIN afterwards.
5. Run the harness: `ANDROID_SERIAL=<serial> apps/mobile/scripts/notification-harness.sh --all --output .omo/evidence/<name>.json`. Takes ~16 minutes and reboots the device (case 5).
6. The harness restores all mutated device state on exit (`deviceidle`, simulated battery, and `device_idle_constants`) via an EXIT/INT/TERM trap. Verify with `adb shell settings get global device_idle_constants` → `null`.

## Outstanding verification

Recorded here so the ⏸️ markers above are not mistaken for oversight. This
document still carries `⏸️ Pending` rows, against a plan criterion
(`.omo/plans/convex-expo-revisit.md` todo 14, Success criterion 6) that requires
zero. They are deliberately *not* being converted to ❌: none of them failed.
They were never run, and recording an unrun check as a failure would be its own
false claim. Blocker 2 is now cleared; the criterion remains unmeetable until
Blocker 1 clears, and should be restated rather than satisfied on paper.

**Blocker 1 — no signed-in session.** Criteria 1, 2, 3, 4, 6, 7, 8, 9 and 10
need Clerk + Convex credentials on a device. Nine of the thirteen numbered
criteria are undischarged for this one reason.

**Blocker 2 — no device attached.** ✅ **Cleared 2026-07-30.** The Pixel 8 Pro
was attached and all seven cases were run against current `main` in a single
sweep, 7/7. Evidence: `.omo/evidence/issue-5-baseline-all-cases.json`. Note that
the notification-harness criteria are the only ones this cleared; the nine
criteria under Blocker 1 still need a signed-in session and remain ⏸️.

Two further gaps, neither device-dependent:

- The claim at criterion 9 that dead-lettered rows "can be requeued via debug
  UI" is not implemented: `syncQueue.deadLettered()` and `syncQueue.requeue()`
  have no call sites, and neither debug screen imports `syncQueue`.
- `.omo/evidence/task-14-*` — the log todo 14's acceptance requires — was never
  produced, so todo 14's own criteria have not been machine-checked.

## Next actions

1. Set up Clerk + Convex accounts and populate `apps/mobile/.env` if not already.
2. Deploy Convex backend (`npx convex dev` or `npx convex deploy`).
3. Work the remaining pending criteria, which all require a signed-in session (auth, RxNorm, sync, cross-device) and are orthogonal to the notification question Phase 1 answered.
4. ✅ Done 2026-07-30 — the harness was re-run with `--all` against current code and reports 7/7 in one invocation. Any acceptance wording still phrased as "6/6" should be restated as "7/7".
5. Re-run this baseline after the fused-timeline notification changes land (the Task Tier of ADR 0003), and diff against `.omo/evidence/issue-5-baseline-all-cases.json`. That comparison is the reason this baseline was taken.
