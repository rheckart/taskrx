package com.taskrx.mobile

import android.app.Application

class TaskRxApplication : Application() {
    val container: AppContainer by lazy { AppContainer.create(this) }

    override fun onCreate() {
        super.onCreate()
        // Channels before anything can post; creating an existing one keeps the user's overrides.
        container.notifications.createChannels()
    }
}
