//! The gate on the port (ADR 0017): every case in `apps/android/core/golden` reproduced exactly.
//!
//! The files are read in place, never copied. A file with no handler fails, and so does a
//! `call` a handler does not know, unless the file is on [`PENDING`]: the rules not ported
//! yet. The list only shrinks; a file that is handled must leave it.

#[path = "golden/docs.rs"]
mod docs;
#[path = "golden/local_day.rs"]
mod local_day;
#[path = "golden/schedule.rs"]
mod schedule;

use std::collections::BTreeMap;
use std::path::PathBuf;

use serde::Deserialize;
use serde_json::Value;

/// The fixture files whose rules are not ported yet.
const PENDING: &[&str] = &[
    "answer", "backlog", "catch-up", "domain", "notices", "parser", "push", "refill", "schedule",
    "today",
];

/// One rule set's file. The envelope is strict; the documents inside are not.
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct GoldenFile {
    rule: String,
    #[allow(dead_code)]
    about: String,
    #[serde(rename = "generatedBy")]
    #[allow(dead_code)]
    generated_by: String,
    cases: Vec<Case>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Case {
    name: String,
    input: Value,
    output: Value,
}

pub type Outcome = Result<Value, String>;
type Handler = fn(&Value) -> Outcome;

fn handlers() -> BTreeMap<&'static str, Handler> {
    BTreeMap::from([
        ("instance-id", schedule::instance_id as Handler),
        ("local-day", local_day::case as Handler),
        ("server-schedule", schedule::server_schedule as Handler),
        ("server-docs", docs::case as Handler),
    ])
}

fn golden_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../android/core/golden")
}

/// Equal as JSON, numbers by value: the fixtures spell a whole number `1780000000000` in one
/// file and `1780000000000.0` in another, and Convex sends both as the same float64.
fn same(a: &Value, b: &Value) -> bool {
    match (a, b) {
        (Value::Number(x), Value::Number(y)) => x.as_f64() == y.as_f64(),
        (Value::Array(x), Value::Array(y)) => {
            x.len() == y.len() && x.iter().zip(y).all(|(p, q)| same(p, q))
        }
        (Value::Object(x), Value::Object(y)) => {
            x.len() == y.len() && x.iter().all(|(k, v)| y.get(k).is_some_and(|w| same(v, w)))
        }
        _ => a == b,
    }
}

#[test]
fn every_golden_case_is_reproduced() {
    let dir = golden_dir();
    let handlers = handlers();
    let mut files: Vec<PathBuf> = std::fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("cannot read {}: {e}", dir.display()))
        .map(|entry| entry.unwrap().path())
        .filter(|p| p.extension().is_some_and(|x| x == "json"))
        .collect();
    files.sort();

    let mut problems = Vec::new();
    let mut checked = 0;
    let names: Vec<String> = files
        .iter()
        .map(|p| p.file_stem().unwrap().to_string_lossy().into_owned())
        .collect();
    for pending in PENDING {
        if handlers.contains_key(pending) {
            problems.push(format!("{pending} is handled, so it must leave PENDING"));
        }
        if !names.iter().any(|n| n == pending) {
            problems.push(format!("{pending} is PENDING but has no file"));
        }
    }

    for (path, name) in files.iter().zip(&names) {
        let file: GoldenFile = serde_json::from_str(&std::fs::read_to_string(path).unwrap())
            .unwrap_or_else(|e| panic!("{name}.json does not match the fixture envelope: {e}"));
        assert_eq!(&file.rule, name, "{name}.json names another rule");
        let Some(handler) = handlers.get(name.as_str()) else {
            if !PENDING.contains(&name.as_str()) {
                problems.push(format!("{name}.json has no handler and is not PENDING"));
            }
            continue;
        };
        for case in &file.cases {
            checked += 1;
            match handler(&case.input) {
                Ok(got) if same(&got, &case.output) => {}
                Ok(got) => problems.push(format!(
                    "{name}: {}\n    expected {}\n    got      {}",
                    case.name, case.output, got
                )),
                Err(e) => problems.push(format!("{name}: {}: {e}", case.name)),
            }
        }
    }

    assert!(
        problems.is_empty(),
        "{} problem(s) over {checked} case(s):\n  {}",
        problems.len(),
        problems.join("\n  ")
    );
    eprintln!(
        "{checked} golden cases reproduced; {} file(s) pending",
        PENDING.len()
    );
}

// ---- input readers shared by the handlers --------------------------------------------------

pub fn field<'a>(input: &'a Value, key: &str) -> Result<&'a Value, String> {
    input.get(key).ok_or_else(|| format!("input has no {key}"))
}

pub fn int(input: &Value, key: &str) -> Result<i64, String> {
    let n = field(input, key)?
        .as_f64()
        .ok_or_else(|| format!("{key} is not a number"))?;
    if n % 1.0 == 0.0 {
        Ok(n as i64)
    } else {
        Err(format!("{key} is not whole"))
    }
}

pub fn text<'a>(input: &'a Value, key: &str) -> Result<&'a str, String> {
    field(input, key)?
        .as_str()
        .ok_or_else(|| format!("{key} is not a string"))
}

pub fn zone(input: &Value) -> Result<jiff::tz::TimeZone, String> {
    let name = text(input, "zone")?;
    taskrx_desktop_core::time::zone(name).ok_or_else(|| format!("unknown zone {name}"))
}

pub fn unknown_call(input: &Value) -> Outcome {
    Err(format!(
        "no handler for call {}",
        input.get("call").unwrap_or(&Value::Null)
    ))
}
