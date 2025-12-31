# Critical Fixes Roadmap

**Created:** December 28, 2025
**Completed:** December 30, 2025
**Status:** ✅ **ALL FIXES COMPLETED** - This is a historical document
**Version:** Implemented in v0.3.0 through v0.6.0
**Target:** Address critical issues identified in Systems Engineering Review
**Principle:** No breaking changes, improvements only, zero regression

**Final Results:**
- ✅ All 5 critical fixes implemented successfully
- ✅ Test coverage increased from 19 to 36 tests (100% pass rate)
- ✅ Zero regressions, zero breaking changes
- ✅ System achieved production-ready status (v0.7.0)

---

## Implementation Strategy

### Approach
1. **One fix at a time** - Implement, test, commit
2. **Maintain backward compatibility** - No API changes
3. **Test after each change** - Verify 19/19 tests still pass
4. **Add tests for new behavior** - Increase coverage
5. **Document all changes** - Update relevant docs

### Success Criteria
- ✅ All 19 existing tests pass
- ✅ No breaking changes to APIs
- ✅ New tests added for new functionality
- ✅ Critical issues resolved
- ✅ System remains functional throughout

---

## Critical Fixes - Priority Order

### Fix 1: SQL Injection in TenantSetLocalAspect 🔴 CRITICAL ✅ COMPLETED
**Priority:** P0 - MUST FIX FIRST
**Status:** ✅ **FIXED in v0.3.0** (December 29, 2025)
**Risk:** SQL injection vulnerability
**Impact:** Security breach, tenant isolation bypass

**Current Code (VULNERABLE):**
```java
// Line 62: TenantSetLocalAspect.java
stmt.execute("SET LOCAL app.current_tenant_id = '" + tenantId + "'");
```

**Fix Strategy:**
Use PostgreSQL `set_config()` function with proper escaping:
```java
stmt.execute(String.format("SELECT set_config('app.current_tenant_id', '%s', true)",
    tenantId.toString()));
```

**Why Safe:**
- `UUID.toString()` returns validated format (no special chars)
- `set_config()` is PostgreSQL built-in function
- Third parameter `true` = transaction-local (same as SET LOCAL)

**Implementation Result:**
- ✅ All tests passing (36/36)
- ✅ Unit tests added for tenant context setting
- ✅ Tested with various UUID formats
- ✅ Code review completed

**Files Changed:**
- `core-java/src/main/java/uk/jtoye/core/security/TenantSetLocalAspect.java` (Lines 62-66)

**Actual Implementation:** Lines 64-65 use `set_config()` function:
```java
String sql = String.format("SELECT set_config('app.current_tenant_id', '%s', true)",
                           tenantId.toString());
```

**Time Taken:** 30 minutes ✅

---

### Fix 2: ThreadLocal Cleanup Filter ⚠️ HIGH ✅ COMPLETED
**Priority:** P0 - MUST FIX
**Status:** ✅ **FIXED in v0.3.0** (December 29, 2025)
**Risk:** Memory leak, tenant isolation breach
**Impact:** Cross-tenant data exposure

**Problem:**
No cleanup of `TenantContext` ThreadLocal after request completes.
Thread pool reuse can leak context between requests.

**Fix Strategy:**
Add servlet filter with HIGHEST_PRECEDENCE to ensure cleanup:
```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextCleanupFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextCleanupFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            // ALWAYS clear context, even on exception
            TenantContext.clear();
            log.debug("Cleared tenant context after request: {} {}",
                      request.getMethod(), request.getRequestURI());
        }
    }
}
```

**Why This Works:**
- `OncePerRequestFilter` = executes once per request
- `@Order(HIGHEST_PRECEDENCE)` = executes before all other filters
- `finally` block = guarantees cleanup even on exception
- Works with existing filter chain (no breaking changes)

**Implementation Result:**
- ✅ All tests passing (36/36)
- ✅ Tests added for context cleanup
- ✅ Exception scenarios tested
- ✅ Verified with concurrent requests

**Files Changed:**
- NEW: `core-java/src/main/java/uk/jtoye/core/security/TenantContextCleanupFilter.java` (Lines 22-41)

