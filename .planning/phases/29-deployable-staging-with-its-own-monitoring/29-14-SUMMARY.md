---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 14
subsystem: infra
tags: [tracer, aks, deploy, keycloak, flyway, cert-manager, workload-identity, csi, blob, digest-parity, live]
status: complete

requires:
  - phase: 29-13
    provides: "jtoye-stg-kv populated (31 objects, 35 grants) and the staging secrets preflight"
  - phase: 29-11
    provides: "Traefik on 51.105.43.14, the four staging A records, both ClusterIssuers"
  - phase: 29-07
    provides: "scripts/staging-db-bootstrap.sh and infra/db/managed-pre-bootstrap.sql"
  - phase: 29-04
    provides: "scripts/staging-realm-import.sh --apply"
  - phase: 29-06
    provides: "scripts/staging-seed.sh --stage vendor"
provides:
  - "A running staging at DEPLOYED_SHA a784e7e2421c7aba164f3e7db858b1861b7df6be: a vendor signs in through Traefik at https://app-staging.olajay.co.uk and sees the seeded culinary-pack rows"
  - "Production Let's Encrypt certificates for api-, app- and auth-staging, issued with default-deny applied"
  - "staging-db-bootstrap.sh --via-context (cluster relay), --stage scratch-create|scratch-drop, and a readback that names the first migration's installer"
  - "scripts/check-deploy-digest-parity.sh (--context, --tag, --plant-stale, --selftest) wired into deploy-staging through one DEPLOY_TAG variable"
  - "Two defects of earlier plans fixed: Keycloak heap flags the JVM refused, core-java Service-link env collision"
affects: [29-15, 29-16, 29-17, 29-18, 29-20, 29-21, 29-22, 29-23, 29-24, 29-26]

actuals:
  tokens: 48900
  tasks: 4
  commits: 18
plan_head_before: e8b56ab3531a89b1bafd9e7faf7723371882f077
plan_head_after: 4db6e802214f39d70e1ddb1f2ea183c751de2d78

tech-stack:
  added: []
  patterns:
    - "Reach a firewall-pinned managed server through a digest-pinned busybox relay pod and a PID-captured kubectl port-forward; psql uses hostaddr=127.0.0.1 so verify-full still checks the real host name"
    - "Fail-arm pods are clones of the LIVE Deployment template (labels, ServiceAccount, WI, CSI volume) with one env change; no pod-template-hash, so no ReplicaSet adopts them"
    - "A scratch-database helper can only name ^jtoye_[a-z0-9]+_arm$, refused before any connection"
    - "Every parity check reads the recorded DEPLOYED_SHA from the evidence file, never HEAD"

key-files:
  created:
    - scripts/check-deploy-digest-parity.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-14-tracer.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-14-t2-red-via-context.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-14-t2-red-flyway-installer.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-14-t3-red-scratch-db.json
  modified:
    - scripts/staging-db-bootstrap.sh
    - k8s/components/keycloak/keycloak.yaml
    - k8s/base/core-java-deployment.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - k8s/LOCAL.md
    - k8s/scripts/connection-budget.conf
    - .github/workflows/ci-cd.yaml
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/deferred-items.md

key-decisions:
  - "The bootstrap reaches jtoye-stg-pg through a cluster relay (--via-context), not a second firewall rule: T-29-34's one-rule firewall stays, and no password or SQL enters the cluster"
  - "Keycloak JAVA_OPTS_KC_HEAP drops -XX:MaxHeapFreeRatio=30: below G1's MinHeapFreeRatio 40 the JVM refuses to start"
  - "core-java runs with enableServiceLinks: false: the redis and rabbitmq Services inject REDIS_PORT/RABBITMQ_PORT=tcp://... into placeholders read as ints"
  - "The first deploy's stuck rollout was cleared by scaling the crash-looping revision-1 ReplicaSets to 0 by name; the capacity behind it is an owner decision (deferred-items)"
  - "The CI deploy-staging job's tag is one job-level DEPLOY_TAG feeding both the image pin and the parity step"
  - "The parity gate keeps the SET form; AKS was measured reporting the INDEX digest, recorded for a later narrowing"

