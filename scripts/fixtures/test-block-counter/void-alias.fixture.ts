// EXPECT: VOID (rc 2) — `xit` is a jest alias the counter does not model.
describe("x", () => {
  xit("a skipped-by-alias test", () => { expect(1).toBe(1) })
})
