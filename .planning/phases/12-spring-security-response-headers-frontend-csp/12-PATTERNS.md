# Phase 12: Spring Security Response Headers + Frontend CSP - Pattern Map

**Mapped:** 2026-04-18
**Files analyzed:** 9 (2 modify, 7 create)
**Analogs found:** 8 / 9

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` (MODIFY) | config (security) | request-response | self (in-place edit); pattern ref `core-java/src/main/java/uk/jtoye/core/config/OpenApiConfig.java` for `@Profile` | exact — existing file |
| `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java` (CREATE) | test (integration) | request-response | `core-java/src/test/java/uk/jtoye/core/integration/ShopControllerIntegrationTest.java` + `core-java/src/test/java/uk/jtoye/core/security/RateLimitIntegrationTest.java.disabled` | exact |
| `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersProdProfileTest.java` (CREATE) | test (integration, profile-gated) | request-response | `core-java/src/test/java/uk/jtoye/core/audit/AuditServiceTest.java` (uses `@ActiveProfiles`) + `ShopControllerIntegrationTest.java` (MockMvc) | role-match (compose) |
| `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersDevProfileTest.java` (CREATE) | test (integration, profile-gated) | request-response | same as ProdProfileTest | role-match (compose) |
| `frontend/next.config.mjs` (MODIFY) | config (frontend) | request-response | self (in-place edit); external ref (Next.js docs) | exact — existing file |
| `frontend/__tests__/csp-headers.test.ts` (CREATE) | test (unit, Jest) | request-response | `frontend/lib/__tests__/api-client.test.ts` (closest `__tests__/` Jest TS shape) + `frontend/types/__tests__/api.test.ts` | role-match — no existing Jest test fetches HTTP headers |
| `frontend/__tests__/header-snapshot.test.ts` (CREATE) | test (unit, snapshot) | request-response | `frontend/lib/__tests__/api-client.test.ts` for file shape; snapshot pattern is new | role-match |
| `frontend/tests/csp-no-violations.spec.ts` (CREATE) | test (Playwright e2e) | event-driven (console listener) | `frontend/e2e/storefront-flows.spec.ts` + `frontend/e2e/stomp-relay.spec.ts` (WebSocket listener is closest analog to CSP-violation listener) | role-match |
| `.github/workflows/ci-cd.yaml` (POSSIBLY MODIFY) | config (CI/CD) | batch | self (existing `test` job at lines 17-86) | exact — existing file |

**Note on `frontend/tests/` vs `frontend/e2e/`:** The research prompt lists `frontend/tests/csp-no-violations.spec.ts` but the project's existing Playwright specs all live in `frontend/e2e/` (per `playwright.config.ts:4` — `testDir: "./e2e"`). **Planner decision needed:** either (a) create a new `frontend/tests/` directory and update `playwright.config.ts` `testDir` to match both, or (b) place the file under `frontend/e2e/csp-no-violations.spec.ts` to match project convention. Recommend (b) — matches convention, zero config change.

---

## Pattern Assignments

### `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` (config, request-response) — MODIFY

**Analog:** self (existing `SecurityFilterChain` bean at lines 49-77).

**Insertion point:** between `.cors(...)` (line 61) and `.authorizeHttpRequests(...)` (line 62), OR between `.authorizeHttpRequests(...)` end (line 68) and `.oauth2ResourceServer(...)` (line 69). Either placement is idiomatic — `.headers(...)` is order-independent in the Spring Security 6 DSL. RESEARCH.md §4.1 suggests between authorizeHttpRequests and oauth2ResourceServer.

**Existing bean signature to extend** (lines 49-50):

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTenantFilter jwtTenantFilter, TenantFilter tenantFilter) throws Exception {
```

**Extension: add `Environment env` parameter** (per RESEARCH.md §4.2 Pattern A) — Spring autowires this automatically. New signature:

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                JwtTenantFilter jwtTenantFilter,
                                                TenantFilter tenantFilter,
                                                org.springframework.core.env.Environment env) throws Exception {
```

**Imports pattern to follow** (existing lines 1-16):

```java
package uk.jtoye.core.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
// ...
```

`Customizer.withDefaults()` is already imported (line 8) — reuse for `contentTypeOptions`. **New imports required:**

```java
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
```

**Existing DSL pattern — mirror the lambda-per-block style** (lines 51-69):

```java
http
    .csrf(csrf -> csrf.disable())
    .cors(Customizer.withDefaults())
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/", "/health", "/actuator/health", "/actuator/info").permitAll()
        // ...
        .anyRequest().authenticated()
    )
    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
