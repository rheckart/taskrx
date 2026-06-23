# POC Results

This document tracks the 13 success criteria from the POC plan §3. Each row shows the criterion, the expected result, the actual result, and a pass/fail verdict.

**Status:** Day 10 — autonomous verification complete; device tests pending.

## Success criteria

| # | Criterion | Expected | Actual | Pass/Fail |
|---|---|---|---|---|
| 1 | Magic-link sign-in completes; user ID persists across app restarts | User can sign in and remains signed in after force-stop | Clerk auth code reviewed; `useAuth` + token cache implemented; no runtime verification yet | ⏸️ Pending (Day 6 user test) |
| 2 | User can search "metformin" → RxNorm returns matches → user selects one | RxNorm search returns relevant results | Convex Action `rxnorm.search` implemented and typed; no runtime API verification yet | ⏸️ Pending (Day 6 user test) |
| 3 | User can save medication with schedule ("daily at 09:00" or "every 8 hours") | Medication saved locally with correct schedule | SQLite repo + schedule picker implemented; unit tests pass; no device verification yet | ⏸️ Pending (Day 6 user test) |
| 4 | Medication persists in local SQLite across app restart | Medication list survives force-stop | `expo-sqlite` repo persists to device storage; no runtime restart test yet | ⏸️ Pending (Day 6 user test) |
| 5 | Local notification fires at scheduled time on a physical Android device (Pixel 8), app closed, not in foreground, surviving Doze + battery optimization | Notification fires within 1 minute of scheduled time | Notifee exact-alarm scheduler implemented; physical device test not yet run | ⏸️ Pending (Day 6 user test) |
| 6 | Tapping notification opens the dose logging screen pre-filled with the right med + scheduled time | Correct medication and time shown | Tap handler + deep-link routing implemented; no device verification yet | ⏸️ Pending (Day 6 user test) |
| 7 | Marking dose "taken" writes a `dose_logs` row in local SQLite | Row exists with status `taken` | Dose log repo + UI implemented; unit tests pass; no device verification yet | ⏸️ Pending (Day 6 user test) |
| 8 | When online, `dose_logs` rows sync to Convex (visible in Convex dashboard) | Row appears in Convex within 60s | Sync worker + Convex mutations implemented; requires deployed backend + device test | ⏸️ Pending (Day 7 / Day 8 user test) |
| 9 | When offline (airplane mode), dose logs accumulate in `sync_queue`; when back online, they sync within 60s | Queue drains and rows appear in Convex | Sync queue + retry logic implemented; requires device test | ⏸️ Pending (Day 8 user test) |
| 10 | Same user logged in on Device B sees the medication + dose log within 10s of Device A's action | Realtime pull updates Device B | Realtime pull hooks implemented; requires two-device test | ⏸️ Pending (Day 7 / Day 8 user test) |
| 11 | Adding 30 meds with daily schedules: rolling scheduler keeps notifications firing without dropping any | All 30 notifications fire in observation window | Rolling scheduler + stress seed implemented; requires device test | ⏸️ Pending (Day 8 user test) |
| 12 | EAS Build produces a sideloadable Android APK that installs and runs on the Pixel 8 | APK installs and app launches | `eas.json` + build scripts configured; no cloud build run yet | ⏸️ Pending (Day 9 user test) |
| 13 | Code is in git with clean, atomic commits; `README.md` documents local run + deploy | Conventional commits; README complete | ✅ Verified (autonomous) — 15 clean commits; README, CHANGELOG, architecture, and test docs complete |

## Evidence log

### Day 6 — Physical device notification tests

See `docs/poc-notification-tests.md`.

| Scenario | Result | Notes |
|---|---|---|
| App closed, normal mode | ⏸️ Pending | Run `docs/poc-notification-tests.md` script on Pixel 8 |
| App force-killed | ⏸️ Pending | Run `docs/poc-notification-tests.md` script on Pixel 8 |
| Do Not Disturb | ⏸️ Pending | Run `docs/poc-notification-tests.md` script on Pixel 8 |
| Low Power Mode | ⏸️ Pending | Run `docs/poc-notification-tests.md` script on Pixel 8 |
| Doze mode | ⏸️ Pending | Run `docs/poc-notification-tests.md` script on Pixel 8 |

### Day 8 — Stress tests

See `docs/poc-stress-tests.md`.

| Scenario | Result | Notes |
|---|---|---|
| 30 meds exact-alarm reliability | ⏸️ Pending | Run `docs/poc-stress-tests.md` script on Pixel 8 |
| Doze force-idle | ⏸️ Pending | Run `docs/poc-stress-tests.md` script on Pixel 8 |
| Add 31st med mid-test | ⏸️ Pending | Run `docs/poc-stress-tests.md` script on Pixel 8 |
| Force-stop then next dose | ⏸️ Pending | Run `docs/poc-stress-tests.md` script on Pixel 8 |
| Offline-first 3 meds + 5 doses | ⏸️ Pending | Run `docs/poc-stress-tests.md` script on Pixel 8 |
| Restore network sync within 60s | ⏸️ Pending | Run `docs/poc-stress-tests.md` script on Pixel 8 |

### Day 9 — Production-style build

| Check | Result | Notes |
|---|---|---|
| EAS preview APK builds | ⏸️ Pending | Run `./apps/mobile/scripts/build-production-apk.sh` |
| APK installs on Pixel 8 | ⏸️ Pending | Run `adb install taskrx-preview.apk` |
| End-to-end flow works on preview build | ⏸️ Pending | Complete auth → add med → notification → dose log flow |

## Autonomous verification

| Check | Command | Result |
|---|---|---|
| TypeScript strict | `npm run typecheck` | ✅ Pass |
| Lint | `npm run lint` | ✅ Pass |
| Unit tests | `npm run -w packages/shared test` | ✅ 23/23 pass |
| Metro dev server | `cd apps/mobile && npx expo start` | ✅ Reaches "Metro waiting" |
| Git history | `git log --oneline` | ✅ 15 clean conventional commits |

## Summary

- **Passed:** 1/13 (criterion 13 — code/docs completeness, autonomous)
- **Pending:** 12/13 (require device, backend, or runtime verification)
- **Failed:** 0/13
- **Blockers:** None recorded. The only open question is physical-device notification reliability.

## Next actions

1. Set up Clerk + Convex accounts and populate `.env`.
2. Deploy Convex backend (`npx convex dev` or `npx convex deploy`).
3. Run Day 6 physical-device tests and update evidence.
4. Run Day 8 stress tests and update evidence.
5. Run Day 9 EAS preview build and update evidence.
6. Mark each criterion as ✅ PASS or ❌ FAIL with supporting evidence.
7. Use this document to finalize `docs/post-poc-decisions.md`.
