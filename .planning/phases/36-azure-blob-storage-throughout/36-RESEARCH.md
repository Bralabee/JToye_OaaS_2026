# Phase 36: Azure Blob Storage Throughout - Research

**Researched:** 2026-09-28
**Domain:** Object storage migration (S3/MinIO → Azure Blob + Azurite), AKS Workload Identity, backup destination, dev-data reseed
**Confidence:** HIGH on the repo inventory and the SDK/Azurite facts (read from source, bytecode and Maven Central); MEDIUM on Azure control-plane behaviour (official docs, not exercised, because there is no Azure account in this phase); LOW only where marked `[ASSUMED]`

## Summary

The swap is well contained. Only three main-code files touch the AWS SDK (`StorageService`, `StorageConfig`, `DemoDataSeeder`). All eight SDK calls sit in `StorageService`, and 13 other main-code files call it through key- or URL-addressed methods that can keep the same signatures. **The most important finding for planning is a false premise in draft success criterion 1:** none of the "existing Testcontainers media suites" touches object storage. Every one of them replaces storage with `@SpyBean StorageService` plus stubbed `putBytes`/`getBytes`/`deleteByKey` (the Javadoc of `CowSafetyIntegrationTest`, `MediaProcessingWorkerIntegrationTest`, `MediaCopyOnWriteIntegrationTest` and others says "without a live MinIO"). They will stay green whatever the storage does. So the phase has to **add** real Azurite tests: a storage-seam round-trip, the #626 rule tested in both directions, and one pipeline test with storage left unstubbed. "Existing suites pass" alone cannot fail.

The stack is settled and current. `com.azure:azure-storage-blob` 12.35.1 and `azure-identity` 1.18.6 are the latest GA releases. `azure-core-http-netty` 1.16.7 declares **netty 4.1.137.Final, exactly the project's existing `netty.version` pin**. Testcontainers 1.21.4, already managed by Boot 3.5.16, ships `org.testcontainers.azure.AzuriteContainer` in `org.testcontainers:azure:1.21.4`. Azurite 3.37.0 (2026-08-26) targets service version 2026-06-06, which is the SDK's default `BlobServiceVersion.getLatest()`. Azurite **does** enforce the #626 rule: its `PublicAccessAuthenticator` lets `blob`-level containers serve `Blob_Download`/`Blob_GetProperties` but never `Container_ListBlob*`. Azurite does **not** support soft delete, blob versions, immutability policies or legal holds, so WORM can only be stated and read back in staging (Phase 29), never tested locally. The Java SDK accepts `UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite` and supplies the emulator key itself (verified in bytecode). The well-known key therefore never needs to appear as a literal in compose or `application.yml`. That matters: **gitleaks 8.30.1 with this repo's config flags the literal key as `generic-api-key` (rc=1, measured).**

There are three structural surprises, each of which needs a task:
1. **The staging AKS cluster was created without `--enable-workload-identity`.** `azure-staging-provision.sh` on `phase-29-research` passes only `--enable-oidc-issuer`, so the webhook that D-02 relies on is not installed yet.
2. **Phase 29's operator-secret list (`scripts/staging-secrets.sh`) exists only on `phase-29-research`.** That branch is 96 commits ahead of the merge-base and must never be modified. The last clause of SC5 can only be met with a handoff record on main that Phase 29 consumes, not with an edit.
3. **The reseed cannot be a URL rewrite alone.** `ProductService.resolveAssetFirst` serves a product's primary ACTIVE `media_asset` ahead of `products.image_url`. Every pre-existing ACTIVE asset key will point at a blob that no longer exists, and it would shadow even a correctly rewritten flat URL.

**Primary recommendation:** Keep `StorageService`'s public surface unchanged. Put the SDK behind a small package-private `BlobObjectStore` adapter, and route by key: any key whose second segment is `quarantine` goes to a **private** container, everything else to the **blob-level public** container. Add one explicit `storage.blob.auth-mode` switch (`connection-string` | `workload-identity`) that is validated when the bean is created, plus a connectivity and public-access probe at startup. The first plan should be a tracer that ships the seam, its four rewritten unit tests, one Azurite round-trip plus #626 test, and the compose `azurite` service. Fan out after that.

## Project Constraints (from CLAUDE.md)

These directives carry the same weight as locked decisions:

