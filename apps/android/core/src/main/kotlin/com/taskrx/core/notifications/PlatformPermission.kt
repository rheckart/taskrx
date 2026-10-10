package com.taskrx.core.notifications

/*
 * Maps what Android reports onto the numeric values permission-state.ts derives from.
 * Kept pure so the mapping - which has a real trap in it - runs without a platform.
 */

/** Exact alarms are a runtime concept from API 31; before it they are granted-equivalent. */
const val EXACT_ALARM_MIN_SDK: Int = 31

/** POST_NOTIFICATIONS is a runtime permission from API 33. */
const val POST_NOTIFICATIONS_MIN_SDK: Int = 33

fun androidAlarmSetting(sdkInt: Int, canScheduleExactAlarms: Boolean): Int =
    when {
        sdkInt < EXACT_ALARM_MIN_SDK -> ANDROID_ALARM_NOT_SUPPORTED
        canScheduleExactAlarms -> ANDROID_ALARM_ENABLED
        else -> ANDROID_ALARM_DISABLED
    }

/**
 * Android never says "you have not asked yet": a runtime permission that is not granted
 * looks identical before the first prompt and after a refusal. [promptShownBefore] is the
 * app's record that the OS prompt was raised at least once - a fact about the past, not a
 * cached verdict about the permission, which is re-read from the platform every time.
 *
 * Notifications switched off in Settings count as denied even where the permission is
 * granted (or does not exist, before API 33): the framework discards every post either way.
 */
fun notificationAuthorization(
    sdkInt: Int,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
    promptShownBefore: Boolean,
): Int {
    val granted = sdkInt < POST_NOTIFICATIONS_MIN_SDK || permissionGranted
    return when {
        granted && notificationsEnabled -> NOTIFICATION_AUTH_AUTHORIZED
        granted -> NOTIFICATION_AUTH_DENIED
        promptShownBefore -> NOTIFICATION_AUTH_DENIED
        else -> NOTIFICATION_AUTH_NOT_DETERMINED
    }
}
