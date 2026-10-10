package com.taskrx.mobile.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
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
import com.taskrx.core.domain.onceTimes
import com.taskrx.mobile.DAY
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.notifications.TapHandler
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

    @BeforeTest
    fun setUp() {
        fx = Fixture(NOW)
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

    private fun awaitText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

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
        awaitText("Taken")

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
}
