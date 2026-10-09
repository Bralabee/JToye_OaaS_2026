---
phase: 37-real-world-operations-readiness
plan: 04
subsystem: auth
tags: [strict-scoping, d-06, shop-access, uxt-004, media-review-queue, ci-gate, k8s-goldens, testcontainers]

requires:
  - phase: 37-03
    provides: "all strict-ON red classes converted to explicit OPERATOR grants; dev seed grants for integration-orders-rw / integration-catalog-ro; full suite green under ACCESS_STRICT_SCOPING=true"
  - phase: 37-02
    provides: "StrictScopingGuard, ShopGrants.grantOperator, the booted-value instrument"
provides:
  - "jtoye.access.strict-scoping defaults to true (application.yml + @Value); compose :-true, .env.example true, k8s app-config \"true\", staging/production goldens regenerated"
  - "StrictScopingDefaultIntegrationTest: UXT-004 acceptance under the shipped default (no switch touched): ungranted 403 + no JIT row, staff/me groupAdmin=false/[], OPERATOR and realm admin 201, JIT bootstrap kept + WARN, younger JIT admin 403"
  - "Posture log: INFO 'access: strict scoping ON (default, D-06)'; WARN naming the explicit ACCESS_STRICT_SCOPING=false override; posture test boots the real application.yml"
  - "scripts/check-strict-scoping-default.sh (allow-list true/on/yes/1; exit 0/1/2) wired into ci-cd.yaml"
  - "GET /api/v1/media/review-queue is shop-scoped (deviation, closes 37-03 deferred-items 2)"
  - "Load-testing scripts refuse to measure a D-06 refusal (staff/me preflight, named 403 failures)"
affects: [37-05, 37-15, 37-B, compose-mcp, k8s-rollout]

actuals:
  tokens: 23115
  tasks: 3
  commits: 7
plan_head_before: c02a3ef9548d2dd19a29831accc7c2ea5c85c818
plan_head_after: 8034ae89

tech-stack:
  added: []
  patterns:
    - "A default is proven by a test that never touches the switch: StrictScopingDefaultIntegrationTest runs on whatever the context booted with, and goes red when application.yml says false"
    - "Config guard as an allow-list of values Spring reads as true, so off/no/0/empty cannot slip past a deny-list of the word false"
    - "Read scoping of tenant-wide lists follows the ProductService.getAllProducts rule: GROUP_ADMIN whole tenant, others their grant set, ungranted nothing, shop-less GROUP_ADMIN-only"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/security/access/StrictScopingDefaultIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaReviewQueueShopScopeIntegrationTest.java
    - scripts/check-strict-scoping-default.sh
    - .planning/phases/37-real-world-operations-readiness/evidence/37-04-red-default-it.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-04-red-review-queue-scope.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-04-flip-evidence.md
  modified:
    - core-java/src/main/resources/application.yml
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaController.java
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessServiceScopingPostureTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StrictScopingBootedValueIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/testsupport/TenantJwts.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaRedriveControllerTest.java
    - docs/api/openapi-snapshot.json
    - docker-compose.full-stack.yml
    - .env.example
    - k8s/base/configmap.yaml
    - k8s/base/core-java-deployment.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - .github/workflows/ci-cd.yaml
    - infra/load-testing/baseline.sh
    - infra/load-testing/load-test.sh
    - infra/load-testing/media-pipeline-arm.sh
    - docs/architecture/ARCHITECTURE.md
    - docs/PRD.md
    - k8s/LOCAL.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md

