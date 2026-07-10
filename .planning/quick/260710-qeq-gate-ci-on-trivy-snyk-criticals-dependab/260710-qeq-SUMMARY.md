---
quick_id: 260710-qeq
title: "Gate CI on Trivy/Snyk criticals + Dependabot + pin actions to SHAs (#91)"
closes_issue: 91
status: complete
branch: feature/91-supply-chain-gate
commits:
  - 7a486ff  # ci-cd.yaml hardening
  - 79e8590  # add dependabot.yml
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
- **Trivy filesystem step:** pinned `aquasecurity/trivy-action` → `ed142fd…` (# v0.36.0);
  appended `exit-code: '1'` + `ignore-unfixed: true`. Build now fails on fixable
  CRITICAL/HIGH. SARIF still uploads (the upload step carries `if: always()`).
- **Snyk step:** removed `continue-on-error: true`; removed the now-redundant step-level
  `env:`; added `if: ${{ env.SNYK_TOKEN != '' }}`; pinned `snyk/actions/gradle` → `9adf32b…`
  (# v1.0.0). Real gate when the token exists, clean *skip* (not failure) when absent.
- **`build-and-push` job:** pinned `docker/setup-buildx-action` → `8d2750c…` (# v3),
  `docker/login-action` → `c94ce9f…` (# v3), `docker/metadata-action` → `c299e40…` (# v5),
  `docker/build-push-action` → `ca052bb…` (# v5).
- **Trivy image-scan step:** pinned `aquasecurity/trivy-action` → `ed142fd…` (# v0.36.0);
  appended `exit-code: '1'` + `ignore-unfixed: true`.
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
$ grep -c '@master'          .github/workflows/ci-cd.yaml   → 0   (expected 0) ✅
$ grep -c 'exit-code'        .github/workflows/ci-cd.yaml   → 2   (expected 2) ✅
$ grep -c 'ignore-unfixed'   .github/workflows/ci-cd.yaml   → 2   (expected 2) ✅
$ grep -c 'continue-on-error' .github/workflows/ci-cd.yaml  → 0   (expected 0) ✅
$ grep -c 'package-ecosystem' .github/dependabot.yml         → 8   (5 distinct ecosystems) ✅
```

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
- **AC #1 ("a seeded CVE fails CI") is verified-by-construction only.** The `exit-code: '1'`
  + `ignore-unfixed: true` config is the mechanism that makes a fixable CRITICAL/HIGH fail
  the `security-scan` job, and it is confirmed present. Full end-to-end proof (seed a known
  vulnerable dependency, watch the job go red) requires an actual CI run on GitHub — not
  reproducible locally in this config-only change.
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
