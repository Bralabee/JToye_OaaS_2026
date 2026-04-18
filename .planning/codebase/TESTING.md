# Testing Patterns

**Analysis Date:** 2026-04-18

## Verified Test Counts (v2.1)

Counted on 2026-04-18 via `find` + `grep`:

| Layer    | Files | Test methods                             | Command                                                                 |
|----------|-------|------------------------------------------|-------------------------------------------------------------------------|
| Java     | 48    | **390** `@Test` methods                  | `grep -rh "@Test" core-java/src/test --include="*.java" \| wc -l`       |
| Jest     | 13    | **76** `it/test` blocks                  | `grep -rE "^\s*(it\|test)\(" frontend/**/*.test.ts{,x} \| wc -l`        |
| Go       | 5     | **50** top-level `func Test*` + 4 subtests = **54 logical** (pkg claim: 57 with table-driven cases) | `grep -rh "^func Test" edge-go --include="*_test.go" \| wc -l` |
| **Total**| **66**| **516 logical test invocations**         |                                                                         |

Per-file Go breakdown: `main_test.go` 18, `client_test.go` 14, `parser_test.go` 12, `jwt_test.go` 5 (+1 subtest), `whatsapp_test.go` 1 (+3 subtests).

The prior project-level claim of "474+ passing (341 Java + 76 Jest + 57 Go)" is now **stale** — Java test count has grown to **390** post-v2.1, Jest is unchanged at 76, Go is **50 top-level / 54 with subtests** (57 includes table-driven iterations executed at runtime, not static `t.Run` calls).

## Test Framework

**Frontend:**
- Runner: Jest 29.7.0
- Config: `frontend/jest.config.js`, setup: `frontend/jest.setup.js`
- Environment: `jest-environment-jsdom`
- Testing Library: `@testing-library/react` 16.x, `@testing-library/jest-dom` 6.x, `@testing-library/user-event` 14.x
- E2E: Playwright 1.59.1 — config `frontend/playwright.config.ts`, specs under `frontend/e2e/`

**Backend (Java):**
- JUnit 5 (`JUnit Jupiter`) via Gradle `useJUnitPlatform()`
- Mocking: Mockito 5.x via `MockitoExtension`
- Slice tests: `@WebMvcTest` + `MockMvc` (controllers)
- Full-context: `@SpringBootTest`
- Integration: Testcontainers 1.21.3 (PostgreSQL 15) — gated by `@Tag("testcontainers")` and Gradle `-PincludeIntegration`
- In-memory DB: H2 for fast unit paths where Testcontainers is overkill

**Backend (Go):**
- Standard `testing` package + `net/http/httptest`
- No external mocking framework — interface-based seams with hand-written fakes
- Table-driven tests common in `main_test.go`, `client_test.go`, `parser_test.go`

**Run Commands:**

Frontend:
```bash
cd frontend
npm test                       # Jest unit
npm run test:watch             # watch mode
npm run test:coverage          # coverage in coverage/
npx playwright test            # all Playwright specs
npx playwright test kitchen-flow.spec.ts
RELAY_E2E=true npx playwright test stomp-relay.spec.ts   # gated spec
```

Backend Java:
```bash
cd core-java
./gradlew test                          # unit only (testcontainers excluded)
./gradlew test -PincludeIntegration     # unit + Testcontainers
./gradlew test --info --tests ShopServiceTest
```

Backend Go:
```bash
cd edge-go
go test ./...                           # all packages
go test -race ./internal/ratelimit      # race detector
go test -cover ./internal/core
```

## Test File Organization

**Frontend:**
- Component / page tests co-located under `__tests__/` next to the code:
  - `frontend/app/dashboard/__tests__/page.test.tsx`
  - `frontend/app/dashboard/kitchen/__tests__/page.test.tsx`
  - `frontend/app/dashboard/products/__tests__/page.test.tsx`
  - `frontend/app/auth/signin/__tests__/page.test.tsx`
  - `frontend/app/api/customer-auth/__tests__/route.test.ts`
  - `frontend/components/dashboard/__tests__/dashboard-shell.test.tsx`
  - `frontend/components/storefront/__tests__/cart-provider.test.tsx`
  - `frontend/hooks/__tests__/use-stomp.test.ts`
  - `frontend/lib/__tests__/api-client.test.ts`
  - `frontend/lib/__tests__/api-client-interceptors.test.ts`
  - `frontend/types/__tests__/api.test.ts`
- Cross-cutting / storefront suites under top-level `frontend/__tests__/`:
  - `frontend/__tests__/shop/cart.test.tsx` (v2.1)
  - `frontend/__tests__/shop/orders-filter.test.tsx` (v2.1)
- E2E specs: `frontend/e2e/{storefront-flows,kitchen-flow,stomp-relay}.spec.ts`

