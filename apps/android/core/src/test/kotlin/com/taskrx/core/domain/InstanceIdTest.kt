package com.taskrx.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class InstanceIdTest {
    private val reminder = "3f1c0a52-6a0e-4d8e-9a7f-2b1c9e0d4a11"

    // Expected values come from Python's uuid.uuid5, an implementation independent of this one,
    // so the test pins the standard rather than echoing the code.
    @Test
    fun `the id is the UUID v5 of reminder and moment under the TaskRx namespace`() {
        assertEquals("5494a8dd-fd71-5cb5-8243-c66e77f77073", instanceIdFor(reminder, 1_763_283_600_000))
        assertEquals("dd4ae944-84d8-5962-855b-5c322b2d6367", instanceIdFor(reminder, 1_763_287_200_000))
    }

    @Test
    fun `the name is UTF-8`() {
        assertEquals("743337de-7ee2-5a91-9446-a0f93c0b91e0", instanceIdFor("local-ü", 0))
    }

    @Test
    fun `two devices derive the same id and two moments derive different ones`() {
        assertEquals(instanceIdFor(reminder, 1), instanceIdFor(reminder, 1))
        assertNotEquals(instanceIdFor(reminder, 1), instanceIdFor(reminder, 2))
        assertNotEquals(instanceIdFor(reminder, 1), instanceIdFor("other", 1))
    }
}
