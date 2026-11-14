package com.taskrx.core.notices

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.MoveRefusal
import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.NoticeRule
import com.taskrx.core.domain.PauseWindow
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.createReminderInstance
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.domain.weeklyTimes
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val UTC: ZoneId = ZoneOffset.UTC

private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, UTC).toInstant().toEpochMilli()

private val NOW = at(2026, 11, 14, 12)

private fun task(
    schedule: ScheduleTimes = weeklyTimes(listOf(1), listOf("07:00")),
    rules: List<NoticeRule> = listOf(NoticeRule(SourceKind.Email, "waste@city.example", listOf("collection"))),
    active: Boolean = true,
    kind: ReminderKind = ReminderKind.Task,
    pauses: List<PauseWindow> = emptyList(),
): Reminder =
    createReminder(
        id = "task-1",
        userId = "user-1",
        kind = kind,
        name = "Put the bins out",
        schedule = schedule,
        createdAt = 0,
        updatedAt = 0,
        active = active,
        pauses = pauses,
        noticeRules = rules,
    )

private fun notice(
    title: String = "Bins moved",
    sentence: String? = "Collection moves to Wednesday 18 November.",
    sourceKind: SourceKind = SourceKind.Email,
    sourceValue: String = "waste@city.example",
): Notice =
    Notice(
        id = "notice-1",
        userId = "user-1",
        sourceKind = sourceKind,
        sourceValue = sourceValue,
        title = title,
        sentence = sentence,
        receivedAt = NOW,
        externalId = null,
        resolution = NoticeResolution.None,
        resolvedAt = null,
        createdAt = NOW,
        updatedAt = NOW,
    )

private fun instance(
    scheduledTime: Long,
    status: InstanceStatus,
    reminderId: String = "task-1",
): ReminderInstance =
    createReminderInstance(
        id = "instance-$scheduledTime-$status",
        reminderId = reminderId,
        userId = "user-1",
        reminderKind = ReminderKind.Task,
        scheduledTime = scheduledTime,
        createdAt = 0,
        updatedAt = 0,
        status = status,
    )

private fun reading(text: String = "18 November"): ReadDate.Found =
    assertIs<ReadDate.Found>(readNoticeDate(text, NOW, UTC))

class ProposalTest {
    @Test
    fun `a rule matches the same source and a term, case-folded`() {
        val rule = NoticeRule(SourceKind.Email, "Waste@City.Example", listOf("COLLECTION"))
        assertTrue(rule.matches(notice()))
        assertTrue(NoticeRule(SourceKind.Email, "waste@city.example").matches(notice()))
        assertTrue(NoticeRule(SourceKind.Email, "waste@city.example", listOf("bins")).matches(notice(title = "Bins moved")))
        assertFalse(NoticeRule(SourceKind.Email, "waste@city.example", listOf("water")).matches(notice()))
        assertFalse(NoticeRule(SourceKind.Email, "other@city.example").matches(notice()))
        assertFalse(NoticeRule(SourceKind.App, "waste@city.example").matches(notice()))
        assertFalse(NoticeRule(SourceKind.Email, "not an address").matches(notice()))
        assertFalse(NoticeRule(SourceKind.Email, "waste@city.example").matches(notice(sourceValue = "not an address")))
    }

    @Test
    fun `the words a notice is read from are its subject and kept sentence`() {
        assertEquals("Bins moved Collection moves to Wednesday 18 November.", noticeText(notice()))
        assertEquals("Bins moved", noticeText(notice(sentence = null)))
    }

    @Test
    fun `proposals are made only for active tasks with a matching rule and a readable date`() {
        val readable = notice()
        assertEquals(1, proposalsFor(readable, listOf(task()), emptyList(), NOW, UTC).size)
        assertEquals(0, proposalsFor(readable, listOf(task(active = false)), emptyList(), NOW, UTC).size)
        assertEquals(0, proposalsFor(readable, listOf(task(kind = ReminderKind.Medication)), emptyList(), NOW, UTC).size)
        assertEquals(0, proposalsFor(readable, listOf(task(rules = emptyList())), emptyList(), NOW, UTC).size)
        assertEquals(0, proposalsFor(notice(sentence = "The bins were collected."), listOf(task()), emptyList(), NOW, UTC).size)
    }

    @Test
    fun `a recurring task moves the latest occurrence at or before the date`() {
        val proposal = proposalsFor(notice(), listOf(task()), emptyList(), NOW, UTC).single()

        assertEquals(at(2026, 11, 16, 7), proposal.from)
        assertEquals(at(2026, 11, 18, 7), proposal.to)
        assertNull(proposal.refusal)
        assertTrue(proposal.applies)
        assertEquals("18 November", proposal.matchedText)
    }

    @Test
    fun `a notice that names a time uses it`() {
        val proposal =
            proposalsFor(notice(sentence = "Collection moves to 18 November at 9:30 am."), listOf(task()), emptyList(), NOW, UTC).single()

        assertEquals(at(2026, 11, 16, 7), proposal.from)
        assertEquals(at(2026, 11, 18, 9, 30), proposal.to)
        assertNull(proposal.refusal)
    }

