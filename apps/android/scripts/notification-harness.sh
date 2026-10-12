#!/usr/bin/env bash
#
# notification-harness.sh — adb-driven notification test harness.
#
# ── THE KOTLIN PORT ────────────────────────────────────────────────────────
# This is apps/mobile/scripts/notification-harness.sh, moved here with as few changes
# as the new app allowed. It drives `com.taskrx.mobile` (same applicationId) over
# the same hidden deep link, `taskrx:///harness/<action>/<id>/<delayMs>/<nonce>`,
# and reads the same three logcat lines, `[harness] action=…`, `[harness] result:`
# and `[harness] error:`. Every case keeps its verdict logic and its meaning.
#
# What changed, and why each is forced rather than chosen:
#   * There is no Metro and no dev client. The APK is built and installed with
#     Gradle, and the app is launched through its own MAIN activity.
#   * The deep link exists in DEBUG builds only (apps/android/app/src/debug): the
#     manifest filter and the code behind it are absent from a release APK.
#   * The notify-kit specifics are now the app's own: alarms are registered by
#     AlarmClockGateway (AlarmManager.setAlarmClock, received by AlarmReceiver),
#     tasks by WorkManager, boot re-arm by RearmReceiver, and the "has the app
#     asked" record is the `prompt_shown` key in shared_prefs/taskrx.notifications.xml.
#   * The harness actions arm through the PRODUCTION scheduler from real (rig-
#     tagged) Reminder rows, not by writing to the OS directly. That is why the
#     alarm store, `dumpsys notification` and the boot case read exactly as before,
#     and why a foreground cannot sweep a rig-armed alarm away (constraint 11).
#   * Case 7 asserts the three soonest of the rolling horizon the scheduler
#     arms; the Expo scheduleForReminder(count=3) API it used does not exist.
#   * The answer screen's button reads "Mark taken" (cases 8 and 9).
#
# PRECONDITION, unchanged in spirit from "the app must be signed out": cases
# 1-7 and 10-11 assume an install with NO real Reminders. `cancel-all` and the
# foreground pass act on whatever is in the database, so a device with real
# data would have its own alarms counted. Use a fresh install or `pm clear`.
# Until phase 5 (sign-in) there is no session, so `auth-status` answers
# `signed_in=false` and cases 8, 9, 12 and 13 report SIGNED OUT, unevaluated.
#
# Codifies as an executable script the manual test cases that were previously
# only prose in docs/poc-notification-tests.md and docs/poc-stress-tests.md —
# which never actually ran, leaving 12 of 13 POC criteria pending. Every case
# is verified from OS state (dumpsys alarm, dumpsys notification, logcat)
# rather than by eyeballing the device.
#
# ── EMPIRICALLY-VALIDATED CONSTRAINTS ──────────────────────────────────────
# Learned by running against a Pixel 8 Pro on Android 17 (API 37). Fourteen
# constraints: 1-7 below, then 8 (deep Doze is blocked by a pending
# wake-from-idle alarm), 9 (`dumpsys alarm` is mostly NOT pending alarms), 10
# (silence from adb is not a fact about the device), 11 (in the signed-in tree
# `rescheduleAll` sweeps away any alarm it did not derive), 12 (an ungranted
# POST_NOTIFICATIONS silently discards every delivery), 13 (`adb shell`
# re-parses the URL you pass it), and 14 (`pm` cannot restore the never-asked
# permission state, and asking the app about it is what spends it), which are
# documented inline at their point of use.
# Do not "simplify" these away; each one caused a real false result.
#
#  1. DEEP LINKS MUST CONTAIN A REAL PATH, NOT JUST A HOST.
#     taskrx://harness?action=schedule has no path segments, so it carries no
#     action. (The Kotlin app accepts the host form `taskrx://harness/<action>/…`
#     as well, but this script sends the three-slash form it always has.)
#     Both of these DO route (three slashes = real path):
#       taskrx:///harness/schedule/x/60000/nonce
#       taskrx:///harness/pending-count/x/0/nonce?extra=1
#     Query strings are fine once a path is present. This script uses path
#     segments purely because they are simpler to build and read.
#     (Earlier debugging wrongly blamed query strings; the real cause of
#     those failures was a LogBox overlay blocking navigation — see 5. There is
#     no LogBox in the Kotlin app.)
#
#  2. USE `am kill`, NEVER `am force-stop`, TO MODEL "APP CLOSED".
#     force-stop sets the package's stopped=true flag, which makes Android
#     drop broadcasts to its receivers. AlarmManager still fires but
#     AlarmReceiver never receives it — a FALSE NEGATIVE.
#     Measured: with force-stop the notification never posted; with `am kill`
#     after backgrounding it posted 34ms after the target time.
#     `am kill` only affects backgrounded processes, hence the HOME keyevent.
#
#  3. THE HARNESS ROUTE ONLY RE-DISPATCHES WHEN ITS PARAMS CHANGE.
#     DeviceHarness ignores a link whose path equals the previous one (an
#     activity re-created with the same intent must not run a write twice), so
#     every invocation must carry a unique nonce or it is a silent no-op.
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
#  6. A CRASHING APP BLOCKS EVERYTHING. (Expo era: a render error raised a
#     LogBox overlay that swallowed every deep link. Kotlin analogue: a crash
#     dialog or a crash loop does the same.) If the harness suddenly stops
#     responding, check `adb logcat -b crash` before suspecting the deep-link
#     format.
#
#  6a. (EXPO-ERA OBSERVATION, kept until re-measured on the Kotlin app.)
#     A LONG DEV-CLIENT SESSION CAN LOCK THE DATABASE. After editing app
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
# Requires: adb on PATH; one attached device; the DEBUG build installed
# (`cd apps/android && ./gradlew :app:installDebug`). No Metro, no `adb reverse`
# for the app itself (case 9 still reverses the Convex port).
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
#   apps/android/scripts/notification-harness.sh --list
#   apps/android/scripts/notification-harness.sh --case 2
#   apps/android/scripts/notification-harness.sh --all --output report.json
#   apps/android/scripts/notification-harness.sh --case 8 --output tap.json
#   apps/android/scripts/notification-harness.sh --case 9 --output drain.json
#   apps/android/scripts/notification-harness.sh --case 12 --output permission.json
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
# Env: TASKRX_PKG ANDROID_SERIAL VERBOSE=1 SKIP_DOZE=1 SKIP_REBOOT=1
#      PERMISSION_PROMPT_TIMEOUT_S (default 180 — case 12)
#      BOOT_UNLOCK_TIMEOUT_S (default 300) BOOT_REGISTER_TIMEOUT_S (default 300)
#      BOOT_ALARM_COUNT (default 3) CONVEX_DEVICE_PORT (default 3210)
#      CONVEX_ENV_FILE (Convex CLI env file; unset = packages/convex/.env.local)
#      SYNC_BUDGET_S (default 60 — criterion 9's own wording)
#
# Case 5 is INTERACTIVE on a device with a screen lock: it reboots, then waits
# for you to unlock before it can observe anything (constraint 7).
#
# The JSON report records a `provenance` block (commit, branch, dirty,
# dirty_files) so a result can always be tied back to the code it tested. The
# commit is the anchor. The installed APK is whatever was last built, not
# necessarily this tree, so a `"dirty": true` run is evidence for the tree the
# APK was built from only if you built it just before running. Rebuild and
# reinstall first, then treat a dirty run as evidence for that tree, not for the
# named commit.
#
# Exit: 0 all passed | 1 a case RAN and FAILED (a product verdict) | 2 no
# product failures but at least one case was never evaluated, or a precondition
# was not met — either way, no trustworthy verdict. See `usage()` and the
# `add_result` / `add_unevaluated` pair: a case has three fates, not two.

