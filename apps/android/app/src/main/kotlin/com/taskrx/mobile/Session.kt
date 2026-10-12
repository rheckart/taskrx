package com.taskrx.mobile

import com.taskrx.mobile.auth.AuthGateway
import com.taskrx.mobile.auth.AuthStatus
import com.taskrx.mobile.auth.LocalOnlyAuth
import com.taskrx.mobile.notifications.CurrentUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Whose data this device is showing.
 *
 * Signed in, it is the Clerk user id - the `subject` the server scopes every row by, which is
 * why a row's `userId` must be exactly it for a push to be accepted. Signed out (or in a build
 * with no sign-in configured) the app runs local-only as one fixed user. Every screen asks
 * [userId] and nothing else, and every repository call takes the id it is handed; the id is
 * never read from anywhere else in :app.
 *
 * The id is mirrored into [CurrentUser] because that is what the boot receiver reads to re-arm
 * notifications with no UI running. It reads from there too, so a user the device harness put
 * there is the one whose day is shown while signed out.
 */
class Session(
    private val current: CurrentUser,
    private val auth: AuthGateway = LocalOnlyAuth,
) {
    val userId: String get() = signedInUserId ?: current.get() ?: LOCAL_USER_ID

    /**
     * The signed-in subject, or null when nobody is. The device harness reads this to report
     * `signed_in=false`, so its signed-in cases come out unevaluated rather than run against an
     * id nothing authenticated.
     */
    val signedInUserId: String? get() = (auth.status.value as? AuthStatus.SignedIn)?.userId

    /** Makes the user durable, so a reboot before the first write still re-arms for them. */
    fun start() {
        if (current.get() == null) current.set(LOCAL_USER_ID)
    }

    /**
     * Keeps [CurrentUser] in step with sign-in and calls [onChange] when whose day this is
     * changes, so notifications are re-armed for the new person. Signing OUT returns to the
     * local user; merely being signed out at launch leaves whoever was there (the harness's).
     */
    fun follow(scope: CoroutineScope, onChange: () -> Unit) {
        scope.launch {
            var wasSignedIn = false
            auth.status.collect { status ->
                when {
                    status is AuthStatus.SignedIn -> {
                        wasSignedIn = true
                        current.set(status.userId)
                        onChange()
                    }
                    status == AuthStatus.SignedOut && wasSignedIn -> {
                        wasSignedIn = false
                        current.set(LOCAL_USER_ID)
                        onChange()
                    }
                }
            }
        }
    }

    companion object {
        /**
         * The only user while signed out or in a build with no sign-in; the fixed id local-only
         * rows are written under. Do not reference it anywhere but here.
         */
        const val LOCAL_USER_ID: String = "local-user"
    }
}
