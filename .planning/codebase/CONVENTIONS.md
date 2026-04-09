# Coding Conventions

**Analysis Date:** 2026-04-07

## Naming Patterns

**Files (Frontend/TypeScript):**
- Page routes: `page.tsx` (Next.js convention)
- Components: PascalCase (e.g., `CartProvider.tsx`, `SafeImage.tsx`)
- Utilities/helpers: camelCase (e.g., `api-client.ts`, `use-toast.ts`)
- Test files: co-located with `__tests__` directory or `*.test.tsx` suffix
- Hooks: `use<Name>` pattern (e.g., `useToast()`, `useCart()`)

**Files (Backend Java):**
- Entity classes: PascalCase (e.g., `Shop.java`, `Product.java`, `Order.java`)
- Service classes: `<Entity>Service.java` (e.g., `ShopService.java`)
- Controller classes: `<Entity>Controller.java` (e.g., `ShopController.java`)
- Repository interfaces: `<Entity>Repository.java` (e.g., `ShopRepository.java`)
- DTO classes: `<EntityName>Dto.java` or request `<Action><Entity>Request.java`
- Mapper interfaces: `<Entity>Mapper.java` (MapStruct convention)
- Exception classes: `<Reason>Exception.java` in `exception/` package

**Files (Go):**
- Package structure: `internal/<domain>` layout
- Test files: `*_test.go` suffix (standard Go convention)
- Functions: camelCase (e.g., `SearchProducts()`, `CreateOrder()`)
- Types: PascalCase (e.g., `CreateOrderRequest`, `ProductSearchResult`)

**Functions/Methods:**
- JavaScript/TypeScript: camelCase (e.g., `addItem()`, `removeItem()`, `updateQuantity()`)
- Java: camelCase (e.g., `getShopById()`, `createShop()`, `updateShop()`)
- Go: camelCase exported, lowercase unexported (e.g., `SearchProducts()`, `createRequest()`)

**Variables:**
- TypeScript: camelCase (e.g., `itemCount`, `totalPennies`, `shopSlug`)
- Java: camelCase (e.g., `tenantId`, `productId`, `isPublished`)
- Database columns: snake_case (e.g., `created_at`, `delivery_fee_pennies`, `opening_hours`)

**Types/Interfaces:**
- TypeScript: PascalCase (e.g., `CartItem`, `CartContextValue`, `SafeImageProps`)
- Java: PascalCase for classes/records
- Java DTOs: `<Entity>Dto` (e.g., `ShopDto`, `OrderDto`)

**Constants:**
- TypeScript: UPPER_SNAKE_CASE (e.g., `TOAST_LIMIT = 1`, `TOAST_REMOVE_DELAY = 1000000`)
- Java: UPPER_SNAKE_CASE for static finals
- Cache keys: use annotation values (e.g., `@Cacheable(value = "shops")`)

## Code Style

**Formatting:**
- Frontend: Managed by Next.js built-in linting via ESLint config in `.eslintrc.json`
- Backend: Gradle/Spring Boot standard formatting (4-space indentation)
- Configuration: `.eslintrc.json` extends `next/core-web-vitals` and `next/typescript`

**Linting:**
- Frontend: ESLint with Next.js and TypeScript rules
- Backend: Gradle tasks enforce Spring Boot patterns and conventions

**Indentation:**
- TypeScript/JavaScript: 2 spaces (Next.js default)
- Java: 4 spaces
- Go: tabs (Go standard)

## Import Organization

**TypeScript/React (Observed Pattern):**
1. Next.js/React framework imports: `import { X } from "react"`, `import { useRouter } from "next/navigation"`
2. External library imports: `import axios from "axios"`, `import { signIn } from "next-auth/react"`
3. Radix UI component imports: `import { Button } from "@/components/ui/button"`
4. Lucide icons: `import { Store, ImageIcon } from "lucide-react"`
5. Custom components/hooks: `import { CartProvider } from "@/components/storefront/cart-provider"`
6. Custom utilities: `import apiClient from "@/lib/api-client"`

**Path Aliases:**
- `@/` points to frontend root directory
- Used throughout: `@/components/`, `@/lib/`, `@/hooks/`, `@/types/`

**Java (Observed Pattern):**
1. Standard library imports: `java.time.*`, `java.util.*`
2. Jakarta/Spring framework imports: `jakarta.persistence.*`, `org.springframework.*`
3. Project imports: `uk.jtoye.core.*`
4. Static imports: used sparingly for test assertions (e.g., `static org.mockito.Mockito.*`)

## Error Handling

**Frontend Patterns:**
- Try-catch blocks in async operations
- Axios interceptors for global error handling (see `api-client.ts`)
- 401 responses trigger redirect to `/auth/signin`
- Errors passed to error boundary or logged to console
- Toast notifications for user-facing errors (not yet implemented pattern, but `useToast` hook available)

