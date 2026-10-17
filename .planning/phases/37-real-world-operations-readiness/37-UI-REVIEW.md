# Phase 37 — UI Review

**Audited:** 2026-10-16 (base review), updated 2026-10-16 with the G-37-6 delta (37-56..37-59)
**Baseline:** 37-UI-SPEC.md (design contract)
**Screenshots:** captured (3/3) to `.planning/ui-reviews/37-20261016-113434` (git-ignored). Caveat: the capture tool only renders the unauthenticated `/` landing page, which Phase 37 did not change. No Phase 37 surface (kitchen board, staff, invite, finance, developers) was seen rendered. Every finding below is code-derived.
**Interaction captures:** off (workflow.ui_interaction_capture is false). Interaction findings are code-derived.
**Ruled deferral (not scored):** the dashboard is forced to the light theme and the theme control is withheld (owner ruling 2026-10-16, quick 261016-edv). The `dark:` classes in new dashboard components (e.g. `invite-staff-form.tsx:71`) are dead code until the dark pass ships.

---

## Pillar Scores

| Pillar | Score | Key Finding |
|--------|-------|-------------|
| 1. Copywriting | 3/4 | Specific, recoverable copy. Generic "Something went wrong" fallbacks remain in 8 places. |
| 2. Visuals | 3/4 | Strong a11y semantics. Not verified in a render, and the loader/spinner accent is off-contract. |
| 3. Color | 2/4 | `orange-600` is used as a non-reserved accent. Hardcoded hex remains. |
| 4. Typography | 2/4 | The contract bars `font-medium` and 12px text. The new components use both. |
| 5. Spacing | 2/4 | A 44px touch target is declared but not applied everywhere. Off-scale padding is widespread. |
| 6. Experience Design | 3/4 | Loading, error and disabled coverage is good. Reduced-motion handling is in one file only. |

**Overall: 15/24** (G-37-6 delta re-audit: no pillar moved, still 15/24; see the G-37-6 delta section)

---

## Top 3 Priority Fixes

1. **Typography contract broken on new components (WARNING)** — The contract allows 400 and 600 only, and 14, 16, 24 and 36px. The 17 new files use `font-medium` (about 25 occurrences, e.g. `kds-card-parts.tsx:46,51`, `hours-notice-card.tsx:85,112`, `reject-orders-dialog.tsx:300,331`, `void-order-dialog.tsx:213`) and `text-xs` 12px (`trader-identity-form.tsx:206,231,291`, `seller-block.tsx:108`, `basket-line-allergens.tsx:70-87`, `kds-new-lane.tsx:427`, `account-client.tsx:186,241`). Fix: change `font-medium` to `font-semibold` or drop it. Raise `text-xs` to `text-sm`. The allergen lines are safety copy and are the worst place for 12px.
2. **Touch target and accent drift (WARNING)** — `trader-identity-form.tsx:25` uses `h-10`, which the contract measured at 40px and barred. Fix: change it to `h-11`. The `Loader2` spinners in `invite-client.tsx:238,274` and `data-request/{confirm,download}-client.tsx:113-114` use `text-orange-600`. The contract limits the accent to 4 named uses (orange-700), and spinners are not one of them. Fix: use `text-slate-500`, or `text-primary` if the spinner is deliberately accented.
3. **Generic fallback errors (WARNING)** — "Something went wrong" appears at `checkout/page.tsx:428`, `basket-changed-panel.tsx:46`, `orders-client.tsx:267`, `invite-client.tsx:74`, `account-client.tsx:84` and `shop-discovery-client.tsx:389`. Several tell the user their data is safe, which is good. The checkout line gives no cause and no next step beyond "try again". Fix: use the contract's typed-problem copy (§ API problem details) for each case.

Further issues (the list is longer than three):
- The off-scale padding the contract says "is neither copied nor changed" is common across the 94 changed files: `gap-3` ×86, `px-3` ×68, `py-2.5` ×38, `p-3` ×30, `py-3` ×26. Check which are in new files.
- `page.tsx` and `dashboard/page.tsx` carry hardcoded hex, including chart colours in `dashboard/page.tsx:107-115`. `checkout/page.tsx:1285` uses `#f97316` for Stripe `colorPrimary`, which is orange-500, not the orange-700 token. Chart colours are arguably legitimate, but the Stripe value contradicts the `--primary` ruling.
- Arbitrary values such as `h-[90vh]` ×6 and `w-[220px]` ×3 are unrelated to the spacing scale.
- `prefers-reduced-motion` appears only in `kds-new-lane.tsx:67`. The other animated new pieces (`animate-spin` loaders, the board-stopped overlay) were not checked and have no grep evidence of handling.
- Of the 94 files, 72 hits of `min-h-11` or `h-11` were found in new files, so the 44px rule is mostly followed. The exception is the `h-10` above.

