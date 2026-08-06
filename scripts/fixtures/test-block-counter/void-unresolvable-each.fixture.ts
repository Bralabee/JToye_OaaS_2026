// EXPECT: VOID (rc 2) — the .each table is a call, not a resolvable array literal.
// A counter that guessed "1" here would under-report by however many rows the call
// returns, and would look perfectly green doing it.
function makeCases() { return [1, 2, 3] }
describe("x", () => {
  it.each(makeCases())("row %p", (n) => { expect(n).toBeDefined() })
})
