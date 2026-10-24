---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 11
subsystem: infra
tags: [kubernetes, aks, traefik, cert-manager, rabbitmq-operator, lets-encrypt, http-01, dns, namecheap, rbac, live-bring-up]
status: complete

requires:
  - phase: 29-09
    provides: "jtoye-stg-aks (3 Ready nodes), the static ingress IP jtoye-stg-ingress-ip, k8s/staging/azure-identity.yaml (aks_api_server_fqdn, ingress_ip_address, ci_principal_object_id)"
  - phase: 29-03
    provides: "scripts/staging-bootstrap.sh stages, k8s/platform (traefik, issuers, rbac, acme-probe), sha256-pinned cert-manager v1.21.2 and RabbitMQ operator v2.23.1"
provides:
  - "Live in jtoye-stg-aks: Namespaces jtoye-staging, cert-manager, rabbitmq-system, traefik; cert-manager v1.21.2; RabbitMQ cluster operator v2.23.1; Traefik v3.7.15 (2 replicas) holding 51.105.43.14 with externalTrafficPolicy Local; IngressClass nginx (k8s.io/ingress-nginx); ClusterIssuers letsencrypt-staging and letsencrypt-prod Ready; CI RBAC bindings"
  - "Owner-created A records app-staging, api-staging, auth-staging, grafana-staging .olajay.co.uk -> 51.105.43.14, verified at dns1 and dns2.registrar-servers.com; api/app/auth/grafana still aa-NXDOMAIN (D-08)"
  - "A proven public edge: DNS -> static IP -> Traefik -> HTTP-01 -> Let's Encrypt STAGING certificate -> HTTPS to a backend in jtoye-staging; probe deleted"
  - "Measured CI in-cluster permissions (Kubernetes-RBAC half) before and after the ci-rbac stage"
  - "evidence/29-11-bootstrap.txt: every stage and read-back, ISO-8601 timestamps, both directions of every check"
affects: [29-13, 29-14, 29-17, 29-21, 29-23, 32]

actuals:
  tokens: 18200
  tasks: 3
  commits: 12
plan_head_before: 8841063d45fbcf25acf02c226792f40a6be12f17
plan_head_after: 7f37c9e4e27a8ddcd87310794de5147c939481fa

tech-stack:
  added: ["cert-manager v1.21.2 (live)", "RabbitMQ cluster operator v2.23.1 (live)", "Traefik v3.7.15 with the Kubernetes Ingress-NGINX provider (live)", "Let's Encrypt staging and production ClusterIssuers (HTTP-01 via ingressClassName nginx)"]
  patterns:
    - "Prove HTTP-01 with the STAGING issuer and a throwaway probe before any production-issuer certificate is requested"
    - "Verify a staging certificate by content: curl --cacert the downloaded staging root must pass, and the system store and the wrong staging root must both refuse"
    - "DNS is verified at the authoritative servers (aa flag), with the production names' aa-NXDOMAIN recorded beside the staging answers"

key-files:
  created:
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-11-bootstrap.txt
  modified: []

key-decisions:
  - "The Azure-RBAC half of the CI principal's in-cluster reach is deferred to plan 29-21's real CI run: impersonating with Extra:oid is refused for the operator (cannot impersonate userextras/oid); the Kubernetes-RBAC half was measured before and after ci-rbac"
  - "Network Contributor on jtoye-stg-outbound-ip was granted to the AKS control-plane identity (owner-approved 2026-10-19) so Traefik's LoadBalancer could bind the static IP"
  - "The no-DNS fail direction stops at cert-manager's own self-check (no such host), so nothing is submitted to Let's Encrypt for validation; that is the expected behaviour and costs no rate-limit budget"

patterns-established:
  - "An evidence helper that appends a timestamped header, combined output and rc, and exits with the command's rc (ev.sh); a pipe inside it masks the rc, so capture into a variable and exit with it"

requirements-completed: [DPLY-01, DPLY-02]

