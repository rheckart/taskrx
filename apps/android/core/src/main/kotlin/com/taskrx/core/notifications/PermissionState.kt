package com.taskrx.core.notifications

/*
 * Pure exact-alarm permission derivation used by the app's permissions module.
 *
 * Kept in `:core` (rather than beside the Android adapter) so it can be
 * unit-tested without a platform. The adapter is a thin wrapper that maps what
 * the OS reports onto the numeric values defined here.
 *
 * On Android < 12 the exact-alarm concept did not exist. That is represented
 * here by [ANDROID_ALARM_NOT_SUPPORTED], a granted-equivalent state.
 *
 * The raw settings stay `Int` rather than enums on purpose: the derivation below
 * must give a defined answer (`undetermined`) for a value nobody recognises, and
 * an enum would make that case unrepresentable instead of handled.
 */

const val ANDROID_ALARM_NOT_SUPPORTED: Int = -1
const val ANDROID_ALARM_DISABLED: Int = 0
const val ANDROID_ALARM_ENABLED: Int = 1

const val NOTIFICATION_AUTH_NOT_DETERMINED: Int = -1
const val NOTIFICATION_AUTH_DENIED: Int = 0
const val NOTIFICATION_AUTH_AUTHORIZED: Int = 1
const val NOTIFICATION_AUTH_PROVISIONAL: Int = 2

enum class PermissionStatus(val wire: String) {
    Authorized("authorized"),
    Denied("denied"),
    Partial("partial"),
    Undetermined("undetermined"),
}

data class PermissionSnapshotInput(
    /** One of the `NOTIFICATION_AUTH_*` values. */
    val notificationAuthorization: Int,
    /** One of the `ANDROID_ALARM_*` values; null when the platform does not expose an alarm setting. */
    val androidAlarm: Int?,
)

data class PermissionSnapshot(
    val status: PermissionStatus,
    val notificationGranted: Boolean,
    val exactAlarmGranted: Boolean,
)

/**
 * Returns true when the app is permitted to schedule exact alarms. When the
 * platform exposes no setting ([alarm] is null) and on Android < 12
 * ([ANDROID_ALARM_NOT_SUPPORTED]), this returns true because the exact-alarm
 * restriction does not apply. On Android 12+ only [ANDROID_ALARM_ENABLED] counts
 * as granted.
 */
fun isExactAlarmGranted(alarm: Int?): Boolean {
    if (alarm == null) {
        return true
    }
    return alarm != ANDROID_ALARM_DISABLED
}

/**
 * Derives the normalized permission state used by the app's UI from the raw
 * numeric values the platform reports. [PermissionStatus.Partial] means the OS
 * granted notification permission but exact-alarm permission is denied —
 * reminders will still be delivered but timing may drift (the alarm falls back
 * to an inexact one).
 */
fun derivePermissionStatus(input: PermissionSnapshotInput): PermissionSnapshot {
    val exactAlarmGranted = isExactAlarmGranted(input.androidAlarm)
    val notificationGranted =
        input.notificationAuthorization == NOTIFICATION_AUTH_AUTHORIZED ||
            input.notificationAuthorization == NOTIFICATION_AUTH_PROVISIONAL

    // Written as a chain rather than a `when` with an `else` arm, because the
    // arm this replaces was both unreachable and wrong in the same direction the
    // POC's hardcoded `true` was: it read an authorization value outside the
    // known set as authorized. A value nobody recognises means the app does not
    // know whether it may notify, and "does not know" is `Undetermined` — which
    // asks, where the old answer would have gone on believing it had permission.
    val status =
        if (notificationGranted) {
            if (exactAlarmGranted) PermissionStatus.Authorized else PermissionStatus.Partial
        } else if (input.notificationAuthorization == NOTIFICATION_AUTH_DENIED) {
            PermissionStatus.Denied
        } else {
            PermissionStatus.Undetermined
        }

    return PermissionSnapshot(status, notificationGranted, exactAlarmGranted)
}

