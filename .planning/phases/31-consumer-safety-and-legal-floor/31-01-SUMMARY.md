---
phase: 31-consumer-safety-and-legal-floor
plan: 01
subsystem: testing
status: BLOCKED — awaiting human verdict at Task 1 package-legitimacy gate
tags: [accessibility, axe-core, jest-axe, playwright, radix-ui, supply-chain, wcag]

# Dependency graph
requires: []
provides:
  - "NOTHING YET — the plan is blocked at its first task, a blocking-human gate."
  - "Verified npm registry evidence for the four [ASSUMED] packages, so the human verdict can be given against measurements rather than against the researcher's recollection."
affects: [31-02, 31-03, 31-14, "every other LGL-02 plan — all of them depend on these installs"]

# Tech tracking
tech-stack:
  added: []          # deliberately empty — no package entered the tree
  patterns: []

key-files:
  created:
    - .planning/phases/31-consumer-safety-and-legal-floor/31-01-SUMMARY.md
  modified: []

key-decisions:
  - "No package was installed. Task 1 is type=checkpoint:human-verify with gate=blocking-human; auto_advance is false in .planning/config.json and package-legitimacy gates are excluded from auto-approval regardless."
  - "npm view --json returns an ARRAY. The first parser read j.scripts on that array and reported 'no postinstall' for all four packages — a vacuous pass. Caught by dist.integrity also reading undefined, and re-run with a positive control."
  - "jest-axe@10.0.0 pins axe-core at EXACTLY 4.10.2, so pinning axe-core@4.13.0 at top level does NOT govern which rules the jsdom layer runs. This corrects the plan's stated rationale and is put to the human."
  - "@types/jest-axe latest is 3.5.9 — a DefinitelyTyped stub seven majors behind jest-axe@10 that depends on axe-core ^3.5.5. Recommended for REMOVAL from the install line; escalated rather than silently dropped."

requirements-completed: []   # LGL-02 is NOT complete — nothing shipped

# Metrics
duration: 14min
completed: null
---

# Phase 31 Plan 01: Accessibility Instrument Bootstrap — CHECKPOINT, NOT COMPLETE

**Blocked at Task 1's blocking-human package-legitimacy gate with verified registry evidence
assembled for all four `[ASSUMED]` packages, plus two findings that change the plan's own
reasoning: `jest-axe@10` pins its own `axe-core@4.10.2`, and `@types/jest-axe` is a stale v3 stub
that would pull a third major of `axe-core`.**

## Performance

- **Duration:** ~14 min (evidence gathering only)
- **Started:** 2026-08-16
- **Completed:** NOT COMPLETE — awaiting human verdict
- **Tasks:** 0 of 3 complete
- **Files modified:** 0 (this SUMMARY is the only artefact)

## Status

| Task | Type | Status |
|------|------|--------|
| 1. Package legitimacy gate | `checkpoint:human-verify` `gate="blocking-human"` | **BLOCKED — awaiting four per-package verdicts** |
| 2. Install packages + resize checkbox primitive | `auto` | Not started (gated by Task 1) |
| 3. Prove the jsdom a11y instrument can fail | `auto` | Not started (gated by Task 2) |

Nothing was installed. `frontend/package.json`, `frontend/package-lock.json` and
`frontend/components/ui/` are untouched.

## Why this stopped rather than proceeding

`.planning/config.json` has `workflow.auto_advance: false` and `workflow._auto_chain_active: false`,
so auto mode is not active. Independently, package-legitimacy checkpoints carrying
`gate="blocking-human"` are excluded from auto-approval even when auto mode *is* active — precisely
because the failure this gate defends against (a slopsquatted or hallucinated package) is one an
agent under time pressure resolves by substituting a near-miss name.

`slopcheck` remains unavailable for the reason RESEARCH.md records: `pip install slopcheck` is
refused by the `block-base-python.py` hook and this project declares no `.conda-env`. It was not
rerouted around.

## Fail-direction evidence recorded BEFORE any change (Task 2's control arm)

Both of Task 2's acceptance assertions were confirmed red on the pre-change tree, which is what
makes them capable of proving anything later:

```
$ cd frontend && out=$(grep -cF 'axe' package.json || true); echo "axe_hits_in_package_json=$out"
axe_hits_in_package_json=0

$ ls components/ui/checkbox.tsx
ls: cannot access 'components/ui/checkbox.tsx': No such file or directory
ls_rc=2
```

