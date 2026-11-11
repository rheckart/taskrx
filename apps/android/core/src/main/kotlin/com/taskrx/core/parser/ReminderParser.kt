package com.taskrx.core.parser

import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.MonthlyTimes
import com.taskrx.core.domain.WeeklyTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.monthlyTimes
import com.taskrx.core.domain.weeklyTimes

/*
 * The natural-language reminder parser, ported from
 * `TaskRx.Core/ReminderParser.cs` (plus its `Patterns`, `Lexicon`,
 * `Extractors` and `State` partials) in the retired .NET app.
 *
 * It turns a phrase like `"atorvastatin 20mg every morning with food"` into a
 * [Reminder]. The grammar is small, deterministic and entirely offline — no
 * date library, no model call — because a medication reminder has to parse on a
 * phone with no signal, and has to parse the same way every time.
 *
 * **It never rejects an input.** A token it cannot place is collected into
 * `unparsedTokens` and the rest of the phrase still produces a reminder; the
 * only two failures are an input with no tokens at all and one where no token
 * could serve as a name. Nothing throws.
 *
 * Divergences from the C#, all deliberate:
 *
 * - **Purity.** `BuildReminder` reads `Guid.Empty`, `string.Empty`,
 *   `DateTimeOffset.UtcNow` (for `IntervalTimes`) and `default` timestamps.
 *   Those become [ParseReminderOptions], so the same input parses to the same
 *   reminder forever, which is what makes this testable and replayable.
 *   `now` is used both as the interval anchor and as `createdAt`/`updatedAt`,
 *   where the C# left the timestamps at their zero value for a caller to fix up.
 * - **The tokenizer keeps only the first alternative** of the C# regex
 *   `[a-zA-Z0-9\.\:]+|[0-9]+\.[0-9]+`. The second can never match: the first
 *   already accepts `.`, so it consumes `1.5` whole.
 * - **`IsNullOrWhiteSpace(input)` is folded into the zero-token check.** Both
 *   C# guards return the same error, and whitespace-only input tokenizes to
 *   nothing.
 * - **`BuildReminder`'s weekly re-normalization is dropped** (C# lines
 *   208-211). `ExtractFields` has already rebuilt the weekly schedule from the
 *   distinct, sorted days, so the second pass can only rebuild an identical
 *   value.
 *
 * C# behaviours kept as-is and flagged where they look wrong: [mergeTime]
 * dropping an `interval` schedule (see `Patterns.kt`), and `N pills per dose`
 * yielding neither a pill count nor a dose (see `Extractors.kt`).
 */

/**
 * The ambient values `ReminderParser.BuildReminder` reaches for in C#, supplied
 * by the caller instead.
 */
data class ParseReminderOptions(
    /** `Reminder.Id`, which the C# leaves as `Guid.Empty`. */
    val id: String,
    /** `Reminder.UserId`, which the C# leaves as `string.Empty`. */
    val userId: String,
    /**
     * Unix ms. Anchors an `every N hours` schedule (C# `DateTimeOffset.UtcNow`)
     * and stamps `createdAt`/`updatedAt`.
     */
    val now: Long,
)

/** `ReminderParseResult(Reminder?, IReadOnlyList<string>, IReadOnlyList<string>)`. */
data class ReminderParseResult(
    /** Absent only when [errors] is non-empty. */
    val reminder: Reminder?,
    /** Tokens the grammar had no place for. A phrase still parses around them. */
    val unparsedTokens: List<String>,
    val errors: List<String>,
)

/** `ReminderParserState`: the accumulator the extraction pass writes into. */
private class ParserState(
    val tokens: List<String>,
    val kind: ReminderKind,
    val now: Long,
) {
    var name: String = ""
    var dosage: String? = null
    var instructions: String? = null
    var schedule: ScheduleTimes? = null
    var priority: Priority = Priority.Normal
    val days: MutableList<Int> = mutableListOf()
    /** `the 15th` … the dates of a `monthly` month, in the order they were read. */
    val monthDays: MutableList<Int> = mutableListOf()
    val unparsedTokens: MutableList<String> = mutableListOf()
    var nameClosed: Boolean = false
    /** Set by [tryParseMonthly]: a month word is a schedule, and nothing else may claim it. */
    var monthlyNamed: Boolean = false
}

