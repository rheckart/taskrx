package com.taskrx.mobile.notifications

import android.content.Context
import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.notices.ReadDate
import com.taskrx.core.notices.noticeText
import com.taskrx.core.notices.proposalsFor
import com.taskrx.core.notices.readNoticeDate
import com.taskrx.mobile.data.Clock
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import java.time.ZoneId

/**
 * The one heads-up a newly pulled Notice gets when it has a proposal the person can apply.
 *
 * It is posted from the pull path, not from a screen, because a notice fetched on the server
 * can arrive while the app is in the background. The pinged ids are kept in preferences so a
 * re-delivery of the same notice (a resolution change, a reconnect) never pings twice.
 */
class NoticePings(
    context: Context,
    private val reminders: RemindersRepository,
    private val instances: ReminderInstancesRepository,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val prefs: NotificationPrefs = NotificationPrefs(context),
    private val post: (noticeId: String, title: String, body: String) -> Unit = { id, title, body ->
        NotificationPoster.postNotice(context, id, title, body)
    },
) {
    private val key = "pinged_notices"

    /** Posts at most one ping per notice, and only when a rule produced a move that can be applied. */
    suspend fun ping(notices: List<Notice>) {
        if (notices.isEmpty()) return
        val now = clock()
        val pinged = prefs.prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        var changed = false

        for (notice in notices) {
            if (notice.resolution != NoticeResolution.None || notice.id in pinged) continue
            val reading = readNoticeDate(noticeText(notice), now, zone()) as? ReadDate.Found ?: continue
            val tasks = reminders.list(notice.userId, kind = ReminderKind.Task)
            val window = 400L * 24 * 60 * 60_000L
            val seen = instances.list(InstanceFilter(notice.userId, since = now - window, until = now + window))
            if (proposalsFor(notice, tasks, seen, now, zone()).none { it.applies }) continue

            post(
                notice.id,
                "A notice may move a task",
                "${notice.title}: ${notice.sentence ?: reading.matchedText}",
            )
            pinged += notice.id
            changed = true
        }

        if (changed) {
            // A few hundred ids is years of notices; the cap only keeps the set from growing forever.
            prefs.prefs.edit().putStringSet(key, pinged.toList().takeLast(200).toSet()).commit()
        }
    }
}