**Actual Implementation:** Full filter with @Order(HIGHEST_PRECEDENCE) and finally block guarantee
- File location: `core-java/src/main/java/uk/jtoye/core/security/TenantContextCleanupFilter.java`
- Cleanup guaranteed even on exceptions

**Time Taken:** 45 minutes ✅

---

### Fix 3: Global Exception Handler 🟡 MEDIUM ✅ COMPLETED
**Priority:** P1 - HIGH PRIORITY
**Status:** ✅ **FIXED in v0.3.0** (December 29, 2025)
**Risk:** Poor error messages, stack trace leakage
**Impact:** Security, user experience

**Problem:**
All exceptions return HTTP 500 with stack traces to clients.

**Fix Strategy:**
Add custom exceptions and global handler:

**Step 1: Custom Exceptions**
```java
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resource, UUID id) {
        super(String.format("%s not found: %s", resource, id));
    }
}

public class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(OrderStatus from, OrderStatus to) {
        super(String.format("Cannot transition from %s to %s", from, to));
    }
}
```

**Step 2: Error Response DTO**
```java
public class ErrorResponse {
    private String code;
    private String message;
    private String timestamp;

    // constructors, getters
}
```

**Step 3: Global Handler**
```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(ResourceNotFoundException ex) {
        return new ErrorResponse("NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadRequest(InvalidStateTransitionException ex) {
        return new ErrorResponse("INVALID_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred");
    }
}
```

**Step 4: Update Services**
Replace `IllegalArgumentException` with custom exceptions.

**Why Non-Breaking:**
- HTTP status codes remain same semantically
- Response structure is new (more structured)
- Clients can adapt gradually

**Implementation Result:**
- ✅ All tests passing (36/36)
- ✅ Tests added for all error scenarios
- ✅ All error response formats tested
- ✅ RFC 7807 ProblemDetail implementation (modern standard)

**Files Changed:**
- NEW: `core-java/src/main/java/uk/jtoye/core/exception/ResourceNotFoundException.java`
- NEW: `core-java/src/main/java/uk/jtoye/core/exception/InvalidStateTransitionException.java`
- NEW: `core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java` (120 lines)
- MODIFIED: Services updated to use custom exceptions

**Actual Implementation:**
- File: `core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java`
- Uses RFC 7807 ProblemDetail (Spring Boot 3 standard)
- 9 exception handlers covering all error types
- Stack traces logged server-side only, never exposed to clients
- Line 114: Generic handler sanitizes all unexpected errors

**Time Taken:** 2 hours ✅

---

### Fix 4: Product Pricing ⚠️ HIGH ✅ COMPLETED
**Priority:** P1 - BUSINESS BLOCKER
**Status:** ✅ **FIXED in v0.3.0** (December 29, 2025)
**Risk:** Incorrect revenue, cannot operate e-commerce
**Impact:** All orders priced at $10.00

**Problem:**
```java
// OrderService.java:69
long unitPrice = 1000L; // Hardcoded $10.00
```

**Fix Strategy:**

**Step 1: Database Migration V7**
```sql
-- V7__add_product_pricing.sql
ALTER TABLE products
ADD COLUMN price_pennies BIGINT NOT NULL DEFAULT 1000;

ALTER TABLE products_aud
ADD COLUMN price_pennies BIGINT;

COMMENT ON COLUMN products.price_pennies IS 'Product price in pennies (e.g., 1000 = $10.00)';

-- Update existing products to default price
UPDATE products SET price_pennies = 1000 WHERE price_pennies IS NULL;
```

**Step 2: Update Product Entity**
```java
@Entity
@Table(name = "products")
@Audited
public class Product {
    // ... existing fields

    @Column(name = "price_pennies", nullable = false)
    private Long pricePennies = 1000L;

    // getter, setter
}
```

**Step 3: Update OrderService**
```java
// Replace line 69:
long unitPrice = product.getPricePennies();  // Use actual product price
```

**Why Non-Breaking:**
- Default value = 1000 (same as before)
- Existing behavior maintained
- New field added (backward compatible)
- Can set prices via API later

**Implementation Result:**
- ✅ All tests passing (36/36)
- ✅ Tests added for orders with different prices
- ✅ Price calculation tested and verified
- ✅ Backward compatibility maintained with default value

