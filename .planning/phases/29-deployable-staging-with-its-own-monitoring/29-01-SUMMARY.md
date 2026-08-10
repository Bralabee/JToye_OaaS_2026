---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 01
subsystem: infra
tags: [azure, aks, postgresql, rabbitmq, redis, cost-management, ghcr, dns, adr]

# Dependency graph
requires:
  - phase: 28-security-triage-and-the-dev-prod-boundary
    provides: "docs/runbooks/credential-rotation.md — the path every staging credential follows (its §6 names Phase 29 explicitly); the owner/runtime DB role split that the managed Flexible Server inherits"
  - phase: 27-observability-hardening
    provides: "deferred-items.md §5 — the 'compose-scoped by construction' record DPLY-03 exists to close; ADR-0002's 2026-07-29 rabbitmq-k8s open question"
provides:
  - "29-OPERATOR-DECISIONS.md — the dated, owner-signed record every downstream plan in this phase reads its SKU/count/ceiling/disposition out of"
  - "SNACKPASS_DISPOSITION=scale-to-zero, decided by the owner against re-measured cost, with the before-state replica table 29-10 must record its after-state against"
  - "ADR-0002 Status = Accepted (2026-08-10) — DPLY-04 unblocked by a record rather than an assumption"
  - "The PostgreSQL 16 requirement written down with its BYPASSRLS evidence as a deliberate deviation from CLAUDE.md's PostgreSQL 15"
  - "The rabbitmq-k8s horizon row answered: cluster-operator v2.22.3 + rabbitmq:4.3.4-management-alpine"
  - "Assumption A5 resolved by measurement — all three jtoye GHCR packages are PUBLIC, so no imagePullSecret is needed"
  - "Assumption A3 resolved by measurement — the £0.00 Postgres line is a free-tier offer, and its window closes ~2027-07-21"
  - "Assumption A4's premise CORRECTED — the snackpass estate is a different project, not a prior deploy of this platform"
affects: [29-04, 29-05, 29-07, 29-09, 29-10, 29-12, 29-13, 29-16]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Decision record as an interface: downstream plans grep named keys out of a dated file rather than re-deriving values"
    - "Every credential recorded PRESENT/ABSENT with the command output that established it, and the plan each absence blocks"
    - "Machine-measure before asking a human — two of Task 2's four items were resolved without operator input"

key-files:
  created:
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/29-OPERATOR-DECISIONS.md
  modified:
    - docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md

key-decisions:
  - "SNACKPASS_DISPOSITION = scale-to-zero — reversible, and the estate turned out to belong to a different project"
  - "NODE_VM_SIZE = Standard_B2s, NODE_COUNT = 3, MONTHLY_CEILING_GBP = 150 unchanged (no D-03 supersession); ~£3.00/month headroom knowingly accepted"
  - "PG_SERVER_VERSION = 16 is a requirement, not a preference — BYPASSRLS is impossible for a non-admin role on Azure Flexible Server at PG15 or earlier"
  - "The snackpass-pg free-window expiry and the Azure Cache Basic retirement are recorded as horizons in ADR-0002; the rows land in 29-09, which already edits dependency-horizons.yaml"
  - "29-01 deliberately does NOT touch infra/dependency-horizons.yaml, to avoid a parallel-wave conflict with 29-09"

patterns-established:
  - "Falsify the instrument before trusting a negative: an empty dig answer and a broken resolver are the same observation, so the DNS probe carries a positive resolver control"
  - "A control that only separates public from ABSENT is not a private-vs-public control — the GHCR probe was re-falsified against packages that provably exist"
  - "Prove a section is untouched by content hash, not by diff shape — the first hash arm caught a real 1-line delta that diff --stat would have hidden"

requirements-completed: [DPLY-01, DPLY-03, DPLY-04]

# Metrics
duration: 42min
completed: 2026-08-10
---

# Phase 29 Plan 01: Operator Decisions + ADR-0002 Sign-off Summary

**The snackpass estate is scale-to-zero by owner decision against a re-measured £101/mo run-rate, ADR-0002 reads Accepted, and the PostgreSQL 16 BYPASSRLS requirement is written down instead of hiding inside a SKU argument.**

## Performance

- **Duration:** 42 min
- **Started:** 2026-08-10T20:41Z
- **Completed:** 2026-08-10T21:23Z
- **Tasks:** 3 (2 of them blocking checkpoints)
- **Files modified:** 2

## Accomplishments