patterns-established:
  - "Fresh-cluster order: bootstrap pre -> deploy (edge-go waits on the realm) -> realm import -> deploy again for the DEPLOYED_SHA record -> bootstrap post/readback -> seed"

requirements-completed: [DPLY-01, DPLY-02]

coverage:
  - id: D1
    description: "A real staging vendor signs in through Traefik on app-staging and sees seeded rows (the tracer)"
    requirement: DPLY-01
    verification:
      - kind: e2e
        ref: "Task 2 verify 1 verbatim: STAGING_E2E=1 npx playwright test e2e/staging-login.spec.ts --project=desktop -> 1 passed, client_id=core-api, encoded staging redirect_uri, /dashboard h1 'Dashboard', PRODUCT_COUNT 5; pre-deploy RED on the missing backend (Traefik default cert)"
        status: pass
      - kind: other
        ref: "Task 2 verify 2: Deployments listed=3 off-tag=0 against the recorded DEPLOYED_SHA"
        status: pass
    human_judgment: false
  - id: D2
    description: "Managed database migrated as jtoye_app, served as jtoye_runtime over verify-full; role readback"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "readback: flyway|jtoye_app|88|t; jtoye_backup bypassrls t, jtoye_runtime f, jtoye_app f; validator 'Database username: jtoye_runtime'; jdbc ...sslmode=verify-full"
        status: pass
    human_judgment: false
  - id: D3
    description: "Each DPLY-01 claim shown able to fail (six arms between two green runs)"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "evidence 29-14-tracer.txt TASK 3: invalid_client/Client not found; Flyway 42501 as jtoye_runtime; validator refuses jtoye_app (OWNS 51); prod names NXDOMAIN aa; 0 FileNotFoundException; mail-in-readiness -> 503 Ready False; closing 0 arm pods, 0 scratch dbs, 1 passed"
        status: pass
    human_judgment: false
  - id: D4
    description: "Running pods are the images built for the recorded deployed sha"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "check-deploy-digest-parity.sh --context jtoye-stg-aks --tag a784e7e2... rc 0; --plant-stale rc 1; --selftest 9/9; gate references removed -> check-gate-enforcement rc 1"
        status: pass
    human_judgment: false
  - id: D5
    description: "Workload Identity, CSI sync and the Blob path"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "A6 token present / SA path absent; 14/15 derived Secrets (pg-backup's CronJob not run); broker restart Ready; upload -> ACTIVE 640x480 WebP, naturalWidth 640 direct; #626 LIST and quarantine refused"
        status: pass
      - kind: e2e
        ref: "storefront renders the uploaded image with naturalWidth > 0"
        status: fail
    human_judgment: true
    rationale: "The storefront half fails on a pre-existing application defect (the public menu ignores media_asset images); fixing it needs a core-java change and a new image, outside this plan's live authority. Recorded in deferred-items and WINDOWS."

duration: 85min
completed: 2026-10-19
---

# Phase 29 Plan 14: The Phase Tracer: a Vendor Signs In to Staging Through Traefik and Sees Seeded Rows

**Staging is live at DEPLOYED_SHA a784e7e2. The credentialed spec signs a Key Vault vendor in at app-staging through Traefik and production Let's Encrypt certificates, and lands on /dashboard with the five seeded culinary-pack dishes. Before the deploy the same spec failed on the missing backend. Six fail arms each went red and were restored by content, between two green runs. Digest parity matches the recorded sha in both directions. One claim is not met: the storefront does not show a pipeline-uploaded image. That is a pre-existing core-java defect, recorded rather than fixed here.**

## Performance

- **Duration:** about 85 min across both executors (Task 1 from 09:16Z, Tasks 2-4 09:36Z to 10:40Z)
- **Tasks:** 4 of 4. Task 4's storefront half is unmet; see Known Gaps.
- **Commits:** 18 counted from the plan ledger (e8b56ab3..4db6e802).
  - 17 are this plan's.
  - 9c8cdc42 (docs(29): D-33) was committed by another session sharing the checkout.
