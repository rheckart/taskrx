package com.taskrx.mobile.adherence

import com.taskrx.core.adherence.ADHERENCE_PERIOD_DAYS
import com.taskrx.core.adherence.AdherencePeriod
import com.taskrx.core.adherence.AdherenceStats
import com.taskrx.core.adherence.adherencePeriod
import com.taskrx.core.adherence.computeAdherence
import com.taskrx.core.domain.AsNeededTimes
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import com.taskrx.mobile.sweep.MissedSweepRunner
import java.time.ZoneId

data class AdherenceReading(val stats: AdherenceStats, val period: AdherencePeriod)

/**
 * The Adherence read: sweep, then read, then compute (archive/expo-rn:apps/mobile/src/adherence/read.ts).
 *
 * Every rule about the figure is [computeAdherence] and every rule about the window is
 * [adherencePeriod], both in :core. What this owns is an ORDER: a dose nobody answered
 * has no stored Instance until the sweep mints one, and the calculator counts what it
 * is given, so a figure computed first is wrong in the flattering direction - one dose
 * in thirty would read as 100%. Putting the sweep inside the read makes that structural:
 * there is no way to obtain the figure that skips it.
 *
 * A failed sweep fails the whole read. Computing on top of an unfinished sweep produces
 * exactly the confident, flattering number this path exists to prevent, and silently;
 * "couldn't load" with a retry serves a person better.
 *
 * Medications taken as needed are left out. Adherence is the share of commitments met, and an
 * as-needed medication has none: a dose taken counts for nothing, a dose not taken is not a
 * miss. Counting its takings would add one to both sides of the figure and pull it toward
 * 100% (ADR 0010).
 *
 * No `reminderKind` filter on the read, though it would fetch fewer rows: Adherence
 * being medication-only is a rule, stated once, in the calculator.
 */
class AdherenceReader(
    private val sweep: MissedSweepRunner,
    private val instances: ReminderInstancesRepository,
    private val reminders: RemindersRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    suspend fun read(userId: String, now: Long): AdherenceReading {
        sweep.run(userId, now)

        val period = adherencePeriod(now, ADHERENCE_PERIOD_DAYS, zone())
        val asNeeded = reminders.list(userId, includeInactive = true).filter { it.schedule is AsNeededTimes }.mapTo(HashSet()) { it.id }
        val rows =
            instances.list(InstanceFilter(userId = userId, since = period.fromMs, until = period.toMs))
                .filterNot { it.reminderId in asNeeded }

        return AdherenceReading(
            stats = computeAdherence(rows, period.fromMs, period.toMs, zone()),
            period = period,
        )
    }
}
