# Self-hosted Convex (Postgres)

Convex backend + dashboard + its own Postgres (not published on the host), via
Docker. Host ports: backend `3220`, HTTP actions `3221`, dashboard `6791`
(3210/3211 belong to the local `npx convex dev` backend).

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

## Point the app at it

A phone cannot reach `localhost`. Use the machine's LAN IP and make the backend
advertise it, then redeploy:

    # .env
    CONVEX_CLOUD_ORIGIN=http://<lan-ip>:3220
    CONVEX_SITE_ORIGIN=http://<lan-ip>:3221
    docker compose up -d

Then set `CONVEX_URL=http://<lan-ip>:3220` in `apps/android/local.properties` and rebuild.
Android blocks cleartext HTTP in release builds; fine for a dev build.
Alternative over USB: `adb reverse tcp:3220 tcp:3220` and use `127.0.0.1:3220`.

## The VPS

Live at `https://taskrx.caballus.tech`. The stack is `vps/docker-compose.yml`, which sits at
`/docker/taskrx` on the box, beside `/docker/caballus` and `/docker/traefik`. It runs only the Convex
backend:

- **Traefik** (already on the box) terminates TLS with Let's Encrypt. The backend publishes no port: ufw is
  off on that box, so a published port would be on the internet. The one public route is `taskrx.caballus.tech`
  to 3210. There is no dashboard and no route for 3211, because the app has no HTTP actions.
- **Postgres** is the one in the caballus stack, reached as `postgres` on `pg-taskrx`, an internal network the caballus stack creates for this backend alone. Traefik reaches the backend on `taskrx_default`.
  TaskRx has its own role and database, `taskrx`, created once by hand (a plain role, owner of its database).
- **The image is pinned by digest**, and labelled so Watchtower (which updates every container daily on that
  box) leaves it alone. Bump it on purpose, after trying the new build locally.
- **Secrets** are in `/docker/taskrx/.env` (mode 600): `TASKRX_DB_PASSWORD` and `INSTANCE_SECRET`. The admin key
  was made on the box (`docker compose exec -T backend ./generate_admin_key.sh`) and is kept in the gitignored
  `infra/convex/.env.vps`, beside `CONVEX_SELF_HOSTED_URL` and `CLERK_JWT_ISSUER_DOMAIN`.

Deploy functions and run any CLI command against it by naming the target:

    CONVEX_TARGET=vps ./deploy.sh
    CONVEX_TARGET=vps ./deploy.sh data reminders

Point the app at it with `CONVEX_URL=https://taskrx.caballus.tech` in `apps/android/local.properties`
(release builds need `https`).

**Backups.** `vps/backup.sh` (at `/docker/taskrx/backup.sh`) dumps the `taskrx` database offsite, to the same
S4 bucket as the caballus backups, under `taskrx/`. It reads that stack's `backup.env` for the bucket and keys
and never edits its files. Three systemd timers (`vps/systemd/`, installed in `/etc/systemd/system`) run it, all
in UTC:

- `frequent`: every hour at :20.
- `nightly`: 07:35, then prunes anything older than 28 days (caballus's own check fails on files over 31).
- `restore-check`: 09:05, restores last night's dump into a scratch database and counts the documents.

A good run writes a stamp in `/docker/taskrx/backup-state/`; nothing mails on failure, so look at
`systemctl --failed` and the stamp dates. Run one by hand with `/docker/taskrx/backup.sh nightly`. To restore,
copy a dump down with rclone, stop the backend, drop and recreate the `taskrx` database in the caballus
Postgres, `pg_restore` into it, and start the backend. The dumps are not encrypted before upload.

**Traefik and its Docker socket.** Docker recreates `/var/run/docker.sock` when the daemon restarts, and a
Traefik container started before that keeps the old file: it logs "Cannot connect to the Docker daemon" and
never sees a new container. If a new route will not appear, `docker restart traefik-traefik-1`.
