---
phase: quick/260930-bvp
plan: 01
status: complete
subsystem: gdpr
tags: [gdpr, article-17, rls, azure-blob, flyway, v67, issue-764]
requires: []
provides:
  - "V67 reviews_tenant_update + erasure_records_photo_count_update policies"
  - "ReviewRepository.findByTenantIdAndCustomerEmail (email-only finder deleted)"
  - "GdprService post-commit photo erasure (afterCommit + REQUIRES_NEW write-once count) and ErasureOutcome"
  - "GdprErasureReviewRlsIntegrationTest (NOSUPERUSER + Azurite, 3 arms)"
affects: [GdprController, DsarFanoutWorker (via eraseSubjectByDigest), docs/metrics.json, CLAUDE.md, AGENTS.md, README.md, docs/CHANGELOG.md]
tech-stack:
  added: []
  patterns: ["TransactionSynchronization.afterCommit with tenant pin + restore", "TransactionTemplate PROPAGATION_REQUIRES_NEW for post-commit writes", "write-once RLS UPDATE policy (USING ... AND col = 0)"]
key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureReviewRlsIntegrationTest.java
    - core-java/src/main/resources/db/migration/V67__reviews_update_policy_and_erasure_photo_count.sql
  modified:
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
decisions:
  - "V67 reviews UPDATE policy pins current_tenant_id() in BOTH USING (excludes cross-tenant PUBLISHED rows) and WITH CHECK (no re-stamping); FOR UPDATE only"
  - "ErasureRecord stays inside the erasure transaction with photos_deleted=0; the true WR-02 count is written once post-commit via REQUIRES_NEW, write-once at the DB"
  - "eraseCustomerData returns ErasureOutcome; photo count throws IllegalStateException until the post-commit step has run; ErasureResponse unchanged (no OpenAPI diff)"
  - "Review lookup on BOTH erase and export carries an explicit tenant predicate; email-only finder deleted"
  - "Dev runtime deliberately NOT rebuilt (DD-5): runtime parity is NOT claimed"
metrics:
  duration: "46 min (2026-09-30T08:03:39Z to 08:49:43Z)"
  completed: 2026-09-30
actuals:
  tokens: 38187
  tasks: 3
  commits: 4
plan_head_before: c5d16ff66940d4b7651c205d8d424bd2962711e4
plan_head_after: bba33fbe11db0f74eedbe7d662e913ec53cdc0e1
---

# Phase quick/260930-bvp Plan 01: GDPR erasure for reviewers (#764) Summary

A customer who left a review can now be erased under the real NOSUPERUSER RLS posture. Four changes do it: V67 adds a tenant-pinned UPDATE policy on `reviews`; the review lookup carries a tenant predicate on both erase and export; photos are deleted from Blob only after the erasure commits, under the erasing tenant; and the true WR-02 photo count is written once, post-commit, onto the evidence row. All three #764 arms were RED on the unfixed tree. Four break arms each turned their named arm RED, and the closing clean run is GREEN.

## Commits

