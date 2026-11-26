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
import com.taskrx.core.domain.asNeededTimes
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.mobile.DAY
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.notifications.TapHandler
import com.taskrx.mobile.ui.notices.ShareIntake
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

    private lateinit var shareIntake: ShareIntake

    private var drugSearch: DrugSearchService = OfflineDrugSearch

    @BeforeTest
    fun setUp() {
        fx = Fixture(NOW, drugSearch)
        shareIntake = ShareIntake()
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

    private fun show() = compose.setContent { TaskRxApp(fx.container, shareIntake = shareIntake) }

    private fun awaitText(text: String, substring: Boolean = false) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitGone(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    /** The ⋮ menu, which holds every screen that is not the day. */
    private fun openMenu(item: String) {
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText(item).performClick()
    }

    /** The round + button, which holds every way of making something new. */
    private fun addMenu(item: String) {
        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithText(item).performClick()
    }

    /** Switches the open list, on Today or Reminders, to its Tasks tab. */
    private fun showTasks() = compose.onNodeWithText("Tasks", substring = true).performClick()

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

        awaitText("Metformin")
        showTasks()
        awaitText("Call pharmacy")

        compose.onNodeWithText("TaskRx").assertExists()
        compose.onNodeWithText("DUE TODAY").assertExists()
    }

    @Test
    fun `an as-needed medication shows while its window is open, and Log a dose names the next time`() {
        runBlocking {
            val r = fx.reminder("prn", ReminderKind.Medication, asNeededTimes(240, 5), createdAt = NOW - DAY, name = "Tylenol")
            // A dose four and a half hours ago: the wait is over and the one-hour window is still open.
            val taken = NOW - 5 * HOUR + 30 * 60_000L
            val earlier = fx.container.instances.create(CreateReminderInstanceInput(r.userId, r.id, r.kind, taken))
            fx.container.instances.markCompleted(earlier.id, taken)
        }
        show()
        awaitText("Ready now")

        compose.onNodeWithContentDescription("Take Tylenol now").performClick()

        // Taking it starts the wait again, so Today stops showing a countdown or a Take anyway button.
        awaitGone("Ready now")
        compose.onAllNodesWithText("Next dose in", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Take anyway").assertCountEquals(0)

        addMenu("Log a dose")
        awaitText("Next dose in 4 h")
        compose.onNodeWithText("Allowed at", substring = true).assertExists()
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
        awaitText("Nothing due today")

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
        compose.onNodeWithText("Done").performScrollTo().performClick()
        // Hide done is on to begin with, so the answered dose leaves the list until it is turned off.
        awaitText("All done")
        compose.onNodeWithText("Show done").performClick()
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
        awaitText("Metformin")
        showTasks()
        awaitText("Call pharmacy")

        compose.onNodeWithText("Call pharmacy").performClick()
        awaitText("Mark done")
    }

    @Test
    fun `the back arrow returns from a form to the day, and the day has none`() {
        show()
        awaitText("Nothing due today")
        compose.onAllNodesWithText("←").assertCountEquals(0)

        addMenu("New task")
        compose.onNodeWithText("New Task").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()

        awaitText("Nothing due today")
    }

    @Test
    fun `adding by phrase reads it, shows what was understood, saves, and lands on the day`() {
        show()
        awaitText("Nothing due today")

        addMenu("Add by typing")
        compose.onNodeWithText("Add a reminder").assertExists()
        compose.onNodeWithText("What do you need to remember?").assertExists()
        compose.onNodeWithText("e.g. atorvastatin 20mg every morning with food").performTextInput("call pharmacy friday 2pm")
        compose.onNodeWithText("Review").performClick()

        compose.onNodeWithText("YOU TYPED").assertExists()
        compose.onNodeWithText("call pharmacy friday 2pm").assertExists()
        compose.onNodeWithText("Save task").performScrollTo().performClick()

        awaitGone("Save task")
        awaitText("call pharmacy")
        runBlocking {
            assertEquals(listOf("call pharmacy"), fx.container.reminders.list(USER).map { it.name })
        }
    }

    @Test
    fun `a kind correction swaps the form without retyping the phrase`() {
        show()
        awaitText("Nothing due today")
        addMenu("Add by typing")
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
        awaitText("Nothing due today")
        addMenu("Add by typing")
        compose.onNodeWithText("e.g. atorvastatin 20mg every morning with food").performTextInput("9am")
        compose.onNodeWithText("Review").performClick()

        compose.onNodeWithText("That phrase couldn’t be read").assertExists()
        compose.onNodeWithText("Save task").assertExists()
    }

    @Test
    fun `the manual medication form saves and lands on the day`() {
        show()
        awaitText("Nothing due today")

        addMenu("New medication")
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
        awaitText("Nothing due today")

        fx.container.toaster.show("Task added", com.taskrx.mobile.ui.components.ToastKind.Success)
        awaitText("Task added")
        compose.onNodeWithText("Task added").performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Task added").fetchSemanticsNodes().isEmpty() }
    }

    // ---- the rest of the screens ----

    @Test
    fun `the menu reaches each of the other screens`() {
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
            openMenu(link)
            awaitText(arrived)
            compose.onNodeWithContentDescription("Back").performClick()
            awaitText("Metformin")
        }
    }

    @Test
    fun `completing a Backlog item records it where it was`() {
        runBlocking { fx.reminder("garage", ReminderKind.Task, noneTimes(), name = "Sort the garage") }
        show()
        awaitText("Nothing due today")

        openMenu("Backlog")
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
        openMenu("Reminders")
        showTasks()
        awaitText("Call pharmacy")

        compose.onNodeWithContentDescription("Edit Call pharmacy").performClick()
        awaitText("Save changes")
        compose.onNodeWithText("Edit reminder").assertExists()
        compose.onNodeWithText("Call pharmacy").performTextReplacement("Call pharmacy today")
        compose.onNodeWithText("Save changes").performScrollTo().performClick()

        awaitText("Call pharmacy today")
        runBlocking {
            assertEquals(setOf("Metformin", "Call pharmacy today"), fx.container.reminders.list(USER).map { it.name }.toSet())
        }
    }

    @Test
    fun `a medication can be edited from Supplies, and returns there`() {
        seed()
        show()
        awaitText("Metformin")
        openMenu("Supplies")
        awaitText("Supply not tracked")

        compose.onNodeWithContentDescription("Edit Metformin").performClick()
        awaitText("Save changes")
        compose.onNodeWithText("As needed").performClick()
        compose.onNodeWithText("Save changes").performScrollTo().performClick()

        awaitText("Supply not tracked")
        runBlocking {
            assertEquals(asNeededTimes(240), fx.container.reminders.get("med")!!.schedule)
        }
    }

    @Test
    fun `an existing medication can be edited to be taken as needed from Reminders`() {
        seed()
        show()
        awaitText("Metformin")
        openMenu("Reminders")
        awaitText("Metformin")

        compose.onNodeWithContentDescription("Edit Metformin").performClick()
        awaitText("Save changes")
        compose.onNodeWithText("As needed").performClick()
        compose.onNodeWithText("At least this long between doses").assertExists()
        compose.onNodeWithText("Save changes").performScrollTo().performClick()

        awaitText("+ Find a medication")
        runBlocking {
            assertEquals(asNeededTimes(240), fx.container.reminders.get("med")!!.schedule)
        }
    }

    @Test
    fun `stopping a reminder asks, then it leaves the list but not the history`() {
        seed()
        show()
        awaitText("Metformin")
        openMenu("Reminders")
        showTasks()
        awaitText("Call pharmacy")

        compose.onNodeWithContentDescription("Stop Call pharmacy").performClick()
        awaitText("Stop Call pharmacy?")
        compose.onNode(hasText("Stop") and hasAnyAncestor(isDialog())).performClick()

        awaitGone("Call pharmacy")
        runBlocking {
            assertEquals(false, fx.container.reminders.get("chore")!!.active)
            assertEquals(true, fx.container.reminders.get("med")!!.active)
        }
    }

    @Test
    fun `drug search offline says so`() {
        show()
        awaitText("Nothing due today")
        openMenu("Reminders")
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
        awaitText("Nothing due today")
        openMenu("Reminders")
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

    @Test
    fun `sharing text can jump straight into a pre-filled New Task and save it`() {
        show()
        awaitText("Nothing due today")

        val shared = "Pick up milk + eggs & 100%? https://example.com/a?x=1&y=2%20z#top\nFrom Trader Joe's on 5th"
        shareIntake.offer(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shared)
            },
        )
        awaitText("Create new task")
        compose.onNodeWithText("Create new task").performClick()

        awaitText("Save task")
        compose.onNodeWithText("New Task").assertExists()
        compose.onNodeWithText("Save task").performScrollTo().performClick()

        awaitGone("Save task")
        runBlocking {
            val saved = fx.container.reminders.list(USER).single()
            assertEquals(ReminderKind.Task, saved.kind)
            assertEquals("Pick up milk + eggs & 100%? https://example.com/a?x=1&y=2%20z#top", saved.name)
            assertEquals(shared, saved.instructions)
        }
    }

    @Test
    fun `sharing text on cold start opens ShareScreen populated even with a second shell mounted`() {
        val backgroundIntake = ShareIntake()
        val foregroundIntake = ShareIntake()
        foregroundIntake.offer(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Buy coffee beans")
            },
        )
        compose.setContent {
            // Two separate Activity instances in the same process: background stays on Today,
            // foreground receives the share with its text intact.
            TaskRxApp(fx.container, shareIntake = backgroundIntake)
            TaskRxApp(fx.container, shareIntake = foregroundIntake)
        }

        awaitText("Create new task")
        // Both the Subject input and the Shared text body show the shared message.
        compose.onAllNodesWithText("Buy coffee beans").assertCountEquals(2)
        compose.onNodeWithText("Create new task").performClick()
        awaitText("Save task")
        compose.onNodeWithText("Save task").performScrollTo().performClick()

        awaitGone("Save task")
        runBlocking {
            assertEquals(listOf("Buy coffee beans"), fx.container.reminders.list(USER).map { it.name })
        }
    }

    @Test
    fun `a second share while on ShareScreen replaces it with the new text`() {
        show()
        awaitText("Nothing due today")

        shareIntake.offer(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "First message")
            },
        )
        awaitText("First message")

        shareIntake.offer(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Second message")
            },
        )
        awaitText("Second message")
        compose.onNodeWithText("Save to Notices").performClick()

        awaitText("1 waiting for you")
        runBlocking {
            assertEquals(listOf("Second message"), fx.container.notices.list(USER).map { it.title })
        }
    }

    @Test
    fun `sharing text can still be saved to Notices`() {
        show()
        awaitText("Nothing due today")

        shareIntake.offer(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Bins move to Thursday")
            },
        )
        awaitText("Save to Notices")
        compose.onNodeWithText("Save to Notices").performScrollTo().performClick()

        awaitText("1 waiting for you")
        runBlocking {
            assertEquals(listOf("Bins move to Thursday"), fx.container.notices.list(USER).map { it.title })
        }
    }

    @Test
    fun `highlighting text via ACTION_PROCESS_TEXT opens a pre-filled New Task directly`() {
        show()
        awaitText("Nothing due today")

        shareIntake.offer(
            Intent(Intent.ACTION_PROCESS_TEXT).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, "Call the pharmacy before 5pm")
            },
        )
        awaitText("Save task")
        compose.onNodeWithText("New Task").assertExists()
        compose.onNodeWithText("Call the pharmacy before 5pm").assertExists()
        compose.onNodeWithText("Save task").performScrollTo().performClick()

        awaitGone("Save task")
        runBlocking {
            val saved = fx.container.reminders.list(USER).single()
            assertEquals(ReminderKind.Task, saved.kind)
            assertEquals("Call the pharmacy before 5pm", saved.name)
            assertEquals(null, saved.instructions)
            val expectedAt =
                java.time.ZonedDateTime.of(2026, 8, 6, 17, 0, 0, 0, java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            assertEquals(onceTimes(expectedAt), saved.schedule)
        }
    }

    @Test
    fun `sharing text with a date and time shows When on ShareScreen and pre-fills New Task`() {
        show()
        awaitText("Nothing due today")

        shareIntake.offer(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Dentist appointment Dec 4 at 2:30pm")
            },
        )
        awaitText("Read from “Dec 4 at 2:30pm”", substring = true)
        compose.onNodeWithText("Create new task").performScrollTo().performClick()

        awaitText("Save task")
        compose.onNodeWithText("Save task").performScrollTo().performClick()

        awaitGone("Save task")
        runBlocking {
            val saved = fx.container.reminders.list(USER).single()
            assertEquals("Dentist appointment Dec 4 at 2:30pm", saved.name)
            val expectedAt =
                java.time.ZonedDateTime.of(2026, 12, 4, 14, 30, 0, 0, java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            assertEquals(onceTimes(expectedAt), saved.schedule)
        }
    }
}
