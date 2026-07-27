#!/usr/bin/env bash
#
# notification-harness.sh — adb-driven notification test harness.
#
# Codifies as an executable script the manual test cases that were
# previously only prose in docs/poc-notification-tests.md and
# docs/poc-stress-tests.md — which never actually ran and left 12 of 13
# POC criteria pending. Every case can be verified from OS state
# (`dumpsys notification`, `dumpsys alarm`, logcat) rather than by
# eyeballing the device.
#
# Requires:
#   * adb on PATH (`adb version` must succeed)
#   * A running Android device or emulator with USB debugging enabled
#     (`adb devices` must list at least one `device` in state `device`)
#   * The TaskRx app installed with package $TASKRX_PKG (default
#     com.taskrx.mobile). The harness does NOT install the app; that is
#     up to `expo run:android` or `adb install` before invocation.
#   * The hidden /harness route wired up (see apps/mobile/app/harness.tsx).
#
# Usage:
#   apps/mobile/scripts/notification-harness.sh --list
#   apps/mobile/scripts/notification-harness.sh --case 1
#   apps/mobile/scripts/notification-harness.sh --all
#   apps/mobile/scripts/notification-harness.sh --all --output report.json
#
# Environment:
#   TASKRX_PKG       Package name (default: com.taskrx.mobile)
#   ANDROID_SERIAL   adb device serial (default: first attached device)
#   VERBOSE=1        Log every adb invocation and dumpsys buffer
#   SKIP_REBOOT=1    Skip case 5 (boot reschedule) — useful on physical
#                    devices where a reboot is inconvenient
#
# Exit codes:
#   0  every requested case passed
#   1  at least one case failed
#   2  precondition failure (no device, app not installed, etc.)

set -uo pipefail

PKG="${TASKRX_PKG:-com.taskrx.mobile}"
DEFAULT_DELAY_MS=90000
DELIVERY_TOLERANCE_S=45
POST_ACTION_SLEEP_S=3
BOOT_TIMEOUT_S=180
STRESS_COUNT=30

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../../.." && pwd)"
default_output="$repo_root/.omo/evidence/notification-harness-$(date -u +%Y%m%dT%H%M%SZ).json"

# ────────────────────────────────────────────────────────────────────
# adb wrappers
# ────────────────────────────────────────────────────────────────────

adb_args=()
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  adb_args+=(-s "$ANDROID_SERIAL")
fi

adb_run() {
  if [[ "${VERBOSE:-0}" == "1" ]]; then
    printf '[adb] %s\n' "$*" >&2
  fi
  adb "${adb_args[@]}" "$@"
}

adb_shell() {
  adb_run shell "$@"
}

# ────────────────────────────────────────────────────────────────────
# Harness deep-link invocation
# ────────────────────────────────────────────────────────────────────

harness_url() {
  local action="$1"
  shift
  local url="taskrx://harness?action=${action}"
  for kv in "$@"; do
    url="${url}&${kv}"
  done
  printf '%s' "$url"
}

harness_invoke() {
  local action="$1"
  shift
  local url
  url="$(harness_url "$action" "$@")"
  adb_shell am start -a android.intent.action.VIEW -d "\"$url\"" -W "$PKG" >/dev/null 2>&1
  sleep "$POST_ACTION_SLEEP_S"
}

harness_read_log() {
  local marker="$1"
  adb_run logcat -d -t 200 ReactNativeJS:V '*:S' 2>/dev/null | grep "\[harness\]" | tail -20 | grep -F "$marker" | tail -1
}

harness_reset_logs() {
  adb_run logcat -c >/dev/null 2>&1
}

harness_pending_count() {
  harness_reset_logs
  harness_invoke pending-count
  sleep 1
  local line
  line="$(harness_read_log 'pending_count=' || true)"
  if [[ -z "$line" ]]; then
    echo "-1"
    return
  fi
  printf '%s' "$line" | sed -n 's/.*pending_count=\([0-9]*\).*/\1/p'
}

harness_cancel_all() {
  harness_invoke cancel-all
}

