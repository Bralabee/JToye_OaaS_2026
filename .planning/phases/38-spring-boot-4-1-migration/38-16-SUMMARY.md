---
phase: 38-spring-boot-4-1-migration
plan: 16
subsystem: docs
tags: [spring-boot-4.1.1, dependency-horizons, check-doc-versions, check-doc-citations, adr, metrics, docs-freshness, statemachine]
status: complete

requires:
  - phase: 38-03
    provides: "The Boot 4.1.1 plugin at both build-file sites and the recorded interim red of both version gates"
  - phase: 38-14
    provides: "The springdoc 3.1.1 snapshot (the mcp-server comments and ADR contract notes describe it)"
  - phase: 38-15
    provides: "The proven CVE floors and the local image-gate run that ADR-0006 cites"
provides:
  - "infra/dependency-horizons.yaml spring-boot row on 4.1.1 / cycle 4.1 / EOL 2027-07-31, #706 exemption deleted"
  - "check-doc-versions.sh Resilience4j row on resilience4j-spring-boot4 (the VOID since 38-03 is gone)"
  - "Boot-4 stack claims in CLAUDE.md, AGENTS.md (outside ORGOS), STACK.md and the other gated docs; Dockerfile label 'Spring Boot 4 backend'"
  - "docs/architecture/decisions/ADR-0006-spring-boot-4-migration.md: decisions, contract changes, deploy and rollback notes, statemachine risk"
  - "CONCERNS.md statemachine entry; docs/metrics.json regenerated once (4183 -> 4286)"
  - "Evidence .planning/phases/38-spring-boot-4-1-migration/evidence/38-16-docs.txt"
affects: [38-17, 38-18]

actuals:
  tokens: 20397
  tasks: 3
  commits: 5
plan_head_before: 4656a7f7762a30d1e52aeac42c8dbf3731d20b78
plan_head_after: 44221109f09f564055a7457472ac06de0970db7f

tech-stack:
  added: []
  patterns:
    - "Read doc version claims from the resolved classpath and Boot's BOM, never from memory"
    - "Separate phase-caused gate failures from pre-existing ones by running the same gate on the pre-phase base in a detached worktree"
    - "Keep superseded versions only inside the doc-versions gate's '(migrated from ...)' clause"

key-files:
  created:
    - docs/architecture/decisions/ADR-0006-spring-boot-4-migration.md
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-16-docs.txt
  modified:
    - infra/dependency-horizons.yaml
    - scripts/check-doc-versions.sh
    - CLAUDE.md
    - AGENTS.md
    - .planning/codebase/STACK.md
    - .planning/codebase/ARCHITECTURE.md
    - .planning/codebase/INTEGRATIONS.md
    - .planning/codebase/CONCERNS.md
    - README.md
    - docs/architecture/ESSENTIAL_ARCHITECTURE.md
    - docs/AI_CONTEXT.md
    - docs/guides/DEPLOYMENT_GUIDE.md
    - docs/guides/USER_GUIDE.md
    - core-java/Dockerfile
    - k8s/LOCAL.md
    - mcp-server/src/tools/create-customer.ts
    - mcp-server/src/tools/create-order.ts
    - docs/metrics.json
    - .planning/phases/38-spring-boot-4-1-migration/deferred-items.md

key-decisions:
  - "/.well-known/oauth-protected-resource is documented as 401 without credentials and 404 with them, with the invalid-token 404 residual, per the owner's 38-06 ruling 'anon-401-parity (Recommended)'. The plan text's 'is 404' predates that ruling and is superseded."
  - "Dated verified records stay history: docs/architecture/ARCHITECTURE.md, docs/PRD.md, SYSTEM_DESIGN_V2.md §1, CHANGELOG, HANDOFF and docs/analysis|archive|audit|planning|reports|status. .planning/PROJECT.md is not gated and was already stale before the phase. The archify diagram subtitle changes at its next regeneration."
  - "The Testcontainers claim stays 1.21.4: the explicit pin wins over Boot 4.1.1's managed 2.0.5 (read from testRuntimeClasspath)."
  - "Rollback after Flyway 12 has validated the history table is recorded as UNVERIFIED in ADR-0006, not claimed safe."
  - "check-openapi-snapshot-fresh's red does not predate the phase: it passes at pre-phase 767f5658. 38-14 introduced it on purpose, and 38-17 clears it on the rebuilt stack."

