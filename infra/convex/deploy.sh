#!/usr/bin/env bash
# Deploy packages/convex to the self-hosted backend. Does not touch .env.local.
# deploy.sh                 deploy (sets CLERK_JWT_ISSUER_DOMAIN first)
# deploy.sh <subcommand..>  any other convex command, e.g. `deploy.sh run reminders:list`
#                           (--env-file is added for you)
set -euo pipefail
. "$(dirname "$0")/convex-env.sh"
cd "$HERE/../../packages/convex"
if [ $# -eq 0 ]; then
  # auth.config.ts reads this at deploy time, so set it first.
  if [ -n "${CLERK_JWT_ISSUER_DOMAIN:-}" ]; then
    npx convex env set --env-file "$CONVEX_ENV_FILE" CLERK_JWT_ISSUER_DOMAIN "$CLERK_JWT_ISSUER_DOMAIN"
  fi
  npx convex deploy --yes --env-file "$CONVEX_ENV_FILE"
else
  sub="$1"; shift
  if [ "$sub" = env ]; then sub2="$1"; shift; npx convex env "$sub2" --env-file "$CONVEX_ENV_FILE" "$@"
  else npx convex "$sub" --env-file "$CONVEX_ENV_FILE" "$@"; fi
fi
