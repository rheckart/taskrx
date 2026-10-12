package com.taskrx.mobile.auth

import android.content.Context
import android.util.Log
import com.clerk.api.Clerk
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.session.GetTokenOptions
import com.taskrx.core.sync.backoffMillis
import dev.convex.android.AuthProvider
import dev.convex.android.AuthState
import dev.convex.android.ConvexClientWithAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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
            is ClerkResult.Failure -> {
                val error = result.throwable ?: IllegalStateException("Clerk token request failed")
                // Otherwise silent: the library swallows it and the server just says "Not authenticated".
                Log.w(TAG, "Clerk token request failed: $error")
                Result.failure(error)
            }
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
    private val auth: AuthGateway,
    private val context: Context,
    private val scope: CoroutineScope,
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

    private var relogin: Job? = null

    /**
     * Logs in again from the Clerk session, for a server that refused the token it holds (see
     * [com.taskrx.mobile.sync.ReauthenticatingGateway]). Launched in the session's own scope,
     * never the caller's: the login flips [ready], which cancels sync, which would cancel a
     * login running inside a drain halfway through.
     */
    @Synchronized
    fun reauthenticate() {
        if (auth.status.value !is AuthStatus.SignedIn || relogin?.isActive == true) return
        relogin = scope.launch { loginWithRetry() }
    }

    init {
        scope.launch { client.authState.collect { Log.i(TAG, "convex auth state: ${it::class.simpleName}") } }
        scope.launch {
            keepLoggedIn(
                combine(auth.status, client.authState) { status, state ->
                    when {
                        status !is AuthStatus.SignedIn -> false
                        state is AuthState.Unauthenticated -> true
                        state is AuthState.AuthLoading -> null
                        else -> false
                    }
                },
                ::reauthenticate,
            )
        }
        scope.launch {
            auth.status.collect { status ->
                when (status) {
                    is AuthStatus.SignedIn -> reauthenticate()
                    AuthStatus.SignedOut -> client.logout(context)
                    AuthStatus.Loading -> Unit
                }
            }
        }
    }
}

/**
 * Asks for a login again, with growing pauses, for as long as the user is signed in but the
 * client is logged out of Convex ([stranded] true; null while a login is under way, which
 * neither stops nor restarts the count).
 *
 * The client gives up for good when one login fails: it reads Unauthenticated, [ready] goes
 * false, sync stops, so no call is refused and nothing would ever ask again. Right after a
 * reconnect Clerk itself fails for a few seconds ("Failed to load client"), so a single
 * re-login can land in exactly that window. Only signing out or a successful login ends this.
 */
internal suspend fun keepLoggedIn(
    stranded: Flow<Boolean?>,
    relogin: () -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) },
) {
    stranded.filterNotNull().distinctUntilChanged().collectLatest { isStranded ->
        var attempt = 1
        while (isStranded) {
            pause(backoffMillis(attempt++))
            relogin()
        }
    }
}

private const val TAG = "convexAuth"
private const val LOGIN_ATTEMPTS = 3
private const val LOGIN_RETRY_MS = 1_000L
