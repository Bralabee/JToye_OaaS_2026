---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 15
subsystem: infra
tags: [traefik, edge, security-headers, csp, cors, rate-limit, trusted-proxies, live, staging]
status: complete

requires:
  - phase: 29-14
    provides: "Staging live at DEPLOYED_SHA a784e7e2, certificates on letsencrypt-prod, the measured Traefik pod addresses"
  - phase: 29-10
    provides: "keycloak-public routes only /realms/jtoye-staging, /realms/jtoye-customers and /resources/"
  - phase: 29-03
    provides: "Traefik v3.7.15 with the Ingress-NGINX provider and the D-29(a) annotation table"
provides:
  - "scripts/check-staging-edge.sh: a re-runnable live edge gate (headers, CSP, CORS, burst 429, admin path) with --fail-arms, registered in gate-enforcement.conf"
  - "Staging app-config security.trusted-proxies = 10.244.0.0/16, measured and deployed; a forged X-Forwarded-For no longer chooses or hides the client"
  - "The D-29(a) table's Observed column, 21 of 21 rows, each pointing into evidence/29-15-edge.txt"
affects: [29-21, 29-22, 29-26]

estimate:
  tokens: 70000
  tasks: 3
actuals:
  tokens: 22000
  tasks: 3
  commits: 21
plan_head_before: 36f199a720cc3937f610690e52354906bf0a1b57
plan_head_after: 43125fbb26226a44107487d98d0f47dae0d2f4d8

tech-stack:
  added: []
  patterns:
    - "A fail arm needs a route that differs only in the thing under test: a scratch agnhost Ingress with no annotations, gated on agnhost's own NOW: body before any arm runs"
    - "A burst probe must outrun the bucket's refill, so it targets a path the backend answers fast and ahead of the app's own limiter"
    - "A same-tag deploy updates a ConfigMap but not a pod's env: restart the Deployment and read the env back from the running pod"

key-files:
  created:
    - scripts/check-staging-edge.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-15-edge.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-15-t1-red-stub-arms.json
  modified:
    - scripts/gates/gate-enforcement.conf
    - k8s/staging/configmap-patch.yaml
    - k8s/goldens/staging.yaml
    - k8s/platform/traefik/README.md

key-decisions:
  - "security.trusted-proxies is the whole overlay pod CIDR 10.244.0.0/16: Traefik pods move on reschedule and node .spec.podCIDR is not populated under Azure CNI overlay, so no narrower value stays true"
  - "The burst arm targets api-staging /api/v1/edge-burst-probe (Spring Security 401 ahead of the app limiter), not a frontend path: the 500m-CPU frontend capped the client at ~68 rps and the 500-token bucket never emptied"
  - "Burst concurrency is 40, under limit-connections 50, so a burst 429 is the rate limit and not the in-flight cap"
  - "--fail-arms runs all five arms against the probe, not only headers and CSP"
  - "Option A (orchestrator): deployed at a784e7e2; running core-java is one config literal (29-16 STARTTLS required) behind the branch by design, closing at 29-22's deploy"

requirements-completed: [DPLY-01, DPLY-02]

coverage:
  - id: D1
    description: "Security headers incl. X-Robots-Tag noindex on app- and api-staging through Traefik; absent on a no-snippet route"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "check-staging-edge.sh normal rc 0 ([headers] all six present on both hosts); --fail-arms rc 0 ([fail-arm-headers] all six MISSING on /fidelity-probe); raw curl -sI recorded"
        status: pass
    human_judgment: false
  - id: D2
    description: "#293: the served CSP admits the realm auth and token endpoints in connect-src and form-action"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "[csp] each endpoint admitted by https://auth-staging.olajay.co.uk/realms/jtoye-staging/ (the bare realm source admits nothing, matcher table rc 1); probe route has no CSP -> FAIL"
        status: pass
    human_judgment: false
  - id: D3
    description: "CORS preflight allowed with credentials for app-staging, not echoed for evil.example"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "[cors] ACAO app-staging + ACAC true; evil.example ACAO none; probe route answers neither -> FAIL"
        status: pass
    human_judgment: false
  - id: D4
    description: "Burst yields 429 from the edge, not 503; sub-limit yields none; the answering layer recorded"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "[burst] 60 at 10/s -> 0 x 429; 1500 at c40 -> 416 then 679 edge 429s, 0 app, 0 x 503; probe route -> 0 x 429 -> FAIL"
        status: pass
    human_judgment: false
  - id: D5
    description: "Keycloak /admin/ not routed; realm discovery 200 with the public issuer"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "[admin] /admin/ and /admin/master/console/ 404, .well-known 200 issuer https://auth-staging.olajay.co.uk/realms/jtoye-staging; probe /admin route 200 -> FAIL"
        status: pass
    human_judgment: false
  - id: D6
    description: "trusted-proxies = measured podCidr, ETP Local kept, forged X-Forwarded-For not the resolved client"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "INV-12 verify rc 1 before / rc 0 after / rc 2 unreadable; before: 52 WARN lines keyed 10.244.1.134 and 10.244.2.154; after: 20 WARN lines all 81.96.202.37, 0 naming 203.0.113.9; env read from the running pod"
        status: pass
    human_judgment: false
  - id: D7
    description: "D-29(a) observed column complete"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "awk verify: pre-edit README VOID rc 2; one blanked cell rows=21 filled=20 rc 1; committed rows=21 filled=21 rc 0 (N=21 measured pre-edit)"
        status: pass
    human_judgment: false

