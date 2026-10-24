# Phase 29: Deployable Staging, With Its Own Monitoring - Research

**Researched:** 2026-10-18 (re-planned on `main` at `29facb94`; supersedes the 2026-08-10 research archived on `phase-29-research` @ `ebee67fe`)
**Domain:** Azure provisioning (AKS + Flexible Server + Key Vault), in-cluster platform services (ingress, cert-manager, Keycloak, RabbitMQ operator, Mailpit), monitoring port (compose → k8s), secrets via Key Vault + Secrets Store CSI + Workload Identity, CI deploy via GitHub OIDC
**Confidence:** HIGH on repo defects and measured Azure/DNS/GitHub state; MEDIUM on the cost total (unit prices measured, two meters unresolved); LOW/ASSUMED items are listed in the Assumptions Log

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### Carried from 2026-08-10, still binding
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

#### Superseded (kept for cross-reference only)
- **D-11 / D-12 (AWS S3 media and backup buckets): SUPERSEDED by Phase 36** (ruling
  2026-09-28). Media goes to Azure Blob `jtoyestgmedia` (uksouth, raw Blob endpoint, `jtoye-images`
  public at `blob` level, `jtoye-quarantine` private); backups go to `jtoyestgbackup` (ukwest),
  container `jtoye-db-backups`, WORM plus soft delete; both through **Workload Identity**, with no
  keys anywhere. The accepted trade: it survives object deletion and app-credential compromise,
  not loss of the whole subscription. Follow `docs/runbooks/azure-blob-provisioning.md` in order.
- **The PG16 pin: SUPERSEDED.** It is a floor, not a pin (D-25).

#### Staging email and the STARTTLS ruling (new)
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

#### Where secrets live (new; closes #100 and #300)
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

#### Redis (new; ADR-0002 follow-through)
- **D-24: Azure Managed Redis, smallest tier, IF research measures it inside the D-03 ceiling.
  Otherwise in-cluster Redis**, recorded as a dated ADR-0002 amendment. "Azure Cache Basic" is
  impossible: Microsoft blocked creation of new Azure Cache for Redis Basic/Standard/Premium for
  existing customers from **2026-10-01** (retirement 2028-09-30). If in-cluster, it is a single
  pod with **no persistence** (cache-only, rebuildable; an alert fires when it is down). The
  `REDIS_SSL` switch carried from the branch (29-02) applies to whichever endpoint is chosen.

#### What staging carries (new)
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

