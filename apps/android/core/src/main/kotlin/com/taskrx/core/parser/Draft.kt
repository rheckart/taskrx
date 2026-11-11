package com.taskrx.core.parser

import com.taskrx.core.domain.AsNeededTimes
import com.taskrx.core.domain.MonthlyTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.isWellFormedSchedule
import com.taskrx.core.domain.monthlyTimes
import com.taskrx.core.domain.noneTimes

/*
 * The reviewable draft that sits between a typed phrase and a saved Reminder.
 *
 * [parseReminder] returns a whole `Reminder` — identity, timestamps, tags,
 * caregivers and all — which is more than an entry screen has any business
 * holding before the person has agreed to it. The parser can be wrong, so what
 * it understood has to be shown and corrected first, and only the fields a
 * person can see and correct belong in that intermediate value.
 *
 * A [ReminderDraft] is exactly those fields. It is not a `Reminder`: it has no
 * id, no owner and no timestamps, because none of those are the parser's to
 * guess and all three are minted by whatever persists it.
 *
 * Three rules live here rather than in the screen that renders them, so the
 * screen decides nothing:
 *
 * - **A phrase always yields a draft** ([readPhrase]). The parser refuses two
 *   inputs — one with no tokens, and one where no token could serve as a name —
 *   and on both of those the person still has to be able to enter their
 *   reminder. There is no state in which the entry surface has nothing to show.
 * - **Switching Kind to task drops the medication-only fields** ([withKind]).
 *   A task has no dose and no supply, so carrying `20mg` across a correction
 *   from medication to task would save a task that claims a dosage.
 * - **What makes a draft savable** ([isSavableDraft]), which is the same
 *   question the two hand-entry forms already ask of a name and a schedule.
 */

/** The fields a person reviews and corrects before a Reminder is written. */
data class ReminderDraft(
    val kind: ReminderKind,
    val name: String,
    val schedule: ScheduleTimes,
    val priority: Priority,
    val dosage: String?,
    val instructions: String?,
    val pillsRemaining: Double?,
    val pillsPerDose: Double?,
    val refillReminderDays: Int?,
    /**
     * The RxNorm concept id, when the person has pinned the drug down to one.
     *
     * No phrase produces it — [readPhrase] always leaves it absent — and it is
     * not on the domain `Reminder`, which does not model RxNorm. It is here
     * because it is nonetheless a field of the thing being reviewed: the surface
     * shows it, the repository writes it to a column, and above all it is a
     * claim about *which drug this is*, so it has to fall to the same rule as
     * the dosage and the supply counts when a draft stops being a medication.
     * Held in the surface's own state instead, that rule would have been a
     * `kind == Task` check in a screen, which is the extraction being
     * incomplete.
     */
    val rxcui: String?,
    /**
     * The tokens the grammar had no place for, carried through so the surface can
     * say what it ignored. A phrase parses *around* these rather than failing on
     * them, which is precisely why they have to be shown: nothing else would tell
     * the person that a word they typed had no effect.
     */
    val unparsedTokens: List<String>,
)

data class ReadPhraseOptions(
    /** Unix ms. Anchors an `every N hours` schedule; see [ParseReminderOptions]. */
    val now: Long,
)

data class PhraseReading(
    /**
     * Whether [draft] is what the parser read, or the blank one it fell back to.
     * The surface says something different in each case; neither is an error
     * state.
     */
    val understood: Boolean,
    val draft: ReminderDraft,
    /** Why the phrase could not be read. Empty when [understood]. */
    val reasons: List<String>,
)

/**
 * The identity [parseReminder] requires and a draft has no use for.
 *
 * A draft is pre-identity by definition: the id and the owner are assigned by
 * whatever writes the row, and neither is readable from a phrase. Passing empty
 * strings and discarding them is the honest spelling of "not yet decided" —
 * inventing an id here would mean generating one for every keystroke of a
 * preview the person may never save.
 */
private const val UNASSIGNED = ""

/**
 * Reads [phrase] into a draft, along with whether the parser understood it.
 *
 * Pure, offline and deterministic, all three inherited from [parseReminder]:
 * the same phrase and the same `now` always give the same reading.
 */
fun readPhrase(phrase: String, options: ReadPhraseOptions): PhraseReading {
    val reading = readWithParser(phrase, options)
    return reading.copy(draft = withMonthlyDates(phrase, reading.draft))
}

/**
 * Reads a `monthly` draft's dates and times, which the token-by-token parser
 * cannot.
 *
 * `mon wed fri` is a schedule because every word in it is a weekday; `the 15th
 * and the 1st` is the same sentence with a sheet of numbers in the middle, and
 * there the parser's context-free token test gives up — three dates look exactly
 * like the name of a drug. So the dates, and the times after them, are read here,
 * from the one phrase whose own words have said `monthly`.
 *
 * Nothing happens to any other draft: a number is a date only in a phrase that
 * said so. Of the parser's own reading, only the schedule is replaced — the name
 * is the letters it kept, and the times before the first date are the parser's
 * and are kept beside the ones read after it. A phrase that said `monthly` but
 * named no date keeps the empty schedule the parser built, which
 * [isSavableDraft] refuses until a date is picked.
 */