set -uo pipefail

PKG="${TASKRX_PKG:-com.taskrx.mobile}"
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
# Case 12 only. How long to wait for the app to put the system permission
# dialog on screen after a cold launch. Generous because the process has to start,
# open the database and restore the session before anything asks. A short budget
# here would report "the app never asked" about an app that was still booting —
# the exact false verdict constraint 10 exists to prevent.
PERMISSION_PROMPT_TIMEOUT_S="${PERMISSION_PROMPT_TIMEOUT_S:-180}"

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
# The commit is the anchor, and `dirty` is load-bearing: against a dirty tree
# the commit alone does not identify what ran, so name the modified files too.
# (The Expo app loaded its JS from Metro, so the tree was exactly what ran; the
# Kotlin APK is whatever was last built and installed, so build it from the tree
# you intend to cite immediately before running.)
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

# Modified tracked files AND untracked ones. Untracked matters: Gradle compiles
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

# The nonce must differ between calls, and most calls run inside `$(...)`: a
# counter incremented in that subshell is lost, so every call sent `n1` and the
# app (which ignores a link whose path equals the previous one) silently dropped
# the second of two identical actions - case 9's post-reconnect `queue-count`
# came back empty as "queue_pending=unknown". Time and pid survive a subshell.
nonce_counter=0
next_nonce() { nonce_counter=$((nonce_counter + 1)); printf 'n%s%s%s' "$(date +%s%N)" "$$" "$nonce_counter"; }

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
  # `result:` OR `error:`. `DeviceHarness` catches a thrown action and logs it
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
# dead app: `am start` re-creates the task but a cold process (Application,
# Room, WorkManager) can take longer than HARNESS_SETTLE_S to answer. The call
# then returns empty and the case reports a scheduler failure that never
# happened — measured as a bogus "case 2 exact_windows=0 expected_ts=" right
# after case 1's kill. An empty result means UNREACHABLE, not "false"; relaunch
# through the launcher activity and ask exactly once more.
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

# Kept as a no-op so the cases that called it (5 and 12, after a reboot or a
# restart) read as before. The Expo app needed `adb reverse` to reach Metro and
# lost it on every reboot; the Kotlin app has no dev server. Case 9's Convex
# tunnel is separate (`ensure_convex_reverse`).
ensure_reverse() { :; }