### Deferred Ideas (OUT OF SCOPE)
- **SES sending-domain verification and real app email:** Phase 32 (D-13).
- **SLOs and the full runbook set (#112 remainder):** post-GTM (D-18).
- **Production DNS and cutover:** Phase 32 (D-08).
- **Zone migration to Azure DNS, external-dns, wildcard certs:** only if record churn becomes real (D-07).
- **ntfy or push alerting beside email:** if email notice-time proves too slow.
- **An off-Azure backup copy:** revisit before real customer data if subscription-loss risk is re-weighed (Phase 36 D-01 trade).
- **Full-catalogue EXIF/WebP media sweep:** carried on Phase 28's deferred list, not folded here.
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description (`.planning/REQUIREMENTS.md:118-122`) | Research Support |
|----|-------------|------------------|
| **DPLY-01** | A real Keycloak vendor login through the ingress to a rendered dashboard, on a resolvable public hostname, with real seeded rows. | Blockers A (client-id), E (migrator/runtime split never wired in k8s), F (NetworkPolicy denies ingress→core-java), G (`prod` profile vs staging smoke test), H (realm template is dev-shaped); §"Seeding via real flows"; §"DNS"; §"Ingress controller decision" |
| **DPLY-02** | The deploy knot closed or per-issue deferred with a reason — #99, #100, #292, #293, #299, #301, #302, #271 (+ #300, #304, #592, #294, #98, #112). | §"DPLY-02 — measured per-issue dispositions" (each with state, main-tree evidence and a recommended disposition); #100 was closed *lexically* by docs PR #955 and must be reopened |
| **DPLY-03** | Prometheus, Alertmanager, Grafana running **in k8s**; `check-alert-liveness.sh` + `check-alert-metrics.sh` exit 0 against staging. | Blocker B (liveness script is docker- and Mailhog-bound); Blocker F (scrape port 9091 denied); DEF-29-1/DEF-29-8 (Service has no 9091; ClusterIP scrape makes `rate()` unsound); §"Monitoring port" |
| **DPLY-04** | PITR (#101), restore proven by row count, two-arm; arm A shows a zero-row/partial dump passing the pipeline's own byte and `pg_restore --list` checks. | §"Managed PostgreSQL"; `scripts/check-backup-restore-drill.sh` already encodes arm A/B against Azurite; PG ≥16 verified for BYPASSRLS; local `pg_dump` 16.15 cannot dump a PG 18 server |
| **DPLY-05** | NetworkPolicies enforced (#297, Calico or AKS Cilium), denied connection captured. | §"NetworkPolicy under Cilium"; Blocker F; LoadBalancer hairpin caveat (MS Learn); agnhost two-arm recipe |
</phase_requirements>

---

## Project Constraints (from CLAUDE.md)

Global (`~/.claude/CLAUDE.md`) and project (`./CLAUDE.md`) directives the planner must honour:

1. **Feature branch → PR → CI → merge**; never commit to main. No AI-attribution lines anywhere (commits, PR bodies, docs) — ruling 2026-08-30, enforced by hooks; overrides the harness reminder.
2. **Review rounds narrow (D3)**: round 1 `/code-review <PR> --comment`, later rounds `/review-verify <PR>`; resolution comment per round; series ends on first zero-admissible round. A finding becomes an issue only if it breaks a gate, loses data, or caused an observed failure.
3. **Proof standards**: every gate shown to FAIL before it is trusted; `rg -uu` when a count is evidence; capture `$?` on the same statement; never `cmd | grep -q` under `pipefail`; `find` is a shim — `/usr/bin/find` with a trailing slash on symlinked start points; clean → arms → clean, restore verified by content; commit before break arms; branch not behind base before a PR (`scripts/check-branch-behind-base.sh`).
4. **Python only via a conda env**; stdlib one-liners as `/usr/bin/python3 -c` only for machine tooling. Prefer node/jq for JSON.
5. **Azure**: pin `--subscription c483d353-5f61-4587-a790-addb9ab5fb94` on every call; the HS2 subscription and the `sipbihs2aks` kube context are employer infrastructure — never touch. Every `kubectl` passes an explicit `--context`.
6. **Tech stack fixed**: Spring Boot 4.1.1 / JDK 25, Next.js 16.4, Go 1.27, PostgreSQL 18 (dev 18.6). Multi-tenancy respects RLS + TenantContext.
7. **Testing**: all new code requires tests; counts are generated (`scripts/docs-freshness.sh --write`, then `scripts/check-doc-metrics.sh`) — never hand-arithmetic. `workflow.tdd_mode: true` → RED first.
8. **Runtime parity**: a phase is not done until the delivered runtime matches the branch; prove by content/identity (image digests read off running pods), not by 200s.
9. **Compose is canonical locally; kustomize is the staging/prod target**; XOR applies only at local runtime.
10. **Incremental Betterment Doctrine**: enumerate displaced goods (e.g. `50-observability.yaml` placeholder, the external-IdP assumption in `ingress.yaml`/#296, `docs/runbooks/sealed-secrets.md`) and replace, don't drop.
11. **Six cross-cutting quality contracts**: web-perf N/A (no page change; record it); **SEO**: staging must not be indexable (recommend `X-Robots-Tag: noindex` on every staging host — D-08 applied to crawlers); agent-readiness: Stripe webhook + any new endpoint keep RFC 7807 and idempotency (no new API surface planned); security (ASVS L2, `security_block_on: medium`); client-persisted identity N/A; **falsifiable evidence + runtime parity: dominant for this phase**.
12. **GSD**: file changes through a GSD command; `SUMMARY.md` presence marks completion; `state.record-session` corrupts STATE (hand-edit).
13. **Machine-stack edits are machine-session work only** — this phase must not edit `~/.claude/**` or `~/dotfiles`.
14. **A blocked command is the answer** — satisfy the precondition or stop; DNS edits are the owner's (the permission classifier blocks them).

---

## Summary

Phase 29 is provisioning-heavy, but **on `main` today a staging deploy cannot work even with a perfect estate**: this research found eight concrete repo defects (Blockers A–H below), five of them new since the August research and invisible to every existing gate because the k8s tree has never been applied to an enforcing cluster with a fresh managed database. The two most consequential new ones: (E) k8s never wires the Phase 28 migrator/runtime role split — `DB_MIGRATION_USER` is a *reviewed omission* in `k8s/scripts/check-env-contract.sh:165` explicitly deferred "to the Phase 29 deploy work", so on a fresh managed database core-java either migrates as the DML-only runtime role (Flyway fails) or boots as the owner (`DatabaseConfigurationValidator` refuses); and (F) `core-java-allow` admits ingress only from frontend/edge-go pods and the `monitoring` namespace on **9090** — not from the ingress controller (so `api-staging` is dead under enforcement) and not on **9091** (so the core-java scrape is dead). Blocker G is that every k8s environment runs `SPRING_PROFILES_ACTIVE=prod` (`k8s/base/core-java-deployment.yaml:69-70`), so `application-staging.yml` never loads in k8s — which makes D-20's "literal in `application-staging.yml`" inert for the cluster, and makes the CI smoke test's `EXPECT_SWAGGER=true` (`ci-cd.yaml:1847`) guaranteed to fail and roll back.

The cost picture changed materially. Measured against the Azure Retail Prices API and the subscription's own Cost Management meters (2026-10-18, GBP, uksouth): **Azure Managed Redis B0 bills 2 node-hours per hour with HA (48 units/day measured in August usage) = £19.86/mo, not the £9.93 August recorded**; AKS's default 128 GiB OS disk is a **P10 at £18.00/disk/month** (measured on the August bill) — an £54/mo line the August total omitted; and the Standard Load Balancer currently bills on a `- Free` meter that is expected to end with the subscription's free offer. With **ephemeral 30 GiB OS disks on B2s** (VM cache measured at 30 GiB) and **in-cluster Redis**, the estate is **£131.22/mo now and £145.02/mo once the LB meter bills**; Managed Redis (any B0 form) breaches the ceiling once the LB bills, so **D-24 resolves to in-cluster Redis**. Two items can still push the total over £150 and must be owner checkpoints: an unexplained new AKS meter `FreeTierInfrastructureCost Uptime SLA` (£0.0377/hr, effective 2026-10-01; MS docs still say Free-tier management is free) and `snackpass-pg` leaving its free tier (≥ £3.21/mo storage plus compute whenever it auto-starts).

Two CONTEXT facts are stale by measurement and should be corrected at the start of planning: **DNS is now Namecheap BasicDNS** (`olajay.co.uk NS dns1/dns2.registrar-servers.com`, measured at the `.uk` registry 2026-10-18T17:16Z), not Netlify/NS1 (D-05/D-07); and the **alert SMTP is now Zoho** (`smtp.zoho.eu:587`, `alerts@olajay.co.uk`, app password held in the project `.env`), not Gmail (D-17) — per the owner's 2026-10-18 memory record. Also measured: the `jtoye-ci` identity was **not** deleted (it still exists with its `environment:staging` federated credential and no role assignments), `Microsoft.KeyVault` and `Microsoft.Storage` are **NotRegistered**, the regional vCPU quota is **10** (B-series family 10, Basv2 family **0**), GHCR images are **public**, and the repo variable `FRONTEND_PUBLIC_API_URL` is the **production** origin, so the CR-02 guard will fail the first staging deploy until it is changed.

**Primary recommendation:** provision a fresh AKS (Free tier, K8s 1.36.x, Azure CNI overlay + **Cilium dataplane and policy**, 3 × `Standard_B2s` with **ephemeral 30 GiB OS disks**, OIDC + Workload Identity + `azure-keyvault-secrets-provider` add-on, own static outbound IP) plus Flexible Server **PostgreSQL 18 on B2s** and an RBAC-mode Key Vault; run **Redis in-cluster**; fix Blockers A–H as Wave-0/1 repo work each with its own fail arm; and sequence: providers + quota check → Key Vault + identities → cluster → platform bootstrap (cert-manager, ingress, RabbitMQ operator) → roles on the managed server → app → monitoring → seed → the five proofs → CI deploy.

---

## Corrections to CONTEXT.md (measured 2026-10-18; fix at the start of `/gsd-plan-phase 29`)

| CONTEXT says | Measured on 2026-10-18 | Evidence | Impact |
|---|---|---|---|
| D-05/D-07: zone at **Netlify DNS (NS1)**, records added "at Netlify" | Delegation is **Namecheap BasicDNS** | `dig @dns1.nic.uk NS olajay.co.uk` → `dns1.registrar-servers.com.`, `dns2.registrar-servers.com.`; authoritative `aa`-NXDOMAIN for all four staging names at `dns1.registrar-servers.com`; production `api`/`app` NXDOMAIN (D-08 holds) `[VERIFIED: dig, 2026-10-18T17:16Z]` | The DNS step targets Namecheap; the owner edits records (the classifier blocks agent DNS edits). The August "edits never reached the zone" failure (NS1 p05) is moot. |
| D-17: alerts via **Gmail SMTP** app password | Alert SMTP is **Zoho** `smtp.zoho.eu:587` STARTTLS from `alerts@olajay.co.uk`; app password held only in the project `.env` as `ALERTMANAGER_SMTP_{FROM,USERNAME,PASSWORD,TO}`; `ALERTMANAGER_SMTP_TO` was still a placeholder on 10-18 | Owner memory `reference_olajay_mail_dns.md` (2026-10-18) `[ASSUMED — memory record, not re-probed this session]` | External-value list for D-23 becomes the four `ALERTMANAGER_SMTP_*` (+ Stripe). Confirm with owner; keep "real inbox" semantics. |
| D-04: `jtoye-ci` identity "is deleted with the old estate" | **Still exists**, federated credential `gh-deploy-staging` subject `repo:Bralabee/JToye_OaaS_2026:environment:staging`, **no role assignments** | `az identity federated-credential list -g jtoye-rg --identity-name jtoye-ci` `[VERIFIED: az 2.91.0]` | Owner choice: reuse (subject already correct) or delete-and-recreate. Either way it needs AKS + resource-group roles. |
| D-20: literal in `application-staging.yml` **and** `application-prod.yml` | k8s staging runs **`prod`** profile: base sets `- name: SPRING_PROFILES_ACTIVE` / `value: "prod"` (`k8s/base/core-java-deployment.yaml:69-70`); staging overlay does not override (golden `k8s/goldens/staging.yaml:281-282` shows `value: prod`) `[VERIFIED]` | The `application-prod.yml` literal is what governs the cluster; the staging-profile literal only protects a host run with `staging`. Keep both (D-20), but the proof must target the prod-profile property set and the live pod. |
| D-21: CA "mounted into the JVM truststore" | Boot 4.1.1 offers a **mail-scoped** trust path: `spring.mail.ssl.bundle` sets `mail.smtp.ssl.socketFactory` from an SSL bundle and (default `verify-hostname=true`) sets `mail.smtp.ssl.checkserveridentity=true` — "the STARTTLS command can use the corresponding SSLSocketFactory, even if the 'mail.(protocol).ssl.enable' property is not set" `[VERIFIED: spring-boot-mail-4.1.1.jar META-INF/spring-configuration-metadata.json + javap of MailSenderPropertiesConfiguration]` | Recommend the SSL-bundle path (full chain + hostname verification, scoped to mail) over a JVM-wide truststore, which would need the public CA set re-merged or Stripe/Entra/Blob TLS breaks. Planner confirms this reading of D-21 with the owner. |
| CONTEXT/STATE: `#100` open | **#100 CLOSED 2026-10-18 by PR #955** (a docs PR) — `closedByPullRequestsReferences: [955]`; #955's body line "Closes #100/#300." `[VERIFIED: gh]` | The lexical-closer trap (`trap_does_not_close_still_closes`). Reopen #100 and close it on the D-23 evidence. #300 was not auto-closed (parser takes the first number only). |
| `rabbitmq-k8s` horizon `manual_review` expires **2026-10-26** (archive) | Now `expires: "2027-01-16"` (`infra/dependency-horizons.yaml:558-561`) `[VERIFIED]` | Not on this phase's critical path; the row still closes only when the operator manifest ships with a declared pin. |

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Cluster lifecycle, CNI, NetworkPolicy enforcement | Azure control plane (AKS, Cilium) | — | Dataplane/policy engine is a create-time property (one-way door) |
| Public IP, TLS issuance, DNS | Azure Network + cert-manager (in-cluster) | Namecheap BasicDNS (manual A records) | D-07; HTTP-01 is answered through the ingress |
| HTTP routing, TLS termination, security headers, rate limits | Ingress controller (in-cluster) | — | `k8s/base/ingress.yaml` owns hosts/annotations |
| Identity (OIDC issue) | Keycloak (in-cluster, D-02) | Managed PG (`keycloak` database) | Realm rendered at pod start from the one template |
| Token validation | core-java / edge-go (API tier) | Keycloak JWKS (in-cluster URL) | Split-horizon: JWKS from in-cluster service, `iss` = public host |
| Relational persistence + PITR | Azure Flexible Server PG 18 | pg-backup CronJob → Blob `jtoyestgbackup` | D-09/D-10 |
| Cache | In-cluster Redis (single pod, no persistence) | — | D-24 resolved by measurement (§Cost) |
| Broker (AMQP + STOMP) | RabbitmqCluster (operator) | — | D-09 |
| Object storage | Azure Blob via Workload Identity | — | Phase 36 |
| Secrets | Azure Key Vault (per env) | Secrets Store CSI driver + WI → synced k8s Secrets | D-23 |
| App mail capture | Mailpit (in-cluster, STARTTLS required, cert-manager CA) | — | D-20/D-21 |
| Alert delivery | Alertmanager → Zoho SMTP (real) + Mailpit (inspectable sink for L-3) | — | D-17 (amended), Blocker B |
| Metrics/rules/dashboards | Prometheus + Grafana (in-cluster) | exporters (in-cluster) | D-16; Grafana only public monitoring host (D-19) |
| Deploy authentication | GitHub Actions → Entra federated credential → AKS | `scripts/deploy.sh` manual | D-04 |
| Seed data | Real-flow seed script (client of the public API + KC admin via port-forward) | — | D-26/D-27 |

---

## Measured estate cost (D-03 / D-24)

### Instrument
- **Azure Retail Prices API**, `currencyCode='GBP'`, retrieved **2026-10-18 ~17:00Z**. Query shape:
  `https://prices.azure.com/api/retail/prices?currencyCode='GBP'&$filter=<urlencoded filter>`, paginated via `NextPageLink`. GBP is returned natively, so **no USD conversion was applied**. For the record, the USD comparators returned at the same time imply **0.7542 GBP/USD** (B2s: £0.0356 vs $0.0472) and **0.7556** (AMR B0: £0.0136 vs $0.018) `[VERIFIED: prices API]`.
- **Cost Management query API** (read-only POST to `Microsoft.CostManagement/query`, `api-version=2023-11-01`) over 2026-08-01..2026-10-17 for the subscription, grouped by meter, Usage + ActualCost `[VERIFIED: az rest]`.
- Monthly = hourly × 730.

### Filters and raw figures

| Line | Filter (abridged) | Raw figure (GBP) |
|---|---|---|
| Node VM | `serviceName eq 'Virtual Machines' and armRegionName eq 'uksouth' and priceType eq 'Consumption' and armSkuName eq 'Standard_B2s'` | B2s `0.0356 / 1 Hour` (Linux); B2als v2 `0.0321`; B2as v2 `0.0642`; B2ms `0.0712`; D2as v5 `0.0755` |
| PG compute | `serviceName eq 'Azure Database for PostgreSQL' and armRegionName eq 'uksouth' and priceType eq 'Consumption'` | Burstable `B2S 0.0574 / 1 Hour`; `B1MS 0.0143`; `B2ms 0.1147` |
| PG storage | same | `Storage Data Stored 0.1004 / 1 GB/Month`; `Backup Storage LRS 0.0755 / 1 GB/Month` |
| Redis | `serviceName eq 'Redis Cache' and armRegionName eq 'uksouth' ...` | `Azure Managed Redis - Balanced B0 0.0136 / 1 Hour`; `B1 0.0279` |
| Key Vault | `serviceName eq 'Key Vault' ...` | `Standard Operations 0.0226 / 10K` |
| Public IP | `serviceName eq 'Virtual Network' ... contains(meterName,'Static')` | `Standard IPv4 Static Public IP 0.0038 / 1 Hour` |
| Load Balancer | `serviceName eq 'Load Balancer' and (armRegionName eq 'uksouth' or armRegionName eq 'Global' ...)` | `Standard Included LB Rules and Outbound Rules 0.0189 / 1 Hour` (Global); also a `... - Free 0` meter |
| Managed disks | `serviceName eq 'Storage' ... meterName eq 'P10 LRS Disk'` etc. | `P10 17.9992/Month`, `P6 9.321`, `P4 4.8212`; Standard SSD `E3 0.9963`, `E4 1.9925`, `E10 7.9701` |
| Blob | `... productName eq 'Blob Storage' and skuName eq 'Hot LRS'` | `Hot LRS Data Stored 0.0133–0.0145 / GB/Month`; writes `0.0445 / 10K` |
| AKS | `serviceName eq 'Azure Kubernetes Service' ...` | `Standard Uptime SLA 0.0755 / 1 Hour`; **`FreeTierInfrastructureCost Uptime SLA 0.0377 / 1 Hour`, effectiveStartDate 2026-10-01** |
| Bandwidth | `serviceName eq 'Bandwidth' ... contains(meterName,'Data Transfer Out')` | `Standard Data Transfer Out` tiers incl. a `0` tier (first-tier free) and `0.0657 / GB` |

### Facts measured from the subscription's own bill (Aug–Oct 2026)

- **AMR B0 with HA bills 48 units/day** (`B0 Cache Instance`, UsageQuantity 48 on every full day 2026-08-11..08-26, £0.650/day) → **£19.86/month**, i.e. two node-hours per hour. The August record's £9.93 was the non-HA figure `[VERIFIED: Cost Management Usage, daily]`.
- **Node OS disks were P10** (`Premium SSD Managed Disks / P10 LRS Disk`, £6.845 for 0.381 disk-months) → the AKS default OS disk costs **£18.00/disk/month** `[VERIFIED: Cost Management]`. The August £139.15 total omitted this line (~£54 for 3 nodes).
- **The AKS load balancer bills on `Standard Included LB Rules and Outbound Rules - Free`** at £0 (24 units/day) — a free-offer meter, alongside `B1MS Compute - Free` and `Storage Data Stored - Free` for `snackpass-pg` `[VERIFIED: Cost Management]`. The offer's end date is not in the bill; CONTEXT dates `snackpass-pg`'s free-tier exit 2026-11-08, which this research assumes is the same 12-month offer `[ASSUMED]`.
- **No AKS control-plane line** appeared for the August cluster (it ran 2026-08-10..08-14, B2s 72 h/day, then stopped) `[VERIFIED]` — but that predates the new `FreeTierInfrastructureCost` meter (effective 2026-10-01). Microsoft Learn's tier page (updated 2026-09-01) still says Free tier = "Free cluster management. Pay as you go for consumed resources." `[CITED: learn.microsoft.com/azure/aks/free-standard-pricing-tiers]`. **Unresolved — owner checkpoint after day 1 of the new cluster.**
- **B2s supports ephemeral OS disks with a 30 GiB cache** (`EphemeralOSDiskSupported: True`, `CachedDiskBytes: 32212254720`); Basv2 SKUs do not support ephemeral OS (`False`) `[VERIFIED: az vm list-skus uksouth]`.
- **Quota:** regional `cores` limit **10**, `standardBSFamily` **10**, `standardBasv2Family` **0** `[VERIFIED: az vm list-usage uksouth]` → 3 × B2s (6 vCPU) fits; B2als_v2 needs a quota request; surge nodes during AKS upgrades need headroom (max surge 1 → 8 vCPU) — fits.
- **Defender for Cloud is not registered** (`Microsoft.Security` NotRegistered) → no per-vCore Defender charge `[VERIFIED: az security pricing list error]`.

### Estate totals (always on)

| Line | Recommended config | £/mo now | £/mo after LB free meter ends |
|---|---|---|---|
| AKS control plane | Free tier | 0.00 | 0.00 (+27.52 if `FreeTierInfrastructureCost` bills — UNRESOLVED) |
| Nodes | 3 × B2s @ 0.0356 | 77.96 | 77.96 |
| Node OS disks | Ephemeral 30 GiB (VM cache) | 0.00 | 0.00 |
| PostgreSQL | Flexible B2s @ 0.0574 + 32 GiB @ 0.1004 (backup storage ≤ provisioned assumed free) | 45.11 | 45.11 |
| Redis | in-cluster pod | 0.00 | 0.00 |
| Public IPs | 2 × Standard static (ingress + outbound) | 5.55 | 5.55 |
| Load balancer | first 5 rules @ 0.0189 | 0.00 (Free meter) | 13.80 |
| PVCs | RabbitMQ 10 GiB + Prometheus 10 GiB → 2 × E3 | 2.00 | 2.00 |
| Key Vault, Blob (2 accounts), egress | rotation polling OFF; < 10K ops; < 100 GB egress | ~0.60 | ~0.60 |
| **Total — recommended** | | **131.22** | **145.02** |
| Alt: Managed Redis B0 **HA** instead of in-cluster | | 151.08 ✗ | 164.88 ✗ |
| Alt: Managed Redis B0 **non-HA** | | 141.15 | 154.95 ✗ |
| Alt: default 128 GiB P10 OS disks (what `az aks create` does without flags) | | 185.22 ✗ | 199.02 ✗ |
| Subscription pressure (not this estate): `snackpass-pg` after free tier | storage 32 GiB | +3.21 | +3.21 (+£0.34/day while auto-started) |

**Verdict:** D-24 → **in-cluster Redis** (Managed Redis does not fit once the LB bills; the HA form does not fit at all). The recommended estate fits at **£145.02** with **£4.98** headroom after the LB meter starts billing — before `snackpass-pg` (+£3.21 minimum) and before the unresolved AKS meter (+£27.52). **The planner must place an owner checkpoint** on: (1) the `FreeTierInfrastructureCost` meter after 24 h of cluster life (Cost Management by meter); (2) `snackpass-pg` disposition (another project, owner's call); (3) whether the ceiling moves if (1) bills. Cheaper levers if needed: B2als_v2 nodes (−£7.66, needs Basv2 quota and loses ephemeral OS → +£14.46 P4 disks, net worse), reusing one IP for inbound and outbound (−£2.77), PG B1ms (−£31.46, but `max_connections` 50 / 35 usable breaks the connection budget — not recommended).

**PITR drill cost:** a restored B2s server bills ~£0.06/hr until deleted; put the delete in a `trap` (carried `staging-pitr-drill.sh` already does).

---

## Standard Stack

### Azure resources (provisioned once by script, every call `--subscription`-pinned)

| Resource | Recommended value | Why |
|---|---|---|
| Providers | register `Microsoft.KeyVault`, `Microsoft.Storage` (both **NotRegistered**, measured); `ContainerService`, `Network`, `Cache`, `DBforPostgreSQL`, `ManagedIdentity`, `OperationalInsights`, `Compute` are Registered `[VERIFIED: az provider show]` | Storage name check for `jtoyestgmedia`/`jtoyestgbackup` returns `SubscriptionNotFound` until Storage is registered (same as Phase 36 recorded); DNS for both names is NXDOMAIN `[VERIFIED]` |
| AKS | `--tier free --kubernetes-version 1.36.<latest> --network-plugin azure --network-plugin-mode overlay --network-dataplane cilium --network-policy cilium --node-vm-size Standard_B2s --node-count 3 --node-osdisk-type Ephemeral --node-osdisk-size 30 --enable-oidc-issuer --enable-workload-identity --enable-addons azure-keyvault-secrets-provider --load-balancer-outbound-ips <own static IP id> --auto-upgrade-channel patch --node-os-upgrade-channel NodeImage` | All flags present in az 2.91.0 `[VERIFIED: az aks create --help]`; versions 1.34–1.37 offered in uksouth `[VERIFIED: az aks get-versions]`; Cilium is Microsoft's recommended engine, NPM Linux retires 2028-09-30 `[CITED: learn.microsoft.com/azure/aks/use-network-policies, updated 2026-09-29]`; an own outbound IP keeps the Postgres firewall rule stable across cluster re-creation |
| AKS RBAC | Recommend `--enable-aad --enable-azure-rbac --disable-local-accounts` with CI using `kubelogin` | A local-account kubeconfig is cluster-admin; Entra + Azure RBAC lets the CI identity be scoped `[ASSUMED]` — owner/planner decision (adds a kubelogin step to CI) |
| PostgreSQL | Flexible Server **18**, Burstable **B2s**, 32 GiB, backup retention 7 days (or more, ≤35), **public access Enabled + one firewall rule = the cluster's outbound IP**, `azure.extensions=uuid-ossp`, admin output suppressed (`--output none`) | PG 18 current minor **18.6** = dev 18.6; uuid-ossp 1.1 supported on 18 `[CITED: MS Learn concepts-supported-versions 2026-08-26; extensions-by-engine]`; BYPASSRLS for azure_pg_admin-created roles on PG16+ `[CITED: MS Learn security-access-control]`; 18 offered in uksouth `[VERIFIED: az postgres flexible-server list-skus]`. B2s `max_connections` 429 (measured on the August server) |
| Key Vault | one per env, **RBAC authorization mode**, soft delete (default), purge protection **decide deliberately** (one-way), name e.g. `jtoye-stg-kv` (available, measured) | `az keyvault check-name` → `nameAvailable: true` for `jtoye-stg-kv`, `jtoye-staging-kv`, `kv-jtoye-staging` `[VERIFIED]` |
| Managed identities | one UAMI per secret-reading workload (D-23), each with one federated credential `system:serviceaccount:jtoye-staging:<sa>`, `Key Vault Secrets User` scoped **per secret**; core-java's UAMI also carries Phase 36 Blob roles | `[CITED: MS Learn csi-secrets-store-identity-access, updated 2026-09-30]`; per-secret RBAC scope `[ASSUMED]` |
| CI identity | `jtoye-ci` (exists) or new; federated subject `repo:Bralabee/JToye_OaaS_2026:environment:staging`; GitHub env `staging` must be created (only `copilot`, `production` exist `[VERIFIED: gh api environments]`) | D-04 |
| Static IPs | ingress IP + outbound IP, Standard SKU | D-07 |
| Storage | per `docs/runbooks/azure-blob-provisioning.md` §§1–8 (Phase 36) | WORM stays UNLOCKED through the drill; human locks |

### In-cluster platform components (applied by a bootstrap script from pinned URL + sha256, outside `k8s/`)

| Component | Version (latest, measured 2026-10-18) | Artefact + sha256 (computed locally) | Notes |
|---|---|---|---|
| cert-manager | **v1.21.2** (2026-09-11) `[VERIFIED: GitHub releases API]` | `cert-manager.yaml`, 1,034,400 bytes, `e03b668ec8675214af6b0a671699d088f2601fa3878e0dbe1b41d3feafd1879f`; images `quay.io/jetstack/cert-manager-{controller,webhook,cainjector}:v1.21.2` | Install FIRST — the RabbitMQ operator manifest contains `cert-manager.io/v1` `Certificate` + `Issuer` |
| RabbitMQ cluster operator | **v2.23.1** (2026-10-13) | `cluster-operator.yml`, `ae35a547751656bc64bb734026249a845d226d96aba5a73783e05c5ec3444687`; image `ghcr.io/rabbitmq/cluster-operator:2.23.1`; operator requests **200m / 500Mi** (manifest) | Release note: upgrading the operator rolls every RabbitmqCluster — pause reconciliation first |
| RabbitMQ broker | `rabbitmq:4.3.6-management-alpine` pinned in the `RabbitmqCluster.spec.image` (= compose pin) | — | Resolves the `rabbitmq-k8s` horizon row; 4.3 community support to 2027-01-31 (`infra/dependency-horizons.yaml:552-554`) |
| Ingress controller | **Decision needed — see Open Question 1.** ingress-nginx last release `controller-v1.15.1` (2026-03-19, retired upstream); Traefik v3.7.15; AKS app-routing Gateway API | — | — |
| Keycloak | `quay.io/keycloak/keycloak:26.8.1` (= compose pin, `docker-compose.full-stack.yml:145`) | — | Hand-written Deployment/Service/Ingress; realm rendered by an init container |
| Mailpit | `axllent/mailpit:v1.31.6` (2026-10-15) `[VERIFIED: GitHub releases API]` | — | `MP_SMTP_TLS_CERT`/`MP_SMTP_TLS_KEY` + `MP_SMTP_REQUIRE_STARTTLS=true` `[CITED: mailpit.axllent.org/docs/configuration/smtp]` |
| Redis | `redis:8.10-alpine` (= compose pin) | — | Single pod, no persistence (D-24) |
| Secrets Store CSI | AKS add-on `azure-keyvault-secrets-provider` (driver upstream v1.6.1) | — | Rotation polling **off** (default) — keeps KV ops cost negligible; env-var consumers need a pod restart on rotation anyway `[CITED: MS Learn csi-secrets-store-configuration-options]` |

### Monitoring images (reuse the compose pins verbatim — D-16; Phase 39 already moved them)

| Image | Pin on main (`infra/monitoring/docker-compose.monitoring.yml`) |
|---|---|
| `prom/prometheus` | `v3.13.4` (line 35) |
| `grafana/grafana` | `13.2.4` (line 82) |
| `prom/alertmanager` | `v0.34.1` (line 112) |
| `oliver006/redis_exporter` | `v1.94.0` (line 160) |
| `prometheuscommunity/postgres-exporter` | `v0.20.1` (line 180) |

`[VERIFIED: file read]`. The archive's "keep Prometheus 2.48 / Grafana 10.2" advice is obsolete — do not carry it.

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|---|---|---|
| Cilium | Calico (`--network-policy calico`) | Supported for standard NetworkPolicy only ("AKS doesn't test or support" other features) `[CITED: use-network-policies]`; no advantage here |
| Cilium | Azure NPM | Retires 2028-09-30 on Linux; documented race with many `ipBlock` sections `[CITED]` |
| In-cluster Redis | Azure Managed Redis B0 | Does not fit the ceiling once the LB bills (§Cost); if chosen later, create with `--clustering-policy NoCluster` (allowed values `EnterpriseCluster, NoCluster, OSSCluster`, default OSSCluster) so the standalone Lettuce client never sees `MOVED` `[VERIFIED: az redisenterprise create --help]`; pin `--access-keys-auth Enabled` and `--public-network-access` explicitly (CLI warns both change in 2.92.0, Nov 2026) |
| Ephemeral OS disks | Managed 32 GiB P4 | +£14.46/mo; survives node stop (ephemeral nodes are re-imaged on stop/start) |
| Mail-scoped SSL bundle | JVM-wide truststore | Must re-merge the JDK `cacerts` or every public-TLS client breaks |

**Installation:** no npm/PyPI packages. Container images and vendored release manifests only.

---

## Package Legitimacy Audit

This phase installs **no npm, PyPI or crates packages**, so `gsd_run query package-legitimacy check` is out of domain. The equivalent provenance check for container images and manifests: does the artefact come from the project's own release channel, and was it read.

| Artefact | Registry / source | Check performed this session | Verdict | Disposition |
|---|---|---|---|---|
| `cert-manager.yaml` v1.21.2 | github.com/cert-manager/cert-manager releases | Downloaded to an isolated scratch dir, sha256 computed, image refs read | OK | Approved — pin URL + sha256 in the bootstrap script |
| `cluster-operator.yml` v2.23.1 | github.com/rabbitmq/cluster-operator releases | Downloaded, sha256 computed, kinds + image + resources read | OK | Approved — pin URL + sha256 |
| `axllent/mailpit:v1.31.6` | Docker Hub / GitHub axllent/mailpit | Latest release tag via GitHub API | OK | Approved — pin by tag (+ digest at implementation) |
| `quay.io/keycloak/keycloak:26.8.1`, `redis:8.10-alpine`, `rabbitmq:4.3.6-management-alpine`, monitoring images | already pinned in repo | Existing pins, existing horizon rows | OK | Approved (no new supply-chain surface) |
| `registry.k8s.io/e2e-test-images/agnhost:2.33` (MS recipe uses `k8s.gcr.io/...`, a redirect) | Kubernetes e2e images | Named in MS Learn's verification recipe | OK | Approved for the DPLY-05 probe only; pin a tag the registry still serves |
| Ingress controller image | depends on Open Question 1 | — | — | Pending decision |

**Packages removed due to [SLOP]:** none. **Flagged [SUS]:** none. Every new pin needs a row in `infra/dependency-horizons.yaml` (`scripts/check-dependency-horizons.sh` enforces), and `rabbitmq-k8s`'s `pin: unknown` is replaced in the same change.

---

## Architecture Patterns

### System Architecture Diagram

```
 Browser ──HTTPS──► Namecheap DNS: app-/api-/auth-/grafana-staging.olajay.co.uk ──► ONE static ingress IP
                                                                                       │
 Stripe (test) ──signed webhook──► api-staging …/api/v1/public/payments/webhook ───────┤
                                                                                       ▼
 ╔═════════════ AKS (Free tier, Cilium dataplane + policy ENFORCING, ns jtoye-staging) ═════════════╗
 ║  Azure LB ─► ingress controller (externalTrafficPolicy: Local) ─┬─► frontend:3000 (SSR)          ║
 ║                       cert-manager HTTP-01 ◄──── ACME ──────────┼─► core-java:9090 (api, SSE)    ║
 ║                                                                 ├─► keycloak:8080 (/realms, /resources only)
 ║                                                                 └─► grafana:3000                 ║
 ║  frontend ──► core-java:9090        frontend/core-java ──► keycloak:8080 (internal issuer/JWKS) ║
 ║  core-java ──► rabbitmq (5672 AMQP, 61613 STOMP) ──► operator-managed RabbitmqCluster            ║
 ║  core-java ──► redis:6379 (in-cluster, no persistence)                                          ║
 ║  core-java ──► mailpit:1025 (STARTTLS REQUIRED, cert from cert-manager CA; trust via SSL bundle) ║
 ║  prometheus ──scrape──► core-java:9091 (headless DNS SD), edge-go:8080, keycloak, rabbitmq:15692,║
 ║                        postgres-exporter:9187, redis-exporter:9121                               ║
 ║  prometheus ─► alertmanager ─┬─► Zoho SMTP smtp.zoho.eu:587 (real inbox)                         ║
 ║                              └─► mailpit:1025 (L-3 inspectable sink, same route)                 ║
 ║  every secret-reading pod: WI ServiceAccount + CSI volume ─► Key Vault ─► synced k8s Secret ─► env║
 ║  pg-backup CronJob (jtoye_backup, BYPASSRLS) ─► Blob jtoyestgbackup (WI)                         ║
 ╚═════════════════════│══════════════════════│═══════════════════════════│═══════════════════════════╝
            egress 5432 (db.port)     egress 443                    egress 443
                       ▼                      ▼                           ▼
        Flexible Server PG 18 (B2s)    Key Vault / Entra token     Blob jtoyestgmedia (uksouth),
        firewall = outbound IP only    endpoint                    jtoyestgbackup (ukwest)
        PITR → new server (drill)
 GitHub Actions ──OIDC federated credential──► Entra ──► AKS API ──► kubectl apply -k k8s/staging
```

### Recommended Project Structure (additions)

```
k8s/
├── base/
│   ├── keycloak/                 # Deployment, Service, Ingress (auth host), realm-template ConfigMap (no secrets)
│   ├── monitoring/               # prometheus/alertmanager/grafana/exporters + config ConfigMaps, generated copy of alerts.yml
│   ├── rabbitmq-cluster.yaml     # RabbitmqCluster CR (pinned image, STOMP plugin, explicit resources)
│   ├── redis.yaml                # single pod, no PVC (D-24)
│   ├── mailpit.yaml              # STARTTLS-required SMTP sink + Certificate
│   └── networkpolicies/          # 50-observability.yaml REPLACED by real policies; new 60-keycloak, 70-messaging…
├── components/azure-keyvault/    # kustomize Component: SecretProviderClasses + CSI volume patches (depth 3 → not auto-discovered as a target)
├── staging/                      # + scale-patch.yaml, client-id patch, WI client-id SA patch, KV component, noindex header
└── local/                        # + delete patches for everything local cannot run (operator CR, keycloak, CSI)
scripts/
├── azure-staging-provision.sh    # carried + adapted (WI, KV, CSI add-on, ephemeral OS, outbound IP)
├── staging-bootstrap.sh          # carried + re-pinned (cert-manager v1.21.2, operator v2.23.1, ingress)
├── staging-secrets.sh            # becomes the D-23 write-once Key Vault writer
├── staging-seed.sh               # D-26 real-flow seed (new)
├── staging-pitr-drill.sh         # carried, retargeted to PG 18
├── check-networkpolicy-enforcement.sh  # carried
├── check-alert-corpus-parity.sh  # carried
└── check-deploy-digest-parity.sh # carried
```

### Pattern 1: One rule corpus, two scrape configs, fixed job names
`check-alert-liveness.sh` maps rules to jobs through `EXPORTER_GAUGES`, `DIRECT_JOBS=("prometheus" "core-java" "edge-go" "keycloak" "rabbitmq" "rabbitmq-queues")` (`scripts/check-alert-liveness.sh:118`) and `SERVICE_JOB_MAP`, and **VOIDs on an unknown job** `[VERIFIED]`. The k8s scrape config must keep the same eight `job_name`s and `labels:`; only targets change. kustomize's `configMapGenerator` cannot read `../../infra/monitoring/...` (outside the root), so the corpus is a committed copy guarded by the carried `check-alert-corpus-parity.sh` (md5-equal on main per the salvage audit).

### Pattern 2: core-java scrape via a headless Service + `dns_sd_configs`
Main's `core-java` Service exposes only `port: 9090` (rendered staging) `[VERIFIED]`, so `core-java:9091` is refused (DEF-29-1). A plain second ClusterIP port fixes connectivity but load-balances per scrape across replicas with a constant `instance`, making `rate()` unsound (DEF-29-8). Do it once, correctly: a headless `core-java-metrics` Service (`clusterIP: None`, port 9091) + `dns_sd_configs` — job name unchanged, per-pod `instance`.

### Pattern 3: Third-party manifests outside `k8s/`
Gates discover targets with `find "$K8S_DIR" -maxdepth 2 -name 'kustomization.yaml'` (`k8s/scripts/check-render-invariants.sh:637`, `check-no-plaintext-secrets.sh:37`) and `render-golden.sh` byte-compares `TARGETS=(staging production)` (`render-golden.sh:90`) `[VERIFIED]`. Vendoring 1 MB of CRDs into a kustomization makes every upstream bump an unreviewable golden diff; apply them from the bootstrap script by pinned URL + sha256. Only our CRs (`RabbitmqCluster`, `ClusterIssuer`, `Certificate`, `SecretProviderClass`) live in kustomizations.

### Pattern 4: Key Vault → CSI → synced Secret → `secretKeyRef` (D-23)

- SPC per workload (`usePodIdentity: "false"`, `clientID: <UAMI client id>`, `keyvaultName`, `tenantId`, `objects`), plus `secretObjects` mapping each Key Vault object to the existing Secret name/key the manifests already reference — so **no `secretKeyRef` in base changes**, and `check-no-plaintext-secrets.sh` stays green (a SecretProviderClass is not `kind: Secret`).
- **Sync lifetime:** "When you delete the pods that consume the secrets, your Kubernetes secret is also deleted" `[CITED: csi-secrets-store-configuration-options]`. Consequences: (a) the RabbitMQ operator's `secretBackend.externalSecret` and the pg-backup CronJob reference Secrets that would not exist between pods; (b) recommend **one tiny always-on `secret-sync` Deployment** (pause image, own WI SA, mounts the SPCs whose Secrets are consumed by operator-managed pods and CronJobs) so those Secrets persist `[ASSUMED design]`.
- **Key Vault names** allow alphanumerics and dashes only `[ASSUMED]`: derive `<secret>--<key>` with `.`/`_` → `-` (e.g. `rabbitmq-credentials--default-user-conf`), and make the generator assert the mapping is injective.
- **Derived name list** = every `secretKeyRef` in the rendered build. Measured on main: **23 unique (name, key) pairs, 26 references** in `kubectl kustomize k8s/staging` (render byte-equal to `k8s/goldens/staging.yaml`) `[VERIFIED]`: `client-ip-credentials/hmac-key`, `dsar-credentials/encryption-key`, `keycloak-credentials/{admin-password,admin-username,frontend-client-secret}`, `nextauth-secret/secret`, `notification-credentials/unsubscribe-signing-secret (optional)`, `postgres-credentials/{backup-password,backup-username,database,host,password,port,username}`, `rabbitmq-credentials/{password,stomp-login,stomp-passcode,username}`, `redis-credentials/password`, `smtp-credentials/{password,username} (optional)`, `stripe-credentials/{api-key,webhook-secret} (optional)`. **Add this phase:** `postgres-credentials/{migration-username,migration-password}` (Blocker E), `rabbitmq-credentials/default_user.conf` (DEF-29-4 — the operator projects exactly that key), the Keycloak DB password, the realm client secrets the template needs (`KEYCLOAK_CLIENT_SECRET`, `EDGE_API_CLIENT_SECRET`, `INTEGRATION_*` — or strip those clients from the staging realm), Grafana admin, the four `ALERTMANAGER_SMTP_*`, the D-27 test-vendor password.
- **Optional refs are a trap:** with `optional: true`, an absent sync starts the pod silently unconfigured (Stripe off, SMTP unauthenticated). The preflight must treat every derived name as REQUIRED for staging.

### Pattern 5: Split-horizon issuer in staging (avoid the LoadBalancer hairpin)
The mechanism already exists: `keycloak.issuer.uri` locates JWKS (core-java `KC_ISSUER_URI`, frontend `KEYCLOAK_ISSUER_INTERNAL`), `keycloak.public.issuer.uri` is the stamped `iss` (core-java `JWT_EXPECTED_ISSUER`, frontend `KEYCLOAK_ISSUER`) — `k8s/base/configmap.yaml:218-231`, `core-java-deployment.yaml:203-207, 359-363`, `frontend-deployment.yaml:231-240` `[VERIFIED]`. Staging today sets both to the public `https://auth-staging.olajay.co.uk/realms/jtoye-staging`. Under Cilium, pod → own LoadBalancer IP is rewritten before policy evaluation; MS Learn: "network policies might not properly process traffic being received from or sent to an external service" through a LoadBalancer `[CITED: use-network-policies]`, and the `0.0.0.0/0 except RFC1918` 443 rule would then not match the translated in-cluster destination `[ASSUMED]`. **Recommend** `keycloak.issuer.uri: http://keycloak.jtoye-staging.svc.cluster.local:8080/realms/jtoye-staging` (internal) with the public value unchanged, mirroring compose (`docker-compose.full-stack.yml:327-328`), plus Keycloak `KC_HOSTNAME=https://auth-staging.olajay.co.uk` so `iss` stays public. Prove it in the DPLY-05 arms (frontend → keycloak allowed; unlabelled pod → keycloak denied).

### Anti-Patterns to Avoid
- Editing `infra/monitoring/prometheus/alerts.yml` "for k8s" — one corpus (D-16); use `DORMANT_RULES` or Alertmanager muting.
- Adding `kind: Secret` to any kustomization, or a connection string/SAS anywhere (INV-9).
- Fixing core-java's missing `AZURE_CLIENT_ID` with a manifest env var — the WI webhook injects it (Phase 36 handoff §3).
- `kubectl apply -f k8s/base/...`; hand-editing goldens; letting the `replicas:` transformer stand in for HPA/PDB control.
- Running any `kubectl` without `--context` (a **stale `jtoye-staging` context** pointing at the deleted cluster exists on this host `[VERIFIED: kubectl config get-contexts]`; `az aks get-credentials --overwrite-existing` must replace it, and scripts must assert the context's server URL matches the new cluster).

---

## Blockers on `main` (each needs its own fail arm)

### Blocker A — `keycloak.client-id: "frontend"` names a client the realm does not have (DPLY-01)
`k8s/base/configmap.yaml:255`: `keycloak.client-id: "frontend"`; `k8s/staging/configmap-patch.yaml:48-55` deliberately does not override it `[VERIFIED]`. The realm template's non-builtin clients are exactly `core-api`, `edge-api`, `integration-catalog-ro`, `integration-orders-rw` `[VERIFIED: node over realm-export.template.json]`. Patch `keycloak.client-id: "core-api"` in staging (what `k8s/local` does). Fail arm: point it at `does-not-exist` and watch the login proof go red (`invalid_client`).

### Blocker B — `check-alert-liveness.sh` cannot run against k8s (DPLY-03 literal criterion)
13 `docker` references; L-0 does `docker inspect "$PROM_CONTAINER"` / `docker exec … md5sum` and VOIDs otherwise (`scripts/check-alert-liveness.sh:204-212`); L-3 requires `MAILHOG_URL="${MAILHOG_URL:-http://localhost:8025}"` (`:80`) and `$MAILHOG_URL/api/v2/search` (`:358, :440`) `[VERIFIED]`. `check-alert-metrics.sh` has 0 docker references. Fixes: carry the branch's `PROM_EXEC` (default `docker exec`, override `kubectl --context … exec -n jtoye-staging deploy/prometheus --`); give L-3 a sink abstraction (`SINK=mailhog|mailpit`) because Mailpit's API is not Mailhog's (`/api/v1/...` — exact search path `[ASSUMED]`, read it off the instance's swagger); and render Alertmanager's single receiver with **two `email_configs`** — Zoho (real) and Mailpit (inspectable) — so the probe travels the real route (all integrations in a receiver are notified `[ASSUMED]`; L-3 itself proves the Mailpit half, the owner confirms the inbox half once). Mailpit requires STARTTLS → Alertmanager's Mailpit `email_config` needs `tls_config.ca_file` for the cluster CA.

### Blocker C — managed-PG prerequisites (DPLY-04, DPLY-01)
PG ≥ 16 for BYPASSRLS (CITED above) — recommend 18. `uuid-ossp` in `azure.extensions` before V1 (and pre-create it as the admin — the admin is not superuser; creating it as `jtoye_app` depends on CREATE-on-database and trusted-extension handling `[ASSUMED]`). Azure reserves 15 connections; `k8s/scripts/check-connection-math.sh:81` hardcodes `RESERVED=3 # superuser_reserved_connections` and reads `max_connections` from compose (`:56`) `[VERIFIED]` — D-25 already decides per-target config. **New:** the host's `psql/pg_dump/pg_restore` are **16.15** `[VERIFIED]`; `pg_dump` refuses a newer server major, and `pg_restore` cannot read an 18 archive with a 16 client — run every drill step through the `ghcr.io/bralabee/jtoye-pg-backup:18-blob` image (PG 18 client, `k8s/base/pg-backup-cronjob.yaml:54`) or a `postgres:18` container, never the host binaries.

### Blocker D — NetworkPolicy egress has no rule for out-of-cluster/new endpoints (DPLY-01, DPLY-05)
`20-core-java.yaml` egress: kube-dns; `jtoye-infrastructure` namespace on 5432 (replaced from `db.port`) and 6379/5672/61613/9093; `0.0.0.0/0 except 10/8,172.16/12,192.168/16` on **443 only** `[VERIFIED]`. A public-access Flexible Server is a public IP on 5432 → denied. The `jtoye-infrastructure` namespace will not exist (RabbitMQ, Redis, Keycloak, Mailpit live in `jtoye-staging`). Also `k8s/base/configmap.yaml:16,138,142,178` point `keycloak.admin.base-url`, `redis.host`, `rabbitmq.host`, `stomp.broker.relay-host` at `*.jtoye-infrastructure.svc.cluster.local` (DEF-29-5) — staging must override all four. Postgres egress: `0.0.0.0/0 except RFC1918` on `db.port` only (the server's public IP can change; a /32 is fragile `[ASSUMED]`); keep `INV-7` (`NETPOL_INFRA_EXPECTED`, `check-render-invariants.sh:470`) in lockstep and re-introduce the branch's `NETPOL_IPBLOCK_EXPECTED` second arm.

### Blocker E — the Phase 28 migrator/runtime split is not wired in k8s (DPLY-01) — NEW
`k8s/scripts/check-env-contract.sh:165`: `DB_MIGRATION_USER|Reviewed omission … the k8s cluster still connects as a single role and adopts the split with the Phase 29 deploy work (DPLY), at which point a distinct migration-username secret key is wired here alongside an in-cluster jtoye_runtime role.` `[VERIFIED]`. `rg -uu 'MIGRATION' k8s/` finds only docs/the allowlist. `DatabaseConfigurationValidator` refuses a DB user that owns any public table (`validateNotTableOwner`, "set DB_USER to the non-owner runtime role (jtoye_runtime)… Flyway keeps the owner/migrator role via DB_MIGRATION_USER") `[VERIFIED]`. So on a fresh managed DB: `DB_USER=jtoye_app` → refused; `DB_USER=jtoye_runtime` without `DB_MIGRATION_USER` → Flyway runs as the DML-only role and fails. **Fix:** add `DB_MIGRATION_USER/PASSWORD` env from new `postgres-credentials` keys, remove the two allowlist entries (so the env-contract gate now *requires* them), and order the role bootstrap: (1) admin creates `jtoye_app` (owner) and `jtoye_runtime` (with `ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app …`) **before** first boot; (2) first core-java boot migrates as `jtoye_app` (V64's guarded `GRANT TRUNCATE ON postcode_centroid` fires because the role exists); (3) re-run `infra/db/create-runtime-role.sql` and `infra/backups/create-backup-role.sql` (idempotent). `create-runtime-role.sql:84` `GRANT TRUNCATE ON postcode_centroid TO jtoye_runtime;` fails on an empty DB, which is why step 1 cannot simply run that file `[VERIFIED: file read]`. Azure note: the admin is `azure_pg_admin` (pseudo-superuser), which "can manage roles and access objects owned by any nonrestricted role" `[CITED: security-access-control]`; `public` schema is owned by `azure_pg_admin` on every version `[CITED]`.

### Blocker F — `core-java-allow` ingress denies the ingress controller and the scrape (DPLY-01, DPLY-03, DPLY-05) — NEW
`k8s/base/networkpolicies/20-core-java.yaml:39-61`: ingress from `podSelector app: frontend` / `app: edge-go` on `port: 9090`, and from namespaces `monitoring` / `jtoye-infrastructure` on `port: 9090` — **no rule for the `ingress-nginx` namespace**, and **no 9091** `[VERIFIED]`. The rendered Ingress routes `api-staging.olajay.co.uk` and `/api/v1/orders/stream` to `core-java:9090` `[VERIFIED: staging render]`, and browsers call the API host directly (`NEXT_PUBLIC_API_URL` is inlined into client code: `frontend/lib/api-client.ts:30`, `hooks/use-stomp.ts:9`). Under enforcement every browser API call and the SSE stream are dropped, and Prometheus (on 9091) is dropped too. The branch never fixed this (its `20-core-java.yaml` ingress is identical). Add an ingress rule from the controller namespace on 9090 and from the Prometheus pods on 9091; update `networkpolicies/README.md`'s matrix. Fail arm: the DPLY-05 probe from the controller namespace to core-java must TIMEOUT before the fix and connect after.

### Blocker G — staging runs the `prod` profile, so the staging smoke test cannot pass (#99, DPLY-02) — NEW
`ci-cd.yaml:1847`: `EXPECT_SWAGGER=true ./scripts/smoke-test.sh https://api-staging.olajay.co.uk` with the comment "Staging exposes Swagger (application-staging.yml)" — but k8s runs `prod` (Blocker table above), where `springdoc.api-docs.enabled: ${SWAGGER_ENABLED:false}` (`application-prod.yml:151-155`) `[VERIFIED]`; and even under the `staging` profile the docs are excluded for deployed profiles (`docs/security/PENTEST-TRIAGE.md:215-227`, #549 closed). So the first real staging deploy fails its smoke test and rolls back healthy pods (`ci-cd.yaml` "Rollback on failure"). Fix: `EXPECT_SWAGGER=false` for staging (asserting the docs are NOT served — preserving #549), and record that k8s staging deliberately runs `prod` for parity. Also: the job pins `kubectl` **v1.28.0** (`ci-cd.yaml` setup-kubectl step) — eight minors behind a 1.36 cluster, outside kubectl's ±1 skew policy `[ASSUMED policy]`; bump with the cluster. `secrets.KUBE_CONFIG_STAGING` (`ci-cd.yaml:1765`) does not exist (`gh secret list` empty) and is replaced by `azure/login` OIDC.

### Blocker H — the realm template is dev-shaped (DPLY-01, security) — NEW
`infra/keycloak/realm-export.template.json:3` `"realm" : "jtoye-dev"`; `core-api` `redirectUris` are all `http://localhost:*` / `http://app.jtoye.local/*` and `webOrigins` `[ "*" ]` (line 687; `edge-api` 784); four **seed users** (`admin-user`, `tenant-a-user`, `tenant-b-user`, `admin-user-b`, tenant ids `…0001/…0002`) share `${KC_SEED_USER_PASSWORD}`; `smtpServer` = `mailhog:1025`, no TLS `[VERIFIED]`. Staging must import a realm named `jtoye-staging` (the overlay's issuer and `keycloak.admin.realms`), with staging `redirectUris`/`webOrigins`, **no seed users** (D-27: only the scripted test vendor can sign in to a public host), SMTP → Mailpit with STARTTLS, and `core-api` `serviceAccountsEnabled: false` (already false in the template — D-02 holds). Recommend a jq transform in the Keycloak init container over the ONE template (`.realm`, `.users |= map(select(.username|startswith("service-account-")))`, clients' URIs, `.smtpServer`), followed by assertions, instead of a second committed realm file. Expose only `/realms/` and `/resources/` on `auth-staging` (keep `/admin` cluster-internal; the seed script uses a port-forward) `[ASSUMED hardening]`.

---

## DPLY-02 — measured per-issue dispositions

States measured with `gh issue view` on 2026-10-18 `[VERIFIED]`.

| # | State | Still real on main? (evidence) | Recommended disposition |
|---|---|---|---|
| **#99** deploy gate is theatre | OPEN | Partly fixed in Phase 26 (real branch gate `ci-cd.yaml:1641`, full-sha pin + render assertion, kustomize apply). Still real: never run; `secrets.KUBE_CONFIG_STAGING` absent; `vars.FRONTEND_PUBLIC_API_URL` = `https://api.olajay.co.uk` (production) so the CR-02 guard (`:1693-1718`) fails staging; Blocker G smoke test; kubectl v1.28 pin | **Fix in phase.** Close on one green CI deploy run + its fail arm (a deliberately wrong `FRONTEND_PUBLIC_API_URL` must fail the guard) |
| **#100** sealed secrets half-landed | **CLOSED 2026-10-18 by docs PR #955** (lexical "Closes #100/#300") | Real: no SecretProviderClass anywhere (`rg -uu -l 'SecretProviderClass|secrets-store.csi' k8s scripts infra` rc=1, control rc=0) | **Reopen**, then close with the D-23 evidence (pods reading KV via CSI; derived-name preflight fail arm) |
| **#292** one frontend image per env | CLOSED 2026-08-04 | Mechanism: CR-02 guard asserts the baked origin equals the overlay's `api.url`; only ONE environment can deploy per commit | **Already closed — record**: Phase 29 builds the staging-origin image (production deploy off, D-08); per-environment images are a **Phase 32** prerequisite (record the reason against #292 in the phase SUMMARY) |
| **#293** CSP omits the IdP origin | CLOSED 2026-08-04 (#521) | `NEXT_PUBLIC_KEYCLOAK_URL` is injected at runtime in the frontend render `[VERIFIED]` | **Verify on the live ingress**: served CSP must contain `https://auth-staging.olajay.co.uk` in `connect-src`/`form-action`; a source with a path matches exactly unless it ends `/` (`trap_csp_realm_path_matching`) |
| **#299** customer realm unconfigured | OPEN | `check-env-contract.sh:163-164` allowlist "OPEN DEFECT #299"; repo var `FRONTEND_PUBLIC_CUSTOMER_KEYCLOAK_URL` = `https://auth.olajay.co.uk/realms/jtoye-customers` (production host, NXDOMAIN) | **Recommend fix in phase for staging**: the realm-render init container imports `realm-export-customers.template.json` too (same Keycloak), wire `CUSTOMER_KC_ISSUER_URI`/`CUSTOMER_JWT_EXPECTED_ISSUER`, set the build var to the staging host, and add the **#859** deploy-time check (`CUSTOMER_JWT_REQUIRE_VERIFIED_EMAIL` must be true outside dev; default `true` at `application.yml:442`, `false` at `application-dev.yml:36`). If descoped: defer with reason "customer sign-in button inert on staging", never half-wire |
| **#301** no mcp-server manifests | OPEN | `rg -uu 'image:.*mcp' k8s` rc=1 | **Defer with reason**: new pool in the connection budget, new scoped client, and a public-exposure decision that belongs to Phase 32; not on the DPLY-01 path |
| **#302** logback FileAppender | CLOSED 2026-08-03 (#473) | emptyDir at `/var/log/jtoye` in the render (`core-java-deployment.yaml:724`) `[VERIFIED]` | **Already closed** — regression check: no `FileNotFoundException` in the first staging boot log |
| **#271** egress vs `DB_PORT` | CLOSED 2026-08-04 (#521) | `replacements:` in `k8s/staging/kustomization.yaml:97-112` `[VERIFIED]`; class recurs (Blockers D/F, Redis port) | **Already closed** — extend the same mechanism; do not reopen |
| **#300** sealed/external secrets, local path | OPEN | Local still sources `.env` (`scripts/k8s-local-secrets.sh`) | **Close as superseded for deployed environments by D-23**, local path accepted with reason (single-user dev host, compose canonical) |
| **#304** stomp-relay.spec.ts | OPEN | Four structural mismatches unchanged | **Defer with reason**: a design task (auth helper, order path, wait strategy, assert-ran) not on the DPLY-01 path; the in-cluster broker this phase ships unblocks it. Optional stretch if capacity allows |
| **#592** one-click unsubscribe unwired | OPEN | `rg -uu 'ONE_CLICK' k8s` rc=1; `application.yml:912` `one-click-base-url: ${NOTIFICATION_UNSUBSCRIBE_ONE_CLICK_BASE_URL:}` (control rc=0) `[VERIFIED]` | **Fix in phase** (cheap): env beside `NOTIFICATION_UNSUBSCRIBE_BASE_URL` (`core-java-deployment.yaml:463`) from a new app-config key = overlay `api.url`; extend `UnsubscribeLinkRoutingTest` to read the manifests and show it FAIL first |
| **#294** SES + bucket unverified | OPEN | Bucket half superseded by Phase 36 (Blob) | **SES half → Phase 32** (D-13 reason); **bucket half closed** with the #626 read-back against `jtoyestgmedia` |
| **#98** observability demo-grade | OPEN | No monitoring manifests in `k8s/` (only a doc mention of `prom/prometheus` at `k8s/DEPLOYMENT.md:679`) | **Close criteria 1–2** (all three services scraped in a prod-shaped deploy; every alert references an emitted metric, via `check-alert-metrics.sh`); **defer criterion 3** (log aggregation + tracing) with reason (D-16 scope) |
| **#112** ops readiness | OPEN | — | **D-18 minimal close**: paging path (Alertmanager → real inbox) + one runbook page |
| #101 PITR | OPEN | `SYSTEM_DESIGN_V2.md:858-872` already marks WAL-G as superseded TARGET `[VERIFIED]` | Close on the two-arm drill; record that Burstable has **no zone-redundant HA** `[ASSUMED]` → DB HA defers to the Phase 32 production SKU |
| #297 enforce NetworkPolicies | OPEN | — | Close on the captured denial (Cilium), retargeted from minikube with a reason |
| #296 in-cluster Keycloak ingress | OPEN | — | Close when the auth host rule + TLS returns together with the Service/Deployment (INV-6 enforces order) |
| #859 (UXT-090) verified-email deploy check | OPEN, homed to Phase 29 by `ISSUE-DISPOSITION.md` | — | Fold into the deploy preflight (see #299) |

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---|---|---|---|
| TLS issuance/renewal | ACME scripts | cert-manager `ClusterIssuer` + `Certificate` | Renewal, solver pods, ordering |
| Internal CA for Mailpit | openssl scripts | cert-manager `selfSigned` → CA `Certificate` → CA `Issuer` | `ca.crt` lands in the issued Secret; rotation is cert-manager's |
| JVM trust for one SMTP host | custom `TrustManager`, `mail.smtp.ssl.trust` | Boot `spring.ssl.bundle.pem.<name>.truststore.certificate` + `spring.mail.ssl.bundle` | Verified: sets the STARTTLS socket factory and `checkserveridentity=true` |
| Broker StatefulSet | hand StatefulSet | RabbitMQ cluster operator | STOMP plugin via `additionalPlugins`, credentials via `externalSecret` (`default_user.conf` key) |
| PITR | WAL-G/pgBackRest | Flexible Server automated backups + PITR | ADR-0002 |
| Secret delivery | sealed-secrets, ESO, env files | AKS `azure-keyvault-secrets-provider` add-on + WI | D-23; managed add-on |
| Azure auth in CI | kubeconfig/SP secret | `azure/login` with federated credential (`id-token: write`) | D-04; subjects exact-match, no wildcards |
| Denied-connection probe | tcpdump | agnhost `connect --timeout=3s` (`TIMEOUT` = denied, silence = allowed) | Microsoft's own recipe, control arm first `[CITED]` |
| Notification mute for staging | new route logic | existing `ALERTMANAGER_MUTE_ALERTNAMES` (`infra/monitoring/alertmanager/entrypoint.sh:75-140`) | Keys on alertname only, so it cannot swallow the L-3 probe |

**Key insight:** the genuinely new build work here is the repo defects and the proof scripts; almost every infrastructure "build it" temptation has a managed or upstream answer the project has already ruled for.

---

## Runtime State Inventory (new environment)

| Category | Items Found | Action Required |
|---|---|---|
| Stored data | Managed PG starts empty; Flyway (as `jtoye_app`) builds the schema; `postcode_centroid` (1,748,230 rows) imports at startup; Keycloak needs its own `keycloak` database; seed rows exist nowhere until D-26 runs | Create databases + roles before first boot (Blocker E ordering); run the seed script; record row counts |
| Live service config (not in git) | Namecheap DNS A records (×4); Azure resources and server parameters (`azure.extensions`, firewall rule); Key Vault contents; GitHub `staging` environment, its vars (`DEPLOY_STAGING_ENABLED`, `FRONTEND_PUBLIC_*`) and the Azure ids; Stripe test-dashboard webhook endpoint + signing secret; the Keycloak realm after import (KC is DB-backed: re-import needs `--override true`, which deletes users not in the template) | Each in the D-18 runbook page with the command that created it; no plan step may assume a value it did not create |
| OS-registered state | None on hosts. A **stale `jtoye-staging` kube context** points at the deleted cluster; `~/.config/jtoye/azure-idle-stop.conf` lists idle-stop PG servers (memory) | Overwrite the context and assert its server; staging is always-on, so it must NOT be added to the idle-stop conf |
| Secrets / env vars | Project `.env` holds `ALERTMANAGER_SMTP_*` (memory); `DSAR_ENCRYPTION_KEY` / `CLIENT_IP_HMAC_KEY` exist locally only for compose; nothing exists for staging; `~/.jtoye/staging-*.env` files from August are for a deleted server | The D-23 writer generates staging values write-once into Key Vault; external values via one documented command each |
| Build artefacts | GHCR `jtoye-{core-java,frontend,edge-go,pg-backup}` are **public** `[VERIFIED: gh api packages]`; tags per commit (`29facb94…`); the frontend image bakes `NEXT_PUBLIC_*` at build | No pull secret; the staging frontend image must be built with staging `FRONTEND_PUBLIC_*` |

---

## Common Pitfalls

### Pitfall 1: Staging `replicas: 2` is overridden by base HPA floors
Rendered staging HPAs: `core-java-hpa minReplicas: 3 / maxReplicas: 10`, `edge-go-hpa 5 / 20`, `frontend-hpa 3 / 10`; PDBs `minAvailable` 2/3/2 `[VERIFIED: staging render]`. No `k8s/staging/scale-patch.yaml` on main. Create it (mirror `k8s/local/scale-patch.yaml`: HPA min 1, PDB min 1, lower max) or nothing else schedules on 3 × B2s.

### Pitfall 2: Capacity on 3 × B2s
Measured in August on the same SKU/dataplane: allocatable **1900m / 2854308Ki per node**, system overhead **1524m / 2212Mi** across three nodes `[VERIFIED: archive 29-PROVISIONING-EVIDENCE §3]`. Estimated requests for app + platform after the scale patch ≈ **2.5 vCPU / 4.6 GiB** (core-java 500m/1Gi, Keycloak ~500m/1Gi, RabbitMQ 250m/512Mi override, operator 200m/500Mi, Prometheus 200m/512Mi, the rest small) `[ASSUMED except core-java and operator]` vs ~4.0 vCPU / 5.7 GiB free → fits at ~62% CPU / ~80% memory; a core-java rolling update briefly adds 1 Gi. Set the RabbitmqCluster's resources explicitly (operator defaults are large `[ASSUMED]`). Re-measure `kubectl describe node` on the new cluster before trusting it. B-series CPU is burstable — JVM start-up and Flyway may exhaust credits.

### Pitfall 3: `configuration-snippet` headers silently dropped
`k8s/base/ingress.yaml` sets HSTS/XFO/nosniff/Referrer/Permissions via `configuration-snippet` `[VERIFIED: render]`; ingress-nginx ≥1.9 defaults `allow-snippet-annotations: false` `[ASSUMED]`. Falsify directly: `curl -sI https://app-staging.olajay.co.uk` must show `strict-transport-security`.

### Pitfall 4: One shared TLS secret for api+app
Base issues `api` and `app` in one `secretName` (staging `jtoye-staging-tls`) — one ACME order; a failed challenge fails both. CONTEXT D-07 says per-host certs: split into per-host `tls` entries/secrets, add DNS records before SANs, and use a Let's Encrypt **staging** issuer first (base hardcodes `cert-manager.io/cluster-issuer: "letsencrypt-prod"`, `ingress.yaml:8`, `sse-ingress.yaml:17`).

### Pitfall 5: `NoOrdersCreated` will email the real inbox twice a day
Severity `info`, `for: 30m`, `repeat_interval: 12h` (`alerts.yml:187-194`, `alertmanager.yml.tmpl`) `[VERIFIED]`. On quiet staging it fires once the D-26 order is 30 minutes old. Use `ALERTMANAGER_MUTE_ALERTNAMES=NoOrdersCreated` (alertname-only matcher, so L-3's `severity=info` probe is unaffected) and record the choice. `seed-order-metric.sh` is the M-1 remedy after a core-java restart (`trap_rebuild_reds_alert_metrics`).

### Pitfall 6: JVM rules bind to Keycloak's JVM
`HighMemoryUsage`/`FrequentGarbageCollection` use unqualified `jvm_*`; Keycloak is scraped in-cluster for the first time. L-2b exists to catch this — read its output.

### Pitfall 7: Keycloak 26 metrics port
Compose uses `KC_LEGACY_OBSERVABILITY_INTERFACE: "true"` to keep `/metrics` on 8080 (`docker-compose.full-stack.yml:162`) `[VERIFIED]`; that switch is deprecated in KC 26 and the native endpoint is the management port 9000 `[ASSUMED]`. Either keep the legacy flag (and record it) or scrape 9000 — the job name stays `keycloak`.

### Pitfall 8: PITR creates a second billable server
Restore always creates a new server; parameters/firewall may not carry; Burstable has no on-demand backup (restore to a timestamp) `[ASSUMED — carried from 2026-08-10 research, re-read the doc at implementation]`. The carried drill script traps the delete.

### Pitfall 9: Workload Identity + `automountServiceAccountToken: false`
Phase 36 SAs set it false (`k8s/base/serviceaccounts.yaml`); A6 must prove the projected `azure-identity-token` exists and the API token does not. The CSI driver obtains its own token per mount, independent of automount `[ASSUMED]`.

### Pitfall 10: `security.trusted-proxies` stays `""`
`k8s/staging/configmap-patch.yaml:81-90` `[VERIFIED]`. With Cilium overlay, the ingress pod CIDR is the cluster pod CIDR (overlay default `10.244.0.0/16` `[ASSUMED]`) — measure it, set `externalTrafficPolicy: Local` on the controller Service, then declare a /16 or narrower (INV-12 refuses /0, < /8, public ranges).

### Pitfall 11: CSI-synced Secrets vanish between pods
See Pattern 4. The RabbitMQ operator and the pg-backup CronJob reference Secrets that only exist while some pod mounts the SPC; the `secret-sync` Deployment pattern prevents a broker that cannot restart and a nightly backup that `CreateContainerConfigError`s.

### Pitfall 12: The CI identity's AKS permissions
`kubectl apply -k k8s/staging` creates a Namespace (cluster-scoped) and CRD instances. Decide the RBAC (local accounts → effectively cluster-admin; Entra + Azure RBAC → scoped role + kubelogin).

---

## Code Examples

### D-20 literal + D-21 bundle (core-java)

```yaml
# application-prod.yml AND application-staging.yml (D-20: literals, not env-defaulted)
spring:
  mail:
    properties:
      mail:
        smtp:
          starttls:
            enable: true
            required: true
```

```yaml
# staging only, via app-config → env (NOT in application-prod.yml: production SES uses public CAs)
# SPRING_MAIL_SSL_BUNDLE=cluster-ca
# SPRING_SSL_BUNDLE_PEM_CLUSTERCA_TRUSTSTORE_CERTIFICATE=file:/etc/jtoye/cluster-ca/ca.crt
# Source: spring-boot-mail-4.1.1 metadata — spring.mail.ssl.bundle sets mail.(protocol).ssl.socketFactory;
#         "the STARTTLS command can use the corresponding SSLSocketFactory, even if the
#          'mail.(protocol).ssl.enable' property is not set"; spring.mail.ssl.verify-hostname (default true)
#          sets mail.smtp.ssl.checkserveridentity=true.
# The nested key name under spring.ssl.bundle.pem.<name>.truststore.certificate is [ASSUMED] (map-typed property).
```

The test extends the `MailStartTlsPropertyTest` shape (`core-java/src/test/java/uk/jtoye/core/notification/MailStartTlsPropertyTest.java` loads the shipped `application.yml` through `MailSenderAutoConfiguration` and asserts `JavaMailSenderImpl.getJavaMailProperties()`): load `application.yml` + `application-prod.yml` (and `-staging.yml`) and assert `mail.smtp.starttls.required == "true"`, RED first; a second case asserts that with `SPRING_MAIL_SSL_BUNDLE` set, `mail.smtp.ssl.socketFactory` is present and `mail.smtp.ssl.checkserveridentity == "true"`.

### Mailpit with required STARTTLS

```yaml
# Source: mailpit.axllent.org/docs/configuration/smtp
env:
  - { name: MP_SMTP_TLS_CERT, value: /tls/tls.crt }
  - { name: MP_SMTP_TLS_KEY,  value: /tls/tls.key }
  - { name: MP_SMTP_REQUIRE_STARTTLS, value: "true" }
# /tls is the cert-manager Certificate Secret; dnsNames must include the exact SMTP_HOST core-java dials
```

### SecretProviderClass (workload identity) with sync

```yaml
# Source: learn.microsoft.com/azure/aks/csi-secrets-store-identity-access + csi-secrets-store-configuration-options
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata: { name: core-java-kv }
spec:
  provider: azure
  parameters:
    usePodIdentity: "false"
    clientID: "<core-java UAMI client id>"
    keyvaultName: "<staging vault>"
    tenantId: "<tenant id>"
    objects: |
      array:
        - |
          objectName: dsar-credentials--encryption-key
          objectType: secret
  secretObjects:
    - secretName: dsar-credentials
      type: Opaque
      data:
        - { key: encryption-key, objectName: dsar-credentials--encryption-key }
# pod: serviceAccountName with azure.workload.identity/client-id annotation, label azure.workload.identity/use: "true",
# volume csi { driver: secrets-store.csi.k8s.io, readOnly: true, volumeAttributes: { secretProviderClass: core-java-kv } }
```

### DPLY-05 denial probe (control arm first)

```bash
# Source: learn.microsoft.com/azure/aks/use-network-policies (verification recipe)
# CONTROL: from a pod labelled app=frontend → core-java:9090 must connect (no output).
# DENIED : from an unlabelled pod in jtoye-staging → core-java:9090 must print TIMEOUT.
# BLOCKER-F ARM: from the ingress controller namespace → core-java:9090 must TIMEOUT before the
#                fix and connect after it; the same pair for prometheus → core-java:9091.
/agnhost connect "$TARGET_IP:$PORT" --timeout=3s --protocol=tcp
```

### GitHub Actions → Azure OIDC

```yaml
# Source: docs.github.com OIDC-in-Azure + Azure/login
permissions: { id-token: write, contents: read }
steps:
  - uses: azure/login@v2
    with: { client-id: ${{ vars.AZURE_CLIENT_ID }}, tenant-id: ${{ vars.AZURE_TENANT_ID }}, subscription-id: ${{ vars.AZURE_SUBSCRIPTION_ID }} }
  - run: az aks get-credentials --subscription "$SUB" -g jtoye-rg -n <cluster> --overwrite-existing
# Federated subject (exact match): repo:Bralabee/JToye_OaaS_2026:environment:staging
```

### Stripe TEST webhook (D-28)
Endpoint: `https://api-staging.olajay.co.uk/api/v1/public/payments/webhook` — `@RequestMapping({"/public/payments", "/api/v1/public/payments"})` + `@PostMapping("/webhook")` (`PaymentController.java:24, 40`); verification `Webhook.constructEvent(payload, sigHeader, stripeProperties.getWebhookSecret())` → on `SignatureVerificationException` → `IllegalArgumentException("Invalid Stripe signature")` → **400** (`PaymentService.java:372-375`, `PaymentController.java:45-55`) `[VERIFIED]`. Register the **versioned** path in a fresh test dashboard. Positive arm: a signed test event (e.g. `stripe trigger payment_intent.succeeded`) → 200 and a `processed_stripe_events` row; negative arms: unsigned and forged-signature POSTs → 400 and no row.

---

## One-way (and costly) doors

| Decision | Door | Why | When it binds |
|---|---|---|---|
| AKS network plugin mode (overlay) and pod CIDR | One-way | Cannot change in place; re-create cluster | `az aks create` |
| Network dataplane/policy engine (Cilium) | One-way in practice | Moving off Cilium is not a supported in-place path `[ASSUMED]`; engines can't be swapped casually | `az aks create` |
| Ephemeral vs managed OS disk | Per node pool | New node pool to change | create |
| AAD + Azure RBAC / local accounts | Reversible but disruptive | Changes every kubeconfig and the CI auth path | create (or update) |
| PostgreSQL major version (18) | One-way (upgrade only) | No downgrade; in-place major upgrade exists upward | server create |
| PG public vs private access | One-way | Cannot switch after create; PITR cannot cross the boundary | server create |
| PG admin login name | One-way | Immutable after create | server create |
| Key Vault name + purge protection | One-way | Globally unique; purge protection cannot be disabled once on and holds the name through retention | vault create |
| Storage account names (`jtoyestgmedia`, `jtoyestgbackup`) | One-way | Global; Phase 36 rule: a taken name is an owner decision, not a substitute | account create |
| WORM time-based retention LOCK | One-way | Cannot be shortened/removed until retention lapses — human step after the drill | runbook §4.4 |
| DNS hostnames (`*-staging.olajay.co.uk`) | Costly | Baked into realm redirect URIs, CSP, cookies, CORS, Stripe webhook, frontend image | first deploy |
| Federated credential subjects (namespace/SA names, `environment:staging`) | Costly | Renaming breaks auth silently | identity setup |
| Static ingress IP | Costly | DNS points at it; deleting releases the address | create |
| Managed Redis clustering policy (if ever chosen) | One-way | "Specified at create time" `[VERIFIED: CLI help]` | create |

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|---|---|---|---|
| ingress-nginx | **Retired upstream**; last release `controller-v1.15.1` 2026-03-19 `[VERIFIED: GitHub API]`; AKS app-routing managed NGINX loses Azure support **after November 2026**; successor = app-routing Gateway API (`--enable-gateway-api --enable-app-routing-istio`) with no rate limiting / header-size limits / EnvoyFilter, istiod and each gateway at min 2 replicas `[CITED: learn.microsoft.com/azure/aks/app-routing-gateway-api, updated 2026-10-08]` | 2025-11 → 2026-11 | Open Question 1 |
| Azure Cache for Redis | Creation blocked for existing customers (CONTEXT: from 2026-10-01; the August `az redis create` already returned `BadRequest … is retiring`); Azure Managed Redis supports **non-clustered** instances on Balanced `[CITED: learn.microsoft.com/azure/redis/overview]` | 2026 | D-24 → in-cluster |
| Azure NPM | Cilium; NPM Linux retires 2028-09-30, Windows 2026-09-30; kubenet retires 2028-03-31 `[CITED]` | 2026 | Cilium at create |
| B-series v1 VMs (B2s) | Retirement announced for **2028-11-15**, Bv2 recommended `[CITED: docs.azure.cn mirror of the retirement list — LOW, verify on Microsoft Learn]`; Basv2 quota here is 0 and Basv2 has no ephemeral OS | 2025-10 announcement | B2s acceptable for staging; add a dated horizon row |
| PostgreSQL on Flexible Server | PG 18 GA at 18.6 `[CITED]` | 2026 | Use 18 (dev parity) |
| AKS Free tier | Docs: free management; a new `FreeTierInfrastructureCost Uptime SLA` meter appears in the price list from 2026-10-01 `[VERIFIED: prices API]` with no matching documentation found | 2026-10 | Owner cost checkpoint |

**Deprecated/outdated in-repo claims this phase must correct:** `50-observability.yaml` ("No observability pods are shipped") — replace; `ingress.yaml` + #296 assume an external IdP — restore the auth host with its Service/Deployment; `infra/keycloak/README.md` documents a `frontend` client that does not exist; `docs/runbooks/sealed-secrets.md`, `k8s/DEPLOYMENT.md:204-218` and `k8s/scripts/seal-secrets.sh` describe the rejected sealed-secrets path (mark superseded by D-23, keep as history); staging configmap comment "SMTP is deliberately NOT overridden here … SES" (`k8s/staging/configmap-patch.yaml:26-28`) becomes false once staging points at Mailpit.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|---|---|---|
| A1 | Alertmanager notifies every `email_configs` entry in one receiver, so a Zoho + Mailpit receiver keeps L-3 honest | Blocker B | L-3 proves only one sink; fall back to IMAP read-back of the real inbox |
| A2 | The `- Free` LB meter ends with the 12-month free offer on 2026-11-08 | Cost | Estate ±£13.80; could end earlier |
| A3 | `FreeTierInfrastructureCost` (£0.0377/hr from 2026-10-01) does not bill a Free-tier cluster | Cost | +£27.52/mo → over ceiling; owner decision |
| A4 | PG backup storage up to 100% of provisioned storage is free | Cost | Small (£0.0755/GB/mo beyond) |
| A5 | Cilium translates pod → own-LoadBalancer-IP traffic before policy, so the 443 `ipBlock` rule would not admit the hairpin | Pattern 5 | If wrong, the public issuer path also works — the internal-issuer recommendation is still safer |
| A6 | Key Vault object names allow only alphanumerics and dashes (hence the `--` mapping) | Pattern 4 | Mapping rules change; the injectivity check still guards |
| A7 | A `secret-sync` pause Deployment keeps CSI-synced Secrets alive for operator/CronJob consumers | Pattern 4 / Pitfall 11 | Broker restart or nightly backup fails; prove by deleting the consumer pod and checking the Secret survives |
| A8 | Per-secret scoping of `Key Vault Secrets User` is supported for RBAC-mode vaults | Standard Stack | Fall back to one vault per trust boundary |
| A9 | Mailpit's search API is under `/api/v1/` (not Mailhog-compatible) | Blocker B | Read the instance swagger before coding the sink |
| A10 | KC 26 serves `/metrics` on management port 9000 natively; `KC_LEGACY_OBSERVABILITY_INTERFACE` is deprecated | Pitfall 7 | Scrape target port; L-1 shows it immediately |
| A11 | Burstable Flexible Server does not support zone-redundant HA | #101 | #101's HA half is Phase 32 either way |
| A12 | PITR does not copy firewall rules/parameters; Burstable has no on-demand backup | Pitfall 8 | Drill script steps; re-read MS Learn at implementation |
| A13 | Overlay pod CIDR defaults to 10.244.0.0/16 | Pitfall 10 | Measure before setting trusted proxies (CONTEXT already requires measurement) |
| A14 | Alert SMTP moved to Zoho; DNS moved to Namecheap (memory 2026-10-18) — DNS half re-measured and confirmed; SMTP half not probed this session | Corrections | Owner confirms D-17 amendment |
| A15 | Request totals for Keycloak/RabbitMQ/Prometheus etc. (~2.5 vCPU / 4.6 GiB) | Pitfall 2 | Node count; measure on the live cluster |
| A16 | The JDK/Spring bundle key path `spring.ssl.bundle.pem.<name>.truststore.certificate` | Code Examples | The D-20/D-21 unit test catches a wrong key |
| A17 | kubectl ±1 minor skew policy makes v1.28 unsupported against 1.36 | Blocker G | Odd CI failures; bump regardless |
| A18 | `azure_pg_admin` can run `ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app` and pre-create `uuid-ossp` | Blocker E/C | Role bootstrap order changes; verify with `pg_roles`/`\ddp` read-backs |

---

## Open Questions

1. **Which ingress controller for a public edge in Oct 2026?**
   - Known: ingress-nginx is retired (no releases since 2026-03-19); AKS managed NGINX ends Nov 2026; Gateway API path needs Ingress → HTTPRoute rewrites, has no rate limiting, and costs ≥4 always-on pods (istiod ×2, gateway ×2) on a 3 × B2s budget; Traefik v3.7's Ingress-NGINX provider documents support for `more_set_headers` in `configuration-snippet`, CORS, `ssl-redirect`, `limit-rps`/`limit-connections`, `proxy-body-size`, buffering, `proxy-read-timeout` `[CITED: doc.traefik.io … /kubernetes/ingress-nginx/]` (not listed: `proxy-buffer-size`, `proxy-buffers-number`, `proxy-connect-timeout`, `proxy-send-timeout`).
   - Recommendation: **owner checkpoint.** Lowest-change: self-installed `controller-v1.15.1` with a dated horizon row, a written security acceptance (staging only, no real customer data) and a Phase 32 migration gate; maintained alternative: Traefik with the NGINX provider, installed into a namespace the NetworkPolicies already name (or update `namespaceSelector`s), with an annotation-fidelity check (`curl -sI` headers, CORS preflight, 429 on burst). Either way `security_block_on: medium` needs the acceptance recorded.
2. **D-26 tenant creation path.** `POST /api/v1/admin/tenants` is `@PreAuthorize("hasRole('admin')")` (`TenantAdminController.java:50-51`) and the realm has `registrationAllowed: false` (template line 33); `access.strict-scoping: "true"` (`configmap.yaml:399`) means the first JIT user becomes the bootstrap GROUP_ADMIN. The script needs a realm-`admin` token to create the tenant, then creates the vendor user (with `tenant_id`) through the KC admin API (port-forward). Recommend a short-lived script-created admin user deleted at the end, or a staging-ops credential in Key Vault — and record that J'Toye has no platform-operator identity (`arch_no_platform_operator`). Onboarding gates: `COMPANIES_HOUSE_API_KEY` is not wired in k8s (`application.yml:869-870` default empty) — use the sole-trader path or resolve the gate via `OnboardingAdminController` `/{id}/gates/{gateType}/resolve`, or supply a free CH key as an external value.
3. **D-26 order payment path.** The guest order request has no explicit payment field (`GuestOrderRequest.java`); a cash order is bounded by `CashOrderCaps`. Confirm with the owner whether the seed order is a cash order or a Stripe TEST PaymentIntent (PAY-04 ruling bans cash at fulfilment).
4. **Reuse or recreate `jtoye-ci`?** It exists with the right subject and no roles.
5. **AKS auth model** (local accounts vs Entra + Azure RBAC + kubelogin) — security vs CI simplicity.
6. **The two unresolved cost meters** — owner decision if either bills (see §Cost).

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|---|---|---|---|---|
| `az` CLI + login to `c483d353…` | provisioning | ✓ | 2.91.0; subscription "JToye Digital Production", Enabled, now the CLI default (still pin) | — |
| Providers KeyVault, Storage | KV, Blob | ✗ NotRegistered | — | `az provider register` (owner/executor, free) |
| Regional vCPU quota | 3 × B2s (+ surge) | ✓ | cores 10, BS family 10, Basv2 0 | quota request only if Basv2 chosen |
| `kubectl` | apply/verify | ✓ | client v1.35.9, embedded Kustomize v5.7.1 (render byte-equal to golden today) | CI pins kustomize 5.6.0 |
| `psql`/`pg_dump`/`pg_restore` | DPLY-04 | ⚠ | **16.15** — cannot dump/restore PG 18 | pg-backup `18-blob` image / `postgres:18` container |
| `docker` | drills, promtool via image | ✓ | 29.9.1 | — |
| `gh` | issues, envs, vars | ✓ | 2.100.0 | — |
| `jq`, `openssl`, `dig`, `node` | scripts | ✓ | jq 1.7, OpenSSL 3.5.9, node v22.23.3 | — |
| `helm`, standalone `kustomize`, `promtool` | — | ✗ | — | not needed (D-16 forbids Helm; promtool via image) |
| `stripe` CLI | D-28 signed test event | not probed | — | Stripe dashboard "send test webhook" |
| Kube contexts | — | `jtoye-staging` (**stale**, deleted cluster), `sipbihs2aks` (**employer — never**) | — | overwrite + assert server URL |
| DNS records | DPLY-01, HTTP-01 | ✗ | Namecheap zone live, staging names NXDOMAIN | owner adds 4 A records |
| Stripe test keys + webhook secret, Zoho SMTP values | D-28, D-17 | owner-held | — | none — external values |

**Missing with no fallback:** DNS A records (owner), Stripe test keys/webhook secret (owner), alert SMTP values confirmation (owner), provider registration + cluster creation (billable — owner approval).
**Missing with fallback:** PG 18 client tooling (container), helm/kustomize/promtool (not needed or via image).

---

## Validation Architecture

`workflow.nyquist_validation: true`, `tdd_mode: true` (`.planning/config.json`).

### Test Framework

| Property | Value |
|---|---|
| Framework | Bash gates (exit 0 clean / 1 violation / 2 VOID); JUnit Jupiter 6 via Gradle; Playwright 1.64.0; live probes (curl, agnhost, psql in the PG 18 image) |
| Config files | `k8s/scripts/*.sh`, `scripts/check-*.sh`, `core-java` Gradle test tasks, `frontend/playwright.config.ts` |
| Quick run (per task) | `k8s/scripts/check-render-invariants.sh && k8s/scripts/render-golden.sh && k8s/scripts/check-env-contract.sh && k8s/scripts/check-no-plaintext-secrets.sh && k8s/scripts/check-connection-math.sh` |
| Full suite (per wave) | the k8s-validate set + `scripts/check-dependency-horizons.sh` + `scripts/check-no-create-extension.sh` + `scripts/check-gate-enforcement.sh` + `scripts/check-alert-rules.sh` + `scripts/check-no-object-store-residue.sh` + `scripts/check-doc-citations.sh` + `./gradlew test` for touched tests |
| Phase gate | Both alert gates exit 0 against staging (timestamped), both PITR arms recorded, the denial captured, the L7 URL recorded, the D-22 two arms, the D-28 three arms, one green CI deploy + digest parity |

### Phase Requirements → Test Map

| Req | Behaviour | Type | Command (pass direction) | Fail direction (must be shown) | Exists? |
|---|---|---|---|---|---|
| DPLY-01 | Verbatim authorize URL carries `client_id=core-api` + encoded `redirect_uri=https%3A%2F%2Fapp-staging…`, lands on a rendered dashboard with seeded rows | e2e (Playwright real-KC login against `https://app-staging.olajay.co.uk`, credential from KV at runtime) | record URL, post-login URL `/dashboard`, `<h1>`, row counts from the API | `keycloak.client-id` → `does-not-exist` gives `invalid_client` / no dashboard | ⚠ helper exists (`dashboard-mobile.spec.ts`), never run on a public host |
| DPLY-01 | Hosts resolve to the ingress IP; production hosts do not | live | `dig @dns1.registrar-servers.com A <host>` = IP for 4 staging names | `api`/`app.olajay.co.uk` must stay NXDOMAIN (D-08); positive control `one.one.one.one` | ❌ Wave 0 script |
| DPLY-01 | Security headers + CSP + noindex served | smoke | `curl -sI` shows HSTS, CSP with `auth-staging`, `X-Robots-Tag: noindex` | a host without the annotation lacks them | ❌ |
| DPLY-01/E | Fresh DB boots with migrator/runtime split | integration + live | core-java boot log: Flyway as `jtoye_app`, validator PASS as `jtoye_runtime` | `DB_MIGRATION_USER` unset → Flyway permission failure; `DB_USER=jtoye_app` → validator refuses | ❌ |
| DPLY-02 | Every issue closed/deferred with a written reason | doc check | checklist file asserted against `gh issue view` state | an issue missing from the checklist fails | ❌ Wave 0 |
| DPLY-02/#592 | One-click URL wired from manifests | unit | `./gradlew test --tests '*UnsubscribeLinkRoutingTest*'` | the extended test FAILS on the current tree | ⚠ exists, extend |
| DPLY-02/#99 | CI deploy runs green and deploys the commit's images | CI + live | deploy-staging job green; `check-deploy-digest-parity.sh` rc 0 | wrong `FRONTEND_PUBLIC_API_URL` fails CR-02; a planted stale digest fails parity | ⚠ branch script |
| DPLY-03 | Rules see live series of the claimed job | live | `PROM_URL=<port-forward> scripts/check-alert-metrics.sh` rc 0 | stop an exporter → violation | ✅ |
| DPLY-03 | Targets up, exporters not blind, transport end-to-end | live | `PROM_EXEC='kubectl --context … exec …' SINK=mailpit … scripts/check-alert-liveness.sh` rc 0 | L-0: edit the served file → VOID/violation; L-3: break the Mailpit route → violation | ❌ Blocker B |
| DPLY-03 | Real human paged | manual (owner) | probe email in the real inbox, timestamped | Zoho password wrong → `alertmanager_notifications_failed_total` rises | ❌ |
| DPLY-03 | One corpus | static | `scripts/check-alert-corpus-parity.sh` rc 0 | 1-byte edit to the copy → fail | ⚠ branch |
| DPLY-04 | PITR restore has source row counts | live | drill: restore to timestamp → count `products,orders,customers` in one statement with `current_database()` → equal | — | ⚠ branch script, retarget |
| DPLY-04 | Arm A: partial dump passes pipeline checks, fails row count | live | dump as non-BYPASSRLS role with `--enable-row-security` → `MIN_BACKUP_BYTES` + `pg_restore --list` PASS, restored tenant `products` = 0 ≠ live | plain `pg_dump` as that role errors on the first RLS table; job run as `jtoye_app` exits non-zero and uploads nothing | ✅ pattern in `scripts/check-backup-restore-drill.sh` (compose), retarget |
| DPLY-04 | `jtoye_backup` is BYPASSRLS, runtime is not | live | `pg_roles` read-back (`rolbypassrls` t / f / f) | a scratch role without BYPASSRLS is reported as failing | ❌ |
| DPLY-05 | Denial captured | live | `scripts/check-networkpolicy-enforcement.sh`: control connects, denied prints `TIMEOUT` | run on a policy-free namespace first: the "denied" pod must connect (proves the probe can connect) | ⚠ branch |
| DPLY-05 | Rendered egress/ingress port sets as declared | static | INV-7 (+ new ingress rows) | plant 9000 / drop the 9091 rule → fail | ✅ extend |
| D-20 | `starttls.required` true under prod + staging profiles | unit | extended `MailStartTlsPropertyTest` | RED before the literal is added | ⚠ extend |
| D-22 | Real mail lands in Mailpit over STARTTLS; non-TLS SMTP fails | live | onboarding email read back by content from Mailpit | point `SMTP_HOST` at a plain SMTP endpoint → `MessagingException` (STARTTLS required) recorded | ❌ |
| D-23 | Derived secret names all present in KV | script | preflight rc 0 | plant a `secretKeyRef` to a missing name → preflight lists it and refuses | ❌ |
| D-23 | Write-once | script | second run changes nothing (value versions unchanged) | an attempt to overwrite `DSAR_ENCRYPTION_KEY` is refused | ❌ |
| D-25 | Connection math per target | static | gate rc 0 with `RESERVED=15` for staging | a pool inside the old budget but outside the new one fails | ⚠ extend |
| D-28 | Signed webhook verified; forged/unsigned rejected | live | signed test event → 200 + `processed_stripe_events` row | unsigned and forged → 400, no row | ❌ |
| Blob (Phase 36 A6/A7/A10/A12/#626) | WI token present; backup upload; public/private posture | live | runbook §8 read-backs | anonymous LIST and quarantine GET refused | ⚠ runbook |

### Sampling Rate
- **Per task commit:** the quick static set (seconds, no cluster).
- **Per wave merge:** full static suite + touched unit tests.
- **Phase gate:** every live arm above with raw output and ISO-8601 timestamps; runtime parity by running-pod image digests.

### Wave 0 Gaps
- [ ] `k8s/staging/scale-patch.yaml` (Pitfall 1)
- [ ] Blockers A, D, E, F, G, H repo fixes, each with its fail arm committed before arms run
- [ ] `scripts/check-alert-liveness.sh` — `PROM_EXEC` + sink abstraction (Blocker B)
- [ ] headless `core-java-metrics` Service + `dns_sd_configs` (Pattern 2)
- [ ] `k8s/scripts/check-connection-math.sh` per-target `RESERVED` (D-25)
- [ ] extended `MailStartTlsPropertyTest` (D-20) and `UnsubscribeLinkRoutingTest` (#592)
- [ ] DPLY-02 checklist file + its assertion
- [ ] carried scripts re-pinned (cert-manager v1.21.2, operator v2.23.1, PG 18) and wired or exempted in `scripts/gates/gate-enforcement.conf`
- [ ] `infra/dependency-horizons.yaml` rows: cert-manager, rabbitmq-cluster-operator, ingress controller, mailpit, agnhost, B-series v1 (2028-11-15); `rabbitmq-k8s` pin replaced
- [ ] regenerated goldens (`render-golden.sh --write`) and `docs/metrics.json` (`docs-freshness.sh --write`) in the same commits as their sources

---

## Security Domain

`security_enforcement` absent → enabled; `security_asvs_level: 2`; `security_block_on: medium`.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---|---|---|
| V1 Architecture | yes | Responsibility map; ADR-0002 amendment for D-24 |
| V2 Authentication | yes | Keycloak 26.8.1, staging realm without seed users, brute-force on (template), password policy `length(12) and upperCase(1) and lowerCase(1) and digits(1) and specialChars(1) and notUsername` (template) `[VERIFIED]`; test vendor credential only in KV |
| V3 Session | yes | NextAuth cookie over TLS-only hosts; HSTS served (Pitfall 3) |
| V4 Access Control | yes | Only Grafana public (D-19); Keycloak `/admin` not exposed; CI identity least privilege; per-workload KV identities |
| V5 Validation | n/a-new | no new API surface; webhook signature verification already fail-closed |
| V6 Cryptography | yes | Let's Encrypt at the edge; `sslmode` to PG (carry `DB_SSL_MODE`); required STARTTLS with chain + hostname verification (D-20/21); KV for keys; never hand-roll |
| V7 Errors/Logging | yes | prod profile (`include-message: never`); confirm no secret material in logs/Grafana; the August admin-password leak via `az … create` output — keep `--output none` |
| V9 Communications | yes | NetworkPolicies ENFORCED; egress narrowed to named ports; no 0.0.0.0/0 widening as a shortcut |
| V10 Malicious Code | yes | Third-party manifests pinned by tag + sha256; images by tag (+ digest) |
| V14 Configuration | yes | No `kind: Secret` in any build; staging OpenAPI not served (#549 — Blocker G fix keeps it so); `noindex`; KV purge-protection decision recorded |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---|---|---|
| Prometheus/Alertmanager reachable publicly | Info disclosure | No Ingress; assert in render |
| Retired ingress controller on the internet edge | Tampering/DoS | Open Question 1: dated horizon row + recorded acceptance, or a maintained controller |
| `configuration-snippet` enabling cross-namespace secret reads (CVE-2021-25742 class) | EoP | Keep snippets off, or restrict who can create Ingress, recorded |
| Keycloak admin console on the public auth host | EoP | Route only `/realms/`, `/resources/` publicly |
| Dev seed users (`tenant-a-user`…) imported into a public realm | Spoofing | Strip users in the staging realm transform; assert zero non-service-account users after import |
| Long-lived kubeconfig in GitHub | Spoofing | OIDC federated credential, `id-token: write` scoped to the job, exact subject |
| Managed PG with a wide firewall | Info disclosure | One rule = the cluster's own outbound IP |
| CSI `optional: true` refs starting unconfigured | Repudiation/tampering | Preflight treats every derived name as required |
| Forged Stripe webhook | Spoofing | Signature verification (exists) + D-28 negative arms |
| Required-TLS downgrade via missing config | Tampering | D-20 literals + test + D-22 break arm |
| Grafana factory admin password | EoP | Admin from KV; verify against the running instance (Grafana applies the env only on first user creation) |

---

## Sources

### Primary (measured this session — HIGH)
- Repo reads on `main` @ `29facb94`: `k8s/base/*` (configmap, core-java/frontend deployments, ingress, sse-ingress, networkpolicies, serviceaccounts, kustomization, pg-backup-cronjob), `k8s/staging/*`, `k8s/scripts/check-render-invariants.sh`, `check-env-contract.sh`, `check-connection-math.sh`, `render-golden.sh`, `scripts/check-alert-liveness.sh`, `scripts/check-backup-restore-drill.sh`, `infra/backups/*`, `infra/db/create-runtime-role.sql`, `infra/keycloak/realm-export.template.json` + README, `infra/monitoring/*`, `infra/dependency-horizons.yaml`, `core-java/src/main/resources/application{,-staging,-prod,-dev}.yml`, `MailStartTlsPropertyTest.java`, `DatabaseConfigurationValidator.java`, `PaymentController.java`, `PaymentService.java`, `TenantAdminController.java`, `.github/workflows/ci-cd.yaml`, ADR-0002, `docs/security/PENTEST-TRIAGE.md`, `SYSTEM_DESIGN_V2.md`, `36-PHASE29-HANDOFF.md`
- `kubectl kustomize k8s/staging` (byte-equal to `k8s/goldens/staging.yaml`), `check-render-invariants.sh` and `check-connection-math.sh` live runs
- `az` 2.91.0 read-only: account list, resource/group list, provider show, aks get-versions, postgres list-skus, vm list-skus, vm list-usage, keyvault check-name, storage check-name (failed: provider), identity federated-credential list, security pricing (not registered), `aks create --help`, `redisenterprise create --help`, `postgres flexible-server create --help`
- Azure Retail Prices API (GBP and USD, uksouth/Global) and Cost Management query API (Aug 1 – Oct 17)
- `dig` against 1.1.1.1, `dns1.nic.uk`, `dns1.registrar-servers.com`
- `gh issue view` ×18, `gh pr view 955`, `gh api` environments / packages / releases (cert-manager, rabbitmq/cluster-operator, ingress-nginx, mailpit, gateway-api, envoy gateway, traefik, secrets-store-csi-driver), `gh variable list`, `gh secret list`
- Downloaded and hashed `cert-manager.yaml` v1.21.2 and `cluster-operator.yml` v2.23.1
- `spring-boot-mail-4.1.1.jar` metadata + `javap` of `MailSenderPropertiesConfiguration`
- Branch archive `origin/phase-29-research` @ `ebee67fe`: 29-RESEARCH, 29-PROVISIONING-EVIDENCE, 29-OPERATOR-DECISIONS, deferred-items, script headers (research input only; every carried claim re-checked above)

### Secondary (official docs fetched — classify-confidence seam rates webfetch LOW; tagged [CITED])
- learn.microsoft.com/azure/aks/free-standard-pricing-tiers (updated 2026-09-01)
- learn.microsoft.com/azure/aks/use-network-policies (updated 2026-09-29)
- learn.microsoft.com/azure/aks/csi-secrets-store-identity-access (updated 2026-09-30)
- learn.microsoft.com/azure/aks/csi-secrets-store-configuration-options (updated 2026-01-30)
- learn.microsoft.com/azure/aks/app-routing-gateway-api (updated 2026-10-08)
- learn.microsoft.com/azure/postgresql/configure-maintain/concepts-supported-versions (2026-08-26)
- learn.microsoft.com/azure/postgresql/extensions/concepts-extensions-by-engine?pivots=postgresql-18
- learn.microsoft.com/azure/postgresql/security/security-access-control (2026-07-14)
- learn.microsoft.com/azure/redis/overview (updated 2026-08-31)
- mailpit.axllent.org/docs/configuration/smtp
- doc.traefik.io/traefik/reference/routing-configuration/kubernetes/ingress-nginx/

### Tertiary (LOW — web search only, verify before relying)
- B-series v1 retirement 2028-11-15 (docs.azure.cn retirement list mirror; Microsoft Q&A thread)
- Third-party AKS pricing blogs (no evidence for a Free-tier charge)

---

## Metadata

**Confidence breakdown:**
- Repo blockers A–H and issue dispositions: HIGH — read from committed files, renders and `gh` this session, with positive controls on every absence
- Azure state, quota, DNS, GitHub state: HIGH — measured read-only
- Cost: MEDIUM — unit prices and the August meters measured; the LB free-offer end date and the new AKS meter are unresolved
- Platform-component behaviour (CSI sync lifetime, Mailpit API, KC metrics port, Cilium hairpin): MEDIUM/LOW — docs or reasoning; each has a live arm in the Validation Architecture
- Ingress controller choice: LOW — a genuine open decision

**Research date:** 2026-10-18
**Valid until:** 2026-11-08 for cost (free-offer end, AKS meter, CLI 2.92.0 breaking defaults in Nov 2026); 2026-11-30 for the ingress landscape (AKS managed NGINX end of support); re-measure DNS and Azure state before relying on either.
