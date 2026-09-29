---
phase: 36-azure-blob-storage-throughout
verified: 2026-09-29T00:00:00Z
status: passed
human_verification_result: "approved by the owner 2026-09-29 via 36-UAT.md (1/1 pass)"
score: 6/6 must-haves verified
covered_files: [".planning/phases/36-azure-blob-storage-throughout/36-01-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-01-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-02-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-02-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-03-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-03-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-04-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-04-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-05-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-05-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-06-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-06-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-07-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-07-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-08-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-08-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-09-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-09-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-10-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-10-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-11-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-11-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-12-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-12-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-13-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-13-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-14-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-14-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-15-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-15-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-16-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-16-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-17-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-17-SUMMARY.md", ".planning/phases/36-azure-blob-storage-throughout/36-18-PLAN.md", ".planning/phases/36-azure-blob-storage-throughout/36-18-SUMMARY.md", ".planning/REQUIREMENTS.md", "core-java/src/main/java/uk/jtoye/core/storage/StorageService.java", "core-java/src/main/java/uk/jtoye/core/storage/StorageProperties.java", "core-java/src/main/java/uk/jtoye/core/storage/StorageConfig.java", "core-java/src/main/java/uk/jtoye/core/storage/BlobObjectStore.java", "core-java/src/main/java/uk/jtoye/core/storage/AzureBlobObjectStore.java", "core-java/src/main/java/uk/jtoye/core/storage/StorageStartupValidator.java", "docker-compose.full-stack.yml", "k8s/base/serviceaccounts.yaml", "k8s/goldens/staging.yaml", "k8s/goldens/production.yaml", "frontend/next.config.mjs", "frontend/lib/security-headers.ts", "scripts/check-no-object-store-residue.sh", "scripts/check-backup-restore-drill.sh", "scripts/dev-media-reseed.sh", "frontend/e2e/storage-images.spec.ts", ".planning/phases/36-azure-blob-storage-throughout/36-PHASE29-HANDOFF.md", "docs/runbooks/azure-blob-provisioning.md"]
covered_digest: "v1:sha256:d0665eef0f407352f29488960998751ef895874e5e5b95e1568472a8f245d51d"
behavior_unverified: 0
overrides_applied: 0
deferred:
  - truth: "The #626 rule (anonymous GET 200 / LIST refused / quarantine refused) is stated for the real staging Azure account with the exact read-back commands executed"
    addressed_in: "Phase 29"
    evidence: "36-PHASE29-HANDOFF.md hands Phase 29 the provisioning spec; docs/runbooks/azure-blob-provisioning.md §8 contains the exact read-back commands Phase 29 must run against jtoyestgmedia once provisioned; WINDOWS.md item #1 (subscription's Microsoft.Storage RP NotRegistered, no verdict on account names) is open and owned by Phase 29's provisioning step. ROADMAP.md lists Phase 29 as the next phase to execute after Phase 36 (line 52-53 ordering) and Phase 29 explicitly 'Blocks'/'is blocked by' this phase per both roadmap entries."
  - truth: "AKS Workload Identity federated-credential token exchange is proven live (the webhook mutates the labelled pod, the Entra ID exchange succeeds) for core-java and pg-backup in staging"
    addressed_in: "Phase 29"
    evidence: "WINDOWS.md item #3 (open): 'jtoye-staging-aks has WI off and is stopped' — proven only at render level (INV-8/9/10) and against the real validators (StorageConfig.validateShape, blobctl config) in this phase, which is everything provable without a WI-enabled live cluster; az aks update --enable-workload-identity is listed as Phase 29's job in 36-PHASE29-HANDOFF.md"
  - truth: "WORM immutability policy, soft delete and container-scoped RBAC are locked and verified on the real jtoyestgbackup account"
    addressed_in: "Phase 29"
    evidence: "36-CONTEXT.md D-11: 'Recorded, NOT locked... The policy stays UNLOCKED through the Phase 29 restore drill, and a human locks it (docs/runbooks/azure-blob-provisioning.md §4.4).' Azurite does not support soft delete/immutability (36-RESEARCH.md), so this is untestable before real accounts exist."
