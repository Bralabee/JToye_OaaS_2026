---
phase: 36-azure-blob-storage-throughout
reviewed: 2026-09-29T16:58:23Z
depth: standard
files_reviewed: 163
files_reviewed_list:
  - AGENTS.md
  - CLAUDE.md
  - core-java/build.gradle.kts
  - core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
  - core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaAsset.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaNormalizer.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaProcessingWorker.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaProperties.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaQuarantineRetentionSweep.java
  - core-java/src/main/java/uk/jtoye/core/media/ProductMediaRepository.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
  - core-java/src/main/java/uk/jtoye/core/shop/ShopService.java
  - core-java/src/main/java/uk/jtoye/core/storage/AzureBlobObjectStore.java
  - core-java/src/main/java/uk/jtoye/core/storage/BlobObjectStore.java
  - core-java/src/main/java/uk/jtoye/core/storage/StorageConfig.java
  - core-java/src/main/java/uk/jtoye/core/storage/StorageConfigurationException.java
  - core-java/src/main/java/uk/jtoye/core/storage/StorageProperties.java
  - core-java/src/main/java/uk/jtoye/core/storage/StorageService.java
  - core-java/src/main/java/uk/jtoye/core/storage/StorageStartupValidator.java
  - core-java/src/main/java/uk/jtoye/core/storage/StorageUnavailableException.java
  - core-java/src/main/resources/application-local.yml
  - core-java/src/main/resources/application.yml
  - core-java/src/test/java/uk/jtoye/core/config/DatabaseConfigurationValidatorOwnershipTest.java
  - core-java/src/test/java/uk/jtoye/core/dev/DemoImageManifestTest.java
  - core-java/src/test/java/uk/jtoye/core/gdpr/GdprServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/media/CowSafetyIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/GateStrictnessTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaAssetDtoMappingTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaBackfillMigrationIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaClaimLockIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaCopyOnWriteIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaDedupAttachIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaDurabilityIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaPipelineAzuriteIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaProcessingWorkerIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaQuarantineRetentionSweepTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaTenantIsolationUnderConcurrencyIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaUploadControllerTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaUploadIdempotencyTest.java
  - core-java/src/test/java/uk/jtoye/core/product/ProductImageCrossTenantBlobDeleteIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/product/ProductImageDeleteIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/resilience/RedisFaultInjectionIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/security/access/SystemPrincipalGuardTest.java
  - core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/shop/ShopImageCrossTenantIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/storage/AzureBlobObjectStoreTest.java
  - core-java/src/test/java/uk/jtoye/core/storage/AzuriteImagePinParityTest.java
  - core-java/src/test/java/uk/jtoye/core/storage/AzuriteStorageIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/storage/LegacyImageUploadPipelineTest.java
  - core-java/src/test/java/uk/jtoye/core/storage/ShopBrandImageKeyTest.java
  - core-java/src/test/java/uk/jtoye/core/storage/StorageConfigShapeTest.java
  - core-java/src/test/java/uk/jtoye/core/storage/StorageDeleteTenantGuardIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/storage/StorageServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/storage/StorageStartupValidatorIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/storage/WorkloadIdentityCredentialBuildTest.java
  - core-java/src/test/java/uk/jtoye/core/testsupport/AzuriteTestSupport.java
  - core-java/src/test/resources/application-test.yml
  - .cursor/rules/oaas-core-java.mdc
  - .cursor/rules/oaas-platform.mdc
  - docker-compose.full-stack.yml
  - docs/AI_CONTEXT.md
  - docs/architecture/ARCHITECTURE.md
  - docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md
  - docs/architecture/ESSENTIAL_ARCHITECTURE.md
  - docs/architecture/SYSTEM_DESIGN_V2.md
  - docs/archive/backups-rehearsal-evidence-2026-07.md
  - docs/CHANGELOG.md
  - docs/CREDITS-demo-images.md
  - docs/DOCUMENTATION_INDEX.md
  - docs/FAILURE_MODES.md
  - docs/guides/DOCKER_QUICK_START.md
  - docs/guides/QA_TEST_PLAN.md
  - docs/HOW_IT_WORKS.md
  - docs/metrics.json
  - docs/runbooks/azure-blob-provisioning.md
  - docs/runbooks/backups.md
  - docs/runbooks/sealed-secrets.md
  - .env.example
  - frontend/components/dashboard/media/__tests__/ReviewQueue.test.tsx
  - frontend/components/ui/asset-image.tsx
  - frontend/components/ui/__tests__/asset-image.test.tsx
  - frontend/e2e/public-layout.spec.ts
  - frontend/e2e/storage-images.spec.ts
  - frontend/lib/security-headers.ts
  - frontend/next.config.mjs
  - frontend/__tests__/csp-headers.test.ts
  - frontend/__tests__/__snapshots__/header-snapshot.test.ts.snap
  - .github/chatmodes/oaas-core-java.chatmode.md
  - .github/chatmodes/oaas-platform.chatmode.md
  - .github/dependabot.yml
  - .github/instructions/oaas-core-java.instructions.md
  - .github/instructions/oaas-platform.instructions.md
  - .github/workflows/ci-cd.yaml
  - .github/workflows/e2e-nightly.yml
  - .gitignore
  - .gitleaksignore
  - .gitleaks.toml
  - HANDOFF.md
  - infra/backups/blobctl/commands.go
  - infra/backups/blobctl/commands_test.go
  - infra/backups/blobctl/config.go
  - infra/backups/blobctl/config_test.go
  - infra/backups/blobctl/emulator.go
  - infra/backups/blobctl/go.mod
  - infra/backups/blobctl/go.sum
  - infra/backups/blobctl/main.go
  - infra/backups/Dockerfile
  - infra/backups/k8s-backup.sh
  - infra/dependency-horizons.yaml
  - infra/docker-compose.yml
  - infra/load-testing/baseline.sh
  - infra/load-testing/media-pipeline-arm.sh
  - infra/monitoring/README.md
  - k8s/base/configmap.yaml
  - k8s/base/core-java-deployment.yaml
  - k8s/base/kustomization.yaml
  - k8s/base/networkpolicies/10-frontend.yaml
  - k8s/base/networkpolicies/20-core-java.yaml
  - k8s/base/networkpolicies/40-datastores.yaml
  - k8s/base/networkpolicies/README.md
  - k8s/base/pg-backup-cronjob.yaml
  - k8s/base/secrets-template.yaml.example
  - k8s/base/serviceaccounts.yaml
  - k8s/DEPLOYMENT.md
  - k8s/goldens/production.yaml
  - k8s/goldens/staging.yaml
  - k8s/local/configmap-patch.yaml
  - k8s/local/kustomization.yaml
  - k8s/LOCAL.md
  - k8s/local/storage-env-patch.yaml
  - k8s/production/configmap-patch.yaml
  - k8s/production/kustomization.yaml
  - k8s/PRODUCTION_READINESS_REPORT.md
  - k8s/production/workload-identity-patch.yaml
  - k8s/QUICK_START.md
  - k8s/scripts/check-env-contract.sh
  - k8s/scripts/check-render-invariants.sh
  - k8s/staging/configmap-patch.yaml
  - k8s/staging/kustomization.yaml
  - k8s/staging/workload-identity-patch.yaml
  - README.md
  - scripts/check-backup-restore-drill.sh
  - scripts/check-dependency-horizons.sh
  - scripts/check-doc-versions.sh
  - scripts/check-media-content-types.sh
  - scripts/check-media-urls-resolve.sh
  - scripts/check-no-measured-placeholders.sh
  - scripts/check-no-object-store-residue.sh
  - scripts/check-postgres-major-parity.sh
  - scripts/dev-media-reseed.sh
  - scripts/gates/gate-enforcement.conf
  - scripts/gates/object-store-residue-allowlist.conf
  - scripts/gates/object-store-residue-selftest.sh
  - scripts/k8s-local-secrets.sh
  - scripts/k8s-local-up.sh
  - scripts/lib/k8s-local-guards.sh
  - scripts/restore-drill.sh
  - scripts/seed-media-review-fixtures.sh
  - scripts/start-dev.sh
  - scripts/stop-dev.sh
  - scripts/verify-env.sh
