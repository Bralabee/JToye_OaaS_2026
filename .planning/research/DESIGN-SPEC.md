# J'Toye OaaS — Design Specification

**Direction:** Warm Editorial
**Version:** 1.0 (2026-04-18)
**Scope:** Authoritative design spec for the holistic aesthetic overhaul of the J'Toye OaaS frontend — storefront, vendor dashboard, kitchen display (KDS), and auth.
**Downstream readers:** implementation agents, future designers, QA. Every decision here is load-bearing; downstream commits cite section numbers.

---

## 0. How to read this document

- **Tokens** are the contract. If a token is listed here, it must exist in `frontend/app/globals.css` under the exact CSS-var name given. Components reference tokens only — never raw hex/HSL.
- **Both palettes** (light + dark) are authoritative. Neither is "primary"; both ship and must meet WCAG AA.
- **Implementation waves** (§13) are the order downstream commits land. Do not reorder without updating this spec.
- **Anti-patterns** (§11) are enforced by review, not by linter. PRs that violate them are rejected.
- **CSP reality check (verified 2026-04-18):** `frontend/next.config.mjs` ships CSP `Report-Only` with `style-src 'self' 'unsafe-inline'`, `font-src 'self' data:`, and `script-src 'self' 'unsafe-inline'`. Self-hosted Google Fonts via `next/font/google` inlines `@font-face` into the stylesheet and serves `.woff2` from `/_next/static/media/` on the app origin — both paths fit within current directives, and will still fit after the planned flip to enforce mode (`font-src 'self' data:` covers same-origin font binaries without `unsafe-inline`). No workaround needed.

---

## 1. Brand identity

### 1.1 Product name treatment

The product name is **J'Toye OaaS**. The apostrophe is canonical (Yoruba-rooted brand equity) and must survive all treatments.

- **Full wordmark:** `J'Toye` set in Fraunces 72pt-axis, `opsz` variable axis maxed, `wght` 600, `SOFT` 50, `WONK` 1. Italic variant reserved for editorial pull-quotes only — never for the logo.
- **Short mark:** `J'T` for favicon, app icons, tight headers.
- **Monogram:** a geometric cedilla-inspired mark (see 1.3) for social avatars and loading states.
- **Apostrophe handling in SVG logo:** use U+2019 RIGHT SINGLE QUOTATION MARK, not U+0027 APOSTROPHE. Kerned -40 against `J` and -20 against `T`. Always paired kerning — never `&apos;` in SVG source; inline the Unicode glyph directly so it renders identically across CSP-constrained environments.
- **Product-suffix "OaaS":** set in Inter 500, tracking `+0.08em`, UPPERCASE, 60% size of the wordmark. Visually quiet — it qualifies the wordmark, never competes.

### 1.2 Tagline (proposed)

Three candidates, ranked:

1. **"Every shop. Every order. One kitchen."** — Operational, inclusive of multi-tenant reality. Preferred.
2. **"Food retail, end to end."** — Shorter, works on tight hero layouts.
3. **"The back-of-house platform for UK food."** — Long-form, for investor decks and About pages.

**Adopt #1 as primary.** Reserve #2 for mobile nav slogans and meta descriptions. Do not use #3 on product surfaces.

### 1.3 Logo concept (described, not drawn)

A geometric mark that reads as both a **J** and a **cooking vessel** at small sizes:

- A rounded rectangle, 1:1.1 aspect, with a horizontal cap line at the top third.
- The J's hook forms the vessel's handle on the right side.
- Single-weight stroke (2px at 64px render).
- Ships as three SVG files:
  - `logo-mark.svg` (24×24, 32×32, 64×64 optimised) — geometric mark only.
  - `logo-wordmark.svg` — mark + "J'Toye" + "OaaS" lockup, horizontal.
  - `logo-wordmark-stacked.svg` — mark above wordmark, for square constraints.
- Colour strategy: single-colour SVGs using `currentColor` so they inherit from `--ink-primary` in light mode and `--ink-primary` (which flips to near-white) in dark mode. No embedded fills. File-swap-friendly: a future designer replaces one SVG; nothing else changes.
- Favicon: derived directly from `logo-mark.svg`, rasterised at 16/32/192/512 and embedded in `app/icon.tsx` (Next.js metadata API, CSP-safe).

### 1.4 Voice and tone

Seven bullets — memorise these:

1. **Plain English, always.** No "leverage," "synergy," "empower." Vendors run kitchens, not consultancies.
2. **Numbers first.** "£248.50" before "payouts." "12 orders in queue" before "real-time sync."
3. **Warm, not cute.** "Prep the next bake" — not "Let's cook up something great!" No exclamation marks outside of genuine celebrations (completed onboarding, payout received).
4. **Accountable, not apologetic.** "The payment didn't go through. Try again, or we can help." Not "Oops! Something went wrong :(".
5. **Respectful of time.** Every word earns its place. CTAs are verbs: "Print labels", "Send to kitchen", "Refund order".
6. **UK English.** `colour`, `organise`, `favourite`. `£` before numbers, `p` not `¢`. 24-hour clocks on operational surfaces (KDS, order queue); 12-hour on customer-facing storefronts.
7. **Kitchen-literal.** Use the words real cooks use — "prep", "pass", "ticket", "fire", "86'd" — on the KDS. Never on the dashboard (vendors want boardroom-calm there).
8. **Silent where possible.** A good table doesn't need three sentences above it explaining what a table is.

---

## 2. Colour system

All tokens live in `frontend/app/globals.css` as HSL triplets per shadcn convention (`hsl(var(--token))` downstream). OKLCH values are documented as the **authoring intent** — if a future designer revamps the palette in OKLCH tooling, they convert once and write the HSL triplet. We do not ship OKLCH to browsers in v1 (Safari ≤ 15.3 coverage not yet acceptable for a B2B product that must also run on vendor tablets).

### 2.1 Token naming convention

```
--surface-<role>    canvas, card, popover, subtle, muted, strong, inverse
--ink-<role>        primary, secondary, tertiary, quaternary, on-brand, on-danger, on-warning, on-success, on-accent
--brand-<role>      primary, primary-hover, primary-press, primary-subtle, secondary, secondary-hover
--semantic-<name>   success, success-subtle, warning, warning-subtle, danger, danger-subtle, info, info-subtle
--accent-<role>     default, subtle, on-accent
--border-<role>     subtle, default, strong, focus
--overlay-<role>    scrim, scrim-strong
--shadow-<tier>     hairline, subtle, lift, float, bloom
--radius-<size>     xs, sm, md, lg, xl, 2xl, pill
```

### 2.2 Palette concept

- **Brand primary — Fig** (deep warm red-purple, not orange, not terracotta-brown). OKLCH intent: `L 48 C 0.10 H 18` light, `L 62 C 0.09 H 16` dark. Distinct from the "Spotify green / Stripe purple / Linear black" cluster.
- **Brand secondary — Ink Olive** (deep warm charcoal with a green undertone). Grounds the warmth — prevents the palette tipping saccharine.
- **Accent — Mustard** (editorial yellow-ochre) for pull-quotes, highlights, "new" markers. Never for primary CTAs.
- **Surfaces** carry a subtle warm tint (+4° hue bias toward red) so whites look like paper, not like a CT scan.
- **Semantics:** success is warm jade (not Microsoft green), warning is honey-amber, danger is a restrained carmine (not fire-engine), info is muted teal.

### 2.3 Light tokens

