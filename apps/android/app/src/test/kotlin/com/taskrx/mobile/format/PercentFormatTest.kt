package com.taskrx.mobile.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Rounding, on the one screen where rounding can tell a lie. Mirrors percent.test.ts.
 *
 * "100%" is reserved for a genuinely perfect period; nothing else is special-cased. A figure
 * below half a percent reads "0%" and the count beside it ("1 of 250") qualifies it, because
 * nudging it UP to "1%" would invent adherence that was not there.
 */
class PercentFormatTest {
    @Test
    fun `an ordinary figure rounds to a whole number`() {
        assertEquals("78%", formatPercent(77.7777))
        assertEquals("50%", formatPercent(50.0))
        assertEquals("33%", formatPercent(33.3333))
    }

    @Test
    fun `a half rounds up as JavaScript rounded it`() {
        assertEquals("51%", formatPercent(50.5))
        assertEquals("1%", formatPercent(0.5))
    }

    @Test
    fun `100 percent only when nothing was missed`() {
        assertEquals("100%", formatPercent(100.0))
    }

    @Test
    fun `100 percent is reserved for exactly one input however close the others get`() {
        val nearMisses = listOf(99.5, 99.6, 99.9, 99.99, 99.999, 99.9999, 99.999999, Math.nextDown(100.0), 100.0 - 2.22e-16 * 50)
        for (percent in nearMisses) assertNotEquals("100%", formatPercent(percent), "for $percent")
        assertEquals("100%", formatPercent(100.0))
    }

    @Test
    fun `a near miss reads 99 percent`() {
        assertEquals("99%", formatPercent(99.6))
    }

    @Test
    fun `0 percent when nothing at all was taken`() {
        assertEquals("0%", formatPercent(0.0))
    }

    @Test
    fun `a near-zero figure is never rounded up into adherence that was not there`() {
        assertEquals("0%", formatPercent(0.4))
        assertEquals("0%", formatPercent(0.0001))
    }
}
