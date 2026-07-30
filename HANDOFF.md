# Handoff: the gates are portable now · 11 PRs merged across two repos · every defect found was GREEN

**Generated:** 2026-07-30 ~14:10 BST. Supersedes the "PR queue empty · #342 + #234 + #330 closed"
handoff (`c43d4b98`), which was accurate at `c43d4b98` and is now stale on its whole header — it
still reports PR #359 as the open one and the working tree on a housekeeping branch.

| | |
|---|---|
| `JToye_OaaS_2026` | **`ccb15e23`** on `main`, tree clean. 4 PRs merged today: #359, #360, #361, #363 |
| `dotfiles` | **`1d149d9`** on `master`, tree clean. 6 PRs merged today: #43–#48 |
| Open PRs | **none in either repo** |
| Open issues | **59** in JToye; the one opened today is **#362** (gate consolidation, deliberately deferred) |
| Live stack | Compose UP, **17** jtoye containers, all healthy/up |
| Gates | **10/10 rc=0**: runtime-freshness · container-config-drift · claims · doc-metrics · project-version · doc-citations · docs-freshness · doc-versions · terminal-states · alert-rules |
| Runtime proof | `Implementation-Version: 2.3.0` read from inside the running `app.jar` · ollama `gemma3:12b 100% GPU UNTIL Forever` |
| Project version | **2.3.0** (artifact). No `v2.3` tag — milestone in development, CHANGELOG stays `[Unreleased]` |
| Conda env | **none needed here** — Java 21 + Gradle wrapper, Go 1.26, Node 22. This repo has no Python |

---

## 0. ⚠ READ FIRST — the one pattern that explains the whole session

**Nearly every real defect found today was a GREEN check.** Not one was caught by something going
red. If you take a single habit from this session, it is: *ask what the check actually reads, not
what it claims to cover.*

| the green thing | what was actually true |
|---|---|
| `docs-freshness` green on every commit for months | README advertised **921** tests against a tree of **1851** — and the sentence directly beneath claimed that gate guarded it. It had never opened README.md |
| `check-doc-versions` green, 84 claims | the **project version** sat at `2.1.0` through the v2.1 AND v2.2 releases — nothing compared the sites to each other |
| `docker ps` → `healthy`, `ollama MATCH` in the drift gate | the container was attached to **no network at all**; its healthcheck ran *inside* itself and never touched the network. The AI feature had been dead for weeks |
| my own new emoji classifier | `'^\s*(//\|\*)'` **could never match** `grep -n` output (the line starts with `path:NN:`), so the comment class was silently always empty |

The corollary now in the Proof Standards: *"the gate I just added now passes" is not evidence.*

### 0.1 I published a wrong conclusion into three artifacts

`docker info --format '{{json .Runtimes}}' | tr ',' '\n' | grep -iE 'nvidia|runc' | head -4`
reported **no nvidia runtime on a host that has one**. The comma-split scattered the JSON and `head`
cut the stream before the `nvidia` entry. "CPU-only, GPU cannot work" reached a **commit, a changelog
and a PR body** before I checked the container's real state and found `offloaded 49/49 layers to GPU`.

**Never bound a stream you are using to prove a negative.** Correct probe:
`docker info --format '{{range $k,$v := .Runtimes}}{{$k}} {{end}}'` → `io.containerd.runc.v2 nvidia runc`

### 0.2 The same restore failed three times, caught only by the closing arm

`cd A && …; git checkout -- path` runs the checkout in whatever directory the shell is **actually**
in — after an earlier `cd`, a different repository. It reported success while restoring nothing,
leaving a vendored file drifted across later arms. The **break** arms were all correct, which is
exactly why nothing else noticed; the repeat-clean arm caught it every time.

Now §1 of the Proof Standards: **bracket arms clean → arms → clean again**, verify restores **by
content** (a token or hash, never `git diff --stat`, which is empty both when a file is restored and
when it was never written), and **commit before running arms**.

---

## 1. What landed — JToye (4 PRs)

```
ccb15e23  feat(gates): the claim-gate engine, 43 assertions from a rule table   (#363)
ef794411  fix(ollama): could never bind its port, so it ran on NO network       (#361)
a366da2a  chore(release): bump to 2.3.0, and gate it so it cannot drift again   (#360)
72d56a0f  docs+ci: README claimed 921 against a tree of 1851                    (#359)
```

