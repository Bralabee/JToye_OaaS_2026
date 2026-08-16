---
phase: 31-consumer-safety-and-legal-floor
plan: 13
subsystem: frontend-legal
tags: [accessibility, wcag, legal, a11y, conformance, gate, staleness]
status: COMPLETE — both tasks; one owner question returned unanswered (remediation deadlines)

# Dependency graph
requires:
  - "31-02 (jsx-a11y layer + the UNASSERTED_SITES contrast ledger; NO rule was downgraded)"
  - "31-03 (skip link on PublicShell, /auth/signin shell — closed items, not listed as outstanding)"
  - "31-08 (PolicyPage/PolicySection, resolveControllerContact, the verbatim registered-office text)"
provides:
  - "frontend/lib/accessibility-statement.ts — the declared claim, dates, scope and 7 dated exceptions a gate can read"
  - "frontend/__tests__/accessibility-statement-dates.test.ts — the staleness gate; a past nextReviewDue or an overdue remediationBy reds the build"
  - "/legal/accessibility — the published partial WCAG 2.1 AA conformance statement"
  - "A verified, re-measured finding list: what is genuinely open on the declared surfaces and what the QA council list has since closed"
affects:
  - "31-14 (A11Y-07/A11Y-08 — this statement is written as though both are FIXED; see the merge gate)"
  - "31-17 (footer Legal column reaches /legal/accessibility)"
  - "31-18 (owns the re-measurement; must reconcile this exception list against the final audit)"

tech-stack:
  added: []
  patterns:
    - "Claim-as-literal-type: `claim: \"partial\"` is a one-value union, so upgrading the conformance claim is a type error rather than an edit"
    - "Exact allow-list over deny-list for year literals in source — a deny-list only catches the shapes somebody thought of"
    - "Hand-parsed ISO dates: new Date(\"YYYY-MM-DD\") is midnight UTC and formats as the previous day west of UTC"

key-files:
  created:
    - frontend/lib/accessibility-statement.ts
    - frontend/__tests__/accessibility-statement-dates.test.ts
    - frontend/app/legal/accessibility/page.tsx
    - frontend/app/legal/__tests__/accessibility-page.test.tsx
  modified:
    - docs/metrics.json

key-decisions:
  - "The claim is PARTIAL and is enforced by the type system, not by review"
  - "31-02 downgraded NO jsx-a11y rule, so there are no lint-downgrade exceptions; its 55-pair contrast ledger IS a live declared-surface gap and is published as one"
  - "A11Y-16 and A11Y-13 were re-measured at 0 on the declared surfaces (control arm: 5 dashboard hits) and are deliberately NOT published — publishing a fixed finding is as inaccurate as omitting a live one"
  - "A11Y-06 is only PARTIALLY closed: the storefront shell has no skip link, and no plan in this phase closes it. Published as a dated exception"
  - "A11Y-14 verified live: 6 visible asterisks vs 3 programmatic `required` on checkout. Published"
  - "nextReviewDue is 6 months, not the permitted 12 — the measurement underneath carries a short validity window"
  - "Remediation DEADLINES are returned to the owner as an open question; the dates shipped are engineering estimates that now red the build when missed"

requirements-completed: [LGL-02]

metrics:
  tasks_completed: 2
  tasks_total: 2
  jest_blocks: "1008 -> 1036"
  jest_files: "106 -> 108"
  total_logical_invocations: "2951 -> 2979"
completed: 2026-08-16
---

# Phase 31 Plan 13: Accessibility Conformance Statement — Summary

**A dated, partial WCAG 2.1 AA conformance claim published at `/legal/accessibility`, driven by one
machine-readable constant, carrying seven named exceptions each with a remediation date — and a
build gate that reds the day the statement expires or any remediation date passes, proven by running
the past-date arm rather than by asserting it.**

## Status: COMPLETE — with one owner question returned unanswered

Both tasks are implemented, committed and verified in both directions. One question is genuinely the
owner's and is returned rather than guessed: see **The owner question** below.

## Tasks

| Task | Name | Status | Commit |
|------|------|--------|--------|
| 1 | Declared statement data + expiry gate | Complete | `4230aad9` |
| 2 | The published conformance statement | Complete | `0ac022ca` |
| — | Regenerate `docs/metrics.json` | Complete | `d49475d5` |

## ⚠ Worktree base correction, before any work began

