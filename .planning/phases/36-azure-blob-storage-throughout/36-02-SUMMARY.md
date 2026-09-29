---
phase: 36-azure-blob-storage-throughout
plan: 02
subsystem: infra
tags: [azurite, docker-compose, dependency-horizons, e2e-nightly, env-contract, "#683", "#441", "#270"]
status: complete

requires:
  - phase: 36-azure-blob-storage-throughout
    provides: "36-01: storage.blob.* bound from STORAGE_* env, the emulator default connection string, the digest-pinned Azurite reference"
provides:
  - "compose service azurite (jtoye-azurite) in docker-compose.full-stack.yml and infra/docker-compose.yml, digest-pinned, loopback 10000"
  - "core-java compose env STORAGE_AUTH_MODE / STORAGE_CONNECTION_STRING / STORAGE_PUBLIC_URL / STORAGE_CREATE_CONTAINERS and depends_on azurite service_healthy"
  - "horizons row id: azurite with the exact pin at both compose sites"
  - "nightly SERVICES names azurite; the retired object-store services are gone from every local runtime"
  - "hybrid runtime (start-dev.sh + application-local.yml) allowed to create its containers (D-08)"
  - "env contract with no object-store credential (verify-env --list-required 20 -> 18)"
affects: [36-06, 36-09, 36-10, 36-15, 36-16, 36-17, 36-18]

actuals:
  tokens: 9260         # chars/4 over git diff f39b3eb4..HEAD (the two code commits), before this SUMMARY
  tasks: 2
  commits: 2           # MEASURED: git rev-list --count f39b3eb4..HEAD, before the SUMMARY commit
plan_head_before: f39b3eb4d18e573b6bd7b072b8597af5b1845ef2

tech-stack:
  added:
    - "mcr.microsoft.com/azure-storage/azurite:3.37.0@sha256:830430c1da1a2d537e08f3e6764dd1f5ae00cf0346bcaf625b968ec3f0971fd5 (compose, both local runtimes)"
  removed:
    - "the object-store server and its init container (compose); their credentials, S3-prefixed values and image tags (.env.example, verify-env.sh)"
  patterns:
    - "One image string, two compose files, one horizons row with a site per file: H-1 guards the full-stack line, H-5 alone guards the hybrid line (exit 2 on divergence)"
    - "A volume is protected from compose by being UNDECLARED, not by a comment"

key-files:
  created: []
  modified:
    - docker-compose.full-stack.yml
    - infra/docker-compose.yml
    - infra/dependency-horizons.yaml
    - .github/workflows/e2e-nightly.yml
    - scripts/start-dev.sh
    - scripts/stop-dev.sh
    - core-java/src/main/resources/application-local.yml
    - .env.example
    - scripts/verify-env.sh

key-decisions:
  - "Azurite healthcheck is a node one-liner: the image has node, wget and nc but NOT curl (probed at the pinned digest). Any HTTP status exits 0, a connection error exits 1 (both shown)."
  - "The hybrid file's volume is named azuritedata, matching that file's pgdata style; the full-stack file uses azurite_data as the plan names it."
  - "MINIO_MC_IMAGE_TAG left .env.example as planned although scripts/k8s-local-secrets.sh still reads it: a fresh .env now falls back to the floating tag there until 36-10. Both routes are unpullable anonymously anyway (quay.io 401). Recorded in the horizons row and the .env.example migration note."
  - "ollama and mailhog horizon site lines corrected while restructuring the same compose file (pin-not-at-site NOTEs 4 -> 1; the remaining one is go-ci-setup in ci-cd.yaml, untouched)."

patterns-established:
  - "Per-compose-file horizons sites for an image declared in a file outside H-1's discovery set"

requirements-completed: []   # plan declares [BLOB-04, BLOB-09]; neither is fully closed here (requirements.ready-ids: 0/2) — see Requirements

