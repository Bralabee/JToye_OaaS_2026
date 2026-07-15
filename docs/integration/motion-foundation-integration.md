# Integrating with the Motion Foundation (read this when merging `main` into a feature branch cut before 2026-07-14)

If your branch was cut from `main` **before 2026-07-14** (merge-base at or before
`41916ed`), it predates the "motion uplift" work that landed on `main` in PRs
**#220, #221, #224, #225**. Pulling `main` is safe, but two of those changes need
a deliberate hand at merge time. This note is the resolution recipe.

## What landed on `main`

- **#220 — motion foundation.** The whole app is now wrapped in
  **`<LazyMotion strict features={domMax}>`** (`frontend/components/motion-provider.tsx`,
  mounted in `frontend/app/layout.tsx`) plus `<MotionConfig reducedMotion="user">`.
  Brand tokens promoted in `globals.css` (primary is orange, not shadcn blue).
  The 10 dashboard pages were converted from `motion.*` to `m.*`.
- **#221** — additive storefront cart drawer + dashboard chrome brand-alignment.
- **#224 / #225** — GSAP scroll animation on the marketing routes only
  (`gsap` + `@gsap/react`, bundled, code-split into the marketing chunks; it does
  **not** load on app/storefront/dashboard routes).

## ⚠️ The one runtime trap: `LazyMotion strict` throws on `motion.*`

`LazyMotion strict` **throws at runtime** if any full `motion.*` component renders —
only the lightweight `m.*` components are allowed (this is what lets the bundle stay
small). So after you pull `main`:

> **Every `motion.*` in your code must become `m.*`, with `import { m } from "framer-motion"`.**

**Jest will NOT catch a miss.** `frontend/jest.setup.js` mocks `framer-motion` to a
passthrough, so `motion.div` and `m.div` both render fine in jsdom and every unit
test stays green. A stray `motion.*` only blows up **in a real browser**. So after
resolving, **load the affected page in a browser** (or a prod build) — do not trust
green jest alone. (This is the same "green but broken" class that shipped the
invisible landing CTAs in #225; the fix there added a Playwright visibility assert.)

## Files that will conflict (both sides changed them since `41916ed`)

| File | How to resolve |
|------|----------------|
| `frontend/app/dashboard/onboarding/page.tsx` | **Take your functional rework**, then convert every `motion.*` → `m.*` and ensure `import { m } from "framer-motion"`. Verify in a browser. |
| `frontend/app/dashboard/onboarding/approvals/page.tsx` | Same as above. |
| `docs/metrics.json` | Don't hand-merge. After resolving code, run `bash scripts/docs-freshness.sh --write` — it regenerates the counts from source. |
| `.planning/STATE.md` | Append-only file — keep **both** sides' rows/entries. |

As of this note the two onboarding files on your branch still use `motion.*`
(5 usages in `onboarding/page.tsx`, 7 in `approvals/page.tsx`) — those are the ones
to convert.

## Quick resolution recipe

```bash
git fetch origin main
git merge origin/main        # or rebase, per your team's convention
# For each conflicting onboarding page: keep YOUR component logic, then:
#   - change every `motion.<tag>`  →  `m.<tag>`
#   - ensure the import line is  `import { m } from "framer-motion"`
# .planning/STATE.md: keep both sides' appended rows.
git checkout --theirs docs/metrics.json 2>/dev/null || true   # then regenerate:
bash scripts/docs-freshness.sh --write
git add -A
# Verify BEFORE committing the merge — jest is blind to the motion./m. trap:
cd frontend && npm run build && npm run lint && npx jest
# Then load /dashboard/onboarding and /dashboard/onboarding/approvals in a browser
# (or a prod build) and confirm they render — the motion. → m. trap is browser-only.
```

## Sanity checks after integration

- `grep -rn 'motion\.' frontend/app frontend/components | grep -v '\bm\.'` should return
  nothing that is a real `motion.*` component (i.e. no `import { motion }` / `<motion.`).
- `frontend/app/page.tsx` and `frontend/app/for-operators/page.tsx` must stay Server
  Components (no `"use client"`) — the GSAP enhancers are separate client components.
- Marketing GSAP scenes are desktop-only; on mobile / `prefers-reduced-motion` they
  degrade to a visible framer-motion floor. Don't "fix" that — it's intentional.
