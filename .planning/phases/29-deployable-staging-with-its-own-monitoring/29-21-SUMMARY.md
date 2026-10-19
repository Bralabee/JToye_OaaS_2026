---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 21
subsystem: infra
tags: [ci, deploy, oidc, kubelogin, key-vault, cr-02, smoke-test, github-environment, dply-02, "#99"]
status: halted

requires:
  - phase: 29-14
    provides: "the deploy-staging digest-parity step (fails closed until this plan gives the job kubelogin and a staging kubeconfig)"
  - phase: 29-13
    provides: "scripts/staging-secrets-preflight.sh and the populated jtoye-stg-kv"
  - phase: 29-09
    provides: "jtoye-ci with federated credential gh-deploy-staging (subject environment:staging), k8s/staging/azure-identity.yaml"
  - phase: 29-01
    provides: "scripts/lib/azure-guard.sh and scripts/azure-staging-provision.sh"
provides:
  - "deploy-staging authenticates only by OIDC (azure/login v3.1.0, jtoye-ci) and kubelogin v0.2.21; the KUBE_CONFIG_STAGING step is gone"
  - "kubectl v1.36.4 in deploy-staging (the cluster's own version, read with az_read)"
  - "scripts/check-frontend-baked-origin.sh (--overlay, --selftest): the CR-02 guard, extracted and wired"
  - "scripts/staging-secrets-preflight.sh --names-only: no value read, only reads Key Vault Reader permits; full mode reports STALE-IDENTITY"
  - "principal_id_* for the ten workload identities in k8s/staging/azure-identity.yaml"
  - "azure-staging-provision.sh ci stage: Key Vault Reader for jtoye-ci at vault scope, probe-before-create, exact role-set read-back"
  - "Smoke test asserts the API docs are NOT served on staging (Blocker G, #549)"
affects: [29-24, 29-26, 29-27]

actuals:
  tokens: 20100
  tasks: 2
  commits: 8
plan_head_before: 36f199a720cc3937f610690e52354906bf0a1b57
plan_head_after: 7765770fb54e8a4f27818c607c8124eed13fdec0

tech-stack:
  added: ["azure/login v3.1.0 (a641126d)", "kubelogin v0.2.21 (sha256-pinned release)"]
  patterns:
    - "A CI preflight is written against the CI identity's role definition, read with az_read: every read it makes must be in that role's actions or dataActions at the scope it holds"
    - "Identifiers the CI identity may not list (principal ids) are committed, and every operator run re-checks them against live (STALE-IDENTITY)"
    - "A forbidden-read detector gets a control arm that proves it fires on the mode that does make those reads"

key-files:
  created:
    - scripts/check-frontend-baked-origin.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-21-ci.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-21-t1-red-production-origin.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-21-t1-red-names-only.json
  modified:
    - .github/workflows/ci-cd.yaml
    - scripts/staging-secrets-preflight.sh
    - scripts/lib/staging-secrets-common.sh
    - scripts/azure-staging-provision.sh
    - k8s/staging/azure-identity.yaml
    - .gitleaks.toml

key-decisions:
  - "--names-only resolves principal ids from the committed identity file and lists grants per secret with --scope, because Key Vault Reader (measured) has no vaults/read, no ManagedIdentity read and no subscription-scope Authorization read"
  - "The vault id is built from the asserted subscription in --names-only, never read"
  - "An empty baked origin is rc 1 in the extracted guard (the inline step said 2); both fail the job"
  - "kubectl is pinned to the cluster's patch, v1.36.4, not the newest 1.36 patch"
  - "The CR-02 selftest runs as a deploy-staging step, not in k8s-validate, to keep this plan's ci-cd.yaml edits inside deploy-staging (29-24 also edits the file)"
  - "The ci stage probes each role assignment before creating it, so a re-run never depends on how the CLI answers a duplicate create"

patterns-established:
  - "OIDC deploy job shape: subscription variable refused unless byte-equal, azure/login pinned by sha, get-credentials, kubelogin convert, then can-i before any apply"

requirements-completed: []

