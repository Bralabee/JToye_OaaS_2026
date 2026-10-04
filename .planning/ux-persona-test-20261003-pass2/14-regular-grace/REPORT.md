# Persona 14 (pass 2): Grace, the Sunday regular

## Persona
Grace is 68, a retired nurse in Lewisham. Every Sunday she orders the same jollof rice and fried plantain from Mama Ade's Kitchen.
- **Device:** a 5-year-old Android with the system font set to LARGE. I emulated this with a 360×740 viewport at DPR 2, an Android Chrome user agent, and `html{font-size:130%}`; the measured root font was 20.8 px.
- **Network:** Slow 3G at home (400 kbit/s, 400 ms RTT) for the arrival and the timed repeat order. I used 4G for the other steps to save time.
- **Habits:** her granddaughter set up her email. She prefers cash, distrusts typing card details, and forgets passwords.
- **Goal:** register, order her usual, re-order it the next week, cope with a forgotten password, leave a review, find out "where is my food?", understand what she agreed to about emails, then close her account and ask for her data.

**Account:** `grace-p2-65308@example.test`.
**Orders:** ORD-00000000-20261003-45B3ECD7 (completed) and ORD-00000000-20261003-ECFB3041 (cancelled at clean-up).

## Journey narrative

1. **Week 1: arriving from the WhatsApp link** (`s01`; `01`, `02`, `03`).
   - On Slow 3G the shop's title appeared at 2.8 s, but the page took 29.9 s to finish loading.
   - At her text size the banner reads **"Mama Ade's Ki…"**, so the name of the shop she trusts is cut off.
   - The header is 4 px wider than the screen: the menu icon is clipped and "Sign in" wraps onto two lines.
   - The cookie box covers half the screen until she taps "Got it".
2. **Registering** (`s02`; `04`, `05b`, `06`).
   - She taps Sign in, then Create an account. The Keycloak form asks for email, password twice, first name and last name.
   - The form shows no password rules, no link to the privacy notice, no terms and no marketing question.
   - She lands straight back on the shop, signed in. No welcome or verification email arrives.
   - When I tried a plain password, the rules appeared one at a time, only after a failure ("must contain at least 1 special characters"), and both password boxes were wiped (`40`).
3. **Ordering her usual** (`s03`–`s05`; `07`–`13`).
   - Add Jollof, Add Plantain and View basket all worked.
   - **In the basket the names read "Jol…" and "Fri…"** (`09`). At her text size she cannot check what she is buying.
   - At checkout her name and email were already filled in. The payment line says "Pay on delivery — cash to the driver. No payment is taken online." That is exactly what Grace wants.
   - She typed 94 characters: address, postcode, phone, and the note "Please ring the bell and wait, I am slow to the door". She ticked the allergen box and placed the order.
   - **The screen then showed only the dark footer** (`13`). "Order confirmed!" was about 900 px further up, off-screen, and focus stayed on `<body>`. Grace would think nothing happened.
4. **The confirmation email** (`mail-week1.json`).
   - It has four lines: the order number, "the shop will confirm it shortly", and a tracking link.
   - It gives no shop name, no items, no total and no "cash on delivery".
5. **Week 2: a new browser, "I want my usual"** (`s06`–`s08`; `14`–`20`).
   - The menu offers only "Shops" and "My Orders".
   - My Orders shows "Mama Ade's Kitchen · 2 items · £15.99", without the dish names.
   - Tapping the order opens tracking. There is **no "Order again" button, no favourites, and her address and phone are not remembered**.
   - I timed it on Slow 3G from the WhatsApp link: **10 taps, 85 typed characters, 83 s**. The taps were the cookie box, two sign-in taps, sign-in submit, two Adds, basket, checkout, the allergen tick and Place order. The typing was email, password, address, postcode and phone.
   - She types her address, postcode and phone (42 characters) again every week.
   - **The confirmation was off-screen again:** scrollY was 1104 and the heading sat at −895 px (`20`).
