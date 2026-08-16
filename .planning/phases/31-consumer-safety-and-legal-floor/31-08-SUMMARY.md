---
phase: 31-consumer-safety-and-legal-floor
plan: 08
subsystem: frontend-legal
tags: [legal, gdpr, a11y, config, build-args, seo]
status: CHECKPOINT — Task 1 (owner decision) outstanding; Tasks 2 and 3 complete
requires:
  - "31-01 (jest-axe + axe-core, human-gated install)"
  - "31-03 (PublicShell skip link + main#main)"
  - "31-05 (POST /api/v1/public/gdpr/dsar — the only DSAR route that exists)"
provides:
  - "PolicyPage / PolicySection — the S2 shell for /legal/privacy|cookies|retention|accessibility"
  - "PolicyToc + sectionId() — one text-derived anchor scheme shared by nav and document"
  - "resolveControllerContact() — the honest-fallback rule for the controller's contact block"
  - "NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE + NEXT_PUBLIC_DATA_PROTECTION_EMAIL wired through all three build-arg sites"
  - "/legal is an index linking its four siblings, Companies House disclosure intact"
affects:
  - "31-11, 31-12, 31-13 (build their pages on PolicyPage/PolicySection/PolicyToc)"
  - "31-17 (adds the Legal column to PublicFooter; asserts reachability end to end)"
  - "31-18 (browser-level a11y gate; owns the below-lg collapsed-disclosure question)"
tech-stack:
  added: []
  patterns:
    - "Plain server components — no client directive, native <details> instead of state (#89 CSP-nonce cascade)"
    - "Optional-with-fallback env classification, mirroring resolveSupportChannel"
key-files:
  created:
    - frontend/components/legal/policy-page.tsx
    - frontend/components/legal/policy-toc.tsx
    - frontend/components/legal/__tests__/policy-page.a11y.test.tsx
    - frontend/components/legal/__tests__/policy-toc.test.tsx
    - frontend/lib/__tests__/company-contact.test.tsx
  modified:
    - frontend/lib/company.ts
    - frontend/lib/env-validation.ts
    - frontend/app/legal/page.tsx
    - .env.example
    - docker-compose.full-stack.yml
    - frontend/Dockerfile
    - docs/metrics.json
decisions:
  - "StorefrontLegalStrip NOT built — app/shop/layout.tsx:73 already renders PublicFooter over the whole /shop/** subtree"
  - "The DSAR API path is deliberately absent from ControllerContact — it is an endpoint, not a page"
  - "Both new env values ship EMPTY; a plausible fake address on a legal page is worse than an omission"
  - "PolicyToc ships expanded below lg — a known, recorded deviation from 'collapsed', chosen because it fails safe"
metrics:
  tasks_completed: 2
  tasks_total: 3
  jest_blocks: "976 -> 1004"
  total_logical_invocations: "2892 -> 2920"
---

# Phase 31 Plan 08: Legal Policy Shell + Controller Contact Wiring — Summary

The shell all four policy documents will render through, and the two contact values a
published privacy notice legally requires wired through every site that can supply them —
with the values themselves left to the owner rather than invented.

## Status: CHECKPOINT — Task 1 is outstanding

**Task 1 is a `gate="blocking-human"` checkpoint and has NOT been answered.** No address, no
data-protection contact route, and no publish/decline decision has been supplied. The three
questions are restated verbatim at the end of this document and in the checkpoint returned to
the orchestrator.

**Execution order deviation, stated explicitly.** The plan places Task 1 first. It was NOT
executed first. Tasks 2 and 3 were executed in their decline-safe form and Task 1 was carried to
the checkpoint, on the orchestrator's carry-forward instruction CF-1 ("Wire the variable,
validate it, and raise the value itself at your checkpoint. Do not invent an address.").

This is safe because the code Task 2 required is **identical either way** — the plan says so in
its own words: *"If Task 1 declined a value, implement the same code path and record the gap. The
code must be correct either way; that is what makes the decline recoverable by setting one build
arg later rather than by another code change."* Nothing built here presumes an answer, and the
gate's actual hazard — publishing an invented or a residential address — is untouched: both
variables ship empty and the contact block is omitted rather than rendered blank.

