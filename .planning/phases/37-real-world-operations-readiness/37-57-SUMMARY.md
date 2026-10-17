---
phase: 37-real-world-operations-readiness
plan: 57
subsystem: notifications
tags: [java, email, copy, sentence-end, parity, g-37-6]

requires:
  - phase: 37-real-world-operations-readiness
    provides: "37-56 frontend/lib/sentence-end.ts (SENTENCE_TERMINATORS, TRAILING_SPACE_CODE_POINTS, withFullStop): the TypeScript rule this plan mirrors"
provides:
  - "uk.jtoye.core.common.Sentences: TERMINATORS, endsSentence, withFullStop, fullStopAfter (the one Java sentence-end rule)"
  - "SentencesTest: the 37-56 case table, an exhaustive 0x0000-0xFFFF whitespace row, and parity with both TypeScript declarations"
  - "the staff invitation, four customer status leads, the customer footer and the onboarding update (text and HTML) routed through Sentences"
affects: [37-59]

actuals:
  tokens: 6200
  tasks: 2
  commits: 5
plan_head_before: d2c3d7ff2150dd2f03b1dcd5e75b2a0005054039
plan_head_after: 9d1397c894c62bfffdb544aa803161c3ef446cc2

tech-stack:
  added: []
  patterns:
    - "An email sentence that ends with a vendor- or reviewer-supplied value ends it through Sentences.withFullStop (after sanitise); markup that renders the value in its own element uses Sentences.fullStopAfter"
    - "Cross-language parity: a Java unit test reads the TypeScript declarations through IntegrationTestSupport.locateRepoFile and fails, naming the file, when a declaration is missing or parses to nothing"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/common/Sentences.java
    - core-java/src/test/java/uk/jtoye/core/common/SentencesTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-57-plan-head-before.txt
    - .planning/phases/37-real-world-operations-readiness/evidence/37-57-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-57-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-57-red-task2-renderer.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java
    - core-java/src/main/java/uk/jtoye/core/notification/template/EmailTemplateRenderer.java
    - core-java/src/test/java/uk/jtoye/core/notification/EmailNotificationServiceStaffInviteTest.java
    - core-java/src/test/java/uk/jtoye/core/notification/EmailNotificationServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/notification/template/EmailTemplateRendererTest.java

key-decisions:
  - "Sentences trims exactly the JavaScript whitespace class: Character.isSpaceChar plus 0x09-0x0D and 0xFEFF, never Character.isWhitespace; code units are int literals, no backslash-u escapes anywhere in the class or its test"
  - "SentencesTest's fullStopAfter/endsSentence agreement row trims with the declared 25-entry list, not String.stripTrailing (which is Java's whitespace class, the one the rule avoids), so it compares the rule with an independent statement of it"
  - "Task 2's RED is recorded as two classifier records from the same run (one JUnit report per record): 37-57-red-task2.json (status emails) and 37-57-red-task2-renderer.json (onboarding)"

patterns-established:
  - "Sentences.withFullStop(value) in place of a template's literal full stop after a free-text value in Java email copy"

requirements-completed: [RWO-004]

