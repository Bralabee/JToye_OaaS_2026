---
phase: 26-local-k8s-overlay-verified-breakage-fixes
plan: 06
subsystem: infra
tags: [kubernetes, minikube, kustomize, runbook, docs-freshness, networkpolicy, ingress-nginx, backups, rls]

# Dependency graph
requires:
  - phase: 26-01
    provides: "the golden-render harness, the three k8s/base fixes (DB_PORT valueFrom, RABBITMQ_USER rename + its PRE-ROLLOUT OPERATOR CHECK, the D-17 kube-dns selector fix) and the StompCredentialResolutionTest that moves the Java count"
  - phase: 26-02
    provides: "the 19 new app-config keys, the D-13 split-horizon wiring, the D-18 dead NEXT_PUBLIC_API_URL removal, and the per-phase deferred-items.md this plan appends to"
  - phase: 26-03
    provides: "check-env-contract.sh + check-render-invariants.sh and the k8s/DEPLOYMENT.md 'K8s static gates' section the runbook builds on"
  - phase: 26-04
    provides: "the committed k8s/local overlay (6 files, 23 resources, 8 endpoint shims) and the base auth.jtoye.co.uk dangling-ingress fix the readiness-report note records"
  - phase: 26-05
    provides: "scripts/lib/k8s-local-guards.sh, k8s-local-secrets.sh, k8s-local-up.sh and the .env K8S_LOCAL_* contract the runbook documents"
provides:
  - "k8s/LOCAL.md — the operator runbook (550 lines, 11 sections) that makes the local cluster reproducible without reading any plan or research document"
  - "The honest non-proof boundary as a first-class deliverable: no TLS/HSTS, NOT the nginx security-header snippet (ingress-nginx v1.12.2), NOT NetworkPolicy enforcement (with the PIT-7 CIDRs and ports written out), NOT HPA scaling"
  - "An EMPTY, visibly-unfilled rehearsal-evidence template with one row per LIVE row of 26-VALIDATION.md (7), a mandatory four-image-identity header, and the localhost:9090 evidence-invalidating rule"
  - "The two-arm backup falsification recipe (app-role dump restores to products = 0; jtoye_backup dump to products > 0) in both k8s/LOCAL.md and docs/runbooks/backups.md"
  - "A dated post-audit note on the signed readiness audit listing the six production-affecting defects Phase 26 closed, with an OUTSTANDING OPERATOR ACTIONS section carrying the blocking rabbitmq-credentials/username pre-rollout check"
  - "docs/metrics.json reconciled 1690 -> 1698 and the CLAUDE.md/AGENTS.md prose synced byte-identically — the docs-freshness gate is green for the first time since plan 26-01"
  - "Seven conscious phase omissions plus one sweep finding recorded as dated deferred items"
affects: [26-07, 26-08, 26-09, milestone-close, network-policy-rollout, observability, frontend-runtime-config, secrets-management]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Append-only assertion on a dated document is measured with `git diff --numstat` (deletions column), NOT `git diff | grep -c '^-'` — git diff's own `--- a/<path>` header begins with a hyphen, so the grep form returns 1 for a pure append and cannot distinguish it from a rewrite"
    - "Secret-leak sweeps must classify each hit against the committed tree: a dev .env whose values are English words (`secret`, `placeholder`) or identifiers (`core-api`, `jtoye_app`) makes a naive value-grep fire on ordinary prose"
    - "A Gradle `UP-TO-DATE` task is BUILD SUCCESSFUL while executing nothing; forcing `cleanTest` and reading the JUnit XML mtimes is what makes a test claim falsifiable"
    - "Evidence templates ship EMPTY with the owning plan named per row, so an unfilled row reads as 'not yet proven' rather than inviting a plausible-looking fill"

