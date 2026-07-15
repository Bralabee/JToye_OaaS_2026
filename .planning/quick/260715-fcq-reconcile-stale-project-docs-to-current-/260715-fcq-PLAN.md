---
phase: quick-260715-fcq
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - CLAUDE.md
  - .planning/PROJECT.md
  - AGENTS.md
autonomous: true
requirements: [DOCS-RECONCILE]
must_haves:
  truths:
    - "CLAUDE.md ## Project header names milestone v2.3 (Vendor Ops + AI Interleaved), not 'Milestone 2: Tier 3'"
    - "CLAUDE.md Constraints cite 1395 logical invocations with a breakdown matching docs/metrics.json exactly"
    - "CLAUDE.md Configuration cites current schema version V56 with a brief V54/V55/V56 note; existing V51/V50 history prose preserved"
    - "PROJECT.md Key context bullet frames V51/1257 as the v2.2 predecessor baseline and states current branch state is V56/1395"
    - "AGENTS.md header + scope paragraph mirror the corrected CLAUDE.md milestone framing"
    - "docs/metrics.json is byte-for-byte unchanged and scripts/docs-freshness.sh stays GREEN"
  artifacts:
    - path: "CLAUDE.md"
      provides: "Current milestone identity + reconciled test/schema counts"
      contains: "Milestone v2.3"
    - path: ".planning/PROJECT.md"
      provides: "Predecessor-baseline clarification in Key context"
      contains: "V56 / 1395"
    - path: "AGENTS.md"
      provides: "Mirrored current milestone header"
      contains: "Milestone v2.3"
  key_links:
    - from: "CLAUDE.md counts"
      to: "docs/metrics.json"
      via: "prose mirrors JSON (not edited)"
      pattern: "1395"
---

<objective>
Reconcile stale human-facing prose in the repo's project docs to the current milestone state. `docs/metrics.json` is authoritative and correct (total_logical_invocations=1395, schema_version=56) and CI-green; only the prose narrative drifted — it still describes the previous milestone (Milestone 2: Tier 3) with stale counts (1257) and schema pointer (V51).

Purpose: Keep the developer-facing project instructions truthful so contributors and AI agents read current-state facts, and so the docs-freshness routine reflects reality.

Output: Corrected prose in CLAUDE.md, .planning/PROJECT.md, and AGENTS.md. No source code, no tests, no docs/metrics.json changes.

**HARD BOUNDARIES (do not cross):**
- Do NOT modify `docs/metrics.json` (it is already correct — mirror it, never edit it).
- Do NOT touch source code, tests, or any file under `core-java/`, `frontend/`, `edge-go/`, `mcp-server/`.
- Do NOT change test counts anywhere (this must not perturb the docs-freshness gate).
- Do NOT rewrite historical/changelog/archive references to past milestones — only fix prose that asserts CURRENT state.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
</execution_context>

<context>
@.planning/PROJECT.md
@docs/metrics.json

<facts>
Authoritative counts (from docs/metrics.json — mirror these verbatim, do NOT edit the JSON):
- total_logical_invocations: 1395
- java_test_methods: 982 across java_test_files: 169
- jest_blocks: 275 across jest_files: 38
- go_test_funcs: 77 across go_test_files: 9
- playwright_blocks: 34 across playwright_specs: 10
- mcp_test_blocks: 27 across mcp_test_files: 6
- schema_version: 56
Arithmetic check: 982 + 275 + 77 + 34 + 27 = 1395.

Current milestone identity (from .planning/PROJECT.md "Current Milestone" section — do NOT fabricate beyond this):
- v2.3 — Vendor Ops + AI Interleaved. Vendor operational control: unblock stuck onboarding, scope access per shop within a tenant, harden image handling (copy-on-write media_asset model + safe async upload pipeline), fix dashboard mobile; plus extend the AI/automation surface (outbound webhooks + mutating MCP tools) on a committed local-k8s overlay.

Schema history mapping (from PROJECT.md line 68 + STATE.md 22-03 decision):
- V54 = notification_consent (GDPR consent/suppression + unsubscribe, COMMS-03)
- V55 = webhook_subscription (COMMS-04/05/06)
- V56 = webhook_delivery (COMMS-04/05/06)
- V52 / V53 = RESERVED for Phases 23/24 (shop_staff, media_asset) — not yet applied → spring.flyway.out-of-order=true required.