6. **"Where is my food?"** (`s09`; `21`–`24`).
   - **Signed in:** /track says "Signed in — we found this one for you. No order number needed." This is lovely.
   - **At large text:** the progress tracker runs off the screen. "Completed" is cut off and the page scrolls sideways (423 px wide on a 360 px screen). This happened on both orders.
   - **Signed out, or on her granddaughter's phone:** /track only asks for the order number and email, with no "sign in to see your orders" option. The tracking link in the email fills in the number, but she still has to type her 27-character email.
   - **No page ever says when the food will arrive.**
   - An email came at every step. The "Ready" email said "no need to come to the shop — we'll deliver it", which is right for a delivery.
   - To produce those updates I moved her orders on through the vendor API, acting as the kitchen. Grace did not do this.
7. **Leaving a review** (`s10`–`s12`; `25`, `26`, `27b`).
   - Once the order was Completed there was **no review or rating button anywhere**: not in My Orders, not on tracking, and not in the "Completed" email.
   - The only way in is `POST /api/v1/public/shops/{slug}/reviews?email=…`. It needs the internal order ID, which no customer screen shows, and it **needs no sign-in**.
   - I posted her review through the API. It appeared at once on the shop page with five stars, **under her full name, "Grace Persona14"** (`27b`).
8. **Forgot password** (`s13`, `s15`; `28`–`33`, `36`).
   - Wrong password, then "Forgot Password?" (her email was already filled in), and the email arrived within seconds. She set a new password and landed straight back on the shop, signed in. This part is good.
   - The email says **"This link and code will expire within 5 minutes"**, uses the word "credentials", and comes from `no-reply@jtoye.local`.
   - I waited 6 minutes, as if her granddaughter read it later. The link then said **"Action expired. Please start again."** (`36`).
9. **Email preferences and marketing** (`s14`; `35`).
   - She was never asked anything about emails, and there is no preferences page. `/account` returns 404, and `/shop/account` shows a soft "Shop not found".
   - The privacy notice has **no marketing section at all**.
   - `/unsubscribe` opened without a link token says "Contact the vendor to update your email preferences", but no shop publishes contact details.
   - In practice she receives no marketing, which is good, but she cannot find out what she agreed to.
10. **Closing the account and asking for her data** (`s14`, `s18`, `s19`; `41`, `42-*`, `43`).
    - **Self-serve:** there is nothing. The privacy notice says to write to `privacy@olajay.co.uk`, and the Keycloak delete-account option is turned off.
    - **Lodging the requests:** acting as the privacy inbox would, I lodged a copy-of-data request (ACCESS) and a deletion request (ERASURE) for her email through `/api/v1/public/gdpr/dsar`.
    - **The confirmation links:** both emails link to `localhost:8080`, where nothing is listening, so the connection is refused. Pointed at the core service instead, the link shows **raw JSON**.
    - **Deletion did nothing:** the worker then logged `dsar_fanout_completed tenantsErased=0 tenantsScanned=2`. Deletion only looks at stored customer records, and placing a storefront order creates none. Tenant A has 7 customer records and none belongs to Grace, Jordan, Sam or Priya.
    - **What survives:** her name, email, phone and "I am slow to the door" note are **still on the order**, and her **full-name review is still public**. She can still sign in and see both orders (`43`), and no "done" email was ever sent.
    - **The copy-of-data request is never carried out:** every sweep logs "ACCESS delivery is not implemented".

## First impressions (first 10 seconds)
The site feels warm and food-first, it is easy on the eye, and the photos look like the food she knows.

But at her text size the first thing she reads is "Mama Ade's Ki…". Then a big dark cookie box covers half the screen. On Slow 3G the words came quickly (2.8 s), but the full page took 30 s.

## Trust, clarity, speed, mobile feel
- **Trust:** "Pay on delivery — no payment is taken online" won her over; she will never type a card here. That trust was lost when she pressed Place order and got a blank dark screen, and the emails never name the shop.
- **Clarity:** the order screens are simple. But the basket says "Jol…" and "Fri…", My Orders says only "2 items", and nothing says when the food will come.
- **Speed:** a repeat order takes 83 s and 85 keystrokes on Slow 3G. That is fine for a first order and tiring when it is the same every week.
- **Mobile at large text:**
  - Broken: the basket names, the confirmation, the tracker and the shop name.
  - Fine: the checkout, the product detail sheet and the menu all fit.
  - The sign-out button is a tiny unlabelled 18 px icon beside a green "person" circle that does nothing when tapped.

