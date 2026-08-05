// EXPECT: VOID (rc 2) — an unmodelled modifier chain must refuse, not guess.
describe("x", () => {
  test.wibble("something the counter has never seen", () => { expect(1).toBe(1) })
})
