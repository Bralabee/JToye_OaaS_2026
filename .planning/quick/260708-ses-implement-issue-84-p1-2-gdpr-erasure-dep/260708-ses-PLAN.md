---
phase: quick-260708-ses
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - core-java/src/main/resources/db/migration/V42__gdpr_erasure_completeness.sql
  - core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecord.java
  - core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecordRepository.java
  - core-java/src/main/java/uk/jtoye/core/customer/CustomerRepository.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderRepository.java
  - core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
  - core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java
  - core-java/src/test/java/uk/jtoye/core/gdpr/GdprServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/gdpr/GdprControllerTest.java
  - core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureIntegrationTest.java
  - docs/metrics.json
autonomous: true
requirements:
  - "ISSUE-84-P1-2"          # GDPR erasure completeness (guest orders, _aud PII, S3 photos, durable record)

must_haves:
  truths:
    - "Erasing a customer also anonymises guest orders (customer_id NULL) that share the subject's email"
    - "Pre-erasure PII in orders_aud and customers_aud is scrubbed for the erased subject"
    - "Review photos in S3/MinIO are physically deleted, not just URL-nulled"
    - "Each erasure persists a durable erasure_records row (no plaintext PII retained)"
    - "An integration test on real Postgres proves guest-order PII reachability + _aud scrub"
  artifacts:
    - path: "core-java/src/main/resources/db/migration/V42__gdpr_erasure_completeness.sql"
      provides: "erasure_records table (tenant-scoped RLS+FORCE) + tenant-scoped UPDATE policies on orders_aud/customers_aud"
      contains: "erasure_records"
    - path: "core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecord.java"
      provides: "Durable erasure record entity"
      contains: "class ErasureRecord"
    - path: "core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecordRepository.java"
      provides: "Persistence for erasure records"
      contains: "ErasureRecordRepository"
    - path: "core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java"
      provides: "Extended erasure: email sweep, S3 delete, _aud scrub, durable record"
      min_lines: 180
    - path: "core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureIntegrationTest.java"
      provides: "Testcontainers proof of guest-PII reachability + _aud scrub"
      contains: "Testcontainers"
  key_links:
    - from: "core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java"
      to: "OrderRepository.findByCustomerEmailOrderByCreatedAtDesc"
      via: "guest-order email sweep"
      pattern: "findByCustomerEmailOrderByCreatedAtDesc"
    - from: "core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java"
      to: "StorageService.delete"
      via: "S3 review-photo cleanup"
      pattern: "storageService\\.delete"
    - from: "core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java"
      to: "orders_aud / customers_aud"
      via: "native tenant-scoped scrub UPDATE"
      pattern: "scrub(Orders|Customer)Audit"
    - from: "core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java"
      to: "erasure_records"
      via: "erasureRecordRepository.save"
      pattern: "erasureRecordRepository\\.save"
---

<objective>
Close GitHub issue #84 [P1-2]: GDPR erasure is cosmetic. Extend the Article-17 erasure
flow so it (1) reaches guest storefront orders that carry PII with no customer_id,
(2) scrubs pre-erasure PII from the Envers customers_aud/orders_aud history for the
subject, (3) physically deletes orphaned review photos from S3/MinIO, and (4) persists a
durable, PII-free erasure record. The admin role gate from issue #83 stays intact.

Purpose: The guest storefront is the primary B2C flow; today its PII (name/email/phone)
is never reached by erasure, and pre-erasure PII survives in the audit history and S3.
This is a UK-GDPR compliance defect (right to erasure not honoured).

Output: V42 migration, ErasureRecord entity+repo, extended GdprService, updated unit tests,
and a Testcontainers integration test proving guest-PII reachability on real Postgres+RLS.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@./CLAUDE.md

<interfaces>
<!-- Contracts the executor needs. Extracted from the codebase — no exploration required. -->

