---
phase: 36-azure-blob-storage-throughout
plan: 12
subsystem: infra
tags: [azurite, azure-blob, runtime-parity, nightly, gates, media-pipeline, stored-xss]

requires:
  - phase: 36-azure-blob-storage-throughout
    provides: "36-03 CSP on the Azurite origin; 36-06 Blob client + startup probe; 36-07 pipeline on real Azurite; 36-08 backup image; 36-11 dev reseed (no old-origin value left)"
provides:
  - "scripts/check-media-urls-resolve.sh: every stored image URL HEADs 200 anonymously on the delivered object store (exit 0/1/2), wired into e2e-nightly"
  - "scripts/check-media-content-types.sh re-targeted to Azurite's public container via the host az CLI (connection string via env only), wired into e2e-nightly; its gate-enforcement.conf exemption removed"
  - "Live evidence that the rebuilt runtime is the branch and that seed images, a real vendor upload and stored content types are correct on Azurite, with every gate shown failing and passing"
affects: [36-13, 36-17, 36-18, phase-29]

actuals:
  tokens: 23529        # chars/4 over git diff a7a54ba4..HEAD before this SUMMARY (94116 chars)
  tasks: 3
  commits: 5           # MEASURED: git rev-list --count a7a54ba4..8f576eb5 (before this SUMMARY commit)
plan_head_before: a7a54ba4b4e1b20d0b04d1e93f5935892424c0c6

tech-stack:
  added: []
  patterns:
    - "Runtime gate with a VOID arm: an empty checked set, a stopped container or missing tooling exits 2 and is never reported as clean"
    - "Enumerate stored URLs as the RLS-exempt Postgres superuser so a read-only gate cannot be blinded by RLS"
    - "A gate that is wired into a workflow carries NO gate-enforcement exemption: with one, deleting the workflow step leaves check-gate-enforcement green"
    - "Emulator credentials reach az only through AZURE_STORAGE_CONNECTION_STRING, never argv"

key-files:
  created:
    - scripts/check-media-urls-resolve.sh
    - .planning/phases/36-azure-blob-storage-throughout/evidence/36-12-live-proof.txt
  modified:
    - scripts/check-media-content-types.sh
    - scripts/gates/gate-enforcement.conf
    - .github/workflows/e2e-nightly.yml
    - HANDOFF.md

key-decisions:
  - "The content-type gate's gate-enforcement.conf entry is REMOVED, not reworded: once the gate is wired into the nightly, the exemption would let the step be deleted with check-gate-enforcement still green (counter-arm recorded)"
  - "Seed-blob restore is done by docker compose up -d --force-recreate --no-deps core-java, never docker restart (restart re-bound the 9090-9091 range to :9091). Force-recreate can also land on :9091; assert docker port and recreate again"
  - "The nightly runs the URL gate straight after the health wait: root /actuator/health stays DOWN/OUT_OF_SERVICE until DemoDataSeeder has returned (measured trace), so UP implies the seed blobs exist"
  - "The two az write arms were run only after the owner sanctioned exactly three writes against local Azurite (checkpoint, blocking-human, answered 'A: sanction' 2026-09-29)"

patterns-established:
  - "Before any az write: prove the target is the emulator with a read-only az --debug call (only 127.0.0.1:10000 contacted), no AZURE_* env and no [storage] defaults"
  - "Restore verified by content: the served blob's sha256 equals the classpath image AND the copy inside the running jar"

requirements-completed: [BLOB-05, BLOB-09]  # copied verbatim from the plan; 36-12 CONTRIBUTES to both, closes NEITHER (requirements.ready-ids: 0/2; BLOB-05 waits on 36-13, BLOB-09 on 36-10/15/16/17)

