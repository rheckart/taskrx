//! The person's data as the four `list` subscriptions last delivered it, decoded as the phone's
//! pull decodes it. In memory only (ADR 0016).

use std::collections::HashMap;

use serde_json::Value;
use taskrx_desktop_core::domain::{Notice, NoticeResolution, Reminder, ReminderInstance};
use taskrx_desktop_core::sync::server_docs::{
    self, instance_to_pulled_with, notice_to_pulled, parent_local_ids, reminder_to_pulled,
};

/// The four lists the desktop subscribes to, as delivered. `None` until the first answer.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Lists {
    pub reminders: Option<Value>,
    pub instances: Option<Value>,
    pub notices: Option<Value>,
    pub watch_sources: Option<Value>,
}

impl Lists {
    /// Reminders and their Instances have both arrived: Today can be drawn.
    pub fn has_today(&self) -> bool {
        self.reminders.is_some() && self.instances.is_some()
    }
}

#[derive(Debug, Clone, Default)]
pub struct Data {
    /// Every Reminder, stopped ones included: their recorded Instances are still history.
    pub reminders: Vec<Reminder>,
    /// Instances whose parent is known; `reminder_id` is the parent's local id.
    pub instances: Vec<ReminderInstance>,
    pub notices: Vec<Notice>,
    reminder_server_ids: HashMap<String, String>,
    instance_server_ids: HashMap<String, String>,
    /// Documents left out: an unknown kind or status, or an Instance whose parent is not listed.
    pub left_out: usize,
}

impl Data {
    /// Decodes what has arrived. One malformed document fails its whole list, as on the phone.
    pub fn decode(lists: &Lists) -> Result<Data, String> {
        let empty = Value::Array(Vec::new());
        let reminder_docs = server_docs::reminders(lists.reminders.as_ref().unwrap_or(&empty))
            .map_err(|e| format!("reminders: {e}"))?;
        let instance_docs = server_docs::instances(lists.instances.as_ref().unwrap_or(&empty))
            .map_err(|e| format!("instances: {e}"))?;
        let notice_docs = server_docs::notices(lists.notices.as_ref().unwrap_or(&empty))
            .map_err(|e| format!("notices: {e}"))?;

        let mut data = Data::default();
        for doc in &reminder_docs {
            match reminder_to_pulled(doc) {
                Some(pulled) => {
                    data.reminder_server_ids
                        .insert(pulled.reminder.id.clone(), pulled.convex_id);
                    data.reminders.push(pulled.reminder);
                }
                None => data.left_out += 1,
            }
        }
        let parents = parent_local_ids(&reminder_docs);
        for doc in &instance_docs {
            match instance_to_pulled_with(doc, &parents) {
                Some(pulled) => {
                    data.instance_server_ids
                        .insert(pulled.instance.id.clone(), pulled.convex_id);
                    data.instances.push(pulled.instance);
                }
                None => data.left_out += 1,
            }
        }
        for doc in &notice_docs {
            match notice_to_pulled(doc) {
                Some(pulled) => data.notices.push(pulled.notice),
                None => data.left_out += 1,
            }
        }
        Ok(data)
    }

    pub fn reminder(&self, local_id: &str) -> Option<&Reminder> {
        self.reminders.iter().find(|r| r.id == local_id)
    }

    pub fn instance(&self, local_id: &str) -> Option<&ReminderInstance> {
        self.instances.iter().find(|i| i.id == local_id)
    }

    pub fn reminder_server_id(&self, local_id: &str) -> Option<&str> {
        self.reminder_server_ids.get(local_id).map(String::as_str)
    }

    pub fn instance_server_id(&self, local_id: &str) -> Option<&str> {
        self.instance_server_ids.get(local_id).map(String::as_str)
    }

    /// Notices still waiting for a decision.
    pub fn open_notices(&self) -> usize {
        self.notices
            .iter()
            .filter(|n| n.resolution == NoticeResolution::None)
            .count()
    }
}
