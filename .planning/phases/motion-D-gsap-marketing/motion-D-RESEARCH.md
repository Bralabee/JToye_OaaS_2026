# Phase motion-D: GSAP Marketing Scroll Animation — Research

**Researched:** 2026-07-14
**Domain:** Client-side scroll animation (GSAP + ScrollTrigger) on Next.js 16 App Router marketing routes, coexisting with an existing framer-motion/LazyMotion foundation, under a nonce + strict-dynamic CSP.
**Confidence:** HIGH (stack fixed, licensing/versions verified, integration patterns from official GreenSock Context7 + confirmed against the repo's real CSP/SSR constraints)

## Summary

This phase adds a **second, purpose-scoped animation engine** (GSAP 3.15 + ScrollTrigger) to two marketing routes only — `/` (sketch 002 winner B) and `/for-operators` (sketch 003 winner B) — to deliver the "full award-site" scroll choreography the design lead locked on 2026-07-14. GSAP does **not** replace the framer-motion/LazyMotion foundation shipped in PR #220/#221 (Phases A–C); the two engines target different DOM subtrees and coexist cleanly. Everything heavy (split-type headline, parallax, **pinned builds**, **horizontal scrub**, scrubbed counters) is a **desktop-only, non-reduced-motion enhancement**, gated by `gsap.matchMedia("(min-width: 768px) and (prefers-reduced-motion: no-preference)")`. Mobile and reduced-motion users get the Motion-class reveal floor (sketch variant A) — the mandatory, non-negotiable degradation per both sketch decisions.

The single biggest environmental constraint is the repo's **issue-#89 CSP**: `script-src 'self' 'nonce-…' 'strict-dynamic'` with **no `'unsafe-eval'` in production**. This has two consequences that shape the whole build: (1) the sketches loaded GSAP from a CDN, but under `'strict-dynamic'` a CDN host-allowlist is **ignored** — the real build **must bundle GSAP via npm** so Next's already-nonced bootstrap loads it and strict-dynamic propagates trust; (2) GSAP core + ScrollTrigger do **not** use `eval`/`new Function`, so no `'unsafe-eval'` is needed — bundling is CSP-clean. An existing Playwright spec (`frontend/e2e/csp-no-violations.spec.ts`) already asserts `/` fires zero CSP violations; it is the regression guard this phase must keep green.

**Primary recommendation:** Install `gsap@^3.15.0` + `@gsap/react@^2.1.2`. Keep both marketing pages as **Server Components** (preserves the force-dynamic nonce cascade + SEO + no-FOUC). Wrap each animated region in a small `"use client"` enhancer that renders its server-provided children visibly and progressively enhances them with `useGSAP({ scope })` + `gsap.matchMedia()`. Hand-split headline words (no SplitText plugin). Set all "hidden" states **inside** `useGSAP` (client-only) so if JS/GSAP fails the content stays visible. This is the path of least resistance; the two spots that "require care" are the horizontal-scrub pilot rail (`invalidateOnRefresh` + refresh timing) and guaranteeing no-FOUC across the SSR boundary.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Marketing page markup + copy + SEO metadata | Frontend Server (RSC) | — | `app/page.tsx` / `app/for-operators/page.tsx` stay Server Components so the root layout's force-dynamic CSP-nonce cascade is preserved (#89) and content is server-rendered (SEO, no FOUC) |
| Per-request CSP nonce + strict-dynamic header | Frontend Server (middleware) | — | `middleware.ts` already emits it; GSAP must not force any CSP relaxation |
| GSAP scene construction (split, parallax, pin, scrub, horizontal) | Browser / Client | — | GSAP touches the live DOM; runs only in a `"use client"` enhancer via `useGSAP` (layout-effect, never on server) |
| Desktop-only + reduced-motion gating | Browser / Client | — | `gsap.matchMedia()` evaluates media queries in the browser and auto-reverts on breakpoint change |
| Reveal-on-enter floor (mobile / reduced-motion) | Browser / Client | Frontend Server | Existing framer-motion `m.` reveals (variant A) — server renders visible content, client enhances |
| Bundle isolation (GSAP off app routes) | Frontend Server (bundler) | — | Importing the client enhancer only into marketing pages code-splits GSAP into those route chunks automatically |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `gsap` | ^3.15.0 | Animation core + ScrollTrigger plugin (bundled) | Industry-standard scroll/timeline engine; now 100% free incl. all plugins `[CITED: webflow.com/updates/gsap-becomes-free]`. 3.15.0 published 2026-04-13 `[VERIFIED: npm registry]` |
| `@gsap/react` | ^2.1.2 | `useGSAP()` hook — scoped animations + automatic cleanup/revert on unmount, React-19 safe | Official GreenSock React adapter; the canonical defense against ScrollTrigger leaks in an SPA. Peer deps `gsap ^3.12.5`, `react >=17` (React 19.2.7 satisfied) `[VERIFIED: npm registry]` |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `ScrollTrigger` | (ships inside `gsap`) | Pin, scrub, start/end, `invalidateOnRefresh` | Imported from `gsap/ScrollTrigger`; registered once at module level. No separate install |
| SplitText | (available free, ships inside `gsap`) | Line/word/char splitting | **Not recommended for this phase** — hand-split words (see Don't Hand-Roll). Keeps one fewer plugin in the marketing chunk and matches what both sketches already did |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| GSAP ScrollTrigger | framer-motion `useScroll`/`whileInView` (already installed) | Motion handles the variant-A floor well but cannot do true **pin + scrub + horizontal-translate-on-vertical-scroll** ergonomically — that ceiling is exactly why sketch 002/003 picked B. Decision is locked; do not re-litigate |
| GSAP SplitText plugin | Hand-split words in the enhancer | SplitText is free now but adds plugin weight + a re-split-on-resize concern; hand-split is trivial for a single headline and is what the sketch shipped |
| Bundled npm GSAP | CDN `<script>` (as in the sketch HTML) | **Blocked by CSP** — `'strict-dynamic'` ignores host allowlists, so a CDN tag would need a nonce and is fragile. Bundling is both CSP-correct and lets Next code-split it into the route chunk |

**Installation:**
```bash
# run in frontend/
npm install gsap@^3.15.0 @gsap/react@^2.1.2
```

**Version verification (done this session):**
- `npm view gsap version` → **3.15.0** (modified 2026-04-13; created 2014-08-25) `[VERIFIED: npm registry]`
- `npm view gsap scripts.postinstall` → **empty** (no install-time script) `[VERIFIED: npm registry]`
- `npm view gsap license` → "Standard 'no charge' license: https://gsap.com/standard-license" `[VERIFIED: npm registry]`
- `npm view @gsap/react version` → **2.1.2**; `peerDependencies` → `{ gsap: '^3.12.5', react: '>=17' }` (no postinstall) `[VERIFIED: npm registry]`

## Package Legitimacy Audit

> slopcheck could **not** run this session: `pip install slopcheck` was blocked by a repo/env guard (base-conda hook, no bypass). Legitimacy was instead established via Context7's **official GreenSock namespace** (`/greensock/gsap`, `/greensock/react`) plus direct npm-registry inspection (11-year package history, matching peer deps, clean postinstall). These are among the most-installed animation packages in the JS ecosystem; slopsquat risk is negligible. Disposition is Approved, but the planner MAY still gate the install behind a `checkpoint:human-verify` per the graceful-degradation rule if desired.

| Package | Registry | Age | Downloads | Source Repo | slopcheck | Disposition |
|---------|----------|-----|-----------|-------------|-----------|-------------|
| `gsap` | npm | ~11 yrs (since 2014-08-25) | multi-M/wk (industry standard) | github.com/greensock/GSAP | unavailable (env-blocked) | Approved — Context7 official org + registry |
| `@gsap/react` | npm | 2.x, official adapter | high | github.com/greensock/react | unavailable (env-blocked) | Approved — Context7 official org + registry |

**Packages removed due to slopcheck [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none
**postinstall check (Node phase):** both packages report **no** `postinstall` script `[VERIFIED: npm registry]`.

## Architecture Patterns

### System Architecture Diagram

```
                        ┌──────────────────────────────────────────────┐
  Request  ───────────► │ middleware.ts (Frontend Server)              │
                        │  • mints per-request nonce                    │
                        │  • sets CSP: script-src 'self' 'nonce-…'      │
                        │    'strict-dynamic'  (NO 'unsafe-eval' prod)  │
                        └───────────────────┬──────────────────────────┘
                                            │ nonce on request headers
                                            ▼
        ┌───────────────────────────────────────────────────────────────┐
        │ app/page.tsx  /  app/for-operators/page.tsx   (Server Comp.)   │
        │  • renders ALL copy + markup as visible SSR HTML (SEO, no FOUC)│
        │  • no "use client"  → force-dynamic nonce cascade intact       │
        │                                                                │
        │   <MarketingScene>            ← "use client" enhancer          │
        │      {server-rendered children, already visible}               │
        │   </MarketingScene>                                            │
        └───────────────────────────────┬───────────────────────────────┘
                                         │ hydration (browser only)
                                         ▼
        ┌───────────────────────────────────────────────────────────────┐
        │ MarketingScene  (Browser / Client)                            │
        │   useGSAP(() => {                                              │
        │     const mm = gsap.matchMedia()                              │
        │     mm.add("(min-width:768px) and                             │
        │             (prefers-reduced-motion: no-preference)", () => { │
        │        gsap.set(hidden states)  ← hidden ONLY here            │
        │        …split/parallax/pin/scrub/horizontal…                  │
        │     })                                                         │
        │   }, { scope: containerRef })  ← auto-revert on unmount/nav    │
        └───────────────────────────────────────────────────────────────┘
             │ desktop + no-reduced-motion              │ mobile OR reduced-motion
             ▼                                          ▼
   GSAP full-award scenes                    matchMedia branch never runs →
   (bundled into THIS route chunk only)      content stays as visible SSR HTML,
                                             framer-motion `m.` reveals handle A-floor
```

Data flow to trace: request → middleware nonce/CSP → server-rendered visible marketing HTML → hydrate client enhancer → `useGSAP` runs a browser-only `matchMedia` gate → desktop builds GSAP scenes / mobile+reduced-motion leaves content untouched. On client-side route change away from the page, `useGSAP` scope reverts and kills all ScrollTriggers.

### Recommended Project Structure
```
frontend/
├── components/marketing/
│   ├── operator-pitch.tsx           # existing "use client" pitch (unchanged content)
│   ├── hero-scene.tsx               # NEW "use client" — GSAP enhancer for / hero + how-it-works
│   └── operator-scroll-scene.tsx    # NEW "use client" — GSAP pin + horizontal for /for-operators
├── lib/
│   ├── motion.ts                    # existing framer-motion vocabulary (untouched)
│   └── gsap.ts                      # NEW — registerPlugin(ScrollTrigger, useGSAP) once; export helpers/consts
└── app/
    ├── page.tsx                     # stays Server Component; mounts <HeroScene>
    └── for-operators/page.tsx       # stays Server Component; mounts <OperatorScrollScene>
```

### Pattern 1: Server page + client enhancer (the SSR/CSP-safe seam)
**What:** Keep the route a Server Component; isolate GSAP in a `"use client"` child that enhances already-rendered, already-visible children.
**When to use:** Both marketing routes. This is the mechanism that (a) preserves the #89 nonce cascade, (b) code-splits GSAP into only these route chunks, (c) guarantees no-FOUC.
```tsx
// components/marketing/hero-scene.tsx
"use client"
import { useRef } from "react"
import { gsap } from "@/lib/gsap"          // registerPlugin lives in lib/gsap.ts
import { useGSAP } from "@gsap/react"

export function HeroScene({ children }: { children: React.ReactNode }) {
  const scope = useRef<HTMLDivElement>(null)
  useGSAP(() => {
    const mm = gsap.matchMedia()
    mm.add("(min-width: 768px) and (prefers-reduced-motion: no-preference)", () => {
      // Hidden states set HERE (client-only): if this never runs, SSR HTML stays visible.
      gsap.set(".hero-word", { yPercent: 115, autoAlpha: 0 })
      gsap.to(".hero-word", { yPercent: 0, autoAlpha: 1, stagger: 0.045, ease: "power3.out" })
      gsap.to(".heat-wash", {
        yPercent: 30, ease: "none",
        scrollTrigger: { trigger: scope.current, start: "top top", end: "bottom top", scrub: true },
      })
      // returning a cleanup fn is optional — matchMedia reverts automatically
    })
  }, { scope })            // auto-reverts ALL of the above on unmount / route change
  return <div ref={scope}>{children}</div>
}
// Source: Context7 /greensock/react (useGSAP scope) + /greensock/gsap-skills (matchMedia)
```
> **Note on `next/dynamic({ ssr: false })`:** In Next 15/16 App Router, `ssr: false` is **not allowed inside a Server Component** — it throws. You do **not** need it here: `useGSAP` runs in a layout effect (browser-only, SSR no-op), so importing `HeroScene` directly into the Server page is already SSR-safe and still code-splits GSAP into the route chunk. Only reach for a client `dynamic(..., { ssr:false })` wrapper if you want to skip rendering the enhancer's markup on the server — which you **don't**, because server-rendering the visible content is what prevents FOUC. `[VERIFIED: Next.js docs + WebSearch — "ssr:false is not allowed with next/dynamic in Server Components"]`

### Pattern 2: Desktop-only + reduced-motion gate with `gsap.matchMedia()`
**What:** One `matchMedia` condition string builds the heavy scenes only on desktop with motion allowed; GSAP **auto-reverts** everything created inside when the condition stops matching (e.g., resize below 768px).
**When to use:** Wrap every pin/scrub/horizontal/parallax scene. This is the locked non-negotiable from both sketch decisions.
```tsx
const mm = gsap.matchMedia(scope)   // optionally pass scope
mm.add(
  { desktop: "(min-width: 768px) and (prefers-reduced-motion: no-preference)" },
  (ctx) => {
    if (!ctx.conditions?.desktop) return
    // pin + horizontal scenes here — never created on mobile/reduced-motion
  }
)
// Source: Context7 /greensock/gsap-skills — "matchMedia() … automatically revert them, ideal for responsive + accessibility"
```

### Pattern 3: Pinned "build-on-scrub" + horizontal-scrub rail (the sketch-003 showpiece)
**What:** Pin a scene and drive item reveals / horizontal translation off scroll position.
**When to use:** `/for-operators` Service-rail (pin + scrubbed build) and pilot rail (pin + horizontal).
```tsx
// Pinned build (Service rail): 3 items reveal as you scrub through a pinned scene
ScrollTrigger.create({
  trigger: ".pin-scene", start: "top top", end: "+=1100",
  pin: true, scrub: true, invalidateOnRefresh: true,
  onUpdate: (self) => {
    items.forEach((it, i) => {
      const seg = gsap.utils.clamp(0, 1, self.progress * items.length - i)
      gsap.set(it, { autoAlpha: seg, x: 24 * (1 - seg) })
    })
  },
})

// Horizontal pilot rail: translate a wide track left as the section is pinned
gsap.to(track, {
  x: () => -(track.scrollWidth - scope.current!.clientWidth),  // function → recomputed on refresh
  ease: "none",
  scrollTrigger: {
    trigger: ".pilot", start: "top 10%",
    end: () => "+=" + (track.scrollWidth - scope.current!.clientWidth),
    pin: true, scrub: 1, invalidateOnRefresh: true,   // REQUIRED so widths recompute on resize/font-load
  },
})
// Source: sketch 003 index.html (proven interaction) + Context7 /greensock/gsap-skills (horizontal scroll, ease:"none")
```
**Care items:** use **function-based** `x`/`end` values so `invalidateOnRefresh: true` recomputes them; call `ScrollTrigger.refresh()` once after fonts/images that affect layout settle (see Pitfalls).

### Anti-Patterns to Avoid
- **CDN `<script>` for GSAP (what the sketch did):** blocked/ignored under `'strict-dynamic'`. Bundle via npm.
- **Adding `'unsafe-eval'` or `'unsafe-inline'` to `script-src`:** unnecessary (GSAP core + ScrollTrigger don't eval) and would regress #89. Never touch `lib/security-headers.ts` for this phase.
- **Making the page itself `"use client"`:** breaks the force-dynamic nonce cascade (#89) and hurts SEO. Keep pages as Server Components.
- **Setting `opacity:0` / hidden in CSS or in server markup:** if JS fails, content is invisible forever. Hide **only** inside `useGSAP`'s matchMedia block.
- **Raw `useEffect` + manual `ScrollTrigger.kill()`:** error-prone leak surface. Use `useGSAP({ scope })` which auto-reverts (ScrollTriggers are included in the context).
- **Hard-coded pixel `end`/`x` for the horizontal rail:** breaks on resize/font swap. Use function values + `invalidateOnRefresh`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| ScrollTrigger cleanup on unmount / client-side nav | manual `kill()` bookkeeping in `useEffect` | `useGSAP({ scope })` | Auto-reverts every tween + ScrollTrigger in scope; the documented leak fix for SPAs `[CITED: Context7 /greensock/react]` |
| Responsive + reduced-motion gating | ad-hoc `matchMedia` listeners + manual teardown | `gsap.matchMedia()` | Auto-creates on match, auto-reverts on unmatch — no listener leaks `[CITED: Context7 /greensock/gsap-skills]` |
| Scroll-linked position math (pin/scrub) | scroll-event listeners + rAF | ScrollTrigger `pin`/`scrub`/`start`/`end` | Handles pin-spacing, refresh, resize, snapping edge cases |
| Timeline sequencing | chained setTimeouts | `gsap.timeline()` | Deterministic, scrub-reversible |

**Key insight:** Word-splitting the single headline is the **one** thing worth hand-rolling (both sketches already did — a ~10-line `splitWords` that wraps each token in `<span class="word">`). It avoids pulling the SplitText plugin into the marketing chunk and sidesteps SplitText's resize-re-split lifecycle for a static headline. Everything else in this domain is a solved ScrollTrigger/useGSAP concern — do not reinvent.

## Common Pitfalls

### Pitfall 1: FOUC / flash-of-hidden-content if GSAP fails or is late
**What goes wrong:** Content that starts at `opacity:0` (in CSS or server markup) stays invisible if the bundle fails, is slow, or the user is on mobile/reduced-motion where the scene never builds.
**Why it happens:** Hidden state declared outside the client-only animation path.
**How to avoid:** Server-render content **visible**. Set hidden states **only inside** `useGSAP`'s `matchMedia` callback (client + desktop + motion-allowed). Prefer `autoAlpha` (GSAP toggles `visibility` + `opacity`, restored on revert). The sketch encodes this exact safety: `.g-hide { opacity:0 }` applied only when a `gsap-ready`/`gsapon` class is present.
**Warning signs:** Blank hero on a throttled connection; empty section on a 375px viewport; content missing under `prefers-reduced-motion: reduce`.

### Pitfall 2: SSR hydration — GSAP touching a non-existent DOM
**What goes wrong:** GSAP called during render/SSR → `window`/DOM undefined errors.
**Why it happens:** Animation code outside an effect, or plugin registration at import in a server-reachable module.
**How to avoid:** All GSAP calls live inside `useGSAP` (runs in a layout effect, SSR no-op). Register plugins in a `"use client"` `lib/gsap.ts` module imported only by client enhancers. `useGSAP` uses `useIsomorphicLayoutEffect`, so it is SSR-safe by design.
**Warning signs:** "window is not defined" / "document is not defined" at build or on the server.

### Pitfall 3: CLS / layout shift from pinned sections
**What goes wrong:** Pinning inserts pin-spacing; if the pinned element's height is measured before fonts/images settle, `start`/`end` are wrong and the page jumps (bad CLS on a marketing page).
**Why it happens:** ScrollTrigger measures at creation; late layout changes invalidate positions.
**How to avoid:** `invalidateOnRefresh: true` on pinned/horizontal triggers; call `ScrollTrigger.refresh()` after webfonts load (`document.fonts.ready.then(() => ScrollTrigger.refresh())`) and after any above-the-fold image with known dimensions loads. Reserve space for pinned scenes so pin-spacing doesn't reflow siblings. Because the heavy pins are desktop-only, mobile CLS risk is already removed by the gate.
**Warning signs:** Content jumps when a scene enters; Lighthouse CLS regression on `/` or `/for-operators`.

### Pitfall 4: Triggers surviving a client-side route change
**What goes wrong:** Navigating away (Next `<Link>`) without killing ScrollTriggers leaves them attached → console errors, leaks, ghost pinning.
**Why it happens:** ScrollTrigger is global; React unmount alone doesn't remove it.
**How to avoid:** `useGSAP({ scope })` reverts the context (and its ScrollTriggers) on unmount — covered automatically. Do **not** create triggers outside the hook/scope.
**Warning signs:** "GSAP target not found" after navigation; pinned spacing persisting on the next route.

### Pitfall 5: CDN-under-strict-dynamic false negative
**What goes wrong:** Copying the sketch's CDN `<script>` "works locally in dev" (dev CSP includes `'unsafe-eval'` and is looser) then **fails in production** where strict-dynamic ignores the host allowlist.
**Why it happens:** Dev vs prod CSP asymmetry (see `security-headers.ts`: `isDev` adds `'unsafe-eval'`).
**How to avoid:** Bundle via npm from the start; never reference a CDN. Verify against **production** CSP (or `NODE_ENV=production` local stack) with the existing `csp-no-violations.spec.ts` before merge.
**Warning signs:** GSAP animations run in `npm run dev` but are dead on the Docker/prod build; CSP console violations only in prod.

### Pitfall 6: docs-freshness metrics gate
**What goes wrong:** Adding Jest/Playwright tests changes the logical-invocation counts; the `docs-freshness` CI gate (`scripts/docs-freshness.sh`) fails the build on drift against `docs/metrics.json`.
**Why it happens:** `docs/metrics.json` is the enforced single source of truth for test counts (CLAUDE.md).
**How to avoid:** Update `docs/metrics.json` (Jest `it/test` count, Playwright `test()` count) in the same PR as the new tests.
**Warning signs:** `docs-freshness` job red with a count-mismatch diff.

## Code Examples

### Register plugins once (client module)
```tsx
// lib/gsap.ts
"use client"
import { gsap } from "gsap"
import { ScrollTrigger } from "gsap/ScrollTrigger"
import { useGSAP } from "@gsap/react"

gsap.registerPlugin(ScrollTrigger, useGSAP)   // register once, module scope
export { gsap, ScrollTrigger }
// Source: Context7 /greensock/gsap-skills — "register once at module level"
```

### Refresh after fonts settle (avoid CLS on pinned scenes)
```tsx
useGSAP(() => {
  const mm = gsap.matchMedia()
  mm.add("(min-width: 768px) and (prefers-reduced-motion: no-preference)", () => {
    /* build pin/horizontal scenes here */
    if (typeof document !== "undefined" && "fonts" in document) {
      document.fonts.ready.then(() => ScrollTrigger.refresh())
    }
  })
}, { scope })
```

### Coexistence with framer-motion (no change to MotionProvider)
```tsx
// Variant-A floor keeps using framer-motion `m.` (LazyMotion strict) on the SAME page;
// GSAP targets different elements (hero words, heat-wash, pinned rails). No shared state,
// both use requestAnimationFrame independently — no conflict. Do NOT wrap GSAP targets in `m.`.
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| GSAP ScrollTrigger/SplitText behind paid Club GreenSock | 100% free incl. all plugins | Apr 2025 (post Webflow acquisition, Oct 2024) | Legal to bundle ScrollTrigger/SplitText in a billed SaaS at no cost `[CITED: webflow.com/updates/gsap-becomes-free]` |
| `gsap.context()` + manual `ctx.revert()` in `useEffect` | `@gsap/react` `useGSAP()` hook | GSAP 3.12+ / @gsap/react 2.x | Automatic scoped cleanup; the standard React integration `[CITED: Context7 /greensock/react]` |
| Old SplitText (larger, manual a11y) | Rewritten SplitText (−50% size, built-in screen-reader handling) | 2025 | If SplitText is ever adopted later, the new build is a11y-safer — but this phase hand-splits |

**Deprecated/outdated:**
- CDN-loaded GSAP for this app: incompatible with the repo's `'strict-dynamic'` CSP. Use the npm bundle.
- `next/dynamic({ ssr:false })` from a Server Component: disallowed in App Router (Next 15/16) — not needed here anyway.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | GSAP core + ScrollTrigger require no `'unsafe-eval'`/`'unsafe-inline'` when bundled | CSP / Pitfalls | If wrong, prod CSP violations; **mitigated** by the existing `csp-no-violations.spec.ts` gate run against a prod build before merge |
| A2 | `768px` is the desktop breakpoint for the gate | matchMedia gating | Cosmetic only — sketches say "below `md`"; Tailwind `md` = 768px, consistent. Confirm with design if a different `md` is desired |
| A3 | slopcheck-equivalent legitimacy holds despite the tool being env-blocked | Package Legitimacy Audit | Very low — packages resolved from Context7 official GreenSock org + 11-yr npm history + clean postinstall |

**Not empty:** three assumptions flagged; A1 is the load-bearing one and is covered by an existing automated CSP gate, so it is verifiable at implementation time.

## Open Questions

1. **Where exactly does the horizontal pilot rail begin/end relative to the fit-check form?**
   - What we know: `/for-operators` renders `OperatorPitch` (one big `"use client"` component) with a pilot-steps `<ol>` and an interactive fit-check.
   - What's unclear: whether the horizontal rail wraps the existing pilot `<ol>` in place or a new markup block; the fit-check's `scrollIntoView` must not fight a pinned section.
   - Recommendation: build the pinned/horizontal scene around a dedicated wrapper that does **not** contain the fit-check; keep `scrollIntoView` targets outside any pinned scene to avoid scroll-position conflicts.

2. **Should the enhancer wrap the whole `OperatorPitch` or just the animated regions?**
   - Recommendation: refactor only the animated regions into scoped children of `OperatorScrollScene`; leave the stateful fit-check logic untouched inside `OperatorPitch` to minimize churn and preserve existing behavior/tests.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| npm registry (`gsap`, `@gsap/react`) | GSAP scenes | ✓ | gsap 3.15.0 / @gsap/react 2.1.2 | — |
| Node/Next build (Next 16.2.2) | bundling GSAP into route chunk | ✓ | next ^16.2.2 | — |
| framer-motion (coexistence) | variant-A floor | ✓ | ^12.42.2 | — |
| Playwright (scroll scene tests) | scene verification | ✓ | ^1.61.1 | — |
| slopcheck | legitimacy audit | ✗ | — | Context7 official-org + npm registry verification (used) |

**Missing dependencies with no fallback:** none.
**Missing dependencies with fallback:** slopcheck (env-blocked) → substituted Context7 official-namespace + npm-registry verification.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework (unit) | Jest 29.7 + @testing-library/react 16 + jsdom |
| Framework (e2e/scroll) | Playwright 1.61 |
| Config file | `frontend/jest.config.js` (jsdom, `@/` mapper, excludes `/e2e/`), `frontend/playwright.config.ts` |
| Quick run command | `cd frontend && npx jest components/marketing lib/gsap` |
| Full suite command | `cd frontend && npm test` (Jest) + `npx playwright test` (e2e) |

**jsdom cannot drive scroll or layout** — unit tests cover the *gating util and pure logic* (breakpoint/reduced-motion decision, word-split function, "content visible when not enhanced"); Playwright covers the *scenes* (scroll, pin, horizontal, CSP-clean).

### Phase Requirements → Test Map
| Req | Behavior | Test Type | Automated Command | File Exists? |
|-----|----------|-----------|-------------------|--------------|
| Gate logic | desktop+no-reduced-motion → enhance; else no-op | unit | `npx jest lib/gsap` | ❌ Wave 0 |
| Word-split | headline splits into `.word` spans, text preserved | unit | `npx jest components/marketing/hero-scene` | ❌ Wave 0 |
| No-FOUC | enhancer renders children visible without JS gate firing | unit (RTL) | `npx jest components/marketing` | ❌ Wave 0 |
| CSP clean | `/` and `/for-operators` fire **zero** CSP violations with GSAP bundled | e2e | `npx playwright test e2e/csp-no-violations.spec.ts` | ✅ exists for `/` — **extend to `/for-operators`** |
| Desktop scene | on desktop viewport, hero words animate / pins engage on scroll | e2e | `npx playwright test e2e/marketing-motion.spec.ts` | ❌ Wave 0 |
| Mobile floor | at 375px, no pin/horizontal; content fully visible | e2e | `npx playwright test e2e/marketing-motion.spec.ts` | ❌ Wave 0 |
| Reduced-motion | with `prefers-reduced-motion: reduce`, no scene; content visible | e2e | Playwright `page.emulateMedia({ reducedMotion: 'reduce' })` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `cd frontend && npx jest components/marketing lib/gsap`
- **Per wave merge:** `cd frontend && npm test` + `npx playwright test e2e/csp-no-violations.spec.ts e2e/marketing-motion.spec.ts`
- **Phase gate:** full Jest + Playwright green; `docs/metrics.json` updated so `docs-freshness` passes.

### Wave 0 Gaps
- [ ] `frontend/lib/__tests__/gsap.test.ts` — desktop/reduced-motion gate decision (pure util extracted from the matchMedia string)
- [ ] `frontend/components/marketing/__tests__/hero-scene.test.tsx` — word-split + renders-children-visible (no-FOUC)
- [ ] `frontend/e2e/marketing-motion.spec.ts` — desktop scene fires; mobile 375px + reduced-motion degrade to visible-static
- [ ] Extend `frontend/e2e/csp-no-violations.spec.ts` to add a `/for-operators` case (currently only `/`, `/shop/[slug]`, `/dashboard`)
- [ ] Update `docs/metrics.json` Jest + Playwright counts in the same PR (docs-freshness gate)

## Security Domain

`security_enforcement` not explicitly false → included.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V5 Input Validation | no | GSAP animates static, developer-authored DOM; no user input feeds animation params |
| V6 Cryptography | no | none introduced |
| V14.4 HTTP Security Headers / CSP | **yes** | Must preserve `script-src 'self' 'nonce-…' 'strict-dynamic'` (no `'unsafe-eval'`/`'unsafe-inline'`) — bundle GSAP, no CDN, no inline scripts. Guarded by `csp-no-violations.spec.ts` |

### Known Threat Patterns for {Next.js 16 + bundled GSAP + nonce CSP}
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| CSP weakening to make a CDN/inline animation "work" | Tampering / Elevation | Bundle via npm; never edit `security-headers.ts`; keep the CSP e2e green |
| Supply-chain (malicious animation dep) | Tampering | Only official `gsap` + `@gsap/react`; verified via Context7 GreenSock org + npm history; no postinstall scripts |
| DOM-based XSS via animated `innerHTML` | Tampering | Word-split uses `textContent`/`createElement` (no `innerHTML` with untrusted data) — matches the sketch's `splitWords` |

## Sources

### Primary (HIGH confidence)
- Context7 `/greensock/gsap-skills` — `useGSAP`, `gsap.matchMedia()` responsive+reduced-motion, ScrollTrigger horizontal/`containerAnimation`, `gsap.context()` cleanup, registerPlugin placement
- Context7 `/greensock/react` — `useGSAP` signature, `scope`/`dependencies`/`revertOnUpdate`, `contextSafe`, automatic revert on unmount
- npm registry — `gsap` 3.15.0 (no postinstall, "no charge" license), `@gsap/react` 2.1.2 (peer `gsap ^3.12.5`, `react >=17`)
- Repo files — `middleware.ts`, `lib/security-headers.ts` (CSP strict-dynamic, no unsafe-eval prod), `app/page.tsx` + `app/for-operators/page.tsx` (Server Components), `components/marketing/operator-pitch.tsx`, `components/motion-provider.tsx` + `lib/motion.ts` (framer-motion foundation), `frontend/e2e/csp-no-violations.spec.ts`, `frontend/jest.config.js`, `.planning/sketches/002…` + `003…/index.html` (proven CDN/hand-split/matchMedia patterns), `MANIFEST.md` (locked motion direction), `CLAUDE.md` (metrics/docs-freshness gate)

### Secondary (MEDIUM confidence)
- [Webflow makes GSAP 100% free](https://webflow.com/updates/gsap-becomes-free) — free-for-commercial incl. all plugins (ScrollTrigger, SplitText), Apr 2025
- [Codrops — Free GSAP plugins (SplitText…)](https://tympanus.net/codrops/2025/05/14/from-splittext-to-morphsvg-5-creative-demos-using-free-gsap-plugins/) — SplitText rewrite (−50%, a11y)
- WebSearch consensus — `ssr:false` not allowed in Server Components (App Router), useGSAP as the standard cleanup path, dynamic import for bundle isolation

### Tertiary (LOW confidence)
- [thinknovus GSAP-in-Next guide](https://www.thinknovus.com/blog/the-definitive-guide-to-using-gsap-in-next-js-for-speed-and-impact), [Next.js Lazy Loading docs](https://nextjs.org/docs/app/guides/lazy-loading) — corroborating, not sole-sourced

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — versions + license + peer deps verified on npm; official GreenSock via Context7
- Architecture / integration: HIGH — patterns from official Context7 docs, cross-checked against the repo's real CSP + SSR constraints
- CSP compatibility: HIGH (A1 assumption) — reasoned from the actual `security-headers.ts` + backed by an existing automated CSP gate
- Pitfalls: HIGH — combination of official docs + the sketches' own encoded safety patterns
- Package legitimacy: MEDIUM — slopcheck env-blocked; substituted Context7 official-org + npm-registry + clean-postinstall verification

**Research date:** 2026-07-14
**Valid until:** ~2026-08-14 (GSAP is stable/mature; re-verify only if Next major or GSAP 3.16+ lands)
