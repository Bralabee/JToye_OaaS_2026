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

## Round-1 review fixes (PR 1118): three admissible findings

Test-first: the new/changed tests were run on the unfixed code (RED), then the fix (GREEN). RED run: `OrderTimelineIntegrationTest` 3 of 5 failed and `OrderAndCustomerSearchIntegrationTest` 1 of 9 failed (log `kitchen-red-r1.log`; the guest test re-run after fixing its own test setup, `kitchen-red-r1b.log`, still red on `actorType`).

1. **Guest checkouts attributed to STAFF.** The actor is STAFF only when `revinfo.user_id` resolves to a row of the tenant's `user_directory` (the query now returns the directory id and no email); `anonymousUser`, blank, or an unresolved id is SYSTEM ("Customer or automatic"). New IT `guestAndUnknownActorsAreNotStaff` writes the revisions through the REAL `TenantRevisionListener` (audited saves under Spring's `AnonymousAuthenticationToken` and under a signed-in customer JWT with no directory row), asserts the listener stored `anonymousUser` and the customer subject, then that both read SYSTEM while a real team member's step still reads STAFF. RED: STAFF for both. GREEN. A finding of its own: a `JwtAuthenticationToken` built without authorities is unauthenticated and the listener records null, which was already SYSTEM.
2. **Colleagues' emails leaked.** The email column is gone from the timeline query, DTO and CSV; a team member without a display name is unnamed ("A team member"). IT `staffCallerNeverSeesAColleaguesEmail` fetches as STAFF and asserts no `@` anywhere in the body; the first timeline IT now asserts no email either. RED: the body carried `bola@kitchen.test`. GREEN.
3. **Phone search.** Term and column are reduced to digits and a leading `44`/`440` becomes `0`: the column in SQL (`regexp_replace(regexp_replace(phone,'[^0-9]','','g'),'^440?','0')`, wrapped in `cast(.. as String)` for HQL), the term in `ListSearch`. A term is treated as a phone only if it is made of digits, spaces and `+ - ( ) .` with at least 3 digits, and the country-code fold applies only to a term of 11+ digits so a fragment like `4477` is not rewritten. IT `phoneMatchesAcrossSpellings` stores `07...`, `+44 7... ...` and `(07...) ...-...` and finds all three with `07..`, `+44..`, `+44 (0)..`, `(07..) ..` and hyphen spellings, on orders and customers, with a decoy not matching. RED: `07..` found only the `07` row. GREEN. `ListSearchTest` gained two tests (spellings meet at national digits; fragments and non-phones).

Gates: `ListSearchTest`, `OrderTimelineServiceTest` (7), `OrderTimelineIntegrationTest` (5/5), `OrderAndCustomerSearchIntegrationTest` (9/9), `OpenApiSnapshotTest` (snapshot regenerated: the `q` description changed), controller/service unit tests green. Frontend untouched this round: jest for `components/dashboard/orders` and `app/dashboard/orders` 162/162, lint rc 0, build rc 0. `check-doc-metrics` PASS after `docs-freshness.sh --write` (java 3242, total 7270).

The 7 recorded findings were left alone as instructed.
