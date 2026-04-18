# Phase 12: Spring Security Response Headers + Frontend CSP — Research

**Researched:** 2026-04-18
**Domain:** Browser-security response headers (Spring Security 6 + Next.js 16)
**Confidence:** HIGH for Spring Security 6 DSL and Next.js 16 CSP patterns; MEDIUM for exact NextAuth/Stripe minimum CSP allowlist (requires empirical Playwright verification)

---

## 1. Executive Summary

This phase is deceptively small on the surface ("add six headers") but hides three hard problems the planner must address up front:

1. **Next.js 16's `middleware.ts` was renamed to `proxy.ts` in October 2025 and is now deprecated.** [CITED: nextjs.org/blog/next-16] The project today has `frontend/middleware.ts:1` that re-exports NextAuth's `auth` middleware — adding CSP there means either renaming to `proxy.ts` (a separate migration concern) or accepting a deprecation warning. Whichever option, this phase's CSP work forces a migration decision.
2. **CSP nonce vs. static CSP is a rendering-mode decision, not just a header-value decision.** [CITED: nextjs.org/docs/app/guides/content-security-policy] Nonce-based CSP (the Next.js-recommended pattern) requires **all pages to be dynamically rendered** — it disables ISR, static optimization, CDN caching, and is incompatible with Partial Prerendering. The storefront `/shop/[slug]` pages are likely cached — picking nonce CSP means recosting those routes. A static CSP with `'unsafe-inline'` keeps rendering untouched but measurably weakens XSS defence. This is the single most impactful decision in the phase and deserves explicit CONTEXT.md acknowledgement.
3. **Stripe's CSP requirements (confirmed against docs.stripe.com/security/guide) add five distinct origins across four directives** [CITED: docs.stripe.com/security/guide] — the phase cannot ship a strict `default-src 'self'` without breaking checkout. The allowlist must be carved into `script-src`, `frame-src`, `connect-src`, `img-src` precisely.

**Primary recommendation:** Ship Phase 12 in two plans. Plan 1 = Spring Security headers (low risk, high confidence — mostly mechanical). Plan 2 = Next.js CSP with a `Content-Security-Policy-Report-Only` header first, enforce-mode after a 48-hour Playwright + staging observation window. Use `next.config.mjs` static CSP (not nonce-based) to avoid the dynamic-rendering tax on the storefront; revisit nonce CSP in a later milestone if ASVS L2+ compliance is pursued.

---

## 2. Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SEC-02 | CSP headers on Next.js frontend responses via `next.config.mjs` or middleware. Minimum directives: `default-src 'self'`, `script-src 'self' 'nonce-<random>'` (or `'unsafe-inline'` only if NextAuth requires it — verify), `style-src 'self' 'unsafe-inline'` (Tailwind), `img-src 'self' data: https:`, `connect-src 'self' <api-origin> <ws-origin>`, `frame-ancestors 'none'`. | §4 Next.js 16 CSP strategy, §5 NextAuth + Stripe allowlist, §7 rollout strategy |
| SEC-03 | Security response headers on Spring Boot responses via `HttpSecurity.headers()` or a `WebMvcConfigurer` filter: `X-Frame-Options: DENY`, `Strict-Transport-Security: max-age=31536000; includeSubDomains` (prod profile only), `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`. HSTS absent in `dev`, present in `prod`. | §3 Spring Security 6 DSL, §6 testing strategy |

---

## 3. Current State Audit (file:line evidence)

### Spring Boot side

**`core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java:50–77`** — single `SecurityFilterChain @Bean`. **Zero `headers(...)` configuration today.** Spring Security 6 applies defaults (X-Content-Type-Options, X-Frame-Options DENY, Cache-Control, some HSTS) when `.headers()` is omitted [CITED: docs.spring.io/spring-security/reference/servlet/exploits/headers.html], but the project has no explicit control, no profile-based HSTS, and no Referrer-Policy. Defaults produce `X-Frame-Options: DENY` and `X-Content-Type-Options: nosniff` out of the box, but **HSTS defaults enable for HTTPS requests** — which leaks from dev to staging/prod without profile gating. [ASSUMED] The project likely inherits defaults today; verify with `curl -I http://localhost:9090/api/v1/shops` once the dev stack is up (add to Plan 1 Wave 0).

No existing `@Profile("prod")` security beans. `@Profile` appears in 4 configs (`RateLimitConfig`, `OpenApiConfig`, `CacheConfig`, `DevTenantController`) so the pattern is known in the codebase.

### Next.js side

**`frontend/next.config.mjs:1–22`** — `headers()` async function **does not exist**. Only `output: 'standalone'` and `images.remotePatterns`. Green field for headers.

**`frontend/middleware.ts:1–5`** — exists; currently a 4-line re-export of NextAuth's `auth` middleware scoped to `/dashboard/:path*`. This file will be touched by this phase's CSP work unless CSP goes in `next.config.mjs` alone.

**`frontend/auth.ts:43–99`** — NextAuth 5.0.0-beta.30 with Keycloak provider. `basePath: "/api/auth"` and `trustHost: true`. The signin flow redirects to `${kcPublicBase}/protocol/openid-connect/auth` (full external Keycloak URL). **Keycloak origin must be in `connect-src` and `form-action`** for the OAuth redirect flow.

**`frontend/app/shop/[slug]/checkout/page.tsx:32–34, 430–451`** — Stripe Elements (`@stripe/react-stripe-js` 6.1.0 + `@stripe/stripe-js` 9.0.1) loaded via `loadStripe(process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY)`. Uses `Elements` wrapper + `PaymentElement` inside iframe. **`js.stripe.com`, `hooks.stripe.com`, `api.stripe.com` required** across `script-src`/`frame-src`/`connect-src` per §5.

