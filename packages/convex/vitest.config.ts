import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // `convex-test` runs the real function bodies against an in-memory
    // deployment, which needs the same runtime Convex uses in production.
    environment: "edge-runtime",
    server: { deps: { inline: ["convex-test"] } },
    env: {
      TZ: "UTC",
    },
    globals: false,
  },
});
