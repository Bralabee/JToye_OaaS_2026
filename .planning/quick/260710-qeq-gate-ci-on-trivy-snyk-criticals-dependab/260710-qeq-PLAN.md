---
quick_id: 260710-qeq
title: "Gate CI on Trivy/Snyk criticals + Dependabot + pin actions to SHAs (#91)"
closes_issue: 91
status: ready
must_haves:
  truths:
    - "A CRITICAL/HIGH fixable dependency causes the security-scan job to exit non-zero (build fails)."
    - "Snyk gates on HIGH+ when SNYK_TOKEN exists; skips cleanly (does not hard-fail) when the secret is absent."
    - "Dependabot opens update PRs for gradle, gomod, npm, docker, and github-actions."
    - "Every third-party GitHub Action is pinned to a full 40-char commit SHA with a version comment."
  artifacts:
    - ".github/workflows/ci-cd.yaml (Trivy exit-code gate, Snyk gate, SHA pins)"
    - ".github/dependabot.yml (new)"
  key_links:
    - ".github/workflows/ci-cd.yaml"
    - ".github/dependabot.yml"
---

# Quick Task 260710-qeq — Supply-chain gate (#91 / P1-9)

## Problem
Vulnerability scanning is toothless: Trivy steps have no `exit-code`, Snyk is
`continue-on-error: true`, there is no Dependabot/Renovate, and third-party
actions float on `@master`/`@vN`. Known CRITICAL/HIGH CVEs merge unimpeded.

## Decisions (locked — do not revisit)
- **Trivy gates via `exit-code: '1'`** on the existing single scan step (both the
  fs scan and the image scan). SARIF is written before the non-zero exit and both
  "Upload …-sarif" steps already carry `if: always()`, so the GitHub Security tab
  still populates. No second Trivy invocation needed.
- **`ignore-unfixed: true`** on both Trivy steps — gate only on CVEs that have a
  fix (unfixable upstream OS CVEs can't be actioned and would wedge every build).
  A seeded vulnerable *dependency* has a fix, so AC #1 still triggers.
- **Snyk**: drop `continue-on-error: true`; add a job-level `env: SNYK_TOKEN` and
  a step `if: ${{ env.SNYK_TOKEN != '' }}` guard. Real gate when the token exists,
  clean skip (not failure) when it doesn't. Job-level env is in scope for step `if`.
- **SHA-pin third-party actions only** (the AC's scope). Leave GitHub-owned
  `actions/*` and `github/codeql-action/*` on their `@vN` float — Dependabot's
  `github-actions` ecosystem will manage/pin them going forward without adding
  churn now.
- **Dependabot, not Renovate** — zero extra infra, native to GitHub.

## Resolved SHA pins (verified 2026-07-10 via `gh api repos/<a>/commits/<ref>`)
| Action | From | Pin to |
|--------|------|--------|
| `aquasecurity/trivy-action` (fs + image) | `@master` | `ed142fd0673e97e23eac54620cfb913e5ce36c25` # v0.36.0 |
| `snyk/actions/gradle` | `@master` | `9adf32b1121593767fc3c057af55b55db032dc04` # v1.0.0 |
| `docker/setup-buildx-action` | `@v3` | `8d2750c68a42422c14e847fe6c8ac0403b4cbd6f` # v3 |
| `docker/login-action` | `@v3` | `c94ce9fb468520275223c153574b00df6fe4bcc9` # v3 |
| `docker/metadata-action` | `@v5` | `c299e40c65443455700f0fdfc63efafe5b349051` # v5 |
| `docker/build-push-action` | `@v5` | `ca052bb54ab0790a636c9b5f226502c73d547a25` # v5 |
| `azure/setup-kubectl` (×2) | `@v3` | `901a10e89ea615cf61f57ac05cecdf23e7de06d8` # v3 |
| `slackapi/slack-github-action` (×2) | `@v1` | `fcfb566f8b0aab22203f066d80ca1d7e4b5d05b3` # v1 |

## Task 1 — Harden `.github/workflows/ci-cd.yaml`
**files:** `.github/workflows/ci-cd.yaml`
**action:**
1. `security-scan` job: add `env:` block (`SNYK_TOKEN: ${{ secrets.SNYK_TOKEN }}`)
   between `permissions:` and `steps:`.
2. Trivy fs step: pin `aquasecurity/trivy-action` to the SHA above; append
   `exit-code: '1'` and `ignore-unfixed: true` under `with:`.
3. Snyk step: pin `snyk/actions/gradle` to the SHA; remove `continue-on-error: true`;
   remove the step-level `env:` (now job-level); add `if: ${{ env.SNYK_TOKEN != '' }}`.
4. `build-and-push` job: pin `docker/setup-buildx-action`, `docker/login-action`,
   `docker/metadata-action`, `docker/build-push-action` to their SHAs.
5. Trivy image step: pin to the SHA; append `exit-code: '1'` and `ignore-unfixed: true`.
6. Deploy jobs: pin both `azure/setup-kubectl` and both `slackapi/slack-github-action`
   occurrences to their SHAs.
Keep every `# vX` trailing comment so the human-readable version survives.
**verify:** `node -e "require('js-yaml')"`-free YAML lint (workflow provides a
node one-liner); `grep -c '@master' ci-cd.yaml` == 0; `grep exit-code` shows 2 hits.
**done:** No `@master` remains; both Trivy steps gate; Snyk guarded; docker/azure/slack pinned.

## Task 2 — Add `.github/dependabot.yml`
**files:** `.github/dependabot.yml` (new)
**action:** version-2 config, weekly schedule, grouped minor/patch to avoid PR spam,
`open-pull-requests-limit`, ecosystems:
- `gradle` at `/` and `/core-java`
- `gomod` at `/edge-go`
- `npm` at `/frontend`
- `docker` at `/core-java`, `/edge-go`, `/frontend`
- `github-actions` at `/`
**verify:** YAML lint passes; `package-ecosystem` present for all 5 ecosystems.
**done:** File exists and is valid; covers every dependency surface in the repo.

## Out of scope
- Fixing any pre-existing CVEs the newly-armed gate surfaces (follow-up / first
  Dependabot PRs).
- Pinning GitHub-owned `actions/*` (Dependabot will manage).