```

**New `.headers(...)` block to insert** (composed from RESEARCH.md §4.1 + §4.2 Pattern A):

```java
boolean isProd = Arrays.asList(env.getActiveProfiles()).contains("prod");

http.headers(headers -> {
    headers.frameOptions(frame -> frame.deny())
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
```

**Filter attachments pattern to preserve** (lines 72-75 — DO NOT REMOVE):

```java
http.addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class);
http.addFilterAfter(jwtTenantFilter, BearerTokenAuthenticationFilter.class);
return http.build();
```

**Comment-style to match** — the existing file uses long multi-line comments above security decisions (lines 52-59 for CSRF rationale). Add a similar 3-5 line comment above the new `.headers(...)` block explaining: "Browser security headers per ASVS 14.4.x. HSTS gated by prod profile so dev HTTP requests do not see it (RESEARCH.md §4.2)."

---

### `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java` (test, request-response) — CREATE

**Analog:** `core-java/src/test/java/uk/jtoye/core/integration/ShopControllerIntegrationTest.java` (lines 1-68) for the `@SpringBootTest + @AutoConfigureMockMvc + @Testcontainers + PostgreSQLContainer + @DynamicPropertySource` scaffold. **Header-assertion vocabulary** comes from `RateLimitIntegrationTest.java.disabled:83-86`.

**Package + imports pattern** (mirror `ShopControllerIntegrationTest.java:1-24`):

```java
package uk.jtoye.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
```

**Class-level annotations — copy from `ShopControllerIntegrationTest.java:26-30`:**

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD)
@org.junit.jupiter.api.Tag("testcontainers")
class SecurityHeadersIntegrationTest {
```

**Testcontainer declaration — copy verbatim from `ShopControllerIntegrationTest.java:33-46`:**

```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("jtoye_test")
        .withUsername("test")
        .withPassword("test");

@DynamicPropertySource
static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("rate-limiting.enabled", () -> "false");
}

@Autowired
private MockMvc mockMvc;
```

**Header-assertion pattern — adapt from `RateLimitIntegrationTest.java.disabled:82-86`:**

```java
// ShopControllerIntegrationTest.java:70-74 style for endpoint+status:
//   mockMvc.perform(get("/api/v1/shops")).andExpect(status().isOk());
// Extended with header matchers (RateLimitIntegrationTest.java.disabled:83-86 shape):

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
void headersPresentOn401() throws Exception {
    // Unauthenticated — pattern from ShopControllerIntegrationTest.java:77-80
    mockMvc.perform(get("/api/v1/shops"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
}
```

**Note:** `WithMockUser` import already shown in `ShopControllerIntegrationTest.java:11`. The `@WithMockUser` stub bypasses the OAuth2 JWT flow — that's the project's idiom for controller-layer tests that need `anyRequest().authenticated()` to pass.

---

### `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersProdProfileTest.java` (test, profile-gated) — CREATE

**Analog composition:**
1. **`@ActiveProfiles` pattern:** `core-java/src/test/java/uk/jtoye/core/audit/AuditServiceTest.java:20` — `@ActiveProfiles("test")`. Use `@ActiveProfiles("prod")` here.
2. **MockMvc scaffold:** identical to `SecurityHeadersIntegrationTest.java` (see above).

**`@ActiveProfiles` import + usage** (from `AuditServiceTest.java:6, 20`):

```java
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("prod")
@org.junit.jupiter.api.Tag("testcontainers")
class SecurityHeadersProdProfileTest {
    // same @Container and @DynamicPropertySource as SecurityHeadersIntegrationTest
}
```

**HSTS-present assertion — from RESEARCH.md §7.1 (note the `.secure(true)` trap):**

```java
@Test
@WithMockUser
void hstsPresentInProdProfile() throws Exception {
    // .secure(true) is REQUIRED — default HstsHeaderWriter only emits HSTS
    // when isSecure() returns true. Without this, prod-profile test passes
    // incorrectly (RESEARCH.md §7.1 "HSTS profile test trap").
    mockMvc.perform(get("/api/v1/shops").secure(true))
        .andExpect(header().string("Strict-Transport-Security",
            org.hamcrest.Matchers.containsString("max-age=31536000")))
        .andExpect(header().string("Strict-Transport-Security",
            org.hamcrest.Matchers.containsString("includeSubDomains")));
}
```

**Known loading issue:** prod profile may require prod-specific `application-prod.yml` config values (e.g., real Keycloak issuer URI). **Planner should verify** whether test context starts cleanly under `@ActiveProfiles("prod")`; if not, add `@DynamicPropertySource` overrides for `spring.security.oauth2.resourceserver.jwt.issuer-uri` to a testcontainer Keycloak stub or to a static URL (the JWT decoder is lazily initialised — static URL may suffice).

---

### `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersDevProfileTest.java` (test, profile-gated) — CREATE

**Analog:** mirror `SecurityHeadersProdProfileTest.java` structure, change `@ActiveProfiles("prod")` → `@ActiveProfiles("dev")`.

**HSTS-absent assertion** (from RESEARCH.md §7.1):

```java
@Test
@WithMockUser
void hstsAbsentInDevProfile() throws Exception {
    mockMvc.perform(get("/api/v1/shops"))
        .andExpect(header().doesNotExist("Strict-Transport-Security"));
}

@Test
@WithMockUser
void hstsAbsentEvenOverHttpsInDevProfile() throws Exception {
    // Explicit disable in non-prod branch (SecurityConfig.java .httpStrictTransportSecurity(hsts -> hsts.disable()))
    // means even .secure(true) requests get NO HSTS in dev.
    mockMvc.perform(get("/api/v1/shops").secure(true))
        .andExpect(header().doesNotExist("Strict-Transport-Security"));
}
```

---

### `frontend/next.config.mjs` (config, request-response) — MODIFY

**Analog:** self (existing file lines 1-22).

**Current module shape to preserve** (lines 1-22):

```js
/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  images: {
    remotePatterns: [
      {
        protocol: 'http',
        hostname: 'localhost',
        port: '9000',
        pathname: '/jtoye-images/**',
      },
    ],
  },
};

export default nextConfig;
```

**Extension pattern (composed from RESEARCH.md §5.3 + §5.4):** Add a `cspDirectives` const above `nextConfig`, add `headers()` async function inside `nextConfig` alongside `output` and `images`. Do NOT restructure the existing object — the diff should be additive only.

**Target shape:**

```js
/** @type {import('next').NextConfig} */

const isDev = process.env.NODE_ENV === 'development'

const cspDirectives = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline'${isDev ? " 'unsafe-eval'" : ''} https://js.stripe.com https://*.js.stripe.com`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https://*.stripe.com https: http://localhost:9000",
  "font-src 'self' data:",
  "connect-src 'self' https://api.stripe.com https://*.stripe.com " +
    (process.env.NEXT_PUBLIC_API_URL || '') + ' ' +
    (process.env.NEXT_PUBLIC_API_URL || '').replace(/^http/, 'ws') + ' ' +
    (process.env.NEXT_PUBLIC_KEYCLOAK_URL || ''),
  "frame-src https://js.stripe.com https://*.js.stripe.com https://hooks.stripe.com",
  "frame-ancestors 'none'",
  "form-action 'self' " + (process.env.NEXT_PUBLIC_KEYCLOAK_URL || ''),
  "base-uri 'self'",
  "object-src 'none'",
  "upgrade-insecure-requests",
].join('; ')

