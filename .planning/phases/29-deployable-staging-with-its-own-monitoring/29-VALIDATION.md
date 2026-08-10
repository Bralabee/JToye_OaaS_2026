---
phase: 29
slug: deployable-staging-with-its-own-monitoring
status: planned
nyquist_compliant: true
wave_0_complete: false
created: 2026-08-10
updated: 2026-08-10
---

# Phase 29 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `29-RESEARCH.md` § "Validation Architecture" — every DPLY criterion mapped to a
> command with a fail-direction arm; 9 Wave-0 gaps enumerated there and assigned to plans below.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | bash gate scripts (`scripts/check-*.sh`, `k8s/scripts/*`) + kustomize build/dry-run + JUnit 5 (Gradle) where code changes + Playwright for the browser proof |
| **Config file** | `.github/workflows/ci-cd.yaml` (k8s-validate + deploy-staging jobs), `scripts/gates/gate-enforcement.conf` |
| **Quick run command** | `kubectl kustomize k8s/staging > /dev/null && ./k8s/scripts/check-no-plaintext-secrets.sh && ./k8s/scripts/check-render-invariants.sh` |
| **Full suite command** | the k8s-validate gate set + `check-dependency-horizons.sh` + `check-no-create-extension.sh` + `check-gate-enforcement.sh` + `check-alert-corpus-parity.sh`, then the cluster-facing gates against the staging target |
| **Estimated runtime** | ~30 s static · ~60–300 s cluster-facing |

---

## Sampling Rate

