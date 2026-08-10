---
phase: 28-security-triage-the-dev-prod-boundary
plan: 05
subsystem: security / disposition record + CI gate
tags: [SEC-02, pentest, triage, sanitization, keycloak, oauth-audience, falsifiability, D-11, D-12, "#548", "#549", "#551", "#628"]

requires:
  - plan: 28-01
    provides: "A1's FALSIFIED verdict and the measurement that settled it"
  - plan: 28-02
    provides: "C3's FIXED-BY-#442 disposition with three observed break arms"
  - plan: 28-03
    provides: "the #488 census and issue #626 (anonymous bucket listing)"
  - plan: 28-04
    provides: "#281's disposition and issue #627 (STOMP subscribe-only gating)"
provides:
  - "docs/security/PENTEST-TRIAGE.md — 11 of 11 findings dispositioned, sanitized, tracked"
  - "scripts/check-pentest-triage.sh — completeness + denominator + vocabulary gate, wired into ops-contracts"
  - "The E1 audience audit: full client table regenerated with jq, indirect resolve path measured inert, live 401 with a discriminating control"
  - "The D-12 realm-template payload for plan 28-10's single import (the unused public client removed)"
  - "Issue #628 — the second provisioning surface that would undo the removal"
affects: [28-07, 28-10, 28-11]

tech-stack:
  added: []
  patterns:
    - "A disposition record is only trustworthy with a gate that reds on a BLANKED cell, not merely a missing row — the assertion that tells a mention from a disposition"
    - "Delimited-table extraction: markers matched as whole comment lines, because a document that must NAME its own markers will otherwise toggle its own scanner"
    - "A live rejection arm is meaningless without a control admitted on the SAME endpoint in the same session"

key-files:
  created:
    - docs/security/PENTEST-TRIAGE.md
    - scripts/check-pentest-triage.sh
    - .planning/phases/28-security-triage-the-dev-prod-boundary/28-05-SUMMARY.md
  modified:
    - .github/workflows/ci-cd.yaml
    - infra/keycloak/realm-export.template.json

key-decisions:
  - "The unused PUBLIC realm client is REMOVED rather than documented — it has no working use, nothing depends on it, and keeping it is one accident deep"
  - "The gate is wired into ci-cd.yaml and deliberately NOT registered in gate-enforcement.conf: it reads one tracked file, which a hosted runner has"
  - "The bulk '8 of 11 already remediated' claim is not carried; every row states its own evidence"
  - "A second provisioning surface for the removed client is filed as #628 rather than folded in, so the realm edit stays one clean payload for 28-10"
  - "No Keycloak import was performed — the running realm still carries the old template by design"

requirements-completed: [SEC-02]

metrics:
  duration: ~22 min
  completed: 2026-08-10
  tasks: 3
  commits: 3
  break_arms_observed_red: 6
---

# Phase 28 Plan 05: Pentest Triage Record + Completeness Gate Summary

**All eleven findings now carry an evidenced, sanitized disposition in a tracked document that a CI
gate reds on four independent ways — including a blanked disposition cell, which is the arm that tells
a mention from a disposition — and E1 is settled by an actual token producing an actual 401 beside a
control admitted on the same endpoint.**

## Performance

- **Duration:** ~22 min (02:33Z → 02:55Z, 2026-08-10)
- **Tasks:** 3 of 3
- **Commits:** 3
- **Files touched:** exactly the four in the plan's `files_modified` — no more

## Task Commits

| Task | Artifact | Commit |
|---|---|---|
| 1 | `docs/security/PENTEST-TRIAGE.md` | `677bebdc` |
| 2 | `scripts/check-pentest-triage.sh` + its `ci-cd.yaml` step (same commit) | `3a377e12` |
| 3 | E1 live arm, realm-template removal, doc §5.3–5.5, E1 row upgraded | `5d8f1539` |

---

## 1. The eleven dispositions

