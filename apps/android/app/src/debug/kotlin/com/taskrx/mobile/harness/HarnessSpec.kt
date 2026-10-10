package com.taskrx.mobile.harness

/**
 * Every action the adb notification harness can ask for. The wire word is the path segment
 * the script sends (`apps/android/scripts/notification-harness.sh`), unchanged from the
 * Expo app's so the script needs no edit to name one.
 *
 * Two families, and the split is not cosmetic:
 *  - **Local-only** (`Schedule` ... `DbProbe`) run on an install nobody is signed in to, which
 *    is what lets cases 1-7 and 10-11 be the delivery baseline on any device.
 *  - **Signed-in** (`AuthStatus`, `SeedDueReminder`, `InstanceAt`, `CleanupSeeded`,
 *    `PurgeSeeded`) act as an authenticated user and refuse, writing nothing, when there is
 *    none. `QueueCount` and `SyncStatus` answer either way.
 */
enum class HarnessAction(val wire: String) {
    Schedule("schedule"),
    ScheduleWeekly("schedule-weekly"),
    ScheduleTask("schedule-task"),
    ScheduleTaskFlood("schedule-task-flood"),
    CancelAll("cancel-all"),
    PendingCount("pending-count"),
    PendingIds("pending-ids"),
    ExactAlarmStatus("exact-alarm-status"),
    NotificationStatus("notification-status"),
    DbProbe("db-probe"),
    AuthStatus("auth-status"),
    SeedDueReminder("seed-due-reminder"),
    InstanceAt("instance-at"),
    QueueCount("queue-count"),
    SyncStatus("sync-status"),
    CleanupSeeded("cleanup-seeded"),
    PurgeSeeded("purge-seeded"),
}

data class HarnessSpec(
    val action: HarnessAction,
    val id: String,
    /** The third segment as a RELATIVE delay, floored at one second so no alarm is armed in the past. */
    val delayMs: Long,
    /** The same segment unclamped: `instance-at` reads it as an absolute epoch, where the floor is meaningless. */
    val value: Long,
    val nonce: String,
)

private const val DEFAULT_VALUE_MS = 90_000L
private const val MIN_DELAY_MS = 1_000L
private val LEADING_INTEGER = Regex("^\\s*[+-]?\\d+")

/**
 * Parses `[action, id, delayMs, nonce]` path segments, tolerating any missing tail.
 * Unknown actions fall back to `pending-count`, as they always have, and a delay that is
 * not a number to 90 seconds. The number is read like JavaScript's `parseInt` did: leading
 * digits count, the rest is ignored.
 */
fun parseHarnessSpec(segments: List<String>, nowMs: Long): HarnessSpec {
    val action = HarnessAction.entries.firstOrNull { it.wire == segments.getOrNull(0) } ?: HarnessAction.PendingCount
    val id = segments.getOrNull(1) ?: "harness-$nowMs"
    val value = segments.getOrNull(2)?.let { LEADING_INTEGER.find(it)?.value?.trim()?.toLongOrNull() } ?: DEFAULT_VALUE_MS
    return HarnessSpec(action, id, maxOf(MIN_DELAY_MS, value), value, segments.getOrNull(3) ?: "")
}