patterns-established:
  - "An ADR cites every measured figure as ev:<evidence file>; a mechanical scan lists digit-bearing paragraphs without a citation, and each is judged by reading"

requirements-completed: [BOOT4-10]

coverage:
  - id: D1
    description: "Both version gates follow the plugin: the horizons spring-boot row is on 4.1.1 / cycle 4.1 with no exemption, and doc-versions resolves the Boot-4 Resilience4j coordinate. A half-bump, a stale claim and an unresolvable coordinate are each caught."
    requirement: BOOT4-01
    verification:
      - kind: other
        ref: "bash scripts/check-doc-versions.sh -> rc=0 (153 claims); bash scripts/check-dependency-horizons.sh (conda env, online) -> rc=0; from 38-03's rc 2/rc 2"
        status: pass
      - kind: other
        ref: "arms A rc=1, C rc=2 (H-5 spring-boot naming core-java/build.gradle.kts), D rc=1 naming CLAUDE.md, E rc=2 VOID, F rc=1 (H-2 fetched 2027-07-31 for spring-boot/4.1); restores sha256 OK; evidence §5"
        status: pass
    human_judgment: false
  - id: D2
    description: "Every gated and prose stack claim describes the Boot-4 tree, every citation this phase broke resolves again, and the core-java image label says Spring Boot 4"
    requirement: BOOT4-14
    verification:
      - kind: other
        ref: "bash scripts/check-doc-citations.sh (conda env) -> rc=0, 46/46 verified (pre-phase 767f5658: 45/45; plan base: 6 phase-caused violations, all re-pointed by content); arm B rc=1"
        status: pass
      - kind: other
        ref: "git grep -e 'JUnit 5' -e 'Tomcat 10.1' -e 'Spring Boot 3 backend' -e '4.1.137' over CLAUDE/AGENTS/README/STACK/Dockerfile -> rc=1 (plan base: 6 lines); 3.5.16 outside a (migrated from) clause -> 0 (plan base: 15)"
        status: pass
      - kind: other
        ref: "AGENTS.md hunks inside ORGOS 389..811 -> 0 (synthetic control -> 1); ORGOS block sha256 identical at base and HEAD"
        status: pass
    human_judgment: false
  - id: D3
    description: "ADR-0006 records D-01..D-05 (D-05 as refined), the 38-05 Jackson-defaults verdict, the contract changes, deploy and rollback notes, the D-03 statemachine risk with the EnumMap replacement as a separate decision, and RFC 9728 out of scope; CONCERNS.md points at it"
    requirement: BOOT4-10
    verification:
      - kind: other
        ref: "awk D-01..D-05/Deploy notes/Rollback notes counts 4/1/2/3/5/2/1 (fail directions: /dev/null all 0; heading removed -> rollback=0; missing file rc=2); awk ADR-0006 in CONCERNS.md -> 2 (plan base 0)"
        status: pass
    human_judgment: true
    rationale: "Whether the deploy and rollback notes are complete and accurate enough for an operator is judged by reading; the citation scan and the re-read against 38-CONTEXT.md are recorded in evidence §7 but cannot prove the prose right."
  - id: D4
    description: "docs/metrics.json regenerated once from source and every prose count matches it"
    requirement: BOOT4-14
    verification:
      - kind: other
        ref: "bash scripts/docs-freshness.sh -> rc=0 (4286); bash scripts/check-doc-metrics.sh -> rc=0 (37 claims); Java delta matches git grep @Test at 767f5658 (2005/306) and HEAD (2108/325)"
        status: pass
      - kind: other
        ref: "arm M (README total back to 4183) check-doc-metrics rc=1; arm N (manifest methods back to 2005) docs-freshness rc=1; restores sha256 OK; evidence §8.5"
        status: pass
    human_judgment: false

