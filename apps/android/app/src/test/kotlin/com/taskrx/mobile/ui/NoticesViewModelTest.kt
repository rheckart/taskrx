package com.taskrx.mobile.ui

import com.taskrx.core.domain.MoveRefusal
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.NoticeRule
import com.taskrx.core.domain.OccurrenceOverride
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.domain.weeklyTimes
import com.taskrx.core.notices.ProposalRefusal
import com.taskrx.core.notices.ReadDate
import com.taskrx.core.notices.UnreadReason
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateNoticeInput
import com.taskrx.mobile.data.UpdateReminderPatch
import com.taskrx.mobile.ui.notices.NoticesViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val NOW = Instant.parse("2026-08-06T12:00:00Z").toEpochMilli()
private val ZONE = ZoneId.of("UTC")

private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli()

@RunWith(RobolectricTestRunner::class)
class NoticesViewModelTest {
    private suspend fun com.taskrx.mobile.Fixture.notice(
        title: String = "Bins moved",
        sentence: String? = "Collection moves to Thursday.",
        receivedAt: Long = NOW,
        resolution: NoticeResolution = NoticeResolution.None,
    ) = container.notices.create(
        CreateNoticeInput(
            userId = USER,
            sourceKind = SourceKind.Email,
            sourceValue = "waste@city.example",
            title = title,
            sentence = sentence,
            receivedAt = receivedAt,
            resolution = resolution,
        ),
    )

    private suspend fun com.taskrx.mobile.Fixture.task(
        schedule: ScheduleTimes = weeklyTimes(listOf(1), listOf("07:00")),
        rules: List<NoticeRule> = listOf(NoticeRule(SourceKind.Email, "waste@city.example", listOf("collection"))),
    ): Reminder {
        val created = reminder("task-1", kind = ReminderKind.Task, schedule = schedule, name = "Put the bins out")
        return container.reminders.update(created.id, UpdateReminderPatch(noticeRules = rules))
    }

    @Test
    fun `refresh lists the notices newest first and counts the undecided ones`() =
        withUi(NOW) {
            notice("older", receivedAt = NOW - 1_000)
            notice("newer", receivedAt = NOW)
            notice("done", receivedAt = NOW - 500, resolution = NoticeResolution.Dismissed)
            val vm = NoticesViewModel(container, zone = { ZONE })

            vm.refresh().join()

            assertFalse(vm.state.value.loading)
            assertEquals(listOf("newer", "done", "older"), vm.state.value.notices.map { it.title })
            assertEquals(2, vm.state.value.unresolved)
        }

