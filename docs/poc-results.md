# POC Results

This document tracks the 13 success criteria from the POC plan §3. Each row shows the criterion, the expected result, the actual result, and a pass/fail verdict.

**Status:** 2026-07-26, post-Phase 1 revisit. Code-level fixes committed on `main`; APK builds and installs cleanly on the emulator. Physical Android 17 device verification remains the authoritative test (see criteria #5, #11, #12) and is pending user action.

## Success criteria

| # | Criterion | Expected | Actual | Pass/Fail |
|---|---|---|---|---|
| 1 | Magic-link sign-in completes; user ID persists across app restarts | User can sign in and remains signed in after force-stop | Clerk `@clerk/clerk-expo` wired end-to-end incl. token cache; duplicate `@clerk/expo` package removed; no runtime device verification yet | ⏸️ Pending (device test) |
| 2 | User can search "metformin" → RxNorm returns matches → user selects one | RxNorm search returns relevant results | Convex Action `rxnorm.search` implemented and typed against the real NLM `approximateTerm.json` endpoint; UI debounces + guards stale responses | ⏸️ Pending (device test) |
| 3 | User can save medication with schedule ("daily at 09:00" or "every 8 hours") | Medication saved locally with correct schedule | SQLite repo + schedule picker implemented; 23 vitest cases on the schedule engine pass; no device verification yet | ⏸️ Pending (device test) |
| 4 | Medication persists in local SQLite across app restart | Medication list survives force-stop | `expo-sqlite` repo persists to device storage; migrations 001–003 apply cleanly on boot; no runtime restart test yet | ⏸️ Pending (device test) |
| 5 | Local notification fires at scheduled time on a physical Android device, app closed, not in foreground, surviving Doze + battery optimization | Notification fires within 1 minute of scheduled time | Now uses `AlarmType.SET_ALARM_CLOCK` — the OEM-resilient primitive the user has already empirically verified firing on a physical Android 17 device via the native MAUI build on `archive/dotnet-maui`. Notify-kit migration (from archived Notifee) fixes killed-app trigger failure (#1100), silent exact-alarm-denial drop, and DST shift (#875). Automated verification via `apps/mobile/scripts/notification-harness.sh` case 1 (killed) + case 3 (Doze) — see `.omo/evidence/task-12-*.log` for emulator smoke, physical-device run pending. | ⏸️ Pending (Todo 13 device run; framework in place) |
| 6 | Tapping notification opens the dose logging screen pre-filled with the right med + scheduled time | Correct medication and time shown | Tap handler + deep-link routing implemented (`src/notifications/tap-handler.ts`); notification data includes `medicationId` and `scheduledTime` for the dose-log deep link; no device verification yet | ⏸️ Pending (device test) |
| 7 | Marking dose "taken" writes a `dose_logs` row in local SQLite | Row exists with status `taken` | Dose log repo + UI implemented; 10 vitest cases on conflict resolution guarantee a locally `taken` dose is never overwritten by a remote pull; no device verification yet | ⏸️ Pending (device test) |
| 8 | When online, `dose_logs` rows sync to Convex (visible in Convex dashboard) | Row appears in Convex within 60s | Sync worker + Convex mutations implemented; requires deployed backend + device test | ⏸️ Pending (Day 7 / device test) |
| 9 | When offline (airplane mode), dose logs accumulate in `sync_queue`; when back online, they sync within 60s | Queue drains and rows appear in Convex | Sync queue + retry logic implemented; MAX_ATTEMPTS=5, exponential backoff to 30s cap. **Phase 1 fix**: dead-letter state (migration 003) ensures failed rows no longer retry forever; retained rows can be requeued via debug UI. Requires device test. | ⏸️ Pending (device test) |
| 10 | Same user logged in on Device B sees the medication + dose log within 10s of Device A's action | Realtime pull updates Device B | Realtime pull hooks implemented; requires two-device test | ⏸️ Pending (device test) |
| 11 | Adding 30 meds with daily schedules: rolling scheduler keeps notifications firing without dropping any | All 30 notifications fire in observation window | **Phase 1 fix**: scheduler now uses a **global 64-dose priority queue** across all active meds (`selectUpcomingDoses` in `@taskrx/shared`, 10 vitest cases), not 10 per med. Diff-based reschedule idempotent. Automated verification via harness case 6. Physical device run pending. | ⏸️ Pending (Todo 13 device run; framework in place) |
| 12 | EAS Build produces a sideloadable Android APK that installs and runs on a physical device | APK installs and app launches | ✅ **Verified on emulator**: `./gradlew assembleDebug` produces a 260MB debug APK; installs on API 36 emulator; Notifee/notify-kit native side initializes on process start (`.omo/evidence/task-12-convex-expo-revisit.log`). EAS build script unchanged. Physical device install still pending as part of Todo 13. | ✅ Emulator install verified; ⏸️ physical device pending |
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
| App closed, normal mode | ⏸️ Pending | Run `apps/mobile/scripts/notification-harness.sh --case 1` on physical device |
| Force-killed | ⏸️ Pending | Case 1 + `dumpsys alarm` inspection |
| Do Not Disturb | ⏸️ Pending | Manual — harness verifies delivery only |
| Low Power Mode | ⏸️ Pending | Case 3 (Doze approximates this) |
| Doze mode | ⏸️ Pending | Case 3 (`--case 3`) |
| Boot reschedule | ⏸️ Pending | Case 5 (`--case 5`) — SKIP_REBOOT=1 on physical if inconvenient |
| Exact-alarm denied fallback | ⏸️ Pending | Case 4 (`--case 4`) |
| `SET_ALARM_CLOCK` primitive in dumpsys | ⏸️ Pending | Case 2 (`--case 2`) |

### Day 8 — Stress tests

See `docs/poc-stress-tests.md`. Also superseded by the harness.

| Scenario | Result | Notes |
|---|---|---|
| 30-medication rolling horizon | ⏸️ Pending | Case 6 (`--case 6`) verifies pending_count in [30, 64] |
| Doze force-idle | ⏸️ Pending | Case 3 |
| Force-stop then next dose | ⏸️ Pending | Case 1 |
| Offline-first 3 meds + 5 doses | ⏸️ Pending | Not yet in harness — manual test |
| Restore network sync within 60s | ⏸️ Pending | Not yet in harness — manual test |

### Day 9 — Production-style build

| Check | Result | Notes |
|---|---|---|
| Local debug APK builds | ✅ Verified | `./gradlew assembleDebug` produces `app-debug.apk` (260MB) |
| APK installs on emulator | ✅ Verified | `emulator-5554` (Android 16, API 36) |
| APK installs on physical device | ⏸️ Pending | `adb install -r apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk` |
| EAS preview APK builds | ⏸️ Pending | `./apps/mobile/scripts/build-production-apk.sh` (requires EAS account) |

## Summary

- **Passed:** 2/13 (criterion 12 partial — emulator install verified; criterion 13 — code + docs complete)
- **Pending on user action:** 11/13 (require physical device + one-time DevLauncher tap; framework is in place)
- **Failed:** 0/13
- **Blockers:** None. The remaining gate is your physical Android 17 device run per Todo 13.

## Next actions

1. Set up Clerk + Convex accounts and populate `apps/mobile/.env` if not already.
2. Deploy Convex backend (`npx convex dev` or `npx convex deploy`).
3. Plug in the physical Android 17 device with USB debugging enabled and confirm `adb devices` shows it.
4. Install the debug APK: `adb -s <serial> install -r apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk` (or rebuild via `./gradlew assembleDebug`).
5. Start Metro: `cd apps/mobile && npx expo start --dev-client`. In the DevLauncher on the device, tap the discovered "TaskRx" server card once.
6. Run the harness: `apps/mobile/scripts/notification-harness.sh --all --output .omo/evidence/task-13-android17-device.json`.
7. Review the JSON report; each ⏸️ Pending row above becomes ✅ (pass) or ❌ (fail) with a real evidence artifact.
8. Update `docs/post-poc-decisions.md` and this document with the go/no-go verdict.