coverage:
  - id: D1
    description: "Platform applied and read back: cert-manager, operator and Traefik Available; Traefik on 51.105.43.14 (equal to Azure and the identity file), policy Local; both ClusterIssuers Ready"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "evidence/29-11-bootstrap.txt: TASK 1 VERIFY 1/2 (as written), EQUALITY and its fail direction (203.0.113.10 -> rc 1), issuer fail direction"
        status: pass
    human_judgment: false
  - id: D2
    description: "Four staging hostnames resolve to the ingress IP at both authoritative servers; production names aa-NXDOMAIN"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "evidence/29-11-bootstrap.txt: TASK 2 VERIFICATION after the owner's 'dns added' (rc 0) and its fail direction (expected IP 51.105.43.15 -> rc 1)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Let's Encrypt STAGING certificate issued end to end for app-staging over HTTP-01 through Traefik; no-DNS probe never Ready; probe deleted (six objects NotFound)"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "evidence/29-11-bootstrap.txt: TASK 3 plan verify #1 (True), verified curl + controls A/B/C, no-DNS 620 s watch, plan verify #2 positive control (gate rc 1) and after delete (gate rc 0)"
        status: pass
    human_judgment: false
  - id: D4
    description: "CI principal's in-cluster permissions measured before and after ci-rbac"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "evidence/29-11-bootstrap.txt: BEFORE/AFTER ci-rbac can-i blocks and the operator's positive control"
        status: pass
    human_judgment: false
    rationale: "Kubernetes-RBAC half measured here; the Azure-RBAC half needs the real jtoye-ci token and is deferred to plan 29-21"

duration: 73min
completed: 2026-10-19
---

# Phase 29 Plan 11: The Staging Edge Brought Up for Real Summary

**Traefik holds 51.105.43.14 in jtoye-stg-aks and the four staging hostnames now resolve to it. A Let's Encrypt STAGING certificate for app-staging was issued over HTTP-01 in about 26 s, and a curl verified against the staging root got the backend's answer. A host with no DNS record never became Ready, and the probe has been deleted.**

## Performance

- **Duration:** 73 min wall clock (06:11:36Z to 07:24:54Z on 2026-10-19). About 27 min of that was the wait for the owner's DNS records.
- **Started:** 2026-10-19T06:11:36Z
- **Completed:** 2026-10-19T07:24:54Z
- **Tasks:** 3/3 (Task 1 by the first executor; Task 2 verification and Task 3 by this continuation)
- **Files modified:** 1 (the evidence file)

## Accomplishments

- **Platform live (Task 1):** namespace, cert-manager v1.21.2 and RabbitMQ operator v2.23.1, both sha256-verified. A one-byte-corrupted manifest made the stage refuse `[digest]` and apply nothing. Traefik v3.7.15 binds the static IP with policy Local. Both ClusterIssuers are Ready and the CI RBAC is applied. CI can-i before ci-rbac: no, no, no. After: yes, yes, and still no for creating ClusterRoles. Out-of-scope controls are no.
- **DNS (Task 2):** after the owner replied "dns added", dns1 and dns2 both return app-staging, api-staging, auth-staging and grafana-staging → 51.105.43.14 with the aa flag. `api`, `app`, `auth` and `grafana` stay aa-NXDOMAIN (D-08). The mistyped host `grafana-stating` resolves nowhere. 1.1.1.1 and 8.8.8.8 already agree, so no public-resolver lag was seen. The gate passes against the real IP (rc 0) and fails against an IP one octet off (rc 1).
- **ACME probe (Task 3):**
  - Timeline: Ingress at 07:11:47Z, DomainVerified at 07:12:09Z, CertificateIssued at 07:12:13Z.
  - Certificate: leaf CN=app-staging.olajay.co.uk, issuer "(STAGING) Ersatz Emmer YR2". The chain runs to "(STAGING) Yonder Yam Root YR", cross-signed by "(STAGING) Pretend Pear X1".
  - Verified curl: `--cacert` set to the downloaded X1 root returned HTTP/2 200 with `NOW: 2026-10-19 07:12:44…`.
  - Controls that must fail did fail: the system trust store gives rc 60 (verify 20) and the X2 root gives rc 60. Plain HTTP on port 80 answers with a 308 redirect to HTTPS.
- **Fail direction:** `probe-nodns-staging` (aa-NXDOMAIN at both servers), on the same issuer, stayed `Ready=False/DoesNotExist` for 620 s across 11 samples. Its Challenge stayed pending with reason "self check … lookup … no such host". After deletion, the read-back found 0 such objects, beside a control count of 6 from the same read.
- **Delete:** the positive control ran first and printed all six names (kubectl rc 0, gate rc 1). After `--stage acme-probe-delete`: kubectl rc 1, empty stdout, `notfound=6 other=0`, gate rc 0. Afterwards jtoye-staging holds no objects of the probed kinds, and the Task 1 platform is unchanged.