coverage:
  - id: D1
    description: "The canonical compose runtime runs a digest-pinned, loopback-bound Azurite, core-java depends on it being healthy, and the retired object-store services are absent from the rendered config"
    requirement: BLOB-04
    verification:
      - kind: other
        ref: "docker compose -f docker-compose.full-stack.yml config --format json + jq1/jq2/jq3 (all 0; arms: digestless tag jq1=1, override re-adding minio jq2=1, azurite removed from depends_on jq3=1)"
        status: pass
      - kind: other
        ref: "jq .services.azurite.ports[0].host_ip = 127.0.0.1 (arm JTOYE_BIND_HOST=0.0.0.0 -> 0.0.0.0)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Azurite at the pinned digest starts alone, reports healthy and answers on 127.0.0.1:10000 with a non-200 status to an unauthenticated LIST; core-java was never started"
    requirement: BLOB-04
    verification:
      - kind: manual_procedural
        ref: "docker compose up -d azurite -> healthy after 11s; curl ?comp=list -> 403 AuthorizationFailure; healthcheck cmd rc=0 on :10000, rc=1 on closed :10001"
        status: pass
    human_judgment: false
  - id: D3
    description: "Horizons manifest carries an azurite row whose pin equals the compose image string at both compose sites; the server row is gone"
    requirement: BLOB-09
    verification:
      - kind: other
        ref: "bash scripts/check-dependency-horizons.sh under conda env jtoye-ops (rc=0; arms: manifest pin without digest rc=2 with H-1 missing-row=1 naming azurite; hybrid image line diverged rc=2 naming infra/docker-compose.yml:110)"
        status: pass
    human_judgment: false
  - id: D4
    description: "The nightly's SERVICES list names azurite instead of the retired services and equals every compose service except ollama/ollama-init"
    requirement: BLOB-04
    verification:
      - kind: other
        ref: "sorted SERVICES vs sorted compose service keys minus ollama, ollama-init: identical (11 names)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The hybrid runtime has its own Azurite and the host core-java is allowed to create its containers (D-08)"
    requirement: BLOB-04
    verification:
      - kind: other
        ref: "infra/docker-compose.yml renders (dummy env) with azurite on 127.0.0.1:10000; STORAGE_CREATE_CONTAINERS=true at start-dev.sh:102 < bootRun :103; application-local.yml parses with storage.blob.create-containers=True"
        status: pass
    human_judgment: false
  - id: D6
    description: "No object-store credential in the env contract; the nightly's generated .env drops them; the old volume stays on disk"
    requirement: BLOB-09
    verification:
      - kind: other
        ref: "verify-env --list-required: rc=0 minio=0 control=1 (base: minio=2); .env copy without the retired pair: new rc=0, base rc=1 naming both; check-env-example-contract rc=0 (18 names; arm rc=1)"
        status: pass
      - kind: other
        ref: "docker volume ls lists jtoye_oaas_2026_minio_data (control name absent, rc=1)"
        status: pass
    human_judgment: false

duration: 8min
completed: 2026-09-28
---

# Phase 36 Plan 02: Azurite Replaces the Retired Object Store Summary

**Every local runtime now runs a digest-pinned Azurite (mcr.microsoft.com/azure-storage/azurite:3.37.0@sha256:8304...1fd5) on loopback 10000. The retired object-store server and its init container are gone from compose and from the nightly's SERVICES. The horizons manifest pins Azurite at both compose sites, and no object-store credential remains in `.env.example` or `verify-env.sh`. This removes #683's root cause (an unpullable image in the service list).**

## Performance

- **Duration:** about 8 min on the wall clock, from 22:03:02Z (after the context reads) to the SUMMARY.
- **Started:** 2026-09-28T22:03:02Z
- **Completed:** 2026-09-28T22:11Z
- **Tasks:** 2 of 2
- **Files modified:** 9 (0 created)

## Accomplishments

- `docker-compose.full-stack.yml`:
  - The new `azurite` service runs `azurite-blob` with the five verified flags plus `--location /data`. It is bound to `${JTOYE_BIND_HOST:-127.0.0.1}:10000` and uses the `azurite_data` volume.
  - Its healthcheck is a node one-liner, because the image has no curl.
  - core-java's env replaces the five retired S3-prefixed entries with four `STORAGE_*` entries. The connection string holds no key.
  - core-java now waits for `azurite: service_healthy`.
  - The old volume is undeclared and stays on disk. The comment explaining why does not name the product, so it passes the 36-16 residue scan.