- **#359** `scripts/check-doc-metrics.sh` — 37 rules over README/CLAUDE.md/AGENTS.md → `docs/metrics.json`.
  Also fixed CLAUDE.md/AGENTS.md claiming schema **V59** when **V60** shipped in #316.
- **#360** version → **2.3.0** in `build.gradle.kts`, `frontend/package.json` + lockfile, README.
  `scripts/check-project-version.sh` (6 claims) makes gradle the source of truth.
  **Not bumped, on purpose:** the `:2.1.0` tags in `k8s/base/*-deployment.yaml` are an inert
  placeholder — every deploy re-pins to `:<sha>` and a premortem guard fails the job if that default
  survives to `kubectl apply`; `type=semver` only fires on a `v*` tag push. Also skipped
  `mcp-server/package.json` (separate `0.x` lineage) and edge-go's `@version 1.0` (OpenAPI spec version).
- **#361** ollama — see §2.
- **#363** the engine — see §3.

## 1.1 What landed — dotfiles (6 PRs)

```
1d149d9  docs(claude): §1 — assert the clean state LAST as well as first    (#48)
e96cddc  feat(git): pre-push verification — the CI this private repo lacks  (#47)
fcf6a0a  refactor(carl): resolve the conda env per project                 (#46)
2852c7b  fix(housekeeping): two false positives from a second-repo test     (#45)
545e38c  docs(claude): promote four shell traps into the Proof Standards    (#44)
d94d121  feat(gates): a reusable claim-gate engine                         (#43)
```

**Proof Standards went from 4 sections to 6**, plus four §1 entries — every one drawn from a failure
that actually occurred today, not an imagined one:

| addition | origin |
|---|---|
| §1 truncating-filter-proves-absence | §0.1 above |
| §1 exit-code-after-intervening-command | break arms reading 0 regardless |
| §1 `grep -q`/pipefail inverts on match (SIGPIPE→141) | a guard that failed **OPEN** |
| §1 closing-arm procedure | §0.2 above |
| §5 a structural check can pass while the function is broken | §2 below |
| §6 backticks inside double quotes **execute** | a commit message containing `` `kubectl apply` `` **ran it** |

---

## 2. The ollama defect — and how the repair repeated its own lesson

**Root cause:** a host-native `ollama serve` (systemd, `/usr/local/bin/ollama`) owns
`127.0.0.1:11434`. The compose service published the same port, so Docker failed
`bind host port 0.0.0.0:11434/tcp: address already in use`, **aborted networking setup**, and left
the container with `NetworkMode` set but `.NetworkSettings.Networks` **empty**.

Measured consequences: `core-java` → `bad address 'ollama:11434'`; `ollama-init` exit **1**; model
volume **24K with empty manifests** — `ollama pull` had *never once* succeeded. **The compose file
was never wrong**, which is why reading it revealed nothing.

**Fix:** `${OLLAMA_HOST_PORT:-11435}` (injected, GLOBAL_RULE_6). Changes nothing for the app —
`core-java` and `ollama-init` both use `http://ollama:11434` over the bridge network and ignore the
published port. `:11435` now serves the container; `:11434` still serves the host service (`llava:7b`).

**Then the repair demonstrated §5 again.** `docker network connect` restored attachment, so the new
`D-4` check went green — while DNS stayed broken, because that command restores the container **name**
but not compose's **service alias** (`[]` instead of `["jtoye-ollama","ollama"]`). Only a compose
`--force-recreate` fixes it; only a functional probe reveals it.

**`D-4` added to `scripts/check-container-config-drift.sh`** — every declared network must actually be
attached, compared by **suffix** since compose renders `jtoye-network` → `<project>_jtoye-network`.
Falsified against the real state: `docker network disconnect` reproduced `attachments=0` while
`docker inspect` still said `healthy`; the gate went 0 → 1 naming the network, then back to 0.
One subtlety recorded in the script: `.strip()` on the inspect output would eat the 4th field
*precisely* when a container is on no network — turning the detection into a short-parts VOID.

