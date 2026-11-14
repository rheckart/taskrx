package com.taskrx.core.domain

import com.taskrx.core.sweep.GRACE_PERIOD_MS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Moving one Occurrence of a task, without touching the Schedule that produced it.
 *
 * A Schedule is a statement about every future moment - "every second Tuesday at 07:00".
 * A move is a statement about one of them - "this Tuesday it is Wednesday at 07:00". The
 * two cannot be expressed in one shape: rewriting `weekly` to say Wednesday would move
 * every future occurrence, and skipping this one would lose the commitment rather than
 * defer it. So the move is a fact about a single moment, and it rides on the Reminder the
 * way `pauses` does (ADR 0009's shape, ADR 0013's rule).
 *
 * Tasks only. A medication Schedule is a clinical instruction, and moving a dose because
 * something outside the app said a time is the app acting on health information it cannot
 * verify. [moveRefusal] enforces that where every other caller already goes.
 *
 * Kept forever: a handful of entries a year, and each one explains why a moment was not
 * where the Schedule put it. An override whose source the Schedule no longer produces -
 * because the person edited the rule - simply never fires again. It is inert, not wrong,
 * and the edit form shows it so the list never becomes a mystery.
 */

/**
 * One occurrence that moved. [from] is the moment the Schedule produced, [to] the moment it
 * now falls on, and [noticeId] the Notice that asked for it when one did.
 */
data class OccurrenceOverride(
    val from: Long,
    val to: Long,
    val noticeId: String? = null,
) {
    init {
        require(to > from) { "An occurrence can only move forward: $from to $to." }
    }
}

/**
 * Why a move is refused, or null when it is allowed.
 *
 * One case per sentence the person can be shown, because each is a different thing to do
 * about it: pick another time, pick another occurrence, or nothing at all.
 */
sealed interface MoveRefusal {
    /** The Reminder is a medication: its moments are not this app's to move. */
    data object NotATask : MoveRefusal

    /** The target is not after the source, so this is not a move. */
    data object NotAfter : MoveRefusal

    /** The target has already passed, so nothing would ever come due there. */
    data object InThePast : MoveRefusal

    /** The source is past its Grace Period: it is settled history, not a moment to defer. */
    data object TooLate : MoveRefusal

    /** The source already carries an answer, and an answer is not rewritten. */
    data object AlreadyAnswered : MoveRefusal

    /**
     * The source carries a `pending` or `snoozed` Instance. Its time was written to the
     * server when it was minted and there is no path that updates it, so moving the moment
     * would leave that row claiming the old one.
     */
    data object InProgress : MoveRefusal

    /** A pause covers the source or the target: the pause wins over the move. */
    data object InAPause : MoveRefusal
}

/**
 * Whether [reminder] may move the occurrence at [from] to [to] as of [now].
 *
 * [instances] are the Instances already minted for that moment (see
 * `ReminderInstancesRepository.allForOccurrence`). The checks run in the order a person
 * would ask them: can this kind of Reminder move at all, is the source still a moment that
 * can move, is the target a real destination, and is anything paused across either end.
 *
 * The source's own blockers come before the target's because a dialog opens on the moment
 * the occurrence already has, where the target is not yet a move. Reporting "pick a moment
 * after the one it is on" there would hide the real reason - that this one is answered, or
 * too late - behind a complaint about a field the person has not touched.
 *
 * A source that has passed but is still inside its Grace Period may move: late is not the
 * same as answered (ADR 0005).
 */
fun moveRefusal(
    reminder: Reminder,
    from: Long,
    to: Long,
    now: Long,
    instances: List<ReminderInstance> = emptyList(),
    gracePeriodMs: Long = GRACE_PERIOD_MS,
): MoveRefusal? {
    if (reminder.kind != ReminderKind.Task) return MoveRefusal.NotATask
    if (now - from > gracePeriodMs) return MoveRefusal.TooLate
    if (instances.any { it.status in ANSWERED_STATUSES }) return MoveRefusal.AlreadyAnswered
    // A row already on the server is a row whose moment was written when it was minted, and
    // no path updates it, so moving the moment would leave that row claiming the old one.
    // Every status is an answer, an open row, or nothing at all, and the answers returned
    // above, so whatever is left here is `pending` or `snoozed`.
    if (instances.isNotEmpty()) return MoveRefusal.InProgress
    if (to <= from) return MoveRefusal.NotAfter
    if (to <= now) return MoveRefusal.InThePast
    if (isPausedAt(reminder.pauses, from) || isPausedAt(reminder.pauses, to)) return MoveRefusal.InAPause
    return null
}

/**
 * The statuses that are an answer. Snoozed is not one: it is "not now", and the row it left
 * is the one this move would strand.
 */
private val ANSWERED_STATUSES = setOf(InstanceStatus.Completed, InstanceStatus.Skipped, InstanceStatus.Missed)

/** [this] with [override] added, replacing any override that moves the same moment. */
fun List<OccurrenceOverride>.withOverride(override: OccurrenceOverride): List<OccurrenceOverride> =
    (filterNot { it.from == override.from } + override).sortedBy { it.from }

/** [this] without the override moving [from], if there is one. */
fun List<OccurrenceOverride>.withoutOverride(from: Long): List<OccurrenceOverride> =
    filterNot { it.from == from }

/*
 * The wire shape, which is also the stored one: `[{"from": 1.7e12, "to": 1.7e12}]`, with
 * `noticeId` only when a Notice caused the move. The same JSON is the Room column, the sync
 * payload and the Convex field (`occurrenceOverrideValidator`), so there is one codec.
 */

fun overridesToJson(overrides: List<OccurrenceOverride>): JsonArray =
    buildJsonArray {
        for (override in overrides) {
            add(
                buildJsonObject {
                    put("from", override.from)
                    put("to", override.to)
                    override.noticeId?.let { put("noticeId", it) }
                },
            )
        }
    }

/**
 * Reads [value] back, or returns an empty list for anything that is not a well-formed array
 * of moves. Numbers arrive from Convex as float64, so `1700000000000.0` is as good as the
 * integer. An empty list is the safe misreading: every moment stays where the Schedule put
 * it, which is what the Reminder said before anything moved.
 */
fun overridesFromJson(value: JsonElement?): List<OccurrenceOverride> {
    if (value !is JsonArray) return emptyList()
    return value.map { element ->
        val entry = element as? JsonObject ?: return emptyList()
        val from = entry["from"].whole() ?: return emptyList()
        val to = entry["to"].whole() ?: return emptyList()
        if (to <= from) return emptyList()

        val noticeId = when (val notice = entry["noticeId"]) {
            null -> null
            is JsonPrimitive -> if (notice.isString) notice.content else return emptyList()
            else -> return emptyList()
        }

        OccurrenceOverride(from, to, noticeId)
    }
}
