---
phase: 26-local-k8s-overlay-verified-breakage-fixes
plan: 01
subsystem: infra
tags: [kubernetes, kustomize, networkpolicy, secretkeyref, stomp, rabbitmq, spring-boot, bash-gate, golden-render]

# Dependency graph
requires:
  - phase: 15-networkpolicies-sealed-secrets
    provides: the 6 NetworkPolicies (incl. the kube-dns DNS-egress rule) whose rendered podSelector D-17 un-poisons
  - phase: 11-stomp-broker-relay
    provides: the stomp.broker.* config block and StompBrokerRelay behind stomp.broker.mode
provides:
  - "k8s/scripts/render-golden.sh — check / --write / --snapshot / --diff-since golden-render harness for the staging + production overlays"
  - "k8s/goldens/{staging,production}.yaml — committed render baselines; every k8s/base edit in Phase 26 is now a reviewable diff"
  - "the fail-closed --snapshot/--diff-since convention (exit 2 on a missing baseline) that plans 26-02/26-04/26-08 anchor their golden assertions to"
  - "DEF-1: core-java DB_PORT sourced from postgres-credentials/port (no literal, no both-fields render)"
  - "DEF-4 deploy half: RABBITMQ_USERNAME -> RABBITMQ_USER, with a blocking PRE-ROLLOUT OPERATOR CHECK comment at the rename site"
  - "D-17: kustomize labels transformer restricted to an explicit Deployment/Service/PDB fields: list, so NetworkPolicy podSelectors are no longer poisoned"
  - "D-05 application half: the additive ${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}} chain + StompCredentialResolutionTest"
affects: [26-02, 26-03, 26-04, 26-05, 26-06, 26-07, 26-08, 26-09]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "golden-render baseline as the Incremental Betterment proof for deploy-layer edits"
    - "named pre-change snapshot (--snapshot/--diff-since) replacing HEAD~1-relative, self-comparing golden assertions"
    - "kustomize labels with includeSelectors:false + includeTemplates:true + an explicit per-kind fields: list"
    - "nested Spring placeholder default chain for additive credential precedence"

key-files:
  created:
    - k8s/scripts/render-golden.sh
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - core-java/src/test/java/uk/jtoye/core/config/StompCredentialResolutionTest.java
  modified:
    - k8s/base/core-java-deployment.yaml
    - k8s/base/kustomization.yaml
    - k8s/staging/kustomization.yaml
    - k8s/production/kustomization.yaml
    - core-java/src/main/resources/application.yml
    - .gitignore

key-decisions:
  - "26-01: --diff-since emits NORMAL diff format (< removed / > added) not unified — the phase's assertions grep '^>' and a unified diff would make `grep -c` return 0 vacuously, recreating the exact anti-false-green failure the convention exists to stop"
  - "26-01: --diff-since headers go to STDERR so stdout is diff-only — this is what keeps `test -s $D` a real signal that the snapshot predates the edit"
  - "26-01: the D-17 fields: list must be repeated in ALL THREE kustomizations; the base entry alone leaves each overlay's own includeSelectors re-poisoning the kube-dns selector"
  - "26-01: operator confirmation of the live rabbitmq-credentials/username recorded as UNAVAILABLE-FROM-THIS-HOST (only the employer AKS context exists, and it must never be targeted) — carried as a blocking in-manifest PRE-ROLLOUT OPERATOR CHECK"
  - "26-01: the committed k8s/goldens baseline IS D-17's required render-level assertion — any future transformer edit that re-poisons a podSelector drifts the golden and fails the gate"

patterns-established:
  - "Golden-render harness: `--write` is the arbiter (docs-freshness idiom); goldens are never hand-edited and are committed WITH the base edit that changed them"
  - "Anchored golden assertion is three-part: resolve_exit=0, forbidden-pattern count on '^>' lines = 0, and a `test -s`/`test ! -s` expectation that catches a snapshot taken after the edit"
  - "Block-scoped selector assertions: a forward-scanning grep/awk over a render counts every later resource's metadata labels and cannot distinguish pre- from post-fix"

requirements-completed: []

# Metrics
duration: ~14min
completed: 2026-07-25
---

# Phase 26 Plan 01: Golden-Render Harness + Three Verified Base Fixes Summary

