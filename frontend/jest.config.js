const nextJest = require('next/jest')

const createJestConfig = nextJest({
  dir: './',
})

const customJestConfig = {
  setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
  testEnvironment: 'jest-environment-jsdom',
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/$1',
  },
  // SCOPE DECISION, 2026-08-28 (plan 34-08, TRUTH-02 / #110).
  //
  // `hooks/**` was NOT in this list. It is now, and the widening was decided and applied
  // BEFORE any threshold number below was chosen — because a floor measured against a
  // scope is a claim about that scope, and picking the number first would have quietly
  // fixed the scope to whatever made the number look best.
  //
  // The reason: phase 34 adds `hooks/use-theme.ts` (plan 34-02) and rewrites
  // `hooks/use-customer-session.ts` around `lib/customer-session-store.ts` (plan 34-03),
  // both with dedicated Jest suites. Under the old glob the store counted (it is in
  // `lib/`) and the two hooks did not. A coverage gate whose scope excludes the directory
  // where newly tested code lands is measuring the wrong thing — it would have reported
  // "coverage held" while being structurally unable to see either hook.
  //
  // The widening MOVES the baseline, and the direction was not predictable in advance —
  // `hooks/` holds untested files as well as this phase's well-tested new ones, so it
  // could as easily have lowered every counter. That would have been information, not a
  // problem; the floor below is set from the post-widening measurement either way. Both
  // measurements, taken on 2026-08-28 on this tree with waves 1 and 2 landed, via
  // `npx jest --coverage --coverageReporters=text-summary --ci --watchAll=false`:
  //
  //                 pre-widening (app/components/lib/types)   post-widening (+ hooks/)
  //   Statements    64.6%   (4029/6236)                       65.12%  (4265/6549)
  //   Branches      57.69%  (1930/3345)                       57.75%  (1980/3428)
  //   Functions     61.49%  (837/1361)                        62.02%  (890/1435)
  //   Lines         65.96%  (3721/5641)                       66.49%  (3939/5924)
  //
  // 9 files and 313 statements entered the scope; nothing left it. 124 suites / 1272
  // tests in BOTH runs — the widening changes what is MEASURED, never what is EXECUTED.
  // The 9: use-cart-count, use-count-up, use-customer-session, use-order-events,
  // use-shop-context, use-stomp, use-stored-state, use-theme, use-toast. The two this
  // phase owns (use-theme, use-customer-session) are among them, which is the point.
  //
  // For the record, 34-RESEARCH.md measured the pre-widening scope at
  // 63.76 / 57.06 / 60.71 / 65.10 earlier the same day, BEFORE waves 1 and 2 landed.
  // The 64.6 / 57.69 / 61.49 / 65.96 above is a fresh run on the tree the floor actually
  // guards — a threshold set from a superseded number is a threshold set from nothing.
  collectCoverageFrom: [
    'app/**/*.{js,jsx,ts,tsx}',
    'components/**/*.{js,jsx,ts,tsx}',
    'hooks/**/*.{js,jsx,ts,tsx}',
    'lib/**/*.{js,jsx,ts,tsx}',
    'types/**/*.{js,jsx,ts,tsx}',
    '!**/*.d.ts',
    '!**/node_modules/**',
    '!**/.next/**',
  ],
  // COVERAGE FLOOR — plan 34-08, TRUTH-02 / #110.
  //
  // There was no coverageThreshold at all, and the CI Jest step did not even pass
  // --coverage, so the frontend number was never produced in CI, let alone checked.
  //
  // MEASURED 2026-08-28 against the scope declared above (post-widening, waves 1+2 on the
  // tree): Statements 65.12  Branches 57.75  Functions 62.02  Lines 66.49.
  //
  // Each value below is floor(measurement) - 2, a single stated rule rather than four
  // separately-negotiated numbers, giving margins of 2.12 / 2.75 / 2.02 / 2.49 points.
  // The margin exists because CI is not this machine: 34-RESEARCH assumption A2 rates
  // "a threshold just below today's LOCAL number will not flake in CI" as MED risk, since
  // a hosted runner can differ in Node minor and jsdom version. Two points of the 6549
  // statements in scope is ~139 statements — wider than any environment-driven drift seen
  // here, and far narrower than a deleted test file.
  //
  // These are NO-REGRESSION GUARDRAILS, not targets. They say "coverage has not fallen",
  // never "coverage is good enough". Raising one is a deliberate act that must arrive
  // with its own fresh measurement recorded here. LOWERING ONE TO MAKE A RED BUILD GREEN
  // IS THE FAILURE MODE, and this repo has already written that down once — see
  // e2e/perf-budgets.ts:64-70, "Raising a budget until the tree passes is how a budget
  // stops meaning anything." If this goes red, the answer is a test, not a smaller number.
  //
  // Each of the four was raised above its measurement once and observed red before being
  // trusted; the real output is in .planning/phases/34-rendering-test-truthfulness/
  // 34-08-SUMMARY.md. A threshold never seen failing is not a gate.
  coverageThreshold: {
    global: {
      statements: 63,
      branches: 55,
      functions: 60,
      lines: 64,
    },
  },
  testMatch: [
    '**/__tests__/**/*.[jt]s?(x)',
    '**/?(*.)+(spec|test).[jt]s?(x)',
  ],
  // Exclude Playwright e2e specs — they use @playwright/test and break Jest's runner
  testPathIgnorePatterns: [
    '/node_modules/',
    '/e2e/',
    '/.next/',
  ],
}

module.exports = createJestConfig(customJestConfig)
