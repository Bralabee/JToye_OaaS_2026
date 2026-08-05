---
quick_id: 260805-j1e
slug: kds-offline-banner-429
date: 2026-08-05
issue: 561
branch: fix/561-kds-banner-429-tolerance
status: complete
---

# Summary — #561 was a product defect, and the mechanism was not the one anyone guessed

## What it turned out to be

#561 listed three candidate mechanisms and honestly said none had been measured. It is
**(3), the product-side one**. The trigger is the board's own recovery request.

`fetchOrders()` on the full path issues **1 list request + one `/detail` per active
ticket**, concurrently, and the `online` handler deliberately takes that path on
recovery. On the E2E vendor's board — `Brixton Village Grill`, **18 active tickets** —
that is **19 requests**, fired twice inside ~400 ms by an offline blip. The tenant
limiter is `Bandwidth.capacity(120).refillIntervally(100, 1 min)`: one lump per minute,
so whatever else the tenant spent in the same 60 s window is carried state.

`fetchKitchenOrderDetails` used `Promise.all`, so a single 429 rejected the **whole**
read — including the list request that succeeded and the eight details that succeeded.
`syncFailed` went true, `deriveFeedState` returned `status: "error"`, and the board
announced *"Orders are not refreshing"* over data it was still holding, with nothing
retrying for up to a minute.

**In a kitchen that means a board which dropped its connection for a moment keeps being
told it is broken long after it is fine** — the exact failure the test's own comment
names: *"A warning that outlives its cause is how a kitchen learns to ignore warnings."*

## Evidence

Two arms from Playwright traces against the live Compose stack, identical request
patterns, opposite outcomes:

| arm | `/api/v1/orders*` | statuses | lowest `X-RateLimit-Remaining` | `:339 [mobile]` |
|---|---|---|---|---|
| `kitchen-flow.spec.ts` alone | 38 (19 + 19) | **38 × 200** | **79** | PASS |
| after the 3 mobile specs that precede it | 38 (19 + 19) | 28 × 200, **10 × 429** (`Retry-After: 12`) | **0** | FAIL |

The 10 × 429 land in the **second** burst — the recovery refetch — and the trace shows
**zero further requests** for the remaining ~20 s, because the next attempt is the 60 s
poll.

Reproducing needed only **4 spec files (1.5 min)**, not the 6.6-min suite: the three
mobile specs before `kitchen-flow` plus `kitchen-flow` itself.

## The fix

- `fetchKitchenOrderDetails` → `Promise.allSettled`, returning `{ details, failedIds }`.
- `fetchOrders` judges the read on **what the board can show**: the list read succeeded
  and every active order has a detail, fresh or held → success. A ticket with **no**
  detail at all still fails the sync and still raises the banner. That second half is
  what stops the fix from being a mute button, and it has its own test.

Deliberately **not** done: no timeout raised, no rate limit loosened, no bounded
concurrency (it would cut the 429s at source but changes refresh latency — a separate
decision, recorded as a follow-up).

## Two things I got wrong, both caught by measurement

1. **The first version of the new E2E test read the live board** — so it cost 19 requests
   to load and 19 to refresh, and became an instance of the very budget dependency it
   existed to remove. Run after the specs preceding it, its own page load was refused and
   the pill read `Offline —` with a null stamp. Rewritten to stub the order list/detail
   at page level with a four-ticket fixture, lifting only the `ws` and `shops` stubs (the
   socket is half of what clears the banner; the STOMP topic is derived from the data).
   Cost to the tenant: nothing. Runtime: 2.3 s.

2. **The rewritten test then armed its 429s during the initial load.** It passed in
   isolation and in the 4-file arm, and failed on **both** projects in the full suite —
   the same shape as the defect itself. The trace named it: throttling was armed on
   `pill → "Live"`, and the pill reads the **socket**, which connects before the first
   read returns. Two tickets never got detail at all, so the board was genuinely missing
   them and correctly refused to go quiet. It now waits for a wall clock in the pill
   (`lastSyncedAt !== null`) and all four tickets rendered before arming.

**The transferable lesson, which #561 had already written down and I still repeated:**
where the full suite and a single spec disagree, **the disagreement is the finding** —
neither result is the answer, and the cheaper side is not the tiebreak.

## Verification

- **New E2E test, both directions against the RUNNING build** (not the source): with the
  old semantics restored, both projects FAIL at `kitchen-flow.spec.ts:513`, the banner
  assertion, after the non-vacuity poll confirmed requests really were throttled;
  restored, both PASS in 2.3 s. The break was proven to have **shipped** — a marker
  string carried into the toast text read back out of the served `.next` bundle at **2**,
  and **0** after the restore, with the fix's own string still at **2**. A break arm that
  silently did not happen reads exactly like a guard that does not fire.
- **Jest page-level pair**, added because per-PR CI runs 2 of 126 E2E specs: a partly
  refused re-read stays quiet; a ticket with no detail at all still raises the banner.
  Break arm run there too (fails on the old semantics), restore hash-verified.
- **`:339` passing is NOT evidence the fix tolerates 429s.** In the green re-run it saw
  **38 × 200, lowest remaining 24** — the condition did not recur. It is budget-dependent
  and therefore cannot be the regression test; the injected-429 test is.
- Runtime parity proven by content, not timestamp: the fix's string is in the served
  bundle (2), a constructed-absent control returns 0, a pre-existing kitchen string
  returns 2.
- Lint: **0 errors**, 26 warnings — both warnings in `kitchen/page.tsx` verified present
  on `origin/main`, with a token I added returning 0 there so the probe discriminates.
- `docs/metrics.json` regenerated with `scripts/docs-freshness.sh --write`, never
  arithmetic: jest 743 → 747, playwright 84 → 85, total 2386 → 2391.

## Follow-up worth filing

The board issues **one request per active ticket** on every full refresh, so an 18-ticket
board costs 19 requests and a 40-ticket board would cost 41 — against 100/min for the
whole tenant. This change makes the board *tolerant* of the resulting refusals; it does
not reduce them. Bounded concurrency, or a batch detail endpoint, is the actual remedy.
