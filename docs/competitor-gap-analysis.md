# Competitor feature gap analysis

TickTick 8.3.0.1 and Medisafe 9.52.1 against TaskRx, with implementation plans for
the gaps worth closing.

Companion to [`task-med-reminder-app-research (2).md`](./task-med-reminder-app-research%20(2).md),
which covers the same two apps from public sources. This document is grounded in the
shipped APKs themselves plus a read of this repository.

## Evidence

**Competitor side** — `~/Work/adb/ticktick-base.apk` (`28a32803…acee`) and
`~/Work/adb/medisafe-base.apk` (`13ceb895…62a80`), pulled from the test device.
REA archive inventory evidence: `ev_5225bfd5…ffcc4`. Static only:

- `AndroidManifest.xml` and `res/xml/*` decoded from binary AXML.
- `resources.arsc` string pool (7,786 TickTick / 5,945 Medisafe values).
- DEX string tables for package and endpoint fingerprints.
- **No decompilation** — no JADX provider on this host, so no business logic was read.
  Every competitor claim below rests on a component name, a user-facing string, or an
  asset, and is labelled with the string that carries it where it matters.

**TaskRx side** — `CONTEXT.md`, `docs/architecture.md`, the 14 routes in
`ui/Routes.kt`, the 106 Kotlin files under `app/src/main`, and the `:core` package list.

## Thesis

**TaskRx is a reminder app that logs. It is not a health record.**

That single sentence decides the ranking below. A feature earns a place if it helps a
dose happen on time, or records honestly that it did or did not. It does not earn a place
because a competitor ships it, and it does not earn a place because the data is already
sitting in the model.

Two consequences, applied in the list that follows:

- Features that **adjudicate** — interactions, dose guidance, "is this safe" — are out.
  A logger that warns about interactions is making a clinical claim it cannot support.
- Features that **accumulate clinical measurements** — vitals, symptoms, mood — are out.
  They turn the store into a record and drag the whole product across a regulatory line
  nobody asked to cross.

What stays is what a person would notice missing within a week of missing doses: being
told, being locked out, and having something to show for it.

## Verdict

TickTick and Medisafe are not the same product, and TaskRx is not behind both of them —
it is behind each on a different axis.

- **Against TickTick**, TaskRx loses on *surface*: no widgets, no calendar, no sharing,
  no voice, no search over its own data, no export. It wins on the thing TickTick cannot
  do — clinical semantics (adherence, missed-by-sweep, refill, as-needed spacing,
  two-tier delivery).
- **Against Medisafe**, TaskRx loses on *safety net and privacy*: no caregiver alert, no
  app lock, no artefact to hand anyone, no backup. It wins on notifications (Medisafe's
  own users report disappearing alarms), on offline-first sync, and on the unified
  medication/task timeline Medisafe has no concept of.

The gaps that matter are therefore not the flashy TickTick ones, and not Medisafe's
clinical ones. They are the four that survive the thesis test — **caregiver alert, nag,
app lock, log export** — plus the three cheap TickTick ones (widget, share intake,
backup).

## What TaskRx already has

Do not rebuild these; several competitor features are already present under different
names.