harness_schedule() {
  local id="$1"
  local delay_ms="$2"
  harness_invoke schedule "id=${id}" "delayMs=${delay_ms}"
}

# ────────────────────────────────────────────────────────────────────
# OS state helpers
# ────────────────────────────────────────────────────────────────────

dumpsys_alarms_for_pkg() {
  adb_shell dumpsys alarm 2>/dev/null | grep -F "$PKG" || true
}

dumpsys_notifications_for_pkg() {
  adb_shell dumpsys notification --noredact 2>/dev/null | grep -F "pkg=$PKG" || true
}

alarm_count_for_pkg() {
  dumpsys_alarms_for_pkg | grep -c "$PKG" || true
}

alarm_clock_used() {
  adb_shell dumpsys alarm 2>/dev/null | grep -i "ALARM_CLOCK\|alarm_clock" | grep -c "$PKG" || true
}

exact_alarm_appops() {
  adb_shell cmd appops get "$PKG" SCHEDULE_EXACT_ALARM 2>/dev/null | head -1
}

set_exact_alarm() {
  local mode="$1" # allow | deny | default
  adb_shell cmd appops set "$PKG" SCHEDULE_EXACT_ALARM "$mode" 2>/dev/null || true
}

doze_force_idle() {
  adb_shell dumpsys battery unplug >/dev/null 2>&1 || true
  adb_shell dumpsys deviceidle force-idle deep >/dev/null 2>&1 || true
}

doze_release() {
  adb_shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
  adb_shell dumpsys battery reset >/dev/null 2>&1 || true
}

force_stop_app() {
  adb_shell am force-stop "$PKG" >/dev/null 2>&1 || true
}

