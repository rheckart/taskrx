# A `monthly` Schedule clamps a missing date to the month's last day

`monthly` is a new Schedule variant: `MonthlyTimes(daysOfMonth, times)`, a set of
dates of the month (1 to 31) and the local times that come due on each. It is the
same shape as `weekly` one calendar step out, and it exists because "pay the rent
on the 1st" and "check the boiler on the 15th" are the most common recurring
things people write down that the other three variants cannot say.

Not every month has every date. The 31st exists in seven months, the 30th in
eleven, and February has neither. Something has to be decided for the other
cases.

## Decision

**A date a month does not have lands on that month's last day.** A reminder set
for the 31st fires on 31 January, 28 February (29 in a leap year), 31 March, 30
April, and so on. The 29th does the same in a non-leap February.

Two entries that collide in a short month — the 30th and the 31st in February —
are the same date, and fire once.

## Considered Options

**Skip the month.** The literal reading: the 31st simply does not come due in
February. Rejected because the failure is invisible. A reminder that does not
fire looks exactly like a reminder with nothing to do, and the person finds out
when the thing they were being reminded about did not happen. Firing a day early
is a small annoyance; not firing is a missed payment.

**Ask at the moment it is written.** "The 31st, or the last day when there is
none?" is a question about a case most people never hit, asked of everyone, and
answered once for all time even though the person may change their mind about
what they wanted. The clamp is the answer people give when they are asked, which
is the argument for making it the default rather than a field.

**Treat it as an interval** ("every month from this date"). Rejected as a
different feature: it fixes the day of the month to whatever the first
occurrence happened to be, so a reminder started on the 30th would drift to
February 28 and then stay there. A monthly reminder is about a date in each
month, not about 30.44 days of elapsed time.

## Consequences

The day a monthly reminder fires can vary by up to three days across the year.
Its sentence says so where the date is not universally real: the picker's hint
reads "A month without that date uses its last day — the 31st fires on February
28", and the schedule is described as "31st of the month at 09:00".

The Occurrence walk is the one `weekly` already uses, over a 400-day horizon
rather than 56. That is what a once-a-month schedule needs to supply a dozen
occurrences — and the timeline asks every reminder for its next 64 — while
staying well inside the sweep's seven-day lookback, so the last visible
occurrence is never mistaken for one that was Missed.

Like `weekly`, a `monthly` with no date or no time is not a well-formed
schedule: it produces nothing, and the review form asks for the missing half
rather than saving a reminder that never comes due.

Adding the variant is additive everywhere it lands, because every union it
touches is closed and exhaustive: the Kotlin `when` over `ScheduleTimes`, the
Convex `scheduleTimesValidator`, and the wire JSON the `schedule` column holds
(`{"$type":"MonthlyTimes","DaysOfMonth":[…],"Times":[…]}`) each name it
explicitly, and a surface that forgets one fails to compile.
