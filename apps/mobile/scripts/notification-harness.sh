#!/usr/bin/env bash
#
# notification-harness.sh — adb-driven notification test harness.
#
# Codifies as an executable script the manual test cases that were previously
# only prose in docs/poc-notification-tests.md and docs/poc-stress-tests.md —
# which never actually ran, leaving 12 of 13 POC criteria pending. Every case
# is verified from OS state (dumpsys alarm, dumpsys notification, logcat)
# rather than by eyeballing the device.
#
# ── EMPIRICALLY-VALIDATED CONSTRAINTS ──────────────────────────────────────
# Learned by running against a Pixel 8 Pro on Android 17 (API 37). Nine
# constraints: 1-7 below, then 8 (deep Doze is blocked by a pending
# wake-from-idle alarm) and 9 (`dumpsys alarm` is mostly NOT pending alarms),
# which are documented inline at their point of use.
# Do not "simplify" these away; each one caused a real false result.
#
#  1. DEEP LINKS MUST CONTAIN A REAL PATH, NOT JUST A HOST.
#     taskrx://harness?action=schedule does NOT route: with two slashes,
#     "harness" parses as the URL *host* and the path is empty, so
#     expo-router has nothing to match.
#     Both of these DO route (three slashes = real path):
#       taskrx:///harness/schedule/x/60000/nonce
#       taskrx:///harness/pending-count/x/0/nonce?extra=1
#     Query strings are fine once a path is present. This script uses path
#     segments purely because they are simpler to build and read.
#     (Earlier debugging wrongly blamed query strings; the real cause of
#     those failures was a LogBox overlay blocking navigation — see 5.)
#
#  2. USE `am kill`, NEVER `am force-stop`, TO MODEL "APP CLOSED".
#     force-stop sets the package's stopped=true flag, which makes Android
#     drop broadcasts to its receivers. AlarmManager still fires but
#     NotificationAlarmReceiver never receives it — a FALSE NEGATIVE.
#     Measured: with force-stop the notification never posted; with `am kill`
#     after backgrounding it posted 34ms after the target time.
#     `am kill` only affects backgrounded processes, hence the HOME keyevent.
#
#  3. THE HARNESS ROUTE ONLY RE-DISPATCHES WHEN ITS PARAMS CHANGE.
#     Re-navigating to an already-mounted route does not remount it, so every
#     invocation must carry a unique nonce or it is a silent no-op.
#
#  4. EXACT ALARMS REQUIRE SCHEDULE_EXACT_ALARM, NOT GRANTED BY DEFAULT on
#     Android 14+. Without it the app correctly degrades to an inexact alarm
#     (observed window=+44s969ms) instead of dropping it. Tests asserting
#     exact timing must grant it first via appops.
#
#  5. DEEP DOZE NEEDS SCREEN-OFF, WHICH LOCKS THE DEVICE, WHICH BLOCKS
#     RELAUNCHING THE APP. Schedule before turning the screen off, and expect
#     to need a manual unlock afterwards. Case 3 is therefore best-effort.
#
#     GENERALISED: on a device with any screen lock set, `--all` CANNOT exit 0
#     in a single pass. Case 3 ends with the device asleep and case 5 ends
#     mid-reboot; both leave the keyguard up, and every other case needs the
#     app foregroundable. Whichever of 3/5 runs first locks the other out, and
#     the script cannot self-unlock — it does not know the credential. Run it
#     in two passes with a manual unlock between (see Usage). `require_reachable`
#     makes this legible: a locked-out case reports UNREACHABLE rather than
#     manufacturing a product FAIL.
#
#  6. A CRASHING ROUTE CAN BLOCK EVERYTHING. In a dev build, an unhandled
#     render error raises a full-screen LogBox overlay that swallows all
#     subsequent deep links, so every case silently reports nothing. If the
#     harness suddenly stops responding, check logcat for a render error
#     before suspecting the deep-link format.
#
#  7. ACTION_BOOT_COMPLETED IS WITHHELD UNTIL THE FIRST MANUAL UNLOCK. On a
#     device with any screen lock, user 0 boots to RUNNING_LOCKED: credential-
#     encrypted storage stays sealed and only LOCKED_BOOT_COMPLETED is sent.
#     `getprop sys.boot_completed` flips to 1 long before this, so timing a
#     boot-receiver check from that property measures nothing. Case 5 waits for
#     `dumpsys user` to report RUNNING_UNLOCKED and prompts for the unlock.
#     This is real product behaviour, not a test artifact: after a reboot, an
#     app's alarms genuinely do not re-arm until the user unlocks once.
#
# Requires: adb on PATH; one attached device; the app installed; Metro
# reachable. Use `adb reverse tcp:8081 tcp:8081` for USB — this also sidesteps
# LAN firewall and multi-subnet problems that break mDNS-discovered LAN IPs.
#
# Usage:
#   apps/mobile/scripts/notification-harness.sh --list
#   apps/mobile/scripts/notification-harness.sh --case 2
#   apps/mobile/scripts/notification-harness.sh --all --output report.json
#
# On a device with NO screen lock, `--all` is a single pass. With a screen lock
# set, use two passes and unlock the device before each (see constraint 5):
#   pass A: --case 1 --case 2 --case 4 --case 6 --case 7
#   pass B: --case 3            # ends asleep + locked
#   pass C: --case 5            # reboots; ends at the lock screen
# Passes B and C each need a fresh manual unlock beforehand. Their assertions
# do not need the app after their setup, so the lock afterwards is harmless.
#
# Env: TASKRX_PKG ANDROID_SERIAL METRO_URL VERBOSE=1 SKIP_DOZE=1 SKIP_REBOOT=1
#      BOOT_UNLOCK_TIMEOUT_S (default 300) BOOT_REGISTER_TIMEOUT_S (default 300)
#      BOOT_ALARM_COUNT (default 3)
#
# Case 5 is INTERACTIVE on a device with a screen lock: it reboots, then waits
# for you to unlock before it can observe anything (constraint 7).
#
# The JSON report records a `provenance` block (commit, branch, dirty,
# dirty_files) so a result can always be tied back to the code it tested. The
# commit is the anchor rather than the APK because the debug build is an Expo
# dev client that loads its JS from Metro — the behaviour under test is the
# working tree. Treat a run with `"dirty": true` as evidence for that tree, not
# for the named commit.
#
# Exit: 0 all passed | 1 a case failed | 2 precondition failure