- **After every task commit:** the quick command (render + secret guard + invariants)
- **After every plan wave:** the full static gate set relevant to the wave's surface
- **Before `/gsd:verify-work`:** the cluster-facing gates green against the LIVE staging target
- **Max feedback latency:** 300 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 01-T1 | 29-01 | 1 | DPLY-04 | T-29-01-01 | Estate mutation only in the owner's subscription | checkpoint | `az resource list -g jtoye-rg -o table` (re-measure, no mutation) | ✅ | ⬜ |
| 01-T2 | 29-01 | 1 | DPLY-01/03 | T-29-01-02 | No credential value enters a tracked file | checkpoint | `aws sts get-caller-identity`; `dig +short A app-staging.olajay.co.uk` | ✅ | ⬜ |
| 01-T3 | 29-01 | 1 | DPLY-04 | T-29-01-03 | Dated decision record; ADR Status → Accepted | doc | `grep -n '^\*\*Status:\*\*' docs/architecture/decisions/ADR-0002-*.md` | ✅ | ⬜ |
| 02-T1 | 29-02 | 1 | DPLY-01 | T-29-02-01/04 | Real OIDC client; scale intent reaches HPA/PDB | static | `kubectl kustomize k8s/staging \| grep -c 'keycloak.client-id: core-api'` | ✅ | ⬜ |
| 02-T2 | 29-02 | 1 | DPLY-01/02 | T-29-02-02/03 | Redis TLS switch; RFC 8058 one-click header | unit | `./gradlew test --tests '*UnsubscribeLinkRoutingTest*'` + `./k8s/scripts/check-env-contract.sh` | ✅ | ⬜ |
| 02-T3 | 29-02 | 1 | DPLY-01 | T-29-02-05 | Render diff anchored to a pre-edit snapshot | static | `./k8s/scripts/render-golden.sh` | ✅ | ⬜ |
| 03-T1 | 29-03 | 1 | DPLY-03 | T-29-03-03 | Alert sink inspectable without bypassing the route | live | `MAILHOG_URL=… PROM_URL=… bash scripts/check-alert-liveness.sh` | ✅ | ⬜ |
| 03-T2 | 29-03 | 1 | DPLY-03 | T-29-03-01/02 | L-0 reads the corpus out of any runtime, byte-exactly | live | `bash -n scripts/check-alert-liveness.sh && shellcheck -S error scripts/check-alert-liveness.sh` | ✅ | ⬜ |
| 03-T3 | 29-03 | 1 | DPLY-05 | T-29-03-02/05 | Enforcement gate exists, control arm first, 0/1/2 | static | `shellcheck -S error scripts/check-networkpolicy-enforcement.sh && ./scripts/check-gate-enforcement.sh` | ❌ **W0** | ⬜ |
| 04-T1 | 29-04 | 2 | DPLY-01/05 | T-29-04-01/05 | Per-datastore egress on its own single port | static | `kubectl kustomize k8s/staging && python3 k8s/scripts/validate-networkpolicies.py` | ✅ | ⬜ |
| 04-T2 | 29-04 | 2 | DPLY-05 | T-29-04-02/03/04 | INV-7 multiset moves with the policy; budget reads the real server | static | `./k8s/scripts/check-render-invariants.sh && ./k8s/scripts/check-connection-math.sh` | ✅ | ⬜ |
| 04-T3 | 29-04 | 2 | DPLY-05 | T-29-04-01 | Additive render diff, no removals | static | `./k8s/scripts/render-golden.sh` | ✅ | ⬜ |
| 05-T1 | 29-05 | 2 | DPLY-01 | T-29-05-01/07 | Provisioning refuses the employer subscription | static | `shellcheck -S error scripts/azure-staging-provision.sh` | ❌ **W0** | ⬜ |
| 05-T2 | 29-05 | 2 | DPLY-01 | T-29-05-04/05 | Fail loud by NAME; DB-side BYPASSRLS check | static | `shellcheck -S error scripts/staging-secrets.sh` | ❌ **W0** | ⬜ |
| 05-T3 | 29-05 | 2 | DPLY-02/03 | T-29-05-03/06 | Digest-pinned third-party installs outside k8s/ | static | `shellcheck -S error scripts/staging-bootstrap.sh && ./scripts/check-gate-enforcement.sh` | ❌ **W0** | ⬜ |
| 06-T1 | 29-06 | 3 | DPLY-03 | T-29-06-04/05 | One rule corpus, enforced by md5 parity | static | `./scripts/check-alert-corpus-parity.sh` | ❌ **W0** | ⬜ |
| 06-T2 | 29-06 | 3 | DPLY-03 | T-29-06-01/02/03 | Cluster-internal Prometheus; credential-free exporters | static | `./k8s/scripts/check-render-invariants.sh && ./k8s/scripts/check-no-plaintext-secrets.sh` | ✅ | ⬜ |
| 06-T3 | 29-06 | 3 | DPLY-03 | T-29-06-SC | Horizon `sites:` cover every reference | static | `./scripts/check-dependency-horizons.sh && ./k8s/scripts/render-golden.sh` | ✅ | ⬜ |
| 07-T1 | 29-07 | 4 | DPLY-03 | T-29-07-02/04 | SMTP credential never a literal; one route, two sinks | static | `./k8s/scripts/check-no-plaintext-secrets.sh` | ✅ | ⬜ |
| 07-T2 | 29-07 | 4 | DPLY-03 | T-29-07-03 | Grafana admin from a Secret; provisioning byte-identical | static | `kubectl kustomize k8s/staging \| grep -c 'url: http://prometheus:9090'` | ✅ | ⬜ |
| 07-T3 | 29-07 | 4 | DPLY-03 | T-29-07-01/05 | No Ingress for Prometheus/Alertmanager (new INV) | static | `./k8s/scripts/check-render-invariants.sh && ./k8s/scripts/render-golden.sh` | ✅ | ⬜ |
| 08-T1 | 29-08 | 5 | DPLY-01 | T-29-08-01/02 | Per-environment redirectUris/webOrigins; metrics on | static | `./k8s/scripts/check-render-invariants.sh && ./k8s/scripts/check-connection-math.sh` | ✅ | ⬜ |
| 08-T2 | 29-08 | 5 | DPLY-01/02 | T-29-08-03/04/05 | INV-6 backends resolve; staging noindex; staging issuer | static | `kubectl kustomize k8s/staging \| grep -c 'auth-staging.olajay.co.uk'` | ✅ | ⬜ |
| 08-T3 | 29-08 | 5 | DPLY-01 | T-29-08-06 | Render diff fully accounted for | static | `./k8s/scripts/render-golden.sh && ./k8s/scripts/check-env-contract.sh` | ✅ | ⬜ |
| 09-T1 | 29-09 | 6 | DPLY-01 | T-29-09-01/05 | Broker pinned + STOMP; INV-7 moves with the policy | static | `kubectl kustomize k8s/staging \| grep -c 'rabbitmq_stomp'` | ✅ | ⬜ |
| 09-T2 | 29-09 | 6 | DPLY-03 | T-29-09-02/04 | Mailhog in staging, ABSENT in production | static | `grep -c 'mailhog' <staging render>` vs `<production render>` | ✅ | ⬜ |
| 09-T3 | 29-09 | 6 | DPLY-01/03 | T-29-09-03/06/SC | Every new pin has a dated row; rabbitmq-k8s resolved | static | `./scripts/check-dependency-horizons.sh && ./k8s/scripts/render-golden.sh` | ✅ | ⬜ |
| 10-T1 | 29-10 | 3 | DPLY-01/05 | T-29-10-04/06/07 | Enforcing dataplane + PG16 read back from the provider | live | `az aks show … --query networkProfile.networkDataplane`; `az postgres flexible-server show … --query version` | ❌ **W0** | ⬜ |
| 10-T2 | 29-10 | 3 | DPLY-04 | T-29-10-02/03 | `jtoye_backup` rolbypassrls = t, `jtoye_runtime` = f | live | `psql -tAc "SELECT rolname,rolsuper,rolbypassrls FROM pg_roles WHERE rolname IN (…)"` | ❌ **W0** | ⬜ |
| 10-T3 | 29-10 | 3 | DPLY-01 | T-29-10-05 | Four staging names resolve; production names do not | live | `dig +short A app-staging.olajay.co.uk` (and the production negatives) | ❌ **W0** | ⬜ |
| 11-T1 | 29-11 | 7 | DPLY-01 | T-29-11-05/07 | Certificates READY; seed idempotent by row count | live | `kubectl get certificate`; `psql -tAc "SELECT count(*) …"` run twice | ❌ **W0** | ⬜ |
| 11-T2 | 29-11 | 7 | DPLY-01/02 | T-29-11-01/02/03/04 | HSTS + CSP + noindex in the SERVED response; digest parity | smoke | `curl -sI https://app-staging.olajay.co.uk \| grep -i strict-transport-security`; `EXPECT_SWAGGER=true ./scripts/smoke-test.sh https://api-staging.olajay.co.uk` | ❌ **W0** | ⬜ |
| 11-T3 | 29-11 | 7 | DPLY-01 | T-29-11-06 | Login proof shown RED against an absent client first | e2e | `PLAYWRIGHT_BASE_URL=https://app-staging.olajay.co.uk npx playwright test e2e/dashboard-mobile.spec.ts` (from `frontend/`) | ⚠ exists, never run against a public host | ⬜ |
| 12-T1 | 29-12 | 8 | DPLY-03 | T-29-12-04 | Every rule references an emitted metric | live | `PROM_URL=http://localhost:9090 bash scripts/check-alert-metrics.sh` | ✅ | ⬜ |
| 12-T2 | 29-12 | 8 | DPLY-03 | T-29-12-02/05 | Targets up, exporters not blind, transport end-to-end | live | `PROM_URL=… ALERTMANAGER_URL=… MAILHOG_URL=… PROM_EXEC=… bash scripts/check-alert-liveness.sh` | ⚠ needs 29-03 | ⬜ |
| 12-T3 | 29-12 | 8 | DPLY-03 | T-29-12-01/03/06 | An alert reaches a human inbox | checkpoint | fire an alert; confirm in Mailhog AND in the real inbox | ❌ **W0** | ⬜ |
| 13-T1 | 29-13 | 8 | DPLY-04 | T-29-13-01/05 | Drill cannot leave a billable server behind | static | `shellcheck -S error scripts/staging-pitr-drill.sh && ./scripts/check-gate-enforcement.sh` | ❌ **W0** | ⬜ |
| 13-T2 | 29-13 | 8 | DPLY-04 | T-29-13-03/06 | **Arm A**: a zero-row dump clears MIN_BACKUP_BYTES + `pg_restore --list` | live | `bash scripts/staging-pitr-drill.sh --both-arms` | ❌ **W0** | ⬜ |
| 13-T3 | 29-13 | 8 | DPLY-04 | T-29-13-04 | No WAL-G PITR claim survives | doc | `grep -n 'WAL-G' docs/architecture/SYSTEM_DESIGN_V2.md`; `./scripts/check-doc-metrics.sh` | ✅ | ⬜ |
| 14-T1 | 29-14 | 8 | DPLY-05 | T-29-14-01/04 | Probe proven able to connect BEFORE any denial | live | `bash scripts/check-networkpolicy-enforcement.sh --context … --control-only` | ⚠ needs 29-03 | ⬜ |
| 14-T2 | 29-14 | 8 | DPLY-05 | T-29-14-02/03/05 | The literal `TIMEOUT` captured from the denied arm | live | `bash scripts/check-networkpolicy-enforcement.sh --context …` | ⚠ needs 29-03 | ⬜ |
| 14-T3 | 29-14 | 8 | DPLY-05 | T-29-14-06 | Gate wired where a runtime exists, not exempted | static | `./scripts/check-gate-enforcement.sh` | ✅ | ⬜ |
| 15-T1 | 29-15 | 9 | DPLY-02 | T-29-15-01/02 | No long-lived kubeconfig; `id-token: write` job-scoped | static | `grep -c 'KUBE_CONFIG_STAGING' .github/workflows/ci-cd.yaml` must be 0 | ✅ | ⬜ |
| 15-T2 | 29-15 | 9 | DPLY-01 | T-29-15-03 | Rolled-out digests equal the pushed digests | live | the deploy job's post-rollout digest step | ❌ **W0** | ⬜ |
| 15-T3 | 29-15 | 9 | DPLY-02 | T-29-15-04/05/06 | #99 closes on a GREEN run; production flags stay off | checkpoint | the deploy-staging run URL; `dig +short A app.olajay.co.uk` empty | ❌ **W0** | ⬜ |
| 16-T1 | 29-16 | 10 | DPLY-02 | T-29-16-02/03/04 | Every issue closed or deferred WITH a reason | doc | `for i in …; do gh issue view $i --json state -q .state; done` | ❌ **W0** | ⬜ |
| 16-T2 | 29-16 | 10 | DPLY-02 | T-29-16-01/05 | One runbook page, every command run | doc | `./scripts/docs-freshness.sh && ./scripts/check-doc-metrics.sh && ./scripts/check-claims.sh` | ✅ | ⬜ |
| 16-T3 | 29-16 | 10 | DPLY-02 | T-29-16-06 | Every gate's rc recorded, VOIDs named as VOIDs | checkpoint | full sweep + `scripts/check-branch-behind-base.sh` | ✅ | ⬜ |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

