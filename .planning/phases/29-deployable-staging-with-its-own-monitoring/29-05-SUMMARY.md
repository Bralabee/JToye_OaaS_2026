---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 05
subsystem: infra
tags: [azure, aks, postgresql, cert-manager, rabbitmq-operator, ingress-nginx, kubectl, bash, secrets, supply-chain]

# Dependency graph
requires:
  - phase: 29-deployable-staging-with-its-own-monitoring
    plan: 01
    provides: "29-OPERATOR-DECISIONS.md — the 12 decision keys, the GHCR_VISIBILITY measurement and the PG16 requirement every script here READS rather than restates"
  - phase: 26-local-k8s-overlay
    provides: "scripts/k8s-local-secrets.sh apply/skip idiom + fail-loud-by-NAME preflight; scripts/k8s-local-up.sh ORDER-block and webhook-probe shape; k8s_local_assert_context, the guard the kube-context guard mirrors"
  - phase: 28-security-triage-and-the-dev-prod-boundary
    provides: "infra/db/create-runtime-role.sql + infra/backups/create-backup-role.sql — the three-role split the managed Flexible Server inherits; docs/runbooks/credential-rotation.md §6"
provides:
  - "scripts/azure-staging-provision.sh — one idempotent script for the whole estate, refusing the employer subscription, dry-run offline by construction"
  - "scripts/staging-secrets.sh — out-of-band Secrets + the three DB roles on the managed server, with a DB-side read-back proven to fail in both directions"
  - "scripts/staging-bootstrap.sh — cert-manager / RabbitMQ operator / ingress-nginx from pinned URLs with recorded sha256, applied outside k8s/ so the goldens stay reviewable"
  - "Three real sha256 digests, computed this session and each confirmed immutable by a second independent fetch"
  - "The staging secrets mechanism DECIDED in writing (plain Secrets) with #100/#300 deferred and the reason recorded in k8s/QUICK_START.md"
  - "A measured correction to Pitfall 5: allow-snippet-annotations alone is NOT sufficient — annotations-risk-level must also be raised"
affects: [29-06, 29-07, 29-09, 29-10, 29-11, 29-12, 29-13, 29-14, 29-15, 29-16]

# Tech tracking
tech-stack:
  added:
    - "cert-manager v1.21.1 (sha256 5f6a499b…, 1,034,400 bytes)"
    - "rabbitmq cluster-operator v2.22.3 (sha256 8e2c20fe…, 351,140 bytes)"
    - "ingress-nginx controller-v1.15.1 (sha256 502fddca…, 16,384 bytes)"
  patterns:
    - "Decision record as an interface: every SKU/count/version/ceiling is parsed from a dated markdown table, and a missing key REFUSES BY NAME rather than defaulting"
    - "Offline dry-run: --dry-run executes no CLI command that names a resource, so 'created nothing' is true by construction rather than by inspection"
    - "Structural self-assertion: the script greps its own source for CLI invocations that bypass the subscription-pinning wrappers, and that assertion was shown able to fire"
    - "Verify-all-then-apply: every third-party artefact is digest-checked before any is installed, so a bad third artefact cannot leave the first two applied"
    - "Test doubles at the tool boundary (a fake `az` on PATH) instead of an env-var seam inside the guard"

key-files:
  created:
    - scripts/azure-staging-provision.sh
    - scripts/staging-secrets.sh
    - scripts/staging-bootstrap.sh
  modified:
    - scripts/gates/gate-enforcement.conf
    - k8s/QUICK_START.md

