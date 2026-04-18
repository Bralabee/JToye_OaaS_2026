# Handoff: Frontend Design System Overhaul — Warm Editorial

**Generated**: 2026-04-19 (session wrap-up)
**Branch**: `feature/design-system-overhaul`
**Worktree**: `/home/sanmi/IdeaProjects/JToye_OaaS_2026-design/` (use this path, not the primary repo — a parallel session is working on `feature/phase-14-stock-race-summary-aggregation` in the primary repo `/home/sanmi/IdeaProjects/JToye_OaaS_2026/`)
**Status**: All 8 implementation waves committed on the design branch. `next build` passes end-to-end. 84/84 jest tests pass. 0 new tsc errors. Ready for PR after visual QA on running dev stack.

---

## Current goal

Holistic aesthetic overhaul of the Next.js 16 frontend from stock shadcn defaults to a **Warm Editorial** design system for the J'Toye OaaS multi-tenant UK food-retail SaaS, delivered methodically with zero breaking changes and full regression-gate passing.

Direction: warm premium + utility-dense hybrid channeling Square/Toast/Stripe/Linear. Fig primary + Ink Olive secondary + Mustard editorial accent. Fraunces + Inter + Geist Mono typography.

---

## Completed work (this session)

### Design branch commits (top → bottom, all on `feature/design-system-overhaul`)

| SHA | Wave | Scope |
|-----|------|-------|
| `114ecc5` | Wave 6 fix | `Fraunces` variable font config (dropped weight array; axes requires variable weight) |
| `d5c57b7` | Wave 5 | Light pass on remaining 11 surfaces + pagination primitive + product-detail-modal migration |
| `4e5d0e5` | Wave 4A | Storefront flagship: `/shop`, `/shop/[slug]`, `/track` + `BrandPlaceholder` component |
| `5cbeff0` | Wave 4B | Dashboard home with KPI sparklines (Recharts) + orders Table + alerts card |
| `1f559aa` | Wave 3 | Shells: dashboard sidebar, storefront nav, auth signin, root layout |
| `fa06bc9` | Wave 2B | Motion helpers (`lib/motion.ts`) + 7 primitive rebuilds (button/card/input/badge/dialog/select/table) |
| `ad2c5fd` | Wave 2A | Bespoke brand kit: mark, wordmark, OG cards, favicons, BRAND constant |
| `447f069` | Wave 1 | Warm Editorial tokens (globals.css) + typography (fonts.ts, fraunces+inter+geistMono) + tailwind.config.ts extensions |
| `e49fd61` | Research | DESIGN-SPEC.md (1160 lines) + ai-agent-tooling-2026-04-18.md (477 lines) |

Ancestor: `009dbc7` Phase 13 on `main` (pre-existing).

### Deliverables by file

**Foundation (Wave 1):**
- `frontend/app/fonts.ts` — Fraunces + Inter + Geist Mono via `next/font/google`, exported as `fontVariables` string + individual font objects.
- `frontend/app/globals.css` — 343 lines, full Warm Editorial token suite (light + dark): surface, ink, brand, accent, semantic (success/warning/danger/info), border, shadow tiers (subtle/lift/float/bloom), radius scale, tracking/leading maps, font-feature-settings for Inter (cv05/cv11/ss01/calt) and tabular-nums for `.font-mono`.
- `frontend/tailwind.config.ts` — 224 lines, extended: `fontFamily.{display,sans,mono}` via CSS vars, fontSize fluid scale, letterSpacing/lineHeight maps, borderRadius scale, boxShadow tiers, `colors.{surface,ink,brand,accent-editorial,success,warning,danger,info}` via `hsl(var(--...))`. Legacy shadcn tokens kept as aliases — zero consumer migration needed.

**Brand (Wave 2A):**
- `frontend/public/brand/mark.svg` (32×32, 410B) — J-apostrophe slab monogram, currentColor
- `frontend/public/brand/mark-dark.svg` (528B) — fig-background variant for OG
- `frontend/public/brand/wordmark.svg` (180×40, 1245B) — editorial slab wordmark
- `frontend/public/brand/wordmark-with-oaas.svg` (240×48, 2374B) — full product lockup
- `frontend/public/brand/og-default.svg` + `og-storefront.svg` (1200×630, ~2.5KB each)
- `frontend/public/favicon.svg` + `apple-touch-icon.svg`
- `frontend/public/brand/README.md` — swap-contract documentation
- `frontend/lib/brand.ts` — `BRAND` constant with `name`, `product`, `fullName`, `tagline`, `shortTagline`, `description`, `marks.{icon,iconDark,wordmark,wordmarkWithProduct,og,ogStorefront}`

