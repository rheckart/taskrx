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
