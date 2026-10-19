# Phase 29: Deployable Staging, With Its Own Monitoring - Context

**Gathered:** 2026-10-18 (re-planned on `main`; supersedes the 2026-08-10 context on `phase-29-research`)
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase delivers **the first runtime of this platform outside a laptop**: a staging
environment a human can log into, which **alerts a human when it breaks**.

- **DPLY-01:** a real Keycloak vendor login **through the ingress** to a rendered dashboard,
  on a resolvable public hostname, with real seeded rows. Phase 26-08's L7 proof (one verbatim
  URL carrying `client_id` and an encoded `redirect_uri`, landing on a rendered dashboard) is
  the template.
- **DPLY-02:** the deploy knot closed, or each issue deferred **with a written reason**: #99, #100,
  #292, #293, #299, #301, #302, #271, plus #300, #304, #592 and the DPLY-01/03-mapped #294, #98,
  #112.
- **DPLY-03:** Prometheus, Alertmanager and Grafana **running in k8s**, with
  `check-alert-liveness.sh` and `check-alert-metrics.sh` exiting 0 **against staging**.
- **DPLY-04:** PITR per #101, restore proven **by row count on a restored database, two-arm**.
  ADR-0002 is Accepted on main (#953, #954), so this is no longer blocked.
- **DPLY-05:** NetworkPolicies **enforced, not merely rendered** (#297), with a denied
  connection captured. Phase 26 recorded enforcement NOT PROVEN, and that must not be
  inherited as a pass.

**How this context relates to the branch.** The phase was first discussed on 2026-08-10, and
9/16 plans executed on `phase-29-research`. None of that code is on main. The 2026-10-17 salvage
audit gave 23 non-`.planning` conflicts, every image pin behind main, and plans 29-11..16 written
against a retired object store. Its verdict: **re-plan on main**. Keep the branch as the archive
(never delete it) and carry named scripts by hand (see `<code_context>`). Decisions keep their
August numbers so the branch's evidence still cross-references; new decisions start at D-20.

**Out of scope:** production cutover, production DNS and anything that makes production
hostnames resolve (Phase 32); the money-path proofs themselves (Phase 30, PAY-01..04; this
phase only makes staging *able* to carry them, D-28); SLOs and the full runbook set (D-18);
real app-email delivery and SES (D-13).

</domain>

<decisions>
## Implementation Decisions

### Carried from 2026-08-10, still binding
- **D-01: AKS in `jtoye-rg`** on the owner's subscription `c483d353-5f61-4587-a790-addb9ab5fb94`
  ("JToye Digital Production"). Network-policy enforcement is enabled at cluster creation, so
  DPLY-05's enforcing CNI comes from the platform. **New since August:** the August cluster is
  being deleted (owner ruling 2026-10-18), so this phase provisions a FRESH cluster.
  `az aks create` must carry `--enable-oidc-issuer` AND `--enable-workload-identity` (the August
  script had only the first; `36-PHASE29-HANDOFF.md` §3). Every `az` call pins `--subscription`.
  The CLI default is the employer's (HS2) subscription, which must never be touched.
- **D-02: Keycloak runs in-cluster**, realm imported from
  `infra/keycloak/realm-export.template.json` (now Keycloak 26.8.1), backed by its own database
  on the managed PostgreSQL server. #296's in-cluster hardening conditions are live. Phase 37's
  `serviceAccountsEnabled: false` on `core-api` must be present in the realm the cluster imports.
- **D-03: ~£150/month estate ceiling, staging always on.** "Alerts a human" means nothing for a
  cluster that is off. **New pressure to account for:** `snackpass-pg` in the same subscription
  leaves its free tier on 2026-11-08 and then bills compute each time it auto-restarts. Planning
  states the measured estate cost against the ceiling, not an estimate.
- **D-04: CI deploys with a real gate.** GitHub Actions authenticates to Azure through an OIDC
  federated credential (no long-lived kubeconfig), and the deploy job runs kustomize against AKS
  on merge to main. #99 closes for real. First bring-up may be manual; done includes a green CI
  deploy. The GitHub `staging` environment does not exist today and must be created. The
  orphaned `jtoye-ci` identity is deleted with the old estate, so this phase creates its own.
- **D-05: `olajay.co.uk` is the platform domain.** The zone is hosted at Netlify DNS (NS1);
  `app-/api-/auth-staging` are NXDOMAIN today (measured 2026-10-18). Re-measure before relying
  on it.
- **D-06: flat `-staging.*` host shape:** `api-staging`, `app-staging`, `auth-staging` and
  `grafana-staging` `.olajay.co.uk`.
- **D-07: one Azure static public IP for the ingress controller.** DNS records are added by
  hand once at Netlify; certificates are per-host Let's Encrypt HTTP-01 via cert-manager. No
  external-dns and no zone migration.
- **D-08: staging records ONLY.** Production hosts stay unresolvable until Phase 32. CI flips
  `DEPLOY_STAGING_ENABLED` and sets staging `FRONTEND_PUBLIC_*`; production flags stay off.
- **D-09: ADR-0002 hybrid, Accepted** (signed 2026-08-10, recorded on main 2026-10-18 by #953
  and #954). PostgreSQL goes to Azure Database for PostgreSQL Flexible Server; Redis goes per
  D-24; RabbitMQ runs in-cluster via the RabbitMQ cluster operator, with the broker version
  declared in-repo. The `rabbitmq-k8s` horizon row then moves to that pin and its
  `manual_review` retires (runbook step 4). — **Reversibility:** costly — the datastore shape
  determines the connection strings, NetworkPolicies, secrets and backup path of every workload.
- **D-10: DPLY-04 proof shape.** Provider PITR restore to a new server, plus a row-count
  comparison. The `pg-backup-cronjob` logical dump stays as the provider-independent second line.
  Arm A runs through the logical-dump path. Precisely, the behaviour it must catch (corrected on
  main by #954): a plain `pg_dump` by a role that is neither owner nor BYPASSRLS **errors** on an
  RLS-enabled table, while `--enable-row-security` gives a **plausible partial** dump (published
  storefront rows and RLS-exempt or RLS-free tables only). The drill must prove the pipeline's
  own size and `pg_restore --list` checks pass such a dump, and that the row-count check does
  not. The BYPASSRLS `jtoye_backup` role and the Phase 28 owner/runtime role split apply to the
  managed server.
- **D-13: app outbound email in staging is captured, never delivered.** It is **AMENDED by
  D-20** (Mailpit with required STARTTLS replaces Mailhog). The SES half of #294 defers to
  Phase 32 with that reason recorded. #294's OTHER half (the `jtoye-images` S3 bucket in
  eu-west-2) is closed by the Phase 36 supersession. Its replacement proof is the #626 read-back
  against `jtoyestgmedia` (`36-PHASE29-HANDOFF.md` §4), recorded against #294. Alert email
  (D-17) is real and separate.
- **D-16: plain kustomize, no Helm.** Hand-written Deployments for Prometheus, Alertmanager,
  Grafana and the exporters under `k8s/`, mounting the SAME rule files and dashboards as
  `infra/monitoring/`. One rule corpus, no PrometheusRule CRD. Exporters point at the managed
  endpoints.
- **D-17: "alerts a human" means real email via Gmail SMTP** (app password) to the owner's
  inbox. The credential lives in the D-23 store.
- **D-18: #112 is closed minimally:** a paging path plus ONE honest runbook page (reach the
  cluster, read Grafana, restart a workload, run the restore drill). SLOs and the full runbook
  set defer post-GTM, with the reason recorded.
- **D-19: only Grafana gets a public hostname** (own login; admin credential in the D-23 store).
  Prometheus and Alertmanager stay cluster-internal.

### Superseded (kept for cross-reference only)
- **D-11 / D-12 (AWS S3 media and backup buckets): SUPERSEDED by Phase 36** (ruling
  2026-09-28). Media goes to Azure Blob `jtoyestgmedia` (uksouth, raw Blob endpoint, `jtoye-images`
  public at `blob` level, `jtoye-quarantine` private); backups go to `jtoyestgbackup` (ukwest),
  container `jtoye-db-backups`, WORM plus soft delete; both through **Workload Identity**, with no
  keys anywhere. The accepted trade: it survives object deletion and app-credential compromise,
  not loss of the whole subscription. Follow `docs/runbooks/azure-blob-provisioning.md` in order.
- **The PG16 pin: SUPERSEDED.** It is a floor, not a pin (D-25).

### Staging email and the STARTTLS ruling (new)
- **D-20: staging app mail goes to in-cluster Mailpit with STARTTLS REQUIRED.** It serves a
  certificate from a cert-manager internal CA. Mailhog remains only in local compose. This
  resolves the conflict between D-13 (capture, never deliver) and the owner ruling of 2026-10-18
  (STARTTLS required in staging and production): staging exercises the exact required-TLS code
  path production will. **Mechanism:** `spring.mail.properties.mail.smtp.starttls.required: true`
  (and `.enable: true`) is written as a LITERAL in `application-staging.yml` and
  `application-prod.yml`, NOT as an env-defaulted key. A missing configmap entry must not be
  able to fall back to opportunistic TLS, the silent class #952 fixed. A test in the
  `MailStartTlsPropertyTest` shape asserts the sender's JavaMail properties under each profile,
  RED first. #952 fixed `.enable`; this phase adds `.required`. Dev and nightly stay
  opportunistic.
- **D-21: core-java trusts the cluster CA.** The cert-manager CA is mounted into
  the JVM truststore with full chain validation. `mail.smtp.ssl.trust` host-pinning is
  REJECTED: it would skip validation and weaken what "required TLS" proves.
- **D-22: the proof is two-arm.** With TLS on, a real onboarding email lands in Mailpit (read
  back by content). In the break arm, staging points at a non-TLS SMTP endpoint and the send
  must FAIL, with the exception recorded. A config-only unit test is not sufficient on its own.

### Where secrets live (new; closes #100 and #300)
- **D-23: Azure Key Vault, one per environment, is the source of truth.** Pods read it through
  the Secrets Store CSI driver, authenticated by Workload Identity (the mechanism Phase 36
  already uses for Blob). Secrets survive cluster deletion, nothing secret is committed, and
  production repeats the pattern. Sealed Secrets and plain env-file Secrets are rejected:
  2026-10-18 showed that a key held only inside a cluster dies with it. The render invariants
  (no `kind: Secret` in any build; INV-9 no storage Secret) stay green.
  - **Every workload that reads a secret** (core-java, frontend, edge-go if any, keycloak,
    pg-backup, rabbitmq, monitoring, Mailpit) gets its own Workload-Identity ServiceAccount, the
    `azure.workload.identity/use` pod label, and a SecretProviderClass volume. The CSI driver's
    `secretObjects` sync creates the k8s Secret that `secretKeyRef` env vars expect ONLY while a
    pod mounts it, so a workload without the mount fails `CreateContainerConfigError`, or, for an
    `optional: true` ref, starts silently unconfigured. Each identity reads only its own
    secrets. Today only core-java and pg-backup carry the WI label
    (`k8s/staging/workload-identity-patch.yaml`). — **Reversibility:**
  costly — every workload's secret mount, the provisioning script and the rotation runbook are
  built on it.
  - **The name list is DERIVED, never hand-enumerated.** The generator and the preflight both
    read every `secretKeyRef` (Secret name + key) from the RENDERED staging build, plus the
    monitoring and Keycloak manifests this phase adds. A hand-written list can omit a name, and
    then the preflight passes while a pod fails at runtime. Measured on main 2026-10-18: 23 keys
    across `client-ip-credentials`, `dsar-credentials`, `keycloak-credentials` (admin-username,
    admin-password, frontend-client-secret), `nextauth-secret`, `notification-credentials`,
    `postgres-credentials` (incl. backup-username/backup-password for `jtoye_backup`),
    `rabbitmq-credentials` (incl. stomp-login/stomp-passcode), `redis-credentials`,
    `smtp-credentials` and `stripe-credentials`. New this phase: the Keycloak database password,
    Grafana admin, and the Alertmanager SMTP values. The derivation is shown failing against a
    planted reference to a name the store lacks.
  - **Machine-made values are generated by a write-once script** that never prints them: every
    derived name except the external ones below (passwords, the NextAuth secret, the Keycloak
    client secret, STOMP credentials, `DSAR_ENCRYPTION_KEY`, `CLIENT_IP_HMAC_KEY`, the
    unsubscribe signing secret, and the rest). It REFUSES to overwrite an existing
    value: re-keying `DSAR_ENCRYPTION_KEY` would make every open request's ciphertext
    unreadable. Rotation goes only through `docs/runbooks/credential-rotation.md`.
  - **External values are supplied once by the owner** with one documented command each:
    Stripe TEST keys and webhook secret, and the Gmail app password with its from/to addresses.
    The deploy preflight lists every missing name and refuses to deploy, rather than starting
    half-configured.

### Redis (new; ADR-0002 follow-through)
- **D-24: Azure Managed Redis, smallest tier, IF research measures it inside the D-03 ceiling.
  Otherwise in-cluster Redis**, recorded as a dated ADR-0002 amendment. "Azure Cache Basic" is
  impossible: Microsoft blocked creation of new Azure Cache for Redis Basic/Standard/Premium for
  existing customers from **2026-10-01** (retirement 2028-09-30). If in-cluster, it is a single
  pod with **no persistence** (cache-only, rebuildable; an alert fires when it is down). The
  `REDIS_SSL` switch carried from the branch (29-02) applies to whichever endpoint is chosen.

### What staging carries (new)
- **D-26: seeded rows come from REAL flows, scripted.** A re-runnable script drives the product
  paths against staging: vendor sign-up and onboarding, then the 1-click culinary pack
  (`GET /api/v1/products/culinary-packs`, `POST /api/v1/products/culinary-packs/seed`, `CulinaryPackSeedService`), then one storefront
  order. Every row is made the way a first tenant's would be, so the seed doubles as a smoke
  test of the paths Phase 32 needs. The dev `DemoDataSeeder` (`@Profile("dev")`) must NEVER run
  against staging: that would cross the Phase 28 dev/prod boundary.
- **D-27: the DPLY-01 login uses a staging-only test vendor** created by the D-26 script. Its
  credential lives in the D-23 Key Vault, never in committed e2e fixtures, so no git-committed
  credential can sign in to a public host.
- **D-28: Stripe TEST mode is wired in this phase.** Test keys and a webhook endpoint at
  `api-staging.olajay.co.uk`, registered in the Stripe test dashboard. Phase 29 proves only that
  a signed test webhook is received and verified, plus its unsigned/forged negative. PAY-03's
  money-path proof stays Phase 30's, but needs no further deploy work afterwards. TEST mode
  only; live keys never enter staging.

### Owner rulings at plan time (2026-10-18, after 29-RESEARCH.md)
- **D-29: the staging ingress controller is Traefik v3 with its Kubernetes Ingress-NGINX
  provider**, not the retired ingress-nginx and not Gateway API. The existing `nginx.ingress.kubernetes.io/*`
  annotations stay the source of truth. The plan must (a) list every annotation the overlays use
  that the Traefik provider does NOT document (research: `proxy-buffer-size`,
  `proxy-buffers-number`, `proxy-connect-timeout`, `proxy-send-timeout`) and say for each what
  replaces it or why its loss is accepted; (b) prove fidelity on the live edge — the security
  headers by `curl -sI`, a CORS preflight, a 429 on burst — each shown FAILING first;
  (c) update every NetworkPolicy `namespaceSelector` that names the ingress namespace. Phase 37's
  trusted-proxies item now measures Traefik's pod CIDR and `externalTrafficPolicy: Local` on
  Traefik's Service.
- **D-30: D-21 stands literally — the cluster CA goes into a JVM-wide truststore** (owner chose
  this over the mail-scoped `spring.mail.ssl.bundle`). The truststore MUST be the JDK's own
  `cacerts` plus the cert-manager CA, built at pod start (init container or image step) — never
  the CA alone, which would break Stripe, Entra and Blob TLS. Proof: STARTTLS to Mailpit
  verifies, AND a public-TLS call (Stripe test API or Blob) still succeeds from the same pod;
  a CA-only truststore is the break arm and must red the public call.
- **D-31: the D-26 seed order is paid by a Stripe TEST PaymentIntent** and settles through the
  D-28 webhook. No cash order in the seed.
- **D-32: AKS uses Entra ID + Azure RBAC with local accounts disabled.** CI authenticates by
  GitHub OIDC to the reused `jtoye-ci` identity (its `environment:staging` federated credential
  is already correct) and `kubelogin`. Role assignments are scripted, least-privilege
  (cluster-scoped RBAC Writer on the staging namespace path, not Cluster Admin, unless a step
  is shown to need more).

### Corrections measured 2026-10-18 (29-RESEARCH.md §Corrections; these override the text above)
- DNS for `olajay.co.uk` is **Namecheap BasicDNS**, not Netlify/NS1 (D-05/D-07). The owner edits records.
- Alert SMTP is **Zoho** `smtp.zoho.eu:587` from `alerts@olajay.co.uk` (D-17 said Gmail);
  the external values are `ALERTMANAGER_SMTP_{FROM,USERNAME,PASSWORD,TO}` plus Stripe TEST.
- The `jtoye-ci` identity was NOT deleted (D-04); it is reused (D-32).
- k8s runs the **`prod`** Spring profile in every environment (`k8s/base/core-java-deployment.yaml:69-70`),
  so the D-20 literal that governs the cluster is the one in `application-prod.yml`.
- **#100 was closed by mistake** by docs PR #955; reopen it and close it on the D-23 evidence.
- D-24 is resolved by measurement: **in-cluster Redis** (Managed Redis breaches the ceiling once
  the LB meter bills). Owner checkpoint after 24 h of cluster life on the
  `FreeTierInfrastructureCost` AKS meter and on `snackpass-pg`.

### Claude's Discretion
- **D-25: managed PostgreSQL major version.** The floor is ≥ 16: `jtoye_backup` BYPASSRLS
  cannot be created by `azure_pg_admin` on 15 or earlier. Prefer the highest Azure-supported
  major ≤ 18, to match dev (PG 18.6). Research verifies against current Microsoft docs. Also
  carried: add `uuid-ossp` to `azure.extensions` before the first Flyway run (V1 creates it),
  and Azure reserves 15 connections, versus `check-connection-math.sh`'s hardcoded `RESERVED=3`.
  **Decided:** the gate takes the reserved count per target (3 for self-hosted, 15 for Azure
  Flexible Server), from config, not a literal. It is shown FAILING on a staging pool sized
  inside the old budget but outside the new one.
- **DPLY-02 per-issue dispositions** not settled above: #292 (staging frontend image built with
  staging `NEXT_PUBLIC_*`), #293 (CSP includes `auth-staging`; remember a CSP source with a path
  matches exactly unless it ends `/`), #299, #301 (mcp-server manifests: build or defer with a
  reason), #302, #271, #304, #592. Each is closed or deferred **with a written reason**; none is
  dropped silently.