| Capability | TaskRx | Where |
|---|---|---|
| Unified medication + task reminders, one lifecycle | yes | `core/domain/Reminder.kt`, ADR 0001 |
| Schedules: daily / interval / weekly / monthly / once / none / as-needed | yes | `core/schedule/ScheduleTimes.kt` |
| Backlog (commitment with no moment) | yes | `ui/backlog`, ADR 0002 |
| Statuses pending / completed / skipped / missed / snoozed | yes | `core/domain/InstanceLifecycle.kt` |
| Grace period + missed sweep | yes, 2 h / 7 d | `core/sweep/Planner.kt`, ADR 0005 |
| Pause windows | yes | `core/domain/Pause.kt`, ADR 0009 |
| Stop with history kept | yes | `core/domain/ReminderLifecycle.kt` |
| As-needed with min gap and 24 h cap | yes | `core/asneeded/AsNeeded.kt`, ADR 0010 |
| Refill projection + reminder | yes | `core/refill/Projection.kt` |
| Adherence, medication-only | yes, with a bar per day | `ui/adherence`, `core/domain/Calculator.kt` |
| Batch answer (take/snooze/skip all) | yes, plus shade batch notification | `core/batch/Batch.kt` |
| Catch up over missed occurrences | yes | `ui/catchup` |
| Undo an answer | yes | `core/undo/Undo.kt`, ADR 0008 |
| Natural-language add ("every two weeks at 9am") | yes | `core/parser/*`, 1,007-line test |
| Two-tier delivery: `setAlarmClock` for doses, WorkManager for tasks | yes | `core/notifications`, ADR 0003/0007 |
| Permission state machine + banners | yes | `core/notifications/PermissionState.kt` |
| Boot / timezone / time-set re-arm | yes | `RearmReceiver` |
| Offline-first Room + self-hosted Convex sync, queue, dead letters, conflict rules | yes | `core/sync/*` |
| Drug lookup (RxNorm) | yes | `ui/search`, `convex/rxnorm.ts` |
| Dark mode following the system | yes | `ui/Theme.kt` |
| Recording a dose nobody scheduled | yes | `ui/dose`, `DoseRecorder` |
| Caregiver *domain model* | model only | `core/domain/Reminder.kt` — `Caregiver`, `deviceTokens`, opt-ins. No table, no transport. |

## Feature matrix

`Y` shipped · `P` partial or model-only · `–` absent · `n/a` not their product

| Feature | TickTick | Medisafe | TaskRx |
|---|---|---|---|
| Recurring schedules incl. interval, monthly, once | Y | Y | Y |
| Natural-language add | Y | – | Y |
| Voice add | Y | – | – |
| Share-sheet intake | Y | – | – |
| Multiple reminders per item | Y (5) | Y (nagging) | P (times list, but no nag) |
| Snooze with presets | Y | Y | Y |
| Repeat-until-answered nag | – | Y | – |
| Skip / reschedule an occurrence | Y | Y | Y (skip; no reschedule) |
| As-needed / PRN medication | – | Y | Y |
| Adherence reporting | – | Y | Y |
| Refill / supply tracking | – | Y | Y |
| Drug–drug and lifestyle interactions | – | Y | – |
| Pill images, shape/colour ID | – | Y | – |
| Barcode / label scan to add | – | Y | – |
| Caregiver alert on missed dose | – | Y (Medfriend) | P (model only) |
| Family pillbox / dependent profiles | – | Y | – |
| Doctor appointments | – | Y | – |
| Doctor contacts / records | – | Y | – |
| Vitals tracking (BP, glucose, weight, pulse…) | – | Y (20+) | – |
| Diary, mood, symptoms, side effects | – | Y | – |
| Drug leaflets | – | Y | – |
| PDF / Excel report to share with a clinician | – | Y | – |
| Backup / restore / export | P (cloud only) | Y (import/export) | – |
| App lock (PIN, biometric) | – | Y | – |
| Home-screen widget | Y (~40) | Y (daily list) | – |
| Lock-screen widget | Y | – | – |
| Quick Settings tiles | Y (8) | – | – |
| Wear OS | Y | Y | – |
| Calendar view + Google/Outlook sync | Y | – | – |
| Location-based reminders | Y | Y (places/geofence) | – |
| Templates | Y | Y (guided flow) | – |
| Habits with streaks | Y | – | – |
| Pomodoro / focus timer + stats | Y | – | – |
| Eisenhower matrix | Y | – | – |
| Countdown / anniversary | Y | – | – |
| Shared lists, assignment, comments | Y | – | – |
| Teams | Y | – | – |
| Attachments / photos | Y | Y (pill photos) | – |
| Search across own data | Y | Y | P (drug search only) |
| Charts over time | Y | Y (Room cards) | P (adherence bars) |
| Themes / accent colours | Y | P | P (light/dark only) |
| i18n | Y (many) | Y | – (strings hardcoded) |
| Offline-first with local source of truth | P | P | Y |
| Self-hosted backend | – | – | Y |
| Coupons, insurance, telemedicine, ads | – | Y | n/a (deliberately) |

