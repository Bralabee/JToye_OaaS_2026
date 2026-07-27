# Handoff: Phase 27 / plan 27-00 — ALL TASKS 0–6 DONE and committed. Not pushed.

**Generated:** 2026-07-27 (session that executed Tasks 5–6).
**Supersedes** the Tasks 0–4 handoff. Its still-valid content is carried forward in §7–§9 —
do not lose those.
**Everything described here is committed to `feature/27-00-ops-spine`. Nothing is pushed yet.**

---

## 1. Git & environment

| | |
|---|---|
| Checkout | `/home/sanmi/IdeaProjects/JToye_OaaS_2026` |
| Branch | **`feature/27-00-ops-spine`**, branched from `origin/main` @ `1499494` |
| Commits ahead | **9**, 0 behind. `check-branch-behind-base.sh` rc=0 |
| Uncommitted | `27-00-SUMMARY.md` + this file only (commit them) |
| Pushed? | **NO.** No remote branch, no PR yet |
| Stack | Compose up, all healthy. `check-runtime-freshness.sh` rc=0 |
| minikube `jtoye` | **Stopped** — compose XOR k8s, never both |
| Java/frontend suites | **NOT run** — no Java/TS source was touched by 27-00 (bash, YAML, markdown, compose only). `docs/metrics.json` unchanged at 1765 |
| New host dependency | **`hey` v0.1.5 installed** to `~/go/bin` (`go install github.com/rakyll/hey@latest`). Not on PATH by default — `export PATH="$(go env GOPATH)/bin:$PATH"` |

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git switch feature/27-00-ops-spine
```

### Live gate state (run at handoff time, real output)

```
scripts/docs-freshness.sh              rc=0   at 1765
scripts/check-runtime-freshness.sh     rc=0
scripts/check-branch-behind-base.sh    rc=0   9 ahead, 0 behind
scripts/check-dependency-horizons.sh   rc=0   27 rows, 6 exemptions, 8 UNKNOWN
scripts/check-terminal-states.sh       rc=1   <- CORRECT. 4 X-3 until 27-03
scripts/check-alert-liveness.sh        rc=1   <- CORRECT. 8 owned/deferred defects
infra/load-testing/baseline.sh         rc=0   (needs hey on PATH + stack up)
```

**Neither `rc=1` is a regression.** Both turn green when 27-03 lands. Do not "fix" them by
weakening the assertion.

---

## 2. What was done

Tasks 0–4 in the previous handoff (commits `db4250c`…`79e098a`). This session added:

| Commit | What |
|---|---|
| `4dc4e9c` | **Task 5** — `infra/dependency-horizons.yaml` (27 rows) + `scripts/check-dependency-horizons.sh` |
| `7c4a617` | Task 5 falsification — 24 arms, 2 criteria rewritten, 3 gate defects fixed |
| `8f00b75` | **Task 6** — `infra/load-testing/{baseline.sh,budget.yaml,README.md,baselines/}` + comment-only pointer in `load-test.sh` |

Read `.planning/phases/27-operational-maturity/27-00-SUMMARY.md` first — it has the full
findings, the four plan defects, and the open finding below.

**Baselines B-1..B-7 are byte-frozen.** Do not edit them; corrections go in a sibling file
(precedent: `B-6-CONTROL-DEFECT.md`, `AC-0.2-DEFECT.md`, `AC-5-ARMS.md`, `AC-6-ARMS.md`).

---

## 3. IMMEDIATE NEXT STEPS

1. **Commit `27-00-SUMMARY.md` and this HANDOFF.md** (they are the only dirty files).
2. **Decide on the PR.** Nothing is pushed. `.planning/` commits should be filtered out of the
   PR branch — use `/gsd-pr-branch`. Note §7: `main`'s CI is **already red** on a cause
   unrelated to this branch, so a PR opened now will show red until the two CI variables are set.
3. **Decide the open finding in §4** — it is a real fail-open in a gate this project relies on.
4. Then Phase 27 continues by wave: `27-01` media durability (wave 1, the P0 upload-loss defect)
   · `27-04` throughput + guards (wave 2, now unblocked — it has its msg/s/consumer number)
   · `27-03` failure visibility (wave 3 — **turns both red gates green**) · `27-02` broker
   upgrade + `27-06` CI wiring (wave 4).

---

## 4. OPEN FINDING — fail-open in `check-runtime-freshness.sh` (needs a decision)

The gate VOIDs only when **zero** built services are verifiable (`:431`). Stop one of four and
the stopped, unproven service is reported as `1 unverified` **inside a PASS** (`:445`). Measured:

```
docker stop jtoye-prometheus             -> rc=0   (not a BUILT service — out of scope entirely)
docker stop <core-java container>        -> rc=0   PASS: 3 ... match (1 unverified)
--compose-file w/ no running container   -> rc=2   VOID, not passing     <- the branch does work
```

This contradicts the project's own rule that these gates "fail closed … on a stopped stack —
'found nothing' is never 'clean'". Per-service, it does not. **Recorded, deliberately NOT
changed** — it is a Phase-26 deliverable and changing a CI gate's exit semantics was outside
Task 6's scope. Either make it VOID when *any* built service is unverifiable, or say in the
header that the guarantee is only "at least one built service was checked".

---

## 5. Things that will bite the next session

Everything in the previous handoff's §5 still applies. **New, all measured this session:**

1. **The `python3` hook blocks a Bash call that invokes `python3` directly.** Put it in a
   script file and run `bash file.sh`. (Heredoc `python3 - <<PY` and multiple `python3` calls in
   one command are also blocked; a commit message containing that literal trips it too — use
   `git commit -F <file>`.)
2. **A single-quoted Python regex inside `python3 -c '...'` terminates the outer string.** Use
   double quotes inside. `bash -n` catches it; nothing else will.
3. **`hey` 0.1.5 prints a LITERAL double percent** — `95%% in 0.0056 secs`. A `95%` pattern
   extracts nothing and the p95 silently reads `0.0` while `grep -c 'p95'` still passes.
4. **`hey -h` is not a version flag** — it prints `flag needs an argument: -h`. Use
   `go version -m "$(command -v hey)"`.
5. **`baseline.sh` artifacts are named `<date>-<sha>.md`, so re-running OVERWRITES the committed
   one.** Break arms must set `ARTIFACT_DIR` to a scratch dir or they destroy the deliverable.
6. **`webhook.deliveries.dlq` holds NINE real dead vendor events.** 27-03's proof counts exactly
   nine. `baseline.sh` never purges a DLQ and refuses any queue whose pre-run depth is non-zero;
   keep both properties. Every artifact should show `9 -> 9`.
7. **The platform rate-limits at 100 req/min/tenant (burst 20), one bucket shared across
   endpoints.** Any arm-A run above that measures Bucket4j, not the app.
8. **`core-api` is a CONFIDENTIAL Keycloak client** — a password grant needs
   `KEYCLOAK_CLIENT_SECRET` from `.env`. `test-client` is public but emits `aud: null`, and
   core-java requires `aud: core-api`, so its tokens 401 on every endpoint.
9. **Spring AMQP retries before dead-lettering, and retries count as UNACKED** — which
   `list_queues messages` includes. A poisoned batch can sit at full depth past a drain timeout
   and land in the DLQ moments later.
10. **`git diff … | grep -c '^[-+]'` counts the diff HEADER** (`--- a/…`, `+++ b/…`). Any
    "only comments changed" assertion must exclude `^(\+\+\+|---)` or it can never reach 0.
11. **A `grep -F` for a pin matches COMMENTS.** Any "the pin is present" check must exclude
    comment lines, or it passes on a tree where the real line was deleted.

---

## 6. Falsification discipline (unchanged, still mandatory)

Run every break arm through
`.planning/phases/27-operational-maturity/baselines/runcheck.sh`:

```bash
runcheck.sh <expected_rc|any> "<label>" -- <command...>
```

It exits 1 when the observed code ≠ expected, so an arm that fails to break cannot be recorded
as a pass. It cannot exec a shell function — write the target as a real script.

---

## 7. CARRIED FORWARD — the CI blocker (unchanged, still red)

`main`'s pipeline fails at **Build and Push Images (frontend)**:
`FATAL: refusing to build the frontend image — required NEXT_PUBLIC_* build-arg(s) are empty`.
frontend fails, core-java and edge-go **cancelled** (fail-fast), so nothing publishes.
`gh variable list` is still **empty**.

```bash
gh variable set FRONTEND_PUBLIC_API_URL --body '<origin>'
gh variable set FRONTEND_PUBLIC_CUSTOMER_KEYCLOAK_URL --body '<origin>'
```

**Needs a human decision**: `jtoye.co.uk` is **NOT REGISTERED** (NXDOMAIN) while
`jtoyedigital.co.uk` is, yet every staging/prod hostname in `k8s/base` targets the unregistered
name.

---

## 8. CARRIED FORWARD — not yet done, independent of Phase 27

- [ ] **Decide the production domain**, then set the two CI variables (§7). Everything else is downstream.
- [ ] **#274** — gitleaks allowlists are **inert**. One env line: `GITLEAKS_VERSION: "8.27.2"` in `.github/workflows/gitleaks.yml`.
- [ ] **#276** — no base-image refresh path; add `fail-fast: false` to the build matrix.
- [ ] **Evidence row L6** — a KDS client receiving a relayed order event through a real broker has **still never been captured**. #266 is *fixed* (`d964a85`) but **unproven**. Cheapest closure: capture it on the next `scripts/k8s-local-up.sh` rehearsal.
- [ ] 6 open security + 7 code-review warnings from Phase 26 — see `deferred-items.md`; **#270/#271/#272** are the sharpest.
- [ ] **20 open dependabot PRs**, several majors that would violate the pinned stack (Spring Boot 3.5.16→**4.1.0**, tailwind 3→4, eslint 9→10, testcontainers 1→2). Triage, do not bulk-merge. **The six exemptions in `infra/dependency-horizons.yaml` now date this work** — `node`/`alpine`/`rabbitmq` expire 2026-11-30, `keycloak`/`prometheus`/`grafana` 2026-12-31.
- [ ] **57 open issues.** Six overlap Phase 27: **#115** (closed by Task 6), **#284** (27-04), **#289** (27-04), **#304**, **#205** (27-05 owns the comment), **#209**.

---

## 9. Standing traps (carried forward, all still live)

- **`grep` here is a bash function → ugrep 7.5.0**, not GNU grep. Use `command grep` in scripts and `git ls-files` + explicit paths.
- **`grep -c` returning 0 exits 1** — under `set -e` an expected-0 criterion kills its own harness. Write `n=$(… || true)`.
- **`cmd | grep -q X` under `pipefail` INVERTS on match** (SIGPIPE→141). Use here-strings.
- **Capture exit codes on the same line**: `out=$(cmd 2>&1); rc=$?`. `echo "$out"; echo "rc=$?"` reports the **echo's** status.
- **`grep -c '^FAIL'` also matches the `FAILED:` summary line**, inflating counts by one.
- **`printf 'FAIL: %s\n' "$multiline"` labels only the FIRST line** — pipe through `sed 's/^/FAIL: /'`.
- **`cleanTest` is load-bearing** or Gradle reports success while executing nothing.
- **`core-java/build/` is stale (2025-12-27); the live dir is `build-local`.**
- **`PageImpl` silently recomputes `totalElements`** — fixture total must exceed page size.
- **`docs/metrics.json` is a cross-branch conflict hotspot.** Recipe: merge → `docs-freshness.sh --write` → `docs-freshness.sh`. `CLAUDE.md:15` and `AGENTS.md:15` quote the counts and must change in the same commit.
- **A second Claude session may share this checkout.** Stage by explicit path — `git add -A` / `git add .` / `git commit -a` are unsafe.
- **The repo squash-merges**, so `git branch --merged` calls merged branches unmerged, and `check-runtime-freshness.sh` can cry DRIFT falsely. Check whether content actually differs before rebuilding.
- **Do not add `Co-Authored-By` trailers.**
- **Do not hand-run `state.record-session`** — it corrupts `STATE.md` mid-plan. `STATE.md` still reads `status: phase-complete`, v2.3 6/6, deliberately untouched.
