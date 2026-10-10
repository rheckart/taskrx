package com.taskrx.mobile.auth

import android.content.Context
import com.clerk.api.Clerk
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.session.GetTokenOptions
import dev.convex.android.AuthProvider
import dev.convex.android.AuthState
import dev.convex.android.ConvexClientWithAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The name of the Clerk JWT template the Convex deployment trusts (`applicationID: "convex"`). */
const val CONVEX_JWT_TEMPLATE = "convex"

/**
 * Gives Convex the token of the signed-in Clerk session.
 *
 * Written here instead of using `com.clerk:clerk-convex-kotlin` (which does exist): that
 * provider calls `Clerk.auth.getToken()` with no template, and the plain session token has no
 * `aud` claim, so a deployment whose `auth.config.ts` says `applicationID: "convex"` rejects
 * it. The Expo app fetched the `convex` JWT template; so does this. `skipCache` is on because
 * the library calls [loginFromCache] to force a refresh, and a cached token is the one that
 * just failed.
 */
class ClerkConvexAuthProvider : AuthProvider<String> {
    override suspend fun login(context: Context, onIdToken: (String?) -> Unit): Result<String> = token()

    override suspend fun loginFromCache(onIdToken: (String?) -> Unit): Result<String> = token()

    override suspend fun logout(context: Context): Result<Void?> = Result.success(null)

    override fun extractIdToken(authResult: String): String = authResult

    private suspend fun token(): Result<String> {
        if (Clerk.session == null) return Result.failure(IllegalStateException("No active Clerk session"))
        return when (val result = Clerk.auth.getToken(GetTokenOptions(template = CONVEX_JWT_TEMPLATE, skipCache = true))) {
            is ClerkResult.Success -> Result.success(result.value)
            is ClerkResult.Failure -> Result.failure(result.throwable ?: IllegalStateException("Clerk token request failed"))
        }
    }
}

/**
 * The Convex client wired to Clerk: when the signed-in state changes, the client logs in from
 * the Clerk session (or out). [ready] is true once Convex holds a valid token - sync waits
 * for it, because every function refuses an unauthenticated caller.
 */
class ConvexSession(
    deploymentUrl: String,
    auth: AuthGateway,
    private val context: Context,
    scope: CoroutineScope,
) {
    val client = ConvexClientWithAuth(deploymentUrl, ClerkConvexAuthProvider())

    val ready: StateFlow<Boolean> =
        client.authState.map { it is AuthState.Authenticated }.stateIn(scope, SharingStarted.Eagerly, false)

    /** The Clerk session can trail the user by a beat, so a first miss is tried again. */
    private suspend fun loginWithRetry() {
        repeat(LOGIN_ATTEMPTS) {
            if (client.loginFromCache().isSuccess) return
            delay(LOGIN_RETRY_MS)
        }
    }

    init {
        scope.launch {
            auth.status.collect { status ->
                when (status) {
                    is AuthStatus.SignedIn -> loginWithRetry()
                    AuthStatus.SignedOut -> client.logout(context)
                    AuthStatus.Loading -> Unit
                }
            }
        }
    }
}

private const val LOGIN_ATTEMPTS = 3
private const val LOGIN_RETRY_MS = 1_000L
