//! Notices (ADR 0014): the date a message names, the sentence kept from it, which rules it
//! matches, and the move each matching task is offered. Kotlin: `core/notices/Extract.kt`,
//! `Intake.kt`, `Proposal.kt`. The date reader is meant to have one implementation, so this is
//! a second one, held to the first only by the golden fixtures.
//!
//! Java's `\d`, `\s` and `\b` are ASCII here, so the patterns spell their classes out: `[0-9]`,
//! `[ \t\n\x0B\x0C\r]`, and a word boundary as "no word character" on the side that needs one.

use std::sync::LazyLock;

use fancy_regex::{Captures, Regex};
use jiff::ToSpan;
use jiff::civil::{Date, Time};
use jiff::tz::TimeZone;

use crate::domain::{
    InstanceStatus, MoveRefusal, Notice, NoticeRule, Reminder, ReminderInstance, ReminderKind,
    move_refusal, normalize_source_value,
};
use crate::schedule::next_occurrences;
use crate::sweep::GRACE_PERIOD_MS;
use crate::time::{instant_at, local_date};

// ---- reading a date --------------------------------------------------------------------------

/// The time a dated notice falls at when it names no time.
pub const DEFAULT_NOTICE_HOUR: i8 = 9;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UnreadReason {
    NoDate,
    Ambiguous,
    Competing,
    Impossible,
}

