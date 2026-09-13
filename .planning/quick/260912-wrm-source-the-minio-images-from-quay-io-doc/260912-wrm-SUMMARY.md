---
phase: quick/260912-wrm
plan: 01
subsystem: infra/container-registry
tags: [supply-chain, registry, minio, docker-compose, horizons-gate, e2e-nightly]
status: TASKS 1-2 COMPLETE · TASK 3 NOT RUN (orchestrator-owned)
requires:
  - "quay.io serving minio/minio and minio/mc (verified by the orchestrator, not re-derived here)"
provides:
  - "every MinIO pull site in this repo names quay.io"
  - "horizon rows whose pin strings match the relocated source surface (H-1 exact string set membership)"
  - "advisory (NOTE-class) detection of MINIO_MC_IMAGE_REF provenance drift"
affects:
  - docker-compose.full-stack.yml
  - scripts/k8s-local-secrets.sh
  - infra/dependency-horizons.yaml
  - .env.example
tech-stack:
  added: []
  patterns: ["registry prefix hardcoded inline on the image ref, matching the keycloak precedent"]
key-files:
  created:
    - .planning/quick/260912-wrm-source-the-minio-images-from-quay-io-doc/260912-wrm-SUMMARY.md
  modified:
    - docker-compose.full-stack.yml
    - scripts/k8s-local-secrets.sh
    - infra/dependency-horizons.yaml
    - .env.example
    - .planning/quick/260912-wrm-source-the-minio-images-from-quay-io-doc/260912-wrm-PLAN.md
decisions:
  - "D-01 upheld: quay.io hardcoded inline, no MINIO_REGISTRY var, no new .env.example variable"
  - "D-01 rationale NARROWED: the repo does contain REGISTRY: ghcr.io, but it governs publishing this repo's own images, not pulling third-party bases"
  - "Task 2(c) hardening KEPT but RE-LABELLED: NOTE-class advisory detection, measured — not exit-2 enforcement as the plan claimed"
metrics:
  duration: "~50 min"
  completed: "2026-09-12"
  tasks_completed: "2 of 3 (Task 3 deliberately out of scope)"
---

# Quick 260912-wrm: Source the MinIO images from quay.io — Summary

Relocated all four MinIO image-pull sites from the now-authenticated Docker Hub path to
`quay.io`, carrying the `minio/mc` digest pin across byte-identical, and updated the two
dependency-horizon rows so H-1's exact string-set membership check still matches the source
surface. The nightly E2E lane was completely dark because `pull access denied for minio/mc`
killed the build step and skipped every later step.

**Scope note:** Task 3 (registry probes with the local cache defeated, the `minio-init`
recreate, the `quay.io/minio/minio` server probe, and the nightly dispatch) was deliberately
**NOT executed** — it mutates the live local environment and the orchestrator owns it. Nothing
in this summary claims registry or runtime evidence.

## What changed

| # | Site | Before | After |
|---|------|--------|-------|
| 1 | `docker-compose.full-stack.yml` minio `image:` (now line 597) | `minio/minio:${MINIO_IMAGE_TAG:-latest}` | `quay.io/minio/minio:${MINIO_IMAGE_TAG:-latest}` |
| 2 | `docker-compose.full-stack.yml` minio-init `image:` (now 628) | `minio/mc:${MINIO_MC_IMAGE_TAG:-latest}` | `quay.io/minio/mc:${MINIO_MC_IMAGE_TAG:-latest}` |
| 3 | `docker-compose.full-stack.yml` `MINIO_MC_IMAGE_REF:` (now 638) | `minio/mc:${MINIO_MC_IMAGE_TAG:-latest}` | `quay.io/minio/mc:${MINIO_MC_IMAGE_TAG:-latest}` |
| 4 | `scripts/k8s-local-secrets.sh` one-shot mc container | `"minio/mc:${MINIO_MC_IMAGE_TAG:-latest}"` | `"quay.io/minio/mc:${MINIO_MC_IMAGE_TAG:-latest}"` |
| 5 | `infra/dependency-horizons.yaml` `minio` row | `pin: "minio/minio:…"`, `sites: […:553]` | `pin: "quay.io/minio/minio:…"`, `sites: […:597]` |
| 6 | `infra/dependency-horizons.yaml` `minio-mc` row | `pin: "minio/mc:…"`, `sites: […:584]` | `pin: "quay.io/minio/mc:…"`, `sites: […:628, …:638]` |
| 7 | `.env.example` digest re-resolve runbook line | `… .RepoDigests 0}}' minio/mc:<tag>` (a registry that now 401s) | `… .RepoDigests 0}}' quay.io/minio/mc:<tag>` |

