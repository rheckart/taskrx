# Day 8 — POC Stress Tests (Pixel 8)

> Historical. Written for the Expo app, whose paths below (`apps/mobile/...`) now live on the `archive/expo-rn` branch. The current harness is `apps/android/scripts/notification-harness.sh`.

This document is the test script for the Day 8 gate: verifying that the TaskRx stack survives two high-load scenarios on a physical Android device (Pixel 8):

1. **Exact-alarm stress test** — 30 medications with staggered daily schedules must all fire correctly under normal conditions, Doze mode, and force-stop.
2. **Offline-first stress test** — local writes made in airplane mode must queue cleanly and sync to Convex within 60 seconds of restoring network.

> **Goal:** Confirm the React Native + Expo + Notifee + expo-sqlite + Convex stack can reliably deliver time-critical, offline-first medication reminders at scale.
> **Device:** Google Pixel 8 (physical), USB debugging enabled.
> **APK:** `apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk`
> **Package name:** `com.taskrx.mobile`
> **Prerequisite:** Complete `docs/poc-notification-tests.md` first. The Day 6 gate must pass before running these stress tests.

---

## Section 0: Prerequisites

### 0.1 Install / verify `adb`

```bash
adb --version
```

Expected output includes a version such as `Android Debug Bridge version 1.0.41`.

### 0.2 Connect the Pixel 8

1. Enable **Developer options** on the Pixel 8.
2. Enable **USB debugging**.
3. Connect the phone to the computer with a USB cable.
4. Verify the device is visible:

```bash
adb devices
```

Expected output:

```text
List of devices attached
<device-id>    device
```

If you see `unauthorized`, accept the "Allow USB debugging?" prompt on the phone and run `adb devices` again.

### 0.3 Disable battery optimization for TaskRx

This is required for AlarmManager exact alarms to survive Doze and Battery Saver.

```bash
adb shell dumpsys deviceidle whitelist +com.taskrx.mobile
```

Expected output includes:

```text
Added: com.taskrx.mobile
```

### 0.4 Install the debug APK

