# Core Java Module -- Complete Catalog

> **Generated**: 2026-04-01  
> **Module**: core-java (Spring Boot 3.4.2, Java 21)

---

## Build Configuration

- **Spring Boot**: 3.4.2
- **Java**: 21 (Eclipse Temurin)
- **Key Dependencies**: Spring Data JPA, Spring Security (OAuth2 Resource Server), Flyway, Hibernate Envers, MapStruct 1.5.5, Bucket4j 8.10.1, Micrometer (Prometheus + Zipkin), SpringDoc OpenAPI 2.6.0, Lombok, TestContainers
- **Dockerfile**: Multi-stage (temurin:21-jdk-alpine -> temurin:21-jre-alpine), non-root user, G1GC, /actuator/health probe

---

## Package Structure

```
uk.jtoye.core/
├── CoreApplication.java           # Boot entry + root redirect to Swagger
├── audit/
│   ├── AuditService.java          # Envers history queries
│   ├── RevInfo.java               # Custom revision entity (tenant_id, user_id)
│   └── TenantRevisionListener.java # Auto-populates tenant/user on audit events
├── common/
│   ├── CurrentTenant.java         # Utility
│   └── GlobalExceptionHandler.java # RFC 7807 error responses
├── config/
│   ├── CacheConfig.java           # Redis cache definitions + tenant-aware key gen
│   ├── CorsConfig.java            # localhost:3000 CORS
│   ├── DatabaseConfigurationValidator.java # Startup RLS validation
│   ├── EnversConfig.java          # Placeholder
│   ├── OpenApiConfig.java         # Swagger config (disabled in prod)
│   ├── RateLimitConfig.java       # Bucket4j + Redis rate limiting
│   ├── TenantAwareCacheKeyGenerator.java # tenant:{id}:{method}:{params}
│   └── WebConfig.java             # Registers rate limit interceptor
├── controller/
│   └── SecurityHealthController.java # GET /health/security
├── customer/
│   ├── Customer.java              # Entity (name, email, phone, allergenRestrictions)
│   ├── CustomerController.java    # CRUD /customers
│   ├── CustomerMapper.java        # MapStruct mapper
│   ├── CustomerRepository.java    # findByEmail, existsByEmail, findByPhone
│   └── CustomerService.java       # No caching (privacy)
├── exception/
│   ├── ErrorResponse.java         # Serializable DTO
│   ├── InvalidStateTransitionException.java
│   └── ResourceNotFoundException.java
├── finance/
│   ├── FinancialTransaction.java  # Entity (amountPennies, vatRate, reference)
│   ├── FinancialTransactionController.java # Create + Read only
│   ├── FinancialTransactionMapper.java # Computed VAT via expression
│   ├── FinancialTransactionRepository.java # findByReference, findByVatRate
│   ├── FinancialTransactionService.java # Append-only, no update/delete
│   ├── VatRate.java               # ZERO(0%), REDUCED(5%), STANDARD(20%), EXEMPT
│   └── dto/
│       ├── CreateTransactionRequest.java # Record
│       └── FinancialTransactionDto.java  # Record with computed vatAmountPennies
├── order/
│   ├── Order.java                 # Entity (state machine, orderNumber, items)
│   ├── OrderController.java       # CRUD + 6 state transition endpoints
│   ├── OrderEvent.java            # SUBMIT, CONFIRM, START_PREP, MARK_READY, COMPLETE, CANCEL
│   ├── OrderItem.java             # Entity (product ref, price snapshot)
│   ├── OrderMapper.java           # Excludes items intentionally
│   ├── OrderRepository.java       # findByStatus, findByShopId, findByOrderNumber
│   ├── OrderService.java          # Creates items with price lookup, generates order numbers
│   ├── OrderStateMachineConfig.java # Spring StateMachine config
│   ├── OrderStateMachineService.java # Validates and sends events
│   ├── OrderStatus.java           # DRAFT, PENDING, CONFIRMED, PREPARING, READY, COMPLETED, CANCELLED
│   └── dto/
│       ├── CreateOrderRequest.java # shopId + items + customer fields
│       ├── OrderDto.java
│       └── OrderItemRequest.java  # productId + quantity
├── product/
│   ├── Product.java               # Entity (sku, title, ingredientsText, allergenMask, pricePennies)
│   ├── ProductController.java     # CRUD /products
│   ├── ProductMapper.java         # MapStruct mapper
│   ├── ProductRepository.java     # findBySku
│   ├── ProductService.java        # Cached (10m TTL)
│   └── dto/
│       ├── CreateProductRequest.java # Validation: sku, title, ingredients, allergenMask(0-16383), price(0-1B)
│       └── ProductDto.java
├── security/
│   ├── JwtTenantFilter.java       # Extracts tenant from JWT (tenant_id/tenantId/tid)
│   ├── RateLimitInterceptor.java  # Per-tenant rate limiting with X-RateLimit-* headers
│   ├── SecurityConfig.java        # Filter chain, CORS, public/protected endpoints
│   ├── TenantContext.java         # ThreadLocal<UUID> for current tenant
│   ├── TenantContextCleanupFilter.java # HIGHEST_PRECEDENCE cleanup
│   ├── TenantFilter.java          # Dev fallback: X-Tenant-Id header
│   └── TenantSetLocalAspect.java  # AOP: SET LOCAL app.current_tenant_id before @Transactional
├── shop/
│   ├── Shop.java                  # Entity (name, address)
│   ├── ShopController.java        # CRUD /shops
│   ├── ShopMapper.java            # MapStruct mapper
│   ├── ShopRepository.java        # findByName
│   ├── ShopService.java           # Cached (15m TTL)
│   └── dto/
│       ├── CreateShopRequest.java # name(@NotBlank), address(optional)
│       └── ShopDto.java
├── sync/
│   ├── SyncController.java        # POST /sync/batch
│   ├── SyncService.java           # Upsert shops/products, evicts caches
│   └── dto/
│       ├── BatchSyncRequest.java  # tenantId + items(List<Map>)
│       └── BatchSyncResponse.java # status + processedCount
└── tenant/
    ├── DevTenantController.java   # POST /dev/tenants/ensure (non-prod only)
    ├── DevTenantService.java      # Create/find tenant
    └── Tenant.java                # Entity (name, not tenant-scoped)
```

