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
# Learned by running against a Pixel 8 Pro on Android 17 (API 37). Thirteen
# constraints: 1-7 below, then 8 (deep Doze is blocked by a pending
# wake-from-idle alarm), 9 (`dumpsys alarm` is mostly NOT pending alarms), 10
# (silence from adb is not a fact about the device), 11 (in the signed-in tree
# `rescheduleAll` sweeps away any alarm it did not derive), 12 (an ungranted
# POST_NOTIFICATIONS silently discards every delivery), and 13 (`adb shell`
# re-parses the URL you pass it), which are documented inline at their point
# of use.
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
#  6a. A LONG DEV-CLIENT SESSION CAN LOCK THE DATABASE. After editing app
#     source with the harness running, every SQLite write began failing with
#     "Error code : database is locked", so `seed-due-reminder` threw and
#     cases 8 and 9 reported SETUP FAILED. `am kill` plus a relaunch cleared it
#     immediately and the same call then succeeded in ~1s.
#     Suspected cause, stated as the suspicion it is: each Metro bundle reload
#     re-runs module initialisation and opens the database again, while the
#     previous native connection stays open — the app's own code opens exactly
#     one connection and uses transactions only for migrations, so a second
#     writer has to come from somewhere outside it. Treated as a DEV-CLIENT
#     artifact, not a product defect, and deliberately NOT worked around here:
#     the harness surfaces the app's error instead (see `harness_once`), and
#     the remedy is to restart the app after touching app source.
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
# CASES 8 AND 9 ADDITIONALLY REQUIRE A SIGNED-IN APP, and are deliberately NOT
# part of `--all`. Cases 1-7 run local-only against a fresh install, which is
# what makes that suite the delivery baseline on any device; folding in two
# cases that report `signed_out` on such a device would turn every baseline run
# into an incomplete one. Run them with `--auth`, or by number.
#
# Case 9 also needs the Convex deployment the DEVICE talks to to be readable
# from THIS machine, because it verifies arrival server-side rather than
# trusting the app's own "synced" flag. With the usual local backend that means
# `adb reverse tcp:3210 tcp:3210` (asserted automatically) plus a `npx convex`
# that resolves the same deployment. When it does not, the case reports itself
# unevaluated rather than failing the product.
#
# Usage:
#   apps/mobile/scripts/notification-harness.sh --list
#   apps/mobile/scripts/notification-harness.sh --case 2
#   apps/mobile/scripts/notification-harness.sh --all --output report.json
#   apps/mobile/scripts/notification-harness.sh --case 8 --output tap.json
#   apps/mobile/scripts/notification-harness.sh --case 9 --output drain.json
#
# RUN 8 AND 9 IN SEPARATE INVOCATIONS. `--auth` runs both, and case 9 then
# reports SETUP FAILED every time: case 8 lets a notification fire, and from
# that point the app's SQLite writes fail with "Error code : database is
# locked" — through an `am kill` and a verified-fresh process, so the lock
# outlives the process that fired the alarm. Run separately, both pass.
# This is the same shape as the two-pass split constraint 5 documents for
# cases 3 and 5: a real limit, recorded rather than hidden, and NOT worked
# around by having a case restart something until it passes. See constraint 6a
# and docs/poc-results.md.
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
#      BOOT_ALARM_COUNT (default 3) CONVEX_DEVICE_PORT (default 3210)
#      SYNC_BUDGET_S (default 60 — criterion 9's own wording)
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
# Exit: 0 all passed | 1 a case RAN and FAILED (a product verdict) | 2 no
# product failures but at least one case was never evaluated, or a precondition
# was not met — either way, no trustworthy verdict. See `usage()` and the
# `add_result` / `add_unevaluated` pair: a case has three fates, not two.

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
# Cases 8 and 9 only. The port the DEVICE reaches its Convex deployment on. For
# the usual local backend this is the loopback port an `adb reverse` tunnel
# forwards; for a cloud deployment the tunnel is simply unused.
CONVEX_DEVICE_PORT="${CONVEX_DEVICE_PORT:-3210}"
# Cases 8 and 9 only. Extra time to wait for the actions that end in
# `rescheduleAll`, which rewrites a horizon of up to 64 triggers one notifee
# call at a time and does not answer inside HARNESS_SETTLE_S.
HARNESS_SLOW_BUDGET_S="${HARNESS_SLOW_BUDGET_S:-90}"
# Case 9 only. Criterion 9 says "sync within 60s", so the budget is the
# criterion's own number rather than a tuned value. Overridable only so a
# slower link can be measured without editing the case.
SYNC_BUDGET_S="${SYNC_BUDGET_S:-60}"
# Case 8 only. Lead time for the seeded occurrence. The schedule engine has
# minute resolution, so this is rounded UP to the next whole minute by
# `seed-due-reminder`; 120s therefore buys 60-120s of setup room.
TAP_LEAD_MS="${TAP_LEAD_MS:-120000}"
# Case 8 only. How long past the armed time to keep looking for the post. An
# exact alarm lands within ~100ms, but the process may have to be started to
# run the headless JS that posts it.
TAP_POST_GRACE_S="${TAP_POST_GRACE_S:-60}"

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

# ── Is there still a device? (constraint 10) ───────────────────────────────
#
# Every `adb_shell` in this script swallows stderr and yields an empty string
# when the device is gone. That is indistinguishable, at the call site, from a
# device that answered with nothing — so a caller that reads emptiness as a
# FACT ABOUT THE DEVICE will state something it cannot know.
#
# It has already happened. During the issue #6 verification the phone dropped
# off USB mid-run and the harness reported "the device rebooted into the lock
# screen and was never unlocked within 300s, so Android never broadcast
# ACTION_BOOT_COMPLETED (user 0 stayed RUNNING_LOCKED)" — four specific claims
# about a device that was not attached — and blamed the empty `dumpsys alarm`
# on a renamed section header. That verdict went into a JSON evidence file,
# which is where these artifacts get committed and cited from.
#
# The startup preconditions below already refuse to begin without a device. The
# gap is losing one MID-RUN, which a reboot makes routine: case 5 reboots by
# design, and a phone can come back on a different USB enumeration or not at
# all.
#
# The guards are at CHOKE POINTS, not at each case, because per-case entry
# checks miss the mid-case loss that is the likelier failure — cases 1, 2 and 3
# never call `require_reachable` at all, and the four that do call it only on
# the way in. Three places cover the script:
#
#   * `harness_once` — every case drives the app through it, and it ends in
#     `adb logcat`, which BLOCKS on a missing device rather than failing. It
#     bails first, so a disconnect returns instead of hanging the run.
#   * `add_result`   — every verdict is recorded through it, so a product FAIL
#     cannot be asserted about a device that was not there to fail.
#   * `pending_dump` — every alarm read goes through it, so an empty dump is
#     attributed to the missing device rather than to the section whitelist.
#
# `require_reachable` also checks, which is redundant with the above but keeps
# the wasted probe out of the four cases that call it.
#
# One deliberate exception, documented at its site: the post-reboot
# `boot_completed` poll, where the device is SUPPOSED to be absent.
#
# `get-state` rather than `adb devices`: it honours `$ANDROID_SERIAL` through
# `adb_args`, and prints exactly `device` only when the device is present AND
# authorized — an unauthorized phone reports `unauthorized` and is correctly
# treated as absent, since nothing can be read from it either.
device_attached() {
  local state
  state="$(adb_run get-state 2>/dev/null | tr -d '\r')"
  [[ "$state" == "device" ]]
}

