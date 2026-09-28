# Phase 36: Azure Blob Storage Throughout - Pattern Map

**Mapped:** 2026-09-28
**Files analyzed:** 34 new or modified (grouped by role below; the full residue inventory is 36-RESEARCH.md §Full Inventory)
**Analogs found:** 31 / 34

All analog paths below are git-tracked source. Line numbers were read on branch `phase-36-azure-blob-storage` at d640e079.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|
| `core-java/.../storage/StorageProperties.java` (mod: `storage.blob.*`) | config | binding | itself (`StorageProperties.java:7-41`) | exact (in-place) |
| `core-java/.../storage/StorageConfig.java` (mod: auth-mode switch + shape validation) | config/bean factory | request-response | itself `StorageConfig.java:15-33` | exact (in-place) |
| `core-java/.../storage/BlobObjectStore.java` + `AzureBlobObjectStore.java` (new) | adapter | file-I/O | `StorageService.java:328-395` (the S3 calls it replaces) | role-match |
| `core-java/.../storage/StorageService.java` (mod: routing, D-09 tenant guard) | service | file-I/O | itself `:289-307`, `:328-400` | exact (in-place) |
| `core-java/.../storage/StorageStartupValidator.java` (new) | startup validator | event-driven | `config/DatabaseConfigurationValidator.java:29-97` | exact |
| `core-java/.../storage/StorageConfigurationException.java` (new) | exception | — | `SecurityConfigurationException` (thrown by the validator above) | exact |
| `core-java/.../dev/DemoDataSeeder.java` (mod: exception map) | seeder | batch | itself `:472-481` | exact (in-place) |
| `core-java/src/test/.../storage/AzuriteStorageIntegrationTest.java` (new) | test (IT) | file-I/O | `config/MediaListenerConcurrencyIntegrationTest.java:62-64` + `audit/AuditIntegrationTest.java:33-47` | role-match |
| Shared Azurite fixture (new, e.g. `testsupport/AzuriteTestSupport.java`) | test support | — | `testsupport/IntegrationTestSupport.java:54,121-135` | role-match (see note) |
| `.../storage/StorageStartupValidatorTest.java`, shape-validation unit test (new) | test (unit) | — | `config/DatabaseConfigurationValidatorOwnershipTest.java` | role-match |
| `.../media/MediaPipelineAzuriteIntegrationTest.java` (new) | test (IT) | event-driven | `media/MediaProcessingWorkerIntegrationTest.java` (stubbed via `@SpyBean`) | role-match |
| 4 rewritten unit tests (`StorageServiceTest`, `LegacyImageUploadPipelineTest`, `ShopBrandImageKeyTest`, `MediaQuarantineRetentionSweepTest`) | test (unit) | — | `StorageServiceTest.java:34-57` (Mockito shape to keep) | exact (in-place) |
| `core-java/src/main/resources/application.yml` + `application-test.yml` | config | — | `application.yml:575-582` | exact (in-place) |
| `docker-compose.full-stack.yml` (azurite replaces minio/minio-init) | config | — | `docker-compose.full-stack.yml:596-619` (minio service) | exact |
| `infra/docker-compose.yml` (azurite, D-08) | config | — | same minio block above | role-match |
| `.env.example`, `scripts/verify-env.sh`, `.github/workflows/e2e-nightly.yml:74-76` | config | — | in-place | exact |
| `infra/dependency-horizons.yaml` (azurite row, drop minio rows) | config | — | `infra/dependency-horizons.yaml:196-220` (`id: minio`) | exact |
| `scripts/dev-media-reseed.sh` (new) | script (dev DB) | batch / CRUD | `scripts/seed-media-review-fixtures.sh:73,101-168` + `V57__shop_staff_grant_source.sql:69-93` | exact |
| `scripts/check-media-urls-resolve.sh` (new) | gate (runtime) | request-response | `scripts/check-media-content-types.sh` (whole file) | exact |
| `scripts/check-no-object-store-residue.sh` (new) | gate (static) | batch scan | `scripts/check-no-create-extension.sh:60-154` | exact |
| `scripts/check-media-content-types.sh` (mod: az CLI on Azurite) | gate (runtime) | request-response | itself | exact (in-place) |
| `scripts/gates/gate-enforcement.conf` + `.github/workflows/ci-cd.yaml` | config / CI | — | `gate-enforcement.conf:37`; `ci-cd.yaml:933-945` | exact |
| `infra/backups/blobctl/` (new Go module + `_test.go`) | utility (CLI) | file-I/O | `edge-go/` (`go.mod`, `cmd/edge/*_test.go`, `Dockerfile`) | partial (only Go module in repo) |
| `infra/backups/Dockerfile` (multi-stage, drop awscli) | config | — | itself + `edge-go/Dockerfile:11-30` builder stage | role-match |
| `infra/backups/k8s-backup.sh` (upload via blobctl) | script | file-I/O | itself `:25-95` | exact (in-place) |
| `infra/backups/restore-drill-local.sh` (new) | script | batch | `docs/runbooks/backups.md:281+` (26-07 L4 recipe) + `seed-media-review-fixtures.sh` psql helper | partial |
| `k8s/base/pg-backup-cronjob.yaml`, `core-java-deployment.yaml`, `configmap.yaml`, overlays | config | — | `pg-backup-cronjob.yaml:78-108` | exact (in-place) |
| `k8s/base/serviceaccount-*.yaml` (new) | config | — | **none** (no ServiceAccount in `k8s/`) | no analog |
| `k8s/base/networkpolicies/20-core-java.yaml`, `40-datastores.yaml` | config | — | in-place | exact |
| `k8s/scripts/check-render-invariants.sh` (INV-7 edit + new WI invariants) | gate | batch scan | itself `:338-357`, `:632-660`, `:833-910` | exact |
| `frontend/lib/security-headers.ts`, `frontend/next.config.mjs` | config | — | `security-headers.ts:94`, `next.config.mjs:19-33` | exact (in-place) |
| `frontend/__tests__/csp-headers.test.ts`, `header-snapshot.test.ts` (extend) | test | — | `csp-headers.test.ts:22-31,108-114,156-175` | exact |
| `frontend/e2e/storage-images.spec.ts` (new) | test (e2e) | request-response | `frontend/e2e/storefront-flows.spec.ts:8-13,420-475` | exact |

