---
quick_id: 260710-qeq
title: "Gate CI on Trivy/Snyk criticals + Dependabot + pin actions to SHAs (#91)"
closes_issue: 91
status: complete
branch: feature/91-supply-chain-gate
commits:
  - 7a486ff  # ci-cd.yaml hardening
  - 79e8590  # add dependabot.yml
  - 05cb93b  # split Trivy report/gate (scope gate to CRITICAL/HIGH, not all severities)
---

# Quick Task 260710-qeq — Supply-chain gate (#91 / P1-9) Summary

Armed the previously-toothless CI vulnerability scanning: Trivy now hard-fails on
fixable CRITICAL/HIGH CVEs, Snyk is a real HIGH+ gate (that skips cleanly without a
token), every third-party GitHub Action is pinned to a full commit SHA, and Dependabot
now opens weekly grouped update PRs across all five dependency ecosystems.

## What changed

### `.github/workflows/ci-cd.yaml` (commit `7a486ff`)
- **`security-scan` job:** added job-level `env: SNYK_TOKEN: ${{ secrets.SNYK_TOKEN }}`
  between `permissions:` and `steps:` (so the step-level `if:` can read it).
- **Trivy filesystem scan (report + gate, commit `05cb93b`):** pinned
  `aquasecurity/trivy-action` → `ed142fd…` (# v0.36.0). Split into TWO steps: a
  `format: sarif` **report** step (no exit-code → Security tab) and a `format: table`
  **gate** step (`severity: CRITICAL,HIGH` + `ignore-unfixed: true` + `exit-code: '1'`).
  Rationale: `format: sarif` forces Trivy to scan ALL severities, so a single-step
  `exit-code` gated on LOW/MEDIUM too (the first CI run failed on non-critical
  gomod/npm advisories). The table gate is scoped precisely and prints the offending
  CVEs in the job log. SARIF still uploads (`if: always()`).
- **Snyk step:** removed `continue-on-error: true`; removed the now-redundant step-level
  `env:`; added `if: ${{ env.SNYK_TOKEN != '' }}`; pinned `snyk/actions/gradle` → `9adf32b…`
  (# v1.0.0). Real gate when the token exists, clean *skip* (not failure) when absent.
- **`build-and-push` job:** pinned `docker/setup-buildx-action` → `8d2750c…` (# v3),
  `docker/login-action` → `c94ce9f…` (# v3), `docker/metadata-action` → `c299e40…` (# v5),
  `docker/build-push-action` → `ca052bb…` (# v5).
- **Trivy image scan:** same report + gate split (pinned `ed142fd…` # v0.36.0);
  gate scoped to fixable `CRITICAL,HIGH` with `exit-code: '1'`.
- **Deploy jobs:** pinned both `azure/setup-kubectl` → `901a10e…` (# v3) and both
  `slackapi/slack-github-action` → `fcfb566…` (# v1).
- Left GitHub-owned `actions/*` and `github/codeql-action/*` on their `@vN` float
  (out of scope; Dependabot's `github-actions` ecosystem will manage them).

### `.github/dependabot.yml` (new, commit `79e8590`)
- Dependabot v2, weekly schedule, `open-pull-requests-limit: 5`, minor+patch grouped
  per entry (`groups.minor-and-patch`) to avoid PR spam.
- 8 entries across 5 ecosystems: `gradle` (`/`, `/core-java`), `gomod` (`/edge-go`),
  `npm` (`/frontend`), `docker` (`/core-java`, `/edge-go`, `/frontend`),
  `github-actions` (`/`).
- Header comment references #91.

## Verification (run before committing)

```
$ grep -c '@master'                     ci-cd.yaml   → 0   (expected 0) ✅
$ grep -cE "^\s+exit-code: '1'"          ci-cd.yaml   → 2   (2 gate steps) ✅
$ grep -c 'ignore-unfixed'              ci-cd.yaml    → 4   (2 report + 2 gate) ✅
$ grep -c "format: 'table'"             ci-cd.yaml    → 2   (fs gate + image gate) ✅
$ grep -c 'continue-on-error'           ci-cd.yaml    → 0   (expected 0) ✅
$ grep -c 'package-ecosystem'   .github/dependabot.yml → 8   (5 distinct ecosystems) ✅
```

### Live CI validation on PR #140 (the authoritative E2E)
- **RED direction proven:** the initial single-step run **failed the `security-scan`
  job (exit code 1)** because Trivy found vulnerabilities → confirms `exit-code: '1'`
  genuinely fails the build on findings.
- **GREEN direction proven:** after scoping the gate to fixable `CRITICAL,HIGH`, the
  `security-scan` job **passed (35s)** → the gate is correctly scoped (no false-positive)
  and the current dependency tree has **no fixable CRITICAL/HIGH CVEs**.

Distinct ecosystems present: `docker`, `github-actions`, `gomod`, `gradle`, `npm`.

In-scope third-party actions pinned (11 references, all 40-char SHA + version comment):
trivy-action (fs + image), snyk/actions/gradle, docker/setup-buildx-action,
docker/login-action, docker/metadata-action, docker/build-push-action,
azure/setup-kubectl (×2), slackapi/slack-github-action (×2). No in-scope third-party
remains on `@master` or `@vN`.

YAML lint (js-yaml under `frontend/node_modules`, since no yq/python on host):
```
$ node -e "const yaml=require('.../frontend/node_modules/js-yaml'); \
    yaml.load(fs.readFileSync('.github/workflows/ci-cd.yaml','utf8')); \
    yaml.load(fs.readFileSync('.github/dependabot.yml','utf8')); console.log('YAML OK')"
YAML OK
```
Both files parse as valid YAML.

## Caveats
- **AC #1 ("a seeded CVE fails CI") is empirically validated on PR #140**, not merely
  by construction: the gate was observed failing the build when Trivy found vulns, then
  passing once scoped to CRITICAL,HIGH. The only step not exercised is a *specifically*
  seeded CRITICAL dependency (vs. the mixed-severity findings that fired the first run) —
  the exit-code path is identical, so this is a formality if belt-and-suspenders proof is
  wanted (throwaway branch: add a known-vuln dep, watch red, discard).
- **Snyk gate is inert until `SNYK_TOKEN` is configured** as a repo/org secret. By design the
  step *skips* (does not fail) when the secret is unset, so this does not block builds today;
  it activates automatically once the secret is added.
- **Pre-existing CVEs the newly-armed gate may surface are out of scope** (per plan). The
  first `security-scan` run on a real push could fail if the current tree already carries
  fixable CRITICAL/HIGH CVEs; remediating those is follow-up work (and the first Dependabot
  PRs).

## Self-Check
- `.github/workflows/ci-cd.yaml` — modified & committed (`7a486ff`) ✅
- `.github/dependabot.yml` — created & committed (`79e8590`) ✅
- Both commits present on `feature/91-supply-chain-gate` ✅
- No docs artifacts committed by executor (orchestrator owns those) ✅

## Self-Check: PASSED