## Tasks

| Task | Name | Status | Commit |
|------|------|--------|--------|
| 1 | Controller contact values | **BLOCKED — awaiting owner** | — |
| 2 | Wire the values through the build-arg triple | Complete | `e5e1e2ba` |
| 3 | PolicyPage, PolicyToc, /legal becomes an index | Complete | `04920cd3` |

## Exported API — what 31-11, 31-12 and 31-13 wire against

Do not re-derive any of this.

```ts
// @/components/legal/policy-page.tsx
export interface PolicyPageProps {
  title: string           // rendered as the page's single <h1>
  lastUpdated: string     // REQUIRED, e.g. "16 August 2026"
  version: string         // REQUIRED, e.g. "1.0"
  sections?: readonly string[]   // the h2 headings IN DOCUMENT ORDER; drives the ToC
  intro?: React.ReactNode
  children: React.ReactNode
}
export function PolicyPage(props: PolicyPageProps): JSX.Element
export function PolicySection(props: {
  heading: string        // the SAME string passed in `sections`
  children: React.ReactNode
  className?: string
}): JSX.Element

// @/components/legal/policy-toc.tsx
export function sectionId(headingText: string): string          // kebab, text-derived
export function tocEntries(headings: readonly string[]): PolicySectionRef[]
export function PolicyToc(props: { sections: readonly PolicySectionRef[]; className?: string })
export const TOC_MIN_SECTIONS = 4
export interface PolicySectionRef { id: string; label: string }

// @/lib/company.ts
export interface CompanyInfo { legalName; companyNumber; registrationJurisdiction;
                               registeredOffice; dataProtectionEmail }   // all string
export interface ControllerContact {
  postal: string | null; email: string | null; emailHref: string | null; anyRoute: boolean
}
export function resolveControllerContact(info?: CompanyInfo): ControllerContact
```

**Usage contract for the content plans:**

1. Pass the same heading strings to `sections` and to each `PolicySection heading`. The anchor
   ids are derived from that text by one shared function, so passing the same string twice is
   the whole mechanism keeping the nav and the document in agreement.
2. `lastUpdated` and `version` are required props. A page that omits either does not compile.
3. Never render the contact block on the raw `registeredOffice` field. Guard on
   `resolveControllerContact(...).anyRoute` and put the heading INSIDE the guard, or an
   unconfigured deployment ships a term with nothing after it.
4. `PolicyPage` supplies the h1; content supplies h2s via `PolicySection` and h3s below them.
   Do not add a second h1 and do not skip to h3.
5. The ToC renders only at `sections.length >= TOC_MIN_SECTIONS` (4).

## What was built

### Task 2 — the build-arg triple

`NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` was referenced at `lib/company.ts:42` and declared in
**none** of the three sites that can supply it, so it resolved to `""` everywhere. It, and the
new `NEXT_PUBLIC_DATA_PROTECTION_EMAIL`, are now declared in all three:

| Site | Line | Form |
|---|---|---|
| `.env.example` | 279 | `NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE=` (no value, no trailing comment) |
| `docker-compose.full-stack.yml` | 447 | under the frontend service's **`build.args`**, not `environment:` |
| `frontend/Dockerfile` | 81–82 | `ARG` + `ENV` pair |

Both ship empty. `resolveControllerContact()` states the degradation rule once so four pages
cannot each get it wrong: a configured value becomes a route, an unconfigured one becomes
`null`, and `anyRoute` lets a caller drop the block **including its own heading**.

The DSAR intake is deliberately absent from `ControllerContact`. 31-05 built
`POST /api/v1/public/gdpr/dsar` — an API endpoint, not a page. Publishing an unlinkable API path
to a consumer as a "contact route" would satisfy the requirement on paper and help nobody.
**Measured:** no frontend DSAR form exists (`rg -uu -ni "dsar" frontend/` matches exactly one
file, `components/marketing/competitive-teardown.tsx`, which is prose), and no plan in this
phase is scheduled to build one — 31-11 says "point at it (or at …)".

### Task 3 — the shell

`PolicyPage` wraps `PublicShell` and never renders its own `<main>`. Both new components are
plain server components with no client directive; the below-`lg` disclosure is a native
`<details>`, so the root layout's `force-dynamic` and the CSP nonce cascade through untouched.