---

## Detailed Findings

### Pillar 1: Copywriting (3/4)
- Positive: error and recovery copy is specific and reassuring ("Your link still works", "Your orders are safe", "Your basket is saved"). No bare `Submit` / `OK` / `Click Here` button labels were found in changed files.
- Gap: the 8 generic "Something went wrong" occurrences listed above. The contract's "Words this phase must not write" list was not compared line by line, so this score is provisional.

### Pillar 2: Visuals (3/4)
- Positive: aria usage is heavy in the new surfaces (`invite-client.tsx` 25, `kds-new-lane.tsx` 14, `opening-schedule-editor.tsx` 10). Errors use `role="alert"`. Decorative spinners are `aria-hidden`.
- Both `size="icon"` buttons (`kds-new-lane.tsx:466`, `kitchen/page.tsx:1222`) need their `aria-label` confirmed. It was not checked.
- Not verified against any render, because the screenshots do not reach the changed surfaces. This caps the score at 3.

### Pillar 3: Color (2/4)
- Accent usage across the changed files: `text-primary|bg-primary|border-primary|orange-` is highest in `basket-changed-panel.tsx` (7), `invite-client.tsx` (7), `mobile-tab-bar.tsx` (6) and `data-request/download-client.tsx` (6). The contract reserves the accent for primary buttons, the focus ring, the active sidebar item and the NEW strip. The data-request pages and invite focus rings use `orange-600` literally, a different shade from `--primary` orange-700. The spinners are off the reserved list.
- Hardcoded hex: 4 gradient stops in `app/page.tsx:68-71` (a Phase 37 file that is a landing page, not a contract surface), chart colours, and the Stripe `#f97316`.
- Ruled deferral, not scored: no dark theme on the dashboard.

### Pillar 4: Typography (2/4)
- Size distribution across the 94 changed files: `text-sm` 519, `text-xs` 216, `text-lg` 66, `text-base` 48, `text-2xl` 31, `text-xl` 18, `text-4xl` 17, `text-3xl` 6. The contract allows 4 sizes. Extended legacy pages may keep grandfathered values, but the 12px use in new files is not grandfathered.
- Weights: `font-bold` appears mainly on grandfathered pages (checkout 10, cart-drawer 6, finance 6), which is allowed. `font-medium` on new components is not.

### Pillar 5: Spacing (2/4)
- The declared scale is multiples of 4 only, with no 12px token. Found: `gap-3` ×86, `px-3` ×68, `py-2.5` ×38, `p-3` ×30, `py-3` ×26, `gap-1.5` ×22, `px-5` ×20. Even allowing for pre-existing surfaces, new files include `px-3` in the shared field classes (`invite-staff-form.tsx:71`, `invite-client.tsx:131`, `trader-identity-form.tsx:25`).
- Touch targets: the `h-10` above breaks the 44px rule. The rest of the new work uses `min-h-11` / `h-11` consistently.

### Pillar 6: Experience Design (3/4)
- Positive: loading, error and disabled states are present in every new interactive component (e.g. `opening-schedule-editor.tsx`, `pause-orders-control.tsx`, `credential-create-dialog.tsx`, `invite-client.tsx`). Destructive actions have dedicated confirmation dialogs (`void-order-dialog`, `reject-orders-dialog`, `confirm-action-dialog`, `secret-reveal-dialog`).
- Gap: reduced-motion handling is evidenced in one file only. Keyboard and focus behaviour of the K2 overlay was not exercised.
- Registry audit: skipped (the UI-SPEC lists no third-party registry blocks used here, and components.json was not checked).

---

## G-37-6 delta (37-56..37-59), audited 2026-10-16

**Scope:** `git diff 36f9aee3..HEAD -- frontend` (base from `evidence/37-56-g376-base.txt`) plus the core-java email copy (copywriting only). **Baseline:** 37-UI-SPEC.md (Spacing exceptions: 44 x 44 px minimum; Copywriting B4-B6). **Screenshots:** none taken in this delta. The live geometry in `evidence/37-59-g376-runtime.md` (§4.2 per-width tables at 390/768/1024/1280, §5 threshold measurements) is used instead; it was measured by the 37-59 browser spec on a runtime proved fresh (§3, freshness rc 0). The static capture tool still cannot reach authenticated surfaces, so nothing in this delta was independently re-rendered by this audit. **Interaction captures:** off; interaction findings are code-derived or taken from the 37-59 evidence. **Verdict: no pillar score moves; Overall stays 15/24.** The defects are closed. The remaining deductions are older and are not touched by the delta.