- **Live estate touched:** kube-context jtoye-stg-aks and subscription c483d353 only, as the owner approved. No Stripe call was made, and no Azure resource was created or changed. The only Azure reads were az_read calls.

## Accomplishments

- **Task 1** (prior executor, f604776c/f124932d/3d9caa82/f684f4f7): deploy.sh gained `--context`, `--image-tag`, the preflight and `DEPLOYED_SHA`. Also added: the STAGING_E2E switch and the staging login spec.
- **Task 2, the tracer** (13a57807, plus the fixes listed under Deviations):
  - **Pre-deploy RED:** the credential read succeeded, and the spec failed with `ERR_CERT_AUTHORITY_INVALID` on the Traefik default certificate (404, no route).
  - **Database bootstrap through the new relay:** pre ran, then post and readback after the first boot. 88 migrations are `installed_by jtoye_app`, all successful. Role readback: jtoye_backup t, jtoye_runtime f, jtoye_app f. `max_connections` is 429 (the budget row is unchanged; its provenance now records the re-measure).
  - **Certificates:** api-, app- and auth-staging became Ready on `letsencrypt-prod` (issuer CN=YR1, notAfter 2027-01-17) **with `default-deny` and `acme-http01-solver-allow` applied in the same apply**. This is the first real proof of the HTTP-01 allow-rule. Retiring the README section "Unproven until the first application deploy" and the memory note is the orchestrator's call. This plan did not touch them.
  - **First live runs of the realm import and the vendor seed:** tenant, vendor, shop, 5 dishes, onboarding LIVE, operator deleted.
  - **Deploy run 3** ended `DEPLOYED_SHA=a784e7e2421c7aba164f3e7db858b1861b7df6be`, and all three Deployments carry that tag.
  - **GREEN:** 1 passed, `client_id=core-api`, with the encoded staging redirect_uri. The frontend log shows `redirect_uri errors : 0`, beside a found Next.js start line.
  - **Mail and readiness:** Mailpit is NotFound, core-java is Ready, `/actuator/health/readiness` returns 200 UP, and no `MANAGEMENT_ENDPOINT_HEALTH_GROUP*` env exists. In both in-jar yml files `group` occurs 0 times, and `readinessstate:` occurs once in application-prod.yml.
- **Task 3** (8970924a), arms run in order clean → arms → clean:
  1. **invalid_client.** The spec failed on the client id, and Keycloak said "Client not found." (400). The token endpoint returned 401 `invalid_client`. The restore re-ran deploy.sh at the recorded tag, then read the running frontend's `KEYCLOAK_CLIENT_ID=core-api`.
  2. **Flyway as jtoye_runtime on a fresh database:** `42501 permission denied for schema public`. The scratch database was created and dropped with the new helper.
  3. **The owner as DB_USER:** DatabaseConfigurationValidator refused jtoye_app ("OWNS 51 table(s)") and the application run failed.
  4. **DNS:** api and app.olajay.co.uk returned NXDOMAIN with aa; the staging names resolve.
  5. **Logs:** 0 FileNotFoundException lines, beside one `Started CoreApplication` line. A planted line gives a count of 1.
  6. **Mail in readiness:** a clone with `readinessState,mail` stayed Running with 0 restarts. It showed 47 readiness 503s, never became Ready, and the EndpointSlice listed it ready=false.
  - **Closing clean:** 0 arm pods, 0 scratch databases, 1 passed.
- **Task 4** (34285950, 8208677e, 4db6e802):
  - **Parity gate:** carried from phase-29-research verbatim, then adapted. Live it returned rc 0 with 3 MATCH, and `--plant-stale` returned rc 1. AKS reports the **index** digest.
  - **CI:** deploy-staging now pins and checks one `DEPLOY_TAG`.
  - **Workload Identity (A6):** the token is present, and the serviceaccount path is absent (rc 2, No such file).
  - **CSI:** 14 of the 15 derived Secrets are present. `pg-backup-credentials` is absent because its CronJob has not run yet.
  - **A7:** the synced Secret is owned by the mounting **ReplicaSet**, so deleting the secret-sync pod never removed it (same uid). The broker restarted Ready.
  - **Blob:** an upload became an ACTIVE 640x480 WebP that renders with naturalWidth 640. Anonymous LIST and the private quarantine container are refused.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The bootstrap could not reach the managed server**