| # | Hash | Subject |
|---|------|---------|
| 1 | e4e3b4df | test(260930-bvp): NOSUPERUSER erasure test for reviewers, cross-tenant and rollback arms (#764) |
| 2 | fe2286b8 | fix(260930-bvp): tenant-scoped UPDATE policy on reviews and tenant-predicated review lookup (#764) |
| 3 | abe00153 | fix(260930-bvp): delete erased reviewers' photos only after commit and record the true count once (#764) |
| 4 | bba33fbe | docs(260930-bvp): schema V67, metrics and changelog for the #764 erasure fix |

The test commit precedes both fix commits. All messages were read back with `git log -1 --format=%B`, and none carries an attribution line.

## Task 1: RED on the unfixed tree (test committed alone at e4e3b4df, run before any production change)

`./gradlew :core-java:integrationTest --rerun --tests 'uk.jtoye.core.gdpr.GdprErasureReviewRlsIntegrationTest'` returned rc=1. The fresh XML reported `tests=3 failures=3 errors=0` at 2026-09-30T08:06:20Z. Verbatim, per arm:

- **Arm A, erasureAnonymisesReviewAndDeletesOwnTenantPhotosUnderRls:** FAILED
  `org.springframework.orm.ObjectOptimisticLockingFailureException: Batch update returned unexpected row count from update [0]; actual row count: 0; expected: 1; statement executed: update reviews set comment=?,customer_email=?,customer_name=?,delivery_rating=?,food_rating=?,order_id=?,photo_urls=?,shop_id=?,tenant_id=? where id=?`
  Caused by: `org.hibernate.StaleStateException` (same message).
- **Arm B, erasureLeavesAnotherTenantsPublishedReviewUntouched:** FAILED with the identical `ObjectOptimisticLockingFailureException` / `StaleStateException`. It failed after its precondition passed (A's session saw 2 reviews under the shared email).
- **Arm C, rolledBackErasureDeletesNoPhoto:** FAILED
  `[the photo after an erasure whose transaction ROLLED BACK (escaped: org.springframework.orm.ObjectOptimisticLockingFailureException: Batch update returned unexpected row count from update [0]; actual row count: 0; expected: 1; ...)] expected: 200 but was: 404`
  This is the #764 data-destruction case itself: the erasure rolled back and the photo was gone anyway.

This confirms the exception the issue had only inferred: Hibernate's zero-row batch-update check, `StaleStateException` wrapped as `ObjectOptimisticLockingFailureException`.

## Task 1: after V67 + tenant-predicated finder (fe2286b8)

The verify block printed `unit=0 armsAB=0 armC=1(expect non-0) oldFinder=1(expect 1) placeholder=1(expect 1)`, with verdict rc=0.

- GdprServiceTest: `tests=8 failures=0 errors=0`. That includes the new `#764: export and erase look reviews up in the CUSTOMER'S tenant, never by email alone`.
- Arms A and B: `tests=2 failures=0`.
- Arm C, still RED as planned (Task 2's RED): `[the photo after an erasure whose transaction ROLLED BACK (escaped: null)] expected: 200 but was: 404`. The erasure now completed inside the rolled-back transaction, and the synchronous delete still destroyed the photo.
- Old-finder gate:
  - fail direction, before step 5: rc=0, output `ReviewRepository.java:1`, `GdprService.java:2`, `GdprServiceTest.java:5`;
  - after the fix: rc=1, empty output.
- V67 placeholder gate:
  - on V67: rc=1, empty output;
  - fail direction on the scratch control file `x = "${placeholder}"`: `rg -uu -F -c` returned `1`, rc=0.
- The fresh-report `find` listed both `TEST-uk.jtoye.core.gdpr.GdprServiceTest.xml` and `TEST-uk.jtoye.core.gdpr.GdprErasureReviewRlsIntegrationTest.xml`.
- Tracer gate: the `<verify>` above is the end-to-end re-run, and it passed before expansion into Task 2.

## Task 2: post-commit photo erasure (abe00153)

**Unit RED, before implementation.** `./gradlew :core-java:test --rerun --tests 'uk.jtoye.core.gdpr.GdprServiceTest'` returned rc=1 with a compile failure of 11 distinct errors, for example:

- `GdprServiceTest.java:225: error: cannot find symbol ... symbol: method recordPhotosDeleted(UUID,UUID,int) location: interface ErasureRecordRepository`
- `GdprServiceTest.java:411: error: invalid method reference ... cannot find symbol symbol: method toResponse()`

**Unit GREEN.** `:core-java:test --rerun --tests 'uk.jtoye.core.gdpr.*'` returned rc=0:

- GdprServiceTest: `tests=14 failures=0 errors=0`. The new tests are:
  - deferred path;
  - afterCommit tenant pin plus a true count written once through REQUIRES_NEW (verified on `getTransaction` propagation);
  - zero-deleted means no write;
  - rollback deletes nothing;
  - the caller's TenantContext is restored (both a prior value and none);
  - a count-write failure is contained and the tally stays settled.
- GdprControllerTest: `tests=5 failures=0`.

**Integration GREEN.** `:core-java:integrationTest --rerun --tests 'uk.jtoye.core.gdpr.*'` returned rc=0, with fresh reports:

| Class | tests | failures |
|---|---|---|
| GdprErasureReviewRlsIntegrationTest | 3 | 0 |
| GdprErasureIntegrationTest | 4 | 0 |
| DsarFanoutIntegrationTest | 11 | 0 |
| DsarVerificationIntegrationTest | 8 | 0 |
| DsarIntakeIntegrationTest | 13 | 0 |
| DsarSubjectAndGlobalRateLimitIntegrationTest | 2 | 0 |

`rg -uu -n 'PROPAGATION_REQUIRES_NEW|registerSynchronization'` on GdprService returned rc=0, matching lines 80 and 297.

### Break arms (clean → arms → clean, on committed abe00153)

Each arm ran the whole class. Each file was restored with `git restore --source=HEAD --staged --worktree`, and the restore was verified with `sha256sum -c` before the next arm.

| Arm | Mutation | Named arm | Verbatim failure |
|---|---|---|---|
| 1 | Removed the `reviews_tenant_update` statement from V67 | A: RED (B also RED; C RED on its reachedEnd precondition) | A: `ObjectOptimisticLockingFailureException: Batch update returned unexpected row count from update [0]; actual row count: 0; expected: 1; statement executed: update reviews set ... where id=?`. C: `[PRECONDITION: the erasure body ran to completion inside the rolled-back transaction (escaped: ...ObjectOptimisticLockingFailureException...)] Expecting value to be true but was false`, and its photo assertion passed: the post-commit move alone kept the photo even though the erasure failed |
| 2 | Re-added an email-only `findByCustomerEmail(String)` and called it from the erasure | B: RED (A and C green) | `ObjectOptimisticLockingFailureException: Batch update returned unexpected row count from update [0]; actual row count: 0; expected: 1; ...`. V67's USING clause refused the cross-tenant row at the database, which is DD-1's defence in depth |
| 3 | `if (false /* BREAK ARM 3 */ && isSynchronizationActive())`, so the step always runs inline | C: RED (A also RED) | C: `[the photo after an erasure whose transaction ROLLED BACK (escaped: null)] expected: 200 but was: 404`. A: `[the DURABLE photo count, read back in a fresh tenant-A transaction] expected: 2L but was: 0L` (the inline REQUIRES_NEW UPDATE cannot see the uncommitted record) |
| 4 | Removed the `setPropagationBehavior(PROPAGATION_REQUIRES_NEW)` line | A: RED (B and C green) | `[the DURABLE photo count, read back in a fresh tenant-A transaction] expected: 2L but was: 0L` |

The closing clean run returned `close_rc=0`, fresh XML `tests=3 failures=0 errors=0` at 08:18:04Z. `git status --short` showed only the four pre-existing untracked paths.

sha256 restore table (before arms vs after the closing run; `diff` rc=0):

| File | Before | After |
|---|---|---|
| GdprService.java | 0386c721087258c8516454fec5810bd32a616e0618fa004e9c13ad9e808a7b69 | 0386c721087258c8516454fec5810bd32a616e0618fa004e9c13ad9e808a7b69 |
| ReviewRepository.java | 78245745ddd897f2048589aa8bc862727ef546385caceb0bd28c515af52237e6 | 78245745ddd897f2048589aa8bc862727ef546385caceb0bd28c515af52237e6 |
| V67__reviews_update_policy_and_erasure_photo_count.sql | a4ff5fb12f38c3847a16e277ac3e7ff590cac6936363d7c2820a05141fcc7838 | a4ff5fb12f38c3847a16e277ac3e7ff590cac6936363d7c2820a05141fcc7838 |

### ErasureResponse unchanged

`git diff c5d16ff6 -- GdprController.java` shows only the `eraseData` body: one comment line, and `.toResponse()` added. The record check `rg -c '^[+-].*(record ErasureResponse|int photosDeleted|UUID recordId|auditRowsScrubbed)'` over that diff returned rc=1 (no match). Its fail direction, the same pattern against a synthetic diff that changes `int photosDeleted`, returned count 1, rc=0. The full integration suite's `OpenApiSnapshotTest` is green, so there is no OpenAPI diff.

## Task 3: doc gates, full suites, branch and runtime

**Doc gates, both directions:**

| Gate | Before edits | After `--write`, prose stale | Final |
|---|---|---|---|
| `scripts/docs-freshness.sh` | rc=1. Committed: 1972 @Test / 303 files / schema 66 / total 4137. Computed from source: 1982 / 304 / **67** / 4147 | — | rc=0 `docs-freshness OK: metrics match source (total logical invocations: 4147).` |
| `scripts/check-doc-metrics.sh` | rc=0 (see deviation 3: the prose still matched the stale manifest) | rc=1, with 13 FAIL lines, e.g. `FAIL: CLAUDE.md [schema_version]: doc says 66, docs/metrics.json says 67`, `FAIL: README.md [java_test_methods]: doc says 1972, docs/metrics.json says 1982` | rc=0 `PASS: all 37 prose metric claim(s) across 3 doc(s) match docs/metrics.json.` |
| `rg 'Current schema version: V67 \(V67' CLAUDE.md AGENTS.md` | rc=1, empty | — | rc=0, 2 matches (AGENTS.md:108, CLAUDE.md:109) |

The `docs/metrics.json` diff is exactly what was expected:

- java_test_methods 1972 → 1982 (+10 = 1 + 6 new unit tests, 3 new integration tests);
- java_test_files 303 → 304;
- schema_version 66 → 67;
- total_logical_invocations 4137 → 4147.

Nothing else moved.

**Migration count.** `ls` counts 67 versioned files, so CLAUDE.md:303 and AGENTS.md:302 now read "67 Flyway migrations, V1 through V67".

**Full suites** (marker touched before both runs; Gradle `--rerun`):

- `./gradlew :core-java:test --rerun` returned rc=0 (BUILD SUCCESSFUL in 1m 5s).
- `./gradlew :core-java:integrationTest --rerun` returned rc=0 (BUILD SUCCESSFUL in 28m 1s). It includes RlsContractTest, ReviewsRlsPolicyIntegrationTest and OpenApiSnapshotTest; fresh XMLs exist for all three.
- Fresh-report totals: `test` had 166 reports, `{tests: 1311, failures: 0, errors: 0, skipped: 1}`. `integrationTest` had 150 reports, `{tests: 741, failures: 0, errors: 0, skipped: 1}`. The totals rc was 0.
- Before/after in `docs/metrics.json` terms: 1972 → 1982 Java `@Test` methods. A full-suite execution count on the pre-change tree was not run, so no execution-level "before" is claimed.

**Branch.** `git fetch origin`, then `scripts/check-branch-behind-base.sh`, returned rc=1: `FAIL: HEAD is 1 commit(s) behind origin/main (and 3 ahead)` (at the time of the check; the docs commit made it 4 ahead). The commit behind is `239841b7 fix(ops): terminal-state deferrals expired 2026-09-30 ... (#768)`, which touches docs/CHANGELOG.md, docs/ops/terminal-states.yaml and docs/runbooks/terminal-states.md. `git merge-tree --write-tree HEAD origin/main` now reports `CONFLICT (content): Merge conflict in docs/CHANGELOG.md`, because both branches add an entry directly under `## [Unreleased]`. Per instructions, I did not merge and did not touch terminal-states.yaml.

**Runtime (DD-5).** The compose stack is running (all 10 containers up and healthy). `scripts/check-runtime-freshness.sh` returned rc=1, `FAIL: 2 of 4 running built service(s) do not match the source tree (0 unverified)`:

- core-java: `[image-not-rebuilt]`, tagged 2026-09-29 17:38:45 UTC, older than abe00153. This is expected.
- frontend: `[image-not-rebuilt]`, older than c5d16ff6 (#763). This predates this plan.

It was **not rebuilt**: a rebuild would apply V67 to the dev database and lock its Flyway checksum while review can still change the migration. **Runtime parity is NOT claimed.**

## Deviations from Plan

1. **[Rule 3 - Blocking] Seeding and reads go through `Session.doWork` on the Hibernate transaction's own connection, not JdbcTemplate + TenantSetLocalAspect.** Customers are seeded by SQL, not `customerRepository.saveAndFlush`. `DsarFanoutIntegrationTest` records a measured failure of the JdbcTemplate shape (an autocommit connection reverts the transaction-local `set_config`), so the test copies its proven `pinnedUpdate`/`pinnedCount` recipe. Files: GdprErasureReviewRlsIntegrationTest. Commit e4e3b4df.
2. **[Rule 3] The test calls `gdprService.eraseCustomerData` inline with `var`** (TenantContext set and cleared around it, and `@AfterEach` clears on throw), instead of a helper typed `ErasureOutcome`. That way the same source compiled against the unfixed tree for the RED run.
3. **check-doc-metrics was rc=0 before the edits, not non-zero.** It compares prose to the manifest, and the manifest had not been regenerated yet. Its natural RED appears after `docs-freshness.sh --write` (rc=1, 13 FAIL lines), which is the recorded fail direction.
4. **Added `@Import(NoScheduledTriggersTestConfig.class)`** so that no `@Scheduled` worker (e.g. the DSAR fan-out) runs against the downgraded role during the arms.
5. **Break arms 1 and 3 turned additional arms RED** beyond the named one. Arm 1 turned all three. Arm 3 also turned arm A, because the inline REQUIRES_NEW count write cannot see the uncommitted record. Each named arm did go RED, so no arm was vacuous.
6. **`@AsSystemHarness` was not needed.** GdprService never calls the shop-scope gate.
7. **Python.** `/usr/bin/python3` against a scratch script and a heredoc edit was refused by `block-base-python`, so all repo-side Python ran in the `jtoye-ops` conda env, as instructed.

## Threat model

Every `mitigate` entry is implemented and proven:

- T-764-01: arm B plus break arm 2.
- T-764-02: V67 USING/WITH CHECK.
- T-764-03: write-once USING `photos_deleted = 0`, and the service asserts 1 row (ERROR otherwise).
- T-764-04: arm C plus break arm 3.
- T-764-05: TenantContext is pinned and restored (unit test), and Bx survives in arm A.
- T-764-07: logs carry counts and ids only, with no email and no URL. The ERROR path logs the exception class name, not its message.

No new surface outside the threat model.

## Known Stubs

None.

## Orchestrator hand-offs

1. **Merge origin/main before the PR** (branch is 1 behind: #768). Resolve the `docs/CHANGELOG.md` conflict by KEEPING BOTH entries under `[Unreleased]`, #768's and this one. "Take theirs" deletes this entry. After the merge, re-run `scripts/docs-freshness.sh` and `scripts/check-doc-metrics.sh`.
2. Re-run `scripts/check-branch-behind-base.sh` immediately before `gh pr create`; expect rc=0.
3. After `gh pr create`, append `(#<PR>)` to the heading `### GDPR erasure works for customers who left a review — 2026-09-30`. Then run `scripts/check-changelog-cites-pr.sh --pr <PR> --title '<title>'` (expect 0) and the absent control `--pr 999999` (expect 1).
4. The PR body carries no AI-attribution lines. Read it back after posting.
5. Rebuild the runtime (`docker compose -f docker-compose.full-stack.yml up -d --build core-java`, plus frontend, which is already stale since #763) once review settles V67. Then run `scripts/check-runtime-freshness.sh`.

## Self-Check: PASSED

- FOUND: core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureReviewRlsIntegrationTest.java
- FOUND: core-java/src/main/resources/db/migration/V67__reviews_update_policy_and_erasure_photo_count.sql
- FOUND commits: e4e3b4df, fe2286b8, abe00153, bba33fbe (`git rev-list --count c5d16ff6..HEAD` = 4)