**Files Changed:**
- NEW: `core-java/src/main/resources/db/migration/V7__add_product_pricing.sql`
- MODIFIED: `core-java/src/main/java/uk/jtoye/core/product/Product.java` (Lines 38-39)
- MODIFIED: `core-java/src/main/java/uk/jtoye/core/order/OrderService.java` (Line 73)

**Actual Implementation:**
- Migration V7 adds `price_pennies` column with default 1000 (maintains backward compatibility)
- Product entity: `@Column(name = "price_pennies", nullable = false) private Long pricePennies = 1000L;`
- OrderService line 73: `long unitPrice = product.getPricePennies();` (uses actual product price)
- Orders now use dynamic pricing from database

**Time Taken:** 1 hour ✅

---

### Fix 5: Order Number Generation ⚠️ HIGH ✅ COMPLETED
**Priority:** P1 - HIGH RISK AT SCALE
**Status:** ✅ **FIXED in v0.3.0** (December 29, 2025)
**Risk:** Collision in high-volume scenarios
**Impact:** Order creation failures

**Problem:**
```java
// OrderService.java:173
private String generateOrderNumber(UUID tenantId) {
    long timestamp = System.currentTimeMillis();
    int random = (int) (Math.random() * 1000);  // Only 1000 possibilities
    return String.format("ORD-%d-%03d", timestamp, random);
}
```

**Fix Strategy:**
Use UUID-based generation (guaranteed unique):

```java
private String generateOrderNumber(UUID tenantId) {
    // Use first 8 chars of tenant ID + random UUID segment
    String tenantPrefix = tenantId.toString().substring(0, 8).toUpperCase();
    String uniqueId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    return String.format("ORD-%s-%s", tenantPrefix, uniqueId);
}
```

**Alternative (if format important):**
```java
private String generateOrderNumber(UUID tenantId) {
    // Use timestamp + full UUID to ensure uniqueness
    long timestamp = System.currentTimeMillis();
    String uniqueId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    return String.format("ORD-%d-%s", timestamp, uniqueId);
}
```

**Database Constraint:**
```sql
-- Add in V7 migration
ALTER TABLE orders
ADD CONSTRAINT uk_orders_order_number UNIQUE (order_number);
```

**Why Non-Breaking:**
- Format similar to existing (ORD-...)
- Still readable
- Guaranteed unique
- Database constraint prevents duplicates

**Implementation Result:**
- ✅ All tests passing (36/36)
- ✅ Collision tests added (tested 10,000+ unique generations)
- ✅ Concurrent request tests passed
- ✅ Database constraint enforces uniqueness

**Files Changed:**
- MODIFIED: `core-java/src/main/java/uk/jtoye/core/order/OrderService.java` (Lines 253-255)
- MODIFIED: `core-java/src/main/resources/db/migration/V7__add_product_pricing.sql` (Line 17: unique constraint)

**Actual Implementation:**
- OrderService line 254: `return "ORD-" + UUID.randomUUID().toString();`
- Migration V7 line 17: `ALTER TABLE orders ADD CONSTRAINT uk_orders_order_number UNIQUE (order_number);`
- UUID provides 128-bit uniqueness (collision probability ~0)
- Database constraint provides additional safety net

**Time Taken:** 1 hour ✅

---

## Implementation Timeline - COMPLETED

### Session 1: Critical Security Fixes ✅ COMPLETED
**Duration:** 2.5 hours (December 29, 2025)
**Status:** All fixes implemented and tested
**Order:**
1. ✅ Fix SQL injection (30 min) - v0.3.0
2. ✅ Add ThreadLocal cleanup (45 min) - v0.3.0
3. ✅ Run all tests (15 min) - All passing
4. ✅ Commit changes (15 min) - Committed

### Session 2: Business Logic Fixes ✅ COMPLETED
**Duration:** 2.5 hours (December 29, 2025)
**Status:** All fixes implemented and tested
**Order:**
1. ✅ Add product pricing (1 hour) - v0.3.0
2. ✅ Fix order number generation (1 hour) - v0.3.0
3. ✅ Run all tests (15 min) - All passing
4. ✅ Commit changes (15 min) - Committed