- **Found during:** Task 2. The firewall admits only the cluster outbound IP 40.120.43.119. psql from the host timed out (measured).
- **Fix:** `--via-context CTX`. The staging kube guard runs first. Then a busybox nc relay pod, pinned by digest, starts in namespace default (default-deny covers jtoye-staging). A port-forward follows, its PID captured, and psql connects with `hostaddr=127.0.0.1`.
- **What does not change:** verify-full still checks the server name, and the firewall keeps its single rule.
- **Commits:** RED 72fcb609, GREEN 0ee75912. Break arm: dropping hostaddr reds the arm, and the restore was checked by blob hash.

**2. [Rule 1 - Bug] Keycloak could not start (29-10)**
- **Cause:** `-XX:MaxHeapFreeRatio=30` is below G1's MinHeapFreeRatio of 40, so the JVM aborts.
- **Measured** with `java -version` in the image: rc 1 with the flag, rc 0 without it.
- **Fix:** 2d34c2c9.

**3. [Rule 1 - Bug] core-java crash-looped after Flyway**
- **Cause:** the `redis` Service's link variable `REDIS_PORT=tcp://10.0.166.34:6379` filled `${REDIS_PORT:6379}`, which RateLimitConfig reads as an int.
- **Fix:** `enableServiceLinks: false`, with goldens regenerated and a shifted LOCAL.md citation fixed (71088d86).

**4. [Rule 3 - Blocking] The fixed pods could not schedule**
- **Cause:** a 1Gi surge pod fit on no node.
- **Unblock:** the two crash-looping revision-1 ReplicaSets (never Ready, serving nothing) were scaled to 0 by name.
- The capacity problem underneath is an owner decision. See Open Items.

**5. [Rule 2 - Missing] The first boot's migration log was gone**
- **Cause:** restarts rotated the log away.
- **Fix:** the readback now reads `flyway_schema_history` grouped by `installed_by` (RED 2571b32d, GREEN d200b2c5).

**6. [Rule 3 - Blocking] Task 3 arm 2 needed a scratch database**
- **Fix:** `--stage scratch-create|scratch-drop --scratch-db`, restricted to `^jtoye_[a-z0-9]+_arm$`. The jtoye and keycloak names are refused before any connection (RED a0d36aed, GREEN 40af2a29).

**7. [Plan adaptation] The fail-arm clones request 600Mi of memory instead of 1Gi**
- The limit stays at 1Gi.
- With a 1Gi request, no node could schedule them.
- The arms' failure reasons (Flyway permission, validator, readiness 503) do not depend on the request.

**8. [Plan adaptation] The deploy ran three times, all at the same tag**
- Run 1 crash-looped (defects 2 and 3).
- Run 2 timed out on edge-go, whose /ready needs the realm's JWKS.
- After the realm import, run 3 went green and printed the single `DEPLOYED_SHA` record. Every `DEPLOYED_SHA` line in the evidence has the same value.

**9. [Plan adaptation] The vendor published one draft dish (Pelau)**
- Culinary-pack dishes are seeded as drafts, so the storefront said "No items yet".
- Pelau was published through `PATCH /api/v1/products/{id}` as the vendor (D-26: through the API, never SQL).

**Total deviations:** 6 auto-fixed (2 bugs, 3 blocking, 1 missing) and 3 plan adaptations. **Impact:** all were needed to run the tracer. Two of them are fixes of earlier plans that only a live deploy could expose.

## Known Gaps

- **The storefront does not render a pipeline-uploaded image (Task 4, unmet).**
  - **Symptom:** `GET /api/v1/public/shops/staging-test-kitchen/products` returns `imageUrl: null` for Pelau, although the vendor DTO carries the ACTIVE WebP.
  - **Cause:** `PublicStorefrontService.toPublicProductDto` reads the flat `products.image_url` column. The asset-first resolution (`ProductService.resolveAssetFirst`, D-03a) is applied to vendor DTOs only.
  - **Status:** pre-existing, and production is affected the same way. The fix needs a core-java change and a new image, which is beyond this plan's live authority.
  - **Recorded in:** deferred-items.md and WINDOWS (unmet-truth).

