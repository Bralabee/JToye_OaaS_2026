# Pass 2 — Kyle, 17, bored, with DevTools open

**Who I am:** Kyle. Wet Tuesday, I'm 17, laptop open, and I've just learned the Network tab shows
exactly what a site sends. Someone said these J'Toye food shops take no payment up front — cash on
collection. So nothing stops me ordering. Let's see how much mess I can make for one little shop.
Device: laptop, Chromium, DevTools. Target: ONE tenant-A shop, **Mama Ade's Kitchen**. Everything
I made is prefixed `kyle-p2`.

## What I did (and what happened)
1. **Ordered like a normal customer** (`01-shop.png`). Shop even advertises "20% OFF". Three items,
   Collection (no address, no card). Name = `<img src=x onerror=alert(1)>`; notes = a `<script>`, an
   RTL-override char and 60 chilli emoji (`03-checkout-filled.png`). Order placed, £16.49. Alert never
   popped — good for them.
2. **Read the request in DevTools.** The order POST sends only product IDs + quantities, no prices
   (`k1-net.json`). Tried tampering price/total to 1p anyway — server ignored it and priced itself
   (`k3-absurd.json`).
3. **Bulk.** Ten-line loop at `POST /public/shops/.../orders`, throwaway emails, joke phones
   ("call me","no","x"). **10 orders in 0.31s, all 201** (`k2-bulk.json`), all showing as real in the
   vendor Orders screen (`05-dashboard-orders.png`). No captcha, no verify, no slow-down.
4. **Beat the rate limit.** There is one (`X-RateLimit-Remaining` drops). Added
   `X-Forwarded-For: 203.0.113.7` → counter jumps back to full (`xff-probe.txt`). New number, full
   bucket again → order forever from one laptop.
5. **Absurd orders.** qty 999 → £8,981 accepted; qty 2,147,483,647 → **£19,305,877,986** accepted
   (`k3-absurd.json`). Negative/zero/empty refused. Another shop's dish via this menu → 404; hidden/
   sold-out → 404; made-up product → 404. Delivery to **Edinburgh** (300+mi) from Peckham → accepted,
   £3.50 (`xff-probe.txt`).
6. **Tried to break their screens.** Vendor Orders, Customers, order detail, alert hooked
   (`k4-dashboard.json`, `k5-render.json`, `06-customers.png`, `07-order-detail.png`). Nothing ran —
   0 injected `<img>`/`<script>` nodes, no dialog. Mailhog email is plain text, doesn't echo my name.
7. **Reviews / snooping.** Can't 1-star bomb (need a COMPLETED order I own, no duplicates). Can't read
   other orders (number + matching email or 404; no bare-email list). Can't replay an idempotency key
   with a different body (422). All locked (`review-tracking-probe.txt`).