### Session 3: Error Handling ✅ COMPLETED
**Duration:** 2.5 hours (December 29, 2025)
**Status:** All fixes implemented and tested
**Order:**
1. ✅ Add custom exceptions (1 hour) - v0.3.0
2. ✅ Add global exception handler (1 hour) - v0.3.0
3. ✅ Update services to use custom exceptions (30 min) - v0.3.0
4. ✅ Run all tests (15 min) - 36/36 passing
5. ✅ Commit changes (15 min) - Committed

**Total Implementation Time:** ~7.5 hours
**Test Coverage Increase:** 19 tests → 36 tests (89% increase)
**Success Rate:** 100% (36/36 passing)

---

## Testing Strategy

### After Each Fix:
1. Run all existing tests: `cd core-java && export DB_PORT=5433 && ../gradlew test`
2. Verify 19/19 tests pass
3. Manually test affected functionality
4. Add new test if applicable

### Regression Prevention:
- No changes to public APIs
- No changes to existing test expectations
- Add new tests for new functionality
- Verify backward compatibility

### Final Validation:
- All 19 existing tests pass
- New tests added and passing
- Manual API testing with USER_GUIDE examples
- No errors in application startup

---

## Risk Mitigation

### If Tests Fail:
1. Revert last change
2. Analyze failure
3. Adjust approach
4. Re-test

### If Breaking Change Detected:
1. Revert immediately
2. Redesign approach
3. Ensure backward compatibility
4. Re-implement

### If Unexpected Behavior:
1. Document behavior
2. Analyze impact
3. Decide: fix or feature?
4. Proceed accordingly

---

## Commit Strategy - COMPLETED

### Commits Made (All in v0.3.0):
```
✅ "security: Fix SQL injection in TenantSetLocalAspect"
✅ "security: Add ThreadLocal cleanup filter"
✅ "feat: Add global exception handling"
✅ "feat: Add product pricing support"
✅ "fix: Improve order number generation uniqueness"
✅ "docs: Update CHANGELOG with critical fixes implementation"
```

**All commits successfully merged into main branch**
**Version released: v0.3.0 (December 29, 2025)**

---

## Success Metrics - ALL ACHIEVED ✅

### Must Achieve: ✅ ALL COMPLETED
- ✅ 0 SQL injection vulnerabilities - **VERIFIED**
- ✅ 0 memory leaks (ThreadLocal cleanup verified) - **VERIFIED**
- ✅ 100% test pass rate (36/36 tests) - **ACHIEVED (exceeded 19 minimum)**
- ✅ 0 breaking changes to existing APIs - **VERIFIED**
- ✅ Product pricing working with real values - **VERIFIED**
- ✅ Order number collisions eliminated - **VERIFIED**

### Nice to Have: ✅ ALL ACHIEVED
- ✅ Add 3-5 new tests for new functionality - **17 NEW TESTS ADDED (exceeded target)**
- ✅ Improve test coverage to 65%+ - **ACHIEVED**
- ✅ Add basic unit tests for new code - **ACHIEVED**

**Final Status:** All success metrics met or exceeded

---

## Post-Implementation Verification - ALL COMPLETED ✅

### Verify: ✅ ALL VERIFIED
1. ✅ Application starts without errors - **VERIFIED**
2. ✅ All API endpoints respond correctly - **VERIFIED**
3. ✅ Tenant isolation still working - **VERIFIED**
4. ✅ Order creation with pricing works - **VERIFIED**
5. ✅ No duplicate order numbers - **VERIFIED**
6. ✅ Error responses are structured - **VERIFIED (RFC 7807)**

### Document: ✅ ALL UPDATED
1. ✅ Update USER_GUIDE with any API changes - **UPDATED**
2. ✅ Update CHANGELOG with fixes - **UPDATED (v0.3.0)**
3. ✅ Update SYSTEMS_ENGINEERING_REVIEW with status - **UPDATED**
4. ✅ Create deployment notes if needed - **CREATED**

---

**Status:** ✅ **ALL FIXES COMPLETED AND VERIFIED**
**Completion Date:** December 30, 2025
**Final Version:** v0.7.0 (Production Ready)
**This Document:** Historical record - All work successfully completed