**A committed `kubectl kustomize` golden baseline with fail-closed `--snapshot`/`--diff-since` anchoring, then the three surgical `k8s/base` fixes it exists to prove safe — `DB_PORT` via `secretKeyRef`, the RabbitMQ env rename with a blocking pre-rollout operator check, and the label transformer stopped from blackholing core-java's DNS egress — plus the additive nested STOMP credential chain proven RED-then-GREEN at all three precedence levels.**

## Performance

- **Duration:** ~14 min (first commit 17:48:44 +0100 → last task commit 17:57:34 +0100, plus verification)
- **Started:** 2026-07-25T16:44Z
- **Completed:** 2026-07-25T17:00Z
- **Tasks:** 3 of 3
- **Files modified:** 10 (4 created, 6 modified) — exactly the plan's `files_modified` set

## Accomplishments

- **The Incremental Betterment proof harness exists and is fail-closed.** `k8s/scripts/render-golden.sh` renders both overlays and compares them byte-for-byte against `k8s/goldens/`. `--diff-since` on a missing baseline exits **2 with an empty stdout**, so a golden assertion anchored to a missing snapshot FAILS rather than silently passing — the vacuous `diff <(git show HEAD~1:<f> || cat <f>) <f>` form is now unusable phase-wide.
- **DEF-1 closed at the render, not just the file.** `DB_PORT` reads `postgres-credentials/port`; the rendered EnvVar has `valueFrom` and **zero** `value:` lines, permanently guarding PIT-2 (both-fields → API-server rejection).
- **DEF-4's deploy half closed with its risk recorded, not assumed.** `RABBITMQ_USER` is now the injected name, so `spring.rabbitmq.username` stops silently defaulting. The rename carries a `PRE-ROLLOUT OPERATOR CHECK` block naming the exact `kubectl … | base64 -d` command, the expected value `jtoye`, the "fix the SECRET, never revert the rename" rule, and why no static gate can cover it.
- **D-17 (verified PRODUCTION defect) closed in all three renders.** The kube-dns DNS-egress `podSelector` is exactly `k8s-app: kube-dns` in base, staging and production — 4 selector blocks per render, 1 key each (was 4 keys each). The 9 Deployment/Service/PDB selector blocks are **byte-identical** to the pre-change snapshot, so no immutable field moved.
- **D-05 proven, not inspected.** `StompCredentialResolutionTest` loads the real `application.yml` and asserts all three precedence levels; it was RED on exactly the 4 "dedicated credential wins" cases before the change and is 8/8 GREEN after.

## Task Commits

1. **Task 1: Golden-render harness + committed pre-change baselines** — `fcbcdc6` (chore)
2. **Task 2: DEF-1 + DEF-4 + D-17** — committed per-fix as the plan specifies, then one reviewed golden regeneration:
   - `550474f` (fix) — DEF-1 `DB_PORT` → `secretKeyRef`
   - `ff40049` (fix) — DEF-4 `RABBITMQ_USERNAME` → `RABBITMQ_USER` + PRE-ROLLOUT OPERATOR CHECK
   - `41ac344` (fix) — D-17 label-transformer `fields:` list across all three kustomizations
   - `445e95e` (chore) — reviewed golden regeneration for all three fixes
3. **Task 3: D-05 STOMP chain (TDD)** — `370bda5` (test, RED) → `486f0b4` (fix, GREEN). No REFACTOR commit: nothing to clean up.

## Files Created/Modified

- `k8s/scripts/render-golden.sh` **(new, 270 lines)** — check / `--write` / `--snapshot <label>` / `--diff-since <label>`. House style of the sibling `check-*.sh` gates: `$BASH_SOURCE` path resolution, `set -euo pipefail`, exit 0 clean / 1 drift-or-stale-label / 2 tooling-or-missing-baseline. Self-describing output (golden path + line count per target).
- `k8s/goldens/staging.yaml`, `k8s/goldens/production.yaml` **(new)** — the render baselines. 1351 lines each pre-change, 1315 each post-change.
- `.gitignore` — `k8s/goldens/.pre/` (transient per-execution snapshots).
- `k8s/base/core-java-deployment.yaml` — `DB_PORT` block replaced with the `pg-backup-cronjob.yaml` `secretKeyRef` shape; RabbitMQ user env renamed; both carry a comment explaining the defect class and, for the rename, the blocking operator check.
- `k8s/base/kustomization.yaml`, `k8s/staging/kustomization.yaml`, `k8s/production/kustomization.yaml` — `includeSelectors: false` + `includeTemplates: true` + a three-entry `fields:` list, with the now-inaccurate "exactly as before" comment replaced by the D-17 rationale.
- `core-java/src/main/resources/application.yml` — the four `stomp.broker` credential keys become the nested chain; `mode`/`relay-host`/`relay-port` untouched.
- `core-java/src/test/java/uk/jtoye/core/config/StompCredentialResolutionTest.java` **(new)** — 8 tests over the real YAML.

