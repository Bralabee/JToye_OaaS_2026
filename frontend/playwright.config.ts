import { defineConfig, devices } from "@playwright/test"

export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false, // Sequential — tests share state (orders, auth)
  // `fullyParallel: false` only sequences tests WITHIN a file. The two projects
  // below still ran concurrently on 2 workers, and both browsers leave through
  // the same Docker gateway IP, so they share ONE server-side rate-limit bucket.
  // Each storefront page load fires several public calls (shop, config,
  // promotions, announcements, reviews), so two contexts exhausted the burst and
  // the order POST came back 429 — surfacing as a checkout that never confirmed
  // (#409). Measured: 1 worker 3/3 green, 2 workers intermittent.
  // Overridable for a deliberate concurrency experiment.
  workers: Number(process.env.PLAYWRIGHT_WORKERS ?? 1),
  retries: 0,
  reporter: [["html", { open: "never" }], ["list"]],
  use: {
    // THE base-URL authority. Specs must navigate with RELATIVE paths and must
    // not declare their own default — enforced by
    // scripts/check-e2e-baseurl-contract.sh.
    //
    // This comment used to read "Dev env uses port 3100 (MCP server holds
    // 3000)". Both halves are false and were the source of the folklore that
    // put `:3100` into nine files' prose and one file's CODE (#505). Measured
    // 2026-08-03 on the Compose stack: frontend **3000**, core-java 9090,
    // edge-go 8089, mcp-server **9100** — nothing publishes 3100 at all.
    // Override for a genuinely different host with PLAYWRIGHT_BASE_URL.
    baseURL: process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "mobile",
      // #420: exclude blocks that are desktop-by-design so they are never ENUMERATED
      // here. Previously they were enumerated and then skipped at runtime, which put 2
      // permanent entries into the suite's skip count for surface that is fully covered
      // by the desktop project. A skip must mean "nobody checked this"; it cannot also
      // mean "not applicable here" and stay useful.
      grepInvert: /@desktop-only/,
      use: {
        browserName: "chromium",
        viewport: { width: 390, height: 844 },
        isMobile: true,
        // REQUIRED, not decorative (#503). `isMobile` alone leaves Chromium
        // reporting `pointer: fine` and `maxTouchPoints: 0`, so `(pointer:
        // coarse)` never matches and this project is blind BY CONSTRUCTION to
        // every defect whose symptom is "behaves like a mouse on a touch
        // device" — including the ungated `hover:` it exists to catch.
        //
        // Do not assume this took effect: e2e/mobile-instrument-contract.spec.ts
        // ASSERTS the resulting media-query state. Enforced by
        // scripts/check-playwright-mobile-contract.sh.
        hasTouch: true,
      },
    },
    {
      name: "desktop",
      // The mirror of the mobile project's grepInvert, for the same reason and
      // added when the first genuinely mobile-only block appeared (#503's
      // coarse-pointer assertion). Without it that block could only be handled
      // with a runtime `test.skip(project !== "mobile")`, which puts a permanent
      // "not applicable here" entry into the skip count — precisely what the
      // comment above says a skip must never mean.
      grepInvert: /@mobile-only/,
      use: {
        browserName: "chromium",
        viewport: { width: 1440, height: 900 },
      },
    },
  ],
})
