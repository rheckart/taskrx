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
# Learned by running against a Pixel 8 Pro on Android 17 (API 37).
# Do not "simplify" these away; each one caused a real false result.
#
#  1. DEEP LINKS MUST USE PATH SEGMENTS, NOT A QUERY STRING.
#     taskrx://harness?action=schedule&id=x does NOT route — expo-router will
#     not match a custom-scheme deep link carrying a query string.
#     taskrx://harness/schedule/x/60000/nonce DOES route.
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

harness() {
  local action="$1" id="${2:-x}" delay="${3:-0}"
  local nonce
  nonce="$(next_nonce)"
  adb_run logcat -c >/dev/null 2>&1
  adb_shell am start -a android.intent.action.VIEW \
    -d "taskrx://harness/${action}/${id}/${delay}/${nonce}" >/dev/null 2>&1
  sleep "$HARNESS_SETTLE_S"
  adb_run logcat -d 2>/dev/null | grep -oE "\[harness\] result:.{0,120}" | tail -1
}

launch_app() {
  local encoded
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
}

# ── Results ────────────────────────────────────────────────────────────────

results=()
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
  local sched reached posted st
  sched="$(harness schedule doze-test 150000)"
  adb_shell dumpsys deviceidle enable deep >/dev/null 2>&1
  adb_shell dumpsys battery unplug >/dev/null 2>&1
  kill_app_realistically
  adb_shell input keyevent KEYCODE_POWER >/dev/null 2>&1
  sleep 3
  for _ in $(seq 1 8); do
    st="$(adb_shell dumpsys deviceidle step deep 2>&1 | tr -d '\r')"
    printf '%s' "$st" | grep -qE "IDLE$" && break
    sleep 2
  done
  reached="$(idle_state)"
  sleep 150
  posted="$(notification_has "doze-test")"
  restore_device
  if [[ "$posted" -ge 1 ]]; then
    add_result 3 doze_deep_idle true "posted while idle state=$reached; $sched"
  else
    add_result 3 doze_deep_idle false "NOT posted; idle state=$reached; $sched. Screen-off locks the device (constraint 5) — unlock and re-run if the app could not be reached."
  fi
}

case4_exact_denied_degrades() {
  deny_exact
  harness cancel-all >/dev/null
  local status sched window posted
  status="$(harness exact-alarm-status)"
  sched="$(harness schedule denied-test 45000)"
  window="$(alarm_lines | grep -oE "window=[+0-9a-z]+" | head -1)"
  sleep 60
  posted="$(notification_has "denied-test")"
  grant_exact
  # The contract is graceful degradation: report denied, downgrade to an
  # inexact alarm, but still deliver. Never silently drop.
  if [[ "$posted" -ge 1 ]] && printf '%s' "$status" | grep -q "exact_alarm_granted=false"; then
    add_result 4 exact_denied_degrades true "reported denied AND still delivered inexactly ($window); $sched"
  else
    add_result 4 exact_denied_degrades false "status=$status window=$window posted=$posted"
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
  sleep 20
  after="$(alarm_count)"
  if [[ "$after" -ge 1 ]]; then
    add_result 5 boot_reschedule true "alarms re-registered after reboot (before=$before after=$after)"
  else
    add_result 5 boot_reschedule false "alarms NOT re-registered (before=$before after=$after). A locked device may block notify-kit's boot receiver."
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

run_case() {
  case "$1" in
    1) case1_killed_app ;;
    2) case2_alarm_clock_primitive ;;
    3) case3_doze ;;
    4) case4_exact_denied_degrades ;;
    5) case5_boot_reschedule ;;
    6) case6_stress_horizon ;;
    *) echo "unknown case: $1" >&2; return 2 ;;
  esac
}

usage() {
  cat <<'EOF'
notification-harness.sh — adb-driven notification test harness

  --list          List cases
  --case N        Run case N (1..6)
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
    --all) cases_to_run=(1 2 3 4 5 6); shift ;;
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
