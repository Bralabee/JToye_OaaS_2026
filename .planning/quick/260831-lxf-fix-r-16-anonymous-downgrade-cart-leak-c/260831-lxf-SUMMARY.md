---
phase: quick/260831-lxf-cart-identity-downgrade
plan: 01
subsystem: storefront-cart-identity
tags: [security, privacy, frontend, localStorage, keycloak, regression-guard, ci-wiring]
requirements: [R-16]
status: complete
pr: 715
branch: feature/r16-cart-identity-downgrade

requires:
  - "#459 cart identity stamp (owner field, canAdoptCart, clearStoredCarts)"
  - "live compose stack (frontend, core-java, keycloak) for the browser arm"
provides:
  - "resolveCartOwner — the pure add/confirm-never-erase ownership rule"
  - "C1c — the anonymous-downgrade browser arm, running in e2e-nightly.yml"
  - "CLAUDE.md sixth quality dimension: client-persisted identity lifecycle"
affects:
  - "every storefront basket write (frontend/components/storefront/cart-provider.tsx)"
  - "customer session renewal and OAuth callback (frontend/lib/customer-auth.ts)"
  - ".github/workflows/e2e-nightly.yml (one new step)"

tech-stack:
  added: []
  patterns:
    - "ownership markers are ADDED or CONFIRMED by a write, REMOVED only by an explicit sign-out"
    - "assert the stored stamp BY CONTENT after a transition, never the rendered view"

key-files:
  created: []
  modified:
    - frontend/lib/cart-identity.ts
    - frontend/components/storefront/cart-provider.tsx
    - frontend/lib/customer-auth.ts
    - frontend/components/storefront/__tests__/cart-provider-identity.test.tsx
    - frontend/lib/__tests__/customer-auth-signout-clears-carts.test.ts
    - frontend/e2e/cart-identity-boundary.verify.mjs
    - .github/workflows/e2e-nightly.yml
    - docs/metrics.json
    - docs/CHANGELOG.md
    - README.md
    - CLAUDE.md
    - AGENTS.md

decisions:
  - "D-01 honoured: Fix 2 is presented as a backstop and stated VACUOUS on the reported repro"
  - "D-02 honoured: current ?? prior ?? null, with the B-takes-the-slot branch asserted explicitly"
  - "D-03 honoured: useStoredState untouched; the pure decision lives in cart-identity.ts"
  - "D-04 deferred: the 'belongs to another account' affordance"
  - "D-05 deferred: the Playwright .spec.ts cross-identity test"

metrics:
  duration_minutes: 42
  tasks_completed: 3
  commits: 5
  jest_blocks_delta: +7
  total_logical_invocations: "3555 -> 3562"
  completed: 2026-08-31
---

# Quick Task 260831-lxf: R-16 Anonymous-Downgrade Cart Leak — Summary

**Closed R-16 by making a basket write ADD or CONFIRM its owner and never ERASE one, so a lapsed
session can no longer hand customer A's basket to the next person who registers on that browser —
proven red first in jsdom on a committed pre-fix tree and in a real browser against the pre-rebuild
container, then green against a frontend rebuilt from the branch.**

PR: <https://github.com/Bralabee/JToye_OaaS_2026/pull/715>

---

## What was wrong

The access cookie lives 300s. The session probe (`getCustomerSession`) runs on mount, on a 1s poll
and on focus, and a `{ authenticated: false }` answer calls `clearMarker()`, which removes
`jtoye-customer-id`. The provider's write effect fires on the very next render — nothing has to
change for it to run, it is keyed on `[key, value, hydratedKey]` — and `serialize` stamped
`getCurrentCustomerId()` **unconditionally**, with no access to the owner already on disk.
`canAdoptCart(null, anyone)` returns true, so by the time the next customer signed in there was no
boundary left to enforce. They inherited the previous account's basket, and checkout posted those
items against their own name and email.

#459 built the identity stamp and applied its own argument — *only a sign-out unambiguously means a
different person may be next* — to the **read** path. The **write** path quietly did the one thing
the module forbids: it removed an ownership marker on an event that is not a sign-out.

---

## What was built

### Fix 1 — the cure (`frontend/lib/cart-identity.ts`, `cart-provider.tsx`)

