# Persona 15: Marcus, screen-reader customer (pass 2, 2026-10-03)

> The coordinator wrote this file from the persona agent's returned summary. The agent could not write it
> (a harness hook blocks subagents from writing report files), and its attempt to return the full
> narrative was interrupted. Findings are in `findings.json` (21 entries: 18 findings, 3 positives).
> The step-by-step announcement record is in the run logs `s01.log`–`s17.log` and `s03.out`–`s17.out`,
> with ARIA snapshots in `aria-*.yaml`, axe results in `axe-*.json`, and screenshots in `*.png`.

## Persona
Marcus, 31, blind since birth. He is a software tester who uses VoiceOver on iPhone and NVDA on Windows.
He drops a takeaway app within two minutes if it doesn't work for him, and he expects WCAG 2.2 AA.

**Method:** keyboard and role-based locators only, reading Chrome's own accessibility tree over CDP. axe-core was
injected from `frontend/node_modules/axe-core`, because `@axe-core/playwright` is not installed. Nothing was installed.

## Verdict (in character)
"I could finish an order, but I wouldn't recommend J'Toye to other blind users yet. The menu, the product dialog and
the checkout allergen gate are better than most UK takeaway sites. But the two moments that decide whether I come
back are silent: placing the order, and waiting for it on the tracking page."

## Scores
Ease 5 · Trust 4 · Speed 7 · Mobile 5 · Overall 5

## Top findings (all CONFIRMED; full detail in findings.json)
| ID | Severity | Finding |
|---|---|---|
| P2-MAR-01 | major | After "Place order", focus is lost and the only announcement is "0 items in basket". The URL and title stay on checkout, so "Order confirmed!" is never spoken. Seen on 2 orders, desktop and iPhone. The user may re-order. |
| P2-MAR-02/03 | major | Tracking status changes are silent: 0 announcements across 2 vendor transitions. The timeline exposes no current or done step. |
| P2-MAR-04 | major | Shop cards on `/shop` have empty accessible names ("link, link, link"). axe reports 0 violations, so the automated gate can't see it. |
| P2-MAR-05 | major | After a menu card's Add/Remove, focus falls to the page body (4 times), so the user loses their place in the menu. |
| P2-MAR-06 | major | Keycloak register and login pages: errors are not tied to fields and not focused. No `lang`, no main landmark, positive tabindex on 7 controls. The register page is titled "Sign in to J'Toye". |
| — | minor | Basket announces "1 1 item in basket" with no item name. |
| — | minor | The copy-order-number button on tracking has no accessible name. |
| — | minor | Basket, checkout, confirmation and tracking all share one generic page title. |
| — | minor | Checkout validation takes two rounds. |
| — | minor | RECONFIRMS P05-4: the allergen badge reads "£8.50 2". |
| — | minor | On mobile, the cookie banner obscures keyboard focus (WCAG 2.2 SC 2.4.11) and is 32 Tabs away. |
| — | minor | `/track` errors and results are not announced. |
| — | minor | The accessibility statement is stale: it claims there is no skip link (there is one), cites WCAG 2.1, and omits basket, confirmation and tracking. |

## Goods to preserve
- The product-detail dialog: focus moves in, is trapped, and Escape returns it to the trigger.
- The checkout allergen gate: a real list plus checkbox; a failure moves focus to it and announces the error.
- Skip link, landmarks and headings; unique control names. The "View" ×12 link problem does not occur on any customer page.

## Test data left behind
- Customer `marcus-15-7731@example.test` (there is no self-serve delete).
- Orders `ORD-00000000-20261003-50F52C87` (Brixton Village Grill) and `ORD-00000000-20261003-F82D641A` (Mama Ade's Kitchen): both cancelled through the vendor API.
- Not tested: whether a cancellation is announced live.
