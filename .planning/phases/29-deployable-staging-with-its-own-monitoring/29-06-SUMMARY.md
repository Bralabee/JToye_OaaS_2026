---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 06
subsystem: infra
tags: [staging, seed, keycloak-admin-api, onboarding, culinary-pack, key-vault, kube-guard, bash, tdd]

requires:
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-01: scripts/lib/azure-guard.sh (az_guard_init, az_read, az_guard_stub)"
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-03: scripts/lib/staging-kube-guard.sh (staging_kube_guard)"
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-04: the jtoye-staging realm (tenant_id managed and admin-only; live-existing assert mode admits post-import users such as this plan's vendor)"
provides:
  - "scripts/staging-seed.sh: --context, --identity-file, --api, --stage vendor (order/all VOID until 29-18), --dry-run, --list-kv-objects, --selftest (11 arms)"
  - "the vendor stage: tenant, test vendor, GROUP_ADMIN grant, shop, one culinary pack, trader identity, onboarding to LIVE, read-back; the per-run realm-admin operator deleted on every exit"
  - "Key Vault object names for 29-13's writer: staging-seed--test-vendor-password, keycloak-credentials--admin-username, keycloak-credentials--admin-password, keycloak-credentials--frontend-client-secret"
  - "ci-cd.yaml k8s-validate step running the selftest"
affects: [29-13, 29-14, 29-18]

actuals:
  tokens: 18413
  tasks: 2
  commits: 3
plan_head_before: d34e818190cf57a08cc54c52a3456fe13801579c
plan_head_after: 2a099d7256af0797ce53421058bc3c8c37ed77d4

tech-stack:
  added: []
  patterns:
    - "Seed rows only through product APIs: the realm admin creates the tenant, grants the owner, resolves gates and approves; the owner does everything else"
    - "Profile guard reads the Ready pods' own container env, never the Deployment template; zero Ready pods is a refusal"
    - "Secrets reach curl only as name@file or inside a mode-600 body file; the selftest greps every curl argv for the STUB-SECRET- marker"
    - "A per-run privileged identity is deleted by the EXIT trap before the port-forward it needs is killed; a failed deletion fails the run"

key-files:
  created:
    - scripts/staging-seed.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-06-t1-red-prod-api.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-06-t1-red-stale-server.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-06-t1-red-dev-profile.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-06-t1-red-rollout-mixed.json
  modified:
    - .github/workflows/ci-cd.yaml

key-decisions:
  - "Under strict scoping (access.strict-scoping true, D-06) nothing is provisioned just in time, measured in ShopAccessService (JIT only when strict scoping is OFF). RESEARCH said the first JIT user becomes the bootstrap GROUP_ADMIN; that is not what the code does. The seed therefore grants the vendor GROUP_ADMIN through POST /api/v1/staff/grant as the realm-admin operator, the same path a first tenant's owner gets access by"
  - "In prod core-java reads the tenant from the JWT only (TenantFilter is @Profile dev/local/test), so the operator also carries the tenant_id attribute once the tenant exists, and its token is re-issued; tenant-scoped admin endpoints (gate resolve, approve) need it"
  - "The seed WAIVEs only the gates a human reviews (BUSINESS_VERIFIED, FOOD_HYGIENE_RATING) and never TRADER_IDENTITY or ALLERGEN_DATA_COMPLETE: the go-live guard needs those two PASSED on the facts, so a waiver would be refused there and would hide a real defect"
  - "Onboarding model MARKETPLACE, sole trader (no company number): COMPANIES_HOUSE_API_KEY is not wired in k8s and the BUSINESS_VERIFIED gate waives itself for a sole trader"

patterns-established:
  - "staging seed = product APIs only, guarded by staging_kube_guard + exact API base + pod profile, with a dry-run that calls nothing"

requirements-completed: []

coverage:
  - id: D1
    description: "The seed refuses the production and local API bases, no context, a stale server URL (the shared guard), a dev pod, a mixed rollout and zero Ready pods, before any az, curl or extra kubectl call"
    requirement: "DPLY-01"
    verification:
      - kind: unit
        ref: "bash scripts/staging-seed.sh --selftest (arms prod-api, local-api, no-context, stale-server, dev-profile, rollout-mixed, no-ready-pod PASS; RED on the guard-free skeleton 3841db98, RED_EVIDENCE_OK x4)"
        status: pass
      - kind: other
        ref: "break arms on scratch copies: first pod only -> rollout-mixed FAIL; Ready ignored -> no-ready-pod FAIL; kube guard removed -> stale-server + dry-run-plan FAIL; template read -> 4 profile/dry-run arms FAIL"
        status: pass
    human_judgment: false
  - id: D2
    description: "--dry-run prints the ordered plan naming admin/tenants, onboarding, trader-identity, culinary-packs/seed and go-live, with no secret and no az/curl call"
    requirement: "DPLY-01"
    verification:
      - kind: unit
        ref: "selftest arm dry-run-plan (rc 0, 19 steps, az 0, curl 0, guards ran: config view + one pod read)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The per-run realm-admin operator is deleted after a failing step and after a successful run; no Key Vault value reaches curl argv, stdout or stderr"
    requirement: "DPLY-01"
    verification:
      - kind: unit
        ref: "selftest arms operator-deleted-on-failure (500 on the culinary seed at line 20, DELETE at line 21) and operator-deleted-on-success (DELETE is the last call)"
        status: pass
      - kind: other
        ref: "break arms: delete removed from the trap -> both FAIL; client secret in argv -> argv leak FAIL; a secret echoed -> marker FAIL; seed failure ignored -> FAIL"
        status: pass
    human_judgment: false
  - id: D4
    description: "The vendor stage creates real staging rows against the live API and Keycloak, idempotently, and the test vendor signs in"
    requirement: "DPLY-01"
    verification: []
    human_judgment: true
    rationale: "No staging cluster, Keycloak or Key Vault exists yet and the owner forbids a live run here; the live --stage vendor is plan 29-14's tracer. The stub flow proves ordering and cleanup, not the real endpoints' answers (FHRS outcome, gate-chain timing, the culinary pack's allergen completeness)."

duration: 16min
completed: 2026-10-18
status: complete
---

# Phase 29 Plan 06: Staging Seed (Vendor Stage) Summary

**`scripts/staging-seed.sh` builds the staging test vendor only through product APIs: a tenant, a vendor whose password comes from Key Vault, a GROUP_ADMIN grant, a shop, one culinary pack, a sole-trader onboarding taken to LIVE, and a read-back. A per-run realm-admin operator is deleted on every exit path. The script runs only after the shared kube guard, an exact API base check, and a check that every Ready core-java pod runs the prod profile. The selftest showed the guards refusing on a guard-free skeleton before they were trusted.**

## Performance

- **Duration:** about 16 min
- **Started:** 2026-10-18T22:45:40Z
- **Completed:** 2026-10-18T23:02Z
- **Tasks:** 2
- **Files modified:** 6 (1 script, 1 workflow, 4 RED evidence records), plus this SUMMARY

## Accomplishments

- **Guards, in order, before any network call.**
  1. `staging_kube_guard` is sourced from 29-03; there is no local copy.
  2. `--api` must be byte-equal to `https://api-staging.olajay.co.uk`.
  3. The profile guard reads `kubectl get pods -l app=core-java -o json`. Every Ready pod's `core-java` container must carry exactly one literal `SPRING_PROFILES_ACTIVE=prod`. It must have no `SPRING_PROFILES_INCLUDE`, and no env value or argument that mentions `spring.profiles`. The Deployment template is never read, and zero Ready pods is a refusal. `DemoDataSeeder` is `@Profile("dev")` at `core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java:112`.
- **The vendor stage** (written and run against stubs here; the live run is 29-14):
  - `az_guard_init`, then four `az_read` Key Vault reads into mode-600 files.
  - A Keycloak port-forward, started by PID.
  - The operator `seed-operator-<UTC>`, holding realm `admin` with an `openssl rand` password.
  - The tenant, reused by name.
  - The operator's `tenant_id`, then a fresh operator token.
  - The vendor, reused by username or created. Its `tenant_id` is read back by content, with a `partialImport` fallback. The run is REFUSED if the attribute is still absent.
  - The vendor token, whose `tenant_id` claim must equal the tenant.
  - `/api/v1/staff/me` → `/api/v1/staff/grant` GROUP_ADMIN.
  - The shop, reused by slug and open every day.
  - The culinary pack, seeded only if the shop is empty.
  - The trader identity, PUT only if it differs.
  - Onboarding: create → submit → WAIVE the open review gates → approve → go-live. Each step is chosen by the current status.
  - A read-back with an ISO-8601 timestamp.
- **Operator cleanup** happens in the EXIT trap, before the port-forward is killed. A deletion that fails turns the run into a failure.
- **11-arm selftest** wired into `ci-cd.yaml` `k8s-validate`.

## Task Commits

1. **Task 1: Guards and the offline plan (TDD)**
   - RED `3841db98` (test): skeleton with no guards, plus the 9-arm selftest and four RED records.
   - GREEN `860d9d14` (feat): `run_guards`.
2. **Task 2: The vendor stage**: `2a099d72` (feat). The stage, the two operator arms, and the CI step.

**Plan metadata:** the docs commit carrying this SUMMARY.

## Evidence (both directions recorded)

### Task 1 (TDD)

**RED** (`3841db98`, guard-free skeleton). `bash scripts/staging-seed.sh --selftest`, rc=1:

```
FAIL prod-api rc=2 (want 1): VOID: the vendor stage is not implemented yet
FAIL local-api rc=2 (want 1): VOID: the vendor stage is not implemented yet
PASS no-context rc=2 kubectl_calls=0
FAIL stale-server rc=2 (want 1): VOID: the vendor stage is not implemented yet
FAIL dev-profile rc=2 (want 1): VOID: the vendor stage is not implemented yet
FAIL rollout-mixed rc=2 (want 1): VOID: the vendor stage is not implemented yet
FAIL no-ready-pod rc=2 (want 1): VOID: the vendor stage is not implemented yet
FAIL dry-run-plan guards did not run: config reads=0 pod reads=0 (want >=1, 1)
PASS list-kv-objects rc=0 objects=4
```

- **Classifier:** `gsd_run check tdd-red-evidence` returned `RED_EVIDENCE_OK` (`target_test_failed`, TAP, 9 tests, 7 failing) for prod-api, stale-server, dev-profile and rollout-mixed. Records: `evidence/29-06-t1-red-{prod-api,stale-server,dev-profile,rollout-mixed}.json`.
- **Semantic assessment:** each target arm executed. Each failed on its planned assertion: the run got past the point where it must refuse, and exited rc 2 from the unimplemented stage instead of rc 1 with the tag. It was not a harness fault, because the same harness PASSes no-context and list-kv-objects in the same run.

**GREEN** (`860d9d14`). The Task 1 verify line:

```
PASS prod-api rc=1 az_calls=0 curl_calls=0 pod_reads=0
PASS local-api rc=1 az_calls=0 curl_calls=0 pod_reads=0
PASS no-context rc=2 kubectl_calls=0
PASS stale-server rc=1 kubectl_calls=1(config view)
PASS dev-profile rc=1 pod_reads=1 deployment_reads=0 az_calls=0 curl_calls=0
PASS rollout-mixed rc=1 pod_reads=1 deployment_reads=0 az_calls=0 curl_calls=0
PASS no-ready-pod rc=1 pod_reads=1 deployment_reads=0 az_calls=0 curl_calls=0
PASS dry-run-plan rc=0 steps=17 az_calls=0 curl_calls=0
PASS list-kv-objects rc=0 objects=4
rc=0
missing=0 fail_lines=0 stub_secret_leaks=0
```

- The verify line's own fail direction: empty output gives `missing=9`.
- `deployment_reads=0` is enforced, not just printed: any kubectl call other than `config view` or the exact pod read fails the arm.

**Break arms.** Each ran on a scratch copy; the real tree was untouched.

| Break | Arm(s) that went red |
|---|---|
| Profile check reads only `.items[0:1]` | `rollout-mixed` (rc 2: the old dev pod was never looked at) |
| Ready condition ignored | `no-ready-pod` |
| `staging_kube_guard` call replaced by an empty-context check | `stale-server`, `dry-run-plan` (config reads 0) |
| Also `get deployment` before the pod read | `dev-profile`, `rollout-mixed`, `no-ready-pod`, `dry-run-plan` ("kubectl calls beyond config view and the pod read: 1") |

**AC: no SQL, no DB client.** Plan's command: `seed=0 control=21`, rc 0. A planted `psql -c` line counts 1.

### Task 2

**Verify 1** (real environment, guaranteed-absent identity file):

```
REFUSED [identity-file]: identity file '/tmp/tmp.4ekOvf5nS3' is not a readable file, so the cluster this context points at cannot be checked (written by plan 29-09)
rc=1 identity_refusals=1
```

With the default identity file (`k8s/staging/azure-identity.yaml`, absent until 29-09), the same refusal happens with rc 1. `--stage order` gives a VOID naming 29-18, rc 2.

**Verify 2** (`--selftest`): all 10 named arms PASS, plus `operator-deleted-on-success`. `rc=0 missing=0 fail_lines=0 stub_secret_leaks=0`.

**The `operator-deleted-on-failure` arm's recorded stub log** (`curl-flow.log`; the stub returns 500 on the culinary-pack seed):

```
GET http://127.0.0.1:19000/health/ready -> 200
POST http://127.0.0.1:18080/realms/master/protocol/openid-connect/token -> 200
POST http://127.0.0.1:18080/admin/realms/jtoye-staging/users -> 201
GET http://127.0.0.1:18080/admin/realms/jtoye-staging/roles/admin -> 200
POST http://127.0.0.1:18080/admin/realms/jtoye-staging/users/op-0001/role-mappings/realm -> 204
POST https://auth-staging.olajay.co.uk/realms/jtoye-staging/protocol/openid-connect/token -> 200
GET https://api-staging.olajay.co.uk/api/v1/admin/tenants -> 200
GET http://127.0.0.1:18080/admin/realms/jtoye-staging/users/op-0001 -> 200
PUT http://127.0.0.1:18080/admin/realms/jtoye-staging/users/op-0001 -> 204
GET http://127.0.0.1:18080/admin/realms/jtoye-staging/users/op-0001 -> 200
POST https://auth-staging.olajay.co.uk/realms/jtoye-staging/protocol/openid-connect/token -> 200
GET http://127.0.0.1:18080/admin/realms/jtoye-staging/users?username=vendor.staging@olajay.co.uk&exact=true -> 200
GET http://127.0.0.1:18080/admin/realms/jtoye-staging/users/vendor-0001 -> 200
GET http://127.0.0.1:18080/admin/realms/jtoye-staging/users/vendor-0001 -> 200
POST https://auth-staging.olajay.co.uk/realms/jtoye-staging/protocol/openid-connect/token -> 200
GET https://api-staging.olajay.co.uk/api/v1/staff/me -> 200
GET https://api-staging.olajay.co.uk/api/v1/shops?size=100 -> 200
GET https://api-staging.olajay.co.uk/api/v1/products?shopId=66666666-7777-4888-9999-000000000000&size=1 -> 200
GET https://api-staging.olajay.co.uk/api/v1/products/culinary-packs -> 200
POST https://api-staging.olajay.co.uk/api/v1/products/culinary-packs/seed -> 500
DELETE http://127.0.0.1:18080/admin/realms/jtoye-staging/users/op-0001 -> 204
```

- The arm line: `PASS operator-deleted-on-failure rc=1 seed_500_line=20 operator_delete_line=21 kv_reads=4 pinned=5 argv_secrets=0`.
- The admin API is only ever `127.0.0.1`. The tokens come from the public issuer, and every az call ends with the pin.
- After the selftest, `pgrep -af '[s]leep 600'` shows no surviving stub port-forward. It was killed by PID.

**Break arms** (scratch copies):

| Break | Result |
|---|---|
| Trap never calls `delete_operator` | `operator-deleted-on-failure` FAIL ("the operator was never DELETEd"), `operator-deleted-on-success` FAIL |
| `client_secret=$(cat …)` in argv | failure arm: "3 curl argv line(s) carry a Key Vault value"; success arm: marker FAIL |
| A secret echoed after the reads | both arms "secret marker printed". The selftest's own output still carries 0 markers, because reasons never quote a value |
| Seed failure ignored (both the status and the `failed` checks removed) | failure arm: "the stage went on past the failed seed" |

The first attempt at the last arm removed only the status check. It stayed green, because the `failed`/`created` check still stopped the stage. That is defence in depth, not a vacuous arm, and the stronger break above went red.

**AC greps:**
- `command grep -c 'seed-operator-' scripts/staging-seed.sh` gives 6.
- `trap cleanup EXIT` is at line 100, and `cleanup()` (which calls `delete_operator`) is at line 85.
- Fail direction: the same trap pattern over `scripts/lib/azure-guard.sh`, which has no trap, gives 0.

**Gates on the final tree:**
- `check-az-subscription-pin.sh`: rc 0. It discovered 5 az-using scripts with findings 0, and this script is counted. A raw `az keyvault secret show` planted in a copy of this script gives `FINDING scripts/staging-seed.sh:1136`, findings=1, rc 1.
- `check-doc-citations.sh` (jtoye-ops python): rc 0, 46 verified, 0 violations.
- `check-gate-enforcement.sh`: rc 0.
- `ci-cd.yaml` parses (PyYAML). `k8s-validate` has 13 steps, one of them the 29-06 step.
- `bash -n`: rc 0. shellcheck is not installed on this host, so it was not run.

## Files Created/Modified

- `scripts/staging-seed.sh`: guards, `--dry-run` plan, `--list-kv-objects`, the vendor stage and the 11-arm `--selftest`.
- `.github/workflows/ci-cd.yaml`: the `k8s-validate` step "Prove the staging seed guards and operator cleanup (29-06 --selftest)".
- `evidence/29-06-t1-red-{prod-api,stale-server,dev-profile,rollout-mixed}.json`: the RED records.

## Decisions Made

See `key-decisions`. In short:
- The vendor gets GROUP_ADMIN through the staff API, because strict scoping provisions nothing.
- The operator carries the tenant, because prod reads the tenant from the JWT only.
- The two facts-only gates are never waived.
- The onboarding is MARKETPLACE, as a sole trader.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] GROUP_ADMIN grant step added**
- **Found during:** Task 2, reading `ShopAccessService`.
- **Issue:** The plan and RESEARCH Open Question 2 say "the first JIT user becomes the bootstrap GROUP_ADMIN". In the code, JIT provisioning runs only when strict scoping is OFF. Staging has `access.strict-scoping: "true"`, so a new vendor would get 403 on `POST /api/v1/shops`.
- **Fix:** The vendor's first call (`GET /api/v1/staff/me`) records it in `user_directory`. The operator then calls `POST /api/v1/staff/grant` with `{userId, shopId: null, GROUP_ADMIN}`, on the `core-api` azp that `HumanCallerGuard` accepts. A vendor that is already GROUP_ADMIN is skipped.
- **Commit:** `2a099d72`.

