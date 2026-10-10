package com.taskrx.mobile

import android.app.Application
import com.taskrx.mobile.notifications.Channels

open class TaskRxApplication : Application() {
    val container: AppContainer by lazy { createContainer() }

    /** Overridable so unit tests get a local-only graph and never load the Convex native library. */
    protected open fun createContainer(): AppContainer = AppContainer.create(this)

    override fun onCreate() {
        super.onCreate()
        // Channels before anything can post; creating an existing one keeps the user's overrides.
        // Deliberately not through [container]: an alarm wakes a dead process into this method,
        // and building the whole graph (database, sign-in, Convex) here means a dose's
        // notification waits on, and can be lost to, any of them. The poster ensures the
        // channels itself too.
        Channels.ensure(this)
    }
}
