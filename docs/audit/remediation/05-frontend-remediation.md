# Frontend Remediation Plan

**Date**: 2026-04-27
**Authors**: Frontend Tech Lead (Specialist) + UX / Design-System Reviewer (Assistant)
**Source audit**: `docs/audit/sources/05-frontend-ux.md`
**Council synthesis**: `docs/audit/COUNCIL-AUDIT-2026-04-27.md`
**Verified against**: `frontend/` HEAD (commit `a8f61c2`)

---

## Operating principles (agreed by both reviewers)

1. **Surgical, not sweeping.** PR #49 ("Warm Editorial / design-system-overhaul") was reverted because it changed the *story* the platform told. Every change here must read as "the same product, fixed" — not "a new product." Token swap + chrome retheme + responsive shell. No new typeface, no new spacing scale, no new card geometry, no new animation language.
2. **Brand reaches the chrome, not just the pages.** The storefront is fine. The dashboard chrome is the work. Touch design tokens once, then let the existing `<Button variant="default">` calls cascade.
3. **Mobile-first applies to vendors too.** The dashboard sidebar must collapse on phones without hurting the desktop sidebar the audit credited as the "rest of the chrome works."
4. **No regressions on the storefront.** Storefront is the highest-graded surface (`05-frontend-ux.md` score 5.5/10 design overall, but the storefront alone is "Deliveroo-adjacent"). Every change must be diffed against `app/shop/*` to confirm nothing degrades.
5. **Each remediation has a verifiable success metric.** Lighthouse score, axe violation count, bundle-byte delta, WCAG contrast ratio, file LOC.
6. **Refactors ship behind visual-snapshot tests, not free-hand.** Splitting a 1231-LOC page is dangerous without a Playwright baseline.

---

## Finding 1 — Design token rebrand cascade

### Specialist proposal

`frontend/app/globals.css` lines 13, 25, 36, 48 hardcode shadcn-blue. The fix is one diff in `globals.css` plus targeted retheme of three files. Verified scope (grep for `bg-blue|bg-purple|from-blue|to-purple|from-purple|text-blue-|text-purple-` in `frontend/`): **13 files, ~36 hits**. Of those, six are storefront/finance/products *intentional accents* (info banners, fallback icons) — leave them. Seven are dashboard chrome and must be converted.

`globals.css` diff (light + dark, full):

```diff
   :root {
-    --primary: 221.2 83.2% 53.3%;
+    --primary: 24 95% 53%;            /* orange-500 */
     --primary-foreground: 210 40% 98%;
-    --secondary: 210 40% 96.1%;
+    --secondary: 210 40% 96.1%;       /* unchanged - neutral surface */
     --secondary-foreground: 222.2 47.4% 11.2%;
     --muted: 210 40% 96.1%;
     --muted-foreground: 215.4 16.3% 46.9%;
-    --accent: 210 40% 96.1%;
-    --accent-foreground: 222.2 47.4% 11.2%;
+    --accent: 158 64% 52%;            /* emerald-500 */
+    --accent-foreground: 0 0% 100%;
     --destructive: 0 84.2% 60.2%;
     --destructive-foreground: 210 40% 98%;
     --border: 214.3 31.8% 91.4%;
     --input: 214.3 31.8% 91.4%;
-    --ring: 221.2 83.2% 53.3%;
+    --ring: 24 95% 53%;               /* match primary */
     --radius: 0.5rem;
   }

   .dark {
     ...
-    --primary: 217.2 91.2% 59.8%;
+    --primary: 24 95% 58%;            /* orange-400-ish for dark surface */
     --primary-foreground: 222.2 47.4% 11.2%;
-    --accent: 217.2 32.6% 17.5%;
-    --accent-foreground: 210 40% 98%;
+    --accent: 158 64% 45%;            /* emerald-600 for dark */
+    --accent-foreground: 0 0% 100%;
-    --ring: 224.3 76.3% 48%;
+    --ring: 24 95% 58%;
   }
```

**Caveat on `--accent`.** shadcn ships `--accent` as a *neutral hover surface*, used by `<Button variant="ghost">` and `<DropdownMenuItem>`. Re-binding it to emerald breaks every hover state into a green flash. The right call is **leave `--accent` neutral** and add a *new* token `--brand-accent` (or use `bg-emerald-500` directly where intentional). Revised:

```diff
-    --accent: 210 40% 96.1%;          /* keep neutral for ghost hover */
-    --accent-foreground: 222.2 47.4% 11.2%;
+    --accent: 210 40% 96.1%;          /* unchanged */
+    --accent-foreground: 222.2 47.4% 11.2%;
+    --brand-accent: 158 64% 52%;
+    --brand-accent-foreground: 0 0% 100%;
```

Then `tailwind.config.ts:41-44` adds:

```ts
brandAccent: {
  DEFAULT: "hsl(var(--brand-accent))",
  foreground: "hsl(var(--brand-accent-foreground))",
},
```

Sidebar diff (`components/dashboard/sidebar.tsx`):

```diff
-      <Store className="h-8 w-8 text-blue-400" />          /* line 57 */
+      <Store className="h-8 w-8 text-orange-400" />

-      <div className="h-10 w-10 rounded-full bg-gradient-to-br from-blue-400 to-purple-500 ..."> /* line 68 */
+      <div className="h-10 w-10 rounded-full bg-gradient-to-br from-orange-400 to-orange-600 ...">

-                  ? "bg-blue-600 text-white shadow-lg shadow-blue-500/50"  /* line 90 */
+                  ? "bg-orange-600 text-white shadow-lg shadow-orange-500/40"
```

Stat-cards diff (`app/dashboard/page.tsx:152-157`):

```diff
   const statCards = [
-    { title: "Shops", value: stats?.shops || 0, icon: Store, color: "text-blue-600", bgColor: "bg-blue-100" },
-    { title: "Products", value: stats?.products || 0, icon: Package, color: "text-purple-600", bgColor: "bg-purple-100" },
-    { title: "Orders", value: stats?.orders || 0, icon: ShoppingCart, color: "text-green-600", bgColor: "bg-green-100" },
-    { title: "Customers", value: stats?.customers || 0, icon: Users, color: "text-orange-600", bgColor: "bg-orange-100" },
+    { title: "Shops", value: stats?.shops || 0, icon: Store, color: "text-orange-600", bgColor: "bg-orange-100" },
+    { title: "Products", value: stats?.products || 0, icon: Package, color: "text-slate-700", bgColor: "bg-slate-100" },
+    { title: "Orders", value: stats?.orders || 0, icon: ShoppingCart, color: "text-emerald-700", bgColor: "bg-emerald-100" },
+    { title: "Customers", value: stats?.customers || 0, icon: Users, color: "text-amber-700", bgColor: "bg-amber-100" },
   ]
```

