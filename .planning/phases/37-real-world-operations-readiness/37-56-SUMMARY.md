---
phase: 37-real-world-operations-readiness
plan: 56
subsystem: ui
tags: [typescript, copy, sentence-end, jest, source-guard, g-37-6]

requires:
  - phase: 37-real-world-operations-readiness
    provides: "the six surfaces measured in planning (allergen panel 31.1, order page D-15, void 37-10, joined toast 37-09, copy-to-shop 37-13, remove-access 37-06)"
provides:
  - "frontend/lib/sentence-end.ts: SENTENCE_TERMINATORS, TRAILING_SPACE_CODE_POINTS, endsSentence, withFullStop (the one TypeScript sentence-end rule)"
  - "six frontend call sites routed through withFullStop"
  - "frontend/__tests__/sentence-end-call-sites.test.ts: per-call-site tests and a source guard with planted controls and an exactly-once allowlist"
  - "evidence/37-56-g376-base.txt: G376_BASE for the 37-56..37-59 gap set"
affects: [37-57, 37-59]

actuals:
  tokens: 17000
  tasks: 3
  commits: 7
plan_head_before: 36f9aee357b4d96f3718239d4d76903ae41a9099
plan_head_after: f1f75a64d30923aaecb2f7539db4bb4db9ad3cfe

tech-stack:
  added: []
  patterns:
    - "A sentence that ends with a free-text value ends it through withFullStop; a template never appends its own full stop after such a value"
    - "Source guard in __tests__/ (Node fs + RegExp, no child process) with planted controls on every run and an allowlist each entry of which must match exactly once"

key-files:
  created:
    - frontend/lib/sentence-end.ts
    - frontend/lib/__tests__/sentence-end.test.ts
    - frontend/__tests__/sentence-end-call-sites.test.ts
    - .planning/phases/37-real-world-operations-readiness/evidence/37-56-g376-base.txt
    - .planning/phases/37-real-world-operations-readiness/evidence/37-56-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-56-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-56-red-task3.json
  modified:
    - frontend/components/storefront/order-allergen-panel.tsx
    - frontend/components/storefront/__tests__/order-allergen-panel.a11y.test.tsx
    - frontend/components/storefront/recorded-allergen-set.tsx
    - frontend/components/dashboard/orders/void-order-dialog.tsx
    - frontend/lib/joined-toast.ts
    - frontend/components/dashboard/products/copy-to-shop-dialog.tsx
    - frontend/app/dashboard/products/__tests__/import-copy.test.tsx
    - frontend/app/dashboard/staff/page.tsx
    - frontend/app/dashboard/__tests__/staff-page.test.tsx

key-decisions:
  - "G376_BASE = 36f9aee357b4d96f3718239d4d76903ae41a9099 (HEAD before the first 37-56 commit; gap-set subject count over it 0, rc 1, control (37-55) count 3) - no fallback taken"
  - "withFullStop trims with String.prototype.trimEnd (the JavaScript whitespace class); TRAILING_SPACE_CODE_POINTS states that set and is NOT read by the trim, so the exhaustive 0x0000-0xFFFF row compares two independent things"
  - "Both declarations stay on one line with no type annotation or assertion (as const was dropped) so 37-57's Java parity regex can read them"
  - "Guard finding = template or JSX interpolation whose expression contains a FREE_TEXT_TOKEN, followed by a full stop that is followed by whitespace, backtick, quote, < or end of line; expressions with nested braces are not matched (none of the routed sites has one)"

patterns-established:
  - "withFullStop(value) in place of `${value}.` wherever a vendor- or person-supplied value ends a sentence"

requirements-completed: [RWO-003, RWO-004, RWO-018, RWO-026]

