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
        "src/schedule/engine.ts",
        "src/schedule/horizon.ts",
        "src/schedule/occurrences.ts",
        "src/domain/enums.ts",
        "src/domain/reminder.ts",
        "src/domain/schedule-times.ts",
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