human_verification:
  - test: "Open http://localhost:3000, go to a seeded shop at 390px and 1280px widths, scroll to the bottom first: product cards show real food photos whose image URL (right-click, copy image address) starts with http://localhost:10000/devstoreaccount1/jtoye-images. Then sign in as the dev vendor and open the media review queue: assets affected by the reseed show FAILED with the reason \"Bytes not carried over in the Phase 36 dev reseed (D-04) -- re-upload\" and a Re-upload control."
    expected: "Real, non-broken food photography renders at both widths from the Azurite origin; the review queue clearly surfaces the FAILED/Re-upload state rather than looking like a silent error or blank tile."
    why_human: "This is the plan's own deliberately-deferred end-of-phase human-check (36-13-PLAN.md Task 2 `<human-check>`), carried forward verbatim into 36-13-SUMMARY.md's end-of-phase verification list and explicitly marked \"queued\" there, never yet answered \"approved\". Whether food photography looks right and whether the FAILED/Re-upload state reads clearly to a vendor is a visual/UX judgment call an assertion cannot make; the Playwright spec proves pixels decode (naturalWidth > 0), not that they look acceptable."
---

# Phase 36: Azure Blob Storage Throughout Verification Report

**Phase Goal:** Object storage is Azure Blob Storage in staging and production and Azurite (`mcr.microsoft.com/azure-storage/azurite`) locally and in the nightly, with no S3 API, MinIO image or AWS storage credential left anywhere in the platform — and every storefront image, upload and backup path proven working end to end on the new store.
**Verified:** 2026-09-29
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