findings:
  critical: 0
  warning: 6
  info: 8
  total: 14
status: issues_found
---

# Phase 36: Code Review Report

**Reviewed:** 2026-09-29T16:58:23Z
**Depth:** standard
**Files Reviewed:** 163
**Status:** issues_found

## Summary

I reviewed the Phase 36 diff (`7f6b1ce5^..eefb46fa`) with most of the time spent on these areas:
- the new storage seam: `StorageService`, `AzureBlobObjectStore`, `StorageProperties` shape rules, `StorageConfig` and `StorageStartupValidator`
- the D-09 cross-tenant URL-delete guard and its callers
- the `blobctl` Go uploader and `k8s-backup.sh`
- the k8s base, overlay, ServiceAccount and NetworkPolicy changes
- the new and changed gates: `check-render-invariants.sh` INV-8..10 and LOC-7, `check-backup-restore-drill.sh`, `check-media-urls-resolve.sh`, `check-media-content-types.sh`, `check-no-object-store-residue.sh`, `check-doc-versions.sh`

Docs, goldens and test files got a lighter pass, looking for claims that contradict the code and for test-reliability problems.

The core design holds up under tracing:
- The container is derived from the key, so quarantine objects cannot reach the public container.
- `urlForKey` refuses quarantine keys. The only callers pass ACTIVE derivative keys (`MediaAssetService.toDto:505`, `ProductService.resolveAssetFirst:86` via `findPrimaryActiveObjectKey`), so the new `IllegalArgumentException` cannot fire on a PENDING or FAILED asset.
- The D-09 tenant check fails closed when TenantContext is absent. `deleteIfExists` keeps the retention sweep's sentinel honest.
- `blobctl` sends `If-None-Match: *` on both its single-shot and staged upload paths.
- The render invariants read by exact path and guard their own blindness.

