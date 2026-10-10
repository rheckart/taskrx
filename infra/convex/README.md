# Self-hosted Convex (Postgres)

Convex backend + dashboard + its own Postgres (not published on the host), via
Docker. Host ports: backend `3220`, HTTP actions `3221`, dashboard `6791`
(3210/3211 belong to the local `npx convex dev` backend that the Expo app uses).

## Setup (once)

    cp .env.example .env     # fill POSTGRES_PASSWORD, INSTANCE_SECRET (openssl rand -hex 32), CLERK_JWT_ISSUER_DOMAIN
    docker compose up -d
    docker compose exec -T backend ./generate_admin_key.sh   # put the key in .env as CONVEX_SELF_HOSTED_ADMIN_KEY='...' (quoted: it contains '|')

`.env` and `backups/` are gitignored.

## Daily

    docker compose up -d / stop / down      # `down -v` DELETES the data volumes
    ./deploy.sh                             # set CLERK_JWT_ISSUER_DOMAIN, then `convex deploy`
    ./deploy.sh run reminders:list '{}' --identity '{"subject":"u","issuer":"<clerk issuer>","tokenIdentifier":"<clerk issuer>|u"}'
    ./deploy.sh env list | function-spec | ...   # any convex subcommand

`deploy.sh` hands the CLI a temporary `--env-file`, which replaces
`packages/convex/.env.local`; that file is never edited.

## Backup / restore

    ./backup.sh                       # -> backups/taskrx-<timestamp>.dump (pg_dump -Fc)
    ./restore.sh backups/<file>.dump  # stops backend, recreates the db, pg_restore, starts backend

Drilled: row written, backed up, deleted, restored, read back.

## Point the Expo app at it

A phone cannot reach `localhost`. Use the machine's LAN IP and make the backend
advertise it, then redeploy:

    # .env
    CONVEX_CLOUD_ORIGIN=http://<lan-ip>:3220
    CONVEX_SITE_ORIGIN=http://<lan-ip>:3221
    docker compose up -d
    # apps/mobile/.env
    EXPO_PUBLIC_CONVEX_URL=http://<lan-ip>:3220

Android blocks cleartext HTTP in release builds; fine for a dev build.
Alternative over USB: `adb reverse tcp:3220 tcp:3220` and use `127.0.0.1:3220`.

## Moving to the Hostinger VPS

Same compose file. Change only:

- Domain + TLS: reverse proxy (Caddy/nginx) with certificates, e.g.
  `convex.example.com` -> 3220, `convex-site.example.com` -> 3221, dashboard
  restricted or not exposed. Bind published ports to `127.0.0.1`.
- `CONVEX_CLOUD_ORIGIN=https://convex.example.com`,
  `CONVEX_SITE_ORIGIN=https://convex-site.example.com`; the app's
  `EXPO_PUBLIC_CONVEX_URL` is the former.
- Secrets: new `POSTGRES_PASSWORD`, `INSTANCE_SECRET`, admin key generated on
  the server; `.env` mode 600, never in the repo. Keep `CLERK_JWT_ISSUER_DOMAIN`.
- Backups: cron `backup.sh` and copy the dumps off the box (rsync/rclone to
  other storage). A dump on the same disk is not a backup.
- Firewall: only 80/443 open; Postgres stays unpublished.
