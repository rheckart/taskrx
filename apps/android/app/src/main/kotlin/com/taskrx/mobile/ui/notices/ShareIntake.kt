package com.taskrx.mobile.ui.notices

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where an incoming text intent should land once the app shell is ready. */
enum class ShareDestination {
    /** From Android's share sheet: save to Notices or turn into a new task. */
    Share,
    /** From the floating text-selection toolbar (ACTION_PROCESS_TEXT): straight to the New Task form. */
    NewTask,
}

data class PendingShare(
    val text: String,
    val destination: ShareDestination = ShareDestination.Share,
)

/** Name and optional notes derived from shared or highlighted text for the New Task form. */
data class SharedTaskDraft(
    val name: String,
    val notes: String,
)

/** The first non-blank line, trimmed and capped to a title-sized line. */
fun shareTitle(text: String): String =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(120)

/**
 * Turns a share's subject and full text into the New Task form's Name and Notes.
 * When the shared text is already the entire name (a single short line left unedited),
 * Notes stays empty rather than repeating the same words twice.
 */
fun taskDraftFromShare(title: String, text: String): SharedTaskDraft {
    val cleanText = text.trim()
    val name = title.trim().ifEmpty { shareTitle(cleanText) }
    val notes = if (cleanText == name) "" else cleanText
    return SharedTaskDraft(name = name, notes = notes)
}

/** Turns raw highlighted text directly into a New Task draft. */
fun taskDraftFromText(text: String): SharedTaskDraft = taskDraftFromShare(title = "", text = text)

/**
 * The text handed to TaskRx by the share sheet or text-selection toolbar, held until the
 * app shell can navigate to it.
 *
 * An incoming share arrives as an Intent, which is not Compose state; this is the one place
 * the two meet. [offer] is called from the activity, the shell collects [pending] and opens
 * the right screen, and [consume] clears it so a recreated activity does not replay it.
 */
object ShareIntake {
    private val mutable = MutableStateFlow<PendingShare?>(null)
    val pending: StateFlow<PendingShare?> = mutable.asStateFlow()

    /** Reads a plain-text share or text-selection action out of [intent], if it is one. */
    fun offer(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") != true) return
                val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                if (shared.isNotEmpty()) mutable.value = PendingShare(shared, ShareDestination.Share)
            }
            Intent.ACTION_PROCESS_TEXT -> {
                if (intent.type != null && intent.type?.startsWith("text/") != true) return
                val shared = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
                if (shared.isNotEmpty()) mutable.value = PendingShare(shared, ShareDestination.NewTask)
            }
        }
    }

    fun consume() {
        mutable.value = null
    }
}
