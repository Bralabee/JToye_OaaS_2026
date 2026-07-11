# Site Map — J'Toye Frontend

Audience-classified inventory of every page in `frontend/app/` (Next.js App Router).
The machine sitemap for search engines is `frontend/app/sitemap.ts` (served at `/sitemap.xml`,
public routes only). **Keep both in sync when adding pages.**

> Regenerate the raw route list with:
> `find frontend/app -name "page.tsx" | sort`

## Public / prospective-vendor pages (no login)

| Route | Purpose |
|---|---|
| `/` | Public landing page (persona routing: order food → `/shop` / run your business → `/for-operators`); wrapped in the shared public shell. Signed-in vendors reach `/dashboard` via the header, not an auto-forward |
| `/for-operators` | Prospective-vendor pitch: takeaway & catering journeys, pilot terms, fit check |
| `/business-model-guide` | Authoritative business-model decision guide (companion: `docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md`) |
| `/shop` | Storefront discovery / shop directory |
| `/track` | Order tracking by order number |
| `/auth/signin` | Vendor (B2B) sign-in — Keycloak `jtoye-dev` realm |

## Customer storefront journey (per shop, guest or customer session)

| Route | Purpose |
|---|---|
| `/shop/[slug]` | A vendor's customer-facing storefront (menu, product detail) |
| `/shop/[slug]/cart` | Cart |
| `/shop/[slug]/checkout` | Guest checkout (Stripe) |
| `/shop/[slug]/orders/[orderNumber]` | Order confirmation / status by order number |
| `/shop/orders` | Customer "My Orders" (customer session — `jtoye-customers` realm) |
| `/shop/auth/callback` | Customer OIDC callback |

## Vendor dashboard (B2B, authenticated)

Everything the dashboard sidebar exposes to a signed-in vendor:

| Route | Sidebar item |
|---|---|
| `/dashboard` | Dashboard (overview) |
| `/dashboard/shops` | Shops |
| `/dashboard/products` | Products |
| `/dashboard/products/import` | — (bulk import, linked from Products) |
| `/dashboard/orders` | Orders |
| `/dashboard/orders/[id]` | — (order detail, linked from Orders) |
| `/dashboard/customers` | Customers |
| `/dashboard/finance` | Finance |
| `/dashboard/marketing` | Marketing |
| `/dashboard/kitchen` | Kitchen (KDS — real-time kitchen display) |

## Vendor onboarding

Phase 18 slice 1 ships onboarding as **backend APIs only** (`POST /onboarding`,
`GET /onboarding/me`, `POST /onboarding/go-live` — state machine + Companies House /
FSA-FHRS / allergen gates). The vendor-facing UI (signup/status page, a
"Start your application" CTA on `/for-operators`, and a dashboard entry) is the
planned follow-on slice — plan `18-07` in `.planning/phases/18-vendor-onboarding-first-slice/`.
Update this section when it lands.