## Gaps worth closing

Every row in the matrix above marked `–` falls into one of two piles: missing by omission,
or missing by decision. Two are decisions already — drug interactions and vitals, both
argued out at the end of this document under *Gaps I would not close*. The list below is
the omission pile, ranked by how much the absence hurts a person who is trying to take
their medication on time.

Nothing here requires a new scheduling axis, a third entity kind, or a rule outside `:core`.

Effort: `S` < 1 day, `M` 2–5 days, `L` 1–3 weeks.

### 1. Caregiver alert on a missed dose — L

**Medisafe:** `Medfriend`. Evidence: `add_medfriend.xml`, `cardview_medfriend.xml`,
`medfriend_invitee_page_1.xml`, `fragment_sync_med_with_friend.xml`, "Improved Safety
Net", "Introducing: Safety Net".

**TaskRx:** the domain type exists and is unused — `Caregiver(id, userId,
caregiverUserId, relationship, notifyForMissedMedication, notifyForMissedTask, channels,
deviceTokens, createdAt)` in `core/domain/Reminder.kt`, and `Reminder.caregiverIds`
persisted as a JSON array. No table, no tokens, no transport. `docs/architecture.md`
lists caregiver alerts under *Deferred*.

**Why it is the top gap:** it is the only feature in either app that catches the failure
mode TaskRx exists to prevent — the dose that was missed and nobody knew. It is also the
one place where being told is the entire product.

**Implementation**

1. **Convex schema** (`packages/convex/convex/schema.ts`): a `caregivers` table
   (`ownerId`, `caregiverId`, `relationship`, `notifyForMissedMedication`,
   `notifyForMissedTask`, `status: invited|active|revoked`, `createdAt`, `deletedAt`) and
   a `deviceTokens` table (`userId`, `token`, `platform`, `lastSeenAt`). Both indexed by
   `by_owner` / `by_user`.
2. **Invite flow**: owner enters a caregiver's email → creates a `caregivers` row with
   `status: invited` → the caregiver sees it on next sign-in under a new "Caregivers"
   section of Settings. No email infrastructure needed for v1; Clerk already identifies
   both parties by email. Asymmetric by construction: the caregiver never gets write
   access to the owner's reminders.
3. **`core/caregiver/Alert.kt`** (pure, Kover-gated): `shouldAlert(caregiver, instance,
   reminder, now): Boolean`. Rules to encode and test:
   - only `missed` instances, never `pending`, `snoozed` or `skipped`
   - `notifyForMissedMedication` / `notifyForMissedTask` gate by `reminderKind`
   - one alert per instance per caregiver — a stable key
     `alert-<instanceId>-<caregiverId>` so a retry cannot double-page
   - a grace window before alerting (reuse the sweep verdict, don't alert on the same
     tick the sweep mints the miss — Medisafe waits, and so should this)
4. **Server-side detection**, because the phone may be off. A Convex cron
   (`crons.ts`, every 5 minutes) queries `reminderInstances` where `status = "missed"`
   and `alertedAt` is null, then for each parent reminder's caregiver links writes an
   `alerts` row and calls an FCM send action. Set `alertedAt` in the same mutation.
5. **FCM**: add `firebase-messaging` to `:app`, a `FirebaseMessagingService` that only
   registers a token with Convex, and `POST_NOTIFICATIONS` handling that already exists.
   Caregiver-side push uses its own channel (`caregiver_alerts`, HIGH) — do **not** reuse
   `medication_reminders`, whose importance the owner may have changed.