### Pillar 1: Copywriting (3/4, unchanged)
- Closed: the double full stop ("Co..") is fixed through one rule, `frontend/lib/sentence-end.ts:30-39` (`withFullStop`, terminators `.!?…`), applied at `order-allergen-panel.tsx` (intro), `recorded-allergen-set.tsx` (allergy-note-sent), `void-order-dialog.tsx:105`, `joined-toast.ts:44`, `copy-to-shop-dialog.tsx` (both result sentences). A source guard (`__tests__/sentence-end-call-sites.test.ts`) fails on any other appended stop. Live proof: §4.2 Copy, `checkout-intro` reads "...Peckham Jollof Co. Based on..." and the invitation line ends "Peckham Jollof Co." once. RED run showed the "Co.." text (§2).
- Closed: VAT helper copy sign-off. `products/page.tsx` JSDoc no longer says "OWNER SIGN-OFF PENDING"; it records the 2026-10-16 owner ruling "accepted as written" (matches 37-UAT.md `owner_ruling`). That clears the provisional flag on that string only.
- WARNING (new, minor): `joined-toast.ts:44` and `void-order-dialog.tsx:105` now rely on `withFullStop` to supply the stop, so the string literal no longer shows the sentence end. A later editor adding a literal "." gets a double stop again. The guard test covers this; the risk is low.
- Not moved: the 8 generic "Something went wrong" fallbacks listed above are untouched, and the "Words this phase must not write" list is still not compared line by line. That keeps the pillar at 3.

### Pillar 2: Visuals (3/4, unchanged)
- Closed: `finance/page.tsx:329-424` stat figures no longer paint under the next card. The grid keys on its own width (`[container-type:inline-size]` wrapper, `grid-cols-1` then 2 at 28rem then 4 at 57rem), figures are `tabular-nums` with `[overflow-wrap:anywhere]`, and the VAT text column gained `min-w-0` (l.~462). Live (§4.2): 390 -> 1 column / 358 px cards; 768 -> 2 / 216; 1024 -> 2 / 344; 1280 -> 4 / 228; every `boxFailures`, `overlaps` and `bannerFailures` list empty at the declared bound (`−£999,999.99`, 164 px).
- Closed: `void-order-dialog.tsx` was found already clean (§2.1). Its widths are 390 / 448 / 448 px with no sideways scroll, the title clears the Close control, and no void POST was sent.
- Positive: pagination hides controls with `display:none` (not just visually), so a hidden control is also out of the tab order and the accessibility tree (`pagination.tsx:~60-62`). The page-N-of-M `<span>` replaces the numbers below 21rem, so the reader is never without a position.
- WARNING: the finance fix leaves three of the four tiers 2 px wider than the measured need (§7 item 2: card 216 vs 214 px). A font-metric change or a figure past the declared bound now wraps inside its card, which is ugly but not broken. Fix: add one container tier of headroom, or cap the figure with a smaller step (`text-xl`) at the narrowest 2-column tier.
- WARNING: no independent render by this audit, and NoAccessPage at 390/768/1280 is on UAT test 6's list but unmeasured (§7 item 1). This keeps the pillar at 3 rather than 4.

### Pillar 3: Color (2/4, unchanged)
- The delta adds no accent. The finance figures keep their semantic greens, reds and amber (`text-green-700`, `text-red-700`, `text-amber-700`), which are not the reserved accent. The pagination active page keeps the default Button variant (the `--primary` fill) and the focus ring, both on the contract's reserved list.
- The prior findings stand: `orange-600` spinners and focus rings, and the `#f97316` Stripe `colorPrimary`. None of the delta files touches them.

### Pillar 4: Typography (2/4, unchanged)
- Positive: `tabular-nums` added to every finance figure (`finance/page.tsx`, stat and VAT tiles). The "Page N of M" status uses `text-sm`, an allowed size.
- Not fixed: the finance cards still use `text-sm font-medium` titles, `font-bold` figures and `text-xs` captions (`finance/page.tsx:~345,~420`). The page is a grandfathered legacy surface, so this is recorded, not newly scored. The pagination page buttons inherit `Button size="sm"` text. No new 12px or `font-medium` is introduced by the delta.
- The prior contract breach on the 17 new files is unchanged, so the pillar stays at 2.

