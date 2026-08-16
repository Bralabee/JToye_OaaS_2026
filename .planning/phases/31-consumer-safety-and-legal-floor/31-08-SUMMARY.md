---
phase: 31-consumer-safety-and-legal-floor
plan: 08
subsystem: frontend-legal
tags: [legal, gdpr, a11y, config, build-args, seo]
status: COMPLETE — all 3 tasks; Task 1 resolved by owner verdict (office declined, dedicated privacy address)
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
  - "OWNER VERDICT 1 — registered office DECLINED for now; ships empty, recorded as a named exception, recoverable by one build arg"
  - "OWNER VERDICT 2 — data-subject route is a DEDICATED privacy@olajay.co.uk, not the shared support queue (one-month statutory clock)"
  - "StorefrontLegalStrip NOT built — app/shop/layout.tsx:73 already renders PublicFooter over the whole /shop/** subtree"
  - "The DSAR API path is deliberately absent from ControllerContact — it is an endpoint, not a page"
  - "PolicyToc ships expanded below lg — a known, recorded deviation from 'collapsed', chosen because it fails safe"
open_items:
  - "PRE-PUBLICATION: privacy@olajay.co.uk must exist and be MONITORED before any page naming it goes live — unverifiable from this repository"
  - "31-13 must publish the registered-office exception (text supplied verbatim below)"
  - "31-07's Article 26 effectiveness-gate box stays UNTICKED"
metrics:
  tasks_completed: 3
  tasks_total: 3
  jest_blocks: "976 -> 1008"
  total_logical_invocations: "2892 -> 2924"
---

# Phase 31 Plan 08: Legal Policy Shell + Controller Contact Wiring — Summary

The shell all four policy documents will render through, and the two contact values a
published privacy notice legally requires wired through every site that can supply them —
with the values themselves decided by the owner rather than invented.

## Status: COMPLETE — the Task 1 gate was raised and answered

**Task 1 was a `gate="blocking-human"` checkpoint.** It was returned unanswered, the owner ruled
on both values, and the verdicts are implemented. No address was invented, inferred or derived at
any point.

**Execution order deviation, stated explicitly.** The plan places Task 1 first. It was NOT
executed first: Tasks 2 and 3 were executed in their decline-safe form, the gate was returned,
and Task 1 landed last — on the orchestrator's carry-forward instruction CF-1 ("Wire the
variable, validate it, and raise the value itself at your checkpoint. Do not invent an
address.").

This was safe because the code Task 2 required is **identical either way** — the plan says so in
its own words: *"If Task 1 declined a value, implement the same code path and record the gap. The
code must be correct either way; that is what makes the decline recoverable by setting one build
arg later rather than by another code change."* That prediction held exactly: resolving the gate
changed **no code at all**, only two configuration values.

## Tasks

| Task | Name | Status | Commit |
|------|------|--------|--------|
| 2 | Wire the values through the build-arg triple | Complete | `e5e1e2ba` |
| 3 | PolicyPage, PolicyToc, /legal becomes an index | Complete | `04920cd3` |
| 1 | Controller contact values (gate raised, then answered) | Complete | `34c516d6` |

## The owner's verdicts, and what they mean downstream

### Verdict 1 — REGISTERED OFFICE: omit for now

`NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` ships **empty**, exactly the declined path already built.
`resolveControllerContact()` returns `postal: null`, the block is dropped heading and all, and no
blank line is rendered where a legally required detail belongs.

**The gap is recorded, not hidden.** Three obligations follow, and each is discharged here:

**(a) 31-13 — the named exception. Consume this text verbatim; do not re-derive it.**

> **Registered office address not published.** J'Toye Digital Ltd (company number 16471464,
> registered in England & Wales) does not currently publish its registered office address on this
> site. UK GDPR Article 13(1)(a)-(b) requires the controller's identity and contact details in a
> privacy notice; the identity and an electronic contact route are published, the postal address
> is not. Data-protection enquiries and data-subject requests should be sent to the contact
> address given in the privacy notice, which is monitored. The registered office remains publicly
> available from the Companies House register against company number 16471464.
>
> *Status: open. Owner decision recorded during phase 31. Remediation: publish the address, or a
> service address, at the next review of this statement.*

31-13 should date-stamp it and set a remediation date per D-12 (partial conformance, dated, with
named exceptions). Where the prose names a company number it is **16471464**; the dissolved
namesake 13434105 must not appear.

