# Testing Patterns

**Analysis Date:** 2026-09-03

This is a REFRESH of a document last written 2026-04-18 (which reported 516
"logical test invocations" across three runners). The picture has changed
substantially: there are now **five** runners, and the single source of truth
for every count is `docs/metrics.json`, not an ad hoc `grep`/`find` count run
by hand. Read that file before quoting any number in this project.

## Test Framework — Five Runners, One Source of Truth

**`docs/metrics.json` is the declared source of truth — and on this branch it is STALE.**
Verified by reading it directly (2026-09-03), then by running the gate that checks it:

> ⚠️ **`scripts/docs-freshness.sh` exits 1 on `feature/qa-remediate-20260902` (measured 2026-09-03).**
> The branch added tests without regenerating the metrics file, so every number quoted below is the
> *documented* value, not the *measured* one. Do not cite these as current until the gate is green.
>
> | Metric | `docs/metrics.json` (documented) | Computed from tree (measured) |
> |---|---|---|
> | `java_test_methods` | 1730 | **1869** |
> | `java_test_files` | 275 | **295** |
> | `jest_blocks` | 1583 | **1779** |
> | `jest_files` | 146 | **169** |
> | `mcp_test_blocks` | 48 | **53** |
> | `total_logical_invocations` | 3572 | **3912** |
>
> `go_test_funcs` (84), `playwright_blocks` (127) and `playwright_specs` (27) are unchanged.
> Remedy: `scripts/docs-freshness.sh --write`, then update the prose in `README.md`, `AGENTS.md`
> and `CLAUDE.md` so the second gate (`scripts/check-doc-metrics.sh`) also passes.

The documented contents, as committed:

```json
{
  "java_test_methods": 1730,
  "java_test_files": 275,
  "go_test_funcs": 84,
  "go_test_files": 11,
  "jest_blocks": 1583,
  "jest_files": 146,
  "playwright_blocks": 127,
  "playwright_specs": 27,
  "mcp_test_blocks": 48,
  "mcp_test_files": 8,
  "total_logical_invocations": 3572
}
```

| Runner | Language | Test methods/blocks | Files | Command that produces the number |
|---|---|---|---|---|
| JUnit 5 | Java | **1730** `@Test` methods | 275 | `scripts/docs-freshness.sh` (regex over git-tracked `.java` files) |
| Jest | TypeScript/JS | **1583** `it`/`test` blocks | 146 | `scripts/count-test-blocks.mjs --family jest` |
| Go `testing` | Go | **84** top-level `Test*` funcs | 11 | `scripts/docs-freshness.sh` (regex over `_test.go` files) |
| Playwright | TypeScript | **127** `test()` declaration sites | 27 specs | `scripts/count-test-blocks.mjs --family playwright` (counts by **declaration site**, not executed run — see below) |
| vitest (`mcp-server/`) | TypeScript | **48** `it`/`test` blocks | 8 | `scripts/count-test-blocks.mjs --family vitest` |
| **Total** | | **3572 logical invocations** *(documented; tree measures 3912)* | | `docs/metrics.json` `.total_logical_invocations` |

**Do not hand-count with `grep`/`find` and trust the number.** The project has twice shipped a wrong count from a naive regex:
- Pre-#291: `\b(it|test)\(` matched `RegExp.prototype.test(` (7 phantom Jest blocks measured 2026-08-05) and could not see `it.each([...])` table-driven tests at all (9 sites, 51 executed tests contributing zero to the static count). `scripts/count-test-blocks.mjs` replaced the regex counter — it masks comments/strings/regexes, rejects member access, and expands `.each` tables; it exits 2 (VOID) on anything it cannot resolve rather than silently under-counting.
- The 2026-04-18 version of this document itself used a hand-run `grep -rE "^\s*(it|test)\(" | wc -l` and landed on 76 Jest blocks against a then-current 1583 — an order of magnitude off from what the tree actually held even at that vintage's true count.

