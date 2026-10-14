package com.taskrx.mobile.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.taskrx.mobile.sync.StuckChange
import com.taskrx.mobile.ui.sync.SyncProblemsContent
import com.taskrx.mobile.ui.sync.SyncProblemsState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncProblemsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `lists what was set aside and why, and Try again asks for another go`() {
        var retries = 0
        compose.setContent {
            SyncProblemsContent(
                SyncProblemsState(
                    items = listOf(StuckChange(1, "Answer to Metformin, due Today 9:00 AM", "Reminder x not found on Convex")),
                    loading = false,
                ),
                onRetry = { retries++ },
            )
        }

        compose.onNodeWithText("Answer to Metformin, due Today 9:00 AM").assertExists()
        compose.onNodeWithText("Reminder x not found on Convex").assertExists()
        compose.onNodeWithText("Try again").assertIsEnabled().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `with nothing stuck it says so`() {
        compose.setContent { SyncProblemsContent(SyncProblemsState(loading = false), onRetry = {}) }

        compose.onNodeWithText("Nothing is stuck").assertExists()
    }

    @Test
    fun `an error is shown and Try again is off while there is nothing to retry`() {
        compose.setContent { SyncProblemsContent(SyncProblemsState(loading = false, error = "disk full"), onRetry = {}) }

        compose.onNodeWithText("disk full").assertExists()
        compose.onNodeWithText("Try again").assertIsNotEnabled()
    }
}
