---
phase: 36-azure-blob-storage-throughout
plan: 04
subsystem: infra
tags: [azure-blob, azurite, go, blobctl, workload-identity, backups, gitleaks, dependabot, ci]
status: complete

requires:
  - phase: 36-azure-blob-storage-throughout
    provides: "36-01: the STORAGE_AUTH_MODE vocabulary (connection-string | workload-identity), exact-match semantics, and the emulator form UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite; 36-02: the digest-pinned Azurite 3.37.0 image"
provides:
  - "Go module github.com/jtoye/blobctl at infra/backups/blobctl (go 1.27.0, azblob v1.8.1, azidentity v1.14.1)"
  - "LoadConfig(env func(string) string) (Config, error), NewClient(Config), Config.String() redacted to mode + endpoint host"
  - "blobctl upload <file> <container> <blobname> | list <container> <prefix> | download <container> <blobname> <outfile>; exit 0 ok / 1 error / 2 usage / 3 already exists / 4 not found"
  - "env contract: STORAGE_AUTH_MODE, STORAGE_ENDPOINT (workload-identity only), STORAGE_CONNECTION_STRING (connection-string only), AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_FEDERATED_TOKEN_FILE"
  - "CI: 'Run blobctl tests (backup uploader, Phase 36)' in the test job; gofmt + go vet for the module in the lint job"
  - "Dependabot gomod entry /infra/backups/blobctl"
  - "gitleaks [[allowlists]] block matching exactly the published Azurite key"
affects: [36-08, 36-09, 36-10, 36-17, phase-29-handoff]

actuals:
  tokens: 16433        # chars/4 over git diff fe99fd78..1b225972 (14856 excluding go.sum)
  tasks: 3
  commits: 5           # MEASURED: git rev-list --count fe99fd78..HEAD before this SUMMARY commit
plan_head_before: fe99fd782379f7dac97ccacc5e0b218ac8967294

tech-stack:
  added:
    - github.com/Azure/azure-sdk-for-go/sdk/storage/azblob v1.8.1
    - github.com/Azure/azure-sdk-for-go/sdk/azidentity v1.14.1
    - github.com/Azure/azure-sdk-for-go/sdk/azcore v1.23.1 (direct, for ETagAny / streaming / to)
  patterns:
    - "Env read only through an injected func, never os.Getenv inside the logic: hermetic tests"
    - "Usage decided before configuration, so a bare invocation exits 2 in any environment"
    - "The real SDK adapter is tested through run() against an in-process fake of the Blob REST service, so SDK request headers are under test, not just dispatch"
    - "No-overwrite by construction: the store interface has neither a delete nor an overwrite method"

key-files:
  created:
    - infra/backups/blobctl/go.mod
    - infra/backups/blobctl/go.sum
    - infra/backups/blobctl/main.go
    - infra/backups/blobctl/config.go
    - infra/backups/blobctl/emulator.go
    - infra/backups/blobctl/commands.go
    - infra/backups/blobctl/config_test.go
    - infra/backups/blobctl/commands_test.go
  modified:
    - .github/workflows/ci-cd.yaml
    - .github/dependabot.yml
    - .gitleaks.toml

key-decisions:
  - "blobctl does NOT use azblob's UploadFile: on v1.8.1 its staged path (files > 256 MiB) commits the block list without forwarding AccessConditions, so If-None-Match is silently dropped (demonstrated: a 257 MiB UploadFile overwrote an existing blob with no error; the same call at 1 KiB refused). Single Put Blob up to 256 MiB, Put Block + Put Block List above it, both carrying If-None-Match: *"
  - "The store interface has no overwrite flag at all (the plan said 'upload with a no-overwrite flag'): overwrite is not expressible, which is strictly stronger"
  - "STORAGE_ENDPOINT is ignored in connection-string mode (the endpoint is derived from the emulator string, as the Java SDK does), because 36-09 maps STORAGE_ENDPOINT into pg-backup in every overlay including local; refusing it would break the local overlay. A connection string alongside workload identity IS refused (one auth path per run)"
  - "Any connection-string key other than UseDevelopmentStorage / DevelopmentStorageProxyUri is refused as emulator-only (D-02), not only the five named ones; errors name the key, never the value"
  - "download opens the outfile O_EXCL 0600 and removes it on any failure: never clobbers a local file, never leaves a half-written dump"
  - "exit 3 also covers 412 ConditionNotMet and BlobImmutableDueToPolicy (both only arise against an existing blob under If-None-Match / WORM)"

