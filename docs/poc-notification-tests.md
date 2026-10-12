# Day 6 — POC Notification Tests (Pixel 8)

> Historical. Written for the Expo app, whose paths below (`apps/mobile/...`) now live on the `archive/expo-rn` branch. The current harness is `apps/android/scripts/notification-harness.sh`.

This document is the test script for the Day 6 gate: verifying that local medication reminders fire reliably on a physical Android device (Pixel 8) under a variety of power and lifecycle states.

> **Next step:** After completing these 6 scenarios, proceed to [`docs/poc-stress-tests.md`](./poc-stress-tests.md) for the Day 8 exact-alarm and offline-first stress tests.

> **Goal:** Confirm the React Native + Expo + Notifee stack can deliver time-critical, offline-first medication reminders on Android.
> **Device:** Google Pixel 8 (physical), USB debugging enabled.
> **APK:** `apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk`
> **Package name:** `com.taskrx.mobile`

---

## Section 0: Setup prerequisites

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

---

## Section 1: Basic test — App open, notification fires when scheduled

### Steps

1. Open TaskRx and sign in.
2. Tap **Add medication**.
3. Name it `Test Med`.
4. Set schedule to **Daily** at a time **2–3 minutes from now** (use the phone's local clock).
5. Save the medication.
6. Lock the Pixel 8 (press power button).
7. Wait until the scheduled time.

### Expected result

- A notification appears at the scheduled minute.
- Notification title: `Test Med`
- Notification body includes `Time to take`
- The phone vibrates / makes sound depending on volume.

### Pass / fail

- [ ] **PASS** — Notification fired on time.
- [ ] **FAIL** — Notification did not fire, fired late, or had wrong content.

### Notes

```text
Scheduled time:
Actual fire time:
Delay (minutes):
Content correct? (title/body):
```

---

## Section 2: App force-killed test

### Steps

1. Open TaskRx.
2. Add or edit a medication scheduled **2–3 minutes from now**.
3. Force-stop the app:

```bash
adb shell am force-stop com.taskrx.mobile
```

Alternatively: **Settings → Apps → TaskRx → Force stop**.

4. Lock the device.
5. Wait until the scheduled time.

### Expected result

- Notification still fires at the scheduled minute.
- Android's AlarmManager persists exact alarms across force-stop for apps that hold `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` and are whitelisted from battery optimization.

### Pass / fail

- [ ] **PASS** — Notification fired after force-stop.
- [ ] **FAIL** — Notification did not fire.

### Notes

```text
Force-stop method used:
Notification fired? Y/N
If no, check exact-alarm permission and battery whitelist.
```

---

## Section 3: Do Not Disturb test

### Steps

1. Enable Do Not Disturb:

```bash
adb shell settings put global zen_mode 1
```

Or pull down the notification shade and tap **Do Not Disturb**.

2. Open TaskRx and schedule a medication **2–3 minutes from now**.
3. Lock the device.
4. Wait until the scheduled time.

### Expected result

- Notification fires and interrupts Do Not Disturb.
- The `medication_reminders` channel is created with HIGH importance, which bypasses DND.

### Pass / fail

- [ ] **PASS** — Notification fired during DND.
- [ ] **FAIL** — Notification was suppressed.

### Notes

```text
DND enabled via:
Notification fired? Y/N
Volume / visual alert present? Y/N
```

---

## Section 4: Low Power Mode test

### Steps

1. Enable Battery Saver:

```bash
adb shell settings put global low_power 1
```

Or open **Settings → Battery → Battery Saver** and turn it on.

2. Open TaskRx and schedule a medication **2–3 minutes from now**.
3. Lock the device.
4. Wait until the scheduled time.

### Expected result

- Notification fires at the scheduled minute.
- Notifee uses `AlarmManager.setExactAndAllowWhileIdle`, which is allowed to fire during Battery Saver for battery-optimized apps.

### Pass / fail

- [ ] **PASS** — Notification fired during Battery Saver.
- [ ] **FAIL** — Notification did not fire or was significantly delayed.

### Notes

```text
Battery Saver enabled via:
Notification fired? Y/N
Delay (minutes):
```

---

## Section 5: Doze mode test

### Steps

1. Force the device into Doze mode:

```bash
adb shell dumpsys deviceidle force-idle
```

2. Open TaskRx and schedule a medication **5+ minutes from now**.
3. Lock the device.
4. Wait until the scheduled time.

### Expected result

- Notification fires despite Doze.
- If it fails, the workaround is the battery-optimization whitelist applied in Section 0.4.

### Pass / fail

- [ ] **PASS** — Notification fired while device was idle/Dozing.
- [ ] **FAIL** — Notification did not fire.

### Notes

```text
Doze forced via adb? Y/N
Scheduled time:
Actual fire time:
Delay (minutes):
```

---

## Section 6: Tap → dose log test

### Steps

1. Schedule a medication **1–2 minutes from now**.
2. Lock the device.
3. When the notification fires, **tap the notification body** (not an action button).
4. Verify the app opens to the dose logging screen.
5. Tap **Mark Taken**.
6. Navigate to the dose history screen.

### Expected result

- App opens to `/dose/log?medicationId=...&scheduledTime=...`.
- The screen shows the correct medication name and scheduled time.
- Tapping **Mark Taken** records the dose.
- Dose history shows a new `taken` entry for the scheduled time.

### Pass / fail

- [ ] **PASS** — Tap navigated correctly and dose was recorded.
- [ ] **FAIL** — Wrong screen, missing data, or dose not recorded.

### Notes

```text
Medication shown:
Scheduled time shown:
Mark Taken succeeded? Y/N
Visible in history? Y/N
```

---

## Section 7: Result summary template

| Scenario | Expected | Actual | Pass / Fail | Notes |
|---|---|---|---|---|
| 1. Basic (app open, locked) | Notification fires on time with correct title/body | | | |
| 2. App force-killed | Notification fires after force-stop | | | |
| 3. Do Not Disturb | Notification bypasses DND | | | |
| 4. Low Power Mode | Notification fires during Battery Saver | | | |
| 5. Doze mode | Notification fires while idle/Doze | | | |
| 6. Tap → dose log | Opens dose log, Mark Taken records history | | | |

### Day 6 gate verdict

**Day 6 gate: PASS / FAIL** — proceed to Day 7 if PASS.

> The gate passes if **at least 4 of the 5 notification reliability scenarios (Sections 1–5) pass** and **Section 6 (tap → dose log) passes**. If exact-alarm permission is denied, document the fallback behavior (inexact alarms may jitter ±15 minutes on Android 12+).
