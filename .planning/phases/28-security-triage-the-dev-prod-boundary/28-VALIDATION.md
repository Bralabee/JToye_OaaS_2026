---
phase: 28
slug: security-triage-the-dev-prod-boundary
status: planned
nyquist_compliant: true
wave_0_complete: true
created: 2026-08-10
updated: 2026-08-10
---

# Phase 28 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Populated by the planner from 28-RESEARCH.md § Validation Architecture.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers 1.21.4 (real Postgres + RLS); bash `check-*.sh` gates |
| **Config file** | `core-java/build.gradle.kts` — `tasks.test` excludes `@Tag("testcontainers")`; `tasks.register<Test>("integrationTest")` includes it |
| **Quick run command** | `cd core-java && ./gradlew :core-java:test --tests '<TouchedClass>'` |
| **Integration run** | `cd core-java && ./gradlew :core-java:integrationTest --tests '<Class>'` |
| **Full suite command** | `cd core-java && ./gradlew :core-java:test :core-java:integrationTest` (mandatory for the auth-touching wave — `trap_scope_gate_integrationtest_regression`) |
| **Estimated runtime** | quick ~60s · integration per-class ~2-5 min · FULL integrationTest 46-49 min in CI |
| **Count manifest** | `docs/metrics.json`, regenerated ONLY by `scripts/docs-freshness.sh --write` — never arithmetic |

---

## Sampling Rate

- **After every task commit:** the touched-class quick command + `bash scripts/check-gate-enforcement.sh`
- **After every wave:** `./gradlew :core-java:test` plus that wave's integration classes
- **Before `/gsd:verify-work`:** FULL `./gradlew :core-java:test :core-java:integrationTest` green
- **Max feedback latency:** 900 seconds for per-task arms; the FULL suite is a wave/phase gate, not a per-task one