patterns-established:
  - "An allowlist regex is written only after measuring what gitleaks captures as the secret (here the full 88-char key), so an anchored regex cannot silently miss a partial capture"
  - "actionlint without shellcheck cannot see a missing fi inside run: (#758 class); extract the run blocks and bash -n them"

requirements-completed: [BLOB-06]

coverage:
  - id: D1
    description: "One STORAGE_AUTH_MODE switch: workload-identity (https account endpoint + all three AZURE_* values, no stored secret) or connection-string accepting ONLY the Azurite emulator form; every real account name, key, SAS or endpoint key refused with 'emulator-only (D-02)' without echoing the value; String() never prints a key, token contents, token path or connection string"
    requirement: BLOB-06
    verification:
      - kind: unit
        ref: "infra/backups/blobctl/config_test.go#TestLoadConfig_AuthModeSwitch,TestLoadConfig_WorkloadIdentity,TestLoadConfig_ConnectionString,TestConfig_StringRedacts,TestNewClient_BuildsOneClientPerMode"
        status: pass
    human_judgment: false
  - id: D2
    description: "upload never overwrites (If-None-Match: * on both the single-shot and the staged path, exit 3), container created private in emulator mode only, sorted list, download 0600 / exit 4, no delete code path; exercised against a real digest-pinned Azurite as well as the in-process fake"
    requirement: BLOB-06
    verification:
      - kind: unit
        ref: "infra/backups/blobctl/commands_test.go (9 top-level tests incl. TestAzureStore_EndToEndAgainstFakeBlobService, TestAzureStore_StagedUploadAlsoNeverOverwrites)"
        status: pass
      - kind: integration
        ref: "manual run of the built binary against mcr.microsoft.com/azure-storage/azurite@sha256:830430c1...1fd5 (upload 0, same-name 3, list, download cmp-identical 600, missing 4, anon GET/LIST 403, publicAccess null)"
        status: pass
    human_judgment: false
  - id: D3
    description: "blobctl tests, gofmt and vet run on every PR in ci-cd.yaml; Dependabot watches the module"
    requirement: BLOB-06
    verification:
      - kind: other
        ref: "actionlint v1.7.12 (0 findings base and new) + bash -n on the 3 extracted blobctl run: blocks (0 failures; missing-fi arm 1 failure) + dependabot.yml parse (1 entry, same shape as /edge-go)"
        status: pass
    human_judgment: true
    rationale: "The GitHub Actions run itself cannot be executed from this machine; only the workflow's syntax and shell were validated locally. The first CI run on the PR is the proof that the steps execute."
  - id: D4
    description: "gitleaks allows exactly the published emulator key and still flags any other AccountKey value"
    verification:
      - kind: other
        ref: "gitleaks v8.27.2 (CI pin, ghcr.io image) and v8.30.1: blobctl dir rc=0; block removed rc=1; random 88-char AccountKey outside repo rc=1; one-char-mutated key rc=1; gitleaks git over fe99fd78..HEAD rc=0 (allowlist-free config rc=1)"
        status: pass
    human_judgment: false

duration: 14min
completed: 2026-09-28
---

# Phase 36 Plan 04: blobctl Summary