nonce_counter=0
next_nonce() { nonce_counter=$((nonce_counter + 1)); printf 'n%s' "$nonce_counter"; }

# ── Harness invocation (constraints 1 and 3) ───────────────────────────────

harness_once() {
  local action="$1" id="${2:-x}" delay="${3:-0}" extra_budget_s="${4:-0}"
  local nonce out deadline
  # Bail before touching adb at all. `adb logcat` BLOCKS waiting for a device
  # rather than failing, so with no device this function does not return an
  # empty string — it hangs the whole run, indefinitely, in whichever case was
  # in flight. Every case funnels through here, so this one check is what keeps
  # a mid-run disconnect from freezing cases 1, 2, 3, 6 and 7 as well as the
  # ones that guard on entry.
  #
  # Returning empty is the right shape: callers already treat emptiness as "the
  # app did not answer", `harness()` retries once, and `add_result` refuses to
  # turn the resulting non-answer into a product FAIL.
  if ! device_attached; then
    return 0
  fi
  # `result:` OR `error:`. `HarnessScreen` catches a thrown action and logs it
  # as `[harness] error: <message>`, and matching only `result:` threw that
  # away — so an action that failed loudly, with a diagnosis, was reported to
  # the caller as silence and rendered as "the app answered: <nothing>".
  #
  # Measured: `seed-due-reminder` failed with "Error code : database is locked"
  # and two consecutive runs recorded SETUP FAILED with no cause, sending the
  # investigation towards deep links and timing. The app had been saying
  # exactly what was wrong the whole time. Callers already treat a reply they
  # cannot parse as a failed setup; the difference is that the reply now
  # carries the reason into the evidence file.
  nonce="$(next_nonce)"
  adb_run logcat -c >/dev/null 2>&1
  adb_shell am start -a android.intent.action.VIEW \
    -d "taskrx://harness/${action}/${id}/${delay}/${nonce}" >/dev/null 2>&1
  sleep "$HARNESS_SETTLE_S"
  out="$(adb_run logcat -d 2>/dev/null | grep -oE "\[harness\] (result|error):.{0,160}" | tail -1)"

  # The blind settle above is kept exactly as it was — cases 1-7 read `dumpsys`
  # immediately after this returns, and shortening their wait would race the
  # alarm's registration. `extra_budget_s` only extends it, and only for callers
  # that ask.
  #
  # They have to ask because some actions genuinely take longer than 8s.
  # `seed-due-reminder` and `cleanup-seeded` both end in `rescheduleAll`, which
  # diffs and rewrites a horizon of up to 64 triggers one notifee call at a
  # time; measured against a 59-alarm horizon it did not answer inside the
  # settle. The reply was then read as "the app did not answer" and case 8
  # reported SETUP FAILED — for a seed that had in fact been written, which is
  # the worse half: `harness()` then retried and seeded a SECOND reminder under
  # the same name. Polling for the line the app does eventually log is what
  # keeps a slow action from looking like a dead one.
  if [[ -z "$out" && "$extra_budget_s" -gt 0 ]]; then
    deadline=$(( $(date +%s) + extra_budget_s ))
    while [[ -z "$out" ]] && (( $(date +%s) < deadline )); do
      device_attached || break
      sleep 2
      out="$(adb_run logcat -d 2>/dev/null | grep -oE "\[harness\] (result|error):.{0,160}" | tail -1)"
    done
  fi
  printf '%s\n' "$out"
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
    # Establish WHY it is empty before naming a cause. A detached device makes
    # every `adb_shell` return nothing, so the whitelist explanation below —
    # which sends the reader off to diff Android dump formats across builds —
    # is exactly wrong when the real answer is that the cable came out.
    if ! device_attached; then
      printf '[pending_dump] NO DEVICE: `dumpsys alarm` returned nothing because no device is attached (adb get-state is not "device"). This says nothing about the alarm store or the section whitelist. Reconnect the device and re-run.\n' >&2
      return 1
    fi
    printf '[pending_dump] PARSE FAILED: the device is attached but the alarm-store section of `dumpsys alarm` came back empty. A section header in the cut whitelist has probably been renamed or reordered. Alarm counts from this run are not trustworthy.\n' >&2
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

# ── Constraint 12: AN UNGRANTED POST_NOTIFICATIONS DISCARDS EVERY DELIVERY. ──
# On Android 13+ the alarm still fires and notify-kit still runs, and then the
# framework throws SecurityException and drops the notification on the floor:
#
#   W NOTIFEE (NotificationManager): POST_NOTIFICATIONS permission is not
#     granted. Notification was not displayed.
#
# Nothing in `dumpsys alarm` shows it — the alarm looks perfect right up to the
# moment it fires — so every delivery case (1, 3, 4, and 8) reports "NOT
# posted" and scores a product FAIL for a permission the rig never granted.
# Measured while building case 8: the alarm fired at the armed millisecond and
# no notification existed a minute later.
#
# `pm grant` rather than a prompt, and it is the same class of accommodation as
# `grant_exact` above: the permission is the user's to give, and the cases
# exist to test delivery, not the consent dialog. `notification_permission`
# reads the state back so a result can say which it ran under.
#
# It is worth knowing WHY a real device can be missing it: `pm clear` revokes
# runtime permissions, and in this app the only caller of `requestPermissions()`
# is the debug screen — so a fresh install never asks. That is a product gap,
# recorded in docs/poc-results.md, not something this grant fixes.
grant_post()       { adb_shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true; }
notification_permission() {
  adb_shell dumpsys package "$PKG" 2>/dev/null \
    | grep -m1 "android.permission.POST_NOTIFICATIONS: granted=" \
    | grep -oE 'granted=(true|false)' | tr -d '\r'
}

# ── Constraint 13: `adb shell` RE-PARSES WHATEVER YOU HAND IT. ──────────────
# `adb shell am start -d "taskrx:///dose/log?a=1&b=2"` does NOT deliver that
# URL. The arguments are joined and run through the DEVICE's shell, so the `&`
# backgrounds the command and everything after it is lost — the app receives
# `?a=1` and reports "Invalid dose link", which reads exactly like a routing
# bug in the app. Measured while building case 9.
#
# The existing `harness://` links encode their parameters as path segments and
# are unaffected, which is why this went unnoticed until a route with a real
# query string was driven. Wrap in single quotes so the device shell strips
# them and `am` sees one argument.
deep_link() { adb_shell am start -a android.intent.action.VIEW -d "'$1'" >/dev/null 2>&1; }

# ── UI reads (cases 8 and 9) ───────────────────────────────────────────────
#
# Cases 1-7 assert from `dumpsys`, which is the stronger instrument and stays
# the default. These two cannot: "the dose screen opened, pre-filled with the
# right medication and time" is a claim about what the USER sees, and only the
# view hierarchy can answer it. Tapping a notification is the same — there is
# no adb command that invokes a notification's content intent, so the case has
# to find the row and tap its coordinates, exactly as a finger would.
#
# PRIVACY, and this is not incidental: `uiautomator dump` captures the whole
# screen, and on a real phone the notification shade is full of the owner's
# messages, calendar invites and addresses. Nothing below ever echoes a dump,
# and every value that reaches the JSON report is one this script matched or
# derived. These reports get committed — keep it that way.
ui_dump()  { adb_run exec-out uiautomator dump /dev/tty 2>/dev/null | tr '>' '\n'; }

# ── The screen does not use the space you think it does. ───────────────────
# `toLocaleString` on a modern ICU separates the time from AM/PM with U+202F
# NARROW NO-BREAK SPACE, not U+0020. The dose screen renders
#
#   Scheduled: Aug 3, 2:39<U+202F>PM        (hex ... 32 3a 33 39 e2 80 af 50 4d)
#
# which is indistinguishable from an ordinary space in any terminal, log or
# screenshot. Case 8 built its expected string with `date`, which emits a plain
# space, and reported "the dose screen did not show the right time" for a
# screen showing exactly the right time — while its name and dosage assertions
# passed, so the failure looked like a scheduling bug rather than a text one.
# Diagnosed only by dumping the bytes.
#
# Normalise the three Unicode spaces ICU actually uses (U+202F, U+00A0, U+2009)
# to U+0020 for every text read, so assertions may be written with the space a
# human would type.
ui_text() {
  ui_dump | grep -oE 'text="[^"]*"' \
    | sed -e 's/^text="//' -e 's/"$//' \
          -e 's/\xe2\x80\xaf/ /g' -e 's/\xc2\xa0/ /g' -e 's/\xe2\x80\x89/ /g' \
    | grep -v '^$'
}

# Does the current screen show this text? Substring, because the dose screen
# renders "Scheduled: Aug 3, 2:22 PM" as one node.
ui_shows() { ui_text | grep -qF "$1"; }

# Tap the centre of the first node whose XML contains `needle`. Returns 1 when
# there is no such node, which callers must treat as "could not be driven"
# rather than as a failed assertion.
tap_node() {
  local needle="$1" line bounds x1 y1 x2 y2
  line="$(ui_dump | grep -F "$needle" | head -1)"
  [[ -z "$line" ]] && return 1
  bounds="$(printf '%s' "$line" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)"
  [[ -z "$bounds" ]] && return 1
  read -r x1 y1 x2 y2 <<<"$(printf '%s' "$bounds" | grep -oE '[0-9]+' | tr '\n' ' ')"
  adb_shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )) >/dev/null 2>&1
}

