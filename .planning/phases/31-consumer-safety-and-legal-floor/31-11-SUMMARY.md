---
phase: 31-consumer-safety-and-legal-floor
plan: 11
subsystem: frontend-legal
tags: [legal, gdpr, pecr, privacy, cookies, browser-storage, article-26, seo]
status: COMPLETE — both tasks; inventory re-derived and CORRECTED in three places
requires:
  - "31-07 (docs/legal/article-26-arrangement.md — the essence reproduced here)"
  - "31-08 (PolicyPage / PolicySection / PolicyToc / sectionId / resolveControllerContact)"
  - "31-05 + 31-09 (the DSAR intake and its verification gate — described, never published as a route)"
provides:
  - "/legal/privacy — the D-15 layered notice carrying the Article 26(2) essence"
  - "/legal/cookies — the exhaustive cookie AND browser-storage disclosure"
  - "The measured browser-storage inventory: 4 cookie families, 10 localStorage keys, 5 sessionStorage keys"
  - "A rendered-output guard against the JSX whitespace defect (DEF-31-11-01)"
affects:
  - "31-12 (owns every retention number; this notice states none and links out)"
  - "31-13 (must publish the registered-office exception; this page omits the block cleanly)"
  - "31-16 (jtoye-cookie-notice-ack is disclosed forward — see the merge-gate item)"
  - "31-17 (footer reachability; the h2 anchor ids below are the deep-link contract)"
tech-stack:
  added: []
  patterns:
    - "Essence reproduced verbatim with identity interpolated, so no company-number literal lives in page source"
    - "Completeness asserted by ITERATION over a duplicated inventory, never by spot-check"
    - "Explicit {\" \"} at inline-element boundaries — the transform deletes the space otherwise"
key-files:
  created:
    - frontend/app/legal/privacy/page.tsx
    - frontend/app/legal/cookies/page.tsx
    - frontend/app/legal/__tests__/privacy-page.test.tsx
    - frontend/app/legal/__tests__/cookies-page.test.tsx
    - .planning/phases/31-consumer-safety-and-legal-floor/deferred-items.md
  modified:
    - docs/metrics.json
decisions:
  - "CORRECTION — sessionStorage is 5 keys, not the plan's 2: the three OAuth transients were missed"
  - "CORRECTION — localStorage is 9 keys, not the plan's 8: the plan's prose counted bullet LINES, not keys"
  - "CORRECTION — 'ZERO third-party scripts' is FALSE: Stripe.js loads on the payment step"
  - "jtoye-cookie-notice-ack disclosed forward for 31-16, which cannot be seen from this worktree"
  - "The access-cookie lifetime is DESCRIBED, never hardcoded — it is the realm's number, not this repo's"
metrics:
  tasks_completed: 2
  tasks_total: 2
  jest_blocks: "1008 -> 1050"
  jest_files: "106 -> 108"
  total_logical_invocations: "2951 -> 2993"
---

# Phase 31 Plan 11: Privacy Notice + Cookie/Browser-Storage Policy — Summary

The two documents LGL-01 names that did not exist in any form — written against a
**re-measured** storage inventory that disagreed with the plan in three places, and
against the Article 26 essence rather than a paraphrase of it.

## Status: COMPLETE

Both tasks executed, six planned break arms run plus one unplanned, all restores
verified by content hash, closing clean arm re-run last.

| Task | Name | Status | Commit |
|------|------|--------|--------|
| 1 | The privacy notice | Complete | `3ad8dad9` |
| 2 | The cookie and browser-storage policy | Complete | `1702018e` |
| — | Whitespace defect found during a break arm (Rule 1) | Fixed | `d7edda2d` |
| — | Metrics, lint, deferred item | Complete | `41eaa859` |

---

## ⚠ START HERE — the worktree was created from the WRONG base

**This worktree was branched from `main` (`bb2ae65d`), not from
`phase/31-consumer-safety-legal-floor` (`0d1834c2`) as the task brief stated.** At spawn,
`git log` showed *"Phase 28: Security Triage"* as HEAD and **none of this plan's
dependencies existed** — no `PolicyPage`, no `PolicyToc`, no `resolveControllerContact`,
no `docs/legal/article-26-arrangement.md`, and no `31-11-PLAN.md` to read.

