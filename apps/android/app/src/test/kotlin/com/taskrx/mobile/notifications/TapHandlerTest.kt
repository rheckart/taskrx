package com.taskrx.mobile.notifications

import android.content.Intent
import com.taskrx.core.notifications.TapOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Mirrors the tap-handler.ts contract: one log line per tap, whatever happened to it. */
@RunWith(RobolectricTestRunner::class)
class TapHandlerTest {
    private val lines = mutableListOf<String>()
    private val handler = TapHandler { lines += it }

    private fun press() = Intent(TapHandler.ACTION_TAP).putExtra("reminderId", "r1").putExtra("scheduledTime", "1000")

    @Test
    fun `a press routes to the answer screen and says so`() {
        val outcome = handler.handle(press())

        assertEquals("dose/log?reminderId=r1&scheduledTime=1000", handler.route.value)
        assertEquals(listOf("[tap] navigating to dose/log?reminderId=r1&scheduledTime=1000"), lines)
        assertEquals(TapOutcome.Navigate::class, outcome::class)
    }

    @Test
    fun `a press from a notification armed by the previous build still routes`() {
        handler.handle(Intent(TapHandler.ACTION_TAP).putExtra("medicationId", "old").putExtra("scheduledTime", "5"))
        assertEquals("dose/log?reminderId=old&scheduledTime=5", handler.route.value)
    }

    @Test
    fun `a press with unusable data is logged and goes nowhere`() {
        handler.handle(Intent(TapHandler.ACTION_TAP).putExtra("reminderId", "r1").putExtra("scheduledTime", "soon"))

        assertNull(handler.route.value)
        assertEquals(listOf("[tap] press with no usable dose data"), lines)
    }

    @Test
    fun `a launcher start is logged as ignored`() {
        handler.handle(Intent(Intent.ACTION_MAIN))
        assertNull(handler.route.value)
        assertEquals(listOf("[tap] ignored: action=android.intent.action.MAIN"), lines)
    }

    @Test
    fun `a missing intent is logged too`() {
        handler.handle(null)
        assertEquals(listOf("[tap] ignored: action=null"), lines)
    }

    @Test
    fun `the route is held until the screen consumes it`() {
        handler.handle(press())
        handler.consume()
        assertNull(handler.route.value)
    }
}
