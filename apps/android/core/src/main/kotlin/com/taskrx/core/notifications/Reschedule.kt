package com.taskrx.core.notifications

/*
 * The reschedule diff: "diff, don't rebuild".
 *
 * Both Tiers are diffed in ONE pass over the union of what is armed. A pass that knew
 * about one Tier only would compute the other's notifications as stale and cancel them.
 */

/** A notification currently armed, as the app's own record remembers it. */
data class ArmedNotification(
    val id: String,
    /** The Tier whose subsystem holds it. Not derivable from the id: a snooze's id carries no Kind. */
    val tier: Tier,
    /** Armed with a weaker mechanism than the Tier's own because exact alarms were denied at the time. */
    val degraded: Boolean = false,
)

data class ReschedulePlan(
    /** Ids armed that no desired set contains. */
    val cancel: List<String>,
    /** Specs not yet armed, doses first. */
    val create: List<NotificationSpec>,
)

/**
 * What to cancel and what to create so that exactly [desired] is armed.
 *
 *  - Cancel only what is in neither desired set. Snoozes are not schedule-derived, so
 *    they are never in [desired] and must be excluded explicitly or merely reopening the
 *    app would cancel a deferred reminder.
 *  - Create only what is missing. Calling this twice with unchanged data yields an empty
 *    plan - the idempotence the cancel/create churn window depends on.
 *  - Everything armed on a Tier in [rearm] is created again, in place, whatever the record
 *    says: after a reboot the record is a memory of alarms the system no longer holds. Such
 *    entries are still cancellable, so a stale one cannot outlive its reminder.
 *  - A dose armed in a degraded (inexact) form is created again once [exactAllowed] is
 *    true, replacing it in place: "recheck, reschedule, degrade if denied" is a cycle,
 *    and the permission being granted later is the step that closes it.
 */
fun planReschedule(
    desired: List<NotificationSpec>,
    armed: List<ArmedNotification>,
    exactAllowed: Boolean,
    rearm: Set<Tier> = emptySet(),
): ReschedulePlan {
    val desiredIds = desired.map { it.id }.toSet()
    val cancel = armed.map { it.id }.filter { it !in desiredIds && !it.startsWith(SNOOZE_ID_PREFIX) }

    val armedById = armed.associateBy { it.id }
    val create =
        desired.filter { spec ->
            val current = armedById[spec.id]
            current == null ||
                current.tier in rearm ||
                (current.degraded && exactAllowed && spec.tier == Tier.Medication)
        }
    return ReschedulePlan(cancel, create)
}

/** What caused the app to re-arm. */
enum class RearmCause {
    Boot,
    PackageReplaced,
    TimeChanged,
    ExactAlarmPermissionChanged,
}

/** The system broadcasts that mean the armed state may be stale; null for anything else. */
fun rearmCauseOf(action: String?): RearmCause? =
    when (action) {
        "android.intent.action.BOOT_COMPLETED" -> RearmCause.Boot
        "android.intent.action.MY_PACKAGE_REPLACED" -> RearmCause.PackageReplaced
        "android.intent.action.TIME_SET", "android.intent.action.TIMEZONE_CHANGED" -> RearmCause.TimeChanged
        "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> RearmCause.ExactAlarmPermissionChanged
        else -> null
    }

/**
 * Which Tiers' armed records can no longer be trusted after [cause], so the next
 * reschedule must create everything on them again.
 *
 *  - Reboot and app update both clear every alarm AlarmManager holds, but WorkManager
 *    persists its own queue across both.
 *  - A wall-clock or zone change shifts a work request's relative delay, so the task
 *    Tier is re-enqueued as well.
 *  - A permission change loses nothing: the diff itself upgrades degraded alarms.
 */
fun tiersToRearm(cause: RearmCause): Set<Tier> =
    when (cause) {
        RearmCause.Boot, RearmCause.PackageReplaced -> setOf(Tier.Medication)
        RearmCause.TimeChanged -> setOf(Tier.Medication, Tier.Task)
        RearmCause.ExactAlarmPermissionChanged -> emptySet()
    }

/**
 * The stored snoozes to arm again after [cause]: those on a lost Tier that are still in
 * the future. A snooze is armed by the person, not derived from a schedule, so the diff
 * cannot recreate it - without this a reboot silently forgot every deferred reminder.
 */
fun snoozesToRearm(stored: List<NotificationSpec>, cause: RearmCause, now: Long): List<NotificationSpec> {
    val lost = tiersToRearm(cause)
    return stored.filter { it.tier in lost && it.fireAt > now }
}
