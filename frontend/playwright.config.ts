import { defineConfig, devices } from "@playwright/test"

export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false, // Sequential — tests share state (orders, auth)
  retries: 0,
  reporter: [["html", { open: "never" }], ["list"]],
  use: {
    // Dev env uses port 3100 (MCP server holds 3000); override via
    // `PLAYWRIGHT_BASE_URL=http://localhost:3100 npx playwright test` locally.
    baseURL: process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "mobile",
      use: {
        browserName: "chromium",
        viewport: { width: 390, height: 844 },
        isMobile: true,
      },
    },
    {
      name: "desktop",
      use: {
        browserName: "chromium",
        viewport: { width: 1440, height: 900 },
      },
    },
  ],
})
