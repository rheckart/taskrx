//! Add and edit: the one sentence a person types, read into a draft. Kotlin: `core/parser`
//! (`Lexicon.kt`, `Patterns.kt`, `Extractors.kt`, `ReminderParser.kt`, `Draft.kt`).
//!
//! Tokens are runs of ASCII letters, digits, `.` and `:`, so every pattern below sees ASCII
//! only, and Java's ASCII `\d` and case folding agree with these.

use std::sync::LazyLock;

use regex::Regex;

use crate::domain::{DAYS_OF_MONTH, Priority, ReminderKind, ScheduleTimes};

// ---- the lexicon -----------------------------------------------------------------------------

const NOISE_WORDS: &[&str] = &[
    "the", "a", "an", "my", "your", "at", "on", "in", "with", "for", "take", "please",
];
const TASK_VERBS: &[&str] = &[
    "call", "pay", "buy", "schedule", "remind", "do", "pick", "take",
];
const TASK_NOUNS: &[&str] = &[
    "out",
    "up",
    "away",
    "down",
    "trash",
    "garbage",
    "recycling",
    "laundry",
    "dishes",
    "plants",
];
const NAME_PREFIX_PARTICLES: &[&str] = &["out", "up", "away", "down", "off", "back"];
const DRUG_SUFFIXES: &[&str] = &[
    "statin", "pril", "olol", "sartan", "prazole", "mide", "tidine", "azepam",
];
const DOSE_UNITS: &[&str] = &["pill", "pills", "tablet", "tablets", "capsule", "capsules"];

fn lower(token: &str) -> String {
    token.to_lowercase()
}

fn is_in(token: &str, set: &[&str]) -> bool {
    set.contains(&lower(token).as_str())
}

fn is_noise_word(t: &str) -> bool {
    is_in(t, NOISE_WORDS)
}
fn is_task_verb(t: &str) -> bool {
    is_in(t, TASK_VERBS)
}
fn is_task_signal(t: &str) -> bool {
    is_in(t, TASK_VERBS) || is_in(t, TASK_NOUNS)
}
fn is_name_prefix_particle(t: &str) -> bool {
    is_in(t, NAME_PREFIX_PARTICLES)
}
fn is_dose_unit(t: &str) -> bool {
    is_in(t, DOSE_UNITS)
}

fn lookup_named_time(t: &str) -> Option<&'static str> {
    Some(match lower(t).as_str() {
        "morning" => "08:00",
        "noon" | "lunch" => "12:00",
        "afternoon" => "14:00",
        "evening" | "dinner" => "18:00",
        "night" | "bedtime" => "21:00",
        "breakfast" => "07:00",
        _ => return None,
    })
}

fn lookup_number_word(t: &str) -> Option<i32> {
    Some(match lower(t).as_str() {
        "a" | "an" | "one" => 1,
        "two" => 2,
        "three" => 3,
        "four" => 4,
        "five" => 5,
        "six" => 6,
        "seven" => 7,
        "eight" => 8,
        "nine" => 9,
        "ten" => 10,
        "eleven" => 11,
        "twelve" => 12,
        "thirteen" => 13,
        "fourteen" => 14,
        "fifteen" => 15,
        "sixteen" => 16,
        "seventeen" => 17,
        "eighteen" => 18,
        "nineteen" => 19,
        "twenty" => 20,
        "thirty" => 30,
        "forty" => 40,
        "fifty" => 50,
        "sixty" => 60,
        "ninety" => 90,
        _ => return None,
    })
}

fn lookup_day_name(t: &str) -> Option<i32> {
    Some(match lower(t).as_str() {
        "monday" | "mon" => 1,
        "tuesday" | "tue" | "tues" => 2,
        "wednesday" | "wed" => 3,
        "thursday" | "thu" | "thur" | "thurs" => 4,
        "friday" | "fri" => 5,
        "saturday" | "sat" => 6,
        "sunday" | "sun" => 0,
        _ => return None,
    })
}

// ---- token patterns --------------------------------------------------------------------------

