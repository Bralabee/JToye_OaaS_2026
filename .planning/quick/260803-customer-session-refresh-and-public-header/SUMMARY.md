---
quick_id: 260803-13q
slug: customer-session-refresh-and-public-header
status: complete
date: 2026-08-03
issues: ["#465", "#457"]
branch: feature/customer-session-refresh-and-public-header
commits: ["567a3939", "826b4df1"]
---

# Summary — customer session renewal (#465) + session-aware public header (#457)

## What was actually wrong

The owner reported one symptom — *"going home when logged in logs me out"*. A browser
falsification run **before** any code was written found it was **two** defects, and that the
filed diagnosis for #457 was only half the story:

| | finding | verdict |
|---|---|---|
| #457 | `PublicHeader` has zero session references, so `/` and `/track` show "Sign in" | **Confirmed.** The session survived the navigation intact — control arm: returning to `/shop` restored signed-in chrome |
| #465 | the session ends at exactly 300s regardless of activity | **New.** Found while measuring #457; filed separately as the P1 |

#465 mattered for sequencing: fixing the header alone would **not** have resolved the reported
symptom, because after five minutes the header would correctly say "Sign in" — the customer really
was logged out.

## Evidence, both directions

**Pre-fix, measured:** expiry stayed pinned through 4 minutes of continuous navigation and the
customer was signed out at 300s. The refresh token was stored HttpOnly for 30 days and never
redeemed (`grant_type: "refresh_token"` existed only in `auth.ts`, the *operator* path on a
different realm). Keycloak's SSO session (30 min idle / 2 h max) was still alive throughout —
re-signing in required no credentials, which is the clearest statement of the bug.

**Break arms — every new assertion was shown to FAIL before being trusted:**

| arm | broke | result |
|---|---|---|
| 1 | write the old refresh token back instead of the rotated one | 2 failed / 11 passed — and the **single**-refresh test still passed, which is exactly why the test asserts two consecutive refreshes |
| 2 | bypass the single-flight map | 1 failed — the concurrent-probe test |
| 3 | revert `PublicHeader` to session-blind | 6 failed, including the anonymous-visitor test, proving it asserts the *seam* and not just the rendering |

All three restores verified **by `git hash-object`**, not by `git diff --stat`. Opening clean arm
29/29; **closing clean arm 54/54** — the closing arm is the only thing that proves the restores
happened.

**Post-fix, on the rebuilt runtime (image id of the running container == the fresh tag):**

| criterion | result |
|---|---|
| AC-1 | **11 minutes** signed in across two full lifespans; expiry rolled forward `00:03:44 → 00:09:25 → 00:14:26` |
| AC-2 | two successive refreshes succeeded — mishandled rotation fails on the *second*, so this is the live rotation proof |
| AC-3 | `document.cookie` empty throughout; no token reachable from JS |
| AC-4 | `/` and `/track` render `My Orders · Ux1 Probe · Sign out`, no `Sign in` |
| AC-5 | 0 direct `getCustomerSession` reads in either header; both consume the shared hook |
| AC-6 | anonymous path untouched — still `200 { authenticated: false }`, never 401 |
| AC-7 | full jest **69 suites / 498 tests / 0 fail**; `npm run build` rc=0; both docs gates rc=0 |

**AC-5 stated precisely.** Other components (`/shop/orders`, checkout, `/track` page,
`RequireCustomerAuth`, the sign-in card) do call `getCustomerSession` directly — those are
page-level data fetchers consuming the same single source, not a competing source of truth, and
they inherit the #465 renewal automatically. The claim proven here is narrower and is the one
#457 asked for: **neither header** reads the session independently.

## Incidental finding — NOT fixed, NOT caused here

`/api/customer-orders` returns **502 `upstream_unavailable`** on the compose stack. The frontend
container cannot reach `http://localhost:9090` (connection refused — the `extra_hosts`
localhost→host-gateway mapping does not win over the container's own loopback), and
`CORE_API_INTERNAL_URL` is unset. Present in the browser console **before** any edit in this task,
so it is pre-existing.

The failure mode is what makes it worth reporting: the page renders the 502 as
*"No orders found for this email"* — **an error displayed as an empty state**. Same shape as #444
(a shipped feature that has never worked). Likely a one-line compose fix
(`CORE_API_INTERNAL_URL: http://core-java:9090`) but it needs its own verification and issue.

## Residue

Keycloak `jtoye-customers` user `ux1probe20260803@test.com` (probe account, enabled). The realm
already carries ~8 `cust+*` and ~11 `e2e*` accounts from prior runs.