```bash
adb install -r apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

Expected output:

```text
Performing Streamed Install
Success
```

If the APK has not been built yet, run:

```bash
cd apps/mobile/android && ./gradlew assembleDebug
```

Or use the helper script:

```bash
./apps/mobile/scripts/build-debug-apk.sh
```

### 0.5 Grant notification and exact-alarm permissions

Open the app and sign in via Clerk magic link. When prompted, allow:

- **Notifications** — required for reminders to display.
- **Alarms & reminders** (exact alarm permission) — required for on-time delivery.

If the exact-alarm prompt is missed, grant it manually:

```bash
adb shell appops set com.taskrx.mobile SCHEDULE_EXACT_ALARM allow
```

Or open **Settings → Apps → TaskRx → Alarms & reminders** and enable it.

### 0.6 Confirm Day 6 gate passed

Before stressing the scheduler, the basic notification path must work. If any Day 6 scenario failed, fix it first (likely Day 4 notification permissions / scheduler logic).

---

## Section 1: Exact-Alarm Stress Test (30 medications)

### 1.0 Seed the test data

#### Option A — Use the debug screen (preferred)

1. Open TaskRx and sign in.
2. From the home screen, tap **Stress Test Seed**.
3. Tap **Seed 30 meds**.
4. Wait for the message confirming 30 medications were seeded.

The screen creates medications named `Stress Test Med 1` through `Stress Test Med 30`, each with a single daily time staggered one hour apart, starting at the current minute + 1. For example, if you tap the button at 14:32, the schedules will be:

| Medication | Daily time |
|---|---|
| Stress Test Med 1 | 14:33 |
| Stress Test Med 2 | 15:33 |
| Stress Test Med 3 | 16:33 |
| ... | ... |
| Stress Test Med 10 | 23:33 |
| Stress Test Med 11 | 00:33 |
| ... | ... |
| Stress Test Med 30 | 07:33 (next day) |

#### Option B — Manual seeding (if debug screen is unavailable)

1. Open TaskRx and sign in.
2. Tap **Add medication** 30 times.
3. For medication `N` (1–30), name it `Stress Test Med N` and set a **Daily** schedule at `(current hour + N - 1) : (current minute + 1)` (wrap hours modulo 24).
4. Save each medication.

> **Tip:** Option A takes seconds. Option B is only a fallback.

### 1.1 Verify schedule registration

1. Open the **Stress Test Seed** debug screen (or the **Notification Debug** screen).
2. Look at **Pending notifications**.

#### Expected result

- Pending notifications shows **300** (30 medications × 10 upcoming doses each).
- If your build caps the per-med count differently, the number should equal `30 × count` and be stable after tapping **Reschedule all**.

#### Pass / fail

- [ ] **PASS** — Pending count is 300 (or 30 × per-med dose count) and stable.
- [ ] **FAIL** — Pending count is 0, much lower than expected, or fluctuates unexpectedly.

#### Notes

```text
Pending count observed:
Expected count:
Stable after reschedule? Y/N
```

### 1.2 Normal operation test

1. Lock the Pixel 8 (press power button).
2. Wait for the next scheduled dose time (the closest `Stress Test Med` time).
3. When the notification fires, note the medication name and exact time.
4. Repeat for at least 3 consecutive hours if time allows.

#### Expected result

- A notification appears at each scheduled minute.
- Notification title matches the expected `Stress Test Med N`.
- Notification body includes `Time to take`.
- No duplicate notifications for the same medication at the same time.

#### Pass / fail

- [ ] **PASS** — All observed notifications fired on time with correct content.
- [ ] **FAIL** — Any notification was missing, late by more than 1 minute, or had wrong content.

#### Notes

```text
Scheduled time 1:
Actual fire time 1:
Medication shown 1:
Scheduled time 2:
Actual fire time 2:
Medication shown 2:
Scheduled time 3:
Actual fire time 3:
Medication shown 3:
```

### 1.3 Doze mode test

1. Force the device into Doze mode:

```bash
adb shell dumpsys deviceidle force-idle
```

2. Lock the device.
3. Wait for the next scheduled `Stress Test Med` dose time.

#### Expected result

- Notification fires despite Doze.
- The battery-optimization whitelist from Section 0.3 plus `AlarmManager.setExactAndAllowWhileIdle` allows the alarm to wake the device.

#### Pass / fail

- [ ] **PASS** — Notification fired while device was idle/Dozing.
- [ ] **FAIL** — Notification did not fire or was delayed more than 1 minute.

#### Notes

```text
Doze forced via adb? Y/N
Scheduled time:
Actual fire time:
Delay (minutes):
```

4. Exit Doze mode when done:

```bash
adb shell dumpsys deviceidle unforce
```

### 1.4 Force-stop test

1. Force-stop the app:

```bash
adb shell am force-stop com.taskrx.mobile
```

2. Lock the device.
3. Wait for the next scheduled `Stress Test Med` dose time.

#### Expected result

- Notification still fires at the scheduled minute.
- Android's AlarmManager persists exact alarms across force-stop for apps that hold `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` and are whitelisted from battery optimization.

#### Pass / fail

- [ ] **PASS** — Notification fired after force-stop.
- [ ] **FAIL** — Notification did not fire.

#### Notes

```text
Force-stop method used:
Notification fired? Y/N
If no, check exact-alarm permission and battery whitelist.
```

### 1.5 Add-medication-while-running test

1. Re-open TaskRx.
2. Add a 31st medication named `Stress Test Med 31` with a daily schedule 2–3 minutes from now.
3. Return to the **Stress Test Seed** or **Notification Debug** screen and tap **Reschedule all**.

#### Expected result

- `rescheduleAll()` cancels and re-schedules all notifications.
- Pending count returns to **310** (31 medications × 10 doses) or `31 × per-med count`.
- Existing `Stress Test Med 1–30` notifications remain scheduled (no drop).
- The new medication's first dose fires on time.

#### Pass / fail

- [ ] **PASS** — Pending count updated correctly and new med fired on time.
- [ ] **FAIL** — Pending count dropped, or new med did not fire.

#### Notes

```text
Pending count before reschedule:
Pending count after reschedule:
New med fired? Y/N
New med fire time:
```

### 1.6 Clear all test data

1. Open the **Stress Test Seed** debug screen.
2. Tap **Clear All Test Meds**.
3. Check **Pending notifications**.

#### Expected result

- All medications with names starting with `Stress Test Med` are soft-deleted.
- Pending notifications drops to **0**.

#### Pass / fail

- [ ] **PASS** — Pending count is 0 after clearing.
- [ ] **FAIL** — Pending count remains above 0 or test meds still appear in the medication list.

#### Notes

```text
Pending count after clear:
Test meds still visible? Y/N
```

---

## Section 2: Offline-First Stress Test

### 2.0 Setup

1. Open TaskRx and sign in.
2. Add 3 medications via the normal app flow:
   - `Test Med 1` — daily at 09:00
   - `Test Med 2` — daily at 13:00
   - `Test Med 3` — daily at 21:00
3. Wait for the sync status badge to show **synced** (green check).
4. Open the Convex dashboard and verify the 3 medications appear for your user.

#### Expected result

- 3 medications exist locally and in Convex.
- Sync status badge shows **synced**.

#### Pass / fail

- [ ] **PASS** — Setup medications visible in Convex dashboard.
- [ ] **FAIL** — Medications missing in Convex (sync not working; stop here and revisit Day 7).

#### Notes

```text
Convex dashboard URL:
Test Med 1 ID:
Test Med 2 ID:
Test Med 3 ID:
```

### 2.1 Offline writes

1. Enable airplane mode:

```bash
adb shell cmd connectivity airplane-mode enable
```

If the above command is unavailable on your Android version, use:

```bash
adb shell settings put global airplane_mode_on 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
```

2. Wait 5 seconds for connectivity to drop.
3. In the app, add 1 more medication:
   - `Offline Med 1` — daily at 10:00
4. Log 5 doses across the 4 medications (mix of taken / skipped):
   - Mark `Test Med 1` dose as **taken**.
   - Mark `Test Med 2` dose as **skipped**.
   - Mark `Test Med 3` dose as **taken**.
   - Mark `Offline Med 1` dose as **skipped**.
   - Mark `Test Med 1` second dose as **taken**.

#### Expected result

- All UI writes succeed with no error toasts.
- The sync status badge changes to **offline** (gray cloud-off) or **pending** (yellow clock).
- Medication list and dose history reflect the new entries immediately.

#### Pass / fail

- [ ] **PASS** — All offline writes succeeded and UI stayed responsive.
- [ ] **FAIL** — Error toasts appeared, UI froze, or writes were lost.

#### Notes

```text
Airplane mode enabled via:
Any error toasts? Y/N
Sync badge shown:
```

### 2.2 Verify sync queue

Run the following `adb` command to inspect the local SQLite `sync_queue`:

```bash
adb shell run-as com.taskrx.mobile sqlite3 /data/data/com.taskrx.mobile/databases/taskrx.db "SELECT COUNT(*) FROM sync_queue;"
```

#### Expected result

- The query returns **6** (1 medication create + 5 dose log operations).
- The exact count may vary slightly if the app performed extra sync housekeeping, but it should be close to 6 and greater than 0.

#### Pass / fail

- [ ] **PASS** — Sync queue contains the expected number of pending entries.
- [ ] **FAIL** — Sync queue is empty (writes were not queued) or count is unexpectedly high.

#### Notes

```text
Sync queue count:
```

> **Note:** `run-as` only works on debug builds. If it fails, the device may need to be rooted, or you can verify queue behavior through the app's sync status badge instead.

### 2.3 Restore network

1. Disable airplane mode:

```bash
adb shell cmd connectivity airplane-mode disable
```

If the above command is unavailable, use:

```bash
adb shell settings put global airplane_mode_on 0
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
```

2. Wait up to 60 seconds.
3. Watch the sync status badge in the app.

#### Expected result

- Sync status badge transitions from **offline** / **pending** → **syncing** (blue spinner) → **synced** (green check).
- In the Convex dashboard, the new medication `Offline Med 1` and all 5 dose logs appear.

#### Pass / fail

- [ ] **PASS** — Badge reached synced state and data appeared in Convex within 60s.
- [ ] **FAIL** — Badge stayed offline/pending, or data did not appear in Convex.

#### Notes

```text
Time to reach synced state (seconds):
Offline Med 1 visible in Convex? Y/N
Dose logs visible in Convex? Y/N
```

### 2.4 Verify queue is drained

Re-run the SQLite query:

```bash
adb shell run-as com.taskrx.mobile sqlite3 /data/data/com.taskrx.mobile/databases/taskrx.db "SELECT COUNT(*) FROM sync_queue;"
```

#### Expected result

- The query returns **0**.
- All pending operations were successfully pushed to Convex and removed from the queue.

#### Pass / fail

- [ ] **PASS** — Sync queue is empty after sync.
- [ ] **FAIL** — Entries remain in the queue (sync worker did not drain them).

#### Notes

```text
Sync queue count after restore:
```

---

## Section 3: Result Summary Template

| Scenario | Expected | Actual | Pass / Fail | Notes |
|---|---|---|---|---|
| 1.1 Verify schedule registration (30 meds) | Pending notifications = 300 (30 × 10) | | | |
| 1.2 Normal operation | All observed notifications fire on time with correct med name | | | |
| 1.3 Doze mode | Notification fires despite Doze | | | |
| 1.4 Force-stop | Notification fires after `am force-stop` | | | |
| 1.5 Add med while running | `rescheduleAll()` includes new med without dropping existing ones | | | |
| 1.6 Clear test data | Pending notifications drop to 0 | | | |
| 2.0 Offline setup | 3 meds visible in Convex dashboard | | | |
| 2.1 Offline writes | 4 meds + 5 dose logs written locally with no errors | | | |
| 2.2 Sync queue | ~6 entries queued while offline | | | |
| 2.3 Restore network | Sync completes within 60s; data visible in Convex | | | |
| 2.4 Queue drained | Sync queue count returns to 0 | | | |

### Day 8 stress test verdict

**Day 8 stress test: PASS / FAIL** — proceed to Day 9 if PASS, revisit Day 4 (notifications) or Day 7 (sync) if FAIL.

> The exact-alarm stress test passes if **Sections 1.1, 1.2, 1.3, and 1.4 all pass**. Section 1.5 and 1.6 are strongly recommended but not blockers.
> The offline-first stress test passes if **Sections 2.1, 2.3, and 2.4 all pass**.
