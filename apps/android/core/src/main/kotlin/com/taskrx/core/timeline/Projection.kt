package com.taskrx.core.timeline

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.schedule.nextOccurrences
import java.time.ZoneId

/*
 * The timeline projection: what a person's day looks like, as one ordered list.
 *
 * This is the pure rule `docs/specs/fused-timeline.md` names — "given the
 * person's Reminders, their stored Reminder Instances, a window, and the current
 * instant, it returns the rows the timeline renders". The screen renders what
 * this returns and decides nothing itself.
 *
 * ## Why it is a union and not a query
 *
 * The two halves of a day are different kinds of thing. An **Occurrence** is
 * computed from a Schedule, has no identity, and is never stored. An
 * **Instance** is a stored record of what became of one. A day therefore has to
 * be assembled from both: moments the Schedule predicts and nobody has answered
 * yet, plus moments that have a recorded answer.
 *
 * Both can describe the same moment, so they are deduplicated on the pairing of
 * **Reminder and scheduled time**, which is the key the lazy-lookup path in
 * `reminderInstancesRepo.findAnswerableForOccurrence` already uses. Where both
 * exist the Instance wins: a stored answer is a fact, and the Occurrence is only
 * a prediction that it would come due — including an Instance at a moment the
 * Schedule does not predict at all, such as one recorded after the fact.
 *
 * Pairing on the pair matters in both directions. On the Reminder alone, a
 * second Occurrence of the same Reminder would swallow the first. On the moment
 * alone, one Reminder's answer would settle another Reminder's Occurrence.
 *
 * ## What this deliberately does not decide
 *
 * It does not mint Instances, and it does not decide that an unanswered
 * Occurrence has become Missed — that is the sweep planner's job, under the
 * Grace Period ADR 0005 settled. An Occurrence whose moment has passed with no
 * answer therefore stays `pending` here and simply is not "next", until a sweep
 * has run and left a `missed` Instance for this projection to find. Nothing in
 * this file applies a Grace Period of its own.
 *
 * It is also Kind-neutral, and since #13 that is load-bearing rather than
 * anticipatory: the home surface hands it both Kinds and renders the one list
 * that comes back. A Kind filter or a Kind-aware sort appearing below would
 * unmake the fusion, which is why the cases pin the property rather than the
 * shipped shape.
 *
 * Pure: no clock, no database, no globals. The caller supplies `now`.
 */

/**
 * The span the timeline covers, both bounds **inclusive**, in Unix ms.
 *
 * Inclusive at the lower bound is the load-bearing half: an Occurrence at 00:00
 * belongs to the day beginning then, and an exclusive bound would drop it from
 * every day it could appear on.
 */
data class TimelineWindow(val start: Long, val end: Long)

data class TimelineRow(
    val reminderId: String,
    /** `null` until the moment has been answered and an Instance exists. */
    val instanceId: String?,
    val name: String,
    val dosage: String?,
    val kind: ReminderKind,
    val scheduledTime: Long,
    /** `pending` for an Occurrence with no Instance behind it. */
    val status: InstanceStatus,
    /**
     * The moment a `snoozed` Instance was deferred to; `null` for every other
     * status.
     *
     * Carried, not acted on. The row stays at [scheduledTime] — that is the
     * instant it occupies on the timeline (ADR 0002) and the half of the
     * deduplication key that makes a moment identifiable, so moving it would
     * unmake the pairing every stored answer is found by. This exists so a
     * surface can say *when* the person deferred it to instead of an
     * unqualified "Snoozed", which is all a snooze amounts to for a Kind whose
     * Tier has no trigger yet.
     */
    val snoozedUntil: Long?,
    /**
     * The one entry the person should act on now: the soonest unanswered moment
     * at or after `now`. At most one row carries it, and none does on a day
     * whose remaining moments are all answered.
     */
    val isNext: Boolean,
    /**
     * When a `completed` Instance was recorded as done; `null` for every other status and
     * for an Occurrence with no Instance. Carried so a row can say when the dose was
     * taken, and so a surface can offer to change it.
     */
    val completedAt: Long? = null,
)

data class ProjectTimelineInput(
    /**
     * Every Reminder that could contribute to the window. Only `active` ones
     * predict new moments; an inactive one still supplies the name and dosage
     * for moments already recorded against it, because stopping a medication
     * does not unmake the Instances already recorded.
     */
    val reminders: List<Reminder>,
    val instances: List<ReminderInstance>,
    val window: TimelineWindow,
    val now: Long,
    /** The zone the Schedules' wall-clock times are read in. */
    val zone: ZoneId = ZoneId.systemDefault(),
)

/**
 * The most moments one Reminder may contribute to one window.
 *
 * A bound is needed because `nextOccurrences` counts rather than ranges, and an
 * `interval` schedule has no horizon of its own. 64 is far past anything a real
 * day holds — one every 22 minutes — so a window is never truncated in practice.
 * A caller projecting a span long enough to exceed it would see the tail
 * missing, which is why this is stated rather than buried.
 *
 * It coincides with the engine's own `DEFAULT_COUNT` without being tied to it:
 * that one is "how many to return when the caller does not say", this one is
 * "how many can fit in a day". They answer different questions and should be
 * free to diverge.
 */
