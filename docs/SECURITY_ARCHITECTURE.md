# Security Architecture - J'Toye OaaS

**Version:** 0.7.2
**Last Updated:** 2026-01-06
**Status:** Production Ready

---

## Overview

J'Toye OaaS implements a defense-in-depth security architecture with **PostgreSQL Row-Level Security (RLS)** as the foundational layer for multi-tenant data isolation. This document describes the critical security components and configuration requirements.

---

## Multi-Tenant Isolation Strategy

### Row-Level Security (RLS)

**Why RLS?**
- **Database-enforced isolation**: Cannot be bypassed by application bugs
- **Defense in depth**: Works even if application code has vulnerabilities
- **Automatic filtering**: No manual `WHERE tenant_id = ?` needed in queries
- **UK GDPR compliance**: Strong data isolation required by law

**How It Works:**
1. Application connects with non-superuser account (`jtoye_app`)
2. Before each transaction, application executes: `SET LOCAL app.current_tenant_id = '<tenant-uuid>'`
3. RLS policies automatically filter all queries to match `current_tenant_id()`
4. PostgreSQL enforces isolation at the database kernel level

---

## CRITICAL: Database User Configuration

### ⚠️ NEVER Use Superuser Account

**PostgreSQL superusers BYPASS all RLS policies.**

This means:
- ❌ Multi-tenant isolation will NOT work
- ❌ Any tenant can see ALL tenants' data
- ❌ Critical GDPR breach
- ❌ Complete security failure

### ✅ Correct Configuration

**Application MUST use:** `jtoye_app` (non-superuser)

**Configuration Files:**
```yaml
# docker-compose.full-stack.yml
DB_USER: jtoye_app  # ✅ Correct

# core-java/.env
DB_USER=jtoye_app  # ✅ Correct

# core-java/src/main/resources/application.yml
username: ${DB_USER:jtoye_app}  # ✅ Correct (with fallback)
```

**❌ WRONG Configuration:**
```yaml
DB_USER: jtoye  # ❌ WRONG - This is the superuser!
DB_USER: postgres  # ❌ WRONG - Another superuser!
```

---

## Database Users

### User Roles

| User | Role | Purpose | RLS Enforced? |
|------|------|---------|---------------|
| `jtoye` | Superuser | Database administration, migrations | ❌ NO |
| `jtoye_app` | Application user | Runtime queries | ✅ YES |

### User Permissions

**jtoye_app has:**
- `SELECT`, `INSERT`, `UPDATE`, `DELETE` on tenant-scoped tables
- `EXECUTE` on required functions
- **NO** superuser privileges
- **NO** ability to bypass RLS

**Creation:**
```sql
CREATE USER jtoye_app WITH PASSWORD 'secret';
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO jtoye_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO jtoye_app;
```

---

## RLS Policies

### Tenant-Scoped Tables

All tables with tenant data have RLS enabled:

| Table | RLS Enabled | Policy Count | Enforcement | Standardized (V15) |
|-------|-------------|--------------|-------------|--------------------|
| `shops` | ✅ Yes | 1 | FORCED | ✅ Yes |
| `products` | ✅ Yes | 1 | FORCED | ✅ Yes |
| `orders` | ✅ Yes | 4 | NORMAL | ✅ Yes (V15) |
| `order_items` | ✅ Yes | 4 | NORMAL | ✅ Yes (V15) |
| `customers` | ✅ Yes | 4 | FORCED | ✅ Yes (V14) |
| `financial_transactions` | ✅ Yes | 1 | FORCED | ✅ Yes |

### Standardized RLS Policy Pattern

**All tables now use the standardized pattern (as of V15):**

```sql
-- Standard policy pattern using current_tenant_id() function
CREATE POLICY table_name_select_policy ON table_name
    FOR SELECT
    USING (tenant_id = current_tenant_id());

CREATE POLICY table_name_insert_policy ON table_name
    FOR INSERT
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY table_name_update_policy ON table_name
    FOR UPDATE
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY table_name_delete_policy ON table_name
    FOR DELETE
    USING (tenant_id = current_tenant_id());

-- Forced RLS (even table owner cannot bypass)
ALTER TABLE table_name FORCE ROW LEVEL SECURITY;
```

**Benefits of Standardized Pattern:**
- ✅ Consistent UUID comparison (no type casting)
- ✅ Centralized logic in `current_tenant_id()` function
- ✅ Easier to maintain and audit
- ✅ Better query planner optimization
- ✅ Clear migration history (V14 customers, V15 orders/order_items)

### Required Function

