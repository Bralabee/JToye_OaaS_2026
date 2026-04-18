# Coding Conventions

**Analysis Date:** 2026-04-18

## Naming Patterns

**Files (Frontend / TypeScript):**
- Page routes: `page.tsx` (Next.js app-router convention) — e.g. `frontend/app/shop/[shopSlug]/page.tsx`
- Route handlers: `route.ts` — e.g. `frontend/app/api/customer-auth/route.ts`
- Components: PascalCase — e.g. `CartProvider.tsx`, `SafeImage.tsx`, `RequireCustomerAuth.tsx`
- Utilities / helpers: kebab-case filenames, camelCase exports — e.g. `api-client.ts`, `customer-auth.ts`, `use-toast.ts`
- Hooks: `use-<name>.ts` files exporting `use<Name>()` functions — e.g. `use-stomp.ts` → `useStomp()`
- Test files: co-located in `__tests__/` directories with `*.test.ts` / `*.test.tsx` suffix
- E2E specs: `*.spec.ts` under `frontend/e2e/`

**Files (Backend Java):**
- Entity classes: PascalCase — `Shop.java`, `Product.java`, `Order.java`
- Service classes: `<Entity>Service.java` — `ShopService.java`, `OrderService.java`, `PublicStorefrontService.java`
- Controller classes: `<Entity>Controller.java` — `ShopController.java`, `PublicStorefrontController.java`
- Repository interfaces: `<Entity>Repository.java` — `ShopRepository.java`
- DTO records / classes: `<Entity>Dto.java`; request bodies: `<Action><Entity>Request.java` (e.g. `CreateShopRequest`)
- Mapper interfaces: `<Entity>Mapper.java` annotated `@Mapper(componentModel = "spring")` (MapStruct)
- Exception classes: `<Reason>Exception.java` under `uk.jtoye.core.exception/`
- Test classes: `<Class>Test.java` for unit, `<Class>IntegrationTest.java` for Testcontainers-tagged
- Config: `<Feature>Config.java` under feature package or `uk.jtoye.core.config/`

**Files (Go):**
- Package layout: `edge-go/internal/<domain>/` (one directory per bounded context — `middleware`, `core`, `ratelimit`, `whatsapp`)
- Cmd layout: `edge-go/cmd/edge/main.go` is the entrypoint
- Test files: `<module>_test.go` sibling to source (e.g. `jwt_test.go`, `client_test.go`)

**Functions / Methods:**
- TypeScript: camelCase — `addItem()`, `updateQuantity()`, `getCustomerSession()`
- Java: camelCase — `getShopById()`, `createShop()`, `transitionOrder()`
- Go: PascalCase for exported identifiers, camelCase for unexported — `SearchProducts()`, `CreateOrder()`, `ForwardWebhook()`, `extractBearerToken()`

**Variables:**
- TypeScript: camelCase — `itemCount`, `totalPennies`, `shopSlug`
- Java: camelCase — `tenantId`, `productId`, `isPublished`
- Database columns: snake_case — `created_at`, `delivery_fee_pennies`, `opening_hours`

**Types / Interfaces:**
- TypeScript: PascalCase — `CartItem`, `CartContextValue`, `SafeImageProps`, `RequireCustomerAuthProps`
- Java: PascalCase; DTO suffix `Dto` (`ShopDto`, `OrderDto`)
- Go: PascalCase — `CreateOrderRequest`, `ProductSearchResult`

**Constants:**
- TypeScript: UPPER_SNAKE_CASE — `TOAST_LIMIT`, `TOAST_REMOVE_DELAY`
- Java: UPPER_SNAKE_CASE `static final`
- Spring cache keys: string literals in annotations — `@Cacheable(value = "shops")`, `@CacheEvict(value = "products", allEntries = true)`

## Code Style

**Formatting:**
- Frontend: ESLint (`frontend/.eslintrc.json` extends `next/core-web-vitals` + `next/typescript`)
- Backend: Spring Boot / Gradle default, 4-space indentation, no dedicated Checkstyle config
- Go: `gofmt` (tabs, standard layout)

**Linting:**
- Frontend: `npm run lint` (Next.js ESLint)
- Backend: Gradle compile-time checks + Spring Boot conventions
- Secrets scan: `scripts/pre-commit-gitleaks.sh` (new v2.1) + `.github/workflows/gitleaks.yml`