/// JavaScript's `String.prototype.trim` whitespace, which the original used.
fn is_js_whitespace(c: char) -> bool {
    matches!(
        c,
        '\t' | '\n'
            | '\u{000B}'
            | '\u{000C}'
            | '\r'
            | ' '
            | '\u{00A0}'
            | '\u{1680}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{202F}'
            | '\u{205F}'
            | '\u{3000}'
            | '\u{FEFF}'
    ) || ('\u{2000}'..='\u{200A}').contains(&c)
}

pub fn js_trim(value: &str) -> &str {
    value.trim_matches(is_js_whitespace)
}

static DOSAGE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"(?i)[0-9]+(\.[0-9]+)?[ \t\n\x0B\x0C\r]*(mg|mcg|g|ml|tablets?|capsules?|pills?|units?)",
    )
    .unwrap()
});
static CLOCK: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)^([0-9]{1,2})(?::([0-9]{2}))?(?::[0-9]{2})?(am|pm)?$").unwrap()
});
static FLOAT_TOKEN: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$").unwrap()
});
static ORDINAL_DAY: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^([0-9]{1,2})(?:st|nd|rd|th)$").unwrap());
static MONTH_DAY: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^([0-9]{1,2})(?:st|nd|rd|th)?$").unwrap());

/// Runs of ASCII letters, digits, `.` and `:`.
fn tokenize(input: &str) -> Vec<String> {
    let mut tokens = Vec::new();
    let mut current = String::new();
    for c in input.chars() {
        if c.is_ascii_alphanumeric() || c == '.' || c == ':' {
            current.push(c);
        } else if !current.is_empty() {
            tokens.push(std::mem::take(&mut current));
        }
    }
    if !current.is_empty() {
        tokens.push(current);
    }
    tokens
}

fn token_at(tokens: &[String], index: isize) -> Option<&str> {
    usize::try_from(index)
        .ok()
        .and_then(|i| tokens.get(i))
        .map(String::as_str)
}

fn eq(value: Option<&str>, lowercase_expected: &str) -> bool {
    value.is_some_and(|v| lower(v) == lowercase_expected)
}

fn parse_int_token(token: Option<&str>) -> Option<i32> {
    let t = token?;
    if t.is_empty() || !t.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    t.parse().ok()
}

fn parse_float_token(token: &str) -> Option<f64> {
    FLOAT_TOKEN
        .is_match(token)
        .then(|| token.parse().ok())
        .flatten()
}

fn parse_clock_time(token: &str) -> Option<String> {
    let caps = CLOCK.captures(token)?;
    let mut hour: i32 = caps[1].parse().ok()?;
    let minute_text = caps.get(2).map(|m| m.as_str());
    let designator = caps.get(3).map(|m| m.as_str().to_lowercase());
    let minute: i32 = minute_text.map_or(Some(0), |m| m.parse().ok())?;
    if minute > 59 {
        return None;
    }
    match designator {
        None => {
            if minute_text.is_none() || hour > 23 {
                return None;
            }
        }
        Some(d) => {
            if !(1..=12).contains(&hour) {
                return None;
            }
            let afternoon = d == "pm";
            if hour == 12 {
                hour = if afternoon { 12 } else { 0 };
            } else if afternoon {
                hour += 12;
            }
        }
    }
    Some(format!("{hour:02}:{minute:02}"))
}

fn looks_like_dosage(token: &str) -> bool {
    DOSAGE.is_match(token)
}

fn has_drug_suffix(tokens: &[String]) -> bool {
    tokens.iter().any(|t| {
        let l = lower(t);
        DRUG_SUFFIXES.iter().any(|s| l.ends_with(s))
    })
}

/// Hours for `count` units of `hours_per_unit`, wrapping on overflow as a Kotlin `Long` does
/// and then capped: a negative product reads as the largest interval.
fn interval_hours(count: i64, hours_per_unit: i64) -> Option<i32> {
    if count <= 0 {
        return None;
    }
    let hours = count.wrapping_mul(hours_per_unit);
    Some(if hours < 0 {
        i32::MAX
    } else {
        hours.min(i64::from(i32::MAX)) as i32
    })
}