**No BLOCKER was found.** Six WARNINGs follow:
- a D-08 control whose "cannot be switched off by environment" claim is false
- a GDPR erasure record that now over-reports deletions more often, because D-09 skips deletes silently
- a CronJob image tag that no CI job builds, pushes or scans
- a credential regression during k8s-local rehearsals
- a stored-URL gate that recognises only one spelling of the retired origin
- transport-vs-programming-error misclassification in the store adapter

## Warnings

### WR-01: `storage.blob.validate-on-startup` CAN be switched off from the environment, contrary to the D-08 guarantee the code and comments state

**File:** `core-java/src/main/resources/application.yml:595`, `core-java/src/main/java/uk/jtoye/core/storage/StorageProperties.java:71-77`, `core-java/src/main/java/uk/jtoye/core/storage/StorageStartupValidator.java:119-129`
**Issue:** Three places say the boot-time probe is on in every runtime and that no runtime can switch it off through its environment:
- the YAML comment ("A literal on purpose, NOT mapped from an env var, so no runtime can switch it off through its environment")
- the `StorageProperties.Blob.validateOnStartup` Javadoc
- the `StorageStartupValidator` Javadoc

That is not how Spring Boot resolves properties. `SystemEnvironmentPropertySource` maps `STORAGE_BLOB_VALIDATE_ON_STARTUP` (and `STORAGE_BLOB_VALIDATEONSTARTUP`) onto `storage.blob.validate-on-startup`. Environment variables outrank `application.yml`, and `@ConditionalOnProperty(... havingValue = "true", matchIfMissing = true)` evaluates against that same Environment. So `STORAGE_BLOB_VALIDATE_ON_STARTUP=false` on the Deployment silently removes the bean. The same applies to `SPRING_APPLICATION_JSON` and `--storage.blob.validate-on-startup=false`.

The probe is what enforces #626 at runtime (public container must not be `container`, quarantine must be private). As written, "a literal in application.yml" is the only guard, and a literal is not a guard. Nothing in `check-render-invariants.sh` or `check-env-contract.sh` asserts the absence of such an env var.

**Fix:** Make the claim true in code instead of in prose. Either:
- drop `@ConditionalOnProperty` from the component and have the test profile exclude the bean, e.g. `@Profile("!test")` on the validator or a test-only `@MockBean`/`@TestConfiguration` override; or
- keep the property but refuse `false` outside the test profile:

```java
@EventListener(ApplicationStartedEvent.class)
public void validate(ApplicationStartedEvent event) {
    Environment env = event.getApplicationContext().getEnvironment();
    // ...existing probe...
}
// and in a separate always-on bean:
if (!blob.isValidateOnStartup() && !env.acceptsProfiles(Profiles.of("test"))) {
    throw new StorageConfigurationException(
        "storage.blob.validate-on-startup=false is only permitted in the test profile (D-08)");
}
```

Also add an INV-9-style render check: no env name matching `^STORAGE_BLOB_` and no `SPRING_APPLICATION_JSON` in staging or production renders. At minimum, correct the three comments.

