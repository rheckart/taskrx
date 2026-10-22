package com.taskrx.mobile.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.taskrx.mobile.ui.settings.SettingsContent
import com.taskrx.mobile.ui.settings.snoozeLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun settings(chosen: Int, picked: MutableList<Int> = mutableListOf()): MutableList<Int> {
        compose.setContent { TaskRxTheme { SettingsContent(chosen, onSnoozeMinutes = { picked += it }) } }
        return picked
    }

    @Test
    fun `lists every snooze length and marks the chosen one`() {
        settings(chosen = 30)

        for (label in listOf("5 minutes", "10 minutes", "15 minutes", "30 minutes", "1 hour")) compose.onNodeWithText(label).assertExists()
        compose.onNodeWithText("30 minutes").assertIsSelected()
    }

    @Test
    fun `tapping a length chooses it`() {
        val picked = settings(chosen = 15)

        compose.onNodeWithText("1 hour").performClick()
        compose.onNodeWithText("5 minutes").performClick()

        assertEquals(listOf(60, 5), picked)
    }

    @Test
    fun `an hour is called an hour and the rest are minutes`() {
        assertEquals("1 hour", snoozeLabel(60))
        assertEquals("5 minutes", snoozeLabel(5))
    }
}