Recovered before any work was done, and it was safe to do so because the relationship was
strictly linear:

```
$ git merge-base --is-ancestor bb2ae65d 0d1834c2   -> rc=0   (phase branch CONTAINS main tip)
$ git rev-list --count HEAD..0d1834c2              -> 76     (commits I was missing)
$ git rev-list --count 0d1834c2..HEAD              -> 0      (commits I would have lost)
$ git status --porcelain | wc -l                   -> 0      (nothing uncommitted)
$ git reset --hard 0d1834c2
```

Zero commits of my own and zero uncommitted files, so the reset was a pure fast-forward
that destroyed nothing.

**This is the known `feedback_worktree_merge` trap and the orchestrator should assume
every wave-3 sibling hit it too.** A sibling that did *not* check would have executed
against a tree with no `PolicyPage`, and the most likely failure mode is not a crash —
it is an executor re-deriving the 31-08 API from scratch and producing a second,
incompatible policy shell. **Worth a direct check on all six worktrees before merging.**

---

## The storage inventory, RE-DERIVED — and three corrections

The plan supplied an inventory measured 2026-08-15 and instructed that it be re-derived,
with the tree winning any disagreement. It disagreed in three places, one of them
material to a published legal claim.

### Correction 1 — sessionStorage is **5** keys, not 2

The plan listed `jtoye-auth-return` and `jtoye-track-email`. The sweep found three more,
written together by `storeAuthTransients()`:

```
$ rg -uu -n 'sessionStorage\.(setItem|getItem|removeItem)' app components lib hooks \
        --glob '!**/__tests__/**' --glob '!**/*.test.*'
lib/customer-auth.ts:174:  sessionStorage.setItem(PKCE_VERIFIER_KEY, verifier)
lib/customer-auth.ts:175:  sessionStorage.setItem(OAUTH_STATE_KEY, state)
lib/customer-auth.ts:176:  sessionStorage.setItem(OAUTH_NONCE_KEY, nonce)
...
$ rg -uu -n 'PKCE_VERIFIER_KEY|OAUTH_STATE_KEY|OAUTH_NONCE_KEY' lib/customer-auth.ts
168:const PKCE_VERIFIER_KEY = "jtoye-pkce-verifier"
169:const OAUTH_STATE_KEY   = "jtoye-oauth-state"
170:const OAUTH_NONCE_KEY   = "jtoye-oauth-nonce"
```

They are constants, so a search for the literal key strings would have found nothing —
which is presumably how the original measurement missed them.

### Correction 2 — localStorage is **9** keys, not 8

The plan's prose says "eight localStorage keys" but its own bullet list contains nine:
the final bullet packs `kds-muted, theme` onto one line. **The prose counted lines, not
keys.** No key was actually missing from the plan's list; the total was.

`hooks/` was outside the plan's declared sweep, so it was checked separately:
`useStoredState` is a generic keyed localStorage hook, and its only caller is
`cart-provider.tsx` with `cartStorageKey(slug)` — an existing key, not a new one.

### Correction 3 — "**ZERO** third-party scripts" is FALSE, and this one is material

The plan instructed the page to state *"There are ZERO analytics, tag-manager or
third-party scripts. Say so plainly."* Two thirds of that is true and is stated. The
third is not:

```
$ rg -uu -n '@stripe/' app components lib hooks --glob '!**/__tests__/**'
app/shop/[slug]/checkout/page.tsx:7: import { loadStripe } from "@stripe/stripe-js"
app/shop/[slug]/checkout/page.tsx:8: import { Elements, PaymentElement, ... } from "@stripe/react-stripe-js"

app/shop/[slug]/checkout/page.tsx:69-71
  const stripePromise = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
    ? loadStripe(process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY)
    : null
```

Corroborated by a second, independent instrument — the CSP does not allow-list a domain
nothing loads:

```
$ rg -uu -n 'stripe\.com' frontend/lib/security-headers.ts
92: script-src 'self' 'nonce-${nonce}' 'strict-dynamic' … https://js.stripe.com https://*.js.stripe.com
99: frame-src https://js.stripe.com https://*.js.stripe.com https://hooks.stripe.com
```

