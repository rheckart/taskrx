package com.taskrx.mobile

import com.taskrx.mobile.data.db.TaskRxDatabase

/**
 * The application Robolectric runs. Always local-only: the real graph, given the Clerk and
 * Convex settings of the developer's local.properties, would load the Convex native library,
 * which a JVM unit test has no build of.
 */
class TestTaskRxApplication : TaskRxApplication() {
    override fun createContainer(): AppContainer = AppContainer(this, TaskRxDatabase.open(this))
}