The 9 gaps from `29-RESEARCH.md` § Validation Architecture, each now assigned to a plan. Every one
needs its fail direction run and recorded — the phase's own doctrine, and the reason Phase 26 found
~22 unfalsifiable criteria.

- [ ] `k8s/staging/scale-patch.yaml` — HPA/PDB, without which nothing schedules (Pitfall 6) → **29-02 T1**
- [ ] `scripts/check-alert-liveness.sh` — k8s exec path for L-0, inspectable sink for L-3 (Blocker B) → **29-03 T1/T2**
- [ ] `k8s/staging/configmap-patch.yaml` — `keycloak.client-id` (Blocker A) → **29-02 T1**
- [ ] NetworkPolicy egress for out-of-cluster datastores + INV-7 update (Blocker D) → **29-04 T1/T2**
- [ ] `scripts/staging-pitr-drill.sh` — both arms, with a `trap` that deletes the restored server → **29-13 T1**
- [ ] `scripts/check-networkpolicy-enforcement.sh` — agnhost two-arm probe, control arm first → **29-03 T3**
- [ ] `redis.ssl` config key + `spring.data.redis.ssl` block (Pitfall 7) → **29-02 T2**
- [ ] Regenerated `k8s/goldens/{staging,production}.yaml` → **29-02 T3, 29-04 T3, 29-06 T3, 29-07 T3, 29-08 T3, 29-09 T3**
- [ ] `infra/dependency-horizons.yaml` rows for cert-manager, the operator, the ingress controller, agnhost; `rabbitmq-k8s`'s `pin: unknown` replaced (**expires 2026-10-26**) → **29-09 T3**

