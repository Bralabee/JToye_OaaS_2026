# Handoff: Frontend Design System Overhaul — Warm Editorial

**Generated**: 2026-04-19 (session wrap-up, v2 — follow-ups resolved)
**Branch**: `feature/design-system-overhaul` (13 clean atomic commits on top of `main`)
**Worktree**: `/home/sanmi/IdeaProjects/JToye_OaaS_2026-design/` (use this path, not the primary repo — a parallel session ships phase-14/15/16 work in the primary repo `/home/sanmi/IdeaProjects/JToye_OaaS_2026/`)
**Status**: All 8 implementation waves + 2 follow-up rounds committed. `next build` passes end-to-end. 84/84 jest tests pass. **0 tsc errors** (was 36 baseline), **0 lint errors** (was broken baseline). Ready for PR pending visual QA on running dev stack.

---

## Current goal

Holistic aesthetic overhaul of the Next.js 16 frontend from stock shadcn defaults to a **Warm Editorial** design system for the J'Toye OaaS multi-tenant UK food-retail SaaS, delivered methodically with zero breaking changes and full regression-gate passing.

Direction: warm premium + utility-dense hybrid channelling Square/Toast/Stripe/Linear. Fig primary + Ink Olive secondary + Mustard editorial accent. Fraunces + Inter + Geist Mono typography.

---

## Completed work (this session)

### Design branch commits — 13 clean atomic commits on top of main

Top → bottom (newest first):

| SHA | Type | Scope |
|-----|------|-------|
| `0984cca` | fix | Tighten `csp-headers.test.ts` type alias so tsc indexes routes[0].source + .headers.find |
| `3846bfb` | Wave 7 | Polish 4 nested routes to Warm Editorial (products/import, cart, checkout, orders/[orderNumber]) |
| `9cf4523` | feat | Token cleanup — border-blue-600 → border-brand-primary + auth callback + landing splash |
| `d8c5101` | chore | Infra cleanup — flat ESLint + jest-dom types + metadataBase |
| `d492569` | docs | HANDOFF-DESIGN-OVERHAUL — full state, deliverables, resume instructions |
| `b4c2447` | fix | Fraunces variable font cannot combine explicit weights with axes |
| `253be77` | Wave 5 | Light pass on remaining 10 surfaces + pagination primitive + product-detail-modal |
| `519e0d8` | Wave 4A | Storefront flagship — /shop, /shop/[slug], /track + BrandPlaceholder |
| `296d7b4` | Wave 4B | Dashboard home — KPI sparklines (Recharts) + orders Table + alerts |
| `b75c784` | Wave 3 | Shells — dashboard sidebar, storefront nav, auth signin, root layout |
| `43031a0` | Wave 2B | Motion helpers (`lib/motion.ts`) + 7 primitive rebuilds |
| `208266c` | Wave 2A | Bespoke brand kit — mark, wordmark, OG cards, favicons, BRAND constant |
| `c565a3b` | Wave 1 | Warm Editorial tokens + typography foundation |

**Ancestor on main**: `0f4c26f Phase 16: Go Edge OpenAPI + Swagger UI (DOC-01) (#48)` (main moved during this session — phases 14, 15, 16 were merged in parallel).

**Note**: The earlier stray ancestor `255ccf2 plan(phase-14)` was rebased out. `e49fd61 docs(design): AI tooling research + DESIGN-SPEC` was also dropped by rebase because identical patch contents landed on main via the phase-14 PR merge — so the DESIGN-SPEC.md + ai-agent-tooling-2026-04-18.md research docs are already on main and NOT duplicated in this branch.

### Deliverables by file (unchanged from v1; plus post-handoff follow-ups below)

See the v1 HANDOFF section above for the full per-file inventory of Waves 1–5. Added in follow-up rounds:

**Follow-up 1 — Infrastructure cleanup (commit `d8c5101`):**
- `frontend/eslint.config.mjs` — new flat-config (ESLint 9 + eslint-config-next v16 native exports). Overrides disable `no-require-imports` on jest.config.js / jest.setup.js (legitimate CJS).
- `frontend/.eslintrc.json` — deleted (legacy).
- `frontend/package.json` — `lint` script now `eslint .`
- `frontend/types/jest-dom.d.ts` — new; imports `@testing-library/jest-dom` to register matcher types globally. Clears all 36 baseline tsc errors.
- `frontend/tailwind.config.ts` — eslint-disable-next-line with rationale for legitimate `require('tailwindcss-animate')` (CJS at build time).
- `frontend/__tests__/csp-headers.test.ts` + `header-snapshot.test.ts` — replaced `any` casts with proper typed imports of next.config.mjs (HeaderRoute type alias).
- `frontend/app/layout.tsx` — added `metadataBase: new URL(process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3100")` to resolve OG/Twitter social-card URLs.
- `frontend/components/dashboard/sidebar.tsx` — surgical `eslint-disable-next-line react-hooks/set-state-in-effect` on theme-hydration-from-localStorage (legitimate mount-once pattern).
- `frontend/components/storefront/storefront-nav.tsx` — same for Keycloak session hydration.
- `frontend/components/storefront/__tests__/cart-provider.test.tsx` — same for legitimate test-harness setState closure capture.

**Follow-up 2 — Design token cleanup + missing surfaces (commit `9cf4523`):**
- All 10 dashboard loading spinners: `border-blue-600` + `text-blue-600` → `border-brand-primary` + `text-brand-primary` (sed batch).
- `frontend/app/dashboard/products/__tests__/page.test.tsx` — test selector updated to assert `border-brand-primary` (no coverage loss; the test still pins the exact class so regressions remain visible).
- `frontend/app/shop/auth/callback/page.tsx` — full Warm Editorial rewrite: `bg-surface-canvas`, `<Link>` instead of `<a href>` (fixes no-html-link-for-pages lint errors), `<Button variant="primary">` back-to-shops CTA, `text-brand-primary` spinner, `motion-reduce` safe.
- `frontend/app/page.tsx` — replaced server-side redirect with a real landing splash. Hero with BRAND tagline + "One kitchen." brand-accented final phrase, two-CTA layout (Browse shops primary / Vendor sign-in secondary), 3-feature strip (Storefronts / Kitchen Display / Trust by default) on surface-subtle background, quiet footer.

