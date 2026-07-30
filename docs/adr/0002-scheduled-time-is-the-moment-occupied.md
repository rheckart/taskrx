# `scheduledTime` is the moment an Instance occupied, not when it was due

Backlog Reminders have no Schedule and therefore no Occurrence, so there is no
due-time to key a Reminder Instance by — yet completing one has to record
*something*. Rather than make `scheduledTime` nullable, a backlog Instance is
minted at the moment the user acts, with `scheduledTime` equal to
`completedAt`. So `scheduledTime` means "the instant this Instance occupied on
the timeline", which coincides with the due time for everything scheduled and
with the completion time for everything in the Backlog.

## Considered Options

Making `scheduledTime` nullable was rejected: it carries four time-ordered
indexes (`by_user_time` and `by_user_status_time`, plus their SQLite twins), and
every one of them would need a null story.

Putting completion on the Reminder itself for Backlog items was rejected because
it creates two completion paths — one for scheduled things, one for unscheduled
— that sync, adherence, and the timeline would each have to learn separately,
and that could disagree.

## Consequences

Anyone reading `by_user_status_time` will reasonably assume it orders by due
time. For Backlog Instances it orders by completion time instead. A Backlog
Reminder is also invisible to any query over Instances until it is acted on —
the Backlog list is rendered from Reminder rows, not Instance rows.