`frontend/components/ui/` holds exactly 20 primitives and no `checkbox.tsx`, and
`frontend/package.json` declares 8 Radix packages (`react-alert-dialog`, `react-dialog`,
`react-dropdown-menu`, `react-label`, `react-select`, `react-slot`, `react-tabs`, `react-toast`) —
`@radix-ui/react-checkbox` is genuinely absent, exactly as the plan states.

## Instrument defect found and corrected during evidence gathering

The first pass at reading install-lifecycle scripts reported **"no postinstall"** for all four
packages. That result was an artefact, not a measurement.

`npm view <spec> --json` returns a **JSON array**, not an object. The parser read `j.scripts` on the
array, got `undefined`, and printed `postinstall: undefined` — which is indistinguishable from a
genuinely clean package. The tell was that `dist.integrity` also read `undefined`, and a published
version always has one:

```
$ npm view jest-axe@10.0.0 --json | node -e '... console.log(type=...)'
rawlen=5257
first120="[\n  {\n    \"_id\": \"jest-axe@10.0.0\", ..."
type=array
keys=0
```

The replacement checker asserts `dist.integrity` is present as a **positive control** and exits 2
(VOID) when it is not. Break arm run first, on a hand-made fake manifest:

```
$ echo '[{"name":"fake","version":"0.0.0","scripts":{}}]' | node pkgcheck.js
VOID: dist.integrity absent — parser is not reading a manifest
BREAK_ARM_rc=2
```

Every reading below comes from the checker **after** that break arm passed. The
`@axe-core/playwright` `prepare` script would have been missed entirely by the broken parser.

## Verified registry evidence — the four packages under gate

### 1. `axe-core@4.13.0` — clean on every axis checked

```
name:        axe-core@4.13.0
repository:  https://github.com/dequelabs/axe-core.git
license:     MPL-2.0
integrity:   sha512-UzGt8zg7Ny8djbYMh…  (positive control OK)
fileCount:   30  unpacked: 3113323
  preinstall  null
  install     null
  postinstall null
  prepare     null
dependencies: {}
maintainers:  ["dylanb <dylan@barrell.com>","wilcofiers <wilcofiers@gmail.com>",
               "dqlabs <labs@deque.com>","npmdeque <axe@deque.com>"]
```

Zero runtime dependencies, zero install-lifecycle scripts, Deque-controlled publisher accounts,
repository matches the authoritative one named in RESEARCH. Published 2026-08-06.

### 2. `@axe-core/playwright@4.13.0` — clean, but carries a `prepare` script RESEARCH did not record

```
name:        @axe-core/playwright@4.13.0
repository:  git+https://github.com/dequelabs/axe-core-npm.git
license:     MPL-2.0
integrity:   sha512-6YLx+kxXu5GJceG4o…  (positive control OK)
fileCount:   7  unpacked: 47180
  preinstall  null
  install     null
  postinstall null
  prepare     "npx playwright install && npm run build"
dependencies: {"axe-core":"~4.13.0"}
```

RESEARCH checked `scripts.postinstall` and `scripts.install` and correctly found both empty. It did
not check `prepare`, and `prepare` is present. **This is assessed as not an install-time hazard**:
npm runs `prepare` only for git/directory dependencies and in a package's own working tree, never
when installing a published registry tarball. It is recorded because the research table's "none"
column reads as broader than what was actually measured. `axe-core: ~4.13.0` is a range, so it
dedupes onto the top-level pin rather than nesting.

### 3. `jest-axe@10.0.0` — clean scripts, but two facts that change the plan's reasoning

```
name:        jest-axe@10.0.0
repository:  git+https://github.com/nickcolley/jest-axe.git
license:     MIT
integrity:   sha512-9QR0M7//o5UVRnEUU…  (positive control OK)
fileCount:   5  unpacked: 20990
  preinstall  null
  install     null
  postinstall null
  prepare     null
dependencies: {"chalk":"4.1.2","axe-core":"4.10.2",
               "lodash.merge":"4.6.2","jest-matcher-utils":"29.2.2"}
maintainers:  ["nickcolley <nickcolley7@gmail.com>"]
```

Version 10.0.0 exists and is installable; the published version list is
`1.0.0 … 9.0.0, 10.0.0, 11.0.0`, so 10.0.0 is real and 11.0.0 is latest, exactly as the plan states.

**(a) The plan's `jest-matcher-utils` reasoning holds.** Measured directly:
`jest-axe@10` → `jest-matcher-utils: 29.2.2`; `jest-axe@11` → `jest-matcher-utils: 30.4.1`.
The repo runs `jest@^29.7.0`. So v10 nests a second *copy* at the same major (29.x) while v11 would
nest a second *major* (30.x). The plan chose v10 for exactly this and the measurement agrees.

