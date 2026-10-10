package com.taskrx.mobile

import android.content.Intent
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.taskrx.mobile.harness.DeviceHarness
import com.taskrx.mobile.notifications.PromptHost
import com.taskrx.mobile.ui.TaskRxApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import android.util.Log

class MainActivity : ComponentActivity() {
    private val container get() = (application as TaskRxApplication).container

    private val notificationPrompt =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            container.notifications.permissions.onPromptResult()
            container.notifications.onForeground()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Makes the local user durable so the boot receiver re-arms for them before anyone
        // has signed in; signing in replaces it with the Clerk user (see Session).
        container.session.start()
        // A launch link only runs on a fresh start: a recreated activity (process death, rotation)
        // is handed the ORIGINAL intent again, and replaying a harness write would run it twice.
        if (savedInstanceState != null || !DeviceHarness.handle(this, container, intent)) container.notifications.taps.handle(intent)
        setContent { TaskRxApp(container) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!DeviceHarness.handle(this, container, intent)) container.notifications.taps.handle(intent)
    }

    override fun onStart() {
        super.onStart()
        container.notifications.platform.host = PromptHost { notificationPrompt.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    override fun onResume() {
        super.onResume()
        // Every foreground: re-read the permissions (the prompt itself is once per run)
        // and bring the armed notifications in line with the store.
        container.notifications.onForeground()
        sweepMissed()
        container.sync?.onForeground()
    }

    /**
     * On every foreground, so a moment that ran out of time reads as Missed whichever
     * screen the person lands on (the Today screen also sweeps, after it has drawn the
     * day). The runner serializes, so the second of the two finds nothing to do.
     */
    private fun sweepMissed() {
        lifecycleScope.launch {
            try {
                container.sweep.run(container.session.userId, container.clock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("sweep", "missed sweep failed", e)
            }
        }
    }

    override fun onStop() {
        container.notifications.platform.host = null
        super.onStop()
    }
}