impl UnreadReason {
    pub fn wire(self) -> &'static str {
        match self {
            Self::NoDate => "noDate",
            Self::Ambiguous => "ambiguous",
            Self::Competing => "competing",
            Self::Impossible => "impossible",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReadDate {
    Found {
        date: Date,
        time: Option<Time>,
        matched_text: String,
    },
    Unread(UnreadReason),
}

const WS: &str = r"[ \t\n\x0B\x0C\r]";

const MONTH_NAMES: [(&str, i8); 25] = [
    ("january", 1),
    ("jan", 1),
    ("february", 2),
    ("feb", 2),
    ("march", 3),
    ("mar", 3),
    ("april", 4),
    ("apr", 4),
    ("may", 5),
    ("may", 5),
    ("june", 6),
    ("jun", 6),
    ("july", 7),
    ("jul", 7),
    ("august", 8),
    ("aug", 8),
    ("september", 9),
    ("sep", 9),
    ("october", 10),
    ("oct", 10),
    ("november", 11),
    ("nov", 11),
    ("december", 12),
    ("dec", 12),
    ("sept", 9),
];

/// The names, longest first, so the alternation never stops at "sep" inside "september".
fn month_pattern() -> String {
    let mut names: Vec<&str> = MONTH_NAMES.iter().map(|(n, _)| *n).collect();
    names.sort_by_key(|n| std::cmp::Reverse(n.len()));
    names.dedup();
    names.join("|")
}

fn month_of(name: &str) -> Option<i8> {
    let lower = name.to_lowercase();
    MONTH_NAMES
        .iter()
        .find(|(n, _)| *n == lower)
        .map(|(_, m)| *m)
}

fn regex(pattern: &str) -> Regex {
    Regex::new(pattern).expect("a valid pattern")
}

static ISO_DATE: LazyLock<Regex> =
    LazyLock::new(|| regex(r"(?<![0-9])([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})(?![0-9])"));
static MONTH_FIRST: LazyLock<Regex> = LazyLock::new(|| {
    regex(&format!(
        r"(?i)(?<![A-Za-z0-9_])({m}){WS}+(?:the{WS}+)?([0-9]{{1,2}})(?:st|nd|rd|th)?(?:,?{WS}+([0-9]{{4}}))?(?![0-9])",
        m = month_pattern()
    ))
});
static DAY_FIRST: LazyLock<Regex> = LazyLock::new(|| {
    regex(&format!(
        r"(?i)(?<![0-9])([0-9]{{1,2}})(?:st|nd|rd|th)?{WS}+(?:of{WS}+)?({m})(?:,?{WS}+([0-9]{{4}}))?(?![0-9])",
        m = month_pattern()
    ))
});
static NUMERIC_WITH_YEAR: LazyLock<Regex> =
    LazyLock::new(|| regex(r"(?<![0-9])([0-9]{1,2})[/.\-]([0-9]{1,2})[/.\-]([0-9]{2,4})(?![0-9])"));
static NUMERIC_DATE: LazyLock<Regex> =
    LazyLock::new(|| regex(r"(?<![0-9])([0-9]{1,2})[/.\-]([0-9]{1,2})(?![0-9])"));
static CLOCK_TIME: LazyLock<Regex> = LazyLock::new(|| {
    regex(&format!(
        r"(?i)(?<![0-9])([0-9]{{1,2}})(?::([0-9]{{2}}))?{WS}*(am|pm)(?![A-Za-z0-9_])"
    ))
});
static PLAIN_TIME: LazyLock<Regex> =
    LazyLock::new(|| regex(r"(?<![0-9])([0-9]{1,2}):([0-9]{2})(?![0-9])"));
static NOON: LazyLock<Regex> =
    LazyLock::new(|| regex(r"(?i)(?<![A-Za-z0-9_])noon(?![A-Za-z0-9_])"));
static MIDNIGHT: LazyLock<Regex> =
    LazyLock::new(|| regex(r"(?i)(?<![A-Za-z0-9_])midnight(?![A-Za-z0-9_])"));

#[derive(Debug, Clone)]
enum Candidate {
    Valid {
        date: Date,
        text: String,
        start: usize,
        end: usize,
    },
    Ambiguous {
        start: usize,
        end: usize,
    },
    Invalid {
        start: usize,
        end: usize,
    },
}

impl Candidate {
    fn span(&self) -> (usize, usize) {
        match self {
            Candidate::Valid { start, end, .. }
            | Candidate::Ambiguous { start, end }
            | Candidate::Invalid { start, end } => (*start, *end),
        }
    }
}

fn calendar_date(year: i32, month: i32, day: i32) -> Option<Date> {
    let (year, month, day) = (
        i16::try_from(year).ok()?,
        i8::try_from(month).ok()?,
        i8::try_from(day).ok()?,
    );
    Date::new(year, month, day).ok()
}

fn group_int(c: &Captures, i: usize) -> Option<i32> {
    c.get(i).and_then(|m| m.as_str().parse().ok())
}

fn captures(re: &Regex, text: &str) -> Vec<(usize, usize, String, Vec<Option<String>>)> {
    re.captures_iter(text)
        .filter_map(Result::ok)
        .map(|c| {
            let whole = c.get(0).expect("a match");
            let groups = (1..c.len())
                .map(|i| c.get(i).map(|m| m.as_str().to_string()))
                .collect();
            (
                whole.start(),
                whole.end(),
                whole.as_str().to_string(),
                groups,
            )
        })
        .collect()
}

fn resolve(
    month: i8,
    day: i32,
    year: Option<i32>,
    today: Date,
    start: usize,
    end: usize,
    text: String,
) -> Candidate {
    if let Some(year) = year {
        return match calendar_date(year, i32::from(month), day) {
            Some(date) => Candidate::Valid {
                date,
                text,
                start,
                end,
            },
            None => Candidate::Invalid { start, end },
        };
    }
    // The next time that date comes round, today included; a 29 February may be years off.
    for offset in 0..=8 {
        if let Some(date) = calendar_date(i32::from(today.year()) + offset, i32::from(month), day)
            && date >= today
        {
            return Candidate::Valid {
                date,
                text,
                start,
                end,
            };
        }
    }
    Candidate::Invalid { start, end }
}

fn date_candidates(text: &str, today: Date) -> Vec<Candidate> {
    let mut all = Vec::new();
    for c in ISO_DATE.captures_iter(text).filter_map(Result::ok) {
        let m = c.get(0).expect("a match");
        let date = (|| calendar_date(group_int(&c, 1)?, group_int(&c, 2)?, group_int(&c, 3)?))();
        all.push(match date {
            Some(date) => Candidate::Valid {
                date,
                text: m.as_str().to_string(),
                start: m.start(),
                end: m.end(),
            },
            None => Candidate::Invalid {
                start: m.start(),
                end: m.end(),
            },
        });
    }
    for (start, end, whole, g) in captures(&MONTH_FIRST, text) {
        let Some(month) = g[0].as_deref().and_then(month_of) else {
            continue;
        };
        let day: i32 = g[1].as_deref().and_then(|d| d.parse().ok()).unwrap_or(0);
        let year = g[2].as_deref().and_then(|y| y.parse().ok());
        all.push(resolve(month, day, year, today, start, end, whole));
    }
    for (start, end, whole, g) in captures(&DAY_FIRST, text) {
        let Some(month) = g[1].as_deref().and_then(month_of) else {
            continue;
        };
        let day: i32 = g[0].as_deref().and_then(|d| d.parse().ok()).unwrap_or(0);
        let year = g[2].as_deref().and_then(|y| y.parse().ok());
        all.push(resolve(month, day, year, today, start, end, whole));
    }
    for (start, end, whole, g) in captures(&NUMERIC_WITH_YEAR, text) {
        all.push(numeric_with_year(start, end, whole, &g));
    }
    for (start, end, whole, g) in captures(&NUMERIC_DATE, text) {
        all.push(numeric(start, end, whole, &g, today));
    }
    // Earliest first, the longer of two at one place first; then no overlaps.
    all.sort_by_key(|c| {
        let (s, e) = c.span();
        (s, std::cmp::Reverse(e - s))
    });
    let mut kept: Vec<Candidate> = Vec::new();
    for c in all {
        let (s, e) = c.span();
        if kept.iter().all(|k| {
            let (ks, ke) = k.span();
            !(s < ke && ks < e)
        }) {
            kept.push(c);
        }
    }
    kept
}

fn int_of(g: &[Option<String>], i: usize) -> i32 {
    g[i].as_deref().and_then(|v| v.parse().ok()).unwrap_or(0)
}

/// A numeric date with no year: ambiguous unless one half can only be a day.
fn numeric(start: usize, end: usize, text: String, g: &[Option<String>], today: Date) -> Candidate {
    let (first, second) = (int_of(g, 0), int_of(g, 1));
    if first > 12 && second > 12 {
        return Candidate::Invalid { start, end };
    }
    let (month, day) = if first > 12 {
        (second, first)
    } else if second > 12 {
        (first, second)
    } else {
        return Candidate::Ambiguous { start, end };
    };
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return Candidate::Invalid { start, end };
    }
    let Some(date) = calendar_date(i32::from(today.year()), month, day) else {
        return Candidate::Invalid { start, end };
    };
    let resolved = if date < today {
        match calendar_date(i32::from(today.year()) + 1, month, day) {
            Some(d) => d,
            None => return Candidate::Invalid { start, end },
        }
    } else {
        date
    };
    Candidate::Valid {
        date: resolved,
        text,
        start,
        end,
    }
}

fn numeric_with_year(start: usize, end: usize, text: String, g: &[Option<String>]) -> Candidate {
    let (first, second) = (int_of(g, 0), int_of(g, 1));
    let year_text = g[2].as_deref().unwrap_or("");
    if year_text.len() != 4 {
        return Candidate::Ambiguous { start, end };
    }
    let (month, day) = if first > 12 && second > 12 {
        return Candidate::Invalid { start, end };
    } else if first > 12 {
        (second, first)
    } else if second > 12 {
        (first, second)
    } else {
        return Candidate::Ambiguous { start, end };
    };
    match calendar_date(year_text.parse().unwrap_or(0), month, day) {
        Some(date) => Candidate::Valid {
            date,
            text,
            start,
            end,
        },
        None => Candidate::Invalid { start, end },
    }
}

/// The first time of day the text names, if any.
fn first_time(text: &str) -> Option<Time> {
    let mut all: Vec<(usize, usize, Time)> = Vec::new();
    for (start, end, _, g) in captures(&CLOCK_TIME, text) {
        let hour = int_of(&g, 0);
        let minute = g[1].as_deref().map_or(0, |m| m.parse().unwrap_or(0));
        if !(1..=12).contains(&hour) || !(0..=59).contains(&minute) {
            continue;
        }
        let meridiem = g[2].as_deref().unwrap_or("").to_lowercase();
        let hour24 = match (meridiem.as_str(), hour) {
            ("am", 12) => 0,
            ("pm", h) if h != 12 => h + 12,
            (_, h) => h,
        };
        all.push((
            start,
            end,
            Time::new(hour24 as i8, minute as i8, 0, 0).expect("a time of day"),
        ));
    }
    for (start, end, _, g) in captures(&PLAIN_TIME, text) {
        let (hour, minute) = (int_of(&g, 0), int_of(&g, 1));
        if !(0..=23).contains(&hour) || !(0..=59).contains(&minute) {
            continue;
        }
        all.push((
            start,
            end,
            Time::new(hour as i8, minute as i8, 0, 0).expect("a time of day"),
        ));
    }
    for (start, end, _, _) in captures(&NOON, text) {
        all.push((start, end, Time::new(12, 0, 0, 0).expect("noon")));
    }
    for (start, end, _, _) in captures(&MIDNIGHT, text) {
        all.push((start, end, Time::midnight()));
    }
    all.sort_by_key(|(s, e, _)| (*s, std::cmp::Reverse(e - s)));
    let mut kept: Vec<(usize, usize, Time)> = Vec::new();
    for c in all {
        if kept.iter().all(|k| !(c.0 < k.1 && k.0 < c.1)) {
            kept.push(c);
        }
    }
    kept.first().map(|c| c.2)
}

/// The one date `text` names, or why it names none: nothing, only an ambiguous numeric date,
/// only impossible ones, or two different dates.
pub fn read_notice_date(text: &str, now: i64, tz: &TimeZone) -> ReadDate {
    let today = local_date(now, tz);
    let candidates = date_candidates(text, today);
    if candidates.is_empty() {
        return ReadDate::Unread(UnreadReason::NoDate);
    }
    let valid: Vec<(&Date, &String)> = candidates
        .iter()
        .filter_map(|c| match c {
            Candidate::Valid { date, text, .. } => Some((date, text)),
            _ => None,
        })
        .collect();
    if valid.is_empty() {
        let ambiguous = candidates
            .iter()
            .any(|c| matches!(c, Candidate::Ambiguous { .. }));
        return ReadDate::Unread(if ambiguous {
            UnreadReason::Ambiguous
        } else {
            UnreadReason::Impossible
        });
    }
    let mut dates: Vec<Date> = Vec::new();
    for (d, _) in &valid {
        if !dates.contains(d) {
            dates.push(**d);
        }
    }
    if dates.len() > 1 {
        return ReadDate::Unread(UnreadReason::Competing);
    }
    ReadDate::Found {
        date: dates[0],
        time: first_time(text),
        matched_text: valid[0].1.clone(),
    }
}

/// `HH:MM`, as `java.time.LocalTime` prints a time with no seconds.
pub fn time_text(t: Time) -> String {
    format!("{:02}:{:02}", t.hour(), t.minute())
}

// ---- the sentence kept -----------------------------------------------------------------------

/// The body's sentences: split after `.`, `!` or `?` (a run of them stays together), and at a
/// blank line; lines inside a paragraph are joined with a space.
pub fn notice_sentences(body: &str) -> Vec<String> {
    let mut sentences = Vec::new();
    let mut current = String::new();
    // Kotlin's `lines()`: \r\n, \n or \r.
    for line in body.replace("\r\n", "\n").replace('\r', "\n").split('\n') {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            if !current.trim().is_empty() {
                sentences.push(current.trim().to_string());
            }
            current.clear();
            continue;
        }
        if !current.is_empty() {
            current.push(' ');
        }
        let chars: Vec<char> = trimmed.chars().collect();
        let mut i = 0;
        while i < chars.len() {
            let ch = chars[i];
            current.push(ch);
            i += 1;
            if matches!(ch, '.' | '!' | '?') {
                while i < chars.len() && matches!(chars[i], '.' | '!' | '?') {
                    current.push(chars[i]);
                    i += 1;
                }
                sentences.push(current.trim().to_string());
                current.clear();
            }
        }
    }
    if !current.trim().is_empty() {
        sentences.push(current.trim().to_string());
    }
    sentences.retain(|s| !s.is_empty());
    sentences
}

