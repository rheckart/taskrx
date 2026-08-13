import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    env: {
      TZ: "UTC",
    },
    coverage: {
      provider: "v8",
      reporter: ["text", "html"],
      include: [
        "src/adherence/calculator.ts",
        // The window the figure covers. Its whole job is local calendar
        // arithmetic that goes wrong twice a year, and the period is tied to
        // ADR 0005's lookback — a branch added here without a case is a figure
        // covering days the sweep cannot account for.
        "src/adherence/period.ts",
        // The draft is what stands between a typed phrase and a written
        // Reminder: whether the parser was understood, what a failed read falls
        // back to, and which fields a Kind correction may not carry across. A
        // branch added here without a case is a task saved with a dosage, or an
        // entry surface with nothing to show.
        "src/parser/draft.ts",
        "src/parser/extractors.ts",
        "src/parser/lexicon.ts",
        "src/parser/patterns.ts",
        "src/parser/reminder-parser.ts",
        "src/parser/schedule-grammar.ts",
        "src/refill/planner.ts",
        "src/schedule/horizon.ts",
        "src/schedule/occurrences.ts",
        "src/domain/enums.ts",
        "src/domain/reminder.ts",
        "src/domain/schedule-times.ts",
        // The sync conflict rules carry the safety-critical "a taken dose
        // stays taken" guard, and the pull path added in #7 is their first
        // real caller. Gated at 100% so a branch cannot be added to them
        // without a case pinning it.
        "src/sync/conflict.ts",
        // The timeline projection is the rule the home screen renders, and the
        // screen decides nothing itself. Gated so a branch cannot be added here
        // without a case stating the rule it changes.
        "src/timeline/projection.ts",
        // The sweep planner decides what becomes Missed, which is what makes
        // adherence honest about doses nobody answered. Its two boundaries are
        // the Grace Period and the lookback, and neither may move without a
        // case naming it.
        "src/sweep/planner.ts",
        // The Backlog completion rule is one branch and one ADR (0002); gated so
        // that branch keeps its case.
        "src/backlog/completion.ts",
        // Tier decides which Reminders may be armed with an alarm-clock
        // primitive and which may occupy the bounded medication Horizon
        // (ADR 0003). A branch added here without a case is a task quietly
        // competing with a dose.
        "src/notifications/tier.ts",
        // Whether the app asks for permission to notify at all, and what it
        // tells someone whose answer leaves reminders undeliverable (issue
        // #20). The defect that motivated it was silent by construction — the
        // alarm fires, Android discards the notification, nothing reports it —
        // so a branch added here without a case is a reminder that vanishes
        // with nobody the wiser.
        "src/notifications/permission-state.ts",
      ],
      exclude: ["src/**/*.test.ts", "src/**/index.ts"],
      thresholds: {
        lines: 100,
        branches: 100,
        functions: 100,
        statements: 100,
      },
    },
    globals: false,
  },
});
