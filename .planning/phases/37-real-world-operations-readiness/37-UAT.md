---
status: testing
phase: 37-real-world-operations-readiness
source: [37-VERIFICATION.md]
started: 2026-10-15T08:10:32Z
updated: 2026-10-15T08:10:32Z
---

## Current Test

number: 1
name: Screen reader order tracking (37-53 plan human-check): with VoiceOver or NVDA follow an order from the storefront to its tracking page (basket, checkout, order page, /track) and let the vendor move the order on
expected: |
  Each page title names its step and shop; the status change is spoken once as 'Order update: {label}. {description}.'; the current step is announced as current; the copy button and the lookup field have names
awaiting: user response

## Tests

### 1. Screen reader order tracking (37-53 plan human-check): with VoiceOver or NVDA follow an order from the storefront to its tracking page (basket, checkout, order page, /track) and let the vendor move the order on
expected: Each page title names its step and shop; the status change is spoken once as 'Order update: {label}. {description}.'; the current step is announced as current; the copy button and the lookup field have names
result: [pending]

### 2. Phone tab-through with the cookie notice (37-53 plan human-check): on a real phone, tab or swipe through the storefront with the cookie notice showing and again after dismissing it
expected: The notice is reached early in the tab order, never covers the focused element, and focus is never lost behind it
result: [pending]

### 3. Review the 40-goods table (evidence/37-53-goods-40.md) and the SC-7 instrument corrections
expected: Owner accepts that 40 of 40 goods hold; note the API, staff, MCP and browser instruments were scratch scripts that are not committed
result: [pending]

### 4. Kitchen chime is audible in a noisy kitchen and repeats until Accept (37-23 plan human-check, 37-VALIDATION manual row)
expected: Tap Turn on sound on /dashboard/kitchen, place a storefront order: the two-tone chime repeats every 5 s until Accept, then stops; the mute indicator shows when muted
result: [pending]

### 5. Board-stopped alarm is distinguishable by ear from the chime; sign off timings (37-23)
expected: Sign out in another tab and wait for the next read: 440 Hz square bursts every 3 s, capped at 2 minutes (owner-accepted 2026-10-10), clearly different from the chime. Sign off 5 s chime / 3 s alarm / 2 min cap
result: [pending]

### 6. 37-15 screens and copy: Staff page (People, Invite, Pending), invite page, NoAccessPage, finance scope banner, void dialog at 390/768/1280; invitation email in Mailhog
expected: Sign off the VAT helper copy (UI-SPEC open item 1) or give wording; judge the People card at 390 px (a block is 44 px wider than its box, buttons cut at the edge); the invite email shows a double full stop when the shop name ends in a period
result: [pending]

### 7. 37-31 sign-offs: the four default reject reasons and the cap/limit defaults
expected: Owner accepts or replaces: SUSPECTED_FAKE / CANNOT_FULFIL / CUSTOMER_REQUEST / OTHER wording; open cash orders per email 3, per phone 3, per connection 5; line quantity 50, basket units 200, lines 50, total 1,000 GBP. Look at the selection bar sharing the bottom of a phone with the cookie notice until Got it is tapped
result: [pending]

### 8. 37-38 screens: basket-changed panel, cash confirmation route (first view and refresh), storefront without promotions, review form notice at 390/768/1280
expected: Panel lists each change and demands re-confirmation; refresh does not re-POST; no promotion anywhere; a vendor can still type a discount into an announcement (noted for the owner)
result: [pending]

### 9. 37-47 credential evidence and Developers page: decode the credential token claims in evidence/37-47-gate-37E.md and review the show-once dialog screenshots
expected: tenant_id, aud and scope match the credential created; the secret is shown once
result: [pending]

### 10. 37-50 G1/G2 screens at 375/390/768/1280, and finding 2 (directory card and basket on a collection-only shop)
expected: Owner decides how a collection-only shop appears on the directory card and the basket
result: [pending]

### 11. Staff invite end to end on a real vendor realm (37-USER-SETUP): send the invite, open the Mailhog or real mail link, register on /invite, confirm the Staff page shows exactly the invited grant
expected: Account created in the vendor realm with the admin-only tenant_id attribute; token carries tenant_id; the invitee cannot edit tenant_id in the account console
result: [pending]

## Summary

total: 11
passed: 0
issues: 0
pending: 11
skipped: 0
blocked: 0

## Gaps
