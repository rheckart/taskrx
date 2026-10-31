package com.taskrx.mobile.catchup

import com.taskrx.core.catchup.CATCH_UP_WINDOW_MS
import com.taskrx.core.catchup.needsCatchUp
import com.taskrx.core.timeline.ProjectTimelineInput
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.core.timeline.TimelineWindow
import com.taskrx.core.timeline.projectTimeline
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import java.time.ZoneId

/**
 * Reads the Missed rows of the last week: what the Catch up screen lists and the Today banner
 * counts. Which rows those are is :core's `needsCatchUp`; this is only the two table reads and
 * the projection, the same ones Today makes for a day.
 */
class CatchUpReader(
    private val reminders: RemindersRepository,
    private val instances: ReminderInstancesRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    suspend fun read(userId: String, now: Long): List<TimelineRow> {
        val since = now - CATCH_UP_WINDOW_MS
        // includeInactive: a stopped reminder's missed doses still need an answer.
        val all = reminders.list(userId, includeInactive = true)
        val stored = instances.list(InstanceFilter(userId, since = since, until = now))
        return needsCatchUp(projectTimeline(ProjectTimelineInput(all, stored, TimelineWindow(since, now), now, zone())))
    }
}
