---
phase: 26
slug: local-k8s-overlay-verified-breakage-fixes
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-25
---

# Phase 26 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Derived from `26-RESEARCH.md` § Validation Architecture (lines 1101-1182).

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | bash gates under `k8s/scripts/` (CI job `k8s-validate`, `.github/workflows/ci-cd.yaml:191-211`) + JUnit 5/Testcontainers (`./gradlew :core-java:test`) + Playwright 1.59.1 (`frontend/playwright.config.ts`, baseURL from `PLAYWRIGHT_BASE_URL`) |
| **Config file** | none for the bash gates — paths resolve from `$BASH_SOURCE`; exit codes 0 = clean, 1 = violation, 2 = tooling failure |
| **Quick run command** | `bash k8s/scripts/check-no-plaintext-secrets.sh && bash k8s/scripts/check-connection-math.sh && bash k8s/scripts/check-env-contract.sh` (~2s, no cluster) |
| **Full suite command** | the three gates + `kubectl kustomize k8s/local` + `kubectl --context jtoye apply -k k8s/local --dry-run=server` + the live rehearsal checklist below |
| **Estimated runtime** | ~2s static; ~15-25 min live rehearsal (cluster start + image load + rollout + probes) |

**Definition of "validated" for an infra phase:** every claim is either (a) a deterministic assertion
over committed text that CI re-runs on every PR, or (b) a live observation captured as **named,
falsifiable evidence** — the command, its expected output, and the actual output recorded in the phase
evidence block. Nothing is marked complete on "it worked once".

---

## Sampling Rate

- **After every task commit:** the three bash gates (~2s, no cluster). Any `k8s/base` edit
  additionally re-runs the staging/production golden-render diff.
- **After every plan wave:** all static gates + `kubectl kustomize k8s/local` + `--dry-run=server`
  when a cluster is up + `./gradlew :core-java:test` whenever `application.yml` changed (it does, for D-05).
- **Before `/gsd:verify-work`:** full static suite green **plus** the live rehearsal evidence block —
  every "live" row below with its actual captured output.
- **Max feedback latency:** ~2s static / one rehearsal cycle for live.

---

## Per-Task Verification Map

Task IDs are assigned by the planner; rows below are the requirement-level contract each task must
inherit. `Exists?` reflects state at planning time (2026-07-25).

| Req | Behaviour | Type | Automated command | Exists? | Status |
|-----|-----------|------|-------------------|---------|--------|
| INFRA-01 | `k8s/local` builds | static | `kubectl kustomize k8s/local >/dev/null` | ✅ auto-covered (guard discovers overlays at `maxdepth 2`) | ⬜ pending |
| INFRA-01 | no `kind: Secret`, no `REPLACE_WITH` in the local build | static | `bash k8s/scripts/check-no-plaintext-secrets.sh` | ✅ exists, green | ⬜ pending |
| INFRA-01 | overlay shims all four endpoints to `host.minikube.internal` | static | `kubectl kustomize k8s/local \| grep -c 'host.minikube.internal'` ≥ 4 | ❌ W0 | ⬜ pending |
| INFRA-01 | `replicas: 1` ×3, `minReplicas: 1` ×3, `minAvailable: 1` ×3, `maxReplicas` unchanged | static | rendered-scale assertion vs expected fixture | ❌ W0 | ⬜ pending |
| INFRA-01 | backup CronJob targets host MinIO | static | `kubectl kustomize k8s/local \| grep 's3.backup.endpoint: http://host.minikube.internal:9000'` | ❌ W0 | ⬜ pending |
| INFRA-01 | every ref resolves, no dangling secret/configmap/label ref | **live** | pre-create ns, then `kubectl --context jtoye apply -k k8s/local --dry-run=server` | ❌ live | ⬜ pending |
| INFRA-02a | no hardcoded `5432` in the core-java env block | static | `! grep -nE '^\s+value: "5432"' k8s/base/core-java-deployment.yaml` | ❌ W0 | ⬜ pending |
| INFRA-02a | `DB_PORT` has `valueFrom` and **no** `value` (guards the both-fields trap permanently) | static | rendered EnvVar has exactly one of the two | ❌ W0 | ⬜ pending |
| INFRA-02b | docs + template specify the NOSUPERUSER role | static | `! grep -n 'from-literal=username=jtoye$' k8s/QUICK_START.md` and same for `secrets-template.yaml.example` | ❌ W0 | ⬜ pending |
| INFRA-02b | core boots as a non-superuser | **live** | `kubectl logs deploy/core-java \| grep -c "is NOT a superuser"` ≥ 1 **and** the DB-side truth `SELECT current_user, usesuper` under the pod's connection identity | ❌ live | ⬜ pending |
| INFRA-02c | CronJob run exits 0 and uploads | **live** | `kubectl create job --from=cronjob/pg-backup …`; `.status.succeeded == 1` | ❌ live | ⬜ pending |
| INFRA-02c | the dump is **NON-EMPTY** (not merely >1000 bytes) | **live, falsifiable** | download object, `pg_restore` into a scratch DB, `SELECT count(*) FROM products` > 0 (`docs/runbooks/backups.md:245-249`) | ❌ live | ⬜ pending |
| INFRA-02d | STOMP creds reach Spring config | static | `check-env-contract.sh` direction (a): 0 injected-but-unread beyond the reasoned allowlist | ❌ W0 | ⬜ pending |
| INFRA-02d | no boot-time guest rejection | **live** | `kubectl logs deploy/core-java \| grep -c "Access refused for user"` == 0 | ❌ live | ⬜ pending |
| INFRA-02d | a KDS client actually receives a relayed event | **live** | `RELAY_E2E=true PLAYWRIGHT_BASE_URL=http://app.jtoye.local npx playwright test e2e/stomp-relay.spec.ts` | ⚠ spec exists, needs cookie-domain parameterisation | ⬜ pending |
| DEF-5 | a real vendor login through the ingress reaches a dashboard | **live** | `PLAYWRIGHT_BASE_URL=http://app.jtoye.local npx playwright test e2e/dashboard-mobile.spec.ts` (`.env` `KC_SEED_USER_PASSWORD`, user `admin-user`) | ⚠ spec exists (13/13 in Phase 23), needs a locally-built frontend image | ⬜ pending |
| DEF-6 | the silent-localhost-default class cannot recur | static | `check-env-contract.sh` direction (b): every unsupplied name is manifest-supplied or allowlisted **with a reason string** | ❌ W0 | ⬜ pending |
| Regression | connection math still holds | static | `bash k8s/scripts/check-connection-math.sh` | ✅ exists, green (133 ≤ 157) | ⬜ pending |
| Regression | staging + production renders unchanged | static | golden-render diff of `kubectl kustomize k8s/staging` / `k8s/production` before vs after base edits | ❌ W0 — **the Incremental Betterment proof** | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `k8s/scripts/check-env-contract.sh` — two-direction env contract + the localhost-default rule
      with a reasoned allowlist (covers INFRA-02a/02d + DEF-6 recurrence; decisions D-07/D-08)
