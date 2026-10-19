---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 08
subsystem: infra
tags: [kubernetes, kustomize, flyway, pgjdbc, tls, keycloak, oidc, cert-manager, traefik, hpa, pdb, render-gate, bash, awk, junit, tdd]

requires:
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-02: INV-7 exact NetworkPolicy table (same-namespace podSelectors for keycloak/redis/rabbitmq/mailpit), WI_TARGETS, the selftest-copy harness shape"
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-03: k8s/platform/traefik/supported-annotations.txt (86 documented nginx annotation suffixes)"
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-04: staging realm jtoye-staging keeps the template's core-api client (Blocker A left open there)"
  - phase: 28-runtime-role-split
    provides: "application.yml spring.flyway.user/password nested defaults onto DB_MIGRATION_USER/DB_MIGRATION_PASSWORD; DatabaseConfigurationValidator refuses an owner runtime role"
provides:
  - "core-java env DB_MIGRATION_USER / DB_MIGRATION_PASSWORD (postgres-credentials migration-username / migration-password) and DB_SSL_MODE / DB_SSL_FACTORY (app-config db.ssl-mode / db.ssl-factory), none optional"
  - "application.yml datasource url ?sslmode=${DB_SSL_MODE:prefer}&sslfactory=${DB_SSL_FACTORY:org.postgresql.ssl.LibPQFactory}; base app-config verify-full + DefaultJavaSSLFactory, k8s/local prefer + LibPQFactory"
  - "check-env-contract.sh REQUIRES the migrator pair (the two reviewed omissions removed)"
  - "scripts/k8s-local-secrets.sh: migration-username/password in postgres-credentials from .env DB_MIGRATION_*, both in the value preflight"
  - "base keycloak.client-id core-api (Blocker A closed); staging keycloak.issuer.uri = in-cluster Keycloak, public issuer unchanged; staging admin seam on; redis/rabbitmq/STOMP relay/Mailpit SMTP hosts in jtoye-staging"
  - "k8s/staging/scale-patch.yaml: HPA minReplicas 1 x3, PDB minAvailable 0 x3; staging replicas list at 1"
  - "Ingress TLS one host per entry: base jtoye-api-tls / jtoye-app-tls, staging jtoye-staging-api-tls / jtoye-staging-app-tls; SSE Ingress references the api Secret with no cert-manager annotation"
  - "Staging Ingresses serve base's six security headers plus X-Robots-Tag noindex, nofollow"
  - "INV-14 (client id is a login client of the realm template) and INV-15 (tls one host, one managed owner per Secret, staging noindex + header drift guard, production no noindex, Traefik-documented annotations); --selftest-inv15 wired into ci-cd.yaml k8s-validate"
affects: [29-09, 29-10, 29-12, 29-14, 29-15, 29-16, 29-22]

actuals:
  tokens: 41936
  tasks: 3
  commits: 6
plan_head_before: e3621e3bd289903ef505088a73c9353464dcb961
plan_head_after: c6eb927ddb9e130054463b9505e26af5c37403f8

tech-stack:
  added: []
  patterns:
    - "A JDBC TLS posture is config: the URL carries ${DB_SSL_MODE}/${DB_SSL_FACTORY} with pgjdbc's own defaults, and the test hands the RESOLVED url to org.postgresql.Driver.parseURL so it asserts what the driver reads"
    - "INGRESS_AWK reads Ingresses structurally (top key, child section, block scalar) into ING/ANN/SNIP/TLS records; never a forward scan of sorted kustomize output"
    - "A gate that reads a file outside k8s/ makes the selftest stage it too (selftest_stage copies k8s/ plus the realm template), or every arm VOIDs"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/config/DatasourceTlsPropertyTest.java
    - k8s/staging/scale-patch.yaml
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-08-t1-red-verify-full.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-08-t1-red-default-prefer.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-08-t3-red-two-host.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-08-t3-red-shared-secret.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-08-t3-red-missing-noindex.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-08-t3-red-unsupported-annotation.json
  modified:
    - core-java/src/main/resources/application.yml
    - k8s/base/core-java-deployment.yaml
    - k8s/base/configmap.yaml
    - k8s/base/frontend-deployment.yaml
    - k8s/base/ingress.yaml
    - k8s/base/sse-ingress.yaml
    - k8s/local/configmap-patch.yaml
    - k8s/local/ingress-patch.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/staging/kustomization.yaml
    - k8s/staging/ingress-hosts-patch.yaml
    - k8s/staging/sse-ingress-hosts-patch.yaml
    - k8s/scripts/check-env-contract.sh
    - k8s/scripts/check-render-invariants.sh
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - scripts/k8s-local-secrets.sh
    - .github/workflows/ci-cd.yaml
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - k8s/LOCAL.md
    - k8s/DEPLOYMENT.md
    - k8s/QUICK_START.md
    - .planning/codebase/INTEGRATIONS.md
    - .planning/WINDOWS.md

