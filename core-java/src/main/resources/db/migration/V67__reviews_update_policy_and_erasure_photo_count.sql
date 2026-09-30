-- V67: a tenant-scoped UPDATE policy on reviews, and a write-once photo-count
-- UPDATE path on erasure_records - policy plus trigger (issue #764).
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
-- (b) erasure_records: the one-time photo count. The review photos are now
-- deleted only AFTER the erasure commits, so how many objects the store really
-- removed (the WR-02 count) is only known then. The erasure_records row is still
-- written INSIDE the erasure transaction, atomically with the anonymisation,
-- with photos_deleted = 0 - literally true at commit, since nothing has been
-- deleted yet. After commit, the service deletes the photos and, when it removed
-- at least one, writes the real count ONCE, in its own transaction.
-- erasure_records has FORCE RLS with SELECT and INSERT policies only (V42), so
-- that UPDATE would match zero rows for the same reason as (a). This migration
-- opens that one UPDATE and nothing else, in two layers that do different jobs:
--
--   * erasure_records_photo_count_update (RLS policy) decides WHICH ROWS the
--     application role may target: its own tenant's (USING and WITH CHECK both
--     pin tenant_id = current_tenant_id(), so no cross-tenant write and no
--     re-stamping), and only while photos_deleted = 0.
--   * erasure_records_write_once() (BEFORE UPDATE trigger) decides WHAT an
--     update may change. A policy is a ROW filter, not a column restriction: on
--     its own it let EVERY column of a zero-count record be rewritten -
--     erased_by, erased_at, the subject digest, the other counts - turning the
--     Article-17 proof row into something the application could falsify.
--     Measured RED under the policy alone by GdprErasureReviewRlsIntegrationTest:
--     arm D's UPDATE ... SET erased_by = 'forged' "updated 1 row(s)" as the
--     NOSUPERUSER application role, and arm E's rewrite of an already-recorded
--     count "updated 1 row(s)" as a BYPASSRLS role. The trigger raises SQLSTATE
--     42501 unless the update is exactly: OLD.photos_deleted = 0,
--     NEW.photos_deleted > 0, and every other column unchanged - compared as the
--     whole row in jsonb minus photos_deleted, so a column added to
--     erasure_records later is immutable by default until a migration
--     deliberately amends this function. It fires for EVERY role whatever its RLS
--     posture; a trigger and not column GRANTs, because Testcontainers migrates
--     and tests as a different role from production's jtoye_runtime, so a
--     GRANT-based restriction would be proven against a role that never runs it.
--
-- So the single UPDATE the database accepts on an erasure record is its photo
-- count going from 0 to a positive number, once; after that the record is final,
-- photos_deleted included. What this does NOT cover, stated exactly:
--   * A record whose count is still 0 may have that count set once, by its own
--     tenant's application role. That is the write the erasure needs, and the
--     database cannot verify the number itself. A record whose erasure removed
--     no photo keeps 0 and stays settable once. The same role can already INSERT
--     records for its own tenant (V42) and has no cross-tenant reach.
--   * DDL and superusers: the table OWNER can drop or disable the trigger, and a
--     superuser can skip it with session_replication_role = replica. Production's
--     application role jtoye_runtime owns nothing and is not a superuser
--     (SEC-04 / #552), so neither is open to it.
--   * DELETE is unchanged: erasure_records has no DELETE policy, so under FORCE
--     RLS a DELETE matches no row for the application role.
--
-- DDL ONLY - no rows are read or written, no backfill, no data change, no role
-- and no extension (scripts/check-no-create-extension.sh). Every tenant
-- comparison goes through the safe current_tenant_id() helper, never the raw
-- GUC cast (V51 / RlsContractTest.noPolicyUsesRawTenantGucCast). Re-run safe:
-- DROP POLICY IF EXISTS then CREATE POLICY in the V51 style, CREATE OR REPLACE
-- FUNCTION, DROP TRIGGER IF EXISTS then CREATE TRIGGER.

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

CREATE OR REPLACE FUNCTION erasure_records_write_once()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.photos_deleted <> 0 THEN
        RAISE EXCEPTION 'erasure_records is write-once: record % already carries its photo count (%)',
                OLD.id, OLD.photos_deleted
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.photos_deleted <= 0 THEN
        RAISE EXCEPTION 'erasure_records is write-once: the only permitted update sets a positive photo count (record %, got %)',
                OLD.id, NEW.photos_deleted
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF (to_jsonb(NEW) - 'photos_deleted') IS DISTINCT FROM (to_jsonb(OLD) - 'photos_deleted') THEN
        RAISE EXCEPTION 'erasure_records is write-once: only photos_deleted may change (record %)',
                OLD.id
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_erasure_records_write_once ON erasure_records;
CREATE TRIGGER trg_erasure_records_write_once
    BEFORE UPDATE ON erasure_records
    FOR EACH ROW
    EXECUTE FUNCTION erasure_records_write_once();