fn unit_of(token: Option<&str>, forms: &[&str]) -> bool {
    token.is_some_and(|t| is_in(t, forms))
}

const MINUTES: &[&str] = &["minute", "minutes", "min", "mins"];
const HOURS: &[&str] = &["hour", "hours", "hr", "hrs"];
const DAYS: &[&str] = &["day", "days"];
const FORTNIGHTS: &[&str] = &["fortnight", "fortnights"];
const WEEKS: &[&str] = &["week", "weeks", "wk", "wks"];

fn try_parse_priority(token: &str, tokens: &[String], index: isize) -> Option<(Priority, isize)> {
    if eq(Some(token), "urgent") {
        return Some((Priority::Critical, 1));
    }
    if eq(Some(token), "important") {
        return Some((Priority::High, 1));
    }
    if eq(Some(token), "low") && eq(token_at(tokens, index + 1), "priority") {
        return Some((Priority::Low, 2));
    }
    None
}

fn try_parse_every(
    token: &str,
    tokens: &[String],
    index: isize,
    now: i64,
) -> Option<(ScheduleTimes, isize)> {
    let next = token_at(tokens, index + 1)?;
    if !eq(Some(token), "every") {
        return None;
    }
    let spelled = unit_of(Some(next), FORTNIGHTS).then_some(1);
    let count = spelled
        .or_else(|| parse_int_token(Some(next)))
        .or_else(|| lookup_number_word(next));
    if let Some(count) = count {
        let unit = if spelled.is_some() {
            Some(next)
        } else {
            token_at(tokens, index + 2)
        };
        let count = i64::from(count);
        let (counted, per) = if unit_of(unit, MINUTES) {
            ((count + 59) / 60, 1)
        } else if unit_of(unit, HOURS) {
            (count, 1)
        } else if unit_of(unit, DAYS) {
            (count, 24)
        } else if unit_of(unit, WEEKS) {
            (count, 168)
        } else if unit_of(unit, FORTNIGHTS) {
            (count, 336)
        } else {
            return None;
        };
        let hours = interval_hours(counted, per)?;
        return Some((
            ScheduleTimes::Interval {
                hours,
                start_at: now,
            },
            if spelled.is_some() { 2 } else { 3 },
        ));
    }
    if let Some(time) = lookup_named_time(next) {
        return Some((
            ScheduleTimes::Daily {
                times: vec![time.to_string()],
            },
            2,
        ));
    }
    if lookup_day_name(next).is_some() {
        return Some((
            ScheduleTimes::Weekly {
                days_of_week: vec![],
                times: vec![],
            },
            1,
        ));
    }
    None
}

fn try_parse_at_time(token: &str, tokens: &[String], index: isize) -> Option<(String, isize)> {
    let leading_at = eq(Some(token), "at");
    let candidate = if leading_at {
        token_at(tokens, index + 1)?
    } else {
        token
    };
    Some((parse_clock_time(candidate)?, if leading_at { 2 } else { 1 }))
}

fn try_parse_monthly(token: &str, tokens: &[String], index: isize) -> Option<isize> {
    if eq(Some(token), "monthly") {
        return Some(1);
    }
    if eq(Some(token), "month") && eq(token_at(tokens, index - 1), "every") {
        return Some(2);
    }
    None
}

fn day_in_range(day: i32) -> Option<i32> {
    DAYS_OF_MONTH.contains(&i64::from(day)).then_some(day)
}

fn try_parse_ordinal_day(token: &str) -> Option<i32> {
    day_in_range(ORDINAL_DAY.captures(token)?[1].parse().ok()?)
}

pub fn try_parse_month_day(token: &str) -> Option<i32> {
    day_in_range(MONTH_DAY.captures(token)?[1].parse().ok()?)
}

fn try_parse_bare_hour(token: Option<&str>) -> Option<String> {
    let hour = parse_int_token(token)?;
    (hour <= 23).then(|| format!("{hour:02}:00"))
}

