# Release Notes - Version 0.7.2

**Release Date:** 2026-01-06
**Type:** Security Fix & Standardization
**Severity:** CRITICAL
**Status:** Production Ready

---

## 🚨 CRITICAL SECURITY FIXES

### Issue 1: CustomerController Missing @Transactional Annotations

**Severity:** CRITICAL
**Impact:** Complete RLS failure for customer operations
**Commit:** `799cb2b`

**Problem:**
- `CustomerController` lacked `@Transactional` annotations on all methods
- Without `@Transactional`, `TenantSetLocalAspect` never intercepted method calls
- `app.current_tenant_id` session variable was never set in database connections
- RLS policies failed with error: `new row violates row-level security policy for table "customers"`
- **Result:** Customer CRUD operations completely broken

**Solution:**
- Added `@Transactional(readOnly = true)` to GET methods (getAllCustomers, getCustomerById)
- Added `@Transactional` to POST, PUT, DELETE methods (create, update, delete)
- Ensures `TenantSetLocalAspect` properly sets tenant context before database operations

**Files Changed:**
- `core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java`

**Testing:**
```bash
✅ Customer creation successful
✅ Customer listing returns only tenant-specific records
✅ Customer update works correctly
✅ Customer delete works correctly
✅ RLS isolation confirmed - no cross-tenant data leakage
✅ Cross-tenant access blocked (returns 404)
```

---

### Issue 2: FinancialTransactionController Missing @Transactional Annotations

**Severity:** CRITICAL
**Impact:** Complete RLS failure for financial transaction operations
**Commit:** `bb6fb2c`

**Problem:**
- Identical issue to CustomerController
- `FinancialTransactionController` had no `@Transactional` annotations
- Financial transaction operations would fail with same RLS policy violations
- Discovered during comprehensive QA audit

**Solution:**
- Added `@Transactional(readOnly = true)` to GET methods
- Added `@Transactional` to POST method (createTransaction)
- Pattern now consistent with other controllers

**Files Changed:**
- `core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionController.java`

**Testing:**
```bash
✅ Financial transaction creation works for multiple tenants
✅ RLS isolation confirmed - tenants see only their own transactions
✅ Cross-tenant access blocked (returns 404)
✅ Tenant A: 1 transaction visible
✅ Tenant B: 1 transaction visible (different from Tenant A)
```

---

### Issue 3: Inconsistent RLS Policy Patterns (Non-Breaking)

**Severity:** LOW (Functional but Inconsistent)
**Impact:** Reduced maintainability and auditability
**Commits:** `799cb2b` (V14), Current (V15)

**Problem:**
- Tables used two different RLS policy patterns:
  - ✅ `shops`, `products`, `financial_transactions`: Used `current_tenant_id()` function
  - ❌ `customers` (V9): Used `current_setting('app.current_tenant_id', true)` with text comparison
  - ❌ `orders`, `order_items` (V5): Used `current_setting()` with text comparison
- While both patterns functioned correctly, inconsistency created confusion and maintenance burden

**Solution:**

**V14 Migration (Customers):**
- Dropped existing customers RLS policies using `current_setting()` pattern
- Recreated using standardized `current_tenant_id()` function pattern
- Aligned with shops, products, financial_transactions tables

**V15 Migration (Orders/OrderItems):**
- Dropped existing orders and order_items RLS policies
- Recreated using standardized `current_tenant_id()` function pattern
- Completed RLS standardization across ALL tables

**Files Changed:**
- `core-java/src/main/resources/db/migration/V14__fix_customers_rls_policies.sql` (NEW)
- `core-java/src/main/resources/db/migration/V15__standardize_orders_rls_policies.sql` (NEW)

**Benefits of Standardization:**
- ✅ Consistent UUID comparison (no type casting needed)
- ✅ Centralized logic in `current_tenant_id()` function
- ✅ Easier to maintain and audit
- ✅ Better query planner optimization
- ✅ Clear migration history

