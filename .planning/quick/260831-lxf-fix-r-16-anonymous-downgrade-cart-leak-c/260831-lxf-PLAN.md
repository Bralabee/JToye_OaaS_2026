---
phase: quick/260831-lxf-cart-identity-downgrade
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - frontend/lib/cart-identity.ts
  - frontend/components/storefront/cart-provider.tsx
  - frontend/lib/customer-auth.ts
  - frontend/components/storefront/__tests__/cart-provider-identity.test.tsx
  - frontend/lib/__tests__/customer-auth-signout-clears-carts.test.ts
  - frontend/e2e/cart-identity-boundary.verify.mjs
  - .github/workflows/e2e-nightly.yml
  - docs/metrics.json
  - README.md
  - AGENTS.md
  - CLAUDE.md
autonomous: true
requirements: [R-16]
user_setup: []

must_haves:
  truths:
    - "After a SIGNED-OUT render of a shop page, a basket stamped owner=A still reads owner=A on disk — the stamp is not erased."
    - "A guest basket (owner null from the start) is still adopted by the customer who signs in or registers mid-shop."
    - "A signed-in write by B over a slot previously owned by A stamps B, so there is no reverse leak of B's items to A."
    - "Signing in as a customer DIFFERENT from the one already recorded clears every stored basket."
    - "A session response carrying no `sub` does NOT clear baskets (unknown identity is never treated as a different person)."
    - "Every new regression guard was observed FAILING on the pre-fix tree before the fix landed."
    - "cart-identity-boundary.verify.mjs runs in an executable CI path (e2e-nightly.yml), not manual-only."
  artifacts:
    - path: "frontend/lib/cart-identity.ts"
      provides: "resolveCartOwner — the pure add/confirm-never-erase ownership rule"
      contains: "resolveCartOwner"
    - path: "frontend/components/storefront/cart-provider.tsx"
      provides: "serialize reads the prior owner before stamping"
      contains: "resolveCartOwner"
    - path: "frontend/lib/customer-auth.ts"
      provides: "setMarker account-switch backstop"
      contains: "clearStoredCarts"
    - path: "frontend/e2e/cart-identity-boundary.verify.mjs"
      provides: "C1c — the anonymous-downgrade browser arm"
      contains: "C1c"
    - path: ".github/workflows/e2e-nightly.yml"
      provides: "the step that actually runs the verify script"
      contains: "cart-identity-boundary.verify.mjs"
  key_links:
    - from: "frontend/components/storefront/cart-provider.tsx"
      to: "frontend/lib/cart-identity.ts"
      via: "import { resolveCartOwner }"
      pattern: "resolveCartOwner"
    - from: "frontend/lib/customer-auth.ts"
      to: "frontend/lib/cart-identity.ts"
      via: "getCurrentCustomerId() read BEFORE rememberCustomerId() write"
      pattern: "getCurrentCustomerId"
    - from: ".github/workflows/e2e-nightly.yml"
      to: "frontend/e2e/cart-identity-boundary.verify.mjs"
      via: "node --env-file=.env step"
      pattern: "cart-identity-boundary"
---

<objective>
Close R-16, the anonymous-downgrade cart leak: a lapsed session makes one signed-out
shop-page render rewrite customer A's basket as `owner: null`, after which ANY next
sign-in adopts it. A newly registered customer inherits the previous account's basket,
and checkout posts those items against the new session's name/email.

Purpose: a basket must not cross a PERSON boundary. #459 built the identity stamp; this
restores the invariant the stamp depends on — that only an explicit sign-out removes it.
Output: the fix (2 layers), three tiers of regression guard each observed failing first,
CI wiring for the browser arm that today runs nowhere, and a standing CLAUDE.md contract
so the next storage-backed surface gets identity-lifecycle testing by default.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@CLAUDE.md
@.claude/skills/proof-standards/SKILL.md
@frontend/lib/cart-identity.ts
@frontend/components/storefront/cart-provider.tsx
@frontend/lib/customer-auth.ts
@frontend/hooks/use-stored-state.ts
@frontend/components/storefront/__tests__/cart-provider-identity.test.tsx
@frontend/lib/__tests__/customer-auth-signout-clears-carts.test.ts
@frontend/e2e/cart-identity-boundary.verify.mjs
</context>

