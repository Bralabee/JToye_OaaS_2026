# Branded Storefronts — a vendor's own web address, served by OaaS

**Status: FUTURE. Not part of v2.3, not in `ROADMAP.md`, not in `ISSUE-DISPOSITION.md`.**
Scoped 2026-10-31 at the owner's request. Nothing here is started, numbered, or scheduled. It
does not compete with any numbered phase in `ROADMAP.md` (28–39 as of 2026-11-01) for time,
review, or Flyway numbers.

## Why it exists

Three Birmingham food businesses are using, or talking about using, OaaS: Remrem Catering, a
sole-trader kitchen, and Toye Foods, which is in discussion. Toye Foods wants OaaS as it is, plus
its **own branded web page**, while staying listed on the OaaS storefront. The question asked on
2026-10-31 was whether OaaS can power a vendor's own website.

Three options were considered. The owner ruled on 2026-10-31:

| | Option | Ruling |
|---|---|---|
| **3** | A vendor's own web address (`order.toyefoods.co.uk`) served by OaaS, with the vendor's branding | **Main plan (this document)** |
| 2 | The vendor's own site, built by their developer, using the OaaS public API | Future alternative (§ Alternatives) |
| 1 | The vendor's existing site links to their OaaS shop page (a button or QR code) | Future alternative; works today with no build |

## Entry gate: do not start until ALL of these are true

1. **Phase 32 is complete**: a `v2.3` tag exists, production is deployed, and one real vendor has
   completed onboarding, a published shop and a first paid order **in production** (GTM-01, GTM-02).
2. **PAY-03 is checked** in `REQUIREMENTS.md`: the full money loop has run on staging, evidenced
   from Stripe's API. A branded front door on an unproven payment path is the wrong order.
3. **The production domain is settled and serving TLS.** As of 2026-10-31 it is a blocking
   decision in `ROADMAP.md` ("`jtoye.co.uk` is registered but parked"). Per-vendor addresses are
   layered on the platform's own address, so that one comes first.
4. **At least one vendor has committed to using it.** Toye Foods is the candidate. Without a
   committed vendor, this is not built.

When the gate opens, plan this as its own milestone or phase through GSD (`/gsd-new-milestone` or
`/gsd-phase`). **Re-verify every "Evidence" line below first**: they were read at `10a34003` (main merged in,
2026-11-01), and the code will have moved. `scripts/check-doc-citations.sh` does not scan this
file by default (its list is the evergreen docs); run it scoped,
`CITATION_DOCS=.planning/future/branded-storefronts-SCOPE.md scripts/check-doc-citations.sh`,
to check the `file:line` citations here.

## What the vendor and their customer get

- The vendor's customers go to the vendor's own address and see the vendor's shop with the
  vendor's logo, colours and name. No OaaS directory, no other vendors, no OaaS-first header.
- They browse, order, pay and track the order without leaving that address.
- The same shop is still listed on the OaaS storefront. Orders from both addresses land in the
  same vendor dashboard, the same order list, the same payouts. **There is one shop record, not two.**
- A small "Powered by OaaS" mark stays in the footer (whether it can be removed is an open decision).

## Evidence: what exists today (`10a34003`, 2026-11-01)

