#!/usr/bin/env bash
#
# verify-harness-guards.sh — asserts that the notification harness reports a
# MISSING DEVICE as a missing device.
#
# Why this exists
# ---------------
# `notification-harness.sh` is the only evidence behind several criteria in
# `docs/poc-results.md`, and every `adb_shell` in it swallows stderr and yields
# an empty string when the device is gone. That is indistinguishable, at the
# call site, from a device that answered with nothing — so a caller that reads
# emptiness as a fact about the device states something it cannot know.
#
# It happened. During the issue #6 verification the phone dropped off USB
# mid-run and the harness recorded, into a JSON evidence file:
#
#   "the device rebooted into the lock screen and was never unlocked within
#    300s, so Android never broadcast ACTION_BOOT_COMPLETED (user 0 stayed
#    RUNNING_LOCKED)"
#
# — four specific claims about a device that was not attached — and blamed the
# empty `dumpsys alarm` on a renamed section header, sending the reader off to
# diff Android dump formats when the cable had come out. That file had to be
# deleted by hand. See issue #19 and constraint 10 in the harness header.
#
# Why it is executable rather than a source scanner
# -------------------------------------------------
# The harness's own header notes that its re-entrancy invariant "has now been
# evaded four separate ways ... because a scanner can only recognise the shapes
# someone thought to describe". So this runs the functions instead of reading
# them. `notification-harness.sh` guards its main block behind a BASH_SOURCE
# check specifically so it can be sourced here.
#
# Why it needs no device, ever
# ----------------------------
# It points `ANDROID_SERIAL` at a serial that cannot exist, so `adb` reports no
# such device regardless of what is physically plugged in. The checks are
# therefore deterministic on a developer machine with a phone attached, on CI
# with none, and in the exact mid-run-disconnect state that caused the defect.
set -uo pipefail

export ANDROID_SERIAL="verify-harness-guards-no-such-device"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HARNESS="$HERE/notification-harness.sh"

# SKIP rather than fail without adb. This is part of the repo's verification
# gate (`npm run verify:harness`), which must stay green on a machine or CI runner with no Android SDK — the rest of
# the repo does not need one. Failing here would make the whole gate depend on
# a tool only device work requires, which is a bigger regression than the one
# this file guards against.
command -v adb >/dev/null 2>&1 || {
  echo "verify-harness-guards: SKIPPED — adb is not on PATH, so the harness's" >&2
  echo "  no-device guards cannot be exercised. Install the Android platform" >&2
  echo "  tools to run this check." >&2
  exit 0
}

# shellcheck source=/dev/null
source "$HARNESS" || { echo "could not source $HARNESS" >&2; exit 2; }

fails=0
ok()  { printf '  PASS  %s\n' "$1"; }
bad() { printf '  FAIL  %s\n' "$1"; fails=$((fails + 1)); }

echo "verify-harness-guards: asserting a missing device is reported as one"

# 1 — the primitive. Checked for EXISTENCE first: a missing function also exits
# non-zero, which would read as "correctly reported no device" and let the
# pre-fix harness pass. Found by running these checks against the old script.
if ! declare -F device_attached >/dev/null; then
  bad "device_attached is not defined"
elif device_attached; then
  bad "device_attached claims a device is attached when none is"
else
  ok "device_attached reports no device"
fi

# 2 — the alarm-store read must not blame the section whitelist for a dump that
# is empty because nothing is attached.
msg="$(pending_dump 2>&1 >/dev/null)"
case "$msg" in
  *"NO DEVICE"*)            ok "pending_dump names the missing device" ;;
  *"renamed or reordered"*) bad "pending_dump still blames a renamed section header" ;;
  *)                        bad "pending_dump emitted an unexpected message: $msg" ;;
esac

# 3 — the case-level guard. This is the one that wrote the false verdict.
results=()
require_reachable 5 boot_reschedule >/dev/null 2>&1
recorded="${results[0]:-}"
if [[ -z "$recorded" ]]; then
  bad "require_reachable recorded no result at all"
else
  case "$recorded" in
    *'"pass":null'*) ok "an unevaluated case records pass:null, not a verdict" ;;
    *'"pass":false'*) bad "an unevaluated case still records pass:false" ;;
    *) bad "unexpected pass field: $recorded" ;;
  esac
  case "$recorded" in
    *'"outcome":"no_device"'*) ok "outcome distinguishes no_device" ;;
    *) bad "outcome is not no_device: $recorded" ;;
  esac
  # The heart of it: say nothing about a phone that is not there.
  case "$recorded" in
    *"lock screen"*|*"RUNNING_LOCKED"*|*"isKeyguardShowing"*|*"never unlocked"*)
      bad "still asserts a device state with no device attached" ;;
    *) ok "asserts nothing about the keyguard or unlock state" ;;
  esac
fi

