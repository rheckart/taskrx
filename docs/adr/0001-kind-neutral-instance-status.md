# Kind-neutral status vocabulary on Reminder Instances

Medications and tasks share one timeline, so one component renders both side by
side. Rather than keep the medication spelling (`taken`, `takenAt`) and have
every consumer branch on Kind to decide whether to display "Taken" or "Done", a
Reminder Instance is now `pending | completed | snoozed | skipped | missed` with
`completedAt`. The five states already fitted both Kinds; only the name of one
member was medication jargon.

## Considered Options

Relabelling `taken` as "Done" in the task UI was materially cheaper — no
migration, and the adherence maths was already written against `taken`. It was
rejected because it pushes a permanent Kind-check into every surface that ever
displays a status, which is exactly the per-consumer cost that a unified
timeline is supposed to remove.

Adding `done` alongside `taken` was rejected because nothing would then stop a
task being written as `taken`, and the two halves would drift.

## Consequences

A clinical reader expects "taken" for a drug, and will not find it. The word
survives in the UI layer — a medication is still *shown* as taken — but the
stored vocabulary is Kind-neutral, and `completedAt` on a medication instance is
the ingestion timestamp.
