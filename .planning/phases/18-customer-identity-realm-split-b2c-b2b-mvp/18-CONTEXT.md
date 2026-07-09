# Phase 18: Customer Identity Realm Split (B2C/B2B) — Context

**Gathered:** 2026-07-09
**Status:** Ready for planning
**Source:** Live investigation + user design decisions this session (2026-07-09)
**Mode:** mvp

<domain>
## Phase Boundary

Separate B2C storefront-customer identity into its own Keycloak realm, decoupled from the B2B staff/vendor realm. **Phase 1 (this phase): email/password only.** Google/social login is deferred to a Phase 2 pending user-supplied Google OAuth credentials.

**User story (Goal):** As a storefront customer, I want to register and log in against a dedicated customer identity realm separate from staff/vendor accounts, so that customer and staff logins are isolated and the admin dashboard no longer offers customer self-registration.

### Verified current state (live, 2026-07-09)
- Both the admin dashboard (NextAuth, confidential client `core-api`) and the storefront (public PKCE client `storefront-client`) authenticate against ONE realm `jtoye-dev` — a shared user pool (4 users: staff + customers mixed).
- Self-registration is ON realm-wide on `jtoye-dev`, so the ADMIN login page ALSO shows a "Register / New user" link (confirmed live) — undesirable for a staff surface.
- No identity providers configured (no Google/social).

### THE load-bearing fact (verified — do not re-litigate)
**Customer/storefront tokens NEVER reach the backend.** `frontend/lib/customer-auth.ts` `fetchWithCustomerAuth` is documented as unused ("the current codebase only uses public endpoints"); `frontend/app/api/customer-auth/session` only decodes the ID token client-side (`decodeJwtPayload`). All storefront data comes from unauthenticated `/public/**` routes (tenant resolved by shop slug). core-java `SecurityConfig`/`AudienceValidator` only ever validate `core-api` admin tokens. **⇒ THE BACKEND (core-java, edge-go) NEEDS NO CHANGES.** The plan MUST NOT add backend multi-issuer validation; only note it as a future dependency IF authenticated customer endpoints are ever added.
</domain>

<decisions>
## Implementation Decisions (LOCKED)

### Realm topology
- Create a NEW realm `jtoye-customers` (name locked) for B2C customers.
- It gets its OWN gitignored render template + a SECOND `keycloak-realm-render` + import mount in docker-compose, mirroring the existing `jtoye-dev` envsubst-sidecar pattern (`infra/keycloak/realm-export.template.json` → rendered `realm-export.json` → mounted read-only → `start-dev --import-realm`).
- Bake ALL Phase-1 realm config into the COMMITTED template so it survives a `--import-realm` (drop-keycloak-DB) rebuild. Do NOT rely on `configure-keycloak.sh` admin-API post-steps or manual admin-API edits for anything load-bearing (they get wiped on re-import — this exact gap broke storefront login this session).

### `jtoye-customers` realm contents (Phase 1)
- `storefront-client`: public client, standard flow + PKCE (S256), self-registration enabled.
- Redirect URIs / web origins: **injected from env, NOT hardcoded** (GLOBAL_RULE_6). Must include the port-3100 workaround (`http://localhost:3100/*` + web origin `http://localhost:3100`) plus 3000/3001. Model the env var like the existing secret-injection pattern (envsubst placeholder in the template fed from `.env`).
- `customer` default role (assigned to new self-registrations).
- Self-registration ON; `verifyEmail` configurable (default matches current `jtoye-dev` behaviour = false for local dev); `resetPasswordAllowed` ON; `loginWithEmailAllowed` + `registrationEmailAsUsername` ON (mirror current storefront UX).
- `tenant_id` handling: current storefront customers are tenant-scoped via shop slug on public endpoints, NOT via a backend-validated token — so a `tenant_id` mapper on the customer token is NOT required for Phase 1 (customer tokens are frontend-only). Do not add backend-affecting claims.

### Harden the staff realm `jtoye-dev`
- Disable realm self-registration (`registrationAllowed: false`) — staff are provisioned, not self-signup. This removes the stray "Register" link from the admin (`core-api`) login page.
- Remove `storefront-client` from `jtoye-dev` (it now lives only in `jtoye-customers`). Also drop the `customer` role + customer-registration wiring from `jtoye-dev`'s `configure-keycloak.sh` path.
- Keep `core-api` + `edge-api` clients and seed staff users (`admin-user`, `tenant-a-user`, `tenant-b-user`, password `JtoyeDev!2026`) UNCHANGED. Admin dashboard login must keep working exactly as today.

### Frontend
- Point `frontend/lib/customer-auth.ts` `KC_BASE` (and any customer issuer/realm constant) at the new customer realm. Add customer-realm env vars mirroring the existing public/internal split (`KEYCLOAK_ISSUER` public + `KEYCLOAK_ISSUER_INTERNAL`) — e.g. `CUSTOMER_KEYCLOAK_ISSUER` / `CUSTOMER_KEYCLOAK_ISSUER_INTERNAL` + `CUSTOMER_KEYCLOAK_CLIENT_ID=storefront-client`.
- Admin NextAuth (`frontend/auth.ts`, client `core-api`) STAYS on `jtoye-dev` — untouched.
- Frontend is TypeScript: any TS change requires `cd frontend && npm run build` (tsc), not just jest.

