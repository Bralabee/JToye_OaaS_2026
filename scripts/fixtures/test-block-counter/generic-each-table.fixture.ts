// Fixture for scripts/check-test-block-counter.sh — NOT part of any test suite.
//
// EXPECT: jest family -> 8 blocks.
//   1 (plain it)
// + 3 (it.each with a TypeScript type argument: `it.each<[string, number]>([...])`)
// + 4 (the same, with NESTED generics in the type argument — the `>>` and `>]>` runs
//      must be walked as a balanced list, not matched on the first `>`)
//
// This is the shape that produced the 12-block hole behind PR #726: the counter read
// the `.each` chain, then looked at the next character for `(` or a backtick, saw `<`,
// and treated the head as a BARE IDENTIFIER — contributing ZERO rather than refusing.
// A silent zero is the exact defect class this counter exists to close (#291, #582);
// the runner oracle (scripts/check-test-count-oracle.sh) caught it, which is what the
// oracle is for, but the tree-side gate must not be the half that lies.

describe("generic each tables", () => {
  it("a plain block", () => {
    expect(1).toBe(1)
  })

  it.each<[string, number]>([
    ["one", 1],
    ["two", 2],
    ["three", 3],
  ])("typed row %s", (_label, n) => {
    expect(n).toBeGreaterThan(0)
  })

  it.each<[string, Partial<Record<string, Array<number>>>]>([
    ["a", { a: [1] }],
    ["b", { b: [1, 2] }],
    ["c", {}],
    ["d", { d: [] }],
  ])("nested-generic row %s", (_label, overrides) => {
    expect(overrides).toBeDefined()
  })
})