- **Stack is fixed:** Spring Boot 3.5.16, JDK 25 (Temurin), Gradle 9.7.1, Next.js 16, Go 1.27, PostgreSQL 15. Add no framework.
- **Multi-tenancy:** every new feature respects RLS and TenantContext. Any backfill or rewrite on a FORCE-RLS table loops tenants with `set_config` (D-05; traps V25→V44→V57).
- **Testing:** all new code requires tests. Counts live in `docs/metrics.json`, which is gated by `scripts/docs-freshness.sh` (tree → metrics) and `scripts/check-doc-metrics.sh` (prose → metrics). Both fail on drift, so regenerate with `--write` and update the prose counts in CLAUDE.md, AGENTS.md and README.
- **Docker:** rebuild ALL containers after code changes and before E2E. `compose start` never rebuilds.
- **Runtime topology:** compose is the canonical local and E2E runtime. k8s kustomize is the staging/prod target. Locally run compose XOR minikube, never both.
- **Falsifiable evidence:** show every acceptance criterion FAILING before trusting it, and record both directions. Missing tooling or empty output exits non-zero (VOID). Use here-strings, never `cmd | grep -q` under pipefail.
- **Runtime parity:** the delivered runtime must match the branch. Gates are `scripts/check-runtime-freshness.sh` (per service; `.Metadata.LastTagTime`) and `scripts/check-branch-behind-base.sh`. Read config out of the running jar (`unzip -p /app/app.jar BOOT-INF/classes/application.yml`).
- **Agent-readiness:** least-privilege credentials, RFC 7807 errors, and an MCP tool for any core capability (this phase adds none, so record N/A).
- **Security:** every plan carries a `<threat_model>` block. The project config sets `security_asvs_level: 2` and `security_block_on: medium`.
- **Web-perf / SEO:** storefront images keep `Cache-Control: public, max-age=31536000, immutable` (LCP). There is no route regression at a throttled mobile profile.
- **Client-persisted identity lifecycle:** N/A. There is no client-side user-scoped state in this phase.
- **Incremental Betterment:** enumerate any good that gets displaced. The ones at risk here are demo storefront imagery after the reseed, the hybrid runtime booting without an object store, and the storefront LCP headers.
- **GSD workflow:** edits go through GSD commands. `phase-29-research` is canonical, so read it via `git show` only and never modify or clean it up.
- **Git:** feature branch, then PR. No AI-attribution trailers or footers (global ruling).
- **Proof-standards skill (`.claude/skills/proof-standards`):** grep/rg are gitignore-honouring functions, so use `rg -uu` or `git grep` and print the rc. `find` is a `bfs` shim. Wrappers that exec cannot see rg. **Also note that `rg -E` means `--encoding`, not extended regex.** Measured this session: `rg -uu -l -E 'software\.amazon'` returned rc=2 with no output. Use `rg -uu -e PATTERN` or `git grep -E`.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### Backup destination (supersedes Phase 29 D-12)
- **D-01:** The Postgres logical dump (the `pg-backup` CronJob, Phase 29 D-10's second line) goes to
  a **dedicated Azure storage account, separate from the media account, in a different Azure region
  from staging**, with an **immutability (WORM) policy and soft delete** on its container. Phase 29
  D-12 ("AWS S3, off-cluster AND off-Azure") is SUPERSEDED. The trade is conscious and recorded:
  this survives object deletion and a compromised app credential, but NOT loss of the whole Azure
  subscription — the case D-12 existed for. The first line (provider PITR, D-10) is unchanged.
  — **Reversibility:** costly — an immutability policy, once locked, cannot be shortened or removed
  until its retention lapses; plan the retention value as a deliberate checkpoint, never a default.

#### Staging/production credentials
- **D-02:** core-java AND the backup CronJob authenticate to Blob Storage with **AKS Workload
  Identity** (federated credential on `jtoye-staging-aks`'s OIDC issuer + an RBAC data-plane role
  scoped to the ONE storage account each needs — media vs backup; least privilege per the
  agent-readiness contract). No account key, connection string or SAS is stored in any k8s Secret,
  sealed or not. Locally and in the nightly, Azurite's well-known dev connection string is used; the
  two auth paths sit behind ONE config switch in the existing storage config layer, and a
  misconfiguration must fail fast at startup (not at first upload).
- **D-03:** Consequence for Phase 29: the two AWS key pairs (media, backup) leave the operator
  secret list; `~/.jtoye/staging-operator.env` drops from 7 values to the 3 Alertmanager SMTP
  values. The provisioning of the storage accounts, identities and role assignments belongs with
  the staging estate (Phase 29's provisioning plans consume this decision).

#### Existing local media
- **D-04:** **Reseed.** The 3.6MB in the `minio_data` volume is in MinIO's on-disk format and is not
  migrated. Dev media is regenerated by the demo seeder against Azurite, and persisted image URLs in
  the dev DB are rewritten to the new origin, keeping the rest of the dev DB (schema V66, the 5
  shops). Hand-uploaded local images are accepted as lost (dev-only). The `minio_data` volume itself
  is **left in place, not deleted** — standing ruling "leave all volumes" (2026-08-16).
- **D-05:** Any URL rewrite against RLS-protected tables must loop tenants with `set_config`
  (the recurring RLS-backfill trap: a bare UPDATE on a FORCE-RLS table reports success on ZERO rows)
  and prove its row count is non-zero on a table known to hold rows.

#### Public image URL origin
- **D-06:** Staging/production serve images from the **raw Blob endpoint**
  (`https://<account>.blob.core.windows.net/<container>/...`); no Front Door/CDN/custom domain in
  this phase (it would add a paid service and depend on the same DNS currently blocking Phase 29).
  Persisted rows keep ABSOLUTE URLs as today; a future move to a custom domain is a one-off URL
  rewrite, deferred to Phase 32 cutover. — **Reversibility:** costly — every persisted
  `image_url` / `additional_image_urls[]` row carries the origin; changing it later is a
  tenant-looped rewrite plus a CSP/`remotePatterns` change.

### Claude's Discretion
- **Container layout.** Blob public access is set PER CONTAINER, not per prefix. Today quarantine
  is a `/quarantine/` key prefix inside the one public bucket (`MediaAssetRepository` guards on it),
  which cannot be expressed on Blob. Split into a public-blob-access container for derivatives and a
  **private** container for quarantine; the #626 rule (anonymous read BY URL, no anonymous LIST)
  maps to container access level `blob` on the public one and `private` on quarantine.
- Regions (staging media account co-located with the AKS cluster; backup account in a paired or
  other UK region), SKU/redundancy tier, Azurite image digest and host port, whether Azurite
  publishes on `localhost:10000`, the SDK version, and the lifecycle-management rule (if any) that
  backs the Phase 27 quarantine horizon versus keeping it in the application sweep.
- How the storage seam is shaped (replace the S3 client inside `StorageService` vs an interface with
  one Blob implementation) — the call-site surface (13 referencing files) must not change.

### Deferred Ideas (OUT OF SCOPE)
- **Custom media domain** (`media.olajay.co.uk` via Front Door/CDN) — Phase 32 cutover, once DNS exists; implies a one-off URL rewrite (D-06).
- **Off-Azure backup copy** — explicitly NOT chosen (D-01); revisit before real customer data if subscription-loss risk is re-weighed.
- **Persist keys instead of absolute URLs** for the legacy flat image columns — the durable fix for origin coupling; a data-model change outside this phase.
</user_constraints>

<phase_requirements>
## Phase Requirements (proposed — the orchestrator said "TBD, derive at plan time")

| ID | Description | Research Support |
|----|-------------|------------------|
| BLOB-01 | core-java stores, reads and deletes media through the Azure Blob SDK behind the unchanged `StorageService` surface. `software.amazon.awssdk` is gone from main code AND from the runtime classpath. One `storage.blob.auth-mode` switch; misconfiguration fails at startup. | §Standard Stack, §Pattern 1–3, §Pitfalls 1–6, SC1 rows in §Validation |
| BLOB-02 | Container split: a blob-level public container for derivatives and seed images, a **private** container for quarantine. The #626 rule (anonymous GET by URL = 200; anonymous LIST refused; anonymous GET of quarantine refused) is proven both ways against Azurite and asserted by core-java at boot in every environment. | §Pattern 2, Azurite `PublicAccessAuthenticator` quote, §Security |
| BLOB-03 | The media pipeline (quarantine → worker → WebP derivative → quarantine delete; reaper/sweep `deleteByKeyChecked`) is proven against a **real** Azurite in Testcontainers. The existing stubbed suites stay green as regression. | §Summary false-premise finding, §Pitfall 1 |
| BLOB-04 | Compose and the nightly run a digest-pinned Azurite (`mcr.microsoft.com/azure-storage/azurite:3.37.0@sha256:8304…1fd5`). `minio`/`minio-init` are removed. The nightly brings the stack up AND executes Playwright. | §Azurite, §Pattern 5, §Environment |
| BLOB-05 | Dev reseed (D-04/D-05): a tenant-looped, dev-only reseed with a non-zero before-count on a table known to hold rows. Pre-existing ACTIVE/PENDING `media_asset` rows are marked FAILED because their bytes are gone. Every storage URL the API can serve HEADs 200 on Azurite. A real browser shows `naturalWidth > 0` for an image served from the storage origin. | §Pattern 6, §Runtime State Inventory, §Pitfall 7–8 |
| BLOB-06 | The pg-backup CronJob writes to Azure Blob (Workload Identity in staging/prod, Azurite locally). A restore from an Azurite-stored dump is exercised with the 26-07 two-arm recipe (arm A zero-row, arm B row-count match). | §Pattern 7, §Backup tooling |
| BLOB-07 | k8s: base/staging/production/local config carries the Blob endpoints. ServiceAccounts plus the `azure.workload.identity/use` pod label on core-java and pg-backup in staging/prod. No storage Secret reference in staging/prod renders. NetworkPolicies drop 9000. Render invariants (INV-7 port sets, LOC-3, new storage invariants), env contract and goldens are updated. | §Pattern 8, §AKS Workload Identity |
| BLOB-08 | Frontend: CSP `img-src` swaps `http://localhost:9000` for `http://localhost:10000` and gains nothing broader. `next.config.mjs` `remotePatterns` admits exact origins only, never `*.blob.core.windows.net`. | §Frontend |
| BLOB-09 | Zero MinIO/S3 residue (repo-wide, fail-armed gate with a reasoned allowlist for historical records). `infra/dependency-horizons.yaml` swaps the minio/minio-mc rows for an azurite row. `check-media-content-types.sh` is re-targeted to Blob. Docs are updated. | §Inventory, §Validation SC6 |
| BLOB-10 | Phase 29 handoff on main: a provisioning spec (storage accounts, `AllowBlobPublicAccess`, container access levels, WORM + soft delete, identities + FICs, RBAC scopes, `az aks update --enable-workload-identity`), the operator-secret list change (7 → 3) and the merge-conflict map for `phase-29-research`. | §AKS findings, §Open Questions |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Object I/O (put/get/delete/exists), container routing | API / Backend (core-java `StorageService` + adapter) | — | This is the single seam today. Keys are server-generated, and no other tier holds storage credentials. |
| Public image delivery | CDN / Static (the Blob public container, raw endpoint per D-06) | Browser | The browser fetches the absolute URL directly. core-java never proxies bytes. |
| Quarantine privacy | Database / Storage (private container) | API (routing by key) | Access level is a container property, and the app must never be able to "choose" it per object. |
| Auth to Blob in staging/prod | Platform (AKS Workload Identity webhook + Entra ID) | API (`WorkloadIdentityCredential`) | No stored secret (D-02). The token exchange happens at `login.microsoftonline.com`. |
| Container creation + public access level | Platform / IaC (control plane, Phase 29) | API only in dev (`create-containers=true` with a connection string) | `Set Container ACL` **does not support Entra ID authorization** [CITED: learn.microsoft.com anonymous-read-access-configure]. |
| Startup validation (config shape, connectivity, access levels) | API / Backend | — | D-02 fail-fast. |
| Backup upload | Batch (pg-backup CronJob) | Storage (immutable container) | A separate identity and account (D-01/D-02). |
| Dev reseed / URL rewrite | Database (tenant-looped SQL, dev only) | Storage (seed re-upload by DemoDataSeeder) | This is dev data only, so it must never be a Flyway migration (see §Pattern 6). |
| CSP / remotePatterns | Frontend Server (Next middleware / build config) | — | The existing nonce-CSP path. |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.azure:azure-storage-blob` | **12.35.1** (latest GA; the POM was published 2026-08-18) | Blob data-plane client | This is Microsoft's official Java SDK. Its default service version is `2026-06-06` [VERIFIED: javap of `BlobServiceVersion.getLatest()` in the 12.35.1 jar]. |
| `com.azure:azure-identity` | **1.18.6** (latest GA; published 2026-09-02) | `WorkloadIdentityCredential` | AKS docs require Java azure-identity ≥ 1.9.0 [CITED: learn.microsoft.com/azure/aks/workload-identity-overview]. |
| `com.azure:azure-sdk-bom` | 1.3.8 (pins blob 12.35.0 / identity 1.18.4) | Optional version alignment | Use the BOM **or** explicit versions. Explicit latest GA is recommended because the BOM lags one patch. |
| `org.testcontainers:azure` | **1.21.4** (managed by Boot 3.5.16's `testcontainers.version`) | `org.testcontainers.azure.AzuriteContainer` | [VERIFIED: javap of azure-1.21.4.jar shows `AzuriteContainer(DockerImageName)`, `getConnectionString()`, `withSsl(...)`, default image `mcr.microsoft.com/azure-storage/azurite`] |
| Azurite image | **3.37.0** (2026-08-26), manifest-list digest `sha256:830430c1da1a2d537e08f3e6764dd1f5ae00cf0346bcaf625b968ec3f0971fd5` | Local and nightly Blob emulator | [VERIFIED: `curl -I` on `mcr.microsoft.com/v2/azure-storage/azurite/manifests/3.37.0` succeeded anonymously; the GitHub release API gives 3.37.0 as the newest] |

Transitive facts that matter (read from the POMs on Maven Central):

- `azure-core-http-netty` 1.16.7 depends on `netty-*` **4.1.137.Final**, `netty-tcnative-boringssl-static` 2.0.81.Final and `reactor-netty-http` 1.2.18. The project already pins `extra["netty.version"] = "4.1.137.Final"` (`core-java/build.gradle.kts:52`), so **no netty conflict**. Boot 3.5.16 manages `tcnative.version` 2.0.81.Final and `reactor-bom` 2024.0.18 (reactor-core 3.7.x, compatible with azure-core's 3.7.19). Jackson is managed at 2.21.4 by Boot, while azure-core asks for 2.18.9 and Boot's version wins [VERIFIED: spring-boot-dependencies-3.5.16.pom, azure-core-1.59.1.pom].
- `azure-identity` 1.18.6 depends on `msal4j` 1.23.1 and `msal4j-persistence-extension` 1.3.0. The latter pulls **`jna` 5.13.0 + `jna-platform`**, native libraries that exist only for desktop token caches [VERIFIED: POMs]. The recommendation is to exclude `msal4j-persistence-extension` `[ASSUMED: WorkloadIdentityCredential does not need it — prove with a unit test that builds the credential]`.
- `netty-tcnative-boringssl-static` ships glibc-linked natives. The runtime image is `eclipse-temurin:25-jre-alpine` (musl) (`core-java/Dockerfile:27`). The recommendation is to exclude it `[ASSUMED: netty falls back to JDK SSL; reactor-netty via webflux already runs without tcnative in this image]`.
- **The httpcore5 pin** (`build.gradle.kts:68`, `extra["httpcore5.version"] = "5.4.3"`) exists because of `software.amazon.awssdk:apache5-client`. After the S3 removal, re-run `./gradlew :core-java:dependencyInsight --dependency httpcore5 --configuration runtimeClasspath`. If nothing pulls it, drop the pin and its comment in the same commit (a dependency-management pin on an absent artifact is inert, but the comment would become false).

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `github.com/Azure/azure-sdk-for-go/sdk/storage/azblob` | v1.8.1 (2026-09-09) | Backup upload/list/download tool (`blobctl`) | For the pg-backup image (see §Backup tooling) |
| `github.com/Azure/azure-sdk-for-go/sdk/azidentity` | v1.14.1 (2026-08-27) | `NewWorkloadIdentityCredential` in `blobctl` | Go azidentity ≥ 1.3.0 is required for WI [CITED: AKS WI overview table] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Explicit `WorkloadIdentityCredential` | `DefaultAzureCredential` | DAC probes a chain that includes `ManagedIdentityCredential`, which goes to IMDS 169.254.169.254:80. The NetworkPolicies allow only 443, so a misconfiguration surfaces as timeouts instead of a crisp error. Explicit is deterministic and matches "one switch". `[ASSUMED: DAC chain order]` |
| Adapter + one Blob implementation | Swap the S3 client inline in `StorageService` | Inline makes unit tests mock the SDK's fluent chain (`BlobServiceClient→BlobContainerClient→BlobClient`) in four rewritten test classes. A 5-method adapter is trivially mockable and keeps SDK types out of `StorageService`. |
| Go `blobctl` in the backup image | `azcopy` 10.32.8 (`--login-type workload` exists) [CITED: github.com/Azure/azure-storage-azcopy/wiki/azcopy_login] | azcopy authenticates to Azurite only with a SAS (no account key). Generating a SAS is HMAC signing, which means either hand-rolled crypto or az CLI. Azurite compatibility is undocumented. |
| Go `blobctl` | Azure CLI in the backup image | It covers both auth modes (VERIFIED below: `az storage … --connection-string "UseDevelopmentStorage=true"` targets 127.0.0.1:10000). The cost is roughly 700 MB of Python and many Trivy findings in a Postgres image. `[ASSUMED size]` |
| Raw endpoint (D-06) | Front Door/CDN | Locked out by D-06. |

**Installation (core-java/build.gradle.kts, replacing lines 149–151):**
```kotlin
// Azure Blob Storage (Phase 36) — Azurite locally, Workload Identity in AKS.
implementation("com.azure:azure-storage-blob:12.35.1")
implementation("com.azure:azure-identity:1.18.6") {
    exclude(group = "com.microsoft.azure", module = "msal4j-persistence-extension") // [ASSUMED] desktop cache only (jna natives)
}
// optional, see Standard Stack: exclude io.netty:netty-tcnative-boringssl-static (glibc natives on a musl image)
testImplementation("org.testcontainers:azure:1.21.4")
```

**Version verification (reproducible):**
```bash
curl -s https://repo1.maven.org/maven2/com/azure/azure-storage-blob/maven-metadata.xml | grep -oE '<version>[^<]+' | tail -3
curl -s https://repo1.maven.org/maven2/com/azure/azure-identity/maven-metadata.xml | grep -oE '<version>[^<]+' | tail -3
curl -s https://proxy.golang.org/github.com/!azure/azure-sdk-for-go/sdk/storage/azblob/@latest
```

## Package Legitimacy Audit

The `gsd_run query package-legitimacy check` seam supports only `npm|pypi|crates` (measured: `Error: Usage: … --ecosystem <npm|pypi|crates>`). It **could not be run** for Maven or Go. Under the provenance rule these packages therefore stop at `[CITED]`, not `[VERIFIED: registry]`.

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| com.azure:azure-storage-blob 12.35.1 | Maven Central | 12.x line since 2019 `[ASSUMED]` | n/a | github.com/Azure/azure-sdk-for-java | seam unsupported; Microsoft `com.azure` namespace; named in MS docs | Approved (CITED) |
| com.azure:azure-identity 1.18.6 | Maven Central | — | n/a | github.com/Azure/azure-sdk-for-java | seam unsupported; named in the AKS WI docs table | Approved (CITED) |
| org.testcontainers:azure 1.21.4 | Maven Central | — | n/a | github.com/testcontainers/testcontainers-java | seam unsupported; Boot-managed version; jar inspected | Approved |
| github.com/Azure/azure-sdk-for-go/sdk/storage/azblob v1.8.1 | proxy.golang.org | — | n/a | github.com/Azure/azure-sdk-for-go | seam unsupported; official Microsoft repo | Approved (CITED) |
| github.com/Azure/azure-sdk-for-go/sdk/azidentity v1.14.1 | proxy.golang.org | — | n/a | same | seam unsupported; named in the AKS WI docs table | Approved (CITED) |
| mcr.microsoft.com/azure-storage/azurite 3.37.0 | MCR | — | n/a | github.com/Azure/Azurite | image; digest recorded | Approved |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none. Because the seam could not run, the planner should still record in the plan that Maven and Go coordinates were checked against Maven Central / proxy.golang.org and the official Microsoft repos.

## Full S3 / MinIO / AWS-storage Inventory (research Q1)

Reproducible commands. The counts are from `git grep` on tracked files; the Java subset is cross-checked with `rg -uu`:
```bash
P='minio|awssdk|S3Client|s3://|S3_[A-Z_]+|storage\.s3|jtoye-images|jtoye-db-backups|localhost:9000|aws s3|aws-cli|awscli|AWS_ACCESS_KEY|AWS_SECRET|amazonaws'
git grep -l -i -E "$P" -- . ':!.planning' | wc -l          # 120 files (rc=0)
git grep -l -E 'software\.amazon' -- core-java/src/main      # 3 (DemoDataSeeder, StorageConfig, StorageService)
git grep -l -E 'software\.amazon' -- core-java/src/test      # 4 (below)
rg -uu -l 'software\.amazon' core-java/src                   # 7 — agrees (NB: not `rg -E`, which is --encoding)
git grep -l -i -E 'minio|S3Client|MinIOContainer' -- core-java/src/test | wc -l   # 19 (the CONTEXT figure)
```
**Warning for the SC6 gate pattern:** `amazonaws` also matches the **SES** SMTP host (`k8s/base/configmap.yaml:278` `smtp.host: "email-smtp.eu-west-2.amazonaws.com"`), which is email and out of scope. `jtoye-db-backups` is also the local backup directory in `infra/backups/backup.sh`. Neither may appear in the residue pattern (see §Validation SC6).

Per-directory counts (files): core-java 35 · docs 29 · k8s 16 · scripts 12 · .github 7 · frontend 7 · infra 6 · .cursor 2 · plus HANDOFF.md, .gitignore, .env.example, docker-compose.full-stack.yml, CLAUDE.md, AGENTS.md.

### Main code (must change)
| File | What | Line(s) |
|------|------|---------|
| `core-java/build.gradle.kts` | `platform("software.amazon.awssdk:bom:2.54.9")`, `software.amazon.awssdk:s3`; netty/httpcore5 comments name awssdk | 149–151; 21–68 comments |
| `storage/StorageConfig.java` | the `S3Client` bean with `.forcePathStyle(true)` | 20–33 |
| `storage/StorageProperties.java` | `storage.s3.*` defaults (quoted below) | 21–41 |
| `storage/StorageService.java` | 8 SDK calls (putObject ×4, headObject, getObjectAsBytes, deleteObject ×2) | 111, 173, 254, 273, 300, 329, 346, 385 |
| `dev/DemoDataSeeder.java` | `import software.amazon.awssdk.core.exception.SdkClientException;` catch = "store unreachable → abort seeding" | import l.~20; catch ~l.470 |
| `resources/application.yml` | `storage.s3.*` block (quoted below) | 575–582 |
| Comments only (residue): `GdprService`, `MediaAsset`, `MediaAssetService`, `MediaNormalizer`, `MediaProcessingWorker`, `MediaProperties`, `ProductMediaRepository`, `ProductService`, `V42__gdpr_erasure_completeness.sql` (a migration comment; **do not edit an applied migration** — allowlist it) | "MinIO"/"S3" in prose | — |

`[VERIFIED: core-java/src/main/java/uk/jtoye/core/storage/StorageProperties.java:22-27]` (read this session):
```
        private String endpoint = "http://localhost:9000";
        private String region = "eu-west-2";
        private String bucket = "jtoye-images";
        private String accessKey = "";
        private String secretKey = "";
        private String publicUrl = "http://localhost:9000/jtoye-images";
```
`[VERIFIED: core-java/src/main/resources/application.yml:575-582]`:
```
storage:
  s3:
    endpoint: ${S3_ENDPOINT:http://localhost:9000}
    region: ${S3_REGION:eu-west-2}
    bucket: ${S3_BUCKET:jtoye-images}
    access-key: ${S3_ACCESS_KEY:minioadmin}
    secret-key: ${S3_SECRET_KEY:minioadmin}
    public-url: ${S3_PUBLIC_URL:http://localhost:9000/jtoye-images}
```

### Tests
- **Compile-breaking (construct `new StorageService(s3Client, …)` or mock S3 types): 4.** These are `storage/StorageServiceTest.java` (291 lines), `storage/LegacyImageUploadPipelineTest.java` (399), `storage/ShopBrandImageKeyTest.java` (313) and `media/MediaQuarantineRetentionSweepTest.java` (200, uses `S3Client`, `DeleteObjectRequest`, `S3Exception`). None of them is tagged `testcontainers`. All four must be rewritten against the adapter in the **same** plan as the seam swap.
- **`@SpyBean StorageService` with stubbed I/O (keep; string residue only):** `CowSafetyIntegrationTest`, `GateStrictnessTest`, `MediaClaimLockIntegrationTest`, `MediaCopyOnWriteIntegrationTest`, `MediaDedupAttachIntegrationTest`, `MediaDurabilityIntegrationTest`, `MediaProcessingWorkerIntegrationTest`, `MediaTenantIsolationUnderConcurrencyIntegrationTest`, `MediaUploadControllerTest`, `MediaUploadIdempotencyTest`, `MediaSweepTenantScopeIntegrationTest`, `ProductImageDeleteIntegrationTest`, `SystemPrincipalGuardTest`, `ShopImageCrossTenantIntegrationTest`. They carry literals such as `"http://minio/derivative"`. Rename these to a neutral host such as `http://store/…`, which is mechanical.
- `MediaAssetDtoMappingTest` (literals `http://minio/jtoye-images/...`), `MediaBackfillMigrationIntegrationTest:66` (`PUBLIC_URL = "http://localhost:9000/jtoye-images/"` feeds the V53 backfill test and **is not a storage call**; keep or rename it, since V53 extracts by tenant-id position, not origin) and `DemoImageManifestTest` (comment).
- **No test file starts a MinIO container.** There is no Testcontainers MinIO helper to replace.

### Compose / env
- `docker-compose.full-stack.yml`: header l.2; core-java env l.350–354 (quoted below); `minio` service l.596–619; `minio-init` l.627–661 (the anonymous `s3:GetObject`-only policy, #626); volume `minio_data` l.775 (**leave the named volume in place per D-04**. Removing it from the compose `volumes:` list is fine, because Docker does not delete a volume that is no longer declared, but do **not** run `down -v` or `volume rm`).
- `[VERIFIED: docker-compose.full-stack.yml:350-354]`:
```
      S3_ENDPOINT: http://minio:9000
      S3_ACCESS_KEY: ${MINIO_ROOT_USER:?MINIO_ROOT_USER must be set}
      S3_SECRET_KEY: ${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD must be set}
      S3_BUCKET: jtoye-images
      S3_PUBLIC_URL: http://localhost:9000/jtoye-images
```
- `.env.example`: l.185–208 (`MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `S3_ENDPOINT`, `S3_BUCKET`, `S3_PUBLIC_URL`, `MINIO_IMAGE_TAG`, `MINIO_MC_IMAGE_TAG=RELEASE.2025-08-13T08-35-41Z@sha256:a7fe…`), l.456–463 (`K8S_LOCAL_MINIO_PORT=9000`), l.479 (backup bucket in host MinIO).
- `scripts/verify-env.sh:43-76` (`MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` in REQUIRED_VARS; the `MINIOADMIN` banned default). **The nightly's `.env` generation loops over this list** (`e2e-nightly.yml:139`).
- `.gitignore:153` `minio_data/`.
- `infra/docker-compose.yml` (the hybrid runtime used by `scripts/start-dev.sh`) has **no** object store at all (`git grep -l -i minio -- infra` matches only backups/horizons/load-testing). See Pitfall 9.

### k8s
- `k8s/base/configmap.yaml` l.170–175 (`s3.backup.*`) and l.252–264 (`s3.*` media), quoted:
  `[VERIFIED: k8s/base/configmap.yaml:172-175]` `s3.backup.bucket: "jtoye-db-backups"` · `s3.backup.prefix: "backups"` · `s3.backup.region: "eu-west-2"` · `s3.backup.endpoint: ""`
  `[VERIFIED: k8s/base/configmap.yaml:261-264]` `s3.endpoint: "https://s3.eu-west-2.amazonaws.com"` · `s3.region: "eu-west-2"` · `s3.bucket: "jtoye-images"` · `s3.public-url: "https://s3.eu-west-2.amazonaws.com/jtoye-images"`
- `k8s/base/core-java-deployment.yaml` l.364–402: env `S3_ENDPOINT/S3_REGION/S3_BUCKET/S3_PUBLIC_URL` (configMapKeyRef) plus the **optional** `s3-media-credentials` secretKeyRefs `S3_ACCESS_KEY`/`S3_SECRET_KEY`.
- `k8s/base/pg-backup-cronjob.yaml` l.43–47 (comment "aws-cli"), l.78–108: `S3_BUCKET`, `S3_PREFIX`, `S3_ENDPOINT`, `AWS_DEFAULT_REGION`, and `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` from Secret `s3-backup-credentials`. There is no `serviceAccountName`.
- **No ServiceAccount object exists anywhere in `k8s/`** (`git grep -n -E 'kind: ServiceAccount|serviceAccountName|automountServiceAccountToken' -- k8s` returned nothing). Pods run as `default`.
- NetworkPolicies: `20-core-java.yaml:127-128` (`port: 9000   # MinIO` in the infra-namespace rule) with the public 443 rule at l.133–142; `40-datastores.yaml:71-79` (pg-backup → infra 9000) and l.80–90 (pg-backup → public 443). The README table rows l.16/18 also need updating.
- `[VERIFIED: k8s/scripts/check-render-invariants.sh:354-357]`:
```
declare -A NETPOL_INFRA_EXPECTED=(
  [core-java-allow]="__DB_PORT__ 5672 6379 9000 9093 61613"
  [pg-backup-allow]="__DB_PORT__ 9000"
)
```
  Also: INV-4 banned literals include `'minioadmin'` (l.334); the LOC-3 local backup repoint expects exactly `http://$LOCAL_HOST_SHIM:9000` (l.1142–1147); the build-time-vs-runtime config key lists name `s3.endpoint`/`s3.backup.endpoint` (l.1051–1052, 1085–1087); the INV-1-ish comment names `s3.public-url` (l.293).
- `k8s/scripts/check-env-contract.sh` l.17, 104, 233 (`minioadmin` in the local-only default list). It will need new direction-(b) allowlist entries if any `AZURE_*` env is read from config (see Pitfall 10).
- `k8s/local/configmap-patch.yaml:154-162`, quoted `[VERIFIED: k8s/local/configmap-patch.yaml:154-155,162]`: `s3.endpoint: "http://host.minikube.internal:9000"` · `s3.public-url: "http://localhost:9000/jtoye-images"` · `s3.backup.endpoint: "http://host.minikube.internal:9000"`. So **k8s/local does not run MinIO in-cluster**: it shims to the host compose MinIO via `host.minikube.internal`.
- `k8s/local/kustomization.yaml:17,50`; `k8s/base/secrets-template.yaml.example` l.11–36, 111–119, 180–191; `k8s/staging/configmap-patch.yaml:24` and `k8s/production/configmap-patch.yaml:26` (comments saying the base AWS values are "correct").
- Goldens `k8s/goldens/{staging,production}.yaml` (16 matches each). Regenerate with `k8s/scripts/render-golden.sh --write`, never by hand.
- Docs: `k8s/LOCAL.md` (24), `k8s/DEPLOYMENT.md` (4), `k8s/QUICK_START.md` (6).

### CI / scripts / infra
- `.github/workflows/e2e-nightly.yml:74-76` SERVICES (quoted `[VERIFIED: e2e-nightly.yml:74-76]`: `postgres keycloak-realm-render keycloak redis rabbitmq` / `core-java edge-go frontend mcp-server minio minio-init mailhog`); l.245 comment about one-shot `minio-init`.
- `.github/workflows/ci-cd.yaml:399,623` (comments only). The Trivy **image** gate (l.1431–1450) scans the built core-java image, which will now contain the Azure SDK jars. The pg-backup image is **not** built or scanned in CI (`git grep jtoye-pg-backup -- .github/workflows` is empty).
- `.github/dependabot.yml:20` (a comment naming awssdk PRs). The Dependabot gradle ecosystem will now also watch `com.azure`.
- `scripts/check-media-content-types.sh` (19 matches: `docker exec` into `jtoye-minio` using its bundled `mc`). This needs a Blob re-target (see §Pattern 9). `scripts/gates/gate-enforcement.conf:37` describes it.
- `scripts/k8s-local-secrets.sh` (32: host MinIO bucket creation via quay `minio/mc`; `s3-backup-credentials`/`s3-media-credentials` from MinIO root creds), `scripts/lib/k8s-local-guards.sh:63` (`K8S_LOCAL_BACKING_SERVICES="postgres redis rabbitmq keycloak minio mailhog"`) and `:130` (`K8S_LOCAL_MINIO_PORT`), `scripts/k8s-local-up.sh:282`.
- Comments only: `scripts/seed-media-review-fixtures.sh:54`, `scripts/stop-dev.sh:34`, `scripts/check-dependency-horizons.sh:63,212`, `scripts/check-no-measured-placeholders.sh:11`, `infra/load-testing/{baseline,media-pipeline-arm}.sh`.
- `scripts/check-runtime-freshness.sh` derives its service set from `docker compose config` **build** contexts (l.64–66). `minio` is image-only and was never in its set, and Azurite will not be either. **No change is needed.**
- `infra/backups/Dockerfile` (apt `awscli`), `infra/backups/k8s-backup.sh` (l.30–40 S3 env, l.46 `DEST="s3://…"`, l.71 `aws s3 cp`, l.77 `aws s3 ls`, l.86 `aws s3 rm`), `infra/backups/backup.sh` (local docker; matches only `jtoye-db-backups`, a directory name, so it is not residue).
- `infra/dependency-horizons.yaml:196-280` has the rows `id: minio` (pin `quay.io/minio/minio:${MINIO_IMAGE_TAG:-latest}`, site `docker-compose.full-stack.yml:597`) and `id: minio-mc` (pin `quay.io/minio/mc:${MINIO_MC_IMAGE_TAG:-latest}`, sites compose :628, :638, `scripts/k8s-local-secrets.sh:299`). H-1 discovers every `image:` line in the declared compose/k8s set, so **a new `azurite` row with the exact pin string and site line is mandatory** or H-1 exits 1.

### Frontend
- `frontend/lib/security-headers.ts:94`, quoted `[VERIFIED: frontend/lib/security-headers.ts:94]`: `"img-src 'self' data: blob: https://*.stripe.com https: http://localhost:9000",` (the l.36–37 comment explains `http://localhost:9000`). Note that `blob:` here is the browser URL scheme and is unrelated to Azure Blob. The residue gate must not flag it.
- `frontend/next.config.mjs:19-33`, quoted `[VERIFIED: frontend/next.config.mjs:21-26]`: `protocol: 'http',` `hostname: 'localhost',` `port: '9000',` `pathname: '/jtoye-images/**',` plus a commented `*.amazonaws.com` example (l.27–32).
- **No code imports `next/image`** (`git grep -l "next/image" -- frontend` returns only a test comment and `middleware.ts`'s matcher excluding `_next/image`; the positive control `next/link` returned 41 files). `remotePatterns` is therefore **inert for rendering**. It only governs what the `/_next/image` optimizer endpoint will fetch server-side, which is a server-side fetch surface (see §Security).
- Tests: `__tests__/__snapshots__/header-snapshot.test.ts.snap:3` (the CSP string); `components/dashboard/media/__tests__/ReviewQueue.test.tsx:88-89` and `components/ui/__tests__/asset-image.test.tsx:12,40` (`http://localhost:9000/jtoye-images/...` fixtures); `components/ui/asset-image.tsx:39-40` (comment); `e2e/public-layout.spec.ts:5` (comment).
- No `mcp-server/` or `edge-go/` references (checked).

### Docs (update; allowlist the historical ones)
`docs/runbooks/backups.md` (13, a live runbook, **rewrite**), `docs/architecture/*.md`, `docs/HOW_IT_WORKS.md`, `docs/FAILURE_MODES.md`, `docs/AI_CONTEXT.md`, `docs/guides/*.md`, `docs/CREDITS-demo-images.md`, `docs/security/MEDIA-BACKFILL-PLAN-2026-08-10.md`, and CLAUDE.md, AGENTS.md, HANDOFF.md, `.cursor/rules/*`, `.github/{instructions,chatmodes}/*`. **Historical records (allowlist with reason, do not rewrite):** `docs/CHANGELOG.md` (history), `docs/archive/**`, `docs/audit/**`, `docs/analysis/**`, `docs/planning/*-2026-*`, `.planning/**`.

## Architecture Patterns

### System Architecture Diagram

```
                         ┌──────────────── BROWSER ─────────────────┐
                         │  <img src=ABSOLUTE URL from API/DB>      │
                         └───────┬──────────────────────────────────┘
                                 │ anonymous GET (no Authorization header)
        dev/nightly:  http://localhost:10000/devstoreaccount1/<public-container>/<key>
        staging/prod: https://<mediaacct>.blob.core.windows.net/<public-container>/<key>
                                 │
                   ┌─────────────▼──────────────┐   anonymous LIST ──► refused (blob level)
                   │ PUBLIC container (level=blob)│  anonymous GET quarantine ──► refused
                   └─────────────▲──────────────┘
                                 │ put derivative/thumbnail/seed/logo
 vendor upload ──► core-java ── StorageService ── routeByKey(key) ──┬──► PRIVATE quarantine container
 (MultipartFile)     │ accept: putBytes("<t>/quarantine/<sha>.<ext>") ─┘      ▲  getBytes / deleteByKey
                     │ outbox ─► RabbitMQ ─► MediaProcessingWorker ──────────┘  (worker, sweep, reaper→sweep)
                     │                         └─ putBytes("<t>/media/<id>.webp" + "_thumb") ─► PUBLIC
                     │
                     └─ BlobObjectStore (adapter) ─ BlobServiceClient
                           auth-mode=connection-string ─► Azurite (UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite)
                           auth-mode=workload-identity ─► WorkloadIdentityCredential ─► login.microsoftonline.com (443)
                                                           ─► https://<mediaacct>.blob.core.windows.net (443)

 pg-backup CronJob ── pg_dump ─► verify (size floor, pg_restore --list) ─► blobctl upload
      ─► BACKUP account (other region), container with WORM (time-based) + soft delete
      restore drill: blobctl download ─► pg_restore scratch DB ─► row counts (arm A zero-row / arm B live)

 startup: StorageConfig bean (shape validation, no network) ─► ApplicationReadyEvent probe
          (both containers exist; public=BLOB; quarantine=private) ─► fail → context exits non-zero
```

### Recommended Project Structure
```
core-java/src/main/java/uk/jtoye/core/storage/
├── StorageService.java          # public surface UNCHANGED; routes by key; no SDK types
├── BlobObjectStore.java         # package-private adapter: put/get/deleteIfExists/exists/containerAccess
├── AzureBlobObjectStore.java    # the one implementation (BlobServiceClient)
├── StorageConfig.java           # auth-mode switch + shape validation → BlobServiceClient bean
├── StorageProperties.java       # storage.blob.* (+ existing max-file-size-bytes, allowed-content-types)
└── StorageStartupValidator.java # ApplicationReadyEvent probe (property-gated), throws StorageConfigurationException
core-java/src/test/java/uk/jtoye/core/storage/
├── AzuriteStorageIntegrationTest.java    # @Tag("testcontainers"): round-trip, #626 both ways, routing
├── StorageStartupValidatorTest.java      # fail-fast arms (bad mode, missing endpoint, wrong access level)
└── (rewritten) StorageServiceTest / LegacyImageUploadPipelineTest / ShopBrandImageKeyTest
infra/backups/blobctl/                    # tiny Go module: upload | list | download (| delete, see Pattern 7)
scripts/dev-media-reseed.sh               # dev-only, tenant-looped, dry-run default (Pattern 6)
scripts/check-media-urls-resolve.sh       # read-only: every servable storage URL HEADs 200, N>=1
scripts/check-no-object-store-residue.sh  # SC6 gate, fail-armed, reasoned allowlist
```

### Pattern 1: One switch, validated when the bean is built
**What:** `storage.blob.auth-mode` selects exactly one construction path. Shape rules run inside the `@Bean` factory, so a bad config fails **before Tomcat starts**, with no network needed.
**When:** always.
```java
// Source: SDK signatures verified by javap (BlobServiceClientBuilder.connectionString/endpoint/credential/retryOptions,
// RequestRetryOptions(RetryPolicyType, Integer, Duration, Duration, Duration, String), WorkloadIdentityCredentialBuilder.build())
@Bean
BlobServiceClient blobServiceClient(StorageProperties props, Environment env) {
    StorageProperties.Blob b = props.getBlob();
    b.validateShape(env.getActiveProfiles());   // throws StorageConfigurationException (see rules below)
    BlobServiceClientBuilder builder = new BlobServiceClientBuilder()
        .retryOptions(new RequestRetryOptions(RetryPolicyType.EXPONENTIAL, b.getMaxTries(),
                Duration.ofSeconds(b.getTryTimeoutSeconds()), null, null, null));
    switch (b.getAuthMode()) {
        case CONNECTION_STRING -> builder.connectionString(b.getConnectionString());
        case WORKLOAD_IDENTITY -> builder.endpoint(b.getEndpoint())
                .credential(new WorkloadIdentityCredentialBuilder().build()); // reads AZURE_CLIENT_ID/TENANT_ID/FEDERATED_TOKEN_FILE
    }
    log.info("Blob storage: mode={} endpointHost={} public={} quarantine={}",
            b.getAuthMode(), b.endpointHostForLog(), b.getPublicContainer(), b.getQuarantineContainer()); // NEVER the connection string
    return builder.buildClient();
}
```
Shape rules (each needs its own failing test):
- `auth-mode` is required and must be one of the two values. An unknown value fails.
- `connection-string` mode: the string is non-blank. **Refused when profile `prod` or `staging` is active** (D-02: no account key in staging/prod). `create-containers` is allowed only in this mode.
- `workload-identity` mode: the endpoint is `https://…` and the env `AZURE_CLIENT_ID`, `AZURE_TENANT_ID` and `AZURE_FEDERATED_TOKEN_FILE` are present (the webhook injects them; a missing SA annotation shows up here as a crisp error). `create-containers=true` is refused, because `Set Container ACL` cannot be authorized with Entra ID [CITED].
- Container names match `^[a-z0-9](?!.*--)[a-z0-9-]{1,61}[a-z0-9]$` and must differ from each other. `public-url` is absolute and ends with `/<public-container>` (the public URL may legitimately use a different host than the endpoint: split horizon).

### Pattern 2: Container routing by key (quarantine privacy)
**What:** keep every key byte-identical to today, so `media_asset.object_key`, the V60 sweep guard and the quarantine key `tenantId + "/quarantine/" + sha256 + ext` (`MediaAssetService.java:161`, `:211`) do not change. `StorageService` decides the container from the key.
```java
// quarantine keys are "<uuid>/quarantine/<sha256>.<ext>" (MediaAssetService.java:161 verified);
// derivative "<t>/media/<id>.webp", seed "<t>/products/seed/<file>", sync uploads "<t>/<prefix>/<entity>/..."
static boolean isQuarantineKey(String key) {
    int first = key.indexOf('/');
    return first > 0 && key.startsWith("quarantine/", first + 1);
}
private String containerFor(String key) {
    return isQuarantineKey(key) ? props.getBlob().getQuarantineContainer() : props.getBlob().getPublicContainer();
}
```
- **Leave `MediaQuarantineRetentionSweep.QUARANTINE_SEGMENT = "/quarantine/"` (l.81) and its guard (l.133) untouched.** D-03 of 27-01 deliberately keeps that guard independently breakable. Do not "DRY" it into the new helper.
- `urlForKey(quarantineKey)` has no public URL. Recommend that it **throw `IllegalArgumentException`**, and that `putBytes` return `null` for quarantine keys. Both current callers ignore the return value (`MediaAssetService.java:163`, `:212`), but the stubs return strings, so check every `doReturn(...).when(storageService).putBytes` and keep the tests passing.
- Do not stamp `public, max-age=31536000, immutable` on quarantine objects. Keep it on the public-container puts (LCP contract).
- **Security gain (worth stating in the plan):** today the quarantine raw bytes, before the EXIF/GPS strip, sit in the **public** bucket. They are anonymously GETtable by URL for the whole 72 h default horizon (`MEDIA_QUARANTINE_RETENTION_MS:259200000`). The MinIO anonymous policy grants `s3:GetObject` on `arn:aws:s3:::jtoye-images/*` (compose l.645). The split closes that exposure. `[VERIFIED: code + compose policy read; the practical risk is bounded by sha256 unguessability]`

### Pattern 3: S3 → Blob operation map
| Today (S3) | Blob (adapter implementation) | Semantics to preserve |
|------------|--------------------------------|------------------------|
| `putObject(bucket,key,contentType,cacheControl,bytes)` | `blobClient.uploadWithResponse(new BlobParallelUploadOptions(BinaryData.fromBytes(b)).setHeaders(new BlobHttpHeaders().setContentType(ct).setCacheControl(cc)), null, Context.NONE)` | This overwrites, like S3 PUT. Content-Type is always detected or produced, never the client's (T-24-02). |
| `headObject` + `NoSuchKeyException`/404 → absent (`putSeedImage`) | `blobClient.exists()`, **or** atomic create-only: `setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*"))`, where 409 `BlobAlreadyExists` means "already present" | Seed idempotency: the bytes at a deterministic key are written once (`immutable` stays honest, #489). |
| `getObjectAsBytes` | `blobClient.downloadContent().toBytes()` | A throw means transient. The worker then `failRetainingBytes` (D-07). |
| `deleteObject` (S3: deleting a missing key succeeds) | **`blobClient.deleteIfExists()`**, NOT `delete()` (which throws 404 `BlobNotFound`) | `deleteByKeyChecked` returns `true` when the blob is removed or already absent, `false` on error, and never throws (27-01 F-5). |
| `S3Exception`, `NoSuchKeyException` | `BlobStorageException` (`getStatusCode()`, `getErrorCode()`), `BlobErrorCode.BLOB_NOT_FOUND` | — |
| `SdkClientException` (DemoDataSeeder: store unreachable → abort seeding) | Treat any non-`BlobStorageException` `RuntimeException` from the first call as "unreachable → abort". `BlobStorageException` means "this entry failed → skip". `[ASSUMED: connection-refused surfaces as a non-BlobStorageException; prove it with a stopped-Azurite test arm]` | "Never fatal to dev boot" |
| Presign, multipart, copy, list | **None used** (`git grep -i 'presign\|copyObject\|listObjects\|CreateMultipartUpload' -- core-java/src/main` rc=1) | — |

### Pattern 4: Startup probe (fail fast, not at first upload)
Use the `DatabaseConfigurationValidator` precedent (`@EventListener(ApplicationReadyEvent.class)`, throws `SecurityConfigurationException`, `@Profile("!test")`). **Gate it on a property instead of a profile** (`storage.blob.validate-on-startup`, default `true`, `false` in `application-test.yml`). The reason is that `RedisFaultInjectionIntegrationTest` and `PublicRateLimitIntegrationTest` boot under `@ActiveProfiles("dev")` with no Azurite (measured: `@ActiveProfiles("dev")` ×2). The probe checks:
1. both containers `exists()`;
2. `publicContainer.getProperties().getBlobPublicAccess() == PublicAccessType.BLOB` (**refuse `CONTAINER`**, which allows LIST);
3. `quarantineContainer.getProperties().getBlobPublicAccess() == null` (private).

`BlobContainerProperties.getBlobPublicAccess()` exists [VERIFIED: javap]. Get Container Properties is a data-plane read that a Blob Data Contributor/Reader can call. So **the #626 rule is asserted at every staging/prod boot**, not only "stated". Limitation: if the **account** has `AllowBlobPublicAccess=false`, the container property can still read `BLOB` while anonymous reads fail. Record this and cover it with the Phase 29 read-back.

In dev with `create-containers=true` (connection-string mode only): `createIfNotExistsWithResponse(new BlobContainerCreateOptions().setPublicAccessType(PublicAccessType.BLOB) …)` for public, and no access type for quarantine. Then run the same probe.

### Pattern 5: Azurite in compose and the nightly
```yaml
  azurite:
    # 3.37.0 = service version 2026-06-06 = azure-storage-blob 12.35.1's default (both verified)
    image: mcr.microsoft.com/azure-storage/azurite:${AZURITE_IMAGE_TAG:-3.37.0@sha256:830430c1da1a2d537e08f3e6764dd1f5ae00cf0346bcaf625b968ec3f0971fd5}
    container_name: jtoye-azurite
    command: >-
      azurite-blob --blobHost 0.0.0.0 --blobPort 10000 --location /data
      --disableProductStyleUrl --skipApiVersionCheck --disableTelemetry
    ports:
      - "${JTOYE_BIND_HOST:-127.0.0.1}:10000:10000"   # loopback-only (#441); the BROWSER loads images here
    volumes:
      - azurite_data:/data
    healthcheck: { … }   # [ASSUMED] image tooling: probe `docker run --rm --entrypoint sh <img> -c 'command -v wget nc node'` first
    networks: [jtoye-network]
```
- **`--disableProductStyleUrl` is load-bearing for k8s/local.** Azurite parses the account name from the **host** whenever the host has a dot and is neither an IP nor `host.docker.internal`. `host.minikube.internal` therefore resolves to account `host` and every request fails. [VERIFIED: Azurite v3.37.0 `blobStorageContext.middleware.ts:269-280` (`!disableProductStyleUrl && !isIPAddress && !isNoAccountHostName && firstDotIndex > 0`) and `constants.ts:19` `NO_ACCOUNT_HOST_NAMES = new Set().add("host.docker.internal")`.] The compose hostnames `azurite` and `localhost` have no dot, so they work even without the flag.
- `--skipApiVersionCheck` (or env `AZURITE_SKIP_API_VERSION_CHECK=true`; "Only the exact, case-sensitive value `true`") [CITED: Azurite README v3.37.0 l.441–444, 572–584] decouples Dependabot SDK bumps from the Azurite pin. Trade-off: a genuinely unsupported newer feature then fails at runtime rather than at the version check. Record that in the plan.
- core-java env: `STORAGE_AUTH_MODE: connection-string`, `STORAGE_CONNECTION_STRING: "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite"`, `STORAGE_PUBLIC_URL: http://localhost:10000/devstoreaccount1/<public-container>`, `STORAGE_CREATE_CONTAINERS: "true"`. The SDK maps the proxy URI to `http://azurite:10000/devstoreaccount1` and supplies the emulator key itself. [VERIFIED: javap of azure-storage-common 12.34.1 `StorageEmulatorConnectionString` (ldc `DevelopmentStorageProxyUri`, `:10000/devstoreaccount1`) and the key constant in `StorageAuthenticationSettings`.]
- Add `depends_on: azurite: condition: service_healthy` to core-java. Today core-java does not depend on minio, which was fine because the S3 client is lazy; the startup probe makes Azurite a hard dependency.
- Nightly: `SERVICES` changes `minio minio-init` → `azurite`. Drop `MINIO_ROOT_*` from `verify-env.sh` REQUIRED_VARS. There is no Azurite credential to generate.
- Testcontainers: `new AzuriteContainer(DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:3.37.0@sha256:…")).withEnv("AZURITE_SKIP_API_VERSION_CHECK", "true")`. Its generated command is `azurite --blobHost 0.0.0.0 --queueHost … --tableHost …` [VERIFIED: javap ldc strings]. A later `withCommand` is overridden by `configure()`, so use the env var. Its `getConnectionString()` builds the literal key **at runtime from the jar constant**, so nothing appears in the repo.

### Pattern 6: Dev reseed, done as a script and never as a Flyway migration (D-04/D-05)
**Why not Flyway:** a migration runs everywhere (the nightly's fresh DB, every Testcontainers context, and one day staging/prod). The correct reseed **marks every pre-existing ACTIVE/PENDING `media_asset` FAILED**, because the bytes are gone. Run against a real store, that migration would destroy every live image. The target origin also differs per runtime (compose vs k8s/local), and a before-count that must be non-zero cannot be asserted in a migration that is correct at zero in CI.

**Why a pure URL rewrite is insufficient (verified):** `ProductService.resolveAssetFirst` (l.82–89) serves `productMediaRepository.findPrimaryActiveObjectKey(id).map(storageService::urlForKey)` **ahead of** the flat `image_url`. Every pre-existing ACTIVE asset (vendor uploads, load-test arms, the V53 backfill of `<t>/products/<pid>/<uuid>.<ext>` keys) resolves to a URL that 404s on a fresh Azurite and shadows any rewritten flat URL. The 2026-08-10 content-type measurement counted **768 objects** in the MinIO bucket (`check-media-content-types.sh:17`), so these rows are real.

**Algorithm** (one tenant-looped `DO` block per phase, run as the owner role `jtoye_app` so FORCE RLS applies, `psql -v ON_ERROR_STOP=1`, via `docker exec -i`):
0. Preconditions: Azurite is up and core-java has booted once, so DemoDataSeeder has re-uploaded the 21 seed objects under `<demo-tenant>/products/seed/`. A `--cutover <ISO-ts>` argument is supplied, and only rows `created_at < cutover` are touched. This guards any upload made after Azurite came up.
1. **Before-counts** per column, per tenant, with the GUC pinned (the "RLS blinds verification" trap). Assert `products.image_url LIKE 'http://localhost:9000/jtoye-images/%' > 0` summed over tenants. Products is the table known to hold rows (5 shops, 21 seeded products per D-04 and the seeder manifest).
2. `media_asset` with `status IN ('ACTIVE','PENDING') AND created_at < cutover` → `status='FAILED'`, `failure_reason='Bytes not carried over in the Phase 36 MinIO→Azurite dev reseed (D-04) — re-upload'`, `quarantine_reclaimed_at=now()`, `version=version+1` (the V59 `@Version`). The vendor review queue then shows these honestly as FAILED with a reason and a Re-upload button (IMG-04), and `resolveAssetFirst` falls through to the flat URL.
3. Flat columns with the old origin: a URL containing `/products/seed/` → rewrite the origin to the new `public-url` (the seeder already uploaded that deterministic key and also re-affirms it on boot; see `DemoDataSeeder.java:457-465`). Any other old-origin URL → `NULL` (`image_url`, `shops.logo_url`, `shops.banner_url`) or remove it from the array (`additional_image_urls`, `reviews.photo_urls`), because the bytes are lost (D-04). `shops.logo_url` values like `/brand/logo-*.png` are frontend static assets (`DemoDataSeeder.java:319-329`) and must stay untouched. The `LIKE` on the old origin already excludes them.
4. Leave the `_aud` tables unrewritten (append-only history). Record this.
5. **After-counts:** old-origin = 0 in every column across all tenants. Print both counts. Flush the Redis caches `products` (10 min TTL) and `shops` (15 min TTL) (`CacheConfig.java:35-41`), or wait them out (see Runtime State Inventory).
6. Then run `scripts/check-media-urls-resolve.sh` (read-only). It enumerates every URL the API can serve: the flat columns, plus `public-url + '/' + object_key` for ACTIVE assets and their `_thumb` siblings. It HEADs each anonymously and **fails on any non-200, and VOIDs when N = 0** (the A-2 denominator rule, as in `check-media-content-types.sh`).

### Pattern 7: pg-backup on Blob (D-01/D-02)
- Keep `k8s-backup.sh`'s dump/verify half unchanged (size floor, `pg_restore --list`, explicit rc). Replace only l.37–46, 71 and 77–93.
- **Tool: a tiny Go `blobctl`** (`upload <file> <container> <blobname>`, `list <container> <prefix>`, `download`). It uses `STORAGE_AUTH_MODE` exactly like core-java: `workload-identity` → `azidentity.NewWorkloadIdentityCredential(nil)` + `azblob.NewClient(endpoint, cred, nil)`, and `connection-string` → `azblob.NewClientFromConnectionString`. It is built in a multi-stage Dockerfile and copied into `postgres:15-bookworm` (the base stays; its major is governed by `scripts/gates/postgres-major-parity.conf:47-49`, so **do not change it here**). `awscli` is removed. **The Go SDK does NOT understand `UseDevelopmentStorage=true`** [VERIFIED: `sdk/storage/azblob/internal/shared/shared.go` at v1.8.1 has `ParseConnectionString` requiring `DefaultEndpointsProtocol` with no `UseDevelopmentStorage` match], so for Azurite `blobctl` needs the full connection string including the published dev key (see Pitfall 11 for the gitleaks consequence).
- **Retention vs WORM:** today the script prunes by filename date (`RETENTION_DAYS=30`, `aws s3 rm`). With WORM, a delete inside retention fails (a WARN, and the script continues). The recommendation is to **remove the in-script prune** and use (a) container-level time-based retention, (b) blob soft delete, and (c) a lifecycle-management delete rule after `retention + margin`. The CronJob identity then needs create/write only. Candidate custom-role data actions are `Microsoft.Storage/storageAccounts/blobServices/containers/blobs/write` and `…/blobs/add/action` `[ASSUMED action names — verify against the Azure RBAC reference in Phase 29]`. With no read, a compromised CronJob cannot exfiltrate old dumps (which hold PII). The fallback is built-in **Storage Blob Data Contributor scoped to the backup container**, where WORM blocks deletes within retention anyway.
- WORM facts [CITED: learn.microsoft.com immutable-storage-overview, 2026-08-18; container-level WORM page]: the retention interval is 1–146,000 days. A new policy starts **unlocked**, which can be shortened, extended or deleted, and MS recommends locking within ~24 h after testing. A **locked** policy cannot be deleted or shortened and allows at most 5 extensions at container level. Put Blob may create new blobs, but overwrites are never allowed. After expiry deletes are allowed but overwrites still are not. A container with a locked policy can be deleted only when empty, and only via the control plane. Soft delete should be enabled **before** immutability. WORM is **incompatible with point-in-time restore**. The docs are internally inconsistent on unlocked delete protection: the overview says both states "protect against deletes and overwrites", while the container-level page says "Unlocked policies don't provide delete protection" (in the account/container-deletion context). Treat unlocked as test-only.
- The timestamped filename `jtoye-backup-YYYYMMDD-HHMMSS.dump` is unique, so the "no overwrite" rule never bites.
- **Restore drill (local, proves the path):** run the rebuilt backup image with `docker run --network <compose-net>` against compose Postgres (as `jtoye_backup`) and Azurite. Then `blobctl list`, `blobctl download`, `pg_restore` into a scratch DB, and compare counts to live (arm B). Arm A is the zero-row dump from the 26-07 L4 recipe, `docs/runbooks/backups.md:281+`. **What it cannot prove:** WORM, soft delete and lifecycle behaviour. Azurite lists "Soft delete & Undelete Blob", "Blob Versions", "Version Level Worm" and "Blob Immutability Policy and Legal Hold" as NOT supported [VERIFIED: Azurite README v3.37.0 Support Matrix]. Those become Phase 29 read-backs (`az storage container immutability-policy show`, `az storage account blob-service-properties show`).

### Pattern 8: k8s wiring (repo half now, Azure half in Phase 29)
- **Base:** replace `s3.*`/`s3.backup.*` keys with `storage.blob.*` and `backup.blob.*` keys. Remove the `s3-media-credentials`/`s3-backup-credentials` references and their template entries. Add `ServiceAccount`s `core-java` and `pg-backup` with `automountServiceAccountToken: false` (the webhook projects its own token volume `[ASSUMED: WI works with automount off; verify in Phase 29]`). Set `serviceAccountName` on both pod templates.
- **Staging/production overlays:** `storage.blob.auth-mode: workload-identity`, the endpoint `https://<acct>.blob.core.windows.net`, `public-url`, and the pod-template label `azure.workload.identity/use: "true"` on core-java and pg-backup [CITED: AKS WI overview, "required in the pod template spec … Fail Close"]. The SA annotation `azure.workload.identity/client-id: <GUID>` is added **in Phase 29, when the identities exist**. Until then core-java's shape validation fails loudly (`AZURE_CLIENT_ID` absent), which is the correct, visible state for an undeployable overlay. Record it in the BLOB-10 handoff.
- **Local overlay:** `auth-mode: connection-string`. The connection string comes from a **local-only env patch plus an imperatively created Secret** (from `scripts/k8s-local-secrets.sh`, not committed). Use `UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://host.minikube.internal` for core-java, which requires Azurite's `--disableProductStyleUrl`, and the full string for `blobctl`. `public-url` stays browser-origin `http://localhost:10000/devstoreaccount1/<public-container>` (the same split horizon as `configmap-patch.yaml:147-155` documents for MinIO). LOC-3 becomes the Azurite backup endpoint.
- **NetworkPolicies:** delete the infra-namespace `9000` entries (`20-core-java.yaml:127-128`, `40-datastores.yaml:71-79`) and update INV-7 to `[core-java-allow]="__DB_PORT__ 5672 6379 9093 61613"` / `[pg-backup-allow]="__DB_PORT__"`. **Blob and the Entra token endpoint are both public 443**, already covered by the `0.0.0.0/0 except RFC1918 : 443` rules (`20-core-java.yaml:133-142`, `40-datastores.yaml:80-90`). Workload Identity needs **no IMDS** (IMDS is managed identity, not WI). Caveat: if Phase 29 fronts the **backup** account with a private endpoint, its IP is RFC1918 and the `except` blocks it. The media account cannot be private, because browsers fetch it (D-06). Put this in the handoff.
- **New render invariants** (each with a break arm): staging/prod render `STORAGE_AUTH_MODE=workload-identity`; the endpoint matches `^https://[a-z0-9]{3,24}\.blob\.core\.windows\.net$`; **zero** `secretKeyRef` to any storage/backup credential and zero `STORAGE_CONNECTION_STRING` env; the WI label is present on both pod templates; `serviceAccountName` is set.
- Goldens: `k8s/scripts/render-golden.sh --write`, then review the diff so that it shows ONLY the storage change (the Incremental Betterment proof, as in Phase 26).

### Pattern 9: Content-type gate on Blob
`check-media-content-types.sh` today does `docker exec` into `jtoye-minio` and runs its `mc`. Azurite has no CLI. The **host** `az` CLI (2.90.0 present) understands `UseDevelopmentStorage=true`: measured with `--debug`, it connects to `127.0.0.1:10000`. The recommendation is `az storage blob list --connection-string "UseDevelopmentStorage=true" -c <public-container> --num-results '*' --query '[].properties.contentSettings.contentType' -o tsv` against the loopback-published port, VOID when `az` is missing, and **scoped to the public container only**. Quarantine objects may legitimately be `application/octet-stream` (`MediaAssetService.java:159`) and are private. Keep A-1/A-2/A-3 unchanged. `[ASSUMED: az attaches the emulator SharedKey; the debug trace showed only the connect]`

### Anti-Patterns to Avoid
- **A Flyway migration for the reseed or the URL rewrite:** see Pattern 6. It would run on real stores one day.
- **A bare `UPDATE` as a superuser "because it bypasses RLS":** that technically works but violates D-05, and it skips proving the tenant loop. Use the owner role plus the tenant loop.
- **`DefaultAzureCredential` in AKS:** IMDS probing leads to timeouts under the 443-only NetworkPolicy. Use `WorkloadIdentityCredential`.
- **`blobClient.delete()` inside `deleteByKeyChecked`:** it throws on missing blobs and turns "already gone" into `false`, so the sweep never stamps its sentinel. Use `deleteIfExists()`.
- **`PublicAccessType.CONTAINER` on the public container:** it re-opens anonymous LIST (#626 regression).
- **A `*.blob.core.windows.net` wildcard** in `remotePatterns` or CSP: it would let `/_next/image` fetch from any Azure account.
- **Logging the connection string**, or putting one in a staging/prod Secret: D-02.
- **Hard-coding `/var/run/secrets/azure/tokens/azure-identity-token`:** read `AZURE_FEDERATED_TOKEN_FILE` [CITED: AKS WI overview "Important" note].

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SharedKey / SAS request signing | HMAC string-to-sign in bash/Java | The Azure SDK (Java/Go), `az` CLI on the host | Canonicalisation rules are subtle and version-dependent. |
| Entra token exchange from the projected SA token | `curl` to `login.microsoftonline.com` with a client assertion | `WorkloadIdentityCredential` (Java), `azidentity` (Go) | Token refresh: "Kubernetes refreshes the projected token in place … read the file again each time" [CITED]. |
| An Azurite container harness | A custom `GenericContainer` with a hand-written connection string | `org.testcontainers.azure.AzuriteContainer` | It generates the connection string with the emulator key from the jar and handles SSL options. |
| Emulator connection string in compose | A literal key | `UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite` (Java SDK) | No gitleaks hit, and no key literal to rotate or explain. |
| Anonymous-access proofs | Reasoning from config | A real anonymous HTTP GET/LIST against Azurite (which implements `PublicAccessAuthenticator`) | Azurite enforces blob-vs-container level [VERIFIED source]. |

**Key insight:** every Blob security property this phase depends on is a **container or account property**, not code. So the proofs have to be behavioural (anonymous HTTP against a real emulator) and runtime-asserted (the startup probe reads `getBlobPublicAccess()`). Only WORM and soft delete remain unprovable locally.

## Runtime State Inventory

This is a migration phase: after every file in the repo is updated, what still holds the old origin or the old store?

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| **Stored data** | Dev Postgres volume `jtoye_oaas_2026_postgres_data` (V66, 5 shops): old-origin absolute URLs in `products.image_url`, `products.additional_image_urls[]`, `shops.logo_url`/`banner_url` (vendor-uploaded ones; seeded logos are `/brand/…` and not storage), `reviews.photo_urls[]` (V27; client-supplied). ACTIVE/PENDING `media_asset` rows whose keys are origin-free but whose **bytes are gone**. `_aud` mirrors (`products_aud`, `shops_aud`) hold history. MinIO volume `jtoye_oaas_2026_minio_data` (3.6 MB per CONTEXT; the 2026-08-10 census was 768 objects). Row counts are **unmeasured**: the stack is down and this research did not start it. | **Data migration:** the dev reseed script (Pattern 6), counts measured in its dry run. `_aud`: leave. `minio_data` volume: **leave in place** (D-04), and never `down -v`. |
| **Live service config** | Redis caches `products`/`shops` (TTL 10/15 min) will serve DTOs carrying old URLs after the rewrite. Keycloak, RabbitMQ and Stripe hold no storage URLs. There is no external dashboard with the MinIO origin. | Flush `products` and `shops` in dev Redis after the reseed (or wait 15 min). Record which. |
| **OS-registered state** | None. No systemd/cron entry references MinIO (the pg-backup CronJob lives in k8s manifests, covered). Verified by the inventory above: no host scripts outside the repo were found by the grep, and **user crontab/systemd were not inspected** `[ASSUMED none]`. | None (state it in the plan). |
| **Secrets / env vars** | `.env` (untracked) holds `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD`, `MINIO_IMAGE_TAG`, `MINIO_MC_IMAGE_TAG`, `S3_*`, `K8S_LOCAL_MINIO_PORT`. With compose's `:?` guards gone, stale keys are harmless but misleading. k8s/local Secrets `s3-backup-credentials` and `s3-media-credentials` exist on any minikube cluster where `k8s-local-secrets.sh` ran. Phase 29's `~/.jtoye/staging-operator.env` (machine-local) lists the AWS pairs. | Code edit: `.env.example` + `verify-env.sh`. The developer's `.env` needs a documented manual step (add `STORAGE_*`, remove `MINIO_*`). The k8s/local stale Secrets: `kubectl delete secret` in the local profile only, when next used. The operator env: Phase 29 (BLOB-10 handoff). |
| **Build artifacts / images** | Local images for `quay.io/minio/minio`, `quay.io/minio/mc` (probably already gone after the 2026-09-27 prune); `ghcr.io/bralabee/jtoye-pg-backup:15` built with awscli; core-java images built with the AWS SDK; stale `core-java/build/` (the live dir is `build-local`). | Rebuild **all** images after the change (CLAUDE.md). Rebuild the pg-backup image locally with a **new tag** so that the parity gate row (`postgres-major-parity.conf:48-49`) and the CronJob image stay consistent (decide whether the tag stays `:15`). Run `check-runtime-freshness.sh` after bring-up. |

## Common Pitfalls

### Pitfall 1: "Existing suites pass against Azurite" is vacuous
**What goes wrong:** the criterion is claimed green when no test ever reached storage.
**Why:** every media IT spies StorageService and stubs its I/O (see Inventory, Tests).
**Avoid:** new `@Tag("testcontainers")` ITs with **unstubbed** storage against `AzuriteContainer`. Break arm: point `public-url`/containers at a wrong container, or stop the container. The test must go red.
**Warning sign:** a "media suite passed against Azurite" claim whose tests never declare an `AzuriteContainer`.

### Pitfall 2: The startup probe kills unrelated test contexts
**What:** `@ActiveProfiles("dev")` ITs (×2) and `{"prod","test"}`/`{"staging","test"}` contexts (×3) boot the full context. The probe, and the rule that refuses connection-string mode under prod/staging, will fail them.
**Avoid:** set `storage.blob.validate-on-startup: false` in `application-test.yml` and on the two dev-profile tests. For prod/staging-profile tests, supply workload-identity **shape** properties (`https://test.blob.core.windows.net`, and fake client/tenant ids plus a temp token-file path as env or properties) so that shape validation passes without a network. Then write a **dedicated** validator test that enables the probe against Azurite and proves both directions.

### Pitfall 3: Adding the probe changes the hybrid runtime's behaviour
**What:** `scripts/start-dev.sh`'s hybrid stack (`infra/docker-compose.yml`) has **no** object store. Today a host-process core-java boots there with a lazy, broken S3 client. A connectivity probe makes that boot **fail** (an Incremental-Betterment regression).
**Avoid:** either add Azurite to `infra/docker-compose.yml` (recommended, for parity) or default the probe off in `application-local.yml`/dev and on in staging/prod. Decide explicitly and record the displaced good.

### Pitfall 4: Azurite rejects the SDK's API version
**What:** "The API version … is not supported by Azurite" after a Dependabot bump of azure-storage-blob.
**Avoid:** pin Azurite at 3.37.0 (it matches 12.35.1 today) **and** set `--skipApiVersionCheck` / `AZURITE_SKIP_API_VERSION_CHECK=true`. Alternatively pin `serviceVersion(BlobServiceVersion.V2026_06_06)` in config.

### Pitfall 5: `deleteIfExists` vs `delete`, and the sentinel
See Anti-Patterns. `MediaQuarantineRetentionSweepTest` currently proves "a missing object counts as gone" with S3 semantics. Port that test first, because the semantics change silently.

### Pitfall 6: The split-horizon origin
**What:** core-java writes to `http://azurite:10000/…` (in-network) while the browser needs `http://localhost:10000/devstoreaccount1/<container>/…`. Using `blobClient.getBlobUrl()` for persisted URLs bakes the **in-network** host into rows. Every image then breaks in the browser while every upload succeeds.
**Avoid:** keep `urlForKey = publicUrl + "/" + key` (config-driven, as today), and never persist `getBlobUrl()`. Test it by asserting the stored URL's prefix equals `storage.blob.public-url`.

### Pitfall 7: The reseed proves nothing because RLS hides the rows
**What:** unpinned verification SELECTs return 0 and read as "rewrite complete" (memory: "RLS blinds the verification query").
**Avoid:** count inside the same tenant loop with the GUC pinned, and require before > 0 on products.

### Pitfall 8: `docker exec` without `-i`
**What:** a heredoc never reaches psql, the job reports success, and nothing ran (memory `trap_docker_exec_no_stdin_runs_nothing`).
**Avoid:** `docker exec -i … psql -v ON_ERROR_STOP=1`, and read the `UPDATE n` / `RAISE NOTICE` counts back.

### Pitfall 9: The staging render is "valid" but the identity is unwired
**What:** the manifests carry the label and SA, but the cluster has no WI webhook. `azure-staging-provision.sh` on `phase-29-research` uses `--enable-oidc-issuer` only (`git show phase-29-research:scripts/azure-staging-provision.sh | grep enable-workload-identity` returned nothing, while the positive control `enable-oidc-issuer` matched at l.555). Pods start with no `AZURE_*` env.
**Avoid:** BLOB-10 handoff: `az aks update -g jtoye-rg -n jtoye-staging-aks --enable-workload-identity --subscription c483d353…` (**pin the subscription**: the ambient default is the employer's). core-java's shape validation turns the omission into a crash-loop with a clear message instead of a silent one.

### Pitfall 10: The env-contract gate and webhook-injected env
**What:** `k8s/scripts/check-env-contract.sh` direction (b) fails on any env the service **reads** that the manifest does not supply. `AZURE_CLIENT_ID`/`AZURE_TENANT_ID`/`AZURE_FEDERATED_TOKEN_FILE`/`AZURE_AUTHORITY_HOST` are injected by the **admission webhook**, not the manifest.
**Avoid:** do not reference them as `${AZURE_*}` in `application.yml` (the SDK reads the env itself). If the validator reads them from Java, expect a direction-(b) allowlist entry reasoned as "injected by the AKS workload-identity mutating webhook at admission (learn.microsoft.com/azure/aks/workload-identity-overview)".

### Pitfall 11: gitleaks on the Azurite key
**Measured:** a scratch file containing `AccountKey=<the published devstoreaccount1 key>` made `gitleaks dir … --config .gitleaks.toml` exit **rc=1**, rule `generic-api-key`. **Avoid:** the Java side uses `UseDevelopmentStorage=true`, so no literal is needed. For sites that do need the literal (`blobctl` against Azurite in `k8s-local-secrets.sh` / the local restore drill), add **one targeted `[[allowlists]]` regex matching exactly the Microsoft-published key**. Prove both directions: that key passes, and a different random `AccountKey=` still fails.

### Pitfall 12: The goldens and `phase-29-research` collide
**What:** that branch modifies 46 files that this phase also touches, including `k8s/base/configmap.yaml`, `core-java-deployment.yaml`, both netpols, both goldens, `check-render-invariants.sh`, `check-env-contract.sh`, the local/staging/production overlays, `application.yml`, the compose file, `dependency-horizons.yaml` and `ci-cd.yaml` (`git diff --name-only $(git merge-base main phase-29-research) phase-29-research`). The branch is 96 commits ahead and main is 82 ahead of the merge-base (2026-08-10).
**Avoid:** keep the base diffs minimal and key-local. Publish a conflict map in the BLOB-10 handoff. When Phase 29 resumes, regenerate the goldens with `--write` after the merge rather than hand-merging them.

## Code Examples

### #626 proven both ways against Azurite (JUnit 5 + Testcontainers)
```java
// Source: Azurite PublicAccessAuthenticator (v3.37.0) — BLOB level allows Blob_Download/GetProperties,
// NOT Container_ListBlobFlatSegment/HierarchySegment. Anonymous = NO Authorization header.
@Test void publicBlobReadableByUrlButContainerNotListable() throws Exception {
    String url = storage.putBytes(tenant + "/media/" + UUID.randomUUID() + ".webp", webp, "image/webp");
    HttpClient http = HttpClient.newHttpClient();
    HttpResponse<byte[]> get = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), BodyHandlers.ofByteArray());
    assertThat(get.statusCode()).isEqualTo(200);
    assertThat(get.body()).isEqualTo(webp);
    assertThat(get.headers().firstValue("Cache-Control")).contains("public, max-age=31536000, immutable");
    String list = blobEndpoint + "/" + publicContainer + "?restype=container&comp=list";
    HttpResponse<String> ls = http.send(HttpRequest.newBuilder(URI.create(list)).GET().build(), BodyHandlers.ofString());
    assertThat(ls.statusCode()).isNotEqualTo(200);                 // exact code [ASSUMED 403/404] — assert "not 200"
    assertThat(ls.body()).doesNotContain("<EnumerationResults");    // no inventory leaked
}
// BREAK ARM (must go red): create the public container with PublicAccessType.CONTAINER → LIST returns 200.
```

### Dev reseed tenant loop skeleton (owner role; D-05)
```sql
-- Source: V53__*.sql:157-256 loop shape (read this session). Old origin literal is the compose value
-- "http://localhost:9000/jtoye-images" (docker-compose.full-stack.yml:354, verified).
DO $$
DECLARE t RECORD; n_before BIGINT := 0; n BIGINT;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.current_tenant_id', t.id::text, true);
    SELECT count(*) INTO n FROM products
     WHERE tenant_id = t.id AND image_url LIKE 'http://localhost:9000/jtoye-images/%';
    n_before := n_before + n;
    -- ... media_asset FAILED flip (created_at < :cutover), flat-column rewrite/NULL, array_remove ...
  END LOOP;
  PERFORM set_config('app.current_tenant_id', '', true);
  IF n_before = 0 THEN RAISE EXCEPTION 'dev reseed: 0 old-origin product rows seen — RLS/tenant loop or wrong origin; refusing'; END IF;
  RAISE NOTICE 'dev reseed: products old-origin before=%', n_before;
END $$;
```

### Workload Identity pod wiring (staging overlay patch)
```yaml
# Source: learn.microsoft.com/azure/aks/workload-identity-overview + workload-identity-deploy-cluster
apiVersion: v1
kind: ServiceAccount
metadata:
  name: core-java
  annotations:
    azure.workload.identity/client-id: "<added in Phase 29 from az identity show --query clientId>"
---
# Deployment core-java: spec.template.metadata.labels
azure.workload.identity/use: "true"
# FIC (Phase 29): az identity federated-credential create --issuer "$AKS_OIDC_ISSUER" \
#   --subject system:serviceaccount:<namespace>:core-java --audience api://AzureADTokenExchange
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| MinIO community images (Docker Hub / quay.io) | Withdrawn: repo archived 2026-04-25, Hub stopped 2026-09-12, quay 401 since ~2026-09-24 (CONTEXT/ROADMAP) | 2026 | This is why the phase exists (#683 nightly red). |
| AKS pod-managed identity (IMDS/NMI) | Microsoft Entra Workload ID (OIDC federation, projected SA token) | GA 2023 `[ASSUMED date]` | No IMDS egress needed; the pod label is required. |
| New storage accounts allow anonymous access | New accounts default to **disallow** `AllowBlobPublicAccess` `[ASSUMED: default flipped for new accounts ~2023]`; must be set `true` explicitly | — | Phase 29 must set it on the media account, and only that one. |
| Azurite strict API-version check only | `--skipApiVersionCheck` flag + `AZURITE_SKIP_API_VERSION_CHECK` env (3.37.0) | 2026-08 | Decouples SDK bumps from the emulator pin. |

**Deprecated/outdated:** the `software.amazon.awssdk` S3 client, `forcePathStyle(true)`, the `mc anonymous set-json` policy mechanism, and `aws s3 cp` in the backup image.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Excluding `msal4j-persistence-extension` does not break `WorkloadIdentityCredential` | Standard Stack | A build or runtime error in staging. Mitigate with a unit test that builds the credential. |
| A2 | netty falls back to JDK SSL if `netty-tcnative-boringssl-static` is excluded (musl image) | Standard Stack | A TLS failure to real Azure, first seen in Phase 29. Keep the jar if unsure. |
| A3 | `DefaultAzureCredential` would probe IMDS and time out under the 443-only netpol | Alternatives | Low. The recommendation is explicit WI regardless. |
| A4 | A connection-refused against Azurite surfaces as a non-`BlobStorageException` RuntimeException | Pattern 3 | The seeder skips per-entry instead of aborting, which makes dev boot slow. Prove it with a stopped-Azurite arm. |
| A5 | The exact status for an anonymous LIST refusal (403 vs 404) | Code Examples | None if the test asserts "not 200 + no `<EnumerationResults`". |
| A6 | WI works with `automountServiceAccountToken: false` | Pattern 8 | Pods get no token and core-java fails fast (visible). Verify in Phase 29. |
| A7 | Custom-role data-action names for a write-only backup identity | Pattern 7 | Phase 29 provisioning error. Fallback: Blob Data Contributor at container scope. |
| A8 | The `az` CLI attaches the emulator SharedKey for `UseDevelopmentStorage=true` | Pattern 9 | The content-type gate VOIDs on 403. Fallback: an explicit connection string with the allowlisted key. |
| A9 | The Azurite image has `wget`/`nc`/`node` for a healthcheck | Pattern 5 | Healthcheck design. Probe the image first. |
| A10 | New storage accounts default to `AllowBlobPublicAccess=false` | State of the Art | Phase 29 must set it explicitly either way. |
| A11 | No user crontab/systemd references MinIO | Runtime State | Low. |
| A12 | Region pairing: UK South ↔ UK West (the backup account in `ukwest`) | Open Q2 | A region-choice error. Confirm in Phase 29 with `az account list-locations`. |
| A13 | The azure-storage-blob 12.x line has been on Maven Central since ~2019 | Legitimacy | None (provenance only). |

## Open Questions (for human checkpoints)

1. **Container names (a one-way door: they are embedded in every persisted URL via `public-url`).**
   - What we know: `jtoye-images` is a valid Blob container name and carries continuity (docs, gates, #626 language). Quarantine needs a new private container.
   - Recommendation: `jtoye-images` (public, level `blob`) plus `jtoye-quarantine` (private). Backup: `jtoye-db-backups`, prefix `backups/`. **Checkpoint before plan 36-01 lands.**
2. **Staging/prod storage account names and regions (global uniqueness, 3–24 lowercase alphanumerics).**
   - What we know: AKS is `uksouth` (Phase 29 evidence: `MC_jtoye-rg_jtoye-staging-aks_uksouth`, OIDC issuer `https://uksouth.oic.prod-aks.azure.com/…`).
   - Recommendation: the media account in `uksouth`, the backup account in `ukwest` `[ASSUMED pair]`. Check the names read-only with `az storage account check-name --name <n> --subscription c483d353…`. The chosen names go into the staging/production overlays and goldens in this phase (a `<<MEASURED>>` placeholder cannot ship: `check-no-measured-placeholders.sh`). **Checkpoint.**
3. **WORM retention value + lock timing (D-01 one-way door).**
   - Recommendation: container-level time-based retention equal to the dump retention (30 days, matching today's `RETENTION_DAYS`), **left unlocked** through the Phase 29 restore drill and then locked by a human. Soft delete at 14 days, and a lifecycle delete at 35 days. **Checkpoint in the BLOB-10 handoff; nothing is locked in this phase** (there is no Azure account in scope).
4. **The public URL origin in dev:** `http://localhost:10000/devstoreaccount1/jtoye-images` (host-published, loopback). Confirm port 10000 is free on the dev host (measured: `ss -ltn` shows no listener on 10000–10002 among 13 LISTEN sockets).
5. **The hybrid runtime:** add Azurite to `infra/docker-compose.yml`, or keep the probe off in dev? (Pitfall 3.)
6. **Pre-existing cross-tenant delete vector (security, see §Security).** Fix it in this phase (a small guard in `delete(url)`) or record it as accepted? With `security_block_on: medium` it should be fixed.
7. **Is the pg-backup image tag bumped?** `:15` is referenced by the parity gate and the CronJob. A content change under the same tag is a mutable tag.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker + Compose v2 | compose stack, Testcontainers, Azurite | ✓ | Docker 29.8.1, Compose v5.5.1 | — |
| JDK | core-java build/tests | ✓ | openjdk 25.0.4.1 | — |
| Go | `blobctl` build | ✓ | go1.27.1 | Build inside the Docker multi-stage |
| Node/npm | frontend jest/build | ✓ (PATH node v22.23.3; the project requires 24+) | v22 / npm 12.1.0 | Use nvm 24, as CI does `setup-node 24` |
| Azure CLI | content-type gate, name checks, Phase 29 handoff | ✓ | 2.90.0 | — |
| psql | reseed (host) | ✓ | 16.15 | `docker exec -i jtoye-postgres psql` (preferred) |
| gitleaks | Pitfall 11 proof | ✓ | 8.30.1 | CI workflow |
| kubectl | k8s render / local | ✓ | 1.35.9 | — |
| kustomize (standalone) | render | ✗ | — | `kubectl kustomize` (the scripts already use it) |
| trivy | image-gate pre-check | ✗ | — | Let CI's Trivy image gate run on the PR, or `docker run aquasec/trivy` |
| azcopy | (not recommended) | ✗ | — | `blobctl` |
| shellcheck | new bash gates | ✗ | — | CI, or `docker run koalaman/shellcheck` |
| MCR anonymous pull (Azurite) | compose/nightly/Testcontainers | ✓ | manifest HEAD answered with a digest, unauthenticated | — |
| Azure subscription (staging) | WI, WORM, real Blob | not in scope (D-03) | — | Phase 29 |

**Missing dependencies with no fallback:** none for this phase's scope.
**Missing with fallback:** kustomize, trivy, shellcheck, azcopy (listed above).
**State note:** the dev stack is DOWN (`docker ps` empty). Volumes are intact (`jtoye_oaas_2026_{postgres,minio,…}_data`). This research started nothing.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Testcontainers 1.21.4 (`@Tag("testcontainers")`), Jest 30.5.1, Playwright 1.62.1, bash gates (exit 0/1/2 = pass/fail/VOID) |
| Config file | `core-java/build.gradle.kts` (the `test` task excludes the tag; the `integrationTest` task includes it), `frontend/jest.config.*`, `frontend/playwright.config.*` |
| Quick run command | `./gradlew :core-java:test --tests 'uk.jtoye.core.storage.*'` |
| Full suite command | `./gradlew :core-java:test :core-java:integrationTest && (cd frontend && npm test -- --ci) && bash k8s/scripts/check-render-invariants.sh && bash k8s/scripts/render-golden.sh` |

### Phase Requirements → Test Map (each row names the FAIL arm that must be observed first)
| Req | Behaviour | Type | Automated command | Deliberate break (must go red) | File exists? |
|-----|-----------|------|-------------------|-------------------------------|--------------|
| BLOB-01 | No AWS SDK in main or on the runtime classpath | gate | `git grep -n -E 'software\.amazon\|S3Client\|awssdk' -- core-java/src/main core-java/build.gradle.kts; echo rc=$?` expects rc=1; `./gradlew -q :core-java:dependencies --configuration runtimeClasspath > deps.txt; grep -c 'software.amazon' <<< "$(cat deps.txt)"` expects 0 | Run on the pre-change tree: 3 files / >0 deps (already measured: 3 main files) | ❌ Wave 0 (gate script) |
| BLOB-01 | Shape validation fails fast | unit | `./gradlew :core-java:test --tests '*StorageConfig*'` | Unknown mode; connection-string under `prod`; WI without `AZURE_CLIENT_ID`; `create-containers` under WI: **each must throw** | ❌ Wave 0 |
| BLOB-01 | Startup probe fails fast | IT | `./gradlew :core-java:integrationTest --tests '*StorageStartupValidator*'` | Missing container; public container at `CONTAINER` level; quarantine public: context must fail | ❌ |
| BLOB-02 | #626 rule + quarantine private | IT (Azurite) | `./gradlew :core-java:integrationTest --tests '*AzuriteStorageIntegrationTest*'` | Create public at `CONTAINER` → LIST 200 → red; swap routing → quarantine GET 200 → red | ❌ |
| BLOB-03 | Pipeline on real Azurite | IT | `./gradlew :core-java:integrationTest --tests '*MediaPipelineAzuriteIntegrationTest*'` | Force the normalizer to fail → no derivative; skip the quarantine delete → quarantine blob still present → red | ❌ |
| BLOB-03 | Existing stubbed suites stay green | IT | `./gradlew :core-java:integrationTest --tests 'uk.jtoye.core.media.*'` | (regression only, and **not** evidence of storage behaviour; state this) | ✅ |
| BLOB-01 | Rewritten unit tests (4 files) | unit | `./gradlew :core-java:test --tests '*StorageServiceTest' --tests '*LegacyImageUploadPipelineTest' --tests '*ShopBrandImageKeyTest' --tests '*MediaQuarantineRetentionSweepTest'` | Revert `deleteIfExists` → `delete` → the "absent = gone" test goes red | ✅ (rewrite) |
| BLOB-04 | Compose: digest-pinned Azurite, no MinIO | gate | `docker compose -f docker-compose.full-stack.yml config --format json > c.json; jq -e '.services.azurite.image \| test("@sha256:[0-9a-f]{64}$")' c.json && jq -e '.services \| has("minio") or has("minio-init") \| not' c.json` | Remove the digest → jq false (rc 1) | ❌ |
| BLOB-04 | Nightly runs Playwright | CI | `gh workflow run e2e-nightly.yml`, then check the run log for a Playwright executed-count > 0 (not merely "stack up") | Historic fail direction: the #683 runs red at pull since 2026-09-25 | ✅ workflow |
| BLOB-05 | Reseed non-zero + zero residue | script | `bash scripts/dev-media-reseed.sh --dry-run --cutover <ts>`, then `--apply` | Point it at a wrong origin → before=0 → **must refuse (exit ≠ 0)** | ❌ |
| BLOB-05 | Every servable URL resolves | gate | `bash scripts/check-media-urls-resolve.sh` (exit 0/1/2) | Delete one blob or insert one bogus URL row → exit 1; empty set → exit 2 | ❌ |
| BLOB-05 | Browser renders storage images | e2e | `cd frontend && npx playwright test e2e/storage-images.spec.ts` asserting ≥ 1 `img` whose `src` starts with the storage public URL and `naturalWidth > 0` | `docker stop jtoye-azurite` → naturalWidth 0 → red. **The existing `storefront-flows.spec.ts` contract #3 accepts fallback tiles and `/brand/` logos, so it cannot prove this.** | ❌ |
| BLOB-06 | Backup to Blob + restore | script | `bash infra/backups/restore-drill-local.sh` (docker-run backup → blobctl list/download → pg_restore → counts) | Arm A: zero-row dump passes size/TOC checks but the count comparison fails (26-07 L4 recipe) | ❌ |
| BLOB-06 | blobctl both auth modes | unit (Go) | `cd infra/backups/blobctl && go test ./...` | Unknown mode / missing env → error | ❌ |
| BLOB-07 | k8s invariants | gate | `bash k8s/scripts/check-render-invariants.sh && bash k8s/scripts/render-golden.sh && bash k8s/scripts/check-env-contract.sh` | Re-add port 9000 → INV-7 red; drop the WI label → new invariant red; add a storage `secretKeyRef` to staging → red | ✅ (extend) |
| BLOB-08 | CSP / remotePatterns exact | jest | `cd frontend && npx jest __tests__/csp-headers.test.ts __tests__/header-snapshot.test.ts` | Add `https://*.blob.core.windows.net` → a new "no wildcard" assertion goes red; keep `localhost:9000` → red | ✅ (extend) |
| BLOB-09 | Zero residue | gate | `bash scripts/check-no-object-store-residue.sh` | Plant `minio` in a non-allowlisted file → exit 1; an allowlist entry with an empty reason → exit 1 | ❌ |
| BLOB-09 | Horizons | gate | `bash scripts/check-dependency-horizons.sh` | Remove the azurite row → H-1 exit 1 | ✅ |
| BLOB-09 | Metrics / docs | gate | `bash scripts/docs-freshness.sh && bash scripts/check-doc-metrics.sh` | Skip `--write` after adding tests → red | ✅ |
| all | Runtime parity | gate | `bash scripts/check-runtime-freshness.sh && bash scripts/check-branch-behind-base.sh` | `compose start` without a rebuild → freshness red | ✅ |
| BLOB-09 | gitleaks with the allowlist | gate | `gitleaks dir . --config .gitleaks.toml` | A different random `AccountKey=` must still fire (rc=1) | ✅ (config) |

### Sampling Rate
- **Per task commit:** the quick run command plus the row-specific gate for the files touched.
- **Per wave merge:** `./gradlew :core-java:test :core-java:integrationTest`, jest, the k8s gates, and the residue gate.
- **Phase gate:** full suite green, ALL images rebuilt, `check-runtime-freshness.sh` PASS, the reseed plus URL-resolve gate plus the Playwright storage spec against the running stack, a nightly `workflow_dispatch` run executing Playwright, then `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] `core-java/src/test/java/uk/jtoye/core/storage/AzuriteStorageIntegrationTest.java`: BLOB-02
- [ ] `…/storage/StorageStartupValidatorTest.java` + a shape-validation unit test: BLOB-01
- [ ] `…/media/MediaPipelineAzuriteIntegrationTest.java`: BLOB-03
- [ ] A shared Azurite test fixture (singleton container, digest-pinned, `AZURITE_SKIP_API_VERSION_CHECK=true`)
- [ ] `scripts/check-no-object-store-residue.sh`, `scripts/check-media-urls-resolve.sh`, `scripts/dev-media-reseed.sh`: wire the gate into CI via `scripts/gates/gate-enforcement.conf` (memory: "unwired gates run nowhere")
- [ ] `frontend/e2e/storage-images.spec.ts`
- [ ] `infra/backups/blobctl/` (+ `_test.go`), `infra/backups/restore-drill-local.sh`
- [ ] `application-test.yml`: `storage.blob.validate-on-startup: false`

## Security Domain

`security_enforcement` is on (absent = enabled), with ASVS level 2 and `block_on: medium`.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V1 Architecture | yes | Container-level access split; separate media vs backup accounts and identities (D-01/D-02) |
| V2 Authentication | yes (service-to-service) | Workload Identity (federated, no stored secret); the Azurite dev key only in dev |
| V3 Session Management | no | — |
| V4 Access Control | yes | Blob-level public container, private quarantine, RBAC scoped per account/container; **tenant-prefix guard on URL-based delete** (below) |
| V5 Input Validation | yes | Keys are server-generated; container names validated; content-type is always detected/produced (existing T-24-02) |
| V6 Cryptography | yes (TLS only) | SDK TLS to `*.blob.core.windows.net`; nothing hand-rolled |
| V7 Error Handling & Logging | yes | Never log connection strings; log mode and endpoint host only |
| V8 Data Protection | yes | Quarantine raw bytes (EXIF/GPS) private (fixes the current public exposure); backups with PII are write-only to the CronJob identity; WORM + soft delete |
| V10 Malicious Code / supply chain | yes | Digest-pinned Azurite; SDK versions pinned; Trivy image gate on core-java |
| V14 Configuration | yes | Fail-fast shape validation; staging/prod refuse connection-string mode; no storage Secret in staging/prod renders (invariant) |

### Known Threat Patterns for this stack
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Anonymous LIST enumerates every tenant's images (#626) | Information disclosure | Level `blob`, never `container`; Azurite test proves both ways; startup probe asserts `getBlobPublicAccess()==BLOB` |
| Quarantine raw upload readable anonymously (**pre-existing** in MinIO: it is in the public bucket) | Information disclosure (EXIF GPS) | Private quarantine container + routing test |
| **Cross-tenant object delete via a client-supplied URL (pre-existing, verified):** `ProductMapper.toEntity/updateEntity` map `CreateProductRequest.imageUrl` (not in the ignore list, `ProductMapper.java:23-29,43-51`); `ShopMapper` maps `logoUrl`/`bannerUrl`; `ReviewService.java:93` stores client `photoUrls`. `StorageService.delete(url)` deletes any key under the public prefix (`StorageService.java:289-307`), and the shared container spans tenants | Tampering / DoS (another tenant's images deleted) | In the rewritten `delete(url)`: the extracted key's first segment must equal `TenantContext` tenant id, else skip + WARN. Test with tenant B's URL under tenant A's context → the blob survives. (Optionally also reject such URLs on create/update.) |
| Connection string / account key leaks via logs or config | Information disclosure | No logging; `UseDevelopmentStorage=true` in dev; refuse connection-string mode under prod/staging; render invariant |
| Over-privileged app identity (account-wide contributor, control-plane rights) | Elevation | Data-plane role at **container** scope for media; no `listkeys` / Storage Account Contributor; containers and ACLs provisioned by IaC |
| Backup identity can read or delete dumps | Tampering / Info disclosure | Write-only custom role (A7) or container-scoped Contributor + WORM |
| WORM locked with the wrong retention | Availability / cost (irreversible) | Unlocked during the drill; human-checkpoint lock (D-01) |
| SSRF via Next `/_next/image` with a broad `remotePatterns` | SSRF | Exact origins only; no wildcard; test asserts it |
| Mutable Azurite tag runs a different emulator | Supply chain | Digest pin + horizons row |
| Private endpoint on the backup account blocked by the RFC1918 `except` | Availability | Handoff note; add a scoped ipBlock if Phase 29 chooses a private endpoint |

## Recommended Plan Decomposition (fine granularity, tracer-first)

| Plan | Wave | Content | Reqs | Checkpoint |
|------|------|---------|------|------------|
| 36-01 **tracer** | 1 | Azure deps (+ exclusions); `StorageProperties storage.blob.*`; `StorageConfig` switch + shape validation; `BlobObjectStore` adapter; `StorageService` on the adapter with key routing (surface unchanged); rewrite the 4 compile-breaking tests; **one** `AzuriteStorageIntegrationTest` (round-trip + #626 both ways + quarantine private); compose `azurite` service replacing `minio`/`minio-init` + core-java env + nightly `SERVICES` + `verify-env.sh`/`.env.example`; `DemoDataSeeder` exception mapping; a local Trivy (docker) or CI image-gate pre-check on the new jars. Lands a thin working slice: compose up, then a vendor upload works end to end. | BLOB-01, 02 (partial), 04 (partial) | **Human: container names + dev public URL/port (OQ1, OQ4)** before merge |
| 36-02 | 2 | Startup probe (property-gated) + all fail-fast tests incl. prod/staging-profile contexts; retry options; log redaction; `application-test.yml`; hybrid-runtime decision (Pitfall 3) | BLOB-01 | — |
| 36-03 | 2 | `MediaPipelineAzuriteIntegrationTest` (quarantine → worker → derivative → quarantine delete; sweep `deleteByKeyChecked` on a missing blob); **tenant-prefix guard in `delete(url)`** + a cross-tenant test | BLOB-02, 03 | OQ6 |
| 36-04 | 2 | Frontend CSP `img-src` + `remotePatterns` exact origins + jest/snapshot + fixture URL renames | BLOB-08 | — |
| 36-05 | 2 | `blobctl` (Go) + tests; backup Dockerfile (drop awscli); `k8s-backup.sh` upload via blobctl, prune removed or retained per OQ3; local restore drill (arms A/B); `docs/runbooks/backups.md` rewrite; gitleaks targeted allowlist + proof | BLOB-06 | OQ3 recorded, OQ7 |
| 36-06 | 3 | k8s base/overlays (keys, env, ServiceAccounts, WI label in staging/prod, local Azurite shim), netpols (drop 9000), INV-7/LOC-3 + new invariants, env-contract allowlist, `k8s-local-secrets.sh` / guards / `k8s-local-up.sh`, secrets template, goldens `--write` | BLOB-07 | **Human: staging/prod account names (OQ2)** |
| 36-07 | 3 | Rebuild ALL images; dev reseed script (dry run, then human-approved `--apply`); Redis flush; `check-media-urls-resolve.sh`; Playwright `storage-images.spec.ts`; browser proof | BLOB-05 | **Human: approve `--apply` against the dev DB; eyeball the storefront** |
| 36-08 | 4 | Residue gate + allowlist + CI wiring; docs/CLAUDE.md/AGENTS.md/.cursor/.github instructions; horizons rows; `check-media-content-types.sh` re-target; docs metrics `--write`; **BLOB-10 Phase 29 handoff doc** (provisioning spec, `--enable-workload-identity`, FIC subjects, RBAC, `AllowBlobPublicAccess` on media only, WORM/soft delete/lifecycle, operator list 7→3, conflict map); nightly `workflow_dispatch` proof | BLOB-09, 10, 04 | — |

**One-way doors (human checkpoints):** container names (OQ1), dev public origin/port (OQ4), staging/prod account names and regions (OQ2), WORM retention and lock (OQ3, Phase 29 execution), and approval of the dev reseed `--apply` (it mutates the shared dev DB, and D-04 accepts the loss of hand-uploaded images).

## Sources

### Primary (HIGH confidence: read or executed this session)
- Repo files read (paths and line ranges cited inline): StorageService/Config/Properties, MediaAssetService, MediaProcessingWorker, MediaQuarantineRetentionSweep, MediaAssetRepository, DemoDataSeeder, ProductService, ProductMapper, ReviewService, GdprService, application.yml, V53 migration, docker-compose.full-stack.yml, e2e-nightly.yml, k8s base/local configmaps, netpols, pg-backup-cronjob, check-render-invariants.sh, next.config.mjs, security-headers.ts, infra/backups/*, dependency-horizons.yaml, core-java/build.gradle.kts
- `git show phase-29-research:` 29-10-SUMMARY.md, 29-CONTEXT.md (D-09..D-12), scripts/azure-staging-provision.sh, scripts/staging-secrets.sh (read-only)
- Maven Central metadata and POMs: azure-storage-blob, azure-identity, azure-core, azure-core-http-netty, azure-sdk-bom 1.3.8, msal4j, spring-boot-dependencies 3.5.16, netty-bom 4.1.137
- javap of the azure-storage-blob 12.35.1, azure-storage-common 12.34.1, azure-identity 1.18.6 and testcontainers azure 1.21.4 jars
- Azurite v3.37.0 source: `src/blob/authentication/PublicAccessAuthenticator.ts`, `src/blob/middlewares/blobStorageContext.middleware.ts`, `src/common/utils/constants.ts`, README (Support Matrix, flags)
- GitHub releases API (Azurite, azcopy), MCR registry API (tags, digest), proxy.golang.org (azblob, azidentity), azure-sdk-for-go `shared.go` v1.8.1
- Local executions: gitleaks 8.30.1 on a scratch file (rc=1 `generic-api-key`), `az --debug` with `UseDevelopmentStorage=true` (→ 127.0.0.1:10000), `ss -ltn` (port 10000 free), tool version probes

### Secondary (MEDIUM: official docs, not exercised)
- learn.microsoft.com/azure/aks/workload-identity-overview (2026-09-04) and /workload-identity-deploy-cluster (2026-07-31)
- learn.microsoft.com/azure/storage/blobs/immutable-storage-overview (2026-08-18), /immutable-container-level-worm-policies
- learn.microsoft.com/azure/storage/blobs/anonymous-read-access-configure (2026-08-20)
- learn.microsoft.com/azure/storage/common/storage-use-azcopy-authorize-azure-active-directory
- github.com/Azure/azure-storage-azcopy/wiki/azcopy_login

### Tertiary (LOW: web search only)
- [Support for AAD Workload Identity · azure-storage-azcopy #2545](https://github.com/Azure/azure-storage-azcopy/issues/2545)
- [Auto-Login and CI/CD Integration | azure-storage-azcopy DeepWiki](https://deepwiki.com/Azure/azure-storage-azcopy/4.4-auto-login-and-cicd-integration)

Research-seam note: `gsd_run query research-plan` routed three questions to `context7`, which is not available in this runtime, so the official MS Learn pages were fetched directly. The seam's `classify-confidence` rates `webfetch` LOW regardless of domain. The digests were cached with `research-store put` at the seam's tier; in this document those facts carry `[CITED]` (official docs).

## Metadata

**Confidence breakdown:**
- Repo inventory and seam analysis: HIGH. Read from source, with reproducible counts and a positive control.
- Standard stack: HIGH. Versions come from Maven Central, and compatibility from the POMs and bytecode.
- Azurite behaviour (#626, URL parsing, unsupported features): HIGH. Read from Azurite source and README at the pinned version.
- Azure control plane (WI, WORM, anonymous access): MEDIUM. Official docs, unexercised (Phase 29).
- Backup tooling choice: MEDIUM. A reasoned trade-off. The azcopy/Azurite compatibility is unverified.
- Pitfalls: HIGH for the ones tied to measured repo facts (1, 2, 3, 9, 11, 12); MEDIUM otherwise.

**Research date:** 2026-09-28
**Valid until:** 2026-10-28 (the SDK and Azurite release monthly; re-check the pins at plan time if later)