`resolveCartOwner(prior, current)` implements D-02: `if (current) return current; return prior ?? null`.
The provider supplies the prior value through a new module-level `readStoredOwner(slug)`, which
returns `undefined` when the stored payload's `shopSlug !== slug` (so another shop's payload can
never donate its owner to this slot), and on corrupt JSON or unavailable storage (an unknown prior
degrades to the previous behaviour, never to something stricter that could eat a basket).
`useStoredState` is untouched, per D-03.

All four branches are load-bearing and all four are asserted:

| prior | current | result | why |
|---|---|---|---|
| null / absent | X | X | guest -> registration carry-over, the good this must not trade away |
| A | null | **A** | the lapsed session. THE FIX. |
| A | B | **B** | B is signed in and writing, so B owns the slot — preserving A would leak B's later items to A |
| A | A | A | unchanged |

### Fix 2 — the backstop (`frontend/lib/customer-auth.ts`)

`setMarker` now reads `getCurrentCustomerId()` before any write and clears every basket when a
**different, non-empty** `sub` arrives. Covers both `handleCallback` and the renewal path.

**Per D-01, stated plainly: on the reported repro this is VACUOUS.** The anonymous downgrade has
already nulled the basket's owner *and* forgotten the recorded id long before any sign-in reaches
here, so `previous` is null and nothing fires. It covers the account switch that happens while the
marker is still intact. It is not sufficient on its own and is not presented as such.

Both operands must be non-empty (T-R16-04): a one-sided `previous !== sub` is also true for
`undefined`, which would empty a live basket every time a session response arrived without a
profile — several times a minute on the 1s poll.

---

## Fail directions — executed and recorded

### Tier 1: jsdom, on the committed pre-fix tree (commit `b7253021`)

The guards were written and committed **while red**, so the natural pre-fix red *is* the fail
direction and it lands on a committed state. **No separate break arm is owed for these, and none was
run** — said explicitly rather than implied.

```
rc=1
FAIL lib/__tests__/customer-auth-signout-clears-carts.test.ts
  ● signing in as a DIFFERENT customer › clears every basket when the incoming sub differs from the recorded one
    expect(received).toHaveLength(expected)
    Expected length: 0
    Received length: 1
    Received array:  [{"productId": "p1", "quantity": 1}]

FAIL components/storefront/__tests__/cart-provider-identity.test.tsx
  ● CartProvider identity boundary › still shows an owned basket while nobody is signed in
    expect(received).toBe(expected) // Object.is equality
    Expected: "sub-customer-a"
    Received: null

  ● CartProvider identity boundary › owner preservation across a lapsed session › does NOT erase an existing owner when the writer is anonymous
    expect(received).toBe(expected) // Object.is equality
    Expected: "sub-customer-a"
    Received: null

Test Suites: 2 failed, 2 total
Tests:       3 failed, 19 passed, 22 total
```

**Controls that correctly PASSED pre-fix** (named, because a fail arm that also fails means the
instrument is broken, not the product):

- `stamps a FRESH guest basket null, because there is nothing to preserve` — the control that stops
  Fix 1 being over-applied into "never write null".
- `hands the slot to B when B writes over a basket A owned` — the D-02 transfer direction; a
  regression guard, not a defect guard.
- `adopts a legacy owner-less payload for the signed-in customer` — the `undefined -> null`
  normalisation.
- `does NOT clear when the SAME customer's session is renewed`.
- `does NOT clear when the session carries NO sub` — the T-R16-04 fail-destructive hazard.

Post-fix, same two files: `rc=0`, `Tests: 22 passed, 22 total`.

### Tier 2: real browser, against the RUNNING pre-fix container

Run before any rebuild. Container identity recorded so the claim is falsifiable:
image `sha256:a9a241329a5080a27c793a96c37375dd60d041c1d85ed7e17b058a7baf5c21cb`,
started `2026-08-31T12:56:18Z`.

