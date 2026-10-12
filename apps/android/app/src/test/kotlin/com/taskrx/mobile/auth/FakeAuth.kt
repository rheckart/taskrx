package com.taskrx.mobile.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An [AuthGateway] the test drives: calls are recorded and a successful code signs the user in. */
class FakeAuth(
    override val configured: Boolean = true,
    initial: AuthStatus = AuthStatus.SignedOut,
    val userId: String = "user_clerk_1",
) : AuthGateway {
    val state = MutableStateFlow(initial)
    override val status: StateFlow<AuthStatus> = state

    val calls = mutableListOf<String>()
    var result: AuthResult = AuthResult.Ok
    var failure: Exception? = null

    private fun step(name: String, signsIn: Boolean = false): AuthResult {
        calls += name
        failure?.let { throw it }
        if (signsIn && result == AuthResult.Ok) state.value = AuthStatus.SignedIn(userId)
        return result
    }

    override suspend fun startSignIn(email: String) = step("startSignIn:$email")

    override suspend fun completeSignIn(code: String) = step("completeSignIn:$code", signsIn = true)

    override suspend fun startSignUp(email: String) = step("startSignUp:$email")

    override suspend fun completeSignUp(code: String) = step("completeSignUp:$code", signsIn = true)

    override suspend fun signOut() {
        calls += "signOut"
        state.value = AuthStatus.SignedOut
    }
}