---

## Task 2 golden-render diff, grouped by kind

`--diff-since 26-01-task2` (snapshot taken BEFORE any edit, at commit `fcbcdc6`):

| Assertion part | Command | Result |
|---|---|---|
| 1. baseline resolved | `render-golden.sh --diff-since 26-01-task2 > "$D"; echo "resolve_exit=$?"` | **`resolve_exit=0`** (a `2` would mean VOID, not passed) |
| 2. no selector added | `grep '^>' "$D" \| grep -c 'selector'` | **`0`** |
| 3. snapshot predates the edit | `test -s "$D"` | **TRUE** — 82 `<` lines, 10 `>` lines across both targets |

Per target (identical for staging and production, 41 removed + 5 added lines each):

**`kind: Deployment` (core-java) — 2 removed / 5 added, both intended env changes**
```
193c193,196
<           value: "5432"
---
>           valueFrom:
>             secretKeyRef:
>               key: port
>               name: postgres-credentials
251c254
<         - name: RABBITMQ_USERNAME
---
>         - name: RABBITMQ_USER
```

**`kind: NetworkPolicy` — 39 removed / 0 added per target**
13 removal hunks, each dropping some subset of
`app.kubernetes.io/managed-by: kustomize`, `app.kubernetes.io/part-of: jtoye-platform`,
`environment: staging|production` from a `podSelector` / nested `egress[].to[].podSelector`.
39 per overlay matches the planning-time verification figure exactly.

**Every removed line mapped to its enclosing document kind** (removed OLD line numbers extracted from the diff hunk headers, then attributed by walking back to the nearest `^kind:`):

| Target | removed lines | in `NetworkPolicy` | in `Deployment` | anywhere else |
|---|---|---|---|---|
| staging | 41 | **39** | 2 (`value: "5432"`, `- name: RABBITMQ_USERNAME`) | **0** |
| production | 41 | **39** | 2 (same two lines) | **0** |

**Selector identity, proven directly rather than by line heuristics.** Extracting every top-level `spec.selector` block (keyed `kind/name`) from the snapshot and from the current golden and diffing the extracts:

```
staging:    selector blocks OLD=9  NEW=9  -> BYTE-IDENTICAL
production: selector blocks OLD=9  NEW=9  -> BYTE-IDENTICAL
```

The 9 are Service ×3 + Deployment ×3 + PodDisruptionBudget ×3, each still carrying
`app: <svc>`, `app.kubernetes.io/managed-by: kustomize`, `app.kubernetes.io/part-of: jtoye-platform`
and `environment: <env>`. Deployment and Service selectors are immutable, so this is the assertion
that rules out an apply failure on a live cluster. `NetworkPolicy` has `podSelector`, not `selector`,
so it is correctly outside this set — and its `spec` is mutable, so the removals are safe to apply.

**`--diff-since` fail-closed output** (the anti-false-green half):
```
$ bash k8s/scripts/render-golden.sh --diff-since does-not-exist; echo "exit=$?"
ERROR: snapshot 'does-not-exist' not found at k8s/goldens/.pre/does-not-exist.
       An assertion anchored to a missing baseline is VOID, not passing,
       so this exits 2 and prints no diff. Take the snapshot BEFORE the edit.
exit=2
stdout_bytes=0
```

**`--snapshot` round trip:** `--snapshot probe` → exit 0, both files created; `--diff-since probe` → exit 0 with a genuinely EMPTY stdout (0 bytes); a SECOND `--snapshot probe` → **exit 1** ("a stale snapshot must never be silently reused"). `probe` deleted afterwards. A `--write` run left the `k8s/goldens/.pre/` listing byte-identical.

---

## DEF-4 operator confirmation (T-26-61, the CONFIRMED HIGH)

### Outcome: `UNAVAILABLE-FROM-THIS-HOST`