| Area | Today | Gap for this feature |
|---|---|---|
| Shop pages | `frontend/app/shop/[slug]/` (page, cart, checkout), slug-addressed, with old-slug 301s (`V86__shop_slug_alias.sql`) | No link between a web address (host) and a shop |
| Branding | `shops.logo_url`, `banner_url`, `description` (`V16__public_storefront.sql`) | No colours, font, favicon, or display-name override |
| Public API | `PublicStorefrontController.java` under `/api/v1/public` (shop, products, slots, guest order, reviews, promotions) | Reused as is; nothing to build |
| Page metadata | `generateMetadata` in `shop/[slug]/page.tsx`: canonical is `/shop/<slug>`, origin from `resolvePublicOrigin(req)` in `frontend/lib/public-origin.ts` (`APP_PUBLIC_ORIGIN` → `NEXTAUTH_URL` → the request origin LAST, on purpose: a raw `Host` header is attacker-chosen, and inside a container it is a bind address, #467/#504), **one origin per deployment** | Canonical and `metadataBase` must follow the vendor's address for that shop, resolved from a VERIFIED address record and passed in above the env values — never read from the raw `Host` header |
| Customer sign-in | `jtoye-customers` Keycloak realm, `storefront-client`, PKCE, HttpOnly cookies (`shop/signin/page.tsx`; the flow is `frontend/lib/customer-auth.ts`, whose `redirect_uri` is already built from `window.location.origin`, so it is per address today). `frontend/auth.ts` is the VENDOR realm's NextAuth config, not this flow. Sign-out: `app/api/customer-auth/logout-url/route.ts` builds `post_logout_redirect_uri` from `resolvePublicOrigin(req)` | Cookies are per address: each vendor address needs its own redirect URI and web origin in Keycloak. Sign-out on a vendor address must return to that address: today it would land on the platform origin, which breaks BSF-05 |
| CORS | `CorsConfig.java`, a fixed list from `cors.allowed-origins` | Only matters if the browser calls the API from the vendor address; avoid that by sending calls through the same-address frontend |
| Email links | `NotificationProperties.Unsubscribe` holds `baseUrl` and `oneClickBaseUrl` (two values, #516); `notification.email.tracking-base-url` is read by `EmailNotificationService`, `OrderPaymentLinkService` (through `jtoye.payment-links.base-url`, which falls back to it), `DsarAccessExportService` and `DsarOutcomeMailer` | Order, tracking and payment-link links must point at the address the customer ordered from. DSAR links are customer-scoped, with no order to read an address from: they stay on the platform address |
| TLS / ingress | nginx ingress, cert-manager `letsencrypt-prod`, explicit host list per overlay (`k8s/base/ingress.yaml`, `k8s/staging/ingress-hosts-patch.yaml`) | Hosts are written into manifests by hand; vendor addresses must be added without a manifest edit and redeploy per vendor |
| Framing | `X-Frame-Options: DENY` (`k8s/base/ingress.yaml:46`) | **Stays.** This feature never embeds the checkout in another site |
| Settlement | `OnboardingModel.WHITE_LABEL` → `DIRECT_CHARGE_VENDOR_MOR` | **Unrelated.** "White label" in the code is about who is the legal seller, not branding. Do not conflate the two |

## Scope

### Requirements (draft IDs, `BSF-*`, to be renumbered when planned)

- **BSF-01 — Address → shop resolution.** A request for a registered vendor address renders that
  shop's storefront at `/`. An unregistered address gets the platform's normal 404. The platform's
  own address behaves exactly as it does today.
- **BSF-02 — Address registration and verification.** A vendor (or an operator, in the first
  version) adds an address. OaaS shows the DNS record to create (a CNAME to a platform target),
  checks it, and only then activates the address. **An unverified address is never served**, so
  nobody can point a domain they do not control at someone else's shop.
- **BSF-03 — TLS per address, automatic.** Activating an address issues its certificate with no
  manifest edit and no redeploy. Renewal is automatic. Expiry and failure raise an alert.
- **BSF-04 — Branding.** Per shop: primary and accent colour, favicon, display name, and an
  optional "about" block. Colours checked for contrast before they are saved (the axe gate in
  `frontend/e2e/public-a11y.spec.ts` must still pass on a branded page).
- **BSF-05 — Scoped navigation.** On a vendor address, every link stays within that shop: no
  directory, no other vendors, no cross-shop search. Leaving the shop means leaving the address.
- **BSF-06 — Sign-in and accounts.** A customer can sign in on the vendor address. It is the same
  `jtoye-customers` account as on the OaaS storefront: one customer, one order history.
  Keycloak redirect URIs and web origins are managed per active address, not edited by hand.
- **BSF-07 — Checkout and payment.** Stripe checkout and its return/redirect URLs go back to the
  address the customer started on. The money path itself does not change.
- **BSF-08 — Links in email, SMS and WhatsApp.** Confirmation, tracking, payment-link and
  unsubscribe links use the address the order was placed on, stored on the order at creation.
- **BSF-09 — SEO.** On a vendor address the canonical URL is that address. On the OaaS
  storefront the same shop's page carries a canonical pointing at the vendor address (decision D3
  below). Per-address `robots.txt` and `sitemap.xml`.
- **BSF-10 — Tenancy and security.** Address → shop lookups are tenant-safe: an address resolves to
  exactly one shop, an address cannot be reassigned while active, and a removed address stops
  serving at once. Reviewed by `oaas-tenancy-security` before merge.
- **BSF-11 — Operations.** Metrics per address (requests, errors, certificate state), an alert on
  certificate failure, and a runbook for "the vendor's address shows the wrong thing / nothing".

### Likely building blocks (to be confirmed when planned, not decided)

1. **Data:** a `shop_domains` table (host, shop, status PENDING/VERIFIED/ACTIVE/REMOVED, verified
   date, certificate state) with globally unique host, like `shops.slug` and `shop_slug_alias`.
   Branding columns on `shops` or a `shop_branding` table. Flyway number: the next free one at
   the time; never reserve one now.
2. **Frontend:** host-based resolution in `frontend/middleware.ts`. On a vendor host, rewrite `/`
   AND the whole `shop/[slug]/**` subtree (cart, checkout, orders) plus the shared `shop/signin`,
   `shop/account`, `shop/orders` and `shop/auth/callback` routes to that shop, and make every
   emitted path host-aware: the old-slug `permanentRedirect('/shop/<canonical>')` in
   `shop/[slug]/page.tsx`, the checkout `returnUrl` and its `router.push` targets. Rewriting `/`
   alone leaves a basket on `order.toyefoods.co.uk` at `/shop/toye-foods/cart` and the directory
   reachable, against BSF-01 and BSF-05. The middleware matcher excludes `/api/*`, `_next/*` and
   static assets, so `app/api/customer-auth/*` and any route-served `robots.txt` / `sitemap.xml`
   need their own host → shop lookup. That lookup is a cached call against a new public endpoint
   that returns only VERIFIED, ACTIVE addresses, and its origin is passed into
   `resolvePublicOrigin()` above the env values, never derived from the raw `Host` header.
3. **TLS and routing:** compare (a) cert-manager with one Certificate per address created by a
   controller or operator job, against (b) a front layer that issues certificates on demand
   (Caddy on-demand TLS, or a managed service such as Azure Front Door custom domains). (a) fits
   the current nginx + cert-manager setup; (b) scales better past a handful of vendors. Decide by
   how many vendors are expected in year one.
4. **Keycloak:** an automated sync of active addresses into the `storefront-client` redirect URIs
   and web origins, through the admin API, with an audit trail.
5. **Backend:** an "origin" field captured on every order and used by notifications; the
   notification base URL becomes per-order instead of global.

### Out of scope for the first version

- Vendors editing page layout or adding their own pages (this is a branded shop, not a website builder).
- Vendors uploading their own TLS certificates.
- Selling domain names, or managing a vendor's DNS for them.
- Embedding OaaS in another site through an iframe (framing stays denied).
- Removing the "Powered by OaaS" mark, unless decision D2 says otherwise.

## Decisions the owner must make before planning

- **D1 — Pricing.** Is a branded address part of a paid tier (`tenants.billing_tier`), an add-on,
  or included? It affects whether BSF-02 has a billing check.
- **D2 — The OaaS mark.** Always shown, or removable on a higher tier?
- **D3 — Where search engines send people.** Should the vendor address be canonical (the
  vendor gets the search traffic, OaaS loses some), or the OaaS page (the reverse)? The default
  proposed here is the vendor's address, since it is what they are paying for.
- **D4 — Self-serve or operator-run.** First version: an operator adds addresses for vendors
  (fewer moving parts). Self-serve comes later.
- **D5 — Subdomain fallback.** Offer `toyefoods.<platform-domain>` to vendors with no domain of
  their own? BSF-01 covers the routing but NOT the certificate: the cluster issuer
  (`letsencrypt-prod`, `k8s/base/ingress.yaml`) solves ACME over HTTP-01
  (`k8s/base/networkpolicies/60-acme-http01-solver.yaml`), and Let's Encrypt issues a wildcard
  `*.<platform-domain>` only over DNS-01, which needs a DNS-01 solver and a DNS-provider
  credential. That is its own building block, not a by-product of BSF-03.

## Risks

- **Domain takeover.** An address left pointing at OaaS after a vendor leaves, or a dangling DNS
  record. Mitigated by BSF-02 verification and BSF-10 immediate removal.
- **Certificate rate limits and failures.** Let's Encrypt limits per registered domain and per
  account. Needs BSF-11 alerting before more than a few vendors.
- **Split sign-in.** A customer signed in on the OaaS storefront is not automatically signed in on
  the vendor address (different cookies). The Keycloak session makes it one redirect, not a new
  password, but it must be tested on a real second address.
- **A regression on the platform's own address.** The host middleware runs on every page request
  (the matcher in `frontend/middleware.ts` excludes `/api/*`, `_next/*` and static assets). The
  current Playwright and axe suites must pass unchanged on the platform address, and a
  vendor-address suite added beside them.

## Alternatives (future, not planned)

### Option 1 — Link from the vendor's own site

Works today: the vendor puts a button or QR code on their existing site pointing at
`/shop/<slug>`. Old-slug 301s (`V86`) keep printed QR codes working. No build. Offer it to any
vendor in the meantime, including Toye Foods.

### Option 2 — The vendor's own site on the OaaS public API (headless)

For a vendor with their own web developer who wants full control of the design.

- **Exists:** the public API (`/api/v1/public`), guest orders with `Idempotency-Key`, signed
  webhooks (`core/webhook/`).
- **Missing:** partner API credentials and per-partner rate limits (the public endpoints are
  anonymous today); self-serve CORS origins (today `cors.allowed-origins` is a config change and
  a redeploy); a supported checkout and payment flow from another origin; published, versioned
  API documentation and a stability promise.
- **Gate:** option 3 is live, and a vendor with a developer asks for it. Option 3 should come
  first because it keeps checkout, payment and customer data on OaaS's side.