Plus WHY comment blocks (the 401 measurement with its date and controls, the identical-digest
finding, the nightly run id, and the D-01 inline-vs-variable reasoning) in the compose file, in
`scripts/k8s-local-secrets.sh`, in `.env.example`, and in both horizon-row `note:` fields.

`MINIO_MC_IMAGE_TAG`'s **value is byte-unchanged** and **no new env var was added** — asserted
at value level, and the variable-name list of `.env.example` diffs empty before vs after.

## Evidence — both directions, real output

### Task 1 — the four pull sites

Clean:
```
minio=1(rc=0) mc_compose=2(rc=0) k8s=1(rc=0)        # EXPECT 1 / 2 / 1, all rc=0
unprefixed_left_rc=1  out=[]                         # EXPECT rc=1, empty
digest_n=1 digest_rc=0                               # value-level, not line-exists
runbook_n=1 runbook_rc=0
varname_diff_rc=0 (no output)                        # no new/removed env var NAME
```

Break (`sed` the minio `image:` back to the docker.io form), then restore, then clean again:
```
POSITIVE grep under break: n=0 rc=1                  # capable of failing
NEGATIVE grep under break: rc=0
  docker-compose.full-stack.yml:597:    image: minio/minio:${MINIO_IMAGE_TAG:-latest}
restore: before=2154612a77… after=2154612a77… restore_ok=YES
closing clean: positive n=1 rc=0 · negative rc=1 out=[]
```

Two further `.env.example` arms, both restored and re-asserted by content hash
(`before=0cc0ff9499… after=0cc0ff9499… restore_ok=YES`):
```
runbook recipe reverted to minio/mc:<tag>  -> runbook grep n=0 rc=1
one digit of the pinned digest mutated     -> digest  grep n=0 rc=1
closing clean: runbook n=1 rc=0 · digest n=1 rc=0
```

`docker compose -f docker-compose.full-stack.yml config` → rc=0, resolving to
`quay.io/minio/minio:latest` (378), `MINIO_MC_IMAGE_REF: quay.io/minio/mc:latest` (418),
`image: quay.io/minio/mc:latest` (421), with zero bare `minio/` refs left in the resolved
document. **This is NOT pull evidence** — `config` never contacts a registry. It does confirm
the `image:` line and the provenance var resolve to the *identical* string, which is the
plan's `key_links` assertion.

### Task 2 — the horizons gate

Pre-edit baseline, re-measured on this branch (not cited from planning):
```
RC=0 · missing-row=0 · pin-not-at-site=6 · site-unresolvable=0
NOTEs: minio(553->575) minio-mc(584->606,616) ollama x2 mailhog go-ci-setup
```

Post-edit clean arm:
```
RC=0
  H-1 coverage   missing-row=0
  H-5 sites      pin-not-at-site=4
                 site-unresolvable=0
OK: every pinned artifact carries a resolved, in-window or explicitly-deferred horizon.
NOTE-drop confirmed 6->4 · minio_notes_n=0 rc=1   (no NOTE names minio or minio-mc)
```
The drop is asserted **positively**: an unchanged 6 would also have been produced by skipping
the `sites:` update entirely.

