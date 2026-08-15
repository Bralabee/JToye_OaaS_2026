# Phase 31: Consumer-Safety and Legal Floor — Pattern Map

**Mapped:** 2026-08-15
**Files analyzed:** 54 new/modified files across 5 surfaces + 3 backend seams + 4 gate/doc artefacts
**Analogs found:** 41 / 54 (exact 14 · role-match 27 · **no analog 13**)

**Inputs read in full:** `31-CONTEXT.md` (D-01..D-18), `31-RESEARCH.md` (1169 lines), `31-UI-SPEC.md` (approved, S1–S5).

> **Every path and line number below was opened in this session.** Where research named an analog, it
> was re-verified rather than re-quoted. Where nothing exists, the row says so and names the nearest
> neighbour and how it differs — an invented pattern is worse than an honest gap.

---

## Contents

1. [File Classification](#file-classification)
2. [Pattern Assignments](#pattern-assignments) — per file, with excerpts
3. [Shared Patterns](#shared-patterns) — cross-cutting
4. [No Analog Found](#no-analog-found) — 13 files, with nearest neighbours
5. [Anti-Analogs](#anti-analogs--patterns-in-the-tree-you-must-not-copy)
6. [Verification Log](#verification-log)

---

## File Classification

### Frontend — new components (S1–S4)

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|
| `frontend/lib/consent.ts` | utility (client store) | event-driven | `frontend/lib/shop-context.ts` | exact |
| `frontend/components/public/cookie-notice.tsx` | component (client chrome) | event-driven | `components/dashboard/mobile-tab-bar.tsx` + `app/shop/[slug]/shop-detail-client.tsx:770-813` | partial |
| `frontend/components/public/consent-banner.tsx` | component (client, dormant) | event-driven | same as above | partial |
| `frontend/components/legal/policy-page.tsx` | component (server shell) | request-response | `frontend/app/legal/page.tsx` + `components/public/public-shell.tsx` | exact |
| `frontend/components/legal/policy-toc.tsx` | component | transform | `components/marketing/business-model-guide.tsx:142-157` | role-match |
| `frontend/components/legal/retention-table.tsx` | component | transform | **none** — see [No Analog](#no-analog-found) | none |
| `frontend/components/storefront/legal-strip.tsx` | component | request-response | `components/public/public-footer.tsx` | **likely NOT REQUIRED** (Correction 1) |
| `frontend/components/storefront/order-allergen-panel.tsx` | component | transform | `app/shop/[slug]/checkout/page.tsx:423-432` + `components/ui/ingredient-text.tsx` | partial |
| `frontend/components/dashboard/kitchen/order-allergen-banner.tsx` | component | streaming (STOMP-fed) | `app/dashboard/kitchen/page.tsx:850-865` (CardHeader) | role-match |
| `frontend/components/dashboard/kitchen/item-allergen-badge.tsx` | component | streaming | `app/dashboard/kitchen/page.tsx:858-863` (`Badge`) | exact |
| `frontend/components/ui/checkbox.tsx` | primitive (shadcn add) | n/a | `components/ui/label.tsx`, `input.tsx` (same registry vintage) | exact |

### Frontend — new/modified routes (S2, S5)

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|
| `frontend/app/legal/privacy/page.tsx` | route (RSC) | request-response | `frontend/app/legal/page.tsx` | exact |
| `frontend/app/legal/cookies/page.tsx` | route (RSC) | request-response | same | exact |
| `frontend/app/legal/retention/page.tsx` | route (RSC) | request-response | same | exact |
| `frontend/app/legal/accessibility/page.tsx` | route (RSC) | request-response | same | exact |
| `frontend/app/legal/page.tsx` **(M)** | route (RSC) | request-response | itself — becomes an index | exact |
| `frontend/app/auth/signin/page.tsx` **(M)** | route (client) | request-response | `components/public/public-shell.tsx` | role-match |
| `frontend/app/sitemap.ts` **(M)** | config | batch | itself, `STATIC_ROUTES` at `:78-88` | exact |
| `frontend/components/public/public-footer.tsx` **(M)** | component | request-response | itself, `:110-138` (a column) | exact |
| `frontend/components/public/public-shell.tsx` **(M)** | component | request-response | `components/marketing/operator-pitch.tsx:70` (skip link) | exact |
| `frontend/components/storefront/storefront-nav.tsx` **(M)** | component | — | one-attribute fix (`aria-label`) | n/a |
| `frontend/app/shop/[slug]/shop-detail-client.tsx` **(M)** | component | — | one-attribute fix (`aria-label` on `:687` nav) | n/a |
| `frontend/app/globals.css` **(M)** | config | — | `:141-151` (`.kds-press` reduced-motion) + `:181-325` (print sheet) | exact |
| `frontend/eslint.config.mjs` **(M)** | config | — | itself, `:29-43` (a scoped rules object) | exact |

### Frontend — modified surfaces (S3, S4)

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|
| `frontend/app/shop/[slug]/checkout/page.tsx` **(M)** | route (client form) | request-response | `components/dashboard/webhooks/WebhookCreateDialog.tsx:134-197` | role-match |
| `frontend/app/dashboard/kitchen/page.tsx` **(M)** | route (client) | streaming | itself, `:866-885` | exact |
| `frontend/components/dashboard/kitchen/kitchen-ticket.tsx` **(M)** | component (print) | transform | itself, `:87-98` (`<ul className="kds-ticket__items">`) | exact |
| `frontend/types/api.ts` **(M)** | model | — | itself, `:490-517` (`ALLERGENS`) | exact |
| `frontend/lib/company.ts` **(M)** / env | config | — | itself, `:36-45` (`NEXT_PUBLIC_*` with a code default) | exact |

### Frontend — tests

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|
| `frontend/e2e/public-a11y.spec.ts` | test (browser) | request-response | `frontend/e2e/public-layout.spec.ts` (helpers + stub) | exact |
| `frontend/__tests__/contrast-literals.test.ts` | test (node) | transform | `frontend/__tests__/contrast-tokens.test.ts` + `palette-discipline.test.ts` | exact |
| `frontend/__tests__/allergen-table-parity.test.ts` | test (node) | transform | `frontend/__tests__/palette-discipline.test.ts` (reads source from disk) | role-match |
| `frontend/__tests__/accessibility-statement-dates.test.ts` | test (node) | transform | `frontend/__tests__/contrast-tokens.test.ts` (VOID-on-unparseable) | role-match |
| `frontend/lib/__tests__/consent.test.ts` | test (jsdom) | event-driven | `frontend/lib/__tests__/cart-identity.test.ts` | exact |
| `frontend/components/legal/__tests__/*.a11y.test.tsx` | test (jsdom) | — | `components/ui/__tests__/ingredient-text.test.tsx` | partial (**no axe precedent**) |
| `frontend/components/public/__tests__/cookie-notice.test.tsx` | test (jsdom) | — | `components/public/__tests__/public-footer-persona.test.tsx` | exact |
| `frontend/app/shop/[slug]/checkout/__tests__/*.test.tsx` | test (jsdom) | — | `components/dashboard/__tests__/shop-switcher.test.tsx:515-525` (role=alert assertion) | role-match |
| `frontend/app/dashboard/kitchen/__tests__/*.test.tsx` **(M/N)** | test (jsdom) | — | `app/dashboard/kitchen/__tests__/page.test.tsx` (exists) | exact |

### Backend (Java)

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|
| `.../product/AllergenCatalog.java` | model (constant table) | — | `frontend/types/api.ts:490-517` (**cross-language mirror**) | partial |
| `.../order/OrderAllergenAggregator.java` | service | transform | `.../product/IngredientMarkupParser.java` (pure, fail-soft, static) | role-match |
| `.../gdpr/DsarRequest.java` + `DsarRequestRepository.java` | model | CRUD | `.../gdpr/ErasureRecord.java` + `ErasureRecordRepository.java` | exact |
| `.../gdpr/DsarIntakeController.java` | controller | request-response | `.../gdpr/GdprController.java` + `.../customer/CustomerController.java:74-86` (Idempotency-Key) | role-match |
| `.../gdpr/DsarFanoutWorker.java` | service (background) | batch | `.../webhook/WebhookRetentionCleanup.java` | **exact — clone it** |
| `.../order/OrderItem.java` **(M)** | model | — | itself, `productName` snapshot precedent (`:33-34`) | exact |
| `.../storefront/PublicStorefrontService.java` **(M)** | service | CRUD | itself, `:735-793` (the item loop + `allergenWarnings` seam) | exact |
| `V62__*.sql` | migration | — | `V61__postcode_centroid.sql` (RLS decision + no `CREATE EXTENSION`) | exact |
| `application.yml` **(M)** | config | — | `:634` (`webhook.delivery.retention-days`) | exact |
| `.../gdpr/*Test.java`, `AllergenAggregatorTest.java` | test | — | existing `*Test` under `core-java/src/test` | exact |

### Gates, docs, CI

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|
| `scripts/check-retention-enforcement.sh` | gate script | transform | `scripts/check-no-create-extension.sh` | **exact — the template** |
| `scripts/gates/claims.manifest` **(M)** | config (TSV) | transform | itself, `:15-16` (source rows), `:19-31` (rule rows) | exact |
| `docs/retention-manifest.json` | config (source of truth) | — | `docs/metrics.json` (the other claim-gate `source`) | exact |
| `.github/workflows/ci-cd.yaml` **(M)** | config | — | `:686-689` (gate step), `:320-322` (e2e run line) | exact |
| `docs/legal/article-26-arrangement.md` | doc | — | `docs/legal/derivation-clause.md`, `article-9-allergen-basis.md` | exact |
| `docs/legal/article-9-allergen-basis.md` **(M)** | doc | — | itself — **extend and date, never contradict** | exact |
| `.env.example`, `docker-compose.full-stack.yml`, `frontend/Dockerfile` **(M)** | config | — | the `NEXT_PUBLIC_SUPPORT_EMAIL` build-arg triple | exact |
| `docs/metrics.json`, `README.md`, `AGENTS.md`, `CLAUDE.md` **(M)** | doc | — | regenerate with `scripts/docs-freshness.sh --write` | exact |

---

## Pattern Assignments

### `frontend/lib/consent.ts` (utility, event-driven) — S1/D-05

**Analog:** `frontend/lib/shop-context.ts` (57 lines — read in full).
This is a **client-only store with a same-tab + cross-tab notification channel and an SSR guard**, which
is exactly the consent module's shape. Research Q1 concluded: **no table, no migration.**

**SSR guard + read/write/subscribe triad** (`lib/shop-context.ts:28-56`):

```typescript
export function getShopContext(): string {
  if (typeof window === "undefined") return ALL_SHOPS_CONTEXT
  return window.localStorage.getItem(SHOP_CONTEXT_KEY) ?? ALL_SHOPS_CONTEXT
}

export function setShopContext(id: string): void {
  if (typeof window === "undefined") return
  window.localStorage.setItem(SHOP_CONTEXT_KEY, id)
  window.dispatchEvent(new Event(SHOP_CONTEXT_CHANGE_EVENT))
}

/**
 * Subscribe to shopContext changes from BOTH the same-tab `shopcontext:change`
 * event and the cross-tab `storage` event. Returns an unsubscribe function.
 */
export function subscribeShopContext(cb: () => void): () => void {
  if (typeof window === "undefined") return () => {}
  window.addEventListener(SHOP_CONTEXT_CHANGE_EVENT, cb)
  window.addEventListener("storage", cb)
  return () => {
    window.removeEventListener(SHOP_CONTEXT_CHANGE_EVENT, cb)
    window.removeEventListener("storage", cb)
  }
}
```

> The header comment at `:11-13` states the reason the same-tab CustomEvent exists: *"The browser
> `storage` event only fires in OTHER tabs"*. `consent.onChange` must do the same or a banner
> dismissed in this tab will not re-render this tab.

**Private-mode / quota resilience — take this from `lib/cart-identity.ts:65-99`**, which `shop-context.ts`
does NOT have (its `setItem` is unguarded and will throw in Safari private mode):

```typescript
export function getCurrentCustomerId(): string | null {
  if (typeof window === "undefined") return null
  try {
    return window.localStorage.getItem(CUSTOMER_ID_KEY) || null
  } catch {
    // Private mode / storage disabled. Unknown identity reads as anonymous,
    return null
  }
}
```

**Key-namespacing constant, exported once** (`lib/cart-identity.ts:38-47`) — the comment states the rule
the consent key must follow:

```typescript
/**
 * Namespace for every per-shop basket. ONE definition, imported by the provider,
 * the nav badge and the sign-out reaper alike — two copies of this string is how
 * a "clear everything" quietly starts missing keys.
 */
export const CART_KEY_PREFIX = "jtoye-cart-"
```

**What is NEW here (no analog):** the **version string** in `jtoye-cookie-notice-ack`. Measured with a
live control — `localStorage.setItem` appears in 10+ files, and a search for
`STORAGE_VERSION|SCHEMA_VERSION|_VERSION\s*=|version.*localStorage` returns **rc=1, zero hits**. No key
in this repo is versioned. Design it, do not look for it.

---

### `frontend/components/public/cookie-notice.tsx` (component, client chrome) — S1

**Analog (placement + safe area):** `components/dashboard/mobile-tab-bar.tsx:83-91` — the only
fixed-bottom chrome that both reserves the iOS home indicator and carries a landmark role:

```tsx
<nav
  data-testid="mobile-tab-bar"
  aria-label="Primary"
  className={cn(
    "fixed inset-x-0 bottom-0 z-50 flex h-14 border-t border-slate-200 bg-white pb-[env(safe-area-inset-bottom)] md:hidden dark:border-slate-800 dark:bg-slate-900",
    className
  )}
>
```

**Analog (the bar it must not overlap):** `app/shop/[slug]/shop-detail-client.tsx:770-793` —
`FloatingCartBar`, the storefront's sticky basket. It uses the `max()` form of the inset, which is the
one to copy because a plain `pb-[env(...)]` collapses to 0 on non-notch devices:

```tsx
className="fixed bottom-0 left-0 right-0 z-50 p-3 sm:p-4 pb-[max(0.75rem,env(safe-area-inset-bottom))] sm:pb-[max(1rem,env(safe-area-inset-bottom))]"
```

> **Both are `position: fixed` and out of flow, so both already satisfy the "zero CLS" half.**
> Neither reserves space in the document — and neither needs to. The UI-SPEC's zero-CLS contract (F2)
> is satisfied by *being* fixed, not by reserving. What is unprecedented is the **stacking**: the
> notice at `z-50` and `FloatingCartBar` at `z-50` occupy the same corner of a 375×667 viewport.
> Nothing in the tree resolves that today. See [No Analog](#no-analog-found).

**Reduced motion for a raw CSS entrance** (`app/globals.css:141-151`) — `MotionConfig reducedMotion="user"`
governs framer-motion only, so a CSS transition needs its own block:

```css
.kds-press {
  transition: transform 140ms cubic-bezier(0.23, 1, 0.32, 1);
}
.kds-press:active {
  transform: scale(0.97);
}
@media (prefers-reduced-motion: reduce) {
  .kds-press {
    transition-duration: 0ms;
  }
}
```

**Announce-on-appear (if the notice needs it):** `components/marketing/business-model-guide.tsx:159` is
the one fixed live region in the tree:

```tsx
{feedback && <div aria-live="polite" className="fixed bottom-5 right-5 z-50 max-w-sm rounded bg-oxblood px-4 py-3 text-sm font-semibold text-white shadow-xl print:hidden">{feedback}</div>}
```

> UI-SPEC contracts `<section aria-label="Cookie notice">` — **not** `role="alertdialog"`, not a
> `<dialog>`. Do not upgrade it to a live region unless the copy changes dynamically.

---

### `frontend/app/legal/{privacy,cookies,retention,accessibility}/page.tsx` (route, request-response) — S2/D-06

**Analog:** `frontend/app/legal/page.tsx` (64 lines, read in full). Copy it structurally, then apply the
UI-SPEC's uplifts (68ch column, 16px body, one `<h1>`, stable `<h2>` ids, "Last updated").

**Metadata + canonical** (`app/legal/page.tsx:1-11`):

```tsx
import type { Metadata } from "next"
import Link from "next/link"
import { getCompanyInfo } from "@/lib/company"
import { PublicShell } from "@/components/public/public-shell"

export const metadata: Metadata = {
  title: "Legal & company information — J'Toye",
  description:
    "Company registration and legal information for J'Toye Digital Ltd, the operator of the J'Toye platform.",
  alternates: { canonical: "/legal" },
}
```

**Shell + the recorded regression** (`app/legal/page.tsx:13-25`) — the comment is the contract:

```tsx
/**
 * Public platform legal page — the operator's Companies House trading
 * disclosure. Platform-owned (J'Toye Digital Ltd), NOT a vendor storefront.
 *
 * Wrapped in PublicShell: it used to render a bare <main> with no header or
 * footer, so anyone landing here from the dashboard legal line had one text
 * link out and no brand chrome at all.
 */
export default function LegalPage() {
  const c = getCompanyInfo()
  return (
    <PublicShell>
      <div className="mx-auto max-w-3xl px-4 py-16 sm:px-6 lg:px-8">
```

**Identity from `getCompanyInfo()`, never hardcoded** (`app/legal/page.tsx:35-47`):

```tsx
<dl className="mt-8 space-y-4 text-sm">
  <div>
    <dt className="font-semibold text-oxblood">Registered company name</dt>
    <dd className="text-slate-600">{c.legalName}</dd>
  </div>
  <div>
    <dt className="font-semibold text-oxblood">Company number</dt>
    <dd className="text-slate-600">{c.companyNumber}</dd>
  </div>
```

**Link colour on legal surfaces is `amber-700`, not `--primary`** (`app/legal/page.tsx:56-60`) — matches
the UI-SPEC's 4.67:1 measurement and its "accent is not a link colour" rule:

```tsx
<Link href="/" className="font-medium text-amber-700 hover:text-amber-800">
```

**The `PublicShell` you wrap in** (`components/public/public-shell.tsx`, 22 lines, read in full):

```tsx
export function PublicShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col bg-cream">
      <PublicHeader />
      <main className="flex-1">{children}</main>
      <PublicFooter />
    </div>
  )
}
```

> Its header comment at `:4-13` records that it is *"Deliberately a PLAIN server component — no client
> directive, no route-segment config — so the root layout's `dynamic = "force-dynamic"` and CSP nonce
> cascade through untouched (the #89 failure mode)."* The skip link added for S5 must not make it a
> client component.

**⚠ Blocking content gap (research, re-verified at `lib/company.ts:36-45`):** `registeredOffice` defaults
to `""` and `NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` is set nowhere. The privacy notice's Art. 13(1)(a)
"who we are" block will render blank. The env-var pattern to mirror is in the same function:

```typescript
export function getCompanyInfo(): CompanyInfo {
  return {
    legalName: process.env.NEXT_PUBLIC_COMPANY_LEGAL_NAME || DEFAULT_LEGAL_NAME,
    companyNumber: process.env.NEXT_PUBLIC_COMPANY_NUMBER || DEFAULT_COMPANY_NUMBER,
    registrationJurisdiction:
      process.env.NEXT_PUBLIC_COMPANY_REGISTRATION || DEFAULT_REGISTRATION,
    registeredOffice: process.env.NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE || "",
  }
}
```

The file's own header (`:14-19`) states these are **BUILD args**, not runtime env: *"because they are
inlined into the browser bundle they must be provided as BUILD args (compose/Dockerfile), mirroring the
ONB-1 support-channel pattern."*

---

### `frontend/components/legal/policy-toc.tsx` (component, transform) — S2

**Analog:** `components/marketing/business-model-guide.tsx:142-157` — **this is a real precedent and the
answer to "is there any long-form in-page nav?" is yes.** It is a sticky labelled `<nav>` of anchors over
`<h2 id="...">` sections, with `scroll-mt-20` on each section.

```tsx
<nav aria-label="Guide topics" className="sticky top-0 z-30 border-b border-slate-200 bg-cream/95 backdrop-blur print:static">
  <div className="mx-auto flex max-w-7xl items-center gap-2 overflow-x-auto px-5 py-3 sm:px-8">
    <span className="mr-2 shrink-0 text-xs font-bold uppercase tracking-[0.15em] text-slate-600">Read</span>
    {navItems.map(([id, label]) => (
      <a key={id} href={`#${id}`} className="shrink-0 rounded-full px-3 py-1.5 text-sm font-semibold text-slate-600 hover:bg-cream focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500">
        {label}
      </a>
    ))}
```

**Section + heading pairing with a stable id** (`business-model-guide.tsx:162-165`) — note the id is
derived from the section's subject, never its index, which is exactly UI-SPEC F4:

```tsx
<section id="the-decision" aria-labelledby="decision-heading" className="scroll-mt-20">
  <SectionLabel number="01" label="The decision" />
  <div className="grid gap-8 lg:grid-cols-[0.8fr_1.2fr]">
    <h2 id="decision-heading" className="text-3xl font-bold leading-tight tracking-[-0.035em] sm:text-4xl">UK-first assisted vertical SaaS, deliberately narrow.</h2>
```

**Three differences the planner must design around, not copy:**
1. It is a `"use client"` marketing component with `useState` filters; a policy page should stay a server
   component so `force-dynamic`/CSP cascade untouched (see `public-shell.tsx:4-13`). A pure `<nav>` of
   `<a href="#…">` needs no client boundary.
2. Its nav is a **horizontal pill strip** (`overflow-x-auto`); UI-SPEC contracts a **sticky side rail at
   `lg`+ and a collapsed disclosure below `lg`**. That responsive shape does not exist in the tree.
3. It uses `aria-labelledby` on the `<nav>` pointing at nothing — UI-SPEC contracts
   `<nav aria-labelledby="on-this-page">`, so the label element must actually exist.

---

### `frontend/components/legal/retention-table.tsx` (component, transform) — S2a

**Analog: NONE that satisfies the contract.** Both existing treatments violate an explicit UI-SPEC rule.
See [No Analog](#no-analog-found) and [Anti-Analogs](#anti-analogs--patterns-in-the-tree-you-must-not-copy).

**What you DO reuse — the shadcn `Table` primitive you must extend** (`components/ui/table.tsx:5-17`,
verified: the wrapper has **no `role`, no `aria-label`, no `tabIndex`**, exactly as UI-SPEC line 305 states):

```tsx
const Table = React.forwardRef<
  HTMLTableElement,
  React.HTMLAttributes<HTMLTableElement>
>(({ className, ...props }, ref) => (
  <div className="relative w-full overflow-auto">
    <table
      ref={ref}
      className={cn("w-full caption-bottom text-sm", className)}
      {...props}
    />
  </div>
))
```

> Note `caption-bottom` in the default. UI-SPEC wants a `<caption>`; the marketing table at
> `business-model-guide.tsx:227` overrides it with `caption-top mb-4 text-left`. Pick one and state why.

**The `Badge` for the `Automated`/`Operational` column:** `components/ui/badge.tsx` (exists). Its
in-context usage with an icon + text label — never colour alone — is at
`app/dashboard/kitchen/page.tsx:858-863`:

```tsx
{config && (
  <Badge className={`${config.bgColor} flex flex-shrink-0 items-center gap-1 text-white`}>
    <StatusIcon className="h-3 w-3" />
    {config.label}
  </Badge>
)}
```

---

### `frontend/app/shop/[slug]/checkout/page.tsx` (route, client form) — S3/D-02/D-03

**Analog for the error announcement:** `components/dashboard/webhooks/WebhookCreateDialog.tsx:190-197`.
This is the **best form in the repo** — react-hook-form + zodResolver + a `role="alert"` server-error
region — and it is still short of what S3 needs.

```tsx
{serverError && (
  <p
    role="alert"
    className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
  >
    {serverError}
  </p>
)}
```

**Its form wiring** (`WebhookCreateDialog.tsx:74-84`):

```tsx
const {
  register,
  handleSubmit,
  reset,
  setValue,
  watch,
  formState: { errors },
} = useForm<FormValues>({
  resolver: zodResolver(schema),
  defaultValues: { targetUrl: "", eventTypes: [] },
})
```

**Its 44px checkbox row** (`WebhookCreateDialog.tsx:160-169`) — the `min-h-11` label wrapper UI-SPEC wants
for the acknowledgement, though the box itself is the 16px `h-4 w-4` that UI-SPEC requires be raised to 24px:

```tsx
<label
  key={et}
  className="flex min-h-11 cursor-pointer items-start gap-3 rounded-md border border-slate-200 px-3 py-2 hover:bg-slate-50"
>
  <input
    type="checkbox"
    className="mt-0.5 h-4 w-4 accent-orange-500 focus:ring-2 focus:ring-orange-500"
    checked={checked}
    onChange={() => toggleEvent(et)}
  />
```

**Its field-level error — the shape S3 must NOT copy** (`WebhookCreateDialog.tsx:146-148`): a plain `<p>`
with no `id`, no `aria-describedby` link, no `aria-invalid` on the input.

```tsx
{errors.targetUrl && (
  <p className="text-xs text-red-600">{errors.targetUrl.message}</p>
)}
```

**Three sub-patterns have ZERO precedent in the tree** (each verified with a live control — see
[Verification Log](#verification-log)): `aria-invalid` (**rc=1, 0 hits**), `aria-describedby` on a form
field (**0 — the 2 hits are Radix dialog internals**), and programmatic focus-move to an invalid field
(**0 — the only `.focus()` in the app is the modal's focus-return**). Nearest neighbour for focus
management is `components/storefront/product-detail-modal.tsx:117`:

```tsx
if (opener && opener.isConnected) opener.focus()
```

**The checkout site itself — what you are extending** (`app/shop/[slug]/checkout/page.tsx:827-859`, read
in full). The new block goes **between** the "How you'll pay" `<h2>` at `:805` and this error/submit run:

```tsx
{/* Error */}
{error && (
  <div className="rounded-xl bg-red-50 border border-red-100 p-3 text-sm text-red-700">
    {error}
  </div>
)}

{/* Below-minimum hint (WR-01) */}
{belowMinimum && (
  <p className="text-center text-xs font-medium text-slate-600">
    Minimum order {formatPrice(minimumOrderPennies)} — add{" "}
    {formatPrice(minimumOrderPennies - subtotalPennies)} more to place this order.
  </p>
)}

{/* Submit */}
<button
  type="submit"
  disabled={submitting || belowMinimum}
  className="flex w-full items-center justify-center gap-2 rounded-2xl bg-oxblood py-3.5 text-sm font-bold text-white hover:bg-oxblood-700 active:scale-[0.98] transition-all shadow-lg disabled:opacity-60 disabled:cursor-not-allowed"
>
```

> Confirms three UI-SPEC facts verbatim: the CTA is `bg-oxblood` (not `bg-primary`), `py-3.5` = 14px
> (recorded, not changed), and `disabled` is already used for `belowMinimum` — which is why the
> acknowledgement gate must **refuse**, not disable.

**The panel to preserve, and its amber-200 boundary to raise** (`checkout/page.tsx:423-432`) — measured
1.25:1, the one colour change this phase makes:

```tsx
{codConfirmation.allergenWarnings.length > 0 && (
  <div className="rounded-xl bg-amber-50 border border-amber-200 p-4 mb-6">
    <h3 className="text-sm font-semibold text-amber-800 mb-2">Allergen warnings</h3>
```

> Research Correction 5 (re-verified at `PublicStorefrontService.java:737-744`): `allergenWarnings` is
> **always empty**, so this branch renders nothing today. Preserve it; do not treat its silence as a
> regression signal.

**Allergen emphasis rendering — reuse, do not re-parse** (`components/ui/ingredient-text.tsx`, exercised by
`components/ui/__tests__/ingredient-text.test.tsx:5-15`):

```tsx
const { container } = render(
  <IngredientText text="mango, **yoghurt (milk)**, cardamom" />
)
const strong = screen.getByText("yoghurt (milk)")
expect(strong.tagName).toBe("STRONG")
expect(container.textContent).not.toContain("**")
```

---

### `frontend/app/dashboard/kitchen/page.tsx` + the two new KDS components (S4/D-04)

**Analog: the file itself.** The card, the item run, and the 44px doctrine are all at `:850-927`.

**Where the banner goes — `CardHeader`, directly under the order-number row** (`page.tsx:853-865`):

```tsx
<CardHeader className="pb-3">
  <div className="flex items-start justify-between gap-2">
    <CardTitle className="min-w-0 truncate text-lg font-semibold">
      {order.orderNumber || `#${order.id.substring(0, 6)}`}
    </CardTitle>
    {config && (
      <Badge className={`${config.bgColor} flex flex-shrink-0 items-center gap-1 text-white`}>
        <StatusIcon className="h-3 w-3" />
        {config.label}
      </Badge>
    )}
  </div>
</CardHeader>
```

**The item run to restructure into a `<ul>`, and the `"{n} items"` summary that STAYS** (`page.tsx:872-885`):

```tsx
{/* Items */}
<div className="text-sm text-slate-600">
  <span className="font-medium">{itemSummary}</span>
  {order.items && order.items.length > 0 && (
    <div className="mt-1 text-xs text-slate-500">
      {order.items.map((item, i) => (
        <span key={item.id || i}>
          {i > 0 && ", "}
          {item.quantity}x {item.productName}
        </span>
      ))}
    </div>
  )}
</div>
```

**The 44px measurement comment — quote it, don't re-derive it** (`page.tsx:893-902`):

```tsx
{/* Actions. The bump keeps the full width it has always had —
    it is the one control pressed a hundred times a service, and
    #105's print must not shrink it. Print sits beside it as an
    icon button with an accessible name.

    Both are h-11 (44px): the shadcn default is h-10, which
    measured 40x40 for the print button on a 375px iPhone SE
    profile — under the 44px minimum for a target pressed by a
    cook's thumb. Enlarging the bump alongside it is not a trade,
    it is the same control with more of it to hit. */}
```

**The print sheet's item list — where the allergen block attaches**
(`components/dashboard/kitchen/kitchen-ticket.tsx:87-98`):

```tsx
<ul className="kds-ticket__items">
  {order.items && order.items.length > 0 ? (
    order.items.map((item, i) => (
      <li key={item.id || i}>
        <span className="kds-ticket__qty">{item.quantity}&times;</span>
        <span className="kds-ticket__name">{item.productName}</span>
```

Its header (`kitchen-ticket.tsx:12-19`) states the constraint the allergen block must satisfy: *"monochrome
and unstyled by the brand — printers are black-on-white and toner is not a design surface"* — which is
exactly why UI-SPEC contracts **bordered, bold, uppercase text**, not an amber fill.

The print CSS lives at `app/globals.css:153-215+`; its `body:has(#kds-print-root)` scoping guard
(`:164-168`) explains why nothing outside `#kds-print-root` prints.

---

### `.../gdpr/DsarFanoutWorker.java` (service, background/batch) — D-17

**Analog: `core-java/src/main/java/uk/jtoye/core/webhook/WebhookRetentionCleanup.java` (101 lines, read in
full). Clone it.** It is the most complete of the three tenant-loop precedents (explicit GUC pin +
per-tenant error isolation), and `MediaQuarantineRetentionSweep` already establishes cloning it as the
house move.

**The whole loop** (`WebhookRetentionCleanup.java:49-100`):

```java
@Scheduled(fixedDelayString = "${webhook.delivery.retention-interval-ms:86400000}")
public void pruneExpired() {
    OffsetDateTime cutoff = OffsetDateTime.now().minusDays(properties.getDelivery().getRetentionDays());
    List<UUID> tenantIds = listTenantIds();
    long total = 0;
    for (UUID tenantId : tenantIds) {
        try {
            total += pruneTenant(tenantId, cutoff);
        } catch (Exception e) {
            log.error("event=webhook_retention_failed tenant={} — continuing: {}",
                    tenantId, e.getMessage());
        }
    }
    ...
}

private long pruneTenant(UUID tenantId, OffsetDateTime cutoff) {
    TenantContext.set(tenantId);
    try {
        Long deleted = transactionTemplate.execute(status -> {
            pinTenantGuc(tenantId);
            return deliveryRepository.deleteByCreatedAtBefore(cutoff);
        });
        ...
    } finally {
        TenantContext.clear();
    }
}

@SuppressWarnings("unchecked")
private List<UUID> listTenantIds() {
    return transactionTemplate.execute(status ->
            entityManager.createNativeQuery("SELECT id FROM tenants").getResultList());
}

private void pinTenantGuc(UUID tenantId) {
    Session session = entityManager.unwrap(Session.class);
    session.doWork(connection -> {
        try (var stmt = connection.prepareStatement(
                "SELECT set_config('app.current_tenant_id', ?, true)")) {
            stmt.setString(1, tenantId.toString());
            stmt.execute();
        }
    });
}
```

**Constructor: `TransactionTemplate` built from the manager, NOT `@Transactional`** (`:39-47`) — this dodges
the Spring self-invocation NULL-tenant trap named in its own header:

```java
public WebhookRetentionCleanup(WebhookDeliveryRepository deliveryRepository,
                               WebhookProperties properties,
                               EntityManager entityManager,
                               PlatformTransactionManager transactionManager) {
    ...
    this.transactionTemplate = new TransactionTemplate(transactionManager);
}
```

**Its header states both hazards** (`:17-28`):

```java
/**
 * Scheduled bounded-retention prune of {@code webhook_delivery} (#107,
 * T-22-05-05) — the {@code ScheduledCleanupService} shape: per-tenant, own
 * transaction each, {@link TenantContext} + GUC pinned so the delete is
 * RLS-scoped, a {@code TransactionTemplate} (not a {@code @Transactional} private
 * method) to dodge the Spring self-invocation NULL-tenant trap.
 *
 * <p><b>Scoped to {@code webhook_delivery} ONLY.</b> Suppression rows
 * ({@code notification_suppression}, 22-02) are deliberately NEVER time-pruned:
 * ...
 */
```

> That second paragraph is also the **source of truth for retention row R-6** in the published schedule
> — quote it, don't paraphrase.

**The `asSystem` wrap that goes INSIDE `transactionTemplate.execute` — verified there is no working
example.** Measured with a live control: `SystemPrincipal.asSystem(` occurs **exactly once** in
`core-java/src/main/java`, at `MediaProcessingWorker.java:298`, **inside a javadoc comment**
(control: `TenantContext.set(` appears across 25 files, so the search mechanism is live). The DSAR
worker is the **first production caller**, so build it from the class contract at
`SystemPrincipal.java:44-50`:

```java
 * <p><strong>The marker is an AUTHORISATION declaration, not a tenancy escape.</strong>
 * A system caller is still tenant-scoped by RLS exactly as every other caller is: the
 * {@code app.current_tenant_id} GUC is pinned from {@link uk.jtoye.core.security.TenantContext}
 * by {@code TenantSetLocalAspect}, and {@code FORCE ROW LEVEL SECURITY} filters every read
 * and write to the pinned tenant. Declaring system work says "this thread may pass the
 * shop-scope gate"; it says nothing whatsoever about which tenant's rows it can see, and it
 * cannot be used to reach another tenant's data.
```

and the lifecycle rule at `SystemPrincipal.java:53-63` (do not "simplify" the restoring `finally`; the
outermost exit `remove()`s rather than sets `false`).

---

### `.../gdpr/DsarRequest.java` + repository (model, CRUD) — D-16

**Analog:** `core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecord.java`. Field shape verified:

```java
@Entity
@Table(name = "erasure_records")
    private UUID id;
    @Column(name = "tenant_id", nullable = false)          private UUID tenantId;
    @Column(name = "subject_customer_id", nullable = false) private UUID subjectCustomerId;
    @Column(name = "subject_email_sha256", length = 64)     private String subjectEmailSha256;
    @Column(name = "orders_anonymised", nullable = false)   private int ordersAnonymised;
    ...
    @Column(name = "erased_by", length = 255)               private String erasedBy;
    @Column(name = "erased_at", nullable = false)           private OffsetDateTime erasedAt;
```

> **`subject_email_sha256` is the rule to carry forward:** a DSAR intake row keyed by email must store the
> **hash**, never the plaintext (V42's stated rule; ASVS V6 in RESEARCH § Security Domain). Note
> `ErasureRecord` is **tenant-scoped**; a platform-level DSAR intake row is **pre-tenant** and therefore
> needs the same explicit RLS decision `postcode_centroid` took — see [Shared Patterns](#4-rls-decision-for-any-new-table).

---

### `.../gdpr/DsarIntakeController.java` (controller, request-response) — D-16

**Analog for the class shape:** `.../gdpr/GdprController.java:25-37` — but note what must **change**: this
one is `@PreAuthorize("hasRole('admin')")` and vendor-scoped; the intake is consumer-facing.

```java
@RestController
@RequestMapping("/gdpr/customers")
@PreAuthorize("hasRole('admin')")  // issue #83 P1-1: PII export + erasure require the admin realm role
@Tag(name = "GDPR", description = "UK GDPR data subject rights — export and erasure")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class GdprController {
```

> **`GdprController.CustomerExport` carries `Integer allergenRestrictions`** (`:72-81`) — the Article 9
> field IS in the Art. 20 export today, recorded as correct in `docs/legal/article-9-allergen-basis.md`.
> D-01 does not change it. **The intake must not widen who can trigger that export**, and the privacy
> notice must describe it accurately.

**Analog for the Idempotency-Key contract:** `.../customer/CustomerController.java:74-86`:

```java
@Operation(summary = "Create customer", description = "Creates a new customer. Requires name and email (unique per tenant). Supply an Idempotency-Key header to make a retried POST safe: a repeated key replays the original customer and never creates a duplicate.")
...
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
```

backed by `.../common/idempotency/IdempotencyService.java:87` (`execute(...)`) and the two typed
exceptions `IdempotencyConflictException` / `IdempotencyPayloadMismatchException` that
`GlobalExceptionHandler` renders as RFC 7807. The OpenAPI header is advertised centrally by
`config/IdempotencyHeaderCustomizer.java:30`.

**Analog for a PUBLIC unauthenticated mutating endpoint:** `.../storefront/PublicStorefrontController.java`
— `@RequestMapping({"/public", "/api/v1/public"})` at `:47`, `@PostMapping("/shops/{slug}/orders")` at
`:192` with `@Valid @RequestBody`. That is the shape for an intake reachable without a JWT. Note the
threat model in RESEARCH § Security Domain: the existing Bucket4j limiter is **per-tenant**, so a
platform-level endpoint needs its own bucket.

---

### `.../order/OrderAllergenAggregator.java` (service, transform) — D-03

**Analog for the parsing substrate (reuse, do not fork):** `.../product/IngredientMarkupParser.java` —
a pure, static, fail-soft parser. Its header states it is *"the SINGLE SOURCE OF TRUTH for the markup
transform"* used by both the save path and the render path:

```java
public record ParsedIngredients(String plainText, List<AllergenSpan> spans) {
}

public static ParsedIngredients parse(String raw) {
    if (raw == null || raw.isEmpty()) {
        return new ParsedIngredients(raw == null ? "" : raw, List.of());
    }
    ...
}
```

**The gap that is the real work of D-03** — `AllergenSpan` (17 lines, read in full) carries **offsets only**:

```java
public record AllergenSpan(int start, int end) {
}
```

So `plainText.substring(start, end)` yields the *word*, and mapping that word to an allergen **bit** does
not exist in Java or TypeScript. That resolution is new logic; it has no analog.

**Analog for aggregating across an order's items — thin.** The only `getItems().stream()` in
`core-java/src/main/java` is a `mapToLong().sum()` at `PublicStorefrontService.java:805-807`:

```java
long itemSubtotal = order.getItems().stream()
        .mapToLong(item -> item.getTotalPricePennies())
        .sum();
```

There is **no set-union / distinct-collect aggregation over order items anywhere in the tree.** See
[No Analog](#no-analog-found).

**The write-time snapshot precedent (Pitfall 5)** — `OrderItem` already snapshots `productName` for
exactly the reason the allergen mask must be snapshotted (`PublicStorefrontService.java:786-789`):

```java
// UIX-03 root-cause fix: snapshot the REAL product title (server-side,
// authoritative) so OrderItem.productName never persists its
// "Unknown Product" default onto the kitchen display / order detail.
item.setProductName(product.getTitle());
```

`OrderItem.java:14-16` is `@Entity @Table(name = "order_items") @Audited`, so any new column needs an
`order_items_aud` mirror in the same migration.

**The empty seam D-02 plugs into** (`PublicStorefrontService.java:735-744`) — read it before editing:

```java
// allergenWarnings stays on the confirmation DTO and is always empty as of
// 2026-07-30: the customer-supplied allergen mask that populated it was
// special-category data (Art. 9) taken over an unauthenticated endpoint with
// no consent capture, and was removed. The field is retained as the seam a
// future *consented* warning path plugs into — the checkout UI already guards
// on length, so an empty list renders nothing. See
// docs/legal/article-9-allergen-basis.md.
List<String> allergenWarnings = new ArrayList<>();
```

---

### `.../product/AllergenCatalog.java` (model, constant table) — D-04

**Analog: the TypeScript original that must be mirrored** (`frontend/types/api.ts:486-517`, read in full).
There is no Java copy (research verified two-arm; the old `PublicStorefrontService.ALLERGEN_NAMES` was
deleted 2026-07-30).

```typescript
// Allergen constants (UK FSA 14). Name-only — the previous decorative emoji icons
// were dropped: several were ambiguous or inaccurate (one bean glyph was reused for
// both Soybeans and Sesame, a hot-dog glyph stood in for Mustard), and the name is
// the authoritative label already rendered alongside them everywhere.
export const ALLERGENS = [
  { bit: 0, name: "Gluten" },
  { bit: 1, name: "Crustaceans" },
  ...
  { bit: 13, name: "Molluscs" },
]

export function hasAllergen(mask: number, bit: number): boolean {
  return (mask & (1 << bit)) !== 0
}

export function getAllergenNames(mask: number): string[] {
  return ALLERGENS.filter(a => hasAllergen(mask, a.bit)).map(a => a.name)
}
```

**Two copies need a parity test that reads BOTH from disk.** The pattern for reading source out of the
tree in a Jest node-environment test is `frontend/__tests__/palette-discipline.test.ts` (below) or the
`fs.readFileSync` at `contrast-tokens.test.ts:20-24`.

---

### `scripts/check-retention-enforcement.sh` (gate script) — D-08 Half A

**Analog: `scripts/check-no-create-extension.sh` (155 lines, read in full).** It is the closest by every
criterion: **static** (reads files, touches no DB/network — the same property the retention gate needs so
it can be *wired into a workflow* rather than exempted), it enumerates-then-refuses-on-empty, it uses
0/1/2 with a documented VOID, and it carries the two shell hazards in its own header.

**Header — exit-code contract and why 2 is load-bearing** (`:26-32`):

```bash
# Exit codes:
#   0  no migration creates an extension
#   1  at least one does — named, with its line
#   2  VOID — the migration directory is missing, or the scan found NO FILES to check
#
# 2 is load-bearing. A zero-file scan reporting "clean" is the vacuous shape this repo has been
# bitten by repeatedly: "I found nothing" must never render as "there is nothing".
```

**Header — the two shell hazards** (`:34-44`), including the self-match trap the retention gate shares
(it will need to name config keys and periods) and the pipefail inversion:

```bash
#   1. A gate that forbids a string must NAME that string, so it can fire on its own definition.
#      The scan is therefore scoped to the migration directory ONLY, by absolute path, and this
#      script does not live there. ...
#
#   2. `cmd | grep -q X` under `set -o pipefail` INVERTS on match: grep exits at the first hit,
#      the writer takes SIGPIPE, and pipefail promotes it to 141 — so a guard written that way
#      fails OPEN on the case it exists to catch. Here-strings only, and counts captured with
#      `|| true` because `grep -c` exits 1 on a zero count, i.e. on the DESIRED state.
```

**Preamble, overridable input, fail/void helpers** (`:46-61`):

```bash
set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

# Overridable so the VOID direction is testable against an empty directory without inventing a
# second code path. Defaults to the real migration directory.
MIGRATION_DIR="${MIGRATION_DIR:-$REPO_ROOT/core-java/src/main/resources/db/migration}"

fail() { echo "FAIL: $*" >&2; exit 1; }
void() { echo "VOID: $*" >&2; exit 2; }
```

> The **overridable input** is the single most useful idea to copy: it makes the VOID arm testable
> without a second code path, which is precisely what CLAUDE.md dimension 5(a) demands.

**Enumerate-then-refuse-on-empty** (`:63-67`):

```bash
# Enumerate first, and refuse to report on an empty set.
mapfile -t MIGRATIONS < <(find "$MIGRATION_DIR" -maxdepth 1 -type f -name '*.sql' | sort)
COUNT="${#MIGRATIONS[@]}"
echo "  scanned    : ${COUNT} migration file(s)"
[ "$COUNT" -gt 0 ] || void "no .sql files found under $MIGRATION_DIR — refusing to report clean over an empty scan"
```

**Exemption-by-addition with a written justification, and a VOID when the table stops describing the
tree** (`:105-108`, `:144-148`) — this is the shape for "R-10 has no consumer, and that is deliberate":

```bash
EXEMPT=(
    # <migration-basename>|<extension>|<justification>
    "V1__base_schema.sql|uuid-ossp|Present since the base schema and applied successfully in every environment. ..."
)
...
# An exemption that stops matching is a silent hole: the line it covers may have been edited into
# a NEW violation, or deleted. Either way the table is now lying, so say so rather than pass.
if [ "$EXEMPTED" -ne "${#EXEMPT[@]}" ]; then
    void "expected ${#EXEMPT[@]} exempted occurrence(s) but matched ${EXEMPTED} — the exemption table no longer describes the tree, so this scan cannot be trusted. Re-read it against the migrations."
fi
```

**Its CI wiring — the step to copy** (`.github/workflows/ci-cd.yaml:684-689`, and note the comment
explains **why it lives in ci-cd.yaml rather than docs-freshness.yml**):

```yaml
      # Static by construction: reads .sql files, touches no database and makes no
      # network call, so it says the same thing on a hosted runner as it does locally.
      - name: Assert no migration creates a PostgreSQL extension (33-02)
        run: |
          chmod +x ./scripts/check-no-create-extension.sh
          ./scripts/check-no-create-extension.sh
```

> **Sequencing (D-08 note, RESEARCH Pitfall 4, re-verified wiring):** the script and this workflow step
> must land in the **same commit**. `check-gate-enforcement.sh` runs at `ci-cd.yaml:830-831`;
> script-without-reference → rc=1, conf-entry-without-script → rc=2. Opposite directions, no green
> two-commit ordering.

---

### `scripts/gates/claims.manifest` + `docs/retention-manifest.json` — D-08 Half B

**Analog: the manifest itself** (`scripts/gates/claims.manifest`, 66 lines, read in full). A `source` row
declares the source of truth; `rule` rows declare each place that quotes it. **TAB-separated.**

**Source row shapes** (`:15-16`) — a JSON source and a regex source:

```
source	metrics	json	docs/metrics.json	int
source	gradle	regex	build.gradle.kts	semver	^\s*version\s*=\s*"\K[0-9]+\.[0-9]+\.[0-9]+(?=")
```

**Rule row shape** (`:19`, `:32`) — `rule<TAB>source<TAB>key<TAB>consumer-file<TAB>label<TAB>PCRE-with-\K`:

```
rule	metrics	total_logical_invocations	README.md	total_logical_invocations	tests-\K[0-9]+(?=%20logical%20invocations)
rule	metrics	schema_version	CLAUDE.md	schema_version	Current schema version: V\K[0-9]+
```

**The jq escape hatch when a PCRE would over-match** (`:62-65`):

```
# The lockfile records the version at TWO paths and also once per dependency, so
# these MUST be addressed by jq path — a PCRE would match every dependency.
rule	gradle	-	frontend/package-lock.json	lock root	jq:.version
rule	gradle	-	frontend/package-lock.json	lock packages[""]	jq:.packages[""].version
```

**Its CI wiring** (`.github/workflows/docs-freshness.yml:89-91`) — already present, so retention rows cost
**no new script and no new step**:

```yaml
      - name: Verify documented claims match their sources (claim-gate engine)
        if: always()
        run: bash scripts/check-claims.sh
```

The engine's own headline invariant (M-1, quoted in RESEARCH:487): *"a rule that matches NOTHING is a
FAILURE, not a pass"* — which is why a rule row is falsifiable by deleting the sentence it reads.

> **Unit trap (Pitfall 7):** `kind` is only `json|regex` and `shape` only `int|semver`. There is **no unit
> transform**, so 259 200 000 ms and "72 hours" will never compare. Keep human units in
> `docs/retention-manifest.json` and let `check-retention-enforcement.sh` own the ms→hours conversion.

---

### `frontend/e2e/public-a11y.spec.ts` (test, browser) — D-10/D-13

**Analog: `frontend/e2e/public-layout.spec.ts`.** Research recommends option (b): a sibling spec, added to
the run line. Two of its helpers are **required**, not optional, because the fixture slug 404s once a real
backend is reachable.

**`resolveStorefrontPath` — resolves a shop that EXISTS in whatever environment**
(`public-layout.spec.ts:215-251`):

```typescript
async function resolveStorefrontPath(page: Page): Promise<string> {
  await page.goto(`${BASE}/shop`)
  await page.waitForLoadState("domcontentloaded")

  // A shop CARD, not merely a link under /shop/. `/shop/` also hosts `signin`,
  // `auth` and `orders`, and the storefront nav's "Sign in" button is an
  // `a[href^="/shop/"]` sitting above the grid ...
  const link = page
    .locator('a[href^="/shop/"]:visible')
    .filter({ has: page.locator("article") })
    .first()
  await expect(
    link,
    "the shop directory listed no storefront to open — neither the fixture stub " +
      "nor a live backend produced one"
  ).toBeVisible({ timeout: 15_000 })

  const href = await link.getAttribute("href")
  expect(href, "storefront link href").toBeTruthy()
  return href as string
}
```

**`openStorefront` — this IS the non-vacuity control pattern D-13 asks for, already written**
(`public-layout.spec.ts:253-275`):

```typescript
/**
 * Open a storefront and REFUSE to continue silently if it has no dish cards.
 *
 * The regression this exists to make loud: when the fixture slug started
 * 404ing, `locator("article").click()` simply waited out the full 60s test
 * timeout ... An empty page also satisfies every invariant below it (no
 * fixed-ratio boxes, no images, no overflow, an `<h1>` present), so the sibling
 * layout test passed VACUOUSLY over the same not-found page ...
 */
async function openStorefront(page: Page, path: string): Promise<void> {
  await page.goto(`${BASE}${path}`)
  await page.waitForLoadState("domcontentloaded")
  // Also outlasts the React streaming buffer (`<div id="S:n" hidden>`), whose
  // duplicate copy of the server-rendered tree is briefly in the DOM.
  await page.waitForTimeout(1200)

  await expect(
    page.locator("article:visible").first(),
    `${path} rendered no dish cards — the storefront did not load, so anything ` +
      `asserted past this point would be asserted over an empty page`
  ).toBeVisible({ timeout: 15_000 })
}
```

**The route list to extend** (`public-layout.spec.ts:204-213`) — note `/legal` is already scanned, which
supports RESEARCH Open Question 3's recommendation to include `/legal/*` in the axe surface list:

```typescript
const PUBLIC_ROUTES = [
  "/", "/shop", "/shop?q=grill", "/for-operators", "/track",
  "/legal", "/business-model-guide", "/competitive",
]
```

**The base URL convention** (`:39`): `const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"`.

**The stack-free constraint, in the spec's own words** (`:29-31`) — the reason this file and not
`e2e-nightly.yml`:

```
 * Wired into CI by the "Frontend E2E (public surfaces)" job in ci-cd.yaml.
 * KEEP IT STACK-FREE — the moment this needs a backend it stops running, and
 * the blind spot comes back.
```

**The CI run line to extend — a one-token edit** (`.github/workflows/ci-cd.yaml:320-325`):

```yaml
      - name: Run public-surface layout conformance (mobile + desktop)
        if: github.event_name != 'pull_request' || steps.filter.outputs.frontend == 'true'
        run: npx playwright test e2e/public-layout.spec.ts
        working-directory: frontend
        env:
          PLAYWRIGHT_BASE_URL: http://localhost:3000
```

**Do not regress:** `frontend/e2e/storefront-dish-modal-a11y.spec.ts` (verified present; header read). Its
`dialogState()` helper at `:38-100` is the model for reading a whole contract out of the live DOM in one
`page.evaluate`, and `:82-91` shows a contract assertion written as a **property** rather than one node's
attribute, after JSON-LD moved `aria-hidden` a level down:

```typescript
pageContentInert: (() => {
  const main = document.querySelector("main")
  if (!main) return false
  if (main.getAttribute("aria-hidden") === "true") return true
  const rendered = Array.from(main.children).filter((c) => c.tagName !== "SCRIPT")
  return (
    rendered.length > 0 &&
    rendered.every((c) => c.getAttribute("aria-hidden") === "true")
  )
})(),
```

Its fixture slug is `const SHOP_SLUG = "mama-ades-kitchen"` (`:35`) — the seeded shop UI-SPEC names.
Its header records the pre-fix measurements that make it falsifiable; **it must stay green**.

---

### `frontend/__tests__/contrast-literals.test.ts` (test, node) — F12 / Pitfall 2

**Analog A — recompute-from-source, and the instrument check:** `frontend/__tests__/contrast-tokens.test.ts`
(134 lines, read in full; **8/8 green, its expectations must not be edited**).

Its non-vacuity control is the first test in the file (`:92-97`) — copy this shape:

```typescript
it("extracts tokens at all — the instrument can see the file", () => {
  // Guards the vacuous case: if the regexes stopped matching, every ratio
  // below would throw rather than silently pass, but this states it outright.
  expect(CSS.length).toBeGreaterThan(500)
  expect(ratio(token("primary"), token("primary-foreground"))).toBeGreaterThan(1)
})
```

Its extraction VOIDs rather than passes on an unparseable input (`:69-85`):

```typescript
function block(scope: ":root" | ".dark"): string {
  const start = CSS.indexOf(`${scope} {`)
  if (start === -1) throw new Error(`VOID: no ${scope} block in globals.css`)
  ...
}
function token(name: string, scope: ":root" | ".dark" = ":root"): [number, number, number] {
  const m = block(scope).match(new RegExp(`--${name}:\\s*([^;]+);`))
  if (!m) throw new Error(`VOID: token --${name} not found in ${scope}`)
  ...
}
```

Its colour maths (`hslToRgb`/`hexToRgb`/`luminance`/`ratio`, `:32-60`) is **reusable verbatim** — the new
literal test needs `hexToRgb` + `luminance` + `ratio` and the same `AA_NORMAL = 4.5`. Its two-surface rule
(`:26-28`, `:112-125`) is the trap that produced ~100 violations: every text pairing must clear on **both**
`#ffffff` and `#FBF6F0`.

**Analog B — scanning `app/` and `components/` for literals:** `frontend/__tests__/palette-discipline.test.ts`
(63 lines, read in full). This is the missing half — a static source scan that already lives outside the
scanned tree so it never matches itself:

```typescript
/**
 * This file lives in `__tests__/` (outside app/ + components/) so its own
 * pattern literals are never scanned by the gates below.
 */
function grepCount(args: string[]): number {
  try {
    const out = execFileSync("grep", args, { cwd: FRONTEND_ROOT, encoding: "utf8" })
    return out.split("\n").filter((line) => line.trim().length > 0).length
  } catch (err) {
    const e = err as { status?: number }
    if (e && e.status === 1) return 0 // no matches
    throw err
  }
}
...
it("keeps marketing surfaces on palette tokens, not raw hex colors", () => {
  expect(grepCount(["-rnoE", "#[0-9a-fA-F]{3,8}", "components/marketing"])).toBe(0)
})
it("keeps the guest /track entry point wired app-wide (IA)", () => {
  expect(grepCount(["-rn", 'href="/track"', "app", "components"])).toBeGreaterThanOrEqual(3)
})
```

> The last assertion is the **positive-control shape** — a `>= 3` expectation proves the search
> mechanism works, so a `.toBe(0)` elsewhere in the same file is a fact about the code and not about
> a broken grep. Every new literal-scanning test needs one.

**`contrast-literals.test.ts` combines A and B:** grep the component sources for `text-|bg-|border-` +
colour-ramp literals, resolve each to a hex from the Tailwind palette, and recompute — so changing the KDS
banner's `amber-800` to `amber-400` reds the build (F12's break arm).

---

### `frontend/components/**/__tests__/*.a11y.test.tsx` (test, jsdom) — D-10 component layer

**No axe precedent exists in this repo** (research: zero `axe`/`pa11y`/`lighthouse` in
`frontend/package.json`, with `playwright` → 1 as the live control). So the a11y tests inherit the
**component-test shape**, not an axe shape.

**Closest shape — `frontend/components/ui/__tests__/ingredient-text.test.tsx`** (33 lines, read in full;
also the component S3 reuses). Plain `@testing-library/react`, no custom render harness, no setup beyond
`jest.setup.js`:

```tsx
import { render, screen } from "@testing-library/react"
import { IngredientText } from "@/components/ui/ingredient-text"

describe("IngredientText (QA FE-1)", () => {
  it("bolds **allergen** spans and never leaks literal asterisks", () => {
    const { container } = render(
      <IngredientText text="mango, **yoghurt (milk)**, cardamom" />
    )
    const strong = screen.getByText("yoghurt (milk)")
    expect(strong.tagName).toBe("STRONG")
    expect(container.textContent).toBe("mango, yoghurt (milk), cardamom")
  })
})
```

**Closest existing `role="alert"` assertion** — `components/dashboard/__tests__/shop-switcher.test.tsx:518-521`
(and the component's own comment at `shop-switcher.tsx:283-287`, which records that an `sr-only`
`role="alert"` is still announced). That is the model for F6.

**Jest config facts that constrain placement** (`frontend/jest.config.js`, read in full):

```javascript
  testEnvironment: 'jest-environment-jsdom',
  moduleNameMapper: { '^@/(.*)$': '<rootDir>/$1' },
  testMatch: ['**/__tests__/**/*.[jt]s?(x)', '**/?(*.)+(spec|test).[jt]s?(x)'],
  testPathIgnorePatterns: ['/node_modules/', '/e2e/', '/.next/'],
```

> **`testMatch` picks up EVERY file under a `__tests__/` directory**, so a fixtures file placed there
> runs as a suite. Put fixtures beside the component or suffix them `.fixtures.ts` outside `__tests__/`.
> Node-environment tests declare `@jest-environment node` in the top docblock
> (`contrast-tokens.test.ts:2`, `palette-discipline.test.ts:2`).

---

### `frontend/lib/__tests__/consent.test.ts` (test, jsdom) — F1

**Analog:** `frontend/lib/__tests__/cart-identity.test.ts` (exists; 5 `localStorage.setItem` uses — the
directory's established localStorage-test idiom) and
`frontend/lib/__tests__/customer-auth-signout-clears-carts.test.ts`.

> **The falsifiability arm is the whole point** (D-05's own warning + F1): a zero-category gate cannot
> fail as shipped. The test must register a **fixture category**, assert `isAllowed === false` and the
> script did not load, record a choice, then assert both flipped — **and** a separate test must assert
> the SHIPPED configuration registers zero non-essential categories. RESEARCH:872-885 gives the exact
> two-arm skeleton.

---

### `frontend/eslint.config.mjs` (config) — the free third layer

**Analog: the file itself** (47 lines, read in full). Add a scoped rules object in the same shape as
`:29-36`. **`.eslintrc.json` does not exist** — verified: `ls` → *"No such file or directory"*, rc=2.

```javascript
import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

/**
 * ESLint v9 flat config (issue #99 do-now).
 * ... eslint-config-next@16 ships NATIVE flat-config arrays at the /core-web-vitals
 * and /typescript subpaths; we spread them directly. Do NOT wrap them with FlatCompat
 * — that crashes with a circular-structure error.
 */
const config = [
  { ignores: [".next/**", "node_modules/**", ...] },
  ...nextCoreWebVitals,
  ...nextTypescript,
  {
    // Tests legitimately use `any` and rely on harness globals — relax there.
    files: ["**/__tests__/**", "**/*.test.*", "**/*.spec.*"],
    rules: { "@typescript-eslint/no-explicit-any": "off", "react-hooks/globals": "off" },
  },
];
```

> Research measured **6 of ~35 jsx-a11y rules enabled** today via the `next/core-web-vitals` subset.
> Appending a `{ rules: { "jsx-a11y/…": "error", … } }` object is the whole change: zero new packages,
> zero new CI minutes, and it catches the class axe provably cannot (placeholder-as-label, A11Y-13).

---

## Shared Patterns

### 1. Skip link — one pattern, three existing copies, do not invent a fourth

**Source:** `components/marketing/operator-pitch.tsx:70` (siblings at `business-model-guide.tsx:110-115`
and `competitive-teardown.tsx:212`).
**Apply to:** `components/public/public-shell.tsx`, `app/shop/layout.tsx`.

```tsx
<a href="#main-pitch" className="sr-only z-50 rounded-full bg-oxblood px-4 py-3 text-sm font-bold text-white focus:not-sr-only focus:absolute focus:left-4 focus:top-4">Skip to operator pitch</a>
```

The `business-model-guide.tsx:110-115` variant uses `rounded` + `font-semibold` and multi-line formatting —
**the two copies already differ.** Pick `operator-pitch.tsx:70` verbatim (UI-SPEC names it) and target the
`PublicShell`'s existing `<main>` after giving it `id="main"`.

### 2. Focus-visible ring — the three primitives that already have it

**Source:** `components/ui/button.tsx:8`, `input.tsx`, `asset-image.tsx`.
**Apply to:** every new interactive element (cookie-notice dismiss, ToC links, acknowledgement checkbox).
`focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2`.

> The marketing components use a **different** idiom —
> `focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500`
> (`business-model-guide.tsx:146`). Two idioms already coexist. UI-SPEC picks the **ring** form; on
> oxblood surfaces swap the ring colour to cream/white (orange-700 on `#3A0B0D` is a weak boundary).

### 3. Reduced motion for raw CSS

**Source:** `app/globals.css:141-151` (`.kds-press`).
**Apply to:** any CSS transition this phase adds (cookie-notice entrance). `MotionConfig reducedMotion="user"`
(`components/motion-provider.tsx:12`) covers framer-motion only.

### 4. RLS decision for any new table

**Source:** `RlsContractTest.EXEMPT_TABLES` — the `postcode_centroid` entry (research cites
`core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java:95-134`), and the identical
exempt-by-addition idiom in `scripts/check-no-create-extension.sh:105-108`.
**Apply to:** `dsar_request` (pre-tenant), any consent table (**should not exist** per Q1).

The rule, stated in both places: exempt **by ADDITION with a written justification**, never by weakening
the sweep — and the justification must say *why adding RLS would be worse* (with no `tenant_id` there is
no predicate, so a FORCE'd policy returns zero rows to every caller).

### 5. Tenant-looped migration backfill

**Applies to:** `V62` if it snapshots `order_items.allergen_mask` (Pitfall 5).
A bare `UPDATE` against a FORCE-RLS table hits **zero rows** — recurred at V25, V44, V57. Loop tenants with
`set_config`. The runtime equivalent is `WebhookRetentionCleanup.pinTenantGuc` (excerpt above).
Also: **never name a property in `${…}` form inside a migration comment** — Flyway substitutes inside
comments and aborts startup everywhere.

### 6. `NEXT_PUBLIC_*` is a BUILD arg, not a runtime env

**Source:** `lib/company.ts:14-19` + the `NEXT_PUBLIC_SUPPORT_EMAIL`/`_URL` triple
(`.env.example`, `docker-compose.full-stack.yml`, `frontend/Dockerfile`), degraded gracefully by
`lib/env-validation.ts`.
**Apply to:** `NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` and the DSAR contact. All three files must change
together or the value is inlined as `""`.

### 7. Gate step wiring

**Source:** `.github/workflows/ci-cd.yaml:684-689` (static gates) and `docs-freshness.yml:60-91`
(claim/doc gates, all `if: always()`).
**Apply to:** `check-retention-enforcement.sh` (**same commit as the script**) and the axe spec run line.

### 8. Reachability is one footer edit, not a new component

**Source:** `components/public/public-footer.tsx:110-138` (the "For customers" column) — copy it for a
"Legal" column. Verified: `app/shop/layout.tsx:73` renders `<PublicFooter />`, so the storefront and its
checkout inherit the fix for free (RESEARCH Correction 1, re-verified this session).

```tsx
<div className="space-y-3">
  <h3 className="text-xs font-bold uppercase tracking-[0.14em] text-gold">
    For customers
  </h3>
  <ul className="space-y-2 text-sm">
    <li>
      <Link href="/shop" className="text-cream/85 transition-colors hover:text-white">
        Browse shops
      </Link>
    </li>
```

> **F-C interacts here:** these column headings are `<h3>` with no `<h2>` above them, which is 2 of the
> 15 axe nodes. The Legal column must be `<h2>`, and so must the existing three — one component edit.
> **Also re-run `scripts/check-geo-attribution.sh` after editing** (Pitfall 8): it reads this footer and
> **VOIDs at 2** if it cannot find it.

### 9. `docs/legal/` is the home for legal documents

**Source:** `docs/legal/article-9-allergen-basis.md` (122 lines, 2026-07-30) and
`docs/legal/derivation-clause.md` (DRAFT). **Verified both present.**
**Apply to:** D-18's Article 26 arrangement. The Art. 9 doc already lists *"Write the privacy notice —
there is currently none"* as its own step 4 — **extend and date it, never contradict it.**

---

## No Analog Found

Files with no close match. The planner should use RESEARCH.md patterns, or design deliberately.

| # | File | Role | Data Flow | Nearest neighbour, and how it differs |
|---|---|---|---|---|
| 1 | `frontend/lib/consent.ts` — **the version-string key** | utility | event-driven | `lib/shop-context.ts` covers store+subscribe+SSR; `lib/cart-identity.ts` covers the try/catch. **Zero versioned localStorage keys exist** (searched `STORAGE_VERSION\|SCHEMA_VERSION\|_VERSION\s*=\|version.*localStorage` → rc=1, against a live control of 10+ `setItem` files). Design the version comparison; there is nothing to copy. |
| 2 | `frontend/lib/consent.ts` — **the script gate** | utility | event-driven | Nothing in the tree conditionally loads a `<script>`. `lib/gsap-gate.ts` gates a *library import* by route, not by consent, and is the only adjacent idea. The block/permit mechanism is new. |
| 3 | `frontend/components/public/cookie-notice.tsx` — **dismissible + z-stacking** | component | event-driven | `mobile-tab-bar.tsx:88` and `FloatingCartBar` (`shop-detail-client.tsx:783`) are both `fixed`+`z-50`+safe-area — so "zero CLS" is inherited for free. But **nothing in the tree is dismissible-and-persisted**, and nothing resolves two `z-50` bottom bars sharing a 375×667 viewport. The overlap rule (UI-SPEC "must not overlap the sticky basket bar") has no precedent. |
| 4 | `frontend/components/legal/retention-table.tsx` | component | transform | **Both existing table treatments violate the contract.** `business-model-guide.tsx:227` uses `min-w-[640px]` + `overflow-x-auto` — guaranteed horizontal scroll at 375px, which F3 forbids. `app/dashboard/webhooks/page.tsx:303` uses `hidden overflow-x-auto sm:block` + a mobile card list — duplicated DOM, which UI-SPEC forbids by name (#556/#593). A table that genuinely **fits** at 375px does not exist here. |
| 5 | `frontend/components/legal/policy-toc.tsx` — **the responsive form** | component | transform | `business-model-guide.tsx:142-157` gives the labelled `<nav>` + stable-id `<h2>` + `scroll-mt-20`. It does **not** give a sticky side rail at `lg`+ collapsing to a disclosure below `lg`. That responsive shape is new. |
| 6 | Checkout **`aria-invalid`** | component | — | **Zero occurrences in `app/` + `components/`** (rc=1). New. |
| 7 | Checkout **`aria-describedby` on a form field** | component | — | Zero. The 2 production hits are Radix dialog internals (`product-detail-modal.tsx:266-268`, `ConfirmActionDialog.tsx:18`). New. |
| 8 | Checkout **focus-move to the invalid field** | component | — | Zero form-focus management. The only `.focus()` in `app/`+`components/` is the modal's focus-return (`product-detail-modal.tsx:117`). New. |
| 9 | Checkout **`autocomplete` tokens** (A11Y-08) | component | — | `WebhookCreateDialog.tsx:141` sets `autoComplete="off"` — the opposite of what WCAG 1.3.5 wants. No positive example on a name/email/address field. New. |
| 10 | **Any `jest-axe` usage** | test | — | Zero axe in the repo. `contrast-tokens.test.ts` is the *only* a11y-adjacent test. Component tests give the render shape; the axe call and its non-vacuity control are new. |
| 11 | `AllergenSpan` **text → allergen-bit resolution** | service | transform | `IngredientMarkupParser` gives offsets; `AllergenSpan(int start, int end)` carries no identity. This mapping exists in **neither** language. **This is the substantive new logic in LGL-03.** |
| 12 | **Set-union aggregation across order items** | service | transform | The only `getItems().stream()` in `core-java/src/main/java` is `mapToLong(...).sum()` (`PublicStorefrontService.java:805-807`). No distinct/collect aggregation over items anywhere. |
| 13 | **A production `SystemPrincipal.asSystem` caller** | service | batch | Measured: 1 occurrence in `core-java/src/main/java`, **inside a javadoc comment** (`MediaProcessingWorker.java:298`); control `TenantContext.set(` = 25 files. Real invocations exist only in 6 test files. Build from the class contract (`SystemPrincipal.java:44-63`), not from an example. |

**Also recorded as probably-unnecessary rather than missing:**
`frontend/components/storefront/legal-strip.tsx` — RESEARCH Correction 1 (re-verified: `app/shop/layout.tsx:73`
renders `<PublicFooter />`) shows the `contentinfo` landmark already exists on `/shop/**`. Build it **only**
for the vendor trading-name line UI-SPEC also wants, and say so; not for a landmark that is already there.

---

## Anti-Analogs — patterns in the tree you must NOT copy

| Pattern | Where | Why not |
|---|---|---|
| `hidden overflow-x-auto sm:block` table + duplicate mobile card list | `app/dashboard/webhooks/page.tsx:296-320`, `webhooks/[id]/page.tsx:444` | Duplicated DOM. UI-SPEC S2a forbids a second mobile-only copy by name; this repo filed the same class twice as a product bug (#556, #593). |
| `min-w-[640px]` inside `overflow-x-auto` | `components/marketing/business-model-guide.tsx:227` | Guarantees a horizontal scrollbar at 375px, which is exactly what F3 asserts against. |
| A bare `<div>` shell with no `<main>`/`<h1>` on a public route | `app/auth/signin/page.tsx:31-33` (verified: client component, `<div className="min-h-screen flex …">`) | This IS F-D — 7 of the 15 remaining axe nodes, plus the stale root `<title>`. It is the defect, not the pattern. |
| A field error as a plain `<p>` with no `id`/`aria-describedby` | `WebhookCreateDialog.tsx:146-148`, `checkout/page.tsx:828-832` | The `role="alert"` sibling at `:190-197` is the good half; the field-level half is the gap. |
| Hand-rolled `fixed inset-0` overlay | (removed) `product-detail-modal.tsx` pre-#446 | Reintroducing it reopens six defects at once. Use Radix. |
| Trusting a CSS-variable contrast test to cover a Tailwind literal | `contrast-tokens.test.ts` is green while `text-emerald-600` fails on 4 nodes | Pitfall 2. The token test reads `globals.css` and is structurally incapable of seeing a utility class. |
| `disabled` as the gate for the acknowledgement | `checkout/page.tsx:845` (`disabled={submitting \|\| belowMinimum}`) | Legitimate for `belowMinimum` (it has a permanent hint at `:835-840`); wrong for acknowledgement — a disabled button gives no feedback on touch. UI-SPEC contracts a **refusal that announces itself**. |

---

## Verification Log

Every "zero" below was run with a positive control of the same shape, per CLAUDE.md § Proof Standards.

| Claim | Command shape | Result | Control |
|---|---|---|---|
| `.eslintrc.json` absent | `ls frontend/.eslintrc.json` | rc=2, "No such file" | `frontend/eslint.config.mjs` read, 47 lines |
| `aria-invalid` unused | `rg -uu -n 'aria-invalid' app components` | **rc=1, 0 hits** | `role="alert"` same flags → rc=0, 12 hits |
| `aria-describedby` on a field | same shape | 2 hits, **both Radix dialog internals** | as above |
| No versioned storage key | `rg -uu 'STORAGE_VERSION\|SCHEMA_VERSION\|_VERSION\s*=\|version.*localStorage' app components lib` | **rc=1** | `localStorage.setItem` → 10+ files |
| No production `asSystem` caller | `rg -uu 'SystemPrincipal\.asSystem\(' core-java/src/main/java` | rc=0, **1 hit, in a javadoc comment** | `TenantContext.set(` → 25 files |
| `/shop/**` has a footer | read `app/shop/layout.tsx:45-76` | `<PublicFooter />` at the layout tail | — |
| shadcn `Table` lacks role/aria/tabindex | read `components/ui/table.tsx:5-17` | `<div className="relative w-full overflow-auto">` only | — |
| `@radix-ui/react-checkbox` absent | `ls frontend/components/ui/` | 20 primitives, **no `checkbox.tsx`** | `badge.tsx`, `label.tsx`, `input.tsx` present |
| `docs/legal/` contents | `ls docs/legal/` | `article-9-allergen-basis.md`, `derivation-clause.md` | — |
| `frontend/app/legal/` contents | `ls -R frontend/app/legal/` | **one file**, `page.tsx` | — |

**Files opened in this session** (each excerpt above is from one of these, not from RESEARCH.md):
`frontend/app/legal/page.tsx` · `components/public/public-shell.tsx` · `components/public/public-footer.tsx:95-175` ·
`components/marketing/business-model-guide.tsx:95-175` · `components/dashboard/mobile-tab-bar.tsx:60-110` ·
`app/shop/[slug]/shop-detail-client.tsx:760-810` · `app/shop/layout.tsx:45-76` ·
`app/shop/[slug]/checkout/page.tsx:410-450, 790-864` · `app/dashboard/kitchen/page.tsx:830-934` ·
`components/dashboard/kitchen/kitchen-ticket.tsx` · `app/globals.css:135-215` ·
`components/dashboard/webhooks/WebhookCreateDialog.tsx:60-210` · `app/dashboard/webhooks/page.tsx:296-320` ·
`components/ui/table.tsx:1-30` · `components/ui/__tests__/ingredient-text.test.tsx` ·
`frontend/__tests__/contrast-tokens.test.ts` · `frontend/__tests__/palette-discipline.test.ts` ·
`frontend/e2e/public-layout.spec.ts:1-45, 190-320` · `frontend/e2e/storefront-dish-modal-a11y.spec.ts:1-100` ·
`frontend/lib/{cart-identity,order-history,shop-context,company}.ts` · `frontend/types/api.ts:483-518` ·
`frontend/app/sitemap.ts:78-115` · `frontend/app/auth/signin/page.tsx:1-45` · `frontend/eslint.config.mjs` ·
`frontend/jest.config.js` · `core-java/.../webhook/WebhookRetentionCleanup.java` ·
`core-java/.../gdpr/GdprController.java` · `core-java/.../gdpr/ErasureRecord.java` ·
`core-java/.../security/access/SystemPrincipal.java:1-80` · `core-java/.../product/{IngredientMarkupParser,AllergenSpan}.java` ·
`core-java/.../order/OrderItem.java:1-60` · `core-java/.../storefront/PublicStorefrontService.java:720-830` ·
`core-java/.../storefront/PublicStorefrontController.java` (grep) ·
`scripts/check-no-create-extension.sh` · `scripts/gates/claims.manifest` ·
`.github/workflows/ci-cd.yaml:300-335, 675-695` · `.github/workflows/docs-freshness.yml:60-100`

---

## Metadata

**Analog search scope:** `frontend/{app,components,lib,types,e2e,__tests__}`,
`core-java/src/main/java/uk/jtoye/core/{gdpr,webhook,order,product,storefront,security,customer,common,config,media}`,
`scripts/`, `scripts/gates/`, `.github/workflows/`, `docs/legal/`.
**Files scanned:** ~340 (glob + grep enumeration); **38 opened and read.**
**Pattern extraction date:** 2026-08-15
**Branch:** `phase-29-research` (working tree clean at start)

**Stale figures NOT carried forward** (RESEARCH § State of the Art): "2 of 126 Playwright tests" → **20 of 220**;
"`notification_consent` (V54)" → the tables are `notification_suppression` + `marketing_opt_in`;
"220 baseline color-contrast violations" → **4** on the declared surfaces; "`/shop/[slug]` has no
`contentinfo`" → it has one.