duration: 20 min
completed: 2026-10-05
---

# Phase 38 Plan 16: Version Gates, Boot-4 Docs, ADR-0006 and Metrics Summary

**Both version gates now follow the Boot 4.1.1 plugin and are green again. They had been red since 38-03, and they are shown to catch a half-bumped build file (rc 2), a stale claim (rc 1) and an unresolvable coordinate (rc 2). Every stack claim in the agent-facing docs describes the Boot-4 tree, using versions read from the resolved classpath. The six citations this phase broke now resolve. ADR-0006 records the migration's decisions, contract changes, deploy and rollback notes, and the statemachine risk. docs/metrics.json was regenerated once, 4183 to 4286.**

## Performance

- **Duration:** 20 min
- **Started:** 2026-10-05T16:03:50Z
- **Completed:** 2026-10-05T16:23:44Z
- **Tasks:** 3 of 3
- **Files modified:** 21 (19 modified, 2 created)

## Accomplishments

- **Version gates (Task 1, tracer):**
  - The horizons `spring-boot` row pins 4.1.1 on cycle 4.1, EOL 2027-07-31. The #706 exemption is deleted.
  - check-doc-versions reads `resilience4j-spring-boot4`.
  - Both gates went from 38-03's rc 2 to rc 0.
  - The arms behaved as planned: A rc=1, B rc=1, C rc=2 with `H-5 spring-boot` naming `core-java/build.gradle.kts`, D rc=1 naming CLAUDE.md, E rc=2. An added arm F showed that the H-2 fetch read spring-boot/4.1 online.
- **Docs:**
  - **Claims.** CLAUDE.md and AGENTS.md take one identical 17-line change, outside ORGOS; the ORGOS block's sha256 is unchanged. STACK.md now covers Spring Boot 4.1.1 / Framework 7.0.9, the explicit starters, Tomcat 11.0.26, netty 4.2.17 with no pin, the Jackson 3 line, Spring Data 2026.0, Hibernate 7.4, Security 7.1, AMQP 4.1, Lettuce 7, SpringDoc 3.1.1, JUnit Jupiter 6, Flyway 12 and the zipkin starter.
  - **Other docs.** README, ESSENTIAL_ARCHITECTURE, ARCHITECTURE/INTEGRATIONS, AI_CONTEXT, DEPLOYMENT_GUIDE and USER_GUIDE are updated. The Dockerfile label now says "Spring Boot 4 backend with multi-tenant isolation".
  - **Citations.** Six citations this phase broke are re-pointed by content. The pre-phase base is rc=0, so none of the six predates the phase.
  - **Deferred items closed:** the AI_CONTEXT cache-key format and the two mcp-server snapshot comments.
- **ADR-0006 (Task 2):**
  - **Decisions:** D-01..D-05 with owner dates, D-05 as refined ("anon-401-parity"), and the 38-05 "jackson3-defaults" verdict (15 tree-equal, 4 raw-unequal on key order only).
  - **Contract changes:** alphabetical key order, the trailing-content 400, the `/.well-known` answers, ISO broker dates, the regenerated OpenAPI, and the restored config keys.
  - **Deploy notes:** no Redis flush (`v4:` keys, narrowed SEC-4 allowlist), no queue drain, idempotency replay, the image gate pre-run locally, and no migration.
  - **Rollback notes:** with one UNVERIFIED Flyway item.
  - **Risk:** the D-03 statemachine risk, with the EnumMap replacement left as a separate decision. RFC 9728 is out of scope.
  - **CONCERNS.md:** a new Fragile Areas entry, and the Boot 3.5 horizons row closed.
