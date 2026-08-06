// Fixture for scripts/check-test-block-counter.sh — NOT part of any test suite.
//
// EXPECT: playwright family -> 3 blocks.
//   2 bare test(...) declarations
// + 1 test.skip("name", fn)  — a DECLARATION, because its first argument is a string
//   0 test.skip(cond, "reason") x2 — runtime skip DIRECTIVES inside a test body
//   0 test.describe / test.use / test.beforeEach / test.setTimeout / test.fail()
//   0 /regex/.test(x)
//
// The dual nature of `test.skip` is the reason the counter inspects the first
// argument instead of putting `skip` in a flat ignore list: an ignore list would
// silently drop a real declaration, which is the failure direction that looks fine.

test.describe("directives", () => {
  test.use({ viewport: { width: 375, height: 812 } })

  test.beforeEach(async ({ page }) => {
    await page.goto("/")
  })

  test("a real declaration", async ({ page }) => {
    test.setTimeout(30_000)
    test.skip(!process.env.RELAY_E2E, "needs the multi-replica stack")
    const url = page.url()
    expect(/\/shop\b/.test(url)).toBe(false)
  })

  test("a second real declaration", async ({ page }) => {
    test.skip(true, "no sign-in method found on /auth/signin")
    test.fail()
    expect(page).toBeTruthy()
  })

  test.skip("a declared-but-skipped test still declares one test", async () => {
    expect(true).toBe(true)
  })
})
