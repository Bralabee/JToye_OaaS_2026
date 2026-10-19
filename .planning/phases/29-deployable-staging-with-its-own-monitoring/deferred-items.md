# Phase 29 — Deferred Items

Out-of-scope discoveries, logged by the plan that found them (executor scope boundary:
only issues caused by the current task are fixed in it).

## Deferred Items

- check-doc-citations.sh C-3 fails on k8s/LOCAL.md:606 (found during 29-03, 2026-10-18)
  status: open
  **What:** `bash scripts/check-doc-citations.sh` (conda env jtoye-ops) exits 1 with
  `FAIL: C-3 k8s/LOCAL.md:606 cites k8s/base/configmap.yaml:177, but that line says nothing
  the claim names`. Without the env it exits 2 (VOID, PyYAML not importable). Plan 29-03
  touches neither file. Measured: the gate exits 0 at 7c687487 (the base before 29-02),
  where line 177 is `stomp.broker.mode: "relay"`; on this branch that key is at line 199,
  moved by 29-02's k8s/base/configmap.yaml edit. The gate is CI-wired
  (`.github/workflows/ci-cd.yaml` ops-contracts), so the branch's CI is red on it.
  **Next:** re-point the citation in k8s/LOCAL.md:606 to `k8s/base/configmap.yaml:199`
  (a one-line doc fix) in the next commit that may touch that doc, then re-run the gate.

- check-connection-math.sh reads the Keycloak pool from compose only (found during 29-10, 2026-10-19)
  status: open
  **What:** `k8s/scripts/check-connection-math.sh` takes the Keycloak line item from
  `docker-compose.full-stack.yml` (`KC_DB_POOL_MAX_SIZE: "20"`, extract at its line ~170)
  and uses it for the k8s targets too. Since 29-10 the staging Keycloak sets its own
  `KC_DB_POOL_MAX_SIZE` in `k8s/components/keycloak/keycloak.yaml`, so raising it there
  alone would leave the k8s budget line computed from compose's value. Both are 20 today;
  the component carries a comment saying the two change together.
  **Next:** have the gate read the value from the staging render (Deployment keycloak env)
  for the k8s-staging row and fail when it disagrees with compose, in the plan that next
  touches the connection budget (29-14 re-measures max_connections).

- validate-networkpolicies.py cannot see component NetworkPolicies (found during 29-10, 2026-10-19)
  status: open
  **What:** `k8s/scripts/validate-networkpolicies.py` (not CI-wired, measured) globs only
  `k8s/base/networkpolicies/`, so `keycloak-allow`, `redis-allow` and `rabbitmq-allow`
  (k8s/components/*) are not read at all: it prints `PASS: 7 files` with rc 0 under the
  jtoye-ops env. It does not report them as dangling; it never opens them. INV-7 in
  `check-render-invariants.sh` asserts all ten staging policies exactly and is the CI
  authority, so nothing is unasserted; the script is simply narrower than its README role.
  **Next:** either point it at the rendered staging policies or record that INV-7
  supersedes it (owner's choice; 29-10 was told to record, not edit).

- Keycloak believes X-Forwarded-* from any peer (found during 29-10, 2026-10-19)
  status: open
  **What:** `KC_PROXY_TRUSTED_ADDRESSES` is unset in `k8s/components/keycloak/keycloak.yaml`,
  so Keycloak trusts forwarded headers from every source address. `keycloak-allow` admits
  only the traefik namespace and frontend/core-java/edge-go pods on 8080, so the exposure
  is limited to those workloads.
  **Next:** set it to the Traefik pod CIDR once plan 29-14 measures it (the same input as
  Pitfall 10's `security.trusted-proxies`).