coverage:
  - id: D1
    description: "deploy-staging authenticates without a stored kubeconfig, pins kubectl to the cluster, runs the names-only preflight, smoke-tests EXPECT_SWAGGER=false"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "Task 1 verify 2: in-staging=0 control-in-production=1 rc 0 (before 3/1 rc 1; no control rc 2); actionlint rc 0 (planted error rc 1)"
        status: pass
      - kind: other
        ref: "live smoke against api-staging: EXPECT_SWAGGER=false rc 0, EXPECT_SWAGGER=true rc 1 (401 on both doc paths)"
        status: pass
      - kind: other
        ref: "the job itself has never run: the first run is plan 29-27, on main"
        status: unknown
    human_judgment: true
    rationale: "Whether azure/login, kubelogin and the names-only preflight work AS jtoye-ci can only be proven by the first CI run (29-27); everything here was proven as the operator or offline"
  - id: D2
    description: "CR-02 guard extracted into scripts/check-frontend-baked-origin.sh and wired"
    requirement: DPLY-02
    verification:
      - kind: unit
        ref: "bash scripts/check-frontend-baked-origin.sh --selftest -> 5/5; RED 1/5 against the always-pass stub (RED_EVIDENCE_OK); unwired -> check-gate-enforcement rc 1"
        status: pass
    human_judgment: false
  - id: D3
    description: "staging-secrets-preflight --names-only reads no value and still reports gaps"
    requirement: DPLY-02
    verification:
      - kind: unit
        ref: "--selftest 16/16; RED 10/15 (RED_EVIDENCE_OK); break arm (fall through to value reads) -> 15/16"
        status: pass
      - kind: other
        ref: "live as operator: full rc 0 (0 STALE), names-only rc 0; wrong committed principal -> 21 MISSING-ACCESS / STALE-IDENTITY, rc 1"
        status: pass
    human_judgment: false
  - id: D4
    description: "GitHub environment staging (main only) with the three Azure variables; jtoye-ci Key Vault Reader at vault scope"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "Task 2 verify 1: env 404 today (RED direction); owner checkpoint pending"
        status: fail
    human_judgment: true
    rationale: "GitHub settings and the Azure role assignment are outward-facing writes; this lane may not make them. Exact commands are in Checkpoint below"

duration: 25min
completed: 2026-10-19
---

# Phase 29 Plan 21: The CI Deploy Gate, Part 1 (OIDC, kubelogin, Names-Only Preflight, True Smoke Test)

**deploy-staging now authenticates through a federated credential and kubelogin, with no stored kubeconfig. It speaks the cluster's own kubectl (1.36.4). Before applying, it refuses a half-configured vault without reading a value, and its smoke test asserts what staging really serves: no API docs. The CR-02 guard is a script whose selftest proves it refuses a production-baked image. Measured live, the old Swagger-expecting smoke line fails a healthy staging (401), so the old job would have rolled it back. The GitHub environment, its variables and jtoye-ci's Key Vault Reader grant are outward-facing writes, left for the owner as exact commands.**

## Performance

- **Duration:** about 25 min (10:50Z to 11:15Z)
- **Tasks:** Task 1 complete. Task 2: the script change and every read-only step are done; its writes are at the owner checkpoint.
- **Commits:** 8, counted from the plan ledger (36f199a7..7765770f), plus this SUMMARY's.

## Accomplishments

**Task 1**
- **CR-02 guard** (`scripts/check-frontend-baked-origin.sh`):
  - RED (a5e10b92): the body was a stub that always passed. The selftest caught the production origin against k8s/staging (1/5).
  - GREEN (572bf11e): 5/5. The production origin gives rc 1 and names both origins. The staging origin gives 0, an empty origin 1. A render with no api.url gives 2, and so does a missing kubectl.
  - The deploy-staging step runs `--selftest` and then the guard. Removing the reference makes check-gate-enforcement fail, naming the script.
- **`staging-secrets-preflight.sh --names-only`** (RED 0212e39c 10/15, GREEN 28007d65 16/16):
  - No `keyvault secret show`, `keyvault show`, `identity list` or `--all`. A control arm proves the detector fires on the full mode.
  - Still reports MISSING, MISSING-ACCESS and an unmapped identity.
  - Full mode now also reports STALE-IDENTITY.
  - Live as the operator: full mode rc 0 with 0 gaps, and names-only rc 0. With a wrong committed principal, names-only gave 21 MISSING-ACCESS and full mode gave STALE-IDENTITY, both rc 1.
- **deploy-staging rewrite** (41f00b6d):
  - `permissions: id-token: write, contents: read`.
  - The subscription variable must equal c483d353 byte-for-byte.
  - `azure/login@a641126d… # v3.1.0`.
  - kubelogin v0.2.21, sha256-verified, then `az aks get-credentials` and `kubelogin convert-kubeconfig -l azurecli`.
  - `kubectl auth can-i create deployments -n jtoye-staging` must say yes before apply.
  - The names-only preflight runs with `JTOYE_AZURE_SUBSCRIPTION_ID` from the environment.
  - kubectl v1.36.4 (read live: kubernetesVersion 1.36.4).
  - `EXPECT_SWAGGER=false`, with the prod-profile/#549 comment.
  - Unchanged: CR-02, the logout pre-flight, the full-sha pin, the render assertion, rollback and digest parity.