**Also:** `OLLAMA_KEEP_ALIVE=-1`. Cold `/api/generate` measured **71 996ms** of which **71 891ms** was
`load_duration` (8 GB disk→VRAM) against a **21ms** eval; warm is **~400ms**. `ollama ps` now reads
`UNTIL Forever`. Costs ~10 GB of the box's 11 264 MiB VRAM — lower to `30m`/`2h` if the desktop needs it.

---

## 3. The portability work — what is reusable and what is not

The question that drove this: *do these gates only work in this repo?* Answer, now demonstrated:

| layer | status |
|---|---|
| Global craft (`~/dotfiles/claude/.claude/CLAUDE.md`, 8 hooks, all skills) | **already travels** — `sync-claude.sh` syncs it, `setup.sh` reinstalls |
| The gate *pattern* | **now extracted** — `~/dotfiles/gates/` |
| Repo-specific rules | **stay in the repo** — `scripts/gates/claims.manifest` |
| Project memory (`~/.claude/projects/<slug>/memory/`) | **does NOT travel** — keyed by absolute path |

**`~/dotfiles/gates/claim-gate.sh`** — a generic engine. A new repo gets the gate by writing a rule
table, not a script:

```bash
~/dotfiles/gates/install.sh /path/to/repo    # vendors engine + template manifest
$EDITOR /path/to/repo/scripts/gates/claims.manifest
bash scripts/check-claims.sh                 # then add to CI
```

- **Vendored, not sourced.** CI runs in a fresh runner holding **one** repo, so an engine living only
  in `~/dotfiles` could never run there. `install.sh --check` compares by **content hash**, catching
  an edited copy even when `VERSION` did not move.
- **Proven to fail first:** `gates/selftest.sh` = **19 arms, 19 pass**, including one asserting a
  *dependency* bump is **not** drift.
- **`jq:` consumers are necessary, not sugar.** `package-lock.json` holds the package's own version at
  two paths *plus* one per dependency; on a 4-entry fixture the PCRE returned `3.1.4 3.1.4 1.0.0 2.7.9`.
- **Equivalence proven, not assumed:** engine vs the two bespoke gates returned **identical exit codes
  across 9 break arms**. `43 = 37 + 6`.

