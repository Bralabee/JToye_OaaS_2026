# Handoff: `main` green; duplicate issues fixed; main checkout resolved; Phase 38 planning next

**Generated 2026-08-24; updated 2026-08-28 (nightly-E2E resolution), 2026-08-31 (customer-surface fixes), 2026-09-02 (QA council `20260902-134741` planned), 2026-09-04 (remediation recorded), 2026-09-05 (review remediated + housekeeping) 2026-09-07 (round 2 concluded, branch reconciled with main) later on 2026-09-07 (dependabot queue + architecture diagrams merged), 2026-09-22 (runtime re-proven, stack torn down for a planned pause), 2026-09-28 (state analysis, `main` green again, Phase 36 opened), 2026-09-29 (Phase 36 executed through plan 36-17) 2026-09-30 (Phase 36 merged), 2026-09-30 evening (#764 and #771 fixed and merged), 2026-10-01 (Spring Boot 4.1 spike, #648 closed), 2026-10-04 (persona testing merged as Phase 37, Spring Boot renumbered to Phase 38, `main` red on a Trivy time-bomb) and 2026-10-04 afternoon (Jackson 2.21.7, nightly 429 fix, review gate re-vendored; pg-backup red) and 2026-10-04 evening (pg-backup red cleared by #890) and 2026-10-04 late (de-dup #892, orgos sync, main checkout resolved). Replaces the 2026-08-18 block.** This is the only live block in this file.

**2026-10-04 (late) delta: duplicate-issue filing fixed, the orgos charters re-synced, the main checkout resolved. Resume here.**
**Where things stand.** Read the current head with `git log -1 origin/main`.
- **`main` is green.** The post-merge CI/CD run for #890 (run 37199749497) passed in full.
- **The Spring Boot branch is green.** `phase-37-spring-boot-4-1` merged `main` at `fecfd6c3`, and that is the branch's first passing run since 10-01 (run 37212702610). In it, the core-java Trivy image gate ran and passed. Outside `.planning/` the branch was byte-identical to `main`. It is behind `main` again by the PRs merged after it; merge `main` before Phase 38 execution starts.
- **#892 fixed #885.** Three issue de-dup sites (the e2e-nightly escalation and the base-image-freshness findings and VOID steps) now use `--search "in:title …"` and keep the exact-title jq filter. Their rendered step scripts, run against the live repo, give: `main` misses #683 at all three sites, the branch finds it, and a substring-title control is rejected. The next scheduled failure is the in-situ proof.
- **Duplicate pair:** issue #884 was closed as a duplicate of issue #759.
- **Orgos charters re-synced** (the OaaS side; PR named in the merge that carries this delta). `sync_agents.py --target oaas` was run with a temporary `targets.yaml` aimed at a clean worktree, because the real target root is the main checkout. Orgos invariant T03 maps `oaas-platform` to `docker-compose.frontend-3100.yml`. That file is GITIGNORED, so T03 passes only on a machine that has the local file. This is an orgos-side defect: copy the file into a fresh worktree before syncing. `~/.claude/agents` was not touched; that is machine-session work.
- **The main checkout was resolved by owner ruling:**
  - The Jackson 2.21.7 hunk was superseded by #886 and restored.
  - The two 2026-10-03 positioning memos (UK competitive, Nigeria delivery) moved to the PRIVATE `jtoye-market-intel` repo, `gtm/research/` (market-intel #67, `077c8eb5`), because this repo is public. Their OaaS links are pinned to `43ed6bbf`. The `docs/analysis/README.md` index rows were dropped.
  - Quick-task 260831-jz4's `evidence/` (41 captured login-page files) moved to `~/IdeaProjects/JToye_OaaS_2026-evidence/quick-260831-jz4-evidence/`.
  - Everything was backed up first, byte-verified, to `~/IdeaProjects/JToye_OaaS_2026-evidence/main-checkout-uncommitted-20261004/`.
  - `.planning/state.json` and `.gsd/` are GSD runtime state, now gitignored by the PR carrying this delta.
  - The checkout itself is still on an old `main`. A fast-forward there is refused by the protected-branch guard, so the owner runs `git -C ~/IdeaProjects/JToye_OaaS_2026 pull --ff-only`.
- **Tooling trap:** a review launched from this OaaS-directory session against ANOTHER repo's PR (`/code-review <url>`, `/review-verify <url>`) is resolved by the round gate against OaaS's PR of the same number. It printed OaaS's sha as "ran on", and round 2 needed `--force-round`. Run cross-repo reviews from that repo's checkout. This is a note for a machine session.

**Next, in order** (owner-chosen planning order: 38, then 31.1, then 37):
1. Plan Phase 38: `/gsd-plan-phase 38` from `../JToye_OaaS_2026-phase37`, after merging `main` into that branch. Inputs: `38-CONTEXT.md` and `38-SPIKE.md`. D-01 must pick a Jackson 3 release that clears CVE-2026-89407, and the Jackson key is re-keyed, not deleted.
2. Plan Phase 31.1: a gap-closure plan for the 17 persona clusters that belong to completed Phase 31 (see `.planning/ISSUE-DISPOSITION.md` § "Persona user-testing 2026-10-03").
3. Phase 37: run `/gsd-discuss-phase 37` before planning. It has 87 clusters in 7 sub-themes, 9 of them P0 that gate Phase 32.
4. After the next scheduled nightly, check `onboarding-blocked-flow.spec.ts` (desktop) by content (#888), and that a failure refreshes #683 rather than filing a new issue (#892).
5. Leave `../oaas-archify-diagrams` and the `phase-29-research` branch alone.

**2026-10-04 (evening) delta: the pg-backup red is cleared (#890).** (SUPERSEDED by the 2026-10-04 late delta above.)
**Where things stand.** `main` = `14e8a5c2` (#890) plus the PR that merged this delta. Read the current head with `git log -1 origin/main`.
- **#890 cleared step 1 of the afternoon list below.** `infra/backups/Dockerfile` now names `libpcre2-8-0` on the existing `apt-get install -y --no-install-recommends ca-certificates` line, so the image takes `10.42-1+deb12u2`. A comment records why there is no `--only-upgrade` and no `=version` pin.
- **Proof before merge.** Both Dockerfiles were built with `--pull` on one base (`postgres@sha256:539ceaaa…`).
  - Trivy 0.70.0 with the gate's flags: the `main` Dockerfile gave rc=1, its only finding CVE-2026-103111; the branch gave rc=0 over 145 debian packages plus blobctl.
  - In the branch image: pcre2 is `deb12u2`, `ca-certificates` is installed, and `/etc/ssl/certs` holds 303 entries, the same as `main`.
  - `check-postgres-major-parity.sh` and `check-dependency-horizons.sh` both gave rc=0.
- **Proof after merge.** `Build and Push Images (pg-backup)` passed on `14e8a5c2` (run 37199749497, 12:37Z). Its Trivy image-gate step ran and passed, and the CronJob tag was published from the gated image. When this was written, the core-java and frontend image legs of that run were still in progress; read the run's final conclusion rather than assuming it.
- **Review.** Round 1 found 0 admissible findings and recorded 1: the quick-task record cites a branch commit, which does not survive a squash-merge, as the earlier rows already do. The series ended at round 1. Quick-task record: `.planning/quick/261004-hhy-fix-pg-backup-pcre2-cve-2026-103111/`.
- **Housekeeping.**
  - The `../JToye_OaaS_2026-pcre2` worktree and its branch were removed, local and remote; the tip equalled #890's head.
  - The worktrees `../JToye_OaaS_2026-jackson2217`, `-reviewgate` and `-jackson` from afternoon step 7 no longer exist on disk.
  - **Trap:** a `git push origin --delete` from the main checkout is refused by pre-push P-2, because that checkout is behind `main`, even though a delete lands nothing. The branch was deleted with `gh api -X DELETE repos/Bralabee/JToye_OaaS_2026/git/refs/heads/<branch>` instead of `--no-verify`.
- **Unchanged:** the main checkout (`../JToye_OaaS_2026`, on `c55e545a`) still holds the other session's uncommitted work. Leave it to that session.

**Next, in order** (the afternoon list below, renumbered, with step 1 done and step 7 mostly done):
1. Merge `main` into `phase-37-spring-boot-4-1`. It was 5 commits behind at 12:45Z (#887, #888, #886, #889, #890), and one more once this delta merges; count it with `git rev-list --count origin/phase-37-spring-boot-4-1..origin/main`. The Phase 38 STATE entry rule, the Jackson re-key, and D-01's Jackson 3 choice clearing CVE-2026-89407 all still hold.
2. After the next scheduled nightly, check `onboarding-blocked-flow.spec.ts` (desktop) by content (afternoon step 3).
3. Fix the de-dup (#885) before the next nightly failure (afternoon step 4).
4. Plan Phases 37, 38 and 31.1, in an order the owner picks.
5. Sync the OaaS side of the orgos charters.
6. Housekeeping: leave `../oaas-archify-diagrams` and the `phase-29-research` branch alone. Never clean up `phase-29-research`.

**2026-10-04 (afternoon) delta: the Jackson red is closed, the nightly flake is fixed at its cause, the review gate is re-vendored, and `main` is red again on a NEW base-image CVE.** (SUPERSEDED by the 2026-10-04 evening delta above.)
**Where things stand.** `main` is #886 (`bc3341d4`) plus the PR that merged this delta. Read the current head with `git log -1 origin/main`, not from this line.
- **`main` is red on `Build and Push Images (pg-backup)`** (run 37193466380). No code change caused it.
  - Trivy found CVE-2026-103111 (HIGH, a pcre2 out-of-bounds write) in `libpcre2-8-0 10.42-1+deb12u1`. It is fixed in `10.42-1+deb12u2`.
  - The package comes from the floating base `FROM postgres:15-bookworm` in `infra/backups/Dockerfile`.
  - The same job passed at 01:24 on `c55e545a` (run 37165437798). It then failed on every run from 09:19Z onwards (`c0c97541` #774, `19cd4572` #882, `f5682f5d`, `a76ebb74`, `bc3341d4`), and none of those touched `infra/backups/`. So the vulnerability DB moved between 01:24Z and 09:19Z. This is the daily-DB time-bomb again.
  - Like the Jackson gate, `build-and-push` never runs on a pull request.
- Three PRs merged this afternoon. All were squash-merged, and each has a closed review series:
- **#887: the review gate was re-vendored to canonical** (`f5682f5d`).
  - The old `review-record.yml` ran on `pull_request` and `pull_request_review`, so it executed a PR's own copy of a workflow that holds `statuses: write`. A PR could grade itself.
  - It now runs on `pull_request_target`, plus `workflow_run` from the new `review-record-relay.yml`.
  - The vendored `review-record-check.sh` counts only OWNER, MEMBER or the Copilot bot, and judges freshness by the head a record names.
  - `gates/review-gate-onboard.sh status` reports **ENFORCED, C1-C4 ok**.
  - The new path is proven live. On #888 a failing `review-record` status posted for the unreviewed head, then flipped to success on the comment carrying the Review-Record line.
  - Round 1 found 0 admissible findings and recorded 9. Findings 1, 2 and 5 concern the canonical gate code in dotfiles, so they are machine-session work. None passes the admission test from this repo, so none was filed.
- **#888: the nightly failure is fixed at its cause** (`a76ebb74`).
  - **Cause:** the 10-02 and 10-04 nightlies failed `onboarding-blocked-flow.spec.ts` because core-java's per-tenant `RateLimitInterceptor` (a bucket of 120, `refillIntervally` at 100/min) returned 429s to the serial one-tenant Playwright run. `GET /shops` got one with `Retry-After: 3`, and `POST /onboarding` got one with `Retry-After: 9`.
  - **The product bug:** the onboarding page showed any shops-fetch error as "Create a shop first".
  - **Page fix:** the page now shows an error panel with "Try again", which also re-runs a failed shops fetch, plus a loading state.
  - **Client fix:** `lib/api-client.ts` replays ONE 429, and only the limiter's own (problem type `https://jtoye.uk/errors/rate-limited`). It retries when `Retry-After` is 0-10 and waits `Retry-After` + 1 s, because the server floors the wait.
  - **Tests:** Jest passes 1891/1891, and every new test was shown failing on the pre-fix source.
  - **Not yet proven on the real stack:** the next scheduled nightly is the first E2E run on a tree that contains #888.
  - **Open question:** the E2E stack still runs the tenant limiter at the production default. Widening it, as #409 did for the public limit, is an owner call. On its own it would hide this bug rather than fix it.
- **#886: `jackson-bom.version` moved from 2.21.6 to 2.21.7** (`bc3341d4`). This fixes CVE-2026-89407 and CVE-2026-89425 (jackson-core) and CVE-2026-91776 and CVE-2026-91777 (jackson-databind).
  - **Comment block:** it now explains why 2.21.7 was chosen, says to delete the pin once Boot manages ≥ 2.21.7, and notes that CVE-2026-89407 also lists Jackson 3.
  - **Proof before merge:**
    - `dependencyInsight` resolves 2.21.6 on `main` and 2.21.7 on the branch.
    - Trivy 0.70.0, run over each boot jar with the gate's flags: rc=1 with the four CVEs on `main`, rc=0 on the branch.
    - Unit tests: 1330/0/1.
  - **Doc citations:** six live citations into `build.gradle.kts` moved +5 lines and were re-pointed by content. Three of them, in TESTING.md and CONCERNS.md, are outside `check-doc-citations.sh`'s scan set, and its token match passes a wrong wide range anyway.
  - **Merge:** it merged with a recorded merge-from-base waiver. The head was a merge of `main` into the reviewed head, the CHANGELOG kept both entries, and the reviewed files were byte-identical.
  - **After merge:** `Build and Push Images (core-java)` passed on `bc3341d4` (run 37193466380), so the Jackson red is closed.
- **Still open from the morning delta below:**
  - #683 is OPEN.
  - #883 is CLOSED, but not by triage. #774's own squash commit `c0c97541` closed it at 08:29Z through the `close #883` text in its message. So the morning rule ("do NOT close #883 while #885 is unfixed") was already broken when that delta merged.
  - #885 (the de-dup only scans the 100 newest open issues) is still OPEN, and #683 is outside that window. **The next nightly failure will file a third copy.**
  - Phases 37 and 38 and the 31.1 gap-closure plan are not planned yet.
  - The OaaS side of the orgos charter sync is not done.
- **The main checkout (`../JToye_OaaS_2026`, on `c55e545a`) still holds the other session's uncommitted work.**
  - Its `core-java/build.gradle.kts` hunk only changes the value to 2.21.7. #886 now carries that change on `main`, together with the comment updates.
  - Pulling in that checkout needs that one hunk dropped first. Leave that, and the `docs/analysis/` files, to the session that wrote them.

**Next, in order:**
1. **Clear the pg-backup red.**
   - On its own branch, add `libpcre2-8-0` to the package list of the existing `apt-get install -y --no-install-recommends ca-certificates` line in `infra/backups/Dockerfile`. Installing a package that is already present upgrades it to the candidate version. Bump that exact dependency, not the whole base image.
   - **Do NOT add `--only-upgrade` to that line.** It also applies to `ca-certificates`, which the base image lacks, so `ca-certificates` would silently never be installed. This was reproduced in the #889 review: rc=0, pcre2 at `deb12u2`, and `/etc/ssl/certs` nearly empty. Trivy and the `dpkg` check would both pass, but blobctl's TLS to Blob and Entra fails, and backups stop.
   - **Do NOT pin `=10.42-1+deb12u2`.** Bookworm mirrors keep only the current version, so the pin breaks the build as soon as a newer revision lands.
   - Prove it before merge:
     - Build both images with `docker build --pull -f infra/backups/Dockerfile infra/backups`, one from the main Dockerfile and one from the branch Dockerfile. Without `--pull`, the main-side result depends on whatever base image is cached locally. Record the base digest of each build.
     - Run Trivy 0.70.0 with the gate's flags (`image --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1`) on both images. Expect rc=1 naming CVE-2026-103111 on main and rc=0 on the branch.
     - In the branch image, confirm `dpkg -s libpcre2-8-0` reads `deb12u2`, that `ca-certificates` is installed, and that `/etc/ssl/certs` is populated.
   - After merge, check the result of `Build and Push Images (pg-backup)` on `main`.
2. Merge `main` into `phase-37-spring-boot-4-1`. It is behind by #887, #888, #886 and the PR that merged this delta; count it with `git rev-list --count origin/phase-37-spring-boot-4-1..origin/main`.
   - The Phase 38 STATE entry rule below still holds.
   - On that branch the Jackson key is re-keyed, not deleted.
   - D-01 must pick a Jackson 3 release that clears CVE-2026-89407.
3. After the next scheduled nightly, check `onboarding-blocked-flow.spec.ts` (desktop) by content; it should pass. If it fails again, pull the trace with `gh run download <id>` and look for a 429 that was not replayed.
4. Fix the de-dup (#885) before the next nightly failure. #883 is already closed, so the only open tracker is #683, and the de-dup cannot see it.
5. Plan Phases 37, 38 and 31.1, in an order the owner picks.
6. Sync the OaaS side of the orgos charters (step 5 of the morning delta).
7. Housekeeping. Remove the worktrees `../JToye_OaaS_2026-jackson2217` (#886 merged), `../JToye_OaaS_2026-reviewgate` (the branch of the PR that merged this delta) and `../JToye_OaaS_2026-jackson` (#760 merged). Leave `../oaas-archify-diagrams` and the `phase-29-research` branch alone. Never clean up `phase-29-research`.

**2026-10-04 delta — persona testing became Phase 37; Spring Boot 4.1 is now Phase 38; `main` is red on new Jackson CVEs. Resume here.** (SUPERSEDED by the 2026-10-04 afternoon delta above.)
**Where things stand.** `main` = `c55e545a` (#881).
- **`main` is red in two places, neither caused by a code change:**
  - CI/CD failed at `Build and Push Images (core-java)`. Trivy found 4 HIGH CVEs in Jackson 2.21.6
    (CVE-2026-89407 and CVE-2026-89425 in jackson-core; CVE-2026-91776 and CVE-2026-91777 in
    jackson-databind), all fixed in 2.21.7. That job (`build-and-push`) runs on pushes to `main`,
    `phase-*` and `phase/**` and on releases, never on pull requests, so a PR goes green without
    it. The Spring Boot branch has failed the same gate since 2026-10-01 (runs on `fc3bc4ed` and
    `77d140dd`). CVE-2026-89407 also lists Jackson 3 (`tools.jackson.core`) as affected.
  - The main checkout has uncommitted work from the evening of 2026-10-03, on no branch: the
    Jackson value bumped to 2.21.7 in `core-java/build.gradle.kts`, a modified
    `docs/analysis/README.md`, and two untracked `docs/analysis/*-POSITIONING-2026-10-03.md`
    files. These are the only copies. They belong to whichever session wrote them: do not stash,
    discard or sweep them into another commit.
  - The 2026-10-04 nightly (run 37171812464, on `c55e545a`) failed: 325 tests, 318 passed,
    1 failed, 6 skipped. The failing test is `onboarding-blocked-flow.spec.ts` "bad company number
    -> fix inline -> re-run checks -> honest in-review" (desktop): `#onboarding-shop` was never
    visible within 10 s. It is not yet triaged. The run's report artifact expires 2026-10-18. The
    nightly failed on 2026-10-02 and passed on 2026-10-03.
  - That failure filed #883 as a duplicate of #683. The escalation step's de-dup only looks at
    the 100 newest open issues, and #881's 103 issues pushed #683 out of that window. That is
    issue #885.
  - `docs-freshness` was red on `main` from #881 onwards, because this file was more than 3 merged
    commits behind. This update clears that.
- **Persona user-testing passes 1-2 (#881, epic #880) are merged.** 15 personas produced 123
  clusters (P0 16, P1 32, P2 49, P3 26), filed as 103 issues plus the epic, labelled
  `ux-persona-test`.
  - **Phase 37 = Real-world operations readiness:** the 87 clusters with no other home. It is not
    planned yet.
  - Phase 31's 17 clusters need a 31.1 gap-closure plan. 19 more go to Phases 29, 30, 32, 33 and 34.
    The mapping is in `.planning/ISSUE-DISPOSITION.md`.
  - Raw evidence (screenshots, scripts, session-state files) lives OUTSIDE the repo, in
    `~/IdeaProjects/JToye_OaaS_2026-evidence/`. Never commit the session-state files.
- **Phase 38 = Spring Boot 4.1 migration (#706).** It was opened 2026-10-01 as Phase 37 on branch
  `phase-37-spring-boot-4-1` and renumbered to 38 on that branch by owner ruling 2026-10-04
  (`37afb5a5`). The branch keeps its name. It is pushed, has no PR, and is checked out at
  `../JToye_OaaS_2026-phase37`. **Its plans are not written yet.**
  - The spike on 4.1.1, run on OpenJDK 25 because the host has no Temurin, was feasible:
    unit 1330/2 fail, integration 745/1 fail. Flyway is proven to run. spring-statemachine 4.0.2
    works on Framework 7.
  - The spike found three defects that no test catches today:
    - `KeycloakAdminClient` sends a garbage body under Jackson 3.
    - 18 `application*.yml` keys are silently ignored, including the Zipkin endpoint and prod log retention.
    - The netty and Tomcat CVE pins must move to their Boot-4 lines.
  - Owner decisions D-01..D-04: Jackson 3 now, explicit starters, keep statemachine, keep a plain
    `Bearer` 401. For D-01, pick a Jackson 3 release that fixes CVE-2026-89407, or the branch's
    image gate stays red after Jackson 2 is gone.
  - On that branch, read `38-CONTEXT.md` first, then `38-SPIKE.md`, both in
    `.planning/phases/38-spring-boot-4-1-migration/`. `38-SPIKE.patch` is a map of the package
    moves only: it used the classic starters, which D-02 reverses.
- **The phase-number collision.** `gsd_run query phase.add` scans sibling git worktrees, not
  branches. It skipped 37 only because `../JToye_OaaS_2026-phase37` had the Spring Boot branch
  checked out. #882 corrects the Phase 37 note on `main` and reserves Phase 38 there with a
  roadmap bullet. The same text is on the Spring Boot branch (`6f813335`). When that branch next
  merges `main`, `.planning/STATE.md` conflicts only on its own Phase 38 entry. `main`'s side of
  that conflict is empty, so resolve it by taking the branch's side (the Phase 38 entry). Taking
  `main`'s side deletes the entry.
- **Issue #648 is CLOSED.** The #726 fix was proven on the production path (edge → core, real chain,
  real converter). Bracketed break arms, run on `43ed6bbf`:

  | Run | Tests | Failures |
  |---|---|---|
  | clean | 16 | 0 |
  | `@PreAuthorize` removed | 16 | 2 |
  | `require()` ×2 removed | 16 | 3 |
  | `requireGroupAdmin()` removed | 16 | 1 |
  | clean again | 16 | 0 |

  The evidence is in the issue's closing comment.
- **orgos charters.** The jtoye-orgos pull request that makes the seven OaaS charters version-free
  merged 2026-10-01. They now point to `package.json`, `go.mod` and the build files instead of
  restating versions, because otherwise every dependabot bump either reds `check-doc-versions`
  (it checks the ORGOS block too) or gets reverted by the next sync. The OaaS side is not synced
  yet: the ORGOS block in `AGENTS.md` still names Next 16.3.6.
- Open pull requests besides this one and #882: dependabot #739 (springdoc 3.1.1, which cannot
  land before Boot 4.1), #775 and #776.

**Next, in order:**
1. Merge #882, then merge `main` into `phase-37-spring-boot-4-1` (keep the branch's Phase 38 STATE entry).
2. Move the Jackson pin to 2.21.7 on its own branch and PR, so `main`'s core-java image builds
   again. Changing the value is not enough: the comment block above it still says 2.21.6 in its
   reasoning ("WHY 2.21.6 and not 2.21.7") and in its delete condition, so update both. PR CI
   never runs the image gate, so the PR must prove the resolved version with
   `./gradlew dependencyInsight --dependency com.fasterxml.jackson.core:jackson-databind --configuration runtimeClasspath`.
   Then merge `main` into the Spring Boot branch again.
3. Triage the nightly failure named above before 2026-10-18. Do NOT close #883 as a duplicate
   while issue #885 is unfixed: the next failure would file a third copy. Close #683 into #883
   instead, or fix the de-dup first.
4. Plan Phases 37 and 38 (`/gsd-plan-phase 37` on `main`, `/gsd-plan-phase 38` from
   `../JToye_OaaS_2026-phase37`), plus the Phase 31.1 gap-closure plan. The order is the owner's call.
5. Sync the OaaS side of the orgos charters in its own PR: the ORGOS block in `AGENTS.md` plus the
   generated `.github/chatmodes`, `.github/instructions` and `.cursor/rules` files. All of them
   predate the R-16 charter sections, so the re-sync also adds those. Rewriting `~/.claude/agents`
   is machine-session work, so leave it to a machine session.
6. After Phase 38 merges, rebase or recreate #739. springdoc 3.1.1 is then part of the migration.

**Housekeeping (2026-10-01, still true 2026-10-04):**
- Left in place:
  - `feature/jackson-2.21.6-cve-2026-68497`: #760 merged, but the branch is still checked out at
    `../JToye_OaaS_2026-jackson`.
  - `feature/archify-architecture-diagrams`: #734 merged, but the branch is checked out at
    `../oaas-archify-diagrams`.
  - `phase-29-research`: never clean it up.
- After their PRs merge, remove the worktrees `../JToye_OaaS_2026-p37note` (#882) and
  `../JToye_OaaS_2026-handoff1001` (this update).
- Toolchain drift was reported on 2026-10-01 only, nothing applied: ripgrep, docker-ce and
  antigravity-hub are DRIFT, and carl-core is PIN-BEHIND.

**2026-09-30 (evening) delta — GDPR erasure fixed twice over (#764, #771); `main` green; the stack runs `eb2e98fd`.** (SUPERSEDED by the 2026-10-04 delta above.)
**Where things stand.** `main` = `eb2e98fd`. Items 1-3 of the morning delta below have merged:
#768 (terminal-state deferrals, which cleared the required Operational Contracts red), #766 (the
review-gate vendored copy refreshed), #767 (Phase 36 state) and #770 (issue #764: V67 `reviews`
UPDATE policy, tenant-scoped review lookup, photos deleted after commit). Then **#772 closed issue
#771**. A review's client-supplied `photoUrls` could name the shop's own catalogue images, and
erasure deleted them. Erasure now deletes a photo only if both hold:
- its key is `<tenant>/reviews/<orderId>/<name>` (`ReviewPhotoKeys`);
- no product, shop or media_asset row references it.

Review creation refuses anything else with a 400 of type `invalid-review-photo`. Since nothing
writes under that path yet, **no non-empty photoUrls value is accepted until a review-photo upload
path exists**. No migration: V67 is still head. Record:
`.planning/quick/260930-l63-fix-771-review-photourls-unvalidated-so-/`.

Measured, not remembered:
- **CI on #772's head (`e7b6cbdb`) passed.** The squash commit's tree is identical to that
  head's tree (tree `81aa953f`).
- **Nightly E2E:** ONE scheduled green, on 09-30 (on `c5d16ff6`). The 09-29 scheduled run
  FAILED (on `db725c94`); the 09-29 green was a manual `workflow_dispatch` (on `536daf41`).
  Neither green ran on a tree containing #770 or #772, so there is no full-suite E2E evidence
  on `eb2e98fd` yet. Issue #683 stays OPEN per its own rule.
- **Runtime:** core-java was rebuilt via `scripts/sync-runtime.sh`, and the freshness gate
  passes 4/4 on `eb2e98fd`.
- **Live probe:** a POST naming a real product image URL returned 400
  `https://jtoye.uk/errors/invalid-review-photo`. The wrong-email control returned a different
  400, and no row was written.

**Review series on #772 ended at round 1 with 0 admissible findings; 10 were RECORDED on the PR.**
Carry two forward to the review-photo upload design:
- a catalogue-referenced own photo survives erasure;
- the catalogue check runs in the transaction, but the delete runs in `afterCommit` with no
  re-check.

Neither is reachable through the application today: nothing in `core-java/src/main` writes
under `reviews/`. An object placed there some other way (a manual upload, a fixture, a restored
backup) is not excluded, and would make both reachable.

Two pre-existing defects were seen but not filed; they fail the admission test:
- `createReview` never checks that the order belongs to the slug's shop;
- the review endpoint takes the reviewer's email as a bare query parameter.

**Housekeeping (this evening).** Every gate passed: the claims gate (47/47), `check-doc-metrics`
(37/37), `docs-freshness`, and edge-go vet/build/tidy/`test -race`. Eight merged branches were
deleted locally and remotely; each tip equalled its merged PR's head.

Deliberately kept:
- `phase-29-research`, the canonical paused body;
- `feature/archify-architecture-diagrams` and `feature/jackson-2.21.6-cve-2026-68497`, both
  checked out in other worktrees.

The jackson branch shows as "unpushed" only because its remote was deleted after PR #760 merged.
Its tip `51fc3279` is #760's head, and the pin is on `main`. Its worktree
`../JToye_OaaS_2026-jackson` can be removed by hand.

Toolchain drift is reported, not applied: conda, npm, claude-code, gemini-cli, copilot, uv and
antigravity-hub, plus carl-core PIN-BEHIND.

**Next, in order:**
1. The dependabot queue: #765, #756, #751 and #739. Their check state moves while they are
   worked, so read it live (`gh pr checks <N>`) rather than from here. Durable facts: #756's
   `Run Tests` red is 6 Jest timeouts, all in
   `frontend/app/dashboard/__tests__/marketing-kitchen-shop-scope.test.tsx`, under the group
   bump; #739 (springdoc 3.x) needs Boot 4.1 (issue #706) and reds the OpenAPI Breaking-Change
   Gate, tests, integration tests, security scan and docs-freshness; a dependabot bump that
   changes a version the docs quote reds docs-freshness via `scripts/check-doc-versions.sh`
   until the live docs are updated on the PR.
2. Issue #648: a within-tenant BOLA on `/api/v1/sync/batch`. #726 added the fix
   (`@PreAuthorize` on `SyncController` plus `shopAccessService.require` in `SyncService`) and
   `SyncBatchAuthorizationIntegrationTest`. Verify the authorization is on the production path
   and the test fails with it removed, then close it. Issue #727 (sync-created products
   orphaned) is in the same code.
3. Gate-class issues that fail open: #761 and #769 (`check-doc-citations.sh`); also #758
   (workflow `run:` shell unparsed), #762 and #759.
4. Phase 29 stays PAUSED on the owner (staging DNS plus 3 operator secrets).
5. Upstream, not this repo: dotfiles issues #279 and #280 (review-gate holes found by #766's
   review).

**2026-09-30 delta (SUPERSEDED by the evening delta above; #768, #766 and #770 merged) — Phase 36 is merged; `main` passes CI but its required Operational Contracts check is red until #768 merges.**
**Where things stand.** Phase 36 is complete (18/18 plans with a SUMMARY). It merged as PR #763,
squash commit `c5d16ff6` "Phase 36: Azure Blob Storage Throughout (#763)", on 2026-09-29 at
22:14 UTC, after the D3 review series ended on round 2 with 0 admissible findings. The
`phase-36-azure-blob-storage` branch is finished. Read from GitHub on 2026-09-30, not remembered:
- **CI/CD run 36638381316 on `c5d16ff6` succeeded.** Every test job passed, including unit,
  Testcontainers RLS integration, public E2E and MCP, as did lint, the security scan, operational
  contracts (it ran on 09-29, the day before the deferrals expired), the OpenAPI gate and the k8s secret guard. All four image builds
  passed: core-java, edge-go, frontend and pg-backup. pg-backup published `:15-blob` only after
  its Trivy gate, with the scanned image's digest. The deploy jobs were skipped, as they were on
  the previous `main` run (`cce0723b`).
- **The first scheduled nightly on `c5d16ff6` (run 36658969040) succeeded.** Of 325 Playwright
  tests, 319 ran and passed, 0 failed and 6 were skipped (budget 6). The restore drill passed,
  with arm A restoring 0 and arm B restoring 23 = live 23. The five scheduled nightlies before it
  (09-25 to 09-29) were red; the last green one was 09-24. Issue #683 is still OPEN. Per its own
  rule, close it only when you can name the change that fixed it: the most likely one is Phase
  36's removal of the withdrawn retired-store images, and one green run does not make the lane
  trustworthy.
- **Required check red on every branch since 2026-09-30.** Fourteen deferrals in
  `docs/ops/terminal-states.yaml` expired that day, so `check-terminal-states.sh` X-2 fails the
  required **Operational Contracts** check on `main` and on every PR. **PR #768 fixes it:** 7
  rows resolved, because their rules already exist, and 7 re-dated to 2026-12-31 by owner
  ruling. Nothing else merges until #768 does; after that, merge `main` into each open PR.
- **Local runtime.** The compose stack is UP (10 containers healthy, core-java on :9090). The
  post-merge hook reports core-java and frontend as DRIFT, because their images predate the squash
  commit. The images were built from the phase branch, so whether the content actually differs is
  not measured. Run `bash scripts/sync-runtime.sh` before trusting the runtime for E2E.
**Next, in order:**
1. **Merge PR #768**, the terminal-state deferrals. It unblocks everything below.
2. **Merge PR #766** (review-gate vendored copy refreshed to canonical; its series ended at
   round 1) and **this PR**, each after a merge from `main`.
3. **Issue #764, the most serious open defect. It is TWO defects, and fixing only the first
   makes things worse.**
   - (a) `reviews` has no UPDATE policy, so erasure's anonymising UPDATE matches 0 rows and the
     erasure rolls back, but only after the review photos are deleted.
   - (b) `findByCustomerEmail` runs under `reviews_tenant_read`, which shows PUBLISHED reviews
     across tenants.
   - Shipping the UPDATE policy (V67) without the tenant-scoped lookup would let an erasure
     anonymise, and delete photos for, **another tenant's** reviews. Photo deletion must also
     move to after the commit.
   - In progress as quick task `260930-bvp` on branch `fix/764-gdpr-erasure-reviews`.
4. **Phase 29 stays PAUSED** on the owner's staging DNS and the 3 remaining operator secrets.
   Phase 36 no longer blocks it. Its hand-off is
   `.planning/phases/36-azure-blob-storage-throughout/36-PHASE29-HANDOFF.md`.
5. Still open from before: dependabot PRs #765, #756, #751 and #739 (#739 needs Boot 4.1,
   #706); issues #758 and #648. Two review-gate holes found by #766's review are filed
   upstream as dotfiles issues #279 and #280.

**2026-09-29 delta (SUPERSEDED by the 2026-09-30 delta above; 36-18 ran and the phase merged) — Phase 36 executed through 36-17; only 36-18 (the nightly on a runner) remains.**
**Where things stand.** Branch `phase-36-azure-blob-storage`, 17 of 18 plans with a SUMMARY
(`.planning/phases/36-azure-blob-storage-throughout/`). The remote copy of the branch is still at
`5b6e76bd` (pushed when the phase opened, measured with `git ls-remote` on 2026-09-29), so the
plan work is local only, and no PR exists for the branch. core-java stores
media through the Azure Blob SDK: Azurite locally (the digest-pinned 3.37.0 image, loopback 10000,
public `jtoye-images` and private `jtoye-quarantine`), and Workload Identity against `jtoyestgmedia`
/ `jtoyestgbackup` in staging (`jtoyeprodmedia` / `jtoyeprodbackup` in production). The pg-backup
image is `:15-blob` and uploads with `blobctl`. A repo-wide gate,
`scripts/check-no-object-store-residue.sh`, keeps the retired store out.
`docs/metrics.json` was regenerated once, in 36-17, to **4130** logical invocations.
**Requirements:** BLOB-01, 03, 05, 07, 08, 09 and 10 are complete. BLOB-02 and BLOB-06 are partial
until Phase 29 provisions the real accounts. BLOB-04 is partial until 36-18 runs the nightly. Each
row in `.planning/REQUIREMENTS.md` names its remaining limb.
**Phase 29 hand-off:** `.planning/phases/36-azure-blob-storage-throughout/36-PHASE29-HANDOFF.md`
covers the superseded decisions, the operator secrets dropping from 7 to 3, the provisioning order
(the runbook `docs/runbooks/azure-blob-provisioning.md`), the verification items, and a
35-file merge-conflict map for `phase-29-research`. That branch was read, never written.
**Resume:** run plan 36-18. It is not autonomous: it needs the owner's approval to push the branch
and dispatch one nightly run, then it reads that run to its report and takes the final
`scripts/check-runtime-freshness.sh` and `scripts/check-branch-behind-base.sh` readings. The broken
windows open in `.planning/WINDOWS.md` (#1 name check, #2 nightly drill never run on a runner, #3
live AKS admission path) belong to 36-18 and Phase 29.

**2026-09-28 delta — state analysis, `main` green again, and Phase 36 (Azure Blob) opened.**
**Where things stand.** `main` = `db725c94`. PR #757 is MERGED: `amqp-client` 5.33.1 → 5.34.0 for
CVE-2026-75516 (issue #754 CLOSED) plus the missing `fi` that had kept
`base-image-freshness.yml`'s tracking-issue step from ever filing. `main`'s post-merge run
`36457853757` succeeded with the Trivy image gate step itself green on core-java — the first green
image gate on `main` since 2026-09-13. The gap that let the `fi` hide (nothing parses shell inside
workflow `run:` blocks) is issue #758, OPEN.
**Why the stack cannot simply be restarted.** Two findings from the analysis, both still true: the
weekly `docker-prune-weekly` run on 2026-09-27 deleted EVERY project image because the stack was
down (volumes survived — `postgres_data` 448.2MB and the retired object store's data volume 3.6MB,
matching the 09-22 checkpoint); and the retired object store's vendor withdrew its community images
for good (repo archived 2026-04-25, its `quay.io` images answer 401 to anonymous pulls — control
images on the same registry answer 200).
That second fact is the whole cause of the nightly E2E being red since 2026-09-25 (issue #683 OPEN;
its body still points at core-java health, which is wrong). Do NOT try to re-source the retired store.
**Owner ruling: object storage becomes Azure Blob throughout (Phase 36).** Staging/prod on Azure
Blob Storage, Azurite (`mcr.microsoft.com/azure-storage/azurite`) locally and in the nightly;
the self-hosted store it replaced and the never-provisioned AWS object-store target retired. Four
decisions are locked in
`.planning/phases/36-azure-blob-storage-throughout/36-CONTEXT.md`: backups to a separate immutable
Azure account in another region (SUPERSEDES Phase 29 D-12, the subscription-loss trade recorded),
AKS Workload Identity (no stored key), reseed local media (leave the retired store's data volume in place, 36-CONTEXT D-04), raw Blob
endpoint (custom domain deferred to Phase 32). Phase 36 blocks Phase 29 plans 29-11 onward and cuts
Phase 29's unfilled operator secrets from 7 to 3.
**Resume:** Phase 36 is planned and in execution on branch `phase-36-azure-blob-storage`; plan
progress is in `.planning/ROADMAP.md` and each plan's SUMMARY under
`.planning/phases/36-azure-blob-storage-throughout/`. The runtime now stores media in Azurite
locally (containers `jtoye-images` public, `jtoye-quarantine` private) and targets Azure Blob
Storage with Workload Identity in staging/production.
**Still open from the analysis, not started:** dependabot PR #739 (springdoc 3.1.1) cannot merge on
Boot 3.5 — its parent POM is Boot 4.1.0 — and belongs after #706; #751/#749 are low-risk but need
the doc-quoted versions updated; #756 fails 6 Jest tests under React 19.3; issue #648 is fixed in
code by #726 (`SyncBatchAuthorizationIntegrationTest`) but still OPEN; the Base Image Freshness
workflow has been red daily since 2026-09-08 (libexpat CVE-2026-93990 on the published image); the
review-gate script vendored at `scripts/gates/review-record-check.sh` lags the canonical copy
(missing the #249 cancelled-run supersession); `.planning/state.json` is an untracked GSD Core
artefact, deliberately never committed.

**2026-09-22 delta — the runtime was re-proven fresh, then deliberately torn down; ordinary work resumes 2026-10-01.**
The local stack was found RUNNING but stale: `scripts/check-runtime-freshness.sh` failed with the
frontend image tagged 2026-09-07 against build inputs that moved on 2026-09-13 (`0a0e277f`, sharp
0.35.4). Rebuilding it surfaced a composition of two known traps that is worth carrying forward,
because neither one alone predicts it. First, `docker compose -f docker-compose.full-stack.yml up
-d --build frontend` is **not frontend-only**: `frontend` depends_on `core-java`, so compose
rebuilt and recreated core-java as well. Second, the recreated core-java then took host port
**:9091** out of its `9090-9091:9090` range, because the container it replaced was still releasing
:9090 at creation time. The frontend bundle bakes `NEXT_PUBLIC_API_URL=http://localhost:9090` at
build time, so that pair silently moves the API off the port the browser calls while SSR keeps
rendering pages perfectly — the #671 failure mode, reached through a door the #671 note does not
name. Recovery was `up -d --force-recreate --no-deps core-java` (it took :9090 first try), after
which the monitoring stack was started so Prometheus pins :9091 and the range cannot re-roll.
**That mitigation has a price, and it is not free:** `docker-compose.full-stack.yml` declares the
`9090-9091:9090` range specifically so `--scale core-java=2` can give replica 2 host :9091, and
Prometheus squatting that port makes a 2-replica scale-up fail on port allocation. It buys a
correct single-replica dev stack at the cost of the documented 2-replica one. The durable fix is
at the root and is NOT in this change: either pin `"9090:9090"` for the single-replica dev
topology, or teach `scripts/check-runtime-freshness.sh` to assert the PUBLISHED HOST PORT — it was
green throughout this incident while the smoke suite was failing every test, because it compares
image timestamps and never looks at where the container is published.
Re-proven afterwards, in this order: the freshness gate green on all four built services; the
smoke suite passing every test — and it had been observed failing every test minutes earlier while
the port was wrong, so the instrument was shown able to fire; and the real browser-side call,
`GET /public/shops`, returning three shops, which is the exact request
`frontend/app/shop/shop-discovery-client.tsx` issues. Both compose projects were then brought down
**without `-v`**, so all six named volumes survive. The checkpoint to compare against on the next
bring-up, read from the live database rather than remembered: schema head **V66**, and the
`shops` TABLE at 5 rows. Note the two shop numbers in this paragraph are different measurements
and the reconciliation was NOT established before teardown: `GET /public/shops` returned 3, the
table holds 5, and whether the other 2 are unpublished/inactive or something else is unverified.
Use the table count as the volume checkpoint, and do not read a 3 from the public endpoint as
evidence of loss until that filter is confirmed.
**Opened in the same session:** a PR raising the `netty.version` pin to 4.1.137.Final for
CVE-2026-75595 (an SNI/mTLS bypass, CVSS 9.1) and CVE-2026-75596. It had been sitting as an
uncommitted working-tree edit that a rebuild had already baked into the running image — live in the
runtime, on no branch and in no review — which is the state this delta exists to end.

**2026-08-31 delta — customer-surface P0/P1 fixes (PR #711, quick task 260831-gnm).** A five-lane
human-like utilisation audit of the customer surfaces found 15 defects; PR #711 fixed the six with
root causes in frontend code — the **P0**: vendor Sign Out never ended the Keycloak SSO session
(one click silently re-entered the dashboard) — plus the `/shop` search-revert, the stale-response
race, the hero paint-then-vanish, the non-fail-safe customer signout teardown, and the systemic
cookie-notice overlay. All six browser-verified fail→pass in two rounds with the audit's own repro
scripts. Full record: `.planning/quick/260831-gnm-fix-p0-p1-customer-surface-audit-finding/` and
the memory file `project_customer_surface_audit_20260831.md`.
**Same-day follow-ups (later on 2026-08-31):** the Keycloak realm half (R-05/R-06/R-11) shipped —
SMTP via Mailhog + a custom `jtoye` login theme, quick task 260831-jz4, merged as `44bf842e`; the
owner then found **R-16**, the "anonymous downgrade" cart-ownership leak (a newly registered
customer inherited the previous account's basket — a signed-out render re-stamps `owner: null`,
which any next sign-in adopts), fixed in quick task 260831-lxf via PR #715 with the lesson
institutionalised (memory `trap_identity_transition_state_laundering`, qa-discover
identity-transition sweep, three agent charters via jtoye-orgos PR #29 — that PR awaits an
owner-side GitHub Actions billing fix, then `make agents-all`). **Still open from the audit**: the
`/shop` CLS 0.1616 breach — **not** a `size-adjust` fix: measured 2026-09-02, the running build
already ships `size-adjust:111.93%` for the Work Sans fallback, and the cause is the flex-wrap
chip row at `frontend/app/shop/shop-discovery-client.tsx:524` (20+12+2 chip + 8 gap = 42 px exactly);
`frontend/e2e/perf-budgets.ts`'s docstring still misattributes landing CLS to hydration —
vendor back-channel logout (front-channel only today), the P2/P3 register, and two filed follow-ups: #714 (Keycloak theme-contract gate) and the
sibling-`.verify.mjs` wiring gap issue from the #715 review. Known residuals recorded in the PR
descriptions of #711, #713 and #715.
**2026-08-31 evening delta — basket bar branded + landing redesign parked.** The owner reported the
first add-to-basket popup rendering unbranded; investigation showed no toast at all — the floating
cart bar's deliberate grey below-minimum state (colour-as-state reading as a dead control). Fixed
via PR #718: always oxblood, the signal moved to an amber label stating delta AND rule
("Add £1.50 to order · min £10.00"), arithmetic unified in `lib/minimum-order.ts` (bar + checkout),
the bar extracted to `components/storefront/floating-cart-bar.tsx`, and all five findings of the
in-session /code-review applied (the interim contrast-scan test-file exclusion was reverted; the
suite lives in `frontend/__tests__/` instead — the gate's own documented placement). Verified live
on the rebuilt 4/4-fresh stack, both directions, at brixton-village-grill. In parallel the landing
redesign ran three sketch rounds (005 all rejected as re-skins; 006 winner A "The Pass" after a
malformed round taught the eyeball-390/768/1280/1920 rule; 007 full-page elaboration), merged as
PRs #719/#720 — and the owner then ruled: **the shipped 004-D landing stays as-is; 006-A/007 are
parked for later, not rejected** (PR #722 records it in the sketch MANIFEST; do not reopen
unprompted). PR #721 fixed the STATE.md row that cited a pre-squash sha. Residual filed in STATE:
dashboard toasts are still stock-shadcn white (`--secondary/--muted/--accent` never got the brand
refresh) plus two upstream `use-toast` staleness bugs.
**2026-09-02 delta — QA council run `20260902-134741` discovered and planned; remediation is the
next command.** Full `/qa-discover` + `/qa-plan` against `1833fd3b` on the proven-fresh 4/4 stack
(gate sweep 41: 39 PASS / 2 FAIL, both pre-existing — `check-alert-metrics` post-restart, and the
`check-dependency-horizons` **RabbitMQ 4.3 EOL 2026-11-30** in-window breach the manifest predicted —
since filed as #724 and deferred to the horizon by PR #725, merged 2026-09-02 evening, so the gate
is green again until 2026-12-01). **97 findings (C:2 H:23 M:36 L:36)**, 83 probes each
with a recorded fail arm, regression-by-omission NONE. The Criticals: **API-1** — the documented
read-only `integration-catalog-ro` credential writes the catalogue via `POST /api/v1/sync/batch`
(`SyncController` has no `@PreAuthorize`; the human BOLA is live for any explicitly-granted user
per `ShopAccessService:326-328`) — and **FE-1** — vendor Sign Out leaves the NextAuth cookie valid
because `@auth/core` re-issues the JWT on every session GET and `frontend/lib/api-client.ts:31`
fires ~24 per dashboard load; the other half of #711. Plan state `planned`: 20 HIGH ids, 52 LOW, 14 opt-in, 11
deferred; 26 adjudications; a separate refuter (5 REFUTED / 14 WEAKENED / 9 SURVIVES, every
REFUTED re-verified by the orchestrator — two would have been outages: a Redis allowlist missing
`java.lang.`, and a Companies House 404→FAILED remap with no zero-padding). Owner resolutions:
**E-1 yes** (vendors take delivery orders by phone/API/MCP → COR-1 ships as Fix A+B, Fix A alone
forbidden); **E-2** the target is a payment request to the customer's registered *confirmed* phone
(= #461, which now depends on #462; INT-9's interim string is `"Unpaid"`); **E-5** fail-safe —
config-injected `post_logout_redirect_uri` + new `scripts/check-keycloak-logout-uri.sh` +
`VENDOR_LOGOUT_COMPLETE_ENABLED` off outside compose. Everything lives in the git-excluded
`.qa-council/20260902-134741/` (`plan.md`, `findings.json`, `refutation.md`, `RETROSPECTIVE.md`)
and memory `project_qa_council_20260902.md`; the procedure lessons went into
`~/.claude/commands/qa-*.md` (dotfiles PR). **Not yet done — an owner checkpoint:** nothing is
filed except the RabbitMQ horizon (#724, deferred to 2026-11-30 by PR #725) — still unfiled: 2
Criticals, the top Highs, a docs epic, amendments to #648/#453/#711, and 9 new defects the refuter
surfaced — and `/qa-remediate 20260902-134741` has not run.
The gate expectation at "Resume here" is 46 (45 `scripts/check-*.sh` + `scripts/docs-freshness.sh`,
which is exactly what `check-handoff-contract.sh` H-1 counts) — this line said 41 until 2026-09-04,
contradicting the EXPECT claim below it in the same file (43 then; 44 since plan 36-08 added
`scripts/check-backup-restore-drill.sh`, 2026-09-29; 45 since plan 36-12 added
`scripts/check-media-urls-resolve.sh`, 2026-09-29; 46 since plan 36-16 added
`scripts/check-no-object-store-residue.sh`, 2026-09-29). H-1 does not read this sentence (no `**`
marker), which is the semantic rot that gate's own closing NOTE says it cannot detect.

**2026-09-04 delta — the remediation RAN, and this file said it had not.** The block above ends
"`/qa-remediate 20260902-134741` has not run"; it had, across ten `qa/cluster-*` lanes, and this
file simply was not updated. Corrected here rather than rewritten above, so the record shows what
was believed and when. **Branch `feature/qa-remediate-20260902` is now PR #726 (MERGED 2026-09-07), 76 commits /
245 files / +16k-3.5k against main.** Both Criticals are in: **API-1** `9980ad17` gates
`POST /api/v1/sync/batch` by scope + shop grant, validates items and derives the shop slug (the
read-only `integration-catalog-ro` credential could write the catalogue); **FE-1** `fe0c4a42` clears
the app session server-side on the Keycloak return leg (`@auth/core` re-issued the JWT on every
session GET, so Sign Out left the cookie live). Two migrations ship: **V65** narrows the six legacy
Envers `_aud` INSERT policies off `WITH CHECK (true)` — the `IS NULL` arm is load-bearing, because
Envers DELETE revisions carry `tenant_id` NULL by construction and the naive predicate breaks every
product/order/customer DELETE — and **V66** adds `orders.unit_count` beside an untouched
`item_count`, no backfill, NULL ≠ 0. `origin/main` is still stamped V64 until this merges, so
CLAUDE.md's "Current schema version" is correct for merged state and goes stale on merge.
**CI on `60132305`: 15 pass / 1 fail / 4 skipped.** The single failure is `review-record`, which is
head-scoped and wants a human — "no review record for head 60132305". Two CI failures were fixed en
route and neither was the remediation's fault: the OpenAPI gate + `OpenApiSnapshotTest` failed on a
stale `docs/api/openapi-snapshot.json`, regenerated in `2f39df00` — oasdiff calls it breaking
because three `200`s disappear, but `HttpStatus.CREATED` is unchanged between main and the branch
(1×/1× in `OrderController`, 2×/2× in `PublicStorefrontController`), so the snapshot had documented
a status no client ever received; and Trivy failed on four HIGH `fast-uri` CVEs that this branch
never introduced (`3.1.5` on main too, `mcp-server/package-lock.json` diff EMPTY against main),
bumped to 3.1.7 in `60132305` — the daily-DB time-bomb, not a regression. Docs and gates closed
alongside: `docs/metrics.json` was stale at 3572 against a tree measuring 3912 and is regenerated
with 22 prose claims reconciled; `.planning/codebase/` remapped (four of seven documents dated
2026-04-18); and `scripts/check-doc-versions.sh` gained a `Go` row after passing 119 claims while
every doc said Go 1.26 and the module had been on 1.27 since #674 — coverage 119 claims/6 docs ->
147/7, proven able to fail before being trusted. Full record:
`.planning/quick/260903-psy-fix-docs-freshness-metrics-drift-and-sta/`. **Not this session's work:**
`b3fd1f05` (enable graphify + ignore the 163 MB it generates) came from a concurrent session on the
same checkout and was carried up by the push — unreviewed here.

**2026-09-05 delta — the PR #726 review was remediated on the branch, then `/housekeeping` ran.**
The review (M1–M7 + Lows: tenant-scoped sync shop upsert, same-shop order guard, payload-bound
checkout key, `state`-bound vendor logout, one redirect sanitiser, body≠header idempotency key
refused 400, `it.each<[...]>` counter hole) landed as `b88480b8`; the Tomcat 10.1.59 pin went to its
own branch as PR #733 with the same `fast-uri` 3.1.7 lock, so whichever merges second rebases cleanly.
**Both PRs sit at 15 pass / 4 skipped / 1 fail, and the one fail is `review-record` on each** — the
human gate, deliberately not self-satisfied (author ≠ verifier); merge is yours. Housekeeping
findings, all measured: every doc gate green from this tree (`check-claims` 47/47, `docs-freshness`
4003, `check-doc-metrics` 37/37, `check-doc-versions` 147/7 docs, `check-changelog-contract` 24/24,
`check-doc-citations` 43 verified — that last one was RED locally only because a stale duplicate of
#733's `build.gradle.kts` hunk sat uncommitted in this tree, shifting two cited line numbers; dropped
with owner OK). `docs/CHANGELOG.md` gained the `fast-uri` entry `60132305` had no line for.
`edge-go`: gofmt/vet/build/tidy/`test -race` all clean. **The compose stack is STALE for this
branch** — images built 2026-08-31 21:23Z, source last moved 2026-09-05 — pages render (`/`, `/shop`,
`/auth/signin`, `/track` 200; 404 page works) but that is the old code: `bash scripts/sync-runtime.sh`
before any E2E. Project memory hygiene went 41 FAILs → 0 (13 `type:` nestings, 27 kebab→snake link
stems, 1 missing description); the capture is committed on dotfiles branch `feature/oaas-memory-hygiene`
(`a7d334b`) but **unpushed by owner choice**: dotfiles' pre-push self-tests fail on the `master`
baseline itself (hook batteries 4/11, review-gate-onboard 16) — `feature/guard-prefix-splitter` looks
like the fix. ⚠ **New dotfiles trap, repaired here:** pushing from a LINKED WORKTREE hands the
pre-push hook an absolute `GIT_DIR`, and its test batteries' `git init` / `git -C /tmp/… commit` then
re-initialise the SHARED repo (`core.bare = true`, working tree unrecognised) and commit their fixtures
(`VERSION`, `f.txt`, a `.env` "leak") onto the pushing branch. Fixed with `git config core.bare false`
+ `update-ref` back to the real tip; nothing reached the remote. Neither the hook nor the tests unset
`GIT_DIR` — that is the dotfiles fix to make. Branch audit: nothing unpushed; no merged-PR branch left
local or remote; `phase-29-research` kept again (never had a PR, 112 unique files — k8s/base +
staging manifests and planning — an owner decision, not cleanup); the ten `qa/cluster-*` branches are
fully contained in `origin/feature/qa-remediate-20260902` but held by another session's worktrees under
`/tmp/claude-1000/`, so they are deletable only after #726 merges and those worktrees go. Toolchain
doctor: 8 DRIFT rows (gh, claude-code, gemini-cli, copilot, docker-ce, fabric-cli, fabric-cicd,
antigravity-hub) + `conda` MISSING — surfaced, not applied. Left as found: untracked
`.planning/quick/260831-jz4-fix-keycloak-realm-config-branded-login-/evidence/`.

**2026-09-07 delta — round 2 committed, the merge-with-main conflict resolved, the branch
concluded.** PR #733 (the Tomcat pin) merged to main on 2026-09-05 and made #726 CONFLICTING on
`core-java/build.gradle.kts`; the reconciliation merge `d3155923` (main → branch, in
`.worktrees/pr-726-fix`) resolved it. The 21-file round-2 review remediation found UNCOMMITTED in
that worktree (a JetBrains Junie session's work) was reviewed in full and committed as `9474805e`:
the unprotected logout-url GET made session-read-only (clearing lives solely on the state-verified
logout-complete leg), `safeReturnTo` control-character hardening with the in-band
`/\t/evil.example` foreign-origin proof, the checkout key re-bound to the last SUBMITTED payload,
the guest idempotency identity bound to the shop UUID with a legacy-compat arm behind an ownership
check, the webhook replay cache-hit authz gate, the `user_directory` refresh moved to afterCommit
REQUIRES_NEW, and a unit-count overflow guard. Two independent reviews then ran on that delta
(tenancy-security + release-QA): **no blocker**; the one SHOULD-FIX — `orders.create` carrying the
same cache-hit-bypasses-authz shape round 2 fixed for webhooks — closed in `2d8a4426` with the
webhook denial-test pattern ported and the full bracket run (pre-hoist controller: test FAILS;
restored by content: PASSES). OpenAPI snapshot regenerated for the new 410 + shop-bound wording
(`2906b8bd`); metrics 4041 → 4042 with the three prose docs reconciled each time. Instrument note:
the first full-suite run failed 116/318 integrationTest classes on `Could not connect to Ryuk at
localhost:32768` — Testcontainers sidecar churn under maxParallelForks=4, an environment failure,
not code; green on re-run with `TESTCONTAINERS_RYUK_DISABLED=true`. Frontend full suites: 172/172
Jest suites (1873 tests) + `next build` clean. **Reviewer residuals deliberately NOT chased into
this PR** (recorded in the PR description too): `media.upload`/`media.reprocess` share the
cache-hit shape at NIT severity (a hit discloses only asset id + status); the legacy-hash compat
arm has no sunset date; flag-OFF logout deployments rely on the client-side clear alone (the
documented E-5 residual). Main-checkout residue resolved: the uncommitted `build.gradle.kts`
Tomcat hunk was the SAME stale duplicate of #733 the 09-05 session already dropped once from this
tree — dropped again; its two companion pins (commons-lang3 3.20.0 over springdoc's 3.17.0,
commons-compress 1.28.0 over Testcontainers' 1.24.0, both scanner-suggested) are recorded HERE as
a candidate follow-up PR against main, not swept into #726.

**2026-09-07 later delta — the dependabot queue and the architecture diagrams merged (quick task
260907-a30).** Five PRs merged in one pass: #734 MERGED (edge-go + core-java interactive diagrams;
its counted claims were re-pinned to a990551e and migrations corrected 64→66 after #726 landed
V65/V66 — the staleness its own DIAGRAMS.md predicts), #730 MERGED (jest 29.7→30.5.1; jest 30
rejects the legacy goo.gl snapshot-header link, one line rewritten by `jest -u`), #728 MERGED
(AWS SDK 2.54.9 + Stripe Java 33.4.0 with the 6 doc version claims), #731 MERGED (codeql-action
4.37.9), #732 MERGED (download-artifact v8 — residual: its only usage sits behind the integration
path filter, so the v8-download/v7-upload pairing first executes on the next main run taking that
path; failure mode is check-jacoco rc=2 VOID, not a silent pass). The sixth, #729 (6 frontend
minor/patch bumps), is the queue's last PR and carries this delta. Its two 09-04 CI timeouts were
neither flaky nor its own bumps: bisection convicted the lockfile regeneration's TRANSITIVE float
`nwsapi` 2.2.24→2.2.27 (jsdom's selector engine; both Radix-Select role-query suites pass 20/20
~9x faster with only that pin reverted). The pin lives in `frontend/package.json` `overrides`;
issue #736 OPEN holds the exit criteria. The shared Security Scan red on all five dependabot PRs
was the Trivy `fast-uri` 3.1.5 daily-DB time-bomb, fixed on main by #733 after the branches were
cut — rebasing onto main cleared it, no code change. Full record:
`.planning/quick/260907-a30-shepherd-pr-734-and-dependabot-queue/`.

**Re-measure every figure here before quoting it forward** — that is this file's standing rule, and
the 2026-08-24 session broke it once itself (see "The truncating filter", below).

> **History moved out on 2026-08-18.** Five stacked session blocks are archived verbatim at
> **`docs/archive/HANDOFF-history-through-2026-08-17.md`** — Phase 28 close-out, Phase 33 shipped,
> and two process-forensics sessions. They carry measured break-arm results and trap mechanisms
> recorded nowhere else. The archive is **not** covered by `scripts/check-handoff-contract.sh`,
> which reads this file only — so its stale claims can neither red the build nor be trusted.

## Resume here

**#733 merged 2026-09-05; #726 was concluded by the 2026-09-07 session** — verify the actual
merge state with `gh pr view 726 --json state,mergedAt` rather than trusting this sentence.
The block below describes the `main` checkout once #726 has merged. After that merge the ten
`qa/cluster-*` branches and the `/tmp/claude-1000/…/qa/wt-*` worktrees holding them become
deletable, and `.worktrees/pr-726-fix` + `feature/fix-pr-726` go with them.

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git checkout main && git pull --ff-only && git status --short   # expect clean

# Gates. EXPECT 46 x rc=0 — and a VOID (2) is NOT a pass.
for g in scripts/check-*.sh scripts/docs-freshness.sh; do
  bash "$g" >/dev/null 2>&1 || echo "rc=$? $(basename "$g")"
done
# 2026-08-29 actual (phase 34 closeout, plan 34-10): all 40 rc=0 from the MAIN checkout,
# including check-e2e-skip-budget re-earned at 6 skips / budget 6 on a fresh full-suite
# run (297 tests, 0 failures), and check-jacoco-coverage at 88.07/71.95/87.55/87.53.
# 2026-08-30 (phase 35 plan 35-13): the count moved 40 -> 41. Plan 35-10 added
# scripts/check-layout-width-contract.sh, which is the 41st gate; H-1 caught the stale
# EXPECT immediately, which is the gate working. Phase-35 sweep from the MAIN checkout:
# 41/41 rc=0, but only after two ENVIRONMENT repairs that are not code defects — a stale
# untracked edge-go/coverage.out (regenerate with: cd edge-go && go test -coverprofile=coverage.out ./...)
# and a jtoye-redis-exporter still holding a REDIS_PASSWORD rotated out of .env
# (repair: docker compose -f infra/monitoring/docker-compose.monitoring.yml up -d --force-recreate redis-exporter).
# Neither is repaired by `docker restart`; the exporter needs a compose RECREATE.
# check-e2e-skip-budget is rc=1 not rc=0 on the phase-35 branch: 7 skips / budget 6, one
# undeclared (onboarding-blocked-flow) — that is open issue #686, not a phase-35 regression.
# THREE gates must be run from the MAIN checkout, not a worktree, and VOID elsewhere:
#   check-runtime-freshness  — compose project name comes from the DIRECTORY
#   check-infra-exposure     — parses `docker compose config`, needs .env to interpolate
#   check-container-config-drift — same .env dependency
# If check-alert-metrics is the only rc=1 after a core-java rebuild, its standing
# remedy is: bash scripts/seed-order-metric.sh   (restart zeroes the counter). Note it can
# also be green without the remedy if a full E2E run has just placed real orders.
```

| | |
|---|---|
| `main` HEAD | tip of `main` at or after the **PR #658** merge — deliberately NOT a sha, see below |
| Phase 31 | `42ac6dc3` — `feat(31): consumer safety and the legal floor (#633)`, 18/18 plans |
| Working tree | NOT re-measured by the 2026-09-30 delta — at the time of writing, work was in flight on `fix/764-gdpr-erasure-reviews` (main checkout) and in linked worktrees for the #767/#768 branches; run `git status` and `git worktree list` before trusting this row |
| Schema head | **V66** (re-measured 2026-09-29 from `core-java/src/main/resources/db/migration/`; V66 is COR-4's `orders.unit_count`) |
| Test manifest | **4137** logical invocations (Java 1972/303 files, Jest 1878/172, Playwright 128/28, Go 98/13, MCP 61/8) — `docs/metrics.json` as merged to `main` in PR #763 (36-17's 4130 plus 7 Java tests added on the branch after 36-17); re-measured 2026-09-30, `docs-freshness.sh` rc=0 |
| Gate sweep 2026-08-25 | **36 PASS, 1 FAIL, 0 VOID** across all 37 gate scripts, measured after the runtime re-sync AND the E2E run. The one non-pass is `check-e2e-skip-budget` **FAIL** (65 skipped vs a budget of 8, plus an undeclared skip) — it was VOID until a completed run replaced the stale report, so this is a real answer rather than an unanswerable one. Progression that day: 34/2/1 → 36/0/1 → 36/1/0 |

> **Why the HEAD row names a PR and not a sha — do not "helpfully" put one back.** A document that
> records its own repository's HEAD cannot be correct at rest: writing the sha IS a commit, so the
> act of correcting it falsifies it. Measured 2026-08-18 — the row was set to `44cacbaf`, and
> merging that update made `main` `b17bef59`, stale again in one step. `check-handoff-contract`
> reaches the same conclusion from the other side: H-3 allows a budget of **3** commits rather than
> demanding exactness. The OTHER shas in this file are historical facts and are fine to keep.

### What shipped 2026-08-24

- **#657 CLOSED** — the amqp-client CVE pin was setting `rabbitmq-amqp-client.version`, a key
  nothing reads. The Boot BOM declares `rabbit-amqp-client.version`. A redundant direct
  `implementation()` pin was forcing the right version anyway, which is exactly why the typo was
  invisible. Property corrected, direct pin removed.
- **#658 CLOSED** — `base-image-freshness.yml` **had never scanned anything, in its entire life.**
  It assembled `ghcr.io/${OWNER}/...` from `github.repository_owner` = `Bralabee`; GHCR repository
  names must be lowercase, so every leg tripped the VOID arm before reaching Trivy. Present in the
  workflow's first commit (`e705d38f`, #520, 2026-08-04). **21 consecutive scheduled runs, all
  failure, zero successes ever.** Fixed on both the scheduled and the dispatch path, plus a
  `$GITHUB_OUTPUT` injection, the missing VOID report arm, a `gh` stderr fold, and `X-6` in
  `check-image-supply-chain.sh` so a revert fails loudly.
- **#659 — fixed in PR #664** (`ec2f44f1`, merged 2026-08-25), having been filed-not-fixed the
  previous morning: `ci-cd.yaml` hardcoded the image owner as a lowercase literal in twelve places
  while deriving it from `github.repository_owner` in two. Same defect one layer down from #658;
  broke both deploy jobs on a fork, transfer or rename. **The two sides of `kustomize edit set
  image` are NOT the same string** — the LHS is a selector into the checked-in manifests, so
  deriving it from the owner too makes kustomize fall back *silently* to the immutable 2.1.0
  default. Proven by rendering the staging overlay under owner `Acme-Fork`: selector-from-file
  pins correctly (rc=0), selector-also-derived FATALs (rc=1). `X-7` in
  `check-image-supply-chain.sh` now holds all three halves — no owner literal, a lowercased
  derivation per pinning step, and `ci-cd.yaml`/`base-image-freshness.yml` still describing the
  same image. ⚠ **The reworked deploy steps have still never executed** — both deploy jobs are
  `vars.DEPLOY_*_ENABLED`-gated and skipped on every run. The overlay render is the evidence.

### What shipped 2026-08-25

- **#652 CLOSED** (`f975f83b`) — codeql-action `upload-sarif` 4.37.6 → 4.37.8, both sites.
- **#655 CLOSED** (`a6eecf70`) — docker/setup-buildx-action v3.12.0 → **v4.3.0, a MAJOR**, and the
  verification is the point. The action sits at exactly one site, inside `build-and-push`, which is
  gated `if: github.event_name == 'push' || 'release'` and **skips on every pull request** — so its
  14 green PR checks were not weak evidence, they were *no* evidence, because the job that loads it
  never ran. It was executed for real on a throwaway `phase-*` branch (whose push event does run
  `build-and-push`): all three legs green, log confirming the v4.3.0 SHA was downloaded. The
  post-merge tree was then verified **byte-identical** to that tested tree (`054cc1fc`), which is
  why it merged 1-behind-base without a re-run — a rebase would only have re-run the 14 jobs that
  cannot test buildx. Main has since built green twice more.
- **Generalisable:** before trusting a green PR on a dependency bump, check whether any job that
  ran actually **loads** the thing being bumped. Map the symbol to its enclosing job; if that job
  is `push`-gated, a `phase-*` branch is the only way to exercise it pre-merge.
- ⚠ **Left behind: nine GHCR tags** from that probe — versions `1169332429` (core-java),
  `1169264525` (edge-go), `1169267851` (frontend), three tags each including the probe's full-sha
  tag. Deleting them needs a `delete:packages` scope the working token does not carry
  (`gh auth refresh -h github.com -s delete:packages,read:packages`). Nothing references them.
- **#661 CLOSED, and #647 CLOSED with it** — the nightly full-suite E2E had been dark for **14
  consecutive nights** (2026-08-11 to 2026-08-24) without executing one Playwright test. core-java
  crash-looped every ~27s on `permission denied for table postcode_centroid` / `TRUNCATE`, resetting
  its health clock each time, so compose aborted every service depending on it. Since the SEC-04
  runtime-migrator split the app connects as the DML-only `jtoye_runtime`, and TRUNCATE is a
  DISTINCT privilege not implied by DELETE; the grant lived only in `create-runtime-role.sql`, a
  MANUAL script, and the nightly's `down -v` meant nobody ever ran it. **V64** moves the grant into
  the schema. The nightly now escalates a scheduled failure into an issue instead of dumping logs
  into the run that already failed.
- **Two blind spots kept it alive, and both are worth remembering.** It cannot reproduce locally —
  this machine's role was granted TRUNCATE out of band and `postcode_centroid` already holds
  1,748,230 rows, so the importer short-circuits before the TRUNCATE. And
  `RuntimeRoleGrantContractTest` asserts *exactly this grant* and was green throughout, because its
  `@BeforeEach` runs `create-runtime-role.sql` itself: **it certifies the script, not the
  deployment.** `PostcodeTruncateGrantMigrationTest` is the falsifiable sibling.

### What shipped 2026-08-30 (afternoon — continues the block below)

- **#684 CLOSED via PR #694** — a fresh volume provisions its own migrator credential
  (`00-create-db.sql` creates `jtoye_app` with `DB_MIGRATION_PASSWORD`, fallback to
  `DB_PASSWORD`); proven on a real `down -v` cycle with digest-confirmed differing
  credentials: healthy, RestartCount 0, V64 64/64, 45/45 E2E smoke. Instrument lesson:
  in-container `psql -h 127.0.0.1` is loopback-`trust` and accepts ANY password — auth
  probes must run over the docker network (now in project memory).
- **#688 CLOSED via PR #696** — dashed count subtitles under `loadFailed` on all four
  dashboard list pages + six raw-axios toasts routed through `describeLoadError`; jest
  now 141/1505, metrics 3494, browser-proven both directions on a rebuilt container.
- **#690 CLOSED** — owner ratified the approvals queue on the Index tier, viewed with a
  real MANUAL_REVIEW application (a side effect of #684's fresh-volume smoke).
- **Phase 35 recorded complete in STATE.md via PR #695** (counters re-measured from
  disk: 119/119 plans, 11/14 phases; percent scoped "of written plans").
- **The unexamined-defaults audit ran** (quick-260830-p2o): four findings filed —
  #699 (md:768 gives tablets the desktop sidebar), #700 (TOAST_LIMIT=1 displaces unread
  error toasts), #701 (12 dialogs on the 512px default, no density policy), #702
  (unclamped table titles) — and eight surfaces examined clean with an instrument-armed
  method. Report: `.planning/quick/260830-p2o-unexamined-defaults-audit/AUDIT.md`.
- **#697 (another session) added the review-record required-status backstop** — every
  PR now needs a review artifact (a PR review, an inline review comment, or a
  `Review-Record:` comment); the audit PR was the first judged by it.

### What shipped 2026-08-30

- **#687 CLOSED — the marketing-motion flake, via PR #692** (squash `b7b2099e`). All 7
  network-idle waits in marketing-motion + csp-no-violations replaced with deterministic
  anchors on a new inert both-branches `data-motion-decided` stamp; three break arms
  observed failing (the planned ARM C createElement vector was VACUOUS under
  `'strict-dynamic'` and was corrected to an inline event handler); clean pass 18/18
  against a rebuilt compose frontend. One CI red en route: `check-changelog-cites-pr`
  wants the entry heading to cite the PR itself, not only the issue — the gate's own
  error text says this has redded main six times.
- **#686 — the undeclared-skip cause is scripted, via PR #693** (branch
  `feature/686-skip-budget-fixture-reset`). `seed-e2e-fixtures.sh` now resets a
  LIVE/terminal demo-tenant `vendor_onboarding` (row + gates, vendor tenant only,
  `Shop.published` untouched; `RESET_ONBOARDING=0` preserves state but verification still
  fails on it). Arms: spec `1 skipped` on WITHDRAWN, opt-out rc=1, post-reset full journey
  `1 passed (6.1s)`. `check-e2e-skip-budget` re-earned VOID → PASS on a fresh full-suite
  run: **323 total / 317 passed / 6 skipped / 0 failed**, budget 6, all declared. The
  dark-lane half is #683's: the nightly's escalation covers the gate step, and tonight's
  ~02:25 UTC run is the confirming instrument for the #687 fix.
- Runtime parity after the #692 merge: `sync-runtime.sh` rc=0, 4/4 FRESH re-asserted by
  the gate's own re-check (core-java + frontend rebuilt and force-recreated, healthy).

### What shipped 2026-08-28

- **#666 CLOSED — the nightly lane is fixed, in two halves.** V64 (#661) had fixed the stack half;
  the remaining `253 passed / 6 failed` were three instrument defects in
  `frontend/e2e/storefront-flows.spec.ts`, fixed in **PR #670** (squash `7ac23442`). Details in the
  E2E section above, now corrected in place. Falsified clean → arms → clean with restores verified
  by `git hash-object`; the closing arm created **4 real orders** carrying V63 `allergen_mask`
  snapshots — the first E2E-driven successful order placements since Phase 31 merged, because
  `placeOrder()` has exactly one caller and the un-ticked LGL-03 gate had silently swallowed every
  submit since 2026-08-17. Full suite on the live stack: **266 total / 258 passed / 8 skipped /
  0 failed**; `check-e2e-skip-budget` PASS. **The confirming instrument is the next scheduled
  nightly (~02:25 UTC)** — a red there re-files automatically via the escalation step.
- **#671 OPEN — the compose port-range trap, filed with its full mechanism.** core-java publishes
  `"9090-9091:9090"` while the frontend bundle bakes `localhost:9090`, AND Prometheus binds
  `127.0.0.1:9091` from the separate monitoring compose project — so monitoring-down-after-reboot
  frees 9091 for core-java, killing browser-side API calls and blocking Prometheus in one move.
  Measured: six local-only test failures cleared by a `--force-recreate` with no code change.
- The handoff PR **#668** and the fix PR both merged; local + remote branch cleanup done
  (`chore/agents-md-resync`, the retired-SDK + Stripe dependency-bump branch, `docs/system-tour-2026-08-19`
  deleted against verified MERGED PR state; `phase-29-research` kept deliberately).

## Environment state — measured 2026-08-24, not remembered

All compose services running. Runtime is **Docker Compose** (`docker-compose.full-stack.yml`), the
canonical local dev/E2E runtime; do not start a local minikube alongside it (they share the dev DB).

| Service | Host port | Probe |
|---|---|---|
| frontend | `3000` | `/legal` → 200 |
| core-java | `9090` | `/health` → 200 (**note: 9090, not 8081** — known port shift) |
| edge-go | **`8089`** → container 8080 | `/health` → 200 |
| mcp-server | `9100` | — |
| postgres | — | schema head V63 |

**`edge-go` is published on host `8089`, not `8080`.** A probe against `localhost:8080` returns
`000`, which reads like a dead service and is not one.

**`check-runtime-freshness` is PASS, 4 of 4, 0 unverified — re-synced 2026-08-25 22:15 UTC.**
It had been FAIL, 2 of 4, from merge drift (core-java behind #665's `build.gradle.kts`, frontend
behind #653's `package.json`). Cleared with `bash scripts/sync-runtime.sh`, rc=0.

Verified **by identity, not by the script's verdict** — the container's image ID must move, not
just the tag's, because a fully CACHED rebuild advances the tag while compose leaves the container
on the old image (that is the `[image-not-rebuilt]` arm, and the reason #662 added
`--force-recreate`):

| service | before | after | container == tag |
|---|---|---|---|
| core-java | `fc187d1903fd` (08-24 20:49) | `eca36448263f` (08-25 22:15) | MATCH |
| frontend  | `64d20e2f668b` (08-17 19:57) | `86ce3c5c4be9` (08-25 22:14) | MATCH |

⚠ **A rebuild is not finished when the tag moves.** Mid-recreate, `docker compose ps -q frontend`
returned EMPTY while the new tag already existed — a wait-loop watching only image IDs fires there
and would hand the next step a stack with no frontend. Wait on the CONTAINER being up, not the tag.

**`check-alert-metrics` fired its documented standing remedy, both directions recorded**: rc=1
(`M-1 rule 'NoOrdersCreated' selector matches ZERO series`) → `bash scripts/seed-order-metric.sh`
→ rc=0 (19 live rules / 25 selectors, 3 dormant). A core-java restart zeroes the counter; this is
restart behaviour, not a defect, and it will recur after every rebuild.

`bash scripts/seed-e2e-fixtures.sh` rc=0 — vendor-refund-flow's DRAFT test and storefront-flows'
STFR-06 can assert non-vacuously again. **vendor-refund-flow's REFUND test stays skipped by
design**: it needs real Stripe test-mode keys (`STRIPE_API_KEY`), not a fixture, so a skip there
is expected and is not a coverage gap.

### E2E run 2026-08-25 23:16–23:23 UTC, against the freshly-synced stack

`cd frontend && PLAYWRIGHT_JSON_OUTPUT_NAME=e2e-artifacts/report.json npx playwright test --reporter=json,list`

**195 passed · 6 failed · 65 skipped (6.6m), rc=1.** All 6 failures are **3 tests × 2 projects**
(mobile + desktop), every one in `e2e/storefront-flows.spec.ts`. Two different root causes, and
they must not be lumped together:

**(a) 4 of 6 — INSTRUMENT defects, loose locators colliding with new legal copy.** Playwright named
the colliding elements outright, so this is not inference:

| test | locator | also matched |
|---|---|---|
| `:125` shop card renders | `getByRole('link', {name:'Browse'})` | `<a href="/legal/cookies">Cookie and browser-storage policy</a>` — `browse` ⊂ `brows`**er** |
| `:541` add items → checkout | `locator('text=Your basket')` | the cookie-policy paragraph (`We only use cookies and browser storage that are …`) |

Both are `strict mode violation … resolved to 2 elements`. The product is fine: `/shop` renders the
shop name, the cuisine tag and the nav; the sibling test `search filters shops` uses the same route
and PASSED. The fix is tighter locators (scope to the nav, or `exact: true`), not product work.
⚠ The nav link is now **"Shops"**, not "Browse" — the `:125` comment ("scope to the nav link")
describes a page that no longer exists.
**Both (a) locators fixed in PR #670 (2026-08-28)**: the card asserted by its own href (nav-scoping fails on mobile, which has only a hamburger), the basket matched by heading role.

**(b) 2 of 6 — `:770` order confirmation email: ATTRIBUTED 2026-08-28, fixed in PR #670.** The LGL-03 allergen acknowledgement (Phase 31) refuses the submit BEFORE any network call, with the button left deliberately enabled — the spec never ticked it, so the click was silently swallowed and no order row was created. Not a broken checkout; the gate doing its job. The pre-attribution record below is kept because its method notes still hold:
`getByRole('heading', {name: 'Order confirmed!'})` → `element(s) not found` after 15s, and
**no order was created**: the only rows in the window are `ORD-E2E-DRAFT-FIXTURE` (22:16:21, from
`seed-e2e-fixtures.sh`) and `metric-seed@jtoye.local` (22:16:09, from `seed-order-metric.sh`) —
both seeds, both predating the 23:16 run. No `email-<timestamp>@test.com` order exists. So the
checkout did not complete server-side; this is not merely a UI assertion problem.

- The heading itself is still in the source: `frontend/app/shop/[slug]/checkout/page.tsx:514`.
- `placeOrder()` is called by **exactly one test**, so there is no passing sibling to cross-check
  the checkout path against — worth fixing, since it makes every checkout failure a single point.
- ⚠ **Ruled out, so nobody repeats it:** the "shop no longer offers delivery" hypothesis. `shops`
  has **no** `delivery_available`/`collection_available` column (only `delivery_info`,
  `minimum_order_pennies`, `delivery_fee_pennies`, `free_delivery_threshold_pennies`), so
  fulfilment is not gated that way.
- ⚠ **Method note:** a bare `count(*)` of recent orders returned **2** and reads as "checkout
  works". It is not — both rows are seed artifacts. Read the ROWS, not the count.

**`check-e2e-skip-budget` moved VOID → FAIL**, which is progress (a completed run replaced the
stale report) but not a pass: `S-1 65 skipped exceeds the declared budget of 8`, plus `S-2` an
undeclared skip in `dashboard-interface-corrections.spec.ts`. **RESOLVED 2026-08-28: the 65 was
not budget drift** — a correctly-ported stack with freshly-seeded fixtures reports **8 skips, all
declared, gate PASS**, and the nightly independently reports 7. The 65 was the both-projects
artefact suspected here, compounded by the port-range condition now filed as #671. One genuinely
new mechanism surfaced: **the suite displaces its own DRAFT fixture on a non-fresh volume** (every
run places orders; 22 accumulated newer than `ORD-E2E-DRAFT-FIXTURE`, pushing it off the top-20
page its test reads, which skips the test undeclared) — re-run `scripts/seed-e2e-fixtures.sh`
before trusting any local skip count.

**vendor-refund-flow's REFUND test stays skipped by design** — it needs real Stripe test-mode keys
(`STRIPE_API_KEY`), not a fixture.

PRIOR, 2026-08-24, the previous clean-runtime record: PASS 4 of 4, 0 unverified. core-java rebuilt
twice, container recreated onto image `2218aa93…`; `amqp-client-5.33.1.jar` read out of the running
`/app/app.jar`, with `5.25.0`, `5.22.0`, `5.3.6` and `5.4.2` at **0 occurrences** under escaping
that finds `5.33.1`/`5.4.3`/`5.5.2` — so the zeros were about the jar, not the pattern. Broker
connected, both outboxes zero `PENDING`/`FAILED`.

## What to do next

**The roadmap says Phase 29. Phase 29 still cannot start.** It reached 9/16 and is PAUSED at its
wave-7 boundary on two owner actions, **re-measured 2026-08-24 and both still unmet**:

| Blocker | Measured 2026-08-24 |
|---|---|
| staging DNS | `dig +short` returns **no answer** for `staging.olajay.co.uk` and `api.staging.olajay.co.uk` |
| operator secrets | **0 populated / 7 declared** in `~/.jtoye/staging-operator.env` |

Neither is an engineering task. Phase 29's authoritative body — including the correct counters
(`total_plans: 93`, `completed_plans: 78`) — lives on branch **`phase-29-research`**, not on `main`.
The `progress:` counters in `main`'s `.planning/STATE.md` remain knowingly corrupt
(`completed_plans` exceeds `total_plans`) and must be repaired **there**.

If the owner has not cleared those, the highest-value available work is:

1. **The dependabot batch opened 2026-08-21 — triaged 2026-08-24, and this batch DID contain
   stale-base artifacts.** The 2026-08-18 note that "every failure was REAL — none a flake, none a
   stale-base artifact" held for *that* batch and does **not** generalise. Five of these six were
   blocked, wholly or partly, by `golang.org/x/mod` CVE-2026-56864/56865 sitting in their **base**,
   closed on main by #656. Proof: **#652** changes nothing but a workflow file, yet its Security
   Scan failed on a Go module CVE. Re-read each PR, but read the BASE as well as the diff.

   - **#653 CLOSED** — jest-axe 10→11. Only failure was the base CVE; `@dependabot rebase` cleared
     it (14 pass) and it merged as `ba33e554`. Major bump, but exercised by 22 `toHaveNoViolations`
     assertions across 10 files plus the self-testing `axe-instrument.test.tsx`.
   - **#650 CLOSED** — superseded by **#665 CLOSED**, which merged as `9a0370e3` and auto-closed
     it. Two of #650's three failures were stale-base (Security Scan; and a doc-citation pointing
     at `build.gradle.kts:103`, which #657 moved to
     `:141`). The real one is prose version pins, which dependabot structurally cannot edit — the
     same reason #604 was superseded by #638. **Note the SEVENTH site:**
     `.planning/codebase/INTEGRATIONS.md:9` carries the literal `com.stripe:stripe-java:<version>`
     token, matched by `check-doc-citations.sh`, not by the four-doc version gate. Fix the six and
     that one still reds.
   - **#652 CLOSED**, **#655 CLOSED** — both merged 2026-08-25 after #664 landed, in that order
     (`f975f83b`, `a6eecf70`). See "What shipped 2026-08-25" above for how #655 was verified;
     the short version is that its 14 green PR checks were incapable of testing it.
   - **#654 MERGED** (2026-08-30, `966d987b`) — framer-motion 12→13, a MAJOR; the owner call was
     made and it landed via the merge queue. Historical note of its pre-merge state: the remaining
     failure is the same prose-pin class (4 sites). Given #605/#606 were closed for being majors,
     this is an owner call, not a mechanical one.
   - **#651 CLOSED** — closed unmerged by dependabot itself on 2026-08-28T15:25:33Z, with the
     comment "Looks like these dependencies are updatable in another way, so this is no longer
     needed". Not an operator decision. Of its five failures, two were stale-base and were already
     clear; the rest were real work in two classes — three latent type errors that `next build`
     structurally cannot see (it type-checks the pages/app graph, not the whole tsconfig program),
     and the prose version pins dependabot structurally cannot edit, the same class that took #604
     to #638 and #650 to #665. Superseded by branch `feature/deps-frontend-651-supersede`, which
     lands the same 10-package bump with both classes fixed.
2. **#654 was the last dependabot PR needing real judgement** — MERGED 2026-08-30 (`966d987b`); see
   item 1. #651 is no longer in that set; its disposition changed on 2026-08-28 and is recorded
   above. Everything else in that batch is closed.
3. Then Phase 30 (The Money Path), Phase 32 or Phase 34.

### The H-2 self-falsification trap, learned twice on 2026-08-24/25

Worth keeping because it cost two red gates in one day, in both directions.

A HANDOFF entry that states its own PR's status **falsifies itself the moment that PR merges**, and
reds H-2 on `main` for whoever opens the next PR. #665 shipped an entry claiming its own PR was
still open; it was false within the hour. The #659/#664 entries here were deliberately written with
**no capitalised state word** for exactly that reason, and survived their own merge untouched.

Three mechanics that are not obvious until they bite:

- **H-2's vocabulary is `(CLOSED|OPEN)` only.** `MERGED` parses as *no claim at all* and slips
  through unchecked — it is not the safe way to record a merged PR, it is the way to record nothing.
- **Quoting the bad claim re-creates it.** H-2 extracts on `#NNN … WORD` proximity with no notion of
  quotation, so a sentence *illustrating* a false claim is scored as that claim. Writing this
  section required wording it to avoid naming the pattern.
- **Fix every stale claim in one pass, not one at a time.** Correcting #665 alone just moved the red
  to #650, which had auto-closed as superseded. Cross-check the whole set against the forge.

### Dependabot: what the 2026-08-18 triage concluded, and why it still applies

**#606 CLOSED** (node 24→25-alpine): endoflife.date says node 25 is `lts: false`, EOL **2026-06-01**,
already passed; node 24 is LTS to 2028-04-30. The support-horizon gate is correct.
**#605 CLOSED** (springdoc 2.8.6→3.1.0, MAJOR): the OpenAPI spec cannot be generated, so the app
likely does not boot. Needs real work. **#631 CLOSED** (frontend npm, 10 updates): real break across
the frontend build. **#604 CLOSED** (the retired object-store SDK) was superseded by **#638 CLOSED**, merged as `9387b3bf`;
its failure was `scripts/check-doc-versions.sh`, because dependabot cannot know to edit `CLAUDE.md`,
`AGENTS.md` and `.planning/codebase/STACK.md`, which each pin the version in prose. **Any future SDK
bump carries the same four-site requirement.**

**One security item was deferred and this branch discharges its precondition:** `next` 16.3.0
updates vendored lodash to 4.17.23 for **CVE-2025-13465** (prototype pollution in
`_.unset`/`_.omit`). The tree now declares `next` `^16.3.2` (lockfile resolves 16.3.3), so the
16.3.0 migration this was waiting on has happened. **The 4.17.23 figure is upstream's, carried
from the original advisory and NOT verified by content on this tree** — Next strips the version
banner from its minified vendor bundles, so `4.17.21` being absent from `node_modules/next` is
evidence about a missing string, not about the version that shipped.
**Assessed 2026-08-19: MEDIUM, LOW reachability, not urgent.** Scorers disagree only on
availability impact (NVD 5.3, GitHub 6.5, vendor 6.9, Red Hat 8.2) — quote the range, not one end.
Our code never imports lodash; the vulnerable internals live only in two vendored Next bundles whose
callers pass fixed internal keys. Fix it when the `next` 16.3.0 migration happens.
### Owner-facing, unresolved — carried from Phase 31

1. **`privacy@olajay.co.uk` must exist and be MONITORED** before the `/legal` pages naming it are
   publicly reachable. Unverifiable from this repository. A published DSAR route nobody reads is
   the same fail-open shape as no route at all, only worse — it looks discharged while a one-month
   statutory clock runs. **This is the load-bearing one.**
2. **Registered office is not published** — `NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` ships empty by
   owner decision, published as a dated exception. Recoverable by one build arg **plus a frontend
   image rebuild**, since `NEXT_PUBLIC_*` is inlined at build time.
3. **`PublicFooter:189` renders the platform's company identity on every tenant storefront**, while
   `frontend/lib/company.ts:9-12` states it must render *"never on tenant storefronts"*. Pre-existing since
   PR #232. Both readings are defensible, so it is a legal-content call, not an engineering one.
   A test pins the count at exactly one so neither answer is silently pre-empted. The number
   rendered is the ACTIVE `16471464`, not the dissolved namesake `13434105`.
4. **`contrast-literals.test.ts`'s `SCAN_ROOTS` excludes `components/legal` and `app/legal`** — the
   ledger is structurally blind to the five `/legal` routes that ARE declared in-scope surfaces.
   That blind spot is exactly why a 4.41:1 mobile contrast failure survived to the final plan.
   Widening it will likely surface further literals.
5. **31-07's Article 26 effectiveness-gate box stays UNTICKED.**

Phase 31's own deferred register is `.planning/phases/31-consumer-safety-and-legal-floor/deferred-items.md`
(DEF-31-11-01 plus five items from 31-17/31-18).

## Four more instrument failures, 2026-08-24 — one in the prescribed remedy, one already archived

These are additions to the 2026-08-18 list below, not replacements. All three produced a
*confident wrong answer*, not an error.

1. **`docker compose up -d --build <svc>` does NOT recreate the container when every layer is
   CACHED — and that command is what `check-runtime-freshness.sh`'s own failure message tells you
   to run.** Measured: after a rebuild whose inputs were byte-identical, buildx still exported a new
   manifest digest (image `89a31b0b` → `5a41e87d` → `2218aa93`), while compose printed
   `Container jtoye_oaas_2026-core-java-1 Running` and left the container on the OLD image for
   seven minutes. The health check said `healthy` throughout — of the *stale* container. The gate
   then correctly stayed red, pointing at a remedy that could not clear it.
   **`docker compose up -d --force-recreate --no-deps <svc>` is what actually works.** The repo
   already warns that `start` and `restart` do not rebuild; this extends it to the prescribed fix.
   Note also that a squash-merge re-dates the commit, so `check-runtime-freshness` goes red on
   merge even when the build inputs are byte-identical — verify with
   `git diff <built-from> <merged> -- <build paths>` before assuming real drift.

   **`scripts/sync-runtime.sh` carried the same defect, and proved it against itself.** Measured on
   the merge of #661: the script ran `up -d --build core-java`, the image moved
   `3f5b80ed -> fc187d19`, the container stayed on the old one, and the script's own re-check
   printed `FAIL: drift REMAINS after rebuilding: core-java` and exited 1 — telling the operator to
   run the command it had just run. It now passes `--force-recreate`. This is the strongest form of
   the lesson: not a claim that a command is insufficient, but a repair script reporting its own
   failure to repair.


   **CORRECTION, and the more useful finding: this was already diagnosed on 2026-08-07 and
   archived.** `docs/archive/HANDOFF-history-through-2026-08-17.md` line 673 reads, verbatim:
   *"`sync-runtime.sh` rebuilds but does not always recreate. It left both `frontend` and
   `core-java` on their previous image IDs; the gate correctly said `[container-not-recreated]` and
   both needed `docker compose up -d --force-recreate --no-deps <svc>`."* The squash-merge re-date
   finding sits in the same bullet block (*"Any PR touching a build input costs a rebuild AFTER
   merge"*). Both were correct, both were actionable, and the script stayed broken for 17 days —
   because archiving the handoff moved the diagnosis somewhere no gate reads and nobody re-reads.
   **The archive's own header warns that its CLAIMS cannot be trusted; its DIAGNOSES were fine.**
   Before concluding you have found something in this repo, grep the archive: #658's reviewer found
   the same shape there (`.planning/quick` had recorded "metadata-action lowercases owner" a month
   before that workflow was written).
2. **`awk … > new && mv new old` silently drops a file's executable bit**, and a defensive `chmod`
   in CI hid it. Two scripts went `100755 -> 100644` this way this session:
   `check-image-supply-chain.sh` (in #658) and `sync-runtime.sh` (in #662). Nothing failed, because
   `ci-cd.yaml` already runs `chmod +x ./scripts/check-image-supply-chain.sh` immediately before
   executing it directly, and every other call site uses `bash scripts/…`. So the regression was
   invisible in both directions: CI compensated, and the humans use `bash`. It surfaced only in a
   merge diff line — `mode change 100755 => 100644`. Prefer editing in place (`sed -i`), or restore
   the bit with `git update-index --chmod=+x` and CHECK THE MERGE DIFF for mode changes.

3. **The truncating filter, walked into while fixing a blind detector.**
   `gh run list --workflow X --limit 8` was used to establish *how long* a workflow had been
   failing, and answered "eight days". `--limit 100` returns **21** rows, all failure, back to the
   day the workflow landed. A bounded stream cannot answer a question about the EXTENT of
   something — that is the one thing it structurally cannot do. The wrong figure reached a commit
   message, a changelog entry and a PR body before code review caught it. This trap was already
   recorded in this repo, which is the point.

4. **`gh pr view --json statusCheckRollup` lags the job it reports.** The Testcontainers job
   completed `success` at 16:01:26Z while the PR rollup still showed it `PENDING` for minutes
   afterwards, across repeated polls. A poll loop that only reads the rollup will sit past a
   finished run. Read the JOB (`gh run view <id> --json jobs`) when the answer matters; the rollup
   is a summary, not the source.

## The instrument lied five times — read this before trusting any check

Every one of these produced a *confident wrong answer*, not an error. This is the most transferable
thing this session produced.

1. **Four background watchers reported false completions**, all from transient
   `error connecting to api.github.com`. One exited **0** with jobs still `pending`; another printed
   `SETTLED` over an **empty result table**. Every one would have read as "CI passed" from the exit
   code alone. **An empty result table is VOID, never an answer** — and `gh pr checks` returns rc=1
   both for "a check failed" and for a network error, so rc alone cannot distinguish them. Poll on
   *content*: require rows > 0 AND pending == 0.
2. **`SELECT max(version) FROM flyway_schema_history` returns `9`, not `63`.** The column is TEXT,
   so it sorts lexically. Use
   `ORDER BY (regexp_replace(version,'\D','','g'))::int DESC LIMIT 1`.
3. **A served-page assertion over conditionally-rendered elements is vacuous.** Checking the
   contrast fix on `/shop`, both the fixed and the old class pattern returned **0** — the elements
   simply do not render with current seed data. "0 occurrences of the bad pattern" is byte-identical
   to "fixed" and to "never rendered". Reading the built chunk out of the container settled it.
   This is the same shape as 31-18's own finding that a scan over nothing looks like a flawless page.
4. **`mergeStateStatus=UNKNOWN` means ALREADY MERGED, not "still computing".** On PR #641 it was
   read as "GitHub has not finished calculating", polled three times, and only caught when an
   independent check errored with `fatal: Not a valid object name origin/<branch>` — the branch was
   gone because another session had merged it. Worse, the conflict count printed beside that error
   read `0`, which was VACUOUS: `git merge-tree` had FAILED, so the grep counted nothing. **An empty
   result and a failed command look identical if you only read the number.** Before merging, check
   the PR is still `OPEN`.

5. **`git log --first-parent` is the only reason the changelog gate survived the merge.** The
   branch carried **44 `feat`/`fix` commits, none citing a PR**. Had `check-changelog-contract.sh`
   scanned all commits, a merge commit would have redded `main` with 44 uncited subjects. It scans
   first-parent, so `main` sees one commit. **Squash with an explicit subject ending `(#NNN)` is the
   only safe merge method here** — rebase strips the citation and voids the gate.

### Where the durable learnings live — READ THEM BEFORE RE-DERIVING THEM

Cross-session learnings are NOT in this file. They are in the per-project memory at
`~/.claude/projects/-home-sanmi-IdeaProjects-JToye-OaaS-2026/memory/`, indexed by `MEMORY.md`
(124 entries). A selective snapshot is versioned in the `Bralabee/dotfiles` repo under
`claude/projects/.../memory/` — 9 of the 124 as of 2026-08-19, via dotfiles PR #106. The memory
directory itself is NOT a git repo; the files are plain files on disk.

This session added `project_phase_31`, `trap_doc_recording_own_head_sha` and
`trap_gh_checks_polling_semantics`, and extended `trap_grep_pattern_shape_false_negative` (a new
SCOPE axis), `trap_rebase_merge_voids_changelog_gate` and `env_gotchas_local_stack`.

**The lesson that cost the most was one already recorded.** `trap_handoff_residue_count_stale`
already said "#429's preamble refuses to quote HEAD SHAs for exactly this reason" — and the HEAD-row
sha problem was still re-derived across three PRs before anyone noticed. Search the memory index
before concluding something is new.

### Writing in this file is itself gated

`scripts/check-handoff-contract.sh` asserts this document against reality:

- **H-1** — a bold-marked `N of N rc=0` claim requires **BOTH** numbers to equal the live
  gate-script count (currently **37**). A truthful "36 of 37" in that bold form FAILS the gate, which
  is why the summary table above states `36 PASS, 0 FAIL, 1 VOID` instead. Any `EXPECT N x rc=0`
  line must also read 37. **Note the trap: this bullet cannot quote the token it describes** —
  writing the bold form here makes the gate fire on its own definition. It did, on the first draft.
- **H-2** — a claim opts in by CAPITALISING its state word: `#116 CLOSED` is checked against the
  forge, `#116 is closed` is narrative and is not. Do not capitalise a state you have not verified.
- **H-3** — this document must stay within **3** merged commits of the base branch.

**THIS FILE WENT STALE FIVE TIMES ON 2026-08-18 ALONE**, four of them within an hour of being
written, and one caused by a DIFFERENT session merging #634. The cause is structural, not
carelessness: H-2 claims mirror forge state, and any PR merge by ANYONE falsifies them. H-2 caught
every one within minutes and named the PR — that is the design working. What it can NEVER see is
shas, prose and runtime facts, which drifted silently every single time. So: after editing forge
state, re-run the gate; and re-read the prose by eye, because nothing else will.