`sectionId()` derives anchors from heading TEXT, never position. One function, called by both
the nav and the headings, because two derivations that agree today will disagree later.

`/legal` keeps its Companies House disclosure verbatim — same `<dl>`, same `getCompanyInfo()`
sourcing, same metadata and canonical — and gains links to the four siblings with the UI-SPEC
copy-table descriptions. Body text rose from 14px to 16px; the spec caps that in one direction
only (may rise, must not shrink).

## Deviations from Plan

### 1. Execution order — Task 1 deferred to the checkpoint

Covered above. No file was written for Task 1, and no value was invented.

### 2. [Rule 1 — Bug] The policy title block was a `<header>` and resolved as a second banner

**Found during:** Task 3, writing the landmark assertion.
**Issue:** `PolicyPage` opened its title block with a `<header>` element. HTML-AAM scopes
`header` to `generic` when it descends from `main`, so a correct implementation is unaffected —
but the accessibility-name library behind these tests maps it to a second `banner` landmark
beside the shell's real one, and not every consumer implements the scoping rule either. Two
banners is a real navigation defect for anyone moving by landmark.
**Fix:** plain `<div>`. The title block gained nothing from the element.
**Caught by:** `expect(screen.getByRole("banner")).toBeInTheDocument()` failing with "found
multiple". A presence-only or a grep-based check would have passed.
**Commit:** `04920cd3`

### 3. `StorefrontLegalStrip` was NOT built — the UI-SPEC's premise for it is false

The UI-SPEC S2 row "Storefront reachability" asserts `/shop/[slug]` and its checkout render **no
`<footer>` at all**. That was re-measured and is false:

```
$ rg -uu -n "PublicFooter" frontend/app/shop/layout.tsx     ; rc=0
4:import { PublicFooter } from "@/components/public/public-footer"
38: * wordmark, same cream page ground, and the SAME PublicFooter — the storefront
73:      <PublicFooter />

$ rg -uu -n "StorefrontLegalStrip" frontend/app frontend/components   ; rc=1   (control: no output)
```