### Backend
- UNTOUCHED. A plan task should ASSERT/verify (grep) that no core-java/edge-go JWT-validation code changed.

### Claude's Discretion
- Exact env var names, template file names, docker-compose service names for the second render/import, and whether the two realms share the alpine render sidecar or get a second one.
- Whether `verifyEmail` defaults on/off for customers (pick the least-friction local-dev default; make it env-configurable).
</decisions>

<canonical_refs>
## Canonical References (downstream agents MUST read before planning/implementing)

### Keycloak realm + compose wiring
- `infra/keycloak/realm-export.template.json` — the existing jtoye-dev template to mirror for jtoye-customers (clients, roles, registration flags, envsubst `${...}` placeholders).
- `infra/keycloak/configure-keycloak.sh` — currently creates storefront-client/customer-role on jtoye-dev post-import (lines ~179-260). Phase 1 moves storefront-client into the customers-realm TEMPLATE; this script's jtoye-dev storefront branches should be removed/relocated.
- `docker-compose.full-stack.yml` — services `keycloak-realm-render` (alpine envsubst sidecar, ~L37-56) and `keycloak` (`start-dev --import-realm`, volume mount of realm-export.json, ~L59-95). A second realm needs a second render + a second import file mounted into `/opt/keycloak/data/import/`.
- `docker-compose.frontend-3100.yml` — frontend port remap to 3100 (the workaround the redirect URIs must cover).

### Frontend customer auth
- `frontend/lib/customer-auth.ts` — `CLIENT_ID`, `KC_BASE`, `REDIRECT_URI` (`${window.location.origin}/shop/auth/callback`), login/register/callback/token-exchange (the repoint target).
- `frontend/app/api/customer-auth/{login,session,logout,logout-url}/route.ts` — HttpOnly-cookie token handling; `session` decodes ID token only (proves backend isolation).
- `frontend/app/shop/auth/callback/page.tsx`, `frontend/components/storefront/require-customer-auth.tsx` (the "Sign in" button → `customerLogin`).
- `frontend/auth.ts` — admin NextAuth (core-api on jtoye-dev) — DO NOT TOUCH.

### Backend (verify-only, do not modify)
- `core-java/.../security/SecurityConfig.java`, `AudienceValidator.java` — only validate core-api tokens; assert unchanged.
</canonical_refs>

<specifics>
## Specific Ideas / Constraints

- Tech: Keycloak 24.0.5, Next.js 16, Spring Boot 3.5.16. Local stack: `docker compose -f docker-compose.full-stack.yml -f docker-compose.frontend-3100.yml`. Frontend on host :3100 (MCP holds :3000).
- Realm re-import gotcha (bit us this session): dropping the `keycloak` DB wipes everything not in the template. So Phase-1 realm config MUST be reproducible from the committed template. NEVER `docker compose down -v` (shared app+keycloak Postgres).
- Migrations: NONE (no DB schema change).
- Testing = real-medium/browser per project norms. Rebuild containers, then Playwright (node, `frontend/@playwright/test`, `NODE_PATH=frontend/node_modules`) verify:
  (a) a customer self-registers + logs in on `jtoye-customers` and lands logged-in on `/shop` (`/api/customer-auth/session` → `{authenticated:true}`);
  (b) admin dashboard login still works on `jtoye-dev` and its login page shows NO "Register" link;
  (c) separate pools — the test customer is absent from `jtoye-dev` and `admin-user` is absent from `jtoye-customers` (admin-API user count/lookup per realm).
  Read secrets from `.env`, never print them. **Base Python is blocked by a hook — use node/jq/curl, never `python3`.**
- Rollback: keep `jtoye-dev` intact until the new realm is proven; the split must not break the working admin login (`admin-user`/`JtoyeDev!2026`) or the current email/password storefront login.
- Env credential rules: values that vary by env (redirect URIs, client secrets, realm hostnames) come from `.env` / a single config layer, never hardcoded literals.
</specifics>

<deferred>
## Deferred Ideas (out of Phase-1 scope)

- **Phase 2 — Google/social login.** Configure a Google identity provider (IdP brokering) in `jtoye-customers` + a "Continue with Google" button on the storefront. Needs a user-supplied Google Cloud OAuth 2.0 client (id/secret); Keycloak broker redirect URI `http://localhost:8085/realms/jtoye-customers/broker/google/endpoint`. NOT in this phase.
- Backend multi-issuer validation — only if/when authenticated customer→backend endpoints are added (none today).
- Production realm hostnames/branding themes beyond local dev.
</deferred>

---

*Phase: 18-customer-identity-realm-split-b2c-b2b-mvp*
*Context gathered: 2026-07-09 (live investigation + user decisions)*
