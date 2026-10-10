package com.taskrx.mobile.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Who is signed in, as far as the app can tell. */
sealed interface AuthStatus {
    /** The sign-in SDK has not finished starting; nothing can be said yet. */
    data object Loading : AuthStatus

    data object SignedOut : AuthStatus

    /** [userId] is the Clerk user id - the `subject` the server scopes every row by. */
    data class SignedIn(val userId: String) : AuthStatus
}

sealed interface AuthResult {
    data object Ok : AuthResult

    /** [message] is shown to the person as it stands. */
    data class Failed(val message: String) : AuthResult
}

/**
 * Email-passcode sign-in and sign-up: the seam over the Clerk SDK, so the screens' logic is
 * tested against a fake. Each flow is two steps, the same as the Expo `(auth)` screens: ask
 * for a code to be sent, then submit it.
 */
interface AuthGateway {
    /**
     * False when this build has no Clerk key: the app then runs local-only, with no sign-in
     * screen, exactly as the Expo app did without `EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY`.
     */
    val configured: Boolean

    val status: StateFlow<AuthStatus>

    suspend fun startSignIn(email: String): AuthResult

    suspend fun completeSignIn(code: String): AuthResult

    suspend fun startSignUp(email: String): AuthResult

    suspend fun completeSignUp(code: String): AuthResult

    suspend fun signOut()
}

/** The gateway of a build with no sign-in configured. */
object LocalOnlyAuth : AuthGateway {
    override val configured: Boolean = false
    override val status: StateFlow<AuthStatus> = MutableStateFlow(AuthStatus.SignedOut)

    private val unavailable = AuthResult.Failed("Sign-in is not configured in this build.")

    override suspend fun startSignIn(email: String): AuthResult = unavailable

    override suspend fun completeSignIn(code: String): AuthResult = unavailable

    override suspend fun startSignUp(email: String): AuthResult = unavailable

    override suspend fun completeSignUp(code: String): AuthResult = unavailable

    override suspend fun signOut() = Unit
}