---

## Security Filter Chain Order

1. `TenantContextCleanupFilter` (HIGHEST_PRECEDENCE) -- ensures cleanup after every request
2. `TenantFilter` (before UsernamePasswordAuthenticationFilter) -- X-Tenant-Id header fallback
3. Spring Security JWT validation (BearerTokenAuthenticationFilter)
4. `JwtTenantFilter` (after BearerTokenAuthenticationFilter) -- JWT tenant extraction
5. `RateLimitInterceptor` (via WebConfig HandlerInterceptor) -- per-tenant rate limiting

### Public Endpoints (no auth required)
`/`, `/health`, `/actuator/health`, `/actuator/info`, `/v3/api-docs/**`, `/swagger-ui/**`

---

## Caching Strategy

| Resource | Cached | TTL | Key Pattern | Reason |
|----------|:------:|-----|-------------|--------|
| Products | Yes | 10 min | `tenant:{id}:getProductById:{uuid}` | Stable, read-heavy |
| Shops | Yes | 15 min | `tenant:{id}:getShopById:{uuid}` | Very stable |
| Customers | No | -- | -- | Privacy-sensitive, frequently updated |
| Orders | No | -- | -- | Change frequently via state machine |
| Financial | No | -- | -- | Compliance-sensitive, append-only |

Cache eviction: Write operations + batch sync evict entire cache namespace.

---

## Error Handling (GlobalExceptionHandler)

All responses use RFC 7807 Problem Details format.

| Exception | HTTP Status | Notes |
|-----------|-------------|-------|
| ResourceNotFoundException | 404 | |
| InvalidStateTransitionException | 400 | |
| IllegalArgumentException | 400 | |
| MethodArgumentNotValidException | 400 | Includes field-level errors |
| DataIntegrityViolationException | 409 | Special handling for duplicate SKU/shop name |
| AuthenticationException | 401 | |
| AccessDeniedException | 403 | |
| Generic Exception | 500 | Logged, details not exposed |

---

## Test Suite

### Unit Tests
- AuditServiceTest, ShopServiceTest, ProductServiceTest, CustomerServiceTest
- OrderServiceTest, OrderStateMachineServiceTest, FinancialTransactionServiceTest
- SyncServiceTest, RateLimitInterceptorTest, TenantSetLocalAspectTest

### Integration Tests (TestContainers: PostgreSQL + Redis)
- MultiTenantIsolationIntegrationTest (RLS enforcement between tenants)
- ShopControllerIntegrationTest, ProductControllerTest
- OrderControllerIntegrationTest, CustomerControllerIntegrationTest
- FinancialTransactionControllerIntegrationTest
- SyncControllerIntegrationTest, AuditIntegrationTest

---

## Order Number Format

```
ORD-{first-8-chars-of-tenant-uuid}-{YYYYMMDD}-{random-8-hex}
```

Example: `ORD-a1b2c3d4-20260401-f9e8d7c6`