/// A sentence that does not start a thought: its first letter is lower case and not a digit.
fn is_fragment(sentence: &str) -> bool {
    let Some(first) = sentence.chars().find(|c| !c.is_whitespace()) else {
        return false;
    };
    !first.is_uppercase() && !first.is_ascii_digit()
}

/// The sentence a notice keeps from its body: the first that names a date (with the one before
/// it when it is only a fragment), else the first, else the title.
pub fn kept_notice_sentence(body: &str, title: &str, now: i64, tz: &TimeZone) -> Option<String> {
    let sentences = notice_sentences(body);
    if sentences.is_empty() {
        let t = title.trim();
        return (!t.is_empty()).then(|| t.to_string());
    }
    let Some(dated) = sentences
        .iter()
        .position(|s| matches!(read_notice_date(s, now, tz), ReadDate::Found { .. }))
    else {
        return Some(sentences[0].clone());
    };
    let start = if dated > 0 && is_fragment(&sentences[dated]) {
        dated - 1
    } else {
        dated
    };
    Some(sentences[start..=dated].join(" "))
}

// ---- rules and proposals ---------------------------------------------------------------------

/// The text a rule's words are looked for in, and a date read from: the title, then the sentence.
pub fn notice_text(notice: &Notice) -> String {
    match &notice.sentence {
        Some(s) => format!("{} {s}", notice.title),
        None => notice.title.clone(),
    }
}