key-decisions:
  - "The datasource URL defaults are pgjdbc's own (sslmode=prefer, LibPQFactory), so an environment that sets neither DB_SSL_* key connects exactly as before; deployed targets get verify-full + DefaultJavaSSLFactory from base app-config and k8s/local patches back (compose Postgres reads ssl=off)"
  - "Blocker A is fixed at the base, not the staging overlay: `frontend` named no client in any realm; base and k8s/local now both say core-api"
  - "INV-14 is stronger than 'the id exists in the template': the client must be non-built-in, enabled, standardFlowEnabled, not bearerOnly and not public (admin-cli and integration-catalog-ro both exist and both fail)"
  - "Staging PDB minAvailable is 0, not 1: a minAvailable-1 PDB over a 1-replica Deployment allows zero disruptions and stalls every AKS node-image drain"
  - "The staging replicas: list is kept at 1 (= the HPA floor) rather than deleted: deleting it renders base spec.replicas 3/5/3, which every kubectl apply re-asserts (11 app pods) until HPA scale-down stabilisation"
  - "INV-15(c) is two-sided: noindex REQUIRED on every staging Ingress and FORBIDDEN on every other deployed target, so a noindex that leaks into base cannot silently de-list production"
  - "The SSE Ingress keeps no cert-manager annotation; the main Ingress is the one owner of jtoye-api-tls / jtoye-staging-api-tls"

patterns-established:
  - "Render invariant reading an input outside k8s/: load it once with PARSE ERROR on missing tool/file/empty read, and stage it in every selftest copy"
  - "Copied-not-inherited config text (a JSON6902 replace of a block scalar) gets a drift guard against the base render"

requirements-completed: [DPLY-01, DPLY-02]

