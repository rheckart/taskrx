# An Occurrence stays answerable for two hours, and a sweep reaches back seven days

The sweep planner needs two numbers that nothing else in the codebase implies,
and `docs/specs/fused-timeline.md` names both as open questions that must be
settled before the sweep is built rather than during it. They are settled here.

**Grace Period: 2 hours.** An Occurrence is Missed once its moment is more than
two hours old with no answer.

**Lookback bound: 7 days.** One sweep considers Occurrences from seven days
before the current instant up to the Grace Period boundary, and nothing older.

## Considered Options

### The Grace Period

The number has to be long enough that a dose taken late is recorded as taken,
and short enough that Adherence tells the truth today rather than tomorrow.

Two hours is the width of "I took it with lunch instead of at breakfast". Below
about an hour the sweep starts converting ordinary lateness into a permanent
`missed` row, which is the failure the spec calls out directly — "a dose is not
missed at 09:01" — and a person who is punished for a late log stops logging.

Longer windows were rejected on the other side of the same trade. At six hours a
morning dose is still unanswered-but-not-missed when the evening dose comes due,
so the timeline carries two live rows for the same medication and Adherence
reports a day as complete for most of the day after it. Two hours also keeps at
most one Occurrence of a reminder answerable at a time for every interval
schedule of four hours or more, which is the realistic floor for a medication
scheduled by interval.

The Grace Period is *not* the last chance to record a dose. Recording after the
fact (story 26) is a separate path, and one that has to survive this decision:
what elapsed Grace costs is the default verdict, not the ability to correct it.

### The lookback bound

Seven days is chosen against the sweep's two failure modes, which pull in
opposite directions.

Too short loses real misses. A phone left flat over a long weekend, or an app
simply not opened for three days, would come back with those Occurrences neither
answered nor Missed — they would fall outside both the sweep and the forward
Occurrence window and cease to exist, which is exactly the live bug this planner
was written to fix.

Too long makes returning expensive. Every Occurrence the sweep decides becomes a
written row, a `sync_queue` entry, and a push to Convex. At thirty days a person
returning from a month away materialises hundreds to thousands of rows on one
foreground and then drains them all — against the criterion that opening the app
after a long absence stays responsive.

A week also matches how the number is read. Adherence is reported by day and
reviewed by week, so a sweep that reaches a week back can always account for
every day a person is looking at.

Bounding by *rows* instead of by *time* was rejected: a cap of N rows makes what
gets recorded depend on how many medications someone takes, so two people with
the same gap get different histories, and the boundary is untestable as a rule.

That rejection is about what bounds the *sweep*. The planner also carries a
per-reminder ceiling on generated Occurrences, and it is deliberately not the
same thing. The Occurrence engine counts rather than ranges, so some count has
to be passed; the ceiling is what that argument is set to, and it exists so a
`schedule` column holding an interval of seconds cannot ask for a row every few
seconds across a week. It does not make one person's history depend on another
reminder of theirs — every reminder gets the whole window independently — and it
is unreachable from any surface the app offers, needing more than one Occurrence
every ten minutes for seven days straight. If it ever binds for a schedule a
person could actually create, that is a bug in this decision, not a tuning
exercise.

## Consequences

The lookback is not the only floor. A sweep also never reaches before the
Reminder itself was created, because day-based Occurrence generation has no
start of its own and would otherwise report a medication added this morning as a
week of missed doses. So the window a sweep actually considers is the *later* of
the lookback bound and the Reminder's creation.

An Occurrence more than seven days old that was never answered stays absent
permanently — the next sweep does not reach it either, because the bound is
measured from the current instant each time. Adherence over a period that
includes a long absence therefore reports the days it has, not the days it
lacks; a gap reads as no data rather than as a wall of Missed. That is the
intended reading, but anyone computing "doses expected" from Instances alone
will undercount across such a gap.

Within the Grace Period an Occurrence is neither answered nor Missed. It is
`pending`, which Adherence excludes from both halves of its fraction, so the
current day's percentage is always computed over the part of the day that has
settled.

Both numbers live as named constants in the shared package next to the planner
that reads them, so the boundary a test pins and the boundary the app applies are
the same value.

## Amendment: a snooze nobody came back to expires

The first version of this decision left every `snoozed` Instance alone, on the reasoning
that a deferral is a deliberate act and not an absence. That is true while the deferral
is running. It stops being true once the moment the person put the dose off to has come
and gone with no answer: the row then holds the dose open for good, and Adherence counts
it as a deferral (in the denominator of a medication, with no verdict) instead of the
miss it was. "Pick a time" made it worse, since a dose can now be put off for hours.

So a `snoozed` Instance becomes `missed` when the *later* of its deferred moment
(`snoozedUntil`) and its own scheduled moment is more than a Grace Period old. The
deferred moment gets the same two hours any other moment does, and the same boundary:
exactly one Grace Period old is still answerable. The scheduled moment is the floor
because a snooze can end before the dose was due (answered early, put off a few minutes).
A snoozed row with no `snoozedUntil` reads as its scheduled moment.

The rule is `snoozeRanOut` in the sweep planner, and the repository applies it again
inside the transaction that writes `missed`: the sweep plans from a snapshot, and a
snooze made between that read and the write must not be overwritten.

Nothing else changes. The Grace Period is still two hours and the lookback still seven
days. A snooze still inside its deferral, or inside the Grace Period after it, is not
touched.