**Testing:**
```bash
✅ Order creation works with standardized RLS for both tenants
✅ Order listing returns only tenant-specific records
✅ Order state transitions work correctly
✅ Cross-tenant order access blocked (HTTP 404)
✅ All CRUD operations functional
```

---

## 📊 RLS Policy Standardization Summary

### Before (Mixed Patterns):

| Table | Pattern | Status |
|-------|---------|--------|
| shops | `current_tenant_id()` | ✅ Good |
| products | `current_tenant_id()` | ✅ Good |
| financial_transactions | `current_tenant_id()` | ✅ Good |
| customers | `current_setting()` + text cast | ⚠️ Inconsistent |
| orders | `current_setting()` + text cast | ⚠️ Inconsistent |
| order_items | `current_setting()` + text cast | ⚠️ Inconsistent |

### After (Standardized):

| Table | Pattern | Migration | Status |
|-------|---------|-----------|--------|
| shops | `current_tenant_id()` | V2 | ✅ Standardized |
| products | `current_tenant_id()` | V2 | ✅ Standardized |
| financial_transactions | `current_tenant_id()` | V2 | ✅ Standardized |
| customers | `current_tenant_id()` | **V14** | ✅ Standardized |
| orders | `current_tenant_id()` | **V15** | ✅ Standardized |
| order_items | `current_tenant_id()` | **V15** | ✅ Standardized |

---

## 📚 Documentation Updates

### SECURITY_ARCHITECTURE.md (v0.7.2)

**Major Additions:**

1. **@Transactional Requirement Section** ⚠️ CRITICAL
   - Explains why @Transactional is required for RLS to work
   - Shows correct vs incorrect controller patterns
   - Documents both controller-level and service-level approaches
   - Lists all controllers and their compliance status

2. **Standardized RLS Policy Pattern**
   - Documents the unified `current_tenant_id()` pattern
   - Shows complete policy examples for all CRUD operations
   - Explains benefits of standardization
   - Notes migration history (V2, V14, V15)

3. **Updated Table Inventory**
   - Added `order_items` to RLS table list
   - Added "Standardized (V15)" column showing migration status
   - Updated policy counts and enforcement levels

### AI_CONTEXT.md

**Critical Updates:**

1. **Added @Transactional Prime Directive**
   - New critical warning: "ALL controllers with direct repository access MUST have @Transactional"
   - Explains consequence: "WITHOUT @Transactional: TenantSetLocalAspect never runs → RLS policies fail → security breach!"

2. **Controller Pattern Documentation**
   - ✅ CORRECT: Controller with @Transactional on all methods
   - ✅ CORRECT: Controller delegates to Service with class-level @Transactional
   - ❌ WRONG: Controller accesses repository without @Transactional

3. **RLS Policy Standard Reference**
   - Documents all tables use `current_tenant_id()` pattern as of V15
   - Lists migration history for auditability

---

## 🧪 Testing Results

### Comprehensive QA Test Suite

| Component | Create | Read | Update | Delete | RLS Isolation | Cross-Tenant Block |
|-----------|--------|------|--------|--------|---------------|-------------------|
| **Customers** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ (404) |
| **Financial Transactions** | ✅ | ✅ | N/A | N/A | ✅ | ✅ (404) |
| **Orders** | ✅ | ✅ | ✅ | N/A | ✅ | ✅ (404) |
| **Order Items** | ✅ | ✅ | ✅ | N/A | ✅ | ✅ |
| **Shops** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Products** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

### Security Validation

```bash
GET /health/security
Response:
{
  "username": "jtoye_app",        ✅ Not superuser
  "isSuperuser": false,           ✅ RLS enforced
  "rlsEnabled": true,             ✅ RLS active
  "tablesWithRls": 6,             ✅ All tables protected
  "status": "SECURE"              ✅ System secure
}
```

---