private const val EMPTY_INPUT_ERROR = "Input is empty."
private const val NO_NAME_ERROR = "Could not determine a name for the reminder."

/** The fallback when a phrase names no time at all. */
private const val DEFAULT_TIME = "09:00"

/** Runs of letters, digits, dots and colons; everything else separates. */
private val TOKEN_PATTERN = Regex("""[a-zA-Z0-9.:]+""")

private fun tokenize(input: String): List<String> = TOKEN_PATTERN.findAll(input).map { it.value }.toList()

/**
 * Medication or chore.
 *
 * A dosage or a drug-class suffix settles it, whatever the opening word.
 * Failing that, a leading task verb marks a chore (`call`, `do`, `schedule`),
 * and a phrase built from chore words — `take out the trash`, `Water the
 * plants downstairs` — is one too. Only then does length decide: a short
 * phrase is a chore, a longer one is assumed to name a drug.
 *
 * Length alone is the wrong question here. It called `take out the trash
 * every two weeks` a medication, so the review screen opened on the wrong
 * Kind, and every correction to the name that followed had to be made in a
 * form showing a Dosage field for a chore.
 */
private fun detectKind(first: String, tokens: List<String>): ReminderKind {
    if (tokens.any(::looksLikeDosage) || hasDrugSuffix(tokens)) return ReminderKind.Medication

    if (isTaskVerb(first)) return ReminderKind.Task

    if (tokens.any(::isTaskSignal)) return ReminderKind.Task

    val meaningful = tokens.count { !isNoiseWord(it) }
    return if (meaningful <= 3) ReminderKind.Task else ReminderKind.Medication
}

/**
 * Once a field has been recognized, the name is finished: later stray tokens
 * are collected as unparsed rather than appended to it.
 */
private fun closeNameIfSet(state: ParserState) {
    if (state.name.trim() != "") state.nameClosed = true
}

private fun appendName(state: ParserState, token: String) {
    state.name = if (state.name.trim() == "") token else "${state.name} $token"
}

/**
 * Whether [token] is the noun a chore is named for (`trash`, `garbage`,
 * `laundry`) and nothing has been named yet.
 *
 * After [consumeTaskNamePrefix] has read `take out`, `take out the trash` still
 * has to get `trash` into the name: a signal word is not a stray, and the
 * fallback to `unparsedTokens` would leave the name `take out` with its subject
 * missing. It is consumed before that fallback for the same reason.
 */
private fun isTaskNoun(state: ParserState, token: String): Boolean =
    state.kind == ReminderKind.Task && state.name.trim() == "" && isTaskSignal(token) && !isTaskVerb(token)

/**
 * Reads the start of a chore's name as one phrase — a verb, the noise words
 * after it, then a particle — and returns how many tokens it took.
 *
 * The pieces are read separately elsewhere, which leaves a hole: the verb
 * belongs in a chore's name, the noise words never do, and the particle is only
 * kept once a name exists. `take out the trash` therefore named itself `trash`.
 * Reading the verb, the noise words after it and the particle as one phrase
 * keeps `take out` and leaves `trash` as the noun that follows.
 *
 * Returns 0 when [token] is not a chore's opening verb, or a name already
 * exists (`take atorvastatin` is a dose, and its verb is dropped).
 */
private fun consumeTaskNamePrefix(state: ParserState, token: String, tokens: List<String>, index: Int): Int {
    if (state.kind != ReminderKind.Task || state.name.trim() != "" || !isNamePrefixVerb(token)) return 0

    appendName(state, token)

    var nextIndex = index + 1
    while (true) {
        val candidate = tokenAt(tokens, nextIndex) ?: return nextIndex - index
        when {
            isNamePrefixParticle(candidate) -> {
                appendName(state, candidate)
                return nextIndex - index + 1
            }
            isNoiseWord(candidate) -> nextIndex += 1
            else -> return nextIndex - index
        }
    }
}

