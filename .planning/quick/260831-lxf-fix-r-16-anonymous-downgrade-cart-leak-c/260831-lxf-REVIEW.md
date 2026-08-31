---
phase: 260831-lxf-fix-r-16-anonymous-downgrade-cart-leak-c
reviewed: 2026-08-31T15:40:00Z
depth: quick
branch: feature/r16-cart-identity-downgrade
diff_base: origin/main
pr: 715
files_reviewed: 12
files_reviewed_list:
  - frontend/components/storefront/cart-provider.tsx
  - frontend/lib/cart-identity.ts
  - frontend/lib/customer-auth.ts
  - frontend/components/storefront/__tests__/cart-provider-identity.test.tsx
  - frontend/lib/__tests__/customer-auth-signout-clears-carts.test.ts
  - frontend/e2e/cart-identity-boundary.verify.mjs
  - .github/workflows/e2e-nightly.yml
  - CLAUDE.md
  - AGENTS.md
  - README.md
  - docs/metrics.json
  - docs/CHANGELOG.md
findings:
  critical: 0
  warning: 10
  info: 3
  total: 13
severity_detail:
  major: 4
  minor: 6
status: issues_found
---

# R-16 (PR #715): Code Review Report

**Reviewed:** 2026-08-31T15:40:00Z
**Depth:** quick (raised to per-file read + call-chain tracing for the three identity modules, as instructed)
**Files Reviewed:** 12
**Status:** issues_found

## Summary

The central invariant holds. I traced the reported repro end to end and could not break it:
`resolveCartOwner` is `current -> prior -> null`, the provider supplies the prior value read
from disk at serialize time, and the OAuth callback route (`/shop/auth/callback`) is a
**static** segment so `CartProvider` is not mounted while `handleCallback` runs — the fresh
registration therefore mounts against a payload still stamped `A` and `canAdoptCart(A, B)`
rejects it. The reverse-leak arm is genuinely closed: a truthy `current` always wins, so B's
items cannot land under A's stamp on any path where B's identity is recorded.

**No Critical finding is proven, and I am not manufacturing one.** What I did find is a set
of ways the fix is *narrower than it reads*, clustered around one theme: R-16 converts the
owner field from **recomputed on every write** to **read back and re-persisted**, and nothing
on the new read path validates it. That change of character is what WR-01 and WR-02 are
about, and it is the class most likely to bite later.

Verification actually executed (not asserted):

- `npx jest cart-provider-identity.test.tsx customer-auth-signout-clears-carts.test.ts` — 22/22 pass.
- `bash scripts/docs-freshness.sh` — `OK: metrics match source (total logical invocations: 3562)`.
- `bash scripts/check-doc-metrics.sh` — `PASS: all 37 prose metric claim(s) across 3 doc(s)`.
- `git log HEAD..origin/main` — empty; the branch is not behind base.
- Counted the e2e script's `check()` calls by hand: **18**, matching the SUMMARY's "18/18".
- Counted the new Jest blocks: 4 + 3 = **7**; `1566 + 7 = 1573`; `1730+1573+84+127+48 = 3562`. Consistent in `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md` and `README.md`.
- `rg -uu` across `.github/`, `scripts/`, `frontend/package.json` for `verify.mjs` references — exactly one, the new one (see WR-06).
- Confirmed `app/shop/[slug]/layout.tsx` is the sole `CartProvider` mount, and `lib/customer-session-store.ts` polls `getCustomerSession()` at 1s/5s + focus + visibilitychange from inside that subtree (this is what WR-03 turns on).

