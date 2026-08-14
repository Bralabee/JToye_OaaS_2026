# Phase 29 Lane C — what was authored offline, and what is still OWED

**Written:** 2026-08-15 · **Quick task:** `260815-00p` · **Branch:** `phase-29-research`

This is the note plan **29-13** and plan **29-15** should read before doing anything else.
Both plans were blocked on their **evidence**, not on their **code**. This lane wrote the
code so that when the estate comes back the remaining work is *running* things rather than
*writing* them.

> **NO LIVE SYSTEM WAS CONTACTED BY THIS LANE.** No cluster, no Azure resource, no database.
> `jtoye-staging-aks` and `snackpass-pg` were verified `Stopped` on 2026-08-14 to halt compute
> billing and were neither started nor queried. No `az account show`, no `az login`, no
> `kubectl`, no `psql`. The PITR drill was executed **only** under `PITR_DRILL_DRY_RUN=1`,
> which stubs every external call — and that was proven structural, not merely configured, by
> running it successfully on a `PATH` containing no `az` binary at all (with a control arm
> first confirming `az` really was unresolvable on that PATH).

---

## 1. What was authored, per file, and what was actually proven about each

| File | What it is | Proven by |
|------|-----------|-----------|
| `scripts/staging-pitr-drill.sh` (433 lines) | Three-arm recovery drill for the **managed** staging DB: arm A logical dump as the app role, arm B as the BYPASSRLS role, arm P provider PITR to a timestamp. | `bash -n` rc=0; dry-run rc=0; injected-failure arm; 3 VOID arms; a no-`az`-on-PATH arm. All below. |
| `scripts/check-deploy-digest-parity.sh` (253 lines) | The **Kubernetes half** of the runtime-parity doctrine: are the running pods the images built for this commit? | `bash -n` rc=0; five fabricated-input arms (0/1/2/2/2). |
| `.github/workflows/ci-cd.yaml` | `deploy-staging` loses its long-lived kubeconfig; gains job-scoped `permissions:`, federated `azure/login`, `az aks get-credentials`, a GHCR read-login and the digest step. | `actionlint` clean **and** shown capable of failing; scoped diff; job-range scope assertion; `deploy-production` byte-identical by hash. |
| `scripts/gates/gate-enforcement.conf` | **Comment lines only** (32 added, 0 rows). | Diff asserted comment-only, with the asserting instrument itself controlled; the "adding a row VOIDs the gate" claim re-measured. |
| `HANDOFF.md` | Both `EXPECT N x rc=0` anchors 38 → 39. | `check-handoff-contract` rc=0. |
| `k8s/DEPLOYMENT.md` | New "Which half of the doctrine each instrument covers" section. | `check-doc-citations` rc=0, byte-identical citation totals to baseline. |
| `docs/architecture/SYSTEM_DESIGN_V2.md` | The superseded WAL-G direction corrected against ADR-0002, **named as a second correction**. | 52 insertions / **1** deletion, that deletion being exactly the WAL-G line; §7.1 true-negative proven byte-identical by hash. |
| `docs/runbooks/backups.md` | Pointer to the drill, stating plainly it has not been run. | 28 insertions / **0** deletions. |

---

## 2. OWED — what this lane could NOT prove

Nothing in this section is done. Each item names the plan that owns it.

### Owed to **29-13** (the PITR drill)

