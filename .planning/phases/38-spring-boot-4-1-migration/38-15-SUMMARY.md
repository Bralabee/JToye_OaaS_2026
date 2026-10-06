---
phase: 38-spring-boot-4-1-migration
plan: 15
subsystem: infra
tags: [spring-boot-4.1.1, cve-pins, trivy, tomcat-11, jackson3, jackson2, amqp-client, netty, image-gate]
status: complete

requires:
  - phase: 38-03
    provides: "The Boot-4 CVE pins (tomcat 11.0.26, amqp-client 5.34.0, jackson-2-bom 2.22.3, jackson-bom 3.1.7, no netty pin)"
  - phase: 38-13
    provides: "The final explicit-starter dependency graph (build file unchanged since)"
provides:
  - "A local Trivy 0.70.0 image-gate pass on the final Boot-4 image (rc=0), beside origin/main's (rc=0), on one DB (UpdatedAt 2026-10-05 13:07:51 UTC)"
  - "Per-pin resolution proofs and load-bearing arms (A, B, C, D as planned, plus E for the Jackson-2 floor and B2 for Tomcat 11.0.25)"
  - "Rewritten pin comments in core-java/build.gradle.kts: a dated Boot-4 paragraph per pin with history kept"
  - "Evidence file evidence/38-15-cve-floors.txt"
affects: [38-16, 38-17, 38-18]

actuals:
  tokens: 7788
  tasks: 2
  commits: 2
plan_head_before: ef51470427391a44415286264b7876d97b1ba4dd
plan_head_after: c201ccbdd9a8e6e31242cf39960ba02a5ebca475

tech-stack:
  added: []
  patterns:
    - "Image-gate pre-emption: build the image locally and scan it with CI's own Trivy version and flags on one pinned cache, beside main's image on the same DB"
    - "A clean scan is evidence only with a package inventory showing the scanner read the nested jars"

key-files:
  created:
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-15-cve-floors.txt
  modified:
    - core-java/build.gradle.kts

key-decisions:
  - "No bump was needed: on the 2026-10-05 13:07 UTC DB both the Boot-4 branch image and origin/main's image pass the gate (rc=0, os and jar targets 0)."
  - "Tomcat stays at 11.0.26, although 11.0.25 passes the gate. Trivy has no record of the advisory-only CVE-2026-76183 (WebSocket constraint bypass) or CVE-2026-86350 (header mix-up), so the gate cannot be the reason. The comment says this so nobody lowers the pin on the gate's word."
  - "CVE-2026-91777 is confirmed on the Jackson-3 line, fixed in 3.1.7. This closes RESEARCH assumption A3: the 3.1.7 floor clears all five Jackson-3 HIGH that Trivy names."
  - "origin/main was scanned as last fetched (03022f21, fetched 2026-10-05 00:34 BST), not re-fetched: the orchestrator forbids touching any remote."

patterns-established:
  - "Each CVE pin comment opens with a dated measured paragraph (key + POM line, managed version, CVEs as Trivy and the advisories name them, arm results with the DB date, the post-merge enforcement rule); older text is kept below as History"

requirements-completed: [BOOT4-12]

