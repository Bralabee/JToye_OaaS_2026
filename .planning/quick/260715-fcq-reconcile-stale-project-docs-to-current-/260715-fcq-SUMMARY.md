---
phase: quick-260715-fcq
plan: 01
subsystem: project-docs
tags: [docs, reconciliation, metrics, milestone-identity]
requires: []
provides: [current-milestone-identity, reconciled-test-counts, current-schema-pointer]
affects: [CLAUDE.md, .planning/PROJECT.md, AGENTS.md]
tech-stack:
  added: []
  patterns: [docs-freshness-mirror]
key-files:
  created: []
  modified:
    - CLAUDE.md
    - .planning/PROJECT.md
    - AGENTS.md
decisions:
  - "Mirrored the LIVE docs/metrics.json (1401/988/170), not the plan's stale 1395 snapshot — the plan's binding intent is 'match metrics.json exactly'."
metrics:
  duration: ~14m
  completed: 2026-07-15
  tasks: 2
  files: 3
  commits: 3
---

# Phase quick-260715-fcq: Reconcile Stale Project Docs Summary

Reconciled stale current-state prose in `CLAUDE.md`, `.planning/PROJECT.md`, and `AGENTS.md` from the previous milestone (Milestone 2: Tier 3 / 1257 / V51) to the live milestone (v2.3 Vendor Ops + AI Interleaved) and the authoritative `docs/metrics.json` (1401 invocations / schema V56); `docs/metrics.json` left byte-for-byte unchanged and `docs-freshness.sh` stays GREEN.

## What Was Done

### Task 1 — CLAUDE.md milestone identity, test counts, schema pointer (commits f5b3ddf, aed0929)
- `## Project` header: `Milestone 2: Tier 3 Enhancements` → `Milestone v2.3: Vendor Ops + AI Interleaved`.
- Scope paragraph second sentence reframed to v2.3 (vendor operational control: unblock onboarding, per-shop scoped access, copy-on-write media_asset image handling + safe async upload, dashboard mobile, outbound webhooks + mutating MCP tools on a local-k8s overlay), sourced verbatim-in-substance from PROJECT.md Current Milestone. First sentence (platform description) and the `**Core Value:**` line left untouched.
- Testing constraint counts updated to mirror `docs/metrics.json` exactly: **1401** logical invocations (988 Java `@Test` across 170 files + 275 Jest across 38 + 77 Go across 9 + 34 Playwright across 10 + 27 MCP vitest across 6). Trailing Testcontainers/CI-gate sentence unchanged.
- Configuration schema pointer: `Current schema version: V51` → `V56`, with a prepended brief note (V54 `notification_consent` / V55 `webhook_subscription` / V56 `webhook_delivery`, all ENABLE+FORCE RLS; V52/V53 reserved for Phases 23/24 → `out-of-order=true` stays required). The entire V51…V40 history prose was preserved intact.

### Task 2 — PROJECT.md predecessor baseline + AGENTS.md mirror (commits 45a77ef, aed0929)
- PROJECT.md "Key context" bullet (line 31): V51/1257 reframed as the **v2.2 predecessor-close** baseline, and the current branch state recorded as **schema V56 / 1401 test invocations**.
- AGENTS.md header + scope paragraph mirror the corrected CLAUDE.md v2.3 framing (AGENTS.md carries no count/schema payload, so no count edits there).
- Straggler sweep confirmed: no `1257` or `Milestone 2: Tier 3` remains in CLAUDE.md or AGENTS.md; all remaining repo hits are confined to the historical/archival LEAVE set.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 — Stale-data bug] Mirrored LIVE metrics.json (1401/988/170), not the plan's 1395 snapshot**
- **Found during:** Verification (docs-freshness.sh printed 1401, not the 1395 the plan `<facts>` block and orchestrator constraint expected).
- **Issue:** The plan's `<facts>` (and the orchestrator's constraint text) hardcoded `1395 / 982 Java methods / 169 files`, captured from a metrics.json snapshot that had since moved. The live, committed, CI-green `docs/metrics.json` is **1401 / 988 methods / 170 files** — confirmed three independent ways: fresh Read, `git show HEAD~2:docs/metrics.json`, and `docs-freshness.sh` recomputing from source (=1401, MATCH). The bump came from concurrent commit `5d7b88d` (CR-01 webhook SSRF fix, which added `WebhookSsrfResolverTest` = 6 Java tests: 982→988, 1395→1401). The must_have "breakdown matching docs/metrics.json **exactly**" and the literal `1395` became contradictory; the invariant (match metrics.json) wins — writing 1395 would have re-introduced the exact prose-vs-source drift this task exists to remove.
- **Fix:** Mirrored the authoritative live values in CLAUDE.md (1401/988/170) and PROJECT.md (1401). Same scope as the plan, corrected literal.
- **Files modified:** CLAUDE.md, .planning/PROJECT.md
- **Commit:** aed0929
- **Boundary respected:** `docs/metrics.json` NOT edited (asserted clean across all 3 commits); no test counts changed; docs-freshness gate unperturbed and GREEN.

