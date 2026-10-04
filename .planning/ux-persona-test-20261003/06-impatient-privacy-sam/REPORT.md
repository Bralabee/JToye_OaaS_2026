# Persona 06: Impatient & privacy-conscious Sam (saved by the coordinator from the agent's returned report)

> The harness refused the subagent's own Write, so the coordinator saved this from its returned report. The scripts and 38 screenshots are in this folder.
> **Coordinator root-cause note on F-01 (checked in the code):** `frontend/app/shop/[slug]/checkout/page.tsx:7` does `import { loadStripe } from "@stripe/stripe-js"`. Stripe documents that the package's default entry injects js.stripe.com as a side effect of being imported, and only `@stripe/stripe-js/pure` defers it. The guard at line 108 (`KEY ? loadStripe(KEY) : null`) therefore cannot stop the load: Stripe loads on EVERY checkout render, with or without a key, cash-only or not. The finding does not depend on this environment.

**Verdict:** "I tried to break it and couldn't make it double-order me. I'd order again, cautiously. But don't plant Stripe trackers on my cash order, and give me a delete-my-data button, not an email address."
**Would use:** yes. **Pay:** yes, cash. **Come back:** yes. **Recommend:** cautiously.
**The ONE thing that would change his mind:** a "Delete my account / download my data" button on My Orders that sends the verification link itself.

**Scores:**
- **Ease 7:** forms are forgiving and clear; losing the confirmation on refresh and the half-typed form hurt.
- **Trust 7:** honest legal pages and solid no-duplicate behaviour, dented by Stripe trackers on a cash order and email-only erasure.
- **Speed 6:** unthrottled localhost only, so not a mobile verdict.
- **Mobile 7:** works at 375px; truncated search placeholder; cookie notice covers about 40% of the first screen.
- **Overall 7.**

**No blocker.** No duplicate order on any path tested:
- triple-tap on "Place order";
- two tabs;
- offline at submit;
- a lost response after the server had accepted the order (the retry reused the same idempotency key and got the same order back).

The vendor view (admin-user, read-only) shows exactly the 6 deliberate orders. **Card double-charge, offline-during-card-payment and 3DS were BLOCKED by environment** (Stripe not configured), not passed.

## Persona
Sam, 52, on an iPhone SE (375px). In a hurry: fat-fingers forms, taps Pay repeatedly, hits Back, refreshes, leaves tabs open. Also reads the cookie banner and the privacy notice.
- **Account:** `sam-cadcb314@example.test` (jtoye-customers, display name "Sam 😤 Impatient").
- **Shop:** Mama Ade's Kitchen.
- **When:** 2026-10-03, about 21:40–22:10 BST.

