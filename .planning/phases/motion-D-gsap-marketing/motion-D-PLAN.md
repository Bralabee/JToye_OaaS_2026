---
phase: motion-D-gsap-marketing
plan: 01
type: execute
wave: 1
depends_on: []
requirements: [MOTION-D-HERO, MOTION-D-OPERATOR, MOTION-D-FLOOR, MOTION-D-CSP]
autonomous: false           # Task 1 is a blocking package-legitimacy checkpoint (slopcheck was env-blocked in RESEARCH)
files_modified:
  - frontend/package.json
  - frontend/package-lock.json
  - frontend/lib/gsap.ts
  - frontend/lib/gsap-gate.ts
  - frontend/components/marketing/reveal.tsx
  - frontend/components/marketing/hero-scene.tsx
  - frontend/components/marketing/operator-scroll-scene.tsx
  - frontend/app/page.tsx
  - frontend/components/marketing/operator-pitch.tsx
  - frontend/lib/__tests__/gsap-gate.test.ts
  - frontend/components/marketing/__tests__/reveal.test.tsx
  - frontend/components/marketing/__tests__/hero-scene.test.tsx
  - frontend/components/marketing/__tests__/operator-scroll-scene.test.tsx
  - frontend/e2e/marketing-motion.spec.ts
  - frontend/e2e/csp-no-violations.spec.ts
  - docs/metrics.json

user_setup: []              # No external service; npm registry only

must_haves:
  truths:
    - "On a desktop viewport (>=768px) with motion allowed, the / hero headline splits into words and animates in, the heat-wash parallaxes on scroll, and the how-it-works step-rail scrubs as you pass it."
    - "On a desktop viewport with motion allowed, /for-operators pins the Service-rail hero and builds its three rail items on scrub, and the four pilot steps translate horizontally as you scroll vertically."
    - "On a 375px phone viewport, no pin / horizontal / parallax runs; every marketing element is fully visible and (where wrapped) reveals on enter via the framer-motion floor."
    - "Under prefers-reduced-motion: reduce, no GSAP scene builds and content is fully visible."
    - "If GSAP JS fails, is slow, or the gate does not match, server-rendered marketing content stays fully visible — no hidden/opacity:0 state exists in CSS or server markup; hidden 'from' states live only inside the client desktop-motion gate."
    - "/ and /for-operators fire zero CSP violations against a production build with GSAP bundled (no CDN reference, no 'unsafe-eval' / 'unsafe-inline' added to script-src)."
    - "/ and /for-operators remain Server Components (the #89 force-dynamic nonce cascade is intact); GSAP is code-split into the marketing route chunks only."
    - "npm run build, eslint ., npx jest, and scripts/docs-freshness.sh all pass; docs/metrics.json reflects the new Jest + Playwright counts; existing operator-pitch.test.tsx stays green (no hardcoded hex, headings/text/roles preserved)."
  artifacts:
    - path: "frontend/lib/gsap-gate.ts"
      provides: "Pure gate util: DESKTOP_MOTION_QUERY const, prefersDesktopMotion(), canEnhance(), splitWords() — no gsap import, jsdom-testable"
      contains: "DESKTOP_MOTION_QUERY"
    - path: "frontend/lib/gsap.ts"
      provides: "Client GSAP registration module — registerPlugin(ScrollTrigger, useGSAP); re-exports gsap, ScrollTrigger, useGSAP"
      contains: "registerPlugin"
    - path: "frontend/components/marketing/reveal.tsx"
      provides: "framer-motion mobile/reduced-motion reveal floor primitive (reuses lib/motion.ts variants); inert on desktop-with-motion"
      min_lines: 20
    - path: "frontend/components/marketing/hero-scene.tsx"
      provides: "'use client' GSAP enhancer for the / hero + how-it-works (split-type, parallax heat-wash, scrubbed step-rail)"
      min_lines: 40
    - path: "frontend/components/marketing/operator-scroll-scene.tsx"
      provides: "'use client' enhancer/hook for /for-operators (pinned rail-build on scrub + horizontal pilot rail + scrubbed count-up)"
      min_lines: 40
    - path: "frontend/e2e/marketing-motion.spec.ts"
      provides: "Playwright proof: desktop scenes fire; mobile 375px + reduced-motion degrade to visible-static floor"
      min_lines: 40
    - path: "docs/metrics.json"
      provides: "Regenerated Jest + Playwright counts so the docs-freshness gate passes"
      contains: "total_logical_invocations"
  key_links:
    - from: "frontend/app/page.tsx"
      to: "frontend/components/marketing/hero-scene.tsx"
      via: "Server page mounts <HeroScene> around already-visible server children"
      pattern: "HeroScene"
    - from: "frontend/components/marketing/operator-pitch.tsx"
      to: "frontend/components/marketing/operator-scroll-scene.tsx"
      via: "useOperatorScrollScene(rootRef) called inside the client pitch, scoped to its root"
      pattern: "useOperatorScrollScene"
    - from: "frontend/components/marketing/hero-scene.tsx"
      to: "frontend/lib/gsap.ts"
      via: "import gsap, ScrollTrigger, useGSAP"
      pattern: "@/lib/gsap"
    - from: "frontend/components/marketing/hero-scene.tsx"
      to: "frontend/lib/gsap-gate.ts"
      via: "import DESKTOP_MOTION_QUERY, canEnhance, splitWords"
      pattern: "@/lib/gsap-gate"
    - from: "frontend/components/marketing/reveal.tsx"
      to: "frontend/lib/motion.ts"
      via: "import fadeInUp / staggerContainer variants"
      pattern: "@/lib/motion"
    - from: "frontend/e2e/csp-no-violations.spec.ts"
      to: "/for-operators"
      via: "new test case asserting CSP header + zero violations"
      pattern: "/for-operators"