## Pattern Assignments

### `StorageProperties.java` / `StorageConfig.java` (config, in-place rewrite)

**Analog (current shape to preserve):** `core-java/src/main/java/uk/jtoye/core/storage/StorageProperties.java:7-21` — plain POJO `@ConfigurationProperties(prefix = "storage")`, nested static class, getters/setters, NOT `@Validated` (no `@Validated` exists in `core-java/src/main`; validation goes in an explicit method as RESEARCH Pattern 1 specifies).
```java
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {
    private S3Properties s3 = new S3Properties();
    private long maxFileSizeBytes = 5_242_880; // 5MB
    private List<String> allowedContentTypes = List.of("image/jpeg", "image/png", "image/webp", "image/gif");
    ...
    public static class S3Properties { private String endpoint = "http://localhost:9000"; ... }
```
Keep `maxFileSizeBytes` and `allowedContentTypes` byte-identical. Replace only the nested class with `Blob` (`authMode`, `connectionString`, `endpoint`, `publicContainer`, `quarantineContainer`, `publicUrl`, `createContainers`, `validateOnStartup`, `maxTries`, `tryTimeoutSeconds`).

**`StorageConfig.java:15-24`** — keep `@Configuration @EnableConfigurationProperties(StorageProperties.class)`, the SLF4J logger and one `@Bean`. Note the log line at `:23-24` logs the endpoint; the new log must log **mode + endpoint host only**, never the connection string (RESEARCH Pattern 1 excerpt).

---

### `StorageService.java` (service, file-I/O, in-place)

