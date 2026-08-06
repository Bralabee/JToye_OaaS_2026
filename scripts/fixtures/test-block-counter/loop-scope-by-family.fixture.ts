// Fixture for scripts/check-test-block-counter.sh — NOT part of any test suite.
//
// EXPECT: playwright family -> 3 blocks.   jest family -> VOID (rc 2).
//
// ONE file, two answers, and both are right — which is the whole reason the loop
// refusal is a per-family policy flag rather than a blanket rule.
//
//   jest       its oracle is numTotalTests, i.e. what RAN. The loop below runs twice,
//              the source declares once, and docs/metrics.json cannot be both. VOID.
//   playwright its oracle is `--list` de-duplicated by (file,line,column), i.e. the
//              DECLARATION SITE — because the project matrix already runs every spec
//              once per project and counting runs would count desktop and mobile
//              twice. A loop-declared test() is one site on BOTH sides, so the two
//              halves agree and there is nothing to refuse. frontend/e2e has 5 such
//              sites counting correctly today; refusing here would red a green tree.
//
// If someone ever "simplifies" POLICY.playwright.loopMultiplies to true, the
// playwright arm on this fixture goes red before the real specs do.

const VIEWPORTS = [375, 768]

test("a plain declaration", async () => {
  expect(1).toBe(1)
})

test("a second plain declaration", async () => {
  expect(2).toBe(2)
})

for (const width of VIEWPORTS) {
  test(`renders at ${width}px`, async () => {
    expect(width).toBeGreaterThan(0)
  })
}
