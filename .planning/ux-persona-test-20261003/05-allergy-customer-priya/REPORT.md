# Persona 05: Priya, the allergy parent (saved by the coordinator from the agent's returned report)

> The harness refused the subagent's own Write, so the coordinator saved this from its returned report. Screenshots and scripts are in this folder.
> **Coordinator re-check (2026-10-03):** all 3 public shops return `"phone":null "email":null`. The public tracking payload for ORD-…8839B69F has 0 allergen fields. P05-1 and P05-2 are confirmed independently.

**Verdict:** "I'd order a drink or a side for myself, but not a main for my son." The checkout acknowledgement gate is excellent. What's missing is everything just before it and just after it.
**What would change her mind:** a phone number for each kitchen, and the allergen list repeated on the confirmation page and email.
**Scores:**
- **Ease 6:** have to open every item to check allergens.
- **Trust 6:** honest copy, but no contact and no record afterwards.
- **Speed 7:** fast on localhost, but not throttle-tested.
- **Mobile 7:** at 200% zoom you scroll inside the modal.
- **Overall 5.**

## Persona
Priya, 37. Her 8-year-old son has a severe peanut and sesame allergy and carries an EpiPen; she is coeliac. She uses a 1280px laptop and a Pixel 7, was tested at 200% zoom (640 CSS px), and uses the keyboard only. Vague "may contain" labels have caught her out before. Her question: can I order safely here, without ever having to guess?

