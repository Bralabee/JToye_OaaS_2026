# Persona 18: Ravi, freelance integrator (pass 2, 2026-10-03)

## Persona

I'm Ravi, 27, a freelance developer. Tenant A (Mama Ade's Kitchen, Peckham Jollof Co., Brixton Village Grill) has hired me on a fixed fee. The owner's brief:

> "Connect my shop to our WhatsApp bot and to ChatGPT/Claude so they can take orders. Push every new order to our Google Sheet via webhook. Sync stock from our EPOS nightly."

I work on a laptop with curl, a terminal, Swagger and an MCP client. I bill by the day, so every dead end costs the vendor money. My first question is how I get credentials without asking for the owner's password.

Surfaces: Core API `:9090` (`/v3/api-docs`), edge gateway `:8089`, MCP server `:9100/mcp` (stateless Streamable HTTP, bearer pass-through), and the dashboard at `/dashboard/webhooks` (Playwright, 1440px).

## Journey narrative

1. **Developer onboarding.** I looked for a "Developers", "API keys" or "Integrations" page. The dashboard nav has Dashboard, Shops, Products, Orders, Customers, Finance, Marketing, Kitchen, Go live, Approvals, Staff, Webhooks and Image review. There is nothing for developers (`w01-dashboard-nav.png`). I probed `/dashboard/developers`, `/dashboard/api`, `/dashboard/settings`, `/dashboard/integrations`, `/developers` and `/docs`, and all six return 404 (`webhooks-ui-log.txt`). The repo docs (`docs/security-scopes.md`) describe least-privilege machine clients (`integration-catalog-ro`, `integration-orders-rw`). Both are created by a Keycloak realm import, and both are hard-wired to tenant A's UUID. A vendor cannot create, see, scope or rotate one. To work at all, I used the owner's `admin-user` password through a password grant on the `core-api` client. That grant also needs the platform's confidential `core-api` client secret, which no real vendor has. The token I got is a full admin token with `catalog:write orders:write customers:write`, and it expires after 300 s.
2. **OpenAPI against live responses.** I diffed 7 responses I actually received against `/v3/api-docs` with a schema walker (`oas-diff.mjs`). The responses: shops list, products list, product create, product update, order create, orders list and order detail. All 7 had **0 mismatches** in field names, types and enums (`oas-diff.txt`). To prove the diff can fail, I renamed `shopId` and stringified `pricePennies` in a copy, and it flagged all 3 mismatches (`oas-diff-breakarm.txt`). The spec does have hygiene problems (P2-RAV-15).
3. **"Mark plantain out of stock".** I created my own `ravi-Fried Plantain` (stock 40) in the unpublished "Unsorted legacy items" shop. A natural `PUT {"available":false}` returns 400 and asks for SKU, title, ingredients, allergen mask and price. `PATCH` returns 405. A full PUT that left out `quantityInStock` changed it from **40 to null ("unlimited")**, while every other omitted field was kept. I reproduced this twice, including with GET afterwards (`prod-get-after-put.json`).
4. **Idempotency over REST.** I created an order with an `Idempotency-Key`. The identical retry returned the same order id. The same key with a different body returned a typed **422** `idempotency-payload-mismatch`. Both the first call and the replay return 201, with no header to say which is the replay. The timestamps are re-serialised (`+01:00` on the first response, `Z` on the replay) (`order1/2/3.json`, `order1.h`, `order2.h`).
5. **MCP session as an AI client.** `initialize` and `tools/list` work and return 5 tools: `list_products`, `list_shops`, `read_orders`, `create_order` and `create_customer`.
   - *"What's on the menu at Mama Ade's?"* `list_products` has no shop filter. Passing `shopId` is silently ignored, and I got all 30 tenant products. They included the unpublished shop's items, other personas' `chaos-p2` items and shop-less orphans. Allergens come back only as an integer bitmask (`mcp-list-products-100.json`).
   - *"Place a collection order for 2 jollof for Ravi."* There are five "jollof" candidates, two of them titled exactly "Jollof Rice" (`mcp-jollof-candidates.tsv`). The order lands in **DRAFT**. The MCP has no tool to submit it, and the kitchen lists only CONFIRMED, PREPARING and READY orders. The replay and different-body cases behave as they do over REST (`mcp-order1/2/3.json`).
   - *"Mark plantain out of stock."* There is no MCP tool for this, so it can't be done over MCP.
   - Edge cases (`mcp-edge-cases.txt`): Peckham's product ordered under Mama Ade's shopId gives a 404 (good). An **unavailable** product is accepted, and the order **submits to PENDING**. A shop-less, unavailable product is accepted. An order with no customer details at all is accepted. Quantity 0, a non-UUID and DELIVERY without an address are all rejected by Zod with structured paths. Core errors reach the agent as flattened prose (`core 422 Idempotency Key Reused: …`).