6. **The offline caveat, stated plainly:** an instance only exists once the *device* minted
   it. If the owner's phone was off for the grace period and never came back online, the
   server never sees a miss and cannot alert. Two mitigations, in order of cost:
   (a) have the client enqueue a "miss" observation optimistically when the sweep runs, and
   (b) accept it — Medisafe has the same hole.
7. **Tests:** `AlertTest` for the pure rules; `packages/convex/caregiverAlerts.test.ts`
   with `convex-test` for the cron's idempotence (run it twice, expect one alert).

**Do not** let a caregiver alert mutate the owner's instance. The alert is a projection,
not an answer.

**Boundary for this thesis:** the caregiver is told *that a dose was missed*. That is a
logging fact, not an assessment — do not add "adherence score" to the message, which is
where this drifts from reminder to clinical monitoring.

### 2. Repeat-until-answered nag — M

**Medisafe:** "nagging" is a first-class behaviour, and `docs/medisafe-skip-snooze-findings.md`
already analyses it. Evidence in the APK: `actions/ActionSnoozeItems`,
`indicator_snooze.xml`, `view_snooze_indicator.xml`, a 12-voice reminder set
("12+ Medtone Reminder Voices"), `alarm_snooze_dialog.xml`.
**TickTick:** a single `ANNOYING_REPEAT_ALERT` action exists in its task alert receiver.

**TaskRx:** one alarm per occurrence, then silence until the 2 h grace period expires and
the sweep calls it missed. If a dose is critical, nothing re-alerts.

**Implementation**

1. `core/notifications/Nag.kt` (pure): `nagPlan(occurrence, tier, now): List<Long>` —
   the follow-up moments for an unanswered dose. Bounded: e.g. 3 nags at +5, +15, +30 min,
   never past the grace period. Injectable policy so tests can pin exact instants.
2. Reuse the existing Tier machinery: nags are medication-tier only, and each nag
   notification **replaces** the previous one for the occurrence (same notification id,
   updated text) instead of stacking three in the shade.
3. Arming: `NotificationScheduler` already diffs desired vs armed with deterministic ids.
   Nags extend the desired set with `nag-${reminderId}-${scheduledTime}-${index}`; the
   diff logic needs no change beyond including them. Cancellation is already covered —
   answering the dose drops every id for that occurrence from the desired set.
4. Cap the horizon cost: a nag is only armed when the occurrence is itself in the Horizon,
   so the 64-dose budget does not shrink.
5. **Test:** `NagTest` for the plan; a harness case that fires a dose, answers nothing, and
   asserts three posts and a cancel on answer.

**Unanswered already has a meaning here.** A nag that fires must not change the status —
the occurrence stays `pending` until the sweep, exactly as today. The nag is a second
attempt at the person, not a second state.

### 3. Log export: PDF and CSV — M

**Medisafe:** "Share PDF report" (`common_pdf_view.xml`), "Export to Excel" (`export.png`),
`report_screen.xml`, `dialog_report_filter.xml`, `report_pill_line.xml`.
**TickTick:** an annual report as a web page — not relevant here.

**TaskRx:** adherence and history exist as screens; there is no artefact, and
`allowBackup="false"` means the data has no path off the device at all.

**Reframed by the thesis:** this is not a *clinical progress report* and must not present a
clinical judgement. It is **the log**, in a shape a person can keep, print, or hand to
whoever asked. The name of the app is the feature.

**Implementation**

1. `core/report/ReportModel.kt` + `Builder.kt`, pure:
   `buildReport(reminders, instances, range, now): ReportModel` — every occurrence in the
   range with its status and timestamps, per-medication counts (taken / skipped / missed),
   refill state, and a `generatedAt`. No Android imports, so it sits under the Kover gate.
   This is the part worth designing carefully; the renderer is trivial.
2. Rendering in `:app`: `android.graphics.pdf.PdfDocument` — no third-party dependency,
   ~150 lines, drawing text from the same `format/` helpers the screens use, so the report
   and the screen cannot disagree. Rows are factual: time, name, quantity, status.
