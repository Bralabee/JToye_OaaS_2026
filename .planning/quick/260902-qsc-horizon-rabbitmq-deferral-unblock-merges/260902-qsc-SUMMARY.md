---
phase: quick-260902-qsc
plan: 01
subsystem: ci / dependency-horizons gate
status: complete
tags: [operational-contracts, dependency-horizons, rabbitmq, exemption, time-bomb, unblock-merges]
requires: [network access to endoflife.date for the gate's H-2 fetches]
provides:
  - "Operational Contracts green again on every PR: dated exemption on the rabbitmq manifest row (expires 2026-11-30, tracked_by #724)"
  - "Tracking issue #724 for the RabbitMQ 4.3 -> 4.4 broker upgrade with today's measurements and the removal step for the exemption"
  - "17 drifted sites: line numbers refreshed (advisory H-5 NOTEs cleared)"
affects:
  - infra/dependency-horizons.yaml
  - docs/CHANGELOG.md
tech-stack:
  added: []
  patterns: ["exemption expiring ON the horizon rather than after it, so the treadmill re-arms on 2026-12-01 with no commit"]
key-files:
  created: []
  modified:
    - infra/dependency-horizons.yaml
    - docs/CHANGELOG.md
decisions: [D-1, D-2, D-3, D-4]
requirements: []
---

# Quick task 260902-qsc — dependency-horizon gate deferral for rabbitmq/4.3

## What happened

On 2026-09-02 the **Operational Contracts** CI job failed on PR #723 (HANDOFF.md, docs-only) and
would have failed on every PR after it, with no code change anywhere:

```
FAIL: H-3 rabbitmq: rabbitmq/4.3 approaching 2026-11-30 (vendor (eol_source override), 89 days)
```

`scripts/check-dependency-horizons.sh` rule H-3 fails any row inside `HORIZON_WARN_DAYS=90`, and
the vendor community-support horizon for RabbitMQ 4.3 (30 Nov 2026) crossed that window today. The
script's own header predicted it ("turns AMBER ~2026-09-01 ... Intended. Not an outage. Not a broken
gate."). Reproduced locally on `origin/main`: rc=1, same line.

## What was measured before choosing the remedy

| Fact | Value | Source (2026-09-02) |
|---|---|---|
| Pin | `rabbitmq:4.3.4-management-alpine` | `docker-compose.full-stack.yml` |
| Newest catalogue cycle | 4.3, `latest: 4.3.5`, `eol: false` | endoflife.date/api/rabbitmq.json |
| 4.3 community support ends | 30 Nov 2026 (commercial 30 Apr 2028, none held) | rabbitmq.com/release-information |
| 4.4 GA | none; Docker Hub `name=4.4` returns only `3.4.4` | hub.docker.com |
| Existing tracking issue | none (searched open issues for rabbitmq / horizon) | gh issue list |

No upgrade target exists, so the only honest remedy is the one the manifest already uses for
spring-boot (#706): a dated exemption naming a tracking issue.

## What was done

1. **Issue #724 filed**: "Upgrade RabbitMQ broker 4.3.x → 4.4 before community support ends
   2026-11-30 (horizon gate deferral)" — measurements above, the steps when 4.4 ships (read the
   version off the running broker first, confirm the in-place path, bump pin, update row, REMOVE the
   exemption because H-4 fails a STALE one), the k8s broker being out of repo (ADR-0002), and the
   4.3.4 → 4.3.5 interim option.
2. **`infra/dependency-horizons.yaml`**: the `rabbitmq` row gains an `exemption:` block
   (`expires: "2026-11-30"`, `tracked_by: "#724"`) and a dated paragraph in its `note`; `--refresh`
   absorbed after inspecting its diff (17 `sites:` line pairs, no `eol_date`).
3. **`docs/CHANGELOG.md`**: Unreleased entry citing PR #725; `check-changelog-cites-pr.sh` rc=0 on
   the real number, rc=1 on an absent number, rc=0 on a docs-titled skip.
4. PR #725 opened; PR #723's unblock path recorded below.

## Proof bracket (clean → arms → clean)

| Arm | State | Expected | Observed |
|---|---|---|---|
| Fail direction | `origin/main` | rc=1 | rc=1, `FAIL: H-3 rabbitmq ... 89 days` |
| Pass | branch | rc=0 | rc=0, `H-3 EXEMPT rabbitmq ... until 2026-11-30 [#724]`, violations=0, active-exemptions=6 |
| Break A | `expires` → 2026-09-01 (rabbitmq block only; count asserted 1) | rc=1 | rc=1, `H-3 rabbitmq: exemption EXPIRED on 2026-09-01 (1 days ago)` |
| Restore A | copy of pristine | sha equal | `2d90eaeaf1a2b61b` both |
| Break B | `tracked_by` → "" | rc=1 | rc=1, `H-4 rabbitmq: exemption has no tracked_by` |
| Restore B | copy of pristine | sha equal | `2d90eaeaf1a2b61b` both |
| Closing clean | branch | rc=0 | rc=0, violations=0 |

Restores verified by file hash (never `git diff --stat`). Note `expires: "2026-11-30"` occurs twice
in the manifest (alpine's DEFERRED-27 shares the date), which is why the arms were scoped to the
rabbitmq block with an asserted count of 1 rather than a file-wide substitution.

## Decisions

- **D-1 — the exemption expires ON the horizon (2026-11-30), not after it.** On 2026-12-01 the row is
  past EOL *and* the block is expired, so the gate reds again with no commit. A later date would let
  a deferral quietly outlive the support window it defers.
- **D-2 — no 4.3.4 → 4.3.5 patch bump in this PR.** It moves no date, it is a runtime change needing
  a rebuild + E2E, and this lands on the eve of a QA council remediation round. Recorded in #724.
- **D-3 — `--refresh` included only after reading its diff.** Memory `trap_endoflife_horizon_drift`
  warns against reflexing to it; here main had NOT absorbed the drift (PR was 0 behind) and the
  diff is line numbers only.
- **D-4 — executed inline, no planner/executor agents.** One YAML block, one changelog entry, one
  issue; the full context was already in hand and the CARL bracket was FRESH/LEAN. GSD artefacts
  (PLAN, SUMMARY, STATE row) are produced so planning stays in sync.

## Unblocking PR #723 (the reason for all this)

Branch protection has `strict: false`, so #723's red Operational Contracts status would stand even
after #725 merges. Path: merge #725 (squash) → merge `origin/main` into `docs/handoff-qa-council-20260902`
and push → CI re-runs on a tree that contains the fix → merge #723 (squash). Both PRs also need a
review record (`review-record` is a required status): `/code-review --comment`, or an explicit
`Review-Record:` comment where a review yields no inline finding.

## Deferred / out of scope

- RabbitMQ 4.4 upgrade itself → #724.
- The staging/production broker version (out of repo, manifest row `rabbitmq-k8s`, `manual_review`
  expires 2026-10-26) — untouched; that review is the next scheduled amber on this gate.
- Untracked `.planning/quick/260831-jz4-.../evidence/` left as found (belongs to #713's session, not
  this task; contains raw login HTML with 0600 perms — not committed deliberately).