**(b) 31-07 — the Article 26 arrangement.** The registered office stays an **open item** and its
effectiveness-gate box stays **UNTICKED**. This plan did not tick it, and nothing here should be
read as satisfying it.

**(c) Recovery is one build arg and no code change.** Set
`NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` in the deploying environment and rebuild the frontend
image. The `ARG`/`ENV` pair, the compose `build.args` entry, the `.env.example` declaration, the
validator entry and the rendering guard are all already in place and tested in both directions.
Because it is a `NEXT_PUBLIC_*` value it is inlined by `next build`, so **the image must be
rebuilt** — a runtime `environment:` entry cannot reach the browser bundle (proven, break arm 2).

### Verdict 2 — DATA-SUBJECT ROUTE: a dedicated privacy address

`NEXT_PUBLIC_DATA_PROTECTION_EMAIL=privacy@olajay.co.uk`.

Reasoning recorded so it is not re-litigated: `olajay.co.uk` is the established platform domain
and `.env.example` already configures `support@olajay.co.uk` as `NEXT_PUBLIC_SUPPORT_EMAIL`, so
this is the same domain and the same build-arg triple. A **dedicated** address rather than the
support queue is deliberate — UK GDPR puts a **one-month statutory clock** on a data-subject
request, and a shared support queue is where that deadline gets missed.

> **⚠ RECORDED AS AN ASSUMPTION, NOT A VERIFIED FACT.** The owner selected "a dedicated privacy
> address"; this specific string is the orchestrator's reading of that choice. **Whether the
> mailbox exists and is monitored cannot be verified from this repository.**
>
> **PRE-PUBLICATION REQUIREMENT:** the mailbox must exist and be monitored before any page naming
> it goes live. A published contact route nobody reads is the same fail-open shape as no route at
> all — and worse, because it looks discharged. This warning is carried in `.env.example` on the
> variable itself, so it travels with the config rather than only with this document.

### A note for 31-11 / 31-12 / 31-13 on the DSAR route

31-09 has landed and added a verification endpoint at `/api/v1/public/gdpr/dsar/verify`, beside
31-05's intake at `/api/v1/public/gdpr/dsar`. Both are **API endpoints**; there is still no
frontend form. If your copy describes how a request is confirmed, that is the route to describe —
but do not publish either path to a consumer as a clickable contact route. The published route is
the email address above.

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

| Site | Form |
|---|---|
| `.env.example` | both keys declared, **no trailing comment on either assignment line** |
| `docker-compose.full-stack.yml` | under the frontend service's **`build.args`**, not `environment:` |
| `frontend/Dockerfile` | `ARG` + `ENV` pair for each |
| `frontend/lib/env-validation.ts` | both added to `optionalEnvVars` — absence is a soft misconfiguration with an operator warning, never a boot failure |

**Proven by compose's own interpolator, not by a grep.** `docker compose --env-file .env.example
config` renders:

```yaml
  frontend:
    build:
      args:
        NEXT_PUBLIC_API_URL: http://localhost:9090
        NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE: ""
        NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL: http://localhost:8085/realms/jtoye-customers
        NEXT_PUBLIC_DATA_PROTECTION_EMAIL: privacy@olajay.co.uk
        NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS: "2"
        NEXT_PUBLIC_SUPPORT_EMAIL: support@olajay.co.uk
```

This satisfies the plan's "assert by reading the surrounding block, not by a bare name grep" with
the strongest available instrument: the tool that actually consumes the file. It shows both keys
under `build.args`, the office resolving to a genuine `""` (**not** to a comment), and the
address resolved through the same `${VAR:-default}` pattern as the support pair.

`resolveControllerContact()` states the degradation rule once so four pages cannot each get it
wrong: a configured value becomes a route, an unconfigured one becomes `null`, and `anyRoute`
lets a caller drop the block **including its own heading**.

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

### 3. UI-SPEC CORRECTION — `StorefrontLegalStrip` was NOT built; the premise for it is false

**Accepted by the orchestrator. 31-17 should not re-litigate this.**

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

**The correction, stated for the UI-SPEC:** S2's "Storefront reachability" row and the
`StorefrontLegalStrip` entry in the Component Inventory ("New") are both superseded. The row's
remediation note in S5 — *"`/shop/[slug]` has no `contentinfo` landmark … The S2 legal strip
supplies it"* — describes a defect that does not exist. RESEARCH Correction 1 already said so and
was re-verified twice: once by this plan, once independently by the orchestrator.

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

### Break arm 3 (Tasks 2 and 1): the `.env.example` inline-comment trap

The trap is live on **exactly** this work, so presence is asserted nowhere — the resolved VALUE
is. A parser reads the raw right-hand side using the permissive end-of-line reading that the real
consumers use (no inline-comment stripping), and the assertions are `office === ""` and
`address === "privacy@olajay.co.uk"`.

**Break arm on the real file** — `NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE=  # supply the Companies
House address` written into `.env.example`, then the suite re-run:

```
✓ finds both keys at all — the control for every assertion below
✓ resolves the data-protection contact to a real address
✕ resolves the registered office to genuinely EMPTY, not to a comment
    Expected: ""
    Received: "  # supply the Companies House address"
  > 246 |     expect(raw).toBe("")
