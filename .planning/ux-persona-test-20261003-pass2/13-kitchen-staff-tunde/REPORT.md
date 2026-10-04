# Pass 2 — Persona 13: Tunde, kitchen hand (code TUN)

## Persona
- **Who:** Tunde, 19. Part-time kitchen hand at Brixton Village Grill (tenant A). Today is his first shift and nobody has trained him.
- **Account:** the shop gave him `tenant-a-user`, which is meant to be a non-admin account.
- **Devices:**
  - The shared kitchen tablet, which stays signed in all day. Tested at 768×1024 portrait and 1024×768 landscape, with touch.
  - His own cheap Android, tested at 360×740 on about 400 kbps with 400 ms latency and 4× CPU throttle.
- **What he came to do:** see what to cook, move tickets along, and not break anything.
- **What he should not see:** money, or customer data beyond what he needs to cook.

## Journey
1. **First sign-in** (`shots/01-first-landing-768.png`).
   - He lands on **Dashboard**, not the kitchen. That page shows "81 orders", a finance card that fails with 403, and a recent-orders list with customer names and emails.
   - The sidebar offers Finance, Approvals, Staff and Webhooks. RECONFIRMS ADE-13.
   - He finds **Kitchen** in the sidebar easily; the icon and label are good.
2. **Empty kitchen** (`shots/02-kitchen-768.png`).
   - The board shows a green "Live" pill, a clock, and "No active orders — Orders will appear here when customers place them".
   - At that moment the Orders page held several Pending orders, about an hour old.
3. **A guest places a cash order with a note** ("severe peanut allergy please") from a separate browser context.
   - I watched the board for 70 s and **the order never appeared** (`shots/03-kitchen-after-guest-order.png`).
   - It sat as **Pending** on the Orders page (`shots/04-orders-page-768.png`). At 768 px the Confirm and Cancel buttons are off the right edge of the table.
   - Three minutes later, with other testers' traffic, it had slid to **page 2** ("Showing 21-40 of 109").
   - So a cash order reaches the kitchen only after someone opens Orders, finds it and taps **Confirm**. Nothing tells Tunde that.
4. **Confirmed orders on the board** (`shots/06-kitchen-1024-tickets.png`, `shots/21-tablet-768-with-tickets.png`, `s12-tablet-layout.out`).
   - The ticket appeared about 1 s after Confirm, with a strong brown **ALLERGENS** banner and allergen chips on each line. Great.
   - But:
     - Every ticket's number reads **"ORD-…"**. The unique tail (for example "70311A26") is cut off.
     - **The customer's note is not on the screen.** The printed ticket has it; the screen card doesn't.
     - Delivery and collection look identical.
     - The newest ticket comes first, so the one that has waited longest sinks to the bottom.
5. **Wet hands** (`shots/07-after-double-tap.png`, `shots/22-phone-after-doubletap.png`, `mail-doubletap-order1.json`, `s13-doubletap-repeat.out`).
   - One clumsy double-tap (250–300 ms apart) on **Start Preparing** sent the order straight to **READY**. Same result 3 times out of 3, on the tablet and the phone.
   - The customer got "Being Prepared" and "**Ready!** … please pick it up" emails in the same second, while nothing had been cooked.
   - There is no undo.
6. **What his token can actually do** (`s2-api-probe-read.out`, `s7-api-mutations.out`).
   - `/api/v1/staff/me` returns `groupAdmin:true`. He is a tenant-wide admin, granted automatically at first login.
   - With his token I:
     - created a product, repriced it to 1p and deleted it;
     - created an **outbound webhook sending every order event to an outside URL** (a delivery was attempted before I revoked it);
     - granted and revoked a staff role;
     - created and deleted a customer;
     - listed every customer with email and phone.
   - From reading the code (not executed), he can also hard-delete orders.
   - Refunds, finance, GDPR export and onboarding approvals correctly return 403.
7. **The all-day tablet's session lapses** (`s6-session-lapse.out`, `shots/09-lapse-t0.png`, `shots/09-lapse-final.png`).
   - I ended only this tablet's own Keycloak session, which is what the realm's maximum session lifespan does on its own.
   - The board kept working (orders seen in about 15 s) until its 5-minute access token expired.
   - Then the next poll got a 401 and the page **jumped to the generic "Vendor sign in" screen**. No sound, no "you were signed out, the kitchen board has stopped".
   - The order placed after that (`…7E355E89`) was never seen; the tablet sat on the sign-in page for 4+ minutes.
   - Signing back in lands on Dashboard, not Kitchen.
   - The good part: it never froze while looking "Live".
   - Unverified: the realm template sets a 2-hour session maximum. I did not read the live value.
