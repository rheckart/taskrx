import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // Node, not jsdom: this suite exists to execute `SyncWorker.drain()`, and
    // every React Native dependency it reaches for is mocked. There is no
    // component rendering here and adding a DOM would only invite it.
    environment: "node",
    env: {
      TZ: "UTC",
    },
    include: ["src/**/*.test.ts"],
    globals: false,
  },
});