Tests: 1 failed, 10 passed
```

Note which check stayed **green**: "finds both keys at all". A presence assertion — and the
plan's own `grep -cF` — passes on this broken file. Only the value assertion fires.

**And the real consumer agrees, which is what makes this a defect rather than a claim about my
parser.** Same broken file, rendered by docker compose itself:

```
$ docker compose --env-file .env.example -f docker-compose.full-stack.yml config
133:        NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE: '# supply the Companies House address'
```

Compose bakes the comment text into `build.args`, `next build` inlines it into the browser
bundle, and the privacy notice publishes a comment as the registered office. Both directions
recorded; the earlier `grep -cE` form (0 clean / 1 broken) is retained above as the cheap
CI-shaped check, but the value assertion is the one that is trusted.

Restored, verified by content: `git hash-object .env.example` →
`0b02c95253ff18e03f48d9885163a98db3f6be2b` (baseline).

### Break arm 3b (Task 1): the data-protection address needs all three sites too

Same sentinel shape as break arm 1, run against the value the owner actually chose. Real
`docker build --no-cache`, same `--build-arg` passed in both arms:

| Arm | Dockerfile | Output |
|---|---|---|
| CLEAN | `ARG` declared | `#5 0.738 INLINED=[privacy@olajay.co.uk]` |
| BREAK | `ARG` line deleted | `#5 0.546 INLINED=[]` |

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
| `grep -cF 'NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE' frontend/Dockerfile >= 1` | **3 hits, and `frontend/Dockerfile:99` is a COMMENT** — deleting BOTH the `ARG` and the `ENV` lines still leaves count 1, so the criterion is satisfied by a build that cannot supply the value at all | **REPLACED, not reported as satisfied.** The real evidence is break arm 1 (the mechanism) and the rendered compose config (the wiring). See the pattern note below. |
| any presence check on the `.env.example` declaration | `VAR=  # text` resolves to the COMMENT TEXT, so the key is present and the variable is configured-to-a-comment — proven against compose itself in break arm 3 | replaced with a resolved-VALUE assertion plus an in-suite break direction |
| `grep -cF 'PublicShell' components/legal/policy-page.tsx >= 1` | 5 hits, of which lines 22 and 29 are prose | the tests assert the chrome by ROLE (banner/contentinfo/main#main/skip-link) — strictly stronger |
| `grep -cF 'max-w-[68ch]' components/legal/policy-page.tsx >= 1` | 4 hits, of which line 146 is a comment | added a rendered-markup assertion ("caps the prose measure at 68 characters") that prose cannot satisfy |
| `grep -cF 'aria-labelledby="on-this-page"' >= 1` | passes on a build where the target id has been deleted (break arm 6 proves it) | the accessible name is resolved in the test instead |

The `use client` and `font-bold` prohibitions are the inverse risk — a comment mentioning the
token would make an expect-0 fail spuriously — so neither string appears anywhere in either new
component, comments included. Measured: `grep -cF` returns `0` for both, in both files.

### The pattern: a token satisfying its own prohibition

**This is not a one-off.** Four of the five weak checks above are the same shape — a `grep` over
a source file, where the file's own explanatory comment contains the token being searched for, so
the check certifies the prose rather than the code. Wave 1 of this phase hit it three times
independently, and this plan hit it four more. The count is now seven in one phase, which makes
it a class rather than an accident.

The rule that follows, for anyone writing a verify limb in this phase: **a grep over a
human-authored source file cannot assert that code exists.** Either (a) assert on rendered
output / resolved config / observed behaviour, which prose cannot satisfy, or (b) anchor the
pattern so a comment cannot match it (`^ARG NEXT_PUBLIC_…$`, not a bare substring). Option (a) is
strictly better and is what was used here in every case.

## Verification

| Gate | Result |
|---|---|
| `npx jest components/legal/__tests__` | 21 passed |
| `npx jest lib/__tests__` | 19 suites, 250 passed |
| Closing clean arm (legal + lib + platform + public) | **27 suites, 306 tests, all passed** |
| Full jest suite | **106 suites, 1008 tests, all passed** |
| `npm run build` (the only type-check gate) | `BUILD_RC=0`, TypeScript finished clean, run again after Task 1 |
| `npx eslint` on every touched file | `ESLINT_RC=0`, 0 errors, 0 warnings |
| `npm run lint` (whole repo) | rc `0`; 28 pre-existing warnings, none in files this plan touched |
| `docker compose --env-file .env.example config` | `CONFIG_RC=0`; both keys resolved under `frontend.build.args` |
| `scripts/docs-freshness.sh --write` | rc `0`; jest_blocks 976→1008, total 2892→2924 |

Per the metrics protocol, `docs/metrics.json` was regenerated but the prose counts in
`README.md`, `CLAUDE.md` and `AGENTS.md` were **not** touched — the correct figure is unknowable
from inside one worktree while siblings are still adding tests.

`STATE.md` and `ROADMAP.md` were not modified.

## Threat model outcomes

| Threat ID | Disposition | Outcome |
|---|---|---|
| T-31-08-01 | mitigate | **Fully discharged.** The publish/decline question went to the owner unanswered and was answered: no address is published. No address was invented, and the residential-address hazard cannot arise from an empty value. |
| T-31-08-02 | mitigate | Both directions of the fallback are tested, including a whitespace-only value (`VAR=" "` is non-empty and defeats a bare truthiness check) and the inline-comment shape (proven against compose itself). The unset arm asserts the TERM is absent, not that the value is empty — a missing `<dd>` and a blank `<dd>` are the same observation otherwise. The declined registered office exercises this path in production, not just in a test. |
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

## THE CHECKPOINT — raised, and RESOLVED

The gate was returned unanswered rather than guessed. Both values were ruled on by the owner and
the verdicts are implemented in commit `34c516d6`. The questions and their answers, for the
record:

| # | Question | Answer |
|---|---|---|
| 1 | The registered office address for a published privacy notice | **DECLINED for now.** Ships empty; recorded as a named exception (text supplied above); recoverable by one build arg. |
| 2 | The data-subject contact route | **A dedicated address: `privacy@olajay.co.uk`.** Not the shared support queue — a data-subject request carries a one-month statutory clock. |
| 3 | Whether the address may be published at all | Superseded by (1). The page claims no postal contact it cannot honour, because it claims none. |

**One measured correction to the plan was accepted before the decision went to the owner.** The
plan offered "(b) the public DSAR form 31-05 built". 31-05 built an **API endpoint** —
`POST /api/v1/public/gdpr/dsar` — and no frontend form exists; 31-09 has since added
`/api/v1/public/gdpr/dsar/verify`, also an endpoint. No plan in this phase builds a form, so
option (b) was not available and the choice was between an email address and new scope. The
orchestrator re-verified this independently with a scoped search over `frontend/app`,
`frontend/components`, `frontend/lib` and `frontend/e2e`: exactly one match, a marketing prose
file.

**What resolving the gate changed:** two values in `.env.example`, one compose default, and four
assertions. **No code.** That is the property the decline-safe design was for, and it held.

### Still open after this plan

1. **`privacy@olajay.co.uk` must exist and be MONITORED before any page naming it goes live.**
   Assumption, not verified fact; unverifiable from this repository. Carried on the variable in
   `.env.example` as well as here.
2. **31-13 must publish the registered-office exception** — verbatim text supplied above.
3. **31-07's Article 26 effectiveness-gate box stays UNTICKED.**

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

Commits claimed — all present on `worktree-agent-a06fc292561ce9545`:
```
34c516d6 feat(31-08): resolve the controller contact gate — dedicated privacy address, office declined
db19185f docs(31-08): summary — legal policy shell + controller contact wiring
04920cd3 feat(31-08): PolicyPage + PolicyToc shell, and /legal becomes an index
e5e1e2ba feat(31-08): wire the controller contact values through the build-arg triple
8e8392b6 docs(31): reconcile metrics and the V62 ledger after the wave-1 merge   (base)
```

Working tree clean after every break arm; all four restores verified by `git hash-object`
against their pre-break baselines, and the closing clean arm re-run last (27 suites, 306 tests).
