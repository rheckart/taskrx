package com.taskrx.mobile.format

import com.taskrx.core.domain.PauseWindow
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val UTC = ZoneId.of("UTC")

private fun day(d: Int, h: Int = 0) = ZonedDateTime.of(2026, 11, d, h, 0, 0, 0, UTC).toInstant().toEpochMilli()

@RunWith(RobolectricTestRunner::class)
class PauseFormatTest {
    private fun line(pause: PauseWindow, now: Long) = pauseLine(pause, now, UTC, Locale.US)

    @Test
    fun `a running pause with an end says the day doses return`() {
        assertEquals("Paused until Fri, Nov 6", line(PauseWindow(day(2), day(6)), now = day(3)))
    }

    @Test
    fun `a running pause with no end says it waits for the person`() {
        assertEquals("Paused until you resume", line(PauseWindow(day(2)), now = day(3)))
    }

    @Test
    fun `a pause starting this very moment is already running`() {
        assertEquals("Paused until you resume", line(PauseWindow(day(3)), now = day(3)))
    }

    @Test
    fun `a pause that has not started says when it will, and when it ends`() {
        assertEquals("Pause starts Mon, Nov 9 · back Fri, Nov 13", line(PauseWindow(day(9), day(13)), now = day(3)))
        assertEquals("Pause starts Mon, Nov 9", line(PauseWindow(day(9)), now = day(3)))
    }
}
