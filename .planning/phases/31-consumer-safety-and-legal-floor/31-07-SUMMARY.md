---
phase: 31-consumer-safety-and-legal-floor
plan: 07
subsystem: legal-documents
tags: [gdpr, article-26, article-9, joint-controller, dsar, allergen, docs]
requires: []
provides:
  - "docs/legal/article-26-arrangement.md — the D-18 Article 26 arrangement with a delimited, publishable essence section"
  - "docs/legal/article-9-allergen-basis.md § Extension, 2026-08-16 — the dated D-01 decision not to consult the stored allergen profile"
affects:
  - "31-11 (privacy notice) — must reproduce the essence verbatim between the ESSENCE:BEGIN/END markers, not paraphrase it"
  - "31-14 (checkout allergen block) — asserts the same exact consumer sentence this determination quotes"
  - "Phase 32 — owns acceptance tracking, onboarding presentation and e-signature (explicitly excluded here)"
tech-stack:
  added: []
  patterns:
    - "DRAFT / not-legal-advice / effectiveness-gate framing cloned from derivation-clause.md"
    - "Append-and-date rather than re-author: original headings preserved, completed next-steps struck through and annotated, never deleted"
    - "Verbatim reuse of a shared clause across two documents, proven byte-identical by an executable check"
key-files:
  created:
    - docs/legal/article-26-arrangement.md
  modified:
    - docs/legal/article-9-allergen-basis.md
decisions:
  - "The Article 26 allocation is stated BY DATA CATEGORY, with an explicit row preserving the Article 9 vendor-controller/platform-processor split for the allergen field — the two determinations coexist rather than one overriding the other"
  - "The dissolved namesake company number is referred to WITHOUT its number, so the document cannot become a source anyone copies the wrong number from"
  - "The DSAR contact point is described with a shipped-vs-built-in-this-phase status table, and its liveness is an effectiveness-gate checkbox rather than an assertion"
metrics:
  duration: ~35 min
  tasks: 2
  files: 2
  completed: 2026-08-16
---

# Phase 31 Plan 07: Article 26 Arrangement + Dated Article 9 Decision — Summary

Authored the joint-controller arrangement D-18 owes (new, 346 lines, with a delimited publishable
essence) and appended a dated D-01 decision-not-to-process section to the Article 9 determination
that already existed — extending and dating it rather than re-authoring it, with every original
heading, finding, exclusion and next step preserved.

## What was built

| Task | Output | Commit |
|---|---|---|
| 1 | `docs/legal/article-26-arrangement.md` — new | `9caf21a6` |
| 2 | `docs/legal/article-9-allergen-basis.md` — appended and dated | `00a153ed` |

### Task 1 — the Article 26 arrangement

New file beside the two legal documents already in `docs/legal/`, in the same DRAFT /
not-legal-advice / effectiveness-gate framing `derivation-clause.md` uses. Sections, in order:
status and date · why it exists (Art. 26(1) and 26(2)) · the parties · the two lines · the allocation
by data category · the single point of contact · respective responsibilities · special category data
· the essence for publication · what is deliberately not in scope · positions requiring adviser
confirmation · effectiveness gate.

Four things in it are load-bearing and worth reading before touching it:

1. **The allocation is by data category, not in the abstract.** Eight rows, each naming concrete
   columns or systems. The row that matters most is the one preserving the existing Article 9
   determination: consumer *order* data is joint-controller, while `Customer.allergenRestrictions`
   stays **vendor controller / platform processor, unchanged**. Those two are not in tension —
   controller roles attach to processing operations, not to companies — and the document says so
   explicitly rather than leaving a reader to notice the apparent collision.
2. **The single point of contact is described in terms of what exists.** A five-row status table
   separates *shipped* (Art. 17 erasure, Art. 20 export, `erasure_records`) from *built in this
   phase* (consumer intake, background fan-out, privacy notice). The design property is stated in
   the published essence because it is the unusual part: **no human at the platform holds
   cross-tenant read** — intake is a request path, execution is a background path, and the boundary
   is a written engineering rule, not a convention.
