package com.taskrx.mobile.notices

import android.util.Log
import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.domain.normalizeSourceValue
import com.taskrx.core.notices.keptNoticeSentence
import com.taskrx.mobile.data.CreateNoticeInput
import com.taskrx.mobile.data.NoticesRepository
import com.taskrx.mobile.data.WatchSourcesRepository
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** An outside message as a road hands it over, before any decision has been made about it. */
data class IntakeMessage(
    val sourceKind: SourceKind,
    val sourceValue: String,
    val title: String,
    val body: String,
    val receivedAt: Long,
    /** What de-duplication keys on for this road (a notification key + post time, a JMAP id). Null when the road has none. */
    val externalId: String?,
)

/** What the funnel did with a message. Every outcome is a normal one; only [Stored] writes. */
sealed interface IntakeResult {
    /** The notice was written. */
    data class Stored(val notice: Notice) : IntakeResult

    /** The message's source is not a watched source, so nothing was kept. */
    data object NotWatched : IntakeResult

    /** A notice for this external id is already stored. */
    data object Duplicate : IntakeResult

    /** The message had nothing worth keeping: no subject and no text. */
    data object Empty : IntakeResult
}

/**
 * The one funnel every road feeds: the notification listener now, the mailbox's payload and
 * the share sheet through the same rules.
 *
 * The order is the plan's (D9): is the source watched? then is this the same message again?
 * then choose the sentence to keep, then write. Nothing is parsed for a date here - the
 * extractor runs when the Notices screen reads the row, so one implementation reads dates and
 * this class cannot disagree with it.
 *
 * A source is watched only when a [com.taskrx.core.domain.WatchedSource] names it. A share is
 * never one: a share arrives through its own screen, where the person's act is the approval.
 */
class NoticeIntake(
    private val notices: NoticesRepository,
    private val watchSources: WatchSourcesRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /**
     * What to do with a notice that was just written. The app passes the heads-up ping, so a
     * notice caught on the phone announces itself the way a mailbox notice does; the pull path
     * pings the same way (D10). It runs after the write, and a failure here never loses the
     * notice.
     */
    private val onStored: suspend (Notice) -> Unit = {},
    private val log: (String, Throwable?) -> Unit = { what, e -> Log.w("noticeIntake", what, e) },
) {
    /**
     * Admits [message] if its source is watched and it has not been seen before.
     *
     * A duplicate is found by `externalId`; a road that supplies one is responsible for making
     * it identify the same message across deliveries.
     */
    suspend fun admit(userId: String, message: IntakeMessage): IntakeResult {
        val source = normalizeSourceValue(message.sourceKind, message.sourceValue)
        if (source == null) {
            log("a message arrived with no usable source value", null)
            return IntakeResult.NotWatched
        }

        val watched = watchSources.list(userId).any { it.kind == message.sourceKind && it.value == source }
        if (!watched) return IntakeResult.NotWatched

        val externalId = message.externalId?.trim()?.ifEmpty { null }
        if (externalId != null && notices.findByExternalId(userId, externalId) != null) return IntakeResult.Duplicate

        val title = message.title.trim()
        val body = message.body.trim()
        if (title.isEmpty() && body.isEmpty()) return IntakeResult.Empty

        val kept = keptNoticeSentence(body, title, message.receivedAt, zone())
        val resolvedTitle =
            when {
                title.isNotEmpty() -> title
                kept != null -> kept.take(120)
                else -> return IntakeResult.Empty
            }
        val notice =
            notices.create(
                CreateNoticeInput(
                    userId = userId,
                    sourceKind = message.sourceKind,
                    sourceValue = source,
                    title = resolvedTitle,
                    sentence = kept?.takeIf { it.isNotBlank() && it != resolvedTitle },
                    receivedAt = message.receivedAt,
                    externalId = externalId,
                ),
            )
        try {
            onStored(notice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("a notice was stored but its ping failed", e)
        }
        return IntakeResult.Stored(notice)
    }
}
