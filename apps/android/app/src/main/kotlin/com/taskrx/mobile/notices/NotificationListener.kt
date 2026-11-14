package com.taskrx.mobile.notices

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.taskrx.core.domain.SourceKind
import com.taskrx.mobile.TaskRxApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The notification-listener road: the messages an app posts, offered to the intake.
 *
 * This service technically receives every notification on the device. That is the reason for
 * the two-step design (D12): the package name is recorded in the seen list for every
 * notification, but **no title or text is read** from a package until the person has approved
 * it as a watched source. An unapproved app's message is a package name and nothing more, and
 * the intake refuses it anyway simply because it is not watched.
 *
 * Extracting the text is done here, only for a package the person watched, and handed to the
 * same [NoticeIntake] the other roads use. Android may withhold text (`EXTRA_TEXT` absent), so
 * a notice from this road can arrive with a title and no date - a supported state, not an
 * error.
 *
 * The service is started and stopped by the platform, so it holds a scope of its own and never
 * touches the app's UI.
 */
class NotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val app: TaskRxApplication? get() = application as? TaskRxApplication

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        val packageName = posted.packageName ?: return
        // Its own notifications are never a notice: the app would keep what it just posted.
        if (packageName == applicationContext.packageName) return

        val container = app?.container ?: return
        // Always recorded, approved or not: this is what makes the seen list a real list of
        // what could be watched, and it is the only thing kept from an unapproved package.
        container.seenApps.record(packageName)

        scope.launch {
            try {
                val user = container.session.userId
                // Read the watched sources first, so nothing is extracted from a package the
                // person has not approved.
                val watched = container.watchSources.list(user).any { it.kind == SourceKind.App && it.value == packageName }
                if (!watched) return@launch
                val message = readMessage(posted) ?: return@launch
                container.noticeIntake.admit(user, message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "admitting a listened notification failed", e)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Reads a notification's title and text, keeping only what a notice can hold: the
     * notification's own key plus its post time as the de-duplication id, its title, and its
     * text. Nothing else about the notification is looked at.
     */
    internal fun readMessage(sbn: StatusBarNotification): IntakeMessage? =
        readMessage(
            notification = sbn.notification ?: return null,
            packageName = sbn.packageName,
            key = sbn.key,
            postTime = sbn.postTime,
        )

    /** The frames of [readMessage], split out so the reading can be tested without a bound service. */
    internal fun readMessage(
        notification: Notification,
        packageName: String,
        key: String,
        postTime: Long,
    ): IntakeMessage? {
        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text =
            listOf(Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUMMARY_TEXT)
                .firstNotNullOfOrNull { extraKey -> extras.getCharSequence(extraKey)?.toString()?.takeIf { it.isNotBlank() } }
                .orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val postedAt = if (postTime > 0) postTime else System.currentTimeMillis()
        return IntakeMessage(
            sourceKind = SourceKind.App,
            sourceValue = packageName,
            title = title,
            body = text,
            receivedAt = postedAt,
            externalId = "notification:$packageName:$key:$postedAt",
        )
    }

    companion object {
        private const val TAG = "notificationListener"
    }
}
