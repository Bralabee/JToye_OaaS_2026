---
phase: "36"
slug: "azure-blob-storage-throughout"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-09-28"
---

# Phase 36 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `36-RESEARCH.md` §Validation Architecture. Standing contract (CLAUDE.md): every criterion
> is shown FAILING on a deliberately broken input before its pass is trusted, and both directions'
> real output are recorded.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers 1.21.4 (`@Tag("testcontainers")`, `AzuriteContainer`), Jest 30.5.1, Playwright 1.62.1, Go `go test`, bash gates (exit 0/1/2 = pass/fail/VOID) |
| **Config file** | `core-java/build.gradle.kts` (`test` excludes the tag; `integrationTest` includes it), `frontend/jest.config.*`, `frontend/playwright.config.*` |
| **Quick run command** | `./gradlew :core-java:test --tests 'uk.jtoye.core.storage.*'` |
| **Full suite command** | `./gradlew :core-java:test :core-java:integrationTest && (cd frontend && npm test -- --ci) && bash k8s/scripts/check-render-invariants.sh && bash k8s/scripts/render-golden.sh` |
| **Estimated runtime** | ~900 seconds (full); ~60 seconds (quick) |

---

## Sampling Rate

- **After every task commit:** Run the quick run command plus the row-specific gate for the files touched
- **After every plan wave:** `./gradlew :core-java:test :core-java:integrationTest`, jest, the k8s gates, the residue gate
- **Before `/gsd-verify-work`:** Full suite green, ALL images rebuilt, `scripts/check-runtime-freshness.sh` PASS, reseed + URL-resolve gate + Playwright storage spec against the running stack, a nightly `workflow_dispatch` run that executes Playwright
- **Max feedback latency:** 900 seconds

---

## Per-Task Verification Map

Task IDs are bound by the planner; rows are keyed by requirement until plans exist.