## First impressions as a troll
Front door wide open — junk orders are effortless and instant. Every door BEHIND it (prices, other
tenants, scripts, reviews, other people's data) is bolted. I can't steal or deface anything; I can
only waste the vendor's food and time — which for a kid is the easiest win because it's free to me.

## Expectations vs reality
| I expected | I got | How it felt |
|---|---|---|
| Something to slow a burst | 10 orders in 0.31s, all accepted | Too easy |
| Rate limit to hold | Reset with a spoofed header | They tried, I walked through |
| A quantity cap | £19bn order accepted | Comedy, but it's on their books |
| My `<script>` to pop | Rendered as plain text | Good for them |
| To buy food for 1p | Server priced it, ignored mine | Properly done |
| To 1-star bomb a shop | Blocked — need my own completed order | Fair |
| To read other orders | 404 without exact order number | Locked |
| A bulk "reject spam" button for the vendor | None — one at a time | Their problem |

## Findings
| ID | Severity | Status | Finding | Evidence |
|---|---|---|---|---|
| P2-KYL-01 | major | CONFIRMED | 10 fake cash orders in 0.31s; no cap/captcha/verify; land as real PENDING | `k2-bulk.json`, `05-dashboard-orders.png` |
| P2-KYL-02 | major | CONFIRMED | Rate limiter bypassed by rotating `X-Forwarded-For` (resolver trusts first hop, no trusted-proxy list) | `xff-probe.txt`, `ClientIpResolver.java` |
| P2-KYL-03 | major | CONFIRMED | No max quantity: 999 → £8,981; 2,147,483,647 → £19.3bn accepted | `k3-absurd.json` |
| P2-KYL-04 | major | CONFIRMED | No bulk-reject / no fraud flag; vendor cancels each junk order by hand | `05-dashboard-orders.png` |
| P2-KYL-05 | minor | CONFIRMED (RECONFIRMS J-01) | 300-mile delivery accepted, flat £3.50, no serviceability check | `xff-probe.txt` |
| P2-KYL-06 | minor | CONFIRMED (RECONFIRMS F-07) | Junk phone/name ('x','no','call me') accepted; no callback possible | `k2-bulk.json` |
| P2-KYL-07 | minor | CONFIRMED | Advertised "20% OFF" never applied to total (display-only promo) | `01-shop.png`, `k1-net.json` |
| P2-KYL-P1 | positive | CONFIRMED | No stored XSS: HTML/script/RTL/emoji stored raw but render inert; emails plain text | `k4-dashboard.json`, `k5-render.json` |
| P2-KYL-P2 | positive | CONFIRMED | Server-authoritative pricing; body price/total ignored | `k3-absurd.json` |
| P2-KYL-P3 | positive | CONFIRMED | Cross-shop / unavailable / ghost products all 404 | `k3-absurd.json` |
| P2-KYL-P4 | positive | CONFIRMED | Review abuse blocked (own + completed + once) | `review-tracking-probe.txt` |
| P2-KYL-P5 | positive | CONFIRMED | Tracking non-enumerable; idempotency reuse-with-different-body → 422 | `review-tracking-probe.txt` |

## What a real troll/fraudster could do
Nothing lucrative — no cheap food (server prices), no data theft, no defacement. But griefing is
trivial and free: rotate `X-Forwarded-For`, loop the order endpoint, and a small cash-on-collection
vendor wakes to a kitchen full of "Mickey Mouse" orders with phone "x" (incl. a £19bn one) that
nobody collects — cancelled one by one, by hand, during service. The no-upfront-payment model's whole
risk is exactly this, and there's currently no brake.

## What delighted me (keep these)
Pricing, tenant isolation, output escaping, review gating, tracking privacy, idempotency — all solid.
Well-defended against theft and takeover; the gap is abuse VOLUME.

## Verdict (in character)
I could ruin a small vendor's Friday for free in about a minute, and she'd have no way to call the
fake customers back or clear them in bulk. I can't rob her — but "can't rob" isn't "can't wreck".
**One thing that would change my mind:** make fake orders cost something or slow down — real
per-identity/device throttling a header can't reset, a quantity cap, and a bulk-reject for the vendor.

## Scores (1–10)
- **Ease (of causing chaos): 9** — a for-loop and a spoofed header.
- **Trust (that they'll stop me): 3** — they tried (limiter, escaping), but the front door's open.
- **Speed: 10** — 10 orders in a third of a second.
- **Mobile: N/A** — done from a laptop with DevTools.
- **Overall (as an abuse surface): 3** — strong vs theft, weak vs volume abuse, the one that burns a
  no-payment vendor's money.

## Cleanup
Placed 16 `kyle-p2` orders total (all tenant-A, under the 25 budget). **All 16 cancelled from the
vendor side as admin-user (HTTP 200 each); 0 non-cancelled kyle orders remain** across all three shops.
Guest orders create no customer rows — nothing to erase there. **Could not remove:** the 16 orders
still exist as status CANCELLED (no vendor order-delete exists; same as pass-1). No products, shops,
staff or reviews created.
