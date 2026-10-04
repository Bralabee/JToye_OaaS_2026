# Persona 04: Jordan, hungry customer (saved by the coordinator from the agent's returned report)

> The harness refused the subagent's own Write, so the coordinator saved this from its returned report. Screenshots and scripts (`s01`–`s18*.mjs`, `lib.mjs`) are in this folder.
> **Coordinator adjudication of J-04 (checked in code and the running jar):** the behaviour is real ON THIS DEV STACK, but it is a deliberate local-dev setting, not a production hole. `application.yml:281` sets `require-verified-email: ${CUSTOMER_JWT_REQUIRE_VERIFIED_EMAIL:true}`, a secure default whose comment names exactly this attack. Only `application-dev.yml:36` relaxes it to `false`, to match the dev realm's `verifyEmail:false` (confirmed live via the Keycloak admin API). Severity downgraded to **minor (dev-only)**. Residual risk: an environment that runs the dev profile, or sets the env var to false, would expose it. A deploy-time assertion would close that gap.

**Verdict:** "I'd use it again only if my mate swears by the jollof. I wouldn't recommend it yet." The menu and guest checkout are good. What's missing is card/Apple Pay, any ready-by time, and an Add button that works on the first tap.
**What would change his mind:** card or Apple Pay, plus a ready-by time and the address on the confirmation ("Ready ~21:20 — 12 Bellenden Rd").
**Scores:**
- **Ease 7:** short guest flow, but the first tap is ignored and nothing can be customised.
- **Trust 5:** cash-only revealed at the last step, Belfast delivery accepted, bare email.
- **Speed 6:** Add does nothing until 4.2 s on 4G and 14 s on Slow 3G.
- **Mobile 7.**
- **Overall 5.**

## Persona
Jordan, 23, a Peckham student on an iPhone 13 (390px). Throttled to 4G (1.6 Mbps, 150 ms RTT), plus one Slow 3G pass (400 kbps, 400 ms). Uses Deliveroo, Uber Eats and Just Eat weekly, judges by photos and price, and has no patience. Arrived from a friend's link.

