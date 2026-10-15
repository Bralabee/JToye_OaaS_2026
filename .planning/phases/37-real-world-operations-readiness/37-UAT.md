---
status: partial
phase: 37-real-world-operations-readiness
source: [37-VERIFICATION.md]
started: 2026-10-15T08:10:32Z
updated: 2026-10-15T18:13:50Z
---

## Current Test

[testing paused — 5 items outstanding (3, 6, 7, 8, 10 need the owner)]

## Tests

### 1. Screen reader order tracking (37-53 plan human-check): with VoiceOver or NVDA follow an order from the storefront to its tracking page (basket, checkout, order page, /track) and let the vendor move the order on
expected: Each page title names its step and shop; the status change is spoken once as 'Order update: {label}. {description}.'; the current step is announced as current; the copy button and the lookup field have names
result: pass
note: "Driven in Chrome 2026-10-15 (order ORD-00000000-20261015-005BD341, cancelled after): titles 'Basket · Mama Ade's Kitchen — J'Toye', 'Checkout · …', 'Order … confirmed · …'; live region recorded exactly one 'Order update: Confirmed. Shop accepted your order.' on the order page and one 'Order update: Preparing. Being prepared now.' on /track; aria-current=step moved Received→Confirmed→Preparing; 'Copy order number' and the Order number / Email lookup fields are named. DOM-level proxy, not a real screen reader."

### 2. Phone tab-through with the cookie notice (37-53 plan human-check): on a real phone, tab or swipe through the storefront with the cookie notice showing and again after dismissing it
expected: The notice is reached early in the tab order, never covers the focused element, and focus is never lost behind it
result: pass
note: "Playwright iPhone 13 profile (390x844), real Tab presses, 45 stops each arm: with the notice showing it is reached first (Got it, Cookie policy) and every focused element sits fully above it (overlap 0); after dismissing, all 45 stops visible, focus never on body mid-sequence. Proxy for a real phone; swipe with a screen reader not exercised."

### 3. Review the 40-goods table (evidence/37-53-goods-40.md) and the SC-7 instrument corrections
expected: Owner accepts that 40 of 40 goods hold; note the API, staff, MCP and browser instruments were scratch scripts that are not committed
result: [pending]
note: "Agent review 2026-10-15: 40/40 rows PASS and instruments uncommitted as stated; but P2-CHA-16 (:47) was a 7-line basket not the 20-line/40-unit good; goods-signout.cjs (P2-TUN-14) missing from the instrument list at :12-15; P2-MAR-P1, P2-CHA-15, P2-RAV-21 are pointers not readings. Awaiting owner acceptance."

### 4. Kitchen chime is audible in a noisy kitchen and repeats until Accept (37-23 plan human-check, 37-VALIDATION manual row)
expected: Tap Turn on sound on /dashboard/kitchen, place a storefront order: the two-tone chime repeats every 5 s until Accept, then stops; the mute indicator shows when muted
result: pass
note: "AudioContext instrumented on /dashboard/kitchen: after Turn on sound, chime every 5.0 s (880 then 660 Hz sine per kds-sound.tsx:31-36); Mute → 0 rings in 10 s, 'Muted' pill and 'Alerts are muted…' status. Stop-on-Accept NOT exercised live: Mama Ade's board holds 89 residue E2E PENDING orders (2026-08-30..10-11), so it never empties; covered by the unit suite only. Audibility in a noisy kitchen not judged."

### 5. Board-stopped alarm is distinguishable by ear from the chime; sign off timings (37-23)
expected: Sign out in another tab and wait for the next read: 440 Hz square bursts every 3 s, capped at 2 minutes (owner-accepted 2026-10-10), clearly different from the chime. Sign off 5 s chime / 3 s alarm / 2 min cap
result: pass
note: "After sign-out in a second tab: red 'Signed out: new orders are NOT showing' overlay with Sign in again / Silence alarm, title 'STOPPED · …'; three 440 Hz square bursts every 3.0 s, chime stopped, cap measured exactly 120 s (41 bursts, then silence). Timings 5 s / 3 s / 2 min confirmed; by-ear distinction not judged."

