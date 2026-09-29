---
phase: quick/260929-i9c
plan: 01
status: complete
subsystem: core-java / dependency management
tags: [security, cve, jackson, trivy, image-gate, bom-override]
requires: []
provides:
  - "extra[\"jackson-bom.version\"] = \"2.21.6\" override in core-java/build.gradle.kts"
affects:
  - core-java image (app.jar BOOT-INF/lib jackson family)
  - .planning/codebase/{STACK,TESTING,CONCERNS}.md citations
tech-stack:
  added: []
  patterns: ["Boot BOM property override (same shape as netty/httpcore5/tomcat/rabbit-amqp-client)"]
key-files:
  created: []
  modified:
    - core-java/build.gradle.kts
    - docs/CHANGELOG.md
    - .planning/codebase/STACK.md
    - .planning/codebase/TESTING.md
    - .planning/codebase/CONCERNS.md
decisions:
  - "jackson-bom.version pinned to 2.21.6 (smallest clearing bump; 2.21.7 / 2.22.x not taken) — LOCKED by plan"
  - "Override the BOM property, not a forced artifact; no direct jackson dependency added"
metrics:
  started: 2026-09-29T12:21:02Z
  completed: 2026-09-29T12:36Z
  duration: ~15 min
branch: feature/jackson-2.21.6-cve-2026-68497
plan_head_before: db725c9482d2d2356f9f168c401417c56d9cc234
actuals:
  tokens: 4449     # chars/4 over git diff db725c94..HEAD (17796 chars)
  tasks: 3
  commits: 2       # git rev-list --count db725c94..HEAD
---

# Phase quick/260929-i9c Plan 01: Pin jackson-bom to 2.21.6 Summary

**The core-java image now ships jackson-databind 2.21.6 instead of 2.21.4. The fix is one Boot-BOM property override, `extra["jackson-bom.version"] = "2.21.6"`. Both directions were proven at the classpath, the boot jar, inside built images, and with CI's exact Trivy 0.70.0 image gate on one DB: before rc=1 naming CVE-2026-68497, after rc=0 with app.jar analysed.**

## Commits (not pushed)

| Commit | Subject | Files |
|---|---|---|
| `1c587f061f092ed4fdc22c1cc847d89f01058da9` | fix(deps): pin jackson-bom to 2.21.6 so core-java stops shipping CVE-2026-68497 | core-java/build.gradle.kts (+46 / -0) |
| `f8773549ba6ba8262d5803bc66cde937f081731f` | docs(deps): changelog the jackson 2.21.6 pin and re-point the build.gradle.kts citations it shifted | docs/CHANGELOG.md, .planning/codebase/{STACK,TESTING,CONCERNS}.md (+60 / -5) |

Both messages were read back with `git log -1 --format=%B`. Neither has a Co-Authored-By, Claude-Session or "Generated with" line. Deletion check `git diff --diff-filter=D HEAD~2 HEAD` is empty.