- Node pool SKU and count within D-03; the DPLY-05 denied-connection capture design.
- **The AKS network-policy engine** (Calico, as #297 names, or AKS-native Cilium) is the
  planner's choice, provided it is chosen at cluster creation (D-01) and DPLY-05's
  denied-connection proof passes against it. ROADMAP SC5 and DPLY-05 were reconciled on
  2026-10-18 to say "an enforcing engine (#297)". #297 closes on the proof, not on the product
  name.
- Alertmanager routing detail, which of the compose alert rules apply to staging, and Grafana
  provisioning mechanics.
- GHCR pull path and image tag strategy; RabbitMQ operator and cert-manager versions (current
  upstream, pinned in-repo, horizon rows added).
- Phase 37's live-cluster items: measure the ingress pod CIDR and verify
  `externalTrafficPolicy: Local` on ingress-nginx before setting `security.trusted-proxies`
  (it stays `""`, fail-closed, until measured).

### Owner rulings in session (2026-10-19, appended during 29-14's execution)
- **D-33: two owner rulings of 2026-10-19.**
  - **(a) Order: 29-26 runs right after 29-22.** 29-26 (the #592 one-click unsubscribe base URL
    and the #299 customer realm) is the persona-testing unlock and has no functional dependency
    on the monitoring chain 29-23..29-25. Its only coupling to them was shared files
    (`k8s/goldens/staging.yaml`, `k8s/scripts/check-render-invariants.sh`,
    `k8s/staging/kustomization.yaml`), so 29-23 now depends on 29-26 and those files stay
    serialized. Ruled order: 29-22 -> 29-26 -> 29-23 -> 29-24 -> 29-25.
    **Measured at the re-plan, recorded so it is not read as a deviation:** 29-26's live task
    moves the D-26 seed order to its next state to send a customer email, and that order is
    created by 29-18 (`scripts/staging-seed.sh --stage order`, which itself runs right after
    29-22). So 29-26 depends on 29-22 AND 29-18. Effective order: 29-22 -> 29-18 -> 29-26 ->
    29-23 -> 29-24 -> 29-25. Waves: 29-26 = 10, 29-23 = 11, 29-24 = 12, 29-25 = 13,
    29-27 = 14, 29-28 = 15. — **Reversibility:** reversible (plan order only).
  - **(b) Persona testers read staging mail at `https://mail-staging.olajay.co.uk`:** Mailpit's
    web UI and its `/api` (port 8025) behind ONE shared HTTP basic-auth credential (plan 29-22,
    Task 4).
    - **DNS:** the A record points at the ingress IP 51.105.43.14. The owner adds it at
      Namecheap; agents never edit DNS. It was measured present at both dns1 and
      dns2.registrar-servers.com on 2026-10-19.
    - **TLS:** from ClusterIssuer `letsencrypt-prod` over HTTP-01 through Traefik, like every
      other staging host.
    - **Network:** the Traefik namespace reaches Mailpit on TCP 8025 only. SMTP 1025 stays
      reachable only from core-java, Keycloak and Alertmanager, and is never published.
    - **The credential** is GENERATED by `scripts/staging-secrets.sh`: never chosen by a
      human, never in git, never printed. It is stored in `jtoye-stg-kv` and delivered through
      the D-23 Secrets Store CSI pattern. The owner retrieves it from Key Vault and hands it to
      testers out of band.
    - **Mailpit sends nothing:** relay, release and forward stay unconfigured, and its Send
      API stays locked.
    - **The accepted risk:** whoever holds the credential reads EVERY staging email,
      password-reset, staff-invite and DSAR-export links included. That is equivalent to
      taking over any staging account. It is acceptable ONLY because staging holds test
      personas and no real personal data. So **no real customer data on staging** is now a
      standing rule, stated in the tester note.
    - **Rotation:** regenerate the Key Vault secret through
      `docs/runbooks/credential-rotation.md`.
    - **Reversibility:** reversible. Delete the Ingress, the DNS record and the two Key Vault
      objects.
  - **How D-33 relates to D-19, D-06, D-08 and D-13/D-20.**
    - **D-19 ("only Grafana gets a public hostname")** is amended in its READING only. Mailpit
      is not a monitoring host, so Grafana is still the only monitoring host with a public name,
      and Prometheus and Alertmanager stay cluster-internal. But mail-staging is a second
      publicly named non-app host.
    - **D-06:** the flat `-staging` host list gains `mail-staging.olajay.co.uk`, in the same
      shape.
    - **D-08** is untouched: this is a staging record only.
    - **D-13/D-20** are untouched: app mail is still captured and never delivered. The UI only
      lets testers read what was captured.

- **D-34: two more owner rulings of 2026-10-19, both after 29-14.**
  - **(a) Fourth node; the D-03 ceiling is raised by its cost.**
    - **Why:** 29-14 measured that core-java and Keycloak could not roll at all. Each takes a
      1Gi surge pod with request equal to limit, and the three B2s nodes had 661, 399 and
      803 Mi allocatable free.
    - **Why the requests cannot shrink:** measured use is core-java 732Mi and Keycloak 621Mi,
      both JVMs.
    - **Change:** nodepool1 was scaled from 3 to 4 Standard_B2s on 2026-10-19, owner-approved.
      `scripts/azure-staging-provision.sh` now has NODE_COUNT=4, so a re-provision matches.
      The new node had 734Mi of 2.7Gi requested after it joined.
    - **Cost:** £0.0356/h list price, about £26/month. The OS disk is ephemeral, so the
      29-19 research projection is £77.96/month for 3 × B2s. The projected estate rises from
      about £131–145 to about £157–171/month. The owner accepted going over D-03's ~£150
      ceiling for this; 29-19 measures the real figure.
    - **Reversible:** scale back to 3 once monitoring's footprint is known, if it fits.
  - **(b) The storefront image defect is fixed inside Phase 29, before persona testing.**
    - **The defect:** 29-14 found that `PublicStorefrontService.toPublicProductDto` reads only
      `products.image_url`, while vendor uploads go through the media_asset pipeline (V53). An
      uploaded photo therefore never reaches the public storefront. Production is affected the
      same way.
    - **Fix:** a dedicated plan, test-first, deployed before 29-26 so personas see photos.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Scope, requirements, prior record
- `.planning/ROADMAP.md` § "Phase 29: Deployable Staging, With Its Own Monitoring": goal, 5 success criteria, issue table.
- `.planning/REQUIREMENTS.md` DPLY-01..DPLY-05 (lines 118-122).
- `.planning/ISSUE-DISPOSITION.md` § "Phase 29".
- Branch archive (read-only, never delete): `git show origin/phase-29-research:.planning/phases/29-deployable-staging-with-its-own-monitoring/<file>`: the August 29-CONTEXT.md, the 29-01..10 PLAN/SUMMARY files and 29-PROVISIONING-EVIDENCE.md. Tip `ebee67fe`.
- `.planning/phases/36-azure-blob-storage-throughout/36-PHASE29-HANDOFF.md`: superseded decisions, the 7→3 operator-secret cut, provisioning order, verification items A6/A7/A10/A12, the #626 read-back, the WORM checkpoint, the functional path.

### Decisions and datastores
- `docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md`: Accepted; its 2026-10-18 section lists the inputs this phase re-derives.
- `docs/runbooks/azure-blob-provisioning.md`: run its sections in order (§1 subscription pin; §4.4 the WORM lock is a HUMAN step, never scripted).
- `docs/runbooks/backups.md`, `infra/backups/create-backup-role.sql`, `infra/backups/k8s-backup.sh`, `k8s/base/pg-backup-cronjob.yaml`.
- `infra/dependency-horizons.yaml` (`rabbitmq-k8s` row) and `docs/runbooks/rabbitmq-broker-upgrade.md` §7.
- `docs/runbooks/credential-rotation.md`: every staging credential's rotation path (D-23).

### Deploy layer
- `k8s/staging/` (kustomization, configmap-patch, ingress host patches), `k8s/base/` (deployments, ingress, sse-ingress, networkpolicies, serviceaccounts, pg-backup-cronjob), `k8s/goldens/`, `k8s/scripts/` (`check-render-invariants.sh` incl. INV-9 and INV-12, `check-no-plaintext-secrets.sh`, `check-connection-math.sh`, `render-golden.sh`).
- `scripts/deploy.sh`, `.github/workflows/ci-cd.yaml` (deploy job, `DEPLOY_*_ENABLED`, `FRONTEND_PUBLIC_*`).
- `k8s/DEPLOYMENT.md` ("Runtime-parity gates"), `k8s/QUICK_START.md`; `k8s/PRODUCTION_READINESS_REPORT.md` is a dated record (append only).
- `scripts/check-runtime-freshness.sh`, `scripts/check-branch-behind-base.sh`.

### Monitoring
- `infra/monitoring/` (docker-compose.monitoring.yml, rules, dashboards, alertmanager config): the one corpus D-16 mounts.
- `scripts/check-alert-liveness.sh`, `scripts/check-alert-metrics.sh`, `scripts/seed-order-metric.sh` (a core-java rebuild reds the metrics gate until it runs).
- `.planning/phases/27-operational-maturity/deferred-items.md` §5.

### Identity, mail, payments
- `infra/keycloak/realm-export.template.json`, `infra/keycloak/README.md` (re-import needs `--override true`).
- `k8s/base/configmap.yaml` split-horizon issuer keys (staging: public issuer == pod-reachable issuer).
- `core-java/src/main/resources/application.yml` mail block and `MailStartTlsPropertyTest` (#952).
- `core-java/src/main/java/uk/jtoye/core/product/CulinaryPackSeedService.java`, `ProductController` (`/culinary-packs`).
- Stripe: `stripe-credentials` (`api-key`, `webhook-secret`, both `optional: true` in `k8s/base/core-java-deployment.yaml`); Phase 30 `30-VERIFICATION.md`.

### Security posture
- `docs/security/PENTEST-TRIAGE.md` and #549 (staging OpenAPI requires auth; do not regress); Phase 28 owner/runtime role split and `DatabaseConfigurationValidator`.

</canonical_refs>

<code_context>
## Existing Code Insights

### Carry from `phase-29-research` (salvage audit 2026-10-17)
- **Nearly as-is:** `check-networkpolicy-enforcement.sh`, `check-alert-corpus-parity.sh` (alerts.yml still md5-equal), `check-deploy-digest-parity.sh`, `staging-pitr-drill.sh` (retarget to the D-25 version), the `PROM_EXEC` change in `check-alert-liveness.sh`, and the 29-02 `DB_SSL_MODE` / `REDIS_SSL` switches.
- **Adapt:** `azure-staging-provision.sh` (keep the subscription guard; add `--enable-workload-identity`, Key Vault and the CSI add-on), `staging-bootstrap.sh`, and `staging-secrets.sh`, which becomes the D-23 write-once Key Vault writer (drop the four `AWS_*` values and both S3 secret applies; add `DSAR_ENCRYPTION_KEY`, `CLIENT_IP_HMAC_KEY` and Stripe).
- **Do not carry:** August image pins (KC 24.0.5, Prometheus v2.48, Grafana 10.2, RabbitMQ 4.3.4, PG16), anything S3/WAL-G, the branch's ADR-0002 edit, and branch line numbers in plans 29-11..16.

### Reusable on main
- The `k8s/staging` overlay already carries the right hostnames, realm and env-varying keys. Phase 36's `core-java` and `pg-backup` ServiceAccounts (`automountServiceAccountToken: false`, the WI pod label in `k8s/staging/workload-identity-patch.yaml`) need the client-id annotation for **Blob**. Until it lands, core-java crash-loops by design; do not "fix" that with a manifest env var. Key Vault (D-23) widens WI to every secret-reading workload, so this is a starting point, not the full scope.
- The `k8s/local` bring-up pair (`scripts/k8s-local-secrets.sh`, `k8s-local-up.sh`): idempotency and XOR-guard patterns.
- The Phase 26 CI gates auto-cover every new kustomization (monitoring, Keycloak, Mailpit, RabbitMQ operator).
- The Phase 26-08 L7 verbatim-URL login proof.

### Established patterns
- Every gate is shown to FAIL before it is trusted: the CI deploy gate, the alert gates against staging, the PITR two-arm drill, the DPLY-05 denied connection, D-22 mail TLS, the D-28 webhook signature.
- Per-issue deferral with a written reason; silence is the defect.
- Dated records are appended, never rewritten.

### Integration points
- Managed PG: Flyway as owner, runtime role, `jtoye_backup`, and a `keycloak` database.
- Key Vault → CSI → pods (D-23). Workload Identity → Blob (Phase 36). OIDC → CI deploy (D-04).
- Alertmanager → Gmail; core-java → Mailpit (STARTTLS, cluster CA); Stripe test → `api-staging` webhook.

</code_context>

<specifics>
## Specific Ideas

- "Nothing may look live before it is": staging-only DNS is a stance about product truth, not a cost measure (D-08).
- The asymmetry is deliberate: app mail is captured (now over required TLS), alert mail is real. The rehearsal environment must never surprise a real inbox, except when the platform itself is breaking.
- Owner rulings 2026-10-18 that shape this phase: STARTTLS required in staging and production; delete the August estate (it cost £70.89 Aug–Oct; nothing in it is reused); ADR-0002 recorded Accepted on main.
- The WORM retention lock on `jtoye-db-backups` stays UNLOCKED through the restore drill, then a HUMAN locks it. — **Reversibility:** one-way — a locked time-based retention policy cannot be shortened or removed until the retention lapses.

</specifics>

<deferred>
## Deferred Ideas

- **SES sending-domain verification and real app email:** Phase 32 (D-13).
- **SLOs and the full runbook set (#112 remainder):** post-GTM (D-18).
- **Production DNS and cutover:** Phase 32 (D-08).
- **Zone migration to Azure DNS, external-dns, wildcard certs:** only if record churn becomes real (D-07).
- **ntfy or push alerting beside email:** if email notice-time proves too slow.
- **An off-Azure backup copy:** revisit before real customer data if subscription-loss risk is re-weighed (Phase 36 D-01 trade).
- **Full-catalogue EXIF/WebP media sweep:** carried on Phase 28's deferred list, not folded here.

</deferred>

---

*Phase: 29-Deployable Staging, With Its Own Monitoring*
*Context gathered: 2026-10-18 (re-plan on main; the August context is archived on `phase-29-research`)*