<mechanism>
Read during planning; re-confirm each line against your tree before depending on it.

1. `cart-provider.tsx:104-109` — `serialize` writes `owner: getCurrentCustomerId()`
   UNCONDITIONALLY. It has no access to, and never consults, the owner already on disk.
2. `use-stored-state.ts:103-116` — the write effect runs on `[key, value, hydratedKey]`.
   On every mount it fires once as soon as `hydratedKey === key`, so a plain page view
   re-persists the basket even when nothing changed.
3. `customer-auth.ts:424-427` — `getCustomerSession()` calls `clearMarker()` on every
   `authenticated: false` probe, and `clearMarker()` (`:113`) calls `forgetCustomerId()`.
   The access token is 300s; the probe runs on mount, on a 1s poll and on focus.
4. Therefore: token lapses -> `jtoye-customer-id` removed -> next shop-page render's write
   effect stamps `owner: null` over A's basket -> `canAdoptCart(null, anyone)` returns true
   (`cart-identity.ts:118`) -> the next sign-in adopts it.
5. `setMarker` (`customer-auth.ts:87-100`) writes the incoming identity and never compares
   it to the outgoing one. `clearStoredCarts` has exactly ONE caller, `clearSignedOutState`,
   reached only from `customerLogout`.

Why the existing checks miss it — all three verified during planning:
- `cart-provider-identity.test.tsx:97-103` asserts the RENDERED items while anonymous and
  never re-reads `storedPayload()?.owner`. The downgrade happens in the same `render()`.
- `cart-identity-boundary.verify.mjs` C1b (`:289-323`) seeds A's payload into a browser
  where B is ALREADY signed in — it skips the anonymous render that does the damage.
- The verify script is referenced by NO workflow, gate or npm script. Measured:
  `rg -uu -n "cart-identity-boundary" --glob '!*.mjs' .` returns only prose in
  `docs/CHANGELOG.md` and a comment in the jest file. It is manual-only.
</mechanism>

<locked_decisions>
D-01 **Fix 1 is the cure, Fix 2 is a backstop.** State it in the SUMMARY: on the reported
repro Fix 2 alone is VACUOUS, because the downgrade nulls `previous` before the sign-in
ever reads it. Do not present Fix 2 as sufficient.

D-02 **The ownership rule is `current ?? prior ?? null`.** A non-null current identity
always wins (add / confirm / transfer). A null current preserves whatever is on disk.
This deliberately keeps guest -> registration carry-over working (prior null, current X
-> X) while killing A -> B laundering, and — the branch that is easy to get wrong —
lets B take ownership of a slot A used to own, so B's later items cannot leak back to A.

D-03 **Least-invasive placement: the provider reads, `cart-identity.ts` decides.** Do NOT
change `useStoredState`'s `serialize` signature. Only `cart-provider.tsx` uses that hook
(measured: `rg -uu -l useStoredState` -> the hook, its own test, the provider), but the
hook is the generic one and this rule is cart-specific. Put the pure decision in
`cart-identity.ts` where it is directly unit-testable.

D-04 **The "belongs to another account" affordance is DEFERRED, not dropped.** Surfacing it
needs a new "rejected because of owner" signal threaded from `parseCart` through the
context into at least the cart page and the shop page, plus copy, a11y and tests — beyond
this quick task. Behaviour is UNCHANGED by this plan (a silently empty basket is already
what the read boundary does today), so this is not a new regression. Record it in the
SUMMARY's Deferred section and append to `.planning/deferred-items.md` if that file exists.