- [ ] Rendered-manifest assertions for `k8s/local` — endpoint-shim count, the scale triple,
      backup endpoint, and `DB_PORT` exactly-one-of `value`/`valueFrom` (same script or a sibling)
- [ ] Staging/production golden-render diff harness (committed golden files, or a CI step diffing
      against the pre-change render)
- [ ] Playwright cookie-domain parameterisation so existing specs run against `app.jtoye.local`
      instead of `localhost`
- [ ] `k8s/LOCAL.md` rehearsal-evidence template — a fixed home for the live rows' captured output

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Live cluster rehearsal (rollout READY, boot-log assertions, backup restore drill, ingress auth E2E) | INFRA-01, INFRA-02, DEF-5 | No cluster in CI; `compose XOR k8s` means the rehearsal requires stopping compose app containers first. GitHub runners cannot host minikube + the host backing services. | `scripts/k8s-local-up.sh` (single idempotent entry point), then run each **live** row above and paste actual output into the `k8s/LOCAL.md` evidence block. Record the four image digests alongside results so a stale-image pass cannot masquerade as green. |
| NetworkPolicy enforcement | INFRA-01 (D-11) | minikube's default CNI does not enforce NetworkPolicies; proving enforcement needs Calico (deferred). | N/A this phase — record as explicitly **not proven** locally in `k8s/LOCAL.md`. |

---

## Anti-Anecdote Rules (from RESEARCH.md § "Making the live proofs reproducible")

1. **One idempotent entry point** — if the rehearsal cannot be re-run from a stopped cluster by a
   single command, it is not reproducible.
2. **Pin evidence to code identity** — record image digests with the results.
3. **Assert negatives with counts, not eyeballs** — a missing log line and an absent grep hit look
   identical unless you assert the count.
4. **Falsify the backup, don't confirm it** — show a `jtoye_app` dump restoring to `products=0`
   *and* the `jtoye_backup` dump restoring to `products>0`.
5. **Prove the ingress path, not localhost** — a `localhost:9090` in the evidence means compose app
   containers were up and the XOR guard was bypassed.
6. **Capture dry-run output verbatim** — a dry-run that silently skipped an admission webhook and one
   that genuinely passed share the same exit code.

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or a Wave 0 dependency
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references above
- [ ] No watch-mode flags
- [ ] Feedback latency < 5s for static gates
- [ ] Live rows each have recorded command + actual output
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
