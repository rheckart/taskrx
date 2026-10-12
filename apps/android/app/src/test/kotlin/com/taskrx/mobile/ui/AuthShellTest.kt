package com.taskrx.mobile.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.taskrx.core.sync.SyncBadge
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.auth.AuthStatus
import com.taskrx.mobile.auth.FakeAuth
import com.taskrx.mobile.data.db.TaskRxDatabase
import com.taskrx.mobile.sync.FakeConnectivity
import com.taskrx.mobile.sync.FakeGateway
import com.taskrx.mobile.ui.components.syncBadgeDescription
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlarmManager

/** The sign-in gate and the header badge, over a real container with a fake Clerk. */
@RunWith(RobolectricTestRunner::class)
class AuthShellTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var database: TaskRxDatabase
    private lateinit var auth: FakeAuth
    private val ready = MutableStateFlow(false)

    private fun container(configured: Boolean = true, initial: AuthStatus = AuthStatus.SignedOut): AppContainer {
        auth = FakeAuth(configured = configured, initial = initial)
        return AppContainer(
            ApplicationProvider.getApplicationContext(),
            database,
            auth = auth,
            remote = AppContainer.Remote(FakeGateway(), ready, FakeConnectivity()),
        )
    }

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TaskRxDatabase::class.java).allowMainThreadQueries().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext<Application>(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun awaitText(text: String, substring: Boolean = false) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun gone(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()

    @Test
    fun `signed out, a build with sign-in shows the sign-in screen and not the day`() {
        val c = container()
        compose.setContent { TaskRxApp(c) }

        awaitText("Sign in to TaskRx")
        compose.onNodeWithText("Send passcode").assertExists()
        assert(gone("Today"))
        assert(gone("Sign out"))
    }

    @Test
    fun `while the SDK starts nothing is offered`() {
        val c = container(initial = AuthStatus.Loading)
        compose.setContent { TaskRxApp(c) }

        compose.waitForIdle()
        assert(gone("Sign in to TaskRx"))
        assert(gone("Today"))
    }

    @Test
    fun `an emailed passcode signs in and the day replaces the screen`() {
        val c = container()
        compose.setContent { TaskRxApp(c) }
        awaitText("Sign in to TaskRx")

        compose.onNodeWithText("Email address").performTextInput("me@example.com")
        compose.onNodeWithText("Send passcode").performClick()
        awaitText("Enter the 6-digit code", substring = true)
        compose.onNodeWithText("Passcode").performTextInput("123456")
        compose.onNodeWithText("Verify").performClick()

        awaitText("Today")
        assertEquals(listOf("startSignIn:me@example.com", "completeSignIn:123456"), auth.calls)
        assert(gone("Sign in to TaskRx"))
        assertEquals("user_clerk_1", c.session.userId)
    }

    @Test
    fun `an error from Clerk is shown on the screen`() {
        val c = container()
        auth.result = com.taskrx.mobile.auth.AuthResult.Failed("Couldn't find your account.")
        compose.setContent { TaskRxApp(c) }
        awaitText("Sign in to TaskRx")

        compose.onNodeWithText("Email address").performTextInput("x@example.com")
        compose.onNodeWithText("Send passcode").performClick()

        awaitText("Couldn't find your account.")
    }

    @Test
    fun `the sign-up screen is one tap away and asks for a verification code`() {
        val c = container()
        compose.setContent { TaskRxApp(c) }
        awaitText("Sign in to TaskRx")

        compose.onNodeWithText("Don't have an account? Sign up", substring = true).performClick()
        awaitText("Create your account")
        compose.onNodeWithText("Email address").performTextInput("new@example.com")
        compose.onNodeWithText("Send code").performClick()
        awaitText("Verification code")
        compose.onNodeWithText("Verification code").performTextInput("654321")
        compose.onNodeWithText("Verify").performClick()

        awaitText("Today")
        assertEquals(listOf("startSignUp:new@example.com", "completeSignUp:654321"), auth.calls)
    }

    @Test
    fun `signed in, the header carries the sync badge and the day has a sign-out`() {
        val c = container(initial = AuthStatus.SignedIn("user_clerk_1"))
        compose.setContent { TaskRxApp(c) }

        awaitText("Today")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(syncBadgeDescription(SyncBadge.Synced)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Sign out").performClick()
        awaitText("Sign in to TaskRx")
        assertEquals(listOf("signOut"), auth.calls)
    }

    @Test
    fun `a build with no sign-in goes straight to the day and shows no badge`() {
        val c = container(configured = false)
        compose.setContent { TaskRxApp(c) }

        awaitText("Today")
        assert(gone("Sign in to TaskRx"))
        assert(gone("Sign out"))
        assertEquals(0, compose.onAllNodesWithContentDescription(syncBadgeDescription(SyncBadge.Offline)).fetchSemanticsNodes().size)
    }
}