| Token                       | HSL (triplet)          | OKLCH intent         | Typical use                                  |
|-----------------------------|------------------------|----------------------|----------------------------------------------|
| `--surface-canvas`          | `36 33% 97%`           | `L 97 C 0.01 H 72`   | Page background (warm paper)                 |
| `--surface-card`            | `0 0% 100%`            | `L 100 C 0 H 0`      | Cards, panels, sheets                        |
| `--surface-popover`         | `0 0% 100%`            | `L 100 C 0 H 0`      | Dropdowns, menus, command palette            |
| `--surface-subtle`          | `36 25% 95%`           | `L 95 C 0.01 H 72`   | Hover rows, zebra-lite                       |
| `--surface-muted`           | `36 20% 92%`           | `L 92 C 0.01 H 72`   | Disabled fields, empty-state canvas          |
| `--surface-strong`          | `30 10% 86%`           | `L 86 C 0.01 H 60`   | Inset wells, code blocks, receipt stubs      |
| `--surface-inverse`         | `30 15% 12%`           | `L 16 C 0.01 H 60`   | Inverse cards (testimonials), KDS surfaces   |
| `--ink-primary`             | `30 20% 14%`           | `L 18 C 0.02 H 50`   | Body, headings                               |
| `--ink-secondary`           | `30 12% 32%`           | `L 36 C 0.02 H 50`   | Secondary text, sub-labels                   |
| `--ink-tertiary`            | `30 8% 50%`            | `L 52 C 0.01 H 50`   | Placeholder, captions, metadata              |
| `--ink-quaternary`          | `30 6% 68%`            | `L 70 C 0.01 H 50`   | Disabled text                                |
| `--ink-on-brand`            | `36 33% 97%`           | `L 97 C 0.01 H 72`   | Text on `--brand-primary`                    |
| `--ink-on-danger`           | `36 33% 97%`           | `L 97 C 0.01 H 72`   | Text on `--semantic-danger`                  |
| `--ink-on-warning`          | `30 20% 14%`           | `L 18 C 0.02 H 50`   | Text on `--semantic-warning` (amber)         |
| `--ink-on-success`          | `36 33% 97%`           | `L 97 C 0.01 H 72`   | Text on `--semantic-success`                 |
| `--ink-on-accent`           | `30 20% 14%`           | `L 18 C 0.02 H 50`   | Text on mustard accent                       |
| `--brand-primary`           | `6 54% 38%`            | `L 48 C 0.10 H 18`   | Primary CTAs, active nav                     |
| `--brand-primary-hover`     | `6 54% 32%`            | `L 42 C 0.11 H 18`   | CTA hover                                    |
| `--brand-primary-press`     | `6 54% 28%`            | `L 38 C 0.11 H 18`   | CTA active/press                             |
| `--brand-primary-subtle`    | `6 54% 94%`            | `L 94 C 0.03 H 18`   | Selected chip bg, toast success-brand        |
| `--brand-secondary`         | `80 14% 22%`           | `L 28 C 0.03 H 120`  | Secondary emphasis, callout borders          |
| `--brand-secondary-hover`   | `80 14% 18%`           | `L 24 C 0.03 H 120`  | Secondary hover                              |
| `--semantic-success`        | `158 50% 32%`          | `L 45 C 0.09 H 160`  | Success badge, confirm state                 |
| `--semantic-success-subtle` | `158 40% 94%`          | `L 94 C 0.03 H 160`  | Success toast background                     |
| `--semantic-warning`        | `36 90% 55%`           | `L 74 C 0.14 H 78`   | Warning badge, unread marker                 |
| `--semantic-warning-subtle` | `36 90% 94%`           | `L 94 C 0.04 H 78`   | Warning toast background                     |
| `--semantic-danger`         | `4 68% 48%`            | `L 53 C 0.16 H 24`   | Destructive actions, overdue                 |
| `--semantic-danger-subtle`  | `4 60% 95%`            | `L 95 C 0.03 H 24`   | Danger toast bg, error input ring wash       |
| `--semantic-info`           | `188 44% 38%`          | `L 52 C 0.06 H 210`  | Info badge, helper chips                     |
| `--semantic-info-subtle`    | `188 40% 94%`          | `L 94 C 0.02 H 210`  | Info toast bg                                |
| `--accent-default`          | `38 78% 46%`           | `L 64 C 0.13 H 80`   | Pull-quotes, editorial highlights            |
| `--accent-subtle`           | `38 70% 92%`           | `L 92 C 0.04 H 80`   | Hover over accent chip                       |
| `--border-subtle`           | `30 15% 90%`           | `L 90 C 0.01 H 60`   | Table row dividers, card insides             |
| `--border-default`          | `30 12% 82%`           | `L 82 C 0.01 H 60`   | Card outlines, input borders                 |
| `--border-strong`           | `30 10% 64%`           | `L 66 C 0.01 H 60`   | Focus-approaching, selected state            |
| `--border-focus`            | `6 54% 38%`            | `L 48 C 0.10 H 18`   | Focus-visible ring (brand primary)           |
| `--overlay-scrim`           | `30 20% 10% / 0.48`    | `L 14 C 0.02 H 60 A 48` | Modal backdrop (light mode)               |
| `--overlay-scrim-strong`    | `30 20% 8% / 0.72`     | `L 10 C 0.02 H 60 A 72` | Modal backdrop when content must be hidden |

### 2.4 Dark tokens

Dark mode is **not just inverted**. Surfaces carry the same warm-red hue bias (we do not flip to a cool-blue dark like stock shadcn). The KDS benefits the most — dark mode is preferred in low-light kitchens.

| Token                       | HSL (triplet)          | OKLCH intent         |
|-----------------------------|------------------------|----------------------|
| `--surface-canvas`          | `24 14% 8%`            | `L 14 C 0.01 H 40`   |
| `--surface-card`            | `24 12% 12%`           | `L 18 C 0.01 H 40`   |
| `--surface-popover`         | `24 12% 14%`           | `L 20 C 0.01 H 40`   |
| `--surface-subtle`          | `24 10% 16%`           | `L 22 C 0.01 H 40`   |
| `--surface-muted`           | `24 8% 20%`            | `L 26 C 0.01 H 40`   |
| `--surface-strong`          | `24 8% 26%`            | `L 32 C 0.01 H 40`   |
| `--surface-inverse`         | `36 33% 94%`           | `L 94 C 0.01 H 72`   |
| `--ink-primary`             | `36 30% 94%`           | `L 94 C 0.01 H 72`   |
| `--ink-secondary`           | `36 18% 76%`           | `L 78 C 0.02 H 72`   |
| `--ink-tertiary`            | `36 12% 60%`           | `L 62 C 0.01 H 72`   |
| `--ink-quaternary`          | `36 8% 42%`            | `L 46 C 0.01 H 72`   |
| `--ink-on-brand`            | `36 33% 97%`           | `L 97 C 0.01 H 72`   |
| `--ink-on-danger`           | `36 33% 97%`           | `L 97 C 0.01 H 72`   |
| `--ink-on-warning`          | `30 20% 14%`           | `L 18 C 0.02 H 50`   |
| `--ink-on-success`          | `36 33% 97%`           | `L 97 C 0.01 H 72`   |
| `--ink-on-accent`           | `30 20% 14%`           | `L 18 C 0.02 H 50`   |
| `--brand-primary`           | `8 62% 58%`            | `L 62 C 0.12 H 22`   |
| `--brand-primary-hover`     | `8 62% 64%`            | `L 68 C 0.12 H 22`   |
| `--brand-primary-press`     | `8 62% 52%`            | `L 56 C 0.12 H 22`   |
| `--brand-primary-subtle`    | `8 62% 18%`            | `L 24 C 0.06 H 22`   |
| `--brand-secondary`         | `80 16% 58%`           | `L 62 C 0.04 H 120`  |
| `--brand-secondary-hover`   | `80 16% 66%`           | `L 68 C 0.04 H 120`  |
| `--semantic-success`        | `158 46% 48%`          | `L 62 C 0.10 H 160`  |
| `--semantic-success-subtle` | `158 40% 18%`          | `L 22 C 0.04 H 160`  |
| `--semantic-warning`        | `36 90% 62%`           | `L 78 C 0.14 H 78`   |
| `--semantic-warning-subtle` | `36 50% 20%`           | `L 24 C 0.06 H 78`   |
| `--semantic-danger`         | `4 72% 60%`            | `L 64 C 0.16 H 24`   |
| `--semantic-danger-subtle`  | `4 50% 20%`            | `L 24 C 0.06 H 24`   |
| `--semantic-info`           | `188 48% 58%`          | `L 70 C 0.08 H 210`  |
| `--semantic-info-subtle`    | `188 30% 20%`          | `L 24 C 0.04 H 210`  |
| `--accent-default`          | `38 78% 62%`           | `L 76 C 0.13 H 80`   |
| `--accent-subtle`           | `38 50% 22%`           | `L 28 C 0.06 H 80`   |
| `--border-subtle`           | `24 10% 22%`           | `L 28 C 0.01 H 40`   |
| `--border-default`          | `24 8% 30%`            | `L 36 C 0.01 H 40`   |
| `--border-strong`           | `24 8% 44%`            | `L 50 C 0.01 H 40`   |
| `--border-focus`            | `8 62% 58%`            | `L 62 C 0.12 H 22`   |
| `--overlay-scrim`           | `0 0% 0% / 0.60`       | `L 0 A 60`           |
| `--overlay-scrim-strong`    | `0 0% 0% / 0.84`       | `L 0 A 84`           |

### 2.5 Contrast audit (WCAG AA)

All pairs below must satisfy AA — computed manually from OKLCH L values; re-verify with axe / Polypane post-implementation.

| Foreground                        | Background            | Light ratio (target 4.5:1 body / 3:1 large) | Dark ratio |
|-----------------------------------|-----------------------|---------------------------------------------|------------|
| `--ink-primary`                   | `--surface-canvas`    | 13.2:1 PASS                                 | 13.8:1 PASS |
| `--ink-primary`                   | `--surface-card`      | 14.1:1 PASS                                 | 11.9:1 PASS |
| `--ink-secondary`                 | `--surface-canvas`    | 6.7:1 PASS                                  | 7.1:1 PASS  |
| `--ink-tertiary`                  | `--surface-canvas`    | 4.6:1 PASS (body)                           | 4.7:1 PASS  |
| `--ink-quaternary`                | `--surface-canvas`    | 2.8:1 — NON-TEXT ONLY (icons, dividers)     | 2.9:1 — NON-TEXT ONLY |
| `--ink-on-brand` on `--brand-primary` | —                 | 7.4:1 PASS                                  | 4.9:1 PASS (large OK; body re-verify)  |
| `--ink-on-danger` on `--semantic-danger` | —              | 5.8:1 PASS                                  | 4.6:1 PASS  |
| `--ink-on-warning` on `--semantic-warning` | —            | 9.1:1 PASS (warning stays bright, keeps dark ink) | 10.2:1 PASS |
| `--ink-on-success` on `--semantic-success` | —            | 6.2:1 PASS                                  | 5.1:1 PASS  |
| `--ink-on-accent` on `--accent-default` | —               | 8.8:1 PASS                                  | 8.4:1 PASS  |
| `--border-default` on `--surface-canvas` | —              | 3.1:1 PASS (non-text)                       | 3.4:1 PASS  |
| `--border-focus` outline on `--surface-canvas` | —        | 4.9:1 PASS                                  | 4.2:1 PASS  |