    @Test
    fun `an answered occurrence is skipped for the one before it`() {
        for (status in listOf(InstanceStatus.Completed, InstanceStatus.Skipped, InstanceStatus.Missed)) {
            val proposal =
                proposalsFor(
                    notice(),
                    listOf(task(schedule = dailyTimes(listOf("07:00")))),
                    listOf(instance(at(2026, 11, 18, 7), status)),
                    NOW,
                    UTC,
                ).single()

            assertEquals(at(2026, 11, 17, 7), proposal.from, "a $status moment is settled")
            assertEquals(at(2026, 11, 18, 7), proposal.to)
            assertNull(proposal.refusal)
        }
    }

    @Test
    fun `a moment past its grace period is settled and the next one after the date is chosen`() {
        val proposal =
            proposalsFor(
                notice(sentence = "Collection moves to 10 November 2026."),
                listOf(task(schedule = dailyTimes(listOf("07:00")))),
                emptyList(),
                NOW,
                UTC,
            ).single()

        // Every moment at or before 10 November is past its Grace Period, so the earliest one
        // after the date is chosen; the target is behind us, which is the plainest refusal.
        assertEquals(at(2026, 11, 11, 7), proposal.from)
        assertEquals(ProposalRefusal.Move(MoveRefusal.InThePast), proposal.refusal)
        assertFalse(proposal.applies)
    }

    @Test
    fun `when nothing is at or before the date, the earliest one after is chosen`() {
        val proposal =
            proposalsFor(
                notice(sentence = "Collection moves to 18 November."),
                listOf(task(schedule = onceTimes(at(2026, 11, 20, 9)))),
                emptyList(),
                NOW,
                UTC,
            ).single()

        assertEquals(at(2026, 11, 20, 9), proposal.from)
        assertEquals(at(2026, 11, 18, 9), proposal.to)
        assertEquals(ProposalRefusal.Move(MoveRefusal.NotAfter), proposal.refusal)
    }

    @Test
    fun `a schedule with no moments has no occurrence to move`() {
        val proposal =
            proposalsFor(notice(), listOf(task(schedule = noneTimes())), emptyList(), NOW, UTC).single()

        assertNull(proposal.from)
        assertEquals(ProposalRefusal.NoOccurrence, proposal.refusal)
        assertFalse(proposal.applies)
        // With no occurrence and no time named, the target is the date at the default time.
        assertEquals(at(2026, 11, 18, 9), proposal.to)
    }

    @Test
    fun `a medication is never proposed, and a forced one is refused`() {
        val medication = task(kind = ReminderKind.Medication)
        assertEquals(0, proposalsFor(notice(), listOf(medication), emptyList(), NOW, UTC).size)

        val forced = proposalFor(notice(), medication, emptyList(), NOW, UTC, reading(), source = at(2026, 11, 16, 7))
        assertEquals(ProposalRefusal.Move(MoveRefusal.NotATask), forced.refusal)
    }

    @Test
    fun `a forced occurrence that is answered or in progress is refused`() {
        val source = at(2026, 11, 16, 7)
        val answered =
            proposalFor(notice(), task(), listOf(instance(source, InstanceStatus.Completed)), NOW, UTC, reading(), source)
        assertEquals(ProposalRefusal.Move(MoveRefusal.AlreadyAnswered), answered.refusal)

        val open =
            proposalFor(notice(), task(), listOf(instance(source, InstanceStatus.Pending)), NOW, UTC, reading(), source)
        assertEquals(ProposalRefusal.Move(MoveRefusal.InProgress), open.refusal)
    }

    @Test
    fun `a pause over either end refuses the move`() {
        val source = at(2026, 11, 16, 7)
        val overSource =
            proposalFor(
                notice(),
                task(pauses = listOf(PauseWindow(source - 1, source + 1))),
                emptyList(),
                NOW,
                UTC,
                reading(),
                source,
            )
        assertEquals(ProposalRefusal.Move(MoveRefusal.InAPause), overSource.refusal)

        val overTarget =
            proposalFor(
                notice(),
                task(pauses = listOf(PauseWindow(at(2026, 11, 18, 6), at(2026, 11, 18, 8)))),
                emptyList(),
                NOW,
                UTC,
                reading(),
                source,
            )
        assertEquals(ProposalRefusal.Move(MoveRefusal.InAPause), overTarget.refusal)
    }

    @Test
    fun `a forced source past its grace period is refused`() {
        val old = NOW - 8 * 24 * 3_600_000L
        val proposal = proposalFor(notice(), task(), emptyList(), NOW, UTC, reading(), source = old)

        assertEquals(ProposalRefusal.Move(MoveRefusal.TooLate), proposal.refusal)
    }

    @Test
    fun `the different-occurrence list is the moments around the one chosen`() {
        val options = occurrenceOptionsAround(task(schedule = dailyTimes(listOf("07:00"))), at(2026, 11, 18, 7), UTC, before = 2, after = 2)

        assertEquals(
            listOf(at(2026, 11, 17, 7), at(2026, 11, 18, 7), at(2026, 11, 19, 7), at(2026, 11, 20, 7)),
            options,
        )
        assertEquals(emptyList(), occurrenceOptionsAround(task(), NOW, UTC, before = -1))
        assertEquals(emptyList(), occurrenceOptionsAround(task(), NOW, UTC, after = -1))
        assertEquals(emptyList(), occurrenceOptionsAround(task(kind = ReminderKind.Medication), NOW, UTC))
    }
}
