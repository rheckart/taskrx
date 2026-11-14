# A notice is evidence, not a commitment

An outside message says a commitment's time has changed: the waste company emails
"collection moves to Wednesday 18 November", or an app posts a notification saying the same.
Or a deadline arrives that has no task yet. TaskRx keeps the sentence, works out what date it
names, and **proposes** what that means for one task. The person answers; only then does
anything move.

The rule the whole feature is built around: **nothing outside the app ever changes a
reminder by itself.**

## Decision

A Notice is a stored row — source, subject, one or two sentences, received time and a
de-duplication id — and it is **evidence**. It never applies anything. From it the app
derives a **Proposal** against one task, and the person confirms or dismisses it. Confirming a
move writes an `OccurrenceOverride` (ADR 0013), not a schedule edit.

Three roads feed one funnel, and all of them are opt-in:

- **Share sheet.** The person shares text with TaskRx. The share *is* the approval; there is
  no watched source to check.
- **Notification listener.** A `NotificationListenerService` receives every notification on
  the device, which is why it is two-step: the **package name** of every notification goes
  into a seen list and nothing else, and title and text are read only for a package the
  person has approved as a watched source.
- **Mailbox.** A Convex action on a five-minute cron polls one Fastmail mailbox over JMAP.
  Nothing on the device speaks to the mail server. A message from an unwatched sender is
  skipped and writes nothing. With no `JMAP_TOKEN` the poll is a no-op.

The funnel's order: **is the source watched** (no: drop, write nothing) → **is this the same
message again** (de-duplicated on the email id, the notification key plus post time, or a
derived id) → **choose the sentence to keep** → **write the notice**. Proposals are derived on
read.

## Retention

A Notice keeps the source, the subject, the sentence the date came from (plus the one before
it when the dated sentence has no subject of its own), the received time and the
de-duplication id. **The body is discarded** after the sentence is chosen.

Notices **sync**. Because the mailbox poll runs on the server, a notice fetched and discarded
there could never reach a phone that is not running, so a notice is a row in Convex like a
reminder, scoped per user. That is a real change in privacy posture and is stated plainly: the
server and its offsite backups now hold those sentences. The narrow retention is the
mitigation — the rest of the message never leaves the mail server.

## The reader is in one place

`core/notices/Extract.kt` is the only date parser in the product. It reads absolute dates and
nothing else: month names and unambiguous numerics, **never a relative word, never a guessed
year, and never an ambiguous numeric date** (`05/06` yields no date). A sentence with no date
is a supported state, and the screen says "no date found" rather than showing a plausible
wrong one.

The server does **no parsing at all**. `mailbox.keptSentences` only picks which spans are
worth keeping — sentences containing a month name or a digit — and stores them. It could
disagree with Kotlin about what is important, but not about what a date is, because it never
forms an opinion. That is why the same sentence arriving by email and by notification reads to
the same date.

## Considered Options

**Apply the notice automatically.** The app cannot verify an outside message, and a wrong date
on a commitment is the failure this app exists to avoid. A proposal costs one tap.

**Rewrite the schedule instead of one occurrence.** "Wednesday this week" is not "every
Wednesday"; ADR 0013 has the full argument.

**Keep the body in the app and parse there.** The retention is the point; the sentence is
enough to decide, and the body is where the personal detail lives.

**Let the server parse dates.** Two implementations drift, and the one the screen shows would
be the one that must be right.

**A forward-to-TaskRx address.** Deferred; the three roads cover where notices actually
arrive.

**Per-user mailboxes.** One mailbox for the deployment (D15) matches how this backend is run.
The UI says so plainly, because a second person signing in on the same backend would see the
first person's notices until a per-user mailbox exists.

## What may never move

- **Medications never move.** A medication schedule is a clinical instruction; moving a dose
  because an outside message said so is the app acting on health information it cannot verify.
- **An answered or opened occurrence never moves**, and neither does one past its Grace Period
  (ADR 0005). The proposal offers "create a separate task" or dismiss instead.
- **A pause wins** over a move covering either end.

A rule is never required: a notice can become a task by hand and be applied to a task by hand
(D13). A rule on the reminder is only the pre-filled version of the same action.

## Consequences

- **Notification access is broad.** Play review can ask why it is needed. The app receives
  everything an app posts before anyone approves it; the service keeps only a package name
  from an unapproved one, and the copy in Settings says so.
- **The mailbox is a live credential in the environment** (`JMAP_TOKEN`), never in the
  database.
- **Some dates are missed.** Relative words, ambiguous numerics and locale-specific formats do
  not read. That is the chosen trade.
- **An override can orphan** when the schedule is edited. It is inert rather than wrong, and
  the edit form lists moves so it never becomes a mystery.
- **Notices accumulate.** They are history; the screen shows the recent ones rather than
  everything ever received.