key-decisions:
  - "The default IT never sets the switch, so it pins the DEFAULT; the mechanism stays pinned by StrictScopingTighteningIntegrationTest, which sets it explicitly"
  - "The review queue is scoped inside this plan (Rule 2) because this plan's flip made the 37-03 gap live; shop-less assets are GROUP_ADMIN-only because Keep and re-process on them already are"
  - "The guard judges values against an allow-list (true/on/yes/1) rather than searching for the word false: Spring also reads off/no/0 as false"
  - "A guard run with both a violation and a VOID exits 1 (a definite violation is reported as one); VOID only when nothing definite was found"
  - "The running compose stack was not rebuilt: per the plan's must-have the flip reaches a shared runtime only at the 37-15 gate, after the owner sees who loses access"
  - "Frontend comments that still describe the OFF default are left to the D-07/D-09 Staff-page work that rewrites the same lines (deferred-items 3)"

patterns-established:
  - "Dev tools that sign in as a seed user check GET /api/v1/staff/me first and VOID/FAIL by name instead of measuring empty reads or 403s"

requirements-completed: [RWO-004, RWO-003]

coverage:
  - id: D1
    description: "Under the shipped default an ungranted vendor is refused a shop write (403 shop-access-denied) with no JIT row; staff/me says groupAdmin=false, grantedShopIds=[]; OPERATOR GROUP_ADMIN and realm admin still write; the oldest JIT admin is the WARN-logged bootstrap and a younger one is refused"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/security/access/StrictScopingDefaultIntegrationTest.java (5 tests): RED 3/5 on 0f1a893b (RED_EVIDENCE_OK), GREEN 5/0 on 44983a13, break arm (yml default false) 3 red, closing clean 5/0"
        status: pass
    human_judgment: false
  - id: D2
    description: "The application ships with strict scoping ON: application.yml and the @Value default say true; the booted bean reads true and logs the ON INFO line; OFF logs a WARN naming the explicit override"
    requirement: "RWO-004"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessServiceScopingPostureTest.java (3 tests; break arm red with 'expected: true but was: false')"
        status: pass
      - kind: integration
        ref: "StrictScopingBootedValueIntegrationTest unset -> expected=true booted=true; break arm red"
        status: pass
    human_judgment: false
  - id: D3
    description: "Every runtime config says true and CI fails the day one says otherwise"
    requirement: "RWO-004"
    verification:
      - kind: other
        ref: "bash scripts/check-strict-scoping-default.sh -> PASS rc 0; 8 break arms rc 1, 4 VOID arms rc 2; pre-Task-2 tree rc 1 naming 5 sites; check-gate-enforcement PASS, and FAIL with the CI step removed"
        status: pass
      - kind: other
        ref: "k8s/scripts/render-golden.sh drift before --write, OK after; git diff 4c55677b..HEAD -- k8s/goldens = one line per golden"
        status: pass
    human_judgment: false
  - id: D4
    description: "GET /api/v1/media/review-queue no longer hands the tenant's whole queue to an ungranted or single-shop vendor (deviation, 37-03 deferred-items 2)"
    requirement: "RWO-003"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/media/MediaReviewQueueShopScopeIntegrationTest.java (4 tests): RED 2/4 on b2ff028a (RED_EVIDENCE_OK), GREEN 4/0 on 4c55677b, two break arms red, closing clean 4/0"
        status: pass
    human_judgment: false
  - id: D5
    description: "Full core-java suite green under the shipped default with no env override"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: ":integrationTest 176 suites / 908 tests / 0 failed (d04fd6c3); :test 187 suites / 1597 tests / 0 failed (8034ae89), ACCESS_STRICT_SCOPING unset"
        status: pass
    human_judgment: false
  - id: D6
    description: "Load-testing scripts fail loudly instead of reporting throughput over a D-06 refusal"
    verification:
      - kind: other
        ref: "preflight decision over 6 canned staff/me responses (all as expected); [403] detector both directions; bash -n rc 0 on all three, rc 2 on a broken copy"
        status: pass
    human_judgment: true
    rationale: "Not run end to end against a live stack: the running image is pre-flip by design (flip reaches the shared runtime at 37-15) and this worktree has no .env to mint a seed-user token"
  - id: D7
    description: "MCP create_order / catalogue read keep working on the live compose stack with real Keycloak tokens after the flip"
    verification: []
    human_judgment: true
    rationale: "Owed to the 37-15 gate (rebuild ALL images + check-runtime-freshness + live MCP create_order). This plan changes code and config only, per its must-have; proven here by integration tests (37-03 DemoDataSeederServiceAccountGrantIntegrationTest) only"