3. CSV alongside it: one row per instance, RFC 4180 quoted. Excel opens it; do not pull in
   Apache POI for a `.xlsx` nobody asked for.
4. Share via a `FileProvider` — none exists in the app yet, so add `res/xml/file_paths.xml`
   exposing a `reports/` cache directory, the provider in the manifest, and
   `ACTION_SEND` with `application/pdf`.
5. New route `report` in `ui/Routes.kt`, ranged by 7 / 30 / 90 days / since start.
6. **Copy discipline:** say "log", "record", "history". Never "report card", "score",
   "compliance", or a grade. The moment the artefact renders a verdict, the app is
   advising.
7. **Pitfall:** a log is a health disclosure. Include the person's name only if they opt
   in, and leave notes out by default.

### 4. App lock: PIN + biometrics, and `FLAG_SECURE` — S/M

**Medisafe:** `activity_set_passcode.xml`, `activity_enter_passcode.xml`,
`preferences_layout_passcode.xml`, "Use biometrics or screen lock",
`fingerprint_dialog_layout.xml`, biometric error colours.
**TickTick:** a fingerprint dialog for its own account, not an app lock.

**TaskRx:** none. A medication log is health information, and on a shared or borrowed
phone the app opens straight to it.

**Implementation**

1. `core/security/Pin.kt` (pure): salted PBKDF2 via `javax.crypto` derivation,
   constant-time compare, and a throttle policy (`attemptsSinceSuccess` → delay).
   Testable, no Android.
2. `:app` `security/LockGate.kt`: a `BiometricPrompt` (`androidx.biometric`) with
   `DEVICE_CREDENTIAL` allowed as a fallback, wrapping `TaskRxApp`'s nav host so no route
   renders until unlocked. Re-lock on `ON_STOP` after a configurable idle window.
3. Store the verifier in `EncryptedSharedPreferences` (`androidx.security.crypto`), not
   the existing `taskrx.settings` prefs.
4. `window.setFlags(FLAG_SECURE)` when lock is enabled — blocks the recents thumbnail and
   screenshots, which is the leak people actually hit.
5. New ADR: *the lock is a UI gate, not encryption at rest* — Room stays unencrypted unless
   SQLCipher is adopted, and the ADR should say so rather than implying more than it does.

### 5. Barcode / label scan to add a medication — M

**Medisafe:** Scandit (`com.scandit`, ~2,442 class references — a paid SDK),
`sc_ic_barcode_scanned.xml`, `ocr_live_scan_bg.xml`, "Matrix scan count button".

**TaskRx:** typing a drug name is the only intake path.

**Implementation**

1. **Do not buy Scandit.** ML Kit Barcode Scanning is free, on-device, and handles the NDC
   formats on US packaging (UPC-A, EAN-13, DataMatrix on unit-dose blisters).
2. `:app` `ui/scan/ScanScreen.kt`: CameraX preview + `BarcodeScanning.getClient()`, with
   `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`. New route `meds/scan`, launched from the
   medication form's name field.
3. NDC normalization is real logic and belongs in `:core`:
   `core/barcode/Ndc.kt` — the 10-digit and 11-digit (5-4-2, 5-3-2, 4-4-2) forms of the
   same product are different strings for the same drug. Parse, normalize to 11-digit,
   validate the check digit. Test all three layouts plus a bad check digit.
4. Lookup: NDC → RxCUI via RxNav `ndcproperties`, as another Convex action next to
   `rxnorm.search`. Falls back to prefilling the name only. A scan that resolves to two
   candidates shows both and lets the person pick — never guess.
5. OCR of the label (Medisafe has it) is a second, larger step — ML Kit Text Recognition
   plus a parser over "take 1 tablet twice daily". `ReminderParser` already reads that
   grammar, so the missing piece is a text extractor, not a language model.

### 6. Home-screen widget — M

**TickTick:** ~40 `AppWidgetProvider*` receivers and ~25 config activities, all exported.
**Medisafe:** `WidgetDailyListReceiver`, `WidgetDailyListViewsService`, "Widget daily list".