set -uo pipefail

PKG="${TASKRX_PKG:-com.taskrx.mobile}"
METRO_URL="${METRO_URL:-http://localhost:8081}"
APP_READY_TIMEOUT_S=180
HARNESS_SETTLE_S=8
# Case 5 only. How long to wait for a human to unlock the device after the
# reboot (ACTION_BOOT_COMPLETED is withheld until then — see constraint 7),
# and how long to then wait for the boot receiver to re-arm the alarm.
BOOT_UNLOCK_TIMEOUT_S="${BOOT_UNLOCK_TIMEOUT_S:-300}"
BOOT_REGISTER_TIMEOUT_S="${BOOT_REGISTER_TIMEOUT_S:-300}"
# Case 5 only. How many alarms to arm before the reboot. Must be >1 or a
# partial restore cannot be detected — see the comment in case5_boot_reschedule.
BOOT_ALARM_COUNT="${BOOT_ALARM_COUNT:-3}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../../.." && pwd)"
default_output="$repo_root/.omo/evidence/notification-harness-$(date -u +%Y%m%dT%H%M%SZ).json"

adb_args=()
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  adb_args+=(-s "$ANDROID_SERIAL")
fi

adb_run() {
  if [[ "${VERBOSE:-0}" == "1" ]]; then printf '[adb] %s\n' "$*" >&2; fi
  adb "${adb_args[@]}" "$@"
}

# ── Provenance (which code was this run taken against?) ────────────────────
#
# A result is only evidence if you can say what it tested. The report used to
# record the device and the wall-clock time but nothing about the code, so a
# JSON artifact could not be tied back to a revision — which is exactly how
# `docs/poc-results.md` ended up with device verdicts nobody could map onto a
# commit.
#
# The commit is the right anchor rather than the APK: the debug build is an
# Expo dev client that loads its JS from Metro at runtime, so the behaviour
# under test is the WORKING TREE, not the installed binary. That also makes
# `dirty` load-bearing — against a dirty tree the commit alone does not
# identify what ran, so name the modified files too.
git_in_repo() { git -C "$repo_root" rev-parse --git-dir >/dev/null 2>&1; }

# The `git_in_repo` guard plus the `${out:-unknown}` default are what make this
# safe: the `| tr` pipeline would otherwise mask git's exit status, since tr
# succeeds even when git does not.
provenance_rev() {
  local out
  git_in_repo || { printf 'unknown'; return; }
  out="$(git -C "$repo_root" rev-parse "$@" 2>/dev/null | tr -d '\r')"
  printf '%s' "${out:-unknown}"
}
provenance_commit() { provenance_rev HEAD; }
provenance_branch() { provenance_rev --abbrev-ref HEAD; }

# Modified tracked files AND untracked ones. Untracked matters: Metro bundles
# whatever is on disk, so a new-but-uncommitted source file is part of what ran
# even though `git diff HEAD` cannot see it. Ignored paths stay excluded — the
# evidence output itself lands in an ignored directory and would otherwise mark
# every run dirty. Bounded so a large diff cannot bloat the report.
provenance_dirty_files() {
  git_in_repo || { printf ''; return; }
  {
    git -C "$repo_root" diff --name-only HEAD 2>/dev/null
    git -C "$repo_root" ls-files --others --exclude-standard 2>/dev/null
  } | sort -u | head -40 | tr '\n' ' ' | sed 's/ *$//'
}

# git C-quotes paths containing non-ASCII or control characters ("src/café.ts"),
# so the raw value can carry a double quote straight into the JSON string and
# make the whole report unparseable. Escape backslash first, then quote.
json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
adb_shell() { adb_run shell "$@"; }

nonce_counter=0
next_nonce() { nonce_counter=$((nonce_counter + 1)); printf 'n%s' "$nonce_counter"; }

# ── Harness invocation (constraints 1 and 3) ───────────────────────────────

harness_once() {
  local action="$1" id="${2:-x}" delay="${3:-0}"
  local nonce
  nonce="$(next_nonce)"
  adb_run logcat -c >/dev/null 2>&1
  adb_shell am start -a android.intent.action.VIEW \
    -d "taskrx://harness/${action}/${id}/${delay}/${nonce}" >/dev/null 2>&1
  sleep "$HARNESS_SETTLE_S"
  adb_run logcat -d 2>/dev/null | grep -oE "\[harness\] result:.{0,120}" | tail -1
}

# Self-heal one level. Cases 1, 3 and 5 deliberately destroy the app process
# (am kill / reboot), so the FOLLOWING case issues its first deep link into a
# dead app: `am start` re-creates the task but the dev client has to reconnect
# to Metro and re-bundle first, which takes longer than HARNESS_SETTLE_S. The
# call then returns empty and the case reports a scheduler failure that never
# happened — measured as a bogus "case 2 exact_windows=0 expected_ts=" right
# after case 1's kill. An empty result means UNREACHABLE, not "false"; relaunch
# through the dev-client URL and ask exactly once more.
harness() {
  local out
  out="$(harness_once "$@")"
  if [[ -z "$out" ]]; then
    launch_app
    sleep "$HARNESS_SETTLE_S"
    out="$(harness_once "$@")"
  fi
  printf '%s\n' "$out"
}

# `adb reverse` is per-connection state and does NOT survive a reboot, so
# case 5 silently loses Metro and every later case fails its app-ready
# precondition for a reason that has nothing to do with the app. Re-assert it
# whenever we are about to need Metro. Only meaningful for a loopback
# METRO_URL; a LAN URL needs no reverse.
ensure_reverse() {
  case "$METRO_URL" in
    *localhost*|*127.0.0.1*)
      local port
      port="$(printf '%s' "$METRO_URL" | grep -oE ':[0-9]+' | tail -1 | tr -d ':')"
      port="${port:-8081}"
      adb_run reverse "tcp:$port" "tcp:$port" >/dev/null 2>&1 || true
      ;;
  esac
}

launch_app() {
  local encoded
  ensure_reverse
  # Wake first. Between cases the screen times out on its own, and `am start`
  # against a sleeping display does not reliably resume the activity — which
  # surfaces as a bogus "app never became ready" precondition failure rather
  # than as anything to do with the app.
  adb_shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  encoded="$(printf '%s' "$METRO_URL" | sed 's|:|%3A|g; s|/|%2F|g')"
  adb_shell am start -a android.intent.action.VIEW \
    -d "exp+taskrx://expo-development-client/?url=${encoded}" >/dev/null 2>&1
}