app_is_foreground() {
  adb_shell dumpsys activity activities 2>/dev/null | grep -q "topResumedActivity.*$PKG"
}

# ── Connectivity (case 9) ──────────────────────────────────────────────────
#
# `cmd connectivity airplane-mode` rather than `settings put global
# airplane_mode_on`: the setting alone does not take the radios down without
# the broadcast, which a shell user may not send. Verified on Android 17 —
# after `enable`, `dumpsys wifi` reads "Wi-Fi is disabled" and the default
# network is gone.
#
# adb itself is unaffected: it runs over USB, so the device stays fully
# observable throughout the offline window.
airplane_on()  { adb_shell cmd connectivity airplane-mode enable  >/dev/null 2>&1 || true; }
airplane_off() { adb_shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true; }

# The OS's own view of whether anything is reachable, which is what NetInfo —
# and therefore the sync worker — is ultimately reading. "none" while airplane
# mode is on; an id once a network comes back.
default_network() {
  adb_shell dumpsys connectivity 2>/dev/null \
    | grep -m1 "Active default network:" | cut -d: -f2 | tr -d ' \r'
}

wait_for_network() {
  local want="$1" deadline=$(( $(date +%s) + ${2:-60} ))
  while (( $(date +%s) < deadline )); do
    local net
    net="$(default_network)"
    if [[ "$want" == "none" && "$net" == "none" ]]; then return 0; fi
    if [[ "$want" == "any" && -n "$net" && "$net" != "none" ]]; then return 0; fi
    sleep 2
  done
  return 1
}

# ── Convex (case 9) ────────────────────────────────────────────────────────
#
# Case 9 asserts arrival ON THE SERVER rather than trusting the app's own
# `sync_status = 'synced'`, because that column is written by the same code
# path the case is testing: a worker that marked rows synced without pushing
# them would pass its own audit. `npx convex data` reads the deployment
# directly.
#
# Run from `packages/convex` because the CLI resolves the deployment from the
# `.env.local` in its own working directory — from the repo root it would
# scaffold a stray project instead.
convex_rows() {
  (cd "$repo_root/packages/convex" && npx convex data "$1" --limit "${2:-50}" 2>/dev/null)
}
convex_reachable() { convex_rows reminders 1 | grep -q "_id"; }
convex_has_local_id() { convex_rows reminderInstances 50 | grep -qF "$1"; }

ensure_convex_reverse() {
  adb_run reverse "tcp:$CONVEX_DEVICE_PORT" "tcp:$CONVEX_DEVICE_PORT" >/dev/null 2>&1 || true
}
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
  # Case 9 takes the radios down. This is the safety net that brings them back
  # if the run is interrupted mid-window — leaving someone's phone in airplane
  # mode because a test was cancelled would be the worst thing this script
  # does, and it is silent: the device stays on adb over USB throughout, so
  # nothing here looks broken.
  airplane_off
  # Case 9 also collapses/expands the shade (case 8 does the tapping). Leave it
  # closed so the next case can foreground the app.
  adb_shell cmd statusbar collapse >/dev/null 2>&1 || true
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

# A case has three possible fates, not two, and flattening them is what made a
# missing cable readable as a product failure.
#
#   pass / fail       — the app was driven and gave an answer. A verdict.
#   unreachable       — the device was there but the case could not be run
#                       (keyguard up, app not foregroundable). Environmental.
#   no_device         — there was no device to run it against. Instrumental.
#
# The last two are NOT verdicts, and `pass` is `null` for both so that a
# consumer reading only that field cannot mistake "never measured" for
# "measured and failed". `outcome` carries the distinction; `summary` counts all
# three; and the exit status separates "something is broken" from "we did not
# find out".
# The one place a result becomes JSON, so the escaping is defined once.
#
# `detail` is built from `dumpsys` output — alarm dumps, notification dumps,
# deviceidle traces — which is arbitrary text this script does not control. Both
# writers previously escaped only the double quote, while `json_escape` right
# above exists precisely because a BACKSLASH has to be escaped first: `\` then
# `"` , never the other way round. A single backslash out of dumpsys therefore
# produced a report that would not parse — and this file's whole purpose is
# emitting evidence artifacts that get committed and cited.
#
# Newlines are flattened before escaping because a raw newline inside a JSON
# string is invalid regardless of quoting.
record_case() {
  local n="$1" name="$2" pass="$3" outcome="$4" label="$5" detail="$6"
  local d
  d="$(json_escape "$(printf '%s' "$detail" | tr '\n' ' ')")"
  results+=("{\"case\":${n},\"name\":\"${name}\",\"pass\":${pass},\"outcome\":\"${outcome}\",\"detail\":\"${d}\"}")
  printf '  [%s] case %s: %s — %s\n' "$label" "$n" "$name" "$d"
}