**TaskRx:** none. It is also the feature users ask for first, because it removes two taps
from the most frequent action.

**Implementation**

1. **Glance** (`androidx.glance:glance-appwidget`), not RemoteViews: Compose-shaped, so the
   widget reads the same `format/` helpers as the screens.
2. `:app` `widget/TodayWidget.kt`: a `GlanceAppWidget` publishing today's medication rows in
   time order with the NEXT row highlighted, plus a Take action per row that fires the
   **existing** `taskrx://dose/<id>/<time>/<action>` PendingIntent — `BatchActionReceiver`
   already runs with the app closed, so no new answer path is needed.
3. Data: a `GlanceStateDefinition` fed from Room through a `WidgetRepository`, refreshed by
   `updateAll` from `NotificationScheduler`'s reschedule seam and from the answer flow.
   Never compute occurrences inside the widget — call `:core`'s existing timeline function.
4. Export **nothing**: `appwidget-provider` receivers are exported by necessity, so keep the
   receiver logic to a single `goAsync` update and validate the incoming action strings.
5. `res/xml/today_widget_info.xml` with `updatePeriodMillis="0"` (the app pushes updates;
   polling wakes the device for nothing).

### 7. Backup and restore — M

**Medisafe:** "Import Your Meds", "Automatically Import Meds".
**TickTick:** cloud sync only.

**TaskRx:** `allowBackup="false"`, self-hosted Convex, no export. If the server dies, the
data is gone — and the backup timers in `infra/convex/vps/systemd` only cover the server.

**Distinct from item 3 on purpose:** the PDF/CSV is for a human to read; this is a
machine round-trip for recovery, and it must restore *exactly*, including pauses and
refill counters that a report flattens.

**Implementation**

1. `core/export/Archive.kt` (pure): a versioned JSON document
   (`{schemaVersion, exportedAt, reminders[], instances[], pauses…}`) plus the inverse.
   `data/Codecs.kt` already has the per-field codecs; this is the envelope around them.
2. `:app`: `ACTION_CREATE_DOCUMENT` → write; `ACTION_OPEN_DOCUMENT` → read, validate
   `schemaVersion`, then import into Room inside one transaction, enqueueing sync entries
   in the existing shape so the server catches up.
3. Round-trip property test in `:core`: export → import → export is byte-identical. Kover
   forces it once the functions are in `:core`.
4. Settings gets an *Data* section with "Back up" / "Restore" — the current Settings screen
   has exactly one control ("Snooze length").

### 8. Appointments — M

**Medisafe:** `activity_appointments_list.xml`, `EditAppointmentActivity`,
`AddAppointmentSilentActivity`, `DoctorsListActivity`, `ic_appointment_past.xml`.

**TaskRx:** an appointment is currently just a task reminder, which is close to right.

**Implementation**

1. Do **not** add a third `Kind` — that would break ADR 0001's two-shape model and every
   `when (kind)` in `:core`.
2. An appointment is a task Reminder whose `tags` contain `appointment`, plus an optional
   `location` string. A "Doctors" screen is a saved filter over that tag plus a contact
   name in `instructions`. No schema change at all for v1.
3. If a contact list is wanted, it is a small local table (name, clinic, phone) with no
   sync in v1 — a phone number is not a medication log, and it does not need to leave the
   device.
4. Row action: a `dial`/`geo:` intent. This removes the "where is that appointment" gap
   without new scheduling semantics.

**Explicitly not copied:** Medisafe's health-records import from 100+ hospitals. It needs
partnerships, is US-specific, and pulls the product toward being a record.

### 9. Pill images — S/M

**Medisafe:** `activity_pillbox_selection.xml`, `med_pill_line.xml`, images per medication.

**TaskRx:** none, but `rxcui` makes it nearly free.

**Implementation:** RxNav `/rximage?rxcui=` returns the official label image. Cache bytes
in app storage keyed by RxCUI (small, immutable), show it on the medication detail and the
answer screen so the person can check they are holding the right box.

