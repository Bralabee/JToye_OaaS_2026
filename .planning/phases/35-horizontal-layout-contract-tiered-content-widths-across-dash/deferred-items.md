# Phase 35 — deferred / out-of-scope discoveries

Items found during execution that lie OUTSIDE the finding plan's declared file set.
Logged rather than fixed, per the executor scope boundary. One section per finding.

---

## D-35-07-a — `docs-freshness.sh` is VOID (rc=2), not merely stale

**Found by:** plan 35-07, during its closing gates (2026-08-29)
**Owner of the offending file:** plan **35-05**
**Owner of the remediation:** plan **35-11** (it owns `docs/metrics.json` and both docs gates)

`scripts/docs-freshness.sh` currently exits **2 (VOID)**, not 1 (drift):

```
ERROR: count-test-blocks.mjs could not count family 'jest' (rc=2):
VOID: frontend/app/dashboard/onboarding/__tests__/page.test.tsx:652:
      describe.each multiplies every block inside it; this counter cannot
      resolve that statically
      Treat this as UNVERIFIED, not as a pass. Extend the counter's POLICY.
```

Introduced by commit `5f9e39b4` — *test(35-05): assert the Detail tier on all three
onboarding branches (RED)* — which added a `describe.each(BRANCHES)` block. The file is
`frontend/app/dashboard/onboarding/__tests__/page.test.tsx`, in plan 35-05's file set and
in no other.

**Why this matters and is not cosmetic.** The counter is failing CLOSED, exactly as
designed: it refuses to report a number it cannot derive statically. The consequence is
that **no plan in this wave can measure its own Jest-block drift** until the counter's
policy is extended or the `describe.each` is rewritten. A reader must not translate this
into "no drift" — the correct reading is *unmeasured*.

**What 35-11 needs to know.** 35-11 Task 2's action text enumerates the plans that add
Jest blocks as "35-01, 35-02, 35-05, 35-08 and 35-09". That list is **incomplete**:
plan **35-07 adds 20 Jest blocks** (measured: the `components/marketing` + `app/shop`
scope moved 126 → 146 tests), across
`components/marketing/__tests__/{operator-pitch,business-model-guide,competitive-teardown}.test.tsx`
and `app/shop/__tests__/shop-discovery-client.test.tsx`. 35-06 also appears to add blocks.
Regenerate with `scripts/docs-freshness.sh --write`, never arithmetically — and note the
regeneration cannot even run until the VOID above is cleared.

**Not fixed here because:** the offending file belongs to another plan running
concurrently in the same working tree, and `docs/metrics.json`, `README.md`, `CLAUDE.md`
and `AGENTS.md` are all in plan 35-11's declared file set. Editing any of them from 35-07
would break the wave's zero-overlap property.

### Confirmed independently by plan 35-06, with its own block count

35-06 reproduced this VOID at its own closing gates (2026-08-29) and attributed it to the
same commit `5f9e39b4` by content (`describe.each` at
`frontend/app/dashboard/onboarding/__tests__/page.test.tsx:652` and `:688`) and by
`git log` on that path, rather than by inference.

35-07's guess above is correct: **35-06 adds 17 Jest blocks.** Measured against the parent
of 35-06's first commit (`6dd5ec2b`), counting literal block openings per file:

| File | before | after | delta |
|---|---|---|---|
| `frontend/app/__tests__/landing.test.tsx` | 9 | 16 | **+7** |
| `frontend/components/public/__tests__/public-header.test.tsx` | 6 | 11 | **+5** |
| `frontend/components/public/__tests__/public-footer-legal.test.tsx` | 10 | 15 | **+5** |

All 17 are plain `it(` blocks — 35-06 added no `it.each` and no `describe.each` — so the
declaration-site count and the executed count agree at 17, and neither of the repo's two
counters needs to resolve a table for them.

---

## D-35-06-a — `/legal/**` sits at 1152px and no tier in the contract claims it