The final file for citation purposes is `core-java/build.gradle.kts` at 1c587f06:
- `wc -l` = **531** (485 on db725c94, so N = 46).
- The block's first comment line is **118**, `// Override the Jackson family managed by Spring Boot 3.5.16's BOM...`.
- The `extra["jackson-bom.version"] = "2.21.6"` line is **162**.
- Evidence: `t1-final-file-lines.txt`.

## Criteria: both arms

### Task 1: classpath and boot jar

| Criterion | Fail / before arm | Pass / after arm | Evidence |
|---|---|---|---|
| dependencyInsight jackson-databind, runtimeClasspath | db725c94: `2.21.4`, Selected by rule (gradle rc=0) | HEAD: `2.21.6`, Selected by rule (rc=0) | t1-insight-before.txt / t1-insight-after.txt |
| Near-miss key is load-bearing | `extra["jackson.bom.version"] = "2.21.6"` in the working tree gives `2.21.4` (rc=0) | correct key gives `2.21.6` | t1-insight-nearmiss.txt, t1-nearmiss-diff.txt |
| Near-miss key really undeclared | `rg -uu -F 'jackson.bom.version'` gives rc=1 in the Boot 3.5.16 pom, jackson-bom-2.21.4, jackson-parent-2.21 and oss-parent-75 (the top of the chain, which has no parent) | positive control `jackson-bom.version` in the Boot pom gives rc=0 (lines 79, 2190) | t1-nearmiss-key-proof.txt |
| Restore after near-miss (clean → arm → clean) | n/a | `git hash-object` = HEAD blob `40712282…`, and the re-run insight gives `2.21.4` | t1-restore-proof.txt, t1-insight-restored.txt |
| Whole family moves; no 2.21.4 left | before: `rg -uu -c 2.21.4` = **8** (8 of 9 family lines) | after: count empty, rc=1. Every com.fasterxml.jackson artifact is at 2.21.6 except jackson-annotations `2.21` | t1-family-before.txt / t1-family-after.txt / t1-family-check.txt |
| jackson-annotations `2.21` is BOM versioning | jackson-bom-2.21.4.pom:62 `<jackson.version.annotations>2.21` | jackson-bom-2.21.6.pom:62, the same line | t1-family-after.txt |
| Boot jar BOOT-INF/lib | before: `jackson-databind-2.21.4.jar` plus 6 more `-2.21.4.jar` (7 total) | after: `jackson-databind-2.21.6.jar`, `-2.21.4.jar` count empty (rc=1). The jar mtime postdates the marker in both arms | t1-bootjar-before.txt / t1-bootjar-after.txt |
| No direct jackson dependency (comment lines filtered) | scratch copy with one `implementation("…jackson-databind:2.21.6")` appended gives **1**. Scratch deleted | HEAD gives **0**. Control: 5 comment lines mention jackson-databind, so the filter matters | t1-direct-dep-check.txt |
| Exactly one non-comment pin line | `git show db725c94:…` gives **0** | HEAD gives **1** | t1-direct-dep-check.txt |
| Supply chain (T-i9c-01) | the 2.21.4 jar sha1 `09e49568…` differs | cached 2.21.6 jar sha1 = cache dir name = Maven Central `.sha1` = `90fc0c39cc03058141d4312ccadb431a583d6574` (curl rc=0) | t1-integrity.txt |

Tracer gate: Task 1's `<verify>` was re-run end to end. Gradle rc=0, and there are 4 lines of `jackson-databind:2.21.6`. **Instrument note:** the plan's `grep -F -c 'selected by rule'` returns **0**, because Gradle prints `Selected by rule` with a capital S. The case-insensitive count is 1. The criterion holds, but that exact line of the plan's verify cannot pass on a correct tree as written.

The after-arm insight also shows `jackson-databind:2.21.4 -> 2.21.6`. That is Boot's own starter-json and actuator-autoconfigure POMs requesting 2.21.4, overridden by the rule. It is expected, not a leftover.

### Task 2: images, Trivy, unit suite

- **Build contexts.** The contexts were `git archive` of the Dockerfile's COPY paths from db725c94 and from HEAD.
  - Pin-line count in the extracted build files: before 0, after 1.
  - `docker build --platform linux/amd64` gave rc=0 for both arms (t2-build-rcs.txt, t2-build-*.log).
- **Jars read from inside each image** (`docker run --rm --entrypoint sh … unzip -l /app/app.jar`, rc=0):
  - before: `jackson-databind-2.21.4.jar`.
  - after: `jackson-databind-2.21.6.jar`, and no `-2.21.4.jar` (rc=1).
  - Evidence: t2-image-jars-{before,after}.txt.
- **Trivy.** Tool: `aquasec/trivy:0.70.0`. Flags: `image --input <tar> --severity CRITICAL,HIGH --ignore-unfixed --pkg-types os,library --exit-code 1 --format table`. It ran on a `docker save` tarball through a read-only mount, with no docker socket. The cache was fresh and shared, and the after arm did not re-download.
  - **BEFORE: rc=1.** Alpine 3.24.2 has 0 findings. `app/app.jar` has exactly 1: `com.fasterxml.jackson.core:jackson-databind (app.jar) | CVE-2026-68497 | HIGH | fixed | 2.21.4 | 2.18.10, 2.21.6, 2.22.2`. The DB download succeeded, so this rc=1 is the finding and not a FATAL.
  - **AFTER: rc=0.** The Report Summary lists `app/app.jar  jar  0`, so the jar was analysed, and Alpine is at 0. There is no jackson row and no other finding, so the **STOP condition did not fire**.
  - DB timestamps: vulnerability DB v2 UpdatedAt 2026-09-29 06:53:54 UTC. Java DB v1 UpdatedAt 2026-09-29 01:08:11 UTC.
  - Evidence: t2-trivy-before.txt, t2-trivy-after.txt, t2-trivy-rcs.txt, t2-trivy-version.txt.
- **Unit suite**, run as `./gradlew :core-java:cleanTest :core-java:test`, rc=0, `> Task :core-java:test` executed:
  - Results: **162 fresh XMLs, 1233 tests, 0 failures, 0 errors, 1 skipped**. That is identical to #757's reference. `cleanTest` reported UP-TO-DATE only because this fresh worktree had no prior results.
  - Pre-run state: the results dir did not exist, so there were 0 XMLs of any age.
  - A first pre-run attempt printed "1". That came from `grep -c .` counting find's own "No such file" error line, so it was invalid and is recorded and discarded.
  - The `-newer` filter was then proven on synthetic files. A stale-only XML gave 0 fresh, and adding one XML touched after the marker gave 1.
  - Evidence: t2-unit.log, t2-unit-totals.txt.
- **Isolation guard (T-i9c-03).** The other checkout's 4 `jtoye_oaas_2026-*` image IDs and 10 container name/image/ID lines match before and after: diff rc=0 on both. As the fail arm, a copy with one ID character altered gave diff rc=1. No `docker compose` was run. Evidence: t2-compose-guard.txt and t2-compose-*-{before,after}.txt.
- **Delegated to CI on the PR, not run locally:** the ~36-min Testcontainers `integrationTest` suite and the OpenAPI Breaking-Change Gate. That is where a Jackson (de)serialisation behaviour change would surface.

### Task 3: docs, citations, gates

- **Citations.** Each new pointer was found by anchor text in the final file, and the arithmetic (+46) agreed on all five: 394→440, 195→241, 218-320→264-366, 333-340→379-386, 184-192→230-238.
  - Strict start-on-anchor check (`t3-citations.txt`, checker source included):
    - Control: the old pointers against db725c94's file PASS 5/5, which proves the anchors are right.
    - New pointers against the final file: **PASS 5/5**, rc=0.
    - **FAIL ARM**: old pointers against the final file: **FAIL 5/5**, rc=1.
    - The six unaffected citations (STACK.md:8/:33/:35, dependency-horizons.yaml:511/:550, k8s/LOCAL.md:1967) PASS 6/6.
    - The new STACK.md jackson citation `:118-162` passes. Its off-by-one `:118-163` fails, rc=1.
  - Step 0 of the check reads each number back out of the docs themselves, and all six match.
  - Adding the STACK.md bullet at line 36 shifts STACK.md's own lines: the old :51 is now :52 and the old :69 is now :70. The only living `STACK.md:NN` citation is a dated historical remark in scripts/check-doc-versions.sh:251 (`STACK.md:110`), and it was already stale at db725c94. Recorded, not changed.
- **CHANGELOG**: the entry is under `## [Unreleased]`, above #757's. Its heading is `### core-java image gate green again: jackson-bom 2.21.6 for CVE-2026-68497 — 2026-09-29`, with **no PR number yet**. Every number in it comes from the evidence files.
- **Gates** (t3-gates.txt). Every rc=0 except the intended fail arm:

| Gate | rc | Note |
|---|---|---|
| docs-freshness.sh | 0 | metrics match source (4042) |
| check-doc-metrics.sh | 0 | 37/37 |
| check-doc-versions.sh | 0 | 147 claims, 0 drift |
| check-changelog-contract.sh | 0 | 31/31 cited |
| check-doc-citations.sh | 0 (with an internal VOID) | STACK.md's 15 citations were verified, including the new `:118-162`. **But** its YAML sub-extraction of docs/ops/terminal-states.yaml was **refused by the pyshim** (bare `python3 -c`), printed `VOID: cannot extract YAML citations…`, and the gate still exited 0. That sub-check is **VOID locally**, and CI's docs-freshness workflow runs it on the PR. It was not re-routed. The gate reporting PASS over an internal VOID is itself a fail-open, recorded here and not fixed. |
| check-handoff-contract.sh | 0 | |
| check-claims.sh | 0 | 47/47 |
| check-gate-enforcement.sh | 0 | |
| check-branch-behind-base.sh | 0 | 0 behind origin/main (db725c94) |
| check-changelog-cites-pr.sh (no PR) | 0 | SKIP: no pull-request context |
| check-changelog-cites-pr.sh --pr 999999 | **1** | fail arm fires, as expected |
| check-changelog-cites-pr.sh --pr 757 | 0 | found arm |