**Task 2** (252780a6)
- **Provision ci stage:** grants Key Vault Reader at vault scope.
  - Each grant is probed and created only when absent.
  - The live read-back refuses unless the role set is exactly Cluster User (cluster), RBAC Writer (namespace) and Key Vault Reader (vault), with no Key Vault role that reads or writes a value.
  - Dry-run: 8 stub calls, 3 creates.
  - Offline read-back arms: exact set rc 0; today's set without the grant rc 1; plus Secrets User rc 1; all absent creates 3.
- **FRONTEND_PUBLIC_* readers** (verify 3): exactly ci-cd.yaml, in jobs build-and-push, deploy-production and deploy-staging. A planted reader in frontend-e2e gives exit 1. The effect on each reader and the reason for repository scope are recorded in the evidence.
- **Live CR-02 check:** with the live variable value it gives rc 0; with the production origin, rc 1.
- **Read-only state today:**
  - The `staging` environment does not exist (404).
  - jtoye-ci holds Cluster User and RBAC Writer only.
  - The federated subject is `repo:Bralabee/JToye_OaaS_2026:environment:staging`.
  - DEPLOY_STAGING_ENABLED is unset.

## Checkpoint (owner / orchestrator; nothing below was run by this executor)

Run these from a checkout that contains this plan's commits, in this order. All values below are identifiers, not secrets.

1. **Key Vault Reader for jtoye-ci (Azure mutation, subscription c483d353):**
   ```bash
   export JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94
   bash scripts/azure-staging-provision.sh --stage ci
   ```
   - **Changes:** creates ONE role assignment: "Key Vault Reader" for principal 4cce597e-719e-4181-b045-8ba024f5bc67 (jtoye-ci), at scope `/subscriptions/c483d353-5f61-4587-a790-addb9ab5fb94/resourceGroups/jtoye-rg/providers/Microsoft.KeyVault/vaults/jtoye-stg-kv`.
   - Cluster User and RBAC Writer already exist; they are read back, not recreated.
   - **Refuses (rc 1)** unless the final role set is exactly those three, with no Secrets User.
   - Append its output to evidence/29-21-ci.txt.
2. **Create the GitHub environment `staging`, custom branch policies on:**
   ```bash
   gh api -X PUT repos/Bralabee/JToye_OaaS_2026/environments/staging --input - <<'EOF'
   {"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}
   EOF
   ```
   - **Changes:** a new environment `staging`, with no reviewers and no wait timer.
   - Its name is half of the federated subject, so it must be exactly `staging`.
3. **Limit it to main:**
   ```bash
   gh api -X POST repos/Bralabee/JToye_OaaS_2026/environments/staging/deployment-branch-policies -f name=main -f type=branch
   ```
   - **Changes:** only refs/heads/main may run a job in `staging`. Only such a job can obtain the OIDC token jtoye-ci trusts.
4. **The three environment variables:**
   ```bash
   gh variable set AZURE_CLIENT_ID       --env staging -R Bralabee/JToye_OaaS_2026 --body ec29905d-231c-43eb-9385-c6f7f72409db
   gh variable set AZURE_TENANT_ID       --env staging -R Bralabee/JToye_OaaS_2026 --body b56df236-36b2-49ab-a25f-050cbaa9787c
   gh variable set AZURE_SUBSCRIPTION_ID --env staging -R Bralabee/JToye_OaaS_2026 --body c483d353-5f61-4587-a790-addb9ab5fb94
   ```
   - **Changes:** three variables visible only to jobs that declare `environment: staging`, i.e. deploy-staging.
5. **Do NOT:**
   - set DEPLOY_STAGING_ENABLED (that is plan 29-27);
   - change any FRONTEND_PUBLIC_* variable. FRONTEND_PUBLIC_API_URL and _CUSTOMER_KEYCLOAK_URL were already set to the staging origins at 08:05:27Z.
6. **Then re-run the plan's Task 2 verifies:**
   - Verify 1 must print `staging true false` and `main`. It returned 404 today, the RED direction.
   - Verify 2 must print the one FRONTEND_PUBLIC_API_URL line.
   - Run `gh variable list --env staging -R Bralabee/JToye_OaaS_2026`.
   - Append all of it to the evidence and re-summarize this plan as `status: complete`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The preflight's reads were not ones jtoye-ci could make**
