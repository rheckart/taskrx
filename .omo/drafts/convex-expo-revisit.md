---
slug: convex-expo-revisit
status: awaiting-approval
intent: clear
pending-action: write .omo/plans/convex-expo-revisit.md
approach: Return TaskRx to the original Convex + Expo + React stack. Restore the deleted POC tree from git, preserve the uncommitted .NET work on an archive branch, then (scope TBD by user) either device-verify the POC or port the richer .NET unified med+task domain onto Convex + Expo.
---

# Draft: convex-expo-revisit

## Components (topology ledger)
<!-- id | outcome (one line) | status | evidence path -->
| id | outcome | status | evidence |
|---|---|---|---|
| git-recovery | Uncommitted .NET work preserved on a branch; Expo POC tree restored on main; nothing lost | active | `git log`, `git status` clean |
| notif-layer | Android exact-alarm reminders fire on a killed/Doze Pixel 8, on a maintained library | active | device test log |
| offline-sync | Local SQLite source of truth + outbound queue reconciles with Convex | active | airplane-mode test |
| domain-port | Unified med+task Reminder domain (weekly, NL parser, refill, adherence) available in TS | pending user scope | vitest run |
| app-shell | Expo Router app with the 5 screens (Today/Schedule/Library/Stats/Settings) | pending user scope | screenshots |

