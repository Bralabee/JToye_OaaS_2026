---
phase: 37-real-world-operations-readiness
reviewed: 2026-10-16T15:56:45Z
depth: deep
scope: delta
diff_base: 36f9aee3
diff_head: e88d3503
files_reviewed: 29
files_reviewed_list:
  - core-java/src/main/java/uk/jtoye/core/common/Sentences.java
  - core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java
  - core-java/src/main/java/uk/jtoye/core/notification/template/EmailTemplateRenderer.java
  - core-java/src/test/java/uk/jtoye/core/common/SentencesTest.java
  - core-java/src/test/java/uk/jtoye/core/notification/EmailNotificationServiceStaffInviteTest.java
  - core-java/src/test/java/uk/jtoye/core/notification/EmailNotificationServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/notification/template/EmailTemplateRendererTest.java
  - frontend/__tests__/sentence-end-call-sites.test.ts
  - frontend/app/dashboard/__tests__/staff-page.test.tsx
  - frontend/app/dashboard/products/__tests__/import-copy.test.tsx
  - frontend/app/dashboard/staff/page.tsx
  - frontend/app/dashboard/finance/page.tsx
  - frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx
  - frontend/app/dashboard/products/page.tsx
  - frontend/components/dashboard/orders/void-order-dialog.tsx
  - frontend/components/dashboard/products/copy-to-shop-dialog.tsx
  - frontend/components/storefront/__tests__/order-allergen-panel.a11y.test.tsx
  - frontend/components/storefront/order-allergen-panel.tsx
  - frontend/components/storefront/recorded-allergen-set.tsx
  - frontend/components/ui/__tests__/pagination.test.tsx
  - frontend/components/ui/pagination.tsx
  - frontend/e2e/screens-copy-uat.spec.ts
  - frontend/lib/__tests__/sentence-end.test.ts
  - frontend/lib/joined-toast.ts
  - frontend/lib/sentence-end.ts
  - docs/metrics.json
  - README.md
  - CLAUDE.md
  - AGENTS.md
findings:
  critical: 0
  warning: 2
  info: 5
  total: 7
status: issues_found
---

# Phase 37: Code Review Report (delta: G-37-6, 37-56..37-59)

**Reviewed:** 2026-10-16T15:56:45Z
**Depth:** deep
**Files Reviewed:** 29
**Status:** issues_found

## Scope note

This is a **delta review of Phase 37**, covering gap G-37-6 (plans 37-56..37-59) over `36f9aee3..e88d3503`. That
base is the pre-gap commit recorded in `evidence/37-56-g376-base.txt`. Hunks that arrived through the merge of
origin/main #926 (`db2887ca`) were ignored unless they touched the delta. This report replaces the previous
delta report (quick 261016-edv).

## Summary

The core of the gap is sound. Both languages share one sentence-end rule:

- `withFullStop` in TypeScript and `Sentences` in Java behave identically.
- Each language's trim is checked exhaustively over 0x0000-0xFFFF.
- The declarations are parity-tested across languages, and `locateRepoFile` fails closed when the TS file is missing.

**Checks run (read-only):**

- **Call sites.** Every frontend call site routed by 37-56 was traced to its callers. None can now receive `undefined`: each has a string fallback (`shop?.name ?? "this kitchen"`, `shopName ?? "the shop"`, `?? "the other shop"`). On the Java side, every `withFullStop`/`fullStopAfter` argument is non-null by construction.
- **Unrouted sentence ends.** A wider scan found no unrouted free-text sentence end in `app/ components/ lib/ hooks/`. It covered the guard's regex without its token filter, `}</strong>.`, `+ "."`, and a lone `.` on its own JSX line. It also found none in `notification/`.
- **Jest.** The seven touched jest suites pass: 145/145, rc 0.
- **Doc metrics.** `scripts/docs-freshness.sh` (rc 0, 6208) and `scripts/check-doc-metrics.sh` (rc 0, 37/37 claims) both pass. The README, CLAUDE.md and AGENTS.md metric edits match `docs/metrics.json`.
- **Finance grids.** Each grid's container is its own parent. The thresholds follow the measured need of the declared bound, and the e2e spec steers each container to exactly its threshold.

**Concerns:**

- **WR-01.** The tiered Pagination's 21rem and 27rem tiers have only about 4 px of slack, measured with one- and two-digit page labels. Labels of 1000 or more push the controls back outside the card, which is the G-37-6 defect 3 class. Nothing measures labels that wide.
- **WR-02.** The pagination instrument arm passes on its 32 px size failures alone. So it never shows that the "control leaves the card" check can fail.

## Warnings

### WR-01: Pagination tiers overflow the card again once page labels reach four digits

**File:** `frontend/components/ui/pagination.tsx:60-61, 88` (with `frontend/e2e/screens-copy-uat.spec.ts:745-811`, `frontend/components/ui/__tests__/pagination.test.tsx:20-21`)

**Issue:** The thresholds assume every control is exactly 44 px:

- **21rem (336 px):** seven controls are 7 x 44 + 6 x 4 = 332 px.
- **27rem (432 px):** nine controls are 9 x 44 + 8 x 4 = 428 px.

37-59 recorded the live margins as "20/4/4 px", measured on the dev ledger, whose page labels are one or two digits.

The page buttons, however, are `min-w-11 px-2` with a 1 px border on the outline variant. Their width is therefore max(44, label + 16 + 2). Label widths were measured in Chromium with Work Sans at 14 px (the dashboard font, `app/layout.tsx`):

| Label | Regular | Bold |
|-------|---------|------|
| "1000" | 32 px | 33 px |
| "8888" | — | 36 px |
| "888" | 27 px | — |

So a four-digit outline button is about 50 px and the current-page button about 48 px:

- **Three-page tier at 21rem:** 2 x 50 + 48 = 148 px against the 132 px budgeted. That is 16 px extra against 4 px of slack, so it overflows across container widths of about 336-348 px.
- **Five-page tier at 27rem:** 4 x 50 + 48 = 248 px against 220 px. That is 28 px extra, so it overflows across about 432-456 px.

The nav is `min-w-0` with `shrink-0` children and no wrap. The last-page button therefore paints outside the card, which is exactly the defect 3 symptom. Wide three-digit labels (for example "888" at 45 px outline) already use up the whole 4 px margin.

The orders list pages at 20, so this begins at 19,981 orders on a tenant's unfiltered list. A Group admin of a busy multi-shop tenant reaches that within a year.

No instrument covers this case:

- The jsdom test uses 24 pages.
- The live spec reads whatever the dev stack holds, at fixed viewports.
- Unlike the finance grids, the live spec never steers the Pagination container to its own 19/21/27rem thresholds. Yet the spec's own finance comment (lines 678-681) records that fixed widths "never land a container on a threshold, so a threshold below the figure's need passes them all".

**Fix:** Size the tiers for the widest label the component can show, or stop the row from overflowing:

```tsx
// Option A: thresholds from the widest label (4-digit labels ~50 px): near tier 4x44 + 3x50 + 6x4 = 350 px -> 22rem,
// five-page tier 4x44 + 5x50 + 8x4 = 458 px -> 29rem (re-measure in Chrome, then round up).
// Option B: drop to the compact "Page N of M" tier when totalPages >= 1000, or let the page buttons wrap:
<nav className="flex min-w-0 flex-wrap items-center gap-1" aria-label="Pagination">
```