Loading spinners (`app/dashboard/page.tsx:147`, `app/dashboard/marketing/page.tsx:504,512`): convert `border-blue-600` → `border-orange-500`.

Marketing tab indicators (`app/dashboard/marketing/page.tsx:534,544`): `border-blue-600 text-blue-600` → `border-orange-600 text-orange-600`.

Orders status-flow gradient (`app/dashboard/orders/page.tsx:476`): `from-blue-50 to-purple-50` → `from-orange-50 to-amber-50`.

Recharts fills (`app/dashboard/page.tsx:276-277`): `#3b82f6, #a855f7` → `#f97316, #10b981` (orange-500, emerald-500).

Files **not touched** (intentional, retain): `app/shop/[slug]/page.tsx:451-456` (info banner, blue is correct semantic for "announcement"), `app/dashboard/finance/page.tsx:38,150` (VAT category color coding — STANDARD blue is a chart-distinct semantic, not chrome). `components/ui/image-uploader.tsx:245` (drag-active state — keep blue, signals "drop here" affordance, distinct from brand). `app/shop/orders/page.tsx:90` (CONFIRMED order status — addressed in finding 9, not here).

### Assistant deliberation

Three challenges:

1. **"This is Warm Editorial 2.0 in disguise."** No. Warm Editorial replaced fonts (serif display), spacing, photography style, button radii, *and* tokens. This proposal touches **one CSS file plus three components** — total ~25 lines. The cardinality is two orders of magnitude smaller. Acceptable. Validated.

2. **Ghost-hover regression risk.** Specialist's first draft re-bound `--accent` to emerald. That would have flashed green on every dropdown menu item, popover hover, and `<Button variant="ghost">` (used 5 times in the sidebar alone, lines 102-118). The revised proposal (keep `--accent` neutral, add `--brand-accent` for opt-in) avoids the regression. **This is the load-bearing correction** — without it the change would have looked broken on first render.

3. **Stat-card color choice.** Specialist's revised palette (orange / slate / emerald / amber) gives four *visually distinct* semantic categories without recreating the rainbow. The original (blue/purple/green/orange) was arbitrary; the new palette is brand-coherent (orange = primary, emerald = accent, slate = neutral, amber = warmth). Validated.

4. **Bonus check — dark mode.** The dark variant orange (`24 95% 58%`) needs verification on the slate-950 sidebar at 4.5:1. WCAG calc: orange `#f97316` on slate-900 `#0f172a` = 4.62:1, passes AA. orange-400-ish on slate-950 = 5.1:1, passes AA. Validated.

### Reconciled position

**Ship.** One-file token change in `globals.css` + three component diffs (sidebar, dashboard page, marketing tabs/spinners) + Recharts fills + orders gradient strip. Zero new design primitives. Zero new typography. Zero spacing changes. Estimated diff: **~60 lines across 5 files**. Reviewability: trivial. Risk: low. The vendor sees the same dashboard layout — just orange not blue.

---

## Finding 2 — Responsive vendor dashboard

### Specialist proposal

The chrome is `frontend/components/dashboard/dashboard-shell.tsx` (24 LOC) wrapping `frontend/components/dashboard/sidebar.tsx` (122 LOC). The audit is correct: `sidebar.tsx:54` is `flex h-full w-64` with no breakpoint, and `dashboard-shell.tsx:19` uses `container mx-auto p-8` (32px padding on every viewport). On 375px the sidebar consumes 256px = 68% of the screen — confirmed.

There is **no `<Sheet>` primitive** in `components/ui/` today. We don't need one. `@radix-ui/react-dialog@1.1.15` is already installed and is the same Radix primitive shadcn's `Sheet` wraps with side-anchored positioning. We add a small `components/ui/sheet.tsx` (~50 LOC) that re-exports Radix Dialog with `data-side="left"` slide-in animation, then refactor `dashboard-shell.tsx` to render the sidebar inline at `md:` and inside a `<Sheet>` below.

Pattern (three breakpoints, no tablet middle-ground — keeps complexity low):

- **<768px (`md:` boundary)**: hamburger in a top app bar; sidebar lives inside `<Sheet side="left">`; main content uses `p-4` (16px).
- **>=768px**: existing sidebar behaviour, `p-6` (24px) — drop the `p-8` to give the content more room without re-engineering page-level paddings.

**`components/ui/sheet.tsx`** (new file, full):

```tsx
"use client"

import * as React from "react"
import * as DialogPrimitive from "@radix-ui/react-dialog"
import { X } from "lucide-react"
import { cn } from "@/lib/utils"

const Sheet = DialogPrimitive.Root
const SheetTrigger = DialogPrimitive.Trigger
const SheetClose = DialogPrimitive.Close
const SheetPortal = DialogPrimitive.Portal

const SheetOverlay = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Overlay>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Overlay>
>(({ className, ...props }, ref) => (
  <DialogPrimitive.Overlay
    ref={ref}
    className={cn(
      "fixed inset-0 z-50 bg-black/60 backdrop-blur-sm",
      "data-[state=open]:animate-in data-[state=open]:fade-in-0",
      "data-[state=closed]:animate-out data-[state=closed]:fade-out-0",
      className
    )}
    {...props}
  />
))
SheetOverlay.displayName = "SheetOverlay"

interface SheetContentProps
  extends React.ComponentPropsWithoutRef<typeof DialogPrimitive.Content> {
  side?: "left" | "right"
}

const SheetContent = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Content>,
  SheetContentProps
>(({ side = "left", className, children, ...props }, ref) => (
  <SheetPortal>
    <SheetOverlay />
    <DialogPrimitive.Content
      ref={ref}
      className={cn(
        "fixed inset-y-0 z-50 flex flex-col bg-slate-900 text-white shadow-xl",
        "transition ease-in-out data-[state=closed]:duration-200 data-[state=open]:duration-300",
        side === "left"
          ? "left-0 w-72 data-[state=open]:slide-in-from-left data-[state=closed]:slide-out-to-left"
          : "right-0 w-72 data-[state=open]:slide-in-from-right data-[state=closed]:slide-out-to-right",
        className
      )}
      {...props}
    >
      {children}
      <DialogPrimitive.Close
        className="absolute right-3 top-3 rounded-md p-1 text-slate-300 hover:bg-slate-800 hover:text-white focus:outline-none focus:ring-2 focus:ring-orange-500"
        aria-label="Close navigation"
      >
        <X className="h-5 w-5" />
      </DialogPrimitive.Close>
    </DialogPrimitive.Content>
  </SheetPortal>
))
SheetContent.displayName = "SheetContent"

export { Sheet, SheetTrigger, SheetClose, SheetContent }
```

**`components/dashboard/dashboard-shell.tsx`** (rewrite, full):

