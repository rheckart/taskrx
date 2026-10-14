# Sourced by the other scripts: loads infra/convex/.env (or .env.$CONVEX_TARGET, e.g.
# CONVEX_TARGET=vps for the VPS) and writes a private
# --env-file for the Convex CLI. That file replaces packages/convex/.env.local
# (which points at the local `convex dev` backend) without editing it.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
set -a; . "$HERE/.env${CONVEX_TARGET:+.$CONVEX_TARGET}"; set +a
export CONVEX_SELF_HOSTED_URL="${CONVEX_SELF_HOSTED_URL:-http://127.0.0.1:${PORT:-3220}}"
unset CONVEX_DEPLOYMENT
CONVEX_ENV_FILE="$(mktemp)"; chmod 600 "$CONVEX_ENV_FILE"
trap 'rm -f "$CONVEX_ENV_FILE"' EXIT
printf "CONVEX_SELF_HOSTED_URL='%s'\nCONVEX_SELF_HOSTED_ADMIN_KEY='%s'\n" \
  "$CONVEX_SELF_HOSTED_URL" "$CONVEX_SELF_HOSTED_ADMIN_KEY" > "$CONVEX_ENV_FILE"
