// Fixture for scripts/check-test-block-counter.sh — NOT part of any test suite.
// It is deliberately outside every path family docs-freshness.sh counts, and its
// extension is .fixture.ts so no runner's testMatch can pick it up.
//
// EXPECT: jest family -> 2 blocks.
//
// Everything below that looks like a block but is not one is the exact shape that
// made the old `\b(it|test)\(` regex over-count by 7 on the real tree.

describe("phantoms", () => {
  it("counts a real block", () => {
    // `\b` treats `.` as a word boundary, so the old pattern matched all of these:
    expect(/kitchen/.test("kitchen")).toBe(true)
    expect(/a/u.test("a")).toBe(true)
    const re = /x/
    expect(re.test("x")).toBe(true)
  })

  // it("this one is commented out and must not be counted", () => {})
  /* test("neither must this block-comment one", () => {}) */

  it("counts a second real block", () => {
    // A token inside a string is prose, not a declaration:
    const prose = 'it("looks like a test") and test("so does this")'
    expect(prose).toContain("looks like")
    // Identifiers that merely END in it/test are not heads either:
    const parts = "a,b".split(",")
    expect(parts.length).toBe(2)
  })
})
