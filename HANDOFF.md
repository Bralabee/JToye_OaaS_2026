# HANDOFF — Phase 29 still paused at wave-7; three OFFLINE lanes shipped around the block

**Written 2026-08-15 (housekeeping). This section supersedes the 2026-08-11 block below for anything
concerning current execution state; everything below it is retained as history.**

**Branch:** `phase-29-research` — **20 commits ahead of `origin/phase-29-research` (UNPUSHED)**,
95 ahead of `origin/main`, 0 behind either. Tree clean.
**Authoritative state:** `.planning/STATE.md` (this file is the summary; STATE.md wins on conflict).

EXPECT 39 x rc=0 — the repo's gate-script count at 29-16 close-out (H-1's denominator:
`ls scripts/check-*.sh scripts/docs-freshness.sh`). It moved 38 -> 39 on 2026-08-15 when Lane C
added `scripts/check-deploy-digest-parity.sh`; both anchors in this file moved with it.

## The block is UNCHANGED, and was re-measured — do not re-derive it from prose

Phase 29 is still paused at the wave-7 boundary on the same two OWNER actions. Re-measured
2026-08-15, not copied forward:

| Blocker | Measurement | Result |
|---|---|---|
| DNS | `dig +short @dns1.p05.nsone.net A <name>.olajay.co.uk` x4 | **no answer for all four** |
| Secrets | populated keys in `~/.jtoye/staging-operator.env` | **0 populated / 7 keys** |

Every remaining plan traces through 29-11 (first deploy), which depends on 29-10 Tasks 2-3, which
depend on those two. **No remaining Phase 29 plan can be COMPLETED without the owner.** What was
done instead is everything that does not need them.

## Shipped 2026-08-14/15 — three offline lanes, all merged to `phase-29-research`

- **Lane A** (`260814-u4t`) — DEF-29-1: the `core-java` Service exposed 9090 only while Prometheus
  scrapes `core-java:9091`, so `up{job="core-java"}` would have been 0 on first deploy with
  ServiceDown firing permanently at a healthy app. Second ClusterIP port added; written up once as
  permanent **INV-9** in `check-render-invariants.sh`. DEF-29-4: `rabbitmq-credentials` now carries
  `default_user.conf` generated ONCE from the same variables as the flat keys (divergence =
  `ACCESS_REFUSED` on every AMQP/STOMP connection with all static gates green). A falsification arm
  caught a defect inside that fix: `$(printf '...\n')` strips the trailing newline, so the conf was
  one byte short of the operator's format — invisible to any line-by-line comparison.
- **Lane B** (`260815-00i`) — **`check-doc-citations` rc=1 -> rc=0** (13 C-3 violations repaired, 0
  remaining, 73 citations across 8 docs). It is CI-wired, so this was the last offline item blocking
  the phase PR from merging once DNS lands. Also DEF-29-7 (12 drifted horizon sites, **zero
  `eol_date` moved** — `--refresh` re-fetches live EOL dates, so that had to be proven, not assumed),
  DEF-29-2, DEF-29-5.
- **Lane C** (`260815-00p`) — `scripts/staging-pitr-drill.sh` (450 lines, EXIT trap installed BEFORE
  the restore is requested) and the federated `azure/login` deploy path with
  `scripts/check-deploy-digest-parity.sh`. **Every green in this lane is STRUCTURAL** — no cluster,
  Azure resource or database was contacted.

## Failed approaches and traps paid for this session (do not repeat)

- **The PITR drill's subscription guard could not fire.** Its deny-list held
  `c483d353-0000-0000-0000-000000000000` and `sipbihs2`, substring-matched. Measured against
  `az account list`: the employer is `8d1c4578-4129-40d5-a6be-fd24d96b7959` ("Prod - HS2 Ltd") and
  `sipbihs2` is their AKS CLUSTER name, never a subscription — so neither entry could match the thing
  the guard existed to stop. Replaying the old logic against the employer id: **blocks=0, it failed
  OPEN**. The zero-padded string was the OWNER's own prefix mistaken for the employer's, so
  "correcting" it to the bare prefix `c483d353` would have refused the CORRECT target and still not
  the employer. Fixed to an exact-match ALLOW-list (`c62ceb31`), proven three ways under
  `PITR_DRILL_DRY_RUN=1`.
- **Test the guard with `PITR_DRILL_DRY_RUN=1`, never a fabricated subscription id.** The dry-run
  mode makes no cloud and no database call. A made-up id is not on any list, so it proves nothing and
  still reaches `az`.
- **An executor's uncommitted STATE.md and SUMMARY.md live only in its worktree** and are destroyed
  by `git worktree remove --force`. Rescue both by content BEFORE removing, and verify with
  `git hash-object` on each side.
- **`--not --remotes` is not "ahead of main".** `git log origin/main..HEAD` said 95 while only 20
  commits were genuinely unpushed, because `origin/phase-29-research` exists at `3bea9893`. An
  earlier session-report conflated the two and overstated the backup risk.

## Environment state

- **The Azure staging estate is PARKED** (owner decision, days-long pause). Verified by reading the
  state back, not by the submit rc: `jtoye-staging-aks` **Stopped**, `snackpass-pg` **Stopped**.
  `jtoye-staging-pg` and `jtoye-staging-redis` deliberately left `Ready`.
  **Azure auto-restarts a stopped Flexible Server after 7 days** — `snackpass-pg` needs re-stopping
  on/after **2026-08-21** or it silently resumes billing.
- **Obligation O-1 is HALF answered and the other half is VOID.** All six snackpass container apps
  are `minReplicas=0` (declared side, measured). The cost-meter side failed all five backoff attempts
  on HTTP 429, so there is **no month-to-date figure** — VOID, never "zero". The `minReplicas=0`
  declaration is NOT a substitute: it was true the whole time `snackpass-pg` sat billing as `Ready`.
- Parked worktree `.claude/worktrees/agent-acedd037d6b3648d0` (branch
  `worktree-agent-acedd037d6b3648d0`, tip `459f5d37`, an ancestor of HEAD) — 29-10's executor
  context. **Do NOT delete; do NOT push.** It resumes 29-10 Tasks 2/3.
- Gates: all offline gates green including `check-doc-citations` rc=0. Go clean (`gofmt`, `vet`,
  `build`, `mod tidy` no drift, all 5 packages pass `-race`). `actionlint` clean and shown able to
  fail. `check-infra-exposure` is rc=0 from the MAIN checkout and rc=2 from a worktree — the compose
  project-name trap, not a defect.
- **`shellcheck` is NOT installed and no CI job runs it.** ~39 shell gate scripts have no linting
  anywhere. 29-13's own verify line would abort at rc=127. Recorded as VOID, never as a pass.

## Resume instructions

1. **When the owner returns**, the two parked items are unchanged: add the four A records
   (`api-staging` / `app-staging` / `auth-staging` / `grafana-staging` -> `20.58.10.18`) in the
   Netlify zone whose panel shows the **Zoho MX records**, and fill all seven values in
   `~/.jtoye/staging-operator.env`. Instant proof:
   `dig +short @dns1.p05.nsone.net A api-staging.olajay.co.uk` -> `20.58.10.18`. All four SANs share
   ONE ACME order, so TLS issues for none until every name resolves. Then restart the estate
   (`az aks start`) — restoring a runtime after source changed is a code-changing event, so rebuild
   and verify parity rather than assuming.
2. Then resume 29-10's executor (parked worktree above) for Tasks 2-3, then wave 7 (29-11), then
   wave 8 (29-12/13/14/15), then 29-16 close-out. **29-14 needs no authoring** — its script already
   exists at 349 lines; only its cluster evidence is outstanding.
3. **Owed proofs, explicitly NOT claimed by Lane C:** a real two-arm PITR restore, and a green
   staging deploy run with digest parity. Both need the live cluster.
4. **Lane D is queued and needs nothing from the owner:** DEF-29-6 (`mail.smtp.starttls.enabled` —
   JavaMail reads `starttls.enable`, so STARTTLS has been off in EVERY environment since Phase 22;
   analysed 2026-08-15 as safe to fix, because staging/local/compose all already declare `"false"`
   and the only `"true"` is base/production pointing at AWS SES:587, which REQUIRES STARTTLS — so the
   fix repairs that path rather than breaking it, and `smtp.auth` is `"false"` everywhere so the
   channel is inert), then #587 (webhook 127-second loss window) and #627 (revoked STOMP subscriber,
   same class as the fixed #281 — author and review must be different agents).
5. Changelog: Phase 29's `[Unreleased]` entry is still owed at ship/PR time (heading gets "(#NNN)"
   when the PR exists) — 29-16/gsd-ship owns it. On conflict keep THIS repo's entry; "take theirs"
   silently deletes it and no gate catches that until after merge.

---

**Written 2026-08-11 (housekeeping). Superseded by the 2026-08-15 section above; retained as
history.**

**Written:** 2026-08-11 (housekeeping) · **Branch:** `phase-29-research` (0 behind origin/main) ·
**Authoritative state:** `.planning/STATE.md` (this file is the summary; STATE.md wins on conflict)

Gate expectation for phase close-out (not yet a measurement — several gates need the staging
cluster and can only VOID from this host today):

EXPECT 39 x rc=0 — the repo's gate-script count (H-1's denominator; `check-gate-enforcement.sh`
reports 38 because it counts differently), at 29-16 close-out.
(38 -> 39: quick 260815-00p Lane C added `scripts/check-deploy-digest-parity.sh`, the
Kubernetes half of the runtime-parity doctrine, wired into ci-cd.yaml deploy-staging.
`scripts/staging-pitr-drill.sh` was added in the same lane and deliberately does NOT count:
it is not a `check-*.sh`, so neither this denominator nor `check-gate-enforcement.sh` sees it.)

## Goal and progress

Execute Phase 29 (deployable staging with its own monitoring) — 16 plans in 9 waves.
**9/16 complete + 29-10 partial.** Waves 1–6 merged to `phase-29-research`, every post-merge gate
green (render-invariants INV-1..8, render-golden, gate-enforcement 37 gates, alert-corpus parity,
connection-math, docs-freshness 2812, doc-metrics 37/37, check-claims 43/43).

**Delivered and live** (owner's Azure sub `c483d353`, rg `jtoye-rg`): AKS `jtoye-staging-aks`
(Cilium dataplane+policy), PostgreSQL Flexible Server 16, Azure Managed Redis Balanced_B0 **port
10000**, static ingress IP `20.58.10.18`, CI federated identity; snackpass estate at 0 replicas.
£139.15/mo vs £150 ceiling. Full manifest set merged: Prometheus+exporters, Alertmanager dual-sink,
Grafana, Keycloak+realm, ingress (4 staging hosts), RabbitmqCluster, Mailhog, ClusterIssuers.

## Why it is paused

Wave 7 = 29-11 (first deploy + real vendor login) depends on 29-10, whose Tasks 2–3 are blocked on
two OWNER actions (both parked by explicit owner decision 2026-08-11):

1. **DNS** — the served zone (`dns1-4.p05.nsone.net`) has ZERO A records; the panel being edited is
   not the delegated zone. Find the Netlify zone whose panel shows the **Zoho MX records**, add
   `api-staging` / `app-staging` / `auth-staging` / `grafana-staging` → `20.58.10.18`.
   Instant proof: `dig @dns1.p05.nsone.net A api-staging.olajay.co.uk` → `20.58.10.18`.
   All four SANs share ONE ACME order — TLS issues for none until every name resolves.
2. **Secrets** — fill all seven values in `~/.jtoye/staging-operator.env` (AWS media pair, AWS
   backup pair, `ALERTMANAGER_SMTP_PASSWORD` = 16-char Gmail app password, `_FROM`, `_TO`).
   Also: bank `~/.jtoye/staging-admin.env` (only copy of the staging DB admin credential).

## Failed approaches (do not repeat)

- Owner twice reported DNS "added"; authoritative dig said otherwise both times. Falsified en route:
  doubled-name theory (NXDOMAIN too), stale-delegation theory (all NS1 sets p01–p10 probed, absent
  everywhere). SOA serial static since 2021 ⇒ the zone has never been written. Verify by
  authoritative dig, never by panel screenshots or propagation waits.
- `az account show` unpinned returns the EMPLOYER's subscription (`Prod - HS2 Ltd`) — every az call
  must pin `--subscription c483d353-...`. The only kube context is the employer's `sipbihs2aks`;
  staging kubectl must pass an explicit context.

## Environment state

- Local compose stack: running, 4/4 images FRESH (core-java rebuilt 2026-08-11; 29-02's keys proven
  by content inside the running jar); `NoOrdersCreated` counter reseeded.
- Parked worktree: `.claude/worktrees/agent-acedd037d6b3648d0` on branch
  `worktree-agent-acedd037d6b3648d0` — 29-10's executor context, ALL commits merged (through
  `459f5d37`). Do NOT delete; it resumes Task 2/3. Do NOT push it (temporary agent branch).
- `phase-29-research`: ~75 unpushed commits, secret-path scan clean, 0 behind origin/main.

## Resume instructions

1. When the owner confirms either parked item: resume 29-10's executor (its worktree above) — Task 2
   = secrets preflight → in-cluster role bootstrap (PG firewall admits only the AKS egress IP);
   Task 3 = authoritative DNS verify. Honour **DEF-29-4**: the `rabbitmq-credentials` Secret must
   carry `default_user.conf` AND `username`/`password` (operator projects only the former, core-java
   reads the latter; missing half = ACCESS_REFUSED with all static gates green). Also add the second
   client-secret key 29-08 recorded as needed in `staging-secrets.sh`.
2. Then wave 7: dispatch 29-11 (first deploy + login, checkpoint plan) → wave 8 (29-12 alert
   liveness [needs Gmail secret], 29-13 PITR [needs AWS backup key], 29-14 NetPol enforcement,
   29-15 CI/CD deploy) → wave 9 (29-16 close-out) → phase verification (gsd-verifier), code-review
   gate, roadmap completion.
3. Obligation **O-1** due ~2026-08-12 evening: re-measure the two snackpass idle meters (Cost
   Management, 429-throttles readily — bounded-retry query bodies in the 29-01 session scratchpad);
   if they have not collapsed, scale-to-zero did not work and the estate decision must be revisited.
4. Changelog: Phase 29's `[Unreleased]` entry is owed at ship/PR time per the repo's changelog
   contract (heading gets "(#NNN)" when the PR exists) — 29-16/gsd-ship owns it.

Expected outcomes on resume: secrets preflight prints `populated=7 empty=0`; all four dig queries
answer `20.58.10.18`; then 29-10 completes and wave 7 unblocks.

---

# Handoff: Phase 28 at close-out — Security Triage + the Dev/Prod Boundary (11/11 plans)

**Generated 2026-08-10 by plan 28-11 (phase finisher). This section supersedes the Phase 33 block
below for anything concerning current execution state** — the Phase-33 "Branch `main`, nothing in
flight" note is now history, because Phase 28 is in flight on its own branch. Everything below is
retained as history.

## Resume here

**Branch `phase/28-security-triage`, at phase close-out — NOT yet merged to `main`.** All eleven
Phase 28 plans have SUMMARYs; plan 28-11 (this one) reconciled the test-count manifest, finalised the
triage record, closed #552, refreshed this handoff, and wrote the CHANGELOG entry. Re-run every
figure below before quoting it forward — this document's numbers go stale in exactly this position.

```bash
git checkout phase/28-security-triage && git status --short   # expect clean
git log --oneline -1
```

**What each plan delivered** (one line each; full record in each `28-NN-SUMMARY.md`):

- **28-01** — pentest finding **A1's root cause FALSIFIED** against a stack rebuilt from HEAD (both
  shop-content tables carry a tenant column + ENABLE/FORCE RLS; the real block is the service-layer
  ownership gate, shown able to fail) + the RLS zero-policy catalog sweep with a denominator.
- **28-02** — SEC-03: the OpenAPI document springdoc *serves* omits the dev-only tenant header on
  deployed profiles, proven on the served string with a filter-present control and three red arms.
- **28-03** — media Content-Type census (0 of 768 outside the allowlist, 0 of 37 legacy objects carry
  EXIF) + the permanent `check-media-content-types.sh` gate; anonymous-listing exposure filed.
- **28-04** — the revoked KDS SSE stream now delivers nothing (per-emit shop-grant re-check); the
  STOMP sibling gap filed. Residuals both 5 minutes: the idle SSE socket window, and cross-replica
  revocation latency.
- **28-05** — the tracked pentest triage record (11 findings) + `check-pentest-triage.sh`; the unused
  public realm client removed from the export.
- **28-06** — `auth == null` no longer grants anything: internal trust is an explicit `asSystem`
  declaration with a behavioural guard, proven across a full 1650-test suite.
- **28-07** — the non-owner DML-only `jtoye_runtime` role + FOR-ROLE future-object grants; the live
  `jtoye_backup` default-privileges defect repaired (40 -> 41 readable); Flyway credential decoupled.
- **28-08** — a boot-time ownership fail-fast (the app refuses to start as a table owner) + the
  future-table grant contract; the live stack repointed to `jtoye_runtime`.
- **28-09** — the MinIO bootstrap digest-pinned + a GetObject-only anonymous bucket policy (anonymous
  enumeration closed, anonymous read-by-URL preserved).
- **28-10** — all six confirmed local credentials rotated on the running stack, each proven
  superseded-refused / current-accepted; one Keycloak import carried rotation + the D-12 audience
  removal (11 -> 10 realm clients).
- **28-11** — this plan: `docs/metrics.json` regenerated by script (2769 -> 2807, whole delta Java
  +38 @Test methods across +8 files) with the prose reconciled; the triage B1 row upgraded to FIXED;
  **#552 is CLOSED**; CHANGELOG entry written; this handoff refreshed.

**Gate scripts: 36** (`ls scripts/check-*.sh scripts/docs-freshness.sh | wc -l` — phase 28 added
`check-media-content-types.sh` and `check-pentest-triage.sh`, 34 -> 36; §6's sweep line carries the
matching promise, measured not remembered). On this branch the sweep is 32 clean; the four non-zero
are all accounted for and none is a Phase 28 code defect:

- `check-doc-citations` rc=1 — **DI-28-01**: 6 C-3 line-drift citations in `.planning/codebase`
  docs and `k8s/LOCAL.md` pointing at `docker-compose.full-stack.yml` / `application.yml` lines that
  plans 28-07 and 28-09 shifted. It is wired into CI, so **it must be repaired before the phase PR
  merges** — logged in the phase's `deferred-items.md`. Not this plan's files.