coverage:
  - id: D1
    description: "Datasource URL carries sslmode/sslfactory from DB_SSL_MODE/DB_SSL_FACTORY; default prefer+LibPQFactory, deployed verify-full+DefaultJavaSSLFactory reach pgjdbc on the datasource and Flyway URLs; Flyway user follows DB_MIGRATION_USER"
    requirement: "DPLY-01"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/config/DatasourceTlsPropertyTest.java (3 tests; RED 2 failed on the TLS-less URL, GREEN tests=3 failures=0 errors=0 skipped=0 in build-local)"
        status: pass
      - kind: unit
        ref: "./gradlew :core-java:test --rerun (full unit suite: 221 fresh reports, tests=1921 failures=0 errors=0)"
        status: pass
      - kind: integration
        ref: "./gradlew :core-java:integrationTest --tests '*FreshChainMigrationIntegrationTest' --tests '*RuntimeRoleGrantContractTest' --rerun (5+5 tests, 0 failures)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Blocker E in the render: core-java reads migration-username/migration-password exactly once in staging; the env-contract gate requires the pair"
    requirement: "DPLY-01"
    verification:
      - kind: other
        ref: "staging render 'key: migration-username' count: 1, rc 0 (pre-change 0, rc 1)"
        status: pass
      - kind: other
        ref: "check-env-contract.sh rc 0; break arm (DB_MIGRATION_* env removed from the Deployment) rc 1 naming DB_MIGRATION_USER/PASSWORD as direction (b) violations; restored by sha, rc 0"
        status: pass
    human_judgment: false
  - id: D3
    description: "Blocker A closed: staging/production render keycloak.client-id core-api; INV-14 fails a client the realm template lacks or cannot log in with"
    requirement: "DPLY-01"
    verification:
      - kind: other
        ref: "check-render-invariants.sh: INV-14 OK on k8s/staging and k8s/production; pre-change tree 12c57184 with the new gate rc 1 naming 'frontend' for both; arms does-not-exist / admin-cli / integration-catalog-ro rc 1; template missing / corrupt / clientless / no jq rc 2"
        status: pass
    human_judgment: false
  - id: D4
    description: "Staging app-config: internal issuer + public issuer, admin seam, in-namespace Redis/RabbitMQ/STOMP/Mailpit hosts, no jtoye-infrastructure host; HPA floors 1, PDB 0"
    requirement: "DPLY-01"
    verification:
      - kind: other
        ref: "staging render 'jtoye-infrastructure' count 0, rc 0 (pre-change exactly 4: keycloak.admin.base-url, rabbitmq.host, redis.host, stomp.broker.relay-host, rc 1)"
        status: pass
      - kind: other
        ref: "HPA minReplicas histogram [3 1], rc 0 (pre-change [2 3 / 1 5], rc 1); PDB minAvailable 0 x3; Deployment replicas 1 x3; maxReplicas 10/10/20 unchanged"
        status: pass
    human_judgment: false
  - id: D5
    description: "Per-host TLS with one cert-manager owner per Secret, staging noindex with base-header drift guard, production indexable, every nginx annotation Traefik-documented (INV-15)"
    requirement: "DPLY-01"
    verification:
      - kind: unit
        ref: "bash k8s/scripts/check-render-invariants.sh --selftest-inv15 (8/8 caught=yes, CLEAN rc=0; RED against the stub: 0/8, classifier RED_EVIDENCE_OK x4)"
        status: pass
      - kind: other
        ref: "staging: managed Secrets jtoye-staging-api-tls, jtoye-staging-app-tls, 1 host each (pre-change: jtoye-staging-tls with 2 hosts, requested twice); k8s/local: 0 tls entries on both Ingresses"
        status: pass
      - kind: other
        ref: "pre-change manifests a64e2e1e with the final gate: rc 1, 12 INV-15 FAIL lines (two-host, shared Secret, no noindex, SSE snippet lacking the six base headers)"
        status: pass
    human_judgment: false
  - id: D6
    description: "The live halves: verify-full TLS against the managed server, the invalid_client arm, the X-Robots-Tag header on the real hosts, per-host ACME issuance"
    requirement: "DPLY-01"
    verification: []
    human_judgment: true
    rationale: "No cluster or managed server exists yet; this plan is configuration plus render gates. The live proofs belong to plan 29-14 (TLS, invalid_client, readiness) and 29-15 (headers on the live edge)."

duration: 24min
completed: 2026-10-19
status: complete
---

# Phase 29 Plan 08: Application Configuration Layer Summary

**Vendor login fixes in config: k8s now migrates as the owner role and runs as the runtime role (Blocker E), over verify-full Postgres TLS. The login names the realm's real core-api client (Blocker A). Staging reaches Keycloak, Redis, RabbitMQ and Mailpit by their in-namespace names and fits 3 x B2s. Each Ingress host has its own certificate and one owner. Two new render invariants back these: INV-14 and INV-15, each shown failing on the pre-change tree.**

## Performance

- **Duration:** 24 min
- **Started:** 2026-10-18T23:36:54Z
- **Completed:** 2026-10-19T00:01:16Z
- **Tasks:** 3
- **Files modified:** 34 (6 commits), plus this SUMMARY

## Accomplishments

- **Blocker E closed in the render.**
  - core-java now gets `DB_MIGRATION_USER` and `DB_MIGRATION_PASSWORD` from `postgres-credentials` (`migration-username` and `migration-password`).
  - `check-env-contract.sh` requires the pair now. The two "reviewed omission" rows are gone, and taking the env entries out of the Deployment makes it fail.
  - `k8s-local-secrets.sh` supplies the pair from the `.env` names compose already uses.
- **Database TLS is verified, not just used (T-29-27).**
  - The datasource URL carries `sslmode`/`sslfactory` from env. The defaults are pgjdbc's own, so an environment that sets neither key behaves exactly as before.
  - Staging and production render `verify-full` with `DefaultJavaSSLFactory`, which checks against the JVM truststore.
  - `k8s/local` keeps `prefer`. Measured: the compose Postgres reports `show ssl` = `off`.
  - Flyway inherits the URL through its #517 placeholder.