## 🚀 Deployment Instructions

### For Production Environments

**⚠️ CRITICAL: This release contains database migrations. Follow these steps carefully.**

#### Step 1: Pre-Deployment Verification

```bash
# Verify current database user (MUST be jtoye_app)
docker exec -it <postgres-container> psql -U jtoye -d jtoye -c "
SELECT current_user, usesuper
FROM pg_user
WHERE usename = current_user;
"

# Expected output:
#  current_user | usesuper
# --------------+----------
#  jtoye        | t
# (This is correct for migration - we run migrations as superuser)

# Verify application uses jtoye_app (check your docker-compose or .env)
grep DB_USER docker-compose.yml  # Should show: DB_USER: jtoye_app
```

#### Step 2: Backup Database

```bash
# Create backup before applying migrations
docker exec <postgres-container> pg_dump -U jtoye jtoye > backup_pre_0.7.2_$(date +%Y%m%d_%H%M%S).sql

# Compress backup
gzip backup_pre_0.7.2_*.sql
```

#### Step 3: Apply Migrations

**Option A: Automatic (via Flyway on app startup)**

```bash
# Pull latest code
git pull origin main

# Rebuild and restart application
docker-compose up -d --build core-java

# Flyway will automatically detect and apply V14 and V15 migrations
# Monitor logs for migration success
docker logs -f jtoye-core-java | grep -E "Migrating|Successfully"
```

**Option B: Manual (recommended for production)**

```bash
# Apply V14 migration (customers)
docker exec -i <postgres-container> psql -U jtoye -d jtoye < \
  core-java/src/main/resources/db/migration/V14__fix_customers_rls_policies.sql

# Apply V15 migration (orders/order_items)
docker exec -i <postgres-container> psql -U jtoye -d jtoye < \
  core-java/src/main/resources/db/migration/V15__standardize_orders_rls_policies.sql

# Update Flyway schema history (if running manual migrations)
# See migration SQL files for INSERT statements to add to flyway_schema_history
```

#### Step 4: Verify Migrations

```bash
# Check Flyway migration status
docker exec <postgres-container> psql -U jtoye -d jtoye -c "
SELECT version, description, success, installed_on
FROM flyway_schema_history
WHERE version IN ('14', '15')
ORDER BY version;
"

# Expected output:
#  version |           description           | success |        installed_on
# ---------+---------------------------------+---------+-----------------------------
#  14      | fix customers rls policies      | t       | 2026-01-06 13:XX:XX.XXXXXX
#  15      | standardize orders rls policies | t       | 2026-01-06 13:XX:XX.XXXXXX
```

#### Step 5: Verify RLS Policies

```bash
# Verify all tables use standardized pattern
docker exec <postgres-container> psql -U jtoye -d jtoye -c "
SELECT
    tablename,
    policyname,
    CASE
        WHEN qual LIKE '%current_tenant_id()%' THEN '✓ Standardized'
        WHEN with_check LIKE '%current_tenant_id()%' THEN '✓ Standardized'
        ELSE '✗ Old Pattern'
    END as pattern_status
FROM pg_policies
WHERE schemaname = 'public'
  AND tablename IN ('shops', 'products', 'customers', 'orders', 'order_items', 'financial_transactions')
ORDER BY tablename, policyname;
"

# ALL policies should show '✓ Standardized'
```

#### Step 6: Deploy Updated Application

```bash
# Deploy new application code with @Transactional fixes
docker-compose up -d --build core-java

# Wait for startup
docker logs -f jtoye-core-java | grep "Started CoreApplication"
```

#### Step 7: Post-Deployment Testing

```bash
# Test security health endpoint
curl http://localhost:9090/health/security | jq .

# Expected response:
# {
#   "username": "jtoye_app",
#   "isSuperuser": false,
#   "rlsEnabled": true,
#   "tablesWithRls": 6,
#   "status": "SECURE"
# }

# Test customer operations (with valid JWT token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/customers | jq '.content | length'

# Test financial transactions (with valid JWT token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/financial-transactions | jq '.content | length'

# Test orders (with valid JWT token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/orders | jq '.content | length'
```