**blobctl, a Go CLI on azblob v1.8.1 / azidentity v1.14.1 with the same one STORAGE_AUTH_MODE switch as core-java. It accepts only the Azurite emulator connection string, never overwrites (If-None-Match: * on both upload paths, working around an azblob UploadFile bug above 256 MiB), has no delete, and is wired into CI, Dependabot and a gitleaks allowlist narrowed to exactly one published key.**

## Performance

- **Duration:** ~14 min
- **Started:** 2026-09-28T22:22:31Z
- **Completed:** 2026-09-28T22:36Z
- **Tasks:** 3 (two TDD RED/GREEN pairs + one wiring task)
- **Files:** 11 (8 created, 3 modified), +1547 lines

## Accomplishments

- A tested Go module at `infra/backups/blobctl`, with 14 top-level tests and 73 PASS lines including subtests. Every test was first seen failing on its assertions (RED_EVIDENCE_OK twice).
- The emulator-only rule (D-02) is enforced in the parser. Any key except `UseDevelopmentStorage` / `DevelopmentStorageProxyUri` is refused, so blobctl cannot reach a real account with a stored key in any runtime.
- An azblob v1.8.1 defect was found and routed around. `UploadFile` drops the `If-None-Match` condition on its staged path (files over 256 MiB), which would have silently broken the no-overwrite guarantee for any large production dump.
- The CI test and lint steps, the Dependabot entry, and a gitleaks allowlist are proven both ways on the CI-pinned 8.27.2.

## Task Commits

1. **Task 1 RED:** `4ea86cb5` test(36-04): add failing auth-mode and emulator-only tests for blobctl
2. **Task 1 GREEN:** `fbc43fef` feat(36-04): implement blobctl's one auth switch and emulator-only parser
3. **Task 2 RED:** `725c72a0` test(36-04): add failing upload/list/download tests for blobctl
4. **Task 2 GREEN:** `7d2e1d19` feat(36-04): implement blobctl upload/list/download that never overwrites or deletes
5. **Task 3:** `1b225972` chore(36-04): run blobctl in CI, watch it with Dependabot, allow only the emulator key

No REFACTOR commits were needed.

## Files Created/Modified

- `infra/backups/blobctl/config.go`: `LoadConfig`, emulator parsing, WI endpoint regex, `NewClient`, redacted `String()`/`GoString()`.
- `infra/backups/blobctl/emulator.go`: the one published `devstoreaccount1` key constant, with the Azurite README citation. It is byte-verified against the constant in the Azure Java SDK `azure-storage-common` 12.34.1 jar; a one-char-changed control is absent.
- `infra/backups/blobctl/commands.go`: `run()` dispatch, exit codes, the `store` interface, and the azblob adapter (single-shot vs staged upload, both with `If-None-Match: *`).
- `infra/backups/blobctl/main.go`: wires `run()` to the process.
- `infra/backups/blobctl/config_test.go` (5 tests) and `commands_test.go` (9 tests, including the in-process Blob REST fake).
- `infra/backups/blobctl/go.mod`, `go.sum`: go 1.27.0, `go mod verify` clean.
- `.github/workflows/ci-cd.yaml`: the blobctl test step (test job) plus gofmt and vet steps (lint job).
- `.github/dependabot.yml`: gomod `/infra/backups/blobctl`.
- `.gitleaks.toml`: one anchored content allowlist.

## Acceptance evidence, both directions

### Task 1