add_result() {
  local n="$1" name="$2" pass="$3" detail="$4"
  local outcome verdict
  # THE choke point, and the reason the guard lives here rather than at each
  # case's entry. Cases 1, 2 and 3 never call `require_reachable` at all, and
  # the four that do call it only on the way IN — so a device lost part-way
  # through (case 3 spends minutes in forced Doze; case 5 deliberately reboots)
  # still landed on `add_result … false` and scored a product FAIL built on
  # readings from nothing.
  #
  # A failure can only be asserted about a device that is there to have failed.
  # When it is not, this is downgraded to "never evaluated" and the original
  # detail is kept but explicitly disowned. Only failures are converted: a PASS
  # cannot be manufactured out of silence in the first place, since every
  # passing assertion requires reading a real value.
  if [[ "$pass" != true ]] && ! device_attached; then
    add_unevaluated "$n" "$name" no_device \
      "NO DEVICE (instrument, not product): the device was lost before this case could be judged (adb get-state is not \"device\"), so what follows is NOT a verdict and the readings behind it came from an absent device: ${detail}"
    return
  fi
  if [[ "$pass" == true ]]; then
    outcome=pass; verdict=PASS
  else
    outcome=fail; verdict=FAIL
  fi
  record_case "$n" "$name" "$pass" "$outcome" "$verdict" "$detail"
}

# Record a case that was never evaluated. `kind` is `unreachable` (the device
# was present and uncooperative) or `no_device` (there was nothing to talk to).
add_unevaluated() {
  local n="$1" name="$2" kind="$3" detail="$4"
  record_case "$n" "$name" null "$kind" \
    "$(printf '%s' "$kind" | tr '[:lower:]' '[:upper:]')" "$detail"
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
  # BEFORE the probe, not after. Two reasons, and the second is not cosmetic:
  #
  #   * Silence from the app and silence from an absent device are identical at
  #     this call site, so the cause has to be established before it is
  #     described. Reading the keyguard off a detached device yields an empty
  #     string, which the defaults below would dress up as
  #     "isKeyguardShowing=unknown" — a claim about a phone that is not there.
  #   * `harness_once` ends in `adb logcat -d`, and `adb logcat` BLOCKS waiting
  #     for a device rather than failing. Probing first therefore does not
  #     return a wrong answer, it hangs the run indefinitely. Measured while
  #     fixing this: the probe never came back with the cable out.
  if ! device_attached; then
    add_unevaluated "$n" "$name" no_device \
      "NO DEVICE (instrument, not product): no device is attached (adb get-state is not \"device\"), so this case was never run. Nothing here is a statement about the app or the phone. Reconnect the device and re-run this case."
    return 1
  fi
  local probe
  probe="$(harness pending-count reachability 0)"
  if [[ -n "$probe" ]]; then
    return 0
  fi
  # The device is attached and still did not answer — genuinely environmental.
  local keyguard wakefulness
  keyguard="$(adb_shell dumpsys window 2>/dev/null | grep -oE 'isKeyguardShowing=[a-z]+' | head -1 | tr -d '\r')"
  wakefulness="$(screen_wakefulness)"
  add_unevaluated "$n" "$name" unreachable \
    "UNREACHABLE (not a product failure): the device is attached but the app never answered a deep link, so this case could not be evaluated. ${keyguard:-isKeyguardShowing=unknown} mWakefulness=${wakefulness:-unknown}. If the keyguard is showing, unlock the device and re-run this case."
  return 1
}

