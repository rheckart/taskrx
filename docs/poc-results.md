# POC Results

This document tracks the 13 success criteria from the POC plan §3. Each row shows the criterion, the expected result, the actual result, and a pass/fail verdict.

**Status:** 2026-07-30, Phase 1 gate **PASS**. The notification harness reports **7/7 on a physical Pixel 8 Pro (Android 17, API 37)** in a single end-to-end `--all` invocation (`exit 0`), taken against commit `a931bbf` with a clean working tree, closing criteria #5, #5a, #11 and #12. **Updated 2026-08-02:** the auth / RxNorm / sync / cross-device criteria that were waiting on a signed-in Clerk + Convex session were exercised during the issue #7 round trip — 7 pass, 4 are partial on a latency or variant qualifier, and only #6 (notification tap) and #9 (airplane mode) remain untouched.
**Updated 2026-08-03:** #6 and #9 are now **verified on the device and executable** as notification-harness cases 8 and 9 — **9 pass, 4 partial, 0 pending**. See "Criteria 6 and 9 on the device" below for what the run also found, including a product gap that would stop notifications reaching a real user on a fresh install.

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

> **Legacy deletion checked against the same baseline (2026-08-02, issue #8).**
> All seven cases pass again, on the same Pixel 8 Pro and the same OS build,
> and every verdict matches the baseline — case 4 `window=+33s726ms`, case 6
> `pending_count` 30 → 30, case 7 three alarms on Mon/Wed/Fri. Evidence:
> `.omo/evidence/issue-8-legacy-deletion-verification.json`, merged from three
> passes for the keyguard reason above. Case 5 needed a second attempt: the
> first reported UNREACHABLE because nobody unlocked the device inside the 300s
> window, which is Android withholding `ACTION_BOOT_COMPLETED` until first
> unlock, not a product failure. The same narrow reading applies as for #6 —
> the harness runs unauthenticated and never touches SQLite.
>
> The tree was **not** clean for this run: three sync files were modified (the
> `applyRemotePull` extraction and its two test files). No harness case loads
> them — every case drives `scheduleForReminder` with an in-memory reminder and
> the app runs signed-out — but the provenance block records it rather than
> claiming a clean checkout.

> **Round trip verified (2026-08-02, issue #7).** A medication created on the
> device reached Convex, survived `pm clear` — a genuine fresh install, which
> replayed migrations 001→010 and produced the unified schema *only* — and came
> back down after re-authenticating on the same account. Evidence:
> `.omo/evidence/issue-7-round-trip-verification.json`.
>
> The two assertions worth naming, because both are regressions this repo has
> actually shipped: the pulled reminder landed under its **original device
> UUID** rather than the Convex `_id`, and `reminder_instances.reminder_id`
> held the **local** parent UUID, not the Convex id (`eecee06`). The `completed`
> instance kept its status and `completed_at` across the trip, and `sync_queue`
> was empty afterwards — the pull writes `sync_status = 'synced'` without
> re-enqueuing a push.
>
> Three things this did **not** verify.
>
> **The safety rule under contention.** A completed local row surviving a pull
> is not the same as one refusing a newer remote row of a different status, and
> that case was never staged. Nor is it covered by tests as fully as it looks:
> `shouldApplyRemoteReminderInstance` is exercised as a *pure function* by
> `packages/shared/src/sync/conflict.test.ts`, and the pull pass is exercised by
> `apps/mobile/src/sync/pull.test.ts` with the repository **mocked**, so the
> guard's actual call site — inside `reminderInstancesRepo.upsertMany` — is run
> by nothing. The rule is proven of the decision and of the pass, never of the
> pair.
>
> **The rule has a server-side hole regardless.**
> `packages/convex/convex/reminderInstances.ts` `markMissed` and `markSkipped`
> patch `status` unconditionally — neither checks whether the stored document is
> already `completed`. The device that recorded the dose is protected by its own
> local guard, but a stale push from a second device flips the *server* copy,
> and any third install then pulls `missed` for a dose that was taken. Pre-existing
> and not introduced by the cutover, so "the rule survives untouched" holds
> literally, but the rule is narrower than its wording suggests.
>
> **Prompt delivery.** The push drains only on `syncWorker.start()` and on
> NetInfo connectivity changes — the `AppState` "active" handler reschedules
> notifications but does not drain — so both pushes here needed an app restart
> before they left the queue.

> **Criteria 6 and 9 run on the device (2026-08-03, issue #9).** Both pass, and
> both are now harness cases rather than prose, so the next person can re-run
> them: `--case 8` and `--case 9`. Evidence:
> `.omo/evidence/issue-9-case-8-notification-tap.json` and
> `.omo/evidence/issue-9-case-9-offline-drain.json`, taken against `8d9ca44`
> and a clean tree on the same Pixel 8 Pro.
>
> **Run them in separate invocations.** `--auth` runs both and case 9 then fails
> every time; see "The database locks after a notification fires" below. This is
> a real limit of the current instrument, recorded rather than papered over.
>
> Four things the run found that are not the criteria themselves. The first is
> a product defect and the most consequential thing in this document.
>
> **A fresh install never asks for POST_NOTIFICATIONS, so no notification is
> ever shown.** The alarm fires exactly on time, notify-kit runs, and the
> framework throws `SecurityException: POST_NOTIFICATIONS permission is not
> granted. Notification was not displayed.` Nothing in `dumpsys alarm` hints at
> it — the alarm looks perfect right up to the millisecond it fires. The only
> caller of `requestPermissions()` is `app/(app)/debug/notifications.tsx`, a
> debug screen; no normal flow requests it. This was observed on the real device
> here, where `pm clear` during the issue #7 round trip had revoked the grant:
> from that moment the app was arming alarms that Android silently discarded.
> **A user installing this app today gets no reminders and no error.** Not
> fixed — it is a product change beyond running these two criteria, and it
> wants its own issue. The harness now grants the permission at startup and
> records the state in every case detail, so no delivery case can quietly fail
> for this reason again (constraint 12).
>
> **Answering a dose leaves a duplicate pending instance.** `/dose/log` re-runs
> its load after the write, `findPendingForOccurrence` filters to `pending` and
> so does not see the row it just completed, and it mints a fresh one — every
> answered occurrence ends up with a `completed` row and a spurious `pending`
> row beside it, both pushed to Convex. Observed repeatedly here (`count=2` at a
> single pairing; three at one point). The screen's own comments anticipate this
> for *reopening* an answered occurrence, but it happens on the first answer,
> without reopening anything. Adherence counts from these rows, so this is not
> cosmetic. Not fixed. Harness case 9 works around it by asking for the
> `completed` row by name rather than the first row.
>
> **The database locks after a notification fires.** Once an alarm has posted, the
> app's SQLite writes fail with `Call to function 'NativeStatement.finalizeAsync'
> has been rejected. → Caused by: Error code : database is locked`, and it
> survives `am kill` and a verified-fresh process — so the lock outlives the
> process that fired the alarm. The app's own code opens exactly one connection
> (`getDb` memoises it) and uses a transaction only for migrations, so the second
> writer comes from outside that code; the suspect is the entry bundle being
> evaluated in a second JS context (notifee's headless task, and Fast Refresh,
> which produced the same symptom). This is stated as a suspicion, not a
> diagnosis — it was not run to ground. It is why cases 8 and 9 must be invoked
> separately, and it deserves its own investigation: if it can happen in
> production, a fired reminder would break dose logging until the app restarts.
>
> **`pm grant` restarts the app.** Granting POST_NOTIFICATIONS from inside a
> case killed the process mid-write and produced the lock above, which surfaced
> as "no reminder was seeded" with no cause. The grant now happens once at
> startup, before anything holds the database.

## Success criteria

| # | Criterion | Expected | Actual | Pass/Fail |
|---|---|---|---|---|
| 1 | Magic-link sign-in completes; user ID persists across app restarts | User can sign in and remains signed in after force-stop | **VERIFIED on Pixel 8 Pro (2026-08-02, incidental to issue #7).** Signed in end-to-end; the resulting Clerk subject `user_3FwrkTUn9wXOhD2fZ0w8veUdq7y` is stamped on every `reminders` / `reminderInstances` document the device pushed, so the identity reached the backend rather than only the UI. Persistence held across three `am force-stop` + relaunch cycles and a device reboot — the app returned to the signed-in home screen each time without re-authenticating. A second sign-in was also completed from scratch after `pm clear` wiped the token cache. **The first clause is not met, though:** both sign-ins used the emailed **passcode**, confirmed by the person who performed them — the sign-in screen offers a passcode and a magic link as separate buttons, and the magic-link path was never taken. So "magic-link sign-in completes" is untested; what is verified is that the email/OTP flow completes and the session survives restarts. The two paths diverge in the deep-link handling a magic link needs and a passcode does not, so this is a real gap rather than a wording quibble. | ⚠️ **PARTIAL** — passcode sign-in and persistence verified, magic link untested |
| 2 | User can search "metformin" → RxNorm returns matches → user selects one | RxNorm search returns relevant results | **VERIFIED on Pixel 8 Pro (2026-08-02, incidental to issue #7).** Typing `metformin` returned six live matches from the real NLM endpoint — four spellings at RxCUI 6809, plus `metformin Pill` (1161611) and `metformin Oral Tablet [Metforming]` (583194). Selecting one carried its RxCUI into the New Medication form, and `rxcui: "1161611"` then survived all the way to the Convex document. **Defect found, not fixed:** the result list keys rows by RxCUI, so the four 6809 rows trigger `Encountered two children with the same key`, and the LogBox overlay that warning raises blocked the first selection tap outright — the same overlay-blocks-navigation failure the harness header documents under constraint 5. | ✅ **PASS** (with a duplicate-key defect) |
| 3 | User can save medication with schedule ("daily at 09:00" or "every 8 hours") | Medication saved locally with correct schedule | **VERIFIED on Pixel 8 Pro for the daily variant (2026-08-02, incidental to issue #7).** Saved `metformin Pill` with dosage `500mg-roundtrip`, daily at 09:00. Read back out of the device's own SQLite: `schedule = {"times":["09:00"],"type":"daily"}` — the tagged-union shape, not the POC's untyped blob. **Only one of the two variants in the criterion was exercised:** the `interval` form ("every 8 hours") was never selected on-device, so it remains covered by the schedule-engine unit tests alone. | ⚠️ **PARTIAL** — daily verified, interval not exercised on device |
| 4 | Medication persists in local SQLite across app restart | Medication list survives force-stop | **VERIFIED on Pixel 8 Pro (2026-08-02, incidental to issue #7).** The saved medication survived repeated `am force-stop` + relaunch cycles and a full device reboot, still rendering in My Medications. Confirmed below the UI as well, by pulling `files/SQLite/taskrx.db` off the device and reading the row directly rather than trusting the screen. Migrations 001→010 (not just 001–003) apply on a fresh boot; see the round-trip note above for the `pm clear` run. | ✅ **PASS** |
| 5 | Local notification fires at scheduled time on a physical Android device, app closed, not in foreground, surviving Doze + battery optimization | Notification fires within 1 minute of scheduled time | **VERIFIED on Pixel 8 Pro / Android 17 / API 37.** Uses `AlarmType.SET_ALARM_CLOCK`. Delivered with the app process dead (`am kill`, 0 processes, `stopped=false`) across **three independent runs: 34 ms, 94 ms, and 31 ms late** — all far inside the 1-minute budget. Full chain in logcat: `Start proc ... for broadcast {NotificationAlarmReceiver}` → `NotifeeHeadlessJS: launched taskId: 1` → notification posted with correct title + `medicationId`. Primitive confirmed at OS level, not inferred: `window=0`, `exactAllowReason=permission`, `whenElapsed == maxWhenElapsed`, and the alarm registered as the device's *next alarm clock* with a timestamp exactly matching the scheduled one. The Doze clause is verified in criterion 5a below. **Re-verified 2026-07-30 against `a931bbf`** in a 7/7 single `--all` run — evidence `.omo/evidence/issue-5-baseline-all-cases.json`. Earlier evidence: `.omo/evidence/task-13-all-cases-final.json`, `.omo/evidence/task-13-SUMMARY.md`, `task-13-cases-1-4.json`, `task-13-cases-6-2.json` (all taken before the alarm-scoping fix; their `exact_windows` counts are inflated by `dumpsys` history — see "Case 5 was measuring the wrong thing"). | ✅ **PASS** |
| 5a | *(sub-criterion)* Notification survives **deep Doze** specifically | Fires while device is in deep IDLE | **VERIFIED on Pixel 8 Pro / Android 17.** With the app process killed and the device on simulated battery, it entered deep `IDLE` with the alarm pending and dozed continuously — observed cycle `IDLE → IDLE_MAINTENANCE → IDLE → IDLE_MAINTENANCE → QUICK_DOZE_DELAY`, never returning to `ACTIVE` before the alarm fired — and the notification still posted. `dumpsys alarm` shows Doze imposes **zero deferral**: `policyWhenElapsed: requester=+2m22s657ms … device_idle=-1m21s983ms` (device_idle resolves earlier than the request) with `whenElapsed == maxWhenElapsed`. **Finding:** Android refuses to enter deep Doze at all while a wake-from-idle alarm is due within `min_time_to_alarm` (default **1 hour**) — `force-idle` reports `Unable to go deep idle; stopped at QUICK_DOZE_DELAY`. That refusal is itself evidence the alarm is classified wake-from-idle. **Accommodation:** `min_time_to_alarm` was lowered to 30s for this case only and stock constants restored afterwards (verified `device_idle_constants = null`); no battery-optimization exemption was granted. Evidence: `.omo/evidence/task-13-case-3-deep-idle.json`, `.omo/evidence/task-13-all-cases-final.json`. | ✅ **PASS** |
| 6 | Tapping notification opens the dose logging screen pre-filled with the right med + scheduled time | Correct medication and time shown | **VERIFIED on Pixel 8 Pro (2026-08-03), now executable as harness case 8.** A real reminder was seeded, `rescheduleAll` armed it from SQLite, the notification posted, and the case found the row in the shade and tapped its coordinates — there is no adb command that invokes a notification's content intent, so this is a real tap. The dose screen opened showing the medication name, its dosage, and `Scheduled: … 3:39 PM` (device tz America/New_York) matching the armed millisecond, and it minted its instance at **exactly** that millisecond (`instance_at=627d30ae… count=1` at `1785785940000`; a row one second off does not match). The dosage assertion is the one that rules out a screen echoing the deep link's parameters — the link carries no dosage, so it can only have come from the row. Evidence: `.omo/evidence/issue-9-case-8-notification-tap.json`. | ✅ **PASS** |
| 7 | Marking dose "taken" writes a `dose_logs` row in local SQLite | Row exists with status `taken` | **VERIFIED on Pixel 8 Pro (2026-08-02, issue #7).** The criterion's vocabulary is legacy on both nouns: `dose_logs` was dropped in migration 009 and `taken` was renamed `completed` in ADR 0001, so what was checked is a `reminder_instances` row with `status = 'completed'`, read out of the device's own SQLite. It also carried `completed_at` and `reminder_kind = 'medication'`. Conflict resolution is still test-only — see the round-trip note above on what contention was *not* exercised. Evidence: `.omo/evidence/issue-7-round-trip-verification.json`. | ✅ **PASS** |
| 8 | When online, `dose_logs` rows sync to Convex (visible in Convex dashboard) | Row appears in Convex within 60s | **VERIFIED against the local deployment (2026-08-02, issue #7).** Two `reminderInstances` documents reached Convex through the unified `api.reminderInstances.create`, each with its parent resolved from the local UUID to the parent's Convex id. **But not within 60s, so the criterion is not met as written:** the worker drains on `syncWorker.start()` and on NetInfo changes only, and the `AppState` "active" handler does not drain, so the push left the queue only after an app restart. Routing is verified; unprompted timeliness is not. Evidence: `.omo/evidence/issue-7-round-trip-verification.json`. | ⚠️ **PARTIAL** — routes correctly, no 60s guarantee |
| 9 | When offline (airplane mode), dose logs accumulate in `sync_queue`; when back online, they sync within 60s | Queue drains and rows appear in Convex | **VERIFIED on Pixel 8 Pro (2026-08-03), now executable as harness case 9.** With airplane mode on and `Active default network: none` confirmed from `dumpsys connectivity`, a dose was answered through the real screen: the instance was written `completed` in SQLite, three entries accumulated in `sync_queue`, and the row was verified **absent from Convex** — the premise, without which the reconnect proves nothing. Airplane mode off: the row reached Convex **4s after the toggle** (0s after the device regained a default network, within one 3s poll), and the queue drained to 0 with nothing dead-lettered. Arrival is read from the deployment with `npx convex data`, keyed on `localId bb0e588a…`, **not** from the app's own `sync_status` — that column is written by the code path under test. Budget 60s. Evidence: `.omo/evidence/issue-9-case-9-offline-drain.json`. **Note on the claim in the old text:** dead-lettered rows still cannot be "requeued via debug UI" — `syncQueue.requeue()` has no call sites, as recorded under Outstanding verification. | ✅ **PASS** |
| 10 | Same user logged in on Device B sees the medication + dose log within 10s of Device A's action | Realtime pull updates Device B | **VERIFIED as two installs, not two devices (2026-08-02, issue #7).** Only one Pixel was available, so "Device B" was the same handset after `pm clear` and re-authentication — the substitution issue #7 explicitly allows. The medication and both instances came down. **But not within 10s, so the criterion is not met as written:** the meds list reads SQLite once on mount, so the pulled rows stayed invisible until the screen was remounted — the data arrives, the screen does not notice. Arrival is verified; latency to the eye is not. Evidence: `.omo/evidence/issue-7-round-trip-verification.json`. | ⚠️ **PARTIAL** — arrives, but the screen needs a remount |
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
| Offline-first 3 meds + 5 doses | ⏸️ Pending | Harness case 9 covers the shape with **one** med and one dose, verified offline in SQLite and in `sync_queue`. The multiplicity is what is still unexercised |
| Restore network sync within 60s | ✅ Pass | Case 9 — reached Convex 4s after the airplane-mode toggle, verified server-side by `localId`, queue drained to 0 |

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

The counts below were rewritten on 2026-08-02. The nine "pending" criteria were
pending on one thing — a signed-in Clerk + Convex session on real hardware —
and the issue #7 round trip established exactly that, so seven of them were
answered as a side effect of verifying the sync cutover rather than by a
campaign aimed at them.

- **Passed: 9/13** — #6 (a tapped notification opens a pre-filled dose screen)
  and #9 (offline accumulation, then drain within 60s) joined the list on
  2026-08-03; both are machine-checked and re-runnable. The seven before them:
  #2 (RxNorm returns live matches, selection carries the
  RxCUI to the backend), #4 (medication survives force-stop and reboot,
  confirmed in the device's own SQLite), #5 (killed-app exact delivery, 31-94 ms across 3 runs,
  plus sub-criterion #5a deep-Doze survival), #7 (an answered Occurrence writes
  a `reminder_instances` row), #11 (30-med horizon cap, no drops), #12 (APK
  builds + installs + runs on hardware), #13 (git + docs). The
  graceful-degradation contract (harness case 4) and the `SET_ALARM_CLOCK`
  primitive proven at OS level (harness case 2) are supporting evidence for #5,
  not separate criteria.
- **Partial: 4/13** — each one arrives but misses a qualifier in its own
  wording, so none is claimed as a pass: #1 (passcode sign-in and session
  persistence are verified, but the magic-link path the criterion names was
  never taken, and it exercises deep-link handling a passcode does not),
  #3 (the `daily` schedule is verified
  on-device, the `interval` variant never was), #8 (rows reach Convex, but not
  "within 60s" — the worker drains only on sign-in and on NetInfo changes),
  #10 (rows reach a second install, but not "within 10s" — the list reads
  SQLite once on mount and does not notice a pull landing afterwards).
- **Pending: 0/13** — #6 and #9 were the last two, and both were run on the
  device on 2026-08-03 as harness cases 8 and 9. Unlike the seven criteria the
  issue #7 round trip answered by hand, these two are now **re-runnable**:
  `notification-harness.sh --case 8` and `--case 9`.
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

Cases 8 and 9 are **not** part of that `--all` baseline and are not counted in
it. They need a signed-in app and a reachable Convex deployment, and they report
themselves unevaluated without one — folding them into `--all` would make every
baseline run on a signed-out device incomplete. Measured separately on
2026-08-03 against `8d9ca44`, clean tree, same device:

| # | Case | Verdict | Measured | Detail |
|---|------|---------|----------|--------|
| 8 | notification_tap | ✅ PASS | 2026-08-03 | tapped the posted notification; dose screen showed name, dosage and `3:39 PM`, and minted its instance at exactly `1785785940000` |
| 9 | offline_queue_drain | ✅ PASS | 2026-08-03 | answered offline with no default network, 3 queued, absent from Convex; arrived 4s after the toggle, queue drained to 0 |

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
5a. For criteria 6 and 9, additionally: sign in on the device, start the Convex
   deployment it points at (`npx convex dev` from `packages/convex` for a local
   one), and `adb reverse tcp:3210 tcp:3210`. Then run the two cases in
   **separate** invocations — `--case 8`, then `--case 9` — for the reason under
   "The database locks after a notification fires". `--auth` runs both in one go
   and case 9 will report SETUP FAILED.
6. The harness restores all mutated device state on exit (`deviceidle`, simulated battery, and `device_idle_constants`) via an EXIT/INT/TERM trap. Verify with `adb shell settings get global device_idle_constants` → `null`.

## Outstanding verification

Recorded here so the ⏸️ markers above are not mistaken for oversight.

**The thirteen numbered criteria now carry zero `⏸️ Pending` rows**, which is
what the plan criterion (`.omo/plans/convex-expo-revisit.md` todo 14, Success
criterion 6) asks for. Four remain ⚠️ **PARTIAL**, and those are not pending —
each was run and missed a qualifier in its own wording, which is a measurement,
not an omission. The ⏸️ markers that remain in this document are all in the
auxiliary tables below (DND, battery-saver, EAS cloud build), not in the
criteria.

**Blocker 1 — no signed-in session.** ✅ **Cleared 2026-08-02/03.** Criteria 1,
2, 3, 4, 7, 8 and 10 were exercised during the issue #7 round trip; 6 and 9
followed on 2026-08-03 as harness cases 8 and 9. Nothing is now waiting on
credentials.

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