---

<objective>
Ship the locked GSAP "full-award" (variant B) scroll choreography on the two marketing routes — `/` (sketch 002-B) and `/for-operators` (sketch 003-B) — as a **desktop-only, non-reduced-motion enhancement** layered on top of the existing server-rendered content, with the framer-motion reveal floor (variant A) as the mandated mobile / `prefers-reduced-motion` degradation.

Purpose: The design lead locked variant B for desktop and variant A as the non-negotiable floor (sketches 002/003, 2026-07-14). This plan is **motion-uplift Phase D**, following Phases A–C shipped in PRs #220/#221 (framer-motion foundation). GSAP does not replace framer-motion — a second, purpose-scoped engine coexists on two DOM subtrees only.

Output: `gsap` + `@gsap/react` bundled via npm; a shared client GSAP module + pure gate util; a reveal floor primitive; two `"use client"` enhancers; the two Server pages progressively enhanced; unit tests (gate/split/no-FOUC) + a Playwright scene/floor spec + an extended CSP spec; regenerated `docs/metrics.json`.

STANDALONE ARTIFACT: This plan is deliberately **not** wired into `.planning/ROADMAP.md` (a parallel v2.3 terminal owns the roadmap). The user slots it into the roadmap when ready. Do NOT edit `.planning/ROADMAP.md` or `.planning/STATE.md`.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md

ABSOLUTE-PATH RULE: All work happens under the worktree `/home/sanmi/IdeaProjects/JToye_OaaS_2026-motion` (branch `feature/plan-gsap-marketing`). The shell CWD may reset to a different checkout between Bash calls — always `cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion && ...` and use absolute paths. NEVER touch the non-motion checkout.
</execution_context>

<context>
@/home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/.planning/phases/motion-D-gsap-marketing/motion-D-RESEARCH.md
@/home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/.planning/sketches/002-marketing-hero-kinetics/index.html
@/home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/.planning/sketches/003-operator-scroll-story/index.html
@/home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/frontend/app/page.tsx
@/home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/frontend/components/marketing/operator-pitch.tsx
@/home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/frontend/lib/motion.ts
@/home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/frontend/components/motion-provider.tsx
@/home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/frontend/e2e/csp-no-violations.spec.ts

<interfaces>
<!-- Contracts the executor should build against directly — no codebase exploration needed. -->