**Deliberately additive** — `check-doc-metrics.sh` and `check-project-version.sh` **stay in CI**
alongside it (issue **#362**), because they cross-check the engine and their headers carry the measured
evidence and the reasons for what is *not* checked. That must be **moved, not discarded**.

### 3.1 Memory is siloed and it rots — confirmed live

`jtoye-market-intel` has **5 memory files** in its own path-keyed namespace which did not load in this
session. Worse: the `HS2-PROJECTS-2025*` directories that older namespaces reference **no longer exist
at that path**. That is why `~/.carl/conda-envs.json` records **no absolute paths** — baking in a path
that has already moved once would bake in the rot — and why the four generic traps went into global
`CLAUDE.md`, which loads everywhere.

### 3.2 Portability tested, not inferred

Ran the routine on `jtoye-market-intel` (frontend-only): **5 of 6 conditional phases skipped
correctly**. Default-branch detection returned **`master`** on dotfiles — a routine hardcoding `main`
would have committed to the wrong branch.

Two false positives found and fixed (#45): the emoji scan (arrows were **35 of 70 hits** and are
typography, not icons; console output is CLI UX, not iconography) and the Phase 1 count check
(a flagged `26 files` correctly described `web/*.html` renders).

---

## 4. Open items

1. **JToye #362 — gate consolidation.** Deferred with executable conditions, not a comment that rots:
   confirm the engine has run green in CI on several PRs *and failed once for a real reason*; **move**
   the headers; confirm the claim count stays **43**; re-run the 9 arms; re-run `check-doc-citations.sh`
   (removing files shifts line numbers — that broke citations **twice** on one branch today).
2. **dotfiles Actions billing — NOT fixed.** `dotfiles` is **PRIVATE** (paid minutes);
   `JToye_OaaS_2026` is **PUBLIC** (free) — that asymmetry is the entire explanation. Its only
   workflow has failed since 2026-07-26 with *"recent GitHub Actions payments have failed or your
   spending limit needs to be increased"*; the job never **starts**, so `failure` there is a VOID
   wearing a verdict's clothing. **All six dotfiles PRs today merged on local verification alone.**
   Needs `github.com/settings/billing`. Making dotfiles public would give free Actions but publishes
   your hook logic, CARL rules and `settings.json` — not a trade worth making for CI convenience.
   Mitigated by **#47**: a `pre-push` hook running `gates/selftest.sh`, `sync-claude.sh --check` and
   `gates/install.sh --check-all`. It gated #48's own push. `--no-verify` bypasses, deliberately.
3. **No `v2.3` git tag.** Artifact is `2.3.0`; the milestone is in development. Cutting the tag is
   also what would finally push a version-numbered image (`type=semver` only fires on `v*`).
4. **Not started, from the portability plan:** running `/housekeeping` end-to-end (not just its
   read-only phases) on a second repo, and the `✕` glyph at
   `frontend/components/marketing/competitive-teardown.tsx:445` — a real close-button finding the
   improved emoji scan isolated, unfixed.

---

## 5. Environment state

- **JToye:** `main` @ `ccb15e23`, clean. No local branches besides `main`. The 264 orphaned
  `refs/remotes/pr/*` refs were deleted; restore via
  `awk '{print "update " $2 " " $1}' .git/pr-refs-backup-20260730.txt | git update-ref --stdin`
  (`update`, **not** `create` — `create` fails on an existing ref) until a `git gc` reclaims them.
- **dotfiles:** `master` @ `1d149d9`, clean. `sync-claude.sh --check` clean.
- **Live stack:** 17 jtoye containers, all healthy. 4/4 built services FRESH. Rebuilt ~11:38–11:39Z;
  `core-java-2.3.0.jar` carries `Implementation-Version: 2.3.0`.
- **Toolchain:** 6 DRIFT surfaced by `doctor.sh --check` (conda, node, npm, gemini-cli, copilot,
  ms-fabric-cli), 1 UNKNOWN (`antigravity`, policy `manual` — its recorded state, not a gap).
  **Report-only; never converge inside a housekeeping run.**
- **Synthetic test residue on the dev DB** (pre-existing, from earlier alert work): two orders for
  `liveness-probe@jtoye.local`, several `SyntheticDeliveryProbe-*` messages in Mailhog.

## 6. Resume instructions

```bash
# 1. Confirm both trees are where this handoff says
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 && git log --oneline -1   # expect ccb15e23
cd /home/sanmi/dotfiles && git log --oneline -1                       # expect 1d149d9

# 2. Confirm the stack is still parity-clean (expect rc=0 on both)
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
bash scripts/check-runtime-freshness.sh;      echo "rc=$?"   # expect 0, "4 running built service(s)"
bash scripts/check-container-config-drift.sh;  echo "rc=$?"  # expect 0, includes ollama MATCH via D-4

# 3. Confirm the new engine works and is current vs canonical
bash scripts/check-claims.sh;  echo "rc=$?"                  # expect 0, "43 claim(s) across 5 doc(s)"
bash ~/dotfiles/gates/install.sh --check-all; echo "rc=$?"   # expect 0, CURRENT (v1.0.0)
bash ~/dotfiles/gates/selftest.sh | tail -2                  # expect "passed=19 failed=0"

# 4. Confirm the AI path is live end-to-end (not just that the model file exists)
docker exec jtoye-ollama ollama ps                           # expect gemma3:12b ... 100% GPU ... Forever
docker exec jtoye-mcp-server wget -q -T60 -O- \
  --post-data='{"model":"gemma3:12b","prompt":"Reply READY","stream":false}' \
  --header='Content-Type: application/json' http://ollama:11434/api/generate   # expect "READY", ~400ms
```

If step 2 VOIDs (exit 2), the stack is down or a built service is not running — **any** missing built
service VOIDs the whole run by design. Bring it up with
`docker compose -f docker-compose.full-stack.yml --env-file .env up -d` and re-run; do **not** treat a
VOID as a pass.

If step 4's inference is slow (~72s rather than ~400ms), the model was evicted — check
`OLLAMA_KEEP_ALIVE` survived in the running container:
`docker exec jtoye-ollama env | grep KEEP_ALIVE` (expect `-1`).
