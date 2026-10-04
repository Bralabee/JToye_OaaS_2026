# Persona 11: Funmi, Friday rush (pass 2)

> The coordinator saved this from the agent's returned summary, because subagents are blocked from writing report files. Full detail is in findings.json.

**Funmi, Friday rush at Brixton Village Grill (P2-FUN): not ready for a Friday.** Once an order is confirmed it's quick and reliable. But nobody gets an alert for a new order, and the one note that matters ("nut allergy!") never reaches the person cooking. I wouldn't pay for it or recommend it yet.

**REPORT.md was not written.** The harness blocks subagents from writing report `.md` files, so the in-character narrative isn't on disk. Everything else is in `/home/sanmi/IdeaProjects/JToye_OaaS_2026/.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/`: `findings.json` (20 entries: 16 defects, 4 positives), the run logs, the scripts and the `shots/` screenshots. Continue this agent if you want the narrative text back.

**Scores:** ease 4, trust 3, speed 8, mobile 4, overall 4.

**Top findings:**
1. **P2-FUN-01, blocker, CONFIRMED (7 of 7 orders):** a new order makes no sound, notification or badge anywhere. The kitchen screen doesn't show an order until someone confirms it, so the only beep follows the vendor's own tap. Nobody notices orders, and unanswered ones stay Pending forever: there were still Pending orders from 11 and 21 days ago (FUN-14).
2. **P2-FUN-02, major (allergen safety), CONFIRMED:** the kitchen ticket leaves out the customer's note ("nut allergy!", "no pepper") and doesn't say collection or delivery. The note is only on the phone's order-detail page.
3. **P2-FUN-03, major, CONFIRMED:** a price change mid-basket charged £12.00 to a customer whose screen said £11.50, with no notice anywhere.
4. **P2-FUN-04, major, CONFIRMED:** a delivery customer's tracking page says "Ready for collection", while the email says it's being delivered.
5. **P2-FUN-05/06/07/08, major, CONFIRMED:**
   - Cancel is one unconfirmed tap with no reason. A refusal looks the same as a customer cancellation, and the email says "Previous status: PENDING".
   - There's no way to pause ordering. The only lever, opening hours, means saving Edit Shop, which changes the shop's public link (reconfirms ADE-03, from reading the code; I didn't save on the shared shop).
   - There's no search on Orders or Customers. A 13-minute-old order was already off page 1, and the phone table cuts off the status and action columns.
   - Finance has no per-day takings or date filter. Cash orders count as revenue while still marked "Unpaid", so the till can't be reconciled.
6. **Minor:** an 86'd item gives a bare error with no way to remove it from the basket, and the basket still lists it. There's no due time on tickets (reconfirms J-03). An 86 or price change takes 4 taps and about 30 s. Phone buttons are 32 px tall. The tablet layout wastes space and shortens the order number to "ORD…".

**Positives (worth keeping):**
- The phone orders list updates live, 1–5 s, with no refresh.
- Moving a ticket to the next stage on the tablet takes about 0.4 s, and the customer's tracking page and email follow within seconds.
- The server refuses an order containing an 86'd item.
- A tablet left on the kitchen screen for 38 minutes stayed live and picked up and processed a new order (order 8) 3 s after it was confirmed.

**Cleanup:** all 9 of my orders are Completed or Cancelled. Sweet Potato Fries are available and Peri Peri Chicken is back to £9.00, both checked on the public API at the end. Fries were off sale for 3 min 41 s: the first restore failed because my script reused an expired saved login.