/**
 * What the app can do about a permission the person has not granted.
 *
 * [Request] is the OS prompt; the other two are Settings screens, because once
 * a runtime permission has been refused the prompt is spent — Android 13+
 * answers a repeat request without showing anything.
 */
enum class PermissionRemedy(val wire: String) {
    Request("request"),
    NotificationSettings("notification-settings"),
    AlarmSettings("alarm-settings"),
}

/**
 * [Blocking] means no reminder reaches the person at all. [Degraded] means they
 * arrive, late.
 *
 * The distinction is the whole reason this is two words rather than a boolean:
 * an app that describes a late reminder in the same terms as a missing one
 * teaches people to read past the message that matters.
 */
enum class PermissionSeverity(val wire: String) {
    Blocking("blocking"),
    Degraded("degraded"),
}

data class PermissionGap(
    val severity: PermissionSeverity,
    val title: String,
    val body: String,
    val remedy: PermissionRemedy,
    val remedyLabel: String,
)

data class PermissionGateInput(
    val snapshot: PermissionSnapshot,
    /**
     * Whether this run of the app has already put the OS prompt in front of the
     * person.
     *
     * Not derivable from the snapshot, and that is the point: `undetermined`
     * before the prompt and `undetermined` after a dismissed prompt are the same
     * platform state and call for opposite behaviour — ask, then stop asking.
     */
    val asked: Boolean,
)

/**
 * Whether to show the OS notification prompt now.
 *
 * True in exactly one state: nothing has been decided and nothing has been
 * asked. Every other answer is either already settled or no longer askable —
 * a refused runtime permission cannot be re-prompted on Android 13+, so a
 * second request would return "denied" without the person seeing anything, and
 * an app that read that as a fresh refusal would be inventing an answer nobody
 * gave.
 */
fun shouldRequestNotificationPermission(input: PermissionGateInput): Boolean =
    input.snapshot.status == PermissionStatus.Undetermined && !input.asked

/**
 * What to tell the person, when the permissions they hold mean a reminder
 * cannot arrive on time — or `null` when nothing needs saying.
 *
 * The ordering is severity-first: a missing POST_NOTIFICATIONS makes every
 * reminder vanish, so it is named before a missing exact alarm, which only
 * makes them late. Issue #20 exists because the first of those was happening
 * to every fresh install with no error, no empty state and no warning; a gap
 * this returns is a gap somebody can see and act on.
 */
fun derivePermissionGap(input: PermissionGateInput): PermissionGap? {
    val snapshot = input.snapshot

    if (snapshot.status == PermissionStatus.Denied) {
        return PermissionGap(
            severity = PermissionSeverity.Blocking,
            title = "Reminders are turned off",
            // No operating system is named here, and that is a rule rather than a
            // style choice: the shared half of this app must stay portable, and
            // `.omo/plans/convex-expo-revisit.md` keeps platform-only concerns
            // behind the notifications boundary so another client can be added
            // alongside rather than by surgery. "Android is discarding your
            // reminders" would be false anywhere else.
            body = "Notifications are switched off for this app, so every reminder it posts is discarded. " +
                "Doses and tasks are still scheduled, and none of them will reach you.",
            remedy = PermissionRemedy.NotificationSettings,
            remedyLabel = "Open notification settings",
        )
    }

    if (snapshot.status == PermissionStatus.Undetermined) {
        // Before the prompt this is not a gap, it is a question about to be asked.
        if (!input.asked) {
            return null
        }
        return PermissionGap(
            severity = PermissionSeverity.Blocking,
            title = "Reminders are turned off",
            body = "Notifications have not been allowed yet, so nothing this app schedules will reach you.",
            remedy = PermissionRemedy.Request,
            remedyLabel = "Allow notifications",
        )
    }

    if (!snapshot.exactAlarmGranted) {
        return PermissionGap(
            severity = PermissionSeverity.Degraded,
            title = "Reminders may arrive late",
            body = "Exact alarms are turned off for this app, so a reminder can be held back by the system " +
                "until the phone is next awake. It will still arrive.",
            remedy = PermissionRemedy.AlarmSettings,
            remedyLabel = "Open alarm settings",
        )
    }

    return null
}