**Arm A1 — H-1 coverage (exit 1).** Reverted only the `minio` manifest pin:
```
A1_RC=1
FAIL: H-1 quay.io/minio/minio:${MINIO_IMAGE_TAG:-latest} is pinned in the declared source
      surface but has NO horizon row
missing-row=1
restore_ok=YES (59be4cc147… both sides)
```

**Arm A2 — H-5 drift → VOID (exit 2).** Pin string present on no non-comment line, first site
moved to `:1`:
```
A2_RC=2
site-unresolvable=2
H-5 minio-mc: declared pin 'quay.io/minio/mc:THIS-STRING-IS-IN-NO-FILE' NOT FOUND on any
  non-comment line of docker-compose.full-stack.yml (declared site …:1)
H-5 minio-mc: … (declared site …:638)
VOIDED: … Exit 2 takes precedence over the 1 contract violation(s) also reported.
restore_ok=YES
```
This also confirms the documented exit-2-over-exit-1 precedence, live.

**Arm A3 — the provenance-line hardening. THE PLAN'S PREDICTION WAS WRONG.** See Deviation 1.

Closing clean arm after every mutation (including the `chmod` fix):
```
HORIZONS_RC=0 · missing-row=0 · pin-not-at-site=4 · site-unresolvable=0
bash -n scripts/k8s-local-secrets.sh -> rc=0
docker compose config -> rc=0
scripts/k8s-local-secrets.sh mode -rwxr-xr-x
```

### Collateral gates

| Gate | rc | Reading |
|------|----|---------|
| `check-doc-citations.sh` | 0 | PASS, 50 citations / 0 violations. Confirms the +22-line comment insertion shifted no cited line: every line-numbered citation into this compose file is ≤479, verified by enumeration across `.planning/`, `docs/`, `k8s/`, `infra/`, `scripts/`. |
| `check-postgres-major-parity.sh` | 0 | PASS. Anchor-based, no line numbers — unaffected. |
| `check-gate-enforcement.sh` | 0 | PASS, 42 gates. |
| `check-infra-exposure.sh` | 2 | VOID — **pre-existing and environmental**, not this change. Control arm on the pre-edit compose: also rc=2, same reason (`Grafana not reachable on 127.0.0.1:3002, curl rc=7`). The minio loopback port rows are byte-identical in both runs. This gate is deliberately not wired into CI. |
| `check-container-config-drift.sh` | 2 | **NEW finding caused by this change — see Deviation 3.** |
| `check-dependency-horizons.sh` | 0 | PASS (above). |

Only `docker-compose.full-stack.yml` mentions minio among the repo's four compose files
(17 lines here; 0 in `infra/docker-compose.yml`, `infra/docker-compose.hostnet.yml`,
`infra/monitoring/docker-compose.monitoring.yml`) — with the 17 as the positive control proving
the search direction works, so the three zeros are genuine absences rather than a broken scan.

## Deviations from Plan

### 1. [Rule 1 — plan assertion refuted by measurement] Arm A3 exits 0, not 2: the provenance hardening is NOTE-class advisory detection, not enforcement

**Found during:** Task 2, arm A3.

**The plan's claim (Task 2(c)):** declaring `MINIO_MC_IMAGE_REF` as a second H-5 site "makes
H-5 assert the pin is present at that exact line, and H-5 absence is VOID (exit 2)".

**Measured.** Reverting ONLY the provenance line to `minio/mc:` while leaving `image:` on quay:
```
A3_RC=0                                  # PLAN EXPECTED 2
NOTE minio-mc: pin not at docker-compose.full-stack.yml:638; found at line(s) 628
pin-not-at-site=5                        # 4 -> 5
site-unresolvable=0
OK: every pinned artifact carries a resolved, in-window or explicitly-deferred horizon.
```