```
C1c — a signed-out render must not downgrade owner:A to owner:null (R-16)
  PASS  C1c.0 the seeded basket DOES render while signed out (fail arm: proves the write effect ran)  [empty=false items=1 titles=["Seeded owned-by-a"]]
        stored after the signed-out render: 1 item(s), owner=null
  FAIL  C1c.1 the owner stamp SURVIVES a signed-out render (only sign-out removes it)  [owner=null expected="sub-absent-customer-a"]
  PASS  C1c.2 customer B really registered and is a different identity from A (fail arm for C1c)  [authenticated=true email=cust-r16-1788188992182@example.com distinct=true]
  FAIL  C1c a newly registered customer does NOT inherit the previous account's basket  [empty=false items=1 titles=["Seeded owned-by-a"]]
        stored after B registered: 1 item(s), owner="9417bd94-7a6a-4faf-ad52-1721d871c868" (B.sub=9417bd94-7a6a-4faf-ad52-1721d871c868)
  PASS  C1c.3 the signed-in writer TAKES the slot, so B's items cannot leak back to A  [owner="9417bd94-..." B.sub=9417bd94-...]

14/17 checks passed
FAILED: C4, C1c.1, C1c
```

`C1c.1` and `C1c` are R-16 itself, reproduced end-to-end: the stamp was erased by a signed-out
render, and a brand-new Keycloak registration then inherited the basket. Both of C1c's own fail arms
passed on the same run, so neither red is an instrument artefact. `C1c.3` passed pre-fix — it is the
D-02 reverse-leak regression guard, not a defect guard. (`C4` is a separate decayed instrument;
see below.)

### Tier 2 green, against a frontend REBUILT from this branch

`docker compose -f docker-compose.full-stack.yml up -d --build --force-recreate frontend`.
Proven by identity, not by a status code: the container's image ID changed
`sha256:a9a24132…` -> `sha256:5011cd21…`.

```
rc=0
  PASS  C3.0 / C3          (cross-shop guard)
  PASS  C4.0 / C4.1 / C4   (post-order clear)
  PASS  C1c.0              [empty=false items=1 titles=["Seeded owned-by-a"]]
        stored after the signed-out render: 1 item(s), owner="sub-absent-customer-a"
  PASS  C1c.1              [owner="sub-absent-customer-a" expected="sub-absent-customer-a"]
  PASS  C1c.2              [authenticated=true distinct=true]
  PASS  C1c                [empty=true items=0 titles=[]]
        stored after B registered: 0 item(s), owner="684ad004-1645-46b0-8968-04fe752f388e"
  PASS  C1c.3              [owner="684ad004-..." B.sub="684ad004-..."]
  PASS  C2.0 / C2.1 / C2   (anonymous carry-forward SURVIVES the fix)
  PASS  C1.0 / C1.1 / C1   (sign-out clear)
  PASS  C1b.0 / C1b        (read boundary)

18/18 checks passed
ALL PASS
```

Runtime parity at that moment:

```
rc=0
  core-java    FRESH  image tagged 2026-08-31 15:13:44 UTC >= newest build-input commit 91629350
  edge-go      FRESH  image tagged 2026-08-31 12:55:16 UTC >= newest build-input commit 5c1bb364
  frontend     FRESH  image tagged 2026-08-31 15:12:43 UTC >= newest build-input commit 0221babe
  mcp-server   FRESH  image tagged 2026-08-31 12:55:16 UTC >= newest build-input commit 96c8d794
PASS: 4 running built service(s) match the source tree (0 unverified).
```

### Tier 3: the doc gates, falsified in both directions

Bracketed clean -> arm -> clean, on a committed state, changing **one digit** in `docs/metrics.json`
(`jest_blocks` 1573 -> 1572):

| arm | docs-freshness | check-doc-metrics | check-test-count-oracle |
|---|---|---|---|
| CLEAN 1 | rc=0 | rc=0 | rc=0 |
| BREAK    | **rc=1** | **rc=1** | **rc=1** |
| CLEAN 2 | rc=0 | rc=0 | rc=0 |

Break-arm output (real, not paraphrased):

```
docs-freshness rc=1        "jest_blocks": 1572,  /  "jest_blocks": 1573,
check-doc-metrics rc=1     FAIL: README.md  [jest_blocks]: doc says 1573, docs/metrics.json says 1572
                           FAIL: CLAUDE.md  [jest_blocks]: doc says 1573, docs/metrics.json says 1572
                           FAIL: AGENTS.md  [jest_blocks]: doc says 1573, docs/metrics.json says 1572
check-test-count-oracle rc=1   jest it/test blocks   runner=1573   manifest=1572
```

Restore verified **by content**: `sha256 12f4d5ea…` before and after, identical, and
`git status --short docs/metrics.json` clean. Never by `git diff --stat`.

The changelog citation gate was bracketed the same way (`(#715)` -> `(#7150)`): rc=0 -> **rc=1** ->
restore by content (`#7150` absent, `(#715) — 2026-08-31` present, git-clean) -> rc=0.

