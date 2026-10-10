package com.taskrx.mobile

import com.taskrx.mobile.notifications.CurrentUser

/**
 * Whose data this device is showing. This is the SEAM the sign-in step replaces.
 *
 * Until phase 5 there is no sign-in, so the app runs local-only as one fixed user. Every
 * screen asks [userId] and nothing else, and every repository call takes the id it is
 * handed; the id is never read from anywhere else in :app. When Clerk lands, [userId]
 * becomes the signed-in user's id and [start] becomes "restore the session or route to
 * sign-in", and no screen changes.
 *
 * The id is mirrored into [CurrentUser] because that is what the boot receiver reads to
 * re-arm notifications with no UI running. It reads from there too, so a user the device
 * harness (or a later sign-in) put there is the one whose day is shown.
 */
class Session(private val current: CurrentUser) {
    val userId: String get() = current.get() ?: LOCAL_USER_ID

    /** Makes the user durable, so a reboot before the first write still re-arms for them. */
    fun start() {
        if (current.get() == null) current.set(LOCAL_USER_ID)
    }

    companion object {
        /**
         * The only user in a local-only build. Replaced by the sign-in step; do not
         * reference it anywhere but here.
         */
        const val LOCAL_USER_ID: String = "local-user"
    }
}
