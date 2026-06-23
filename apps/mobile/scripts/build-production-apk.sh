#!/usr/bin/env bash
set -euo pipefail

# build-production-apk.sh
# Builds a production-like Android APK via EAS for sideload testing.
# Run from the repo root: ./apps/mobile/scripts/build-production-apk.sh
#
# Defaults to the "preview" profile (APK, internal distribution).
# Pass "--production" as the first argument to build a Play Store-ready AAB.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOBILE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

PROFILE="preview"

if [[ "${1:-}" == "--production" ]]; then
  PROFILE="production"
fi

log_info() {
  echo -e "${GREEN}[taskrx]${NC} $1"
}

log_warn() {
  echo -e "${YELLOW}[taskrx]${NC} $1"
}

log_error() {
  echo -e "${RED}[taskrx]${NC} $1"
}

log_step() {
  echo -e "${BLUE}[taskrx]${NC} $1"
}

# 1. Check Node / npm
if ! command -v node &>/dev/null; then
  log_error "Node.js is not installed or not in PATH."
  exit 1
fi

if ! command -v npm &>/dev/null; then
  log_error "npm is not installed or not in PATH."
  exit 1
fi

log_info "Node: $(node --version)"
log_info "npm: $(npm --version)"

# 2. Check JDK
if ! command -v javac &>/dev/null; then
  log_warn "javac not found. EAS Build runs in the cloud, but local troubleshooting may need JDK."
else
  log_info "JDK: $(javac -version 2>&1 | head -n 1)"
fi

# 3. Check Android SDK (optional for cloud build, required for adb install)
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  log_warn "ANDROID_HOME / ANDROID_SDK_ROOT is not set."
  log_warn "You will need adb if you plan to install the resulting APK locally."
fi

# 4. Check EAS CLI
if ! command -v npx &>/dev/null; then
  log_error "npx is not available."
  exit 1
fi

if ! npx eas --version &>/dev/null; then
  log_info "EAS CLI not found locally; installing temporarily..."
  cd "${MOBILE_DIR}"
  npm install --save-dev eas-cli
fi

log_info "EAS CLI: $(cd "${MOBILE_DIR}" && npx eas --version)"

# 5. Validate Expo project config
log_step "Validating Expo project configuration..."
cd "${MOBILE_DIR}"
npx expo config --type public

# 6. Start EAS Build
log_step "Starting EAS Build (profile: ${PROFILE})..."
BUILD_OUTPUT=$(npx eas build --platform android --profile "${PROFILE}" --non-interactive 2>&1) || {
  log_error "EAS Build failed."
  echo "${BUILD_OUTPUT}"
  exit 1
}

# 7. Report build URL
BUILD_URL=$(echo "${BUILD_OUTPUT}" | grep -oE 'https://expo\.dev/accounts/[^ ]+/builds/[^ ]+' | head -n 1 || true)

log_info "Build submitted successfully."
if [[ -n "${BUILD_URL}" ]]; then
  log_info "Build URL: ${BUILD_URL}"
fi

if [[ "${PROFILE}" == "preview" ]]; then
  log_info "When the build completes, download the APK and sideload it:"
  log_info "  adb install taskrx-preview.apk"
else
  log_info "When the build completes, download the AAB and upload it to Google Play Console."
fi