key-decisions:
  - "The ambient Azure subscription on this host is the EMPLOYER's — measured, not assumed. Every az call is explicitly pinned and the guard refuses a non-owner target three different ways."
  - "gate-enforcement.conf gains a COMMENT, not a row: an entry naming a non-check script makes check-gate-enforcement.sh exit 2 for EVERY gate. Measured before writing."
  - "Pitfall 5 route: allow-snippet-annotations=true AND annotations-risk-level=Critical on the controller ConfigMap, with the CVE-2021-25742 acceptance dated in the script header. The strictly better add-headers route needs files this plan does not own — recorded, not dropped."
  - "Static IP bound by service annotation, not spec.loadBalancerIP, confirmed against Microsoft's current docs (the research flagged the spelling MEDIUM confidence)."
  - "Plain Kubernetes Secrets for staging; #100/#300 sealed-secrets DEFERRED with the reason written into k8s/QUICK_START.md and carried to 29-16."
  - "PostgreSQL 16 enforced as an executable refusal, not a comment — below 16 the whole backup story is silently empty."

patterns-established:
  - "Read the fail-direction EXIT CODE, not just the refusal message: a cleanup EXIT trap was rewriting 2 into 1, and only the number revealed it"
  - "A guard that protects nothing in a given mode is announced as not-evaluated, out loud, so it cannot be confused with a guard that was skipped"
  - "An artefact pin is only trustworthy once fetched TWICE and compared byte-for-byte — otherwise the digest records a moving target"

requirements-completed: [DPLY-01, DPLY-02, DPLY-03]

# Metrics
duration: 84min
completed: 2026-08-10
---

# Phase 29 Plan 05: Three Guard-First Provisioning Scripts Summary

**The whole staging estate is now describable by three idempotent scripts whose inputs are the recorded decisions and whose every guard has been shown to refuse — including one, the subscription guard, whose fail direction is the DEFAULT state of this machine.**

## Performance

- **Duration:** ~84 min
- **Tasks:** 3
- **Files:** 5 (3 created, 2 modified)
- **Azure resources created / deleted / scaled:** **0** (`az resource list -g jtoye-rg` = 10 before, 10 after)

## Task Commits

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Azure estate provisioning | `12a61fb5` | `scripts/azure-staging-provision.sh` (777 lines) |
| 2 | Secrets + DB-role bootstrap | `6f102ae8` | `scripts/staging-secrets.sh` (599 lines) |
| 3 | Pinned platform bootstrap | `9c1d7bc1` | `scripts/staging-bootstrap.sh` (546), `scripts/gates/gate-enforcement.conf`, `k8s/QUICK_START.md` |

## The measurement that reframed Task 1

`az account show` on this host, this session:

```
8d1c4578-…   Prod - HS2 Ltd   Enabled
```

**The ambient default subscription is the employer's.** The subscription guard is therefore not a
precaution against a hypothetical mistake — the unguarded path is the *default* path, and an
unqualified `az aks create -g jtoye-rg …` here does not create anything in the owner's
subscription. That inverted the design: rather than assert the ambient value (which would make the
script unusable without `az account set`, a global mutation of `~/.azure` affecting every session
on this machine), every call is explicitly pinned, the pinned value must equal the record's
`AZURE_SUBSCRIPTION_ID`, and a STEP 1c source assertion proves no invocation bypasses the pinning
wrappers.

## Falsification evidence — every arm, both directions

### Task 1 — `azure-staging-provision.sh`

| Arm | Setup | Expected | Measured |
|---|---|---|---|
| A | `--subscription 00000000-…` | refuse | **rc=1** `REFUSED [wrong-subscription]`, message names `Prod - HS2 Ltd` as EMPLOYER infrastructure |
| B | fake `az` on PATH resolving a *different* id than asked | refuse | **rc=1** `REFUSED [subscription-identity-mismatch]: asked for c483d353… but the CLI resolved 8d1c4578-DOUBLE-armB` |
| C | fake `az` returning the OWNER id but the employer *display name* | refuse | **rc=1** `REFUSED [employer-subscription]: … intent is not a safety mechanism` |
| D | `NODE_VM_SIZE` row deleted from a copy of the record | refuse, naming it | **rc=1** `MISSING: decision key NODE_VM_SIZE` |
| E | `PG_SERVER_VERSION` row deleted | refuse, naming it | **rc=1** `MISSING: decision key PG_SERVER_VERSION` |
| F | `PG_SERVER_VERSION` set to `15` | refuse (Blocker C) | **rc=1** `REFUSED [pg-version]: … every logical dump would silently capture ZERO rows` |
| G | `--delete-snackpass` against the recorded `scale-to-zero` | refuse | **rc=1** `REFUSED [snackpass-disposition]: … a DIFFERENT PROJECT` |
| H | one unpinned `az group list` injected into a scratch copy | VOID | **rc=2**, prints `526:az group list -o tsv` then the VOID |
| clean | `--dry-run`, asserted **last** | 0 | **rc=0** |