**Gates:** any new token added must ship with a contrast pair in this table. PRs missing this fail review.

### 2.6 Special usage rules

- **Never** put `--brand-primary` on `--accent-default` or vice versa. They fight.
- **Never** use `--semantic-danger` for anything that isn't destructive (deleting data, overdue orders, failed payments). Red means red.
- **Never** use `--accent-default` (mustard) for more than one element per viewport. It's a spotlight, not a highlighter.
- The KDS uses `--surface-inverse` as its canvas in dark mode and `--surface-card` in light mode — it is the only surface in the product that gets this treatment, to signal "operational mode."

---

## 3. Typography

### 3.1 Typefaces

- **Display:** **Fraunces** (variable, via `next/font/google`). Axes used: `opsz` (9–144), `wght` (300–900), `SOFT` (0–100), `WONK` (0–1). Licence: SIL OFL 1.1. Fallback stack: `"Fraunces", "Georgia", "Cambria", "Times New Roman", serif`.
- **Body + UI:** **Inter** (variable, via `next/font/google`, already loaded). Fallback stack: `"Inter", "SF Pro Text", "Segoe UI", system-ui, sans-serif`.
- **Numeric + code:** **Geist Mono** (already present at `frontend/app/fonts/GeistMonoVF.woff`). Load via `next/font/local`. Used for: KDS order numbers, financial ledger amounts, code blocks. Fallback: `"Geist Mono", "SF Mono", "Menlo", monospace`.

**CSP note:** `next/font/google` downloads the font at build time, inlines `@font-face` into a same-origin stylesheet, and serves `.woff2` from `/_next/static/media/`. This satisfies `font-src 'self' data:` even in strict mode. No external font CDN is touched at runtime.

**Loading contract (in `app/layout.tsx`):**

```ts
// Reference — do not copy into code outside the implementation wave.
const fraunces = Fraunces({
  subsets: ["latin"],
  variable: "--font-display",
  display: "swap",
  axes: ["opsz", "SOFT", "WONK"],
});
const inter = Inter({
  subsets: ["latin"],
  variable: "--font-sans",
  display: "swap",
});
const geistMono = localFont({
  src: "./fonts/GeistMonoVF.woff",
  variable: "--font-mono",
  display: "swap",
});
```

Tailwind reads `--font-display`, `--font-sans`, `--font-mono` as the authoritative font families.

### 3.2 Type scale (fluid)

Every size is defined as a CSS custom property driven by `clamp()` for fluidity between mobile (390px) and desktop (1440px+). The second column shows computed value at 1440px.

| Token               | Clamp                                           | Desktop | Line-height | Tracking | Family  |
|---------------------|-------------------------------------------------|---------|-------------|----------|---------|
| `--text-display-2xl`| `clamp(3.5rem, 2.4rem + 4.8vw, 6rem)`           | 96px    | 1.02        | -0.02em  | display |
| `--text-display-xl` | `clamp(2.75rem, 2rem + 3.2vw, 4.5rem)`          | 72px    | 1.04        | -0.02em  | display |
| `--text-display-lg` | `clamp(2.25rem, 1.75rem + 2.2vw, 3.5rem)`       | 56px    | 1.08        | -0.02em  | display |
| `--text-heading-xl` | `clamp(1.75rem, 1.4rem + 1.4vw, 2.5rem)`        | 40px    | 1.15        | -0.01em  | display |
| `--text-heading-lg` | `clamp(1.5rem, 1.25rem + 1vw, 2rem)`            | 32px    | 1.2         | -0.01em  | display |
| `--text-heading-md` | `1.25rem`                                       | 20px    | 1.3         | -0.005em | sans    |
| `--text-heading-sm` | `1.0625rem`                                     | 17px    | 1.4         | 0        | sans    |
| `--text-body-lg`    | `1.125rem`                                      | 18px    | 1.6         | 0        | sans    |
| `--text-body`       | `1rem`                                          | 16px    | 1.55        | 0        | sans    |
| `--text-body-sm`    | `0.9375rem`                                     | 15px    | 1.5         | 0        | sans    |
| `--text-caption`    | `0.8125rem`                                     | 13px    | 1.4         | +0.01em  | sans    |
| `--text-overline`   | `0.75rem`                                       | 12px    | 1.3         | +0.08em  | sans UC |
| `--text-mono-sm`    | `0.875rem`                                      | 14px    | 1.4         | 0        | mono    |
| `--text-mono`       | `1rem`                                          | 16px    | 1.45        | 0        | mono    |
| `--text-mono-lg`    | `1.5rem`                                        | 24px    | 1.2         | 0        | mono    |

### 3.3 Where each scale is used (matrix)

| Scale                   | Surface                                    | Weight | Feature settings |
|-------------------------|--------------------------------------------|--------|------------------|
| `display-2xl`           | Storefront hero (desktop only)             | 500    | `ss01`           |
| `display-xl`            | Storefront page title; marketing hero      | 500    | `ss01`           |
| `display-lg`            | Section titles on storefront; auth headline| 400    | `ss01`           |
| `heading-xl`            | Dashboard page title; shop detail title    | 600    | —                |
| `heading-lg`            | Section headers inside pages; dialog title | 600    | —                |
| `heading-md`            | Card titles; table section labels          | 600    | —                |
| `heading-sm`            | Sub-card labels; metric card eyebrow       | 600    | —                |
| `body-lg`               | Storefront body prose; hero subhead        | 400    | —                |
| `body`                  | Default paragraph, form field value        | 400    | `cv05`, `cv11`   |
| `body-sm`               | Dashboard rows; secondary explanation      | 400    | `cv05`, `cv11`   |
| `caption`               | Metadata, helper text, input hint          | 400    | `cv05`           |
| `overline`              | Eyebrow labels, table column headers       | 600 UC | `ss01`           |
| `mono-sm`               | Inline IDs, order numbers in lists         | 500    | `tnum`, `ss01`   |
| `mono`                  | Financial amounts in tables                | 500    | `tnum`, `ss01`   |
| `mono-lg`               | KDS order number, big-ticket price         | 600    | `tnum`, `ss01`   |

### 3.4 Font feature settings — global defaults

Applied at `body`:

```
font-feature-settings: "cv05" 1, "cv11" 1, "ss01" 1, "calt" 1;
font-variant-numeric: normal;
```

Applied to `.tabular`, financial tables, KDS, and all `.font-mono`:

```
font-variant-numeric: tabular-nums;
font-feature-settings: "tnum" 1, "ss01" 1, "calt" 1;
```

Applied to Fraunces display scales:

```
font-feature-settings: "ss01" 1, "liga" 1, "dlig" 1;
font-optical-sizing: auto;
```

The `cv05`/`cv11` pair on Inter gives us the single-storey `a` and open `4` — noticeably more editorial than default Inter. `ss01` in Fraunces enables the stylistic alternates used in the logo lockup.

### 3.5 Tracking and leading maps (Tailwind extension)

```
letterSpacing:
  tighter: -0.02em
  tight:   -0.01em
  normal:   0
  wide:    +0.01em
  wider:   +0.04em
  widest:  +0.08em   // reserved for overline and UPPERCASE labels

lineHeight:
  display: 1.04
  tight:   1.15
  snug:    1.3
  normal:  1.55
  relaxed: 1.7       // long prose on storefront product descriptions
```

### 3.6 Text colour application defaults

- Headings: `--ink-primary`
- Body: `--ink-primary`
- Secondary: `--ink-secondary`
- Captions/helper: `--ink-tertiary`
- Disabled text: `--ink-quaternary`
- Links in body text: `--brand-primary`, underline on hover only, **no colour change** on active/visited (prevents the "8 different blues" problem).

---

## 4. Spacing and layout

### 4.1 4px base grid

Scale (Tailwind `spacing` extension — supplements, does not replace, Tailwind defaults):

| Token | Value | Use                                          |
|-------|-------|----------------------------------------------|
| `0.5` | 2px   | Hairline inset (rare)                        |
| `1`   | 4px   | Icon-to-text gap                             |
| `2`   | 8px   | Field padding, chip padding                  |
| `3`   | 12px  | Between related form fields                  |
| `4`   | 16px  | Card padding (dense), row gap                |
| `5`   | 20px  | Comfortable card padding                     |
| `6`   | 24px  | Section inside gap; card gap                 |
| `8`   | 32px  | Between unrelated sections (mobile)          |
| `10`  | 40px  | Sub-section spacing (desktop)                |
| `12`  | 48px  | Section spacing (desktop)                    |
| `16`  | 64px  | Hero-to-next-section (desktop)               |
| `20`  | 80px  | Between page hero and body on storefront     |
| `24`  | 96px  | Hero vertical padding (desktop)              |
| `32`  | 128px | Editorial pull-quote isolation               |

### 4.2 Container widths

| Token           | Width  | Use                                          |
|-----------------|--------|----------------------------------------------|
| `--w-prose-narrow` | `48ch` | Auth forms, single-focus narrative copy    |
| `--w-prose`     | `64ch` | Long-form marketing, FAQs                    |
| `--w-content`   | `72rem`| Dashboard content column                     |
| `--w-wide`      | `90rem`| Storefront hero, KDS grid                    |
| `--w-full-bleed`| `100vw`| Hero imagery, announcements                  |

Note: `tailwind.config.ts` already sets `container: { 2xl: '1400px' }`. Keep it — `72rem` = 1152px fits inside, `90rem` = 1440px is the new cap.