duration: 27min
completed: 2026-10-19
---

# Phase 29 Plan 15: The Live Staging Edge, Proven Arm by Arm, and Trusted Proxies Set by Measurement

**Through Traefik, staging now serves the six security headers plus noindex, a CSP that admits the realm endpoints, a CORS answer only for the app origin, an edge 429 under burst, and no Keycloak admin route. A scratch route with no annotations makes every one of those checks fail first. core-java now trusts X-Forwarded-For only from the measured overlay pod CIDR 10.244.0.0/16, so a forged header is ignored and each client finally has its own rate-limit bucket.**

## Performance

- **Duration:** about 27 min (10:53Z to 11:20Z), with one checkpoint for the deploy decision
- **Tasks:** 3 of 3
- **Commits:** the ledger counts 21 in `36f199a7..43125fbb`. Five are this plan's (9987858b, 34ec3543, 38cd8923, 77cd9f6a, 43125fbb). The other 16 are 29-16, 29-21 and 29-29 work merged into the shared branch by the orchestrator during the plan.
- **Live estate touched:** kube-context `jtoye-stg-aks` and subscription c483d353, Azure reads via `az_read` only. Writes:
  - the scratch `fidelity-probe` objects, created and deleted by the gate three times, each deletion verified NotFound
  - one `deploy.sh` run
  - one `rollout restart deployment/core-java`

## Accomplishments

- **Task 1, `scripts/check-staging-edge.sh`:**
  - RED (9987858b): real scaffolding with stubbed arms. `--fail-arms` reported all five arms as unable to fail, and exited 1. Classifier: `RED_EVIDENCE_OK`.
  - GREEN (34ec3543): `--fail-arms` exits 0. All five arms FAIL on the probe, and the probe is gone afterwards. The normal run exits 0 (5 of 5).
  - The CSP matcher follows CSP3 path matching. The table in the evidence shows the bare realm source admitting nothing.
  - Registered in `gate-enforcement.conf` in the RED commit. `check-gate-enforcement` exits 1 with the line removed and 0 with it restored (restore checked by blob hash). It still exits 0 after 29-21's merge.
- **Task 2, trusted proxies (38cd8923, 43125fbb):**
  - **Measured:**
    - podCidr is 10.244.0.0/16 (Azure CNI overlay, Cilium), which matches `29-ESTATE-DECISIONS` POD_CIDR.
    - Traefik pods sit at 10.244.2.154 and 10.244.1.134.
    - The Service keeps ETP `Local`, and Traefik's access log shows the real client 81.96.202.37.
  - **Before:** a forged header was keyed on the Traefik pod. There were two buckets, one per replica, and the forged and the real address were both ignored.
  - **Set and deployed:** the deploy at a784e7e2 updated the ConfigMap. The running pod's env stayed `""` until the core-java restart, and read `10.244.0.0/16` after it.
  - **After:**
    - There is one bucket, keyed on 81.96.202.37.
    - 203.0.113.9 never appears in any core-java warning line, whether sent plain or followed by an in-range hop.
    - The edge gate exits 0, and digest parity exits 0.
- **Task 3, D-29(a) Observed column (77cd9f6a):** 21 of 21 rows are filled. Measured:
  - 308 redirects on all three hosts.
  - 413 at the edge for 11 MiB; 10 MiB passes.
  - About 50 requests in flight per client per Traefik replica.
  - Preflight methods, headers and max-age as configured.
  - The SSE stream held 90 s, with keep-alives arriving every 25 s.
  - Certificates Ready.

  Four annotations are recorded as "not externally observable" instead of being claimed as passing.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The burst arm could not empty the bucket on a frontend path**
