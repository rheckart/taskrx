#!/usr/bin/env bash
set -euo pipefail

# deploy-production.sh
# Stub script for deploying a signed Android App Bundle (AAB) to the Google Play
# Store internal track. This is NOT run automatically during the POC; it exists
# only to document the future release flow.
#
# Prerequisites before this script can be used:
#   1. Google Play Console project for com.taskrx.mobile
#   2. Service account with Release Manager access + JSON key
#   3. Signed AAB from EAS Build production profile
#   4. fastlane supply or Play Console API access configured
#
# Usage (future):
#   ./apps/mobile/scripts/deploy-production.sh path/to/taskrx.aab

AAB_PATH="${1:-}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() {
  echo -e "${GREEN}[taskrx]${NC} $1"
}

log_warn() {
  echo -e "${YELLOW}[taskrx]${NC} $1"
}

log_error() {
  echo -e "${RED}[taskrx]${NC} $1"
}

if [[ -z "${AAB_PATH}" ]]; then
  log_error "Usage: $0 <path-to-signed-aab>"
  exit 1
fi

if [[ ! -f "${AAB_PATH}" ]]; then
  log_error "AAB not found: ${AAB_PATH}"
  exit 1
fi

log_warn "POC stub: this script does not perform a real Play Store upload."
log_info "AAB to deploy: ${AAB_PATH}"
log_info "Target track: internal"
log_info "Release status: draft"

cat <<'EOF'

Future implementation options:

1. fastlane supply (recommended):
   cd apps/mobile
   bundle exec fastlane android deploy_internal aab:path/to/taskrx.aab

2. Google Play Developer API directly:
   Use the Android Publisher API edits.insert -> edits.bundles.upload ->
   edits.tracks.update -> edits.commit flow.

3. EAS Submit (simplest if using EAS):
   cd apps/mobile
   npx eas submit --platform android --profile production --path path/to/taskrx.aab

EOF

log_info "Deploy stub complete. No upload was performed."