# 4 — the choke point. Cases 1, 2 and 3 never call `require_reachable`, and the
# four that do call it only on entry, so a device lost part-way through used to
# reach `add_result … false` and score a product FAIL on readings from nothing.
# A failure must be unassertable when there is no device to have failed.
results=()
add_result 1 killed_app_delivery false "notification NOT posted within 75s" >/dev/null 2>&1
recorded="${results[0]:-}"
case "$recorded" in
  *'"outcome":"no_device"'*) ok "add_result refuses to record a FAIL with no device" ;;
  *'"outcome":"fail"'*)      bad "add_result still records a product FAIL with no device" ;;
  *)                         bad "add_result produced an unexpected record: $recorded" ;;
esac

# A PASS is left alone: it cannot be manufactured out of silence, because every
# passing assertion had to read a real value to be made.
results=()
add_result 1 killed_app_delivery true "posted after am kill" >/dev/null 2>&1
case "${results[0]:-}" in
  *'"outcome":"pass"'*) ok "add_result still records a genuine PASS unchanged" ;;
  *)                    bad "add_result mangled a passing result: ${results[0]:-<none>}" ;;
esac

# 5 — the hang. `harness_once` ends in `adb logcat`, which BLOCKS waiting for a
# device rather than failing, so with no device it froze the whole run instead
# of returning. Every case funnels through here.
start=$SECONDS
out="$(harness_once pending-count guardcheck 0 2>/dev/null)"
elapsed=$(( SECONDS - start ))
if (( elapsed > 5 )); then
  bad "harness_once took ${elapsed}s with no device — it is still blocking on adb"
elif [[ -n "$out" ]]; then
  bad "harness_once returned output with no device attached: $out"
else
  ok "harness_once returns immediately and empty with no device"
fi

# 5b — the signed-in precondition, which cases 8 and 9 gate on. It asks the app
# a question, and with no device the answer is silence — which must not be read
# as "the app is signed out". That would be the same shape of false verdict as
# the keyguard claim above: a statement about a session on a phone that is not
# attached, and one that sends the reader off to sign in on it.
results=()
require_signed_in 8 notification_tap >/dev/null 2>&1
recorded="${results[0]:-}"
if [[ -z "$recorded" ]]; then
  bad "require_signed_in recorded no result at all"
else
  case "$recorded" in
    *'"outcome":"no_device"'*) ok "require_signed_in names the missing device" ;;
    *'"outcome":"signed_out"'*) bad "require_signed_in blames the session with no device: $recorded" ;;
    *) bad "require_signed_in produced an unexpected record: $recorded" ;;
  esac
  case "$recorded" in
    *'"pass":null'*) ok "a signed-out/absent device records pass:null, not a verdict" ;;
    *) bad "require_signed_in recorded a verdict: $recorded" ;;
  esac
fi

# 5c — case 9 takes the radios down, and `restore_device` is what brings them
# back on an interrupted run. It is trapped on EXIT/INT/TERM, so the one thing
# it must never do is exit non-zero part-way and skip the rest: a phone left in
# airplane mode by a cancelled test is silent (adb is over USB) and is the most
# user-hostile thing this script can leave behind.
if ! declare -F airplane_off >/dev/null; then
  bad "airplane_off is not defined"
elif ! grep -q 'airplane_off' <<<"$(declare -f restore_device)"; then
  bad "restore_device does not restore airplane mode"
elif restore_device >/dev/null 2>&1; then
  ok "restore_device restores airplane mode and survives a missing device"
else
  bad "restore_device exited non-zero with no device — the EXIT trap would stop early"
fi

