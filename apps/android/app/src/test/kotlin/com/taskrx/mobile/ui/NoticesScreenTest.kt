package com.taskrx.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.taskrx.core.domain.MoveRefusal
import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.weeklyTimes
import com.taskrx.core.notices.Proposal
import com.taskrx.core.notices.ProposalRefusal
import com.taskrx.mobile.format.formatFullDayTime
import com.taskrx.mobile.ui.notices.NoticeOccurrencePick
import com.taskrx.mobile.ui.notices.NoticeTaskDraft
import com.taskrx.mobile.ui.notices.NoticeTaskPick
import com.taskrx.mobile.ui.notices.NoticesContent
import com.taskrx.mobile.ui.notices.NoticesState
import com.taskrx.mobile.ui.notices.ShareContent
import com.taskrx.mobile.ui.notices.ShareState
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val NOW = 1_780_000_000_000L
private val ZONE: ZoneId = ZoneOffset.UTC

private fun at(year: Int, month: Int, day: Int, hour: Int = 0): Long =
    ZonedDateTime.of(year, month, day, hour, 0, 0, 0, ZONE).toInstant().toEpochMilli()

private fun task(name: String = "Put the bins out"): Reminder =
    createReminder(
        id = "task-1",
        userId = "user-1",
        kind = ReminderKind.Task,
        name = name,
        schedule = weeklyTimes(listOf(1), listOf("07:00")),
        createdAt = NOW,
        updatedAt = NOW,
    )

private fun proposal(
    reminder: Reminder = task(),
    from: Long? = at(2026, 11, 16, 7),
    to: Long = at(2026, 11, 18, 7),
    refusal: ProposalRefusal? = null,
) = Proposal(
    reminder = reminder,
    from = from,
    to = to,
    date = LocalDate.of(2026, 11, 18),
    time = null,
    matchedText = "18 November",
    refusal = refusal,
)

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
        onApply: (Notice, Proposal) -> Unit = { _, _ -> },
        onBeginPick: (Notice) -> Unit = {},
        onPickTask: (Reminder) -> Unit = {},
        onCancelPick: () -> Unit = {},
        onBeginOccurrence: (Notice, Proposal) -> Unit = { _, _ -> },
        onChooseOccurrence: (Long) -> Unit = {},
        onCancelOccurrence: () -> Unit = {},
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
                    onApply = onApply,
                    onBeginPick = onBeginPick,
                    onPickTask = onPickTask,
                    onCancelPick = onCancelPick,
                    onBeginOccurrence = onBeginOccurrence,
                    onChooseOccurrence = onChooseOccurrence,
                    onCancelOccurrence = onCancelOccurrence,
                    now = { NOW },
                    zone = ZONE,
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
                notices = listOf(
                    notice(id = "a", title = "Moved", resolution = NoticeResolution.Applied),
                    notice(id = "b", title = "Put aside", resolution = NoticeResolution.Dismissed),
                ),
                loading = false,
            ),
        )

        compose.onNodeWithText("Moved a task").assertIsDisplayed()
        compose.onNodeWithText("Dismissed").assertExists()
        compose.onNodeWithText("0 waiting for you").assertIsDisplayed()
    }

    @Test
    fun `a notice turned into a task says that, not that a task moved`() {
        content(
            NoticesState(
                notices = listOf(notice(id = "c", title = "Added", resolution = NoticeResolution.NewTask)),
                loading = false,
            ),
        )

        compose.onNodeWithText("Turned into a task").assertIsDisplayed()
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
    fun `a proposal shows both moments and applies or picks another occurrence`() {
        val applied = mutableListOf<Proposal>()
        val other = mutableListOf<Proposal>()
        val p = proposal()
        content(
            NoticesState(notices = listOf(notice()), loading = false, proposals = mapOf("n1" to listOf(p))),
            onApply = { _, proposal -> applied += proposal },
            onBeginOccurrence = { _, proposal -> other += proposal },
        )

        compose.onNodeWithText("Put the bins out").assertIsDisplayed()
        compose.onNodeWithText("“18 November”").assertIsDisplayed()
        compose.onNodeWithContentDescription("Apply Put the bins out").performClick()
        compose.onNodeWithText("Different occurrence").performClick()

        assertEquals(listOf(p), applied)
        assertEquals(listOf(p), other)
    }

    @Test
    fun `a refused proposal says why and offers no apply`() {
        val p = proposal(refusal = ProposalRefusal.Move(MoveRefusal.AlreadyAnswered))
        content(NoticesState(notices = listOf(notice()), loading = false, proposals = mapOf("n1" to listOf(p))))

        compose.onNodeWithText("That moment is already answered.").assertIsDisplayed()
        compose.onNodeWithText("Apply").assertDoesNotExist()
        compose.onNodeWithText("Different occurrence").assertIsDisplayed()
    }

    @Test
    fun `the task picker lists tasks and picks one`() {
        val picked = mutableListOf<Reminder>()
        val t = task()
        content(
            NoticesState(notices = listOf(notice()), loading = false, pick = NoticeTaskPick(notice(), listOf(t))),
            onPickTask = { picked += it },
        )

        compose.onNodeWithText("Apply this notice to").assertIsDisplayed()
        compose.onNodeWithText("Put the bins out").performClick()

        assertEquals(listOf(t), picked)
    }

    @Test
    fun `the occurrence picker lists moments and chooses one`() {
        val chosen = mutableListOf<Long>()
        val p = proposal()
        val options = listOf(at(2026, 11, 17, 7), at(2026, 11, 18, 7))
        content(
            NoticesState(notices = listOf(notice()), loading = false, occurrencePick = NoticeOccurrencePick(notice(), p, options)),
            onChooseOccurrence = { chosen += it },
        )

        compose.onNodeWithText("Move which occurrence?").assertIsDisplayed()
        compose.onNodeWithText(formatFullDayTime(at(2026, 11, 17, 7), ZONE)).performClick()

        assertEquals(listOf(at(2026, 11, 17, 7)), chosen)
    }

    @Test
    fun `the share form shows the text and offers creating a task or saving to notices`() {
        var saved = 0
        var createdTask = 0
        compose.setContent {
            TaskRxTheme {
                ShareContent(
                    state = ShareState(text = "Bins move to Thursday", title = "Bins"),
                    onTitle = {},
                    onSave = { saved++ },
                    onCreateTask = { createdTask++ },
                )
            }
        }

        compose.onNodeWithText("Bins move to Thursday").assertIsDisplayed()
        compose.onNodeWithText("Create new task").performClick()
        compose.onNodeWithText("Save to Notices").performClick()

        assertEquals(1, createdTask)
        assertEquals(1, saved)
    }

    @Test
    fun `the share form shows the detected date and time when present`() {
        val detectedAt = at(2026, 11, 18, 14)
        compose.setContent {
            TaskRxTheme {
                ShareContent(
                    state =
                        ShareState(
                            text = "Dentist Nov 18 at 2pm",
                            title = "Dentist Nov 18 at 2pm",
                            detectedAt = detectedAt,
                            matchedText = "Nov 18 at 2pm",
                        ),
                    onTitle = {},
                    onSave = {},
                    now = { NOW },
                )
            }
        }

        compose.onNodeWithText("When").assertIsDisplayed()
        compose.onNodeWithText("Read from “Nov 18 at 2pm” — tap to change, or keep for Create new task.").assertIsDisplayed()
    }
}