- **Metrics (Task 3):**
  - Regenerated once: Java 2005 to 2108 methods and 306 to 325 files, total 4183 to 4286. An independent git-grep at the pre-phase base and at HEAD agrees.
  - Ten prose counts are updated. check-doc-metrics went from 10 FAILs to 37/37.
  - Static sweep: 42 gates, 32 rc=0. All 10 non-zero gates give the same rc on the plan base; each reason is in evidence §8.6.

## Task Commits

1. **Task 1 (tracer): version gates follow the plugin; docs and citations** - `0f477503` (docs), evidence `23026241` (docs)
2. **Task 2: ADR-0006 and the statemachine risk record** - `01e46ac6` (docs)
3. **Task 3: metrics regenerated once; static sweep** - `dc4cc9ea` (docs), evidence `44221109` (docs)

**Plan metadata:** this SUMMARY's commit, then the STATE/ROADMAP/REQUIREMENTS record.

## Files Created/Modified

- `infra/dependency-horizons.yaml`: the spring-boot row on 4.1.1 / 4.1 / 2027-07-31, exemption deleted, a dated header line added.
- `scripts/check-doc-versions.sh`: the Resilience4j row on the Boot-4 coordinate; the label accepts "Spring Boot [34] Starter".
- `CLAUDE.md`, `AGENTS.md`: the Constraints line with a migrated clause, the stack block and the Testing counts.
- `.planning/codebase/STACK.md`: the Boot-4 refresh; three re-pointed and three narrowed citations, plus one new one.
- `.planning/codebase/{ARCHITECTURE,INTEGRATIONS,CONCERNS}.md`, `README.md`, `docs/architecture/ESSENTIAL_ARCHITECTURE.md`, `docs/AI_CONTEXT.md`, `docs/guides/{DEPLOYMENT_GUIDE,USER_GUIDE}.md`, `k8s/LOCAL.md`, `core-java/Dockerfile`: claims, citations and the image label.
- `mcp-server/src/tools/create-{customer,order}.ts`: comments only.
- `docs/architecture/decisions/ADR-0006-spring-boot-4-migration.md`: new.
- `docs/metrics.json`: regenerated by `docs-freshness.sh --write`.
- `.planning/phases/38-spring-boot-4-1-migration/deferred-items.md`: both entries marked resolved.
- `.planning/phases/38-spring-boot-4-1-migration/evidence/38-16-docs.txt`: start state, the edits, the gates, the arms, the acceptance checks, the ADR checks, the metrics and the sweep.

## Fail-direction record

| Criterion | Real tree | Fail direction |
|---|---|---|
| check-doc-versions | rc=0 (153) | arm A rc=1 (SpringDoc), arm D rc=1 (CLAUDE.md Spring Boot), arm E rc=2 VOID; plan base rc=2 |
| check-dependency-horizons | rc=0 | arm C rc=2 (H-5 spring-boot, core-java/build.gradle.kts), arm F rc=1 (H-2 date); plan base rc=2 |
| check-doc-citations | rc=0 (46/46) | arm B rc=1; plan base rc=1 (6 violations) |
| AGENTS.md hunks inside ORGOS | 0 | synthetic hunk at 400 -> 1 |
| spring-boot row exemption/#706 count | 0 (eol_cycle 4.1 count 1) | plan base 2 (eol_cycle 4.1 count 0) |
| resilience4j-spring-boot4 in the gate | 1 line | plan base rc=1 |
| 3.5.16 outside a migrated clause | 0 | plan base 15 |
| prose grep (JUnit 5 / Tomcat 10.1 / Boot 3 label / 4.1.137) | rc=1 | plan base 6 lines |
| ADR verify awk | all counts > 0 | /dev/null all 0; heading removed -> rollback=0; missing file rc=2 |
| ADR-0006 in CONCERNS.md | 2 | plan base 0 |
| docs-freshness | rc=0 | before --write rc=1; arm N rc=1 |
| check-doc-metrics | rc=0 (37) | before prose edits rc=1 (10); arm M rc=1 |
| Java @Test count | 2108 / 325 | pre-phase 2005 / 306 |
| root-pin guard | PIN OK | from /tmp: FATAL, rc=1 |

