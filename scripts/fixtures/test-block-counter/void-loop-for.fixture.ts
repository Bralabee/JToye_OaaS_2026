// Fixture for scripts/check-test-block-counter.sh — NOT part of any test suite.
//
// EXPECT: jest family -> VOID (rc 2), naming the loop-declared head's line and the
// line the for-loop enclosing it was opened on.
//
// This is the reproduction from issue #582, unchanged. Before the fix the counter
// answered {"blocks":3} at rc=0 for a file where FIVE tests execute — the one case
// where it emitted a number it could not justify.
//
// The damage is not the wrong number, it is the deadlock: docs/metrics.json
// .jest_blocks is asserted from both ends, by the static counter (3) and by jest's
// numTotalTests (5). Both host jobs are required checks, so 3 reds the runner oracle,
// 5 reds docs-freshness, and each failure's advice reproduces the other one.

describe("plain", () => {
  it("a", () => { expect(1).toBe(1) })
  it("b", () => { expect(1).toBe(1) })
})

describe("loop declared", () => {
  for (const n of [1, 2, 3]) {
    it(`case ${n}`, () => { expect(n).toBeGreaterThan(0) })
  }
})