coverage:
  - id: D1
    description: "One TypeScript sentence-end rule (withFullStop) with a literal table and an exhaustive whitespace-set row"
    verification:
      - kind: unit
        ref: "frontend/lib/__tests__/sentence-end.test.ts"
        status: pass
    human_judgment: false
  - id: D2
    description: "Checkout allergen panel names 'Peckham Jollof Co.' once with one full stop; 'Mama Ade's Kitchen' unchanged"
    requirement: RWO-004
    verification:
      - kind: unit
        ref: "frontend/components/storefront/__tests__/order-allergen-panel.a11y.test.tsx#names a kitchen whose name ends in a full stop once, with one full stop (G-37-6)"
        status: pass
      - kind: other
        ref: "./gradlew :core-java:test --tests uk.jtoye.core.notification.EmailNotificationServiceTest (28/0/0, storefront wording parity)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Order-page allergy-note, void conflict (no-date), joined toast (business and scope) end a name once"
    requirement: RWO-018
    verification:
      - kind: unit
        ref: "frontend/__tests__/sentence-end-call-sites.test.ts#call sites end a vendor-named sentence once (G-37-6)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Copy-to-shop result and Staff remove-access consequence end a shop name once"
    requirement: RWO-026
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/products/__tests__/import-copy.test.tsx (anchored copy-result assertion)"
        status: pass
      - kind: unit
        ref: "frontend/app/dashboard/__tests__/staff-page.test.tsx#names a remaining shop whose name ends in a full stop once, with one full stop (G-37-6)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Source guard keeps withFullStop the only way a free-text value ends a sentence"
    verification:
      - kind: unit
        ref: "frontend/__tests__/sentence-end-call-sites.test.ts#guard: a free-text value ends a sentence only through withFullStop (G-37-6)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Live proof on the delivered runtime (checkout at Peckham Jollof Co.)"
    verification: []
    human_judgment: true
    rationale: "Owned by 37-59 (rebuild ALL, freshness, live run); this plan proves the copy in jest only"

duration: 10min
completed: 2026-10-16
status: complete
---

# Phase 37 Plan 56: One TypeScript sentence-end rule (G-37-6 defect 2) Summary

**`withFullStop` in `frontend/lib/sentence-end.ts` ends every vendor- or person-named frontend sentence once — "prepared by Peckham Jollof Co." no longer reads "Co.." — routed through six call sites and held by a source guard with planted controls.**

## Performance

- **Duration:** about 10 min
- **Started:** 2026-10-16T14:00:30Z
- **Completed:** 2026-10-16T14:10:30Z
- **Tasks:** 3 of 3
- **Files modified:** 16 (9 source/test files modified, 3 created, 4 evidence files)

## G376_BASE

`36f9aee357b4d96f3718239d4d76903ae41a9099`, written as the only line of
`evidence/37-56-g376-base.txt` before Task 1's first commit and carried by that commit (0426c8f1).
The gap-set subject count over it was 0 (`rg -c '\((37-56|37-57|37-58|37-59)\)'` rc 1); the
positive control `(37-55)` over the same log counted 3 (rc 0). No fallback taken.
`git cat-file -e "$(cat …/37-56-g376-base.txt)^{commit}"` rc 0; fail arm `deadbeef^{commit}` rc 128.

## Accomplishments

- `frontend/lib/sentence-end.ts`: `SENTENCE_TERMINATORS = ".!?…"`, `TRAILING_SPACE_CODE_POINTS` (25 code points on one line), `endsSentence`, `withFullStop`. The trim is `trimEnd`; the declared list is checked against it over every code unit 0x0000-0xFFFF.
- Six call sites routed: `allergenPanelIntroCopy` (customer-facing; the observed defect), `ALLERGY_NOTE_SENT_COPY`, `alreadyVoidedCopy` (no-date branch), `joinedToastTitle` (business and scope), `copyResultSentence` (copied-to and nothing-copied), Staff `removeDescription`.
- Source guard: scans 289 `.ts`/`.tsx` files under app/, components/, lib/, hooks/; finds exactly the two ALLOW entries once each and nothing else after GREEN.

## Task Commits

1. **Task 1: one rule, wired through the checkout allergen panel** - RED `0426c8f1` (test), GREEN `090e4ec4` (feat), `da216255` (test: the whitespace row names the disagreeing code unit first; see Deviations)
2. **Task 2: order-page, void and joined-toast sentences** - RED `75a1e50e` (test), GREEN `613d6d72` (feat)
3. **Task 3: copy-result, remove-access, the corrected test and the guard** - RED `81df4cf1` (test), GREEN `f1f75a64` (feat)

