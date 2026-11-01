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
  status: resolved (#979) — `k8s/scripts/check-connection-math.sh` now extracts `KC_DB_POOL_MAX_SIZE` from `k8s/components/keycloak/keycloak.yaml` as well as `docker-compose.full-stack.yml`, enforces a drift guard between the two, and exercises `keycloak-pool-drift` in `--selftest`
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
  status: resolved (#979) — `k8s/scripts/validate-networkpolicies.py` now validates `k8s/base/networkpolicies/*.yaml`, `k8s/components/**/*.yaml` carrying `kind: NetworkPolicy`, and `k8s/goldens/*.yaml` (18 files, 41 NetworkPolicy docs) with `--selftest`
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
  status: resolved by 29-29 (`40ceffbc`, deployed `6e95ee6c`): the anonymous staging menu now serves Pelau's ACTIVE pipeline derivative (asset 14add455, the vendor's 29-22 replacement of 1475a89c) as imageUrl and imageUrls[0], and the storefront renders it at naturalWidth 640 on mobile and desktop with a matching Product JSON-LD image (evidence/29-29-storefront-image.txt)
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

- `.env.example` cites `docker-compose.monitoring.yml:118` for the postgres-exporter sslmode default (found during 29-24, 2026-10-19)
  status: open
  **What:** line 118 was already a Mailhog comment BEFORE 29-24 (measured at 1640fb7b); the
  sslmode default sits in the postgres-exporter DATA_SOURCE_NAME. `.env.example` is not in
  check-doc-citations.sh's default set, so no gate sees it. 29-24 shifted the file by 10 lines
  and fixed the three citations that gates DO check (two horizon sites, TS-13).
  **Next:** cite by subject (service name) rather than line, or add `.env.example` to the set.

- The local compose Alertmanager runs prom/alertmanager:v0.27.0 while compose pins v0.34.1 (found during 29-24, 2026-10-19)
  status: open
  **What:** `docker inspect jtoye-alertmanager` reports image v0.27.0, and its env differs from the
  current `.env` (its own env renders sha256 97a00e0a; the current `.env` renders 51f026d4). A
  stale LOCAL runtime: `scripts/check-runtime-freshness.sh` is the gate for it.
  **Next:** recreate the monitoring stack locally (`docker compose -f infra/monitoring/docker-compose.monitoring.yml up -d --force-recreate alertmanager`).

- The STOMP dashboard's panels name a datasource uid nobody provisions (found during 29-24, 2026-10-19)
  status: open
  **What:** `infra/monitoring/grafana/dashboards/stomp-dashboard.json` references
  `{"type":"prometheus","uid":"prometheus"}` in every panel, while
  `provisioning/datasources/prometheus.yml` sets no `uid`, so Grafana assigns a random one.
  Measured on grafana/grafana:13.2.4: `/api/ds/query` with uid `prometheus` answers
  `{"message":"Data source not found"}`, so every panel is empty in compose today and in
  staging (29-24 serves byte copies). Pre-existing; not caused by 29-24.
  **Trap for the fix:** adding `uid: prometheus` to the datasource is right on a FRESH store
  (staging: emptyDir) but on an EXISTING store (compose's `grafana_data`) Grafana exits 1 at
  start: `Datasource provisioning error: data source not found` (measured: random uid first,
  then the uid file on the same volume). The fix must carry `deleteDatasources` for the old
  entry, or change the dashboard side, and be proven on both a fresh and an existing store.
  **Next:** a small plan (or 29-27's runbook work, which says "read Grafana") fixes the
  canonical files and re-copies them.

- deploy.sh certifies a deploy without waiting on the monitoring Deployments (found during 29-25, 2026-10-19)
  status: open
  **What:** `scripts/deploy.sh` runs `rollout status` only for core-java, edge-go and frontend
  (the three retagged images). On 2026-10-19T17:34Z it printed `DEPLOYED_SHA=6e95ee6c…` while
  the new alertmanager and grafana pods were still ContainerCreating; they became Ready about
  a minute later. A monitoring Deployment that never becomes Ready would not stop the deploy.
  **Next:** 29-27 (runbook) or a small plan: wait on every Deployment in the render, or at
  least the monitoring ones, and keep `rollout undo` scoped to what was retagged.

- Neither staging-secrets selftest runs in CI (found during 29-25, 2026-10-19)
  status: open
  **What:** `scripts/staging-secrets-preflight.sh --selftest` was 8/16 on 4945dc00 (29-24's
  @-validator kinds read as literal prefixes; fixed in 2382de1e) and nobody saw it, because no
  workflow runs it; `scripts/staging-secrets.sh --selftest` is likewise unwired (noted at 29-22).
  Both need kubectl + python3/PyYAML only, no Azure.
  **Next:** wire both into the ops-contracts job; check-gate-enforcement.sh then guards them.

- The frontend has no Prometheus scrape job in either runtime (found during 29-25, 2026-10-19)
  status: open
  **What:** #98 criterion 1 asks that Prometheus "scrapes all three services". Live staging
  targets: core-java and edge-go up per pod; the frontend (Next.js) exposes no `/metrics` and
  has no job in `infra/monitoring/prometheus/prometheus.yml.tmpl` or its k8s copy.
  **Next:** 29-27 decides #98's disposition: either the "three services" are core-java,
  edge-go and the gateway path already scraped, or a frontend metrics job is a new item.

- An env-literal SMTP password crashes Alertmanager in Kubernetes (found during 29-25, 2026-10-19)
  status: open (by design, recorded)
  **What:** the entrypoint writes `ALERTMANAGER_SMTP_PASSWORD` to `/tmp`, which the staging pod
  does not have (read-only root, no /tmp emptyDir): FATAL, CrashLoopBackOff. Staging uses only
  `ALERTMANAGER_SMTP_PASSWORD_FILE` from the CSI mount, so this fails closed and is harmless,
  but an operator who sets the env literal during an incident takes Alertmanager down.
  **Next:** note it in 29-27's runbook (rotate through Key Vault, never through env).

## Filed as issues 2026-10-20 (owner: "any and all issues should be filed")

Every open item above now has a tracker entry; this file stays the narrative, the issues are the work.

| Item | Issue |
| --- | --- |
| Grafana OOMKilled twice on staging (found at housekeeping) | #968 |
| deploy.sh does not wait on the monitoring Deployments | #969 |
| Staging selftests in no CI job (secrets, preflight, db-bootstrap) | #970 |
| Frontend has no Prometheus scrape job (#98 criterion 1) | #971 |
| Mailpit certificate read only at start (breaks about 2027-01-17) | #972 |
| Order counter vanishes on core-java restart | #973 |
| STOMP dashboard datasource uid | #974 |
| Edge rate limits per Traefik replica | #975 |
| Keycloak trusts X-Forwarded-* from any peer | #976 |
| Truststore init image outside digest parity | #977 |
| deploy.sh two passes around the realm import | #978 |
| Small gate and doc corrections (checklist) | #979 |
| FRONTEND_PUBLIC_API_URL points at staging | #980 |
| Order-state emails lack List-Unsubscribe (follow-up to #592) | #981 |
| #112 remainder (SLOs, alerts.md stubs) | #982 |
| Production DB HA, assumption A11 (remainder of #101) | #983 |
| dotfiles: uncommitted review-repos.txt edit, settings.json drift (machine session) | Bralabee/dotfiles#334 |

Resolved, no issue: storefront image (29-29), core-java/keycloak could not roll (D-34, 4th node), check-doc-citations C-3 (gate exits 0, 2026-10-20).
Recorded in 29-27 Task 2's runbook, no issue: an env-literal SMTP password crashes Alertmanager in k8s (rotate through Key Vault).

## Phase 32 inputs this phase produced (plan 29-27, 2026-10-20)

Everything below was decided or measured in Phase 29 and is deliberately NOT done for production here.
Phase 32 inherits it; none of it is a defect in staging.

- **Production opts into the components.** Keycloak, Redis, RabbitMQ, Mailpit (staging only: production
  needs a real mail path), the monitoring stack, the Key Vault chain and the cluster-CA truststore live
  under `k8s/components/` and are listed by `k8s/staging/kustomization.yaml` only. `k8s/production` and
  `k8s/local` list none of them, on purpose (production decides in Phase 32).
- **Production connection budget at the reserved 15.** At Azure's 15 reserved connections, production at
  `max_connections` 200 gives a budget of 148 against a demand of 155. Phase 32 must size
  `max_connections` at 209 or more, or lower the pool, and change the `k8s-production` row of
  `k8s/scripts/connection-budget.conf` to its measured value (plan 29-05's counterfactual line prints
  this on every run of `check-connection-math.sh`).
- **Per-environment frontend images (#292).** The staging origin is baked into the frontend image at
  build time. A production image needs its own build; until then `FRONTEND_PUBLIC_API_URL` must not feed a
  production build (#980).
- **SES for production mail (#294, D-13).** The SES half of #294 moves to Phase 32; the bucket half was
  closed by the #626 read-back (plan 29-14).
- **Database high availability (the #101 half, #983).** Staging is Burstable, which has no zone-redundant
  HA. The production SKU decides it (assumption A11).
- **mcp-server (#301).** It needs a pool in the connection budget, a scoped client and a public-exposure
  decision before it has a manifest set.
- **stomp-relay e2e rework (#304).** A design task: an auth helper, the order path, a wait strategy and an
  assert-ran check. Unblocked by the in-cluster broker; off the DPLY-01 path.
- **#98 criterion 3.** Log aggregation, single emission and edge-to-core traces were outside D-16's
  monitoring scope. The frontend scrape job (criterion 1) is #971. #98 itself stays open for criterion 3.
- **#112 remainder (#982).** SLOs and the five TODO first-response stubs in `docs/runbooks/alerts.md`.
- **Traefik-namespace egress policy.** The `traefik` namespace's egress is deliberately left open (it must
  reach the API server and every backend); the backends' own policies bound what it may reach (plan 29-03).
  A tighter egress policy for that namespace is a production hardening decision.
- **Production trusted proxies.** Staging's `security.trusted-proxies` is the whole overlay pod CIDR,
  `10.244.0.0/16`, measured in plan 29-15 because Traefik pods move on reschedule and the node
  `podCIDR` is not populated under Azure CNI overlay. Production must measure its own, and
  `KC_PROXY_TRUSTED_ADDRESSES` for Keycloak is still unset (#976).
- **Grafana edge throttling (R-3, production).** On staging the owner accepted R-3 on 2026-10-21: failed basic-auth guesses on `grafana-public` are not rate-limited, because the Ingress-NGINX provider orders basic auth before `limit-rps`. The acceptance is staging-only and holds only while the credential is machine-generated (`29-SECURITY.md`, Accepted Risks Log). Production does NOT serve `grafana-public` today: `k8s/production` lists only `../base` (`kubectl kustomize k8s/production` renders 0 `grafana-public`, while staging renders 1). Production opts into the components, `../components/monitoring` included, on purpose in Phase 32 (`k8s/staging/kustomization.yaml:17-19`). That opt-in brings this route with its basic-auth-before-`limit-rps` chain, so Phase 32 must rule on R-3 for production when it adds the component. The two choices are to accept it on the same conditions, or to move the route to a Traefik-native Middleware chain (a rate limit, then basic auth) and update INV-21.
- **Key Vault for production (D-23 repeats).** Production's secrets path is not Sealed Secrets by default
  any more: D-23's Key Vault + CSI chain, its write-once writer and its preflight are the staging pattern,
  and Phase 32 repeats the decision for its own vault, identities and grants.