coverage:
  - id: D1
    description: "Servable-URL gate: 23/23 stored image URLs answer 200 anonymously on Azurite; retired-origin, bad shape, 404 and deleted-blob arms exit 1; empty scope and stack-down exit 2"
    requirement: BLOB-05
    verification:
      - kind: other
        ref: "bash scripts/check-media-urls-resolve.sh (evidence/36-12-live-proof.txt §(5), URL gate arms, Sanctioned write arms)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Both gates wired into e2e-nightly; check-gate-enforcement reds when either step is removed"
    requirement: BLOB-09
    verification:
      - kind: other
        ref: "bash scripts/check-gate-enforcement.sh (arms GE and content-types step removal, evidence file)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Delivered runtime is the branch: all built images rebuilt, check-runtime-freshness PASS 4/4 with 0 unverified (also after the core-java recreates), running jar application.yml byte-identical to HEAD (blob=1, s3=0)"
    verification:
      - kind: other
        ref: "bash scripts/check-runtime-freshness.sh; docker exec <core-java> unzip -p /app/app.jar BOOT-INF/classes/application.yml"
        status: pass
    human_judgment: false
  - id: D4
    description: "core-java boots on Azurite with the probe on: jtoye-images=blob, jtoye-quarantine=off; seeder uploads the 21 demo images and restores a deleted one byte-identically"
    requirement: BLOB-05
    verification:
      - kind: other
        ref: "core-java boot log + az storage container show-permission + sha256 compare (evidence file §(1), §(3), delete-blob arm)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Real vendor upload round trip on the running stack: 202 -> ACTIVE, derivative and thumbnail image/webp with the immutable cache header, quarantine object private (403) and then gone"
    verification:
      - kind: other
        ref: "evidence/36-12-live-proof.txt §Upload round trip (script /tmp/claude-3612/upload-roundtrip.sh, not committed)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Content-type gate re-targeted to Azurite's public container: text/html blob -> exit 1 naming it; empty/missing container, wrong key, no az, no Azurite -> exit 2; clean PASS 0 of 23"
    requirement: BLOB-09
    verification:
      - kind: other
        ref: "bash scripts/check-media-content-types.sh (evidence file Task 3 section + text/html arm)"
        status: pass
    human_judgment: false

duration: "~50 min of execution between the first and last task commit (07:17Z-08:00Z), plus the checkpoint wait; the first executor's start time was not recorded"
completed: 2026-09-29
status: complete
---

# Phase 36 Plan 12: Delivered-runtime proof on Azurite Summary

**Two new runtime gates, both nightly-wired and both shown failing: every stored image URL must answer 200 anonymously on Azurite, and the public container must hold only image content types. The rebuilt stack is proven to match the branch, the seeder restores a deleted seed blob byte-identically, and a real vendor upload goes quarantine -> worker -> WebP with the quarantine kept private throughout.**

## Performance

- **Duration:** ~50 min of execution between the first and last task commit, plus the owner-checkpoint wait (the first executor's start time was not recorded)
- **Started:** at or before 2026-09-29T07:17Z (first task commit 36e32d6b at 07:17:13Z)
- **Completed:** 2026-09-29T08:02Z
- **Tasks:** 3 of 3
- **Files modified:** 6 (plus this SUMMARY)

## Accomplishments

- **`scripts/check-media-urls-resolve.sh`** (295 lines, exit 0/1/2). It enumerates products.image_url, additional_image_urls, shop logo/banner, review photos, and every ACTIVE media_asset derivative plus its `_thumb`, reading as the RLS-exempt superuser. It HEADs each URL anonymously and requires 200. A retired `localhost:9000` value is a FAIL. An empty checked set, a stopped postgres/azurite container, or missing tooling is VOID. Live result: **23/23 PASS** (21 seed images, plus 1 upload x {derivative, thumbnail}).
- **`scripts/check-media-content-types.sh` re-targeted.** It lists only `jtoye-images` with the host az CLI. The credential reaches az only through `AZURE_STORAGE_CONNECTION_STRING` and never appears on argv. A-1/A-2/A-3 are unchanged. The quarantine container is out of scope by design, because it is private. Assumption A8 is proven: a signed list returns 200, a wrong key 403, and an anonymous list 403.
- **Both gates are wired into `e2e-nightly.yml`.** The URL gate runs right after the health wait and the content-type gate right after it. The stale exemption is gone from `gate-enforcement.conf`.
- **Runtime parity.** All four built images were rebuilt with `up -d --build` on the nightly's SERVICES list. `check-runtime-freshness` is PASS 4/4 with 0 unverified, and still PASS after the core-java recreates at the end. The application.yml read out of the running jar is byte-identical to HEAD (sha b8c9cd03…; blob=1, s3=0; origin/main's copy gives blob=0, s3=1). `check-branch-behind-base` shows 0 behind.
- **Boot on Azurite.** The probe logs `jtoye-images (blob)` and `jtoye-quarantine (private)`, the seeder uploads all 21 demo images, and there are 0 ERROR lines. Root health stays DOWN/OUT_OF_SERVICE until the seeder has returned (measured trace).
- **Upload round trip.** The real vendor gets a 202 and the asset is ACTIVE within 5 s. The derivative (1200x900) and the thumbnail (400x300) both return 200 with `image/webp` and `public, max-age=31536000, immutable`. The quarantine object returns 403 while it exists and is absent from an az listing afterwards.
- **Redis:** 0 residue keys hold the retired origin, and **no flush was needed**. A planted control key was detected and then deleted.