**Motion + primitives (Wave 2B):**
- `frontend/lib/motion.ts` — `EASE` object (standard/emphasized/spring), `DURATION` map (fast..slowest), Framer Motion variants (`fadeUp`, `fadeIn`, `scaleFade`, `listStagger`, `listItem`, `navUnderline`), `useReducedMotionSafe()` hook returning null-op variants when `prefers-reduced-motion`
- `frontend/components/ui/button.tsx` — 8 variants (+primary/editorial/subtle), 5 sizes, `isLoading`, hover lift, focus-visible 2px ring, `asChild` preserved
- `frontend/components/ui/card.tsx` — 4 variants (default/flat/lifted/inset), CardTitle in font-display
- `frontend/components/ui/input.tsx` — sizes sm/md/lg, tone variants, `invalid` prop, `Omit<..., "size">` for HTML compat
- `frontend/components/ui/badge.tsx` — 8 semantic tones
- `frontend/components/ui/dialog.tsx` — tailwind-animate scaleFade content, warm scrim overlay, `motion-reduce:animate-none`
- `frontend/components/ui/select.tsx` — input-matched sizing, scaleFade content
- `frontend/components/ui/table.tsx` — `numeric` cell prop (tabular-nums + font-mono + right-align), overline headers, subtle row hover

**Shells (Wave 3):**
- `frontend/components/dashboard/sidebar.tsx` — purged slate/blue/purple; BRAND wordmark lockup, fig-tinted avatar, ink nav with brand-primary active rail (Framer `layoutId` shared-element), `WORKSPACE`/`OPERATIONS` section overlines, ghost-button tray for theme+signout
- `frontend/components/dashboard/dashboard-shell.tsx` — `bg-surface-canvas`, `max-w-[90rem]` container, fadeUp page enter
- `frontend/components/storefront/storefront-nav.tsx` — ink hierarchy tokens, navUnderline hover, primary pill CTA, success-tinted status pill
- `frontend/app/auth/signin/page.tsx` — lifted Card on `bg-brand-primary/5` canvas, BRAND mark SVG replaces generic icon, font-display title, scaleFade enter
- `frontend/app/layout.tsx` — BRAND-driven metadata (title/description/OG/Twitter), `bg-surface-canvas text-ink-primary antialiased` on body
- `frontend/app/auth/signin/__tests__/page.test.tsx` — minimal selector sync (6 cases, no coverage loss)
- `frontend/jest.setup.js` — Proxy-based framer-motion mock supporting any `motion.*` tag + `AnimatePresence` + reduced-motion hooks

**Flagship pages (Wave 4):**
- `frontend/app/shop/page.tsx` — editorial hero with BRAND tagline, search + category pills, 4-col shop card grid with BrandPlaceholder banner fallback, listStagger enter
- `frontend/app/shop/[slug]/page.tsx` — 21:9 banner (single legibility gradient — the only approved exception), overlapping header Card, sticky backdrop-blur category sub-nav, product Card grid with font-mono pricing, Dialog-based hours disclosure with Table
- `frontend/app/track/page.tsx` — compact hero, Input-based lookup, result Card with horizontal/vertical progress stepper (Framer fill), numeric Table item list
- `frontend/app/dashboard/page.tsx` — greeting in font-display, 4-card KPI row with Recharts sparklines (brand-primary stroke + /10 fill), 2/3 recent-orders Table + 1/3 top-customers/alerts stack, page-level fadeUp staggered children
- `frontend/components/storefront/brand-placeholder.tsx` — aspect-aware fallback, `bg-surface-muted` + mark.svg at 30% opacity

**Light pass (Wave 5):**
- `frontend/app/shop/layout.tsx` — surface-canvas root, backdrop-blur-sm header, BRAND wordmark replaces orange J
- `frontend/components/storefront/product-detail-modal.tsx` — migrated to Dialog primitive, font-display title, font-mono price, BrandPlaceholder fallback
- `frontend/app/shop/orders/page.tsx` — Card lifted per order, semantic status Badges, Table item rows
- `frontend/app/page.tsx` — untouched (server-side redirect only; no UI to enhance)
- `frontend/app/dashboard/customers/page.tsx` — Table + Input + Select filter bar
- `frontend/app/dashboard/finance/page.tsx` — KPI row + transactions Table, `?export=1` auto-triggers client-side CSV (page wrapped in `<Suspense>` per `useSearchParams` requirement)
- `frontend/app/dashboard/products/page.tsx` — Card grid with semantic stock Badges; violet→`accent-editorial` for AI suggestions panel
- `frontend/app/dashboard/kitchen/page.tsx` — 48px touch targets, loud Badge status, age-border semantics; bump-button optimistic-update + audio beep preserved
- `frontend/app/dashboard/shops/page.tsx` — lifted Card grid with BrandPlaceholder banner fallback
- `frontend/app/dashboard/orders/page.tsx` — Table view; `?new=1` auto-opens create Dialog; `?status=<STATUS>` pre-seeds filter
- `frontend/app/dashboard/marketing/page.tsx` — 4-tile grid (Announcements/Promotions/Reviews/Insights)
- `frontend/components/ui/pagination.tsx` — purged slate-600 residue, ghost icon buttons, badge-style current page