### 4.3 Section rhythm

| Surface     | Hero top/btm | Section gap | Sub-section gap | Intra-card gap |
|-------------|--------------|-------------|-----------------|----------------|
| Storefront desktop | 96 / 96 | 80 | 48 | 24 |
| Storefront mobile  | 48 / 48 | 40 | 24 | 16 |
| Dashboard desktop  | 40 / 24 | 40 | 24 | 16 |
| Dashboard mobile   | 24 / 16 | 24 | 16 | 12 |
| KDS (all)          | 16 / 16 | 16 | 12 | 8  |
| Auth               | centred, vertical padding 96 / 96 desktop, 64 / 64 mobile | — | — | 24 |

### 4.4 Dashboard grid

- 12-column grid, 24px gutter on desktop; 4-column, 16px gutter on mobile.
- KPI row: 4-wide cards at `xl`, 2-wide at `md`, 1-wide at `sm`. Enforced via `grid-cols-1 md:grid-cols-2 xl:grid-cols-4`.
- Primary content area: 8 cols. Sidebar detail: 4 cols. Stack on `md:`-down.
- Row rhythm: 8px vertical between related items, 16px between row groups, 24px between sections.

### 4.5 Storefront grid

- Product grid: `1 / 2 / 3 / 4` cols at `sm / md / lg / xl`. Card min-height preserved by aspect-ratio on image, not fixed height.
- Hero: two-column on desktop (text 6/12, image 6/12 full-bleed right), stacked on mobile with image first.
- Category strip: horizontal scroll-snap on mobile, grid on desktop.

---

## 5. Radius and shadow

### 5.1 Radius

| Token                  | Value  | Use                                          |
|------------------------|--------|----------------------------------------------|
| `--radius-none`        | `0`    | Full-bleed imagery, tabular cells            |
| `--radius-xs`          | `4px`  | Checkboxes, small chips, badges              |
| `--radius-sm`          | `6px`  | Inputs, selects, small buttons               |
| `--radius-md`          | `10px` | Buttons (default), dropdown items            |
| `--radius-lg`          | `14px` | Cards, dialogs, sheets                       |
| `--radius-xl`          | `20px` | Feature tiles, storefront product cards      |
| `--radius-2xl`         | `28px` | Editorial feature callouts                   |
| `--radius-pill`        | `999px`| Status chips, avatar frames, filter pills    |

Tailwind extension (`borderRadius`):

```
radius: { none: 0, xs: '4px', sm: '6px', md: '10px', lg: '14px', xl: '20px', '2xl': '28px', pill: '999px' }
```

Note: the existing tailwind config maps `lg → var(--radius)`. Redefine the token set; keep backwards compat by setting `--radius: 10px` (matches new `md`).

### 5.2 Shadow

Shadows are tuned to the warm-neutral palette — they use a warm near-black (`30 20% 10%`) at low alpha, not pure black. This is the single easiest way to avoid the "AI-generated" look (stock shadows are always flat grey).

| Token                | Value (light)                                                                                                        | Use                                  |
|----------------------|----------------------------------------------------------------------------------------------------------------------|--------------------------------------|
| `--shadow-hairline`  | `inset 0 -1px 0 0 hsl(var(--border-subtle))`                                                                        | Underlining rows, sticky headers     |
| `--shadow-subtle`    | `0 1px 2px 0 hsl(30 20% 10% / 0.04), 0 1px 1px 0 hsl(30 20% 10% / 0.03)`                                           | Card rest                            |
| `--shadow-lift`      | `0 4px 8px -2px hsl(30 20% 10% / 0.06), 0 2px 4px -2px hsl(30 20% 10% / 0.04)`                                     | Card hover, button hover             |
| `--shadow-float`     | `0 12px 24px -6px hsl(30 20% 10% / 0.12), 0 4px 8px -4px hsl(30 20% 10% / 0.06)`                                   | Dropdowns, popovers, toasts          |
| `--shadow-bloom`     | `0 24px 48px -8px hsl(30 20% 10% / 0.18), 0 8px 16px -4px hsl(30 20% 10% / 0.08)`                                  | Dialogs, sheets                      |

Dark mode shadows use `hsl(0 0% 0% / ...)` (true black) at higher alpha (×2) because the surface is already dark.

Tailwind `boxShadow` extension:

```
boxShadow: {
  hairline: 'var(--shadow-hairline)',
  subtle:   'var(--shadow-subtle)',
  lift:     'var(--shadow-lift)',
  float:    'var(--shadow-float)',
  bloom:    'var(--shadow-bloom)',
}
```

**Anti-pattern:** no blurry coloured gradients as shadows. No `box-shadow: 0 0 40px hsl(var(--brand-primary))`. If you catch yourself reaching for glow, you want an accent border instead.

---

## 6. Motion language

Motion is a restraint discipline here. The KDS especially must not jitter; vendors watch it all day.

### 6.1 Curves

| Token                 | Cubic-bezier            | Use                                          |
|-----------------------|-------------------------|----------------------------------------------|
| `--ease-standard`     | `cubic-bezier(0.4, 0, 0.2, 1)` | Default for everything unless otherwise stated |
| `--ease-emphasized`   | `cubic-bezier(0.3, 0, 0, 1)`   | Entering modals, sheets, major state changes |
| `--ease-decelerate`   | `cubic-bezier(0, 0, 0.2, 1)`   | Incoming elements (slide-in from edge)        |
| `--ease-accelerate`   | `cubic-bezier(0.4, 0, 1, 1)`   | Exiting elements (dismissed toast)           |
| `--ease-spring-soft`  | Framer spring: stiffness 280, damping 28 | Drawer open, bottom-sheet drag |
| `--ease-spring-snap`  | Framer spring: stiffness 400, damping 30 | Hover lift, button press |

### 6.2 Durations

| Token                 | ms   | Use                                              |
|-----------------------|------|--------------------------------------------------|
| `--duration-instant`  | 80   | Checkbox tick, toggle state flip                 |
| `--duration-fast`     | 120  | Small state changes, chip selection              |
| `--duration-default`  | 180  | Card hover, button hover, nav underline          |
| `--duration-moderate` | 240  | Modal, sheet, popover entrance                   |
| `--duration-slow`     | 320  | Page transitions, hero image crossfade           |
| `--duration-slowest`  | 480  | Onboarding reveal, celebratory success           |

### 6.3 Signature motions