## Open Items (recorded; the owner or a later plan decides)

- **Capacity (Pitfall 2, measured):**
  - Node requests leave 661, 399 and 803 Mi free (allocatable is 2787 Mi per node). So every rolling update of core-java or keycloak will stick Pending.
  - This collides with 29-22's planned rolls (maxUnavailable 0) and 29-23/29-24's added requests.
  - The options are a fourth node or bigger SKU (cost), lower requests, or maxUnavailable 1 for staging. Owner decision; recorded in deferred-items and WINDOWS.
- **KC_PROXY_TRUSTED_ADDRESSES (29-10 item a):**
  - The Traefik pods sit at 10.244.1.134 and 10.244.2.154, inside the overlay pod CIDR 10.244.0.0/16, and change IP on every reschedule.
  - The only stable value is the whole CIDR. That adds nothing beyond keycloak-allow's boundary (namespace traefik plus three app pods on 8080).
  - Left unset, for 29-15 (trusted proxies).
- **SMTP AUTH against Mailpit (29-13 item b):**
  - `mail.smtp.auth=false`, but SMTP_USERNAME and SMTP_PASSWORD are injected.
  - Jakarta Mail then authenticates only if the server advertises AUTH. So the answer depends on Mailpit's configuration, which 29-22 writes. Not measurable here.
- **KC_HEALTH_ENABLED (29-04 item c):** resolved. It is set, and the import's readiness wait on 9000 passed live.
- **Fresh-cluster order:** deploy.sh cannot go green before the realm import, so 29-26 should expect two passes or move the import into deploy.sh.
- **Draft dishes:** four culinary-pack dishes remain unpublished. 29-18's order seed needs available products.
- **The parity step in CI fails closed until 29-21.** The shared guard needs kubelogin and the kubeconfig's server to equal the staging API server. The job is gated off by `DEPLOY_STAGING_ENABLED`.
- **The bootstrap selftest runs in no CI job.**
- **The pg-backup CronJob's mount-then-env ordering** stays [ASSUMED]. Its first run is scheduled for 02:00 UTC, and the proof belongs to 29-20.
- **Stripe:** no delivery was made. 29-18 owns the first delivery.
- **Measured once:** Keycloak working set 620Mi against its 1Gi limit, started in 13.2s. The broker started with its egress rules in place; whether it ever uses the 443 egress was not measured.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: transient-pod | scripts/staging-db-bootstrap.sh | --via-context starts a relay pod in namespace default (no NetworkPolicy). It forwards only the TLS stream, lives for one stage and is deleted by name on every exit path (0 relay pods left after each live run). It is still an unlabelled-namespace workload, so a policy for `default` would have to admit it |

T-29-52..56 are mitigated as the plan states:
- The credential was read through az_read only. Every run's output and artifacts were checked: 0 hits.
- The leak scan of the evidence found 0.
- Parity was checked in both directions against the record.
- A6 holds.
- #626 refusals are recorded raw.
- Every arm pod was deleted. The scratch database was dropped.

## Next Phase Readiness

- Expansion plans may start. The tracer is green and falsified, and staging is back in its proven state.
- 29-15, 29-18, 29-22 and 29-26 should read the Open Items above first.

## Self-Check: PASSED

- FOUND: scripts/check-deploy-digest-parity.sh, evidence/29-14-tracer.txt, evidence/29-14-t2-red-via-context.json, evidence/29-14-t2-red-flyway-installer.json, evidence/29-14-t3-red-scratch-db.json
- FOUND (ancestors of HEAD): 72fcb609, 0ee75912, 2d34c2c9, 71088d86, 2571b32d, d200b2c5, 13a57807, a0d36aed, 40af2a29, 8970924a, 34285950, 8208677e, 4db6e802
- `git rev-list --count e8b56ab3..4db6e802` = 18 (17 plan commits plus 9c8cdc42 from another session)
- evidence DEPLOYED_SHA values: one distinct, a784e7e2421c7aba164f3e7db858b1861b7df6be
