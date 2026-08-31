---
phase: 260831-gnm-fix-p0-p1-customer-surface-audit-finding
reviewed: 2026-08-31T12:03:02Z
depth: quick
diff_base: 24e82bfa..HEAD
files_reviewed: 14
files_reviewed_list:
  - frontend/app/api/vendor-auth/logout-url/route.ts
  - frontend/lib/vendor-logout.ts
  - frontend/components/dashboard/sidebar.tsx
  - frontend/components/dashboard/mobile-tab-bar.tsx
  - frontend/lib/customer-auth.ts
  - frontend/lib/customer-idp-logout.ts
  - frontend/app/shop/shop-discovery-client.tsx
  - frontend/app/shop/[slug]/shop-detail-client.tsx
  - frontend/components/marketing/hero-scene.tsx
  - frontend/lib/gsap-gate.ts
  - frontend/components/public/cookie-notice.tsx
  - frontend/components/public/public-header.tsx
  - frontend/components/storefront/storefront-nav.tsx
  - frontend/hooks/use-bottom-chrome-height.ts
findings:
  critical: 2
  warning: 7
  info: 3
  total: 12
status: issues_found
---

# 260831-gnm: Code Review Report

**Reviewed:** 2026-08-31T12:03:02Z
**Depth:** quick (auth route reviewed thoroughly, per instruction)
**Files Reviewed:** 14 source files (+ 7 test files read for coverage-gap analysis)
**Status:** issues_found

## Summary

The security core of R-01 is **sound**. I went looking for the four named hazards
and could not make any of them fire:

- **Public-issuer host.** `keycloakBase()` is `NEXT_PUBLIC_KEYCLOAK_URL ||
  KEYCLOAK_ISSUER`, which mirrors `auth.ts:48` exactly. Verified against the
  live config: `docker-compose.full-stack.yml:479` sets `KEYCLOAK_ISSUER` to the
  **public** `http://localhost:8085/realms/jtoye-dev` and the internal host is a
  separate name (`KEYCLOAK_ISSUER_INTERNAL`, line 480) that this route never
  reads; `k8s/base/frontend-deployment.yaml:218/294` supplies both from
  `app-config`. There is no path by which `keycloak:8080` reaches the returned
  URL. Both candidate names are realm-inclusive, so the `/protocol/...`
  concatenation is correct.
- **Open redirect.** I tried protocol-relative, backslash, percent-encoded
  (`%2F%2F`, `/%2f`), scheme-prefixed, tab/CR/LF-embedded and origin-escape
  candidates against `sanitizeRedirect` + `${origin}${redirect}` and could not
  produce a value whose browser-parsed host is not this app's. The redirect only
  ever concatenates onto an *injected* origin, and it is emitted through
  `URLSearchParams`, so no header/response splitting either. (Weakest-copy
  concern below as WR-05, not an exploitable hole.)
- **id_token handling.** Within the RP-initiated-logout contract:
  `id_token_hint` is spec'd as a query parameter, and this is not a *new*
  exposure — `lib/session-callback.ts:19` already copies `idToken` onto the
  client-readable session. Nothing logs it. (Cache-header gap as WR-04.)
- **Unauthenticated callers.** `auth()` returning null or a session with no
  `idToken` degrades to a same-origin app path with no Keycloak host and no
  hint — asserted by two tests, and I confirmed the branch by reading it.
- **Cart-identity semantics (R-04).** Unchanged and correct.
  `clearSignedOutState()` (= `clearMarker()` + `clearStoredCarts()`) still fires
  only on an *explicit* sign-out; the session-lapsed path in
  `getCustomerSession()` still calls `clearMarker()` alone, so the anonymous
  carry-forward survives, and `canAdoptCart` still rejects a different signed-in
  owner. Moving the teardown into `finally` widens *when* it runs, never *what*
  it clears.

What is **not** sound is the client half of the same P0. `vendorLogout` bounds
its lookup fetch and then hands control to an **unbounded** `signOut()` in the
`finally`, so the one failure mode R-04 exists to close — a request that never
answers — strands the vendor signed in with no navigation and no feedback. I
proved this rather than asserted it (CR-01). It compounds with the absence of
any busy state on the two vendor buttons, which the same branch added to the
four customer buttons: a double-tap can cancel the federated logout outright
(CR-02). Both re-open the exact defect the branch was written to close.

Everything else is quality/robustness. The 91 tests across the seven changed
suites pass locally.

## Critical Issues

### CR-01: `vendorLogout` never navigates — and never resolves — if NextAuth's `signOut` stalls