**Boundary:** showing the manufacturer's label image is a fact. Identifying an unknown
loose pill from its shape and colour is a classifier rendering a verdict on what the person
is about to swallow — that is out, and it is why Medisafe's `activity_pillbox_selection`
has no counterpart here. Barcode scanning (item 5) covers the case that matters: a box
with a code on it.

### 10. Templates — S/M

**TickTick:** "Save as template", "Search project template".
**Medisafe:** guided `TemplateFlowActivity`, ~102 template-flow resources.

**TaskRx:** every new medication re-enters the same fields.

**Implementation:** `core/template/Template.kt` — a name plus the recurring field set
(dosage, instructions, times, refill defaults). Stored in Room and Convex like a reminder.
"Save as template" on an existing reminder, "From template" at the top of Add. No new
scheduling semantics, which is exactly why it is cheap.

### 11. Voice and share-sheet intake — S

**TickTick:** `QuickSettingsVoiceAddTileService`, `voice_input_widget_progress_bar.xml`,
`ShareReceiveActivity`, `ProcessTextActivity`, "Scan Documents".

**TaskRx:** the parser already reads "take 2 pills at 9am daily"; there is no way to get
text *into* it from outside the app.

**Implementation**

1. Voice: `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` → transcript → `ReminderParser`. No
   new domain code; the parser is the hard part and it is done.
2. Share sheet: add an `ACTION_SEND` / `text/plain` intent filter to a small
   `ShareReceiveActivity` that routes to `Routes.NEW_MEDICATION` with the text prefilled.
   `PROCESS_TEXT` is the same trick for selected text in other apps.
3. Both are `:app`-only changes and both are worth more than most of the L-sized items here.

### 12. Search across your own reminders — S

**TickTick:** global search with filters. **Medisafe:** search over meds.
**TaskRx:** `ui/search` searches **drugs**, not your data.

**Implementation:** a `:core` `matches(query, reminder)` reusing the parser's tokenization,
backed by a Room `LIKE` or FTS query. A `meds`/`tasks` filter row. Half a day.

### 13. Charts over time — S/M

Adherence already renders a bar per day. The gap is trend, not numbers: per-medication
history over 30/90 days, taken versus scheduled. A single `Canvas` composable fed by
`core/report/ReportModel` (item 3) — build them together so the chart and the log cannot
disagree. Counts and dates only; no grades, no score out of a hundred.

### 14. i18n readiness — M

Both competitors ship many locales; TaskRx has **no** `strings.xml` at all — every label is
a Kotlin string literal. Extract the UI strings now, while there are few. But do not
promise a second language on that basis: the real cost is `core/parser`'s `Lexicon.kt` (181
lines of English verbs and units), which is the actual product value and needs a
per-language lexicon plus tests, not a translation pass.

### 15. Theme override — S

`ui/Theme.kt` has light and dark palettes, chosen by `isSystemInDarkTheme()`. Add a
three-way setting (System / Light / Dark) in `AppSettings`, which already exists and
already persists a preference. Twenty lines.

## Gaps I would not close

