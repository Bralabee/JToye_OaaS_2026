# Quick 261104-auth — SUMMARY (persona close-out, PR #1114)

Written by the supervisor from the executor's reports (the executor's Write of this file was refused by the harness). Evidence files are in `evidence/` beside this file.

| Issue | Outcome |
|---|---|
| 843 | Fixed. Refresh route is stateless (no server token cache): a refused refresh clears nothing and answers `refreshRefused`; cookies are cleared only when the browser's one retry (sent as the query parameter `?retry=1`, not a custom header, so no CORS allow-list is involved and INV-24 holds) is also refused; transient errors (5xx, 429, bad body, network) clear nothing. Keycloak 26.8.1 measured: replaying a spent refresh token revokes the session, so the browser serialises session probes across tabs with a Web Lock (30 s bound) and never sends a replay. A generation counter stops a stale probe joining or clearing across sign-in/sign-out. Residual: a reload mid-refresh can still replay the spent token. |
| 847 | Templates fixed (reset links 1 h, verify links 24 h; `staging-realm-import.sh` refuses a staging realm below the floors); the live staging realm needs the owner's kcadm step in `docs/runbooks/keycloak-action-token-lifespan.md`. Branded/HTML emails (SW-JRD-06 half) not done. |
| 848, SW-MRC-12, SW-SAM-10 | Fixed for sign-in, register, register-after-error and forgot-password: FTL overrides of `template.ftl`, `login.ftl`, `register.ftl` add main landmark, page title, no positive tabindex, password rules up front, privacy link. New gate `scripts/check-keycloak-theme-ftl.sh` (Keycloak version marker, a11y properties, rules sentence derived from `passwordPolicy`, lifespan floors, k8s mounts; selftest 26/26). Still stock: `user-profile-commons.ftl`, `login-update-password.ftl`. |

RED → GREEN: #843 late-probe and two-tab cases red on main and on the round-1 memo code, green after; #847 measured from real emails (exp 5 min → 60 min / 1440 min); #848 axe + structure 12 failures on the old theme → 0 on four pages (`frontend/e2e/keycloak-login-a11y.verify.mjs`, run against real Keycloak containers, not in CI). Gates: full jest 307 suites, lint, build, theme gate + selftest, wordmark and realm-import selftests, k8s copy-parity, render-golden, render-invariants, gate-enforcement, docs-freshness, doc-metrics, 0 behind base. Dashboard suites flaked under host load (~60 on 16 cores) and pass alone.

Review round 1 (on 6f5ada0b): 4 admissible fixed (see the PR resolution comment), 4 recorded.