## TDD: RED / GREEN evidence

| Task | RED record | Verdict | Target | What failed (semantic) |
|------|-----------|---------|--------|------------------------|
| 1 | evidence/37-56-red-task1.json | RED_EVIDENCE_OK | panel: names a kitchen whose name ends in a full stop once | intro rendered "…prepared by Peckham Jollof Co.. Based on…"; 25/26 panel tests passed; the helper suite's "Cannot find module '../sentence-end'" is the expected new-module fault, recorded and not the semantic RED |
| 2 | evidence/37-56-red-task2.json | RED_EVIDENCE_OK | allergy note sent: 'Peckham Jollof Co.' | 3 terminator cases red on "..", 4 goods green (incl. the dated void branch) |
| 3 | evidence/37-56-red-task3.json | RED_EVIDENCE_OK | guard: no free-text value outside the allowlist… | 5 failed / 67 passed; guard listed exactly the three expected findings (below); planted-controls and allowlist tests passed in the same run |

Guard RED output, verbatim:
```
"app/dashboard/staff/page.tsx:711 {remaining.join(\"; \")}",
"components/dashboard/products/copy-to-shop-dialog.tsx:41 {shopName}",
"components/dashboard/products/copy-to-shop-dialog.tsx:48 {shopName}",
```

## Acceptance criteria, both directions

- `rg -uu -c -F 'withFullStop(vendorName)' frontend/components/storefront/order-allergen-panel.tsx`: RED tree rc 1 (no output); GREEN `1` rc 0.
- `git diff --quiet "$G376_BASE"..HEAD -- core-java/.../EmailNotificationServiceTest.java` rc 0; fail direction over `order-allergen-panel.tsx` rc 1.
- `EmailNotificationServiceTest` (unedited) 28 tests, 0 failures, after Task 1 and again after Task 2 (report timestamps 14:03:54Z, 14:06:02Z; `parity: every mirrored sentence equals the storefront constant it quotes` present).
- Task 2 commits `75a1e50e` + `613d6d72` list only the four task files plus `evidence/37-56-red-task2.json` (the record this plan requires); no Java.
- Fail arm (Task 1, unconditional stop after trim): terminator rows red (Co., !, ?, …, trailing space, NBSP, two stops, whitespace set, endsSentence agreement) and the new panel test red; "Mama Ade's Kitchen" rows and the existing VENDOR intro test green. Restored, sha256 `79d93cb7…` before = after.
- Fail arm (Task 1, 0xfeff deleted from the declaration): only the whitespace-set row red, `+ "U+FEFF dropped but not declared"`; 41 other tests green. Restored by sha256; closing clean run 42/42.
- Fail arm (Task 2, literal stop put back after shopName): only "allergy note sent: 'Peckham Jollof Co.'" red, 51 green across the four suites. Restored by sha256; closing clean run 52/52.
- Fail arm A (Task 3, literal stop put back in removeDescription): the staff-page test AND the guard red, guard naming `app/dashboard/staff/page.tsx:712 {remaining.join("; ")}`; 70 green. Restored by sha256.
- Fail arm B (stale third allowlist entry): guard red, `- "app/legal/privacy/page.tsx staleEntry.name: 1"` / `+ "…: 0"`. Removed by sha256 restore; closing clean run 72/72.
- Full gate: `npm --prefix frontend test` 252 suites / 2999 tests, `npx tsc --noEmit`, `npm run lint` → `rc=0 0 0`. Lint 0 errors, 29 pre-existing warnings, none in a touched file (grep over the warning file list rc 1; control `playwright.config` in the same list counted 1).

## Files Created/Modified