**Public surface to keep unchanged** (from grep): `upload(...)` `:88,:106`, `uploadNamed` `:166`, `SEED_URL_MARKER` `:194`, `productUploadUrlPrefix` `:200,:214`, `putSeedImage` `:237`, `delete(String)` `:289`, `putBytes` `:328`, `getBytes` `:345`, `deleteByKey` `:362`, `deleteByKeyChecked` `:382`, `urlForKey` `:398`, `detectContentType` `:484`. Constructor `:79` changes type of its first argument only.

**D-09 tenant guard goes into `delete(String)`** — current body (lines 289-307):
```java
public void delete(String imageUrl) {
    if (imageUrl == null || imageUrl.isBlank()) return;
    String publicUrlPrefix = properties.getS3().getPublicUrl() + "/";
    if (!imageUrl.startsWith(publicUrlPrefix)) {
        log.debug("Skipping delete for external URL: {}", imageUrl);
        return;
    }
    String key = imageUrl.substring(publicUrlPrefix.length());
    try { s3Client.deleteObject(...); log.info("Deleted image from storage: {}", key); }
    catch (Exception e) { log.warn("Failed to delete image {}: {}", key, e.getMessage()); }
}
```
Insert after `key` is extracted: compare `key` first segment against `TenantContext.get()`; mismatch or absent tenant → `log.warn(...)` and `return` (same skip-and-log shape as the external-URL branch).

**Best-effort checked delete contract** (lines 382-395) — preserve exactly; swap `deleteObject` for `deleteIfExists()`:
```java
public boolean deleteByKeyChecked(String objectKey) {
    if (objectKey == null || objectKey.isBlank()) return true;
    try { ...delete...; log.info("Deleted object from storage by key: {}", objectKey); return true; }
    catch (Exception e) { log.warn("Failed to delete object {}: {}", objectKey, e.getMessage()); return false; }
}
```
**`putBytes` (328-340)** keeps `contentType` = detected type and `cacheControl("public, max-age=31536000, immutable")` for public-container keys only (RESEARCH Pattern 2). Quarantine routing helper `isQuarantineKey` per RESEARCH Pattern 2; do NOT touch `MediaQuarantineRetentionSweep.QUARANTINE_SEGMENT` (l.81/133).

---

### `StorageStartupValidator.java` (startup validator, event-driven)

**Analog:** `core-java/src/main/java/uk/jtoye/core/config/DatabaseConfigurationValidator.java`

**Imports/annotations** (lines 1-9, 29-31):
```java
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
@Component
@org.springframework.context.annotation.Profile("!test")
public class DatabaseConfigurationValidator {
```
Deviation (RESEARCH Pattern 4): gate on `@ConditionalOnProperty(name="storage.blob.validate-on-startup", havingValue="true", matchIfMissing=true)` instead of `@Profile("!test")` — two `@ActiveProfiles("dev")` ITs boot without Azurite.

**Core + error pattern** (lines 53-96):
```java
@EventListener(ApplicationReadyEvent.class)
public void validateDatabaseConfiguration() {
    try {
        validateNotSuperuser(); ...
        log.info("✅ DATABASE SECURITY VALIDATION PASSED");
    } catch (SecurityConfigurationException e) {
        log.error("CRITICAL SECURITY ERROR: {}", e.getMessage());
        throw e;
    } catch (Exception e) {
        throw new SecurityConfigurationException(
            "Database security validation failed with unexpected error: " + e.getMessage(), e);
    }
}
```
Copy the typed-rethrow + wrap-unknown structure with `StorageConfigurationException`. (Drop the emoji log markers per no-emoji convention for new code.)

---

### `DemoDataSeeder.java` (seeder, in-place)

**Analog:** itself, lines 472-481 — `catch (SdkClientException e)` → "object store unreachable; skipping demo image seeding". Replace with: `catch (BlobStorageException e)` = per-entry skip, then `catch (RuntimeException e)` = unreachable → abort (RESEARCH Pattern 3 row). Keep the WARN message and "never fatal to dev boot" comment.

