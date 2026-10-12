package com.taskrx.mobile

import androidx.test.core.app.ApplicationProvider
import com.taskrx.mobile.auth.AuthStatus
import com.taskrx.mobile.auth.FakeAuth
import com.taskrx.mobile.notifications.CurrentUser
import com.taskrx.mobile.notifications.NotificationPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionTest {
    private val current = CurrentUser(NotificationPrefs(ApplicationProvider.getApplicationContext()))
    private val auth = FakeAuth()
    private val session = Session(current, auth)
    private val job = Job()
    private val changes = mutableListOf<String>()

    @Before
    fun setUp() {
        current.set(null)
        Dispatchers.setMain(Dispatchers.Unconfined)
        session.follow(CoroutineScope(job + Dispatchers.Unconfined)) { changes += session.userId }
    }

    @After
    fun tearDown() {
        job.cancel()
        Dispatchers.resetMain()
        current.set(null)
    }

    @Test
    fun `signed out the day is the local user's and nobody is signed in`() {
        assertEquals(Session.LOCAL_USER_ID, session.userId)
        assertNull(session.signedInUserId)
        assertEquals(emptyList(), changes, "being signed out at launch changes nothing")
    }

    @Test
    fun `signing in makes the Clerk user the one whose day it is, durably`() {
        auth.state.value = AuthStatus.SignedIn("user_42")

        assertEquals("user_42", session.userId)
        assertEquals("user_42", session.signedInUserId)
        assertEquals("user_42", current.get(), "the boot receiver re-arms for them")
        assertEquals(listOf("user_42"), changes)
    }

    @Test
    fun `signing out returns to the local user`() {
        auth.state.value = AuthStatus.SignedIn("user_42")
        auth.state.value = AuthStatus.SignedOut

        assertEquals(Session.LOCAL_USER_ID, session.userId)
        assertNull(session.signedInUserId)
        assertEquals(Session.LOCAL_USER_ID, current.get())
        assertEquals(listOf("user_42", Session.LOCAL_USER_ID), changes)
    }

    @Test
    fun `being signed out at launch leaves a user the harness put there`() {
        current.set("harness-user")
        auth.state.value = AuthStatus.Loading
        auth.state.value = AuthStatus.SignedOut

        assertEquals("harness-user", session.userId)
        assertEquals(emptyList(), changes)
    }
}
