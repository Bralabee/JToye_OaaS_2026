// Fixture for scripts/check-test-block-counter.sh — NOT part of any test suite.
//
// EXPECT: jest family -> 14 blocks.
//   1 (plain it)
// + 3 (inline flat array)
// + 4 (inline array-of-arrays, trailing comma)
// + 2 (nested commas inside an element must not split it)
// + 3 (table referenced by an in-file const, with a TS type annotation —
//      this is the `it.each(hostile)` shape in logout-url-origin.test.ts)
// + 1 (it.skip is still one declared test: jest's numTotalTests counts pending)
//
// This is the direction the old regex could not see AT ALL: none of the .each
// tables below contain the literal `it(`, so they contributed zero to a manifest
// that claimed to count executed blocks.

const hostile: Array<[string, string]> = [
  ["protocol-relative", "//evil.example"],
  ["absolute", "https://evil.example"],
  ["backslash", "\\\\evil.example"],
]

describe("tables", () => {
  it("a plain block", () => {
    expect(1).toBe(1)
  })

  it.each([500, 502, 503])("flat row %p", (code) => {
    expect(code).toBeGreaterThan(0)
  })

  it.each([
    [null],
    [undefined],
    [""],
    ["   "],
  ])("row %p", (input) => {
    expect(input ?? "").toBeDefined()
  })

  it.each([
    { a: 1, b: 2 },
    ["x", "y", "z"],
  ])("an element containing commas is still ONE row", (row) => {
    expect(row).toBeDefined()
  })

  it.each(hostile)("rejects a %s redirect", (_label, raw) => {
    expect(raw).toBeTruthy()
  })

  it.skip("a skipped block is still declared, and jest counts it as pending", () => {
    expect(true).toBe(true)
  })
})