Docs accuracy: the sixth-dimension wording in `CLAUDE.md` is internally consistent (the
ordinals are adoption-chronological, and the second paragraph correctly says "The
falsifiability dimension" rather than "the fifth"); `AGENTS.md` and `README.md` do not carry
that section at all, so there is nothing stale there. The changelog heading cites `(#715)`.
One changelog claim is overstated — WR-08.

## Critical Issues

None proven.

## Warnings

### WR-01: `readStoredOwner` performs no type validation, and R-16 makes a bad `owner` permanent

**Severity:** Major
**File:** `frontend/components/storefront/cart-provider.tsx:93-104` (specifically `:100`), with `frontend/lib/cart-identity.ts:156-162`

**Issue:** `readStoredOwner` returns `parsed.owner` verbatim — any JSON value survives. It
then flows into `resolveCartOwner(prior, current)`, whose `priorOwner ?? null` guards only
`null`/`undefined`, and straight back out through `JSON.stringify`.

Before this change, every write recomputed the stamp from `getCurrentCustomerId()`, so a
corrupt or tampered `owner` was **repaired by the first write**. Now it is read, preserved and
re-persisted on every subsequent anonymous write — indefinitely. Two concrete states:

- `owner: ""`, `owner: 0`, `owner: false` — falsy but not nullish. `canAdoptCart` opens with
  `if (!owner) return true` (`cart-identity.ts:118`), so the basket becomes **adoptable by any
  signed-in customer**, which is precisely the R-16 end state, and it now survives every write
  instead of being overwritten.
- `owner: {}` / `owner: []` — truthy non-string. `owner === current` is never true, so the slot
  becomes permanently unreadable to every signed-in customer while still rendering to anonymous
  ones.

Reaching either requires same-origin write access (XSS, devtools, an extension), so this is
hardening rather than a remotely exploitable bug — but the field *is* the identity boundary,
and the PR is what changed it from derived state into persisted state.

**Fix:**
```ts
function readStoredOwner(slug: string): string | null | undefined {
  if (typeof window === "undefined") return undefined
  try {
    const raw = window.localStorage.getItem(cartStorageKey(slug))
    if (raw === null) return undefined
    const parsed = JSON.parse(raw) as CartState
    if (parsed.shopSlug !== slug) return undefined
    // An owner is a non-empty opaque subject id or it is nothing. Anything else
    // is a payload we did not write: degrade to "no prior owner" (today's
    // behaviour) rather than persisting it forever. An empty string in
    // particular is `!owner`-true in canAdoptCart, i.e. adoptable by anyone.
    return typeof parsed.owner === "string" && parsed.owner.length > 0
      ? parsed.owner
      : undefined
  } catch {
    return undefined
  }
}
```

### WR-02: two silent paths leave a signed-in customer with no recorded identity — and R-16 now stamps the PREVIOUS owner there

**Severity:** Major
**File:** `frontend/lib/customer-auth.ts:399`, `frontend/lib/customer-auth.ts:454`, `frontend/lib/cart-identity.ts:82-90`

**Issue:** The whole fix rests on `getCurrentCustomerId()` returning non-null whenever somebody
is signed in. Two paths break that quietly:

1. `handleCallback` builds `sub: payload.sub ?? ""` (`:399`) and hands it to `setMarker` →
   `rememberCustomerId("")`, which returns early on a falsy sub (`cart-identity.ts:84`). A
   sub-less ID token therefore establishes a **live cookie session with no recorded identity**.
   The account-switch backstop also cannot fire (`previous && sub && …` is false).
2. `rememberCustomerId`'s `catch` swallows a storage write failure (`:87-89`), giving the same
   state.

In both, `getCurrentCustomerId()` is null forever for that session, so every cart write takes
the `prior` branch. **Customer B's items are then stored under customer A's stamp, and A adopts
them on their next sign-in** — the reverse leak the changelog says is impossible. Pre-fix these
same states produced `owner: null`; post-fix they produce a *wrong non-null owner*, which
`canAdoptCart` treats as an authoritative claim. `getCustomerSession` has the same shape at
`:454`, where `if (data.expiresAt)` gates the entire `setMarker` call.

The root cause is that `getCurrentCustomerId()` returns `null` for two different facts —
"nobody is signed in" (preserve the prior owner: correct) and "somebody is signed in but we
did not record who" (preserving is wrong). Keycloak always issues `sub`, so exploitability is
low; the defect is that the fix's correctness silently depends on that.

**Fix:** reject a sub-less token in the callback, the same way a bad nonce is already a hard
reject, so the ambiguous state cannot be created:
```ts
// customer-auth.ts, in handleCallback, beside the nonce check
if (!payload.sub) {
  clearAuthTransients()
  return null   // an id token with no subject cannot establish a cart identity
}
```
and drop the `?? ""` at `:399`. If a softer landing is preferred, have `setMarker` call
`forgetCustomerId()` when the session is authenticated but sub-less, so the slot degrades to
"anonymous" rather than inheriting the previous person's stamp.

### WR-03: `clearStoredCarts()` has no same-document invalidation, so a live CartProvider can resurrect the cleared basket under the new owner

**Severity:** Major
**File:** `frontend/lib/customer-auth.ts:116`, `frontend/lib/cart-identity.ts:171-186`, `frontend/components/storefront/cart-provider.tsx:180-196`

**Issue:** `setMarker` is reached from inside the `[slug]` subtree — `app/shop/[slug]/layout.tsx`
mounts `CartProvider`, and `lib/customer-session-store.ts:164` polls `getCustomerSession()`
every 1s for 5s plus on `focus` and `visibilitychange`; `app/shop/[slug]/checkout/page.tsx`,
`app/shop/[slug]/orders/[orderNumber]/page.tsx` and `components/storefront/require-customer-auth.tsx`
call it too. When the account-switch backstop fires from any of those, `clearStoredCarts()`
removes the key **on disk only**: a same-document `localStorage` write raises no `storage`
event, so the provider's React state still holds the outgoing customer's items. The previous
person's basket stays on screen, and the next `setItems` (any add / remove / quantity change)
re-persists it stamped with the **new** customer's sub — making the leak permanent and
legitimate-looking.

Honest reachability: the realistic cross-tab route self-heals, because the *other* tab's
`rememberCustomerId` write does raise a `storage` event that `cart-provider.tsx:183-193`
reacts to by re-reading and rejecting; and the OAuth callback is a static route with no
provider mounted. So this is a hole in the backstop's self-containment rather than a
demonstrated exploit — but the guarantee currently depends on an unstated cross-tab event
ordering that no test pins.

**Fix:** make the clear observable in its own document, mirroring the existing
`jtoye:cart-updated` broadcast:
```ts
// cart-identity.ts, end of clearStoredCarts()
for (const k of doomed) window.localStorage.removeItem(k)
// Same-document writes raise no `storage` event, so anything holding a basket in
// React state must be told explicitly or it will re-persist it under the NEW owner.
window.dispatchEvent(new CustomEvent("jtoye:carts-cleared"))
```
```tsx
// cart-provider.tsx, alongside the existing storage effect
useEffect(() => {
  const onCleared = () => setItems(EMPTY_ITEMS)
  window.addEventListener("jtoye:carts-cleared", onCleared)
  return () => window.removeEventListener("jtoye:carts-cleared", onCleared)
}, [setItems])
```

### WR-04: the new browser gate only executes when the entire 126-test nightly suite is green

**Severity:** Major
**File:** `.github/workflows/e2e-nightly.yml:362-366`

**Issue:** The step carries no `if:`, so it inherits GitHub's default `success()` and is
**skipped** whenever any earlier step fails — including `Wait for core-java and the frontend`,
the report-verdict step and `Enforce the declared skip budget`. This file's own escalation
comment records 14 consecutive scheduled failures (2026-08-11 → 2026-08-24); across every one
of those nights this gate would have run zero times. The step's justification is "a guard
nothing executes decays without anyone learning it decayed" — wiring it behind the single most
failure-prone step in the repository only partly closes that.

Second half: the workflow triggers are `schedule` + `workflow_dispatch` only, so **the wiring
cannot fire on the PR that introduces it**, and a scheduled run executes the default branch.
The SUMMARY's evidence is a local run; the CI lane itself is unproven at merge time.

Counter-argument, acknowledged: the script registers three real Keycloak users and places a
real order, so hoisting it above the Playwright suite risks perturbing the fixtures the suite
asserts on. That is a genuine tension, not an excuse to leave it undocumented.

**Fix:** either give it its own job (`needs: full-suite` removed, its own stack or a
`if: always()`-guarded run once the stack step succeeded), or move it to immediately after
`Seed E2E fixtures` and give its arms their own shop slug via `E2E_SHOP_SLUG`. At minimum,
run `gh workflow run "E2E Nightly (full stack)" --ref feature/r16-cart-identity-downgrade`
before merge and cite the run URL, and record the ordering trade-off in the step comment:
```yaml
      # ORDERING: deliberately after the suite — this arm registers 3 real Keycloak
      # users and places a real order, which would perturb fixtures the suite asserts
      # on. The cost is that a red suite SKIPS this gate entirely; accepted because
      # <reason>. Revisit if the nightly's failure rate stays non-zero.
```

### WR-05: `readStoredOwner` duplicates `parseCart`'s cross-shop guard, and that copy is covered by no test

**Severity:** Minor
**File:** `frontend/components/storefront/cart-provider.tsx:93-104` vs `:62-72`

**Issue:** The read path and the write path now hold two independent copies of "read the key,
parse it, reject a foreign `shopSlug`". A change to one silently diverges from the other. Worse,
the write-side copy is untested: `cart-provider-identity.test.tsx`'s `seed()` (`:30-36`) always
writes `shopSlug: SLUG`, and `cart-provider.test.tsx:15` seeds without an `owner` at all.
Deleting `if (parsed.shopSlug !== slug) return undefined` from `readStoredOwner` leaves the
entire Jest suite green — a foreign shop's owner would then donate itself to this slot.

**Fix:** extract one helper both call, and add the missing arm:
```ts
function readPayload(slug: string): CartState | undefined {
  if (typeof window === "undefined") return undefined
  try {
    const raw = window.localStorage.getItem(cartStorageKey(slug))
    if (raw === null) return undefined
    const parsed = JSON.parse(raw) as CartState
    return parsed.shopSlug === slug ? parsed : undefined
  } catch { return undefined }
}
```
```tsx
it("does NOT let another shop's payload donate its owner to this slot", () => {
  localStorage.setItem(cartStorageKey(SLUG),
    JSON.stringify({ shopSlug: "some-other-shop", owner: A, items: [] }))
  signedInAs(null)
  renderCart()
  expect(storedPayload()?.owner).toBeNull()   // NOT A
})
```

### WR-06: three sibling `.verify.mjs` guards still run nowhere, and no gate prevents recurrence

**Severity:** Minor
**File:** `frontend/e2e/customer-realm-split.verify.mjs`, `frontend/e2e/customer-signout-idp-session.verify.mjs`, `frontend/e2e/track-operator-persona.verify.mjs`; `scripts/check-gate-enforcement.sh:81-84`

**Issue:** Measured with `rg -uu -n "verify\.mjs" .github/ scripts/ frontend/package.json` —
exactly **one** hit, the line this PR adds. The other three browser guards have zero references
anywhere. `check-gate-enforcement.sh` — the script that exists precisely because six gates were
found running nowhere — enumerates only `find "$REPO_ROOT/scripts" -maxdepth 1 -name 'check-*.sh'`,
so `.verify.mjs` guards are outside it. This PR fixes one instance of the class by hand; the
class stays open and the new wiring can be deleted again with nothing firing. Per this project's
own doctrine, the fix for a recurring failure is a script that fails loudly, not a hand repair.

**Fix:** extend `check-gate-enforcement.sh` to enumerate `frontend/e2e/*.verify.mjs` alongside
`scripts/check-*.sh`, reusing the existing exemption table so any deliberately-manual script is
named with a written justification rather than silently uncovered.

### WR-07: `NODE_PATH` in the new CI step is inert and documents a mechanism that does not exist

**Severity:** Minor
**File:** `.github/workflows/e2e-nightly.yml:365`

**Issue:** `NODE_PATH: frontend/node_modules` has no effect on ESM resolution — `NODE_PATH` is a
CommonJS/`require` mechanism and Node's ESM resolver does not consult it. `import { chromium }
from "@playwright/test"` inside `frontend/e2e/*.mjs` resolves by walking up from the importing
file's own directory, finding `frontend/node_modules` regardless. The step works, but for a
different reason than the config claims, and the variable would not rescue the script if it
were ever moved out of `frontend/`.

**Fix:** drop the `NODE_PATH` line, or replace it with a comment naming the real mechanism
(`resolved by directory walk from frontend/e2e/, so the script must stay under frontend/`).

### WR-08: the changelog's guest carry-forward claim is unqualified and false in the shared-device case it describes

**Severity:** Minor
**File:** `docs/CHANGELOG.md` (Unreleased → R-16 entry, second bullet)

**Issue:** "A guest basket still carries forward into sign-in or registration — the good this fix
deliberately does not trade away." That holds only when the slot has **no prior owner**. After a
lapsed session leaves `owner: A` on disk, a *different* anonymous shopper's own additions are now
stamped `A` (`resolveCartOwner` preserves the prior owner on every anonymous write), so when that
shopper registers, `canAdoptCart(A, C)` rejects and **their own basket is silently discarded, with
no UI signal**. The Jest guard states the qualifier correctly ("stamps a FRESH guest basket null,
because there is nothing to preserve"); the release note drops it. The trade is the right one —
losing C's basket beats leaking A's — but the note claims a behaviour change did not happen when
it did.

**Fix:** qualify the sentence ("a guest basket built in a slot with no prior owner still carries
forward…"), and consider surfacing a "your basket was cleared" toast when an adopt is rejected, so
the loss is explained rather than mysterious.

### WR-09: the repaired item-count locator is page-wide and has a live shape collision

**Severity:** Minor
**File:** `frontend/e2e/cart-identity-boundary.verify.mjs:124-128`

**Issue:** The replacement selects **any** `<p>` whose entire text is `N item(s)`.
`components/storefront/cart-drawer.tsx:71-73` renders exactly that shape
(`<p className="text-xs …">{itemCount} item{…}</p>`). It does not collide today only because
Radix `Sheet` unmounts its content when closed and the drawer is portalled after `{children}` —
two facts the script does not state and no test pins. A `forceMount` or a redesign that leaves
the drawer in the DOM silently redirects `.first()`. The repair traded a colour-utility coupling
for a text-shape coupling; both are incidental.

**Fix:** add `data-testid="cart-item-count"` to the cart page's count paragraph
(`app/shop/[slug]/cart/page.tsx:47`) and select on it. A testid does not move with a contrast
pass, which is the exact failure being repaired.

### WR-10: a fixed 1500 ms sleep stands in for the condition C1c.1 depends on

**Severity:** Minor
**File:** `frontend/e2e/cart-identity-boundary.verify.mjs:405-406`

**Issue:** `await page.waitForTimeout(1500)` after "THE DOWNGRADING RENDER" is a sleep, not a
condition. On a loaded runner the provider's write effect may not have run when it expires, in
which case C1c.1 ("the owner stamp SURVIVES") passes **trivially** — nothing wrote, so nothing
could have downgraded. The arm is saved from vacuity only because `cartPageState` at `:410`
navigates to the cart page and waits up to 8s for the non-empty heading, and *that* page's
provider write runs before the stamp is read at `:418`. So C1c.0 is what makes C1c.1
non-vacuous, by a route the comment does not describe.

**Fix:** replace the sleep with a real condition, e.g.
```js
await page.waitForFunction(
  ([k, slug]) => {
    const raw = window.localStorage.getItem(k)
    if (!raw) return false
    try { return JSON.parse(raw).shopSlug === slug } catch { return false }
  },
  [cartKey(SHOP), SHOP],
  { timeout: 10000 }
)
```
or, at minimum, state in the comment that C1c.0 is the arm that proves a write occurred.

## Info

### IN-01: no minimum-check-count assertion — an empty `results` would print a pass

**File:** `frontend/e2e/cart-identity-boundary.verify.mjs:615-622`
**Issue:** If `results` were ever empty the script prints `0/0 checks passed` and exits **0** —
the "found nothing is never clean" shape this repo's other gates fail closed on
(`check-e2e-skip-budget.sh` exits 2/VOID for exactly this). Every arm currently pushes a failing
result from its `catch`, so an empty `results` is hard to reach today; a future arm that returns
early would reopen it.
**Fix:** `if (results.length < 18) { console.log("VOID: expected >=18 checks, got " + results.length); process.exit(2) }`

### IN-02: the script header's logging claim no longer matches what it logs

**File:** `frontend/e2e/cart-identity-boundary.verify.mjs:54-56`, `:452`, `:459`, `:167-168`
**Issue:** The header states "Only booleans, pass/fail lines and generated test emails (not
secret) are logged", but `describeStored` prints the stored `owner` and the new lines print
`B.sub=…` — Keycloak subject ids (line `:272` already did this for A before this PR, so the claim
was already drifting). These are throwaway users on a realm destroyed with the runner, so the real
risk is nil; the header is simply now inaccurate, and an inaccurate secrets claim is the kind that
gets trusted later.
**Fix:** update the header to name subject ids explicitly, or log only a prefix
(`sub.slice(0, 8)`) — the assertions compare full values in code, not in the log line.

### IN-03: `resolveCartOwner`'s doc states a nullish rule the code does not implement

**File:** `frontend/lib/cart-identity.ts:140` vs `:160-161`
**Issue:** The comment says "Concretely `current ?? prior ?? null`", but the body is
`if (current) return current; return priorOwner ?? null` — truthiness, not nullish coalescing.
The two differ for `current === ""`, which the implementation deliberately treats as "no
identity". `getCurrentCustomerId()` cannot return `""` today (`|| null` at `:68`), so the
behaviours coincide — but the doc states a rule the code does not implement, and the divergence
is precisely the empty-string case WR-01 is about.
**Fix:** say "a *truthy* current always wins; a blank id is not an identity", so the next reader
does not "simplify" the guard into a literal `current ?? prior ?? null` and re-open WR-01.

---

_Reviewed: 2026-08-31T15:40:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: quick_
