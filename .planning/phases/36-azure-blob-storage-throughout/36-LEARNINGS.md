---
phase: 36
phase_name: "azure-blob-storage-throughout"
project: "J'Toye OaaS — Milestone v2.3: Vendor Ops + AI Interleaved"
generated: "2026-09-29"
counts:
  decisions: 10
  lessons: 9
  patterns: 9
  surprises: 8
missing_artifacts: []
---

# Phase 36 Learnings: azure-blob-storage-throughout

## Decisions

### Azure Blob throughout, Azurite locally and in the nightly
Object storage moved from the S3 API (MinIO locally, plus a never-provisioned AWS S3 eu-west-2 target in
`k8s/{staging,production}`) to Azure Blob Storage in staging/production and a digest-pinned Azurite
(`mcr.microsoft.com/azure-storage/azurite:3.37.0@sha256:8304…1fd5`) in compose, the hybrid runtime and the
nightly. MinIO, the S3 SDK and every AWS storage credential left the platform.

**Rationale:** Owner ruling 2026-09-28 ("go with A, Azure Blob throughout"), forced by MinIO withdrawing its
community images (quay.io 401 to anonymous pulls since ~2026-09-24), which caused #683 and blocked the local restart.
**Source:** 36-CONTEXT.md (Phase Boundary), 36-02-SUMMARY.md

