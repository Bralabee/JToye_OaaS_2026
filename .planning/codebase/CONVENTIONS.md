# Coding Conventions

**Analysis Date:** 2026-09-03

This is a REFRESH of a document last written 2026-04-18. The codebase has grown
substantially since (Java tests alone went from ~390 to 1730 methods per
`docs/metrics.json`); this version reflects the tree as of commit `0eed4f66`
on `feature/qa-remediate-20260902`.

## Naming Patterns

**Files (Frontend / TypeScript, `frontend/`):**
- Page routes: `page.tsx` (Next.js App Router) — e.g. `frontend/app/shop/[shopSlug]/page.tsx`
- Route handlers: `route.ts` — e.g. `frontend/app/api/customer-auth/route.ts`
- Components: PascalCase — e.g. `frontend/components/cart/CartProvider.tsx`, `frontend/components/safe-image.tsx` (component name `SafeImage`, filename can be kebab-case even when the export is PascalCase — verify per-directory)
- Utilities/helpers: kebab-case filenames, camelCase exports — e.g. `frontend/lib/api-client.ts`, `frontend/lib/customer-auth.ts`
- Hooks: `use-<name>.ts` files exporting `use<Name>()` — e.g. `frontend/hooks/use-toast.ts` → `useToast()`, `frontend/hooks/use-theme.ts` → `useTheme()`
- Test files: co-located `__tests__/` directories, `*.test.ts`/`*.test.tsx` suffix — e.g. `frontend/app/__tests__/landing.test.tsx`, `frontend/lib/__tests__/delivery-fee.test.tsx`
- E2E specs: `*.spec.ts` under `frontend/e2e/` — e.g. `frontend/e2e/storefront-flows.spec.ts`, `frontend/e2e/csp-no-violations.spec.ts`

