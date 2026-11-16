# An overdue task does not expire

ADR 0005 bounds the Missed sweep at seven days back. That bound exists because every dose the
sweep decides becomes a written row, a `sync_queue` entry and a push, and returning after a
month away must not materialise thousands of doses on one foreground. The Tasks tab inherited
the same bound for a simpler reason: it read the rows the sweep had recorded.

That inheritance is wrong for tasks. A missed dose is an event in a treatment history; a task
is a commitment the person still means to keep. A one-off task that fell due more than a week
ago is not history — it is the thing that disappeared from the screen, and the person cannot
answer what they cannot see. So **Overdue reaches back as far as the task does**, and only the
sweep keeps the seven-day bound.

## The shape

`sectionTasks` no longer filters on `SWEEP_LOOKBACK_MS`. It takes every past-due `pending` or
`missed` task row it is given, still one row per task at the latest such moment.

The caller supplies the older rows, in two pieces:

- **Stored task Instances from before the projected window.** These are facts already recorded
  — a sweep that did run, an answer, a snooze nobody came back to — read with the ordinary
  Instance query. A snoozed row past its deferral is shown as the Missed row it is.
- **The latest past moment of a task with no stored row at all.** `latestOccurrenceBefore` in
  `:core` asks the Schedule for its most recent moment before now and finds it directly.
  Nothing is minted until the person answers, so a task from two years ago costs one row, not
  two years of rows.

The projection still runs over `now - 7 days` through `day + 7`. Widening *that* window was
the obvious alternative and is rejected: the day-based Occurrence engine's horizons are
measured from the window start, so a year-wide window spends the `daily` horizon in its first
sixty days and hides the recent task. The latest-moment lookup asks the engine the question it
can answer cheaply.

## What does not change

- **Catch up still reaches seven days**, because it lists what the sweep recorded and the
  sweep still reaches seven days. An older task is answered from its Overdue row, which
  offers Done and Skip directly.
- **The sweep is untouched.** Doses keep ADR 0005 exactly as written, including its cost
  bound, its pause and snooze rules, and its "stays absent permanently" consequence.
- **Adherence is untouched.** It already excludes tasks, and this decision mints nothing.
- **Overdue is still a display word.** The stored status stays `pending` or `missed`.

## Consequence

An unfinished one-off task sits in Overdue until it is answered. That is the intended
behaviour and the reason for the decision, but it means Overdue is not a calendar section and
has no natural empty state on a person who keeps a long tail of commitments. If that becomes
noise, the answer is a filter or a grouping on the surface, not an expiry rule here.