**(b) The plan's `axe-core` reasoning does NOT hold for the jsdom layer.** `jest-axe@10` declares
`axe-core: "4.10.2"` — an **exact** version, not a range. npm cannot dedupe an exact 4.10.2 onto a
top-level 4.13.0, so it will nest `axe-core@4.10.2` under `node_modules/jest-axe/`. The consequence:
the jsdom layer (Task 3, and every `jest-axe` assertion in later LGL-02 plans) runs **axe-core
4.10.2 rules**, while the Playwright layer runs 4.13.0. Pinning `axe-core@4.13.0` at top level still
does the job the plan wanted against `eslint-plugin-jsx-a11y` drift (threat T-31-01-04), but it does
not give the two a11y layers a single shared rule set. This is a correctness note for the human and
for plan 31-02, not a reason to reject the package.

`jest-axe` has a **single maintainer account** at ~2.5M weekly downloads. Not disqualifying, and the
repository matches the authoritative one — but it is the weakest publisher-concentration signal of
the four and the human should see it.

### 4. `@radix-ui/react-checkbox@1.3.11` — clean, React 19 compatible

```
name:        @radix-ui/react-checkbox@1.3.11
repository:  git+https://github.com/radix-ui/primitives.git
license:     MIT
integrity:   sha512-Gnptr9pDDQxD3hgq2…  (positive control OK)
fileCount:   9  unpacked: 76205
  preinstall  null
  install     null
  postinstall null
  prepare     null
maintainers:  ["hadihallak <hallak.aa@gmail.com>","chancestrickland <hi@chance.dev>",
               "mark-workos <mark@workos.com>","npm-workos <service+npm@workos.com>"]
```

`peerDependencies.react` is `^16.8 || ^17.0 || ^18.0 || ^19.0 || ^19.0.0-rc` — React 19 compatible,
which the repo requires. Published 2026-07-31. Repository is the same `radix-ui/primitives` monorepo
as the eight Radix packages already installed. The `*-workos` maintainer accounts are the point the
human should sanity-check against the existing eight.

## Additional finding — a fifth package the gate does not cover

The plan's Task 2 action line installs `@types/jest-axe` alongside the three gated packages, but the
gate itself lists only four packages. Measured:

```
name:        @types/jest-axe@3.5.9
repository:  https://github.com/DefinitelyTyped/DefinitelyTyped.git
license:     MIT
integrity:   sha512-z98CzR0yVDalCEuhG…  (positive control OK)
dependencies: {"axe-core":"^3.5.5","@types/jest":"*"}
maintainers:  ["types <ts-npm-types@microsoft.com>"]
```

`@types/jest-axe` is at **3.5.9** — a DefinitelyTyped stub for `jest-axe` **v3.5.x**, seven majors
behind the v10 being installed — and it depends on **`axe-core: ^3.5.5`**. Installing it would put a
**third major of `axe-core` (3.x)** into the tree alongside 4.13.0 and jest-axe's nested 4.10.2, and
its type definitions describe an API seven majors stale. That directly cuts against threat
T-31-01-04, whose whole point is that `axe-core` must not drift.

Recommendation put to the human: **drop `@types/jest-axe` from the install line** and, after
installing `jest-axe@10`, check whether it ships its own `index.d.ts` (its 5-file tarball is small
enough that this resolves immediately post-install). It is escalated here rather than silently
dropped, because silently substituting or omitting a package the plan names is the behaviour this
gate exists to prevent.

## Deviations from Plan

None yet — no task has executed. The three findings above (the `npm view --json` array defect, the
`jest-axe` exact `axe-core` pin, and the `@types/jest-axe` staleness) are evidence gathered *for* the
gate, not deviations from it.

## Issues Encountered

1. **Worktree base was behind the plan's base commit.** HEAD was at `bb2ae65d`, an ancestor of the
   required `64d9f0ad`. Corrected with the sanctioned setup-time `git reset --hard 64d9f0ad`;
   working tree was clean beforehand, so nothing was discarded.
2. **The first package-metadata instrument was vacuous** (see above). Replaced with a positive-control
   checker whose break arm was run first.

## Next Phase Readiness

**Blocked.** Every other LGL-02 plan in this phase depends on these three devDependencies and the
checkbox primitive. Nothing downstream can start until the four verdicts are given.

On resume, the remaining work is unchanged: install the approved packages, resize the shadcn
checkbox to `h-6 w-6` with the house focus ring, and land the two-arm `jest-axe` instrument test.

---
*Phase: 31-consumer-safety-and-legal-floor*
*Status: BLOCKED at Task 1 — human verdict required*