## Expectations vs reality
| Expected | Got | How it felt |
|---|---|---|
| Pay cash | "Pay on delivery — cash to the driver" | Relieved |
| See what's in my basket | "Jol…", "Fri…" | Squinting, unsure |
| "Thank you, your order is placed" | A dark footer; the message is off-screen above | "Did it go through? Shall I ring them?" |
| "Order my usual again" | No reorder, no favourites, address not saved | "The same typing every week" |
| Know when it will arrive | Status words only, no time | Rings her granddaughter |
| Reset my password when I get to it | The link died after 5 minutes | Locked out until someone helps |
| Leave a review on my order | No button anywhere | "Where do I write it?" |
| My first name or initials on a public review | Full name published | Exposed |
| "Delete my account" in settings | No settings at all; email an olajay.co.uk address | Confused: "who is olajay?" |
| My data is deleted after I confirm | Marked done with nothing deleted; the copy of her data never sent | She has no way to know, which is the problem |

## Findings
Full step-by-step entries are in `findings.json`.

| ID | Sev | Status | Finding | Evidence |
|---|---|---|---|---|
| P2-GRA-01 | blocker | CONFIRMED | A confirmed deletion request finishes with `tenantsErased=0` for a storefront customer. Her name, email, phone, note and public review all survive, and no "done" email is sent. | core log, `43`, `s19`, vendor order read-back |
| P2-GRA-02 | major | CONFIRMED | A confirmed copy-of-data request is never carried out ("ACCESS delivery is not implemented"). RECONFIRMS F-02. | core log |
| P2-GRA-03 | major | CONFIRMED | No self-serve account closing or data request: no account page, Keycloak delete turned off, mailto only. RECONFIRMS F-02. | `39`, `privacy-notice.txt` |
| P2-GRA-04 | major | CONFIRMED | After Place order at large text the screen shows only the footer; the confirmation is off-screen (seen twice). | `13`, `20`, s08 output |
| P2-GRA-05 | major | CONFIRMED | No reorder or favourites, and address and phone are not remembered: 10 taps, 85 characters and 83 s every week. | `17`, `18`, `19`, s08 output |
| P2-GRA-06 | major | CONFIRMED | Customers cannot leave a review from any screen; the API needs an order ID customers never see. | `25`, `26`, s10 output |
| P2-GRA-07 | major | CONFIRMED | A review publishes the customer's full name, with no notice and no choice. | `27b`, `review-post.json` |
| P2-GRA-08 | major | CONFIRMED | The password-reset link expires in 5 minutes; opened at 6 minutes it no longer works. | `36`, `mail-reset.txt` |
| P2-GRA-09 | major | CONFIRMED | At large text the basket cuts dish names to "Jol…" and "Fri…". | `09` |
| P2-GRA-10 | minor | CONFIRMED | At large text the order tracker runs off the screen and the page scrolls sideways (423 vs 360 px). | `18`, `24` |
| P2-GRA-11 | minor | CONFIRMED | At large text the shop name is cut off, the header spills 4 px off-screen, and "Sign in" wraps. | `02` |
| P2-GRA-12 | minor | CONFIRMED | The green "person" icon is decoration (labelled "Storefront"). The only account control is an unlabelled 18 px Sign-out. RECONFIRMS J-10. | `17`, s16 output |
| P2-GRA-13 | minor | CONFIRMED | The link to confirm a data request points at `localhost:8080`, which refuses connections on the local stack. Pointed at the right port, it shows raw JSON. | `41`, `42-*` |
| P2-GRA-14 | minor | CONFIRMED | The privacy notice says the shop is named "again on your order confirmation", but neither the page nor the email names it. RECONFIRMS J-06, F-11. | `13b`, `mail-week1.json`, `privacy-notice.txt` |
| P2-GRA-15 | minor | CONFIRMED | Email consent is invisible: never asked, no preferences page, no marketing section in the privacy notice, and `/unsubscribe` says "contact the vendor". | `35`, `privacy-notice.txt` |
| P2-GRA-16 | minor | CONFIRMED | Password rules (12+ characters including a symbol) stay hidden until she fails, appear one at a time, and both boxes are cleared. No privacy link at sign-up. | `05b`, `40` |
| P2-GRA-17 | minor | CONFIRMED | Signed out, /track offers no way to sign in, and the email link still needs her email typed. No arrival time anywhere. RECONFIRMS J-03. | `21`, `22`, `24` |
| P2-GRA-18 | minor | CONFIRMED | Posting a review needs no sign-in: the customer's email in the URL plus the order ID is enough. | `review-post.json` |
| P2-GRA-19 | polish | CONFIRMED | Emails come from two addresses (`noreply@jtoye.uk` and `no-reply@jtoye.local`), the reset email says "credentials", and order emails never name the shop or the items. RECONFIRMS J-06, F-11. | `mail-final.json` |
| P2-GRA-20 | polish | CONFIRMED | React error #418 now also appears on /track, and "Auto-refreshing" shows on a finished order. RECONFIRMS J-14. | s07, s10 output |

