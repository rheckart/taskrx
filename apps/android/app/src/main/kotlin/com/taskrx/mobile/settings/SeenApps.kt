package com.taskrx.mobile.settings

import android.content.Context

/**
 * The packages whose notifications this app has seen, whether or not they are approved.
 *
 * A listener receives every notification every app posts. This list is the "what could I
 * watch?" question answered without keeping anything but a package name (D12): the service
 * records the package here and holds no title or text from an app until the person has
 * approved it as a watched source.
 *
 * Device-only and never synced: which apps happen to post on this phone is not data about a
 * reminder, and syncing it would copy a list of the person's apps to the server for no
 * reason. Capped so it cannot grow without bound.
 */
class SeenApps(context: Context) {
    private val prefs = context.getSharedPreferences("taskrx.seen_apps", Context.MODE_PRIVATE)

    /** Records [packageName] as seen. No-op when it is already there. */
    fun record(packageName: String) {
        val name = packageName.trim()
        if (name.isEmpty()) return
        val current = seen()
        if (name in current) return
        // A SharedPreferences string set has no order, so the cap is a size limit, not a
        // recency one: it only keeps the list from growing without bound.
        val next = (current + name).take(MAX)
        prefs.edit().putStringSet(KEY, next.toSet()).commit()
    }

    /** The packages seen so far, in no particular order. */
    fun seen(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty()

    /** Forgets a package, so a person who removed it does not see it offered again at once. */
    fun forget(packageName: String) {
        val next = seen() - packageName
        prefs.edit().putStringSet(KEY, next).commit()
    }

    private companion object {
        const val KEY = "seen_apps"
        const val MAX = 200
    }
}
