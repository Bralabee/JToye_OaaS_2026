---
phase: quick
plan: 261009-g8m
subsystem: core-java security/access + customer API
status: complete
tags: [windows-29, d-06, d-08, shop-access, customers, idempotency, 37-B]
requires: [ShopAccessService ladder (Phase 23/37-05), StrictScopingGuard, ShopGrants]
provides: [ShopAccessService.requireAnyAccess(), customer endpoint grant gate, phase-37-b-staff-access re-cut (one squash on origin/main)]
affects: [CustomerController (all five operations), docs/api/openapi-snapshot.json, docs/metrics.json, .planning/WINDOWS.md]
tech-stack:
  added: []
  patterns: [controller-level gate before the Idempotency-Key branch (FinancialTransactionController precedent)]
key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/customer/CustomerAccessGateIntegrationTest.java
    - .planning/quick/261009-g8m-windows-29-ruling-b-gate-customer-endpoi/evidence/red-customer-gate.json
    - .planning/quick/261009-g8m-windows-29-ruling-b-gate-customer-endpoi/evidence/task-base.txt
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
    - core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java
    - core-java/src/test/java/uk/jtoye/core/integration/CustomerControllerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/common/idempotency/CustomerIdempotencyIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/ScopedWriteAccessIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/integration/TypedNotFoundBodyIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/integration/VanishedRowStatusIntegrationTest.java
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - .planning/WINDOWS.md
    - .planning/STATE.md (uncommitted, orchestrator Step 8)
decisions:
  - "WINDOWS #29 RULED 2026-10-09 (owner, option B): every customer endpoint requires an honoured grant under D-06 (requireAnyAccess = the staff/me predicate), checked before the Idempotency-Key replay; options C and E are separate future rulings"
  - "requireAnyAccess never calls onRequest() and never reads shop_staff rows directly; requiredRole STAFF means 'any grant'"
  - "Break arm B2 as planned could not red (a gate anywhere before return still refuses); the create arm was strengthened to also POST with a fresh Idempotency-Key and assert zero rows"
metrics:
  duration: 51 min
  completed: 2026-10-09
  started: 2026-10-09T11:05:28Z
actuals:
  tokens: 50973
  tasks: 3
  commits: 6
plan_head_before: 2ab64fbb9a7e05c6da966d56d3a5084bb4169887
plan_head_after: 29520bf2f6bcd6d15bcc9c86af1f6c84ddea194d
---

# Quick 261009-g8m: WINDOWS #29 ruling B, gate every customer endpoint on an honoured grant — Summary

Under D-06 strict scoping, every `/api/v1/customers` operation now requires the caller to hold an honoured grant. The check is `ShopAccessService.requireAnyAccess()`, the same predicate `GET /api/v1/staff/me` uses. It returns the typed `shop-access-denied` 403 and runs before any lookup and before the Idempotency-Key replay. It was proven RED-first through MockMvc on Testcontainers Postgres, with break arms. `phase-37-b-staff-access` was re-cut as one squash commit on origin/main that carries the gate and none of 37-16.

`actuals.commits: 6` was measured as `git rev-list --count 2ab64fbb..HEAD`. TASK_BASE stands in for the protocol-0c ledger file, which was not written separately; TASK_BASE was recorded before the first commit.

## TASK_BASE and commits (with Task 3 PICK/SKIP class)

TASK_BASE = `2ab64fbb9a7e05c6da966d56d3a5084bb4169887`