**This worktree was branched from `main` (`bb2ae65d`), not from the phase branch — 76 commits behind
the intended base `0d1834c2`.** The plan file did not exist and `CLAUDE.md` showed the V61 schema
ledger rather than V63. Measured before acting:

```
git rev-list --count bb2ae65d..0d1834c2  ->  76
git rev-list --count 0d1834c2..bb2ae65d  ->  0
```

Zero commits the other way, so `0d1834c2` strictly contains `bb2ae65d` and a fast-forward reset lost
nothing. Working tree was clean (`git status --porcelain` = 0 lines). Reset performed at agent
startup, which is the one sanctioned place for `git reset --hard`. Two sibling worktrees
(`agent-ac0c122606d00ba8f`, `agent-adcaf66fcd100fefc`) were already at `0d1834c2`, so the defect was
specific to this worktree's creation, not to the orchestrator's base choice.

**Had this gone unnoticed, everything downstream would have been wrong quietly:** 31-08's
`PolicyPage`, `resolveControllerContact` and the verbatim registered-office text do not exist at
`bb2ae65d`, so the page would have been built against components that were not there — and the
failure would have looked like a coding error rather than a base error.

## The final exception list, with dates

`preparedOn` **2026-08-15** · `lastReviewedOn` **2026-08-16** · `nextReviewDue` **2027-02-16**

`preparedOn` is the date the axe evidence was captured against the running Compose stack (RESEARCH
§ "Measured Accessibility Baseline — 2026-08-15"), **not** the date the file was written. That
distinction is the plan's, and it is why the value lives in the constant: 31-18 re-measures and owns
moving it.

| # | id | Category | What it is | `remediationBy` |
|---|---|---|---|---|
| 1 | `vendor-dashboard-not-assessed` | out-of-scope | Everything behind a vendor sign-in is unassessed and unclaimed (D-09) | **2027-02-16** |
| 2 | `identity-provider-registration` | third-party | "Create an account" leaves our origin for the identity provider | **2027-02-16** |
| 3 | `stripe-hosted-payment-form` | third-party | The card fields inside our checkout page are rendered by the payment provider | **2027-02-16** |
| 4 | `storefront-no-skip-link` | known-defect | No skip link on `/shop`, `/shop/[slug]`, `/shop/[slug]/checkout`, `/shop/signin` (WCAG 2.4.1, level A) | **2026-11-16** |
| 5 | `required-fields-marked-visually-only` | known-defect | Three checkout address fields marked required by a visible `*` only (WCAG 3.3.2 / 4.1.2) | **2026-11-16** |
| 6 | `text-contrast-below-minimum` | known-defect | Text below 4.5:1 on public pages — 31-02's 55-pair ledger (WCAG 1.4.3, level AA) | **2027-02-16** |
| 7 | `registered-office-not-published` | published-information | 31-08's verbatim text, consumed unchanged | **2027-02-16** |

Entry 7 is categorised **`published-information`, not a WCAG failure**, deliberately: it is a UK GDPR
Article 13 gap that 31-08 routed here because this is where the phase's dated-exception mechanism
lives. Miscategorising it would have made the page imply the standard says something it does not.
The text is 31-08's blockquote copied verbatim, date-stamped by the constant. Company number
**16471464** throughout; **13434105 appears nowhere**, asserted as an absence in both test files.

## Findings VERIFIED AGAINST THE TREE and deliberately NOT published

The plan is explicit that publishing a fixed finding is as inaccurate as omitting a live one, so each
candidate was re-measured rather than transcribed.

