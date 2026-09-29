---
phase: 36-azure-blob-storage-throughout
fixed_at: 2026-09-29T17:35:41Z
review_path: .planning/phases/36-azure-blob-storage-throughout/36-REVIEW.md
iteration: 1
findings_in_scope: 6
fixed: 6
skipped: 0
status: all_fixed
---

# Phase 36: Code Review Fix Report

**Fixed at:** 2026-09-29T17:35:41Z
**Source review:** .planning/phases/36-azure-blob-storage-throughout/36-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 6 (WR-01..WR-06; there are no Critical findings; IN-01..IN-08 were left alone as instructed)
- Fixed: 6. Of these, WR-04 is a documented, reasoned acceptance with no code change. WR-02 and WR-06 are logic changes, marked **fixed: requires human verification**.
- Skipped: 0

**Where the work and verification ran:** in the main checkout `/home/sanmi/IdeaProjects/JToye_OaaS_2026`, on branch `phase-36-azure-blob-storage`, not in an isolated worktree. The orchestrator directed this: it named this checkout and said no other session was using it, and a hand-rolled worktree has no `node_modules` or Gradle state for the gates. Nothing was pushed, and the docker stack was not rebuilt or restarted. The live stack was only read: one read-only run of `check-media-urls-resolve.sh`, which issues SELECTs and anonymous HEADs.

**Commits** (base `e8b6a132`):