| ID | Finding (sanitized) | Status | Settled by |
|----|---------------------|--------|------------|
| A1 | Cross-tenant read/write on two shop-content tables | **FALSIFIED** | Plan 28-01, against a stack rebuilt from HEAD: both tables carry a tenant column, RLS enabled **and** forced, two policies each. The write is blocked at the **service layer**, and that guard was shown to fail — exactly one named test red, restore hash-verified. |
| A2 | Header-supplied tenant context in the request path | FIXED | Filter profile-gated to dev/local/test; #440 removed the advertisement; plan 28-02 proved the strip on the **served** document with a filter-present control and a non-empty-paths denominator. **Do-not-re-file rule recorded** against `OpenApiConfig.java:51`. |
| A3 | Shop-scoped list returned 200 for a non-owned shop | FIXED | `OrderService.getOrdersByShop` calls `shopAccessService.require(shopId, ShopRole.STAFF)` before querying (read at `:315-318`); `ShopScopedListGateTest` carries the shape for products/promotions/announcements. |
| B1 | Database reachable off-host with a static credential | **OPEN-TRACKED** | Exposure half fixed by #510. Rotation + the runtime/owner role split are **OPEN under #552**, in flight in plans 28-07…28-10. Deliberately **not** pre-dated as fixed. |
| B2 | Monitoring UI reachable with a product-default credential | FIXED | Loopback-bound (#510); `verify-env.sh` rejects product-default and short values; `check-infra-exposure.sh` C1/C2/C3 interrogate the **running** instance and include a random-credential rejection control. Value rotation rides #552. |
| C1 | Local mail archive reachable unauthenticated | FIXED | `${JTOYE_BIND_HOST:-127.0.0.1}` in compose; confirmed live — `docker ps` shows `127.0.0.1:8025` only. |
| C2 | Metrics scrape endpoint unauthenticated | FIXED | Base profile `include: health,info` (`application.yml:430-437`); prod moves the scrape onto `MANAGEMENT_SERVER_PORT` (`application-prod.yml:105-120`), never published by the k8s Service/Ingress. |
| C3 | API contract readable unauthenticated | FIXED | **FIXED-BY-#442** (`looksLocal && !isDeployedProfile`), three permanent profile tests, one observed break arm reddening only the staging arm (plan 28-02). **#549's staging description is stale.** |
| C4 | Edge metrics endpoint unauthenticated | FIXED | #550, closed 2026-08-05. |
| D1 | Inbound webhook accepted unsigned requests | FIXED | Fail-closed at the edge (`handlers.go:249-262`): an **unset** signing secret refuses with 503 + Retry-After rather than skipping verification; missing/invalid signature → 401. |
| E1 | Realm export ships a client with no audience mapper | FIXED | This plan — §3 below. #551 closed. |

**The "8 of 11 already remediated" bulk claim was deliberately not carried.** Each row above states its
own evidence, because a bulk number cannot be falsified — and the two rows it would have swept up (B1
and E1) are precisely the two that were not finished.

---

## 2. The gate: four break arms, real rc values

`scripts/check-pentest-triage.sh` (185 lines) asserts **three** things — completeness, a row
denominator, and status-vocabulary + non-empty disposition. Bracketed clean → arms → clean again;
every restore verified **by content hash** against the committed blob
`898c862eed7ba9a4db9e0d3b629d1e3f377ed627`, never by `git diff --stat`. Source was committed **before**
the arms ran, so `git checkout --` restored from a committed state (`trap_break_arm_revert_eats_fixes`).

| Arm | Break applied | rc | What it printed |
|---|---|---|---|
| **Opening clean** | none | **0** | `PASS: all 11 findings carry a well-formed disposition`, 11 rows, all IDs listed |
| **FD1 — completeness** | deleted the C2 disposition row | **1** | `FAIL: 1 finding(s) have NO disposition row … : C2` — the ID is **named** |
| **FD2 — denominator** | added a twelfth row for the invented ID `Z9` | **1** | `row names 'Z9', which is not one of the 11 findings` + `the table holds 12 row(s) but there are 11 findings` |
| **FD3 — vocabulary** | blanked C1's disposition cell, leaving ID **and** status in place | **1** | `C1: the disposition cell is EMPTY — an ID with a status and nothing else is a mention, not a disposition` |
| **FD4 — fail-closed / self-match** | moved the document aside entirely | **2 (VOID)** | `VOID: triage record not found … a missing disposition record is not a clean one` |
| **Closing clean** | none | **0** | 11 rows, `git status --short` empty, blob hash unchanged |

**FD4 is also the self-match proof.** The script names all eleven IDs in its own source — it has to,
because the finding file is git-excluded. With the document gone it exited **2**, not 0: it did not
find its own IDs. Source assertions backing that: `TRIAGE_DOC` referenced **11** times, repo-wide
glob / recursive-search constructs **0**, and `| grep -q` in code **0** (raw count 1, on the header
line that names the forbidden shape — code-only count taken with comments stripped, positive control
`grep -c` = 3 proving the stripped filter can still match).

**FD3 is the arm that matters most.** It is the only one that distinguishes *mentioned* from
*dispositioned*, and it is what a well-meaning "I'll fill this in later" edit would trip.

### Gate count: 34 → 35

| Measurement | Before | After |
|---|---|---|
| `check-gate-enforcement.sh` gates | **34** | **35** |
| rc | 0 | **0** |
| workflows / exemptions | 6 / 6 | 6 / 6 (unchanged) |
| `grep -c check-pentest-triage scripts/gates/gate-enforcement.conf` | — | **0** |

The exemption count is unchanged on purpose: this gate reads one tracked markdown file by absolute
path, so the conf's stated bar ("a hosted runner does not have the thing this inspects") is false for
it. It is **wired**, not exempted.

### The wiring will actually run — asserted, not assumed

Parsed `ci-cd.yaml` rather than eyeballing it: the step
`Assert every pentest finding carries a disposition (28-05 SEC-02)` is one of **16** steps in the
`ops-contracts` job, that job has **no job-level `if:`**, and none of the workflow's three triggers
carries a `paths:` filter. So on a PR to `main` the step runs.

**Known and not a defect:** the push trigger is `[main, 'phase-*', 'phase/**']`, and this worktree
branch matches none of them, so first CI contact is at PR-open (`trap_phase_branch_ci_filter`).

---

## 3. E1 — settled by measurement

### 3.1 The live arm, with its control (2026-08-10T02:49Z, running local stack)

| Arm | Presented | Token endpoint | Protected core-API endpoint |
|---|---|---|---|
| 0 — baseline | no token | — | **401** |
| 1 — claim | a token from the **public** client, requested with **no client secret** | **200** | **401** |
| 2 — control | a token carrying the expected audience, **same endpoint, same session** | **200** | **200** |

The decoded `aud` claim was **absent** on arm 1 and `core-api` on arm 2. No token value, secret or
password was printed, stored, or written anywhere.

**Why all three readings were needed.** Arm 1's 401 alone is equally consistent with an endpoint that
refuses everything — arm 2 proves the endpoint **discriminates**. Arm 0 keeps arm 1 honest in the other
direction: the route is genuinely protected, so the 401 is not simply its default answer. And arm 1's
**200 at the token endpoint** is the finding itself, measured rather than inferred from a `publicClient`
flag.

### 3.2 The audit table, regenerated not transcribed

`jq` over both committed exports. Direct core-API audience mappers: **3** — the resource server's own
client and the two least-privilege machine clients, all intended. `edge-api` has none (correct — it
forwards user tokens); `storefront-client` has none (correct — trust-scoped by issuer).

**The indirect path, recorded with its measurement:** every client carries the `roles` default scope,
which holds an `oidc-audience-resolve-mapper` that adds the audience of any client for which the *user*
holds client roles.

| Measurement | Result |
|---|---|
| Clients carrying the `roles` default scope | **11 of 11** (→ 10 of 10 after the removal) |
| Client roles declared by `core-api` | **0** |
| Seeded users holding any client-role mapping | **0 of 8** |

Inert today. Accepted and **disclosed with the numbers**, because it is the mechanism that silently
widens the audience the day someone declares a `core-api` client role.

### 3.3 The decision: **REMOVE**, and why

`infra/keycloak/realm-export.template.json`: the unused public client object deleted (42 lines) plus
its dead empty entry in `roles.client` (1 line). Clients **11 → 10**. Verified after the edit:

- `jq -e .` on the template → **rc=0**; the same check on a deliberately corrupted copy → **rc=5**, so
  the pass is evidence about validity rather than about jq being lenient;
- `[.clients[].clientId]` no longer lists it; occurrences in the file **0** with a positive control
  (`core-api` = 10);
- the three intended audience mappers unchanged, re-measured.

Reasoning, recorded in the doc so a reviewer can disagree:

1. **No working use.** The load-testing harness's own comments say this client's tokens authorise
   nowhere and the working path is the intended client with its secret. Two architecture documents
   still carry a recipe using it — already dead — and one of them already asserts the client does not
   exist, which the removal makes true.
2. **Asymmetric cost to keep.** Its safety is one accident deep: an audience mapper, or a `core-api`
   client role granted to any user, turns a **credential-free** token request into an accepted API
   call. A client that needs two facts to stay harmless, in an export that gets copied to new
   environments, is worth more removed than documented.
3. **Nothing depends on it.** `searchcheck` agreed across search paths (17 files, all planning records,
   docs and the two realm/provisioning surfaces) — no application code, test or E2E spec.

### 3.4 NO KEYCLOAK IMPORT WAS PERFORMED — 28-10 owns it

**The running realm still carries the old template.** The export is the *source*; the realm lives in
the identity provider's database, so dropping a volume is a no-op and the change reaches the running
realm only through `kc.sh import --override true` **plus a restart**.

**Plan 28-10 performs that one import, carrying both D-02's rotation and this plan's D-12 payload** —
one import event, two payloads, exactly as the CONTEXT's `<specifics>` requires. This plan made
**exactly one** realm change (the template edit above) so 28-10 inherits a clean single payload; the
second provisioning surface that would have been the natural second edit is filed as **#628** instead.

Consequence to expect and not misread: until that import runs, §3.1's live arm remains reproducible on
the running stack. That is the designed state.

---

## 4. Issue outcomes

| Issue | Action | Verified as stored |
|---|---|---|
| #548 (tracking) | **CLOSED** + comment | 3708 chars; names `docs/security/PENTEST-TRIAGE.md` **2×**; contains `#552 remains **OPEN**` and `IS a completed triage` |
| #549 (C3) | **CLOSED** + comment | 1526 chars; names the record 2×; states the staging description is stale |
| #551 (E1) | **CLOSED** + comment | 3044 chars; names the record 2×; carries the client table, the indirect measurement, the three live statuses and the removal |
| #552 (B1) | **asserted still OPEN** | `gh issue view 552` → OPEN |
| #626 / #627 | untouched, still OPEN | recorded in the doc's §6 |
| #628 | **FILED** (new) | 2573 chars; sanitized, 0 leakage with a positive control |

Every body was written through `--body-file` (never an interpolating double-quoted string — backticks
inside `"…"` execute and silently drop the phrases they were meant to quote), and every comment was
**read back as stored** with `gh issue view --json`.

**Closing-keyword hazard checked, not assumed.** All three comment bodies and all three commit messages
were scanned for a closing keyword adjacent to an issue number (`(clos|fix|resolv)…#NNN`). One near-miss
was found and rewritten (`The fix landed with #442` → `#442 is the change that landed it`) even though
the intervening words would not have triggered the parser — the rule is number-before-keyword, and the
scan is cheap. Commit messages: zero occurrences.

**Sanitization proven, not asserted.** Ten secret-holding env key names counted **0** in the committed
document; the identical greps against `.env.example` counted **19**, so the greps can match. A
high-entropy scan (`[A-Za-z0-9+/=]{20,}`) returned three matches, all dictionary identifiers — a path
fragment, a JSON field name and a test class name — and a synthetic random token was matched by the
same pattern, proving the scanner is not blind.

---

## 5. Deviations from Plan

### Auto-fixed

**1. [Rule 1 — Instrument defect, caught by the gate's own denominator] The gate's first run swept the document's own vocabulary table**

- **Found during:** Task 2, first execution.
- **Issue:** the extractor matched the table markers as **bare tokens** with `index()`. The document's
  §2 has to *name* those markers in order to document the contract, so that prose switched the
  extractor on at the wrong place; the scan then swept §2's four-row status-vocabulary table in with
  the dispositions and reported **16 rows**. This is the recorded "a rule must name the string it
  forbids" shape, one artifact further along than `check-no-create-extension.sh` records it.
- **What caught it:** the **denominator** assertion, at `rc=1`. The completeness assertion was
  perfectly green throughout — all eleven IDs were found — over a demonstrably broken scan. Had the
  gate asserted only completeness, as a simpler reading of D-11 would have produced, it would have
  shipped green and stayed green over a scan that could not be trusted.
- **Fix:** markers matched as **whole HTML-comment lines** (anchored ERE) in both the existence check
  and the awk extractor; the document's §2 now refers to them only in truncated, mid-sentence form,
  and says why. Recorded in the script header so the next person does not delete the denominator for
  being redundant.
- **Commit:** `3a377e12`

**2. [Rule 3 — Blocking] The customers realm export is not valid JSON as committed**

- **Found during:** Task 1, running the audit.
- **Issue:** the plan's interfaces block names `infra/keycloak/realm-export-customers.json`. The actual
  file is `realm-export-customers.template.json`, and it carries **three unquoted** `${…}` placeholders
  (a boolean and two array bodies), so `jq` aborts with a parse error. A naive audit would have read
  the failure as "no clients found" and reported a clean sheet over an unparsed file.
- **Fix:** the audit substitutes those three placeholders before parsing, and the substitution is
  targeted rather than global — a blanket `${…}` replacement corrupts the file further, because other
  placeholders live *inside* quoted strings (including a long explanatory note). Recorded in the doc.
- **Commit:** `677bebdc`

**3. [Plan-directed] A second provisioning surface filed rather than fixed**

- **Found during:** Task 3, checking what depends on the client being removed.
- **Issue:** a legacy operator script under `infra/keycloak/` creates or updates the same client
  through the admin API. Removing it from the export alone leaves a removal that something else
  quietly undoes — a structural green over a live surface.
- **Fix:** filed as **#628**, per the plan's own instruction to file rather than widen. Measured first
  that the script is **not** in the automated bootstrap (neither compose nor the dev start-up script
  invokes it), so this is latent rather than live, and recorded that distinction rather than
  overstating it.
- **Commit:** `5d8f1539` (the record); issue #628.

### Tooling notes (not deviations)

- **`python3` is blocked in this environment** by an env-policy hook. FD3's cell-blanking was done with
  `awk` instead. No Python was needed; the hook's precondition was satisfied by not doing Python work,
  not by routing around it.
- **`gh issue close --comment "$(cat …)"`** was refused by the worktree-isolation guard, and it is the
  interpolating-string shape the Proof Standards forbid anyway. Comments were posted with
  `gh issue comment --body-file` and the issues closed separately.

---

## 6. Threat Model Outcomes

| Threat ID | Disposition | Evidence |
|---|---|---|
| T-28-19 Information disclosure across the sanitization boundary | **mitigated** | 0 secret-holding key names in the doc against 19 in the control; high-entropy scan clean with a synthetic-token control; 0 leakage in all four issue bodies read back **as stored**, each with a positive control |
| T-28-20 A realm client able to mint the audience unintentionally | **mitigated** | full client table regenerated with `jq`; the unused public client **removed** (11 → 10) with jq validity shown able to fail; live 401 with a discriminating control on the same endpoint |
| T-28-21 The indirect audience-resolve path | **accept, disclosed with its measurement** | 11 of 11 clients carry the scope, `core-api` declares 0 client roles, 0 of 8 users hold any client-role mapping — recorded in the doc with the consequence spelled out |
| T-28-22 A disposition record silently emptied or truncated | **mitigated** | three assertions, four break arms at rc 1/1/1/2, hash-verified restores, closing clean arm |
| T-28-23 The gate passing by matching its own source | **mitigated** | absolute-path scan, 0 globs, and FD4 proved it: document moved aside → **rc=2**, not 0 |

Cross-cutting dimensions: web-perf **N/A**, SEO **N/A**, agent-readiness **N/A** (no API surface
changed — the E1 arm only exercised existing endpoints). Falsifiable evidence: **6 break arms observed
red** (4 gate, 1 jq-validity, 1 row-count), 4 controlled sanitization greps, a positive control on the
live 401, and the clean state asserted last as well as first.

## Known Stubs

None. §5.3 of the document carried a "recorded below" placeholder between Tasks 1 and 3 by design; it
is filled and the E1 row upgraded `OPEN-TRACKED` → `FIXED` in `5d8f1539`. The one remaining placeholder
is **deliberate and named**: §6's third row reserves the slot for the default-privileges defect that
plan 28-07 files, because an omission is invisible where a placeholder is not.

## Threat Flags

None. No new network endpoint, auth path, file-access pattern or trust-boundary schema change. The
realm-template edit **narrows** an identity surface by removing a client.

## Notes for later plans

- **28-07** — file the default-privileges defect and put its issue number into the placeholder row in
  `docs/security/PENTEST-TRIAGE.md` §6.
- **28-10** — the realm template already carries the D-12 payload. **Do not re-edit it**; perform the
  ONE import carrying both this and the rotation. The running realm still has the old client until
  then, and §3.1's arm is reproducible until it does not.
- **28-11** — upgrade **B1's row** at close-out (it is the only `OPEN-TRACKED` row of the eleven), and
  own the manifest: this plan added **0** test invocations (one bash gate, which
  `docs-freshness.sh` does not count) and left `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md` and
  `README.md` untouched. Also consider whether **#628**, **#626** and **#627** belong in the close-out
  narrative — all three are recorded in §6 of the doc.
- **Any plan editing the triage doc** — the gate reads the table between whole-line comment markers.
  Never write a marker on a line of its own anywhere else in that document; §2 explains why.

## Self-Check: PASSED

| Claim | Verification | Result |
|---|---|---|
| `docs/security/PENTEST-TRIAGE.md` | `test -s` + blob `898c862e…` at HEAD~1, updated at HEAD | FOUND |
| `scripts/check-pentest-triage.sh` | `test -x`, 185 lines, `rc=0` | FOUND |
| `.github/workflows/ci-cd.yaml` step in `ops-contracts` | YAML parsed; step present, no job-level `if:` | FOUND |
| `infra/keycloak/realm-export.template.json` | `jq -e .` rc=0, 10 clients, 0 occurrences of the removed client | FOUND |
| Commit `677bebdc` / `3a377e12` / `5d8f1539` | `git log --oneline 8da7b451..HEAD` | ALL FOUND |
| #548 / #549 / #551 CLOSED, #552 OPEN, #628 OPEN | `gh issue view` per issue | AS CLAIMED |
| `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md`, `README.md`, `STATE.md`, `ROADMAP.md` | `git diff --name-only 8da7b451..HEAD` | NOT MODIFIED — the diff lists exactly the four planned files |
| Working tree | `git status --short` | empty before this SUMMARY |

---
*Phase: 28-security-triage-the-dev-prod-boundary*
*Completed: 2026-08-10*