**Manifest exception, DESIGNED:** plans 28-01 through 28-10 are explicitly forbidden from touching
`docs/metrics.json` or the prose counts. `check-doc-metrics.sh` is therefore expected RED from the
first test-adding commit until plan 28-11 closes it. This is the 33-07 precedent: one plan owns the
manifest, the red is declared in advance, and every intervening plan states it in its SUMMARY.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 28-01-T1 | 28-01 | 1 | SEC-01 | T-28-01, T-28-03 | A1 cross-tenant write blocked on a stack rebuilt from HEAD | integration + live | `./gradlew :core-java:integrationTest --tests '*CrossTenantAuthzIntegrationTest' --tests '*ShopPromotionsRlsPolicyIntegrationTest'` ; `bash scripts/check-runtime-freshness.sh` | ✅ 6+3 tests | ⬜ pending |
| 28-01-T2 | 28-01 | 1 | SEC-04 | T-28-02 | Every RLS-enabled non-exempt table has >=1 policy | integration | `./gradlew :core-java:integrationTest --tests '*RlsContractTest'` | ✅ extend | ⬜ pending |
| 28-02-T1 | 28-02 | 1 | SEC-03 | T-28-05, T-28-08 | Served `/v3/api-docs` omits the tenant header when `TenantFilter` is absent | integration | `./gradlew :core-java:integrationTest --tests '*TenantHeaderAbsentDocumentTest'` | ❌ new | ⬜ pending |
| 28-02-T2 | 28-02 | 1 | SEC-03 | T-28-06, T-28-07 | Staging/prod doc gating shown capable of failing | integration | `./gradlew :core-java:integrationTest --tests '*OpenApiDevProfileGatingTest' --tests '*StagingActuatorPortIsolationTest' --tests '*OpenApiProdProfileGatingTest'` | ✅ | ⬜ pending |
| 28-03-T1 | 28-03 | 1 | SEC-02 | T-28-09, T-28-12 | No stored object carries a Content-Type outside the allowlist | shell gate | `bash scripts/check-media-content-types.sh` ; `bash scripts/check-gate-enforcement.sh` | ❌ new | ⬜ pending |
| 28-03-T2 | 28-03 | 1 | SEC-02 | T-28-10, T-28-11 | Census recorded with controls; deferred sweep dated; DEC-5 filed | doc + CLI | `test -s docs/security/MEDIA-BACKFILL-PLAN-2026-08-10.md` ; `gh issue view <n>` | ❌ new | ⬜ pending |
| 28-04-T1 | 28-04 | 1 | SEC-04 | T-28-13, T-28-15, T-28-16 | Grant re-checked before every emit, under a pinned tenant | unit | `./gradlew :core-java:test --tests '*OrderSse*'` | ✅ modify | ⬜ pending |
| 28-04-T2 | 28-04 | 1 | SEC-04 | T-28-14 | Revoked blocked AND still-granted delivered, same broadcast; miss-path resolves under NOSUPERUSER | unit + integration | `./gradlew :core-java:test --tests '*OrderSseGrantRecheckTest'` ; `./gradlew :core-java:integrationTest --tests '*OrderSse*'` | ❌ new | ⬜ pending |
| 28-04-T3 | 28-04 | 1 | SEC-04 | T-28-17, T-28-18 | STOMP temporal behaviour measured; #281 residuals stated | measurement | `./gradlew :core-java:test --tests '*OrderSse*'` | n/a | ⬜ pending |
| 28-05-T1 | 28-05 | 2 | SEC-02 | T-28-19, T-28-20 | 11 sanitized dispositions, E1 client table regenerated | doc assertion | `grep -cE '^\| *(A1\|A2\|A3\|B1\|B2\|C1\|C2\|C3\|C4\|D1\|E1) *\|' docs/security/PENTEST-TRIAGE.md` | ❌ new | ⬜ pending |
| 28-05-T2 | 28-05 | 2 | SEC-02 | T-28-22, T-28-23 | A removed/blanked/missing row fails CI | shell gate | `bash scripts/check-pentest-triage.sh` ; `bash scripts/check-gate-enforcement.sh` | ❌ new | ⬜ pending |
| 28-05-T3 | 28-05 | 2 | SEC-02, SEC-03 | T-28-20, T-28-21 | A client not intended to reach core-api gets an actual 401, with a control | live arm + CLI | `bash scripts/check-pentest-triage.sh` ; `jq -e . infra/keycloak/realm-export.template.json` | ✅ AudienceValidator | ⬜ pending |
| 28-06-T1 | 28-06 | 2 | SEC-04 | T-28-24, T-28-26, T-28-28 | Absent principal denied; trust declared not inferred | unit + integration | `./gradlew :core-java:test --tests '*ShopAccess*' --tests '*SystemPrincipal*'` | ❌ new | ⬜ pending |
| 28-06-T2 | 28-06 | 2 | SEC-04 | T-28-25, T-28-29 | Only entry points reaching a gated call declare themselves | integration | `./gradlew :core-java:integrationTest --tests '*OrderSse*' --tests '*MediaAsset*'` | ✅ + new | ⬜ pending |
| 28-06-T3 | 28-06 | 2 | SEC-04 | T-28-25, T-28-27 | #284 guard reds on a removed declaration; FULL suite green | FULL suite | `./gradlew :core-java:test :core-java:integrationTest` | ❌ new | ⬜ pending |
| 28-07-T1 | 28-07 | 3 | SEC-04 | T-28-30, T-28-32, T-28-34 | Runtime role bootstrapped with `FOR ROLE` defaults; backup defect repaired | SQL + live psql | `grep -c "FOR ROLE jtoye_app" infra/db/create-runtime-role.sql infra/backups/create-backup-role.sql` | ❌ new | ⬜ pending |
| 28-07-T2 | 28-07 | 3 | SEC-04 | T-28-31, T-28-35 | Flyway credential decoupled; `spring.flyway.url` still declared | integration | `./gradlew :core-java:integrationTest --tests '*FreshChainMigrationIntegrationTest'` | ✅ extend | ⬜ pending |
| 28-07-T3 | 28-07 | 3 | SEC-04 | T-28-31, T-28-36 | Half-applied environment fails verification | shell | `bash scripts/verify-env.sh --list-required` ; `docker compose -f docker-compose.full-stack.yml config` | ✅ extend | ⬜ pending |
| 28-08-T1 | 28-08 | 4 | SEC-04 | T-28-37 | Boot refuses when the role owns its tables | integration | `./gradlew :core-java:integrationTest --tests '*DatabaseConfigurationValidatorOwnershipTest'` | ❌ new | ⬜ pending |
| 28-08-T2 | 28-08 | 4 | SEC-04 | T-28-38, T-28-39, T-28-40 | A table created AFTER the grants is readable by the runtime role | integration | `./gradlew :core-java:integrationTest --tests '*RuntimeRoleGrantContractTest' --tests '*RlsContractTest'` | ❌ new | ⬜ pending |
| 28-08-T3 | 28-08 | 4 | SEC-04 | T-28-41, T-28-42, T-28-43 | Live stack runs as a non-owner; isolation measured with a superuser control | live + shell | `bash scripts/check-runtime-freshness.sh` ; live psql owner-count query | n/a | ⬜ pending |
| 28-09-T1 | 28-09 | 5 | SEC-04 | T-28-44, T-28-48 | Bootstrap runs a pinned digest; a wrong digest fails loud | shell | `docker compose -f docker-compose.full-stack.yml config` ; `bash scripts/check-image-supply-chain.sh` | ✅ | ⬜ pending |
| 28-09-T2 | 28-09 | 5 | SEC-04 | T-28-45, T-28-46, T-28-47, T-28-49 | Anonymous LIST refused, anonymous GET still 200 | live HTTP + shell gate | `curl -s -o /dev/null -w "%{http_code}" "http://localhost:9000/jtoye-images?list-type=2&max-keys=10"` ; `bash scripts/check-media-content-types.sh` | ❌ new (28-03) | ⬜ pending |
| 28-10-T1 | 28-10 | 6 | SEC-04 | T-28-54 | Owner confirms the credential set before rotation | **MANUAL — blocking human gate** | n/a (see Manual-Only Verifications) | n/a | ⬜ pending |
| 28-10-T2 | 28-10 | 6 | SEC-04, SEC-02 | T-28-50, T-28-51, T-28-52, T-28-53, T-28-55, T-28-56 | Superseded value fails AND current succeeds, same run, per surface | shell | `bash scripts/check-infra-exposure.sh` ; `bash scripts/check-runtime-freshness.sh` | ✅ C1/C2/C3, D | ⬜ pending |
| 28-10-T3 | 28-10 | 6 | SEC-04 | T-28-53 | Runbook records the procedure and its failure shapes | doc assertion | `grep -c "check-infra-exposure" docs/runbooks/credential-rotation.md` | ❌ new | ⬜ pending |
| 28-11-T1 | 28-11 | 7 | all | T-28-60 | Manifest and prose agree; both halves of the loop pass | shell | `bash scripts/docs-freshness.sh` ; `bash scripts/check-doc-metrics.sh` | ✅ | ⬜ pending |
| 28-11-T2 | 28-11 | 7 | SEC-02 | T-28-60, T-28-61 | Final dispositions; every gate runs; HANDOFF count is the live measurement | shell | `bash scripts/check-pentest-triage.sh` ; `bash scripts/check-gate-enforcement.sh` ; `ls scripts/check-*.sh scripts/docs-freshness.sh \| wc -l` | ✅ | ⬜ pending |
| 28-11-T3 | 28-11 | 7 | all | T-28-57, T-28-58, T-28-59, T-28-62 | Runtime matches the branch; branch not behind base; FULL suite green | shell + FULL suite | `bash scripts/check-runtime-freshness.sh` ; `bash scripts/check-branch-behind-base.sh` ; `./gradlew :core-java:test :core-java:integrationTest` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