### Backups go to a separate, immutable Azure account in another region (supersedes Phase 29 D-12)
The `pg-backup` logical dump goes to a dedicated backup storage account in `ukwest` (media and AKS are in
`uksouth`), with a WORM policy and soft delete on `jtoye-db-backups`. Phase 29 D-12 ("AWS S3, off-cluster AND
off-Azure") is superseded.

**Rationale:** Survives object deletion and a compromised app credential. The accepted trade is that it does
NOT survive loss of the whole Azure subscription, which is the case D-12 existed for. An off-Azure copy is
deferred explicitly.
**Source:** 36-CONTEXT.md (D-01, D-11, Deferred Ideas)

### AKS Workload Identity; no stored key anywhere
core-java and the backup CronJob authenticate with Workload Identity. Each uses a federated credential and a
data-plane RBAC role scoped to the one account it needs. No account key, connection string or SAS sits in any
k8s Secret. One `storage.blob.auth-mode` switch selects `connection-string` (Azurite) or `workload-identity`.
Any connection string that is not the emulator form is refused at startup in every profile. The operator
secret list drops from 7 to 3 values.

**Rationale:** Least privilege under the agent-readiness contract. A misconfiguration must fail fast at
startup, not at the first upload.
**Source:** 36-CONTEXT.md (D-02, D-03), 36-01-SUMMARY.md, 36-06-SUMMARY.md, 36-17-SUMMARY.md

### Reseed dev media; do not migrate it
The 3.6 MB in `minio_data` was not migrated. The demo seeder regenerates dev media on Azurite. Persisted URLs
were rewritten through a tenant-looped `set_config` loop with a non-zero before-count. Pre-cutover ACTIVE
assets were marked FAILED with a Phase 36 reason. The old volume stays on disk. The reseed is a one-off
script, not a Flyway migration.

**Rationale:** MinIO's on-disk format is not portable, and the standing "leave all volumes" ruling applies. A
Flyway migration would mark real vendors' live media FAILED on a real store. D-05 exists because of the
recurring RLS-backfill trap, where a bare UPDATE on a FORCE-RLS table silently matches zero rows.
**Source:** 36-CONTEXT.md (D-04, D-05), 36-11-SUMMARY.md

### Raw Blob endpoint; absolute URLs kept; custom domain deferred
Staging and production serve images from `https://<account>.blob.core.windows.net/<container>/…`. There is no
Front Door or CDN. Persisted rows keep absolute URLs.

**Rationale:** A CDN would add a paid service and depend on the same DNS that is blocking Phase 29. The cost is
recorded: every persisted `image_url` carries the origin, so a later move needs a tenant-looped rewrite plus a
CSP change (deferred to Phase 32).
**Source:** 36-CONTEXT.md (D-06)

### A private quarantine container, chosen from the key
Derivatives go to the public `jtoye-images` container (access level `blob`: anonymous GET by URL, no
anonymous LIST, per #626). Raw uploads go to the private `jtoye-quarantine` container. The container is
derived from the key (`<tenant>/quarantine/…`). No caller chooses it and it is never persisted.

**Rationale:** Blob public access is set per container, not per prefix. The old in-bucket `/quarantine/`
prefix could not be expressed on Blob.
**Source:** 36-CONTEXT.md (Claude's Discretion, D-07), 36-01-SUMMARY.md

### One storage port with one SDK adapter
`StorageService` depends on a `BlobObjectStore` port that has no SDK type in any signature.
`AzureBlobObjectStore` is the only class that touches the SDK. The public `StorageService` surface, referenced by 13
main-code files, is unchanged.

**Rationale:** The unit tests mock a 7-method port, never the SDK's fluent chain, and the call-site surface
does not move.
**Source:** 36-01-SUMMARY.md

### URL-addressed deletes refuse another tenant's key (D-09)
`StorageService.delete(url)` deletes only when the key's tenant segment equals `TenantContext`. It fails
closed with no tenant context and refuses `.`/`..` segments, `\` and `%`. After review (WR-02) it returns a
boolean, so GDPR erasure counts only deletes that actually happened.

**Rationale:** Before the fix, a cross-tenant delete was reproduced end to end under NOSUPERUSER RLS. The
erasure record had also over-counted refused deletes, which undermines Article 17 evidence.
**Source:** 36-CONTEXT.md (D-09), 36-07-SUMMARY.md, 36-REVIEW-FIX.md (WR-02)

### Storage account names fixed; WORM retention recorded, not locked
The accounts are `jtoyestgmedia`/`jtoyestgbackup` and `jtoyeprodmedia`/`jtoyeprodbackup`, all StorageV2
Standard_LRS. The recorded values are 30-day WORM, 14-day soft delete and a 35-day lifecycle delete. The policy
stays unlocked through the Phase 29 restore drill, and a human locks it.

**Rationale:** A locked immutability policy cannot be shortened or removed until its retention lapses, so
locking it is a deliberate checkpoint and never a default. The names are embedded in persisted URLs and in the
goldens.
**Source:** 36-CONTEXT.md (D-11), 36-05-SUMMARY.md

### The backup uploader is write-only and never prunes
`blobctl` (Go, azblob) has no delete and no overwrite flag. It sends `If-None-Match: *` on both upload paths.
The backup script never prunes. Restores from a real account use `az … --auth-mode login` under a human's
time-bound Reader role, never the CronJob identity. The image tag moved from `:15` to `:15-blob` (D-10).

**Rationale:** Pruning or overwriting from the job identity is exactly the capability that a compromised
credential would abuse against an immutable backup.
**Source:** 36-04-SUMMARY.md, 36-08-SUMMARY.md, 36-09-SUMMARY.md

---

## Lessons

### azure-identity puts JNA on the classpath directly
Excluding `msal4j-persistence-extension` was not enough. azure-identity 1.18.6 declares
`net.java.dev.jna:jna-platform` directly, so the whole `net.java.dev.jna` group has to be excluded. A
production-classpath probe showed `WorkloadIdentityCredential` still builds and reaches the token path without
JNA.

**Context:** The plan's single exclusion left 2 jna lines on `runtimeClasspath`. The test classpath carries jna
through Testcontainers, so 36-06 tests this in a class loader built from the production runtime classpath.
**Source:** 36-01-SUMMARY.md, 36-06-SUMMARY.md

### The Blob SDK silently drops a port or path in `DevelopmentStorageProxyUri`
The SDK builds the emulator endpoint from the proxy URI's scheme and host only, and always appends
`:10000/devstoreaccount1`. A closed-port test therefore needs an explicit `BlobEndpoint=`. The startup validator
now requires the proxy URI to be a bare http host.

**Context:** The plan's closed-port connection string would have reached a running dev Azurite, not the closed
port.
**Source:** 36-01-SUMMARY.md, 36-06-SUMMARY.md

### A readiness-time probe runs after ApplicationRunners
Spring Boot calls `ApplicationRunner`s between `ApplicationStartedEvent` and `ApplicationReadyEvent`.
`DemoDataSeeder` is a runner. A probe on the ready event creates the containers after the seeder has already
written into missing ones. The seeder only logs a WARN for each failure, so first boot would have silently
seeded no images.

**Context:** Measured by break arm B in 36-06. The listener moved to `ApplicationStartedEvent`.
**Source:** 36-06-SUMMARY.md

### A non-BYPASSRLS role cannot take a plain `pg_dump` of FORCE-RLS tables
`pg_dump` requests `row_security=off`, and Postgres refuses that to a non-BYPASSRLS role, so the job exits 1.
The planned "hollow backup exits 0 and restores 0" arm was impossible on a correct tree. It became A-job (the
real job fails and uploads nothing) plus A-dump (a hollow dump taken with `--enable-row-security` passes the
content checks and then restores 0).

**Context:** Satisfying the plan literally would have meant removing the job's rc check, which is a safety net.
**Source:** 36-08-SUMMARY.md

### Residue greps were blind in several ways
- Case-sensitive `MINIO|S3_` missed `MinIO` prose.
- `\bS3_` misses `AWS_S3_BUCKET`, because `_` is a word character.
- A literal `localhost:9000` misses `127.0.0.1:9000`.
- Case-sensitive prose checks miss `s3:GetObject`.

The residue gate uses the widened forms and self-tests them on its own engine.

**Context:** Each blind spot was found by running the fail direction, never by a passing run.
**Source:** 36-02-SUMMARY.md, 36-14-SUMMARY.md, 36-15-SUMMARY.md, 36-16-SUMMARY.md

### A test that names a forbidden token contradicts the residue grep
A test asserting "img-src does not include `http://localhost:9000`" has to contain the literal, which makes a
tree-wide residue grep unsatisfiable. Asserting "no img-src source on port 9000, on any host" is strictly
stronger: it also catches `127.0.0.1:9000`. It also keeps the literal out of the tree.

**Context:** This is the known vacuous shape "a rule that must name the token it forbids".
**Source:** 36-03-SUMMARY.md

### Comment lines shift citations; an un-run gate turns CI red later
Comments added by five earlier plans moved the targets of five `.planning/codebase` line citations. No plan
ran `check-doc-citations`, so the first push reddened Operational Contracts and 14 ops steps were skipped.
Line-level allowlist entries in the residue gate go stale in the same way when lines above them move.

**Context:** It was fixed in 36-18 by re-pointing the citations. 36-09 had already hit the same class once.
**Source:** 36-09-SUMMARY.md, 36-16-SUMMARY.md, 36-18-SUMMARY.md

### A `git grep` check on an untracked file always passes
`git grep` searches tracked content only, so a pre-commit secret or residue check on a new file returns rc=1
whatever the file says.

**Context:** Caught twice. The fix is to check the working file with a real grep plus a positive control, then
run the `git grep` form after the commit.
**Source:** 36-07-SUMMARY.md, 36-17-SUMMARY.md

### `docker restart` moves core-java to host port 9091
core-java publishes the range `9090-9091:9090` and has no `container_name`. Restarting it, and sometimes
force-recreating it, rebinds it to :9091, and every browser-side API call then fails. The fix is to
force-recreate, assert `docker port`, and recreate again if it shows :9091. Resolve the container with
`docker compose ps -q core-java`.

**Context:** This also invalidated the first UAT capture.
**Source:** 36-12-SUMMARY.md, 36-UAT.md

---

## Patterns

### Anonymous-access proofs are behavioural and probe every container
#626 and quarantine privacy are proven with a plain HttpClient GET/LIST with no Authorization header against a
real Azurite. A privacy test must probe the key in every container a routing mistake could send it to, not
only the intended one.

**When to use:** Any access-control claim about object storage.
**Source:** 36-01-SUMMARY.md

### One shared, digest-pinned Testcontainers fixture
`AzuriteTestSupport` holds the pinned image, container creation and property registration for every Blob IT.
It uses `.asCompatibleSubstituteFor(...)`, because a tag@digest reference otherwise fails Testcontainers'
compatibility check. A parity test asserts that the pin is identical in both compose files and in the
horizons row.

**When to use:** Any emulator a test suite and the runtimes must share.
**Source:** 36-01-SUMMARY.md, 36-07-SUMMARY.md

### A restore drill that can fail in every way that matters
Arm A restores 0 and arm B restores the live count. The drill must also fail on a wrong role, a wrong
database, and truncated, bit-rotted, empty and missing dumps. It also checks `current_database()` on every
count; that check is load-bearing, because without it a count read from the live DB passes arm B.

**When to use:** Any backup/restore gate.
**Source:** 36-08-SUMMARY.md

### A browser image proof cross-checks the server-rendered set
Filter by the full storage src prefix, VOID on zero images, and require every storage-origin `<img>` in the
served HTML to be a loaded `<img>` in the browser.

**When to use:** Any image-loads proof where a fallback component can remove failed images from the DOM.
**Source:** 36-13-SUMMARY.md

### Prove the target is the emulator before any `az` write
Make a read-only `az --debug` call that contacts only 127.0.0.1:10000, with no `AZURE_*` env and no `[storage]`
defaults, and write only under an explicit owner sanction.

**When to use:** Any `az storage` mutation during local work.
**Source:** 36-12-SUMMARY.md

### Every `az` line pins the subscription
Commands that cannot carry `--subscription` (`az account list`, `az account list-locations`) are replaced
(`az account show --subscription` with an equality assertion, `az rest --subscription`), not exempted.

**When to use:** Every runbook or script that touches the Azure estate, because the CLI default subscription is
the employer's.
**Source:** 36-05-SUMMARY.md

### A tag bump whose contents change moves everything in one commit
When an image's contents change, its tag moves together with the manifest, the documented build line, the
horizons pin and the goldens. Workload Identity opt-in is a pod-template label patched per real overlay; base
and local never carry it.

**When to use:** Any image whose contents change, and any per-environment identity wiring.
**Source:** 36-09-SUMMARY.md

### A gate is wired in the commit that creates it, with a hermetic self-test
The residue gate, its CI step and a 41-arm TAP self-test harness landed together. The allowlist is line-level
and reasoned. Whole-file entries exist only for records classed as historical.

**When to use:** Any new CI gate; splitting creation from wiring leaves intermediate commits red.
**Source:** 36-16-SUMMARY.md

### A test context opts out of a startup check; the check is never weakened
`application-test.yml` covers the test profile. `@TestPropertySource` or boot args cover any other context,
each with a written reason. Review then added render invariant INV-11, so staging and production cannot switch
the check off from the environment.

**When to use:** Any fail-fast startup validator that some test contexts cannot satisfy.
**Source:** 36-06-SUMMARY.md, 36-REVIEW-FIX.md (WR-01)

---

## Surprises

### azblob `UploadFile` silently overwrites above 256 MiB
On azblob v1.8.1, the staged path (files over 256 MiB) commits the block list without forwarding
`AccessConditions`, so `If-None-Match: *` is dropped. A 257 MiB upload overwrote an existing blob with no error.

**Impact:** `blobctl` sends a single Put Blob up to the limit, and above it uses Put Block plus Put Block List
with the condition passed explicitly. Without this, a large dump could overwrite an existing one.
**Source:** 36-04-SUMMARY.md

### The subscription could not check name availability
`az storage account check-name` returned `SubscriptionNotFound` for all four names, because `Microsoft.Storage`
is NotRegistered on the subscription. Only DNS evidence (NXDOMAIN) exists.

**Impact:** Name availability is unverified until Phase 29 registers the provider. The provisioning runbook
gained a registration step (§2.1) that re-runs `check-name` before any create (WINDOWS.md #1).
**Source:** 36-05-SUMMARY.md

### A public container ACL cannot be set on the data plane without shared key
Set Container ACL cannot be authorised with an Entra token, and shared-key access is disabled. So
`--auth-mode login` cannot set `jtoye-images` to level `blob`.

**Impact:** The runbook creates containers on the control plane (`az storage container-rm create
--public-access`).
**Source:** 36-05-SUMMARY.md

### Quarantined raw uploads had been publicly readable
Under the old single public bucket, raw uploads under `/quarantine/` were anonymously readable by key for the
72 h horizon.

**Impact:** Closed by the private container (T-36-01). The retention Javadoc that described the old exposure
was rewritten.
**Source:** 36-01-SUMMARY.md, 36-14-SUMMARY.md

### The cross-tenant image delete was real
Tenant A could save tenant B's image URL on its own product and then remove it, which deleted B's blob. This
was reproduced under NOSUPERUSER RLS before the fix.

**Impact:** Fixed by the D-09 guard, with an RLS-real test that includes a same-tenant control.
**Source:** 36-07-SUMMARY.md

### Every scheduled nightly since 2026-09-25 had died at image pull
Each one failed on `minio-init unauthorized` and never executed Playwright.

**Impact:** The first dispatch on Azurite (run 36552435811) executed 325 tests: 319 passed, 0 failed,
6 skipped. The URL gate, the Content-Type gate and the restore drill also passed in that run.
**Source:** 36-18-SUMMARY.md

### Trivy reds that the phase did not cause
- The core-java image failed on `jackson-databind 2.21.4` (CVE-2026-68497), which the Spring Boot BOM selects.
  This is the daily-DB time-bomb; the same job passed on 5b6e76bd on 2026-09-28.
- The new pg-backup CI leg failed on 22 findings, all in `/usr/local/bin/gosu` from the `postgres:15-bookworm`
  base. Nothing in the image uses gosu.

**Impact:** gosu is now removed in the runtime stage (dea65db7). The jackson bump is its own change and is
recorded in deferred-items.md.
**Source:** 36-18-SUMMARY.md, STATE.md (quick task 260929-fast)

### Review found two holes in the phase's own guarantees
- `storage.blob.validate-on-startup` could be switched off from the environment (WR-01).
- The `:15-blob` image that the CronJob references was pushed and scanned by no CI job (WR-03).

**Impact:** Closed by render invariant INV-11 and by a 4th build-and-push matrix leg. The review-fix commits
landed after UAT and were carried forward explicitly in re-verification.
**Source:** 36-REVIEW-FIX.md, 36-VERIFICATION.md
