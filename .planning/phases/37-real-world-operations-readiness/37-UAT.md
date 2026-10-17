---
status: complete
phase: 37-real-world-operations-readiness
source: [37-VERIFICATION.md]
started: 2026-10-15T08:10:32Z
updated: 2026-10-16T17:13:48.340Z
---

## Current Test

[testing complete]

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
result: pass
note: "Agent review 2026-10-15: 40/40 rows PASS and instruments uncommitted as stated; but P2-CHA-16 (:47) was a 7-line basket not the 20-line/40-unit good; goods-signout.cjs (P2-TUN-14) missing from the instrument list at :12-15; P2-MAR-P1, P2-CHA-15, P2-RAV-21 are pointers not readings. Awaiting owner acceptance."
owner_ruling: "2026-10-16: accepted 40/40, with the caveats recorded: P2-CHA-16 was a 7-line basket, not the 20-line/40-unit good; P2-MAR-P1, P2-CHA-15 and P2-RAV-21 are pointers, not readings; goods-signout.cjs is missing from the instrument list."
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
result: pass
evidence: "Owner-requested re-test 2026-10-16, driven by Claude in the owner's Chrome (signed in as admin-user; freshness gate rc 0 first). 1268 px: void dialog on COMPLETED ORD-00000000-20261012-41834038 opened from /dashboard/orders/{id} 'Void order', inside the viewport, 0 children outside, Keep order closed it; DB still COMPLETED, voided_at null, 0 REVERSAL rows. Peckham checkout (basket with Suya Platter) reads 'These items are prepared by Peckham Jollof Co. Based on…', no '..' on the page. Staff invite (Staff · Peckham) in Mailhog reads '…as Staff for Peckham Jollof Co.' with double=0 (a planted 'Co..' control is caught); invitation cancelled afterwards. 768 px (sidebar 256 px): finance cards stack, Revenue £400.48, Net £400.48, Total VAT £66.66 each one line, inside their cards, no page overflow; void dialog fits, buttons 44 px tall, Keep order closes it. Chrome's minimum viewport is 616 px, so 390 px was not reachable in Chrome: at 616 px the pagination row shows first/previous/1-5/next/last, all 44x44 inside the card. 390 px covered by Playwright Chromium on the same stack: screens-copy-uat.spec.ts defect 1, defect 3 and item 4, 3 passed, each with its built-in instrument arm."
retest: "fixed by 37-56..37-59 (G-37-6 resolved; live specs screens-copy-uat.spec.ts 5/5 on a runtime proven fresh, freshness rc 0 re-checked 2026-10-16 before this re-test); owner re-test pending. Previous result: issue (major), report kept below."
note: "Partly observed 2026-10-15: double full stop confirmed in EmailNotificationService.java:289 ('for %s.'); further Staff-page defects logged as tests 13-15. VAT copy and screen sign-off still the owner's."
reported: "Claude-driven UAT 2026-10-16 at the owner's request (Chrome at ~768/1280, Playwright Chromium at 390/768/1280, overflow probe with a planted-600px control that is caught): (a) Finance at 768 with the sidebar: the stat cards are 98 px wide and clip the money — Revenue and Net read '£400.4' (last digit cut), Total VAT spills past its card; (b) the double full stop is live for customers as well as in the invite email: the checkout allergen panel reads 'prepared by Peckham Jollof Co..' (EmailNotificationService.java:289 'for %s.' still present); (c) Orders at 390: the pagination row is 320 px in a 308 px box and the last-page button sits on the screen edge outside its card. Clean: Staff page (People/Invite/Pending) at 390/768/1280 (the 44 px People-card overflow no longer reproduces), invite page at all three widths. NOT driven: the void dialog (the detail panel did not open from the scripted row click and the Chrome re-sign-in did not complete after 3 attempts). Owner still to sign off the VAT helper copy (products/page.tsx VAT_RATE_HELP_COPY)."
severity: major
owner_ruling: "2026-10-16: VAT helper copy accepted as written (products/page.tsx VAT_RATE_HELP_COPY). The test stays an issue for the three G-37-6 defects and the undriven void dialog; fix as a gap-closure plan next session."

