package com.taskrx.mobile.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.taskrx.core.adherence.AdherenceStats
import com.taskrx.core.adherence.DayAdherence
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.createReminderInstance
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.refill.projectRefill
import com.taskrx.mobile.adherence.AdherenceReading
import com.taskrx.mobile.refill.RefillRow
import com.taskrx.core.adherence.AdherencePeriod
import com.taskrx.mobile.ui.adherence.AdherenceContent
import com.taskrx.mobile.ui.adherence.AdherenceUiState
import com.taskrx.mobile.ui.backlog.BacklogActions
import com.taskrx.mobile.ui.backlog.BacklogContent
import com.taskrx.mobile.ui.backlog.BacklogEntry
import com.taskrx.mobile.ui.backlog.BacklogState
import com.taskrx.mobile.ui.history.HistoryContent
import com.taskrx.mobile.ui.history.HistoryItem
import com.taskrx.mobile.ui.history.HistoryState
import com.taskrx.mobile.ui.reminders.ReminderEntry
import com.taskrx.mobile.ui.reminders.RemindersActions
import com.taskrx.mobile.ui.reminders.RemindersContent
import com.taskrx.mobile.ui.reminders.RemindersState
import com.taskrx.mobile.ui.reminders.lastCompletedLine
import com.taskrx.mobile.ui.search.DrugMatch
import com.taskrx.mobile.ui.search.DrugSearchContent
import com.taskrx.mobile.ui.search.DrugSearchState
import com.taskrx.mobile.ui.search.SearchStatus
import com.taskrx.mobile.ui.supplies.SuppliesActions
import com.taskrx.mobile.ui.supplies.SuppliesContent
import com.taskrx.mobile.ui.supplies.SuppliesState
import com.taskrx.mobile.ui.supplies.SupplySort
import com.taskrx.mobile.ui.supplies.SupplyDraft
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val NOW = Instant.parse("2026-08-13T08:00:00Z").toEpochMilli()
private const val HOUR = 3_600_000L

