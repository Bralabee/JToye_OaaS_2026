---
phase: 36-azure-blob-storage-throughout
plan: 14
subsystem: storage
tags: [azure-blob, residue, BLOB-09, comments, test-literals, azurite, falsifiability]
status: complete

requires:
  - phase: 36-01
    provides: the functional swap to the Blob seam, which left prose and literals behind by design
  - phase: 36-06
    provides: StorageStartupValidator and the storage shape rules (their files carry no residue)
  - phase: 36-07
    provides: the D-09 guard and the Azurite pipeline ITs, plus the last recorded full-suite totals (1297 / 738)
provides:
  - core-java free of retired-store residue under both plan greps and a stricter case-insensitive prose grep, V42 excepted
  - neutral stub host http://store/ and the Azurite origin http://localhost:10000/devstoreaccount1/jtoye-images/ in the test literals
  - a corrected MediaProperties Javadoc (quarantine bytes are private since T-36-01, not anonymously readable)
affects: [36-16, 36-17]

actuals:
  tokens: 11190        # chars/4 over git diff f7dff060..a3a213a8 (44762 chars), before this SUMMARY
  tasks: 3
  commits: 3           # MEASURED: git rev-list --count f7dff060..HEAD, before the SUMMARY commit
plan_head_before: f7dff06007d46f4f63ae3e6584519b6583f82e25

tech-stack:
  added: []
  patterns:
    - "A comment-only claim about Java source is proven by comparing comment-stripped token streams against the base, not by a diff-line regex that cannot see trailing comments"
    - "Stub literals that nothing asserts are renamed freely; asserted literals are renamed as input/expectation pairs and then broken once to show the assertion still fires"

key-files:
  created: []
  modified:
    - core-java/src/main/java/uk/jtoye/core/media/MediaProperties.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaProcessingWorker.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaBackfillMigrationIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaAssetDtoMappingTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductImageDeleteIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/gdpr/GdprServiceTest.java
    - core-java/build.gradle.kts

key-decisions:
  - "MediaProperties' retention Javadoc was rewritten, not renamed: it said quarantine objects are anonymously readable by key, which stopped being true when T-36-01 moved them to the private container"
  - "The plan's awk comment-only check reads 2 on the correct tree (a trailing // comment on MediaAssetService:326); it is recorded as written and a comment-stripped token comparison is used as the proof"
  - "Two residue hits outside the plan's file list (build.gradle.kts:287, MediaQuarantineRetentionSweepTest:30) were fixed, because Task 3 requires rc=1 over all of core-java"
  - "BLOB-01 marked complete: this plan closes its last clause (no retired SDK named in main code, now down to comments), and 36-06's open real-boot item D6 is tied to BLOB-02 by 36-07"

patterns-established:
  - "Residue greps over core-java run case-insensitively for prose too ((?i)(?<!UI-SPEC )\\bs3\\b), because 's3:GetObject' is invisible to the case-sensitive form"

requirements-completed: [BLOB-01]   # plan declares [BLOB-09, BLOB-01]; requirements.ready-ids: BLOB-01 ready, BLOB-09 blocked (36-10/12/15/16/17 open)