fn try_parse_instructions(
    token: &str,
    tokens: &[String],
    index: isize,
) -> Option<(&'static str, isize)> {
    let next = token_at(tokens, index + 1)?;
    const PHRASES: &[(&str, &str, &str)] = &[
        ("with", "food", "with food"),
        ("with", "water", "with water"),
        ("without", "food", "without food"),
        ("after", "meals", "after meals"),
        ("before", "meals", "before meals"),
    ];
    PHRASES
        .iter()
        .find(|(a, b, _)| eq(Some(token), a) && eq(Some(next), b))
        .map(|(_, _, v)| (*v, 2))
}

fn try_parse_refill(token: &str, tokens: &[String], index: isize) -> Option<isize> {
    let next = token_at(tokens, index + 1);
    if parse_int_token(Some(token)).is_some()
        && let Some(n) = next
    {
        if is_dose_unit(n) {
            if eq(token_at(tokens, index + 2), "per") && eq(token_at(tokens, index + 3), "dose") {
                return None;
            }
            return Some(2);
        }
        if eq(Some(n), "per") && eq(token_at(tokens, index + 2), "dose") {
            return Some(3);
        }
    }
    if eq(Some(token), "per") && eq(next, "dose") {
        return Some(2);
    }
    if eq(Some(token), "refill")
        && eq(next, "in")
        && parse_int_token(token_at(tokens, index + 2)).is_some()
    {
        return Some(if eq(token_at(tokens, index + 3), "days") {
            4
        } else {
            3
        });
    }
    None
}

fn sorted_distinct<T: Ord + Clone>(items: impl IntoIterator<Item = T>) -> Vec<T> {
    let mut v: Vec<T> = items.into_iter().collect();
    v.sort();
    v.dedup();
    v
}

fn merge_time(schedule: Option<&ScheduleTimes>, time: &str) -> ScheduleTimes {
    let merged =
        |times: &[String]| sorted_distinct(times.iter().cloned().chain([time.to_string()]));
    match schedule {
        Some(ScheduleTimes::Daily { times }) => ScheduleTimes::Daily {
            times: merged(times),
        },
        Some(ScheduleTimes::Weekly {
            days_of_week,
            times,
        }) => ScheduleTimes::Weekly {
            days_of_week: days_of_week.clone(),
            times: merged(times),
        },
        _ => ScheduleTimes::Daily {
            times: vec![time.to_string()],
        },
    }
}

/// The least gap an as-needed medication gets when the phrase does not give one.
pub const DEFAULT_AS_NEEDED_GAP_MINUTES: i32 = 4 * 60;

fn try_parse_as_needed(token: &str, tokens: &[String], index: isize) -> Option<isize> {
    if eq(Some(token), "prn") {
        return Some(1);
    }
    let opener = eq(Some(token), "as") || eq(Some(token), "when") || eq(Some(token), "if");
    (opener && eq(token_at(tokens, index + 1), "needed")).then_some(2)
}

// ---- the extractors (read over the whole phrase) ---------------------------------------------

fn extract_pills_remaining(tokens: &[String]) -> Option<i32> {
    for (i, token) in tokens.iter().enumerate() {
        let i = i as isize;
        let (Some(count), Some(next)) = (parse_int_token(Some(token)), token_at(tokens, i + 1))
        else {
            continue;
        };
        if !is_dose_unit(next) {
            continue;
        }
        if eq(token_at(tokens, i + 2), "per") && eq(token_at(tokens, i + 3), "dose") {
            continue;
        }
        return Some(count);
    }
    None
}

fn extract_pills_per_dose(tokens: &[String]) -> Option<f64> {
    tokens.iter().enumerate().find_map(|(i, token)| {
        let i = i as isize;
        let amount = parse_float_token(token)?;
        (eq(token_at(tokens, i + 1), "per") && eq(token_at(tokens, i + 2), "dose"))
            .then_some(amount)
    })
}