/** The list screens, over state they are handed. What each decides is tested at its ViewModel. */
// Tall, so a row with its editor open is on screen: a tap outside the window does nothing.
@Config(qualifiers = "w411dp-h2400dp")
@RunWith(RobolectricTestRunner::class)
class ListScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun reminder(
        id: String = "r",
        name: String = "Metformin",
        kind: ReminderKind = ReminderKind.Medication,
        schedule: ScheduleTimes = dailyTimes(listOf("09:00")),
        dosage: String? = null,
        pills: Double? = null,
        perDose: Double? = null,
    ): Reminder =
        createReminder(id, "u", kind, name, NOW, NOW, dosage = dosage, schedule = schedule, pillsRemaining = pills, pillsPerDose = perDose, refillReminderDays = 7)

    private fun instance(status: InstanceStatus, at: Long = NOW - HOUR, kind: ReminderKind = ReminderKind.Medication, notes: String? = null) =
        createReminderInstance("i-$at", "r", "u", kind, at, NOW, NOW, status, completedAt = if (status == InstanceStatus.Completed) at else null, notes = notes)

    // ---- backlog ----

    private class BacklogCalls {
        val log = mutableListOf<String>()

        fun actions() =
            BacklogActions(
                onRefresh = { log += "refresh" },
                onComplete = { log += "complete:${it.name}" },
                onMoveToTimeline = { r, _ -> log += "move:${r.name}" },
            )
    }

    private fun backlog(state: BacklogState, calls: BacklogCalls = BacklogCalls()) {
        compose.setContent { TaskRxTheme { BacklogContent(state, calls.actions(), now = { NOW }) } }
    }

    @Test
    fun `an empty Backlog says what it is for`() {
        backlog(BacklogState(loading = false))

        compose.onNodeWithText("Nothing in your backlog").assertExists()
        compose.onNodeWithText("Anything you add without a time waits here until you get to it.").assertExists()
    }

    @Test
    fun `a Backlog row is completed in the Kind's own word`() {
        val calls = BacklogCalls()
        val med = reminder("m", "Ibuprofen", schedule = noneTimes())
        val task = reminder("t", "Sort the garage", ReminderKind.Task, noneTimes())
        backlog(BacklogState(entries = listOf(BacklogEntry(med, null), BacklogEntry(task, null)), loading = false), calls)

        compose.onNodeWithText("Taken").assertExists()
        compose.onNodeWithText("Done").assertExists()
        compose.onNodeWithContentDescription("Complete Ibuprofen").performClick()
        compose.onNodeWithContentDescription("Complete Sort the garage").performClick()

        assertEquals(listOf("complete:Ibuprofen", "complete:Sort the garage"), calls.log)
    }

    @Test
    fun `a Backlog row says when it was last done, or nothing`() {
        val done = reminder("m", "Ibuprofen", schedule = noneTimes())
        backlog(
            BacklogState(
                entries = listOf(BacklogEntry(done, instance(InstanceStatus.Completed)), BacklogEntry(reminder("t", "Garage", ReminderKind.Task, noneTimes()), null)),
                loading = false,
            ),
        )

        assertEquals(1, compose.onAllNodesWithText("Last done", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Task").assertExists()
    }

    @Test
    fun `giving a Backlog row a time opens an editor, and moving needs a usable time`() {
        val calls = BacklogCalls()
        val med = reminder("m", "Ibuprofen", schedule = noneTimes())
        backlog(BacklogState(entries = listOf(BacklogEntry(med, null)), loading = false), calls)

        compose.onAllNodesWithText("Move to timeline").assertCountEquals(0)
        compose.onNodeWithContentDescription("Give Ibuprofen a time").performClick()
        compose.onNodeWithText("Move to timeline").assertIsEnabled().performClick()

        assertEquals(listOf("move:Ibuprofen"), calls.log)
        compose.onAllNodesWithText("Move to timeline").assertCountEquals(0)
    }

    @Test
    fun `choosing no time at all leaves nothing to move`() {
        val med = reminder("m", "Ibuprofen", schedule = noneTimes())
        backlog(BacklogState(entries = listOf(BacklogEntry(med, null)), loading = false))

        compose.onNodeWithContentDescription("Give Ibuprofen a time").performClick()
        compose.onNodeWithText("No time").performClick()

        compose.onNodeWithText("Move to timeline").assertIsNotEnabled()
    }

    @Test
    fun `a row with a write in flight cannot be completed again`() {
        val med = reminder("m", "Ibuprofen", schedule = noneTimes())
        backlog(BacklogState(entries = listOf(BacklogEntry(med, null)), loading = false, busy = setOf("m")))

        compose.onNodeWithContentDescription("Complete Ibuprofen").assertIsNotEnabled()
    }

    @Test
    fun `a failed Backlog can be retried`() {
        val calls = BacklogCalls()
        backlog(BacklogState(loading = false, error = "disk full"), calls)

        compose.onNodeWithText("Failed to load: disk full").assertExists()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(listOf("refresh"), calls.log)
    }

    @Test
    fun `while the Backlog loads there is a placeholder`() {
        backlog(BacklogState(entries = listOf(BacklogEntry(reminder(), null)), loading = true))

        compose.onNodeWithContentDescription("Loading your backlog").assertExists()
        compose.onAllNodesWithText("Metformin").assertCountEquals(0)
    }

    // ---- history ----

    private fun history(state: HistoryState, now: Long = NOW) {
        compose.setContent { TaskRxTheme { HistoryContent(state, onRefresh = {}, now = now) } }
    }

    @Test
    fun `history groups by day and reads each Kind in its own word`() {
        history(
            HistoryState(
                items =
                    listOf(
                        HistoryItem(instance(InstanceStatus.Completed, NOW - HOUR), "Metformin", "500mg"),
                        HistoryItem(instance(InstanceStatus.Completed, NOW - 2 * HOUR, ReminderKind.Task), "Call pharmacy", null),
                        HistoryItem(instance(InstanceStatus.Missed, NOW - 30 * HOUR, notes = "felt sick"), "Metformin", null),
                    ),
                loading = false,
            ),
        )

        compose.onNodeWithText("TODAY").assertExists()
        compose.onNodeWithText("YESTERDAY").assertExists()
        compose.onNodeWithText("500mg").assertExists()
        compose.onNodeWithText("Taken").assertExists()
        compose.onNodeWithText("Done").assertExists()
        compose.onNodeWithText("Missed").assertExists()
        compose.onNodeWithText("felt sick").assertExists()
        compose.onNodeWithText("Taken:", substring = true).assertExists()
        compose.onNodeWithText("Done:", substring = true).assertExists()
    }

    @Test
    fun `an empty history says what lands in it`() {
        history(HistoryState(loading = false))

        compose.onNodeWithText("Nothing recorded yet").assertExists()
    }

    // ---- reminders ----

    private class ReminderCalls {
        val log = mutableListOf<String>()

        fun actions() =
            RemindersActions(
                onRefresh = { log += "refresh" },
                onEdit = { log += "edit:${it.name}" },
                onStop = { log += "stop:${it.name}" },
                onSearchDrug = { log += "search" },
            )
    }

    private fun reminders(state: RemindersState, calls: ReminderCalls = ReminderCalls()) {
        compose.setContent { TaskRxTheme { RemindersContent(state, calls.actions()) } }
    }

    @Test
    fun `a reminder row says its Kind, its Schedule and when it was last done`() {
        val med = reminder("m", "Metformin", dosage = "500mg")
        val entries = listOf(ReminderEntry(med, instance(InstanceStatus.Completed)), ReminderEntry(reminder("t", "Call", ReminderKind.Task, noneTimes()), null))
        reminders(RemindersState(entries = entries, loading = false))

        compose.onNodeWithText("Medication · Daily at 09:00").assertExists()
        compose.onNodeWithText("Task · In your backlog — no time set").assertExists()
        compose.onNodeWithText("Last done: Never").assertExists()
        assertTrue(lastCompletedLine(entries[0]).startsWith("Last taken: "))
        assertEquals("Last done: Never", lastCompletedLine(entries[1]))
    }

    @Test
    fun `stopping asks first, and keeping it stops nothing`() {
        val calls = ReminderCalls()
        reminders(RemindersState(entries = listOf(ReminderEntry(reminder(), null)), loading = false), calls)

        compose.onNodeWithContentDescription("Stop Metformin").performClick()
        compose.onNodeWithText("Stop Metformin?").assertExists()
        compose.onNodeWithText("Keep it").performClick()
        compose.onAllNodesWithText("Stop Metformin?").assertCountEquals(0)
        assertEquals(emptyList(), calls.log)

        compose.onNodeWithContentDescription("Stop Metformin").performClick()
        compose.onNode(hasText("Stop") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("stop:Metformin"), calls.log)
    }

    @Test
    fun `a row can be edited, and drug search is reachable even from an empty list`() {
        val calls = ReminderCalls()
        reminders(RemindersState(entries = listOf(ReminderEntry(reminder(), null)), loading = false), calls)

        compose.onNodeWithContentDescription("Edit Metformin").performClick()
        compose.onNodeWithText("+ Find a medication").performClick()

        assertEquals(listOf("edit:Metformin", "search"), calls.log)
    }

    @Test
    fun `an empty reminder list says where to add one`() {
        reminders(RemindersState(loading = false))

        compose.onNodeWithText("No reminders yet").assertExists()
        compose.onNodeWithText("+ Find a medication").assertExists()
    }

    // ---- adherence ----

    private fun adherence(state: AdherenceUiState) {
        compose.setContent { TaskRxTheme { AdherenceContent(state, onRefresh = {}) } }
    }

    private fun reading(stats: AdherenceStats) = AdherenceReading(stats, AdherencePeriod(0, 1))

    @Test
    fun `the headline is the percentage with the count beside it, in the wording the glossary allows`() {
        val days =
            listOf(
                DayAdherence("2026-08-10", 3, 4, 75.0),
                DayAdherence("2026-08-11", 0, 0, 0.0),
                DayAdherence("2026-08-12", 0, 2, 0.0),
                DayAdherence("2026-08-13", 2, 2, 100.0),
            )
        adherence(AdherenceUiState(reading(AdherenceStats(62.5, 5, 2, 1, 0, 8, days)), loading = false))

        compose.onNodeWithText("LAST 7 DAYS").assertExists()
        compose.onNodeWithText("63%").assertExists()
        compose.onNodeWithText("5 of 8 taken").assertExists()
        compose.onNodeWithText("Taken").assertExists()
        compose.onNodeWithText("Missed").assertExists()
        compose.onNodeWithText("Skipped").assertExists()
        compose.onNodeWithText("Snoozed").assertExists()
        compose.onNodeWithText("DAY BY DAY").assertExists()
    }

    @Test
    fun `a day with nothing due says so and does not show 0 percent`() {
        val days = listOf(DayAdherence("2026-08-11", 0, 0, 0.0), DayAdherence("2026-08-12", 1, 1, 100.0))
        adherence(AdherenceUiState(reading(AdherenceStats(100.0, 1, 0, 0, 0, 1, days)), loading = false))

        compose.onNodeWithText("None recorded").assertExists()
        compose.onAllNodesWithText("0%").assertCountEquals(0)
        compose.onNodeWithText("1/1").assertExists()
        // The headline and the full day both read 100%.
        compose.onAllNodesWithText("100%").assertCountEquals(2)
    }

    @Test
    fun `a snooze is explained only when there was one`() {
        val none = AdherenceStats(100.0, 1, 0, 0, 0, 1, listOf(DayAdherence("2026-08-12", 1, 1, 100.0)))
        adherence(AdherenceUiState(reading(none), loading = false))
        compose.onAllNodesWithText("snooze", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a snooze that lowered the figure is named`() {
        val one = AdherenceStats(50.0, 1, 0, 0, 1, 2, listOf(DayAdherence("2026-08-12", 1, 2, 50.0)))
        adherence(AdherenceUiState(reading(one), loading = false))
        compose.onNodeWithText("1 snooze counts against the figure — a medication put off is one not yet taken.").assertExists()
    }

    @Test
    fun `a week with nothing due is not shown as zero`() {
        adherence(AdherenceUiState(reading(AdherenceStats(0.0, 0, 0, 0, 0, 0, emptyList())), loading = false))

        compose.onNodeWithText("Nothing to report yet").assertExists()
        compose.onAllNodesWithText("0%").assertCountEquals(0)
    }

    @Test
    fun `a failed read shows the failure and not a figure`() {
        adherence(AdherenceUiState(loading = false, error = "sweep failed"))

        compose.onNodeWithText("Failed to load: sweep failed").assertExists()
        compose.onAllNodesWithText("%", substring = true).assertCountEquals(0)
    }

    // ---- supplies ----

    private class SupplyCalls {
        val log = mutableListOf<String>()
        var saved: SupplyDraft? = null

        fun actions() =
            SuppliesActions(
                onRefresh = { log += "refresh" },
                onSort = { log += "sort:${it.label}" },
                onEdit = { log += "edit:$it" },
                onCancelEdit = { log += "cancel" },
                onSave = { id, draft ->
                    log += "save:$id"
                    saved = draft
                },
                onOpenBacklog = { log += "backlog" },
            )
    }

    private fun supplies(state: SuppliesState, calls: SupplyCalls = SupplyCalls()) {
        compose.setContent { TaskRxTheme { SuppliesContent(state, calls.actions()) } }
    }

    private fun row(med: Reminder) = RefillRow(med, projectRefill(med, NOW, ZoneId.of("UTC")))

    @Test
    fun `a tracked supply shows how long it lasts and its working`() {
        val med = reminder("m", "Atorvastatin", pills = 12.0, perDose = 1.0)
        supplies(SuppliesState(rows = listOf(row(med)), loading = false))

        compose.onNodeWithText("12 days left").assertExists()
        compose.onNodeWithText("enough for 12 more", substring = true).assertExists()
        compose.onAllNodesWithText("Reorder now").assertCountEquals(0)
        compose.onAllNodesWithText("Out of stock").assertCountEquals(0)
    }

    @Test
    fun `a supply nobody recorded has no number and says what is missing`() {
        val noSupply = reminder("a", "A", pills = null, perDose = 1.0)
        val noDose = reminder("b", "B", pills = 10.0, perDose = null)
        supplies(SuppliesState(rows = listOf(row(noSupply), row(noDose)), loading = false))

        compose.onAllNodesWithText("Supply not tracked").assertCountEquals(2)
        compose.onNodeWithText("Add how many you have to see how long it lasts.").assertExists()
        compose.onNodeWithText("Add how many you take at a time to see how long it lasts.").assertExists()
        compose.onAllNodesWithText("0 days", substring = true).assertCountEquals(0)
    }

    @Test
    fun `supplies are sorted by name unless told otherwise, ignoring case`() {
        val rows = listOf(row(reminder("c", "cetirizine", pills = 1.0, perDose = 1.0)), row(reminder("a", "Atorvastatin", pills = 90.0, perDose = 1.0)), row(reminder("b", "Bupropion", pills = 30.0, perDose = 1.0)))

        assertEquals(listOf("Atorvastatin", "Bupropion", "cetirizine"), SuppliesState(rows = rows).sortedRows.map { it.medication.name })
    }

    @Test
    fun `sorting by pills left puts the fewest first and an unrecorded count last`() {
        val rows =
            listOf(
                row(reminder("a", "Alpha", pills = 90.0, perDose = 1.0)),
                row(reminder("b", "Beta", pills = null, perDose = 1.0)),
                row(reminder("c", "Gamma", pills = 4.0, perDose = 1.0)),
                row(reminder("d", "Delta", pills = 0.0, perDose = 1.0)),
            )

        val sorted = SuppliesState(rows = rows, sort = SupplySort.PillsLeft).sortedRows

        assertEquals(listOf("Delta", "Gamma", "Alpha", "Beta"), sorted.map { it.medication.name })
    }

    @Test
    fun `sorting by days left puts the soonest to run out first, and ties fall back to the name`() {
        val rows =
            listOf(
                row(reminder("a", "Zinc", pills = 10.0, perDose = 1.0)),
                row(reminder("b", "Untracked", pills = null, perDose = 1.0)),
                row(reminder("c", "Aspirin", pills = 10.0, perDose = 1.0)),
                row(reminder("d", "Iron", pills = 2.0, perDose = 1.0)),
            )

        val sorted = SuppliesState(rows = rows, sort = SupplySort.DaysLeft).sortedRows

        assertEquals(listOf("Iron", "Aspirin", "Zinc", "Untracked"), sorted.map { it.medication.name })
    }

    @Test
    fun `the sort chips choose the order`() {
        val calls = SupplyCalls()
        supplies(SuppliesState(rows = listOf(row(reminder("m", "Atorvastatin", pills = 12.0, perDose = 1.0))), loading = false), calls)

        compose.onNodeWithText("Pills left").performClick()
        compose.onNodeWithText("Days left").performClick()

        assertEquals(listOf("sort:Pills left", "sort:Days left"), calls.log)
    }

    @Test
    fun `a supply about to run out is badged, and an empty one says out of stock`() {
        val soon = reminder("a", "Soon", pills = 3.0, perDose = 1.0)
        val empty = reminder("b", "Empty", pills = 0.0, perDose = 1.0)
        supplies(SuppliesState(rows = listOf(row(soon), row(empty)), loading = false))

        compose.onNodeWithText("Reorder now").assertExists()
        compose.onNodeWithText("Out of stock").assertExists()
    }

    @Test
    fun `what the pass did to the Backlog is said, both ways`() {
        val calls = SupplyCalls()
        supplies(SuppliesState(raised = listOf("Refill A"), retired = listOf("Refill B", "Refill C"), loading = false), calls)

        compose.onNodeWithText("Added \"Refill A\" to your backlog.").assertExists()
        compose.onNodeWithText("Cleared 2 reminders you no longer need.").assertExists()
        compose.onNodeWithText("Open the backlog").performClick()
        assertEquals(listOf("backlog"), calls.log)
    }

    @Test
    fun `plurals and the single clearing read right`() {
        supplies(SuppliesState(raised = listOf("A", "B"), retired = listOf("Refill C"), loading = false))

        compose.onNodeWithText("Added 2 reminders to your backlog.").assertExists()
        compose.onNodeWithText("Cleared \"Refill C\" — you have enough now.").assertExists()
    }

    @Test
    fun `no medications says supplies are a medication thing`() {
        supplies(SuppliesState(loading = false))

        compose.onNodeWithText("No medications yet").assertExists()
        compose.onNodeWithText("Supplies are a medication thing — a task has nothing to run out of.").assertExists()
    }

    @Test
    fun `collecting a refill opens the editor with the default said out loud, and saves what was typed`() {
        val calls = SupplyCalls()
        val med = reminder("m", "Atorvastatin", pills = 3.0, perDose = 1.0)
        supplies(SuppliesState(rows = listOf(row(med)), editingId = "m", loading = false), calls)

        compose.onNodeWithText("Blank means 7 days.").assertExists()
        compose.onNodeWithText("Save").performClick()

        assertEquals(listOf("save:m"), calls.log)
        assertEquals(SupplyDraft(3.0, 1.0, 7), calls.saved)
    }

    @Test
    fun `a negative number of days is saved as none`() {
        val calls = SupplyCalls()
        val med = reminder("m", "Atorvastatin", pills = 3.0, perDose = 1.0)
        supplies(SuppliesState(rows = listOf(row(med)), editingId = "m", loading = false), calls)

        compose.onNode(hasText("7") and hasSetTextAction()).performTextReplacement("-3")
        compose.onNodeWithText("Save").performClick()

        assertEquals(SupplyDraft(3.0, 1.0, 0), calls.saved)
    }

    @Test
    fun `the editor can be cancelled, and is offered as a button when closed`() {
        val calls = SupplyCalls()
        val med = reminder("m", "Atorvastatin", pills = 3.0, perDose = 1.0)
        supplies(SuppliesState(rows = listOf(row(med)), editingId = "m", loading = false), calls)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), calls.log)
    }

    @Test
    fun `a closed row offers I collected a refill`() {
        val calls = SupplyCalls()
        val med = reminder("m", "Atorvastatin", pills = 3.0, perDose = 1.0)
        supplies(SuppliesState(rows = listOf(row(med)), loading = false), calls)

        compose.onNodeWithText("I collected a refill").performClick()

        assertEquals(listOf("edit:m"), calls.log)
        compose.onAllNodesWithText("Blank means", substring = true).assertCountEquals(0)
    }

    // ---- drug search ----

    private class SearchCalls {
        val log = mutableListOf<String>()
    }

    private fun search(state: DrugSearchState, calls: SearchCalls = SearchCalls()) {
        compose.setContent { TaskRxTheme { DrugSearchContent(state, onQuery = { calls.log += "q:$it" }, onPick = { calls.log += "pick:${it.rxcui}" }) } }
    }

    @Test
    fun `search starts with a prompt`() {
        search(DrugSearchState())
        compose.onNodeWithText("Type a drug name to search RxNorm.").assertExists()
    }

    @Test
    fun `search offline says it needs a connection and points to typing the name`() {
        search(DrugSearchState("met", SearchStatus.Unavailable))

        compose.onNodeWithText("Drug search is not available offline").assertExists()
        compose.onNodeWithText("It looks names up on a server. You can still add a medication by typing its name.").assertExists()
    }

    @Test
    fun `search results can be picked, and an empty result says no matches`() {
        val calls = SearchCalls()
        search(DrugSearchState("met", SearchStatus.Done(listOf(DrugMatch("6809", "metformin")))), calls)
        compose.onNodeWithText("metformin").performClick()
        compose.onNodeWithText("RxCUI 6809").assertExists()
        assertEquals(listOf("pick:6809"), calls.log)
    }

    @Test
    fun `search with nothing found, searching, or failing`() {
        search(DrugSearchState("zzz", SearchStatus.Done(emptyList())))
        compose.onNodeWithText("No matches for “zzz”.").assertExists()
    }

    @Test
    fun `a failed search shows its message`() {
        search(DrugSearchState("met", SearchStatus.Failed("timeout")))
        compose.onNodeWithText("Search failed: timeout").assertExists()
    }

    @Test
    fun `typing reaches the caller`() {
        val calls = SearchCalls()
        search(DrugSearchState(), calls)
        compose.onNodeWithText("Drug name").performTextInput("m")
        assertNotNull(calls.log.firstOrNull { it.startsWith("q:") })
    }

    @Test
    fun `a search in flight shows no stale rows`() {
        search(DrugSearchState("met", SearchStatus.Searching))
        compose.onAllNodesWithText("No matches", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Type a drug name", substring = true).assertCountEquals(0)
    }
}