coverage:
  - id: D1
    description: "One Java sentence-end rule with the 37-56 case table and an exhaustive whitespace row"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/common/SentencesTest.java"
        status: pass
    human_judgment: false
  - id: D2
    description: "Sentences is held equal to frontend/lib/sentence-end.ts on SENTENCE_TERMINATORS and TRAILING_SPACE_CODE_POINTS"
    verification:
      - kind: unit
        ref: "SentencesTest#parity_terminatorsEqualTheTypeScriptDeclaration, #parity_trailingSpaceCodePointsEqualTheTypeScriptDeclaration"
        status: pass
    human_judgment: false
  - id: D3
    description: "The staff invitation for 'Peckham Jollof Co.' ends 'for Peckham Jollof Co.' then the line break"
    requirement: RWO-004
    verification:
      - kind: unit
        ref: "EmailNotificationServiceStaffInviteTest#inviteEmail_shopNameEndingInAFullStop_hasOneFullStop"
        status: pass
    human_judgment: false
  - id: D4
    description: "Every customer status email and its footer name 'Peckham Jollof Co.' with one full stop"
    verification:
      - kind: unit
        ref: "EmailNotificationServiceTest#everyStatusEndsAShopNameWithOneFullStop"
        status: pass
    human_judgment: false
  - id: D5
    description: "The onboarding update states a reason ending in a full stop once, text and HTML; a reason without one still gains one outside the strong element"
    verification:
      - kind: unit
        ref: "EmailTemplateRendererTest#onboardingReasonEndingInAFullStopHasOneFullStop"
        status: pass
    human_judgment: false
  - id: D6
    description: "Live Mailhog proof on the delivered runtime"
    verification: []
    human_judgment: true
    rationale: "Owned by 37-59 (rebuild core-java, read Sentences out of the running jar, Mailhog run); this plan proves the copy in unit and integration tests only"

duration: 12min
completed: 2026-10-16
status: complete
---

# Phase 37 Plan 57: One Java sentence-end rule (G-37-6 defect 2, server side) Summary

**`uk.jtoye.core.common.Sentences` ends every email sentence that closes on a shop name or onboarding reason exactly once. The invitation for "Peckham Jollof Co." no longer reads "Co..". A parity test reads both declarations from 37-56's `frontend/lib/sentence-end.ts`, so the Java rule and the TypeScript rule cannot drift apart.**

## Performance

- **Duration:** about 12 min
- **Started:** 2026-10-16T14:25:09Z
- **Completed:** 2026-10-16T14:37:00Z
- **Tasks:** 2 of 2
- **Files modified:** 11 (2 created, 5 modified source/test files, 4 evidence files)

## Accomplishments

- `Sentences.java`:
  - `TERMINATORS = ".!?…"`, written as literal UTF-8.
  - `endsSentence`, `withFullStop` and `fullStopAfter`.
  - The trim drops `isSpaceChar` code units plus 0x09-0x0D and 0xFEFF. That is the 25-code-unit JavaScript class.