8. **Network drop** (`s9-offline.out`, `shots/16-offline-5s.png`, `shots/17-back-online.png`, `shots/18-api-down-100s.png`, `shots/19-api-back.png`).
   - **Wi-Fi off for 60 s:** an "Offline — no live order feed" banner within 5 s. The order placed while offline appeared **1 s** after reconnect. Excellent.
   - **Wi-Fi up but the API unreachable** (the more common case with a flaky router):
     - the live socket announced the new order, but the board couldn't load it and dropped it silently;
     - the pill still said **Live** for about 50 s;
     - at about 58 s it switched to "Orders are not refreshing";
     - the order showed 8 s after the API came back.
9. **End of shift** (`s10-signout-handover.out`, `shots/12-after-signout.png`, `shots/13-back-after-signout.png`, `shots/14-kc-form-next-person.png`, `shots/15-next-person-kitchen.png`).
   - Sign-out is real. The next person faces a Keycloak form with no username filled in and must type a password, and Back shows only the sign-in page.
   - **But Tunde's mute stays on the tablet.** For the next person the speaker icon says sound is **ON** ("Mute alerts") while new orders make **no beep** (`s11-mute-truth.out`, `s11b-mute-repeat.out`, `shots/20-mute-pretrue.png`).
   - One tap leaves the icon unchanged and actually unmutes. Reproduced twice; the unmuted control arm beeped correctly.
10. **His own phone** (`shots/10-phone-360-kitchen.png`, `shots/10b-phone-360-kitchen-full.png`).
    - No horizontal scroll, and the bottom tab bar has Kitchen.
    - On throttled Wi-Fi the heading came in about 3 s and the tickets in about 14 s.
    - The header, the "All shops" info box and the status pill push the first ticket down to y≈570 of 740 px.
    - The Customers page on his phone lists every customer's email and phone (`shots/11-phone-customers.png`).

## First impressions, trust, clarity, speed, mobile
- **First 10 seconds:** "Why am I looking at order counts and customer emails? Oh, Kitchen. It says Live and nothing to cook. Cool." That calm is the dangerous part, because orders were waiting.
- **Trust:** the offline handling earns it. The silent drop to a sign-in page, the lying speaker icon and the double-tap skip lose it.
- **Clarity:**
  - Allergens are the clearest thing on the screen.
  - Order numbers are unreadable.
  - Notes are missing.
  - The Pending → Confirm step on another page is invisible to a newcomer.
- **Speed:**
  - About 1 s from Confirm to ticket.
  - About 1 s catch-up after going offline.
  - About 14 s on the phone with bad Wi-Fi.
- **Arm's length:**
  - The allergen banner (18 px bold on brown) is readable.
  - Line items at 14 px are not.
  - At 1024×768 not one full ticket fits above the fold; the first bump button sits at y=716.
  - A 256 px admin sidebar is always on screen.

## Expectations vs reality
| Expected | Got | How it felt |
|---|---|---|
| New orders pop up on the kitchen screen | Only after someone confirms them on the Orders page; the board says they "will appear when customers place them" | Lied to, by a calm green "Live" |
| Can't mess up with wet hands | A double-tap jumps to READY and emails the customer "Ready!"; no undo | Scary: the customer walks in for food that isn't cooked |
| See the customer's request ("peanut allergy") | Only on the printed ticket, not on screen | I'd have missed it |
| Shout out an order number | Every ticket says "ORD-…" | Have to read names instead |
| Kitchen-only access | Tenant-wide admin: staff, webhooks, products, customers | "I could change the prices." |
| Board tells me if it stops | Offline: yes, instantly. API down: about 1 min late. Session lapse: silently becomes a sign-in page | Mixed |
| My settings leave with me | Mute stays, and the icon lies to the next person | Next shift misses orders |
| Only see what I need to cook | Customer emails, phones, addresses, all shops' orders | Uncomfortable |

