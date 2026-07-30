# Medication and task data share one store, and all of it is treated as health data

`docs/task-med-reminder-app-research (2).md` proposes architectural isolation —
health data in a compliant vault, task data in a standard store, cross-referenced
only in the UI. We are doing the opposite: one `reminders` table with Kind as a
column, one sync path, one export path. Kind is a discriminator, not a boundary.
The consequence is accepted deliberately: because any row could be a medication,
**every** row gets health-grade handling, and any path by which data leaves the
device — analytics, crash reporting, export, sharing — must be explicitly
Kind-filtered and reviewed before it ships.

## Consequences

Task data inherits obligations it would not have carried on its own. No
third-party SDK may see row content. This is cheap to hold as a rule now and
expensive to retrofit once an analytics SDK is already wired in.

HIPAA is very unlikely to apply — this is a consumer app with no provider
relationship — but the FTC Act and the Health Breach Notification Rule apply
regardless of that. GoodRx was fined $1.5M under exactly that combination while
not being a covered entity.

Encryption at rest is **not** part of this decision and remains open. The device
database is opened unkeyed, so medication names and adherence history sit in
plaintext on the device. The cheapest moment to close this was during the
unified-table cutover, when every row is rewritten anyway; that opportunity was
consciously passed over, and the gap is now on its own footing.