Straggler-sweep classification (grep hits for "1257" / "Milestone 2: Tier 3" / "Tier 3 Enhancements" / current-tense V51):
FIX (assert CURRENT state): CLAUDE.md, .planning/PROJECT.md, AGENTS.md (:4 header + :6 scope para — GSD-generated mirror of PROJECT.md).
LEAVE (historical/archival — do NOT touch):
- .planning/ROADMAP.md — line 13 shipped-milestone list entry + line 34 "Schema at close: V51 / 1257" inside a `<details>` shipped-milestones archive block (explicitly a v2.2-close baseline).
- .planning/MILESTONES.md — archived milestone header.
- .planning/milestones/v2.2-ROADMAP.md — archive.
- .planning/phases/21-* — phase execution artifacts (planning record).
- .planning/quick/260714-2c4-*/SUMMARY.md — historical quick-task record.
- docs/CHANGELOG.md — changelog is history by definition.
- .planning/research/STACK.md — stale research-doc TITLE from an earlier milestone, not a live current-state claim.
</facts>
</context>

<tasks>

<task type="auto">
  <name>Task 1: Correct CLAUDE.md milestone identity, test counts, and schema pointer</name>
  <files>CLAUDE.md</files>
  <action>
Make three surgical prose edits to ./CLAUDE.md. Use the Edit tool; preserve all surrounding content and the file's existing dense-prose style.

1) `## Project` header (line ~4) — replace `**J'Toye OaaS — Milestone 2: Tier 3 Enhancements**` with `**J'Toye OaaS — Milestone v2.3: Vendor Ops + AI Interleaved**`.

2) Scope paragraph (line ~6) — keep the first sentence (the platform description: "J'Toye OaaS is a multi-tenant UK retail SaaS platform … through a shared infrastructure.") verbatim. Replace ONLY the second sentence ("This milestone adds vendor marketing tools, real-time kitchen displays, API versioning, and closes remaining test gaps from the previous milestone.") with a v2.3 framing sourced from `.planning/PROJECT.md` Current Milestone — e.g. it turns to vendor operational control: unblocking stuck onboarding, scoping access per shop within a tenant, hardening image handling (copy-on-write media_asset model + safe async upload pipeline), fixing dashboard mobile, and extending the AI/automation surface (outbound webhooks + mutating MCP tools) on a committed local-k8s overlay. Do NOT fabricate scope beyond PROJECT.md. Leave the `**Core Value:**` line untouched.

3) Constraints — Testing line (line ~15) — update counts to mirror docs/metrics.json EXACTLY (do not edit the JSON): `1257` → `1395`; and the parenthetical breakdown to `982 Java @Test methods across 169 files + 275 Jest it/test blocks across 38 files + 77 top-level Go Test* funcs across 9 files + 34 Playwright test() blocks across 10 specs + 27 MCP-server vitest it/test blocks across 6 files under mcp-server/`. Leave the trailing "Multiple Java files use Testcontainers … fails the build on drift." sentence unchanged.

4) Configuration — schema pointer (line ~108) — change `Current schema version: V51` → `Current schema version: V56`. Prepend a BRIEF note (matching existing dense-prose style) covering: V56/V55/V54 [Phase 22 Notifications & Comms] added the notifications/webhooks tables — notification_consent (V54), webhook_subscription (V55), webhook_delivery (V56) — all ENABLE+FORCE RLS tenant-scoped; V52/V53 remain RESERVED for Phases 23/24 (shop_staff / media_asset), so spring.flyway.out-of-order=true stays required. Then KEEP the entire existing "(V51 RLS uuid-cast safety [Issue #113 / P3-11]: …" history prose and all prior V50…V40 entries intact — do NOT delete history; only move the "current" pointer to V56 and prepend the new-migration note.
  </action>
  <verify>
    <automated>grep -n "Milestone v2.3: Vendor Ops" CLAUDE.md && grep -n "1395 logical invocations" CLAUDE.md && grep -n "Current schema version: V56" CLAUDE.md && grep -c "V51 RLS uuid-cast safety" CLAUDE.md && test -z "$(grep -n '1257\|Milestone 2: Tier 3' CLAUDE.md)" && echo OK-NO-STALE</automated>
  </verify>
  <done>CLAUDE.md header reads "Milestone v2.3: Vendor Ops + AI Interleaved"; Testing constraint reads 1395 with the metrics.json breakdown; schema pointer reads V56 with a brief V54/V55/V56 note while V51/V50 history prose is preserved; no "1257" or "Milestone 2: Tier 3" string remains in CLAUDE.md.</done>
</task>

<task type="auto">
  <name>Task 2: Clarify PROJECT.md predecessor baseline, mirror AGENTS.md header, and confirm straggler sweep</name>
  <files>.planning/PROJECT.md, AGENTS.md</files>
  <action>
Two surgical prose edits plus a confirmation grep.