coverage:
  - id: D1
    description: "The final Boot-4 core-java image passes a local Trivy 0.70.0 scan with the CI image-gate flags; main's image and the DB timestamp are recorded beside it"
    requirement: BOOT4-12
    verification:
      - kind: other
        ref: "docker run ... aquasec/trivy:0.70.0 image --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 jtoye-core-java:p38-branch -> rc=0 (re-run after commit: rc=0); p38-main rc=0; evidence sections 1-5"
        status: pass
      - kind: other
        ref: "trivy --list-all-pkgs inventory: 246 jar packages read incl. every pinned artifact; jar-less base image -> no jar target (evidence section 5)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Each pin resolves as intended on runtimeClasspath and is load-bearing (near-miss keys revert to managed versions; tomcat 11.0.24 makes the image gate exit 1)"
    requirement: BOOT4-12
    verification:
      - kind: other
        ref: "dependencyInsight x5 before/closing/after byte-identical; plan verify pinned=2 / pinned=4 (arm outputs 0 / 0)"
        status: pass
      - kind: other
        ref: "arms A 3.1.5, B 11.0.24, C 5.30.0, E 2.21.5 (each jar scan rc=1), D image scan rc=1 naming CVE-2026-65182; each restore sha256 OK; evidence sections 8-9"
        status: pass
    human_judgment: false
  - id: D3
    description: "Every pin comment records its Boot-4 key, managed version, CVEs cleared, arm results and date, with history kept; no netty pin, Jackson-2 floor on jackson-2-bom.version; scan images removed"
    requirement: BOOT4-12
    verification:
      - kind: other
        ref: "git diff comment-only filter -> empty (fail direction prints the extra[...] lines); git grep netty pin rc=1 (origin/main: 1 line); jackson-2-bom line count 1 (origin/main: 0); p38 image count 0 (before rm: 3)"
        status: pass
    human_judgment: false

duration: 13 min
completed: 2026-10-05
---

# Phase 38 Plan 15: CVE Floors and the Local Image Gate Summary

**The final Boot 4.1.1 core-java image passes the CI Trivy image gate locally (Trivy 0.70.0, CI flags, rc=0). origin/main's image also scans rc=0 on the same DB (UpdatedAt 2026-10-05 13:07 UTC). Every CVE floor resolves on runtimeClasspath and is shown to be load-bearing: moving any one of them back reopens named HIGH or CRITICAL CVEs. Each pin comment now records those measurements.**

## Performance

- **Duration:** 13 min
- **Started:** 2026-10-05T15:47:35Z
- **Completed:** 2026-10-05T16:00:49Z
- **Tasks:** 2 of 2
- **Files modified:** 2 (1 build file, comments only; 1 evidence file)

## Accomplishments

- **Image gate, ahead of merge (Task 1):**
  - Built `jtoye-core-java:p38-branch` and `:p38-main` on the same Temurin base digests. origin/main was exported cleanly with `git archive`.
  - The jar listings confirm the images by content: the branch image has `spring-boot-4.1.1.jar` and no 3.5 or `spring-boot-jackson2` jar; main has `spring-boot-3.5.16.jar`.
  - Both scans ran on one Trivy cache and returned rc=0, with 0 findings on both the alpine and the jar target. No bump was needed.
- **Resolution (Task 2):** dependencyInsight on runtimeClasspath, run three times (before the arms, after the arms, after the edit), with byte-identical output each time:

  | Artifact | Resolved | Reason |
  |---|---|---|
  | tomcat-embed-core | 11.0.26 | selected by rule |
  | amqp-client | 5.34.0 | selected by rule |
  | com.fasterxml databind | 2.22.3 | by constraint |
  | tools.jackson databind | 3.1.7 | by constraint |
  | netty-codec-http | 4.2.17.Final | selected by rule, with no pin |

- **Load-bearing arms:** each arm was restored by sha256 and followed by a closing clean run.

  | Arm | Change | Resolves to | Trivy result |
  |---|---|---|---|
  | A | near-miss key `jackson3-bom.version` | 3.1.5 | 5 HIGH |
  | B | near-miss key `tomcat-version` | 11.0.24 | 3 CRITICAL |
  | C | amqp-client pin removed | 5.30.0 | 4 HIGH |
  | E | near-miss key `jackson2-bom.version` | 2.21.5 | 5 HIGH |
  | D | image rebuilt with tomcat 11.0.24 | 11.0.24 | gate rc=1, naming CVE-2026-65182, CVE-2026-65905 and CVE-2026-68525 |

