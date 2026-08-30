# Security Remediation Pair
**Specialist**: AppSec Engineer (12y multi-tenant SaaS pentesting)
**Assistant**: Threat Modeller (STRIDE / defence-in-depth)
**Date**: 2026-04-27
**Total scope**: 10 findings, estimated **~78 eng-hours** (~10 focused engineering days)
**Source audit**: `docs/audit/sources/02-security-engineer.md`

---

## Cross-cutting principles

The pair agreed five operating principles before drafting individual fixes — every finding below is judged against them:

1. **Fail closed, never fail open.** Missing tenant context, missing role claim, missing verification token → 401/403, not "skip and proceed". The single most common bug in this codebase (RateLimitInterceptor `return true` on missing tenant, IDOR conditional on `verify` presence, TenantContext skipped on non-JWT path) is "if the security signal is absent, allow the request". Every remediation here flips that default.
2. **RLS is depth, not access control.** Postgres RLS protects against application-layer mistakes; it never authorises a request. Application code must always have prior, independent authorisation logic (`@PreAuthorize`, signed token, mandatory verify param). Where the audit found RLS being used *as* access control (Findings 1, 5), the fix is application-layer authorisation first, RLS last.
3. **Tokens never leave the server.** Bearer tokens, refresh tokens, Stripe secrets, Keycloak client secret — none should be retrievable from a browser context. Where they currently are (Finding 3), we BFF-proxy.
4. **Every multi-tenant table gets FORCE RLS + a CI assertion.** "We added a table and forgot RLS" is the recurring root cause across V18, V27, V28, V29. The CI test in Finding 6 exists specifically to make this PR-blocking.
5. **One migration per finding, never bundled.** Every SQL fix is its own Vnn migration so it can be rolled back surgically. We do not amend prior migrations even if we "could".

---

## Finding 1: Customer-orders IDOR (`PublicStorefrontController` + V18)

### Specialist proposal

**Files in scope**:
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java:91-104` (verified — controller has `if (verifyOrderNumber != null && !verifyOrderNumber.isBlank())` guard, i.e. opt-in verification)
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java:233-265` (verified — sets `app.customer_email` GUC then runs `findByCustomerEmailOrderByCreatedAtDesc`)
- `core-java/src/main/resources/db/migration/V18__order_history_by_email.sql:9-19` (verified — RLS policy reads `app.customer_email` GUC unconditionally)

**Phase 1 — Immediate hard fail (deploy in next push, ~30 min):**

```java
// PublicStorefrontController.java:91-104  REPLACE WITH:
@GetMapping("/orders")
@Operation(summary = "Customer order history",
        description = "List orders for a customer. Requires either a recent order number OR a signed magic-link token.")
public ResponseEntity<List<PublicOrderStatus>> getCustomerOrders(
        @RequestParam @Email @NotBlank String email,
        @RequestParam(name = "verify", required = false) String verifyOrderNumber,
        @RequestParam(name = "token",  required = false) String magicToken,
        HttpServletRequest req) {
    rateLimiter.consumeOrThrow("orders.history.email:"   + email.toLowerCase());
    rateLimiter.consumeOrThrow("orders.history.ip:"      + clientIp(req));

    boolean verified = false;
    if (verifyOrderNumber != null && !verifyOrderNumber.isBlank()) {
        // Throws ResourceNotFoundException → 404 if mismatch (existing behaviour)
        storefrontService.trackOrder(verifyOrderNumber, email);
        verified = true;
    } else if (magicToken != null && !magicToken.isBlank()) {
        verified = magicLinkService.verify(magicToken, email);
    }
    if (!verified) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Either 'verify' (a recent order number) or 'token' (magic link) is required");
    }
    return ResponseEntity.ok(storefrontService.getCustomerOrders(email));
}
```

**Phase 2 — Magic-link token (4-6h):**

New table:

```sql
-- V35__order_history_magic_links.sql
CREATE TABLE order_history_tokens (
    token_hash        BYTEA       PRIMARY KEY,            -- SHA-256 of issued opaque token
    customer_email    VARCHAR(255) NOT NULL,
    issued_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ  NOT NULL,
    consumed_at       TIMESTAMPTZ,
    issuer_ip         INET,
    CHECK (expires_at > issued_at)
);
CREATE INDEX idx_oht_email ON order_history_tokens(customer_email);
CREATE INDEX idx_oht_expires ON order_history_tokens(expires_at);
```

We store SHA-256 of the token, not the token itself, so a DB read does not yield usable tokens. Token TTL = 15 minutes, single-use (`consumed_at` set on first verify), issued via `POST /public/orders/magic-link {email}` which (a) always returns 202 regardless of whether the email exists (no enumeration oracle) and (b) only emails when `EXISTS(SELECT 1 FROM orders WHERE customer_email = ?)`.

**Rate limiter** (per-IP + per-email, IP-keyed Bucket4j fallback for `TenantContext.isEmpty()` path — see Finding 9 hardening):

```yaml
# application.yml
rate-limiting:
  public:
    orders-history:
      per-email:  { capacity: 5,  refill-tokens: 5,  refill-period: PT1H }
      per-ip:     { capacity: 30, refill-tokens: 30, refill-period: PT1H }
    magic-link:
      per-email:  { capacity: 3,  refill-tokens: 3,  refill-period: PT1H }
      per-ip:     { capacity: 10, refill-tokens: 10, refill-period: PT1H }
```

Bucket store = Redis (`LettuceBasedProxyManager`) so per-pod buckets don't fragment.

**Test (`PublicStorefrontControllerSecurityTest`):**

```java
@Test void getCustomerOrders_withoutVerifyOrToken_returns400() throws Exception {
    mvc.perform(get("/public/orders").param("email","victim@example.com"))
       .andExpect(status().isBadRequest())
       .andExpect(jsonPath("$.message").value(containsString("verify")));
}
@Test void getCustomerOrders_withWrongOrderNumber_returns404() {…}
@Test void getCustomerOrders_withValidVerify_returns200() {…}
@Test void getCustomerOrders_perEmailRateLimit_returns429AfterFifth() {…}
@Test void magicLink_brute_force_returns400_andDoesNotLeakWhetherEmailExists() {…}
```

Plus a Testcontainers integration test that runs the IDOR (`curl /public/orders?email=victim@example.com`) against the running container and asserts 400 — i.e. the regression test is at the HTTP layer, not the controller method.

**Eng-hours**: Phase 1 = 0.5h. Phase 2 magic-link = 5h. Tests = 2h. **Total: ~7.5h**.

**Rollout**: Phase 1 ships first as a single-line behavioural change → release-note "verify is now required". Phase 2 ships next sprint as opt-in additional auth path.

**Rollback**: Phase 1 — single revert commit. Phase 2 — `DROP TABLE order_history_tokens` (no FK references) and revert `MagicLinkService` bean.

### Assistant deliberation

- **CHALLENGE — Phase 1 breaks any existing storefront UI that calls the endpoint without `verify`.** Repo-grep should be done before merge. If the customer dashboard relies on the bug to render "your orders" after sign-in, this fix will produce a user-visible 400. Mitigation: greppable inventory of callers BEFORE Phase 1 deploys. If any are found, they must move to authenticated `/orders` endpoints (NextAuth session) or to the magic-link flow. The specialist should not assume "the bug has no consumers".
- **VALIDATE — token-hash-not-token storage is correct.** SHA-256 means a DB compromise yields hashes useless against a constant-time `MessageDigest.isEqual(sha256(input), stored)` check. Single-use via `consumed_at` blocks replay. 15-minute TTL is short enough to bound exposure on shoulder-surfing/email-cache attacks. **No further change needed here.**
- **CHALLENGE — the per-email rate limit can be weaponised against legitimate customers.** An attacker who knows your email can burn your 5/hour budget so the *real owner* cannot retrieve their orders. Mitigation: the per-email bucket should fail-OPEN to "show no orders, return 200" rather than 429 once exceeded — but log a metric and tag the email. Alternative: lock per-email behind successful CAPTCHA (e.g. Cloudflare Turnstile) on Nth attempt. Recommend: keep 429 in v1 because availability < confidentiality for this endpoint, but instrument an alert if any email's bucket hits zero >3x/day.
- **RISK — magic-link email path itself is an enumeration oracle if not implemented carefully.** Specialist's design says "always return 202" — must verify the email-send timing is constant (mail-send happens async via RabbitMQ, controller returns 202 immediately whether email is queued or not). If the controller awaits SMTP, response time leaks existence. Add a millis-timing assertion to the test.
- **ALTERNATIVE — make the endpoint authenticated-only.** The cleanest fix is to delete `/public/orders` entirely and require login. The specialist kept it because the existing storefront flow is "guest checkout, then track". Acceptable, but worth re-litigating once paying customer #10 has been onboarded — guest order-tracking via order-number-in-url is friction-light enough that the magic-link flow may be unnecessary product complexity.

### Reconciled position