/**
 * Reads a date or a time into the `monthly` schedule, and returns how many tokens
 * it took — or null when [token] is neither.
 *
 * Dates are kept apart from clock times by a rule the parser already knows: an
 * ordinal (`15th`) is a date and nothing else, a bare number is a date only while
 * no bare number has been read yet as a date, and a clock time needs an hour, a
 * minute or a designator — `9am`, `9:00`, or a bare hour behind `at`. A bare `9`
 * in `monthly on the 9th at 9` is the date; with a date already in hand it is the
 * time, so `monthly the 1st at 9` reads both.
 *
 * A date does not close the name; a time does, as it does everywhere else.
 */
private fun mergeMonthly(
    state: ParserState,
    schedule: MonthlyTimes,
    token: String,
    tokens: List<String>,
    index: Int,
): Int? {
    if (mergeMonthlyDate(state, schedule, token)) return 1

    // `9` on its own is no clock time (it is a date above), so a bare number is a time
    // only behind an `at`: `monthly on the 9th at 9`.
    val hour = if (equalsIgnoreCase(token, "at")) tryParseBareHour(tokenAt(tokens, index + 1)) else null
    val time =
        tryParseAtTime(token, tokens, index)
            ?: tryParseNamedTime(token)?.let { Match(it, 1) }
            ?: if (hour == null) null else Match(hour, 2)
    if (time == null) return null

    state.schedule = monthlyTimes(schedule.daysOfMonth, (schedule.times + time.value).distinct().sorted())
    closeNameIfSet(state)
    return time.consumed
}

/**
 * Reads [token] as a date of the month into [state], or returns false.
 *
 * A bare number is a date only while none has been read, so `at 9` is the time after
 * `the 15th`; an ordinal is a date wherever it appears.
 */
private fun mergeMonthlyDate(state: ParserState, schedule: MonthlyTimes, token: String): Boolean {
    val day = tryParseOrdinalDay(token) ?: if (state.monthDays.isEmpty()) tryParseMonthDay(token) else null
    if (day == null) return false

    state.monthDays.add(day)
    state.schedule = monthlyTimes(state.monthDays.distinct().sorted(), schedule.times)
    return true
}

/**
 * Reads whatever starts at [index] and returns how many tokens it took, which
 * is at least one. The order of the attempts is the grammar's precedence and
 * matches `ReminderParser.ExtractFields` line for line.
 */
private fun consumeToken(state: ParserState, token: String, index: Int): Int {
    val tokens = state.tokens

    val dosage = tryParseDosage(token)
    if (dosage != null) {
        state.dosage = dosage
        closeNameIfSet(state)
        return 1
    }

    val priority = tryParsePriority(token, tokens, index)
    if (priority != null) {
        state.priority = priority.value
        closeNameIfSet(state)
        return priority.consumed
    }

    // `every month` and `monthly`. The month wins over the words around it: `the` in `the 1st of
    // every month` is a noise word, and `9am` in `monthly at 9am` is a time that belongs to the
    // dates this file cannot see. So it is read first, and it takes the schedule whatever else
    // has claimed it — `Jan 15 monthly` has a `15` for a clock time before the month word arrives.
    val monthly = tryParseMonthly(token, tokens, index)
    if (monthly != null) {
        state.schedule = monthly.value
        state.monthlyNamed = true
        closeNameIfSet(state)
        return monthly.consumed
    }

    // While the phrase has said `monthly`, a date and a time are the month's: `the 1st and the
    // 15th at 9am` would otherwise close the name at the first date and leave the second one
    // unparsed.
    val monthlySchedule = state.schedule
    if (state.monthlyNamed && monthlySchedule is MonthlyTimes) {
        mergeMonthly(state, monthlySchedule, token, tokens, index)?.let { return it }
    }

    val every = tryParseEvery(token, tokens, index, state.now)
    if (every != null) {
        state.schedule = every.value
        closeNameIfSet(state)
        return every.consumed
    }

    // `daily` yields to a schedule already in hand, so `twice daily` stays twice.
    val daily = tryParseDaily(token)
    if (daily != null) {
        state.schedule = state.schedule ?: daily
        closeNameIfSet(state)
        return 1
    }

    val twiceDaily = tryParseTwiceDaily(token)
    if (twiceDaily != null) {
        state.schedule = twiceDaily
        closeNameIfSet(state)
        return 1
    }

    val namedTime = tryParseNamedTime(token)
    if (namedTime != null) {
        state.schedule = mergeTime(state.schedule, namedTime)
        closeNameIfSet(state)
        return 1
    }

    val atTime = tryParseAtTime(token, tokens, index)
    if (atTime != null) {
        state.schedule = mergeTime(state.schedule, atTime.value)
        closeNameIfSet(state)
        return atTime.consumed
    }

    val day = tryParseDayName(token)
    if (day != null) {
        state.days.add(day)
        state.schedule = state.schedule ?: weeklyTimes(state.days, emptyList())
        closeNameIfSet(state)
        return 1
    }

    if (isWeekly(token)) {
        state.schedule = state.schedule ?: weeklyTimes(state.days, emptyList())
        closeNameIfSet(state)
        return 1
    }

    val instructions = tryParseInstructions(token, tokens, index)
    if (instructions != null) {
        state.instructions = instructions.value
        closeNameIfSet(state)
        return instructions.consumed
    }

    // The inventory numbers themselves are read later, from the whole token list.
    val refill = tryParseRefill(token, tokens, index)
    if (refill != null) {
        closeNameIfSet(state)
        return refill
    }

    // A kicker is `every` that read nothing: the rest of the interval was not
    // understood, so the word introduced nothing and must not become a stray
    // token the surface then blames on the person's phrase.
    if (equalsIgnoreCase(token, "every")) {
        closeNameIfSet(state)
        return 1
    }

    val prefix = consumeTaskNamePrefix(state, token, tokens, index)
    if (prefix > 0) return prefix

    if (isNoiseWord(token)) return 1

    if (isTaskNoun(state, token)) {
        appendName(state, token)
        return 1
    }

    if (state.nameClosed) {
        state.unparsedTokens.add(token)
        return 1
    }

    appendName(state, token)
    return 1
}