### Pillar 5: Spacing (2/4, unchanged, one prior finding partly closed)
- Closed: the 44 x 44 px minimum on `components/ui/pagination.tsx` (`h-11 w-11` and `h-11 min-w-11`, l.~57-60). This was 32 px before and was shared by seven dashboard pages, so the fix reaches all of them. Live (§4.2, §2): every control measured 44 x 44, inside its card, inside the viewport at 375/390/768/1024/1280. Thresholds hold with 20 px (19rem tier) and 4 px (21rem and 27rem tiers) of margin (§5).
- Not closed: `trader-identity-form.tsx:25` is still `h-10` (not in the delta).
- Still off-scale: the delta adds `gap-3` (finance VAT grid), `px-2` (pagination page buttons) and `gap-x-4 gap-y-2`. `gap-3` and `px-2` are not multiples of 4 and the contract's scale has no 12 px token. The VAT grid's `gap-3` is a carry-over, the grid classes were rewritten but the gap was kept.
- WARNING: `Pagination` now sets `container-type:inline-size`. The JSDoc (l.~28-31) records that a shrink-to-fit parent would collapse it to zero width. Every current caller puts it in a `CardContent` after a Table, so this is safe today, with no automated guard against a future caller.

### Pillar 6: Experience Design (3/4, unchanged)
- Positive: the void dialog is now driven in a browser at three widths with "Keep order" closing it and a proven "no void request sent" (§4.2), which discharges the "undriven" note from the UAT. The pagination interaction is asserted: at 390 "Go to next page" shows "Showing 21-40" and "Go to first page" returns "Showing 1-20".
- Positive: the instruments were proven to fail (§4.1 arms, §5 four edge-width reds on the 37-58 build before the threshold raise). The first GREEN run caught a vacuous finance arm, and it was fixed (`1debebf8`). This is strong evidence for the falsifiability contract.
- WARNING: at 375 px (below 19rem) a vendor can only step previous or next. The first and last jumps and the page numbers are unreachable on a long list, with no replacement control. Fix: show first and last as an overflow menu, or allow a page-number input, if the product cares about long order lists on phones.
- WARNING: the "Page N of M" status has no `aria-live`, so a screen-reader user hears no confirmation after a page change except through the `aria-current` on hidden numbers (which are `display:none` at that width). Fix: `aria-live="polite"` on the status span.
- WARNING (process, not product): the combined desktop verify line is not runnable on the default dev tenant bucket (§12, rate limit 100/min). It was logged in `deferred-items.md`; it does not affect the shipped UI.
- Reduced-motion handling is still evidenced in one file only.

### Delta pillar movement

| Pillar | Prior | After delta | Why |
|--------|-------|-------------|-----|
| 1. Copywriting | 3/4 | 3/4 | Double stop and the VAT sign-off flag closed; generic fallbacks untouched |
| 2. Visuals | 3/4 | 3/4 | Overflow closed with live per-width proof; 2 px margins and one unmeasured screen keep it at 3 |
| 3. Color | 2/4 | 2/4 | No colour change |
| 4. Typography | 2/4 | 2/4 | `tabular-nums` added; the contract breach on new files stands |
| 5. Spacing | 2/4 | 2/4 | The 44 px target is fixed on pagination, but `h-10` and off-scale gaps remain |
| 6. Experience Design | 3/4 | 3/4 | Dialog and pagination are proven in a browser; phone page-jump and live-region gaps |

**Delta top fixes (in addition to the three above):** (1) give the narrowest pagination tier a way to jump pages and an `aria-live` status (`pagination.tsx`); (2) add headroom to the 2 px finance container tiers (`finance/page.tsx` stat 28rem/57rem, VAT 54rem) or step the figure size down at the 2-column tier; (3) measure NoAccessPage at 390/768/1280, which the UAT lists and no instrument covers.

---

## Files Audited
Contract and summaries: `37-UI-SPEC.md` (spacing, typography, color sections). Code-scanned: 94 changed `.tsx` files under `frontend/` (excluding tests), including the 47 new files such as `components/dashboard/kitchen/*`, `components/dashboard/staff/*`, `components/dashboard/developers/*`, `components/storefront/*`, `app/invite/*`, `app/data-request/*` and `app/dashboard/developers/*`. Screenshot reviewed: `mobile.png` (landing page only).

G-37-6 delta files (37-56..37-59): `frontend/app/dashboard/finance/page.tsx`, `frontend/components/ui/pagination.tsx`, `frontend/components/dashboard/orders/void-order-dialog.tsx`, `frontend/components/dashboard/products/copy-to-shop-dialog.tsx`, `frontend/components/storefront/order-allergen-panel.tsx`, `frontend/components/storefront/recorded-allergen-set.tsx`, `frontend/lib/joined-toast.ts`, `frontend/lib/sentence-end.ts`, `frontend/app/dashboard/staff/page.tsx`, `frontend/app/dashboard/products/page.tsx`. Evidence read: `evidence/37-59-g376-runtime.md`, `37-UAT.md` (G-37-6 entry and owner ruling). Tests, `frontend/e2e/screens-copy-uat.spec.ts` and core-java email copy (`Sentences.java`, `EmailNotificationService`, `EmailTemplateRenderer`) were taken from the 37-59 evidence, not re-read line by line. No screenshots in this delta.