## Environment notes (not findings)
- My first checkout ran during the :9091 port outage (#671) and showed "Allergen information not recorded". That isn't a finding, but it does show the screen fails safe ("not recorded", never "none").
- All results below were re-run against :9090 after the fix.
- Stripe isn't configured locally, so I placed a cash order for collection.
- **Test data left behind:** ORD-00000000-20261003-8839B69F, Brixton Village Grill, PENDING, guest email priya-05-9250@example.test. A guest can't cancel it.

## Journey
1. **Discover** (`01-discover-1280.png`):
   - The chips are cuisines only; there is no allergen filter.
   - Searching "peanut", "gluten free" or "sesame" returns "No kitchens found" (`16-search-sesame.png`).
   - The footer says "Allergen info available on all products".
2. **Brixton menu** (`02`):
   - Cards show "△2", a count with no allergen names.
   - Items with nothing declared show nothing at all.
   - The card's "Add" button adds the item without ever showing the allergens.
3. **Item detail** (`03-detail-*`):
   - Ingredients put the allergen words in bold ("**wheat wrap**, **yaji (peanuts)**"), and the amber box shows named chips (Gluten, Peanuts). Good.
   - For an item with nothing declared (Peri Peri Chicken) the box is missing entirely, with no "none declared" wording.
4. **Basket** (`05`, `07`): name, category, price and quantity only; no allergens.
5. **Checkout** (`08`), the best moment:
   - "Allergens in this order: … this order contains: [Milk]", followed by a required checkbox and "We do not store your allergies and we cannot check this order against them".
   - Clicking without the tick is refused with a `role="alert"` message, focus moves to the checkbox, and Space ticks it (`09`).
   - It shows one combined set, with no per-item attribution.
6. **Allergy note:** a generic "Order notes (optional)" field.
7. **Confirmation** (`10`): no allergens, and my note isn't echoed back.
8. **Email** (`mailhog.json`): plain text only; no items, allergens or note.
9. **/track and the shop order page** (`11`, `12`): "3 items · £11.50"; no items or allergens. The public status payload has no allergen or line fields.
10. **Vendor view** (read-only, `14`):
    - An "⚠ ALLERGENS — Milk" banner, a per-line chip, and my note verbatim.
    - The API returns `allergenMask:64, allergenNames:["Milk"], allergenFlags:[]`.
    - So the snapshot is stored, but only the shop can see it.
11. **Legal:**
    - The privacy notice's allergen section is clear and honest.
    - It says "You can also contact any shop directly", but all 3 shops have `phone:null, email:null`.
12. **Pixel 7** (`20`–`23`): clear, but "Peckham Jollof Co.." has a double full stop.
13. **200% zoom** (`24`, `25`): no horizontal scroll, but the allergen box starts at y=649 in a 450px viewport, so you scroll inside the modal.
14. **Keyboard** (`26`, `27`):
    - The skip link works.
    - Reaching the first "View details" takes 17 Tabs.
    - The focus ring is visible; Enter, Escape and Close all behave.
    - The card's accessible text is "Halal Spicy £8.50 2"; the triangle has no name.

## Ingredients vs declared allergens
All 21 published products are consistent, so I couldn't see the mismatch flag live. **SUSPECTED:** checkout passes `allergenFlags={null}`, so a flag would only ever reach the vendor and kitchen.

## Expectations vs reality
| Expected | Got | Felt |
|---|---|---|
| Filter by my allergens | None; search finds nothing | Have to open every item |
| Names on the card | "△2", no accessible name | Guessing |
| "None declared" said explicitly | Section omitted | Silence is not reassurance |
| Allergens in the basket | None | Inconsistent |
| A warning I must actively acknowledge | Gated, keyboard-accessible | Exactly right |
| Allergens on confirmation, email and tracking | None | No proof of what I agreed to |
| A way to tell the kitchen | Generic notes field; reached the vendor verbatim | Works, but feels like a "special request" |
| A way to ask the kitchen | No phone or email anywhere | Every message says "ask the kitchen", and I can't |
| "May contain" info | Doesn't exist | The label that burnt me before |
| Legal clarity | Clear and honest | Trust goes up |

## Findings
| ID | Severity | Status | Finding | Evidence |
|---|---|---|---|---|
| P05-1 | major (safety) | CONFIRMED (coordinator re-checked) | Allergen copy says "ask the kitchen", but no published shop has a phone or email. The privacy notice says "contact any shop directly", which is impossible. | public shops API; `02` |
| P05-2 | major (safety) | CONFIRMED (coordinator re-checked) | No customer surface shows the acknowledged allergens after ordering. The snapshot is vendor-only. | `10`, `mailhog.json`, `11`, `12`, `14` |
| P05-3 | major | CONFIRMED | No allergen filter; allergen search returns nothing | `16` |
| P05-4 | major (a11y and safety) | CONFIRMED | Card badge "△N" has no names and no accessible name; "Add" works without the allergens being seen | `02`, ARIA snapshot |
| P05-5 | major | CONFIRMED | Detail omits the allergen section when nothing is declared | `03-detail-Peri-Peri-Chicken.png`, `22` |
| P05-6 | minor | CONFIRMED | Basket shows no allergens | `05`, `07` |
| P05-7 | minor | CONFIRMED | No "may contain" / cross-contact field exists | payload keys |
| P05-8 | minor | CONFIRMED | Allergy note is a generic notes field: not an alert for the vendor, never echoed to the customer | `14` |
| P05-9 | minor | SUSPECTED | Mismatch flags never reach the customer (`allergenFlags={null}`) | `checkout/page.tsx` |
| P05-10 | minor | CONFIRMED | No per-item attribution in the checkout allergen set | `08` |
| P05-11 | minor (a11y) | CONFIRMED | At 200% zoom the allergen box is below the fold, inside the modal | `25` |
| P05-12 | polish | CONFIRMED | "Co.." double full stop; card icon on the Place-order button for a cash order | `23`, `08` |

## Goods to preserve
- The checkout acknowledgement gate: placed last before submit, refused without the tick, `role="alert"`, focus moved, keyboard-operable.
- Honest "not the same as allergen-free" copy.
- Bold allergen words in the ingredients, plus named chips.
- "We do not store your allergies…".
- The privacy notice's allergen section.
- Vendor sees the note verbatim, with per-line chips snapshotted at the time of ordering.
- Visible focus rings, a skip link, and no horizontal scroll at 200%.

## Verdict (in character)
"The checkout made me stop and read, and I liked that. But my son's life depends on asking the kitchen one question: do you use sesame oil, or peanuts on the same grill? There was no number to ring. Afterwards, the email and the tracking page didn't even say what I'd agreed to. I'd order a drink or a side for myself, but not a main for my son."