| Req | Behaviour | Test Type | Automated Command | Deliberate break (must go red first) | File Exists | Status |
|-----|-----------|-----------|-------------------|--------------------------------------|-------------|--------|
| BLOB-01 | No AWS SDK in main or runtime classpath | gate | `git grep -n -E 'software\.amazon\|S3Client\|awssdk' -- core-java/src/main core-java/build.gradle.kts` (expect rc=1) + runtimeClasspath has 0 `software.amazon` | Run on the pre-change tree: 3 main files / >0 deps | ❌ W0 | ⬜ pending |
| BLOB-01 | Storage config shape fails fast | unit | `./gradlew :core-java:test --tests '*StorageConfig*'` | Unknown mode; connection-string under prod; WI without `AZURE_CLIENT_ID` — each must throw | ❌ W0 | ⬜ pending |
| BLOB-01 | Startup probe fails fast | IT | `./gradlew :core-java:integrationTest --tests '*StorageStartupValidator*'` | Missing container; public container at `CONTAINER` level; quarantine public → context fails | ❌ W0 | ⬜ pending |
| BLOB-02 | #626 rule + private quarantine | IT (Azurite) | `./gradlew :core-java:integrationTest --tests '*AzuriteStorageIntegrationTest*'` | Public container at `CONTAINER` level → anonymous LIST 200 → red; swap routing → quarantine GET 200 → red | ❌ W0 | ⬜ pending |
| BLOB-03 | Pipeline on real Azurite | IT | `./gradlew :core-java:integrationTest --tests '*MediaPipelineAzuriteIntegrationTest*'` | Force normalizer failure → no derivative; skip quarantine delete → blob remains → red | ❌ W0 | ⬜ pending |
| BLOB-03 | Existing stubbed media suites stay green | IT | `./gradlew :core-java:integrationTest --tests 'uk.jtoye.core.media.*'` | Regression only — NOT evidence of storage behaviour (they stub StorageService) | ✅ | ⬜ pending |
| BLOB-04 | Compose: digest-pinned Azurite, no MinIO | gate | `docker compose -f docker-compose.full-stack.yml config --format json` + `jq` digest / no-minio assertions | Remove the digest → jq false (rc 1) | ❌ W0 | ⬜ pending |
| BLOB-04 | Nightly executes Playwright | CI | `gh workflow run e2e-nightly.yml`; run log shows Playwright executed-count > 0 | Historic: #683 runs red at image pull since 2026-09-25 | ✅ | ⬜ pending |
| BLOB-05 | Reseed non-zero + zero residue | script | `bash scripts/dev-media-reseed.sh --dry-run` then `--apply` | Wrong origin → before-count 0 → must refuse (exit ≠ 0) | ❌ W0 | ⬜ pending |
| BLOB-05 | Every servable URL resolves | gate | `bash scripts/check-media-urls-resolve.sh` | Delete one blob / insert bogus URL → exit 1; empty set → exit 2 | ❌ W0 | ⬜ pending |
| BLOB-05 | Browser renders storage images | e2e | `cd frontend && npx playwright test e2e/storage-images.spec.ts` (≥1 img from storage origin, `naturalWidth > 0`) | `docker stop jtoye-azurite` → naturalWidth 0 → red | ❌ W0 | ⬜ pending |
| BLOB-06 | Backup to Blob + restore | script | `bash infra/backups/restore-drill-local.sh` | Zero-row dump passes size/TOC but count comparison fails | ❌ W0 | ⬜ pending |
| BLOB-06 | Backup uploader both auth modes | unit (Go) | `cd infra/backups/blobctl && go test ./...` | Unknown mode / missing env → error | ❌ W0 | ⬜ pending |
| BLOB-07 | k8s invariants | gate | `bash k8s/scripts/check-render-invariants.sh && bash k8s/scripts/render-golden.sh && bash k8s/scripts/check-env-contract.sh` | Re-add port 9000 → red; drop WI label → red; storage `secretKeyRef` in staging → red | ✅ (extend) | ⬜ pending |
| BLOB-08 | CSP / remotePatterns exact origins | jest | `cd frontend && npx jest __tests__/csp-headers.test.ts __tests__/header-snapshot.test.ts` | Add `https://*.blob.core.windows.net` → red; keep `localhost:9000` → red | ✅ (extend) | ⬜ pending |
| BLOB-09 | Zero MinIO/S3 residue | gate | `bash scripts/check-no-object-store-residue.sh` | Plant `minio` in a non-allowlisted file → exit 1; empty allowlist reason → exit 1 | ❌ W0 | ⬜ pending |
| BLOB-09 | Horizons / metrics / gitleaks | gate | `bash scripts/check-dependency-horizons.sh`; `bash scripts/docs-freshness.sh && bash scripts/check-doc-metrics.sh`; `gitleaks dir . --config .gitleaks.toml` | Remove azurite row → exit 1; skip `--write` → red; random `AccountKey=` still fires | ✅ | ⬜ pending |
| all | Runtime parity | gate | `bash scripts/check-runtime-freshness.sh && bash scripts/check-branch-behind-base.sh` | `compose start` without rebuild → red | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `core-java/src/test/java/uk/jtoye/core/storage/AzuriteStorageIntegrationTest.java` — BLOB-02
- [ ] `core-java/src/test/java/uk/jtoye/core/storage/StorageStartupValidatorTest.java` + shape-validation unit test — BLOB-01
- [ ] `core-java/src/test/java/uk/jtoye/core/media/MediaPipelineAzuriteIntegrationTest.java` — BLOB-03
- [ ] Shared Azurite test fixture (singleton, digest-pinned, API-version check skipped)
- [ ] `scripts/check-no-object-store-residue.sh`, `scripts/check-media-urls-resolve.sh`, `scripts/dev-media-reseed.sh` — wired via `scripts/gates/gate-enforcement.conf`
- [ ] `frontend/e2e/storage-images.spec.ts`
- [ ] `infra/backups/blobctl/` (+ `_test.go`), `infra/backups/restore-drill-local.sh`
- [ ] `application-test.yml`: startup validation off for unrelated test contexts

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Staging Storage Account: anonymous GET allowed, LIST refused; WORM + soft delete on backup container | BLOB-02, BLOB-06, BLOB-10 | No Azure account in scope (D-03) — provisioned in Phase 29 | Stated in the BLOB-10 Phase 29 handoff with the exact `az` read-back commands |
| Approve dev reseed `--apply` against the shared dev DB | BLOB-05 | Mutates the shared dev DB; D-04 accepts loss of hand-uploaded images | Review `--dry-run` counts, then approve |
| Eyeball the storefront after reseed | BLOB-05 | Visual confirmation in addition to the Playwright `naturalWidth` proof | Open the storefront at 390/1280 widths |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 900s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
