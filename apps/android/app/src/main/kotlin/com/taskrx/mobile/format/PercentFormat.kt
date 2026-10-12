package com.taskrx.mobile.format

import kotlin.math.floor

/**
 * How this app writes an adherence percentage. Mirrors archive/expo-rn:apps/mobile/src/format/percent.ts.
 *
 * A whole number, because "77.7777%" claims a precision a week of doses does not have.
 * But plain rounding is not safe at the top: 99.6% rounds to "100%", and a person who
 * missed a dose is told they missed none. So "100%" is reserved for a genuinely perfect
 * period. Only the top end is reserved: understating adherence gives a figure someone
 * can act on, overstating it is the flattering number this exists to remove, so a
 * near-zero figure stays "0%" and the count beside it ("1 of 250") qualifies it.
 *
 * The guard is about this function's domain, not its callers: it holds across every
 * number accepted, not across the subset one caller currently produces.
 */
fun formatPercent(percent: Double): String {
    // JavaScript's Math.round: halves go up. Kotlin's roundToInt() goes to even.
    val rounded = floor(percent + 0.5).toLong()
    if (rounded == 100L && percent < 100) return "99%"
    return "$rounded%"
}
