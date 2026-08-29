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
 * DESKTOP CLS ON `/` — 0.1316, the PRE-CHANGE control, measured at 1440x900.
 *
 * (a) THIS IS A RECORD, NOT A BUDGET, exactly as LANDING_CLS_KNOWN_BASELINE
 * above is. It is what the landing route did BEFORE phase 35 touched it. It is
 * not a target, nobody designed to it, and it may not be raised to make a red
 * run green.
 *
 * (d) WHY A DESKTOP ARM EXISTS AT ALL. Every CLS instrument this repository owns
 * measures at a 375px viewport — `landing-webperf.spec.ts` and
 * `webhooks-webperf.spec.ts` both pin one, and the Playwright `desktop` project
 * runs this file at 375px anyway because the describe's own `test.use` overrides
 * the project viewport. Phase 35's only real width change is the landing page's
 * content band moving from 1152px to 1280px, and a `max-width` is emitted with
 * NO media query, so it cannot bind against a 375px viewport. The mobile arm is
 * therefore STRUCTURALLY BLIND to this change: it would have reported a pass
 * from an instrument incapable of seeing what it was asked about. That is
 * ORCH-02 (orchestrator decision, 2026-08-29 — CONTEXT.md section 4b).
 *
 * (c) CONDITIONS. A CLS number without its conditions is not a number:
 *     viewport 1440x900 (the config's `desktop` project), no `isMobile`
 *     CDP Network.emulateNetworkConditions — latency 40 ms, 1.5 Mbps down,
 *       750 kbps up (~Fast 3G), the same helper the mobile arm uses
 *     Emulation.setCPUThrottlingRate rate 4
 *     sampled after the h1 is visible AND every <img> has settled
 *     both arms served by `next start` from a fresh `next build`, on the host,
 *       against the live Compose core API, in one interleaved session
 *
 * (b) THE FULL RESULT. Three arms, interleaved (control, treatment, mech,
 * control, ...) in a single process so machine drift could not land on one arm.
 * Every run recorded, not just the kept one:
 *
 *   CONTROL    merge base 96c8d794 (pre-phase-35)          band 1152px
 *              0.1316  0.1316  0.1316  0.1316  0.1316  0.1316     (6 runs)
 *   TREATMENT  branch HEAD b16d0874                        band 1280px
 *              0.0362  0.0362  0.0362  0.0362  0.0362  0.0362     (6 runs)
 *   MECH       branch HEAD with MARKETING_MAX_PX = 1152    band 1152px
 *              0.1316  0.1316  0.1316                             (3 runs)
 *
 * Observed spread within every arm: 0.0000. LCP 792–808 ms throughout.
 *
 * THE MECH ARM IS WHY CAUSATION IS CLAIMED RATHER THAN CORRELATION. 55 frontend
 * files differ between the merge base and this branch. MECH is byte-identical to
 * TREATMENT except for one number in `lib/layout-widths.ts` (verified: the only
 * file `diff -rq` reports between the two trees), and it reproduces the CONTROL
 * exactly. So the move is caused by the marketing cap's VALUE and by nothing
 * else in the phase.
 *
 * DIRECTION: DOWN. Desktop CLS IMPROVED from 0.1316 to 0.0362, a 72% reduction.
 * 35-06 handed over the opposite prediction (its D-35-06-b): CLS is area-
 * weighted, the shifting region is ~11% wider, so an identical displacement
 * should weigh MORE. The measurement refutes it. No new shift appeared either —
 * all three arms record exactly ONE layout-shift entry, at ~1520 ms, with the
 * same hero sources. The same shift got LIGHTER.
 *
 * The mechanism visible in the entry's own rects: the persona-door grid
 * (`div.mt-6.grid.grid-cols-1...sm:grid-cols-2`) reflows during hydration at the
 * narrow band and does not at the wide one —
 *     CONTROL / MECH   prev h=220 -> cur h=248   (+28px, an extra text line)
 *     TREATMENT        prev h=220 -> cur h=220   (no growth)
 * and the settled page is 28px shorter at 1280 (doc 2234 vs 2262). A height
 * change makes every box below it unstable too; removing the reflow removes that
 * cascade. Full arithmetic is NOT reconstructed here on purpose: the
 * layout-shift API caps `sources` at five entries, so the complete set of
 * unstable elements is not observable and impact x distance cannot be derived
 * from what it reports.
 *
 * (e) RELATIONSHIP TO THE MOBILE RECORD, stated so nobody assumes one asserts
 * against the other. LANDING_CLS_KNOWN_BASELINE is 0.1793 at 375px; this is
 * 0.1316 at 1440px. THEY ARE NOT THE SAME MEASUREMENT and neither replaces the
 * other: CLS normalises by viewport, so the two numbers describe different
 * layouts of different widths and their difference (0.0477) means nothing on its
 * own. The mobile arm keeps asserting against 0.1793 and is untouched by this
 * plan; the desktop arm asserts against the two constants declared here. Mobile
 * is inert to this change by construction — 35-06 measured the generated
 * stylesheet and found the `max-w-marketing` rule emitted with no media query,
 * so a 1280px cap cannot bind at 375px.
 *
 * (f) CLS_BUDGET IS DELIBERATELY NOT RAISED, and neither is the mobile record.
 * 0.1 stays declared and unmet at both viewports, so the pre-existing debt on
 * `/` stays visible. Phase 35 did not set out to fix it and does not claim to.
 */
export const LANDING_CLS_DESKTOP_CONTROL = 0.1316

/**
 * The desktop CLS this phase actually SHIPPED — 0.0362, same conditions, same
 * session, same instrument as the control above.
 *
 * WHY A SECOND CONSTANT RATHER THAN ONLY THE CONTROL. The no-regression form
 * against 0.1316 is the criterion plan 35-09 specifies and it is the one that
 * can never wrongly red — but on its own it leaves 0.1154 of slack over what the
 * route now measures, i.e. a bound 3.6x the value it is guarding. A guard that
 * only fires on a catastrophe is the "criterion incapable of failing" this
 * project keeps paying for, and an improvement nothing asserts is an improvement
 * that rots back silently. So the delivered good is ratcheted: both bounds are
 * asserted, the control states "this phase did not make it worse" and this one
 * states "and the improvement is still there".
 *
 * DEFENSIBLE AS A GATE, on the evidence rather than on hope: the observed spread
 * across six treatment runs was 0.0000 — the shift is deterministic geometry,
 * not a timing race — and the shared LANDING_CLS_TOLERANCE of 0.02 is 55%
 * headroom over the measured value. No new tolerance was invented for it.
 *
 * IT ALSO CATCHES A STALE RUNTIME, which is a feature and not a side effect. A
 * frontend image built before this phase serves the 1152px band and scores
 * 0.1316, which sails under the control bound and reds this one. Measured: the
 * Compose frontend image was tagged 15:42 while the newest commit touching
 * `frontend/` was 20:40 the same day, so a run against :3000 on the day this was
 * written would have measured the pre-change code and reported a pass.
 *
 * If a different machine finds this brittle, the honest response is to
 * RE-MEASURE with the two-arm protocol and record what it finds — not to widen
 * the tolerance until the tree goes green. That is the same move as raising a
 * budget.
 */
export const LANDING_CLS_DESKTOP_RECORD = 0.0362

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
 * (The card's text is quoted exactly as the probe read it. The distance pill was
 * relabelled from kilometres to MILES later the same day, at 33-07's human gate,
 * so that card now reads "0.2 miles away" — a string change inside an absolutely
 * positioned element, which is why the measurement above still describes the
 * current build. The record is left as measured rather than retro-fitted.)
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