---

### `AzuriteStorageIntegrationTest.java` + shared fixture (test)

**Analog A (container field, digest/image string):** `core-java/src/test/java/uk/jtoye/core/config/MediaListenerConcurrencyIntegrationTest.java:62-64`
```java
@Container
static final RabbitMQContainer RABBIT = new RabbitMQContainer(
        DockerImageName.parse("rabbitmq:4.3.4-management-alpine"));
```
**Analog B (Spring IT class header + DynamicPropertySource):** `core-java/src/test/java/uk/jtoye/core/audit/AuditIntegrationTest.java:33-47`
```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("testcontainers")
class AuditIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")...;
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }
```
**Fixture analog:** `core-java/src/test/java/uk/jtoye/core/testsupport/IntegrationTestSupport.java:54` (`public final class`, static helpers) and `:121-135` (`registerPostgresTestProperties(registry, container)`). Model `AzuriteTestSupport.registerAzuriteProperties(registry, azurite)` on it (connection string, containers, `create-containers=true`, `validate-on-startup` as the test needs).
**Note:** there is NO true singleton-container fixture in the repo — every IT declares a per-class static `@Container`. A singleton (static-initializer `start()`) would be new; if chosen, say so in the plan. The `@Tag("testcontainers")` is mandatory (the `test` task excludes it, `integrationTest` includes it).

---

### Rewritten unit tests (4 files)

**Analog:** `core-java/src/test/java/uk/jtoye/core/storage/StorageServiceTest.java:34-57` — `@ExtendWith(MockitoExtension.class)`, `@Mock` the client, construct directly:
```java
storageService = new StorageService(s3Client, properties, new MediaNormalizer(new MediaProperties()));
```
Replace `@Mock S3Client` with `@Mock BlobObjectStore` (the adapter) so no SDK types leak into service tests; keep `ArgumentCaptor` assertions on content-type/cache-control.

---

### `docker-compose.full-stack.yml` / `infra/docker-compose.yml` (azurite service)

**Analog:** `docker-compose.full-stack.yml:596-619` (minio service): `container_name: jtoye-*`, `restart: unless-stopped`, loopback bind `"${JTOYE_BIND_HOST:-127.0.0.1}:9000:9000"` with the #441 comment, named volume, healthcheck, `networks: [jtoye-network]`. Image tag via env override with default (`${MINIO_IMAGE_TAG:-latest}`) — Azurite default must carry `@sha256:` (RESEARCH Pattern 5). core-java `depends_on` block shape at `:368-374` (`condition: service_healthy`) — add `azurite`.

---

### `infra/dependency-horizons.yaml` (azurite row)

**Analog:** lines 196-219 (`id: minio`): `id`, `pin` (must equal the compose `image:` string character-for-character — H-1), `sites: ["docker-compose.full-stack.yml:<line>"]`, `kind: image`, `owner`, `eol_slug`, `note`, `manual_review {last_checked, expires, url}`. Update `sites` line numbers after the compose edit.

---

### `scripts/dev-media-reseed.sh` (dev-only DB script, batch)

**Analog 1 — psql helper, VOID contract:** `scripts/seed-media-review-fixtures.sh`
- exit codes header `:73` (`2 = VOID — could not evaluate ... Never treat as 0`)
- `:101` `void() { echo "VOID: $*" >&2; exit 2; }`
- `:107-110` the single query path:
```bash
# -v ON_ERROR_STOP=1 is load-bearing: without it psql reports success after a failed statement.
psql_q() {
  docker exec -i "$PG_CONTAINER" psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 -tAc "$1"
}
```
- `:133` GUC pin `select set_config('app.current_tenant_id', '$TENANT_ID', false);`
- `:167-168` multi-statement body piped with `docker exec -i` and `rc=$?` on the same line.