| Feature | Why not |
|---|---|
| **Drug–drug and lifestyle interaction checking** | It adjudicates. A reminder that logs can tell you what you took; the moment it says "these two conflict" it is giving advice, and the FDA boundary in Part 2 of the research doc is exactly the line being crossed. Medisafe has a clinical team and a claims posture for this. If it is ever wanted, link out to a professional source — do not render a verdict. |
| **Vitals, symptoms, mood, side-effect logging** | Turns the store into a clinical record. `add_measurement.xml` and 20+ trackers are Medisafe's product, which *is* a health record. Every measurement added is a new regulatory and privacy surface for a feature the thesis says is out of scope. Side effects are the sharpest case: "log this symptom" is one step from "is this serious". |
| Pomodoro, focus timer, focus sounds | Different product. `fragment_focus_statistics_page.xml` is a productivity app inside a productivity app, and it would compete with the dose alarm for the Horizon. |
| Habit tracker, streaks, achievements | `%1$s achievement scores required`, "Best Streak". Grievous to gamify medication, and habit logging is a third aggregate with its own schedule semantics. |
| Eisenhower matrix | `"Use Eisenhower Matrix to prioritize tasks."` TaskRx stores `priority` but no scheduling rule reads it, by design. A quadrant screen would be a view over a field that deliberately does nothing. |
| Countdown / anniversary | `%1$d Day Left`. No medication meaning. |
| Shared lists, assignment, comments, teams | Large permission model. The caregiver slice (item 1) is the only part that serves the thesis, and it is asymmetric where collaboration is symmetric. |
| Coupons, insurance, telemedicine, ads | Medisafe's monetisation (`room_insurance_approved.xml`, "Get coupons for your meds", `VucaVideoActivity`). A product decision, not a feature. |
| Pharmacy import | Needs pharmacy partnerships ("Import meds from your pharmacy"). |
| Health-records import from hospitals | Same — partnerships, and it makes the app a record. |
| Loose-pill shape/colour identification | A classifier telling a person what they are about to swallow. Barcode scanning covers the real case. |
| Wear OS | Two modules and a data layer for a watch. Real, but it should follow the widget, which needs the same repository seam. |
| Location reminders | A trigger type the Schedule cannot express (`Schedule` is time-only). Would mean a second trigger axis through `nextOccurrences`, the Horizon and the sweep. Geofencing is also the worst battery and privacy trade in the app. |
| Email-to-task, Siri/Alexa, Zapier | Integrations for a task app; none has a medication analogue. **Superseded for the task side only** by `docs/plans/external-notices.md`: a notice arriving from outside (share sheet, notification, mailbox) may *propose* a move to a task, and the person confirms it. Medications are never touched, so the line still holds for them. |
| Lock-screen widget | Needs `requestDismissKeyguard` flows that fight the app lock. |

## Suggested sequence

1. **Share-sheet + voice intake** (S) and **search your own data** (S) — cheapest wins,
   both `:app`-only.
2. **App lock** (S/M) — the privacy floor, and a prerequisite for anything that puts more
   health data on the screen.
3. **Nag** (M) — the clinical fix that stays inside the thesis: it changes when the app
   speaks, not what it claims. Reuses the Tier and diff machinery the harness already
   proves.
4. **Log export + charts** (M) — one `:core` model, two renderers. This is the feature the
   name promises.
5. **Backup/restore** (M) — closes the `allowBackup="false"` hole.
6. **Caregiver alerts** (L) — the biggest build, and the only one needing FCM plus a server
   cron. After the log export, because both read the same `missed` instances.
7. **Widget** (M), **barcode** (M), **templates** (S/M), **pill images** (S/M),
   **appointments** (M).
8. **Theme override** (S) and **UI string extraction** (M, extraction only).

## Open questions for the product owner

**Answered — recorded here so later work does not re-litigate it:** *TaskRx is a reminder
app that logs. It is not a health record.* That decision removes interaction checking and
vitals from the roadmap, and it means the log export is a statement of fact, never an
assessment.

Still open:

- **What does the log go to?** If it is only for the person themselves, the CSV is enough
  and the PDF is polish. If someone else reads it — a partner, a carer, a clinician — then
  the copy, the review process and the wording all matter more than the layout.
- **Is a server cron acceptable at all?** Caregiver alerts are the first feature that cannot
  be local-only, and `docs/architecture.md` lists push notifications as deferred *by
  choice*. That choice needs an explicit yes before item 1 starts.
- **How much does a caregiver see?** "Your mother missed her 09:00 dose" is a log. "Your
  mother is at 62% adherence" is monitoring. The first is the feature; the second is the
  drift the thesis rules out.
- **Is app lock opt-in or mandatory?** Mandatory is safer and costs a setup step for
  everyone; opt-in keeps onboarding frictionless and protects nobody who forgets.
