---
id: 260730-n32
type: quick
status: complete
date: 2026-07-30
---

# Quick: the last unfixed emoji-scan finding, and a handoff that still self-stales

Two small, unrelated-in-code but same-in-spirit fixes, both drawn from
`HANDOFF.md` §4 item 4 — the only named "not started" work left after PR #367 merged.

## Task 1 — `competitive-teardown.tsx:445`: a raw glyph standing in for an icon

`HANDOFF.md` §4 called this "a real close-button finding". **It is not a close button.**
It is a decorative gap-marker (`aria-hidden="true"`) inside the "What Flipdish has that
J'Toye does not" list. The framing was wrong; the finding was still real.

The actual defect is narrower and better-evidenced: a raw `U+2715` used as an icon in
rendered UI, where this repo has a settled precedent for exactly that job —
`lucide-react`'s `<X />`, used in 8 other components, including the directly analogous
`business-model-guide.tsx:211`:

```tsx
<BoundaryList title="We reject" icon={<X className="text-amber-600" />} ... />
```

Same "we reject / hard gaps" semantic, same `text-amber-600`. So this is not a judgement
call about which icon to use — it is one file that missed an existing convention.

**Change:** import `X` from `lucide-react`; replace the `<span>✕</span>` with
`<X aria-hidden="true" className="h-4 w-4 shrink-0 text-amber-600" />`.
`shrink-0` is added deliberately, and the first wording of this plan **overstated why** — it
claimed the icon squashes without it. It does not, on the six strings actually shipped.
Break arm at 360px, stripping only that class off the live nodes: widths
`16,16,16,16,16,16` → `16,16,16,16,16,16`, `squashedAfter=0`. **The arm did not fire.**

Re-run with a longer label (`"Marketing automation and lifecycle campaigns"`) in the same
card, which *did* fire: **16px → 11.27px without the class.** So the honest statement is
that `shrink-0` is **defensive, not currently load-bearing** — the mechanism is real and now
measured, but no shipped `GAPS` entry is long enough to trigger it. It stays on that basis,
and the sibling `ShieldCheck` in `business-model-guide.tsx` already carries the same class.

**Left alone, on the project's own recorded rule** (`feedback_emoji_product_content`:
the no-emoji rule targets decorative code emoji only) — the two remaining frontend-wide
candidates are product data: `🇬🇧 Independent UK kitchens` and `⭐ {d.rating} · FHRS 5`.

## Task 2 — `HANDOFF.md` §5/§6 still quote HEADs that go stale

The document's own header note says a handoff quoting its repo's HEAD "is stale the moment
it merges", and that those facts must be **run, not read**. §1's changelog SHAs are
fixed-in-time and legitimately stay. But §5 and §6 step 1 still *asserted* live HEADs —
`901cfba3` and `1d149d9` — and merging PR #367 invalidated the first one immediately,
reproducing the exact defect the preamble declares fixed, in the same document.

**Change:** §5 states the *shape* (default branch, clean, nothing unmerged) and defers to
§6. §6 step 1 becomes a loop that:
- **resolves** each repo's default branch from `origin/HEAD` rather than typing it —
  §3.2 already measured that dotfiles is `master`, and a routine hardcoding `main`
  commits to the wrong branch;
- reports `dirty` / `ahead` / `behind` instead of a SHA, so it cannot go stale;
- prints an explicit `VOID` line on a failed fetch or unreadable `origin/HEAD`, and says
  a VOID is not a pass — per Proof Standards, an empty result is the absence of a verdict.

## Falsification (Proof Standards §1 — fail direction first)

| assertion | fail direction | clean direction |
|---|---|---|
| emoji scan isolates the glyph | pre-fix file from `git show HEAD:` → `decorative-UI candidates: 1`, printing `✕` | fixed tree → `0` |
| the glyph grep can fire at all | pre-fix source → `1` (so the rendered `0` is a real absence, not an already-0 grep) | rendered page → `0` |
| new §6 step 1 detects a bad tree | run against the dirty working tree → `dirty=2 ahead=0 behind=0` | post-merge → `dirty=0 ahead=0 behind=0` |
| default branch is resolved, not assumed | same run reported dotfiles as `vs origin/master`, JToye as `vs origin/main` | — |
| `shrink-0` is load-bearing | **arm did not fire** on shipped strings (`squashedAfter=0`); fired only under a longer label, `16px → 11.27px` | claim downgraded to *defensive*, wording corrected |

**Verified against the delivered runtime, not the build** (Proof Standards §2): the frontend
image was rebuilt and the container recreated, and the running container's image ID was
compared to the tag's — `77104523f2fa` both sides, which is what catches a `start`-only.
Rendered at a **360×780** viewport in a real browser: `icons: 6` (matching the 6 declared
`GAPS` entries — a count, not merely "an svg exists"), `painted: 6` at exactly 16×16,
`squashed: 0`, `glyphLeftInRenderedText: 0`.

The pre-fix scan was run against a copy extracted from git into the scratchpad, **not** by
mutating and restoring the tree — §0.2 of the handoff records that same restore failing
three times in one session.