- **Found during:** Task 1.
- **Measured:** the Key Vault Reader definition has neither `Microsoft.KeyVault/vaults/read` (the `keyvault show` call) nor any ManagedIdentity read (`identity list`). Its `Microsoft.Authorization/*/read` holds only under the vault, so the subscription-wide `role assignment list --all` is out of reach too.
- **Consequence:** the plan's "presence + enabled + per-identity assignments" check, run as written, would have VOIDed or refused every CI deploy.
- **Fix:** `--names-only` builds the vault id and lists grants per secret with `--scope`. It resolves principal ids from new committed `principal_id_*` keys in `k8s/staging/azure-identity.yaml`, which is local-config, so the render and goldens do not change.
- **Keeping the map honest:** the full mode now re-checks those ids against the live identities (STALE-IDENTITY).
- **Stub change:** the stub lib gained listing by `--scope`.
- **Files outside the plan's list:** k8s/staging/azure-identity.yaml and scripts/lib/staging-secrets-common.sh.
- **Commits:** 0212e39c and 28007d65.

**2. [Rule 3 - Blocking] gitleaks flagged two of the new identifier lines**
- **What:** generic-api-key matched on the keycloak and secret_sync keys.
- **Fix:** the existing anchored `client_id_*` allowlist rule now reads `(client|principal)_id_*`, with the same UUID-only value shape.
- **Fixture checks:** a non-UUID value under `principal_id_*` still fires, and so does a UUID under another key.
- **Commit:** 86807d2f.

**3. [Rule 2 - Missing] Re-running the ci stage, and proving its result**
- The stage created assignments unconditionally. It now probes first.
- It also reads back the exact role set and refuses any Key Vault role that reads a value (T-29-76).
- **Commit:** 252780a6.

**4. [Rule 1 - Bug, caught in GREEN] The forbidden-read detector could not match**
- The first GREEN run was 14/16: the pattern assumed a leading space that the stub's argv log does not have.
- **Fix:** the pattern is anchored at line start, and a control arm now proves the detector fires. Fixed before commit 28007d65.

### Plan adaptations
- **Empty baked origin:** rc 1, per the plan's `<behavior>`. The inline step exited 2. Both fail the job.
- **Where the CR-02 selftest runs:** as a deploy-staging step, so it runs only on main when the deploy is enabled, not on pull requests. This keeps the ci-cd.yaml edits inside deploy-staging (29-24 edits the same file). The selftest could move to k8s-validate later.
- **Order of the variable change and the reader enumeration:** the repository variables were changed (by the orchestrator, 08:05:27Z) before the enumeration ran here. The enumeration found the same three readers the plan measured on 2026-10-18, and the effects recorded are the ones that change has.
- **The other build arguments:** no FRONTEND_PUBLIC_* beyond the two origins has a staging value. Support email, support URL, review SLA and registered office are environment-independent and optional, so they were left unchanged.
- **Task 2's outward writes were not made:** this is a lane-B override. They are listed under Checkpoint.

## Known Gaps / Open Items

- **Proven only as the operator:**
  - That jtoye-ci can actually log in. azure/login lists subscriptions; jtoye-ci holds only resource-scoped roles, and the subscription is expected to be listed.
  - That jtoye-ci can make the names-only reads.
  - That kubelogin can fetch the AKS token within the federated assertion's lifetime. The job fetches it immediately after login, through `can-i`.
  - Plan 29-27's first run is the proof.
- **shellcheck is not installed on this host.** The new scripts and actionlint's shell checks were not shellchecked. Not run, not clean.
- **`actions/checkout@v4` in deploy-staging stays tag-pinned.** This predates the plan and was not changed.
- **deploy-production keeps kubectl v1.28.0 and its inline CR-02.** It now refuses a staging-baked image, which is correct while it is off (Phase 32).

## Threat Flags

None beyond the plan's register:
- **T-29-75:** the stored kubeconfig is removed, and the environment is limited to main once the checkpoint runs.
- **T-29-76:** Key Vault Reader only, with the exact-set read-back.
- **T-29-77/78:** EXPECT_SWAGGER=false, measured live.

The committed principal ids are identifiers (T-29-32), like the client ids already in that file.

## Self-Check: PASSED

- FOUND: scripts/check-frontend-baked-origin.sh, evidence/29-21-ci.txt, evidence/29-21-t1-red-production-origin.json, evidence/29-21-t1-red-names-only.json
- FOUND (ancestors of HEAD): a5e10b92, 572bf11e, 0212e39c, 28007d65, 41f00b6d, 252780a6, 86807d2f, 7765770f
- `git rev-list --count 36f199a7..7765770f` = 8
