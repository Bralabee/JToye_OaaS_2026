# Persona 07: Dele, curious rival tenant (saved by the coordinator from the agent's returned report)

> **Coordinator coverage note: read this before citing "no blockers".** The probe was narrower than the brief.
> **Exercised:**
> - authenticated cross-tenant reads of PRODUCTS by ID (3 IDs → 404)
> - customer lookup by email (empty)
> - public order tracking (needs number + matching email)
> - webhook SSRF at creation
> - edge and MCP route surface
> - blob container listing (403)
> - one dashboard URL swap
>
> **NOT exercised:**
> - cross-tenant reads of orders, customers or shops by ID
> - any cross-tenant WRITE (PUT/PATCH/DELETE on a tenant A object)
> - the kitchen's real-time STOMP topic subscriptions
> - rate-limit fairness between tenants
> - quarantined/PENDING media fetch by direct key
>
> So the result is "no leak found on the surfaces probed", not "isolation proven". The RLS + NOSUPERUSER integration suites in the repo remain the stronger evidence for the unprobed surfaces.

**Verdict:** "I tried hard to see my rival's data and couldn't get a single row… I'd use it, pay, come back." What would change his mind: the platform telling vendors outright that other vendors can't see their sales or customers.
**Scores:**
- **Ease 7**
- **Trust 9**
- **Speed 7:** localhost only, not throttled.
- **Mobile 6:** not exercised.
- **Overall 8.**

## Persona
Dele, 29, a tenant B vendor (`admin-user-b` / `tenant-b-user`). He knows DevTools and is nosy about rival tenant A. This was an authorised, read-oriented test on localhost. The only writes were his own webhook subscriptions, which he created and deleted.

## Journey
1. Unauthenticated `/api/v1/shops` → 401.
2. He harvested IDs from the public storefront. `/api/v1/public/shops/{slug}/products` image URLs embed the owner's tenant UUID (`…/jtoye-images/00000000-0000-0000-0000-000000000001/…`), which shows all 3 seed shops belong to tenant A. Product UUIDs: `66cfa32c…`, `e0fb7c25…`, `fb54651b…`.
3. Media: an ACTIVE image fetched anonymously → 200 (public by design). Container listing (`?comp=list`) → 403.
4. Logged in as `admin-user-b` (`01-dashboard-logged-in.png`) and reused his own token:
   - `/api/v1/shops` → only `kemi-camberwell` (tenant …0002);
   - `/api/v1/products` → only his own probe product;
   - all 3 tenant A product UUIDs → **404** (RFC 7807);
   - customer-by-email → empty.
5. Public order tracking: missing email → 400; wrong email → 404. Orders can't be enumerated.
6. Webhook SSRF:
   - HTTP → "targetUrl must be an HTTPS URL";
   - HTTPS to 169.254.169.254, 127.0.0.1 or localhost → "resolves to a disallowed (private, loopback, or link-local) address";
   - unresolvable host → "host could not be resolved".
   - 0 created; the list is empty afterwards (`webhook-results.json`).
7. Edge (:8089) exposes only `/api/v1/sync/batch` and `/api/v1/webhooks/whatsapp`. MCP (:9100) exposes only `/health` without auth.
8. Dashboard URL `/dashboard/products/66cfa32c…` → a generic 404 page (`03-dashboard-crosstenant-product-attempt.png`).
9. `/legal` (`02-legal-page.png`): company info, privacy notice, data retention and accessibility. Nothing on vendor-to-vendor confidentiality.

## Expectations vs reality
| Expected | Got | Felt |
|---|---|---|
| Guess an ID, see a rival's product | 404 with a valid tenant B token | Relieved |
| Reuse a public ID in the vendor API | 404; doesn't even confirm it exists | Impressed |
| Track a stranger's order by number | Needs number + matching email | Fair |
| Webhook to the cloud metadata IP | HTTPS-only + resolved-IP deny | Thought-through |
| Browse rival blobs | ACTIVE image public by design; container not listable | Acceptable |
| Be told rivals can't see my data | No such statement | Had to prove it myself |

## Findings
| ID | Severity | Status | Finding | Evidence |
|---|---|---|---|---|
| F1 | positive | CONFIRMED | Cross-tenant product read → 404 for all 3 IDs | `probe-results.json`, `dele_probe.mjs` |
| F2 | positive | CONFIRMED | Webhook SSRF blocked (HTTPS + resolved private/loopback/link-local deny) | `webhook-results.json`, `dele_webhook.mjs` |
| F3 | positive | CONFIRMED | Order tracking can't be enumerated (number + email) | journey step 5 |
| F4 | positive | CONFIRMED | No cross-tenant path via edge or MCP | journey step 7 |
| F5 | minor | CONFIRMED | Tenant UUID disclosed in public image URLs (maps shop → tenant) | `probe_public.py` output |
| F6 | minor (trust) | CONFIRMED | No vendor-to-vendor confidentiality assurance in the UI or legal copy | `02-legal-page.png` |

## Goods to preserve
- Cross-tenant reads return 404, not 403, so they don't confirm the object exists.
- SSRF check on the resolved IP.
- Order tracking bound to number + email.
- Consistent RFC 7807 errors.

## Environment note
core-java was on :9091 for part of the window (#671). All probes were re-run against :9090.
