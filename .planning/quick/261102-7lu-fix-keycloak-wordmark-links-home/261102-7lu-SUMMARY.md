---
phase: quick
plan: 261102-7lu
subsystem: keycloak / login theme / realm render
tags: [keycloak, realm, theme, wordmark, ux, owner-ruling-2026-11-02]
status: complete
pr: 1095
requires: []
provides:
  - "displayNameHtml anchor to ${PUBLIC_APP_ORIGIN} in both realm templates"
  - "PUBLIC_APP_ORIGIN render variable (compose x3, .env.example, verify-env.sh)"
  - "staging transform + assertion for the header link"
  - "scripts/check-keycloak-wordmark-home.sh (+ --selftest), wired in ci-cd.yaml"
  - "docs/runbooks/keycloak-wordmark-home.md"
affects: [staging-realm-import, local compose realms, production realm (operator)]
tech-stack:
  added: []
  patterns: ["per-environment value inside a realm string via envsubst allow-list + staging jq rewrite"]
key-files:
  created:
    - scripts/check-keycloak-wordmark-home.sh
    - docs/runbooks/keycloak-wordmark-home.md
    - .planning/quick/261102-7lu-fix-keycloak-wordmark-links-home/evidence/
  modified:
    - infra/keycloak/realm-export.template.json
    - infra/keycloak/realm-export-customers.template.json
    - infra/keycloak/staging-realm.jq
    - infra/keycloak/themes/jtoye/login/resources/css/jtoye.css
    - infra/keycloak/README.md
    - docker-compose.full-stack.yml
    - infra/docker-compose.yml
    - infra/docker-compose.hostnet.yml
    - .env.example
    - scripts/verify-env.sh
    - scripts/staging-realm-import.sh
    - .github/workflows/ci-cd.yaml
    - docs/CHANGELOG.md
decisions:
  - "Owner ruling 2026-11-02: the wordmark is an anchor to the environment's public app origin, in both realms; the theme stays CSS-only (no FTL override needed: kcSanitize keeps the anchor)"
  - "New variable PUBLIC_APP_ORIGIN, defaulted in compose (an existing .env must keep booting), validated when set"
  - "Rollout is a field-scoped kcadm update, never import --override / --recreate (those delete realm users)"
metrics:
  completed: 2026-11-02
commits: 2
plan_head_before: 9b737e61
---

# Quick 261102-7lu: the Keycloak wordmark links home

Every Keycloak page now renders `<a href="<public app origin>" … rel="nofollow">J'Toye</a>` in
`#kc-header-wrapper`. That includes the "Email verification" page where a tester got stuck. The
`jtoye` theme stays CSS-only.

## displayNameHtml (both templates)

```
<a href=\"${PUBLIC_APP_ORIGIN}\" style=\"color:#3A0B0D;font-weight:700;letter-spacing:0.01em;text-decoration:none\">J'Toye</a>
```

## Commits

| # | Subject |
|---|---------|
| 1 | `664a56e8` fix(keycloak): link the J'Toye wordmark home on every Keycloak page |
| 2 | docs(changelog): the Keycloak wordmark links home (#1095); quick 261102-7lu summary |

No AI-attribution lines in either message or in the PR body (owner ruling 2026-08-30).

## Evidence (`evidence/`)

| File | Fail direction | Pass direction |
|------|----------------|----------------|
| `sanitiser-red-unchanged-realm.txt` / `sanitiser-green-anchor-realm.txt` | unchanged realm on throwaway KC 26.8.1: login, register, **Email verification** headers are `<span …>J&#39;Toye</span>`, `NO ANCHOR`, rc 1 | branch realm: same three pages `<a href="http://localhost:3000" style="…" rel="nofollow">`, `ANCHOR PRESENT`, rc 0 |
| `sanitiser-green-reset-page-and-css.txt` | — | "Forgot Your Password?" page has the anchor; the served `jtoye.css` carries the `#kc-header-wrapper a` rules |
| `sanitiser-green-staging-origin-via-kcadm.txt` | — | `https://app-staging.olajay.co.uk` set by `kcadm.sh update` survives the sanitiser on all three pages |
| `rollout-kcadm-on-existing-realm.txt` | the realm imported with the OLD header (RED above) | the runbook script applied to it: all three pages show the anchor; the realm's pre-existing users are still listed |
| `gate-red-origin-main.txt` / `gate-green-branch.txt` | origin/main's files: 8 defects, rc 1 | branch: rc 0; `--selftest` 8/8 PASS (clean, span, literal host, second href, envsubst omits, no env entry, 2 VOIDs) |
| `staging-realm-selftest.txt` | a planted `<span>` staging render: `DEFECT: displayNameHtml does not link home`, rc 1; the raw-customer arm names the same defect | `--selftest` 9/9 PASS, rc 0; the offline renders of both kinds hold `href="https://app-staging.olajay.co.uk"` |
| `verify-env-public-app-origin-arms.txt` | trailing slash, a quote break-out, an inline `# comment`: FAIL | `http://localhost:3000` and the staging https origin: PASS; unset: INFO |

Other gates run on the branch: `scripts/check-gate-enforcement.sh` rc 0, `scripts/docs-freshness.sh`
rc 0, `scripts/check-doc-metrics.sh` rc 0 (no tests added, `docs/metrics.json` unchanged),
`scripts/check-env-example-contract.sh` rc 0, `k8s/scripts/check-env-contract.sh` rc 0,
`scripts/check-changelog-contract.sh` rc 0, `scripts/check-branch-behind-base.sh` PASS (1 ahead,
0 behind). `docker compose config` resolves `PUBLIC_APP_ORIGIN: http://localhost:3000` in all three
compose files, and envsubst with the new allow-lists renders the anchor in both realm files.

The throwaway containers (`jtoye-kc-wordmark-red`, `-green`, `-mailhog`) and their network were
removed. The shared compose stack's `jtoye-keycloak` was never touched.

## Not done / unverified

- **No deploy.** Staging and the local compose realms keep the old header until the runbook's
  `kcadm.sh update` is run (owner or cluster session for staging). Until then, the next
  `staging-realm-import.sh --apply` live read-back names `displayNameHtml does not link home`.
- The script has not been run inside the staging pod. Whether `/tmp` is writable there, and whether the
  bootstrap admin still signs in, were not checked.
- Hover and focus styling were checked as served CSS rules, not in a browser.
- The rendered `infra/keycloak/realm-export*.json` are gitignored build products. The compose
  render sidecar regenerates them, so there is nothing to commit.
