-- V61: Phase 33 / plan 33-02 — the offline postcode-centroid substrate for locality (CUST-01).
--
-- DDL ONLY. No data. The 1,748,230-row dataset ships as a gzipped classpath resource
-- (core-java/src/main/resources/geo/postcode-centroids.csv.gz) and is loaded at startup by
-- PostcodeCentroidImporter. Putting ~46 MB of INSERTs here instead would execute on every
-- Testcontainers integration test that spins a fresh Postgres — minutes per container, for data
-- that no unit test needs and that the test profile deliberately replaces with a 7-row fixture.
--
-- ============================================================================================
-- WHY THIS MIGRATION CREATES NO EXTENSION, AND WHY THAT IS NOT A COMPROMISE
-- ============================================================================================
-- The obvious implementation of "shops near me" is cube + earthdistance, or PostGIS. Neither is
-- available, and this was measured on the live stack inside a rolled-back transaction rather
-- than assumed:
--
--     cube           1.5   trusted = t   superuser = t
--     earthdistance  1.1   trusted = f   superuser = t
--     PostGIS        absent entirely (pg_available_extensions returns 0 rows)
--
-- Flyway runs as the spring.datasource.username role = jtoye_app, which is rolsuper = f,
-- rolbypassrls = f, and has_database_privilege('jtoye_app','jtoye','CREATE') = false.
--
-- (Written WITHOUT dollar-brace syntax on purpose. Flyway substitutes placeholders inside
-- migration SQL INCLUDING COMMENTS, so writing a property name in dollar-brace form makes the
-- whole migration fail with "No value provided for placeholder" — and it takes the entire
-- application down at startup, not just this file. Measured here twice: once naming the
-- datasource property, and again in the note explaining the first one, which reproduced the
-- fault by quoting the very syntax it was warning about.)
--
-- The measurement, as the role itself:
--
--     SET ROLE jtoye_app; <extension-creating statement for cube>;
--     ERROR:  permission denied to create
--             extension "cube"
--
-- (That is one server message, wrapped across two lines, and the statement above is described
-- rather than written. Both are deliberate: scripts/check-no-create-extension.sh scans this whole
-- directory case-insensitively and cannot tell a statement from a comment quoting one — which is
-- the right trade, because a commented-out extension statement still deserves a human look. The
-- cost is that a migration must discuss this constraint without spelling it, and this file is the
-- worked example.)
--
-- So even the TRUSTED extension fails. The available "fix" — granting jtoye_app CREATE ON
-- DATABASE — is a privilege escalation on the exact role the entire RLS wall is built around,
-- and is explicitly rejected. Plain SQL plus a Java-side bounding box (GeoBounds) is therefore
-- the answer here, not a fallback.
--
-- This constraint is NOT specific to V61: any future migration that creates an extension breaks
-- every environment, because the role cannot execute the statement at all. That invariant used
-- to be a sentence in a plan; it is now enforced across the whole migration directory by
-- scripts/check-no-create-extension.sh, wired into ci-cd.yaml.
-- ============================================================================================

-- --------------------------------------------------------------------------------------------
-- postcode_centroid — public reference data, NOT tenant-scoped.
-- --------------------------------------------------------------------------------------------
-- Deliberately has no tenant_id, no RLS policy and no _aud mirror. The postcode of a public
-- address is not tenant information, and there is no customer data here. It is exempted BY
-- ADDITION in RlsContractTest.EXEMPT_TABLES with a written justification — the schema-walk sweep
-- itself is never weakened.
--
-- The postcode IS the primary key, stored in the dataset's canonical form: UPPERCASED and
-- SPACE-STRIPPED ('SE155BS', never 'SE15 5BS'). Upstream field widths vary between 6, 7 and 8
-- characters, so a fixed-width or padding-based parser mis-keys; PostcodeGeocoder owns the
-- normalisation so there is exactly one place that can get it wrong.
CREATE TABLE IF NOT EXISTS postcode_centroid (
    postcode  TEXT             PRIMARY KEY,
    latitude  DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL
);

COMMENT ON TABLE postcode_centroid IS
    'GB postcode unit centroids in WGS84, from OS Code-Point Open under OGL v3. Public reference '
    'data: no tenant_id, no RLS, no audit mirror. Provenance, accuracy (~100 m) and the '
    'GB-only limitation are recorded in core-java/src/main/resources/geo/SOURCE.md. Loaded by '
    'PostcodeCentroidImporter; never populated by a migration.';

COMMENT ON COLUMN postcode_centroid.postcode IS
    'Uppercased, space-stripped postcode unit (SE155BS). Normalisation is owned by '
    'PostcodeGeocoder — do not hand-build this key.';

-- --------------------------------------------------------------------------------------------
-- The index 33-06's distance prefilter needs.
-- --------------------------------------------------------------------------------------------
-- shops.latitude / shops.longitude already exist (V16__public_storefront.sql); only the index is
-- new. A composite btree over both columns is what makes the leakproof bounding-box predicate
-- (GeoBounds.boxAround) cheap: PostgreSQL will only push a predicate below an RLS security
-- barrier if the operator is LEAKPROOF, which plain float8 comparison is and a distance function
-- is not. Without this index the prefilter still returns the right answer, but by sequential
-- scan — correct and slow, which is the failure mode that shows up only once there is real data.
--
-- Partial on NOT NULL: a shop with no coordinate can never satisfy a bounding-box predicate, so
-- indexing those rows only enlarges the index. Coordinates are NULL for every shop until 33-05's
-- backfill runs, and permanently NULL for Northern Ireland vendors, whom Code-Point Open does not
-- cover at all.
CREATE INDEX IF NOT EXISTS idx_shops_lat_lon
    ON shops (latitude, longitude)
    WHERE latitude IS NOT NULL AND longitude IS NOT NULL;
