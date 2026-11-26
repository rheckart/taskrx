package com.taskrx.mobile.ui

import android.content.ClipData
import android.content.Intent
import android.text.SpannableString
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.SourceKind
import com.taskrx.mobile.USER
import com.taskrx.mobile.ui.notices.PendingShare
import com.taskrx.mobile.ui.notices.ShareDestination
import com.taskrx.mobile.ui.notices.ShareIntake
import com.taskrx.mobile.ui.notices.ShareViewModel
import com.taskrx.mobile.ui.notices.SharedTaskDraft
import com.taskrx.mobile.ui.notices.taskDraftFromShare
import com.taskrx.mobile.ui.notices.taskDraftFromText
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun `ShareIntake routes ACTION_SEND to Share and ACTION_PROCESS_TEXT to NewTask`() {
        val intake = ShareIntake()
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "  Pick up prescription  ")
            }
        intake.offer(send)
        assertEquals(PendingShare("Pick up prescription", ShareDestination.Share), intake.pending.value)
        intake.consume()

        val processText =
            Intent(Intent.ACTION_PROCESS_TEXT).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, SpannableString("  Call pharmacy tomorrow  "))
            }
        intake.offer(processText)
        assertEquals(PendingShare("Call pharmacy tomorrow", ShareDestination.NewTask), intake.pending.value)
    }

    @Test
    fun `ShareIntake falls back to ClipData or EXTRA_SUBJECT when EXTRA_TEXT is absent`() {
        val intake = ShareIntake()
        val fromClip =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                clipData = ClipData.newPlainText("msg", "  From clip data  ")
            }
        intake.offer(fromClip)
        assertEquals(PendingShare("From clip data", ShareDestination.Share), intake.pending.value)
        intake.consume()

        val fromSubject =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "  Subject only  ")
            }
        intake.offer(fromSubject)
        assertEquals(PendingShare("Subject only", ShareDestination.Share), intake.pending.value)
    }

    @Test
    fun `ShareIntake ignores blank text, non-text shares, and unrelated actions`() {
        val intake = ShareIntake()
        intake.offer(Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_TEXT, "caption") })
        assertNull(intake.pending.value)

        intake.offer(Intent(Intent.ACTION_PROCESS_TEXT).apply { type = "text/plain"; putExtra(Intent.EXTRA_PROCESS_TEXT, "   ") })
        assertNull(intake.pending.value)

        intake.offer(Intent(Intent.ACTION_VIEW).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "ignored") })
        assertNull(intake.pending.value)
    }

    @Test
    fun `taskDraftFromShare skips duplicate notes for a short unedited line and keeps full text otherwise`() {
        assertEquals(
            SharedTaskDraft(name = "Pick up milk", notes = ""),
            taskDraftFromShare("Pick up milk", "  Pick up milk  "),
        )
        assertEquals(
            SharedTaskDraft(name = "Groceries", notes = "Pick up milk"),
            taskDraftFromShare("Groceries", "Pick up milk"),
        )
        assertEquals(
            SharedTaskDraft(name = "Bins move to Thursday", notes = "Bins move to Thursday\nCollection is a day later."),
            taskDraftFromText("Bins move to Thursday\nCollection is a day later."),
        )
        val longLine = "x".repeat(150)
        assertEquals(
            SharedTaskDraft(name = "x".repeat(120), notes = longLine),
            taskDraftFromText(longLine),
        )
    }

    @Test
    fun `a clear date and time in shared text is detected and editable`() =
        withUi(NOW) {
            val vm = ShareViewModel(container, initialText = "Dentist Dec 4 at 2:30pm", zone = { ZoneOffset.UTC })
            val expected = ZonedDateTime.of(2026, 12, 4, 14, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

            assertEquals(expected, vm.state.value.detectedAt)
            assertEquals("Dec 4 at 2:30pm", vm.state.value.matchedText)

            val edited = expected + 3_600_000L
            vm.setDetectedAt(edited)
            assertEquals(edited, vm.state.value.detectedAt)
        }
}