**Indentation:**
- TypeScript / JavaScript: 2 spaces
- Java: 4 spaces
- Go: tabs

## Import Organization

**TypeScript / React (observed order):**
1. React / Next.js framework: `import { useState } from "react"`, `import { useRouter } from "next/navigation"`
2. External libraries: `import axios from "axios"`, `import { signIn } from "next-auth/react"`
3. UI primitives: `import { Button } from "@/components/ui/button"`, `import { Store } from "lucide-react"`
4. Internal components / hooks: `import { CartProvider } from "@/components/storefront/cart-provider"`
5. Internal utilities / types: `import apiClient from "@/lib/api-client"`, `import type { CartItem } from "@/types/cart"`

**Path Aliases:**
- `@/*` → `frontend/*` (tsconfig `paths`). Used uniformly: `@/app`, `@/components`, `@/lib`, `@/hooks`, `@/types`.

**Java (observed order):**
1. JDK: `java.time.*`, `java.util.*`
2. Jakarta / Spring: `jakarta.persistence.*`, `org.springframework.*`
3. Project: `uk.jtoye.core.*`
4. Static imports (tests only): `static org.mockito.Mockito.*`, `static org.assertj.core.api.Assertions.*`

**Go:**
- Standard library first, then third-party (`github.com/...`), then internal (`github.com/jtoye/edge-go/internal/...`), separated by blank lines (goimports enforced).

## Error Handling

**Frontend Patterns:**
- Try / catch around all async network calls
- Axios interceptors in `frontend/lib/api-client.ts`:
  - Request interceptor attaches bearer token from NextAuth session
  - Response interceptor: 401 → redirect to `/auth/signin`; other errors propagated to caller
- Toast notifications via `useToast()` for user-visible failures
- **Customer-auth guard (v2.1):** `frontend/components/storefront/require-customer-auth.tsx` wraps storefront routes that require a logged-in customer; shows sign-in CTA instead of the protected UI when `getCustomerSession()` resolves null. Used by cart / checkout / orders pages.

**Backend Patterns (Java):**
- Custom exception hierarchy under `uk.jtoye.core.exception/`:
  - `ResourceNotFoundException` → 404
  - `InvalidStateTransitionException` → 400
  - `ForbiddenException` → 403
  - `ConflictException` → 409
- Global handler: `@RestControllerAdvice GlobalExceptionHandler` with `@ExceptionHandler` per type; returns RFC 7807 `ProblemDetail` (`title`, `detail`, `type`, `status`, per-field errors for validation)
- Validation: `@Valid` on `@RequestBody` triggers `MethodArgumentNotValidException` → 400 with field map
- Security: `AuthenticationException` → 401, `AccessDeniedException` → 403, `DataIntegrityViolationException` → 409
- Services throw, controllers never catch; exceptions bubble to the advice

**Go Patterns:**
- Error wrapping: `fmt.Errorf("forward to core: %w", err)` preserves chain for `errors.Is` / `errors.As`
- HTTP status inspection: `if resp.StatusCode >= 400 { return nil, fmt.Errorf(...) }`
- Circuit breaker: `c.breaker.Execute(func() (interface{}, error) { ... })` wraps remote calls (`sony/gobreaker`)
- Bearer extraction: `extractBearerToken(authHeader)` returns `(token, error)` — never panics
- Errors returned upward; logging done by caller via `zap` with contextual fields

## Logging

**Frameworks:**
- Frontend: `console.log/error` for dev only; production telemetry via backend
- Java: SLF4J + Logback (Spring Boot default) — `private static final Logger log = LoggerFactory.getLogger(X.class);`
- Go: `go.uber.org/zap` — structured, field-based (`logger.Info("msg", zap.String("tenant_id", tid))`)

**Java Patterns:**
```java
log.debug("Looking up shop {} for tenant {}", shopId, tenantId);
log.info("Created shop {} for tenant {}", shop.getId(), tenantId);
log.warn("Rate limit exceeded for tenant {}", tenantId);
log.error("Failed to publish order event", ex);
```

**When to Log:**
- Service layer — entry / exit of business operations
- State transitions (order status changes, payment confirmations)
- Security events (auth failures, rate limits, RLS denials) — WARN or higher
- Caught exceptions before rethrow at boundary layers

**Log Levels:**
- DEBUG: intermediate flow, parameter snapshots
- INFO: create / update / delete, state changes
- WARN: recoverable issues, deprecations, rate-limit hits
- ERROR: exceptions, integration failures

