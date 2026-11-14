package com.taskrx.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private fun parse(text: String) = overridesFromJson(Json.parseToJsonElement(text))

private fun task(
    pauses: List<PauseWindow> = emptyList(),
    overrides: List<OccurrenceOverride> = emptyList(),
) = createReminder(
    id = "task-1",
    userId = "user-1",
    kind = ReminderKind.Task,
    name = "Water the plants",
    createdAt = 0,
    updatedAt = 0,
    pauses = pauses,
    overrides = overrides,
)

private fun instance(
    scheduledTime: Long,
    status: InstanceStatus,
    id: String = "instance-1",
) = createReminderInstance(
    id = id,
    reminderId = "task-1",
    userId = "user-1",
    reminderKind = ReminderKind.Task,
    scheduledTime = scheduledTime,
    createdAt = 0,
    updatedAt = 0,
    status = status,
)

class OccurrenceOverrideTest {
    @Test
    fun `a move must go forward`() {
        assertFailsWith<IllegalArgumentException> { OccurrenceOverride(10, 10) }
        assertFailsWith<IllegalArgumentException> { OccurrenceOverride(10, 9) }
        OccurrenceOverride(10, 11)
    }

    @Test
    fun `adding a move for the same moment replaces it`() {
        val first = OccurrenceOverride(100, 200)
        val overrides = listOf(first)
            .withOverride(OccurrenceOverride(300, 400))

        assertEquals(listOf(first, OccurrenceOverride(300, 400)), overrides)

        val replaced = overrides.withOverride(OccurrenceOverride(100, 250, noticeId = "notice-1"))
        assertEquals(listOf(OccurrenceOverride(100, 250, noticeId = "notice-1"), OccurrenceOverride(300, 400)), replaced)
    }

    @Test
    fun `the list stays ordered by the moment it moves`() {
        val overrides = listOf<OccurrenceOverride>()
            .withOverride(OccurrenceOverride(300, 350))
            .withOverride(OccurrenceOverride(100, 150))

        assertEquals(listOf(100L, 300L), overrides.map { it.from })
    }

    @Test
    fun `removing a move leaves the rest`() {
        val overrides = listOf(OccurrenceOverride(100, 200), OccurrenceOverride(300, 400))

        assertEquals(listOf(OccurrenceOverride(300, 400)), overrides.withoutOverride(100))
        assertEquals(overrides, overrides.withoutOverride(500))
    }

    @Test
    fun `the wire shape round-trips, with and without a notice`() {
        val overrides = listOf(
            OccurrenceOverride(1_700_000_000_000, 1_700_003_600_000),
            OccurrenceOverride(1_700_100_000_000, 1_700_103_600_000, noticeId = "notice-1"),
        )

        assertEquals(overrides, overridesFromJson(overridesToJson(overrides)))
        assertEquals("""[{"from":100,"to":200}]""", overridesToJson(listOf(OccurrenceOverride(100, 200))).toString())
        assertEquals(
            """[{"from":100,"to":200,"noticeId":"n1"}]""",
            overridesToJson(listOf(OccurrenceOverride(100, 200, "n1"))).toString(),
        )
    }

    @Test
    fun `convex float64 numbers read as whole milliseconds`() {
        assertEquals(
            listOf(OccurrenceOverride(1_700_000_000_000, 1_700_003_600_000)),
            parse("""[{"from": 1700000000000.0, "to": 1700003600000.0}]"""),
        )
    }