# Cases 8 and 9 only. The signed-out app is not a broken app: `/dose/log`
# renders "Sign in to log a dose" and the sync worker is never started, both
# correctly. Running these cases against it would assert nothing and report a
# FAIL, which is the same false-verdict shape constraint 10 exists to prevent —
# so a signed-out app makes the case UNEVALUATED, with the reason named.
#
# This is also why `--all` stops at 7: cases 1-7 are local-only by design and a
# device with no session must still be able to produce a clean delivery
# baseline.
require_signed_in() {
  local n="$1" name="$2" out
  # Before the probe, for the reason `require_reachable` documents at length: a
  # detached device answers every question with silence, and silence here would
  # otherwise be dressed up as "the app is signed out" — a claim about a
  # session on a phone that is not attached.
  if ! device_attached; then
    add_unevaluated "$n" "$name" no_device \
      "NO DEVICE (instrument, not product): no device is attached (adb get-state is not \"device\"), so this case was never run. Nothing here is a statement about the app's session."
    return 1
  fi
  out="$(harness auth-status)"
  if [[ -z "$out" ]]; then
    add_unevaluated "$n" "$name" unreachable \
      "UNREACHABLE (not a product failure): the app stopped answering deep links between the reachability probe and the auth check, so this case was never run."
    return 1
  fi
  if printf '%s' "$out" | grep -q "signed_in=true"; then
    return 0
  fi
  # An action that THREW is not an app that is signed out. Both arrive here as
  # "not signed_in=true", and blaming the session would send the reader off to
  # sign in on a device that already is.
  if printf '%s' "$out" | grep -q "\[harness\] error:"; then
    add_unevaluated "$n" "$name" app_error \
      "APP ERROR (not a verdict): the auth check itself threw inside the app, so whether this case would pass is unknown. The app reported: ${out}"
    return 1
  fi
  add_unevaluated "$n" "$name" signed_out \
    "SIGNED OUT (not a product failure): the app has no Clerk session, so this case was never run. It asserts against the signed-in tree — SQLite rows owned by a subject, and a sync worker that only starts once signed in — none of which exists here. Sign in on the device and re-run. Reported by the app as: ${out}"
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
    add_unevaluated 5 boot_reschedule setup_failed \
      "SETUP FAILED (not a product failure): no alarm was armed before the reboot (before=$before), so there was nothing for the boot receiver to restore. Not rebooting."
    return
  fi
  # Two independent parses of the same fact. If they disagree, the instrument
  # is wrong and no verdict from it should be believed — say so instead of
  # rebooting the device to measure with a broken ruler.
  if [[ "$before" != "$before_uid" ]]; then
    add_unevaluated 5 boot_reschedule instrument_fault \
      "INSTRUMENT FAULT (not a product failure): the two independent alarm counts disagree before the reboot (alarm_count=$before, per-uid tally=$before_uid). One of the two parses of \`dumpsys alarm\` is wrong, so neither before nor after can be trusted. Not rebooting. This guard exists because an unscoped count silently made this case unfalsifiable for three runs."
    return
  fi
  adb_run reboot >/dev/null 2>&1
  sleep 10
  deadline=$(( $(date +%s) + 180 ))
  # Deliberately NO `device_attached` bail inside this loop, unlike everywhere
  # else in this script. Here the device is SUPPOSED to be missing: it is
  # rebooting, and adb loses it for most of this window. A guard here would fire
  # on the normal path and report "no device" for a phone that is merely
  # restarting — inverting the very defect constraint 10 exists to prevent.
  #
  # Nothing is lost by waiting it out. Reaching `boot_completed=1` proves the
  # device is back, so the unlock wait below starts from a known-attached state
  # and any absence IT sees is genuine loss. A device that never returns simply
  # burns this budget and is then correctly reported there.
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
  # A reboot is exactly when a phone goes missing: it can come back on a
  # different USB enumeration, or not at all. Give up early on a device that has
  # gone rather than spending the whole budget polling nothing and then blaming
  # the user for not unlocking — the false verdict that motivated constraint 10.
  local device_lost=0
  while (( $(date +%s) < unlock_deadline )); do
    adb_shell dumpsys user 2>/dev/null | grep -q RUNNING_UNLOCKED && break
    if ! device_attached; then
      device_lost=1
      break
    fi
    sleep 5
  done
  if (( device_lost )) || ! device_attached; then
    add_unevaluated 5 boot_reschedule no_device \
      "NO DEVICE (instrument, not product): the device did not come back on adb after the reboot (adb get-state is not \"device\"), so this case was never evaluated. Whether the boot receiver re-registered the alarms is unknown — this is not a claim that it did not. Reconnect the device and re-run case 5."
    return
  fi
  if ! adb_shell dumpsys user 2>/dev/null | grep -q RUNNING_UNLOCKED; then
    ensure_reverse
    add_unevaluated 5 boot_reschedule unreachable \
      "UNREACHABLE (not a product failure): the device is attached but rebooted into the lock screen and was never unlocked within ${BOOT_UNLOCK_TIMEOUT_S}s, so Android never broadcast ACTION_BOOT_COMPLETED (user 0 stayed RUNNING_LOCKED) and the boot receiver could not have run. Re-run and unlock when prompted."
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

# ── Signed-in cases (8, 9) ─────────────────────────────────────────────────
#
# Both need a Clerk session, so neither is in `--all`. See `require_signed_in`.

# Start each signed-in case from a fresh app process.
#
# Not hygiene — a precondition, and one that was found the hard way. Both cases
# begin by WRITING to SQLite, and an app process that has been running for a
# while starts refusing every write with
#
#   [harness] error: Call to function 'NativeStatement.finalizeAsync' has been
#   rejected. → Caused by: Error code : database is locked
#
# after which nothing either case does can succeed, and the whole run reports
# SETUP FAILED. `am kill` plus a relaunch clears it every time, and the same
# call then completes in about a second.
#
# What is actually going on is NOT established, so this comment does not claim
# it. Two observations bound it: the app's own code opens exactly one
# connection (`getDb` memoises it) and uses a transaction only for migrations,
# so the second writer comes from outside that code; and the failures appeared
# after a Metro bundle reload and again after a trigger notification fired, both
# of which evaluate the entry bundle in a SECOND JS context (Fast Refresh, and
# notifee's headless task — see `src/notifications/background-handler.ts`).
# A second context re-running module init would open a second connection to the
# same file, which is exactly what SQLITE_BUSY reports.
#
# It is recorded in docs/poc-results.md rather than papered over, and the
# restart is deliberately NOT applied to cases 1-7: those assert delivery, an
# `am kill` mid-suite would change what they measure, and none of them writes.
# Returns non-zero if the process could not be killed, so a case can say so
# rather than proceeding against a database that may already be locked.
#
# The kill is CHECKED, not assumed, and retried. `am kill` only terminates a
# process Android considers cached, and both cases reach here moments after the
# app was last in the foreground — case 8 ends on the dose screen it just
# tapped its way to. Two seconds after HOME is not always enough for the
# demotion, and an `am kill` that silently declines leaves exactly the
# long-lived process this function exists to replace: case 9 then failed with
# "database is locked" immediately after case 8's alarm had fired.
fresh_app_process() {
  local tries=0
  while (( tries < 3 )); do
    kill_app_realistically
    [[ -z "$(adb_shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ]] && break
    sleep 5
    tries=$((tries + 1))
  done
  if [[ -n "$(adb_shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ]]; then
    return 1
  fi
  wait_for_app_ready >/dev/null 2>&1 || true
}

# Criterion 6: tapping the notification opens the dose logging screen
# pre-filled with the right medication and scheduled time.
#
# ── Constraint 11: IN THE SIGNED-IN TREE, `rescheduleAll` SWEEPS AWAY EVERY
# ALARM IT DID NOT DERIVE. `_layout.tsx` calls it on every transition to
# `active`, and its cancel pass removes each pending trigger outside the
# schedule-derived desired set. The `schedule` action cases 1-7 use arms a
# notification under a bare id, which is never in that set — so in this tree it
# is retracted at the next foreground and simply never fires. Measured: armed,
# confirmed in `dumpsys alarm`, gone one foreground later, no notification.
#
# So this case seeds a REAL reminder due shortly and lets `rescheduleAll` arm
# it (`seed-due-reminder`). That is not a workaround, it is the stronger test:
# the alarm under the finger is then one the production scheduler derived from
# a row in SQLite, and the screen the tap lands on reads that same row back.
#
# What the assertions are for, since three of the four look redundant:
#   * the medication NAME proves the tap routed to a dose screen for the right
#     reminder rather than to the home screen or the last-viewed dose;
#   * the DOSAGE proves the screen loaded the row rather than echoing the deep
#     link's parameters, which carry no dosage;
#   * the TIME proves the occurrence identity survived the trip;
#   * and the INSTANCE ROW is the one assertion that cannot be satisfied by a
#     screen that merely looks right — `/dose/log` mints its pending instance at
#     exactly the `(reminder, scheduled time)` pairing it loaded, so a row at
#     that exact millisecond is proof of which occurrence it opened. It is
#     checked at the exact `fire_at`, and a row one second off does not match.
case8_notification_tap() {
  if ! fresh_app_process; then
    add_unevaluated 8 notification_tap setup_failed \
      "SETUP FAILED (not a product failure): the app process could not be restarted, so this case would have run against a process that may already be holding the database locked. Nothing was measured."
    return
  fi
  require_reachable 8 notification_tap || return
  require_signed_in 8 notification_tap || return
  grant_exact
  # NOT `grant_post` — it is granted once at startup, because `pm grant`
  # restarts the app and a restart here would drop the database mid-write.
  local perm
  perm="$(notification_permission)"

  harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null

  local label seeded rid fire_at armed name
  label="tap-$(date +%s)"
  # `harness_once`, NOT `harness`: the self-heal retry is wrong for a write.
  # An empty reply means "no answer yet", not "nothing happened", and asking
  # again seeds a SECOND reminder under the same name — two reminders, two
  # alarms, one label, and a case that then taps whichever posted. The budget
  # above is what makes a retry unnecessary; `require_reachable` ran moments
  # ago, so a genuinely dead app is not the likely cause here.
  seeded="$(harness_once seed-due-reminder "$label" "$TAP_LEAD_MS" "$HARNESS_SLOW_BUDGET_S")"
  rid="$(printf '%s' "$seeded" | grep -oE 'reminder_id=[0-9a-fA-F-]+' | head -1 | cut -d= -f2)"
  fire_at="$(printf '%s' "$seeded" | grep -oE 'fire_at=[0-9]+' | head -1 | cut -d= -f2)"
  armed="$(printf '%s' "$seeded" | grep -oE 'armed=[0-9]+' | head -1 | cut -d= -f2)"
  # The name is the notification's title, but `[harness] ...` is a character
  # class to grep, so every search below uses the regex-safe label instead.
  name="[harness] $label"

  if [[ -z "$rid" || -z "$fire_at" ]]; then
    add_unevaluated 8 notification_tap setup_failed \
      "SETUP FAILED (not a product failure): no reminder was seeded, or the scheduler armed nothing for it, so there was never a notification to tap. The app answered: ${seeded:-<nothing>}"
    return
  fi

  # Background the app first: the case is about a notification the user
  # receives and taps, not about a foreground event.
  adb_shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  local posted=0 fire_s deadline
  fire_s=$(( fire_at / 1000 ))
  deadline=$(( fire_s + TAP_POST_GRACE_S ))
  while (( $(date +%s) < deadline )); do
    posted="$(notification_has "$label")"
    [[ "$posted" -ge 1 ]] && break
    sleep 5
  done

  if [[ "$posted" -lt 1 ]]; then
    harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null
    add_result 8 notification_tap false \
      "the seeded reminder never posted, so the tap could not be tested (armed=$armed for $fire_at, waited ${TAP_POST_GRACE_S}s past it; POST_NOTIFICATIONS $perm). If the permission reads false, the alarm fires and the framework discards the notification — see constraint 12."
    return
  fi

  adb_shell cmd statusbar expand-notifications >/dev/null 2>&1
  sleep 3
  if ! tap_node "$label"; then
    adb_shell cmd statusbar collapse >/dev/null 2>&1
    harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null
    add_unevaluated 8 notification_tap unreachable \
      "UNREACHABLE (not a product failure): the notification posted (dumpsys sees it) but no node carrying it was found in the expanded shade, so nothing could be tapped. This is an instrument limit — there is no adb command that invokes a notification's content intent — not a statement about the tap handler."
    return
  fi

  local ready=0
  for _ in $(seq 1 15); do
    if app_is_foreground; then ready=1; break; fi
    sleep 2
  done

  # One dump, reused: each is a round trip, and re-reading between assertions
  # would let the screen change underneath them.
  local screen tz t12 t24 shows_time=0
  screen="$(ui_text)"
  tz="$(adb_shell getprop persist.sys.timezone 2>/dev/null | tr -d '\r')"
  # Both renderings, because the screen formats with `toLocaleString` and the
  # hour is not zero-padded in every locale — observed "Aug 3, 2:22 PM" from a
  # `hour: "2-digit"` format. Asserting one spelling would fail a correct
  # screen on a device set to the other.
  t12="$(TZ="${tz:-UTC}" date -d "@$fire_s" '+%-I:%M %p' 2>/dev/null)"
  t24="$(TZ="${tz:-UTC}" date -d "@$fire_s" '+%H:%M' 2>/dev/null)"
  { [[ -n "$t12" ]] && printf '%s' "$screen" | grep -qF "$t12"; } && shows_time=1
  { [[ -n "$t24" ]] && printf '%s' "$screen" | grep -qF "$t24"; } && shows_time=1

  local shows_name=0 shows_dosage=0 shows_actions=0
  printf '%s' "$screen" | grep -qF "$name"        && shows_name=1
  printf '%s' "$screen" | grep -qF "1 tablet"     && shows_dosage=1
  printf '%s' "$screen" | grep -qF "Mark Taken"   && shows_actions=1

  local inst inst_id
  inst="$(harness instance-at "$rid" "$fire_at")"
  inst_id="$(printf '%s' "$inst" | grep -oE 'instance_at=[^ ]+' | head -1 | cut -d= -f2)"

  harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null
  adb_shell cmd statusbar collapse >/dev/null 2>&1

  if (( shows_name && shows_dosage && shows_actions && shows_time )) &&
     [[ -n "$inst_id" && "$inst_id" != none ]]; then
    add_result 8 notification_tap true \
      "tapped the posted notification and landed on the dose screen, pre-filled: name and dosage rendered from the seeded row, scheduled time shown as '${t12}' (device tz ${tz:-unknown}), and the screen minted its instance at exactly ${fire_at} (${inst}). Armed by rescheduleAll from SQLite, not by a synthetic trigger. POST_NOTIFICATIONS $perm"
  else
    add_result 8 notification_tap false \
      "tap did not land on a correctly pre-filled dose screen: name=$shows_name dosage=$shows_dosage actions=$shows_actions time=$shows_time (expected '$t12' or '$t24' in tz ${tz:-unknown}), instance-at ${fire_at}: ${inst}. app_foregrounded=$ready posted=$posted"
  fi
}

# Criterion 9: offline, dose logs accumulate in `sync_queue`; back online, they
# sync within 60s.
#
# The measurement is taken from the SERVER, not from the app's own
# `sync_status = 'synced'` column: that column is written by the very code path
# under test, so a worker that marked rows synced without pushing them would
# pass its own audit. `convex_has_local_id` reads the deployment directly.
#
# Two clocks are recorded and they are not interchangeable. The criterion says
# "when back online", so the budget is measured from the moment the DEVICE has
# a default network again — the radios take several seconds to reassociate
# after airplane mode, and charging that to the sync worker would be measuring
# the phone's modem. The interval from the airplane-mode toggle is reported
# beside it so the stricter number is never hidden.
#
# The offline premise is asserted rather than assumed: if the row is already on
# the server before the reconnect, nothing was ever queued and the case has
# measured nothing, so it says so instead of passing.
case9_offline_queue_drain() {
  if ! fresh_app_process; then
    add_unevaluated 9 offline_queue_drain setup_failed \
      "SETUP FAILED (not a product failure): the app process could not be restarted, so this case would have run against a process that may already be holding the database locked. Nothing was measured."
    return
  fi
  require_reachable 9 offline_queue_drain || return
  require_signed_in 9 offline_queue_drain || return
  ensure_convex_reverse
  if ! convex_reachable; then
    add_unevaluated 9 offline_queue_drain no_backend \
      "NO BACKEND (instrument, not product): the Convex deployment could not be read from this machine with \`npx convex data\`, so arrival could not be verified server-side and this case was never run. Start the deployment the device points at (\`npx convex dev\` from packages/convex for a local one) and re-run."
    return
  fi

  harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null

  # An hour out on purpose. This case answers the dose through the screen and
  # never needs the alarm to fire; a nearer occurrence would post a
  # notification into the middle of the measurement.
  local label seeded rid fire_at
  label="drain-$(date +%s)"
  # `harness_once` for the same reason as case 8: a retried write double-seeds.
  seeded="$(harness_once seed-due-reminder "$label" 3600000 "$HARNESS_SLOW_BUDGET_S")"
  rid="$(printf '%s' "$seeded" | grep -oE 'reminder_id=[0-9a-fA-F-]+' | head -1 | cut -d= -f2)"
  fire_at="$(printf '%s' "$seeded" | grep -oE 'fire_at=[0-9]+' | head -1 | cut -d= -f2)"
  if [[ -z "$rid" || -z "$fire_at" ]]; then
    add_unevaluated 9 offline_queue_drain setup_failed \
      "SETUP FAILED (not a product failure): no reminder was seeded, so there was no dose to answer. The app answered: ${seeded:-<nothing>}"
    return
  fi

  airplane_on
  if ! wait_for_network none 60; then
    airplane_off
    add_unevaluated 9 offline_queue_drain unreachable \
      "UNREACHABLE (not a product failure): the device still had a default network 60s after \`cmd connectivity airplane-mode enable\`, so it was never offline and the case would have measured nothing."
    return
  fi

  # Answer the dose through the real screen, offline. Constraint 13: the URL is
  # quoted through `deep_link` or the device shell eats it at the `&`.
  deep_link "taskrx:///dose/log?medicationId=${rid}&scheduledTime=${fire_at}"
  sleep 6
  if ! tap_node "Mark Taken"; then
    airplane_off
    harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null
    add_unevaluated 9 offline_queue_drain unreachable \
      "UNREACHABLE (not a product failure): the dose screen never rendered a Mark Taken button while offline, so no dose was answered and there was nothing to queue."
    return
  fi
  sleep 4

  # `completed=`, never the first row: answering a dose leaves two instances at
  # the pairing (the screen re-loads after the write and mints a fresh pending
  # one), and reading whichever sorted first reported a recorded dose as
  # unrecorded. The completed row is also the one worth tracking to the server —
  # it is the dose the user actually answered.
  local inst inst_id
  inst="$(harness instance-at "$rid" "$fire_at")"
  inst_id="$(printf '%s' "$inst" | grep -oE 'completed=[^ ]+' | head -1 | cut -d= -f2)"
  if [[ -z "$inst_id" || "$inst_id" == none ]]; then
    airplane_off
    harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null
    add_result 9 offline_queue_drain false \
      "the dose was not recorded locally while offline, so nothing could accumulate: instance-at ${fire_at} reported ${inst}. Offline writes must land in SQLite first; this is the half of the criterion that does not involve the network at all."
    return
  fi

  local queued dead
  queued="$(harness queue-count)"
  dead="$(printf '%s' "$queued" | grep -oE 'queue_dead=[0-9]+' | head -1 | cut -d= -f2)"
  queued="$(printf '%s' "$queued" | grep -oE 'queue_pending=[0-9]+' | head -1 | cut -d= -f2)"
  if [[ "${queued:-0}" -lt 1 ]]; then
    airplane_off
    harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null
    add_result 9 offline_queue_drain false \
      "the answered dose left nothing in sync_queue while offline (queue_pending=${queued:-unknown}), so there is no record to drain when the network returns — an offline answer would be lost."
    return
  fi

  if convex_has_local_id "$inst_id"; then
    airplane_off
    harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null
    add_unevaluated 9 offline_queue_drain setup_failed \
      "SETUP FAILED (not a product failure): instance ${inst_id} was already on the Convex deployment while the device had no default network, so it never accumulated offline and the reconnect could not have been what delivered it. Nothing was measured. Check that the deployment \`npx convex data\` reads is the one the device pushes to."
    return
  fi

  local t0 connected_at=0 arrived_at=0 deadline net
  t0=$(date +%s)
  airplane_off
  # Budget plus slack: the poll must be able to OVERRUN the budget, or a sync
  # that took 61s would be indistinguishable from one that never happened.
  deadline=$(( t0 + SYNC_BUDGET_S + 60 ))
  while (( $(date +%s) < deadline )); do
    if (( connected_at == 0 )); then
      net="$(default_network)"
      [[ -n "$net" && "$net" != "none" ]] && connected_at=$(date +%s)
    fi
    if convex_has_local_id "$inst_id"; then
      arrived_at=$(date +%s)
      break
    fi
    sleep 3
  done

  local drained
  drained="$(harness queue-count | grep -oE 'queue_pending=[0-9]+' | head -1 | cut -d= -f2)"
  harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null

  if (( arrived_at == 0 )); then
    add_result 9 offline_queue_drain false \
      "the dose answered offline never reached Convex: ${inst_id} was still absent $(( $(date +%s) - t0 ))s after the network came back (budget ${SYNC_BUDGET_S}s), with queue_pending=${drained:-unknown} queue_dead=${dead:-unknown}. The rows accumulated correctly; the drain is what did not happen."
    return
  fi

  local since_connect since_toggle
  since_toggle=$(( arrived_at - t0 ))
  since_connect=$(( connected_at == 0 ? since_toggle : arrived_at - connected_at ))

  if (( since_connect <= SYNC_BUDGET_S )) && [[ "${drained:-1}" == "0" ]]; then
    add_result 9 offline_queue_drain true \
      "accumulated offline then drained on reconnect: the dose was answered with no default network, queued (queue_pending=$queued) and verifiably absent from Convex; after the network returned it arrived in ${since_connect}s (${since_toggle}s from the airplane-mode toggle, budget ${SYNC_BUDGET_S}s) and the queue drained to 0. Verified server-side by localId ${inst_id}, not from the app's own sync_status. queue_dead=${dead:-0}"
  else
    add_result 9 offline_queue_drain false \
      "arrived, but not within the criterion: ${since_connect}s after the network returned (${since_toggle}s from the toggle, budget ${SYNC_BUDGET_S}s), queue_pending after=${drained:-unknown} (expected 0), queue_dead=${dead:-unknown}, localId ${inst_id}"
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
    8) case8_notification_tap ;;
    9) case9_offline_queue_drain ;;
    *) echo "unknown case: $1" >&2; return 2 ;;
  esac
}

usage() {
  cat <<'EOF'
notification-harness.sh — adb-driven notification test harness

  --list          List cases
  --purge         Hard-delete every row cases 8 and 9 seeded (Convex, then device)
  --case N        Run case N (1..9)
  --all           Run cases 1-7: the local-only delivery baseline
  --auth          Run cases 8-9: need a signed-in app and a reachable Convex.
                  Prefer `--case 8` then `--case 9` as separate invocations —
                  case 8 fires a notification, after which the app's SQLite
                  writes fail with "database is locked" and case 9 cannot set
                  itself up. See constraint 6a.
  --output PATH   JSON report destination

`--all` deliberately stops at 7. Cases 1-7 assert delivery against a fresh,
signed-out install, which is what lets any device produce a baseline; cases 8
and 9 assert the signed-in tree and report themselves unevaluated without a
session, so including them would make every baseline run incomplete.

Env: TASKRX_PKG ANDROID_SERIAL METRO_URL VERBOSE=1 SKIP_DOZE=1 SKIP_REBOOT=1

Exit codes — a case has three fates, so do not read exit != 0 as "broken":
  0  every case ran and passed
  1  at least one case RAN and FAILED. A product verdict.
  2  no product failures, but at least one case was never evaluated, or a
     startup precondition was not met. Either way no trustworthy verdict was
     produced. In the report those cases carry "pass": null and an "outcome"
     of "unreachable" (device present, app not driveable) or "no_device".

Before running:
  adb reverse tcp:8081 tcp:8081
  (cd apps/mobile && npx expo start --dev-client)
  Ensure the device is UNLOCKED.
EOF
}

# Remove every row cases 8 and 9 created, from both sides.
#
# They seed a real reminder and answer a real dose, and both push to Convex, so
# a device used for these cases accumulates fabricated medications and — the
# part that matters — fabricated `reminder_instances`. Adherence is computed
# from exactly those rows. `cleanup-seeded` runs inside each case and only soft
# deletes, which is right there and not enough over time.
#
# CONVEX FIRST, then the device. In the other order the device's next pull can
# land between the two steps and write the reminders straight back down.
purge_seeded() {
  local convex_out device_out
  echo "purging seeded rows from Convex..."
  # Pull the two counts out by name rather than trying to isolate "the JSON".
  # The CLI interleaves npm notices, an ExperimentalWarning and a convex.json
  # warning with the result, echoes the `{}` argument, and pretty-prints the
  # return across several lines — so both "the first {...}" and "the last line"
  # are wrong, and each was wrong in the direction of reporting success. Reading
  # the named fields is also order-independent, which the JSON's key order is
  # not.
  local purged_r purged_i
  convex_out="$( (cd "$repo_root/packages/convex" && npx convex run harnessData:purgeSeeded '{}' 2>&1) | tr -d '\n ' )"
  purged_r="$(printf '%s' "$convex_out" | grep -oE '"reminders":[0-9]+' | tail -1 | cut -d: -f2)"
  purged_i="$(printf '%s' "$convex_out" | grep -oE '"instances":[0-9]+' | tail -1 | cut -d: -f2)"
  if [[ -n "$purged_r" && -n "$purged_i" ]]; then
    printf '  convex: reminders=%s instances=%s\n' "$purged_r" "$purged_i"
  else
    printf '  convex: FAILED — %s\n' "$(printf '%s' "$convex_out" | tail -c 300)" >&2
    echo "  not touching the device: purging it while the rows still exist on" >&2
    echo "  Convex would let the next pull write them straight back down." >&2
    return 2
  fi

  echo "purging seeded rows from the device..."
  if ! device_attached; then
    echo "  device: no device attached — Convex is purged, the device is not." >&2
    return 2
  fi
  device_out="$(harness purge-seeded x 0 "$HARNESS_SLOW_BUDGET_S")"
  if [[ -z "$device_out" ]]; then
    echo "  device: the app did not answer. Is it signed in and reachable?" >&2
    return 2
  fi
  # `parseHarnessSpec` falls back to `pending-count` for an action it does not
  # know, so a dev client still serving a bundle from before this action existed
  # answers cheerfully with a notification count — which looks like a successful
  # purge of nothing. Measured, on the first real run of this flag. Recognise
  # the wrong answer and say what it means.
  if printf '%s' "$device_out" | grep -q "pending_count="; then
    echo "  device: the app answered a DIFFERENT action ($device_out), which means" >&2
    echo "  its bundle predates \`purge-seeded\` and the fallback in" >&2
    echo "  parseHarnessSpec took over. NOTHING was purged from the device." >&2
    echo "  Restart the app so it reloads from Metro, then re-run --purge." >&2
    return 2
  fi
  printf '  device: %s\n' "$device_out"
  case "$device_out" in
    *"signed_in=false"*)
      echo "  the app is signed out, so nothing was purged from it." >&2
      return 2 ;;
  esac
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
8  notification_tap         [signed in] Tapping the posted notification opens /dose/log pre-filled
9  offline_queue_drain      [signed in] Airplane mode: doses queue, then reach Convex on reconnect
EOF
}

# ── Main ───────────────────────────────────────────────────────────────────
#
# Everything below runs only when this file is EXECUTED, not when it is
# sourced. `scripts/verify-harness-guards.sh` sources it to call
# `device_attached`, `pending_dump` and `require_reachable` directly, which is
# the only way to assert what they do with no device attached — the exact
# condition under which this script previously invented a device state (see
# constraint 10). Without this guard, sourcing runs the argument parser, prints
# usage and exits, so the check had to slice the file at a line number and
# broke whenever anything above moved.
if [[ "${BASH_SOURCE[0]}" != "${0}" ]]; then
  return 0
fi

command -v adb >/dev/null 2>&1 || { echo "adb not found on PATH" >&2; exit 2; }
[[ $# -eq 0 ]] && { usage; exit 2; }

output="$default_output"
cases_to_run=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --list) list_cases; exit 0 ;;
    --purge) purge_seeded; exit $? ;;
    --case) cases_to_run+=("$2"); shift 2 ;;
    --all) cases_to_run=(1 2 3 4 5 6 7); shift ;;
    --auth) cases_to_run+=(8 9); shift ;;
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