- **Blocker A closed at the base.** `keycloak.client-id` is now `core-api`. The old value `frontend` named no client in any realm. INV-14 holds the value to the realm template, and requires a client a confidential browser login can use.
- **DEF-29-5 and Pattern 5.**
  - Staging now uses in-cluster addresses for:
    - the Keycloak issuer (JWKS);
    - the admin API, which is now enabled;
    - Redis, RabbitMQ and the STOMP relay;
    - Mailpit SMTP.
  - The issuer stamped into tokens stays public.
  - The SMTP comment records the 29-16/29-22 ordering, and why sends failing on DNS before 29-22 do no harm.
- **Pitfall 1.** Staging HPA floors are 1, and the PDBs use `minAvailable: 0` so a node drain is never blocked. `maxReplicas` is unchanged, because the connection-math gate depends on it.
- **Pitfall 4 and its hidden twin.**
  - There is now one tls entry and one Secret per host.
  - The SSE Ingress no longer requests a certificate into the main Ingress's Secret. It references that Secret instead.
- **D-08 applied to crawlers.** Every staging Ingress serves the six base security headers plus `X-Robots-Tag: noindex, nofollow`. INV-15 also refuses noindex in production.

## Task Commits

1. **Task 1: Migrator/runtime split and verified DB TLS (TDD)**
   - RED `63f38822` (test): DatasourceTlsPropertyTest plus 2 RED evidence records.
   - GREEN `12c57184` (feat): application.yml, the Deployment env, app-config, env-contract, the local secrets script, goldens, metrics, citations.
2. **Task 2: Real client id, internal issuer, in-namespace hosts, scale floors, INV-14**: `a64e2e1e` (feat).
3. **Task 3: Per-host TLS, one owner per Secret, noindex, INV-15 (TDD)**
   - `3799c286` (feat): manifests and goldens.
   - RED `8d8d8da0` (test): `--selftest-inv15` against a stub INV-15, plus 4 RED records.
   - GREEN `c6eb927d` (feat): INV-15 itself and the CI step.

**Plan metadata:** the docs commit that carries this SUMMARY.

## Evidence (both directions recorded)

### Task 1

**RED** (`63f38822`, `./gradlew :core-java:test --tests '*DatasourceTlsPropertyTest' --rerun`): rc 1, `3 tests completed, 2 failed`. The report is in `core-java/build-local/test-results/test/`, written by this run.
- `defaultUrlKeepsPreferAndLibPq`: `Expecting actual: "jdbc:postgresql://localhost:5432/jtoye" to end with: "?sslmode=prefer&sslfactory=org.postgresql.ssl.LibPQFactory"` (line 53).
- `deployedTargetsVerifyTheServer`: `[verify-full: ...] expected: "verify-full" but was: null` (line 81). pgjdbc parsed the URL, but found no TLS parameter in it.
- `flywayUserFollowsTheMigratorSplit` passed. That was expected: the fallback already existed and is preserved.
- Classifier: `RED_EVIDENCE_OK target_test_failed` for both targets (JUnit XML, 3 tests, 1 pass, 2 fail). Records: `evidence/29-08-t1-red-{verify-full,default-prefer}.json`.
- **Semantic assessment:** both target tests executed in this run and failed on the planned assertion, not on setup.

**GREEN** (verify 1, run as written): `gradle rc=0`, `report: tests="3" failures="0" errors="0" skipped="0"`, rc 0.
- **VOID direction:** the same command without `--rerun` gives `Task :core-java:test UP-TO-DATE` and `VOID: no JUnit report written by THIS run`, rc 2.
- **Wider runs:**
  - The full unit suite (`:core-java:test --rerun`): 221 fresh reports, tests=1921, failures=0, errors=0 (1 skipped, pre-existing).
  - `integrationTest` for FreshChainMigrationIntegrationTest and RuntimeRoleGrantContractTest: 5 and 5 tests, 0 failures. Both read application.yml's Flyway URL or connect with the new defaults.

