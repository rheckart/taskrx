package com.taskrx.mobile.auth

import com.taskrx.mobile.ui.auth.AuthMode
import com.taskrx.mobile.ui.auth.AuthViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val auth = FakeAuth()

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = AuthViewModel(auth)

    @Test
    fun `asks for an email before sending anything`() {
        val vm = vm()
        vm.setEmail("   ")
        vm.sendCode()
        assertEquals("Please enter your email address.", vm.state.value.error)
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun `sign-in sends a passcode to the trimmed email, then asks for the code`() {
        val vm = vm()
        vm.setEmail("  me@example.com ")
        vm.sendCode()

        assertEquals(listOf("startSignIn:me@example.com"), auth.calls)
        assertTrue(vm.state.value.codeSent)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a correct passcode signs in`() {
        val vm = vm()
        vm.setEmail("me@example.com")
        vm.sendCode()
        vm.setCode(" 123456 ")
        vm.verify()

        assertEquals("completeSignIn:123456", auth.calls.last())
        assertEquals(AuthStatus.SignedIn("user_clerk_1"), auth.state.value)
    }

    @Test
    fun `an empty passcode is refused with the sign-in wording`() {
        val vm = vm()
        vm.setEmail("me@example.com")
        vm.sendCode()
        vm.verify()
        assertEquals("Please enter the passcode.", vm.state.value.error)
        assertEquals(1, auth.calls.size)
    }

    @Test
    fun `sign-up sends a verification code and verifies it, with its own wording`() {
        val vm = vm()
        vm.switchMode(AuthMode.SignUp)
        vm.setEmail("new@example.com")
        vm.sendCode()
        vm.verify()
        assertEquals("Please enter the verification code.", vm.state.value.error)

        vm.setCode("654321")
        vm.verify()

        assertEquals(listOf("startSignUp:new@example.com", "completeSignUp:654321"), auth.calls)
        assertEquals(AuthStatus.SignedIn("user_clerk_1"), auth.state.value)
    }

    @Test
    fun `a failure is shown as it stands and the step can be retried`() {
        auth.result = AuthResult.Failed("Couldn't find your account.")
        val vm = vm()
        vm.setEmail("nobody@example.com")
        vm.sendCode()

        assertEquals("Couldn't find your account.", vm.state.value.error)
        assertFalse(vm.state.value.codeSent)
        assertFalse(vm.state.value.busy)

        auth.result = AuthResult.Ok
        vm.sendCode()
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.codeSent)
    }

    @Test
    fun `a wrong code leaves the code step in place`() {
        val vm = vm()
        vm.setEmail("me@example.com")
        vm.sendCode()
        auth.result = AuthResult.Failed("Incorrect code")
        vm.setCode("000000")
        vm.verify()

        assertEquals("Incorrect code", vm.state.value.error)
        assertTrue(vm.state.value.codeSent)
        assertEquals(AuthStatus.SignedOut, auth.state.value)
    }

    @Test
    fun `an exception from the SDK becomes a message instead of a crash`() {
        auth.failure = java.io.IOException("offline")
        val vm = vm()
        vm.setEmail("me@example.com")
        vm.sendCode()
        assertEquals("offline", vm.state.value.error)

        auth.failure = IllegalStateException()
        vm.sendCode()
        assertEquals("Something went wrong. Please try again.", vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `changing the email goes back to the email step`() {
        val vm = vm()
        vm.setEmail("me@example.com")
        vm.sendCode()
        vm.setCode("12")
        vm.changeEmail()

        assertFalse(vm.state.value.codeSent)
        assertEquals("", vm.state.value.code)
        assertEquals("me@example.com", vm.state.value.email)
    }

    @Test
    fun `switching screens starts over but keeps the email typed`() {
        val vm = vm()
        vm.setEmail("me@example.com")
        vm.sendCode()
        vm.switchMode(AuthMode.SignUp)

        assertEquals(AuthMode.SignUp, vm.state.value.mode)
        assertEquals("me@example.com", vm.state.value.email)
        assertFalse(vm.state.value.codeSent)
    }

    @Test
    fun `a build with no sign-in says so`() {
        val vm = AuthViewModel(LocalOnlyAuth)
        vm.setEmail("me@example.com")
        vm.sendCode()
        assertEquals("Sign-in is not configured in this build.", vm.state.value.error)
    }
}