**Attempted from this host, actual output:**
```
$ kubectl config get-contexts
CURRENT   NAME          CLUSTER       AUTHINFO                                    NAMESPACE
          sipbihs2aks   sipbihs2aks   clusterUser_sipbihs2aks_group_sipbihs2aks

$ kubectl config current-context
error: current-context is not set
```

**Reason:** exactly one kubeconfig context exists — the employer AKS cluster `sipbihs2aks`, which is
DO-NOT-TOUCH infrastructure (`k8s-kustomize-deploy` memory) — and no current-context is even set.
There is no reachable `jtoye-staging` or `jtoye-production` cluster from this machine, so the live
`rabbitmq-credentials/username` value cannot be read here. This is precisely what the plan predicted.
**Not recorded as "assumed fine".**

**How the risk is carried instead:**
1. A `PRE-ROLLOUT OPERATOR CHECK` block sits at the rename site in
   `k8s/base/core-java-deployment.yaml`, so it is unavoidable in a diff review. It names the exact
   command for **both** namespaces
   (`kubectl -n jtoye-{staging,production} get secret rabbitmq-credentials -o jsonpath='{.data.username}' | base64 -d`),
   the expected value `jtoye`, and states plainly: **if it differs, change the SECRET to the broker
   user its stored password authenticates — do NOT revert this rename**, because reverting restores
   the silent-default defect and re-hides the mismatch.
2. The same block records **why no gate can cover it**: secret VALUES never appear in a kustomize
   render (only `secretKeyRef` NAMES do), and `check-no-plaintext-secrets.sh` exists precisely to
   guarantee they never will — so neither the golden diff nor any static invariant can see it.
   Verified: `grep -A 30 'PRE-ROLLOUT OPERATOR CHECK' … | grep -c 'check-no-plaintext-secrets'` = 1.
3. The operator-facing record is **plan 26-06's** dated appended note on
   `k8s/PRODUCTION_READINESS_REPORT.md` (that file is a dated signed audit — appended, never
   rewritten). 26-06 already carries this instruction at its line 232.

**Mitigating inference, stated as inference:** for AMQP to be working in staging/production today, the
stored password must already authenticate the broker user `jtoye` (which is what the pool has used all
along via the Spring default), which makes `username: jtoye` likely. It remains unverifiable from this
repository.

---

## Three-case STOMP resolution results (D-05)

`StompCredentialResolutionTest` loads the real `application.yml` via
`ConfigDataApplicationContextInitializer` (not a synthetic property map) and strips the
system-environment and system-property sources, so an ambient `RABBITMQ_USER` can neither mask a
regression nor invent one.

| Level | Supplied | `client-login` / `system-login` | `client-passcode` / `system-passcode` | Pre-change |
|---|---|---|---|---|
| 1 — dedicated wins | `STOMP_CLIENT_*` **and** `RABBITMQ_*` | `stomp-relay-user` | `stomp-relay-secret` | **RED** |
| 2 — fallback (compose) | `RABBITMQ_*` only | `amqp-pool-user` | `amqp-pool-secret` | GREEN (unchanged) |
| 3 — terminal default | nothing | `guest` | `guest` | GREEN (unchanged) |

**RED evidence** against the pre-change single-level form — `8 tests completed, 4 failed`, exactly the
level-1 cases:
```
clientLoginPrefersTheDedicatedStompCredential    expected: "stomp-relay-user"   but was: "amqp-pool-user"
systemLoginPrefersTheDedicatedStompCredential    expected: "stomp-relay-user"   but was: "amqp-pool-user"
clientPasscodePrefersTheDedicatedStompPasscode   expected: "stomp-relay-secret" but was: "amqp-pool-secret"
systemPasscodePrefersTheDedicatedStompPasscode   expected: "stomp-relay-secret" but was: "amqp-pool-secret"
```
That levels 2 and 3 were **already green** is the Incremental Betterment proof: the chain is purely
additive, so compose (which sets `RABBITMQ_USER`/`RABBITMQ_PASSWORD` and no `STOMP_CLIENT_*`) resolves
to exactly the values it did before.

**GREEN:** `8 tests, 0 failures, 0 ignored, 100% successful`. Full `:core-java:test` **767 tests,
0 failures, 1 ignored, BUILD SUCCESSFUL** — no pre-existing unit test regressed by the YAML change.
`git diff --stat core-java/src/main/` shows `application.yml` as the only changed main-source file.

---

## Static gate results