| Criterion | PASS on the real tree | FAIL on a deliberate break |
|---|---|---|
| RED recorded first | stub `LoadConfig`/`NewClient` return "not implemented". `go test -json` shows 5/5 top-level tests failing on assertions (e.g. `config_test.go:161: unexpected error: not implemented`). `gsd_run check tdd-red-evidence` gives **RED_EVIDENCE_OK**, target `TestLoadConfig_ConnectionString`, exit 1. This was a compile-clean RED, not a compile error; see deviation 4 | n/a (this IS the fail direction) |
| verify command | `go mod verify` "all modules verified", `go vet` 0, `gofmt -l` empty, `go test -count=1 -v` **rc=0**, 52 PASS lines, 0 FAIL, no "[no test files]" | see break arms below |
| `awk '/^func Test/…' config_test.go` ≥ 4 | **5** | n/a (count of the committed file) |
| Break arm: parser accepts an AccountKey pair | restored from HEAD, sha256 `18095acb…` identical before and after, suite green | `TestLoadConfig_ConnectionString` **FAIL**: `refused_emulator-only:_AccountKey_alone_next_to_the_emulator_flag` and `…key_case_does_not_bypass_the_refusal` ("expected an error, got nil"), rc=1 |
| Extra arm (T-36-13): `String()` prints the token path | restored, same hash, green | `TestConfig_StringRedacts` **FAIL**: `leaks "/tmp/TestConfig_StringRedacts…/azure-identity-token"` |
| `git grep -c 'emulatorAccountKey ='` reports only emulator.go | **VACUOUS AS WRITTEN**: it prints nothing on the real tree (rc=1), because gofmt aligns the const block to two spaces. Replaced (deviation 2) by `git grep -c -E 'emulatorAccountKey[[:space:]]+='` scoped to the module: `emulator.go:1` only. Literal key across the repo (`rg -uu -F`): only `infra/backups/blobctl/emulator.go`. That search exited rc=2 because `.gradle-docker/daemon/8.10.2` is unreadable (Docker-owned Gradle daemon dir), so that one directory is **unsearched**. Repo-wide, the replacement grep also hits `36-04-PLAN.md`, which quotes the token in its own criterion | staged duplicate `zz_dup.go`: grep reports **2** declaring files, and `rg -uu -F` lists both. Removed; closing grep back to `emulator.go:1` |

### Task 2