Then add a live arm. Steer the Pagination root to exactly 21rem and 27rem (reusing `steerFinanceContainer`'s approach), with a routed list response whose `totalElements` gives `totalPages >= 1000` and `currentPage >= 999`. Add a jsdom case with `totalPages: 1500` that pins whichever option is chosen.

### WR-02: The pagination instrument arm cannot show that the containment check fails

**File:** `frontend/e2e/screens-copy-uat.spec.ts:797-811`

**Issue:** The arm forces every page button to `width/height: 32px` and then asserts only `armed.failures.length > 0`. `measureControls` pushes an "under 44 x 44" failure for every 32 px control, so the arm passes on the size check alone, whatever the containment checks do.

Defect 3 had two halves: controls outside the card or viewport, and targets under 44 px. The arm therefore never shows that the "leaves the card" or "leaves the viewport" branches (lines 487-492) can fail. A regression there, such as a wrong `.bg-card` ancestor or broken rect arithmetic, would leave the main assertion green and the arm green.

The spec already recognised this shape for the void dialog: its second arm (lines 921-933) forces the title check's own fail direction "because the 600 px arm is caught by the viewport check". The finance arm (line 731) has a milder form of the same weakness: a `VOID:` entry in `boxFailures` would also satisfy `> 0`.

**Fix:** Make each arm assert the failure it exists to prove, and add a 44 px containment arm:

```ts
expect(armed.failures.some((f) => f.includes("leaves the card")), "containment check fires").toBe(true)
expect(armed.failures.some((f) => /under 44 x 44/.test(f)), "size check fires").toBe(true)
// and a containment-only arm: keep 44 px controls but force all nine visible and unwrapped at 390
const forced44 = await page.addStyleTag({ content:
  'nav[aria-label="Pagination"] button { display: inline-flex !important; } nav[aria-label="Pagination"] > span { display: none !important; }' })
// expect failures to contain "leaves the card" and NOT "under 44"
// finance arm: expect(armed.boxFailures.filter((f) => !f.startsWith("VOID")).length).toBeGreaterThan(0)
```

## Info

### IN-01: The Java parity test's input is not a Gradle input, so a TypeScript-only edit leaves `:core-java:test` UP-TO-DATE

**File:** `core-java/src/test/java/uk/jtoye/core/common/SentencesTest.java:26, 121-146`; `core-java/build.gradle.kts:386-411`

**Issue:** `SentencesTest` reads `frontend/lib/sentence-end.ts` at runtime. `tasks.test` declares only `productionRuntimeClasspath` as an extra input. After an edit to the TS declarations alone, a local `./gradlew :core-java:test` reports UP-TO-DATE and the parity test never runs, which is the "build reporting success while executing nothing" class.

CI is unaffected: it checks out fresh, with no Gradle build cache and with `build-local` not cached. The existing `EmailNotificationServiceTest` storefront-parity test has the same property.

**Fix:** In `tasks.test`, add `inputs.files(rootProject.files("frontend/lib/sentence-end.ts", "frontend/components/storefront/seller-block.tsx", "frontend/components/storefront/recorded-allergen-set.tsx", "frontend/components/storefront/order-allergen-panel.tsx")).withPropertyName("frontendParitySources")`.

### IN-02: The sentence-end source guard is a per-line deny-list of identifier tokens

**File:** `frontend/__tests__/sentence-end-call-sites.test.ts:109-111`

**Issue:** The guard finds a free-text value only when its expression contains one of nine tokens (`name|business|shop|vendor|seller|trader|scope|remaining|reason`) and the full stop is on the same line. The following bypass it silently:

- a value bound to another identifier, such as `${title}.`, `${label}.`, `${inviter}.` or `${who}.`;
- a template split across lines;
- string concatenation (`x + "."`).

No current site bypasses it: the wider scan found none. The guard's own header documents the nested-brace limit but not these. Per the project's "deny-list guards fail open" lesson, it protects only the names it already knows.

**Fix:** Document the limit in the guard header, or invert it. Flag every `${…}.` sentence end except an allow-list of known non-free-text expressions (counts, dates, enums), with each allow-list entry required to match exactly once, as the current `ALLOWLIST` is.

### IN-03: The onboarding email's HTML and text paths can disagree on trailing whitespace; the "after sanitise" contract is not followed there

**File:** `core-java/src/main/java/uk/jtoye/core/notification/template/EmailTemplateRenderer.java:120-130`; `core-java/src/main/java/uk/jtoye/core/common/Sentences.java:24-26`

**Issue:** The text path uses `withFullStop(reason)`, which trims trailing whitespace. The HTML path interpolates the untrimmed `reasonHtml` and appends `fullStopAfter(reason)`, which also decides on the trimmed value. A reason ending in a space or newline therefore renders `<strong>… pending </strong>. Open` in HTML, a visible space before the stop, while the text reads `pending. Open`.

`Sentences`' Javadoc says that in email code the rule is applied after `EmailNotificationService.sanitise`. This renderer never sanitises the reason. That does not open header injection, because the reason never reaches the subject.

**Fix:** Normalise once and use the result for both paths, for example `String reason = s(m, "reason").strip();`, then `sHtml` over the stripped value. Alternatively, reword the Javadoc to "after any normalisation the caller applies".

### IN-04: One vendor-typed sentence end in email copy still bypasses the rule (onboarding gate reason)

**File:** `core-java/src/main/java/uk/jtoye/core/onboarding/gate/AllergenCompletenessGate.java:115-122` (not in the delta file list; reached through the delta's onboarding email)

**Issue:** `buildReason` joins vendor-supplied SKUs and appends `". Each product needs …"`. A SKU ending in a full stop produces `ABC-1.. Each product` in the dashboard reason and in the onboarding email. The new `EmailTemplateRendererTest` uses exactly this sentence shape, but with SKUs that do not end in a stop. The rule's stated contract is that "every email sentence that ends with such a value ends it through `withFullStop`", and this sentence ends with a vendor value.

**Fix:**

```java
+ Sentences.withFullStop(String.join(", ", named) + overflow) + " Each product needs …"
```

When `overflow` is present, the sentence ends in `)`, so the rule adds the stop correctly.

### IN-05: Stale or over-claiming coverage comments

**File:** `frontend/components/ui/pagination.tsx:20-21`; `frontend/components/ui/__tests__/pagination.test.tsx:15-17`; `frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx:429-430`

**Issue:**

- `pagination.tsx` still says "These are starting thresholds: 37-59 measures the real widths in Chrome and raises a threshold …". 37-59 has run, so this is now future tense about a past step. It also reads as if the Pagination thresholds were steered and measured at their edges, which they were not (see WR-01).
- Both jsdom suites state that "whether Tailwind EMITS each container rule is checked against the built stylesheet". No per-PR check does this. `37-58-SUMMARY.md` itself records two facts:
  - The built-CSS grep "cannot fail once the RED test exists", because Tailwind's content globs include `__tests__/`. The tests' literal class strings are therefore emitted whatever the source does.
  - The falsifiable form is `evidence/37-58-tw-emits.js`, a one-off probe that nothing runs.
- Only the live spec, which runs at the gate and nightly and never per PR, catches a non-emitted variant.

**Fix:** Change the `pagination.tsx` note to past tense with the measured margins (20/4/4 px at one- and two-digit labels). Restate the two test headers as "emission was probed once by `evidence/37-58-tw-emits.js` (37-58); per PR nothing checks it, and the live spec catches a missing variant".

---

_Reviewed: 2026-10-16T15:56:45Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: deep_