### 6. 37-15 screens and copy: Staff page (People, Invite, Pending), invite page, NoAccessPage, finance scope banner, void dialog at 390/768/1280; invitation email in Mailhog
expected: Sign off the VAT helper copy (UI-SPEC open item 1) or give wording; judge the People card at 390 px (a block is 44 px wider than its box, buttons cut at the edge); the invite email shows a double full stop when the shop name ends in a period
result: [pending]
note: "Partly observed 2026-10-15: double full stop confirmed in EmailNotificationService.java:289 ('for %s.'); further Staff-page defects logged as tests 13-15. VAT copy and screen sign-off still the owner's."

### 7. 37-31 sign-offs: the four default reject reasons and the cap/limit defaults
expected: Owner accepts or replaces: SUSPECTED_FAKE / CANNOT_FULFIL / CUSTOMER_REQUEST / OTHER wording; open cash orders per email 3, per phone 3, per connection 5; line quantity 50, basket units 200, lines 50, total 1,000 GBP. Look at the selection bar sharing the bottom of a phone with the cookie notice until Got it is tapped
result: [pending]
note: "Code matches: CancelReason + V81 CHECK + reject-orders-dialog.tsx:63-66 labels 'Looks like a fake or test order' / 'We can't make this right now' / 'The customer asked to cancel' / 'Something else'; limits 50/200/50/£1,000 and caps 3/3/5 in application.yml + configmap. Note: the stored reason is never read back to the vendor (no DTO exposes it). Awaiting owner sign-off."

### 8. 37-38 screens: basket-changed panel, cash confirmation route (first view and refresh), storefront without promotions, review form notice at 390/768/1280
expected: Panel lists each change and demands re-confirmation; refresh does not re-POST; no promotion anywhere; a vendor can still type a discount into an announcement (noted for the owner)
result: [pending]
note: "Partly observed: Mama Ade's storefront shows an 'E2E launch offer — 20% off selected dishes' announcement (the noted discount-in-announcement case). Basket-changed panel, cash confirmation refresh and review notice NOT driven."

### 9. 37-47 credential evidence and Developers page: decode the credential token claims in evidence/37-47-gate-37E.md and review the show-once dialog screenshots
expected: tenant_id, aud and scope match the credential created; the secret is shown once
result: pass
note: "evidence/37-47-gate-37E.md:359-373: azp = created clientId, aud core-api, scope 'catalog:read orders:write', tenant_id …0001; unrequested scopes 403; replay returns secretShown:false, clientSecret:null; DOM/storage scan finds the secret 0 times after 'I've saved it' (:453). Caveat: only a pre-decoded payload is recorded, no raw JWT."

### 10. 37-50 G1/G2 screens at 375/390/768/1280, and finding 2 (directory card and basket on a collection-only shop)
expected: Owner decides how a collection-only shop appears on the directory card and the basket
result: [pending]
note: "Not exercised; owner decision. Screens of /shops and two storefronts at 390/768/1280 captured (no horizontal overflow)."

### 11. Staff invite end to end on a real vendor realm (37-USER-SETUP): send the invite, open the Mailhog or real mail link, register on /invite, confirm the Staff page shows exactly the invited grant
expected: Account created in the vendor realm with the admin-only tenant_id attribute; token carries tenant_id; the invitee cannot edit tenant_id in the account console
result: pass
note: "Invited uat37-invite@example.test as Staff · Mama Ade's Kitchen from the Staff page; Mailhog mail correct (role, shop, fragment token, UK-time expiry); /invite created the account and signed in; GET /api/v1/staff/invites → ACCEPTED; exactly one shop_staff row (STAFF, Mama Ade's, OPERATOR); own-shop orders 200, Brixton 403; token tenant_id …0001; account-API POST forging tenant_id → 400 error-user-attribute-read-only."

### 12. Webhooks page for a user without group-admin (found during 2026-10-15 session)
expected: A Staff user opening /dashboard/webhooks sees the typed access refusal, like Staff and Finance do
result: issue
reported: "API returns 403 shop-access-denied requiredRole GROUP_ADMIN for the Staff invitee, but the page shows 'No webhook endpoints yet' and an 'Add endpoint' button while the tenant has endpoints"
severity: major