| Criterion | PASS on the real tree | FAIL on a deliberate break |
|---|---|---|
| RED recorded first | 8 new top-level tests fail on assertions, 5 existing pass. **RED_EVIDENCE_OK**, target `TestAzureStore_EndToEndAgainstFakeBlobService` | n/a |
| verify command | `go vet` 0, gofmt empty, `go test -count=1 -v` **rc=0**, 14 top-level PASS, 73 PASS lines, 0 FAIL | see arms |
| no delete code path: `git grep -n -i -E 'Delete(Blob\|Container)?\(\|\.Delete\('` prints only rc=1 | **UNSATISFIABLE AS WRITTEN on a correct tree**: `-i` matches Go's builtin map `delete(env, missing)` at `config_test.go:118` (rc=0). Replaced (deviation 3) by the case-sensitive `git grep -n -E 'Delete[A-Za-z]*\(\|\.Delete\('` over the module: **rc=1**. Structurally, the `store` interface has no delete method, and the Blob fake fails the test on any DELETE request (none issued) | planted `s.c.DeleteBlob(…)`, `s.c.DeleteContainer(…)`, `x.Delete(…)`, `x.DeleteIfExists(…)` (each staged): **rc=0, 1 line** each. Closing clean rc=1 |
| Break arm: drop IfNoneMatch | restored from HEAD, sha256 `f1c38794…` identical, suite green | **arm A** (single-shot Put Blob without the condition): `TestAzureStore_EndToEndAgainstFakeBlobService` FAIL, `re-upload exit = 0, want 3` and `stored bytes = "PGDMP replacement": the existing blob was overwritten`. **Arm B** (CommitBlockList without the condition): `TestAzureStore_StagedUploadAlsoNeverOverwrites` FAIL, `staged re-upload exit = 0, want 3` and `…the staged commit overwrote the existing blob` |
| `go build … && blobctl; echo rc` prints usage, rc=2 | usage on stderr, stdout empty, **rc=2**. `env -i blobctl upload` also gives rc=2 | control: a well-formed `blobctl list c p` with no env gives **rc=1** (`STORAGE_AUTH_MODE must be …`), so 2 specifically means usage |

**Real path against Azurite.** This was extra to the plan's criteria, run under proof standard 5. The image was the digest-pinned `azurite@sha256:830430c1…1fd5`, already local, so nothing was pulled. The container `6b9a4be46bae` was started with `-p 127.0.0.1:10000:10000` and removed by that ID afterwards; `docker ps -a` was empty and nothing was listening on 10000. Results with `STORAGE_CONNECTION_STRING=UseDevelopmentStorage=true`:
- upload #1: rc=0
- same name again: rc=3 ("already exists")
- a new name into the existing container: rc=0
- `list jtoye-db-backups drill/`: sorted names, rc=0; a prefix matching nothing: empty output, rc=0
- download: rc=0, `cmp` identical to the ORIGINAL bytes (upload #2 did not overwrite), mode 600
- missing blob: rc=4 with no file left; missing container: rc=4
- anonymous GET of the blob and anonymous LIST of the container: both 403
- `az storage container show` (a read) reports `publicAccess: null`; the control on a nonexistent container gives rc=3 `ContainerNotFound`

The positive anonymous direction (a `blob`-level container serves anonymous GET 200 on this Azurite) is not re-proven here: the az guard blocks `az storage container create`, and I did not bypass it. It was proven in 36-01 (AzuriteStorageIntegrationTest, #626 both directions).

**The SDK trap, demonstrated.** A throwaway uncommitted test called azblob `UploadFile` with `AccessConditions{IfNoneMatch: ETagAny}` against the fake, which already held a blob of that name:
- 1 KiB file: `err=true`, not overwritten, commits=0
- 257 MiB sparse file: `err=false`, stored length 269484032, **overwritten=true**, commits=1

### Task 3

| Criterion | PASS | FAIL |
|---|---|---|
| gitleaks arms, CI-pinned **v8.27.2** (`ghcr.io/gitleaks/gitleaks:v8.27.2`; the version in `.github/workflows/gitleaks.yml` `GITLEAKS_VERSION`) | **(1)** `dir infra/backups/blobctl` with the block: **rc=0** "no leaks found" | **(2)** block removed (17 lines, regexTarget back to 1): **rc=1** "leaks found: 1" on emulator.go:23 `generic-api-key`. **(3)** scratch file OUTSIDE the repo with `AccountKey=<random 88-char base64>` in a full connection string: **rc=1**. **(4, extra)** the published key with ONE character changed: **rc=1** |
| same on local v8.30.1 | (1) rc=0; (5, extra) the published key in a full connection-string shape: rc=0 | (2) rc=1, (3) rc=1, (4) rc=1, all `generic-api-key` |
| Pre-measurement | before writing the regex: both versions flag emulator.go:23, and the captured `Secret` is exactly the 88-char key (sha256 `0011cc25…` equal to the published key), so an anchored regex can match it | n/a |
| History scan (what pre-push P-3 runs) | `gitleaks git --log-opts fe99fd78..HEAD` 8.27.2: 5 commits, **rc=0**; 8.30.1 rc=0 | same range with the allowlist-free config: **rc=1** |
| `git grep -n 'infra/backups/blobctl' -- ci-cd.yaml` ≥ 3 | **3** (l.149 test step, l.1303 gofmt, l.1313 vet) | base commit: **0** |
| `git grep -n 'directory: "/infra/backups/blobctl"' -- dependabot.yml` = 1 | **1** (l.49). Parsed with PyYAML (conda env jtoye-ops): 1 entry, gomod, weekly, same shape as `/edge-go` | base: **0** |
| `regexTarget` +1 vs base | base **1**, new **2** | n/a |
| `bash scripts/check-gate-enforcement.sh` | **rc=0**, "42 gates, 7 workflows, 6 exempt" | planted unwired `scripts/check-zz-planted-3604.sh`: **rc=1**, "FAIL: 1 gate(s) are referenced by no workflow …". Removed; closing rc=0 |
| Workflow validity (override 7) | actionlint v1.7.12: **0 findings** on base and on new, no new findings. `bash -n` on the 3 extracted blobctl `run:` blocks: 0 failures | **Missing-`fi` arm** in the gofmt block: actionlint **rc=0 (blind; no shellcheck on this machine)**, but the extracted `bash -n` catches it: "syntax error: unexpected end of file", rc=1. **Misspelled key** (`workin-directory`): actionlint reports `unexpected key "workin-directory"` [syntax-check] |

**The CI run itself is UNEXECUTED.** Only the workflow's syntax and shell were validated locally. The first PR run is the proof that the steps execute.

## TDD Gate Compliance

`test(36-04)` RED commits `4ea86cb5` and `725c72a0` each precede their `feat(36-04)` GREEN commits (`fbc43fef`, `7d2e1d19`). Both REDs were verified RED_EVIDENCE_OK by `gsd_run check tdd-red-evidence`. The records are at `/tmp/claude-3604/red-t1.json` and `red-t2.json`. They are TAP synthesised from `go test -json` top-level pass/fail events, because the checker parses TAP, not Go's output.

## Decisions Made

See `key-decisions` in the frontmatter. The load-bearing one is the explicit single-shot vs staged upload, which replaces `UploadFile` (deviation 1).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The plan-prescribed `UploadFile` silently drops If-None-Match above 256 MiB**
- **Found during:** Task 2 GREEN
- **Issue:** The plan said "upload uses the blockblob UploadFile path with AccessConditions … IfNoneMatch". On azblob v1.8.1, `blockblob/models.go getCommitBlockListOptions()` copies Tags, Metadata, Tier, HTTPHeaders and CPK, but NOT `AccessConditions`. Any file above `MaxUploadBlobBytes` (256 MiB) is committed unconditionally. Demonstrated: a 257 MiB `UploadFile` overwrote an existing blob and returned no error.
- **Fix:** `UploadNoOverwrite` sends a single Put Blob (`bb.Upload`) up to the limit. Above it, it stages 8 MiB blocks and calls `CommitBlockList` with `AccessConditions` explicitly. The limit and block size are package vars, so `TestAzureStore_StagedUploadAlsoNeverOverwrites` drives the staged path with 10 B / 4 B.
- **Files modified:** infra/backups/blobctl/commands.go, commands_test.go
- **Verification:** break arm B (condition removed from the commit) turns that test red; restored by hash.
- **Committed in:** 7d2e1d19

**2. [Rule 1 - Vacuous criterion] `git grep -c 'emulatorAccountKey ='` never matches**
- **Found during:** Task 1 acceptance
- **Issue:** gofmt aligns the const block (`emulatorAccountKey  =`, two spaces), so the criterion as written returned nothing on the correct tree.
- **Fix:** whitespace-tolerant replacement plus a repo-wide literal count, both directions recorded above. The original result is recorded alongside.
- **Committed in:** n/a (verification only)

**3. [Rule 1 - Unsatisfiable criterion] the case-insensitive delete grep matches Go's builtin `delete`**
- **Found during:** Task 2 acceptance
- **Issue:** `-i` makes `Delete(…)\(` match `delete(env, missing)` in a test (a map operation), so "prints only rc=1" is false on a correct tree. I declined to bend test code to dodge a grep.
- **Fix:** case-sensitive replacement, positive controls on four delete spellings, and the structural proofs (no delete in the interface; the fake fails on any DELETE).
- **Committed in:** n/a (verification only)

**4. [Process - RED validity] RED used signature stubs, not a missing implementation**
- **Found during:** Tasks 1 and 2 RED
- **Issue:** The plan allows "failing to compile or failing". tdd.md (#3770) classifies compile failures as INVALID_RED.
- **Fix:** Each RED commit carried compile-clean stubs (types and signatures returning "not implemented", `run` returning -1), so the target tests failed on their assertions. emulator.go was committed in RED because the redaction test asserts against the constant; it is data, not behaviour.

**5. [Rule 2 - Missing critical] Additional refusals and safety beyond the case table**
- **Found during:** Tasks 1 and 2
- **Additions:**
  - A connection string alongside workload identity is refused.
  - Unknown connection-string keys are refused (not only the five named).
  - The proxy URI is also refused with userinfo, a query or a fragment.
  - Duplicate keys are refused.
  - `GoString()` is redacted, so `%#v` cannot print the identity fields.
  - download is O_EXCL/0600 and removes a partial file.
  - Empty positional args are usage errors (except list's prefix).
- **Committed in:** fbc43fef, 7d2e1d19

---

**Total deviations:** 5 (1 SDK bug worked around, 2 defective criteria replaced by stronger forms, 1 RED-validity adjustment, 1 set of hardening additions).
**Impact on plan:** Deviation 1 is essential for correctness: without it a large dump could overwrite an existing one. No scope creep, and no files outside `files_modified`.

## Issues Encountered

- The az guard blocked `az storage container create` against local Azurite (a mutation verb). I did not bypass it; the public-container positive control is cited from 36-01 instead.
- The base-python guard blocked a `/usr/bin/python3 -c` that imported PyYAML (not stdlib). I re-ran it under `conda activate jtoye-ops`, as in 36-02's precedent.
- `rg -uu` over the whole repo exits 2 on one unreadable Docker-owned directory, `.gradle-docker/daemon/8.10.2`. The absence-of-other-copies claim excludes that directory.

## Known reds inherited / moved (not fixed, per override 11)

- `scripts/docs-freshness.sh` rc=1. It was already red before this plan (Java 1897→1914, Jest 1873→1878). This plan moves **Go test funcs 84→98 (+14)** and **Go test files 11→13**. docs/metrics.json is NOT regenerated; that is 36-17's.
- `k8s/scripts/check-env-contract.sh`: untouched (36-09).

## Threat Flags

None. blobctl opens no endpoint and adds no auth path beyond the plan's threat register. T-36-12, T-36-13, T-36-14, T-36-15 and T-36-SC are each mitigated, and each mitigation was shown failing above. T-36-SC: go.sum is committed, `go mod verify` runs in CI and the Dependabot entry exists. `azblob` pulls `apache/arrow-go/v18` transitively (`go mod why`: `azblob/internal/arrow`), which widens the image's dependency surface. Dependabot now watches it.

## Next Phase Readiness

- **36-08** (backup image + drill) can build blobctl multi-stage from `infra/backups/blobctl` (`go.mod` at l.3 `go 1.27.0` is the go-toolchain horizon site 36-08 names). The contract it relies on is tested: `blobctl upload` exits 0/3/1, bare `blobctl` exits 2, `list` is sorted, and `download` exits 4 on missing.
- **36-10** (k8s-local secrets): the published key in connection-string shape now passes gitleaks (arm 5). blobctl itself does not accept that shape, though; it needs `UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://host.minikube.internal`, and no key-bearing Secret is required for it.
- Open: the CI steps have never run on GitHub. Watch the first PR run.

## Self-Check: PASSED

- All 8 created files exist; the 3 modified files carry the changes (verified by the grep criteria above).
- Commits `4ea86cb5`, `fbc43fef`, `725c72a0`, `7d2e1d19` and `1b225972` are on `phase-36-azure-blob-storage`; `git rev-list --count fe99fd78..HEAD` = 5 before this SUMMARY.
- Final re-run before writing: `go test -count=1 -v ./...` gave 14 top-level PASS, 73 PASS lines, 0 FAIL. The stub scan (`rg -uu -i 'TODO|FIXME|placeholder|coming soon|not implemented' infra/backups/blobctl`) gave rc=1 (none). No container left running.

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-28*
