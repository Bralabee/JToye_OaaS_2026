/**
 * Declared Core Web Vitals budgets for throttled-mobile E2E arms.
 *
 * WHY THIS MODULE EXISTS. CLAUDE.md makes web performance a standing acceptance
 * criterion for any phase touching a user-facing page, and says to measure
 * "against a config-declared budget where one exists (introduce one rather than
 * inventing an ad-hoc number)". None existed: `webhooks-webperf.spec.ts` says so
 * in its own docblock — *"no numeric budget is declared in the repo"* — and
 * carries the numbers as local constants. This declares them once.
 *
 * THESE ARE NO-REGRESSION GUARDRAILS, NOT SLAs. They are deliberately generous
 * and they are seeded from the values `webhooks-webperf.spec.ts` already asserts,
 * rather than invented fresh. A budget nobody can defend gets `|| true` appended
 * to it the first time it goes red; a budget that only ever catches a genuine
 * collapse keeps working.
 *
 * Profile they describe: a 375px mobile viewport with CDP throttling — roughly
 * Fast 3G plus a 4x CPU slowdown. They mean nothing against unthrottled
 * localhost, where almost anything passes. "Builds clean" is not "loads fast".
 */

/**
 * Largest Contentful Paint ceiling, milliseconds, at the throttled profile.
 * Google's "good" threshold is 2500 ms on real hardware; this is a collapse
 * detector for a 4x-throttled emulated device, not a target to design to.
 */
export const LCP_BUDGET_MS = 8000

/**
 * Cumulative Layout Shift ceiling. 0.1 is Google's own "good" boundary, and it
 * is the one number here that IS a real-world threshold rather than a throttled
 * guardrail — layout shift does not get worse because the CPU is slower, it gets
 * worse because boxes are not reserved.
 */
export const CLS_BUDGET = 0.1

/**
 * INP is deliberately NOT declared.
 *
 * Interaction to Next Paint needs a real interaction to measure, and the routes
 * these budgets currently cover are read-mostly. A number invented here would be
 * asserted against whatever the first run happened to produce, which is a
 * measurement of the runner rather than of the product — and declaring a number
 * you cannot defend is worse than declaring fewer. When a route with a genuine
 * interaction budget arrives, add it then, with the interaction that justifies it.
 */

/**
 * KNOWN PRE-EXISTING CLS ON `/` — 0.1793, measured, NOT a 33-03 regression.
 *
 * The landing route does not meet CLS_BUDGET and did not before this phase
 * touched it. Measured as a two-arm A/B on 2026-08-08, both throttled to a 375px
 * viewport at 4x CPU, the pre-change build running simultaneously on :3001:
 *
 *   CONTROL   pre-33-03 (commit 8f6c03b1)   CLS=0.1793  LCP=764ms  shifts=1
 *   TREATMENT 33-03                          CLS=0.1793  LCP=744ms  shifts=1
 *
 * Identical to four decimal places. The single shift fires at ~1516 ms and its
 * `sources` are all HERO elements — the search form, the category chips, the
 * paragraph and both persona doors — i.e. client-island hydration above the
 * kitchen row this phase rewrote. Layout shift propagates downward, and the
 * control arm is what proves that rather than merely arguing it.
 *
 * THIS NUMBER IS A RECORD, NOT A BUDGET, AND CLS_BUDGET WAS DELIBERATELY NOT
 * RAISED TO 0.2 TO GO GREEN. Raising a budget until the tree passes is how a
 * budget stops meaning anything. The landing spec therefore asserts the
 * NO-REGRESSION form against this recorded value, which is falsifiable and fires
 * if 33-07's client island makes the shift worse — while the absolute 0.1 target
 * stays declared and unmet, so the debt stays visible.
 *
 * Fixing it means changing how `HeroSearch` hydrates, which is outside 33-03's
 * file set and is its own scoped work.
 */
export const LANDING_CLS_KNOWN_BASELINE = 0.1793

/** Tolerance on the no-regression comparison — run-to-run jitter, not headroom. */
export const LANDING_CLS_TOLERANCE = 0.02

/**
 * Recorded client-JS baseline for `/`, in bytes: the sum of every script the
 * landing route actually downloads, measured 2026-08-08 on the rebuilt stack.
 *
 * MEASURED, not estimated: 953,353 bytes across 21 scripts on the rebuilt stack.
 * A first draft of this file carried an invented 461,000 and the assertion caught
 * it — a number nobody measured is not a baseline.
 *
 * Two-arm A/B on 2026-08-08, pre-change build running simultaneously on :3001:
 *   CONTROL   pre-33-03  945,338 bytes / 20 scripts
 *   TREATMENT 33-03      953,353 bytes / 21 scripts   (+8,015 bytes, +0.85%)
 * The extra chunk is ShopCard. Growth is negligible and is recorded rather than
 * assumed.
 *
 * A BASELINE, not yet a ceiling. `/` is a Server Component with two small client
 * islands (HeroSearch, DishScroller). 33-07 adds a third that requests a
 * coordinate and refetches, and its plan enforces a declared ceiling; this is the
 * number that ceiling has to be justified against. It lives here rather than in a
 * summary because a baseline recorded only in prose cannot be compared against by
 * a test.
 */
export const LANDING_BUNDLE_BASELINE_BYTES = 953_353

/**
 * Growth factor the landing route's client JS may not exceed relative to the
 * baseline above, until 33-07 replaces this with an absolute ceiling it can
 * defend. A multiplier rather than an absolute number, because chunk hashing and
 * code-splitting move the exact figure between builds.
 */
export const LANDING_BUNDLE_MAX_GROWTH = 1.5