**Analog 2 — tenant loop with row counting:** `core-java/src/main/resources/db/migration/V57__shop_staff_grant_source.sql:69-93`
```sql
DO $$
DECLARE t RECORD; n BIGINT; backfilled BIGINT := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.current_tenant_id', t.id::text, true);
        UPDATE shop_staff SET ... WHERE tenant_id = t.id AND ...;
        GET DIAGNOSTICS n = ROW_COUNT;
        backfilled := backfilled + n;
    END LOOP;
    PERFORM set_config('app.current_tenant_id', '', true);
    RAISE NOTICE 'V57: backfilled ... % row(s).', backfilled;
END $$;
```
Add: `--dry-run` default / `--apply` / `--cutover`; refuse (exit non-zero) when the before-count is 0 (D-05); run as `jtoye_app` (owner, FORCE RLS applies), never the superuser.

---

### `scripts/check-media-urls-resolve.sh` (runtime gate) and `check-media-content-types.sh` (re-target)

**Analog:** `scripts/check-media-content-types.sh` (268 lines). Copy:
- Header sections (`WHY THIS EXISTS`, `WHAT IS ASSERTED` A-1 relation / A-2 denominator ≥ 1, `EXIT CODES 0/1/2`, `SHAPE RULES OBSERVED`, `WHY THIS IS NOT IN CI`) — lines 1-117.
- Preamble lines 119-131:
```bash
set -uo pipefail
VOID=2
FAIL=1
void() {
    echo "VOID: $*" >&2
    echo "  (a result that cannot be evaluated is never a clean bill)" >&2
    exit "$VOID"
}
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
```
- Tool preconditions `:137-138` (`command -v docker ... || void ...`), container-state check `:173-176` (`STATE=$(docker inspect -f '{{.State.Status}}' ...); rc=$?`).
- No credential on the command line (`:98-100`): for Azurite use `az --connection-string` via env var, not argv.

---

### `scripts/check-no-object-store-residue.sh` (static gate with reasoned allowlist)

**Analog:** `scripts/check-no-create-extension.sh:60-154`
- Enumerate-then-refuse-empty (`:63-66`): `mapfile -t ... ; [ "$COUNT" -gt 0 ] || void "...refusing to report clean over an empty scan"`.
- Exemptions by addition with justification (`:104-107`): `EXEMPT=( "<file>|<token>|<justification>" )`.
- `is_exempt` with `IFS='|' read -r` + `grep -qF ... <<< "$line_text"` (here-string, not pipe) `:109-118`.
- Stale-exemption VOID (`:141-143`): matched-count must equal table size.
- Final `fail "..."` / `echo "PASS: ..."` `:145-154`.
**Allowlist hygiene analog (blank reason / duplicate / malformed → FAIL):** `k8s/scripts/check-render-invariants.sh:638-656`
```bash
svc="${entry%%|*}"; reason="${entry#*|}"
if [[ -z "$svc" || "$svc" == "$entry" ]]; then fail "... malformed ..."; fi
if [[ -z "${reason//[[:space:]]/}" ]]; then fail "... has a blank reason ..."; fi
if [[ -n "${ALLOW_INGRESS_REASON[$svc]:-}" ]]; then fail "... duplicate entry ..."; fi
```
Directory-prefix allowlist entries (`docs/archive/`, `.planning/`, `V42__...sql`, `docs/CHANGELOG.md`) per RESEARCH §Docs. Pattern must exclude `amazonaws` SES host and `jtoye-db-backups` dir (RESEARCH l.218) and CSP `blob:`.

---

### Gate wiring (`gate-enforcement.conf` / `ci-cd.yaml`)

- `scripts/check-gate-enforcement.sh:124-131`: a gate is satisfied if referenced by any workflow file (`refs > 0`); otherwise it must have a `gate-enforcement.conf` line `<basename.sh> <reason>`.
- Static gate (`check-no-object-store-residue.sh`) → wire into CI, copying `.github/workflows/ci-cd.yaml:933-945`:
```yaml
      - name: Assert no migration creates a PostgreSQL extension (33-02)
        run: |
          chmod +x ./scripts/check-no-create-extension.sh
          ./scripts/check-no-create-extension.sh
```
- Runtime gate (`check-media-urls-resolve.sh`) → a `gate-enforcement.conf` entry in the shape of line 37 (`check-media-content-types.sh <reason: needs a running store>`), and rewrite line 37's MinIO wording. Or wire into `e2e-nightly.yml` (the conf's own `:29-35` note: "wiring beats exempting whenever a real runtime is available").

