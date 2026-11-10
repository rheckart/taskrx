package com.taskrx.mobile.asneeded

import com.taskrx.core.domain.AsNeededTimes
import com.taskrx.core.domain.Reminder

/**
 * A medication taken as needed, with the moments its doses were taken. The wait is not stored:
 * it depends on the clock (the 24-hour window moves, the countdown runs), so a screen works it
 * out from these with `asNeededStatus` each time it looks. Shared by Today and Log a dose.
 */
data class AsNeededEntry(val reminder: Reminder, val schedule: AsNeededTimes, val takings: List<Long>)