**Found by:** plan 35-06, while proving the shed token was gone from `app/page.tsx` (2026-08-29)
**Owner of the offending file:** none in this phase
**Owner of the remediation:** unassigned — raise at the **35-13** owner gate

After 35-06 migrated the four landing bands, `rg -uu -n 'max-w-6xl' app components` returns
exactly one remaining hit in shipped source:

```
components/legal/policy-page.tsx:112:  <div className="mx-auto w-full max-w-6xl px-4 py-16 sm:px-6">
```

That component renders all five published policy pages (`/legal`, `/legal/privacy`,
`/legal/cookies`, `/legal/retention`, `/legal/accessibility`) — **public, indexable
surfaces**, all inside the same `PublicShell` whose header and footer rails now declare the
Marketing tier at 1280px. So those five pages now carry the exact defect ORCH-04 was raised
to fix on `/`: content inset 128px from its own chrome.

**Not fixed here because:** `components/legal/policy-page.tsx` is outside 35-06's declared
file set and outside every other plan's in this phase (checked against all twelve
`files_modified` blocks). CONTEXT.md section 4's tier table names the Marketing tier as
applying to "landing, for-operators, business-model-guide" and does not mention `/legal`,
so assigning it a tier is a **contract decision, not an executor fix** — and a prose page at
`max-w-[68ch]`-adjacent widths may well be deliberate. Recorded so the decision is taken
explicitly rather than by the surface being forgotten.

Note the two readings are genuinely different and both are defensible: 1152px may be the
right *reading* width for a policy page, in which case the correct outcome is a declared
tier that happens to be narrower — not silence.

---

## D-35-06-b — the landing's desktop CLS could rise on area alone, for 35-09

**Found by:** plan 35-06 (2026-08-29)
**Owner of the remediation:** plan **35-09** (it owns the ORCH-02 desktop CLS arm)

Not a defect and not deferred work — a specific, mechanical risk 35-09's arm should be
shaped to catch, recorded here so it is not rediscovered by accident.

`e2e/perf-budgets.ts:49-56` records the single CLS shift on `/` as firing at ~1516 ms with
its `sources` being **hero elements** — the search form, the category chips, the paragraph
and both persona doors. Every one of those sits inside the hero band 35-06 just widened
from 1152px to 1280px.

CLS is **area-weighted**: the impact fraction is the union of the shifting region's visible
area before and after, over the viewport area. A hero region that is ~11% wider therefore
produces a ~11% larger impact fraction for an *identical* vertical displacement. The
distance factor is unchanged; the impact factor is not.

This cannot show at mobile — `.max-w-marketing{max-width:1280px}` is emitted with no media
query (verified against the generated stylesheet), so it cannot bind against a 375px or
390px parent, and the recorded 0.1793 baseline was measured at 375px. **Desktop is the only
place this can appear, and nothing in the repo measured desktop CLS on `/` before 35-09.**

---

## The Compose frontend runtime predates wave 3 — measured, not fixed here

**Found by:** plan 35-08 (2026-08-29)
**Owner of the remediation:** plans **35-12 / 35-13** (they own runtime parity and the phase close)

`scripts/check-runtime-freshness.sh` on this branch:

```
frontend     DRIFT  [image-not-rebuilt]  image tagged 2026-08-29 15:42:00 UTC
                    / newest build-input commit 34256f5c (2026-08-29 19:40:26 UTC)
core-java    FRESH    edge-go  FRESH    mcp-server  FRESH
FAIL: 1 of 4 running built service(s) do not match the source tree (0 unverified).
```

Corroborated structurally before the gate was consulted, which is the cheaper signal:
`curl localhost:3000/` returns **0** occurrences of the tier attribute, so the running image
predates 35-06 entirely.

**Why this is recorded rather than fixed.** It is outside 35-08's file set, and 35-08 did not
need it: every measurement was taken against a locally built `next start`, which is also what
the per-PR CI job does. But it is a trap for any later plan that measures against `:3000` and
reads a confident red as a product defect — the failure would be real, current, and about the
wrong tree.

