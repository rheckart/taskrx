# POC Results

This document tracks the 13 success criteria from the POC plan §3. Each row shows the criterion, the expected result, the actual result, and a pass/fail verdict.

**Status:** Template — fill in actual results after Days 6, 8, and 9 device tests.

## Success criteria

| # | Criterion | Expected | Actual | Pass/Fail |
|---|---|---|---|---|
| 1 | Magic-link sign-in completes; user ID persists across app restarts | User can sign in and remains signed in after force-stop | TBD | TBD |
| 2 | User can search "metformin" → RxNorm returns matches → user selects one | RxNorm search returns relevant results | TBD | TBD |
| 3 | User can save medication with schedule ("daily at 09:00" or "every 8 hours") | Medication saved locally with correct schedule | TBD | TBD |
| 4 | Medication persists in local SQLite across app restart | Medication list survives force-stop | TBD | TBD |
| 5 | Local notification fires at scheduled time on a physical Android device (Pixel 8), app closed, not in foreground, surviving Doze + battery optimization | Notification fires within 1 minute of scheduled time | TBD | TBD |
| 6 | Tapping notification opens the dose logging screen pre-filled with the right med + scheduled time | Correct medication and time shown | TBD | TBD |
| 7 | Marking dose "taken" writes a `dose_logs` row in local SQLite | Row exists with status `taken` | TBD | TBD |
| 8 | When online, `dose_logs` rows sync to Convex (visible in Convex dashboard) | Row appears in Convex within 60s | TBD | TBD |
| 9 | When offline (airplane mode), dose logs accumulate in `sync_queue`; when back online, they sync within 60s | Queue drains and rows appear in Convex | TBD | TBD |
| 10 | Same user logged in on Device B sees the medication + dose log within 10s of Device A's action | Realtime pull updates Device B | TBD | TBD |
| 11 | Adding 30 meds with daily schedules: rolling scheduler keeps notifications firing without dropping any | All 30 notifications fire in observation window | TBD | TBD |
| 12 | EAS Build produces a sideloadable Android APK that installs and runs on the Pixel 8 | APK installs and app launches | TBD | TBD |
| 13 | Code is in git with clean, atomic commits; `README.md` documents local run + deploy | Conventional commits; README complete | TBD | TBD |

## Evidence log

### Day 6 — Physical device notification tests

See `docs/poc-notification-tests.md`.

| Scenario | Result | Notes |
|---|---|---|
| App closed, normal mode | TBD | |
| App force-killed | TBD | |
| Do Not Disturb | TBD | |
| Low Power Mode | TBD | |
| Doze mode | TBD | |

### Day 8 — Stress tests

See `docs/poc-stress-tests.md`.

| Scenario | Result | Notes |
|---|---|---|
| 30 meds exact-alarm reliability | TBD | |
| Doze force-idle | TBD | |
| Add 31st med mid-test | TBD | |
| Force-stop then next dose | TBD | |
| Offline-first 3 meds + 5 doses | TBD | |
| Restore network sync within 60s | TBD | |

### Day 9 — Production-style build

| Check | Result | Notes |
|---|---|---|
| EAS preview APK builds | TBD | |
| APK installs on Pixel 8 | TBD | |
| End-to-end flow works on preview build | TBD | |

## Summary

- **Passed:** 0/13 (template)
- **Failed:** 0/13 (template)
- **Blockers:** None recorded yet.

## Next actions

1. Run Day 6 physical-device tests and update evidence.
2. Run Day 8 stress tests and update evidence.
3. Run Day 9 EAS preview build and update evidence.
4. Mark each criterion as PASS or FAIL with supporting evidence.
5. Use this document to drive `docs/post-poc-decisions.md`.