- **Wave 0 (24h)**: Ship Phase 1 (mandatory `verify`) AFTER greppable caller inventory across `frontend/`. If any caller found, fix it in same PR.
- **Wave 1 (next sprint)**: Phase 2 magic-link with hashed-token storage, async email send, constant-time timing.
- **Rate limit**: per-email + per-IP, return 429 in v1, **add Prometheus alert `idor_email_bucket_exhausted` for monitoring weaponisation**.
- **Tests**: HTTP-layer Testcontainers regression is the load-bearing test; controller-unit tests are supporting.

---

## Finding 2: Role-based authorisation taxonomy

### Specialist proposal

**Confirmed evidence**:
- `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java:68-75` — `.anyRequest().authenticated()` is the only authorisation rule. No `JwtAuthenticationConverter` bean. JWT roles are dropped on the floor.
- `grep -rn "@PreAuthorize\|@RolesAllowed\|@Secured" core-java/src/main/java/` returns 0 hits.

**Role taxonomy** (Keycloak realm-roles, mapped into Spring authorities as `ROLE_<NAME>`):

| Role | Scope | Granted endpoints (illustrative) | Justification |
|---|---|---|---|
| `OWNER` | tenant | everything below + `/gdpr/**`, `DELETE /shops/{id}`, billing | Sole signatory on destructive tenant-wide actions |
| `MANAGER` | tenant | `POST/PUT/DELETE /products`, `POST/PUT /orders`, `POST/PUT /promotions`, all reads | Day-to-day vendor operator |
| `STAFF` | tenant | `POST /orders`, `PUT /orders/{id}/status` (subset of transitions), product reads | Counter staff / barista |
| `KITCHEN` | tenant | `GET /orders`, `PUT /orders/{id}/status` (PREPARING→READY only), KDS WebSocket | Kiosk-style kitchen display |
| `READONLY` | tenant | `GET /**` only, no writes | Accountant, auditor, investor view |

**`JwtAuthoritiesConverter`** (new file `core-java/src/main/java/uk/jtoye/core/security/JwtAuthoritiesConverter.java`):

```java
package uk.jtoye.core.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Stream;

@Component
public class JwtAuthoritiesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Set<String> KNOWN_ROLES =
            Set.of("OWNER", "MANAGER", "STAFF", "KITCHEN", "READONLY");

    private final JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new HashSet<>(scopeConverter.convert(jwt));
        // Keycloak: realm_access.roles is the realm-role list
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
            roles.stream()
                 .map(Object::toString)
                 .map(String::toUpperCase)
                 .filter(KNOWN_ROLES::contains)
                 .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                 .forEach(authorities::add);
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
```

**Wire in `SecurityConfig.java`**:

```java
.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
    jwt.jwtAuthenticationConverter(jwtAuthoritiesConverter)))

// And enable annotation processing:
@EnableMethodSecurity(prePostEnabled = true)
```

**Annotation list (every destructive / financial / GDPR endpoint):**

| Endpoint | Annotation | Justification |
|---|---|---|
| `GdprController#exportCustomer`  | `@PreAuthorize("hasRole('OWNER')")`              | GDPR DSAR; legal hold material |
| `GdprController#eraseCustomer`   | `@PreAuthorize("hasRole('OWNER')")`              | Irreversible PII destruction |
| `OrderController#deleteOrder`    | `@PreAuthorize("hasAnyRole('OWNER','MANAGER')")` | Loss of audit trail |
| `OrderController#transition`     | `@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF','KITCHEN')")` + per-state filter | Role-by-state matrix lives in service |
| `ShopController#deleteShop`      | `@PreAuthorize("hasRole('OWNER')")`              | Revenue-bearing entity |
| `ShopController#publish/unpublish` | `@PreAuthorize("hasAnyRole('OWNER','MANAGER')")` | Public-facing change |
| `ProductController#delete`       | `@PreAuthorize("hasAnyRole('OWNER','MANAGER')")` | Inventory integrity |
| `FinancialTransactionController#create/update/refund` | `@PreAuthorize("hasRole('OWNER')")` | Direct ledger write — owner-only signoff |
| `PromotionController#delete`     | `@PreAuthorize("hasAnyRole('OWNER','MANAGER')")` | Marketing-budget impact |
| `AnnouncementController#delete`  | `@PreAuthorize("hasAnyRole('OWNER','MANAGER')")` | Public messaging |
| `CustomerController#delete`      | `@PreAuthorize("hasRole('OWNER')")`              | PII deletion |
| `BulkImportController#*`         | `@PreAuthorize("hasAnyRole('OWNER','MANAGER')")` | Mass mutation |
| `DevTenantController` (entire)   | `@PreAuthorize("hasRole('OWNER') and @env.isDev()")` | Already disabled in prod, belt-and-braces |

**Keycloak realm-roles config** (`infra/keycloak/realm-jtoye-dev.json`):

```json
{
  "realm": "jtoye-dev",
  "roles": {
    "realm": [
      {"name": "OWNER",    "description": "Tenant owner — full destructive access"},
      {"name": "MANAGER",  "description": "Day-to-day operator"},
      {"name": "STAFF",    "description": "Counter staff — limited writes"},
      {"name": "KITCHEN",  "description": "Kitchen-display kiosk"},
      {"name": "READONLY", "description": "Audit/accountant/investor"}
    ]
  },
  "defaultRoles": ["READONLY"]
}
```

**Eng-hours**: Converter + wiring = 2h. Annotation pass over ~30 endpoints = 3h. Keycloak realm config + seed users = 2h. Tests (one per role × one happy + one denied per endpoint = ~60 tests) = 6h. **Total: ~13h**.

**Rollout**:
1. Deploy converter + `@EnableMethodSecurity` with NO annotations → behaviour unchanged.
2. Issue a script that grants every existing user `OWNER` (preserving current "everyone is admin" behaviour).
3. Annotate one controller per day, with feature-flag escape hatch (`spring.security.role-enforcement.enabled=false`) to disable globally if regression.
4. Once all annotated, demote users to least-privilege role.

**Rollback**: per-controller — revert the annotation commit. Globally — set `spring.security.role-enforcement.enabled=false`.

### Assistant deliberation

- **CHALLENGE — Kitchen-display kiosk is a shared device with a long-lived session.** The KITCHEN role is the most likely path to a credential-loss incident: the tablet by the fryer is unlocked all day, the JWT is in the browser. Recommend (a) very short access-token TTL (5 min) for the KITCHEN role specifically, (b) device-bound session via a per-device sub-claim, (c) IP-pinned tokens (IP-pin breaks if the kitchen is on cellular failover — discuss). The specialist's role taxonomy is right but operational hardening for KITCHEN is missing.
- **VALIDATE — `@EnableMethodSecurity` over `@EnableGlobalMethodSecurity` (deprecated) is correct for Spring Boot 3.4.** Authority converter via `JwtAuthenticationToken` is the canonical path. No issues with the wiring.
- **CHALLENGE — the OrderController state-transition rules cannot live in `@PreAuthorize` SpEL.** "KITCHEN can move PREPARING→READY but not READY→COMPLETED" is a state-machine concern, not an annotation concern. Specialist correctly flagged "+ per-state filter" — but the implementation must be in `OrderService.transition()` which checks `SecurityContextHolder.getContext().getAuthentication().getAuthorities()` against a state-event matrix. Don't try to encode this in annotations; it'll be unreadable by sprint 3.
- **RISK — the rollout step "grant every existing user OWNER" creates an irreversible window.** If the app is already in production with shared accounts that were never role-tagged, you'll write OWNER to every user's Keycloak record. Specialist should require: this only happens in non-prod first, then prod by manual operator action with audit log, NOT via an automated migration script.
- **ALTERNATIVE — consider attribute-based access control (ABAC) over RBAC for finance writes.** Pure RBAC means every OWNER can write any financial transaction; ABAC could require dual-control for transactions >£500. Out of scope for v1, but flag for v2 once a real customer has asked for "manager approval" workflows.
- **VALIDATE — `defaultRoles: ["READONLY"]` is the right principle of least privilege for new users.** Specialist nailed this.

### Reconciled position

- Ship the converter + `@EnableMethodSecurity` first with NO annotations (defence-in-depth: even unused, it removes "no role infra exists" excuse).
- Annotate destructive endpoints in a single PR per controller, behind a global kill-switch.
- KITCHEN role gets an additional config: `keycloak.role-token-ttl.KITCHEN=PT5M` (5-minute access tokens), no IP pin v1, revisit if there's a real incident.
- State-transition role logic lives in `OrderService.transition()`, NOT in annotations.
- Existing-user-promotion happens by manual audited action in prod, automated only in dev/staging.

---

## Finding 3: NextAuth accessToken leaked to browser (BFF migration)

### Specialist proposal

**Confirmed**:
- `frontend/auth.ts:87-91` writes `session.accessToken = token.accessToken as string`; `/api/auth/session` exposes this to any JS context (`useSession()` returns it).
- `frontend/lib/api-client.ts:31-34` reads `session.accessToken` client-side and attaches as `Authorization: Bearer …`.