## Decisions Made

See `key-decisions` in the frontmatter. In brief:
- `/.well-known` is documented per the owner's 38-06 ruling, not the plan text.
- Dated records stay history.
- Testcontainers stays 1.21.4.
- The Flyway rollback item is recorded as unverified.
- The openapi-snapshot-fresh red is 38-14's by design and 38-17's to clear.

## Deviations from Plan

### Owner-decision deviations

**1. `/.well-known/oauth-protected-resource` is not "404" (owner decision, 38-06)**
- **Found during:** Task 2.
- **Issue:** The plan's action says the deploy note should state "/.well-known/oauth-protected-resource is 404". That text predates the owner's 2026-10-05 ruling "anon-401-parity (Recommended)".
- **Fix:** ADR-0006 gives a per-caller table: 401 with plain `Bearer` and the 401 document for a caller with no credentials; 404 when credentials are present; the invalid or expired bearer 404 residual. The deploy note warns that an anonymous smoke check expecting 404 is wrong. STACK.md says the same.
- **Committed in:** 01e46ac6 (ADR), 0f477503 (STACK.md).

### Auto-fixed Issues

**2. [Rule 3 - Blocking] check-doc-citations VOIDs on the python shim outside a conda env**
- **Found during:** Task 1.
- **Issue:** Like the horizons gate in 38-03, the citations gate shells out to python. Outside an env it reported rc=1 with a `docs/ops/terminal-states.yaml` VOID, so that file's 17 citations went unread.
- **Fix:** Every python-using gate ran inside the existing `engineering-doctrine` env. The VOIDed run is recorded (evidence §1.3).

**3. [Rule 2 - Missing critical] Current-state docs outside `files_modified`**
- **Found during:** Task 1.
- **Issue:** The repo-wide sweep found current-state claims that the phase made false outside the plan's list: docs/AI_CONTEXT.md (Spring Boot 3.5.16, JUnit 5, and the deferred cache-key line), docs/guides/DEPLOYMENT_GUIDE.md and USER_GUIDE.md ("Spring Boot 3"). The contract also carried the two mcp-server comments.
- **Fix:** One-claim edits. deferred-items.md marks both carried items resolved. Dated records were left alone (key-decisions).
- **Committed in:** 0f477503.

**4. [Rule 2 - Strengthening] Added arms F and N**
- **Arm F:** horizons `eol_date` 2027-07-30. It proves the clean run's H-2 fetch was online, read cycle 4.1, and agrees with 2027-07-31.
- **Arm N:** manifest methods back to 2005. It proves docs-freshness catches a stale manifest, not only stale prose.
- **Committed in:** evidence 23026241 and 44221109.

**5. [Rule 1 - Bug] ADR facts corrected against source during self-review**
- Boot4ModuleLivenessIntegrationTest's probes were read from the test: Flyway, Brave/Zipkin, Prometheus, the HTTP client builders, both state-machine factories, and Jackson 3. Security-access belongs to StatemachineSecurityAccessTest.
- The epoch-date example was re-cited to the 38-01 golden fixture; evidence/38-01-golden-capture.txt does not contain it.
- The ISO write form was cited to ev:38-07 and ev:38-09.
- Four figures got direct citations after the scan.
- **Committed in:** 01e46ac6 (corrections made before that commit; recorded in evidence §7.3).

### Plan text that could not be satisfied as written