const nextConfig = {
  output: 'standalone',
  images: {
    // ... unchanged
  },
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          {
            key: isDev ? 'Content-Security-Policy-Report-Only' : 'Content-Security-Policy',
            value: cspDirectives,
          },
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
          { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=(), browsing-topics=()' },
        ],
      },
    ]
  },
};

export default nextConfig;
```

**Rollout note per RESEARCH.md §8:** ship CSP as `Content-Security-Policy-Report-Only` first. The `isDev ? ... : ...` ternary above is a **placeholder for phase-2 rollout**; the planner should pick one of:
- **Plan 2 Task 1:** ship Report-Only unconditionally (both dev and prod), observe 1 week.
- **Plan 2 Task N (later):** flip to enforce by removing the ternary (always `Content-Security-Policy`).

---

### `frontend/__tests__/csp-headers.test.ts` (test, Jest) — CREATE

**Analog:** `frontend/lib/__tests__/api-client.test.ts:1-50` for the file location convention (co-located `__tests__/` for Jest) and the `describe`/`it` block shape. **No existing Jest test fetches HTTP headers** — this is a mild new-pattern territory.

**File-shape skeleton** (modeled on `api-client.test.ts`):

```ts
// frontend/__tests__/csp-headers.test.ts

describe('Next.js security response headers', () => {
  describe('CSP', () => {
    it('headers() returns Content-Security-Policy with expected directives', async () => {
      // Approach A: import nextConfig and call headers() directly.
      // nextConfig.headers() is an async function that returns an array
      // of { source, headers: [{ key, value }] } entries.
      const { default: nextConfig } = await import('../next.config.mjs')
      const routes = await nextConfig.headers()
      expect(routes.length).toBeGreaterThan(0)

      const csp = routes[0].headers.find(
        (h: { key: string; value: string }) =>
          h.key === 'Content-Security-Policy' || h.key === 'Content-Security-Policy-Report-Only'
      )
      expect(csp).toBeDefined()
      expect(csp!.value).toContain("default-src 'self'")
      expect(csp!.value).toContain("frame-ancestors 'none'")
      expect(csp!.value).toContain('https://js.stripe.com')
      expect(csp!.value).toContain("form-action 'self'")
    })

    it('X-Content-Type-Options is nosniff', async () => {
      const { default: nextConfig } = await import('../next.config.mjs')
      const routes = await nextConfig.headers()
      const xcto = routes[0].headers.find(
        (h: { key: string; value: string }) => h.key === 'X-Content-Type-Options'
      )
      expect(xcto?.value).toBe('nosniff')
    })
  })
})
```

**Why unit-level, not fetch-based:** per RESEARCH.md §7.3 Option B recommendation — avoids spinning up Next in CI. Full header-on-the-wire check is covered by Playwright (next file).

**Jest config caveat:** the project's `jest.config.js` must allow importing `.mjs`. Confirm `transformIgnorePatterns` or use a pure-JS re-export helper if Jest/ts-jest rejects ESM import.

---

### `frontend/__tests__/header-snapshot.test.ts` (test, snapshot) — CREATE

**Analog:** `frontend/lib/__tests__/api-client.test.ts` for file shape. Snapshot golden-file comparison is new; the concept is borrowed from RESEARCH.md §7.3.

**Pattern:**

```ts
// frontend/__tests__/header-snapshot.test.ts
import { readFileSync } from 'fs'
import { join } from 'path'

