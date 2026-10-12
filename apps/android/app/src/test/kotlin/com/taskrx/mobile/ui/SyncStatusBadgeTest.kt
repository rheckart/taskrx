package com.taskrx.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.taskrx.core.sync.SyncBadge
import com.taskrx.mobile.ui.components.SyncStatusBadge
import com.taskrx.mobile.ui.components.syncBadgeDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncStatusBadgeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `every state is drawn and spoken`() {
        var shown by mutableStateOf(SyncBadge.Synced)
        compose.setContent { SyncStatusBadge(shown) }
        for (badge in SyncBadge.entries) {
            shown = badge
            compose.waitForIdle()
            compose.onNodeWithContentDescription(syncBadgeDescription(badge)).assertExists()
        }
    }

    @Test
    fun `only the error mark can be pressed, and pressing it opens the problems`() {
        var opened = 0
        var shown by mutableStateOf(SyncBadge.Error)
        compose.setContent { SyncStatusBadge(shown, onProblems = { opened++ }) }

        compose.onNodeWithContentDescription(syncBadgeDescription(SyncBadge.Error)).performClick()
        assertEquals(1, opened)

        for (badge in SyncBadge.entries - SyncBadge.Error) {
            shown = badge
            compose.waitForIdle()
            compose.onNodeWithContentDescription(syncBadgeDescription(badge)).performClick()
        }
        assertEquals(1, opened, "no other state is a button")
    }

    @Test
    fun `the words are distinct`() {
        assertEquals(SyncBadge.entries.size, SyncBadge.entries.map(::syncBadgeDescription).toSet().size)
    }
}