```sql
-- Returns current tenant ID from session variable
CREATE OR REPLACE FUNCTION current_tenant_id()
RETURNS UUID AS $$
BEGIN
  RETURN NULLIF(current_setting('app.current_tenant_id', true), '')::uuid;
END;
$$ LANGUAGE plpgsql STABLE;
```

---

## Application Security Flow

### 1. Authentication (Keycloak)

```
User → Keycloak → JWT Token (with tenant_id claim)
```

**JWT Claims:**
- `tenant_id`: UUID of user's tenant
- `sub`: User ID
- `email`: User email
- Standard OAuth2 claims

### 2. Request Authorization

```java
// JwtTenantFilter extracts tenant_id from JWT
@Component
public class JwtTenantFilter extends OncePerRequestFilter {
    protected void doFilterInternal(...) {
        String tenantId = extractTenantFromJwt(request);
        TenantContext.set(UUID.fromString(tenantId));
        // ... continue chain
    }
}
```

### 3. Database Query Execution

```java
// TenantSetLocalAspect runs before @Transactional methods
@Aspect
public class TenantSetLocalAspect {
    @Before("@annotation(Transactional)")
    public void setTenantContext() {
        UUID tenantId = TenantContext.get();
        jdbcTemplate.execute(
            "SET LOCAL app.current_tenant_id = '" + tenantId + "'"
        );
    }
}
```

### ⚠️ CRITICAL: @Transactional Requirement

**ALL controllers that directly access repositories MUST have `@Transactional` annotations.**

**Why This is Critical:**
- Without `@Transactional`, `TenantSetLocalAspect` never runs
- Without the aspect running, `app.current_tenant_id` is never set
- Without tenant context set, RLS policies fail with "violates row-level security policy"
- **Result: Complete security failure for that controller**

**✅ Correct Pattern:**
```java
@RestController
@RequestMapping("/customers")
public class CustomerController {

    @GetMapping
    @Transactional(readOnly = true)  // ✅ REQUIRED for SELECT
    public Page<CustomerDto> getAll(Pageable pageable) {
        return customerRepository.findAll(pageable).map(this::toDto);
    }

    @PostMapping
    @Transactional  // ✅ REQUIRED for INSERT
    public ResponseEntity<CustomerDto> create(@RequestBody CreateRequest req) {
        Customer customer = new Customer();
        customer.setTenantId(TenantContext.get().orElseThrow());
        // ... set fields
        return ResponseEntity.ok(toDto(customerRepository.save(customer)));
    }

    @PutMapping("/{id}")
    @Transactional  // ✅ REQUIRED for UPDATE
    public ResponseEntity<CustomerDto> update(@PathVariable UUID id, @RequestBody UpdateRequest req) {
        // ... update logic
    }

    @DeleteMapping("/{id}")
    @Transactional  // ✅ REQUIRED for DELETE
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        // ... delete logic
    }
}
```

**❌ WRONG - Will Fail:**
```java
@RestController
@RequestMapping("/customers")
public class CustomerController {

    @GetMapping  // ❌ Missing @Transactional
    public Page<CustomerDto> getAll(Pageable pageable) {
        return customerRepository.findAll(pageable).map(this::toDto);
        // ERROR: new row violates row-level security policy
    }
}
```

**Alternative Pattern (Service Layer):**
```java
// Controller delegates to service
@RestController
@RequestMapping("/orders")
public class OrderController {

    @GetMapping
    public Page<OrderDto> getAll(Pageable pageable) {
        return orderService.getAllOrders(pageable);  // Service has @Transactional
    }
}

// Service has class-level @Transactional
@Service
@Transactional  // ✅ All methods are transactional
public class OrderService {

    public Page<OrderDto> getAllOrders(Pageable pageable) {
        // TenantSetLocalAspect runs here because of class-level @Transactional
        return orderRepository.findAll(pageable).map(this::toDto);
    }
}
```

**Controllers with Direct Repository Access Requiring @Transactional:**
- ✅ `CustomerController` - Fixed in v0.7.2
- ✅ `FinancialTransactionController` - Fixed in v0.7.2
- ✅ `ProductController` - Already correct
- ✅ `ShopController` - Already correct
- ✅ `OrderController` - Uses OrderService (which has class-level @Transactional)

### 4. RLS Filtering

```sql
-- Developer writes:
SELECT * FROM shops;

-- PostgreSQL executes (RLS adds WHERE clause automatically):
SELECT * FROM shops WHERE tenant_id = current_tenant_id();
```

---

## Runtime Security Validation

### Startup Checks

The `DatabaseConfigurationValidator` runs on application startup and validates:

