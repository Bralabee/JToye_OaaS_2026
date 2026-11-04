# Quick 261104-public-content — SUMMARY (persona close-out, PR #1116)

Written by the supervisor from the executor's report (the executor's Write of this file was refused by the harness).

Owner rulings 2026-11-04: VAT basis — prices include VAT for VAT-registered businesses and carry no VAT for non-registered ones (one constant in `frontend/lib/pricing.ts`); positioning — UK-wide, launching first in Birmingham and the West Midlands; merchant terms — drafted by Claude at the owner's request, marked "Draft — to be reviewed by a solicitor before it applies to paying vendors", v0.1, 4 Nov 2026, not in force.

| Issue | Outcome |
|---|---|
| 803 | Partly: "Start your application" → `/contact#apply` (steps, pricing + terms links, sign-in for approved operators); `/auth/signin` gains "Apply to join". Human route shows only once the owner sets `FRONTEND_PUBLIC_CONTACT_EMAIL` / `_PHONE` (unset). No vendor self-registration. |
| 804 | Fixed: `/pricing`, `/about`, `/legal/merchant-terms` (were 404); footer, legal hub, sitemap (+ `/contact`, `/legal/terms`); card fee links Stripe pricing (2026-11-01 ruling). |
| 878 | UXT-119 half fixed (positioning + vendor-to-vendor confidentiality); UXT-114 fixed earlier (PR #901). |
| SW-CLR-03, SW-CLR-06 | Fixed (positioning line, VAT sentence). |

Evidence: tests written first (suite-level red: pages/constants absent); four break arms (old CTA href, "in London" lede, draft marker removed, VAT paragraph removed) turned 7 tests red, restored. Green: 39 new tests; `npm test` 304 suites / 3642 tests; lint; build; `e2e/public-a11y.spec.ts` 10/10 (WCAG 2.2 AA, new pages, mobile + desktop); `e2e/public-layout.spec.ts` 52/52; 375 px screenshots read (no overflow); docs-freshness + check-doc-metrics; changelog cites #1116; 0 behind base.

Open for the owner/solicitor: liability cap (12 months of fees) and exclusions are conventional starting points; notice period "as agreed with the plan"; whether J'Toye's own fees carry VAT depends on J'Toye Digital's registration; contact repo variables unset; `/business-model-guide` still mentions a London cluster (strategy analysis, handled by the customer-rulings lane which removes internal strategy pages).

## Review round 1 fixes (3 admissible)

| # | Finding | RED | GREEN |
|---|---|---|---|
| 1 | docs-freshness red: 2 Playwright `test()` blocks added, `docs/metrics.json` said 199 | `bash scripts/docs-freshness.sh` rc 1 (computed 201 Playwright, 7247 total after this round's new jest blocks). After `--write`, `bash scripts/check-doc-metrics.sh` rc 1 on README, CLAUDE.md and AGENTS.md (still 7237, 3642 and 199) | `docs/metrics.json` regenerated (201 Playwright blocks, 3650 Jest blocks, 7247 total); README, CLAUDE.md and AGENTS.md prose updated; `check-doc-metrics.sh` rc 0 (39 claims); `docs-freshness.sh` rc 0. Last time the manifest was written before the two e2e tests were added, which is how 199 stayed. |
| 2 | `/about` reused `PLATFORM_NOT_SELLER_COPY`, the checkout's per-order sentence | New tests in `app/__tests__/public-content-pages.test.tsx` failed against the old page (3 of 3: the per-order sentence rendered, the new sentence absent, the checkout import present) | `/about` now says "J'Toye provides the platform, and each kitchen is the seller of the food it lists." and does not import the checkout's copy. 3 of 3 pass. Break arm (sentence reworded to "each kitchen sells") turned the sentence test red. One slip on the way: my own source comment named the constant and failed the no-import source test (the "prose counts" trap); reworded. |
| 3 | The merchant terms retyped the plan list and claimed "the same figures, in the same words" | 3 new tests failed on the old terms (the list is not `planSentence()`, since `planSentence` did not exist; the terms page does not use the shared helper; the "same words" claim is present). A fourth, the `/pricing` row-token test, passed already and stays as a guard | `planSentence()` added to `frontend/lib/pricing.ts`; the terms render `pricingPlans(PRICING).map(planSentence)`, the same rows `/pricing` renders as its table. The claim now reads "The same plans and figures are shown as a table on the pricing page." Tests compare the terms' list items with `planSentence()` of each plan and check every name, figure and unit appears in order in each `/pricing` row; a literal unit test pins the three sentences. Break arm (list item replaced with typed text) turned two tests red. |

Round totals: the new file now holds 46 tests (7 added), `lib/__tests__/pricing.test.ts` 1 added. RED run: 6 failed, 40 passed, 46 total. After the fix: 46 of 46.

Gates this round: full `npm test` rc 0 with `--maxWorkers=3` (304 suites, 3650 tests). Two earlier runs at default parallelism failed one to four unrelated dashboard suites (`create-fulfilment`, `products-orders-shop-scope`, and two others) on timeouts while the machine load average was about 53; the first of those passes alone (11 of 11) and the full run is clean at lower parallelism. `npm run lint` rc 0 (26 pre-existing warnings, 0 errors). `npm run build` rc 0. `docs-freshness.sh` rc 0. `check-doc-metrics.sh` rc 0. CHANGELOG entry (#1116) updated with the round-1 bullet.

Not re-run this round: the Playwright specs (no page markup other than the two sentences and the plan list changed).