- **Found during:** Task 1.
- **Measured:** on app-staging `/robots.txt`, 1500 requests took 22 s (about 68 rps), so there were 0 × 429.
- **Fix:** target api-staging `/api/v1/edge-burst-probe`. Spring Security answers 401 there before core-java's own limiter runs, so a 429 can only come from the edge. The failing run is kept in the evidence.
- **Commit:** 34ec3543

**2. [Rule 2 - Missing] Fail arms for all five checks, not only headers and CSP**
- D-29(b) asks for the CORS and 429 checks to be shown failing too. The scratch Ingress therefore also routes api-staging `/fidelity-probe` and auth-staging `/admin` to agnhost for the few seconds it exists.
- **Commit:** 9987858b / 34ec3543

**3. [Rule 3 - Blocking] A same-tag deploy does not refresh the pod's env**
- `app-config` is a plain ConfigMap, read as env at pod start.
- **Fix:** `kubectl rollout restart deployment/core-java`. The env was read back from the running pod before and after.
- **Commit:** 43125fbb

### Plan adaptations

- **Burst concurrency 40 instead of 50**, to stay under `limit-connections`. Otherwise the in-flight cap would also answer 429, and the two refusals have the same body.
- **Forged-header observable:** the DEBUG line never logs, because core-java's level is above DEBUG. The plan's fallback, the per-client bucket, was used: core-java's WARN line "Public rate limit exceeded for client X" names the resolved address.
- **Deploy at a784e7e2 with an image input changed (orchestrator option A).** 29-16's `ed07f053` (STARTTLS required) changed `application-prod.yml` and `application-staging.yml` after a784e7e2. Running core-java is one config literal behind the branch, by design: shipping it before 29-22 gives Mailpit TLS would break staging mail early. The gap closes at 29-22's deploy.

**Total deviations:** 3 auto-fixed (2 blocking, 1 missing) and 3 plan adaptations.

## Findings

- **Two rate-limit buckets before this plan.** With trust-none, core-java keyed every client on whichever Traefik replica carried it. `x-ratelimit-remaining` counted down in pairs. This is subtler than INV-12's "one bucket" warning, and gone now.
- **Edge limits are per Traefik replica.** Each replica keeps its own in-memory rate bucket and its own in-flight count. A client that reaches both replicas gets about double the configured limits. Recorded in the README rows.

## Open Items

- **The CI deploy job does not run the gate yet.** `gate-enforcement.conf` says plan 29-21 runs `check-staging-edge.sh` from the deploy job. 29-21 halted at its owner checkpoint, and no workflow references the script as of 43125fbb. The registration keeps `check-gate-enforcement` green until it is wired.
- **Residual trust, accepted and recorded in configmap-patch.yaml.** Every pod sits inside 10.244.0.0/16, so a pod that can reach core-java directly could supply X-Forwarded-For. The internet cannot: only Traefik reaches core-java, Traefik appends the real peer on the right, and the walk stops at the first hop outside the range.
- **KC_PROXY_TRUSTED_ADDRESSES (29-14's item) stays unset for Keycloak.** 29-14 recorded why: the only stable value is the same /16, which adds nothing beyond keycloak-allow's boundary. This plan sets core-java's list only.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: transient-route | scripts/check-staging-edge.sh | `--fail-arms` publishes auth-staging `/admin` and app/api `/fidelity-probe` to an agnhost pod for the length of the run. The backend is agnhost netexec (no Keycloak). The routes are deleted by name on every exit path through an EXIT trap, and their absence is verified (3 live runs, 0 survivors). |

T-29-57..60 are mitigated as planned:
- forged X-Forwarded-For checked before and after
- the headers arm has a no-snippet control route
- an unlisted origin is not echoed
- an edge 429 with a sub-limit control

## Self-Check: PASSED

- FOUND: scripts/check-staging-edge.sh, evidence/29-15-edge.txt, evidence/29-15-t1-red-stub-arms.json
- FOUND (ancestors of HEAD): 9987858b, 34ec3543, 38cd8923, 77cd9f6a, 43125fbb
- `git rev-list --count 36f199a7..43125fbb` = 21 (5 plan commits plus 16 merged from other lanes)