coverage:
  - id: D1
    description: "Ten core-java main files describe Azure Blob / the object store instead of the retired store; no code token, runtime literal or log format changed; V42 untouched"
    requirement: BLOB-09
    verification:
      - kind: other
        ref: "git grep residue r1/r2/r3 over core-java/src/main (V42 excluded) -> rc=1 each; planted MinIO/S3 -> rc=0"
        status: pass
      - kind: other
        ref: "comment-stripped token comparison, 10/10 files SAME vs f7dff060; changed log literal and changed code token -> DIFF rc=1"
        status: pass
      - kind: other
        ref: "./gradlew :core-java:compileJava rc=0 (class mtime after source mtime)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Media-package tests carry http://store/ stubs and the Azurite origin; @Test counts unchanged in all 13 files; asserted literals still fail when broken"
    requirement: BLOB-09
    verification:
      - kind: integration
        ref: "focused :test + :integrationTest uk.jtoye.core.media.* rc=0 (5 unit + 20 IT classes, 0 failures)"
        status: pass
      - kind: unit
        ref: "MediaAssetDtoMappingTest break arm: 1 failure on the target assertion, restored by hash"
        status: pass
      - kind: integration
        ref: "MediaBackfillMigrationIntegrationTest break arm (origin through /products/seed/): expected 3 but was 0, restored by hash"
        status: pass
    human_judgment: false
  - id: D3
    description: "The remaining five test files and build.gradle.kts renamed; both plan greps and the stricter prose grep return rc=1 over all of core-java; the four UI-SPEC S3 lines are byte-identical to base"
    requirement: BLOB-09
    verification:
      - kind: other
        ref: "git grep r1/r2/r3 over core-java rc=1; planted residue in a DisplayName and build.gradle.kts -> rc=0"
        status: pass
      - kind: integration
        ref: "focused Task 3 run (GdprServiceTest 6, DemoImageManifestTest 5, ProductImageDelete 3, ShopImageCrossTenant 7, SystemPrincipalGuardTest 8), 0 failures"
        status: pass
    human_judgment: false
  - id: D4
    description: "No behaviour moved: full unit and integration suites reproduce the last recorded totals exactly"
    requirement: BLOB-01
    verification:
      - kind: unit
        ref: "./gradlew :core-java:test --rerun-tasks: 166 classes, 1297 tests, 0 failures, 0 errors, 1 skipped"
        status: pass
      - kind: integration
        ref: "./gradlew :core-java:integrationTest --rerun-tasks: 149 classes, 738 tests, 0 failures, 0 errors, 6 skipped"
        status: pass
    human_judgment: false

duration: 41min
completed: 2026-09-29
---

# Phase 36 Plan 14: core-java Residue Removal Summary

**core-java no longer names the retired object store anywhere except the applied V42 migration. Twenty-seven source and test files and one build-file comment changed, 75 lines in, 74 out. Every main-code change is comment-only, proven by a comment-stripped token comparison against the base. The full suites reproduce the last recorded totals exactly: 1297 unit, 738 integration, 0 failures.**

## Performance

- **Duration:** 41 min
- **Started:** 2026-09-29T06:28:05Z
- **Completed:** 2026-09-29T07:09:53Z
- **Tasks:** 3 of 3
- **Files modified:** 29 (10 main, 18 test, 1 build script)

## Accomplishments

- **Main code (Task 1).** Ten files now say "Blob", "object store" or "storage" where they said MinIO or S3. Every claim is kept: a physical delete still happens only at ref-count 0, and a transient read failure still retains the bytes. In `MediaProperties` the comment itself was wrong: it said quarantine objects are anonymously readable by key. Since T-36-01 they sit in the private `jtoye-quarantine` container, so the Javadoc now says what the 72 h horizon still bounds.
- **Media tests (Task 2).** Stubs return `http://store/...`. The DTO-mapping inputs and expectations were renamed as pairs. `MediaBackfillMigrationIntegrationTest.PUBLIC_URL` is now the Azurite origin. V53 extracts keys by tenant position, so the rename is neutral (evidence below).
- **Remaining tests and the build file (Task 3).** The GdprServiceTest display name now reads "deletes stored photos". The product-delete test seeds and expects `http://store/...`. The 27-04 measurement note in `build.gradle.kts` now names "the old SDK's object-store connection reaper".
- **No behaviour moved.** The full suites match the 36-07 totals exactly, and nothing under `core-java/src/test` changed between that run and this plan's base.

## Task Commits

1. **Task 1: Main-code comments describe Blob** - `eca88544` (docs)
2. **Task 2: Media-package test literals** - `f8978eb8` (test)
3. **Task 3: Remaining test literals, display names, build-file note** - `a3a213a8` (test)

**Plan metadata:** this SUMMARY commit (docs)

## Files Created/Modified