### Quality gates passed

| Gate | Command | Result |
|------|---------|--------|
| Typecheck | `npx tsc --noEmit` (grep-filtered `__tests__` + `.test.`) | **0 new errors** (36 baseline errors in `__tests__` all pre-existing, unrelated to design work) |
| Unit tests | `npm test` | **15 suites, 84 tests, 1 snapshot — all pass** (2.9s) |
| Production build | `npx next build` | **All 22 routes compile cleanly** — 7 static, 15 dynamic (including API routes + middleware) |
| ESLint | `npm run lint` | **Skipped** — `next lint` was removed from Next.js 16; script is broken at baseline (not a design-work regression) |

### Specific functional behavior verified preserved

- NextAuth v5 signin flow — `signIn("keycloak", ...)` call + callback URL unchanged
- Customer session polling (focus/visibility/storage listeners + 5s post-mount interval) in StorefrontNav unchanged
- Cart add/remove/quantity via `useCart`, `addItem`, `updateQuantity` unchanged
- Product detail modal prop surface (`onAdd`/`onIncrement`/`onDecrement`/`onClose`/`quantity`) unchanged
- Hours disclosure data source (`shop.openingHours` record) unchanged
- Search endpoint `/public/shops?q=` + order lookup `/public/orders/:orderNumber?email=` with 15s auto-refresh unchanged
- KDS bump-button optimistic update + audio beep unchanged
- All `data-testid` attributes preserved (no test weakening anywhere)

---

## Remaining work

### Before PR opens (blocking for merge)

1. **Visual QA on running dev stack** (manual, ~30 min). Dev stack must be booted; see `docker-compose.yml`. Then:
   - Boot frontend: `cd frontend && npm run dev` on port 3100 (MCP holds 3000)
   - Boot backend + deps: `docker-compose up -d postgres redis keycloak rabbitmq minio core-java edge-go`
   - Manually walk through: `/`, `/auth/signin`, `/shop`, `/shop/{any-slug}`, `/dashboard`, `/dashboard/kitchen`, `/track`
   - Check mobile breakpoint via DevTools responsive mode (390×844 + 1440×900 to match Playwright projects)
   - Verify: dark mode toggle in dashboard sidebar still works; CSP doesn't block `next/font/google` (should work — fonts are self-hosted to `/_next/static/media/`); images load with natural width > 0 (per `feedback_image_rendering.md` memory)

2. **Playwright regression sweep** (requires running stack). Existing suites in `frontend/e2e/`:
   - `csp-no-violations.spec.ts` — confirms CSP doesn't fire on main flows (critical for design work)
   - `kitchen-flow.spec.ts` — KDS interaction
   - `stomp-relay.spec.ts` — real-time order updates
   - `storefront-flows.spec.ts` — customer shopping flow
   
   ```bash
   cd frontend
   PLAYWRIGHT_BASE_URL=http://localhost:3100 npx playwright test
   ```
   Expected: all pass. Mobile + desktop projects run automatically.

3. **`metadataBase` warning** (non-blocking but cleanup candidate). In `frontend/app/layout.tsx` metadata:
   ```ts
   metadataBase: new URL(process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3100"),
   ```
   This resolves the "using http://localhost:3000" warning on social card images.

### Nice-to-have (can be follow-ups)

- **`tweakcn`-driven palette refinement** (optional): feed a vendor food photograph into https://tweakcn.com/ai and iterate Wave 1 `globals.css` tokens. Current palette is hand-authored; a photographed reference could refine warmth.
- **Storybook + Chromatic VRT** (per research doc `ai-agent-tooling-2026-04-18.md` §4): component-level visual regression once UI stabilizes.
- **Next.js 16 `next lint` fix**: the stock `next lint` command was removed; either flip `package.json` script to `eslint . --ext .ts,.tsx` with a flat `eslint.config.mjs`, or use `@next/eslint-plugin-next` directly. Unrelated to design work but surfaced during regression gate.
- **36 baseline tsc errors in `__tests__`**: all from missing `@testing-library/jest-dom` type augmentation. Add `/// <reference types="@testing-library/jest-dom" />` or import `'@testing-library/jest-dom'` in a `jest-dom.d.ts`. Unrelated to design work.
- **Ancestor commit `255ccf2`** on this branch is actually a phase-14 plan commit that drifted in during worktree setup. Harmless (only touches `.planning/`) but can be rebased out pre-merge via `git rebase -i 009dbc7` and dropping that commit if a clean history is wanted.

