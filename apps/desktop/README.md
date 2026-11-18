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
