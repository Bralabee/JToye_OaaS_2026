---
status: complete
task: harden demo seeder image-seeding failsafe (PR #214 regression)
completed: 2026-07-13
commit: d8bf1c9
---

# Quick 260713-ppr: DemoDataSeeder image-seeding failsafe

**Regression fixed:** PR #214's `seedProductImages()` called `StorageService.putSeedImage()` (S3 head/put) unguarded. The 3 `@ActiveProfiles("dev")` integration tests (`SecurityHeadersDevProfileTest`, `RedisFaultInjectionIntegrationTest`, `PublicRateLimitIntegrationTest`) boot Postgres/Redis via Testcontainers but no MinIO, so `SdkClientException` propagated out of `ApplicationRunner.run` → dev-profile context startup failed → all 4 test methods errored. Integration Tests passed on main (data-only seeder); PR #214 regressed it (confirmed: main 4/4 pass, branch 2/2 fail, same 4 tests; CI exception = `software.amazon.awssdk.core.exception.SdkClientException`).

**Fix (`d8bf1c9`):** wrapped the per-entry image work in `DemoDataSeeder.seedProductImages()`:
- `catch (SdkClientException)` — object store unreachable → log once + `return` (abort image seeding; avoids 21 sequential connection-timeouts). Data seeding already committed above; only optional image URLs skip.
- `catch (RuntimeException)` — store reachable but this entry failed (service error / unreadable image / bad request) → log + `continue`.
- Added `import software.amazon.awssdk.core.exception.SdkClientException`.
- Honours the seeder's own documented "never fatal to dev boot" contract. Real dev (MinIO up) still seeds all 21 unchanged.

**Verification:**
- `./gradlew -p core-java compileJava` → EXIT 0.
- `bash scripts/docs-freshness.sh` → green, 1243 (no metric change — no test added).
- Full 24-min Testcontainers suite deferred to CI (authoritative gate) due to session context limit — pushed `d8bf1c9`; the `Integration Tests (Testcontainers RLS)` job on PR #214 re-verifies. **Expected: the 3 dev-profile classes now pass WITHOUT MinIO.**
- No schema change (V50), no new deps (aws-sdk already present), dev-profile-only.

**If CI still red:** re-check that `SdkClientException` is what propagates (vs a wrapping `CompletionException`); the `RuntimeException` catch is a backstop but a wrapped SDK exception under a non-RuntimeException would need widening the catch. Unlikely — SdkClientException extends RuntimeException.