Erasure entrypoint (KEEP the class-level @PreAuthorize("hasRole('admin')") from #83):
  GdprController @RequestMapping("/gdpr/customers") — runtime path /api/v1/gdpr/customers/**
  DELETE /{customerId}/erase -> GdprService.eraseCustomerData(UUID) -> ErasureResponse
  Current ErasureResponse record: (UUID customerId, OffsetDateTime erasedAt, int ordersAnonymised, int reviewsAnonymised)

GdprService current deps (constructor-injected): CustomerRepository, OrderRepository, ReviewRepository.
  Constants: ANONYMISED = "[REDACTED]", ANONYMISED_EMAIL = "redacted@erased.invalid".

Existing repository methods (already present — reuse, do not duplicate):
  OrderRepository.findByCustomerId(UUID) : List<Order>
  OrderRepository.findByCustomerEmailOrderByCreatedAtDesc(String) : List<Order>   <-- guest reachability
  ReviewRepository.findByCustomerEmail(String) : List<Review>
  CustomerRepository.findById(UUID)

Order entity: getId(), getTenantId(), getCustomerId() (nullable for guests),
  get/setCustomerName, get/setCustomerEmail, get/setCustomerPhone, get/setNotes, setUpdatedAt.
Customer entity: getId(), getTenantId(), getEmail(), setName/Email/Phone/Notes/AllergenRestrictions/UpdatedAt.
Review entity (NOT @Audited — there is no reviews_aud): getPhotoUrls():List<String>,
  setPhotoUrls, setCustomerName, setCustomerEmail, setComment.

Guest order creation (the reachability gap):
  PublicStorefrontService.createGuestOrder(..) sets customerName/customerEmail/customerPhone
  with NO customerId (customer_id column is NULL). These rows are invisible to findByCustomerId.

StorageService.delete(String imageUrl) : void  — idempotent, no-ops on blank/external URLs,
  swallows failures with a WARN log. Reuse for review-photo deletion.

Tenancy mechanics (important for native _aud scrub + record insert):
  TenantContext.set(UUID)/get():Optional<UUID>/clear() (uk.jtoye.core.security).
  TenantSetLocalAspect runs @Before on any @Transactional class/method AND on every
  Repository+ / JdbcTemplate call, issuing set_config('app.current_tenant_id', <tenant>, true).
  So inside GdprService's @Transactional flow the tenant GUC is set for repository/native calls.
  RLS helper: current_tenant_id() (V1) reads current_setting('app.current_tenant_id', true) -> uuid.

Audit-table state (verified V4/V5/V9/V11/V35):
  orders_aud PII cols: customer_name, customer_email, customer_phone, notes; scope col tenant_id; PK (id, rev).
  customers_aud PII cols: name, email, phone, notes; scope col tenant_id; PK (id, rev).
  BOTH have FORCE ROW LEVEL SECURITY (V35) and ONLY SELECT + INSERT policies — no UPDATE policy,
  so a scrub UPDATE is DENIED for the NOSUPERUSER app role until V42 adds tenant-scoped UPDATE policies.

Integration-test harness: uk.jtoye.core.testsupport.IntegrationTestSupport
  .registerPostgresTestProperties(registry, postgres) — combine with @ActiveProfiles("test"),
  @Testcontainers, @Tag("testcontainers"), a per-class postgres:15 @Container. Testcontainers
  bootstrap role is SUPERUSER (bypasses even FORCE RLS), so the scrub UPDATE runs in-test without
  the new policy; the policy exists for production correctness. Seed a tenants row before writes.
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: V42 migration + ErasureRecord entity/repo + native _aud scrub queries (contracts)</name>
  <files>core-java/src/main/resources/db/migration/V42__gdpr_erasure_completeness.sql, core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecord.java, core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecordRepository.java, core-java/src/main/java/uk/jtoye/core/customer/CustomerRepository.java, core-java/src/main/java/uk/jtoye/core/order/OrderRepository.java</files>
  <action>
Create migration V42__gdpr_erasure_completeness.sql (next version after V41) with three parts.

(a) CREATE TABLE erasure_records — the durable, PII-free erasure record. Columns: id UUID PRIMARY KEY, tenant_id UUID NOT NULL, subject_customer_id UUID NOT NULL, subject_email_sha256 CHAR(64) (one-way hash only — NEVER store the plaintext email, that would re-introduce the PII we are erasing), orders_anonymised INT NOT NULL DEFAULT 0, reviews_anonymised INT NOT NULL DEFAULT 0, aud_rows_scrubbed INT NOT NULL DEFAULT 0, photos_deleted INT NOT NULL DEFAULT 0, erased_by VARCHAR(255), erased_at TIMESTAMPTZ NOT NULL DEFAULT now(), CONSTRAINT fk_erasure_records_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id). Add CREATE INDEX idx_erasure_records_tenant ON erasure_records(tenant_id). This table is NOT Envers-audited — it is itself the audit record; do NOT add an _aud mirror and do NOT annotate the entity @Audited.

(b) Enable tenant isolation on erasure_records following the established convention (V35 FORCE-RLS pattern + V14 current_tenant_id() policy style): ALTER TABLE erasure_records ENABLE ROW LEVEL SECURITY; ALTER TABLE erasure_records FORCE ROW LEVEL SECURITY; then CREATE POLICY erasure_records_select_policy FOR SELECT USING (tenant_id = current_tenant_id()) and CREATE POLICY erasure_records_insert_policy FOR INSERT WITH CHECK (tenant_id = current_tenant_id()). Wrap each CREATE POLICY in the DO $$ ... IF NOT EXISTS (SELECT 1 FROM pg_policies ...) guard style used by the sibling migrations so re-runs are safe.

(c) Add the MISSING tenant-scoped UPDATE policies that let the scrub actually run under FORCE RLS for the NOSUPERUSER app role: CREATE POLICY orders_aud_update_policy ON orders_aud FOR UPDATE USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id()); and CREATE POLICY customers_aud_update_policy ON customers_aud FOR UPDATE USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id()); — both guarded with the IF NOT EXISTS pg_policies check. Add a SQL comment on each explaining this is the deliberate GDPR Article-17 exception to append-only audit history (targeted PII scrub only; Envers stays enabled).

Create ErasureRecord.java (@Entity, @Table(name="erasure_records")) with JPA-mapped fields matching the columns above; UUID id assigned in the constructor/service (mirror the id-assignment style used by other entities in this codebase — no DB default reliance for id). Provide a convenience all-args constructor plus getters. Do NOT add @Audited.

Create ErasureRecordRepository.java extending JpaRepository&lt;ErasureRecord, UUID&gt;.

Add native @Modifying scrub methods (interface-first contracts the service will call). To OrderRepository add:
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(value = "UPDATE orders_aud SET customer_name = :redacted, customer_email = NULL, customer_phone = NULL, notes = NULL WHERE tenant_id = :tenantId AND (customer_id = :customerId OR customer_email = :email)", nativeQuery = true)
  int scrubOrdersAudit(UUID tenantId, UUID customerId, String email, String redacted);
To CustomerRepository add:
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(value = "UPDATE customers_aud SET name = :redacted, email = NULL, phone = NULL, notes = NULL WHERE tenant_id = :tenantId AND id = :customerId", nativeQuery = true)
  int scrubCustomerAudit(UUID tenantId, UUID customerId, String redacted);
Use @Param on each argument (project uses -parameters? do NOT assume — add explicit @org.springframework.data.repository.query.Param). Explicit tenant_id in the WHERE is mandatory defense-in-depth per the multi-tenancy constraint: native queries on _aud must never rely on RLS alone.
  </action>
  <verify>
    <automated>test -f core-java/src/main/resources/db/migration/V42__gdpr_erasure_completeness.sql && grep -q "erasure_records" core-java/src/main/resources/db/migration/V42__gdpr_erasure_completeness.sql && grep -q "orders_aud_update_policy" core-java/src/main/resources/db/migration/V42__gdpr_erasure_completeness.sql && grep -q "customers_aud_update_policy" core-java/src/main/resources/db/migration/V42__gdpr_erasure_completeness.sql && grep -q "scrubOrdersAudit" core-java/src/main/java/uk/jtoye/core/order/OrderRepository.java && grep -q "scrubCustomerAudit" core-java/src/main/java/uk/jtoye/core/customer/CustomerRepository.java && ./gradlew :core-java:compileJava -q</automated>
  </verify>
  <done>V42 migration creates erasure_records (tenant-scoped, FORCE RLS) and adds UPDATE policies on orders_aud/customers_aud; ErasureRecord entity + repository compile; native scrub methods exist on OrderRepository and CustomerRepository; core-java compiles.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Extend GdprService — guest-order email sweep, S3 photo deletion, _aud scrub, durable record</name>
  <files>core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java, core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java</files>
  <behavior>
    - Guest reachability: erasing customer C (email E) anonymises BOTH customerId-linked orders AND guest orders (customer_id NULL) whose customer_email = E, de-duplicated by order id. ordersAnonymised counts the merged distinct set.
    - S3 cleanup: for every review photo URL, StorageService.delete(url) is invoked exactly once before photoUrls is nulled; photosDeleted counts URLs passed to delete.
    - Audit scrub: scrubOrdersAudit(tenantId, customerId, originalEmail, ANONYMISED) and scrubCustomerAudit(tenantId, customerId, ANONYMISED) are called with the customer's tenantId; aud_rows_scrubbed = sum of returned row counts.
    - Durable record: exactly one ErasureRecord is saved, carrying tenantId, subjectCustomerId, a SHA-256 hex hash of the original email (never the plaintext), the four counts, and erased_by from the security principal (fallback "system" when no authentication is present).
    - 404 unchanged: eraseCustomerData throws ResourceNotFoundException when the customer id is absent.
  </behavior>
  <action>
Extend GdprService.eraseCustomerData(UUID customerId). Add constructor-injected dependencies: StorageService (uk.jtoye.core.storage.StorageService) and ErasureRecordRepository. Capture originalEmail and tenantId = customer.getTenantId() up front (tenantId drives the native scrub WHERE clauses — do NOT rely on TenantContext for the value, though the aspect will have set the GUC for RLS).

Order sweep for guest reachability (per D acceptance criterion): build a de-duplicated map keyed by Order.getId() from findByCustomerId(customerId) UNION findByCustomerEmailOrderByCreatedAtDesc(originalEmail). Anonymise each once (customerName=ANONYMISED, customerEmail=null, customerPhone=null, notes=null, updatedAt=now) and saveAll. ordersAnonymised = distinct count. This is the line that reaches guest storefront orders that today's findByCustomerId-only walk misses.

Review handling: for each review from findByCustomerEmail(originalEmail), if getPhotoUrls() is non-null, iterate and call storageService.delete(url) per URL (increment photosDeleted), then setPhotoUrls(null). Keep the existing PII anonymisation (customerName=ANONYMISED, customerEmail=ANONYMISED_EMAIL, comment=null). saveAll. StorageService.delete is already idempotent and swallows failures, so a missing/external URL does not break erasure.

Audit scrub (run after saving the live entities so the flush ordering is deterministic; @Modifying flushAutomatically already forces the JPA flush first): int audScrubbed = orderRepository.scrubOrdersAudit(tenantId, customerId, originalEmail, ANONYMISED) + customerRepository.scrubCustomerAudit(tenantId, customerId, ANONYMISED). This scrubs the pre-erasure PII rows in orders_aud/customers_aud; post-erasure Envers rows written on flush already hold redacted values.

Durable record: compute subjectEmailSha256 = lowercase hex SHA-256 of originalEmail (use java.security.MessageDigest "SHA-256"; do NOT store plaintext). Resolve erasedBy from SecurityContextHolder.getContext().getAuthentication(): use getName() when present, else "system". Persist one ErasureRecord (new UUID id, tenantId, customerId, subjectEmailSha256, ordersAnonymised, reviewsAnonymised, audScrubbed, photosDeleted, erasedBy, erasedAt=now) via erasureRecordRepository.save. Keep the existing SLF4J log line but add the record id.

Update GdprController.ErasureResponse to carry the new evidence: add fields recordId (UUID), guestReach unnecessary — ordersAnonymised now includes guests — instead add auditRowsScrubbed (int) and photosDeleted (int). New record signature: (UUID customerId, OffsetDateTime erasedAt, int ordersAnonymised, int reviewsAnonymised, int auditRowsScrubbed, int photosDeleted, UUID recordId). Have eraseCustomerData return the populated response. Do NOT touch the class-level @PreAuthorize("hasRole('admin')") gate or the DELETE mapping — issue #83's role gate stays exactly as-is.

Do NOT introduce a v1/simplified variant, do NOT defer any of the four acceptance items — all four (guest sweep, _aud scrub, S3 delete, durable record) ship in this task.
  </action>
  <verify>
    <automated>./gradlew :core-java:test --tests "uk.jtoye.core.gdpr.GdprServiceTest" -q</automated>
  </verify>
  <done>GdprService reaches guest orders by email, deletes S3 review photos via StorageService, scrubs orders_aud/customers_aud through the native tenant-scoped queries, persists a PII-free ErasureRecord, and returns the extended ErasureResponse; the #83 admin gate is untouched; GdprServiceTest passes.</done>
</task>

<task type="auto">
  <name>Task 3: Tests (unit updates + Testcontainers guest-PII reachability proof) + docs-freshness sync</name>
  <files>core-java/src/test/java/uk/jtoye/core/gdpr/GdprServiceTest.java, core-java/src/test/java/uk/jtoye/core/gdpr/GdprControllerTest.java, core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureIntegrationTest.java, docs/metrics.json</files>
  <action>
Update GdprServiceTest (Mockito): add @Mock StorageService and @Mock ErasureRecordRepository (the @InjectMocks GdprService now has 5 deps). Stub orderRepository.findByCustomerEmailOrderByCreatedAtDesc and the two scrub methods (return int counts), storageService is void. Extend eraseCustomerData_anonymisesAllPii to: seed a guest Order (no customerId, customerEmail = subject email) returned by findByCustomerEmailOrderByCreatedAtDesc and a customer-linked Order via findByCustomerId, assert the merged distinct set is anonymised (guest order name/email/phone redacted); seed a review with two photo URLs and verify storageService.delete is called for each; verify scrubOrdersAudit and scrubCustomerAudit are invoked with the customer's tenantId and original email; verify erasureRecordRepository.save is called exactly once and captured record carries the four counts and a non-null 64-char email hash (and NOT the plaintext email). Give the test customer a tenantId. Keep the existing no-orders and not-found tests green (stub the new mocks to return empty/0).

Update GdprControllerTest: fix both ErasureResponse constructions to the new 7-arg signature and add jsonPath assertions for $.auditRowsScrubbed, $.photosDeleted, and $.recordId on the erase test. Leave the export tests unchanged.

Create GdprErasureIntegrationTest (@SpringBootTest, @Testcontainers, @ActiveProfiles("test"), @Tag("testcontainers"), per-class postgres:15 @Container, @DynamicPropertySource -> IntegrationTestSupport.registerPostgresTestProperties). Autowire GdprService, OrderRepository, JdbcTemplate (and EntityManager if needed to flush). In @BeforeEach seed a tenants row for TENANT_A. The reachability proof:
  1. TenantContext.set(TENANT_A) so TenantSetLocalAspect emits the tenant GUC for the seeded writes and the erasure transaction; clear it in @AfterEach.
  2. Create a Customer (tenantId=TENANT_A, email="subject@example.com") via CustomerRepository and capture its id.
  3. Create a GUEST Order via OrderRepository with customerId = NULL, customerEmail = "subject@example.com", customerName = "Guest Subject", customerPhone = "+447700900111", tenantId = TENANT_A (this insert makes Envers write an orders_aud row holding the PII — the pre-erasure history row).
  4. Call gdprService.eraseCustomerData(customerId).
  5. Assert the guest order LIVE row (re-fetched) has customerName = "[REDACTED]", customerEmail null, customerPhone null — proves guest PII reachability (the core acceptance criterion).
  6. Assert via jdbcTemplate that no orders_aud row for TENANT_A still contains customer_email='subject@example.com' or customer_name='Guest Subject', and no customers_aud row still contains email='subject@example.com' — proves _aud scrub.
  7. Assert jdbcTemplate.queryForObject("SELECT count(*) FROM erasure_records WHERE subject_customer_id = ?", ...) == 1 and its subject_email_sha256 is non-null — proves the durable record.
Model tenant seeding and JWT/RLS setup on RoleBasedAccessIntegrationTest and the existing cross-tenant integration tests. Testcontainers runs as SUPERUSER so the scrub UPDATE and record INSERT succeed regardless of the new policy; the policy is for production. Do NOT weaken or bypass RoleBasedAccessIntegrationTest (it asserts admin-role access to /gdpr/** and must stay green).

After all tests pass, regenerate the docs manifest: run scripts/docs-freshness.sh --write to update docs/metrics.json (baseline after #83 is 735 logical invocations; the new unit-test methods + the new integration-test class will raise java_test_methods / total_logical_invocations). Do not hand-edit the JSON.
  </action>
  <verify>
    <automated>./gradlew :core-java:test --tests "uk.jtoye.core.gdpr.*" --tests "uk.jtoye.core.security.RoleBasedAccessIntegrationTest" -q && ./gradlew :core-java:integrationTest --tests "uk.jtoye.core.gdpr.GdprErasureIntegrationTest" -q && bash scripts/docs-freshness.sh --write && bash scripts/docs-freshness.sh</automated>
  </verify>
  <done>Unit tests (GdprServiceTest, GdprControllerTest) pass with the new deps/DTO; GdprErasureIntegrationTest proves on real Postgres that a guest order's PII is anonymised, orders_aud/customers_aud PII is scrubbed, and one erasure_records row is persisted; RoleBasedAccessIntegrationTest still green; docs/metrics.json regenerated and the docs-freshness gate passes.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| admin client → /api/v1/gdpr/customers/{id}/erase | Authenticated admin (JWT, #83 role gate) triggers irreversible anonymisation scoped to their tenant |
| GdprService → orders_aud / customers_aud (native UPDATE) | App writes directly to append-only audit history — bypasses JPA/Envers, must be tenant-scoped |
| GdprService → S3/MinIO (object delete) | App issues destructive deletes against external object storage |
| erasure_records row | New durable audit artifact; must not itself re-store subject PII |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-ses-01 | Information Disclosure | Guest-order PII never erased (customer_id NULL) | mitigate | Sweep orders by subject email (findByCustomerEmailOrderByCreatedAtDesc) merged with findByCustomerId; integration test asserts guest row anonymised |
| T-ses-02 | Information Disclosure | Pre-erasure PII persists in orders_aud/customers_aud | mitigate | Tenant-scoped native scrub UPDATE (explicit tenant_id in WHERE) + V42 UPDATE policies; integration test asserts no plaintext PII remains in _aud |
| T-ses-03 | Elevation of Privilege | Cross-tenant erasure (admin of tenant A scrubs tenant B _aud) | mitigate | tenantId from customer.getTenantId() in every native WHERE; V42 UPDATE policies gate on current_tenant_id(); FORCE RLS on _aud tables |
| T-ses-04 | Information Disclosure | erasure_records re-stores the erased email | mitigate | Store only SHA-256 hex of email (subject_email_sha256); never persist plaintext; unit test asserts hash ≠ plaintext |
| T-ses-05 | Repudiation | No durable proof erasure occurred (log-only today) | mitigate | Persist erasure_records row with counts + erased_by principal + timestamp |
| T-ses-06 | Denial of Service | S3 delete failure aborts the whole erasure | accept | StorageService.delete already swallows failures with WARN and no-ops on blank/external URLs; DB anonymisation is the compliance-critical path and proceeds regardless |
| T-ses-07 | Tampering | Disabling Envers to erase history | mitigate | Envers stays enabled; only a targeted PII-column UPDATE is issued (deliberate Article-17 exception documented in V42 comments) |

No package-manager installs in this plan — Package Legitimacy Gate not applicable.
</threat_model>

<verification>
- ./gradlew :core-java:compileJava passes (V42 + entity/repo + native queries).
- ./gradlew :core-java:test --tests "uk.jtoye.core.gdpr.*" green (unit).
- ./gradlew :core-java:integrationTest green — GdprErasureIntegrationTest proves guest-PII reachability + _aud scrub + durable record; RoleBasedAccessIntegrationTest (from #83) stays green.
- bash scripts/docs-freshness.sh passes (docs/metrics.json regenerated, no drift; total_logical_invocations rises above the 735 baseline).
- Manual grep confirms class-level @PreAuthorize("hasRole('admin')") on GdprController is unchanged.
</verification>

<success_criteria>
- Erasure anonymises guest-order PII (customer_id NULL, email-matched), not just customerId-linked rows. [AC1]
- Envers customers_aud/orders_aud PII is scrubbed for the erased subject via tenant-scoped native UPDATE. [AC2]
- Orphaned S3/MinIO review photos are physically deleted through StorageService. [AC3]
- A durable, PII-free erasure_records row is persisted per erasure; erasure remains admin role-gated (#83 intact). [AC4]
- Integration test on real Postgres proves guest-PII reachability. [AC5]
- docs/metrics.json regenerated; docs-freshness CI gate passes; full :core-java:integrationTest green.
</success_criteria>

<output>
Create `.planning/quick/260708-ses-implement-issue-84-p1-2-gdpr-erasure-dep/260708-ses-SUMMARY.md` when done.
</output>
