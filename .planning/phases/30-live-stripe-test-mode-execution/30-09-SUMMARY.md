---
phase: 30-live-stripe-test-mode-execution
plan: 09
subsystem: staging-ops
tags: [staging, stripe, test-mode, d-02, orphan-intents, issue-1038, cleanup]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-07: staging-seed.sh hand tools; 30-08: the deploy and parity procedure and the kubectl shim; quick 261025-8p7: the issue #1038 cleanup fix"
provides:
  - "PR #1041 (issue #1038 fix + Task 0 orphan-intent stages) reviewed to zero admissible findings over 4 rounds, squash-merged as b728254e"
  - "Staging runs b728254e by digest (core-java, edge-go, frontend); ci-cd run 40176504077 green through Deploy to Staging and the Staging Edge Probe"
  - "D-02 executed by owner choice (all-listed): the 6 orphaned TEST intents are canceled at Stripe, read back canceled/livemode=false"
  - "No payable TEST intent on staging is left without an order; the one open intent left (C97DCE79) has an order (issue #1031 class)"
affects: [30-10, 30-32]

actuals:
  tasks: 4
  commits: 2        # on feat/30-09-d02-orphans: the Task 1 evidence commit and this SUMMARY commit; Task 0 shipped inside PR #1041

key-files:
  created:
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-09-d02-drafts.txt
    - .planning/phases/30-live-stripe-test-mode-execution/30-09-SUMMARY.md
  modified:
    - .planning/STATE.md
    - .planning/ROADMAP.md

requirements-completed: []
---

# Plan 30-09 Summary: the orphaned TEST intents are cancelled, and Stripe says so

**No payable Stripe TEST intent on staging is left without an order.** The issue #1038 fix shipped first: PR #1041 merged as `b728254e`, and staging runs those images by digest. Without it, the cleanup job could have created new orphans while this plan ran. The listing then found 0 abandoned DRAFTs and 6 orphaned intents. The owner chose to cancel all six. Stripe accepted every cancel, with none refused, and independent read-backs show `canceled`, `livemode=false`.

## Task 0: orphan-intent stages (shipped in PR #1041)

- `list-orphan-intents` and `cancel-orphan-intents --intents …`, scoped to the vendor's own tenant through `metadata.tenant_id`.
- Selftest went from 41 to 47 PASS. The break arms each turned their named arm red (`evidence/30-09-task0-selftest.txt`).
- Review series: round 1 found 1 admissible finding (the cross-tenant orphan), which was fixed. Rounds 2 to 4 found 0. The Review-Record is on `626bf830`.
- All 14 required checks passed, and the PR squash-merged as `b728254e`. The main ci-cd run `40176504077` was green through Deploy to Staging and the Staging Edge Probe.
- Parity: each pod's imageID equals the ghcr digest CI pushed for `b728254e`.

| Service | Digest |
|---|---|
| core-java | `d5ef2a60…` |
| frontend | `4e67b932…` |
| edge-go | `0f8f1ec8…` |

## Task 1: read-only listings (2026-10-25T09:36Z)

- `list-drafts`: count 0.
- `list-orphan-intents`: `VENDOR-ORDERS total=8`, 6 orphans, 7 open intents in all.
- Six of issue #1038's seven are orphans. The seventh, `…C97DCE79`, is **not**: its order still exists. The 2026-10-19 swarm confirmed that order while it was unpaid. The issue had it wrong.
- No orphans were found beyond the issue's list. A re-run gave the same result.

## Task 2: owner checkpoint

The owner chose `all-listed`, with no exclusions. The choice was written to the evidence file before Task 3 ran.

## Task 3: cancel and read back (2026-10-25T10:05Z)

| Check | Result |
|---|---|
| `CANCEL-ORPHANS` summary | requested=6, cancelled=6, refused=0 |
| `SEED-ORPHAN-CANCEL` lines | 6, each `intent_status=canceled livemode=false` |
| `stripe-get`, first intent (`pi_3UTJiN…`) | `canceled`, `livemode=false`, reason `abandoned` |
| `stripe-get`, last intent (`pi_3USJ3Z…`) | `canceled`, `livemode=false`, reason `abandoned` |
| Re-listing | 0 orphans, 0 drafts; still_listed=0 (control: 6 against the Task 1 listing) |
| `open` count | 1, which is C97DCE79's intent |

## Deviations

1. **Task 1's PII check, as written, cannot pass on a correct listing.** It counts every line containing `@`, and the script always prints its own sign-in line (`OK: signed in as <vendor test account>`), so the run read `pii_lines=1`. The check was replaced by a strictly stronger form, recorded beside the original rather than substituted silently. The stronger form drops exactly that one literal line; any other address still counts.
   - Planted emails on the orphan lines counted 6.
   - A sign-in line naming any other address counted 1.
2. **The leak scan's positive control comes from its selftest.** `--leak-scan` matches the actual Key Vault values, not patterns, so a made-up key cannot trip it. Its selftest is the fail direction: 13/13, and `leak-scan hit=1-named`. A first run outside the conda env came back VOID (rc 2) and was not counted as a pass.
3. **The evidence file's own prose tripped the PII regex.** It quoted the regex and the control string, which gave 3 hits. The prose was reworded, and the file now scans 0, with a planted copy scanning 1.

## Left on staging

- C97DCE79's order is CONFIRMED and unpaid, and its intent `pi_3USL6GDpS9ICNeRt3mZUuGVa` is still payable. This is issue #1031 (a server CANCEL leaves the intent payable) and outside D-02's scope.
- The orphan stages can see only intents on the platform account. A direct-charge intent on a connected account would not be listed.