## Orchestrator hand-offs

1. After `gh pr create`, append `(#<PR>)` to the new CHANGELOG heading. Otherwise check-changelog-cites-pr.sh P-1 reds on the pull_request event.
2. Re-run `scripts/check-branch-behind-base.sh` immediately before opening the PR.
3. The image gate runs on push and release only. It proves itself first on `main`'s post-merge run. The phase-36 branch picks this pin up by merging main.
4. Commit SUMMARY.md and `evidence/` BEFORE this worktree is ever removed, because uncommitted files die with `worktree remove --force`.
5. Throwaway images are kept for you:
   - `jtoye-jackson-check:before` = `sha256:dc3c919d9e047dac47543d744701d1b05f8935e0aae6c9c985b5990b311f3e16`
   - `jtoye-jackson-check:after` = `sha256:fc80d11193390781eaef106db5597c35dff8bf730a3703925146f0ed0c96d157`
   - Remove them with: `docker rmi jtoye-jackson-check:before jtoye-jackson-check:after`
6. `/tmp/claude-jackson-trivy-cache/` (2.8G) is **root-owned**, because the Trivy container wrote it. My user cannot delete it. To remove it: `docker run --rm -v /tmp/claude-jackson-trivy-cache:/c aquasec/trivy:0.70.0 clean --all --cache-dir /c` or `sudo rm -rf /tmp/claude-jackson-trivy-cache`.

## Recorded, not fixed

- `scripts/check-jacoco-coverage.sh:176` cites `build.gradle.kts:15`, but the build-dir redirect is at :19. Pre-existing.
- `scripts/check-doc-versions.sh:251` has a historical comment citing `.planning/codebase/STACK.md:110`, which was already stale at db725c94. Pre-existing.
- `check-doc-citations.sh` exits 0 while one of its sub-extractions is VOID (the pyshim refusal above). It is a fail-open in the gate's aggregation. Pre-existing, and not caused by this change.
- The plan's Task 1 `<verify>` greps `selected by rule` case-sensitively, which can never match Gradle's `Selected by rule`.
- Deliberately not done, per the plan: no version-floor unit test, no 2.21.7 or 2.22.2, and no local integration suite.

## Deviations from Plan

- **[Rule 1 - instrument] Pre-run freshness count.** The first attempt counted find's error line as a result. It was discarded, and the filter was proven instead on synthetic stale and fresh XMLs. Documented above; no code impact.
- **Blank-line placement.** The new block is separated from the amqp line by a blank line, matching the house style between every other `extra[...]` block. The diff is still insertions only (+46/-0).
- Otherwise the plan was executed as written. STATE.md and ROADMAP.md were not touched, per the plan and orchestrator.

## Threat Flags

None. No new endpoint, auth path or schema. The only change is a dependency version inside an existing BOM-managed family.

## Known Stubs

None.

## Self-Check: PASSED

- Commits `1c587f06` and `f8773549` are both on `feature/jackson-2.21.6-cve-2026-68497`, and `git rev-list --count db725c94..HEAD` = 2.
- Modified files exist, and the pin line is present once (non-comment count 1).
- All 36 evidence files are present under `evidence/`, including `t1-family-extractor.sh`.
