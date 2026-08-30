# Phase 35: Horizontal Layout Contract — Pattern Map

**Mapped:** 2026-08-29
**Upstream inputs read:** `CONTEXT.md` (no `RESEARCH.md` exists in this phase dir — confirmed by `ls`)
**Route files analysed:** 66 under `frontend/app/` (38 `page.tsx`, 21 `layout.tsx`, 3 `error.tsx`, 3 `loading.tsx`, 1 `not-found.tsx`)
**Width-bearing surfaces inventoried:** 60 sites across 30 files
**New artefacts needing an analog:** 3 (constants module, contract spec, tailwind theme extension) — 2 have strong analogs, 1 has **none**

---

## 0. The three structural findings that reshape the change list

Read these before the inventory. Each one changes *where* the planner writes edits.

### F-1. No dashboard page owns a width container. All 21 inherit one line.

`theme.container` is consumed by **exactly one** call site in the whole tree:

```
components/dashboard/dashboard-shell.tsx:55
<div className="container mx-auto p-4 pb-20 sm:p-8 sm:pb-20 md:pb-8 dark:text-slate-100">
```

Measured with a class-context pattern (`(className|class|cn\()…["'`\s]container[\s"'`]`) over `app/` + `components/`: **1 hit**. The naive `rg 'container'` returns 269 hits over 55 files — almost all of them `DialogContent`, `CardContent`, `TabsContent`, `container` locals in tests. **Do not use the naive count.**

Every one of the 15 dashboard sub-layouts is a metadata-only pass-through (`return children`), verified individually; `rg -l 'className'` over all 16 dashboard layouts returns **rc=1, no match**. No dashboard `page.tsx` adds an outer `mx-auto max-w-*` either.

**Consequence for the plan:**

| Tier | Mechanism | Edit count |
|---|---|---|
| **Shell** | change the cap at `dashboard-shell.tsx:55` (or `tailwind.config.ts:31-37`) | **1 site** |
| **Index** | *no edit* — index pages already inherit the shell with no further cap | **0 sites** (but see F-3) |
| **Detail** | **add** a `~1100px` wrapper where none exists today | **~5 new wrappers** |
| **Marketing** | `app/page.tsx` only; the other three marketing surfaces are already at 1280 | **1 file, 4 lines** |

Index is "free" only in the mechanical sense. It is invisible to any test, which is the problem F-3 addresses.

### F-2. Two of the four tiers have no stock Tailwind token. Marketing already has one.

Tailwind 3.4.1 (`package.json:65`), stock `maxWidth` scale, and `rg 'maxWidth' tailwind.config.ts app/globals.css` returns **rc=1 — there is no `theme.extend.maxWidth` today**.

| Tier | Target | Stock token | Gap |
|---|---|---|---|
| Shell | ~1700px | none (`7xl` = 1280 is the ceiling) | needs `theme.extend.maxWidth` or an arbitrary value |
| Index | fluid to shell | n/a | inherits |
| Detail | ~1100px | none (`5xl` = 1024, `6xl` = 1152) | needs `theme.extend.maxWidth` |
| **Marketing** | **~1280px** | **`max-w-7xl` = 80rem = exactly 1280px** | **none — already the idiom** |
| Prose | 68ch | `max-w-[68ch]` arbitrary, 6 uses | leave |

The Marketing tier is **already ~75% shipped**: `max-w-7xl` is the container on `/for-operators`, `/business-model-guide`, `/competitive`, **and both public chrome components** (`public-header.tsx:79`, `public-footer.tsx:83`). Only `app/page.tsx` sits at `max-w-6xl` (1152).

**Incremental Betterment argument the planner should use:** today the landing page's content band (1152) is *narrower than its own header rail* (1280) — the header's nav and the hero's text do not share a left edge. Moving `/` to `max-w-7xl` does not invent a number; it makes the landing page agree with the chrome that is already wrapped around it, and with its three sibling marketing routes. That is an additive alignment, not a re-layout.

### F-3. Index is untestable unless it is *declared*, not merely inherited.

"Index = fluid to shell" implemented as "change nothing" produces a contract that **no assertion can distinguish from the bug**. A spec that measures `/dashboard/orders` and finds 1700px cannot tell whether that is because the page is deliberately Index-tier or because someone forgot to give it a Detail cap. Both read identically.

CONTEXT §5 demands every criterion be run against a deliberately broken input. A width contract where three of five tiers share one DOM shape has no break arm. The planner should require an explicit, queryable tier marker (a `data-layout-tier` attribute or a shared wrapper component) so the spec asserts *declared tier → measured width*, and a mis-tiered page is a red rather than a silent pass.

---

## 1. Full inventory

Tier vocabulary: **Shell** (~1700, dashboard chrome) · **Index** (fluid to shell) · **Detail** (~1100) · **Marketing** (~1280) · **Prose** (68ch, leave) · **N/A-container** (not a page-width surface: overlay, dialog, spinner, truncation clamp, chrome rail).

Tailwind reference used throughout: `2xl`=672 · `3xl`=768 · `4xl`=896 · `5xl`=1024 · `6xl`=1152 · `7xl`=1280 · `lg`=512 · `md`=448.

### 1a. Dashboard shell + chrome

| File | Idiom | Line | What it is | Proposed tier | Confidence · rationale |
|---|---|---|---|---|---|
| `components/dashboard/dashboard-shell.tsx` | `container` (1400 @2xl) | **55** | the single authenticated content band; wraps every `/dashboard/**` page | **Shell** | **High** — the one root cause named in CONTEXT §2; this line is the whole Shell tier |
| `tailwind.config.ts` | `theme.container.screens["2xl"]: "1400px"` | **31–37** | stock shadcn scaffold block, shipped verbatim | **Shell (source of truth)** | **High** — 1 consumer only (F-1), so editing it here is safe; but see §3 for why a shared constant is better than editing this literal |
| `components/dashboard/dashboard-shell.tsx` | `max-w-[55%]` | 50 | mobile top-bar shop-switcher column clamp | **N/A-container** | **High** — a `md:hidden` truncation clamp; CONTEXT §5 requires mobile byte-identical, so do not touch |
| `components/dashboard/sidebar.tsx` | `w-64` (256px) | 65 | `hidden md:flex` desktop sidebar | **N/A-container** | **High** — fixed rail; it is the 256px CONTEXT §1 subtracts, not a content cap |
| `app/dashboard/layout.tsx` | — | 35 | `return <DashboardShell>{children}</DashboardShell>` | **N/A-container** | **High** — pass-through |
| 15 × `app/dashboard/**/layout.tsx` | none | — | metadata-only carriers, all `return children` | **N/A-container** | **High** — verified: `rg -l 'className'` over all of them → rc=1, no match |
| `app/dashboard/error.tsx` | none | 18 | `flex min-h-[50vh]` centred error panel | **N/A-container** | **High** — inherits the shell; no cap of its own |

### 1b. Dashboard routes (all inherit `container`; none declares a width today)

| Route file | Idiom | Line | What the surface IS | Proposed tier | Confidence · rationale |
|---|---|---|---|---|---|
| `app/dashboard/page.tsx` | inherited | — | overview: KPI cards, recharts, recent-orders table (`overflow-x-auto` :448) | **Index** | Med-High — data surface; its table is the same shape as `/dashboard/orders`. See §2 A-9 |
| `app/dashboard/orders/page.tsx` | inherited | — | six-column orders table (`overflow-x-auto` :637) | **Index** | **High** — the motivating artefact in CONTEXT §1 (900px empty gutter @2560) |
| `app/dashboard/orders/[id]/page.tsx` | inherited | — | single-order detail: line items, timeline, refund | **Detail** | **High** — CONTEXT §4 names "order detail" as the Detail exemplar |
| `app/dashboard/products/page.tsx` | inherited | — | products table; the **A11Y-3 focusable region** lives here (`tabIndex={0}` :478-479) | **Index** | **High** — table-first; CONTEXT §5 flags this exact page's scroll region |
| `app/dashboard/products/import/page.tsx` | inherited | — | CSV import wizard, tabbed form (`space-y-6` :36) | **Detail** | **High** — a form, not a list |
| `app/dashboard/customers/page.tsx` | inherited | — | customers table (`overflow-x-auto` :288) | **Index** | **High** — named in CONTEXT §4 |
| `app/dashboard/shops/page.tsx` | inherited | — | shops table (`overflow-x-auto` :336) | **Index** | **High** — named in CONTEXT §4 |
| `app/dashboard/finance/page.tsx` | inherited | — | VAT/transaction ledger table (`overflow-x-auto` :293) + charts | **Index** | Med — see §2 A-4 |
| `app/dashboard/marketing/page.tsx` | inherited | — | two campaign tables (`overflow-x-auto` :802, :969) | **Index** | Med-High — see §2 A-5 |
| `app/dashboard/kitchen/page.tsx` | inherited | — | KDS card board, STOMP-live, no table | **Index** | Med — see §2 A-6 |
| `app/dashboard/staff/page.tsx` | inherited | — | staff table + grant form in a Card | **Index** | Med — see §2 A-7 |
| `app/dashboard/onboarding/page.tsx` | inherited | — | onboarding state-machine form + gate checklist | **Detail** | **High** — a form with sequential gates; width buys nothing |
| `app/dashboard/onboarding/approvals/page.tsx` | inherited | — | approvals queue of review cards + dialogs | **Index** | Med — see §2 A-8 |
| `app/dashboard/media/review/page.tsx` → `components/dashboard/media/ReviewQueue.tsx` | inherited (`space-y-8` :194) | — | flagged-media review queue, card grid | **Index** | Med — a queue; but it owns a 320px spec, see §4 B-5 |
| `app/dashboard/webhooks/page.tsx` | inherited | — | subscriptions table (`hidden overflow-x-auto sm:block` :303) | **Index** | **High** — table-first |
| `app/dashboard/webhooks/[id]/page.tsx` | inherited | — | delivery log — a `[id]` **detail route** whose body is a wide table (:444) | **Index** | **Low — genuinely ambiguous**, see §2 A-3 |
| `app/dashboard/payments/connect/return/page.tsx` → `connect-outcome.tsx` | `max-w-2xl` (672) | **149** | Stripe Connect return status panel, 3 paragraphs | **Detail — already compliant, leave** | **High** — 672 < 1100; widening prose-length status copy is a regression |
| `app/dashboard/payments/connect/refresh/page.tsx` → same component | `max-w-2xl` (672) | **149** | Stripe Connect refresh status panel | **Detail — already compliant, leave** | **High** — same component, same reasoning |

Dashboard dialog caps — **all N/A-container** (Radix portals; rendered outside the page container, so the page tier cannot reach them, and their width is a modal-ergonomics decision not a layout-contract one):
`customers/page.tsx:413` · `marketing/page.tsx:1082,1309` · `orders/page.tsx:773,949` · `products/page.tsx:633` · `shops/page.tsx:424` (`max-w-2xl`) · `components/dashboard/orders/RefundDialog.tsx:187` · `components/dashboard/webhooks/{ConfirmActionDialog:58,SecretRevealDialog:68,WebhookCreateDialog:125}` (`max-w-md`) · `components/ui/dialog.tsx:39` (`max-w-lg` default).

Dashboard text clamps — **all N/A-container**: `marketing/page.tsx:993` (`max-w-[200px]` truncate cell) · `webhooks/page.tsx:319` (`max-w-[260px]` truncate cell) · `marketing/page.tsx:287`, `webhooks/page.tsx:290`, `webhooks/[id]/page.tsx:433`, `components/dashboard/load-error-panel.tsx:40` (empty-state copy measure).

### 1c. Marketing / public surfaces

| File | Idiom | Line(s) | What the surface IS | Proposed tier | Confidence · rationale |
|---|---|---|---|---|---|
| `app/page.tsx` | `max-w-6xl` ×4 | **187, 276, 292, 318** | `/` landing — hero, near-you row, how-it-works, CTA. Wrapped in `PublicShell` (:171) | **Marketing → 1280** | **High** — the only marketing surface off-tier; brings it into line with its own header rail (F-2) |
| `app/page.tsx` | `max-w-xl` | 200 | hero sub-paragraph measure | **Prose-leave-alone** | **High** — a text measure inside the band |
| `components/marketing/operator-pitch.tsx` | `max-w-7xl` ×3 | **73, 80, 102** | `/for-operators` sticky bar, hero grid, body | **Marketing — already compliant** | **High** — 1280 = target |
| `components/marketing/business-model-guide.tsx` | `max-w-7xl` ×4 | **118, 143, 161, 255** | `/business-model-guide` header, tab rail, body, footer | **Marketing — already compliant** | **High** |
| `components/marketing/competitive-teardown.tsx` | `max-w-7xl` ×4 | **220, 250, 266, 560** | `/competitive` header, filter rail, body, footer | **Marketing — already compliant** | **High** |
| `components/public/public-header.tsx` | `max-w-7xl` | **79** | shared sticky header rail for all `PublicShell` routes | **Marketing chrome — already compliant** | **High** — must stay equal to the Marketing tier or the misalignment inverts |
| `components/public/public-footer.tsx` | `max-w-7xl` | **83** | shared footer rail (also used by `/shop` layout) | **Marketing chrome — already compliant** | **High** |
| `components/public/public-shell.tsx` | **none** | 45–51 | `flex min-h-screen flex-col` + uncapped `<main id="main">` | **N/A-container** | **High** — deliberately width-free; children own the band. Do **not** add a cap here: `/shop`'s layout is a separate tree and `PolicyPage` nests inside it |
| `components/marketing/*` inner clamps | `max-w-{2xl,3xl,4xl,md,sm}` | operator-pitch 83,84,111,134 · business-model-guide 126,130,159,187,233 · competitive-teardown 232,236,270,273,294,364,453,494,499 | headline and paragraph measures inside the 1280 band | **Prose-leave-alone** | **High** — typographic measure, orthogonal to the page tier |
| `components/marketing/hero-search.tsx` | `max-w-xl` | 39 | landing search form width | **N/A-container** | **High** — a control, not a band. **CLS-sensitive** (CONTEXT §5) |
| `components/public/consent-banner.tsx` | `max-w-3xl` | 72 | fixed consent overlay | **N/A-container** | **High** — overlay |
| `components/public/cookie-notice.tsx` | `max-w-3xl` | 114 | fixed cookie notice overlay | **N/A-container** | **High** — overlay; mounted from `app/layout.tsx:48`, so it appears over *every* surface |

### 1d. Legal / prose

| File | Idiom | Line(s) | What the surface IS | Proposed tier | Confidence · rationale |
|---|---|---|---|---|---|
| `components/legal/policy-page.tsx` | `max-w-6xl` | **112** | outer two-column band for all 4 policy pages (68ch column + sticky TOC rail) | **Leave alone** (or Marketing — see §2 A-2) | Med — the 1152 is sized to *68ch + rail + gap*, not chosen as a page width |
| `components/legal/policy-page.tsx` | `max-w-[68ch]` ×3 | **124, 151, 156** | title block, body column, back-link | **Prose — leave alone** | **High** — CONTEXT declares prose out of scope; **and a test asserts this class string**, see §4 B-8 |
| `app/legal/page.tsx` | `max-w-3xl` (768) | **72** | `/legal` policy index — a `<dl>` of company facts + a policy list | **Leave alone** | Med — see §2 A-1 |
| `app/legal/{privacy,cookies,retention,accessibility}/page.tsx` | inherited from `PolicyPage` | — | the four published policies | **inherits Prose** | **High** — all four render through `PolicyPage`, no own container |
| `app/legal/cookies/page.tsx` | `overflow-x-auto` region | 64, 243 | focusable cookie table region (`role=region` + `tabIndex=0`) | **N/A-container** | **High** — a scroll region inside the 68ch column |
| `components/legal/retention-table.tsx` | `overflow-x-auto` + `tabIndex={0}` | 117–120 | focusable retention table region | **N/A-container** | **High** — same |

### 1e. Storefront (`/shop`)

| File | Idiom | Line(s) | What the surface IS | Proposed tier | Confidence · rationale |
|---|---|---|---|---|---|
| `app/shop/layout.tsx` | `max-w-7xl` | **64** | storefront sticky header rail (a deliberate verbatim mirror of `PublicShell`, :34-53) | **Marketing chrome — leave at 1280** | **High** — must stay equal to `public-header.tsx:79`; the layout's own `<main>` (:81) is uncapped |
| `app/shop/shop-discovery-client.tsx` | `max-w-7xl` ×2 | **384, 635** | `/shop` directory — search + shop card grid + skeleton | **Marketing (leave at 1280)** — **contested** | **Low — the single most consequential ambiguity**, see §2 A-10 |
| `app/shop/loading.tsx` | `max-w-7xl` | **12** | `/shop` route skeleton | **must MATCH `/shop`'s final tier** | **High** — skeleton/content parity; a mismatch is a visible width jump on hydration |
| `app/shop/[slug]/shop-detail-client.tsx` | `max-w-4xl` ×9 | **423, 462, 478, 528, 564, 638, 672, 713, 742, 820** | shop detail: hero overlay, info bar, category rail, menu list | **Detail (896 → ~1100)** — **contested** | Med — see §2 A-11 |
| `app/shop/[slug]/loading.tsx` | `max-w-7xl` | **14** | `/shop/[slug]` skeleton | **BUG — mismatched today**, see §2 A-12 | **High** — skeleton 1280 vs content 896 |
| `app/shop/[slug]/not-found.tsx` | `max-w-4xl` | **30** | shop-not-found panel | **matches `shop-detail-client`** | **High** — consistent today; keep it consistent |
| `app/shop/[slug]/layout.tsx` | none | 16–23 | `CartProvider` + `CartDrawer`, no DOM band | **N/A-container** | **High** |
| `app/shop/[slug]/cart/page.tsx` | `max-w-2xl` ×2 | **19, 35** | basket review | **Detail — already narrower, leave** | **High** — a linear checkout step; 672 is right |
| `app/shop/[slug]/checkout/page.tsx` | `max-w-2xl` ×4 | **371, 509, 583, 719** | checkout form (address, payment, confirm) | **Detail — already narrower, leave** | **High** — same |
| `app/shop/[slug]/orders/[orderNumber]/page.tsx` | `max-w-lg` ×4 | **53, 131, 147, 336** | customer order receipt | **Detail — already narrower, leave** | **High** — a receipt; 512 is deliberate |
| `app/shop/orders/orders-client.tsx` | `max-w-lg` ×3 | **375, 384, 399** | customer order history list | **Detail — already narrower, leave** | Med-High — a phone-first list; widening would strand it |
| `app/shop/signin/page.tsx` | `max-w-md` | **42** | customer sign-in | **N/A-container (auth card)** | **High** |
| `app/shop/auth/callback/page.tsx` | none | 71, 83, 94 | OIDC callback spinner | **N/A-container** | **High** |
| `app/shop/error.tsx` | none | 18 | storefront error panel | **N/A-container** | **High** |
| `app/shop/shop-discovery-client.tsx` | `max-w-xl` ×3 | 390, 396, 637 | sub-heading measure, search input, search skeleton | **N/A-container** | **High** — controls/measures |
| `components/storefront/customer-signin-prompt.tsx` | `max-w-md` | 30 | sign-in prompt panel | **N/A-container** | **High** |
| `components/storefront/cart-drawer.tsx` | `sm:max-w-md` | 63 | slide-over sheet | **N/A-container** | **High** — overlay |
| `components/storefront/product-detail-modal.tsx` | `max-w-lg` | 119 | dish modal | **N/A-container** | **High** — overlay |
| `components/storefront/storefront-nav.tsx` | `max-w-[100px]` | 150 | session-pill name truncation | **N/A-container** | **High** |

### 1f. Auth, utility, root

| File | Idiom | Line(s) | What the surface IS | Proposed tier | Confidence · rationale |
|---|---|---|---|---|---|
| `app/layout.tsx` | none | 39–52 | root `<html>/<body>` + providers + `CookieNotice` | **N/A-container** | **High** — no band; `dynamic = "force-dynamic"` lives here, do not disturb |
| `app/loading.tsx` | none | 16 | root navigation loading boundary | **N/A-container** | **High** |
| `app/error.tsx` | none | 18 | root error boundary | **N/A-container** | **High** |
| `app/auth/signin/page.tsx` | `max-w-md` | **74** | vendor sign-in card. **Deliberately NOT in `PublicShell`** (:52, documented) | **N/A-container (auth card)** | **High** — a 448px card is the correct shape |
| `app/auth/signin/page.tsx` | `max-w-md` | 146 | legal line under the card | **N/A-container** | **High** — matched to the card |
| `app/auth/signin/layout.tsx` | none | 43 | metadata-only, `return <>{children}</>` | **N/A-container** | **High** |
| `app/track/page.tsx` | `max-w-lg` | **249** | guest order-tracking form, inside `PublicShell` (:82) | **Detail — already narrower, leave** | **High** — a two-field lookup form |
| `app/track/page.tsx` | `max-w-xs` | 297 | empty-state copy measure | **N/A-container** | **High** |
| `app/track/layout.tsx` | none | 46 | metadata-only, `return children` | **N/A-container** | **High** |
| `app/unsubscribe/unsubscribe-content.tsx` | `max-w-lg` | **96** | one-click opt-out confirmation, inside `PublicShell` (`page.tsx:38`) | **Detail — already narrower, leave** | **High** — single-purpose transactional panel |
| `components/ui/dialog.tsx` / `sheet.tsx` / `toast.tsx` / `image-uploader.tsx` | `max-w-{lg,sm,[420px],[160px],[200px]}` | dialog:39 · sheet:39,41 · toast:17 · uploader:298,299 | primitives | **N/A-container** | **High** — component-level defaults |
| `components/ui/table.tsx` | `overflow-auto` wrapper | **9** | every `<Table>` is already wrapped in `relative w-full overflow-auto` | **N/A-container** | **High** — but load-bearing for A11Y-3, see §4 B-6 |

---

## 2. Ambiguous cases — competing readings, with a recommendation

Twelve surfaces could defensibly take two tiers. None is silently resolved.

### A-1. `app/legal/page.tsx:72` — `max-w-3xl` (768)
- **Reading 1 (Prose, leave):** it is a `<dl>` of company facts plus a linked policy list — reading matter. 768 sits between the 68ch measure and the Detail tier and reads as a deliberate prose choice.
- **Reading 2 (Marketing, 1280):** it is a public, indexable, *index* page. Its sibling under `PolicyPage` is 1152. At 768 inside a 1280 header rail it is visibly the narrowest public page in the product.
- **RECOMMENDED: leave alone (Prose-adjacent).** Its content is text with no columns to gain from width, CONTEXT declares prose out of scope, and widening a 6-item list to 1280 produces a mostly-empty band — the exact defect this phase exists to remove, reintroduced on a different page. *Record it as an explicit N/A rather than dropping it.*

### A-2. `components/legal/policy-page.tsx:112` — `max-w-6xl` (1152)
- **Reading 1 (leave):** the 1152 is not a page width, it is *68ch body + sticky TOC rail + `lg:gap-12`* summed. It is derived, and changing it changes the rail's relationship to the text.
- **Reading 2 (Marketing, 1280):** it is a public surface directly under the 1280 header rail, and inherits the same misalignment `app/page.tsx` has.
- **RECOMMENDED: leave alone.** The 68ch column is fixed and out of scope, so extra width goes entirely to the gap between text and rail — no reader benefit, and it moves the sticky TOC further from the prose it indexes. **If** the planner takes it to 1280 for chrome alignment, it MUST be treated as a *displaced good* under Incremental Betterment (the current text↔rail proximity) and the a11y test at `components/legal/__tests__/policy-page.a11y.test.tsx:141,149` re-run — that test queries `[class*="max-w-[68ch]"]` by class string.

### A-3. `app/dashboard/webhooks/[id]/page.tsx` — Detail route, Index content
- **Reading 1 (Detail, ~1100):** it is a `[id]` route. Every other `[id]` route in the dashboard (`orders/[id]`) is Detail. Route shape should predict tier, or the contract is not a contract.
- **Reading 2 (Index, fluid):** the reason to open it is the **delivery log** — a wide, multi-column, timestamp-heavy table with its own `overflow-x-auto` at :444. Squeezing it to 1100 makes it scroll horizontally *more* than it does today at 1400. That is a strict regression on the page's primary job.
- **RECOMMENDED: Index.** Tier by *content shape*, not route shape — Polaris's own rule (CONTEXT §3) is "full-width for lists of data that have many columns", and this is one. **State the exception in the contract doc** so the next reader does not "fix" it back to Detail. This is the case that proves tier must be *declared* (F-3), because route-shape inference gets it wrong.

### A-4. `app/dashboard/finance/page.tsx` — ledger table + charts
- **Reading 1 (Index):** the transaction ledger is a wide multi-column table (`overflow-x-auto` :293) — VAT rate, amounts, order id, timestamps. Exactly the Polaris case.
- **Reading 2 (Detail):** the charts above it are fixed-aspect recharts panels; stretching them to 1700 produces very wide, very short plots that read worse.
- **RECOMMENDED: Index.** The table is the surface's reason to exist; the chart band can be capped *within* an Index page by a local wrapper, which is an additive change. The inverse — capping the whole page to protect the charts — starves the ledger, which is the defect being fixed. **Verify visually at 2560 before and after** (CONTEXT §6 already requires a visual capture for orders; add finance).

### A-5. `app/dashboard/marketing/page.tsx` — two tables + composer dialogs
- **Reading 1 (Index):** two `overflow-x-auto` campaign tables (:802, :969).
- **Reading 2 (Detail):** most *work* here happens in the campaign composer, which is a form.
- **RECOMMENDED: Index.** The composers are Radix `DialogContent` at `max-w-2xl` (:1082, :1309) — rendered in a portal, **outside** the page container, so the page tier cannot affect them either way. The competing reading dissolves on inspection: only the tables are governed by this page's tier.

### A-6. `app/dashboard/kitchen/page.tsx` — KDS board
- **Reading 1 (Index):** a live operational board of order cards; more width = more columns visible = fewer glances during service. This is the highest-value width gain in the product.
- **Reading 2 (Shell/none):** it is a card grid, not a table, so the Polaris "many columns" justification does not literally apply.
- **RECOMMENDED: Index.** A card grid that reflows into more columns is the *same* argument as a wide table, and a kitchen display is the one dashboard surface likely to run on a genuinely large screen. **Hazard:** it is STOMP-live with `AnimatePresence`; more columns changes the reflow on every order transition. Exercise `e2e/kitchen-flow.spec.ts` and `e2e/stomp-relay.spec.ts` after the change.

### A-7. `app/dashboard/staff/page.tsx` — table + grant form
- **Reading 1 (Index):** a staff table plus a shop-scope table; list-first.
- **Reading 2 (Detail):** the grant flow (pick user, pick shop, pick role) is a form.
- **RECOMMENDED: Index.** The grant form lives inside a `Card`, which is already narrower than the band; a Card inside an Index page keeps its own width. Tiering the page Detail to protect a Card would cap the tables for no gain.

### A-8. `app/dashboard/onboarding/approvals/page.tsx` — approvals queue
- **Reading 1 (Index):** it is a *queue* — the Polaris resource-index case by name.
- **Reading 2 (Detail):** each entry is a review card with narrative gate reasons (business verification, hygiene rating, allergen completeness) — prose, not columns.
- **RECOMMENDED: Index**, with the review-card content capped internally. Lowest-confidence of the dashboard set. If the planner prefers Detail here, that is defensible — but it must be **stated**, not defaulted, because Detail is the only dashboard tier that requires a new wrapper and a silent default would ship it as Index.

### A-9. `app/dashboard/page.tsx` — the overview
- **Reading 1 (Index):** KPI cards + a recent-orders table (:448) that is the same six-column shape as `/dashboard/orders`.
- **Reading 2 (Detail/Shell):** it is a summary, not a resource list; the KPI row at 1700 gives four cards a lot of air.
- **RECOMMENDED: Index.** It is the landing surface of the whole dashboard and its table shares a shape with the phase's motivating artefact — two surfaces showing the same table at two different widths would be exactly the "inconsistent half-shipped layout" the brief warns against. It is also one of the three `dashboard-a11y-axe.test.tsx` pages, so it gets per-PR axe coverage for free.

### A-10. `app/shop/shop-discovery-client.tsx:384,635` — `/shop` directory. **The most consequential ambiguity.**
- **Reading 1 (Marketing, stay 1280):** it is public, unauthenticated, SEO-critical, served through storefront chrome whose header rail is `max-w-7xl` = 1280 (`app/shop/layout.tsx:64`). Keeping content and chrome equal is the same alignment argument used to *move* `/`.
- **Reading 2 (Index, fluid):** CONTEXT §1 measures it at **50.0% of a 2560 viewport** — the second-worst number in the baseline — and it is a card *grid* over a paged result set, i.e. a resource index. Under Polaris/Carbon it is precisely the full-width case.
- **RECOMMENDED: Marketing — leave at 1280.** Three reasons, in order: (i) CONTEXT §4's tier table scopes Index to "products, orders, customers, shops" — the *dashboard* resources — and scopes Shell to "dashboard chrome", so a public route taking a dashboard tier is outside the declared contract; (ii) going fluid would put the card grid wider than the header rail directly above it, inverting the misalignment this phase is fixing on `/`; (iii) it is the LCP/CLS-measured route in CONTEXT §6 and the least-cost change is none.
  **This is a recommendation, not a finding — flag it for the human gate.** If the owner wants `/shop` fluid, the header rail at `app/shop/layout.tsx:64` and `public-footer.tsx:83` must move with it, which turns a 0-file change into a 3-file change with SEO and CLS re-measurement.

### A-11. `app/shop/[slug]/shop-detail-client.tsx` — `max-w-4xl` (896) ×9
- **Reading 1 (Detail, → ~1100):** it is a menu — a vertical list of dish rows with images. Linear's 1136 and Lightspeed's 1100 are the peers. 896 is narrower than every measured peer.
- **Reading 2 (Marketing, → 1280):** it is public and SEO-bearing, and 1280 would match the chrome.
- **RECOMMENDED: Detail (~1100).** A menu row is a scannable line, not a grid; at 1280 the dish name and price drift too far apart. **But note the cost:** this is **9 sites in one file** and it is the highest-churn edit in the phase. If the planner wants to minimise risk, "leave at 896" is defensible — 896 is *within* prose-measure territory and the surface is not in CONTEXT §1's baseline table. **Recommend deferring this file unless the phase explicitly claims storefront scope.**

### A-12. `app/shop/[slug]/loading.tsx:14` vs `shop-detail-client.tsx` — **an existing defect, not a tier question**
The skeleton band is `max-w-7xl` (1280). The content that replaces it is `max-w-4xl` (896). **The page narrows by 384px the moment real content arrives.** This is a pre-existing inconsistency the inventory surfaced; it is also a latent CLS contributor on a route that has no CLS budget recorded.
- **RECOMMENDED: fix it in this phase regardless of which tier `/shop/[slug]` lands on** — skeleton and content must declare the same tier. It is a one-line change and it is exactly the class of defect a width contract exists to prevent. Same rule applies to `app/shop/loading.tsx:12` vs `shop-discovery-client.tsx:384` (currently **consistent** at 1280 — keep them so).

---

## 3. Closest existing analogs for the new artefacts

### 3a. NEW — the declared-constants module (e.g. `frontend/lib/layout-widths.ts`)

**No single analog covers both halves.** Two files together do.

**Analog for SHAPE and DOCUMENTATION DENSITY — `frontend/e2e/perf-budgets.ts`** (the precedent CONTEXT §4 names). Confirmed shape: no default export, no wrapping object, one `export const` per value, each preceded by a docblock that records *how the number was measured and what regression it catches*. Two conventions worth copying literally:

- **A derived value written as its derivation, not as a literal** (:137-138) — so the arithmetic cannot drift from the number:
```ts
export const LANDING_BUNDLE_CEILING_BYTES =
  LANDING_BUNDLE_BASELINE_BYTES + LANDING_BUNDLE_ISLAND_ALLOWANCE_BYTES
```
- **A deliberately-absent value is documented as absent** (:37-46, the "INP is deliberately NOT declared" block). Phase 35 has an exact counterpart: Index has no number, and saying so in the module is what stops someone adding one.

Also copy the `LANDING_CLS_KNOWN_BASELINE` idea (:74) — a *recorded measurement* held next to, and distinct from, a *budget*. Phase 35's equivalent is the pre-change 1400px, which the falsifiability arm needs.

**Analog for LOCATION and CROSS-BOUNDARY IMPORT — `frontend/lib/cart-identity.ts`.** This is the answer to "does anything import shared constants across app+test": **exactly one precedent exists in the repo**, and it is this file.

```
e2e/public-a11y.spec.ts:75     import { CART_KEY_PREFIX } from "../lib/cart-identity"   ← relative, from e2e
components/storefront/cart-provider.tsx:17   from "@/lib/cart-identity"                  ← alias, from app
hooks/use-cart-count.ts:8                    from "@/lib/cart-identity"
lib/customer-auth.ts:22                      from "@/lib/cart-identity"
lib/__tests__/cart-identity.test.ts:10       from "@/lib/cart-identity"                  ← alias, from jest
```

Its own docblock states the rule Phase 35 needs verbatim (:38-43): *"ONE definition, imported by the provider, the nav badge and the sign-out reaper alike — two copies of this string is how a 'clear everything' quietly starts missing keys."*

Note the **two import styles**: `@/lib/…` from app and jest (jest resolves it via `jest.config.js:11` `'^@/(.*)$': '<rootDir>/$1'`), `../lib/…` from Playwright (which has no alias mapping). A Phase 35 module must be importable both ways.

`frontend/lib/chart-colors.ts` is a third, lighter analog — a pure `as const` object of declared design values in `lib/` — but it is imported by app code only, so it does not demonstrate the test boundary.

**RECOMMENDATION: `perf-budgets.ts`'s content discipline, at `cart-identity.ts`'s address.** Put it in `frontend/lib/`, not `frontend/e2e/`, because Phase 35's constants must reach Tailwind and app components as well as specs — and `e2e/` is the wrong side of that boundary.

### 3b. NEW — the viewport-parameterised Playwright width-contract spec

Three analogs, one per property the spec needs.

**(i) Measure-in-browser + route loop + a NON-VACUITY CONTROL — `frontend/e2e/public-layout.spec.ts`.** The closest overall match, and the one that already solves Phase 35's sharpest trap. Its route loop (:213-237) is the structure to copy:

```ts
for (const route of PUBLIC_ROUTES) {
  test(`${route} …`, async ({ page }) => {
    await page.goto(`${BASE}${route}`)
    await page.waitForLoadState("domcontentloaded")
    await page.waitForTimeout(1500)          // entrance animations must not leave content hidden
    expect(await horizontalOverflow(page), "horizontal overflow (px)").toBeLessThanOrEqual(1)
  })
}
```

And its measuring helper (:110-114) — a plain `page.evaluate` returning a number, not a class-name check:

```ts
async function horizontalOverflow(page: Page): Promise<number> {
  return page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
}
```

**Copy its vacuity guard, lines 319-375, more or less verbatim.** Its docblock says: *"A missing table has a `scrollWidth` of 0, and 0 <= clientWidth is trivially true, so the fit assertion PASSES over a…"* — and the fix is an explicit `expect(size.clientWidth).toBeGreaterThan(0)` before the real assertion. A width-contract spec fails the identical way: if the band selector matches nothing, `width` is 0 and *every* "at most 1700" assertion passes. Phase 35's spec must assert the band was found and has non-zero width **before** comparing it to a tier.

**(ii) Import the declared constants instead of restating them — `frontend/e2e/landing-webperf.spec.ts:33-39`.** The only spec in the tree that imports a budget module; its header (:14) states the intent: *"…rather than restated, so there is one place to argue with."* Copy the import form and the practice of naming the constant in the failure message.

**(iii) Pin a viewport so it holds under BOTH Playwright projects — `frontend/e2e/dashboard-mobile.spec.ts:375-380`.** This is load-bearing: `playwright.config.ts` declares only two projects, `mobile` (390×844) and `desktop` (1440×900). Phase 35 asserts at **1440 / 1920 / 2560**, none of which the config provides beyond 1440. The pattern, with its reasoning comment:

```ts
test.describe("Dashboard mobile shell (390px)", () => {
  // Pin the viewport so it is exercised correctly under BOTH the `mobile` and
  // `desktop` Playwright projects — at a 1440px desktop viewport the tab bar
  // hides and the sidebar shows, which would be a false red.
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true })
```

Without this, a 2560 assertion would run twice at the project viewport and measure nothing it claims to.

**(iv) The falsifiability lesson to carry over — `dashboard-mobile.spec.ts:613-622`.** Its docblock records a measured vacuous assertion: `docScrollWidth <= window.innerWidth + 1` **compares a number against itself**, because when content is wider than the layout viewport the browser zooms out and `window.innerWidth` grows to match (`{"docScrollWidth":1200,…,"innerWidth":1200,…}`). The fix is to compare against `page.viewportSize()`. Any Phase 35 assertion phrased as "band ≤ viewport" inherits this bug directly.

### 3c. MODIFIED — `tailwind.config.ts` theme extension

**NO ANALOG EXISTS.** Two independent gaps, both verified:

1. `rg 'maxWidth' tailwind.config.ts app/globals.css` → **rc=1, no match**. There is no `theme.extend.maxWidth` precedent to copy. The nearest structural sibling is the `theme.extend.colors` block (:39-83), which shows the house style: extend, with a comment explaining *why* each token exists.
2. `rg '^import|require\(' next.config.mjs jest.config.js jest.setup.js postcss.config.mjs playwright.config.ts tailwind.config.ts` → **no config file in this repo imports a repo-local module.** `tailwind.config.ts` imports only `tailwindcss` types and `tailwindcss-animate`.

**Hazard the planner must resolve, not assume:** Tailwind 3.4.1 loads a TS config through `jiti`, which does **not** apply `tsconfig.json` `paths` by default. `import { … } from "@/lib/layout-widths"` in `tailwind.config.ts` is likely to fail at build time. Use a **relative** specifier (`./lib/layout-widths`) and prove it by running an actual `npm run build`, not by reading the config. This is a genuinely new pattern for this codebase and deserves its own small verification step.

**Alternative with a real analog:** skip the Tailwind extension entirely and use arbitrary values (`max-w-[1700px]`) fed by the constants only in the *spec*. The tree already uses arbitrary max-widths in 11 files (`max-w-[68ch]`, `max-w-[55%]`, `max-w-[420px]`, `max-w-[200px]`…), so that idiom is established. The trade-off: the number then exists twice (Tailwind class + constants module) and can drift — which is the exact failure `cart-identity.ts`'s docblock warns about. **Recommend the theme extension, with the build verified.**

---

## 4. Blast radius — tests that assert on width, layout, or horizontal scroll

Searched `e2e/`, `__tests__/`, and every co-located `__tests__/` under `app/` and `components/`, for `scrollWidth|clientWidth|offsetWidth|getBoundingClientRect|innerWidth|boundingBox|maxWidth|max-width|overflow-x`. Excluded `e2e-artifacts/` (gitignored build output; an earlier `rg -uu` swept 513 KB of stale report JSON from it — do not count those hits).

### B — Playwright (live stack)

| # | File · line | The specific assertion | Why Phase 35 touches it |
|---|---|---|---|
| **B-1** | `e2e/public-layout.spec.ts:213-237` | loops 12 `PUBLIC_ROUTES` incl. `/`, `/shop`, `/legal/*`, `/business-model-guide`, `/competitive`; asserts `horizontalOverflow(page) <= 1`, `aspectViolations == []`, `brokenImages == []` | **The CI browser gate** (only spec wired into CI, per its header). `/` moves 1152→1280 and runs straight at it. CONTEXT §5's "no body horizontal scroll" constraint is this line |
| **B-2** | `e2e/public-layout.spec.ts:319-375` | legal-table fit: `clientWidth > 0` (vacuity control) **then** `scrollWidth <= clientWidth` | Fires if `policy-page.tsx:112` moves. A wider band makes it *more* likely to pass — which is why the `clientWidth > 0` control is what keeps it honest |
| **B-3** | `e2e/public-layout.spec.ts:330` | `setViewportSize({ width: 375, height: 667 })` | Mobile-parity arm |
| **B-4** | `e2e/dashboard-mobile.spec.ts:390-431` | 11-route loop at 390px: `h1.scrollWidth <= clientWidth+1`; `lines <= 1`; **`mainWidth >= 300`** | The mobile-must-not-move guard (CONTEXT §5). Note `mainWidth` measures `<main>`, which sits *outside* the container — so it will not catch a container-only change. Do not treat a green here as proof the Shell tier is mobile-safe |
| **B-5** | `e2e/dashboard-mobile.spec.ts:534-536` and `:643-680` | `docScrollWidth <= page.viewportSize().width` at 375px, over all 11 routes | The 375px overflow guard. Its docblock (:613-622) is the vacuity lesson §3b(iv) cites |
| **B-6** | `e2e/media-review-320.spec.ts:154-155,162-166` | `document.documentElement.scrollWidth <= 320`, **plus** per-control `boundingBox()` right-edge checks | `/dashboard/media/review` is inside the shell — any change to the shell's padding or cap can red this at the tightest viewport in the suite |
| **B-7** | `e2e/landing-webperf.spec.ts:237-239` and `:420-422` | `overflow.scrollWidth <= overflow.clientWidth + 1` on `/`, pre- and post-location-grant | `/` is the Marketing-tier edit |
| **B-8** | `e2e/landing-webperf.spec.ts:207` (+ `:176` @375px) | CLS vs `LANDING_CLS_KNOWN_BASELINE` (0.1793) ± `LANDING_CLS_TOLERANCE` (0.02), and LCP vs `LCP_BUDGET_MS` | **CONTEXT §5's named control arm.** "It still breaches CLS_BUDGET" is expected; "it got worse than 0.1793" is the failure |
| **B-9** | `e2e/landing-webperf.spec.ts` (bundle arm) | `LANDING_BUNDLE_CEILING_BYTES` = 953,353 + 20,480 | A CSS-only tier change should not move this. If it does, something imported a module it should not have |
| **B-10** | `e2e/webhooks-flow.spec.ts:229-235` | `setViewportSize(375×800)` then `scrollWidth <= clientWidth+1` on `/dashboard/webhooks` | Dashboard-inside-shell; also `:157` pins `1440×900` |
| **B-11** | `e2e/webhooks-webperf.spec.ts:185,208-210` | per-route overflow at 375px + LCP/CLS on webhooks routes | Same shell |
| **B-12** | `e2e/unsubscribe-flow.spec.ts:111,121-123` | `setViewportSize(375×800)` then `scrollWidth <= clientWidth+1` | `/unsubscribe` is `PublicShell` + `max-w-lg`; only at risk if `PublicShell` gains a cap (recommended: it must not) |
| **B-13** | `e2e/cookie-notice-layout.spec.ts:75,82` | notice vertical position at 375×812 | The notice mounts from `app/layout.tsx:48` over **every** surface |
| **B-14** | `e2e/dashboard-a11y-nightly.spec.ts:53,114` | axe over `KEY_ROUTES = ["/dashboard","/dashboard/orders","/dashboard/products"]` | **The `scrollable-region-focusable` guard CONTEXT §5 names.** Report-only (nightly), so it will not block a PR — say so explicitly rather than counting it as a gate |
| **B-15** | `e2e/marketing-motion.spec.ts:119-151` | `boundingBox()` on headings/steps at 375px on `/` and `/for-operators` | `/` changes width; scroll-reveal positions move |
| **B-16** | `e2e/marketing-dish-scroller.spec.ts:95,135` | `scrollWidth - clientWidth > 2` — asserts the dish rail **IS** overflowing | **Inverted risk:** this one fails if the rail *stops* overflowing. A wider `/` band could make the dish row fit and red this. The only assertion in the suite that breaks by things getting *better* |
| **B-17** | `e2e/kitchen-flow.spec.ts`, `e2e/stomp-relay.spec.ts:111` | live KDS transitions | No width assertion, but A-6 tiers `/dashboard/kitchen` Index and it is `AnimatePresence`-driven — re-run both |

### C — Jest / jsdom (per-PR)

| # | File · line | The specific assertion | Why Phase 35 touches it |
|---|---|---|---|
| **C-1** | `app/dashboard/products/__tests__/mobile-header-and-scroll-a11y.test.tsx:70-78` | `getByRole("region", { name: /products table/i })` has `tabIndex="0"` | **A11Y-3.** jsdom does not lay out, so this asserts *attributes*, not overflow. It will stay green whether or not the region still overflows — the "structural check passes while the function is broken" trap. The functional half is B-14, which is nightly and report-only. **Flag the gap; do not report C-1 green as A11Y-3 coverage** |
| **C-2** | `app/dashboard/products/__tests__/mobile-header-and-scroll-a11y.test.tsx:35-44` | header row has `flex-wrap` and `gap-3` | Class-string assertion on the products header; fires if the header markup is restructured |
| **C-3** | `components/legal/__tests__/policy-page.a11y.test.tsx:141,149` | `container.querySelectorAll('[class*="max-w-[68ch]"]')` and `.closest('[class*="max-w-[68ch]"]')` | **The only test in the repo that asserts on a width class string.** It is simultaneously the guard that keeps prose at 68ch and the tripwire that fires if `policy-page.tsx` is refactored (A-2) |
| **C-4** | `components/legal/__tests__/retention-table.a11y.test.tsx:90` | `expect(region.className).toContain("overflow-x-auto")` | Guards the focusable retention-table region |
| **C-5** | `components/marketing/__tests__/competitive-teardown.test.tsx:49-62` | the radar chart's `role=img` container has an `overflow-x-auto` parent | `/competitive` is already at 1280 so this should not move — but the file's own docblock (:36-45) notes jsdom has no layout, so it proves the *fallback exists*, not that it is unneeded |
| **C-6** | `__tests__/dashboard-a11y-axe.test.tsx` | jest-axe over `/dashboard`, `/dashboard/orders`, `/dashboard/products`, each with a documented non-vacuity heading check and its own break arm | The per-PR a11y gate on the three Index pages. jsdom cannot fire layout-dependent rules — `scrollable-region-focusable` will not appear here whatever happens |
| **C-7** | `__tests__/axe-instrument.test.tsx` | proves the axe instrument can fail (break arm + clean arm + non-vacuity arm) | Not affected, but it is the *model* for how Phase 35's width spec should prove its own instrument |
| **C-8** | `app/dashboard/webhooks/__tests__/delivery-log.test.tsx:154` | `Object.defineProperty(window, "innerWidth", …)` — a responsive-branch test | Fires if the webhooks delivery-log layout branches change (A-3) |
| **C-9** | `components/dashboard/__tests__/shop-switcher.test.tsx:447` | comment referencing the `max-w-[55%]` (~206px) column and 4-line wrap at 375px | Mobile top bar; do not touch `dashboard-shell.tsx:50` |

### D — NOT blast radius (checked and cleared)

- `__tests__/header-snapshot.test.ts` — **name collision only.** It snapshots HTTP **security headers** / CSP (`lib/security-headers`), not the site header. `rg 'max-w|container'` over it and its `.snap` → **rc=1, no match**.
- **Responsive image `sizes` hints — none exist in shipped code.** `rg 'sizes\s*=|sizes:'` over `app/` + `components/` returns only two unrelated hits in `components/dashboard/__tests__/shop-switcher.test.tsx` (page-size numbers). The `sizes = "(max-width: 640px) 100vw, …"` string in `docs/audit/remediation/05-frontend-remediation.md:350` is a **proposed** snippet in an audit document, not code. So widening containers cannot break a `sizes` hint — but it does mean images will render larger with no `sizes` guidance, which belongs in the LCP re-measurement (CONTEXT §6), not here.
- `app/globals.css` — one width rule, `width: 72mm` at :197 (print/label CSS). Not a page band.
- **No document in the repo states a content-width standard.** Verified from the repo root with a validated control (`rg -uu -l 'zzz-no-such-width-token' docs/` → rc=1, clean no-match, so the search *could* have returned nothing meaningfully). The single hit for `max-width` in `docs/` is the audit snippet above. CONTEXT §2's claim holds.

### E — Suite-count side effect

Adding a spec file changes the Playwright `test()` block count, which `scripts/docs-freshness.sh` and `scripts/check-doc-metrics.sh` both gate against `docs/metrics.json` (project standard: 120 Playwright `test()` blocks across 25 specs; the phase adds at least one file). Regenerate with `scripts/docs-freshness.sh --write` — **never** by arithmetic, since the script greps literal `test(` / `it(` tokens.

---

## 5. Shared patterns to apply across every changed file

### Declared-constant discipline
**Source:** `frontend/lib/cart-identity.ts:38-43` · **Apply to:** every tier number.
> *"ONE definition, imported by the provider, the nav badge and the sign-out reaper alike — two copies of this string is how a 'clear everything' quietly starts missing keys."*

Phase 35's four tier numbers must exist once. The failure mode is identical: a spec asserting 1700 while the shell renders 1690 is a test that measures a literal, not a contract.

### Copy-verbatim over hand-rolled variants
**Source:** `components/public/public-shell.tsx:21-24` and `app/shop/layout.tsx:50-53` · **Apply to:** any new tier wrapper component.
> *"Three copies of this pattern already existed and two had already drifted apart; a fourth variant would be the drift, not the fix."*

This repo has already paid for hand-rolled layout duplication (four skip-link copies). If the plan introduces a tier wrapper, there must be **one** component, used by every tier, parameterised — not one per tier.

### Non-vacuity before the real assertion
**Source:** `e2e/public-layout.spec.ts:319-375` and `__tests__/axe-instrument.test.tsx` · **Apply to:** every width assertion.
Assert the band was found and `clientWidth > 0` **before** comparing it to a tier. A selector that matches nothing measures 0, and `0 <= 1700` is green.

### Comment the reason at the site, in prose that survives greps
**Source:** `components/public/public-shell.tsx:36-41` · **Apply to:** `tailwind.config.ts` and the constants module.
> *"a `grep` limb passed on a page whose landmark had been deleted, because the file's own comment still spelled the tag out. Prose counts."*

If a Phase 35 verify greps for a width token, the comment explaining that token will satisfy the grep. Describe tokens in words; write each literal exactly once.

---

## 6. Metadata

**Search scope:** `frontend/app/`, `frontend/components/`, `frontend/lib/`, `frontend/e2e/`, `frontend/__tests__/`, `frontend/hooks/`, `frontend/types/`, root `docs/`, plus `tailwind.config.ts`, `tsconfig.json`, `jest.config.js`, `playwright.config.ts`, `next.config.mjs`, `package.json`.

**Search discipline.** All evidential searches used `rg -uu`. Every count reported here was taken with a pattern proven able to match (or a control pattern proven able to return a clean rc=1 no-match). Three instrument defects were caught and corrected mid-analysis; they are recorded because the corrected numbers are what the inventory rests on:

1. `rg 'container'` → 269 hits / 55 files, which is **not** the Tailwind class. A class-context pattern gives the true answer: **1**.
2. `rg 'sizes='` returned rc=1 "clean" over code that uses `sizes = ` with spaces — a pattern-shape false negative. Corrected to `sizes\s*=`.
3. The first `docs/` probe ran from `frontend/` where **no `docs/` directory exists**, and its rc was read after a `| head` so it reported the pager's status. Re-run from the repo root with a validated control.

**Counts asserted, and how:**

| Claim | Method | Value |
|---|---|---|
| Tailwind `container` class call sites | class-context regex over `app/` + `components/` | **1** (`dashboard-shell.tsx:55`) |
| `max-w-7xl` | `rg -uu -c` summed | 18 occurrences / 9 files |
| `max-w-6xl` | same | 5 / 2 |
| `max-w-4xl` | same | 15 / 5 |
| `max-w-[68ch]` | same | 6 / 2 |
| Route files under `app/` | `/usr/bin/find` with a trailing-slash start point | 66 (38 page · 21 layout · 3 error · 3 loading · 1 not-found) |
| Dashboard sub-layouts with any `className` | `rg -uu -l` | **0** (rc=1) |
| `theme.extend.maxWidth` present | `rg -uu` | **0** (rc=1) |
| Config files importing a repo module | `rg -uu '^import\|require\('` over 6 configs | **0** |
| e2e specs importing an app-side module | `rg -uu "from ['\"](@/\|\.\./)"` over `e2e/` | **1** (`public-a11y.spec.ts:75`) |
| Repo docs declaring a content-width standard | `rg -uu -i` from repo root, with control | **0** |