## Task Commits

1. **Task 1: Servable-URL gate, wired into the nightly**: `36e32d6b` (feat)
2. **Task 2: Rebuild, boot on Azurite, parity, resolution, real upload**: `074b2a34` (test). The delete-blob arm is in `8f576eb5` (test).
3. **Task 3: Content-type gate re-targeted to Azurite and wired into the nightly**: `aa093e4a` (feat) and `d685ad14` (test, arms). The text/html arm is in `8f576eb5` (test).

**Plan metadata:** recorded in the docs commit that follows this SUMMARY.

## Both-direction evidence (all in `evidence/36-12-live-proof.txt`)

| Check | Fail direction (real output) | Pass direction |
|---|---|---|
| URL gate U-1 (deleted blob) | sanctioned `az storage blob delete` of seed `dodo.jpg`; anonymous GET 404; gate **rc=1** `HTTP 404 products.image_url id=fda34f79… …/products/seed/dodo.jpg` | core-java force-recreated; this boot logged 1x `Uploaded seed image … dodo.jpg` and 20x already present; served sha256 `76e0294e…` = classpath = running-jar copy, `cmp` rc=0; gate **rc=0, 23/23** |
| URL gate U-2 (retired origin) | planted `shops.banner_url` on the unpublished tenant-B probe shop, **rc=1** `unrewritten old-origin URL` | row restored, whole-row md5 equal; rc=0 |
| URL gate shape / 404 | `banner.jpg` gives **rc=1** `unrecognised URL shape`; `<public>/nope/…` gives **rc=1** `HTTP 404` | an external URL is reported and not fetched, rc=0 |
| URL gate U-3 / stack down | random `--only-tenant` gives **rc=2**; postgres exited gives **rc=2** | tenant 1 gives rc=0, 21 |
| Content-type A-1 (stored XSS) | sanctioned upload of `36-12-arm/xss.html` as `text/html`; anonymous GET serves `content-type: text/html`; gate **rc=1** `text/html 36-12-arm/xss.html`, 1 of 24 | sanctioned delete; full listing has 23 blobs, 0 under `36-12-arm/`, positive control dodo.jpg = 1; GET 404; gate **rc=0, 0 of 23** |
| Content-type VOIDs | empty container, missing container, wrong key, az absent, Azurite absent: all **rc=2** | clean rc=0 |
| gate-enforcement | nightly step removed gives **rc=1** naming the gate; the same removal with the old exemption stays rc=0 (why the entry went) | rc=0 |
| handoff-contract | EXPECT 45 changed to 44 in place gives **rc=1** | rc=0 |
| runtime freshness | edge-go stopped gives **rc=2** VOID | PASS 4/4, 0 unverified (start and end) |
| branch-behind-base | `--base` = a ref with 1 commit not on HEAD gives **rc=1** | rc=0, 0 behind |
| jar content | origin/main's application.yml gives blob=0 s3=1; empty file gives 0/0 | running jar blob=1 s3=0, sha = HEAD |
| argv rule | control line gives 1 | script gives 0 |
| residue grep | pre-36-12 versions give 18 hits | rc=1, 0 lines |

Before any write, the target was proven to be the emulator. A read-only `az --debug` listing contacted only `127.0.0.1:10000`. No AZURE_* variables were set and `~/.azure/config` has no `[storage]` defaults. No `--account-name` or `--subscription` was passed. Exactly three az writes were made, each carrying `# [az-ok] local Azurite only, 36-12 arm`, and all returned rc=0.

## Files Created/Modified