D-05 **The Playwright `.spec.ts` cart-across-identity test is DEFERRED.** Reason quoted from
the verify script's own header (`:6-9`): it needs TWO real Keycloak registrations in ONE
browser context, "a shape the shared Playwright config's per-test isolation actively works
against". Adding it would also move `playwright_blocks`. Record it; do not attempt it here.
</locked_decisions>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Stop the erase (Fix 1 + Fix 2), with every guard observed red first</name>
  <files>
    frontend/components/storefront/__tests__/cart-provider-identity.test.tsx,
    frontend/lib/__tests__/customer-auth-signout-clears-carts.test.ts,
    frontend/lib/cart-identity.ts,
    frontend/components/storefront/cart-provider.tsx,
    frontend/lib/customer-auth.ts
  </files>

  <behavior>
  Guards FIRST, committed while red, then the fix. The natural pre-fix red IS the
  fail direction (proof-standards §1) and it lands on a committed state (§8), so no
  separate break arm is owed for these — say that explicitly in the SUMMARY rather
  than implying a break arm was run.

  A. `cart-provider-identity.test.tsx` — ONE added assertion, ZERO new blocks:
     in the existing `it("still shows an owned basket while nobody is signed in")`
     (`:97-103`), after the rendered-items assertion add
     `expect(storedPayload()?.owner).toBe(A)`.
     Pre-fix this reads `null`. This single line is the defect, stated.

  B. `cart-provider-identity.test.tsx` — NEW blocks that separate the two cases the
     existing `it("writes owner: null while anonymous...")` (`:130`) conflates:
       - "stamps a FRESH guest basket null (nothing to preserve)": empty storage,
         `signedInAs(null)`, addItem -> `storedPayload()?.owner` is `null`. ALLOWED.
         PASSES pre-fix — it is the control that stops Fix 1 being over-applied.
       - "does NOT erase an existing owner when the writer is anonymous":
         `seed(A, [...])`, `signedInAs(null)`, addItem -> owner is still `A`, and the
         item added while anonymous IS in `items`. RED pre-fix.
       - "hands the slot to B when B writes over a basket A owned": `seed(A, [...])`,
         `signedInAs(B)`, render -> rendered items empty AND `storedPayload()?.owner`
         is `B` (never `A` — a preserved-A stamp here would leak B's later items back
         to A). Assert the D-02 transfer direction explicitly.
       - "adopts a legacy owner-less payload for the signed-in customer":
         `seed(undefined, [...])`, `signedInAs(A)` -> owner becomes `A`. Guards the
         `undefined -> null` normalisation.

  C. `customer-auth-signout-clears-carts.test.ts` — a new
     `describe("signing in as a DIFFERENT customer")` (same file: this is one
     ownership-lifecycle story and splitting it is how the distinction gets collapsed):
       - "clears every basket when the incoming sub differs from the recorded one":
         `seedSignedIn()` (records `sub-a`) + `seedBaskets()`, drive a
         `getCustomerSession()` whose profile sub is `sub-b` -> `basketItems(SLUG)` and
         `basketItems(OTHER)` are empty, `CUSTOMER_ID_KEY` is `sub-b`. RED pre-fix.
       - "does NOT clear when the SAME customer's session is renewed": sub `sub-a` ->
         baskets intact. Control.
       - "does NOT clear when the session carries NO sub": profile without `sub` ->
         baskets intact, and `CUSTOMER_ID_KEY` unchanged. This is the fail-destructive
         hazard: an unknown identity must never be read as a different person.
       - Assert on `basketItems()`, never on key presence — see that file's own note
         at `:37-45`; a re-created EMPTY key is legitimate.
  </behavior>

  <action>
  STEP 1 — branch. `git fetch origin` then create `feature/r16-cart-identity-downgrade`
  from the THEN-CURRENT `origin/main` (PR #713, Keycloak, may have merged since planning).
  Confirm `git log HEAD..origin/main` is empty before doing anything else. Do not add
  Co-Authored-By trailers to any commit.

  STEP 2 — write the guards in A, B and C above. Run them. RECORD THE RED VERBATIM in the
  SUMMARY: which arms failed, with the actual `expected/received` lines. An arm you
  expected to fail that PASSES pre-fix is a finding — stop and re-derive it, do not
  quietly reword it. Commit this red state (`test(cart): ...`).

  STEP 3 — Fix 1, load-bearing. In `frontend/lib/cart-identity.ts` add an exported pure
  function `resolveCartOwner(priorOwner: string | null | undefined, current: string | null):
  string | null` implementing D-02: `if (current) return current; return priorOwner ?? null`.
  Document WHY in the file's existing voice — an anonymous write is a lapsed session far
  more often than a new person, and sign-out is the only unambiguous "a different person
  may be next" moment (the module header already argues this; extend it, do not restate it).

  In `cart-provider.tsx` add a module-level `readStoredOwner(slug)` that reads
  `cartStorageKey(slug)`, JSON-parses, and returns `parsed.owner` — but returns `undefined`
  when the payload's `shopSlug !== slug`, so another shop's payload can never donate its
  owner to this slot. Wrap in try/catch returning `undefined` (private mode / corrupt JSON:
  unknown prior owner degrades to today's behaviour, never to a stricter one that eats a
  basket). Then change `serialize` to
  `owner: resolveCartOwner(readStoredOwner(shopSlug), getCurrentCustomerId())`.
  Do NOT touch `useStoredState` (D-03).

  STEP 4 — Fix 2, backstop. In `customer-auth.ts` `setMarker`, read
  `const previous = getCurrentCustomerId()` BEFORE any write — that call is the only moment
  the outgoing identity is still on disk — and `if (previous && sub && previous !== sub)
  clearStoredCarts()`. BOTH operands must be non-empty: a blank or absent `sub` is "unknown",
  not "different person", and clearing there would empty a live basket every time a session
  response arrived without a profile. `clearStoredCarts` is already imported at `:19`.
  Add `getCurrentCustomerId` to that import. Comment that this covers `handleCallback`
  (`:383`) AND the renewal path (`:432`), and that on the reported repro it is vacuous on
  its own (D-01).

  STEP 5 — re-run the two files green, then the FULL jest suite green. Record the block
  delta (new `it(`/`test(` blocks added) for Task 3.
  </action>

  <verify>
    <automated>out=$( cd frontend && npx jest components/storefront/__tests__/cart-provider-identity.test.tsx lib/__tests__/customer-auth-signout-clears-carts.test.ts 2>&1 ); rc=$?; echo "rc=$rc"; printf '%s\n' "$out" | tail -25</automated>
    <automated>out=$( cd frontend && npx jest 2>&1 ); rc=$?; echo "rc=$rc"; printf '%s\n' "$out" | tail -12</automated>
    <automated>out=$( cd frontend && npm run lint 2>&1 ); rc=$?; echo "rc=$rc"; printf '%s\n' "$out" | tail -8</automated>
    <automated>out=$( cd frontend && npm run build 2>&1 ); rc=$?; echo "rc=$rc"; printf '%s\n' "$out" | tail -8</automated>
  </verify>

  <done>
  Both target files pass rc=0 and the full suite passes rc=0. `npm run build` rc=0 (jest
  does NOT type-check — the build is the TS gate). eslint rc=0, and the verdict is read
  from the rc, never from eslint's last line (which is the FIXABLE count). The SUMMARY
  carries the verbatim pre-fix red for every guard that had to go red, and names the
  control arms that correctly passed pre-fix.
  </done>
</task>

<task type="auto">
  <name>Task 2: The C1c browser arm, its pre-fix red, and CI wiring so it stops running nowhere</name>
  <files>
    frontend/e2e/cart-identity-boundary.verify.mjs,
    .github/workflows/e2e-nightly.yml
  </files>

  <action>
  STEP 1 — add a new self-contained `anonymousDowngradeGuard(browser)` function to
  `cart-identity-boundary.verify.mjs`, its OWN `browser.newContext()`, registered in
  `main()` alongside `crossShopGuard` / `postOrderClear` / `sharedBrowserFlow`. Extend the
  header's criteria list to five and say what C1c covers that C1b cannot: C1b seeds into a
  browser where B is already signed in, so it never performs the anonymous render that
  erases the stamp.

  The arm, in order — each check via the existing `check()` helper:
    - Seed from `${BASE}/shop` (a provider-FREE page: the slug layout mounts CartProvider
      and writes its own payload back within a few hundred ms — the failure the C3 comment
      at `:342-345` already records) with
      `{ shopSlug: SHOP, owner: FAKE_A, items: [seedItem("owned-by-a")] }`, where
      `FAKE_A = "sub-absent-customer-a"`. No Keycloak needed for this half: the downgrade
      is a signed-OUT render, and an arbitrary opaque string is exactly what A's `sub` is.
    - `C1c.0` FAIL ARM: navigate to `${BASE}/shop/${SHOP}` (THE DOWNGRADING RENDER), wait
      ~1500ms for the write effect, then `cartPageState` shows the seeded item. Without
      this, "owner unchanged" below is satisfied by a page that never hydrated.
    - `C1c.1` THE FIX: `storedCart(page, SHOP)` -> `payload.owner === FAKE_A`. Log via
      `describeStored` either way. RED pre-fix (reads `null`).
    - `C1c.2` FAIL ARM: `registerCustomer(page, emailB, "/shop/" + SHOP)` then `session(page)`
      -> `authenticated === true` and a non-empty `sub` that `!== FAKE_A`.
    - `C1c` THE CONSEQUENCE: `cartPageState(page, SHOP)` is empty and contains no
      `owned-by-a` title. RED pre-fix (B inherits A's basket — the reported defect).
    - `C1c.3` REVERSE-LEAK GUARD: `storedCart` owner is now B's real `sub` (D-02: the
      signed-in writer takes the slot). A stamp still reading `FAKE_A` here would mean B's
      subsequent items are stored under A's name.
  Wrap in the same `try/catch/finally { context.close() }` shape as the neighbouring
  functions, pushing a failing result on a throw so an exception can never read as a pass.

  STEP 2 — RUN IT AGAINST THE PRE-FIX RUNTIME FIRST. The currently-running compose frontend
  predates Task 1 by construction, so the fail direction is nearly free — do it BEFORE any
  rebuild. From the repo root:
  `NODE_PATH=frontend/node_modules PLAYWRIGHT_BASE_URL=http://localhost:3000 node --env-file=.env frontend/e2e/cart-identity-boundary.verify.mjs`
  Record C1c.1 and C1c FAILING with their real `[detail]` strings, and C1c.0 / C1c.2
  PASSING (a fail arm that also fails means the instrument is broken, not the product —
  suspect the instrument first). If the stack is not up, start it with
  `scripts/start-dev.sh` and re-run.

  STEP 3 — rebuild and re-run GREEN. `docker compose -f docker-compose.full-stack.yml up -d
  --build frontend`. TRAP: that command re-tags core-java too, so force-recreate and, if
  `scripts/check-alert-metrics.sh` reds afterwards, run `scripts/seed-order-metric.sh` —
  a core-java rebuild always reds it and that is not a finding. Then re-run the verify
  script; expect ALL PASS across C1c/C1/C2/C3/C4.

  STEP 4 — wire it into CI. Add a step to `.github/workflows/e2e-nightly.yml` immediately
  after `- name: Enforce the declared skip budget` (`:344`), at repo-root working directory,
  matching the script's documented invocation at its `:11-13`:

    - name: Gate — cart identity boundary (#459 / R-16)
      env:
        PLAYWRIGHT_BASE_URL: http://localhost:3000
        NODE_PATH: frontend/node_modules
      run: node --env-file=.env frontend/e2e/cart-identity-boundary.verify.mjs

  No `continue-on-error` — that string appears nowhere in the file on purpose (header
  `:32-34`). The script already exits 1 on any failed check and 2 when
  `KC_SEED_USER_PASSWORD` is unset, so it fails closed. Add a comment saying WHY the
  nightly and not the per-PR job: `frontend-e2e` in `ci-cd.yaml` is deliberately stack-free
  and this needs a real Keycloak plus two live registrations. Confirm the default slugs
  exist on that stack — `SHOP=peckham-jollof-co` comes from `DemoDataSeeder`,
  `OTHER_SHOP=mama-ades-kitchen` from `scripts/seed-e2e-fixtures.sh:57` — and set
  `E2E_SHOP_SLUG` explicitly in the step if your run shows otherwise.

  STEP 5 — arm the wiring. Delete the new step, run
  `bash scripts/check-gate-enforcement.sh; echo "rc=$?"`, restore it, run again. EXPECTED
  RESULT: rc is UNCHANGED both times, because that gate inventories `scripts/check-*.sh`
  only (`:84`) and a `frontend/e2e/*.verify.mjs` is outside it. Record that as a measured
  NEGATIVE — the gate needs no exemption-table entry — rather than assuming it. Restore by
  CONTENT (grep the step name back), never by `git diff --stat`. Adjacent finding to note,
  not to fix: `frontend/e2e/customer-signout-idp-session.verify.mjs` is unwired by the same
  measurement.
  </action>

  <verify>
    <automated>out=$( NODE_PATH=frontend/node_modules PLAYWRIGHT_BASE_URL=http://localhost:3000 node --env-file=.env frontend/e2e/cart-identity-boundary.verify.mjs 2>&1 ); rc=$?; echo "rc=$rc"; printf '%s\n' "$out" | tail -30</automated>
    <automated>out=$(rg -uu -n "cart-identity-boundary" .github/workflows/e2e-nightly.yml); rc=$?; echo "rc=$rc"; printf '%s\n' "$out"</automated>
    <automated>out=$(rg -uu -c "continue-on-error" .github/workflows/e2e-nightly.yml); rc=$?; echo "rc=$rc (1 = the forbidden string is present, 0 matches expected)"; printf '%s\n' "$out"</automated>
    <automated>out=$(bash scripts/check-runtime-freshness.sh 2>&1); rc=$?; echo "rc=$rc"; printf '%s\n' "$out" | tail -12</automated>
  </verify>

  <done>
  The verify script prints ALL PASS at rc=0 against a frontend container REBUILT from this
  branch, and the SUMMARY carries the pre-fix FAIL output for C1c.1 and C1c beside it.
  `check-runtime-freshness.sh` rc=0 with 0 unverified — run it from the MAIN checkout, never
  a worktree (the compose project name is derived from the directory and VOIDs elsewhere);
  rc=2 is VOID, never a pass. The new step exists in `e2e-nightly.yml`, and
  `check-gate-enforcement.sh` was shown to be indifferent to it in BOTH directions.
  </done>
</task>

<task type="auto">
  <name>Task 3: Reconcile the test-count docs, institutionalise the contract, ship the PR</name>
  <files>docs/metrics.json, README.md, CLAUDE.md, AGENTS.md</files>

  <action>
  STEP 1 — metrics. Task 1 adds `it(` blocks, so both halves of the docs loop move.
  Run `bash scripts/docs-freshness.sh --write` (never arithmetic — the counter greps
  LITERAL `it(` / `test(`), then reconcile the numbers quoted in prose in README.md,
  CLAUDE.md and AGENTS.md: `jest_blocks`, `jest_files` if a new file appeared (it should
  not — all additions go into two existing files), and `total_logical_invocations`.
  Cross-check with `bash scripts/check-test-count-oracle.sh` — it asserts jest's own
  `numTotalTests` against the same key from the opposite end and both are required; a
  disagreement is a real defect in the counter, not a rounding error (precedent: 1504 vs
  1503 in plan 35-11). Baseline for the diff: `jest_blocks: 1566`, `jest_files: 145`,
  `total_logical_invocations: 3555`.

  STEP 2 — the standing contract. In `CLAUDE.md` § "Cross-Cutting Quality Contracts
  (design-time)" (`:387`), add a SIXTH bullet after the Security bullet (`:396`) and before
  the blank line preceding "Falsifiable evidence + runtime parity" (`:398`). Match the
  existing bullets' density and length; do not restructure the section. Substance:

    **Client-persisted identity lifecycle** — any surface that persists USER-SCOPED state
    client-side (localStorage/sessionStorage/IndexedDB: baskets, drafts, preferences) owns
    its identity-lifecycle TRANSITIONS — sign-in, sign-out, session lapse, account switch,
    new registration — tested THROUGH the transition, never as two steady states either
    side of it. Assert the stored ownership stamp BY CONTENT after the transition, not the
    rendered view: R-16 shipped because a test checked what the page showed while the same
    render silently erased the stamp on disk. Ownership markers may be ADDED or CONFIRMED
    by a write and REMOVED only by an explicit sign-out; a lapsed session is not a new
    person. N/A for state with no user scope (theme, cookie banner) — record it.

  THEN fix the two counters that make this a sixth: `:389` opens "Five quality
  dimensions ... the other three were brought to the same bar ... and the fifth ..." and
  `:391` opens "The fifth dimension differs from the other four". Update both so the prose
  is not self-falsifying. Do not touch the falsifiability bullet's own text.

  STEP 3 — record the deferrals from D-04 (the "belongs to another account" affordance) and
  D-05 (the Playwright cross-identity spec) in the SUMMARY, each with its reason, and append
  to `.planning/deferred-items.md` if that file exists. Regression by omission is a defect:
  neither may be silently dropped.

  STEP 4 — ship. Confirm `git log HEAD..origin/main` is empty (or record a merge from base)
  BEFORE opening the PR — a branch behind its base ships a runtime missing already-merged
  work that no rebuild can fix. Pass the PR body via a quoted heredoc (`<<'EOF'`) or
  `-F <file>`, NEVER an interpolating `-m` string: this body names commands in backticks and
  an interpolating string would execute them and silently drop the text. Read back what was
  actually stored. Reference issue #459 as the origin and R-16 (2026-08-31 customer-surface
  audit) as this finding; put any issue number BEFORE a closing keyword, since the parser is
  lexical and "does not close #N" still closes #N. Squash-merge (a rebase-merge VOIDs the
  changelog gate's `--first-parent` range). No Co-Authored-By trailers.
  </action>

  <verify>
    <automated>out=$(bash scripts/docs-freshness.sh 2>&1); rc=$?; echo "rc=$rc"; printf '%s\n' "$out" | tail -15</automated>
    <automated>out=$(bash scripts/check-doc-metrics.sh 2>&1); rc=$?; echo "rc=$rc"; printf '%s\n' "$out" | tail -15</automated>
    <automated>out=$(bash scripts/check-test-count-oracle.sh 2>&1); rc=$?; echo "rc=$rc"; printf '%s\n' "$out" | tail -10</automated>
    <automated>out=$(bash scripts/check-branch-behind-base.sh 2>&1); rc=$?; echo "rc=$rc"; printf '%s\n' "$out" | tail -8</automated>
    <automated>out=$(rg -uu -c "Client-persisted identity lifecycle" CLAUDE.md); rc=$?; echo "rc=$rc"; printf '%s\n' "$out"</automated>
    <automated>out=$(rg -uu -n "^Five quality dimensions|^The fifth dimension differs" CLAUDE.md); rc=$?; echo "rc=$rc (expect 1 = no stale count sentences remain)"; printf '%s\n' "$out"</automated>
  </verify>

  <done>
  `docs-freshness.sh`, `check-doc-metrics.sh` and `check-test-count-oracle.sh` all rc=0 —
  and the doc-metrics gate was falsified in both directions per its own header recipe
  (`sed` one quoted number, expect rc=1; restore, expect rc=0), because a gate seen only
  passing may be incapable of failing. `check-branch-behind-base.sh` rc=0 (rc=2 is VOID).
  CLAUDE.md carries the sixth bullet AND no surviving "Five quality dimensions" / "the fifth
  dimension differs from the other four" sentence. Deferrals D-04 and D-05 are recorded with
  reasons. PR open, body read back verbatim, CI green.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| browser localStorage -> React render | Client-owned storage read as if it described a person. Attacker-controllable by its owner, so this is an ACCIDENTAL-carry-over boundary on a shared device, not a server authz boundary. |
| Keycloak session probe -> `setMarker` | An identity assertion arriving asynchronously and re-writing the local ownership marker. |
| browser -> `POST /api/v1/orders` (checkout) | Items chosen under one identity submitted with another identity's contact details. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-R16-01 | Information disclosure | `cart-provider.tsx` `serialize` | mitigate | Fix 1 (D-02): an anonymous write preserves the existing owner, so A's item selections are never re-exposed as adoptable. Guarded by the jest arm in Task 1B and browser check C1c.1. |
| T-R16-02 | Spoofing (basket attributed to the wrong person) | `customer-auth.ts` `setMarker` | mitigate | Fix 2: a recorded previous identity that differs from the incoming non-empty `sub` clears every basket before the new marker lands. Guarded by Task 1C. |
| T-R16-03 | Information disclosure (reverse) | ownership transfer on a shared slot | mitigate | D-02 makes a non-null current identity always win, so B's later items cannot be stored under A's stamp. Guarded by Task 1B arm 3 and C1c.3. |
| T-R16-04 | Denial of service (self-inflicted: destroying a live basket) | `setMarker` clear condition | mitigate | The clear requires BOTH `previous` and `sub` non-empty. A session response without a profile is "unknown", never "different person". Guarded by the no-sub control in Task 1C. |
| T-R16-05 | Tampering | any client-crafted cart payload | accept | localStorage is owned by its user; a user forging their own basket harms only themselves. Server-side pricing/validation at checkout is the real control and is unchanged here. |
| T-R16-06 | Repudiation / elevation | checkout posts items with no `sub` binding | accept (out of scope, recorded) | Server-side binding of cart contents to the authenticated subject is a backend change beyond this quick task. Record in the SUMMARY as a follow-up so it is deferred, not dropped. |
| T-R16-SC | Tampering | npm/pip/cargo installs | N/A | No dependency is added or changed by this plan. |
</threat_model>

<verification>
- Fail direction executed and recorded for EVERY new assertion, at both tiers: jest arms
  observed red on a committed pre-fix tree, browser arms C1c.1/C1c observed red against the
  pre-rebuild frontend container. A criterion reported without its fail-direction run is
  labelled unverified, never presented as satisfied.
- Controls that must PASS pre-fix are named too: the fresh-guest-null arm, the same-sub
  renewal arm, the no-sub arm, and browser C1c.0 / C1c.2. A fail arm that also fails means
  the instrument is broken — suspect the instrument first.
- Runtime parity: the browser green is taken against a frontend REBUILT from this branch
  (`up -d --build frontend`, then force-recreate — that command re-tags core-java too),
  proven by `check-runtime-freshness.sh` rc=0 with 0 unverified, run from the main checkout.
- Every rc is captured on the same statement as its command and printed. No `| grep -q`
  under pipefail; `rg -uu` for any count or absence used as evidence.
</verification>

<success_criteria>
1. A basket stamped `owner: A` survives a signed-out shop-page render with its stamp intact
   — proven in jsdom AND in a real browser, both with the pre-fix red recorded.
2. A brand-new customer registering on a device where a previous customer shopped sees an
   EMPTY basket, and the slot is re-stamped to them.
3. A guest who builds a basket and then registers still keeps it (the good this fix must not
   trade away).
4. Full jest suite green, `npm run build` rc=0, eslint rc=0.
5. `cart-identity-boundary.verify.mjs` executes in `e2e-nightly.yml` and fails closed.
6. `docs-freshness.sh`, `check-doc-metrics.sh`, `check-test-count-oracle.sh`,
   `check-branch-behind-base.sh` all rc=0.
7. CLAUDE.md carries the client-persisted identity-lifecycle contract, with the section's
   own "five dimensions" counters corrected.
8. D-04 and D-05 recorded as deferred with reasons; T-R16-06 recorded as a follow-up.
</success_criteria>

<output>
Create `.planning/quick/260831-lxf-fix-r-16-anonymous-downgrade-cart-leak-c/260831-lxf-SUMMARY.md` when done.
</output>
