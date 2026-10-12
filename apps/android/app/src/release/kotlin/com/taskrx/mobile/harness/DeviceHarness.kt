package com.taskrx.mobile.harness

import android.content.Context
import android.content.Intent
import com.taskrx.mobile.AppContainer

/**
 * The release build's device harness: there is none. The debug source set has the real one
 * (src/debug), together with the manifest filter that routes `taskrx://` links to it, so
 * a release APK cannot be driven from outside by an intent.
 */
object DeviceHarness {
    @Suppress("UNUSED_PARAMETER")
    fun handle(context: Context, container: AppContainer, intent: Intent?): Boolean = false
}