**The recipe 35-08 used, so it does not have to be rediscovered.** Build once, then serve on a
port Keycloak already accepts as a redirect URI (`infra/keycloak/realm-export.json` lists 3000,
3100, 9090, 8080 — **3011 is NOT among them**, so the dashboard half cannot log in there):

```
cd frontend
NEXT_PUBLIC_API_URL=http://localhost:9090 \
NEXT_PUBLIC_KEYCLOAK_URL=http://localhost:8085/realms/jtoye-dev \
NEXTAUTH_URL=http://localhost:3100 NEXTAUTH_SECRET=<from .env> npm run build
# then `next start -p 3100` with KEYCLOAK_ISSUER_INTERNAL and CORE_API_INTERNAL_URL
# pointed at localhost (the compose values name container hostnames the host cannot resolve)
```

A second server on **:3011** with `CORE_API_INTERNAL_URL` pointed at a dead port reproduces the
**stack-free CI shape** from the same artefact. That arm is worth keeping: it is what showed
that `/` serves **5** marketing bands with no backend and **6** with one, because the kitchen row
is `{shops.length > 0 && …}` from a server fetch. An exact band count taken from a live-stack
probe would have red-ed the per-PR gate on every pull request.

---

## From 35-10 — `check-e2e-skip-budget.sh` is rc=2 VOID, and it is 35-12's

Measured 2026-08-29 on `feature/35-horizontal-layout-contract`, before this plan changed
anything and again after:

```
check-e2e-skip-budget  (2026-08-29T21:12:16Z)
VOID: report describes a DIFFERENT spec set than the tree — re-run the suite.
        report : e1c6611559839202bf4ffd598c4d24107a4b4a0b19e0bedbb53b07deb20e088b
        tree   : 53a74f730a2ffc7a25242cf8b8eab0965b67912c6649bd4f082db465f0be71e0
```

The gate compares CONTENT, so this is not an mtime artefact — the stored report genuinely
describes a spec set that predates this branch's spec changes (35-08 added
`e2e/layout-width-contract.spec.ts`; 35-09 touched `landing-webperf.spec.ts`). It is failing
**closed**, exactly as designed.

**A fresh full-suite report clears it, and that run is 35-12's.** Not fixed here: 35-10 adds
no spec and touches no `e2e/` file, so it cannot produce the report the gate wants, and
hand-editing the stored hash would convert a correct VOID into a false green — the precise
inversion this repo's own proof standards forbid. Recorded so the next reader knows the VOID
is understood rather than unnoticed.

**Related and still open:** `#686` records the skip budget at 7/6, and the gate is wired only
into the dark nightly lane (`#683`), so on a current tree it fires nowhere at all. That is a
separate defect from this VOID and is not 35-10's either.

## From 35-10 — PATTERNS.md's G-2 attribution is wrong, and PATTERNS was not edited

PATTERNS.md section F-1 and section 6 attribute the naive `container` substring noise (269
hits / 55 files by its count, 371 comment-stripped lines / 55 files by mine) to
`DialogContent`, `CardContent` and `TabsContent`. **None of those three identifiers contains
the string `container`** — "Content" is not "container". Measured: the hits are Testing
Library's `container` local (189 bare, 70 `container.querySelector`, 30
`container.querySelectorAll`, 27 `container.firstElementChild`, 15 `container.textContent`, 9
`containerRequest`), every one in a test file, plus 2 occurrences of `.container` which are
`dashboard-shell.test.tsx`'s own absence guard. Shipped non-test source is **0**
case-sensitively and **10** case-INSENSITIVELY — `ResponsiveContainer` ×8 (recharts) and
`staggerContainer` ×2 (framer-motion).

The correction is recorded in `scripts/check-layout-width-contract.sh`'s header, where it is
load-bearing, and in `35-10-SUMMARY.md`. **PATTERNS.md itself was NOT edited**: it is a dated
planning artefact of this phase and rewriting its measurements after the fact would destroy
the record of what the plans were actually built against. Whoever revisits the inventory
should read the gate header alongside it.