| Candidate | Measured result | Published? |
|---|---|---|
| **A11Y-16** `<button>` inside `<a href>` (council said 16 instances) | **0 on the declared surfaces.** All 5 real instances are in `app/dashboard/**`, already excluded by D-09 | **No** |
| **A11Y-13** placeholder-as-label | **0 on the declared surfaces** (31-02's 31-rule jsx-a11y layer at error, plus real `<label htmlFor>`) | **No** |
| **A11Y-06** skip link | **PARTIALLY closed.** Closed by 31-03 on `PublicShell`; **open on the storefront shell** | **Yes — entry 4**, scoped to the four storefront routes only |
| **A11Y-14** required-marker parity | **LIVE.** 6 visible `*` vs 3 programmatic `required` | **Yes — entry 5** |
| `public-header.tsx` two unlabelled `<nav>`s (31-02 handed this to 31-13 to decide) | Confirmed present (`:94`, `:177`), but never simultaneously in the accessibility tree, so not a `landmark-unique` violation today | **No — decision recorded here** |
| **A11Y-07 / A11Y-08** | Unfixed on THIS base (`autoComplete` = 0, `role="alert"`/`aria-invalid`/`aria-describedby` = 0 in checkout). Both are 31-14's scope | **No — written as fixed; see the merge gate** |

### The A11Y-16 measurement was nearly wrong, and the control arm is why it is not

A first multiline scan reported one hit on a declared surface,
`components/marketing/hero-search.tsx`. Reading it:

```
$ rg -uu -n 'Link|<button' components/marketing/hero-search.tsx
12: * This replaces a decorative <Link href="/shop"> that LOOKED like a search box
60:      <button
```

The only `<Link>` in that file is **in a comment describing markup that was removed**. The file
contains no `<Link>` element at all. Had I reported that hit, the statement would have published a
defect that does not exist — the eighth instance of this phase's prose-satisfies-grep class.

The corrected scan strips comments before matching and **ships a control arm**: the same matcher run
over `app/dashboard/**`, which is known to contain instances.

```
files scanned: 64
=== A11Y-16 nested interactive (declared surfaces, comments stripped) : 0
=== A11Y-13 placeholder with no programmatic label                    : 0
=== A11Y-14 files with a visible * required marker                    : 1
  app/shop/[slug]/checkout/page.tsx
=== CONTROL (out-of-scope dashboard, same matcher): 5 hits over 50 files
```

The control is what makes the two zeros evidence about the code rather than about a dead matcher.

### The A11Y-14 measurement also nearly went wrong

`rg -uu -c 'required' checkout/page.tsx` returns **4**, which against 6 asterisks looks like "4 of 6
correct". Reading the hits:

```
108:        redirect: "if_required",     <- a Stripe option, not an attribute
703:              required
716:              required
729:              required
```

The real count is **3**, not 4. Labels carrying a visible `*` are at `:635` (address 1), `:662`
(town/city), `:677` (postcode), `:699` (name), `:712` (email), `:725` (phone) — so the three
**delivery-address** fields are the ones with no programmatic requirement. The published exception
says exactly that. A count taken from `grep -c` alone would have published a wrong number in a legal
document.

### The storefront skip link, control-armed

```
$ rg -uu -c 'focus:not-sr-only' components/public/public-shell.tsx   ; rc=0  count=1   <- control
$ rg -uu -c 'focus:not-sr-only' app/shop/layout.tsx                  ; rc=1  (no output)
```

`app/shop/layout.tsx` renders `<main className="flex-1">` with **no `id`** and no skip link, and it
is the shell for all four storefront routes. 31-03 recorded this handoff explicitly ("this plan's
scope was `PublicShell`"), and **no plan in this phase closes it** — `rg -uu -l 'skip link|skip-link'`
over the phase directory matches only 31-03's plan/summary, the RESEARCH, the UI-SPEC, the PATTERNS
file and this plan. So it is genuinely open and is published.

## Falsifiability — every arm, both directions, real output

Every arm was run **after** the work was committed, so `git checkout --` restored from a committed
state rather than the index. Every restore is verified by `git hash-object` against
`git rev-parse HEAD:<path>`. The clean arm ran **last**.

### Arm 1a — THE EXPIRY GATE. `nextReviewDue` set to a past date

This is the plan's headline deliverable and the one that must be observed firing.

```
BREAK  nextReviewDue: "2027-02-16" -> "2025-01-01"
  ✕ has not expired: nextReviewDue is still in the future
      expect(received).toBeGreaterThan(expected)
      Expected: > 1786838400000        <- today at UTC midnight, read from the real clock
      Received:   1735689600000        <- 2025-01-01
    > 93 |     expect(due).toBeGreaterThan(today)
  ✕ schedules the next review within 12 months of the last one
  Tests: 2 failed, 8 passed
CLEAN  10 passed
RESTORE  git hash-object = c60c3eba1a097fed44f0df54970489123d668a7c  (== HEAD blob)
```

The `Expected: >` value is today's UTC midnight, not a constant — which is the proof the comparison
is against the passing of real time and not against a frozen literal that would pass forever.

### Arm 1b — the exception list emptied. THE VACUITY DEMONSTRATION

```
BREAK  exceptions: []
  ✕ NON-VACUITY CONTROL: … are all populated      Expected: > 0   Received: 0
  ✕ gives every exception a description, a reason and a remediation date
  ✓ has no overdue remediation date — an exception list with passed dates is decoration
  ✓ gives every exception a unique, stable id and a known category
  Tests: 4 failed, 6 passed
CLEAN  10 passed
RESTORE  c60c3eba…  (== HEAD blob)
```

**Read which checks stayed GREEN.** "has no overdue remediation date" and the id/category sweep both
passed happily over a statement with **no exceptions at all** — every per-item assertion is
satisfied by iterating nothing. The non-vacuity control is the only thing between this build and a
green suite over an empty exception list. That is D-13 demonstrated on this plan's own work.

### Arm 1c — one `remediationBy` removed

```
BREAK  remediationBy deleted from `storefront-no-skip-link`
  ✕ gives every exception a description, a reason and a remediation date
      - Array []
      + Array [ "storefront-no-skip-link" ]
  ✕ has no overdue remediation date
      VOID: storefront-no-skip-link.remediationBy is not an ISO date: "undefined"
  Tests: 2 failed, 8 passed
CLEAN  10 passed
RESTORE  c60c3eba…  (== HEAD blob)
```

The completeness assertion names the offending entry, and the overdue check **VOIDs** rather than
comparing `NaN` and silently passing.

### Arm 2a — "partially" changed to "fully". A BAD ARM, recorded as such

```
BREAK  "This website is partially conformant" -> "fully conformant"
  ✕ claims PARTIAL conformance and never full conformance
    > 93 |  expect(text).toContain("partially conformant")
```

**This arm is weaker than it looks and is recorded rather than reported satisfied.** The test failed
on the *presence* half, so `expect(text).not.toContain("fully conformant")` was never reached — the
absence assertion is unproven by this arm. Exactly 31-08's break-arm-7 shape.

### Arm 2a-bis — the overclaim ADDED alongside, isolating the absence assertion

```
BREAK  "…each with the date we expect to fix it. We are now fully conformant."
  ✕ claims PARTIAL conformance and never full conformance
      expect(received).not.toContain(expected)
      Expected substring: not "fully conformant"
    > 96 |  expect(text.toLowerCase()).not.toContain("fully conformant")
  Tests: 1 failed, 17 passed
CLEAN  18 passed
```

The presence assertion passed (the page still says "partially conformant"), so **only** the absence
assertion fired. Both halves of the overclaim check are now proven capable of failing.

### Arm 2b — one exception dropped from the rendered page while left in the data

```
BREAK  STATEMENT.exceptions.map(...) -> STATEMENT.exceptions.slice(0, -1).map(...)
  ✕ renders exactly as many exceptions as the constant declares
      Expected: 7    Received: 6
  ✕ publishes every exception's id, description and remediation date
  ✕ carries the registered-office exception with its company number
  Tests: 3 failed, 15 passed
CLEAN  18 passed
RESTORE  54f343363cc8f85abe672d40b5b49535cc951650  (== HEAD blob)
```

This is T-31-13-04 — the failure where the data is complete, the gate counting the data is green,
and the page a regulator reads is missing an exception.

### Arm 2c — the contact guard removed so an empty address builds a link

```
BREAK  `contact.anyRoute ?` -> `true ?`, `contact.emailHref` -> "mailto:" + (contact.email || "")
  ✕ FALLBACK: with no contact configured it names the routes that DO exist, and emits no empty mailto
  ✕ never emits an empty mailto in EITHER configuration
      - Array []
      + Array [ "mailto:", ]
  Tests: 2 failed, 16 passed
CLEAN  18 passed
RESTORE  54f34336…  (== HEAD blob)
```

`mailto:` with nothing after it renders as a link that looks live and goes nowhere — the fail-open
shape D-16 exists to prevent.

### Arm 2d — the palette relitigated under WCAG pressure (T-31-13-07)

```
BREAK  AA_NORMAL 4.5 -> 3.5 in __tests__/contrast-tokens.test.ts
  npx jest __tests__/contrast-tokens.test.ts  ->  Tests: 8 passed, 8 total     (GREEN!)
  git diff --name-only -- frontend/__tests__/contrast-tokens.test.ts | wc -l   ->  1
CLEAN
  diff lines -> 0
RESTORE  c13ea0475776e16216552b08752cfc657ff53abd  (== HEAD blob)
```

**Confirmed independently on this tree:** the contrast suite stays 8/8 green while its own contract
is cut by a full point. The test cannot police its own threshold; only the diff assertion sees it.
`contrast-tokens.test.ts` is byte-identical to base and was never edited.

### Arm 2e — a date hardcoded into the page prose

```
BREAK  "The next review is due by 16 February 2027 or {due}."
  ✕ hardcodes no date in the page source — every year literal is an allow-listed statute citation
      Array [
        "2010",
    +   "2027",
      ]
CLEAN  18 passed
RESTORE  54f34336…  (== HEAD blob)
```

Worth noting: **the plan's own limb would have missed this.** `grep -cE '20[0-9]{2}-[0-9]{2}-[0-9]{2}'`
matches ISO only, and this break is long-form. The exact allow-list of year literals catches both,
and fails closed on any shape nobody thought of.

### Closing clean arm (run LAST)

```
$ git status --short                    (empty)
$ git diff --stat HEAD                  (empty)

working tree                                       committed blob
c60c3eba…  lib/accessibility-statement.ts          c60c3eba…   OK
1e5065f2…  __tests__/accessibility-statement-dates 1e5065f2…   OK
54f34336…  app/legal/accessibility/page.tsx        54f34336…   OK
a0b91857…  app/legal/__tests__/accessibility-page  a0b91857…   OK
c13ea047…  __tests__/contrast-tokens.test.ts       c13ea047…   OK   (untouched)

npx jest (scoped: statement + page + both contrast suites + legal shell)
  Test Suites: 6 passed, 6 total     Tests: 68 passed, 68 total
npx jest (full)
  Test Suites: 108 passed, 108 total Tests: 1036 passed, 1036 total
npm run build   BUILD_RC=0, "Running TypeScript", route ƒ /legal/accessibility present
npm run lint    LINT_RC=0, 28 warnings (the same 28 pre-existing; zero in this plan's files)
```

## Vacuous checks found and recorded — NOT reported as satisfied

### The plan's two `grep -cF` verify limbs both pass on a statement with no dates at all

The plan's Task 1 `<verify>` block asserts
`grep -cF 'nextReviewDue' lib/accessibility-statement.ts >= 1` and
`grep -cF 'remediationBy' … >= 1`. Both are satisfied by this file's own explanatory prose.
**Demonstrated, not argued** — a copy with every data occurrence stripped and the comments left
intact:

```
=== data occurrences remaining in the broken copy ===
  (no 'nextReviewDue:' value line)
  (no 'remediationBy:' value line)

=== the plan's limbs, run against that broken copy ===
grep -cF nextReviewDue = 1   -> limb '>= 1' reports: PASS
grep -cF remediationBy = 2   -> limb '>= 1' reports: PASS

=== what the surviving hits actually are ===
95: * `nextReviewDue` is six months rather than the twelve the standard permits.
21: * decoration. `remediationBy` is required by the type, and the gate beside this
58:  remediationBy: string
```

A statement with **no review date and no remediation dates whatsoever** satisfies both limbs. The
strictly stronger form is what shipped: the suite imports the module and asserts the parsed values,
proven able to fail by arms 1a and 1c.

**This is the eighth and ninth instance of this class in phase 31.** 31-08 counted seven and called
it a class rather than an accident; that reading is confirmed.

| Check | Why it cannot fail as written | What was done |
|---|---|---|
| `grep -cF 'nextReviewDue' >= 1` | 1 of 3 hits is a comment; passes with the value deleted | Replaced by parsed assertions (arm 1a) |
| `grep -cF 'remediationBy' >= 1` | 2 of 9 hits are prose/type; passes with all 7 values deleted | Replaced by parsed assertions (arm 1c) |
| `grep -cE '20[0-9]{2}-…' page.tsx == 0` | ISO-shaped only; a long-form date literal passes it | Kept, plus an **exact allow-list** of all year literals (arm 2e) |
| `grep -ciF 'fully conformant' page.tsx == 0` | Would fail spuriously if a comment named the phrase — the inverse risk | The phrase appears nowhere in the file, comments included; the assertion is on **rendered text** |
| arm 2a as written in the plan | Fails on the presence half before reaching the absence half | Re-run as arm 2a-bis, both recorded |

## Deviations from Plan

### 1. [Rule 3 — Blocking] The worktree was branched from the wrong base

Covered above. Reset to `0d1834c2` at startup; fast-forward, nothing lost, verified by commit counts
in both directions before acting.

### 2. [Rule 3 — Blocking] `node_modules` absent in a fresh worktree

`npm ci` (rc=0). Installs the committed lock tree; no package added, `package.json` and
`package-lock.json` untouched. Per instruction, no symlink or hardlink to the main checkout.

### 3. [Rule 3 — Blocking] A stale Turbopack cache reds `npm run build` with no code change

Between two builds of an unchanged tree, `npm run build` went rc=0 → rc=1 with 18 errors, all
`Module not found: Can't resolve '@vercel/turbopack-next/internal/font/google/font'`. This is a
Turbopack cache-state failure, **not a code error and not caused by this plan** — nothing here
touches fonts. `rm -rf .next` and rebuild → rc=0, TypeScript ran, route present.

Recorded because the failure names a font module and reads exactly like a real dependency defect.
Anyone hitting it should clear `.next` before investigating.

### 4. [Rule 2 — Missing critical] The exception list is not the plan's candidate list

The plan supplies six candidates and instructs "VERIFY EACH BEFORE LISTING IT". Verification moved
two candidates off the list (A11Y-16, A11Y-13, both measured 0 on declared surfaces with a live
control) and added two the plan did not name:

- **the storefront skip link** — A11Y-06 is only partially closed, which the plan's candidate line
  ("CLOSED by 31-03 for PublicShell routes; verify") invites but does not state;
- **31-02's 55-pair contrast ledger** — a live WCAG 1.4.3 AA gap on declared surfaces that 31-02
  explicitly handed forward ("hand 31-13 an enumerated, ratio-annotated list of what the conformance
  statement must either fix or declare"). Omitting it would have made the statement wrong.

### 5. `docs/metrics.json` regenerated; the three prose docs deliberately NOT touched

Per the orchestrator's instruction. `docs-freshness.sh` was observed **rc=1 before** the write and
rc=0 after — so the write did real work rather than rewriting an already-correct file.

**`check-doc-metrics.sh` is rc=1 in this worktree, and that is the correct state:**

```
FAIL: README.md [total_logical_invocations]: doc says 2951, docs/metrics.json says 2979
FAIL: CLAUDE.md [jest_blocks]: doc says 1008, docs/metrics.json says 1036
FAIL: AGENTS.md [jest_files]: doc says 106, docs/metrics.json says 108
```

A worktree cannot see its siblings' tests, so any prose figure computed here would be wrong the
moment wave 3 merges. This is the wave-1 lesson applied. **The orchestrator owns this.**

---

**Total deviations:** 5 (3 blocking, 1 missing-critical, 1 procedural). No Rule 4 situation arose.

## ⚠ MERGE GATE ITEMS FOR THE ORCHESTRATOR

### 1. A11Y-08 — the conditional this plan was told to carry

**This statement is written as though A11Y-08 is FIXED.** It does not appear as an exception.

Measured on this plan's base (`0d1834c2`): `rg -uu -c 'autoComplete' app/shop/[slug]/checkout/page.tsx`
returns **rc=1, no output** — zero `autocomplete` tokens. 31-14 owns the fix ("Every checkout input
that collects the user's own data carries a valid autocomplete token"), and it runs in a worktree I
cannot observe.

> **If 31-14 reports A11Y-08 unfixed, this statement must gain it as a named exception with a
> remediation date before merge.** It is a WCAG 2.1 level **AA** failure (SC 1.3.5) on a declared
> surface that axe cannot detect, so no gate in this phase will catch the discrepancy. The statement
> would simply be false.

Adding it is a one-entry edit to `ACCESSIBILITY_STATEMENT.exceptions` in
`frontend/lib/accessibility-statement.ts`; the page renders it and the count assertion follows
automatically. Suggested entry, ready to paste if needed:

```ts
{
  id: "checkout-inputs-no-autocomplete",
  title: "Checkout fields do not tell your browser what they are for",
  description:
    "The name, email, phone and delivery address fields at checkout are not tagged with what kind of information they hold, so your browser or device cannot fill them in for you automatically.",
  reason:
    "This makes ordering slower and harder for anyone who finds typing difficult, and it is a level AA requirement we have not yet met.",
  category: "known-defect",
  routes: ["/shop/[slug]/checkout"],
  remediationBy: "2026-11-16",
},
```

### 2. A11Y-07 — the same shape, same owner

31-14's must_haves also cover announced field errors ("A refused submit announces itself to assistive
technology and moves focus to the control that refused"). Measured on this base:
`role="alert"` / `aria-invalid` / `aria-describedby` in checkout = **rc=1, no output** (zero).
This statement assumes it fixed too. **Same conditional applies.**

### 3. `docs/metrics.json` prose reconciliation

`check-doc-metrics.sh` is rc=1 here by design (see deviation 5). Re-run
`scripts/docs-freshness.sh --write` on the merged tree, then fix `README.md`, `CLAUDE.md` and
`AGENTS.md` — **never by arithmetic on the conflicting values.**

### 4. PRE-PUBLICATION, inherited from 31-08 and now load-bearing

**`privacy@olajay.co.uk` must exist and be MONITORED before this page goes live.** This page
publishes it as the accessibility feedback route and promises a reply "within one working week". A
published commitment backed by an unmonitored mailbox is the exact fail-open shape D-16 exists to
prevent — and worse here, because the page now also promises a response time. Unverifiable from this
repository.

Note the degradation is safe: with the variable unset the page renders the existing-routes fallback,
never an empty link (arm 2c). But the fallback is only reached if the value is genuinely absent — a
configured-but-unread mailbox looks identical to a working one.

### 5. For 31-18 — this exception list is a snapshot, not the final word

31-18 owns the re-measurement and must reconcile all seven entries against the final audit. Two
specific risks:

- **Entry 5 (`required-fields-marked-visually-only`) could be incidentally closed by 31-14**, which
  is editing the same file. Re-measure before publishing it as open.
- **`contrast-literals.test.ts` does not scan `app/legal/**`** (its `SCAN_ROOTS` are `app/page.tsx`,
  `app/shop`, `app/auth/signin`, `components/public`, `components/storefront`,
  `components/marketing`). So this new page's own utility classes are **not** covered by that gate.
  They were recomputed by hand here and all clear AA on the surfaces they render on —
  `text-amber-800` 7.09 on white / 6.60 on cream, `text-amber-700` 5.02 / 4.67, `text-slate-700`
  10.35 / 9.64, `text-oxblood` on `bg-cream-100` 14.44 — but that is a manual check, not a gate.
  Widening `SCAN_ROOTS` to include `app/legal` belongs to whoever owns that file (31-02's).

## THE OWNER QUESTION — returned unanswered

**The remediation DEADLINES in the table above are engineering estimates, not a business
commitment.** Every other value on this page was derived from a measurement or consumed verbatim
from a recorded decision. These seven dates were not: nothing in the repository states when the
business intends to fix a contrast ledger or assess the vendor dashboard.

They ship rather than being left blank because D-12 forbids "in due course", the gate requires every
exception to carry a future date, and a page with no dates would fail to be the deliverable at all.
A wrong-but-dated commitment is also self-correcting in a way a blank is not: **an overdue
`remediationBy` now reds the build** (proven, arm 1c/1b), so a date the business does not honour
becomes a build failure rather than a quiet lie on a public page.

> **Question for the owner:** are these seven remediation dates acceptable as published
> commitments — in particular **2026-11-16** for the two small fixes (storefront skip link,
> checkout required markers) and **2027-02-16** for the contrast remediation and the vendor-dashboard
> assessment? They are now legally operative text on a public page and will fail the build if
> missed. Amend `frontend/lib/accessibility-statement.ts` to change any of them; no code change is
> needed.

A second, smaller one, flagged rather than guessed: **`nextReviewDue` is set to six months**
(2027-02-16) rather than the twelve the standard permits, because the measurement underneath carries
a short declared validity window. That is inside the permitted bound in the safe direction, but it
is a commitment to review twice a year.

## Threat model outcomes

| Threat ID | Disposition | Outcome |
|---|---|---|
| T-31-13-01 | mitigate | Discharged. "partially conformant" asserted present and "fully conformant" asserted absent **on rendered text**; both halves proven able to fail (arms 2a-bis). `claim` is additionally a one-value union type, so the overclaim is a compile error. |
| T-31-13-02 | mitigate | Discharged. The expiry gate was **observed firing** against a past date (arm 1a), comparing against the real clock. |
| T-31-13-03 | mitigate | Discharged. Every exception carries `remediationBy` (required by the type); an overdue date reds the build; the empty-list arm proved the non-vacuity control is the only thing that catches an empty list. |
| T-31-13-04 | mitigate | Discharged. Rendered count asserted equal to the constant's, never to a literal; proven by arm 2b (`Expected: 7 Received: 6`). |
| T-31-13-05 | mitigate | Discharged in code — the fallback is proven in both configurations and an empty `mailto:` is proven impossible (arm 2c). **NOT discharged operationally:** whether the mailbox is monitored is unverifiable here (merge gate 4). |
| T-31-13-06 | mitigate | Discharged. Both third-party surfaces verified in code (`lib/customer-auth.ts:250` redirects to `${KC_BASE}/protocol/openid-connect/registrations`; checkout `:537-557` renders `<Elements>`, `:139` renders `<PaymentElement>`) and published with reasons. A test asserts ≥2 `third-party` entries. |
| T-31-13-07 | mitigate | Discharged. `contrast-tokens.test.ts` byte-identical (`c13ea047…`), diff 0 lines, still 8/8; the diff guard was proven able to fire (arm 2d). |
| T-31-13-SC | accept | No package added. `npm ci` installed the committed lock tree only. |

## Threat Flags

None. No network endpoint, auth path, file access pattern or schema change was introduced. The page
is a public server-rendered document with no client directive and no new configuration.

## Known Stubs

None. The page references `/legal/privacy` in its unconfigured-contact fallback; that route is being
built by sibling 31-11 in parallel and deliberately does not exist in this worktree. Per the
orchestrator's instruction the path is referenced, not stubbed. **31-17 asserts reachability.**

## Verification

| Gate | Result |
|---|---|
| `npx jest __tests__/accessibility-statement-dates.test.ts` | **10 passed** |
| `npx jest app/legal/__tests__/accessibility-page.test.tsx` | **18 passed** |
| `npx jest __tests__/contrast-tokens.test.ts` | **8 passed**, file unmodified (blob `c13ea047…`) |
| Scoped clean arm (6 suites) | **68 passed** |
| Full jest suite | **108 suites, 1036 tests, all passed** (base: 106 / 1008) |
| `npm run build` | `BUILD_RC=0`, "Running TypeScript", `ƒ /legal/accessibility` in the route manifest |
| `npm run lint` | `LINT_RC=0`; 28 pre-existing warnings, **0** in this plan's files |
| `npx eslint` on the four new files | `rc=0`, 0 errors, 0 warnings |
| `scripts/docs-freshness.sh` | rc **1 before** → rc **0 after** `--write` (1008→1036, 2951→2979) |
| `scripts/check-doc-metrics.sh` | rc **1** — expected and orchestrator-owned (merge gate 3) |

`STATE.md` and `ROADMAP.md` were not modified. `frontend/app/legal/page.tsx` was not modified — it
already links `/legal/accessibility` (`:60`), verified on base.

## Self-Check: PASSED

Files claimed created — all four present on disk (`ls -l`, not `git show`):

```
frontend/lib/accessibility-statement.ts                    13042 bytes
frontend/__tests__/accessibility-statement-dates.test.ts    7604 bytes
frontend/app/legal/accessibility/page.tsx                  12156 bytes
frontend/app/legal/__tests__/accessibility-page.test.tsx   11660 bytes
```

**One defect this self-check caught in its own document:** the four byte counts above were first
written from estimate rather than measurement, and were wrong by 1,000–4,000 bytes each. They are
now the real `ls -l` output. Recorded rather than silently corrected, because a self-check
containing invented figures is worse than no self-check — it certifies nothing while looking like
evidence.

Commits claimed — all resolve (`git cat-file -t`, which fails on a fabricated hash rather than
printing nothing):

```
4230aad9  feat(31-13): declared accessibility statement data and its expiry gate
0ac022ca  feat(31-13): publish the partial WCAG 2.1 AA conformance statement
d49475d5  chore(31-13): regenerate docs/metrics.json for the two new test files
0d1834c2  docs(31): reconcile metrics and the V63 ledger after the wave-2 merge   (base)
```

`git status --short` empty at hand-back; every break arm restored and each restore verified by
content hash against its committed blob; the closing clean arm ran last.

---
*Phase: 31-consumer-safety-and-legal-floor*
*Completed: 2026-08-16*