private fun withMonthlyDates(phrase: String, draft: ReminderDraft): ReminderDraft {
    val schedule = draft.schedule as? MonthlyTimes ?: return draft

    val tokens = MONTHLY_TOKEN.findAll(phrase).map { it.value }.toList()

    // Read from the first date, or from the start when the phrase named none — a
    // number in front of one belongs to whatever the parser made of the words
    // before it (`every 15 minutes monthly`). A phrase with no date reads nothing
    // here and keeps the parser's empty `monthly`, which the form asks a date for.
    val firstDate = tokens.indexOfFirst { tryParseMonthDay(it) != null }
    val from = if (firstDate == -1) 0 else firstDate
    val afterDates = tokens.subList(from, tokens.size)
    val dates = afterDates.mapNotNull(::tryParseMonthDay).distinct().sorted()
    val times =
        (schedule.times + afterDates.mapNotNull(::parseClockIfAny))
            .distinct()
            .sorted()

    return draft.copy(schedule = monthlyTimes(dates, times), unparsedTokens = emptyList())
}

/** The same runs of letters, digits, dots and colons the parser's tokenizer keeps. */
private val MONTHLY_TOKEN = Regex("""[a-zA-Z0-9.:]+""")

/** An `"HH:MM"` for a clock time written after `at` or as-is, or null. */
private fun parseClockIfAny(token: String): String? = tryParseAtTime(token, emptyList(), 0)?.value

private fun readWithParser(phrase: String, options: ReadPhraseOptions): PhraseReading {
    val result = parseReminder(phrase, ParseReminderOptions(id = UNASSIGNED, userId = UNASSIGNED, now = options.now))
    val reminder = result.reminder

    if (reminder == null) {
        return PhraseReading(
            understood = false,
            // The phrase becomes the name. It is the one thing the person definitely
            // typed on purpose, and a failed read must not be a surface that quietly
            // discards their input — clearing a wrong name is one gesture, retyping a
            // lost one is not.
            //
            // No schedule, rather than a guessed one: the parser reached this arm
            // without producing a reminder at all, so nothing it saw about *when* is
            // trustworthy. `none` puts the reminder in the Backlog until the person
            // gives it a time, which is a true statement about what is known.
            draft =
                ReminderDraft(
                    kind = ReminderKind.Task,
                    name = jsTrim(phrase),
                    schedule = noneTimes(),
                    priority = Priority.Normal,
                    dosage = null,
                    instructions = null,
                    pillsRemaining = null,
                    pillsPerDose = null,
                    refillReminderDays = null,
                    rxcui = null,
                    unparsedTokens = result.unparsedTokens,
                ),
            reasons = result.errors,
        )
    }

    return PhraseReading(
        understood = true,
        draft =
            ReminderDraft(
                kind = reminder.kind,
                name = reminder.name,
                schedule = reminder.schedule,
                priority = reminder.priority,
                dosage = reminder.dosage,
                instructions = reminder.instructions,
                pillsRemaining = reminder.pillsRemaining,
                pillsPerDose = reminder.pillsPerDose,
                refillReminderDays = reminder.refillReminderDays,
                // Never from a phrase. Nobody says an RxNorm concept id out loud, and the
                // parser has no lexicon that could recognise one; it arrives, if at all,
                // from a lookup the person chose to make.
                rxcui = null,
                unparsedTokens = result.unparsedTokens,
            ),
        reasons = emptyList(),
    )
}

/**
 * Re-Kinds a draft, dropping what the new Kind cannot carry.
 *
 * Kind detection is the parser's most confident guess and its most visible
 * mistake — "buy paracetamol" is a shopping errand that reads as a drug — so
 * correcting it is the single most likely correction. The medication-only
 * fields go with the correction rather than being hidden by the form, because a
 * hidden field is still a saved one: `withKind(draft, Task)` on a draft holding
 * `20mg` must not write a task with a dosage.
 *
 * A schedule of "as needed" goes too, for the same reason: it is a medication's rule.
 *
 * `rxcui` goes with them. It is a claim about which drug this is, and a task is
 * not one, so a draft corrected from medication to task must not keep pointing
 * at an RxNorm concept.
 *
 * Nothing is dropped in the other direction. Every field a task carries a
 * medication carries too, and the medication-only ones are absent on a task
 * draft already.
 */
fun withKind(draft: ReminderDraft, kind: ReminderKind): ReminderDraft {
    if (kind == ReminderKind.Medication) return draft.copy(kind = kind)

    return draft.copy(
        kind = kind,
        // As needed is a way to take a medication. A task has no doses to space out, so it goes to the
        // Backlog (no time), where a time can be given to it.
        schedule = if (draft.schedule is AsNeededTimes) NoneTimes else draft.schedule,
        dosage = null,
        pillsRemaining = null,
        pillsPerDose = null,
        refillReminderDays = null,
        rxcui = null,
    )
}

/**
 * Renames a draft, dropping the RxNorm identity that was attached to the old
 * name.
 *
 * An `rxcui` is not an independent field: it is an assertion that *this name*
 * is that RxNorm concept. Someone picks "Metformin Hydrochloride 500 MG Oral
 * Tablet" from a lookup and then shortens it to "Metformin", and without this
 * the row is saved claiming a concept id for a name nobody verified — a
 * medication record whose two halves disagree, which is the one kind of wrong
 * this data must not be.
 *
 * It clears unconditionally rather than comparing against the picked name,
 * because the draft does not remember which name the pick was for and a draft
 * that had to would be storing the answer twice. Re-picking from the lookup is
 * one tap; a silently mismatched identity is not recoverable at all.
 */
fun withName(draft: ReminderDraft, name: String): ReminderDraft = draft.copy(name = name, rxcui = null)

/**
 * Whether a draft can be written as a Reminder.
 *
 * The same two conditions the hand-entry forms apply — a name, and a schedule
 * that carries what its own variant needs — asked in one place so the third
 * entry surface does not write a third copy of the answer. A `none` schedule
 * passes: having no time is what the Backlog is.
 */
fun isSavableDraft(draft: ReminderDraft): Boolean =
    jsTrim(draft.name) != "" && isWellFormedSchedule(draft.schedule)