fn extract_refill_days(tokens: &[String]) -> Option<i32> {
    tokens.iter().enumerate().find_map(|(i, token)| {
        let i = i as isize;
        if !eq(Some(token), "refill") || !eq(token_at(tokens, i + 1), "in") {
            return None;
        }
        parse_int_token(token_at(tokens, i + 2))
    })
}

// ---- the parser ------------------------------------------------------------------------------

struct State<'a> {
    tokens: &'a [String],
    kind: ReminderKind,
    now: i64,
    name: String,
    dosage: Option<String>,
    instructions: Option<String>,
    schedule: Option<ScheduleTimes>,
    priority: Priority,
    days: Vec<i32>,
    month_days: Vec<i32>,
    unparsed: Vec<String>,
    name_closed: bool,
    as_needed: bool,
    monthly_named: bool,
}

impl State<'_> {
    fn name_is_blank(&self) -> bool {
        self.name.trim().is_empty()
    }

    fn close_name_if_set(&mut self) {
        if !self.name_is_blank() {
            self.name_closed = true;
        }
    }

    fn append_name(&mut self, token: &str) {
        self.name = if self.name_is_blank() {
            token.to_string()
        } else {
            format!("{} {token}", self.name)
        };
    }
}

fn detect_kind(first: &str, tokens: &[String]) -> ReminderKind {
    if tokens.iter().any(|t| looks_like_dosage(t)) || has_drug_suffix(tokens) {
        return ReminderKind::Medication;
    }
    if is_task_verb(first) || tokens.iter().any(|t| is_task_signal(t)) {
        return ReminderKind::Task;
    }
    if tokens
        .iter()
        .enumerate()
        .any(|(i, t)| try_parse_as_needed(t, tokens, i as isize).is_some())
    {
        return ReminderKind::Medication;
    }
    let meaningful = tokens.iter().filter(|t| !is_noise_word(t)).count();
    if meaningful <= 3 {
        ReminderKind::Task
    } else {
        ReminderKind::Medication
    }
}

/// "pick up", "take out the": a task's verb and its particle open the name.
fn consume_task_name_prefix(state: &mut State, token: &str, index: isize) -> isize {
    if state.kind != ReminderKind::Task || !state.name_is_blank() || !is_task_verb(token) {
        return 0;
    }
    state.append_name(token);
    let mut next = index + 1;
    loop {
        let Some(candidate) = token_at(state.tokens, next) else {
            return next - index;
        };
        if is_name_prefix_particle(candidate) {
            state.append_name(candidate);
            return next - index + 1;
        } else if is_noise_word(candidate) {
            next += 1;
        } else {
            return next - index;
        }
    }
}

fn merge_monthly_date(state: &mut State, times: &[String], token: &str) -> bool {
    let day = try_parse_ordinal_day(token).or_else(|| {
        if state.month_days.is_empty() {
            try_parse_month_day(token)
        } else {
            None
        }
    });
    let Some(day) = day else { return false };
    state.month_days.push(day);
    state.schedule = Some(ScheduleTimes::Monthly {
        days_of_month: sorted_distinct(state.month_days.clone()),
        times: times.to_vec(),
    });
    true
}

fn merge_monthly(
    state: &mut State,
    days: &[i32],
    times: &[String],
    token: &str,
    index: isize,
) -> Option<isize> {
    if merge_monthly_date(state, times, token) {
        return Some(1);
    }
    let hour = if eq(Some(token), "at") {
        try_parse_bare_hour(token_at(state.tokens, index + 1))
    } else {
        None
    };
    let (time, consumed) = try_parse_at_time(token, state.tokens, index)
        .or_else(|| lookup_named_time(token).map(|t| (t.to_string(), 1)))
        .or_else(|| hour.map(|h| (h, 2)))?;
    state.schedule = Some(ScheduleTimes::Monthly {
        days_of_month: days.to_vec(),
        times: sorted_distinct(times.iter().cloned().chain([time])),
    });
    state.close_name_if_set();
    Some(consumed)
}

