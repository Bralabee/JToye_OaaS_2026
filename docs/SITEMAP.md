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
| `/for-operators` | Prospective-vendor pitch in five sections: one-kitchen hero with the standard-plan margin, both rhythms (daily takeaway and weekend batch/catering), compliance built in, V88 pricing table, four-step pilot with a dated not-yet-live list. Four product images from a fictional shop; no fit check, no PDF pack |
| `/business-model-guide` | Authoritative business-model decision guide (companion: `docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md`). **`noindex,nofollow` and not linked from the footer** since PR 1080 (SW-CLR-05); `/competitive` likewise. Its `sitemap.ts` entry is a recorded follow-up for after Phase 30 plan 30-19 |
| `/shop` | Storefront discovery / shop directory |
| `/track` | Order tracking by order number. **`noindex` + EXCLUDED from `sitemap.xml`** (FE-6) — a transactional lookup surface, not discovery content; its `robots.txt` disallow and the sitemap omission now agree. |
| `/unsubscribe` | Token-based one-click email opt-out (GDPR/PECR, COMMS-03). **`noindex,nofollow` + EXCLUDED from `sitemap.xml`** — a transactional surface, not discovery content; never renders the `email`/`token` query params into meta or body. |
| `/auth/signin` | Vendor (B2B) sign-in — Keycloak `jtoye-dev` realm |
| `/legal/terms` | Terms of use, compiled from copy already published (checkout seller and cancellation wording, the privacy notice, the operator pricing). Linked from the `/legal` hub and the footer (PR 1080) |
| `/contact` | Contact J'Toye: company identity from `lib/company.ts`, plus an email and phone from `NEXT_PUBLIC_CONTACT_EMAIL`/`_PHONE`, each shown only when set. Linked from the footer, the `/legal` hub, the seller block and `/for-operators` (PR 1080) |

> **`/unsubscribe` is deliberately absent from `frontend/app/sitemap.ts`.** It is a
> public route but must stay unindexed (its page metadata sets `robots.index=false`),
> so it is recorded here for the human inventory but omitted from the machine sitemap.

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
