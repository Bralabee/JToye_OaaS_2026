---
quick_id: 260730-u3t
description: separate customer and vendor sign-in
date: 2026-07-30
branch: feat/customer-vendor-signin-split
status: complete
---

# Quick Task 260730-u3t — SUMMARY

## The defect

`components/public/public-header.tsx:84,136` ("Sign in") and
`components/public/public-footer.tsx:76` ("Vendor sign in") both resolved to `/auth/signin`.

They are not two doors to one system. `/auth/signin` authenticates against the **`jtoye-dev`**
staff realm via NextAuth; customers exist only in **`jtoye-customers`** (`lib/customer-auth.ts`,
`storefront-client`, PKCE, HttpOnly cookies). Both realms are live, and the **backend split is
already correct** (`security/CustomerJwtVerifier.java`, a separate `CUSTOMER_KC_ISSUER_URI`). Only
the frontend surface leaked — so a shopper clicking the primary CTA on `/`, `/track`, `/legal`,
`/for-operators`, `/competitive` or `/business-model-guide` was sent to an identity pool their
account does not exist in, with no route back.

Customer login also had **no page at all** — a bare `window.location` redirect fired from a button
inside `StorefrontNav`, so an expired session, a `/shop/orders` deep link and a bookmark had nowhere
to land.

## What shipped

| | |
|---|---|
| `/shop/signin` | NEW customer landing page. Server component (keeps `metadata` for SEO) + a client card, because a `"use client"` page cannot export metadata |
| `PublicHeader` | "Sign in" → `/shop/signin` (desktop + mobile sheet) |
| `PublicFooter` | "Vendor sign in" → `/auth/signin`, unchanged and now genuinely distinct |
| `/auth/signin` | retitled "Vendor sign in"; copy names its audience |
| both pages | reciprocal persona cross-link |
| `StorefrontNav`, `RequireCustomerAuth` | bare redirect → `Link` to `/shop/signin?next=…` |
| `safeReturnTo` | narrows `?next=` at both the read and the `router.replace()` end |
| `ShopService` | reserved-slug guard (see below) + RFC 7807 422 |

## Why the reserved-slug guard came first

Adding `/shop/signin` adds a **static** segment under `/shop/[slug]`, and Next.js resolves static
before dynamic. A shop holding that slug becomes permanently unreachable at its own URL.

This is reachable today, not theoretical: `CreateShopRequest.slug` is user-supplied, `ShopMapper`
does not ignore it, and `ShopService` only generates a slug when the supplied one is **blank**
(`ShopService.java:69`, `:188`). **`/shop/auth` and `/shop/orders` have been static since Phase 18**,
so the exposure predates this work. Both write paths are guarded; the generated path needs no guard
because it appends a random 8-character suffix, and there is a test asserting exactly that rather
than assuming it.

## Evidence

### Live browser proof — the decisive one

Ran against a real build of this branch on :3105, clicking the real controls and reading the realm
the browser actually landed in:

```
PASS  header 'Sign in' lands on /shop/signin
PASS  customer page shows its own heading
PASS  customer page offers account creation
PASS  customer page cross-links to the vendor page
PASS  CUSTOMER flow reaches the jtoye-customers realm      saw: jtoye-customers
PASS  CUSTOMER flow does NOT reach the staff realm         saw: jtoye-customers
PASS  footer 'Vendor sign in' lands on /auth/signin
PASS  vendor page shows its own heading
PASS  vendor page cross-links to the customer page
PASS  VENDOR flow reaches the jtoye-dev staff realm        saw: jtoye-dev
PASS  the two personas reach DIFFERENT realms              jtoye-customers vs jtoye-dev
PASS  a protocol-relative ?next= is neutralised            stored: null
ALL PASS
```

### Control arm — the run above means nothing without it

The **same harness** against the live stack's pre-fix build on :3000:

```
confirm PRE-FIX: header href on main's build =  2 x href="/auth/signin"
page.waitForURL: Timeout 15000ms exceeded.
  navigated to "http://localhost:3000/"
  navigated to "http://localhost:3000/auth/signin"     <-- the bug, in the log
exit rc=1
```

### Unit break arms, each verified to fail

| criterion | break arm | observed |
|---|---|---|
| personas differ | revert both header hrefs | `expect(customerHref).not.toBe(vendorHref)` fails; 3 failed / 1 passed |
| open redirect | delete the 3 guard lines, plant asserted first | protocol-relative + backslash tests fail; 2 failed / 15 passed |
| reserved slug | neuter `assertSlugNotReserved` | exactly the 3 rejection tests fail; the 2 acceptance tests correctly still pass |

Every restore verified **by content** (marker counts, guard-line greps), never by `git diff --stat`,
and a clean arm re-run after each.

## Things that went wrong, recorded

1. **A break arm silently did not run.** My first attempt at the `safeReturnTo` arm used a perl
   regex that never matched — `grep -c BREAK-ARM` returned `0` and the suite reported "17 passed".
   Had I read that as a pass it would have been a fabricated result. Redone by deleting the guard
   lines by number and **asserting the plant took effect before running the tests**.
2. **My verification grep was the defect, twice.** Checking the Java report for method names
   returned 0 for all five new tests — the XML records `@DisplayName`, not method names. And the
   live browser arm reported two false FAILs because `isVisible()` samples rather than waits, so it
   measured a hydration race; the served HTML contained the heading all along.
3. **The split broke an existing verifier.** `e2e/customer-realm-split.verify.mjs` located the
   storefront control with `getByRole("button")`, which the change turned into a link. That would
   not have failed loudly — `.first()` on a missing role **times out**, which reads as a slow stack.
   Repaired to the two-step flow, deliberately **not** relaxed to a button-or-link matcher.
4. **Checked and found safe:** the six vendor E2E specs locate the SSO control by "Sign in with
   Keycloak", unchanged by the retitle — otherwise their `test.skip(true, "No sign-in method
   found")` fallback would have gone green while testing nothing.

## Deliberately NOT done

- `lib/api-client.ts` still bounces 401 → `/auth/signin`. Its only consumers are `app/dashboard/**`
  and the dashboard image-uploader, so the vendor destination is correct there.
- `lib/public-api-client.ts` gains **no** 401 handler. Nothing calls a customer-authenticated
  endpoint through it — `customer-auth.ts`'s own `fetchWithCustomerAuth` records that it has no
  callers — so the handler would be dead code, and the expired-session path is already covered by
  `RequireCustomerAuth`. Recorded rather than silently skipped.
- No Playwright `.spec.ts` added. The live proof ran as a standalone harness (the
  `customer-realm-split.verify.mjs` precedent) because it needs Keycloak; the CI public-surface job
  is stack-free.

## Gates

Full Jest **64 suites / 441 tests / 0 failures**. `next build` rc=0 with `/shop/signin` in the route
table; zero `tsc` errors outside test files. `ShopServiceTest` 22 tests 0 failures, report verified
fresh. 11/11 static gates rc=0, including both halves of the metrics loop after re-baselining
1851 → 1868.
