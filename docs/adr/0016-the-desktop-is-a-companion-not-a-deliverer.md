# The desktop is a companion: it shows and answers, the phone delivers

A desktop TaskRx (Omarchy Linux first) is wanted for seeing the day, answering, and typing
reminders on a keyboard. It is a **Companion**: it reads and writes the same Convex data as
the phone, and never arms a notification. The phone remains the only device with a Horizon.

## What the desktop does

- Today (Medications timeline, Tasks sections), add and edit reminders (the phrase parser,
  pause, stop), Backlog, Catch up, and Proposals from mailbox notices.
- Answers write to Convex directly. The phone sees them as ordinary remote writes and its
  existing conflict rules (`core/sync/Conflict.kt`) apply unchanged.
- Shows a past-grace, unanswered Occurrence as Missed by running the ported sweep planner,
  but **never persists a sweep verdict**. The phone stays the one writer of `missed`
  (ADR 0005). Answering such an Occurrence from the desktop is allowed.
- The share sheet and notification listener stay phone-only. The mailbox road already runs
  on the server, so its notices reach the desktop with no desktop work.

## Considered Options

**Delivering while open, or as a background service, was rejected.** A laptop sleeps and has
no equivalent of `setAlarmClock`, so a desktop Horizon would be a weaker guarantee wearing
the same name. Two delivering devices also need a rule for "both alerted" and for clearing
one when the other answers. None of that buys a dose that the phone does not already
deliver, and it would put ADR 0003's device-verified evidence in question.

**An offline-first local store was rejected.** The desktop holds data **in memory only**.
A Room-style store and sync queue on the laptop would be a second plaintext copy of health
data on disk, widening the encryption-at-rest gap ADR 0004 left open, and would mean porting
the phone's whole data and sync layer. Without a connection the desktop says it cannot
reach the server and offers nothing to answer.

## Consequences

- `CONTEXT.md` gains **Companion**, and the Horizon is defined as the phone's.
- A desktop that cannot reach Convex is useless, by design. The laptop must reach the
  self-hosted deployment (`infra/convex`) over `https://`.
- The sign-in session token is the one thing the desktop writes to disk, and it goes in the
  system keyring (Secret Service), never a plain file.
- Two devices can now create an Instance for the same Occurrence. ADR 0018 is what keeps
  that to one row.
- The stack is ADR 0017.

## The phone has to hear about the desktop

The phone pulls only while its process is alive (`SyncCoordinator`), and it reschedules after
each pull. Without a change, a dose answered on the desktop still alarms on the phone, and a
medication added or paused on the desktop is not armed or disarmed until the phone app next
runs. So, on the phone:

- **A periodic background pull.** A WorkManager periodic job (Android's 15-minute minimum)
  pulls once and reschedules, so desktop edits reach the Horizon with the app closed.
- **A check at fire time.** Before a dose notification posts, the receiver asks Convex, with
  a short timeout, whether that Occurrence is already answered, and stays silent if it is.
  **It fails open:** no network, no token, a timeout or any error means the dose posts as it
  does today. A Companion may suppress an alarm only by a positive answer from the server,
  never by being unreachable.

Failing open covers a check that is slow or wrong, not one that never finishes: the check
builds the app graph and loads the Convex library, and a process that dies on the way (a native
crash, a low-memory kill) would take the dose with it. So before the check starts, the receiver
arms a backup alarm holding the same dose, 15 seconds out on the same primitive, and cancels it
once the dose has posted or the server has answered. The backup posts without asking. The price
is a dose up to 15 seconds late in the rare case it is needed.

The timeout is per moment, not per dose. Android hands a process its alarms one at a time and
holds the next until the last one's receiver is done, so on a phone with five doses at 07:30
and no network, a timeout per dose would post the fifth twenty seconds late, where the build
before the check posted all five at once. The doses of one instant share one read of that
instant and one timeout, counted from the first alarm; a dose that arrives after it still gets a
quarter second to match its rows, so a read that lands after the first dose gave up answers a
later dose only if it lands within that quarter second. This removes the stacking, not the
timeout: on the phone a process woken from a long freeze took about 6 s to reach the server, so
a dose answered elsewhere can still ring after an idle wake until that reconnect is shortened.

The check runs on the Medication Tier's path, which ADR 0003's device evidence covers. It
needs its own harness cases: offline posts, answered elsewhere stays silent, and a slow
server posts within the timeout. Server push (FCM) was rejected for now: it brings a
third-party SDK that ADR 0004 would have to clear, for latency the two measures above mostly
cover.
