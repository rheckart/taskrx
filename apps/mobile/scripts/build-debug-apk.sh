#!/usr/bin/env bash
set -euo pipefail

# build-debug-apk.sh
# Builds a sideloadable debug APK for the TaskRx mobile app.
# Run from the repo root: ./apps/mobile/scripts/build-debug-apk.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOBILE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ANDROID_DIR="${MOBILE_DIR}/android"
APK_OUTPUT="${ANDROID_DIR}/app/build/outputs/apk/debug/app-debug.apk"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
  echo -e "${GREEN}[taskrx]${NC} $1"
}

log_warn() {
  echo -e "${YELLOW}[taskrx]${NC} $1"
}

log_error() {
  echo -e "${RED}[taskrx]${NC} $1"
}

# 1. Check adb
if ! command -v adb &>/dev/null; then
  log_error "adb is not installed or not in PATH."
  log_error "Install Android platform-tools and add adb to your PATH."
  exit 1
fi

log_info "adb found: $(adb --version | head -n 1)"

# 2. Check Android SDK
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  log_warn "ANDROID_HOME / ANDROID_SDK_ROOT is not set."
  log_warn "Gradle may fail if it cannot locate the Android SDK."
fi

# 3. Check for a connected device (optional but helpful)
DEVICE_COUNT=$(adb devices | grep -c "device$" || true)
if [[ "${DEVICE_COUNT}" -eq 0 ]]; then
  log_warn "No Android device detected. Connect your Pixel 8 with USB debugging enabled."
else
  log_info "Detected ${DEVICE_COUNT} Android device(s)."
fi

# 4. Ensure android/ prebuild exists
if [[ ! -d "${ANDROID_DIR}" ]]; then
  log_info "Android project not found; running expo prebuild..."
  cd "${MOBILE_DIR}"
  npx expo prebuild --no-install --platform android
fi

# 5. Run the debug build
log_info "Building debug APK with Gradle..."
cd "${ANDROID_DIR}"
./gradlew assembleDebug

# 6. Report output
if [[ -f "${APK_OUTPUT}" ]]; then
  log_info "Build succeeded."
  log_info "APK: ${APK_OUTPUT}"
  ls -lh "${APK_OUTPUT}"
else
  log_error "APK not found at expected path: ${APK_OUTPUT}"
  exit 1
fi

# 7. Optional install if a device is connected
if [[ "${DEVICE_COUNT}" -gt 0 ]]; then
  log_info "Installing APK on the connected device..."
  adb install -r "${APK_OUTPUT}"
  log_info "Install complete."
fi