## Open assumptions (announced defaults)
| assumption | adopted default | rationale | reversible? |
|---|---|---|---|
| Offline sync mechanism | Keep the POC's hand-rolled SQLite + `sync_queue` + backoff worker. Do NOT adopt PowerSync. | Convex's own Object Sync Engine is still unreleased (IndexedDB first, SQLite "in the future"). PowerSync's Convex connector shipped 2026-06-10 as **Experimental** with "we can't yet guarantee continued support"; it also moves the read path out of Convex query functions into Sync Streams, meaning authorization has to be re-expressed. The POC's worker already works. | Yes — swappable later |
| Auth provider | Keep Clerk (`@clerk/clerk-expo`), already wired end-to-end incl. Convex JWT verification | Convex Auth now supports React Native and would remove a vendor, but Clerk is already working in the POC and the .NET server was also being pointed at Clerk. Not worth churn now. | Yes |
| Backend of record | Convex (per the user's explicit direction) | User said "revisit using convex and expo with react" | No — this is the premise |
| Repo shape | npm workspaces: `apps/mobile`, `packages/convex`, `packages/shared` (POC layout) | Already proven; matches restored tree | Yes |

## Findings (cited)

### F1 — Repo is in a dangerous, split-brain state (highest priority)
- HEAD is still the **Expo POC**: `cfec46e chore(shared): migrate to Bundler module resolution`, Fri Jun 26 2026. Tag `poc-v0.1.0` exists. Only branch: `main` (+ `origin/main`).
- Working tree has **106 uncommitted changes**: 90 deletions (the entire Expo POC), 2 modifications (`README.md`, `.gitignore`), 14 untracked additions (all the .NET work: `src/`, `apps/TaskRx.*`, `TaskRx.slnx`, `global.json`, `AGENTS.md`, `.claude/`, `.pi/`, `docs/agents/`, the research doc).
- **The entire .NET MAUI + ASP.NET Core effort has never been committed.** A stray `git checkout .` or `git stash` destroys ~6,700 LOC. `.omo/plans/poc-plan.md` is also deleted-uncommitted.

### F2 — The .NET domain layer is genuinely richer than the POC's
`src/TaskRx.Core/` is only ~1,485 LOC of pure, dependency-free logic with **57 passing tests**, and it implements the research doc's central insight that the POC never did:
- Unified `Reminder` with `ReminderKind { Medication, Task }` discriminator — `src/TaskRx.Core/Reminder.cs:5-24`, `src/TaskRx.Core/Enums.cs:5-6`; one `reminders` table with a `kind` column — `src/TaskRx.Data/Migrations/M0001_InitialSchema.cs:10-27`.
- **Weekly schedules**: `DailyTimes | IntervalTimes | WeeklyTimes` — `src/TaskRx.Core/ScheduleTimes.cs:3-11`; engine walks up to 56 days for weekly — `src/TaskRx.Core/ScheduleEngine.cs:158-190`.
- **Natural-language reminder parser** (~950 LOC, rule/regex/lexicon based, no LLM): dosage units, priority words, `every N hours/days`, named times (morning/bedtime/dinner), `twice`, explicit `8:30pm`, weekday names, `with food`/`before meals`, `N pills`, `N per dose`, `refill in N days`; unknown tokens returned in `UnparsedTokens` rather than failing — `src/TaskRx.Core/ReminderParser.cs:8-37,175-183`, `ReminderParserPatterns.cs:20-251`, `ReminderParserLexicon.cs:5-52`.
- `RefillPlanner` (threshold = lookahead doses × 3) — `src/TaskRx.Core/RefillPlanner.cs:27-33`.
- `AdherenceCalculator` (% + daily grouping; note: `Snoozed` is silently excluded) — `src/TaskRx.Core/AdherenceCalculator.cs:5-30`.
- Caregiver model with per-kind notify flags and device tokens — `src/TaskRx.Core/Caregiver.cs`.

By contrast the POC's shared engine is **daily + interval only, medication only** — `poc-v0.1.0:packages/shared/src/index.ts:14-23`, `packages/shared/src/schedule/engine.ts:121-166` (23 tests, 100% coverage thresholds).

### F3 — The *live* .NET MAUI app has no reminders at all
This is the decisive fact. There are **two** MAUI projects and `TaskRx.slnx` includes only the newer one.
- `TaskRx.slnx:1-14` references `apps/TaskRx.Maui` — **not** `src/TaskRx.Maui`.
- `apps/TaskRx.Maui` (live): 5 pages + ViewModels + `ReminderApiClient` over HTTP to `http://127.0.0.1:5226`. **No notification code, no AlarmManager, no BroadcastReceiver, no SQLite, no offline queue, no SignalR client.** Manifest declares only `INTERNET` + `ACCESS_NETWORK_STATE` — `apps/TaskRx.Maui/Platforms/Android/AndroidManifest.xml:1-6`; packages are only MauiControls/CommunityToolkit.Mvvm/Logging.Debug/Http — `apps/TaskRx.Maui/TaskRx.Maui.csproj:34-39`.
- `src/TaskRx.Maui` (orphaned, not in solution) is where the notification pipeline lives: `AlarmManager.SetAlarmClock` for up to 64 doses per active med — `src/TaskRx.Maui/Platforms/Android/Notifications/AndroidNotificationScheduler.cs:55-63,81-97`; `MedicationAlarmReceiver` posts the notification — `:49-65`; `BootRescheduleReceiver` handles boot/`MY_PACKAGE_REPLACED` with a deferred-reschedule Preferences flag — `BootRescheduleReceiver.cs:9-16,39-57`; manifest has `POST_NOTIFICATIONS`/`SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`/`RECEIVE_BOOT_COMPLETED`/`WAKE_LOCK` — `src/TaskRx.Maui/Platforms/Android/AndroidManifest.xml:3-9`; local store is `sqlite-net-pcl` with a `MedicationEntity` only.
- **So README.md's "MAUI notifications pipeline verified on real Pixel 8 Pro" refers to an orphaned project that is not built by the solution.** The shipped .NET client cannot remind anyone of anything, and cannot work offline. For a medication reminder app that is the whole product.

### F4 — .NET backend: solid, but partly hollow
- Builds clean (.NET 10.0.300, 0 errors); **87 tests pass** — 57 Core + 30 Server (Testcontainers Postgres `18-alpine3.24`); `src/TaskRx.Data.Tests` contains **zero tests** — `src/TaskRx.Data.Tests/TaskRx.Data.Tests.csproj:1-21`.
- **README is stale**: claims EF Core + `dotnet ef database update` + "33 tests". Reality is **Dapper + FluentMigrator + Npgsql** — `src/TaskRx.Data/TaskRx.Data.csproj:10-17`, `DependencyInjection.cs:47-58`, migration version `20260704181626`.
- Aspire AppHost provisions Postgres 18 + pgAdmin:5050 — `apps/TaskRx.AppHost/AppHost.cs:3-13`.
- Auth: Clerk JWT bearer is wired but **`Auth:DevBypass` defaults to `IsDevelopment()`**, so the default identity is a synthetic `"demo"` — `apps/TaskRx.Server/Program.cs:23-45,80-92`.
- **`RefillTaskGenerator` hardcodes `ListByUserAsync("demo", ...)`** — single-user only — `apps/TaskRx.Server/Cron/RefillTaskGenerator.cs:49-54`.
- **SignalR is dead code**: `RemindersHub` declares `ReceiveInstanceUpdated`/`ReceiveReminderUpdated`/`ReceiveMissedDose` and groups by user, but nothing in the codebase ever resolves `IHubContext<RemindersHub>` to publish — `Realtime/RemindersHub.cs:6-25`, `Program.cs:74`.
- FCM is real Firebase Admin SDK with a log-only fallback when credentials are absent — `Fcm/FcmSender.cs:20-25,87-115`.
- No endpoints for tags, caregivers, notification log, or adherence stats despite repositories existing — `Program.cs:68-74`.
- `MissedDoseDetector`: 1-min poll, 30-min medication / 2-hr task grace, FCM only for medication — `Cron/MissedDoseDetector.cs:6-8,50-58,76-107`.

### F5 — The POC is better than its own docs suggest, with 3 real defects
Restored-from-git inventory (`poc-v0.1.0`): `apps/mobile` ~4,900 LOC, `packages/convex` ~900, `packages/shared` ~470.
Real assets:
- Convex `medications`/`doseLogs` with **`localId` idempotency** — the local-UUID↔Convex-`_id` bridge, which is the genuinely fiddly part: `poc-v0.1.0:packages/convex/convex/schema.ts:13-20`, `medications.ts:86-115`, `apps/mobile/src/sync/worker.ts:314-346`.
- Real Clerk JWT verification via Convex provider config + `ctx.auth.getUserIdentity()` — `packages/convex/convex/auth.config.ts:20-26`, `auth.ts:12-20`; every function calls `getCurrentUserId` first.
- Real NLM RxNav calls (`approximateTerm.json`, 5s timeout, 10 results) + 300ms-debounced search with stale-response sequence guard — `convex/rxnorm.ts:109-153`, `app/(app)/meds/search.tsx:18-89`.
- Sync worker: NetInfo-driven, single-flighted, exponential backoff 1s→30s cap, MAX_ATTEMPTS 5 — `sync/worker.ts:26-28,104-158,375-381`.
- Conflict rule that matters: a locally `taken` dose is never overwritten by remote — `db/repos/doseLogs.ts:380-389`.
Defects to fix on restore:
1. `checkExactAlarmPermission()` **hardcodes `Promise.resolve(true)`** on Android — the app cannot tell that exact alarms are denied — `src/notifications/permissions.ts:24-35`.
2. Scheduler horizon is **10 doses per med**, not 64, and `rescheduleAll` cancels *everything* then re-adds on every foreground — `src/notifications/scheduler.ts:13-16,123-137`. (The orphaned .NET scheduler used 64 — `AndroidNotificationScheduler.cs:89`.)
3. Sync queue **never dead-letters**: after 5 attempts the source row is marked `error` but `pending()` still returns the row forever — `sync/worker.ts:146-153`, `db/repos/syncQueue.ts:80-111`.
Also: `conflict.ts` helpers (`mergeMeds`/`mergeDoseLogs`/`resolveConflict`) have **no call sites** — the repos reimplement the rules inline.
Versions at HEAD: Expo `~56.0.12`, RN `0.85.3`, React `19.2.3`, Convex `^1.41.0`, `@clerk/clerk-expo ^2.19.31` (plus a stray second `@clerk/expo ^3.5.3`), Notifee `^9.1.8`, expo-sqlite `^56.0.5`.

### F6 — Notifee is effectively unmaintained, and its known bugs are exactly the POC's unverified risk
- Invertase's Notifee has stopped receiving updates; an actively maintained fork **`react-native-notify-kit`** exists (github.com/invertase/notifee#1266, Oct 2025 onward). Its fixed-bug list includes, verbatim:
  - *"Trigger notifications not firing on Android 14-15 when app is killed (#1100)"*
  - *"`SCHEDULE_EXACT_ALARM` denial silently dropping alarms with no fallback (#1100)"*
  - *"`getInitialNotification()` returning null on cold start (#1128)"*
  - plus New Architecture/TurboModules migration, Kotlin Android bridge, and **AlarmManager as the default trigger mechanism instead of WorkManager**.
- **POC success criterion #5 was "notification fires on a physical Pixel 8, app closed, surviving Doze"** — `docs/poc-results.md:15` — and it was never run. That is precisely bug #1100. The POC would likely have *failed* that test on Notifee 9.1.8.
- API is drop-in compatible (default export still `notifee`); migration is a dependency swap.
- `expo-notifications` is the other option but per the same thread lacks `alarmManager.allowWhileIdle`, `openAlarmPermissionSettings()`, `openBatteryOptimizationSettings()`, and full-screen intents — all things a medication reminder wants.

### F7 — `USE_EXACT_ALARM` is a Play Store review risk
Google restricts `USE_EXACT_ALARM` (the auto-granted one) to apps whose **core function is an alarm clock or calendar**. `SCHEDULE_EXACT_ALARM` is **denied by default on Android 14+** for everything else and silently degrades to an inexact alarm. Both POC and orphaned-.NET manifests declare **both** permissions — `poc-v0.1.0:apps/mobile/plugins/withNotifeeAndroidPermissions.js:11-35`, `src/TaskRx.Maui/Platforms/Android/AndroidManifest.xml:3-9`. A medication reminder is a defensible "alarm" claim but is not automatically approved; needs an in-app fallback path (`openAlarmPermissionSettings()`) regardless.

### F8 — Convex offline story, as of today
- Convex's own **Object Sync Engine is not shipped**. The design post states the first release targets **IndexedDB**, with SQLite "probably" later; convex.dev/sync still answers "Convex doesn't currently provide a full offline sync mechanism… The team is actively exploring this space."
- **PowerSync added experimental Convex support 2026-06-10.** PowerSync's React Native SDK is GA, but the **Convex connector is Experimental** ("APIs and behavior may still change, and we can't yet guarantee continued support"). Adopting it moves reads from Convex query functions to Sync Streams, so authorization must be re-expressed, and Convex object fields arrive as JSON text. PowerSync also has a **.NET SDK (Beta)**, which is the road not taken.
- Convex Auth now supports React Native (alternative to Clerk).

### F9 — Librarian corroboration + corrections (cited, 2026-07-25)
- **Notifee: officially archived by Invertase on 2026-04-07**, read-only, last release 9.1.8 (2024-12-20 — confirmed by `npm view` `time.modified`). The archived README itself **recommends `react-native-notify-kit`** as the community-maintained drop-in, alongside `expo-notifications`. (github.com/invertase/notifee)
- **`react-native-notify-kit` v10.5.0**: peers `expo:*`, `react-native >=0.73.0`; **development target RN 0.85.3** — identical to the POC's pinned RN. New Architecture / TurboModules **only**; Expo Go unsupported (CNG/prebuild dev builds only); Apache-2.0; toolchain Node 22+ / JDK 17 or 21 (local: Node v26.4.0, JDK 17.0.19 ✓). AlarmManager is the **default** trigger backend (was WorkManager) with automatic fallback when exact-alarm permission is absent. Ships `react-native-notify-kit/jest-mock`. Its config plugin explicitly **does not** add `USE_EXACT_ALARM`.
- notify-kit fixed-bug list includes beyond what I first flagged: **#961** `AlarmType.SET_EXACT` doesn't work in Doze / `SET` uses `RTC` instead of `RTC_WAKEUP`; **#875 DST shifts repeating notifications by ±1 hour**; **#1237** `getNotificationSettings()` returns `DENIED` instead of `NOT_DETERMINED` before request (this is what makes replacing the POC's hardcoded permission stub actually possible); #1100 killed-app Android 14-15 (missing `goAsync()`); #1100 silent exact-alarm-denial drop; #1128 cold-start `getInitialNotification()` null.
- **Expo SDK 57.0.0 is current** (RN 0.86, React 19.2.3, compile/target SDK 36). POC is SDK 56 / RN 0.85.3. ⇒ **Staying on SDK 56 is the evidence-backed choice for this phase**, because notify-kit's validated dev target is RN 0.85.3. SDK 57 is a separate, later upgrade with notify-kit compatibility to re-verify (no current compat matrix exists for SDK 57).
- **`USE_EXACT_ALARM` is NOT verifiably approved for medication apps.** Android's documented examples/restrictions name **alarm-clock and calendar** apps only; no Google Play policy text approving medication reminders was found. Android 14 lists `setExact`, `setExactAndAllowWhileIdle`, **and `setAlarmClock`** as all requiring exact-alarm authorization. Documented correct path: declare `SCHEDULE_EXACT_ALARM` → `canScheduleExactAlarms()` → `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` → recheck → reschedule on permission change → **degrade gracefully**. ⇒ Drop `USE_EXACT_ALARM` from the POC plugin.
- **PowerSync's Convex source connector is listed alpha/experimental**; PostgreSQL is GA. Reconfirms D3. (PowerSync also has a **.NET SDK, beta**, `PowerSync.Maui@0.1.2`, 2026-05-07 — target frameworks listed are net8.0/net9.0, **MAUI 10 not verified**.)
- **No official Convex C#/.NET client exists.** Convex first-party clients: JS/TS, React Native, Kotlin, Swift, Rust, Python. C#/.NET would be limited to `POST /api/query|mutation|action|run/{fn}` HTTP/OpenAPI, which Convex explicitly documents as **"not reactive/real-time."** ⇒ The "MAUI client + Convex backend" hybrid would have been the worst of the options considered.
- **Correction to any impression that MAUI is dying — it is not.** MAUI **10.0.90** shipped 2026-07-22 (support policy updated 2026-07-23; EOL 2027-05-11); MAUI 11 preview.6 shipped 2026-07-20; Microsoft published CoreCLR-by-default (2026-05-12), CoreCLR-only for .NET 11 P6 (2026-07-14), and Material 3 (2026-05-13). No Microsoft source says MAUI is replaced by Blazor Hybrid/Uno/Avalonia. ⇒ **The pivot rationale is F3 (the shipped MAUI client has no reminder capability at all) + stack fit for offline-first realtime, NOT MAUI's viability.** Recorded so the decision rests on true premises.
- Realm/MongoDB Atlas Device Sync reached **end-of-life 2025-09-30** (local DB continues; sync does not) — rules it out as a MAUI offline-sync route.

### F10 — USER-SUPPLIED EVIDENCE (2026-07-26) + the AlarmType discovery
- **The user has already device-tested the MAUI alarms and they WORKED on a physical Android 17 device.** This is the single most important piece of evidence in the whole investigation and it was not in any doc (`docs/poc-results.md` still shows all notification criteria `⏸️ Pending`; README's Pixel 8 Pro claim was vague). It means: **Android will reliably deliver time-critical alarms for this app class on Android 17.** The platform question is ANSWERED, positively.
- Crucially, the proven mechanism is `AlarmManager.SetAlarmClock` (`src/TaskRx.Maui/.../AndroidNotificationScheduler.cs:55-63,81-97`) — **not** `setExactAndAllowWhileIdle`.
- **The Expo POC uses the weaker primitive.** `poc-v0.1.0:apps/mobile/src/notifications/scheduler.ts:68-76` sets `AlarmType.SET_EXACT_AND_ALLOW_WHILE_IDLE`. Per notify-kit's AlarmType guide: `SET_EXACT_AND_ALLOW_WHILE_IDLE` = exact ✓, wakes ✓, Doze-bypass ✓, **OEM-resilient ✗**, status icon ✗. `SET_ALARM_CLOCK` = all of those **plus OEM-resilient ✓** and status icon ✓, and is "the strongest Android guarantee available for a scheduled notification … the same mechanism the stock Clock app uses"; vendor aggressive-kill policies (MIUI/ColorOS/EMUI/FuntouchOS, cf. dontkillmyapp.com) "generally respect `setAlarmClock` even when they would otherwise drop `setExactAndAllowWhileIdle`."
- **SELF-CORRECTION (verified, not assumed):** I initially inferred that `SET_ALARM_CLOCK` was only available in notify-kit. **False.** Unpacking both tarballs shows `AlarmType.SET_ALARM_CLOCK = 4` in `dist/types/Trigger.d.ts` of BOTH `@notifee/react-native@9.1.8` and `react-native-notify-kit@10.5.0` (upstream PR #749 shipped). ⇒ The AlarmType fix is **independent of the library migration**; the POC simply chose the weaker primitive. The notify-kit migration (D7) remains justified on its own grounds — archived 2026-04-07, plus fixes #1100/#961/#875/#1237/#1128 — but must NOT be justified by `SET_ALARM_CLOCK` availability.
- Degradation contract confirmed: with `SET_ALARM_CLOCK`, if exact-alarm access is absent the library falls back to `setAndAllowWhileIdle` (inexact) rather than crashing or dropping the trigger; recommended check is `getNotificationSettings().android.alarm` + `openAlarmPermissionSettings()`. This directly validates D9 and feeds todo 6.
- Tradeoff to surface: `SET_ALARM_CLOCK` renders a **persistent status-bar alarm-clock glyph** while any trigger is pending. With a rolling horizon there is always one pending ⇒ permanent icon. Judged acceptable/desirable for a medication app; recorded as a conscious product decision.
- Device/target gap noted: device is Android 17 while `apps/mobile/app.json` sets `targetSdkVersion 34`. Backward-compatible and fine for testing; raising targetSdk is a later Play-compliance task, explicitly not in scope.

### F11 — Second criterion added by user: web + iOS reach
User weighted **"web + iOS reach"** alongside testability. This is the tiebreaker that settles the stack question in favour of Expo/TS, independent of the testability analysis (which actually favoured .NET on measured evidence — see F12). Consequences folded into the plan without adding scope:
- Domain logic MUST live in `packages/shared`, never `apps/mobile` — so Expo web, Android, and future iOS share one engine. (Reinforces D6 / Phase 2.)
- The global-64 notification horizon (todo 7) is now **load-bearing for iOS**, which enforces a hard 64-scheduled-notification cap per app — not merely tidiness.
- Android-only concerns (`SET_ALARM_CLOCK`, `SCHEDULE_EXACT_ALARM`, boot receivers) stay behind the `src/notifications/` boundary so iOS can be added alongside, not by surgery.
- Known iOS follow-up: notify-kit implements iOS repeating timestamp triggers as a bounded rolling schedule of one-shots rather than native repeating calendar triggers — relevant when iOS is picked up.
- Web caveat recorded: `notify-kit` does not run on web at all, and `expo-sqlite` on web needs the OPFS/wa-sqlite path — so an Expo-web client can serve UI/management but NOT reminder delivery. Web is a companion surface, not a reminder surface.

### F12 — Testability analysis (asked directly by the user), honest result
Measured on this machine: **.NET builds and tests green today with zero setup** (`dotnet build TaskRx.slnx` → 0 errors, 48s; 57 Core tests in 57ms; 30 Server tests in 3s against real Postgres via Testcontainers = Docker present and working). The **TS/Expo side is entirely unverified** — deleted from the tree, with a flagged Node v26.4.0 vs Expo SDK 56 risk.
- Domain logic: tie (xUnit ≈ vitest).
- Backend: **near-tie, and I corrected myself here** — I was about to claim Convex is hard to test. Wrong: `convex-test` is a mature in-memory JS mock of the Convex backend (67 versions, ~540k weekly downloads, 0 deps, vitest, `@edge-runtime/vm`), needs **no deployment and no Docker**, and can mock `fetch` and control time/randomness — things the Testcontainers/real-backend approach explicitly cannot. Convex also offers a local OSS backend for higher fidelity. So: .NET wins fidelity, Convex wins controllability + no-Docker.
- **"Does the alarm fire?": tie, and it dominates.** Pure `adb`/`dumpsys` either way; the harness is a bash script regardless of language. Sub-edge to .NET for debuggability (C# calls AlarmManager directly; Expo routes through a Kotlin TurboModule).
- Build/iterate loop: **.NET wins decisively** (measured 48s, zero setup vs Expo prebuild + Gradle + dev client).
- UI testing: **TS wins** (Expo web + Playwright, headless) but narrower than first stated — covers screens/forms/navigation only, since notify-kit has no web support.
- Training-data density: TS wins on the client half; ASP.NET Core well covered on the server half.
- **Honest conclusion:** on testability alone, .NET is easier *today* — but its advantage is concentrated in the half of the system that already works. The broken half (client has no reminder capability; delivery unverified) is equally hard in both. Testability therefore did **not** decide this; **F11 (web + iOS reach) did**, plus F10 removing most of the platform risk.
- Third option raised and declined by the user: Expo client + keep the ASP.NET Core server (drop Convex). Would have deleted Phase 2 entirely and removed the `localId`↔Convex-`_id` bridge. Recorded for the record; not chosen.

## Decisions (with rationale)
| # | Decision | Rationale |
|---|---|---|
| D1 | **Before anything else**, commit the .NET work to an archive branch, then restore the POC tree on `main`. Never `checkout`/`stash` the dirty tree. | F1. 6,700 LOC of unversioned work is one careless command from gone. Purely additive; zero downside. |
| D2 | Target stack = Expo + React + Convex + Clerk + expo-sqlite | User's explicit direction. |
| D3 | Keep hand-rolled SQLite + sync queue; do not adopt PowerSync | F8/F9. Convex connector is alpha/experimental, and it would discard the POC's working worker + Convex-side authorization. |
| D4 | Fix the 3 POC defects (F5) as part of restore, not "later" | Each one silently breaks a safety-critical path. |
| D5 | **Scope = prove the stack first** (user-chosen). Phase 2 domain port is planned but hard-gated behind the device-test result. | User answer Q1. Verifying the notification foundation before investing 2 months is the highest-information ordering. |
| D6 | **Archive .NET, then port `TaskRx.Core` (1,485 LOC / 57 tests) to TypeScript** in gated Phase 2 (user-chosen). | User answer Q2. Preserves the unified med+task model, weekly schedules, NL parser, refill, adherence — all pure logic that ports cleanly and is strictly ahead of the POC's domain. |
| D7 | **Migrate Notifee → `react-native-notify-kit` v10.5.0** (user-chosen). | User answer Q3 + F6/F9. Notifee is archived and its own README points here; fixes the exact killed-app/Doze/DST/permission bugs that criterion #5 tests. Drop-in: import path only. |
| D8 | **Stay on Expo SDK 56 / RN 0.85.3 this phase.** Do NOT upgrade to SDK 57. | F9. notify-kit's validated dev target *is* RN 0.85.3; no notify-kit↔SDK 57 compat matrix exists. Minimizes variables while answering one question. |
| D9 | **Drop `USE_EXACT_ALARM`;** implement the documented `SCHEDULE_EXACT_ALARM` request→recheck→reschedule→degrade lifecycle. | F7/F9. Not a verifiably approved category for medication apps ⇒ Play-review liability. The user-granted route plus honest degradation is the compliant design, and it forces the permission-denied path to actually be handled. |
| D10 | **Device tests become agent-automated over `adb`** (emulator gate first, then physical device confirmation), not a human checklist. | The POC's tests were written as human markdown checklists and consequently *never run* — 12 of 13 criteria still ⏸️. Automating them is the only way this doesn't happen again. Android SDK exists at `/home/rob/Android/Sdk` but lacks `emulator` + system images; both installable via `sdkmanager`. Emulator cannot model OEM battery optimization, so real hardware remains authoritative. |
| D11 | **Use `AlarmType.SET_ALARM_CLOCK`, not `SET_EXACT_AND_ALLOW_WHILE_IDLE`.** | F10. This is the mechanism the user has **already empirically verified firing on a physical Android 17 device** via the native MAUI build. It is the only AlarmType that is OEM-resilient (survives MIUI/ColorOS/EMUI/FuntouchOS aggressive-kill), it is what the stock Clock app uses, and it degrades to inexact rather than dropping when permission is absent. Available in both libraries, so this is orthogonal to D7. Accepted tradeoff: a persistent status-bar alarm glyph while a dose is pending. **Highest value-per-line change in the plan.** |
| D12 | **Stack = Convex + Expo confirmed, but decided by reach, not testability.** | F11 + F12. Measured testability favours .NET today; web + iOS reach favours Expo decisively, and F10 removes most of the platform risk that made the pivot scary. Recording the true reason so the decision isn't later misremembered as "TS tests better." |

## Scope IN
- Git recovery / archive of the .NET work (D1).
- Restoring and de-risking the Convex + Expo POC.
- Whatever the user selects in Q1/Q2/Q3 below.

## Scope OUT (Must NOT have)
- No iOS work.
- No web client.
- No drug-interaction checker.
- No PowerSync.
- No `git checkout`, `git restore`, `git stash`, `git clean`, or `git reset --hard` against the dirty working tree at any point.
- No deletion of the .NET code until it is committed somewhere and the user confirms.

## Open questions
- Q1 — Scope/destination of the revisit (prove-the-stack vs full rebuild vs sequenced).
- Q2 — Fate of the .NET work (archive only / port the Core domain to TS / keep ASP.NET as backend).
- Q3 — Notification library (notify-kit fork vs stay on Notifee vs expo-notifications). Safety-critical + external dependency ⇒ owner's call.

## Approval gate
status: approved
approved-at: 2026-07-26 (user: "approve")
pending-action: executing Phase 0 (rescue). Phase 1 begins after Phase 0 completes and user confirms Phase 0 result.
Q1-Q3 answered → D5/D6/D7 recorded. D8/D9/D10 derived from F9 corrections and announced as defaults for veto.
On approval: execution belongs to the worker (`$start-work`). On scope change: revise the plan, do not re-explore.
