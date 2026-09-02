---
phase: quick-260902-qsc
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - infra/dependency-horizons.yaml
  - docs/CHANGELOG.md
autonomous: true
requirements: []
user_setup: []

must_haves:
  truths:
    - "bash scripts/check-dependency-horizons.sh exits 0 on the branch, and exited 1 on origin/main the same day (fail direction observed, not assumed)"
    - "The rabbitmq row's exemption names a real GitHub issue in tracked_by and expires no later than the vendor horizon 2026-11-30 — the deferral cannot outlive the community-support window"
    - "Breaking the exemption (expired date; missing tracked_by) makes the gate exit 1 naming the row; restoring it makes the gate exit 0 again (bracketed break arms)"
    - "Any --refresh diff included is site line numbers only — no eol_date rewrite rides in unexamined"
    - "docs/CHANGELOG.md has an entry heading citing this PR's number (check-changelog-cites-pr.sh rc=0)"
    - "PR #723 and the fix PR each carry a review record the review-record gate accepts, all required checks green, both merged to main via squash"
  artifacts:
    - path: "infra/dependency-horizons.yaml"
      provides: "dated exemption on the rabbitmq row"
      contains: "tracked_by"
    - path: "docs/CHANGELOG.md"
      provides: "Unreleased entry for the horizon deferral"
      contains: "dependency-horizons"
  key_links:
    - from: "infra/dependency-horizons.yaml rabbitmq.exemption.tracked_by"
      to: "GitHub issue for the 4.3 -> 4.4 broker upgrade"
      via: "issue number"
      pattern: "tracked_by: \"#[0-9]+\""
---

<objective>
Unblock merging before the next QA phase. The Operational Contracts CI job went red on every PR on
2026-09-02 with no code change: `scripts/check-dependency-horizons.sh` rule H-3 fails the `rabbitmq`
row because rabbitmq/4.3's vendor community-support horizon (2026-11-30) crossed the 90-day warn
window (89 days). RabbitMQ 4.3 is the newest cycle (endoflife.date: 4.3.5 latest, no 4.4; Docker Hub:
no 4.4 tag), so there is no upgrade target and the sanctioned remedy is a DATED, TRACKED exemption —
the same shape the spring-boot row uses (#706). The gate's own header predicted this exact moment.

Purpose: PR #723 (HANDOFF.md, docs-only) and every PR after it are blocked by a required check that
cannot pass on any tree without this change.

Output: exemption block on the rabbitmq manifest row naming a new tracking issue; site line numbers
refreshed if that is all --refresh changes; CHANGELOG entry citing the PR; both open PRs merged.
</objective>

<tasks>

## Task 1 — File the tracking issue (done first: tracked_by needs the number)

Title: "Upgrade RabbitMQ broker 4.3.x → 4.4 before community support ends 2026-11-30 (horizon gate deferral)".
Body records what was MEASURED today (gate output, vendor dates, endoflife.date cycles, Docker Hub
tag absence, the 4.3.4 vs 4.3.5 patch gap), the steps when 4.4 ships (read version off the running
broker per the runbook, confirm in-place path, bump pin, update row, REMOVE exemption because H-4
fails a STALE one), and that the k8s broker is out of repo (ADR-0002).
Labels: dependencies, tech-debt, docker, P2.

## Task 2 — Manifest exemption + gate proof

files: infra/dependency-horizons.yaml
action:
  1. Baseline: run the gate on the branch BEFORE editing — expect rc=1 naming `H-3 rabbitmq`.
  2. Add to the `rabbitmq` row an `exemption:` block: multi-line `reason` (no upgrade target exists;
     4.4 not GA; vendor dates; why the exemption ends ON the horizon), `expires: "2026-11-30"`,
     `tracked_by: "#<issue>"`. Append a dated paragraph to the row's `note` recording that the
     predicted amber arrived on 2026-09-02.
  3. Evaluate `--refresh`: include the rewrite ONLY if the diff touches `sites:` line numbers; if any
     `eol_date` changes, examine it separately and revert unless it matches a live fetch.
  4. Prove: gate rc=0 with the exemption. Break arm A: set expires to 2026-09-01 → expect rc=1
     "exemption EXPIRED". Break arm B: blank tracked_by → expect rc=1 "H-4 ... no tracked_by".
     Restore by content (grep the real values back), re-run → rc=0. Record all four outputs.
verify: bash scripts/check-dependency-horizons.sh; echo rc=$?  → 0, and the H-3 line reads
        "H-3 EXEMPT rabbitmq: ... until 2026-11-30 [#<issue>]"
done: commit `fix(horizons): ...` on branch fix/horizon-rabbitmq-43-deferral

## Task 3 — Changelog, PR, review records, merges

files: docs/CHANGELOG.md
action:
  1. Push the branch, open the PR (title `fix(horizons): ...`), capture its number.
  2. Add an `## [Unreleased]` entry heading citing `(#<PR>)` in docs/CHANGELOG.md; run
     `scripts/check-changelog-cites-pr.sh --pr <PR> --title '<title>'` → 0, and the absent-number
     control → 1. Commit + push.
  3. Review records: run /code-review --comment on #723 and on the fix PR; where a review yields no
     inline comment, post an explicit `Review-Record:` issue comment describing what was reviewed.
  4. Merge the fix PR first (squash). Then merge origin/main INTO the #723 branch and push so its
     Operational Contracts check re-runs on a tree that contains the fix (strict=false means the old
     red status would otherwise stand). Merge #723 (squash) when green.
verify: `gh pr view <n> --json state` = MERGED for both; `git log --oneline -3 origin/main` shows both.
done: STATE.md quick-task row + SUMMARY.md committed with docs(quick-260902-qsc) on the fix branch
      BEFORE the PR merges (so the artefacts land with the change).

</tasks>

<verification>
- Gate fail-direction on origin/main observed 2026-09-02 (rc=1) — recorded in SUMMARY.
- Gate pass on branch (rc=0) + two break arms (rc=1 each) + closing clean run (rc=0).
- check-changelog-cites-pr.sh both directions.
- Both PRs MERGED; main's Operational Contracts job green on the post-merge push.
</verification>