### WR-02: GDPR erasure counts every review photo as deleted even when D-09 (or the external-URL branch) silently refused the delete, so the Article 17 record overstates what was erased

**File:** `core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java:199-203`, `core-java/src/main/java/uk/jtoye/core/storage/StorageService.java:271-308`
**Issue:** `photosDeleted++` runs unconditionally after `storageService.delete(url)`. `delete` returns `void` and silently returns (WARN log only) in five cases:
- an external URL
- no tenant segment
- no TenantContext
- a foreign tenant
- a non-plain path

Phase 36 added the last four of these. Review photo URLs are client-supplied (`ReviewService:93` stores `request.getPhotoUrls()` verbatim), so a review can carry another tenant's URL or an unrelated URL. D-09 now correctly refuses to delete it. The erasure still records the photo as "deleted" in the `ErasureRecord` photo count, which is the Article 17 evidence row.

This is a proof artifact claiming a deletion that did not happen. D-09 made the gap between "counted" and "deleted" wider and invisible to the caller.

**Fix:** Have `delete` report its outcome, and count only real removals. Record refusals separately if the record needs them:

```java
public enum UrlDeleteOutcome { DELETED, ABSENT, SKIPPED_EXTERNAL, REFUSED, FAILED }
public UrlDeleteOutcome delete(String imageUrl) { ... }

// GdprService
UrlDeleteOutcome o = storageService.delete(url);
if (o == UrlDeleteOutcome.DELETED || o == UrlDeleteOutcome.ABSENT) photosDeleted++;
else photosNotDeleted++;   // surfaced in the log / record, not folded into "deleted"
```

### WR-03: The CronJob now references `ghcr.io/bralabee/jtoye-pg-backup:15-blob`, which no CI job pushes or image-scans

**File:** `k8s/base/pg-backup-cronjob.yaml:54`, `.github/workflows/ci-cd.yaml:1386-1387`, `.github/workflows/e2e-nightly.yml:472`, `docs/runbooks/backups.md:275-276`
**Issue:** The tag moved from `:15` to `:15-blob` (D-10), and the image now contains new first-party code (`blobctl`, an Azure SDK Go binary). But:
- `build-and-push` still builds only `[core-java, edge-go, frontend]`, so the Trivy image gate never sees this image.
- The nightly builds it locally for the drill and never pushes it.
- The only way the tag reaches GHCR is the manual `docker build` / `docker push` in the runbook.

Two consequences:
1. The first staging or production deploy of these manifests, with `DEPLOY_*_ENABLED` flipped on, starts the nightly backup in `ImagePullBackOff` unless someone remembered the manual push. A CronJob that never runs does not page anyone, so backups stop silently.
2. The `postgres:15-bookworm` OS layer and the compiled Go stdlib are never gated for fixable CRITICAL/HIGH CVEs, unlike every other shipped image. The fs scan covers `go.sum` but not the image.

The phase changed this surface, so leaving it outside the image pipeline is a phase decision, not only legacy.

**Fix:** Add `pg-backup` to the `build-and-push` matrix, with `context: infra/backups` and `file: infra/backups/Dockerfile`, pushing the exact tag the CronJob names (or pin the CronJob to a CI-produced digest), so the existing Trivy image steps cover it. At minimum, add a render or CI check that fails when the image named in `pg-backup-cronjob.yaml` is not published.

### WR-04: The Azurite store is fully readable and writable with a PUBLIC key whenever the backing services are published off-loopback (the k8s-local rehearsal requires exactly that)

**File:** `docker-compose.full-stack.yml` (azurite service, published `${JTOYE_BIND_HOST}:10000`), `infra/backups/blobctl/emulator.go:243-249`, `.env.example:449-456`, `k8s/local/configmap-patch.yaml` (backup.blob.connection-string)
**Issue:** MinIO was guarded by rotated `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` values, which `verify-env.sh` checked against a deny list. Azurite now runs with the Microsoft-published `devstoreaccount1` key, and the `.env.example` note says a k8s-local rehearsal requires a non-loopback `JTOYE_BIND_HOST`. For that period, any host on the LAN can use the published key to:
- list, read and delete `jtoye-db-backups`, which holds BYPASSRLS dumps of the dev database, every tenant's rows included;
- write blobs with arbitrary `Content-Type` (for example `text/html`) into `jtoye-images`, which the browser treats as a trusted image origin (`img-src http://localhost:10000`).