# 5d — the two task-Tier cases, run end to end with nothing attached.
#
# Checked by RUNNING them rather than by asserting they call `require_reachable`,
# for the reason this file exists at all: the invariant has been evaded by
# shapes nobody thought to describe. Both read counts back out of the app, and
# with no device those reads are empty strings — which is the input that
# produced the false verdict this file was written after.
#
# The timing half is specific to case 10 and not hypothetical. It polls for a
# notification to a deadline minutes out, and `notification_has` answers 0 for
# an absent device exactly as it does for one that has not posted yet — so a
# case that entered that loop with nothing attached would spin out its whole
# budget before recording anything, inside a gate that is supposed to be
# device-free and fast. The same shape as the `adb logcat` block in check 5.
#
# Case 12 joins them, and it is the one with the sharpest edge: it REVOKES
# POST_NOTIFICATIONS before it measures anything, and its "the app never asked"
# branch is the loudest FAIL text in this file. With no device, `pm revoke` is a
# no-op that reports nothing and the dialog poll finds nothing — the two inputs
# that would otherwise compose into "THE APP DOES NOT READ THE PLATFORM" about a
# phone nobody plugged in, written into an evidence artifact. It also polls to a
# 180s deadline, which is the same spin-out shape case 10 has.
# Case 13 joins for a reason of its own. It is a REPRODUCTION and is expected to
# FAIL on a real device until issue #23 is fixed, so it is the case most likely
# to be read quickly and believed. With no device its probes come back empty and
# its delivery poll would spin to a deadline minutes out — and "REPRODUCED — the
# database is unwritable" is not a sentence to write about a phone nobody
# plugged in.
for probe in "10 case10_task_tier_delivery" "11 case11_task_flood_preserves_doses" \
             "12 case12_fresh_install_asks_to_notify" \
             "13 case13_db_after_notification"; do
  set -- $probe
  probe_n="$1"; probe_fn="$2"
  if ! declare -F "$probe_fn" >/dev/null; then
    bad "$probe_fn is not defined"
    continue
  fi
  results=()
  start=$SECONDS
  "$probe_fn" >/dev/null 2>&1
  elapsed=$(( SECONDS - start ))
  recorded="${results[0]:-}"
  if [[ -z "$recorded" ]]; then
    bad "$probe_fn recorded no result at all"
    continue
  fi
  # `pass:null` and not `pass:false`. Both the entry probe and `add_result`'s
  # choke point enforce this, which is belt and braces on purpose: these two
  # cases are the ones whose FAIL text names a missing dose alarm, and that is
  # the last sentence in this repository that should ever be written about a
  # phone nobody plugged in.
  case "$recorded" in
    *'"pass":null'*) ok "case $probe_n records pass:null with no device" ;;
    *) bad "case $probe_n reached a verdict with no device: $recorded" ;;
  esac
  if (( elapsed > 10 )); then
    bad "case $probe_n took ${elapsed}s with no device — it is polling something that will never answer"
  else
    ok "case $probe_n returns promptly with no device"
  fi
done

# 6 — the report has to PARSE. `detail` is built from dumpsys output, which is
# arbitrary text this script does not control, so a backslash in it must be
# escaped before the quote or the evidence file is unreadable. Both writers
# previously escaped only the quote. An unparseable artifact is a lost run.
results=()
add_unevaluated 9 escaping_probe no_device 'trailing backslash \ and a "quote" together' >/dev/null 2>&1
recorded="${results[0]:-}"
if command -v python3 >/dev/null 2>&1; then
  if printf '%s' "$recorded" | python3 -c 'import json,sys; json.load(sys.stdin)' >/dev/null 2>&1; then
    ok "a result containing a backslash and a quote still parses as JSON"
  else
    bad "result is not valid JSON — escaping is wrong: $recorded"
  fi
else
  case "$recorded" in
    *'\\\\'*) ok "backslash is escaped (python3 absent, checked textually)" ;;
    *)        bad "backslash is not escaped: $recorded" ;;
  esac
fi

# 7 — the harness is a DEBUG-build facility. The deep link and the code behind it are
# the whole of the security carve-out (the route works signed out, is linked from no
# UI, and its actions are local-only), and what keeps that from widening a released
# app's surface is that none of it exists there. Checked on the sources, since the
# claim is about which source set holds each piece: the `taskrx` scheme filter lives
# only in src/debug, the real DeviceHarness only in src/debug, and the release
# stand-in handles nothing. A built release APK is checked the same way by
# `./gradlew :app:processReleaseManifest` - this needs no Android SDK.
android_root="$(cd "$HERE/.." && pwd)"
if grep -q 'android:scheme="taskrx"' "$android_root/app/src/debug/AndroidManifest.xml" 2>/dev/null; then
  ok "the taskrx deep-link filter is declared in the debug manifest"
else
  bad "the debug manifest does not declare the taskrx deep-link filter"
fi
if grep -rq 'android:scheme' "$android_root/app/src/main/AndroidManifest.xml" "$android_root/app/src/release" 2>/dev/null; then
  bad "a URL scheme filter exists outside src/debug - the harness route would ship in release"
else
  ok "no URL scheme filter exists in the main or release source sets"
fi
if [[ -f "$android_root/app/src/debug/kotlin/com/taskrx/mobile/harness/HarnessRunner.kt" ]] \
   && ! find "$android_root/app/src/main" "$android_root/app/src/release" -name 'HarnessRunner.kt' 2>/dev/null | grep -q .; then
  ok "the harness actions exist only in the debug source set"
else
  bad "the harness actions are missing from src/debug or present outside it"
fi
if grep -q 'fun handle(.*): Boolean = false' "$android_root/app/src/release/kotlin/com/taskrx/mobile/harness/DeviceHarness.kt" 2>/dev/null; then
  ok "the release DeviceHarness handles nothing"
else
  bad "the release DeviceHarness is missing or does something"
fi

echo
if (( fails == 0 )); then
  echo "verify-harness-guards: all checks passed"
  exit 0
fi
echo "verify-harness-guards: $fails check(s) failed" >&2
exit 1