1. ✅ Application is NOT using a superuser
2. ✅ RLS is enabled on all tenant-scoped tables
3. ✅ RLS policies exist
4. ✅ `current_tenant_id()` function exists
5. ✅ Application user has correct permissions

**If any check fails, application WILL NOT START.**

### Security Health Endpoint

```bash
GET /health/security

Response:
{
  "username": "jtoye_app",
  "isSuperuser": false,
  "rlsEnabled": true,
  "tablesWithRls": 5,
  "status": "SECURE"
}
```

Use this endpoint to monitor RLS status in production.

---

## Testing RLS

### Integration Tests

All RLS tests MUST run with `jtoye_app` user:

```java
@TestPropertySource(properties = {
    "spring.datasource.username=jtoye_app"
})
class MultiTenantIsolationIntegrationTest {

    @Test
    void shouldEnforceTenantIsolation() {
        // Create data for Tenant A
        TenantContext.set(TENANT_A);
        Shop shopA = shopRepository.save(new Shop(...));

        // Query as Tenant B
        TenantContext.set(TENANT_B);
        List<Shop> shops = shopRepository.findAll();

        // Should NOT see Tenant A's shop
        assertThat(shops).doesNotContain(shopA);
    }
}
```

### Manual Testing

```bash
# Create shops for both tenants
TOKEN_A=$(curl -s -d 'grant_type=password' -d 'client_id=test-client' -d 'username=tenant-a-user' -d 'password=password123' "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

TOKEN_B=$(curl -s -d 'grant_type=password' -d 'client_id=test-client' -d 'username=tenant-b-user' -d 'password=password123' "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

# Tenant A creates shop
curl -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"name":"Tenant A Shop","address":"London"}'

# Tenant B lists shops (should NOT see Tenant A's shop)
curl http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN_B"
```

---

## Common Pitfalls

### ❌ Mistake #1: Using Superuser

**Problem:**
```yaml
DB_USER: jtoye  # Superuser - RLS bypassed!
```

**Solution:**
```yaml
DB_USER: jtoye_app  # Non-superuser - RLS enforced
```

### ❌ Mistake #2: No Tenant Context Set

**Problem:**
```java
// Forgot to set tenant context
shopRepository.findAll();  // Returns empty or fails
```

**Solution:**
```java
TenantContext.set(tenantId);
shopRepository.findAll();  // Returns tenant's shops
```

### ❌ Mistake #3: Testing as Superuser

**Problem:**
```java
// Test database uses postgres user
@SpringBootTest  // RLS not tested!
```

**Solution:**
```java
@TestPropertySource(properties = {
    "spring.datasource.username=jtoye_app"
})
@SpringBootTest  // RLS properly tested
```

---

## Production Deployment Checklist

### Before Deployment

- [ ] Verify `DB_USER=jtoye_app` in ALL environment configs
- [ ] Verify `DatabaseConfigurationValidator` is enabled
- [ ] Run RLS integration tests
- [ ] Check `/health/security` endpoint shows `"status": "SECURE"`
- [ ] Verify application logs show "✅ DATABASE SECURITY VALIDATION PASSED"
- [ ] Test cross-tenant access returns 404 (not other tenant's data)

### Monitoring

- [ ] Monitor `/health/security` endpoint
- [ ] Alert if `isSuperuser: true` ever appears
- [ ] Alert if `rlsEnabled: false`
- [ ] Log all RLS policy violations (shouldn't happen if configured correctly)

### Incident Response

**If RLS is breached:**
1. Immediately stop application
2. Verify database user is `jtoye_app`
3. Check RLS policies are enabled
4. Review audit logs for cross-tenant access
5. Notify affected customers (GDPR requirement)

---

## UK GDPR Compliance

RLS is REQUIRED for GDPR compliance:

### Data Isolation (Article 32)
✅ "Appropriate technical measures to ensure data security"
- RLS provides kernel-level isolation

### Data Breach Prevention (Article 33)
✅ "Measures to prevent unauthorized access"
- RLS prevents application bugs from leaking data

### Right to be Forgotten (Article 17)
✅ "Technical measures to ensure erasure"
- RLS ensures deleted tenant data is inaccessible

---

## References

- [PostgreSQL RLS Documentation](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- [OWASP Multi-Tenancy Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Multitenant_Architecture_Cheat_Sheet.html)
- [UK GDPR Security Requirements](https://ico.org.uk/for-organisations/guide-to-data-protection/guide-to-the-general-data-protection-regulation-gdpr/security/)

---

## Support

For security issues:
- Review this document
- Check `/health/security` endpoint
- Verify database user configuration
- Review application startup logs

For questions: security@jtoye.uk

---

**Security is NOT optional. Multi-tenant isolation is the FOUNDATION of this system.**