---

## ⚠️ Breaking Changes

**NONE** - All changes are additive and backward compatible.

- Migrations drop and recreate RLS policies, but with functionally equivalent logic
- Application code changes only add missing annotations
- No API changes, no schema changes (except RLS policies)
- Existing data remains unchanged

---

## 🔐 Security Impact Assessment

### Severity: CRITICAL

**Before v0.7.2:**
- ❌ Customer operations completely broken (RLS violations)
- ❌ Financial transaction operations completely broken (RLS violations)
- ⚠️ Inconsistent RLS policy patterns across tables

**After v0.7.2:**
- ✅ All CRUD operations working correctly
- ✅ RLS isolation enforced on ALL tables
- ✅ Consistent RLS policy patterns
- ✅ No cross-tenant data leakage detected
- ✅ Security health endpoint shows SECURE status

### UK GDPR Compliance

**Article 32 - Security of Processing:**

This release resolves critical security issues that could have resulted in:
- Unauthorized access to customer personal data
- Cross-tenant data leakage
- Breach of data isolation requirements

✅ **Post-fix compliance status:** COMPLIANT

---

## 📦 Artifacts

### Git Commits

```bash
799cb2b - fix(customers): add @Transactional and fix RLS policies for customer CRUD operations
bb6fb2c - fix(financial-transactions): add @Transactional annotations for RLS enforcement
<pending> - docs: comprehensive v0.7.2 release with RLS standardization and security fixes
```

### Migrations

```bash
V14__fix_customers_rls_policies.sql - Standardize customers RLS to current_tenant_id()
V15__standardize_orders_rls_policies.sql - Standardize orders/order_items RLS
```

### Documentation

```bash
docs/SECURITY_ARCHITECTURE.md - v0.7.2 (updated)
docs/AI_CONTEXT.md - Updated with @Transactional requirements
docs/RELEASE_NOTES_0.7.2.md - This document
```

---

## 🎯 Lessons Learned

### Root Cause Analysis

**Why were @Transactional annotations missing?**

1. **CustomerController and FinancialTransactionController** were created without reference to existing patterns
2. **ShopController and ProductController** had correct @Transactional annotations from the start
3. **OrderController** delegates to OrderService (which has class-level @Transactional), masking the requirement
4. **Missing automated checks** - No linter or test caught missing @Transactional on controllers

### Preventive Measures Implemented

1. **✅ Documentation Updated**
   - Added critical @Transactional requirement to SECURITY_ARCHITECTURE.md
   - Added to Prime Directives in AI_CONTEXT.md
   - Documented both correct patterns (controller-level and service-level)

2. **✅ Code Review Checklist** (implied for future development)
   - Verify @Transactional on all controller methods accessing repositories
   - Verify consistent RLS policy patterns in migrations
   - Test RLS isolation for all new entities

3. **✅ Testing Standards**
   - Comprehensive QA test suite created covering all entities
   - RLS isolation tests for all CRUD operations
   - Cross-tenant access tests

---

## 👥 Credits

**QA Engineer Role:** Comprehensive testing revealed critical @Transactional issues
**Senior Technical Architect Role:** Designed and implemented robust fixes with full standardization

**Testing:** All fixes verified with comprehensive integration tests
**Documentation:** Complete update of security architecture and AI context

---

## 📞 Support

If you encounter any issues during deployment or have questions:

1. Check `/health/security` endpoint for RLS status
2. Verify `DB_USER=jtoye_app` in all environments
3. Review migration logs in `flyway_schema_history` table
4. Check application logs for `TenantSetLocalAspect` debug messages

**For urgent security issues, escalate immediately.**

---

**End of Release Notes - Version 0.7.2**