- `scripts/check-media-urls-resolve.sh`: the servable-URL gate (exit 0/1/2; `--only-tenant`, `--public-url`)
- `scripts/check-media-content-types.sh`: re-targeted to Azurite's public container through the host az CLI
- `scripts/gates/gate-enforcement.conf`: the content-types exemption removed
- `.github/workflows/e2e-nightly.yml`: two blocking gate steps after the health wait
- `HANDOFF.md`: gate-count claims 44 -> 45
- `.planning/phases/36-azure-blob-storage-throughout/evidence/36-12-live-proof.txt`: the live evidence (555 lines)

## Decisions Made

See `key-decisions` in the frontmatter. The main one: a gate that is wired into a workflow must not also carry a gate-enforcement exemption, because the exemption silently survives deletion of the workflow step. This was measured, not argued.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] core-java has no `container_name`**
- **Found during:** Task 2
- **Issue:** The plan's literal `docker exec jtoye-core-java` refers to a container that does not exist (`docker inspect` rc=1). Compose removed `container_name` from core-java for `--scale` and publishes the range `9090-9091:9090`. The running container is `jtoye_oaas_2026-core-java-1`.
- **Fix:** Resolved the container with `docker compose -f docker-compose.full-stack.yml ps -q core-java` throughout. The URL gate reads STORAGE_PUBLIC_URL from the running core-java container resolved the same way.
- **Verification:** The jar read returned rc=0 (45781 bytes) on both the original and the recreated container.
- **Committed in:** 074b2a34, 36e32d6b

**2. [Rule 3 - Blocking] `docker restart` moves core-java to host :9091**
- **Found during:** Task 2 (readiness trace), and again during the delete-blob restore
- **Issue:** Restarting re-allocates the published range. `docker restart` bound 0.0.0.0:9091. In this continuation the first `up -d --force-recreate --no-deps core-java` also bound :9091, because :9090 was still held by the old proxy during the swap (`ss` showed it free afterwards).
- **Fix:** Used force-recreate, never restart. After each recreate, asserted `docker port`, and recreated again when it showed :9091. The second recreate bound :9090. The seeder attribution is clean: the final container logged the single `Uploaded seed image … dodo.jpg`, so the short-lived :9091 instance had not uploaded it.
- **Verification:** `docker port` shows `9090/tcp -> 0.0.0.0:9090`, and freshness PASS with the same image id `356bca98…` before and after.
- **Committed in:** 8f576eb5 (evidence)

**3. [Rule 2 - Missing critical] gate-enforcement exemption removed rather than reworded**
- **Found during:** Task 3
- **Issue:** The plan left open whether to remove or reword the entry. Measured: with the exemption present, removing the nightly step keeps `check-gate-enforcement` at rc=0, so the gate could lose its only runner silently.
- **Fix:** Removed the entry.
- **Verification:** Step removed without the exemption gives rc=1 naming the gate. Step removed with the exemption restored gives rc=0 (counter-arm). Closing clean is rc=0.
- **Committed in:** aa093e4a

**4. [Rule 1 - Instrument] Task 3 "empty scratch container" arm run on a real empty container**
- **Found during:** Task 3
- **Issue:** Creating a scratch container is an az write, which the guard refused before the sanction, and the sanction did not cover it.
- **Fix:** Pointed `MEDIA_CONTAINER` at `jtoye-quarantine`, which exists and is genuinely empty, and got rc=2. Added a missing-container arm (`jtoye-no-such-container`, `ContainerNotFound`, rc=2). Together they cover "empty" and "not there". No container was created.
- **Committed in:** d685ad14 (evidence)

**5. [Rule 1 - Instrument] Two first-attempt measurements were invalid, caught, and re-run**
- The handoff-contract arm run on a `/tmp` copy returned rc=2, because the H-3 VOID masked H-1. It was re-run in place (EXPECT 45 changed to 44), giving rc=1 and a restored rc=0.
- The first Redis residue read took the password from redis's `/proc/1/cmdline`, which redis rewrites. It failed loudly (WRONGPASS, keys=0) and was not counted. It was re-run with `REDISCLI_AUTH` passed to `docker exec` by name.
- **Committed in:** 074b2a34, d685ad14 (evidence)

**6. [Note] Seed-blob restore mechanism**
- The plan says to restore by "restarting core-java". Force-recreate was used instead, for the port reason in item 2. Same seeder path, same putIfAbsent semantics.

---

**Total deviations:** 5 auto-fixed (2 blocking, 1 missing-critical, 2 instrument corrections) plus 1 mechanism note.
**Impact on plan:** None of these weaken a criterion. Items 3 and 4 make checks stricter, and 1, 2 and 6 are environment facts. No scope creep.

