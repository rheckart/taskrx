package com.taskrx.mobile.notices

import android.app.Notification
import com.taskrx.core.domain.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The listener's reading of a posted notification.
 *
 * The service needs the platform to bind it, so the piece under test is the reading itself:
 * what text it extracts, and what de-duplication id it builds. `readMessage` is split from the
 * `StatusBarNotification` for exactly this, so a test can hand it the frames a notification has.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationListenerTest {
    private val listener = NotificationListener()

    private fun notification(title: String? = "Bins moved", text: String? = "Collection moves to Wednesday 18 November."): Notification {
        val notification = Notification()
        notification.extras.putCharSequence(Notification.EXTRA_TITLE, title)
        if (text != null) notification.extras.putCharSequence(Notification.EXTRA_TEXT, text)
        return notification
    }

    @Test
    fun `a notification reads into a message with a keyed external id`() {
        val message =
            listener.readMessage(
                notification = notification(),
                packageName = "com.example.waste",
                key = "0|com.example.waste|1|null|10000",
                postTime = 1_780_000_000_000L,
            )!!

        assertEquals(SourceKind.App, message.sourceKind)
        assertEquals("com.example.waste", message.sourceValue)
        assertEquals("Bins moved", message.title)
        assertEquals("Collection moves to Wednesday 18 November.", message.body)
        assertEquals(1_780_000_000_000L, message.receivedAt)
        assertEquals("notification:com.example.waste:0|com.example.waste|1|null|10000:1780000000000", message.externalId)
    }

    @Test
    fun `the big text is used when the short text is absent`() {
        val notification = Notification()
        notification.extras.putCharSequence(Notification.EXTRA_TITLE, "Bin day")
        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "Collection moves to 18 November.")

        val message = listener.readMessage(notification, "com.example.waste", "k", 1L)!!

        assertEquals("Collection moves to 18 November.", message.body)
    }

    @Test
    fun `the summary text is the last fallback`() {
        val notification = Notification()
        notification.extras.putCharSequence(Notification.EXTRA_SUMMARY_TEXT, "18 November")

        val message = listener.readMessage(notification, "com.example.waste", "k", 1L)!!

        assertEquals("18 November", message.body)
    }

    @Test
    fun `a notification with neither title nor text is not a message`() {
        assertNull(listener.readMessage(notification(title = null, text = null), "com.example.waste", "k", 1L))
        assertNull(listener.readMessage(notification(title = "  ", text = "  "), "com.example.waste", "k", 1L))
    }

    @Test
    fun `a zero post time is read as now`() {
        val message = listener.readMessage(notification(), "com.example.waste", "k", 0)!!

        assertTrue(message.receivedAt > 0)
    }
}