key-files:
  created:
    - k8s/LOCAL.md
  modified:
    - k8s/QUICK_START.md
    - k8s/DEPLOYMENT.md
    - k8s/PRODUCTION_READINESS_REPORT.md
    - docs/runbooks/backups.md
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/deferred-items.md

key-decisions:
  - "26-06: the plan's append-only criterion `git diff <file> | grep -c '^-'` == 0 is UNSATISFIABLE — git diff emits its own `--- a/<path>` header, so a pure append scores 1 and a line-deleting change scores 2; replaced with `git diff --numstat` deletions == 0 and proven falsifiable (116/0 clean vs 116/1 after deliberately deleting one pre-existing line)"
  - "26-06: the plan's secret sweep (grep each .env value, expect zero hits) is WEAK against this dev .env, whose POSTGRES_PASSWORD/DB_PASSWORD/KC_DB_PASSWORD value is the English word `secret` and POSTGRES_EXPORTER_PASSWORD is `placeholder`; it fired 8 times on pure prose. Replaced with a form that classifies each hit against the committed tree: 6 pre-existing-vocabulary hits, 0 genuine leaks"
  - "26-06: `./gradlew :core-java:test` alone returned UP-TO-DATE (BUILD SUCCESSFUL executing nothing), and the stale `core-java/build/test-results/` on this host is from 2025-12-27 and reports 3 FAILURES — the real build dir is `build-local` (build.gradle.kts:15). Reading either would have produced a false green or a false red"
  - "26-06: the test-count claim's boundary is stated, not implied — `:core-java:test` excludes @Tag(testcontainers), so 767/0 executed this session is a SUBSET of the 1151 @Test methods the manifest counts"
  - "26-06: `.planning/PROJECT.md` staleness (1257 baseline at :128) is RECORDED as a deferred item, not fixed — it is outside this plan's file list and line 165 is a dated Phase 25 narrative"
  - "26-06: INFRA-01 and INFRA-02 deliberately NOT marked complete (anti-false-green) — their acceptance includes live proofs only plans 26-07 and 26-08 can produce"

patterns-established:
  - "Non-proof boundary as a named deliverable: each 'local does not prove X' claim carries its concrete evidence (controller version, CIDR, port list) so it cannot be waved away, and grep-asserted per fact"
  - "Per-row evidence ownership: every live evidence row names the plan that will fill it (L1-L5 = 26-07, L6-L7 = 26-08), so an unfilled row is attributable rather than ambiguous"
  - "Single-writer discipline for a cross-branch merge-conflict hotspot: exactly one plan per phase writes docs/metrics.json, via `--write` as the arbiter, and the prose that quotes it is synced in the same commit"

requirements-completed: []

# Metrics
duration: ~52min
completed: 2026-07-25
---

# Phase 26 Plan 06: Docs Reconcile + Local Runbook Summary

**`k8s/LOCAL.md` now makes the local cluster reproducible by someone who has read none of this phase — and states plainly, with concrete evidence, the four things a green local run does not prove — while `docs/metrics.json` is reconciled 1690 → 1698 and the docs-freshness gate is green for the first time since plan 26-01.**

## Performance

- **Duration:** ~52 min
- **Started:** 2026-07-25T19:00Z (approx)
- **Completed:** 2026-07-25T19:52Z
- **Tasks:** 2 of 2
- **Files modified:** 8 (1 created, 7 modified)

## Accomplishments

- **`k8s/LOCAL.md` (550 lines, all 11 sections).** A developer can bring up, verify and tear down the
  local cluster from this one file. It documents the twelve ordered bring-up steps individually so any
  single step is runnable by hand, the real `.env` `K8S_LOCAL_*` keys, the verified tool versions
  (kubectl v1.33.3 / Kustomize v5.6.0, minikube v1.36.0, Docker 29.6.2), and the exact `/etc/hosts`
  line shape.