## Authorization Gate (checkpoint, blocking-human)

The machine's az-mutation PreToolUse guard refused `az storage blob delete` and `az storage blob upload`, even against the local emulator (jtoye-cloud READ-ONLY ruling). Per "a blocked command is the answer", no other tool was used. The first executor returned a `checkpoint:decision` (blocking-human). On 2026-09-29 the owner answered **"A: sanction"** for exactly three writes against local Azurite, each carrying `[az-ok]`. This continuation ran those three writes and nothing else.

## Issues Encountered

- **Inherited red, not in scope:** `scripts/docs-freshness.sh` exits rc=1 on test-count drift in `docs/metrics.json` (total 4129 on the tree). 36-17 owns it, and 36-12 added no tests. `k8s/scripts/check-env-contract.sh` is PASS rc=0. The prompt's `scripts/check-env-contract.sh` path does not exist; the gate lives under `k8s/scripts/`.
- **Dev-state change left in place:** the upload round trip left one real ACTIVE media_asset, `a5da9e44-cc37-4013-9771-d46660918816`, on product `d3325cbb…` (Jollof Rice), `is_primary=false`. That is why the URL gate counts 23 (21 seeds plus derivative and thumbnail). It is valid pipeline output and it is not removed.

## Known Stubs

None.

## User Setup Required

None. No external service configuration is required.

## Next Phase Readiness

- **The stack is LEFT RUNNING for 36-13**, rebuilt from this branch, with freshness PASS and 0 unverified at 08:00Z. The running state:

  | service | container | state | image | host ports |
  |---|---|---|---|---|
  | azurite | jtoye-azurite | healthy | 830430c1da1a | 127.0.0.1:10000 |
  | core-java | jtoye_oaas_2026-core-java-1 | healthy | 356bca98c4b9 | 0.0.0.0:9090 |
  | edge-go | jtoye-edge-go | healthy | cd432b1549a8 | 0.0.0.0:8089 |
  | frontend | jtoye-frontend | healthy | e9e3eb4af373 | 0.0.0.0:3000 |
  | keycloak | jtoye-keycloak | healthy | f8ade94c1d0a | 127.0.0.1:8085 |
  | mailhog | jtoye-mailhog | healthy | 8d76a3d4ffa3 | 127.0.0.1:1025, 8025 |
  | mcp-server | jtoye-mcp-server | healthy | 5faed32b5dac | 0.0.0.0:9100 |
  | postgres | jtoye-postgres | healthy | f7d23353e1b1 | 127.0.0.1:5433 |
  | rabbitmq | jtoye-rabbitmq | healthy | 44bf7eb50fe1 | 127.0.0.1:5672, 15672, 61613 |
  | redis | jtoye-redis | healthy | 858f009f9709 | 127.0.0.1:6379 |

  No retired object-store service is running. All named volumes were created before this session; none was deleted or recreated.
- **BLOB-05** still needs 36-13's browser proof (`naturalWidth > 0` for an image served from the storage origin).
- **BLOB-09** still needs 36-10/15/16/17.
- **For whoever closes BLOB-02:** this plan recorded the real-runtime boot with the probe passing (`jtoye-images (blob)`, `jtoye-quarantine (private)`, anonymous LIST 403, anonymous GET 200). That is evidence for 36-06 D6 on the compose runtime.
- **Nightly:** both new steps have been shown to fail and pass locally, but neither has run on a hosted runner yet (36-18).

## Self-Check: PASSED

- FOUND: scripts/check-media-urls-resolve.sh (295 lines, >= 100), scripts/check-media-content-types.sh (contains jtoye-images), evidence/36-12-live-proof.txt
- FOUND commits: 36e32d6b, 074b2a34, aa093e4a, d685ad14, 8f576eb5 (`git rev-list --count a7a54ba4..HEAD` = 5 before this SUMMARY)
- Acceptance criteria re-run on the final tree and the recreated container: nightly refs 1 + 1; syntax rc=0; running jar blob=1 s3=0 (control 0/1); residue grep rc=1 (control 18); argv awk 0 (control 1); compose ps shows azurite and core-java running with no retired service; gate-enforcement, handoff-contract and env-contract rc=0; freshness PASS 0 unverified; branch-behind-base rc=0

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-29*
