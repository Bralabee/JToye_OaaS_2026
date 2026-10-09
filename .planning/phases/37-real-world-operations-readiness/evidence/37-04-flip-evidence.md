# 37-04 evidence: strict scoping ON by default (D-06), review-queue scope, guard gate

All runs from `/home/sanmi/IdeaProjects/JToye_OaaS_2026-phase37`, branch `phase-37-ops-readiness`,
with `ACCESS_STRICT_SCOPING` **unset** in the shell (each run log starts with
`env ACCESS_STRICT_SCOPING=[<unset>]`). JUnit XML read from `core-java/build-local/test-results`
and copied out of the build dir before the next run. XML reader: `check-xml.sh` (37-03), which
prints BAD on `tests=0`, `failures>0` or `errors>0` and VOID on a missing or unparseable file.

## 1. Task 1 (tracer, TDD): deny-by-default under the shipped default

Command (plan verify 1):
`./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests '…StrictScopingDefaultIntegrationTest' --tests '…StrictScopingBootedValueIntegrationTest' --no-daemon`

| Run | Tree | rc | Result |
|-----|------|---:|--------|
| RED | `0f1a893b` (test only, default still false) | 1 | 6 tests, 3 failed: ungranted write `201` (and the log line `JIT-provisioned tenant-wide GROUP_ADMIN for sub …`); staff/me `groupAdmin:true, grantedShopIds:null`; no bootstrap WARN. OPERATOR and realm-admin controls passed. Instrument: `expected=false booted=false`. |
| GREEN | `44983a13` | 0 | 5/0 + 1/0; instrument `env.ACCESS_STRICT_SCOPING=null expected=true booted=true property=true` |
| Break arm | `44983a13` + application.yml default back to `false` (uncommitted; sha256 before `7a37ad23…701044`) | 1 | 4 failed: the same 3 default-IT arms, and the instrument `expected: true but was: false` |
| Restore | `git checkout --` | — | sha256 `7a37ad23…701044` (matches) |
| Closing clean | `44983a13` | 0 | 5/0 + 1/0, `booted=true` |

RED record: `evidence/37-04-red-default-it.json` → `gsd_run check tdd-red-evidence` = `RED_EVIDENCE_OK`
(`target_test_failed`, 3 fail / 2 pass), semantic assessment in the record.

Posture unit test (plan verify 2):
`./gradlew :core-java:cleanTest :core-java:test --tests '…ShopAccessServiceScopingPostureTest' --no-daemon`