## Environment notes (not findings)
- **:9091 outage (#671).** The first two checkout attempts fell in that window and were discarded. Re-run on the fixed stack: £3.50 delivery, total £22.99, allergens Crustaceans/Fish/Celery (`24-guest-checkout-fresh.png`).
- **Stripe not configured.** Everything was cash-only, so the impatience tests ran on the cash order-submit path.
- **Instrument artefacts.** Scripts that crashed left a stale basket or refresh token behind. Each was ruled out before recording anything.

## Journey (screenshots in this folder)
1. **/shop** (`01`):
   - Clean; about 3 s to load on unthrottled localhost.
   - The search placeholder is cut off at "…or your…".
   - The cookie notice says "strictly necessary only, nothing to accept or reject". Before checkout the only cookies were `authjs.csrf-token` and `authjs.callback-url`.
2. **Legal** (`02`, `03`, `03b`): the cookie policy lists every item and says "no analytics, no pixels". The privacy notice explains joint controllership and single-use-link verification for erasure. The retention schedule labels each period Automated or Operational.
3. **Register** (`06`, `07`, `08`): every error is in plain English and next to its field ("Invalid email address", "minimum length 12", the mismatch message, "Maximal length is 255"). The emoji in the first name was accepted.
4. **Triple "Add"** (`09`): after the first tap the button becomes a stepper, so the result was quantity 1, not 3.
5. **Checkout validation** (`12`, `13`):
   - Clear errors for the address and for postcode "zz1".
   - It accepted the email `…@example` (no TLD) and the phone `12`.
   - `se155bs` was accepted and shown as `SE155BS`; `+44 7700 900 123` was accepted.
   - 500 characters of notes with emoji were accepted.
   - The allergen acknowledgement is enforced.
6. **Triple-tap "Place order"** (`16`, `17`): exactly 1 POST and 1 order (27082946).
7. **Refresh the confirmation** (`18`): the URL is still /checkout, so it shows "Nothing to checkout". The order number is gone and there's no link to My orders. No second order was created.
8. **Back then Forward** (`20`): the basket is kept, but the address, phone and notes are wiped.
9. **Two tabs:** tab 2 updates itself to "Nothing to checkout", so no duplicate is possible.
10. **Offline at submit** (`26`, `27`): "Failed to place order. Please try again." with no mention of being offline. Taps while offline sent nothing. Back online, one retry gave one order (7BF67717).
11. **Lost response after the server accepted** (`28`, `29`): the retry sent the same idempotency key (dfed4d90…) and got the same order number (888A1F58). No duplicate.
12. **My Orders** (`30`): 6 orders. The status filter offers "Draft". A customer can't cancel.
13. **Vendor cross-check** (`31`): the same 6 orders, no duplicates, the emoji renders. The vendor sees "Pending" where the customer sees "Received".
14. **Session lapse:** deleting the access cookie → silently refreshed. Clearing all cookies → basket kept, "Sign in" shown (`35`).
15. **Sign-out** (`36`): the basket and customer cookies are cleared. Still on the device: `jtoye-checkout-email-…` (disclosed), `jtoye-guest-orders` (holds the order number AND the email), and the Stripe cookies.
16. **Stripe on a cash-only checkout:** browse, shop page and basket set no third-party cookies. /checkout loads js.stripe.com and m.stripe.network, POSTs m.stripe.com/6, and sets `__stripe_mid` (1 year), `__stripe_sid` and `m@m.stripe.com`, while the page says "No payment is taken online". Confirmed twice (s12, s13).
17. **Marketing:** only transactional emails arrived, from noreply@jtoye.uk, with no items, shop name or total. Marketing opt-in was never offered, so unsubscribe couldn't be tested. `/unsubscribe` with no token shows a clear page (`33`).
18. **DSAR:** the only route is `mailto:privacy@olajay.co.uk` (`38`). There's no account or data page in the signed-in UI (`37`). Not sent, because it's a real external mailbox, so this is BLOCKED. On paper the process is clear, but it's far harder than signing up.

## Expectations vs reality
| Expected | Got | Felt |
|---|---|---|
| Charged once however many taps | One order for triple-tap, two tabs, offline retry and lost-response retry (same key, same order); card path BLOCKED | Relieved |
| Back never loses my basket | Basket kept; half-typed fields wiped | Mostly fine |
| Errors say how to fix it | Validation excellent; network errors vague | OK; would worry with a card |
| No to cookies means no | True while browsing; Stripe trackers with a 1-year cookie on a cash checkout | Let down |
| Deleting data is as easy as giving it | Email, verification link, up to a month | No |
| Refreshing the confirmation is safe | No duplicate, but the confirmation vanishes | Panic for a second |

## Findings
| ID | Severity | Status | Finding | Evidence |
|---|---|---|---|---|
| F-01 | major | CONFIRMED | Stripe JS and fraud telemetry load on a CASH-only checkout and set `__stripe_mid` (1 year, survives sign-out), `__stripe_sid` and m.stripe.com cookies. Contradicts the cookie policy's "on the payment step". | s12, s13, `24` |
| F-02 | major | CONFIRMED | No self-serve data access or erasure: mailto only, no web form or account page, although a backend intake exists | `38`, `37` |
| F-03 | major | CONFIRMED | Refreshing the confirmation loses it ("Nothing to checkout", no order number, no My orders link), so an impatient user may re-order | `17`, `18` |
| F-04 | minor | CONFIRMED | A customer cannot cancel an order | `30`, `34` |
| F-05 | minor | CONFIRMED | Cookie policy not accurate: `jtoye-guest-orders` is written for signed-in orders, holds the email and survives sign-out; `jtoye-customer-last-signin` (customer UUID) not listed | s5/s19 |
| F-06 | minor | CONFIRMED | Network failure says only "Failed to place order. Please try again.", with no "you're offline" and no "it may already be placed" | `26`, `28` |
| F-07 | minor | CONFIRMED | Checkout accepts email `name@example` and phone `12` | `13` |
| F-08 | minor | CONFIRMED | Back then Forward wipes the address, phone and notes | `20` |
| F-09 | polish | CONFIRMED | "Draft" filter for customers; Received vs Pending mismatch; truncated placeholder; "E2E 20% OFF" test promo visible | `30`, `31`, `01` |
| F-10 | minor | SUSPECTED (env-induced) | With the API unreachable, checkout showed "Delivery Free", a lower total and "allergen info not recorded" instead of an error: it fails open on price | `12` |
| F-11 | polish | CONFIRMED | Two domains (jtoye.uk sender vs olajay.co.uk privacy contact); the email omits the shop name and total | Mailhog dump |

## Goods to preserve
- **Idempotency:** a lost response followed by a retry returns the same order.
- **"Add" turns into a stepper,** so frantic taps don't add three.
- **Tabs stay in sync,** so a stale tab can't place a second order.
- **Plain-English validation** next to each field.
- **Forgiving postcode and phone formats.**
- **The basket survives a lapsed session,** and the token refreshes silently.
- **Honest legal pages:** item-by-item cookie list, the retention schedule.
- **Allergen acknowledgement gate.**
- **Sign-out really clears** the basket and customer cookies.

## Cleanup needed
Customer `sam-cadcb314@example.test`, plus 6 cash orders at Mama Ade's Kitchen, all Received/Pending:

ORD-00000000-20261003-{BB9D0024, 57EB73A4, 27082946, 40C5FC61, 7BF67717, 888A1F58}

They need cancelling from the vendor side. No DSAR was lodged.