**Backend (Java):**
- Mirrors `src/main/java/` layout under `src/test/java/uk/jtoye/core/<domain>/`
- v2.1 additions worth noting:
  - `storefront/PublicStorefrontControllerTest.java` — `@WebMvcTest` slice, 11 `@Test`s (the 4 MockMvc flows plus edge cases)
  - `storefront/PublicStorefrontServiceTest.java`
  - `websocket/WebSocketConfigTest.java` — 5 `@Test`s
  - `config/BusinessMetricsServiceTest.java`, `ScheduledCleanupServiceTest.java`, `TenantAwareCacheKeyGeneratorTest.java`, `TenantCacheEvictorTest.java`
- Naming: `<Class>Test.java` (unit), `<Class>IntegrationTest.java` (Testcontainers)

**Go:**
- Sibling `*_test.go` files in the same package:
  - `edge-go/cmd/edge/main_test.go` — rate limiter + `extractBearerToken` + router wiring
  - `edge-go/cmd/edge/whatsapp_test.go` — `ForwardWebhook` + verification flow
  - `edge-go/internal/core/client_test.go` — `SearchProducts`, `CreateOrder`, circuit-breaker fallback
  - `edge-go/internal/middleware/jwt_test.go`
  - `edge-go/internal/whatsapp/parser_test.go`

## Per-Layer Test Strategy

**Java — unit (service layer):**
- `@ExtendWith(MockitoExtension.class)` + `@Mock` repositories / mappers + `@InjectMocks` service
- `TenantContext.set(randomUUID())` in `@BeforeEach`; cleared in `@AfterEach`
- Reflection helper `setField()` for JPA auto-generated ids / timestamps
- AAA (Arrange-Act-Assert) comment markers consistent across the suite

**Java — controller slice:**
- `@WebMvcTest(Controller.class)` with `@AutoConfigureMockMvc(addFilters = false)` to bypass security when testing routing + JSON contract
- Example: `PublicStorefrontControllerTest` — `mockMvc.perform(get("/public/shops/{slug}", slug))` + `jsonPath("$.name").value("Test Shop")`

**Java — integration (Testcontainers):**
- `@Tag("testcontainers")` + `@SpringBootTest` + `@Testcontainers` with a shared Postgres 15 container
- Gated out of the default test task; run in CI via `-PincludeIntegration`
- Verifies real RLS policies, real Flyway migrations, real transactional behaviour
- Examples: `MultiTenantIsolationIntegrationTest`, `AuditIntegrationTest`, `SyncControllerIntegrationTest`

**Jest — component:**
- `render(<Component />)` from `@testing-library/react`, assertions via `screen.getByRole` / `getByText`
- `userEvent.setup()` for interactions; prefer over `fireEvent`
- Global mocks in `jest.setup.js`: `next-auth/react`, `next/navigation`, `framer-motion`, `ResizeObserver`, `recharts`
- `beforeEach(jest.clearAllMocks)` at the top of every `describe`

**Jest — v2.1 additions:**
- `cart.test.tsx` — exercises `CartProvider` reducer, `localStorage` persistence, add / remove / quantity math in pennies
- `orders-filter.test.tsx` — verifies `/public/orders?email=` filter UI (note: underlying endpoint flagged for enumeration risk — see below)
- `use-stomp.test.ts` — covers STOMP client hook connect / subscribe / reconnect behaviour introduced with the broker relay

**Go:**
- Table-driven style for parser + rate-limiter edge cases
- `httptest.NewServer` as a stand-in for the Core API; verifies request shape, headers, and response handling
- Circuit breaker (`sony/gobreaker`) forced open in `client_test.go` to assert fallback path

**Playwright E2E:**
- `storefront-flows.spec.ts` — browse shop → add to cart → customer sign-in → checkout → order confirmation
- `kitchen-flow.spec.ts` — KDS dashboard receives WebSocket order updates
- `stomp-relay.spec.ts` — STOMP broker relay happy path; **gated on `RELAY_E2E=true`** to avoid running when RabbitMQ isn't up

## Mocking

**Jest:**
- Global: `jest.setup.js` mocks NextAuth, Next.js navigation, `framer-motion`, `recharts`, `global.ResizeObserver`
- Local: `jest.mock('@/lib/api-client')` + `const mockedApi = apiClient as jest.Mocked<typeof apiClient>`
- Response shaping via `mockImplementation(url => url === '/shops' ? {...} : {...})`

**Mockito:**
- `@Mock` + `@InjectMocks`; `lenient().when().thenAnswer()` for mappers stubbed across many tests
- `ArgumentCaptor<T>` for verifying payloads passed to repositories
- `verify(repo, times(1)).saveAndFlush(any())` after action