### Deeper polish deferred

- **Root `/` page**: currently a pure server-side redirect; no UI. Could become a marketing splash per DESIGN-SPEC §10.1 with BRAND tagline + two CTAs. Left for a future sketch.
- **`/dashboard/products/import`**: Wave 5 didn't touch this nested page (it was out of scope); inherits tokens but may benefit from layout pass.
- **`/shop/[slug]/cart`, `/checkout`, `/orders/[orderNumber]`**: nested storefront routes. Tokens inherit automatically but no explicit light pass applied. Verify visually.
- **Dashboard loading spinners**: kept `border-blue-600` class on loading states because `dashboard/products/__tests__` pins that class via `toHaveClass('border-blue-600')`. Harmless (blue-600 is closer to the new brand palette than not) but swap to `border-brand-primary` when you also update that test.

---

## Key decisions with rationale

| Decision | Rationale |
|---|---|
| Isolated git worktree at `…-design/` | Primary repo had a parallel Claude session actively committing to `feature/phase-14-stock-race-summary-aggregation`. Each fresh shell inherited that branch's HEAD. Worktree bypassed the contention. |
| Warm Editorial over Stripe-like cool blue | Food retail needs appetite + trust + editorial craft, not fintech coldness. Channels Square/Toast/Stripe typography/Linear quiet confidence without copying any. |
| `weight: "variable"` for Fraunces (not specific weights) | Next.js 16 rejects `axes` + specific `weight` array. Variable weight loads all 100–900 anyway; globals.css already sets explicit `font-weight` per type-scale step. |
| `class-variance-authority` for primitive variants | Already in shadcn dep tree; canonical pattern; zero new deps. |
| Framer Motion `motion.div + scaleFade` via tailwindcss-animate classes in Dialog | Radix `forceMount` pattern has SSR fragility; data-state-driven `zoom-in-95 fade-in-0 duration-moderate` is semantically identical and respects `motion-reduce:animate-none`. Motion helpers remain available to page-level consumers that want `motion.div` directly. |
| `BrandPlaceholder` replaces all null-image fallbacks | Spec §8 forbids gradient/grey placeholders. BrandPlaceholder keeps brand presence in empty states. Aspect-prop-aware for card vs modal use. |
| Legacy shadcn tokens kept as aliases | Zero consumer migration needed. Existing `bg-primary`, `text-foreground`, `bg-background` etc. still resolve — they now point at Warm Editorial values. |
| One-file swap contract for future designers | `frontend/app/globals.css` is the single palette file. Overwrite token values → every component updates. No code changes required. Documented in `frontend/public/brand/README.md` and `DESIGN-SPEC.md §15`. |
| No runtime CSS-in-JS for theme | CSP enforces `style-src 'self' 'unsafe-inline'` only for `next/font` injected stylesheets. All colour changes go through CSS vars; dark mode via `.dark` class on `<html>`. |

---

## Failed approaches (and why)

| Approach | Why it failed |
|---|---|
| Working in primary repo at `/home/sanmi/IdeaProjects/JToye_OaaS_2026/` | A parallel Claude session was actively committing to `feature/phase-14-stock-race-summary-aggregation` in the same filesystem. Each fresh Bash shell inherited phase-14 HEAD regardless of my explicit `git checkout`. **Solution**: created isolated worktree. |
| Fraunces with explicit weight array + axes | Next.js 16 Turbopack errored: "Axes can only be defined for variable fonts when the weight property is nonexistent or set to 'variable'". **Solution**: dropped `weight:` array; Fraunces is a variable font so all weights load. |
| `next lint` as a regression gate | Next.js 16 removed the `next lint` command; stock script parses "lint" as a path. **Solution**: skipped; flagged as infra cleanup task. |
| Writing HANDOFF.md at repo root as `HANDOFF.md` | Would collide with existing root `HANDOFF.md` from v2.2 scoping session. **Solution**: wrote to `.planning/research/HANDOFF-DESIGN-OVERHAUL.md`. |

---