`check-infra-exposure.sh` goes red during the window. That tells the operator the ports are exposed. It does not tell them the credential guarding the port is public. This is a regression in credential posture compared with the store it replaces.

**Fix:** Run Azurite with a generated key: `AZURITE_ACCOUNTS=devstoreaccount1:${AZURITE_ACCOUNT_KEY}`, with the key generated like the other `.env` secrets and required by `verify-env.sh`. Then use the `AccountName=devstoreaccount1;AccountKey=…;BlobEndpoint=http://azurite:10000/devstoreaccount1` form. `StorageProperties.validateEmulatorConnectionString` already accepts that form, and `blobctl`'s `loadEmulator` would need the same form. Alternatively, keep Azurite loopback-only and expose it to minikube through a narrower route than `JTOYE_BIND_HOST`.

### WR-05: `check-media-urls-resolve.sh` treats only `http://localhost:9000/` as the retired origin; other spellings of the same dead store pass as "external (not fetched)"

**File:** `scripts/check-media-urls-resolve.sh:91`, `:254-261`
**Issue:** U-2 ("no stored value may still point at the retired :9000 origin") matches a single literal prefix. A stored URL under any of these goes to the `http://*|https://*` branch, is counted as EXTERNAL, printed as "not fetched", and does not fail the gate:
- `http://127.0.0.1:9000/jtoye-images/…`
- `http://minio:9000/…`
- `http://localhost:9000` with a different container path
- the pre-Phase-36 k8s default origin `https://s3.eu-west-2.amazonaws.com/jtoye-images/…` (base `s3.public-url` before this phase)

The phase's own residue gate knows about both host spellings (`R1` includes `(localhost|127\.0\.0\.1):9000`), so this gate is fail-open on a spelling its sibling covers. The broken image is exactly what U-1/U-2 exist to catch.

**Fix:** Match the retired store by pattern, not one literal. Make any unrecognised absolute origin a VOID-or-FAIL unless it is on an explicit external allowlist:

```bash
RETIRED_ORIGIN_RE='^https?://(localhost|127\.0\.0\.1|minio)(:9000)?/|^https://s3[.-][a-z0-9.-]*amazonaws\.com/'
...
if [[ "$url" =~ $RETIRED_ORIGIN_RE ]]; then OLD=$((OLD+1)); FAILURES+=(...); continue; fi
```

### WR-06: `AzureBlobObjectStore.call` reports every non-`BlobStorageException` RuntimeException as "Object store unreachable"

**File:** `core-java/src/main/java/uk/jtoye/core/storage/AzureBlobObjectStore.java:125-133`, `:86-94`; `core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java:472-482`
**Issue:** The catch-all `RuntimeException` branch wraps programming and validation errors as `StorageUnavailableException("Object store unreachable …")`. Examples:
- the SDK's `IllegalArgumentException` for an invalid blob name or length
- an `NullPointerException` from a null argument
- this class's own `IllegalStateException("Unrecognised container access level …")` at line 93

The interface contract (`BlobObjectStore` Javadoc) says `StorageUnavailableException` means a transport failure. Consumers act on that:
- `DemoDataSeeder` aborts all remaining image seeding on the first one, when it should skip just that entry.
- The startup validator's error then reads as a connectivity problem, which sends the operator to the network instead of the bug.

**Fix:** Only wrap exceptions that are actually transport failures, and let everything else propagate unchanged:

```java
} catch (BlobStorageException e) {
    throw e;
} catch (UncheckedIOException | reactor.core.Exceptions.ReactiveException
         | io.netty.channel.ConnectTimeoutException e) {   // or: cause chain contains IOException / TimeoutException
    throw new StorageUnavailableException(...);
}
```

A simpler rule is to wrap only when the cause chain contains an `IOException` or `TimeoutException`. Throw the unknown-access-level case as a `StorageConfigurationException`.

## Info

### IN-01: `k8s-backup.sh` says missing Blob configuration fails the job before the dump, but only `STORAGE_AUTH_MODE` presence is checked

**File:** `infra/backups/k8s-backup.sh:21`, `:37`, `:74`
**Issue:** `STORAGE_ENDPOINT`, the `AZURE_*` identity variables and the connection string are validated only inside `blobctl upload`, after `pg_dump` has written a full BYPASSRLS dump of every tenant. Before Phase 29 annotates the ServiceAccount, `AZURE_CLIENT_ID` is missing. A production CronJob would then do a full PII dump to ephemeral disk every night and fail only at upload.
**Fix:** Add a no-network `blobctl check-config` subcommand that runs `LoadConfig` and exits 1 on error. Call it before `pg_dump`.

