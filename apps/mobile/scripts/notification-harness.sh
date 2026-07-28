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
# Learned by running against a Pixel 8 Pro on Android 17 (API 37). Six constraints.
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
#  6. A CRASHING ROUTE CAN BLOCK EVERYTHING. In a dev build, an unhandled
#     render error raises a full-screen LogBox overlay that swallows all
#     subsequent deep links, so every case silently reports nothing. If the
#     harness suddenly stops responding, check logcat for a render error
#     before suspecting the deep-link format.
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
# Env: TASKRX_PKG ANDROID_SERIAL METRO_URL VERBOSE=1 SKIP_DOZE=1 SKIP_REBOOT=1
#
# Exit: 0 all passed | 1 a case failed | 2 precondition failure

set -uo pipefail

PKG="${TASKRX_PKG:-com.taskrx.mobile}"
METRO_URL="${METRO_URL:-http://localhost:8081}"
APP_READY_TIMEOUT_S=180
HARNESS_SETTLE_S=8

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

alarm_lines()      { adb_shell dumpsys alarm 2>/dev/null | grep -A3 "walarm\*:${PKG}" || true; }
alarm_count()      { adb_shell dumpsys alarm 2>/dev/null | grep -c "walarm\*:${PKG}" || true; }
alarm_is_exact()   { alarm_lines | grep -c "window=0" || true; }
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
  grant_exact
  harness cancel-all >/dev/null
  harness schedule boot-test 900000 >/dev/null
  local before after deadline
  before="$(alarm_count)"
  adb_run reboot >/dev/null 2>&1
  sleep 10
  deadline=$(( $(date +%s) + 180 ))
  while (( $(date +%s) < deadline )); do
    [[ "$(adb_shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
    sleep 3
  done
  # The reboot dropped the adb reverse tunnel with it. Restore it now, or every
  # subsequent case fails "app never became ready" for a Metro-connectivity
  # reason that looks like an app fault.
  ensure_reverse
  # POLL, do not sleep a fixed grace. sys.boot_completed=1 does NOT mean the
  # BOOT_COMPLETED broadcast has reached this app: the framework fans that
  # broadcast out to every registered receiver in turn, and a phone with many
  # installed apps takes a while to get to ours. Measured on a Pixel 8 Pro /
  # Android 17: RebootBroadcastReceiver started ~40s after boot_completed, so
  # the previous fixed 20s grace reported a FALSE NEGATIVE ("alarms NOT
  # re-registered") for a boot receiver that was simply not called yet.
  local reg_deadline
  reg_deadline=$(( $(date +%s) + 180 ))
  after=0
  while (( $(date +%s) < reg_deadline )); do
    after="$(alarm_count)"
    [[ "$after" -ge 1 ]] && break
    sleep 5
  done
  local waited_s=$(( 180 - (reg_deadline - $(date +%s)) ))
  # Leave the app usable for whatever case runs next: after a reboot the
  # process is gone and the dev client has not reconnected to Metro, so a
  # following case would fail its harness call for reasons this case caused.
  wait_for_app_ready >/dev/null 2>&1 || true
  if [[ "$after" -ge 1 ]]; then
    add_result 5 boot_reschedule true "alarms re-registered after reboot (before=$before after=$after; receiver landed ~${waited_s}s after sys.boot_completed)"
  else
    add_result 5 boot_reschedule false "alarms NOT re-registered within 180s of boot (before=$before after=$after). Check that RECEIVE_BOOT_COMPLETED is granted and the package is not in the stopped state."
  fi
}

case6_stress_horizon() {
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
  printf '{\n  "timestamp": "%s",\n  "package": "%s",\n' "$(date -u -Iseconds)" "$PKG"
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
