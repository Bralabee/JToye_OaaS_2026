# Quick 261104-kitchen — SUMMARY (persona close-out, PR #1118)

Written by the supervisor from the executor's report (the executor's Write of this file was refused by the harness).

| Issue | Outcome |
|---|---|
| 818 | Fixed: one bump in flight per order, button disabled meanwhile (server already refuses a skipped step). |
| 820 | Fixed: row Cancel opens the reject dialog (single-order mode, required reason); 44 px row buttons on coarse pointers. |
| 822 | Fixed: server-side `q` on Orders and Customers (order number/id, name, phone ignoring spaces, email only where the caller may see it); tenant + scope in the query; `%`, `_`, `\` escaped. Customer-scoped orders list still filters client-side. |
| 824 | Fixed: grid oldest-first; ticket counts `unitCount`, falling back to lines when not recorded. Density at 1024x768 / 768x1024 not measured. |
| 866 | Fixed: a socket frame no longer advances last-synced until its order loads; failed load shows "Not updating" and re-reads the board. |
| 858 | Partly: one-tap Sold out switch via `PATCH {available}`. Inline price edit not done (PATCH refuses price by the 37-39 decision; owner ruling needed). |
| 827 | Partly: Today / 7 days / This month / All time / From-To range feeds figures, ledger and CSV. Cash/card split not done (cash orders are recorded Unpaid, see 828). |
| 857 | Fixed: `GET /orders/{id}/timeline` from the order's audit history (who/when per step) + CSV download; no migration. |

RED → GREEN: kitchen tests red first then 326/326; `OrderAndCustomerSearchIntegrationTest` 8/8 red with `q` bypassed, 8/8 green; `ListSearchTest` 5/5; orders/customers UI 12/15 red (3 assert preserved behaviour) then 492/492; products switch 6/6 red then 155/155; finance range 7/7 red then 41/41; timeline unit break arm red then green, `OrderTimelineIntegrationTest` 3/3.

Gates: `npm test` 3641 passed / 6 failed (all 5 s timeouts in `create-fulfilment.test.tsx`, passes 11/11 alone; fails 2/11 on origin/main too, pre-existing slowness under host load); lint, build rc 0; check-doc-metrics, branch-behind-base, pre-push; OpenAPI snapshot regenerated (two `q` params + timeline path); controller count 34 → 35 in docs.

Not verified: full Java suites, Go, mcp-server, Playwright/375 px, rebuilt containers; MCP follow-up for `q` and timeline recorded, not built.