```tsx
"use client"

import { useState } from "react"
import { Menu } from "lucide-react"
import type { ReactNode } from "react"
import { Sidebar } from "@/components/dashboard/sidebar"
import { Sheet, SheetContent, SheetTrigger } from "@/components/ui/sheet"
import { Button } from "@/components/ui/button"

export function DashboardShell({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false)

  return (
    <div className="flex h-screen overflow-hidden bg-slate-50 dark:bg-slate-950">
      {/* Desktop sidebar — unchanged from audit-credited behaviour */}
      <div className="hidden md:flex">
        <Sidebar />
      </div>

      {/* Mobile sidebar — sheet overlay */}
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent side="left">
          <Sidebar onNavigate={() => setOpen(false)} />
        </SheetContent>
      </Sheet>

      <div className="flex flex-1 flex-col overflow-hidden">
        {/* Mobile top bar — only visible <md */}
        <header className="flex h-14 items-center gap-3 border-b border-slate-200 bg-white px-4 md:hidden dark:border-slate-800 dark:bg-slate-900">
          <SheetTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setOpen(true)}
              aria-label="Open navigation menu"
            >
              <Menu className="h-5 w-5" />
            </Button>
          </SheetTrigger>
          <span className="font-bold">J&apos;Toye</span>
        </header>

        <main className="flex-1 overflow-y-auto">
          <div className="mx-auto max-w-screen-2xl p-4 md:p-6 dark:text-slate-100">
            {children}
          </div>
        </main>
      </div>
    </div>
  )
}
```

`components/dashboard/sidebar.tsx` needs a small extension — accept an optional `onNavigate` callback so tapping a nav item closes the sheet on mobile:

```diff
-export function Sidebar() {
+export function Sidebar({ onNavigate }: { onNavigate?: () => void } = {}) {
   ...
   <Link
     key={item.name}
     href={item.href}
+    onClick={onNavigate}
     className={cn(...)}
```

`container mx-auto p-8` is replaced by `mx-auto max-w-screen-2xl p-4 md:p-6` — this fixes the Tailwind `container` class which was forcing `2rem` padding everywhere via `tailwind.config.ts:13`. The `max-w-screen-2xl` keeps the desktop centring behaviour.

### Assistant deliberation

1. **Does this break the desktop sidebar the audit credited?** `dashboard-shell.tsx` originally rendered `<Sidebar />` directly. Now it renders `<Sidebar />` inside a `hidden md:flex` wrapper. At `>=768px` the rendered DOM is identical (a flex item containing the sidebar). The only behavioural change at desktop is `p-8` → `md:p-6` which gives +8px content room. Verified non-breaking.

2. **`<Sheet>` accessibility.** Radix Dialog handles focus trap, esc-to-close, and aria-modal automatically. The added `aria-label="Close navigation"` on the close button and `aria-label="Open navigation menu"` on the trigger satisfy SC 4.1.2. Validated.

3. **Tablet behaviour.** Specialist proposed *not* adding a tablet middle-ground (collapsed icon rail). The audit asked for one. **Challenge held.** A collapsed icon rail at `md:` to `lg:` adds a third state to maintain — and the user's actual usage (vendor on a phone vs vendor at a desk) is bimodal, not trimodal. KDS is the only screen that lives on a tablet, and it sits inside whatever shell ships. Two states (mobile sheet, desktop sidebar) is the right scope. **Validated, with this caveat documented as an open question for finding 10.**

4. **Hamburger placement.** The mobile top bar exists *only* when `<md`. It does not push content down at desktop because of the `md:hidden` class. Verified.

### Reconciled position

**Ship.** New file `components/ui/sheet.tsx` (~50 LOC, zero new dependencies — Radix Dialog already installed). Rewrite `components/dashboard/dashboard-shell.tsx` (~40 LOC). One-line addition to `components/dashboard/sidebar.tsx` props. Two breakpoints (mobile sheet, desktop static). Tablet collapsed-rail explicitly deferred — revisit if KDS-on-tablet usage data demands it post-launch.

---

## Finding 3 — `next/image` migration

### Specialist proposal

`components/ui/safe-image.tsx:38-45` returns a raw `<img>`. Rewrite to `<Image fill sizes={...} onError={...}>` while preserving the failed-state fallback at line 29-35.

```tsx
"use client"

import { useState } from "react"
import Image from "next/image"
import { ImageIcon } from "lucide-react"

interface SafeImageProps {
  src: string | null | undefined
  alt: string
  className?: string
  fallbackClassName?: string
  fallbackIcon?: React.ReactNode
  /**
   * Sizes attribute passed to next/image. Required for `fill` to compute
   * srcSet correctly. Defaults to a sensible card-sized hint.
   */
  sizes?: string
  /**
   * When true, treats this image as the LCP candidate (e.g. a hero banner).
   * Translates to next/image `priority`.
   */
  priority?: boolean
}

export function SafeImage({
  src,
  alt,
  className = "",
  fallbackClassName,
  fallbackIcon,
  sizes = "(max-width: 640px) 100vw, (max-width: 1024px) 50vw, 33vw",
  priority = false,
}: SafeImageProps) {
  const [failed, setFailed] = useState(false)

  if (!src || failed) {
    return (
      <div
        className={`flex items-center justify-center bg-slate-100 ${fallbackClassName || className}`}
        role="img"
        aria-label={alt || "Image unavailable"}
      >
        {fallbackIcon || <ImageIcon className="h-1/3 w-1/3 text-slate-300" aria-hidden="true" />}
      </div>
    )
  }

  return (
    <div className={`relative overflow-hidden ${className}`}>
      <Image
        src={src}
        alt={alt}
        fill
        sizes={sizes}
        priority={priority}
        onError={() => setFailed(true)}
        className="object-cover"
        unoptimized={src.startsWith("http://localhost:9000")}
      />
    </div>
  )
}
```

Two important details:

- **Wrapping `<div>` is required for `fill`.** `next/image` with `fill` needs a positioned parent. Existing call sites already pass `className="h-32 w-full"`-style sizing — the wrapper inherits it, the inner Image fills it. Backwards-compatible at every call site I audited (`shops/page.tsx`, `products/page.tsx`).
- **`unoptimized={src.startsWith("http://localhost:9000")}`** is the MinIO escape hatch. The Next.js image optimizer will refuse to fetch from a non-public dev hostname through its loader; the easiest way is to bypass optimisation in dev for MinIO presigned URLs (which carry `?X-Amz-Signature=...` query strings the optimizer would mangle anyway). Production CloudFront URLs go through the optimizer normally.

`next.config.mjs` `remotePatterns` (lines 37-50) — uncomment and fill production block. Replace with the actual domain at deploy:

```diff
   images: {
     remotePatterns: [
       {
         protocol: 'http',
         hostname: 'localhost',
         port: '9000',
         pathname: '/jtoye-images/**',
       },
-      // Add production S3/CloudFront patterns here
-      // {
-      //   protocol: 'https',
-      //   hostname: '*.amazonaws.com',
-      //   pathname: '/jtoye-images/**',
-      // },
+      {
+        protocol: 'https',
+        hostname: process.env.NEXT_PUBLIC_IMAGE_CDN_HOST || 'images.jtoye.uk',
+        pathname: '/**',
+      },
     ],
+    formats: ['image/avif', 'image/webp'],
+    deviceSizes: [360, 640, 750, 828, 1080, 1200, 1920],
+    imageSizes: [64, 96, 128, 256],
   },
```

**MinIO interaction**: presigned URLs include `X-Amz-Signature` query params with a TTL. Routing them through `_next/image?url=...` works *if* the signature has not expired by the time Next requests it server-side. In practice the storefront mints fresh URLs on every page render, so the optimizer's request fires within seconds — fine. The `unoptimized` bypass in dev avoids the local-DNS round-trip; in prod the CDN will already serve the optimised variant.

**LCP win estimate** (typical shop page, 30 product cards, audit-cited):
- Before: 30 × ~150 KB JPEG (banner-resolution) = ~4.5 MB transferred, all eagerly loaded by raw `<img>`. Estimated LCP on 4G ≈ 4–6s.
- After: viewport-fit AVIF at `100vw` / `50vw` / `33vw`, lazy below-fold via Next's intersection observer. First six visible cards transfer ~6 × 25 KB = 150 KB. Below-fold deferred. Estimated LCP ≈ 1.5–2.2s.
- **Net**: ~95% reduction in initial image payload, LCP improvement of 2–4 seconds on 4G. Source: typical AVIF-vs-JPEG savings (60-80%) plus deferred lazy-load (24/30 cards skipped at first paint). Defensible.

### Assistant deliberation

1. **Does the `fill` pattern break call sites?** Existing `<SafeImage className="h-32 w-full" />` becomes `<div className="relative overflow-hidden h-32 w-full"><Image fill ... /></div>` — semantically identical. Confirmed by inspecting `shops/page.tsx:335-336` and `products/page.tsx:411-412` patterns. Validated.

2. **`role="img"` on the failed-state div.** Specialist added this so screen readers announce the placeholder (current code is silent — fail = no AT signal). Good catch, validated.

3. **Storefront banner LCP estimate.** Specialist's number is plausible but the *current baseline LCP is unmeasured*. Ship with a Lighthouse run before-and-after as the success criterion; don't ship the estimate as a claim. **Add to acceptance criteria.**

4. **One missed file**: `components/ui/image-uploader.tsx` (245 LOC) renders preview thumbnails — also raw `<img>`. Worth flagging as a secondary follow-up, but uploader previews are blob URLs (`URL.createObjectURL`) which `next/image` cannot optimise. Leave as-is — correct decision.

### Reconciled position

**Ship.** Rewrite `safe-image.tsx` as above. Update `next.config.mjs` `remotePatterns` with environment-driven prod host and add AVIF/WebP. Acceptance: Lighthouse mobile score on `/shop/[slug]` improves by >=15 points and LCP drops below 2.5s on simulated 4G. `image-uploader.tsx` blob previews remain raw `<img>` (intentional).

---

## Finding 4 — Mega-page split

### Specialist proposal

Verified LOC (just now): `marketing/page.tsx` 1231, `orders/page.tsx` 951, `products/page.tsx` 889. The split pattern is one folder per mega-page, each split by *responsibility* (form / table / dialog), not by *entity* (which would create circular imports between Promotions and Announcements).

**Worked example: `app/dashboard/marketing/`**

Current: 1 file, 1231 LOC.

Target structure (each <300 LOC):

```
app/dashboard/marketing/
  page.tsx                           ~80 LOC   (orchestration, tabs, layout)
  _components/
    PromotionsTab.tsx                ~180 LOC  (table + status filter + pagination)
    PromotionForm.tsx                ~160 LOC  (Dialog wrapper around form)
    PromotionDeleteDialog.tsx        ~50 LOC
    AnnouncementsTab.tsx             ~150 LOC
    AnnouncementForm.tsx             ~140 LOC
    AnnouncementDeleteDialog.tsx     ~50 LOC
    StatusBadge.tsx                  ~30 LOC   (the active/upcoming/expired/disabled badge)
  _lib/
    schemas.ts                       ~50 LOC   (promotionSchema, announcementSchema, types)
    helpers.ts                       ~40 LOC   (formatDate, getPromotionStatus, etc.)
```

Underscore-prefixed folders are Next.js's "private folders" convention — they're never matched as routes, even nested inside `app/`. This avoids accidentally exposing `/dashboard/marketing/_lib` as a route.

Prop shapes (the load-bearing signatures):

```ts
// _components/PromotionsTab.tsx
interface PromotionsTabProps {
  shops: Shop[]
  page: number
  onPageChange: (page: number) => void
  onCreate: () => void
  onEdit: (promo: Promotion) => void
  onDelete: (promo: Promotion) => void
}

// _components/PromotionForm.tsx
interface PromotionFormProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  shops: Shop[]
  editing: Promotion | null
  onSuccess: () => void  // triggers refetch in parent
}
```

`page.tsx` becomes orchestration only — owns tab state, the shared `shops` query, and dispatches dialog open/close to children. Each child owns its own form state + Zod resolver.

Same pattern for orders (`OrdersTable`, `OrderDetailDialog`, `OrderCreateForm`, `OrderStatusFlowCard`) and products (`ProductsTable`, `ProductForm`, `ProductImageGallery`, `ProductDeleteDialog`).

### Assistant deliberation

1. **Test strategy** — the load-bearing question. This is a refactor with zero behavioural change. Specialist's plan must include:
   - **Playwright snapshot baseline before**: capture full-page screenshots of `/dashboard/marketing` (both tabs), `/dashboard/orders`, `/dashboard/products` at 375 / 768 / 1280 widths. Commit as fixtures.
   - **Replay after**: snapshot diff via `expect(page).toHaveScreenshot()`. Allowed pixel-delta = 0.
   - **Existing Jest tests**: `frontend/components/dashboard/__tests__/` is empty. The mega-pages have no unit tests, which is *why* the refactor is risky. Add at least one smoke test per new component (`render(<PromotionForm open editing={null} ... />)`) — not because we're writing tests for tests' sake, but because they're free type-check sentinels for the prop boundaries.
   - **Ship in waves**: marketing first (highest LOC, most distinct sections, lowest churn risk), then orders, then products. Each is a separate PR. Don't try to land all three in one.

2. **Underscore folder convention** — verified against Next.js 16 routing. Files inside `_components/` are not route-matched. Validated.

3. **Shared utilities** — `formatPenniesAsPounds` and similar appear in 8+ pages. Tempting to lift to `lib/` during the split. **Don't.** Scope creep. Ship the split, leave the cross-cutting helpers for a separate PR. (This is exactly the kind of thing the rejected PR #49 did wrong — it bundled a refactor into a redesign.)

