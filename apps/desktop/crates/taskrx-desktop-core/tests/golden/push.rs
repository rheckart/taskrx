//! `push`: the Convex call a write's payload becomes.

use serde_json::{Value, json};
use taskrx_desktop_core::sync::push::{
    PushCall, Wire, instance_create_call, instance_update_call, notice_create_call,
    notice_delete_call, notice_update_call, reminder_create_call, reminder_delete_call,
    reminder_update_call, watch_source_create_call, watch_source_delete_call,
    watch_source_update_call,
};

use super::{Outcome, field, text};

fn call_json(call: PushCall) -> Value {
    json!({ "function": call.function, "args": Wire::Object(call.args).to_json() })
}

pub fn case(input: &Value) -> Outcome {
    let payload = field(input, "payload")?;
    let convex_id = || text(input, "convexId");
    let call = match (text(input, "entity")?, text(input, "operation")?) {
        ("reminder", "create") => reminder_create_call(payload),
        ("reminder", "update") => reminder_update_call(payload, convex_id()?),
        ("reminder", "delete") => Ok(reminder_delete_call(convex_id()?)),
        ("instance", "create") => instance_create_call(payload, text(input, "parentConvexId")?),
        ("instance", "update") => instance_update_call(payload, convex_id()?),
        ("notice", "create") => notice_create_call(payload),
        ("notice", "update") => notice_update_call(payload, convex_id()?),
        ("notice", "delete") => Ok(notice_delete_call(convex_id()?)),
        ("watchSource", "create") => watch_source_create_call(payload),
        ("watchSource", "update") => watch_source_update_call(payload, convex_id()?),
        ("watchSource", "delete") => Ok(watch_source_delete_call(convex_id()?)),
        (entity, operation) => return Err(format!("no handler for {entity} {operation}")),
    };
    call.map(call_json).map_err(|e| e.0)
}