## Task Commits

1. **Task 1: Apply the platform and read it back** - `8eae544d` (feat)
2. **Task 2: Owner adds the four staging A records** - `56aeee34` (docs, measurement before the records existed: absent), `3dee1e36` (docs, verification after "dns added")
3. **Task 3: ACME probe, no-DNS fail direction, delete** - `7f37c9e4` (docs)

**Plan metadata:** recorded in the SUMMARY commit that follows.

The commit count is the measured `git rev-list --count 8841063d..HEAD` = 12. Plan 29-12 ran while this plan waited at the DNS checkpoint, so its 8 commits fall inside that range. Four of the 12 are this plan's own.

## Files Created/Modified

- `.planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-11-bootstrap.txt`: raw, timestamped output of every stage, read-back and control, for all three tasks (825 lines). It contains no secret values. The ACME challenge token it records is public by design.

No repository code changed in this plan. The live changes are the cluster objects listed under `provides`, plus one Azure role assignment from Task 1, which the owner approved.

## Decisions Made

See `key-decisions` in the frontmatter. None of them changes the plan's design.

## Deviations from Plan

**1. [Rule 3 - Blocking, Task 1, prior executor] Network Contributor on jtoye-stg-outbound-ip for the AKS control-plane identity.** Without it, Traefik's LoadBalancer could not bind the static IP. The owner approved it, and it is recorded in the Task 1 evidence (AZURE READ before, AZURE MUTATION, read-back). Commit `8eae544d`.

**2. [Measurement scope, Task 1] Only half of the CI principal's can-i could be measured.** Azure's webhook answers "no opinion" for `--as <oid>` alone, so only the Kubernetes-RBAC half could be measured. The operator is refused `--as-user-extra oid=…`. As the plan allows, the Azure-RBAC half is deferred to plan 29-21, with the refusal recorded verbatim.

**3. [Instrument, Task 2] The first fail-direction record shows rc=0.** That record piped the script into `tail`, which masked the script's status. The arm was re-run with the status captured directly and recorded rc=1. Both records stay in the evidence, with the cause written down.

**4. [Ordering note, Task 3] One evidence block is out of time order.** The acme-probe stage block appears after the 07:12:07Z in-flight Challenge record, because the helper appends when the command ends. The timestamps are authoritative.

**Total deviations:** 1 owner-approved live change, 1 deferred measurement, 2 evidence-instrument notes. **Impact:** none on the must-haves.

## Issues Encountered

- At 07:12:07Z cert-manager's in-cluster self-check briefly got a 404 (the solver route was not yet in Traefik). It passed by 07:12:09Z, with no action needed.
- The CI principal's full in-cluster reach (the Azure RBAC half) is still open for plan 29-21. That plan also has to confirm that the federated jtoye-ci token's Kubernetes user name is its object id, which the RBAC binding subjects assume.

## Observations for Later Plans

- **jtoye-staging has no NetworkPolicy yet.** `k8s/base`, with `00-default-deny` and `60-acme-http01-solver`, arrives with the application deploy. So this probe proved HTTP-01 without the default-deny in place. The solver allow-rule is first exercised when the overlay deploys. If certificates then time out with a challenge failure, look at that rule before suspecting DNS.
- Traefik redirects HTTP to HTTPS with a 308 on the `web` entrypoint. HTTP-01 still validated, so the redirect does not block the solver path.
- No production-issuer certificate was requested. Production-issuer requests are now safe to make for the staging hosts.

## User Setup Required

Done by the owner on 2026-10-19: the four Namecheap A records (TTL 300) pointing at 51.105.43.14. No further setup is needed.

## Next Phase Readiness

The edge is live and proven. Later plans can put real hosts on letsencrypt-prod. The static IP, DNS and Traefik routing are settled for app-staging, api-staging, auth-staging and grafana-staging.

## Self-Check: PASSED

- FOUND: evidence/29-11-bootstrap.txt (contains `dns1.registrar-servers.com`)
- FOUND commits: 8eae544d, 56aeee34, 3dee1e36, 7f37c9e4 (each an ancestor of HEAD)
- Live read-back at 07:24:14Z: issuers Ready, Traefik 51.105.43.14 Local, no probe residue
