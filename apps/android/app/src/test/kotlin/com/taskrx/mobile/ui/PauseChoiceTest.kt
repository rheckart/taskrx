package com.taskrx.mobile.ui

import com.taskrx.mobile.ui.reminders.PauseChoice
import com.taskrx.mobile.ui.reminders.resolvePause
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val NEW_YORK = ZoneId.of("America/New_York")

/** What the date picker returns for a tapped day: that day's midnight, in UTC. */
private fun picked(d: Int) = LocalDate.of(2026, 11, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun local(d: Int, h: Int = 0, m: Int = 0) =
    ZonedDateTime.of(2026, 11, d, h, m, 0, 0, NEW_YORK).toInstant().toEpochMilli()

class PauseChoiceTest {
    private val now = local(3, 14, 30)

    @Test
    fun `no days chosen pauses from now until the person resumes`() {
        assertEquals(PauseChoice(now, null), resolvePause(null, null, now, NEW_YORK))
    }

    @Test
    fun `a resume day starts doses again at the beginning of that local day`() {
        assertEquals(PauseChoice(now, local(6)), resolvePause(null, picked(6), now, NEW_YORK))
    }

    @Test
    fun `today as the start day is now, never earlier`() {
        assertEquals(PauseChoice(now, null), resolvePause(picked(3), null, now, NEW_YORK))
        assertEquals(PauseChoice(now, null), resolvePause(picked(1), null, now, NEW_YORK), "a past day is not backdated")
    }

    @Test
    fun `a later start day begins at the start of that local day`() {
        assertEquals(PauseChoice(local(9), local(13)), resolvePause(picked(9), picked(13), now, NEW_YORK))
    }

    @Test
    fun `a resume day that is not after the start is refused`() {
        assertNull(resolvePause(picked(9), picked(9), now, NEW_YORK))
        assertNull(resolvePause(picked(9), picked(8), now, NEW_YORK))
        assertNull(resolvePause(null, picked(3), now, NEW_YORK), "today's first moment has already gone")
    }
}