### 7. 37-31 sign-offs: the four default reject reasons and the cap/limit defaults
expected: Owner accepts or replaces: SUSPECTED_FAKE / CANNOT_FULFIL / CUSTOMER_REQUEST / OTHER wording; open cash orders per email 3, per phone 3, per connection 5; line quantity 50, basket units 200, lines 50, total 1,000 GBP. Look at the selection bar sharing the bottom of a phone with the cookie notice until Got it is tapped
result: pass
note: "Code matches: CancelReason + V81 CHECK + reject-orders-dialog.tsx:63-66 labels 'Looks like a fake or test order' / 'We can't make this right now' / 'The customer asked to cancel' / 'Something else'; limits 50/200/50/£1,000 and caps 3/3/5 in application.yml + configmap. Note: the stored reason is never read back to the vendor (no DTO exposes it). Awaiting owner sign-off."
owner_ruling: "2026-10-16: accepted as written: the four reject reasons, caps 3/3/5 and limits 50/200/50/£1,000. Noted gap: the stored reject reason is never read back to the vendor."
### 8. 37-38 screens: basket-changed panel, cash confirmation route (first view and refresh), storefront without promotions, review form notice at 390/768/1280
expected: Panel lists each change and demands re-confirmation; refresh does not re-POST; no promotion anywhere; a vendor can still type a discount into an announcement (noted for the owner)
result: pass
note: "Partly observed: Mama Ade's storefront shows an 'E2E launch offer — 20% off selected dishes' announcement (the noted discount-in-announcement case). Basket-changed panel, cash confirmation refresh and review notice NOT driven."
evidence: "Claude-driven UAT 2026-10-16: basket-changed panel live via basket-revalidation.spec.ts UXT-007 2/2 (reprice shown '£was → £now', refused until accepted, placed once; INSTRUMENT arm without the vendor edit: no panel, first POST accepted). Cash order ORD-00000000-20261016-4E172F2C placed in Chrome (one POST 201); the confirmation reloaded twice with GETs only and the database holds exactly 1 order for its email (control: the query sees all 475 orders). No promotion on /shop or any storefront; the only discount text is Mama Ade's vendor-typed announcement 'E2E launch offer — 20% off' (the noted owner case). Review-form notice N/A: no review form ships (37-38 SUMMARY; owner 2026-10-12, WINDOWS row 43 open). Directory, storefront and confirmation clean at 390/768/1280. The first view is headed 'Order confirmed', a refresh 'Order ORD-…'."

### 9. 37-47 credential evidence and Developers page: decode the credential token claims in evidence/37-47-gate-37E.md and review the show-once dialog screenshots
expected: tenant_id, aud and scope match the credential created; the secret is shown once
result: pass
note: "evidence/37-47-gate-37E.md:359-373: azp = created clientId, aud core-api, scope 'catalog:read orders:write', tenant_id …0001; unrequested scopes 403; replay returns secretShown:false, clientSecret:null; DOM/storage scan finds the secret 0 times after 'I've saved it' (:453). Caveat: only a pre-decoded payload is recorded, no raw JWT."

### 10. 37-50 G1/G2 screens at 375/390/768/1280, and finding 2 (directory card and basket on a collection-only shop)
expected: Owner decides how a collection-only shop appears on the directory card and the basket
result: pass
note: "Not exercised; owner decision. Screens of /shops and two storefronts at 390/768/1280 captured (no horizontal overflow)."
evidence: "Observed 2026-10-16: the /shop directory card for Brixton Village Grill (collection-only) reads 'Collection only' where the others show their delivery fee, and its storefront says so. The basket on a collection-only shop was not driven. The owner's decision remains."
owner_ruling: "2026-10-16: accepted: the directory card reads 'Collection only' where other shops show their delivery fee. The basket on a collection-only shop was not driven."
### 11. Staff invite end to end on a real vendor realm (37-USER-SETUP): send the invite, open the Mailhog or real mail link, register on /invite, confirm the Staff page shows exactly the invited grant
expected: Account created in the vendor realm with the admin-only tenant_id attribute; token carries tenant_id; the invitee cannot edit tenant_id in the account console
result: pass
note: "Invited uat37-invite@example.test as Staff · Mama Ade's Kitchen from the Staff page; Mailhog mail correct (role, shop, fragment token, UK-time expiry); /invite created the account and signed in; GET /api/v1/staff/invites → ACCEPTED; exactly one shop_staff row (STAFF, Mama Ade's, OPERATOR); own-shop orders 200, Brixton 403; token tenant_id …0001; account-API POST forging tenant_id → 400 error-user-attribute-read-only."

### 12. Webhooks page for a user without group-admin (found during 2026-10-15 session)
expected: A Staff user opening /dashboard/webhooks sees the typed access refusal, like Staff and Finance do
result: pass
retest: "fixed by 37-54/55 (#925); owner re-test 2026-10-16. Original report: API returns 403 shop-access-denied requiredRole GROUP_ADMIN for the Staff invitee, but the page shows 'No webhook endpoints yet' and an 'Add endpoint' button while the tenant has endpoints (major)"
evidence: "Chrome 2026-10-16 (owner-requested, driven by Claude in the owner's Chrome): invited uat12-retest-1016@example.test as Staff · Mama Ade's Kitchen from the Staff page, took the link from Mailhog, registered on /invite and signed in. /dashboard/webhooks shows 'Group admin access required' with no list, no Add endpoint, no 'No webhook endpoints yet'; GET /api/v1/webhooks 403. Side note: the Chrome extension's network reader listed _rsc prefetches as 503; the browser's own Performance entries show the same requests completing 200 with bytes, 12 parallel prefetches all returned 200 and the frontend logged nothing: an instrument artifact, not an app defect."