1) `.planning/PROJECT.md` "Key context" bullet (line ~31) — currently: "Direct predecessor is v2.2 (production hardening + vendor order ops), fully merged to main; schema at V51, 1257 test invocations, docs-freshness green." Edit it so V51/1257 read as the v2.2 PREDECESSOR-CLOSE baseline and add the current branch state — e.g.: "Direct predecessor is v2.2 (production hardening + vendor order ops), fully merged to main; at v2.2 close schema was V51 with 1257 test invocations, docs-freshness green. Current branch state post-Phase-22 is schema V56 / 1395 test invocations." Minimal edit — do NOT touch the "[M2]"/"[v2.x]" requirement rows elsewhere in PROJECT.md (those are history).

2) `AGENTS.md` (GSD-generated mirror, `<!-- GSD:project-start source:PROJECT.md -->`) — apply the SAME milestone-identity correction as CLAUDE.md Task 1 edits 1+2: line ~4 header `**J'Toye OaaS — Milestone 2: Tier 3 Enhancements**` → `**J'Toye OaaS — Milestone v2.3: Vendor Ops + AI Interleaved**`; and the line ~6 second sentence ("This milestone adds vendor marketing tools…") → the same v2.3 framing used in CLAUDE.md. AGENTS.md does NOT carry the 1257/V51 payload, so no count/schema edits there.

3) Confirm the straggler sweep is complete — run the grep in verify. Every remaining hit MUST fall in the LEAVE set (ROADMAP.md, MILESTONES.md, milestones/v2.2-ROADMAP.md, phases/21-*, quick/260714-2c4-*, docs/CHANGELOG.md, research/STACK.md) which are historical/archival and MUST NOT be edited. Do NOT modify docs/metrics.json.
  </action>
  <verify>
    <automated>grep -n "Current branch state post-Phase-22 is schema V56 / 1395" .planning/PROJECT.md && grep -n "Milestone v2.3: Vendor Ops" AGENTS.md && test -z "$(grep -n '1257\|Milestone 2: Tier 3' AGENTS.md)" && git diff --name-only -- docs/metrics.json | grep -q . && echo "METRICS-CHANGED-FAIL" || echo "METRICS-UNCHANGED-OK"</automated>
  </verify>
  <done>PROJECT.md Key context bullet frames V51/1257 as the v2.2-close baseline and states current V56/1395; AGENTS.md header + scope paragraph mirror the corrected v2.3 framing; docs/metrics.json is unmodified; all remaining stale-string grep hits are confined to the historical/archival LEAVE set.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

Docs-only prose reconciliation. No code paths, endpoints, dependencies, or data flows are added or changed. No new trust boundary is introduced or crossed.

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-fcq-01 | Tampering | docs/metrics.json (CI source of truth) | mitigate | Plan forbids editing metrics.json; Task 2 verify asserts `git diff` shows it unchanged, and docs-freshness.sh must stay green. |
| T-fcq-02 | Information disclosure | prose edits | accept | Only public milestone/scope facts sourced from PROJECT.md; no secrets or PII involved. |
</threat_model>

<verification>
After both tasks, run the phase-level proof:

1. `bash scripts/docs-freshness.sh` — MUST exit 0 (GREEN). metrics.json is unchanged, so the gate that validates it stays green; run it as proof the docs reconciliation did not perturb the source of truth.
2. `grep -rn "1257\|Milestone 2: Tier 3" -- ./CLAUDE.md` — MUST return nothing (exit 1 / empty).
3. `git status --porcelain -- docs/metrics.json` — MUST return nothing (file untouched).
4. `git status --porcelain` — should show ONLY CLAUDE.md, .planning/PROJECT.md, AGENTS.md modified (plus this plan's own quick-dir artifacts). No source/test files.
</verification>

<success_criteria>
- CLAUDE.md identifies the current milestone as v2.3 (Vendor Ops + AI Interleaved) with a truthful scope sentence sourced from PROJECT.md.
- CLAUDE.md Testing constraint = 1395 with a breakdown that mirrors docs/metrics.json; schema pointer = V56 with a brief V54/V55/V56 note; V51/V50 history preserved.
- PROJECT.md Key context bullet scopes V51/1257 to the v2.2 predecessor close and records current V56/1395.
- AGENTS.md milestone header + scope paragraph match CLAUDE.md.
- Historical/changelog/archive references to past milestones are left intact.
- docs/metrics.json unchanged; `scripts/docs-freshness.sh` GREEN.
- Atomic commit on branch `feature/v2.3-milestone-init` (NOT main); metrics.json excluded.
</success_criteria>

<output>
Create `.planning/quick/260715-fcq-reconcile-stale-project-docs-to-current-/260715-fcq-SUMMARY.md` when done.
</output>
