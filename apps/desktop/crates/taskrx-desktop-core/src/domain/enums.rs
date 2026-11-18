//! The enumerations every rule reads, with the strings Convex stores for them.
//! Kotlin: `core/domain/Enums.kt`, `core/domain/Notice.kt`.

macro_rules! wire_enum {
    ($(#[$meta:meta])* $name:ident { $($variant:ident => $wire:literal),+ $(,)? }) => {
        $(#[$meta])*
        #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
        pub enum $name { $($variant),+ }

        impl $name {
            /// Every value, in declaration order.
            pub const ALL: &'static [$name] = &[$($name::$variant),+];

            /// The string Convex stores.
            pub fn wire(self) -> &'static str {
                match self { $($name::$variant => $wire),+ }
            }

            /// The value for a stored string, or `None` for one this build does not know.
            pub fn from_wire(wire: &str) -> Option<Self> {
                match wire { $($wire => Some($name::$variant),)+ _ => None }
            }
        }
    };
}

wire_enum!(
    /// What a Reminder is: a medication dose or a task.
    ReminderKind { Medication => "medication", Task => "task" }
);

wire_enum!(
    Priority { Critical => "critical", High => "high", Normal => "normal", Low => "low" }
);

wire_enum!(
    /// What became of one Occurrence.
    InstanceStatus {
        Pending => "pending",
        Completed => "completed",
        Snoozed => "snoozed",
        Skipped => "skipped",
        Missed => "missed",
    }
);

wire_enum!(
    /// Where a Notice came from.
    SourceKind { Email => "email", App => "app", Share => "share" }
);

wire_enum!(
    /// What became of a Notice.
    NoticeResolution { None => "none", Applied => "applied", NewTask => "newTask", Dismissed => "dismissed" }
);

/// The statuses still waiting for the person: pending, snoozed, and the sweep's guess, missed.
pub fn is_answerable(status: InstanceStatus) -> bool {
    matches!(
        status,
        InstanceStatus::Pending | InstanceStatus::Snoozed | InstanceStatus::Missed
    )
}

/// A moment is answered when it has rows and none of them is still answerable.
pub fn is_moment_answered(statuses: &[InstanceStatus]) -> bool {
    !statuses.is_empty() && statuses.iter().all(|s| !is_answerable(*s))
}
