---
phase: 28-security-triage-the-dev-prod-boundary
plan: 02
subsystem: security / api-contract
tags: [SEC-03, openapi, springdoc, tenant-header, falsifiability, D-14, "#549", "#440", "#442"]
requires:
  - TenantHeaderSchemeCustomizer (issue #440, already shipped)
  - SecurityConfig profile gating (issue #442, already shipped)
  - IntegrationTestSupport.registerPostgresTestProperties
provides:
  - TenantHeaderAbsentDocumentTest (three-arm assertion on the SERVED /v3/api-docs document)
  - fail-direction evidence for the D-14 / #549 staging+prod doc-endpoint gating
  - C3 disposition record for plan 28-05's triage doc
affects:
  - core-java integrationTest suite (+1 file, +3 @Test methods)
tech-stack:
  added: []
  patterns:
    - "bean-DEFINITION removal via ApplicationContextInitializer to reproduce a deployed-profile context shape"
    - "@Nested Spring contexts for claim / control / denominator arms in one class"
key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/config/TenantHeaderAbsentDocumentTest.java
  modified: []
decisions:
  - "SC-3's 'CI gate' is satisfied by integrationTest, not a scripts/check-*.sh"
  - "C3 disposition = FIXED-BY-#442; #549's staging-is-unauthenticated description is stale"
  - "docs/metrics.json deliberately NOT updated — plan 28-11 owns the manifest"
metrics:
  duration: ~25 min
  completed: 2026-08-10
  tasks: 2
  commits: 1
  tests_added: 3
---

# Phase 28 Plan 02: SEC-03 Served-Document Assertion + D-14 Fail Direction Summary

Proved that the OpenAPI document springdoc actually **serves** omits the dev-only `X-Tenant-Id`
override when `TenantFilter` is absent — with a filter-present control, a non-empty-`paths`
denominator, and **three** independently observed red break arms across the two tasks.

## What Shipped

**One new file. Zero source changes.** Task 2 is a proof task by design (RESEARCH DEC-2 measured
D-14 as already shipped by #442), so its deliverable is recorded evidence, not a diff.

| Task | Artifact | Commit |
|------|----------|--------|
| 1 | `core-java/src/test/java/uk/jtoye/core/config/TenantHeaderAbsentDocumentTest.java` | `10763d4b` |
| 2 | none by design — break-arm evidence only, `SecurityConfig.java` byte-identical to HEAD | — |

## Task 1 — the served document, three arms

`TenantHeaderSchemeCustomizerTest` already proved the strip on the OpenAPI **model object** in both
directions, and its own javadoc records that it deliberately does not go through `/v3/api-docs`. The
open link was that springdoc applies the customizer to what it **serves**. Every assertion in the
new class is on the served response **string**, never on the model.

The filter is removed by **bean definition**, not by profile, because the customizer keys off
`ObjectProvider<TenantFilter>.getIfAvailable()` — removal reproduces the deployed-profile condition
exactly while `OpenApiConfig` stays loaded (it is `@Profile("!prod")`).

### Arm results (CLEAN)

| Arm | Context | Assertion | Result |
|-----|---------|-----------|--------|
| 1 (claim) | filter bean removed | served string contains neither `TenantFilter.TENANT_HEADER` nor `tenant-header` | PASS |
| 2 (control) | stock `test`, filter present | served string contains **both** | PASS |
| 3 (denominator) | filter bean removed | served `paths` non-empty | PASS — **observed 107 paths** (floor 50) |

Per-class, from `build-local/test-results/integrationTest/`:

```
TenantHeaderAbsentDocumentTest$FilterAbsent    tests="2" failures="0"   (arms 1 + 3)
TenantHeaderAbsentDocumentTest$FilterPresent   tests="1" failures="0"   (arm 2)
```

All three executed **by name** — recorded, not inferred from a green class:

```
arm 1: the SERVED document advertises neither the header nor the scheme
arm 2: with the filter present the SERVED document retains both strings
arm 3: the served document is non-empty, so arm 1 cannot pass vacuously
```

### FAIL DIRECTION 1 — the strip (`TenantHeaderSchemeCustomizer.customise`)

Neutralised with `if (true) { return; }` as the first statement.

| State | Result |
|-------|--------|
| CLEAN | 3 tests, 0 failures |
| BREAK | **3 tests, 1 failed** — `FilterAbsent` `failures="1"`, `FilterPresent` `failures="0"` |
| RESTORE | `git hash-object` = `git rev-parse HEAD:<path>` = `95e442b20bbf391bea79e6809cb492424470c37d` |
| CLEAN AGAIN | 3 tests, 0 failures |

Red output, verbatim:

```
java.lang.AssertionError: [the served /v3/api-docs document still names the X-Tenant-Id override
header, but TenantFilter is absent from this context — the document is advertising a tenant
mechanism the running service does not honour]
```

Signature is exactly right: **only arm 1 detects the strip regression.** Arm 2 stays green (with the
filter present the customizer is a no-op either way) and arm 3 stays green (the document is still
built). A break arm that reddened all three would have meant the arms were not independent.

### FAIL DIRECTION 2 — the harness (bean removal made a no-op)

Skipped the `removeBeanDefinition` call **and** its post-condition guard — the guard alone would have
thrown, which would have measured the guard rather than arm 1.

| State | Result |
|-------|--------|
| BREAK | **`FilterAbsent` tests="2" failures="1"**, `FilterPresent` failures="0" |
| RESTORE | `git hash-object` = `git rev-parse HEAD:<path>` = `d35e4999e8669f7d002b4e34a52845b7fb0f3f7d` |
| CLEAN AGAIN | 3 tests, 0 failures |

Red on arm 1's **own assertion** (same `X-Tenant-Id` message), which is the point: arm 1 is measuring
the bean removal, not an unrelated context difference between the two `@Nested` contexts.

### Harness hazards closed in the artifact

- **Post-processor ordering.** A `BeanDefinitionRegistryPostProcessor` registered from an
  `ApplicationContextInitializer` has its registry callback invoked **before** component scanning,
  where there is nothing named `tenantFilter` to remove. The remover is therefore a plain
  `BeanFactoryPostProcessor`, which Spring defers until after all registry work. Getting this
  backwards is not a loud failure by default — it is a removal that finds nothing.
- **Fail-loud removal.** Throws if the name is absent, if there is not exactly one
  `TenantFilter`-typed bean, or if any survives removal. A silent no-op removal would leave arm 1
  asserting nothing.
- **No copied literal.** The test references `TenantFilter.TENANT_HEADER` (5 occurrences) and
  `TenantHeaderSchemeCustomizer.SCHEME_NAME`. Verified `"X-Tenant-Id"` as a quoted literal is absent
  — **with a positive control**, since an empty grep is evidence about the pattern: the same
  `git grep -c -F '"X-Tenant-Id"'` returns `1` against `docs/api/openapi-snapshot.json`.

### SC-3's "CI gate", stated explicitly

The ROADMAP wording asks for "a CI gate shown to fail". **`integrationTest` runs in CI on every
PR/push, so `TenantHeaderAbsentDocumentTest` IS that gate** — this is a deliberate substitution and
is recorded here rather than left implicit.

A `scripts/check-*.sh` was **rejected**: it needs no running stack, it belongs in `integrationTest`,
and it would immediately owe `scripts/gates/gate-enforcement.conf` an entry whose stated bar ("a
hosted runner does not have the thing this inspects") is simply false here. Confirmed no new gate
script was added: `bash scripts/check-gate-enforcement.sh` → `rc=0`, `gates: 33`,
`PASS: every gate either runs in CI or has a declared reason it cannot.`

Also confirmed: grepping `docs/api/openapi-snapshot.json` for `X-Tenant-Id` would have been the wrong
gate (RESEARCH DEC-3). It legitimately contains the string **twice** — the scheme's `name` field and
the description prose — because `OpenApiSnapshotTest` boots under `test`, where `TenantFilter` IS
present and the advertisement IS accurate.

## Task 2 — D-14 / #549 fail direction

D-14 asked for a config change. **RESEARCH DEC-2 measured that the change already shipped in #442**,
and the both-direction profile-parameterised test it asks for already exists as three classes. So no
config change was written — there is nothing to change, and a task that changes nothing would read as
done while proving nothing. What was genuinely missing is the fail direction.

Protocol run in full, CLEAN → BREAK → RESTORE → CLEAN AGAIN:

| State | `OpenApiDevProfileGatingTest` | `StagingActuatorPortIsolationTest` | `OpenApiProdProfileGatingTest` |
|-------|---|---|---|
| CLEAN | 1 test, 0 fail | 4 tests, 0 fail | 3 tests, 0 fail |
| BREAK | 1 test, 0 fail | **4 tests, 1 FAIL** | 3 tests, 0 fail |
| CLEAN AGAIN | 1 test, 0 fail | 4 tests, 0 fail | 3 tests, 0 fail |

**BREAK:** `SecurityConfig.java:194` reverted to the pre-#442 shape — `if (!active.contains("prod"))`
in place of `if (looksLocal && !isDeployedProfile)`, so `staging` matches the `permitAll` again.
Edited against a uniqueness check first (`git grep -c -F` on the condition returned exactly **1**,
at line 194 — the live condition, not an explanatory comment) and **read back by line number** after
the edit. This is the 33-07 trap the plan names, and it was avoided by measurement rather than care.

Failing test and message, verbatim:

```
apiDocsNotAnonymousInStaging()
org.opentest4j.AssertionFailedError: /v3/api-docs answered 200 to an UNAUTHENTICATED caller under
the staging profile — staging enables springdoc, so this is a real anonymous read of the API
surface ==> expected: not equal but was: <200>
```

The break arm is **not** green, so the criterion is genuinely falsifiable — no substitution was
needed and none was made. Note the single-arm signature: dev stays 200 (still green) and prod stays
gated (still green), so the staging arm is the only one that detects this regression, exactly as
`StagingActuatorPortIsolationTest`'s own javadoc predicts for its sibling `configprops` assertion.

**RESTORE:** verified by content **and** by hash —
`git hash-object` = `git rev-parse HEAD:<path>` = `d764d243d1a768b63859203279b35377a0db397f`, and
`git grep -n -F 'if (looksLocal && !isDeployedProfile) {'` returns line **194** again.
`git status --short` at task end: **empty** — no modification to `SecurityConfig.java`.

### C3 disposition — for plan 28-05's triage doc

Sanitized (finding ID only), ready to transcribe:

> **C3 — disposition: FIXED-BY-#442.** The staging/prod OpenAPI-endpoint gating shipped in #442 as
> `looksLocal && !isDeployedProfile` (`SecurityConfig.java:190-196`), not as a `!isProd` condition.
> Evidence: three permanent `integrationTest` classes cover dev→200 / staging→not-200 / prod→not-200,
> and the control is falsifiable — reverting the condition to the pre-#442 `!prod` shape turns
> `StagingActuatorPortIsolationTest.apiDocsNotAnonymousInStaging` **red** with
> `expected: not equal but was: <200>`; restore verified by content hash; clean-again arm green.
> **#549's description of staging as unauthenticated is stale.**

**#549 is deliberately NOT closed here** — 28-05 closes it pointing at the triage doc, so the closure
and the record land together.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Wrong package for `BeanFactoryPostProcessor`**
- **Found during:** Task 1, first `compileTestJava`
- **Issue:** imported `org.springframework.beans.factory.BeanFactoryPostProcessor`; the type lives in
  `org.springframework.beans.factory.config`. 3 compile errors.
- **Fix:** corrected the import.
- **Files modified:** the new test file, pre-commit.
- **Commit:** folded into `10763d4b` (the error never reached a commit).

### Deliberate structural choice (not a deviation, recorded for the reviewer)

The plan specifies "a `BeanDefinitionRegistryPostProcessor` calling `removeBeanDefinition`";
RESEARCH Pattern 2 specifies "a `BeanFactoryPostProcessor` that calls `removeBeanDefinition`". Both
agree on the `removeBeanDefinition` call (the plan's `key_links` pattern). I implemented the
**`BeanFactoryPostProcessor`** form because the BDRPP registry callback provably runs *before*
component scanning when registered from an `ApplicationContextInitializer` — the definition would not
yet exist. The `key_links` contract (`removeBeanDefinition`, `get\("/v3/api-docs"\)`) is satisfied.

## Cross-Plan Dependency — manifest NOT updated (by instruction)

This plan adds **+1 Java test file** and **+3 `@Test` methods** and deliberately leaves
`docs/metrics.json`, `CLAUDE.md`, `AGENTS.md` and `README.md` untouched — **plan 28-11 owns the
manifest.** Confirmed: `git status --short` is empty and none of those four files appear in any diff
from this plan.

**28-11 must regenerate, not do arithmetic** (`trap_docs_freshness_block_counter`):
`scripts/docs-freshness.sh --write`. Baseline at this plan's HEAD was
`java_test_methods: 1595`, `java_test_files: 256`, `total_logical_invocations: 2769`.

A counting hazard worth carrying forward: the new file contains **4** lines matching a naive `@Test`
substring but only **3** test methods — the extra is `@Testcontainers`. The gate's real expression is
`@Test\b`, which correctly returns **3** (verified with the gate's own pattern). Until 28-11 runs,
`docs-freshness` is expected to be red on this branch; that is the planned state, not a defect.

## Verification

| Check | Result |
|-------|--------|
| `integrationTest --tests '*TenantHeaderAbsentDocumentTest*'` | 3 tests, 0 failures |
| Same, plus the three gating classes (11 tests total) | **BUILD SUCCESSFUL**, 0 failures |
| `bash scripts/check-gate-enforcement.sh` | `rc=0` — unchanged, 33 gates |
| `git status --short` at plan end | empty (before SUMMARY) |
| New `scripts/check-*.sh` created | none |
| `docs/metrics.json` / `CLAUDE.md` / `AGENTS.md` / `README.md` modified | none |

Break arms observed red: **3** (2 in Task 1, 1 in Task 2), each with a content-hash-verified restore
and a re-run clean arm.

## Threat Model Outcome

| Threat ID | Disposition | Evidence |
|-----------|-------------|----------|
| T-28-05 | mitigated | arm 1 on the SERVED document + arm 2 filter-present control; break arm 1 red |
| T-28-06 | mitigated | Task 2 CLEAN/BREAK/RESTORE/CLEAN — the shipped #442 control is now *trusted* rather than assumed |
| T-28-07 | mitigated | four classes, 11 tests, all in `integrationTest` which runs in CI |
| T-28-08 | mitigated | arm 3 denominator (107 paths, floor 50) + arm 2 control |

Cross-cutting: web-perf **N/A**, SEO **N/A** (the doc endpoint is non-200 on deployed profiles),
agent-readiness **preserved** — arm 2 asserts the contract still advertises the header on the
profiles where the mechanism genuinely exists, so the document keeps matching live responses.
Falsifiable evidence: **3 break arms, 3 hash-verified restores, 3 clean-again arms.**

## Known Stubs

None. No placeholder values, no hardcoded empties, no TODO/FIXME introduced.

## Threat Flags

None — this plan adds no network endpoint, auth path, file access pattern or schema change.
Its only artifact is a test.

## Notes for the Orchestrator

- Ran entirely in the worktree. **No `docker compose` / `check-runtime-freshness.sh` was executed**
  (the worktree directory name yields a different compose project name — `trap_compose_project_name_from_directory`).
  This plan is Gradle/Testcontainers only, so nothing was deferred by that constraint.
- Testcontainers spins its own throwaway Postgres per test class, which is project-name independent
  and therefore unaffected by the worktree.
- STATE.md / ROADMAP.md deliberately untouched — orchestrator owns those.

## Self-Check: PASSED

| Claim | Verification | Result |
|-------|--------------|--------|
| `core-java/src/test/java/uk/jtoye/core/config/TenantHeaderAbsentDocumentTest.java` exists | `test -f` | FOUND |
| `.planning/phases/28-security-triage-the-dev-prod-boundary/28-02-SUMMARY.md` exists | `test -f` | FOUND |
| Task 1 commit `10763d4b` | `git log --oneline` | FOUND |
| SUMMARY commit `3f9d50a9` | `git log --oneline` | FOUND |
| `SecurityConfig.java` byte-identical to HEAD | `git hash-object` = `git rev-parse HEAD:<path>` = `d764d243d1a768b63859203279b35377a0db397f` | MATCH |
| `TenantHeaderSchemeCustomizer.java` byte-identical to HEAD | hash = `95e442b20bbf391bea79e6809cb492424470c37d` | MATCH |
| Working tree clean | `git status --short` | empty |