- **Comments:** the four pin blocks and the netty note each open with a dated Boot-4 paragraph. The earlier text is kept below as History. The diff touches comment lines only.
- **Cleanup:** the three scan images were removed. The compose image `jtoye_oaas_2026-core-java:latest` is untouched (same ID). build-local was rebuilt from the committed tree after the arms.

## Task Commits

1. **Task 1 (tracer): build the final image and scan it with the CI gate's flags, beside main's** - `8621df59` (docs)
2. **Task 2: every pin resolves and every key is load-bearing; comments record the measurements** - `c201ccbd` (docs)

**Plan metadata:** this SUMMARY's commit, then the STATE/ROADMAP/REQUIREMENTS record.

## Files Created/Modified

- `.planning/phases/38-spring-boot-4-1-migration/evidence/38-15-cve-floors.txt`: DB metadata, both builds, jar listings, the main, branch and arm scans, the inventory proof, dependencyInsight x5 x3, arms A/B/B2/C/D/E, and the plan-end checks.
- `core-java/build.gradle.kts`: comments only. One dated Boot-4 paragraph each for tomcat, amqp-client and Jackson (both keys), plus the netty note. The four `extra[...]` lines are unchanged.

## Fail-direction record

| Criterion | Real tree | Fail direction |
|---|---|---|
| branch image gate | rc=0 (re-run after commit: rc=0) | tomcat 11.0.24 image (arm D): rc=1, 3 CRITICAL |
| scan read the jar (replacement for "Total:") | alpine + app.jar rows; 246 jar packages | jar-less base image: no jar target |
| no Boot-3 jar in branch app.jar | 0 (rc=1) | main image: 1 |
| tomcat pinned=N (plan awk) | 2 | arm B output: 0 |
| tools.jackson pinned=N (plan awk) | 4 | arm A output: 0 |
| Jackson-3 key load-bearing | 3.1.7 | near-miss: 3.1.5 |
| Tomcat key load-bearing | 11.0.26 | near-miss: 11.0.24 |
| amqp-client pin load-bearing | 5.34.0 | removed: 5.30.0 |
| Jackson-2 key load-bearing | 2.22.3 | near-miss: 2.21.5 |
| no netty pin (git grep) | rc=1 | origin/main: 1 line |
| jackson-2-bom line (git grep) | 1 line | origin/main: rc=1 |
| comment-only diff filter | empty | value change: prints both extra[...] lines |
| p38 images at plan end | 0 | before rm: 3 |
| one DB for all scans | UpdatedAt/DownloadedAt unchanged at end | n/a (metadata re-read, not a check) |

## Decisions Made

See `key-decisions` in the frontmatter. In brief:
- No bump was needed.
- Tomcat is held at 11.0.26 on the advisory, not the gate.
- CVE-2026-91777 is confirmed on Jackson 3 and cleared by 3.1.7.
- main was scanned at its last fetched ref, without a re-fetch.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The tracer's "Total:" fails_when cannot pass on a clean scan**
- **Found during:** Task 1.
- **Issue:** Trivy 0.70 prints a per-target `Total:` block only for a target that has findings. On the correct, clean image, `grep -c 'Total:'` is 0, so the literal fails_when fires on a passing gate. It cannot tell "the scan ran and found nothing" from "the scan never read the image".
- **Fix:** Replaced with a strictly stronger check, recorded beside the original:
  - the Report Summary must carry both the alpine row and the `app/app.jar` jar row;
  - a `--list-all-pkgs` inventory must show 246 jar packages, including every pinned artifact at its pinned version.
- **Fail direction:** the jar-less base image yields no jar target. `Total:` appears under arm D.
- **Files modified:** evidence only.
- **Committed in:** 8621df59.