**None.** Every verification shape this phase needs already exists in the tree:

- Testcontainers + real-Postgres RLS harness (`IntegrationTestSupport`, `RlsContractTest`) — extended, not invented
- Profile-gating integration tests with a non-vacuity control (`OpenApiProdProfileGatingTest`) — the recipe for SC-3's new class
- Served-document fetch via MockMvc (`OpenApiSnapshotTest:100-104`) — the base recipe
- Static doc gate with a 0/1/2 exit contract (`check-geo-attribution.sh`) — the shape for `check-pentest-triage.sh`
- Runtime-dependent gate with a conf entry (`check-live-shop-coordinates.sh`) — the shape for `check-media-content-types.sh`
- Grafana/broker credential arms with an instrument-validity control (`check-infra-exposure.sh` C1/C2/C3, D) — reused verbatim
- Runtime and branch parity gates (`check-runtime-freshness.sh`, `check-branch-behind-base.sh`)

Every new test and gate listed above is created inside the plan that first needs it, and every new
`scripts/check-*.sh` lands with its wiring in the SAME task (`check-media-content-types.sh` +
`gate-enforcement.conf` in 28-03-T1; `check-pentest-triage.sh` + `ci-cd.yaml` in 28-05-T2). No plan
pre-declares a sibling plan's script — the conf VOIDs on entries naming scripts that do not exist.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Owner confirms the identity of #552's six credentials (research Assumption A5) before rotation executes | SEC-04 | Only the owner can check the inferred key set against the private pentest report, which is not in this repository and must not enter it. Rotating the wrong set leaves a live credential live while SEC-04 is recorded satisfied. | Plan 28-10 Task 1, `checkpoint:decision`, gate `blocking-human`. Present the enumerated list BY KEY NAME only — never a value. Owner replies `confirm-six`, `include-runtime-password`, or `amend-set` plus corrected names. Record the answer verbatim with its date; carry it into the triage doc's B1 row. No rotation commit may predate the recorded answer. |