wait_for_app_ready() {
  local deadline=$(( $(date +%s) + APP_READY_TIMEOUT_S ))
  launch_app
  while (( $(date +%s) < deadline )); do
    local out
    out="$(harness pending-count x 0)"
    if [[ -n "$out" ]]; then printf '%s\n' "$out"; return 0; fi
  done
  return 1
}

# ── OS state helpers ───────────────────────────────────────────────────────

# ── Constraint 9: `dumpsys alarm` IS MOSTLY NOT PENDING ALARMS. ────────────
# It prints the live alarm store FIRST, then a run of history and statistics
# sections — "Removal history", "Allow while idle history", "Next wake from
# idle", the per-uid stats — and those ALSO carry `*walarm*:<pkg>` tags, for
# alarms that already fired or were cancelled. Grepping the whole dump mixes
# live alarms with dead ones.
#
# Measured on a Pixel 8 Pro / Android 17 with exactly 3 alarms armed: the
# unscoped grep counted 14 (3 pending + 1 "Next wake from idle" duplicate of a
# pending one + 10 removal-history entries), while the OS's own
# "Pending alarms per uid" line read `u0a221:3`.
#
# This is what made case 5 unfalsifiable, and it is not a small error. A reboot
# CLEARS the removal history, so `after` was structurally smaller than `before`
# however perfectly the boot receiver did its job — which is precisely the
# after<before pattern every archived case-5 artifact shows (14->2, 3->2,
# 3->2). The case was reading history depth across a reboot, not rescheduling.
#
# The rule this yields is narrow, so state it precisely rather than as "never
# grep the raw dump": reads of the ALARM STORE — counting alarms, reading their
# windows — must go through `pending_dump`. The summary lines that live OUTSIDE
# the store ("Next alarm clock information", "Pending alarms per uid") are not
# part of it, and `next_alarm_clock` and `alarm_count_per_uid` read them
# directly on purpose.
#
# The cut is a whitelist of the section headers that follow the store. If a
# future Android build renames or reorders one so the FIRST line matches, awk
# exits immediately and emits nothing — every alarm read silently becomes 0,
# reporting a product FAIL for an instrument fault. That is the same inversion
# this constraint exists to prevent, and it is the worse direction to fail in:
# the old unscoped grep over-counted loudly, this would under-count quietly.
# So emptiness is checked rather than trusted. An empty dump means the parse
# broke; it never means "no alarms".
#
# The load-bearing check is the PRECONDITION at startup, not this warning: a
# caller like `alarm_count` runs `pending_dump` inside a pipeline in a command
# substitution, where the exit status is grep's and an `exit` would only end
# the subshell — so a failure here cannot reliably abort the run from inside.
# The format is verified once before any case runs; this warning is the
# belt-and-braces signal if it somehow changes mid-run.
pending_dump() {
  local out
  out="$(adb_shell dumpsys alarm 2>/dev/null | awk '
    /^[[:space:]]*(LazyAlarmStore stats|Pending user blocked background alarms|Pending alarms per uid|Scheduled user wakeups|App Alarm history|Next wake from idle|Removal history|Allow while idle)/ { exit }
    { print }
  ')"
  if [[ -z "$out" ]]; then
    printf '[pending_dump] PARSE FAILED: the alarm-store section of `dumpsys alarm` came back empty. A section header in the cut whitelist has probably been renamed or reordered. Alarm counts from this run are not trustworthy.\n' >&2
    return 1
  fi
  printf '%s\n' "$out"
}

alarm_lines()      { pending_dump | grep -A3 "walarm\*:${PKG}" || true; }
alarm_count()      { pending_dump | grep -c "walarm\*:${PKG}" || true; }
alarm_is_exact()   { alarm_lines | grep -c "window=0" || true; }

# The OS's own tally. Read from a different line of the dump by a different
# parse, so when it agrees with `alarm_count` that is genuine corroboration
# rather than the same mistake made twice. Case 5 asserts the agreement,
# because `alarm_count` silently returning a wrong number is precisely the
# failure that made that case unfalsifiable for three runs.
alarm_count_per_uid() {
  local uid token line
  # `appId=`/`uid=`, not `userId=` — Android 17's dumpsys package does not emit
  # the latter, and grepping for it silently yielded an empty uid and a 0 count.
  uid="$(adb_shell dumpsys package "$PKG" 2>/dev/null | grep -oE '\b(appId|uid)=[0-9]+' | head -1 | cut -d= -f2 | tr -d '\r')"
  [[ -z "$uid" ]] && { printf '0'; return; }
  if (( uid >= 10000 )); then token="u0a$((uid - 10000))"; else token="$uid"; fi
  line="$(adb_shell dumpsys alarm 2>/dev/null | grep -m1 'Pending alarms per uid:' | tr -d '\r')"
  # Anchor on the delimiter so u0a221 cannot match inside u0a2210.
  printf '%s' "$line" | grep -oE "[][, ]${token}:[0-9]+" | head -1 | grep -oE '[0-9]+$' || printf '0'
}
next_alarm_clock() { adb_shell dumpsys alarm 2>/dev/null | grep -A1 "Next alarm clock information" | tail -1 | tr -d '\r'; }
notification_has() { adb_shell dumpsys notification --noredact 2>/dev/null | grep -c "$1" || true; }
grant_exact()      { adb_shell cmd appops set "$PKG" SCHEDULE_EXACT_ALARM allow >/dev/null 2>&1 || true; }
deny_exact()       { adb_shell cmd appops set "$PKG" SCHEDULE_EXACT_ALARM deny  >/dev/null 2>&1 || true; }
idle_state()       { adb_shell dumpsys deviceidle get deep 2>/dev/null | tail -1 | tr -d '\r'; }
screen_wakefulness() { adb_shell dumpsys power 2>/dev/null | grep -oE 'mWakefulness=[A-Za-z]+' | head -1 | cut -d= -f2 | tr -d '\r'; }
pkg_stopped()      { adb_shell dumpsys package "$PKG" 2>/dev/null | grep -oE "stopped=(true|false)" | head -1 | tr -d '\r'; }

# Constraint 2: background first, then `am kill`. NEVER force-stop.
kill_app_realistically() {
  adb_shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  sleep 2
  adb_shell am kill "$PKG" >/dev/null 2>&1
  sleep 2
}