**2. [Rule 2 - Missing critical] Added arm E for the Jackson-2 floor**
- **Found during:** Task 2.
- **Issue:** must_haves truth 2 requires each pin to be shown load-bearing. Arms A-D cover Jackson 3, Tomcat and amqp-client, but not `jackson-2-bom.version`.
- **Fix:** Arm E (near-miss `jackson2-bom.version`) resolves 2.21.5, and Trivy names 5 HIGH. It also measures the swagger-core 2.22.1 to 2.21.5 downgrade that the 2.22.3 floor exists to stop.
- **Committed in:** c201ccbd.

**3. [Rule 2 - Missing critical] Added arm B2 (Tomcat 11.0.25) before writing the Tomcat comment**
- **Found during:** Task 2.
- **Issue:** The comment must name the CVEs "as Trivy and the advisories name them". Trivy names only three CRITICAL, all fixed in 11.0.25, so the reason for 11.0.26 had to be established rather than assumed.
- **Fix:** At 11.0.25 the gate scan is clean, and no Tomcat entry exists at any severity. A positive control on 11.0.24 lists the three. The comment records that the gate needs >= 11.0.25 and that the advisory (CVE-2026-76183, CVE-2026-86350) is the reason for 11.0.26.
- **Committed in:** c201ccbd.

**4. [Orchestrator constraint] origin/main was not re-fetched**
- **Found during:** Task 1.
- **Issue:** The plan says `git fetch origin main`, but the orchestrator forbids touching any remote.
- **Fix:** Scanned the local origin/main ref (03022f21, last fetched 2026-10-05 00:34 BST per the reflog), exported with `git archive` (rc=0). Recorded in evidence section 2.

**5. [Rule 2 - Strengthening] DB pinning and per-arm jar scans**
- All scans used the one cache. The DB metadata was re-read at the end and is unchanged; NextUpdate is 2026-10-06, so no refresh was possible within the plan.
- Arms A, B, B2, C and E scanned a freshly built bootJar with `trivy rootfs` and the CI flags. This names the CVEs each pin clears without three more image builds.
- Arm D used the full image rebuild and image scan, as planned.

---

**Total deviations:** 5. These are 1 criterion correction, 2 added arms, 1 constraint-driven substitution and 1 strengthening. **Impact on plan:** none on scope. The build file change is comment-only, and no pin value moved.

## Issues Encountered

None. No CVE needed a bump, so no accept/waive decision arose.

## Known Stubs

None.

## Threat Flags

None beyond the plan's threat model:
- T-38-37 is mitigated: the pins are proven, and arms A-E plus the arm-D gate run show they are load-bearing.
- T-38-38 is mitigated: there are near-miss arms for both Jackson keys and the Tomcat key.
- T-38-SC is mitigated: the only scanner was CI's own aquasec/trivy:0.70.0, and no package was installed.

## User Setup Required

None.

## Next Phase Readiness

- **38-16 (docs/ADR):** can cite evidence/38-15-cve-floors.txt for BOOT4-12.
  - Tomcat 11.0.26 rests on the advisory, not the gate.
  - CVE-2026-91777 is confirmed on Jackson 3.
- **38-17:** the rebuilt-stack checks are unaffected. This plan touched no compose image, and its scan images are gone.
- **38-18 (PR body):**
  - The local image gate was rc=0 on DB 2026-10-05 13:07 UTC.
  - The Trivy DB moves daily, so the post-merge `build-and-push` gate may still see a newer DB.
- **Out of scope, not fixed:** the core-java Dockerfile's `LABEL ... description="Spring Boot 3 backend..."` is stale under Boot 4. It is cosmetic; it belongs to a docs or Dockerfile plan, not to a CVE floor.

## Self-Check: PASSED

- FOUND: .planning/phases/38-spring-boot-4-1-migration/evidence/38-15-cve-floors.txt
- FOUND: core-java/build.gradle.kts
- FOUND: 8621df59, c201ccbd (git log --all). Control: an absent hash reports MISSING.
- Task 1 verify re-run after its commit: rc=0. Task 2 acceptance re-run after edit: netty rc=1, jackson-2-bom 1 line, p38 images 0.

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*