## What delighted Grace (goods to preserve)
- **Cash on delivery, stated plainly** at checkout: "No payment is taken online".
- **Tracking without the order number when signed in:** "we found this one for you".
- **An email at every step,** and the Ready email knows it's a delivery.
- **Name and email already filled in** at checkout.
- **The product detail sheet fits at 130% text:** Close stays visible and Escape closes it.
- **No sideways scrolling** on the checkout or the basket.
- **Forgot password fills in her email** and returns her to the shop, signed in.
- **Her review appears instantly** with stars.
- **No unwanted marketing emails.**
- **Data requests are confirmed by email first,** and the reply doesn't reveal whether any data is held.

## Verdict (in character)
"The food is right and I can pay the driver in cash, God bless them for that. But every Sunday I type my address again as if I've never ordered before, and the little writing in the basket says 'Jol…'. When I pressed the button it all went dark, and I had to ring my granddaughter to ask if it had worked.

The password link had expired before she'd even read me the email. And when we asked them to delete me, my name was still there on the shop page with my review. I'll keep ordering because it's Mama Ade's. But if Just Eat had a big 'Order again' button, I'd be gone."

**The one thing that would change her mind:** an "Order my usual again" button that remembers her address and phone and asks only "Cash on delivery? Yes."

## Scores (1–10)
- **Ease: 4.** The first order was fine, but the repeat is as hard as the first, and there is no reorder, review or account page.
- **Trust: 4.** Cash on delivery reassures her, but the blank screen after ordering and the deletion that deleted nothing undercut it.
- **Speed: 5.** Words appear in 2.8 s on Slow 3G, but the page takes 30 s to load fully and a repeat order takes 83 s.
- **Mobile / large text: 4.** The checkout fits, but the basket, confirmation, tracker and shop name break at 130%.
- **Overall: 4.**

## Cleanup
- **Account:** customer `grace-p2-65308@example.test` (Keycloak realm jtoye-customers; the password is in `password.txt`). It is still active: there is no self-serve way to close it, and the deletion request did not remove it.
- **Order 1:** ORD-00000000-20261003-45B3ECD7 is COMPLETED. I moved it through the vendor API as the kitchen.
- **Order 2:** ORD-00000000-20261003-ECFB3041 was CANCELLED by me through the vendor API. I used the tenant A admin account, on my own order only.
- **Review:** `b2e8cff0-3b9b-40b1-ba42-d7de4b218a2f` is still public on Mama Ade's Kitchen, signed "…persona test P2-GRA". There is no way to delete it, and the deletion request did not anonymise it (P2-GRA-01), so an operator needs to remove it.
- **Data requests:** two `dsar_request` rows for her email remain. The copy request is still outstanding; the deletion is marked "completed" with nothing deleted.
- **Persona folder:** the `state-*.json` files hold this test account's session cookies. Everything else (scripts, screenshots 01–43, mail dumps, `findings.json`) is in `/home/sanmi/IdeaProjects/JToye_OaaS_2026/.planning/ux-persona-test-20261003-pass2/14-regular-grace/`.