**File:** `frontend/lib/vendor-logout.ts:75-84`
**Issue:**
The lookup fetch is bounded (`fetchWithTimeout`, 3 s). `signOut()` is not.
`next-auth/react`'s `signOut` performs two un-timeouted `fetch` calls to
`/api/auth/csrf` and `/api/auth/signout`. Because it is `await`ed *inside* the
`finally`, a stall there means `window.location.href = destination` on the next
line **never executes**: the vendor remains on the dashboard, with the app
session and every Keycloak SSO cookie alive, and the button gives no feedback.
That is the R-04 defect verbatim ("a request that RESOLVES and a request that
REJECTS — and misses the one that actually happens on a phone leaving a wifi
cell, which is a request that never answers at all"), left unfixed on the P0
path while the customer sibling bounds both of its fetches.

The file's own docblock claims cover: *"a failed or slow URL lookup must still
sign the vendor out locally and still land them on `/auth/signin`."* The word
"slow" is honoured for the lookup only.

The test suite has the matching hole: `lib/__tests__/vendor-logout.test.ts:88`
covers a **lookup** that never settles and `:106` covers a `signOut` that
**rejects**; nothing covers a `signOut` that never settles.

**Proof (fail direction, run and then reverted):** a probe mocking `signOut` as
`() => new Promise(() => {})` with a healthy lookup, advanced by
`VENDOR_LOGOUT_TIMEOUT_MS * 100` (300 s of virtual time):

```
console.log
  PROBE settled after 300s of timers: false
```

The promise had not settled and no destination was assigned. Control: the same
probe with the shipped `mockResolvedValue(undefined)` settles immediately (the
five existing tests).

**Fix:** bound the local teardown too, and put the navigation beyond its reach.

```ts
} finally {
  try {
    // A NextAuth signOut that never answers must not hold the navigation.
    await Promise.race([
      signOut({ redirect: false }),
      new Promise((r) => setTimeout(r, VENDOR_LOGOUT_TIMEOUT_MS)),
    ])
  } catch {
    /* Even a NextAuth failure must not strand the vendor on the dashboard. */
  }
  if (typeof window !== "undefined") {
    window.location.href = destination
  }
}
```

Navigating to the Keycloak end-session URL is what actually matters; the local
cookie drop is a best-effort second (and the redirect back to `/auth/signin`
re-evaluates it anyway). Add the missing arm to the test file so the guard is
shown to fail before it is trusted.

---

### CR-02: Vendor "Sign Out" has no busy state, and a double-tap can cancel the federated logout

**File:** `frontend/components/dashboard/sidebar.tsx:143`,
`frontend/components/dashboard/mobile-tab-bar.tsx:177`,
`frontend/lib/vendor-logout.ts:61`
**Issue:**
The same branch added `signingOut` + `disabled` + `aria-busy` to all four
*customer* sign-out affordances (`public-header.tsx`, `storefront-nav.tsx`) with
an explicit rationale — *"Without a busy state the shopper gets no
acknowledgement at all and taps again."* The two *vendor* affordances got
`onClick={() => vendorLogout()}`: a floated promise, no disabled state, no
`aria-busy`, and now a round-trip that is bounded at 3 s and therefore visibly
slow on a bad connection. The reasoning applies identically to a vendor; it was
simply not applied.

That omission is not only cosmetic, because `vendorLogout` is not re-entrant:

1. Tap 1 → lookup A in flight.
2. Tap 2 → lookup B in flight.
3. A resolves with the Keycloak end-session URL → `signOut()` → `location.href = <keycloak>`. The browser begins navigating (asynchronously).
4. B resolves. The app session was just dropped by step 3's `signOut`, so the
   route takes its `!idToken` branch and returns `<origin>/auth/signin`. The
   second invocation assigns `window.location.href = "<origin>/auth/signin"`,
   **overriding the pending navigation to Keycloak.**

Net result: the app cookie is gone, the Keycloak SSO session is alive, and the
vendor lands on the sign-in page where one click on "Sign in with Keycloak"
re-enters the dashboard with no credential prompt — precisely the P0 this branch
closes, reachable by the exact user behaviour (`tap again`) the branch documents
as expected.

**Fix:** give both vendor buttons the busy state the customer buttons got, and
make `vendorLogout` idempotent so the guard does not depend on the UI alone.

```tsx
// sidebar.tsx / mobile-tab-bar.tsx
const [signingOut, setSigningOut] = useState(false)
const handleSignOut = () => { setSigningOut(true); void vendorLogout() }
// …
<Button onClick={handleSignOut} disabled={signingOut} aria-busy={signingOut}>
```

```ts
// vendor-logout.ts — a second call must not re-race the first.
let inFlight: Promise<string> | null = null
export function vendorLogout(): Promise<string> {
  return (inFlight ??= doVendorLogout())
}
```

Deliberately do **not** reset `signingOut` in a `finally` here — see WR-06; the
correct terminal state for a sign-out button is "stays busy until the page
goes away".

## Warnings

### WR-01: R-03's entrance budget is measured from the wrong clock — the hero entrance is dead on every soft navigation

**File:** `frontend/components/marketing/hero-scene.tsx:70-73`,
`frontend/lib/gsap-gate.ts:44-73`
**Issue:**
`performance.now()` is milliseconds since the document's **time origin**, which
is set once at the initial page load and is *not* reset by client-side routing.
`HeroScene` is mounted by `app/page.tsx:181`, and `/` is reachable by soft
navigation — `public-header.tsx`'s own docblock states the wordmark "ALWAYS goes
to `/`" via `next/link` from every public surface.

So a visitor who lands on `/shop`, browses for 30 s and clicks the wordmark
mounts the hero at `performance.now() ≈ 30000`, and `entranceIsSafe` returns
`false`. The entrance is therefore **permanently skipped for the entire rest of
the session** after the first 1.2 s, regardless of how fast the bundle arrived —
and `data-entrance="skipped"` will faithfully report it as a correct decision,
so a throttled-profile observation pass reads green either way.

The docblock's stated calibration ("comfortably past a healthy hydration on a
warm connection") is only true for the *first* document load. This is a
regression-by-omission under the project's Incremental Betterment Doctrine: a
working good (the landing entrance on in-app navigation) is traded away to fix
the late-hydration case.

**Fix:** measure elapsed time since *this scene* became eligible, not since
navigation start.

```ts
// inside useGSAP, before the matchMedia block
const mountedAt = performance.now()
// …inside the desktop branch:
const animateEntrance = entranceIsSafe(performance.now() - mountedAt)
```

For the hard-load case this is identical to today's value in every way that
matters (the hook fires as soon as the bundle executes, so `mountedAt` *is* the
hydration moment); for a soft navigation it correctly reads ~0 and the entrance
plays. Add a control arm asserting `data-entrance="played"` after a simulated
soft nav — without one, the current behaviour and the fixed behaviour are
indistinguishable from the test suite.

---

### WR-02: The R-07 offset does not reach the two surfaces where three of the five symptoms were measured

**File:** `frontend/components/public/cookie-notice.tsx:70-101`
**Issue:**
`CookieNotice` is mounted in the **root** layout (`app/layout.tsx:48`), so it
renders on every route. The `--jt-bottom-chrome` publishers are not: only
`MobileTabBar` (dashboard, `dashboard-shell.tsx:101`) and `FloatingCartBar`
(`/shop/[slug]`) call the hook. On `/` and on `/shop` (discovery) no publisher
exists, the property is absent, and the notice falls back to `bottom: 0px` —
exactly where it was.

Symptoms (3) "covered the mobile 'Browse all kitchens' zero-result escape hatch"
(that control is on `/shop`) and (4) "hid ~80% of the landing 'Order food near
you' CTA at 390x844" (that is `/`) are therefore addressed only by the
pointer-events split and the smaller card, not by the offset. The docblock
claims the split "closes (1), (3) and (4) as a CLASS rather than one at a time".
It does not: `pointer-events-none` removes *click interception*, leaving the
control **visible-through-nothing but still occluded** — a user cannot click a
CTA they cannot see. The claim is stronger than the mechanism.

This is a documentation-truthfulness finding as much as a UI one: a future
reader will trust "closed as a class" and stop looking.

**Fix:** either narrow the claim to what pointer-events actually buys (it closes
the *interception* class, and (1) specifically, where the sidebar rail is beside
rather than under the card), or extend the publisher so `/` and `/shop` also
declare their bottom band. Then verify visually at 390x844 with a scroll first —
a screenshot without scrolling shows scroll-reveal content as empty bands.

---

### WR-03: `ConsentBanner` bypasses the entire R-07 fix

**File:** `frontend/components/public/cookie-notice.tsx:143`,
`frontend/components/public/consent-banner.tsx:68`
**Issue:**
`CookieNotice` returns `<ConsentBanner />` and exits *before* any of the R-07
work, whenever `choosableCategories().length > 0`. `ConsentBanner`'s container
is still the pre-fix shape:

```
"fixed inset-x-0 bottom-0 z-40 border-t border-white/15 bg-oxblood text-cream",
```

No `pointer-events-none` wrapper, no `pointer-events-auto` card, no
`var(--jt-bottom-chrome, 0px)`. Every one of the five measured symptoms returns
on that branch — including symptom (2), the storefront cart bar painting over
the dismiss control, which makes the banner **permanently un-dismissable and the
consent choice unrecordable**. For a consent surface that is a compliance
failure, not a cosmetic one.

It is dormant today only by accident: `choosableCategories()` filters
`registeredCategories()` to the non-essential ones, and nothing non-essential is
registered yet. The day an analytics category is added, this ships broken behind
a green suite — the fix will look done because `cookie-notice.tsx` was fixed.

**Fix:** hoist `WRAPPER_CLASS` / `CARD_CLASS` and the `bottom` style into a
shared positioning shell that both the notice and the banner render inside, so
there is one bottom-chrome contract rather than a fixed one and a forgotten one.

---

### WR-04: The id_token is returned by a plain GET with no `Cache-Control: no-store` / `Vary: Cookie`

**File:** `frontend/app/api/vendor-auth/logout-url/route.ts:112-114`
**Issue:**
The response body embeds the caller's raw `id_token`. The handler sets no cache
headers; `export const dynamic = "force-dynamic"` governs Next's *rendering*
mode, not the emitted `Cache-Control`, and I found no explicit `no-store` in the
app-route module or anywhere in `frontend/app/api/**`. Correctness therefore
rests on an implicit framework default plus every intermediary (ingress, any
future CDN or corporate proxy) inferring "do not share this" from a URL that
carries no user-varying component. A shared cache keyed on path alone would
serve user A's id_token to user B.

This is defence-in-depth rather than a live exploit — the deployment has no CDN
in front of `/api/*` today, and `lib/session-callback.ts:19` already exposes the
same token on `/api/auth/session`. But this is a *new* endpoint on the P0 path
and it is one line to make explicit. The customer sibling
(`app/api/customer-auth/logout-url/route.ts`) shares the gap; fixing both is
the same edit.

**Fix:**

```ts
return NextResponse.json(
  { url: `${base}/protocol/openid-connect/logout?${params.toString()}` },
  { headers: { "Cache-Control": "private, no-store, max-age=0", Vary: "Cookie" } }
)
```

Apply to the degraded branch at line 98 as well, so the two paths cannot drift.

---

### WR-05: `sanitizeRedirect` is a third, and weakest, copy of a narrowing this repo already owns

**File:** `frontend/app/api/vendor-auth/logout-url/route.ts:70-76`
**Issue:**
There are now three same-origin redirect narrowers:
`lib/customer-auth.ts:519` (`safeReturnTo`),
`app/api/customer-auth/logout-url/route.ts` (`sanitizeRedirect`), and this one.
The new copy is character-identical to the customer route's and strictly weaker
than `safeReturnTo`, which additionally: trims, rejects a backslash **anywhere**
(not only at index 1), and rejects any `scheme:` prefix.

To be precise about severity — I could not turn the missing checks into an
escape. `startsWith("/")` already excludes a bare scheme, `//` and `/\` are
caught, `searchParams.get()` decodes percent-encoding before the checks (so
`%2F%2Fevil` and `/%2fevil` are rejected), and a tab/CR/LF-embedded path is
stripped by the browser into a *same-host* `//path`, not a cross-origin URL.
This is a maintenance finding: three copies of a security predicate, and the
strongest one is not the one guarding the newest surface. The next person to
harden `safeReturnTo` will not know to harden these two.

**Fix:** export `safeReturnTo` from a server-safe module and have both route
handlers call it with their own fallback:

```ts
import { safeReturnTo } from "@/lib/safe-return-to"
const redirect = safeReturnTo(req.nextUrl.searchParams.get("redirect"), "/auth/signin")
```

Keep the existing hostile-input table in `logout-url.test.ts:177-184` — and add
the two cases `safeReturnTo` already covers and this copy does not
(`"/foo\\evil.example"`, `"  //evil.example"`), so the strengthening is shown to
fail against the current implementation first.

---

### WR-06: The customer busy state flickers off at exactly the wrong moment

**File:** `frontend/components/public/public-header.tsx:65-73`,
`frontend/components/storefront/storefront-nav.tsx:29-37`
**Issue:**
```ts
setSigningOut(true)
try { await customerLogout() } finally { setSigningOut(false) }
```
`customerLogout()` resolves at the end of *its own* `finally`, i.e. immediately
after it assigns `window.location.href = logoutUrl`. Assigning `location.href`
only *schedules* a navigation; the document stays live and interactive until it
commits — which on the bad connection this feature targets is precisely the slow
part. So the button is re-enabled during the whole navigation window, which is
the window the busy state exists to cover, and the shopper can tap again exactly
as before.

`customerLogout` is more forgiving of a re-tap than `vendorLogout` (CR-02) —
both fetches are bounded and the teardown is idempotent — but the second call
still re-runs the teardown and re-assigns `location.href`, and the flag makes a
promise it does not keep.

**Fix:** drop the `finally`. A sign-out button's correct terminal state is
"busy until this document goes away".

```ts
const handleSignOut = async () => {
  setSigningOut(true)
  await customerLogout()   // navigation is the terminal state
}
```

If a reset is wanted for the "navigation was blocked" case, reset on
`pagehide`/`visibilitychange`, not on promise resolution.

---

### WR-07: `resolvePublicOrigin`'s last fallback is Host-header-derived

**File:** `frontend/app/api/vendor-auth/logout-url/route.ts:89`
(consuming `frontend/lib/public-origin.ts:88`)
**Issue:**
The chain ends `?? toOrigin(req?.nextUrl?.origin)`. The module documents,
correctly, that inside a container this is the bind address and is filtered by
`isBindAddress`. Outside a container — a non-standalone `next dev`, or any
deployment where the server binds a real interface — `nextUrl.origin` *does*
follow the request Host, so with `NEXTAUTH_URL` and `APP_PUBLIC_ORIGIN` both
unset an attacker-supplied `Host: evil.example` flows into
`post_logout_redirect_uri`. The only thing standing between that and a
post-authentication open redirect is Keycloak's registered-redirect-URI check —
a control in a different system, not asserted by anything here.

Inherited rather than introduced (the customer route consumed it first), and
`lib/env-validation.ts:71` lists `NEXTAUTH_URL` as REQUIRED, so it is a residual.
Recording it because the new route is on the P0 path and the residual is not
stated anywhere in it.

**Fix:** either drop the request-origin fallback (the `null` branch is already
correct and safe — it omits `post_logout_redirect_uri` and still terminates the
session), or gate it on `NODE_ENV !== "production"`.

## Info

### IN-01: Unencoded query interpolation in the vendor lookup URL

**File:** `frontend/lib/vendor-logout.ts:65`
**Issue:** `` `/api/vendor-auth/logout-url?redirect=${FALLBACK_DESTINATION}` ``
interpolates without `encodeURIComponent`. Safe for today's constant
(`/auth/signin` has no reserved characters); a latent defect the moment the
destination becomes dynamic. The customer sibling
(`customer-auth.ts:469`) has the same shape.
**Fix:** `?redirect=${encodeURIComponent(FALLBACK_DESTINATION)}`.

---

### IN-02: A superseded 429 leaves its retry timer armed

**File:** `frontend/app/shop/shop-discovery-client.tsx:325`
**Issue:** The R-09 generation guard returns from `catch` before the rate-limit
branch, so a stale request's already-scheduled `retryTimerRef` (armed by an
*earlier* 429 for an abandoned query) is neither cleared nor its
`retryAttemptRef` reset. It will fire `fetchRef.current()` — which is by then
the current query, so the result is correct but the request is unintended, and
the backoff budget carries over from a query the customer has left. Harmless
today; noise in the retry accounting.
**Fix:** clear `retryTimerRef` when a new generation starts, beside
`const generation = ++fetchGeneration.current`.

---

### IN-03: `--jt-bottom-chrome` is a global single-writer property with no ownership token

**File:** `frontend/hooks/use-bottom-chrome-height.ts:74-89`
**Issue:** Every cleanup calls `removeProperty` unconditionally, and the effect
has no dependency array so cleanup+publish run on every render. That is correct
only while the docblock's claim holds — "the two publishers … are never mounted
together" — which nothing enforces: a third publisher, or a future layout that
nests the storefront chrome inside the dashboard shell, silently gets
last-cleanup-wins. Also cosmetic: `AnimatePresence`'s exit animation keeps the
cart bar mounted (and its height published) for the exit duration after the
basket empties, so the notice hovers above nothing for ~300 ms before snapping
down.
**Fix:** key the property by publisher (`data-bottom-chrome` attribute + a
`Math.max` over the mounted set), or at minimum only `removeProperty` when the
value currently stored is the one this instance wrote. A test that mounts two
publishers and unmounts one would fail today.

---

_Reviewed: 2026-08-31T12:03:02Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: quick (auth route: thorough)_