**2. [Rule 3 - Blocking] The operator carries `tenant_id`**
- **Issue:** The onboarding admin endpoints are tenant-scoped. In prod the tenant comes only from the JWT; `TenantFilter`'s `X-Tenant-Id` is dev/local/test only.
- **Fix:** Once the tenant exists, the operator's attribute is set and read back, then its token is re-issued. The only tenant-less calls are the tenant list/create (at most 2). Those increment `tenant_context_missing_total` far below the `TenantIsolationFailure` rate of 0.1/s for 2 min.
- **Commit:** `2a099d72`.

**3. [Rule 1 - Bug in the plan's order] Shop, culinary pack and trader identity come BEFORE the onboarding**
- `CreateOnboardingRequest.shopId` is `@NotNull`, so the shop has to exist first.
- `ALLERGEN_DATA_COMPLETE` is evaluated at submit and FAILs a shop with no products. That would move the onboarding to ACTION_REQUIRED, from which a resolve cannot advance it: the recompute only acts from VERIFYING.
- `TRADER_IDENTITY` needs the identity and the shop email.
- **Commit:** `2a099d72`.

**4. [Rule 2 - Correctness] TRADER_IDENTITY and ALLERGEN_DATA_COMPLETE are never resolved**
- The plan says "resolve each open gate". The go-live guard requires these two PASSED on the facts and re-evaluates both at go-live, so a waiver would only be refused later and would hide a real defect.
- An open one is a FAIL that names the gate's reason. The others are WAIVEd, not PASSed, with "staging seed, scripted — D-26". Nobody verified a hygiene rating, so a waiver records that more honestly than a pass.
- **Commit:** `2a099d72`.

**5. [Rule 2 - Missing critical] The selftest wired into CI, plus a success-path arm**
- The selftest now runs in `ci-cd.yaml` (not in `files_modified`). This follows 29-03 and 29-04: an arm that runs nowhere proves nothing after its commit.
- `operator-deleted-on-success` was added, because "deleted on every exit path" includes the path that succeeds. That arm also proves a re-run makes no trader-identity or onboarding write.
- **Commit:** `2a099d72`.

**6. [Rule 2 - Missing critical] Other additions**
- The shop is created with every-day hours (00:00 to 23:59). A shop saved without hours takes no storefront orders (37-20), and 29-18 places one.
- The vendor token's `tenant_id` claim is checked, as well as the attribute.
- A vendor whose `tenant_id` names another tenant is REFUSED [vendor-tenant], never re-homed.
- The profile guard also refuses `SPRING_PROFILES_INCLUDE`, and any env value or argument that mentions `spring.profiles`.
- **Commits:** `860d9d14`, `2a099d72`.

**7. [Recorded] No Idempotency-Key header is sent**
- The plan says every POST "the API documents as Idempotency-Key aware" carries a stable key. None of this stage's endpoints adopts `@Idempotent`. Measured: only orders.create, customers.create, the guest order, DSAR intake and integration credentials do.
- Idempotency here comes from check-before-write on every step. The order stage (29-18) is where the header applies.

**8. [Recorded] Refusals and VOIDs**
- `--stage order|all` gives a VOID (rc 2) before the guards run.
- A failed or unparseable pod read is REFUSED [profile]: an unverifiable profile is refused, never assumed.

---

**Total deviations:** 8.
- 3 blocking or order fixes, all measured in the code.
- 3 correctness or critical additions.
- 2 recorded interpretations.

**Impact:** without 1 to 3, the live run in 29-14 would have stopped at the first shop create or at the first gate resolve.

## TDD Gate Compliance

Task 1 (`tdd="true"`):
- RED `3841db98` (`test(29-06)`) precedes GREEN `860d9d14` (`feat(29-06)`).
- `RED_EVIDENCE_OK` for prod-api, stale-server, dev-profile and rollout-mixed, with the semantic assessment above.
- No REFACTOR commit.

Task 2 is `type="auto"`. Its new arms were shown failing by break arms rather than by a RED commit.

## Issues Encountered

None beyond the deviations.

## Known Stubs

None. The order stage is a deliberate VOID that names plan 29-18; it is not a stub that feeds any output.

## Threat Flags

None. The surface is the one in the threat model: the public API and token endpoint, and the Keycloak admin API through a port-forward.

## User Setup Required

None now. The live run (29-14) needs:
- the four Key Vault objects (29-13's writer),
- `k8s/staging/azure-identity.yaml` (29-09),
- a shell that exports `JTOYE_AZURE_SUBSCRIPTION_ID`.

## Next Phase Readiness

The seed is ready for the tracer (29-14). Unknowns that only the live run can answer:
- the FHRS gate's real outcome from inside the cluster (MANUAL_REVIEW is expected; FAILED would give ACTION_REQUIRED and one resubmit),
- whether the first culinary pack passes `AllergenCompletenessGate`,
- the gate chain's timing (deadline 180 s, `STAGING_SEED_GATE_DEADLINE_SECONDS`).

## Self-Check: PASSED

- FOUND: `scripts/staging-seed.sh`, `.github/workflows/ci-cd.yaml`, and the four `evidence/29-06-t1-red-*.json`.
- FOUND: commits `3841db98`, `860d9d14`, `2a099d72` (ancestors of HEAD).
- Stub scan (`rg -uu 'TODO|FIXME|placeholder|coming soon'` over the script): no match, rc 1.

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-10-18*