The control arm matters: the second search returns nothing with rc=1, so the first search's
three hits are a statement about the code and not about a dead search mechanism.
`app/shop/layout.tsx:73` mounts `PublicFooter` over the entire `/shop/**` subtree, so
`getByRole("contentinfo")` resolves on every storefront route already. 31-17's Legal column in
`PublicFooter` therefore reaches the storefront for free, in one component. Building a second
footer would have been duplicated DOM — the trap this repo has already paid for twice (#556,
#593).

### 4. `PolicyToc` ships EXPANDED below `lg` (recorded, not hidden)

UI-SPEC S2 asks for "a collapsed disclosure at the top of the document below `lg`". The `open`
state of a `<details>` is server-rendered markup, not a style, so it cannot vary by viewport
without either a client boundary (which surrenders the CSP-nonce property, #89) or a second
rendered instance (duplicated DOM). Of the two available failure modes — a mobile nav that
starts open and can be closed, versus a desktop rail that silently vanishes if a CSS override
does not land — this picks the one that fails safe. **Flagged for 31-18** to confirm in a real
browser or re-open. It is recorded here rather than quietly dropped.

## Falsifiability — both directions, real output

### Break arm 1 (Task 2): the three-site requirement is real

**The arm as written in the plan is VACUOUS on this tree, and is recorded as such.** It says
"remove the `ARG` line, rebuild, and confirm the value inlines empty". With no value supplied by
design, the value inlines empty **in both arms**, so removing the `ARG` changes nothing
observable. Replaced with a strictly stronger form using a sentinel value; both are recorded.

Real `docker build --no-cache`, same `--build-arg` passed in both arms:

| Arm | Dockerfile | Output |
|---|---|---|
| CLEAN | `ARG` declared | `#5 0.656 INLINED=[SENTINEL-OFFICE-31-08]` |
| BREAK | `ARG` line deleted | `#5 0.578 INLINED=[]` |

A build arg the Dockerfile never declares is not in the build environment at all, so compose
passing it is silently discarded. The three-site requirement is proven, not cargo-culted.

### Break arm 2 (Task 2): `next build` inlines at BUILD time

Real `npm run build`, twice:

| Arm | Command | Files under `.next/` containing the sentinel |
|---|---|---|
| SET | `NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE='SENTINEL-OFFICE-31-08' npm run build` | **8**, including `.next/static/chunks/1ymbw3_ii162p.js` and `.next/static/chunks/0znv56yqggb57.js` |
| UNSET | `npm run build` | **0** |
| CONTROL | `rg -uu -l 'NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE' .next` on the UNSET tree | **15** |

The control is what makes the 0 evidence: 15 files still name the variable, so the zero is about
the sentinel and not about a dead search. The sentinel reaching `.next/static/chunks/` is direct
proof the value is baked into the **browser** bundle, which is why a runtime `environment:` entry
cannot fix it.

### Break arm 3 (Task 2): the `.env.example` inline-comment trap

| Arm | Input | `grep -cE '^NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE=[[:space:]]*#'` |
|---|---|---|
| CLEAN | the committed `.env.example` | `0` |
| BREAK | `NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE=  # set me in .env` | `1` |

The expected-0 was shown capable of firing before being trusted.

### Break arm 4 (Task 3, arm a): F4 — a dangling anchor

Break: `PolicySection` given its own derivation (`heading.toLowerCase().replace(/\s+/g,"_")`)
instead of the shared `sectionId` — "two derivations that agree today will disagree later".

```
✕ resolves every href when the nav and the document agree
  > 160 |     expect(dangling).toEqual([])
  - Array []
  + Array [ "#who-we-are", "#what-data-we-collect",
  +         "#how-long-we-keep-data", "#your-rights-and-how-to-use-them" ]
Tests: 3 failed, 7 passed
```

Restored, verified by content: `git hash-object` → `b934a7aebaa6728bf5dd45e377ccb0a50714341b`
(baseline `b934a7ae…`). Clean arm: 10 passed.

### Break arm 5 (Task 3, arm b): THE ARTEFACT CLASS, demonstrated

Break: `{children}` removed from `PolicyPage`, so the page renders chrome and a title and
nothing else.

```
✓ renders inside the shared shell with exactly one main landmark
✓ states a date AND a version under the title
✓ ARTEFACT DEMONSTRATION: axe still reports zero over an empty document …
✕ carries one h1, h2 sections, and skips no heading level inside main
✕ caps the prose measure at 68 characters
✕ reaches zero violations on a fully rendered document
    TestingLibraryElementError: Unable to find an accessible element with the role "heading"
Tests: 3 failed, 8 passed
```

**Read the third failure carefully — it is the whole point.** The test named "reaches zero
violations" failed on its **control**, not on `toHaveNoViolations`. `toHaveNoViolations` does not
appear anywhere in the output: axe was perfectly happy scanning a policy page with no content in
it. Two other assertions — the landmark count and the date line — also stayed green over that
blank page. The non-vacuity control is the only thing between this build and a green suite over
an empty legal document. That is D-13, demonstrated rather than described, and it is why the
control is asserted BEFORE every scan and scoped to `main` (the shared footer supplies h2
headings of its own, so a document-wide heading count would have stayed green here too).

The demonstration is also pinned as a permanent test — "ARTEFACT DEMONSTRATION: axe still
reports zero over an empty document, and the control is what catches it" — so it re-runs forever
rather than living only in this document.

Restored, verified by content: `b934a7aebaa6728bf5dd45e377ccb0a50714341b`.

### Break arm 6 (Task 3, arm c): the ToC's accessible name

Break: the `id` attribute deleted from the label element. **The `aria-labelledby` attribute was
left in place**, which is exactly the analog's bug and exactly what a grep cannot see.

```
✕ has an accessible name resolved from a real element
    TestingLibraryElementError: Unable to find an accessible element with the
    role "navigation" and name `/on this page/i`
  > 88 |  const nav = screen.getByRole("navigation", { name: /on this page/i })
✕ lists every section once, in document order
Tests: 2 failed, 8 passed
```

A `grep -cF 'aria-labelledby="on-this-page"'` would have returned 1 on this broken build. Only
resolving the name catches it. Restored, verified by content:
`593d825b4e2bd61397593ffb86ae938ba994cbdf`.

### Break arm 7 (Task 3, arm d): the preserved Companies House disclosure

**First attempt was a bad arm and is recorded as such.** Substituting `13434105` for
`{c.companyNumber}` made `getByText("16471464")` fire first, so the arm never reached the
absence assertion and could not distinguish the two checks. Re-run the way the recorded incident
actually happened — the dissolved number **added alongside** the correct one:

```
✕ still renders the operator identity from getCompanyInfo()
  > 257 |  expect(container.textContent).not.toContain("13434105")
  Expected substring: not "13434105"
  Received string: "… Company number16471464 Place of registrationEngland & Wales
                     Previous registration13434105 …"
Tests: 1 failed, 10 skipped
```

Every presence assertion passed — `getByText("16471464")` succeeded, the test ran past it — and
**only** the absence assertion fired. This confirms CF-1's finding on this page independently:
if you assert on company identity, assert absence.

Restored, verified by content: `ce79d4ed6ec2fe02c4fbe400b542ac584f7925d5`.

### Closing clean arm (the assertion the restores are proven by)

```
$ git status --short              (empty)
$ git hash-object frontend/components/legal/policy-page.tsx   b934a7ae…  ✓ baseline
$ git hash-object frontend/components/legal/policy-toc.tsx    593d825b…  ✓ baseline
$ git hash-object frontend/app/legal/page.tsx                 ce79d4ed…  ✓ baseline
$ npx jest components/legal/__tests__ lib/__tests__/company-contact.test.tsx \
          components/platform/__tests__ components/public/__tests__ --ci
Test Suites: 9 passed, 9 total     Tests: 63 passed, 63 total
```

All four breaks were made **after** the work was committed, so `git checkout --` restored from a
committed state rather than from the index.

## Vacuous checks found and recorded (not silently substituted)

| Check | Why it cannot fail as written | What was done |
|---|---|---|
| "remove the Dockerfile `ARG`, confirm the value inlines empty" | with no value supplied, both arms inline empty — the arm is a no-op | replaced with a sentinel-valued arm; both forms recorded above |
| `grep -cF 'NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE' frontend/Dockerfile >= 1` | **3 hits, and `frontend/Dockerfile:99` is a COMMENT** — deleting both the `ARG` and the `ENV` lines still leaves count 1 | recorded; the real evidence is break arm 1, which exercises the mechanism |
| `grep -cF 'PublicShell' components/legal/policy-page.tsx >= 1` | 5 hits, of which lines 22 and 29 are prose | the tests assert the chrome by ROLE (banner/contentinfo/main#main/skip-link) — strictly stronger |
| `grep -cF 'max-w-[68ch]' components/legal/policy-page.tsx >= 1` | 4 hits, of which line 146 is a comment | added a rendered-markup assertion ("caps the prose measure at 68 characters") that prose cannot satisfy |
| `grep -cF 'aria-labelledby="on-this-page"' >= 1` | passes on a build where the target id has been deleted (break arm 6 proves it) | the accessible name is resolved in the test instead |

The `use client` and `font-bold` prohibitions are the inverse risk — a comment mentioning the
token would make an expect-0 fail spuriously — so neither string appears anywhere in either new
component, comments included. Measured: `grep -cF` returns `0` for both, in both files.

## Verification

| Gate | Result |
|---|---|
| `npx jest components/legal/__tests__` | 21 passed |
| `npx jest lib/__tests__` | 19 suites, 246 passed |
| Full jest suite | **106 suites, 1004 tests, all passed** |
| `npm run build` (the only type-check gate) | `BUILD_RC=0`, TypeScript finished clean |
| `npx eslint` on every touched file | `ESLINT_RC=0`, 0 errors, 0 warnings |
| `npm run lint` (whole repo) | rc `0`; 28 pre-existing warnings, none in files this plan touched |
| `scripts/docs-freshness.sh --write` | rc `0`; jest_blocks 976→1004, total 2892→2920 |

Per the metrics protocol, `docs/metrics.json` was regenerated but the prose counts in
`README.md`, `CLAUDE.md` and `AGENTS.md` were **not** touched — the correct figure is unknowable
from inside one worktree while siblings are still adding tests.

`STATE.md` and `ROADMAP.md` were not modified.

## Threat model outcomes

| Threat ID | Disposition | Outcome |
|---|---|---|
| T-31-08-01 | mitigate | Question 3 (may the address be published at all?) is put to the owner unanswered; the service-address alternative is offered. No address was invented. |
| T-31-08-02 | mitigate | Both directions of the fallback are tested, including a whitespace-only value (`VAR=" "` is non-empty and defeats a bare truthiness check). The unset arm asserts the TERM is absent, not that the value is empty — a missing `<dd>` and a blank `<dd>` are the same observation otherwise. |
| T-31-08-03 | mitigate | `grep -cF 'use client'` = 0 in both new components, comments included. Both are plain server components. |
| T-31-08-04 | accept | Both values are published information by construction. No secret was added as a `NEXT_PUBLIC_*` value. |
| T-31-08-05 | mitigate | Break arm 5. The control was demonstrated firing while axe reported zero, and the demonstration is a permanent test. |
| T-31-08-SC | accept | No package installed. |

## Threat Flags

None. No network endpoint, auth path, file access pattern or schema change was introduced; the
two new build args are public by construction and are declared in the register above.

## Known Stubs

None. The four `/legal/*` routes linked from the index do not exist yet — that is the plan's
stated sequencing (31-11/12/13 build them; 31-17 asserts reachability), not a stub, and it is
recorded in the index file's own comment.

---

## THE CHECKPOINT — Task 1, awaiting the owner

Two values that a published privacy notice legally requires are set nowhere in this repository.
They are business facts, not engineering choices. **Supply, or explicitly decline, each of the
three below.**

**1. The registered office address**, as it should appear in a published privacy notice.
UK GDPR Art. 13(1)(a)-(b) requires the controller's identity **and contact details**. This must
be the address on the Companies House record for **16471464** (J'TOYE DIGITAL LTD, **ACTIVE**) —
not the dissolved namesake **13434105**. The live site has already cited the wrong one once.

**2. The data-subject contact route.** Either (a) a monitored email address for data-protection
requests, (b) a public DSAR form, or (c) both with the email as the fallback for people who will
not use a form. State which.

> **A correction to the plan's own wording, measured.** The plan offers "(b) the public DSAR form
> 31-05 built". 31-05 built an **API endpoint** — `POST /api/v1/public/gdpr/dsar` — and **no
> frontend form exists**. No plan in this phase is scheduled to build one. So option (b) is not
> available today unless someone adds it; the realistic choices are a monitored email address, or
> commissioning a form as new scope.

**3. Whether the address may be published at all.** If the registered office is a residential
address, say so — that is a real consideration, and the honest answer changes what gets
published, not whether the requirement is met. If it cannot be published, the alternative is a
service address, and the page must not claim a postal contact it cannot honour.

**Consequence of a decline, already implemented and recoverable by one build arg:**
`resolveControllerContact()` returns `anyRoute: false`, the contact block is omitted in full
including its heading, and no blank line is rendered where a legally required detail belongs. The
missing value then becomes a **named gap in 31-13's conformance statement with a remediation
date**. What must not happen is a page that renders a term with nothing after it — that is
simultaneously a broken page and an Art. 13 failure, and it gets triaged as the first.

Once answered, the only change needed is the value itself in `.env.example` (line 279 / the line
below it) and in the deploying environment. No code change.

---

## Self-Check: PASSED

Files claimed created — all five present (`ls -1`, no MISSING):
```
frontend/components/legal/policy-page.tsx
frontend/components/legal/policy-toc.tsx
frontend/components/legal/__tests__/policy-page.a11y.test.tsx
frontend/components/legal/__tests__/policy-toc.test.tsx
frontend/lib/__tests__/company-contact.test.tsx
```

Commits claimed — both present at the tip of `worktree-agent-a06fc292561ce9545`:
```
04920cd3 feat(31-08): PolicyPage + PolicyToc shell, and /legal becomes an index
e5e1e2ba feat(31-08): wire the controller contact values through the build-arg triple
8e8392b6 docs(31): reconcile metrics and the V62 ledger after the wave-1 merge   (base)
```