---

## Falsifiability Ledger

Every criterion in this phase states its fail direction. The arms that MUST be observed red, because
each has a recorded history of passing vacuously in this repository or a directly analogous one:

| Arm | Plan | Why it can otherwise pass while proving nothing |
|-----|------|--------------------------------------------------|
| A1 break arm reds EXACTLY 1 named test | 28-01 | zero failures means the guard cannot fail; >1 means the arm is broader than the claim |
| RLS-policy sweep denominator | 28-01 | a query matching no rows satisfies "zero policy-less tables" vacuously |
| Served-document arm 2 (filter present) | 28-02 | without it, arm 1's absence is indistinguishable from an unbuildable document |
| Served-document arm 3 (`paths` non-empty) | 28-02 | an empty document satisfies the absence assertion vacuously |
| Media gate VOIDs on a stopped stack | 28-03 | a gate failing OPEN on missing input is worse than no gate |
| EXIF census positive control | 28-03 | "0 objects carry EXIF" is indistinguishable from "exiftool reported nothing" |
| SSE **liveness** arm reds under an unconditional deny | 28-04 | a re-check denying everyone passes the security arm perfectly while killing the KDS |
| SSE miss-path arm reds when `TenantContext.set` is neutralised | 28-04 | the `set_config` is defence in depth under a global aspect; breaking it proves nothing |
| Triage gate reds on a BLANKED disposition cell | 28-05 | distinguishes "mentioned" from "dispositioned" |
| Triage gate VOIDs when the doc is moved aside | 28-05 | the script names all 11 IDs in its own source and could self-match |
| Ownership test's profile arm | 28-08 | the validator is `@Profile("!test")`; a `test`-only context passes while never running it |
| Future-table grant arm with `FOR ROLE` OMITTED | 28-08 | the only arm that distinguishes the correct clause from the inert one already live on `jtoye_backup` |
| Anonymous GET measured on the SAME key before and after | 28-09 | no existing test fetches a storefront image anonymously; breaking it would be silent |
| Superseded-credential-fails direction, per surface | 28-10 | the old value working is precisely what an unrotated credential looks like |
| `check-doc-metrics` reds on a one-digit prose change | 28-11 | README sat at 921 for months with gate 1 green throughout |
| `check-runtime-freshness` VOIDs (rc=2) with one service stopped | 28-01, 28-08, 28-11 | it previously printed `PASS: 3 … (1 unverified)` and exited 0 |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or are the declared manual gate (28-10-T1)
- [x] Sampling continuity: no 3 consecutive tasks without an automated verify
- [x] Wave 0 covers all MISSING references (none required — every shape exists in-tree)
- [x] No watch-mode flags
- [x] Feedback latency < 900s for per-task arms; the FULL suite is a wave/phase gate
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** planner-approved 2026-08-10
