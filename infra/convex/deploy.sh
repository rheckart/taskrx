#!/usr/bin/env bash
# Deploy packages/convex to the self-hosted backend. Does not touch .env.local.
# deploy.sh                 deploy (sets CLERK_JWT_ISSUER_DOMAIN and CLERK_DESKTOP_CLIENT_ID first)
# deploy.sh <subcommand..>  any other convex command, e.g. `deploy.sh run reminders:list`
#                           (--env-file is added for you)
set -euo pipefail
. "$(dirname "$0")/convex-env.sh"
cd "$HERE/../../packages/convex"
if [ $# -eq 0 ]; then
  # auth.config.ts reads these at deploy time, so set them first.
  for var in CLERK_JWT_ISSUER_DOMAIN CLERK_DESKTOP_CLIENT_ID; do
    if [ -n "${!var:-}" ]; then
      npx convex env set --env-file "$CONVEX_ENV_FILE" "$var" "${!var}"
    fi
  done
  npx convex deploy --yes --env-file "$CONVEX_ENV_FILE"
else
  sub="$1"; shift
  if [ "$sub" = env ]; then sub2="$1"; shift; npx convex env "$sub2" --env-file "$CONVEX_ENV_FILE" "$@"
  else npx convex "$sub" --env-file "$CONVEX_ENV_FILE" "$@"; fi
fi
