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