### The CI-wiring arming measurement — a MEASURED NEGATIVE

`check-gate-enforcement.sh` was run with the new `e2e-nightly.yml` step present, deleted, and
restored:

| arm | precondition | rc |
|---|---|---|
| CLEAN 1 | step present | 0 (40 gates, 7 workflows, 6 exempt) |
| BREAK | `rg -uu -c cart-identity-boundary` rc=1 count=0 — step genuinely gone | 0 |
| CLEAN 2 | restored, `sha256 e42b3698…` byte-identical, rg rc=0 count=1, git-clean | 0 |

**rc is unchanged in both directions, exactly as the plan predicted.** The gate collects
`find "$REPO_ROOT/scripts" -maxdepth 1 -name 'check-*.sh'` only, so a `frontend/e2e/*.verify.mjs` is
outside its inventory and **no exemption-table entry is owed**. Recorded as measured, not assumed.

---

## Deviations from plan

### 1. [Rule 3 - Blocking] The verify script's item-count locator was dead

- **Found during:** Task 2 STEP 2, the very first pre-fix run.
- **Issue:** `cartPageState` located the basket's item-count line by a *colour utility class*,
  `p.text-sm.text-slate-500`. PR #522 (Lane C a11y, `--primary` -> orange-700) moved that paragraph
  to `text-slate-600`. The locator therefore matched nothing, `.textContent()` waited out its full
  30s default and **threw**, taking down every arm that reads a non-empty basket — C3, C4, C1c, C2
  and C1. The first pre-fix run produced `FAIL C3 threw: locator.textContent: Timeout 30000ms
  exceeded` and nothing usable.
- **Fix:** located by CONTENT (`page.locator("p").filter({ hasText: /^\s*\d+\s+items?\s*$/ })`),
  bounded at 5s, non-throwing, degrading to `-1` which fails every `itemCount >= 1` arm CLOSED and
  announces itself on stdout.
- **File:** `frontend/e2e/cart-identity-boundary.verify.mjs`
- **Commit:** `82d2f870`

### 2. [Rule 3 - Blocking] C4 never ticked the allergen acknowledgement

- **Found during:** Task 2 STEP 2 (the repaired pre-fix run — `FAIL C4 threw: locator.waitFor:
  Timeout 45000ms exceeded … 'order confirmed'`).
- **Issue:** Phase 31 (D-02) added a mandatory pre-submit allergen acknowledgement whose handler
  `if (!acknowledged) { setAckError(true); return }` refuses **before any network call**. The submit
  button deliberately stays enabled, so the click was a silent no-op and C4 waited 45s for an
  "Order confirmed" heading that was never coming.
- **Fix:** tick the Radix `role=checkbox` inside `[data-testid="allergen-ack-row"]`, and **assert**
  it as a new `C4.1` fail arm — so the next such change reads as "the order was never placed"
  instead of "the basket failed to clear".
- **Commit:** `82d2f870`

**Both have one root cause, and it is the same root cause Task 2 STEP 4 exists to remove:** this
script ran in no workflow, gate or npm script. It decayed twice, over months, and nothing told
anyone. That is now fixed — it runs in `e2e-nightly.yml`.

### 3. [Rule 2 - Missing critical] `docs/CHANGELOG.md` entry citing #715

- **Issue:** the plan is silent on the changelog, but `check-changelog-contract.sh` is a
  **merge-time** truth — it inventories feat/fix PRs already merged on `origin/main`. It therefore
  passes on this PR whatever the changelog says, and would **red main** immediately after the squash.
- **Fix:** entry added citing `#715`, then bracketed in both directions (above).
- **Commit:** `9d47bc0e`

### 4. [Scope note] The existing `writes owner: null while anonymous` block was kept, not rewritten

The plan's B-list could be read as replacing it. It was **kept** (CLAUDE.md: when in doubt make the
change additive) and given a comment stating precisely which rule it asserts — *a FRESH basket
written anonymously is stamped null*, not the broader and now-false *an anonymous write always
stamps null*. Block delta is therefore +7, not +6.

---

## Blocked commands, honoured

One `python3` invocation (to strip the workflow step for the arming arm) was **blocked** by
`block-base-python.py`: no conda env is declared for this repo. The precondition was satisfied a
different way — the Edit tool — rather than rerouting around the guard or declaring an env this
task has no business declaring.

