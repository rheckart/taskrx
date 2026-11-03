package com.taskrx.mobile.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.taskrx.core.batch.BatchAction
import com.taskrx.core.catchup.CatchUpDay
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.mobile.ui.catchup.CatchUpContent
import com.taskrx.mobile.ui.catchup.CatchUpState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val DAY_START = Instant.parse("2026-08-05T00:00:00Z").toEpochMilli()
private const val NINE_AM = 9 * 3_600_000L

/** What the Catch up screen shows and what each button hands back. */
@RunWith(RobolectricTestRunner::class)
class CatchUpScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun row(name: String, kind: ReminderKind = ReminderKind.Medication) =
        TimelineRow("r-$name", null, name, null, kind, DAY_START + NINE_AM, InstanceStatus.Missed, null, false, null)

    private val calls = mutableListOf<String>()

    private fun show(vararg rows: TimelineRow) {
        compose.setContent {
            CatchUpContent(
                state = CatchUpState(days = listOf(CatchUpDay(DAY_START, rows.toList())), loading = false),
                onRefresh = {},
                onAnswer = { answered, action -> calls += "${action.wire}:${answered.joinToString(",") { it.name }}" },
                onUndo = {},
            )
        }
    }

    private fun descriptionOf(prefix: String) = compose.onNodeWithContentDescription(prefix, substring = true)

    @Test
    fun `each row has its own take and skip that answer only that row`() {
        show(row("A"), row("B"))

        descriptionOf("Take B,").performClick()
        descriptionOf("Skip A,").performClick()

        assertEquals(listOf("take:B", "skip:A"), calls)
    }

    @Test
    fun `a task row says Done where a medication says Take`() {
        show(row("Pills"), row("Plants", ReminderKind.Task))

        descriptionOf("Done Plants,").performClick()
        descriptionOf("Take Pills,").performClick()

        assertEquals(listOf("take:Plants", "take:Pills"), calls)
        compose.onAllNodesWithText("Done").assertCountEquals(1)
    }

    @Test
    fun `the shortcut answers only its own kind`() {
        show(row("A"), row("B"), row("T1", ReminderKind.Task), row("T2", ReminderKind.Task))

        descriptionOf("Take all 2 medications").performClick()
        descriptionOf("Skip all 2 tasks").performClick()

        assertEquals(listOf("take:A,B", "skip:T1,T2"), calls)
    }

    @Test
    fun `a lone medication or task has no shortcut`() {
        show(row("A"), row("T", ReminderKind.Task))

        compose.onAllNodesWithText("Take all").assertCountEquals(0)
        compose.onAllNodesWithText("Done all").assertCountEquals(0)
        compose.onAllNodesWithText("Skip all").assertCountEquals(0)
    }

    @Test
    fun `there is no button that answers medications and tasks together`() {
        show(row("A"), row("B"), row("T1", ReminderKind.Task), row("T2", ReminderKind.Task))

        compose.onAllNodesWithText("Done all").assertCountEquals(1)
        compose.onAllNodesWithText("Take all").assertCountEquals(1)
        compose.onAllNodesWithText("Skip all").assertCountEquals(2)
    }
}
