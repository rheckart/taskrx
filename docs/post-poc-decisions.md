# Post-POC Decisions

This document records the decisions to make after the POC device tests (Days 6, 8, and 9) are complete. It is a template; fill in the **Recommendation** column with evidence from `docs/poc-results.md`.

## Decision matrix

| # | Question | Options | Evidence needed | Recommendation |
|---|---|---|---|---|
| 1 | **Stack viable?** | YES / NO | All 13 success criteria in `docs/poc-results.md` | TBD |
| 2 | **Add iOS support?** | Now / v2 / Never | Day 6 notification reliability on Android; Apple Developer account availability | TBD |
| 3 | **Add web app?** | Now / v2 / Never | Cross-device sync validation; user demand | TBD |
| 4 | **Add drug interactions?** | Now / v2 / Never | POC med-search performance; API availability | TBD |
| 5 | **Add task manager?** | Now / v2 / Never | Scope capacity; user validation | TBD |
| 6 | **Build full MVP (3–4 months)?** | YES / NO / Pivot | POC results + stakeholder sign-off | TBD |
| 7 | **Pivot?** | Continue / Partial pivot / Full pivot | If R1/R2/R3 from plan §9 fired | TBD |

## Detailed notes

### 1. Stack viable?

- **Criteria:** React Native + Expo + Convex + Clerk + Notifee + expo-sqlite reliably delivers offline-first, on-time reminders and cross-device sync.
- **Evidence:** `docs/poc-results.md` §3 success criteria.
- **Options:**
  - YES — proceed to MVP.
  - NO — document blockers and decide whether to pivot.

### 2. Add iOS support?

- **Estimate:** +1 week for parity (Notifee + Clerk + Convex all support iOS).
- **Main work:** iOS-specific notification permissions, App Store submission prep.
- **Options:**
  - Add now — requires Apple Developer account.
  - Defer to v2 — focus on Android MVP first.
  - Skip — stay Android-only.

### 3. Add web app?

- **Estimate:** +2 weeks for a basic web client.
- **Main work:** Convex queries already work; need React web UI + responsive layout.
- **Options:**
  - Add now — fills MediSafe's biggest gap per research.
  - Defer to v2 — mobile-first until product-market fit.

### 4. Add drug interactions?

- **Estimate:** +2 weeks for a basic interaction checker.
- **Main work:** Integrate a drug-interaction API (e.g., NLM RxNav interaction API) and surface warnings.
- **Options:**
  - Add now — strong differentiator vs TickTick.
  - Defer to v2 — core reminder flow is higher priority.

### 5. Add task manager?

- **Estimate:** +3 weeks for TickTick-parity basic features.
- **Main work:** General tasks, recurring tasks, priorities, calendar view.
- **Options:**
  - Add now — builds the combined task + med vision.
  - Defer to v2 — keep POC scope tight.

### 6. Build full MVP?

- **Estimate:** 3–4 months per research doc.
- **Prerequisites:** POC passes, user validation, budget/team confirmed.
- **Options:**
  - YES — greenlight MVP.
  - NO — run more experiments first.
  - Pivot — change direction based on POC findings.

### 7. Pivot?

- **Trigger:** Any of the high-impact risks from plan §9 materialize (Clerk/Convex JWT broken, local notifications unreliable, exact-alarm permission unsolvable).
- **Options:**
  - Continue — no pivot needed.
  - Partial pivot — swap one layer (e.g., Expo Notifications instead of Notifee, Supabase instead of Convex).
  - Full pivot — change product or platform direction.

## Next steps

1. Complete Day 6 physical-device notification tests.
2. Complete Day 8 stress tests.
3. Fill in `docs/poc-results.md` with actual evidence.
4. Return to this document and record final recommendations.
5. Present findings to stakeholders for go/no-go on MVP.