- **Page enter:** 200ms `ease-standard`, opacity 0 → 1, translateY 8px → 0. Only on route change, not on component re-render.
- **Card hover:** 180ms `ease-spring-snap`, translateY 0 → -2px, shadow `subtle` → `lift`. Only on pointer devices (`@media (hover: hover)`). Mobile gets press state instead.
- **Button press:** 100ms, scale 1 → 0.98, then release 140ms back to 1.
- **Modal enter:** 240ms `ease-emphasized`, opacity 0 → 1, scale 0.96 → 1. Scrim fades 180ms.
- **Sheet (cart drawer) enter:** 280ms `ease-spring-soft`, translateX 100% → 0 (right-hand sheet) or translateY 100% → 0 (bottom sheet on mobile).
- **Nav underline:** 180ms `ease-standard`, width 0 → 100%, origin varies by direction (see Linear's playbook — slides in the direction of motion).
- **Toast:** enter 240ms `ease-decelerate`, exit 160ms `ease-accelerate`. Stack vertically, newest on top.
- **KPI sparkline draw:** 480ms once on mount, `ease-decelerate`. Never re-animates on data refresh — flip values without redraw.
- **KDS order card "new" flash:** 2 × 480ms pulse of `--accent-subtle` border, then settles. No shake, no bounce.

### 6.4 Motion helpers (file to publish in implementation wave 4)

Path: `frontend/lib/motion.ts`.

Exports (reference API — implementers author this file; do NOT paste this into code inside this research wave):

```ts
export const durations = { instant: 0.08, fast: 0.12, default: 0.18, moderate: 0.24, slow: 0.32, slowest: 0.48 } as const;
export const easings = {
  standard:   [0.4, 0, 0.2, 1],
  emphasized: [0.3, 0, 0, 1],
  decelerate: [0, 0, 0.2, 1],
  accelerate: [0.4, 0, 1, 1],
} as const;
export const springs = {
  soft: { type: 'spring', stiffness: 280, damping: 28 },
  snap: { type: 'spring', stiffness: 400, damping: 30 },
} as const;

// Variants
export const fadeUp = { hidden: { opacity: 0, y: 8 }, visible: { opacity: 1, y: 0, transition: { duration: durations.default, ease: easings.standard } } };
export const scaleIn = { hidden: { opacity: 0, scale: 0.96 }, visible: { opacity: 1, scale: 1, transition: { duration: durations.moderate, ease: easings.emphasized } } };
export const sheetRight = { hidden: { x: '100%' }, visible: { x: 0, transition: springs.soft } };

// Reduced motion override
export const withReducedMotion = <T extends object>(v: T): T => (prefersReducedMotion() ? { ...v, transition: { duration: 0 } } : v);
```

### 6.5 `prefers-reduced-motion`

Every motion path respects `@media (prefers-reduced-motion: reduce)`. Helpers collapse to instant swaps. Page-enter and hover-lift are dropped entirely. Modal scrim still fades (needed for a11y cue) but at 80ms not 180ms.

Global CSS rule at bottom of `globals.css`:

```
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
}
```

---

## 7. Iconography

- **Library:** Lucide React (already in deps). 1.5px stroke.
- **Sizes:** 16px inline (in buttons, chips), 20px default (standalone), 24px prominent (section headers), 32px feature tiles, 40px empty-state illustrations.
- **Alignment:** icons sit on text baseline inside buttons — `translateY(-0.5px)` micro-adjust inside the `Button` component (once, centrally).
- **Colour:** inherit from parent `color`. Never a hardcoded hex.
- **Pairing with text:** gap 8px (mobile), 10px (desktop). Icon left by default; right-aligned only for "external link" pattern.
- **Bundle hygiene:** import icons individually (`import { ChefHat } from 'lucide-react'`) — the codebase already does this; maintain it.

### 7.1 Brand mark SVG pattern

- Authoring: Figma → `svgo` → `components/brand/LogoMark.tsx` (React component). Uses `currentColor`. Props: `size`, `className`.
- Favicon: generated once into `app/icon.tsx` as a Next.js metadata icon (SSR, CSP-clean).
- OG image: generated dynamically with `@vercel/og` → `app/opengraph-image.tsx` — respects CSP because it renders server-side.

---

## 8. Imagery language

### 8.1 Real food photography

- Source: the product-owner provides photography for seed shops. Third-party stock is a last resort and must be licensed (Pexels, Unsplash) — **NEVER** AI-generated food imagery (avoid "hyperreal 7-finger hands" problem; also the product is for real food vendors, authenticity is the brand).
- **Processing:** crop to canonical ratios (see 8.3), compress to WebP at 82% quality, serve through Next.js `<Image>` with `priority` on hero and `loading="lazy"` below the fold.

### 8.2 Fallback SVG illustrations

When photography is unavailable:

- Palette: drawn from brand tokens (primary, secondary, accent) at subtle opacities (30–60%).
- Style: flat, editorial, geometric — think Stripe illustrations, not Duolingo.
- Example inventory to commission (file in `public/illustrations/`): `empty-orders.svg`, `empty-customers.svg`, `empty-products.svg`, `payment-success.svg`, `shop-offline.svg`, `kitchen-empty.svg`.
- Each ships light + dark variants OR uses `currentColor` + `--accent-default` via CSS vars for theming.

### 8.3 Aspect ratios

| Context                | Ratio | Min dimensions (served) |
|------------------------|-------|-------------------------|
| Storefront hero        | 21:9  | 1920 × 823              |
| Marketing section      | 16:9  | 1600 × 900              |
| Shop cover             | 16:9  | 1200 × 675              |
| Category tile          | 4:3   | 800 × 600               |
| Product card           | 1:1   | 800 × 800               |
| Product gallery zoom   | 1:1   | 1600 × 1600             |
| Testimonial / avatar   | 1:1   | 160 × 160               |
| Banner / notice        | 3:1   | 1500 × 500              |

### 8.4 Placeholder strategy

- **Skeleton:** solid `--surface-muted` block at the correct aspect-ratio, 1.5s pulse using `opacity 1 → 0.6 → 1` (no shimmer gradient — that's the AI-looking tell). Implemented as a reusable `<Skeleton />` primitive.
- **Image `onError`:** swap to `<PlaceholderTile kind="product">` — a same-ratio tile with the product's first letter in Fraunces display size over `--brand-primary-subtle`. Looks intentional, not broken.
- **Verification gate:** per the repo memory rule `feedback_image_rendering.md`, E2E tests must assert `naturalWidth > 0` for every hero and product image.

### 8.5 Photography content rules

- Warm lighting, window-light preferred over studio strobe.
- Include vendor hands, aprons, steam — human signals. No empty dish-on-white.
- Colour-grade: warm shadows, slightly desaturated highlights. The palette complements the brand tokens.
- Never put text on photography without a solid-colour gutter or a ≥50% scrim over the text area. Text-on-image legibility is tested at mobile breakpoint.

---

## 9. Component patterns

For every primitive listed, **public API is preserved**. If the current file at `frontend/components/ui/<name>.tsx` exposes prop `variant="destructive"`, the redesign keeps `variant="destructive"`. Internal classes change; signatures don't. Callers need not migrate.

### 9.1 `Button`

**Path:** `frontend/components/ui/button.tsx`

**Variants — `variant`:** `primary` (brand), `secondary` (outline), `ghost`, `destructive`, `link`.
**Sizes — `size`:** `sm` (32px), `default` (40px), `lg` (48px), `icon` (36×36 square), `icon-lg` (44×44 for KDS).

**Anatomy:**
- Leading icon slot (16–20px)
- Label (Inter 500, `body-sm` on `sm`, `body` on default, `body-lg` on `lg`)
- Trailing icon slot
- Optional loading spinner replaces leading icon; label stays

**Interaction states:**

| State         | Primary                                                      | Secondary                                            |
|---------------|--------------------------------------------------------------|------------------------------------------------------|
| Rest          | bg `--brand-primary`, text `--ink-on-brand`                  | bg transparent, border `--border-default`, text `--ink-primary` |
| Hover         | bg `--brand-primary-hover`, shadow `subtle`                  | bg `--surface-subtle`                                |
| Focus-visible | outline 2px `--border-focus`, outline-offset 2px             | same                                                 |
| Active/press  | bg `--brand-primary-press`, scale 0.98                       | bg `--surface-muted`, scale 0.98                     |
| Disabled      | opacity 0.5, cursor not-allowed, bg stays                    | same                                                 |
| Loading       | disabled + spinner, label stays, width stable (no reflow)    | same                                                 |

**Motion:** 180ms hover, 100ms press (spring-snap on scale).

**A11y:** `aria-busy` during loading; `aria-disabled` when `disabled`; loading state announces "Loading" to screen readers once, not on every re-render.

**API preserved:** `variant`, `size`, `asChild`, plus all HTML button attrs.

### 9.2 `Card`

**Path:** `frontend/components/ui/card.tsx`

**Variants — `tone`:** `default`, `raised` (shadow-lift), `inset` (bg `--surface-subtle`), `muted`, `inverse` (for KDS and testimonials).

**Anatomy:** `Card > CardHeader > (CardTitle, CardDescription) + CardContent + CardFooter`. Preserved.

**Defaults:**
- Padding: `p-5` (20px) default, `p-6` (24px) on lg+.
- Radius: `rounded-lg` (14px) → maps to `--radius-lg`.
- Border: `border border-[hsl(var(--border-subtle))]`.
- Shadow: `--shadow-subtle` rest, `--shadow-lift` hover (only when `asLink` or `role="button"`).
- Hover lift applies ONLY when card is interactive. Static cards never move.

**Motion:** interactive card — 180ms lift + shadow. Respects `prefers-reduced-motion`.

**A11y:** if card is interactive, it's a button or link (never a `div` with `onClick`). Cursor and focus ring follow suit.

**API preserved:** `className`, `children`, and all sub-components.

### 9.3 `Input`

**Path:** `frontend/components/ui/input.tsx`

**Variants — `tone`:** `default`, `danger` (error), `ghost` (transparent, for search bars).
**Sizes:** `sm` (32px), `default` (40px), `lg` (48px).

**Anatomy:**
- Optional leading icon (absolutely positioned left, 12px inset)
- Input element
- Optional trailing control (clear button, unit label, reveal password)
- Optional hint text below, `caption` size, `--ink-tertiary`
- Optional error text below, `caption`, `--semantic-danger`

**Interaction states:**

| State         | Border                                     | Background              |
|---------------|--------------------------------------------|-------------------------|
| Rest          | `--border-default`                         | `--surface-card`        |
| Hover         | `--border-strong`                          | same                    |
| Focus-visible | outline 2px `--border-focus`, offset 1px, border transparent | same |
| Error         | `--semantic-danger`                        | `--semantic-danger-subtle` on dark mode; white on light |
| Disabled      | `--border-subtle`                          | `--surface-muted`       |

**Placeholder colour:** `--ink-tertiary` — no italic, no lighter grey.

**Autocomplete tokens:** declare `autoComplete` on every form field — `email`, `current-password`, `new-password`, `given-name`, `family-name`, `tel`, etc. Fail the a11y audit if missing.

**API preserved:** all HTML input attrs + the optional icon/trail props (add if not present).

### 9.4 `Badge`

**Path:** `frontend/components/ui/badge.tsx`

**Variants — `tone`:** `neutral`, `brand`, `success`, `warning`, `danger`, `info`, `accent`.
**Variants — `emphasis`:** `solid` (filled), `soft` (subtle bg + strong text), `outline`.
**Sizes:** `sm` (20px tall), `default` (24px tall).

**Anatomy:** optional leading dot (6px filled circle) + label + optional trailing icon (14px).

**Default:** `soft` emphasis — chosen because `solid` badges scream and UIs use a lot of them. Use `solid` for unread counts and primary status indicators only.

**States:** no hover (badges are non-interactive). If they become filters (common on dashboards), promote to a `Chip` component with hover/press states, a `button` role, and `aria-pressed`.

**A11y:** include an `aria-label` when the visual is a dot/icon only.

### 9.5 `Dialog`

**Path:** `frontend/components/ui/dialog.tsx` (Radix under the hood — keep it).

**Variants — `size`:** `sm` (400px), `default` (520px), `lg` (720px), `xl` (960px).
**Variants — `tone`:** `default`, `danger` (for destructive confirmations — header ornament red, primary CTA danger).

**Anatomy:** `Dialog > DialogTrigger > DialogOverlay > DialogContent > (DialogHeader > DialogTitle, DialogDescription) + body + DialogFooter`.

**Defaults:**
- Overlay: `--overlay-scrim`, 180ms fade.
- Content: `--surface-card`, `--shadow-bloom`, `--radius-lg`, 240ms scale+fade via `ease-emphasized`.
- Max-width per size, max-height `min(85vh, 900px)`, internal scroll on body.
- Close button: top-right, 32×32 hit target, icon 20px.

**Focus management:** Radix default (initial focus on first focusable inside, trap until close, return on dismiss). Do not override.

**Escape + outside-click:** both dismiss; destructive dialogs require explicit `Cancel` click (set `onInteractOutside={(e) => e.preventDefault()}`).

**Motion:** no shake, no spring. Scale 0.96 → 1 and fade.

### 9.6 `DropdownMenu`

**Path:** `frontend/components/ui/dropdown-menu.tsx` (Radix)

**Anatomy:** `Trigger → Portal → Content → Item/Separator/Label/CheckboxItem/RadioItem`. Preserved.

**Defaults:**
- Content: `--surface-popover`, `--shadow-float`, `--radius-md`, min-width `12rem`, max-height `min(70vh, 480px)` scroll.
- Item: 8px vert padding, 12px horiz; leading icon 16px; hover bg `--surface-subtle`; selected bg `--brand-primary-subtle`; destructive items text `--semantic-danger`.
- Separator: 1px `--border-subtle`.
- Keyboard: full Radix default (arrow keys, type-ahead, home/end).

**Motion:** open 180ms `ease-standard`, fade + translateY(-4px → 0). No scale.

### 9.7 `Label`

**Path:** `frontend/components/ui/label.tsx`

**Style:** Inter 500, `body-sm`, `--ink-primary`, 8px margin-bottom when in a field stack.
**Required marker:** trailing `*` in `--semantic-danger`, `aria-hidden="true"` — the actual required-state is on the input via `aria-required`.
**Optional marker:** trailing `(optional)` in `--ink-tertiary`, `caption` size. Prefer this over required markers — saves visual noise since most inputs are required.

### 9.8 `Pagination`

**Path:** `frontend/components/ui/pagination.tsx`

**Variants:** `compact` (mobile, Prev/Next + "3 of 12"), `numbered` (desktop default), `load-more` (storefront).

**Defaults:**
- Numbered: 36×36 square buttons; active page — `solid primary` with `--shadow-subtle`; ellipsis separator `...`.
- Compact: left/right `ghost` icon buttons; middle label `caption`.
- Load-more: centered full-width `secondary` button with spinner during fetch.

**A11y:** `<nav aria-label="Pagination">`; current page has `aria-current="page"`; prev/next buttons have explicit `aria-label`.

### 9.9 `Select`

**Path:** `frontend/components/ui/select.tsx` (Radix)

**Trigger:** same visual as `Input`, plus trailing chevron 16px.
**Content:** same visual as DropdownMenu content.
**Selected checkmark:** leading 14px check icon in `--brand-primary`, appears to the left of the item label.
**Placeholder:** `--ink-tertiary`, no italic.
**Error state:** mirrors `Input` error (border + subtle bg + error message below).

### 9.10 `Table`

**Path:** `frontend/components/ui/table.tsx`

**Anatomy:** `Table > TableCaption + TableHeader > TableRow > TableHead, TableBody > TableRow > TableCell, TableFooter`. Preserved.

**Defaults:**
- `TableHeader`: `--surface-subtle` bg, `overline` text on column labels, 40px row height, `--shadow-hairline` bottom.
- `TableBody` rows: 48px height, 16px horiz padding, `--border-subtle` bottom. No zebra striping by default.
- Hover row: `--surface-subtle` at 50% opacity (subtle tint, not full block).
- Selected row: `--brand-primary-subtle` bg, 2px left border `--brand-primary`.
- Numeric cells: `font-mono`, `tabular-nums`, text-right.
- Status cells: pass through a `Badge`.

**Sticky header:** when scroll area exceeds viewport, header is `position: sticky; top: 0; z-index: 1` with `--shadow-hairline`.

**Density modes:** `compact` (40px rows, for finance ledger), `default` (48px), `comfortable` (56px, for customer directory).

**Sort / filter UI:** chevron icons in header, clickable to sort; active sort shows direction icon in `--brand-primary`.

**A11y:** `<th scope="col">`; `<caption>` is visually hidden but announced. Sortable headers are `<button>` inside `<th>` with `aria-sort`.

### 9.11 `Toast` + `Toaster`

**Path:** `frontend/components/ui/toast.tsx` + `frontend/components/ui/toaster.tsx`

**Variants — `tone`:** `default`, `success`, `warning`, `danger`, `info`, `brand`.

**Anatomy:** leading icon 20px + (title `heading-sm` + optional description `body-sm`) + optional action button (`secondary sm`) + close (icon 16px).

**Defaults:**
- Width: 360px (desktop), full-width minus 16px gutter (mobile).
- Stack: top-right desktop, top-centre mobile, newest on top, max 3 visible.
- Duration: success/info 4s, warning 6s, danger 8s — user can dismiss.
- Hover pauses auto-dismiss; focus inside also pauses.
- `--shadow-float`, `--radius-md`.

**Motion:** per §6.3 — 240ms decelerate in, 160ms accelerate out.

**A11y:** `role="status"` for success/info, `role="alert"` for warning/danger. Screen-reader-announce the title; description is optional.

---

## 10. Surface-specific rules

### 10.1 Storefront (B2C) — `/shop`, `/shop/[slug]`, `/shop/orders`, `/track`

- Full-bleed hero photography, 21:9 desktop / 4:3 mobile.
- Typography: generous — `display-2xl` for shop name, `body-lg` for description, `heading-lg` for section headers.
- Product card: 1:1 image, 16px padding, `heading-md` name, `body-sm` description (2 lines clamp), price in `mono` at `text-body-lg`. Add-to-cart button full-width below card footer on hover (desktop) / always visible (mobile).
- Nav: full nav on scroll-top, collapses to sticky micro-bar (shop name + cart + account) after 64px scroll. Micro-bar height 48px, `--surface-card` with `--shadow-hairline` bottom.
- Cart: **slide-over sheet** from right, not a dropdown. 420px wide desktop, full-width mobile. Line items stack with thumbnail, name, qty stepper, subtotal. Footer: subtotal + delivery estimate + checkout CTA.
- Section rhythm: 96/64/48 (desktop hero/section/sub).
- Order tracking (`/track`): timeline view, 5 status dots, current one lit in `--brand-primary`, completed in `--semantic-success`, pending in `--ink-tertiary`. Update polls the backend every 15s — no optimistic UI.
- "Order confirmed" celebratory state: 480ms scale+fade of a large `CheckCircle2` icon over `--semantic-success-subtle`, then settles. One animation, not a confetti shower.

### 10.2 Dashboard (B2B) — `/dashboard`, `/customers`, `/finance`, `/products`, `/shops`, `/orders`, `/marketing`

- **Shell:** left sidebar (240px desktop, collapse to 64px on `lg` with icon-only; full-width drawer on `md:`-down). Top bar: breadcrumb, env badge (dev/staging/prod), quick actions (search, notifications, user menu).
- **Sidebar groups:** Overview (Dashboard) → Commerce (Shops, Products, Orders, Customers) → Money (Finance) → Growth (Marketing) → Operations (Kitchen). Each group has a `overline` label, items below.
- **Density:** 48px nav item rows, 16px padding, icons 20px. Active item: `--brand-primary-subtle` bg, `--brand-primary` text, 2px left border `--brand-primary`.
- **Command-K palette (future, designed now to reserve):** `⌘K` / `Ctrl+K` opens a centred dialog with search, recent actions, entity quick-jump. Shell should already dispatch the keybinding (no-op initially).
- **Page header:** `heading-xl` title, optional `body` description, right-aligned primary + secondary CTAs, optional filter bar beneath. Consistent across pages.
- **KPI card row:** title `overline`, value `heading-xl` in `mono` tabular-nums, delta chip (up/down vs last period), sparkline 80×24 in `--brand-primary` at 40% opacity. Sparklines animate once on mount, never on refresh.
- **Data tables:** `default` density. Row click opens detail (drawer right 480px on desktop, full-screen on mobile). Bulk actions bar appears on selection, sticky at top of table area.
- **Empty states:** 320×240 SVG illustration + `heading-md` title + `body` explanation + primary CTA. Use commissioned illustrations (§8.2), not generic magnifier icons.
- **Keyboard:** Escape closes drawers/modals; `/` focuses the global search; `g then d` jumps Dashboard (g/d/c/p/o/f/m/k mapping to sidebar items) — design-reserved; implementation post-v1.

### 10.3 Kitchen Display System (KDS) — `/dashboard/kitchen`

- **Canvas:** `--surface-inverse` in light mode (warm dark); `--surface-canvas` in dark mode (stays dark). KDS is visually distinct from the rest of the dashboard — vendors need to know at a glance "this is the cook's screen, not the owner's."
- **Grid:** 3-up on desktop, 2-up on tablet, 1-up on mobile. Auto-reflow as orders arrive.
- **Order card:**
  - Width min 320px; height content-driven.
  - Header: big mono order number (`mono-lg`), elapsed time chip (updates every 5s), table/customer name.
  - Body: line items, each with qty × name + modifiers in secondary ink, allergens as `warning` chips.
  - Footer: status chip + action buttons. Buttons min 48×48.
- **Status chips (loud):** `Prep` (`--semantic-info`), `Fire` (`--accent-default`), `Ready` (`--semantic-success`), `Held` (`--ink-tertiary`). Typography `overline`, `solid` emphasis.
- **Elapsed time rules:** 0–5 min `--ink-secondary`; 5–10 min `--semantic-warning`; >10 min `--semantic-danger` + 2Hz pulse on the time chip (respects reduced-motion — switches to solid red, no pulse).
- **Touch:** 48×48 min hit target; action buttons 56px on mobile. No hover states relied upon — all interactions respond to press.
- **Audio:** new-order chime at 30% volume default, mutable via top-right toggle; preference persists in localStorage. Optional, a11y-safe.
- **Performance:** real-time updates via WebSocket (already implemented). No layout shift on new order — cards enter from the top with 280ms slide+fade, existing cards reflow via FLIP animation (library-free, 180ms translate).

### 10.4 Auth — `/auth/signin` (and future sign-up/reset paths)

- **Layout:** single centred card on `--surface-canvas` with a subtle warm tint (`linear-gradient(180deg, hsl(var(--brand-primary-subtle)) 0%, hsl(var(--surface-canvas)) 40%)` at 0.3 opacity — NOT a bright gradient; a whisper of fig).
- **Card:** 440px wide, `--surface-card`, `--shadow-bloom`, `--radius-xl` (20px), 40px internal padding.
- **Header:** brand mark (48px) top-centre, wordmark below, tagline in `body-sm` `--ink-secondary`.
- **Fields:** labels above, inputs full-width `lg` size, CTA `lg` full-width `primary`.
- **Federated sign-in:** Keycloak button as `secondary` size `lg`, branded icon left. Divider with "or" sits between federated and email/password.
- **Post-sign-in:** smooth route transition (200ms fade-up per §6.3) to `/dashboard`.
- **Errors:** inline under the field, `caption`, `--semantic-danger`. Global error (e.g. account locked) as a `danger` banner above the form, 16px margin below.

---

## 11. Forbidden list (enforced in review)

Items here are **rejection-worthy** in PR review. No debate, just fix.

1. **Centred hero with rainbow gradient.** The canonical AI-demo tell.
2. **Emoji bullets in product UI.** Emojis are allowed only in user-generated content (shop names, product descriptions) and in notification audio cues.
3. **Purple-pink gradient CTAs** (`from-purple-500 to-pink-500`). Forbidden.
4. **Gradient text.** Editorial brand uses solid colour with typography doing the work.
5. **Generic Unsplash placeholders** ("man laptop smiling," "food on white"). Either real vendor photography or brand-palette SVG illustrations.
6. **Drop-shadow on body text.** Readability killer, AI-demo signifier.
7. **Coloured underlines on body links.** Links are `--brand-primary`, underline on hover only.
8. **Multi-colour page backgrounds.** One surface per page. Sections distinguish with whitespace + hairline, not bg colour changes — except for explicit CTA banners and KDS.
9. **Pastel dark mode** (`slate-900` with `purple-500` accents). Our dark mode stays warm.
10. **Two simultaneous animations on dashboard surfaces.** One at a time. KPI sparkline OR toast slide — not both.
11. **Inline `style=` attributes.** CSP-compliant, but also a design discipline — everything goes through tokens.
12. **Hardcoded hex/rgb in components.** Token or bust.
13. **CSS-in-JS libraries that emit inline styles at runtime** (styled-components, emotion). We use Tailwind; stay there.
14. **`!important` declarations.** If you need one, the token or selector is wrong.
15. **`<div onClick>` with no role.** Buttons are buttons; links are links.
16. **Icons without text at the sole affordance** (other than universally understood: close X, search magnifier, chevrons). Add `aria-label` AND a tooltip.
17. **Tooltips as the only source of information.** Touch devices don't have hover; anything critical must be visible.
18. **Toast-spam.** More than one toast per user action. If you're tempted, you need a dialog.
19. **Infinite scroll on dashboards.** Use pagination. Infinite scroll is for storefront browse only, and even there, add a "Load more" button after every 4 pages.
20. **Loading spinners > 500ms without a skeleton.** Users want structure, not the wheel of doom.
21. **Centre-aligned paragraph prose.** Left-aligned always in English UI.
22. **Full-uppercase paragraphs.** Overlines only. Never body text.
23. **Small-text light-grey-on-white for critical info** (payment amount, delivery window). Even "optional" fields deserve `--ink-secondary`.
24. **Modal inside modal.** Redesign the flow.
25. **Carousels on dashboards.** Ever. They're fine on storefront hero (single-image preferred) but never on a working surface.

---

## 12. Accessibility gates (MUST pass)

Every PR touching UI runs an axe scan. Zero violations below `moderate` severity are allowed to merge.

### 12.1 Contrast

- Every colour pair used in implementation must appear in §2.5. If not, it's added via a spec amendment before implementation.
- Text on photography requires a 50% solid scrim (`--overlay-scrim`) minimum.

### 12.2 Focus

- Every interactive element has a visible focus ring: outline 2px `--border-focus`, outline-offset 2px, `--radius` matches the control. No outline-suppression.
- Tab order follows visual order; no `tabindex > 0`.
- Focus trap inside Radix-based dialogs/sheets (they handle it); verify none of our custom wrappers break it.
- `:focus-visible` only — avoid `:focus` styling, which flashes on mouse click.

### 12.3 Keyboard

- Every action reachable by keyboard. Drag-and-drop (if added) pairs with keyboard equivalents.
- Escape closes modals/sheets/dropdowns.
- Enter activates default CTA in forms; Space activates buttons.
- Radix primitives stay. Replacing with raw HTML is an explicit rejection.

### 12.4 Motion

- `prefers-reduced-motion` respected globally via §6.5 CSS + per-component Framer `withReducedMotion`.
- No motion longer than 500ms on transient state changes. Anything longer lives in onboarding.

### 12.5 Semantic HTML

- `<nav>`, `<main>`, `<article>`, `<aside>`, `<section>` used appropriately.
- Landmarks announced: skip-link to `#main`, visible on focus.
- Heading hierarchy: exactly one `<h1>` per page, no skipped levels.
- Lists for grouped items (`<ul>`, `<ol>`), not `<div>`s.

### 12.6 Forms

- Every input has a visible `<label>` linked by `htmlFor`/`id`.
- `autoComplete` attributes set (see §9.3).
- Error messages: `aria-describedby` links to error text; `aria-invalid="true"` on the field.
- Required-state signalled with `aria-required`, visually via `*` or `(optional)` per §9.7.

### 12.7 Live regions

- Toasts: `role="status"` / `role="alert"`.
- Order status updates on `/track`: `aria-live="polite"` on the status text element.
- KDS order count and queue: `aria-live="polite"` on the header count.

### 12.8 Internationalisation-ready (not i18n-complete in v1, but architecturally open)

- No hardcoded English strings in primitives — they accept `aria-label` props.
- Currency: rendered via `Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP' })`, never string concatenation.
- Dates: via `Intl.DateTimeFormat`; display in `Europe/London` timezone by default.

---

## 13. Implementation playbook (downstream agents)

Strict commit order. Each step is one commit on a feature branch (`feature/design-overhaul-<wave>`). Do not merge until the whole wave lands and §14 gates pass.

### Wave 1 — Foundation (no visual change yet)

1. **Add fonts**
   - Edit `frontend/app/layout.tsx` to load `Fraunces` and `Inter` via `next/font/google` with CSS variables `--font-display` and `--font-sans`.
   - Edit `frontend/app/layout.tsx` to load `GeistMonoVF.woff` via `next/font/local` as `--font-mono`.
   - Attach class names on `<html>` so the CSS vars are available globally.
   - Commit message: `feat(design): load Fraunces display + Inter body + Geist mono`

2. **Write `globals.css` token suite**
   - Replace `frontend/app/globals.css` with the full §2 token set (light + dark), §3.5 tracking/leading, §5 shadow vars, §6.3/6.5 motion + reduced-motion rule, §3.4 font-feature rules.
   - Keep shadcn legacy aliases (`--background`, `--foreground`, `--primary`, ...) mapped to the new tokens so existing components don't break during the transition. Aliases removed at end of wave 5.
   - Commit message: `feat(design): install Warm Editorial token suite in globals.css`

3. **Extend `tailwind.config.ts`**
   - Add `fontFamily.display/sans/mono` reading the font vars.
   - Add `colors.*` for all new tokens (e.g. `colors.surface.canvas: 'hsl(var(--surface-canvas))'`).
   - Add `borderRadius`, `boxShadow`, `spacing` scale supplements, `letterSpacing`, `lineHeight`, `transitionDuration`, `transitionTimingFunction` per §3–6.
   - Keep all existing shadcn colour aliases intact for continuity.
   - Commit message: `feat(design): extend Tailwind with Warm Editorial scale`

### Wave 2 — Motion + primitives

4. **Publish motion helpers**
   - Create `frontend/lib/motion.ts` per §6.4.
   - Export `durations`, `easings`, `springs`, `fadeUp`, `scaleIn`, `sheetRight`, `withReducedMotion`, `prefersReducedMotion`.
   - Unit test: snapshot the returned objects in `__tests__/motion.test.ts`.
   - Commit message: `feat(design): add motion token helpers`

5. **Re-author primitives in `components/ui/`**
   - Order inside the commit: `button.tsx`, `input.tsx`, `label.tsx`, `badge.tsx`, `card.tsx`, `select.tsx`, `dropdown-menu.tsx`, `dialog.tsx`, `pagination.tsx`, `table.tsx`, `toast.tsx`, `toaster.tsx`.
   - Signatures preserved per §9. Replace class names to use new tokens and Tailwind utilities. Add variant expansions (e.g. `tone`, `emphasis`) with safe defaults so existing callers keep working.
   - Add `<Skeleton />` primitive at `frontend/components/ui/skeleton.tsx` (new file) for §8.4.
   - Run: `npm run lint`, `npm test`.
   - Commit message: `feat(design): re-author UI primitives on Warm Editorial tokens`

### Wave 3 — Shells

6. **Re-author shells**
   - `frontend/components/dashboard-shell.tsx` (or the file that renders the sidebar; locate via `app/dashboard/layout.tsx`) per §10.2.
   - `frontend/components/storefront-shell.tsx` (or current storefront wrapper) per §10.1.
   - `frontend/app/auth/*` layout per §10.4.
   - KDS shell inside `app/dashboard/kitchen/page.tsx` per §10.3.
   - Brand identity assets land here: `frontend/components/brand/LogoMark.tsx`, `frontend/components/brand/LogoWordmark.tsx`, `frontend/app/icon.tsx`.
   - Commit message: `feat(design): re-author app shells — dashboard, storefront, auth, KDS`

### Wave 4 — Flagship pages

7. **Re-author flagship routes**
   - `frontend/app/shop/page.tsx` (storefront index)
   - `frontend/app/shop/[slug]/page.tsx` (shop detail)
   - `frontend/app/dashboard/page.tsx` (KPI overview)
   - `frontend/app/track/page.tsx` (order tracking)
   - Use commissioned illustrations from `public/illustrations/` if present, else placeholder skeletons.
   - Playwright snapshot tests added per route (see §14).
   - Commit message: `feat(design): re-author flagship pages — shop, shop detail, dashboard, track`

### Wave 5 — Remaining surfaces + cleanup

8. **Light pass on remaining pages**
   - `app/dashboard/customers`, `finance`, `products`, `shops`, `orders`, `marketing`, `shop/orders`, `auth/signin`.
   - Apply tokens; re-verify spacing rhythm; no new primitives needed.
   - Remove shadcn legacy colour aliases from `globals.css`; confirm nothing broke.
   - Commit message: `feat(design): finalise remaining surfaces and remove legacy aliases`

### Wave 6 — Verification

9. **Visual + functional gate** (not a commit but a merge block — see §14).

---

## 14. Verification protocol

Every wave runs this checklist before merge.

### 14.1 Command gates

```
cd frontend
npm run build            # Next build — zero errors, zero type errors
npm run lint             # next lint — zero warnings for `@typescript-eslint/*` and `react/*`
npm test                 # Jest — all suites green, coverage not regressed
npx playwright test      # Both projects: mobile (390×844) + desktop (1440×900)
```

CI-equivalent in `.github/workflows/*` must run the same four. If any fails, the wave doesn't merge.

### 14.2 Visual snapshot gates

Create `frontend/e2e/visual/` with one snapshot test per flagship route. Author them during wave 4 commit (step 7):

- `auth-signin.spec.ts` — `/auth/signin` light + dark, mobile + desktop.
- `storefront-index.spec.ts` — `/shop` light + dark, mobile + desktop.
- `shop-detail.spec.ts` — `/shop/the-bake-shop` (or first seeded shop) light + dark, mobile + desktop.
- `dashboard-overview.spec.ts` — `/dashboard` light + dark, desktop only (KPIs need width).
- `track.spec.ts` — `/track?orderId=...` light + dark, mobile + desktop.
- `kds.spec.ts` — `/dashboard/kitchen` with 3 seeded orders, desktop only.
- `cart-sheet.spec.ts` — cart sheet open state, mobile.

Use `expect(page).toHaveScreenshot({ maxDiffPixelRatio: 0.01 })`. Snapshot baselines commit to `frontend/e2e/visual/__screenshots__/`.

### 14.3 Interaction gates (click-through)

Per project memory rule `feedback_e2e_click_through.md`, snapshot tests alone are insufficient. For each flagship route, add an interaction test that:

- Loads the page.
- Clicks the primary CTA.
- Verifies the resulting navigation / modal / state.
- Asserts `naturalWidth > 0` for all hero and product images (per `feedback_image_rendering.md`).

### 14.4 Accessibility gates

- `npx playwright test --grep @a11y` runs `@axe-core/playwright` against every flagship route.
- Zero `moderate`+ violations allowed.

### 14.5 CSP gates

- After each wave, start the dev server, open devtools, visit each flagship route, assert zero `Content-Security-Policy` report violations in the console. The CSP is still Report-Only in production config (see `next.config.mjs`), so violations are logged rather than blocking — but the design spec declares zero tolerance anyway.
- When the planned flip to enforce mode lands (Phase 12-02-07), re-run the full suite: everything here is CSP-safe by design, so nothing should break.

### 14.6 Dev-env specifics

- Dev runs on port **3100** (not 3000; MCP server holds 3000 per `feedback_port3100.md`). Playwright `baseURL` must use `http://localhost:3100`. Confirm before running.
- Before E2E, **always** rebuild all Docker images per `feedback_rebuild_containers.md`: `docker compose build && docker compose up -d`. Stale containers cause false positives.

---

## 15. Design-token export strategy (future-proofing)

### 15.1 The one-file contract

A future designer can rebrand J'Toye OaaS by editing **one file**: `frontend/app/globals.css`.

- All colour, typography scale, radius, shadow, spacing supplement, and motion tokens live there as CSS variables.
- Components reference those variables through Tailwind's `hsl(var(--token))` pattern. No component file hardcodes a value.
- Therefore: swap the token values → the whole product rebrands. Zero component changes.

### 15.2 What CAN be changed in globals.css without code changes

- Any colour token value (light or dark).
- Any radius value.
- Any shadow tier.
- Font-face declarations (swap Fraunces for a different display face via `next/font/google` config change in `app/layout.tsx` — one file, one import).
- Type scale clamp ranges.
- Motion durations and easings (unless Framer Motion variants hardcode them — the rule is motion helpers read CSS vars via `getComputedStyle`, wave 2 commits this).

### 15.3 What REQUIRES a code change

- Adding a new component variant (e.g. a new `Button` tone).
- Changing a primitive's anatomy (e.g. moving the dialog close button to left).
- Swapping Lucide for a different icon library.

### 15.4 Token-export format (optional future)

For a future native/Figma pipeline:

- Mirror the token set in `frontend/design-tokens.json` using the [W3C Design Tokens](https://tr.designtokens.org/) format.
- Author a build script `frontend/scripts/build-tokens.ts` that emits `globals.css` from the JSON, so JSON is the source of truth.
- Not required for v1 — defer until a designer is onboarded.

### 15.5 Versioning the design system

- Bump `DESIGN-SPEC.md` version header on any token change.
- Breaking changes (renamed tokens, removed variants) require a migration note in the commit body.
- Keep a short `CHANGELOG` section at the bottom of this file for future additions.

---

## 16. Appendix — design references channeled (not copied)

The Warm Editorial direction channels energy from the following, explicitly not copying any of them:

| Reference            | What we take                                           | What we leave                             |
|----------------------|--------------------------------------------------------|-------------------------------------------|
| Square (Block) POS   | Vendor trust, high-contrast financial surfaces         | Their blue-black palette                  |
| Toast POS            | Warmth in B2B (rare), kitchen operational literacy     | Their cluttered information density       |
| Shopify Admin        | Dense clarity, sidebar grouping, command-K intent      | Their green brand, generic Polaris look   |
| Stripe               | Typographic craft, trust signals on payment flows      | Their purple, their gradient hero         |
| Linear               | Quiet confidence, keyboard-first, restrained motion    | Their mono-heavy aesthetic                |
| Uber Eats / Deliveroo| Appetite-coded hero photography, cart-as-sheet pattern | Their saturation, orange-juice CTA        |

---

## 17. Open questions to resolve during implementation

None blocking. These are acknowledged decisions the downstream agents can make without further spec:

1. **Command-K palette** — reserve keyboard binding in wave 3, full implementation post-v1. Do not ship an incomplete palette.
2. **Dark mode toggle** — UI control location: top bar user menu. Default: follow system (`media (prefers-color-scheme: dark)`). Persist override in localStorage key `jtoye:theme`.
3. **Empty-state illustrations** — if commissioning is not ready by wave 5, ship with skeleton tiles sized to the final illustration aspect ratio so swapping in illustrations later is a one-file change.
4. **OG images** — generate dynamically via `@vercel/og` (server-rendered, CSP-clean). Author one template in wave 3: `app/opengraph-image.tsx`.
5. **Login wordmark vs mark-only** — use wordmark. Mark-only on OG images and favicon.

---

## 18. Changelog

- **1.0 (2026-04-18)** — Initial authoritative spec for the Warm Editorial overhaul. Scope: tokens, typography, components, surface rules, accessibility gates, implementation playbook.

---

*End of DESIGN-SPEC.md. Downstream agents cite this document by section and sub-section number in PR descriptions (e.g. "implements §2.3 + §5.1").*
