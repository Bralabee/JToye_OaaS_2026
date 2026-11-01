# HANDOFF — landing elevate run: ship it (PR, review, merge), then polish

Written 2026-11-01 by the machine session that ran the elevate run. Resume in a FRESH session opened
in this worktree. Load `design-run` first.

## State

- Worktree `/home/sanmi/IdeaProjects/JToye_OaaS_2026-landing-elevate`, branch
  `design/landing-elevate-20261101`, from `origin/main` at `1fb15a8b` plus the design record.
- Steps done: 0 (brief records `Elevate or redesign: elevate (Sanmi, 2026-11-01)`;
  `rule-check.sh brief` rc 0), sketch approved, D14 (palette adopted, 3-line headline kept,
  Track-live copy, card peek), build, gate, separate score, **blind A/B WON** (`verdict.md`).
- Build record: `README.md` in this folder (gate before→after, tests, LCP/CLS, unsettled).
- Gate after: lint 0 FAIL; inspect 6 FAIL = 3 headline (owner ruling D14) + 3 shared chrome
  (separate PR, owner ruling). Jest landing/marketing green; Playwright landing specs green except
  11 storefront SEO tests that need `/shop/{slug}` (fail identically on the before build: need the
  Compose stack).
- The superseded redesign branch `design/landing-run-20261101` (worktree `-design-run`) stays
  unmerged; its `design/` record was carried here. Do not merge it; ask Sanmi before deleting.

## Next, in order

1. **Doc-metrics gate is red:** README/CLAUDE.md/AGENTS.md quote old test counts. Run
   `scripts/docs-freshness.sh --write` and `scripts/check-doc-metrics.sh` (conda env `jtoye-ops`),
   fix the prose counts, commit.
2. `git fetch && git log HEAD..origin/main` must be empty (merge `origin/main` if not, never rebase
   a pushed branch).
3. Open the PR as a DRAFT to get its number, add the `docs/CHANGELOG.md` entry
   (`### <Title> (#PR) — 2026-11-0x` under `## [Unreleased]`), `gh pr ready`.
4. Review series from this worktree: `/code-review <PR> --comment`, resolution comment from
   `~/.claude/skills/review-verify/resolution-template.md`, `/review-verify <PR>` until zero
   admissible, Review-Record on the FULL head, `gh pr merge --squash` in a merge window
   (`gh run list --branch main --workflow ci-cd.yaml --limit 1` idle).
5. Then the polish, owner to prioritise (from `verdict.md`): R14 phone first screen needs a real
   food picture; photo repeats; tablet operator door; small grey text; food below the kitchens;
   desktop LCP outlier (GSAP headline split after font swap — a recorded-decision change);
   3x phone image sizes. Shared-chrome PR (header/footer hairlines, footer caps labels, cookie
   notice covering the phone's Order-food door) is its own PR.

## Never

The Compose stack or its database (exclude `near-you-row.spec.ts`'s `docker exec` block); the
main checkout; `.planning/STATE.md`; repo-root `HANDOFF.md`. No attribution lines anywhere.
