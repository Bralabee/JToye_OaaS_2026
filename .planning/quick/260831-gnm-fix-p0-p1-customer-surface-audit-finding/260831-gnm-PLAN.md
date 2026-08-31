---
phase: quick-260831-gnm
plan: 01
type: execute
wave: 1
depends_on: []
autonomous: true
requirements: [R-01, R-02, R-03, R-04, R-07, R-09]
branch: feature/customer-surface-fixes
files_modified:
  - frontend/app/shop/shop-discovery-client.tsx
  - frontend/app/shop/__tests__/shop-discovery-client.test.tsx
  - frontend/app/api/vendor-auth/logout-url/route.ts
  - frontend/app/api/vendor-auth/__tests__/logout-url.test.ts
  - frontend/lib/vendor-logout.ts
  - frontend/lib/__tests__/vendor-logout.test.ts
  - frontend/components/dashboard/sidebar.tsx
  - frontend/components/dashboard/mobile-tab-bar.tsx
  - frontend/lib/customer-auth.ts
  - frontend/lib/customer-idp-logout.ts
  - frontend/lib/__tests__/customer-auth-signout-clears-carts.test.ts
  - frontend/components/storefront/storefront-nav.tsx
  - frontend/components/public/public-header.tsx
  - frontend/lib/gsap-gate.ts
  - frontend/lib/__tests__/gsap-gate.test.ts
  - frontend/components/marketing/hero-scene.tsx
  - frontend/components/public/cookie-notice.tsx
  - frontend/components/public/__tests__/cookie-notice.test.tsx
  - frontend/hooks/use-bottom-chrome-height.ts
  - frontend/hooks/__tests__/use-bottom-chrome-height.test.tsx
  - frontend/app/shop/[slug]/shop-detail-client.tsx
  - docs/metrics.json
  - CLAUDE.md
  - AGENTS.md
  - README.md

must_haves:
  truths:
    - "A vendor who clicks Sign Out and then clicks 'Sign in with Keycloak' is CHALLENGED for credentials — the Keycloak SSO session is gone, not just the app cookie."
    - "The browser-facing vendor end_session URL names the PUBLIC issuer host, never the container-internal one."
    - "Clearing the /shop search box (typing-clear, the X, or 'Browse all kitchens') leaves it cleared — the SSR seed never reappears ~400ms later."
    - "A slow keystroke response can never overwrite a newer result set or its count."
    - "A customer sign-out whose server round-trip never settles still clears the marker, the identity and every stored basket's items, and still navigates away."
    - "All three customer sign-out affordances show a busy/disabled state while a sign-out is in flight."
    - "A GSAP bundle that hydrates late never hides already-painted landing content; the no-JS and reduced-motion paths are unchanged."
    - "The cookie notice never intercepts a click on interactive chrome, and its own 'Got it' control is always clickable — including on a mobile storefront with a non-empty basket."
  artifacts:
    - path: "frontend/app/api/vendor-auth/logout-url/route.ts"
      provides: "Vendor Keycloak end-session URL built server-side from the NextAuth id_token"
      exports: ["GET"]
    - path: "frontend/lib/vendor-logout.ts"
      provides: "vendorLogout() — bounded logout-url fetch, next-auth signOut(redirect:false), front-channel end_session navigation"
      exports: ["vendorLogout", "VENDOR_LOGOUT_TIMEOUT_MS"]
    - path: "frontend/hooks/use-bottom-chrome-height.ts"
      provides: "Publishes a mounted bottom-fixed bar's height as --jt-bottom-chrome so the cookie notice can sit above it"
      exports: ["useBottomChromeHeight", "BOTTOM_CHROME_VAR"]
    - path: "frontend/lib/gsap-gate.ts"
      provides: "entranceIsSafe() — the late-hydration predicate that stops the entrance blanking painted content"
      exports: ["entranceIsSafe", "ENTRANCE_BUDGET_MS"]
    - path: "frontend/lib/__tests__/vendor-logout.test.ts"
      provides: "Proof the vendor sign-out fetches the end-session URL, clears the app session, and navigates to the IdP"
    - path: "frontend/hooks/__tests__/use-bottom-chrome-height.test.tsx"
      provides: "Proof the custom property is published on mount and CLEARED on unmount"
  key_links:
    - from: "frontend/components/dashboard/sidebar.tsx"
      to: "frontend/lib/vendor-logout.ts"
      via: "onClick={() => vendorLogout()}"
      pattern: "vendorLogout\\("
    - from: "frontend/components/dashboard/mobile-tab-bar.tsx"
      to: "frontend/lib/vendor-logout.ts"
      via: "onClick={() => vendorLogout()}"
      pattern: "vendorLogout\\("
    - from: "frontend/lib/vendor-logout.ts"
      to: "frontend/app/api/vendor-auth/logout-url/route.ts"
      via: "fetch('/api/vendor-auth/logout-url?redirect=…')"
      pattern: "vendor-auth/logout-url"
    - from: "frontend/components/public/cookie-notice.tsx"
      to: "frontend/hooks/use-bottom-chrome-height.ts"
      via: "consumes var(--jt-bottom-chrome) as its bottom offset"
      pattern: "jt-bottom-chrome"
    - from: "frontend/components/marketing/hero-scene.tsx"
      to: "frontend/lib/gsap-gate.ts"
      via: "entranceIsSafe(performance.now()) guards the two autoAlpha:0 entrance blocks"
      pattern: "entranceIsSafe"
---

<objective>
Close the six P0/P1/P2 findings from the 2026-08-31 five-lane customer-surface audit that
are in scope: vendor federated logout (R-01, **P0**), the search-reverts seed resurrection
and the stale-response race (R-02/R-09), the retroactive hero blanking (R-03), the
fail-open customer sign-out teardown (R-04), and the systemic cookie-notice overlay
(R-07).

Purpose: R-01 is a live account-takeover on a shared device — one click after "Sign Out"
re-enters the dashboard as the departed user. R-04 is the same class one step milder (a
stalled request leaves the previous customer's basket and session intact). R-07 was found
independently by four of five audit lanes and makes the vendor Sign Out button — the very
control R-01 fixes — physically unclickable on the dashboard.

Output: 21 frontend source/test files plus the single `docs/metrics.json` regeneration and
its prose reconciliation.

**Scope is closed.** The audit's other findings (R-05/R-06/R-11 Keycloak theme + SMTP,
R-08 `size-adjust` CLS, and every P2 not listed above) are explicitly OUT and must not
appear in any commit here.

**Division of verification labour.** Every `<verify>` block below is Jest / ESLint /
`npm run build` level and runs without a stack. The browser-level proof — rebuilt Compose
images, the real Keycloak cookie-jar probe for R-01, `elementFromPoint` for R-07, the
throttled-profile hero capture for R-03 — is the ORCHESTRATOR's, after this plan executes.
Do not claim a browser-level truth from a unit test.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
@.claude/skills/proof-standards/SKILL.md
</execution_context>

