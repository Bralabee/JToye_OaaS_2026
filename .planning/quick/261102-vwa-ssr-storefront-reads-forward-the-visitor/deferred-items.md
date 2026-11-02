# Deferred items — quick 261102-vwa

Out of scope for this plan (pre-existing, not caused by it). Logged, not fixed.

## /shop server render sends a client-reference function as the page size

Observed by the probe on the BASE build (6ce700eb), `evidence/probe-before.jsonl`, cases `shop` and `none`
(and the readiness poll): the server-side read of `/shop` reaches core as

    GET /public/shops?page=0&size=function%28%29%7Bthrow+Error%28%22Attempted+to+call+SHOPS_PAGE_SIZE%28%29+from+the+server+but+SHOPS_PAGE_SIZE+is+on+the+client...

Cause: `frontend/app/shop/page.tsx:10` imports `SHOPS_PAGE_SIZE` from `./shop-discovery-client`, a `"use client"`
module (`shop-discovery-client.tsx:31`, `export const SHOPS_PAGE_SIZE = 12`). In the server bundle a client
module's exports are client references, so `page.tsx:70` passes a function, `String(size)` stringifies it,
and core receives a non-numeric `size`. Expected effect: core answers 400, `getJson` returns `defer`, and
`/shop` renders the client island's loading path instead of the SSR listing (#507/#447 regression for /shop).
Not verified against a real core here (the stub ignores the query). The landing's read (`size=8`) and the
sitemap's (`size=500`) are unaffected. Fix belongs in its own quick task (move the constant to a non-client
module); this plan forwards the header on that read exactly as on every other one.
