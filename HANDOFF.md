# Handoff: Phase 26 MERGED — milestone v2.3 build complete; `main` CI blocked on one business decision

**Generated**: 2026-07-26 (rewritten after Phase 26 + two CI fixes merged; supersedes the pre-merge version)
**Checkout**: `feature/phase-26-local-k8s-overlay` @ `78eaa99` — **3 commits behind `origin/main`**, which is expected: that branch is merged and dead. Do new work from `origin/main`.
**`origin/main`**: `53f0444`
**Status**: All engineering done and merged. **`main`'s CI/CD Pipeline is RED and cannot go green until a human decides the production domain.**

## Goal

Close out milestone v2.3. The build is **6/6 phases**. What remains is the milestone close-out sequence plus one blocking business decision.

## What landed on `main` this session

| Commit | What | Verified |
|---|---|---|
| `a67f50d` | **Phase 26** — committed `k8s/local` overlay, 5 CI gates, live minikube rehearsal, 4 real prod defects fixed (PR #267) | `k8s/local/*` (6 files) + the 3 new gate scripts read back out of `origin/main` |
| `5cd1ddf` | gitleaks: allowlist Phase 26's planted probe secret (PR #273) | 2 fingerprint lines present in `.gitleaksignore` on `main` |
| `53f0444` | Trivy: clear the frontend image gate at the base image (PR #275) | both `rm -rf npm` and `apk upgrade` lines present in `frontend/Dockerfile` on `main` |

All three squash-merged, matching repo convention (every recent PR lands as a single-parent commit).

## THE BLOCKER — a decision, not a task

`main`'s pipeline fails at `Build and Push Images (frontend)`, step `Build and push Docker image`:

```
#12 FATAL: refusing to build the frontend image — required NEXT_PUBLIC_* build-arg(s) are empty:
         --build-arg NEXT_PUBLIC_API_URL=<value>                (missing or empty)
         --build-arg NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL=<value>  (missing or empty)
```

This is Phase 26's own CR-02 gate working as designed — a build that cannot produce a working CSP must not produce an image. Confirmed identically on **both** `a67f50d` and `5cd1ddf` runs, and reproduced locally (`docker build` with no build-args → exit 1).

Fix is two repo variables, **currently both unset** (`gh variable list` returns empty):

```bash
gh variable set FRONTEND_PUBLIC_API_URL --body '<origin>'
gh variable set FRONTEND_PUBLIC_CUSTOMER_KEYCLOAK_URL --body '<origin>'
```

**Their values depend on an unresolved question**: `jtoye.co.uk` is **NOT REGISTERED** (`host -t NS` → NXDOMAIN; Nominet RDAP → 404). `jtoyedigital.co.uk` — the parent company site — **is** registered. Every staging/production hostname in `k8s/base` targets the unregistered one, so cert-manager could never issue `jtoye-tls`, and the name is squattable. Pre-existing; Phase 26 did not introduce it.

**Blast radius while unset**: the matrix is fail-fast, so the frontend failure **cancels `core-java` and `edge-go`**. *No images publish at all*, and both deploy jobs skip.

## Not Yet Done

- [ ] **Decide the production domain**, then set the two variables above. Everything else is downstream.
- [ ] Milestone v2.3 close-out, in this order (per `project_v23_sequencing`): `/gsd-review-backlog` → `/qa-council` → `/gsd-complete-milestone`. **QA audit goes last.**
- [ ] **Issue #274** — gitleaks allowlists are inert. Fix is one env line: `GITLEAKS_VERSION: "8.27.2"` in `.github/workflows/gitleaks.yml`.
- [ ] **Issue #276** — no base-image refresh path; add `fail-fast: false` to the build matrix.
- [ ] **Evidence row L6** — a KDS client receiving a relayed order event through a real broker has still never been captured. #266 is *fixed* (`d964a85`) but unproven; `26-VALIDATION.md`'s INFRA-02d row stays RED **deliberately**. Do not read the fix as a proof. Cheapest closure: capture it on the next `scripts/k8s-local-up.sh` rehearsal.
- [ ] 6 open security warnings + 7 code-review warnings from Phase 26, deliberately scoped out — see `deferred-items.md`. #270/#271/#272 cover the sharpest.
- [ ] Remote branch `feature/phase-26-local-k8s-overlay` still exists; safe to delete.

## Failed Approaches (Don't Repeat These)

Everything in the previous handoff's list still stands (~22 unfalsifiable criteria: already-0 greps, self-comparing diffs, `UP-TO-DATE` builds executing nothing, `.Created` vs `.Metadata.LastTagTime`, `grep -q` inverting under `pipefail`, stale `core-java/build/` vs live `build-local`). **New ones found this session:**

- **A fresh `git clone -b main <local-repo>` checks out your STALE local `main`, not `origin/main`.** A gitleaks scan reported "no leaks found" and would have concluded there was nothing to fix. Always `git checkout <explicit-sha>` and echo it before scanning.
- **AWS's documentation example key (`wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY`) is allowlisted by gitleaks' own default config.** Using it as a "does the scanner still catch real secrets?" control gives a **false pass**. Use a freshly random key.
- **`gitleaks detect` without `-v` prints only counts, no findings.** Grepping for `Finding|Secret|File:` returns nothing and reads as clean.
- **`[[allowlists]]` (plural) is silently inert in gitleaks 8.24.3**, the version `gitleaks-action@v2` downloads. Singular `[allowlist]` with an identical regex works. Fails *safe* (scans more, not less). → #274
- **Trivy findings in a Node image are usually NOT app dependencies.** `tar`/`sigstore` had **zero** entries in `frontend/package-lock.json` — they are npm's own bundled deps at `/usr/local/lib/node_modules/npm`. An `overrides` bump would have been a PR that could not work. Check the lockfile before assuming.
- **A PR's green `gitleaks`/`Build and Push` proves nothing about a fix to them.** `build-and-push` is `skipping` on `pull_request`, and gitleaks scans a narrow `--first-parent A^..B` range on PRs vs `--log-opts=-1` on push. Prove locally.
- **`Integration Tests` completing in 9s is a path-gated short-circuit, not a run.** Check elapsed time before claiming a suite passed.
- **`gh pr checks --json` is unsupported** by the installed `gh`. Parse the plain tab-separated output.
- **`python3` is blocked by the `block-base-python` hook.** Use `sed`/`awk`, or activate a named conda env.

## Key Decisions

| Decision | Rationale |
|---|---|
| Merged Phase 26 despite knowing `main`'s pipeline would go red | The redness is pre-existing (failing since `e01e654` 01:41) and gated on a business decision, not on Phase 26's correctness. All 14 PR checks were green. |
| gitleaks fix via `.gitleaksignore` fingerprints, **not** an inline `gitleaks:allow` | `.gitleaksignore`'s header prefers inline allows, but line 378 sits inside a fenced block of **verbatim captured tool output**; editing it would corrupt the evidence it exists to preserve. |
| Did **not** fix #274's root cause in the same PR | A version bump makes the path exemptions genuinely active for the first time, which *reduces* scanning coverage on planning docs. That is a real widening of exemptions and needs its own review, not a ride-along. |
| Trivy fixed by deleting npm from the runner stage, not bumping CVEs | npm is build-time only; the stage runs `node server.js` + a `node -e` healthcheck. Removes the whole finding class rather than chasing 7 `tar` CVEs. Both halves proven load-bearing in isolation. |
| Used a **git worktree** for both fix branches | A second session shares this checkout; switching its HEAD is unsafe. Worktrees left it on its own branch throughout. |

## Current State

**`origin/main` = `53f0444`.** Working tree clean apart from this file. The checkout is 3 commits behind main **by design** — its branch is merged.

**CI on main**: RED. Three runs, all `conclusion=failure`, all identical:
`Build and Push Images (frontend): failure` → `(core-java): cancelled` → `(edge-go): cancelled` → both deploys `skipped`.
A third run on `53f0444` (`30204053683`) was `in_progress` at handoff time and will fail the same way.

**Environment**: minikube profile `jtoye` **Stopped, not deleted**. `/etc/hosts:10` = `192.168.49.2 api.jtoye.local app.jtoye.local`. **compose XOR k8s** — never both (shared dev DB). Docker 29.6.2 present; `gitleaks`/`shellcheck` are NOT installed on this host — use containers (`zricethezav/gitleaks:v8.24.3`, `koalaman/shellcheck:stable`).

## Resume Instructions

1. **Start from main**, not this branch:
   ```bash
   git fetch origin && git switch -c <new-branch> origin/main
   ```

2. **Once the domain is decided**, set both variables, then re-run the pipeline:
   ```bash
   gh variable set FRONTEND_PUBLIC_API_URL --body '<origin>'
   gh variable set FRONTEND_PUBLIC_CUSTOMER_KEYCLOAK_URL --body '<origin>'
   gh workflow run "CI/CD Pipeline" --ref main
   ```
   - Expected: the frontend build passes CR-02, then passes the Trivy gate (`53f0444` cleared it), then all three legs publish.
   - If Trivy fails again it is a **new** CVE, not a regression — `53f0444` was proven against the exact gate flags.

3. **Verify by actual state, not watcher exit code** — `gh pr checks --watch` exit code is unreliable in this repo:
   ```bash
   gh run list --branch main --workflow "CI/CD Pipeline" --limit 1
   ```

4. **Reproduce the Trivy proof** if you touch `frontend/Dockerfile`:
   ```bash
   docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest \
     image --scanners vuln --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 <image>
   ```
   Expected: exit 0. Build the image WITHOUT the fix first and confirm exit 1 — a gate never seen failing is not evidence.

## Warnings

- **`jtoye.co.uk` IS NOT REGISTERED.** Decide before setting `FRONTEND_PUBLIC_API_URL`. See THE BLOCKER above.
- **A second Claude session shares this checkout.** Stage by explicit path — `git add -A` / `git add .` / `git commit -a` are unsafe. Prefer a worktree for any branch work.
- **`docs/metrics.json` is a cross-branch conflict hotspot and neither side is ever right.** Recipe: `git merge origin/main` → `scripts/docs-freshness.sh --write` → `scripts/docs-freshness.sh` (exit 0). The gate validates the JSON but **not the prose quoting it** — `CLAUDE.md:15` and `AGENTS.md:15` cite the counts (currently **1736**) and must change in the same commit.
- **Do NOT `gsd-sdk query state.record-session` mid-plan** — it silently bumps `completed_plans`, rewrites `percent` on a different denominator, and destroys `last_activity`. Hand-edit `STATE.md`. `roadmap.update-plan-progress` is safe.
- **Do not add `Co-Authored-By` trailers** (global instruction, and the repo's dominant convention is no trailers).
- Local minikube does **not** enforce NetworkPolicies (D-11), has no TLS, and drops the PIT-1 nginx header snippet. Do not enable snippet annotations to make something pass — see `k8s/LOCAL.md` §6.
- **`k8s/goldens/.pre/` is gitignored scratch.** Committed goldens are `k8s/goldens/{staging,production}.yaml`; regenerate with `render-golden.sh --write` and commit the reviewed diff in the same change — CI enforces this.