## Comments

**Frontend:**
- JSDoc comment block on exported components / utilities describing purpose (see `safe-image.tsx`, `require-customer-auth.tsx`)
- Inline comments reserved for non-obvious business rules (slug generation, penny-based currency math)
- Workaround markers: `// TODO(v2.2):` or `// HACK:` with owner/context

**Java:**
- Controllers: OpenAPI annotations (`@Operation(summary = ...)`, `@ApiResponse`) replace Javadoc
- Services: brief Javadoc on public methods — "what + why", not "how"
- Exceptions: one-line Javadoc stating when thrown and the resulting HTTP status
- Avoid restating field names in getters / setters

**Go:**
- Doc comments on every exported identifier start with the identifier name: `// SearchProducts queries the core API for products matching q.`

## Function Design

**Size:**
- Complex business logic: < 50 lines
- Small helpers: < 10 lines
- Controllers: 5–15 lines, delegate to service

**Parameters:**
- TypeScript: destructured object for component props / hooks — `function Component({ shopSlug, children }: Props)`
- Java: explicit parameters (DTOs, ids); constructor injection for services
- Go: explicit parameters, `error` as last return value

**Return Values:**
- React components: JSX; hooks: tuple or object of state + callbacks
- Java services: `DTO` or `Optional<DTO>`; throw rather than return null
- Go: `(result, error)` convention; never panic on expected errors

## Module Design

**Exports:**
- Frontend: named exports for components and utilities; default export reserved for Next.js pages / layouts
- Java: public class per file; package-private helpers
- Go: capitalised identifier = exported; keep package surface small

**Barrel files:**
- Rare. `frontend/components/ui/` components export individually; no `index.ts` re-exports.

**Package Organization:**
- Frontend: `app/` (routes), `components/{ui,dashboard,storefront}/`, `lib/`, `hooks/`, `types/`
- Backend: `uk.jtoye.core.<domain>/` — e.g. `shop/`, `order/`, `storefront/`, `payment/`, `security/`, `config/`
- Go: `internal/<domain>/` — one domain per dir

## Specific Patterns

**Type Safety:**
- Frontend: TypeScript strict mode; `zod` schemas for runtime validation of inbound data
- Backend: Jakarta Bean Validation (`@Valid`, `@NotNull`, `@Email`) on DTOs
- Go: explicit structs, no `interface{}` unless strictly necessary

**Null / Optional Handling:**
- TypeScript: optional chaining (`?.`), nullish coalescing (`??`); avoid `!` non-null assertions
- Java: `Optional<T>` on repository reads, guard clauses at method entry
- Go: `nil` checks before dereference; use `errors.New` or wrapped errors instead of sentinel nils

**Immutability:**
- React: spread / map / filter for state updates (see `CartProvider` reducer)
- Java: setters used inside services for JPA-managed entities; DTOs are mutable POJOs but treated immutably in mapping layer
- Functional style (stream / map / filter / reduce) preferred for collections

**Dependency Injection:**
- Frontend: React Context (`CartContext`, `ToastContext`) for cross-cutting state; hooks for local
- Backend: Spring constructor injection (`@RequiredArgsConstructor` via Lombok) — never field injection
- Go: manual wiring in `cmd/edge/main.go`; dependencies passed into constructors explicitly

**Tenant / Auth Context:**
- Java: `TenantContext.set(tenantId)` populated by `JwtTenantFilter` per request; services read via `TenantContext.get()`; cleared in `finally`
- Frontend: `NextAuth` session for staff (`/dashboard`) and custom `customerLogin()` / `getCustomerSession()` for storefront customers
- Go: `JWTMiddleware` extracts bearer, validates against Keycloak JWKS, attaches claims to `gin.Context`

**Mapping:**
- MapStruct interfaces (`@Mapper(componentModel = "spring")`) with abstract methods; implementations generated at compile time into `build/generated/sources/annotationProcessor/`

**Transactions:**
- `@Transactional` at the service layer (class-level read-only default; write methods annotated individually)
- Never on controllers; never on repositories

**Caching:**
- Spring Cache (`@Cacheable`, `@CacheEvict`) with Redis backend
- `TenantAwareCacheKeyGenerator` prepends `tenantId` to every cache key to prevent cross-tenant bleed

---

*Convention analysis: 2026-04-18*
