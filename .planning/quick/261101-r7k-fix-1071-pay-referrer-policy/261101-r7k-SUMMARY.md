---
phase: quick
plan: 261101-r7k
subsystem: k8s-ingress, frontend-pay, staging-edge-probe, docs
tags: [security-headers, referrer-policy, ingress, traefik, pay, edge-probe, issue-1071]
status: complete
requires: []
provides:
  - app-host Ingresses (jtoye-ingress, jtoye-sse-ingress) no longer set Referrer-Policy; the apps own it
  - /pay document carries <meta name="referrer" content="no-referrer">
  - check-staging-edge.sh pay-referrer arm (+ fail arm) and --only <arm>
affects:
  - staging-edge-probe CI job (runs the new arm after every staging deploy)
  - /invite (same no-referrer rule, also clobbered by the edge line, fixed by the same change)
tech-stack:
  added: []
  patterns:
    - "header owned by the app, not the edge, when any route must override it (more_set_headers replaces)"
    - "document-level meta referrer as a second layer a header-rewriting proxy cannot reach"
key-files:
  created:
    - frontend/app/pay/__tests__/page-metadata.test.ts
    - .planning/quick/261101-r7k-fix-1071-pay-referrer-policy/evidence/pay-referrer-red-green.txt
    - .planning/quick/261101-r7k-fix-1071-pay-referrer-policy/evidence/rp-count.sh
  modified:
    - k8s/base/ingress.yaml
    - k8s/staging/ingress-hosts-patch.yaml
    - k8s/staging/sse-ingress-hosts-patch.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - k8s/platform/traefik/README.md
    - k8s/LOCAL.md
    - k8s/PRODUCTION_READINESS_REPORT.md
    - k8s/components/keycloak/keycloak.yaml (comment only)
    - k8s/scripts/check-render-invariants.sh (message text only)
    - frontend/app/pay/page.tsx
    - scripts/check-staging-edge.sh
    - .github/workflows/ci-cd.yaml (comments and one step name)
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - docs/CHANGELOG.md
decisions:
  - "Owner ruling 2026-11-01: issue #1071 option 2 (apps own Referrer-Policy) plus a meta tag on /pay"
  - "Component Ingresses (grafana, keycloak, mailpit) keep their Referrer-Policy line: no app of ours sends one on those hosts"
  - "pay-referrer requires exactly ONE Referrer-Policy header with the exact value no-referrer (a proxy that adds instead of replaces would leave two)"
  - "--only is refused with --fail-arms and --skip-burst; the PASS line says every other arm was not run"
metrics:
  duration: "~40 min"
  completed: 2026-11-01
actuals:
  tokens: 14000
  tasks: 4
  commits: 5
plan_head_before: 6c1fdc93c533267a4289281758417407f0ac879a
plan_head_after: c51f8f5fbdb682bffef82f65c0f4c83e68e74ad3
pr: 1073
---

# Quick 261101-r7k: The Apps Own Referrer-Policy, So /pay's no-referrer Reaches the Browser Summary

The staging ingress snippet no longer sets `Referrer-Policy` on the app and API hosts. Its `more_set_headers` had been replacing the frontend's `no-referrer` on `/pay` and `/invite`. `/pay` now also carries a `no-referrer` meta tag. The live edge probe gains a `pay-referrer` arm. On today's staging that arm FAILS, which is correct. It goes green when this PR deploys.

## Commits

`commits: 5` counts the commits up to `plan_head_after`. The CHANGELOG and SUMMARY commit follows them.