## Out-of-Scope Observations (surfaced, NOT fixed — respecting plan scope)

1. **PROJECT.md line 135 stale `1257`** — the "Testing" constraint bullet reads `baseline is 1257 logical invocations`, a stale current-state claim. The plan explicitly scoped PROJECT.md edits to the Key context bullet (line 31) only, and line 135 is absent from the must_haves. Left unedited to avoid improvising scope. **Recommend a follow-up** to bump 1257→1401 there for full internal consistency.

2. **Concurrent writers on the shared branch** — while executing, a parallel phase-22 security-finalization process committed to `feature/v2.3-milestone-init`: `5d7b88d` (SSRF fix, bumped metrics 1395→1401), and `4259a57` (`docs(phase-22): security gate SECURED`, which committed `.planning/phases/22-notifications-comms/22-SECURITY.md` + `deferred-items.md`). Those two files' changes belong to `4259a57`, **not** to any of my commits — each of my three commits was verified via `git show --stat` to touch only its intended work-product file(s). HEAD is now `4259a57` (ahead of my `aed0929`); my commits are intact in the linear history. Left the concurrent files untouched per the SCOPE BOUNDARY rule. Noting only so the interleaving is visible.

## Verification Results

| Check | Command | Result |
|-------|---------|--------|
| docs-freshness GREEN | `bash scripts/docs-freshness.sh` | `OK: metrics match source (total logical invocations: 1401)` — exit 0 |
| No stale strings in work-product files | `grep -rn "Milestone 2: Tier 3\|1257" ./CLAUDE.md AGENTS.md` | empty (exit 1) — PASS |
| metrics.json untouched | `git diff --quiet HEAD~3 HEAD -- docs/metrics.json` | UNCHANGED across all 3 fcq commits |
| Arithmetic | 988+275+77+34+27 | = 1401 (mirrors metrics.json) |
| CLAUDE.md schema pointer | `grep "Current schema version: V56"` | present; V51 history preserved (grep -c `V51 RLS uuid-cast safety` = 1) |
| Commit scope | `git show --name-only` per commit | union = {CLAUDE.md, .planning/PROJECT.md, AGENTS.md} only |
| Remaining `Milestone 2: Tier 3` repo hit | grep --include=*.md | only `docs/CHANGELOG.md:263` (changelog = LEAVE set) |

## Commits

- `f5b3ddf` docs(260715-fcq): reconcile CLAUDE.md milestone identity + metrics to v2.3/1395/V56
- `45a77ef` docs(260715-fcq): scope PROJECT.md V51/1257 to v2.2 close + mirror AGENTS.md to v2.3
- `aed0929` docs(260715-fcq): mirror LIVE metrics.json (1401/988/170), not stale plan snapshot (1395)

(Branch `feature/v2.3-milestone-init`, never main. Metrics.json excluded from every commit.)

## Self-Check: PASSED

- Files exist: CLAUDE.md ✓, .planning/PROJECT.md ✓, AGENTS.md ✓
- Commits exist: f5b3ddf ✓, 45a77ef ✓, aed0929 ✓
- AGENTS.md scope paragraph landed (`vendor operational control`) ✓
- docs/metrics.json unchanged; docs-freshness GREEN ✓