| Gate | Result |
|---|---|
| `k8s/scripts/check-no-plaintext-secrets.sh` | **exit 0** — exactly three discovered targets (`k8s/base` 22 resources, `k8s/production` 23, `k8s/staging` 23), 0 plaintext Secrets. Adding `k8s/goldens/` created no fourth overlay (`test ! -f k8s/goldens/kustomization.yaml` passes). |
| `k8s/scripts/check-connection-math.sh` | **exit 0**, `PASS`, `133 <= 157` for both k8s rows; drift guard and HPA-memory guard OK. Its `awk '/name: DB_POOL_SIZE/{getline; …}'` parser survived the env-block edit (no comment inserted between `- name:` and `value:`). |
| `k8s/scripts/render-golden.sh` | **exit 0** — both renders match their committed goldens (1315 lines each). |
| `./gradlew :core-java:test` | **BUILD SUCCESSFUL** — 767 tests, 0 failures, 1 ignored. |
| `scripts/docs-freshness.sh` | **exit 1 — KNOWN-DEFERRED**, see below. |

Rendered spot-checks:
```
$ kubectl kustomize k8s/production | grep -A 4 'name: DB_PORT'   # (both container and cronjob consumers)
        - name: DB_PORT
          valueFrom:
            secretKeyRef:
              key: port
              name: postgres-credentials

$ kubectl kustomize k8s/production | awk 'BEGIN{RS="\n---"} /name: core-java-allow/{print}' | grep -B 2 -A 1 'k8s-app: kube-dns'
      podSelector:
        matchLabels:
          k8s-app: kube-dns
```
Rendered `DB_PORT` `value:` line count = **0**. `default-deny` still renders `podSelector: {}`.
kube-dns selector blocks per render, key count: base 4×1, staging 4×1, production 4×1 (was 4×4).

## Deferred (by design, not a failure)

`scripts/docs-freshness.sh` check mode is **RED** from Task 3 onward, exactly as the plan states:

| metric | committed `docs/metrics.json` | recomputed | delta |
|---|---|---|---|
| `java_test_methods` | 1143 | 1151 | +8 (the new test's 8 methods) |
| `java_test_files` | 201 | 202 | +1 |
| `total_logical_invocations` | **1690** | **1698** | +8 |

`docs/metrics.json` was **NOT touched** (`git status --short docs/metrics.json` clean). Plan **26-06**
is the single writer for it this phase — it is a documented cross-branch merge-conflict hotspot, and
`--write` is the arbiter. This mirrors Phases 22/23/24, which all reconciled counts in the phase-gate plan.

## Decisions Made

1. **`--diff-since` emits NORMAL diff format, not unified.** The plan's prose says "unified", but its
   own diff-direction convention and every downstream acceptance criterion use `<`/`>` and
   `grep '^>'`. A unified diff uses `-`/`+`, so `grep '^>' | grep -c '<pattern>'` would return 0
   **vacuously** — recreating the exact anti-false-green failure the convention exists to prevent.
   Normal format satisfies both the convention paragraph and the criteria. Check-mode drift still
   prints `diff -u` (nothing greps it, and it reads better for a human).
2. **`--diff-since` headers go to stderr.** If the per-target header were on stdout, `test -s "$D"`
   would be trivially true and the "snapshot taken after the edit" detector would be dead. Stdout is
   diff-only; a no-op edit yields 0 bytes.
3. **`--snapshot` validates both goldens exist before `mkdir`,** so a failed snapshot never leaves a
   half-populated label directory that the stale-label guard would then refuse on retry.
4. **The committed goldens ARE D-17's required render-level assertion.** D-17 asks for one "so the
   class cannot silently return with the next transformer edit". Any future transformer edit that
   re-poisons a `podSelector` drifts the golden and fails the gate (wired into CI by 26-03). No new
   script was invented outside this plan's `files_modified`.
5. **`k8s/base` is deliberately not a golden target.** It renders without a namespace and only ships
   through an overlay, so an overlay render is the behaviour that actually reaches a cluster. Base is
   still asserted directly for the kube-dns selector.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Two Task-2/Task-1 acceptance criteria were unfalsifiable as literally written; replaced with block-scoped equivalents that actually distinguish pre- from post-fix**

- **Found during:** Task 1 (baseline assertion) and Task 2 (D-17 verification)
- **Issue:** Both criteria scan **forward** from `k8s-app: kube-dns`, but `kubectl kustomize` emits
  map keys **alphabetically**, so the poisoned labels (`app.kubernetes.io/*`, `environment`) sort
  **before** `k8s-app`, never after:
  - Task 1: `grep -A 4 'k8s-app: kube-dns' … | grep -c 'app.kubernetes.io/part-of'` was specified as
    ">= 1" on the poisoned baseline. Actual on the pre-fix baseline: **0**. The criterion could never
    pass, in either direction — it proves nothing.
  - Task 2: `awk '/k8s-app: kube-dns/{found=1} found&&/app.kubernetes.io\/(managed-by|part-of)/{c++}'`
    was specified as "prints 0". Once `found=1` it counts those labels for the **rest of the file**,
    including every later resource's `metadata.labels` — which survive the fix by design. Actual on
    the pre-fix baseline: **34**; it would also be nonzero post-fix. It cannot distinguish the two
    states, so "prints 0" was unreachable and a passing variant would have been meaningless.
- **Fix:** proved the same **intent** with block-scoped assertions that genuinely separate the states:
  (a) `grep -B 4` for the baseline direction — **4** poisoned lines on the pre-fix golden; and
  (b) an awk that walks each `matchLabels:` block by indentation and prints the KEY COUNT of every
  block mentioning `k8s-app: kube-dns` — **4 keys × 4 blocks pre-fix, 1 key × 4 blocks post-fix**, in
  base, staging and production. This is strictly stronger: it asserts the selector contains *only*
  `k8s-app: kube-dns`, which is the wording of the plan's own `must_haves` truth.
- **Files modified:** none (verification-only; the assertions are recorded in this SUMMARY)
- **Verification:** pre-fix golden 4 blocks × 4 keys; post-fix 4 blocks × 1 key in all three renders.
- **Committed in:** `41ac344` / `445e95e` (evidence recorded here)

**2. [Rule 1 - Bug] `grep -c 'key: username'` expected value was wrong (2, not 1) — assertion re-expressed as unchanged-versus-baseline**

- **Found during:** Task 2 (DEF-4)
- **Issue:** The criterion says the non-comment `key: username` count "is still 1". There have always
  been **two**: `DB_USER` → `postgres-credentials/username` and the RabbitMQ user →
  `rabbitmq-credentials/username`. Verified against the pre-change file:
  `git show 550474f~1:k8s/base/core-java-deployment.yaml | grep -v '^\s*#' | grep -c 'key: username'` = **2**.
- **Fix:** asserted the criterion's real intent — the secret KEY was not renamed — by comparing the
  count to the pre-change baseline (2 → 2) and showing the `RABBITMQ_USER` block still reads
  `name: rabbitmq-credentials` / `key: username`.
- **Files modified:** none (verification-only)
- **Verification:** count unchanged at 2; block printed in full.
- **Committed in:** `ff40049` (evidence recorded here)

**3. [Rule 3 - Blocking] Reworded two comments so the phase's own greps return the specified values**

- **Found during:** Task 2 (DEF-4) and Task 3 (test Javadoc)
- **Issue:** Two criteria are literal greps that my first drafts tripped on **prose**, not on code:
  `grep -c 'RABBITMQ_USERNAME' k8s/base/core-java-deployment.yaml` must return 0, but the explanatory
  comment named the old env verbatim (returning 1); and `grep -c '@Tag' …StompCredentialResolutionTest.java`
  must return 0, but the Javadoc wrote `{@code @Tag}` (returning 1). The plan's success criterion is
  stricter than the action text: "`RABBITMQ_USERNAME` no longer appears in **any manifest**".
- **Fix:** the manifest comment now describes the old name as *"an env name suffixed `..._USERNAME` —
  mirroring the secret KEY instead of the Spring placeholder"*, which is more precise about the actual
  defect mechanism than the bare token was; the Javadoc says *"an UNTAGGED plain unit test — it
  carries no JUnit tag annotation at all (in particular not the `"testcontainers"` tag)"*. No meaning
  lost. The RED commit was amended (branch has no upstream; nothing was rewritten that had been pushed).
- **Files modified:** `k8s/base/core-java-deployment.yaml`, `core-java/src/test/java/uk/jtoye/core/config/StompCredentialResolutionTest.java`
- **Verification:** both greps now return 0; `grep -rn 'RABBITMQ_USERNAME' k8s/base k8s/staging k8s/production` = 0 hits.
  (`k8s/PRODUCTION_READINESS_REPORT.md` still mentions the old name — deliberately untouched: it is a
  dated signed audit that gets an appended note from 26-06, never a rewrite. Archived planning docs
  under `.planning/milestones/` and `docs/CHANGELOG.md` are historical records and equally untouched.)
- **Committed in:** `ff40049`, `370bda5` (amended)

---

**Total deviations:** 3 auto-fixed (2 broken/unfalsifiable assertions corrected, 1 blocking
comment-wording conflict). **No scope creep** — no file outside the plan's `files_modified` was
touched, and no behaviour differs from what the plan specified. Two of the three are precisely the
anti-false-green class this plan was written to defend against, caught by running the assertions
instead of assuming them.

## Issues Encountered

- **`python3` and `yq` unavailable for authoritative YAML parsing.** A repo hook blocks base-conda
  `python3`, and `yq` is not installed. Rather than activate an unrelated conda env, the selector
  identity proof was built in awk (`RS="\n---"` per-document, 2-space-indent `spec.selector`
  extraction keyed by `kind/name`). It produced a clean 9-blocks-byte-identical result for both
  targets, which is stronger evidence than a line-number heuristic would have been.
- **No cluster reachable for the DEF-4 secret check** — expected; recorded as
  `UNAVAILABLE-FROM-THIS-HOST` with the actual `kubectl config get-contexts` output above.

## Constraint compliance

- **Static side of the static/live split respected.** No minikube start, no compose container stopped,
  no DB role / bucket / Secret created, no `kubectl apply`. Only `kubectl kustomize` (pure local
  render) and `kubectl config get-contexts` (local kubeconfig read) were run. Every mutation remains
  plan 26-07's, behind its human-action checkpoint.
- **Golden-snapshot convention honoured.** All golden assertions anchor to the named
  `26-01-task2` snapshot; the forbidden `diff <(git show HEAD~1:<f> … || cat <f>) <f>` form appears
  nowhere, and `--diff-since` makes it structurally unavailable.
- **JDK 21** (`java version "21.0.6"`, `JAVA_HOME=/usr/java/jdk-21-oracle-x64`) via `./gradlew`.
- **`key: port` precedent verified before copying** — it is at
  `k8s/base/pg-backup-cronjob.yaml:46-50`, confirming the RESEARCH correction over CONTEXT.md's 64-68.
- **`docs/metrics.json` untouched** — 26-06 is its single writer this phase.

## Threat model disposition

| Threat | Disposition | Evidence |
|---|---|---|
| T-26-01 (EoP — DB env block) | mitigated | `DB_USER`/`DB_PASSWORD` `secretKeyRef`s untouched (non-comment `key: username` count unchanged at 2); `check-connection-math.sh` still parses the block, exit 0. The app still connects as NOSUPERUSER `jtoye_app`. |
| T-26-02 (Spoofing — STOMP creds) | mitigated | Three-case resolution test, 8/8 GREEN, RED on level 1 pre-change. `guest` reachable only when nothing is supplied. |
| T-26-03 (Tampering — `fields:` list) | mitigated | 9 Deployment/Service/PDB selector blocks byte-identical to the snapshot; `grep '^>' "$D" \| grep -c selector` = 0. |
| T-26-04 (DoS — kube-dns egress) | mitigated | Selector is exactly `k8s-app: kube-dns` in all three renders (4 blocks × 1 key each). |
| T-26-05 (Info disclosure — goldens) | accepted, verified | `grep -c '^kind: Secret'` = 0 in both goldens; the only placeholder is the non-secret `deployment.timestamp`. `check-no-plaintext-secrets.sh` green. |
| T-26-61 (Spoofing/DoS — live secret value) | mitigated out-of-band | `UNAVAILABLE-FROM-THIS-HOST` recorded with evidence; in-manifest PRE-ROLLOUT OPERATOR CHECK; remediation direction fixed ("fix the secret, never revert"); operator record via 26-06. |
| T-26-SC (supply chain) | n/a | Zero packages installed — bash + YAML + one Java test file. |

**Other quality contracts:** web performance **N/A** (no user-facing page changed); SEO **N/A** (no
public/unauthenticated surface changed); AI agent-readiness **N/A** (no API surface, endpoint or
OpenAPI change).

**Threat flags:** none. No new network endpoint, auth path, file-access pattern or schema change was
introduced at a trust boundary.

## Known Stubs

None. This plan ships one bash gate, three YAML/manifest edits and one unit test — no placeholder
values, no empty-collection defaults reaching a UI, no unwired data source.

## User Setup Required

None for this plan's own execution. **One blocking operator action is carried forward** into the
staging/production rollout path: confirm `rabbitmq-credentials/username` equals `jtoye` in
`jtoye-staging` and `jtoye-production` before the next rollout (full command and remediation rule in
the `PRE-ROLLOUT OPERATOR CHECK` block in `k8s/base/core-java-deployment.yaml`; operator-facing record
to be appended by 26-06).

## Next Phase Readiness

**Ready for the rest of Wave 1+ and for the plans that depend on this harness:**
- `render-golden.sh --snapshot <label>` / `--diff-since <label>` is available and fail-closed for
  **26-02** (`app-config` split-horizon keys), **26-04** (ingress host cleanup + DEF-6 env block) and
  **26-08**. Each must use its **own** label — a stale label is refused with exit 1.
- **26-03** owns wiring `render-golden.sh` into the `k8s-validate` CI job (single writer for
  `.github/workflows/ci-cd.yaml`) and the D-07/D-08 env-contract gate. Note for 26-03: direction (a)
  of that gate will now find `RABBITMQ_USER` **read** by `application.yml` (DEF-4 closed here), and
  `STOMP_CLIENT_LOGIN`/`STOMP_CLIENT_PASSCODE` read too — three fewer injected-but-unread names than
  the RESEARCH inventory recorded.
- **26-06** owns the `docs/metrics.json` reconcile (1690 → 1698) **and** the dated
  `k8s/PRODUCTION_READINESS_REPORT.md` note carrying the DEF-4 operator check.
- **26-07** owns every live mutation; nothing in this plan pre-empted it.

**Concerns:**
- `docs-freshness` check mode stays RED until 26-06 runs `--write`. Expected; do not hand-fix.
- Any plan editing a `kustomization.yaml` `labels:` entry must repeat the `fields:` list — a new
  overlay copied from the pre-fix staging shape would silently re-poison its own NetworkPolicy
  selectors. The golden gate catches it for staging/production; a brand-new overlay (e.g. `k8s/local`
  in **26-05**) has no golden yet, so assert its kube-dns selector directly.

## Self-Check: PASSED

All 5 created files and all 6 modified files exist on disk; all 8 commits
(`fcbcdc6`, `550474f`, `ff40049`, `41ac344`, `445e95e`, `370bda5`, `486f0b4`, `9fd9f2a`)
resolve in `git log`. Every must_haves truth in the plan frontmatter is backed by a
command + captured output above — none is claimed unproven.

| must_haves truth | Proof |
|---|---|
| Reviewer can prove base edits leave staging/production behaviourally unchanged | `--diff-since 26-01-task2` diff reproduced above, grouped by kind, every removed line attributed to its document kind |
| `DB_PORT` sourced from `postgres-credentials` | rendered EnvVar shows `valueFrom.secretKeyRef` `key: port`; `grep -cE '^\s+value: "5432"'` = 0 |
| No rendered core-java EnvVar carries both `value:` and `valueFrom:` | rendered `DB_PORT` `^\s*value:` count = **0** |
| Injected RabbitMQ env name matches what `application.yml` reads | `- name: RABBITMQ_USER` ×1; `RABBITMQ_USERNAME` in `k8s/base|staging|production` = 0; `application.yml` reads `${RABBITMQ_USER:jtoye}` |
| Rename ships with a recorded operator confirmation | `UNAVAILABLE-FROM-THIS-HOST` with actual `kubectl config get-contexts` output + in-manifest PRE-ROLLOUT OPERATOR CHECK (grep-verified) |
| Every golden assertion anchored to a NAMED snapshot that must resolve | `--diff-since does-not-exist` → exit **2**, 0 bytes stdout; `--snapshot` refuses a stale label with exit 1 |
| STOMP chain resolves dedicated → RabbitMQ → `guest` | 8/8 GREEN, RED on exactly the 4 level-1 cases pre-change |
| Rendered kube-dns podSelector contains ONLY `k8s-app: kube-dns` in base/staging/production | 4 blocks × 1 key in each of the three renders (was 4 × 4) |
| Deployment/Service/PDB selectors byte-identical before and after | 9 extracted selector blocks per target, `diff` empty for both |

---
*Phase: 26-local-k8s-overlay-verified-breakage-fixes*
*Plan: 01*
*Completed: 2026-07-25*
