// Fixture for scripts/check-test-block-counter.sh — NOT part of any test suite.
//
// EXPECT: jest family -> VOID (rc 2), and the message must name 'it.each(' — not
// 'it(' — so the refusal is visibly about the loop and not about the table.
//
// A loop multiplies a RESOLVABLE `.each` table just as surely as it multiplies a
// plain block: 2 statuses x 2 rows = 4 executed tests, and the table resolves
// cleanly to 2. That is the dangerous direction — the counter has a confident,
// correct-looking answer for the part it can see, so the loop check has to run
// BEFORE the chain is classified rather than after.

for (const status of [500, 503]) {
  it.each([["timeout"], ["reset"]])(`%s at ${status}`, (label) => {
    expect(label).toBeTruthy()
  })
}