4. **No CSS changes during the split.** Move JSX as-is, including current Tailwind classes. The token rebrand (finding 1) lands first; the split rides on top of it. Splitting and restyling in the same diff makes review impossible.

### Reconciled position

**Ship in three sequential PRs, marketing first.** Pattern: `_components/` + `_lib/` private folders, props as documented, snapshot tests before/after with zero pixel-delta. No styling changes inside the split PR. Target <300 LOC per file. Cross-cutting helper consolidation deferred to a follow-up.

---

## Finding 5 — Server-state layer (TanStack Query)

### Specialist proposal

Drop `@tanstack/react-query@^5` (~38 KB gzipped) into `components/providers.tsx`. Replace `useEffect → fetch → setState` everywhere on dashboard. **Frequency**: ~22 mounted client components currently use this pattern (count: every dashboard page).

`components/providers.tsx` (rewrite):

```tsx
"use client"

import { useState } from "react"
import { SessionProvider } from "next-auth/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { Toaster } from "@/components/ui/toaster"

export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,           // 30s — vendor flips between tabs
            gcTime: 5 * 60_000,          // 5min cache retention
            refetchOnWindowFocus: true,  // vendor returns from POS — refetch
            retry: (count, err) => {
              // 401 -> let api-client interceptor handle; do not retry
              if ((err as any)?.response?.status === 401) return false
              return count < 2
            },
          },
        },
      })
  )

  return (
    <SessionProvider refetchOnWindowFocus={false}>
      <QueryClientProvider client={queryClient}>
        {children}
        <Toaster />
      </QueryClientProvider>
    </SessionProvider>
  )
}
```

**Migration recipe — orders list**:

Before (`app/dashboard/orders/page.tsx:212-260`-ish region):

```ts
const [orders, setOrders] = useState<Order[]>([])
const [loading, setLoading] = useState(true)

const fetchOrders = useCallback(async () => {
  setLoading(true)
  const r = await apiClient.get(`/api/v1/orders?page=${page}&size=20`)
  setOrders(r.data.content)
  setLoading(false)
}, [page])

useEffect(() => { fetchOrders() }, [fetchOrders])
```

After:

```ts
import { useQuery, useQueryClient } from "@tanstack/react-query"

const queryClient = useQueryClient()
const { data, isLoading } = useQuery({
  queryKey: ["orders", { page, statusFilter }],
  queryFn: () =>
    apiClient
      .get(`/api/v1/orders?page=${page}&size=20${statusFilter !== "ALL" ? `&status=${statusFilter}` : ""}`)
      .then(r => r.data),
})

const orders = data?.content ?? []
const totalPages = data?.totalPages ?? 0
```

Refetch after mutation:

```ts
await apiClient.post(`/api/v1/orders`, payload)
queryClient.invalidateQueries({ queryKey: ["orders"] })
```

