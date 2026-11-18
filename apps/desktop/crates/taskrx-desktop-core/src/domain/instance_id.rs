//! One Occurrence, one Instance id, on every device (ADR 0018). Kotlin: `core/domain/InstanceId.kt`.

/// The fixed namespace every TaskRx Instance id is derived under.
pub const INSTANCE_ID_NAMESPACE: &str = "937826dc-d56b-41d7-aaef-162b5e2d434f";

/// The Instance id for `reminder_id`'s Occurrence at `scheduled_time`: UUID v5 (SHA-1) of
/// `"<reminderId>@<scheduledTime>"` under [`INSTANCE_ID_NAMESPACE`], lowercase and hyphenated.
pub fn instance_id_for(reminder_id: &str, scheduled_time: i64) -> String {
    let namespace = uuid::Uuid::parse_str(INSTANCE_ID_NAMESPACE).expect("the namespace is a UUID");
    uuid::Uuid::new_v5(
        &namespace,
        format!("{reminder_id}@{scheduled_time}").as_bytes(),
    )
    .to_string()
}