private const val MAX_OCCURRENCES_PER_REMINDER = 64

/**
 * Reminder and scheduled time together — the deduplication key, and the same
 * pairing a caller needs to key a rendered list. Public so the two cannot
 * drift into disagreeing about what makes a row unique.
 */
fun timelineRowKey(reminderId: String, scheduledTime: Long): String = "$reminderId@$scheduledTime"

private fun withinWindow(moment: Long, window: TimelineWindow): Boolean =
    moment >= window.start && moment <= window.end

/**
 * Which of two Instances claiming the same pairing is the one to show.
 *
 * Two should not exist. One does, today: answering mints a duplicate `pending`
 * Instance beside the `completed` one — recorded in `docs/CHANGELOG.md` on
 * 2026-08-03, still open, and found here because a device rendered the same
 * moment twice and raised "two children with the same key".
 *
 * The pairing is what identifies a moment on the timeline, so it has to hold
 * across stored rows as well as between stored and predicted ones. Resolving it
 * is not a fix for the duplicate — the rows are still both written and both
 * counted by adherence — but the timeline showing a taken medication as
 * simultaneously taken and outstanding is a defect of its own.
 *
 * An answer beats `pending`, because a recorded answer is a fact and `pending`
 * is only the absence of one. Between two answers the later `updatedAt` wins,
 * and an exact tie falls to the instance id so the row cannot flicker between
 * renders of identical data.
 */
private fun preferredInstance(a: ReminderInstance, b: ReminderInstance): ReminderInstance {
    val aAnswered = a.status != InstanceStatus.Pending
    val bAnswered = b.status != InstanceStatus.Pending
    if (aAnswered != bAnswered) return if (aAnswered) a else b
    if (a.updatedAt != b.updatedAt) return if (a.updatedAt > b.updatedAt) a else b
    return if (a.id < b.id) a else b
}

fun projectTimeline(input: ProjectTimelineInput): List<TimelineRow> {
    val (reminders, instances, window, now, zone) = input

    val byId = reminders.associateBy { it.id }

    // Stored answers first, so they own their moment before any prediction is
    // considered for it. Keyed by the pairing rather than appended, so two
    // Instances claiming one moment collapse to the one that should be shown.
    val claimed = LinkedHashMap<String, Pair<ReminderInstance, Reminder>>()
    for (instance in instances) {
        if (!withinWindow(instance.scheduledTime, window)) continue

        // Nothing to render it with. Inventing a name would put a row on the
        // timeline that names nothing.
        val reminder = byId[instance.reminderId] ?: continue

        val key = timelineRowKey(instance.reminderId, instance.scheduledTime)
        val held = claimed[key]
        claimed[key] =
            Pair(if (held == null) instance else preferredInstance(held.first, instance), reminder)
    }

    val rows = ArrayList<TimelineRow>()
    for ((instance, reminder) in claimed.values) {
        rows.add(
            TimelineRow(
                reminderId = reminder.id,
                instanceId = instance.id,
                name = reminder.name,
                dosage = reminder.dosage,
                kind = reminder.kind,
                scheduledTime = instance.scheduledTime,
                status = instance.status,
                snoozedUntil = instance.snoozedUntil,
                isNext = false,
                completedAt = instance.completedAt.takeIf { instance.status == InstanceStatus.Completed },
            ),
        )
    }

    for (reminder in reminders) {
        if (!reminder.active) continue

        // `nextOccurrences` excludes a moment landing exactly on `fromMs`, so the
        // anchor sits one millisecond before the window to keep `start` inclusive.
        val occurrences =
            nextOccurrences(reminder, window.start - 1, MAX_OCCURRENCES_PER_REMINDER, zone)

        for (occurrence in occurrences) {
            if (occurrence.fireAt > window.end) break
            if (claimed.containsKey(timelineRowKey(reminder.id, occurrence.fireAt))) continue

            rows.add(
                TimelineRow(
                    reminderId = reminder.id,
                    instanceId = null,
                    name = reminder.name,
                    dosage = reminder.dosage,
                    kind = reminder.kind,
                    scheduledTime = occurrence.fireAt,
                    status = InstanceStatus.Pending,
                    // An Occurrence nobody has answered has no deferral to report —
                    // there is no Instance to have recorded one.
                    snoozedUntil = null,
                    isNext = false,
                ),
            )
        }
    }

    // Chronological, with an explicit tie-break on `reminderId` so equal moments
    // do not reshuffle: the input order here is the caller's, and a stable sort
    // only preserves that.
    val sorted = rows.sortedWith(compareBy<TimelineRow>({ it.scheduledTime }, { it.reminderId }))

    val nextIndex =
        sorted.indexOfFirst { it.status == InstanceStatus.Pending && it.scheduledTime >= now }
    if (nextIndex == -1) return sorted

    return sorted.mapIndexed { index, row -> if (index == nextIndex) row.copy(isNext = true) else row }
}
