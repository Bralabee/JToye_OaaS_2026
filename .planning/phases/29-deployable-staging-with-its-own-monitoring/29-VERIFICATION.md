---
phase: 29-deployable-staging-with-its-own-monitoring
verified: 2026-10-21T05:25:00Z
status: passed
score: 5/5 must-haves verified
re_verification: "2026-10-21T05:21Z: DPLY-03 gap closed by #1000 (merged 4f3f3e14, deploy run 39542329063 green); check-alert-metrics.sh rc 0 against live staging Prometheus, renamed-selector break arm rc 1, clean again rc 0"
covered_files: [".github/workflows/ci-cd.yaml", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-01-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-01-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-02-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-02-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-03-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-03-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-04-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-04-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-05-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-05-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-06-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-06-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-07-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-07-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-08-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-08-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-09-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-09-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-10-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-10-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-11-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-11-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-12-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-12-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-13-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-13-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-14-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-14-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-15-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-15-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-16-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-16-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-17-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-17-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-18-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-18-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-19-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-19-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-20-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-20-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-21-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-21-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-22-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-22-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-23-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-23-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-24-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-24-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-25-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-25-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-26-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-26-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-27-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-27-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-28-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-28-SUMMARY.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-29-PLAN.md", ".planning/phases/29-deployable-staging-with-its-own-monitoring/29-29-SUMMARY.md", "frontend/e2e/staging-login.spec.ts", "k8s/components/monitoring/alertmanager.yaml", "k8s/components/monitoring/grafana.yaml", "k8s/components/monitoring/prometheus.yaml", "k8s/staging/kustomization.yaml", "scripts/check-alert-liveness.sh", "scripts/check-alert-metrics.sh", "scripts/check-deploy-digest-parity.sh", "scripts/check-dply02-dispositions.sh", "scripts/check-networkpolicy-enforcement.sh", "scripts/check-staging-edge.sh", "scripts/staging-grafana-edge.sh", "scripts/staging-pitr-drill.sh"]
covered_digest: "v3:sha256:6245811bdb6d99b4abd8802a6fc27109f6969d30505ed3926aadc5c6e1dd33c6"
behavior_unverified: 0
overrides_applied: 0
gaps: []
resolved_gaps:
  - truth: "check-alert-liveness.sh and check-alert-metrics.sh exit 0 against the staging target, not against compose (ROADMAP SC3, DPLY-03)"
    was: "partial at 02:20Z: check-alert-metrics.sh rc 1 live, NoOrdersCreated read http_server_requests_seconds_count, a series destroyed by every core-java roll (#973)"
    resolved_by: "PR #1000 (merged 4f3f3e14): NoOrdersCreated reads sum(increase(jtoye_orders_total[30m])) < 1, the counter BusinessMetricsService registers at startup, so the series exists from the first scrape after every roll. Deployed by run 39542329063 (Security Scan, Deploy to Staging, Staging Edge Probe all success)."
    re_proven: "2026-10-21T05:21Z, 5 minutes after the roll, read-only port-forward to deploy/prometheus: rc 0 (19 live rules / 25 selectors); scratch rules file with the selector renamed to jtoye_orders_created_total rc 1 (M-1 NoOrdersCreated ZERO series); real rules again rc 0. jtoye_orders_total{instance=10.244.3.148:9091} present at 0; the live rule reads the new expression, state pending (muted on staging by design)."
human_verification: []
---

# Phase 29: Deployable Staging, With Its Own Monitoring - Verification Report

**Phase Goal:** A staging environment exists that a human can log into and that **alerts a human when it breaks** - the first runtime of this platform outside a laptop.
**Verified:** 2026-10-21T02:20:00Z
**Status:** passed
**Re-verification:** Yes - 2026-10-21T05:21Z, DPLY-03 only (see "Re-verification after #1000" under SC3). The rest of this report is the initial verification at 02:20Z and is unchanged.

Verified against: worktree `JToye_OaaS_2026-29-close` at `8499fdf8` (origin/main, #989 merged). The live staging runtime was read at 2026-10-21T02:08Z and runs `d887f27dce66a1371c9abec77330835c7f8b4ca4` (the #990 merge, one commit before `8499fdf8`; the diff between the two touches CI workflows, `check-deploy-digest-parity.sh`, `check-staging-edge.sh`, `gate-enforcement.conf` and `staging-db-bootstrap.sh` only, no image build path). The deploy of `8499fdf8` was in progress and was not waited for.

Method: SUMMARY.md claims were not trusted. Each success criterion was re-measured on the live estate (kube context `jtoye-stg-aks` only, namespace `jtoye-staging`, read-only; Azure only through `scripts/lib/azure-guard.sh` `az_read` on subscription `c483d353`) and on the tree, and the recorded evidence files were read for the parts that cannot be re-run without mutating the estate. Every pass below is paired with a fail direction that was either run today or read as raw output in an evidence file; where neither exists it says so.

## Goal Achievement

### Observable Truths (ROADMAP success criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Staging serves a real Keycloak vendor login through the ingress to a dashboard with real seeded rows, on a resolvable public hostname (DPLY-01) | VERIFIED | Re-run today on the live runtime, see SC1 below |
| 2 | The deploy knot (#99 #100 #292 #293 #299 #301 #302 #271) is closed or deferred with a reason recorded per issue (DPLY-02) | VERIFIED (one traceability warning) | Gate rc 0 on live GitHub state, see SC2 below |
| 3 | Prometheus, Alertmanager and Grafana run in Kubernetes, and both alert gates exit 0 against the staging target (DPLY-03) | VERIFIED (re-verified 05:21Z after #1000; liveness gate evidence dated 2026-10-19) | Stack running and healthy; metrics gate red at 02:20Z (#973), green live after the #1000 deploy with a break arm, see SC3 below |
| 4 | PITR with a restore proven by row count on a restored database, two-arm, arm A showing a zero-row dump passes the pipeline checks (DPLY-04) | VERIFIED | Raw drill output plus today's live reads, see SC4 below |
| 5 | NetworkPolicies are enforced, not merely rendered, with a denied connection captured (DPLY-05) | VERIFIED (evidence dated 2026-10-19; not re-probed today) | Raw enforcement output plus today's live==render parity, see SC5 below |

**Score:** 5/5 truths verified (4/5 at the initial 02:20Z run), 0 behavior-unverified, 0 overrides.

### SC1 / DPLY-01: vendor login through the ingress to a dashboard with seeded rows

**Claim:** one verbatim authorize URL carrying `client_id` and an encoded `redirect_uri`, landing on a rendered dashboard with real rows, on a public hostname, not a port-forward.

**Evidence (today, 2026-10-21):**
- DNS: `app-`, `api-`, `auth-`, `mail-`, `grafana-staging.olajay.co.uk` all resolve to `51.105.43.14` at both `dns1.registrar-servers.com` and `8.8.8.8`.
- Credentialed browser run, vendor password read through `az_read` from `jtoye-stg-kv` (length 25, never printed), `STAGING_E2E=1 PLAYWRIGHT_BASE_URL=https://app-staging.olajay.co.uk npx playwright test e2e/staging-login.spec.ts --project=desktop`, started 02:09:27Z, rc 0: `2 passed (13.7s)`.
  - `AUTHORIZE_URL https://auth-staging.olajay.co.uk/realms/jtoye-staging/protocol/openid-connect/auth?scope=openid+profile+email&response_type=code&client_id=core-api&redirect_uri=https%3A%2F%2Fapp-staging.olajay.co.uk%2Fapi%2Fauth%2Fcallback%2Fkeycloak&...`
  - `FINAL_URL https://app-staging.olajay.co.uk/dashboard`, `H1 Dashboard`, `PRODUCT_COUNT 5 (Curry Channa & Aloo, Doubles, Guyanese Cook-up Rice, Guyanese Pepperpot, Pelau)`.
  - Second test: the customer realm (`jtoye-customers`, `storefront-client`, redirect `/shop/auth/callback`) serves its login page 200 with the J'Toye theme CSS. This is the #299 outcome.
- Identity of the running thing, by content not status code: `scripts/check-deploy-digest-parity.sh --context jtoye-stg-aks --tag d887f27d...` rc 0, core-java `sha256:827528c2...`, edge-go `sha256:c7ad50bc...`, frontend `sha256:2ea14849...` all MATCH. Certificate issuer is Let's Encrypt `CN=YR1`, notAfter 2027-01-17.
- Edge fidelity: `scripts/check-staging-edge.sh --skip-burst` rc 0, 4/4 (headers, CSP admits the realm auth and token URLs in connect-src and form-action, CORS echoes the app origin and refuses `https://evil.example`, `/admin/` is 404 while the realm discovery document is 200).
- Seeded rows: the public storefront JSON for `staging-test-kitchen` returns the dishes; Pelau carries a `jtoyestgmedia.blob.core.windows.net` WebP `imageUrl` that fetches `200 image/webp 6538 B` with `RIFF....WEBP` magic bytes (this is the 29-14 unmet storefront-image truth, closed by 29-29).

**Fail direction:**
- Today, same authorize URL with `client_id=nosuchclient`: HTTP 400 and the Keycloak page contains "Client not found"; with `client_id=core-api`: HTTP 200 "Sign in to J'Toye". The spec's step is therefore capable of failing on the client id.
- Recorded raw in `evidence/29-14-tracer.txt`: pre-deploy RED (`ERR_CERT_AUTHORITY_INVALID` on the Traefik default certificate, 404), wrong client id (`invalid_client`), Flyway as `jtoye_runtime` (`42501`), owner as `DB_USER` refused by the validator, production names `api`/`app.olajay.co.uk` NXDOMAIN with `aa` (re-measured today: `status: NXDOMAIN` for both, `NOERROR` for the google.com control), 0 `FileNotFoundException`, mail-in-readiness 503. I did not re-run the wrong-password arm (Keycloak lockout risk).
- Digest parity control today: the same gate against the earlier tag `33585b23...` returns rc 1 with all three digests "NOT IN THE EXPECTED SET".

**Not live-verified but recorded:** the browser run proves a vendor can sign in and see rows; it does not prove a customer can complete sign-up through the Mailpit mail path (recorded in 29-26 evidence; not re-run).

**Verdict: VERIFIED.**

### SC2 / DPLY-02: the deploy knot closed or deferred with a reason per issue

**Claim:** every named issue has a written, evidence-linked disposition, applied after the owner approved the exact table.

**Evidence (today):**
- `bash scripts/check-dply02-dispositions.sh` rc 0 `derived=20 table=20 findings=0`; `--post-close` rc 0 `rows=20 findings=0` (the live GitHub state matches each row's expected state); `--verify-approval` rc 0 `pinned=19`, approved manifest hash `1d50dd2c...` equals the current manifest.
- Independent `gh issue view` of the eight named issues: #99 CLOSED 2026-10-20T19:36:58Z, #100 CLOSED 19:37:02Z, #292 CLOSED 2026-08-04, #293 CLOSED 2026-08-04, #299 CLOSED 19:37:31Z, #302 CLOSED 2026-08-03, #271 CLOSED 2026-08-04, #301 OPEN (deferred, reason written in the table row and in `evidence/29-27-comments/issue-301.txt`). The related #101 #112 #296 #297 #300 #592 #859 CLOSED; #98 #294 #304 OPEN as deferred with written reasons.
- #99 is closed on a deploy that ran: CI run 39471893302 on `33585b23`, job "Deploy to Staging" `success`, "Deploy to Production" `skipped`. The first attempt on the phase merge `bffd2f74` failed at digest parity and rolled back by the rule (run 39440780472, recorded in `evidence/29-28-merge-deploy.txt`); the gate fault was fixed through its own reviewed PR #986.
- Tree checks today (jtoye-ops env): `check-render-invariants.sh`, `render-golden.sh`, `check-env-contract.sh`, `check-no-plaintext-secrets.sh`, `check-connection-math.sh`, `check-keyvault-chain.sh`, `check-gate-enforcement.sh`, `docs-freshness.sh`, `check-doc-metrics.sh`, `check-alert-rules.sh`, `check-no-create-extension.sh`, `check-doc-citations.sh`, `check-dependency-horizons.sh` all rc 0. `/var/log/jtoye` is mounted in `k8s/base/core-java-deployment.yaml:790` (#302); the 5432 egress is a replaced-from-config value (`k8s/base/networkpolicies/20-core-java.yaml:160`, #271).

**Fail direction:** `check-dply02-dispositions.sh --selftest` 64/64 rc 0 (run today). Recorded in 29-27/29-28: one changed character of the approved hash rc 1; "closes #99" and an attribution trailer line each rc 1 on the text scanner; the rollback rule applied for real on attempt 1 (`DEPLOY_STAGING_ENABLED` false, read back); the digest-parity selftest 33/33 with three break arms restored by sha256.

**Warnings (do not fail the criterion, which asks for "closed or deferred with a reason"):**
1. #301 is deferred "to Phase 32", but the ROADMAP Phase 32 section (22 lines scanned; the control pattern `production|cutover` matches 6 lines in the same range) names no mcp-server work. The deferral reason is recorded; the receiving phase does not carry it as a success criterion, so it can be lost.
2. #292 is closed (2026-08-04) with the residue that Phase 29 builds the staging-origin image only. The repo variable `FRONTEND_PUBLIC_API_URL` points at staging (#980), so a production build from main would bake the staging API origin. The CR-02 guard refuses that in CI (29-21), but Phase 32 owns the per-environment images.

**Verdict: VERIFIED.**

### SC3 / DPLY-03: monitoring in Kubernetes, both gates exit 0 against staging

**Claim:** Prometheus, Alertmanager and Grafana run in k8s; `check-alert-liveness.sh` and `check-alert-metrics.sh` exit 0 against the staging target.

**Evidence (today, read-only `kubectl exec`):**
- Manifests exist: `k8s/components/monitoring/{prometheus,alertmanager,grafana}.yaml`. Pods Running: `prometheus-55c4dc84b4-64bxd`, `alertmanager-6688ccf5b6-6ssw2`, `grafana-5887cdc944-dg476` (13 h, 13 h, 4 h 52 m), plus `postgres-exporter` and `redis-exporter`.
- Prometheus: `/-/healthy` ok; 8 active targets, all `up` (core-java, edge-go, keycloak, postgres, prometheus, rabbitmq, rabbitmq-queues, redis); 19 rules, all `health: ok`, none firing; one active Alertmanager `http://alertmanager:9093`.
- Alertmanager v0.34.1, cluster `ready`; `alertmanager_notifications_failed_total{integration="email"}` is 0 for every reason (this pod has sent 0 emails since it started).
- Grafana 13.2.4 `database: ok`; the edge basic-auth gate (#990): `scripts/staging-grafana-edge.sh --probe --context jtoye-stg-aks` rc 0, `PROBE: 4/4 PASS` (no credentials 401, random wrong password 401, credential 200 with Grafana title, http redirects 308); its `--selftest` 18/18; anonymous `https://grafana-staging.olajay.co.uk/` and `/api/health` both 401.
- Gates, recorded raw in `evidence/29-25-dply03.txt`: `check-alert-metrics.sh` rc 0 at 17:36:04Z and 18:09:14Z ("19 live rule(s) / 25 selector(s) all match >= 1 live series"); `check-alert-liveness.sh` rc 0 at 17:36:57Z-17:37:36Z (targets=8 down=0, rules=19 selectors-matching-0-series=0, probe delivered to Mailpit, attempts 0 to 2, failed 0 to 0); owner confirmed the probe in the real inbox (recorded 17:44:18Z, pasted body in the evidence). Static: `check-alert-rules.sh` rc 0, `check-alert-liveness.sh --selftest` 10/10 rc 0.

**Fail direction:**
- Recorded: planted rule with no series, scratch copy of `alerts.yml`, same live Prometheus: rc 1 "selector matches ZERO series" (`29-25-dply03.txt` section 3e); redis-exporter stopped reds the gate; wrong SMTP password raises `alertmanager_notifications_failed_total`.
- **Observed today, unplanted:** `check-alert-metrics.sh` against the live staging Prometheus (queries tunnelled through `kubectl exec deploy/prometheus -- wget`, so no port-forward and no write) returned rc 1: `FAIL: M-1 rule 'NoOrdersCreated' selector matches ZERO series ... CAUSE: http_server_requests_seconds_count is created on the FIRST matching request and is destroyed when core-java restarts`. The tunnel was proven live in the same run (it queried 28 selectors, 25 live and 3 dormant, and every other rule passed).

**Gap:** the criterion's literal condition (both gates exit 0 against staging) holds only after `scripts/seed-order-metric.sh` writes one guest order, and every deploy undoes it. That script is a business-row write the owner must approve (`docs/runbooks/staging-operations.md:153-155`). Staging redeploys on every merge to main. Issue #973 tracks it (OPEN). The substance (stack running, targets up, rules valid, delivery to a human proven) is intact; the standing proof is not. I could not take the order (a write the constraints forbid) so I did not re-prove rc 0 today. I also did not run `check-alert-liveness.sh`: it posts a synthetic alert that emails the owner.

**Warnings:**
1. #98 stays OPEN as an approved deferral: the frontend has no scrape job (#971) and log aggregation and edge-to-core tracing are out of D-16 scope. The ROADMAP table says "DPLY-03 *is* this issue", so DPLY-03 as the ROADMAP states it is met in its two named gates but not in #98's full scope. The disposition is recorded and owner-approved.
2. #112 closed as a minimal close with two acceptance criteria deliberately unmet (first-response runbook entries, SLOs), carried by #982.
3. NoOrdersCreated is muted by design on staging (alertname-only matcher, proven both directions with amtool in 29-25), so even when the counter exists a quiet staging never pages for it.

**Verdict: PARTIAL, a gap.** Override suggestion if the owner accepts #973 as a standing caveat:

```yaml
overrides:
  - must_have: "check-alert-liveness.sh and check-alert-metrics.sh exit 0 against the staging target, not against compose"
    reason: "Both exited 0 on staging on 2026-10-19 with fail arms; after every core-java roll the order counter (#973) is absent until seed-order-metric.sh is run, a documented owner-approved step"
    accepted_by: "{owner name}"
    accepted_at: "{ISO timestamp}"
```

I have not added it. The owner has not accepted this deviation in anything I can find, and adding it myself would be the verifier approving its own gap.

#### Re-verification after #1000 (2026-10-21T05:21Z)

The override above was never needed: the owner chose the fix. PR #1000 (series ended on round 3, zero admissible, Review-Record on `6d0fbb93`) changed `NoOrdersCreated` in both rule files (byte-identical) to `sum(increase(jtoye_orders_total[30m])) < 1`. `jtoye_orders_total` is the `jtoye.orders.created` counter, which BusinessMetricsService registers at startup. Micrometer's Prometheus exporter strips the reserved `_created` suffix, which is why the name is not `jtoye_orders_created_total`. The series therefore exists at 0 from the first scrape after every roll, with no order write. promtool unit tests for the rule are in `infra/monitoring/prometheus/tests/alerts.test.yml`, run by `check-alert-rules.sh` S-6. #1000 merged as `4f3f3e14`. Main run 39542329063 then reported Security Scan, Deploy to Staging and Staging Edge Probe all success; core-java and Prometheus pods were 5 minutes old when read.

Run against the live staging Prometheus (read-only `kubectl port-forward deploy/prometheus`; script and rules sha256-equal to `origin/main`):

- `check-alert-metrics.sh` **rc 0**: "PASS: 19 live rule(s) / 25 selector(s) all match >= 1 live series ... 3 dormant rule(s) still correctly have no data."
- **Break arm, rc 1:** a scratch copy of `alerts.yml` with the selector renamed to `jtoye_orders_created_total` (7 substitutions) gave "FAIL: M-1 rule 'NoOrdersCreated' selector matches ZERO series".
- **Clean again, rc 0:** the real rules file, after the break arm.
- `jtoye_orders_total{instance="10.244.3.148:9091",job="core-java"}` = 0 (the new pod, before any order). The `/api/v1/rules` row for NoOrdersCreated reads the new expression, state `pending`. That is expected on a quiet staging, and Alertmanager mutes it there (warning 3 above).

`check-alert-liveness.sh` was again **not** re-run, because it has no read-only mode and posts a synthetic alert that emails the owner. Its rc 0 on staging is dated 2026-10-19 (`evidence/29-25-dply03.txt`). The one known cause of its L-2/L-2b red since then was the zero-series NoOrdersCreated selector, which the metrics gate above shows is gone. This is the same dated-evidence standard SC5 is held to.

The first CI execution of `staging-edge-probe` was green in the same run: headers, csp, cors, admin 4 of 4. The burst arm is skipped by design (`--skip-burst`), so the job does not prove the rate limit.

**Verdict after re-verification: VERIFIED.**

### SC4 / DPLY-04: PITR with a two-arm restore proven by row count

**Claim:** a restore proven by row count on a restored database; arm A shows a zero-row or partial dump still passes the pipeline's own byte-size and `pg_restore --list` checks.

**Evidence:**
- Raw drill output `evidence/29-20-pitr.txt` section C (run 20261019144933, 14:49:33Z to 15:09:17Z): PITR of `jtoye-stg-pg` at T0 `14:51:13Z` into a new server; before-marker present, after-marker absent; arm P counts equal to source at T0 (products 6, orders 1, customers 0, shops 1). Arm A (`jtoye_drill_norls`, NOBYPASSRLS): plain `pg_dump` rc 1 "query would be affected by row-level security policy"; `--enable-row-security` dump passes the pipeline checks (`15841187 bytes, floor 1000 bytes`, 15841x the floor, `pg_restore --list` readable) yet restores to `products 0, orders 0` against source 6 and 1. Arm B (`jtoye_backup`, BYPASSRLS) restores to equal counts. Zero-byte file refused by the floor. The temporary server was deleted and the deletion read back with the live server as control.
- Today's live reads: `az postgres flexible-server show`: state Ready, version 18, `backupRetentionDays` 7, earliest restore point `2026-10-19T01:02:46Z`, HA Disabled. The container `jtoye-db-backups` immutability policy reads `state: Locked, days: 30` (via `az_read`). The pg-backup CronJob (`0 2 * * *`) has run twice: job `pg-backup-29874360` (2026-10-20, 14 s, `jtoye-backup-20261020-020002.dump`, 15860198 B) and job `pg-backup-29875800` (today); its log reads `Starting backup ... as jtoye_backup (BYPASSRLS expected)`, `Dump verified: 15860202 bytes, archive readable`, `Backup complete: jtoye-backup-20261021-020024.dump`. The two sizes differ by 4 bytes, consistent with a full and not a partial dump (the arm A partial dump was about 11 KB smaller than the arm B full one).

**Fail direction:** arm A is the fail direction by construction and is recorded as such; the plan's verify line was also shown failing on three edited copies of the output (deletion line removed, rc 1, counts-did-not-differ rewritten), `29-20-pitr.txt` section D.

**Not claimed and not verified:** the stored `.dump` blobs themselves were never downloaded or restored (the owner's temporary Reader grant was revoked the same minute, and I have no read on that container); the drill's arm B dump was taken fresh from the restored server with the same role and image script as the CronJob. DB high availability is out of this criterion and deferred (#983, Burstable SKU, Phase 32). I did not repeat the drill (it creates a billable server).

**Verdict: VERIFIED.**

### SC5 / DPLY-05: NetworkPolicies enforced, not merely rendered, denied connection captured

**Claim:** an enforcing engine, with a denied connection captured as positive proof; the Phase 26 "NOT PROVEN" record not inherited.

**Evidence:**
- Engine today: `az aks show` `networkDataplane: cilium`, `networkPolicy: cilium`, `networkPlugin: azure`, tier Free, Kubernetes 1.36.4; the `cilium` DaemonSet is 4/4 Ready in `kube-system`.
- Raw run `evidence/29-17-enforcement.txt` (2026-10-19T11:30:59Z to 11:37:48Z, context `jtoye-stg-aks`, pinned agnhost digest): control `A` app=frontend to core-java:9090 CONNECTED; `B` unlabelled to the same Service `rc=1 output=[TIMEOUT]` DENIED; `M2` and `K2` DENIED against the management port and Keycloak; `E2` DENIED on egress 1.1.1.1:80 while the allowed `E1` 443 CONNECTED; policy-free namespace arm `P0` CONNECTED (so the probe can connect where no policy exists, which is what makes the TIMEOUTs the policy's). Verdict `PASS ... NetworkPolicy is ENFORCED on context 'jtoye-stg-aks' (Cilium)`, `CLEANUP remaining=0`.
- Today's parity: 17 NetworkPolicies live in `jtoye-staging`, all 17 spec-equal to `k8s/goldens/staging.yaml` (compared by content in Python; no policy only-live, none only-golden; a planted podSelector change on `core-java-allow` reads as different, so the comparison can fail). The 29-14 SUMMARY recorded 11 policies; I did not establish how many existed at the time of the 29-17 run, and the monitoring policies (29-23/29-24) came after it.

**Fail direction:** in the same raw file, the Blocker F arm removed `core-java-allow` `spec.ingress[1]` live: `F1` flipped to DENIED, the check exited 1, and the public API timed out (`curl: (28)`, code 000) for about 70 s; the rule was restored by JSON patch and `cmp(saved, live)=0`, `cmp(committed render, live)=0`, F1 CONNECTED, API 200. Also: the first attempt at `E2` on port 25 was VOIDed because port 25 times out with no policy at all (the classifier refuses a denial it cannot attribute to the policy); `--selftest` 22/22 after a RED of 3/21; a concurrent run refuses without deleting the other run's objects; a SIGTERM leaves 0 artefacts.

**Limit:** I did not re-run the probe today. It creates a namespace and pods, which the read-only constraint forbids. The denied connection is therefore dated 2026-10-19; what is re-proven today is that the live policy set equals the committed render and runs on the Cilium dataplane. Policies added after the run (the monitoring ones, e.g. `alertmanager-allow`, `grafana-allow`, `prometheus-allow`, the exporters) have no denied-arm of their own in the 29-17 file; I did not check whether 29-23/29-24 recorded one. Cilium endpoint status (`kubectl get cep -o json`) exposes no `policy.enforcing` field in this version, so it gave no live signal.

**Verdict: VERIFIED** (on dated raw evidence plus today's config parity; see the limit).

## Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `scripts/check-deploy-digest-parity.sh` | pod digests vs built images | VERIFIED | run today, pass and wrong-tag control |
| `scripts/check-staging-edge.sh` | edge fidelity | VERIFIED | 4/4, burst arm not run |
| `scripts/check-dply02-dispositions.sh` | DPLY-02 gate | VERIFIED | three modes rc 0, selftest 64/64 |
| `scripts/check-alert-metrics.sh`, `check-alert-liveness.sh` | DPLY-03 gates | VERIFIED (after #1000) | metrics gate rc 0 live with a break arm at 05:21Z; it was red at 02:20Z (#973); liveness dated 2026-10-19 |
| `scripts/check-networkpolicy-enforcement.sh` | DPLY-05 probe | VERIFIED | raw run, not re-run today |
| `scripts/staging-pitr-drill.sh` | DPLY-04 drill | VERIFIED | raw run, not re-run today |
| `scripts/staging-grafana-edge.sh` | Grafana edge gate | VERIFIED | probe 4/4, selftest 18/18 |
| `k8s/components/monitoring/{prometheus,alertmanager,grafana}.yaml` | in-cluster stack | VERIFIED | pods Running and healthy |
| `frontend/e2e/staging-login.spec.ts` | DPLY-01 spec | VERIFIED | 2 passed today |

## Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| app-staging | auth-staging | NextAuth redirect, `client_id=core-api`, encoded `redirect_uri` | WIRED | spec printed the URL; a wrong client id returns 400 |
| Keycloak callback | `/dashboard` | `/api/auth/callback/keycloak` | WIRED | FINAL_URL `/dashboard`, h1, 5 rows |
| Prometheus | Alertmanager | `alertmanager:9093` | WIRED | `/api/v1/alertmanagers` lists it, none dropped |
| Alertmanager | owner inbox | Zoho STARTTLS | WIRED at 2026-10-19 | recorded, owner confirmed; 0 sends since this pod started |
| CronJob pg-backup | Blob `jtoye-db-backups` | write-only identity | WIRED | job log "Backup complete" today; container Locked |
| CI deploy-staging | cluster | OIDC + kubelogin | WIRED | run 39471893302 success |
| Traefik | core-java | NetworkPolicy `core-java-allow` | WIRED and enforced | F1 arm both directions |

## Data-Flow Trace (Level 4)

| Artifact | Data variable | Source | Real data | Status |
|----------|---------------|--------|-----------|--------|
| dashboard | product rows | `/api/v1/products` as the vendor, managed Postgres | 5 seeded dishes returned to a signed-in vendor | FLOWING |
| storefront | `imageUrl` | `media_asset` ACTIVE WebP in Blob | fetches 200 image/webp 6538 B | FLOWING |
| Grafana/Prometheus | rule series | live scrape of 8 targets | 19 of 19 rules have series after #1000 (NoOrdersCreated reads the startup-registered `jtoye_orders_total`) | FLOWING |

## Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| vendor login to dashboard | Playwright staging-login spec | 2 passed, 5 rows | PASS |
| wrong client id refused | curl authorize URL | 400 "Client not found" | PASS (fail direction) |
| runtime = built images | `check-deploy-digest-parity.sh --tag d887f27d` / `--tag 33585b23` | rc 0 / rc 1 | PASS / FAIL as intended |
| Grafana gated at the edge | `staging-grafana-edge.sh --probe` | 4/4 | PASS |
| public path ignores spoofed forwarded host | curl with `X-Forwarded-Host: evil.example` | `token_endpoint` unchanged | PASS |
| live policy set equals render | Python content compare | 17 = 17, 0 differing, control differs | PASS |
| alert metrics against live Prometheus | `check-alert-metrics.sh` via exec tunnel (02:20Z) | rc 1, NoOrdersCreated | FAIL (gap, #973) |
| alert metrics against live Prometheus after #1000 | `check-alert-metrics.sh` via port-forward (05:21Z) / renamed-selector scratch rules / clean again | rc 0 / rc 1 / rc 0 | PASS / FAIL as intended / PASS |

## Probe Execution

No `probe-*.sh` scripts are declared for this phase; its gates are the `scripts/check-*.sh` and `staging-*.sh` family run above (Step 7c: not applicable).

## Requirements Coverage

| Requirement | Source plans | Description | Status | Evidence |
|-------------|--------------|-------------|--------|----------|
| DPLY-01 | 29-01..05, 07..16, 18, 22, 29 | real KC vendor login through ingress, public hostname, seeded rows | SATISFIED | SC1 |
| DPLY-02 | 29-02..04, 08, 10..15, 24..26, 28 | deploy knot closed or deferred per issue | SATISFIED (two warnings) | SC2 |
| DPLY-03 | 29-23, 29-24, 29-25 | Prometheus, Alertmanager, Grafana in k8s; both gates exit 0 on staging | SATISFIED (after #1000; liveness evidence dated) | SC3, including the re-verification |
| DPLY-04 | 29-07, 29-09, 29-20 | PITR restore by row count, two-arm | SATISFIED | SC4 |
| DPLY-05 | 29-01, 29-02, 29-09, 29-17 | NetworkPolicies enforced, denied connection captured | SATISFIED (dated evidence) | SC5 |

Orphaned requirements: none. REQUIREMENTS.md lists exactly DPLY-01..05 for Phase 29 and all five are claimed by plans. The five rows are marked Complete "pending phase verification": The initial run confirmed DPLY-01, 02, 04 and 05 but not DPLY-03 as written. The 05:21Z re-verification after #1000 confirms DPLY-03.

## Anti-Patterns Found

No new code was scanned for stubs (the phase is infrastructure, gates and manifests, and its code is covered by the gates above). Specific findings:

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `.planning/phases/29-.../29-VALIDATION.md` | frontmatter | `status: draft`, `nyquist_compliant: false`, `wave_0_complete: false`, sign-off boxes unchecked, `Approval: pending` | Warning | validate-phase was never run; the per-requirement map there is the plan, not a result |
| `29-SECURITY.md` | n/a | did not exist at 02:20Z | Resolved | written by `/gsd-secure-phase 29` the same day; register 133 rows, `threats_open: 0` |
| `k8s/staging` Keycloak | n/a | in-cluster pods can spoof `X-Forwarded-*` to `keycloak:8080` (`KC_PROXY_TRUSTED_ADDRESSES=10.244.0.0/16` admits the whole pod CIDR) | Warning | accepted residual (29-28, #988); public path measured unaffected today; what else on the pod network can reach Keycloak was not measured by either 29-28 or me |
| Phase diff `a5dd4d94..8499fdf8` (non-markdown, 34945 added lines) | 1 hit | `TBD` word-match | Info | one match, a deliberate selftest plant in `check-dply02-dispositions.sh` that rewrites a table row to `deferred: TBD` to prove the gate refuses a placeholder; not a debt marker. Scan control: a planted `FIXME` line matches. Markdown files were not scanned |

## Human Verification Required

None. At 02:20Z the gap needed an owner decision: fix #973, or accept it with an override. The owner chose the fix (#1000).

## Gaps Summary

Four of the five success criteria hold on today's live estate, and the headline outcome of the phase is real: a vendor signs in through the public ingress to a dashboard with seeded rows (re-run today on the CI-deployed runtime, digest parity proven by content); the deploy knot is dispositioned issue by issue with the live GitHub state matching the owner-approved table; the PITR restore is proven by row count with a zero-row arm A that passes the pipeline checks (and the backup runs nightly into a locked container); NetworkPolicy enforcement is shown by a captured TIMEOUT with a policy-free control and a live rule-removal fail arm.

**Closed at 05:21Z.** The paragraph below is the 02:20Z state. #1000 fixed it, and SC3's re-verification has the live proof (rc 0, break arm rc 1, clean again rc 0).

One gap blocks a clean pass. DPLY-03's second clause, both alert gates exit 0 against staging, is **not true now**: `check-alert-metrics.sh` returns 1 against the live staging Prometheus because core-java rolled 11 minutes earlier and the order counter behind `NoOrdersCreated` is gone. It was green on 2026-10-19 and the cause is a ticketed, owner-aware defect (#973), but with `DEPLOY_STAGING_ENABLED=true` it will be the standing state after every merge. The monitoring itself (targets, rules, Alertmanager, Grafana, the delivery path to a human) is intact; the gate is correctly reporting that one alert is blind. Either fix #973 (register the counter at zero on startup, a small core-java change) or have the owner record the override above. `check-alert-liveness.sh` was not re-run today and is expected to red L-2 for the same reason.

Warnings that do not change the status: #98 and #112 are closed or deferred below their full scope by owner-approved dispositions; #301's receiving phase does not list it; the SC5 denied connection is dated 2026-10-19 and the later monitoring policies have no denied-arm of their own in that file; the DPLY-04 stored dumps were never restored (only the PITR server and a fresh dump from it were); `29-VALIDATION.md` is outstanding housekeeping (`29-SECURITY.md` was written later the same day).

Gate re-run limits stated plainly: not re-run today because they would mutate the estate or send mail were the denied-connection probe (creates pods), the PITR drill (creates a billable server), `check-alert-liveness.sh` (emails the owner), `seed-order-metric.sh` (writes an order) and the edge burst arm (1500 requests at concurrency 40). None was re-run and none is claimed as re-proven today.

---

_Verified: 2026-10-21T02:20:00Z_
_Verifier: Claude (gsd-verifier)_