6. **Webhooks.** In the UI, `http://` is refused inline (`w04`). The signing secret is shown once, Escape cannot dismiss it, and closing needs an explicit "I've saved it" (`w05`, redacted). Rotation warns that there is no overlap window (`w10`, `w11`). There is no "send test event" button. Because `example.com` is a public HTTPS host, **real deliveries happened**. Other personas' order events and my own submit produced `order.preparing` and `order.pending`. Each got HTTP 405 and was retried. Within about 46 s the subscription was **AUTO_PAUSED** (10 consecutive failed attempts). Both events went terminal FAILED after 5 of the documented 8 attempts (`deliveries1.json`, `w08`, `w09`). The delivery log, auto-pause banner and Replay buttons are clear. No email went to anyone (Mailhog search "webhook" = 0). I checked the documented HMAC test vector with openssl and it matches byte for byte; changing 1 byte gives a different digest. That same vector shows the payload carries only ids and statuses, with no items, totals or customer (`vec-body.json`).
7. **EPOS sync through the edge.** I sent 3 products plus a `type:"stock"` item to `POST :8089/api/v1/sync/batch`. The response was `202 {"status":"SUCCESS","processed_count":3}`: the stock item was **silently skipped**, and nothing said which item was dropped. The two new SKUs were created with `shopId: null`, so **#727 is confirmed live**. They don't appear on any storefront, even though the spec says null means "available on all tenant shops". One of them, titled "Jollof Rice", **collides** with Mama Ade's real "Jollof Rice". The same-SKU item updated my existing product's price. `SyncItem` has no stock or availability field at all. Through MCP I ordered the orphan "Jollof Rice" at Mama Ade's. The order line records **allergenMask 0**, while the shop's real Jollof Rice declares **256** (`mcp-orphan-order-detail.json`).
8. **Rate limits.** Core sends `X-RateLimit-Limit: 120` and Remaining/Reset headers, and the remaining budget is shared with every user in the tenant. The edge sends no rate headers. I sent 70 *unauthenticated* requests to the edge and got 41 × 401 and **29 × 429**. The 429 body is `{"error":"rate limit exceeded"}`, with no `Retry-After` (`edge-burst.txt`, `edge-429-sample.txt`). The edge bucket is one process-wide limit of 20 rps with a burst of 40, applied before authentication. From the code, Core's own 429 is RFC 7807 (`type …/errors/rate-limited`, `retryAfterSeconds`). I did not trigger it, because draining tenant A's shared bucket would have locked out the other personas running at the same time.
9. **WhatsApp.** The edge's WhatsApp webhook returns `503 {"error":"webhook signing not configured"}` with `Retry-After: 300`. In the code it is a single platform-wide `WHATSAPP_DEFAULT_TENANT_ID`/`WHATSAPP_DEFAULT_SHOP_ID` set by environment variable. One vendor on the whole platform can have a bot, and only an operator can set it up.
10. **Cleanup.** I deleted all 8 orders (204) and all 3 products (204). The webhook subscription is revoked, but it **cannot be deleted** (`DELETE` returns 405), so a REVOKED `https://example.com/ravi-gsheet-hook` row stays in tenant A's webhook list. I created no customers (`cleanup-log.txt`).

## First impressions (first 10 seconds)

Swagger loads, `/v3/api-docs` is rich, and the error bodies are proper RFC 7807 with Natasha's-Law field messages. That felt like a grown-up API. Ten seconds later I was asking where I get a key, and there is no answer in the product.

## Trust, clarity, speed

- **Trust:** the contract is honest: the OpenAPI matched every response I diffed. Idempotency is real, and the webhook signing is careful. The *behaviour* behind the contract is what worries me. "Out of stock" doesn't stop API or MCP orders. An omitted field turns stock tracking off. Agent orders sit in DRAFT. Sync drops items silently.
- **Clarity:** the webhooks UI is the clearest part of the product. The MCP tool descriptions say nothing about DRAFT, and the error mapping drops the field-level detail.
- **Speed:** every call came back fast on localhost. I did not throttle, so this tells you nothing about production. Token lifetime (300 s) and the shared per-tenant budget are the real speed limits for an integration.
- **Mobile:** not applicable. This is laptop work; I didn't test the dashboard at phone width.

## Expectations vs reality