describe('Security headers snapshot (regression guard)', () => {
  it('matches golden snapshot', async () => {
    const { default: nextConfig } = await import('../next.config.mjs')
    const routes = await nextConfig.headers()

    // Build a deterministic snapshot: sort headers by key for stable output
    const snapshot = routes.map((r: { source: string; headers: Array<{key: string; value: string}> }) => ({
      source: r.source,
      headers: r.headers
        .slice()
        .sort((a, b) => a.key.localeCompare(b.key))
        .map((h) => ({ key: h.key, value: h.value })),
    }))

    const goldenPath = join(__dirname, 'snapshots', 'security-headers.golden.json')
    const golden = JSON.parse(readFileSync(goldenPath, 'utf8'))

    expect(snapshot).toEqual(golden)
  })
})
```

**Companion file to create:** `frontend/__tests__/snapshots/security-headers.golden.json` (committed). Regenerate with a one-off script or by running the test once, inspecting output, and writing the expected JSON.

**Jest toMatchSnapshot alternative:** Jest has built-in `.toMatchSnapshot()` which autogenerates `__snapshots__/*.snap` files. Simpler but less explicit. Either works; the explicit JSON form matches the "golden file" language in RESEARCH.md §7.3.

---

### `frontend/tests/csp-no-violations.spec.ts` (test, Playwright) — CREATE

**RECOMMEND RENAMING to `frontend/e2e/csp-no-violations.spec.ts`** to match project convention (see note at top of doc).

**Analog:** `frontend/e2e/storefront-flows.spec.ts:1-13` (imports, BASE const, SHOP_SLUG const) + `frontend/e2e/stomp-relay.spec.ts:132-135` (event listener pattern — `page.on("websocket", ...)` is the closest analog to `page.on("console", ...)` for CSP violations).

**Imports pattern — copy from `storefront-flows.spec.ts:8-12`:**

```ts
import { test, expect, type Page } from "@playwright/test"

const BASE = "http://localhost:3000"
const SHOP_SLUG = "jollof-express-brixton-900b57a8"
```

**Response-header inspection pattern — adapt from Playwright docs (RESEARCH.md §7.2):**

```ts
test("homepage has CSP header", async ({ page }) => {
  const response = await page.goto(`${BASE}/`)
  expect(response).not.toBeNull()
  expect(response!.ok()).toBe(true)  // RESEARCH.md §9 failure-mode #2: assert ok first

  const headers = response!.headers()
  const csp = headers['content-security-policy'] || headers['content-security-policy-report-only']
  expect(csp).toBeDefined()
  expect(csp).toContain("default-src 'self'")
  expect(csp).toContain("frame-ancestors 'none'")
  expect(csp).toContain('https://js.stripe.com')
})
```

**Event listener pattern — composed (RESEARCH.md §7.2) using `stomp-relay.spec.ts:132-135` style:**

```ts
// stomp-relay.spec.ts:132-135 analog:
//   const wsConnections: string[] = []
//   page.on("websocket", (ws) => {
//     wsConnections.push(ws.url())
//   })
//
// Replace "websocket" listener with "console" listener for CSP violations:

test("no CSP violations during storefront browse", async ({ page }) => {
  const violations: string[] = []
  page.on("console", (msg) => {
    if (msg.type() === "error" && msg.text().includes("Content Security Policy")) {
      violations.push(msg.text())
    }
  })

  await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
  await page.waitForLoadState("networkidle")
  // storefront-flows.spec.ts:118 uses waitForTimeout(3000) — matches that idiom:
  await page.waitForTimeout(3000)

  // Navigate to checkout where Stripe Elements mounts (iframe requires frame-src)
  // Pattern copied from storefront-flows.spec.ts:53-57 cart flow:
  const addBtn = page.locator('button:has-text("Add")').first()
  if (await addBtn.isVisible({ timeout: 5000 })) {
    await addBtn.click()
    await page.goto(`${BASE}/shop/${SHOP_SLUG}/cart`)
    await page.waitForLoadState("networkidle")
  }

  // RESEARCH.md §9 failure-mode #3: wait for Stripe iframe to mount before asserting
  // (not all flows reach checkout in this test; gate accordingly)
  expect(violations).toEqual([])
})
```

**Port caveat — RESEARCH.md §Assumptions A5:** `playwright.config.ts:11` hardcodes `baseURL: "http://localhost:3000"` but CLAUDE.md feedback (`feedback_port3100.md`) says dev runs on 3100. If Plan 2 runs Playwright locally during dev, either:
- Update `playwright.config.ts` to `baseURL: process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"`, or
- Start a dedicated test server on port 3000 before running.

Same treatment as the existing specs (all hardcode 3000) — consistency matters.

---

### `.github/workflows/ci-cd.yaml` (config, CI/CD) — POSSIBLY MODIFY

**Analog:** self, existing `test` job lines 17-86.

**Decision point (RESEARCH.md §11 Q4):** default is **Option B** — no Playwright job in CI; rely on Jest-based `csp-headers.test.ts` for CI gating. If this default is kept, **NO MODIFICATION NEEDED** to ci-cd.yaml.

**If planner chooses Option A** (add Playwright job for CSP smoke), mirror the existing `test` job shape (lines 17-86). Relevant excerpts:

**Service container pattern** (lines 20-33) — probably not needed for frontend-only Playwright, but shown for reference:

```yaml
services:
  postgres:
    image: postgres:15
    # ...
```

**Node + Playwright setup** (mirror lines 51-56 + add Playwright install):

```yaml
# NEW JOB (append after `test` job at line 86):
playwright-csp:
  name: Playwright CSP smoke
  runs-on: ubuntu-latest
  needs: [test]
  steps:
    - uses: actions/checkout@v4

    - name: Set up Node.js 20
      uses: actions/setup-node@v4
      with:
        node-version: '20'
        cache: 'npm'
        cache-dependency-path: frontend/package-lock.json

    - name: Install frontend dependencies
      run: npm ci
      working-directory: frontend

    - name: Install Playwright browsers
      run: npx playwright install --with-deps chromium
      working-directory: frontend

    - name: Build Next.js
      run: npm run build
      working-directory: frontend

    - name: Start Next.js in background
      run: npm run start &
      working-directory: frontend

    - name: Wait for server
      run: |
        for i in {1..30}; do
          curl -s http://localhost:3000/ && break || sleep 1
        done

    - name: Run Playwright CSP smoke
      run: npx playwright test e2e/csp-no-violations.spec.ts
      working-directory: frontend
```

**Existing frontend step to mirror** (line 75-77):

```yaml
- name: Run frontend build (validates TypeScript)
  run: npm run build
  working-directory: frontend
```

**Recommendation — carry forward RESEARCH.md §11 Q4 default:** skip Option A in CI for Phase 12; add it in a later phase if CSP regressions slip through. Only Jest header test runs in CI now. Flag as deferred in the plan.

---

## Shared Patterns

### Security lambda DSL style (Spring)

**Source:** `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java:51-69`
**Apply to:** all Spring header-config work. Spring Security 6 canonical lambda-DSL style (NOT 5.x `.and()` chains).

```java
http
    .csrf(csrf -> csrf.disable())
    .cors(Customizer.withDefaults())
    .authorizeHttpRequests(auth -> auth
        .requestMatchers(...).permitAll()
        .anyRequest().authenticated()
    )
    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
```

New `.headers(...)` block must follow the same style — lambda argument named `headers`, sub-lambdas for each nested customizer.

### `@Profile` / `@ActiveProfiles` convention (Spring)

**Source (main):** `core-java/src/main/java/uk/jtoye/core/config/OpenApiConfig.java:24` (`@Profile("!prod")`), `core-java/src/main/java/uk/jtoye/core/tenant/DevTenantController.java:20` (`@Profile({"dev", "local", "default"})`).
**Source (test):** `core-java/src/test/java/uk/jtoye/core/audit/AuditServiceTest.java:6, 20` (`@ActiveProfiles("test")`).
**Apply to:** HSTS profile gating + all profile-aware security tests.

```java
// Main-source gating (SecurityConfig): runtime env check is preferred over @Profile
// on the SecurityFilterChain bean itself because the chain is needed in every profile.
// Use Arrays.asList(env.getActiveProfiles()).contains("prod") inside the bean method
// (see RESEARCH.md §4.2 Pattern A — avoids splitting into two beans).

// Test-source gating:
@ActiveProfiles("prod")  // or "dev"
class SecurityHeadersProdProfileTest { ... }
```

### MockMvc + Testcontainers integration test scaffold

**Source:** `core-java/src/test/java/uk/jtoye/core/integration/ShopControllerIntegrationTest.java:26-49`
**Apply to:** all three new `SecurityHeaders*Test` files.

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD)
@org.junit.jupiter.api.Tag("testcontainers")
class XxxTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15") ...;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) { ... }

    @Autowired
    private MockMvc mockMvc;
}
```

### Header-assertion vocabulary (MockMvc)

**Source:** `core-java/src/test/java/uk/jtoye/core/security/RateLimitIntegrationTest.java.disabled:83-86`

```java
.andExpect(header().exists("X-RateLimit-Limit"))
.andExpect(header().string("X-Frame-Options", "DENY"))          // exact match
.andExpect(header().string("HSTS-Header", org.hamcrest.Matchers.containsString("max-age=31536000")))  // substring
.andExpect(header().doesNotExist("Strict-Transport-Security"))  // negative
```

### Playwright spec file conventions

**Source:** `frontend/e2e/storefront-flows.spec.ts:1-13` + `frontend/e2e/stomp-relay.spec.ts:18-24`
**Apply to:** `csp-no-violations.spec.ts`.

```ts
import { test, expect, type Page } from "@playwright/test"

const BASE = process.env.FRONTEND_URL || "http://localhost:3000"
// Add shop-specific constants as needed

test.describe("Feature name", () => {
  test("behaviour", async ({ page }) => {
    await page.goto(`${BASE}/...`)
    await page.waitForLoadState("networkidle")
    // Assertions ...
  })
})
```

**Note the existing pattern uses `waitForLoadState("networkidle")` + `waitForTimeout(N)` pairs** — copy this idiom rather than relying solely on `networkidle`.

### Jest test file conventions (frontend)

**Source:** `frontend/lib/__tests__/api-client.test.ts:1-50`
**Apply to:** `frontend/__tests__/csp-headers.test.ts`, `frontend/__tests__/header-snapshot.test.ts`.

- File path: co-located `__tests__/` directory or top-level `frontend/__tests__/`.
- Suffix: `*.test.ts` (no JSX → `.ts`, not `.tsx`).
- Structure: nested `describe()` + `it()`; `expect(...).toBe(...)`, `expect(...).toBeDefined()`.
- No `import '@testing-library/jest-dom'` for pure non-DOM tests (api-client.test.ts omits it).

---

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `frontend/__tests__/header-snapshot.test.ts` | snapshot test | request-response | No existing snapshot/golden-file test in the frontend codebase. Pattern is new — synthesised from Jest docs + RESEARCH.md §7.3. Not a strong analog; planner proceeds from the framework defaults. |

All other files have at least a role-match analog to copy from.

---

## Critical Context Notes for the Planner

1. **`frontend/middleware.ts` is OUT OF SCOPE for this phase** (RESEARCH.md §5.2). Static CSP goes in `next.config.mjs` only. Do not rename to `proxy.ts` in Phase 12.

2. **HSTS via `.secure(true)`** — MockMvc prod-profile test WILL produce a false-green without `.secure(true)` because Spring's `HstsHeaderWriter` only emits HSTS on HTTPS requests (RESEARCH.md §7.1 "HSTS profile test trap"). Plan must include this flag in assertions.

3. **Frontend Playwright file location:** project convention is `frontend/e2e/*.spec.ts`, NOT `frontend/tests/*.spec.ts`. Rename per the note at the top of this document to avoid `playwright.config.ts` conflicts.

4. **Port 3000 vs 3100 (RESEARCH.md §Assumptions A5):** `playwright.config.ts:11` hardcodes 3000; local dev runs on 3100 per CLAUDE.md memory. Either parameterize via env var or ensure the Next server under test runs on 3000. Flag explicitly in the plan.

5. **Report-Only rollout (RESEARCH.md §8):** Plan 2 should ship CSP as `Content-Security-Policy-Report-Only` first, then flip to `Content-Security-Policy` in a follow-up task after a 1-week observation window in staging.

6. **No Playwright job in CI today:** decision deferred per RESEARCH.md §11 Q4 — use Jest-based header test in CI. `.github/workflows/ci-cd.yaml` modification likely NOT NEEDED.

---

## Metadata

**Analog search scope:**
- `core-java/src/main/java/uk/jtoye/core/security/` — SecurityConfig, TenantContext-related files
- `core-java/src/main/java/uk/jtoye/core/config/` — OpenApiConfig, CacheConfig, RateLimitConfig (for `@Profile`)
- `core-java/src/test/java/uk/jtoye/core/security/` — existing security tests
- `core-java/src/test/java/uk/jtoye/core/integration/` — MockMvc pattern source
- `core-java/src/test/java/uk/jtoye/core/audit/` — `@ActiveProfiles` usage
- `frontend/next.config.mjs` — target file (existing state)
- `frontend/__tests__/`, `frontend/lib/__tests__/`, `frontend/types/__tests__/` — Jest analogs
- `frontend/e2e/` — Playwright analogs
- `frontend/playwright.config.ts` — test runner config
- `.github/workflows/ci-cd.yaml` — CI/CD pattern

**Files scanned:** 12 read + 8 Grep searches + 5 Glob searches.

**Pattern extraction date:** 2026-04-18
