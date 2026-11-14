package com.taskrx.core.notices

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.MoveRefusal
import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.NoticeRule
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.moveRefusal
import com.taskrx.core.domain.normalizeSourceValue
import com.taskrx.core.schedule.nextOccurrences
import com.taskrx.core.sweep.GRACE_PERIOD_MS
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * What a Notice seems to mean for a task, waiting for the person's answer.
 *
 * Nothing here writes or decides (D2, D3). It matches the notice's source against the rules
 * authored on each task ("Notices about this"), chooses which Occurrence the notice is about
 * (D11), and says why a move is refused when it is. The screen shows the two moments and the
 * person confirms; `moveRefusal` remains the single rule about whether a move is allowed.
 */

/** Why a proposal cannot be applied. Either the schedule has no moment near the date, or the move itself is refused. */
sealed interface ProposalRefusal {
    /** The task's schedule produces no Occurrence around the date: `none`, a spent `once`, or nothing in the window. */
    data object NoOccurrence : ProposalRefusal

    /** The occurrence cannot move for one of the reasons `moveRefusal` names. */
    data class Move(val reason: MoveRefusal) : ProposalRefusal
}

/**
 * One task a Notice could move, and the move it is asking for.
 *
 * [from] is the Occurrence the notice is about, or null when the schedule produced none near
 * [date]. [to] is where that occurrence would land: [date] at the notice's own time, or at the
 * occurrence's wall-clock time when the notice named none. [refusal] is null when the move can
 * be applied.
 */
data class Proposal(
    val reminder: Reminder,
    val from: Long?,
    val to: Long,
    val date: LocalDate,
    val time: LocalTime?,
    val matchedText: String,
    val refusal: ProposalRefusal?,
) {
    /** Whether the person can confirm this move. */
    val applies: Boolean get() = from != null && refusal == null
}

/**
 * The proposals every task's rules make for [notice], in the order the tasks were given.
 *
 * A notice with no readable date proposes nothing: there is no target to show. The screen
 * still says "no date found" and offers a task by hand.
 */
fun proposalsFor(
    notice: Notice,
    reminders: List<Reminder>,
    instances: List<ReminderInstance>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Proposal> {
    val reading = readNoticeDate(noticeText(notice), now, zone) as? ReadDate.Found ?: return emptyList()
    return reminders
        .filter { it.kind == ReminderKind.Task && it.active }
        .filter { reminder -> reminder.noticeRules.any { it.matches(notice) } }
        .map { proposalFor(notice, it, instances, now, zone, reading) }
}

/**
 * The move [notice] asks of [reminder], with [reading] already read.
 *
 * [source] forces the Occurrence to move; without it the choice is D11's: the latest
 * unsettled occurrence at or before the named date, else the earliest one after. The screen's
 * "different occurrence" list passes one back in.
 */
fun proposalFor(
    notice: Notice,
    reminder: Reminder,
    instances: List<ReminderInstance>,
    now: Long,
    zone: ZoneId,
    reading: ReadDate.Found,
    source: Long? = null,
): Proposal {
    val pivot = endOfDay(reading.date, zone)
    val chosen = source ?: chooseSource(reminder, pivot, now, instances, zone)
    val time =
        reading.time
            ?: chosen?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
    val to = ZonedDateTime.of(reading.date, time ?: DEFAULT_NOTICE_TIME, zone).toInstant().toEpochMilli()

    if (chosen == null) {
        return Proposal(reminder, null, to, reading.date, reading.time, reading.matchedText, ProposalRefusal.NoOccurrence)
    }

    // The target being behind us is the plainest refusal and must not hide behind "not after
    // the source", which is what the shared check would say about a date that has passed.
    val refusal =
        if (to <= now) {
            ProposalRefusal.Move(MoveRefusal.InThePast)
        } else {
            moveRefusal(reminder, chosen, to, now, instancesAt(instances, reminder.id, chosen))?.let(ProposalRefusal::Move)
        }
    return Proposal(reminder, chosen, to, reading.date, reading.time, reading.matchedText, refusal)
}

/**
 * The moments around [around] the screen offers as "a different occurrence": a few before and
 * a few after, so the person can move the one the notice is about or a neighbour of it.
 */
fun occurrenceOptionsAround(
    reminder: Reminder,
    around: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    before: Int = 3,
    after: Int = 3,
): List<Long> {
    if (reminder.kind != ReminderKind.Task || before < 0 || after < 0) return emptyList()
    val all = nextOccurrences(reminder, around - WINDOW_MS, WINDOW_COUNT, zone).map { it.fireAt }
    return (all.filter { it <= around }.takeLast(before) + all.filter { it > around }.take(after)).distinct()
}

/** Whether [rule] admits [notice]: same source, and one of its terms (if any) in the kept words. */
fun NoticeRule.matches(notice: Notice): Boolean {
    if (sourceKind != notice.sourceKind) return false
    val ruled = normalizeSourceValue(sourceKind, sourceValue) ?: return false
    val seen = normalizeSourceValue(notice.sourceKind, notice.sourceValue) ?: return false
    if (ruled != seen) return false
    if (terms.isEmpty()) return true
    val text = noticeText(notice)
    return terms.any { text.contains(it, ignoreCase = true) }
}

/** The words a notice is matched and read from: its subject and the sentence kept from its body. */
fun noticeText(notice: Notice): String = listOfNotNull(notice.title, notice.sentence).joinToString(" ")

/* ---------------------------------------------------------------------------------------
 * D11: choosing the occurrence
 * --------------------------------------------------------------------------------------- */

/**
 * How far back the walk starts. The latest moment at or before a date is at most a month
 * before it (a monthly schedule), so 35 days reaches it for every recurring variant while
 * staying inside the engine's own look-ahead (56 days for weekly, 60 for daily). An hourly
 * interval contributes at most 840 moments in that span, well under [WINDOW_COUNT].
 */
private const val WINDOW_DAYS = 35L
private const val WINDOW_MS = WINDOW_DAYS * 24 * 60 * 60_000L
private const val WINDOW_COUNT = 4096

private val ANSWERED = setOf(InstanceStatus.Completed, InstanceStatus.Skipped, InstanceStatus.Missed)

/**
 * The latest occurrence at or before [pivot] that is still unsettled, else the earliest one
 * after it. "Unsettled" is the same idea as `moveRefusal`: no answer, and not past its Grace
 * Period. A task whose schedule has no moment near the date yields null.
 */
private fun chooseSource(
    reminder: Reminder,
    pivot: Long,
    now: Long,
    instances: List<ReminderInstance>,
    zone: ZoneId,
): Long? {
    val before =
        nextOccurrences(reminder, pivot - WINDOW_MS, WINDOW_COUNT, zone)
            .map { it.fireAt }
            .filter { it <= pivot }
            .filterNot { settled(reminder.id, it, now, instances) }
    if (before.isNotEmpty()) return before.last()
    return nextOccurrences(reminder, pivot, 1, zone).firstOrNull()?.fireAt
}

private fun settled(reminderId: String, moment: Long, now: Long, instances: List<ReminderInstance>): Boolean =
    now - moment > GRACE_PERIOD_MS ||
        instances.any { it.reminderId == reminderId && it.scheduledTime == moment && it.status in ANSWERED }

private fun instancesAt(instances: List<ReminderInstance>, reminderId: String, moment: Long): List<ReminderInstance> =
    instances.filter { it.reminderId == reminderId && it.scheduledTime == moment }

private fun endOfDay(date: LocalDate, zone: ZoneId): Long =
    date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