duration: 56m
completed: 2026-10-08
status: complete
---

# Phase 37 Plan 04: Strict scoping ON by default (D-06) Summary

**`jtoye.access.strict-scoping` now defaults to true in application.yml, compose, `.env.example`, the k8s app-config and both rendered goldens. A new integration test proves "ungranted means no access" under the shipped default without touching the switch. A CI gate (`scripts/check-strict-scoping-default.sh`, an allow-list of true values) fails any runtime that overrides it back. The media review queue, which this flip would have opened to every ungranted vendor, is now shop-scoped.**

## Performance

- **Duration:** 56 min (about 31 min of it the full-suite runs)
- **Started:** 2026-10-08T10:25:29Z
- **Completed:** 2026-10-08T11:21:00Z
- **Tasks:** 3 (plus 1 deviation fix)
- **Files modified:** 29 (6 created, 23 modified)

## Accomplishments

- **Task 1 (tracer).** The default flip (`${ACCESS_STRICT_SCOPING:true}` and `@Value(...:true)`) is now behind `StrictScopingDefaultIntegrationTest`. Its five cases:
  - ungranted write → 403 `shop-access-denied`, and 0 `shop_staff` rows for that user (a granted control user in the same tenant reads 1, so the count query can see rows);
  - ungranted `GET /api/v1/staff/me` → `groupAdmin:false`, `grantedShopIds:[]`;
  - OPERATOR GROUP_ADMIN → 201;
  - realm admin → 201;
  - JIT-only tenant → the oldest JIT admin gets 201 and a WARN naming the tenant and the user; a younger JIT admin gets 403.
- **Task 1, posture log.** OFF is now a WARN naming the explicit `ACCESS_STRICT_SCOPING=false` override and the owner ruling, and ON is INFO. The #285 / sync / integration-orders-rw blocker text is gone. The posture test boots the real `application.yml` and reads the bean's value and its own startup line.
- **Task 2.** Compose, `.env.example`, the configmap and the deployment comment now state D-06 and say that false needs an owner ruling. The goldens were regenerated by `render-golden.sh --write`; only the one line changes in each. The new gate is wired into `ci-cd.yaml` next to the no-create-extension gate.
- **Task 3.** The full suite is green under the default (0 failures). It needed no new grants, because 37-03 had already converted every class. The three load-testing scripts now check the seed user's effective access first. They VOID or FAIL by name instead of reporting throughput over empty reads or 403s.
- **Deviation.** `MediaAssetService.reviewQueue` now applies the product-list read rule (details under Deviations). `MediaRedriveControllerTest` AC-4.8 now reads the queue as shopA's manager. The OpenAPI snapshot was regenerated; only the endpoint description changed.

## Task Commits

1. **Task 1 RED: failing default IT** - `0f1a893b` (test)
2. **Task 1 GREEN: strict scoping defaults to ON** - `44983a13` (feat)
3. **Deviation RED: failing review-queue scope IT** - `b2ff028a` (test)
4. **Deviation GREEN: review queue shop-scoped** - `4c55677b` (fix)
5. **Task 2: every runtime true + the CI gate** - `c28859ba` (feat)
6. **Task 3: load-testing scripts refuse a D-06 refusal** - `d04fd6c3` (fix)
7. **Doc accuracy + shifted citations** - `8034ae89` (docs)

**Plan metadata:** the commit that adds this SUMMARY (docs). STATE.md and ROADMAP.md are not touched; the orchestrator owns them.

## Evidence (both directions)