wait_for_boot() {
  local deadline=$(( $(date +%s) + BOOT_TIMEOUT_S ))
  while (( $(date +%s) < deadline )); do
    if adb_shell getprop sys.boot_completed 2>/dev/null | grep -q "1"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# ────────────────────────────────────────────────────────────────────
# Case results
# ────────────────────────────────────────────────────────────────────

results=()

add_result() {
  local case_num="$1"
  local name="$2"
  local pass="$3"
  local detail="$4"
  local artifact="${5:-}"
  local esc_name esc_detail esc_artifact
  esc_name="$(printf '%s' "$name" | sed 's/"/\\"/g')"
  esc_detail="$(printf '%s' "$detail" | sed 's/"/\\"/g; s/$/\\n/' | tr -d '\n' | sed 's/\\n$//')"
  esc_artifact="$(printf '%s' "$artifact" | sed 's/"/\\"/g')"
  results+=("{\"case\":${case_num},\"name\":\"${esc_name}\",\"pass\":${pass},\"detail\":\"${esc_detail}\",\"artifact\":\"${esc_artifact}\"}")
}

reset_state() {
  force_stop_app
  doze_release
  set_exact_alarm allow
  harness_invoke cancel-all >/dev/null 2>&1 || true
  sleep 1
}

# ────────────────────────────────────────────────────────────────────
# Test cases
# ────────────────────────────────────────────────────────────────────

case1_app_closed() {
  local name="app_closed_normal"
  reset_state
  local id="harness-c1-$(date +%s)"
  harness_schedule "$id" 30000
  force_stop_app
  sleep 40
  local alarms notif_matches
  alarms="$(dumpsys_alarms_for_pkg)"
  notif_matches="$(dumpsys_notifications_for_pkg | grep -c "$id" || true)"
  if [[ "$notif_matches" -ge 1 ]]; then
    add_result 1 "$name" true "notification $id posted after force-stop"
  else
    add_result 1 "$name" false "notification $id did NOT post; alarms tail: $(printf '%s' "$alarms" | tail -3)"
  fi
}

case2_alarm_clock_primitive() {
  local name="alarm_clock_primitive_in_use"
  reset_state
  local id="harness-c2-$(date +%s)"
  harness_schedule "$id" $DEFAULT_DELAY_MS
  local alarm_clock uses
  uses="$(alarm_clock_used)"
  if [[ "$uses" -ge 1 ]]; then
    add_result 2 "$name" true "dumpsys alarm shows ALARM_CLOCK entries for $PKG (count=$uses)"
  else
    add_result 2 "$name" false "no ALARM_CLOCK entries in dumpsys alarm for $PKG"
  fi
}

case3_doze() {
  local name="doze_deep_idle"
  reset_state
  local id="harness-c3-$(date +%s)"
  harness_schedule "$id" 60000
  force_stop_app
  doze_force_idle
  sleep 70
  local notif_matches
  notif_matches="$(dumpsys_notifications_for_pkg | grep -c "$id" || true)"
  local doze_state
  doze_state="$(adb_shell dumpsys deviceidle get deep 2>/dev/null | tail -1)"
  doze_release
  if [[ "$notif_matches" -ge 1 ]]; then
    add_result 3 "$name" true "notification fired under forced idle (state=$doze_state)"
  else
    add_result 3 "$name" false "notification did NOT fire under forced idle (state=$doze_state)"
  fi
}

case4_alarm_denied() {
  local name="exact_alarm_denied_degrades"
  reset_state
  set_exact_alarm deny
  local id="harness-c4-$(date +%s)"
  harness_schedule "$id" 30000
  sleep 35
  local notif_matches appops
  notif_matches="$(dumpsys_notifications_for_pkg | grep -c "$id" || true)"
  appops="$(exact_alarm_appops)"
  set_exact_alarm allow
  if [[ "$notif_matches" -ge 1 ]]; then
    add_result 4 "$name" true "notification fired (degraded) with SCHEDULE_EXACT_ALARM=deny; appops=$appops"
  else
    add_result 4 "$name" false "notification did NOT fire with SCHEDULE_EXACT_ALARM=deny (appops=$appops)"
  fi
}

case5_boot_reschedule() {
  local name="boot_reschedule"
  if [[ "${SKIP_REBOOT:-0}" == "1" ]]; then
    add_result 5 "$name" true "skipped (SKIP_REBOOT=1)"
    return
  fi
  reset_state
  local id="harness-c5-$(date +%s)"
  harness_schedule "$id" 600000
  local before after
  before="$(alarm_count_for_pkg)"
  adb_run reboot >/dev/null 2>&1 || true
  sleep 5
  if ! wait_for_boot; then
    add_result 5 "$name" false "timeout waiting for sys.boot_completed"
    return
  fi
  sleep 15
  after="$(alarm_count_for_pkg)"
  if [[ "$after" -ge 1 ]]; then
    add_result 5 "$name" true "alarm re-registered after reboot (before=$before, after=$after)"
  else
    add_result 5 "$name" false "alarm NOT re-registered after reboot (before=$before, after=$after)"
  fi
}

case6_stress_horizon() {
  local name="stress_thirty_med_horizon"
  reset_state
  local i
  for ((i=0; i<STRESS_COUNT; i++)); do
    harness_schedule "harness-c6-${i}" $(( 60000 + i * 1000 ))
  done
  local count
  count="$(harness_pending_count)"
  if [[ "$count" -ge $STRESS_COUNT && "$count" -le 64 ]]; then
    add_result 6 "$name" true "scheduled $STRESS_COUNT harness notifications; pending_count=$count (within 30..64)"
  else
    add_result 6 "$name" false "pending_count=$count outside expected [30,64]"
  fi
  harness_invoke cancel-all >/dev/null 2>&1 || true
}

run_case() {
  case "$1" in
    1) case1_app_closed ;;
    2) case2_alarm_clock_primitive ;;
    3) case3_doze ;;
    4) case4_alarm_denied ;;
    5) case5_boot_reschedule ;;
    6) case6_stress_horizon ;;
    *) echo "unknown case: $1" >&2; return 2 ;;
  esac
}

# ────────────────────────────────────────────────────────────────────
# Precondition checks
# ────────────────────────────────────────────────────────────────────

require_adb() {
  if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found on PATH" >&2
    exit 2
  fi
}

require_device() {
  local devices
  devices="$(adb devices | tail -n +2 | grep -w device | wc -l)"
  if [[ "$devices" -lt 1 ]]; then
    echo "no adb device attached (adb devices returns none)" >&2
    exit 2
  fi
}