<context>
@CLAUDE.md

Source files, all already read during planning — the contracts you need are inlined below,
so do NOT go exploring the codebase to rediscover them.

@frontend/app/shop/shop-discovery-client.tsx
@frontend/lib/customer-auth.ts
@frontend/lib/customer-idp-logout.ts
@frontend/app/api/customer-auth/logout-url/route.ts
@frontend/components/marketing/hero-scene.tsx
@frontend/components/public/cookie-notice.tsx

<interfaces>
<!-- Extracted from the tree at plan time. Use these directly. -->

`frontend/auth.ts` — the vendor NextAuth config. **No change is needed here.**
`callbacks.jwt` already stores `token.idToken = account.id_token` (auth.ts:77) and
`refreshAccessToken` preserves it (auth.ts:40); `lib/session-callback.ts` already copies it
onto the session (`s.idToken = token.idToken`). The id_token is therefore available to a
server route via `await auth()` **today**.

`frontend/lib/public-origin.ts`:
```ts
export function resolvePublicOrigin(req?: { nextUrl: URL } | null): string | null
```
Returns `APP_PUBLIC_ORIGIN` -> `NEXTAUTH_URL` -> request origin, each narrowed to an
http(s) origin with wildcard bind addresses (`0.0.0.0`, `::`) rejected. **`null` is a real
answer** — callers degrade to a relative path or omit `post_logout_redirect_uri` entirely.

`frontend/lib/cart-identity.ts` / `frontend/lib/customer-auth.ts`:
```ts
export function clearStoredCarts(): void          // removes every `jtoye-cart-*`
function clearSignedOutState() { clearMarker(); clearStoredCarts() }   // module-private
export async function customerLogout()            // lines 399-434, the defect
```

`frontend/lib/gsap-gate.ts` (PURE — no `"use client"`, no gsap import; keep it that way):
```ts
export const DESKTOP_MOTION_QUERY = "(min-width: 768px) and (prefers-reduced-motion: no-preference)"
export function prefersDesktopMotion(opts: {width: number; reducedMotion: boolean}): boolean
export function canEnhance(): boolean
export function splitWords(el: HTMLElement): HTMLSpanElement[]
```

`frontend/jest.setup.js` already mocks, globally:
- `next-auth/react` — `signOut` is a `jest.fn()` you can assert on directly.
- `next/navigation` — `useSearchParams` is `jest.fn(() => ({ get: jest.fn(), … }))`, so a
  test can `(useSearchParams as jest.Mock).mockImplementation(...)`.
- `framer-motion` — `m.*` are passthroughs that strip motion props.
- `ResizeObserver` — a no-op class stub.

Route-handler test pattern in this repo (`app/api/customer-orders/__tests__/route.test.ts`):
`/** @jest-environment node */`, import the handler, build `new NextRequest(url, {headers})`.

Bottom-fixed chrome that shares the cookie notice's corner:
- `frontend/app/shop/[slug]/shop-detail-client.tsx:818` — `FloatingCartBar`, a local
  (non-exported) component: `className="fixed bottom-0 left-0 right-0 z-50 p-3 sm:p-4 pb-[max(0.75rem,env(safe-area-inset-bottom))] …"`
- `frontend/components/dashboard/mobile-tab-bar.tsx:80` —
  `"fixed inset-x-0 bottom-0 z-50 flex h-14 … md:hidden …"`
</interfaces>

<environment_constraints>
Three traps measured during planning. Each will silently produce a wrong build if ignored.

1. **`NEXT_PUBLIC_KEYCLOAK_URL` has NO `ARG`/`ENV` line in `frontend/Dockerfile`, and that
   is deliberate** (Dockerfile:104-108 forbids "tidying" it in). It stays runtime-resolvable
   **server-side**. So a SERVER route handler may read it; a CLIENT component may not —
   Next inlines `process.env.NEXT_PUBLIC_*` at build time and would bake `""`.
   This is exactly why the vendor end-session URL must be built in a route handler,
   mirroring `app/api/customer-auth/logout-url/route.ts`.

2. **Container-hostname split horizon.** `KEYCLOAK_ISSUER = http://localhost:8085/realms/jtoye-dev`
   (public, what the BROWSER uses) and `KEYCLOAK_ISSUER_INTERNAL = http://keycloak:8080/realms/jtoye-dev`
   (pod-reachable, server-to-server only). The end-session URL is navigated to by the
   BROWSER, so it must use `NEXT_PUBLIC_KEYCLOAK_URL || KEYCLOAK_ISSUER`.
   `KEYCLOAK_ISSUER_INTERNAL` in that URL produces a host no browser can resolve.

3. **Do NOT put the vendor route under `app/api/auth/`.** `auth.ts` sets
   `basePath: "/api/auth"` and the catch-all lives at `app/api/auth/[...nextauth]/route.ts`.
   Use `app/api/vendor-auth/logout-url/route.ts`, mirroring the customer naming.
</environment_constraints>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: /shop search — stop the SSR seed resurrecting a cleared query, and stop a stale keystroke response winning</name>
  <files>
frontend/app/shop/shop-discovery-client.tsx
frontend/app/shop/__tests__/shop-discovery-client.test.tsx
  </files>

  <behavior>
Two new Jest arms, both written and observed FAILING before the source change.

R-02 — "a cleared query stays cleared once the island owns the URL":
  - Mount `<ShopDiscoveryClient initial={page([NEAR])} initialQuery="jollof" initialInterpretation={TEXT} />`
    with `(useSearchParams as jest.Mock).mockImplementation(() => new URLSearchParams(window.location.search))`
    and `window.history.replaceState(null, "", "/shop?q=jollof")` seeded first.
    Reading the mock from `window.location.search` is what makes the harness faithful:
    the component's own debounce writes the URL through `history.replaceState`, and jsdom
    updates `window.location.search` for it, so the mock observes exactly what a real
    `useSearchParams` observes.
  - Type "grill" into the search input, advance fake timers past 400ms, `rerender()`.
    The URL is now `?q=grill`.
  - Clear the input to "", advance fake timers past 400ms, `rerender()`.
    The URL now has no `q`.
  - ASSERT the input's value is still `""`.
    Pre-fix this reads `"jollof"` — the immutable SSR seed, resurrected.

R-09 — "a stale response never overwrites a newer result set":
  - Make `mockGet` hand out a manually-resolvable deferred per call.
  - Fire search A (1 result, totalElements 1) then search B (2 results, totalElements 2).
  - Resolve B first, then resolve A.
  - ASSERT the grid shows B's two kitchens and the count reads 2.
    Pre-fix A's late settle overwrites both and the page asserts a false count.