- `frontend/lib/sentence-end.ts` - the rule and its two declarations
- `frontend/lib/__tests__/sentence-end.test.ts` - the literal table, exhaustive whitespace row, endsSentence agreement
- `frontend/__tests__/sentence-end-call-sites.test.ts` - per-call-site tests and the source guard
- `frontend/components/storefront/order-allergen-panel.tsx` - intro ends with `withFullStop(vendorName)`; constants and `allergenFlagCopy` byte-identical
- `frontend/components/storefront/recorded-allergen-set.tsx`, `frontend/components/dashboard/orders/void-order-dialog.tsx`, `frontend/lib/joined-toast.ts`, `frontend/components/dashboard/products/copy-to-shop-dialog.tsx`, `frontend/app/dashboard/staff/page.tsx` - routed
- `frontend/app/dashboard/products/__tests__/import-copy.test.tsx` - anchored copy-result assertion
- `frontend/app/dashboard/__tests__/staff-page.test.tsx`, `frontend/components/storefront/__tests__/order-allergen-panel.a11y.test.tsx` - new terminator tests

## Goods displaced and kept

- **Displaced (one assertion):** `import-copy.test.tsx` asserted `2 copied to ${LONG_NAME}.` with LONG_NAME ending "Ltd." — it encoded the double full stop, so it was evidence of the defect, not a good. Replaced by the anchored `^2 copied to <LONG_NAME>$`, which failed on the pre-fix tree ("…Ltd..").
- **Kept:** every sentence for a value that does not end in a terminator renders byte-for-byte as before (each call site has a passing ordinary-name case, green before and after); the dialog's wrapping and width assertions; the existing "Staff · Brixton Bakery." and VENDOR intro tests; the 409 "on {date}" form; the remove-access title and no-access sentence; the panel's three states and acknowledgement; every exported string constant the Java parity test mirrors.

## Decisions Made

See key-decisions. The ALLOW entries are recorded in the guard with a reason each: `app/legal/accessibility/page.tsx` `{surface.name}` (constants in that file) and `app/shop/[slug]/checkout/page.tsx` `{fulfilmentType === "COLLECTION" ? "shop" : "driver"}` (two literal words).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug in a test's diagnostics] The whitespace-set row did not name U+FEFF in its fail arm**
- **Found during:** Task 1 fail arm (whitespace set)
- **Issue:** the row asserted the literal 25-entry list before the exhaustive loop, so deleting 0xfeff failed on the list diff (`- 65279`) and never reached the message naming the code unit, which the acceptance criterion requires.
- **Fix:** assert the exhaustive disagreement list first, then the literal list. Re-ran the arm: only that row red, `"U+FEFF dropped but not declared"`.
- **Files modified:** frontend/lib/__tests__/sentence-end.test.ts
- **Committed in:** da216255 (a test-only commit after Task 1's GREEN; the behaviour and the GREEN implementation are unchanged)

**2. [Rule 1 - Instrument] Task 1's first fail arm used the trimmed form**
- **Found during:** Task 1 fail arm (unconditional stop)
- **Issue:** a first arm `return \`${text}.\`` also skipped the trim, so the tab row went red too, which says nothing about the terminator decision.
- **Fix:** the recorded arm is `return \`${trimmed}.\`` (unconditional stop after the trim): terminator rows red, the two "Mama Ade's Kitchen" rows green, as the criterion states. Both arms restored by sha256.

---

**Total deviations:** 2 auto-fixed (test diagnostics, fail-arm shape). No source scope change.

## Issues Encountered

- `scripts/docs-freshness.sh` reports drift (rc 1, total_logical_invocations 6180) because this plan adds jest blocks. **37-59 owns `docs/metrics.json`** and the quoted counts (its Task with `docs-freshness.sh --write` and `check-doc-metrics.sh`); not regenerated here. The script produced counts rather than VOID, so the new tests are literal `it(` blocks it can count.

## Next Phase Readiness

- 37-57's Java parity test can read `SENTENCE_TERMINATORS` and `TRAILING_SPACE_CODE_POINTS` from `frontend/lib/sentence-end.ts` (each one line, no annotation or assertion).
- 37-59 reads G376_BASE from `evidence/37-56-g376-base.txt` and owns the live proof on the rebuilt runtime and the metrics regeneration.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-16*

## Self-Check: PASSED

All 7 created files present; all 7 task commits (0426c8f1, 090e4ec4, da216255, 75a1e50e, 613d6d72, 81df4cf1, f1f75a64) are ancestors of HEAD; control sha deadbee0 reported missing.
