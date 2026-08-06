// EXPECT: VOID (rc 2) — a tagged-template .each table is not an array literal.
describe("x", () => {
  it.each`
    a    | b
    ${1} | ${2}
  `("$a + $b", ({ a, b }) => { expect(a + b).toBe(3) })
})