launch_app() {
  # Wake first. Between cases the screen times out on its own, and `am start`
  # against a sleeping display does not reliably resume the activity — which
  # surfaces as a bogus "app never became ready" precondition failure rather
  # than as anything to do with the app.
  adb_shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  # The launcher activity, with no route: how a person's tap starts the app.
  # Case 12 depends on that — a launch that carried a deep link would be one the
  # app did not choose.
  adb_shell am start -n "$PKG/com.taskrx.mobile.MainActivity" >/dev/null 2>&1
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
# On Android 13+ the alarm still fires and the receiver still runs, and then the
# framework drops the notification on the floor. The app says so when it can:
#
#   W notify: notifications are off; <id> will be discarded
#
# (NotificationPoster, logcat tag `notify`.)
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
# runtime permissions, and until issue #20 the only caller of
# `requestPermissions()` was the debug screen — so a fresh install never asked,
# and this grant was papering over a product defect rather than a rig gap. The
# app now asks on the signed-in path; CASE 12 is what proves it, and it is the
# one case that must undo this grant to mean anything.
grant_post()       { adb_shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true; }
notification_permission() {
  adb_shell dumpsys package "$PKG" 2>/dev/null \
    | grep -m1 "android.permission.POST_NOTIFICATIONS: granted=" \
    | grep -oE 'granted=(true|false)' | tr -d '\r'
}

# ── Case 12 only: put the app back in the state a FRESH INSTALL is in. ───────
#
# `pm revoke` alone is not that state. It leaves the USER_SET/USER_FIXED flags
# the earlier grant or denial wrote, and with those set Android answers a
# runtime request without showing anybody a dialog — so the app would look to
# case 12 exactly as it looks after a second refusal, and "the app never asked"
# and "the app asked and was refused" would be indistinguishable. Clearing the
# flags is what makes the next request produce a real prompt.
#
# `pm clear` would be the genuine article and is what `docs/poc-results.md`
# records the defect against, but it also wipes SQLite and the Clerk session,
# and this case needs a signed-in app to have anything to be reminded about.
# The permission state it reproduces is identical; the session is not destroyed.
# Recorded as the difference it is rather than described as a fresh install.
#
# Both commands are per-package. `pm reset-permissions` takes no package and
# would reset every app on the phone, which is not a thing a test may do to
# somebody's device.
#
# ── Constraint 14: `pm` CANNOT RESTORE THE NEVER-ASKED STATE, AND ASKING THE
# APP ABOUT IT IS WHAT SPENDS IT. ───────────────────────────────────────────
#
# AND NOT EVEN THAT IS ENOUGH. The app records that it has asked in its OWN
# SharedPreferences — `taskrx.notifications.xml`, `prompt_shown` (PromptRecord) —
# and derives NOT_DETERMINED from it, because Android will not say whether a
# denial is a refusal or a question never put. No `pm` command reaches that file, so with the flag left set the app reads
# `denied` on a permission nobody has ever been asked about, and correctly
# declines to prompt: Android will not show a dialog for a spent permission.
#
# Measured, and it is how the first run of case 12 failed. The case's own setup
# raised the dialog, its next deep link brought the harness activity in front of
# it, and the flag was set — so the case then reported "THE APP NEVER ASKED"
# about an app that had asked moments earlier, in the setup, unobserved.
#
# `run-as` reaches it and works on the debug build this harness drives. This is
# what `pm clear` does wholesale; doing it by hand is what lets the session and
# SQLite survive, which the signed-in half of this case needs. Only the one key
# is removed: the same file holds the armed-alarm ledger, the snoozes and the
# user id the boot receiver re-arms for, and deleting the file would orphan them.
#
# THE APP MUST BE DEAD when this runs, or Android flushes the in-memory
# SharedPreferences back over the deletion on exit. `pm revoke` above kills the
# package, which is why the order here is not arbitrary.
NOTIFEE_PREFS="shared_prefs/taskrx.notifications.xml"
reset_post() {
  adb_shell pm revoke "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  adb_shell pm clear-permission-flags "$PKG" \
    android.permission.POST_NOTIFICATIONS user-set user-fixed >/dev/null 2>&1 || true
  # Explicitly, rather than relying on `pm revoke` above having killed the
  # package: it only does that when it CHANGES something, so on a re-run where
  # the permission is already revoked the app is left running — and a running
  # app writes its in-memory SharedPreferences back over the deletion below when
  # it exits. The deletion would appear to succeed and the flag would return.
  kill_app_realistically
  adb_run exec-out run-as "$PKG" sed -i '/name="prompt_shown"/d' "$NOTIFEE_PREFS" >/dev/null 2>&1 || true
}

# Whether the app still believes it has asked.
#
# Read from the FILE rather than by asking the app, and that is the whole point:
# every question put to the app arrives through a deep link, which mounts the
# provider, which asks — so a probe for "has the prompt been spent" would spend
# it. This is the one precondition in this case that can be checked without
# touching the thing being measured.
app_has_asked() {
  adb_run exec-out run-as "$PKG" cat "$NOTIFEE_PREFS" 2>/dev/null \
    | grep -c 'name="prompt_shown" value="true"' || true
}

# ── Case 13 only: what is actually on disk beside the database. ─────────────
#
# A `-journal` file here is a HOT JOURNAL — left behind by a connection killed
# mid-transaction — and it is the one explanation that fits the detail issue #23
# says does not fit the others: the lock reportedly survives `am kill` and a
# verified-fresh process, which no in-memory connection conflict can do. A
# `-wal`/`-shm` pair means the journal mode changed under us.
#
# `run-as` needs a debuggable build, which is what this harness drives. Prints
# names only, never contents: the database holds someone's medication history.
sqlite_files() {
  adb_run exec-out run-as "$PKG" ls databases 2>/dev/null \
    | tr -d '\r' | tr '\n' ' ' | sed 's/ *$//'
}

# The system permission dialog, matched on the permission controller's own view
# id rather than on the word "Allow" — the text is localised and the id is not,
# and a case that could only run on an English phone would report a device in
# any other language as one where the app never asked.
PERMISSION_ALLOW_ID="com.android.permissioncontroller:id/permission_allow_button"
permission_dialog_showing() { ui_dump | grep -q "$PERMISSION_ALLOW_ID"; }

# Dismiss without answering. BACK, not "Don't allow": a dismissal leaves the
# permission undetermined and still askable, where a refusal would spend the
# prompt this case is about to measure.
dismiss_permission_dialog() {
  permission_dialog_showing || return 0
  adb_shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  sleep 2
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
deep_link() { wake_screen; adb_shell am start -a android.intent.action.VIEW -d "'$1'" >/dev/null 2>&1; }

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
#
# A SLEEPING SCREEN DUMPS NOTHING. With no screen lock the display still times
# out (60s on the Pixel), and case 9 spends longer than that waiting for
# airplane mode, so its dose screen "never rendered" and its tap landed on a
# dozing display. Measured on a Pixel 8 Pro: mWakefulness=Dozing, an empty
# dump, two UNREACHABLE and two FAILs that were all the instrument. Case 8's
# shade read went the same way. Waking first costs nothing on a lit screen.
ui_dump()  { wake_screen; adb_run exec-out uiautomator dump /dev/tty 2>/dev/null | tr '>' '\n'; }
wake_screen() { adb_shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; sleep 1; }

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
  local needle="$1" line bounds x1 y1 x2 y2 try
  # Read more than once. On the Pixel a single dump missed a "Mark taken" that a
  # screenshot taken in the same second shows on screen (case 9, offline), and a
  # missed read is reported as the app's failure to render.
  for try in 1 2 3 4 5; do
    line="$(ui_dump | grep -F "$needle" | head -1)"
    [[ -n "$line" ]] && break
    sleep 2
  done
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
# scaffold a stray project instead. That file names the local `convex dev`
# backend; to audit the self-hosted one (infra/convex) instead, set
# CONVEX_ENV_FILE to an env file naming it, which the CLI reads in its place.
convex_cli() {
  (cd "$repo_root/packages/convex" && npx convex "$@" ${CONVEX_ENV_FILE:+--env-file "$CONVEX_ENV_FILE"})
}
convex_rows() {
  convex_cli data "$1" --limit "${2:-50}" 2>/dev/null
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
  # Case 12 revokes POST_NOTIFICATIONS, and every path out of it that is not the
  # happy one leaves it revoked. The rig grants it at startup, so granted IS the
  # state this script found the phone in, and restoring it is not an
  # accommodation — it is refusing to leave somebody's phone silently unable to
  # show a reminder because a test was cancelled part-way. Same reasoning as
  # `airplane_off` above, and the same silence: nothing on the device looks
  # wrong afterwards.
  grant_post
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
# cannot resume the activity while the keyguard is up, so no action runs and no
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
  # The Expo build lost its `adb reverse` tunnel to Metro with the reboot and had to
  # restore it here. `ensure_reverse` is a no-op in the Kotlin port; the call stays so
  # the sequence reads as it did.
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
  # process is gone and has not been relaunched, so a following case would
  # fail its harness call for reasons this case caused.
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

# ── Task Tier cases (10, 11) ───────────────────────────────────────────────
#
# The second Tier ADR 0003 called for and ADR 0007 chose a mechanism for. Both
# are local-only, so both belong in the `--all` baseline: a task needs no
# Clerk subject to be armed, and the whole point of the split is that it holds
# on any install.
#
# Case 10 asserts a task IS delivered and 11 asserts a dose STILL is. Neither
# alone is enough. A build that armed no tasks at all would sail through 11,
# and a build that armed every task with `SET_ALARM_CLOCK` — the pre-ADR-0003
# behaviour, and the one that leaves an evening insulin dose silently unarmed —
# would sail through 10.

# Delivery through WorkManager is DEFERRABLE by design, which is the trade the
# task Tier makes. A one-time work request with an initial delay runs at or
# after that delay, batched with whatever else the OS has queued, so a fixed
# `sleep` past the fire time is a coin flip in the way case 4 documents for
# inexact alarms. Poll well past it instead, and only call non-delivery once
# the whole budget has elapsed.
TASK_DELIVERY_SLACK_S=180

case10_task_tier_delivery() {
  require_reachable 10 task_tier_delivery || return
  # Granted deliberately. If the exact-alarm appop were denied, EVERY trigger
  # would degrade and "no alarm-clock treatment" would prove nothing about the
  # app's own choice. Under a grant, an alarm-clock registration is available
  # for the taking and the task not taking it is a decision.
  grant_exact
  harness cancel-all >/dev/null
  local out armed ids fire_at nac label posted deadline
  label="task-tier-$(date +%s)"
  out="$(harness schedule-task "$label" 90000)"
  armed="$(printf '%s' "$out" | grep -oE "task_armed=[0-9]+" | cut -d= -f2)"
  ids="$(printf '%s' "$out" | grep -oE "task_ids=[^ ]*" | cut -d= -f2)"
  fire_at="$(printf '%s' "$out" | grep -oE "fire_at=[0-9]+" | cut -d= -f2)"

  if [[ -z "$armed" || "$armed" == "0" ]]; then
    add_result 10 task_tier_delivery false "no task trigger was armed: ${out:-unreachable}"
    return
  fi

  # The task must not have registered itself as the device's next alarm clock.
  # That is the same reading case 2 uses to PROVE `setAlarmClock` for a dose,
  # so the two cases are one instrument pointed at both Kinds.
  nac="$(next_alarm_clock)"
  if printf '%s' "$nac" | grep -q "$fire_at"; then
    add_result 10 task_tier_delivery false "a task took the alarm-clock treatment: next_alarm_clock=$nac matches fire_at=$fire_at"
    return
  fi

  posted=0
  deadline=$(( $(date +%s) + 90 + TASK_DELIVERY_SLACK_S ))
  while (( $(date +%s) < deadline )); do
    posted="$(notification_has "$label")"
    (( posted >= 1 )) && break
    sleep 10
  done

  if (( posted >= 1 )); then
    harness cancel-all >/dev/null
    add_result 10 task_tier_delivery true "task notified at its time without the alarm-clock treatment; armed=$armed ids=$ids next_alarm_clock=$nac"
    return
  fi

  # ── Nothing posted. Establish WHY before calling it a delivery failure. ────
  #
  # A trigger that was RETRACTED never had the chance to fire, and reporting
  # that as "the task Tier does not deliver" is the same class of false verdict
  # as blaming a renamed dumpsys section for an absent device. Measured, on this
  # rig, before this branch existed: the case scored a product FAIL while the
  # mechanism underneath was working perfectly — WorkManager ran the job on
  # time and posted the notification when asked directly.
  #
  # The cause would be constraint 11. `MainActivity.onResume` runs
  # `rescheduleAll` on every foreground, and every deep link this case sends
  # makes exactly that transition; its cancel pass removes every trigger outside
  # the schedule-derived desired set. In the Kotlin port the task is armed from a
  # rig Reminder row that IS in SQLite, so a healthy build re-arms it rather than
  # retracting it and this branch should be unreachable — but a build in which the
  # row, the Tier or the horizon is wrong would land here, and "the trigger was
  # retracted" is a different verdict from "armed and never fired".
  #
  # `pending-ids` distinguishes the two states cheaply: the id still pending
  # with no notification is a genuine delivery failure; the id gone is the rig.
  local still auth
  still="$(harness pending-ids "$label" 0)"
  auth="$(harness auth-status "$label" 0)"
  harness cancel-all >/dev/null

  if [[ -n "$still" ]] && ! printf '%s' "$still" | grep -q "$ids"; then
    add_unevaluated 10 task_tier_delivery setup_failed \
      "the trigger was RETRACTED before its moment, so delivery was never exercised: armed $ids, and it is no longer pending. This is constraint 11 — the app answered '${auth}', and while signed in \`rescheduleAll\` runs on every foreground and cancels every trigger not derived from SQLite, which this rig-armed one is not. Run the local-only baseline against a signed-out app."
  else
    add_result 10 task_tier_delivery false "NOT posted within 90s+${TASK_DELIVERY_SLACK_S}s, and the trigger was still pending, so it was armed and did not fire; armed=$armed ids=$ids pending=${still:-unreadable}"
  fi
}

case11_task_flood_preserves_doses() {
  require_reachable 11 task_flood_preserves_doses || return
  grant_exact
  harness cancel-all >/dev/null
  local out dose_armed task_armed task_budget dose_budget flood dose_at nac
  out="$(harness schedule-task-flood "flood-$(date +%s)" 600000)"
  dose_armed="$(printf '%s' "$out" | grep -oE "dose_armed=[0-9]+" | cut -d= -f2)"
  task_armed="$(printf '%s' "$out" | grep -oE "task_armed=[0-9]+" | cut -d= -f2)"
  task_budget="$(printf '%s' "$out" | grep -oE "task_budget=[0-9]+" | cut -d= -f2)"
  dose_budget="$(printf '%s' "$out" | grep -oE "dose_budget=[0-9]+" | cut -d= -f2)"
  flood="$(printf '%s' "$out" | grep -oE "flood=[0-9]+" | cut -d= -f2)"
  dose_at="$(printf '%s' "$out" | grep -oE "dose_at=[0-9]+" | cut -d= -f2)"

  if [[ -z "$dose_armed" || -z "$task_armed" || -z "$task_budget" ||
        -z "$dose_budget" || -z "$flood" || -z "$dose_at" ]]; then
    add_result 11 task_flood_preserves_doses false "could not read the counts back: ${out:-unreachable}"
    return
  fi

  # `next-alarm-clock` naming the dose is the strongest form of the claim
  # available from outside the app: not merely that a trigger exists under the
  # dose's id, but that the OS is holding the alarm-clock registration for it
  # with the task budget completely full.
  nac="$(next_alarm_clock)"
  harness cancel-all >/dev/null

  # The two premises first, and a failure of either is reported as the case
  # proving NOTHING rather than as a product verdict. A case that concluded "the
  # dose survived" from a flood too small to have threatened it would be the
  # worst possible outcome here: a green tick against the one failure this
  # ticket exists to prevent.
  if (( flood <= dose_budget )); then
    add_result 11 task_flood_preserves_doses false "the flood ($flood) does not exceed the medication budget ($dose_budget), so a Kind-blind scheduler would have armed the dose anyway and the case proved nothing"
  elif (( task_armed < task_budget )); then
    add_result 11 task_flood_preserves_doses false "the flood did not fill the task budget, so the case proved nothing: task_armed=$task_armed budget=$task_budget"
  elif (( dose_armed < 1 )); then
    add_result 11 task_flood_preserves_doses false "A DOSE WAS LEFT UNARMED with $task_armed tasks armed — the failure ADR 0003 exists to prevent"
  elif ! printf '%s' "$nac" | grep -q "$dose_at"; then
    add_result 11 task_flood_preserves_doses false "the dose is armed but not as the next alarm clock: expected $dose_at, next_alarm_clock=$nac"
  else
    add_result 11 task_flood_preserves_doses true "$flood tasks (> the $dose_budget medication budget) filled the task Tier ($task_armed/$task_budget) and the dose still holds the alarm clock: $nac"
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
# ALARM IT DID NOT DERIVE. `MainActivity.onResume` calls it on every foreground,
# and its cancel pass removes each pending trigger outside the schedule-derived
# desired set. (Expo era: the `schedule` action armed a notification under a bare
# id, which is never in that set, so in the signed-in tree it was retracted at the
# next foreground and never fired. The Kotlin `schedule` arms from a Reminder row
# instead, so it IS in that set; the same constraint still binds anything armed
# any other way.) Measured: armed,
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

  wake_screen
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
  printf '%s' "$screen" | grep -qF "Mark taken"   && shows_actions=1

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
  if ! tap_node "Mark taken"; then
    airplane_off
    harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null
    add_unevaluated 9 offline_queue_drain unreachable \
      "UNREACHABLE (not a product failure): the dose screen never rendered a Mark taken button while offline, so no dose was answered and there was nothing to queue."
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
  # One more tap when nothing was recorded AND the button is still up: on the
  # Pixel about one tap in five never reached the app (screenshots before and
  # after show the same untouched screen, and the app logged nothing). With no
  # completed row there is nothing a second tap could answer twice.
  if [[ -z "$inst_id" || "$inst_id" == none ]] && tap_node "Mark taken"; then
    note "case 9: the first Mark taken tap recorded nothing; tapped again"
    sleep 4
    inst="$(harness instance-at "$rid" "$fire_at")"
    inst_id="$(printf '%s' "$inst" | grep -oE 'completed=[^ ]+' | head -1 | cut -d= -f2)"
  fi
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

# Issue #20: a fresh install never asked for POST_NOTIFICATIONS, so no reminder
# ever arrived.
#
# ── WHY THIS CASE COULD NOT BE ONE OF 1-11 ─────────────────────────────────
# Every existing delivery case runs under `grant_post`, which the rig issues at
# startup precisely so no case can fail for a permission the rig never granted
# (constraint 12). That grant is also what HID this defect: the app was arming
# alarms Android discarded, and nine green cases said delivery worked. The same
# was true of the debug screen — opening it requests the permission as a side
# effect, so anybody who went looking found the app working.
#
# So this case is the one that must revoke the grant, and it must never reach
# the app through a route that asks on its own behalf. It launches through the
# launcher activity, which is how a person's tap starts the app and
# carries no route, and it never opens `/debug/notifications`.
#
# ── WHAT IT ASSERTS, IN ORDER ──────────────────────────────────────────────
#   1. The OS reads `granted=false` and the APP agrees. The second half is what
#      distinguishes a real platform query from the hardcoded boolean the POC
#      shipped for exact alarms — a constant agrees in one state and cannot
#      follow the OS into the other, and this case reads it in both.
#   2. Launched cold, the app asks — with no deep link, no debug screen and no
#      tap from the harness beyond the launch itself.
#   3. After the person allows it, the OS and the app both read granted.
#   4. **A notification actually POSTS.** The criterion this case exists for is
#      not that an alarm was armed. `docs/poc-results.md` criterion #5 was a
#      genuine PASS on an armed alarm the whole time the app was delivering
#      nothing, which is exactly how a defect this size stayed invisible.
#
# Signed-in, and so not part of `--all`: step 4 seeds a real reminder and lets
# the production scheduler arm it, for the reason constraint 11 gives — in this
# tree an alarm armed any other way is swept away before it can fire.
case12_fresh_install_asks_to_notify() {
  # Before `fresh_app_process`, and not merely for tidiness: that helper ends in
  # `wait_for_app_ready`, which polls to a 180s deadline for an answer a
  # detached device will never give — so an unplugged cable would spin this case
  # out for three minutes inside a gate that must stay device-free and fast.
  # Cases 8 and 9 reach the same helper first and get away with it only because
  # nothing runs them without a device; the guard script runs this one.
  if ! device_attached; then
    add_unevaluated 12 fresh_install_asks_to_notify no_device \
      "NO DEVICE (instrument, not product): no device is attached (adb get-state is not \"device\"), so this case was never run. Nothing here is a statement about whether the app asks for permission to notify."
    return
  fi
  if ! fresh_app_process; then
    add_unevaluated 12 fresh_install_asks_to_notify setup_failed \
      "SETUP FAILED (not a product failure): the app process could not be restarted, so the prompt this case measures would have been attributed to a process that had already made its permission decision. Nothing was measured."
    return
  fi
  require_reachable 12 fresh_install_asks_to_notify || return
  require_signed_in 12 fresh_install_asks_to_notify || return
  grant_exact

  # ── 1. Back to never-asked, WITHOUT asking the app anything. ─────────────
  #
  # Nothing below reads the app's own view of the permission before the launch,
  # and that is a correction rather than an omission. The first version did, and
  # the read is what broke it: every question to the app goes through a deep
  # link, the deep link mounts the provider, the provider asks — and the next
  # deep link brings the harness activity in front of the dialog and spends it.
  # The case then measured its own setup and blamed the app.
  #
  # So the never-asked state is established from OUTSIDE the app, and verified
  # from outside it too. What the app believes is read only AFTER the dialog has
  # been answered, at which point asking costs nothing.
  reset_post

  local before_os before_flag
  before_os="$(notification_permission)"
  before_flag="$(app_has_asked)"

  if [[ "$before_os" != "granted=false" ]]; then
    add_unevaluated 12 fresh_install_asks_to_notify setup_failed \
      "SETUP FAILED (not a product failure): POST_NOTIFICATIONS could not be put back into its not-granted state — the OS still reports '${before_os:-unreadable}'. With the permission already held there is nothing for the app to ask for, so the case would have passed without exercising anything."
    return
  fi
  if [[ "$before_flag" != "0" ]]; then
    add_unevaluated 12 fresh_install_asks_to_notify setup_failed \
      "SETUP FAILED (not a product failure): the app still records that it has asked (${NOTIFEE_PREFS}, prompt_shown=true), so it reports DENIED rather than NOT_DETERMINED and the app will correctly decline to prompt for a permission Android would not show a dialog for. This is the rig failing to reproduce a fresh install, not the app failing to ask. \`run-as\` needs a debuggable build; on a release build use \`pm clear\` and sign in again."
    return
  fi

  # ── 2. Launch the way a person launches it, and wait to be asked. ────────
  #
  # `kill_app_realistically` rather than `fresh_app_process`, deliberately: that
  # helper ends in `wait_for_app_ready`, which deep-links until the app answers
  # — and every one of those links is the activity switch that cancels the very
  # dialog this case is waiting for. The launch below is the whole interaction,
  # and the dialog poll is its own readiness signal.
  kill_app_realistically
  launch_app

  local asked=0 deadline
  deadline=$(( $(date +%s) + PERMISSION_PROMPT_TIMEOUT_S ))
  while (( $(date +%s) < deadline )); do
    if ! device_attached; then break; fi
    if permission_dialog_showing; then asked=1; break; fi
    sleep 5
  done

  if (( asked == 0 )); then
    # Establish that the app was RUNNING before concluding it did not ask. A dev
    # client that never finished bundling produces no dialog for the same 180s as
    # an app that does not ask, and only one of those is a defect. Safe to probe
    # here and nowhere earlier: there is no dialog left to cancel.
    local alive
    alive="$(harness pending-count x 0 "$HARNESS_SLOW_BUDGET_S")"
    if [[ -z "$alive" ]]; then
      add_unevaluated 12 fresh_install_asks_to_notify setup_failed \
        "SETUP FAILED (not a product failure): no permission dialog appeared, and the app does not answer a deep link either — so it never came up, and its silence says nothing about whether it asks. Check Metro and \`adb reverse tcp:8081 tcp:8081\`."
      return
    fi
    add_result 12 fresh_install_asks_to_notify false \
      "THE APP NEVER ASKED. Launched cold with POST_NOTIFICATIONS not granted, its user-set/user-fixed flags cleared and the app's own prompt_shown flag cleared, no permission dialog appeared within ${PERMISSION_PROMPT_TIMEOUT_S}s — and the app was running throughout (it answered '${alive}'). This is issue #20 exactly: the alarms would still be armed, still fire on time, and Android would discard every notification with nothing on screen to say so."
    return
  fi

  # ── 3. Allow it, and check the OS and the app together. ──────────────────
  #
  # This is also where "the app reads the platform rather than a constant" is
  # settled, and the pair is the two steps rather than two readings: a hardcoded
  # `true` would never have raised the dialog in step 2, and a hardcoded `false`
  # could not report `authorized` here.
  tap_node "$PERMISSION_ALLOW_ID"
  sleep 3
  local after_os after_app
  after_os="$(notification_permission)"
  after_app="$(harness notification-status)"

  if [[ "$after_os" != "granted=true" ]]; then
    add_result 12 fresh_install_asks_to_notify false \
      "the dialog appeared and allowing it did not grant the permission: the OS still reports '${after_os:-unreadable}'. app reading: '${after_app}'"
    return
  fi
  if ! printf '%s' "$after_app" | grep -q "notifications_granted=true"; then
    add_result 12 fresh_install_asks_to_notify false \
      "the OS granted POST_NOTIFICATIONS and the app did not notice: OS ${after_os}, app '${after_app:-<nothing>}'. A person who allows the permission would go on seeing the degraded-mode warning, with reminders that in fact work."
    return
  fi

  # ── 4. And a notification actually posts. ────────────────────────────────
  harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null
  local label seeded fire_at armed posted post_deadline
  label="fresh-$(date +%s)"
  # `harness_once`, not `harness`: a retry on a write seeds a second reminder
  # under the same name. Case 8 documents this at length.
  seeded="$(harness_once seed-due-reminder "$label" "$TAP_LEAD_MS" "$HARNESS_SLOW_BUDGET_S")"
  fire_at="$(printf '%s' "$seeded" | grep -oE 'fire_at=[0-9]+' | head -1 | cut -d= -f2)"
  armed="$(printf '%s' "$seeded" | grep -oE 'armed=[0-9]+' | head -1 | cut -d= -f2)"

  if [[ -z "$fire_at" || "$fire_at" == "none" ]]; then
    add_unevaluated 12 fresh_install_asks_to_notify setup_failed \
      "SETUP FAILED (not a product failure): the permission half passed, but no reminder could be seeded, so delivery was never exercised. The app answered: ${seeded:-<nothing>}"
    return
  fi

  adb_shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  posted=0
  post_deadline=$(( fire_at / 1000 + TAP_POST_GRACE_S ))
  while (( $(date +%s) < post_deadline )); do
    posted="$(notification_has "$label")"
    (( posted >= 1 )) && break
    sleep 5
  done

  harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null

  if (( posted >= 1 )); then
    add_result 12 fresh_install_asks_to_notify true \
      "asked, was allowed, and DELIVERED. From POST_NOTIFICATIONS revoked (${before_os}), its user-set/user-fixed flags cleared and the app's prompt_shown flag cleared — a never-asked state established entirely from outside the app — a cold launch through the dev-client entry URL with no deep link and no debug screen put the system prompt on screen within ${PERMISSION_PROMPT_TIMEOUT_S}s. After Allow the OS reads ${after_os} and the app reads '${after_app}'. A reminder seeded through the production scheduler (armed=${armed} for ${fire_at}) then POSTED — not merely armed."
  else
    add_result 12 fresh_install_asks_to_notify false \
      "the permission was asked for and granted, and the notification still did not post: armed=${armed} for ${fire_at}, waited ${TAP_POST_GRACE_S}s past it. OS ${after_os}, app '${after_app}'. The permission is not the cause here — look at delivery."
  fi
}

# Issue #23: after a notification fires, every SQLite write fails with
# "database is locked".
#
# ── THIS CASE IS A REPRODUCTION, AND IT IS EXPECTED TO FAIL TODAY. ──────────
# It exists before any fix, deliberately. `docs/poc-results.md` records the lock
# from a single session with its cause GUESSED — "stated as a suspicion, not a
# diagnosis" — and a fix chosen against an anecdote cannot be shown to have
# worked. This turns the anecdote into a reading that can go green.
#
# The shape is taken from the workaround the lock already forced on this file:
# cases 8 and 9 cannot share an invocation, because case 8 fires a notification
# and case 9 then cannot write. So the reproduction is exactly that sequence,
# with the write reduced to its smallest form — a lock acquisition that leaves
# no row behind — and with the state that would explain it captured at the same
# moment rather than reasoned about afterwards.
#
# What it records either way, because a repro that only says "it failed" moves
# nobody forward:
#   * `journal_mode` and `busy_timeout`, read from the connection. Rollback
#     journal with a zero timeout means a second writer fails INSTANTLY.
#   * the SQLite directory listing, so a hot `-journal` is visible if present.
#   * the app's verbatim error.
#   * whether the notification actually posted — without that, the case has not
#     reached the state it is about, and says so instead of reporting a verdict.
#
# A PASS here means the product property holds: a write succeeds after a
# notification has fired. When that happens, `--auth` should also run 8 and 9 in
# one invocation, and the two-pass workaround in this header can go.
case13_db_after_notification() {
  if ! device_attached; then
    add_unevaluated 13 db_after_notification no_device \
      "NO DEVICE (instrument, not product): no device is attached (adb get-state is not \"device\"), so this case was never run. Nothing here is a statement about the database."
    return
  fi
  if ! fresh_app_process; then
    add_unevaluated 13 db_after_notification setup_failed \
      "SETUP FAILED (not a product failure): the app process could not be restarted, so this case would have run against a process that may ALREADY be holding the lock it is trying to reproduce — which is the one starting state that makes its verdict meaningless."
    return
  fi
  require_reachable 13 db_after_notification || return
  require_signed_in 13 db_after_notification || return
  grant_exact

  # The baseline, and it is load-bearing. If the database is already unwritable
  # before any notification has fired, then whatever this case observes later is
  # not caused by the notification and the reproduction has proved nothing.
  local before files_before
  before="$(harness db-probe x 0 "$HARNESS_SLOW_BUDGET_S")"
  files_before="$(sqlite_files)"
  if [[ -z "$before" ]]; then
    add_unevaluated 13 db_after_notification setup_failed \
      "SETUP FAILED (not a product failure): the app did not answer the probe before the notification, so there is no baseline to compare against."
    return
  fi
  # `db_writable`, NOT `db_lockable`. The first version of this case gated on a
  # lock acquisition, which passed while a real write failed in the same second —
  # so it declared a healthy baseline on a database that could not be written to,
  # and then reported the seed's failure as a setup problem. The two readings are
  # both kept because their disagreement is the diagnosis, but only the one that
  # matches what the product does may gate a verdict.
  if ! printf '%s' "$before" | grep -q "db_writable=yes"; then
    add_unevaluated 13 db_after_notification setup_failed \
      "SETUP FAILED (not a product failure): the database was ALREADY unwritable before any notification fired, so nothing this case goes on to observe can be attributed to one. The app reported: ${before} | files: ${files_before:-unreadable}. Restart the app and re-run."
    return
  fi

  harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null

  local label seeded fire_at armed
  label="dblock-$(date +%s)"
  # `harness_once`, not `harness`: a retry on a write seeds a second reminder.
  seeded="$(harness_once seed-due-reminder "$label" "$TAP_LEAD_MS" "$HARNESS_SLOW_BUDGET_S")"
  fire_at="$(printf '%s' "$seeded" | grep -oE 'fire_at=[0-9]+' | head -1 | cut -d= -f2)"
  armed="$(printf '%s' "$seeded" | grep -oE 'armed=[0-9]+' | head -1 | cut -d= -f2)"

  if [[ -z "$fire_at" || "$fire_at" == "none" ]]; then
    add_unevaluated 13 db_after_notification setup_failed \
      "SETUP FAILED (not a product failure): no reminder was seeded, so no notification was ever going to fire. The app answered: ${seeded:-<nothing>}"
    return
  fi

  # Background it: the case is about a notification arriving the way one really
  # arrives, and the headless JS context that suspicion points at only exists
  # when the app is not in front of the person.
  adb_shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  local posted=0 post_deadline
  post_deadline=$(( fire_at / 1000 + TAP_POST_GRACE_S ))
  while (( $(date +%s) < post_deadline )); do
    posted="$(notification_has "$label")"
    (( posted >= 1 )) && break
    sleep 5
  done

  if (( posted < 1 )); then
    harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null
    add_unevaluated 13 db_after_notification setup_failed \
      "SETUP FAILED (not a product failure): the seeded reminder never posted (armed=${armed} for ${fire_at}), so the app never reached the state this case is about. Nothing was measured about the database."
    return
  fi

  # The measurement. Taken through a deep link, which brings the app forward —
  # that is the same route the person takes when they tap the notification to
  # log the dose, which is the moment the lock was originally reported at.
  local after files_after
  after="$(harness db-probe x 0 "$HARNESS_SLOW_BUDGET_S")"
  files_after="$(sqlite_files)"

  harness cleanup-seeded x 0 "$HARNESS_SLOW_BUDGET_S" >/dev/null

  if [[ -z "$after" ]]; then
    add_unevaluated 13 db_after_notification setup_failed \
      "SETUP FAILED (not a product failure): the notification posted, and the app then did not answer the probe at all. Silence is not a locked database — it is an app that could not be reached. before=${before} | files after: ${files_after:-unreadable}"
    return
  fi

  if printf '%s' "$after" | grep -q "db_writable=yes"; then
    add_result 13 db_after_notification true \
      "the database is still writable after a notification fired. before=[${before}] after=[${after}] files_before=[${files_before:-unreadable}] files_after=[${files_after:-unreadable}] armed=${armed} for ${fire_at}"
  else
    add_result 13 db_after_notification false \
      "REPRODUCED — the database is unwritable after a notification fired, and was writable before it. before=[${before}] after=[${after}] files_before=[${files_before:-unreadable}] files_after=[${files_after:-unreadable}] armed=${armed} for ${fire_at}. A \`-journal\` in files_after is a hot journal and explains why the lock survives a fresh process; a journal_mode of \`delete\` with busy_timeout 0 explains why a second writer fails instantly rather than waiting."
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
    10) case10_task_tier_delivery ;;
    11) case11_task_flood_preserves_doses ;;
    12) case12_fresh_install_asks_to_notify ;;
    13) case13_db_after_notification ;;
    *) echo "unknown case: $1" >&2; return 2 ;;
  esac
}