restore_device() {
  adb_shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
  adb_shell dumpsys deviceidle disable >/dev/null 2>&1 || true
  adb_shell dumpsys battery reset      >/dev/null 2>&1 || true
  adb_shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  # Case 3 shrinks min_time_to_alarm to make deep Doze reachable. Always put
  # the device back on stock Doze constants — leaving a phone with a 30s
  # min_time_to_alarm would quietly change its real battery behaviour.
  adb_shell settings delete global device_idle_constants >/dev/null 2>&1 || true
}

# ── Results ────────────────────────────────────────────────────────────────

results=()
note() {
  if [[ "${VERBOSE:-0}" == "1" ]]; then printf '  [note] %s\n' "$*" >&2; fi
}

add_result() {
  local n="$1" name="$2" pass="$3" detail="$4"
  local d
  d="$(printf '%s' "$detail" | tr '\n' ' ' | sed 's/"/\\"/g')"
  results+=("{\"case\":${n},\"name\":\"${name}\",\"pass\":${pass},\"detail\":\"${d}\"}")
  local verdict=FAIL
  [[ "$pass" == true ]] && verdict=PASS
  printf '  [%s] case %s: %s — %s\n' "$verdict" "$n" "$name" "$d"
}

# An empty harness reply means the app could not be reached, which is NOT the
# same as the app answering "false". `harness()` already self-heals one level,
# but a case that reads an empty reply into its pass expression renders a
# product FAIL for an environmental fault — case 4 reported
# `status= window= posted=0` this way when the real cause was a secure
# keyguard. Every case whose verdict depends on a harness round-trip must call
# this first and bail on non-zero.
#
# The dominant cause is the lock screen: with a PIN/pattern set, `am start`
# cannot resume the activity while the keyguard is up, so no JS runs and no
# `[harness] result:` line is ever logged. Cases 3 and 5 end by putting the
# device to sleep, which re-arms it for everything that follows.
require_reachable() {
  local n="$1" name="$2"
  local probe
  probe="$(harness pending-count reachability 0)"
  if [[ -n "$probe" ]]; then
    return 0
  fi
  local keyguard wakefulness
  keyguard="$(adb_shell dumpsys window 2>/dev/null | grep -oE 'isKeyguardShowing=[a-z]+' | head -1 | tr -d '\r')"
  wakefulness="$(screen_wakefulness)"
  add_result "$n" "$name" false \
    "UNREACHABLE (not a product failure): the app never answered a deep link, so this case could not be evaluated. ${keyguard:-isKeyguardShowing=unknown} mWakefulness=${wakefulness:-unknown}. If the keyguard is showing, unlock the device and re-run this case."
  return 1
}

# ── Cases ──────────────────────────────────────────────────────────────────

case1_killed_app() {
  grant_exact
  harness cancel-all >/dev/null
  local sched exact procs stopped posted
  sched="$(harness schedule kill-test 60000)"
  exact="$(alarm_is_exact)"
  kill_app_realistically
  procs="$(adb_shell ps -A 2>/dev/null | grep -c "$PKG" || true)"
  stopped="$(pkg_stopped)"
  sleep 75
  posted="$(notification_has "kill-test")"
  if [[ "$posted" -ge 1 ]]; then
    add_result 1 killed_app_delivery true "posted after am kill; $sched; exact_windows=$exact; procs_after_kill=$procs; $stopped"
  else
    add_result 1 killed_app_delivery false "NOT posted; $sched; exact_windows=$exact; procs_after_kill=$procs; $stopped"
  fi
}

case2_alarm_clock_primitive() {
  grant_exact
  harness cancel-all >/dev/null
  local sched ts exact reason nac
  sched="$(harness schedule primitive-test 120000)"
  ts="$(printf '%s' "$sched" | grep -oE "at=[0-9]+" | cut -d= -f2)"
  exact="$(alarm_is_exact)"
  reason="$(alarm_lines | grep -oE "exactAllowReason=[a-z-]+" | head -1)"
  nac="$(next_alarm_clock)"
  # setAlarmClock registers the alarm as the device's "next alarm clock".
  # Matching timestamps proves the primitive is actually in use, rather than
  # merely requested in source.
  if [[ "$exact" -ge 1 ]] && [[ -n "$ts" ]] && printf '%s' "$nac" | grep -q "$ts"; then
    add_result 2 alarm_clock_primitive true "window=0 (x$exact), $reason, registered as next alarm clock: $nac"
  else
    add_result 2 alarm_clock_primitive false "exact_windows=$exact reason=$reason expected_ts=$ts next_alarm_clock=$nac"
  fi
}

