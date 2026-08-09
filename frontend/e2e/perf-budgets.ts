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
 * What the island is allowed to cost, in bytes, on top of the baseline above.
 *
 * 33-03 left a 1.5x GROWTH FACTOR here — 1,430,029 bytes — as an explicit
 * placeholder for "until 33-07 replaces this with an absolute ceiling it can
 * defend". 33-07 is that plan, and this is that ceiling. It is a TIGHTENING, not
 * a substitution: 973,833 is 456,196 bytes below the bound it replaces, and the
 * regression it was written for (below) sails under the old one.
 *
 * DERIVED FROM A MEASUREMENT, NOT CHOSEN. Both arms run on the rebuilt stack
 * with this file's own meter, 2026-08-09:
 *
 *   33-03 baseline, no island                             953,353 bytes
 *   33-07 island, issuing its one GET with `fetch`        958,988   (+5,635)
 *   33-07 island, issuing it through `publicApiClient`  1,005,834   (+52,481)
 *
 * The allowance is 20,480 bytes — 3.6x the growth actually shipped, which is
 * headroom for a small future addition and for chunk-boundary shuffling, and
 * comfortably under the 46,846 bytes that pulling axios onto this route costs.
 * So the ceiling has a REGRESSION IT DEMONSTRABLY CATCHES rather than being a
 * number nobody can defend: re-importing the axios client into the landing
 * island puts the route at 1,005,834 and reds this assertion by 31,999 bytes.
 *
 * Why an absolute ceiling now that a multiplier was right before: a multiplier
 * of a moving baseline ratchets. Each plan measures its own growth against the
 * previous plan's total, so a route can gain 50% three times and never fail.
 * `/` is the LCP-critical page every customer sees first; the number it may not
 * exceed should be a number, and changing it should be a visible edit here.
 */
export const LANDING_BUNDLE_ISLAND_ALLOWANCE_BYTES = 20_480

/**
 * The bound `landing-webperf.spec.ts` asserts. Written as baseline + allowance
 * rather than as a literal so the derivation cannot drift away from the number.
 */
export const LANDING_BUNDLE_CEILING_BYTES =
  LANDING_BUNDLE_BASELINE_BYTES + LANDING_BUNDLE_ISLAND_ALLOWANCE_BYTES

/**
 * VERTICAL displacement, in CSS pixels, permitted anywhere on `/` in the
 * POST-GRANT window — after the "Use my location" click lands a new list.
 *
 * WHY VERTICAL PIXELS AND NOT A LAYOUT-SHIFT SCORE. Two reasons, and the second
 * was measured the hard way.
 *
 * FIRST: the layout-shift entry carries `hadRecentInput`, true for anything
 * within 500 ms of an interaction, and every CLS implementation — including the
 * one in this file's spec — drops those entries. So a post-grant CLS assertion
 * has a number that cannot move however badly the row jumps. It is right for the
 * metric and useless as this plan's criterion.
 *
 * SECOND, AND THIS IS THE PART I GOT WRONG FIRST: the obvious repair — sum the
 * shift entries CLS discards and bound the total — FORBIDS THE FEATURE. Measured
 * 2026-08-09 at 375px, that total is 0.0687, and the probe shows exactly one
 * entry with exactly one source:
 *
 *   A.group.grow.basis-[220px]   "0.4 km away Peckham Jollof Co. …"
 *     prev  x=136  y=443.234375  height=216
 *     cur   x= 16  y=443.234375  height=216
 *
 * A card moving 120 px HORIZONTALLY with its y and its height unchanged to the
 * fractional pixel. That is the reorder — the entire point of the feature, asked
 * for by the visitor a moment earlier. A budget that reds on it would be a budget
 * that says "do not ship distance ordering", and the honest response is to
 * replace the criterion rather than to raise it until it goes green.
 *
 * What actually matters is that the row does not push the PAGE around: content
 * below it staying put, and cards keeping their height. That is what this bounds,
 * and it is falsifiable in the direction that matters — putting the distance in
 * the card's flow instead of out of it changes card height, and un-reserving the
 * status line's height moves everything below the heading.
 *
 * 1 px is fractional-rounding tolerance around a measured ZERO, not headroom.
 */
export const LANDING_POST_GRANT_MAX_VERTICAL_PX = 1