| Expected | Got | How it felt |
|---|---|---|
| An API key or scoped client in Settings | No developer surface; the owner's password plus a platform client secret | Uncomfortable. I'd be holding the keys to their whole business |
| `PATCH available=false` | 400 or 405; a full PUT turned stock tracking off | Scary for a nightly job |
| Out-of-stock blocks orders | API and MCP accept and submit unavailable items | The bot will sell food they don't have |
| An agent order reaches the kitchen | DRAFT, no submit tool, kitchen hides it | Customer told "ordered", nothing gets cooked |
| A webhook payload I can put in a Sheet row | Only ids and status | I need an API call back, with credentials I can't get |
| A test-delivery button | None; real traffic had to fail first | Fiddly |
| Retries survive a blip | Auto-paused in about 46 s, nobody told | Friday-night data loss |
| EPOS stock sync | Sync has no stock field; skipped items still report SUCCESS | Brief item 4 can't be done |
| A typed 429 | Core yes (from the code); edge no | Half there |
| Plug MCP into ChatGPT/Claude | No OAuth discovery; 5-minute bearer | Needs a custom proxy |
| Connect our WhatsApp bot | One platform-wide env-var tenant, currently 503 | Brief item 1 can't be done for this vendor |

## Findings

| ID | Severity | Status | Finding | Evidence |
|---|---|---|---|---|
| P2-RAV-01 | blocker | CONFIRMED | No way for a vendor to give a developer scoped credentials. The only path is the owner's password plus the platform's confidential `core-api` secret | `w01-dashboard-nav.png`, `webhooks-ui-log.txt` |
| P2-RAV-02 | major | CONFIRMED ×2 | API and MCP orders accept, and submit to PENDING, products marked `available=false` | `rest-unavailable-order.json`, `mcp-edge-cases.txt` |
| P2-RAV-03 | major | CONFIRMED ×2 | A product PUT that omits `quantityInStock` silently turns stock tracking off. There is no PATCH, and flipping availability means resending the allergen fields | `prod-put.json`, `prod-get-after-put.json` |
| P2-RAV-04 | major | CONFIRMED | MCP `create_order` lands in DRAFT, with no submit tool and no webhook event. The kitchen never sees AI orders | `mcp-order1.json`, `api-docs.json` |
| P2-RAV-05 | major | CONFIRMED | (confirms issue #727 live) Synced products are shop-less, orderable at any shop, and listed to agents under duplicate titles. The order line records the wrong allergen mask (0 vs 256) | `products-after-sync.json`, `mcp-jollof-candidates.tsv`, `mcp-orphan-order-detail.json` |
| P2-RAV-06 | major | CONFIRMED | `/sync/batch` can't carry stock or availability, skips unknown items silently, and still reports SUCCESS | `sync-body.json`, `sync-resp1.json`, `edge-openapi.json` |
| P2-RAV-07 | major | CONFIRMED | MCP can't be added to hosted ChatGPT/Claude: no OAuth discovery, no `WWW-Authenticate`, and a 300 s bearer | `mcp-initialize.txt` |
| P2-RAV-08 | major | CONFIRMED | The webhook payload has no items, totals or customer, so a Sheet row can't be built without API credentials | `vec-body.json` |
| P2-RAV-09 | major | SUSPECTED | Redirects are disabled on webhook delivery, so a Google Apps Script web-app receiver (which 302s) would always fail | code read only |
| P2-RAV-10 | major | CONFIRMED | Auto-pause after about 46 s of failures cuts the documented 8 attempts to 5, and nobody is notified | `deliveries1.json`, `w08-list-auto-paused.png`, `w09-delivery-log-failed.png` |
| P2-RAV-11 | minor | CONFIRMED | No test-delivery button, no request/response body in the delivery log, and subscriptions can't be deleted | `w06-list-after-create.png`, `w09-delivery-log-failed.png`, `cleanup-log.txt` |
| P2-RAV-12 | major | CONFIRMED | The edge rate limit is one global bucket applied before auth. Its 429 is untyped, with no Retry-After and no rate headers | `edge-burst.txt`, `edge-429-sample.txt` |
| P2-RAV-13 | minor | CONFIRMED | MCP flattens Core's RFC 7807 errors to prose and drops the `type` URI and the per-field `errors` map | `mcp-order3.json` |
| P2-RAV-14 | major | CONFIRMED | MCP `list_products` has no shop filter, silently ignores `shopId`, and gives allergens only as a bitmask | `mcp-list-products-100.json`, `mcp-tools-list.txt` |
| P2-RAV-15 | minor | CONFIRMED | OpenAPI hygiene: internal OAuth `tokenUrl`, `*/*` on 117 of 140 operations, 429 documented on 1 of 140, Spring internals in the schemas, snake_case vs camelCase between edge and Core | `api-docs.json`, `edge-openapi.json` |
| P2-RAV-16 | minor | CONFIRMED | Orders with no customer name, email or phone are accepted over API and MCP | `mcp-edge-cases.txt` |
| P2-RAV-17 | polish | CONFIRMED | An idempotent replay can't be told apart (201, no header), has no Location, and the body is byte-different | `order1.h`, `order2.h`, `order1.json`, `order2.json` |
| P2-RAV-18 | major | CONFIRMED | WhatsApp is one platform-wide env-var tenant and shop. A vendor can't connect their own bot | journey step 9, `edge-openapi.json` |
| P2-RAV-19 | positive | CONFIRMED | The OpenAPI matches the live responses: 0 mismatches across 7, and a break arm proves the diff can fail | `oas-diff.txt`, `oas-diff-breakarm.txt` |
| P2-RAV-20 | positive | CONFIRMED | Idempotency over REST and MCP: a replay returns the original order, and a different body gets a typed 422 | `order1-3.json`, `mcp-order1-3.json` |
| P2-RAV-21 | positive | CONFIRMED | Webhook secret UX, delivery log, auto-pause banner and replay are good, and the HMAC test vector reproduces with openssl | `w04`, `w05`, `w09`, `w10`, `w11`, `vec-body.json` |
| P2-RAV-22 | positive | CONFIRMED | Bad agent input is rejected with typed, field-level errors, and a cross-shop product gets a non-disclosing 404 | `mcp-edge-cases.txt`, `mcp-edge-A.txt` |

None of these repeat a pass-1 finding. Pass-1 ADE, F (Dele) and C (Claire) did not cover the API, MCP or webhook integrator surfaces, except Dele's F2 (webhook SSRF) and F4 (no cross-tenant path), which I did not retest.

## What delighted me (keep these)

- **The OpenAPI tells the truth.** 0 mismatches across the 7 responses I diffed, with a break arm proving the diff can fail.
- **Idempotency is real.** The same key replays the same order, and a changed body gets a typed 422, over both REST and MCP. MCP makes the key mandatory.
- **The webhook secret is handled the way Stripe does it:** shown once, can't be dismissed by accident, an explicit rotation warning, 43-char base64url. HTTPS is enforced in the UI. The auto-pause banner is clear, and there are Replay buttons.
- **The HMAC test vector in the docs works with openssl**, byte for byte.
- **Core validation errors are RFC 7807, with per-field Natasha's-Law messages.** The cross-shop product is refused as 404, so it doesn't reveal that the product exists.
- **MCP Zod validation catches bad agent input** (quantity 0, names instead of UUIDs, DELIVERY without an address) with machine-readable paths.

## Verdict, in character

I wouldn't take the fixed fee. Two of the four brief items can't be done: EPOS stock sync and a WhatsApp bot for this vendor. The other two need workarounds I'd be ashamed to hand over: borrowing the owner's admin password, and a polling proxy to keep a 5-minute token alive for ChatGPT. And the AI orders would sit in DRAFT where the kitchen never sees them. I'd tell the vendor the platform is "API-shaped, not integration-ready".

**The one thing that would change my mind:** a dashboard "Developers" page where the owner creates a scoped, revocable client (for example orders-only, no catalogue write) for their own tenant. Put a real PATCH for stock and availability behind it, and make the API respect `available=false`.

## Scores (1 to 10)

- **Ease: 3.** There's no credential path, partial updates are traps, and the MCP has no shop filter and no submit tool.
- **Trust: 4.** The contract and the signing are excellent. The out-of-stock and stock-wipe behaviour would cost the vendor real food and money.
- **Speed: 6.** Fast on localhost (unthrottled, so not proof). The 300 s tokens and the shared tenant budget slow integrations down.
- **Mobile: N/A.** Laptop persona; phone width not tested.
- **Overall: 4.** Solid foundations, not yet usable by a third-party developer without platform-operator help.

## Leftovers after cleanup

- Webhook subscription `156f721d-7f6f-426d-84a7-1ea248730d6f` (`https://example.com/ravi-gsheet-hook`) is REVOKED but still listed. There is no delete endpoint.
- Its 2 FAILED delivery rows (kept for 30 days by design).
- Outbox and webhook events already emitted for my deleted orders. Each order received a 204 on delete, and GET now returns 404.
- None left: products (0 `RAVI-*` SKUs remain), orders (all 8 deleted) and customers (none were created).