## Findings
| ID | Sev | Status | Finding | Evidence |
|---|---|---|---|---|
| P2-TUN-01 | major | CONFIRMED | New cash orders never reach the kitchen board, which shows confirmed orders onward only, while it claims "orders will appear when customers place them". At 768 px the Confirm button is off-screen, and orders slide to page 2 under load | `shots/03`, `shots/04`, `s3-order-flow.mjs` |
| P2-TUN-02 | major | CONFIRMED ×3 | A double-tap on Start Preparing skips to READY and emails "Ready!" in the same second; no undo | `shots/07`, `shots/22`, `mail-doubletap-order1.json`, `s13-doubletap-repeat.out` |
| P2-TUN-03 | major | CONFIRMED | The customer's order note (allergy or request) is not shown on the kitchen screen card, only on the printed ticket | `shots/06`, `s4-confirm-and-board.mjs` |
| P2-TUN-04 | major | CONFIRMED | Order number truncated to "ORD-…" on every ticket at 360, 768 and 1024 px | `shots/06`, `shots/10`, `shots/21-*`, `s12-tablet-layout.out` |
| P2-TUN-05 | major | CONFIRMED | The "non-admin" staff login is a tenant-wide admin: products, webhooks, staff and customers are all writable through the API. RECONFIRMS ADE-13 (menu); the API reach is new | `s2-api-probe-read.out`, `s7-api-mutations.out` |
| P2-TUN-06 | major | CONFIRMED (2 h setting SUSPECTED) | On the all-day tablet a session lapse silently replaces the board with the sign-in page; later orders go unseen; re-login lands on Dashboard | `s6-session-lapse.out`, `shots/09-*` |
| P2-TUN-07 | major | CONFIRMED ×2 | Mute survives sign-out, and the speaker icon then shows sound on while new orders stay silent | `s10-signout-handover.out`, `s11-mute-truth.out`, `s11b-mute-repeat.out`, `shots/20-mute-pretrue.png` |
| P2-TUN-08 | minor | CONFIRMED | Newest-first sort hides the longest-waiting ticket below the fold | `shots/21-*`, `s12-tablet-layout.out` |
| P2-TUN-09 | minor | CONFIRMED | With Wi-Fi up but the API unreachable, the board shows "Live" for about 50 s while an announced order is dropped | `s9-offline.out`, `shots/18`, `shots/19` |
| P2-TUN-10 | minor | CONFIRMED | A kitchen hand sees far more personal data than needed: emails on the landing page, all customers, and phone and address in the kitchen data | `shots/01`, `shots/11`, `s7-api-mutations.out` |
| P2-TUN-11 | minor | CONFIRMED | Delivery and collection look identical on the screen ticket | `s4-confirm-and-board.mjs` |
| P2-TUN-12 | polish | CONFIRMED | Hard to read at arm's length on a tablet (details below) | `shots/21-*`, `shots/06`, `shots/10` |

P2-TUN-12 in detail:
- line items are 14 px;
- a permanent 256 px sidebar at 768 and 1024 px;
- an "All shops" info box appears on every load;
- the "ALLERGENS" heading is clipped at the card edge;
- the first bump button is below the fold at 1024×768.

## What delighted me (goods to preserve)
- **Honest offline handling:** an "Offline — no live order feed" banner with the last-updated time within seconds, and a 1 s catch-up on reconnect.
- **Allergen banner and per-line chips.** The one thing nobody can miss.
- **Sign-out really signs out.** The next person must type a password, and Back doesn't bring the old board back.
- **The money and GDPR wall holds** for non-admins: refund, finance, GDPR export and approvals all return 403.
- **About 1 s from Confirm to ticket**, with a beep for new orders when not muted.
- **The phone works:** no horizontal scroll, a Kitchen tab in the bottom bar, 44 px bump buttons.
- The "Not updating / Refresh now" banner, once it does appear, is clear and tells you how stale the board is.

## Verdict (in character)
"Honestly the screen looks proper: big allergen warning, says Live. That's why it's dangerous. It sat there saying 'nothing to cook' while people's orders were waiting on another page I didn't even know about. My thumb was wet, I tapped twice, and the customer got told her food was ready when I hadn't started it. And there's no way back. After lunch the tablet was just sitting on a login page and nobody noticed, because it didn't make a sound. Then the next guy thought the sound was on, because the little speaker said so. And somehow I can change the prices and add staff? I'm the kitchen hand, bro. I'd use it because the boss makes me, but I wouldn't trust it on a Friday night.

**The one thing that would change my mind:** put every new order on the kitchen screen the moment it comes in, with a number I can shout and the customer's note, and make the board scream if it ever stops."

## Scores
- **Ease: 4/10.** The kitchen is easy to find, but the hidden Pending → Confirm step, the truncated numbers and the missing notes trip up an untrained hand.
- **Trust: 4/10.** Offline handling is honest, but the silent sign-out, the lying mute icon and the double-tap skip each break a promise.
- **Speed: 7/10.** About 1 s from Confirm to ticket and 1 s catch-up after offline; about 14 s on bad phone Wi-Fi.
- **Mobile: 6/10.** Works at 360 px with no scroll, but tickets start below the fold and the text is small at arm's length.
- **Overall: 4/10.** A good-looking, nearly-right kitchen board with several real-service traps.

## Cleanup
- All 12 orders I created at Brixton Village Grill are now COMPLETED (`s99-cleanup.out`).
- Test product `TUN-TEST-1` is deleted, webhook `41331306…` is revoked, the test customer is deleted, and the self-granted STAFF row is revoked.
- The saved session state and token scratch files are removed.