**Mechanism, read out of the gate** (`scripts/check-dependency-horizons.sh`, H-5 block): the
VOID/exit-2 class (`DRIFT`) fires only when the pin appears on **no** non-comment line of the
file, or the file is unreadable. The exact-line check is a separate, `LINE_DRIFT`/NOTE class
that is **exit-code-neutral**. Because the `image:` line always carries the pin, no manifest
shape can make a provenance-line divergence exit 2. The plan contradicted itself — its own
`<measured_state_do_not_re_derive>` block had this right ("a NOTE only, advisory,
exit-code-neutral") while the (c) rationale overstated it.

**Is it decorative? No — measured with a control arm.** Same break, but with the OLD
single-site declaration:
```
A3CONTROL_RC=0 · pin-not-at-site=4 · minio_mc_NOTE_n=0 (rc=1)   # TOTAL SILENCE
```
So the second site is the difference between a named NOTE in CI output and no signal at all.

**Resolution:** the two-site declaration is **kept** (strictly additive detection) and
**re-labelled everywhere** — in the `minio-mc` row's `note:`, in PLAN.md (an
`EXECUTION CORRECTION` under (c), plus the `<done>`, threat `T-wrm-03` and success-criterion 3
rows that repeated the exit-2 claim), and here. **Do not cite it as a blocking gate.** Residual
accepted: a provenance-line divergence can still merge.

### 2. [Rule 1 — bug I introduced and fixed] Executable bit lost on `scripts/k8s-local-secrets.sh`

The `awk` rewrite that inserted the WHY comment wrote a fresh file, dropping the mode:
`old mode 100755 / new mode 100644` appeared in the diff. `chmod 755` restored it; the diff now
carries no mode line and `ls -l` shows `-rwxr-xr-x`. `k8s-local-up.sh:439` invokes it as
`bash "$SCRIPT_DIR/k8s-local-secrets.sh"` so that path was unaffected, but `k8s/LOCAL.md:168`
documents running it directly — which the lost bit **would** have broken.

### 3. [Finding, not a fix — orchestrator decision] `check-container-config-drift.sh` now reports minio image DRIFT, and D-02's honesty argument has a gap

