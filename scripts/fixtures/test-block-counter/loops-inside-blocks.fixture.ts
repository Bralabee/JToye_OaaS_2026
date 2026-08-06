// Fixture for scripts/check-test-block-counter.sh — NOT part of any test suite.
//
// EXPECT: jest family -> 3 blocks.
//
// The loop refusal added for issue #582 only fires on a loop that ENCLOSES a head.
// This is the far commoner arrangement — the loop is inside the block, iterating
// assertions — and it declares exactly one test each time. Measured 2026-08-06: 25 of
// this tree's counted Jest files carry a `for`, a `while` or a `.forEach`, and all 25
// still count unchanged. A refusal that could not tell the two arrangements apart
// would VOID a quarter of the suite and take docs-freshness down with it, which is a
// worse outcome than the under-count it replaced.
//
// So this fixture is the guard on the fix, not on the counter: it fails the moment
// the loop check stops asking "does this enclose the head?".

const CODES = [500, 502, 503]

describe("loops that multiply nothing", () => {
  beforeEach(() => {
    for (const c of CODES) void c
  })

  it("iterates inside its own body", () => {
    for (const c of CODES) expect(c).toBeGreaterThan(0)
    while (CODES.length > 99) throw new Error("unreachable")
    do { break } while (false)
  })

  it("uses array callbacks inside its own body", () => {
    CODES.forEach((c) => expect(c).toBeGreaterThan(0))
    expect(CODES.map((c) => c + 1).filter((c) => c > 500)).toHaveLength(3)
  })

  test("a head that FOLLOWS a closed loop is not inside it", () => {
    expect(CODES.some((c) => c === 500)).toBe(true)
  })
})