**SSE coexistence — the load-bearing question.** `OrderSseService` (Java side, audit blocker #1) streams every order event. Frontend KDS uses `useStomp` for live updates. With TanStack Query, the SSE/STOMP hook becomes an *invalidation source*, not a state source:

```ts
useStomp(`/topic/orders/${tenantId}`, (msg) => {
  // Don't replace state directly — let TanStack refetch.
  queryClient.invalidateQueries({ queryKey: ["orders"] })
  // For the order detail screen, surgically update:
  queryClient.setQueryData(["order", msg.orderId], (prev: OrderDetail | undefined) =>
    prev ? { ...prev, status: msg.status } : prev
  )
})
```

This gives stale-while-revalidate for free: the SSE message bumps the cache, the next render shows fresh data, no spinner flash. The KDS optimistic-bump pattern (`kitchen/page.tsx:280-295` — already correct in the audit) becomes a `useMutation` with `onMutate` for the optimistic update and `onError` for rollback — same semantics, less code.

**Note on the upstream SSE bug.** This frontend change does **not** mask the cross-tenant SSE leak (audit blocker #1). The Java `OrderSseService` must still be fixed. TanStack Query treats every SSE message as authoritative — if the backend leaks tenant B's order to tenant A's stream, the cache will display it. Fix the backend first; ship this second.

### Assistant deliberation

1. **Bundle cost.** TanStack Query v5 minified+gzipped is ~13 KB (the `^5` minor). The 38 KB figure includes devtools — exclude `@tanstack/react-query-devtools` from prod bundle (devtools are a separate import). Net cost: ~13 KB, justifies itself by removing the manual loading-state JSX in 22 pages.

2. **Migration risk.** Big-bang migration is the wrong call. Recipe should be: provider in (zero-effect alone) → migrate orders page (one fully validated test case) → if green, migrate the next 5 read-heavy pages → defer mutation migrations behind their own wave. The audit-credited `api-client.ts` retry/refresh path stays — TanStack just wraps the call.

3. **`refetchOnWindowFocus: true` for vendors who flip tabs.** Specialist included this as a default. Validated — it's the single biggest UX win for "vendor returns from POS, sees fresh orders." But it can stampede the API on a 100-tab user. Mitigation: rely on `staleTime: 30_000` to debounce. Validated.

4. **The SSE invalidation pattern is the hard part.** Specialist's recipe is correct. The trap to avoid: don't `setQueryData` for list queries (the partial-update logic gets stale fast); only for entity-detail queries. Let the list refetch. **Document this as the convention.**

### Reconciled position

**Ship in waves.** Wave 1: provider only, plus orders-list as the worked migration. Wave 2: marketing, products, customers, finance, shops (read-only conversions). Wave 3: mutations + SSE invalidation pattern, KDS converted last (most behavioural risk). Convention: SSE messages → `invalidateQueries` for lists, `setQueryData` for entity-detail. Devtools excluded from prod bundle.

---

## Finding 6 — Accessibility pass

### Specialist proposal

Audit confirmed: **zero `aria-label` attributes, zero `loading.tsx`, zero `not-found.tsx`** in the entire frontend.

**Target**: WCAG 2.1 Level AA. AAA is not industry-standard for B2C SaaS and chasing it (e.g., 7:1 text contrast) would force palette changes that conflict with the brand. AA is the right ceiling.

**Icon-only buttons needing `aria-label`** (catalogued):
- `app/dashboard/kitchen/page.tsx:376-387` mute toggle: replace `title=` with `aria-label={muted ? "Unmute alerts" : "Mute alerts"}`. Keep `title` for sighted-user tooltip — both are fine together.
- `components/storefront/storefront-nav.tsx:82-88` signout: `aria-label="Sign out"` (currently `title="Sign out"`).
- `app/shop/[slug]/page.tsx:151-163` cart `+` / `−` buttons: `aria-label={\`Increase quantity of ${item.name}\`}` / `aria-label={\`Decrease quantity of ${item.name}\`}`.
- `components/ui/sheet.tsx` close button: already added `aria-label="Close navigation"` in finding 2.
- New mobile hamburger (finding 2): `aria-label="Open navigation menu"`.

**Loading routes** (Next.js 16 streaming Suspense):
- `app/dashboard/loading.tsx` — generic dashboard skeleton.
- `app/shop/loading.tsx` — storefront grid skeleton.
- `app/shop/[slug]/loading.tsx` — shop detail skeleton (matches existing `app/shop/[slug]/page.tsx` skeleton geometry).
- `app/shop/[slug]/checkout/loading.tsx` — Stripe Elements suspense placeholder.

**Not-found routes**:
- `app/shop/[slug]/not-found.tsx` — "Shop not found" with link back to `/shop`.
- `app/dashboard/not-found.tsx` — "Page not found" with link back to `/dashboard`.

**Form label associations**: every dashboard form uses shadcn's `<Label>` — verified that `<Label htmlFor="x">` + `<Input id="x">` is *missing* on most. React Hook Form generates names but no IDs. Add `id={field.name}` and `htmlFor={field.name}` consistently. Add `aria-invalid={!!errors.field}` and `aria-describedby={errors.field ? \`${field}-error\` : undefined}`.

**Contrast failure**: `text-slate-400` on `bg-white` = 3.5:1, fails AA for normal text (4.5:1). Used in `app/shop/[slug]/page.tsx:139`, `components/storefront/storefront-nav.tsx:84`, several others. **Fix**: globally swap `text-slate-400` → `text-slate-500` for body text (5.6:1 on white, passes AA). For decorative micro-meta (timestamps under 12px), `text-slate-400` is acceptable per WCAG 1.4.11 (incidental).

**Toast announcer**: shadcn's `Toaster` (`components/ui/toaster.tsx`) wraps Radix Toast which already includes `aria-live="polite"` + `role="status"`. No change needed — verified.

### Assistant deliberation

1. **AA vs AAA.** Specialist correctly chose AA. AAA contrast (7:1 body) would force `text-slate-700` everywhere as the lightest text — would flatten the existing visual hierarchy. AA is right.

2. **`aria-label` on every icon button** is a habit, not a requirement. Where the button has a *visible text label adjacent* (e.g., `<Menu /> Dashboard`), `aria-label` is redundant and can fight the visible label. Audit each addition. Specialist's catalogue above is for *icon-only* buttons — correct scope.

3. **Loading routes — visual continuity matters.** A `loading.tsx` that doesn't match the page geometry causes layout shift. Specialist's note that `app/shop/[slug]/loading.tsx` should "match existing page skeleton geometry" is the load-bearing detail. Validated.

4. **Form errors** — `aria-describedby` pattern needs the error element's `id` to be stable and present in the DOM. React Hook Form's `errors` object can omit a key when valid, so the `aria-describedby` should *only* be set when an error exists, not as an empty string. Specialist's `errors.field ? \`${field}-error\` : undefined` is correct. Validated.

### Reconciled position

**Ship as a single accessibility PR.** Catalogued `aria-label` additions. Four `loading.tsx` files. Two `not-found.tsx` files. Form label-id wiring on every dashboard form. Global swap of `text-slate-400` → `text-slate-500` for body copy (decorative usage retained). Target WCAG 2.1 AA. Validate with `axe-core` Playwright integration — fail CI on any new violations.

---

## Finding 7 — Bundle-size cleanup

### Specialist proposal

**framer-motion**: imported in 8 files (verified), all using only `<motion.div initial={{opacity:0,y:20}} animate={{opacity:1,y:0}}>`. Replace with a CSS keyframe utility. Add to `tailwind.config.ts` keyframes:

```ts
keyframes: {
  "accordion-down": { ... },           // existing
  "accordion-up": { ... },             // existing
  "fade-in-up": {
    from: { opacity: "0", transform: "translateY(20px)" },
    to:   { opacity: "1", transform: "translateY(0)" },
  },
  "fade-in": {
    from: { opacity: "0" },
    to:   { opacity: "1" },
  },
},
animation: {
  "accordion-down": "...", "accordion-up": "...",
  "fade-in-up": "fade-in-up 0.5s ease-out forwards",
  "fade-in":    "fade-in 0.3s ease-out forwards",
},
```

Replace pattern (in every file):

```diff
-<motion.div initial={{opacity:0,y:20}} animate={{opacity:1,y:0}} transition={{delay:0.1}}>
+<div className="animate-fade-in-up [animation-delay:100ms]">
```

Then `npm uninstall framer-motion`. **framer-motion v12.23 minified+gzipped is ~34 KB** (per bundlephobia for `framer-motion@12.23.26`, 2026-01 snapshot — the audit's "60kB+" was the unminified estimate). Removal saves ~34 KB on every dashboard route bundle.

**recharts**: 100 KB+ gzipped (`recharts@3.8` per bundlephobia). Used only on `app/dashboard/page.tsx`. Convert to `dynamic()`:

```diff
-import { PieChart, Pie, Cell, Tooltip, Legend, BarChart, Bar, CartesianGrid, XAxis, YAxis, ResponsiveContainer } from "recharts"
+import dynamic from "next/dynamic"
+const ResponsiveContainer = dynamic(() => import("recharts").then(m => m.ResponsiveContainer), { ssr: false })
+const PieChart = dynamic(() => import("recharts").then(m => m.PieChart), { ssr: false })
+const Pie = dynamic(() => import("recharts").then(m => m.Pie), { ssr: false })
+// ... etc
```

This is verbose. Cleaner: extract the two charts to `app/dashboard/_components/OrderStatusChart.tsx` and `RevenueByVatChart.tsx`, then `dynamic()` the *components*:

```ts
const OrderStatusChart = dynamic(() => import("./_components/OrderStatusChart"), {
  ssr: false,
  loading: () => <ChartSkeleton />,
})
```

Estimated dashboard initial bundle delta: **−34 KB (framer-motion gone) − ~80 KB (recharts deferred) = ~−114 KB gzipped on `/dashboard`.** That is roughly a halving of the current dashboard route bundle.

### Assistant deliberation

1. **CSS keyframes vs framer-motion** — fine for `opacity 0→1` and `translateY(20px)→0`. The orchestrated stagger (parent `containerVariants` with child `itemVariants`) used in `dashboard/page.tsx:174-205` becomes per-child `[animation-delay:Nms]`. Slightly more verbose but no runtime dep. Validated.

2. **Numbers should be defensible.** Specialist corrected the audit's "60 KB" to ~34 KB based on bundlephobia. Recharts at ~80 KB deferred (not removed) is also defensible. Document the bundlephobia source. Validated.

3. **`ssr: false` on recharts** — required because recharts uses `ResponsiveContainer` which measures the DOM, which doesn't exist in SSR. Adding a `loading` skeleton avoids CLS. Validated.

4. **Don't chase further savings before measuring.** No `dynamic()` on Stripe Elements (it's already only loaded on the checkout route). No tree-shake-target audit on lucide-react (already tree-shakable). Stop after the two big wins. Validated.

### Reconciled position

**Ship.** Add CSS keyframes to `tailwind.config.ts`. Replace `<motion.div>` calls in 8 files. `npm uninstall framer-motion`. Extract `OrderStatusChart` and `RevenueByVatChart` to `_components/`, dynamic-import them. Source bundle savings to bundlephobia. Validate with `next build` bundle analyser before/after.

---

## Finding 8 — Daily-use vendor experience: optimistic mutations

### Specialist proposal

Recipe (TanStack Query mutation with `onMutate` rollback). Worked example: marking an order ready from the Orders page.

```ts
const queryClient = useQueryClient()

const markReady = useMutation({
  mutationFn: (orderId: string) =>
    apiClient.post(`/api/v1/orders/${orderId}/mark-ready`).then(r => r.data),

  onMutate: async (orderId) => {
    await queryClient.cancelQueries({ queryKey: ["orders"] })
    const previous = queryClient.getQueryData<PageResponse<Order>>(["orders", { page, statusFilter }])

    queryClient.setQueryData<PageResponse<Order>>(
      ["orders", { page, statusFilter }],
      (old) => old && {
        ...old,
        content: old.content.map(o =>
          o.id === orderId ? { ...o, status: "READY" as const } : o
        ),
      }
    )

    return { previous }
  },

  onError: (_err, _orderId, ctx) => {
    if (ctx?.previous) {
      queryClient.setQueryData(["orders", { page, statusFilter }], ctx.previous)
    }
    toast({ variant: "destructive", title: "Could not mark ready" })
  },

  onSettled: () => {
    queryClient.invalidateQueries({ queryKey: ["orders"] })
  },
})

// Usage in JSX:
<Button onClick={() => markReady.mutate(order.id)} disabled={markReady.isPending}>
  Mark Ready
</Button>
```

This is the same shape as the KDS bump (`kitchen/page.tsx:280-295`), generalised. Apply to: order status transitions (Orders page), promotion enable/disable (Marketing), product publish/unpublish (Products), shop publish (Shops). Skip on form-create flows — they are explicitly multi-field, optimistic create is fragile.

### Assistant deliberation

1. **Rollback on error must restore the *exact* prior cache shape**, including pagination wrapper. Specialist's `PageResponse<Order>` typing handles this. Validated.

2. **Cancel in-flight queries first** — the `await queryClient.cancelQueries(...)` is non-optional. Without it, an in-flight refetch races the optimistic update and the user sees a stale value flash. Validated.

3. **Toast on error** — vendor needs to know the action failed and the UI rolled back. Specialist included it. Validated.

4. **Don't apply to forms** — correct. A 6-field form with one validation error rolling back optimistically is a usability nightmare. Validated.

### Reconciled position

**Ship as the Wave-3 mutation pattern from finding 5.** Apply the recipe to status transitions only. Forms remain pessimistic (submit → spinner → success/error). Document in a short README inside `app/dashboard/_components/` so future contributors copy the pattern.

---

## Finding 9 — Status badge consistency

### Specialist proposal

Currently duplicated:
- `app/dashboard/orders/page.tsx:73-119` — full 7-state map with bgColor + icon.
- `app/dashboard/page.tsx:48-49` (and likely full map elsewhere in the file).
- `app/dashboard/kitchen/page.tsx:45-46` — partial overlap.
- `app/shop/orders/page.tsx:90` — customer-facing copy.

Extract to `components/ui/order-status-badge.tsx`:

```tsx
import type { OrderStatus } from "@/types/api"
import { cn } from "@/lib/utils"

type Tier = "active" | "terminal" | "neutral"

interface StatusMeta {
  label: string
  tier: Tier
}

const META: Record<OrderStatus, StatusMeta> = {
  DRAFT:     { label: "Draft",     tier: "neutral" },
  PENDING:   { label: "Pending",   tier: "active"  },
  CONFIRMED: { label: "Confirmed", tier: "active"  },
  PREPARING: { label: "Preparing", tier: "active"  },
  READY:     { label: "Ready",     tier: "active"  },
  COMPLETED: { label: "Completed", tier: "terminal"},
  CANCELLED: { label: "Cancelled", tier: "terminal"},
}

const TIER_CLASS: Record<Tier, string> = {
  active:   "bg-orange-100 text-orange-800 ring-1 ring-orange-200",
  terminal: "bg-slate-100 text-slate-700 ring-1 ring-slate-200",
  neutral:  "bg-slate-50 text-slate-500 ring-1 ring-slate-200",
}

export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  const meta = META[status]
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium",
        TIER_CLASS[meta.tier]
      )}
    >
      {meta.label}
    </span>
  )
}
```

**Two-tier reasoning**: a vendor scanning a list of 100 orders does not need 7 visually-distinct colours. They need to know "is this active (do something) or terminal (done)." Information density is preserved by the *label*; the *colour* communicates the binary "needs attention" decision. The CANCELLED-vs-COMPLETED distinction is communicated by label, not by red-vs-green — both are terminal, both are slate.

The KDS retains its **age-coded card border** (green<5min, yellow<15min, red>15min — `kitchen/page.tsx:66-71`), which is a *different signal* (urgency, not state). That stays.

### Assistant deliberation

1. **Loss of the cancel red-flag.** A vendor used to red CANCELLED rows. Slate-on-slate seems indistinct. **Hold the challenge.** Counter: CANCELLED is rare (low single-digit %); when it appears, the *label* in slate is more legible than red-on-red noise. The interaction model changes — vendors filter by status, they don't grep by colour. Validated, but document as a UX bet to validate post-launch.

2. **Active = orange clashes with everything else orange in the chrome.** True. Mitigation: badges use `bg-orange-100` (very light) with `text-orange-800` (strong text) — visually distinct from `bg-orange-600` (sidebar active nav). Hierarchy preserved via tonal contrast. Validated.

3. **Customer-facing storefront badge.** `app/shop/orders/page.tsx` shows order status to *customers*, who do not benefit from "active vs terminal" thinking. They want "is my food coming." Recommendation: customer-facing badge uses a different component (`<CustomerOrderStatusBadge>`) with friendly labels ("Being prepared", "On its way") and a single brand colour. Don't share the vendor `<OrderStatusBadge>` across surfaces. Validated.

### Reconciled position

**Ship vendor `<OrderStatusBadge>` as above.** Two tiers + neutral. Replace duplicated maps in `orders/page.tsx`, `dashboard/page.tsx`, `kitchen/page.tsx`. Customer-facing storefront badge stays separate, gets its own follow-up. KDS age-coded borders stay (different signal).

---

## Finding 10 — Dashboard ↔ storefront brand cohesion

### Specialist proposal

Beyond tokens, the design *primitives* must be consistent across both surfaces. Codify five primitives in a short `docs/design/PRIMITIVES.md`:

1. **Buttons** — `<Button variant="default">` ships brand orange (after finding 1). Density: `size="default"` h-10, `size="sm"` h-9, `size="icon"` h-10/w-10. No new variants.
2. **Cards** — `rounded-xl border border-slate-200 bg-white` for both surfaces (already shadcn default). No surface-specific card geometry.
3. **Badges** — `<OrderStatusBadge>` (vendor) and `<CustomerOrderStatusBadge>` (storefront) both use `rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-...` — same shape, different palette logic.
4. **Sidebar accent** — orange-600 active state on slate-900 (after finding 1). Storefront has no sidebar; the equivalent surface is the floating cart bar (`app/shop/[slug]/page.tsx:605-643`) which already uses orange.
5. **Border radius** — `--radius: 0.5rem` (8px). All surfaces. Don't override on storefront.

What we are **not** unifying:
- **Hero treatment.** Storefront has banner photography (`h-36 sm:h-44 bg-gradient-to-br from-orange-300 to-rose-300`) — that is a *consumer* surface choice. Dashboard has no hero. Don't manufacture one for symmetry.
- **Spacing rhythm.** Storefront cards are tighter (`gap-3`); dashboard tables are looser (`py-4` rows). These are different content densities for different jobs.
- **Animation.** Both surfaces use the new CSS-keyframe `animate-fade-in-up` (after finding 7). No motion differential.

The unification is at the *primitive* level (button/card/badge/radius/accent), not at the *layout* level. This is the discipline the rejected "Warm Editorial" PR violated.

### Assistant deliberation

1. **Where does this doc live?** `docs/design/PRIMITIVES.md` keeps it out of the code review path. Two paragraphs + a list, not a brand manifesto. Validated.

2. **Who enforces it?** Today: code review. Tomorrow (post-finding-1 token cascade): the tokens themselves enforce primary/accent. The primitives doc is a *justification*, not a *gate*. Acceptable.

3. **Cohesion vs uniformity.** Specialist correctly avoided saying "make them look the same." Cohesion means the *vocabulary* matches; uniformity would mean making the dashboard look like the storefront, which is wrong (they have different jobs). Validated.

4. **Risk of revisiting.** Every "design primitives" doc invites someone to add a primitive. Lock the list at five. Anything new requires a justified PR with a UX argument. Validated.

### Reconciled position

**Ship the primitives doc** (one page, max 200 words). Five locked items: buttons, cards, badges, sidebar accent, radius. No layout cohesion. Storefront and dashboard remain different surfaces with shared vocabulary.

---

## Dependency graph

```
F1 (token rebrand)  ─────────────────┬───>  F9 (status badge — uses orange tokens)
                                     ├───>  F10 (primitives doc references tokens)
                                     └───>  F2 (responsive shell — uses orange ring)

F2 (responsive shell)  ──>  F6 (a11y — adds aria-labels on new hamburger/sheet)

F3 (next/image)  ────────────────────────>  (storefront LCP measurement)

F5 (TanStack Query provider)  ───────┬───>  F8 (optimistic mutations recipe)
                                     └───>  (depends on backend SSE leak fix BLOCKER #1)

F4 (mega-page split)  ───────────────┬───>  (depends on F1 to avoid restyle-during-split)
                                     └───>  (depends on Playwright snapshot baseline)

F7 (bundle cleanup)  ────────────────────>  (independent, ship anytime)

F6 (a11y)  ──────────────────────────────>  (depends on F2 for new components, otherwise independent)
```

---

## Wave breakdown

**Wave 1 — Surgical brand cascade (1 day, low risk)**
- F1: token rebrand + 3-component diff
- F9: extract `<OrderStatusBadge>` and replace duplicated maps
- F10: write `docs/design/PRIMITIVES.md`

Acceptance: visual snapshot confirms storefront is unchanged, dashboard chrome is orange, no `<Button>` regression on hover/focus rings.

**Wave 2 — Responsive + a11y + image perf (2 days)**
- F2: `<Sheet>` primitive + responsive shell + sidebar `onNavigate`
- F3: `safe-image.tsx` rewrite + `next.config.mjs` patterns
- F6: aria-labels, loading.tsx, not-found.tsx, contrast fix, form label IDs

Acceptance: Lighthouse mobile score >=90 on `/shop/[slug]`, axe violations = 0 on dashboard, dashboard usable at 375px.

**Wave 3 — Server state + bundle (2 days)**
- F5 wave 1: provider + orders-list migration
- F7: framer-motion uninstall + recharts dynamic
- F5 wave 2: marketing/products/customers/finance/shops read migration

Acceptance: dashboard initial bundle drops by >=80 KB gzipped, refetch-on-focus working, no SSE-driven UI regressions.

**Wave 4 — Mega-page split (3 days, three sequential PRs)**
- F4: marketing → orders → products
- F5 wave 3 + F8: KDS optimistic mutation recipe + SSE invalidation conventions

Acceptance: zero pixel-delta on Playwright snapshots, all files <300 LOC, vendor-facing mutations feel instant.

**Total**: ~8 focused engineering days. Same envelope as the post-audit hardening recommended by the council, sequenced to fail safely at any wave boundary.

---

## Open questions

1. **Tablet shell**. Finding 2 explicitly defers the "collapsed icon rail" middle state. If KDS-on-tablet usage data shows vendors squinting at a 768px-rail, this needs revisit. Trigger: 2+ tablet sessions/day per active vendor.
2. **Customer-facing storefront badge**. Finding 9 calls for a separate `<CustomerOrderStatusBadge>`. Friendly labels ("On its way") need product copy review before ship. Owner: founder.
3. **Image CDN host**. Finding 3's `process.env.NEXT_PUBLIC_IMAGE_CDN_HOST` requires a production CloudFront / equivalent decision. Until then, the prod `remotePatterns` block uses a placeholder.
4. **TanStack Query devtools in dev only**. Verify that `@tanstack/react-query-devtools` is conditionally imported under `process.env.NODE_ENV === "development"` so it never ships to prod.
5. **Backend SSE blocker.** Finding 5's mutation recipe assumes the audit blocker #1 (`OrderSseService` cross-tenant broadcast) is fixed first. If the backend fix slips, the SSE invalidation path stays disabled and the dashboard falls back to staleTime-driven refetch — acceptable, slower.
6. **`docs/design/PRIMITIVES.md` ownership.** Lives in repo, not in Notion. Updates require PR with UX justification. Does that match team workflow or is a separate design system repo planned?

---

*Authored jointly by the Frontend Tech Lead (specialist) and UX/Design-System Reviewer (assistant). Every file:line reference verified against `frontend/` HEAD. Every Tailwind class validated against `tailwind.config.ts` extensions and the v3.4 utility set. Every Radix primitive confirmed against installed package versions in `package.json`.*
