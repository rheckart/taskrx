package com.taskrx.mobile.settings

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeenAppsTest {
    private fun seenApps() = SeenApps(ApplicationProvider.getApplicationContext())

    @Test
    fun `a package is recorded once and reads back`() {
        val seen = seenApps()
        seen.record("com.example.a")
        seen.record("com.example.a")
        seen.record("com.example.b")

        assertEquals(setOf("com.example.a", "com.example.b"), seen.seen())
    }

    @Test
    fun `a blank package is ignored`() {
        val seen = seenApps()
        seen.record("   ")

        assertTrue(seen.seen().isEmpty())
    }

    @Test
    fun `a package can be forgotten`() {
        val seen = seenApps()
        seen.record("com.example.a")
        seen.record("com.example.b")
        seen.forget("com.example.a")

        assertEquals(setOf("com.example.b"), seen.seen())
    }
}
