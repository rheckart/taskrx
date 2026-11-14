package com.taskrx.mobile.ui

import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.SourceKind
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateNoticeInput
import com.taskrx.mobile.ui.notices.NoticesViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val NOW = Instant.parse("2026-08-06T12:00:00Z").toEpochMilli()
private val ZONE = ZoneId.of("UTC")

@RunWith(RobolectricTestRunner::class)
class NoticesViewModelTest {
    private suspend fun com.taskrx.mobile.Fixture.notice(
        title: String = "Bins moved",
        receivedAt: Long = NOW,
        resolution: NoticeResolution = NoticeResolution.None,
    ) = container.notices.create(
        CreateNoticeInput(
            userId = USER,
            sourceKind = SourceKind.Email,
            sourceValue = "waste@city.example",
            title = title,
            sentence = "Collection moves to Thursday.",
            receivedAt = receivedAt,
            resolution = resolution,
        ),
    )

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
    fun `an unreadable notices table says so`() =
        withUi(NOW) {
            val vm = NoticesViewModel(container, zone = { ZONE })
            database.openHelper.writableDatabase.execSQL("DROP TABLE notices")

            vm.refresh().join()

            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.loading)
        }
}