`loadStripe` injects `js.stripe.com` at runtime and Stripe sets its own cookies. **A
published cookie policy asserting "no third-party scripts" would have been a false
statement of fact in a legal document** — the precise class of error this page exists to
prevent. The page names Stripe, scopes it to the payment step, and does **not** enumerate
Stripe's cookie names: those are Stripe's to publish, and copying a list this repo does
not own is the same defect as hardcoding the realm's `300`.

The analytics half was measured and really is zero, so it is stated plainly:

```
$ rg -uu -ni 'googletagmanager|google-analytics|gtag\(|posthog|mixpanel|segment\.com|
              hotjar|plausible|fathom|matomo|facebook\.net|fbq\(' app components lib hooks \
              --glob '!**/__tests__/**'
rc=1   (no matches — and the Stripe search above is the control proving the sweep works)
```

Also checked and absent: `indexedDB`, `caches.open`, `navigator.storage`, `window.name`
(rc=1).

### The inventory as published

**Cookies — 4 first-party families** (all `httpOnly`, `sameSite=lax`, `secure` in
production, `path=/` — `lib/customer-auth-cookies.ts:31-38`):

| Name | Lifetime as published | Source of truth |
|---|---|---|
| `jtoye-customer-access` | described as the IdP's session length, **never a number** | `login/route.ts:51` — `maxAge = tokens.expiresAt - now` |
| `jtoye-customer-refresh` | up to 30 days | `REFRESH_MAX_AGE = 60*60*24*30` |
| `jtoye-customer-id` | up to 30 days | same constant |
| `authjs.*` family | dashboard session; sign-in values discarded on completion | read from the installed `@auth/core` |

The `authjs.*` members are named individually rather than as "some dashboard cookies",
read out of the installed package rather than assumed from the v4/v5 naming change:

