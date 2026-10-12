package com.taskrx.mobile.auth

import android.content.Context
import com.clerk.api.Clerk
import com.clerk.api.auth.types.VerificationType
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.network.serialization.errorMessage
import com.clerk.api.signin.SignIn
import com.clerk.api.signin.verifyCode
import com.clerk.api.signup.SignUp
import com.clerk.api.signup.sendEmailCode
import com.clerk.api.signup.verifyCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Clerk-backed [AuthGateway]. A thin adapter over the SDK's statics, deliberately free of
 * decisions (the screens' logic is in `AuthViewModel`, tested against a fake). It needs a real
 * Clerk instance and a person with an inbox, so it is exercised by hand, not by a unit test.
 *
 * Email code is chosen explicitly. The SDK's own `sendCode` helpers prefer an email LINK when
 * the instance supports one, and the screens ask for a passcode.
 */
class ClerkAuthGateway(context: Context, publishableKey: String, scope: CoroutineScope) : AuthGateway {
    init {
        Clerk.initialize(context.applicationContext, publishableKey)
    }

    override val configured: Boolean = true

    override val status: StateFlow<AuthStatus> =
        combine(Clerk.isInitialized, Clerk.userFlow) { initialized, user ->
            when {
                !initialized -> AuthStatus.Loading
                user == null -> AuthStatus.SignedOut
                else -> AuthStatus.SignedIn(user.id)
            }
        }.stateIn(scope, SharingStarted.Eagerly, AuthStatus.Loading)

    private var signIn: SignIn? = null
    private var signUp: SignUp? = null

    override suspend fun startSignIn(email: String): AuthResult =
        when (val result = Clerk.auth.signInWithOtp { this.email = email }) {
            is ClerkResult.Success -> {
                signIn = result.value
                AuthResult.Ok
            }
            is ClerkResult.Failure -> AuthResult.Failed(result.errorMessage)
        }

    override suspend fun completeSignIn(code: String): AuthResult {
        val pending = signIn ?: return AuthResult.Failed("Request a passcode first.")
        return when (val result = pending.verifyCode(code)) {
            is ClerkResult.Success -> activate(result.value.createdSessionId)
            is ClerkResult.Failure -> AuthResult.Failed(result.errorMessage)
        }
    }

    override suspend fun startSignUp(email: String): AuthResult {
        val created =
            when (val result = Clerk.auth.signUp { this.email = email }) {
                is ClerkResult.Success -> result.value
                is ClerkResult.Failure -> return AuthResult.Failed(result.errorMessage)
            }
        return when (val result = created.sendEmailCode()) {
            is ClerkResult.Success -> {
                signUp = result.value
                AuthResult.Ok
            }
            is ClerkResult.Failure -> AuthResult.Failed(result.errorMessage)
        }
    }

    override suspend fun completeSignUp(code: String): AuthResult {
        val pending = signUp ?: return AuthResult.Failed("Request a code first.")
        return when (val result = pending.verifyCode(code, VerificationType.EMAIL)) {
            is ClerkResult.Success -> activate(result.value.createdSessionId)
            is ClerkResult.Failure -> AuthResult.Failed(result.errorMessage)
        }
    }

    /** Makes the session the verification created the active one, unless the SDK already did. */
    private suspend fun activate(sessionId: String?): AuthResult {
        if (Clerk.session != null) return AuthResult.Ok
        if (sessionId == null) return AuthResult.Failed("Sign-in did not complete. Please try again.")
        return when (val result = Clerk.auth.setActive(sessionId)) {
            is ClerkResult.Success -> AuthResult.Ok
            is ClerkResult.Failure -> AuthResult.Failed(result.errorMessage)
        }
    }

    override suspend fun signOut() {
        Clerk.auth.signOut()
    }
}