---

### `infra/backups/blobctl/` (Go CLI)

**Analog (only Go module in the repo):** `edge-go/`
- `edge-go/go.mod:1-3` — `module github.com/jtoye/edge` / `go 1.27.0` (match the go directive; the Dockerfile comment `edge-go/Dockerfile:5-10` says every Go version pin moves in lockstep with `infra/dependency-horizons.yaml` rows `golang`, `go-toolchain`, `go-ci-setup`).
- Tests co-located `*_test.go` (`edge-go/cmd/edge/main_test.go`, `handlers_test.go`).
- Builder stage `edge-go/Dockerfile:11-30`:
```dockerfile
FROM golang:1.27-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download && go mod verify
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -trimpath ...
```
Copy this as stage 1 of `infra/backups/Dockerfile`; stage 2 stays `FROM postgres:15-bookworm` (do not change the base major — `postgres-major-parity.conf`), `COPY --from=builder`, keep `ENV HOME=/tmp`, `USER 1000:1000`, `ENTRYPOINT ["/usr/local/bin/backup"]`. Remove the `apt-get install awscli` line. Check whether CI's Go test job needs a new working-directory entry for `infra/backups/blobctl` (else its tests run nowhere).

### `infra/backups/k8s-backup.sh` (in-place)

Keep `:25-66` (required-env `: "${X:?...}"`, `log`/`fail`, `pg_dump` with explicit rc, size floor, `pg_restore --list`). Replace `S3_*` env (`:30,:34,:38-40`), `DEST` (`:46`), the upload (`:71`) with `blobctl upload ... || fail "Blob upload failed"`, and remove/replace the prune loop `:73-92` per WORM decision (RESEARCH Pattern 7).

---

### k8s manifests + `check-render-invariants.sh`

- CronJob env shape: `k8s/base/pg-backup-cronjob.yaml:78-108` — `configMapKeyRef {name: app-config, key: ...}` for target config; delete the `secretKeyRef s3-backup-credentials` pair (`:97-106`). Add `serviceAccountName`.
- INV-7 expected ports table `check-render-invariants.sh:354-357` → drop `9000` from both entries. `FORBIDDEN_RENDER_LITERALS` `:331-335` (drop/keep `minioadmin`).
- New invariants: copy the per-target block shape at `:833-903` — extract from render, `parse_fail` on an empty parse ("Fix the parser, do not delete the invariant"), accumulate `invN_bad`, set `invN_msg`, and add `invN_msg` to the combined OK/FAIL line at `:905-909` and the final PASS text at `:1283`.
- Goldens: `k8s/scripts/render-golden.sh --write`, never by hand.

---

### Frontend CSP / remotePatterns + tests

- `frontend/lib/security-headers.ts:94`: `"img-src 'self' data: blob: https://*.stripe.com https: http://localhost:9000",` → replace the last token with the exact Azurite origin; update comment `:34-37`.
- `frontend/next.config.mjs:19-33`: replace the `localhost:9000 /jtoye-images/**` pattern with the Azurite pattern; delete the commented `*.amazonaws.com` example.
- Test analog `frontend/__tests__/csp-headers.test.ts:22-31` (`parseCsp` helper) and `:108-114` (`it("has the baseline directives", ...)` with `buildCsp(base)`). New assertions: `parseCsp(csp)["img-src"].split(" ")` does not contain `http://localhost:9000` and contains no `*.blob.core.windows.net`. For remotePatterns use the dynamic-import analog `:169-172` (`const mod: any = await import("../next.config.mjs")`) and assert `mod.default.images.remotePatterns`.
- Snapshot: `header-snapshot.test.ts` regenerate with `npm test -- -u header-snapshot` (per its header `:1-7`).
- Fixture renames: `components/ui/__tests__/asset-image.test.tsx:12,40`, `components/dashboard/media/__tests__/ReviewQueue.test.tsx:88-89`.