/// Whether `rule` admits `notice`: the same source, spelled the same way once normalised, and
/// one of its words in the notice when it has any.
pub fn rule_matches(rule: &NoticeRule, notice: &Notice) -> bool {
    if rule.source_kind != notice.source_kind {
        return false;
    }
    let (Some(ruled), Some(seen)) = (
        normalize_source_value(rule.source_kind, &rule.source_value),
        normalize_source_value(notice.source_kind, &notice.source_value),
    ) else {
        return false;
    };
    if ruled != seen {
        return false;
    }
    if rule.terms.is_empty() {
        return true;
    }
    let text = notice_text(notice).to_lowercase();
    rule.terms.iter().any(|t| text.contains(&t.to_lowercase()))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProposalRefusal {
    /// The task has no moment near the date to move.
    NoOccurrence,
    Move(MoveRefusal),
}

impl ProposalRefusal {
    pub fn wire(self) -> &'static str {
        match self {
            Self::NoOccurrence => "noOccurrence",
            Self::Move(m) => m.wire(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Proposal {
    pub reminder_id: String,
    pub from: Option<i64>,
    pub to: i64,
    pub date: Date,
    pub time: Option<Time>,
    pub matched_text: String,
    pub refusal: Option<ProposalRefusal>,
}

impl Proposal {
    pub fn applies(&self) -> bool {
        self.from.is_some() && self.refusal.is_none()
    }
}

const WINDOW_MS: i64 = 35 * 24 * 60 * 60_000;
const WINDOW_COUNT: i64 = 4096;

fn end_of_day(date: Date, tz: &TimeZone) -> i64 {
    let next = date.checked_add(1.days()).expect("a day in range");
    next.to_zoned(tz.clone())
        .expect("a day in range")
        .timestamp()
        .as_millisecond()
        - 1
}

fn settled(reminder_id: &str, moment: i64, now: i64, instances: &[ReminderInstance]) -> bool {
    now - moment > GRACE_PERIOD_MS
        || instances.iter().any(|i| {
            i.reminder_id == reminder_id
                && i.scheduled_time == moment
                && matches!(
                    i.status,
                    InstanceStatus::Completed | InstanceStatus::Skipped | InstanceStatus::Missed
                )
        })
}

/// The moment a notice moves: the latest unsettled one on or before the notice's day, else the next one after it.
fn choose_source(
    reminder: &Reminder,
    pivot: i64,
    now: i64,
    instances: &[ReminderInstance],
    tz: &TimeZone,
) -> Option<i64> {
    let before: Vec<i64> = next_occurrences(reminder, pivot - WINDOW_MS, WINDOW_COUNT, tz)
        .unwrap_or_default()
        .into_iter()
        .map(|o| o.fire_at)
        .filter(|m| *m <= pivot)
        .filter(|m| !settled(&reminder.id, *m, now, instances))
        .collect();
    if let Some(last) = before.last() {
        return Some(*last);
    }
    next_occurrences(reminder, pivot, 1, tz)
        .ok()?
        .first()
        .map(|o| o.fire_at)
}

fn proposal_for(
    reminder: &Reminder,
    instances: &[ReminderInstance],
    now: i64,
    tz: &TimeZone,
    date: Date,
    time: Option<Time>,
    matched_text: &str,
) -> Proposal {
    let chosen = choose_source(reminder, end_of_day(date, tz), now, instances, tz);
    // The notice's time, else the moment's own, else 09:00.
    let at = time.or_else(|| {
        chosen.map(|c| {
            jiff::Timestamp::from_millisecond(c)
                .expect("a moment")
                .to_zoned(tz.clone())
                .time()
        })
    });
    let at = at.unwrap_or_else(|| Time::new(DEFAULT_NOTICE_HOUR, 0, 0, 0).expect("09:00"));
    let to = instant_at(
        date,
        at.hour(),
        at.minute(),
        at.second(),
        at.subsec_nanosecond() / 1_000_000,
        tz,
    );
    let refusal = match chosen {
        None => Some(ProposalRefusal::NoOccurrence),
        Some(_) if to <= now => Some(ProposalRefusal::Move(MoveRefusal::InThePast)),
        Some(from) => {
            let at_moment: Vec<ReminderInstance> = instances
                .iter()
                .filter(|i| i.reminder_id == reminder.id && i.scheduled_time == from)
                .cloned()
                .collect();
            move_refusal(reminder, from, to, now, &at_moment).map(ProposalRefusal::Move)
        }
    };
    Proposal {
        reminder_id: reminder.id.clone(),
        from: chosen,
        to,
        date,
        time,
        matched_text: matched_text.to_string(),
        refusal,
    }
}

/// The move each active task whose rules admit `notice` is offered, when the notice names a date.
pub fn proposals_for(
    notice: &Notice,
    reminders: &[Reminder],
    instances: &[ReminderInstance],
    now: i64,
    tz: &TimeZone,
) -> Vec<Proposal> {
    let ReadDate::Found {
        date,
        time,
        matched_text,
    } = read_notice_date(&notice_text(notice), now, tz)
    else {
        return Vec::new();
    };
    reminders
        .iter()
        .filter(|r| r.kind == ReminderKind::Task && r.active)
        .filter(|r| r.notice_rules.iter().any(|rule| rule_matches(rule, notice)))
        .map(|r| proposal_for(r, instances, now, tz, date, time, &matched_text))
        .collect()
}

/// A task's moments near `around` to choose from: up to `before` at or before it, `after` after.
pub fn occurrence_options_around(
    reminder: &Reminder,
    around: i64,
    tz: &TimeZone,
    before: usize,
    after: usize,
) -> Vec<i64> {
    if reminder.kind != ReminderKind::Task {
        return Vec::new();
    }
    let all: Vec<i64> = next_occurrences(reminder, around - WINDOW_MS, WINDOW_COUNT, tz)
        .unwrap_or_default()
        .into_iter()
        .map(|o| o.fire_at)
        .collect();
    let earlier: Vec<i64> = all.iter().copied().filter(|m| *m <= around).collect();
    let mut out: Vec<i64> = earlier[earlier.len().saturating_sub(before)..].to_vec();
    out.extend(all.iter().copied().filter(|m| *m > around).take(after));
    let mut seen = std::collections::HashSet::new();
    out.retain(|m| seen.insert(*m));
    out
}
