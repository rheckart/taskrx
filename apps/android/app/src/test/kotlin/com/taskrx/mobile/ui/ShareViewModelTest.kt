package com.taskrx.mobile.ui

import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.SourceKind
import com.taskrx.mobile.USER
import com.taskrx.mobile.ui.notices.ShareViewModel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val NOW = Instant.parse("2026-08-06T12:00:00Z").toEpochMilli()

@RunWith(RobolectricTestRunner::class)
class ShareViewModelTest {
    @Test
    fun `the first non-blank line becomes the subject`() =
        withUi(NOW) {
            val vm = ShareViewModel(container)

            vm.load("\n  Bins move to Thursday  \nCollection is a day later.")

            assertEquals("Bins move to Thursday", vm.state.value.title)
            assertEquals("\n  Bins move to Thursday  \nCollection is a day later.", vm.state.value.text)
            assertTrue(vm.state.value.canSave)
        }

    @Test
    fun `a long first line is cut to a subject-sized title`() =
        withUi(NOW) {
            val vm = ShareViewModel(container)

            vm.load("x".repeat(200))

            assertEquals(120, vm.state.value.title.length)
        }

    @Test
    fun `saving stores a share notice and reads no date from it`() =
        withUi(NOW) {
            val vm = ShareViewModel(container)
            vm.load("Bins move to Thursday\nCollection is a day later.")

            vm.save().join()

            assertTrue(vm.state.value.saved)
            val notice = container.notices.list(USER).single()
            assertEquals(SourceKind.Share, notice.sourceKind)
            assertEquals("share", notice.sourceValue)
            assertEquals("Bins move to Thursday", notice.title)
            assertEquals("Bins move to Thursday\nCollection is a day later.", notice.sentence)
            assertEquals(NoticeResolution.None, notice.resolution)
            assertEquals(NOW, notice.receivedAt)
        }

    @Test
    fun `a blank share cannot be saved`() =
        withUi(NOW) {
            val vm = ShareViewModel(container)
            vm.load("   ")

            assertFalse(vm.state.value.canSave)
            vm.save().join()

            assertTrue(container.notices.list(USER).isEmpty())
        }

    @Test
    fun `a second load does not overwrite what the person is editing`() =
        withUi(NOW) {
            val vm = ShareViewModel(container)
            vm.load("First")
            vm.setTitle("Mine")

            vm.load("Second")

            assertEquals("Mine", vm.state.value.title)
            assertEquals("First", vm.state.value.text)
        }
}