MotionProvider is mounted app-wide in frontend/app/layout.tsx (LazyMotion strict + domMax + MotionConfig reducedMotion="user"), so framer-motion `m.` components work on the marketing routes. Root layout is `export const dynamic = "force-dynamic"` (the #89 CSP-nonce cascade).

Existing framer-motion vocabulary (frontend/lib/motion.ts) — reuse for the floor:
  export const fadeInUp: Variants        // { hidden: {opacity:0,y:20}, visible:{opacity:1,y:0} }
  export const staggerContainer: Variants
  export const staggerItem: Variants
  export const durations, springPop, springSoft

Jest env (frontend/jest.setup.js): framer-motion is globally MOCKED to a passthrough that STRIPS motion-only props (initial/animate/whileInView/variants/viewport/...) and renders plain tags → in jsdom, `m.div whileInView` renders children VISIBLE. There is NO window.matchMedia mock; jsdom leaves it undefined. OperatorPitch already relies on `window.matchMedia?.(...)` short-circuiting to falsy.

New pure module contract — frontend/lib/gsap-gate.ts (NO "use client", NO gsap import, jsdom-safe):
  export const DESKTOP_MOTION_QUERY = "(min-width: 768px) and (prefers-reduced-motion: no-preference)"
  export function prefersDesktopMotion(opts: { width: number; reducedMotion: boolean }): boolean   // width >= 768 && !reducedMotion
  export function canEnhance(): boolean            // typeof window !== "undefined" && typeof window.matchMedia === "function"
  export function splitWords(el: HTMLElement): HTMLSpanElement[]  // wraps each whitespace token in <span class="gsap-word">, preserves child element nodes, textContent-only (no innerHTML w/ untrusted data), idempotent

New client module contract — frontend/lib/gsap.ts ("use client"):
  import { gsap } from "gsap"; import { ScrollTrigger } from "gsap/ScrollTrigger"; import { useGSAP } from "@gsap/react"
  gsap.registerPlugin(ScrollTrigger, useGSAP)   // once, module scope
  export { gsap, ScrollTrigger, useGSAP }

E2E signal contract (so Playwright can assert deterministically):
  - splitWords sets class "gsap-word" on each word span.
  - Each enhancer sets data-motion-active="desktop" on its scope root INSIDE the matchMedia desktop branch, and removes it in the matchMedia cleanup (so mobile/reduced-motion never has it).
  - Pinned scenes on /for-operators produce ScrollTrigger's ".pin-spacer" element in the DOM on desktop only.
</interfaces>
</context>

<tasks>

<task type="checkpoint:human-verify" gate="blocking-human">
  <name>Task 1: Checkpoint — verify GSAP package legitimacy before install</name>
  <what-built>Nothing yet. RESEARCH.md's Package Legitimacy Audit could not run slopcheck (env-blocked), so both installs are treated as [ASSUMED] per the legitimacy gate. This gate confirms provenance BEFORE any `npm install`.</what-built>
  <how-to-verify>
    1. Open https://www.npmjs.com/package/gsap — confirm it is the official GreenSock package (repo github.com/greensock/GSAP, ~11-year history, multi-million weekly downloads, current version 3.15.x, no install-time/postinstall script).
    2. Open https://www.npmjs.com/package/@gsap/react — confirm it is the official GreenSock React adapter (repo github.com/greensock/react, current 2.1.x, peerDeps gsap ^3.12.5 + react >=17, no postinstall).
    3. Confirm neither is a slopsquat (name/scope exactly `gsap` and `@gsap/react`, published by the GreenSock org).
  </how-to-verify>
  <resume-signal>Type "approved" to proceed with the pinned install (gsap@^3.15.0, @gsap/react@^2.1.2), or name a concern.</resume-signal>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Install GSAP + shared plumbing (lib/gsap.ts, lib/gsap-gate.ts) + Reveal floor + unit tests</name>
  <files>frontend/package.json, frontend/package-lock.json, frontend/lib/gsap.ts, frontend/lib/gsap-gate.ts, frontend/components/marketing/reveal.tsx, frontend/lib/__tests__/gsap-gate.test.ts, frontend/components/marketing/__tests__/reveal.test.tsx, docs/metrics.json</files>
  <read_first>
    - RESEARCH.md "Code Examples" (register plugins once) + "Pattern 1/2" + "Don't Hand-Roll" (hand-split rationale).
    - Sketch 002 index.html `splitWords()` (lines ~299-322) — the proven token-preserving word split to port into `splitWords`.
  </read_first>
  <behavior>
    gsap-gate.test.ts (jsdom, imports the PURE module only — never lib/gsap.ts):
    - prefersDesktopMotion({width:1440,reducedMotion:false}) === true
    - prefersDesktopMotion({width:1440,reducedMotion:true}) === false
    - prefersDesktopMotion({width:767,reducedMotion:false}) === false
    - prefersDesktopMotion({width:768,reducedMotion:false}) === true   (768 breakpoint = Tailwind md, per RESEARCH A2)
    - DESKTOP_MOTION_QUERY contains "min-width: 768px" and "prefers-reduced-motion: no-preference"
    - splitWords(h1) on "Order from local kitchens. Or run yours." → creates multiple `.gsap-word` spans; concatenated textContent equals the original text (nothing dropped); calling it twice does not double-wrap
    - splitWords preserves a nested child element's text (feed an element with an inner <span> and assert the inner text survives inside a word span)
    - canEnhance() reflects window.matchMedia presence (true when a matchMedia fn is defined on window; guardable)
    reveal.test.tsx (jsdom, framer-motion is mocked → props stripped):
    - <Reveal>content</Reveal> renders its children VISIBLE (text queryable), with NO opacity:0 / hidden / aria-hidden on the wrapper — proves the no-FOUC default
  </behavior>
  <action>
    Install exact deps in the worktree frontend: `cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/frontend && npm install gsap@^3.15.0 @gsap/react@^2.1.2` (bundled — NEVER a CDN `<script>`; strict-dynamic CSP ignores host allowlists per RESEARCH Pitfall 5). Do NOT touch lib/security-headers.ts or middleware.ts.

    Create `lib/gsap-gate.ts` as a PURE module (no "use client", no gsap import) implementing the frontmatter interface contract: DESKTOP_MOTION_QUERY, prefersDesktopMotion, canEnhance, splitWords. Port splitWords from the sketch 002 implementation but emit class `gsap-word`, use textContent/createElement only (no innerHTML with untrusted data — STRIDE T-motion-D-03), and make it idempotent (if the element already contains `.gsap-word`, return the existing spans).

    Create `lib/gsap.ts` ("use client") that registers ScrollTrigger + useGSAP once at module scope and re-exports `gsap`, `ScrollTrigger`, `useGSAP`. This module is the ONLY place plugins are registered.

    Create `components/marketing/reveal.tsx` ("use client") — the mobile / reduced-motion floor primitive. It reuses `fadeInUp` (and optionally `staggerContainer`/`staggerItem`) from lib/motion.ts and renders an `m.div`/`m.section` (accept an `as`/`className`/`variants` prop) with `initial="hidden" whileInView="visible" viewport={{ once: true, amount: 0.2 }}`. CRITICAL — it must be INERT on desktop-with-motion so it never co-animates a GSAP-owned element: gate the reveal with `prefersDesktopMotion`, and to satisfy the `react-hooks/set-state-in-effect` lint rule (which bit PR #221) read the media state via `useSyncExternalStore` (React 19) subscribing to a matchMedia listener — do NOT use `useEffect`+`setState`. When desktop-with-motion OR when JS has not resolved the gate yet, render children plain and fully visible (no hidden state). Never set opacity:0 in CSS or server markup.

    Write the two unit-test files per <behavior>. gsap-gate.test.ts must import from `@/lib/gsap-gate` only (keeps gsap out of the jsdom unit test).

    Regenerate metrics so the gate passes: `cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion && bash scripts/docs-freshness.sh --write` then commit docs/metrics.json in this task. Do NOT edit the CLAUDE.md prose count line (owned by the parallel v2.3 terminal; not gated by the script — only docs/metrics.json is).
  </action>
  <verify>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/frontend && npm run build && npm run lint && npx jest lib/gsap-gate components/marketing/reveal && cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion && bash scripts/docs-freshness.sh</automated>
  </verify>
  <done>gsap + @gsap/react in package.json/lock; lib/gsap.ts registers plugins; lib/gsap-gate.ts is pure + jsdom-safe; Reveal renders children visible by default and is desktop-inert; unit tests green; build + eslint + docs-freshness all pass.</done>
</task>

<task type="auto">
  <name>Task 3: Landing / hero kinetics enhancer (hero-scene.tsx) + wire into app/page.tsx</name>
  <files>frontend/components/marketing/hero-scene.tsx, frontend/app/page.tsx, frontend/components/marketing/__tests__/hero-scene.test.tsx, docs/metrics.json</files>
  <read_first>
    - Sketch 002 index.html Variant B `initB()` (lines ~324-376) — split-type stagger, parallax heat-wash, scrubbed step-rail draw + step `.on` activation. This is the desktop choreography to reproduce.
    - frontend/app/page.tsx — the real Server Component markup to enhance (hero heading, two persona doors, "How it works" steps, trust chips).
    - RESEARCH.md Pattern 1 (server page + client enhancer seam) and Pitfall 1 (no-FOUC).
  </read_first>
  <action>
    Create `components/marketing/hero-scene.tsx` ("use client") that takes `{ children }`, renders `<div ref={scope}>{children}</div>`, and runs `useGSAP(() => { ... }, { scope })` importing gsap/ScrollTrigger/useGSAP from `@/lib/gsap` and DESKTOP_MOTION_QUERY/canEnhance/splitWords from `@/lib/gsap-gate`. Guard the whole build with `if (!canEnhance()) return;` (jsdom / no matchMedia → no-op). Inside `useGSAP`, use `gsap.matchMedia()` and `mm.add(DESKTOP_MOTION_QUERY, () => { ... return cleanup })`:
      - Set `data-motion-active="desktop"` on scope.current at branch start; remove it in the returned cleanup.
      - Hidden "from" states set ONLY here (never in CSS/markup): split the headline via `splitWords`, then stagger the `.gsap-word` spans up into place (yPercent/autoAlpha); deal-in the two persona doors; reveal the how-it-works section title and trust chips on their own ScrollTriggers.
      - Parallax heat-wash: scrub-translate the hero's gradient overlay on scroll (trigger the hero section, start top top / end bottom top, scrub true).
      - Scrubbed step-rail: draw a rail fill left→right and toggle each step's active state on enter/leaveBack as you scrub through the "How it works" grid (reproduce sketch 002's rail + `.on` behavior).
      - Call `document.fonts.ready.then(() => ScrollTrigger.refresh())` inside the branch to avoid CLS on late font layout (RESEARCH Pitfall 3).
    Prefer `autoAlpha` for hide/show so GSAP restores visibility on matchMedia revert.

    Modify `frontend/app/page.tsx` (KEEP it a Server Component — no "use client"): import HeroScene and wrap the hero + how-it-works region with `<HeroScene>...</HeroScene>` so the server-rendered, already-visible children are progressively enhanced. Add stable, presentational hooks the enhancer targets WITHOUT hiding anything: `data-hero-headline` on the `<h1>`, a class/data hook on each persona door, a dedicated heat-wash element with a hook (the existing `aria-hidden` gradient div), a hook on the "How it works" steps grid + each step, and a hook on the trust chips. Do not add any opacity/hidden classes. Optionally wrap the trust-chip strip in `<Reveal>` for the mobile floor (GSAP-inert on desktop). Do not add SplitText, View Transitions, or any storefront/dashboard motion (out of scope).

    Write `components/marketing/__tests__/hero-scene.test.tsx` (jsdom): render `<HeroScene>` with representative children and assert the children render fully VISIBLE (headline text queryable, no opacity:0/hidden on wrapper), proving canEnhance() no-ops safely and no-FOUC holds when the gate never fires.

    Regenerate + commit metrics: `cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion && bash scripts/docs-freshness.sh --write`.
  </action>
  <verify>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/frontend && npm run build && npm run lint && npx jest components/marketing/hero-scene && cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion && bash scripts/docs-freshness.sh</automated>
  </verify>
  <done>app/page.tsx stays a Server Component and mounts HeroScene; hidden states exist only inside the desktop matchMedia branch; jsdom test proves children render visible; build (no "window is not defined") + eslint + jest + docs-freshness pass. Desktop scene behavior itself is proven by Task 5's Playwright run.</done>
</task>

<task type="auto">
  <name>Task 4: /for-operators pinned + horizontal enhancer + wire into operator-pitch.tsx</name>
  <files>frontend/components/marketing/operator-scroll-scene.tsx, frontend/components/marketing/operator-pitch.tsx, frontend/components/marketing/__tests__/operator-scroll-scene.test.tsx, docs/metrics.json</files>
  <read_first>
    - Sketch 003 index.html Variant B `initB()` (lines ~271-325) — pinned Service-rail build-on-scrub, horizontal pilot-rail (function-based x + invalidateOnRefresh), scrubbed count-up.
    - frontend/components/marketing/operator-pitch.tsx — the real "use client" pitch (hero `#main-pitch` with the RailItem cards, the four `pilotSteps` `<ol>`, terms band, and the stateful fit-check).
    - frontend/components/marketing/__tests__/operator-pitch.test.tsx — the guard test you MUST keep green (see constraints below).
    - RESEARCH.md Pattern 3 (pin + horizontal, care items) + Open Questions 1 & 2 (keep the fit-check OUT of any pinned scene; enhance only the animated regions).
  </read_first>
  <action>
    Create `components/marketing/operator-scroll-scene.tsx` ("use client") exporting a hook `useOperatorScrollScene(scopeRef: RefObject<HTMLElement>)`. It runs `useGSAP(() => { if (!canEnhance()) return; const mm = gsap.matchMedia(); mm.add(DESKTOP_MOTION_QUERY, () => { ...; return cleanup }) }, { scope: scopeRef })` using `@/lib/gsap` + `@/lib/gsap-gate`. Inside the desktop branch:
      - Set `data-motion-active="desktop"` on scopeRef.current; remove in cleanup.
      - Signature scene 1 — pin the hero (`data-op-pin="hero"`) and build the three rail items (`[data-rail-item]`) one-by-one on scrub (ScrollTrigger.create with pin+scrub+invalidateOnRefresh, onUpdate clamps per-item progress → autoAlpha/x), plus the split-type headline entrance via `splitWords`.
      - Signature scene 2 — horizontal pilot rail: translate the pilot track (`[data-pilot-track]`) left as its section (`data-op-pin="pilot"`) is pinned; use FUNCTION-based `x` and `end` (`-(track.scrollWidth - containerWidth)`) with `invalidateOnRefresh: true` (RESEARCH Pattern 3 care items) so widths recompute on resize/font-load.
      - Scrubbed count-up on the terms band values (port sketch 003 `countUp`; reduced-motion path is already excluded by the gate).
      - `document.fonts.ready.then(() => ScrollTrigger.refresh())` for CLS safety.

    Modify `operator-pitch.tsx` MINIMALLY (it stays "use client"; do NOT change `app/for-operators/page.tsx`, which already mounts it as a Server Component child):
      - Add a `rootRef` on the outermost `<div>` and call `useOperatorScrollScene(rootRef)`.
      - Add presentational hooks ONLY (data-attributes / token classes — NO hidden state, NO hardcoded hex): `data-op-headline` on the `<h1>`; `data-rail-item` on each RailItem wrapper; `data-op-pin="hero"` on the `#main-pitch` section; restructure the pilot `<ol>` into a horizontal track by adding `data-op-pin="pilot"` on its section wrapper and `data-pilot-track` on a flex row containing the existing `<li>` steps (add `data-pilot-step` to each) — PRESERVE the `<ol>/<li>` list semantics, all step text, and the numbers.
      - Keep the fit-check (`#fit-check`), its state, `scrollIntoView`, and the terms copy exactly as-is; the fit-check must NOT sit inside a pinned scene (Open Question 1).

    CONSTRAINTS to keep operator-pitch.test.tsx green (it reads the source string): introduce NO hardcoded hex (`/#[0-9a-fA-F]{3,8}/` must still fail to match) — use Tailwind token classes / data-attributes only; do NOT add `font-black`/`text-7xl`/`text-8xl`/`font-serif`; preserve every heading accessible name and body-text string the test asserts (e.g. "Keep the order… Keep the customer… Keep the kitchen moving", "one London cluster", pilot step copy). splitWords runs client-desktop-only, so jsdom keeps the original `<h1>` text intact.

    Write `components/marketing/__tests__/operator-scroll-scene.test.tsx` (jsdom): render `<OperatorPitch />` and assert the pilot step text + headings still render fully visible (no-FOUC; canEnhance no-op path). Confirm the existing operator-pitch.test.tsx still passes.

    Regenerate + commit metrics: `cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion && bash scripts/docs-freshness.sh --write`.
  </action>
  <verify>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/frontend && npm run build && npm run lint && npx jest components/marketing && cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion && bash scripts/docs-freshness.sh</automated>
  </verify>
  <done>operator-pitch.tsx enhanced via useOperatorScrollScene with hooks only (zero hex, headings/text/roles/fit-check preserved); for-operators/page.tsx unchanged (still a Server Component); all component jest tests including the existing operator-pitch.test.tsx green; build + eslint + docs-freshness pass. Pin/horizontal behavior proven by Task 5.</done>
</task>

<task type="auto">
  <name>Task 5: Playwright scene/floor spec + CSP spec extension + full gate + metrics</name>
  <files>frontend/e2e/marketing-motion.spec.ts, frontend/e2e/csp-no-violations.spec.ts, docs/metrics.json</files>
  <read_first>
    - frontend/e2e/csp-no-violations.spec.ts — the existing local/staging CSP smoke (helper `collectCspViolations`, prod-build caveat in its header). Extend, do not rewrite.
    - frontend/playwright.config.ts — `mobile` (390x844) and `desktop` (1440x900) projects; baseURL override via PLAYWRIGHT_BASE_URL (dev uses 3100).
    - RESEARCH.md Validation Architecture (Phase Requirements → Test Map) + Pitfall 5 (CSP must be verified against a PRODUCTION build; dev CSP includes 'unsafe-eval').
  </read_first>
  <action>
    Create `e2e/marketing-motion.spec.ts` asserting the locked desktop scenes and the mandated floor, using the deterministic signals from the interface contract:
      - Desktop (1440x900): on `/`, `h1[data-hero-headline] .gsap-word` count >= 2 and the hero scope carries `data-motion-active="desktop"`; scroll and assert the heat-wash transform changes. On `/for-operators`, at least one `.pin-spacer` exists after scrolling into the pinned hero, `[data-op-headline] .gsap-word` count >= 2, and horizontal translation moves the pilot track.
      - Mobile (375px — use a 375-wide context or override the viewport): on `/` and `/for-operators`, `.gsap-word` count === 0, `[data-motion-active="desktop"]` absent, NO `.pin-spacer`, and key headings + every `[data-pilot-step]` are visible with a non-zero bounding box (content fully visible — the floor).
      - Reduced-motion: `page.emulateMedia({ reducedMotion: "reduce" })` on a desktop viewport → both routes have `.gsap-word` count === 0, no `.pin-spacer`, no `data-motion-active`, headings visible.
    Follow the existing spec conventions (this file, like csp-no-violations.spec.ts, is a LOCAL/STAGING tool — not wired into CI; the docs-freshness gate still counts its `test()` blocks).

    Extend `e2e/csp-no-violations.spec.ts`: add a `/for-operators` test that navigates there, asserts the CSP header is present (`default-src 'self'`, `frame-ancestors 'none'`), waits for networkidle + a settle timeout, and asserts `collectCspViolations` is empty — proving GSAP bundled fires zero violations and no CDN/`unsafe-eval` was introduced.

    Run the Playwright suite against a PRODUCTION build (Pitfall 5): build + start the frontend in production mode (or the local Docker/prod stack on :3100), then `PLAYWRIGHT_BASE_URL=<url> npx playwright test e2e/marketing-motion.spec.ts e2e/csp-no-violations.spec.ts` for both projects. Record the result in the SUMMARY. If a live prod stack is unavailable in the execution environment, mark the Playwright run as a blocking `<human-check>` for the developer and still assert the specs compile + are typed via `npm run build`/tsc.

    Regenerate + commit metrics one final time so playwright_blocks/playwright_specs + jest counts match: `cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion && bash scripts/docs-freshness.sh --write`.
  </action>
  <verify>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/frontend && npm run build && npm run lint && npx jest && cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion && bash scripts/docs-freshness.sh</automated>
    <human-check>Against a production build (local Docker/prod stack on :3100 or NODE_ENV=production): `PLAYWRIGHT_BASE_URL=http://localhost:3100 npx playwright test e2e/marketing-motion.spec.ts e2e/csp-no-violations.spec.ts` — desktop scenes fire on /, pins engage on /for-operators, 375px + reduced-motion show fully-visible static content, and both routes report zero CSP violations.</human-check>
  </verify>
  <done>marketing-motion.spec.ts + the /for-operators CSP case exist and pass against a prod build (desktop scenes fire; mobile 375px + reduced-motion degrade to visible-static; zero CSP violations on both routes); full jest suite green; docs/metrics.json regenerated so docs-freshness passes.</done>
</task>

</tasks>

<multi_source_coverage_audit>
GOAL — Deliver the locked GSAP "full-award" desktop scroll choreography on `/` and `/for-operators` with the framer-motion floor as the mobile/reduced-motion degradation, CSP-clean and no-FOUC.

| Source | Item | Covered by |
|--------|------|-----------|
| GOAL | Desktop GSAP choreography on `/` (split-type, parallax, scrubbed rail) | Task 3 |
| GOAL | Desktop GSAP choreography on `/for-operators` (pinned build + horizontal + count-up) | Task 4 |
| GOAL | Mobile / reduced-motion visible floor | Task 2 (Reveal) + Tasks 3/4 gating + Task 5 proof |
| CONTEXT D1 | Variant B desktop = sketch 002 hero + sketch 003 for-operators | Tasks 3, 4 |
| CONTEXT D1 | Variant A floor for mobile + reduced-motion; pin/horizontal/parallax NEVER on phones/reduced-motion | Task 2 gate + Tasks 3/4 matchMedia + Task 5 mobile/reduced-motion assertions |
| CONTEXT D2 | Bundle GSAP via npm (not CDN); scope to marketing route chunks | Task 2 install + import-graph code-split (Tasks 3/4) |
| CONTEXT D3 | Pages stay Server Components; GSAP in small "use client" enhancers; useGSAP({scope}); matchMedia gate | Tasks 3 (page unchanged as RSC), 4 (for-operators unchanged) |
| CONTEXT D4 | NO-FOUC: hidden states only inside matchMedia/useGSAP; never in CSS/server markup | Tasks 2/3/4 (contract) + Task 2/3 jsdom visible tests |
| CONTEXT D5 | Reuse existing framer-motion foundation (lib/motion.ts) for the floor; GSAP coexists | Task 2 Reveal reuses fadeInUp; MotionProvider untouched |
| RESEARCH | gsap@^3.15.0 + @gsap/react@^2.1.2; register once in lib/gsap.ts | Task 2 |
| RESEARCH | Hand-split words (no SplitText plugin) | Task 2 splitWords + Tasks 3/4 |
| RESEARCH | gsap.matchMedia desktop+no-reduced-motion gate; auto-revert | Tasks 3/4 |
| RESEARCH | Horizontal rail care: function-based x/end + invalidateOnRefresh; ScrollTrigger.refresh after fonts | Task 4 |
| RESEARCH | Extend csp-no-violations.spec.ts to /for-operators; verify vs prod build | Task 5 |
| RESEARCH | Package legitimacy gate (slopcheck env-blocked → [ASSUMED]) | Task 1 checkpoint |
| RESEARCH | docs-freshness metrics gate on new Jest/Playwright counts | Tasks 2/3/4/5 regenerate docs/metrics.json |

Every GOAL / CONTEXT / RESEARCH item is COVERED. No REQUIREMENTS.md IDs exist for this standalone phase (deliberately absent from ROADMAP); requirements frontmatter uses synthetic MOTION-D-* IDs traceable to the sketch 002-B / 003-B decisions.

Out of scope (not gaps): Phase E (D3 Market-Heat); any storefront/dashboard/app-route motion; SplitText plugin (hand-split instead); View Transitions; editing ROADMAP.md/STATE.md/CLAUDE.md prose.
</multi_source_coverage_audit>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| npm registry → build | Third-party animation packages enter the bundle (supply chain) |
| server (RSC + nonce CSP) → browser | Client enhancers execute in the browser under the #89 strict-dynamic CSP |
| developer-authored DOM → GSAP/splitWords | Static, developer-authored copy is transformed client-side (no user input) |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-motion-D-SC | Tampering | `npm install gsap @gsap/react` | mitigate | Task 1 blocking-human legitimacy checkpoint (slopcheck env-blocked → [ASSUMED]); pin ^3.15.0 / ^2.1.2; both verified as official GreenSock org with no postinstall (RESEARCH audit) |
| T-motion-D-01 | Tampering / Elevation | CSP script-src (lib/security-headers.ts, middleware.ts) | mitigate | Bundle via npm; NEVER add 'unsafe-eval'/'unsafe-inline'; never reference a CDN; regression-guarded by the extended csp-no-violations.spec.ts against a prod build (Task 5) |
| T-motion-D-02 | Tampering | Marketing route bundles | mitigate | Import lib/gsap.ts only from the two enhancers imported only by the two marketing pages → GSAP auto-code-splits into marketing chunks; no leakage into app/storefront routes |
| T-motion-D-03 | Tampering | `splitWords` DOM rewrite | mitigate | textContent/createElement only; no `innerHTML` with untrusted data; input is static developer copy (RESEARCH Security Domain) |
| T-motion-D-04 | Denial of Service (UX) | Pinned/horizontal ScrollTriggers | accept/mitigate | Desktop-only gate removes mobile CLS/jank; invalidateOnRefresh + fonts.ready refresh mitigate layout-shift; useGSAP scope auto-reverts triggers on route change (no leak) |
</threat_model>

<verification>
Phase-level checks (all must pass against the worktree):
- `cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/frontend && npm run build` — tsc + Next build clean (no "window is not defined"; GSAP code-split into marketing chunks).
- `cd .../frontend && npm run lint` — `eslint .` clean (react-hooks rules incl. set-state-in-effect; media gate via useSyncExternalStore, not useEffect+setState).
- `cd .../frontend && npx jest` — full Jest suite green, including the pre-existing operator-pitch.test.tsx (zero hex, headings/text preserved).
- `cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-motion && bash scripts/docs-freshness.sh` — docs/metrics.json matches source (Jest + Playwright counts).
- Playwright (prod build, local/staging — Task 5): `e2e/marketing-motion.spec.ts` + `e2e/csp-no-violations.spec.ts` green across desktop + mobile projects; zero CSP violations on `/` and `/for-operators`.
</verification>

<success_criteria>
- Desktop `/` shows the split-type headline, parallax heat-wash, and scrubbed step-rail; desktop `/for-operators` pins the Service-rail (builds on scrub) and scrolls the pilot rail horizontally with scrubbed counters.
- 375px phones and `prefers-reduced-motion: reduce` show fully-visible content with the framer-motion reveal floor and NO pin/horizontal/parallax.
- No-FOUC verified: content is server-rendered visible; hidden states exist only inside the client desktop-motion gate.
- Zero CSP violations on both routes against a production build; no CDN, no 'unsafe-eval'/'unsafe-inline' added; both pages remain Server Components.
- build + eslint + jest + docs-freshness all pass; existing tests unbroken; docs/metrics.json regenerated.
</success_criteria>

<output>
Create `/home/sanmi/IdeaProjects/JToye_OaaS_2026-motion/.planning/phases/motion-D-gsap-marketing/motion-D-01-SUMMARY.md` when done. Do NOT edit `.planning/ROADMAP.md` or `.planning/STATE.md` (owned by the parallel v2.3 terminal). This plan is a standalone artifact the user slots into the roadmap later.
</output>