3. **Nothing is restated that already exists in words.** The special-category block is reproduced
   **verbatim** from the Article 9 doc, and the derivation exclusion is cross-referenced as
   **clause M.5** rather than reworded.
4. **The scope exclusion is explicit and unmissable.** "No acceptance flow exists, no vendor has
   signed this, and no signature is recorded anywhere in this platform" — Phase 32 named as owner
   (D-18), and the acceptance mechanism is an unticked box in the effectiveness gate.

### Task 2 — the dated Article 9 extension

`docs/legal/article-9-allergen-basis.md` gained 143 lines and lost 6. The 6 lost are **only** the two
status/refs lines (rewritten to carry both dates) and the four lines of recommended steps 2 and 4
(re-emitted struck through, original wording intact). No finding, no table row, no exclusion, no
step was deleted.

Added as a final section `## Extension, 2026-08-16 — the decision not to consult the stored profile`:
the decision · the reasoning (citing Finding 1, not re-arguing it) · what the consumer sees instead
(D-02, with the exact sentence) · the D-03 product-level reconciliation, described as advisory ·
what is unchanged (the Art. 20 export still carries the field, and that is correct) · what remains
open (the consent record, deliberately).

Next-steps annotations: step 1 **still open**; step 2 **delivered**, naming the four tests in
`frontend/app/dashboard/customers/__tests__/allergen-consent-notice.test.tsx`; step 3 **still open,
deliberately** — a decision *not to process* creates no processing to obtain a condition for, so the
consent record is not required by this phase and must not be recorded as delivered; step 4 **closed
by Phase 31**, naming `/legal/privacy`.

## The essence, verbatim — for 31-11

**31-11 must reproduce the text between `<!-- ESSENCE:BEGIN -->` and `<!-- ESSENCE:END -->` at
`docs/legal/article-26-arrangement.md:248-279`. Do not paraphrase it.** A paraphrase of an Article
26(2) essence is a second, differently worded arrangement, and the two will drift. The markers exist
so the boundary is unambiguous to a machine as well as to a reader.

> ### Who is responsible for your information when you order
>
> When you order food through J'Toye, two businesses are involved: **J'Toye Digital Ltd** (company
> number 16471464), which runs the platform, and **the shop you ordered from**, which makes and
> supplies your food. The shop is named to you when you order.
>
> For the information created by your order — your name, contact details, delivery address, what you
> ordered and what you paid — **J'Toye and the shop are jointly responsible**. J'Toye decides how the
> platform collects and stores it; the shop decides what it needs in order to serve you.
>
> Some things are J'Toye's responsibility alone. Your J'Toye storefront account and password are ours,
> not the shop's. Records a shop keeps about you for its own reasons — including any note it makes of
> your allergies — are the shop's responsibility, and J'Toye only stores them on the shop's behalf.
> **J'Toye does not check your order against any allergy information a shop has recorded, and does not
> hold allergy information about you.** What you are shown at checkout is what the shop has declared
> about the food in that order.
>
> **You can contact J'Toye about your information, once, for every shop you have ordered from.** You do
> not need to contact each shop separately. We will act on your request across every shop that holds
> your details. You can also contact any shop directly, and you can complain to the Information
> Commissioner's Office at any time.
>
> **No J'Toye employee can browse across shops to look at your details.** Requests are carried out by
> an automated process that works through one shop at a time. That is deliberate: it is how we can
> offer you a single place to ask while keeping each shop's records separate from every other shop's.
>
> The full arrangement between J'Toye and the shops is a written document. This is its essence, which
> we publish because the law requires us to make it available to you.

Two constraints on 31-11 that follow from the essence text:

- It promises the consumer **can contact J'Toye once, for every shop**. That is only true once the
  DSAR intake (31-05) and the fan-out (31-09) are live. If 31-11 publishes this before both are in
  the delivered runtime, the notice is the fail-open shape D-16 exists to prevent.