| Task | Commit | Subject |
|------|--------|---------|
| 1 | e9afe35e | fix(k8s): the app-host Ingresses stop setting Referrer-Policy; the apps own it (#1071) |
| 2 (red) | b3f76e4e | test(pay): the /pay document must carry a no-referrer meta tag (red) (#1071) |
| 2 (fix) | 45d45b9c | fix(pay): /pay renders a no-referrer meta tag no edge can overwrite (#1071) |
| 3 | f5507bd2 | feat(edge-probe): a pay-referrer arm reads /pay's Referrer-Policy through the live edge (#1071) |
| 4 (docs) | c51f8f5f | docs: test counts for the /pay metadata test, and a LOCAL.md line count kept (#1071) |
| 4 (changelog) | (follows) | docs: CHANGELOG (#1073) and the quick-task summary |

I read every message back with `git log --format=%B`. None has an attribution trailer or footer.

## Evidence (verbatim in `evidence/pay-referrer-red-green.txt`)

- **Section 0, the live edge before the change.** On 2026-11-01, app-staging `/pay`, `/invite` and `/`, and api-staging `/api/v1/public/shops`, all carry `strict-origin-when-cross-origin`. `/pay` has no referrer meta tag.
- **Section 1, the Ingress change.**
  - Referrer-Policy lines on the app-host Ingresses (`rp-count.sh`):

    | Overlay | Before | After |
    |---|---|---|
    | base | 1 | 0 |
    | staging | 2 | 0 |
    | production | 1 | 0 |
    | local | 0 | 0 |

  - Whole render after the change: base and production have 0. Staging has 3, all on component Ingresses.
  - Goldens:
    - The check gave rc 1 after the edit and rc 0 after `--write`.
    - `--diff-since pre-1071` shows exactly 3 removed lines and nothing else. The snapshot was taken before the edit.
  - Render invariants:
    - `check-render-invariants.sh`: rc 0 before the change and rc 0 after.
    - `--selftest-inv15`: rc 0, and all 8 planted arms were caught.
    - Break arm: with the line put back in base only, INV-15 FAILs on both staging app-host Ingresses (rc 1). Restoring it, verified by content, gives rc 0.
- **Section 2, the frontend.**
  - Jest RED (`Received: undefined`, rc 1), then GREEN: 3 suites, 43/43, rc 0. tsc rc 0. Lint rc 0, with 0 errors.
  - Real path:
    - `next build` gave rc 0.
    - `next start` serves `/pay` with `Referrer-Policy: no-referrer` and `<meta name="referrer" content="no-referrer"/>`.
    - Control: the home page has no tag.
- **Section 3, the edge probe.**
  - `--only pay-referrer` on today's staging FAILS, rc 1 (text and TAP).
  - `--skip-burst`: the four old arms PASS and pay-referrer FAILS, rc 1.
  - `--fail-arms --context jtoye-stg-aks`: 7/7, rc 0. pay-referrer FAILED on the probe as it must. The probe was then deleted and verified gone.
  - Local harness:
    - Passes (rc 0): the real `/pay` and a `no-referrer` fake.
    - Fails (rc 1): `/`, no header, two headers, `No-Referrer`.
    - Unreachable host: VOID, rc 2.
    - Control: the harness on origin/main's script finds no arm (rc 3).
  - Usage errors give rc 2.
  - shellcheck and actionlint show no new finding class.
- **Section 4, the docs gates.**
  - Every gate is rc 0 after the fixes, and each one was seen failing first:
    - docs-freshness rc 1 before `--write`.
    - doc-metrics rc 1 before the prose edits.
    - object-store-residue rc 1 when LOCAL.md gained a line.
    - changelog-cites-pr rc 1 without the entry.
  - check-branch-behind-base: rc 0, 0 behind.

## Deviations from Plan / Brief

1. **[Incremental Betterment] The staging render keeps 3 `Referrer-Policy` lines.** The brief said to render ZERO in `k8s/staging`. The 3 remaining lines are on `grafana-public`, `keycloak-public` and `mailpit-ui`, the component Ingresses for hosts no app of ours serves. Removing them would drop the header on those hosts. The app-host Ingresses render 0 in every overlay. base and production render 0 in total.
2. **[Rule 3 - Blocking] node_modules was copied, not symlinked.** Jest, tsc and lint worked through the symlink. `next build` (Turbopack) refused a node_modules outside the project root, so I replaced the symlink with `cp -a` of the main checkout's tree (gitignored). The lockfiles are byte-identical (`cmp` rc 0).
3. **[Rule 1 - Bug] k8s/LOCAL.md line count.** My first edit added a line, and 13 line-numbered object-store-residue allowlist entries went stale (rc 1). I rewrapped it to the original line count instead of renumbering the allowlist.
4. **[Extra] The `/invite` page is fixed by the same edge change.** It has the same `no-referrer` rule and was measured clobbered live. It gets no meta tag and no arm, because neither was asked for.
5. **[Extra] Comments that counted "six" snippet headers now say "five".** They are in `ci-cd.yaml`, `keycloak.yaml` and `check-render-invariants.sh` (message text). The `PRODUCTION_READINESS_REPORT.md` bullet is also updated. The rendered output is unchanged apart from the 3 golden lines.
6. **A collided probe run is recorded as invalid.** One `--skip-burst` run (08:33:02Z) overlapped my own `--fail-arms` run. Its scratch probe deliberately routes auth-staging `/admin` to agnhost, so that run's admin arm read FAIL. The clean runs before and after it show admin PASS.

## Observed, not filed (admission test: no gate broken, no data lost, no failure caused)

- **auth-staging also gets `Referrer-Policy: strict-origin-when-cross-origin` from the keycloak Ingress snippet.** Measured live on `/realms/jtoye-staging/account`. Keycloak's own realm default for this header is `no-referrer`, so the edge may be loosening it the same way. I have not proven this: I did not read Keycloak's header behind the edge. Out of scope for #1071.

## Cross-cutting quality contracts

- **Security:** T-r7k-01 is mitigated, by the edge removal plus the meta tag, proven live (RED) and locally (GREEN). T-r7k-02 is accepted: the browser default is the same value. T-r7k-03 is mitigated: the component Ingresses are untouched.
- **SEO:** `/pay` stays noindex,nofollow, and the test asserts it.
- **Web performance:** one meta tag, no bundle change.
- **Agent-readiness and client-persisted identity:** N/A.
- **Falsifiable evidence:** every check above was run in its fail direction first.
- **Runtime parity:** the live green for `pay-referrer` waits for the staging deploy of PR #1073. It is not claimed here.

## Self-Check: PASSED

- I checked that every commit is on HEAD: e9afe35e, b3f76e4e, 45d45b9c, f5507bd2, c51f8f5f.
- I checked that the created files exist: page-metadata.test.ts, evidence/pay-referrer-red-green.txt, evidence/rp-count.sh.