case3_doze() {
  if [[ "${SKIP_DOZE:-0}" == "1" ]]; then
    add_result 3 doze_deep_idle true "skipped (SKIP_DOZE=1)"
    return
  fi
  grant_exact
  harness cancel-all >/dev/null
  local sched reached posted
  # ── Constraint 8: A PENDING WAKE-FROM-IDLE ALARM BLOCKS DEEP DOZE BY DESIGN.
  # DeviceIdleController.stepIdleStateLocked refuses to enter deep idle when
  # getNextWakeFromIdleTime() is within min_time_to_alarm, which defaults to
  # ONE HOUR. Measured: with the test alarm 150s out, both `step deep` and
  # `force-idle deep` stall, and force-idle reports
  #   "Unable to go deep idle; stopped at QUICK_DOZE_DELAY".
  # That refusal is itself evidence our alarm is classified wake-from-idle —
  # the OS will not Doze straight past a setAlarmClock alarm. But to observe
  # DELIVERY from inside deep Doze we must shrink the constant for the run,
  # then restore it. This is a test accommodation and is recorded as such in
  # the result detail; it changes when the OS is WILLING to Doze, never
  # whether the alarm fires.
  adb_shell settings put global device_idle_constants min_time_to_alarm=30000 >/dev/null 2>&1
  local mtta
  mtta="$(adb_shell dumpsys deviceidle 2>/dev/null | grep -oE 'min_time_to_alarm=[^ ]+' | head -1 | tr -d '\r')"
  # Alarm far enough out that we can reach idle and still sit in it: at the
  # moment we force idle the alarm must be MORE than min_time_to_alarm away.
  sched="$(harness schedule doze-test 240000)"
  local fire_at
  fire_at=$(( $(date +%s) + 240 ))
  adb_shell dumpsys deviceidle enable deep >/dev/null 2>&1
  adb_shell dumpsys battery unplug >/dev/null 2>&1
  kill_app_realistically
  # KEYCODE_SLEEP, never KEYCODE_POWER: POWER is a TOGGLE, so depending on the
  # screen state a prior case left behind it can switch the screen ON, pinning
  # the device at ACTIVE and making deep idle unreachable.
  adb_shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1
  # Confirm the screen is genuinely off before stepping. deviceidle will not
  # leave ACTIVE while the display is on, and a single blind keyevent is not
  # reliable if a prior case left the device awake.
  local awake_tries=0
  while (( awake_tries < 6 )); do
    [[ "$(screen_wakefulness)" != "Awake" ]] && break
    adb_shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1
    sleep 2
    awake_tries=$((awake_tries + 1))
  done
  # Track the DEEPEST state observed rather than sampling one point: after
  # screen-off the framework advances ACTIVE -> INACTIVE -> QUICK_DOZE_DELAY on
  # its own and then parks awaiting explicit steps, so a single read races those
  # transitions and can report a shallower state than was actually reached.
  # Keep this loop SHORT. It previously ran 25 x 2s and consumed most of the
  # window before the alarm fired, so the device was already awake servicing
  # the alarm by the time the force-idle fallback ran.
  local deepest cur
  deepest="$(idle_state)"
  for _ in $(seq 1 6); do
    cur="$(idle_state)"
    case "$cur" in
      IDLE|IDLE_MAINTENANCE) deepest=IDLE; break ;;
      SENSING|LOCATING|IDLE_PENDING|QUICK_DOZE_DELAY|INACTIVE) deepest="$cur" ;;
    esac
    adb_shell dumpsys deviceidle step deep >/dev/null 2>&1
    sleep 2
  done
  # Constraint 7: `step deep` CANNOT advance past QUICK_DOZE_DELAY. Once quick
  # doze is armed (screen off + unplugged), DeviceIdleController drives the
  # ACTIVE -> IDLE transition from a timer, and stepIdleStateLocked has no
  # handler for that state, so stepping silently no-ops forever. Measured on a
  # Pixel 8 Pro / Android 17: 25 steps left it parked at QUICK_DOZE_DELAY.
  # `force-idle deep` is the procedure in Android's own Doze testing docs and
  # DOES reach genuine deep idle. Record which mechanism got us there so the
  # evidence never overstates how the state was reached.
  local reach_method=stepped stalled_at force_out
  if [[ "$deepest" != "IDLE" ]]; then
    stalled_at="$deepest"
    # Capture stdout: force-idle reports WHY it could not reach idle
    # ("Unable to go deep idle; stopped at <state>"), and silently discarding
    # that turns a diagnosable refusal into an unexplained INCONCLUSIVE.
    force_out="$(adb_shell dumpsys deviceidle force-idle deep 2>&1 | tr -d '\r' | tr '\n' ' ')"
    sleep 3
    cur="$(idle_state)"
    if [[ "$cur" == "IDLE" || "$cur" == "IDLE_MAINTENANCE" ]]; then
      deepest=IDLE
      reach_method="force-idle deep (stepping stalled at $stalled_at)"
    else
      reach_method="force-idle REFUSED: '${force_out}' (stepping stalled at $stalled_at, state after=$cur)"
    fi
  fi
  reached="$deepest"
  # Hold until the alarm is due, sampling as we go. The LAST state observed
  # before the alarm fires is the meaningful one: it proves the device was
  # still genuinely dozing right up to the moment the alarm had to wake it.
  # Do NOT require idle to still hold AFTER delivery — a wake-from-idle alarm
  # is supposed to pull the device out of Doze, so demanding otherwise would
  # fail the test for behaving correctly.
  # Doze is a CYCLE, not a latch: the controller runs
  #   IDLE -> IDLE_MAINTENANCE -> QUICK_DOZE_DELAY -> IDLE -> ...
  # for as long as the screen stays off. Sampling one instant and demanding
  # it read IDLE fails the test whenever that instant lands in a maintenance
  # window — i.e. it fails for the OS behaving exactly as designed. What
  # actually matters is that the device was genuinely dozing for the whole
  # wait and never woke to ACTIVE before the alarm was due.
  local saw_deep_idle=0 woke_active=0 trail=""
  [[ "$reached" == "IDLE" ]] && saw_deep_idle=1
  while (( $(date +%s) < fire_at - 10 )); do
    cur="$(idle_state)"
    case "$cur" in
      IDLE|IDLE_MAINTENANCE) saw_deep_idle=1 ;;
      ACTIVE) woke_active=1 ;;
    esac
    [[ "$cur" != "${trail##*>}" ]] && trail="${trail:+$trail>}$cur"
    sleep 5
  done
  sleep 25
  posted="$(notification_has "doze-test")"
  local at_check
  at_check="$(idle_state)"
  restore_device
  # The case name claims deep idle, so passing REQUIRES deep idle: the device
  # must have BEEN in deep idle while the alarm was pending. Delivering at a
  # shallower state is a weaker result and must not be reported as a pass.
  if [[ "$posted" -ge 1 && "$saw_deep_idle" -eq 1 && "$woke_active" -eq 0 ]]; then
    add_result 3 doze_deep_idle true "posted after the device entered deep IDLE with the alarm pending and dozed continuously until it fired (entered via $reach_method; $mtta [lowered for the run, then restored]; doze cycle observed: $trail; never returned to ACTIVE before fire; state after delivery=$at_check); $sched"
  elif [[ "$posted" -ge 1 && "$saw_deep_idle" -eq 1 ]]; then
    add_result 3 doze_deep_idle false "INCONCLUSIVE: reached deep IDLE via $reach_method and delivered, but the device returned to ACTIVE before the alarm was due, so delivery cannot be attributed to Doze survival (cycle: $trail). $sched"
  elif [[ "$posted" -ge 1 ]]; then
    add_result 3 doze_deep_idle false "INCONCLUSIVE: delivered, but device only reached '$reached', not deep IDLE. $reach_method. $sched"
  else
    add_result 3 doze_deep_idle false "NOT posted; device state '$reached'; $sched. Screen-off locks the device (constraint 5) — unlock and re-run if the app could not be reached."
  fi
}

