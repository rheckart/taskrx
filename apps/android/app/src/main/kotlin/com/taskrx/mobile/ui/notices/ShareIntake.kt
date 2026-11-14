package com.taskrx.mobile.ui.notices

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The text handed to TaskRx by the share sheet, held until the app shell can navigate to it.
 *
 * A share arrives as an Intent, which is not Compose state; this is the one place the two
 * meet. [offer] is called from the activity, the shell collects [text] and opens the intake
 * screen, and [consume] clears it so a recreated activity does not replay the same share.
 */
object ShareIntake {
    private val mutable = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = mutable.asStateFlow()

    /** Reads a plain-text share out of [intent], if it is one. */
    fun offer(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("text/") != true) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (shared.isNotEmpty()) mutable.value = shared
    }

    fun consume() {
        mutable.value = null
    }
}
