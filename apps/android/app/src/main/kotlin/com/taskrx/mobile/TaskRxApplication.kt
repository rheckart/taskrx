package com.taskrx.mobile

import android.app.Application
import androidx.work.Configuration
import com.taskrx.mobile.notifications.Channels

open class TaskRxApplication : Application(), Configuration.Provider {
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

    /**
     * WorkManager hands the system only [WORK_SCHEDULER_LIMIT] jobs at once and keeps the rest in
     * its own database, chosen by when they were enqueued rather than when they are due. At the
     * default of 20, the Horizon's tasks (up to `MAX_TASK_TRIGGERS`) filled every slot and the
     * background pull, or a task due soon, waited with the app closed until one of them ran.
     * Read because the default initializer is removed in the manifest.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMaxSchedulerLimit(WORK_SCHEDULER_LIMIT).build()

    companion object {
        /** WorkManager's ceiling; the system allows an app far more jobs than this. */
        const val WORK_SCHEDULER_LIMIT = 50
    }
}
