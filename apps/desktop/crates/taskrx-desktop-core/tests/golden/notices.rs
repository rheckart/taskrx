//! `notices`: reading a date, the sentence kept, which rules match, and the moves proposed.

use serde_json::{Value, json};
use taskrx_desktop_core::domain::{NoticeRule, SourceKind};
use taskrx_desktop_core::notices::{
    ReadDate, kept_notice_sentence, notice_sentences, notice_text, occurrence_options_around,
    proposals_for, read_notice_date, rule_matches, time_text,
};
use taskrx_desktop_core::sync::server_docs::{self, notice_to_pulled};

use super::domain::instances;
use super::schedule::reminder;
use super::{Outcome, field, int, text, unknown_call, zone};

fn notice(doc: &Value) -> Result<taskrx_desktop_core::domain::Notice, String> {
    let docs = server_docs::notices(&Value::Array(vec![doc.clone()])).map_err(|e| e.to_string())?;
    Ok(notice_to_pulled(docs.first().ok_or("empty list")?)
        .ok_or("a notice of an unknown kind")?
        .notice)
}

/// A rule as written, not normalised: the case is about what matching does with it.
fn rule(v: &Value) -> Result<NoticeRule, String> {
    Ok(NoticeRule {
        source_kind: SourceKind::from_wire(text(v, "sourceKind")?).ok_or("unknown source kind")?,
        source_value: text(v, "sourceValue")?.to_string(),
        terms: field(v, "terms")?
            .as_array()
            .ok_or("terms are not a list")?
            .iter()
            .filter_map(|t| t.as_str().map(str::to_string))
            .collect(),
    })
}

pub fn case(input: &Value) -> Outcome {
    match text(input, "call")? {
        "readNoticeDate" => Ok(
            match read_notice_date(text(input, "text")?, int(input, "now")?, &zone(input)?) {
                ReadDate::Found {
                    date,
                    time,
                    matched_text,
                } => {
                    json!({ "date": date.to_string(), "time": time.map(time_text), "matchedText": matched_text })
                }
                ReadDate::Unread(reason) => json!({ "unread": reason.wire() }),
            },
        ),
        "noticeSentences" => Ok(json!(notice_sentences(text(input, "body")?))),
        "keptNoticeSentence" => Ok(json!(kept_notice_sentence(
            text(input, "body")?,
            text(input, "title")?,
            int(input, "now")?,
            &zone(input)?
        ))),
        "matches" => {
            let n = notice(field(input, "notice")?)?;
            Ok(
                json!({ "matches": rule_matches(&rule(field(input, "rule")?)?, &n), "noticeText": notice_text(&n) }),
            )
        }
        "proposalsFor" => {
            let tz = zone(input)?;
            let reminders = field(input, "reminders")?
                .as_array()
                .ok_or("reminders are not a list")?
                .iter()
                .map(reminder)
                .collect::<Result<Vec<_>, _>>()?;
            let minted = instances(field(input, "instances")?)?;
            let proposals = proposals_for(
                &notice(field(input, "notice")?)?,
                &reminders,
                &minted,
                int(input, "now")?,
                &tz,
            );
            Ok(json!(proposals
                .iter()
                .map(|p| json!({
                    "reminderId": p.reminder_id, "from": p.from, "to": p.to, "date": p.date.to_string(),
                    "time": p.time.map(time_text), "matchedText": p.matched_text,
                    "refusal": p.refusal.map(|r| r.wire()), "applies": p.applies(),
                }))
                .collect::<Vec<_>>()))
        }
        "occurrenceOptionsAround" => Ok(json!(occurrence_options_around(
            &reminder(field(input, "reminder")?)?,
            int(input, "around")?,
            &zone(input)?,
            3,
            3
        ))),
        _ => unknown_call(input),
    }
}
