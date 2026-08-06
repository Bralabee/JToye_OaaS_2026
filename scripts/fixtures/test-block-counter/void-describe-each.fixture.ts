// EXPECT: VOID (rc 2) — describe.each multiplies every block inside it, so the one
// visible `it(` below is two executed tests. Refusing is the only honest answer.
describe.each([["a"], ["b"]])("suite %s", (name) => {
  it("one inner block that runs twice", () => { expect(name).toBeTruthy() })
})
