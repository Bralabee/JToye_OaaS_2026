---
phase: "29"
slug: "deployable-staging-with-its-own-monitoring"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-10-18"
---

# Phase 29 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `29-RESEARCH.md` §Validation Architecture, amended by owner rulings D-29..D-32 in `29-CONTEXT.md`.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Bash gates (exit 0 clean / 1 violation / 2 VOID); JUnit Jupiter 6 via Gradle; Playwright 1.64.0; live probes (curl, agnhost, psql in the PG 18 image) |
| **Config file** | `k8s/scripts/*.sh`, `scripts/check-*.sh`, core-java Gradle test tasks, `frontend/playwright.config.ts` |
| **Quick run command** | `k8s/scripts/check-render-invariants.sh && k8s/scripts/render-golden.sh && k8s/scripts/check-env-contract.sh && k8s/scripts/check-no-plaintext-secrets.sh && k8s/scripts/check-connection-math.sh` |
| **Full suite command** | quick set + `scripts/check-dependency-horizons.sh` + `scripts/check-no-create-extension.sh` + `scripts/check-gate-enforcement.sh` + `scripts/check-alert-rules.sh` + `scripts/check-no-object-store-residue.sh` + `scripts/check-doc-citations.sh` + `./gradlew test` for touched tests |
| **Estimated runtime** | quick ~30 s (no cluster); full ~10 min; live arms per plan |

---

## Sampling Rate

- **After every task commit:** quick static set
- **After every plan wave:** full static suite + touched unit tests
- **Before `/gsd-verify-work`:** every live arm below recorded with raw output and ISO-8601 timestamps; runtime parity by running-pod image digests
- **Max feedback latency:** 60 s for static checks

---

## Per-Task Verification Map

Requirement-level map (task IDs are filled in by the planner/executor):

| Req | Behaviour | Pass direction | Fail direction (must be shown first) |
|-----|-----------|----------------|--------------------------------------|
| DPLY-01 | Real KC login through the ingress to a rendered dashboard with seeded rows | verbatim authorize URL (`client_id`, encoded `redirect_uri`) → `/dashboard`, `<h1>`, API row counts | wrong client id → `invalid_client`, no dashboard |
| DPLY-01 | Staging hosts resolve; production hosts do not | `dig @dns1.registrar-servers.com` = ingress IP | `api`/`app.olajay.co.uk` stay NXDOMAIN; positive control resolves |
| DPLY-01 / D-29 | Traefik edge keeps headers, CSP (`auth-staging`), noindex, CORS, rate limit | `curl -sI`, preflight, 429 on burst | host/route without the annotation lacks them |
| DPLY-01 / E | Fresh DB boots with the migrator/runtime split | Flyway as migrator, validator PASS as runtime | `DB_MIGRATION_USER` unset → Flyway fails; runtime = owner → validator refuses |
| DPLY-02 | Every listed issue closed/deferred with a written reason | checklist asserted against `gh issue view` | issue missing from checklist → fail |
| DPLY-02 / #592 | One-click unsubscribe URL wired | extended `UnsubscribeLinkRoutingTest` | RED on current tree |
| DPLY-02 / #99 | CI deploy green and deploys the commit's images | deploy job green + digest parity rc 0 | prod `FRONTEND_PUBLIC_API_URL` fails CR-02; planted stale digest fails parity |
| DPLY-03 | Rules see live series | `check-alert-metrics.sh` rc 0 vs staging | stopped exporter → violation |
| DPLY-03 | Targets up, transport end to end | `check-alert-liveness.sh` (k8s exec + Mailpit sink) rc 0 | served-file edit → VOID/violation; broken route → violation |
| DPLY-03 | Real human paged (Zoho) | probe email in the real inbox, timestamped (manual) | wrong password → `alertmanager_notifications_failed_total` rises |
| DPLY-04 | PITR restore carries source row counts | restored counts = source in one statement with `current_database()` | — |
| DPLY-04 | Arm A: partial dump passes pipeline checks | byte size + `pg_restore --list` pass, restored `products` = 0 ≠ live | job as non-BYPASSRLS role exits non-zero / uploads nothing |
| DPLY-05 | Denied connection captured under enforcement | control connects, denied prints TIMEOUT | policy-free namespace: "denied" pod connects (probe can connect) |
| D-20 | `starttls.required` true under prod + staging | extended `MailStartTlsPropertyTest` | RED before the literal |
| D-22 / D-30 | Mail over STARTTLS with the JVM truststore; public TLS still works | onboarding mail read back from Mailpit AND a Stripe/Blob TLS call succeeds | plain SMTP → `MessagingException`; CA-only truststore → public TLS fails |
| D-23 | Secret names present; write-once | preflight rc 0; re-run changes no versions | missing `secretKeyRef` name listed and refused; overwrite of `DSAR_ENCRYPTION_KEY` refused |
| D-25 | Connection math per target | gate rc 0 with `RESERVED=15` | pool inside old budget but outside new → fail |
| D-28 / D-31 | Signed TEST webhook verified; seed order settled by PaymentIntent | 200 + `processed_stripe_events` row; order PAID | unsigned/forged → 400, no row |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `k8s/staging/scale-patch.yaml` (HPA floor, research Pitfall 1)
- [ ] Blockers A, D, E, F, G, H repo fixes, each with its fail arm committed before arms run
- [ ] `scripts/check-alert-liveness.sh` — k8s exec + sink abstraction (Blocker B)
- [ ] headless `core-java-metrics` Service + `dns_sd_configs`
- [ ] `k8s/scripts/check-connection-math.sh` per-target `RESERVED` (D-25)
- [ ] extended `MailStartTlsPropertyTest` (D-20) and `UnsubscribeLinkRoutingTest` (#592)
- [ ] DPLY-02 checklist file + its assertion
- [ ] Traefik annotation-fidelity check (D-29)
- [ ] carried scripts re-pinned and wired or exempted in `scripts/gates/gate-enforcement.conf`
- [ ] `infra/dependency-horizons.yaml` rows for every new pinned component (Traefik, cert-manager, RabbitMQ operator, Mailpit, agnhost)
- [ ] regenerated goldens and `docs/metrics.json` in the same commits as their sources

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| DNS records at Namecheap | DPLY-01 | agent DNS edits are blocked; owner holds the registrar login | owner adds the four staging A records; agent verifies with `dig` |
| Real inbox receives a page | DPLY-03 | a human inbox is the thing under test | owner confirms the timestamped probe email arrived |
| Stripe test-dashboard webhook registration | D-28 | dashboard action on the owner's Stripe account | owner registers the endpoint; signing secret goes to Key Vault |
| Cost meters after 24 h | D-24 / D-03 | owner decision on the ceiling | Cost Management by meter: `FreeTierInfrastructureCost`, `snackpass-pg` |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