**Verify 2:** env-contract, doc-metrics and docs-freshness give rc 0.
- Before the prose update, check-doc-metrics printed 10 FAIL lines (README, CLAUDE.md and AGENTS.md: 6328, 2915 and 413 against 6331, 2918 and 414).
- Before `--write`, docs-freshness exited 1 (2915 committed, 2918 computed).

**AC break arm** (committed first at `12c57184`; clean, arm, clean):

| Step | sha (prefix) | env-contract |
|------|--------------|--------------|
| CLEAN-BEFORE | `3ca214f97d1c56be` (= committed) | rc 0 |
| ARM: both DB_MIGRATION_* env entries removed | `5fd2486460c142a5` | rc 1: `DIRECTION (b) VIOLATION [core-java] ... DB_MIGRATION_PASSWORD (default: '${spring.datasource.password}')`, `DB_MIGRATION_USER (default: '${spring.datasource.username}')` |
| CLEAN-AFTER | `3ca214f97d1c56be` | rc 0 |

**AC staging migrator key:**
- After: `migration-username refs: 1`, rc 0.
- Pre-change tree: `migration-username refs: 0`, rc 1.

Read back across the three overlays:

| Target | `db.ssl-mode` | `db.ssl-factory` |
|--------|---------------|------------------|
| staging | `verify-full` | `DefaultJavaSSLFactory` |
| production | `verify-full` | `DefaultJavaSSLFactory` |
| local | `prefer` | `LibPQFactory` |

### Task 2

**Verify 1** (`check-render-invariants.sh`): rc 0.
- `k8s/staging` and `k8s/production` each print `INV-14 OK (keycloak.client-id 'core-api' is a login client of the realm template)`.
- base and local print `SKIP`.

**INV-14 fail direction:** the new gate run over the pre-change tree `12c57184` gives rc 1, with both `FAIL [k8s/production]` and `FAIL [k8s/staging] INV-14: app-config keycloak.client-id 'frontend' is not a clientId in the realm template`. More arms, each in a temp copy:

| Arm | rc | Result |
|-----|----|--------|
| `does-not-exist` | 1 | not a clientId |
| `admin-cli` | 1 | in the template, but not usable (built-in) |
| `integration-catalog-ro` | 1 | in the template, but not usable (no standard flow) |
| template missing | 2 | PARSE ERROR |
| template corrupt | 2 | PARSE ERROR |
| template with no clients | 2 | PARSE ERROR |
| no jq on PATH | 2 | PARSE ERROR |

**Verify 2** (HPA histogram):
- After: `count minReplicas: [3 1]`, rc 0.
- Pre-change: `[2 3` / `1 5]`, rc 1. This matches the plan's recorded control exactly.

**AC hosts** (`jtoye-infrastructure` count in the staging render):
- After: `jtoye-infrastructure refs: 0`, rc 0.
- Pre-change: `jtoye-infrastructure refs: 4`, rc 1. The four were `keycloak.admin.base-url`, `rabbitmq.host`, `redis.host` and `stomp.broker.relay-host`, exactly as the plan predicted after 29-02.

**AC issuers** (read from the rendered app-config):
- `keycloak.issuer.uri = http://keycloak.jtoye-staging.svc.cluster.local:8080/realms/jtoye-staging`
- `keycloak.public.issuer.uri = https://auth-staging.olajay.co.uk/realms/jtoye-staging`
- Also: `keycloak.admin.enabled "true"`, `smtp.host mailpit.jtoye-staging.svc.cluster.local`, port `"1025"`, auth `"false"`, starttls `"true"`, `security.trusted-proxies ""`.
- The PDBs read `0` three times, Deployment replicas `1` three times, and maxReplicas 10, 10 and 20 (unchanged).

`--selftest-inv7` still gives 5 of 5 `caught=yes` and `CLEAN rc=0`, now that the copy stages the realm template.

### Task 3

**RED** (`8d8d8da0`, stub INV-15): `--selftest-inv15` rc 1.
- All 8 arms are `caught=no`, each with `got rc=0`. Every plant applied; none reports "plant did not apply".
- `CLEAN rc=0`.
- Classifier (TAP, 9 tests): `RED_EVIDENCE_OK target_test_failed` for two-host, shared-secret, missing-noindex and unsupported-annotation. Records: `evidence/29-08-t3-red-*.json`.
- **Semantic assessment:** each planted tree was judged by the copy's own gate, which exited 0 only because INV-15 was a stub. The clean arm in the same run is ok.