Full detail, including every command and the gate's arm output verbatim, is in `evidence/37-04-flip-evidence.md`.

| Check | Pass direction | Fail direction |
|-------|----------------|----------------|
| Default IT (5 cases) | 5/0 on `44983a13`, closing clean 5/0 | RED 3/5 on `0f1a893b`: write `201` plus a `JIT-provisioned …` log line, staff/me `groupAdmin:true`, no WARN. Break arm (yml `false`): the same 3 fail. |
| Booted-value instrument (37-02) | unset → `expected=true booted=true property=true`. Its fallback was changed from `"false"` to `"true"` in `44983a13` (the plan's required edit). | break arm: `expected: true but was: false` |
| Posture unit test | 3/0 | break arm: the booted-default case fails `expected: true but was: false` |
| `grep -n 'ACCESS_STRICT_SCOPING:true' application.yml` | `210:` rc 0 | pre-change file: rc 1 |
| Review-queue scope IT | 4/0, neighbours 13/0 | RED 2/4; arm 1 (filter off) 2 red; arm 2 (shop-less let through) 1 red |
| `check-strict-scoping-default.sh` | real tree rc 0 | 8 arms rc 1 (the plan's compose / configmap / application.yml / golden, plus `off` in an overlay env, `0` in `.env.example`, `no` in a profile, a dropped default); 4 arms rc 2 (no application.yml, configmap key deleted, no root, no `k8s/local`); pre-Task-2 tree rc 1 naming all 5 sites |
| `check-gate-enforcement.sh` | PASS | CI step removed → FAIL naming the gate; restored by hash |
| Golden diff | `git diff 4c55677b..HEAD -- k8s/goldens`: line 47 `"false"` → `"true"` in each golden, nothing else | `render-golden.sh` before `--write` → drift on exactly that line |
| Acceptance `rg -uu … 'ACCESS_STRICT_SCOPING:-false\|access.strict-scoping: "false"\|ACCESS_STRICT_SCOPING=false'` | rc 1, nothing | pre-Task-2 export: 5 lines, rc 0 |
| Full suite (unset) | `:integrationTest` 176 / 908 / 0 failed; `:test` 187 / 1597 / 0 failed | n/a. The RED runs above are the falsifying arms; the reader's BAD direction fired in each of them. |

Golden diff, as shown:

```
--- a/k8s/goldens/production.yaml
+++ b/k8s/goldens/production.yaml
@@ -47 +47 @@ data:
-  access.strict-scoping: "false"
+  access.strict-scoping: "true"
--- a/k8s/goldens/staging.yaml
+++ b/k8s/goldens/staging.yaml
@@ -47 +47 @@ data:
-  access.strict-scoping: "false"
+  access.strict-scoping: "true"
```

Load-testing scripts (acceptance: changed, or listed as read-only):
- `baseline.sh` reads only, but authenticated endpoints (`/api/v1/shops`, `/api/v1/products`). Under D-06 these answer an ungranted user 200 with empty bodies, so it was **changed**: it now VOIDs on no access, and its 403 hint names the gate. The caller-supplied `TOKEN` path (AC-6.2) is untouched.
- `load-test.sh` writes (`POST /shops`, GROUP_ADMIN only). It was **changed** to exit 1 unless TEST_USER is a GROUP_ADMIN, and to exit 1 on a `[403]` in the write test.
- `media-pipeline-arm.sh` writes (`POST /api/v1/products/{id}/image`, SHOP_MANAGER). It was **changed** to VOID on no access and to FAIL naming the gate on any 403.
- None of the three only reads public endpoints, so none was left unchanged.

## Decisions Made

See `key-decisions` in the frontmatter.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical] `GET /api/v1/media/review-queue` was not shop-scoped**
- **Found during:** carried forward from 37-03 (deferred-items §2), made live by Task 1's flip
- **Issue:** `MediaAssetService.reviewQueue()` made no `ShopAccessService` call. Under the D-06 default, a vendor with no grant, or with a grant on one shop, received the tenant's FAILED, flagged and stalled assets: asset ids, product ids and failure reasons for shops they cannot otherwise read.
- **Fix:** the queue follows the existing `ProductService.getAllProducts` read rule, through the existing `ShopAccessService.isGroupAdmin()` / `grantedShopIds()` path:
  - a GROUP_ADMIN sees the whole tenant's queue;
  - any other caller sees only assets whose owning shop (`resolveOwningShopId`) is in its grant set;
  - an ungranted caller gets `[]`;
  - a shop-less asset is GROUP_ADMIN only.

  The OpenAPI description was updated and the snapshot regenerated. That is a description-only contract change: no schema or status change.
- **Companion check:** `POST /api/v1/media/{assetId}/keep` already requires SHOP_MANAGER on the owning shop (WR-03, `MediaKeepShopScopeIntegrationTest` 3/0). No change was needed.
- **Files modified:** `MediaAssetService.java`, `MediaController.java`, `MediaRedriveControllerTest.java` (AC-4.8 caller), `docs/api/openapi-snapshot.json`; new `MediaReviewQueueShopScopeIntegrationTest.java`
- **Verification:** RED 2/4 (RED_EVIDENCE_OK), GREEN 4/0, two break arms red, closing clean. `deferred-items.md` §2 now points at `b2ff028a` / `4c55677b`.
- **Commits:** `b2ff028a`, `4c55677b`

**2. [Rule 1 - Bug] Two doc citations shifted by Task 1**
- **Found during:** Task 2 verification (`check-doc-citations.sh`)
- **Issue:** `k8s/LOCAL.md:609` and `:1634` cite `application.yml:519` for the STOMP broker mode. The strict-scoping comment grew by one line, which moved that line to 520. The gate reported 2 violations.
- **Fix:** both citations now point at 520. Re-run: 0 violations.
- **Commit:** `8034ae89`

**3. [Rule 1 - Bug] ARCHITECTURE.md and PRD.md said the flag defaults OFF**
- **Fix:** both now state the D-06 default. **Commit:** `8034ae89`

**4. [Rule 3 - Blocking] The posture test's context needed Boot's conversion service**
- **Found during:** Task 1 GREEN
- **Issue:** the first boot of a context with the real `application.yml` failed: `@Value Duration` binding found no converter.
- **Fix:** install `ApplicationConversionService.getSharedInstance()`, the same idiom as `ShopReservedSlugAccountTest`.
- **Commit:** `44983a13`

**5. [Rule 2 - Missing critical] `baseline.sh` changed although it only reads**
- **Issue:** the plan would leave read-only scripts unchanged, but only scripts that read *public* endpoints. `baseline.sh` reads authenticated lists, which under D-06 return empty 200s to the ungranted seed user. Its 2xx-only assertion would then score empty reads as a baseline, the vacuous shape its own header warns about.
- **Fix:** the same staff/me preflight as the other two scripts. **Commit:** `d04fd6c3`

**6. [Rule 3 - Blocking] Python-free verification; compound commands split**
- No python step was needed; every check is a bash or jq script in the session scratchpad (`run-t1.sh`, `run-it.sh`, `run-unit.sh`, `run-full.sh`, `run-unit-all.sh`, `arms.sh`, `preflight-cases.sh`, `check-xml.sh`). The isolation guard refused several compound lines, so they were split or moved into scripts.

---

**Total deviations:** 6 auto-fixed (2 missing critical, 2 bugs, 2 blocking).
**Impact on plan:** one production authorization fix that the flip made necessary, plus doc accuracy. No refusal assertion was weakened anywhere, and no test sets the switch to false to pass.

## Issues Encountered

- **First full-suite `:test` result was void (instrument fault, mine).** During the full run I ran `scripts/check-boot-config-keys.sh`, which starts its own Gradle `:core-java:test --rerun`. That deleted the first build's `test-results/test/binary/in-progress-results-generic.bin`, and `:test` failed with `NoSuchFileException`. `:integrationTest` in that run was unaffected (176 / 908 / 0). `:test` was then rerun alone with nothing else touching Gradle: 187 / 1597 / 0. Lesson: no Gradle-invoking gate while a suite is running.
- **Self-caught gate defect.** The first real-tree run of `check-strict-scoping-default.sh` failed the correct compose line: the placeholder regex was not anchored to `${`. It was fixed before commit, and every arm ran on the committed version.
- `check-doc-citations.sh` prints VOID for `docs/ops/terminal-states.yaml` on this machine, because the python shim refuses its bare `python3`. This is environmental and unchanged by this plan; its 29 checkable citations pass.
- The plan ledger was kept in the session scratchpad (`p04/plan-head-before` = `c02a3ef9…`), as in 37-02 and 37-03, because this worktree's git dir lives inside the main checkout's `.git/`.

## Known Stubs

None.

## Threat Flags

None. No new endpoint or trust boundary. The review-queue change narrows an existing read surface (T-37-06, read half). The gate is T-37-07's mitigation. T-37-08 (lockout) is covered by the bootstrap-admin arm of the default IT.

## TDD Gate Compliance

- **Task 1:** RED `0f1a893b` `test(37-04)` → GREEN `44983a13` `feat(37-04)`. RED classified `RED_EVIDENCE_OK` and semantically assessed. No REFACTOR.
- **Deviation:** RED `b2ff028a` `test(37-04)` → GREEN `4c55677b`, typed `fix(37-04)` because it fixes an authorization gap; the gate's `feat` pattern is satisfied by `44983a13` and `c28859ba`. RED was `RED_EVIDENCE_OK`.
- **Task 2 (`tdd="true"`): no separate RED commit.** The gate script and the config flip landed together in `c28859ba`. The failing direction was shown on the committed script instead:
  - the pre-Task-2 tree gives rc 1, naming all 5 false sites;
  - 8 rc-1 arms and 4 rc-2 arms ran on copies.

  It is flagged here because the gate shape (a `test(...)` commit before the `feat(...)`) was not followed for this task.
- **Task 3:** `type="auto"`, no `tdd`. It is a verification task plus script hardening.

## User Setup Required

None. After D-06, anyone running the load-testing scripts must first grant TEST_USER on the Staff page; each script now says so and stops if it has no access.

## Next Phase Readiness

- **37-15 gate** owns putting the flip on the shared runtime: the owner checkpoint listing JIT-only users who lose access, rebuilding ALL images, `check-runtime-freshness.sh`, and live MCP `create_order` (deferred-items §4; prior-wave carry-forward 3 remains owed there).
- **D-07 / D-09 Staff-page work:** rewrite the frontend comments and copy that still describe the OFF default (deferred-items §3).
- **Before the phase PR:** docs metrics (deferred-items §1) have moved again. This plan added 10 Java `@Test` methods in 2 new files, plus 1 posture case. Re-measure; do not copy these numbers.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-08*

## Self-Check: PASSED

- Created files exist: StrictScopingDefaultIntegrationTest.java, MediaReviewQueueShopScopeIntegrationTest.java, scripts/check-strict-scoping-default.sh, evidence/37-04-red-default-it.json, evidence/37-04-red-review-queue-scope.json, evidence/37-04-flip-evidence.md
- Commits on this branch: 0f1a893b, 44983a13, b2ff028a, 4c55677b, c28859ba, d04fd6c3, 8034ae89 (`git rev-list --count c02a3ef9..HEAD` = 7 at SUMMARY write)
- Acceptance criteria re-run: application.yml grep line 210; gate rc 0; gate-enforcement PASS; render invariants PASS; goldens OK; false-value search rc 1