**Arms B and C use a test double at the tool boundary — a fake `az` first on PATH in a subshell.**
This is a deliberate, recorded substitution for the acceptance criterion's stated method (see
"Acceptance criteria corrected" below).

Dry-run created nothing, proven by count with a working instrument:

```
az resource list -g jtoye-rg … --query 'length(@)'                          -> 10   (before)
az resource list -g jtoye-rg … --query 'length(@)'                          -> 10   (after)
az resource list -g jtoye-rg … --resource-type Microsoft.App/containerApps  ->  6   (control: the query varies)
```

The filtered control matters: `10` subscription-wide and `10` in `jtoye-rg` are the same number, so
the unfiltered pair alone could not distinguish "unchanged" from "stuck". The 6 shows the
instrument responds to its inputs.

Rendered-command assertions (against the captured dry-run output, not the source):

```
DRY-RUN would run: az aks create … --network-dataplane cilium …            -> 1 match
DRY-RUN would run: az postgres flexible-server create … --version 16 …     -> 1 match
grep -c 'az aks create'  -> 1     grep -c 'uuid-ossp' -> 3
```

### Task 2 — `staging-secrets.sh`

| Arm | Setup | Expected | Measured |
|---|---|---|---|
| 1 | every required variable unset | refuse, naming each | **rc=1**, **23 names** printed, then `REFUSED [value-preflight]: 23 required variable(s) missing` |
| 2 | only `ALERTMANAGER_SMTP_PASSWORD` unset | name exactly that one | **rc=1**, `MISSING: ALERTMANAGER_SMTP_PASSWORD`, `1 required variable(s) missing` |
| 3 | no `--context` | VOID | **rc=2**, message names `sipbihs2aks` as EMPLOYER infrastructure |
| 4 | `--context sipbihs2aks` named explicitly | VOID | **rc=2** `on the refusal list … intent is not a safety mechanism` |
| 5 | `--context` naming an absent context | VOID | **rc=2**, lists the known contexts |
| **6** | **`jtoye_backup` WITHOUT BYPASSRLS** | **refuse** | **rc=1** `REFUSED [backup-role-bypassrls]: … would capture ZERO rows from every FORCE-RLS table` |
| **7** | **`jtoye_runtime` WITH BYPASSRLS** | **refuse** | **rc=1** `REFUSED [runtime-role-overprivileged]: … multi-tenant isolation becomes impossible` |
| 8 | both roles correct | 0 | **rc=0**, both `OK:` lines |
| 9 | all three roles dropped | refuse (empty ≠ pass) | **rc=1** `REFUSED [roles-absent]: … An empty result is not a pass` |
| 10 | database port unreachable | VOID | **rc=2** `could not query pg_roles … the verification is VOID, not passing` |
| 11 | full `--roles-only` bootstrap | 0 | **rc=0**, all three roles created and read back `f/f`, `t/f`, `f/f` |
| 12 | `--roles-only` run a second time | 0 (idempotent) | **rc=0** |

**Arms 6–12 ran against a THROWAWAY `postgres:16-alpine` container** on `127.0.0.1:55432`, created
and destroyed inside this session. The shared dev Postgres was **not** mutated, and was re-read
afterwards to prove it: `jtoye_backup t/f`, `jtoye_runtime f/f`, `jtoye_app f/f` — unchanged. This
follows 29-03's precedent (a second session drives the main checkout).