- It says **"J'Toye does not check your order against any allergy information a shop has recorded"**.
  That must not be softened, and it must agree with the checkout sentence 31-14 asserts.

## The citation gate: MEASURED, not assumed

The plan required this to be measured rather than presumed covered. It is **not** covered by default.

```
bash scripts/check-doc-citations.sh                       -> rc=0
  docs: 8  (CLAUDE.md, AGENTS.md, .planning/codebase/STACK.md,
            .planning/codebase/ARCHITECTURE.md, .planning/codebase/INTEGRATIONS.md,
            k8s/DEPLOYMENT.md, k8s/LOCAL.md, docs/ops/terminal-states.yaml)
  citations total=80 verified=73 uncheckable=7  violations 0
```

**`DEFAULT_DOCS` contains no path under `docs/legal/`.** Both files in this plan sit OUTSIDE the
gate's default scanned set, so its `rc=0` says nothing whatever about them — recording that as
"the gate passed" would have been exactly the vacuous pass the gate's own header warns about.

Pointed at the new document explicitly, the gate produced all three of its outcomes on this file —
which is the strictly stronger result, because it proves the gate is *capable* of failing here:

```
CITATION_DOCS="docs/legal/article-26-arrangement.md" bash scripts/check-doc-citations.sh

ARM 1 (as first drafted)  -> rc=2  VOID
  "3 citation(s) found but NONE could be verified (all uncheckable) —
   reporting clean over zero verified claims would be a vacuous pass"
  Cause: the strong token sat on the NEXT doc line from its citation; the gate
  evaluates the claim per line.

ARM 2 (tokens moved onto the citation line, one citation genuinely wrong) -> rc=1  FAIL
  FAIL: C-3 docs/legal/article-26-arrangement.md:160 cites ErasureRecord.java:40,
        but that line says nothing the claim names
        claim: ... `subject_email_sha256` / cited: private String subjectEmailSha256;
  Real defect: line 40 is the Java field; the snake_case column name is on line 39.

ARM 3 (citation corrected to :39)  -> rc=0  PASS
  citations total=3  verified=3  uncheckable=0  violations 0
```