---

## Threat register disposition

| Threat ID | Disposition | Outcome |
|---|---|---|
| T-R16-01 | mitigate | **Closed.** Fix 1; guarded by the jsdom `does NOT erase…` arm and browser `C1c.1`, both observed red first. |
| T-R16-02 | mitigate | **Closed.** Fix 2; guarded by `clears every basket when the incoming sub differs…`, observed red first. |
| T-R16-03 | mitigate | **Closed.** D-02's non-null-current-wins branch; guarded by `hands the slot to B…` and `C1c.3` (both regression guards — correct pre-fix, asserted so they stay correct). |
| T-R16-04 | mitigate | **Closed.** Both operands required non-empty; guarded by the no-sub control, which passes on both sides. |
| T-R16-05 | accept | Unchanged. localStorage is owned by its user; server-side pricing/validation is the real control. |
| T-R16-06 | accept (recorded) | **Deferred, see below.** |
| T-R16-SC | N/A | No dependency added or changed. Confirmed: `package.json` / `package-lock.json` untouched. |

**No new threat flags.** Nothing in this change adds a network endpoint, an auth path, a file-access
pattern or a schema change at a trust boundary.

---

## Deferred — recorded, not dropped

- **D-04 — the "this basket belongs to another account" affordance.** Surfacing it needs a
  "rejected because of owner" signal threaded from `parseCart` through the context into at least the
  cart page and the shop page, plus copy, a11y and tests. **Behaviour is UNCHANGED by this plan** —
  a silently empty basket is already what the read boundary does today — so this is not a new
  regression by omission.
- **D-05 — the Playwright `.spec.ts` cart-across-identity test.** Quoting the verify script's own
  header: it needs two real Keycloak registrations in one browser context, "a shape the shared
  Playwright config's per-test isolation actively works against". It would also move
  `playwright_blocks`.
- **T-R16-06 — server-side binding of cart contents to the authenticated subject at checkout.** A
  backend change beyond this quick task. Today a signed-in customer can still submit a cart whose
  items were chosen under no identity; the client-side boundary is the only control.

`.planning/deferred-items.md` **does not exist** (checked: the convention here is per-phase files
under `.planning/phases/*/deferred-items.md`, and this is a quick task), so the plan's conditional
append did not apply. These three are recorded here instead.

---

## Adjacent findings — noted, not fixed

- `frontend/e2e/customer-signout-idp-session.verify.mjs` is unwired by the same measurement that
  found this script unwired. Not touched.
- The `[adjacent]` `post_logout_redirect_uri=http://0.0.0.0:3000/shop` defect recorded in the
  script's own C1 comment **no longer reproduces**: both runs logged
  `URL after sign-out: http://localhost:3000/shop`. The comment is now stale; left in place because
  re-deriving why it stopped is out of scope here.

---

## Verification results

| Check | rc | Notes |
|---|---|---|
| `npx jest` (full) | 0 | 145 suites, **1573 tests**, 0 failed |
| `npm run build` | 0 | the TS gate — jest does not type-check |
| `npm run lint` | 0 | 0 errors, 32 pre-existing warnings; verdict read from **rc**, never eslint's last line |
| `cart-identity-boundary.verify.mjs` | 0 | **18/18 ALL PASS**, against a container rebuilt from this branch |
| `check-runtime-freshness.sh` | 0 | 4/4 FRESH, **0 unverified**; run from the MAIN checkout |
| `check-branch-behind-base.sh` | 0 | 4 ahead, **0 behind** `origin/main` (44bf842e) at PR-open |
| `docs-freshness.sh` | 0 | falsified in both directions |
| `check-doc-metrics.sh` | 0 | 37 claims / 3 docs; falsified in both directions |
| `check-test-count-oracle.sh` | 0 | jest 1573, playwright 127/27, vitest 48/8 — all agree; falsified |
| `check-gate-enforcement.sh` | 0 | measured indifferent in BOTH directions (see above) |
| `check-changelog-contract.sh` | 0 | 21 cited, 0 exempt |
| `check-changelog-cites-pr.sh` | 0 | cites #715; falsified in both directions |
| `check-alert-metrics.sh` | 0 | 19 rules / 25 selectors; **no reseed needed** — C4 placed a real order |

