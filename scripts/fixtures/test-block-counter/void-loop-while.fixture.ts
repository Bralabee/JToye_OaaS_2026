// Fixture for scripts/check-test-block-counter.sh — NOT part of any test suite.
//
// EXPECT: jest family -> VOID (rc 2), naming the while-loop body.
//
// A BRACELESS loop body. There is no `{` between the loop header and the head, so
// walking the brackets that enclose the head finds nothing loop-shaped — the counter
// has to look at what governs the head directly as well. Without that second check
// this file counts 2 while jest executes 5, and it is the shape a break-arm is most
// likely to miss because it does not look like a block at all.

describe("braceless", () => {
  it("a plain block", () => { expect(1).toBe(1) })

  let i = 4
  while (i--) it(`countdown ${i}`, () => { expect(i).toBeGreaterThan(-1) })
})