- `infra/docker-compose.yml`: the hybrid runtime has its own Azurite (D-08) with the same image string. The file header now warns that it must never run together with the full stack (shared container names and ports 5433, 8085 and 10000).
- `infra/dependency-horizons.yaml`:
  - New `azurite` row, with the pin at `docker-compose.full-stack.yml:609` and `infra/docker-compose.yml:110`.
  - The object-store server row is deleted.
  - The client-image row keeps only its `scripts/k8s-local-secrets.sh:299` site. Its note now marks it TRANSITIONAL, to be removed by 36-10.
- `.github/workflows/e2e-nightly.yml`:
  - SERVICES names `azurite`.
  - The one-shot-container comment names only `keycloak-realm-render`.
  - The `core-java.depends_on` sentence includes azurite.
  - The REQUIRED_VARS count reads 18. The old comment said 16 while the list held 20.
- `scripts/start-dev.sh`:
  - Names Azurite in the header, the usage text and the step-1 banner.
  - Exports `STORAGE_CREATE_CONTAINERS=true` right before `bootRun`.
- `application-local.yml`: sets `storage.blob.create-containers: true`.
- `scripts/stop-dev.sh`: its volume comment now names Azurite.
- `.env.example`:
  - The retired block is replaced by one that says Azurite needs no credential and that the emulator key must never go into `.env`.
  - A commented `AZURITE_IMAGE_TAG` example shows the tag@digest form.
  - A migration note covers the retired keys.
- `scripts/verify-env.sh`: the two object-store credential names are out of REQUIRED_VARS, and the object-store default is out of the deny list. Every other check is unchanged.

## Task Commits

1. **Task 1: Azurite replaces the retired store in both compose files, the horizons manifest and the nightly.** `a9b0265a` (feat)
2. **Task 2: the hybrid runtime creates its containers (D-08), and the env contract drops the object-store credentials.** `14278342` (feat). This commit also carries the nightly's REQUIRED_VARS count, because that number depends on Task 2.

**Plan metadata:** in the SUMMARY commit that follows, plus a separate ROADMAP progress commit.

TDD: not applicable. The plan is `type: execute` and neither task carries `tdd="true"`. Both tasks are config and infra; the proof is the both-direction evidence below.

## Evidence: every acceptance criterion, both directions

`SCRATCH=/tmp/claude-36-02`. Every command ran from the repo root, and every break arm ran after its task's commit.

### A9: probe the image before designing the healthcheck

- `docker run --rm --entrypoint sh <pinned image> -c 'command -v …'` gave rc=0:
  - `node=/usr/local/bin/node` (v22.23.2)
  - `wget=/usr/bin/wget`
  - `nc=/usr/bin/nc`
  - `curl=MISSING`
- `azurite-blob --help` gave rc=0. Each flag occurs exactly once: blobHost=1, blobPort=1, location=1, disableProductStyleUrl=1, skipApiVersionCheck=1, disableTelemetry=1. Control: a made-up `--definitelyNotAFlag` occurs **0** times.
- Healthcheck command inside the running container: rc=0 against `:10000`, and **rc=1 against the closed `:10001`**. So it can fail.

### Task 1

