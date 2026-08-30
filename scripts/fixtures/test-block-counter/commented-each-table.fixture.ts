// Fixture for scripts/check-test-block-counter.sh — NOT part of any test suite.
//
// EXPECT: jest family -> 10 blocks.
//   3 (const table, trailing comma, every row trailed by a LINE comment)
// + 2 (const table, NO trailing comma, rows trailed by line comments)
// + 2 (inline table, trailing comma + line comments)
// + 2 (const table, trailing comma + BLOCK comments)
// + 1 (a plain block, so a table that collapsed to zero would still be visible)
//
// THE SHAPE THIS EXISTS FOR, AND WHY each-tables.fixture.ts DID NOT CATCH IT.
// That fixture already covers a trailing comma (its four-row inline table) and it
// is full of comments — but never the two TOGETHER on the same row, and the
// defect lives exactly in the combination. Two checks that each pass on half of a
// shape do not cover the shape.
//
// Comments used to be masked to FILL. FILL is deliberately NOT whitespace, so
// that a masked ELEMENT (a string literal row) still reads as content when
// counting array rows — correct for elements, wrong for comments, because a
// comment is never an element. A comment sitting after the trailing comma
// therefore made that comma look like a SEPARATOR rather than a TERMINATOR, and
// every such table gained one phantom row.
//
// Measured on frontend/lib/__tests__/layout-widths.test.ts: the counter said 16,
// jest executed 15. That is not a cosmetic drift — docs-freshness.sh and
// check-test-count-oracle.sh assert the SAME manifest key from opposite ends and
// both are required checks, so a one-row over-count means no value of
// docs/metrics.json lets the pull request merge.

const LINE_COMMENTED_TRAILING = [
  ["shell", 1700], // Stripe Dashboard --Chrome-maxWidth
  ["detail", 1100], // Linear's detail ladder
  ["marketing", 1280], // Stripe marketing pages
] as const

// The control for the arm above: same comments, NO trailing comma. This one
// counted correctly even before the fix, which is what isolates the defect to the
// comma-then-comment combination rather than to comments in tables generally.
const LINE_COMMENTED_NO_TRAILING = [
  ["a", 1], // first
  ["b", 2] // second, and deliberately no trailing comma
] as const

const BLOCK_COMMENTED_TRAILING = [
  ["x", 1], /* the block-comment path is masked by different code than the line */
  ["y", 2], /* one, so it needs its own row rather than being assumed to follow */
] as const

describe("each-tables whose rows carry trailing comments", () => {
  it.each(LINE_COMMENTED_TRAILING)("trailing comma + line comment: %s", (name) => {
    expect(name).toBeTruthy()
  })

  it.each(LINE_COMMENTED_NO_TRAILING)("no trailing comma + line comment: %s", (name) => {
    expect(name).toBeTruthy()
  })

  it.each([
    [1], // one
    [2], // two
  ])("inline table, trailing comma + line comment: %p", (n) => {
    expect(n).toBeGreaterThan(0)
  })

  it.each(BLOCK_COMMENTED_TRAILING)("trailing comma + block comment: %s", (name) => {
    expect(name).toBeTruthy()
  })

  it("a plain block, so a table that collapsed to zero would still be visible", () => {
    expect(1).toBe(1)
  })
})