**`frontend/hooks/use-stomp.ts:9`** + `frontend/hooks/__tests__/use-stomp.test.ts:75` — WebSocket broker at `ws://core.local:9090/ws` (dev) → `wss://...` (prod). **`ws:` and `wss:` schemes must be allowed in `connect-src`** or the KDS WebSocket (Phase 11) breaks.

**No existing CSP reference anywhere in `frontend/` except Keycloak realm export** (`infra/keycloak/realm-export.json:1409,1414`) which sets `referrerPolicy` and `strictTransportSecurity` on Keycloak's own responses, not ours.

### Test infrastructure

- **MockMvc in use across 14 test files** (grep for `header().string` confirmed only 1 pre-existing file uses response-header assertions, in the disabled `RateLimitIntegrationTest.java.disabled`). `ShopControllerIntegrationTest.java:22–24` imports `MockMvcResultMatchers.*` → `header().string(...)` is available on `andExpect(...)`. New header-assertion tests will follow the existing `@SpringBootTest @AutoConfigureMockMvc @Testcontainers` pattern from `ShopControllerIntegrationTest.java:26–49`.
- **Playwright at `frontend/playwright.config.ts`** — `baseURL: "http://localhost:3000"` (note: CLAUDE.md memory says dev runs on port 3100; `feedback_port3100.md` flagged the mismatch — Plan needs to parameterize or flag this). Three existing e2e specs in `frontend/e2e/`. Response-header assertions go via `page.waitForResponse()` + `response.headers()`.
- **CI**: `.github/workflows/ci-cd.yaml:17–86` runs `./gradlew :core-java:test` + `npm run build` + `go test`. **No Playwright job in CI today** — header-regression tests must either (a) add a Playwright job or (b) use a lighter node-based fetch test. Plan should pick one.

### Summary: this is a green-field header phase with zero existing `headers()` configuration on either side. No refactor hazards. One cross-milestone migration flag (`middleware.ts` → `proxy.ts`).

---

## 4. Spring Security 6 Headers DSL (for Spring Boot 3.4.2)

**Verified against docs.spring.io/spring-security/reference/servlet/exploits/headers.html** [CITED]. Spring Boot 3.4.2 pulls Spring Security 6.4.x; the lambda DSL below is the canonical 6.x form.

### 4.1 Exact syntax to add to `SecurityConfig.securityFilterChain(...)` — between the existing `.authorizeHttpRequests(...)` block (line 62) and `.oauth2ResourceServer(...)` block (line 69):

```java
// Source: docs.spring.io/spring-security/reference/servlet/exploits/headers.html
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

.headers(headers -> headers
    .frameOptions(frame -> frame.deny())
    .contentTypeOptions(Customizer.withDefaults())         // enabled by default; explicit for clarity
    .referrerPolicy(referrer -> referrer
        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
    // HSTS handled per-profile — see 4.2
)
```

**Key points:**
- `Customizer.withDefaults()` is already imported in `SecurityConfig.java:8`. No new imports needed except `ReferrerPolicyHeaderWriter`.
- The lambda DSL is Spring Security 6 canonical; older `.and()`-chained 5.x syntax still compiles but will not match the rest of the file.
- `contentTypeOptions` is ON by default — the explicit block is for audit readability.
- `.frameOptions(frame -> frame.deny())` overrides the default SAMEORIGIN to DENY as REQUIREMENTS.md SEC-03 specifies.

### 4.2 Profile-based HSTS — two patterns, pick one