### 13. Staff page form controls readable (found during 2026-10-15 session; reframed 2026-10-16 after the forced-light ruling)
expected: Role, Shop and Team-member selects and their labels on /dashboard/staff are legible. The dashboard is now forced light (quick 261016-edv), so check the light dashboard; no theme control is offered and a stored dark choice or dark system setting still shows light. Dark styling of these cards stays measured by the 37-55 spec
result: pass
retest: "fixed by 37-54/55 (#925); owner re-test 2026-10-16. Original report: All five selects compute color rgb(248,250,252) on background rgb(255,255,255): invite form (invite-staff-form.tsx:60 SELECT_CLASS, 37-09) and the Grant-access selects (staff/page.tsx:812,857, since July) hardcode bg-white (major)"
evidence: "Chrome 2026-10-16 as admin-user on /dashboard/staff: html.dark false, no theme control, all five form selects 20.01:1 and their labels 10.35:1 (Shop-context switcher 17.85:1); with localStorage theme=dark stored and a reload the page stays light (body and select bg rgb(255,255,255)); stored value restored afterwards. Probe control: the original defect colours (rgb(248,250,252) on white) measure 1.05:1, so the probe can fail."

### 14. Pending invitations table at 768 px (found during 2026-10-15 session)
expected: Pending invitations fit the tablet layout without breaking words or hiding actions
result: pass
retest: "fixed by 37-54/55 (#925); owner re-test 2026-10-16. Original report: At 768 px the table is 630 px wide in a 398 px scroll box: emails and words break mid-word ('manag er', 'Unsort ed'), Send invitation again is cut and Cancel invitation is off-screen (minor)"
evidence: "Chrome window could not be resized (maximised 3374 px; framing refused by frame-ancestors; popup blocked), so the live 37-55 case ran in Playwright Chromium on the same stack: `staff-webhooks-uat.spec.ts -g G-37-14 --project=desktop` 1 passed: real invitation, layout checked at every width 390..1440, built-in arm forcing the old four-column layout at 768 caught, Cancel invitation clicked at 768 and the row left."

### 15. Team member picker labels (found during 2026-10-15 session)
expected: Every person in the Grant-access picker shows a name and a masked email or nothing
result: pass
retest: "fixed by 37-54/55 (#925); owner re-test 2026-10-16. Original report: Integration accounts render as 'integration-orders-rw (null)' and 'integration-catalog-ro (null)' (cosmetic)"
evidence: "Chrome 2026-10-16 as admin-user: Team-member picker has 22 options, none containing null/undefined/'()'; integration accounts read 'integration-orders-rw' and 'integration-catalog-ro'; people read 'Name (m***@domain)'."

## Summary

total: 15
passed: 15
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

- gap_id: G-37-12
  truth: "A Staff user opening /dashboard/webhooks sees the typed access refusal"
  status: resolved
  resolved_by: 37-54-PLAN.md
  resolved_at: 2026-10-16
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
  status: resolved
  resolved_by: 37-54-PLAN.md
  resolved_at: 2026-10-16
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
  status: resolved
  resolved_by: 37-54-PLAN.md
  resolved_at: 2026-10-16
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
  status: resolved
  resolved_by: 37-54-PLAN.md
  resolved_at: 2026-10-16
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

