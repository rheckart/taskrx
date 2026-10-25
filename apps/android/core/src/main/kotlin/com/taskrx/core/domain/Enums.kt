package com.taskrx.core.domain

/*
 * The unified reminder domain enums, ported from `TaskRx.Core/Enums.cs` in the retired .NET app.
 *
 * The C# side serialized these as PascalCase member names. The stored and
 * synced spelling here is lowercase `snake_case`, matching the local SQLite
 * schema and the Convex payloads, so every member carries it as [wire]. The
 * mapping between the two spellings is mechanical: PascalCase member name <->
 * snake_case literal.
 */

/** `TaskRx.Core.ReminderKind`. The axis the POC's medication-only types lack. */
enum class ReminderKind(val wire: String) {
    Medication("medication"),
    Task("task"),
    ;

    companion object {
        fun fromWire(wire: String): ReminderKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** `TaskRx.Core.Priority`, ordered most to least urgent, as in the C# enum. */
enum class Priority(val wire: String) {
    Critical("critical"),
    High("high"),
    Normal("normal"),
    Low("low"),
    ;

    companion object {
        fun fromWire(wire: String): Priority? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * `TaskRx.Core.InstanceStatus`, with one member renamed.
 *
 * [Completed] is what the C# called `Taken`. Both Kinds share this list and a
 * task is never "taken", so keeping the medication spelling would have pushed a
 * Kind check into every surface that displays a status — the per-consumer cost
 * a unified timeline exists to remove. See ADR 0001, which also records why
 * relabelling in the UI, or adding `done` alongside `taken`, were rejected. A
 * medication is still *shown* as taken; only the stored vocabulary is
 * Kind-neutral.
 *
 * [Snoozed] is deliberately distinct from [Skipped] and [Missed]: the adherence
 * calculator filters non-[Pending] instances and then counts only
 * completed/missed/skipped, which is what makes a snooze affect a medication
 * streak without counting as a miss.
 */
enum class InstanceStatus(val wire: String) {
    Pending("pending"),
    Completed("completed"),
    Snoozed("snoozed"),
    Skipped("skipped"),
    Missed("missed"),
    ;

    companion object {
        fun fromWire(wire: String): InstanceStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The statuses a person can still act on.
 *
 * [InstanceStatus.Pending] is the obvious one. [InstanceStatus.Snoozed] is the
 * one worth stating: a snooze is a deferral, not an answer — "not now" rather
 * than "no" — so the moment is still outstanding and the row it produced is the
 * row a later visit settles. Treating a snooze as answered would make snoozing
 * the one way to put a moment permanently out of reach, and would open a second
 * Instance beside it every time the person came back.
 *
 * `completed` and `skipped` are answers. [InstanceStatus.Missed] is the sweep's
 * verdict, not the person's, and it is only ever a guess: the Grace Period ran
 * out with no answer, which says nothing about a dose taken without opening the
 * app. So a missed moment is reopened rather than closed, and answering it
 * settles the same row; a dose taken late is recorded as taken instead of being
 * stuck as missed. The server only refuses a write out of `completed`, so a
 * missed row can move to `completed` or `skipped`.
 *
 * It lives here rather than in the two callers because they would otherwise
 * each carry a copy: the on-device repository builds its `WHERE status IN (…)`
 * from it, and the timeline decides from it whether a row is still tappable. A
 * row the list offers and the lookup refuses is a button that does nothing.
 */
val ANSWERABLE_STATUSES: Set<InstanceStatus> =
    setOf(InstanceStatus.Pending, InstanceStatus.Snoozed, InstanceStatus.Missed)

fun isAnswerable(status: InstanceStatus): Boolean = status in ANSWERABLE_STATUSES

/**
 * `TaskRx.Core.NotificationType`, used by the notification log.
 *
 * [Taken] here is NOT the [InstanceStatus] member ADR 0001 renamed. This enum
 * records what happened to a notification, not what became of an Instance, and
 * the rename deliberately did not reach it. The tests pin both lists so the
 * difference cannot be "tidied away" by a blanket search-and-replace — which is
 * exactly how it was nearly lost.
 */
enum class NotificationType(val wire: String) {
    Fired("fired"),
    Taken("taken"),
    Snoozed("snoozed"),
    Skipped("skipped"),
    Missed("missed"),
    CaregiverPinged("caregiver_pinged"),
    ;

    companion object {
        fun fromWire(wire: String): NotificationType? = entries.firstOrNull { it.wire == wire }
    }
}