- **The owner's decision is recorded, dated and attributed**, against evidence re-measured on the day rather than the research's figures quoted back. No Azure resource was created, deleted or scaled — Task 1 forbids it and plan 29-10 owns execution.
- **Three of the research's premises were corrected by measurement**, two of which would have changed what a later plan did (see Issues Encountered).
- **ADR-0002 is signed** with the sign-off venue named, closing its own 2026-07-29 open question, and **DPLY-04 is unblocked by a record rather than an assumption.**
- **Two of Task 2's four operator items were resolved without the operator**, by measuring rather than asking.

## Task Commits

1. **Task 1: Owner decision — the snackpass estate and the £150 ceiling** — `fa11e325` (docs)
2. **Task 2: Credential presence — the measured half** — `f1a56007` (docs)
3. **Task 3: Record the decisions and sign ADR-0002** — `9555dcd6` (docs)

## Files Created/Modified

- `.planning/phases/29-deployable-staging-with-its-own-monitoring/29-OPERATOR-DECISIONS.md` — the dated record: 12 decision keys, 5 credential-presence keys, the re-measured estate, the three corrected findings, and 5 follow-up obligations
- `docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md` — Status → Accepted (2026-08-10) plus one appended dated section

## Decisions Made

Owner decisions (via the orchestrator's `AskUserQuestion` gate, auto-mode off) are recorded verbatim in §3 of the record. The judgement calls this plan made itself:

- **Did not touch `infra/dependency-horizons.yaml`.** The owner asked for the free-window expiry to be recorded as a horizon row. Plan **29-09** already edits that file for the `rabbitmq-k8s` row, and this is a parallel-wave worktree — two agents editing one YAML file is a merge conflict waiting to happen. The *reason* is written into ADR-0002 (so it survives independently) and the row is assigned to 29-09 as obligation O-2/O-5. The owner's instruction is honoured; only its landing place moved.
- **Recorded `GRAFANA_ADMIN_PASSWORD` as self-suppliable rather than ABSENT.** The rotation runbook generates such values with the system CSPRNG; calling it "missing" would have manufactured a blocker that does not exist. It is deliberately not one of Task 2's four items.
- **Left `## Decision (proposed)` unedited** in the ADR despite now reading stale. The plan says exactly two changes, append-style, and the file's convention is that dated records are appended, never rewritten. The new section says so explicitly so a reader is not confused.
- **Used `awk` rather than Python for the cost arithmetic.** A hook blocked base-env Python and this repo declares no conda env; declaring one would have added a file outside the plan's `files_modified` for ten additions of arithmetic.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Recorded the resource-inventory gap the research left**

- **Found during:** Task 1
- **Issue:** The research inventoried 8 resources in `jtoye-rg`. The live measurement returned **10** — `jtoye-bootcamp` (a Free-tier Static Web App in West Europe, backed by a separate repo) was absent from the research entirely. A disposition executed by resource-group scope could have swept up an unrelated project.
- **Fix:** Recorded in §2.1 with its tier, region and source repo, and explicitly excluded from the snackpass disposition.
- **Verification:** `az resource list -g jtoye-rg` output pasted verbatim into the record.
- **Committed in:** `fa11e325`

**2. [Rule 2 - Missing Critical] Added the O-1 verification obligation for a mechanism claim**

- **Found during:** Task 1
- **Issue:** The measurement that made `scale-to-zero` attractive (99.88% of spend is idle-metered) supports a claim about the *future* — that `minReplicas: 0` will collapse the bill. Recording that as established would be a structural-green-over-a-dead-feature failure: the config would read `minReplicas: 0` while the meters kept billing.
- **Fix:** Recorded as an explicit mechanism claim, with obligation **O-1** requiring the two idle meters be re-measured ~48h after 29-10 applies the change. The owner accepted this caveat in their answer.
- **Verification:** O-1 names the exact meters and the revisit trigger.
- **Committed in:** `fa11e325`

**3. [Rule 1 - Bug] Replaced a GHCR control that could not distinguish private from absent**

- **Found during:** Task 1 (pre-measuring for Task 2)
- **Issue:** The first fail-direction arm probed a nonexistent repo, which returns `NO-TOKEN`. That only separates public from *absent* — it says nothing about private, so the three PUBLIC results were not yet trustworthy.
- **Fix:** Re-ran against `bralabee/snackpass-*`, which provably **exist** (Container Apps is pulling them with a registry credential) and also return `NO-TOKEN`. That makes it a genuine existing-but-private control.
- **Verification:** Both the weak and the valid control are recorded in §7.1, weak arm included, because the replacement is the instructive part.
- **Committed in:** `f1a56007`

**4. [Rule 1 - Bug] Section-identity check initially reported a false difference**

- **Found during:** Task 3
- **Issue:** The byte-identity proof for the 2026-07-29 section reported `FAIL` on a 1-line delta. Investigation showed the delta was the blank separator line that the newly appended section structurally requires — the 29 content lines were untouched.
- **Fix:** Re-ran with trailing-blank normalisation only, plus two fail arms proving the normalisation still detects a one-word edit *and* an appended non-blank sentence, so it is not swallowing real changes.
- **Verification:** Both hashes `f7d3c3ae…`, 29 lines each; mutation arms produce different hashes.
- **Committed in:** `9555dcd6`

---

**Total deviations:** 4 auto-fixed (2 missing critical, 2 bugs)
**Impact on plan:** All four are recording/verification corrections. No scope creep — the file set is exactly the two entries in `files_modified`.

## Issues Encountered

**Three of the research's premises were wrong, and two of them mattered.**

1. **The run-rate was understated by ~£6/month.** Research: ≈£3.17/day → ≈£95/mo. Measured with daily granularity: £3.3190/day steady state over 8 full days → **£101.02/mo**. The corrected arithmetic is £101.02 + £147.00 = **£248.02** against a £150 ceiling (1.65×), not the £242 the plan's objective quoted. The node line was spot-checked independently against a live retail-price pull and matched exactly (£0.0358/hr × 3 × 730 = £78.40).

2. **Assumption A4's premise was false — and this is the one that could have destroyed something.** The research inferred from app names that the estate was "a prior Container Apps deployment of THIS platform". The image references say otherwise: `ghcr.io/bralabee/snackpass-*`, a separate codebase with a `python-vision` service J'Toye has never had. J'Toye's images are `jtoye-*`. `delete-snackpass` would have destroyed **a different project's deployment**, not a redundant copy of this one. The option table's framing was built on that inference.

3. **Assumption A3 was resolvable without the portal.** The plan assigned "is the £0.00 a free-tier offer?" to the operator as a dashboard task. The Cost Management meters answer it directly — they are named `B1MS Compute - Free` and `Storage Data Stored - Free`. Consequences recorded: the allowance is B1ms-shaped and cannot be redirected to the B2s staging server (Blocker C's connection math rules B1ms out by ~3×), and the window closes ~2027-07-21.

**Azure Cost Management throttles aggressively.** Four of six query attempts returned HTTP 429. Bounded retry wrappers with a deadline are kept in the scratchpad; any later plan re-running these queries should expect the same.

## User Setup Required

**Two credentials remain genuinely operator-only and are recorded ABSENT with what they block.** They do not block this plan — all four of 29-01's success criteria are met — but they block later waves and should be collected before those run:

| Item | Blocks | State |
|---|---|---|
| AWS credentials (eu-west-2) | **29-13 / #294** — and D-11 requires the bucket verification *before first deploy*, so leaving this open is a scope decision, not a scheduling one | ABSENT — `aws sts get-caller-identity` → "Unable to locate credentials" |
| #294's four bucket facts | 29-13 | UNMEASURED, blocked by the above. Includes the **quarantine-prefix-not-public** check, which is a security assertion (a public quarantine prefix is a stored-XSS primitive on the storefront's own origin) |
| D-12 backup bucket | 29-13's restore drill | UNKNOWN — neither confirmed present nor absent |
| Gmail SMTP app password + From/To | 29-07, 29-12 (D-17) | ABSENT, operator-only |
| Netlify DNS portal access | 29-10, and therefore HTTP-01 issuance | UNCONFIRMED — the zone was measured live; portal access is not machine-checkable |

Resolved *without* the operator: GHCR visibility (PUBLIC), the DNS zone state, and the free-tier question.

## Next Phase Readiness

**Ready.** Every value the phase's downstream plans read is now defined and dated:

- **29-04** reads `PG_ACCESS_MODE` — defined (`public-with-firewall`).
- **29-05** reads the SKU/count/ceiling/disposition and refuses if any key is missing — all 12 decision keys are defined.
- **29-09** owns the `rabbitmq-k8s` horizon row; the pin it needs (`cluster-operator:2.22.3`, `rabbitmq:4.3.4-management-alpine`) is in ADR-0002, plus two new rows to land (O-2, O-5).
- **29-10** executes the disposition; the **before-state** replica table is recorded so its after-state has a baseline (O-4).
- **29-16** inherits the PG16 skew record.

**Concerns:**

- The accepted headroom is **£3.00/month**. Any unplanned line item breaches the ceiling.
- **O-1 is a real obligation, not a formality.** If the idle meters have not dropped ~48h after 29-10, `scale-to-zero` did not work and the disposition must be revisited.
- The three Azure resource providers are still `NotRegistered` (has a fallback — `az provider register`).
- The only kube context on this host is `sipbihs2aks`, the **employer's cluster**. Every `kubectl` in later plans must pass `--context` explicitly or run after `az aks get-credentials`.

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-08-10*