**Files (Backend Java, `core-java/src/main/java/uk/jtoye/core/`):**
- Entity classes: PascalCase — `Shop.java`, `Product.java`, `Order.java`
- Service classes: `<Entity>Service.java` — `ShopService.java`, `OrderService.java`
- Controller classes: `<Entity>Controller.java` — `ShopController.java`, `PromotionController.java`
- Repository interfaces: `<Entity>Repository.java` — `ShopRepository.java`
- DTO classes: `<Entity>Dto.java` in a `dto/` subpackage — `core-java/src/main/java/uk/jtoye/core/shop/dto/ShopDto.java`, `dto/CreateShopRequest.java`
- Mapper interfaces: `<Entity>Mapper.java` (MapStruct, `@Mapper(componentModel = "spring")`) — `ShopMapper.java`
- Exception classes: `<Reason>Exception.java`, all under `core-java/src/main/java/uk/jtoye/core/exception/` (domain-specific exceptions may live under a domain's own `exception` subpackage, e.g. `uk.jtoye.core.media.exception.DecompressionBombException`)
- Package markers: `package-info.java` per domain package, annotated `@NonNullApi` (Spring's `org.springframework.lang.NonNullApi`) — e.g. `core-java/src/main/java/uk/jtoye/core/shop/package-info.java`
- Test classes mirror the production class one-to-one: `ShopService.java` → `core-java/src/test/java/uk/jtoye/core/shop/ShopServiceTest.java`

**Files (Go, `edge-go/`):**
- Package structure: `internal/<domain>/` — `internal/auth/`, `internal/core/`, `internal/middleware/`, `internal/whatsapp/`
- Test files: `*_test.go` suffix, co-located with the source — e.g. `internal/whatsapp/parser_test.go`, `cmd/edge/main_test.go`

**Files (MCP server, `mcp-server/src/`):**
- Tool handlers under `src/tools/`, one file per tool — `create-order.ts`, `list-shops.ts`, `read-orders.ts`
- Test files co-located, `*.test.ts` suffix — `src/tools/create-order.test.ts`, `src/core-client.test.ts`, `src/errors.test.ts`

**Functions:**
- TypeScript/JavaScript: camelCase — `addItem()`, `refreshSessionOnce()`
- Java: camelCase — `getShopById()`, `createShop()`
- Go: camelCase exported / lowercase unexported — `NewClient()`, `parseMessage` internals

**Variables:**
- TypeScript: camelCase — `itemCount`, `totalPennies`
- Java: camelCase — `tenantId`, `productId`, `isPublished`
- Database columns: snake_case — `created_at`, `delivery_fee_pennies`

**Types:**
- TypeScript: PascalCase — `CartItem`, `PublicShop`, `SafeImageProps`
- Java: PascalCase for classes/records; DTOs suffixed `Dto` — `ShopDto`, `OrderDto`
- Constants: TypeScript UPPER_SNAKE_CASE (e.g. `MAX_RETRIES`, `RETRY_DELAYS_MS` in `frontend/lib/api-client.ts`); Java `static final` UPPER_SNAKE_CASE

**Java test method naming — verified pattern:**
`test<Method>_<Scenario>` — e.g. `testCreateShop_Success`, `testCreateShop_MissingTenant`, `testGetShopById_NotFound` (see `core-java/src/test/java/uk/jtoye/core/shop/ShopServiceTest.java:163,192,255`). `@DisplayName` is used selectively on classes and complex test methods for human-readable descriptions, not as a substitute for the naming convention.

## Code Style

**Indentation — verified against real files, not assumed:**
- Java: **4 spaces** — verified in `core-java/src/main/java/uk/jtoye/core/shop/ShopService.java:33-40` (`cat -A` shows literal space characters, no tabs)
- TypeScript/JavaScript: **2 spaces** — verified in `frontend/lib/api-client.ts:21-24`
- Go: **tabs** — verified in `edge-go/internal/core/client.go:20-22` (`cat -A` shows `^I` tab markers)

**No repo-wide formatter is enforced for Java or Go.** There is no `.editorconfig`, no `checkstyle.xml`, and no Spotless plugin wired in `core-java/build.gradle.kts` — indentation is convention-only, held by review rather than a tool. Frontend has no `.prettierrc` either; formatting is whatever the ESLint flat config catches plus author discipline.

**Frontend linting:**
- ESLint **9, flat config** at `frontend/eslint.config.mjs`, run as `eslint .` via `npm run lint` (`package.json` script). Next.js 16 removed `next lint`, so there is no Next-managed lint step and no legacy `.eslintrc` — the flat config is the only linter.
- The config spreads `eslint-config-next/core-web-vitals` and `eslint-config-next/typescript` **directly** (`...nextCoreWebVitals`, `...nextTypescript`) — it does **NOT** wrap them in `FlatCompat`. `eslint-config-next@16` ships native flat-config arrays at those subpaths; wrapping them crashes with a circular-structure error (documented in the file's own header comment, confirmed by reading `frontend/eslint.config.mjs:1-13`).
- A dedicated block layers the full `jsx-a11y` `recommended` rule set at `error` (31-02/LGL-02) on top of the six rules Next's own config enables at `warn`. One rule (`jsx-a11y/control-has-associated-label`) is deliberately left off — the file documents a measured false positive at `app/shop/shop-discovery-client.tsx:390` rather than silently omitting it.
- Two scoped overrides: `**/__tests__/**`, `**/*.test.*`, `**/*.spec.*` relax `@typescript-eslint/no-explicit-any`; `jest.config.js`/`jest.setup.js` relax `@typescript-eslint/no-require-imports` (CommonJS, and `jest.setup.js`'s `require` is load-bearing — see Testing patterns).

**Backend:**
- Gradle/Spring Boot standard formatting, 4-space indentation, held by convention.
- `core-java/build.gradle.kts` is heavily commented with measurement-backed rationale for build/test task wiring (see TESTING.md) — this is itself a project convention: non-obvious build decisions carry a dated, measured justification inline rather than being left to institutional memory.

## Import Organization

**TypeScript:**
- `@/` path alias points at the frontend root (`frontend/tsconfig.json`) — used throughout as `@/components/`, `@/lib/`, `@/hooks/`, `@/types/`.
- No enforced import-order rule (no `eslint-plugin-import` ordering block observed in `eslint.config.mjs`) — order is by convention: external packages, then `@/` aliases, then relative imports.

**Java:**
- Package declaration, then imports grouped by nothing more than the IDE default (alphabetical within `import` block, no explicit third-party/first-party separation observed) — e.g. `core-java/src/main/java/uk/jtoye/core/shop/ShopService.java:1-27` mixes `org.springframework.*` and `uk.jtoye.core.*` imports alphabetically, with a blank line before `java.util.*` imports.
- Static imports (`import static ...`) grouped at the end of the import block in test files — see `core-java/src/test/java/uk/jtoye/core/shop/ShopServiceTest.java:29-31`.

**Go:**
- Standard-library imports first, third-party after a blank line — `edge-go/internal/core/client.go:3-14` (`bytes`, `context`, `encoding/json`, `fmt`, `io`, `net/http`, `time`, then `github.com/sony/gobreaker`, `go.uber.org/zap`).

## Error Handling

**Java — RFC 7807 Problem Details, centralized:**
- `core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java` (724 lines) is the single `@RestControllerAdvice` for the whole API. Every domain exception gets its own `@ExceptionHandler` method building a `ProblemDetail` with a `title`, a `type` URI under `https://jtoye.uk/errors/<kind>`, and the appropriate `HttpStatus`.
- Custom exception hierarchy lives entirely under `core-java/src/main/java/uk/jtoye/core/exception/` (17 files) — `ResourceNotFoundException` (404), `InvalidStateTransitionException` (400), `MissingTenantContextException` (500 — a security-configuration fault, not a client error), `ShopAccessDeniedException`, `IdempotencyConflictException`, `InsufficientStockException`, etc. Some domains (e.g. `media`) keep their own `exception` subpackage rather than the shared one — `uk.jtoye.core.media.exception.DecompressionBombException`, `PayloadTooLargeException`.
- **Convention documented inline**: a handler's status choice carries a written rationale when it is non-obvious. Example — `MissingTenantContextException` maps to 500 (not 400) because a missing tenant context indicates the JWT/tenant filter chain failed server-side, not a client mistake; the doc comment at `GlobalExceptionHandler.java:83-90` states this explicitly so a future edit doesn't "fix" it back to 400.
- Generic exceptions (`IllegalArgumentException` → 400, `IllegalStateException` → 400) are caught too, but every new domain fault gets its own typed exception in preference to reusing a generic one, precisely so its status/detail can diverge from the generic default when needed (see the `MissingTenantContextException` and `MisconfiguredPlatformRadiusException` comments contrasting themselves with the generic `IllegalStateException` handler).
- Server-fault exceptions log at ERROR before returning a generic detail to the client — internal specifics never leak into the response body (`GlobalExceptionHandler.java:84-90`, `:99-105`).

**Go — wrapped errors, explicit status checks:**
- `fmt.Errorf("context: %w", err)` preserves the error chain — verified throughout `edge-go/internal/core/client.go` (10 call sites, e.g. `:111,116,126,144`).
- HTTP status is checked explicitly against the numeric code (`httpResp.StatusCode >= 400`, `:134`), not via a status-code library abstraction.
- Circuit breaker (`sony/gobreaker`) wraps outbound calls to Core; on the edge there is **no fallback** — breaker-open or a transport error returns 502 directly to the caller (`edge-go/internal/core/client.go`). The frontend and `mcp-server` bypass the edge entirely and call Core directly, so this 502-only behavior is scoped to edge-routed traffic only.

**TypeScript — Axios interceptors + typed responses:**
- `frontend/lib/api-client.ts` centralizes request/response handling: a request interceptor injects the Bearer token and `X-Tenant-Id`; a response interceptor retries 5xx/network errors (max 2 retries, 250ms/500ms backoff — **4xx is never retried**) and debounces concurrent 401s onto a single `getSession()` refresh promise (module-level `refreshPromise` singleton) rather than stampeding the auth endpoint.
- MCP server (`mcp-server/src/errors.ts`) has its own `toToolError` mapping from Core's RFC 7807 responses to MCP tool errors — exercised for real (not mocked) in tool tests like `mcp-server/src/tools/create-order.test.ts` so the delegation path is proven end to end.

## Logging

**Java:** SLF4J via `LoggerFactory`, one `private static final Logger log` per class (e.g. `ShopService.java:33`, `GlobalExceptionHandler.java:57`).
- DEBUG: method entry, intermediate calculations.
- INFO: business-significant operations (create/update/delete).
- WARN: recoverable issues.
- ERROR: exceptions before rethrow/handling — always paired with a generic client-facing detail when the log carries internals (see Error Handling above).

**Go:** `go.uber.org/zap` structured logging — fields via `zap.String(...)`, e.g. `edge-go/internal/core/client.go` circuit-breaker state-change logging (`logger.Info("Circuit breaker state changed", zap.String(...))`).

**Frontend:** `console.log`/`console.error` in the browser; no structured logging framework. MCP server uses `pino` (`mcp-server/package.json` dependency) — tests explicitly assert PII (order DTOs, customer data) is **never** logged, by hoisting a shared spy over the `pino` mock and asserting on its call args (`mcp-server/src/tools/create-order.test.ts:1-16`, T-25-09).

## Comments

**Convention: dated, measurement-backed rationale for non-obvious decisions.** This is the dominant comment style across the codebase, not just in build scripts. A comment explaining *why* typically cites an issue/PR number, a measured before/after, or a specific defect it prevents recurring — e.g. `ShopService.java`'s `reservedSlugs` field explains the Next.js static-route collision it guards against; `ShopMapper.java`'s `@BeanMapping` comment cites `QA-council BE-02` and the exact defect (`published` NOT NULL violated by a naive partial update).
- JSDoc/TSDoc: function-level doc comments on exported utilities and non-obvious React components, e.g. `frontend/lib/api-client.ts`'s file-level comment enumerating its four hardening behaviors.
- Java: OpenAPI annotations (`@Operation`, `@ApiResponse`) preferred over Javadoc on controller methods; service methods get brief Javadoc; exception classes get a one-line Javadoc naming the resulting HTTP status (`ResourceNotFoundException.java:3-5`).
- Workarounds and known limitations are written down with the reasoning, not just flagged — consistent with the project's `CLAUDE.md` "Proof Standards" doctrine of recording measured evidence rather than assumptions.

## Function Design

- Target: < 50 lines for complex business logic; small utility functions < 10 lines acceptable (per `CLAUDE.md`).
- Controllers: thin delegation to service layer, typically 5-15 lines per endpoint method (`ShopController.java` `list()` is 5 lines).
- Frontend: destructured object parameters for components/hooks; Java constructor/service methods take individual typed parameters (JPA/Spring convention).
- Go: explicit parameters, error as the last return value (`(result, error)` convention throughout `edge-go/internal/core/client.go`).

## Module Design

**Java:** package-per-domain under `core-java/src/main/java/uk/jtoye/core/<domain>/` (e.g. `shop/`, `product/`, `order/`, `media/`, `finance/`), each with its own `package-info.java` marked `@NonNullApi`. Public classes are the domain's API surface; DTOs live in a `dto/` subpackage; some domains keep a private `exception/` subpackage for domain-specific faults.

**Go:** `internal/<domain>/` isolates each concern (`auth`, `core`, `middleware`, `whatsapp`) from external import — enforced by Go's `internal/` visibility rule, not just convention.

**Frontend:** `app/` (routes), `components/`, `lib/`, `hooks/`, `types/` — named exports for components and utilities, default export reserved for Next.js page/layout files (App Router convention).

**MCP server:** `src/tools/` — one file per MCP tool, each exporting its handler and Zod input schema (e.g. `createOrderHandler`, `createOrderInputSchema` from `src/tools/create-order.ts`), imported directly by that tool's co-located test file.

---

*Convention analysis: 2026-09-03*