case4_exact_denied_degrades() {
  # Probe BEFORE deny_exact so that bailing out cannot leave the appop revoked.
  require_reachable 4 exact_denied_degrades || return
  deny_exact
  harness cancel-all >/dev/null
  local status sched window posted
  status="$(harness exact-alarm-status)"
  sched="$(harness schedule denied-test 45000)"
  window="$(alarm_lines | grep -oE "window=[+0-9a-z]+" | head -1)"
  # An INEXACT alarm may legitimately fire anywhere inside its window, so a
  # fixed sleep is a coin flip. Measured: 45s delay + window=+33s729ms means
  # delivery as late as ~79s, while the old `sleep 60` checked at ~68s and
  # reported "posted=0" for an alarm that had not yet come due — the app was
  # degrading correctly and still got marked FAIL. Poll past the END of the
  # window instead, and only conclude non-delivery once it has fully elapsed.
  local win_s deadline
  win_s="$(printf '%s' "$window" | grep -oE '[0-9]+s' | head -1 | tr -d 's')"
  win_s="${win_s:-60}"
  deadline=$(( $(date +%s) + 45 + win_s + 30 ))
  posted=0
  while (( $(date +%s) < deadline )); do
    posted="$(notification_has "denied-test")"
    [[ "$posted" -ge 1 ]] && break
    sleep 5
  done
  grant_exact
  # The contract is graceful degradation: report denied, downgrade to an
  # inexact alarm, but still deliver. Never silently drop.
  if [[ "$posted" -ge 1 ]] && printf '%s' "$status" | grep -q "exact_alarm_granted=false"; then
    add_result 4 exact_denied_degrades true "reported denied AND still delivered inexactly ($window, polled to end of window); $sched"
  else
    add_result 4 exact_denied_degrades false "status=$status window=$window posted=$posted (waited past end of the inexact window)"
  fi
}