**Metrics:** `jest_blocks` 1566 -> **1573**, `jest_files` **145 (unchanged)**,
`total_logical_invocations` 3555 -> **3562**. Regenerated with `docs-freshness.sh --write`, never by
arithmetic. Cross-checked from the opposite end by the oracle: no repeat of the 1504-vs-1503 counter
defect from plan 35-11.

---

## Commits

| # | Hash | Message |
|---|---|---|
| 1 | `b7253021` | `test(cart)`: R-16 guards, committed **RED** — the anonymous downgrade, stated |
| 2 | `0221babe` | `fix(cart)`: a basket write may ADD or CONFIRM an owner, never ERASE one (R-16) |
| 3 | `82d2f870` | `test(e2e)`: C1c browser arm, two instrument repairs, and CI wiring |
| 4 | `e2593fc6` | `docs`: reconcile test counts to 3562, and contract client-persisted identity lifecycle |
| 5 | `9d47bc0e` | `docs(changelog)`: record the R-16 cart identity fix, citing #715 |

Every message was passed via a quoted heredoc (`git commit -F -`) and read back with
`git log -1 --format=%B`. The PR body went through `--body-file` and was read back from the API:
the three backtick-wrapped phrases that an interpolating string would have executed and silently
dropped (`` `current ?? prior ?? null` ``, `` `sha256:a9a24132` ``, `` `text-slate-500` ``) are all
present in the stored text.

---

## Success criteria

| # | Criterion | Status |
|---|---|---|
| 1 | Stamp survives a signed-out render, jsdom AND real browser, pre-fix red recorded | ✅ |
| 2 | A brand-new customer sees an EMPTY basket and the slot is re-stamped to them | ✅ C1c + C1c.3 |
| 3 | A guest who builds a basket and then registers still keeps it | ✅ C2 + the fresh-guest control |
| 4 | Full jest green, `npm run build` rc=0, eslint rc=0 | ✅ |
| 5 | verify script executes in `e2e-nightly.yml` and fails closed | ✅ no `continue-on-error` |
| 6 | The four doc/branch gates rc=0 | ✅ all falsified too |
| 7 | CLAUDE.md carries the contract, counters corrected | ✅ sixth bullet; no `^Five quality dimensions` / `^The fifth dimension differs` line survives (rc=1, with a positive control proving the anchored pattern shape CAN match this file) |
| 8 | D-04, D-05, T-R16-06 recorded as deferred with reasons | ✅ |

---

## Self-Check: PASSED

All modified files present on disk; all five commit hashes resolve in `git log`; PR #715 open with
the body stored verbatim.

## CI on #715 — settled

Polled to settlement (0 pending). **Every check passes except `review-record`**, which is not
producible by this executor:

```
pass  Run Tests (5m0s)                        pass  Frontend E2E (public surfaces) (4m8s)
pass  Lint (1m23s)                            pass  Integration Tests (Testcontainers RLS)
pass  MCP Server Tests                        pass  OpenAPI Breaking-Change Gate (1m28s)
pass  Operational Contracts (38s)             pass  docs-freshness (25s)
pass  Security Scan (1m0s)                    pass  gitleaks / GitGuardian / pii-guard
pass  K8s Kustomize Secret Guard              pass  Branch Not Behind Base
pass  verdict
skip  Trivy · Build and Push Images · Deploy to Staging · Deploy to Production

fail  review-record — "no review record for head 9d47bc0e — /code-review --comment,
                       a Review-Record: comment, or a waive"
```

Note that `gh pr checks` returns **rc=1** here because of that one failing gate; rc=1 does not by
itself distinguish "a check failed" from "the checks are unreachable", so the table above is the
instrument, not the rc.

## Handoff — one item NOT done, deliberately

**The PR is open and NOT merged.** Two reasons, both requiring a decision above this task:

1. The `review-record` check is failing — `no review record for head 9d47bc0e — /code-review
   --comment, a Review-Record: comment, or a waive`. That gate is satisfied by a review pass, not by
   this executor.
2. `.planning` artifacts (this SUMMARY and the PLAN) are **uncommitted by instruction** — the
   orchestrator owns the final docs commit. Squash-merging now would strand them.

The plan's Task 3 STEP 4 asks for a squash-merge (a rebase-merge strips the `(#PR)` suffix and voids
the changelog gate's `--first-parent` range). That remains the correct merge mode when the two items
above are resolved.