    @Test
    fun `anything malformed reads as no moves at all`() {
        assertEquals(emptyList(), overridesFromJson(null))
        assertEquals(emptyList(), overridesFromJson(JsonNull))
        assertEquals(emptyList(), parse("""{"from": 1, "to": 2}"""))
        assertEquals(emptyList(), parse("""[[]]"""))
        assertEquals(emptyList(), parse("""[{"to": 200}]"""))
        assertEquals(emptyList(), parse("""[{"from": 100}]"""))
        assertEquals(emptyList(), parse("""[{"from": "100", "to": 200}]"""))
        assertEquals(emptyList(), parse("""[{"from": true, "to": 200}]"""))
        assertEquals(emptyList(), parse("""[{"from": 100.5, "to": 200}]"""))
        assertEquals(emptyList(), parse("""[{"from": 100, "to": 100}]"""))
        assertEquals(emptyList(), parse("""[{"from": 100, "to": 50}]"""))
        assertEquals(emptyList(), parse("""[{"from": 100, "to": 200, "noticeId": 7}]"""))
        assertEquals(emptyList(), parse("""[{"from": 100, "to": 200, "noticeId": {}}]"""))
        // One bad entry loses the whole list: half a set of moves is a worse guess than none.
        assertEquals(emptyList(), parse("""[{"from": 100, "to": 200}, {"from": 300}]"""))
    }
}

class MoveRefusalTest {
    private val now = 1_000_000L
    private val from = now + 1_000
    private val to = from + 3_600_000

    @Test
    fun `a task with an open future moment may move`() {
        assertNull(moveRefusal(task(), from, to, now))
    }

    @Test
    fun `a medication never moves`() {
        val medication = createReminder(
            id = "med-1",
            userId = "user-1",
            kind = ReminderKind.Medication,
            name = "Lithium",
            createdAt = 0,
            updatedAt = 0,
        )

        assertEquals(MoveRefusal.NotATask, moveRefusal(medication, from, to, now))
    }

    @Test
    fun `the target must be after the source and in the future`() {
        assertEquals(MoveRefusal.NotAfter, moveRefusal(task(), from, from, now))
        assertEquals(MoveRefusal.NotAfter, moveRefusal(task(), from, from - 1, now))
        // A target that is after the source is still refused when it has already passed; that
        // only happens for a source that has passed too, inside its Grace Period.
        assertEquals(MoveRefusal.InThePast, moveRefusal(task(), now - 1_000, now, now))
        assertEquals(MoveRefusal.InThePast, moveRefusal(task(), now - 1_000, now - 1, now))
    }

    @Test
    fun `a source past its grace period is settled history`() {
        val grace = 2 * 3_600_000L
        val old = now - grace - 1

        assertEquals(MoveRefusal.TooLate, moveRefusal(task(), old, to, now, gracePeriodMs = grace))
        // Exactly one grace period old is still answerable, as the sweep treats it.
        assertNull(moveRefusal(task(), now - grace, to, now, gracePeriodMs = grace))
    }

    @Test
    fun `an answered moment never moves`() {
        for (status in listOf(InstanceStatus.Completed, InstanceStatus.Skipped, InstanceStatus.Missed)) {
            assertEquals(
                MoveRefusal.AlreadyAnswered,
                moveRefusal(task(), from, to, now, listOf(instance(from, status))),
                "a $status moment must not move",
            )
        }
    }

    @Test
    fun `a moment with a row already on the server never moves`() {
        assertEquals(MoveRefusal.InProgress, moveRefusal(task(), from, to, now, listOf(instance(from, InstanceStatus.Pending))))
        assertEquals(MoveRefusal.InProgress, moveRefusal(task(), from, to, now, listOf(instance(from, InstanceStatus.Snoozed))))
        // An answered row wins the message when both somehow exist.
        assertEquals(
            MoveRefusal.AlreadyAnswered,
            moveRefusal(
                task(),
                from,
                to,
                now,
                listOf(instance(from, InstanceStatus.Pending, "a"), instance(from, InstanceStatus.Completed, "b")),
            ),
        )
    }

    @Test
    fun `a pause over either end refuses the move`() {
        assertEquals(
            MoveRefusal.InAPause,
            moveRefusal(task(pauses = listOf(PauseWindow(from - 1, from + 1))), from, to, now),
        )
        assertEquals(
            MoveRefusal.InAPause,
            moveRefusal(task(pauses = listOf(PauseWindow(to - 1, to + 1))), from, to, now),
        )
        assertNull(moveRefusal(task(pauses = listOf(PauseWindow(to + 1, to + 2))), from, to, now))
    }
}