- Routed: seven sites, six of them in `EmailNotificationService`:
  - the invitation;
  - the received, confirmed, preparing and completed leads (the completed lead's second placeholder only);
  - the "sent on behalf of" footer;
  - the onboarding update: `withFullStop` in the text part, `fullStopAfter` after `</strong>` in the HTML part (the reason is still escaped through `sHtml`).
- Not changed:
  - the ready and cancelled copy, and the webhook-paused copy;
  - the order-completed lead's first placeholder, which is mid-sentence;
  - the order of `sanitise`, which still runs first.

## Task Commits

1. **Task 1: one Java rule, parity, the invitation**: RED `77265a25` (test), GREEN `439066bd` (feat).
2. **Task 2: status leads, footer, onboarding reason**: RED `5b9e9cf1` (test), GREEN `7ddf24be` (feat), then `9d1397c8` (test: soft assertions, see Deviations).

## TDD: RED / GREEN evidence

| Task | RED record | Verdict | Target | What failed (semantic) |
|------|-----------|---------|--------|------------------------|
| 1 | evidence/37-57-red-task1.json | RED_EVIDENCE_OK | StaffInviteTest#"G-37-6: a shop whose name ends in a full stop is named once, with one full stop" | body sent: "…as Staff for Peckham Jollof Co.." then the line break. 6 of 7 passed, including both existing copy tests (unedited). |
| 2 | evidence/37-57-red-task2.json | RED_EVIDENCE_OK | EmailNotificationServiceTest#"G-37-6: … every status email and its footer" | [received] body: "has been sent to Peckham Jollof Co.. They will confirm…" and footer "on behalf of Peckham Jollof Co..". No other double full stop in the platform text. 28 of 29 passed. |
| 2 | evidence/37-57-red-task2-renderer.json | RED_EVIDENCE_OK | EmailTemplateRendererTest#"G-37-6 — a reason that already ends in a full stop …" | text read "…an ingredients list.. Open your dashboard". 16 of 17 passed, including `onboardingRendersReason` and `onboardingReasonIsHtmlEscaped`. |

The classifier can fail too. The first Task 1 record named the target by its method name, and the report names tests by their `@DisplayName`. That record returned `INVALID_RED (no_target_test_failure)`. Naming the target by its display name gave `RED_EVIDENCE_OK`.

## Acceptance criteria, both directions

- **Task 1 verify:** `SentencesTest` 8/0/0 and `EmailNotificationServiceStaffInviteTest` 7/0/0, from a `cleanTest`. The report timestamps are 14:27:32Z; the closing run's are 14:29:08Z.
- **Existing tests unedited:**
  - `git diff $PLAN_HEAD_BEFORE..HEAD` over `EmailNotificationServiceStaffInviteTest.java` prints `rc=0` and `0` removed lines.
  - Over `EmailNotificationServiceTest.java` + `EmailTemplateRendererTest.java` it prints `rc=0` and `0`.
  - Fail direction: over `EmailNotificationService.java` it prints `2`, and over `EmailTemplateRenderer.java` `2`.
- **Call-site count:** `rg -uu -c 'Sentences\.withFullStop\(' …/EmailNotificationService.java` gives `1` (rc 0) on the RED tree (`5b9e9cf1`) and `6` (rc 0) after GREEN.
- **No backslash-u escapes:** `rg -uu -c -F '\u'` over `Sentences.java` and `SentencesTest.java` gives rc 1. A planted control file containing one gives rc 0, count 1.
- **Fail arm A** (`withFullStop` appends unconditionally):
  - Red: 5 `SentencesTest` rows (terminators, trailing whitespace, whitespace set, edge rows, agreement) and the new invitation test.
  - Green: the "Mama Ade's Kitchen" row, both parity tests and the 6 other invitation tests.
  - Restored, sha256 `433da0f1…` before = after.
- **Fail arm B** (U+2026 removed from `SENTENCE_TERMINATORS` in `frontend/lib/sentence-end.ts`):
  - Only the terminator parity test is red: `[Sentences.TERMINATORS vs frontend/lib/sentence-end.ts SENTENCE_TERMINATORS] expected: ".!?" but was: ".!?…"`.
  - Restored, sha256 `79d93cb7…` before = after.
- **Fail arm C1** (trim is `Character.isWhitespace(c) || Character.isSpaceChar(c)`):
  - Only the whitespace-set test is red, naming exactly `U+001C`, `U+001D`, `U+001E`, `U+001F` "dropped but not declared" and `U+FEFF` "declared but not dropped".
  - Restored by sha256.
- **Fail arm C2** (`0xfeff` deleted from `TRAILING_SPACE_CODE_POINTS`):
  - Only the code-point parity test is red: "could not find the following elements: [65279]".
  - Restored by sha256.
  - Closing clean run 15/15.
- **Task 2 verify:**
  - Unit `uk.jtoye.core.common.*` + `uk.jtoye.core.notification.*`: 18 classes, 146 tests, 0 failures.
  - Integration `uk.jtoye.core.notification.*`: 7 classes, 22 tests, 0 failures, against Testcontainers Postgres. This includes `CustomerEmailSellerIntegrationTest` and `OrderNotificationListenerIntegrationTest`.
- **Task 2 fail arm** (literal full stop put back after the shop in the footer):
  - Only `everyStatusEndsAShopNameWithOneFullStop` is red, with a failure for each of the six statuses (received, confirmed, preparing, ready, completed, cancelled). The other 145 tests are green.
  - Restored, sha256 `9b7d5be4…` before = after. Closing clean run 146/0.
- **Plan verification 1:** the full unit suite, `./gradlew :core-java:cleanTest :core-java:test --no-daemon`, gave rc 0: 214 classes, 1852 tests, 0 failures.

## New @Test counts (docs/metrics.json is 37-59's)

| File | Before | After |
|------|--------|-------|
| `SentencesTest.java` | 0 (new file) | 8 |
| `EmailNotificationServiceStaffInviteTest.java` | 6 | 7 |
| `EmailNotificationServiceTest.java` | 28 | 29 |
| `EmailTemplateRendererTest.java` | 16 | 17 |

That is 11 new Java `@Test` methods in 1 new file. Every one is a plain `@Test`.

## Goods displaced and kept

- **Displaced:** none.
- **Kept:**
  - Every name or reason that does not end in a terminator is sent byte for byte as before. All the existing copy, parity, CR/LF, non-ASCII, HTML-escaping and seller-block tests passed unedited (0 removed lines in all three existing test files).
  - `sanitise` still runs before `Sentences`.
  - The plain-text-only rule, Reply-To and From are untouched.
  - The blank-reason onboarding branch is unchanged.

## Decisions Made

See key-decisions.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug in a test's diagnostics] The status-email test stopped at the first failing status**
- **Found during:** Task 2 fail arm.
- **Issue:** The test used hard assertions in its STATUSES loop. With the footer full stop put back, it went red (correct, and nothing else did) but named only `[received]`. The criterion asks to see it red for every status.
- **Fix:** `SoftAssertions` across the loop with a single `assertAll()`. The assertions are the same, and none is weakened. Re-run on GREEN: 146/0. Arm re-run: all six statuses named.
- **Files modified:** `EmailNotificationServiceTest.java` (the new test only; the existing tests are still unedited, 0 removed lines from PLAN_HEAD_BEFORE).
- **Commit:** `9d1397c8`

**2. [Process] Task 2's RED is two records, not one**
- The plan names one file, `evidence/37-57-red-task2.json`, "for both new tests". The classifier takes one JUnit report per record, and the two tests are in two classes. So the same run is recorded as `37-57-red-task2.json` (EmailNotificationServiceTest) and `37-57-red-task2-renderer.json` (EmailTemplateRendererTest). Both are RED_EVIDENCE_OK. No report was combined or edited.

**3. [Environment] The RED-record builder was written in Node, not Python**
- `block-base-python` refused `/usr/bin/python3` for a script under the session scratchpad. I treated the refusal as the answer and wrote the same builder in Node. It copies each report unchanged into `output`.

## Issues Encountered

None beyond the deviations above.

## Threat model

- **T-37-131:** `Sentences` runs after `sanitise`. It adds at most one full stop and removes only trailing whitespace. `fullStopAfter` chooses only between "" and ".". `crlfInShopNameCannotInjectAHeader`, `otherControlCharactersAreStripped`, `inviteEmail_tenantControlledNamesAreSanitised` and `onboardingReasonIsHtmlEscaped` are green and unedited.
- **T-37-132:** mitigated by the parity tests and arms B, C1 and C2.
- **T-37-SC:** no package installed.

No new security surface.

## User Setup Required

None.

## Next Phase Readiness

37-59 owns:
- rebuilding core-java and reading `uk/jtoye/core/common/Sentences.class` out of the running jar;
- the live Mailhog run (an invitation and an order email for "Peckham Jollof Co.");
- `docs/metrics.json` (+11 Java `@Test`, +1 file).

## Self-Check: PASSED

- FOUND: core-java/src/main/java/uk/jtoye/core/common/Sentences.java
- FOUND: core-java/src/test/java/uk/jtoye/core/common/SentencesTest.java
- FOUND: evidence/37-57-plan-head-before.txt, 37-57-red-task1.json, 37-57-red-task2.json, 37-57-red-task2-renderer.json
- FOUND (ancestors of HEAD): 77265a25, 439066bd, 5b9e9cf1, 7ddf24be, 9d1397c8