| Commit | Finding |
|---|---|
| `bb5bf74f` | WR-01 |
| `f4c38d6b` | WR-02 |
| `71b206bf` | WR-01 follow-up (a citation shifted by WR-01's longer comment) |
| `0a71d6ff` | WR-03 |
| `0be25b8a` | WR-04 |
| `23dfc672` | WR-05 |
| `f7168940` | WR-06 |
| `8a2d778e` | WR-02/WR-06 follow-up (metrics for the 7 new tests) |

None of the commit messages carries a Co-Authored-By, Claude-Session or "Generated with" line: grep over `e8b6a132..HEAD` returned 0.

## Fixed Issues

### WR-01: `storage.blob.validate-on-startup` CAN be switched off from the environment

**Files modified:** `k8s/scripts/check-render-invariants.sh`, `core-java/src/main/resources/application.yml`, `core-java/src/main/java/uk/jtoye/core/storage/StorageProperties.java`, `core-java/src/main/java/uk/jtoye/core/storage/StorageStartupValidator.java`, `.planning/codebase/INTEGRATIONS.md` (follow-up)
**Commits:** `bb5bf74f`, follow-up `71b206bf`
**Applied fix:**
- **New render invariant INV-11.** It runs on every target (base, local, staging, production) and fails a render when:
  - (a) the raw render text names the switch in any spelling. The check is case-insensitive and allows `-`, `_`, `.` or nothing between the words. This one rule covers env names and values, args, command, ConfigMap keys and values reached through configMapKeyRef, JSON bodies, and block scalars that the path walker skips.
  - (b) a core-java container sets `SPRING_APPLICATION_JSON`, or takes `JAVA_OPTS`, `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS` from a Secret. A Secret's value never appears in the render, so rule (a) cannot see it.
  - (c) a core-java container uses `envFrom`.
- **How containers are found.** A core-java container is any container whose image is the core-java image, found by exact path in any workload kind. Finding zero is a parse error (exit 2), not a pass.
- **Pattern self-test.** A self-test proves the pattern matches all 5 spellings of the switch and none of 5 neighbours.
- **Comments.** The three comments now state the truth: the literal is not a guard on its own, and INV-11 keeps the probe on in every k8s target. Test contexts keep their opt-out in `src/test/resources`.
- **Follow-up.** The longer yml comment moved the Stripe breaker block by 5 lines. That broke the citation in `.planning/codebase/INTEGRATIONS.md:14` (740-745 became 745-750): `check-doc-citations.sh` failed C-3 on the branch and passed on `e8b6a132`. The citation was renumbered in `71b206bf`.

**Fail/pass evidence (committed state, each arm restored and checked by sha256):**

| Arm | Result |
|---|---|
| clean before | rc=0 `PASS: INV-1..INV-7 and INV-11 hold across 4 kustomize target(s)` |
| env `STORAGE_BLOB_VALIDATEONSTARTUP=false` | rc=1, 4/4 targets FAIL `INV-11: the render names the storage-probe switch` |
| env named `storage.blob.validate-on-startup` | rc=1, 4/4 FAIL |
| `SPRING_APPLICATION_JSON` from a Secret | rc=1, 4/4 FAIL `sets env 'SPRING_APPLICATION_JSON'` |
| `JAVA_OPTS` from a Secret | rc=1, 4/4 FAIL `sources 'JAVA_OPTS' from a Secret` |
| `JAVA_OPTS` block scalar carrying `-D...=false` | rc=1, 4/4 FAIL (raw-text rule) |
| CONTROL: harmless literal `JAVA_OPTS=-Xmx512m` | rc=0 (no false positive) |
| `envFrom` on core-java | rc=1, 4/4 FAIL `uses envFrom` |
| `args: [--storage.blob.validate-on-startup=false]` | rc=1, 4/4 FAIL |
| app-config key `storage.blob.validate-on-startup: "false"` | rc=1, 4/4 FAIL |
| BLIND: container finder matches nothing | rc=2 `PARSE ERROR: [k8s/base] INV-11 found 0 containers running the core-java image` |
| BLIND: pattern narrowed to one spelling | rc=2 `PARSE ERROR: INV-11 self-test: PROBE_SWITCH_RE matched 1 of 5 spellings` |
| clean after | rc=0 |

The fail direction caught a real bug in my own first draft. The `JAVA_OPTS`-from-a-Secret arm first returned rc=0, because the second `=~` overwrote `BASH_REMATCH` before the env index was read. I fixed that (and reduced the `envFrom` message to one line per container) and amended it into `bb5bf74f`, then re-ran every arm. The two BLIND arms also first failed to apply (my `sed` did not match), so they were redone with an exact replacement. The table shows the final runs.

Also passing after the change: `render-golden.sh` (renders unchanged), `check-env-contract.sh`, and `./gradlew :core-java:compileJava`.

### WR-02: GDPR erasure counts every review photo as deleted even when the delete was refused

**Status:** fixed: requires human verification (logic change in Article 17 evidence accounting)
**Files modified:**
- `core-java/src/main/java/uk/jtoye/core/storage/StorageService.java`
- `core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java`
- `core-java/src/test/java/uk/jtoye/core/storage/StorageServiceTest.java`
- `core-java/src/test/java/uk/jtoye/core/gdpr/GdprServiceTest.java`
- `core-java/src/test/java/uk/jtoye/core/product/ProductImageDeleteIntegrationTest.java`

**Commit:** `f4c38d6b`
**Applied fix:**
- **`StorageService.delete(String)` now returns `boolean`.** It returns `true` only when `deleteIfExists` reported that this call removed an object. All of these return `false`:
  - a null or blank URL;
  - an external URL;
  - each D-09 refusal (no tenant segment, no TenantContext, foreign tenant, non-plain path);
  - an already-absent object;
  - a store failure.

  Behaviour is otherwise unchanged (WARN and continue).
- **`GdprService` counts only `true` results,** in both the response and the durable `ErasureRecord`. It logs a WARN with the number of photo URLs the record does not vouch for. The URLs are still cleared from the review. The record schema is unchanged (no migration).
- **Integration test stub.** `ProductImageDeleteIntegrationTest` now stubs the non-void method with `doReturn(true)`, because `doNothing` is only legal on void methods.

**Fail/pass evidence (committed state; tests read from fresh `core-java/build-local` XMLs):**
- ARM A (GdprService back to counting every attempt): gradle rc=1, GdprServiceTest 7 tests / 1 failure, `Erasure (WR-02): a photo the store did NOT delete is never counted as deleted in the record FAILED` (GdprServiceTest.java:247). Restored by hash.
- ARM B (`delete` returns `true` whether or not anything was removed): gradle rc=1, StorageServiceTest 27 / 1 failure, `delete (WR-02) - an object that was already absent is reported as NOT deleted FAILED` (StorageServiceTest.java:302). Restored by hash.
- Clean after: GdprServiceTest 7/0, StorageServiceTest 27/0.

**For the human check:** confirm that "already absent" should count as NOT deleted. The review's example counted ABSENT as deleted. The orchestrator asked for "only true deletions", which is what this implements.

### WR-03: The CronJob references `ghcr.io/bralabee/jtoye-pg-backup:15-blob`, which no CI job pushes or scans

**Files modified:** `.github/workflows/ci-cd.yaml`, `docs/runbooks/backups.md`
**Commit:** `0a71d6ff`
**Applied fix:**
- **New matrix leg.** `pg-backup` is a fourth `build-and-push` leg: context `infra/backups`, file `infra/backups/Dockerfile`, amd64-only. The existing Trivy SARIF report and table gate now cover it unchanged.
- **New step on that leg** (`id: cronjob_image`). It reads the image reference from `k8s/base/pg-backup-cronjob.yaml` and both goldens, so the tag is never restated in the workflow. The leg fails before pushing when:
  - the three disagree;
  - the reference is a digest pin;
  - the reference has no tag;
  - the repository is not the one this job publishes to (`${REGISTRY}/${IMAGE_OWNER,,}/jtoye-pg-backup`, derived from the owner as the deploy jobs derive theirs).
- **Tag push.** On the default branch the exact tag (`15-blob`) is pushed as an extra metadata `type=raw` tag. Feature-branch pushes never move it.
- **Goldens.** No golden change was needed: the tag the CronJob names is the tag CI now pushes.
- **Runbook.** It now says CI publishes the image. The manual `docker push` line is gone; the local build line stays.

**Fail/pass evidence** (the step's `run:` block was extracted from the committed workflow with js-yaml and run from the repo root with CI's env):

| Arm | Result |
|---|---|
| clean, owner `Bralabee` | rc=0, `GITHUB_OUTPUT: tag=15-blob` |
| fork owner `acme-fork` | rc=1 `the CronJob pulls 'ghcr.io/bralabee/jtoye-pg-backup', but this job publishes 'ghcr.io/acme-fork/jtoye-pg-backup'` |
| staging golden tag drifted | rc=1 `the CronJob and the goldens disagree` (lists both refs) |
| digest pin everywhere | rc=1 `is a digest pin; this step publishes a TAG` |
| no pg-backup reference at all | rc=2 `found no jtoye-pg-backup image reference` |
| clean after | rc=0, tree clean |

Other checks:
- actionlint: the break arm (`steps.cronjob_imagex`) gave rc=1 `property "cronjob_imagex" is not defined`. After restoring (by hash) it gave rc=0, and HEAD also gave rc=0.
- `check-gate-enforcement.sh`: rc=0.
- The bash half of the supply-chain gate's X-7, run by hand: 2 pins = 2 lowercased bases, 0 owner literals.
- **VOID:** `scripts/check-image-supply-chain.sh` rc=2. Its Python half was refused by the machine's pyshim: `BLOCKED by pyshim (~/.local/shims/python3, the execution-point authority): python3 -c … with no env would run in BASE`. It was not routed around. X-1..X-6 were therefore not machine-evaluated here. They should hold, because the matrix keeps `fail-fast: false` and the Trivy steps are untouched.
- **VOID:** a YAML parse through `/usr/bin/python3 -c 'import yaml'` was refused by the `block-base-python` hook. The workflow was parsed with the repo's own `frontend/node_modules/js-yaml` instead (matrix = `[core-java, edge-go, frontend, pg-backup]`), and with actionlint.

**Not verifiable here:** `build-and-push` only runs on push. The new leg's real build, push, Trivy verdict and metadata tag list are unproven until the first push to a `phase-*` branch or main. The main build's Trivy gate may now go red on a fixable CRITICAL/HIGH in `postgres:15-bookworm`, which would block the deploy jobs. That is the same policy every other shipped image is under.

### WR-04: The Azurite store is readable and writable with a PUBLIC key while the backing services are published off-loopback

**Resolution:** documented, reasoned acceptance. No code or compose change.
**Files modified:** `k8s/LOCAL.md`, `.env.example`, `scripts/gates/object-store-residue-allowlist.conf`
**Commit:** `0be25b8a`
**Why acceptance rather than a code fix:**
- The exposure exists only in the opt-in window, and `check-infra-exposure.sh` is red for all of it.
- Only local dev data is at stake. INV-4 and INV-9 keep emulator strings and keys out of staging and production renders.
- The real fix is a generated `AZURITE_ACCOUNTS` key, and that is not the smallest correct change. Every `UseDevelopmentStorage=true` consumer would move to the account-key form together: compose core-java, blobctl `loadEmulator`, the local overlay (LOC-1/3/7, with the key moved out of app-config into a Secret), the restore drill, the nightly and the reseed scripts. That can only be proven on a rebuilt stack, and this pass was told not to rebuild or restart it. A compose credential change shipped without that proof would be an unverified runtime claim.

`k8s/LOCAL.md` now states:
- the exposure (both containers, and what each holds);
- that the red gate says the port is open, not that its credential is public;
- the three reasons above;
- the conditions: trusted network only, shortest window, restore `127.0.0.1` straight after, nothing in the dev DB you would not publish;
- the trigger for taking the real fix (routine rehearsals, or an untrusted network).

A narrower bind (the address `host.minikube.internal` resolves to) is mentioned, explicitly marked as unmeasured. `.env.example`'s reachability note points to the section. The docs' claim about the other services was checked against compose: Postgres, Redis (`--requirepass`), RabbitMQ and Keycloak have credentials required by `verify-env.sh`, while Mailhog and Ollama have none. So the text names the four and does not say "all other services".

**Fail/pass evidence:**
- The new paragraph moved lines, and `check-no-object-store-residue.sh` caught it: rc=1, 17 violations and 14 stale allowlist entries (`k8s/LOCAL.md:849` and so on, `.env.example:488`).
- After renumbering (+35, then +1 after a wording fix; `.env.example` 488 became 492): rc=0 `PASS: … 1941 hit line(s) covered by 52 reasoned, live allowlist entr(ies)`.
- Also rc=0: `check-doc-citations.sh`, `check-no-measured-placeholders.sh`, `check-env-contract.sh`.

### WR-05: `check-media-urls-resolve.sh` treats only `http://localhost:9000/` as the retired origin

**Files modified:** `scripts/check-no-object-store-residue.sh`, `scripts/check-media-urls-resolve.sh`, `scripts/gates/object-store-residue-allowlist.conf`
**Commit:** `23dfc672`
**Applied fix:**
- **`--print-retired-pattern` on the residue gate.** It runs the same self-test, then prints R-1 alone on stdout and exits 0 without scanning; all other output goes to stderr. A self-test miss still VOIDs.
- **The media gate takes R-1 from there.** No second list is kept. Every absolute http(s) URL outside the public origin is classified with case-insensitive PCRE: a match is a FAIL, anything else stays external. The gate VOIDs when:
  - the pattern cannot be obtained;
  - it misses any of 5 retired samples (both loopback spellings, the container name, path-style and virtual-hosted cloud hostnames);
  - it matches an ordinary external URL;
  - it matches the delivered public URL itself.
- **Allowlist.** The two entries for the removed literal were replaced by one functional entry covering the sample lines (157-159).

**Fail/pass evidence:**
- **Defect reproduced.** In a hermetic harness (fake `docker`/`curl` on PATH feeding fixed rows), the OLD gate from `e8b6a132` was given only the 127.0.0.1, container-name and cloud-hostname spellings. It returned rc=0 `PASS … 0 point at the retired origin` and printed all three as `external (not fetched)`.
- **NEW gate, same rows:** rc=1 `FAIL: 3 … unrewritten old-store URL` for p2, s1 and r1.
- **NEW gate, 4 retired + 1 legitimate external:** rc=1 with 4 FAIL. `https://cdn.example.com/legit.jpg` stayed external.
- **VOID arms**, all rc=2:
  - residue gate absent;
  - pattern narrowed to one spelling (`does not match 'http://127.0.0.1:9000/…'`);
  - over-broad pattern (`matches an ordinary external URL`);
  - residue gate VOIDs;
  - public URL on a retired origin.
- **Residue gate `--print-retired-pattern`:**
  - break arm (R-1's first token renamed): rc=2, empty stdout, `VOID: self-test: r1-pos.txt matched 22 of 25 positive controls`;
  - restored by hash;
  - clean: rc=0, one line.
- **Normal residue gate run:** rc=0.
- **Pass arm on the LIVE stack** (read-only): rc=0 `PASS: 23 reference(s) (23 distinct URLs) answer 200 anonymously; 0 point at the retired store.`

### WR-06: `AzureBlobObjectStore.call` reports every non-`BlobStorageException` RuntimeException as "Object store unreachable"

**Status:** fixed: requires human verification (exception classification)
**Files modified:** `core-java/src/main/java/uk/jtoye/core/storage/AzureBlobObjectStore.java`, `core-java/src/main/java/uk/jtoye/core/storage/BlobObjectStore.java`, `core-java/src/test/java/uk/jtoye/core/storage/AzureBlobObjectStoreTest.java`
**Commit:** `f7168940`
**Applied fix:**
- **Narrowed wrapping.** `call` now wraps as `StorageUnavailableException` only when the cause chain carries an `IOException` or a `java.util.concurrent.TimeoutException`. The walk uses an identity set, so a cyclic chain cannot loop.
- **What propagates unchanged.** `BlobStorageException` still does, and so does everything else: IAE, NPE, identity errors.
- **Access level.** An unrecognised container access level is now a `StorageConfigurationException`.
- **Interface Javadoc.** It now states the third case.
- **No seeder change needed.** DemoDataSeeder's existing per-entry `catch (RuntimeException)` now skips a bad entry instead of aborting all seeding.

**Fail/pass evidence** (committed state; AzureBlobObjectStoreTest, fresh XMLs):
- **ARM A** (wrap everything again): rc=1, 7 tests / 3 failures. The IAE, NPE and unrecognised-access-level tests fail. Restored by hash.
- **ARM B** (drop `IOException` from the rule): rc=1, 7 / 3 failures. The transport-shapes test fails, and so do both real-SDK closed-port tests (`get/put from an unreachable store …`). This is the load-bearing proof: the SDK's genuine refused-connection failure carries an `IOException` in its chain. Restored by hash.
- **Clean after:** 7/0.

**For the human check:** the timeout shapes are covered by constructed exceptions, not by a live timeout:
- the Netty read/response `TimeoutException`;
- the per-try timeout;
- Reactor's `IllegalStateException("Timeout on blocking read", TimeoutException)`.

Only the refused connection was driven through the real SDK. A slow-store (timeout) run against Azurite would confirm that shape end to end.

## Verification summary

- **Unit suite:** `./gradlew :core-java:cleanTest :core-java:test` ran after all Java changes: BUILD SUCCESSFUL, **1304 tests, 0 failures, 0 errors, 1 skipped**, across 166 fresh result XMLs in `core-java/build-local/test-results/test` (dir mtime 2026-09-29T18:32:52 local).
- **Integration (Testcontainers), only the touched storage/GDPR/image classes:** `./gradlew :core-java:integrationTest --tests 'uk.jtoye.core.storage.*' --tests 'uk.jtoye.core.product.ProductImage*' --tests 'uk.jtoye.core.gdpr.*IntegrationTest' --tests 'uk.jtoye.core.shop.ShopImageCrossTenantIntegrationTest' --tests 'uk.jtoye.core.media.MediaPipelineAzuriteIntegrationTest'`. Result: BUILD SUCCESSFUL, **12 classes, 77 tests, 0 failures, 0 errors** (timestamps 2026-09-29T17:33-17:34Z). By class:
  - AzuriteStorage 10;
  - StorageDeleteTenantGuard 3;
  - StorageStartupValidator 10;
  - ProductImageDelete 3;
  - ProductImageCrossTenantBlobDelete 3;
  - GdprErasure 4;
  - DsarFanout 11;
  - DsarIntake 13;
  - DsarVerification 8;
  - DsarSubjectAndGlobalRateLimit 2;
  - ShopImageCrossTenant 7;
  - MediaPipelineAzurite 3.
- **docs/metrics.json CHANGED** (commit `8a2d778e`): `java_test_methods` went from 1965 to 1972 and `total_logical_invocations` from 4130 to 4137. The +7 new `@Test` methods are StorageServiceTest +1, GdprServiceTest +1 and AzureBlobObjectStoreTest +5.
  - `docs-freshness.sh` showed its fail direction (rc=1 before `--write`) and passes after.
  - `check-doc-metrics.sh` showed its fail direction (rc=1, 7 FAIL lines across README.md, CLAUDE.md and AGENTS.md) and passes after the prose update: `PASS: all 37 prose metric claim(s) across 3 doc(s)`.
- **Final sweep on HEAD `8a2d778e`**, all rc=0:
  - `check-render-invariants.sh`
  - `render-golden.sh`
  - `check-env-contract.sh`
  - `check-no-object-store-residue.sh`
  - `check-gate-enforcement.sh`
  - `check-doc-citations.sh`
  - `check-doc-metrics.sh`
  - `docs-freshness.sh`
  - `check-doc-versions.sh`
  - `check-no-measured-placeholders.sh`
  - actionlint (ci-cd.yaml, e2e-nightly.yml)

  The one exception is `check-image-supply-chain.sh`: rc=2, VOID by the pyshim refusal.

## Residuals (recorded, not filed)

- **INV-11 covers k8s renders only.**
  - docker compose and the hybrid runtime could still set the override. The comments say "every k8s target", no more.
  - A `SPRING_CONFIG_LOCATION`/`SPRING_CONFIG_IMPORT` pointing at a mounted file that sets the switch is not banned.
- **WR-03 coverage gaps.**
  - `pg-backup` is not in `base-image-freshness.yml`'s scheduled scan of published `:latest` images.
  - The CronJob tag stays mutable, and each main build re-pushes `15-blob`; deploys pin it by tag, not by sha.
- **Commit atomicity.** WR-01 and the test-count bookkeeping each needed a follow-up commit (`71b206bf`, `8a2d778e`) because a gate caught the side effect after the finding's own commit. Both are in scope and named for their finding.

---

_Fixed: 2026-09-29T17:35:41Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