---

### `frontend/e2e/storage-images.spec.ts` (e2e)

**Analog:** `frontend/e2e/storefront-flows.spec.ts`
- Imports/base URL `:8,13`: `import { test, expect, type Page } from "@playwright/test"`; `const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"`.
- Image evaluation `:429-437`:
```ts
const imageResults = await page.evaluate(() =>
  Array.from(document.querySelectorAll("img")).map((img) => {
    const el = img as HTMLImageElement
    return { loaded: el.complete && el.naturalWidth > 0, naturalWidth: el.naturalWidth, src: el.src.substring(0, 100) }
  })
)
```
- VOID-on-zero message style `:462-465` (`expect(cardCount, "VOID: no product cards rendered — ...").toBeGreaterThan(0)`).
New spec must filter `src.startsWith(<storage public URL>)` and require count ≥ 1 with `naturalWidth > 0` (Contract #3 accepts fallback tiles, so it cannot prove this — RESEARCH l.727). Do not truncate `src` before the prefix test.

## Shared Patterns

### Bash gate contract (exit 0/1/2)
**Source:** `scripts/check-media-content-types.sh:119-131`, `scripts/check-no-create-extension.sh:63-66,141-154`
**Apply to:** `check-no-object-store-residue.sh`, `check-media-urls-resolve.sh`, `dev-media-reseed.sh`, `restore-drill-local.sh`
Rules: empty input → VOID (2); `rc=$?` on the same statement; here-strings not `| grep -q`; `grep -c ... || true`; no `| head` on a listing used to prove absence.

### Fail-fast configuration
**Source:** `DatabaseConfigurationValidator.java:53-96` (typed rethrow + wrap unknown)
**Apply to:** `StorageConfig` shape validation and `StorageStartupValidator`.

### Tenant GUC pinning
**Source:** `V57__shop_staff_grant_source.sql:69-93`; `seed-media-review-fixtures.sh:133`
**Apply to:** reseed script, URL-resolve gate queries (an unpinned query returns 0 rows under RLS), D-09 test setup (`TenantContext.set`).

### Testcontainers IT header
**Source:** `AuditIntegrationTest.java:33-47`, `IntegrationTestSupport.java:121`
**Apply to:** `AzuriteStorageIntegrationTest`, `MediaPipelineAzuriteIntegrationTest`, `StorageStartupValidator` IT arms.

### Digest-pinned image + horizons row
**Source:** `docker-compose.full-stack.yml:596-597`, `infra/dependency-horizons.yaml:196-219`
**Apply to:** compose azurite (both compose files), the Testcontainers image string, the pg-backup image tag bump (D-10).

## No Analog Found

| File | Role | Reason |
|---|---|---|
| `k8s/base/serviceaccount-core-java.yaml`, `serviceaccount-pg-backup.yaml` | config | No `kind: ServiceAccount` / `serviceAccountName` anywhere in `k8s/` (RESEARCH l.281). Use RESEARCH §Code Examples (WI pod wiring) and the AKS docs. |
| Singleton Azurite fixture (if chosen over per-class `@Container`) | test support | Repo only uses per-class static `@Container` fields. |
| `infra/backups/restore-drill-local.sh` | script | No existing restore-drill script; the recipe exists only as prose in `docs/runbooks/backups.md:281+`. |

## Metadata

**Analog search scope:** `core-java/src/{main,test}`, `scripts/`, `scripts/gates/`, `k8s/`, `infra/`, `edge-go/`, `frontend/{__tests__,e2e,lib}`, `.github/workflows/`
**Files scanned:** ~40
**Pattern extraction date:** 2026-09-28
