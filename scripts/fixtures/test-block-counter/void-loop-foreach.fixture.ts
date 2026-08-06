// Fixture for scripts/check-test-block-counter.sh — NOT part of any test suite.
//
// EXPECT: jest family -> VOID (rc 2), naming the .forEach(...) callback.
//
// The head is not inside any `for` keyword here — it is inside an arrow function that
// happens to be a `.forEach` argument. A scan that only looked for loop KEYWORDS
// enclosing the head would count this file as 2 while jest executes 3, which is the
// same deadlock wearing a different shape.

const CODES = [500, 502, 503]

describe("forEach declared", () => {
  it("a plain block", () => { expect(1).toBe(1) })

  CODES.forEach((code) => {
    it(`maps status ${code}`, () => { expect(code).toBeGreaterThan(0) })
  })
})