## Environment state

- **Worktree**: `/home/sanmi/IdeaProjects/JToye_OaaS_2026-design/`
- **Branch**: `feature/design-system-overhaul`
- **Most-recent commit**: `114ecc5 fix(design): Fraunces variable font cannot combine explicit weights with axes`
- **Branch ancestry**: `114ecc5` → `d5c57b7` → `4e5d0e5` → `5cbeff0` → `1f559aa` → `fa06bc9` → `ad2c5fd` → `447f069` → `e49fd61` → `255ccf2` (stray phase-14 plan) → `009dbc7` (main)
- **Working tree**: clean (verified post-commit)
- **Node version**: Node 20+ (per CLAUDE.md)
- **Package manager**: npm (via `package-lock.json`; no pnpm/yarn)
- **Dev port**: 3100 (MCP server holds 3000)
- **Docker-compose services expected**: postgres (5432 — watch for conflict with `dealflow_*` containers per memory), redis, keycloak, rabbitmq, minio, core-java (Spring Boot), edge-go (Gin)
- **No running services** at session end

---

## Resume instructions

### For the human (next session / PR owner)

1. **Switch to the worktree**:
   ```bash
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-design
   git status   # should be clean
   git log --oneline -10   # should show Wave 1..5 + font fix
   ```

2. **Boot dev stack**:
   ```bash
   # If you haven't already:
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026   # (main repo — docker-compose lives there)
   docker-compose up -d postgres redis keycloak rabbitmq minio core-java edge-go
   
   # Then from the design worktree:
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-design/frontend
   PORT=3100 npm run dev
   ```
   Expected: Next.js ready on http://localhost:3100 in ~5s.

3. **Run Playwright**:
   ```bash
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-design/frontend
   PLAYWRIGHT_BASE_URL=http://localhost:3100 npx playwright test
   ```
   Expected: 4 suites pass on mobile + desktop. If any fail, screenshots/traces are in `playwright-report/`.

4. **Visual click-through** (per `feedback_e2e_click_through.md`):
   - Sign in via Keycloak → should land in `/dashboard` with new sidebar + KPI cards
   - Click "Kitchen" → KDS page with loud Badges
   - Navigate to `/shop` (unauthenticated) → editorial hero with BRAND tagline
   - Click into any shop → 21:9 banner + product cards
   - Open product modal → Dialog primitive with font-display title + font-mono price
   - Try `/track` → stepper with Framer fill
   - Toggle dark mode in sidebar → every token flips via CSS vars

5. **Open PR**:
   ```bash
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-design
   git push -u origin feature/design-system-overhaul
   gh pr create --title "feat(design): Warm Editorial frontend overhaul" \
     --body-file /home/sanmi/IdeaProjects/JToye_OaaS_2026-design/.planning/research/HANDOFF-DESIGN-OVERHAUL.md
   ```

### For a fresh Claude session resuming this work

Paste this into the new session:

```
Resuming frontend design system overhaul. Context:
- Worktree: /home/sanmi/IdeaProjects/JToye_OaaS_2026-design/
- Branch: feature/design-system-overhaul
- All 8 waves committed. `next build` passes. 84/84 jest pass. 0 new tsc.
- Read .planning/research/HANDOFF-DESIGN-OVERHAUL.md for the full state.
- Read .planning/research/DESIGN-SPEC.md for design authority.
- Do NOT cd into /home/sanmi/IdeaProjects/JToye_OaaS_2026/ — parallel session active there.
- Remaining: visual QA on running dev stack + Playwright sweep + PR.
```

---

## Tests / build artifacts

- `frontend/.next/` — production build output (gitignored). Re-produce via `npx next build` in the design worktree.
- `frontend/playwright-report/` — last Playwright HTML report location (may be empty; no e2e run this session).
- `frontend/test-results/` — last Playwright raw traces.
- Jest: `15 suites, 84 tests, 1 snapshot` — green (2.9s).
- tsc baseline: `36` errors in `__tests__/*` pre-existing (all `toBeInTheDocument`/`toHaveClass` missing-type), design-work errors: `0`.

---

## References

- Design authority: `.planning/research/DESIGN-SPEC.md` (1160 lines)
- Ecosystem research: `.planning/research/ai-agent-tooling-2026-04-18.md` (477 lines)
- Visual standard memories: `/home/sanmi/.claude/projects/-home-sanmi-IdeaProjects-JToye-OaaS-2026/memory/feedback_ui_quality.md`, `feedback_e2e_click_through.md`, `feedback_image_rendering.md`, `feedback_rebuild_containers.md`, `feedback_port3100.md`