**GREEN** (`c6eb927d`): all 8 arms `caught=yes` (two-host, shared-secret, missing-noindex, unsupported-annotation, snippet-drift, production-noindex, supported-list-empty, supported-list-missing), `CLEAN rc=0`, rc 0.

**Verify 2** (the quick static set: check-render-invariants, render-golden, check-env-contract, check-no-plaintext-secrets, check-connection-math): rc 0, with 0 FAIL or PARSE ERROR lines.
- INV-15 on staging: `OK (2 Ingresses, 3 tls entries x1 host, 1 managed with unique Secrets, noindex on all, 32 nginx annotations all Traefik-documented)`.
- INV-15 on production: the same, with `no noindex` and 31 annotations.

**INV-15 against the real pre-change manifests:** the `a64e2e1e` tree with the final gate gives rc 1 and 12 INV-15 FAIL lines:
- staging and production: `jtoye-ingress tls[0] carries 2 hosts`;
- staging and production: `Secret 'jtoye-staging-tls'` / `'jtoye-tls' is requested by 2 tls entries`;
- staging: both Ingresses serve no noindex;
- staging: the SSE snippet lacks all six base header lines.

**AC managed Secrets** (staging):
- After: `jtoye-ingress[0] hosts=1 secret=jtoye-staging-api-tls`, `jtoye-ingress[1] hosts=1 secret=jtoye-staging-app-tls`, 2 single-host managed Secrets.
- Pre-change: jtoye-ingress and jtoye-sse-ingress were both managed; `jtoye-staging-tls` had 2 hosts and was requested twice.

**AC local:** both Ingresses have 0 tls entries and there are 0 raw `tls:` keys. The control is the base render, which has 3 tls entries.

**Other gates:**

| Gate | Result |
|------|--------|
| `check-gate-enforcement.sh` | rc 0 |
| `ci-cd.yaml` (PyYAML) | parses; `k8s-validate` has 14 steps |
| `check-doc-citations.sh` (jtoye-ops env) | PASS, 46 verified |

**Goldens:** regenerated with `render-golden.sh --write` in `12c57184`, `a64e2e1e` and `3799c286`, the commits that change the render. The gate gives rc 0 after each.

## Files Created/Modified

- `core-java/src/main/resources/application.yml`: the datasource URL with the TLS parameters.
- `core-java/src/test/java/uk/jtoye/core/config/DatasourceTlsPropertyTest.java`: new; 3 tests, asserting through pgjdbc `Driver.parseURL`.
- `k8s/base/core-java-deployment.yaml`: the four new env entries.
- `k8s/base/configmap.yaml`:
  - adds `db.ssl-mode` and `db.ssl-factory`;
  - `keycloak.client-id` is now `core-api`, with the Blocker A comment.
- `k8s/base/frontend-deployment.yaml`: a comment only.
- `k8s/base/ingress.yaml` and `sse-ingress.yaml`: per-host TLS; the SSE Ingress has no issuer.
- `k8s/local/configmap-patch.yaml`: the SSL overrides and a client-id comment.
- `k8s/local/ingress-patch.yaml`: a comment only.
- `k8s/staging/configmap-patch.yaml`: issuer, admin seam, hosts, SMTP, and corrected comments.
- `k8s/staging/scale-patch.yaml`: new.
- `k8s/staging/kustomization.yaml`: registers the patch; replicas set to 1.
- `k8s/staging/ingress-hosts-patch.yaml` and `sse-ingress-hosts-patch.yaml`: TLS Secrets and the noindex snippet.
- `k8s/scripts/check-env-contract.sh`: two allowlist rows removed.
- `k8s/scripts/check-render-invariants.sh`:
  - INV-14 and INV-15, with their header docs;
  - INGRESS_AWK;
  - `selftest_stage`;
  - `--selftest-inv15`.