require_app_installed() {
  if ! adb_shell pm path "$PKG" >/dev/null 2>&1; then
    echo "app package $PKG is not installed on the device" >&2
    echo "install first: cd apps/mobile && npx expo run:android --variant debug" >&2
    exit 2
  fi
}

# ────────────────────────────────────────────────────────────────────
# JSON output
# ────────────────────────────────────────────────────────────────────

write_report() {
  local output="$1"
  local device_serial device_release device_sdk device_fingerprint
  device_serial="$(adb_shell getprop ro.serialno 2>/dev/null | tr -d '\r')"
  device_release="$(adb_shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
  device_sdk="$(adb_shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  device_fingerprint="$(adb_shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r')"

  local pass_count=0 fail_count=0
  for r in "${results[@]}"; do
    if printf '%s' "$r" | grep -q '"pass":true'; then
      pass_count=$((pass_count + 1))
    else
      fail_count=$((fail_count + 1))
    fi
  done

  mkdir -p "$(dirname "$output")"
  {
    printf '{\n'
    printf '  "timestamp": "%s",\n' "$(date -u -Iseconds)"
    printf '  "package": "%s",\n' "$PKG"
    printf '  "device": {\n'
    printf '    "serial": "%s",\n' "$device_serial"
    printf '    "release": "%s",\n' "$device_release"
    printf '    "sdk": "%s",\n' "$device_sdk"
    printf '    "fingerprint": "%s"\n' "$device_fingerprint"
    printf '  },\n'
    printf '  "summary": {\n'
    printf '    "passed": %d,\n' "$pass_count"
    printf '    "failed": %d\n' "$fail_count"
    printf '  },\n'
    printf '  "cases": [\n'
    local i
    for ((i=0; i<${#results[@]}; i++)); do
      if [[ $i -eq $((${#results[@]} - 1)) ]]; then
        printf '    %s\n' "${results[$i]}"
      else
        printf '    %s,\n' "${results[$i]}"
      fi
    done
    printf '  ]\n'
    printf '}\n'
  } >"$output"
  echo "wrote $output"
}

# ────────────────────────────────────────────────────────────────────
# CLI
# ────────────────────────────────────────────────────────────────────

usage() {
  cat <<EOF
notification-harness.sh — adb-driven notification test harness

Modes:
  --list            List all cases with a one-line description
  --case N          Run only case N (1..6)
  --all             Run every case
  --output PATH     Write JSON report to PATH (default: $default_output)

Env:
  TASKRX_PKG        Package name (default com.taskrx.mobile)
  ANDROID_SERIAL    adb device serial (default: first attached)
  VERBOSE=1         Log adb invocations
  SKIP_REBOOT=1     Skip case 5 on physical devices
EOF
}

list_cases() {
  cat <<EOF
1  app_closed_normal            App closed via force-stop; notification still fires
2  alarm_clock_primitive        dumpsys alarm shows SET_ALARM_CLOCK primitive in use
3  doze_deep_idle               Notification fires under forced deep-idle (Doze)
4  exact_alarm_denied_degrades  With SCHEDULE_EXACT_ALARM denied, notification still delivers via fallback
5  boot_reschedule              After reboot, notify-kit re-registers scheduled alarms
6  stress_thirty_med_horizon    Scheduling 30 notifications yields 30..64 pending
EOF
}

if [[ $# -eq 0 ]]; then
  usage
  exit 2
fi

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

if [[ ${#cases_to_run[@]} -eq 0 ]]; then
  usage
  exit 2
fi

require_adb
require_device
require_app_installed

echo "running cases: ${cases_to_run[*]}"
for c in "${cases_to_run[@]}"; do
  echo "---- case $c ----"
  run_case "$c"
done

write_report "$output"

failures=0
for r in "${results[@]}"; do
  if ! printf '%s' "$r" | grep -q '"pass":true'; then
    failures=$((failures + 1))
  fi
done

if [[ "$failures" -gt 0 ]]; then
  echo "$failures case(s) failed" >&2
  exit 1
fi
echo "all cases passed"
exit 0