**Playwright counts by declaration site, not by execution.** The suite runs a `mobile` + `desktop` project matrix, so 127 `test()` sites execute as more runs (`~182` on prior measurement) — `playwright_blocks` in `docs/metrics.json` and README always mean "declared in source", per `scripts/check-test-count-oracle.sh`'s own documented rationale.

## Two Independent Freshness Gates (Not One)

The count above is enforced by **two** separate CI gates, wired in `.github/workflows/docs-freshness.yml`, each closing one direction of the loop:

1. **`scripts/docs-freshness.sh`** — recomputes counts from the source tree and asserts them against `docs/metrics.json`. Regenerate with `scripts/docs-freshness.sh --write` after a legitimate change.
2. **`scripts/check-doc-metrics.sh`** — asserts the numbers **quoted in prose** (README.md, `AGENTS.md`, this project's `CLAUDE.md`) against `docs/metrics.json`. This gate exists because gate 1 alone was insufficient: README sat at 921 for months while the tree stood at 1895+, and `docs-freshness.sh` was green on every one of those commits because it never opens a doc.

A third gate, **`scripts/check-test-count-oracle.sh`**, closes a different hole: it asks each **runner itself** (`jest --coverage`'s JSON report, `playwright test --list`, vitest's report) how many tests it actually has, and compares that to the manifest — the only gate here whose answer doesn't come from reading source with a regex. Wired at `.github/workflows/ci-cd.yaml:221` (jest), `:229` (playwright), `:802` (vitest).

## Java: JUnit 5 + Mockito + Testcontainers

**Unit tests — Mockito, no container:**
```java
@ExtendWith(MockitoExtension.class)
class ShopServiceTest {
    @Mock private ShopRepository shopRepository;
    @Mock private ShopMapper shopMapper;
    @Mock private StorageService storageService;
    // ...
    @Test
    void testCreateShop_Success() { ... }
    @Test
    void testCreateShop_MissingTenant() { ... }
}
```
(`core-java/src/test/java/uk/jtoye/core/shop/ShopServiceTest.java`)

**Test method naming convention:** `test<Method>_<Scenario>` — verified across `ShopServiceTest.java` (`testCreateShop_Success`, `testGetShopById_NotFound`, `testUpdateShop_NotFound`, etc.). `@DisplayName` is layered on selectively for human-readable descriptions, not used as a substitute.

**Integration tests — real Postgres via Testcontainers, exercising RLS.** 143 files in `core-java/src/test/` reference `@Testcontainers` (verified with `rg -uu -l "@Testcontainers" core-java/src/test --type java | wc -l`, .gitignore-safe count). Representative classes proving RLS enforcement, not just app-layer scoping:
- `core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java` — the schema-walk sweep: every `pg_class` relation with `relkind='r' AND relnamespace='public'::regnamespace` must carry both `ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY`, unless explicitly named in `EXEMPT_TABLES` with a written justification (catches "added a tenant table, forgot RLS" — the failure mode behind V11→V4, V14→V9, V15→V5, V33→V27/28/29 retro-patches).
- `core-java/src/test/java/uk/jtoye/core/security/MultiTenantIsolationIntegrationTest.java`, `AuditTableInsertPolicyIntegrationTest.java`, `RuntimeRoleGrantContractTest.java`, `PostcodeTruncateGrantMigrationTest.java` — RLS/grant contract tests.
- `core-java/src/test/java/uk/jtoye/core/media/MediaAssetRlsPolicyIntegrationTest.java`, `core-java/src/test/java/uk/jtoye/core/review/ReviewsRlsPolicyIntegrationTest.java`, `core-java/src/test/java/uk/jtoye/core/marketing/ShopPromotionsRlsPolicyIntegrationTest.java` — per-domain RLS policy tests.

**No shared abstract Testcontainers base class.** There is no `AbstractIntegrationTest`/`IntegrationTestBase` superclass; each test class declares its own `@Container static PostgreSQLContainer<?> postgres` and its own `@DynamicPropertySource`. What IS shared is a static helper, `core-java/src/test/java/uk/jtoye/core/testsupport/IntegrationTestSupport.java`:

```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("jtoye_test").withUsername("test").withPassword("test");

@DynamicPropertySource
static void configureProperties(DynamicPropertyRegistry registry) {
    IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
}
```

`IntegrationTestSupport.registerPostgresTestProperties()` overrides `application-test.yml`'s H2 defaults back to Postgres (datasource URL/driver/dialect, `ddl-auto=none` so Flyway — including RLS policies — is the sole schema source, a dead-port brokerless RabbitMQ so no live broker is needed). Every `@Testcontainers` class combines three things: `@ActiveProfiles("test")`, this helper, and its own per-class `@Container` (fresh DB per class, no cross-class pollution).

**RLS-enforcement caveat, documented in the helper's Javadoc:** the Testcontainers bootstrap role is a Postgres **superuser**, which bypasses even FORCE RLS. Tests proving actual enforcement additionally downgrade after seeding: `jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER")` (pattern used by `ScheduledCleanupServiceIntegrationTest`, `ShopImageCrossTenantIntegrationTest`). A further helper, `IntegrationTestSupport.provisionRuntimeRoleFromShippedSql()`, provisions the actual production **non-owner** role `jtoye_runtime` by driving the shipped `infra/db/create-runtime-role.sql` through the container's own `psql` — because `NOSUPERUSER` alone is not the same property as non-ownership (a table owner still bypasses per-tenant isolation if FORCE is ever forgotten; only the non-owner runtime role's own privilege set does not depend on that).

**Production-shaped JWT auth in `@SpringBootTest` + `MockMvc` tests:** a `RequestPostProcessor` builds a UUID-subject Keycloak-shaped JWT carrying `realm-admin` (an implicit `GROUP_ADMIN`) rather than Spring Security Test's `@WithMockUser`, because `ShopAccessService`'s fail-closed access checks reject a non-JWT principal (`ShopControllerIntegrationTest.java` `adminJwt()`).

**Gradle wiring — `test` vs `integrationTest` are two different tasks over the same source set** (`core-java/build.gradle.kts:186-284`):
- `tasks.test` **excludes** `@Tag("testcontainers")` — the fast unit job, runs on every PR/push.
- `tasks.register<Test>("integrationTest")` **includes only** `@Tag("testcontainers")` — run locally with `./gradlew :core-java:integrationTest`.
- Both draw from `sourceSets["test"]`. Measured contribution (`core-java/build.gradle.kts:292-307`): `integrationTest` alone contributes 607 tests across 132 classes and +25.43 coverage points over `test` alone — so **a unit-only coverage number is wrong by roughly a quarter of the codebase** and must never be quoted as "the" Java coverage figure.

**Coverage — JaCoCo, aggregate only, floor not target:** `scripts/check-jacoco-coverage.sh` enforces a floor on the **aggregate** report (`test.exec` + `integrationTest.exec` merged), and VOIDs (exit 2) rather than reporting 0% when either `.exec` file is absent — because Gradle's `JacocoReport` has a built-in `onlyIf` that silently skips report generation when no execution data exists, which would otherwise read as a real 0%. Measured 2026-08-29 aggregate figures documented in the script: INSTRUCTION 88.06%, BRANCH 71.88%, LINE 87.55%, METHOD 87.53% (vs. 62.55/51.03/62.12/65.01 for `test` alone — the ~25-point gap the gate exists to prevent anyone from mistaking for the real number). Wired at `.github/workflows/ci-cd.yaml:382-383`.

## Go: standard `testing` package

**Table-style and named-scenario tests, no third-party assertion library** — plain `t.Fatalf`/`t.Errorf`:
```go
func TestParseMessage_MultipleItems(t *testing.T) {
    order := ParseMessage("+447700900000", "2x Chocolate Cake\n1x Sourdough Bread")
    if len(order.Items) != 2 {
        t.Fatalf("expected 2 items, got %d", len(order.Items))
    }
    ...
}
```
(`edge-go/internal/whatsapp/parser_test.go`)

Test naming: `Test<Function>_<Scenario>` (`TestParseMessage_ProductNameWithCommas`, `TestParseMessage_SingleItem`, `TestParseMessage_FreeText`). Regression tests cite the issue they fix directly in the doc comment (`TestParseMessage_ProductNameWithCommas` cites "fix #8").

**11 test files** across `edge-go/`: `internal/auth/service_token_test.go`, `internal/middleware/jwt_test.go`, `internal/core/contract_test.go`, `internal/core/client_test.go`, `internal/whatsapp/parser_test.go`, `cmd/edge/handlers_test.go`, `cmd/edge/whatsapp_test.go`, `cmd/edge/metrics_routing_test.go`, `cmd/edge/main_test.go`, `cmd/edge/metrics_test.go` (+1 more per the 11-file manifest total).

**Coverage — `scripts/check-go-coverage.sh`, structural pre-parse before trusting any percentage.** `go tool cover -func` on an **empty** profile or one containing only `mode: set` returns `rc=0` and prints `total: (statements) 0.0%` — indistinguishable from a real zero. The gate therefore requires a mode line **plus at least one data line** before it will read any percentage; anything else VOIDs (exit 2). `MIN_TOTAL_PERCENT=65.0`, set 1.8 points below the 2026-08-28 measured total of 66.8% (per-package: `internal/whatsapp` 92.6%, `internal/auth` 88.6%, `internal/core` 80.0%, `internal/middleware` 79.8%, `cmd/edge` 49.8%). This is a **no-regression floor**, not a target — the script's own header states lowering it to make a red build green is the failure mode it exists to prevent. Wired at `.github/workflows/ci-cd.yaml:138-139`.

## Jest (frontend unit/component tests)

**Config:** `frontend/jest.config.js`, built on `next/jest`. `testEnvironment: 'jest-environment-jsdom'`. `moduleNameMapper` maps `^@/(.*)$` to the frontend root, matching the `@/` TS path alias.

**Test location:** co-located `__tests__/` directories (`frontend/app/__tests__/`, `frontend/lib/__tests__/`) or `*.test.ts(x)` alongside source. `testMatch: ['**/__tests__/**/*.[jt]s?(x)', '**/?(*.)+(spec|test).[jt]s?(x)']`. Playwright specs under `frontend/e2e/` are explicitly excluded via `testPathIgnorePatterns` (they use `@playwright/test`, which breaks Jest's runner if picked up).

**Global mocks in `frontend/jest.setup.js`:** `next-auth/react` (`useSession`, `signIn`, `signOut`, `getSession`), `next/navigation` (`useRouter`, `usePathname`, `useParams`, `useSearchParams`), and `framer-motion` (strips motion-only DOM props like `initial`/`animate`/`whileHover` before forwarding to a real DOM element, so `LazyMotion`/`m.*` components render without warnings). A test needing the real framer-motion re-mocks locally with `jest.requireActual`.

**`jest.setup.js` uses `require`, not `import`, deliberately** — `setupFilesAfterEnv` runs *before* a test file registers its own `jest.mock(...)` calls, so a top-level static import of a module under test would bind to the real dependency and defeat those mocks (documented in `frontend/eslint.config.mjs`'s override block for `jest.config.js`/`jest.setup.js`, which turns off `@typescript-eslint/no-require-imports` for exactly these two files).

**Coverage — `coverageThreshold` in `frontend/jest.config.js`, floor-not-target, same doctrine as JaCoCo/Go:**
```js
coverageThreshold: {
  global: { statements: 63, branches: 55, functions: 60, lines: 64 },
}
```
`collectCoverageFrom` spans `app/**`, `components/**`, `hooks/**`, `lib/**`, `types/**` — `hooks/**` was deliberately added (2026-08-28, plan 34-08) *before* any threshold number was picked, specifically so new hook tests (`use-theme`, `use-customer-session`) would be measured rather than invisible to the gate. Each threshold is `floor(measurement) - 2`, one stated rule rather than four separately negotiated numbers. Each was raised above its own measurement once and observed red before being trusted (per the config's own comment, evidence recorded in `.planning/phases/34-rendering-test-truthfulness/34-08-SUMMARY.md`).

**Fixture pattern:** typed inline fixture objects matching the domain type, e.g. `FIXTURE_SHOP: PublicShop` in `frontend/app/__tests__/landing.test.tsx`, with the data-loading function mocked (`jest.mock("@/lib/storefront-server", () => ({ loadShopList: jest.fn(async () => ({...})) }))`) rather than hitting the network — component tests assert rendered chrome; the equivalent server-rendered HTML is separately asserted by a Playwright spec that hits the real running stack.

**`next/headers` mocking:** throws under Jest ("`headers` was called outside a request scope" — no request store exists), so tests exercising Server Components that call it must account for this explicitly (see `landing.test.tsx` header comment) — this is treated as a genuine coverage gap the test caught, not just an inconvenience to mock around.

## Playwright (frontend E2E)

**Config:** `frontend/playwright.config.ts`. `testDir: "./e2e"`. `fullyParallel: false` — tests within a file are sequential because they share state (orders, auth). `workers: Number(process.env.PLAYWRIGHT_WORKERS ?? 1)` — **defaults to 1**, not Playwright's own default, because two concurrent browser contexts through the same Docker gateway IP share one server-side rate-limit bucket and exhausted it, surfacing as a checkout that silently never confirmed (#409; measured: 1 worker 3/3 green, 2 workers intermittent).

**Project matrix:** `mobile` and `desktop` projects. Desktop-only specs are excluded from the `mobile` project's enumeration entirely (not enumerated-then-skipped) — a prior version enumerated them and skipped at runtime, which put 2 permanent entries into the suite's skip count for coverage that the desktop project already fully exercises (#420). A skip must mean "nobody checked this"; it must never also mean "not applicable here."

**`baseURL` is a single declared authority** (`use.baseURL`), overridable only via `PLAYWRIGHT_BASE_URL`; specs must navigate with relative paths and must not declare their own default — enforced by `scripts/check-e2e-baseurl-contract.sh`. The config's own comment corrects prior folklore that put the wrong port (`:3100`) into nine files' prose and one file's code (#505) — measured ports on the Compose stack: frontend 3000, core-java 9090, edge-go 8089, mcp-server 9100.

**Report freshness by content digest, not mtime.** A content digest of `frontend/e2e/**` + the config file itself is stamped into the JSON report's `config.metadata.specDigest` (computed by `scripts/e2e-spec-digest.sh`, called from both the config and the gate so there is exactly one hash implementation). This replaced an mtime-based staleness check (`find -newer <report>`), which went VOID after every `git pull`/merge/stash-pop touching a spec **even when the bytes were unchanged**, because git rewrites mtimes on those operations regardless of content.

**Skip budget — `scripts/check-e2e-skip-budget.sh`.** Enforces four things: (S-1) total skipped count ≤ a configured max; (S-2) every skipped test matches a declared ALLOW entry by **title**, not just contributing to a total (a fixed skip and a newly-appeared skip cancel out in a bare count but must not cancel out here); (S-3) every ALLOW entry matches at least one currently-skipped test (a stale exemption fails the gate, so exemptions are retired by the gate going red, not by someone remembering to check); (S-4) the matcher runs a **self-test in both directions** — a known-present title must match, a constructed-absent one must not — so "all declared" can never be satisfied by a matcher that has silently stopped matching anything. This gate deliberately does not run the suite itself (needs the full Compose stack, unavailable on the per-PR runner); it reads a report produced by `.github/workflows/e2e-nightly.yml`, which does stand up the stack.

## vitest (`mcp-server/`)

**Config:** `mcp-server/vitest.config.ts` — `environment: "node"` (server-side HTTP forwarder, no DOM), `include: ["src/**/*.test.ts"]`.

**Mocking pattern — `vi.hoisted` for shared spies, module mocks with `.js` extension paths (ESM):**
```ts
const { logSpies } = vi.hoisted(() => ({
  logSpies: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn(), fatal: vi.fn(), trace: vi.fn() },
}));
vi.mock("pino", () => ({ default: () => logSpies }));
vi.mock("../core-client.js", () => ({ corePost: vi.fn(), coreGet: vi.fn() }));
```
(`mcp-server/src/tools/create-order.test.ts`) — the mocked import path carries the `.js` extension even though the source file is `.ts`, matching the project's `"type": "module"` + `tsc` output convention.

**What is deliberately NOT mocked:** error-mapping (`toToolError` from `errors.js`) runs for real, so the delegation path from a mocked `corePost` failure through to the actual MCP tool error shape is exercised end to end, not just asserted against a second mock.

**PII-safety is a first-class test assertion, not incidental.** `create-order.test.ts` exists specifically to prove the pino logger spy is never called with request args or response body — order DTOs carry customer PII (T-25-09). This mirrors the project's `client-persisted identity lifecycle`/security cross-cutting contracts: a capability that touches PII gets a dedicated negative test, not just a positive "it forwards the request" test.

**8 test files, 48 blocks** (`docs/metrics.json`): `src/errors.test.ts`, `src/index.test.ts`, `src/core-client.test.ts`, `src/tools/create-customer.test.ts`, `src/tools/list-shops.test.ts`, `src/tools/list-products.test.ts`, `src/tools/create-order.test.ts`, `src/tools/read-orders.test.ts`.

## Executable Quality Gates (`scripts/check-*.sh`)

There are **42** `scripts/check-*.sh` scripts (counted 2026-09-03; 42 on disk and 42 git-tracked, no untracked strays). Every one must either run in a CI workflow or carry a written exemption — enforced by a meta-gate, `scripts/check-gate-enforcement.sh`, itself wired at `.github/workflows/ci-cd.yaml:1184-1185` (and is self-covering: it must reference itself or it fails its own rule).

**Wired directly in `.github/workflows/docs-freshness.yml`:** `check-test-block-counter.sh`, `check-doc-versions.sh`, `check-doc-metrics.sh`, `check-project-version.sh`, `check-geo-attribution.sh`, `check-claims.sh`, `check-changelog-contract.sh`, `check-changelog-cites-pr.sh`, `check-env-example-contract.sh`, `check-handoff-contract.sh`.

**Wired in `.github/workflows/ci-cd.yaml`** (selected, testing/quality-relevant): `check-go-coverage.sh` (:138-139), `check-e2e-typecheck.sh` (:183), `check-test-count-oracle.sh` (jest :221, playwright :229, vitest :802), `check-jacoco-coverage.sh` (:382-383), `check-branch-behind-base.sh` (:701-702), `check-terminal-states.sh`, `check-dependency-horizons.sh`, `check-alert-rules.sh`, `check-doc-citations.sh` (:912-928), `check-no-create-extension.sh` (:941-942), `check-retention-enforcement.sh` (:969-970), `check-pentest-triage.sh` (:987-988), `check-image-supply-chain.sh` (:1005-1006, `--explain` flag), `check-edge-core-contract.sh` (:1039-1040), `check-postgres-major-parity.sh` (:1056-1057), `check-e2e-baseurl-contract.sh` (:1083-1084), `check-playwright-mobile-contract.sh` (:1088-1089), `check-no-measured-placeholders.sh` (:1093-1094), `check-ssr-coverage-contract.sh` (:1130-1131), `check-layout-width-contract.sh` (:1166-1167), `check-gate-enforcement.sh` itself (:1184-1185), `check-keycloak-logout-uri.sh` (:1579, :1769).

**Wired in `.github/workflows/e2e-nightly.yml`:** `check-openapi-snapshot-fresh.sh` (:285 — deliberately NOT in `gate-enforcement.conf`'s runtime-exemption table, because this workflow brings up the full Compose stack with `--build` and waits on a live health endpoint, so a real runtime exists to curl against — "wiring beats exempting whenever a real runtime is available"), `check-keycloak-logout-uri.sh` (:298), `check-e2e-skip-budget.sh` (:362).

**Deliberately exempted (declared, not silently absent) — `scripts/gates/gate-enforcement.conf`:** `check-runtime-freshness.sh`, `check-container-config-drift.sh`, `check-infra-exposure.sh` (only assertion A is static; B/C need a live stack), `check-alert-mute.sh`, `check-live-shop-coordinates.sh`, `check-media-content-types.sh` — each entry states in prose *why* a GitHub-hosted runner can only ever VOID on that check (no running containers, no live Grafana, no seeded DB, no MinIO bucket to exec into). The bar for an entry is explicitly not "inconvenient in CI" — it is "would only ever exit 2 there."

**The meta-gate's own history is the point:** measured 2026-08-05, of the (then) 24 `check-*.sh` scripts, six had zero CI references — three legitimately runtime-only, three (`check-e2e-baseurl-contract.sh`, `check-playwright-mobile-contract.sh`, `check-no-measured-placeholders.sh`) simply forgotten, each written *because* a specific defect shipped and each incapable of firing on the PR that would have caught a regression of that exact defect. "Remember to wire new gates into CI" as a written instruction did not survive; `check-gate-enforcement.sh` does.

## The Project's Proof Standard: Show the Fail Direction

The codebase treats **"a check has only ever been observed passing" as equivalent to "unverified"** — this shows up structurally, not just as a written rule:

- **`scripts/check-e2e-skip-budget.sh` S-4** — a literal, executed self-test of its own title-matcher: run it against a known-present skip title (must match) and a constructed-absent one (must not match), on every invocation, so the matcher cannot silently stop matching anything while the gate keeps reporting green.
- **`scripts/check-go-coverage.sh`** — structurally validates the coverage profile (mode line + ≥1 data line) *before* trusting any percentage from it, because an empty or `mode: set`-only profile returns `rc=0` and a plausible-looking `0.0%` — indistinguishable from a real, passing zero-coverage measurement without the structural pre-check.
- **`scripts/check-jacoco-coverage.sh`** — checks that both `test.exec` and `integrationTest.exec` exist and are non-empty *before* reading any CSV, because Gradle's `JacocoReport onlyIf` silently skips report generation on missing execution data, producing a green build with no report rather than an error.
- **Coverage floors across all three languages (JaCoCo, Go, Jest) are floors that were themselves observed red once before being trusted** — each threshold's origin comment states it was raised above the measured baseline and confirmed to fail there first, per this project's `CLAUDE.md` cross-cutting "Falsifiable evidence" contract (§6a: "Every acceptance criterion must be shown to FAIL before it is trusted").
- **`RlsContractTest`** is a schema-walk (queries `pg_class` structurally) rather than a hardcoded positive list of tables — a hardcoded list can silently miss a new table; the schema walk cannot, and a test proving this class of check would need to add a table without RLS and watch the sweep name it (the documented pattern for validating any allow/deny-list style gate in this codebase, per the project's "deny-list guards fail open" institutional lesson).
- **`scripts/check-doc-metrics.sh` M-1`** requires its extraction pattern to match at least once in the target doc — a zero-match rule would trivially "pass" if someone deleted the sentence it checks, which is the classic vacuous-assertion shape (`== 0` already true before the change) the project explicitly calls out project-wide.

---

*Testing analysis: 2026-09-03*