- `.github/workflows/ci-cd.yaml`: the `--selftest-inv15` step.
- `scripts/k8s-local-secrets.sh`: the migrator keys and the preflight.
- `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md`, `README.md`: 6331 / 2918 / 414.
- `k8s/LOCAL.md` and `.planning/codebase/INTEGRATIONS.md`: five citations re-pointed after line shifts, plus one Secret name.
- `k8s/DEPLOYMENT.md` and `k8s/QUICK_START.md`: the certificate describe command names the two new Secrets.
- `.planning/WINDOWS.md`: one deviation entry (the replicas choice).

## Decisions Made

See `key-decisions` in the frontmatter.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The staging `replicas:` list was set to 1, not deleted**
- **Found during:** Task 2.
- **Issue:** Deleting the block renders base `spec.replicas` 3/5/3. Every `kubectl apply` resets each Deployment to its manifest count, so each deploy would ask 3 x B2s for 11 app pods until the HPA scales down after its stabilisation window. That is the capacity failure Pitfall 1 exists to prevent.
- **Fix:** Count 1, equal to the new HPA floor. This matches the two-mechanism shape `k8s/local` uses. The misleading part of the old list was the 2 that the HPA floor overrode; that is gone.
- **Also recorded:** removing `spec.replicas` from the base Deployments would also change production, so it is the owner's decision. Recorded in `.planning/WINDOWS.md` as kind `deviation`.
- **Commit:** `a64e2e1e`.

**2. [Rule 2 - Missing critical] INV-14 requires a login-capable client, not just an existing one**
- **Issue:** "Equals a clientId in the template" would accept `admin-cli`, `realm-management` or the service-account-only `integration-*` clients. A vendor login cannot complete with any of them.
- **Fix:** INV-14 also excludes Keycloak built-ins, and requires the client to be enabled, `standardFlowEnabled`, not `bearerOnly` and not `publicClient`. Arms for each are recorded above.
- **Commit:** `a64e2e1e`.

**3. [Rule 2 - Missing critical] INV-15(c) forbids noindex in production; two extra selftest arms**
- **Issue:** The plan only said "not required" in production. A noindex that drifts into base would de-list the live storefront, which is the "no stray noindex on public pages" SEO contract in CLAUDE.md.
- **Fix:** Every deployed target outside `NOINDEX_TARGETS` must serve no noindex. Two arms were added beyond the plan's four:
  - `production-noindex` and `snippet-drift`, which make the plan's drift guard falsifiable;
  - `supported-list-empty` and `supported-list-missing`, which test the rc-2 PARSE ERROR the plan specifies.
- **Commit:** `8d8d8da0`, `c6eb927d`.

**4. [Rule 3 - Blocking] The selftest copies stage the realm template**
- **Issue:** INV-14 reads `infra/keycloak/realm-export.template.json` from REPO_ROOT. The `--selftest-inv7` copies held only `k8s/`, so every arm would have VOIDed (rc 2).
- **Fix:** A new `selftest_stage` helper is used by both selftests.
- **Commit:** `a64e2e1e`.

**5. [Rule 2 - Missing critical] `--selftest-inv15` is wired into CI**
- **Fix:** A `k8s-validate` step beside `--selftest-inv7`, following the 29-02 precedent: a falsifiability arm that runs nowhere proves nothing after the commit that adds it. `check-gate-enforcement.sh` gives rc 0.
- **Commit:** `c6eb927d`.

**6. [Rule 3 - Blocking] `check-doc-citations.sh` re-pointed after the line shifts**
- **Issue:** The application.yml, configmap and Deployment edits shifted five lines cited in `k8s/LOCAL.md` and `.planning/codebase/INTEGRATIONS.md`. The gate gave rc 1 with 5 C-3 violations.
- **Fix:** Re-pointed: 965-969 to 974-978, 199 to 213, 639 to 648 (twice), 324-328 to 356-360. The gate now gives rc 0. It is CI-wired (ops-contracts).
- **Commit:** `12c57184`.

**7. [Doc accuracy] Comments and runbook commands that became false**
- Changed: the `frontend-deployment.yaml` and local client-id comments ("only k8s/local patches it"); the staging "SMTP deliberately NOT overridden" and "client-id DELIBERATELY NOT OVERRIDDEN" comments; and `kubectl describe certificate jtoye-tls` in DEPLOYMENT.md and QUICK_START.md. The Secret no longer exists.
- The staging issuer comment states the Cilium hairpin claim as RESEARCH A5 (assumed, not measured).
- **Commit:** `a64e2e1e`, `3799c286`.