- `core-java/src/main/java/uk/jtoye/core/{gdpr/GdprService, media/MediaAsset, media/MediaAssetService, media/MediaNormalizer, media/MediaProcessingWorker, media/MediaProperties, media/MediaQuarantineRetentionSweep, media/ProductMediaRepository, product/ProductService, shop/ShopService}.java`: comments only
- `core-java/src/test/java/uk/jtoye/core/media/*` (13 files, including `MediaQuarantineRetentionSweepTest` outside the plan's list): stub literals, the backfill origin, prose
- `core-java/src/test/java/uk/jtoye/core/{dev/DemoImageManifestTest, gdpr/GdprServiceTest, product/ProductImageDeleteIntegrationTest, security/access/SystemPrincipalGuardTest, shop/ShopImageCrossTenantIntegrationTest}.java`
- `core-java/build.gradle.kts`: one comment line (outside the plan's list)

## Evidence, both directions

Scratch: `/tmp/claude-3614/`. Base `f7dff060`. The residue greps (V42 excluded):
- **r1:** the plan's token grep (`minio|awssdk|…|localhost:9000|storage\.s3|S3_[A-Z]`, case-insensitive)
- **r2:** the plan's prose grep (`(?<!UI-SPEC )\bS3\b`)
- **r3:** mine, strictly stronger: r2 made case-insensitive

| Criterion | Pass direction (real tree) | Fail direction (broken input) |
|---|---|---|
| Positive control, pre-edit | n/a | before any edit, over core-java: r1 rc=0 with **62** lines, r2 rc=0 with **14**, r3 rc=0 with **16** (r3 also catches `MediaProperties:99-100` `s3:GetObject`/`s3:ListBucket`, which r2 cannot see). All lists saved. |
| T1 residue over `core-java/src/main` | r1 rc=1, r2 rc=1, r3 rc=1, no lines | ARM C: `MinIO` planted in the `MediaAssetService:326` comment gives r1 rc=0 and prints the line. `S3` planted gives r2 rc=0 and prints the line. |
| T1 negative control | `git grep 'UI-SPEC S3' -- core-java/src/main` gives 3 lines, unchanged | ARM F: with the lookbehind dropped, the four UI-SPEC lines surface (rc=0), so the lookbehind is what excludes them |
| T1 compile | `compileJava` rc=0; `MediaProperties.class` 07:28:40 is newer than the source's 07:28:26, so it really recompiled | n/a |
| T1 migrations untouched | `git diff --name-only f7dff060..HEAD -- core-java/src/main/resources/db/migration/` prints nothing, rc=0; `sha256sum -c` on V42 is OK (`fc133df3…`) | ARM D: a line appended to V42 makes the same diff (worktree form) print the V42 path |
| T1 comment-only, **plan awk as written** | prints **2** on the correct tree. Both lines are the old and new side of `MediaAssetService:326`, where the rewritten text is a trailing `//` comment on an unchanged code line. **The criterion cannot read 0 on a correct tree.** Recorded, not reported as satisfied. | ARM B: the plan awk reads 4 with a code change |
| T1 comment-only, **stronger replacement** | comment-stripped token streams of all 10 changed main files against `f7dff060`: 10/10 SAME, rc=0 | ARM A: a runtime log literal changed gives DIFF, rc=1. ARM B: `refs > 0` changed to `refs >= 0` gives DIFF, rc=1. |
| T2 media residue | `git grep -i 'minio\|localhost:9000'` over the media tests gives rc=1; r3 over them gives rc=1 | covered by the pre-edit list (every one of those files had hits) |
| T2 @Test counts | 13/13 SAME against `git show f7dff060:<file>` (each show rc=0) | ARM H: one `@Test` removed from GdprServiceTest makes the comparison report CHANGED, base=6 now=5 |
| T2 V53 origin independence | V53 selects `v_pos := position(t.id::text \|\| '/' IN p.image_url)` and `v_key := substring(p.image_url FROM v_pos)` (the gallery loop is the same). The only origin-sensitive predicate is `position('/products/seed/' IN image_url) = 0`, and the Azurite origin does not contain it. | Backfill ARM: `PUBLIC_URL` set to `…/jtoye-images/products/seed/` fails `[tenant1 main: 1 primary + 2 gallery] expected: 3 but was: 0`, so the test still reads the origin through V53's only origin-sensitive path |
| T2 renamed assertions still fire | DTO mapping 11/11 green | DTO ARM: expectation `x.webp` changed to `BROKEN.webp` fails `flaggedActiveAssetMapsFlaggedTrueWithDerivativeUrls` (`expected … BROKEN.webp but was … x.webp`). Restored; sha256 before and after equal. |
| T2 focused run | rc=0; `:test` and `:integrationTest` both executed (not UP-TO-DATE); media: unit 5 classes and IT 20 classes, 0 failures, 0 errors | the arms above |
| T3 residue over all of core-java | r1 rc=1, r2 rc=1, r3 rc=1 | ARM E: "S3" planted in the GdprServiceTest DisplayName and "S3/MinIO" in `build.gradle.kts:287`. r1 rc=0 catches the build file. r2 rc=0 catches both. r1 and r2 are complementary. |
| T3 UI-SPEC control | `git grep 'UI-SPEC S3' -- core-java` diffs to empty against the base list (4 lines) | ARM G: a planted `(UI-SPEC S3)` gives r2 rc=1 (correctly ignored); the same spot as `(S3)` gives r2 rc=0 |
| T3 @Test counts | 5/5 SAME (5, 6, 3, 8, 7) | ARM H |
| T3 renamed assertion still fires | ProductImageDelete 3/3 green | `containsExactly("…/g2.jpg")` changed to `BROKEN.jpg` fails `removeAdditionalImageReleasesCorrectGalleryRow`. Restored by hash. |
| Full suites | `:core-java:test :core-java:integrationTest --rerun-tasks` gives `BUILD SUCCESSFUL in 31m 13s`, rc=0. **unit: 166 classes, 1297 tests, 0 failures, 0 errors, 1 skipped. integration: 149 classes, 738 tests, 0 failures, 0 errors, 6 skipped.** 0 of the XMLs predate the run. | Expected totals derived independently: 36-07 recorded 1297 / 738 at `e02b1ba0`, and `git diff --stat e02b1ba0..f7dff060 -- core-java/src/test core-java/build.gradle.kts` is empty. The static count over all test files is 1979 annotations in 317 files at both base and HEAD, so the delta is **0**, as intended. |
| env contract (inherited, keep green) | `k8s/scripts/check-env-contract.sh` rc=0, PASS | n/a (regression only) |

Every break arm ran clean → arm → clean. Restores were verified by sha256 over the touched files (equal before and after), and `git status --short core-java` showed 0 dirty files after each one.

**Search scope.** `git grep` sees tracked files. `rg -uu` over core-java also finds hits in `.gradle-local/` (1172, the Gradle dependency cache), `.ua/` (183, an analysis-tool cache) and the build outputs. All three are git-ignored and generated, and the 36-16 gate scans tracked files. core-java has no untracked, non-ignored files (`git status --short --ignored core-java`), so dropping those directories cannot change the answer.

**Hits deliberately left:**
- `WebhookSsrfResolverTest:51` "Azure/AWS/GCP metadata": about the cloud metadata IP, not the store
- `build.gradle.kts:289,292` "AWS-SDK" / "AWS SDK": the 27-04 measurement's own history. Neither plan grep nor any 36-16 R-1/R-2 token matches them.
- `V42__gdpr_erasure_completeness.sql:6`: an applied migration; 36-16 allowlists it

## Decisions Made

See key-decisions in the frontmatter. In short:
- The `MediaProperties` Javadoc was corrected, not just renamed.
- The plan's awk criterion is recorded as vacuous-by-construction and replaced by the token comparison.
- The two off-list residue hits were fixed.
- BLOB-01 was marked complete.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Two residue hits outside `files_modified`**
- **Found during:** pre-edit residue scan
- **Issue:** `core-java/build.gradle.kts:287` ("S3/MinIO connection reaper") and `MediaQuarantineRetentionSweepTest.java:30` ("S3 deleted a missing key") are both hits under r1/r2. Task 3 requires rc=1 over all of core-java.
- **Fix:** "AWS SDK v2's object-store connection reaper" and "The retired store deleted a missing key successfully". Both historical claims are unchanged.
- **Verification:** r1/r2/r3 rc=1 over core-java. ARM E fires on the build file.
- **Committed in:** `a3a213a8` (build.gradle.kts), `f8978eb8` (sweep test)

**2. [Rule 1 - Bug, documentation] MediaProperties described a quarantine exposure that no longer exists**
- **Found during:** Task 1. The plan's greps are case-sensitive for prose and do not see `s3:GetObject`; the file was in the plan's list anyway.
- **Issue:** the comment said quarantine objects are anonymously readable by key. `StorageService` routes quarantine keys to the private container (T-36-01), so that is false under Blob.
- **Fix:** the comment now gives the history and the current fact: the horizon bounds the retention of raw, unvalidated bytes, not an anonymous-read window. I also added the case-insensitive r3 as a standing check.
- **Committed in:** `eca88544`

**3. [Criterion defect] The awk "comment-only" check cannot read 0 on a correct tree**
- **Issue:** its regex treats any line that does not start with a comment marker as code, and `MediaAssetService:326` carries its rewritten text as a trailing `//` comment on a code line. The plan's own action names that sentence as one to keep.
- **Handling:** the awk result (2) is recorded as it came out. The comment-stripped token comparison replaces it: 10/10 SAME, and it fails on a changed literal and on a changed code token.

**4. [Verify defect] The Task 3 verify ran `SystemPrincipalGuardTest` under a task that can never execute it**
- **Issue:** the class is `@Tag("testcontainers")`, and `:test` excludes that tag. The plan's command passed only because the other two filters matched, and no XML appeared for the class. The plan's own `fails_when` (a missing XML) counts that as a failure.
- **Handling:** re-ran it under `:core-java:integrationTest`: 8 tests, 0 failures. It also ran in the full suite.

---

**Total deviations:** 4 (1 blocking scope gap, 1 documentation correctness fix, 2 plan-criterion defects replaced by stronger forms)
**Impact on plan:** there is no scope creep. Every edit is still a comment or a test literal. The two criterion defects are recorded with their stronger replacements.

## Issues Encountered

- The full integration run leaves its XMLs until the task ends, so for ~30 min the only integration XML on disk was the stale failing report from the product-delete break arm (07:38). The final totals were read only after the run, with a check that no XML predates the run start (0 stale).

## TDD Gate Compliance

Not applicable: `type: execute`, and no task carries `tdd="true"`. This plan adds no behaviour; the fail direction of every renamed assertion is shown above instead.

## Requirements

- **BLOB-01 marked complete.** `requirements.ready-ids` reports it ready. This plan closes its last clause: the retired SDK is no longer named even in main-code comments. The other clauses were proven by 36-01 and 36-06: Blob SDK behind the unchanged surface, AWS SDK absent from the shipped runtime classpath, one auth-mode switch, and fail-at-startup via the shape rules and the SpringApplication arms. 36-06's D6 (a real runtime boot) is still open, but 36-07 tied it to BLOB-02, which stays open.
- **BLOB-09 stays open.** It is blocked by 36-10/12/15/16/17. This plan delivers its core-java half.

## User Setup Required

None. No external service configuration is required.

## Next Phase Readiness

- 36-16 can allowlist exactly one core-java path: `core-java/src/main/resources/db/migration/V42__gdpr_erasure_completeness.sql`. Its R-1/R-2 tokens find nothing else in core-java.
- Inherited red, unchanged: `scripts/docs-freshness.sh` (owned by 36-17). This plan moves 0 test counts.

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-29*

## Self-Check: PASSED