Arm 11 is more than a structural pass: it executed the SQL this file owns (`jtoye_app` creation +
grants) and both invoked SQL files against a real PostgreSQL 16, so the bootstrap path is
functionally proven, not merely syntactically valid.

Other Task 2 assertions, each with a control:

| Assertion | Value | Control |
|---|---|---|
| literal credential assignments in the script | **0** | same instrument on a scratch file containing two -> **2** |
| `rolbypassrls` occurrences (AC: ≥ 2) | **7** | same pattern on `k8s-local-secrets.sh` -> **4** (matches real code) |
| GHCR_VISIBILITY read from the record | cell containing `PUBLIC` | row deleted -> **empty**, i.e. the VOID branch |

### Task 3 — `staging-bootstrap.sh`

| Arm | Setup | Expected | Measured |
|---|---|---|---|
| 13 | live download of all three artefacts | 0 | **rc=0**, all three matched size + sha256 |
| 14 | cert-manager truncated to 900,000 bytes | refuse | **rc=1** `REFUSED [artefact-size]: expected 1034400 bytes, got 900000` |
| 15 | operator manifest, **same size**, one byte changed | refuse | **rc=1** `REFUSED [artefact-digest]`, both digests printed |
| 16 | no `--context` | VOID | **rc=2** |
| 17 | `--context sipbihs2aks` | VOID | **rc=2** |
| 18 | `--context` naming an absent context | VOID | **rc=2** |
| 19 | conf entry naming a non-`check-*.sh` | VOID | **rc=2** for **every** gate |
| 20 | same, against the **edited** conf | VOID | **rc=2** — so the PASS is evidence the checker reads this file |
| clean | `check-gate-enforcement.sh`, asserted **after** the break arms | 0 | **rc=0**, `gates: 36, workflows: 6, exempt: 6` (the checker's own numbers) |

Arm 15 is the one that matters. A truncated file fails the *size* check and never reaches the
digest, so arm 14 alone would have left the sha256 comparison unexercised — a check observed only
passing. The same-size single-byte tamper is what actually proves it.

Provenance facts measured rather than quoted:

```
cert-manager.yaml       1,034,400 bytes  sha256 5f6a499b8c1857d57f560f536e0dcc830914b45c420899fe7ad0692c8624e408
cluster-operator.yml      351,140 bytes  sha256 8e2c20fe9fe8fb06a8e4a99574951d7933ba7cbc4d83c854bc5e7acc7dc0624e
ingress-nginx deploy.yaml  16,384 bytes  sha256 502fddca66b09c20dd48b6d0a792a9671cd663a3a0d2a8bda5ae990d13b6c5b2
```

Each was fetched **twice** and compared byte-for-byte (`cmp` -> identical), which is what makes the
digest a pin rather than a snapshot of a moving target. The ingress-nginx manifest was additionally
sanity-checked for completeness (670 lines, 19 documents, all expected kinds) because its size is
exactly 2^14 and that coincidence deserved a second look.

Order and Pitfall-5 claims, asserted against the artefacts:

```
cluster-operator.yml apiVersion: cert-manager.io/v1   -> 3   (Certificate 5870, 5887; Issuer 5904)
operator image                                        -> ghcr.io/rabbitmq/cluster-operator:2.22.3 (line 5818)
ingress-nginx.yaml allow-snippet-annotations          -> 0   (so it takes the false default)
step order by line number                             -> cert-manager 402 < rabbitmq 452 < ingress 462
```

Dry-run safety, by structure: the script contains exactly **one** real `apply` (line 396), sitting
immediately after the `--dry-run-only` early return at 392–395 and after the verbatim server-side
dry-run at 389. Every other cluster mutation (rollout waits, the ConfigMap patch, the Service
annotate) is inside one of four `MODE != dryrun` blocks (lines 405, 454, 465, 509).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The cleanup EXIT trap silently rewrote exit 2 into exit 1**

- **Found during:** Task 3, reading the *number* on a fail-direction arm rather than just the refusal text
- **Issue:** `cleanup() { [ -n "$WORKDIR" ] && [ -d "$WORKDIR" ] && rm -rf "$WORKDIR"; }` — when `WORKDIR` was still empty the trap's final test returned 1, and on bash 5.2.21 that status became the script's. A `void` that called `exit 2` came out as **rc=1**. In this repo 1 and 2 are different verdicts ("this failed" vs "this could not be evaluated, which is never clean") and CI branches on them, so every VOID from every guard was being reported as a violation.
- **Reproduced in isolation** (7-line script, same bash) before fixing — not diagnosed by inference.
- **Fix:** capture `$?` as the trap's first statement and re-assert it with `exit "$rc"` as its last; the removal is unchanged. The reproduction is recorded in the script's header so it is not "tidied" back.
- **Verification:** all three VOID arms re-run -> **rc=2**; both refuse arms -> **rc=1**; clean -> **rc=0**; and `/tmp` entry count identical before/after a run with no stray `tmp.*`, proving the removal still happens.
- **Committed in:** `9c1d7bc1`

**2. [Rule 1 - Bug] The STEP 1c self-assertion fired on its own prose**

- **Found during:** Task 1, first dry-run
- **Issue:** the "no unpinned CLI invocation" check matched the tool's name anywhere on a line, so it flagged its own header, its own refusal messages and `command -v az` — **rc=2 on a correct tree**. This is the recorded "a rule that must name the token it forbids fires on its own definition" trap.
- **Fix:** anchored the pattern to a COMMAND POSITION (line start, or after `|`, `&&`, `;`, `$(`), stripped full-line comments first, and filtered lines that already carry the pin. Loosening the assertion was rejected as the wrong direction.
- **Verification:** clean tree -> the OK line; one injected `az group list` -> **rc=2** naming line 526.
- **Committed in:** `12a61fb5`

**3. [Rule 1 - Bug] Dry-run output was not a faithful record of what would run**

- **Found during:** Task 1
- **Issue:** `az_read`'s "would read" notice went to **stdout**, and every caller captures it with `$(…)`. The whole sentence landed in the variable, so the printed firewall-rule command carried a paragraph where an IP address belongs. A dry run whose printed commands are not the commands that would run is worse than no dry run.
- **Fix:** notice to stderr, a named placeholder (`<aks-egress-ip>`) to stdout; error suppression moved inside the wrapper so a caller-side `2>/dev/null` cannot swallow the notice. `az_print` also now quotes any argument a shell would re-interpret, so `<PG_ADMIN_USER>` cannot become a redirection on copy-paste.
- **Committed in:** `12a61fb5`

**4. [Rule 2 - Missing Critical] `allow-snippet-annotations` alone would NOT have served the headers**

- **Found during:** Task 3
- **Issue:** the plan's action text says to set `allow-snippet-annotations` in the controller ConfigMap. Upstream docs (checked, not recalled) classify `configuration-snippet` as **Critical** risk while `annotations-risk-level` defaults to **High**, and an annotation above the configured level is rejected regardless of the snippet flag. Setting only the one key reproduces the exact Pitfall 5 symptom it was meant to fix — headers absent, Ingress rejected, every HTTP check still 200.
- **Fix:** both keys set (`annotations-risk-level: Critical`), the reason recorded in the header, and the script prints the route it took plus reads the effective value back into the evidence block.
- **Committed in:** `9c1d7bc1`

**5. [Rule 2 - Missing Critical] `spec.loadBalancerIP` is deprecated**

- **Found during:** Task 3. The research flagged the static-IP annotation spelling as MEDIUM confidence and said to confirm at implementation time; that was done rather than skipped.
- **Fix:** `service.beta.kubernetes.io/azure-pip-name` + `service.beta.kubernetes.io/azure-load-balancer-resource-group`, per Microsoft's current static-IP how-to, which states `loadBalancerIP` "is still functional but is being deprecated" and that the annotation form avoids LoadBalancer throttling. The resource-group annotation is required because the IP lives in the AKS **node** resource group.
- **Committed in:** `9c1d7bc1`

**6. [Rule 2 - Missing Critical] `jtoye_app` has no operator-bootstrap path on a managed server**

- **Found during:** Task 2
- **Issue:** both role SQL files reference `ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app`, but the only thing that creates that role is `infra/db/init/00-create-db.sql`, which runs **only on an empty PostgreSQL data directory** — i.e. on compose, never on a managed Flexible Server. It also binds one password to both the owner and the runtime role, which the Phase 28 split forbids, so it could not be reused even if it did run. Without this, the whole role bootstrap fails on a managed server with "must be member of role" and reads like an Azure privilege problem.
- **Fix:** a deliberately minimal STEP 4a creating `jtoye_app` with `DB_MIGRATION_PASSWORD`, granting `CREATE, USAGE ON SCHEMA public` (required since PostgreSQL 15 removed PUBLIC's CREATE) and `GRANT jtoye_app TO CURRENT_USER` so the two invoked files' `ALTER DEFAULT PRIVILEGES` clauses can execute. Marked in the file as the one piece of role SQL it owns, with the reason.
- **Verification:** arm 11 — the full path ran green against a real PostgreSQL 16.
- **Committed in:** `6f102ae8`

---

**Total deviations:** 6 auto-fixed (3 bugs, 3 missing critical). No Rule 4 architectural decisions were needed.

## Acceptance criteria corrected rather than silently substituted

**Task 1, the subscription-guard arm.** The criterion says to prove the refusal "by setting the
active subscription to any other id **in a subshell**". `az account set` is **not** subshell-scoped
— it writes `~/.azure/azureProfile.json`, which is global to this machine and shared with the
second session that drives the main checkout. Following it literally would leave the operator's
default subscription pointed at employer infrastructure if the restore failed, which is the very
outcome the guard exists to prevent. Replaced with a **strictly stronger** pair: an explicit
`--subscription <other-id>` arm plus two test doubles at the tool boundary (arms B and C), which
together exercise three independent refusal paths instead of one and mutate nothing. Both the
original method and the replacement are recorded here; the substitution is not silent.

**Task 1, `grep -n 'Standard_B2s' … returns only the default-assignment line`.** The tree gives a
**stricter** result: there is no default-assignment line at all, because the SKU is read from the
decision record. Measured: **0** occurrences in executable code (comments stripped), **1** total —
a comment quoting the record's cell format so the parser's expectation is documented. The
instrument was shown able to fire: the same awk returns **1** against a scratch file that does
hardcode the SKU. Recorded rather than reported as satisfied-as-written.

**Task 3, `gate-enforcement.conf`.** The plan lists this file under `files_modified` and asks to
"satisfy check-gate-enforcement.sh's default-deny for anything this plan adds". This plan adds no
`scripts/check-*.sh`, and **an entry naming a non-gate makes the checker exit 2 for every gate** —
measured (arm 19) before writing anything. The file therefore gains a **comment block** (which the
parser ignores) recording that reasoning, not a row. Doing what the wording literally suggested
would have taken the gate-wiring check offline on every PR.

## Issues Encountered

**The fail direction was the default state, not a contrived one.** The subscription guard's refusal
path is what this machine does with no flags at all. That is unusual and worth stating: most guards
in this repo have to be provoked into refusing, and this one has to be provoked into passing.

**A power-of-two file size deserved a second look.** `ingress-nginx` `deploy.yaml` came back at
exactly 16,384 bytes, which is the shape of a truncated buffer. Two independent fetches agreed
byte-for-byte and a structural scan found 670 lines, 19 documents and every expected kind, so the
size is genuine — but it was checked rather than assumed, because a digest pinned to a truncated
file would be a permanently-passing check over a broken artefact.

**Azure Cost Management was not re-queried.** 29-01 recorded that four of six attempts returned
HTTP 429. Nothing in this plan needs cost data at runtime — the ceiling is read from the record —
so the throttling was avoided rather than worked around.

## Known Stubs

None. All three scripts are complete and re-runnable. What has **not** happened, by design, is the
live execution:

- No Azure resource was created, deleted or scaled (10 before, 10 after). Plan **29-10** runs
  `azure-staging-provision.sh` for real, after applying the snackpass disposition.
- No cluster was touched. There is no staging cluster yet, and the only context on this host is the
  employer's — which every script refuses.
- The `--roles-only` path was proven against a throwaway PostgreSQL 16, not against the managed
  Flexible Server, which does not exist yet.
- The security headers have **not** been observed on a response. The header-serving route is set and
  announced by the script; the `curl -sI … strict-transport-security` proof belongs to plan **29-11**
  and is written into both the script's own NEXT block and `k8s/QUICK_START.md`.

## Threat Flags

None beyond the plan's own register. Every disposition in `<threat_model>` was implemented and
exercised:

| Threat | Disposition | Evidence |
|---|---|---|
| T-29-05-01 employer subscription | mitigate | arms A/B/C, three independent refusal paths |
| T-29-05-02 employer kube context | mitigate | arms 3/4/5 and 16/17/18, all rc=2, all naming the cluster |
| T-29-05-03 substituted manifest | mitigate | arms 14 (size) and 15 (same-size digest tamper) |
| T-29-05-04 credential in a tracked artifact | mitigate | 0 literal assignments, instrument shown able to return 2 |
| T-29-05-05 backup role without BYPASSRLS | mitigate | arms 6 and 7, both directions, on a real PostgreSQL 16 |
| T-29-05-06 headers silently absent | mitigate | both ConfigMap keys set + the CVE acceptance dated; served-response proof is 29-11's |
| T-29-05-07 long-lived CI credential | mitigate | user-assigned identity + exact-match federated subject; no client secret anywhere |
| T-29-05-SC supply chain | mitigate | three sha256 digests computed and each double-fetched; no npm/PyPI in scope, recorded not skipped |

One addition to the register's spirit rather than its letter: the STEP 1c source assertion, which
makes T-29-05-01's mitigation structural (no invocation can reach the ambient default) instead of
depending on every future edit remembering the pin.

## Cross-Cutting Quality Contracts

- **Web performance** — N/A (no user-facing page touched).
- **SEO / discoverability** — N/A (no public surface touched).
- **AI agent-readiness** — N/A (no API surface). All three scripts emit the repo's uniform 0/1/2
  exit contract, and defect #1 above was precisely a violation of that contract being silently
  repaired.
- **Security** — covered under Threat Flags. The load-bearing additions are the subscription and
  context refusals, the digest verification, and the two-directional role assertion.
- **Falsifiable evidence + runtime parity** —
  **(a)** every acceptance criterion was run in its fail direction first; 20 arms recorded with both
  directions; three criteria corrected rather than silently substituted; the clean state asserted
  **last** in each task; and the one restore that mattered (`gate-enforcement.conf`) was proven by
  `git hash-object` before and after (`6e3ec550…`, then `d2ad29ac…` for the edited file), never by
  `git diff --stat`.
  **(b)** Runtime parity: this plan ships **no runtime artefact** — nothing is built, deployed or
  restored, and no Azure or Kubernetes state changed. `check-runtime-freshness.sh` and
  `check-container-config-drift.sh` are deliberately **not** run here: a worktree's directory name
  changes the compose project name and would VOID them, so they belong to the main checkout.

## User Setup Required

Before plan 29-10 can run these for real:

| Item | Needed by | State |
|---|---|---|
| `PG_ADMIN_USER` / `PG_ADMIN_PASSWORD` | `azure-staging-provision.sh` STEP 6 | operator-supplied, generate with `openssl rand -hex 32` |
| Gmail app password + From/To | `staging-secrets.sh` (`ALERTMANAGER_SMTP_*`) | **ABSENT** — recorded in 29-01 §7.3, operator-only |
| AWS keys, media + backup | `staging-secrets.sh` (`AWS_*`) | **ABSENT** — recorded in 29-01 §7.2; also blocks 29-13 / #294 |
| Netlify DNS portal access | the four A records | **UNCONFIRMED** — 29-01 §7.5 |

`GRAFANA_ADMIN_PASSWORD` is self-suppliable (`openssl rand -hex 32`) per 29-01 §7.4 and is not an
operator blocker. `GHCR_VISIBILITY` is resolved: PUBLIC, so no `imagePullSecret` — the script reads
that from the record and skips the Secret with the reason printed.

## Next Phase Readiness

- **29-09** inherits two horizon rows this plan names but does not write (that file is another
  plan's, to avoid a parallel-wave conflict): **ingress-nginx `controller-v1.15.1`** — no security
  fixes after March 2026, plus a recorded Gateway-API migration deferral — and the three pinned
  artefact digests. Both reasons are in `staging-bootstrap.sh`'s header so they survive
  independently of the row.
- **29-10** runs `azure-staging-provision.sh` after applying the snackpass disposition. Its evidence
  block is the input to everything after it; the four values later plans cannot infer are the node
  resource group, the static ingress IP, the Postgres FQDN and the AKS egress IP.
- **29-07** reads `alertmanager-smtp` (`username`, `password`, `from`, `to`) and must declare
  `L3_SINK_TO`/`L3_HUMAN_TO` — 29-03 made a two-destination receiver with either unset a VOID.
- **29-11** owns the served-header proof. Do not read "the ConfigMap says true" as "the headers are
  sent": Pitfall 5 has no warning sign by construction.
- **29-16** inherits the #100/#300 deferral reason, now written into `k8s/QUICK_START.md` rather
  than living only in a plan.

## Self-Check: PASSED

Claims verified rather than trusted:

- **Files exist:** `scripts/azure-staging-provision.sh` (777 lines, mode 755),
  `scripts/staging-secrets.sh` (599, 755), `scripts/staging-bootstrap.sh` (546, 755),
  `scripts/gates/gate-enforcement.conf`, `k8s/QUICK_START.md` (435 lines).
- **Commits exist:** `12a61fb5`, `6f102ae8`, `9c1d7bc1` — all present in `git log`, all on
  `worktree-agent-ae201cce837f730c8`, none on a protected ref.
- **No deletions:** `git diff --diff-filter=D HEAD~1 HEAD` empty for each of the three commits.
- **`must_haves` artefacts:** all three exceed their `min_lines` (777 ≥ 200, 599 ≥ 150, 546 ≥ 150).
- **`key_links` patterns present:** `OPERATOR-DECISIONS` ×9 in the provisioning script,
  `rolbypassrls` ×7 in the secrets script, `sha256` ×14 in the bootstrap script.
- **Static gates:** `bash -n` rc=0 and `shellcheck -S error` rc=0 on all three (shellcheck 0.11.0
  via `koalaman/shellcheck:stable`); `./scripts/check-gate-enforcement.sh` rc=0 with its own
  measured counts (36 gates / 6 workflows / 6 exempt).
- **Zero cloud mutation:** `az resource list -g jtoye-rg --subscription <owner>` = **10** at plan
  start and **10** at plan end.
- **Shared state intact:** the throwaway container is gone (`docker ps -a` filter empty), and the
  shared dev Postgres still reads `jtoye_backup t/f`, `jtoye_runtime f/f`.

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-08-10*
