package com.taskrx.mobile

import android.content.Intent
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.taskrx.mobile.notifications.PromptHost

class MainActivity : ComponentActivity() {
    private val container get() = (application as TaskRxApplication).container

    private val notificationPrompt =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            container.notifications.permissions.onPromptResult()
            container.notifications.onForeground()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container.notifications.taps.handle(intent)
        setContent {
            MaterialTheme {
                Text("TaskRx")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        container.notifications.taps.handle(intent)
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
    }

    override fun onStop() {
        container.notifications.platform.host = null
        super.onStop()
    }
}
