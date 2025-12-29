# Critical Fixes Roadmap

**Created:** December 28, 2025
**Target:** Address critical issues identified in Systems Engineering Review
**Principle:** No breaking changes, improvements only, zero regression

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

### Fix 1: SQL Injection in TenantSetLocalAspect 🔴 CRITICAL
**Priority:** P0 - MUST FIX FIRST
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

**Testing:**
- Verify all 19 tests still pass
- Add unit test for tenant context setting
- Test with various UUID formats

**Files Changed:**
- `core-java/src/main/java/uk/jtoye/core/security/TenantSetLocalAspect.java`

**Estimated Time:** 30 minutes

---

### Fix 2: ThreadLocal Cleanup Filter ⚠️ HIGH
**Priority:** P0 - MUST FIX
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

**Testing:**
- Verify all 19 tests still pass
- Add test for context cleanup
- Test exception scenarios

**Files Changed:**
- NEW: `core-java/src/main/java/uk/jtoye/core/security/TenantContextCleanupFilter.java`

**Estimated Time:** 45 minutes

---

### Fix 3: Global Exception Handler 🟡 MEDIUM
**Priority:** P1 - HIGH PRIORITY
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

**Testing:**
- Verify all 19 tests still pass
- Add tests for error scenarios
- Test all error response formats

**Files Changed:**
- NEW: `core-java/src/main/java/uk/jtoye/core/exception/ResourceNotFoundException.java`
- NEW: `core-java/src/main/java/uk/jtoye/core/exception/InvalidStateTransitionException.java`
- NEW: `core-java/src/main/java/uk/jtoye/core/exception/ErrorResponse.java`
- NEW: `core-java/src/main/java/uk/jtoye/core/exception/GlobalExceptionHandler.java`
- MODIFIED: `core-java/src/main/java/uk/jtoye/core/order/OrderService.java`

**Estimated Time:** 2 hours

---

### Fix 4: Product Pricing ⚠️ HIGH
**Priority:** P1 - BUSINESS BLOCKER
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

**Testing:**
- Verify all 19 tests still pass
- Add test for order with different prices
- Test price calculation

**Files Changed:**
- NEW: `core-java/src/main/resources/db/migration/V7__add_product_pricing.sql`
- MODIFIED: `core-java/src/main/java/uk/jtoye/core/product/Product.java`
- MODIFIED: `core-java/src/main/java/uk/jtoye/core/order/OrderService.java`

**Estimated Time:** 1 hour

---

### Fix 5: Order Number Generation ⚠️ HIGH
**Priority:** P1 - HIGH RISK AT SCALE
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

**Testing:**
- Verify all 19 tests still pass
- Add collision test (generate 10,000 numbers)
- Test with concurrent requests

**Files Changed:**
- MODIFIED: `core-java/src/main/java/uk/jtoye/core/order/OrderService.java`
- MODIFIED: `core-java/src/main/resources/db/migration/V7__add_product_pricing.sql` (add constraint)

**Estimated Time:** 1 hour

---

## Implementation Timeline

### Session 1 (Current): Critical Security Fixes
**Duration:** 2-3 hours
**Order:**
1. ✅ Fix SQL injection (30 min)
2. ✅ Add ThreadLocal cleanup (45 min)
3. ✅ Run all tests (15 min)
4. ✅ Commit changes (15 min)

### Session 2: Business Logic Fixes
**Duration:** 2-3 hours
**Order:**
1. ✅ Add product pricing (1 hour)
2. ✅ Fix order number generation (1 hour)
3. ✅ Run all tests (15 min)
4. ✅ Commit changes (15 min)

### Session 3: Error Handling
**Duration:** 2-3 hours
**Order:**
1. ✅ Add custom exceptions (1 hour)
2. ✅ Add global exception handler (1 hour)
3. ✅ Update services to use custom exceptions (30 min)
4. ✅ Run all tests (15 min)
5. ✅ Commit changes (15 min)

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

## Commit Strategy

### Commit After Each Fix:
```
Fix 1: "security: Fix SQL injection in TenantSetLocalAspect"
Fix 2: "security: Add ThreadLocal cleanup filter"
Fix 3: "feat: Add global exception handling"
Fix 4: "feat: Add product pricing support"
Fix 5: "fix: Improve order number generation uniqueness"
```

### Final Commit:
```
"docs: Update CHANGELOG with critical fixes implementation"
```

---

## Success Metrics

### Must Achieve:
- ✅ 0 SQL injection vulnerabilities
- ✅ 0 memory leaks (ThreadLocal cleanup verified)
- ✅ 100% test pass rate (19/19 minimum)
- ✅ 0 breaking changes to existing APIs
- ✅ Product pricing working with real values
- ✅ Order number collisions eliminated

### Nice to Have:
- Add 3-5 new tests for new functionality
- Improve test coverage to 65%+
- Add basic unit tests for new code

---

## Monitoring Post-Implementation

### Verify:
1. Application starts without errors
2. All API endpoints respond correctly
3. Tenant isolation still working
4. Order creation with pricing works
5. No duplicate order numbers
6. Error responses are structured

### Document:
1. Update USER_GUIDE with any API changes
2. Update CHANGELOG with fixes
3. Update SYSTEMS_ENGINEERING_REVIEW with status
4. Create deployment notes if needed

---

**Status:** READY TO IMPLEMENT
**Next Action:** Begin Fix 1 (SQL Injection)