**8. [Structure, recorded] Task 3 is three commits, with the manifests first**
- The RED selftest's plants assume the new manifest shape: they remove a noindex line, and add a host to the replaced tls list. So the manifest change landed first as `feat` (config, with every gate green), then `test` RED, then `feat` GREEN.

---

**Total deviations:** 8.
- 1 bug.
- 3 missing-critical strengthenings.
- 2 blocking.
- 1 doc-accuracy batch.
- 1 recorded structure choice.

**Impact on plan:** Every must-have truth holds. The one plan instruction not followed literally is the replicas deletion (deviation 1), and it is recorded in WINDOWS.md for the owner.

## TDD Gate Compliance

- **Task 1:** RED `63f38822` (`test(29-08)`) precedes GREEN `12c57184` (`feat(29-08)`). RED_EVIDENCE_OK was given for both target tests.
- **Task 3:** RED `8d8d8da0` precedes GREEN `c6eb927d`. RED_EVIDENCE_OK was given for the four planned arms.
- **Refactor:** none was needed.
- **Task 2:** `type="auto"`, with no tdd flag.

## Issues Encountered

- A `sed` delimiter collision briefly replaced one comment line in `k8s/staging/configmap-patch.yaml` with `X`. It was caught by reading the file back before commit, and the paragraph was rewritten.
- The full `integrationTest` suite (607 Testcontainers tests) was not run. Only the two classes that resolve application.yml's Flyway URL or open real connections were. Every other integration test overrides `spring.datasource.url` through `DynamicPropertySource`.

## Known Stubs

None. The RED-phase stub INV-15 was replaced in `c6eb927d`.

## Threat Flags

None beyond the register:

| Threat | Mitigated by |
|--------|--------------|
| T-29-26 | the env-contract requirement and the break arm |
| T-29-27 | verify-full and the unit test |
| T-29-28 | INV-14 |
| T-29-29 | INV-15(c) |
| T-29-30 | INV-15(a)/(b) |

Enabling the Keycloak admin seam in staging is planned behaviour. Its credential arrives from Key Vault in plan 29-12, and its egress is the same-namespace Keycloak podSelector that 29-02 already allows.

## User Setup Required

None. Nothing was applied to any cluster, and no Azure call was made.

## Next Phase Readiness

| Plan | What it needs from 29-08 |
|------|--------------------------|
| 29-12 | Key Vault must supply `postgres-credentials` `migration-username`/`migration-password` (the owner `jtoye_app`), and `keycloak-credentials` admin keys (the admin seam is now on in staging) |
| 29-10 | Keycloak's Service must be named `keycloak` in `jtoye-staging` on 8080, with `KC_HOSTNAME` public. The auth-staging Ingress it adds must pass INV-15 (one host, its own Secret, noindex plus the base headers, documented annotations) |
| 29-09 / 29-22 | Redis and RabbitMQ Services must be named `redis` and `rabbitmq`, and Mailpit `mailpit` on 1025 |
| 29-14 | The live proofs: verify-full to the managed server, Flyway as `jtoye_app` with the runtime as `jtoye_runtime` (DatabaseConfigurationValidator), the invalid_client arm, and the readiness claim for a dead mail host |
| 29-15 | `curl -sI` on each staging host shows `x-robots-tag: noindex, nofollow` |

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-10-19*

## Self-Check: PASSED

- FOUND: DatasourceTlsPropertyTest.java, k8s/staging/scale-patch.yaml, and all 6 evidence/29-08-t*-red-*.json records.
- FOUND (ancestors of HEAD): 63f38822, 12c57184, a64e2e1e, 3799c286, 8d8d8da0, c6eb927d.
- Must-have artifact contents:
  - `minReplicas: 1` x3 in scale-patch.yaml;
  - `verify-full` x5 in the test;
  - `INV-15` x30 and `supported-annotations.txt` x6 in check-render-invariants.sh;
  - `migration-username` x1 in core-java-deployment.yaml.
- Commits were measured with `git rev-list --count e3621e3b..HEAD` = 6 at SUMMARY write. `plan_head_after` was read from git.