**Follow-up 3 — Nested route polish (commit `3846bfb`, delegated subagent):**
- `frontend/app/dashboard/products/import/page.tsx` (443 lines) — Warm Editorial tokens, CSV upload preserved, violet → `accent-editorial` mustard for AI suggestions.
- `frontend/app/shop/[slug]/cart/page.tsx` (190 lines) — Card lifted, font-mono tabular prices, BrandPlaceholder fallback, `rounded-pill shadow-lift` primary CTA.
- `frontend/app/shop/[slug]/checkout/page.tsx` (557 lines) — Input tone=brand, lifted order summary, **Stripe appearance** aligned with Warm Editorial palette (theme=flat, `colorPrimary: hsl(1,35%,42%)` Fig, `colorText: hsl(30,10%,16%)` Ink, `colorBackground: hsl(32,30%,99%)` Surface, `colorDanger: hsl(358,55%,45%)`, Inter fontFamily, 10px radius — intentional HSL hardcode at API boundary since Stripe doesn't accept CSS vars).
- `frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx` (356 lines) — semantic status Badge, font-mono order number, Table item rows, stepper consistent with /track.

**Follow-up 4 — tsc tightening (commit `0984cca`):**
- `frontend/__tests__/csp-headers.test.ts` — type alias `HeaderRoute` replaces the too-loose `Promise<unknown[]>` so the 7 downstream index accesses (`routes[0].source`, `routes[0].headers.find(...)`) resolve cleanly. Zero runtime impact.

### Quality gates — all green post-rebase

| Gate | Command | Result |
|------|---------|--------|
| **Typecheck** | `npx tsc --noEmit` | **0 errors** (was 36 baseline — matcher types now registered via jest-dom.d.ts) |
| **Lint** | `npm run lint` | **0 errors, 11 warnings** (was broken via `next lint` removal; warnings are pre-existing `<img>` + unused-vars, not introduced by design work) |
| **Unit tests** | `npm test` | **15 suites, 84 tests, 1 snapshot — all pass** (3.0s) |
| **Production build** | `npx next build` | **All 22 routes compile cleanly** — 7 static (`/`, `/_not-found`, `/auth/signin`, `/shop`, `/shop/auth/callback`, `/shop/orders`, `/track`), 15 dynamic (API + dashboard + shop/[slug] variants), middleware |

### Functional behavior verified preserved

- NextAuth v5 signin flow — `signIn("keycloak", ...)` + callback URL unchanged
- Customer session polling (focus/visibility/storage + 5s post-mount interval) in StorefrontNav unchanged
- Cart add/remove/quantity via `useCart`, `addItem`, `updateQuantity` unchanged
- Product detail modal prop surface unchanged
- Hours disclosure data source (`shop.openingHours`) unchanged
- Search endpoint `/public/shops?q=` + order lookup `/public/orders/:orderNumber?email=` with 15s auto-refresh unchanged
- KDS bump-button optimistic update + audio beep unchanged
- Stripe Elements payment flow — config preserved; only visual appearance aligned
- CSV import flow (validation, progress, column mapping) unchanged
- All `data-testid` attributes preserved

---

## Remaining work

### Blocking for PR merge (manual — require running stack)

1. **Visual QA on running dev stack** (~30 min, manual):
   ```bash
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026   # main repo — docker-compose lives there
   docker-compose up -d postgres redis keycloak rabbitmq minio core-java edge-go
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-design/frontend
   PORT=3100 npm run dev
   ```
   Walk through: `/`, `/auth/signin`, `/shop`, `/shop/{slug}`, `/dashboard`, `/dashboard/kitchen`, `/track`, `/shop/[slug]/cart`, `/shop/[slug]/checkout`. Verify dark mode toggle in sidebar still works; CSP doesn't block `next/font/google` (should work — self-hosted); images load with `naturalWidth > 0` (per `feedback_image_rendering.md`).

2. **Playwright regression sweep** (requires running stack):
   ```bash
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-design/frontend
   PLAYWRIGHT_BASE_URL=http://localhost:3100 npx playwright test
   ```
   Four existing suites on mobile + desktop projects: `csp-no-violations.spec.ts`, `kitchen-flow.spec.ts`, `stomp-relay.spec.ts`, `storefront-flows.spec.ts`. Expected: all pass.

3. **Open PR**:
   ```bash
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-design
   git push -u origin feature/design-system-overhaul
   gh pr create --title "feat(design): Warm Editorial frontend overhaul" \
     --body-file .planning/research/HANDOFF-DESIGN-OVERHAUL.md
   ```

### Resolved in this round (was "remaining" in v1 HANDOFF)

- ~~`metadataBase` warning~~ — fixed; env-driven with localhost:3100 fallback
- ~~36 baseline tsc errors~~ — fixed via `types/jest-dom.d.ts`
- ~~`next lint` broken at Next.js 16 baseline~~ — fixed via flat ESLint config
- ~~Stray ancestor commit `255ccf2`~~ — rebased out
- ~~Dashboard loading spinners using `border-blue-600`~~ — swapped to `border-brand-primary` with test sync
- ~~Root `/` page as bare redirect~~ — landing splash shipped
- ~~`/shop/[slug]/cart`, `/checkout`, `/orders/[orderNumber]` not explicitly touched~~ — polished in Wave 7
- ~~`/dashboard/products/import` not touched~~ — polished in Wave 7
- ~~`app/shop/auth/callback/page.tsx` still slate/orange~~ — full Warm Editorial rewrite

### Nice-to-have (genuine followups, not blockers)

- **11 lint warnings**: all pre-existing — `<img>` recommendations (10+) + 1–2 unused-vars. Acceptable; can be cleaned in a separate infra pass.
- **`tweakcn`-driven palette refinement**: feed a vendor food photograph into https://tweakcn.com/ai and iterate Wave 1 `globals.css` tokens. Optional — the hand-authored palette is production-ready as-is.
- **Storybook + Chromatic VRT** (per `ai-agent-tooling-2026-04-18.md` §4): component-level visual regression. Useful once the surface stabilises post-PR.
- **Next.js `<Image>` migration**: replace remaining `<img>` tags with `<Image>` for LCP/bandwidth. Behaviour-sensitive (needs width/height or fill); defer.

---

## Key decisions with rationale

| Decision | Rationale |
|---|---|
| Isolated git worktree at `…-design/` | Primary repo had a parallel Claude session actively committing to `feature/phase-14-stock-race-summary-aggregation`. Each fresh shell inherited that branch's HEAD. Worktree bypassed the contention. |
| Warm Editorial over fintech-cool blue | Food retail needs appetite + trust + editorial craft, not fintech coldness. Channels Square/Toast/Stripe typography/Linear quiet confidence without copying. |
| `weight: "variable"` for Fraunces (not specific weights) | Next.js 16 rejects `axes` + specific `weight` array. Variable weight loads all 100–900; globals.css already sets explicit `font-weight` per type-scale step. |
| Flat ESLint config consuming eslint-config-next/core-web-vitals + typescript | `next lint` was removed in Next.js 16. eslint-config-next v16 ships flat-native exports so migration is trivial — no need for `@next/eslint-plugin-next` direct, no FlatCompat bridge. |
| Surgical `eslint-disable-next-line react-hooks/set-state-in-effect` on 3 legitimate hydration patterns | Theme from localStorage, Keycloak session fetch, test-harness closure capture. Rule is correct in general but these three are single-shot mount-time patterns with no cascade risk. Rationale comments explain why — future-reader-safe. |
| Stripe appearance hardcoded HSL triplets | Stripe's appearance API doesn't accept CSS variables. The only clean way is hardcoding token values at the API boundary, isolated to checkout/page.tsx. Matches `--brand-primary` etc. exactly. |
| Replaced `any` casts in CSP tests with proper type aliases | Once ESLint flat config enabled `no-explicit-any`, the lazy casts surfaced. Clean fix is tightening types rather than silencing the rule — gives future refactors a real contract. |
| Rebased to drop `255ccf2` + `e49fd61` (the latter as patch-dup) | Clean PR history. The `255ccf2 plan(phase-14)` commit drifted into the design branch during worktree creation and carries unrelated phase-14 plan files. The `e49fd61 docs(design)` commit landed on main via phase-14's merge, so it's already upstream — dropping is the correct rebase outcome. |
| `<img>` tags kept as-is in nested routes (not migrated to `<Image>`) | Migrating to Next.js `<Image>` requires width/height props or `fill` layout mode — behaviour-sensitive. Zero-risk design work means leaving them. The 10+ lint warnings are acceptable tech debt flagged for a dedicated infra pass. |
| Landing splash, not redirect | Unauthenticated visitors hitting `/` should see marketing (B2C audience = shoppers). Authenticated vendors already have bookmarks/nav to `/dashboard`. Two-CTA layout serves both audiences cleanly. |

---

## Failed approaches (and why)

| Approach | Why it failed |
|---|---|
| Working in primary repo at `/home/sanmi/IdeaProjects/JToye_OaaS_2026/` | Parallel session's branch switches kept inheriting into my shells. Solved via isolated worktree. |
| Fraunces with explicit weight array + axes | Next.js 16 Turbopack errored: "Axes can only be defined for variable fonts when the weight property is nonexistent or set to 'variable'". Dropped `weight:` array. |
| Initial `any` cast on `next.config.mjs` dynamic import | ESLint + tsc both flagged. Replaced with proper type alias `HeaderRoute`. |
| Initial `Promise<unknown[]>` typing | Too loose — downstream indexing (`routes[0].source`) errored TS2571. Upgraded to typed `HeaderRoute` alias. |
| `next lint` as a regression gate | Next.js 16 removed the command. Migrated to flat ESLint 9. |
| Writing HANDOFF.md at repo root as `HANDOFF.md` | Would collide with existing root `HANDOFF.md` from v2.2 scoping session. Wrote to `.planning/research/HANDOFF-DESIGN-OVERHAUL.md`. |

---

## Environment state

- **Worktree**: `/home/sanmi/IdeaProjects/JToye_OaaS_2026-design/`
- **Branch**: `feature/design-system-overhaul`
- **Most-recent commit**: `0984cca fix(design): tighten csp-headers.test.ts type alias`
- **Branch ancestry**: 13 clean atomic commits on top of `main` tip `0f4c26f` (Phase 16)
- **Working tree**: clean
- **Node version**: Node 20+ (per CLAUDE.md)
- **Package manager**: npm (via `package-lock.json`)
- **Dev port**: 3100 (MCP server holds 3000)
- **No running services** at session end

---

## Resume instructions

### For the human (next session / PR owner)

1. **Switch to worktree**:
   ```bash
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-design
   git status              # should be clean
   git log --oneline -15   # should show 13 design commits on top of main's phase-16 tip
   ```

2. **Boot dev stack** (in primary repo):
   ```bash
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
   docker-compose up -d postgres redis keycloak rabbitmq minio core-java edge-go
   
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-design/frontend
   PORT=3100 npm run dev
   ```

3. **Run Playwright**:
   ```bash
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-design/frontend
   PLAYWRIGHT_BASE_URL=http://localhost:3100 npx playwright test
   ```

4. **Visual click-through** (per `feedback_e2e_click_through.md` memory):
   - Sign in via Keycloak → should land in `/dashboard` with new sidebar + KPI cards
   - Click "Kitchen" → KDS page with loud Badges, 48px touch targets
   - Navigate to `/shop` (unauthenticated) → editorial hero with BRAND tagline
   - Click into any shop → 21:9 banner + product cards, open product modal (Dialog)
   - Add to cart → `/shop/[slug]/cart` view, then `/checkout` with Stripe
   - Try `/track` → stepper with Framer fill
   - Toggle dark mode in sidebar → every token flips via CSS vars

5. **Open PR**:
   ```bash
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-design
   git push -u origin feature/design-system-overhaul
   gh pr create --title "feat(design): Warm Editorial frontend overhaul" \
     --body-file .planning/research/HANDOFF-DESIGN-OVERHAUL.md
   ```

### For a fresh Claude session

```
Resuming frontend design system overhaul.
- Worktree: /home/sanmi/IdeaProjects/JToye_OaaS_2026-design/
- Branch: feature/design-system-overhaul (13 atomic commits)
- tsc: 0 errors. lint: 0 errors. jest: 84/84. next build: 22 routes.
- Read .planning/research/HANDOFF-DESIGN-OVERHAUL.md for full state.
- Read .planning/research/DESIGN-SPEC.md (on main) for design authority.
- Do NOT cd into /home/sanmi/IdeaProjects/JToye_OaaS_2026/ — parallel session active.
- Remaining: visual QA on running dev stack + Playwright sweep + PR.
```

---

## Tests / build artifacts

- `frontend/.next/` — production build output (gitignored). Reproduce via `npx next build`.
- `frontend/playwright-report/` — last Playwright HTML report (empty this session; requires running stack).
- `frontend/test-results/` — last Playwright raw traces.
- Jest: **15 suites, 84 tests, 1 snapshot — green (3.0s)**.
- tsc: **0 errors** (was 36 baseline).
- Lint: **0 errors, 11 warnings** (all pre-existing `<img>` + unused-vars).

---

## References

- DESIGN-SPEC.md — on `main` at `.planning/research/DESIGN-SPEC.md` (1160 lines, merged via phase-14 PR)
- ai-agent-tooling-2026-04-18.md — on `main` at `.planning/research/` (477 lines)
- Visual standard memories: `/home/sanmi/.claude/projects/-home-sanmi-IdeaProjects-JToye-OaaS-2026/memory/feedback_ui_quality.md`, `feedback_e2e_click_through.md`, `feedback_image_rendering.md`, `feedback_rebuild_containers.md`, `feedback_port3100.md`