    @Test
    fun `beginning a task offers tomorrow at nine in the device's zone`() =
        withUi(NOW) {
            val created = notice()
            val vm = NoticesViewModel(container, zone = { ZONE })
            vm.refresh().join()

            vm.beginTask(created)

            val expected =
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(NOW), ZONE)
                    .toLocalDate()
                    .plusDays(1)
                    .atTime(9, 0)
                    .atZone(ZONE)
                    .toInstant()
                    .toEpochMilli()
            assertEquals(expected, vm.state.value.draft!!.at)
            assertEquals(created.id, vm.state.value.draft!!.notice.id)
        }

    @Test
    fun `saving a draft makes a one-off task and settles the notice`() =
        withUi(NOW) {
            val created = notice()
            val vm = NoticesViewModel(container, zone = { ZONE })
            vm.refresh().join()
            val at = NOW + 3 * 24 * 3_600_000

            vm.beginTask(created)
            vm.setDraftAt(at)
            vm.saveTask().join()

            assertNull(vm.state.value.draft)
            val task = container.reminders.list(USER).single()
            assertEquals(ReminderKind.Task, task.kind)
            assertEquals("Bins moved", task.name)
            assertEquals(OnceTimes(at), task.schedule)
            assertEquals("Collection moves to Thursday.", task.instructions)
            assertEquals(NoticeResolution.Applied, container.notices.get(created.id)!!.resolution)
        }

    @Test
    fun `dismissing keeps the notice but records the decision`() =
        withUi(NOW) {
            val created = notice()
            val vm = NoticesViewModel(container, zone = { ZONE })
            vm.refresh().join()

            vm.dismiss(created).join()

            assertEquals(NoticeResolution.Dismissed, container.notices.get(created.id)!!.resolution)
            assertEquals(0, vm.state.value.unresolved)
        }

    @Test
    fun `cancelling a draft writes nothing`() =
        withUi(NOW) {
            val created = notice()
            val vm = NoticesViewModel(container, zone = { ZONE })
            vm.refresh().join()

            vm.beginTask(created)
            vm.cancelTask()

            assertNull(vm.state.value.draft)
            assertTrue(container.reminders.list(USER).isEmpty())
            assertEquals(NoticeResolution.None, container.notices.get(created.id)!!.resolution)
        }

    @Test
    fun `refresh reads the date and proposes the move a rule names`() =
        withUi(NOW) {
            val task = task()
            val created = notice(sentence = "Collection moves to Wednesday 18 November.")
            val vm = NoticesViewModel(container, zone = { ZONE })

            vm.refresh().join()

            val reading = assertIs<ReadDate.Found>(vm.state.value.readings[created.id])
            assertEquals(LocalDate.of(2026, 11, 18), reading.date)
            assertNull(reading.time)
            val proposal = vm.state.value.proposals[created.id]!!.single()
            assertEquals(task.id, proposal.reminder.id)
            assertEquals(at(2026, 11, 16, 7), proposal.from)
            assertEquals(at(2026, 11, 18, 7), proposal.to)
            assertNull(proposal.refusal)
        }

    @Test
    fun `applying a proposal writes the override and settles the notice`() =
        withUi(NOW) {
            val task = task()
            val created = notice(sentence = "Collection moves to Wednesday 18 November.")
            val vm = NoticesViewModel(container, zone = { ZONE })
            vm.refresh().join()
            val proposal = vm.state.value.proposals[created.id]!!.single()

            vm.applyProposal(created, proposal).join()

            val updated = container.reminders.get(task.id)!!
            assertEquals(
                listOf(OccurrenceOverride(at(2026, 11, 16, 7), at(2026, 11, 18, 7), created.id)),
                updated.overrides,
            )
            assertEquals(NoticeResolution.Applied, container.notices.get(created.id)!!.resolution)
        }

    @Test
    fun `a notice with no date proposes nothing and says why`() =
        withUi(NOW) {
            task()
            val created = notice(sentence = "The bins were collected today.")
            val vm = NoticesViewModel(container, zone = { ZONE })

            vm.refresh().join()

            assertEquals(ReadDate.Unread(UnreadReason.NoDate), vm.state.value.readings[created.id])
            assertTrue(vm.state.value.proposals[created.id].orEmpty().isEmpty())
        }

    @Test
    fun `picking a task by hand builds a proposal with no rule`() =
        withUi(NOW) {
            val task = task(rules = emptyList())
            val created = notice(sentence = "Collection moves to Wednesday 18 November.")
            val vm = NoticesViewModel(container, zone = { ZONE })
            vm.refresh().join()
            assertTrue(vm.state.value.proposals[created.id].orEmpty().isEmpty())

            vm.beginPick(created).join()
            assertEquals(listOf(task.id), vm.state.value.pick!!.tasks.map { it.id })
            vm.pickTask(task).join()

            assertNull(vm.state.value.pick)
            val proposal = vm.state.value.proposals[created.id]!!.single()
            assertEquals(at(2026, 11, 16, 7), proposal.from)
            assertEquals(at(2026, 11, 18, 7), proposal.to)
        }

    @Test
    fun `choosing a different occurrence rebuilds the proposal around it`() =
        withUi(NOW) {
            task(schedule = dailyTimes(listOf("07:00")))
            val created = notice(sentence = "Collection moves to Wednesday 18 November.")
            val vm = NoticesViewModel(container, zone = { ZONE })
            vm.refresh().join()
            val first = vm.state.value.proposals[created.id]!!.single()
            // The 18th's own moment is already the target, so moving it to itself is not a move.
            assertEquals(ProposalRefusal.Move(MoveRefusal.NotAfter), first.refusal)

            vm.beginOccurrence(created, first)
            assertTrue(vm.state.value.occurrencePick!!.options.contains(at(2026, 11, 17, 7)))
            vm.chooseOccurrence(at(2026, 11, 17, 7)).join()

            val chosen = vm.state.value.proposals[created.id]!!.single()
            assertEquals(at(2026, 11, 17, 7), chosen.from)
            assertEquals(at(2026, 11, 18, 7), chosen.to)
            assertNull(chosen.refusal)
        }

    @Test
    fun `a refused proposal is not applied`() =
        withUi(NOW) {
            val task = task(schedule = onceTimes(at(2026, 11, 20, 9)))
            val created = notice(sentence = "Collection moves to 18 November.")
            val vm = NoticesViewModel(container, zone = { ZONE })
            vm.refresh().join()
            val proposal = vm.state.value.proposals[created.id]!!.single()
            assertEquals(ProposalRefusal.Move(MoveRefusal.NotAfter), proposal.refusal)

            vm.applyProposal(created, proposal).join()

            assertTrue(container.reminders.get(task.id)!!.overrides.isEmpty())
            assertEquals(NoticeResolution.None, container.notices.get(created.id)!!.resolution)
        }

    @Test
    fun `a new task starts at the date and time the notice named`() =
        withUi(NOW) {
            val created = notice(sentence = "Collection moves to 18 November at 5pm.")
            val vm = NoticesViewModel(container, zone = { ZONE })
            vm.refresh().join()

            vm.beginTask(created)

            assertEquals(at(2026, 11, 18, 17), vm.state.value.draft!!.at)
        }

    @Test
    fun `a new task from a passed date moves to the next day`() =
        withUi(NOW) {
            val created = notice(sentence = "Collection moves to 1 January 2026.")
            val vm = NoticesViewModel(container, zone = { ZONE })
            vm.refresh().join()

            vm.beginTask(created)

            assertEquals(at(2026, 1, 2, 9), vm.state.value.draft!!.at)
        }

    @Test
    fun `an unreadable notices table says so`() =
        withUi(NOW) {
            val vm = NoticesViewModel(container, zone = { ZONE })
            database.openHelper.writableDatabase.execSQL("DROP TABLE notices")

            vm.refresh().join()

            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.loading)
        }
}
