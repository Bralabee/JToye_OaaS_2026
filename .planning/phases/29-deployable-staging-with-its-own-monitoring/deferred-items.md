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

- The public storefront never shows an image uploaded through the safe media pipeline (found during 29-14, 2026-10-19)
  status: open
  **What:** measured live on staging at a784e7e2: a vendor PNG became an ACTIVE 640x480 WebP
  derivative on jtoyestgmedia (renders in Chromium, naturalWidth 640), the vendor product DTO
  returns it as `imageUrl`, but `GET /api/v1/public/shops/staging-test-kitchen/products`
  returns `"imageUrl":null,"imageUrls":[]` for the same product and the storefront renders no
  <img>. `ProductService.resolveAssetFirst` (D-03a asset-first dual-read) is applied only to
  vendor DTOs; `PublicStorefrontService.toPublicProductDto` reads the flat
  `products.image_url` column, which the Phase 24 pipeline never writes. Pre-existing, not
  caused by 29-14, and it affects production the same way. 29-14's "the storefront renders it
  with naturalWidth > 0" truth is unmet until this is fixed and a new image is deployed.
  **Next:** apply the same asset-first resolution in `toPublicProductDto` (with a test that
  a pipeline-only image appears on the public menu), rebuild, redeploy, and re-run the
  storefront half of 29-14's Blob check. Passes the admission test (an observed failure).

- Staging cannot roll core-java or keycloak (found during 29-14, 2026-10-19)
  status: open
  **What:** both request 1Gi and roll with maxSurge 1 / maxUnavailable 0. Measured after the
  first deploy, node requests leave 661Mi / 399Mi / 803Mi free on the three B2s nodes
  (allocatable 2787Mi each), so the surge pod fits nowhere and stays Pending until the 10m
  progress deadline; deploy.sh then undoes it. On the first deploy the stuck rollout was
  cleared by scaling the crash-looping revision-1 ReplicaSets to 0 by name. The next image
  change to either Deployment, and 29-22's planned rolls (which rely on maxUnavailable 0),
  will hit the same wall, and 29-23/29-24 add Prometheus/Grafana requests on top.
  **Next:** an owner decision, not an executor's: a fourth node or a larger SKU (cost), lower
  requests, or maxUnavailable 1 for staging (contradicts 29-17/29-22's coupling notes).

- deploy.sh cannot go green on a fresh cluster before the realm import (found during 29-14, 2026-10-19)
  status: open
  **What:** edge-go's /ready requires the jtoye-staging realm's JWKS, which only
  `staging-realm-import.sh --apply` creates, and the import needs Keycloak, which deploy.sh
  brings up. So the first deploy.sh on an empty namespace times out on edge-go and prints no
  DEPLOYED_SHA; 29-14 ran it, imported, and re-ran it. Recorded so a rebuilt environment
  (29-26) expects two passes.
  **Next:** document the order in k8s/DEPLOYMENT.md, or let deploy.sh run the import between
  apply and the edge-go wait.

- Culinary-pack dishes are drafts, so a freshly seeded staging storefront is empty (found during 29-14, 2026-10-19)
  status: open
  **What:** `staging-seed.sh --stage vendor` seeds five dishes with available=false (the pack's
  FSA-review default), so the public menu reads "No items yet". 29-14 published one dish
  (Pelau) as the vendor through `PATCH /api/v1/products/{id}` for the Blob check; the other four
  remain drafts. 29-18's order seed needs available products.
  **Next:** 29-18 decides whether the seed publishes its dishes through the product API.

- The staging-db-bootstrap selftest runs in no CI job (noticed during 29-14, 2026-10-19)
  status: open
  **What:** `scripts/staging-db-bootstrap.sh --selftest` (now 7 arms, including the
  via-context relay and scratch-db arms 29-14 added) is not invoked by any workflow, and
  check-gate-enforcement.sh does not name it (it is not among the scripts that gate reads).
  **Next:** wire it beside the other staging selftests if a docker-capable job can host it.
