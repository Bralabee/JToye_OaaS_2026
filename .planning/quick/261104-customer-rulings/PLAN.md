# 261104-customer-rulings: five owner rulings from the persona close-out (2026-11-04)

Branch `fix/persona-customer-rulings`. Lane id `customer-rulings`. Issues: #788 (+SW-CLR-14), #836 (+SW-CLR-05), #862, #868, SW-JRD-07 / SW-SAM-11 (epic #964).

Each is an OWNER RULING made 2026-11-04. Where a future reader needs the why, the code comment says "owner ruling 2026-11-04, persona close-out".

## Per item

| # | Ruling | Fix | Test (written first, seen RED) |
|---|--------|-----|--------------------------------|
| 1 | #788/SW-CLR-14: no "UK food-hygiene verified" claim nobody performs | Marker removed from the landing trust strip. Reworded-claim option rejected: the public shop DTO carries no FHRS rating, so "shows each kitchen's FSA rating" would be false too. FSA manual-review adjudication stays unbuilt. | `landing-no-unperformed-verification-claim.test.tsx`: rendered page + source scan of app/components/lib |
| 2 | #836/SW-CLR-05: internal strategy pages off the public site | `/competitive` and `/business-model-guide` (+ PDF, components, colours, tests) deleted; footer operator-surface list, ssr-routes gate list, docs SITEMAP/PRD/index updated. Direct hit = Next 404. | `internal-strategy-pages-removed.test.ts` (route dirs, PDF, sitemap list, no inbound links, no repo path on a public page) + Playwright `internal-strategy-404.spec.ts` |
| 3 | #862: allergen filter by what the vendor DECLARES, never "free from" | `GET /public/shops?declares=Peanuts,...` (any-of, AllergenCatalog names, unknown = 400, with distance = 400); a search term that IS an allergen name also matches declaring dishes; `gluten free` is never read as `declares gluten`. "Show dishes that declare" select on the directory and on each menu with an always-visible declaration help line. | `AllergenCatalogTest` (+4), `DeclaredAllergenSearchIntegrationTest` (NOSUPERUSER, 7), `PublicStorefrontControllerTest` (+4), 3 Jest files |
| 4 | #868: customer cancels own order while PENDING; card refunded via RefundService | `POST /public/orders/{n}/cancel` (guest: number+email in body; signed in: `X-Customer-Token`), `Idempotency-Key`, typed 409 `order-not-cancellable-by-customer` (contactShop), typed 502 `refund-failed`. `CustomerOrderCancellationService` runs the same `OrderService.applyTransition` every staff cancel runs (kitchen board SSE, stock, slot, outbox, email) with reason CUSTOMER_REQUEST, with the ledger REVERSAL and a reserved Refund row committed first, then `RefundService.issueCancelRefund` (Stripe, no transaction open). Next proxy `/api/customer-orders/{n}/cancel`; `CancelOrderPanel` on `/track` and the order page. | `CustomerOrderCancelIntegrationTest` (14, NOSUPERUSER), Jest panel + proxy |
| 5 | SW-JRD-07/SW-SAM-11: edit display name, change-password link | The name lives only in Keycloak. Core `PUT /public/customer/profile` changes it through the EXISTING admin seam (`KeycloakAdminClient`), account chosen by the verified token `sub`. Next proxy `/api/customer-profile` re-issues the session cookies. "Your details" + "Change password" link (Keycloak account console) on `/shop/account`. No new store, no migration. | Java unit + controller tests, Jest route + page tests |

## Not decided here (listed for the owner)

- #868: deposit / paid-in-parts orders and orders whose payment has not settled are refused online (contact the shop). The refund amount for those was not ruled.
- #862: only the "declares" direction was ruled. An exclude ("hide dishes that declare X") filter was not built.
- #788: who adjudicates an FHRS MANUAL_REVIEW (#453) is untouched.

## Threat notes

- #868 ownership is the tracking pair read under `orders_guest_tracking`; the order's tenant is pinned only AFTER the pair matches; wrong pair = same 404 as tracking (no oracle); a bad token is a 401 and never falls back to the body email. The unauthenticated half of the transition is package-private.
- #868 money: cancel is flushed before the refund (a kitchen that just accepted fails on `@Version` before money moves); the cancel, a CREATING Refund row and the ledger reversal commit BEFORE Stripe is called (review round 1, A2); the Stripe key is the Refund row's own stored key, so a retry of an unfinished attempt re-sends the identical request (A1).
- #5: core takes the account from the verified token `sub` and re-checks the stored email equals the token's; the name is trimmed, length-capped and control-character-free; the log never carries the name or address.
- No migration in this lane (V90 is #1115, V91 the vendor-rulings lane; any later need takes V92+).
- No new third-party script; CSP unchanged. Keycloak account console is a plain link.
- MCP: none for #868 (customer's own action; the MCP server acts for a vendor). None for #5 (a customer's own profile).
