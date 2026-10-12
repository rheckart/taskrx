package com.taskrx.mobile.harness

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.notifications.TapHandler
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The hidden adb harness entry: `taskrx:///harness/<action>/<id>/<delayMs>/<nonce>`, driven
 * by `apps/android/scripts/notification-harness.sh` through `adb shell am start`.
 *
 * ## Security carve-out
 * The route works with nobody signed in (the harness must run on a fresh install), is not
 * linked from any UI, and its actions are local-only: they schedule, count and cancel
 * notifications on this device. **It exists in debug builds only** - the manifest filter
 * is in `src/debug`, and `src/release` has an empty stand-in for this object - so it adds
 * nothing to what a released app exposes. (The signed-in actions refuse without a subject;
 * see [HarnessRunner].)
 *
 * ## Replies
 * Logged on tag [TAG] as `[harness] action=<a> key=<k>` then `[harness] result: <reply>` or
 * `[harness] error: <message>`: the three lines the script reads out of logcat. A thrown
 * action is reported rather than swallowed, because silence from the app is what the script
 * reads as "unreachable", and an action that failed loudly deserves its diagnosis in the
 * evidence file.
 *
 * ## Nonce
 * A link whose path equals the previous one is ignored, exactly as the Expo route only
 * re-dispatched when its params changed (an activity re-created with the same intent must
 * not run a write twice). Every invocation carries a unique nonce.
 *
 * Parameters are PATH segments because the script has always sent them that way; the
 * `taskrx://harness/...` host form is accepted too.
 */
object DeviceHarness {
    const val TAG = "TaskRxHarness"
    private const val HARNESS = "harness"

    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The most recent dispatch, for a test to await. */
    @Volatile internal var lastJob: Job? = null

    private var lastKey: String? = null

    /** True when [intent] was a harness (or harness-opened) link, so the caller must not treat it as anything else. */
    fun handle(context: Context, container: AppContainer, intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "taskrx") return false

        val path = uri.pathSegments
        val segments =
            when {
                uri.host == HARNESS -> path
                path.firstOrNull() == HARNESS -> path.drop(1)
                else -> return openAnswerLink(container, uri, path)
            }

        val key = segments.joinToString("/")
        synchronized(this) {
            if (key == lastKey) return true
            lastKey = key
        }
        val spec = parseHarnessSpec(segments, System.currentTimeMillis())
        Log.i(TAG, "[harness] action=${spec.action.wire} key=$key")
        val runner = HarnessRunner(depsFor(context, container))
        lastJob =
            scope.launch {
                try {
                    Log.i(TAG, "[harness] result: ${runner.run(spec)}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "[harness] error: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        return true
    }

    /**
     * `taskrx:///dose/log?medicationId=<id>&scheduledTime=<ms>`: the answer screen, which the
     * signed-in cases open directly. Resolved by the same tap handler a notification press
     * goes through (it still understands the legacy `medicationId` key), so the link and the
     * press cannot disagree about where they land.
     */
    private fun openAnswerLink(container: AppContainer, uri: Uri, path: List<String>): Boolean {
        if (path.joinToString("/") != "dose/log") return false
        val press =
            Intent(TapHandler.ACTION_TAP)
                .putExtra(TapHandler.EXTRA_REMINDER_ID, uri.getQueryParameter(TapHandler.EXTRA_REMINDER_ID))
                .putExtra(TapHandler.EXTRA_LEGACY_MEDICATION_ID, uri.getQueryParameter(TapHandler.EXTRA_LEGACY_MEDICATION_ID))
                .putExtra(TapHandler.EXTRA_SCHEDULED_TIME, uri.getQueryParameter(TapHandler.EXTRA_SCHEDULED_TIME))
        container.notifications.taps.handle(press)
        return true
    }

    private fun depsFor(context: Context, container: AppContainer) =
        HarnessDeps(
            database = container.database,
            reminders = container.reminders,
            instances = container.instances,
            syncQueue = container.syncQueue,
            scheduler = container.notifications.scheduler,
            medication = container.notifications.medicationGateway,
            tasks = container.notifications.taskGateway,
            permissions = container.notifications.platform,
            userId = { container.session.userId },
            signedInUserId = { container.session.signedInUserId },
            clock = container.clock,
            zone = { ZoneId.systemDefault() },
            cancelPosted = { context.getSystemService(NotificationManager::class.java).cancelAll() },
            // No sync worker until phase 5, which replaces this with the worker's own status.
            syncStatus = { "not_started" },
        )

    /** Forgets the previous link, so a test can send the same one twice. */
    internal fun reset() {
        synchronized(this) { lastKey = null }
        lastJob = null
    }
}