fn consume_token(state: &mut State, token: &str, index: isize) -> isize {
    let tokens = state.tokens;
    if looks_like_dosage(token) {
        state.dosage = Some(token.to_string());
        state.close_name_if_set();
        return 1;
    }
    if let Some((priority, consumed)) = try_parse_priority(token, tokens, index) {
        state.priority = priority;
        state.close_name_if_set();
        return consumed;
    }
    if let Some(consumed) = try_parse_as_needed(token, tokens, index) {
        state.as_needed = true;
        state.close_name_if_set();
        return consumed;
    }
    if let Some(consumed) = try_parse_monthly(token, tokens, index) {
        state.schedule = Some(ScheduleTimes::Monthly {
            days_of_month: vec![],
            times: vec![],
        });
        state.monthly_named = true;
        state.close_name_if_set();
        return consumed;
    }
    if state.monthly_named
        && let Some(ScheduleTimes::Monthly {
            days_of_month,
            times,
        }) = state.schedule.clone()
        && let Some(consumed) = merge_monthly(state, &days_of_month, &times, token, index)
    {
        return consumed;
    }
    if let Some((schedule, consumed)) = try_parse_every(token, tokens, index, state.now) {
        state.schedule = Some(schedule);
        state.close_name_if_set();
        return consumed;
    }
    if eq(Some(token), "daily") {
        if state.schedule.is_none() {
            state.schedule = Some(ScheduleTimes::Daily {
                times: vec!["09:00".into()],
            });
        }
        state.close_name_if_set();
        return 1;
    }
    if eq(Some(token), "twice") {
        state.schedule = Some(ScheduleTimes::Daily {
            times: vec!["08:00".into(), "18:00".into()],
        });
        state.close_name_if_set();
        return 1;
    }
    if let Some(time) = lookup_named_time(token) {
        state.schedule = Some(merge_time(state.schedule.as_ref(), time));
        state.close_name_if_set();
        return 1;
    }
    if let Some((time, consumed)) = try_parse_at_time(token, tokens, index) {
        state.schedule = Some(merge_time(state.schedule.as_ref(), &time));
        state.close_name_if_set();
        return consumed;
    }
    if let Some(day) = lookup_day_name(token) {
        state.days.push(day);
        if state.schedule.is_none() {
            state.schedule = Some(ScheduleTimes::Weekly {
                days_of_week: state.days.clone(),
                times: vec![],
            });
        }
        state.close_name_if_set();
        return 1;
    }
    if eq(Some(token), "weekly") {
        if state.schedule.is_none() {
            state.schedule = Some(ScheduleTimes::Weekly {
                days_of_week: state.days.clone(),
                times: vec![],
            });
        }
        state.close_name_if_set();
        return 1;
    }
    if let Some((instructions, consumed)) = try_parse_instructions(token, tokens, index) {
        state.instructions = Some(instructions.to_string());
        state.close_name_if_set();
        return consumed;
    }
    if let Some(consumed) = try_parse_refill(token, tokens, index) {
        state.close_name_if_set();
        return consumed;
    }
    if eq(Some(token), "every") {
        state.close_name_if_set();
        return 1;
    }
    let prefix = consume_task_name_prefix(state, token, index);
    if prefix > 0 {
        return prefix;
    }
    if is_noise_word(token) {
        return 1;
    }
    // A task noun ("trash", "plants") is the name of a task that has none yet.
    if state.kind == ReminderKind::Task
        && state.name_is_blank()
        && is_task_signal(token)
        && !is_task_verb(token)
    {
        state.append_name(token);
        return 1;
    }
    if state.name_closed {
        state.unparsed.push(token.to_string());
        return 1;
    }
    state.append_name(token);
    1
}

