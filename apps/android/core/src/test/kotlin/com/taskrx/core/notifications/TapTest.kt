package com.taskrx.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TapTest {
    @Test
    fun `routes a press to the answer screen with both params`() {
        val outcome = resolveTap(true, "tap", "r1", null, "1000")
        assertEquals(TapOutcome.Navigate(TapTarget("r1", 1000), "dose/log?reminderId=r1&scheduledTime=1000"), outcome)
        assertEquals("[tap] navigating to dose/log?reminderId=r1&scheduledTime=1000", outcome.logLine)
    }

    @Test
    fun `is Kind-neutral`() {
        assertEquals("dose/log?reminderId=task-1&scheduledTime=5", answerRoute(TapTarget("task-1", 5)))
    }

    @Test
    fun `falls back to the legacy medicationId key`() {
        val outcome = resolveTap(true, "tap", null, "old", "7") as TapOutcome.Navigate
        assertEquals("old", outcome.target.reminderId)
    }

    @Test
    fun `prefers reminderId over medicationId`() {
        val outcome = resolveTap(true, "tap", "new", "old", "7") as TapOutcome.Navigate
        assertEquals("new", outcome.target.reminderId)
    }

    @Test
    fun `encodes ids that need it`() {
        assertEquals("dose/log?reminderId=a%20b%26c&scheduledTime=1", answerRoute(TapTarget("a b&c", 1)))
    }

    @Test
    fun `a press with no id is reported`() {
        val outcome = resolveTap(true, "tap", null, null, "1")
        assertEquals(TapOutcome.NoUsableData, outcome)
        assertTrue(outcome.logLine.contains("no usable dose data"))
    }

    @Test
    fun `a press with no time or a non-numeric time is reported`() {
        assertEquals(TapOutcome.NoUsableData, resolveTap(true, "tap", "r", null, null))
        assertEquals(TapOutcome.NoUsableData, resolveTap(true, "tap", "r", null, "soon"))
    }

    @Test
    fun `a non-press is ignored and says what it was`() {
        val outcome = resolveTap(false, "android.intent.action.MAIN", "r", null, "1")
        assertEquals(TapOutcome.Ignored("android.intent.action.MAIN"), outcome)
        assertEquals("[tap] ignored: action=android.intent.action.MAIN", outcome.logLine)
    }
}
