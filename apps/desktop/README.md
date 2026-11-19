# TaskRx desktop Companion

The desktop shows and answers; the phone delivers (ADR 0016). Rust, with the phone's `:core` as
the reference (ADR 0017). The plan is `docs/plans/desktop-companion.md`.

## Crates

- `crates/taskrx-desktop-core`: the rules, ported from `apps/android/core`. Pure: no clock, no
  I/O. Every rule is held to the golden fixtures `:core:test` writes to
  `apps/android/core/golden`, read in place by `tests/golden.rs`.
- `crates/taskrx-desktop-service`: everything else that can be tested without a window. Sign-in
  through the browser (Clerk OAuth, PKCE, a loopback port), the refresh token in the Secret
  Service keyring, the Convex connection on its own thread and runtime, the data decoded as the
  phone's pull decodes it, each screen's state, and which call an answer sends. Documents stay in
  memory; the keyring entry is the only thing written to disk (ADR 0016).
- `crates/taskrx-desktop`: the window, drawn with Rune (pinned to a `dev` revision; its patch
  table is repeated in the workspace `Cargo.toml`). It draws the service's state, forwards
  presses, and follows the Omarchy theme (`colors.toml`, re-read when it changes, and Hyprland's
  rounding). It holds no rule. One copy runs at a time: two would each refresh the sign-in and
  spend the other's refresh token.

## Running it

```bash
cargo run -p taskrx-desktop                                  # the window
cargo run -p taskrx-desktop -- --snap day.png --scene day    # headless, made-up data
cargo run -p taskrx-desktop -- --list-scenes
```

The scenes (`day`, `all`, `tasks`, `catch-up`, `backlog`, `notices`, `signed-out`, `offline`) draw `crates/taskrx-desktop/scenes/day.json`,
which is made up, with no network, keyring or sign-in. `--tree` and `--script` drive them as
Rune's README describes. `TASKRX_DEPLOYMENT_URL` points the window at another deployment.

## The gate

```bash
cargo test && cargo clippy --all-targets -- -D warnings
```

`tests/golden.rs` fails on a fixture file it has no handler for, and on a `call` a handler does
not know, unless the file is on its `PENDING` list: the rules not ported yet. Porting a rule
takes its file off the list. Numbers are compared by value, since the fixtures spell a whole
number with and without `.0` and Convex sends both as the same float64.

Time zones come from jiff's bundled database, so a rule answers the same on every machine. A
wall-clock time in a spring-forward gap moves later by the gap, and one in a fall-back overlap
takes the earlier offset, as `java.time` resolves them.
