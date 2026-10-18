package com.taskrx.mobile.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.mobile.DAY
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.notifications.TapHandler
import com.taskrx.mobile.ui.search.DrugMatch
import com.taskrx.mobile.ui.search.DrugSearchService
import com.taskrx.mobile.ui.search.OfflineDrugSearch
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlarmManager

private val NOW = Instant.parse("2026-08-06T09:05:00Z").toEpochMilli()
private val NINE = Instant.parse("2026-08-06T09:00:00Z").toEpochMilli()

/**
 * The shell, navigation and the notification tap, end to end over the real container
 * (in-memory Room, Robolectric's AlarmManager, a test WorkManager). The point is the
 * seams: a tap resolved by :core arrives at the answer screen through the NavHost; an
 * answer comes back to the day showing it; Add writes and lands on the day.
 */
@RunWith(RobolectricTestRunner::class)
class AppShellTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var fx: Fixture

    private var drugSearch: DrugSearchService = OfflineDrugSearch

    @BeforeTest
    fun setUp() {
        fx = Fixture(NOW, drugSearch)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext<Application>(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        fx.container.notifications.currentUser.set(USER)
    }

    @AfterTest
    fun tearDown() = fx.close()

    private fun seed() = runBlocking {
        fx.reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY, name = "Metformin")
        fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + 2 * HOUR), createdAt = NOW - DAY, name = "Call pharmacy")
    }

    private fun show() = compose.setContent { TaskRxApp(fx.container) }

    private fun awaitText(text: String, substring: Boolean = false) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitGone(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    private fun tap(reminderId: String?, scheduledTime: Long?) {
        val intent = Intent(TapHandler.ACTION_TAP)
        reminderId?.let { intent.putExtra(TapHandler.EXTRA_REMINDER_ID, it) }
        scheduledTime?.let { intent.putExtra(TapHandler.EXTRA_SCHEDULED_TIME, it.toString()) }
        fx.container.notifications.taps.handle(intent)
    }

    @Test
    fun `the app opens on the day with both Kinds on it`() {
        seed()
        show()

        awaitText("Call pharmacy")

        compose.onNodeWithText("TaskRx").assertExists()
        compose.onNodeWithText("Metformin").assertExists()
        compose.onNodeWithText("Today").assertExists()
    }

    @Test
    fun `a notification tap lands on the answer screen for that moment, Kind-neutral`() {
        seed()
        show()
        awaitText("Metformin")

        tap("med", NINE)
        awaitText("Mark taken")

        compose.onNodeWithText("Answer a reminder").assertExists()
        compose.onNodeWithText("MEDICATION").assertExists()
        assertEquals(null, fx.container.notifications.taps.route.value, "the route is consumed once navigated")
    }

    @Test
    fun `a task's notification tap reads Mark done`() {
        seed()
        show()
        awaitText("Metformin")

        tap("chore", NOW + 2 * HOUR)
        awaitText("Mark done")

        compose.onNodeWithText("TASK").assertExists()
    }

    @Test
    fun `a tap with no usable data navigates nowhere`() {
        seed()
        show()
        awaitText("Metformin")

        tap(null, null)
        compose.waitForIdle()

        compose.onNodeWithText("TaskRx").assertExists()
        assertEquals(null, fx.container.notifications.taps.route.value)
    }

    @Test
    fun `a tap for a reminder this device lacks says so`() {
        show()
        awaitText("Nothing scheduled")

        tap("gone", NINE)
        awaitText("Could not load this reminder")

        compose.onNodeWithText("This reminder no longer exists.").assertExists()
    }

    @Test
    fun `answering returns to the day, which shows the answer and keeps the reminder`() {
        seed()
        show()
        awaitText("Metformin")
        tap("med", NINE)
        awaitText("Mark taken")

        compose.onNodeWithText("Mark taken").performClick()
        awaitText("Recorded")
        compose.onNodeWithText("Done").performClick()
        awaitText("Taken", substring = true)

        compose.onNodeWithText("Metformin").assertExists()
        compose.onNodeWithText("Today").assertExists()
        runBlocking {
            assertEquals(InstanceStatus.Completed, fx.container.instances.list(InstanceFilter(USER)).single { it.reminderId == "med" && it.scheduledTime == NINE }.status)
        }
    }

    @Test
    fun `a timeline row opens the same answer screen`() {
        seed()
        show()
        awaitText("Call pharmacy")

        compose.onNodeWithText("Call pharmacy").performClick()
        awaitText("Mark done")
    }

    @Test
    fun `the back arrow returns from a form to the day, and the day has none`() {
        show()
        awaitText("Nothing scheduled")
        compose.onAllNodesWithText("←").assertCountEquals(0)

        compose.onNodeWithText("+ Task").performClick()
        compose.onNodeWithText("New Task").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()

        awaitText("Nothing scheduled")
    }

    @Test
    fun `adding by phrase reads it, shows what was understood, saves, and lands on the day`() {
        show()
        awaitText("Nothing scheduled")

        compose.onNodeWithText("+ Add").performClick()
        compose.onNodeWithText("Add a reminder").assertExists()
        compose.onNodeWithText("What do you need to remember?").assertExists()
        compose.onNodeWithText("e.g. atorvastatin 20mg every morning with food").performTextInput("call pharmacy friday 2pm")
        compose.onNodeWithText("Review").performClick()

        compose.onNodeWithText("YOU TYPED").assertExists()
        compose.onNodeWithText("call pharmacy friday 2pm").assertExists()
        compose.onNodeWithText("Save task").performScrollTo().performClick()

        awaitGone("Save task")
        awaitText("Today")
        runBlocking {
            assertEquals(listOf("call pharmacy"), fx.container.reminders.list(USER).map { it.name })
        }
    }

    @Test
    fun `a kind correction swaps the form without retyping the phrase`() {
        show()
        awaitText("Nothing scheduled")
        compose.onNodeWithText("+ Add").performClick()
        compose.onNodeWithText("e.g. atorvastatin 20mg every morning with food").performTextInput("metformin 500mg twice daily")
        compose.onNodeWithText("Review").performClick()
        compose.onNodeWithText("Save medication").assertExists()

        compose.onNodeWithText("Task").performClick()

        compose.onNodeWithText("Save task").assertExists()
        compose.onAllNodesWithText("Dosage").assertCountEquals(0)
    }

    @Test
    fun `a phrase that cannot be read says so and still opens a form`() {
        show()
        awaitText("Nothing scheduled")
        compose.onNodeWithText("+ Add").performClick()
        compose.onNodeWithText("e.g. atorvastatin 20mg every morning with food").performTextInput("9am")
        compose.onNodeWithText("Review").performClick()

        compose.onNodeWithText("That phrase couldn’t be read").assertExists()
        compose.onNodeWithText("Save task").assertExists()
    }

    @Test
    fun `the manual medication form saves and lands on the day`() {
        show()
        awaitText("Nothing scheduled")

        compose.onNodeWithText("+ Medication").performClick()
        compose.onNodeWithText("New Medication").assertExists()
        compose.onNodeWithText("e.g. Metformin").performTextInput("Metformin")
        compose.onNodeWithText("Save medication").performScrollTo().performClick()

        awaitGone("Save medication")
        awaitText("Today")
        runBlocking {
            assertEquals(listOf("Metformin"), fx.container.reminders.list(USER).map { it.name })
        }
    }

    @Test
    fun `a toast confirms for a moment then goes`() {
        show()
        awaitText("Nothing scheduled")

        fx.container.toaster.show("Task added", com.taskrx.mobile.ui.components.ToastKind.Success)
        awaitText("Task added")
        compose.onNodeWithText("Task added").performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Task added").fetchSemanticsNodes().isEmpty() }
    }

    // ---- the rest of the screens ----

    @Test
    fun `the footer reaches each of the other screens`() {
        seed()
        show()
        awaitText("Metformin")

        val destinations =
            listOf(
                "Reminders" to "+ Find a medication",
                "Backlog" to "Nothing in your backlog",
                "How it's going" to "LAST 7 DAYS",
                "Supplies" to "Supply not tracked",
                // The sweep has already recorded yesterday's unanswered dose by the time it is opened.
                "History" to "YESTERDAY",
            )
        for ((link, arrived) in destinations) {
            compose.onNodeWithText(link).performClick()
            awaitText(arrived)
            compose.onNodeWithContentDescription("Back").performClick()
            awaitText("Metformin")
        }
    }

    @Test
    fun `completing a Backlog item records it where it was`() {
        runBlocking { fx.reminder("garage", ReminderKind.Task, noneTimes(), name = "Sort the garage") }
        show()
        awaitText("Nothing scheduled")

        compose.onNodeWithText("Backlog").performClick()
        awaitText("Sort the garage")
        compose.onNodeWithContentDescription("Complete Sort the garage").performClick()
        awaitText("Last done", substring = true)

        compose.onNodeWithText("Sort the garage").assertExists()
        runBlocking {
            val recorded = fx.container.instances.list(InstanceFilter(USER)).single()
            assertEquals(InstanceStatus.Completed, recorded.status)
            assertEquals(NOW, recorded.scheduledTime)
        }
    }

    @Test
    fun `editing a reminder from the list saves and comes back to the list`() {
        seed()
        show()
        awaitText("Metformin")
        compose.onNodeWithText("Reminders").performClick()
        awaitText("Call pharmacy")

        compose.onNodeWithContentDescription("Edit Call pharmacy").performClick()
        awaitText("Save changes")
        compose.onNodeWithText("Edit reminder").assertExists()
        compose.onNodeWithText("Call pharmacy").performTextReplacement("Call pharmacy today")
        compose.onNodeWithText("Save changes").performScrollTo().performClick()

        awaitText("Call pharmacy today")
        awaitText("+ Find a medication")
        runBlocking {
            assertEquals(setOf("Metformin", "Call pharmacy today"), fx.container.reminders.list(USER).map { it.name }.toSet())
        }
    }

    @Test
    fun `stopping a reminder asks, then it leaves the list but not the history`() {
        seed()
        show()
        awaitText("Metformin")
        compose.onNodeWithText("Reminders").performClick()
        awaitText("Call pharmacy")

        compose.onNodeWithContentDescription("Stop Call pharmacy").performClick()
        awaitText("Stop Call pharmacy?")
        compose.onNode(hasText("Stop") and hasAnyAncestor(isDialog())).performClick()

        awaitGone("Call pharmacy")
        compose.onNodeWithText("Metformin").assertExists()
        runBlocking { assertEquals(false, fx.container.reminders.get("chore")!!.active) }
    }

    @Test
    fun `drug search offline says so`() {
        show()
        awaitText("Nothing scheduled")
        compose.onNodeWithText("Reminders").performClick()
        compose.onNodeWithText("+ Find a medication").performClick()
        awaitText("Type a drug name to search RxNorm.")

        compose.onNodeWithText("Drug name").performTextInput("met")

        awaitText("Drug search is not available offline")
    }

    @Test
    fun `a drug picked from search starts the medication form with its name and RxCUI`() {
        drugSearch =
            object : DrugSearchService {
                override suspend fun search(query: String) = listOf(DrugMatch("6809", "metformin hydrochloride 500 MG"))
            }
        tearDown()
        setUp()
        show()
        awaitText("Nothing scheduled")
        compose.onNodeWithText("Reminders").performClick()
        compose.onNodeWithText("+ Find a medication").performClick()
        compose.onNodeWithText("Drug name").performTextInput("met")
        awaitText("metformin hydrochloride 500 MG")

        compose.onNodeWithText("metformin hydrochloride 500 MG").performClick()
        awaitText("Save medication")
        compose.onNodeWithText("metformin hydrochloride 500 MG").assertExists()
        compose.onNodeWithText("Save medication").performScrollTo().performClick()

        awaitGone("Save medication")
        runBlocking {
            val saved = fx.container.reminders.list(USER).single()
            assertEquals("metformin hydrochloride 500 MG", saved.name)
            assertEquals("6809", fx.container.reminders.getRxcui(saved.id))
        }
    }
}