## Environment notes (not findings)
- **ENV-1:** the :9091 outage (#671). Every affected step was re-run; results below come from the re-runs only.
- **ENV-2:** Stripe is not configured (env-var names in `.env` don't match compose), so `acceptsCardPayments:false`. The 4242 success, 3DS and declined legs are **BLOCKED** and need a separate pass.

## Journey
1. **/shop on 4G** (`01`, `02`):
   - h1 in about 0.2 s, LCP about 2.4 s.
   - Cards show min order, delivery fee, the free-over threshold and "Open".
   - Each card has a gradient and initials instead of a food photo; no ETA or rating.
   - The cookie banner covers the first card.
2. **Search** (`03a`–`03d`):
   - SE15 4QL → "3 kitchens within 3.1 miles", nearest first.
   - BT1 1AA and ZZ99 9ZZ both show the same "No kitchens found".
   - "jollof" → 2 results.
3. **Direct shop link** (`04`, `05`, `07`):
   - h1 in about 1 s; real photos (10/10 with naturalWidth > 0); dietary chips; allergen badge; fees shown.
   - No prep or delivery time and no item customisation.
   - Descriptions repeat the item name, and Popular items are duplicated under Mains.
4. **Basket** (`06`, `08`–`10`): "Add" turns into a stepper; a sticky "View basket" bar; units counted correctly (4 items, £20.00); the delivery fee is explained.
5. **Guest collection checkout** (`11`, `12`, `14`):
   - Cash-only is revealed only at the bottom ("Pay on collection — cash to the shop").
   - The allergen acknowledgement is required.
   - The order was confirmed in about 20 ms (…3FD79CC4).
   - The confirmation shows no items, no shop address and no ready time.
6. **Tracking** (`15`): a status timeline and "Live updates every 15 seconds"; no ETA, items or address. Still PENDING about 25 minutes later.
7. **Email** (`mail1.json`): about 4 plain-text lines with no shop, items, total or address. The tracking link prefills the order number but asks for the email again; a wrong email gives a clean "Order not found".
8. **Guest delivery** (`20`, `21`, `23`): Belfast BT1 1AA was ACCEPTED as the address for a Peckham shop (…B090D3F3), and Manchester M1 1AE was accepted at Mama Ade's (…6CC30687). Also a "Co.." double full stop.
9. **Account mid-journey** (`24`–`26`): registration needed no email verification, and the basket survived sign-up.
10. **My Orders** (`27`): all 3 guest orders are listed under that email, with matching counts and totals. See J-04 for the dev-only caveat.
11. **Sign out, then sign in** (`32`, `33`, `35`):
    - The sign-out control is a 14 px icon; one tap, no confirmation.
    - The basket is cleared on sign-out but not restored on sign-in.
    - React hydration error #418 appeared once.
12. **Speed** (`s17-slow3g.mjs`, `36-*`):

| profile | h1 | Add visible | taps needed | Add works at | all menu images |
|---|---|---|---|---|---|
| 4G | 0.98 s | 0.99 s | 2 | 4.2 s | 8.6 s |
| Slow 3G | 3.2 s | 3.3 s | 7 | 14.1 s | 34.6 s |

About 358 KB transferred in total.

## Expectations vs reality
| Expected | Got | Felt |
|---|---|---|
| Photos up front | Menu: yes. Shop list: gradients and initials | Unfinished list |
| Prices and fees up front | Consistent everywhere | Better than Deliveroo |
| ETA up front | None anywhere | Deal-breaker |
| Apple Pay speed | Cash only (ENV-2), revealed last | Would abandon |
| Know when to walk over | No time, no address | Anxious |
| No forced account | True guest checkout | Genuinely good |
| Basket follows me | Survives sign-up; not restored on sign-in | Worse than Deliveroo |
| Delivery only where served | Belfast and Manchester accepted | Driver to Belfast? |
| Customise food | No modifiers | Uber Eats does |

## Findings
| ID | Severity | Status | Finding | Evidence |
|---|---|---|---|---|
| J-01 | major | CONFIRMED (2 shops, 2 postcodes) | Delivery accepted to any postcode; no radius or serviceability check at checkout, although discovery computes distances | `21`, `23`; orders …B090D3F3, …6CC30687 |
| J-02 | major | CONFIRMED (3 fresh contexts) | Add is visible at about 1 s but ignores taps until hydration (4.2 s on 4G; 14 s and 7 taps on Slow 3G), with no cue | `s17`, `36-*` |
| J-03 | major | CONFIRMED | No ready or delivery time anywhere; no shop address on the collection confirmation | `14`, `15`, `mail1.json` |
| J-04 | minor (dev-only, coordinator) | CONFIRMED on dev | Unverified registration + My Orders linked by email → another person's order history is visible. Prod default `require-verified-email=true` blocks it. | `27`, `realm-export-customers.json`, `application.yml:281`, `application-dev.yml:36` |
| J-05 | major | CONFIRMED | Cash-only revealed only at the bottom of checkout; shop card, menu and basket say nothing | `11`, `12` |
| J-06 | minor | CONFIRMED | Confirmation email is 4 plain lines: no shop, items, total, payment method or address | `mail1.json` |
| J-07 | minor | CONFIRMED | Confirmation and tracking never list what was ordered | `14`, `15`, `19` |
| J-08 | minor | CONFIRMED | Search can't distinguish invalid, out-of-area and no-kitchens-near | `03b`, `03c` |
| J-09 | minor | CONFIRMED | Basket not restored on sign-in after sign-out | `s16` |
| J-10 | minor | CONFIRMED | Sign-out is a 14 px icon; one tap, no confirmation | `32` |
| J-11 | minor | CONFIRMED | No item customisation | `07` |
| J-12 | polish | CONFIRMED | Shop list uses gradients and initials, not photos | `02` |
| J-13 | polish | CONFIRMED | Descriptions repeat the name; Popular duplicated under Mains; "Co.."; "Draft" in the customer filter; 30-character order number | `05`, `20`, `27` |
| J-14 | polish | SUSPECTED | React hydration error #418 on /shop/orders | `s13` console |
| J-15 | polish | CONFIRMED | Cookie banner covers the first shop card | `01` |

## Goods to preserve
- A real guest checkout, with tracking by order number + email.
- Honest, consistent fees.
- Unit counts and totals agree on every surface (4/£20.00, 3/£30.49, 3/£17.00).
- Postcode search with real distances.
- Menu photos, dietary chips, the allergen panel.
- Sticky basket bar; "Add" turns into a stepper.
- The basket survives sign-up, and guest orders appear in My Orders.
- Sign-out clears the basket on a shared phone.
- Orders are placed instantly and the email arrives within seconds.

## Cleanup needed
These can't be cancelled from the customer side:
- Pending cash orders ORD-00000000-20261003-3FD79CC4, -B090D3F3 and -6CC30687.
- Customer jordan-60809@example.test.
