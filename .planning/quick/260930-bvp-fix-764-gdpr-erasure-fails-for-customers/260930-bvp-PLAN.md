---
phase: quick/260930-bvp
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureReviewRlsIntegrationTest.java
  - core-java/src/main/resources/db/migration/V67__reviews_update_policy_and_erasure_photo_count.sql
  - core-java/src/main/java/uk/jtoye/core/review/ReviewRepository.java
  - core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
  - core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecordRepository.java
  - core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java
  - core-java/src/test/java/uk/jtoye/core/gdpr/GdprServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/gdpr/GdprControllerTest.java
  - core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureIntegrationTest.java
  - docs/metrics.json
  - CLAUDE.md
  - AGENTS.md
  - README.md
  - docs/CHANGELOG.md
autonomous: true
requirements: [QUICK-260930-bvp, ISSUE-764]
branch: fix/764-gdpr-erasure-reviews
base: origin/main @ c5d16ff6 (#763)
worktree: none — SEQUENTIAL in the main checkout /home/sanmi/IdeaProjects/JToye_OaaS_2026

estimate:
  tokens: 200000
  raw_tokens: 200000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "Under a NOSUPERUSER role with FORCE RLS in force, erasing a customer who has a review carrying stored photos SUCCEEDS: the review's customer_name/customer_email/comment/photo_urls are anonymised, exactly one erasure_records row exists for the subject, and the subject's own-tenant photo objects answer 404 to an anonymous GET"
    - "The erasure response's photosDeleted AND the durable erasure_records.photos_deleted (read back in a fresh transaction) both equal the number of objects the store actually removed — an external URL and another tenant's URL on the same review are neither deleted nor counted (WR-02 and D-09 preserved)"
    - "Another tenant's PUBLISHED review written under the same email is untouched by the erasure (name, email, comment, photo_urls unchanged; its photo still answers 200), and reviewsAnonymised counts only the erasing tenant's review — with a recorded precondition proving that review WAS visible to the erasing tenant's RLS session, so the arm can fail"
    - "When the erasure's transaction rolls back, no photo is deleted from storage: the object still answers 200 with its original bytes, the review keeps its PII, and no erasure_records row exists — with a recorded precondition proving the erasure body ran to completion inside the rolled-back transaction"
    - "The new integration test was committed BEFORE any fix and recorded RED on the unfixed tree (verbatim failure per arm); after the fix each of four break arms (no reviews UPDATE policy, email-only lookup, inline photo deletion, default propagation for the count write) turns its named arm RED, and the closing clean run is GREEN with every restored file matching its recorded sha256"
    - "The full core-java unit suite and the full integrationTest suite both actually executed on the final tree (fresh XML reports) with 0 failures and 0 errors"
    - "scripts/docs-freshness.sh and scripts/check-doc-metrics.sh are rc 0 on the final tree and were each observed non-zero before the doc edits; docs/metrics.json reports schema_version 67"
  artifacts:
    - path: core-java/src/main/resources/db/migration/V67__reviews_update_policy_and_erasure_photo_count.sql
      provides: "tenant-scoped UPDATE policy on reviews + write-once tenant-scoped UPDATE policy on erasure_records.photos_deleted"
      contains: "FOR UPDATE"
    - path: core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureReviewRlsIntegrationTest.java
      provides: "NOSUPERUSER + real Azurite proof of the three #764 arms"
      contains: "NOSUPERUSER"
    - path: core-java/src/main/java/uk/jtoye/core/review/ReviewRepository.java
      provides: "tenant-predicated email lookup"
      contains: "findByTenantIdAndCustomerEmail"
    - path: core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
      provides: "post-commit photo deletion with tenant pin, REQUIRES_NEW write-once count, ErasureOutcome"
      contains: "afterCommit"
    - path: core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecordRepository.java
      provides: "write-once photo-count UPDATE"
      contains: "recordPhotosDeleted"
    - path: docs/CHANGELOG.md
      provides: "the #764 entry (heading number appended by the orchestrator after gh pr create)"
  key_links:
    - from: GdprService.eraseCustomerData / exportCustomerData
      to: ReviewRepository.findByTenantIdAndCustomerEmail
      via: "explicit tenant predicate taken from the customer row read under RLS — the SELECT policy alone is cross-tenant for PUBLISHED shops"
      pattern: "findByTenantIdAndCustomerEmail"
    - from: GdprService post-commit hook
      to: StorageService.delete
      via: "TransactionSynchronization.afterCommit with TenantContext pinned to the captured tenant (D-09 guard reads TenantContext)"
      pattern: "registerSynchronization"
    - from: GdprService post-commit hook
      to: ErasureRecordRepository.recordPhotosDeleted
      via: "TransactionTemplate PROPAGATION_REQUIRES_NEW — a default-propagation write inside afterCommit joins the already-committed transaction and is silently lost"
      pattern: "PROPAGATION_REQUIRES_NEW"
    - from: GdprController.eraseData
      to: GdprService.ErasureOutcome.toResponse
      via: "the response is built AFTER the proxied transactional call returns, i.e. after commit and after the photo step ran; ErasureResponse itself is unchanged (no OpenAPI diff)"
      pattern: "toResponse"
---

<objective>
Fix issue #764: GDPR Article-17 erasure fails for any customer who wrote a review, and it destroys that customer's review photos on the way down.

Three defects, one per fix:
1. `reviews` has FORCE RLS and no UPDATE policy, so the anonymising UPDATE matches 0 rows for the application role. Hibernate rejects that and the erasure rolls back. Fix: V67 adds a tenant-scoped UPDATE policy.
2. The review lookup is by email only. `reviews_tenant_read` shows PUBLISHED reviews across tenants, so the loop picks up another tenant's review. Fix: an explicit tenant predicate on BOTH GdprService call sites. The export has the same leak.
3. Review photos are deleted from Blob inside the transaction, before it commits. Fix: delete them only after commit, and keep the WR-02 photo count truthful in both the response and the durable record.

Purpose: a data subject who left a review can actually be erased. A failed erasure destroys nothing. The Article-17 evidence row never claims more than happened.
Output: V67 migration, tenant-scoped repository finder, post-commit photo erasure in GdprService, a NOSUPERUSER + Azurite integration test shown RED then GREEN with break arms, updated unit tests, and green doc gates plus a CHANGELOG entry.
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@CLAUDE.md
@.claude/skills/proof-standards/SKILL.md
@core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
@core-java/src/main/java/uk/jtoye/core/review/ReviewRepository.java

## Standing rules for this run (owner rulings and project contracts; apply to every task)

- **Where and how.** Execute SEQUENTIALLY in the main checkout, on branch `fix/764-gdpr-erasure-reviews`. Do NOT push, open a PR or merge; the orchestrator does that. Do NOT edit `.planning/STATE.md`.
- **Staging.** A second session may drive this checkout. Stage files by explicit path only, never `git add -A` or `git add .`. Read `git diff --cached --name-only` before every commit. Never stage `.gsd/`, `.planning/state.json` or `.planning/quick/260831-jz4-*/evidence/`: they are untracked and not ours.
- **Commit messages.** No AI-attribution lines of any kind: no Co-Authored-By trailer, no session link, no "Generated with" footer. This is the owner ruling of 2026-08-30, it overrides the harness reminder, and `git/.git-hooks/prepare-commit-msg` enforces it. Pass messages through a quoted heredoc into a file with `git commit -F`, then read them back with `git log -1 --format=%B`.
- **Proof standards.** Capture every rc on the same statement as its command (`out=$(cmd); rc=$?`).
  - Use `rg -uu` when a search result is evidence.
  - `grep` is ugrep, so use `grep -F` or `rg -F` for literal braces.
  - Never `cmd | grep -q` under pipefail.
  - A Gradle run counts only if its XML report is newer than a marker file touched before the run. `--rerun` is on every targeted test invocation because an UP-TO-DATE task executes nothing.
  - The live build dir is `core-java/build-local` (`core-java/build.gradle.kts:19`), NOT `core-java/build`.
- **Falsifiability contract (CLAUDE.md).** Record every acceptance check in BOTH directions with real output. If a check cannot fail, say so and replace it with a stronger form. Never report a vacuous pass as satisfied.

## Facts measured at planning time (cite them; re-measure only where a task says to)

- **The #764 defect in GdprService.**
  - `GdprService.java:199` finds reviews by email alone. `:76` does the same for the Article-20 export.
  - `:204-213` calls `storageService.delete(url)` for every photo INSIDE the transaction, BEFORE `:221 reviewRepository.saveAll(reviews)`.
  - The first flush after that is `orderRepository.scrubOrdersAudit`, which is @Modifying(flushAutomatically).
  - `:243-246` writes the ErasureRecord with the synchronously counted `photosDeleted`.
- **The email-only finder.** It is declared at `ReviewRepository.java:21`, the only method taking just the email String. Its only callers are `GdprService.java:76` and `:199`, plus `GdprServiceTest.java:85, 142, 228, 269, 293`. Nothing in `mcp-server` or `frontend` calls it.
- **`reviews` RLS.**
  - FORCE (`V35:83`).
  - `reviews_tenant_read` FOR SELECT is `tenant_id = current_tenant_id() OR EXISTS(published shop)` (`V39:72-80`).
  - `reviews_tenant_write` is FOR INSERT only (`V51:95-108`).
  - No UPDATE or DELETE policy exists in any migration.
- **Reviews are not audited.** `Review.java` carries no `@Audited`, and `rg -uu 'reviews_aud'` over `core-java/src/main/resources/db/migration` matches nothing. So no `_aud` mirror needs a policy, and no Envers INSERT happens on update.
- **The only other reviews writer** is `ReviewService.java:95`, which saves a NEW review (an INSERT). The new UPDATE policy therefore opens exactly the erasure path.
- **`erasure_records`** has FORCE RLS with SELECT and INSERT policies only (`V42:42-68`), so an UPDATE on it matches 0 rows today, the same bug class. Columns: `photos_deleted INT NOT NULL DEFAULT 0` (`V42:32`). The id is app-assigned in the ErasureRecord constructor. `ErasureRecordRepository` has no custom methods.
- **`StorageService.delete(url)` (`:279`)**
  - Refuses any key whose tenant segment differs from `TenantContext`, and fails closed with no context (D-09).
  - Returns true only when this call removed an object (WR-02).
  - `putBytes(key, bytes, contentType)` (`:350`) returns the public URL.
- **`TenantSetLocalAspect.java:43-61`** issues `set_config('app.current_tenant_id', …, true)` from `TenantContext` before every Repository or JdbcTemplate call, but only while an actual transaction is active.
- **afterCommit precedents.**
  - `TenantLifecycleService.java:156-167`: registers only if synchronization is active, and catches Throwable in the hook.
  - `TenantCacheEvictor.java:127-137`: runs inline when no synchronization is active, and captures the tenant before registering.
  - `KeycloakDeprovisionService.java:71-80`: explains why post-commit work must be REQUIRES_NEW.
  - `DsarFanoutWorker.java:203-231`: sets `TenantContext` around `transactionTemplate.execute(...)` and clears it in a `finally` AFTER commit. So a hook registered during the DSAR fan-out still runs with the tenant set.
- **Why the existing suites are green.**
  - `GdprErasureIntegrationTest` runs as the Testcontainers SUPERUSER, which bypasses FORCE RLS (its own Javadoc, `:41-43`). It never inserts a review.
  - `DsarFanoutIntegrationTest` downgrades to NOSUPERUSER (`:145-155`) and passes, which proves every OTHER erasure step works under RLS. It too never inserts a review.
- **Recipes to copy.**
  - Once-only NOSUPERUSER downgrade with a `rolsuper` precondition: `DsarFanoutIntegrationTest.java:120-155`.
  - Real Azurite, `putBytes`, and an anonymous GET verdict: `ProductImageCrossTenantBlobDeleteIntegrationTest.java:65-135`, plus `AzuriteTestSupport`.
  - Shop and order seed column lists that satisfy every NOT NULL: `ReviewsRlsPolicyIntegrationTest.java:368-391`.
  - Customer seeding through the repository: `GdprErasureIntegrationTest.java:100-103`.
- **ErasureResponse consumers.**
  - `GdprControllerTest.java:86-94, 119` (mocked service).
  - `GdprErasureIntegrationTest.java:122, 278` (explicitly typed).
  - `GdprServiceTest` (uses `var`).
  - `docs/api/openapi-snapshot.json:1206`.
  - No frontend or MCP consumer of `photosDeleted`.
- **Gradle.** Use the root `./gradlew`.
  - `:core-java:test` excludes the `testcontainers` tag. `:core-java:integrationTest` runs only that tag and also asserts the OpenAPI snapshot (`core-java/build.gradle.kts:269-305, 524+`).
  - Reports land in `core-java/build-local/test-results/{test,integrationTest}/TEST-*.xml`.
  - Docker 29.8.1 is up.
- **Doc gates.**
  - `docs/metrics.json` has `"schema_version": 66`.
  - `README.md:298` reads `Database schema version: **V66**`.
  - `CLAUDE.md:109` and `AGENTS.md:108` open `Current schema version: V66 (V66 …`.
  - `CLAUDE.md:15` and `AGENTS.md:15` quote the test totals.
  - `CLAUDE.md:303` and `AGENTS.md:302` read "64 Flyway migrations, V1 through V64", which is already stale: 66 versioned files exist today.
  - `scripts/docs-freshness.sh --write` regenerates the manifest. `scripts/check-doc-metrics.sh` checks the prose against it.
- **Changelog convention** (quick 260929-i9c). Write the entry heading WITHOUT a PR number. The orchestrator appends `(#<PR>)` after `gh pr create`, because `check-changelog-cites-pr.sh` P-1 needs the real number.
- **Config.** `workflow.tdd_mode = true`. `security_enforcement` is absent, which means enabled.

## Design decisions taken by the planner (Claude's discretion — the executor implements them as written)

- **DD-1: both V67 policies pin the tenant in USING and WITH CHECK.**
  - USING is what excludes the cross-tenant PUBLISHED rows that the SELECT policy exposes, so the database refuses a cross-tenant anonymisation even if the Java predicate ever regresses.
  - WITH CHECK stops a row being re-stamped into another tenant.
  - FOR UPDATE only, not FOR ALL: no DELETE path exists, and granting one would widen the change beyond the need.
- **DD-2: the ErasureRecord stays inside the anonymisation transaction.** It stays atomic with the erasure, so the evidence row can never be lost while the data was erased.
  - It is written with `photos_deleted = 0`, which is literally true at commit: nothing has been deleted yet.
  - After commit, the hook deletes the photos and then writes the real count ONCE, in a REQUIRES_NEW transaction, through `UPDATE … SET photos_deleted = :n WHERE id = :id AND tenant_id = :tenantId AND photos_deleted = 0`.
  - V67's `erasure_records` policy makes that write-once at the database: USING `tenant_id = current_tenant_id() AND photos_deleted = 0`.
  - Rejected alternatives:
    - Counting attempts in-transaction violates WR-02.
    - Recording 0 and only logging the real count permanently under-claims, which regresses what WR-02 designed.
    - Writing the record after commit can lose the evidence row.
- **DD-3: the admin response must be truthful without an API change.** The service returns an `ErasureOutcome` whose accessors carry the same names as `ErasureResponse`, plus `toResponse()`. The controller calls `toResponse()` after the proxied call returns, which is after commit and after the hook. If the hook has not run (the caller is inside an enclosing transaction), `photosDeleted()` and `toResponse()` throw `IllegalStateException` rather than report a false 0. `ErasureResponse` is byte-unchanged, so there is no OpenAPI diff.
- **DD-4: the export is scoped too.** The same email-only lookup feeds the Article-20 export, which would put another tenant's published review into this tenant's export. The email-only finder is then deleted, so it cannot be reintroduced.
- **DD-5: the dev runtime is NOT rebuilt by this plan.** Rebuilding would apply V67 to the dev database and lock its Flyway checksum while the migration can still change in the D3 review rounds. Runtime parity is therefore NOT claimed. Task 3 records the runtime's state honestly.

## Coverage audit (orchestrator's required fix shape → task)

| # | Required item | Covered by |
|---|---------------|-----------|
| 1 | V67 tenant-scoped UPDATE on reviews via `current_tenant_id()`; USING and WITH CHECK decided; header in repo style; no Flyway placeholder token; `_aud` checked (none, not audited) | Task 1 (DD-1) |
| 2 | Tenant predicate in the repository query, not RLS alone | Task 1 (DD-4 extends to export) |
| 3 | Photos deleted only after commit (afterCommit precedent); WR-02 counts kept truthful | Task 2 (DD-2, DD-3) |
| 4 | Testcontainers NOSUPERUSER test: happy, cross-tenant and rollback arms; RED on unfixed code recorded; committed before break arms | Task 1 (write + RED), Task 2 (arms + break arms) |
| 5 | metrics.json regenerated; schema/migration/test-count prose; CLAUDE.md V67 entry; RlsContractTest and count assertions | Task 3 (RlsContractTest runs in the full integrationTest suite; no test pins the policy set on either table — `rg -uu` over the test tree found none) |
| 6 | FULL unit + integrationTest suites; build-local verified | Task 3 |
| 7 | CHANGELOG entry; PR number is an orchestrator step | Task 3 + orchestrator hand-off |
</context>

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: Tracer — commit the RED NOSUPERUSER erasure test, then V67 + tenant-scoped review lookup</name>
  <files>core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureReviewRlsIntegrationTest.java, core-java/src/main/resources/db/migration/V67__reviews_update_policy_and_erasure_photo_count.sql, core-java/src/main/java/uk/jtoye/core/review/ReviewRepository.java, core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java, core-java/src/test/java/uk/jtoye/core/gdpr/GdprServiceTest.java</files>
  <precondition>`docker info` exits 0 (Testcontainers needs Postgres 15 and Azurite containers) and `git rev-parse --abbrev-ref HEAD` prints fix/764-gdpr-erasure-reviews.</precondition>
  <read_first>
    - core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java (whole file)
    - core-java/src/main/java/uk/jtoye/core/review/ReviewRepository.java, core-java/src/main/java/uk/jtoye/core/review/Review.java
    - core-java/src/main/resources/db/migration/V39__fix_storefront_rls_uuid_cast.sql lines 60-85; V51__rls_uuid_cast_safety.sql lines 85-110; V42__gdpr_erasure_completeness.sql; V65__aud_insert_policies_tenant_check.sql lines 1-60 (header style)
    - core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureIntegrationTest.java; core-java/src/test/java/uk/jtoye/core/gdpr/DsarFanoutIntegrationTest.java lines 100-160
    - core-java/src/test/java/uk/jtoye/core/product/ProductImageCrossTenantBlobDeleteIntegrationTest.java (whole file, incl. its anonymousGet and webpBytes helpers); core-java/src/test/java/uk/jtoye/core/testsupport/AzuriteTestSupport.java
    - core-java/src/test/java/uk/jtoye/core/review/ReviewsRlsPolicyIntegrationTest.java lines 368-391 (seed column lists)
    - core-java/src/main/java/uk/jtoye/core/storage/StorageService.java lines 250-300 and 345-360
    - core-java/src/test/java/uk/jtoye/core/gdpr/GdprServiceTest.java
  </read_first>
  <behavior>
    Test class GdprErasureReviewRlsIntegrationTest has three arms. Each carries a precondition that proves it CAN fail.

    Arm A, erasureAnonymisesReviewAndDeletesOwnTenantPhotosUnderRls:
    - Setup: tenant A has a customer with email X, a shop, an order, and a review by X. The review's photo_urls holds, in this order:
      - A1 and A2, two real Azurite objects under tenant A's key prefix;
      - one external URL (https://example.invalid/not-ours.jpg);
      - one real Azurite object under tenant B's key prefix (Bx).
    - Preconditions:
      - `rolsuper` for the connection role is false.
      - Under tenant A's session the review row is visible (count 1).
      - A1, A2 and Bx each answer 200 to an anonymous GET.
    - After `gdprService.eraseCustomerData`:
      - the review reads customer_name "[REDACTED]", customer_email "redacted@erased.invalid", comment NULL and photo_urls NULL;
      - A1 and A2 answer 404; Bx answers 200 with its original bytes;
      - `photosDeleted()` on the returned object is 2 and `reviewsAnonymised()` is 1;
      - exactly one erasure_records row exists for the subject, and its photos_deleted, read back in a fresh tenant-A transaction, is 2.

    Arm B, erasureLeavesAnotherTenantsPublishedReviewUntouched:
    - Setup: tenant A has a customer with email Y and a review by Y with one A-owned photo. Tenant B has a PUBLISHED shop (published = true) and a review by the SAME email Y, carrying its own B-owned photo and a distinct name and comment.
    - Precondition: under tenant A's session, the count of reviews whose customer_email is Y is 2. This proves the SELECT policy exposes B's row to A, so the arm can discriminate.
    - After erasing A's customer:
      - the call succeeds and `reviewsAnonymised()` is 1;
      - read under tenant B, B's review keeps its name, email, comment and photo_urls exactly;
      - B's photo answers 200;
      - A's review is anonymised.

    Arm C, rolledBackErasureDeletesNoPhoto:
    - Setup: tenant A has a customer with email Z and a review with one A-owned photo.
    - Run: inside an OUTER TransactionTemplate with `TenantContext` set to A, call `gdprService.eraseCustomerData`, set a `reachedEnd` flag, then `status.setRollbackOnly()`. Do NOT read `photosDeleted()` inside the enclosing transaction. Catch any RuntimeException escaping the template and keep it for the report: on the unfixed tree the #764 RLS failure throws from inside it, and the arm's verdict is the storage object and the rows, not the exception.
    - Assertions, in this order:
      1. The photo still answers 200 with its original bytes. This is the primary verdict and the one recorded as RED on the unfixed tree.
      2. `reachedEnd` is true.
      3. Read in a fresh tenant-A transaction, the review keeps its PII (a visibility precondition of count 1 comes first).
      4. Zero erasure_records rows exist for the subject.

    Every test uses fresh random tenant UUIDs and unique emails and slugs. The class is NOT @Transactional (the afterCommit hook must really fire), so data persists across methods.
  </behavior>
  <action>
    Step 1: write the test FIRST. Create `GdprErasureReviewRlsIntegrationTest` in `uk.jtoye.core.gdpr` with the three arms in `<behavior>` and those exact method names.

    Class shape:
    - Annotations `@SpringBootTest`, `@Testcontainers`, `@ActiveProfiles("test")`, `@Tag("testcontainers")`. NOT `@Transactional`.
    - Its own static Postgres 15 container (database jtoye_test, user test) and a static `AzuriteTestSupport.newAzurite()` container.
    - Properties via `IntegrationTestSupport.registerPostgresTestProperties` and `AzuriteTestSupport.registerAzuriteProperties`.
    - Autowire GdprService, StorageService, BlobObjectStore, JdbcTemplate, PlatformTransactionManager, and CustomerRepository.

    In @BeforeEach:
    - Create the Azurite containers with `AzuriteTestSupport.createContainers(blobObjectStore, ContainerAccess.BLOB)`.
    - Then downgrade the bootstrap role exactly once with the DsarFanoutIntegrationTest recipe: an AtomicBoolean guard, `ALTER ROLE "test" NOSUPERUSER`, and a `rolsuper = false` precondition asserted every time.

    Seeding and reading after the downgrade:
    - All seeding and every verification read goes through a helper that sets `TenantContext`, runs the work in `new TransactionTemplate(txManager).execute(...)`, and clears `TenantContext` in a finally. TenantSetLocalAspect then pins the tenant GUC on every JdbcTemplate/Repository call inside that transaction.
    - `tenants` has no RLS, so insert it directly.
    - Shops and orders use the column lists from `ReviewsRlsPolicyIntegrationTest:368-391`. Tenant B's shop has published = true.
    - Customers are saved with `customerRepository.saveAndFlush` inside the tenant transaction, as in `GdprErasureIntegrationTest:100-103`.
    - Reviews are inserted through JdbcTemplate with photo_urls built by an SQL `ARRAY[?, ?, …]::text[]` constructor over bound parameters.
    - Photo objects are put with `storageService.putBytes(tenant + "/reviews/" + randomUUID + "/p.webp", bytes, "image/webp")`. Keep the returned URLs and bytes for the verdicts.
    - The anonymous-GET helper is copied from the ProductImage test.

    If the context refuses the erasure call at the shop-scope gate (#283), add `@uk.jtoye.core.testsupport.AsSystemHarness` as the sibling Blob test does, and record that in the SUMMARY. Do not otherwise alter production auth.

    Step 2: commit the test ALONE, before any fix. Subject: `test(260930-bvp): NOSUPERUSER erasure test for reviewers, cross-tenant and rollback arms (#764)`. Stage only the test file.

    Step 3: run the class on the UNFIXED tree and record, per arm, the verbatim failing exception class and message (or assertion diff) in the SUMMARY.
    - Expected: A and B fail from the 0-row UPDATE of reviews. Record the actual Hibernate/Spring exception; the issue only inferred it. C fails on assertion 1 (the photo is gone).
    - If ANY arm passes on the unfixed tree, STOP. That arm cannot fail. Strengthen it and re-run before touching production code.

    Step 4: create `V67__reviews_update_policy_and_erasure_photo_count.sql` (per DD-1 and DD-2).
    - Re-run safe: `DROP POLICY IF EXISTS` then `CREATE POLICY`, the V51 style.
    - (a) `reviews_tenant_update ON reviews FOR UPDATE`, USING and WITH CHECK both `tenant_id = current_tenant_id()`.
    - (b) `erasure_records_photo_count_update ON erasure_records FOR UPDATE`, USING `tenant_id = current_tenant_id() AND photos_deleted = 0`, WITH CHECK `tenant_id = current_tenant_id()`.
    - Every tenant comparison uses the safe helper `current_tenant_id()` only. `RlsContractTest.noPolicyUsesRawTenantGucCast` sweeps pg_policy, so a raw GUC cast fails the suite.
    - Header comment in the V65 house style. It must explain:
      - WHY: FORCE RLS with no UPDATE policy means the anonymising UPDATE matches 0 rows for the NOSUPERUSER role, the erasure rolls back, and the photos were already deleted. Measured RED by `GdprErasureReviewRlsIntegrationTest`; quote the recorded exception class.
      - Why USING and WITH CHECK both pin the tenant: USING excludes the published-shop rows `reviews_tenant_read` exposes across tenants; WITH CHECK prevents re-stamping a row.
      - Why FOR UPDATE and not FOR ALL.
      - Why there is no `_aud` policy: Review is not @Audited and `reviews_aud` does not exist.
      - Why `erasure_records` now needs a narrow UPDATE (photo deletion moved after commit, so the count is only known then), why it is write-once, and the accepted residual: rows still at 0 stay tenant-updatable, and the same role can already INSERT records for its tenant.
      - No data change, no backfill, no role or extension.
    - No Flyway placeholder token anywhere in the file, comments included. That token is a dollar sign immediately followed by an opening brace; a comment carrying one aborts startup.

    Step 5 (per DD-4): add `List<Review> findByTenantIdAndCustomerEmail(UUID tenantId, String customerEmail)` to `ReviewRepository`. Then delete the old derived query at `ReviewRepository.java:21`, the one whose only parameter is the email String; after the switch it has no caller. Do not name the deleted method in any comment.

    Step 6: in `GdprService`, point both lookups at the new finder.
    - The export (`:76`) passes `customer.getTenantId()` and `customer.getEmail()`.
    - The erasure (`:199`) passes the captured `tenantId` and `originalEmail`.
    - Extend the erasure Javadoc's completeness list with one sentence: the lookup carries an explicit tenant predicate because `reviews_tenant_read` shows PUBLISHED reviews across tenants (#764).
    - Leave the photo loop synchronous in this task; Task 2 moves it.

    Step 7: in `GdprServiceTest`, re-stub the five mock sites to the new finder with `(tenantId, "jane@example.com")`. Add one test verifying that both export and erase call the finder with the customer's tenant id; a stub with the wrong tenant returns the empty default.

    Step 8: run the checks below.
    - The GdprServiceTest class is GREEN.
    - Arms A and B are GREEN.
    - Arm C is still RED, and must stay RED here: the photo is deleted synchronously inside the enclosing transaction. Record that output; it is Task 2's RED.
    - Commit with subject `fix(260930-bvp): tenant-scoped UPDATE policy on reviews and tenant-predicated review lookup (#764)`, staging only this task's files.
  </action>
  <verify>
    <automated>M=$(mktemp) && ./gradlew :core-java:test --rerun --tests 'uk.jtoye.core.gdpr.GdprServiceTest'; rc1=$?; ./gradlew :core-java:integrationTest --rerun --tests 'uk.jtoye.core.gdpr.GdprErasureReviewRlsIntegrationTest.erasureAnonymisesReviewAndDeletesOwnTenantPhotosUnderRls' --tests 'uk.jtoye.core.gdpr.GdprErasureReviewRlsIntegrationTest.erasureLeavesAnotherTenantsPublishedReviewUntouched'; rc2=$?; ./gradlew :core-java:integrationTest --rerun --tests 'uk.jtoye.core.gdpr.GdprErasureReviewRlsIntegrationTest.rolledBackErasureDeletesNoPhoto'; rc3=$?; out=$(rg -uu -c 'findByCustomerEmail\(' core-java/src); rc4=$?; out5=$(rg -uu -F -c '${' core-java/src/main/resources/db/migration/V67__reviews_update_policy_and_erasure_photo_count.sql); rc5=$?; echo "unit=$rc1 armsAB=$rc2 armC=$rc3(expect non-0) oldFinder=$rc4(expect 1) placeholder=$rc5(expect 1)"; /usr/bin/find core-java/build-local/test-results/ -name 'TEST-uk.jtoye.core.gdpr.*.xml' -newer "$M"; [ $rc1 -eq 0 ] && [ $rc2 -eq 0 ] && [ $rc3 -ne 0 ] && [ $rc4 -eq 1 ] && [ $rc5 -eq 1 ]</automated>
  </verify>
  <acceptance_criteria>
    - The test commit precedes the fix commit: `git log --format=%s -2` shows the `fix(260930-bvp)` subject above the `test(260930-bvp)` subject.
    - The RED run on the unfixed tree is recorded in the SUMMARY per arm, verbatim. All three arms were non-green there.
    - After the fix, arms A and B pass and arm C fails on its photo assertion; that is Task 2's RED, recorded.
    - The old-finder gate returns rc 1 on this tree. Its fail direction: the same command returned rc 0 before step 5, and that output is recorded.
    - The V67 placeholder gate returns rc 1. Its fail direction: the same `rg -uu -F -c` against a scratch file under the session scratchpad containing a dollar-brace token returns rc 0, recorded.
    - The fresh-report `find` lists the XML files for both GdprServiceTest and GdprErasureReviewRlsIntegrationTest. An empty listing means nothing ran and is a VOID, never a pass.
  </acceptance_criteria>
  <done>The test class exists and is committed before any fix, with its RED run recorded. V67 and the tenant-predicated finder are committed. Arms A and B are green, arm C is red (as expected), and GdprServiceTest is green.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Delete review photos only after commit, write the true count once, and prove it with break arms</name>
  <files>core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java, core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecordRepository.java, core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java, core-java/src/test/java/uk/jtoye/core/gdpr/GdprServiceTest.java, core-java/src/test/java/uk/jtoye/core/gdpr/GdprControllerTest.java, core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureIntegrationTest.java</files>
  <precondition>Task 1's two commits are present (`git log --format=%s -2`), and arm C of GdprErasureReviewRlsIntegrationTest is RED on HEAD.</precondition>
  <read_first>
    - core-java/src/main/java/uk/jtoye/core/tenant/TenantLifecycleService.java lines 125-170 (afterCommit precedent)
    - core-java/src/main/java/uk/jtoye/core/config/TenantCacheEvictor.java lines 100-140 (inline fallback when no synchronization)
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionService.java lines 65-82 (why REQUIRES_NEW)
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarFanoutWorker.java lines 115-145 and 195-235 (TransactionTemplate construction; tenant set around commit)
    - core-java/src/main/java/uk/jtoye/core/security/TenantContext.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java; core-java/src/test/java/uk/jtoye/core/gdpr/GdprControllerTest.java
  </read_first>
  <behavior>
    Unit tests in GdprServiceTest. Write them first and record the RED run.

    - Deferred path. With `TransactionSynchronizationManager.initSynchronization()` active:
      - `eraseCustomerData` calls `storageService.delete` zero times;
      - the ErasureRecord passed to save carries photosDeleted 0;
      - `outcome.photosDeleted()` throws IllegalStateException.
    - Commit trigger. Invoking `afterCommit()` on every registered synchronization:
      - calls `delete` once per photo URL;
      - at each delete, `TenantContext.get()` equals the customer's tenant; capture it with an Answer;
      - settles the tally from the true returns (WR-02: delete returns true, false, true → 2 deleted);
      - calls `erasureRecordRepository.recordPhotosDeleted(recordId, tenantId, 2)` exactly once.
    - Zero deleted. When no delete returns true, `recordPhotosDeleted` is never called.
    - Rollback trigger. Invoking only `afterCompletion(STATUS_ROLLED_BACK)` deletes nothing.
    - Tenant restore. `TenantContext` is restored after the hook: a prior value X is still X afterwards; no prior value is still empty afterwards.
    - Hook failure. When `recordPhotosDeleted` throws, the hook does not propagate the exception, and the tally is still settled with the real deleted count.
    - Inline path. With no synchronization active, deletion runs inline, and the existing assertions (2 / 1 / 0 photos deleted) hold unchanged.
    - Cleanup. Every test that initialises synchronization clears it in a finally.

    Integration: GdprErasureReviewRlsIntegrationTest arms A, B and C are all GREEN. Arm A's durable count of 2 now comes from the post-commit REQUIRES_NEW write.
  </behavior>
  <action>
    Step 1: write the unit tests in `<behavior>` first, run them, and record RED.
    - Add `@Mock PlatformTransactionManager`.
    - Under `@InjectMocks`, a mocked manager lets TransactionTemplate run its callback (getTransaction returns null, commit is a no-op).

    Step 2 (per DD-3): add to `GdprService` a nested public record `ErasureOutcome`.
    - Components: customerId, erasedAt, ordersAnonymised, reviewsAnonymised, auditRowsScrubbed, recordId, and a `PhotoErasureTally photos`.
    - A method `photosDeleted()` delegating to the tally.
    - `toResponse()` building the UNCHANGED `GdprController.ErasureResponse`.

    Add a nested public static final class `PhotoErasureTally`:
    - public no-arg constructor;
    - `settle(int deleted, int notDeleted)`;
    - `isSettled()`;
    - `deleted()` and `notDeleted()`, which throw IllegalStateException while unsettled. The message says photo deletion runs after the enclosing transaction commits, and to read the durable erasure record instead.

    `eraseCustomerData` returns `ErasureOutcome`.

    Step 3 (per DD-2): restructure the erasure's review section.
    - While anonymising the tenant-scoped reviews, collect every non-null photo URL into a list. Do not call storage inside the loop.
    - Save the ErasureRecord with photosDeleted 0.
    - Then hand the post-commit step (captured tenantId, customerId, recordId, the URL list, a new tally) to a private scheduler:
      - it registers a `TransactionSynchronization` whose `afterCommit()` runs the step when `TransactionSynchronizationManager.isSynchronizationActive()`;
      - otherwise it runs the step inline (the TenantCacheEvictor idiom).
    - The existing INFO erasure log now reports how many photo URLs were detached and that deletion runs after commit, instead of a deleted count. Log no emails and no URLs.

    Step 4: the post-commit step (a private method).
    - Save `TenantContext.get()` and set `TenantContext` to the captured tenantId. D-09 in `StorageService.delete` reads it, so a missing or foreign context would refuse or misdirect deletes.
    - For each URL: `storageService.delete(url)` true increments deleted, otherwise notDeleted.
    - In a finally:
      - settle the tally with the counts reached;
      - restore TenantContext: set the saved value if present, else clear. The DSAR worker still owns its own clear.
    - If deleted is above 0, run `erasureRecordRepository.recordPhotosDeleted(recordId, tenantId, deleted)` through a `TransactionTemplate` built in the constructor from an injected `PlatformTransactionManager`, with `setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)`. Put a comment citing KeycloakDeprovisionService: a default-propagation write inside afterCommit joins the committed transaction and is silently lost. The aspect pins the GUC because TenantContext is still set inside that template.
    - If the update count is not 1, log ERROR naming the record id and the counts.
    - Log INFO with the deleted count and the record id. Move the existing WR-02 WARN for notDeleted here unchanged.
    - Wrap the whole step in a catch of RuntimeException that logs ERROR with the record id and never rethrows. This follows the TenantLifecycleService precedent: the erasure HAS committed, and a post-commit failure must not surface as a failed erasure. The record then under-claims and never over-claims.

    Step 5: add to `ErasureRecordRepository` a `@Modifying` native `@Query`, `int recordPhotosDeleted(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("photosDeleted") int photosDeleted)`. It runs `UPDATE erasure_records SET photos_deleted = :photosDeleted WHERE id = :id AND tenant_id = :tenantId AND photos_deleted = 0`. Javadoc: write-once, post-commit only, and must run in a REQUIRES_NEW transaction.

    Step 6: callers of the new return type.
    - `GdprController.eraseData` returns `ResponseEntity.ok(gdprService.eraseCustomerData(customerId).toResponse())`, with a one-line comment that the proxied call returns after commit and after the photo step. Do not touch the `ErasureResponse` record declaration.
    - `eraseSubjectByDigest` is unchanged; add one Javadoc sentence that photo deletion runs when the caller's per-tenant transaction commits.
    - `GdprControllerTest`: the two stubs return an `ErasureOutcome` whose tally is settled with the same numbers the old response carried (4 deleted), so every JSON assertion stays identical.
    - `GdprErasureIntegrationTest:122` and `:278`: change the explicit `GdprController.ErasureResponse` type to `var`.

    Step 7: run the unit tests (GREEN), then the whole `uk.jtoye.core.gdpr.*` integration set: the new class, GdprErasureIntegrationTest, DsarFanoutIntegrationTest and DsarVerificationIntegrationTest. Everything must be GREEN.
    - Commit with subject `fix(260930-bvp): delete erased reviewers' photos only after commit and record the true count once (#764)`, staging only this task's files.

    Step 8: break arms on the COMMITTED tree, bracketed clean → arms → clean.
    - First record the sha256 of GdprService.java, ReviewRepository.java and the V67 file.
    - Run each arm alone, then restore with `git restore --source=HEAD --staged --worktree -- <file>`, and verify the restore BY sha256 equality before the next arm:
      1. Arm 1: remove the reviews UPDATE policy statement from V67. Expect arm A RED (0-row update).
      2. Arm 2: temporarily re-add an email-only derived finder and call it from the erasure path. Expect arm B RED.
      3. Arm 3: make the scheduler always run the step inline. Expect arm C RED (photo deleted).
      4. Arm 4: drop the REQUIRES_NEW propagation line so the template uses the default. Expect arm A RED on the durable count (expected 2, read 0).
    - Record each arm's verbatim failure.
    - Any arm that stays GREEN is a vacuous check. Report it, strengthen the test, commit that, and redo the bracket.
    - Finish with a clean run of the class GREEN and all three hashes equal to the recorded ones.
  </action>
  <verify>
    <automated>M=$(mktemp) && ./gradlew :core-java:test --rerun --tests 'uk.jtoye.core.gdpr.*'; rc1=$?; ./gradlew :core-java:integrationTest --rerun --tests 'uk.jtoye.core.gdpr.*'; rc2=$?; echo "unit=$rc1 integration=$rc2"; /usr/bin/find core-java/build-local/test-results/ -name 'TEST-uk.jtoye.core.gdpr.*.xml' -newer "$M"; out=$(rg -uu -n 'PROPAGATION_REQUIRES_NEW|registerSynchronization' core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java); rc3=$?; echo "$out"; [ $rc1 -eq 0 ] && [ $rc2 -eq 0 ] && [ $rc3 -eq 0 ]</automated>
  </verify>
  <acceptance_criteria>
    - The unit RED run (before implementation) and the GREEN run are both recorded.
    - All `uk.jtoye.core.gdpr.*` unit and integration tests pass on the committed tree, and fresh XML reports are listed for each class. An empty listing is a VOID.
    - All four break arms turned their named arm RED, each recorded verbatim. The closing clean run is GREEN. The three sha256 values after restore equal the pre-arm values, recorded side by side.
    - `ErasureResponse` is unchanged: `git diff c5d16ff6 -- core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java` shows no line inside the `record ErasureResponse(` declaration, recorded.
  </acceptance_criteria>
  <done>Photos are deleted only after commit, with the erasing tenant pinned. The response and the durable record report only real deletions, and the record's count is written once, post-commit. All GDPR tests pass. Four break arms are recorded.</done>
</task>

<task type="auto">
  <name>Task 3: Doc gates, CHANGELOG entry, full core-java suites, and an honest runtime/branch report</name>
  <files>docs/metrics.json, CLAUDE.md, AGENTS.md, README.md, docs/CHANGELOG.md</files>
  <precondition>Task 2's commit is present and the gdpr test set is green on HEAD.</precondition>
  <read_first>
    - scripts/docs-freshness.sh (header, lines 1-20 and 60-100), scripts/check-doc-metrics.sh (header)
    - CLAUDE.md line 109 (the schema-version entry style) and lines 15, 303; AGENTS.md lines 15, 108, 302; README.md line 298
    - docs/CHANGELOG.md lines 1-45 (entry style under [Unreleased])
    - scripts/check-changelog-cites-pr.sh (header), scripts/check-branch-behind-base.sh (header)
  </read_first>
  <action>
    Step 1, the natural RED of both doc gates. Before editing anything, run `scripts/docs-freshness.sh` (check mode) and `scripts/check-doc-metrics.sh`, and record each rc and the lines naming drift. Expected: docs-freshness is non-zero on schema_version 66 vs the V67 head and on the Java test totals.

    Step 2: run `scripts/docs-freshness.sh --write` and read `git diff docs/metrics.json`.
    - Expect exactly: schema_version 67, the Java `@Test` count up by the number of `@Test` methods added in Tasks 1 and 2, the Java file count up by 1, and the derived logical total.
    - Anything else moving means the tree differs from what this plan measured. Investigate and report; do not absorb it.

    Step 3: bring the prose to the manifest.
    - CLAUDE.md:15 and AGENTS.md:15: the totals, as `check-doc-metrics.sh` names them.
    - README.md:298: V66 becomes V67.
    - CLAUDE.md:303 and AGENTS.md:302: "64 Flyway migrations, V1 through V64" becomes the true count and range. Count the versioned files first; expect 67 and V1 through V67.
    - CLAUDE.md:109 and AGENTS.md:108: prepend a V67 entry in EACH file's own established style. The number after "Current schema version:" becomes V67, and the V67 entry becomes the parenthesised lead. The former V66 lead follows as a plain entry, the way V65 follows today. CLAUDE.md uses the bracketed-provenance form "[#764 — …]". AGENTS.md uses its shorter form.
    - The V67 entry covers:
      - the defect (no UPDATE policy under FORCE RLS; 0-row UPDATE; erasure rolled back after its photos were deleted; measured RED by GdprErasureReviewRlsIntegrationTest with the recorded exception class);
      - the two policies and why USING and WITH CHECK both pin the tenant;
      - the write-once `erasure_records` photo-count policy and why deletion moved after commit;
      - the explicit tenant predicate on the review lookup (the SELECT policy is cross-tenant for PUBLISHED shops);
      - no `_aud` mirror (Review is not @Audited);
      - no backfill and no data change.
    - Re-run both gates until each returns rc 0.

    Step 4: add a `docs/CHANGELOG.md` entry directly under `## [Unreleased]`, above the Phase 36 entry.
    - Heading: `### GDPR erasure works for customers who left a review — 2026-09-30`, with NO PR number (house convention; the orchestrator appends it).
    - Bullets: **Why** (issue #764 in the body only, never in the heading), **Fix** (V67, the tenant predicate on erase and export, post-commit deletion, write-once count), **Proof** (NOSUPERUSER + Azurite test, RED on the unfixed tree, four break arms).

    Step 5: the FULL suites.
    - Touch a marker file.
    - Run `./gradlew :core-java:test --rerun`, then `./gradlew :core-java:integrationTest --rerun`, capturing each rc on its own statement. The integration suite includes RlsContractTest, ReviewsRlsPolicyIntegrationTest and the OpenAPI snapshot assertion.
    - Sum `tests`, `failures`, `errors` and `skipped` over every `TEST-*.xml` newer than the marker in `core-java/build-local/test-results/test` and `.../integrationTest`, using a stdlib one-liner spelled `/usr/bin/python3 -c`. Record the totals.
    - Pass means both rc are 0, both report sets are non-empty and fresh, and failures plus errors equal 0.
    - A red test that is not this plan's: record it with its output and whether it also fails on `origin/main`. Do not fix it silently.

    Step 6, the branch and the runtime (per DD-5).
    - Run `git fetch origin` and then `scripts/check-branch-behind-base.sh`; record the rc.
    - Check whether the compose stack is running (`docker compose -f docker-compose.full-stack.yml ps`).
    - If it is running, run `scripts/check-runtime-freshness.sh` and record the verdict. Expect core-java STALE or VOID.
    - Do NOT rebuild. Rebuilding applies V67 to the dev DB while the migration may still change in review.
    - The SUMMARY states plainly that runtime parity is NOT claimed, and why.

    Step 7: commit the docs with subject `docs(260930-bvp): schema V67, metrics and changelog for the #764 erasure fix`, staging only this task's five files.

    Hand-offs the SUMMARY lists for the orchestrator:
    - append `(#<PR>)` to the changelog heading after `gh pr create`, then run `scripts/check-changelog-cites-pr.sh --pr <PR> --title '<title>'` (expect 0) and the absent control `--pr 999999` (expect 1);
    - re-run `scripts/check-branch-behind-base.sh` immediately before opening the PR;
    - the PR body carries no AI-attribution lines;
    - rebuild the runtime once review settles V67.
  </action>
  <verify>
    <automated>scripts/docs-freshness.sh; rc1=$?; scripts/check-doc-metrics.sh; rc2=$?; out=$(rg -uu -n '"schema_version": 67' docs/metrics.json); rc3=$?; out4=$(rg -uu -n 'Current schema version: V67 \(V67' CLAUDE.md AGENTS.md); rc4=$?; echo "$out4"; M=$(mktemp) && ./gradlew :core-java:test --rerun; rc5=$?; ./gradlew :core-java:integrationTest --rerun; rc6=$?; /usr/bin/python3 -c 'import glob,os,sys,xml.etree.ElementTree as E; m=os.path.getmtime(sys.argv[1]); t={"tests":0,"failures":0,"errors":0,"skipped":0}; n=0
for f in glob.glob("core-java/build-local/test-results/*/TEST-*.xml"):
  if os.path.getmtime(f)<=m: continue
  n+=1; r=E.parse(f).getroot()
  for k in t: t[k]+=int(r.get(k,0))
print("fresh_reports",n,t); sys.exit(0 if n>0 and t["failures"]+t["errors"]==0 else 1)' "$M"; rc7=$?; echo "freshness=$rc1 docmetrics=$rc2 schema67=$rc3 v67prose=$rc4 unit=$rc5 integration=$rc6 totals=$rc7"; [ $rc1 -eq 0 ] && [ $rc2 -eq 0 ] && [ $rc3 -eq 0 ] && [ $rc4 -eq 0 ] && [ $rc5 -eq 0 ] && [ $rc6 -eq 0 ] && [ $rc7 -eq 0 ]</automated>
  </verify>
  <acceptance_criteria>
    - Both doc gates were recorded non-zero before the edits (step 1) and rc 0 after. Both directions are recorded.
    - `rg` finds `Current schema version: V67 (V67` in BOTH CLAUDE.md and AGENTS.md (2 matches). The fail direction: 0 matches on the pre-edit tree, recorded in step 1.
    - The full unit and integration suites executed, with fresh report counts over 0 for each directory, and failures plus errors equal 0. The totals are recorded next to the pre-change totals from `docs/metrics.json`.
    - The changelog entry sits under `[Unreleased]` above the Phase 36 entry, and its heading carries no `#` number.
    - The check-branch-behind-base rc and the runtime state (running or down, and the freshness verdict if running) are recorded, with runtime parity explicitly NOT claimed.
  </acceptance_criteria>
  <done>Doc gates are green with both directions recorded. The CHANGELOG entry is written. The full unit and integration suites are green on fresh reports. The branch and runtime state are reported honestly. The orchestrator hand-offs are listed.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| admin client → GdprController | admin-realm-role request (`@PreAuthorize("hasRole('admin')")`) naming a customer id to erase/export |
| application role → Postgres | NOSUPERUSER app role under FORCE RLS; tenant carried by the `app.current_tenant_id` GUC pinned from TenantContext |
| application → Azure Blob | external, NON-transactional store; deletes cannot be rolled back |
| DSAR worker (system) → GdprService | background per-tenant fan-out; tenant set by the worker around its own transaction |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-764-01 | Information disclosure / Tampering | GdprService review lookup (erase + export) | high | mitigate | `findByTenantIdAndCustomerEmail` with the tenant taken from the customer row read under RLS. Email-only finder deleted. V67's USING clause also refuses a cross-tenant UPDATE at the database. Proven by arm B and break arm 2. |
| T-764-02 | Tampering | `reviews_tenant_update` policy (V67) | medium | mitigate | USING and WITH CHECK both `tenant_id = current_tenant_id()`, so no cross-tenant row can be targeted or re-stamped. FOR UPDATE only, no DELETE. The only UPDATE caller is the erasure (ReviewService writes INSERTs only). |
| T-764-03 | Repudiation / Tampering | `erasure_records_photo_count_update` policy (V67) | medium | mitigate + accept residual | Write-once at the database (USING `photos_deleted = 0`). The service UPDATE touches one column with an id + tenant + `= 0` predicate and asserts 1 row. Accepted residual: a record still at 0 remains tenant-updatable by the app role. That role can already INSERT records for its own tenant, and has no cross-tenant reach. |
| T-764-04 | Denial of service (data destruction) | review photo deletion | high | mitigate | Deletion runs only in `afterCommit`. A rolled-back erasure deletes nothing. Proven by arm C and break arm 3. |
| T-764-05 | Elevation of privilege | post-commit storage delete | medium | mitigate | The hook pins TenantContext to the captured erasing tenant and restores the prior value in a finally, so the D-09 guard compares against the right tenant and fails closed otherwise. Arm A's B-owned URL (Bx) proves a foreign object survives. |
| T-764-06 | Repudiation | post-commit failure window (crash between commit and delete, or a failed count write) | low | accept | Deletion is best-effort after commit; failures are logged at ERROR/WARN with the record id and counts, with no PII and no URLs. The durable count can under-claim but never over-claim (WR-02). Orphaned objects have no DB reference left to leak. |
| T-764-07 | Information disclosure | logs | low | mitigate | Logs carry counts, customer id and record id only. No emails and no photo URLs (URLs are client-supplied and may carry anything). |
| T-764-SC | Tampering | npm/pip/cargo installs | low | accept | No package installs in this plan; no new dependencies. |
</threat_model>

<verification>
- Task 1 recorded RED on the unfixed tree for all three arms, verbatim, BEFORE any production change, and the test commit precedes the fix commits.
- Four break arms each turned their named arm RED. The clean → arms → clean bracket closes GREEN with the sha256 restores recorded.
- The `uk.jtoye.core.gdpr.*` unit and integration tests are green (Task 2). The full `:core-java:test` and `:core-java:integrationTest` suites are green on fresh reports (Task 3).
- `scripts/docs-freshness.sh` and `scripts/check-doc-metrics.sh` are rc 0, and each was observed non-zero before the edits.
- `ErasureResponse` is unchanged (no OpenAPI diff; the integrationTest snapshot assertion is green).
- Runtime parity is explicitly NOT claimed (DD-5). Branch-behind-base is recorded, and the orchestrator re-checks it before opening the PR.
</verification>

<success_criteria>
- A customer with a review, including photos, is erasable under the real NOSUPERUSER RLS posture. PII is anonymised, one evidence row is written, and own-tenant photos are removed.
- Another tenant's published review under the same email is neither read into the erasure or the export, nor modified.
- A rolled-back erasure destroys no photo.
- The response and the durable record report only deletions that happened. The record's count is written once, after the objects are gone.
- All core-java unit and integration tests pass, the doc gates pass, and the CHANGELOG entry exists with its PR number left to the orchestrator.
</success_criteria>

<output>
Create `.planning/quick/260930-bvp-fix-764-gdpr-erasure-fails-for-customers/260930-bvp-SUMMARY.md` when done. It must carry:

- the per-arm RED output on the unfixed tree;
- the four break-arm outputs, with the sha256 restore table;
- the unit and integration totals before and after;
- both directions of each doc gate;
- the branch-behind-base rc and the runtime state;
- the orchestrator hand-offs: the PR number appended to the CHANGELOG heading with check-changelog-cites-pr run in both directions; branch-behind-base re-checked immediately before `gh pr create`; no AI attribution in the PR body; the runtime rebuilt once review settles V67.
</output>