Plus three gaps this plan set adds, from the same source:
- [ ] Staging seed path for DPLY-01's "real seeded rows" (none exists in the repo today) → **29-11 T1**
- [ ] Runtime-parity analogue for k8s (`check-runtime-freshness.sh` can only VOID against a cluster) → **29-11 T2** by hand, **29-15 T2** in CI
- [ ] `check-alert-corpus-parity.sh` — the executable form of D-16's "one corpus" → **29-06 T1**

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Owner disposition of the live `snackpass-*` estate in `jtoye-rg` | D-03 budget | A cost decision on a running estate is the owner's, not a planner's (assumption A4) | 29-01 T1: present re-measured spend and the three options |
| AWS credentials, Gmail app password, GHCR visibility, Netlify access | DPLY-01/03/04 | Measured absent on this host; no automated fallback exists | 29-01 T2: supply each with its verifying command output |
| Four DNS A records at Netlify | DPLY-01 | cert-manager has no Netlify DNS solver (D-07) | 29-10 T3: add four records, verify with `dig` from a non-default resolver |
| Vendor login through the ingress to a rendered dashboard | DPLY-01 | Real browser + Keycloak redirect over public DNS/TLS | 29-11 T3: Phase 26-08 L7 template, red arm run first |
| Alert email reaches a real inbox | DPLY-03 / #112 | The terminal receiver is a human mailbox | 29-12 T3: fire, confirm receipt, record spam/inbox placement |
| Repository secrets/variables + the green deploy run | DPLY-02 (#99) | Claude cannot write repo secrets or merge to main | 29-15 T3: set, delete `KUBE_CONFIG_STAGING`, merge, record the run URL |
| Phase verdict | all | Expectation questions no automated arm asks | 29-16 T3 |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or a Wave 0 dependency recorded above
- [x] Sampling continuity: no 3 consecutive tasks without an automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 300s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** planned 2026-08-10 — `wave_0_complete` flips when the 12 Wave-0 items above are green.