```
$ rg -uu -n 'name: `\$\{|name: `__' node_modules/@auth/core/lib/utils/cookie.js
49: `${cookiePrefix}authjs.session-token`     78: `${cookiePrefix}authjs.pkce.code_verifier`
58: `${cookiePrefix}authjs.callback-url`      88: `${cookiePrefix}authjs.state`
69: `${useSecureCookies ? "__Host-" : ""}authjs.csrf-token`
98: `${cookiePrefix}authjs.nonce`            107: `${cookiePrefix}authjs.challenge`
```

**localStorage — 10 disclosed** (9 measured + 1 forward-declared): `jtoye-cart-<shop>`,
`jtoye-checkout-email-<shop>` **(EMAIL)**, `jtoye-customer-id`,
`jtoye-customer-logged-in`, `jtoye-customer-expires-at`, `jtoye-guest-orders`,
`jtoye-cookie-notice-ack` *(31-16)*, `shopContext`, `theme`, `kds-muted`.

**sessionStorage — 5**: `jtoye-track-email` **(EMAIL)**, `jtoye-auth-return`,
`jtoye-pkce-verifier`, `jtoye-oauth-state`, `jtoye-oauth-nonce`.

**Deliberately NOT disclosed:** the two legacy keys that `clearMarker()` only ever
removes. Their names appear **nowhere in the page source, comments included** — the
wave-1 "token satisfies its own prohibition" trap, avoided by naming the file and
function instead of the keys.

### A collision worth recording

**`jtoye-customer-id` is BOTH a cookie and a localStorage key, holding different
things** — the cookie carries the ID token, the localStorage item carries the opaque
Keycloak `sub` used to stamp a basket. My first duplicate-DOM assertion asserted each
name appears in exactly one row page-wide and **failed on this**. The assertion was
wrong, not the page: it conflated "one name" with "one store". It is now scoped per
table, and collapsing the two rows would have under-disclosed one of them.

---

## The h2 anchor ids — the deep-link contract for 31-17

Read out of the rendered DOM, not derived by hand.

**`/legal/privacy`** (9): `who-we-are` · `who-is-responsible-for-what` ·
`the-trading-line-and-the-gdpr-line` · `what-we-collect-and-why` ·
`allergen-and-dietary-information` · `how-long-we-keep-it` ·
`your-rights-and-how-to-exercise-them` · `complaints` · `changes-to-this-notice`

**`/legal/cookies`** (7): `what-this-policy-covers` · `cookies-we-set` ·
`information-stored-in-your-browser` · `information-stored-for-the-current-tab-only` ·
`third-party-services` · `what-we-do-not-use` · `how-to-see-and-delete-this-information`

Both are ≥ `TOC_MIN_SECTIONS`, so the ToC renders on both.

---

## The contact route rendered CONFIGURED

`resolveControllerContact()` returned `email: privacy@olajay.co.uk`, `postal: null`,
`anyRoute: true`. So:

- the **data-protection contact renders**, as a real `mailto:`;
- the **registered-office block is dropped heading and all** — the owner's Verdict 1
  exercised in production, not merely in a test;
- **31-13 still owns publishing the named exception.** This page does not mention the
  missing address, which is correct — a notice should not narrate its own gaps; the
  accessibility statement is where the exception is declared.

Both configuration states are permanent tests. Unconfigured, the rights section renders
**no mailto at all** and instead names the two routes that exist regardless of
configuration: the shop named on the order confirmation, and the ICO.

**The DSAR endpoints are published nowhere.** `/api/v1/public/gdpr/dsar` and
`/api/v1/public/gdpr/dsar/verify` appear in neither page. The notice *describes* the
confirmation step ("we send that address a single-use confirmation link") without
publishing an unlinkable API path as a consumer route.

**One accuracy constraint taken from 31-09 rather than assumed:** ERASURE executes
end-to-end via the scheduled worker, but ACCESS is *"counted and logged, not executed"*.
The notice therefore says an **erasure** request is carried out automatically across
every shop, and never claims automated fulfilment of an access request.

---

## Deviations from Plan

### 1. [Rule 1 — Bug] The JSX transform silently deleted three spaces

**Found during:** break arm 2c, by reading the rendered-output dump in a failure
message — not by any gate.

The delivered HTML read `js.stripe.comso your card details`, `<shop>there is one item`
and `Clearing site datain your browser`. **The source has the space in all three
places**, so no amount of code review could see it.

Mechanism isolated with a four-arm control, all arms same shape, one variable:

| Arm | Following JSXText | Rendered |
|---|---|---|
| control | no entity | `</code> so your` — kept |
| **break** | `&apos;` several words later | `</code>so your` — **LOST** |
| control | `&apos;` only *before* the element | `</code> so your` — kept |
| control | explicit `{" "}`, entity present | `</code> so your` — kept |

The entity does not need to be adjacent. And this is **systematically reachable**: the
project's own `react/no-unescaped-entities` rule *requires* `&apos;` in JSX text.

Fixed with explicit `{" "}`, and guarded permanently on **both** pages by an assertion
over rendered `innerHTML` (`/<\/(?:code|span|a|strong|em|b|i)>[A-Za-z]\w*/`). The guard
was observed failing before being trusted — it names `"</code>so"`.

**Restore discipline note:** this fix was uncommitted when its break arm ran, so
`git checkout --` would have destroyed it (`trap_break_arm_revert_eats_fixes`). The break
was reverted **by hand**, verified green, and committed before anything else.

Prevalence elsewhere is logged as **DEF-31-11-01** in `deferred-items.md`: approximately
3 boundaries across 2 marketing components, out of scope here. The scanner producing that
number was itself controlled against a fixture (1 bad + 2 good → reported exactly 1)
before the number was believed.

### 2. [Rule 2] The prohibited-allergen-wording check caught its own author

The Article 9 determination bars wording implying the platform has checked a consumer's
allergies. My first draft contained *"it is not a check that the order is **safe for
you**"* — a **denial** — and the assertion went red on it.

That is a false positive against the legal risk and a true positive against the rule as
written. **The rule was kept and the page reworded**, because "not safe for you" is one
careless edit or pull-quote away from "safe for you" and the phrase has no legitimate use
on this surface in either polarity. Recorded in the test so nobody later "fixes" it into
a negation-aware regex.

### 3. The essence is reproduced with identity interpolated

The plan requires the Article 26 essence verbatim, *and* requires `16471464` to be absent
from page source. The essence names the number. Resolved by interpolating
`{company.legalName}` and `{company.companyNumber}` at exactly the two identity points:
**rendered output is the arrangement's words; source holds no literal.** Both constraints
hold simultaneously — `grep -cF '16471464'` on the page is `0`, and the rendered text
contains it.

The short brand name "J'Toye" *is* literal in prose, matching the established idiom on
`/legal` and the UI-SPEC's own copy table. Only the *legal identity* is sourced from
`getCompanyInfo()`.

### 4. A test threshold was arbitrary and was replaced

A first-draft assertion required every table cell to exceed 20 characters and flagged
*"Until you sign out."* (19) — a complete and correct lifetime. An arbitrary quota would
have pushed the page toward padding its cells. Replaced with the defensible bar: non-empty
and not a placeholder (`TBD`/`TODO`/`n/a`/`-`), since a published `TBD` in a legal
schedule is the actual defect.

---

## Falsifiability — both directions, real output

Every arm ran against a **committed** tree. Every restore verified by `git hash-object`.

### Task 1 baseline: `641d234e8546da8f53e700478b1326b16edf2cd9`

**Arm 1a — a literal retention period in the retention section**

```
✕ links to the retention schedule and states no period of its own
    Received: ["6 years", "30 days"]
  > 210 |     expect(periods).toBeNull()
Tests: 1 failed, 18 passed
```
Restored → `641d234e…` ✓

**Arm 1b — the contact unconfigured, fallback replaced by an empty mailto**

```
✕ degrades to the routes that exist when no contact is configured
    Expected length: 0   Received length: 1
    Received object: [<a … href="mailto:">our data protection team</a>]
Tests: 1 failed, 18 passed
```
The failure output shows the defect exactly: `href="mailto:"` — a link that looks
discharged and goes nowhere. Restored → `641d234e…` ✓

**Arm 1c — an `<h4>` directly under an `<h2>`**

```
✕ skips no heading level inside the document
    + "H2 \"Complaints\" -> H4 \"Raising a concern with us first\""
Tests: 1 failed, 18 passed
```
Diagnostic, not merely boolean. Restored → `641d234e…` ✓

### Task 2 baseline: `a3d439f5cc0f81ac1cd6330921e6e5060628f318`

**Arm 2a — delete one storage key row** *(the arm that justifies iteration)*

```
✕ discloses every session-storage key
    + "jtoye-oauth-nonce",
✕ gives every disclosed item a purpose and a lifetime, not just a name
    + "jtoye-oauth-nonce: no row",
Tests: 2 failed, 19 passed
```

**Read the pass count, because it is the actual evidence.** 19 of 21 assertions stayed
green over a policy page that was silently missing a disclosed key — including every
structural, framing and metadata assertion. **Only the two iterating assertions fired**,
and they *named* the missing key. That is the spot-check difference demonstrated rather
than asserted. Restored → `a3d439f5…` ✓

**Arm 2b — add a legacy remove-only key**

```
✕ does not list the legacy keys that are only ever removed
    + "jtoye-customer-profile",
Tests: 1 failed, 20 passed
```
Restored → `a3d439f5…` ✓

**Arm 2c — hardcode `300 seconds`**

```
✕ describes the access cookie's lifetime instead of publishing it
    Expected pattern: /identity provider/i
    Received: "…300 seconds, after which it is renewed in the background…"
✕ publishes no bare seconds figure anywhere on the page
    Expected pattern: not /\b\d+\s*seconds?\b/i
Tests: 2 failed, 19 passed
```
Restored → `a3d439f5…` ✓ — **and this is the arm whose output revealed the whitespace
defect above.**

**Arm 2d (unplanned) — the new whitespace guard**

```
✕ loses no space where an inline element meets the text after it
    + "</code>so",
Tests: 1 failed, 21 passed
```
Reverted **by hand**, not by `git checkout`, because the fix it guards was uncommitted.

### Closing clean arm, run LAST

```
$ git status --short                                     (empty)
$ git hash-object frontend/app/legal/privacy/page.tsx     641d234e…  ✓ baseline
$ git hash-object frontend/app/legal/cookies/page.tsx     2b53855e…  ← see note
$ npx jest app/legal/__tests__ components/legal/__tests__ lib/__tests__/company-contact.test.tsx
Test Suites: 5 passed, 5 total     Tests: 74 passed, 74 total
```

**Note on the cookies hash.** It is deliberately **not** its Task-2 baseline: commit
`d7edda2d` intentionally changed the file to fix the whitespace defect. Each of the three
break-arm restores *was* verified back to `a3d439f5…` at the time it ran; the divergence
is a committed fix, not a failed restore. Recording this rather than quietly reporting a
matching hash, because "the hash matches" is exactly the claim that must not be fudged.

---

## Vacuous checks found and recorded

| Check as specified | Why it is weak | What was done |
|---|---|---|
| `grep -cF '16471464' page.tsx == 0` | passes on a page that renders no identity at all, and on a page that renders the *dissolved* number | kept as the cheap CI-shaped form, but the trusted assertion resolves the **rendered** text: presence of the active number first (the control), then absence of the dissolved one — the ordering 31-08's break arm 7 proved necessary |
| `grep -cF 'browser storage' page.tsx >= 1` | a comment containing the phrase satisfies it | asserted on rendered `main.textContent` instead |
| `grep -cF 'scope="row"' page.tsx >= 1` | one row header satisfies a three-table page | asserted per table, with a non-vacuity control (≥3 tables, each >1 body row) |
| "the two email-bearing keys appear with their retention" | satisfied by the words existing *anywhere* on a long page | asserted **within the same table row**, plus a separate assertion that both are also called out in prose |
| "no literal `300`" | a source grep also matches Tailwind classes like `slate-300`, so it fails on correct pages and passes on a page whose only `300` is in a class | asserted on rendered text with word boundaries, plus a stronger companion (`no bare "<n> seconds"` anywhere) |

The non-vacuity control is applied before **every** substantive scan, scoped to `main`,
per D-13 and 31-08's demonstration that axe reports zero over an empty document.

---

## Verification

| Gate | Result |
|---|---|
| `npx jest app/legal/__tests__` | **42 passed** (20 privacy + 22 cookies) |
| Full `npx jest --ci` | **108 suites, 1050 tests, 0 failures** (baseline 106/1008) |
| `npm run build` (the only type-check gate) | `BUILD_RC=0`; `/legal/privacy` and `/legal/cookies` both listed in the route table |
| `npx eslint` on all four touched files | `ESLINT_RC=0`, 0 errors, 0 warnings |
| `npm run lint` (whole repo) | `LINT_RC=0`; 28 pre-existing warnings, none in files this plan touched |
| `scripts/docs-freshness.sh --write` | `rc=0`; jest_blocks 1008→1050, files 106→108, total 2951→2993 |

### A transient build failure, diagnosed rather than assumed

The **first** `npm run build` returned `BUILD_RC=1` with 18 Turbopack errors. It was not
this plan's code:

```
Received response with status 404 when requesting
https://fonts.gstatic.com/s/worksans/v24/QGYCz_wNahG…woff2

$ curl -s -o /dev/null -w "%{http_code}" <that exact URL>   ->  404
```

`next/font/google` (Work Sans, loaded in the root layout — untouched here) failed to
fetch on a fresh `node_modules`. **Re-running the identical tree returned `BUILD_RC=0`
with zero `gstatic` lines**, which is the decisive control: same code, different result,
therefore transient. A build run with both new pages moved aside also passed, but that
arm is *not* the discriminator and is recorded as such — the same-tree re-run is.

Worth flagging to the orchestrator: a sibling worktree hitting this 404 could report a
red build that is purely network.

Per the metrics protocol, `docs/metrics.json` was regenerated but the prose counts in
`README.md`, `CLAUDE.md` and `AGENTS.md` were **not** touched — the correct total is
unknowable from inside one worktree while five siblings are still adding tests.

`STATE.md` and `ROADMAP.md` were not modified.

---

## Merge-gate items for the orchestrator

1. **31-16 key agreement.** This page discloses `jtoye-cookie-notice-ack`, in
   localStorage, with the lifetime *"Until you clear your browser storage."* If 31-16
   shipped a different key name, a different store, or an expiry, **the cookie policy is
   wrong on merge** and the completeness test will still be green, because the test
   asserts the page against my list, not against 31-16's code. This needs a human diff of
   the two.
2. **Base-commit check on all six wave-3 worktrees** — see the section at the top.
3. **Prose metric reconciliation** on the merged tree (`2993` is this worktree's total and
   will be wrong once siblings land).
4. **31-12 owns every retention number.** This notice states none by construction and the
   assertion enforcing that is scoped to its retention section.
5. **31-13 must still publish the registered-office exception** — unchanged by this plan.
6. **DEF-31-11-01** in `deferred-items.md` affects two marketing components owned by
   nobody in this phase.

---

## Threat model outcomes

| Threat ID | Disposition | Outcome |
|---|---|---|
| T-31-11-01 | mitigate | Inventory re-derived from source and asserted by **iteration**; arm 2a proved a spot-check would not have fired (19/21 green on a page missing a key). Three corrections found. |
| T-31-11-02 | mitigate | Both legacy keys asserted absent, and their names appear nowhere in the page source including comments. |
| T-31-11-03 | mitigate | Access-cookie lifetime described, never hardcoded; arm 2c fires on `300 seconds`. Extended to Stripe: its cookie names are Stripe's to publish and are deliberately not copied. |
| T-31-11-04 | mitigate | Retention section links out and states no period; arm 1a fires, naming `["6 years","30 days"]`. |
| T-31-11-05 | mitigate | Allergen section cites the determination; the trading/GDPR paragraph names both lines. Prohibited wording asserted absent — and the check fired on a *denial* during authoring. |
| T-31-11-06 | mitigate | Identity from `getCompanyInfo()`; both the dissolved number and any literal are absent from source, presence-then-absence asserted on rendered text. |
| T-31-11-07 | mitigate | Rights section points at the configured address; the unconfigured arm is a permanent test proving it degrades to the shop and the ICO rather than an empty mailto. |
| T-31-11-SC | accept | No package added. `npm ci` only. |

## Threat Flags

None. No network endpoint, auth path, file access pattern or schema change was
introduced. Both routes are public, unauthenticated, static server components.

**One pre-existing surface was newly *documented* rather than introduced:** Stripe.js on
the checkout page. It is not new — it is disclosed here for the first time.

## Known Stubs

None. `/legal/retention` and `/legal/accessibility` are linked from both pages and do not
exist yet; that is 31-12's and 31-13's scheduled work, matching how 31-08's index already
links them.

---

## Owner question — one, unanswered

**`privacy@olajay.co.uk` is now published on a consumer-facing page.**

31-08 recorded this as *"an assumption, not a verified fact"* with a **pre-publication
requirement**: the mailbox must exist and be monitored before any page naming it goes
live. This plan is the page that names it, so the requirement is now live rather than
theoretical.

Whether that mailbox exists and is monitored **cannot be verified from this repository**,
so it is not asserted here. A published data-protection contact that nobody reads is
worse than none, because a one-month statutory clock starts on delivery and it looks
discharged.

**This does not block the merge** — the address is a build-time value and the page renders
correctly either way — but it **must be confirmed before this branch reaches production.**

---

## Self-Check: PASSED

Files claimed created — all five present:

```
frontend/app/legal/privacy/page.tsx
frontend/app/legal/cookies/page.tsx
frontend/app/legal/__tests__/privacy-page.test.tsx
frontend/app/legal/__tests__/cookies-page.test.tsx
.planning/phases/31-consumer-safety-and-legal-floor/deferred-items.md
```

Commits claimed — all present on `worktree-agent-ac0c122606d00ba8f`:

```
41eaa859 chore(31-11): regenerate metrics, drop an unused import, log a deferred defect
d7edda2d fix(31-11): restore three spaces the JSX transform silently deleted
1702018e feat(31-11): publish the cookie and browser-storage policy
3ad8dad9 feat(31-11): publish the privacy notice with the Article 26 essence
0d1834c2 docs(31): reconcile metrics and the V63 ledger after the wave-2 merge   (base)
```

Working tree clean after every break arm; all six planned restores plus the unplanned
seventh verified by content, and the closing clean arm re-run last (5 suites, 74 tests).