Both arms are ABSENCE-adjacent, so pair each with the positive control already required by
this file's header: the R-02 arm must first assert the seed IS adopted on a genuine
`?q=` change (URL -> state still works), and the R-09 arm must assert the ordinary
single-request path still renders its result.
  </behavior>

  <action>
**R-02 — `urlQuery` (line 209).** Replace the standing fallback
`searchParams.get("q") ?? initialQuery`.

The seed is a FIRST-RENDER default, not a standing fallback. `initialQuery` is immutable,
so `?? initialQuery` re-supplies it every time the debounce (lines 362-378) deletes `q` —
and the URL->state effect (lines 352-357) then writes it back over the input ~400ms later.
The first clear on a deep link is safe only by accident (the `[urlQuery]` dependency does
not change), which is why this reads as intermittent.

Introduce a ref that records when this island has taken ownership of the URL, and read it
where `urlQuery` is derived: once the island has itself written the URL, an ABSENT `q` is a
statement ("the customer cleared it"), never a gap to fill from the seed. Set the ref to
true inside the debounce effect on the line immediately before `window.history.replaceState`
— so the write always precedes the render that reads it, and there is no stale-read window.

Do NOT touch `appliedUrlQuery`: it is the anti-ping-pong guard and still does its job.
Do NOT convert the ref to state; a re-render is already caused by the router notification.

Add a comment above the new derivation naming the defect (R-02), the mechanism (immutable
seed as standing fallback), and the affordances it broke — including the "Browse all
kitchens" escape hatch at line ~604, whose own comment promises "Never a dead end".

**R-09 — `fetchShops` (lines 255-318).** Add a monotonic generation ref. Increment it at
the top of `fetchShops` and capture the value locally; then, in ALL THREE settle paths —
the success block, the `catch` block, and the `finally` — return early when the captured
generation is no longer the current one. The `finally` guard matters as much as the others:
a stale settle that clears `loading` would strip the spinner off a request still in flight.

A generation counter rather than an AbortController on axios, deliberately: an axios
cancellation surfaces in `catch` as an error that `isRateLimitError` / `describeLoadError`
would misclassify as a genuine load failure and render the A11Y-8 error panel over a
perfectly good newer result. The counter has no such failure mode. Record that reasoning in
the comment so it is not "simplified" later.
  </action>

  <verify>
    <automated>cd frontend &amp;&amp; npx jest app/shop/__tests__/shop-discovery-client.test.tsx --ci --watchAll=false</automated>
    <fail-direction>
Mandatory, per proof-standards §1 — a criterion observed only passing may be incapable of
failing. Run BEFORE the source edit, with only the test file written:

  cd frontend &amp;&amp; git stash push -- app/shop/shop-discovery-client.tsx
  npx jest app/shop/__tests__/shop-discovery-client.test.tsx --ci --watchAll=false; rc=$?
  # EXPECT rc=1 with the R-02 arm reporting  expected "" / received "jollof"
  # and the R-09 arm reporting the count as 1 where 2 was expected.
  git stash pop

Then verify the restore BY CONTENT, never by `git diff --stat` (empty both when a file is
restored and when it was never written):

  grep -c "clientOwnsUrl\|entranceIsSafe" app/shop/shop-discovery-client.tsx   # not applicable pre-edit
  git status --porcelain app/shop/shop-discovery-client.tsx                    # expect clean

Record BOTH directions' real output in the SUMMARY. If an arm cannot be made to fail, say
so explicitly and replace it with a stronger form — never report the vacuous pass.
    </fail-direction>
  </verify>

  <done>
`?q=` clearing is sticky on every affordance; a stale keystroke settle changes nothing; both
new arms were observed red against the pre-fix component and green against the fixed one,
with the real output recorded. Committed atomically on `feature/customer-surface-fixes`.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Sign-out that actually ends the session — vendor federated logout (R-01, P0) and a customer teardown that cannot be gated by a stalled request (R-04)</name>
  <files>
frontend/app/api/vendor-auth/logout-url/route.ts
frontend/app/api/vendor-auth/__tests__/logout-url.test.ts
frontend/lib/vendor-logout.ts
frontend/lib/__tests__/vendor-logout.test.ts
frontend/components/dashboard/sidebar.tsx
frontend/components/dashboard/mobile-tab-bar.tsx
frontend/lib/customer-auth.ts
frontend/lib/customer-idp-logout.ts
frontend/lib/__tests__/customer-auth-signout-clears-carts.test.ts
frontend/components/storefront/storefront-nav.tsx
frontend/components/public/public-header.tsx
  </files>

  <behavior>
**R-01 route** (`app/api/vendor-auth/__tests__/logout-url.test.ts`, `@jest-environment node`,
mirroring `app/api/customer-auth/__tests__/logout-url-origin.test.ts`):
  - with a session carrying `idToken: "ID"` -> the returned URL's host is the PUBLIC issuer
    host and `id_token_hint=ID` is present.
  - **the container-hostname arm, with its fail direction run**: with
    `KEYCLOAK_ISSUER_INTERNAL=http://keycloak:8080/realms/jtoye-dev` also set, the returned
    URL must still name `localhost:8085`. Prove the assertion can fail by temporarily
    sourcing the base from the internal issuer and watching it red on `keycloak:8080`.
  - `?redirect=//evil.example` and `?redirect=/\evil.example` -> `post_logout_redirect_uri`
    is the sanitised same-origin `/auth/signin`, never the attacker host.
  - `resolvePublicOrigin` returning null (unset `APP_PUBLIC_ORIGIN`/`NEXTAUTH_URL`, request
    origin `http://0.0.0.0:3000`) -> the URL carries `id_token_hint` and NO
    `post_logout_redirect_uri`. Losing the return journey is cosmetic; losing the sign-out
    is the security defect.
  - no session / no `idToken` -> `{url}` is the sanitised relative or absolute app path with
    no `id_token_hint` and no Keycloak host at all.

**R-01 client** (`lib/__tests__/vendor-logout.test.ts`):
  - happy path: fetch resolves `{url: "http://localhost:8085/…/logout?id_token_hint=ID"}` ->
    `signOut` (the `next-auth/react` mock) is called with `{redirect: false}`, and
    `vendorLogout()` RESOLVES TO that URL.
  - logout-url fetch rejects -> `signOut` is still called and the resolved value is
    `"/auth/signin"`. A broken IdP lookup must never leave a vendor signed in.
  - logout-url fetch never settles -> under fake timers, advancing past
    `VENDOR_LOGOUT_TIMEOUT_MS` still resolves to `"/auth/signin"` and still called `signOut`.

