# Post-POC Decisions

This document records the decisions to make after the POC device tests (Days 6, 8, and 9) are complete. It is updated on Day 10 with current recommendations based on autonomous code review; device-test evidence is still pending.

## Decision matrix

| # | Question | Options | Evidence needed | Recommendation |
|---|---|---|---|---|
| 1 | **Stack viable?** | YES / NO | All 13 success criteria in `docs/poc-results.md` | ✅ YES (autonomous code review confirms) — pending device verification |
| 2 | **Add iOS support?** | Now / v2 / Never | Day 6 notification reliability on Android; Apple Developer account availability | ⏸️ Defer until Android proven |
| 3 | **Add web app?** | Now / v2 / Never | Cross-device sync validation; user demand | ⏸️ Defer until mobile proven |
| 4 | **Add drug interactions?** | Now / v2 / Never | POC med-search performance; API availability | ⏸️ High value for v2 |
| 5 | **Add task manager?** | Now / v2 / Never | Scope capacity; user validation | ⏸️ High value for v2 |
| 6 | **Build full MVP (3–4 months)?** | YES / NO / Pivot | POC results + stakeholder sign-off | ⏸️ TBD after device tests |
| 7 | **Pivot?** | Continue / Partial pivot / Full pivot | If R1/R2/R3 from plan §9 fired | ✅ Not needed — continue current stack |

## Detailed notes

### 1. Stack viable?

- **Criteria:** React Native + Expo + Convex + Clerk + Notifee + expo-sqlite reliably delivers offline-first, on-time reminders and cross-device sync.
- **Evidence:**
  - Autonomous checks pass: `npm run typecheck`, `npm run lint`, `npm run -w packages/shared test` (23/23), Metro starts.
  - Code review confirms all critical paths are implemented: auth, RxNorm search, SQLite repos, schedule engine, Notifee scheduler, dose logging, sync worker, realtime pull hooks, EAS build scripts.
- **Recommendation:** ✅ **YES** — stack is viable from a code and static-verification standpoint. Final confirmation requires Day 6/8/9 device tests.

### 2. Add iOS support?

- **Estimate:** +1 week for parity (Notifee + Clerk + Convex all support iOS).
- **Main work:** iOS-specific notification permissions, App Store submission prep, Apple Developer account.
- **Recommendation:** ⏸️ **Defer until Android proven.** The POC is Android-only by design. Add iOS only after Pixel 8 tests validate the core notification reliability.

### 3. Add web app?

- **Estimate:** +2 weeks for a basic web client.
- **Main work:** Convex queries already work; need React web UI + responsive layout.
- **Recommendation:** ⏸️ **Defer until mobile proven.** Web fills a real gap (see research doc), but mobile MVP must come first.

### 4. Add drug interactions?

- **Estimate:** +2 weeks for a basic interaction checker.
- **Main work:** Integrate a drug-interaction API (e.g., NLM RxNav interaction API) and surface warnings.
- **Recommendation:** ⏸️ **High value for v2.** Strong differentiator vs TickTick and a natural extension of the RxNorm search already built.

### 5. Add task manager?

- **Estimate:** +3 weeks for TickTick-parity basic features.
- **Main work:** General tasks, recurring tasks, priorities, calendar view.
- **Recommendation:** ⏸️ **High value for v2.** Core to the combined vision, but out of scope for the medication-focused POC.

### 6. Build full MVP?

- **Estimate:** 3–4 months per research doc.
- **Prerequisites:** POC passes device tests, user validation, budget/team confirmed.
- **Recommendation:** ⏸️ **TBD after device tests.** Autonomous checks are green, but the go/no-go decision depends on physical-device notification reliability.

### 7. Pivot?

- **Trigger:** Any of the high-impact risks from plan §9 materialize (Clerk/Convex JWT broken, local notifications unreliable, exact-alarm permission unsolvable).
- **Current assessment:** No evidence of broken integration or unsolvable permission path. Notifee exact-alarm code is present and correctly configured in `AndroidManifest.xml` and scheduler.
- **Recommendation:** ✅ **Continue — no pivot needed.** Revisit only if Day 6/8 device tests fail catastrophically.

## Next steps

1. Complete Day 6 physical-device notification tests on Pixel 8.
2. Complete Day 8 stress tests on Pixel 8.
3. Complete Day 9 EAS preview build + sideload.
4. Update `docs/poc-results.md` with actual evidence.
5. Return to this document and convert provisional recommendations into final decisions.
6. Present findings to stakeholders for go/no-go on MVP.

---

*Updated on Day 10 (June 22, 2026). Device-test evidence pending.*