1. **A real provider PITR restore of `jtoye-staging-pg`**, with:
   - per-table row counts on the restored server,
   - the achieved **RPO** (gap between the restore target and the incident),
   - the wall-clock **RTO** for the restore to reach `Ready`,
   - confirmation the restored server was deleted (the trap's `delete rc`).
2. **Arm A executed for real** — the app-role `pg_dump` refused (expected rc=1), its partial
   artifact shown *passing* `MIN_BACKUP_BYTES` and `pg_restore --list`, then restoring to
   **zero rows**. The 149x ratio the dry run prints is a **fixture reproducing the Phase 26
   L4 measurement**, not a new measurement.
3. **Arm B executed for real** — BYPASSRLS dump restoring to counts equal to the source.
4. **A dated result section in `docs/runbooks/backups.md`**, in the shape of the existing
   2026-07-25 local-cluster section. This lane deliberately did **not** write one: there are
   no results, and an empty results section is worse than none.
5. **The runbook's "Pending (needs a live cluster)" checkboxes** — the prod S3 artifact and
   the prod restore drill — remain unticked and were not touched.

### Owed to **29-15** (the federated deploy path)

6. **One green `deploy-staging` run, with its URL.** Nothing in this lane has ever executed
   the job. It cannot run until `DEPLOY_STAGING_ENABLED` is `true` **and** staging DNS/TLS
   resolve — both of which are gated on the two owner actions the phase is paused for.
7. **The federated login actually working** — i.e. that the Entra credential's exact-match
   subject `…:environment:staging` matches what the run presents. A subject mismatch fails
   closed and is invisible until the first run.
8. **The three `secrets.AZURE_*` values existing** (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`,
   `AZURE_SUBSCRIPTION_ID`). The workflow references them; this lane did not and could not
   create them. Their registered values are in `29-PROVISIONING-EVIDENCE.md` §2.4 and are
   deliberately **not** copied into this file.
9. **The digest step's real output**, and with it the **narrowing of the expected SET to a
   single measured digest**. The gate currently asserts membership in {index digest} ∪
   {platform manifest digests} because *which* digest AKS/containerd reports in
   `.status.containerStatuses[].imageID` is not measurable without a cluster. This is
   knowingly the weaker of the two correct forms — narrowing it speculatively would red every
   correct deploy.
10. **The SKIP-when-flag-unset arm** — that the job correctly does not run when
    `vars.DEPLOY_STAGING_ENABLED` is not `'true'`. Not exercisable from a checkout.
11. **OPERATOR ACTION: delete the staging kubeconfig repository secret.** Its name is
    **`KUBE_CONFIG_STAGING`**. This lane removed every *reference* to it from
    `.github/workflows/ci-cd.yaml` (verified: zero matches, with a control confirming the
    reference existed at the previous commit). **Removing the reference stops it being used;
    it does not remove the credential.** Until an operator deletes the secret in repository
    settings, a standing cluster-admin-capable credential still exists and is readable by any
    workflow that asks for it. This is the single highest-value follow-up in this list.

    *(The name is recorded here and not in the workflow on purpose: a check asserting "this
    workflow no longer references that secret" is satisfied by prose naming it — a rule that
    must spell the token it forbids fires on its own definition, and the workflow file is
    exactly where that check looks.)*

---

## 3. STALE PREMISES — do not waste a cycle rediscovering these

Both plans' prose contains statements that were true when written and are not true now.

| # | The plan says | Measured 2026-08-15 |
|---|---------------|---------------------|
| S-1 | 29-13: `SYSTEM_DESIGN_V2.md:657` falsely claims "WAL-G to S3 (PITR)" | **Gone.** PR #197 (`docs/CHANGELOG.md:2183`) already relabelled that content TARGET. Line 657 no longer holds it. The remaining issue was different and narrower — the TARGET diagram still *named* WAL-G while ADR-0002 (Accepted 2026-08-10) chose provider PITR. **This lane fixed that**, and named it as a *second* correction. |
| S-2 | 29-13: the §7.1 WAL-G mention should be removed | **Do not touch it.** `WAL Archive: NONE — no archive_command, no WAL-G, no pgBackRest` is a **true negative statement** about the current posture. Deleting it removes a correct fact. It is untouched (proven byte-identical by hash; note its line number moved 1466 → 1517 because text was inserted above it, which is why the proof is by content and not by line number). |
| S-3 | 29-13 verify line runs `shellcheck -S error …` | **shellcheck is NOT installed on this host** and **no CI job runs it** (the single `shellcheck` hit in `.github/workflows/` is a `# shellcheck disable=` comment in `e2e-nightly.yml`). The invocation returns **rc=127** with zero findings, which is indistinguishable from a clean pass. Treat it as **VOID**, never as satisfied. |
| S-4 | 29-13: arm A's `pg_dump` "silently captures zero rows" | **It exits 1.** `pg_dump` requests `row_security=off`, which Postgres refuses for a non-BYPASSRLS role on a FORCE-RLS table. The runbook already carries this correction. A drill written around "arm A's dump succeeds" fails on the true behaviour. The subject is the **partial artifact** left behind, which still clears both content checks. |
| S-5 | 29-15: the `Configure kubeconfig` step is at 1194-1197 | **Offsets moved** and have moved again since. Locate steps by content, never by line number. |
| S-6 | 29-15 (implied): per-service digests can come from `build-and-push` outputs | **They cannot.** `build-and-push` is a matrix job and GitHub Actions matrix outputs are **last-writer-wins** — three parallel legs leave exactly one value, non-deterministically. Digests must be resolved from the registry at deploy time (hence `packages: read`). |
| S-7 | — | `deploy-staging` had **no** `permissions:` block, so it inherited the repository default. Adding one both enables OIDC and *tightens* the job. Assert its **scope by job line range**; a file-wide grep for `id-token: write` is satisfied by a workflow-level grant, which is the exact thing being avoided. |

---

## 4. WHICH CRITERIA ARE STRUCTURAL-ONLY — said plainly

**A green `bash -n`, a green `actionlint`, a passing dry-run trace and five passing digest
fixture arms prove SHAPE.** They are evidence about the *files in this branch* and about
nothing else. Specifically, they are **not** evidence that:

- a restore works, or that staging's backups are recoverable at all;
- the federated credential authenticates, or that the Entra subject matches;
- any digest has ever matched, or that the digest step can read a real `imageID`;
- the firewall re-application makes a restored server reachable;
- the counting role can see rows on the real staging database.

Every arm run in this lane was against a **stub or a fixture**. That is the honest ceiling of
offline work, and it is stated here so a future reader cannot mistake a green gate for a
working drill — which is precisely the failure mode this project has recorded before
("a structural check can pass while the function is still broken").

**Two things were found to be genuinely unfalsifiable or VOID and are reported as such rather
than as passes:**

- **shellcheck** — VOID (rc=127, not installed, no CI job runs it). See S-3.
- **`python3 -c "import yaml…"`**, the plan's YAML-parse verify line — **blocked by a
  policy hook** (no conda env is declared for this project, and this lane declined to invent
  one). It was **replaced by `actionlint`**, which is a *strictly stronger* instrument: it
  parses the same YAML **and** validates the GitHub Actions schema on top. The substitution
  is recorded rather than made silently, and `actionlint` was itself shown capable of failing
  (an injected broken `${{ }}` expression on a scratch copy → rc=1).

---

## 5. Two instrument defects found while verifying — worth knowing

Both produced **false negatives** that briefly read as real findings. Both were caught by
control arms, not by the check passing.

1. **`rg` and `grep` are Claude Code shell FUNCTIONS and do not exist inside a
   `bash script.sh` subprocess.** An assertion script calling `rg` died at **rc=127 with zero
   results** and reported *"no `id-token` grant at all"* — on a file that has one. Zero
   results from a missing binary is indistinguishable from zero results from a clean tree.
   Inside scripts, use `git grep` (a real binary); use `rg -uu` only in the interactive shell
   where the function exists, and always print the rc.
2. **`git grep -n` prefixes the FILE PATH**, so `${line%%:*}` extracts the path, not the line
   number. A scope assertion consequently reported all four occurrences as "outside the job".
   Strip the path first, and reject an unparseable line reference explicitly rather than
   letting it fall through as a violation.

Neither was a product defect. Suspect the instrument first.