**Backend Patterns:**
- Custom exception hierarchy: `ResourceNotFoundException`, `InvalidStateTransitionException` in `uk.jtoye.core.exception`
- Global exception handler: `GlobalExceptionHandler` annotated with `@RestControllerAdvice`
- Returns RFC 7807 Problem Detail responses with:
  - HTTP status code
  - Title field (e.g., "Resource Not Found")
  - Detail message (specific error details)
  - Type URI (e.g., "https://jtoye.uk/errors/not-found")
  - Optional properties map for field-level validation errors
- Specific handlers for:
  - `ResourceNotFoundException` → 404 Not Found
  - `InvalidStateTransitionException` → 400 Bad Request
  - `MethodArgumentNotValidException` → 400 with field-level error map
  - `DataIntegrityViolationException` → 409 Conflict (detects duplicate constraints)
  - `AuthenticationException` → 401 Unauthorized
  - `AccessDeniedException` → 403 Forbidden

**Go Patterns:**
- Error wrapping with `fmt.Errorf("context: %w", err)` for error chain preservation
- Status code checks: `if httpResp.StatusCode >= 400`
- Circuit breaker integration: errors passed through `c.breaker.Execute()` wrapper
- Error logging: typically returned to caller, let client decide logging

## Logging

**Framework:**
- Frontend: `console.log()`, `console.error()` (browser console)
- Backend Java: SLF4J with LoggerFactory (configured in Spring Boot)
- Go: `go.uber.org/zap` for structured logging

**Patterns (Java):**
```java
private static final Logger log = LoggerFactory.getLogger(ShopService.class);

log.debug("Debug message: {}", variable);      // Development details
log.info("Info message {} for tenant {}", id, tenantId);  // Business events
log.warn("Warning message");                   // Potential issues
log.error("Error occurred", exception);        // Failures
```

**When to Log:**
- Service layer: entry point of significant operations
- Condition checks: `log.debug("Checking X condition")`
- State changes: `log.info("Created shop {} with ID {} for tenant {}")`
- Errors: caught exceptions before rethrowing or handling

**Log Levels:**
- DEBUG: method entry, intermediate calculations, detailed flow
- INFO: business-significant operations (create, update, delete)
- WARN: recoverable issues, deprecated usage
- ERROR: exceptions, failures that need attention

## Comments

**When to Comment:**
- Complex algorithm logic: explain the "why", not the "what"
- Non-obvious business rules: e.g., slug generation, UUID handling
- Workarounds and known limitations: why a shortcut exists
- Integration points with external systems

**JSDoc/TSDoc (Observed Pattern):**
- Used sparingly but consistently
- Function-level comments for public exports in utilities
- Example from `safe-image.tsx`:
```typescript
/**
 * Image component with error fallback and lazy loading.
 * Shows a placeholder when the image fails to load or src is null.
 */
export function SafeImage({ src, alt, ... }: SafeImageProps) { ... }
```

**Java Javadoc (Observed Pattern):**
- Controller methods: OpenAPI annotations (`@Operation`, `@ApiResponse`) preferred over Javadoc
- Service methods: Brief Javadoc comment explaining purpose
- Exception classes: Single-line Javadoc explaining when thrown and resulting HTTP status

## Function Design

**Size:**
- Target: < 50 lines for complex business logic
- Small utility functions: < 10 lines acceptable
- Controllers: typically 5-15 lines (delegation to service)

**Parameters:**
- Frontend: use destructuring for objects (e.g., `{ shopSlug, children }`)
- Backend: individual parameters for JPA/Spring (entities, DTOs)
- Go: explicit parameters, error as last return value

**Return Values:**
- Frontend: React components return JSX, hooks return state + methods
- Backend: Services return DTOs or Optional<DTO>
- Go: multiple returns with `(result, error)` convention

## Module Design

**Exports:**
- Frontend: Named exports for components, default export for pages
- Backend: Public classes are exported, package-private for internal classes
- Go: Capitalized identifiers are exported, lowercase unexported

**Barrel Files:**
- Not heavily used in this codebase
- React component groups exported individually

**Package Organization:**
- Frontend: `app/` (pages), `components/`, `lib/`, `hooks/`, `types/`
- Backend: `src/main/java/uk/jtoye/core/<domain>/` (feature modules)
- Go: `internal/<domain>/` (isolated by feature)

## Specific Patterns

**Type Safety:**
- Frontend: TypeScript strict mode, interface/type definitions required
- Backend: Gradle type checking, POJO/DTO validation with `@Valid`
- Go: Explicit type declarations, error type checking

**Null/Optional Handling:**
- Frontend TypeScript: Optional chaining (`?.`), nullish coalescing (`??`)
- Backend Java: `Optional<T>`, null checks with guard clauses
- Go: Error-checking pattern, nil checks before dereferencing

**Immutability:**
- Frontend: React uses immutable state updates (spread operator, map/filter)
- Backend: Entity setters used in service layer, DTOs are mutable POJOs
- Functional style preferred in logic implementations (map, filter, reduce)

**Dependency Injection:**
- Frontend: React Context and hooks for shared state
- Backend: Spring dependency injection via constructor injection
- Go: Manual injection, passing dependencies as function arguments

---

*Convention analysis: 2026-04-07*
