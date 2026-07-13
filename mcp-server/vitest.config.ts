import { defineConfig } from "vitest/config";

// Node environment (this is a server-side HTTP forwarder, no DOM).
// include mirrors the docs-freshness path family: mcp-server/src/**/*.test.ts
export default defineConfig({
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