**Pattern A (recommended): Conditional inside the single SecurityFilterChain**

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                JwtTenantFilter jwtTenantFilter,
                                                TenantFilter tenantFilter,
                                                Environment env) throws Exception {
    boolean isProd = Arrays.asList(env.getActiveProfiles()).contains("prod");

    http.headers(headers -> {
        headers.frameOptions(f -> f.deny())
               .contentTypeOptions(Customizer.withDefaults())
               .referrerPolicy(r -> r.policy(
                   ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
        if (isProd) {
            headers.httpStrictTransportSecurity(hsts -> hsts
                .includeSubDomains(true)
                .maxAgeInSeconds(31_536_000L));
        } else {
            headers.httpStrictTransportSecurity(hsts -> hsts.disable());
        }
    });
    // ... rest of chain
}
```

**Pattern B (alternative): two `@Bean`s with `@Profile`**

Split `SecurityConfig` into `BaseSecurityConfig` (everything except HSTS) + `ProdSecurityHeaders @Profile("prod")` that post-processes the chain. More ceremony, less common.

**Recommendation: Pattern A.** The existing SecurityConfig is a single bean; keeping it that way minimizes diff surface and matches the codebase style. Pattern B is only worth it if HSTS config grows substantial (multiple max-age policies, preload-list toggling per-env, etc.) — not the case here.

**Default-HSTS trap to verify:** Spring Security 6 emits HSTS by default **only when the request appears to be HTTPS** (per `HstsHeaderWriter`'s default `secureRequestMatcher`). [CITED: docs.spring.io/spring-security/reference/servlet/exploits/headers.html] In local dev over `http://`, no HSTS is sent by default — so the project *might* already meet SEC-03's "absent in dev" criterion passively. But once we add `.headers(...)` explicitly, we take control and must be explicit — don't rely on default matcher, disable in non-prod explicitly (as Pattern A does).

### 4.3 Non-Spring-Security hook option (don't use, but know it exists)

A `Filter` implementing `doFilterInternal` that sets headers on `HttpServletResponse` is mechanically possible (`WebMvcConfigurer` won't do it — that's for resolvers/converters; you'd need `OncePerRequestFilter`). Spring Security's `HeaderWriterFilter` already occupies this role in the chain — duplicating it outside is an anti-pattern. **Stick with `HttpSecurity.headers(...)`.**

---

## 5. Next.js 16 CSP Strategy — Where and How

### 5.1 Two configuration surfaces, different tradeoffs

| Surface | File | Pros | Cons |
|---------|------|------|------|
| Static CSP | `next.config.mjs` `headers()` | No per-request cost; compatible with ISR/PPR/SSG; simplest to reason about; no runtime bugs | No nonce → must allow `'unsafe-inline'` in `script-src` or `'unsafe-eval'` in dev; XSS defence measurably weaker |
| Nonce CSP | `proxy.ts` (formerly `middleware.ts`) | Strict CSP possible (`'strict-dynamic' 'nonce-xxx'`); defense-in-depth best-in-class | **Forces dynamic rendering of every matched page** — disables ISR, static optimization, CDN edge caching, incompatible with Partial Prerendering [CITED: nextjs.org/docs/app/guides/content-security-policy]; breaks storefront caching plan |

**Recommendation:** **Static CSP via `next.config.mjs`** for this phase. The J'Toye storefront (`/shop/[slug]`) benefits heavily from static rendering; trading that for nonce-strictness is not worth the cost at ASVS L1. Revisit when the project targets L2+ or stricter compliance. Document this as a logged decision.

### 5.2 Next.js 16 `middleware.ts` → `proxy.ts` — migration pressure

Quoted release notes [CITED: nextjs.org/blog/next-16]:
> "The `middleware.ts` file is still available for Edge runtime use cases, but it is **deprecated** and will be **removed in a future version**."

**What this means for Phase 12:**
- The project currently has `frontend/middleware.ts` (4 lines, NextAuth `auth` re-export). This emits a deprecation warning on every `next build` under Next.js 16.
- **If we choose nonce CSP**, we'd put the CSP logic in `proxy.ts` (or keep it in `middleware.ts` with the warning). Either way this phase becomes entangled with the deprecation.
- **If we choose static CSP (§5.1 recommendation)**, we avoid touching `middleware.ts`/`proxy.ts` entirely. The file stays as-is; the deprecation is a separate milestone concern.

**Planner action:** add a one-line note to the plan: "CSP via `next.config.mjs` only; do not modify `middleware.ts` in this phase. `middleware.ts` → `proxy.ts` migration is tracked separately (deferred)."

### 5.3 Recommended CSP value (verified hostnames — see §6 for derivation)

```js
// Source: constructed from:
//  - docs.stripe.com/security/guide [CITED]
//  - nextjs.org/docs/app/guides/content-security-policy [CITED]
//  - auth.ts:50 (Keycloak authorize URL — NEXT_PUBLIC_KEYCLOAK_URL)
//  - hooks/use-stomp.ts:9 (ws:// broker — NEXT_PUBLIC_API_URL)

const isDev = process.env.NODE_ENV === 'development'

// NOTE: For dev, 'unsafe-eval' is required for React DevTools error overlays.
// Production runs without 'unsafe-eval'.
const cspDirectives = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline'${isDev ? " 'unsafe-eval'" : ''} https://js.stripe.com https://*.js.stripe.com`,
  "style-src 'self' 'unsafe-inline'",                                    // Tailwind injects inline styles
  "img-src 'self' data: blob: https://*.stripe.com https: http://localhost:9000",  // MinIO dev + S3 prod
  "font-src 'self' data:",
  "connect-src 'self' https://api.stripe.com https://*.stripe.com " +
    (process.env.NEXT_PUBLIC_API_URL || '') + ' ' +
    (process.env.NEXT_PUBLIC_API_URL || '').replace(/^http/, 'ws') + ' ' +
    (process.env.NEXT_PUBLIC_KEYCLOAK_URL || ''),
  "frame-src https://js.stripe.com https://*.js.stripe.com https://hooks.stripe.com",
  "frame-ancestors 'none'",
  "form-action 'self' " + (process.env.NEXT_PUBLIC_KEYCLOAK_URL || ''),  // NextAuth redirects to Keycloak
  "base-uri 'self'",
  "object-src 'none'",
  "upgrade-insecure-requests",
].join('; ')
```

**Three important deviations from REQUIREMENTS.md suggestions:**

1. **`'unsafe-inline'` in `script-src`** — REQUIREMENTS.md hedged ("only if NextAuth requires it — verify"). [ASSUMED] Next.js 16 without a nonce injects inline hydration scripts (`__next_f.push([...])`) that will be blocked without `'unsafe-inline'`. The Next.js team explicitly documents this as a known limitation of the static CSP path [CITED: nextjs.org/docs/app/guides/content-security-policy — "Without Nonces" section]. **Empirical verification required in Plan 2 Wave 1** via Playwright console-error assertion.

2. **`frame-src` added** — SEC-02's REQUIREMENTS.md list omitted `frame-src` entirely. Stripe 3DS **requires** `frame-src https://hooks.stripe.com` [CITED: docs.stripe.com/security/guide]. Without it, 3D Secure authentication breaks silently. Must be included.

3. **`form-action` added** — NextAuth's sign-in flow submits a form to Keycloak's `/protocol/openid-connect/auth`. `form-action 'self'` alone would block that submission. Must include Keycloak origin.

### 5.4 Where to write it in `next.config.mjs`

```js
// Source: nextjs.org/docs/app/api-reference/config/next-config-js/headers [CITED]
const cspDirectives = /* see 5.3 */

const nextConfig = {
  output: 'standalone',
  images: { /* existing */ },
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'Content-Security-Policy', value: cspDirectives },
          // Optionally mirror X-Frame-Options here for older browsers,
          // though frame-ancestors supersedes it. See §5.5.
        ],
      },
    ]
  },
}
```

### 5.5 X-Frame-Options on Next.js?

`frame-ancestors 'none'` in CSP is stricter and supersedes `X-Frame-Options: DENY` for modern browsers [CITED: nextjs.org/docs/app/api-reference/config/next-config-js/headers — "X-Frame-Options" section notes "**This header has been superseded by CSP's `frame-ancestors` option**"]. For Next.js responses we do **not** need `X-Frame-Options` if CSP has `frame-ancestors 'none'`. SEC-03 requires `X-Frame-Options` **on Spring responses** — that stands; it applies to the API, not the frontend.

---

## 6. NextAuth + Stripe CSP Requirements (exact hostnames)

### 6.1 Stripe — verified against docs.stripe.com/security/guide [CITED]

| Directive | Required hostnames | Why |
|-----------|-------------------|-----|
| `script-src` | `https://js.stripe.com`, `https://*.js.stripe.com` | Stripe.js loader + sharded assets |
| `frame-src` | `https://js.stripe.com`, `https://*.js.stripe.com`, `https://hooks.stripe.com` | Elements iframe + 3DS authentication redirect iframe |
| `connect-src` | `https://api.stripe.com`, `https://*.stripe.com` (covers `q.stripe.com` telemetry and `m.stripe.com` fraud signals) | Payment Intent confirmation, telemetry |
| `img-src` | `https://*.stripe.com` | Card brand icons, Stripe-hosted images |

**Known gotcha** [CITED: github.com/stripe/stripe-js/issues/127]: without `https://q.stripe.com` in `connect-src`, Stripe.js emits console warnings but payments still work. Wildcard `https://*.stripe.com` covers it.

**Not needed** for this project (Stripe Elements, not Stripe Checkout): `https://checkout.stripe.com`. Checkout.stripe.com is used by Stripe Checkout (the hosted page redirect) — the J'Toye checkout in `checkout/page.tsx` uses `Elements` inline, not Checkout redirect.

### 6.2 NextAuth 5.0.0-beta.30 with Keycloak provider

NextAuth itself runs **server-side** in App Router (`app/api/auth/[...nextauth]/route.ts`) so it does not inject new client-side script sources. The one CSP-relevant behavior:

- **Sign-in triggers a redirect** from `/auth/signin` to `${NEXT_PUBLIC_KEYCLOAK_URL}/protocol/openid-connect/auth`. This is a browser redirect, not a fetch — so it needs `form-action` (if submitted via form) or is a plain navigation (no CSP cost beyond `default-src` for the destination page, which is on a different origin).
- **Token refresh** (`auth.ts:10–19`) runs server-side only (inside the `jwt` callback), no CSP impact on browser.
- **Session cookie** is a standard `Set-Cookie` — no CSP interaction.

**Conclusion:** NextAuth adds `form-action <keycloak-origin>` and `connect-src <keycloak-origin>` (for potential client-side token refresh, which this project doesn't do but is defensive). No `script-src` additions.

[ASSUMED → verify in Plan 2 Wave 2] The Keycloak login page itself is outside CSP scope (served by Keycloak, not Next.js). Confirm by running Playwright signin flow and asserting zero console CSP violations during the round-trip.

### 6.3 Internal J'Toye hostnames

From `frontend/lib/public-api-client.ts:4`, `frontend/hooks/use-stomp.ts:9`:
- `NEXT_PUBLIC_API_URL` (dev: `http://localhost:9090`, prod: `https://api.jtoye.co.uk` or similar) → `connect-src`
- WebSocket: same origin, `ws://` / `wss://` scheme → **must be added explicitly** because CSP distinguishes `http:` from `ws:` (the `connect-src` directive matches the scheme).
- MinIO dev: `http://localhost:9000` → `img-src`

---

## 7. Testing Strategy

### 7.1 Spring — MockMvc header assertions

**Pattern (adapt from `ShopControllerIntegrationTest.java:70–80`):**

```java
// Source: pre-existing pattern, extended with header() matcher
// import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@Test
@WithMockUser
void shopsEndpointHasSecurityHeaders() throws Exception {
    mockMvc.perform(get("/api/v1/shops"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
}

@Test
@ActiveProfiles("prod")
void hstsPresentInProdProfile() throws Exception {
    mockMvc.perform(get("/api/v1/shops").secure(true))  // simulate HTTPS
        .andExpect(header().string("Strict-Transport-Security",
            org.hamcrest.Matchers.containsString("max-age=31536000")));
}

@Test
// default test profile
void hstsAbsentInDevProfile() throws Exception {
    mockMvc.perform(get("/api/v1/shops"))
        .andExpect(header().doesNotExist("Strict-Transport-Security"));
}
```

**HSTS profile test trap:** Spring's `MockMvc` with `.secure(true)` simulates an HTTPS request. The default `HstsHeaderWriter` requires `isSecure()` to be true to emit the header. Without `.secure(true)`, even prod-profile tests will see no HSTS header and pass incorrectly. Plan must document this.

**Place tests:** new file `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java` — mirrors existing `MultiTenantIsolationIntegrationTest.java` / `TenantFilterTest.java` pattern in `security/`.

### 7.2 Next.js — Playwright header assertion

**Pattern:**

```typescript
// Source: playwright.dev/docs/api/class-response#response-headers
import { test, expect } from '@playwright/test'

test('homepage has CSP header', async ({ page }) => {
  const response = await page.goto('/')
  expect(response).not.toBeNull()
  const csp = response!.headers()['content-security-policy']
  expect(csp).toBeDefined()
  expect(csp).toContain("default-src 'self'")
  expect(csp).toContain("frame-ancestors 'none'")
  expect(csp).toContain('https://js.stripe.com')
})

test('no CSP violations during storefront browse', async ({ page }) => {
  const violations: string[] = []
  page.on('console', (msg) => {
    if (msg.type() === 'error' && msg.text().includes('Content Security Policy')) {
      violations.push(msg.text())
    }
  })
  await page.goto('/shop/test-shop')
  await page.click('button:has-text("Add to cart")')
  await page.goto('/shop/test-shop/checkout')
  // Stripe Elements needs a tick to mount
  await page.waitForTimeout(2000)
  expect(violations).toEqual([])
})
```

**Place tests:** new file `frontend/e2e/security-headers.spec.ts` — alongside existing `storefront-flows.spec.ts`.

### 7.3 Header snapshot test (success criterion 5)

**Recommendation: a single JSON snapshot + golden-file comparison**. Commit to `core-java/src/test/resources/security-headers.golden.json` and `frontend/__snapshots__/security-headers.snapshot.json`. Any diff fails CI.

```java
// Spring side — snapshot assertion
@Test
void headerSnapshotMatchesGolden() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/v1/shops")).andReturn();
    Map<String, String> actual = Map.of(
        "X-Frame-Options", result.getResponse().getHeader("X-Frame-Options"),
        "X-Content-Type-Options", result.getResponse().getHeader("X-Content-Type-Options"),
        "Referrer-Policy", result.getResponse().getHeader("Referrer-Policy")
        // HSTS omitted in dev profile
    );
    String expected = Files.readString(
        Path.of("src/test/resources/security-headers.golden.json"));
    assertThat(new ObjectMapper().writeValueAsString(actual))
        .isEqualToIgnoringWhitespace(expected);
}
```

**CI integration (ci-cd.yaml:17–86):** tests run inside `./gradlew :core-java:test` + `npm test`. The Playwright tests need a running Next.js dev server — currently **not in CI** (the frontend job only runs `npm run build`, not Playwright). Plan must choose between:
- **Option A:** add a CI step that runs `npm run build && npm run start &` then `npx playwright test e2e/security-headers.spec.ts` (slower but thorough).
- **Option B:** replace Playwright check with a lighter node-native `fetch` test in `frontend/__tests__/headers.test.ts` that spins up Next in memory. Faster, but can't catch browser-console CSP violations.

**Recommendation: Option B for CI-gating** (fast, catches header-value regressions), **Option A for pre-release smoke** (catches real-browser CSP violations, runs on `release: types: [created]` in GHA).

---

## 8. Rollout Strategy Recommendation

### The core question: enforce CSP day one, or `Content-Security-Policy-Report-Only` first?

| Approach | Pros | Cons |
|----------|------|------|
| **Enforce day one** | Simpler; no follow-up cut-over; audit-clear | A missed directive breaks the live site (e.g., a Stripe iframe 3DS flow that only fires on certain cards) — discovered only when a user tries to pay |
| **Report-Only first (recommended)** | Surfaces violations without breaking users; gives 48h observation window in staging before production enforce | Requires a `report-uri` / `report-to` endpoint (or accept browser-only console noise); two-phase rollout |

**Recommendation for THIS project:**

1. **Plan 1 (Spring headers)**: ship enforce day one. Headers on a JSON API have no rendering dependency — zero risk of breakage.
2. **Plan 2 (Next.js CSP)**: ship `Content-Security-Policy-Report-Only` on the first merge. Leave for **one week** in staging, run Playwright nightly smoke, observe browser console noise from stakeholder UAT. Flip to enforce in a follow-up PR with a one-line `'Content-Security-Policy' → 'Content-Security-Policy-Report-Only'` diff.

**Reporting endpoint:** to capture violations, either:
- Add a `report-uri /api/csp-report` endpoint (new Next.js route handler that logs to stdout — Prometheus can scrape later), OR
- Skip the endpoint and rely on browser console during manual QA + Playwright console listeners in CI.

Given the project already has structured logging (`lib/env-validation.ts`, etc.), the endpoint is maybe 15 LOC. Recommend adding it; it's useful post-phase too.

**Why this project specifically:** J'Toye has real money on the line (Stripe payments). A CSP that breaks 3DS on a subset of cards would cause silent revenue loss. Report-Only is the risk-appropriate cut-over.

---

## 9. Validation Architecture (Nyquist)

### Test Framework

| Property | Value |
|----------|-------|
| Framework | Spring Boot Test 3.4.2 + JUnit 5 (MockMvc) for Java; Jest 29.7.0 + Playwright 1.59.1 for TS |
| Config file | `core-java/build.gradle.kts`; `frontend/jest.config.js`, `frontend/playwright.config.ts` |
| Quick run command (Spring) | `./gradlew :core-java:test --tests "*SecurityHeaders*"` |
| Quick run command (Next) | `cd frontend && npx playwright test e2e/security-headers.spec.ts` |
| Full suite command | `./gradlew :core-java:test && cd frontend && npm test && npx playwright test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| SEC-03 | `GET /api/v1/shops` 200 includes `X-Frame-Options: DENY` | integration (MockMvc) | `./gradlew :core-java:test --tests "SecurityHeadersIntegrationTest.shopsEndpointHasSecurityHeaders"` | ❌ Wave 0 |
| SEC-03 | Same endpoint includes `X-Content-Type-Options: nosniff` | integration (MockMvc) | (same as above) | ❌ Wave 0 |
| SEC-03 | Same endpoint includes `Referrer-Policy: strict-origin-when-cross-origin` | integration (MockMvc) | (same as above) | ❌ Wave 0 |
| SEC-03 | HSTS present in `prod` profile | integration (MockMvc + @ActiveProfiles) | `./gradlew :core-java:test --tests "SecurityHeadersIntegrationTest.hstsPresentInProdProfile"` | ❌ Wave 0 |
| SEC-03 | HSTS absent in `dev` profile | integration (MockMvc) | `./gradlew :core-java:test --tests "SecurityHeadersIntegrationTest.hstsAbsentInDevProfile"` | ❌ Wave 0 |
| SEC-03 | Headers present on 4xx responses | integration (MockMvc unauthenticated) | `./gradlew :core-java:test --tests "SecurityHeadersIntegrationTest.headersOn401"` | ❌ Wave 0 |
| SEC-02 | Homepage CSP header present, contains `default-src 'self'`, `frame-ancestors 'none'` | e2e (Playwright response inspection) | `npx playwright test e2e/security-headers.spec.ts -g "homepage has CSP"` | ❌ Wave 0 |
| SEC-02 | `/shop/[slug]` storefront CSP header present | e2e (Playwright) | (same file, different grep) | ❌ Wave 0 |
| SEC-02 | `/dashboard` CSP header present | e2e (Playwright, requires auth setup) | (same file) | ❌ Wave 0 |
| SEC-02 | No browser-console CSP violations during full storefront→checkout flow | e2e (Playwright console listener) | `npx playwright test e2e/security-headers.spec.ts -g "no CSP violations"` | ❌ Wave 0 |
| Both | Header-snapshot regression test | integration + unit | `./gradlew :core-java:test --tests "*HeaderSnapshot*"`, `npm test -- headers` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `./gradlew :core-java:test --tests "*SecurityHeaders*"` + `npx playwright test e2e/security-headers.spec.ts` (both < 30s)
- **Per wave merge:** full Spring test suite + full Playwright e2e
- **Phase gate:** `./gradlew :core-java:test && cd frontend && npm test && npx playwright test` green before `/gsd-verify-work`

### Wave 0 Gaps

- [ ] `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java` — covers SEC-03
- [ ] `core-java/src/test/resources/security-headers.golden.json` — snapshot golden
- [ ] `frontend/e2e/security-headers.spec.ts` — covers SEC-02
- [ ] `frontend/__snapshots__/security-headers.snapshot.json` — snapshot golden
- [ ] (Optional) `.github/workflows/ci-cd.yaml` Playwright step OR `frontend/__tests__/headers.test.ts` Jest-based lighter alternative
- [ ] Framework installs: none — all test frameworks already in `build.gradle.kts` + `package.json`

### Failure Modes

1. **False green on HSTS-dev test:** if the test doesn't set a test-profile explicitly, it might inherit prod profile in CI and see HSTS. Use `@ActiveProfiles("dev")` explicitly on the dev-absent test.
2. **False green on CSP Playwright:** if Playwright runs against a broken dev server, `response.headers()` returns empty. Assert `response.ok()` first.
3. **Playwright flakiness on Stripe iframe:** Stripe's iframe loads async and emits CSP warnings until mounted. Wait for `iframe[title*="Secure"]` to be visible before asserting zero violations.

---

## 10. Threat Model Dimensions (STRIDE)

SEC-02 + SEC-03 mitigate specific STRIDE categories. The planner's `<threat_model>` block should reference these directly:

| Threat | STRIDE Category | Mitigated By | Residual Risk |
|--------|-----------------|--------------|---------------|
| **Clickjacking** (attacker frames our UI to trick user into unintended clicks) | Tampering | `X-Frame-Options: DENY` (Spring) + `frame-ancestors 'none'` (Next.js CSP) | Near-zero for modern browsers; IE11 users get `X-Frame-Options` fallback |
| **MIME sniffing attack** (attacker uploads a file that browser interprets as script despite server Content-Type) | Elevation of Privilege (via Tampering) | `X-Content-Type-Options: nosniff` (both services) | Zero for the directive itself; attack surface is any user-upload pipe (image uploads on Products) |
| **Referer leak** (sensitive URL paths leak to third-party via `Referer` header) | Information Disclosure | `Referrer-Policy: strict-origin-when-cross-origin` | None — partial disclosure (origin only) is accepted by standard |
| **Protocol downgrade MITM** (HTTPS site visited over HTTP first, attacker strips TLS) | Tampering + Spoofing | `Strict-Transport-Security: max-age=31536000; includeSubDomains` (prod only) | First-visit window before HSTS pin; consider `preload` for stronger guarantee in a later phase |
| **Reflected XSS** (attacker injects script via URL param, reflected into page) | Tampering (via XSS) + Information Disclosure | CSP `script-src 'self'` + allowlisted Stripe origins blocks inline reflections to origins outside allowlist. **Note: `'unsafe-inline'` in static CSP weakens this significantly.** | MEDIUM — `'unsafe-inline'` allows inline `<script>` reflected via unsanitized templating. Must combine with input sanitization (Next.js escapes by default in JSX). |
| **Stored XSS** (attacker stores malicious script in DB, executed in another user's browser) | Tampering (via XSS) | Same CSP as above. | Same as reflected — `'unsafe-inline'` weakens. Consider nonce CSP in a later milestone. |
| **Cross-origin data theft** (malicious site fetches API data via `<form>` or `<script>` tag) | Information Disclosure | CORS (already configured), `connect-src` allowlist on frontend | Unchanged — CSP `connect-src` restricts our frontend's fetches; it doesn't help cross-origin attackers. |

**Threats NOT addressed by this phase (residual for other phases):**
- **CSRF** — already mitigated by stateless JWT Bearer auth (SecurityConfig.java:60 comment cites ADR-001)
- **SQL injection / ORM injection** — JPA parameter binding
- **Tenant isolation bypass** — SEC-01 / Phase 13 (Guest Tracking Tenant Validation)
- **Insecure direct object reference (IDOR)** — not scoped here
- **Rate-limiting bypass** — Bucket4j (RateLimitConfig)
- **Credential stuffing** — Keycloak's responsibility

**ASVS Categories applicable:**

| ASVS Category | Applies | Standard Control |
|---------------|---------|------------------|
| V2 Authentication | no (handled by Keycloak + NextAuth) | — |
| V3 Session Management | no (JWT Bearer; sessions handled by NextAuth client cookie) | — |
| V4 Access Control | no | — |
| V5 Input Validation | partial — Next.js JSX auto-escape + Jakarta `@Valid`; this phase doesn't add direct controls | — |
| V6 Cryptography | no (Keycloak) | — |
| **V14 Configuration** | **yes — primary focus** | CSP, HSTS, security headers per ASVS 14.4.1–14.4.7 |

Specifically maps to:
- **ASVS 14.4.1**: `Content-Security-Policy` with strict `frame-ancestors` — SEC-02 ✓
- **ASVS 14.4.2**: `X-Content-Type-Options: nosniff` — SEC-03 ✓
- **ASVS 14.4.3**: `Referrer-Policy` no-referrer or strict-origin — SEC-03 ✓ (strict-origin-when-cross-origin)
- **ASVS 14.4.5**: `Strict-Transport-Security` ≥ 1 year in prod — SEC-03 ✓
- **ASVS 14.4.7**: `X-Frame-Options` or CSP `frame-ancestors` — SEC-02 + SEC-03 ✓

---

## 11. Open Questions (RESOLVED — defaults adopted in 12-01 and 12-02 objective blocks)

These are the decisions a `/gsd-discuss-phase 12` would normally resolve. Phase 12 skipped that step, so the planner must pick defaults and note them explicitly.

1. **Nonce CSP vs. static CSP** — §5.1. Recommendation: static. Impact if wrong: nonce CSP breaks ISR on storefront. **Default:** static via `next.config.mjs`.
2. **Enforce vs. Report-Only rollout** — §8. Recommendation: Report-Only first for CSP, enforce for Spring headers. **Default:** two-phase.
3. **CSP reporting endpoint** — §8. Recommendation: add `POST /api/csp-report` route handler, log to stdout. **Default:** omit for now (browser console + Playwright sufficient), add in a follow-up if violations are noisy.
4. **Playwright in CI** — §7.3 CI integration. Recommendation: Option B (lighter Jest fetch-based check in CI, full Playwright in local + release). **Default:** Option B; log as deferred.
5. **`middleware.ts` → `proxy.ts` migration scope** — §5.2. Recommendation: out of scope for Phase 12; ride the deprecation warning. **Default:** out of scope.
6. **HSTS `preload` directive** — §4.2, threat table. Recommendation: NOT in Phase 12 (requires registration at hstspreload.org, immutable commitment). **Default:** defer.
7. **Permissions-Policy header** — not in SEC-03 scope but cheap to add. Recommendation: include `Permissions-Policy: camera=(), microphone=(), geolocation=(), browsing-topics=()` on both services as it has no runtime impact. **Default:** ADD IT — one more line of defence, near-zero cost.
8. **Dev profile HTTP fallback** — §4.2. Recommendation: explicit `.httpStrictTransportSecurity(hsts -> hsts.disable())` in non-prod branch to ensure SEC-03 criterion 2 ("absent in dev") passes deterministically regardless of request scheme. **Default:** explicit disable.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Next.js 16.2.2 static CSP requires `'unsafe-inline'` in `script-src` due to inline hydration scripts. | §5.1, §5.3 | LOW — if React 19/Next 16 changed hydration to use external scripts only, we over-permit inline. Empirically verifiable in Plan 2 Wave 1 by trying without `'unsafe-inline'` first. |
| A2 | Spring Security 6 default `HstsHeaderWriter` only emits HSTS on `isSecure()` requests, so local HTTP dev is incidentally safe from HSTS leakage without explicit config. | §3, §4.2 | MEDIUM — if this changes in a Spring Security patch, dev environment starts emitting HSTS, breaking SEC-03 criterion 2. Explicit `.disable()` in non-prod branch is the mitigation (which is the recommendation). |
| A3 | NextAuth v5 beta client-side code does not inject new inline scripts beyond Next.js framework scripts. | §6.2 | LOW — verifiable via Playwright in Plan 2. If wrong, add NextAuth-specific hash or nonce to `script-src`. |
| A4 | The project's Stripe integration uses Stripe Elements only, not Stripe Checkout, so `checkout.stripe.com` is not required. | §6.1 | LOW — confirmed by reading `checkout/page.tsx:430–451` which uses `<Elements>` inline. If the project adds Stripe Checkout in a later phase, CSP must be extended. |
| A5 | The dev stack runs frontend on port 3100 (per CLAUDE.md memory `feedback_port3100.md`), not 3000 as `playwright.config.ts` hardcodes. | §3, §7 | MEDIUM — Playwright tests against port 3000 will fail locally. Plan must parameterize via `baseURL: process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"` or fix the memory. |

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Spring Security 6.4.x | Spring header config | ✓ (via Spring Boot 3.4.2) | 6.4.x | — |
| Next.js | CSP headers | ✓ | 16.2.2 | — |
| Playwright | e2e header tests | ✓ | 1.59.1 | Jest + `fetch` if Playwright CI ends up too slow |
| MockMvc | Spring integration tests | ✓ (Spring Boot Test) | 3.4.2 | — |
| Stripe test mode | CSP-violation smoke during checkout | ✓ (existing) | — | Skip 3DS flow, assert Elements mounts only |
| Keycloak dev instance | NextAuth signin CSP flow | ✓ (docker-compose) | 24.0.5 | — |

**No blocking dependencies missing.** All test infrastructure in place.

---

## Sources

### Primary (HIGH confidence)
- **docs.spring.io/spring-security/reference/servlet/exploits/headers.html** — Spring Security 6 headers DSL, HSTS/frameOptions/referrerPolicy/contentTypeOptions configuration, enum values for `ReferrerPolicyHeaderWriter.ReferrerPolicy`.
- **nextjs.org/docs/app/api-reference/config/next-config-js/headers** — `headers()` async function syntax, matcher patterns, version 16.2.4 (last updated 2026-04-15).
- **nextjs.org/docs/app/guides/content-security-policy** — nonce vs. static CSP, dynamic rendering requirement, `proxy.ts` pattern, `'unsafe-inline'` inline-hydration requirement.
- **nextjs.org/blog/next-16** (published 2025-10-21) — `middleware.ts` → `proxy.ts` deprecation, exact quote: *"still available for Edge runtime use cases, but it is deprecated and will be removed in a future version."*
- **nextjs.org/docs/messages/middleware-to-proxy** — codemod command: `npx @next/codemod@canary middleware-to-proxy .`
- **docs.stripe.com/security/guide** (via WebFetch 2026-04-18) — required CSP directives for Stripe.js + Elements + 3DS.

### Secondary (MEDIUM confidence — cross-verified)
- `github.com/stripe/stripe-js/issues/127` — confirms telemetry origin `q.stripe.com` in `connect-src`.
- `github.com/vercel/next.js/discussions/81703` — Next.js inline hydration scripts + `'unsafe-inline'` requirement in non-nonce mode.

### Tertiary (LOW confidence — mark for validation)
- ASVS 14.4.x category mappings — based on OWASP ASVS v4.0.3 knowledge; planner should verify against the project's current ASVS target if one is formalized.

---

## Metadata

**Confidence breakdown:**
- Spring Security 6 DSL: HIGH — direct quote from official docs, DSL unchanged since 6.0.
- Next.js 16 CSP strategy: HIGH — fresh docs (updated 2026-04-15), Next.js 16 specifically covered.
- Stripe CSP requirements: HIGH — verified against docs.stripe.com.
- NextAuth v5 beta CSP impact: MEDIUM — no NextAuth-specific CSP doc found; inferred from server-side execution model. Empirical Playwright verification required.
- Current state audit: HIGH — direct file reads with line numbers.
- Rollout strategy: HIGH (recommendation) — standard industry practice for CSP rollout.

**Research date:** 2026-04-18
**Valid until:** 2026-05-18 (30 days — Next.js 16 is post-v16 GA so docs are stable; Spring Security 6 DSL unchanged for multiple minor versions).

---

## RESEARCH COMPLETE

**Phase:** 12 — Spring Security Response Headers + Frontend CSP
**Confidence:** HIGH

### Key Findings (3 most decision-impacting)

1. **Next.js 16 deprecated `middleware.ts` in favor of `proxy.ts` (October 2025, Next.js 16 GA).** The project's current `frontend/middleware.ts` emits deprecation warnings under Next.js 16.2.2. Recommendation: scope CSP into `next.config.mjs` (static CSP), leave `middleware.ts` untouched this phase, track rename as a separate future migration. This avoids entangling two concerns.

2. **Static CSP vs. nonce CSP is a rendering-mode decision, not just a header value.** Nonce CSP forces every page to be dynamically rendered — kills ISR/SSG/PPR on storefront. Static CSP requires `'unsafe-inline'` in `script-src` which measurably weakens XSS defence. Recommendation: static CSP for this ASVS L1 milestone; revisit for L2+ compliance in a future phase.

3. **Stripe needs 5 distinct origins across 4 directives, and REQUIREMENTS.md's CSP list is missing `frame-src` and `form-action` — both required to keep Stripe 3DS and NextAuth signin working.** Ship CSP as `Content-Security-Policy-Report-Only` first (one week observation), then enforce. Spring headers can ship enforce day one (no rendering dependency).

### File Created
`/home/sanmi/IdeaProjects/JToye_OaaS_2026/.planning/phases/12-spring-security-response-headers-frontend-csp/12-RESEARCH.md`

### Confidence Assessment
| Area | Level | Reason |
|------|-------|--------|
| Standard Stack | HIGH | All tooling already in repo (Spring Security 6, Next.js 16, Playwright, MockMvc) |
| Architecture | HIGH | Current state audited with file:line; zero existing header config; clean green field |
| Pitfalls | HIGH | Three specific traps documented (middleware→proxy, nonce-requires-dynamic, REQUIREMENTS.md missing frame-src) with citations |
| NextAuth/Stripe allowlist | MEDIUM | Stripe verified against official docs; NextAuth inferred — Playwright empirical verification required in Plan 2 Wave 1 |

### Open Questions (for planner defaults)
8 listed in §11 — most impactful: nonce vs. static CSP, enforce vs. Report-Only rollout, Playwright-in-CI vs. Jest-fetch alternative, port 3000 vs. 3100 for Playwright baseURL.

### Ready for Planning
Research complete. Planner can now create PLAN.md files. Recommend 2 plans: **Plan 1** = Spring Security headers (mechanical, enforce day one, ~4 tasks); **Plan 2** = Next.js CSP via `next.config.mjs` with Report-Only → enforce cut-over (~6 tasks including snapshot infra).