**Go:**
- Interface seams + `httptest.NewServer` — no mocking DSL
- Logger: `zap.NewNop()` in tests to suppress output

## Fixtures & Factories

- Frontend: mocks inline per test or in a `defaultMock(url)` helper returning shaped `Promise.resolve({ data })` objects
- Java: fixtures built in `@BeforeEach`; `setField(obj, "id", uuid)` helper via reflection for JPA-managed fields
- Factory classes intentionally not used — shared fixtures live in test base classes only when they cross 3+ suites

## Coverage

**Frontend:**
- `jest.config.js` `collectCoverageFrom`: `app/**/*.{ts,tsx}`, `components/**/*`, `lib/**/*`, `types/**/*`
- Excluded: `*.d.ts`, `node_modules`, `.next`
- Not gated in CI; `npm run test:coverage` produces `frontend/coverage/lcov-report/index.html`

**Backend:**
- No coverage gate currently; Jacoco can be wired via Gradle plugin if required
- Focus remains service + controller + integration layers; entities and `@Configuration` are excluded from targeted coverage

## Common Patterns

**Async (Jest):**
```typescript
await waitFor(() => {
  expect(screen.getByText('Dashboard')).toBeInTheDocument()
})
```

**Error assertion (JUnit):**
```java
when(shopRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
assertThrows(ResourceNotFoundException.class, () -> shopService.getShopById(UUID.randomUUID()));
```

**MockMvc slice:**
```java
mockMvc.perform(get("/public/shops/{slug}", "test-shop"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.name").value("Test Shop"));
```

**Go table-driven:**
```go
for _, tc := range []struct{ header, want string; err bool }{
    {"Bearer abc", "abc", false},
    {"", "", true},
    {"Basic xyz", "", true},
} {
    got, err := extractBearerToken(tc.header)
    if (err != nil) != tc.err || got != tc.want { t.Errorf(...) }
}
```

## CI / CD

- `.github/workflows/ci-cd.yaml` — lint + Java tests + Jest + Go tests + Docker image build + deploy gate
- `.github/workflows/gitleaks.yml` (new v2.1) — secret scanning on every push / PR
- Pre-commit: `scripts/pre-commit-gitleaks.sh` for local secret prevention
- Environment: `DOCKER_API_VERSION=1.45` exported for Testcontainers compatibility

## Smoke Tests (shell, non-unit)

New in v2.1, run after `docker compose up` to validate the live stack:

- `infra/monitoring/scripts/smoke-test-alertmanager.sh` — verifies Alertmanager is reachable, configuration loaded, routes resolve, a synthetic alert reaches the expected receiver
- `scripts/smoke-test-stomp-relay.sh` — asserts RabbitMQ STOMP relay accepts connect, subscribe, publish; verifies a message round-trips to a subscribed client
- `scripts/smoke-test.sh` — legacy end-to-end stack check (health, Swagger, one auth flow)

## Known Testing Gaps (from recent audits)

- **Stock race condition** — concurrent `POST /public/orders` against the same product currently passes because no test exercises two simultaneous reservations under optimistic / pessimistic locking. No Testcontainers test covers the race; only single-threaded service tests exist. **Risk: oversell.**
- **`/public/orders?email=` enumeration** — `orders-filter.test.tsx` asserts the UI but not the security boundary. An authenticated customer can currently list orders by other customers' emails; there is no integration test asserting that a customer session is scoped to its own email. **Risk: PII disclosure.**
- **No auth test for `RequireCustomerAuth`** — the new storefront guard has no unit test for the unauthenticated branch (sign-in prompt rendering); only the authenticated happy path is implicitly covered via `cart.test.tsx`.
- **WebSocket disconnect / reconnect path** — `use-stomp.test.ts` covers connect + subscribe; forced-disconnect and backoff-reconnect edge cases are not asserted.
- **Go rate-limiter under load** — unit tests verify the bucket math but no benchmark / contention test exists.

## Known Testing Considerations

**Frontend:**
- `recharts` requires mocking (jsdom lacks SVG layout)
- `ResizeObserver` must be polyfilled globally
- `framer-motion` animations mocked to avoid async timers leaking between tests
- `next/navigation` (`useRouter`, `usePathname`, `useSearchParams`) mocked globally

**Backend:**
- JPA auto-generated ids / timestamps require the `setField()` reflection helper
- `TenantContext` must be populated before any service call — missing tenant throws `IllegalStateException`
- `lenient()` stubbing used where mappers are invoked across most tests but verified in few
- Testcontainers image pull gated by `DOCKER_API_VERSION=1.45` on some Linux hosts

**Go:**
- Race detector (`-race`) should be used for any new goroutine-bearing code
- `httptest` servers must be `defer srv.Close()`'d — leaks mask flaky port allocation

---

*Testing analysis: 2026-04-18*