case5_boot_reschedule() {
  if [[ "${SKIP_REBOOT:-0}" == "1" ]]; then
    add_result 5 boot_reschedule true "skipped (SKIP_REBOOT=1)"
    return
  fi
  # The pass expression reads `dumpsys alarm` only, but the SETUP below needs
  # the app: an unreachable app arms nothing, so `before` is 0, nothing can
  # survive the reboot, and the case reports "alarms NOT re-registered" for a
  # boot receiver that was never given anything to restore. Probe before the
  # reboot so an unreachable device costs no reboot cycle.
  require_reachable 5 boot_reschedule || return
  grant_exact
  harness cancel-all >/dev/null
  # Arm SEVERAL alarms, not one. The loss this case exists to detect is a
  # PARTIAL restore — "armed 3, re-registered 2" — and at before=1 that cannot
  # be expressed: 1 is the weakest value satisfying `after >= before`, so the
  # case would pass without ever having been able to fail that way.
  #
  # All of them are placed far beyond the reboot window (15m+), which also
  # removes the other half of the old ambiguity: an alarm that cannot come due
  # during the reboot cannot have "legitimately fired" during it, so a missing
  # alarm afterwards has only one explanation.
  local i
  for ((i = 0; i < BOOT_ALARM_COUNT; i++)); do
    harness schedule "boot-test-$i" $((900000 + i * 60000)) >/dev/null
  done
  local before after deadline before_uid
  before="$(alarm_count)"
  before_uid="$(alarm_count_per_uid)"
  if [[ "$before" -lt 1 ]]; then
    add_result 5 boot_reschedule false \
      "SETUP FAILED (not a product failure): no alarm was armed before the reboot (before=$before), so there was nothing for the boot receiver to restore. Not rebooting."
    return
  fi
  # Two independent parses of the same fact. If they disagree, the instrument
  # is wrong and no verdict from it should be believed — say so instead of
  # rebooting the device to measure with a broken ruler.
  if [[ "$before" != "$before_uid" ]]; then
    add_result 5 boot_reschedule false \
      "INSTRUMENT FAULT (not a product failure): the two independent alarm counts disagree before the reboot (alarm_count=$before, per-uid tally=$before_uid). One of the two parses of \`dumpsys alarm\` is wrong, so neither before nor after can be trusted. Not rebooting. This guard exists because an unscoped count silently made this case unfalsifiable for three runs."
    return
  fi
  adb_run reboot >/dev/null 2>&1
  sleep 10
  deadline=$(( $(date +%s) + 180 ))
  while (( $(date +%s) < deadline )); do
    [[ "$(adb_shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
    sleep 3
  done
  # `sys.boot_completed=1` is NOT the starting gun for ACTION_BOOT_COMPLETED.
  # On a device with any screen lock, user 0 comes up RUNNING_LOCKED: only
  # LOCKED_BOOT_COMPLETED is broadcast, credential-encrypted storage stays
  # sealed, and ACTION_BOOT_COMPLETED is withheld until the FIRST manual
  # unlock — which may never come while a script is watching. Timing the
  # registration poll from boot_completed therefore expired against a
  # broadcast Android was deliberately holding back, and reported "alarms NOT
  # re-registered" for a receiver that had not been called yet. Measured on a
  # Pixel 8 Pro / Android 17: BOOT_COMPLETED fanned out 0s after unlock and
  # RebootBroadcastReceiver started the app process ~52s later, restoring the
  # alarm exactly (window=0).
  #
  # Wait for the unlock first, and say so, rather than silently burning the
  # budget. This is a real user-visible property, not a test artifact: alarms
  # legitimately do not re-arm after a reboot until the user unlocks once.
  local unlock_deadline
  unlock_deadline=$(( $(date +%s) + BOOT_UNLOCK_TIMEOUT_S ))
  if ! adb_shell dumpsys user 2>/dev/null | grep -q RUNNING_UNLOCKED; then
    printf '  [note] device rebooted into the lock screen. ACTION_BOOT_COMPLETED is withheld until the first unlock — UNLOCK THE DEVICE NOW (waiting up to %ss).\n' "$BOOT_UNLOCK_TIMEOUT_S"
  fi
  while (( $(date +%s) < unlock_deadline )); do
    adb_shell dumpsys user 2>/dev/null | grep -q RUNNING_UNLOCKED && break
    sleep 5
  done
  if ! adb_shell dumpsys user 2>/dev/null | grep -q RUNNING_UNLOCKED; then
    ensure_reverse
    add_result 5 boot_reschedule false \
      "UNREACHABLE (not a product failure): the device rebooted into the lock screen and was never unlocked within ${BOOT_UNLOCK_TIMEOUT_S}s, so Android never broadcast ACTION_BOOT_COMPLETED (user 0 stayed RUNNING_LOCKED) and the boot receiver could not have run. Re-run and unlock when prompted."
    return
  fi
  # The reboot dropped the adb reverse tunnel with it. Restore it now, or every
  # subsequent case fails "app never became ready" for a Metro-connectivity
  # reason that looks like an app fault.
  ensure_reverse
  # POLL, do not sleep a fixed grace, and time the poll from the UNLOCK rather
  # than from boot: the framework fans BOOT_COMPLETED out to every registered
  # receiver in turn, and a phone with many installed apps takes a while to
  # reach ours. Measured on a Pixel 8 Pro / Android 17: BOOT_COMPLETED began
  # fanning out immediately on unlock and RebootBroadcastReceiver started our
  # process ~52s later. The previous fixed 20s grace, and then a 180s window
  # timed from boot_completed, both reported a FALSE NEGATIVE ("alarms NOT
  # re-registered") for a receiver that had not been called yet.
  local unlocked_at reg_deadline
  unlocked_at=$(date +%s)
  reg_deadline=$(( unlocked_at + BOOT_REGISTER_TIMEOUT_S ))
  after=0
  while (( $(date +%s) < reg_deadline )); do
    after="$(alarm_count)"
    # `before`, not 1, is the bar. Breaking on the first alarm to reappear
    # accepts a partial restore: a device that armed 3 and re-registered 2
    # stops polling here and gets recorded as a pass, which is precisely the
    # loss this case exists to detect (todo 11: "assert alarms re-registered").
    # The setup above already rejects `before < 1`, so this can never be a
    # vacuous 0 >= 0.
    [[ "$after" -ge "$before" ]] && break
    sleep 5
  done
  local waited_s=$(( $(date +%s) - unlocked_at ))
  # Leave the app usable for whatever case runs next: after a reboot the
  # process is gone and the dev client has not reconnected to Metro, so a
  # following case would fail its harness call for reasons this case caused.
  wait_for_app_ready >/dev/null 2>&1 || true
  if [[ "$after" -ge "$before" ]]; then
    add_result 5 boot_reschedule true "alarms re-registered after reboot (before=$before after=$after, cross-checked against the OS per-uid tally=$before_uid; all armed 15m+ out so none could have come due during the reboot; receiver landed ~${waited_s}s after the first unlock, which is when ACTION_BOOT_COMPLETED is broadcast)"
  elif [[ "$after" -ge 1 ]]; then
    add_result 5 boot_reschedule false "PARTIAL RESTORE — only $after of $before alarms came back within ${BOOT_REGISTER_TIMEOUT_S}s of the first unlock, so $(( before - after )) dose(s) would never fire. Unlike earlier revisions of this case, that is now a product defect rather than an ambiguity: every alarm was armed 15m+ out, so none could have come due during the reboot, and the count was cross-checked against the OS per-uid tally (=$before_uid) before rebooting. Identify the missing alarms with \`harness pending-ids\`."
  else
    add_result 5 boot_reschedule false "alarms NOT re-registered within ${BOOT_REGISTER_TIMEOUT_S}s of the first unlock (before=$before after=$after). The device WAS unlocked, so ACTION_BOOT_COMPLETED was broadcast and this is a real failure. Check that RECEIVE_BOOT_COMPLETED is granted and the package is not in the stopped state."
  fi
}

case6_stress_horizon() {
  require_reachable 6 stress_horizon_cap || return
  grant_exact
  harness cancel-all >/dev/null
  local i count
  for ((i = 0; i < 30; i++)); do
    harness schedule "stress-$i" $((300000 + i * 1000)) >/dev/null
  done
  count="$(harness pending-count | grep -oE "pending_count=[0-9]+" | cut -d= -f2)"
  harness cancel-all >/dev/null
  if [[ -n "$count" ]] && (( count >= 1 && count <= 64 )); then
    add_result 6 stress_horizon_cap true "pending_count=$count within [1,64]"
  else
    add_result 6 stress_horizon_cap false "pending_count=${count:-unknown} outside [1,64]"
  fi
}

# The unified-model case. Before the cutover `medications` had no shape for a
# weekly schedule, so a "Mon/Wed/Fri at 09:00" reminder produced no alarms at
# all. This asserts the opposite from device state: every fire timestamp the
# scheduler produced must land on a requested weekday at the requested
# wall-clock time, evaluated with the DEVICE's clock and timezone (`adb shell
# date`), not the host's — a weekly schedule that drifts across a DST boundary
# fails here even though the host would call it correct.
case7_weekly_schedule() {
  require_reachable 7 weekly_schedule || return
  grant_exact
  harness cancel-all >/dev/null
  local out fire_ats tz ts sec dow hhmm bad=0 count=0
  out="$(harness schedule-weekly "weekly-$(date +%s)" 0)"
  fire_ats="$(printf '%s' "$out" | grep -oE "weekly_fire_ats=[0-9,]*" | cut -d= -f2)"

  if [[ -z "$fire_ats" ]]; then
    add_result 7 weekly_schedule false "no fire times returned: ${out:-unreachable}"
    return
  fi

  # Interpret the timestamps in the DEVICE's timezone, but with the host's GNU
  # date: Android's toybox date does not reliably accept `-d @epoch`, and a
  # silently-empty weekday there would read as a violation and fail a passing
  # build. Reading the zone off the device keeps the assertion device-local.
  tz="$(adb_shell getprop persist.sys.timezone 2>/dev/null | tr -d '\r')"
  if [[ -z "$tz" ]]; then
    add_result 7 weekly_schedule false "could not read device timezone"
    return
  fi

  for ts in ${fire_ats//,/ }; do
    count=$((count + 1))
    sec=$((ts / 1000))
    dow="$(TZ="$tz" date -d "@$sec" +%w)"
    hhmm="$(TZ="$tz" date -d "@$sec" +%H:%M)"
    case "$dow" in
      1|3|5) ;;
      *) bad=$((bad + 1)); note "fire $ts weekday=$dow not in {1,3,5}" ;;
    esac
    if [[ "$hhmm" != "09:00" ]]; then
      bad=$((bad + 1))
      note "fire $ts time=$hhmm expected 09:00"
    fi
  done

  harness cancel-all >/dev/null

  if (( count == 3 && bad == 0 )); then
    add_result 7 weekly_schedule true "3 alarms, all Mon/Wed/Fri 09:00 in $tz ($fire_ats)"
  else
    add_result 7 weekly_schedule false "count=$count expected 3, violations=$bad ($fire_ats)"
  fi
}

run_case() {
  case "$1" in
    1) case1_killed_app ;;
    2) case2_alarm_clock_primitive ;;
    3) case3_doze ;;
    4) case4_exact_denied_degrades ;;
    5) case5_boot_reschedule ;;
    6) case6_stress_horizon ;;
    7) case7_weekly_schedule ;;
    *) echo "unknown case: $1" >&2; return 2 ;;
  esac
}