### 13. Staff page selects readable in dark theme (found during 2026-10-15 session)
expected: Role, Shop and Team-member selects on /dashboard/staff are legible in dark mode
result: issue
reported: "All five selects compute color rgb(248,250,252) on background rgb(255,255,255): invite form (invite-staff-form.tsx:60 SELECT_CLASS, 37-09) and the Grant-access selects (staff/page.tsx:812,857, since July) hardcode bg-white"
severity: major

### 14. Pending invitations table at 768 px (found during 2026-10-15 session)
expected: Pending invitations fit the tablet layout without breaking words or hiding actions
result: issue
reported: "At 768 px the table is 630 px wide in a 398 px scroll box: emails and words break mid-word ('manag er', 'Unsort ed'), Send invitation again is cut and Cancel invitation is off-screen"
severity: minor

### 15. Team member picker labels (found during 2026-10-15 session)
expected: Every person in the Grant-access picker shows a name and a masked email or nothing
result: issue
reported: "Integration accounts render as 'integration-orders-rw (null)' and 'integration-catalog-ro (null)'"
severity: cosmetic

## Summary

total: 15
passed: 6
issues: 4
pending: 5
skipped: 0
blocked: 0

## Gaps

- gap_id: G-37-12
  truth: "A Staff user opening /dashboard/webhooks sees the typed access refusal"
  status: failed
  reason: "Observed in 2026-10-15 Chrome UAT session: API 403 requiredRole GROUP_ADMIN rendered as 'No webhook endpoints yet' + Add endpoint"
  severity: major
  test: 12
  root_cause: "fetchSubscriptions catch shows a transient destructive toast and leaves subscriptions empty, so the 403 renders the "No webhook endpoints yet" empty state with Add endpoint; Finance (finance/page.tsx:243-252) already has the honest admin-gate card pattern (QA FE-2)"
  artifacts:
    - path: "frontend/app/dashboard/webhooks/page.tsx"
      issue: "webhooks page lines 73-89 and 309"
  missing:
    - "Detect 403 shop-access-denied on list() and render the access-required card (mirror Finance/Approvals); hide Add endpoint; jest test RED on the current page"
  debug_session: ""

- gap_id: G-37-13
  truth: "Staff page selects are legible in dark mode"
  status: failed
  reason: "Observed in 2026-10-15 Chrome UAT session: five selects render rgb(248,250,252) text on white (bg-white hardcoded)"
  severity: major
  test: 13
  root_cause: "SELECT_CLASS and the Grant-access select classes hardcode bg-white while text colour inherits the dark theme foreground"
  artifacts:
    - path: "frontend/components/dashboard/staff/invite-staff-form.tsx"
      issue: "SELECT_CLASS line 60; frontend/app/dashboard/staff/page.tsx lines 812 and 857"
  missing:
    - "Use theme tokens (bg-background text-foreground border-input) for all five selects; measure computed contrast in dark mode before and after"
  debug_session: ""

- gap_id: G-37-14
  truth: "Pending invitations table fits 768 px"
  status: failed
  reason: "Observed in 2026-10-15 Chrome UAT session: 630 px table in 398 px box; words break mid-word; action buttons cut/off-screen"
  severity: minor
  test: 14
  root_cause: "Pending invitations table keeps a four-column layout in a 398 px content box at 768 (persistent 256 px sidebar), so cells wrap per character and the actions overflow the scroll box"
  artifacts:
    - path: "frontend/components/dashboard/staff"
      issue: "pending-invitations table (37-09)"
  missing:
    - "Stacked card layout below the md content width as the People table already does at 390, or break-words only on the email; verify at 390/768/1024 with no clipped action"
  debug_session: ""

- gap_id: G-37-15
  truth: "Picker shows no literal 'null'"
  status: failed
  reason: "Observed in 2026-10-15 Chrome UAT session: integration accounts show '(null)'"
  severity: cosmetic
  test: 15
  root_cause: "picker label is `${d.displayName} (${d.email})`; integration accounts have email null"
  artifacts:
    - path: "frontend/app/dashboard/staff/page.tsx"
      issue: "line 522"
  missing:
    - "Omit the parenthesis when email is null; unit test for a null-email directory entry"
  debug_session: ""