| Commit | Message | Class |
|---|---|---|
| aa91eba9 | test(261009-g8m): RED arms for the WINDOWS #29 customer gate | PICK |
| 9c265411 | docs(261009-g8m): RED evidence and task base | SKIP |
| 3af4bedc | feat(261009-g8m): gate every customer endpoint on an honoured grant (WINDOWS #29, ruling B) | PICK |
| f8a44ae1 | test(261009-g8m): the refused create arm also proves the keyed path writes nothing | PICK |
| 96d75dac | docs(261009-g8m): refresh doc metrics | SKIP |
| 29520bf2 | docs(261009-g8m): WINDOWS #29 ruled B and fixed, #30 live proof owed | PICK |

No commit was MIXED (`mixed=0`). Path purity:
- `git show --name-only` of aa91eba9 lists only the new IT.
- 3af4bedc lies only under core-java/ plus the snapshot (rg for any other path: rc 1).
- 96d75dac is exactly AGENTS.md, CLAUDE.md, README.md and docs/metrics.json.
- 29520bf2 is exactly .planning/WINDOWS.md.

Every commit message was read back with `git log --format=%B 2ab64fbb..HEAD` (grc 0, 3814 chars). The attribution scan matched nothing (rg rc 1). Control: the same rg on a sample `Co-Authored-By:` line printed 1, rc 0.

## Task 1: RED, GREEN, fallout, break arms

**RED** (unfixed tree, `cleanIntegrationTest integrationTest --tests ...CustomerAccessGateIntegrationTest`): gradle rc 1, fresh XML, tests=12 failures=8 errors=0 skipped=0.

| Arm | RED |
|---|---|
| ungranted_list_403 | FAIL: expected 403, was 200 (target) |
| ungranted_getById_403 | FAIL: was 200 |
| ungranted_create_403_writesNothing | FAIL: was 201 |
| ungranted_update_403_changesNothing | FAIL: was 200 |
| ungranted_delete_403_deletesNothing | FAIL: was 204 |
| revokedLastGrant_nextRequest_403 | FAIL after its 200 control and the realm-admin 204 revoke passed |
| deHonouredJitGroupAdmin_403 | FAIL after its row-count-1 instrument passed |
| ungrantedReplayOfKnownIdempotencyKey_403_notStored201 | FAIL after the granted 201 and the same-id replay 201 passed |
| staffOfOneShop_allFiveOperationsSucceed | PASS (control) |
| tenantWideStaffWithNoShops_isAdmitted | PASS (control) |
| realmAdmin_isAdmitted | PASS (control) |
| strictOff_ungrantedUnchanged_andNoJitRow | PASS (control) |

The observed split of 8 fail / 4 pass equals the predicted 8/4. All eight failures are at the status assertion inside `assertShopAccessDenied` (line 451).

**RED record:** `evidence/red-customer-gate.json`. The classifier verdict is `RED_EVIDENCE_OK`, reason `target_test_failed`, target "WINDOWS #29: an ungranted user is refused the customer list, and staff/me agrees they have no access", actual "expected: 403 but was: 200". Semantic assessment as recorded: the target executed and failed on the planned assertion for the intended reason. There was no compile, fixture or context fault (errors=0), and every refusal arm's own in-arm controls passed before its refusal assertion failed. Classifier fail direction: a hand-made green record gave `INVALID_RED`. Note that `gsd_run check tdd-red-evidence --raw` prints the full JSON envelope, so the bare verdict needs `--raw --pick verdict`. The plan's verify compared the bare token, and I used `--pick verdict` there.

**GREEN:** CustomerAccessGateIntegrationTest 12/0/0/0, fresh.

**Fallout (observed, eight-class set after GREEN, before fixes).** Every red was the gate's 403, and each was fixed by granting the caller access it needs:

| Test (method) | Red | Fix |
|---|---|---|
| CustomerIdempotencyIntegrationTest.sameKeyReplay_returnsOriginalCustomer_noDuplicate | ShopAccessDeniedException (non-JWT @WithMockUser fails closed) | `@WithMockUser` adds `ROLE_admin` |
| CustomerControllerIntegrationTest: create/list/getById/update/delete (5) | expected 201, was 403 | `@WithMockUser` adds `ROLE_admin` |
| ScopedWriteAccessIntegrationTest: writeScopedTokenCreates201OnCustomerCreate, writeScopedTokenReaches404OnCustomerUpdate, writeScopedTokenReaches404OnCustomerDelete | 201/404 expected, 403 | `customersWriteJwt` uses a fixed sub holding STAFF on SHOP_A (seeded once in @BeforeEach); the "no shop dependency" comment rewritten |
| TypedNotFoundBodyIntegrationTest: GET/PUT/DELETE /customers/{unknown} and the live-customer CONTROL (4) | 404/200 expected, 403 | the caller holds a tenant-wide STAFF grant (`customerStaff(tenant)`) |
| VanishedRowStatusIntegrationTest: the 6 customer arms (2 unknown-id, 2 race, 2 CONTROL) | 404/204/200 expected, 403 (the race arms reported "never blocked") | `vendor(tenant)` now seeds a tenant-wide STAFF grant before building the token |
| LocationHeaderContractTest | green, as predicted | none |
| OpenApiSnapshotTest | red until regenerated | `updateOpenApiSnapshot` |

Loosening scan over the five fixed tests' diff (any changed line carrying a status or StrictScopingGuard expectation): rc 1, no matches. Control: the same scan on a sample `isOk()` line matched, rc 0. After the fixes, all 8 classes were green on fresh XML (1+12+7+7+1+13+9+12).

**Task 1 automated verify:** rc 0. gradle rc 0, all 8 fresh XML at 0/0, red=RED_EVIDENCE_OK, gate calls 5 (rg rc 0), customer ops with 403 = 5. The snapshot diff at feat^..feat touched only `/api/v1/customers` and `/api/v1/customers/{id}`, with nothing outside paths. Fail directions, all executed:
- TASK_BASE controller: 0 gate calls, rg rc 1.
- TASK_BASE snapshot: 0 customer ops with 403, rc 1.
- Scope one-liner on 96d5a94b 2ab64fbb: changed `/api/v1/orders/kitchen`, rc 1.
- A bad ref raised CalledProcessError, rc 1.

Ordering check: `merge-base --is-ancestor aa91eba9 3af4bedc` rc 0, and the reverse is rc 1.

**Break arms.** CustomerController sha256 `8665e463f45ad0b4c29c8632aaf410377644c6197ddcafd77838dac777041ebc`. The opening porcelain was `?? .planning/milestone.lock` plus the untracked PLAN.md.
- **B1** (gate line removed from `list()`, gate count 4): ungranted_list_403 FAILED, "expected: 403 but was: 200", gradle rc 1. It was restored by `git checkout HEAD --`, and sha256 -c reported OK.
- **B2 as planned** (gate moved below the Idempotency-Key branch, after the response is built, before `return`): the replay arm stayed **green** (gradle rc 0, 1/0/0). The plan's prediction was wrong, see Deviations. Restored, sha256 OK.
- **B2b** (gate placed directly after the Idempotency-Key if/else, i.e. after `idempotencyService.execute`): ungranted_create_403_writesNothing FAILED, "[the refused create wrote no row] expected: 0L but was: 1L"; the replay arm passed. Restored, sha256 OK.
- **B2c** (gate only on the unkeyed branch): the replay arm FAILED, "expected: 403 but was: 201". The strengthened create arm also failed, keyed path, 201. Restored, sha256 OK.
- **Closing clean:** sha256 OK, CustomerAccessGateIntegrationTest 12/0/0/0 fresh, gradle rc 0, and porcelain unchanged from the opening.

## Task 2: full suites, doc metrics, ledger, STATE

**Full suites** ran on f8a44ae1, the final code tree. The later commits touch no core-java path (`git diff --name-only f8a44ae1 HEAD -- core-java` is empty). Gradle rc 0, BUILD SUCCESSFUL in 27m 2s, and every report was fresh (stale 0):
- unit: 189 files, **1624 tests, 0 failures, 0 errors, 1 skipped**, unchanged from the baseline.
- integration: 189 files, **1018 tests, 0 failures, 0 errors, 1 skipped**. That is 1006 + N with N = 12, the twelve new CustomerAccessGateIntegrationTest @Test methods. The fixes added no methods; the f8a44ae1 strengthening extended an existing arm.
- DemoDataSeederServiceAccountGrantIntegrationTest ran 5/0/0, which covers the integration-orders-rw service account.

**Doc gates, main checkout.**
- Before any edit: docs-freshness rc 1 (37-16 drift plus this task; computed 2465/364, 2408/208, 150, total 5182) and check-doc-metrics rc 0.
- After `--write`: check-doc-metrics rc 1, 19 FAIL lines across README, CLAUDE and AGENTS.
- After the prose edits: check-doc-metrics rc 0 (37 claims) and docs-freshness rc 0.

The metrics commit also absorbs 37-16's pre-existing drift (+3 java methods/+1 file, +25 jest blocks/+3 files, +1 playwright).

**Ledger.**
- WINDOWS #29 carries the ruling text: option B, with options C and E as separate future rulings. It is `| fixed |`, and the text survived the `windows fixed 29` re-render (it was written into both the table cell and the JSON block).
- #30 (unrun-verify, open) records the owed live proof.
- The frontmatter reads open 7 / fixed 23 / total 30.
- Fail directions on TASK_BASE: row 29 had no "option B" (rc 1) and was not fixed (rc 1), row 30 was absent (rc 1), and the counts were 7/22/29.

**STATE.md:** one decision bullet before "### Pending Todos". `git diff --numstat` shows `1 0 .planning/STATE.md`. It is uncommitted and left for the orchestrator. On TASK_BASE the bullet count was 0 (rc 1).

## Task 3: re-cut of phase-37-b-staff-access

**Precondition:** `git fetch origin` rc 0, origin/main = 1773fe530e17d6ee2c1ee56044eaac0f36d67f7a, cut/phase-37-b-staff-access = 96d5a94b5a007138864cb9948fbee2b033f9e024, and ls-remote returned rc 0 with empty output. The old branch head was **24d58c1b0a4f53e6c728d3e6bd9d8d81bd9ee451**, with no upstream.

**Worktree** `../JToye_OaaS_2026-37b-v2` on tmp/37b-v2-recut, with HEAD verified at 96d5a94b.
- The four PICKs cherry-picked cleanly (the snapshot auto-merged, no conflict).
- Eight-class run in the worktree: gradle rc 0 in 2m 16s, all 8 fresh at 0/0 (1, 12, 7, 7, 1, 13, 9, 12). OpenApiSnapshotTest's check mode confirms the merged snapshot matches the worktree source.
- Doc gates in the worktree before the write: docs-freshness rc 1 (computed java 2462/363, jest 2383/205, total 5153) and check-doc-metrics rc 0.
- After `--write`: check-doc-metrics rc 1 (10 FAIL lines). After the prose edits, both gates were rc 0, and jest_blocks = **2383** (no 37-16 leak).
- The worktree commit 73a55b97 is `docs(37-B): doc metrics for the re-cut tree`.
- Tag **cut/phase-37-b-staff-access-v2 = 73a55b978264d7c564ddc37953bf87da1671300e** (lightweight, like the original).

**Squash.**
- `checkout --no-track -B phase-37-b-staff-access origin/main` rc 0, then `merge --squash` rc 0. Before committing, the index tree equalled the v2 tree (47128157).
- `commit -F` from the scratchpad ran with hooks, rc 0. The message was read back intact: first line `feat(37-B): staff access and finance`, includes the WINDOWS #29 bullet, no backticks, no attribution.
- check-branch-behind-base.sh from the worktree: PASS, 1 ahead, 0 behind.

**New phase-37-b-staff-access = 36d7f74bcc063c59032b8baf53502dd35dbb8e46** (old 24d58c1b). Shared tree = **47128157b8c3a1b19126c27af2f27add38d11f90**. The old cut's tree is c010219c, which differs.

`git log origin/main..phase-37-b-staff-access --oneline`:
```
36d7f74b feat(37-B): staff access and finance
```

**Task 3 automated verify:** rc 0.
- count=1; trees b == v; v != old; `git diff --quiet v2 branch` rc 0.
- The old cut is an ancestor of v2 (rc 0), and 2ab64fbb is not (rc 1).
- KitchenStatusesParityTest is absent in v2 (rc 128) and present on phase-37-ops-readiness (rc 0, control).
- The gate appears in v2 with count 5 and is absent in the old cut (git grep rc 1, control).
- No upstream (rc 128), ls-remote rc 0 and empty, worktree gone, tmp branch gone (rc 1), current branch phase-37-ops-readiness, porcelain within the allow-list.
- `git diff --stat v2 branch` printed nothing, rc 0. Control: `git diff --quiet v2 old-cut` rc 1.
- Porcelain allow-list control: the first attempt used `core-java/x.tmp`, which is gitignored (`.gitignore:101:*.tmp`), so it never appeared in porcelain and the control was vacuous (bad=0). Re-run with a non-ignored `core-java/w29-porcelain-control.txt` (check-ignore rc 1): it printed `UNEXPECTED: ?? core-java/w29-porcelain-control.txt` and set bad=1. The file was deleted and the porcelain was re-read clean.

**Teardown:**
- Worktree porcelain was empty, then `git worktree remove` without --force returned rc 0, and the directory no longer exists.
- tmp/37b-v2-recut == the v2 tag (73a55b97), then `branch -D` rc 0.
- `git worktree list` no longer shows the path.

Nothing was pushed and no PR was opened.

**Main checkout at the end:** branch `phase-37-ops-readiness`, porcelain:
```
 M .planning/STATE.md
?? .planning/milestone.lock
?? .planning/quick/261009-g8m-windows-29-ruling-b-gate-customer-endpoi/261009-g8m-PLAN.md
```
This SUMMARY.md will join the quick-dir line. All of these are owned by the orchestrator's Step 8.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Test gap] The planned break arm B2 could not red, and the hazard it targeted was uncovered**
- **Found during:** Task 1 Step 4
- **Issue:** The plan's B2 moved the gate "below the Idempotency-Key branch, after the response is built" and predicted the replay arm would red with 201. It stayed green. A gate anywhere before `return` still throws, so no stored 201 can leak. The real hazard of placing the gate after `idempotencyService.execute` is a WRITE: with a fresh key the create commits inside `execute` before the 403. No arm covered that, because the create arm sent no key.
- **Fix:** ungranted_create_403_writesNothing now also POSTs with a fresh Idempotency-Key and asserts the 403 and a zero row count. The arm count stays at 12. The break arms that actually red were then recorded: B2b reds the create arm (row written, 0 vs 1) and B2c reds the replay arm with 201.
- **Files modified:** CustomerAccessGateIntegrationTest.java
- **Commit:** f8a44ae1 (PICKed into the re-cut)

**2. [Rule 3 - Instrument] The classifier's `--raw` output is JSON, not a bare token.** The verify used `--raw --pick verdict`. Both forms report `RED_EVIDENCE_OK`.

**3. [Rule 1 - Vacuous control] The porcelain allow-list control's scratch file was gitignored.** It was re-run with a non-ignored path, which fired (see Task 3).

**4. Two commit attempts failed safely and were redone.** First, a regex replacement for the doc metrics mangled two claims (`\1364` was read as a different backreference). The gate caught it as "rule matched NOTHING" (25 of 37 claims). The three docs were restored from HEAD and the replacement redone with `\g<1>`. Second, one sort-order equality check stopped a commit chain before the message file was written. Nothing was committed in that state, and the staged set was confirmed and committed on the retry.

## Cross-cutting dimensions

- Web performance: N/A (no page changed).
- SEO: N/A (authenticated API).
- Client-persisted identity lifecycle: N/A (no client storage).
- AI agent-readiness: the 403 is typed RFC 7807 `shop-access-denied` with `requiredRole` STAFF, and the OpenAPI snapshot documents it on all five operations. MCP create_customer is preserved through the service account's grant (DemoDataSeederServiceAccountGrantIntegrationTest 5/0/0).
- Security: T-g8m-01..07 are mitigated and proven by the arms above. T-g8m-08 and T-g8m-09 are accepted by ruling (options C and E).
- Falsifiable evidence: both directions are recorded for every criterion above, including two vacuous controls found and replaced (B2, x.tmp).
- Runtime parity: NOT claimed. The compose runtime predates the gate (WINDOWS #30).

## Known Stubs

None.

## Residuals

- WINDOWS #30 is open: the live proof at the 37-B ship (ungranted GET /api/v1/customers gives 403 shop-access-denied, STAFF gives 200, and an ungranted dashboard login lands on the no-access page).
- Until the 37-B ship rebuild, check-runtime-freshness is expected to report core-java and check-openapi-snapshot-fresh the five new 403s.
- Options C (per-shop narrowing) and E (a SHOP_MANAGER floor on customer writes and staff DELETE) await their own owner rulings.
- The old phase-37-b-staff-access head 24d58c1b is now reachable only through the reflog. The original cut tag 96d5a94b is untouched.

## Self-Check: PASSED

- FOUND: core-java/src/test/java/uk/jtoye/core/customer/CustomerAccessGateIntegrationTest.java
- FOUND: evidence/red-customer-gate.json, evidence/task-base.txt
- FOUND commits on HEAD: aa91eba9, 9c265411, 3af4bedc, f8a44ae1, 96d75dac, 29520bf2
- FOUND refs: cut/phase-37-b-staff-access-v2 = 73a55b97, phase-37-b-staff-access = 36d7f74b