| Run | rc | Result |
|-----|---:|--------|
| GREEN (after adding Boot's conversion service; the first attempt failed to boot the context on `Duration` binding) | 0 | 3/0 |
| Break arm (application.yml default `false`) | 1 | `bootedBean_readsTheD06Default…`: `[… booted from application.yml (ACCESS_STRICT_SCOPING=null)] expected: true but was: false` |
| Closing clean | 0 | 3/0 |

Acceptance grep `grep -n 'ACCESS_STRICT_SCOPING:true' core-java/src/main/resources/application.yml`:
real tree → `210:    strict-scoping: ${ACCESS_STRICT_SCOPING:true}` rc 0; the pre-change file (exported
from `c02a3ef9`) → nothing, rc 1.

## 2. Deviation: review queue shop scope (TDD)

Command: `./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests '…MediaReviewQueueShopScopeIntegrationTest' --no-daemon`

| Run | Tree | rc | Result |
|-----|------|---:|--------|
| RED | `b2ff028a` | 1 | 4 tests, 2 failed: the ungranted vendor's queue and the shop-A grantee's queue both contained the tenant's other assets. OPERATOR GROUP_ADMIN and realm-admin controls passed. |
| GREEN (+ neighbours) | `4c55677b` | 0 | ShopScope 4/0, MediaReviewQueue 3/0, MediaRedriveController 7/0, MediaKeepShopScope 3/0 |
| Arm 1: shop filter disabled (`if (false && …)`) | uncommitted | 1 | the same 2 arms fail |
| Arm 2: shop-less asset let through (`owningShop == null \|\| …`) | uncommitted | 1 | only the shop-A arm fails (sees the shop-less asset) |
| Restore after each arm | `git checkout --` | — | sha256 `09593717…cd947a` both times |
| Closing clean | `4c55677b` | 0 | 4/0 |

RED record: `evidence/37-04-red-review-queue-scope.json` → `RED_EVIDENCE_OK`.

## 3. Task 2: the guard gate, both directions

Real tree: `bash scripts/check-strict-scoping-default.sh` → `RESULT: PASS`, 6 values judged
(application.yml, compose, .env.example, configmap, both goldens), rc 0.

**Self-caught defect on the first real-tree run:** the placeholder regex was not anchored to
`${`, so on the compose line `ACCESS_STRICT_SCOPING: ${ACCESS_STRICT_SCOPING:-true}` it captured
`${ACCESS_STRICT_SCOPING:-true` as the value and failed the correct tree. Fixed by anchoring
(`\$\{ACCESS_STRICT_SCOPING:-?…\}`); every arm below ran on the fixed, committed script.

Pre-Task-2 tree (config exported from `4c55677b`, application.yml already true) → rc 1, 10 FAIL
lines naming `docker-compose.full-stack.yml:324`, `.env.example:156`, `k8s/base/configmap.yaml:391`,
`k8s/goldens/staging.yaml:47`, `k8s/goldens/production.yaml:47` (each "not true" plus "no true value").

Arms (each a fresh copy of the inputs with one break; script `arms.sh`):

```
== ARM control: rc=0 (expected 0)
RESULT: PASS — every runtime says strict scoping is ON (D-06)
325:      ACCESS_STRICT_SCOPING: ${ACCESS_STRICT_SCOPING:-false}
== ARM compose-false: rc=1 (expected 1)
FAIL: docker-compose.full-stack.yml:325 placeholder-default value 'false' is not true (D-06: strict scoping must not be overridden to false)
FAIL: docker-compose.full-stack.yml mentions strict scoping but states no true value (the true default was dropped)
387:  access.strict-scoping: "false"
== ARM configmap-false: rc=1 (expected 1)
FAIL: k8s/base/configmap.yaml:387 assignment value 'false' is not true (D-06: strict scoping must not be overridden to false)
FAIL: k8s/base/configmap.yaml mentions strict scoping but states no true value (the true default was dropped)
210:    strict-scoping: ${ACCESS_STRICT_SCOPING:false}
== ARM appyml-false: rc=1 (expected 1)
FAIL: core-java/src/main/resources/application.yml:210 placeholder-default value 'false' is not true (D-06: strict scoping must not be overridden to false)
FAIL: core-java/src/main/resources/application.yml mentions strict scoping but states no true value (the true default was dropped)
47:  access.strict-scoping: "false"
== ARM golden-false: rc=1 (expected 1)
FAIL: k8s/goldens/production.yaml:47 assignment value 'false' is not true (D-06: strict scoping must not be overridden to false)
FAIL: k8s/goldens/production.yaml mentions strict scoping but states no true value (the true default was dropped)
        - name: ACCESS_STRICT_SCOPING
          value: "off"
== ARM overlay-env-off: rc=1 (expected 1)
FAIL: k8s/staging/workload-identity-patch.yaml:43 env value 'off' is not true (D-06: strict scoping must not be overridden to false)
155:ACCESS_STRICT_SCOPING=0
== ARM envexample-zero: rc=1 (expected 1)
FAIL: .env.example:155 assignment value '0' is not true (D-06: strict scoping must not be overridden to false)
FAIL: .env.example mentions strict scoping but states no true value (the true default was dropped)
    strict-scoping: no
== ARM profile-relaxed-no: rc=1 (expected 1)
FAIL: core-java/src/main/resources/application-prod.yml:168 assignment value 'no' is not true (D-06: strict scoping must not be overridden to false)
210:    strict-scoping: ${ACCESS_STRICT_SCOPING}
== ARM appyml-default-dropped: rc=1 (expected 1)
FAIL: core-java/src/main/resources/application.yml mentions strict scoping but states no true value (the true default was dropped)
== ARM no-appyml: rc=2 (expected 2)
VOID: required file missing: core-java/src/main/resources/application.yml
== ARM configmap-key-deleted: rc=2 (expected 2)
VOID: k8s/base/configmap.yaml never mentions strict scoping — the key was deleted or the file is not the one this gate must read
== ARM no-such-root: rc=2 (expected 2)
VOID: root directory not found: <scratch>/arms/no-such-root
== ARM no-local-overlay: rc=2 (expected 2)
VOID: overlay directory missing: k8s/local
arms done, unexpected=0
```

(RESULT lines omitted above for the FAIL/VOID arms; each printed `RESULT: FAIL …` or `RESULT: VOID …`.)
The plan asked for 4 rc-1 arms and 2 rc-2 arms; 8 and 4 were run. The extra rc-1 arms are the
spellings a deny-list of the word "false" would miss (`off`, `0`, `no`, a dropped default).

Wiring: `bash scripts/check-gate-enforcement.sh` → `PASS` (47 gates). With the ci-cd.yaml step
renamed away (uncommitted, sha256 before `823b85be…f74e`) → rc 1,
`check-strict-scoping-default.sh … referenced by no workflow`. Restored by `git checkout --`,
sha256 matched, closing run `PASS`.

Goldens: `k8s/scripts/render-golden.sh` on the edited base (before `--write`) → rc 1, drift shown
in both overlays on `access.strict-scoping` only; after `--write` → `OK` both. `git diff 4c55677b..HEAD -- k8s/goldens`
changes exactly one line per golden (`"false"` → `"true"`, line 47).

Acceptance search `rg -uu -n 'ACCESS_STRICT_SCOPING:-false|access.strict-scoping: "false"|ACCESS_STRICT_SCOPING=false' docker-compose.full-stack.yml .env.example k8s`:
real tree → nothing, rc 1; the same search over the pre-Task-2 export → 5 lines (compose:324,
.env.example:156, configmap:391, both goldens:47), rc 0.

`k8s/scripts/check-render-invariants.sh` → PASS; `k8s/scripts/check-env-contract.sh` → PASS;
`scripts/check-env-example-contract.sh` → PASS; `scripts/check-no-measured-placeholders.sh` → PASS.

Doc citations: `scripts/check-doc-citations.sh` failed with 2 violations after Task 1
(`k8s/LOCAL.md:609` and `:1634` cite `application.yml:519`, which moved to 520 when the
strict-scoping comment grew by one line). Citations updated; re-run → 0 violations, 29 verified.
Its `docs/ops/terminal-states.yaml` arm prints VOID on this machine because the python shim
refuses its bare `python3` call; that is the local environment, unchanged by this plan.

## 4. Task 3: load-testing preflight

The decision both preflights use, fed canned `staff/me` responses (`preflight-cases.sh`):

```
ungranted      expected=VOID-noaccess  got=VOID-noaccess
group-admin    expected=PROCEED        got=PROCEED
one-shop       expected=PROCEED        got=PROCEED
401            expected=VOID-code      got=VOID-code
unreachable    expected=VOID-code      got=VOID-code
garbage-200    expected=VOID-noaccess  got=VOID-noaccess
```

The `[403]` detector in load-test.sh on a hey-shaped distribution: with `[403] 97 responses` →
match; with only `[201]` → no match. `bash -n` on all three scripts → rc 0; on a copy with an
appended `if then fi` → rc 2, `syntax error near unexpected token 'then'`.

Not run against the live stack: the running compose stack still has the pre-flip image (by design,
see deferred-items §4), and this worktree has no `.env` to mint a `tenant-a-user` token.

## 5. Task 3: full suite under the shipped default

Plan command: `./gradlew :core-java:cleanTest :core-java:test :core-java:cleanIntegrationTest :core-java:integrationTest --continue --no-daemon`,
`ACCESS_STRICT_SCOPING` unset.

| Run | Head | Start / end (UTC) | rc | Result |
|-----|------|-------------------|---:|--------|
| Full command | `d04fd6c3` | 10:48:16 / 11:17:41 | 1 | `:integrationTest` 176 suites, 908 tests, 0 failed, 1 skipped; instrument `expected=true booted=true`. `:test` **void**: `NoSuchFileException … test-results/test/binary/in-progress-results-generic.bin` — a second Gradle build I started by running `scripts/check-boot-config-keys.sh` (which runs `:core-java:test --rerun` for one class) during this run deleted the first build's binary results. Instrument fault, not a test result. |
| `:test` alone (nothing else running) | `8034ae89` (docs-only commit after `d04fd6c3`) | 11:18:20 / 11:19:39 | 0 | 187 suites, 1597 tests, 0 failed, 1 skipped |

Neither half was UP-TO-DATE (`> Task :core-java:test` and `> Task :core-java:integrationTest`
executed after the clean tasks). Shape check against 37-03 (`c3fb5743`: 187 / 1596 and 173 / 894):
`:test` +1 = the posture test's new booted-default case; `:integrationTest` +3 suites / +14 tests =
37-03's `DemoDataSeederServiceAccountGrantIntegrationTest` (5, added after its full run) +
`StrictScopingDefaultIntegrationTest` (5) + `MediaReviewQueueShopScopeIntegrationTest` (4).
No test needed a new grant: 37-03's strict-ON conversion already covered every class, so the flip
itself produced 0 reds.
