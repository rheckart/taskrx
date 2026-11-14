package com.taskrx.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.SourceKind
import com.taskrx.mobile.ui.notices.NoticeTaskDraft
import com.taskrx.mobile.ui.notices.NoticesContent
import com.taskrx.mobile.ui.notices.NoticesState
import com.taskrx.mobile.ui.notices.ShareContent
import com.taskrx.mobile.ui.notices.ShareState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val NOW = 1_780_000_000_000L

private fun notice(
    id: String = "n1",
    title: String = "Bins moved",
    resolution: NoticeResolution = NoticeResolution.None,
) = Notice(
    id = id,
    userId = "user-1",
    sourceKind = SourceKind.Email,
    sourceValue = "waste@city.example",
    title = title,
    sentence = "Collection moves to Thursday.",
    receivedAt = NOW,
    externalId = null,
    resolution = resolution,
    resolvedAt = null,
    createdAt = NOW,
    updatedAt = NOW,
)

@RunWith(RobolectricTestRunner::class)
class NoticesScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun content(
        state: NoticesState,
        onBeginTask: (Notice) -> Unit = {},
        onDismiss: (Notice) -> Unit = {},
        onSaveTask: () -> Unit = {},
        onCancelTask: () -> Unit = {},
    ) {
        compose.setContent {
            TaskRxTheme {
                NoticesContent(
                    state = state,
                    onRefresh = {},
                    onBeginTask = onBeginTask,
                    onDismiss = onDismiss,
                    onDraftAt = {},
                    onSaveTask = onSaveTask,
                    onCancelTask = onCancelTask,
                    now = { NOW },
                )
            }
        }
    }

    @Test
    fun `a notice shows its source, subject and kept sentence`() {
        content(NoticesState(notices = listOf(notice()), loading = false))

        compose.onNodeWithText("Email · waste@city.example").assertIsDisplayed()
        compose.onNodeWithText("Bins moved").assertIsDisplayed()
        compose.onNodeWithText("Collection moves to Thursday.").assertIsDisplayed()
        compose.onNodeWithText("1 waiting for you").assertIsDisplayed()
    }

    @Test
    fun `an undecided notice offers a task and a dismissal`() {
        val begun = mutableListOf<Notice>()
        val dismissed = mutableListOf<Notice>()
        content(NoticesState(notices = listOf(notice()), loading = false), onBeginTask = { begun += it }, onDismiss = { dismissed += it })

        compose.onNodeWithContentDescription("New task from Bins moved").performClick()
        compose.onNodeWithContentDescription("Dismiss Bins moved").performClick()

        assertEquals(listOf("n1"), begun.map { it.id })
        assertEquals(listOf("n1"), dismissed.map { it.id })
    }

    @Test
    fun `a settled notice says what became of it and offers nothing`() {
        content(
            NoticesState(
                notices = listOf(notice(id = "a", title = "Applied", resolution = NoticeResolution.Applied), notice(id = "b", title = "Put aside", resolution = NoticeResolution.Dismissed)),
                loading = false,
            ),
        )

        compose.onNodeWithText("Turned into a task").assertIsDisplayed()
        compose.onNodeWithText("Dismissed").assertExists()
        compose.onNodeWithText("0 waiting for you").assertIsDisplayed()
    }

    @Test
    fun `an empty list says where notices come from`() {
        content(NoticesState(loading = false))

        compose.onNodeWithText("No notices").assertIsDisplayed()
    }

    @Test
    fun `the draft form shows the notice and saves or cancels`() {
        var saved = 0
        var cancelled = 0
        content(
            NoticesState(notices = listOf(notice()), loading = false, draft = NoticeTaskDraft(notice(), NOW + 3_600_000)),
            onSaveTask = { saved++ },
            onCancelTask = { cancelled++ },
        )

        compose.onNodeWithText("From this notice").assertIsDisplayed()
        compose.onNodeWithText("Add task").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, saved)
        assertEquals(1, cancelled)
    }

    @Test
    fun `the share form shows the text and saves`() {
        var saved = 0
        compose.setContent {
            TaskRxTheme {
                ShareContent(
                    state = ShareState(text = "Bins move to Thursday", title = "Bins"),
                    onTitle = {},
                    onSave = { saved++ },
                )
            }
        }

        compose.onNodeWithText("Bins move to Thursday").assertIsDisplayed()
        compose.onNodeWithText("Save to Notices").performClick()

        assertEquals(1, saved)
    }
}
