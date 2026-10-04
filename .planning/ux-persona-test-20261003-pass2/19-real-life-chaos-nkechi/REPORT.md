# Persona 19: Nkechi, things change mid-journey (pass 2)

> The coordinator saved this from the agent's returned summary, because subagents are blocked from writing report files. Full detail is in findings.json.

Nkechi wouldn't trust J'Toye with the office lunch. The site was charged a higher price than it showed her, with no warning, and the allergen list she ticked is not the list stored on the order. Double-taps on a bad connection were handled perfectly.

**Scores:** ease 7, trust 3, speed 6, mobile 6, overall 4.

**Top findings** (all CONFIRMED; 19 entries in `findings.json`):
1. **P2-CHA-01, major (reproduced twice): a price rise mid-checkout is charged silently.** The vendor raised a price while she sat on checkout. The button said "Place order · £188.50" and the confirmation said £198.50; on the phone it was £19.00 shown and £21.00 charged. The basket keeps the price from when the item was added, and the email has no total (reconfirms J-06), so she has nothing in writing at the door.
2. **P2-CHA-02, major (twice): she acknowledged one allergen list and the order records another.** She ticked Gluten, Fish, Peanuts; the vendor then edited a product and the stored order says Gluten, Eggs, Fish, Peanuts, Milk. Second run: Sesame was added after her tick. The checkout's allergen panel is never re-checked and no customer screen shows the stored set (reconfirms P05-2 with a new trigger).
3. **P2-CHA-05, major (3 of 3 runs): she gets signed out on a slow network.** Coming back more than 5 minutes after sign-in on Slow 3G, several session checks fire together with the same single-use refresh token. One succeeds and the others clear her cookies. The basket survives, and one tap on Sign in restores her without a password.
4. **P2-CHA-03, major: a deleted dish shows "Product not found: 89560dca-…".** The basket still lists the dish, so on a 20-line basket she can't tell which line is the problem.
5. **P2-CHA-09, major: no way to order ahead for a set time.** Orders are as-soon-as-possible only, and a closed shop takes nothing. The refusal message itself is clear ("currently closed. Opening hours today: 10:00 - 22:00").

**Minor findings:**
- Sold-out items stay in the basket with no flag, and she learns about them one per failed submit.
- The basket doesn't follow her account to the phone.
- Checkout never shows the minimum order.
- The vendor can't change the delivery fee at all; the API returns 200 and ignores it.
- The vendor has no "pause orders" switch, and opening hours can't be cleared once set (reconfirms ADE-11).
- The kitchen ticket is 834 px tall, says "18 items" where she was told 35, and cuts the order number to "ORD-00…".
- A product that has ever been ordered can never be deleted, even if the order was cancelled.

**What worked:**
- Four taps under Slow 3G with 20% packet loss produced exactly one order.
- A lost reply followed by a retry reused the same Idempotency-Key and made no duplicate.
- Server refusals for sold-out, minimum order and closed are clear, and the basket and form survive them.
- The kitchen ticket's allergen banner and per-line chips are excellent.
- An order placed at 00:01 BST is numbered 20261004 and labelled "4 Oct, 00:01" everywhere she looked.

**Not tested:**
- **Delivery-fee change mid-basket:** impossible, because the vendor can't edit the fee.
- **23:59 order:** my script used a £2 item, under the £10 minimum, so checkout kept the button disabled.
- **BST end on 25 Oct:** can't be demonstrated live.

**Cleanup:**
- All 6 of my orders are cancelled.
- Of my 13 chaos-p2 products, 2 are deleted. The other 11 refused deletion (409, referenced by an order) and are set unavailable; none show on the public menu.
- Minimum order is back to £10.00.
- **One leftover on Peckham Jollof Co.:** opening hours are now `{}` where they were `null`. Behaviour is the same (empty means always open), but no vendor screen or API call can set them back to `null`. Restoring it exactly needs a database write, which I was not allowed to do.
- **Other personas:** Peckham was below my test minimum for about 5 seconds and showed as closed for about 11 seconds, so anyone ordering there then may have been refused.

**REPORT.md was not written.** The harness blocked the Write tool for a report file from a subagent. I didn't route around the block, so the narrative needs saving by you. I did write `findings.json` (19 self-contained entries); the folder also has the scripts, script outputs, order JSON and 40+ screenshots. Report outline:
- **Persona:** Nkechi, office lunch for 8, laptop plus iPhone 13, cash on delivery; vendor admin-user on Peckham Jollof Co., 13 own "chaos-p2" products.
- **Journey:**
  - Register; build a 20-line, 40-unit basket (£226.00, VAT £37.66, free delivery). No way to say who each dish is for.
  - Device switch: phone basket empty.
  - Price rise and sold-out while on checkout.
  - Deleted item gives the UUID error.
  - Minimum raised to £199 (checkout never shows it; the server subtotal was really £198.50).
  - Closing time.
  - Allergen race: £188.50 shown, £198.50 charged, two extra allergens stored.
  - Kitchen ticket; double-tap and lost reply; slow-network sign-out; midnight order.
- **Verdict in character:** "For a sandwich for myself, fine. For the team lunch, no: I collected £188.50 from eight people and the driver will ask for £198.50, and I ticked a list the kitchen didn't get." The one change that would win her back: checkout re-checks prices, availability and allergens just before she pays and makes her confirm anything that changed.
- **Shared root cause** of P2-CHA-01, -03, -04 and -07: the basket is a browser-side copy that checkout never re-checks against the live menu or shop rules.

Everything is in `/home/sanmi/IdeaProjects/JToye_OaaS_2026/.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/`; open `findings.json` first.