D-02 refuses to recreate the running `jtoye-minio` (an unreviewed ~12-month version jump is out
of scope for a registry relocation) and justifies that as honest under the runtime-parity
contract because `check-runtime-freshness.sh` is per-service over **built** services and minio
has no build paths. That is true of that gate — but `check-container-config-drift.sh` exists
specifically to cover that gap (its header cites TS-16: "a service running a third-party image
whose COMPOSE CONFIG changed is outside [runtime-freshness's] scope entirely"), and its D-3 rule
compares the declared `image:` against `.Config.Image` for **non-built** services. minio is one.

Measured, with a control:
```
POST-EDIT:  minio  DRIFT
                image   declared: quay.io/minio/minio:latest
                        running : minio/minio:latest
PRE-EDIT (control, same running stack):  minio  MATCH
```
Both runs exit 2, but from a **pre-existing** VOID unrelated to minio (the monitoring compose
file has 5 declared services and none running) and both show `minio-init NOT RUNNING` already.

**Not fixed here, deliberately:** the remedy is recreating a container, which is Task 3 /
orchestrator territory, and recreating the *server* is what D-02 refuses. The gate is **not
wired into any CI workflow** (only a comment at `ci-cd.yaml:1066` listing it among live-runtime
gates), so **CI is unaffected** — but a local gate sweep will now show this row until
`jtoye-minio` is recreated. Flagged for the orchestrator rather than silently absorbed.

### 4. [Plan artifact correction, per orchestrator] The `*_REGISTRY` absolute claim was too strong

PLAN.md D-01 asserted "There is no `*_REGISTRY` var anywhere in the repo." Verified false:
`REGISTRY: ghcr.io` at `.github/workflows/ci-cd.yaml:12`, also `scripts/build-images.sh:8`, read
by `scripts/check-image-supply-chain.sh:480`. It governs **publishing** this project's own images
to ghcr, never **pulling** third-party base images, so it is not a counter-precedent and decision
(a) stands. Narrowed in PLAN.md to the true claim — *no registry variable governs third-party
image pulls* — and the false absolute is not repeated in any committed artifact.
(`check-image-supply-chain.sh` has zero matches for `minio`, the compose file, or `image:`, so it
is unaffected by this change.)

### 5. [Method deviation, stated] Break-arm restores used content snapshots, not `git checkout`

The plan says commit before running break arms because `git checkout` restores from the index.
Here the four files are **tracked** and the work was **uncommitted**, so the index held the
**pre-edit** tree — `git checkout --` would have destroyed the work, which is the very trap that
rule exists to avoid. Every arm therefore restored from a content snapshot taken after the edits,
and **every restore was verified by sha256 before/after** with a closing clean re-assert. The
restore-verification property the rule protects is satisfied by a stronger method;
`git diff --stat` was never used as a restore check.

## Authentication gates

None.

## Known Stubs

None. No code paths, no UI, no data wiring — four image references, two manifest rows and
documentation.

## Threat Flags

None new. The change narrows nothing and widens nothing in the ASVS surface: the digest pin is
carried across unchanged, the registry host stays a committed reviewed literal (T-wrm-02), and
no credential handling is touched. `T-wrm-03`'s disposition is **weakened in effect** from what
the plan assumed — see Deviation 1 — and is recorded in the plan's threat register rather than
left implied.

## Not verified here — Task 3, orchestrator-owned

Everything below is **unverified by this executor** and no claim in this document depends on it:

- **Registry truth.** quay.io manifest probes with their controls (bogus digest → 404, Docker
  Hub still 401, redis control → 200). The orchestrator measured these; they were not re-derived.
- **Cache-defeated pull.** This daemon uses the containerd image store, whose content store is
  shared across repositories **by digest**, and the local `minio/mc:latest` **is** the pinned
  digest `sha256:a7fe349e…` — so a digest pull can be served with zero registry contact. The
  absence must be *shown* before any pull counts as evidence.
- **`minio-init` recreate** (`--no-deps --force-recreate --pull always`, with
  `MINIO_MC_IMAGE_TAG` passed INLINE because the local `.env` declares neither var), its
  `minio-init-image-ref=quay.io/…` log line, and the all-zeros-digest break arm that must fail
  `not found` **naming quay.io** with no `latest` fallback.
- **The `quay.io/minio/minio` server probe** under this repo's exact
  `server /data --console-address ":9001"` command line, and its bogus-flag arm.
- **The nightly dispatch** on this branch: `Build and start the stack (no ollama)` and
  `Wait for core-java and the frontend` must both be `success`. The run as a whole is **expected
  to go red** on `Gate — cart identity boundary (#459 / R-16)` because the locator fix lives on
  an unmerged branch (PR #742) — that is not this change failing.
- **`docs/CHANGELOG.md` is deliberately untouched.** The commit subject is `fix(...)`, so
  `check-changelog-cites-pr.sh` requires an entry citing `(#<PR>)`, and the PR number does not
  exist until the PR is open. Sequencing (open PR → add the entry citing the **PR** number, not
  the issue → push again) is the orchestrator's, as is the squash-not-rebase merge.

## Self-Check: PASSED

Files asserted present:
```
FOUND: docker-compose.full-stack.yml
FOUND: scripts/k8s-local-secrets.sh
FOUND: infra/dependency-horizons.yaml
FOUND: .env.example
FOUND: .planning/quick/260912-wrm-source-the-minio-images-from-quay-io-doc/260912-wrm-PLAN.md
FOUND: .planning/quick/260912-wrm-source-the-minio-images-from-quay-io-doc/260912-wrm-SUMMARY.md
```
Commit hash recorded in the execution report. Branch `feature/minio-images-via-quay`,
`git log HEAD..origin/main` empty at start of execution (re-measured, not cited).