# Grant POST_NOTIFICATIONS ONCE, here, before the app is brought up — never
# from inside a case.
#
# Two reasons, and the second is the one that cost a run. Every delivery case
# (1, 3, 4, 8) silently reports "NOT posted" without it (constraint 12), so it
# belongs to the rig rather than to any one case. And `pm grant` RESTARTS the
# app when it changes a permission: issued mid-case it killed the process while
# SQLite was in use, and the next write then failed with
# "Error code : database is locked" — which surfaced as case 8 reporting SETUP
# FAILED with no seed. Granting before `wait_for_app_ready` means the restart,
# if any, happens before anything is holding the database.
grant_post

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
unevaluated_count=0
for r in "${results[@]}"; do
  if printf '%s' "$r" | grep -q '"outcome":"pass"'; then
    pass_count=$((pass_count + 1))
  elif printf '%s' "$r" | grep -q '"outcome":"fail"'; then
    fail_count=$((fail_count + 1))
  else
    # `unreachable` or `no_device`. Counted apart from failures on purpose:
    # rolling them in is what let a missing cable read as broken delivery.
    unevaluated_count=$((unevaluated_count + 1))
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
  printf '  "summary": { "passed": %d, "failed": %d, "unevaluated": %d },\n  "cases": [\n' \
    "$pass_count" "$fail_count" "$unevaluated_count"
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

# Three exit codes for the three fates, so a caller can tell "the product is
# broken" from "we did not find out". Previously both were 1, which is how an
# unplugged cable came back looking like a delivery regression.
#
#   0  every case ran and passed
#   1  at least one case ran and FAILED — a product verdict
#   2  no product failures, but at least one case was never evaluated. Also the
#      startup-precondition code, and for the same reason: no trustworthy
#      verdict was produced.
if (( fail_count > 0 )); then
  echo "$fail_count case(s) failed" >&2
  (( unevaluated_count > 0 )) && echo "$unevaluated_count case(s) were never evaluated" >&2
  exit 1
fi
if (( unevaluated_count > 0 )); then
  echo "$unevaluated_count case(s) were never evaluated — no product failures, but this run is INCOMPLETE" >&2
  exit 2
fi
echo "all cases passed"
exit 0
