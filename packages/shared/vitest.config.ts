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
        "src/schedule/engine.ts",
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