/// A sentence read into a draft.
#[derive(Debug, Clone, PartialEq)]
pub struct ReminderDraft {
    pub kind: ReminderKind,
    pub name: String,
    pub schedule: ScheduleTimes,
    pub priority: Priority,
    pub dosage: Option<String>,
    pub instructions: Option<String>,
    pub pills_remaining: Option<f64>,
    pub pills_per_dose: Option<f64>,
    pub refill_reminder_days: Option<i32>,
    pub rxcui: Option<String>,
    pub unparsed_tokens: Vec<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PhraseReading {
    pub understood: bool,
    pub draft: ReminderDraft,
    /// Shown to the person, so their wording is part of the contract.
    pub reasons: Vec<String>,
}

const EMPTY_INPUT_ERROR: &str = "Input is empty.";
const NO_NAME_ERROR: &str = "Could not determine a name for the reminder.";

fn read_with_parser(phrase: &str, now: i64) -> PhraseReading {
    let tokens = tokenize(phrase);
    let not_understood = |unparsed: Vec<String>, reason: &str| PhraseReading {
        understood: false,
        draft: ReminderDraft {
            kind: ReminderKind::Task,
            name: js_trim(phrase).to_string(),
            schedule: ScheduleTimes::None,
            priority: Priority::Normal,
            dosage: None,
            instructions: None,
            pills_remaining: None,
            pills_per_dose: None,
            refill_reminder_days: None,
            rxcui: None,
            unparsed_tokens: unparsed,
        },
        reasons: vec![reason.to_string()],
    };
    let Some(first) = tokens.first() else {
        return not_understood(Vec::new(), EMPTY_INPUT_ERROR);
    };

    let mut state = State {
        tokens: &tokens,
        kind: detect_kind(first, &tokens),
        now,
        name: String::new(),
        dosage: None,
        instructions: None,
        schedule: None,
        priority: Priority::Normal,
        days: Vec::new(),
        month_days: Vec::new(),
        unparsed: Vec::new(),
        name_closed: false,
        as_needed: false,
        monthly_named: false,
    };
    let mut next = 0isize;
    for (i, token) in tokens.iter().enumerate() {
        let i = i as isize;
        if i < next {
            continue;
        }
        next = i + consume_token(&mut state, token, i);
    }
    if let Some(ScheduleTimes::Weekly { times, .. }) = &state.schedule {
        state.schedule = Some(ScheduleTimes::Weekly {
            days_of_week: sorted_distinct(state.days.clone()),
            times: times.clone(),
        });
    }
    if state.monthly_named {
        let times = match &state.schedule {
            Some(ScheduleTimes::Monthly { times, .. }) => times.clone(),
            _ => Vec::new(),
        };
        state.schedule = Some(ScheduleTimes::Monthly {
            days_of_month: sorted_distinct(state.month_days.clone()),
            times,
        });
    }
    if state.as_needed {
        let gap = match &state.schedule {
            Some(ScheduleTimes::Interval { hours, .. }) => hours.wrapping_mul(60),
            _ => DEFAULT_AS_NEEDED_GAP_MINUTES,
        };
        state.schedule = Some(if state.kind == ReminderKind::Task {
            ScheduleTimes::None
        } else {
            ScheduleTimes::AsNeeded {
                min_gap_minutes: gap,
                max_per_24h: None,
            }
        });
    }

    if state.name_is_blank() {
        return not_understood(state.unparsed, NO_NAME_ERROR);
    }
    PhraseReading {
        understood: true,
        draft: ReminderDraft {
            kind: state.kind,
            name: state.name.trim().to_string(),
            schedule: state.schedule.unwrap_or(ScheduleTimes::Daily {
                times: vec!["09:00".into()],
            }),
            priority: state.priority,
            dosage: state.dosage,
            instructions: state.instructions,
            pills_remaining: extract_pills_remaining(&tokens).map(f64::from),
            pills_per_dose: extract_pills_per_dose(&tokens),
            refill_reminder_days: extract_refill_days(&tokens),
            rxcui: None,
            unparsed_tokens: state.unparsed,
        },
        reasons: Vec::new(),
    }
}

/// A monthly reading's dates and times, read again from the phrase from its first date on,
/// so a bare "1" or "15" counts as a date there.
fn with_monthly_dates(phrase: &str, draft: ReminderDraft) -> ReminderDraft {
    let ScheduleTimes::Monthly { times, .. } = &draft.schedule else {
        return draft;
    };
    let tokens = tokenize(phrase);
    let from = tokens
        .iter()
        .position(|t| try_parse_month_day(t).is_some())
        .unwrap_or(0);
    let after = &tokens[from..];
    let dates = sorted_distinct(after.iter().filter_map(|t| try_parse_month_day(t)));
    let times = sorted_distinct(times.iter().cloned().chain(after.iter().filter_map(|t| {
        if eq(Some(t), "at") {
            None
        } else {
            parse_clock_time(t)
        }
    })));
    ReminderDraft {
        schedule: ScheduleTimes::Monthly {
            days_of_month: dates,
            times,
        },
        unparsed_tokens: Vec::new(),
        ..draft
    }
}

/// The one sentence a person types, read into a draft.
pub fn read_phrase(phrase: &str, now: i64) -> PhraseReading {
    let reading = read_with_parser(phrase, now);
    PhraseReading {
        draft: with_monthly_dates(phrase, reading.draft),
        ..reading
    }
}

/// The draft as the other Kind. A task keeps no dose, supply or RxNorm id, and cannot be taken as needed.
pub fn with_kind(draft: &ReminderDraft, kind: ReminderKind) -> ReminderDraft {
    if kind == ReminderKind::Medication {
        return ReminderDraft {
            kind,
            ..draft.clone()
        };
    }
    ReminderDraft {
        kind,
        schedule: if matches!(draft.schedule, ScheduleTimes::AsNeeded { .. }) {
            ScheduleTimes::None
        } else {
            draft.schedule.clone()
        },
        dosage: None,
        pills_remaining: None,
        pills_per_dose: None,
        refill_reminder_days: None,
        rxcui: None,
        ..draft.clone()
    }
}

/// The draft renamed; the RxNorm id belonged to the old name.
pub fn with_name(draft: &ReminderDraft, name: &str) -> ReminderDraft {
    ReminderDraft {
        name: name.to_string(),
        rxcui: None,
        ..draft.clone()
    }
}

pub fn is_savable_draft(draft: &ReminderDraft) -> bool {
    !js_trim(&draft.name).is_empty() && is_well_formed_schedule(&draft.schedule)
}

/// The span `Date` can represent, in ms either side of the epoch.
const MAX_MOMENT_MS: i64 = 8_640_000_000_000_000;

fn is_moment(ms: i64) -> bool {
    (-MAX_MOMENT_MS..=MAX_MOMENT_MS).contains(&ms)
}

static TIME_ONLY: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^([0-9]{1,2}):([0-9]{2})(?::[0-9]{2}(?:\.[0-9]+)?)?$").unwrap());

fn is_usable_time(time: &str) -> bool {
    TIME_ONLY.captures(time).is_some_and(|c| {
        c[1].parse::<u32>().is_ok_and(|h| h <= 23) && c[2].parse::<u32>().is_ok_and(|m| m <= 59)
    })
}

/// Whether a schedule carries what its own variant needs to produce Occurrences. Kotlin:
/// `isWellFormedSchedule` in `core/domain/ScheduleTimes.kt`.
pub fn is_well_formed_schedule(schedule: &ScheduleTimes) -> bool {
    match schedule {
        ScheduleTimes::Daily { times } => times.iter().any(|t| is_usable_time(t)),
        ScheduleTimes::Weekly {
            days_of_week,
            times,
        } => !days_of_week.is_empty() && times.iter().any(|t| is_usable_time(t)),
        ScheduleTimes::Monthly {
            days_of_month,
            times,
        } => !days_of_month.is_empty() && times.iter().any(|t| is_usable_time(t)),
        ScheduleTimes::Interval { hours, start_at } => *hours > 0 && is_moment(*start_at),
        ScheduleTimes::Once { at } => is_moment(*at),
        ScheduleTimes::None => true,
        ScheduleTimes::AsNeeded {
            min_gap_minutes,
            max_per_24h,
        } => *min_gap_minutes > 0 && max_per_24h.is_none_or(|m| m > 0),
    }
}