Derived from ROADMAP.md "### Phase 36" Success Criteria 1–6 (the roadmap contract), cross-checked against `must_haves` in all 18 plan frontmatters and BLOB-01..BLOB-10 in REQUIREMENTS.md.

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | core-java stores/reads/deletes media through the Azure Blob SDK behind an unchanged `StorageService` surface; AWS SDK absent from main code AND the runtime classpath; one `storage.blob.auth-mode` switch fails at startup on misconfiguration; the media pipeline is proven against a real Azurite. (BLOB-01, BLOB-03) | ✓ VERIFIED | `git grep -l -E 'software\.amazon' -- core-java/src/main` → 0 hits (I ran this myself). Running container `jtoye_oaas_2026-core-java-1`: `unzip -l /app/app.jar \| grep -ci software/amazon` → **0**; `grep -ci azure` → 10 (I ran this myself, not from SUMMARY). `StorageConfig`/`StorageProperties.Blob.validateShape` (read directly) throw `StorageConfigurationException` in the `@Bean` factory before Tomcat starts. `MediaPipelineAzuriteIntegrationTest` (`@Testcontainers`, `@Tag("testcontainers")`, real `AzuriteContainer`) exercises accept→worker→WebP derivative→quarantine delete unstubbed; CI Testcontainers integration suite green on eefb46fa (orchestrator-supplied CI run 36590179085). |
| 2 | The #626 rule (anonymous GET by URL = 200, LIST refused, quarantine private) is proven both ways against Azurite and asserted by core-java at every boot; a cross-tenant URL delete is refused (D-09). (BLOB-02) | ✓ VERIFIED (staging-account read-back deferred, see `deferred:`) | `StorageStartupValidator` (read in full): runs on every `ApplicationStartedEvent`, gated on `storage.blob.validate-on-startup` (literal `true` in application.yml, no env override) — refuses a `CONTAINER`-level public container and a non-`PRIVATE` quarantine container, in every runtime including staging/production once deployed. `AzuriteStorageIntegrationTest` proves `#626: the public container serves anonymous GET by URL but refuses an anonymous LIST` both directions with precondition/postcondition assertions. `StorageDeleteTenantGuardIntegrationTest.foreignTenantBlobSurvivesAndOwnBlobIsDeleted` proves tenant B's blob survives tenant A's delete request (precondition 200, postcondition 200 unchanged, own blob 404). `StorageService.delete()` (read in full) refuses on missing tenant segment, absent `TenantContext`, foreign tenant, or a non-plain-path key, logging WARN (D-09). Remaining limb — the exact read-back commands executed against the real `jtoyestgmedia` account — cannot run before Phase 29 provisions it; the commands themselves are written and committed (`docs/runbooks/azure-blob-provisioning.md` §8, verified present). |
| 3 | Compose, the hybrid runtime and the nightly run a digest-pinned Azurite; `minio`/`minio-init` are gone; a `workflow_dispatch` nightly executes Playwright with an executed count above zero. (BLOB-04) | ✓ VERIFIED | `grep -ni minio docker-compose.full-stack.yml` → 0 hits (I ran this myself). `docker-compose.full-stack.yml:609` pins `mcr.microsoft.com/azure-storage/azurite:${AZURITE_IMAGE_TAG:-3.37.0@sha256:830430c1...}`. `.github/workflows/e2e-nightly.yml:77` service list is `core-java edge-go frontend mcp-server azurite mailhog` (no minio). Nightly evidence file `evidence/36-18-nightly-run.txt` (read in full, not just cited): `conclusion=success`, `playwright_executed=325 playwright_passed=319 playwright_skipped=6`, `storage_images_spec=... mobile=passed desktop=passed`; fail-direction control in the same file: historical run `36511482252` (pre-fix) shows `playwright_executed=0`. Currently-running local `jtoye-azurite` container confirmed via `docker ps` (healthy), no `minio` container present. |
| 4 | Existing local media is deliberately reseeded (tenant-looped, D-04/D-05); every servable image URL HEADs 200 on Azurite; an upload travels the full pipeline; a real browser renders storefront images with `naturalWidth > 0` from the storage origin. (BLOB-05) | ✓ VERIFIED | `scripts/dev-media-reseed.sh` (read in full): loops `t.id` from `tenants`, calls `set_config('app.current_tenant_id', ...)` per tenant before every RLS-scoped SELECT/UPDATE (D-05), records per-tenant before/after visibility counts, marks affected `media_asset` rows FAILED with a Phase-36-specific `failure_reason`. `frontend/e2e/storage-images.spec.ts` + `evidence/36-13-browser-proof.txt` (read in full): documented RED with Azurite stopped (`in-dom=0 loaded=0`, 2 failed) and with the wrong storage origin (VOID), documented GREEN after `start azurite` (`loaded=9`, 2 passed) — a genuine falsifiable fail-then-pass pair, not a pass-only assertion. Nightly run confirms `storage-images: mobile=passed desktop=passed`. |
| 5 | The pg-backup job writes to Azure Blob and a nightly two-arm restore drill (zero-row arm caught, live-count arm matched) runs; k8s base/staging/production/local carry Blob config with Workload Identity, dedicated ServiceAccounts, no storage Secret, no port 9000; Phase 29 has a handoff on main. (BLOB-06, BLOB-07, BLOB-10) | ✓ VERIFIED (live-cluster WI exchange + locked WORM deferred, see `deferred:`) | `evidence/36-18-nightly-run.txt` line 53: `restore_drill=PASS: arm A restored 0, arm B restored 23 = live 23 (> 0).` — a genuine executed nightly gate, not a static check. `k8s/base/serviceaccounts.yaml` defines ServiceAccounts `core-java` and `pg-backup` (I read the file); `k8s/goldens/{staging,production}.yaml` each show 2 occurrences of `azure.workload.identity/use` and 0 occurrences of `s3-media-credentials\|s3-backup-credentials\|S3_ACCESS_KEY\|S3_SECRET_KEY` (I grepped the live goldens myself, excluding `.pre/` historical snapshots which legitimately retain the old shape). `k8s/base/networkpolicies/{20-core-java,40-datastores}.yaml` reference port 9000 only in comments explaining its removal — no active rule. `36-PHASE29-HANDOFF.md` (git-committed at `a7027815`, I read the full file): decisions-superseded table, the 7→3 operator-secret-list diff, a merge-conflict map. |
| 6 | Frontend `remotePatterns`/CSP `img-src` admit the Azurite origin and nothing broader (test-enforced); a repo-wide fail-armed residue gate finds zero MinIO/S3 residue; horizons carry an azurite row; the content-type gate is re-targeted to Blob; docs/metrics are current. (BLOB-08, BLOB-09) | ✓ VERIFIED | `frontend/lib/security-headers.ts:94` CSP `img-src` carries `http://localhost:10000`, no `9000`/wildcard. `frontend/next.config.mjs` `remotePatterns` names one exact `hostname: 'localhost', port: '10000'` entry with a comment pointing at `__tests__/csp-headers.test.ts` for wildcard enforcement. I ran `scripts/check-no-object-store-residue.sh` myself: `SUMMARY files_scanned=2557 raw_hits=2089 unique_lines=1925 allowlisted=1925 violations=0 stale_entries=0 hygiene_failures=0` → `PASS`. Fail-direction evidence (`evidence/36-16-arms.txt`, read in full): multiple genuine `FAIL: 1 residue line(s)` runs after planting a violation, each restored and re-verified PASS — real break-arm proof, not a pass-only script. `infra/dependency-horizons.yaml:197-202` has `id: azurite` with the exact compose pin and two sites; no `id: minio` row remains. `scripts/check-media-content-types.sh` (read) targets `az storage blob list` against the Blob endpoint, not `mc`/MinIO. `docs/metrics.json` `total_logical_invocations: 4130` matches the prose count already live in this session's loaded CLAUDE.md. Wired into CI at `.github/workflows/ci-cd.yaml:975-977` (Operational Contracts job, reported green by the orchestrator for run 36590179085). |