usage() {
  cat <<'EOF'
notification-harness.sh — adb-driven notification test harness

  --list          List cases
  --purge         Hard-delete every row cases 8 and 9 seeded (Convex, then device)
  --case N        Run case N (1..13)
  --all           Run cases 1-7, 10-11: the local-only delivery baseline
  --auth          Run cases 8-9: need a signed-in app and a reachable Convex.
                  Prefer `--case 8` then `--case 9` as separate invocations —
                  case 8 fires a notification, after which the app's SQLite
                  writes fail with "database is locked" and case 9 cannot set
                  itself up. See constraint 6a.
  --output PATH   JSON report destination

`--all` deliberately skips 8, 9 and 12 rather than stopping at a number. Cases
1-7 and 10-11 assert delivery against a fresh, signed-out install, which is what
lets any device produce a baseline; 8, 9 and 12 assert the signed-in tree and
report themselves unevaluated without a session, so including them would make
every baseline run incomplete. Case 12 additionally REVOKES POST_NOTIFICATIONS,
which every other delivery case depends on the rig having granted — running it
inside the baseline would break the cases that follow it.

Env: TASKRX_PKG ANDROID_SERIAL VERBOSE=1 SKIP_DOZE=1 SKIP_REBOOT=1

Exit codes — a case has three fates, so do not read exit != 0 as "broken":
  0  every case ran and passed
  1  at least one case RAN and FAILED. A product verdict.
  2  no product failures, but at least one case was never evaluated, or a
     startup precondition was not met. Either way no trustworthy verdict was
     produced. In the report those cases carry "pass": null and an "outcome"
     of "unreachable" (device present, app not driveable) or "no_device".

Before running:
  (cd apps/android && ./gradlew :app:installDebug)   # the DEBUG build: the harness
                                                     # link does not exist in release
  Ensure the device is UNLOCKED, and that the app has no real Reminders
  (fresh install or `adb shell pm clear com.taskrx.mobile`).
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
  convex_out="$(convex_cli run harnessData:purgeSeeded '{}' 2>&1 | tr -d '\n ' )"
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
  # know, so an installed build from before this action existed
  # answers cheerfully with a notification count — which looks like a successful
  # purge of nothing. Measured, on the first real run of this flag. Recognise
  # the wrong answer and say what it means.
  if printf '%s' "$device_out" | grep -q "pending_count="; then
    echo "  device: the app answered a DIFFERENT action ($device_out), which means" >&2
    echo "  the installed build predates \`purge-seeded\` and the fallback in" >&2
    echo "  parseHarnessSpec took over. NOTHING was purged from the device." >&2
    echo "  Rebuild and reinstall the debug build, then re-run --purge." >&2
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
10 task_tier_delivery       A task notifies at its time and never registers as the next alarm clock
11 task_flood_preserves_doses  A full task budget still leaves the dose armed and holding the alarm clock
12 fresh_install_asks_to_notify [signed in] POST_NOTIFICATIONS revoked and never asked: the app asks, and then DELIVERS
13 db_after_notification    [signed in] Issue #23 repro: is SQLite still writable after a notification fired? Expected to FAIL until fixed
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
    --all) cases_to_run=(1 2 3 4 5 6 7 10 11); shift ;;
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
  echo "  (cd apps/android && ./gradlew :app:installDebug)" >&2
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

echo "waiting for the app to be ready..."
ready="$(wait_for_app_ready)" || {
  echo "app never became ready. Check:" >&2
  echo "  1. the DEBUG build is installed (the harness link is debug-only)" >&2
  echo "  2. the device is UNLOCKED — a locked device cannot foreground the app" >&2
  echo "  3. the app is not crashing: adb logcat -b crash -d" >&2
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
