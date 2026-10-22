package com.taskrx.mobile.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppSettingsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a phone that never chose snoozes for fifteen minutes`() {
        assertEquals(15, AppSettings(context).snoozeMinutes.value)
    }

    @Test
    fun `a chosen length is kept across a restart`() {
        AppSettings(context).setSnoozeMinutes(30)

        assertEquals(30, AppSettings(context).snoozeMinutes.value)
    }

    @Test
    fun `choosing updates the value that screens are watching`() {
        val settings = AppSettings(context)

        settings.setSnoozeMinutes(5)

        assertEquals(5, settings.snoozeMinutes.value)
    }

    @Test
    fun `a stored length that is not a choice reads as the default`() {
        context.getSharedPreferences("taskrx.settings", android.content.Context.MODE_PRIVATE).edit().putInt("snooze_minutes", 7).commit()

        assertEquals(15, AppSettings(context).snoozeMinutes.value)
    }

    @Test
    fun `a length the screen does not offer is refused`() {
        val settings = AppSettings(context)

        assertFailsWith<IllegalArgumentException> { settings.setSnoozeMinutes(7) }
        assertEquals(15, settings.snoozeMinutes.value)
    }
}