**Score:** 6/6 truths verified (0 present-but-behavior-unverified). One deliberately-deferred end-of-phase human-check is still queued (see Human Verification below), which is why overall status is `human_needed` rather than `passed` despite a clean score.

### Deferred Items

Every item below is explicitly out-of-scope for Phase 36 by its own CONTEXT.md/RESEARCH.md and requires a live Azure subscription resource that does not exist yet (Microsoft.Storage RP NotRegistered on the subscription, `jtoye-staging-aks` created without `--enable-workload-identity` and currently stopped). All three are formally handed to Phase 29, which ROADMAP.md lists as the next phase to execute (line 52 lists Phase 36 ahead of Phase 29 in run order, both currently unchecked) and which explicitly "Blocks"/"is blocked by" Phase 36 in both phases' roadmap entries.

| # | Item | Addressed In | Evidence |
|---|------|-------------|----------|
| 1 | The #626 rule stated for the real staging Azure account, with the exact read-back commands executed | Phase 29 | `docs/runbooks/azure-blob-provisioning.md` §8 (read in full) contains the exact `curl`/`az` read-back commands, written but not yet runnable; WINDOWS.md item #1 open, owned by Phase 29's account provisioning |
| 2 | AKS Workload Identity live token exchange (webhook mutation + Entra ID exchange) for core-java and pg-backup | Phase 29 | WINDOWS.md item #3 open: "jtoye-staging-aks has WI off and is stopped"; `36-PHASE29-HANDOFF.md` lists `az aks update --enable-workload-identity` as Phase 29's task |
| 3 | WORM immutability policy locked, soft delete verified, RBAC scopes verified on the real backup account | Phase 29 | 36-CONTEXT.md D-11: "Recorded, NOT locked... a human locks it" post-restore-drill; Azurite does not support immutability/soft delete at all (36-RESEARCH.md), so this genuinely cannot be tested pre-Phase-29 |

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `core-java/src/main/java/uk/jtoye/core/storage/{StorageService,StorageProperties,StorageConfig,BlobObjectStore,AzureBlobObjectStore,StorageStartupValidator}.java` | Azure Blob seam behind unchanged `StorageService` surface | ✓ VERIFIED | All 6 files present, read in full; no AWS SDK types; routing, cross-tenant guard, startup probe all substantive and wired into `@Component`/`@Bean` |
| `frontend/e2e/storage-images.spec.ts` | Real-browser proof images decode from storage origin | ✓ VERIFIED | Present, substantive (documented fail/pass arms), wired into nightly-only Playwright run (not the per-PR gate, by design) |
| `scripts/dev-media-reseed.sh` | Tenant-looped, dry-run-default reseed | ✓ VERIFIED | Present, uses `set_config` per tenant, dry-run/apply modes, non-zero before-count assertions |
| `scripts/check-no-object-store-residue.sh` | Repo-wide fail-armed residue gate | ✓ VERIFIED | Present, ran locally (0 violations, 52 live allowlist entries), wired into `ci-cd.yaml` |
| `scripts/check-backup-restore-drill.sh` | Two-arm nightly restore drill | ✓ VERIFIED | Present, wired into `e2e-nightly.yml:480`; nightly evidence shows PASS with both arms |
| `.planning/phases/36-azure-blob-storage-throughout/36-PHASE29-HANDOFF.md` | Phase 29 provisioning handoff | ✓ VERIFIED | Present, committed (`a7027815`), substantive (decisions table, secret-list diff, merge-conflict map) |
| `docs/runbooks/azure-blob-provisioning.md` | Provisioning + read-back runbook | ✓ VERIFIED | Present, committed, §8 has exact read-back commands |
| `k8s/base/serviceaccounts.yaml`, `k8s/goldens/{staging,production}.yaml` | ServiceAccounts + WI labels, no storage Secret | ✓ VERIFIED | `core-java`/`pg-backup` ServiceAccounts defined; live goldens (not `.pre/`) show 0 storage-secret references, 2 WI-label occurrences each |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `StorageService` | `BlobObjectStore`/`AzureBlobObjectStore` | Constructor injection, `containerFor(key)` routing | ✓ WIRED | Read directly; routing by key prefix (`quarantine/` vs derivative), no SDK types leak into `StorageService` |
| `StorageConfig` bean | `StorageProperties.Blob.validateShape` | Called inside the `@Bean` factory method | ✓ WIRED | Fails before Tomcat starts on bad shape (per Javadoc + code read) |
| `StorageStartupValidator` | `BlobObjectStore` | `@EventListener(ApplicationStartedEvent.class)` | ✓ WIRED | Runs on every boot in every environment (property default `true`, not env-mapped) |
| `docker-compose.full-stack.yml` core-java | `azurite` service | `STORAGE_CONNECTION_STRING: UseDevelopmentStorage=true;...http://azurite` | ✓ WIRED | Confirmed by running local stack (jtoye-azurite healthy, core-java serving 200s per orchestrator's runtime facts) |
| `.github/workflows/e2e-nightly.yml` | `scripts/check-backup-restore-drill.sh` | Explicit `run: bash scripts/check-backup-restore-drill.sh` step | ✓ WIRED | Line 480; nightly run 36552435811 executed it and PASSED |
| `.github/workflows/ci-cd.yaml` | `scripts/check-no-object-store-residue.sh` | Explicit `run` step at line 977 | ✓ WIRED | CI run 36590179085 (Operational Contracts job) reported green |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| AWS SDK absent from the running container's classpath | `docker exec jtoye_oaas_2026-core-java-1 sh -c 'unzip -l /app/app.jar \| grep -ci software/amazon'` | `0` | ✓ PASS |
| Azure SDK present in the running container's classpath | `docker exec jtoye_oaas_2026-core-java-1 sh -c 'unzip -l /app/app.jar \| grep -ci azure'` | `10` | ✓ PASS |
| No MinIO reference in compose | `grep -ni minio docker-compose.full-stack.yml \| wc -l` | `0` | ✓ PASS |
| Azurite pinned by digest in compose | `grep -n azurite docker-compose.full-stack.yml` | `mcr.microsoft.com/azure-storage/azurite:${AZURITE_IMAGE_TAG:-3.37.0@sha256:830430c1...}` | ✓ PASS |
| Repo-wide residue gate | `bash scripts/check-no-object-store-residue.sh` | `PASS: ... violations=0 stale_entries=0` (2557 files scanned) | ✓ PASS |
| Live goldens carry no storage Secret refs | `grep -ln 's3-media-credentials\|s3-backup-credentials\|S3_ACCESS_KEY\|S3_SECRET_KEY' k8s/goldens/staging.yaml k8s/goldens/production.yaml` | (empty) | ✓ PASS |
| Live goldens carry WI pod label | `grep -c azure.workload.identity/use k8s/goldens/{staging,production}.yaml` | `2` each | ✓ PASS |
| No port 9000 active NetworkPolicy rule | `grep -rn 9000 k8s/base/networkpolicies/` | 3 hits, all comments explaining removal | ✓ PASS |
| Local Azurite container healthy, no MinIO container | `docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}' \| grep -i "azurite\|minio"` | `jtoye-azurite ... Up 9 hours (healthy)` (no minio row) | ✓ PASS |

### Probe Execution

No `scripts/*/tests/probe-*.sh` files exist for this phase and no PLAN/SUMMARY declares one. The equivalent falsifiable-evidence gates for this phase are the ones already run above as behavioral spot-checks and the nightly-wired scripts (`check-no-object-store-residue.sh`, `check-backup-restore-drill.sh`, `check-media-urls-resolve.sh`), which I executed or whose fail-direction evidence I read directly rather than trusting SUMMARY narration.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| BLOB-01 | 36-01, 36-06, 36-14 | Blob SDK behind unchanged `StorageService`; AWS SDK absent from main code + runtime classpath; one auth-mode switch fails at startup | ✓ SATISFIED | Verified independently: 0 `software.amazon` hits in main code and in the running jar's classpath |
| BLOB-02 | 36-01, 36-05, 36-06, 36-07, 36-12 | #626 rule both ways against Azurite, asserted at every boot; D-09 cross-tenant delete guard | ✓ SATISFIED locally/every-runtime; staging-account read-back deferred to Phase 29 (see Deferred Items #1) | `AzuriteStorageIntegrationTest`, `StorageDeleteTenantGuardIntegrationTest`, `StorageStartupValidator` all read directly and substantive |
| BLOB-03 | 36-07 | Media pipeline proven against real Azurite unstubbed | ✓ SATISFIED | `MediaPipelineAzuriteIntegrationTest`, `@Tag("testcontainers")`, real `AzuriteContainer` |
| BLOB-04 | 36-02, 36-18 | Digest-pinned Azurite in compose/nightly/hybrid; minio gone; nightly executes Playwright > 0 | ✓ SATISFIED | Nightly evidence file: 325 executed, 319 passed, conclusion=success |
| BLOB-05 | 36-11, 36-12, 36-13 | Tenant-looped reseed; servable URLs HEAD 200; upload pipeline; browser naturalWidth > 0 | ✓ SATISFIED | Reseed script read in full; browser-proof evidence file documents genuine red→green |
| BLOB-06 | 36-04, 36-08, 36-09 | pg-backup writes to Blob; nightly two-arm restore drill | ✓ SATISFIED for the drill itself (ran on a real GitHub runner, PASS); WORM lock + live RBAC verification deferred to Phase 29 (see Deferred Items #3) | `evidence/36-18-nightly-run.txt` line 53 |
| BLOB-07 | 36-09, 36-10 | k8s Blob config, WI, ServiceAccounts, no storage Secret, no port 9000 | ✓ SATISFIED at render/manifest level; live AKS admission deferred to Phase 29 (see Deferred Items #2) | Live goldens grepped directly |
| BLOB-08 | 36-03 | CSP/remotePatterns admit Azurite origin only | ✓ SATISFIED | Files read directly, test-enforced |
| BLOB-09 | 36-02, 36-10, 36-12, 36-14, 36-15, 36-16, 36-17 | Zero residue; horizons row; content-type gate retargeted; docs/metrics current | ✓ SATISFIED | Residue gate run locally: 0 violations; horizons row present; metrics match CLAUDE.md |
| BLOB-10 | 36-05, 36-17 | Phase 29 handoff on main | ✓ SATISFIED | `36-PHASE29-HANDOFF.md` committed and read in full |

**No orphaned requirements** — every BLOB-01..BLOB-10 ID declared in REQUIREMENTS.md §BLOB is claimed by at least one of the 18 plans, and every plan's `requirements:` frontmatter maps back to a real BLOB ID.

### Anti-Patterns Found

Scanned every file changed on this branch relative to `origin/main` (223 files) for `TBD|FIXME|XXX` (debt-marker gate) and spot-checked the storage/k8s/frontend surfaces for `TODO|HACK|PLACEHOLDER` and stub shapes.

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `scripts/check-backup-restore-drill.sh:223`, `scripts/check-no-object-store-residue.sh:117`, `scripts/gates/object-store-residue-selftest.sh:46` | — | `mktemp -d ".../*.XXXXXX"` | — | False positive — `XXXXXX` is `mktemp`'s own template syntax, not a debt marker. No action needed. |
| `scripts/check-no-measured-placeholders.sh:33` | — | `PATTERN="...TBD..."` | — | False positive — the literal string `TBD` is itself the regex the gate is scanning FOR (a placeholder-detection pattern), not a debt marker left in this phase's own code. No action needed. |

No genuine `TBD`/`FIXME`/`XXX`/`TODO`/`HACK`/`PLACEHOLDER` debt markers found in phase-touched files. No blocker.

### Human Verification Required

1. **Storefront + vendor review-queue visual look after the reseed**

   **Test:** Open http://localhost:3000, go to a seeded shop at 390px and 1280px widths, scroll to the bottom first: product cards show real food photos whose image URL (right-click, copy image address) starts with `http://localhost:10000/devstoreaccount1/jtoye-images`. Then sign in as the dev vendor and open the media review queue: assets affected by the reseed show FAILED with the reason "Bytes not carried over in the Phase 36 dev reseed (D-04) -- re-upload" and a Re-upload control.

   **Expected:** Real, non-broken food photography renders at both widths from the Azurite origin; the review queue clearly surfaces the FAILED/Re-upload state in a way a vendor would understand, not as a silent error or blank tile.

   **Why human:** This is the plan's own deliberately-deferred end-of-phase check (`36-13-PLAN.md` Task 2 `<human-check>`), carried into `36-13-SUMMARY.md`'s end-of-phase verification list and explicitly marked "queued" — never yet answered "approved" by a human. The automated Playwright spec proves pixels decode (`naturalWidth > 0`); it cannot judge whether the photography looks acceptable or whether the FAILED/Re-upload UI reads clearly, which is exactly the class of check this project's own UI-quality/E2E-click-through standards (`feedback_ui_quality`, `feedback_e2e_click_through` in project memory) reserve for a human.

### Gaps Summary

No FAILED truths and no missing/stub artifacts were found. All 10 BLOB requirements have direct, independently-verified evidence in code, tests, CI runs, and committed docs — not merely SUMMARY narration. The phase's own REQUIREMENTS.md checkboxes mark BLOB-02 and BLOB-06 "Partial"/"In progress" (unticked) because their staging-account and live-AKS limbs cannot be executed before Phase 29 provisions real Azure resources; that is an honest, correctly-scoped deferral recorded in `36-PHASE29-HANDOFF.md`, `docs/runbooks/azure-blob-provisioning.md` §8, and `WINDOWS.md` items #1 and #3 — not a Phase 36 defect. The single blocking item for a clean `passed` verdict is the still-queued end-of-phase human visual check from `36-13-PLAN.md`, which needs a human to open the app and answer "approved" or describe what's wrong.

---

*Verified: 2026-09-29*
*Verifier: Claude (gsd-verifier)*
