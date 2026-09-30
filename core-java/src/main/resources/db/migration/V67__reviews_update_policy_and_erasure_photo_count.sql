-- V67: a tenant-scoped UPDATE policy on reviews, and a write-once photo-count
-- UPDATE policy on erasure_records (issue #764).
--
-- WHY THIS EXISTS. reviews carries FORCE ROW LEVEL SECURITY (V35) and, until
-- now, only two policies: reviews_tenant_read FOR SELECT (V39) and
-- reviews_tenant_write FOR INSERT (V51). There was no UPDATE policy in any
-- migration, so for the NOSUPERUSER application role every UPDATE on reviews
-- matched ZERO rows. The only UPDATE on reviews is the GDPR Article-17
-- erasure's anonymisation of a reviewer's name, email, comment and photo URLs,
-- and Hibernate rejects a zero-row update, so the whole erasure rolled back -
-- for EVERY customer who had ever left a review. Worse, the erasure deleted the
-- review photos from Blob storage BEFORE that failure, and object storage is not
-- transactional: the rollback restored the rows but not the photos. Measured RED
-- by GdprErasureReviewRlsIntegrationTest under a NOSUPERUSER role on the unfixed
-- tree, all three arms:
--
--     org.springframework.orm.ObjectOptimisticLockingFailureException:
--       Batch update returned unexpected row count from update [0];
--       actual row count: 0; expected: 1;
--       statement executed: update reviews set comment=?, ... where id=?
--     (caused by org.hibernate.StaleStateException)
--
-- and the rollback arm read the review photo as 404 after the erasure had
-- rolled back. The existing erasure suites stayed green because
-- GdprErasureIntegrationTest runs as the Testcontainers SUPERUSER, which bypasses
-- FORCE RLS, and neither it nor DsarFanoutIntegrationTest ever inserts a review.
--
-- (a) reviews_tenant_update. USING and WITH CHECK both pin the tenant, and each
-- does a different job:
--   * USING decides which existing rows an UPDATE may target.
--     reviews_tenant_read exposes the reviews of every PUBLISHED shop to every
--     tenant (the public storefront read), so a session pinned to tenant A can
--     SEE tenant B's published reviews. Without a tenant USING clause, an erasure
--     for A that picked up B's review under the same email would anonymise B's
--     row. The service now carries an explicit tenant predicate on the lookup
--     too; this clause is the database refusing the cross-tenant write even if
--     that Java predicate ever regresses.
--   * WITH CHECK decides what the row may look like afterwards: it stops an
--     UPDATE from re-stamping a row into another tenant.
-- FOR UPDATE and not FOR ALL: no code path deletes a review, and FOR ALL would
-- grant a DELETE nothing needs. The only other writer of reviews
-- (ReviewService) INSERTs a new review, so this policy opens exactly the
-- erasure path.
--
-- No _aud policy: Review is not @Audited and reviews_aud does not exist, so an
-- UPDATE on reviews writes no Envers revision.
--
-- (b) erasure_records_photo_count_update. The review photos are now deleted
-- only AFTER the erasure commits, so how many objects the store really removed
-- (the WR-02 count) is only known then. The erasure_records row is still written
-- INSIDE the erasure transaction, atomically with the anonymisation, with
-- photos_deleted = 0 - literally true at commit, since nothing has been deleted
-- yet. After commit, the service deletes the photos and writes the real count
-- ONCE, in its own transaction. erasure_records has FORCE RLS with SELECT and
-- INSERT policies only (V42), so that UPDATE would match zero rows for the same
-- reason as (a). This policy lets it through, narrowly:
--   * USING tenant_id = current_tenant_id() AND photos_deleted = 0 makes the
--     write WRITE-ONCE at the database: once a count is recorded the row is no
--     longer a target, so the evidence cannot be rewritten afterwards.
--   * WITH CHECK tenant_id = current_tenant_id() stops re-stamping.
-- Accepted residual: a record whose count is still 0 remains updatable by the
-- application role for its own tenant. That role can already INSERT records for
-- its own tenant, and it has no cross-tenant reach, so this adds no new power
-- over another tenant's evidence.
--
-- DDL ONLY - no rows are read or written, no backfill, no data change, no role
-- and no extension (scripts/check-no-create-extension.sh). Every tenant
-- comparison goes through the safe current_tenant_id() helper, never the raw
-- GUC cast (V51 / RlsContractTest.noPolicyUsesRawTenantGucCast). Re-run safe in
-- the V51 style: DROP POLICY IF EXISTS, then CREATE POLICY.

DROP POLICY IF EXISTS reviews_tenant_update ON reviews;
CREATE POLICY reviews_tenant_update ON reviews
    FOR UPDATE
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

DROP POLICY IF EXISTS erasure_records_photo_count_update ON erasure_records;
CREATE POLICY erasure_records_photo_count_update ON erasure_records
    FOR UPDATE
    USING (tenant_id = current_tenant_id() AND photos_deleted = 0)
    WITH CHECK (tenant_id = current_tenant_id());