- gap_id: G-37-6
  truth: "37-15 screens hold at 390/768/1280 and copy is clean"
  status: resolved
  resolved_by: 37-59-PLAN.md
  resolved_at: 2026-10-16
  reason: "Claude-driven UAT 2026-10-16 (owner-requested): Finance stat cards clip money at 768 ('£400.4'); double full stop after a shop name ending in a period on the checkout allergen panel and in the invite email; Orders pagination overflows its card at 390"
  severity: major
  test: 6
  root_cause: "Measured in planning 2026-10-16. (1) finance/page.tsx keys the stat grid on the VIEWPORT (md:grid-cols-4): at 768 the persistent 256 px sidebar and sm:p-8 leave 448 px, so four cards are ~100 px with 50 px of text room for a text-2xl figure (~85 px for £400.40); the figure is painted under the next card (read '£400.4') and the last one spills past its card. The VAT breakdown grid has the same viewport-keyed shape (sm:/lg:) and a flex-1 text column with no min-w-0. (2) Every copy template that ends a sentence with a vendor- or person-supplied value appends its own literal full stop, and no shared rule knows a value ending in '.', '!', '?' or '…' already ends the sentence: 6 frontend sites (allergenPanelIntroCopy, ALLERGY_NOTE_SENT_COPY, alreadyVoidedCopy, joinedToastTitle, copyResultSentence, the Staff page remove-access description) and 7 server sites (sendStaffInvite 'for %s.', four customer status leads, the 'on behalf of' footer, the onboarding reason in text and HTML); import-copy.test.tsx:314 even asserts the double stop. (3) components/ui/pagination.tsx is a fixed, unwrapped row of nine 32 px controls (320 px with gaps) shared by seven dashboard pages, so it overflows any card under 320 px (308 px at 390) and every target is below the 44 px UI-SPEC minimum. (4) The void dialog is reached from /dashboard/orders/[id] (OrderDetailPanel 'Void order', Group admin, CONFIRMED or later and not voided); the list row click navigates there, while the list page's inline detail dialog has been unreachable since 17-04, which is why a scripted click on the list never opened it. (5) The JSDoc above VAT_RATE_HELP_COPY and the UI-SPEC copy row still say the owner's sign-off is pending."
  artifacts:
    - path: "frontend/app/dashboard/finance/page.tsx"
      issue: "stat grid ~l.335 'grid gap-4 md:grid-cols-4'; VAT breakdown grid ~l.409 'sm:grid-cols-2 lg:grid-cols-4' and its flex-1 text column; stale COVERAGE BOUNDARY note ~l.229"
    - path: "frontend/components/ui/pagination.tsx"
      issue: "nine h-8 w-8 controls in a non-wrapping row (~l.40-95); shared by customers, webhooks/[id], finance, shops, products, marketing and orders pages"
    - path: "frontend/components/storefront/order-allergen-panel.tsx"
      issue: "allergenPanelIntroCopy ~l.122 places the vendor name before a literal full stop (customer-facing)"
    - path: "core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java"
      issue: "sendStaffInvite ~l.289 'for %s.'; status leads ~l.157, 167, 178, 230; footer ~l.476"
    - path: "core-java/src/main/java/uk/jtoye/core/notification/template/EmailTemplateRenderer.java"
      issue: "onboardingCopy ~l.128-129 reason before a literal full stop (text and HTML)"
    - path: "frontend/components/storefront/recorded-allergen-set.tsx"
      issue: "ALLERGY_NOTE_SENT_COPY ~l.67"
    - path: "frontend/components/dashboard/orders/void-order-dialog.tsx"
      issue: "alreadyVoidedCopy no-date branch ~l.104; the dialog itself not yet driven at 390/768/1280"
    - path: "frontend/lib/joined-toast.ts"
      issue: "joinedToastTitle ~l.43 (business and scope)"
    - path: "frontend/components/dashboard/products/copy-to-shop-dialog.tsx"
      issue: "copyResultSentence ~l.41 and ~l.48"
    - path: "frontend/app/dashboard/staff/page.tsx"
      issue: "removeDescription ~l.711"
    - path: "frontend/app/dashboard/products/__tests__/import-copy.test.tsx"
      issue: "~l.314 asserts the double full stop"
    - path: "frontend/app/dashboard/products/page.tsx"
      issue: "stale pending-sign-off JSDoc above VAT_RATE_HELP_COPY ~l.115"
  missing:
    - "Finance stat and VAT grids chosen by container queries on their own content box, figures tabular with an overflow-wrap safety net, thresholds derived from a declared bound (−£999,999.99) and measured live; jest RED on the current page (37-58 T1)"
    - "One sentence-end rule per language: frontend/lib/sentence-end.ts and uk.jtoye.core.common.Sentences, held equal by a parity test; every free-text sentence end routed through it; a frontend source guard with planted controls; the test that encoded the double stop corrected (37-56, 37-57)"
    - "Pagination tiers chosen by container queries (prev/status/next, plus first/last, a three-page window, the five-page window), every control 44 x 44 px; jest RED on the current component (37-58 T2)"
    - "The void dialog driven live through the real opener at 390/768/1280, opened and never confirmed; fixed before the rebuild if it fails (37-59)"
    - "The VAT helper JSDoc and UI-SPEC row record the 2026-10-16 sign-off (37-58 T1)"
    - "Live RED on the old runtime and GREEN after rebuilding ALL images with freshness rc 0, a stylesheet and a jar probe in both directions, goods specs green, and a path-scoped PR branch that excludes the light-theme change (37-59)"
  debug_session: ""
