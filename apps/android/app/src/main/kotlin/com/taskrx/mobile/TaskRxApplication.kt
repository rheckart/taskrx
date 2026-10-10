package com.taskrx.mobile

import android.app.Application

open class TaskRxApplication : Application() {
    val container: AppContainer by lazy { createContainer() }

    /** Overridable so unit tests get a local-only graph and never load the Convex native library. */
    protected open fun createContainer(): AppContainer = AppContainer.create(this)

    override fun onCreate() {
        super.onCreate()
        // Channels before anything can post; creating an existing one keeps the user's overrides.
        container.notifications.createChannels()
    }
}