**6. The sweep's "predates the phase" does not hold for check-openapi-snapshot-fresh**
- **Found during:** Task 3.
- **Issue:** It is rc=1 at HEAD and at the plan base, but rc=0 at pre-phase 767f5658 ("PASS — 0 differences"). 38-14 regenerated the snapshot on springdoc 3.1.1, and the running core-java container ("Up 23 hours") still serves the pre-Boot-4 spec.
- **Disposition:** Recorded as introduced by 38-14 on purpose and owned by 38-17, as the orchestrator contract says. It is not fixable here without rebuilding compose, which the plan forbids.

**7. `k8s/DEPLOYMENT.md` needed no change.** It carries 0 citations and no stack claim (grep). It stays listed in `files_modified` but is untouched.

---

**Total deviations:** 7: 1 owner-decision supersession, 4 auto-fixes (1 blocking environment, 1 missing-critical scope, 1 strengthening, 1 accuracy), and 2 plan-text notes.
**Impact on plan:** No scope change to the gates or the build. The extra docs are one-claim edits, and the build file, Java and snapshot are untouched.

## Issues Encountered

Non-zero static gates, none caused by this plan (each gives the same rc on the plan base; evidence §8.6):
- **Environment VOIDs (6):** backup-restore-drill, e2e-skip-budget, e2e-typecheck, go-coverage, jacoco-coverage and test-count-oracle. The test-count-oracle VOID is the jest and playwright families only, which need node_modules; its vitest family passes 61/8.
- **Runtime state, 38-17's:** check-alert-metrics (no order has been placed since core-java last restarted, and it is red at the pre-phase base too) and check-openapi-snapshot-fresh (deviation 6).
- **Staleness against origin/main:** check-branch-behind-base (4 behind; the merge from base is 38-18's) and check-handoff-contract H-3 (the pre-existing red the orchestrator lists).

## Known Stubs

None.

## Threat Flags

None beyond the plan's threat model:
- T-38-39 is mitigated: the ADR's deploy and rollback notes cite 38-08, 38-09, 38-10 and 38-15, and the doc gates are green with arms.
- T-38-49 is mitigated: arms C, D and E, plus arm F.
- T-38-SC: no package was installed.

## User Setup Required

None.

## Next Phase Readiness

- **38-17 (rebuilt runtime):**
  - On `/.well-known`, expect 401 without a token and 404 with one, as ADR-0006 states.
  - `check-openapi-snapshot-fresh.sh` and `check-alert-metrics.sh` are red on the current, pre-rebuild container. Both must be re-run on the rebuilt stack. NoOrdersCreated needs one order after the restart (`scripts/seed-order-metric.sh`).
  - The image label in the rebuilt jar's Dockerfile now says "Spring Boot 4 backend".
- **38-18 (PR body):**
  - ADR-0006's "Contract changes" section lists the five contract notes (key order, trailing 400, `/.well-known`, ISO broker dates, OpenAPI) and the restored settings.
  - The branch is 4 commits behind origin/main as last fetched; the merge from base is 38-18's.
  - BOOT4-01 and BOOT4-14 stay open (`requirements.ready-ids`: blocked on 38-17 and 38-18). BOOT4-10 is marked complete.
- **Unverified, carried:** the Flyway 12 to Flyway 11 rollback acceptance (ADR-0006, Rollback notes).

## Self-Check: PASSED

- FOUND: docs/architecture/decisions/ADR-0006-spring-boot-4-migration.md
- FOUND: .planning/phases/38-spring-boot-4-1-migration/evidence/38-16-docs.txt
- FOUND: 0f477503, 23026241, 01e46ac6, dc4cc9ea, 44221109 (git log). Control: an absent hash reports MISSING.
- Re-run on HEAD 44221109: check-doc-versions rc=0, check-dependency-horizons rc=0, check-doc-citations rc=0, docs-freshness rc=0, check-doc-metrics rc=0.
- commits: 5, measured as `git rev-list --count 4656a7f7..44221109`.

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*
