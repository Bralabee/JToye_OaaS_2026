---
id: 260730-n32
type: quick
status: complete
date: 2026-07-30
---

# Summary

Closed the last item `HANDOFF.md` §4 still listed as "not started", and fixed the same
document's habit of quoting HEADs that go stale.

## What changed

| file | change |
|---|---|
| `frontend/components/marketing/competitive-teardown.tsx` | raw `U+2715` → `lucide-react`'s `<X />`, matching 8 existing usages; `X` added to imports |
| `HANDOFF.md` | §5 states the shape; §6 step 1 resolves each repo's default branch and reports `dirty/ahead/behind` + an explicit `VOID`, instead of quoting SHAs |

## Two corrections to what was handed over

1. **`HANDOFF.md` §4 called the glyph "a real close-button finding". It is not a close
   button** — it is a decorative gap-marker (`aria-hidden="true"`) in the "hard gaps" list.
   The finding was real; the framing was not.
2. **My own first wording overstated `shrink-0`.** The break arm was run and *did not fire*
   on the shipped strings, so the class is **defensive, not currently load-bearing**. Kept,
   with corrected wording, on a mechanism that was then measured (16px → 11.27px under a
   longer label).

## Evidence

- `next build` rc=0 (type-checked — jest does not type-check), `eslint` rc=0, component
  jest suite **6/6**.
- Emoji scan, both directions: pre-fix file read out of git → `decorative-UI candidates: 1`
  printing the glyph; fixed tree → `0`. Run against a copy extracted into the scratchpad,
  **never by mutating and restoring the tree** — §0.2 records that restore failing 3× in one
  session.
- All 7 repo doc gates rc=0: claims 43, citations 62, docs-freshness 1851, doc-metrics 37,
  project-version 6, doc-versions 84, terminal-states.
- **Runtime, not just build:** frontend image rebuilt and container recreated; running
  container's image ID `77104523f2fa` equals the tag's. Rendered at 360×780 in a real
  browser: 6 icons for 6 declared `GAPS`, all painted at 16×16, 0 squashed, 0 glyphs left.

## Deliberately left

The two remaining frontend-wide emoji candidates — `🇬🇧 Independent UK kitchens` and
`⭐ {d.rating} · FHRS 5` — are product data, not decorative icons, per the project's own
recorded rule. Recorded as deliberate, not ignored.