### IN-02: Dead `RETENTION_DAYS` env on the CronJob

**File:** `k8s/base/pg-backup-cronjob.yaml:113-115`
**Issue:** `k8s-backup.sh` no longer prunes and never reads `RETENTION_DAYS`. Retention is the container's WORM, soft-delete and lifecycle setup (35-day lifecycle rule per the provisioning runbook). A `"30"` here suggests a retention setting that does nothing and disagrees with the real one.
**Fix:** Remove the env var, or replace it with a comment pointing to the provisioning runbook section 4.

### IN-03: The backups runbook status note is stale

**File:** `docs/runbooks/backups.md:221-225`
**Issue:** The note says "Until that lands, `k8s/base/pg-backup-cronjob.yaml` still names the pre-Blob image". The CronJob, goldens and horizons pin already name `:15-blob` on this branch.
**Fix:** Delete the status paragraph, or restate it as the Phase 29 dependency (identities and the ServiceAccount client-id annotation).

### IN-04: `public-url` is validated after `trim()` but used untrimmed

**File:** `core-java/src/main/java/uk/jtoye/core/storage/StorageProperties.java:318`, `:328`; `StorageService.java:191`, `:274`, `:412`
**Issue:** A value with trailing whitespace passes `validatePublicUrl`, because both checks trim. Every composed image URL would then embed the space, and the D-09 prefix match in `delete()` would never match. Env and ConfigMap values are not trimmed by Spring.
**Fix:** Refuse `!publicUrl.equals(publicUrl.trim())` in `validatePublicUrl`, or normalise in the setter.

### IN-05: `K8S_LOCAL_AZURITE_PORT` is a knob the pods can never follow

**File:** `scripts/k8s-local-up.sh:282`, `.env.example:462`, `k8s/local/configmap-patch.yaml` (`DevelopmentStorageProxyUri=http://host.minikube.internal`)
**Issue:** The SDK always appends `:10000` to `DevelopmentStorageProxyUri`, and the shape rules forbid a port. The STEP 5 reachability probe can therefore go green on a non-default `K8S_LOCAL_AZURITE_PORT` that core-java and blobctl never dial.
**Fix:** Make `k8s_local_load_env` refuse any value other than `10000`, or drop the key and hard-code the probe port with a comment.

### IN-06: `blobctl` `proxyHost` builds an invalid endpoint for an IPv6 literal

**File:** `infra/backups/blobctl/config.go:169`, `:196`
**Issue:** `u.Hostname()` strips the brackets, so `http://[::1]` becomes `http://::1:10000/devstoreaccount1`, which fails at request time rather than at config load.
**Fix:** Re-bracket the host with `net.JoinHostPort(host, emulatorBlobPort)`, or refuse IPv6 literals in `proxyHost`.

### IN-07: `check-backup-restore-drill.sh` can report VOID after an arm has already FAILED

**File:** `scripts/check-backup-restore-drill.sh:455`
**Issue:** If arm B has already set `FAILED=1` and the A-dump step then returns 3, `void` exits 2. The recorded verdict becomes "could not evaluate" when a real failure (the backup does not carry tenant data) was established. Both codes fail the nightly job, but the triage signal is wrong.
**Fix:** In `void()`, exit 1 when `FAILED` is already 1, and print the earlier failure first.

### IN-08: INV-4's `AccountKey=` literal is case-sensitive, and SAS markers are not covered

**File:** `k8s/scripts/check-render-invariants.sh:397`, `:955`
**Issue:** Connection-string keys are case-insensitive: `StorageProperties` lowercases them, and the SDK accepts `accountkey=`. A render carrying `accountkey=…` or a SAS (`SharedAccessSignature=`, `sig=`) in some value not caught by INV-9's name-based checks passes INV-4. Separately, `grep -nE … 2>/dev/null` inside `if` treats a grep error (rc 2) as "no hit", which is fail-open. That grep pattern predates this phase.
**Fix:** Use `grep -niE` for the storage literals, add `SharedAccessSignature=` and `[?&]sig=`, and distinguish rc 1 from rc ≥ 2.

---

_Reviewed: 2026-09-29T16:58:23Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