**Step 1 — Strip from session callback (`frontend/auth.ts`):**

```typescript
async session({ session, token }) {
  // DO NOT expose tokens to the browser. They live only in the encrypted
  // NextAuth JWT cookie and are read server-side by route handlers.
  if (token.error) {
    (session as any).error = token.error
  }
  return session
}
```

**Step 2 — BFF route handler** (`frontend/app/api/proxy/[...path]/route.ts`):

```typescript
import { auth } from "@/auth"
import { NextRequest, NextResponse } from "next/server"

const CORE_API = process.env.CORE_API_URL_INTERNAL!  // server-only env var

async function handle(req: NextRequest, ctx: { params: { path: string[] } }) {
  const session = await auth()  // server-side; reads encrypted cookie
  if (!session) return NextResponse.json({ error: "unauthorized" }, { status: 401 })

  // The JWT cookie is decrypted server-side; pull accessToken from the JWT,
  // not from the session DTO returned to the browser.
  const token = (session as any).__rawToken?.accessToken
                ?? await getAccessTokenFromCookie(req)
  if (!token) return NextResponse.json({ error: "no token" }, { status: 401 })

  const path = ctx.params.path.join("/")
  const url  = `${CORE_API}/${path}${req.nextUrl.search}`

  const upstream = await fetch(url, {
    method: req.method,
    headers: {
      Authorization: `Bearer ${token}`,
      "X-Tenant-Id": (session.user as any)?.tenantId ?? "",
      "Content-Type": req.headers.get("content-type") ?? "application/json",
      "X-Forwarded-For": req.headers.get("x-forwarded-for") ?? "",
    },
    body: ["GET","HEAD"].includes(req.method) ? undefined : await req.text(),
    cache: "no-store",
  })

  // Stream back; preserve status + content-type
  const body = upstream.body
  return new NextResponse(body, {
    status:  upstream.status,
    headers: { "content-type": upstream.headers.get("content-type") ?? "application/json" },
  })
}

export { handle as GET, handle as POST, handle as PUT, handle as PATCH, handle as DELETE }
```

`getAccessTokenFromCookie(req)` reads the encrypted NextAuth JWT cookie via `getToken({req, secret})` from `next-auth/jwt`. This is server-only — the secret never leaves the Node process.

**Step 3 — `lib/api-client.ts` migration:**

```typescript
const apiClient = axios.create({
  baseURL: "/api/proxy",   // was process.env.NEXT_PUBLIC_API_URL
  headers: { "Content-Type": "application/json" },
})

// REMOVE the request interceptor that attached Authorization — the BFF does it.
```

The 401-refresh interceptor stays, but instead of calling `getSession()` it just retries (the BFF itself handles the refresh server-side via `refreshAccessToken`).

