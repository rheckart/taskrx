package com.taskrx.core.parser

/*
 * The inventory extractors, ported from
 * `TaskRx.Core/ReminderParserExtractors.cs` on the `archive/dotnet-maui`
 * branch.
 *
 * These re-scan the whole token list rather than reading state accumulated by
 * the main loop, exactly as the C# does: `ReminderParser.BuildReminder` calls
 * all three with `state.Tokens`. Lookahead is bounded by [tokenAt] returning
 * `null` past the end.
 */

/**
 * `30 pills` / `30 tablets`.
 *
 * `N <unit> per dose` is skipped, so that the `1` in `30 pills 1 per dose` is
 * read as the dose and only the `30` as the count.
 */
fun tryExtractPillsRemaining(tokens: List<String>): Int? {
    for ((index, token) in tokens.withIndex()) {
        val count = parseIntToken(token)
        val next = tokenAt(tokens, index + 1)
        if (count == null || next == null) continue
        if (!isDoseUnit(next)) continue

        if (equalsIgnoreCase(tokenAt(tokens, index + 2), "per") &&
            equalsIgnoreCase(tokenAt(tokens, index + 3), "dose")
        ) {
            continue
        }

        return count
    }

    return null
}

/**
 * `1 per dose`, `1.5 per dose`.
 *
 * Read as a `double` where the phrase parser ([tryParseRefill]) only accepts an
 * `int`, which is the C# behaviour: `1.5 per dose` sets the dose but leaves
 * `1.5` in the unparsed tokens.
 */
fun tryExtractPillsPerDose(tokens: List<String>): Double? {
    for ((index, token) in tokens.withIndex()) {
        val amount = parseFloatToken(token)
        if (amount != null &&
            equalsIgnoreCase(tokenAt(tokens, index + 1), "per") &&
            equalsIgnoreCase(tokenAt(tokens, index + 2), "dose")
        ) {
            return amount
        }
    }

    return null
}

/** `refill in 7` / `refill in 7 days`. */
fun tryExtractRefillDays(tokens: List<String>): Int? {
    for ((index, token) in tokens.withIndex()) {
        if (!equalsIgnoreCase(token, "refill")) continue
        if (!equalsIgnoreCase(tokenAt(tokens, index + 1), "in")) continue

        val days = parseIntToken(tokenAt(tokens, index + 2))
        if (days != null) return days
    }

    return null
}