- `check-infra-exposure` rc=1 — the cohabiting FOREIGN `asao-*` compose stack (OlaJay's) on
  `0.0.0.0`; **zero jtoye services fail**. Environmental.
- `check-e2e-skip-budget` rc=2 (VOID) — the documented once-per-merge staleness detector; re-earned
  by an E2E suite re-run (needs the runtime).
- `check-handoff-contract` — green after this refresh (the anchor is `EXPECT 36`, updated here).

**Deferred to the orchestrator (runtime work, per this plan's docs-only scope):** the stack rebuild
+ `check-runtime-freshness.sh` parity read (application.yml read from inside the fat jar),
`check-branch-behind-base.sh`, the full `:core-java:test :core-java:integrationTest` run, and the
Playwright re-run that re-earns `check-e2e-skip-budget`.

**Issue close-out:** **#552 is CLOSED** by this plan (B1 remainder — rotation of six keys + the
runtime/owner role split). #281 (SSE, 28-04) and #283/#284 (asSystem, 28-06) are resolved by shipped
code and close at merge via the PR's `closes` keywords. #629 (jtoye_backup, filed and fixed by 28-07)
plus #627 and #628 stay tracked and carry/close at merge. #626 and #270 were closed by 28-09. #488's
urgent limb is measured-closed and gated; its Core-Web-Vitals residual is deferred to a dated media
backfill plan. **On CHANGELOG merge:** "take theirs" silently deletes this phase's own entry in
`docs/CHANGELOG.md` — resolve it by keeping the Phase 28 entry, then re-run both changelog gates.

**Next roadmap item after Phase 28 merges:** per ROADMAP.md.

---

# Handoff: Phase 33 SHIPPED — 10/10 plans on main, nothing in flight

**Generated 2026-08-10. This section supersedes everything below for anything concerning
Phase 33's execution state.** Everything below is retained as history.

## Resume here

**Branch `main`, clean tree, everything merged — there is no in-flight phase work.**
Phase 33 shipped via PR #620 (plans 33-00..33-07, rebase-merge); the additive postcode-proximity
pair 33-08/33-09 (issue #619) shipped via PR #623 (squash, main commit `8d53a6fc`, 2026-08-09);
UF-33-01 closed via #621; learnings extracted via #622. Issue #619 is CLOSED and the post-merge
main CI/CD run (31337206398) concluded success end-to-end, image builds included.

```bash
git checkout main && git status --short   # expect clean
git log --oneline -1                      # expect 8d53a6fc or a descendant
```

Two decisions travel forward from Phase 33, both recorded, neither to be re-derived:
**D-A is LOCKED interpretation-first** (owner walkthrough verdict 2026-08-09, quoted verbatim in
33-09-SUMMARY.md — a geocodable postcode-shaped `q` is a locality question; do not re-litigate),
and **CUST-02's `MANUAL_REVIEW` adjudicator remains unassigned** (owner-deferred per D-2, carried
to the next phase's decision queue). Next roadmap item, per ROADMAP.md: **Phase 28 (Security
Triage + the Dev/Prod Boundary) — not started, not yet planned.**

## Resume point as of 2026-08-08 (superseded — history only)

**Branch `phase/33-the-consumer-product`** (since merged via #620 and deleted).
No PR — the stacked strategy said nothing merges to `main` until `33-07` lands.

| Plan | Wave | State |
|---|---|---|
| **33-00** | 1 | **COMPLETE 4/4**, SUMMARY written |
| **33-01** | 2 | **COMPLETE 2/2**, SUMMARY written |
| **33-03** | 2 | **COMPLETE 5/5**, SUMMARY written. Task 4's human gate was **approved** by the owner |
| **33-04** | 2 | **COMPLETE 3/3**, SUMMARY written. Task 3's human gate was **approved** by the owner |

**Waves 1, 2 and 3 are done. Next action: execute `33-05`** (wave 4) — geocode on the write path,
range-validate `CreateShopRequest`, seeder coordinates through the SAME geocoder, and the tenant-looped
backfill. After it, 33-06 (wave 5) then 33-07 (wave 6).

| Plan | Wave | State |
|---|---|---|
| **33-02** | 3 | **COMPLETE 3/3**, SUMMARY written |

### What 33-02 leaves you, so 33-05 does not re-derive it

- **The dev database already holds 1,748,230 postcode centroids.** Verified on the delivered runtime,
  not just in tests: Flyway applied V61, the importer loaded the real gzipped artefact in ~18 s, and a
  restart logs *"already holds 1748230 rows … skipping import"*. **No plan needs to re-import.**
- **Read `jtoye.geo.*`, never add to it.** 33-02 owns that block. `coordinate-backfill.enabled` and
  `default-radius-km`/`max-radius-km` already exist for 33-05 and 33-06; both plans share a wave, and
  two of them editing `application.yml` is a merge conflict by design.
- **The API:** `PostcodeGeocoder.locate(String)` → `Optional<PostcodeGeocoder.Coordinate>`, and
  `GeoBounds.boxAround(lat, lon, radiusKm)` → a record with `contains()`. Use the geocoder for the
  seeder too — that is the point of there being one implementation.
- **`SE15 4QA` is not a real postcode** and is the phase's permanent negative control. It is in the
  seeded demo data, satisfies every plausible regex, and `api.postcodes.io` returns 404 for it. The
  real postcode nearest Bellenden Road is **`SE15 4BW`** (51.466812, −0.073164), already in the test
  fixture for whichever plan seeds the replacement.
- **Northern Ireland does not geocode, permanently.** Code-Point Open is GB-only; a NI vendor keeps
  their storefront but is absent from distance-ranked results. Licence containment, recorded in
  `SOURCE.md` — not a bug for 33-05 to fix.

### A trap 33-02 hit three times, which will bite anyone editing a migration

**Flyway substitutes placeholders inside migration SQL — including comments.** A comment naming a
property in dollar-brace form fails the migration with *"No value provided for placeholder"* and takes
the whole application down at startup. It happened once naming the datasource property, and again in
the note explaining the first one, which quoted the syntax it was warning about. Relatedly,
`check-no-create-extension.sh` scans case-insensitively and does **not** skip comments, so a migration
must discuss that statement without spelling it — `V61__postcode_centroid.sql` is the worked example.

### What 33-04 settled, so nobody reopens it

CUST-03 closed on its **recorded-decision limb** (`ADR-0005`), not the populate limb.
`identityProviders` is still empty **by decision**. Google needs HTTPS on a resolving host for any
non-`localhost` redirect URI; `jtoye.co.uk` resolves to Namecheap parking whose HTTPS does not answer.
Groundwork is committed **inert** (`enabled: false`, zero `GOOGLE` vars in `.env`).

Enabling it later needs **all four**, and the first three reach nothing without the fourth: both env
vars, `enabled: true` in the template, the redirect URI registered with Google, and a **realm
replacement** — `--import-realm` skips an existing realm and Keycloak is Postgres-backed, so dropping
`keycloak_data` is a no-op. Procedure is in `infra/keycloak/README.md`.

### Two things 33-07 must consume, already measured

- **The `/` client-JS baseline is 953,353 bytes** (21 scripts). Control, pre-33-03: 945,338 / 20 —
  the +8,015 is `ShopCard`. It lives in `frontend/e2e/perf-budgets.ts` so a test can consume it;
  33-07's declared ceiling must be justified against that number, not against prose.
- **The CLS no-regression assertion is already in place** and will fire if 33-07's client island
  makes the shift worse.

## The delivered runtime — CORRECTED 2026-08-08 during 33-04

**The claim below was true when written and false five minutes later, and this is worth reading
before trusting any parity statement in a handoff.** `scripts/check-runtime-freshness.sh` ran rc=1
during 33-04's close-out, naming **two** stale services:

- `frontend` — image tagged 17:13:09 UTC, but `380ba3b8` (*33-03's own final commit*, 18:18:39 BST)
  touched its build paths afterwards. So 33-03 rebuilt, verified, wrote the claim below, and **then
  committed again** — invalidating its own parity proof at the last step.
- `core-java` — image predates `bfa0836c` (33-01, 17:31 BST).

Neither was caused by 33-04, and that was **measured, not assumed**: `git diff --name-only
51a0c633..HEAD` for 33-04's four commits touches **zero** files under `core-java/` or `frontend/`.
Both services were rebuilt and recreated during 33-04's close-out.

**The lesson is about ordering, not about anyone's diligence.** A runtime-parity proof is only valid
against the commit that was HEAD when it was taken. Take it **last**, after the final commit — or
re-take it. The gate is what caught this; the prose did not.

The original 33-03 claim follows, retained as the record of what was proven at that moment.

### (33-03's claim, superseded above)

The frontend image was rebuilt and the container **force-recreated** — a rebuild that is only
`start`ed still serves the old code. Running container image id `sha256:fcdf723b…` **matches** the
tag's id. Live reads against it:

```
Permissions-Policy: camera=(), microphone=(), geolocation=(self), browsing-topics=()
initial HTML of /   Mama Ade 1 · Peckham Jollof 1 · Brixton Village 1
                    Mama's Kitchen 0 · Spice Route 0 · Olive & Vine 0 · Crumb & Co 0 · Hanoi House 0
```

## Two things the next session must not re-derive

1. **`/` has CLS 0.1793 and it is PRE-EXISTING, not a 33-03 regression.** Established by building the
   pre-change commit `8f6c03b1` and running it simultaneously on `:3001`: control 0.1793 / treatment
   0.1793, identical to four decimal places, one shift each, sources all hero client-island
   hydration. `CLS_BUDGET` was deliberately **not** raised to 0.2 to go green; the spec asserts the
   no-regression form against the recorded value and annotates the unmet absolute target on every
   run. **Fixing it means changing how `HeroSearch` hydrates — outside 33-03's file set. Escalated,
   not absorbed.** Full record in `frontend/e2e/perf-budgets.ts`.
2. **The `/` client-JS baseline is 953,353 bytes across 21 scripts** (control was 945,338 / 20; the
   +8,015 is `ShopCard`). `33-07` must justify its ceiling against that number, which lives in
   `perf-budgets.ts` so a test can consume it rather than only prose.

## Owner decisions already taken (33-00 Task 4, dated 2026-08-08)

`Q-1 = q1-commit` · `Q-2 = q2-param` · `Q-3 = q3-record`. Recorded in `33-CONTROL-ARMS.md`.
A1 licence confirmed: **OGL v3, commercial-permitted, no share-alike**, against OS's own OpenData
page and the National Archives text.

## Corrections to earlier records, found by re-measuring

- **`jtoye.co.uk` RESOLVES** — `162.255.119.30`, Namecheap parking. But `https://` times out and
  `http://` returns a 302 parking redirect, so Google's HTTPS-on-a-resolving-host requirement is
  still unmet. `q3-record` stands, for a different reason than the one written in its option text.
  **Anyone flipping `DEPLOY_*_ENABLED` on a successful `getent` would be acting on a parking page.**
- The Phase 33 plan set is on `main` (PRs #615/#616/#617). The stale `origin/feature/33-the-consumer-product`
  branch is the pre-squash source of #615 and is **not** the execution branch.

## Traps this session hit, so the next one does not

- **A comment naming a forbidden token trips the guard that forbids it.** Writing the client
  directive inside `page.tsx`'s docblock — while explaining why not to add one — turned
  `landing.test.tsx`'s structural guard red. The plan says leave that guard alone, so the prose was
  reworded. A position-aware form (directive must be the FIRST statement) is strictly better and is
  follow-up.
- **`content-length` is absent on Next's script chunks.** A bundle meter reading that header reports
  **zero** and sails under any ceiling. Measure from `res.body()`, and keep a non-vacuity assertion.
- **`gzip` stores the input mtime**, so an identical dataset produces different bytes on every run.
  `gzip -9 -n` — otherwise a ~15 MB artefact lands in git history on every regeneration.
- **A zero-width space (`U+200B`) can be written into a shell script by an edit** and bash will try
  to execute it. `bash -n` catches it; sweep with `grep -c $'\xe2\x80\x8b'`.
- The frontend Dockerfile **refuses to build** without `NEXT_PUBLIC_API_URL` and
  `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` build-args. Pass them when building a control image.

- **A GitHub issue reference matches a hex-colour rule.** `__tests__/palette-discipline` greps
  `components/marketing` for `/#[0-9a-fA-F]{3,8}/`, and `544` is valid hex — so a comment citing the
  issue scores against an expected 0. The convention already existed and is easy to miss:
  `dish-scroller.tsx` writes *"PR 221"* for exactly this reason. Write "issue 544", not the hash.
- **`headers()` throws under jest** ("called outside a request scope"). Any page that reads it for a
  CSP nonce needs `next/headers` mocked in its unit test. `npm run build` rc=0 and 41 green Playwright
  tests did **not** see this — a type-check and an E2E suite cannot see a jest suite that never runs.
- **Validate a test instrument on the CLEAN tree before trusting any break arm it reports.** A local
  prod server on `:3002`, used to avoid container rebuilds, failed the tests on an *unmodified* tree
  (29 bytes served). Run against it, every break arm would have "failed" for the wrong reason and read
  as success.
- **`grep -c` counts LINES**, and a Next.js page is one line — it reports `1` for a string that occurs
  eight times. Count occurrences with `awk`, or the number means something other than what you read.

## Expected-red, by design

From wave 2 until `33-07` Task 4 writes the prose figures, the phase branch is **expected** to be red
on `scripts/check-doc-metrics.sh`. **Do not hand-edit the figures to chase green** — they are wrong
the moment the next plan lands.

Current state, measured: `scripts/docs-freshness.sh` (tree → `metrics.json`) **rc=0**;
`scripts/check-doc-metrics.sh` (prose → `metrics.json`) **rc=1**, on
`AGENTS.md [playwright_specs]: doc says 18, docs/metrics.json says 19`. `docs/metrics.json` was
regenerated **by script**: jest 839→850, jest files 94→95, playwright blocks 80→88, specs 18→19,
total logical invocations **2509 → 2528**.

### The full loop, re-measured at 33-04's close-out: 28 of 31 rc=0

The three that are not green are **all pre-existing and all understood**. Do not chase them blind:

| Gate | rc | Why, and whose tail it is |
|---|---|---|
| `check-doc-metrics` | 1 | The expected-red above. `33-07` Task 4 writes the prose figures. |
| `check-claims` | 1 | Same root cause — README/AGENTS prose vs `metrics.json`. Verified failing **identically** on a worktree of `51a0c633`, so it is not 33-04's. |
| `check-e2e-skip-budget` | 2 (VOID) | The spec-set content hash no longer matches its stored report, because `33-01`/`33-03` added specs. It wants a **suite re-run**, which is those plans' tail — `33-04` touched zero spec files. |

Two were repaired during 33-04 and should stay green: `check-handoff-contract` (the H-1 gate-count
drift above) and `check-runtime-freshness` (both stale services rebuilt and recreated, parity then
proven by content — `application.yml` read from **inside** `/app/app.jar`, 671 lines, matching the
tree, and both containers' image IDs matching their tags).

**One trap for anyone who rebuilds `core-java`:** `check-alert-metrics` will go rc=1 immediately
afterwards. `http_server_requests_seconds_count` is created on the first matching request and
**destroyed on restart**, so the `NoOrdersCreated` rule is genuinely blind until traffic exists. It is
not a regression and needs no investigation — run `bash scripts/seed-order-metric.sh`, which places
one real guest order and waits for the scrape. Done at 33-04's close-out; series 0 → 1.

---

# Handoff: four authors, four checks that could not fail, and none caught by their own author

**Generated:** 2026-08-08. **This section supersedes the 2026-08-02 document below for anything
concerning Phase 33 and the roadmap's success criteria.** Everything below is retained verbatim as
history — its §2.4 (owner-found defects) and §4 (the blocking commercial decisions) are still live.

## Resume here

**Branch `fix/33-plan-check-blockers`, commit `28a3dc60`, clean tree, 0 behind `origin/main`.**

**The independent plan-check returned PASS at `28a3dc60`** — five rounds, on the fifth. Its own
words, kept because a paraphrase would be weaker:

> *After this fix I expect no further findings of this class in the Phase 33 plan set. Every
> `<automated>` limb across all eight plans has now been executed against the tree and its exit code
> recorded … The class that survived four passes did so because nobody was running the limbs; they
> have now been run.*

```bash
git checkout fix/33-plan-check-blockers && git status --short   # expect: clean
git log HEAD..origin/main --oneline                             # expect: empty
```

Then, in order:

1. **Merge strategy: DECIDED 2026-08-08 — ONE STACKED PR for the whole phase.** Every plan commits
   to a single long-lived phase branch; nothing merges to `main` until `33-07` lands.
   `.github/workflows/docs-freshness.yml` runs **both** metric gates — `scripts/docs-freshness.sh`
   at `.github/workflows/docs-freshness.yml:46` and `scripts/check-doc-metrics.sh` at
   `.github/workflows/docs-freshness.yml:59` — on `pull_request` and `push` to main, and Phase 33
   moves the figures those gates read while `33-07` Task 4 owns the prose.

   **So the phase branch is EXPECTED to be red on those two gates from wave 2 until `33-07`
   completes. That is the designed state, not a regression.** Do not open a PR to `main` mid-phase to
   watch it go green, and **do not edit the prose figures to match a half-finished tree** — that
   number is wrong the moment the next plan lands, and it recreates exactly the drift these gates
   exist to catch (README sat at `921` while the tree was at `1895`, green throughout).

   ⚠ **Rebase the phase branch on `main` before `33-07` runs, then re-run
   `scripts/docs-freshness.sh --write`.** A stacked branch accumulates staleness against its base,
   and the counts written into the prose must describe the *merged* tree. If `main` has moved, those
   figures are wrong before they are written.
2. **The four owner gates in `33-00` block Wave 2.** Licence first — see below.
3. Only then start execution at Wave 1 (`33-00`).

## What shipped this session

Four PRs, all **MERGED**: `#613` (nanoid CVE-2026-67213 — it was reddening every PR with no code
change, and `#612` was blocked solely by it), `#612`, `#614` (the criteria-decay audit), `#615`
(Phase 33's plans).

## The finding that changed the plan

`ROADMAP.md` was written 2026-08-01 and `ISSUE-DISPOSITION.md` swept the board 2026-08-07. **Neither
re-measured itself**, and work landed in between. Two success criteria could no longer fail and were
about to be planned as work: Phase 28's SC-4 (all five named infra ports are now loopback-bound; the
three still open are *applications*) and Phase 33's SC-4 / `#458` (nav gating shipped in `#508` and
`#591`; `#458` stays **OPEN** by a deliberate scope split recorded in its own comment).

Full evidence with controls: `.planning/CRITERIA-DECAY-2026-08-08.md`. `ROADMAP.md` carries the
corrections inline at both phases.

**Also still true, and load-bearing for Phase 33:** `#460`'s missing link is *population*, not
reading. `DemoDataSeeder.upsertShop` takes no coordinate parameters and the seeder never sets them,
so every seeded shop has NULL coordinates. A ranking feature over NULL returns nothing before and
nothing after — it cannot be shown to fail. Population is proven first.

## Three product defects found while planning, that no audit here had found

| where | what |
|---|---|
| `frontend/next.config.mjs:35` | `geolocation=()` — an **empty** allowlist, denying the API to the page's own origin on every route, before any prompt. Phase 33's entire located path was dead on arrival and would have presented as a *user denial* |
| `core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java:255` | the seeded Peckham address used `SE15 4QA`, which is **not a real postcode** — absent from Code-Point Open and 404 from ONSPD. The locality fix would have silently deleted a shop from the storefront. Now corrected, and kept as a permanent negative control |
| OS Code-Point Open | 879 rows are Null Island — `positional_quality_indicator = 90` with eastings/northings `0,0`, and **the sentinel is in a different column from the coordinates**. Loaded blind they become the nearest shop to everyone |

## The process finding — read this before writing any check

**Four separate authors each wrote at least one check that could not fail, and not one was caught by
its own author.** The planner, the first plan-checker, the second checker's target set, and me.
Every instance was found by someone who had not written it.

The structural work was right every single time it was examined — zero same-wave collisions across
66 declared file entries, every `depends_on` strictly earlier, no verify reading a later-wave file,
and every factual measurement in the plans reproducing exactly. **The failures were all in the
enforcement layer**, and self-verification never caught one.

Concrete instances, so the shapes are recognisable:

- `grep -c X | grep -q 0` — `grep -c` exits **1** on the desired *absent* state, and under `pipefail`
  the pipeline reports failure when the check should pass. Nine sites, in a plan whose own text
  forbade the shape.
- A verdict piped into `tail` — `f 2>&1 | tail -3` returns **rc=0** without `pipefail` whatever `f`
  decided. Fails **open**. My first measurement of this was itself invalid because I had set
  `pipefail` in the test shell — the entire variable at issue.
- Replacing that fail-open with a command that **cannot run**: `gitleaks` is not installed here
  (`command -v gitleaks` → rc=1; the only one in this repo is the GitHub Action), `gitleaks detect`
  scans git *history* not the working tree, and the limb ran before the file it was meant to scan
  existed. It was also **already satisfied** — widening an allowlist can only make a scanner quieter.
- Asserting a **constant exists** rather than that anything consumes it:
  `export const BUNDLE_CEILING_BYTES = 999999999` with no consumer passes. Written by me, one
  paragraph after I wrote that "a check that cannot fail is a note, not a criterion".
- `grep -c` on a **missing file** yields empty, and `test "" -ge 1` exits **2** (`integer expression
  expected`), not 1 — so it VOIDs rather than fails.
- A criterion asserting a **census** (`total = 5`) where an invariant was meant. It reds on any sixth
  shop, including one an E2E run creates, and a gate that fails on legitimate data gets ignored.

**The rule that actually works: run the fail direction before writing the check down, and have
someone who did not write it confirm.** Nothing else caught any of these.

## Phase 33 — state and shape

Plans at `.planning/phases/33-the-consumer-product/`, `33-00` … `33-07`. **8 plans, 6 waves, 28
tasks**, all `verify.plan-structure` valid with 0 errors. Scope is `#460`, `#544`, `#432` — all
three **OPEN**. Out: `#453`, `#452`, `#545`, `#546`, `#285`, and `#458`'s dispatch half.

Waves: `33-00`=1 · `33-01`/`33-03`/`33-04`=2 · `33-02`=3 · `33-05`=4 · `33-06`=5 · `33-07`=6.

**It was 5 waves until the last pass, and the reason matters.** `33-05` and `33-06` each create a
runtime gate, and `scripts/check-gate-enforcement.sh` is default-deny: every `scripts/check-*.sh`
needs a workflow reference or a `gate-enforcement.conf` entry. They shared wave 4, and `33-06`'s
verify runs the enforcement check — so it would fail on its sibling's script depending on ordering.
**One plan cannot pre-declare the other's exemption**, because the conf VOIDs at **rc=2** on an entry
naming a script that does not exist yet (*"a table that names nothing cannot be trusted to name the
right things"*). Verified directly, with the restore confirmed byte-identical by content. Hence the
extra wave. Do not collapse it back.

### Locked decisions — do not re-litigate

- **D-1** Coordinates derive from postcode via **OS Code-Point Open**. Open data, no API key, no
  sixth commercial dependency. ONSPD was rejected on licence grounds: its NI data carries an
  internal-business-use-only EUL. Accepted limits, stated rather than discovered: **~100 m
  postcode-centroid accuracy** and **Great Britain only** — a Northern Ireland vendor will not
  geocode. Columns already exist (`core-java/src/main/resources/db/migration/V16__public_storefront.sql:15`)
  and are read exactly once as a DTO pass-through
  (`core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java:720`).
- **D-2** `#453` ships **no code**. SC-3's recorded-decision limb is the deliverable. Rejected:
  routing to the tenant's own `GROUP_ADMIN` (the reviewed party becomes the reviewer, degrading
  `BUSINESS_VERIFIED` / `FOOD_HYGIENE_RATING` to self-attestation on a food platform), and a
  cross-tenant operator identity (contra the recorded no-platform-operator constraint).
- **D-3** Scope as above.
- **SEO is IN scope, not N/A.** `frontend/lib/structured-data.ts:256` already exports a tested
  `shopListStructuredData` that `/shop` consumes; `frontend/app/page.tsx` emits none. `33-03` reuses
  it, asserts against raw response bytes (a client-only node passes a DOM query but is invisible to a
  crawler), and its fail direction requires that a well-formed but **empty** `ItemList` still fails.

### Blocked on the owner — four gates in `33-00`, licence first

Ordered deliberately: if the licence is not OGL-compatible the entire coordinate substrate is void
and the rest is moot. ⚠ **The canonical URL printed inside Code-Point Open's own `licence.txt`
returns 404**, so "OGL v3" currently rests on secondary sources and a human must read the live page.
Then: the ~15 MB-per-refresh git cost of committing the derived artefact; the radius shape; and
whether `#432` populates or lands a dated decision.

**One escalation, overrulable:** `frontend/app/page.tsx:51` holds the five invented vendors, and the
row heading is at `frontend/app/page.tsx:180`. The primary CTA and a step body also say "near you".
Both are ruled **out of scope** as aspirational marketing copy rather than claims about the current
result set, with line numbers named so the unsatisfiable blanket assertion is never reached for
again.

### Two requirements deliberately do not close

**CUST-02** — D-2 explains why nothing is built for `#453` but does not name *who adjudicates*
`MANUAL_REVIEW`, which is what the criterion asks. **CUST-04 / SC-6** (`#546`, `#545`, `#285`) was
never measured and is recorded as **unknown, not clean**. Both are stated in the ROADMAP scope note
rather than absorbed. Confirm you accept this before execution, not at close-out.

### Known open, deliberately not self-fixed

`.planning/phases/33-the-consumer-product/33-00-PLAN.md:361`'s break arm says writing a singular `[allowlist]` section makes the plural
`[[allowlists]]` limb exit 1. It would not — adding one does not remove the existing three, so the
grep still returns 3 and passes. Measured: `CONTROL-ARMS` → 0 (live), `[[allowlists]]` → 3 (already
satisfied, so a **regression guard**, not a criterion). Handed to the independent checker rather than
fixed on my own say-so a third time.

### Deliberate red state during the phase

`check-doc-metrics.sh` reads 37 prose claims from README.md, CLAUDE.md and AGENTS.md, while
`docs-freshness.sh --write` regenerates **only** `docs/metrics.json`. This phase moves those figures
(≥4 frontend test files, ≥6 Java, V61 shifting `schema_version` 60→61). `33-07` Task 4 owns the three
docs; the check was dropped from the four earlier verifies because it **cannot** be green mid-phase.
**Do not "fix" it mid-phase by editing prose to match a half-finished tree.**

## Unrelated threads still open

Five dependabot PRs (`#604`–`#608`), red for assorted reasons; `Operational Contracts` fails on three
of them and is worth its own look. `#587` is **OPEN** — outbound webhooks give a receiver 127 s
before an event is permanently lost, one method in shipped code, and it must be shown to fail before
any fix is trusted.

---

# Handoff: the audit that found the bugs is itself invisible to the repo

**Generated:** 2026-08-02 ~20:35 BST. Supersedes #430, which was accurate for the roadmap-blindness
session that produced it. Its §0.1 (two threads with no ticket) and §2.4 (the four blocking
decisions) are **still live** and are carried forward here in §4 — this document does not discard them.

> **§0.1 is the point of this document.** A full QA council ran on 2026-08-02 and found
> **3 Critical / 10 High / 9 Medium / 13 Low** across Phases 22–27. Three findings have been fixed.
> Everything else lives only in `.qa-council/disc-20260802-121732/`, which is **GITIGNORED**. It is now
> all filed (#438–#454), but the *evidence* still exists in one untracked directory. The previous
> handoff's lesson was that a roadmap review cannot see what has no ticket; this is the same lesson one
> layer down — a *repo* review cannot see what is not in the repo.
>
> **§2.4 is the newer and, for the product, larger story.** Twelve findings from the owner **using the
> running app** — filed as #457–#463. Four of them had a *different mechanism* than the symptom
> suggested, and one (#460, no concept of locality) is a missing subsystem the business model already
> assumes. **No audit in this repo found any of them.** Every process here was green while a signed-in
> customer could not tell they were signed in and no order took a payment.

| | |
|---|---|
| `JToye_OaaS_2026` | **2026-08-07 SESSION CLOSE: FIVE merged â #602, #603, #609, #610, #611. `main` at `34b1dcf0`, CI completed/success, gates 29/29 on a plain BARE sweep, runtime 4/4 FRESH, 11/11 healthy.** The ROADMAP named **15 of 57** open issues and read as complete; six had ZERO planning coverage and four of those are P1 filed from USING the app. Every issue now has a home (`.planning/ISSUE-DISPOSITION.md`), Phases **33/34** added. A terminated session's abandoned WhatsApp evaluation was rescued and verified. **Nothing had ever checked a citation in THIS file** â H-5 added, 17 violations found. A bare gate call that exited 2 on a USAGE error was fixed, so Â§6's sweep instruction is now literally executable. #461's decision was NOT outstanding â it was made 2026-08-02 and I misread the issue TITLE instead of its BODY. **Read Â§0.-16 FIRST.** PRIOR â **2026-08-06 LATER: a five-lane supervised batch — #588, #586, #591, #589 merged, #590 carries this update. THREE previously-shipped fixes were found broken while green, including #476's own fix for #282 whose fixture could not see the defect. #587 and #592 filed. Read §0.-13 FIRST.** PRIOR — **2026-08-06: FOUR merged — #573, #574, #583, #584. `main` at `2e9792fb`. #523 is the only open PR again. Two gates were GREEN over instructions that could not be followed; the changelog-citation defect fired a 5th and 6th time and now has a PR-time gate (#583). "Does not close #450" CLOSED #450. Read §0.-12 FIRST.** PRIOR — **2026-08-05 EVENING (updated): the four-lane batch is DONE and merged; `main` is at #577 (`bfdfbdfa`). #572, #575, #577 shipped; #573 and #574 remain open. The changelog-citation defect fired FOUR times — filed as #579. Read §0.-11 and its Addendum.** PRIOR — **a supervised four-lane agent batch. `main` is at #572 (`861591cf`, closes #550). Three issues closed by measurement alone — #487, #205, #104 — and #571 filed from #205's one unmet criterion. #573 and #574 are open. Read §0.-11 FIRST.** PRIOR — **2026-08-05 later: TWO more PRs — #563 (`d36a1865`, closes #561) and #565 (`b0043014`) — plus #564 filed. Read §0.-10 FIRST; it closes §0.-9's open question and supersedes every "flake" reading of #561.** PRIOR SAME DAY — **shipped SIX PRs — #541, #553, #554, #555, #558, #559 — closed FOUR issues (#289, #420, #556, #557) and filed SIX (#548–#552, #556/#557). Read §0.-8 then §0.-7.** Phase 28 opened: #548–#552 filed, #289 closed. PRIOR — **2026-08-04 shipped 12 PRs and closed 1.** The six-lane Wave-1 train (#522 #521 #515 #520 #519 #518), the handoff (#528), the postgres major-parity gate + the first restore drill this repo has run (#529), and three dependabot bumps (#527 #524 #526). **#525 (postgres 15→18 backup image) was CLOSED, not merged** — see §0.-2. HEAD deliberately **not** quoted |
| Open PRs | **5 as of 2026-08-07 evening, ALL dependabot â #604 #605 #606 #607 #608, every one opened 15:25â15:28Z.** â  **#523 is CLOSED** (15:25:36Z), superseded by **#606** which targets node **25**-alpine where #523 wanted **26** â so the "only open PR, on hold all week" reading is DEAD; re-measure before repeating it. The hold REASON is unchanged and still applies to #606: `node-version: '24'` is pinned in **6** places across `ci-cd.yaml` (4), `docs-freshness.yml` and `e2e-nightly.yml`, and `mcp-server/package.json` still declares no `engines`, so a green MCP check is evidence about a version the PR does not change. PRIOR â **1 as of 2026-08-07 housekeeping** — only **#523**, still ON HOLD for the same reason it has been all week (every CI job pins `node-version: 24`, so its green MCP check is evidence about a version the PR does not change). #597 merged as `d13932d6`. PRIOR — **2 as of 2026-08-06 housekeeping** — **#595** (the #593 fix) and #523, still ON HOLD for the same reason all week. #594 merged as `5a548ac8`. ⚠ Both #594 and #595 spent **~2.5 h unmergeable during a GitHub Actions major outage** (declared 15:22 UTC), for no content reason at all — every red check died in `Set up job` or was cancelled with zero steps. See §0.-14 before reading any red on this board as a defect. PRIOR — **2 as of 2026-08-06 later** — #590 (this one) and #523, still ON HOLD for the same reason all week. The five-lane batch opened five and merged four. PRIOR — **1 as of 2026-08-06** — back to just **#523**, still ON HOLD for the same reason it has been all week (every CI job pins `node-version: 24`, so its green MCP check is evidence about a version the PR does not change). #573 and #574 merged, and #583/#584 were opened and merged the same day. PRIOR — **3 as of 2026-08-05 late evening** — #523 (still ON HOLD), #573 and #574; #572/#575/#577 all merged. PRIOR — **3 as of 2026-08-05 evening** — #523 (unchanged, still ON HOLD), plus **#573** (`PUT /shops` publish drop) and **#574** (test-block counter) from the lane batch. PRIOR — **1 as of 2026-08-05 after EIGHT merges** — still only **#523**, which is the same PR it was this morning and is still ON HOLD for the same reason. #563 and #565 merged after the six below. PRIOR reading — **1 after six merges**; #541/#553/#554/#555/#558/#559 all merged. PRIOR reading (**2**) — #523 (dependabot node 24→26, ON HOLD: every CI job pins `node-version: 24`, so its green MCP check is evidence about a version the PR does not change, and `mcp-server/package.json` declares no `engines`) and #530 (a housekeeping doc fix). **Re-measure before trusting this cell** |
| Open issues | **57 as of 2026-08-07 evening**, `--limit 300`, re-measured after three merges. **Flat, and that flatness is the story**: nothing was filed and nothing closed, yet all 57 went from *15 named in ROADMAP* to *57 with an explicit disposition*. A count that does not move can still describe a completely different board. Findings from this session were added as a **comment on #208** rather than new issues. PRIOR â **57 as of 2026-08-07 housekeeping**, `--limit 300`. Down one, and the one is #593 — which closed as a **test** defect, not a product fix (§0.-14). Nothing was filed this session, which is itself worth reading carefully: a session that closes an issue and files none is either a quiet one or one that did not look. This one did not hunt — it fixed two gates it had tripped over. PRIOR — **58 as of 2026-08-06 housekeeping, RE-MEASURED** with `--limit 300` after the #593 work. Still 58: #593 closes with #595, and nothing else moved. A count that does not move across a filed-and-then-fixed defect is the ordinary case — do not read it as a quiet session. PRIOR — **58 as of 2026-08-06 housekeeping** — #593 filed for the KDS duplicate-mute regression the re-earned E2E run found. ⚠ **That framing is wrong — see §0.-14.** PRIOR — **58 as of 2026-08-06 later**, `--limit 300`. Down one net from 59, which hides real churn: #582, #571 and #485 closed by the batch, #516 closes with #590, against #587 (webhook 127s retry window) and #592 (one-click unsubscribe unwired in k8s) filed FROM the batch. Finding defects raises this number and lowers the risk. PRIOR — **59 as of 2026-08-06**, re-measured with `--limit 300`. Down two from 61: #450 closed (see §0.-12 — by a PR body that said it would **not** close it) and #536/#550 already counted, against #582 filed for the test-count-gate deadlock. **Do not read −2 as two problems solved**: one of those closures was accidental and one new defect was filed. PRIOR — **61 as of 2026-08-05 late evening**, re-measured with `--limit 300` — #550 closed by #572, #536 by #577, and #579 filed against the changelog gate, so the count moved by one in each direction and landed where it started. A flat count is not a quiet period. PRIOR — **61 as of 2026-08-05 evening**, measured with `--limit 300`. Down four: #550 closed by #572, and #487/#205/#104 closed by measurement with no code written. #571 was filed against that, so the net is −3 issues for +1 new one. PRIOR — **65 as of 2026-08-05 after eight PRs — and it is EXACTLY the 65 it was before this session's last two merges**, having gone 65 → 64 (#561 closed by #563) → 65 (#564 filed). A second, cleaner instance of the point below: the count did not move and two real things happened. PRIOR reading — **64 after six PRs**, itself the SAME 64 it was mid-session, having moved 60 → 65 → 64 in between. That stability hides real churn: five filed (#548–#552, the pentest disposition), two more filed from the E2E re-run (#556/#557), and four closed (#289, #420, #556, #557). **A flat count is not a quiet day.** Filing findings raises it and lowers the risk; do not read the number as progress in either direction. PRIOR framing — #548–#552 added five while #289 closed one. Filing findings raises the count and lowers the risk; do not read the number as progress in either direction. PRIOR — **62**, measured after the train with `--limit 300` (the default `--limit` is **30** and silently undercounts). 19 issues closed by the six PRs. ⚠ **Five of those did not auto-close**: a PR body reading `Closes #293, #506, #271, ...` only closes **#293** — GitHub's parser consumes the FIRST number in a comma list and ignores the rest. #506/#271/#298 were closed by hand afterwards; **#299 and #303 were deliberately left OPEN** because Lane D only made them *visible* as `OPEN DEFECT` allowlist entries, it did not fix them. #299 is a real production gap: the customer-storefront realm is unconfigured in EVERY k8s environment |
| Issue-count history | It moved in **both** directions across 2026-08-03 (63 → 86 → 92 → 89 → 85 → 80 → **62**) as the council backlog was filed and the trains closed issues, which is why no single figure here is safe to carry. Re-run `gh issue list --state open --limit 300 --json number --jq length` |
| Milestone | **v2.3 is OPEN and spans Phases 21–34** (widened from 21–32 on 2026-08-07 by the all-57 issue triage — Phase **33** The Consumer Product, Phase **34** Rendering + Test Truthfulness; Phase 32 now *depends on* 33). Requirements **46 → 53** across 15 categories (`PAY-04`, `CUST-01..04`, `TRUTH-01/02`). Owner ruling stands — see §4. Do **not** run `/gsd-complete-milestone` |
| Live stack | **Compose UP, 11 jtoye app/backing services, 11/11 healthy** (re-measured 2026-08-07 evening; this counts `docker compose ps` services, NOT the 16-container figure below which included the separate monitoring stack â they measure different things, do not read one as a regression of the other). PRIOR â Compose UP, **16** jtoye containers = 11 full-stack + 5 monitoring; **14 report healthy**. The two without health status define no healthcheck — that is **not** unhealthy. **Infra ports are now loopback-only** (#510): Postgres, Redis, RabbitMQ, MinIO, MailHog, Keycloak, Grafana, Prometheus, Alertmanager and both exporters bind `127.0.0.1`. App-tier ports (core-java 9090, frontend 3000, edge-go 8089, mcp-server 9100) stay on all interfaces as **named, reasoned exemptions** |
| Gates | **29 `check-*.sh` as of 2026-08-07 evening (30 counting `docs-freshness.sh`, which is the figure H-1 asserts against), and the sweep is 29/29 green** â up from 28 because #601 added `check-e2e-typecheck.sh`. â  **The row below saying "STILL 28" was stale within hours of being written.** â  **A BARE SWEEP NOW WORKS FOR ALL 29 â measured 29/29 green with no per-gate special-casing.** `check-test-count-oracle.sh` used to exit **2 (VOID) on a usage error** when called bare, which reads exactly like a real VOID, and that is precisely how it cost time here: it appeared in the sweep beside a genuine `check-alert-metrics` failure and the two had to be separated by hand. A bare call now runs **all three families and aggregates** (a real FAIL outranks a VOID, matching the sibling gates); an *unknown* family such as `jset` still VOIDs, because a typo is not a request to check everything. §6's bare-sweep instruction is therefore literally executable. `check-alert-metrics` fired its documented post-recreate remedy again (`scripts/seed-order-metric.sh` â rc=0). PRIOR â **STILL 28 `check-*.sh` as of 2026-08-07** — `scripts/e2e-spec-digest.sh` is a helper, not an assertion, so it is deliberately NOT named `check-*` and `check-gate-enforcement` still reports 28 (verified rc=0 after the change). ⚠ **The two standing taxes this row has carried for weeks are GONE — see §0.-15.** `check-e2e-skip-budget` no longer VOIDs once per merge (it compares spec CONTENT, not mtime), and `check-runtime-freshness` no longer reports DRIFT for a spec-only commit (`.dockerignore` is now applied where it is unambiguous). The `check-alert-metrics` remedy after a core-java recreate is UNCHANGED and still fires. PRIOR — **28 `check-*.sh` as of 2026-08-06** (29 counting `docs-freshness.sh`, the figure H-1 asserts against §6). #574 added `check-test-block-counter.sh` + `check-test-count-oracle.sh`; #583 added `check-changelog-cites-pr.sh`. **Swept on `main` after #584: 29/29 rc=0** — and that sweep is only meaningful because #584 made it *possible*: two of the 29 could not return 0 from a bare invocation, so the §6 instruction had been unachievable since #574 with **H-1 green the whole time** (see §0.-12). `check-alert-metrics` still fires its documented standing remedy after any core-java recreate (`scripts/seed-order-metric.sh`). PRIOR — **25 `check-*.sh` as of 2026-08-05** (26 counting `docs-freshness.sh`, which is the figure H-1 asserts against the resume block in §6). #553 added `check-gate-enforcement.sh` and wired three gates that ran NOWHERE — see §0.-7; **six of the previous 24 had zero CI references, three of them not deliberately**. Sweep on `main` after #565, runtime re-synced: **26/26 rc=0**, plus 6/6 k8s. **Both standing remedies fired, exactly as this document predicts them to** — `check-alert-metrics` rc=1 after each core-java recreate (`seed-order-metric.sh` → 0), and `check-e2e-skip-budget` rc=2 VOID **once per merge**, because a merge refreshes `frontend/e2e`'s mtime and the gate is a *staleness detector*. Its content was byte-identical both times, verified with `git rev-parse HEAD:<spec>` against the tested blob — and the report was still **re-earned by re-running the suite**, because touching it is fixing the gate rather than the thing. Budget ~6.5 min each time; plan for it after any merge touching a spec. PRIOR — sweep at `7c1ef2a7`: **25/26 rc=0**, the one non-zero being `check-e2e-skip-budget` rc=2 VOID. PRIOR — **24 scripts** (was 22 — #519/#276 adds `check-image-supply-chain.sh` and #337 adds `check-edge-core-contract.sh`; #513 had earlier added `check-e2e-baseurl-contract.sh` and `check-playwright-mobile-contract.sh`). **22 green, 0 fail, 0 VOID**, measured on `main` after #512/#513 with the runtime rebuilt. `check-e2e-skip-budget` is no longer the standing VOID it was — but understand WHAT it is: a **staleness detector**, not a one-time fix. It VOIDs whenever the stored report is older than `frontend/e2e`, which **any checkout or merge touching a spec re-triggers**, so expect it after pulling and re-run the suite (~6 min: `PLAYWRIGHT_JSON_OUTPUT_NAME=e2e-artifacts/report.json npx playwright test --reporter=json,list`). Seed first — `scripts/seed-e2e-fixtures.sh` — or the DRAFT block skips and the budget fails. ⚠ It now sits at **exactly its ceiling of 8**, so the next skip added trips it. ⚠ `check-infra-exposure` **is not wired into CI** — part of it needs a live broker, so it could only ever VOID on a runner, the same reason `check-runtime-freshness` stays out. **Nothing stops someone re-adding `0.0.0.0` in a PR**. `scripts/ci-lane-cost.sh` is deliberately NOT named `check-*` and is NOT in this count: it answers a planning question, not a correctness one |
| Merge-train lesson | **`docs/metrics.json` conflicted three ways on every lane, and NEITHER SIDE WAS EVER RIGHT.** Lane E: ours 2093 / theirs 2106 / truth **2107**. Lane A: ours 2142 / theirs 2107 / truth **2157**. Lane B: 2202. Each lane adds to a different counter (Java / Go / Jest), so "take ours" and "take theirs" are both wrong and the only correct move is `scripts/docs-freshness.sh --write` on the merged tree. The same conflict also carried README's build badge, whose two sides were the **404 repo** and the fix for it — and which side was correct **flipped** between lanes, because the fix landed mid-train |
| Test baseline | **Read `docs/metrics.json`; this cell deliberately quotes no figure.** It moved three times in one day, and nothing gates a number written *here* — `check-doc-metrics` reads only README/CLAUDE/AGENTS, so a count copied into this document rots silently. Regenerate with `scripts/docs-freshness.sh --write`; never hand-arithmetic a delta, because the gate counts literal `@Test` and a renamed or table-driven test makes arithmetic wrong |
| Runtime | **4/4 FRESH on `main` @ `101b12ec`, re-synced 2026-08-07 evening**, proven by identity (running frontend container image ID == the tag just built, `4a3bee52`) and functionally (frontend **200** on `/` and `/shop/brixton-village-grill`; core-java **200** health and **401** on `/api/v1/orders` â auth enforced, not a permissive 200). â  **TWO traps fired here and both are new. (1) A SQUASH-MERGE RE-DATES BUILD INPUTS, so rebuilding on the BRANCH cannot satisfy the gate on `main`** â the branch image was tagged 15:59:51 UTC and the squash commit landed 16:11:25 UTC touching the same path, so `main` went DRIFT immediately despite a green branch. Any PR touching a build input costs a rebuild AFTER merge; do not "prepare" a runtime on a branch. **(2) `sync-runtime.sh` REBUILDS BUT DOES NOT ALWAYS RECREATE** â it rebuilt `frontend` and `core-java` and left BOTH containers on their previous image IDs; the gate correctly said `[container-not-recreated]` and both needed an explicit `docker compose up -d --force-recreate --no-deps <svc>`. A rebuild is not a recreate. PRIOR â **`frontend` was rebuilt TWICE on 2026-08-06 and is left on the CLEAN tree.** Once with a deliberate second mute `<Button>` in `page.tsx`, to prove #593's new assertion can still fail (3/3, `Expected: 1  Received: 2`); once after restoring it. The restore was verified **by content, not by `git diff`** — `git hash-object` == the clean baseline `ed995707…`, and the break-arm marker absent (grep rc=1). The running container's image ID was checked against the tag on **both** rebuilds, so "the container is the tree" is proven by identity rather than assumed. PRIOR — **4/4 FRESH on `main` @ `2e9792fb`, re-synced 2026-08-06** — `core-java` rebuilt and recreated after #573 (the post-merge hook flagged `[image-not-rebuilt]` correctly). **Proven functionally, not by the gate**: `PUT /shops` with `published:true` returned **200** with a body reporting `false` before, and **409 SHOP_PUBLISH_NOT_ACCEPTED** after, with two controls that must NOT change — echoing the current state and omitting the field both stay **200** no-ops. Those controls are the whole proof; without them the fix is indistinguishable from the naive "reject on presence" version that would 409 every ordinary shop edit. Zero audit revisions across five PUTs, with a positive control proving `shops_aud` records MODs at all. PRIOR — **STALE AS WRITTEN — re-measure before trusting. `edge-go` was rebuilt from #572's branch during verification and `prometheus` force-recreated; `main` has since moved. Run `scripts/check-runtime-freshness.sh` from the MAIN checkout.** PRIOR — **4/4 FRESH after #565, re-synced 2026-08-05** — `frontend` rebuilt and **recreated** after each of the two merges. **Proven by content, not by the gate**: the fix's own string (`order-detail reads`) read out of the served `.next` bundle = **2**, a constructed-absent control = **0**, a pre-existing kitchen string = **2**, so the probe discriminates both ways. Note the probe had to be *chosen* — the obvious one is present in both the fixed and broken builds, so proving a break had shipped needed a marker string planted in the toast text (2 with the break, 0 after restore). PRIOR — **4/4 FRESH at `93ad0ab0`, re-synced after #559** — `frontend` and `core-java` both rebuilt and **recreated**. Proven by content from the served build with controls **both ways**: `other shop is` **2**, `other shop are` **0**, `kds-board-shop-loading` **2**, a constructed-absent string **0**. **The zero on the OLD string is the load-bearing row** — a present-new check alone is satisfied by a build containing both. Functional re-check `kitchen-flow` 14/14. PRIOR — **4/4 FRESH at `7c1ef2a7`, re-synced after #554.** `core-java` went `[image-not-rebuilt]` the moment #554 merged (image tagged 00:51:41 vs build inputs at 11:52:31); `sync-runtime.sh` rebuilt **and recreated** it, gate rc=0 after. **Proven by content, not by the gate**: `SHOP_SCOPED_FEATURES` read from inside the running `app.jar` = **1**, negative control `NotARealFieldControl` = **0**, positive control `KITCHEN_FEATURE` = **1**, so the probe discriminates both ways. Functional path re-exercised too, not just the check that motivated the rebuild — health 200, `/shop/brixton-village-grill` 200, `/api/v1/orders` 401. PRIOR — re-synced 2026-08-04 after the Wave-1 train. All four were stale (`rc=1`, each named with its build-input commit); `scripts/sync-runtime.sh` rebuilt and **recreated** them, gate `rc=0` after. Both directions recorded. **Proven by content, not only by the gate:** `TenantCacheEvictor`, `PublicUnsubscribeController` and `OrderStateChangeListener` (all #519) read back from **inside** the running `app.jar` via `unzip -l`, with a `NotARealClassControl` returning **0** so the probe can demonstrably say no; and the frontend's `--primary` was read out of the **served** stylesheet (`/_next/static/chunks/*.css`) as `17.5 88.3% 40.4%` — Lane C's orange-700, matching source, where orange-600 would be `20.5 90.2% 48.2%` |
| E2E | **NIGHTLY GREEN on merged `main` (`510b4da8`): `182 total / 175 passed / 0 failed / 7 skipped`, run 31138225934, 2026-08-07 01:30→01:49 UTC.** This is the authority — full compose stack, both projects, fail-closed. `check-e2e-skip-budget` **PASS**, 7 skips against a budget of 8, so there is **headroom for the first time in weeks** (the row below sat at exactly the ceiling). 175/7 rather than the local 174/8 because the nightly seeds fixtures, so a locally-skipped test executes: *"vendor-refund-flow's DRAFT test and storefront-flows' STFR-06 can now assert non-vacuously."* ⚠ **This does not by itself prove the #593 fix** — the same nightly was green on `4eda1aa4` while the ~30% flake was live; a single nightly can clear a 7-in-10 race. The proof of that fix remains its break arm (3/3 failing with a genuinely duplicated button, 12/12 passing restored). ⚠ **The standing "the nightly has not run since `d4930719`" claim below was STALE when written** — it had run 2026-08-06 03:01 on `4eda1aa4` and passed. PRIOR — **GREEN: `174 passed / 8 skipped / 0 failed / 0 flaky` of 182 (383 s), 2026-08-06, on the #593 fix branch** — the same baseline recorded after #565. `check-e2e-skip-budget` **rc=0**, earned against a report regenerated AFTER the last spec edit; the gate VOIDs on a report older than `frontend/e2e`, so an older one cannot certify it. ⚠ **The RED below was a TEST defect, not a product one. Read §0.-14 before acting on it.** There are not two mute buttons: `getByTitle` was matching React's streaming staging buffer (`<div hidden id="S:0">`), which holds a second copy of the ENTIRE dashboard shell for ~300 ms. It is a **race — 7 fails in 10 runs**, not deterministic, and `getByRole` never saw it because `[hidden]` is not in the accessibility tree. PRIOR, kept because it is how #593 was found — **RED on `main` @ `1c796d42`: `172 passed / 8 skipped / 2 FAILED of 182` (6.4 min), 2026-08-06.** Both failures are `frontend/e2e/kitchen-flow.spec.ts:243` (mobile + desktop) — **two identical "Mute alerts" buttons in the DOM**, a strict-mode violation and the #556 hydration-duplication class that #577 reported removing. Filed as **#593**. Run against `:3000`, the container rebuilt at 14:30:16 UTC and gate-confirmed FRESH, so this is the CURRENT build and not a stale artifact. Regression window is #565..now — the previous full run was 0 failed and **#577 restructured that exact header** — but that is a window, NOT a bisect. ⚠ **Do not "fix" it with `.first()`**: the same test already does that for the shop selector, which is precisely why nobody noticed the button had the same defect. Assert `toHaveCount(1)` and show it failing first. `check-e2e-skip-budget` stays VOID until a GREEN report is earned; it has deliberately NOT been re-run to green. PRIOR — **Local full suite on `main` after #565: `174 passed / 8 skipped / 0 failed of 182` (6.6 min), 0 failed.** 182 not 180 because #563 added one `test()` block × 2 projects. Skips sit at **exactly** the declared ceiling of 8, so the next one added trips the gate. ⚠ **The NIGHTLY has still not run since `d4930719`** — the authority is stale even though the local number is green; **re-dispatch it**. ⚠ **Do not verify the KDS feed area with `frontend/e2e/kitchen-flow.spec.ts:339`** — it is budget-dependent (38 × 200 in one run, 28 × 200 + 10 × 429 in another, identical request pattern), and that dependency is what made #561 read as a flake for two sessions; `:406` injects the condition instead. PRIOR — **Nightly (the authority) GREEN: `180 / 173 passed / 0 failed / 7 skipped`** on `d4930719`, twice (§0.-6). `kitchen-flow.spec.ts` is **14/14 locally** against the rebuilt stack after #558/#559 (§0.-8), but that is one spec, not the suite — **re-dispatch the nightly to get a current whole-suite number.** `check-e2e-skip-budget` is rc=0 at **exactly its ceiling of 8**, so the next skip added trips it. HISTORY, kept because it is how #556/#557 were found — **local re-run at `7c1ef2a7`: `169 passed / 3 failed / 8 skipped` in 6.7m.** The 3 failures were all `kitchen-flow.spec.ts`, **NOT #554** — that PR changed **0 frontend files** (`git show --name-only 7c1ef2a7`) and `/dashboard/kitchen` is `"use client"`, so a Java STOMP change cannot cause a DOM strict-mode violation. **Re-running the spec alone gave `13 passed / 1 failed`.** ⚠ **I read that as "2 of the 3 were flakes" and it was WRONG — `:339 [mobile]` now fails 2/2 in the full suite and passes 2/2 in isolation; see §0.-9 and #561.** What was true is that only `[desktop] :455` is deterministic in BOTH contexts — filed as **#556**: `KdsBoardShopName` renders at page.tsx **:546** (loading branch) *and* **:575** (loaded), both emitting the same `data-testid`, so React's hydration swap transiently puts two in the DOM and strict mode resolves the stale one reading *"No shop selected"*. Same mechanism as #540, the class #542 tracks. ⚠ **The full suite and the single spec disagree — measure both before believing either.** STALE 2026-08-04 LOCAL ROW FOLLOWS — **127 passed / 8 skipped / 0 failed of 135** — a LOCAL run of the spec files, NOT the nightly (which runs both projects: 180 instances, see §0.-5) —, run against the re-synced stack, `check-e2e-skip-budget` **rc=0** at exactly its ceiling of 8. ⚠ **The first run of this suite reported 48 skipped / 21 undeclared and that figure was an INSTRUMENT ARTEFACT, not a finding** — the suite was launched without sourcing `.env`, so 26 vendor-authenticated specs self-skipped on "No vendor password". `set -a; . ./.env; set +a` first, and export `E2E_VENDOR_PASSWORD` from `KC_SEED_USER_PASSWORD`. A skip count is meaningless unless the credentials were present |

> ⚠ **A second session drives this same checkout.** Not a worktree — the same working tree. A `git
> checkout` here moves *their* HEAD, and `main` moved four times while this document was being written.
> **Re-measure every number below before repeating it**; §2.4's first entry is what happens when you
> don't.

> **Why no HEAD SHAs.** A document quoting its own repo's HEAD is stale the moment it merges.
> §6 pairs every fact with the command that produces it: **run them, don't read them.**

---

## 0. ⚠ READ FIRST

### 0.-16 the roadmap named 15 of 57 open issues and read as complete (2026-08-07 evening, latest)

**FIVE merged**: **#602** (all-57 issue triage), **#603** (a terminated session's rescued WhatsApp
evaluation), **#609** (a `.dockerignore` hole), **#610** (H-5 — nothing had ever checked a citation
in *this* file), **#611** (a bare gate invocation that exited 2 on a usage error).

**Closing state, all re-measured at session end and not carried forward:**

| | |
|---|---|
| `main` | `34b1dcf0` |
| CI on `main` | **`completed / success`** — 12 jobs success, 3 skipped, 0 failed. The `Build and Push Images (core-java)` job is the ~22-minute tail on every merge; it is not a hang |
| Gates | **29/29 green on a plain BARE sweep**, 0 fail 0 VOID — no per-gate special-casing, which #611 is what made possible |
| Runtime | **4/4 FRESH**, 11 compose services, **11/11 healthy** |
| Open issues | **57**, every one with a disposition |
| Open PRs | **5**, all dependabot (#604–#608) |
| Working tree | clean on `main`, no stray local or remote branches |

**Two things this session added that did not exist before it:**

- **H-5 in `scripts/check-handoff-contract.sh`** (#610) — every backticked `path:line` citation in
  this document must name a path that **exists**. `HANDOFF.md` was outside
  `check-doc-citations.sh`'s `DEFAULT_DOCS` and `.github/workflows/ci-cd.yaml:675` invokes that gate **bare**, so no
  citation here had **ever** been checked by anything. First run: **17 violations**. H-5 asserts
  path existence only — deliberately **not** line content, because 6 of the 8 residual line-drift
  citations sit in HISTORICAL sections and gating those makes a required check permanently red over
  a document that is telling the truth about a past tree. Proven able to fail, and its self-test
  proven able to VOID.
  **It then caught its own author, one day old, on live traffic:** writing *this* section cited the
  CI workflow by bare filename instead of its repo-relative path, and H-5 failed the commit — the
  exact defect class it was built for, reintroduced by the person who had just spent an afternoon
  removing 15 of them. Strong evidence it earns its place, and a reminder that the habit does not
  stick just because the finding was recent.
  **Then it fired a second time on the sentence above**, because the first draft of it *quoted* the
  offending citation verbatim — so the note describing the defect committed the defect. That is this
  repo's recorded shape *"a doc rule that must name the string it forbids fires on its own
  definition"*, and it is why the sentence now describes the citation instead of reproducing it. The
  same trap took two attempts to clear earlier the same day on `.planning/ISSUE-DISPOSITION.md`'s
  control token. **A verification example and the material it verifies must not share a namespace.**
- **A bare `check-test-count-oracle.sh` now checks all three families** (#611) instead of exiting
  **2 (VOID) on a usage error** — an exit code indistinguishable from a real inability to verify.
  An *unknown* family (`jset`) still VOIDs: a typo is not a request to check everything.

**The trigger was the owner, not a gate.** He said: *"there are still over 50 issues open. and
you're pushing for phase 28."* It measured out worse than the objection:

```
open issues                                   57
  named anywhere in .planning/ROADMAP.md      15
  not named                                   42
```

Six of the 42 appeared in **zero** files anywhere under `.planning/` — **#453 #460 #461 #544 #462
#507**. Four are P1 and **all four were filed from the owner using the running application**. The
highest-signal source on the board had the least planning coverage while the roadmap read as a
finished go-to-market plan. Phase 28 was never the wrong *next* phase — its scope IS backlog (9
issues) — but `STATE.md`'s "Next: Phase 28" implied 28 was the board.

`.planning/ISSUE-DISPOSITION.md` now gives every open issue exactly one home. Re-run its own
coverage check before quoting any figure from it; `gh issue list --limit` defaults to **30**.

#### The thing to actually learn from this session: I classified an issue from its TITLE

I reported **#461** as *"needs a product decision before it can be planned."* **Wrong.** The decision
was made **2026-08-02** and is quoted verbatim *in the issue body*: the payment request goes to the
buyer's **verified telephone number**, or the social channel they engaged on; pay-on-collection is
not permitted. I read the title and never opened the body — **the same failure the sweep exists to
fix, one layer in.** It rescued six issues the roadmap could not see, then mis-read one of the six.

Measuring what *"verified telephone number"* requires produced the bigger finding:
`Customer.phone` is `@Column(length = 50)`, **optional**, free text; **nothing verifies a phone** —
no `phone_verified` column in any of the 60 migrations, no OTP, no flow — while `emailVerified`
resolves to 4 files including `CustomerJwtVerifier`. **The platform verifies email and does not
verify phone, and the design routes on phone.** So **#462 moved Phase 33 → Phase 30**, **#208 became
a CRITICAL-PATH deferral** (the WhatsApp Business API account is the delivery mechanism for a P1 —
effectively a **fifth** commercial blocker beside domain / hosting / Stripe keys / ADR-0002), and
**PAY-04** was added. `PublicStorefrontService:508-521` deliberately falls back to cash-on-delivery
when no provider is configured — that fallback is what makes the policy violable today.

**Only #453 now awaits a product decision** (who adjudicates onboarding `MANUAL_REVIEW`, given there
is no cross-tenant operator identity).

#### A terminated session's work was abandoned mid-flight and had to be rescued

Another session died leaving **two unpushed commits stranded on the branch of an unrelated PR**. Its
WhatsApp evaluation was good and landed unchanged (#603) with a separate verification addendum.
Everything in it verified. Four things it lacked — and one is a pattern, not a detail:

**The missing Meta `GET` handshake is a RE-DISCOVERY.** Unfixed for three months, first recorded
2026-04-27 warning `accept re-registration breaks` (`docs/audit/remediation/07-edge-absorb-remediation.md:146`).
Four lines below it the same document says `no need to change` (`docs/audit/remediation/07-edge-absorb-remediation.md:150`),
on the stated grounds that Meta already has the webhook registered — which **cannot be true**, since
registration requires the handshake this repo has never been able to answer. **A false reassurance
sat four lines under the finding and is the likeliest reason nobody actioned it.** Now filed as a
comment on **#208**.

Also: finding 5 was understated — **four** always-200 paths, and the two that are unambiguously
infrastructure are not the one cited. Each logs on one line and answers 200 on the next:
`Failed to acquire service token for WhatsApp order` (`edge-go/cmd/edge/handlers.go:305`) and
`Failed to create order from WhatsApp` (`edge-go/cmd/edge/handlers.go:376`). Finding 8 is **6**
occurrences not 1, three of them in the *generated* API artifacts.

#### Three traps fired, all mine, all caught by a closing arm rather than a break arm

1. **`git checkout --` deleted an entire uncommitted addendum.** I ran a break arm on an
   **uncommitted** tree; the restore reverted to the index and wiped 106 lines of new work rather
   than undoing the break. The **closing clean arm** caught it — 34 citations where there should have
   been 51. The break arm looked perfect both times. *Commit before running arms.*
2. **A `pgrep` wait-loop self-matched** and reported "STILL RUNNING at deadline" for a build that had
   finished 8 minutes earlier. Bracket self-exclusion was **not** sufficient — the pattern reappeared
   in the polling shell's own command line. Only the deadline terminated it.
3. **`nohup … &` inside a backgrounded task returns exit 0 immediately** for the *launcher*, so the
   completion notification arrived while the Docker build was still running. Caught only because the
   log ended mid-`next build`.

#### Runtime facts that will cost the next session time if unknown

- **A squash-merge re-dates build inputs.** Rebuilding on the branch cannot satisfy the gate on
  `main`: the branch image was tagged 15:59:51 UTC, the squash commit landed 16:11:25 UTC touching
  the same path, and `main` went DRIFT immediately despite a green branch. **Any PR touching a build
  input costs a rebuild AFTER merge.**
- **`sync-runtime.sh` rebuilds but does not always recreate.** It left both `frontend` and
  `core-java` on their previous image IDs; the gate correctly said `[container-not-recreated]` and
  both needed `docker compose up -d --force-recreate --no-deps <svc>`.
- ~~**`check-test-count-oracle.sh` requires a mode argument.**~~ **FIXED the same evening.** A bare
  call used to exit **2 (VOID) on a usage error**, indistinguishable from a real VOID, which is
  exactly how it cost time here — it appeared in the sweep beside a genuine `check-alert-metrics`
  failure and the two had to be separated by hand. A bare call now runs **all three families and
  aggregates** (FAIL 1 outranks VOID 2, matching the sibling gates); an *unknown* family such as
  `jset` still VOIDs, because a typo is not a request to check everything. **§6's bare sweep is now
  literally executable: 29/29 green with no per-gate special-casing.**
- **`#523` is CLOSED**, superseded by **#606** targeting node **25**-alpine where #523 wanted **26**.
  The hold reason is unchanged: `node-version: '24'` is pinned in **6** places and
  `mcp-server/package.json` declares no `engines`.

#### This document is not citation-checked, and it shows

`HANDOFF.md` is **not** in `check-doc-citations.sh`'s `DEFAULT_DOCS` (which reaches `CLAUDE.md`,
`AGENTS.md`, three `.planning/codebase/*`, `k8s/DEPLOYMENT.md` and `docs/ops/terminal-states.yaml`).
Run on demand for the first time on 2026-08-07 it reports **17 violations** — all pre-existing, none
from §0.-16, which was checked and cleaned before merge:

```
CITATION_DOCS=HANDOFF.md scripts/check-doc-citations.sh
```

Almost every one is the same shape: a **bare filename used as if it were a path** —
`kitchen-flow.spec.ts`, `stomp-relay.spec.ts`, `OpenApiConfig.java`, `SyncService.java`,
`customer-auth.ts`, `k8s-backup.sh`, `public-origin.ts`, `prometheus.yml.tmpl`. The gate wants
`frontend/e2e/kitchen-flow.spec.ts`. Nothing is *wrong* in the prose; the citations are simply
unfollowable, which is the specific thing the gate exists to prevent. Fixing them is a contained
piece of work nobody has scoped. **Note the same trap that produced two of them in §0.-16: the gate
is LINE-oriented, so a backticked token must sit on the same source line as the citation it
justifies — prose wrapping alone turns a good citation UNCHECKABLE.**

**RESIDUE:** `scripts/seed-order-metric.sh` wrote a real order row into the dev DB
(`ORD-00000000-20260807-51229A3D`) to clear `check-alert-metrics` after the core-java recreate. That
is the documented remedy and its documented cost.

#### Resume here — commands with their expected answers

Run these before trusting anything above. Each states what a correct tree returns, so a different
answer is a finding rather than a puzzle.

```bash
# 1. State. Nothing here was carried forward; all of it was measured at session end.
git -C . log origin/main --oneline -1        # expect 34b1dcf0  (or later — then re-measure everything)
gh issue list --state open --limit 300 --json number --jq length   # expect 57   (--limit defaults to 30 and undercounts)
gh pr list  --state open --limit 100 --json number --jq length     # expect 5, ALL dependabot

# 2. Gates. A BARE sweep now works for all 29 — that was #611's whole point.
for g in scripts/check-*.sh; do bash "$g" >/dev/null 2>&1 || echo "rc=$? $g"; done
#   expect NO output. If check-alert-metrics is the only rc=1 after a core-java
#   recreate, that is its documented standing remedy: bash scripts/seed-order-metric.sh

# 3. Runtime. A rebuild is NOT a recreate — see the Runtime row.
scripts/check-runtime-freshness.sh           # expect rc=0, 4/4 FRESH
```

**Where to pick up work.** Not Phase 28 by reflex — read `.planning/ISSUE-DISPOSITION.md` first; it
gives all 57 issues a home and is the thing this session existed to produce. The three highest-value
threads it exposes:

1. **#461 needs no decision, it needs four dependencies** — capture → **verify** → channel → Stripe
   keys. The second does not exist: nothing verifies a phone number anywhere in the 60 migrations.
   That is #462, which moved into Phase 30 for exactly this reason.
2. **#208 is now a critical-path deferral**, not an optional AI feature. It is the delivery channel
   for #461's payment request, so a WhatsApp Business API account is effectively a **fifth**
   commercial blocker beside domain / hosting / Stripe keys / ADR-0002. The findings are filed as a
   comment on the issue.
3. **#453 is the only issue awaiting a product decision** — who adjudicates onboarding
   `MANUAL_REVIEW` when there is no cross-tenant operator identity.

**Two cheap wins left on the floor**, both already measured and neither actioned: **#286** should be
*narrowed*, not closed (its `/dashboard/staff` half already runs live; only 390×844-vs-375px and 9
route stubs remain), and **#110** should be narrowed to coverage (its "Playwright runs in CI" half is
met by the nightly). Closing either whole would discard a real remainder.

**Known, recorded, deliberately ungated:** 8 line-drift citations in this file (`C-3` class — the
path resolves, the line moved). H-5 does not check line content and the reasoning is above. Run
`CITATION_DOCS=HANDOFF.md scripts/check-doc-citations.sh` to see them; do **not** "fix" them by
weakening H-5.

### 0.-15 two gates were asking a cheaper question than the one they advertised (2026-08-07)

Both taxed every merge. Neither was wrong about its *purpose*; each measured a proxy, and
the gap only ever showed up as recurring cost, which is why it survived so long.

| gate | advertised question | question it actually asked | cost |
|---|---|---|---|
| `check-e2e-skip-budget` | does this report describe the specs on disk? | were the specs *written* after the report? (**mtime**) | VOID after every merge touching a spec; ~6.5 min re-run to clear |
| `check-runtime-freshness` | can the running image differ from the tree? | did any file in the build context change? | DRIFT + a prescribed rebuild for a commit that cannot change the artifact |

**The skip-budget gate now compares CONTENT.** `scripts/e2e-spec-digest.sh` hashes
`<relpath>\t<git hash-object>` over `frontend/e2e/**` + `playwright.config.ts`;
`playwright.config.ts` stamps it into `config.metadata.specDigest` at run time so every
producer records it with no extra step; the gate recomputes and compares. Absent, sentinel
or mismatched ⇒ **VOID** — it cannot be satisfied by omitting the field it checks. The
decisive arm, on one tree with every spec `touch`ed and bytes provably unchanged:

```
OLD (mtime)   would VOID: 'report is OLDER than frontend/playwright.config.ts'
NEW (content) PASS: all 8 skip(s) are declared and within the budget of 8.   rc=0
```

**The good that VOID was accidentally doing is preserved, not dropped.** §0.-13 below says
it plainly — *"a gate that VOIDs 'once per merge' is not noise — it is the only thing asking
for the run that finds this class"* — and it is right: that nag is how #593 surfaced. The two
questions it conflated are now separate. The digest answers *is the skip set valid* (VOID if
not). A new **non-failing ADVISORY** answers *is the suite result current*, by comparing the
report's own `stats.startTime` against commits touching `frontend/` outside the spec set. So
you still get told to re-run — for the true reason, without a red gate that is wrong.

**`--from-nightly`** pulls the last successful nightly's report instead of re-running 20
minutes locally. **Not a bypass**: the download faces the same digest check, so a nightly
that ran on a different tree VOIDs. Verified — it currently refuses, because the nightly
predates the digest contract. It starts working after the first nightly on merged `main`.

**The runtime gate now applies `.dockerignore`, conservatively.** Its header used to refuse
outright, reasoning that translating ignore patterns "risks excluding MORE than intended, and
an over-broad exclusion is a FALSE NEGATIVE". That reasoning stands and is now the design
*constraint*: any `!` re-include voids the whole file, and any glob, `..` or the Dockerfile
itself is skipped **and printed**. Every refusal falls back to the old over-reporting, so the
failure direction is unchanged. `frontend/.dockerignore` now excludes `e2e/` and
`playwright.config.ts`.

⚠ **Two claims made during this work were falsified by measuring them, and both would have
been believable.**

- *"Ignoring `e2e/` moves its type-checking to CI's `npm run build`."* **False.** Planting
  `const broken: number = "..."` in a spec: `npm run build` → **rc=0, not mentioned**;
  `npx tsc --noEmit` → **rc=2, TS2322**. `next build` checks the pages/app graph, not the
  whole tsconfig program — so the Docker build never checked these specs either. Nothing is
  lost, but because the coverage *never existed by that path*. The gap is pre-existing; do
  not "restore" it.
- *The first break arms asserted FRESH/DRIFT and were **vacuous**.* This branch's own commits
  touch `.dockerignore`, a genuine build input, so the frontend drifts regardless of the arm —
  every arm would have "passed". The discriminating signal is **which commit the gate names**:
  e2e-only ⇒ stays `872bf9b3`; `frontend/app` ⇒ advances to `a09a9036`; `!` present ⇒ advances
  to `3ac60b06`.

And one defect found in the change by its own output: `dockerignore_excludes()` set a global
read after `mapfile -t x < <(fn …)`. Process substitution runs the function in a **subshell**,
so no refusal ever reached the parent — the gate printed a clean run precisely because the part
that reports doubt could not speak. Caught only by noticing `frontend/.dockerignore` has globs
yet printed no refusal. Notes now travel in the return stream, which makes it unrepresentable.

### 0.-14 #593 was not a product defect — the locator was reading React's staging buffer (2026-08-06)

§0.-13 filed **#593** as "two identical Mute alerts buttons on a control the kitchen relies on" and
sent the next reader to *find the second mount*. There is no second mount. The KDS renders one mute
button, correctly wired, announced once, and **`page.tsx` was not changed**. #595 fixes the test.

This section supersedes §0.-13's #593 block rather than appending to it, because acting on that
block's diagnosis means hunting a defect that does not exist.

#### The mechanism, measured

Next streams this route's Suspense boundary: the resolved markup is parked in a
**`<div hidden id="S:0">`** appended to `<body>` and spliced into the boundary by the `$RC`/`$RV`
bootstrap on a **~300 ms reveal throttle**. Between hydration finishing and that throttle firing,
the **entire dashboard shell is in the document twice** — once live, once inside `[hidden]`.

At one instant inside that window, on the live compose stack:

```
getByTitle(/Mute alerts|Unmute alerts/)            -> 2
getByRole("button", {name: /Mute alerts|Unmute…/}) -> 1
document.querySelectorAll("h1")                    -> 2
second button .offsetParent                        -> null
ancestor path -> body > div#S:0[hidden] > div > main > … > button
```

500 ms later: 1 button, 1 `h1`, no `S:0`, `$RB.length === 0`. It clears itself.

**Role locators are immune by construction** — they read the accessibility tree, which excludes
`[hidden]`. Raw attribute locators (`getByTitle`, `getByTestId`) are not.

#### Three claims in §0.-13 that the evidence contradicts

- **"It is NOT a whole-page double mount — if it were, the heading would also resolve to 2."** The
  heading *is* duplicated; there really are two `<h1>`s in that window. The heading assertion passed
  because it uses `getByRole`. That asymmetry — not a partial mount — is the entire phenomenon.
- **"A screen reader announces the mute toggle twice… only one of them is wired to the state the
  other displays."** Neither holds. The staged copy is `display:none` and absent from the a11y tree;
  the failing run's own ARIA snapshot lists exactly **one** `button "Mute alerts"`.
- **"Deterministically, on both projects."** It is a **race**: **7 failures in 10 runs** of the same
  test (`--repeat-each=10`, desktop). A warm single-test run usually wins it, a loaded whole-suite
  run usually loses it. The first re-run passing is what exposed the real cause — treating that pass
  as "fixed itself" would have buried it again.

#### The repo had already solved this, and this spec was the holdout

`frontend/e2e/dashboard-mobile.spec.ts:44-70` documents the identical mechanism, the identical measurement
(`byTestId=2` but `byRole=1`), the identical *"deliberately NOT fixed with `.first()`"* warning and
the identical remedy — dated **2026-07-31**. It also records the trap: *"Fixing only the tab-bar
locator moved the failure rather than removing it."*

`kitchen-flow.spec.ts` adopted none of it. **That** is the defect #595 closes: a known hazard with a
known remedy, applied in one spec and not its sibling. All 12 of that spec's remaining raw test-id
locators are now scoped through the same `LIVE = "body > div:not([hidden])"` root the siblings use.
Several were latently flaky the same way — including `kds-board-shop` `toHaveCount(1)`, which reads
**2** inside the window and would have been filed as **#556 recurring**, exactly as #593 was.

#### §0.-13's `.first()` lesson was right, and its example was wrong in a more useful way

§0.-13 said the shop-selector `.first()` was a duplicate-suppressor and that this is why the
button's duplication survived. The conclusion stands — `.first()` does convert a defect into a
permanently invisible one — but that `.first()` was doing something worse: **selecting the wrong
control entirely.**

```
getByRole("combobox")   -> 2  ["shop-context-select-sidebar", "Kitchen display shop"]
.first().textContent()  -> "All shopsUnsorted legacy itemsBrixton Village Grill…"
```

That is the dashboard-wide switcher in the **sidebar**, whose option list happens to contain "Test
Shop" and so satisfied `/Test Shop/i` by accident. The comment above it asserted *"there is exactly
one Select on this page"* — false — so the block had **never asserted anything about the kitchen
board's own selector**. A workaround that quietly rebinds an assertion to a different element is a
sharper version of the same lesson, and it survived a code review that wrote a paragraph defending it.

#### Fail direction, both ways — including a real rebuild

| arm | result |
|---|---|
| clean tree, **old** `getByTitle(...).toBeVisible()` | **7 fail / 10** |
| clean tree, **new** `getByRole(...).toHaveCount(1)` | 10 pass / 10 |
| **broken tree** — a second mute `<Button>` added to `page.tsx`, frontend image rebuilt, running image ID verified against the tag | **3 fail / 3**, `Expected: 1  Received: 2` |
| restored (`git hash-object` == clean baseline `ed995707`), rebuilt, re-run | 12 pass / 12, both projects |

The break arm is the load-bearing row: `toHaveCount(1)` on a role locator **still fails on a genuine
pair**, so the fix is not the old assertion made quiet. `.first()` would have gone green there.

The `LIVE` scope was proven the same way rather than assumed — against a reconstructed staging
buffer (a `[hidden]` clone of the shell appended to `<body>`):

```
clean    raw=1  scoped=1  rawH1=1
staged   raw=2  scoped=1  rawH1=2  roleHeading=1  rawTitle=2  roleButton=1
```

The raw query demonstrably doubles, so the guard's input is genuinely broken; the scope and the role
locators both hold at 1.

#### The class is NARROWER than it looks — measured, so nobody repeats the sweep

The obvious next move after #595 is to sweep every spec using a raw test-id locator. **Don't** —
it was measured and the answer is no. The staging buffer is emitted by the server's **streaming
HTML response**, so it exists only for a **full document load**, and only until the reveal throttle
fires. An App Router **client-side navigation fetches an RSC payload, not a new document**, and
produces none:

```
FULL LOAD  /dashboard/webhooks : {"stagingDivs":0,…}   <- warm run, window already missed; NOT evidence
after settle on /dashboard     : {"stagingDivs":0,"h1":1}
CLIENT NAV -> /dashboard/kitchen: {"stagingDivs":0,"h1":1}
  +50ms / +150ms / +300ms / +600ms : all {"stagingDivs":0,"h1":1}
```

The load-bearing rows are the client-nav ones — five samples across ~1.1 s, zero buffers. (The
first row is *not* evidence of anything: a warm full load usually misses the window, which is the
same reason #593 read as a flake.)

So the two remaining raw-attribute sites on dashboard routes were checked and are **not exposed**:

- `frontend/e2e/webhooks-flow.spec.ts:205` `getByTestId("deliveries-table")` — reached by a **client-side** nav
  (`a[href]` click → `waitForURL`). No buffer exists there.
- `frontend/e2e/stomp-relay.spec.ts:122` `waitForSelector('[data-testid="order-card"]', {state:"attached"})` —
  the scariest-looking one, because `state:"attached"` explicitly accepts hidden elements and it
  feeds a **latency** assertion, so a match on a staged copy would not fail loudly, it would
  fabricate a fast pass. But it runs after a `load`-gated `page.goto` **plus** several API
  round-trips; the buffer is long gone.

Exposure is therefore: **a full document load, asserted at `domcontentloaded`, before the throttle
fires.** Every spec that does that is now handled — `kitchen-flow` by #595, `dashboard-mobile` and
`dashboard-interface-corrections` since 2026-07-31.

**No gate was added, deliberately.** The standing doctrine is that a recurring failure earns an
executable check, and this one declined it on evidence: the exposure is narrow and already covered,
a lint on `getByTestId` would fire mostly on safe uses, and this repo already carries 29 gate
scripts — **six of which were once found wired to nothing**. A noisy gate is how the next reader
learns to ignore gates.

#### The E2E row is green again, and the skip budget was re-earned honestly

`174 passed / 8 skipped / 0 failed / 0 flaky` of 182 (383 s) — the baseline recorded after #565.
`check-e2e-skip-budget` is **rc=0** against a report regenerated **after the last spec edit**, which
is the only way it can be non-vacuous (the gate VOIDs on a report older than `frontend/e2e`). §0.-13
was right to leave it VOID rather than re-run it to green.

#### ⚠ Both PRs are blocked by a GitHub Actions outage, not by their content

GitHub declared a **major outage** for Actions at **15:22 UTC 2026-08-06**, still unresolved hours
later: *"workflow runs are delayed or failing to complete."* Every red check on #594 and #595 during
this window failed in **`Set up job`** — `Failed to resolve action download info: Service
Unavailable` — or was cancelled with **zero steps executed**. None reached a single step.

Two things follow, and the second is the one that costs time if forgotten:

- **A red check is not evidence until you read its steps.** `gh api repos/…/actions/jobs/<id>` and
  look at which step failed. A whole-board red that is really an outage is indistinguishable from a
  real break in the checks UI.
- **`docs-freshness` has a genuine failure mode that matters here** — its
  *"Verify the live claims in HANDOFF.md still hold"* step — and #594 is a HANDOFF change. Its
  failures this session were **not** that step; it never got that far, across four attempts. Do not
  record it as "docs-freshness passed" until a run actually executes it.

### 0.-13 A five-lane batch, and three fixes that were themselves broken and green (2026-08-06, superseded by §0.-14)

Five supervised lanes on #582, #516, #485, #458, #571. Four merged — #588, #586, #591, #589 — and
#590 carries this update. Two issues filed: **#587**, **#592**.

**The batch's real output is not the five fixes. It is that three previously-shipped fixes were
found broken while green**, each by a lane that went looking at the thing rather than the ticket.

#### #476's fix for #282 was itself truncating, and its own test could not see it

`fetchAllMyShops` — written to FIX this exact bug class — defaulted to `size=200` and treated
"fewer rows than I asked for" as the last page. core-java sets
`spring.data.web.pageable.max-page-size: 100`, which clamps every paged endpoint. So its first
**full** page of 100 read as a short page and it returned **100 of 250 shops**.

It survived because **#476's fixture honoured `?size=200` literally**. The double was more permissive
than the real server, so the clamp could not appear and the suite passed over the defect. A short
page is now measured against the `size` the SERVER reports; the kitchen board inherits the repair.

Also from that lane: only **six** of #485's eight sites were still defective (two were fixed by #535)
and three had drifted from the filed line numbers. It re-measured with `rg -uu` instead of trusting
the table. One consequence was serious — a shop past row 100 could not be picked when starting an
onboarding application, and onboarding is the sole writer of `Shop.published`, so **that shop could
never be taken live at all**.

#### #516's own issue prescribed the wrong fix

The issue assumed the unsubscribe fix needed `/api/v1/public/unsubscribe` served on the app host.
It does not: `frontend/app/unsubscribe/page.tsx` already exists and already POSTs the token to the
API. `base-url` was never the wrong value — **the path appended to it was**. Implementing the issue
as written would have changed ingress config for nothing.

That lane also found a half nobody had noticed: RFC 8058 `List-Unsubscribe` is **POSTed**, and a
Next.js page answers **405**, so one URL cannot serve both. The header now targets an API origin when
configured and degrades honestly when not — plain RFC 2369, with `List-Unsubscribe-Post` NOT stamped,
so it never advertises a capability it cannot honour. The k8s half is unwired and filed as **#592**.

`k8s/scripts/check-env-contract.sh` caught that lane giving the dev profile a `localhost:9090`
default no manifest supplies — **the same D-19 class as the bug it was fixing**. It removed the
override rather than allowlisting it.

#### A break arm went GREEN on /track

Setting the empty-state flag on the `!res.ok` path as well as the empty-list one — the #467 defect,
*"we could not ask"* rendered as *"you have none"* — **failed nothing in the suite**. The source
comment asserted the error paths were safe and no test could have contradicted it. Two arms cover it
now.

That lane also found the footer defect by looking at the RENDERED PAGE: gating the operator column
collapsed the grid, which is tidier in a screenshot and wrong in a browser — the session resolves
after first paint, so the column vanished live and "For customers" slid ~200px right as it went.

#### #587 — outbound webhooks give a receiver 127 seconds

`computeBackoffMillis` is `baseMs << (attempts-1)`. With the shipped defaults the entire schedule is
1, 2, 4, 8, 16, 32, 64 s, then terminal `FAILED` with no dead-letter and no replay. **A routine
deploy loses every event that fires during it**, and `auto-pause-threshold` is 10 so a single deploy
can burn 8 attempts per event without tripping the pause. `backoff-cap-ms: 3600000` is **dead
config** — the largest delay ever produced is 64 s, so the knob reads as an hour and delivers two
minutes.

#### The suite that had not run since #565 found a live regression — #593

`check-e2e-skip-budget` VOIDs after any merge touching `frontend/e2e`, and the remedy is to re-earn
the report by running the suite. Doing that rather than treating it as ceremony surfaced **two
identical "Mute alerts" buttons** on `/dashboard/kitchen` — the #556 hydration duplication that #577
reported removing, back on a control a kitchen relies on.

`172 passed / 8 skipped / 2 failed`, deterministic across both projects, against the container
rebuilt at 14:30:16 UTC and gate-confirmed FRESH.

The reason it went unseen: **no full suite had run since #565**, and #577 restructured that exact
header in between. Two lessons, both cheap:

- A gate that VOIDs "once per merge" is not noise — it is the only thing asking for the run that
  finds this class. Re-earn it; do not touch the report's mtime.
- The failing assertion's neighbour already carried a `.first()` workaround for the SAME defect on
  the shop selector, with a comment asserting "there is exactly one Select on this page". That
  workaround is why the button's duplication survived. **`.first()` converts a defect into a
  permanently invisible one.**

`check-e2e-skip-budget` is deliberately left VOID. Re-running until it goes green without fixing
#593 would be fixing the gate instead of the thing.

#### Process, measured

- **Three of five lanes hit infrastructure stalls**, four with NOTHING committed. Every resume now
  leads with "commit what you have first". One lane needed its changelog entry and PR body written
  by the supervisor after it stalled at that step.
- **The metrics boundary earned itself.** Every lane PR went red on `docs-freshness` because agents
  cannot touch `docs/metrics.json` — so no wrong count shipped. #590 alone produced THREE different
  correct totals: 2463 on the lane's base, 2487 at the first reconcile, 2509 on the merged tree.
- **`check-doc-metrics`'s failure list is PARTIAL.** Fixing the six CLAUDE.md/AGENTS.md claims it
  printed revealed four more in README.md it had not. Re-run after every fix; one listing is not the
  complete set.
- **The #583 citation gate fired for real** on a lane that stalled before writing its entry — a
  `fix(...)` PR owing an entry and having none, which would previously have gone green at PR time
  and redded `main` after merge.
- **"#458 is NOT closed" survived the merge**, verified with `gh issue view` after #591 landed and
  not merely by reading the phrasing.

### 0.-12 Two gates were green over instructions that could not be followed (2026-08-06, superseded by §0.-13)

Four PRs merged — #573, #574, #583, #584. `main` at `2e9792fb`. The two carried over from §0.-11 are
gone, so **#523 is the only open PR again**. But the merges are not the story.

#### The changelog-citation defect fired twice more, then got a gate

§0.-11 recorded four instances and filed #579. Reconciling #573 and #574 found **two more** — both
headings citing the issue (`(#450 item 4)`, `(#291)`) and not the PR. Six in two days. Both were
caught **before** merge this time, which is the only reason `main` did not go red again.

`check-changelog-cites-pr.sh` (#583) closes it at PR time. C-1 is unchanged and still wanted: it
asks about *merged* PRs and remains the backstop for one that merged with no entry at all.

**How it was validated matters more than that it exists.** Replayed against all six real instances —
each PR's changelog exactly as it stood at merge, with that PR's real title — all six return `1`.
Then the arm that makes it evidence: three PRs that **were** correctly cited (#567, #563, #554)
return `0`. Without that negative arm a gate that simply always failed would look identical across
the first six. Then it was pushed with **its own entry uncited**, observed failing on its own pull
request at exit 1, and observed passing after the citation — both read out of the CI log, not
inferred from a check bubble.

#### …and the resume block in §6 was prescribing a loop that could not succeed

Running §6 after #583 merged showed the real problem. **"EXPECT N x rc=0" had been unachievable
since #574**, and #583 made it worse:

    check-test-count-oracle.sh   (#574)  VOIDs at 2 without a family argument
    check-changelog-cites-pr.sh  (#583)  VOIDed at 2 off a pull request

Off a PR is every local run — every time anyone actually follows the instruction.

**H-1 was green through both.** It compares the *number* in the claim against the count of gate
scripts on disk, and that comparison was correct each time. It never asks whether the loop it guards
can return 0. This is the `structural green over a dead feature` shape applied to a *document*: the
gate asserts the property it happens to measure, not the behaviour the reader depends on.

#584 fixes both halves — the script now distinguishes **inapplicable** (no PR: SKIP at 0) from
**unverified** (`GITHUB_EVENT_NAME=pull_request` with no number: still VOID at 2), and the loop
supplies the argument `check-test-count-oracle` needs. Verified by **running the instruction**:
`ran 29 gates, 0 non-zero`, on `main`, after merge. Guard arms confirm it did not open up — a
missing number under a PR event is still 2, a genuinely uncited PR is still 1, and the CI log for
#584 shows `PASS: … citing #584`, i.e. it **asserted** rather than skipped.

#### "Does not close #450" closed #450

#573's PR body said, in bold, **"Does not close #450."** GitHub's closing-keyword parser is lexical:
it matched `close #450` and closed the issue at the second #573 merged. The negation is invisible to
it. The commit message was fine — it said "does not close **it**".

Natural experiment, three PRs trying to say the same thing:

| PR | wording | outcome |
|---|---|---|
| #575 | "Does **not** close the issue" | stayed open |
| #577 | "**#450 is NOT closed**" | stayed open |
| #573 | "Does not close **#450**." | **CLOSED** |

The discriminator is only whether the keyword is immediately followed by the reference. **Put the
number first, or say "the issue".** Then verify with `gh issue view N --json state`, because it
fails silently.

Checked before reopening: #534's own table shows #450 had **seven** sub-items (1, 2, 3, 4, 5b, 5c,
5d) and #573 landed the last one, so it is **correctly closed — by accident**. Left closed. Next
time the issue will not be complete.

#### #574's claim held, and #574 had a defect of the same class it was fixing

Independent verification against the runners rather than the PR's arithmetic: `npx jest --json`
reports **800**, per-file disagreement is **0 of 91** for the new counter and **10 of 91** (net +44)
for the old. The Playwright 85 → 80 is over-count removal — all five dropped matches are phantoms
like `/[?&]page=1\b/.test(u)`, no spec missing. On `main`'s tree the old counter said 756 while the
runner said 800, so **`main` had been green over a 44-test error**.

The defect: `expected=$(manifest_int "$2")` ran `void()`'s `exit 2` inside a command substitution,
which exits only the subshell — so every VOID surfaced as a rc=1 FAIL reading `docs/metrics.json
says .`, the wrong severity with the wrong remedy. `docs-freshness.sh`'s `count_js` documents and
avoids exactly this shape. Control: pre-fix rc=1, post-fix rc=2.

#### #582 filed: the two test-count gates can deadlock

A loop-declared Jest test is one declaration site and N executed tests. The static counter returns
`{"blocks":3}` at **rc=0** for a file where 5 tests run — no VOID, a confidently wrong number. Set
the manifest to what the counter wants and the oracle fails; set it to what the runner wants and
`docs-freshness` fails. Both are required checks and each failure's suggested remedy triggers the
other. Latent today (0/91). Playwright is immune because its oracle counts declaration sites.

#### Two instrument defects of my own, recorded because they nearly landed

- **`jq`'s `//` treats `false` as empty.** `.published // "n/a"` reported the field as *absent* when
  it was present and `false`. In the runtime after-arm the body is the entire question.
- **A PUT body built from guessed field names.** `phoneNumber`, `cuisineType`, `addressLine1` do not
  exist on the shop DTO (`phone`, `tags`, `deliveryInfo`, `openingHours`, `logoUrl` do). On a
  full-replace PUT that sends nulls for real fields; against a populated shop it would have wiped
  live data, and it ran twice. Nothing was lost **only because** the shop chosen was the sparse
  archive row whose fields were already null, so the dirty check found nothing to write — luck, not
  design. Verified after the fact: `shops_aud` holds one revision for it (the 2026-07-11 ADD) and the
  newest revision in `revinfo` for **any** table predates the probes, with a positive control
  (`revtype` 0:22 / 1:14 / 2:6) proving the query can see updates at all. Build request bodies by
  echoing the GET response.

#### #573 was proven on the delivered runtime, not just in CI

Rebuilt core-java (the post-merge hook correctly flagged DRIFT), then the same probe both sides:
before, `PUT published:true` → **200** with the body reporting `false`; after → **409**
`SHOP_PUBLISH_NOT_ACCEPTED` carrying `requestedPublished`/`currentPublished`. The load-bearing arms
are the two controls that must **not** change — a body echoing the current state, and one omitting
the field, both stay **200** no-ops. Those are the only thing distinguishing this fix from the naive
"reject on presence" version, which would 409 every ordinary shop edit because the vendor form sends
`published` on every save.

### 0.-11 A supervised four-lane agent batch, and the gate that suggested the wrong fix (2026-08-05, superseded by §0.-12)

**Read this before §0.-10.** `main` is at **#572** (`861591cf`). **#573** and **#574** are open and
neither is merged. Re-measure everything below; do not quote it forward.

#### What ran

Four file-disjoint lanes, each an agent in its own worktree, supervised rather than autonomous: the
supervisor kept `HANDOFF.md`, `docs/metrics.json` and merge sequencing; agents owned their code,
tests and changelog entry. Runtime proofs that need the compose stack were run by the supervisor
from the **main checkout**, because a worktree gets a different compose project name.

| lane | scope | outcome |
|---|---|---|
| A | #536 kitchen CLS + #450/5d | still running at time of writing |
| B | #450/3 + #450/4 | **#573** — item 4 fixed, item 3 correctly refused (see below) |
| C | #550 | **#572 MERGED** |
| D | #291 | **#574** — bigger than filed (see below) |

Closed by measurement with **no code written**: **#487**, **#205**, **#104**. **#571** filed.

#### #550 was configuration, not code — and the runtime arm was the supervisor's job

The management-listener mechanism already existed from #442; `EDGE_MANAGEMENT_PORT` was simply set
nowhere. One `.env` key (`EDGE_GO_METRICS_PORT`, default 9101) now drives both the edge service and
the Prometheus target through the existing `__PLACEHOLDER__`/sed render idiom.

Measured before and after, same probes, from the main checkout:

| probe | before | after |
|---|---|---|
| `/metrics` on published 8089 | **200** | **404** |
| protected route — CONTROL | 401 | **401** |
| `/health` — kubelet path | 200 | **200** |
| 9101 from host | — | **refused** |
| Prometheus target | `:8080` up | **`:9101` up** |
| `count(http_requests_total{job="edge-go"})` | 3 | **3** |

The unchanged 401 is load-bearing: it proves the 404 is the route moving, not the listener dying.
The unchanged series count proves the metrics **moved** rather than were lost.
⚠ `prometheus.yml.tmpl` renders at container **start** — a `restart` reads a stale render and gives
a false pass. Force-recreate.

#### The finding worth carrying: a gate suggested the WRONG fix, and it would have gone green

`check-doc-citations` was the one red job on #572, with **9 violations** — the lane's insertions
shifted line numbers in `docker-compose.full-stack.yml` (+18), the monitoring compose (+7),
`prometheus.yml.tmpl` (+2) and `main.go` (227→299), and three docs cite those files by line. Control
arm on `origin/main` was **62/62, 0 violations**, so all nine were the branch's.

For TS-12 the gate reported that subject `job_name` *"is at `infra/monitoring/prometheus/prometheus.yml.tmpl:42`"*. **That is the
first `job_name` in the file — the `prometheus` self-scrape job.** The citation has always meant
`core-java`, which sat at the old line 72 and now sits at 74. Taking the gate's own suggestion would
have turned the gate green while silently re-pointing a terminal-state locator at a different scrape
job: a green gate over a wrong fact, in the gate built to prevent exactly that.

**Two rules follow.** Add `check-doc-citations.sh` to the reflex set whenever a change inserts or
deletes lines in a cited file — no lane ran it unprompted. And when a gate names a replacement,
verify the replacement means what the claim meant; a first-match suggestion is not an answer.

#### Lane B refused half its own scope, and was right to

#450 sub-item 3 (WhatsApp webhook 500 on a known unconfigured state) is **not in core-java**. The
inbound webhook exists only at `edge-go/cmd/edge/handlers.go:209-213`. The core-java agent measured
that, declined to cross its write boundary, and wrote a handover instead — including the detail that
`edge-go/cmd/edge/handlers_test.go:127-134` **asserts the 500 and must flip**, while the signature
arms at `:140/:154/:171/:187` must stay green as the still-fails-closed control. Re-dispatched to the
edge-go agent. **A refusal that produces a correct handover is a better outcome than a fix in the
wrong package.**

Sub-item 4 shipped with **two** break arms, and the second is the point: under the naive
"reject on presence" fix all three refusal tests pass — it reads as a total success — and only the
control arm catches that it would 409 every ordinary shop edit, because the vendor form sends
`published` on every save (`frontend/app/dashboard/shops/page.tsx:158,183`). The issue did not
mention that, and it changed the fix. ⚠ `POST /shops` has the identical silent drop and is
**unfixed** — recorded, not quietly dropped.

#### #291 was wrong in BOTH directions, and totals would have hidden it

The filed defect was phantoms (`RegExp.prototype.test(`). Reconciled against the runners as oracle,
the manifest was wrong both ways: `jest_blocks` **745 → 789** (−7 phantoms, **+51** missed
`it.each` rows) and `playwright_blocks` **85 → 80**, an over-count nobody had filed. Total
**2391 → 2430**.

The load-bearing check is **per-file agreement, not the total**: the new counter matches Jest on all
91 files with 0 disagreements, where the old regex disagreed on 10 files summing to +44. A matching
total can hide two offsetting errors — which is this bug's own history.

#### Two costs of the supervision model, both real

1. **`HANDOFF.md` is agent-barred, so H-1 fired on #574.** Lane D added two gate scripts (26 → 28)
   and could not update the resume block's own expected count. That was the supervisor's to fix **on
   #574's branch**, not on `main` — putting it on `main` first would red `main` the moment #574
   merged, which is precisely the #569 failure. **Done in this change**, on the branch, together
   with the merge from `main`.
2. **`docs/metrics.json` conflicts three ways again.** #572 moved the total to 2394, #573 landed
   2409, #574 redefines the counting method. Neither "ours" nor "theirs" is ever right —
   regenerate with `scripts/docs-freshness.sh --write` on the merged tree, and merge #574
   **last** because it changes the method rather than the number. Every branch-local prediction
   in this train was wrong; measure on the merged tree instead.

#### A vacuous probe of my own, recorded rather than dropped

`docker exec jtoye-edge-go wget -q -O - http://localhost:9101/metrics` returned nothing and looked
like a failure. It is not: the edge image is **scratch-based**, so there is no `wget` and no shell
at all — `docker exec jtoye-edge-go /bin/sh -c 'echo hi'` fails with
`stat /bin/sh: no such file or directory`. Re-probed from `jtoye-prometheus`, which is on the same
network and does have `wget`: 3 sample lines. **Suspect the instrument first.**

#### Monitoring had to be recovered before any of this could be measured

The stack was **down** (11 containers, not 16). `up -d` failed on a stale network id because the
full-stack had been recreated under it; `down --remove-orphans` first is required, and
`set -a; . ./.env; set +a` before either (REDIS_PASSWORD is mandatory). All 8 targets up afterwards.

#### Addendum, later the same evening — the batch finished, and #579 was proven by a fourth instance

`main` reached **#577** (`bfdfbdfa`). Merged after the section above was written: **#575** (#450
item 3, WhatsApp `503 + Retry-After` — live-probed on the rebuilt binary, with #550's 404/200 as a
same-binary regression control), **#578** + **#580** (changelog citations), and **#577** (#536).

**#577's measured result overturned its own issue.** #536 said the residual CLS was a 16rem band
swapping for eighteen tickets, and prescribed a count-sized skeleton. It is not. The largest frame
is the **shell footer** — at y=797 in an 844px viewport, shoved 4574px down — so ticket count is
irrelevant and clearing the fold is everything. The fix is a viewport-sized reserve across all board
states. CLS **0.8287 → 0.0171** at the repo's declared profile with a real 18-ticket board, both
numbers re-measured on the branch rather than quoted from #535, and every run asserts
`bumpButtons=18` first so an empty board VOIDs instead of scoring low.

**#450 5d had already landed in #535** and the brief did not reflect it; the lane closed the one
measured hole instead (an unpublished selected shop degrades to `shops[0]` silently). **#450 stays
OPEN** — items 3 and 4 are in #575 and #573.

#### The changelog citation defect fired FOUR times in one day — now filed as #579

#568, #572, #575 and #577 all merged with an entry citing the **issue** and not the **PR**, redding
`main` each time. It is not carelessness: the entry is authored while the PR is open, when the
author is thinking in issue numbers, and C-1 only asks its question about **merged** PRs — so the
gate is structurally incapable of firing on the PR that breaks it. It goes red afterwards, on
`main`, and surfaces first as an inherited red on somebody else's unrelated PR.

⚠ **And the fix for it has its own trap, which I walked into.** #578 was titled `fix(docs): …`, and
C-1's subject matcher is `^(feat|fix)(\([^)]*\))?!?: ` — so the PR fixing the citation became itself
a `fix` PR owing an entry, and `main` stayed red one commit longer. #570 got this right with
`docs(changelog): …`. **Use a `docs(...)` subject for changelog-citation corrections.**

#### Merge-train shape, for whoever runs the next one

Three PRs each touching `docs/CHANGELOG.md` and `docs/metrics.json` **serialize**: every merge
re-dirties the rest, and each needs its own reconcile on the merged tree. Measured totals differed
from every branch-local prediction — #573 alone predicted 2395, main carried 2394, the merged tree
gave **2398**; #577's merged tree gave **2405**. Never carry a branch's number forward.

### 0.-10 #561 answered: a product defect, and a test that was wrong three times (2026-08-05, superseded by §0.-11)

**Read this before §0.-9, which it closes.** **#561 is CLOSED**; **#563 is MERGED** (`d36a1865`)
and **#565 is MERGED** (`b0043014`); **#564 is CLOSED**, shipped by #567 (`9762bce6`). `main` moved
again — re-measure, do not quote.

#### The mechanism, measured — and it is none of the three things it looked like

§0.-9 left three candidates and said plainly that none had been run. It is **(3), the product-side
one**, and the trigger is the board's *own* recovery request.

`fetchOrders()` on the full path issues **1 list request + one `/detail` per active ticket**,
concurrently, and the `online` handler deliberately takes that path on recovery. On the E2E
vendor's board — `Brixton Village Grill`, **18 active tickets** — that is **19 requests**, fired
twice inside ~400 ms by an offline blip. The tenant limiter is
`Bandwidth.capacity(120).refillIntervally(100, 1 min)`: **one lump per minute**, so whatever else
the tenant spent in the same 60 s window is carried state. That is exactly the "state left by the
specs that ran first" §0.-9 correctly stopped at.

`fetchKitchenOrderDetails` used `Promise.all`, so a single 429 rejected the **whole** read —
including the list request that succeeded and the eight details that succeeded. `syncFailed` went
true, `deriveFeedState` returned `status: "error"`, and the board raised *"Orders are not
refreshing"* **over data it was still holding**, with nothing retrying for up to a minute. The
trace shows **zero** further requests for the remaining ~20 s.

Two arms, identical request patterns, opposite outcomes:

| arm | `/api/v1/orders*` | statuses | lowest `X-RateLimit-Remaining` | `:339 [mobile]` |
|---|---|---|---|---|
| `kitchen-flow.spec.ts` alone | 38 (19 + 19) | **38 × 200** | **79** | PASS |
| after the 3 mobile specs before it | 38 (19 + 19) | 28 × 200, **10 × 429** (`Retry-After: 12`) | **0** | FAIL |

**Reproducing took 4 spec files and 1.5 min**, not the 6.6-min suite: the three mobile specs
preceding `kitchen-flow`, then `kitchen-flow`.

#### The fix, and the half of it that is easy to undo by accident

`Promise.allSettled`, and the read is judged on **what the board can show**: list read succeeded
and every active order has a detail, fresh or held → success. **A ticket with no detail at all
still fails the sync and still raises the banner**, and that has its own test. Without that second
half the "fix" is a mute button, and an incomplete kitchen board that stays quiet is the more
dangerous of the two failures.

#### The part worth carrying: the test was wrong THREE times, and no passing run ever caught it

Each version passed something before it was found wrong. None was caught by review.

| version | what was wrong | what caught it |
|---|---|---|
| v1 | read the **live** board, so it cost 19 + 19 requests and became an instance of the budget dependency it existed to remove; its own page load was refused and the pill read `Offline —` | the 4-file repro arm |
| v2 | armed its injected 429s during the **initial** load — it waited on the pill reading "Live", and the pill reads the **socket**, which connects before the first read returns. Two tickets then had no detail at all, so the board *correctly* refused to go quiet and the test called that a failure | the **full suite** (passed in isolation, failed on **both** projects) |
| v3 | its own fixture reads (`/api/v1/shops`, `/api/v1/staff/me`) could be refused — measured `429 Retry-After: 9` on both, leaving `selectedShopId` null, no topic, no socket, pill `Offline —` | the **post-merge** suite on `main` (173 passed / 1 failed) |

One shared cause: **a finite, minute-granular tenant budget that no test declares a claim on.**
That is #564.

#### Four things to take from it

1. **Where the full suite and a single spec disagree, the disagreement IS the finding.** §0.-9
   wrote that, and I then resolved the disagreement by trusting the isolated run anyway — twice.
   Neither result is the answer and the cheaper side is not the tiebreak.
2. **`:339` is not a regression guard for this.** It is budget-dependent: in the green re-run it
   saw **38 × 200, lowest remaining 24** — the condition did not recur, so its pass says nothing
   about 429 tolerance. `frontend/e2e/kitchen-flow.spec.ts:406` **injects** the condition instead. Verify this
   area with a trace and a request count, never with `:339`.
3. **A break arm can silently not happen.** The break was proven to have *shipped* by carrying a
   marker string into the toast text and reading it back out of the **served** `.next` bundle: 2
   with the break, **0** after the restore, with the fix's own string still at 2. A marker is
   needed because the surrounding code is present in both versions, so the obvious probe cannot
   discriminate.
4. **A fallback that never fires is unproven.** Draining the bucket with two back-to-back spec
   runs did **not** reproduce the refusal (16/16, 2.4 s), so both paths of the new retry were
   **forced**: attempt-1-refused passes in **7.5 s** instead of 2.4 s (it waited and recovered);
   all-attempts-refused fails after ~17 s with the intended message naming the URL and the budget.

#### Numbers from this session, each re-measured after the last merge

- Full E2E on `main`: **174 passed / 8 skipped / 0 failed of 182** (6.6 min), skips at exactly the
  declared ceiling of 8.
- jest **91 suites / 791 tests**; `npm run build` rc=0; lint **0 errors** (both warnings verified
  present on `origin/main` with a probe shown to discriminate).
- All **26** repo gates and all six k8s gates rc=0, after the two standing remedies fired exactly
  as documented: `check-alert-metrics` rc=1 → `seed-order-metric.sh`, and `check-e2e-skip-budget`
  rc=2 VOID **twice** — once per merge, because a merge refreshes the spec's mtime. Its content
  was byte-identical both times (`git rev-parse HEAD:…` matched the tested blob), and the report
  was still re-earned by re-running the suite rather than touched.
- `docs/metrics.json` regenerated, never hand-arithmetic: jest 743 → **747**, playwright 84 → **85**,
  total 2386 → **2391**, carried into README/CLAUDE/AGENTS (two gates read the prose, not the
  manifest — and `check-claims` caught a second copy in README after `check-doc-metrics` had
  already gone green).

### 0.-9 A correction: "flake" was the wrong call, and so was my second guess (2026-08-05)

**§0.-8 says two of the three E2E failures were flakes. That is wrong for one of them**, and the
correction is worth more than the finding.

`frontend/e2e/kitchen-flow.spec.ts:339` `[mobile]` (offline → banner → recovery) now has four data points:

| run | `:339 [mobile]` |
|---|---|
| full suite, run 1 | **failed** |
| spec alone | passed |
| spec alone, after a frontend rebuild | passed |
| full suite, run 2 (after #558/#559) | **failed** |

**2/2 failing in the full suite, 2/2 passing in isolation.** A flake does not sort itself that
cleanly by context. I labelled it from a single isolated pass, which is exactly the sample size
that cannot tell "fixed" from "not reproduced here".

**Then my second guess was also wrong, and the config falsified it before I wrote it down.** The
obvious explanation is worker contention — but `playwright.config.ts` sets `fullyParallel: false`
and `workers: 1`. The suite runs **sequentially on one worker**. So the difference between
"full suite" and "this spec alone" is not concurrency; it is **state left by the specs that ran
first**. Filed as **#561** with three candidate mechanisms and the measurement that discriminates
them — none of them yet run, and the issue says so.

**Why this one matters beyond a red test.** `:339` is deliberately the spec that lifts every stub
and uses the real stack — real SSO, real WebSocket, real STOMP topic, real feed. If the cause turns
out to be product-side, then a kitchen board that went offline **never tells the vendor it is live
again**, under exactly the conditions a real kitchen runs in: a long session with accumulated
orders. The test's own comment already says it — *"A warning that outlives its cause is how a
kitchen learns to ignore warnings."*

**The transferable lesson.** §0.-8's own lesson 1 was *"the full suite and a single spec disagree —
measure both before believing either"*. I wrote that, then in the same breath resolved the
disagreement by trusting the isolated run. Where the two disagree, **the disagreement is the
finding**; neither result is the answer, and one pass on the cheaper side is not a tiebreak.

**Suite state at `14750546`:** `171 passed / 1 failed / 8 skipped` of 180 (6.6m).
`check-e2e-skip-budget` **rc=0**, 8 skipped at exactly its ceiling of 8.

### 0.-8 Two UI defects no gate could see, both found by re-running a suite (2026-08-05)

**Read this before §0.-7.** Two more PRs merged after it: **#558** (`d16935ab`, closes #556) and
**#559** (`93ad0ab0`, closes #557). `main` HEAD is `93ad0ab0` at the time of writing — re-measure.

**The point of this section is where these came from.** Neither was found by a gate. All 26 gates
were green, CI was green, and the nightly was green, while the kitchen board told a vendor something
false. They surfaced only because the local E2E suite was re-run to clear a stale
`check-e2e-skip-budget` report — housekeeping, not a hunt.

#### #556 — the board said "No shop selected" while it was still loading

`kitchen/page.tsx` renders `KdsBoardShopName` twice: **:546** in the loading early-return and
**:575** in the loaded body. The loading one passed `shopName={null}`, so both took the same branch.

**The product half, which the issue as filed undersold.** While data was in flight the header read
**"No shop selected"**. Not vague — *false*. A vendor whose shop is loading is told there is no
shop, and a screen reader announces it as settled fact. Loading was a third state borrowing the
empty state's voice.

**The test half.** Both renders carried `data-testid="kds-board-shop"`. Next server-renders this
`"use client"` component's loading state and swaps it after hydration, so **both trees are briefly
in the DOM**; Playwright strict mode found two elements and resolved the stale one. Same mechanism
as #540, the class #542 tracks.

Fixed in #558: `loading` is explicit and carries its own testid, so the three states are
distinguishable by any consumer — a test, a screen reader, a future component.

#### #557 — the grammar defect sitting under the comment written to prevent it

`KdsAllShopsNotice`'s `shopCount === 2` branch rendered a singular noun with a plural verb:
*"orders for your other shop **are** not on this screen"*. Same class as `"1 items in basket"`
(#533) — and it sat directly beneath:

```
// "your other 1 shop" is the `"1 items"` defect in #450 item 5 wearing a
// different hat. The count is only worth printing when it is >1.
```

That earlier fix dropped the count correctly and left the verb plural. **It removed half of a
grammar defect, and its own stated reasoning should have caught the other half.** Fixed in #559 by
putting noun and verb in the SAME ternary branch — the old shape had the noun conditional and the
verb hardcoded outside it, so they could only agree by coincidence.

#### Four verification lessons, all of which cost time here

1. **The full suite and a single spec disagree, and neither is "the" answer.** The suite reported
   **3 failed**; re-running `kitchen-flow.spec.ts` alone gave **1 failed**. ⚠ **I called the other
   two "flakes" and that was WRONG for one of them — see §0.-9.** Fixing
   all three as one thing would have chased two non-causes. **Measure both before believing either.**
2. **Blame the most recent merge last, not first.** #554 had just touched the STOMP *kitchen* path
   and the failures were all in `kitchen-flow`. It is innocent: `git show --name-only 7c1ef2a7`
   changes **0 frontend files**, and `/dashboard/kitchen` is `"use client"`. Rule out by
   measurement, not by plausibility.
3. **A fix that updates one assertion and misses its twin looks complete locally.** #557's wrong
   string was encoded in **two** test files; the second
   (`frontend/app/dashboard/__tests__/marketing-kitchen-shop-scope.test.tsx:288`) was found only by searching for the string rather
   than trusting the first suite to be the only one.
4. **Guard the opposite direction or the fix is satisfiable by its mirror image.** "Fix the
   grammar" is satisfied by making *everything* singular. A 4-shop vendor must still read "your
   other 3 shops **are**", and that is now asserted.

#### Proof, and the half that is load-bearing

Runtime rebuilt **and recreated**, 4/4 FRESH at `93ad0ab0`. Read out of the served build inside the
running container, with controls **both ways**:

| string | count | meaning |
|---|---|---|
| `other shop is` | **2** | #557's fix is in the artifact |
| `other shop are` | **0** | **the defect is GONE, not merely accompanied** |
| `kds-board-shop-loading` | **2** | #558's fix is in the artifact |
| a constructed-absent string | **0** | the probe can report absence |

**The zero on the old string is the load-bearing row.** A present-new check alone is satisfied by a
build containing both. `kds-board-shop-loading` appears in **both** the SSR and client chunks, which
independently corroborates the diagnosed hydration mechanism rather than leaving it a plausible
story.

Functional re-check: `kitchen-flow.spec.ts` **14/14** against the rebuilt stack, including the
`:455` test that was reproducibly red.

### 0.-7 Phase 28 opened: two criteria closed, and a class of gate that ran nowhere (2026-08-05)

**Read this before §0.-6.** Three PRs merged after it: **#541** (`feb8ef63`), **#553** (`515652b9`),
**#554** (`7c1ef2a7`). `main` HEAD at the time of writing is `7c1ef2a7`; re-measure, do not quote.

#### Phase 28 is roughly half-done, and most of that was already true before this session

Phase 28 (Security Triage + the Dev/Prod Boundary) was "not started, not planned". Measuring it
first turned out to matter more than building it: **8 of the 11 pentest findings were already
remediated by work that shipped for other reasons.** The phase's real remaining content is small.

| criterion | state | settled by |
|---|---|---|
| SEC-01 (re-verify A1, record CONFIRMED/FALSIFIED) | **CLOSED** | root cause **FALSIFIED**, both halves — see below |
| SEC-02 (all 11 findings filed or accepted) | **CLOSED** | **#548** tracking + **#549/#550/#551/#552** |
| SEC-03 (no dev branch under prod; no advertisement) | **already done** | **#440** — see the correction below |
| SEC-04 (no `0.0.0.0` infra ports) | **already done, now ENFORCED** | #510 bound them; **#553** made the gate able to fire |
| #289 (STOMP shop-gate hard-coded) | **CLOSED** | **#554** |
| #283 / #284 (`auth == null` bypass + async SecurityContext) | **OPEN — one piece, not two** | see §0.-7 "what is left" |

#### SEC-01: A1's root cause is FALSIFIED, and the guard was proven able to fail

The criterion fails if the re-verification is skipped *or* reports "as filed" without an arm. Both
halves of the stated root cause are false on the tree:

- **Schema.** `shop_promotions` and `shop_announcements` both carry `tenant_id`, RLS **enabled and
  forced**, 2 policies each. **Non-vacuous**: the same query against `tenants` (deliberately
  RLS-free) returns `f|f|0`, so the probe can report absence.
- **Service.** Both services call `require(shopId, SHOP_MANAGER)` on create/update/delete.

**The guard was falsified, not merely observed passing.** `CrossTenantAuthzIntegrationTest` (6) and
`ShopPromotionsRlsPolicyIntegrationTest` (3) pass clean. Neutralise the ownership check in
`PromotionService.createPromotion` and the run goes to **exactly 1 failure —
`createPromotion_crossTenantShop_isBlocked()`** — and no other. Restore verified by
`git hash-object`, closing arm clean. Four arms, all recorded.

⚠ **These are `@Tag("testcontainers")` tests.** `:core-java:test --tests "*CrossTenantAuthz*"` fails
with *"No tests found for given includes"*, which reads like the test does not exist. Use
**`:core-java:integrationTest`**.

#### A correction, because it nearly became a filed defect

`core-java/src/main/java/uk/jtoye/core/config/OpenApiConfig.java:51` still contains the line *"Dev fallback: Use `X-Tenant-Id` header…"*, and a
source read says SEC-03 is open. **It is not.** `TenantHeaderSchemeCustomizer` strips the scheme,
the global requirement, every per-operation requirement **and that prose bullet** at document-build
time when the filter is absent, with 3 unit tests. #440 closed it properly. **Do not re-file this
from a grep.**

#### The finding that was in no issue: six gates ran nowhere

Measured while checking SEC-04: of the 24 `scripts/check-*.sh`, **six had zero references in
`.github/workflows/`**. Three are deliberate — they inspect a running stack and could only ever
VOID on a runner. **Three were not**, and each had been written *because* a defect shipped:
`check-e2e-baseurl-contract` (#505), `check-playwright-mobile-contract` (#503),
`check-no-measured-placeholders` (27-04 D-05).

This is its own failure class, one level up from the usual one. The usual trap is *a gate passes
while the thing is broken*. Here **the gate is correct and never runs**, so "the gate is green" was
never even a claim anyone made — the property simply went unasked. Same shape as #510: the loopback
fix is real, and its gate is one of the three that genuinely cannot run in CI, so nothing stopped
anyone re-adding `0.0.0.0`.

Fixed in **#553**: the three are wired into `ops-contracts`, plus
**`scripts/check-gate-enforcement.sh`** + `scripts/gates/gate-enforcement.conf` — every
`check-*.sh` must run in a workflow **or** carry a reasoned exemption. Default-deny, self-covering,
and it **failed itself on its first run**. Repo is now **25** `check-*.sh` (26 counting
`docs-freshness.sh`, which is what H-1 asserts).

**Measuring this correctly needs three things**, all of which went wrong first:
`rg -uu` (`.github/` is hidden); the rc on its **own statement** (`grep …; echo "rc=$?"` reports the
echo's, and printed a false "wired"); and a **known-wired control** — `ci_refs=0` everywhere is
equally consistent with "nothing is wired" and "the probe is broken". After the fix,
`check-infra-exposure` correctly **stays** at 0, which is what proves the measurement still
discriminates rather than having become universally 1.

#### #554 — the STOMP shop gate was default-open

`TenantChannelInterceptor` gated shops with `KITCHEN_FEATURE.equals(parts[FEATURE_WORD])`. Correct
today, and **default-open**: a second shop-scoped topic inherits the tenant wall and skips the shop
check, silently. Now reads `StompDestinations.SHOP_SCOPED_FEATURES`, and
`StompShopGateCoverageTest` derives the shop-scoped set from the **factories** by reflection and
fails when the registry falls behind. Behaviour on today's tree is **unchanged**
(`Set.of("kitchen").contains(x)` ≡ `"kitchen".equals(x)`), which is why the control is the whole
suite — **133 classes / 952 tests / 0 failures / 0 errors / 1 skipped** — not the diff.

#### Three traps this session, all in the *verification*, not the code

1. **A break arm can silently not happen, and that reads as "the guard does not fire".** A
   `perl -0pi -e` substitution failed with a syntax error, the factory was never added, the test
   passed. Only asserting the break had landed (`factory present: 1`) caught it. **A break arm needs
   its own proof that the break happened.**
2. **`git checkout` after a break arm eats uncommitted work.** The #289 registry change was
   uncommitted; `checkout` restores from the **index** and would have discarded the fix along with
   the break. The break block was removed **by editing**, then hash-verified. (This trap is already
   recorded in this repo and it still nearly fired.)
3. **`grep` and `awk` silently failed to display very long lines.** Reviewing the metrics diff, both
   showed **2 of 5** changed lines — the three long prose lines vanished. Only `Read` showed them.
   Reported as "only the badge changed" it would have been wrong. **For a diff that matters, read
   the diff.**

Also: the background-task completion notice reports the **pipeline's** exit code. A
`gradle … | tail` that BUILD FAILED was announced as *"exit code 0"*. Read the artifact, not the
notification.

#### What is left in Phase 28

**#283 and #284 are one piece, not two.** #283 (replace the retained `auth == null` bypass with an
explicit `asSystem()` marker) is explicitly deferred as oversized — **62 no-principal test files**
plus every internal call path — and #284's fix shape depends on either that marker or
`SecurityContext` propagation. #284's guard-test half wants call-graph analysis, and **there is no
ArchUnit dependency in this repo**, so it needs that added or a narrower reflection check. Budget a
session, not a tail-end.

Also open from the disposition: **#549** (staging OpenAPI), **#550** (edge `/metrics`, measured
HTTP 200 with a 401 control), **#551** (audience-mapper audit), **#552** (rotation — needs the
owner, it touches real values). And two prevention items from the report that are still unbuilt: a
CI assertion enumerating tenant-scoped tables that fails when one lacks an RLS policy, and a gate
asserting no dev-only branch is reachable under `prod`.

### 0.-6 The nightly is GREEN — the first clean run this repo has produced (2026-08-05)

**Read this before §0.-5**, which predicts this outcome rather than reporting it.

```
run 30971049317   sha d4930719   total=180  passed=173  failed=0  skipped=7
run 30967157741   sha d4930719   total=180  passed=173  failed=0  skipped=7
```

Two consecutive runs on `d4930719` (#543). §0.-5's prediction — *"expect `failed=0`"* — **held**, and
the four surviving checkout failures were indeed the `.env.example` inline comment and nothing else.

**What this does and does not establish.** It establishes that the suite has a real CI baseline for
the first time: seven earlier runs produced *zero* test results, and the eighth and ninth produced
failures. It does **not** establish the card path — with `STRIPE_API_KEY` genuinely empty, checkout
takes the COD fallback, so `failed=0` here is consistent with the online path still never having
executed. That remains the Phase 30 owner decision. **A green suite over an unreachable branch is
exactly the shape this document keeps warning about; do not read it as payment coverage.**

**The 7 skips are declared and inside the budget of 8, but unverified** — filed as **#547**, the
successor to #420 (now CLOSED). The budget sits one below its ceiling, so the next skip added trips it.

**Gate sweep at this commit:** 21/25 `scripts/check-*` rc=0 and 6/6 k8s gates rc=0. The non-zero ones
were all branch-local and are cleared by this merge — `check-branch-behind-base` (1 behind),
`check-changelog-contract` (#543's entry lives on main), `check-handoff-contract` (the two rows this
commit corrects) — plus the two standing remedies that are not regressions: `check-alert-metrics`
rc=1 (`scripts/seed-order-metric.sh`, fires on every core-java recreate) and `check-e2e-skip-budget`
rc=2 VOID (stored report older than `frontend/e2e`; re-run the suite).

**Unfiled, found while measuring the above: 6 of 24 gates have ZERO CI references.** Three are
deliberate — they need a live stack and could only ever VOID on a runner (`check-infra-exposure`
part B, `check-container-config-drift`, `check-alert-mute`). **Three are purely static and nothing
runs them:** `check-e2e-baseurl-contract` (#505), `check-playwright-mobile-contract` (#503) and
`check-no-measured-placeholders` (27-04 D-05). Each was written to stop a *specific* defect
recurring, and none can fire on a PR — the same shape as SEC-04, whose fix (#510) is green while
nothing prevents someone re-adding `0.0.0.0` tomorrow. Measure it with
`rg -uu -l "<gate>.sh" .github/workflows/` and **capture the rc on its own statement** — the
`ci_refs=0` reading is an absence claim, so it needs `-uu` (`.github/` is hidden) and a
known-wired control (`check-changelog-contract` → `docs-freshness.yml`) to prove the probe can see.

### 0.-5 The nightly finally produced a number, and it found four things no gate could (2026-08-05)

**Read this before §0.-4.** That section describes the five-lane triage train; this one describes what
happened when the train's own work was finally tested by something that had never run.

#### The headline: there is a nightly baseline now, for the first time

`e2e-nightly.yml` had run **seven** times and produced **zero** test results — every run died building
the stack, on #517. #532 fixed that, and the eighth run completed:

```
run 30955236660   sha a769b597   total=180  passed=167  failed=6  skipped=7
```

**180, not 135.** The 135 figure elsewhere in this document is a *local* run of the spec files; the
nightly runs both Playwright projects, so it executes ~180 test instances. Do not compare them. The
row in §Live-stack that reads `127 passed / 8 skipped / 0 failed of 135` is a **local** measurement
and is labelled as such — it is not the suite's CI state and never was.

#### The 6 failures decomposed into three different kinds — this is the reusable part

Two of them were ours, one was a measured flake, and **four were a pre-existing defect that only the
nightly could reach**. Conflating those would have produced the wrong fix three times over.

| failures | test | verdict |
|---|---|---|
| 4 | `storefront-flows` checkout + Mailhog | **pre-existing**, not the train (#538) — but see "The SECOND nightly" below: #539 fixed the NPE and the tests still failed, on `.env.example` (#543) |
| 2 | `public-layout` modal shape | **#537 fallout** — see the stub hazard below |
| (+1 local only) | `storefront-flows:155` menu loads | **a real flake**, measured 10/25 and 7/12 |

**The first diagnosis was wrong and it is worth knowing why.** The modal test exercises the component
#533 rewrote to a Radix Dialog, so #533 was the obvious suspect. It is innocent. The cause is #537:
the spec stubs the API with `context.route("**/public/**")`, which intercepts **browser** requests
only. Once `/shop/[slug]` became a server component, the Next server's fetch stopped passing through
the browser, so against a live backend the fixture slug `test-kitchen` gets an authoritative 404 →
`notFound()` → **no dish cards exist** → `locator.click` waits out 60s. The failure screenshot is a
"Shop not found" page. Fixed in #540 (tests only — `notFound()` on a missing slug is correct).


#### The SECOND nightly, and why "fixed" was the wrong word for the checkout half

Run `30964857894` on `1112ff15`, dispatched after #539 and #540 merged:

```
total=180  passed=169  failed=4  skipped=7      (was 167 / 6)
```

**#540 worked** — both modal failures gone. **#539 worked too, at what it targeted**: the
`Order.getId() is null` NPE occurs **0 times** in this run's stack logs, against 2 in the previous
one. But the four checkout failures survived, with an identical symptom
(`getByRole('heading', { name: 'Order confirmed!' })` never appears) and a *different* cause:

```
java.lang.RuntimeException: Payment processing unavailable. Please try again later.
	at uk.jtoye.core.storefront.PublicStorefrontService.createGuestOrder(PublicStorefrontService.java:576)
```

That is the catch block rethrowing a `StripeException` — the code now persists the order, reaches
Stripe correctly, and fails because **CI had a Stripe key that was not a real one**.

**Where CI got a key from, given the workflow never sets one.** `e2e-nightly.yml` does
`cp .env.example .env`, and `.env.example` carried:

```
STRIPE_API_KEY=               # sk_test_... from Stripe dashboard
```

**Docker Compose treats an inline comment as part of the value.** Measured with a two-service probe:
that line resolves to `STRIPE_API_KEY: '# sk_test_... from Stripe dashboard'`, while a bare `VAR=`
resolves to `""` and `CONTROL_SET=realvalue` resolves normally — so the probe discriminates.
`isConfigured()` is `apiKey != null && !apiKey.isBlank()`, so a **comment was a credential**.

Four variables had that shape, all of them "leave blank unless you have a key" flags:
`ANTHROPIC_API_KEY`, `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`,
`NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`. Fixed in **#543**.

**The correction worth carrying: the product was right on both runs and the environment was lying
about being configured.** #538 was a genuine defect and #539 genuinely fixed it — but the thing that
*surfaced* it, and then kept the test red afterwards, was `.env.example`. A run that fails twice for
two different reasons is not the same as a fix that did not work, and reading it that way would have
sent someone back into `PublicStorefrontService` for a third time.

**Still not established, and #543 does not change it:** with the key genuinely empty, checkout returns
to the COD fallback, so the **card path remains unexercised**. Proving it needs a real Stripe test key
— the same owner decision that gates Phase 30.

**Expect on the next dispatch:** `failed=0`. **This resolved — see §0.-6.** #543 merged as `d4930719`
and the next two runs both returned `failed=0`. The contingency below was not needed, and is retained
because it is the right first move if the suite ever goes red here again: if checkout is red after a
`.env.example` fix, the cause is a third one and the `.env` resolution should be checked first — read
the value the container actually got, not the file.
#### The hazard #540 worked around but did NOT fix — this scales, and it fails silently

**A browser-level API stub cannot describe a server-rendered route.** The consequence is worse than a
red test:

> **The public-surface gate was green exactly when CI had no backend.** Stack-free, core is
> unreachable → the server fetch fails → `getJson` catches → `defer` → the client island fetches →
> the browser stub answers → pass. Give it a real backend and the same spec fails.

A **second, quieter regression** rode along: the sibling `/shop/test-kitchen` layout test was
**passing vacuously** over that same not-found page — an empty page has no fixed-ratio boxes, no
images, no overflow, and does have an `<h1>`.

**#507 has 20 more routes queued for exactly this conversion.** Each one silently removes whatever
`context.route` coverage its spec had, and the failure mode is a vacuous pass, not a failure. This is
an unfiled structural decision about how the CI browser gate should work. **File it before the
conversions start.**

#### #538 — card checkout has never worked, only been unreachable

`PublicStorefrontService.createGuestOrder` called `paymentService.createPaymentIntent(order)` at
`:512` and `orderRepository.save(order)` at `:524`, and `PaymentService:126` is
`.putMetadata("order_id", order.getId().toString())`. So the id is dereferenced **twelve lines before
it is assigned** → guaranteed NPE → HTTP 500.

It is gated behind `isConfigured()` (`apiKey != null && !isBlank()`), and **every stack has an empty
Stripe key**, so checkout has always taken the COD fallback and the online path has never executed.
That is why it survived: the same test **passes locally** (COD) and **fails in CI**.

**This sits directly under a Phase-30 blocker.** The day a Stripe test key is added to any stack, the
first card order 500s. Fixed in #539 (persist → pay, `saveAndFlush` so the V24 unique index resolves
before money is asked for; rollback on a failed intent is the *correct* behaviour, not just the free
one — keeping the DRAFT row would strand the customer's idempotency key on an unpayable order).

Two adjacent money-path defects were fixed in the same PR because they are only *possible* once the
order has an id at that moment: the PaymentIntent id is now persisted to `order.payment_reference`
(without it WR-02's idempotent-retry re-fetch at `:346-357` is guarded on `paymentReference != null`
and can **never** fire — a retried card checkout returned a null client secret), and the Stripe call
now carries an `Idempotency-Key`, which the standing agent-readiness contract requires of any mutating
endpoint.

**What #539 does NOT establish, and nobody should read into it:** the card path is correct by
construction and by test, and has still **never executed against Stripe**. The artifact that closes it
is a test-mode `pi_...` id paired with the local order row. That needs a Stripe test key — an owner
decision, and the immediate follow-up.

#### Merge-train traps, second sitting

§0.-4 lists four. Three more, all of which cost a real CI cycle today:

5. **`check-changelog-contract` C-1 wants the PR's OWN number and agents write issue numbers.** #533's
   entry cited `(#446, #272)`, so the gate went **RED on main** the instant it squash-merged and had
   to be repaired from inside #534. Every subsequent PR was fixed pre-merge. Check this before merging.
6. **`docs-freshness --write` run during an UNRESOLVED merge inflates its own counts.** An unmerged
   path appears in the index **once per stage**, so a still-conflicted spec file was counted more than
   once — it reported `playwright 92/19` where the truth was `83/18`, exactly the 9 `test(` blocks of
   the conflicted file. Resolve and `git add` every source conflict **before** regenerating. Docs-only
   conflicts are immune, which is why #534/#535 were unaffected and green.
7. **A branch name contains `/`.** `> "$SCRATCH/merge-$BR.log"` fails *before* the command runs and
   bash reports the **redirect's** status as the merge's, so the merge silently never happens and
   everything downstream operates on the unmerged tree. Guard by asserting
   `git merge-base --is-ancestor origin/main HEAD` after any "successful" merge — never by the rc.

#### State as of this commit

Ten PRs merged in sequence: **#532 #533 #534 #535 #537** (triage train), **#539 #540** (nightly
fallout). Closed: **#517 #446 #272 #454 #105 #106 #447 #538**. Filed and open: **#536**
(`/dashboard/kitchen` CLS 0.2005 against a 0.1 budget, measured, in no lane's scope).

**Do not quote these figures without re-running them** — every one moved at least twice today:

| | |
|---|---|
| runtime | 4/4 FRESH after rebuild; #539 proven in the running jar (`Idempotency` ×2 in `PaymentService.class`, was 0, negative control 0); #537 proven by served HTML (`/shop/brixton-village-grill` 91,745 B, 1 `<h1>`, 1 canonical, 1 JSON-LD, 16 `og:`; `/robots.txt` 200, was 404) |
| local specs | `public-layout` + `storefront-flows` **52 passed** against the rebuilt stack — all three previously-failing tests green |
| nightly | run `30964857894` = **180/169/4/7**. #540 fixed; #539 fixed its NPE (0 occurrences, was 2); checkout still red on `.env.example`. **#543 is now MERGED and the prediction held** — see §0.-6 |
| Keycloak | dev drift removed — `:3102`/`:3103` gone from `jtoye-dev`/`core-api`, proven by rejecting those redirect_uris while `:3000`/`:3100` still return a login page. **Realms are `jtoye-dev` and `jtoye-customers`; a probe against `jtoye` returns `Realm not found`, which reads exactly like "no drift"** |


### 0.-4 The five-lane triage train (2026-08-04) — and four traps inside the MERGE itself

**What ran.** All **62** open issues were triaged read-only by five specialist agents in parallel
(frontend / core-java / security / platform / product), each required to prove an issue was still
live **against the tree** rather than trusting its title. The correction being applied: the
2026-08-03 train selected issues for *not colliding*, which is close to the inverse of *user-visible*,
and the owner then looked at the running app and still saw every problem they had reported. So this
pass rated **user-visibility** as a first-class output.

**What the triage found before a line was written** — re-verify, do not quote:

- **~9 issues were already fixed** and needed closing with evidence, not work (#104's mobile sidebar
  landed in Phase 19 and is guarded by an e2e spec; all six of #99's named CI/CD defects have
  citations on the tree).
- **#487 and #488 were filed UNMEASURED and both measure to ZERO** — 0 cross-tenant promotion rows,
  0 bucket objects with GPS, 0 outside the content-type allowlist, each with a fail-direction
  control. **#487 additionally carries an outage warning**: narrowing `shop_promotions_read` the
  obvious way would return zero promotions on every public shop page.
- **#453 (P1 `bug`, "MANUAL_REVIEW is on no surface") is probably already built** — queue, resolver
  endpoint and vendor email all shipped 2026-07-14, **19 days before the issue was filed**. The
  council saw *"No applications waiting"* because the page renders that when both queues are empty.
  Verify with a seeded row and a second-tenant arm; do not close on a code read.
- **#460 is worse than filed**: beyond `geolocation` = 0 and `deliveryRadius` = 0, **nothing writes
  `shops.latitude`/`longitude`** — not the API, the UI, the seeder, or a migration default. So even a
  "decision-neutral distance sort" is unbuildable; the real first step is a coordinate capture path.
- **The nightly E2E had run 7 times and produced a test result ZERO times**, all dying on #517.

**What shipped.** Five PRs, one per lane: **#532** (fresh-DB Flyway), **#533** (dish-modal dialog
semantics), **#534** (dashboard corrections), **#535** (kitchen print + offline UX), **#537**
(server-rendered storefront + SEO). Closed: #517, #446, #272, #454, #105, #106, #447. **#536 was
filed**, not fixed — `/dashboard/kitchen` is over the 0.1 CLS budget and belongs to no lane.

**#517's mechanism is not what its issue says, and the difference matters.** A Postgres **placeholder
GUC resets to the empty string, not to unset**: a virgin session reads `NULL`, the same session after
a committed transaction-local `set_config` reads `''`, and `''::uuid` raises 22P02 where `NULL::uuid`
is harmless. So **all six** `is_local => true` call sites leave `''` behind — V44 is merely the first
one a fresh chain reaches before V46. A fix aimed at V44 alone would have been wrong. It fires only
when V44 and V46 land on the **same physical connection**, and `out-of-order=true` applied V46 before
V44 on every long-lived database — which is why no developer machine reproduces it and every fresh
one dies.

---

**Four traps that existed only inside the merge train.** None is in any lane's diff; each cost a red
CI run or a wrong artifact.

1. **`git add` BEFORE regenerating makes every local gate green about a file you will never push.**
   On #533 the index carried `metrics.json` at 2207 while the working tree carried 2230.
   `docs-freshness` rc=0 and `check-doc-metrics` 37/37 PASS — **both true, both about the working
   tree**. CI read the commit and failed correctly. **Rule: regenerate → stage → verify with
   `git show HEAD:<file>`, never from the working tree.**

2. **`check-changelog-contract` C-1 wants the PR's OWN number, and every agent wrote issue numbers.**
   #533's entry cited `(#446, #272)`, so the gate went **RED on main** the instant it squash-merged,
   and had to be repaired from inside #534. #534/#535/#537 were fixed pre-merge. **Any new entry must
   cite its own PR number.**

3. **"Keep BOTH sides" on `docs/CHANGELOG.md` is right for two DIFFERENT entries and wrong for the
   SAME entry that was edited.** #537's merge produced A1's heading twice — the branch's stale
   `(#446, #272)` copy as an **orphaned heading with no body**, plus main's corrected `(#533 …)` copy
   with the body. Keep-both still beats take-either (which silently deletes a release note), but
   **check for duplicate `### ` headings afterwards.**

4. **`rg` does not exist inside a script.** `rg` and `grep` are shell FUNCTIONS the harness injects
   and there is **no system ripgrep behind them**, so `rg` in a `bash script.sh` dies rc=127 — which
   is indistinguishable from "no matches found". It reported `CHANGELOG conflicted but no markers
   found` on a file with three markers. Use `awk` in scripts; `grep` is safe (it falls through to
   the real binary).

**Also:** a branch name contains `/`, so `> "$SCRATCH/merge-$BR.log"` fails **before** the command
runs and bash reports the *redirect's* status as the merge's — the merge silently never happened and
counts were regenerated on an unmerged tree. Guard it by asserting `git merge-base --is-ancestor
origin/main HEAD` after any "successful" merge, not by trusting the exit code.

**Two environment facts for whoever is next.** The Keycloak `jtoye-dev` `core-api` client gained
`http://localhost:3102/*` and `http://localhost:3103/*` as redirect URIs so lanes could verify against
the shared backend; these are dev drift and are due for removal. And note the realms are
**`jtoye-dev`** and **`jtoye-customers`** — a probe against a realm named `jtoye` returns
`{"error":"Realm not found."}`, which reads exactly like "no drift found".


### 0.-3 Branch/worktree cleanup, and two hazards that were asserted rather than measured (2026-08-04, later)

**The tree is now `main` only.** 31 local branches → **1**; 10 remote → **2** (`main` + the open
dependabot #523); 16 worktrees → **1**. `.claude/worktrees/` is empty. No unpushed commits anywhere.

The 15 `wave1/*` branches were **fully absorbed** into `main` and are deleted. Do not go looking for
them. That verdict was contested and had to be settled with the right instrument:

- `git diff main...wave1/x` reported **185–1080 insertions "not in main"** and was quoted as evidence
  of a gap in the merge train. **It is the wrong instrument** — three-dot shows changes made *on the
  branch*, and stays large whenever `main` moves on. It structurally cannot answer "is this in main".
- The question that can be answered: take every line the branch **added** relative to its own
  merge-base, and look for it in `main`'s current file. Result: **13/15 fully absorbed**; `ci-276`'s
  one straggler is `docker/login-action@…v4.5.2` where `main` already has **v4.6.0** (branch behind,
  not ahead); `k8s-298-299-303`'s two are `Reviewed omission` entries for `CORE_API_INTERNAL_URL` and
  `NEXT_PUBLIC_KEYCLOAK_URL` — and `main` **supplies both as real env entries** in
  `k8s/base/frontend-deployment.yaml` + the goldens, closing #292/#293. Merging them back would be a
  regression: re-documenting as "acceptable omission" two vars that are now set.
- **The instrument was falsified before it was trusted**: against `origin/main~25` the same check
  reports **6993/7194 added lines absent (97%)**, against current `main` **0%**. It can say
  OUTSTANDING at scale, so ABSORBED is a real verdict, not a check incapable of failing.

**`git branch -d` cannot retire a squash-merged branch and says so misleadingly.** It refused every
`wave1/*` as *"not fully merged"* while their content was demonstrably in `main`. `-D` is required,
and is only safe **after** a content proof — ancestry is the wrong authority here. Contrast the 15
`worktree-agent-*` branches, which `-d` accepted because they *were* true ancestors. Two branch
families, same repo, opposite correct tool.

**A destructive step invalidates the audit that preceded it.** Deleting the 8 merged `batch/*` remote
branches stranded all 15 `wave1/*`: their commits were reachable only *through* those branches, so
they silently became local-only. The pre-deletion unpushed audit said "clean" and stayed true only
until the delete ran. **Re-run Phase 13 after any branch deletion, not just before.** They were pushed
as backup, then deleted again once absorption was proven — that round trip was correct, not waste.

**Two hazards in the previous handoff's orbit were measured and did not survive:**

1. **"A GSD update wipes `~/.claude/hooks|agents|skills`"** — this is `update.md` *prose*, not observed
   behaviour. GSD's installer last ran **2026-07-27 12:15**; custom files older than that survived it
   with original mtimes (`block-git-commit.sh` 04-15, `warn-version-stragglers.py` 06-20,
   `block-secrets.sh` 07-14, `carl-hook.py` 07-25, `skills/ui-ux-pro-max` 03-04). `gsd-user-files-backup/`
   was never refreshed on 07-27, so the backup step did not even run. Custom hooks were moved out to
   `~/.claude/guard-hooks/` on the strength of the prose, then **reverted** — `settings.json` is back to
   its pre-move hash `0d20e0fb`. Do not redo this. Agents/skills *cannot* be relocated anyway: they are
   found by directory convention, with no path registration in `settings.json`.
2. **`feature/faster-integration-tests-parallelism` was NOT orphaned work** — see §3, row corrected.

**One live-config change did land** (outside this repo): `block-main-branch.sh` now permits branch
**deletions** from `main` — narrowly, never for `main`/`master`, never alongside a commit/merge.
Shipped in dotfiles PR #64. It had a real bug on first use in anger: newline-splitting ran *before*
backslash-continuation folding, so every wrapped multi-branch delete was blocked. Fixed; harness
30 → 38 cases with 3 break arms (12/10/4 failures, each isolating one clause).

### 0.-2 The backup pipeline could not restore, and nothing in the repo could see it (2026-08-04)

Dependabot #525 bumped `infra/backups/Dockerfile` to `postgres:18-bookworm` while every server in the
tree stayed on **15.17**, and **every CI check was green**. Measured against the live server:

| | |
|---|---|
| `postgres:18` `pg_dump -Fc` against 15.17 | works — 469,421 bytes |
| `postgres:15` `pg_restore --list` on that dump | **rc=1** `unsupported version (1.16) in file header` |
| `postgres:15` on a pg15 dump (control) | rc=0 |
| `postgres:18` on a pg15 dump (direction) | rc=0 |

Tooling reads its own major and **older, never newer**. Backups keep succeeding and stop being
restorable by the server's own client — visible only during a recovery.

**Two things now close it, both in #529:**

- `scripts/check-postgres-major-parity.sh` — 25th gate, in `ops-contracts`. 8 declared sites in
  `scripts/gates/postgres-major-parity.conf`. Anchored on line prefixes, **not** a bare `postgres:`
  token, because `docker-compose.full-stack.yml:130` holds `jdbc:postgresql://postgres:5432/` (a bare
  token extracts **5432** as a major) and `infra/backups/Dockerfile:4` holds a *historical*
  `postgres:15-alpine` comment that must stay 15 forever.
- `scripts/restore-drill.sh` — **the first restore rehearsal this repo has run.** 40 tables / 8095
  rows / flyway 60/60 into a throwaway server. Deliberately not `check-*` (needs a live DB + Docker).

**`pg_restore --list` was never evidence.** It reads the archive HEADER and loads zero rows, and
`infra/backups/k8s-backup.sh:66` runs it *inside the image that produced the dump* — agreeing by construction twice.

**The drill is built around RLS blinding the verifier.** A count as a non-BYPASSRLS role with no
tenant GUC returns fewer rows silently, rc=0; count both sides that way and `0 == 0` passes over a
restore that loaded nothing. Defences: both sides BYPASSRLS, a `MIN_ROWS` floor, and a **control**
(`media_asset_aud`: BYPASSRLS **2224**, unpinned **741**).

WARNING — **`DB_USER` means two different things.** `.env` `DB_USER` = `jtoye_app` (**not** BYPASSRLS);
the CronJob's comes from secret key **`backup-username`** = `jtoye_backup`. Using the wrong one makes
`pg_dump` fail with *"query would be affected by row-level security policy for table customers"*,
which reads like a production fault and is not one. The drill asserts the role attribute first.

**#525 is not wrong forever — it is out of order.** That bump is REQUIRED to perform a 15 to 18
upgrade (the logical path dumps with the new tooling). PostgreSQL 15 is supported to **2027-11-11**,
18 to 2030-11-14. Bring it back with the server, the tag, the CronJob and both horizon rows moving
together, and run the drill before and after.


### 0.-1 The Wave-1 merge train (2026-08-04) — and the defects that existed ONLY in the merge

Six PRs merged in one sequence. `main` ended at `a9fb05bc`; **24/24 repo gates, 6/6 k8s gates,
127/135 E2E passing against a re-synced runtime.**

| PR | lane | closes |
|---|---|---|
| #522 | Lane C — a11y, `--primary` → orange-700 | #451 |
| #521 | Lane D — k8s, render-only | #293, #506, #271, #298 |
| #515 | the nightly E2E credential faults | refs #420 |
| #520 | Lane E — docs/CI, gate count 22 → 24 | #276, #337, #449 |
| #519 | Lane A — core-java | #278, #483, #489, #498, #501, #502 |
| #518 | Lane B — frontend | #295, #306, #490, #495, #504 |

**Two defects existed only where branches met. Neither branch's CI could see either, and no test
caught either — both were caught by a gate that only became capable of seeing them mid-train.**

1. **Two allowlist entries went stale on contact.** #298 widened the env-contract gate carrying
   reasoned entries for `CORE_API_INTERNAL_URL` and `NEXT_PUBLIC_KEYCLOAK_URL`, which no manifest
   supplied. #293/#506 — a *different branch* — then supplied exactly those two. Merged, the gate
   went `rc=1` with **zero contract violations**. Both entries' stated REASONS were falsified too:
   `CORE_API_INTERNAL_URL`'s claimed absence cost "a hairpin through the ingress, not a 502", but
   Next inlines `NEXT_PUBLIC_*` into the **server** bundle, so the fallback had already frozen to
   `http://localhost:9090`. It was a 502 path.
2. **`APP_PUBLIC_ORIGIN`** (Lane B × Lane D). Lane B added the reader; Lane D widened the gate to the
   frontend. Fixed by a **reasoned allowlist entry, not by supplying it** — it sits at the head of a
   *fallback* chain (`frontend/lib/public-origin.ts:87`) and absence falls straight through to `NEXTAUTH_URL`,
   which the manifest already supplies from `app-config/frontend.url`. Injecting it would have made a
   second source of truth for one origin.

**`docs/metrics.json` conflicted on EVERY lane and NEITHER SIDE WAS EVER RIGHT** — Lane E: ours 2093
/ theirs 2106 / truth **2107**; Lane A: ours 2142 / theirs 2107 / truth **2157**; Lane B: **2202**.
Each lane increments a different counter (Java / Go / Jest), so "take ours" and "take theirs" are
both wrong every time. The only correct move is `scripts/docs-freshness.sh --write` on the merged
tree, then re-sync the prose. **The same conflict carried README's build badge, and which side was
correct FLIPPED mid-train** once Lane E's 404-repo fix landed on main — a blanket resolution rule
would have silently reverted it.

**Three instrument failures worth carrying:**

- **A gate sweep globbing `scripts/check-*.sh` silently omits `k8s/scripts/`** — the six gates a k8s
  change actually exercises. This was hit in Lane D, written into that changelog entry as a lesson,
  and then **repeated two lanes later on Lane B**, where CI caught a real violation that should have
  been found locally. Writing the lesson down did not prevent the repeat; the resume block now runs
  both sweeps.
- **`rg` died mid-session with `claude native binary not installed` and the `|| echo` fallback
  printed a clean result.** A search that cannot run is indistinguishable from a search that found
  nothing. Use `git diff --name-only -- <pathspec>` with a **positive control** proving the query can
  return something.
- **A skip count with no credentials is not a measurement.** The first E2E run reported 48 skipped /
  21 undeclared — an artefact of not sourcing `.env`, not a regression. See the E2E row above.

**Five issues did not auto-close**: `Closes #A, #B, #C` closes only **#A**. #506/#271/#298 were closed
by hand; **#299 and #303 remain OPEN on purpose** — Lane D only made them *visible* as `OPEN DEFECT`
allowlist entries. **#299 is a live production gap** (customer-storefront realm unconfigured in every
k8s environment).

**Still true after the train:** Lane D's k8s work is **render-verified only** — no cluster exists
(no kind, no k3d, the `minikube` profile `jtoye` has no container, and the only kubectl context is the
employer's HS2 AKS). **The CrashLoop #271 describes was never demonstrated**, and #297 (Calico) stays
out. **#517 remains the blocker for #420** and is intermittent (2 of 3), so one green fresh-DB boot
proves nothing.

### 0.0 The parallel-agent run of 2026-08-03 — and the seven findings that were WRONG AS FILED

Eight specialised agents in isolated worktrees, assembled into **three** PRs, **12 issues closed**.
The batching was the point: the `Integration Tests (Testcontainers RLS)` job is path-filtered to
`core-java/**` and measured **45 min** on #509. Five backend issues went into that one run instead of
five; #508 and #510 reported the same job at **0 min**, path-skipped. Frontend-only PRs cost ~3 min
total, so **batching is worth it for `core-java/**` and buys nothing elsewhere.**

**The single most transferable result: SEVEN filed claims were falsified while being worked.** Not one
was caught by a test passing — every one came from running the fail direction first.

| issue | what the filing said | what was true |
|---|---|---|
| **#484** | `unless="#result == null"` cannot fire for `Optional.empty()` | **Premise false.** Spring unwraps the Optional *before* evaluating `unless` (`CacheAspectSupport:600-601` → `:552` → `:897`). Its recommended fix throws `EL1004E` on every SUCCESSFUL lookup and disables the products cache — **and the issue's own acceptance criterion would have gone GREEN on that broken tree**, because a disabled cache also holds zero entries. Closed as invalid; a regression guard shipped instead |
| **#444** | replay 404s for the same reason as the log | **Half true, and the false half is the dangerous one.** Un-keyed replay was broken; **keyed replay PASSED on the unfixed tree** — and the keyed path is the one the frontend api-client uses, since it auto-retries with a key. A fix validated only there would have gone green over a live defect |
| **#444** | `TenantSetLocalAspect` "never fires" | It *does* fire, then returns early on `!isActualTransactionActive()`. `SimpleJpaRepository` opens its transaction **inside** the Spring Data proxy, after the advice returned. That is why annotating the *caller* fixes it and "make the aspect pin harder" would not |
| **#440** | unauthenticated spec survives to **production** | **False.** `OpenApiConfig` is `@Profile("!prod")`, prod sets `api-docs.enabled: false`, and anonymous reads need `looksLocal && !isDeployedProfile`. The real exposure was **staging**, which the finding never mentions |
| **#448** | 105 responses point at success DTOs | **Misread its own number.** 105 is the *total* 4xx/5xx; **96** pointed at success DTOs, 9 declared no body. Two sub-claims also false |
| **#500** | 3 bare `notFound()` sites in one controller | **12 sites across 7 controllers.** Fixing only the named one would have made #448's spec promise a body that 9 other sites never send |
| **#463** | `/shop` is a server-rendered 12 ms control | **False.** `frontend/app/shop/page.tsx:1` is `"use client"` and fetches on mount — the 12 ms was the HTML shell. So there was **no** server-rendered control in the comparison, and the owner's *"the same applies to all pages"* is **broader** than the issue recorded (#507) |

**This is now the fourth, fifth, sixth and seventh instance of the pattern §2.1 already records twice**
(SEC-01/A1's falsified root cause, F-M7/#442's falsified location). **Re-verify before implementing is
not advice here; it is the difference between a fix and a no-op over a live defect.**

### 0.0.1 Parallelism: what the previous handoff got wrong, and the two rules that made it work

§2.6 said *"do not parallelise this cluster."* **The file sets refute it.** The only genuine collision
was #467 ↔ #463, which both rewrite `frontend/app/shop/orders/page.tsx` — those went to one agent.
#459 and #458 are disjoint from those and from each other, and **all branches merged with zero
conflicts**. Check the actual file sets before declaring a cluster unparallelisable.

Two coordination rules did the real work, and both **prevent** conflicts rather than resolving them:

1. **No agent may touch `docs/metrics.json` or `docs/CHANGELOG.md`.** Regenerate once per lane at
   assembly. A per-branch edit collides on the same lines and silently deletes a sibling's.
   Corroboration worth keeping: in both lanes the agents' *independent* predictions summed to exactly
   what `docs-freshness.sh --write` produced (+15/+25/+10 → 548; +9/+9/+4/+19 → +41).
2. **Where two agents must share a file, give each an explicit region.**
   `docker-compose.full-stack.yml` was split `environment:` (#508) vs `ports:` (#510) — **merged with
   no conflict.**

### 0.0.2 Four traps this run hit in practice

- **`Closes #A, #B, #C` closes only #A.** GitHub needs the keyword before *each* reference. #508 read
  `Closes #459, #463, #467`; #459 closed and the other two silently did not. **Check issue state after
  a merge** — and note `gh issue view` lags a merge by seconds, so a stale OPEN read may just be lag.
- **A line-number citation breaks whenever anything above it moves, and the shifts COMPOUND.** mailhog
  was cited at 541; it became 552 after #508, 591 after #510 alone, **602 combined**. Re-point by
  locating the cited *subject*, never by applying an offset.
- **`rg`/`grep` do not exist inside `bash script.sh`** — they are shell functions. A citation-repointing
  helper reported success while changing **nothing** (`git status` empty), and a disclosure sweep
  returned a confident 0 from `command not found`. Use `/usr/bin/grep` inside scripts, and seed a
  control.
- **Docker's `LastTagTime` is UTC; `git log %cI` is local.** An ad-hoc staleness comparison called
  edge-go STALE on a **59-minute** gap that was purely the offset. Normalise to epoch — or just run
  `check-runtime-freshness.sh`, which already does.

**And the process miss worth repeating:** the CI `docs-freshness` job runs **seven** scripts, not the
three obvious ones. Verifying `docs-freshness` + `check-doc-metrics` + `check-doc-citations` locally
and calling it green missed `check-handoff-contract`, which then went red in CI. **Run the workflow's
real step list.**

### 0.1 The QA council's findings are not in the repository

`/qa-council` run **`disc-20260802-121732`** audited the six phases shipped since the 2026-07-14 run
(22 comms, 23 vendor-scoped access, 24 CoW media, 25 mutating MCP, 26 k8s overlay, 27 ops + the
RabbitMQ 3.12→4.3 replacement). It is the first full council since then.

**Where it lives:** `.qa-council/disc-20260802-121732/` — `findings.json`, `plan.md`,
`QA-COUNCIL-REPORT.md`, and per-lane evidence under `evidence/`. **`.qa-council/` is in
`.gitignore`.** One `rm` destroys it, and no clone has ever contained it.

**`.qa-council/LATEST` still points at `disc-20260714-162412`** — the July run. It will not lead you
to the August one. Read the directory listing, not the pointer.

| | |
|---|---|
| Fixed | **F-C1 + F-H1** cross-tenant write BOLA + list leak (#433 MERGED) · **F-M1** optimistic-lock 500 (#434 MERGED) |
| Group A remainder — **all FILED 2026-08-02**, clustered by root cause as the council adjudicated | **#444** F-H4 webhook delivery log (missing tenant GUC) · **#445** F-H3 raw-image endpoints bypass the Phase-24 pipeline · **#446** F-M3 hand-rolled dish modal · **#447** F-H8/F-H9 SEO · **#448** F-M5/F-L1 ProblemDetail · **#449** F-M8 17 docs-broken · **#450** the small-broken copy set · **#451** F-M4 419 axe violations · **#452** F-H5/F-H7 lifecycle dead-ends · **#453** F-H6 · **#454** F-M6 CLS |
| Group B → Phase 28, **SEC-02 filed; #442 now CLOSED** | **#438** F-C2 dev Postgres bind · **#439** F-C3 Grafana default creds · **#440** F-H2 spec advertises a tenant-override header · **#441** F-H10 infra port binds + mail archive · **#442** F-M7 actuator/OpenAPI/edge — **CLOSED** by PR #472, and two of its three claims were FALSIFIED (§2.1). The other four OPEN, `security` + P1/P2 labelled, **deliberately sanitised** (§2.1) |
| Group C → tracked | allergen text↔mask = #427 (still OPEN) · storefront social signup = #432 (still OPEN) · the low-severity set |

**The single most important result, worth carrying verbatim.** Phase 28's SEC-01 was written as
*"re-verify pentest A1"*. A1 is a real Critical cross-tenant write BOLA — but its **filed root cause
("missing `tenant_id` / RLS") is FALSIFIED**: both tables carry `tenant_id` with ENABLE + FORCE RLS.
The real cause was service-layer authorization in `ShopAccessService.require()`, and it also affected
`POST /products`. **Implementing the filed fix would have shipped a no-op over a live Critical.**
Re-verify, don't implement, is the whole reason that finding got closed correctly.

**The durable fix is not "look harder".** Either give the council run a tracking issue per Group, or
un-ignore a findings summary. Neither is done.

### 0.2 Instruments that lied, this session

| what I measured with | what it actually did |
|---|---|
| **HANDOFF.md §3's "one `shop_announcements`, one `shop_promotions` row"**, quoted to the owner as current | **Wrong by a day and wrong about the contents.** There were **6** rows, and **4 were created that same morning** as `SEC01-PROBE-*` / `VERIFY-PROBE-XT` attack probes — the council's `state.json` recorded them as *"DELIBERATELY RETAINED … evidence for SEC-01"*. A destructive step was approved on stale figures. Snapshotted to `evidence/sec-A1-residue-rows-preclean.txt` before deleting. **Re-run a handoff's measurement before repeating its numbers as current** |
| `BUILD SUCCESSFUL` from a `--tests`-filtered gradle run | Means nothing on its own — it is also what running **zero** tests looks like. Read `tests="N" failures="N"` out of `build-local/test-results/`. (`core-java/build/` is a **stale 2025-12-27 artifact** reporting 3 false failures — the live dir is `build-local`) |
| the changelog entry I wrote for F-M1 | **Could not satisfy its own gate.** `check-changelog-contract` keys on the merged PR's own `(#NNN)`, which does not exist until `gh pr create` prints it. The entry merged as #434 and only then went red. **Add the number after `gh pr create`, before merging** |
| `jsonPath("$.code")` in a standalone-MockMvc test | Failed `PathNotFoundException` against a handler that is **correct in production**. `new ObjectMapper()` does not register `ProblemDetailJacksonMixin`; `Jackson2ObjectMapperBuilder.json().build()` does. Same fix `RateLimitInterceptorTest` carries for #413 |
| 19 gates, read as a flat pass/fail | Two went red for reasons that are **correct behaviour**, not regressions: `check-runtime-freshness` after any core-java source change, and `check-alert-metrics` after any rebuild that recreates core-java. Both name their own remedy in their output. Expect them |

---

## 1. What landed

### 1.1 #434 — a lost optimistic-lock race is a 409, not an opaque 500 (F-M1 / INT-03)

`ObjectOptimisticLockingFailureException` matched **none** of `GlobalExceptionHandler`'s 30 handlers,
so it fell to the `Exception.class` catch-all: `500 .../errors/internal`, *"An unexpected error
occurred"*.

**Nothing was actually failing, which is the point.** 8 barrier-synchronised `confirm`s on one PENDING
order measured `{200: 1, 500: 7}` while data integrity **held** — exactly one transition applied, final
state consistent. The same duplicate and illegal transitions run **sequentially** already returned a
typed `400`. The race was the only thing separating a correct 400 from an opaque 500.

**Why it mattered operationally:** a KDS is a shared shop screen, so two staff bumping one ticket is
the normal case — and the frontend api-client **auto-retries on 5xx**. A 500 turned ordinary contention
into a retry storm against a row whose write had already succeeded.

Declared on the **`OptimisticLockingFailureException` superclass**, not Hibernate's subclass, so a
Spring-translated `StaleObjectStateException` and any future `@Version` entity are covered — one root
cause, two reported symptoms (INT-03 and the security lane's A1-del), one handler.

Detail is a **fixed string**: the provider message names the table and the `version` column, so it is
logged at WARN and never returned.

**Functional proof, same instrument as the finding, reproduced 3× on 3 distinct orders — the last on
the merged-main runtime:**

| | codes | types | final_status |
|---|---|---|---|
| before (council, 13:20) | `{200: 1, 500: 7}` | `errors/internal` | CONFIRMED |
| after | `{200: 1, 409: 7}` | `errors/concurrent-modification` | CONFIRMED |

Recorded at `.qa-council/disc-20260802-121732/evidence/fm1-optlock-409-postfix.txt`.

Falsified with opening and closing clean arms: clean 4/4 → break arm (handler de-registered) **3 of 4
fail, control arm still passing** → restore verified **by `git hash-object`** → closing clean 4/4.
Full unit suite **870 tests / 0 failures** (council baseline 866 + these 4, nothing else disturbed).

### 1.2 #435 — the `.idea` residue, and the changelog citation

Four IntelliJ database-tooling paths added to `.gitignore` (`dataSources.xml`,
`dataSources.local.xml`, `dataSources/`, `db-forest-config.xml`). The tree had been permanently
`dirty=4` since 2026-08-01, and this document's own resume block carried a footnote telling the reader
to discount it — **which trains you to discount a dirty tree at exactly the moment it is the signal.**

**Four exact paths, deliberately not a blanket `.idea/`**: the repo tracks `.idea/vcs.xml`,
`.idea/gradle.xml` and `.idea/go.imports.xml` on purpose. Control arm: `check-ignore .idea/vcs.xml`
still returns rc=1 after the change.

Also carries the `(#434)` citation fix described in §0.2.

### 1.3 The changelog gate was red on `main` before this session started

`check-changelog-contract` was **rc=1 on the first sweep of the session, before any change** — #433
merged 2026-08-02 with no entry, after #430 recorded 19/19 green on 2026-08-01. Both were true at the
time; the gate is not flaky, the world moved. Backfilled in #434; the gate now cites **22 of 22**.

---

## 2. Open items — this session's

### 2.1 The pentest backlog is now TRACKED, and A1 is ANSWERED

`SECURITY-FINDINGS.md` (untracked, git-excluded; evidence at
`~/strix_runs/host-docker-internal-9090_d8c0/`, chmod 600). Status changed this session:

- **A1** — **RESOLVED as a finding, not as filed.** Real Critical, false root cause, fixed at the
  service layer in #433. SEC-01 is answered; do not re-run it.
- **A2** (request-header tenant fallback, CVSS 8.2) — **still real in code, dev-scoped.**
  `TenantFilter` is `@Profile({"dev","local","test"})` and k8s sets `SPRING_PROFILES_ACTIVE=prod`.
  Compose runs `dev`, so it is live locally. `core-java/src/main/java/uk/jtoye/core/config/OpenApiConfig.java:50` still **advertises** the scheme
  unconditionally — that is council F-H2, now **#440**.
- **B1/B2/C1** — the Compose stack publishes infra ports with no bind address. Inventory deliberately
  not reproduced here; see the local evidence file. These are council F-C2 / F-H10, now **#438** and
  **#441**.

> **Disclosure note, added 2026-08-02.** The port inventory and the header name were spelled out in
> this section in #430 and carried forward unreviewed into #436. They are removed from the current
> file, but **`git log -p HANDOFF.md` still contains them** — removing text from HEAD does not remove
> it from a public repository's history, and this is recorded rather than quietly edited. Treat it as
> already-public and rotate on that basis; do not treat this edit as a containment.

**SEC-02 is COMPLETE as of 2026-08-03: all five Group B findings are CLOSED** — **#438 is CLOSED**,
**#439 is CLOSED**, **#441 is CLOSED** (all PR #510), **#440 is CLOSED** (PR #509), **#442 is CLOSED**
(PR #472). The audit is no longer one `rm` away from being lost, and it is no longer outstanding.

**#440's finding was partly FALSIFIED when it was worked** — a third instance of the pattern this
document already records twice. *"Survives to production"* is **false**: `OpenApiConfig` is
`@Profile("!prod")` and prod sets `api-docs.enabled: false`. *"Unauthenticated"* is **false** for a
deployed environment: anonymous spec reads are permitted only when `looksLocal && !isDeployedProfile`.
What was genuinely exposed is **staging** — which the finding never mentions. Re-verify before
implementing; the filed location was wrong, exactly as F-M7's was.

**#438, #439 and #441 are being closed by PR #510**, which binds every infra port to loopback behind
`${JTOYE_BIND_HOST:-127.0.0.1}` and rotates the monitoring credential live. Note its gate,
`scripts/check-infra-exposure.sh`, is **not wired into CI** — part of it needs a live broker, so it
could only ever VOID on a runner, the same reason `check-runtime-freshness` stays out. **Nothing
currently stops someone re-adding `0.0.0.0` in a PR.**

**They are deliberately sanitised, and that is a constraint on whoever works them.** This repository
is **public**, which is the same reason `SECURITY-FINDINGS.md` was git-excluded. The issues carry the
component, the problem class, the scope, the fix direction and falsifiable acceptance criteria — but
**no reproduction commands, no port/credential pairings and no role attributes**. Verified after
filing by scanning **GitHub's stored bodies**, not the local drafts, with a control token proving the
scan was not blind. The detail lives in `.qa-council/disc-20260802-121732/evidence/sec-findings.md`.
**Do not paste repro steps into these issues when working them.**

**#442 is CLOSED (PR #472) — and the reason to keep reading it is now different.** This section used
to say F-M7 was the one Group B finding reaching production, because its `permitAll` entries were not
profile-gated. **That reasoning was half wrong, and re-verifying before implementing is what caught
it.** Two of its three claims were falsified:

- *metrics unauthenticated in prod* — **FALSE**: prod binds actuator to a separate
  `management.server.port` and the k8s Service publishes only the app port. An existing test already
  proved it both directions. Implementing the filed fix would have authenticated an unreachable
  endpoint.
- *OpenAPI unauthenticated in prod* — **FALSE for the default config**: springdoc is off there
  (`SWAGGER_ENABLED:false`). Gated anyway as defence in depth, recorded as such.
- What was genuinely exposed: the **edge gateway**, and **staging** — which the finding never
  mentions, and which had no management port, `show-details: always`, *and* springdoc explicitly
  enabled.

**The transferable lesson, now twice in this file:** a council finding names a symptom and guesses a
mechanism. SEC-01/A1 had a falsified root cause; F-M7 had a falsified location. Re-verify, then fix
what is actually true — and say which parts were wrong rather than quietly fixing something else.

### 2.2 The cross-tenant DB residue is GONE

All 6 rows deleted via `CLEAN_RESIDUE=1 bash scripts/seed-e2e-fixtures.sh`, verified 0 remaining **with
a blindness control** (3 promotions / 1 announcement still visible, so the verification query was not
simply seeing nothing). Snapshot retained at `evidence/sec-A1-residue-rows-preclean.txt`.

Previous handoffs described this as 2 rows. It was 6. See §0.2.

### 2.3 The three most load-bearing planning files are still ungated

`ROADMAP.md`, `REQUIREMENTS.md` and `STATE.md` are covered by **no gate** — unchanged from #430. A
`check-state-freshness` asserting `STATE.md`'s current phase against `ROADMAP.md`'s progress table was
recommended in `260801-ths`'s SUMMARY and is **still not built**.

### 2.4 Twelve findings the owner got by USING the app — none of which any audit found

Reported 2026-08-02 from live use, verified against the tree, filed as seven issues. Read this section
before §0.1's: the council findings are mostly correctness and infrastructure; these are the product.

| issue | items | what it is |
|---|---|---|
| **#457 is CLOSED** (PR #466, 2026-08-03) | 1b, 9 | Public header was **session-blind**. Browser-falsified and confirmed — **and it was hiding #465** (below) |
| **#458 is OPEN** (PR #508, 2026-08-03 — **partial**) | 1a, 2, 3, 4 | Nav gating shipped, desktop **and** mobile sheet, plus `/track` auto-population. **Stays open for the dispatch notification**, which is not a copy change: `OrderStatus` has no `DISPATCHED` value and `OrderStateMachineConfig` has no such edge. That gap surfaced a separate live defect — DELIVERY customers emailed *"ready for collection"* — filed as #502 |
| **#459 is CLOSED** (PR #508, 2026-08-03) | 6 | Cart payloads now carry the owning `sub` and are cleared on sign-out; anonymous carry-forward preserved. The naive fix — clearing in `clearMarker()` — satisfies the headline criterion and **breaks** anonymous carry-forward and the post-order clear, demonstrated by break arm |
| **#460 is OPEN** | 7 | **No concept of locality.** A phase, not a patch. P1 |
| **#461 is OPEN** | 8, 10 | No payment processing; pay-on-collection must become channel-issued payment links. P1 |
| **#462 is OPEN** | 11 | No second factor, no verified contact channel |
| **#463 is CLOSED** (PR #508, 2026-08-03) | 5 | `/shop/orders` is now a server component: time-to-content **2562 ms → 1001 ms**, CLS **1.0149 → 0.0052**, client fetches on load **1 → 0**, at the repo's own throttled mobile profile. Its premise was wrong — `/shop` is **also** `"use client"`, so there was no server-rendered control in the comparison; the systemic half (20 more pages) is #507 |
| **#467 is CLOSED** (PR #508, 2026-08-03) | — | The orders API 502 rendered as *"No orders found"*. Config alone could not fix it: `NEXT_PUBLIC_API_URL` is inlined at build time into the **server** bundle too, so only a non-`NEXT_PUBLIC_` variable works. k8s has the same shape and is UNMEASURED — #506 |

> ⚠ **#463 and #467 had to be closed BY HAND.** #508's body read `Closes #459, #463, #467`, and GitHub's
> auto-close requires the keyword before **each** reference — a comma-separated list after one `Closes`
> closes only the first. #459 closed; the other two silently did not. The same shape was sitting in two
> sibling PRs and was fixed there before merge. **Check issue state after a merge; do not assume the
> body did what it reads like it did.**

Item 12 (README review) went as a **comment on #449**, which already owns the entry-doc surface.

**Four had a different mechanism than the symptom — this is the part worth carrying:**

- **"Going home logs me out" was BOTH — and the browser run is what separated them.** ~~Not yet
  browser-proven~~ — **proven 2026-08-03, and the diagnosis above was half right.** `PublicHeader`
  really did contain zero session references, and the session really did survive the navigation
  (control arm: returning to `/shop` restored signed-in chrome; `/shop/orders` resolved the identity
  server-side). But the same run found the session **also dies on a 300s timer regardless of
  activity** — filed as **#465**, fixed with #457 in PR #466. Both are CLOSED.

  **This is the entry to carry.** Insisting on the browser arm before writing code is what turned one
  symptom into two defects. Had the header been fixed on the strength of the filing alone, the report
  would have persisted and read as unfixed — after five minutes the header would correctly say
  "Sign in", because the customer genuinely was logged out. The refresh token had been sitting
  HttpOnly for 30 days, never redeemed; the only `grant_type: "refresh_token"` in the frontend was
  `auth.ts`, the **operator** path on a different realm.
- **The basket cannot cross shops** — `cart-provider.tsx` keys `jtoye-cart-{shopSlug}`, the payload
  carries its own slug, and the parser rejects a mismatch (`:57-61`). It crosses **identities** because
  `customerLogout()` clears four keys (`frontend/lib/customer-auth.ts:94-98`) and not the cart. One device, public
  catalogue data, no PII, no server-side exposure. Real, bounded — and the desirable
  anonymous→signed-in carry-forward must survive the fix.
- **Payments are not missing code.** `PublicStorefrontService:508-521` falls back to COD *by design*
  when the provider is unconfigured. This is the empty-`STRIPE_API_KEY` state (§4). First action is
  keys, not code.
- **The slow page is not a slow query.** Measured: orders API **13–17 ms** warm, `/shop`
  server-rendered **12 ms**. `/shop/orders` is `"use client"` end-to-end, so the spinner covers bundle
  + hydration + fetch. "Optimise the query" would have been wasted work.

**#460 is re-ranked above the rest.** `navigator.geolocation` = **0**, `deliveryRadius` = **0**. Shop
`latitude`/`longitude` exist on the DTO (`:657-658`) and **nothing computes distance**; postcode search
is a substring match over name/description/address. Birmingham and London see the same vendor list.
Vendor visibility, delivery feasibility, distance-based fees and the local-SEO work (#447) are all
downstream of a locality model that does not exist.

**The process lesson.** Every gate was green, 23 council issues had just been filed, and none of this
surfaced. The council audits what the code *does*; the owner used what the product *is*. **Keep doing
this by hand** — no gate in this repo would have caught a single one of the twelve.

### 2.5 The eight-PR train fixed ten issues that are all INVISIBLE at this data scale

**The owner looked at the running app after the train merged and still saw the problems they had asked
about.** They were right, and the reason is a selection error worth not repeating.

The brief was "issues resolvable without colliding with each other or a concurrent session."
Non-collision selects for *small and isolated*, which is close to the inverse of *user-visible*. The
result: ten issues CLOSED, none of which change what anyone sees in normal use.

| Issue | Why it is invisible today |
|---|---|
| #302, #274, #418, #287 | CI / infrastructure only |
| #279 | forward-looking hardening — no field rendered today was ever vulnerable |
| #390 | only observable in a delete/edit race |
| #288 | needs a non-GROUP_ADMIN with ZERO shop grants; `shop_staff` has 2 rows, both GROUP_ADMIN/JIT |
| #290 | needs a `user_directory` row with NULL/empty `display_name`; all 4 rows have one |
| #282 | the cap was 200 and the tenant has 4 shops |
| #445 | forward-only; existing objects keep their raw bytes, EXIF and client-declared Content-Type |

Measured against the dev DB on 2026-08-03, not assumed. **This is also why the browser verification of
#476 had to force all three states** — two by Playwright route interception, one via the PR's own
`NEXT_PUBLIC_SHOPS_PAGE_SIZE=2` knob against the real backend. Nobody has yet seen any of the three
arise from real rows; a DB-seeded run is still owed.

**Nine follow-ups were filed** for work the agents found and correctly refused to do: 483, 484, 485,
486, 487, 488, 489, 490, 495. Two carry warnings that matter more than the fix:

- **483 says do NOT apply #287's fix to `SyncService`.** It carries the identical
  `@CacheEvict(allEntries = true)`, but that path genuinely upserts (`findByName`/`findBySku` +
  `orElseGet` at `core-java/src/main/java/uk/jtoye/core/sync/SyncService.java:90,105`), so removing the eviction there ships stale reads. Only
  its *radius* is wrong.
- **487 is UNMEASURED and says so in its title.** Its first step is a read-only query, not a fix.

### 2.6 What the next session should actually pick up

**Superseded on 2026-08-03 — this section's advice was acted on, and one line of it was wrong.**

Of the seven the owner reported by USING the app: **#467, #463 and #459 are CLOSED** (PR #508),
**#458 is partially done** (nav gating shipped; dispatch notification deferred). **#460, #461 and
#462 remain OPEN and were deliberately not staffed** — none is an engineering task yet. #460 needs an
ADR, #461 is blocked on Stripe test-mode keys, #462 needs a product decision; two of those three are
already §4 blocking decisions. Putting agents on them would have produced code prejudging decisions
that have not been made.

**"Do not parallelise this cluster" was overbroad, and the file-level evidence refutes it.** The only
genuine collision was #467 ↔ #463, which both rewrite `frontend/app/shop/orders/page.tsx` — those two
went to a single agent. #459 (`cart-provider.tsx` + `customer-auth.ts`) and #458 (`storefront-nav.tsx`
+ `app/track/page.tsx`) are file-disjoint from those and from each other, and all three branches
merged with **zero conflicts**. Check the actual file sets before declaring a cluster unparallelisable.

**What was right, and is worth keeping:** *verify each in a real browser against the live stack rather
than trusting jsdom.* That is what turned #458's nav work into the discovery that no `DISPATCHED`
state exists (#502), and what proved #459's naive fix breaks anonymous carry-forward.

**Two coordination rules the parallel run established**, both of which prevented conflicts rather than
resolving them: agents were barred from `docs/metrics.json` and `docs/CHANGELOG.md` (regenerated once
per lane at assembly instead), and where two agents had to share a file they were each given an
explicit region — `docker-compose.full-stack.yml` was split `environment:` vs `ports:` and merged
clean.

---

## 3. Carried forward — still true, not re-measured unless noted

- **#418 is CLOSED, its mechanism is now known, and this document had it wrong.** The line that used to
  sit here said the suite does *not* race its own `@Scheduled` flusher, because `@DynamicPropertySource`
  parks both intervals at 24h. **That reasoning is refuted by PR #480**, now merged:
  `@Scheduled(fixedDelayString=…)` leaves `initialDelay` at **0**, so the first execution fires
  at context refresh *regardless of the interval*. Parking suppresses the second run onward, never the
  first — a probe with both intervals at 86400000 still found **10 live scheduled tasks**. The earlier
  supporting evidence was vacuous too: a flush pass over an empty tenant list logs nothing, so "no
  scheduled trace in the failure window" was never absence of execution. Amplified reproduction:
  300 samples → 72 failures, then 25; with the fix and the amplifier still on, 300 → **0**.
  `NoScheduledTriggersTestConfig` removes the `internalScheduledAnnotationProcessor` bean so nothing is
  ever scheduled; no sleeps, no widened timeouts, no production code touched.
- **A merge with no changelog entry reddens every *other* open PR, not the one that caused it.**
  `check-changelog-contract` ranges over **merged history** (`FLOOR..origin/main`) while reading the
  changelog from the **branch's** copy. #473 and #475 merged without entries, so from that moment every
  open PR failed the required `docs-freshness` job with `C-1 PR #473 … no entry` — a failure naming a
  PR the author had never touched. **#491 is CLOSED** and backfilled both entries. Three consequences
  worth keeping: the gate is satisfiable from inside any PR (it reads *your* changelog), so it is **not**
  the "gate forbids its own remedy" shape; a merge train must add the entry **in the merging PR**,
  because the cost lands on everyone else the instant it merges; and **two sessions diagnosed and fixed
  this independently within the hour** — the second only discovered the first when `gh pr merge`
  refused. Before writing a fix for a *shared* red gate, re-read `origin/main`.
- **The standing policy that came out of it: every feat/fix PR carries its OWN changelog entry, added
  before it merges.** The gate keys on the **squash-merge subject's trailing `(#NNN)`** —
  `PR_RE='\(#([0-9]+)\)$'`, anchored to end of line — so a branch whose subject ends `(#279)` will be
  demanded as **#478**, its merge number, not its issue number. Verified, not assumed:
  `grep -oP '\(#([0-9]+)\)$' <<< '…(#418) (#480)'` returns `(#480)`. The number exists the moment
  `gh pr create` prints it, so "you cannot write it before the PR exists" is false — only "before the
  PR is *opened*" is. Each entry in the #480/#474/#478 train was falsified by building the squash
  commit locally (`git merge --squash` + a commit carrying the predicted subject) and running the gate
  with `CHANGELOG_BASE_REF` pointed at it: clean → PASS, citation mangled → **FAIL naming that exact
  PR**, restored → PASS. Note the gate **cannot** check the citation pre-merge from the branch itself
  (its range ends at `origin/main`, where the PR is absent), so a green run on the branch proves
  nothing about it — the simulation is the only real evidence.
- **"Resolve doc conflicts by taking main's copy" is WRONG for `docs/CHANGELOG.md`, and nothing
  would have caught it.** The blanket rule is right for `AGENTS.md`/`CLAUDE.md`/`README.md`/
  `docs/metrics.json`, which are regenerated straight afterwards. It is wrong for the changelog:
  once a sibling PR merges, both entries want the same insertion point under `## [Unreleased]`, so
  taking main's copy **silently deletes your own entry**. Hit on #476's re-rebase after #479 landed.
  The gate cannot see it — its range ends at `origin/main`, where your PR is absent — so the branch
  stays green and the omission only surfaces *after* merge, reddening everyone else. Resolve that one
  file by taking main's copy and **re-inserting** your entry, then assert both citations by content
  (`grep -c '(#476)'` and `grep -c '(#479)'` each `1`) before continuing the rebase.
- **Never run a gate from the main checkout — it is usually BEHIND `origin/main`.**
  `check-changelog-contract` resolves its commit *range* from `origin/main` but reads
  `docs/CHANGELOG.md` from the **working tree**. Run from a tree two commits behind, it compared a
  current range against a stale file and reported #474 and #480 as uncited — both of which were
  present and correct. Measured 2026-08-03; the same run from an up-to-date tree was rc=0, 30 of 30.
  A gate reading a stale tree fails in whichever direction the staleness happens to point, which is
  **worse than not running it**, because the output looks authoritative. Before trusting any verdict,
  pass or fail, assert the tree's identity: `git rev-parse HEAD` vs `git rev-parse origin/main`.
- **A new E2E spec landed un-run, and the skip-budget gate caught it.** #456 added
  `frontend/e2e/marketing-dish-scroller.spec.ts`; `check-e2e-skip-budget` now returns **rc=2 VOID**
  because the stored report is older than the specs it describes. That is the gate working — a stale
  report certifying a skip set that no longer exists is a documented trap here. It is also the most
  concrete argument yet for dispatching the nightly job below: it would have run this spec.
- **#420 is CLOSED** (2026-08-05). Its CI half is satisfied: `e2e-nightly.yml` now runs and is green —
  see §0.-6. Its successor is **#547** (the 7 declared-but-unverified skips). The line this replaced
  read *"has still never run"*, which was true when written and stopped being true two days later.
  Per-PR CI still runs 2 of 126, and that half is unchanged. Corollary still live: `Integration Tests`
  passed in **6s** on #435 — path-filtered, a skip wearing a tick. The same job took **43m51s** on
  #434, which is what running it looks like.
- **The refund E2E stays skipped deliberately** — `Stripe.Refund.create` with an empty key. Needs keys,
  not a fixture.
- **`NoOrdersCreated` goes blind after any rebuild that recreates core-java.** Remedy:
  `bash scripts/seed-order-metric.sh`. Hit twice this session. Expect it every time.
- **Fixtures decay by design** — seeds write every instant relative to now. Re-run the seed before
  suspecting the product.
- **No `v2.3` git tag** — latest is `v2.2` while `build.gradle.kts` reads `2.3.0`. GTM-01.
- **`financial_transactions.order_id` has no FK to `orders`**; 3 rows point at deleted orders.
- **Toolchain: 3 DRIFT + 1 UNKNOWN**, none applied — **re-measured 2026-08-04** (was 2 DRIFT on 08-03;
  the conda target moved and copilot appeared). `conda` 26.1.1→**26.7.0**, `ms-fabric-cli` 1.2.0→1.6.1,
  `@github/copilot` 1.0.77→1.0.78. `antigravity` is UNKNOWN because it is a **manual** channel the probe
  cannot query — a recorded decision, not a gap. `docker-ce` restarts the daemon — stack down first.
  Housekeeping surfaces drift and does not converge it; apply via `update.sh --tier N` deliberately.
- **`.claude/worktrees/` was not gitignored: 9 live agent worktrees, 70,498 untracked files**, each a
  full working copy holding one of the merge train's branches. A plain `git add .` in this checkout
  staged all of it. Fixed in **#482**, scoped to `.claude/worktrees/` and **not** `.claude/`, because
  the project convention reserves `.claude/skills/` for tracked project skills.
  **As of 2026-08-04 the directory is EMPTY** — all 15 worktrees removed (see §0.-3). Each was verified
  clean (0 uncommitted, 0 untracked) and unheld by any process (`lsof +D`) *before* removal, because the
  absorption analysis that cleared their branches examined **commits only** and is blind to a dirty
  working tree. The gitignore fix stays: the hazard returns the moment an agent run recreates them.
  **The habit matters more than the fix.** The same hazard fired earlier the same day at 369 lines
  through a *named-path* `git add AGENTS.md`, which merged another session's uncommitted work as
  #469 and had to be reverted by #470 — a named path proves *which file*, never *which lines*. So:
  **`git diff --staged` before every commit here**, and treat `N insertions / 0 deletions` on a file
  you only edited as content that arrived from someone else.
- ~~**Orphaned and worth someone's attention: `feature/faster-integration-tests-parallelism`**~~
  **RESOLVED 2026-08-04 — it was not orphaned work, it was superseded work, and it is deleted.**
  Its single commit `c142b90c` is the *earlier unpushed attempt* that **#512 (`d95239dc`) explicitly
  supersedes** — #512's own message names it: *"an earlier unpushed attempt used
  `availableProcessors()/4` … INERT ON CI"*. Pushing it would have opened a PR that **conflicts** in
  `core-java/build.gradle.kts`, and whose only merge-in-its-favour reverts the divisor `/2 → /4`,
  re-breaking the speed-up on CI. Proven before deleting: `git merge-base --is-ancestor` → not an
  ancestor (so genuinely not in `main`), `git merge-tree` → CONFLICT on exactly the lines #512 rewrote,
  and `main` line 218 already reads `availableProcessors() / 2`. The 47-minute measurement stands; #512
  is the fix for it.

---

## 4. Carried forward from #430 — the blocking decisions

Phases 29–32 do not start until these land. **None are engineering tasks.**

| decision | state |
|---|---|
| **Production domain** | **SETTLED 2026-08-04, measured — the old row was wrong.** `jtoye.co.uk` **is registered** (owner-confirmed; NS `dns1/dns2.registrar-servers.com`, A `162.255.119.30`) and **resolves**: `http://jtoye.co.uk` → **200**, redirecting to `www.` on `72.251.11.125`, with an **empty `<title>`** — a registrar placeholder, not the app. **`https://` FAILS: no TLS cert.** So the blocker is no longer registration; it is **TLS + repointing DNS at real hosting**. Separately still true: **`olajay.co.uk` resolves to nothing** (`dig +short A` empty, with `google.com` answering on the same run to prove the resolver works), and `FRONTEND_PUBLIC_*` still point at it. Do not flip `DEPLOY_*_ENABLED` on "it's registered" — a 200 from a parking page is not a deployment target. |
| **Hosting target** | Your Azure sub is `c483d353`; the employer HS2 sub is off-limits. A live `snackpass-*` Container Apps stack already runs this product |
| **Stripe test-mode keys** | Empty on every stack. Gates Phase 30 **entirely** |
| **ADR-0002 sign-off** | Still `Proposed` — gates PITR / DPLY-04 |

Also unscheduled: **#427 is OPEN** (ADR-0004 ingredient graph, 0% built — its finding that nothing
reconciles `allergen_mask` against the ingredients text is a live product risk, scheduled as LGL-03 in
Phase 31) and **#428 is OPEN** (the catering cohort, *half the stated go-to-market*, deliberately gated
until ≥3 caterers are interviewed — **Wave 1 costs no engineering time and can start today**).

**`k8s/` still ships zero monitoring manifests** (DPLY-03, written to fail on the current tree). A
staging deploy today would be wholly unmonitored.

---

## 5. Environment state

- **Branch `main`**, 0 behind, **clean** — the `.idea` residue that made this line read `dirty=4` for
  two days is gone as of #435. A dirty tree now means *your* change.
- ⚠ **CORRECTED 2026-08-05 — the row below is STALE and was measured before the 2026-08-04/05 trains
  ran.** Actual now: **15 local branches**, **8 worktrees** (7 under `.claude/worktrees/` plus the
  main checkout), **16 jtoye containers**. All 7 worktree branches correspond to PRs that have since
  merged (`feat/507-447-…`, `fix/450-454-…`, `fix/checkout-payment-intent-ordering`,
  `fix/517-…`, `fix/446-…`, `feat/105-106-…`, `fix/nightly-e2e-…`) plus 7 `worktree-agent-*`
  branches, so they are retirable — but retire them **by content**, not by `git branch -d`, which
  calls a squash-merged branch "not fully merged" (§0.-3 records why). This is the recurring shape
  this document keeps hitting: the figure below was true when written and was quoted forward after
  it stopped being. **Re-run `git worktree list` and `git branch` before
  repeating either number.** ✅ **CLEARED 2026-08-07, same session, one hour after the row below was
  written — which is why that row is struck rather than quoted. **FINAL for this session, after the
  reconcile branches were dropped too: 1 local branch (`main`), 2 remote (`main` + dependabot
  #523), 1 worktree (the main checkout).** ⚠ **This row has now been rewritten THREE times in one
  session** — 12 local → 3 → 1 — each time because the session itself changed what it was
  describing. That is not carelessness, it is the structural problem: a document that quotes a live
  count is stale the moment the next command runs. Treat every number in this row as the output of
  `git branch` / `git worktree list` at one instant, and **run them rather than reading them**. The
  intermediate reading was: 3 local branches (`main` + the two
  reconcile branches), **2 remote** (`main` + dependabot #523), **1 worktree** (the main checkout),
  and `.claude/worktrees` down from **8.4 GB to 4.0 KB**.** All 9 agent worktrees and their 9
  branches were removed, plus 10 merged remote branches. Every removal was gated on four checks run
  first, not after: `dirty=0`, `unpushed=0`, a MERGED PR, and **zero commits pushed after
  `mergedAt`**. The dirty check carried a positive control — a planted file read as `1` and cleared
  to `0` — because `dirty=0` and a silently-failed `git status` are otherwise indistinguishable. No
  process was cwd'd inside any worktree and nothing had been written in 6 hours. Post-removal:
  `git fsck` rc=0 (dangling objects only, expected), runtime/gate-enforcement/skip-budget all rc=0,
  `/shop` and core-java health both 200. ⚠ `git branch -d` accepted all 9, which is **not** evidence
  they merged into `main` — `-d` also passes when a local branch merely matches its upstream. The
  merge evidence is the PR state checked beforehand. STALE ONE HOUR LATER — *12 local branches, 12
  remote, 10 worktrees, 8.4 GB.* Four branches were
  retired this session — `docs/handoff-593-correction`, `docs/handoff-housekeeping-593`,
  `fix/kds-e2e-streaming-staging-locators-593` (local **and** remote, PRs #596/#594/#595 all MERGED,
  and each remote tip checked for post-merge pushes: 0) and remote `fix/582-loop-declared-test-void`
  (#588). **Every one of the 9 agent worktrees holds a branch whose PR has MERGED**, so all 9 are
  retirable — they are the 8.4 GB. Deliberately NOT removed here: another session may be inside one.
  ⚠ **DELETED 2026-08-07 on the owner's instruction — the two `*-reconcile-with-main` branches are
  GONE, and this paragraph is kept so nobody hunts for them.** They were PROVEN SUPERSEDED, never
  merged. Neither had a PR under its own name (#573 merged from
  `fix/450-shop-publish-drop-typed-409`, #577 from `fix/kds-board-reserve-536`), so any
  `gh pr list --state merged` headRefName filter reports them as unknown forever — which is why the
  decision was made by **blob hash** instead: every source file on both was byte-identical to
  `main`, and the sole difference was that `main`'s CHANGELOG heading carries the `(#573)`/`(#577)`
  citation theirs lacked, i.e. `main`'s copy was strictly the better one. **Recovery, for 90 days:**
  `fix/573-…` tip `0a8a017f99e216cf741f6cb2dcbc4f0c56c5bc53`, `fix/577-…` tip
  `42b695463e20ed40fbeeaee0beb2902141839905` — both still resolve as commit objects
  (`git cat-file -t`), reachable via `git reflog` / `git fsck --lost-found`.
  ⚠ **One measurement moved because of this session's own cleanup, and it would read as new work.**
  `fix/577-…` reported **0** unpushed commits during the audit and **4** an hour later. Nothing was
  written to it. `git log <b> --not --remotes` counts commits reachable from no remote ref, and 10
  merged remote branches were deleted in between — so deleting refs *manufactured* unpushed
  commits. Both readings were correct at the time. Re-measure after any ref cleanup, and never
  compare an unpushed count across it. PRIOR — **Re-measured
  2026-08-05 after #563/#565 and all three still hold
  — 15 local branches, 8 worktrees, 16 containers.** Both branches this session created were
  removed by `gh pr merge --delete-branch`, locally and remotely, so they add nothing to retire.
  Recorded because "still true" is itself a measurement, and the alternative is a reader assuming
  drift that is not there. STALE ROW FOLLOWS —
  **Branches/worktrees (2026-08-04):** **1 local** (`main`), **2 remote** (`main` + dependabot #523),
  **1 worktree**. Zero unpushed commits. Everything else was retired — see §0.-3 for what was proven
  before deleting, and for why `git branch -d` refused branches whose content was already in `main`.
- **Live stack:** 16 jtoye containers — 11 from `docker-compose.full-stack.yml` + 5 from
  `infra/monitoring/docker-compose.monitoring.yml`. **14 report healthy**;
  `jtoye-redis-exporter` and `jtoye-postgres-exporter` report no health status because their images
  define **no healthcheck**. That is **not** unhealthy. `keycloak-realm-render` is a one-shot init that
  exits by design and is not counted.
- **Runtime is CURRENT.** `sync-runtime.sh` was run *after* both merges and parity re-asserted:
  4/4 FRESH. The F-M1 fix was then re-proven functionally against that rebuilt stack.
- **Conda env:** none needed — no Python application code. Note the `block-base-python` hook refuses
  bare `python3` here; there is no `.conda-env` for this repo. The F-M1 race probe was therefore
  written in **Node**, not Python.
- **Stripe:** UNCONFIGURED (empty key → COD only). Email → Mailhog. S3 → MinIO. Broker → RabbitMQ 4.3.4.

---

## 6. Resume instructions

```bash
# 0. Tree state, asserted rather than quoted. Resolve the default branch, never hardcode it.
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git fetch -q origin
b=$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD) || echo "VOID: no origin/HEAD"
echo "on $(git branch --show-current) vs $b: dirty=$(git status --porcelain|wc -l) ahead=$(git rev-list --count $b..HEAD) behind=$(git rev-list --count HEAD..$b)"
# expect behind=0 AND dirty=0. A VOID line is NOT a pass.

# 1. Every gate. Capture rc on its OWN statement — an rc read after a pipe is the pipe's.
#    RUN FROM THE MAIN CHECKOUT, NOT A WORKTREE (compose project name comes from the directory).
#    Two gates need an argument or a context to be runnable at all, so the loop supplies it.
#    Without this the instruction below was UNACHIEVABLE and nothing noticed: H-1 compares
#    the NUMBER to the gate count, never whether the loop can actually return 0 for each.
#    check-test-count-oracle (#574) VOIDed on a missing family; check-changelog-cites-pr
#    (#583) VOIDed off a pull request. Both were "EXPECT N x rc=0" with a green H-1.
for g in scripts/check-*.sh scripts/docs-freshness.sh; do
  case "$(basename "$g")" in
    # needs a family; jest is the one the manifest is most often wrong about
    check-test-count-oracle.sh) set -- jest ;;
    *)                          set -- ;;
  esac
  bash "$g" "$@" >/dev/null 2>&1; rc=$?; printf '%-34s rc=%s\n' "$(basename "$g" .sh)" "$rc"
done
# EXPECT 39 x rc=0. A VOID (2) is not a pass. (34 -> 36: phase 28 added
#   check-media-content-types.sh (plan 28-03, media Content-Type allowlist) and
#   check-pentest-triage.sh (plan 28-05, the eleven-finding disposition record.
#   36 -> 38: phase 29 added check-networkpolicy-enforcement.sh (plan 29-03) and
#   check-alert-corpus-parity.sh (plan 29-06).
#   38 -> 39: quick 260815-00p Lane C added check-deploy-digest-parity.sh, the
#   Kubernetes half of the runtime-parity doctrine (the compose half is
#   check-runtime-freshness.sh, which can only VOID against a cluster). The same
#   lane added scripts/staging-pitr-drill.sh, which does NOT move this number: it
#   is not a check-*.sh and is invisible to this glob by design).
#   32 -> 34: plan 33-05 added
#   check-live-shop-coordinates.sh, proving the seeded coordinates exist on the
#   LIVE database, and plan 33-06 added check-openapi-snapshot-fresh.sh, diffing
#   the committed OpenAPI snapshot against the RUNNING service.
#   31 -> 32: plan 33-02 added
#   check-no-create-extension.sh, enforcing that no Flyway migration creates a
#   PostgreSQL extension — the role Flyway runs as cannot execute the statement at
#   all, so one would abort startup in every environment at once.
#   30 -> 31: plan 33-01 added
#   check-geo-attribution.sh, the OGL year gate for the Code-Point Open
#   attribution lines. H-1 had been failing on this since 33-01 landed — the
#   script was added and this number was not, which is the exact drift H-1
#   exists to catch, so the gate was working and nobody read it.
#   29 -> 30: #601 added
#   check-e2e-typecheck.sh, after `next build` was measured NOT to type-check
#   frontend/e2e/** despite tsconfig including **/*.ts — a planted
#   `const broken: number = "..."` in a spec gives npm run build rc=0 and
#   tsc --noEmit rc=2, so a type error in a spec could reach main green.
#   NOTE the two counts differ by one and both are correct: this line counts
#   32 GATE SCRIPTS (31 check-*.sh + docs-freshness.sh), while
#   check-gate-enforcement reports 31 because it counts only check-*.sh.
#   (22 -> 24: #276 added
#   check-image-supply-chain.sh and #337 added check-edge-core-contract.sh.
#   24 -> 25: check-postgres-major-parity.sh, after dependabot #525 bumped the
#   BACKUP image to postgres:18 against a 15 server with every CI check green.
#   25 -> 26: check-gate-enforcement.sh (#553), after SIX of the 24 check-*.sh
#   were measured to have ZERO references in .github/workflows/ — three of them
#   gates written to stop a specific defect recurring, and incapable of firing
#   on a PR. That gate now fails the build if a new one is added unwired.
#   26 -> 28: check-test-block-counter.sh and check-test-count-oracle.sh (#574),
#   after the old regex counter was measured 44 tests short of what Jest actually
#   ran, with `docs-freshness` green on every one of those commits.
#   28 -> 29: check-changelog-cites-pr.sh (#579), after SIX PRs in two days cited
#   the issue instead of the PR in their changelog heading and redded `main` after
#   merging, because C-1 asks only about MERGED PRs and so cannot fire on the PR
#   that breaks it.)
# MEASURED 2026-08-06 on #579's branch, off `main` @ d80b5363.
#   Getting there needed BOTH standing remedies, in this order:
#     check-alert-metrics    rc=1 -> scripts/seed-order-metric.sh      (every core-java recreate)
#     check-e2e-skip-budget  rc=2 -> re-run the suite, ~6.5 min        (every merge touching a spec)
#   Neither is a regression and both print their own remedy. Budget the 6.5 min.
# EARLIER 2026-08-05 on the #553 branch: 23/26 rc=0 — the three non-zero being
#   check-alert-metrics (1), check-e2e-skip-budget (2 VOID) and
#   check-handoff-contract (1, this document's own #420 claim, corrected in #541).
# If check-runtime-freshness is 1 -> you changed source: bash scripts/sync-runtime.sh
# If check-alert-metrics    is 1 -> core-java was recreated: bash scripts/seed-order-metric.sh
#    (this fires EVERY time core-java is recreated; observed rc=1 -> seed -> rc=0 on 2026-08-04)
# If check-e2e-skip-budget  is 2 -> stored report older than frontend/e2e; re-run the suite (below)
# Both gates print their own remedy. Neither is a regression.
# ALSO RUN THE K8S GATES — `scripts/check-*.sh` does NOT glob them, and they are the
# ones a k8s change actually exercises. This omission bit twice in one session; the
# second time CI caught a real violation (APP_PUBLIC_ORIGIN) that should have been local.
for g in k8s/scripts/check-*.sh k8s/scripts/render-golden.sh; do
  bash "$g" >/dev/null 2>&1; rc=$?; printf '%-34s rc=%s\n' "$(basename "$g" .sh)" "$rc"
done
# All six of these must be rc=0. (Deliberately NOT phrased as an "EXPECT <n> x rc=0"
# claim: H-1 matches that exact shape and compares it to the count of scripts/check-*.sh,
# so writing it that way here makes a TRUE statement about the k8s gates fail the gate.)

# 1b. E2E — SOURCE .env FIRST or the count is a lie.
#     Without it, 26 vendor-authenticated specs self-skip on "No vendor password" and
#     the suite reports 48 skipped / 21 undeclared, which reads exactly like a regression.
set -a; . ./.env; set +a
export E2E_VENDOR_PASSWORD="${E2E_VENDOR_PASSWORD:-$KC_SEED_USER_PASSWORD}"
bash scripts/seed-e2e-fixtures.sh          # or the DRAFT block skips and the budget fails
( cd frontend && PLAYWRIGHT_JSON_OUTPUT_NAME=e2e-artifacts/report.json \
    npx playwright test --reporter=json,list )
# EXPECT 174 passed / 8 skipped / 0 failed of 182 (~6.6 min), measured 2026-08-05 on
# `main` after #565. 182 = both projects; #563 added one test() block, so 180 -> 182.
# 8 is EXACTLY the declared ceiling, so the next skip added trips the gate.
# ⚠ This is the LOCAL suite. The NIGHTLY is the authority and has not run since
#   `d4930719` — re-dispatch it rather than reading this line as whole-suite health.

# 2. The QA council findings — the thing no repo command can show you (§0.1).
ls .qa-council/disc-20260802-121732/                 # NOT the LATEST pointer; it still says July
sed -n '1,80p' .qa-council/disc-20260802-121732/QA-COUNCIL-REPORT.md

# 3. Re-prove F-M1 is live, rather than trusting this document.
#    Expect {"200":1,"409":7} and type errors/concurrent-modification. A 500 means the runtime is stale.
docker exec jtoye_oaas_2026-core-java-1 sh -c \
  'unzip -p /app/app.jar BOOT-INF/classes/uk/jtoye/core/common/GlobalExceptionHandler.class | strings' \
  | grep -c concurrent-modification      # expect 2; a filesystem `find` returns a misleading 0

# 4. Before merging ANY PR — never an inline gh-api-pipe-wc idiom
~/dotfiles/gates/pr-merge-guard.sh --repo Bralabee/JToye_OaaS_2026 --pr <n> --expect-head <sha>
#    0 = safe · 1 = not safe · 2 = VOID (could not evaluate — NEVER treat as 0)
```

**#457 is DONE (PR #466) — and it paid for the method.** Settling it in a browser first, as the issue
demanded, turned one symptom into two defects and found **#465** (the session ended at 300s regardless
of activity, with a 30-day refresh token never redeemed). Both closed. Keep doing this: the ten-minute
browser arm is what stopped a correct-looking header fix from shipping over a live P1.

**#442 is DONE (PR #472), and its trap was real.** The acceptance criteria warned that authenticating
metrics without giving Prometheus a way in blinds the Phase 27 alerting layer. Verified: the scrape
config declares no `basic_auth` and no `authorization` for either job, so authentication was the wrong
fix outright. Closed by **port isolation** instead — the approach prod already used. Two of the
finding's three claims were falsified along the way (§2.1).

**Recommended next move: #444 (F-H4)** — the webhook delivery log is permanently empty, a shipped
Phase-22 feature that has never worked, and the finding names the cause in one line (no
`@Transactional`, so `TenantSetLocalAspect` never pins the GUC and RLS returns nothing). Needs no
decision from §4.

**#564 is CLOSED** (§0.-10), shipped by #567 (`9762bce6`) on 2026-08-05 — no longer work to pick up.
The KDS board issued one request per active ticket on every full refresh, so an 18-ticket board cost
19 and a 40-ticket board would have cost 41 — against 100/min for the *whole* tenant. #563 made the
board **tolerant** of the resulting refusals; it did not reduce them, and the failure mode scaled
with how busy the kitchen was, which is the wrong way round. A batch endpoint was the real fix;
bounded concurrency only moves the cliff. Measured on the same 18-ticket board, before → after:
**38 requests → 2**, `/detail` **36 → 0**, **10 × 429 → 0**, lowest `X-RateLimit-Remaining`
**0 → 115**. It also took three separate test defects out at their shared root.

⚠ **#444 is core-java, so its PR WILL run the full Testcontainers suite (~47 min, measured on #472).**
That is not a cost to avoid — RLS behaviour under a real Postgres is exactly what that suite buys, and
this repo has a recorded trap where auth-layer changes silently break *existing* integration tests.
Frontend-only work path-skips it in ~5s, so batching is free there and only there.

**Also newly filed: #467** — `/api/customer-orders` 502s on the compose stack (`CORE_API_INTERNAL_URL`
unset; `localhost` in-container is the container's own loopback, and `extra_hosts` does not beat it),
and the UI renders that failure as *"No orders found for this email"*. **An error displayed as an
empty state** — invisible to the user, to a screenshot, and to any test asserting the page renders.
Found while browser-verifying #466, pre-existing.


**Then #444 (F-H4)** — the webhook delivery log is permanently empty, a shipped Phase-22 feature that
has never worked, and the finding names the cause in one line (no `@Transactional`, so
`TenantSetLocalAspect` never pins the GUC and RLS returns nothing).

**#460 and #461 need a DECISION before any code**, and both are P1. #460 (locality) is a phase, and
§4's unresolved production-domain question touches it. #461 (payments) is blocked on Stripe test-mode
keys — which is already one of §4's four blocking decisions, so it is the same blocker wearing a
different hat, not a new one.

**The whole council backlog is now filed — #438–#454, 23 issues.** A coverage sweep maps all 34
findings to filed / already-fixed / deliberately-Group-C, with **0 unaccounted** and a control token
proving the sweep discriminates. Two findings — **#453 is OPEN** (F-H6, High) and **#454 is CLOSED** (fixed in #534)
(F-M6) — appear in `findings.json` and the report prose but **in no group in `plan.md`**: the council
found them and never routed them. They were caught only by that sweep. If you run a council again,
diff `findings.json` against the groups in `plan.md` before trusting the adjudication.

**#428 Wave 1 (catering discovery) still costs no engineering time and runs in parallel with anything.**

**Merged code is not running code.** After any merge touching source: `bash scripts/sync-runtime.sh`,
then reseed the order metric. `docker compose start` does not rebuild.

**Squash-merge note:** the repo squash-merges, so `git branch --merged` and `git branch -d` call
merged branches unmerged. Use PR state as the authority.