**Step 4 — SSE/WebSocket** — these cannot proxy through Next.js easily because of stream semantics. Two paths:
- **SSE** (`/orders/stream`): proxy via `app/api/proxy-sse/[...path]/route.ts` using `ReadableStream` passthrough. NextJS supports streaming responses.
- **WebSocket** (`/ws`): cannot be proxied through Next.js without a separate server. Solution: keep direct connection but switch from `?token=` query param (Finding 9 hardening item #1) to a **short-lived session ticket** issued by `/api/proxy/ws-ticket`. Client requests a ticket, server returns a one-time 30-second token, client opens `wss://api.../ws?ticket=…`. Server validates ticket and discards.

**Migration cost honest accounting**:
- ~40 files in `frontend/` import `apiClient` or call `process.env.NEXT_PUBLIC_API_URL` directly. Verified by `grep -rln "NEXT_PUBLIC_API_URL\|apiClient" frontend/`.
- BFF adds ~10ms latency per request (extra hop). Negligible for vendor dashboard, possibly visible on KDS WebSocket.
- The Node process now does the work two pods used to share. Capacity planning: bump frontend pod count by ~30%.
- Cookie-based auth means **CSRF must be re-evaluated**. Stateless JWT in Authorization header is not CSRF-able; cookie-bound proxy IS. Mitigation: enforce `SameSite=Strict` on the NextAuth cookie (already default in NextAuth v5) AND require `X-Requested-With: XMLHttpRequest` header on the BFF (custom header → blocked by CORS preflight).

**Eng-hours**: Strip session = 0.5h. BFF route = 4h. SSE proxy = 3h. WS ticket flow = 4h. Refactor 40 callers (mostly find-replace base URL) = 4h. Tests + e2e = 4h. **Total: ~20h**.

**Rollout**:
1. Day 1 — strip `accessToken` from session() callback (single line). Frontend will break — rate-limit to staging only.
2. Day 1+ — implement BFF route, swap `apiClient` baseURL.
3. Day 2 — implement SSE proxy.
4. Day 3 — implement WS ticket; remove `?token=` query path on server.

**Rollback**: revert in commit-order. `accessToken` re-exposure is 1-line revert.

### Assistant deliberation

- **CHALLENGE — the BFF doesn't actually fix XSS, it just changes the prize.** Once cookies are the credential, an XSS payload can `fetch('/api/proxy/orders').then(r => r.json())` from the same origin and exfiltrate every endpoint. The win is "attacker cannot keep the credential and use it from elsewhere", but in-page data exfiltration is unchanged. Specialist must pair this with **CSP + sanitisation of all user-content render paths** (Finding 9 hardening item #2). Otherwise this is security theatre.
- **VALIDATE — `SameSite=Strict` + custom-header CSRF defence is the standard BFF stance.** Both layers needed: SameSite alone breaks if a sub-domain is compromised; custom header alone breaks if CORS allows the request. Together they cover 95% of CSRF.
- **CHALLENGE — WS ticket flow is correct but the implementation needs tighter binding.** If the ticket is just `random_uuid()` stored in Redis with 30s TTL, an MITM (against the wss:// itself if cert pinning fails) can steal and replay within 30s. Tighten by binding ticket to `Sec-WebSocket-Key` or to client IP. Recommend: bind to client IP for v1 (will break behind aggressive corporate NAT but acceptable for vendor admin), revisit if support tickets surface.
- **RISK — capacity bump assumption is optimistic.** "30% more pods" assumes the BFF is mostly proxy with no JSON parsing. In reality, NextJS streams need to wrap-unwrap for header rewriting; on a busy dashboard this could double Node CPU. Recommend: load-test the BFF before any prod cutover, instrument `/api/proxy/*` p95 in Grafana, set alert on >150ms BFF overhead.
- **ALTERNATIVE — instead of full BFF, just shorten access-token TTL to 60 seconds and refresh aggressively.** This is the OAuth2 BCP recommendation when SPAs must hold tokens. Reduces XSS exfil window from "until token expires" to "≤60s". Cheaper than BFF (no infra change). The pair's read: BFF is the right end-state because `refresh_token` exposure is also fixed; but if engineering bandwidth is constrained, short-TTL access tokens are an acceptable Wave-1 mitigation while BFF is a Wave-2 build.

### Reconciled position

- **Wave 1 (24h)**: Strip `accessToken` and `refreshToken` from session() callback. Drop access-token TTL in Keycloak to **2 minutes** as compensating control. Audit and sanitise all user-content render paths (`<div dangerouslySetInnerHTML>` grep across `frontend/`).
- **Wave 2 (5 days)**: Build BFF for HTTP. Add CSP header (Finding 9). Add custom-header CSRF check.
- **Wave 3 (3 days)**: BFF for SSE. WS ticket flow with IP-binding. Remove `?token=` from `JwtHandshakeInterceptor`.
- Do NOT touch BFF until all user-content sanitisation has shipped — fixing token exposure before fixing the XSS sink is the wrong order.

---

## Finding 4: Edge-go JWT hardening (with absorb cross-reference)

### Specialist proposal

**Confirmed evidence**: `edge-go/internal/middleware/jwt.go:99-132, 199-227` — `m.publicKeys = newKeys` (line 227) is a write with no mutex; `publicKey, ok := m.publicKeys[kid]` (line 119) is a concurrent read; signing-method check is `*jwt.SigningMethodRSA` (line 101) which accepts RS256/RS384/RS512 — no `aud` check anywhere.

**Cross-reference to Finding 7 (edge-go absorb verdict)**: The audit's edge-go agent recommends "delete and absorb into Spring". If that path is taken, this finding's value is "what carries to the absorbed Spring side" (see end of section). If the absorb is deferred or rejected, the patch below is required.

**Patch (`edge-go/internal/middleware/jwt.go`):**

```go
package middleware

import (
    "crypto/rsa"
    // … existing imports …
    "sync"
    "golang.org/x/sync/singleflight"
)

type JWTMiddleware struct {
    jwksURL         string
    issuer          string
    audience        string                 // NEW
    logger          *zap.Logger
    keysMu          sync.RWMutex           // NEW
    publicKeys      map[string]*rsa.PublicKey
    lastRefresh     time.Time
    refreshInterval time.Duration
    refreshGroup    singleflight.Group     // NEW — coalesces concurrent refreshes
}

func NewJWTMiddleware(jwksURL, issuer, audience string, logger *zap.Logger) *JWTMiddleware {
    // … existing refreshInterval parsing …
    return &JWTMiddleware{
        jwksURL: jwksURL, issuer: issuer, audience: audience, logger: logger,
        publicKeys: make(map[string]*rsa.PublicKey),
        refreshInterval: refreshInterval,
    }
}

func (m *JWTMiddleware) Validate() gin.HandlerFunc {
    return func(c *gin.Context) {
        authHeader := c.GetHeader("Authorization")
        if authHeader == "" {
            c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing authorization header"})
            return
        }
        tokenString := strings.TrimPrefix(authHeader, "Bearer ")
        if tokenString == authHeader {
            c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid authorization header format"})
            return
        }

        token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
            kid, ok := token.Header["kid"].(string)
            if !ok {
                return nil, errors.New("missing kid in token header")
            }
            return m.lookupKey(kid)
        },
            jwt.WithLeeway(30*time.Second),
            jwt.WithValidMethods([]string{"RS256"}),                  // NEW — pinned alg
            jwt.WithIssuer(m.issuer),                                  // NEW — built-in iss check
            jwt.WithAudience(m.audience),                              // NEW — aud check
        )
        if err != nil || !token.Valid {
            m.logger.Warn("JWT validation failed", zap.Error(err))
            c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
            return
        }

        claims, ok := token.Claims.(jwt.MapClaims)
        if !ok {
            c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token claims"})
            return
        }
        // Issuer + audience already validated by jwt.Parse options.

        var tenantID string
        for _, key := range []string{"tenant_id", "tenantId", "tid"} {
            if val, ok := claims[key].(string); ok && val != "" {
                tenantID = val
                break
            }
        }
        c.Set("jwt_claims", claims)
        c.Set("tenant_id", tenantID)
        c.Set("user_id", claims["sub"])
        c.Next()
    }
}

func (m *JWTMiddleware) lookupKey(kid string) (*rsa.PublicKey, error) {
    m.keysMu.RLock()
    publicKey, ok := m.publicKeys[kid]
    needsRefresh := time.Since(m.lastRefresh) > m.refreshInterval
    m.keysMu.RUnlock()

    if ok && !needsRefresh {
        return publicKey, nil
    }

    // Singleflight: concurrent misses coalesce onto a single refresh.
    _, err, _ := m.refreshGroup.Do("refresh", func() (interface{}, error) {
        return nil, m.refreshKeys()
    })
    if err != nil {
        return nil, fmt.Errorf("failed to refresh keys: %w", err)
    }

    m.keysMu.RLock()
    publicKey, ok = m.publicKeys[kid]
    m.keysMu.RUnlock()
    if !ok {
        return nil, fmt.Errorf("public key not found for kid: %s", kid)
    }
    return publicKey, nil
}

func (m *JWTMiddleware) refreshKeys() error {
    // … existing fetch + decode logic that builds newKeys map …

    m.keysMu.Lock()
    m.publicKeys = newKeys
    m.lastRefresh = time.Now()
    m.keysMu.Unlock()

    m.logger.Info("Refreshed JWKS", zap.Int("key_count", len(newKeys)))
    return nil
}
```

**Test additions** (in `jwt_test.go`):

```go
// Run with: go test -race ./internal/middleware/...
func TestJWTMiddleware_ConcurrentRefresh_NoRace(t *testing.T) { … }
func TestJWTMiddleware_WrongAudience_Rejected(t *testing.T)   { … }
func TestJWTMiddleware_HS256Token_Rejected(t *testing.T)      { … } // alg-confusion attack
func TestJWTMiddleware_ExpiredToken_Rejected(t *testing.T)    { … }
```

**`main.go` update**: pass `KC_AUDIENCE=core-api` to `NewJWTMiddleware`.

**Eng-hours**: Patch = 2h. Tests = 2h. **Total: ~4h**.

**Cross-reference to absorb (Finding 7)**:

If edge-go is absorbed into Spring (recommended by audit agent #7), the patch above is throwaway BUT the lessons must transfer:
1. Spring's `NimbusJwtDecoder` already validates `aud` if configured: `JwtDecoders.fromIssuerLocation(issuer)` + `OAuth2TokenValidator` chain with `JwtValidators.createDefaultWithIssuer(issuer)` + a custom `JwtClaimValidator<List<String>>("aud", aud -> aud.contains("core-api"))`. **Verify this is currently configured.** Looking at `SecurityConfig.java:34-50`, the `NimbusJwtDecoder.withJwkSetUri(...).build()` does NOT set audience validation — same bug as edge-go, just in Spring.
2. Spring's JWKS singleflight is built-in (`NimbusJwtDecoder` internally caches and refreshes); no manual mutex needed.
3. The "WhatsApp webhook handler" (~150 LOC) that earns edge-go's keep needs to move to a Spring `WhatsAppController` with the same HMAC verification logic. Transfer the test assertions wholesale.

### Assistant deliberation

- **VALIDATE — `singleflight.Group` is the right pattern.** Without it, JWKS refresh storms (every concurrent request seeing `needsRefresh=true` triggers a fetch) hammer Keycloak. Standard issue.
- **CHALLENGE — `jwt.WithValidMethods([]string{"RS256"})` is correct, but the alg-confusion attack vector specifically requires checking that the *header alg* and the *key type* match.** The library's `WithValidMethods` rejects HS256 tokens but a malicious `none` header would still need separate handling. Recommend: also explicitly reject `alg: none` even though `WithValidMethods` should already block it. Belt and braces.
- **CHALLENGE — applying this patch may waste the absorb work.** If the team is deciding to delete edge-go this quarter, a 4h patch is sunk cost. The pair recommends deciding the absorb question FIRST. If absorb is "yes within 30 days", apply only the absolute minimum (`sync.RWMutex`) for now, and apply the rest to Spring's `SecurityConfig` instead.
- **RISK — Spring's `NimbusJwtDecoder` audience check needs to be added even if edge-go is patched.** The audit found `aud` missing in edge-go but the same gap exists in Spring's decoder configuration. Don't fix one and forget the other.
- **ALTERNATIVE — use Keycloak's `ClientPolicy` to require audience-specific tokens.** Keycloak can be configured to issue tokens with `aud: ["core-api"]` only when the client requests it. Fixes the issue at issue-time rather than verify-time. Useful but doesn't replace verify-time check (defence in depth).

### Reconciled position

- **Decide absorb question first.** If absorb is committed for this quarter:
  - Apply MINIMUM patch to edge-go: just `sync.RWMutex` + `WithValidMethods([]string{"RS256"})`. Skip the `singleflight` and `WithAudience` because the code is going away.
  - Apply FULL audience + alg-pin to Spring `SecurityConfig` JwtDecoder bean as part of the absorb.
- If absorb is deferred:
  - Apply full edge-go patch above.
  - Also fix Spring `SecurityConfig` audience validation (separate finding to file).
- Reject `alg: none` explicitly (defence in depth).
- Configure Keycloak client to issue `aud: ["core-api"]` going forward.

---

## Finding 5: Reviews RLS bypass (V35 fix migration)

### Specialist proposal

**Confirmed evidence**: `db/migration/V27__customer_reviews.sql:31-36` (verified) — uses `current_setting('app.tenant_id', true)::UUID` (the WRONG GUC; the aspect sets `app.current_tenant_id`) AND has an OR-clause on `customer_email` that any storefront caller can satisfy.

**Migration `V35__fix_reviews_rls_policy.sql`:**

```sql
-- V35: Fix reviews_tenant_write policy.
--
-- Problems with the V27 policy:
--   1. Uses GUC name `app.tenant_id` but TenantSetLocalAspect sets
--      `app.current_tenant_id`. The tenant_id branch always evaluates
--      false and is dead code.
--   2. The OR-clause on `app.customer_email` accepts any storefront
--      request (every public order endpoint sets that GUC), allowing
--      reviews to be written for ANY shop with arbitrary tenant_id.
--   3. No ownership proof — caller can claim any order_id.
--
-- This fix:
--   * Uses the correct GUC name.
--   * Removes the customer_email-as-auth OR branch.
--   * Requires EXISTS proof: the cited order_id must belong to the same
--     shop AND have the same customer_email currently set in the GUC.

DROP POLICY IF EXISTS reviews_tenant_write ON reviews;

CREATE POLICY reviews_tenant_write ON reviews
    FOR INSERT
    WITH CHECK (
        -- Tenant-side write (vendor moderating their own reviews)
        tenant_id::text = current_setting('app.current_tenant_id', true)
        OR
        -- Customer-side write (verified order ownership)
        (
            current_setting('app.customer_email', true) IS NOT NULL
            AND current_setting('app.customer_email', true) <> ''
            AND customer_email = current_setting('app.customer_email', true)
            AND EXISTS (
                SELECT 1 FROM orders o
                WHERE o.id = reviews.order_id
                  AND o.shop_id = reviews.shop_id
                  AND o.tenant_id = reviews.tenant_id
                  AND o.customer_email = current_setting('app.customer_email', true)
                  AND o.status IN ('COMPLETED','READY')  -- only completed orders can be reviewed
            )
        )
    );

-- Belt: also re-issue UPDATE/DELETE policies (today they are missing →
-- default-deny is fine for storefront, but vendor-moderation needs UPDATE).
DROP POLICY IF EXISTS reviews_tenant_update ON reviews;
CREATE POLICY reviews_tenant_update ON reviews
    FOR UPDATE
    USING      (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));

DROP POLICY IF EXISTS reviews_tenant_delete ON reviews;
CREATE POLICY reviews_tenant_delete ON reviews
    FOR DELETE
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
```

**Test (`ReviewRlsIntegrationTest`, Testcontainers):**

```java
@Test void reviewWrite_withoutOwnedOrder_fails() {
    setSession("app.customer_email", "attacker@example.com");
    assertThrows(DataAccessException.class, () ->
        jdbc.update("INSERT INTO reviews (tenant_id,shop_id,order_id,customer_email,food_rating)"
                   + " VALUES (?,?,?,?,5)", randomTenant, victimShop, victimOrder, "attacker@example.com"));
}
@Test void reviewWrite_withOwnedOrder_succeeds() { … }
@Test void reviewWrite_byVendor_succeeds() {
    setSession("app.current_tenant_id", vendorTenant.toString());
    assertDoesNotThrow(() -> jdbc.update(…));
}
@Test void reviewWrite_crossTenant_failsEvenWithCustomerEmail() { … }
```

**Eng-hours**: Migration = 1h. Tests = 2h. **Total: ~3h**.

**Rollout**: Standard Flyway forward migration. No data-migration component (only policy change).

**Rollback**: `V36__rollback_reviews_rls.sql` re-creating the V27 policy. (Note: rollback to a known-broken policy is itself a security regression — Flyway forward-only is the right discipline here. Document the rollback path but do not encourage its use.)

### Assistant deliberation

- **VALIDATE — the `EXISTS (SELECT 1 FROM orders WHERE …)` pattern is correct ownership proof.** This is the standard "have you been a customer" check. The order status filter (`COMPLETED`, `READY`) prevents writing reviews for orders still in DRAFT/PENDING — pragmatic.
- **CHALLENGE — what if the customer changes their email after ordering?** The order has the email at order-time. If the customer updates their email in their account, the EXISTS check will fail. Is this acceptable? Pair's read: yes — email is the only ownership token we have on the public storefront, and changing email should invalidate review-write privilege. Document this in the migration comment.
- **CHALLENGE — the policy still allows tenant write without order verification.** A vendor employee could write a 5-star review under a fake customer_email. This is "vendor self-review" risk. Specialist's policy permits it via the first OR branch. Mitigation: log every vendor-side review with `created_by` user-id (separate column in V36) and surface in admin audit log. Outside scope of this finding but flag for follow-up.
- **RISK — `o.status IN ('COMPLETED','READY')` hardcodes status names.** If the OrderStatus enum is renamed in code, this policy silently rejects all customer reviews. Mitigation: add an integration test that asserts the migration's status constants match the current `OrderStatus` enum. Catches drift in CI.
- **VALIDATE — UPDATE/DELETE policies added for vendor moderation.** Specialist correctly thought beyond INSERT.

### Reconciled position

- Ship V35 as written.
- Add a CI test `OrderStatusEnumDriftTest` that ensures any value listed in V35 still exists in `OrderStatus`.
- Add `created_by_user_id UUID` to `reviews` in a follow-up V36 migration so vendor-side reviews are auditable (separate finding, not blocking).
- Document in migration comment that email change invalidates review-write privilege (intentional).

---

## Finding 6: FORCE ROW LEVEL SECURITY pass + CI assertion

### Specialist proposal

**Verified gap** (from `grep -n "FORCE ROW LEVEL SECURITY" db/migration/`):

| Table | Has FORCE? | Migration |
|---|---|---|
| `shops`, `products`, `financial_transactions` | YES | V2 |
| `customers` | YES | V9, V14 |
| `orders`, `order_items` | YES | V15 |
| `payment_event_outbox` | YES | V33 |
| `shops_aud`, `products_aud`, `financial_transactions_aud`, `orders_aud`, `order_items_aud`, `customers_aud` | **NO** | V4/V11 (RLS enabled but not FORCED) |
| `reviews` | **NO** | V27 |
| `shop_promotions` | **NO** | V28/V29 |
| `shop_announcements` | **NO** | V29 |

**Migration `V36__force_rls_remaining_tables.sql`:**

```sql
-- V36: Add FORCE ROW LEVEL SECURITY to all tenant-scoped tables that
-- were missed. FORCE means even the table owner is subject to RLS;
-- without it, a privileged role (or migration role) silently bypasses.

ALTER TABLE reviews              FORCE ROW LEVEL SECURITY;
ALTER TABLE shop_promotions      FORCE ROW LEVEL SECURITY;
ALTER TABLE shop_announcements   FORCE ROW LEVEL SECURITY;

-- Audit (Envers) tables — privileged role bypass is the audit-evasion
-- vector here. FORCE blocks any future bug from reading another tenant's
-- audit history.
ALTER TABLE shops_aud                  FORCE ROW LEVEL SECURITY;
ALTER TABLE products_aud               FORCE ROW LEVEL SECURITY;
ALTER TABLE financial_transactions_aud FORCE ROW LEVEL SECURITY;
ALTER TABLE orders_aud                 FORCE ROW LEVEL SECURITY;
ALTER TABLE order_items_aud            FORCE ROW LEVEL SECURITY;
ALTER TABLE customers_aud              FORCE ROW LEVEL SECURITY;
```

**CI test** (`MultiTenantIsolationIntegrationTest`, additive):

```java
@Test
void everyTenantScopedTableHasForceRls() throws Exception {
    List<String> required = List.of(
        "shops","products","financial_transactions","customers","orders","order_items",
        "payment_event_outbox","reviews","shop_promotions","shop_announcements",
        "shops_aud","products_aud","financial_transactions_aud","orders_aud",
        "order_items_aud","customers_aud"
    );
    Set<String> forced = jdbc.query(
        "SELECT relname FROM pg_class WHERE relkind='r' AND relforcerowsecurity = true",
        (rs, i) -> rs.getString("relname")
    ).stream().collect(Collectors.toSet());

    List<String> missing = required.stream().filter(t -> !forced.contains(t)).toList();
    assertThat(missing)
        .as("Tables missing FORCE ROW LEVEL SECURITY (potential cross-tenant leak vector)")
        .isEmpty();
}
```

**Eng-hours**: Migration = 0.5h. Test = 1h. **Total: ~1.5h**.

**Rollout**: Forward Flyway migration. Verify migration role is NOT the runtime app role (otherwise `FORCE` will lock out application access — but check shows runtime is `jtoye_app`, migration is `jtoye`).

**Rollback**: `V37__rollback_force_rls.sql` with `NO FORCE ROW LEVEL SECURITY` per table. Same caveat as Finding 5 — this is a security regression.

### Assistant deliberation

- **VALIDATE — the CI test is the load-bearing artefact.** The migration is one-time; the test prevents the next Vnn migration from forgetting FORCE. This is the "process gap, not code gap" exact remediation called out in the audit's cross-cutting theme A.
- **CHALLENGE — the test list is hand-maintained.** A future tenant-scoped table added without updating this test silently bypasses. Mitigation: invert the test — query `pg_tables` for any table with a `tenant_id` column and assert FORCE. Then it's self-updating.

```java
// Better:
List<String> tenantTables = jdbc.queryForList(
    "SELECT table_name FROM information_schema.columns "
    + "WHERE column_name='tenant_id' AND table_schema='public'", String.class);
// then assert all in `forced`
```

- **CHALLENGE — `customers_aud` already enables RLS in V9 line 67 but does it have policies?** If RLS is enabled with no policies AND FORCE is added, the table becomes inaccessible (default-deny). Specialist must verify each `_aud` table has at least one policy before adding FORCE. Quick check: V11 added `_insert_policy` for shops/products/financial_transactions; orders_aud, order_items_aud, customers_aud need verification. **This is a blocker on the migration as written.**
- **RISK — runtime user must NOT be table owner.** Verify `\d+ reviews` shows owner is migration user, not `jtoye_app`. If the dev environment runs Flyway as `jtoye_app` (some shops do this for simplicity), FORCE will brick the app. Add a startup assertion: `SELECT current_user` should never equal table owner.
- **ALTERNATIVE — also add `FORCE` to `tenants` table and lock app-user out of it entirely.** Currently `tenants` has no RLS (intentional per V2 comment). Specialist's audit notes this as Medium #17. The cleanest fix is: revoke `SELECT ON tenants` from `jtoye_app`; only the migration role / scheduled-job-role can read it. Out of strict scope here but pair them.

### Reconciled position

- Ship V36 with the FIX from the assistant: add the inverted `information_schema` query to the CI test so it's self-updating.
- Before V36 deploys, audit each `_aud` table for at least one policy. If `orders_aud`/`order_items_aud`/`customers_aud` lack policies, V36 must include them.
- Verify migration user ≠ runtime user in CI (one-line check).
- File a separate finding to revoke `SELECT ON tenants` from runtime user (Medium follow-up).

---

## Finding 7: Edge-go global rate limiter (or absorb)

### Specialist proposal

**Verified evidence**: `edge-go/cmd/edge/main.go:71-102` is one process-wide token bucket (`make(chan struct{}, burst)`); `main.go:149-153` uses it as `r.Use(rateLimiter(...))` — single bucket for all tenants and all paths. Spring's correct per-tenant pattern lives in `core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java:60-99` (Bucket4j keyed by `tenantId`).

**Two paths — pair recommends Path B if absorb is committed:**

**Path A — patch edge-go (~6h):**

```go
// edge-go/internal/middleware/ratelimit.go (NEW FILE)
package middleware

import (
    "net/http"
    "sync"
    "time"
    "github.com/gin-gonic/gin"
    "golang.org/x/time/rate"
)

type TenantRateLimiter struct {
    mu       sync.RWMutex
    buckets  map[string]*entry
    rps      rate.Limit
    burst    int
    ttl      time.Duration
}

type entry struct {
    limiter  *rate.Limiter
    lastSeen time.Time
}

func NewTenantRateLimiter(rps, burst int, ttl time.Duration) *TenantRateLimiter {
    rl := &TenantRateLimiter{
        buckets: make(map[string]*entry),
        rps: rate.Limit(rps), burst: burst, ttl: ttl,
    }
    go rl.gcLoop()
    return rl
}

func (rl *TenantRateLimiter) Limit() gin.HandlerFunc {
    return func(c *gin.Context) {
        // Key precedence: tenant_id (set by JWT middleware) → IP
        key, _ := c.Get("tenant_id")
        keyStr, _ := key.(string)
        if keyStr == "" {
            keyStr = "ip:" + c.ClientIP()
        }
        if !rl.allow(keyStr) {
            c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{"error": "rate limit exceeded"})
            return
        }
        c.Next()
    }
}

func (rl *TenantRateLimiter) allow(key string) bool {
    rl.mu.RLock()
    e, ok := rl.buckets[key]
    rl.mu.RUnlock()
    if !ok {
        rl.mu.Lock()
        e, ok = rl.buckets[key]
        if !ok {
            e = &entry{limiter: rate.NewLimiter(rl.rps, rl.burst), lastSeen: time.Now()}
            rl.buckets[key] = e
        }
        rl.mu.Unlock()
    }
    e.lastSeen = time.Now()
    return e.limiter.Allow()
}

func (rl *TenantRateLimiter) gcLoop() {
    t := time.NewTicker(5 * time.Minute)
    for range t.C {
        cutoff := time.Now().Add(-rl.ttl)
        rl.mu.Lock()
        for k, e := range rl.buckets {
            if e.lastSeen.Before(cutoff) {
                delete(rl.buckets, k)
            }
        }
        rl.mu.Unlock()
    }
}
```

Wire in `main.go`:

```go
// BEFORE the protected group, AFTER JWT middleware (so tenant_id is set):
limiter := middleware.NewTenantRateLimiter(rps, burst, 30*time.Minute)
protected := r.Group("/", jwtMiddleware.Validate(), limiter.Limit())
```

For unauthenticated paths (`/whatsapp` webhook), key by source IP only — different limiter instance with stricter limits.

**Caveat**: this is per-pod. At horizontal scale, two pods with one tenant each see independent buckets. Acceptable for early-stage; document for later.

**Path B — handover to Spring's Bucket4j+Redis (~0h edge-go work, recommended):**

If absorb is committed, the existing Spring `RateLimitInterceptor` is already per-tenant via Redis. The only edge-go-unique surface (WhatsApp webhook) moves to a `WhatsAppController` in Spring and inherits the existing rate limit with `@RateLimit(...)` (or by registering it on the existing interceptor). **Net work: 0 in edge-go, ~3h in Spring to add IP-keyed buckets for `/public/**` and `/whatsapp` paths** (also fixes the audit's hardening item — `RateLimitInterceptor.preHandle` returns `true` when `TenantContext.isEmpty()`).

**Eng-hours**: Path A = 6h. Path B = 3h (Spring side). Path A also creates eng-hour debt because absorb-later means doing it twice.

**Rollout**: Path B is cleaner — no edge-go change, single Spring change with feature flag.

### Assistant deliberation

- **VALIDATE — `TenantContext.isEmpty()` returning `true` (allow) is the correct flag for the existing audit hardening item.** Path B fixes both findings simultaneously.
- **CHALLENGE — per-pod buckets in Path A defeat the purpose at >2 pods.** The audit's edge-go agent already flagged this in `.planning/codebase/CONCERNS.md:195`. Don't ship Path A as a "good enough" — document it as "interim, only if absorb deferred >60 days".
- **CHALLENGE — Path B's Spring-side IP-bucket implementation needs care.** `request.getRemoteAddr()` behind a Kubernetes ingress is the ingress IP, not the client. Must read `X-Forwarded-For` carefully (validate trust chain) or use Spring's `ForwardedHeaderFilter`. Otherwise all clients share one bucket.
- **RISK — IP-keyed buckets enable noisy-neighbour DoS within a NAT.** Office of 50 employees behind a single egress IP shares the bucket. Mitigation: combine IP + user-agent fingerprint + cookie ID for `/public/**` paths. Or accept the limitation at this scale — file as known-issue.
- **ALTERNATIVE — use Cloudflare or similar at the edge for rate limiting.** Removes the entire concern from app code. Not free but cheap; recommend for any future productionisation.

### Reconciled position

- **Path B preferred.** Decide absorb question in same week as Finding 4 reconciliation.
- If absorb deferred: Path A as interim with a documented "interim only" marker and a 60-day expiry.
- Spring side IP-bucket implementation must use `ForwardedHeaderFilter` and explicitly trust the ingress source range.
- File "rate limiting at edge (Cloudflare)" as a future-proofing item once paying customers exist.

---

## Finding 8: Secrets posture (rotation runbook)

### Specialist proposal

**Verified `.env` (committed values are dev defaults — file IS gitignored, but the values in the working tree are weak):**

| Secret | Current value | Cadence | Mechanism |
|---|---|---|---|
| `POSTGRES_PASSWORD` | `secret` | 90 days (prod), pre-staging-deploy | kubeseal → `postgres` secret → rolling restart of `core-java`, `keycloak` |
| `DB_PASSWORD` (`jtoye_app`) | `secret` | 90 days | `ALTER USER` then kubeseal → `core-java` rolling restart |
| `REDIS_PASSWORD` | `<rotated-2026-08-29-see-.env>` | 90 days | kubeseal → `core-java` (Lettuce reconnects) |
| `RABBITMQ_DEFAULT_PASS` | `<rotated-2026-08-29-see-.env>` | 90 days | RabbitMQ user reset → `core-java` reconnects |
| `NEXTAUTH_SECRET` | base64 32-byte (OK strength) | 180 days | kubeseal → frontend rolling restart **(invalidates all sessions)** |
| `KEYCLOAK_CLIENT_SECRET` | `core-api-secret-2026` | 90 days, immediately on staff exit | Keycloak admin → kubeseal → frontend + core-java rolling |
| `STRIPE_API_KEY` | (not in `.env`, prod-only) | 30 days, immediately on suspected leak | Stripe dashboard rotate → kubeseal → core-java |
| `STRIPE_WEBHOOK_SECRET` | (prod-only) | 90 days | Stripe webhook endpoint rotate → kubeseal → core-java |
| `MINIO_ROOT_PASSWORD` | (per-env, not in `.env` shared) | 90 days | MinIO admin → kubeseal → core-java |
| `KEYCLOAK_ADMIN_PASSWORD` | `admin123` | 90 days, MFA required in prod | Keycloak admin reset → kubeseal |
| `GRAFANA_ADMIN_PASSWORD` | `admin` | 90 days | Grafana admin → kubeseal |

**Rotation runbook (per-secret skeleton)**:

1. **Generate**: `openssl rand -base64 32` (or service-specific generator).
2. **Provision**: write to source-of-truth (Postgres `ALTER USER`, Keycloak admin, Stripe dashboard).
3. **Seal**: `kubectl create secret generic <name> --from-literal=<key>=<value> --dry-run=client -o yaml | kubeseal -o yaml > sealed/<name>.yaml`.
4. **Apply**: `kubectl apply -f sealed/<name>.yaml`.
5. **Restart sequence** (per secret — listed in table above).
6. **Smoke**: hit `/actuator/health` (when fixed per Finding 9 hardening) + run `make smoke-prod` Playwright suite.
7. **Audit**: append entry to `docs/security/secret-rotation-log.md` with date, operator, secret name, smoke result.

**Staging vs prod policy for `.env` weak passwords**:
- **Dev** (`docker-compose up`): the current `.env` defaults are acceptable. They are localhost-only and the network is not exposed. **DO NOT** change them — they're the documented dev experience.
- **Staging**: rotate to non-default values BEFORE first deploy. `.env.staging` is generated at deploy time from sealed secrets, never committed.
- **Prod**: same as staging + cadence applies. `STRIPE_API_KEY` is verified present in K8s Secret (audit caveat #12 from COUNCIL doc — confirm before first prod deploy).

**Eng-hours**: Runbook authoring + sealed-secret bootstrap = 4h. First rotation drill = 2h. **Total: ~6h** (one-time; rotations are ~30min each thereafter).

**Rollout**: Runbook to `docs/security/secret-rotation.md`, link from `HANDOFF.md`. First drill on staging within 2 weeks of doc publication.

**Rollback**: each rotation must have a "previous value" stored in offline 1Password vault for 24h. After 24h the previous value is destroyed.

### Assistant deliberation

- **VALIDATE — keeping dev `.env` defaults is correct.** Changing dev passwords just adds friction; the threat model is "host machine compromised", at which point the password is the least of your worries.
- **CHALLENGE — `NEXTAUTH_SECRET` rotation invalidates all sessions.** This is a user-visible logout event. Specialist correctly flagged it but didn't say how often. Pair recommends: 180-day cadence, NOT during business hours, with a comms email 7 days prior.
- **CHALLENGE — Stripe API key rotation has a Stripe-side gotcha.** Stripe supports two active keys for transition. Use that — don't single-cut. Specialist's runbook doesn't document the Stripe-side overlap. Add to procedure.
- **RISK — `KEYCLOAK_CLIENT_SECRET` rotation requires both frontend AND core-java to be rolled in lockstep.** If frontend gets the new secret but core-java doesn't, every login fails. Mitigation: rolling-restart sequence MUST be core-java first (it must accept either old or new during transition — Keycloak supports `client.secret.previous` for a short window), then frontend, then expire old after 1h.
- **ALTERNATIVE — adopt HashiCorp Vault for dynamic secrets.** Long-term win for prod; out of scope for v1. Note in roadmap.
- **CHALLENGE — `git log -p -- .env` retroactive check is in the audit but not in the runbook.** If a prior commit in main history contains a real prod secret, rotation alone doesn't help — must also rewrite history (`git filter-repo`) and force-push. Add this as a Day-0 verification step.

### Reconciled position

- Adopt the runbook as written, with three additions:
  1. Stripe key rotation uses Stripe's two-active-keys window, not single-cut.
  2. Keycloak client secret uses Keycloak's previous-secret window with explicit core-first → frontend rolling order.
  3. Day-0: `git log -p --all -- .env` audit; if prod secrets ever appeared, run `git filter-repo` and rotate immediately.
- `NEXTAUTH_SECRET` rotation: 180 days, off-hours, 7-day prior comms.
- File HashiCorp Vault adoption as a v2 hardening item.

---

## Finding 9: Hardening list (medium / low)

### Specialist proposal

For each item from the audit, fix-now or defer-with-acceptance:

| Item | File:line | Action | Now / Defer | Eng-hours |
|---|---|---|---|---|
| WebSocket JWT in URL query | `JwtHandshakeInterceptor.java:24-37` | Replace `?token=` with one-time ticket (Finding 3 Wave 3); reject handshake when no ticket present (currently always returns true) | NOW — paired with Finding 3 | 3h (already in Finding 3) |
| Missing CSP header | `SecurityConfig.java:81-93` | Add `Content-Security-Policy: default-src 'self'; script-src 'self' 'nonce-{{nonce}}'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https://*.minio.local; connect-src 'self' https://api.jtoye.uk https://*.stripe.com; frame-ancestors 'none'; base-uri 'self'; form-action 'self'` — start in `Content-Security-Policy-Report-Only` for 2 weeks then enforce | NOW | 4h (incl. report-only monitor) |
| Image upload Content-Type spoof | `StorageService.java:88,113` | Use `detectedType` (returned from magic-byte check) for S3 `contentType`, not `file.getContentType()` | NOW | 1h |
| `getExtension` honours user filename | `StorageService.java:245-249` | Map `detectedType → fixed extension` (e.g. `image/jpeg → .jpg`); ignore client filename | NOW | 1h |
| Scheduled jobs raw `SELECT id FROM tenants` | `PaymentEventOutboxFlusher.java:73-75`, `ScheduledCleanupService.java:53-55` | Move to dedicated `tenant_scheduler` Postgres role with `SELECT ON tenants` only; revoke from `jtoye_app`; or use a tenant-cache (TenantRegistry bean) refreshed on tenant create/delete events | DEFER 30d (no current attack path; restructure with tenant-event refactor) | 4h |
| No Stripe webhook payload size cap | `PaymentController.java:30-43` | Add `@RequestBody @Size(max=65536) String payload` + Spring filter `RequestSizeLimitFilter` for `/public/payments/webhook` capped at 256KB | NOW | 1h |
| RateLimitInterceptor allows public/* | `RateLimitInterceptor.java:74-77` | Fix as part of Finding 7 Path B (Spring IP-bucket fallback) | NOW (folded into Finding 7) | (in #7) |
| `error.include-message: always` | `application.yml:97-99` | Set to `never` in `application.yml` (base); explicit `always` only in `application-dev.yml` | NOW | 0.5h |
| `JwtHandshakeInterceptor` always-true handshake | `JwtHandshakeInterceptor.java:34-35` | Reject when `attributes.get("jwt_token") == null` (return false) | NOW | 0.5h |
| CORS `addAllowedHeader("*")` | `CorsConfig.java` | Allowlist `Authorization, Content-Type, X-Tenant-Id, X-Requested-With` | NOW | 0.5h |
| `setAllowedOriginPatterns` foot-gun | `CorsConfig.java` | CI grep test that fails build if `setAllowedOriginPatterns("*")` ever appears | NOW | 0.5h |
| Pool reset hook for `app.customer_email` GUC | HikariCP config + `set_config(.., true)` audit | Hibernate `physicalConnectionReleased` callback to `RESET ALL`; OR audit every `set_config` call site to use `true` (transaction-local) — currently mostly true but audit confirms | NOW | 2h |
| Async `TaskDecorator` for tenant context | `AsyncConfig` (new bean) | `TaskDecorator` that captures `TenantContext.get()` on submission, sets in worker thread, clears on completion | DEFER 30d (no `@Async` DB usage today; flag as required-before-next-async-feature) | 2h |
| Swagger UI exposed in non-prod | `SecurityConfig.java:70` | Restrict by IP allowlist or remove `permitAll`; `application-prod.yml` already disables `springdoc.api-docs` but static UI assets still resolve | DEFER (low risk; no PII; convenience > risk for dev/staging) | 1h |

**Total**: NOW items = ~14h. DEFER items = ~7h (later).

**Rollout**: Single PR per item ("hardening: <item>"), small commits, each independently revertable.

### Assistant deliberation

- **VALIDATE — CSP report-only first is correct.** Going straight to enforce will break inline scripts that NextJS or third-party libs inject. 2-week report window with `report-uri` to a local endpoint catches them.
- **CHALLENGE — `frame-ancestors 'none'` will break the Stripe Checkout iframe** (or any iframe embed of the dashboard). Verify Stripe's payment flow uses redirect, not embed, before enforcing. If Stripe Elements is in use, `frame-ancestors 'self' https://*.stripe.com`.
- **CHALLENGE — image extension lock-down may break legitimate `.jpeg` vs `.jpg` user expectation.** Specialist mapped `image/jpeg → .jpg` — fine, but document that `.jpeg` uploads will be stored as `.jpg`. Test for any frontend code that asserts on the original extension.
- **RISK — deferring scheduled-job tenant access for 30d is acceptable IF the same fix lands as part of the absorb (Finding 7).** If absorb is rejected, this finding's defer rationale weakens. Re-evaluate in 30 days.
- **CHALLENGE — `error.include-message: never` may break frontend error display.** Some Axios error toasts read `response.data.message`. Verify frontend behaviour after the change. Recommendation: keep `always` in dev, `never` in prod (use `application-prod.yml` override). Update specialist's table: instead of base = never, prod = never override.
- **VALIDATE — payload size cap on Stripe webhook.** Trivial fix, real DoS surface. No further changes.

### Reconciled position

- Apply NOW items as listed, with three corrections:
  - CSP `frame-ancestors`: include Stripe domains.
  - Image extension: log a warning when caller's extension is normalised away, so frontend bug reports are diagnosable.
  - `error.include-message`: leave base as `always`, override `prod` to `never`.
- Defer items get GitHub issues with 30-day SLA.
- All NOW items can be PRed in parallel; no inter-dependencies.

---

## Finding 10: OWASP Top 10 closure plan

### Specialist proposal

| OWASP | Current | Target | Specific actions (referenced findings) | Order | Hours |
|---|---|---|---|---|---|
| A01 Broken Access Control | FAIL | PASS | Findings 1 (IDOR), 2 (RBAC), 5 (reviews policy), 6 (FORCE RLS) — plus existing SSE fix from backend remediation | Wave 0–1 | 25h (covered) |
| A02 Cryptographic Failures | PARTIAL | PASS | Finding 3 (BFF + token strip), Finding 8 (rotation), Finding 9 (CSP header — defence) | Wave 0–2 | 26h (covered) |
| A03 Injection | PASS | PASS | Maintain — add CI grep test for `String.format` with SQL or `+` concatenation in repository code | Wave 2 | 1h |
| A04 Insecure Design | FAIL | PASS | Findings 1, 2, 5 + backend remediation Stripe idempotency. Add architectural decision record (ADR-008) prohibiting "RLS as access control" | Wave 0–1 | 1h ADR |
| A05 Security Misconfiguration | PARTIAL | PASS | Finding 9 (CSP, CORS allowlist, error message, Swagger guard, payload caps), Finding 8 (rotation), backup the audit Action 11 (backup hygiene) | Wave 1 | 8h (covered) |
| A06 Vulnerable & Outdated Components | NOT AUDITED | PASS | Wire `gradle dependencyCheckAnalyze` (OWASP Dependency-Check plugin) to CI, fail on CVSS ≥ 7.0; `npm audit --audit-level=high` to CI; weekly Renovate bot | Wave 1 | 4h |
| A07 IdentAuth Failures | PARTIAL | PASS | Finding 4 (edge JWT — aud, alg, race), Finding 9 (WS handshake reject, ticket flow), Finding 8 (KC client rotation) | Wave 1 | 7h (covered) |
| A08 Software & Data Integrity | PARTIAL | PASS | Backend remediation Stripe idempotency (out of this doc's scope), plus Finding 9 webhook payload cap | Wave 0 | covered elsewhere |
| A09 Logging & Monitoring | PASS-ish | PASS | DevOps remediation (separate doc) — `MDC.put("tenantId", …)`, fix `tenant_context_missing_total` counter, Alertmanager rules for `idor_email_bucket_exhausted` (added in Finding 1) | Wave 1 | (DevOps doc) |
| A10 SSRF | PASS | PASS | Maintain — add CI grep test for `URL.openConnection` / `RestTemplate` with user input in path | Wave 2 | 1h |

**Eng-hours covered above**: ~78h (Findings 1–9). **Net new**: ~7h (A03, A04, A06, A10 incremental).

### Assistant deliberation

- **VALIDATE — A06 is genuinely "NOT AUDITED" today and needs CI integration urgently.** A single CVE in a Spring Boot transitive dep (e.g. CVE-2024-22243 SSRF in 3.4.x) could be live and unknown.
- **CHALLENGE — A04 "Insecure Design" doesn't move to PASS just by fixing IDOR + RBAC.** It moves when the architectural process changes. The ADR is necessary but not sufficient — also need: tenant-isolation regression test required on every new-table PR (cross-cutting theme A from COUNCIL doc), threat-modelling step in `/gsd-secure-phase` per CARL workflow.
- **CHALLENGE — A09 PASS-ish today is over-generous.** The audit notes `tenant_context_missing_total` counter does not exist. Until that fires, you have no visibility into the very class of bug Finding 6 protects against. Down-grade to PARTIAL until DevOps remediation lands.
- **RISK — moving any of these from FAIL to PASS without independent verification is overconfidence.** Pair recommends: after each wave, retain an external pentester to re-test the specific OWASP categories changed. Without external eyes, "PASS" is self-declared.
- **ALTERNATIVE — adopt OWASP ASVS L2 as the explicit target standard.** Currently Top 10 is the bar; ASVS gives per-control checklists. More mature posture; defer to v2.

### Reconciled position

- Adopt the table as the closure plan with assistant corrections (A04 needs process change + ADR; A09 stays PARTIAL until counter exists).
- After Wave 1 completion: retain external pentest for A01, A02, A07 specifically.
- Add `gradle dependencyCheckAnalyze` to CI in same week as Finding 9 hardening PRs.
- Consider ASVS L2 adoption in v2 (post-customer-10).

---

## Dependency graph

```
Wave 0 (24h — pre-prod blockers, ship within a day):
  F1.Phase1 (mandatory verify) ─────────────────┐
  F5 (V35 reviews policy) ──────────────────────┤
  F6 (V36 FORCE RLS + CI test) ─────────────────┤
  F8 (Day-0 git history audit; .env audit)──────┤
  F9.NOW.subset (error.include-message,         │
       JwtHandshake reject, image extension, ───┤   ← independent NOW items
       payload size cap, CORS allowlist)        │
                                                 │
                                                 ▼
                                       (deploy Wave 0 to staging)
                                                 │
Wave 1 (5–7 days — high-priority hardening):    │
  F2 (RBAC converter + annotations) ────────────┤  ← no deps, can start day 1
  F3.Wave1 (strip tokens + KC TTL + XSS audit) ─┤  ← gates F3.Wave2
  F4 (edge JWT or absorb decision) ─────────────┼─→ depends on absorb decision
  F7 (rate limit per-tenant) ───────────────────┼─→ depends on absorb decision
  F8 (rotation runbook + first drill) ──────────┤
  F9.NOW.full (CSP report-only, image type     │
       fix, async TaskDecorator if not deferred)┤
  F10.A06 (dependency-check in CI) ─────────────┘

Wave 2 (3–5 days — deeper migration):
  F1.Phase2 (magic-link service) ───────────────┐
  F3.Wave2 (BFF for HTTP) ──────────────────────┤  ← depends on F3.Wave1 + CSP enforce
  F3.Wave3 (BFF for SSE + WS ticket) ───────────┤  ← depends on F3.Wave2
  CSP enforce (after 2-week report-only soak) ──┤
  F10.A03 / A10 (CI grep tests) ────────────────┘

Day-2 (post-customer-10):
  F9.DEFER (scheduled-job tenant access, async TaskDecorator if deferred)
  External pentest (A01, A02, A07)
  ASVS L2 adoption
  HashiCorp Vault for secrets
```

---

## Wave breakdown (pre-prod blockers / high / day-2)

**Pre-prod blockers (Wave 0 — must ship before any prod hosting >1 tenant)**:
- F1 Phase 1 (IDOR mandatory verify)
- F5 (reviews V35)
- F6 (FORCE RLS V36 + CI test)
- F8 Day-0 git audit
- F9 NOW subset (the cheap hardening wins)

**High (Wave 1 — must ship within sprint)**:
- F2 (RBAC)
- F3 Wave 1 (strip tokens, short TTL, XSS audit)
- F4 (edge JWT; absorb decision drives scope)
- F7 (per-tenant rate limit; absorb decision drives scope)
- F8 (rotation runbook live + first drill)
- F9 NOW remainder
- F10 A06 (dependency-check)

**Day-2 / Wave 2+**:
- F1 Phase 2 (magic-link)
- F3 Wave 2/3 (BFF + WS ticket)
- F9 DEFER items
- External pentest
- ASVS L2 / Vault

---

## Open questions for human decision

1. **Absorb edge-go yes/no?** Drives F4 + F7 scope. Pair recommends: yes, within 30 days. Decision needed BEFORE Wave 1 starts to avoid wasted patch effort.
2. **Guest order-tracking via `/public/orders` or authenticated-only?** F1 Phase 2 magic-link is correct if guest tracking stays. If it moves to authenticated-only (sign-in required to see history), Phase 2 becomes obsolete — saving ~5h. Product call.
3. **KITCHEN role IP-pinning?** Tighter security but breaks cellular failover. Pair leaves to operator preference.
4. **CSP `frame-ancestors`: Stripe-only or no embeds at all?** Depends on whether Stripe Elements is used (need to verify in `frontend/`). If yes, allow Stripe; if no (redirect-only flow), `frame-ancestors 'none'`.
5. **Force RLS on `tenants` table?** Currently no RLS by design. Specialist + assistant agree app-runtime should not have `SELECT ON tenants`. Confirm and revoke.
6. **External pentest budget?** Without external verification, "PASS" claims on OWASP table are self-graded. Allocate £3–5k post-Wave-1 for a 2-day engagement focused on tenant isolation.
7. **NextAuth session invalidation comms.** Who owns the customer email when `NEXTAUTH_SECRET` rotates? Founder, or eventual support function?

---

**End of remediation pair output.** All findings have a reconciled position. All eng-hours summed: ~78h direct + ~7h OWASP incremental + ~7h DEFER follow-up = ~92h total. The pair's combined recommendation is to ship Wave 0 within 48h, freeze feature work, complete Wave 1 within 7 days, and revisit Wave 2 alongside the customer-development phase recommended by the Council audit.
