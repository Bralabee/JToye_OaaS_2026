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
  Phase 32 with that reason recorded. Alert email (D-17) is real and separate.
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
  path production will. The staging and production profiles set
  `mail.smtp.starttls.required=true` (#952 fixed the `.enable` spelling; this phase sets the
  `.required` half). Dev and nightly stay opportunistic.
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
  (no `kind: Secret` in any build; INV-9 no storage Secret) stay green. — **Reversibility:**
  costly — every workload's secret mount, the provisioning script and the rotation runbook are
  built on it.
  - **Machine-made values are generated by a write-once script** that never prints them: DB,
    Redis and RabbitMQ passwords, Keycloak admin, Grafana admin, `DSAR_ENCRYPTION_KEY`,
    `CLIENT_IP_HMAC_KEY`, the unsubscribe signing secret. It REFUSES to overwrite an existing
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

### Claude's Discretion
- **D-25: managed PostgreSQL major version.** The floor is ≥ 16: `jtoye_backup` BYPASSRLS
  cannot be created by `azure_pg_admin` on 15 or earlier. Prefer the highest Azure-supported
  major ≤ 18, to match dev (PG 18.6). Research verifies against current Microsoft docs. Also
  carried: add `uuid-ossp` to `azure.extensions` before the first Flyway run (V1 creates it),
  and Azure reserves 15 connections, versus `check-connection-math.sh`'s `RESERVED=3`.
- **DPLY-02 per-issue dispositions** not settled above: #292 (staging frontend image built with
  staging `NEXT_PUBLIC_*`), #293 (CSP includes `auth-staging`; remember a CSP source with a path
  matches exactly unless it ends `/`), #299, #301 (mcp-server manifests: build or defer with a
  reason), #302, #271, #304, #592. Each is closed or deferred **with a written reason**; none is
  dropped silently.
- Node pool SKU and count within D-03; the AKS network-policy engine (Azure, Calico or Cilium),
  as long as enforcement is real; the DPLY-05 denied-connection capture design.
- Alertmanager routing detail, which of the compose alert rules apply to staging, and Grafana
  provisioning mechanics.
- GHCR pull path and image tag strategy; RabbitMQ operator and cert-manager versions (current
  upstream, pinned in-repo, horizon rows added).
- Phase 37's live-cluster items: measure the ingress pod CIDR and verify
  `externalTrafficPolicy: Local` on ingress-nginx before setting `security.trusted-proxies`
  (it stays `""`, fail-closed, until measured).

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
- `.planning/phases/27-observability-hardening/deferred-items.md` §5.

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
- The `k8s/staging` overlay already carries the right hostnames, realm and env-varying keys; Phase 36's `core-java` and `pg-backup` ServiceAccounts (`automountServiceAccountToken: false`, the WI pod label) need only the client-id annotation patch. Until it lands, core-java crash-loops by design; do not "fix" that with a manifest env var.
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