- **The honest boundary is a deliverable, not a caveat.** §6 states, each with its concrete evidence:
  no TLS/HSTS (`tls: null`, no cert-manager); **not** the nginx security-header snippet
  (ingress-nginx **v1.12.2** defaults `allow-snippet-annotations: false` +
  `annotations-risk-level: High`, so the base `configuration-snippet`'s 6 `more_set_headers`
  directives are rejected by the validating admission webhook — with an explicit *do not enable it on
  the cluster* instruction); **not** NetworkPolicy enforcement (D-11), with the PIT-7 CIDRs
  (`0.0.0.0/0` except `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) and the seven ports
  (`5433/8085/6379/5672/61613/9000/1025`) written out rather than summarised; and not HPA scaling.
- **An empty evidence template that cannot be faked into a pass.** §11 has **7 rows — one per LIVE row
  of `26-VALIDATION.md`'s Per-Task Verification Map** (independently counted: 7 in the map, 7 in the
  file), every `Actual` field blank, a mandatory header block for **four** image identities (PIT-4: the
  on-host `:2.1.0` images predate Phases 23, 24 and 25), and the rule that a `localhost:9090` anywhere
  in the captured output means the compose apps were up, the XOR guard was bypassed, and **the run does
  not count**. Each row names its owner: L1–L5 → plan 26-07, L6–L7 → plan 26-08.
- **The two-arm backup falsification, in both places it belongs.** `MIN_BACKUP_BYTES` (1000) and
  `pg_restore --list` **both pass on a schema-only zero-row dump**, so only a restore-and-count
  falsifies it: the app-role (`jtoye_app`) dump must restore to **`products = 0`** and the
  `jtoye_backup` BYPASSRLS dump to **`products > 0`**. Written into `k8s/LOCAL.md` §9 and appended as a
  new dated section in `docs/runbooks/backups.md`, referencing the proven restore commands rather than
  duplicating them.
- **The signed audit gained a dated note it cannot lose.** Six production-affecting defects Phase 26
  closed that the 2026-01-16 audit did not surface, each with plan number, mechanism and production
  effect — including the `auth.jtoye.co.uk` → Service `keycloak` ingress rule that published a hostname
  with **no backend in any render** (503 in staging *and* production) whose shared `jtoye-tls` SAN
  entry risked failing certificate issuance for `api` and `app` too.
- **`docs-freshness` is green.** 1690 → **1698**; `schema_version` unchanged at **59**; the
  CLAUDE.md/AGENTS.md prose lines are byte-identical and no longer say 1684 or 376 Jest.

## Task Commits

1. **Task 1: `k8s/LOCAL.md` runbook + rehearsal-evidence template + deploy-doc cross-links** —
   `52e5660` (docs) — 885 insertions, 0 deletions across 6 files
2. **Task 2: single `docs/metrics.json` reconcile + CLAUDE.md/AGENTS.md prose sync** —
   `d622e9c` (docs) — 31 insertions, 5 deletions across 4 files

## The metrics reconcile

`scripts/docs-freshness.sh --write` (the arbiter — `docs/metrics.json` was never hand-edited), then
check mode confirmed **exit 0**: `docs-freshness OK: metrics match source (total logical invocations: 1698).`

| field | before | after | delta |
|---|---|---|---|
| `java_test_methods` | 1143 | **1151** | **+8** |
| `java_test_files` | 201 | **202** | **+1** |
| `total_logical_invocations` | **1690** | **1698** | **+8** |
| `java_controllers` | 23 | 23 | 0 |
| `schema_version` | 59 | **59** | 0 (no Flyway migration this phase) |
| `go_test_funcs` / `go_test_files` | 77 / 9 | 77 / 9 | 0 |
| `jest_blocks` / `jest_files` | 382 / 59 | 382 / 59 | 0 |
| `playwright_blocks` / `playwright_specs` | 40 / 11 | 40 / 11 | 0 (26-05's cookie-domain change added no block) |
| `mcp_test_blocks` / `mcp_test_files` | 48 / 8 | 48 / 8 | 0 |

**Per-language breakdown of the new total:** 1151 Java `@Test` + 382 Jest `it/test` + 77 Go `Test*` +
40 Playwright `test()` + 48 mcp-server vitest = **1698**, asserted arithmetically (`jq` sum == total).
The diff touches only count fields; no field was added or removed.

**The entire +8/+1 is plan 26-01's `StompCredentialResolutionTest`**, verified from this run's own
JUnit XML rather than from the plan's claim: `tests="8" skipped="0" failures="0" errors="0"`.

**The bash gates contribute 0 by design** — so no reviewer should hunt for a missing delta.
`scripts/docs-freshness.sh` counts only Java `@Test`, Go `^func Test*`, Jest/vitest `it(`/`test(` and
Playwright `test(`; it counts no bash at all. Plans 26-01's `render-golden.sh` and 26-03's
`check-env-contract.sh` / `check-render-invariants.sh` therefore add nothing, as do 26-02 (manifests),
26-04 (YAML) and 26-06 (Markdown).

**Prose sync.** Both `CLAUDE.md:15` and `AGENTS.md:15` were stale on **three** counts before this phase
(1684 total, 376 Jest blocks, 58 Jest files, versus the manifest's 1690 / 382 / 59). Both now read
1698 / 1151 / 202 / 382 / 59 / 77 / 9 / 40 / 11 / 48 / 8, and
`diff <(sed -n 15p CLAUDE.md) <(sed -n 15p AGENTS.md)` produces no output — byte-identical, so they
cannot drift apart again. `grep -c '1684'` and `grep -c '376 Jest'` are both **0** in both files.

## OUTSTANDING OPERATOR ACTIONS, as appended to the signed audit

The blocking item, verbatim in substance as it now reads in
`k8s/PRODUCTION_READINESS_REPORT.md`:

Plan 26-01 renamed the injected env `RABBITMQ_USERNAME` → **`RABBITMQ_USER`** (the Secret **key**
`username` is unchanged). Before the rename `spring.rabbitmq.username` silently fell back to its
`application.yml` default — the literal `jtoye` — while using the Secret's real password; after the
rename the Secret's `username` **value** takes effect for the primary AMQP pool for the first time. So
before the next staging or production rollout, confirm in **both** namespaces:

```bash
kubectl -n jtoye-staging    get secret rabbitmq-credentials -o jsonpath='{.data.username}' | base64 -d
kubectl -n jtoye-production get secret rabbitmq-credentials -o jsonpath='{.data.username}' | base64 -d
```

Expected output in each: **`jtoye`** (a username, not a credential — no secret value appears in the
note). **Remediation direction, one-way: if the value differs, change the SECRET to the broker user
whose password that Secret authenticates. Never revert the rename.** Reverting restores the original
defect, re-armed and undetectable to anyone reading the manifest.

**No static gate can ever cover this**, and the note says so: Secret *values* never appear in a
kustomize render, and `k8s/scripts/check-no-plaintext-secrets.sh` exists specifically to guarantee they
never will. It is an operator action permanently, not a gap awaiting automation.

**Plan 26-01's recorded confirmation outcome, quoted verbatim in the note:**

> ### Outcome: `UNAVAILABLE-FROM-THIS-HOST`
>
> **Attempted from this host, actual output:**
> ```
> $ kubectl config get-contexts
> CURRENT   NAME          CLUSTER       AUTHINFO                                    NAMESPACE
>           sipbihs2aks   sipbihs2aks   clusterUser_sipbihs2aks_group_sipbihs2aks
>
> $ kubectl config current-context
> error: current-context is not set
> ```
>
> **Reason:** exactly one kubeconfig context exists — the employer AKS cluster `sipbihs2aks`, which is

The note adds that this context is employer infrastructure and must never be targeted, which is why
the confirmation was recorded as unavailable rather than attempted — the check therefore remains open.
Two further operator items were folded in from plan 26-02's deferrals: the four
`UNVERIFIABLE-FROM-THIS-HOST` SES/S3 values to confirm before activating those credentials, and the
`NOT-PROVISIONED` Stripe Connect return/refresh routes (a vendor lands on a 404 — a UX dead-end, not a
correctness defect).

## Append-only proof on the two dated documents

Both are dated records this repository never rewrites. Measured with `git diff --numstat`
(`added deleted file`):

```
116  0  k8s/PRODUCTION_READINESS_REPORT.md
 37  0  docs/runbooks/backups.md
193  0  .planning/phases/26-.../deferred-items.md   (167 in Task 1 + 26 in Task 2)
```

**Zero deletions on all three.** The in-cluster `- [ ] CronJob completes **in-cluster** (exit 0)`
checkbox in `docs/runbooks/backups.md:293` is still **UNTICKED** — it is plan 26-07's to tick, and the
new falsification recipe was inserted *above* it. The 2026-07-10 dated local-drill results are
untouched.

`test ! -f .planning/deferred-items.md` passes — no top-level file was created; the convention is
per-phase.

## Deferred items recorded (per-phase file: `.planning/phases/26-local-k8s-overlay-verified-breakage-fixes/deferred-items.md`)

Seven conscious omissions from this phase, each with a reason and a file citation, plus one sweep
finding:

1. **Calico CNI locally** to actually prove NetworkPolicy enforcement — and the status change worth
   recording: **the stated prerequisite is now cleared** by the D-17 fix + INV-3, so what remains is a
   host-gateway egress rule for the CIDR/ports named in the runbook.
2. **Env-contract gate coverage for `edge-go` (`os.Getenv`) and the frontend (`process.env`)** — the
   gate is core-java only; Phase 26 found a concrete instance the limit hid (`JWT_EXPECTED_ISSUER` read
   by edge-go since the issuer/JWKS fix, supplied by no manifest).
3. **The customer-storefront realm is unconfigured in EVERY k8s environment**
   (`CUSTOMER_KC_ISSUER_URI`, `CUSTOMER_JWT_EXPECTED_ISSUER`, `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL`).
4. **Sealed-secrets / external-secrets for local** — locked out by `.planning/PROJECT.md:141`.
5. **No `mcp-server` k8s manifest set** — needs its own phase (manifests + scoped credential +
   NetworkPolicy row + ingress decision + connection-budget effect).
6. **An `emptyDir` at `/var/log/jtoye` in the base** as the durable PIT-5 fix; local only sets
   `log.path: /tmp`, and the base default is deliberately unchanged.
7. **`OLLAMA_URL` and `ZIPKIN_ENDPOINT`** remain reasoned allowlisted omissions in
   `check-env-contract.sh`.
8. *(sweep finding, Rule 2)* **`.planning/PROJECT.md:128` still quotes a 1257 logical-invocation
   baseline** as a live constraint (stale by 441), and `:165` quotes 1684 in a dated Phase 25
   narrative. Neither is read by the docs-freshness gate, which is why they drifted. Recorded for the
   milestone-close pass rather than edited here.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The plan's append-only criterion is unsatisfiable as written**
- **Found during:** Task 1, verifying the readiness-report append
- **Issue:** `git diff <file> | grep -c '^-'` returns **0** was the stated criterion. `git diff` emits
  its own `--- a/<path>` header line, which begins with a hyphen — so a **pure append scores 1** and a
  line-deleting change scores 2. The criterion can never be satisfied by any modification, and it
  cannot distinguish an append from a rewrite, which is the property it was supposed to assert.
- **Fix:** replaced with `git diff --numstat` and asserted the **deletions** column is 0. Proven
  falsifiable rather than assumed: with the clean append it reports `116 0`; after deliberately
  deleting one pre-existing line (`**Auditor:** DevOps Team`) it reports `116 1`, while the plan's grep
  form went only 1 → 2. Restored from a scratchpad `cp` backup and confirmed byte-identical with `cmp`
  — **`git checkout --` was never used on an uncommitted file** (26-04's process incident).
- **Files modified:** none (a verification-method correction)
- **Commit:** `52e5660`

**2. [Rule 1 - Bug] The plan's secret-value sweep is weak against this dev `.env`**
- **Found during:** Task 1, the pre-commit leak check
- **Issue:** "for each `.env` credential name, grep the file for its decoded value and expect zero
  hits" reported **8 hits**. Diagnosis (performed without printing any value, by SHA-256 prefix):
  `POSTGRES_PASSWORD` / `DB_PASSWORD` / `KC_DB_PASSWORD` all hold the six-character English word
  `secret`; `POSTGRES_EXPORTER_PASSWORD` holds `placeholder`; and `DB_USER` (`jtoye_app`),
  `KEYCLOAK_CLIENT_ID` (`core-api`), `K8S_LOCAL_POD_HOST` (`host.minikube.internal`) and
  `K8S_LOCAL_BACKUP_BUCKET` (`jtoye-db-backups`) are non-credential identifiers this phase is
  *required* to name. So the criterion fires on ordinary prose ("Create Secrets", "`REPLACE_WITH_*`
  placeholder") and cannot detect a real leak among the noise.
- **Fix:** replaced with a sweep that classifies each hit against the committed tree — a value already
  present in `HEAD` outside `.env*` is repository vocabulary, not a new disclosure. Result: **6
  pre-existing-vocabulary hits, 0 genuine leaks.**
- **Files modified:** none (a verification-method correction)
- **Commit:** `52e5660`

**3. [Rule 1 - Bug] `./gradlew :core-java:test` returned UP-TO-DATE — BUILD SUCCESSFUL executing nothing**
- **Found during:** Task 2 verification
- **Issue:** the plan's criterion is "`./gradlew :core-java:test --no-daemon` BUILD SUCCESSFUL (the
  count the manifest now claims is a count of passing tests)". The first invocation reported
  `> Task :core-java:test UP-TO-DATE` / `BUILD SUCCESSFUL in 5s` / `5 actionable tasks: 5 up-to-date`
  — green while running no test at all. That is precisely the green-by-construction class this
  repository keeps catching.
- **Fix:** re-ran as `:core-java:cleanTest :core-java:test --no-daemon` (45s, `2 executed`) and read
  the counts from the JUnit XML, checking the mtimes to prove they came from this run.
- **Files modified:** none
- **Commit:** `d622e9c`

**4. [Rule 1 - Bug] The obvious test-results directory is a stale 2025 artifact reporting 3 failures**
- **Found during:** Task 2 verification, immediately after fix 3
- **Issue:** `core-java/build/test-results/test/` contains 4 XML files dated **2025-12-27** totalling
  **6 tests / 3 failures**. Reading it would have produced a **false RED** on a plan that changes no
  source. `core-java/build.gradle.kts:15` redirects the build directory:
  `layout.buildDirectory.set(file("build-local"))`.
- **Fix:** counts taken from `core-java/build-local/test-results/test/` — 104 suite files, **all**
  written `2026-07-25T19:31:44Z`.
- **Files modified:** none
- **Commit:** `d622e9c`

**5. [Rule 2 - Missing critical documentation] The staleness sweep found `.planning/PROJECT.md` drift**
- **Found during:** Task 2's systematic stale-reference sweep (QUALITY_RULE_6)
- **Issue:** `.planning/PROJECT.md:128` still asserts "baseline is 1257 logical invocations" as a live
  constraint. The docs-freshness gate does not read `PROJECT.md`, so nothing catches it.
- **Fix:** recorded as a dated deferred item rather than edited — `PROJECT.md` is outside this plan's
  declared file list, and line 165 is a dated Phase 25 narrative this repo appends to rather than
  rewrites. The sweep found no other stale count outside `.planning/`, no stale gate count
  (`k8s/DEPLOYMENT.md` correctly says "Five scripts"), and no surviving "there is no `k8s/local`
  overlay" claim (26-05 fixed `scripts/deploy.sh`).
- **Files modified:** `.planning/phases/26-.../deferred-items.md`
- **Commit:** `d622e9c`

## Verification

| Check | Command | Result |
|---|---|---|
| docs-freshness check mode | `bash scripts/docs-freshness.sh` | **exit 0** — `metrics match source (total logical invocations: 1698)` |
| `schema_version` unchanged | `jq -r '.schema_version' docs/metrics.json` | **59** |
| Java delta is exactly the 26-01 file | JUnit XML of `StompCredentialResolutionTest` | `tests="8" failures="0"` → 1143+8 = **1151**, files 201+1 = **202** |
| Playwright unchanged | `jq` | **40 / 11** |
| Total is the component sum | `jq` arithmetic | **1698 == 1698** |
| Prose lines byte-identical | `diff <(sed -n 15p CLAUDE.md) <(sed -n 15p AGENTS.md)` | **no output** |
| Stale prose gone | `grep -c '1684'`, `grep -c '376 Jest'` | **0 / 0** in both files |
| Runbook length | `wc -l < k8s/LOCAL.md` | **550** (≥ 150) |
| All 11 sections present | `grep '^## '` | 11 numbered sections + Related documents |
| XOR rule precise | `grep -cF 'compose XOR k8s'` | **2**, with all 4 app services and all 6 backing services named in the §2 table |
| PIT-1 stated | `grep -cF 'v1.12.2'` / `'allow-snippet-annotations'` | **3 / 3**, with the do-not-enable instruction |
| PIT-7 concrete | `for s in 192.168.0.0/16 172.16.0.0/12 10.0.0.0/8 61613 5433 8085 9000 1025` | **nothing missing** |
| kube-dns finding | `grep -cF 'kube-dns'` | **3** — states it was live in `k8s/production`, is fixed, and is asserted by INV-3 |
| Python-script limitation | `grep -cF 'validate-networkpolicies.py'` | **1**, with raw-files-not-render stated |
| P-6 caveat | `grep -cF 'immutable'` | **5**, with the `field is immutable` apply symptom |
| PIT-13 both cases | `grep -cF 'CreateContainerConfigError'` | **3**, non-optional vs `optional: true` distinguished |
| Backup falsification | `grep -cF 'products = 0'` | **2**, both arms tabulated |
| Evidence template | `grep -c '^\*\*L[0-9]'` vs live rows in 26-VALIDATION | **7 == 7** |
| Evidence-invalidating rule | `grep -cF 'localhost:9090'` | **1**, in its own subsection |
| Deploy docs cross-linked | `grep -c 'LOCAL.md'` | QUICK_START **2**, DEPLOYMENT **2** |
| Dated docs append-only | `git diff --numstat` | **116/0**, **37/0** — zero deletions |
| CronJob checkbox untouched | `grep -n 'CronJob completes'` | line 293, still `- [ ]` |
| No top-level deferred file | `test ! -f .planning/deferred-items.md` | passes |
| `k8s/local` + `scripts/` untouched | `git diff --quiet k8s/local scripts/` | true |
| Five static gates | all five `k8s/scripts/*.sh` | **ALL_GATES_GREEN** (baseline before, and after each task) |
| Unit suite | `./gradlew :core-java:cleanTest :core-java:test --no-daemon` | **BUILD SUCCESSFUL — 767 tests / 0 failures / 0 errors / 1 skipped**, 104 suites, all XML at 2026-07-25T19:31:44Z |
| No new secret value | classified sweep over all touched docs | 6 pre-existing-vocabulary hits, **0 genuine leaks** |

**Honest boundary on the test claim.** `:core-java:test` excludes `@Tag("testcontainers")`, so the
**767** executed this session is a *subset* of the **1151** `@Test` methods the manifest counts. The
integration half was not re-run — this plan changes no source at all, only Markdown and a generated
manifest. The on-disk `integrationTest` results from **2026-07-24T18:42:31Z** report **392 tests / 0
failures / 0 errors**, giving an aggregate of 1159 executed against 1151 counted methods. Stated
rather than implied, because "the manifest counts passing tests" is only fully discharged by running
both tasks.

**Tooling absent, recorded not silently skipped:** `gitleaks` is not installed on this host, so the
leak check was the classified `.env`-value sweep above rather than a scanner run. `python3` remains
blocked by a repo hook (base-conda guard), so all XML/YAML aggregation used `awk`.

## Constraint compliance

- **STATIC SIDE ONLY, fully honoured.** No `minikube start`, no `minikube ssh`, no compose container
  stopped or started, no DB role / bucket / Secret created, no `kubectl apply`, no
  `--dry-run=server`. The only cluster-adjacent commands were `kubectl kustomize k8s/local` (a pure
  local render) and `kubectl version --client`. The employer AKS context `sipbihs2aks` was never
  targeted or even consulted.
- **No fabricated evidence.** Every `Actual` field in §11 is empty. No plausible-looking output was
  pre-filled anywhere, and each row names the plan that owns it.
- **`k8s/local` and `scripts/` untouched** — `git diff --quiet k8s/local scripts/` is true across both
  commits.
- **All five CI gates green** before Task 1, after Task 1 and after Task 2.
- **Dated documents appended to, never rewritten** — zero deletions on the signed audit and on the
  backups runbook, proven with a falsifiable measure.
- **Credentials as names only.** No `.env` value appears in any added line that is not already
  repository vocabulary; the runbook's credential steps reference variable names
  (`DB_BACKUP_PASSWORD`, `KC_SEED_USER_PASSWORD`, `NOTIFICATION_UNSUBSCRIBE_SECRET`) and never values.
- **Single writer of `docs/metrics.json` respected** — this is the phase's only write to it, made via
  `--write` and never by hand.

## Requirements

**INFRA-01 and INFRA-02 are deliberately NOT marked complete — anti-false-green.** This plan closes
the documentation half only. Their acceptance includes live proofs that only plan 26-07 (server
dry-run, rollout READY, NOSUPERUSER boot, no guest rejection, in-cluster CronJob + the two-arm restore)
and plan 26-08 (the STOMP relay event and the real vendor login through the ingress) can produce.
`requirements-completed` is therefore empty.

## Next

**Plan 26-07** — the live rehearsal, behind its `checkpoint:human-action`. It owns:

- Filling evidence rows **L1–L5** of `k8s/LOCAL.md` §11, using the copy-pasteable block
  `scripts/k8s-local-up.sh` step 12 prints (which already carries the four image identities, the
  namespace, the resolved ingress hosts and the date).
- Ticking the `- [ ] CronJob completes **in-cluster** (exit 0)` checkbox in
  `docs/runbooks/backups.md`, left deliberately untouched here.
- Running the two-arm backup falsification and recording **both** counts.

**Plan 26-08** owns rows **L6–L7**. Note for both: a `localhost:9090` anywhere in captured output
invalidates the run, and a row with no image identity recorded is not a pass.

## Self-Check: PASSED

Verified after writing this summary:

- `k8s/LOCAL.md` — FOUND (550 lines)
- `k8s/PRODUCTION_READINESS_REPORT.md`, `docs/runbooks/backups.md`, `k8s/QUICK_START.md`,
  `k8s/DEPLOYMENT.md`, `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md`,
  `.planning/phases/26-.../deferred-items.md` — all FOUND and modified
- `.planning/deferred-items.md` — correctly ABSENT
- Commit `52e5660` — FOUND
- Commit `d622e9c` — FOUND