/**
 * The C# walks the tokens with `while (i < tokens.Count)` and advances by a
 * variable `consumed`; a skip cursor over the indexed tokens is the same
 * traversal.
 */
private fun extractFields(state: ParserState) {
    var nextIndex = 0
    for ((index, token) in state.tokens.withIndex()) {
        if (index < nextIndex) continue
        nextIndex = index + consumeToken(state, token, index)
    }

    // Days are collected as they are seen, so the weekly schedule built at the
    // first one has to be rebuilt from the full set.
    val schedule = state.schedule
    if (schedule is WeeklyTimes) {
        state.schedule = weeklyTimes(state.days.distinct().sorted(), schedule.times)
    }
    // The month word wins over a weekday or a `twice`, so its schedule is the one that
    // survives, rebuilt from the dates read along the way.
    if (state.monthlyNamed) {
        val times = (state.schedule as? MonthlyTimes)?.times.orEmpty()
        state.schedule = monthlyTimes(state.monthDays.distinct().sorted(), times)
    }
}

private fun buildReminder(state: ParserState, options: ParseReminderOptions): Reminder =
    createReminder(
        id = options.id,
        userId = options.userId,
        kind = state.kind,
        name = state.name.trim(),
        schedule = state.schedule ?: dailyTimes(listOf(DEFAULT_TIME)),
        priority = state.priority,
        createdAt = options.now,
        updatedAt = options.now,
        dosage = state.dosage,
        instructions = state.instructions,
        pillsRemaining = tryExtractPillsRemaining(state.tokens)?.toDouble(),
        pillsPerDose = tryExtractPillsPerDose(state.tokens),
        refillReminderDays = tryExtractRefillDays(state.tokens),
    )

/**
 * Parses [input] into a [Reminder], plus the tokens it could not place.
 *
 * Pure: every value that is not derived from [input] comes from [options].
 */
fun parseReminder(input: String, options: ParseReminderOptions): ReminderParseResult {
    val tokens = tokenize(input)
    val first = tokens.firstOrNull() ?: return ReminderParseResult(null, emptyList(), listOf(EMPTY_INPUT_ERROR))

    val state = ParserState(tokens = tokens, kind = detectKind(first, tokens), now = options.now)

    extractFields(state)

    val unparsedTokens = state.unparsedTokens.toList()
    if (state.name.trim() == "") {
        return ReminderParseResult(null, unparsedTokens, listOf(NO_NAME_ERROR))
    }

    return ReminderParseResult(buildReminder(state, options), unparsedTokens, emptyList())
}