**R-04** (`lib/__tests__/customer-auth-signout-clears-carts.test.ts`, new arm — the one the
audit specifically calls for):
  - `global.fetch = jest.fn(() => new Promise(() => {}))` — never settles.
  - `jest.useFakeTimers()`; start `customerLogout()`; `await jest.advanceTimersByTimeAsync(LOGOUT_FETCH_TIMEOUT_MS + 1)`; await the call.
  - ASSERT the marker and `CUSTOMER_ID_KEY` are gone and that each seeded basket holds NO
    ITEMS. **Assert on items, never on key presence** — a re-created EMPTY `jtoye-cart-<slug>`
    key is legitimate after a correct sign-out (`use-stored-state.ts`'s write effect), and a
    key-presence gate would red a correct build.
  - Against the PRE-FIX code this arm does not merely assert wrong — `customerLogout()`
    never resolves and the test times out. A jest timeout IS the fail direction; record it.
  - The three existing arms in this file must remain green and unedited: anonymous
    carry-forward, explicit-signout clears all, different-owner rejection (`cart-identity.ts`
    header contract).
  </behavior>

  <action>
Commit this task as TWO atomic commits — R-01 then R-04 — on `feature/customer-surface-fixes`.

── R-01, the P0 ────────────────────────────────────────────────────────────────
Today `sidebar.tsx:138` and `mobile-tab-bar.tsx:164` both call bare
`signOut({callbackUrl:"/auth/signin"})`. That clears the NextAuth cookie and nothing else:
all six Keycloak IdP cookies survive, and one click on "Sign in with Keycloak" silently
re-enters the dashboard as the departed user. No vendor federated-logout route exists.

**1. `frontend/app/api/vendor-auth/logout-url/route.ts` (new).** Mirror
`app/api/customer-auth/logout-url/route.ts` structurally, differing only where the vendor
realm differs:
  - `export const dynamic = "force-dynamic"`; `export async function GET(req: NextRequest)`.
  - `const session = await auth()` from `@/auth`; the id token is `session?.idToken`
    (already placed there by `buildSession`; auth.ts needs no edit).
  - KC base: `process.env.NEXT_PUBLIC_KEYCLOAK_URL || process.env.KEYCLOAK_ISSUER`.
    **Never `KEYCLOAK_ISSUER_INTERNAL`** — see environment_constraints trap 2. Write the
    reason into the file header the way the customer route does, because the customer
    route's header documents the exact opposite conclusion for its own server-side sibling
    and a reader will otherwise "fix" this one into the internal host.
  - Copy `sanitizeRedirect` verbatim in behaviour (reject non-`/` starts, `//host`,
    `/\host`), default `"/auth/signin"` instead of `"/shop"`.
  - `const origin = resolvePublicOrigin(req)`; build `post_logout_redirect_uri` only when
    origin is non-null, and OMIT the parameter entirely when it is null — the customer
    route's measured finding applies unchanged: an unregistered redirect uri errors WITHOUT
    terminating the session, whereas `logout?id_token_hint=…` alone terminates it.
  - No id token -> `NextResponse.json({ url: postLogoutRedirectUri ?? redirect })`.

**2. `frontend/lib/vendor-logout.ts` (new).** Exports `VENDOR_LOGOUT_TIMEOUT_MS` (3000) and
`vendorLogout()`, an async function taking no arguments and resolving to the `string` URL it
navigated to.

Ordering is load-bearing and mirrors the customer path: fetch the end-session URL FIRST
(while the session still exists, so the route can read the id_token), THEN
`await signOut({ redirect: false })` from `next-auth/react` — `redirect:false` because the
navigation we want is to Keycloak, not to `/auth/signin`, and letting NextAuth navigate
would abandon the IdP half exactly as today — THEN assign `window.location.href`.

Bound the fetch with `fetchWithTimeout`, **exported from `lib/customer-auth.ts`** and
imported here. ONE implementation, both call sites — it is specified at R-04 step 4 below,
so if you are taking R-01 first, write it there first and import it. No new module and no
change to `files_modified`: `lib/customer-auth.ts` is already listed. Wrap the whole body so the
`signOut` + navigate pair runs in a `finally` — a failed URL lookup must still sign the
vendor out locally and still land them on `/auth/signin`.

`vendorLogout` RETURNS the URL it navigated to. That is not decoration: jsdom refuses to
navigate, reports it through the virtual console, and leaves `location.href` unchanged (see
the existing `customer-auth-signout-clears-carts.test.ts` header), so the return value is
the unit tests' only honest handle on the navigation. Do not add a test-only injected
navigator parameter.

**3. Wire it.** `sidebar.tsx:138` and `mobile-tab-bar.tsx:164` -> `onClick={() => vendorLogout()}`.
Remove the now-unused `signOut` import from both if nothing else uses it. Leave the button
markup, icons and labels untouched.

── R-04 ────────────────────────────────────────────────────────────────────────
`customerLogout()` (customer-auth.ts:399-434) gates `clearSignedOutState()` — the SOLE
caller of `clearStoredCarts` — behind two un-timeouted `await fetch` calls, with no
`finally`. A stalled request is therefore a silent no-op sign-out: session alive, basket
intact, still stamped with the departing customer's `sub`.

**4. A bounded fetch helper.** Not `AbortSignal.timeout` alone: its internal timer is NOT
driven by jest fake timers, so the never-settling arm above would hang rather than assert.
Use a race that an `AbortController` backs, so a real request is genuinely cancelled AND the
timeout is deterministic under fake timers even when a mock ignores the signal:

  - create an `AbortController`
  - `setTimeout` (a plain one — this is what fake timers control) that calls `ctl.abort()`
    and rejects a timeout promise
  - `await Promise.race([fetch(input, {...init, signal: ctl.signal}), timeoutPromise])`
  - `clearTimeout` in a `finally`

Export it from `lib/customer-auth.ts` as **`fetchWithTimeout`**, alongside
`LOGOUT_FETCH_TIMEOUT_MS = 3000`. This is the SINGLE implementation referred to by R-01
step 2: `lib/vendor-logout.ts` imports this same symbol rather than growing a second copy.
Comment the fake-timer reasoning inline — the obvious "simplification" to
`AbortSignal.timeout` would silently re-break the never-settling test.

**5. Restructure `customerLogout`** so the local teardown and the navigation are in a
`finally`, not in the success path and a duplicated catch. `clearSignedOutState()` and the
`window.location.href` assignment run exactly once, whatever the two fetches did. Both
fetches use the bounded helper. Keep the inner try/catch around the logout-url fetch that
degrades `logoutUrl` to `/shop`.

**6. `customer-idp-logout.ts:84`** — the server-side Keycloak `fetch` is unbounded, and the
module's own header documents the connect-hang trap it is exposed to. Add
`signal: AbortSignal.timeout(3000)` (correct here: server runtime, no fake timers in its
test path). The existing `catch` already returns `"failed"`, so an abort degrades exactly as
the module's best-effort contract requires; add a line to the docblock saying so.

**7. Pending state on all three customer sign-out affordances** — `storefront-nav.tsx:155-161`,
`public-header.tsx:158-167`, `public-header.tsx:246-253`. Each gets a local
`const [signingOut, setSigningOut] = useState(false)`, an async handler that sets it,
`await customerLogout()`, and clears it in a `finally`; the button gets `disabled={signingOut}`
and `aria-busy={signingOut}`. Preserve every existing accessible name (`title="Sign out"`,
the `sr-only` span, the visible "Sign out" label in the sheet) and the `SheetClose asChild`
wrapper on the third. Do not introduce a spinner icon — `aria-busy` plus the disabled state
is the contract; visual treatment is the orchestrator's browser pass to judge.
  </action>

  <verify>
    <automated>cd frontend &amp;&amp; npx jest lib/__tests__/vendor-logout.test.ts lib/__tests__/customer-auth-signout-clears-carts.test.ts app/api/vendor-auth/__tests__/logout-url.test.ts --ci --watchAll=false</automated>
    <automated>cd frontend &amp;&amp; npx jest components/storefront components/public app/api/customer-auth --ci --watchAll=false</automated>
    <automated>cd frontend &amp;&amp; npm run lint</automated>
    <fail-direction>
Three arms, each run BEFORE its fix, output recorded in both directions.

(a) R-04 never-settling arm against the pre-fix `customer-auth.ts`:
    git stash push -- lib/customer-auth.ts
    npx jest lib/__tests__/customer-auth-signout-clears-carts.test.ts --ci --watchAll=false; rc=$?
    # EXPECT rc=1 — a jest TIMEOUT on the new arm (customerLogout never resolves).
    git stash pop
    git status --porcelain lib/customer-auth.ts    # expect clean — verify the restore BY CONTENT

(b) R-01 container-hostname arm: temporarily source the route's KC base from
    KEYCLOAK_ISSUER_INTERNAL, re-run the route test, and record it failing on
    `keycloak:8080`. Restore, re-run, record it passing on `localhost:8085`.
    Without this arm the assertion may be incapable of naming the wrong host.

(c) R-01 open-redirect arm: temporarily return the raw `?redirect=` unsanitised and confirm
    the `//evil.example` case reds. Restore and confirm green.

**Commit before running any break arm** so the restore target is a committed state —
`git checkout` restores from the INDEX and would silently discard post-stage edits.
Run clean -> arms -> clean again; the closing clean assertion is the only proof the restore
happened.
    </fail-direction>
    <human-check>
NOT satisfiable here and must not be claimed from Jest: that a real vendor sign-out leaves
zero Keycloak SSO cookies and that the next "Sign in with Keycloak" is CHALLENGED. That is
the orchestrator's rebuilt-stack cookie-jar probe. Note it as owed in the SUMMARY.
    </human-check>
  </verify>

  <done>
A vendor sign-out ends the Keycloak session via a browser-resolvable end_session URL built
server-side from the id_token; a customer sign-out's local teardown and navigation are
unconditional and bounded at 3s on both the client fetches and the server-side IdP call; all
three customer affordances report busy. Fail directions (a), (b), (c) all executed with real
output recorded. Two atomic commits.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 3: Chrome that stops fighting the page — no retroactive hero blanking (R-03), no cookie notice over interactive controls (R-07), then the single metrics regeneration</name>
  <files>
frontend/lib/gsap-gate.ts
frontend/lib/__tests__/gsap-gate.test.ts
frontend/components/marketing/hero-scene.tsx
frontend/hooks/use-bottom-chrome-height.ts
frontend/hooks/__tests__/use-bottom-chrome-height.test.tsx
frontend/components/public/cookie-notice.tsx
frontend/components/public/__tests__/cookie-notice.test.tsx
frontend/components/dashboard/mobile-tab-bar.tsx
frontend/app/shop/[slug]/shop-detail-client.tsx
docs/metrics.json
CLAUDE.md
AGENTS.md
README.md
  </files>

  <behavior>
R-03 (`lib/__tests__/gsap-gate.test.ts`, added to the existing suite):
  - `entranceIsSafe(0) === true`, `entranceIsSafe(ENTRANCE_BUDGET_MS) === true`,
    `entranceIsSafe(ENTRANCE_BUDGET_MS + 1) === false`.
  - Boundary arms both sides, so an inverted or off-by-one predicate reds.
  - Be honest about reach: this proves the PREDICATE, not the rendering. The claim "a late
    bundle never blanks painted content" is a browser-level truth and belongs to the
    orchestrator's throttled-profile pass. Label it that way in the SUMMARY rather than
    letting a green unit test imply it.

R-07 (`hooks/__tests__/use-bottom-chrome-height.test.tsx`):
  - **The harness must reproduce the AnimatePresence integration shape, not a mount/unmount
    pair.** A co-located mount-then-unmount test passes against the broken
    `useEffect(…, [])` implementation exactly as readily as against the correct one, and so
    proves nothing about the defect. Drive a component whose ref-bearing child is rendered
    CONDITIONALLY on a prop, and assert this four-step SEQUENCE across re-renders of ONE
    mounted component:
      1. first render with the child absent (`ref.current === null`) -> property ABSENT;
      2. RE-RENDER with the child present -> property PUBLISHED;
      3. RE-RENDER with the child absent again -> property CLEARED;
      4. unmount -> property still absent.
    Step 2 is the arm that fails against a `[]`-dependency implementation. Assert it after a
    RE-RENDER, never after a fresh mount, or the arm silently reverts to the vacuous shape
    and the blocker this spec exists to close ships green.
  - jsdom reports `offsetHeight` as 0 for every element, so the test MUST stub it (define
    `offsetHeight` on the element or on `HTMLElement.prototype` for the arms that expect a
    publish). Without the stub the "height 0 -> removeProperty" rule makes step 2
    indistinguishable from step 1 and the whole suite goes green against a hook that never
    publishes anything. State this in the test header; the real pixel value remains the
    orchestrator's browser check.
  - the clear and unmount arms are load-bearing too: a property left behind would push the
    notice permanently off the bottom of a page that has no bottom bar at all.

R-07 (`components/public/__tests__/cookie-notice.test.tsx`, added to the existing suite):
  - the outer positioning wrapper carries `pointer-events-none` and the inner card carries
    `pointer-events-auto` — structural, so pair each with a fail arm (delete the class,
    watch it red) rather than trusting a class-name grep that has only been seen passing.
  - the "Got it" button is still reachable by role and still writes the acknowledgement
    (the existing arms cover this; do not weaken them).
  - the legal copy still asserts "cookies and browser storage", "strictly necessary", and
    that there is nothing to accept or reject. The compaction must not become a copy
    regression.
  </behavior>

  <action>
── R-03 ────────────────────────────────────────────────────────────────────────
`hero-scene.tsx:45-57` sets `gsap.set(words,{yPercent:115,autoAlpha:0})` and animates in.
On a throttled load the bundle hydrates ~2.5s after first paint, so that `set` RETROACTIVELY
BLANKS an h1 and persona CTAs the user has already been reading — measured ~800ms of blank.
The file's "No-FOUC contract" guards against JS never arriving; it does not guard against JS
arriving LATE, which is the opposite failure and needs the opposite defence.

**1. `lib/gsap-gate.ts`** — add `ENTRANCE_BUDGET_MS` (1200) and `entranceIsSafe`, a pure
predicate taking `elapsedMs: number` and returning `boolean`. Keep the module PURE (no
`"use client"`, no gsap import; that purity is why it is jsdom-testable and why it stays out
of the GSAP route chunk).

Docblock: an entrance is an ENTRANCE, not a reveal. Past the budget the content is already
painted and hiding it to animate it in is a regression, not a flourish.

**2. `hero-scene.tsx`** — inside the `mm.add(DESKTOP_MOTION_QUERY, …)` branch compute
`const animateEntrance = entranceIsSafe(typeof performance !== "undefined" ? performance.now() : 0)`.
`performance.now()` is ms since navigation start, which is exactly the clock this question
needs. Guard ONLY the two entrance blocks — the headline `set`+`to` pair and the persona
doors `set`+`to` pair. Everything else (heat-wash parallax, how-title, step rail, step
deal-ins, chips, `fonts.ready` refresh) is scroll-triggered and cannot blank a first paint;
leave it alone.

Preserve, without exception:
  - `splitWords(headline)` runs UNCONDITIONALLY. The `.gsap-word` spans are an E2E signal
    and skipping the wrap would break assertions that have nothing to do with this fix; the
    spans are visually inert without the tweens.
  - `data-motion-active="desktop"` and `data-motion-decided="scene"|"static"` set exactly
    where they are today. These are the deterministic anchors absence assertions hang on.
  - the no-JS path (nothing runs) and the reduced-motion / mobile path (query does not
    match). Both are currently CORRECT — do not regress them to fix this.

Add a NEW inert marker so the decision is observable to the orchestrator's browser pass:
`data-entrance="played"|"skipped"` on the scope, set in-branch beside the existing markers.
Inert means inert: no stylesheet rule, no logic reads it.

**Guard, stated rather than left to omission** (same convention as the R-07 z-ranking and
zero-CLS goods, which are preserved by statement): `app/page.tsx` stays a Server Component
and is NOT edited, `hero-scene.tsx` keeps its `"use client"` directive, and `lib/gsap-gate.ts`
stays directive-free and pure. The CSP nonce cascade from `middleware.ts` through the
`force-dynamic` root layout is therefore untouched by this change. Adding `"use client"` to
`gsap-gate.ts`, or hoisting any of this into `page.tsx`, would move which bundle carries the
scene and is out of scope.

── R-07 ────────────────────────────────────────────────────────────────────────
The notice is `fixed inset-x-0 bottom-0 z-40` (cookie-notice.tsx:70-74) and four of five
audit lanes found it independently. Five measured symptoms, and the fix must close all five:
(1) it covers the vendor sidebar's bottom-rail Sign Out on the dashboard — `elementFromPoint`
returns the notice; (2) on a mobile storefront with a non-empty basket the z-50 FloatingCartBar
paints over "Got it", so the notice is permanently un-dismissable and the acknowledgement is
never written; (3) it covers the mobile "Browse all kitchens" zero-result escape hatch;
(4) it hides ~80% of the landing "Order food near you" CTA at 390x844; (5) its own copy
truncates in the mobile band.

The file's existing z-ranking decision (lines 53-64) is CORRECT and stays: the notice must
never occlude the cart bar or the tab bar. What it did not reason about is the reverse
direction — those bars occluding the notice's own dismiss control — and symptom (2) is
exactly that. z-index cannot fix it, because either ordering breaks one of the two. The
notice has to stop sharing the band.

**3. `frontend/hooks/use-bottom-chrome-height.ts` (new).** Exports the constant
`BOTTOM_CHROME_VAR`, whose value is the custom-property name `--jt-bottom-chrome`, and the
hook `useBottomChromeHeight`, taking a `React.RefObject<HTMLElement | null>` and returning
`void`.

**THE MECHANISM IS LOAD-BEARING, AND A DEPENDENCY-ARRAY EFFECT SHIPS THE BUG SILENTLY.**
Verified in the tree during planning: `FloatingCartBar` is rendered UNCONDITIONALLY at
`shop-detail-client.tsx:800`, so it mounts with the page and never unmounts. The element
that actually carries `fixed bottom-0 … z-50` is the `m.div` at `:813-818`, INSIDE
`<AnimatePresence>` and gated on `itemCount > 0`. The two lifecycles are decoupled. A
literal `useEffect(() => {…}, [])` therefore fires exactly once, at `FloatingCartBar`'s
mount, when the basket is empty and `ref.current` is `null` — it publishes nothing and never
runs again, so `--jt-bottom-chrome` is never set and symptom (2) ships broken behind a green
Jest run. Do not write it that way.

Required shape:
  - `useLayoutEffect` with **NO dependency array**, so it re-runs after every render.
    `useCart()` supplies `itemCount`, so a basket going non-empty RE-RENDERS
    `FloatingCartBar` (it does not remount it), and that render is the only signal available.
  - Re-read `ref.current` FRESH on every run. Never close over a value captured at mount.
  - Measure `ref.current?.offsetHeight ?? 0`. When the height is `0` — ref detached, or the
    element present but `display:none` — call `removeProperty`. Otherwise `setProperty` to
    that height in `px`. One rule covering null-ref, hidden, and unmounted alike.
  - `removeProperty` in the cleanup too, so a final unmount cannot strand a value.
  - Make it isomorphic: `useLayoutEffect` warns during SSR and both callers are
    `"use client"` components that Next still server-renders. Select `useLayoutEffect` in
    the browser and `useEffect` on the server behind a `typeof window !== "undefined"` check.
  - Add a `window` `resize` listener that re-measures. Not decoration: `mobile-tab-bar` is
    `md:hidden` rather than conditionally rendered, so its ref is ALWAYS attached and its
    height is 0 only because of the breakpoint. Crossing `md` causes no re-render, so without
    a resize re-measure a session that loads at ≥md and then narrows keeps a stale `0px` and
    the notice sits under the tab bar — the same silent staleness this design exists to avoid.

The bars PUBLISH and the notice CONSUMES — deliberately this way round rather than the
notice measuring the DOM, because the render that changes a bar's presence is exactly the
render the publisher already observes, so no `MutationObserver` and no polling are needed and
there is no tuned offset constant that can drift. That staleness objection is the one the
existing comment raises against offsets, and it is answered here rather than ignored.

**4. Publish from both bars.** `mobile-tab-bar.tsx:80` and the `FloatingCartBar` at
`app/shop/[slug]/shop-detail-client.tsx:805-818` each get a `ref` and a
`useBottomChromeHeight(ref)` call. `FloatingCartBar` is a local, non-exported component —
leave it that way; the hook is what carries the unit-testable contract.

**5. `cookie-notice.tsx`** — restructure `NOTICE_CLASS` into a wrapper + card:
  - wrapper: `fixed inset-x-0 z-40 pointer-events-none` with an inline
    `style={{ bottom: `var(${BOTTOM_CHROME_VAR}, 0px)` }}`. `pointer-events-none` is what
    closes symptoms (1), (3) and (4) as a CLASS rather than one at a time: the notice can no
    longer intercept a click on anything it is not itself drawn over.
  - card: `pointer-events-auto`, inset from the edges on mobile (`mx-3 mb-3 rounded-xl`) and
    right-aligned and width-capped on `sm:` and up (`sm:ml-auto sm:mr-4 sm:max-w-md`), so on
    the desktop dashboard it sits nowhere near the bottom-LEFT sidebar rail.
  - the bottom offset closes symptom (2): with a cart bar or tab bar mounted the notice sits
    ABOVE it and "Got it" is always clickable.
  - compact the copy to close symptom (5) — one short sentence that keeps every element of
    the legal intent (cookies AND browser storage; strictly necessary; nothing to accept or
    reject), with `/legal/cookies` carrying the detail as it already does. Keep the `h2`
    heading and its "must not compete with the page's own h1" sizing rationale.
  - keep `z-40`, keep the `pb-[max(0.75rem,env(safe-area-inset-bottom))]` form and its
    stated reason, keep the framer-motion `m.section` fade, and keep the
    `choosableCategories().length > 0 -> <ConsentBanner />` supersession branch.
  - UPDATE the docblock: the zero-CLS mechanism is unchanged (still `fixed`, still out of
    flow, still never server-rendered) and the z-ranking decision is unchanged; what is new
    is the pointer-events split and the published-offset mechanism. Record why an offset is
    acceptable HERE when the same file rejected one before — because this offset is
    published by the bar itself at mount, so it cannot drift.

── Close-out ───────────────────────────────────────────────────────────────────
**6. The single `docs/metrics.json` regeneration.** This plan adds Jest blocks across all
three tasks. Do this ONCE, here, after tasks 1 and 2 are committed — one regeneration per
change-set is this repo's convention and per-task regenerations create pointless conflicts.

  scripts/docs-freshness.sh --write

then reconcile the prose counts in `CLAUDE.md`, `AGENTS.md` and `README.md` to the new
manifest. Both halves of the loop are enforced and both fail the build:
`docs-freshness.sh` (tree -> manifest) and `check-doc-metrics.sh` (prose -> manifest).
`scripts/count-test-blocks.mjs` greps LITERAL `it(` / `test(` — regenerate with `--write`,
never by arithmetic on the previous number. Baseline at plan time: `jest_blocks: 1505`,
`jest_files: 141`, `total_logical_invocations: 3494`. No Playwright spec is touched by this
plan, so `playwright_blocks` / `playwright_specs` must be UNCHANGED — if they move,
something outside scope was edited.
  </action>

  <verify>
    <automated>cd frontend &amp;&amp; npx jest lib/__tests__/gsap-gate.test.ts hooks/__tests__/use-bottom-chrome-height.test.tsx components/public/__tests__/cookie-notice.test.tsx --ci --watchAll=false</automated>
    <automated>cd frontend &amp;&amp; npx jest --ci --watchAll=false</automated>
    <automated>cd frontend &amp;&amp; npm run lint</automated>
    <automated>cd frontend &amp;&amp; rm -rf .next &amp;&amp; npm run build</automated>
    <automated>scripts/docs-freshness.sh; rc=$?; echo "docs-freshness rc=$rc"</automated>
    <automated>scripts/check-doc-metrics.sh; rc=$?; echo "check-doc-metrics rc=$rc"</automated>
    <fail-direction>
(a) `npm run build` is the ONLY frontend type-check — jest does not type-check. Prove the
    build can fail before trusting its green: introduce a deliberate `TS2322` (e.g. assign a
    string to `ENTRANCE_BUDGET_MS`), confirm `rc=1` with "Failed to type check", revert BY
    CONTENT, confirm `rc=0` from a cleared `.next`. A cached/UP-TO-DATE build reporting
    success while executing nothing is a known vacuous shape here.

(b) `check-doc-metrics.sh` fail arm: hand-edit one prose count in README.md by one, confirm
    it exits non-zero naming the file and key, restore, confirm it exits 0. rc=2 is VOID
    (missing jq / missing manifest), NOT a pass — read the rc, do not infer it.

(c) R-07 pointer-events arms: delete `pointer-events-none` from the wrapper, confirm the new
    assertion reds; restore. Same for `pointer-events-auto` on the card. A class-name
    assertion that has only been seen passing may be matching something else entirely.

(d) R-07 unmount arm: make the hook's cleanup a no-op, confirm the "removes the property on
    unmount" arm reds; restore.

(e) R-03 boundary arms: invert `entranceIsSafe`'s comparison, confirm both boundary arms
    red; restore.

Commit before running arms. Run clean -> arms -> clean again and verify each restore BY
CONTENT (a unique token or `git status --porcelain` on the named path), never by
`git diff --stat`, which is empty both when a file is restored and when it was never written.
    </fail-direction>
    <human-check>
Explicitly NOT provable from this task and owed to the orchestrator's rebuilt-stack pass:
  - R-03: the h1 and persona CTAs stay visible throughout a throttled (4x CPU / Slow 4G)
    landing load, with `data-entrance="skipped"` observed on the late-hydration run. A
    screenshot cannot verify motion; capture the timeline, not a still.
  - R-07 symptoms (1)-(4): `elementFromPoint` over the vendor sidebar Sign Out at desktop,
    over "Got it" at 390x844 with a non-empty basket, over "Browse all kitchens" on a
    zero-result /shop, and over the landing CTA — each must return the intended control and
    not the notice. Then TAP "Got it" for real and confirm the acknowledgement is written;
    a Playwright `click()` passes where a human tap fails, which is why the suites never
    caught this.
  - R-07 symptom (5): the compacted copy does not truncate at 390px.
    </human-check>
  </verify>

  <done>
The entrance never hides painted content and the no-JS / reduced-motion paths are untouched;
the cookie notice cannot intercept a click outside its own card and its dismiss control is
always reachable; the bars publish their height and clear it on unmount; the full Jest suite,
ESLint and a cleared-`.next` `npm run build` are green; `docs/metrics.json` regenerated once
with the prose in CLAUDE.md / AGENTS.md / README.md reconciled and both gates rc=0;
Playwright counters unchanged. Fail arms (a)-(e) all executed with real output recorded.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| browser -> `/api/vendor-auth/logout-url` | attacker-controllable `?redirect=` crosses here |
| frontend server -> Keycloak (jtoye-dev realm) | end-session URL is composed here and handed to the browser |
| browser -> `/api/customer-auth/logout*` | a hostile or merely dead network can stall the response |
| any page -> `document.documentElement` style | `--jt-bottom-chrome` is written by mounted chrome |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-QF-01 | Tampering | `app/api/vendor-auth/logout-url/route.ts` `?redirect=` | mitigate | `sanitizeRedirect` copied in behaviour from the customer route: reject anything not starting with a single `/`, reject `//host` and `/\host`; default `/auth/signin`. Tested with both hostile forms AND a fail arm proving the assertion can name the attacker host. |
| T-QF-02 | Information disclosure | `id_token_hint` in a browser-navigable URL | accept | OIDC RP-initiated logout puts `id_token_hint` in the front channel by specification, and the vendor session ALREADY carries `idToken` to the browser via `buildSession` (auth.ts / session-callback.ts). This route adds no new exposure. The access and refresh tokens are not placed in the URL. |
| T-QF-03 | Spoofing | vendor SSO session surviving sign-out (**the R-01 P0**) | mitigate | Front-channel `end_session` with `id_token_hint`, reached from both dashboard affordances, ordered so the URL is fetched while the session still exists. Residual: front-channel only — if the browser abandons the navigation the IdP session survives. A back-channel revoke (the customer path's `endCustomerIdpSession`) is the strictly stronger form and is NOT in scope here; record it as a follow-up rather than implying it was done. |
| T-QF-04 | Denial of service | stalled `/api/customer-auth/*` gating the teardown (**R-04**) | mitigate | 3s `AbortController`-backed race on both client fetches and `AbortSignal.timeout(3000)` on the server-side Keycloak call; teardown and navigation moved into `finally` so neither is reachable-only-on-success. |
| T-QF-05 | Elevation of privilege | a previous customer's basket adopted by the next device user | mitigate | `clearStoredCarts` now runs unconditionally. The `cart-identity.ts` contract is preserved intact: anonymous carry-forward stays, explicit sign-out clears all, different-owner rejection stays. Asserted on ITEMS, never on key presence — an empty re-created key is legitimate. |
| T-QF-06 | Tampering | `--jt-bottom-chrome` left set after a bar unmounts | mitigate | The hook removes the property in its cleanup, with a dedicated unmount arm proven able to fail. A stale value would push the notice off-screen — a silent regression a mount-only test cannot see. |
| T-QF-07 | Denial of service | cookie notice occluding interactive controls (**R-07**) | mitigate | `pointer-events-none` wrapper + `pointer-events-auto` card removes interception as a class; the published offset keeps the dismiss control clear of bottom-fixed chrome. Browser-level `elementFromPoint` proof is the orchestrator's. |
| T-QF-SC | Tampering | npm/pip/cargo installs | n/a | **This plan installs no packages.** Every mechanism used (`AbortController`, `performance.now`, CSS custom properties, existing `gsap`/`framer-motion`/`next-auth`) is already in the tree, so the package-legitimacy gate has nothing to act on. If an install becomes necessary, STOP and route it through the legitimacy protocol with a blocking human checkpoint. |
</threat_model>

<verification>
Plan-level, after all three tasks:

1. `cd frontend && npx jest --ci --watchAll=false` — full suite green, and the coverage floor
   in `jest.config.js` (63/55/60/64) still met. If a floor goes red the answer is a test,
   never a smaller number — that rule is written into the config itself.
2. `cd frontend && npm run lint` — ESLint 9 flat config, `eslint .`. Read the VERDICT line,
   not the last line: eslint's final line is the FIXABLE count, not the result.
3. `cd frontend && rm -rf .next && npm run build` — the only frontend type-check, run from a
   cleared `.next` so "success" is not a cached no-op.
4. `scripts/docs-freshness.sh` and `scripts/check-doc-metrics.sh` — both rc=0. rc=2 is VOID.
5. `git log HEAD..origin/main` empty (or a merge from main recorded) before any PR — a branch
   behind its base ships a runtime missing already-merged work that no rebuild can fix.
6. Every fail-direction arm in every task executed, with REAL output for both directions
   recorded in the SUMMARY. Any criterion that could not be made to fail is labelled
   **unverified** and replaced with a stronger form — never reported as a satisfied pass.

Owed to the orchestrator and explicitly NOT claimable from anything above: rebuild ALL
Compose images (`docker compose start` does not rebuild), confirm runtime parity by content
and identity (`.Metadata.LastTagTime` per service vs the newest commit touching its build
paths, plus the running container's image ID vs the tag's), then run the browser probes
listed in Task 2's and Task 3's `<human-check>` blocks.
</verification>

<success_criteria>
- A vendor sign-out ends the Keycloak SSO session; the next "Sign in with Keycloak" is
  challenged for credentials. (R-01, P0)
- The vendor end-session URL names the public issuer host; `keycloak:8080` appears nowhere in
  a browser-navigable URL, proven with a fail arm.
- `?redirect=//evil.example` cannot escape the origin.
- Clearing the /shop search leaves it cleared on every affordance, including "Browse all
  kitchens". (R-02)
- A late keystroke response cannot overwrite a newer result set or its count. (R-09)
- A customer sign-out whose round-trip never settles still clears marker, identity and every
  basket's items, and still navigates. All three affordances report busy. (R-04)
- A late-hydrating GSAP bundle never hides painted landing content; the no-JS and
  reduced-motion paths are byte-for-byte unchanged in behaviour. (R-03)
- The cookie notice intercepts no click outside its own card, its "Got it" is always
  clickable including over a non-empty mobile basket, and its copy does not truncate at
  390px. (R-07)
- Nothing outside the six in-scope findings was changed: no Keycloak realm config, no
  `size-adjust` work, no unlisted P2.
- `docs/metrics.json` regenerated ONCE with prose reconciled; both doc gates rc=0;
  Playwright counters unchanged.
- Every gate in this plan was observed FAILING against a deliberately broken input before it
  was trusted, with both directions' real output recorded.
</success_criteria>

<output>
Create `.planning/quick/260831-gnm-fix-p0-p1-customer-surface-audit-finding/260831-gnm-SUMMARY.md`
when done. The SUMMARY must carry, for every gate in this plan, BOTH directions' real output
— and must name any criterion that could not be made to fail, rather than reporting its
vacuous pass. Commits on `feature/customer-surface-fixes`, no Co-Authored-By trailers.
</output>