**Recommendation for a later plan (not done here — out of this plan's file scope):** add
`docs/legal/*.md` to `DEFAULT_DOCS`. All three of this file's citations are now verifiable, so the
addition would be green on arrival, and the gate has been shown able to catch a wrong line in it.

## Fail-direction arms — both directions' real output

**Committed before every break arm; restores verified by `git hash-object`, never `git diff --stat`;
clean arm re-run LAST in every case.**

### Arm A — the pre-state control (Task 1's existence assertion)

```
test -f docs/legal/article-26-arrangement.md   -> rc=1  (absent)
ls -1 docs/legal/  -> article-9-allergen-basis.md, derivation-clause.md   (2 files, neither of them this)
```
After Task 1: `rc=0`. The existence assertion was red before the work and green after it.

### Arm B — insert the dissolved company number (Task 1)

Clean content hash before: `6f66ead8bbc307dbc373d45a85288581ffcf54f8`

BREAK — the parties table's `16471464` replaced with the dissolved number:
```
essence(-i)=12
active-company-16471464=2
dissolved-company-13434105=1
FAIL dissolved company number PRESENT
BREAK-ARM rc=1
```

CLEAN, after `git checkout -- docs/legal/article-26-arrangement.md`:
```
git hash-object -> 6f66ead8bbc307dbc373d45a85288581ffcf54f8   (identical to pre-break)
active-company-16471464=3
dissolved-company-13434105=0
ALL TASK-1 ASSERTIONS PASS   rc=0
```

**Worth recording, because it is the reason both assertions exist:** under the break, the
*active-number* assertion still PASSED (count fell 3 → 2, still `>= 1`). A document can cite the
right number three times and the wrong one once. Only the explicit dissolved-number assertion caught
it — an "asserts 16471464 is present" check alone would have been vacuous against this exact defect.

### Arm C — delete an original heading (Task 2)

Committed content hash before: `d48a28010fc2aefec7d80f41b39333a101ca4b2b`

BREAK — `### DPA wording to add` removed, its body left in place:
```
heading [### DPA wording to add] = 0
FAIL lost original heading: ### DPA wording to add
TASK-2 ASSERTIONS FAILED
BREAK-ARM rc=1
```

CLEAN, after restore:
```
git hash-object -> d48a28010fc2aefec7d80f41b39333a101ca4b2b   (identical)
heading [### DPA wording to add] = 1
ALL TASK-2 ASSERTIONS PASS   rc=0
```

### Arm D — reword the shared special-category block (added, not required by the plan)

The plan forbids two differently worded versions of one commitment, so the property was made
executable rather than asserted in prose: extract the block from both files and compare byte for
byte. The extractor VOIDs (rc=2) if either extraction is empty, so a broken extractor cannot report
a pass.

BREAK — "dietary-health **information**" → "dietary-health **data**" in the Article 26 copy only:
```
FAIL: the two documents word the special-category commitment differently
1c1
< > We process special category data (including customer allergen and dietary-health information) only
---
> > We process special category data (including customer allergen and dietary-health data) only
BREAK-ARM rc=1
```

CLEAN, after restore (`git hash-object` → `6f66ead8...`, identical):
```
PASS: special-category block is byte-identical in both documents   rc=0
```

### Closing clean arms (run last, after every restore)

```
verify-t1.sh        ALL TASK-1 ASSERTIONS PASS       rc=0
verify-t2.sh        ALL TASK-2 ASSERTIONS PASS       rc=0
verify-verbatim.sh  PASS: byte-identical             rc=0
git status --short  (empty)
```

## Diff shape on the pre-existing document — inspected, not merely counted

`git diff --stat` reported `143 insertions(+), 6 deletions(-)`. A stat line cannot tell an
annotation from a deleted finding, so the removed lines were enumerated:

```
git diff -U0 docs/legal/article-9-allergen-basis.md | grep -E '^-[^-]'

-**Status:** Determination recorded 2026-07-30. One code change shipped with it; one item remains open.
-**Refs:** ADR-0004 (knowledge-graph strategy), [`derivation-clause.md`](derivation-clause.md) (Art. 9 excluded from derivation)
-2. **Add the vendor-facing notice** on the allergen checkboxes (small frontend change, no schema),
-   so the duty is visible at the point of entry.
-4. **Write the privacy notice** — there is currently none; the operator's `/legal` page carries
-   company registration details only.
```

Exactly the date/status line and the two annotated next-steps, both re-emitted with their original
wording intact inside a strikethrough. Additionally spot-checked as still present verbatim:
`the vendor is the controller`, `customerAllergenMask`, `manifestly made public`, and
`excluded from Anonymous Statistical Data under`.

## Claims that could NOT be verified from the repo

Recorded here rather than papered over, per the phase's evidence contract.

| # | Claim | Status | What is needed |
|---|---|---|---|
| 1 | **The registered office address of J'Toye Digital Ltd.** | **NOT IN THE REPO.** `getCompanyInfo()` returns `""` unless `NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` is supplied as a build arg; no default exists in code, and `/legal` renders the field only when non-empty. | **Owner must supply it.** An Article 26 arrangement and a privacy notice both need a postal address for the controller. It is flagged as an open item inside the document and is an unticked effectiveness-gate box. It was NOT invented. |
| 2 | **Every legal position in both documents.** | **UNVERIFIABLE BY CONSTRUCTION** — these are legal characterisations, not facts about the tree. | Adviser review. Marked `[ADVISER]` inline and collected as a numbered list of nine positions; the Article 9 doc's own step 1 remains open and now covers the extension too. |
| 3 | **Stripe's controller/processor role for card data.** | **NOT DETERMINABLE FROM THIS REPO.** Verified only the mechanism: `@stripe/react-stripe-js` `PaymentElement` in `frontend/app/shop/[slug]/checkout/page.tsx` (card data goes to Stripe in the browser) and `transfer_data[destination]` destination charges in `PaymentService`. | Take the characterisation from Stripe's own terms. The document states the mechanism as fact and marks the legal role `[ADVISER]`. |
| 4 | **The exact public DSAR route.** | **DOES NOT EXIST IN THIS WORKTREE.** 31-05 is a sibling wave-1 plan; its own `<interfaces>` leaves the idempotency shape to a measurement taken at build time. | Deliberately NOT quoted as a URL. The arrangement describes the *behaviour* (opaque acknowledgement, hashed identifier, IP rate limit, background per-tenant execution) and gates effectiveness on it being live. 31-11 takes the literal route from 31-05's SUMMARY, not from here. |
| 5 | **`/legal/privacy` exists** (asserted in the Article 9 next-steps annotation). | **NOT YET** — 31-11 is wave 3. | Written as a falsifiable statement in the document itself: *"if `/legal/privacy` does not resolve, this annotation is wrong and the phase did not finish."* If the phase aborts before wave 3, this annotation is the defect to fix. |
| 6 | **The dissolved namesake's number.** | Verified present at `frontend/lib/company.ts:5-6` — and then **deliberately not written into either document**. | None. Referring to it as "a dissolved company of a closely similar name" gives the reader the warning without creating a second place the wrong number can be copied from. |

## Deviations from Plan

**None affecting scope.** Three small refinements inside it, all additive:

1. **[Rule 2 — correctness] The three `file:line` citations were rewritten** so each strong token
   shares a line with its citation, after the scoped citation gate VOIDed at rc=2 and then FAILed at
   rc=1 on a genuinely wrong line number (`ErasureRecord.java:40` → `:39`). The plan asked for the
   coverage measurement; leaving three uncheckable citations in a legal document, having watched the
   gate refuse to certify them, would have been the vacuous-pass shape.
2. **[Rule 2 — correctness] Arm D (the verbatim cross-document check) was added.** The plan required
   the special-category block be reused verbatim, but that property had no executable arm. Prose
   cannot detect a one-word drift; the check can, and was shown to.
3. **The allocation table gained rows the plan did not enumerate** — reviews, marketing, payment
   card data, and the explicit Article 9 row. The plan required allocation "by data category, not in
   the abstract"; a table that omits the categories a reader will ask about is abstract by omission.

**No package was installed. No code was changed. `STATE.md` and `ROADMAP.md` were not touched** (the
orchestrator owns those writes).

## Notes for downstream plans

- **31-11 (privacy notice):** reproduce the essence between the markers verbatim. Do not publish the
  "contact us once for every shop" promise before 31-05 and 31-09 are live in the delivered runtime.
  Describe the Art. 20 export accurately — it **does** include the allergen field, and the extended
  Article 9 doc says why that is correct and not in tension with D-01.
- **31-14 (checkout):** the exact sentence *"We do not store your allergies and we cannot check this
  order against them."* is now quoted in `docs/legal/article-9-allergen-basis.md`. A reword in either
  place must red the other.
- **Phase 32:** the effectiveness gate is the acceptance checklist. Ticking the acceptance box is
  Phase 32's deliverable; the document already says no signature exists.
- **A later docs plan:** consider adding `docs/legal/*.md` to `DEFAULT_DOCS` in
  `scripts/check-doc-citations.sh`.

## Self-Check: PASSED

Files:
```
FOUND: docs/legal/article-26-arrangement.md
FOUND: docs/legal/article-9-allergen-basis.md
```

Commits:
```
FOUND: 9caf21a6  docs(31-07): author the Article 26 joint-controller arrangement
FOUND: 00a153ed  docs(31-07): date and extend the Article 9 determination with D-01
```

Both documents carry an explicit date (`2026-08-16`), and the Article 9 document carries both its
original `2026-07-30` and the extension date, so a reader can see it was revisited rather than
replaced.
