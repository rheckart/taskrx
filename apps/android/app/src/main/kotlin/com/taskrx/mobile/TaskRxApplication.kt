package com.taskrx.mobile

import android.app.Application

class TaskRxApplication : Application() {
    val container: AppContainer by lazy { AppContainer.create(this) }
}