| Criterion | Pass (real tree) | Fail direction (deliberately broken input) |
|---|---|---|
| verify block: config rc, jq1, jq2, jq3 | `rc=0`, compose.json 23342 bytes, `jq1=0 jq2=0 jq3=0` | **jq1:** `AZURITE_IMAGE_TAG=3.37.0` renders the image `…azurite:3.37.0`, giving `false` and `jq1=1`. **jq2:** an override file re-adding a `minio` service gives `false` and `jq2=1`. **jq3:** a copy of the compose file with only the two `azurite:`/`condition:` lines removed from core-java's depends_on (diff shows exactly lines 387-388) gives `false` and `jq3=1` |
| horizons gate exits 0 | `rc=0`, `missing-row=0`, `site-unresolvable=0`, `pin-not-at-site=1` (go-ci-setup, pre-existing). Baseline before the plan: `rc=0`, pin-not-at-site=4. Closing clean after the arms: `rc=0`, same counts | **Arm A** (manifest pin with the digest dropped): **rc=2**, with `FAIL: H-1 mcr.microsoft.com/azure-storage/azurite:${AZURITE_IMAGE_TAG:-3.37.0@sha256:8304…} is pinned … but has NO horizon row` (`missing-row=1`), plus H-5 VOID on both declared sites (`site-unresolvable=2`). The plan expected exit 1; see deviation 1. **Arm B** (the hybrid file's image line diverged): **rc=2**, `H-5 azurite: declared pin … NOT FOUND on any non-comment line of infra/docker-compose.yml (declared site infra/docker-compose.yml:110)`. Restores were verified by blob hash: manifest `624dfd01…` = HEAD, hybrid compose `34801a22…` = HEAD |
| core-java env keys | `STORAGE_AUTH_MODE`, `STORAGE_CONNECTION_STRING`, `STORAGE_CREATE_CONTAINERS`, `STORAGE_PUBLIC_URL`, and `S3_` count **0**. Values: `connection-string`, `UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite`, `http://localhost:10000/devstoreaccount1/jtoye-images`, `"true"` | An override adding `S3_ENDPOINT` makes the `^S3_` count **1** |
| host_ip | `127.0.0.1` | `JTOYE_BIND_HOST=0.0.0.0` prints `0.0.0.0` |
| `.volumes has azurite_data`, and the old volume survives | `true`, rc=0. `.volumes has minio_data` is `false`. `docker volume ls` still lists `jtoye_oaas_2026_minio_data` (grep -x rc=0) | The compose file at `f39b3eb4`, rendered, gives `has("azurite_data") = false`, rc=1. Volume check control: a name that does not exist gives rc=1. The old volume itself was **not** deleted to run a fail arm (D-04), so this criterion's fail direction is shown on the search only |
| Azurite probe non-200, core-java never started | `up -d azurite`: **healthy after 11s**. `curl http://127.0.0.1:10000/devstoreaccount1?comp=list` returned **403** with `<Code>AuthorizationFailure</Code>`. `docker ps --filter name=jtoye-core-java -q` was empty, and `docker ps -a` held only `jtoye-azurite` | **Not falsifiable here without breaking a plan rule.** Showing the core-java check fail would mean starting core-java, which the plan forbids. The stronger statement is recorded instead: before the probe `docker ps -a` listed no containers at all, and during it only `jtoye-azurite` |
| `git grep -n -i minio` over both compose files | prints only `rc=1` | at `f39b3eb4`: `docker-compose.full-stack.yml:36` matches, `rc=0` |
| nightly SERVICES matches compose | sorted SERVICES equals the sorted compose services minus `ollama`/`ollama-init` (11 names). `git grep -i minio` over the workflow gives rc=1 | n/a (a set equality, read from both sides) |

After the probe: `docker compose stop azurite`, then removal by the captured ID only (`26db3dac622c`) and removal of the one network the probe created (`jtoye_oaas_2026_jtoye-network`). I ran no `down -v` and no `volume rm`. The new `jtoye_oaas_2026_azurite_data` volume stays, per the leave-all-volumes ruling. `docker ps -a` is empty at the end.

### Task 2

| Criterion | Pass | Fail direction |
|---|---|---|
| verify block 1 (`--list-required`) | `rc=0 minio=0 control=1` (18 names) | `f39b3eb4`'s verify-env gives `rc=0 minio=2 control=1`. The empty-list vacuity guard: a missing script gives `rc=127 minio=0 control=0`, which fails on `control` |
| verify block 2 (`--help`) | `bash -n` passes, `rc=0`, `azurite=1` | `f39b3eb4`'s start-dev.sh gives `rc=0 azurite=0` |
| exactly 1 `STORAGE_CREATE_CONTAINERS=true`, before bootRun | `scripts/start-dev.sh:102`; the bootRun invocation is at `:103` | at `f39b3eb4`: no line, `rc=1` |
| `create-containers: true` in application-local.yml | 1 line (`:31`). PyYAML under `jtoye-ops` parses it as `{'blob': {'create-containers': True}}` | at `f39b3eb4`: `rc=1` |
| `MINIO\|S3_` over the three scripts gives rc=1; .env.example only in the K8S_LOCAL block | scripts: `rc=1`. .env.example: only `:456 K8S_LOCAL_MINIO_PORT=9000`, and the K8S_LOCAL block starts at `:445` | at `f39b3eb4`: `.env.example` 11 matches, `verify-env.sh` 4. **This criterion was blind to one file** (deviation 2): it is case-sensitive, so it cannot see `MinIO` prose in `stop-dev.sh`. Strengthened form, `git grep -i minio` over the three scripts: now `rc=1`, at `f39b3eb4` `rc=0` (stop-dev.sh 1, verify-env.sh 4) |
| `AccountKey` in .env.example gives rc=1 | `rc=1` | a planted `…AccountKey=planted` line was found at `:496` (`rc=0`). Restore verified by hash `94c29184…` before = after, then closing `rc=1` |
| `verify-env.sh` still passes on the developer's existing .env | `rc=0`, 0 FAIL lines (the retired keys are harmless) | A scratch copy of `.env` without the retired pair: the **new** script gives `rc=0` and `f39b3eb4`'s gives **`rc=1`**, naming exactly `MINIO_ROOT_USER` and `MINIO_ROOT_PASSWORD`. The base copy ran from an in-tree temp path, because a `/tmp` copy VOIDs on relative realm-template paths; that is recorded, and the temp file is removed. The new script can still fail: a copy without `REDIS_PASSWORD` gives `rc=1` naming it |

### Adjacent gates that read the changed files (all run; reds are VOID only because the stack is down)

- `check-env-example-contract.sh`: **rc=0**, `PASS: all 18 required variable(s)`. Arm: a template copy without `REDIS_PASSWORD` gives **rc=1**.
- `check-infra-exposure.sh`: part A **PASS**, listing both new `azurite 127.0.0.1 10000 -> 10000 loopback` rows (22 ports). Part B is **VOID (rc=2)** because no containers are running.
- `check-container-config-drift.sh` (under `jtoye-ops`): **rc=2 VOID**, "13 declared service(s) and NONE running". It parses the new compose file.
- `check-no-measured-placeholders.sh`: rc=0. `check-handoff-contract.sh`: rc=0.
- Tool note: the horizons gate needs PyYAML. The machine's python shim refuses bare `python3`, so the gate was run under `conda activate jtoye-ops`, the env earlier phases used for this. Without it the gate VOIDs (rc=2, "cannot parse").

## Decisions Made

- **Healthcheck:** node HTTP GET of `?comp=list`. Any status counts as healthy; a connection error counts as unhealthy. `interval 10s`, `timeout 5s`, `retries 5`.
- **Horizons protection per site:**
  - H-1 discovers only `docker-compose.full-stack.yml`, so the hybrid file's line is protected by H-5 alone.
  - The row's note says this, and arm B proves that divergence there is exit 2, not a NOTE.
  - The pin string is deliberately absent from every comment in both compose files, so H-5's file-wide search cannot be satisfied by prose.
- **Manual review date:** the row's `manual_review.expires` is `2026-12-27`, exactly 90 days after `last_checked 2026-09-28`.
- **Warnings:** start-dev.sh's "never run alongside" warnings now include port 10000, because both stacks now publish it.

## Deviations from Plan

**1. [Rule 1: criterion's expected exit code was wrong] Dropping the digest from the manifest pin exits 2, not 1**
- **Found during:** Task 1, horizons fail arm.
- **Issue:** the criterion expected exit 1. Changing the row's pin makes both of these fire:
  - H-1 (the compose string has no row): exit-1 class.
  - H-5 (the row's pin is at neither declared site): VOID class.
- The gate's documented precedence is 2 > 1 > 0 (AC-5.14 in its header), so it exits 2.
- **Outcome:** the H-1 `FAIL … has NO horizon row` line naming the azurite image is present, so the criterion's intent (a red that names Azurite) holds, with a stronger exit code. No fix is possible or needed. The observed result is recorded instead of claiming rc=1.

**2. [Rule 1: criterion could not see one file] `git grep -E 'MINIO|S3_'` is case-sensitive**
- **Found during:** Task 2, fail arm at `f39b3eb4`.
- **Issue:** the base `stop-dev.sh` held `MinIO objects` and still gave no match, so the criterion was vacuous for that file.
- **Fix:** also ran `git grep -i minio` over the same three scripts: now rc=1, base rc=0 with stop-dev.sh 1 match. Both forms are recorded.

**3. [Scope note] ollama and mailhog horizon site lines corrected**
- **Where:** Task 1, `infra/dependency-horizons.yaml`.
- These rows' declared lines (621/686/702) were already stale before the plan (baseline NOTEs), and this plan restructures the same compose file.
- They now name 636/701/717, where the pins actually sit. pin-not-at-site went from 4 to 1. The remaining NOTE (go-ci-setup, `ci-cd.yaml`) is outside this plan's files and was left alone.

**4. [Rule 2: accuracy of a comment the change falsified] The nightly header's `core-java.depends_on` sentence**
- It said postgres/keycloak/redis/rabbitmq "only". azurite is now a hard dependency, so the sentence names it.

**5. [Ordering] The nightly REQUIRED_VARS count is in the Task 2 commit, not Task 1**
- The plan lists the file under Task 1 but says to read the number after Task 2, from `--list-required`. The comment previously said 16 while the list held 20; it now says 18, the measured value.

**6. [Transitional, recorded] `.env.example` no longer carries the object-store client image tag, which `scripts/k8s-local-secrets.sh` still reads**
- A `.env` copied fresh from the template makes that bootstrap fall back to the floating tag. An existing `.env` keeps its digest pin.
- Both routes are unpullable anonymously since ~2026-09-24, which is why this phase exists.
- 36-10 removes that script's use of the image and the horizons row. The note is in the row and in the `.env.example` migration note.

**7. [Cleanup beyond the plan's `stop`] Container and network removed after the probe**
- The stopped `jtoye-azurite` container (by captured ID) and the compose network the probe created were removed, so no project container is left behind.
- No volume was touched.

**Total deviations:** 7:
- 2 Rule 1 criterion corrections
- 1 Rule 2 comment accuracy
- 4 scope, ordering and transitional notes

**Impact:** none widens scope beyond the plan's nine files. Two make a criterion's evidence honest (one exit code corrected, one blind spot closed).

## Transitional state this plan knowingly leaves (recorded, not fixed)

- **`k8s/scripts/check-env-contract.sh`:** still red (36-09 owns it, inherited from 36-01). This plan changes no k8s manifest.
- **`scripts/docs-freshness.sh`:** unchanged by this plan, which adds no tests. It stays red from 36-01's count drift (36-17 owns it).
- **`scripts/check-media-content-types.sh` and `scripts/k8s-local-secrets.sh`:** still reference the retired credential names. They are owned by the content-type retarget plan and 36-10, and are outside this plan's files.
- **Runtime-parity gates:** not applicable. No image was built and no service is left running. The nightly's actual end-to-end run belongs to 36-18.

## Issues Encountered

- `scripts/check-dependency-horizons.sh` VOIDs under the bare-python shim (the "cannot parse" message). It was run under `conda activate jtoye-ops`. This is not a repo defect, but CI must keep a PyYAML-capable python3 on PATH, which it already does.
- Running `f39b3eb4`'s `verify-env.sh` from `/tmp` VOIDed on relative realm-template paths. It was rerun from an in-tree temp copy, which was deleted afterwards.

## Known Stubs

None.

## Threat Flags

None. The only new surface is the published port 10000, which the plan's T-36-07 covers. It is loopback-bound in both files, shown by jq and by the exposure gate's part A.

## Requirements

`BLOB-04` and `BLOB-09` are **not** marked complete. `requirements.ready-ids` reports 0/2.
- **BLOB-04** also needs the nightly to bring the stack up and execute Playwright (36-18).
- **BLOB-09** also needs the repo-wide residue gate, the content-type retarget, and the docs and metrics work (36-10/12/14/15/16/17).

This plan delivers BLOB-04's compose, nightly-list and hybrid parts, and BLOB-09's horizons row and env-contract part.

## User Setup Required

None. Existing developers may delete the retired object-store keys from their `.env` (see the `.env.example` migration note). They are harmless if left, as the verify-env run above shows.

## Next Phase Readiness

- Compose now depends on Azurite being healthy. 36-06's startup probe can be switched on without a runtime that lacks a store, since the hybrid runtime has one too (D-08).
- Nothing in this plan started core-java. The first real boot against Azurite through compose, and the reseed, belong to later plans.

## Self-Check: PASSED

- All 9 modified files exist, and both code commits resolve: `a9b0265a`, `14278342`.
- The must-have markers are present:
  - `jtoye-azurite` in `docker-compose.full-stack.yml`
  - `azurite` in `infra/docker-compose.yml`
  - `id: azurite` in `infra/dependency-horizons.yaml`
  - `STORAGE_CREATE_CONTAINERS` in `scripts/start-dev.sh`

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-28*