usage() {
  cat <<'EOF'
notification-harness.sh — adb-driven notification test harness

  --list          List cases
  --case N        Run case N (1..7)
  --all           Run every case
  --output PATH   JSON report destination

Env: TASKRX_PKG ANDROID_SERIAL METRO_URL VERBOSE=1 SKIP_DOZE=1 SKIP_REBOOT=1

Before running:
  adb reverse tcp:8081 tcp:8081
  (cd apps/mobile && npx expo start --dev-client)
  Ensure the device is UNLOCKED.
EOF
}

list_cases() {
  cat <<'EOF'
1  killed_app_delivery      Backgrounded + `am kill`; notification still posts
2  alarm_clock_primitive    dumpsys proves setAlarmClock (window=0 + next-alarm-clock timestamp match)
3  doze_deep_idle           Fires in forced deep idle (best-effort; may need manual unlock)
4  exact_denied_degrades    Permission denied: reports denied AND still delivers inexactly
5  boot_reschedule          Alarms re-registered after reboot
6  stress_horizon_cap       30 scheduled -> pending count stays within the 64 cap
7  weekly_schedule          Mon/Wed/Fri weekly reminder arms 3 alarms at 09:00 device-local
EOF
}

command -v adb >/dev/null 2>&1 || { echo "adb not found on PATH" >&2; exit 2; }
[[ $# -eq 0 ]] && { usage; exit 2; }

output="$default_output"
cases_to_run=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --list) list_cases; exit 0 ;;
    --case) cases_to_run+=("$2"); shift 2 ;;
    --all) cases_to_run=(1 2 3 4 5 6 7); shift ;;
    --output) output="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done
[[ ${#cases_to_run[@]} -eq 0 ]] && { usage; exit 2; }

devices="$(adb devices | tail -n +2 | grep -cw device || true)"
(( devices < 1 )) && { echo "no adb device attached" >&2; exit 2; }
adb_shell pm path "$PKG" >/dev/null 2>&1 || {
  echo "app $PKG not installed. Build and install first:" >&2
  echo "  (cd apps/mobile/android && ./gradlew assembleDebug)" >&2
  echo "  adb install -r apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk" >&2
  exit 2
}

# Verify the alarm-store parse BEFORE running anything. Every alarm assertion
# in this script reads through `pending_dump`, and if its section whitelist no
# longer matches this Android build it yields nothing — which would surface as
# alarm counts of 0 and score product FAILs for an instrument that cannot read.
# Checking once here is what makes that impossible; see constraint 9.
if ! pending_dump >/dev/null; then
  echo "cannot parse the alarm store out of 'dumpsys alarm' on this device." >&2
  echo "Every alarm assertion depends on it, so no verdict would be trustworthy." >&2
  echo "The section whitelist in pending_dump likely needs updating for this build." >&2
  exit 2
fi

echo "waiting for app + Metro to be ready..."
ready="$(wait_for_app_ready)" || {
  echo "app never became ready. Check:" >&2
  echo "  1. Metro is running (npx expo start --dev-client)" >&2
  echo "  2. adb reverse tcp:8081 tcp:8081 is established" >&2
  echo "  3. the device is UNLOCKED — a locked device cannot foreground the app" >&2
  exit 2
}
echo "app ready: $ready"
echo "running cases: ${cases_to_run[*]}"

# Case 3 mutates device state (battery unplug, deviceidle, and the global
# device_idle_constants). If the run is interrupted, that state MUST still be
# rolled back — otherwise the user is left holding a phone whose Doze
# behaviour has been silently altered by a test.
trap 'restore_device' EXIT INT TERM

for c in "${cases_to_run[@]}"; do
  echo "---- case $c ----"
  run_case "$c"
done

restore_device

pass_count=0
fail_count=0
for r in "${results[@]}"; do
  if printf '%s' "$r" | grep -q '"pass":true'; then
    pass_count=$((pass_count + 1))
  else
    fail_count=$((fail_count + 1))
  fi
done

mkdir -p "$(dirname "$output")"
{
  dirty_files="$(provenance_dirty_files)"
  # Outside a git repo the tree was never inspected, so `false` would be a
  # clean-tree claim about something unexamined — exactly the kind of
  # unearned assertion this block exists to prevent. Say "unknown" instead.
  if ! git_in_repo; then
    dirty_json='"unknown"'
  elif [[ -n "$dirty_files" ]]; then
    dirty_json=true
  else
    dirty_json=false
  fi
  printf '{\n  "timestamp": "%s",\n  "package": "%s",\n' "$(date -u -Iseconds)" "$PKG"
  printf '  "provenance": { "commit": "%s", "branch": "%s", "dirty": %s, "dirty_files": "%s" },\n' \
    "$(provenance_commit)" \
    "$(provenance_branch)" \
    "$dirty_json" \
    "$(json_escape "$dirty_files")"
  printf '  "device": { "serial": "%s", "release": "%s", "sdk": "%s", "fingerprint": "%s" },\n' \
    "$(adb_shell getprop ro.serialno 2>/dev/null | tr -d '\r')" \
    "$(adb_shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')" \
    "$(adb_shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')" \
    "$(adb_shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r')"
  printf '  "summary": { "passed": %d, "failed": %d },\n  "cases": [\n' "$pass_count" "$fail_count"
  for ((i = 0; i < ${#results[@]}; i++)); do
    if (( i == ${#results[@]} - 1 )); then
      printf '    %s\n' "${results[$i]}"
    else
      printf '    %s,\n' "${results[$i]}"
    fi
  done
  printf '  ]\n}\n'
} >"$output"
echo "wrote $output"

if (( fail_count > 0 )); then
  echo "$fail_count case(s) failed" >&2
  exit 1
fi
echo "all cases passed"
exit 0
