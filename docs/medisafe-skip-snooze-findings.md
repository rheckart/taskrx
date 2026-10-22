# Medisafe: how skip, snooze and missed work

Source: `com.medisafe.android.client` base.apk (the copy in `~/Projects/apks/`), read statically. Nothing was run.

**How it was read.** The REA Android tools need a JADX MCP JAR and apktool. Neither is installed, so those tools did not run. Instead: `unzip`, `aapt2 dump resources` for strings and arrays, and `dexdump -d` for bytecode. The bytecode was read by hand, so the values below are exact, but the surrounding logic is my reading of it. Class names are in the app's own package, `com.medisafe.android.base`.

## What Medisafe does

Status values stored on a dose (`ScheduleItem.status`): `pending`, `taken`, `snooze`, `dismissed` (this is "skip"), `missed`, `deleted`.

### Snooze (`actions/ActionSnoozeItems`)
- New time = now + snooze minutes. The setting is `snooze_min`, **default 10**, titled "Snooze duration". Its picker offers 5, 10, 15, 30, 60, 90 and 120 minutes (confirmed in `res/xml/preferences_layout_med_reminders.xml`).
- Sets status `snooze`, moves the dose's actual time, **resets the dose's reminder counter to 0** (the field is called `snoozeCounter`), and clears `lastReminderAt`. A manual snooze therefore restarts the reminder cycle.
- Remembers where the snooze came from (`SOURCE_MANUAL_FROM_REMINDER` for the notification, `SOURCE_MANUAL` for the in-app reschedule).
- Works on one dose or a whole group at once ("Snooze All"). It logs "snooze one" or "snooze all".
- Cancels the notification with the fixed id 3. I did not work out what that notification is.

### Reschedule (`actions/ActionRescheduleItems`)
- Same state change as snooze, but to a time the person picks. Strings: "Reschedule for", "%1$s meds rescheduled for %2$s".
- Skips doses whose medication group is no longer active.
- There is a "Reschedule all" confirm: "Reschedule all %1$s meds?"

### Skip (`actions/ActionDismissItems`)
- Status `dismissed`. Actual time becomes now. Extra data on the dose is wiped.
- Stores **where the skip came from** in the dose's metadata JSON (`source`).
- Records how many minutes early or late the skip was, against the planned time.
- Skip has an **Un-skip** button. Skipping all asks first: "Skip all %1$s meds?"
- **Skip survey** (`SurveyManager.showSkipSurvey`): "Can you please indicate why you're skipping this dose?" Reasons:
  - Med isn't near me
  - Ran out of the med
  - Forgot / busy / asleep
  - Don't need to take this dose
  - Worried about the cost
  - Side effects / other health concerns
  - Other

  It does not show every time. It has three limits: a count of skip presses, a maximum times shown, and a minimum days since last shown. Those limits come from config, so I could not read the real numbers. The answer is saved to a report. Toast: "Thanks! Your response has been saved in your report for your records."

### Take all (`bulk_actions_take_all_warning_*`)
- "Take all %1$s meds?" with the warning "This will override any previously marked medications. Alternatively, you can mark each med separately."
- Result toast is pluralised: "N meds taken / skipped / snoozed / rescheduled".

### Nagging and missed (`actions/ActionOnNotificationReceived`, `Config`)
- Each time a reminder fires, the dose's `snoozeCounter` goes up by 1 and `lastReminderAt` is set (`markItemsAsNotified`, read directly). So the counter means "reminders fired so far". A manual snooze sets it back to 0.
- Setting `max_alarms`, titled "Max reminders per medication", **default 4**, picker 1 to 10.
- **Inference, not read directly:** the app re-rings on its own, one ring every `snooze_min` minutes, up to `max_alarms` rings. I did not read the re-ring scheduling code. The evidence is the counter above, `max_alarms`, "Show popup on last reminder" (default on), and the window math below.
- Window = `snooze_min × (max_alarms − 1)` = 30 minutes at the defaults (`getMaxAutoSnoozeMs`, read directly).
- A dose that is still `pending` or `snooze` and older than that window becomes `missed`. The query looks only at doses between 24 hours ago and the window edge, so older ones are not touched by this pass.
- This sweep runs only inside `ActionOnNotificationReceived`. A dose turns `missed` when a later notification arrives, not on a timer.
- Other settings on the same screen: popup notification, show med names, and "shake to take" (default off).
- Doses missed while the phone was off are collected and shown when it turns back on ("You've missed doses since you've turned the device off"). A setting, "Missed Doses Reminder", turns this on or off.
- The taken-dose dialog shows "Last taken at %1$s" and, for an unanswered dose, "You haven't taken any action for this dose. Consider if it's safe to take now."

## Compared with TaskRx today

| Thing | Medisafe | TaskRx |
|---|---|---|
| Snooze length | Setting, 5–120 min, default 10 | Fixed 15 (`SNOOZE_MINUTES`, `format/AnswerWords.kt:7`) |
| Reschedule to a chosen time | Yes | No |
| Skip reason | Optional survey, rate-limited | None |
| Un-skip / undo | Yes | None (grep for undo/revert found nothing) |
| Auto re-ring if ignored | Yes, up to 4 rings | No |
| Missed | After ~30 min of nagging | After a 2 hour grace period (ADR 0005) |
| Per-dose counter | `snoozeCounter` = reminders fired; reset to 0 by a manual snooze | `snooze_count` increments per snooze (`ReminderInstancesRepository.kt:148`) |
| Take/skip/snooze all | Yes, with confirm | Yes (batch notification, Today's slot buttons) |
| Missed while phone off | Summary on boot | `core/notifications/DueWhileAway.kt` covers this |

## What I would copy, in order

1. **Snooze length setting.** Small. 5/10/15/30/60 minutes, default 15 so nothing changes for you today.
2. **"Pick a time" reschedule.** Reuses the snooze path, with a time picker.
3. **Un-skip / undo** after a skip or snooze.
4. **Optional skip reason.** One tap, never required, stored on the instance. Do not copy the rate limiting at first; ask only on medications, not tasks.
5. **Auto re-ring.** Biggest change. It touches AlarmManager scheduling and the missed rule, so it needs an ADR. Medisafe's "missed after 30 min" is much tighter than your 2 hour grace, so do not copy that number.

## What I would not copy
- Wiping the dose's extra data on skip or miss. You keep notes and history.
- Reusing the name `snoozeCounter`. Theirs counts reminders fired. Yours counts snoozes. If you add auto re-ring, keep a separate re-ring count and reset it on a manual snooze, as they do.

## Not covered
- Real config values for the skip-survey limits (remote config).
- The notification action buttons' exact layout and the take dialog's flow. Strings were read, UI code was not.
- Anything server-side. This was the APK only.
- JADX output. If you install the JADX MCP JAR and set `REA_JADX_MCP_JAR`, the REA tools can read the same classes as Java.
