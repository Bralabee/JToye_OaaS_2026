---
phase: quick/260912-wrm
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - docker-compose.full-stack.yml
  - scripts/k8s-local-secrets.sh
  - infra/dependency-horizons.yaml
  - .env.example
  - docs/CHANGELOG.md
autonomous: false
requirements: [QUICK-260912-wrm]
branch: feature/minio-images-via-quay
base: origin/main @ e911ef58 (behind-count measured 0)

must_haves:
  truths:
    - "Every place this repo pulls a MinIO image names quay.io, not the now-authenticated Docker Hub path"
    - "The minio/mc digest pin is byte-identical after the move — same sha256, same release tag"
    - "A wrong digest against quay.io still fails the pull loudly, naming quay.io, with no fallback to latest"
    - "The minio-init provenance echo states the reference that actually ran, and is now gate-enforced"
    - "check-dependency-horizons.sh stays exit 0 with missing-row=0 and site-unresolvable=0"
    - "The nightly reaches PAST `Build and start the stack (no ollama)` and PAST `Wait for core-java and the frontend`"
  artifacts:
    - path: docker-compose.full-stack.yml
      provides: "quay.io-prefixed minio + minio-init image refs and the MINIO_MC_IMAGE_REF provenance var"
      contains: "quay.io/minio/mc:${MINIO_MC_IMAGE_TAG:-latest}"
    - path: scripts/k8s-local-secrets.sh
      provides: "quay.io-prefixed one-shot mc container for the backup-bucket bootstrap"
      contains: "quay.io/minio/mc:${MINIO_MC_IMAGE_TAG:-latest}"
    - path: infra/dependency-horizons.yaml
      provides: "minio + minio-mc horizon rows whose pin strings match the source surface exactly"
      contains: "quay.io/minio/minio:${MINIO_IMAGE_TAG:-latest}"
    - path: .env.example
      provides: "the digest-re-resolve runbook line pointing at a registry that still answers"
      contains: "quay.io/minio/mc:<tag>"
  key_links:
    - from: "docker-compose.full-stack.yml minio-init image:"
      to: "docker-compose.full-stack.yml MINIO_MC_IMAGE_REF"
      via: "both must carry the identical reference string, or the run-time provenance echo lies"
      pattern: "quay\\.io/minio/mc:\\$\\{MINIO_MC_IMAGE_TAG:-latest\\}"
    - from: "infra/dependency-horizons.yaml pin:"
      to: "docker-compose.full-stack.yml image:"
      via: "H-1 exact string set membership + H-5 exact-line presence"
      pattern: "quay\\.io/minio/(minio|mc):"
---

<objective>
MinIO's Docker Hub repositories started requiring authentication between 2026-09-09 and
2026-09-12. The nightly E2E lane is completely dark: run 34722659896 died at
`Build and start the stack (no ollama)` with
`pull access denied for minio/mc, repository does not exist or may require 'docker login'`,
and because that step fails every later step — the whole Playwright suite and all four gates —
is `skipped`.

Relocate every MinIO image pull to quay.io, which serves the same bytes under the same tag,
and prove it without re-pinning the digest and without recreating the running MinIO server.

Purpose: restore the only lane that exercises the real stack end to end.
Output: four pull sites + two tracking rows + one runbook line moved to quay.io; a nightly
dispatch on this branch that reaches past the build and the readiness wait.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
@.claude/skills/proof-standards/SKILL.md
</execution_context>

<context>
@CLAUDE.md
@.planning/phases/28-security-triage-the-dev-prod-boundary/28-09-SUMMARY.md

Source files (read the exact ranges, once each — do not re-read):
- docker-compose.full-stack.yml lines 570-640 (minio + minio-init)
- scripts/k8s-local-secrets.sh lines 285-300 (the one-shot mc container)
- infra/dependency-horizons.yaml lines 190-225 (the two minio rows) and lines 1-80 (its header)
- .env.example lines 184-203 (the MinIO block)
</context>

<measured_state_do_not_re_derive>
All of the following was measured during planning on 2026-09-12. Treat it as given.

REGISTRY (anonymous pull token, against the registry Docker actually uses), with controls:
  library/redis:7-alpine                 -> 200   (positive control: the probe works)
  library/postgres:15-alpine             -> 200   (positive control)
  minio/mc:latest                        -> 401
  minio/minio:latest                     -> 401
  minio/mc:RELEASE.2025-08-13T08-35-41Z  -> 401   (a PINNED tag is gated too)
  quay.io/minio/mc:latest                -> 200
  quay.io/minio/minio:latest             -> 200
401 not 404 => auth gate, not deletion, and it covers minio/minio as well as minio/mc.

THE DIGEST IS PRESERVED EXACTLY — THE LOAD-BEARING FACT:
  quay.io/minio/mc@sha256:a7fe349e…                  -> 200
  quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z      -> 200, docker-content-digest
                                                        sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727
  CONTROL: an all-zeros digest on the same quay repo  -> 404 (the probe discriminates)
Same bytes, same tag->digest mapping. This is a pure registry relocation.
DO NOT re-pin to a different artifact. DO NOT change MINIO_MC_IMAGE_TAG's value.

THE FOUR PULL SITES (current line numbers; re-measure before editing, they will shift):
  1. docker-compose.full-stack.yml:575  image: minio/minio:${MINIO_IMAGE_TAG:-latest}
  2. docker-compose.full-stack.yml:606  image: minio/mc:${MINIO_MC_IMAGE_TAG:-latest}
  3. docker-compose.full-stack.yml:616  MINIO_MC_IMAGE_REF: minio/mc:${MINIO_MC_IMAGE_TAG:-latest}
  4. scripts/k8s-local-secrets.sh:294   --entrypoint /bin/sh "minio/mc:${MINIO_MC_IMAGE_TAG:-latest}"

NOTHING ELSE PULLS THESE IMAGES. Measured repo-wide with `rg -uu` over tracked and untracked
files, controls included:
  - e2e-nightly.yml is the ONLY workflow that starts docker-compose.full-stack.yml
    (COMPOSE_FILE at :71, `up -d --build $SERVICES` at :242). ci-cd.yaml does not.
  - No k8s manifest pulls a MinIO image: k8s/local shims every endpoint to
    host.minikube.internal and CONSUMES the host compose MinIO (k8s/LOCAL.md:56).
  - scripts/check-media-content-types.sh mentions `minio/mc` only in prose; it reaches `mc`
    by `docker exec` into the running MinIO container and has, by its own header, "no
    image-pull dependency".
  - Remaining `minio/(mc|minio)` hits are GitHub release URLs and comment prose.

THE HORIZONS GATE — how it reacts, measured by reading the script:
  - H-1 coverage is EXACT STRING SET MEMBERSHIP: it greps the literal `image:` value out of
    the compose files and fails (exit 1) unless that exact string is some row's `pin`. So
    `pin:` MUST carry the registry prefix once the compose line does.
  - The schema ALREADY holds a registry-prefixed pin and is green on it today:
    `pin: "quay.io/keycloak/keycloak:24.0.5"` (infra/dependency-horizons.yaml:112).
  - H-5 drift uses `grep -nF` over non-comment lines: ABSENT -> VOID (exit 2). A declared
    site line that no longer carries the pin is a NOTE only, advisory, exit-code-neutral.
  - BASELINE measured on this branch before any edit:
      bash scripts/check-dependency-horizons.sh  -> RC=0
      H-1 coverage missing-row=0 · H-5 pin-not-at-site=6 (NOTE) · site-unresolvable=0
      The six NOTEs: minio(553->575), minio-mc(584->606,616), ollama x2(621,686->643,708),
      mailhog(702->724), go-ci-setup(1269->53,907,1272).
  - Wired in CI at .github/workflows/ci-cd.yaml:918 (`ops-contracts` job).

LOCAL DOCKER STATE — and the cache trap, with its mechanism:
  minio/minio:latest  id/digest sha256:14cea493d9a3…  12 months old  (jtoye-minio IS RUNNING on it, up 3 days healthy)
  minio/mc:latest     id/digest sha256:a7fe349ef4bd…  12 months old  == THE PINNED DIGEST
  docker info Driver=overlayfs, DriverStatus contains `io.containerd.snapshotter.v1`
  => this daemon uses the CONTAINERD IMAGE STORE, whose content store is shared across
     repositories BY DIGEST. So `docker pull quay.io/minio/mc@sha256:a7fe349e…` can be
     satisfied entirely from local content WITHOUT EVER CONTACTING QUAY.IO. A successful
     digest pull is therefore NOT registry evidence until the local copy is gone.

THE LOCAL .env DOES NOT DECLARE EITHER VAR:
  rg -uu -c '^MINIO_MC_IMAGE_TAG=' .env -> rc=1 (zero matches)
  rg -uu -c '^MINIO_IMAGE_TAG=' .env    -> rc=1 (zero matches)
  So locally compose resolves `:-latest`. 28-09 recorded WHY: the secret-path hook blocks
  editing `.env`, and the pin was proven by passing `MINIO_MC_IMAGE_TAG=<pin>` INLINE on the
  compose invocation (shell env overrides .env). Reuse that recipe. Without it, a local
  "proof" proves `:latest`, not the digest pin.

CITATION SAFETY: the only line-numbered citations into docker-compose.full-stack.yml from the
check-doc-citations.sh scope are `:43` (.planning/codebase/STACK.md) and `:310` (k8s/LOCAL.md).
Both are ABOVE the MinIO block, so inserting comment lines at 573+ cannot shift them.

docs/metrics.json delta is ZERO: no Java @Test, Jest/vitest block, Go Test func or Playwright
test is touched. Do not run docs-freshness.sh --write.
</measured_state_do_not_re_derive>

<decision id="D-01" title="Hardcode the quay.io prefix inline; do NOT introduce a MINIO_REGISTRY var">
**Chosen: option (a)** — `quay.io/minio/mc:${MINIO_MC_IMAGE_TAG:-latest}` at each site.
Rejected: option (b), `${MINIO_REGISTRY:-quay.io}/minio/mc:…`.

Measured against what the file already does, not assumed:
- docker-compose.full-stack.yml has 10 `image:` refs. Exactly ONE carries a registry prefix —
  `quay.io/keycloak/keycloak:24.0.5` at line 145 — and it is hardcoded inline. ZERO carry a
  registry variable. EXECUTION CORRECTION (2026-09-12): the original claim here — "there is no
  `*_REGISTRY` var anywhere in the repo" — was too strong and is withdrawn.
  `REGISTRY: ghcr.io` exists at `.github/workflows/ci-cd.yaml:12` (also
  `scripts/build-images.sh:8`, read by `scripts/check-image-supply-chain.sh:480`). It governs
  PUBLISHING this project's OWN images to ghcr, never PULLING third-party base images, so it is
  not a counter-precedent and decision (a) stands. The true, narrower claim: no registry
  variable governs third-party image pulls.
- infra/dependency-horizons.yaml already holds `pin: "quay.io/keycloak/keycloak:24.0.5"` and
  the horizons gate is green on it (measured RC=0 this session). So a registry-prefixed `pin:`
  is an established, gate-proven shape in that schema — not a new experiment.

Why this does not violate GLOBAL_RULE_6 / ARCHITECTURE_RULE_8 ("never hardcode values that
vary by environment; inject via ONE config layer"):
- The rule governs values that VARY BY ENVIRONMENT. This one does not. There is exactly one
  runtime that pulls these images — the compose stack, serving local dev and the nightly — and
  it reaches the public internet in both. A `MINIO_REGISTRY` var would hold the identical value
  in every environment this repo has. A knob with one setting is not injection.
- The part that genuinely varies IS already injected through one config layer: the tag/digest,
  via `MINIO_IMAGE_TAG` / `MINIO_MC_IMAGE_TAG` declared in `.env.example`. The rule is
  satisfied for the varying part and always has been.
- (b) would actively weaken the property the pin exists for (#270). `minio-init` runs with the
  ROOT object-storage credentials. Making its registry HOST — the supply-chain origin —
  settable from the environment hands an env var the power to choose the trust anchor for a
  root-credentialed container. The digest constrains the bytes; it does not make an arbitrary
  host an acceptable place to ask for them, and a typo'd or injected host turns a reviewed pull
  into an unreviewed one. The registry host belongs in committed, reviewed source on the same
  line as the repo name.
- Gate consequence seals it: H-1 compares the literal `image:` string to `pin:` character for
  character. Under (a) the manifest row is a literal, greppable string. Under (b) the pin would
  be `${MINIO_REGISTRY:-quay.io}/minio/mc:${MINIO_MC_IMAGE_TAG:-latest}` — a second layer of
  indirection inside a field whose whole documented purpose (that file's header) is that values
  are MEASURED, not derived.
- Cost if MinIO relocates again: a four-line diff across two files, and doing it inconsistently
  is caught by H-1 (exit 1) and H-5 (exit 2). Cheaper than a permanent knob nobody asked for.

Consequence for `.env.example`: **no new variable is declared.** The existing MinIO block gets
(i) a WHY line recording the 401 measurement and its date, and (ii) a correction to the
digest-re-resolve recipe at :201, which currently names `minio/mc:<tag>` — a registry that now
401s, i.e. a runbook line that is already broken.
</decision>

<decision id="D-02" title="Recreate minio-init to prove the move; do NOT recreate the minio server">
`minio-init` — RECREATE. It is a one-shot that exits 0, its work (`mb --ignore-existing` +
`anonymous set-json`) is idempotent and changes no data, it builds nothing, and it is the only
place the digest pin is actually exercised. Use `--no-deps --force-recreate --pull always` so
(i) the running `minio` is untouched, and (ii) nothing is built — which structurally avoids the
recorded trap that `docker compose up -d --build <service>` RE-TAGS dependency images, and
keeps `core-java` untouched so `check-alert-metrics` is not reddened and
`scripts/seed-order-metric.sh` does not need re-running.

`minio` (the server) — DO NOT RECREATE. The running container is on `minio/minio:latest`
digest `sha256:14cea493…`, pulled 12 months ago. `quay.io/minio/minio:latest` is TODAY's
latest. Recreating it would be an unbounded, unreviewed ~12-month version jump on the canonical
local dev + E2E runtime, against a live `minio_data` volume, inside a change whose entire scope
is "relocate the registry". Refused.

Why that is still honest under CLAUDE.md's runtime-parity contract, stated by mechanism:
- The `image:` string is consulted only on container CREATE, so the running server cannot
  disagree with the branch in any way that affects behaviour today.
- `scripts/check-runtime-freshness.sh` is per-service over BUILT services (image
  `.Metadata.LastTagTime` vs the newest commit touching that service's build paths). `minio`
  has no build paths, so it is outside that gate's scope by construction, not by omission.
- No tag is re-pointed and no bytes change, so this is a REFERENCE difference, not a staleness
  defect.
- The server ref is proven instead by Task 3's cache-defeating pull AND by actually running the
  pulled image with this repo's exact command line in a throwaway container. The nightly, which
  tears down with `down -v` and starts from a fresh volume, is where the new ref takes effect
  for the server — and that dispatch is this plan's terminal proof.
- Recorded residual, explicitly out of scope: `minio/minio` is still an unpinned floating
  `:latest` (28-09 Deviation 4 already records this). Pinning it is a separate decision and is
  NOT taken here.
</decision>

<tasks>

<task type="auto">
  <name>Task 1: Relocate the four pull sites and fix the .env.example runbook line</name>
  <files>docker-compose.full-stack.yml, scripts/k8s-local-secrets.sh, .env.example</files>
  <action>
Per D-01, hardcode `quay.io/` inline at all four pull sites. Change ONLY the registry prefix —
the repo name, the tag, the `${VAR:-latest}` indirection and the digest all stay byte-identical.

Re-measure the line numbers first; never edit by the numbers quoted above:
  rg -uu -n 'minio/(mc|minio):\$\{MINIO' docker-compose.full-stack.yml scripts/k8s-local-secrets.sh

Then make exactly these four substitutions:
  (1) compose, minio service `image:`        minio/minio:  ->  quay.io/minio/minio:
  (2) compose, minio-init `image:`           minio/mc:     ->  quay.io/minio/mc:
  (3) compose, `MINIO_MC_IMAGE_REF:`         minio/mc:     ->  quay.io/minio/mc:
  (4) scripts/k8s-local-secrets.sh:294       minio/mc:     ->  quay.io/minio/mc:

Site (3) is not cosmetic. It is echoed at run time as `minio-init-image-ref=` so a future run
STATES which image it ran. If it is left naming docker.io while the container pulls from quay,
the provenance line starts lying — the exact defect the echo was added to prevent. Task 2
makes that an enforced gate rather than a convention.

Then, in docker-compose.full-stack.yml, extend the EXISTING comment block above the minio
service with the WHY, in this repo's style (the measurement and its date, so a future reader
does not "helpfully" revert it). Record: MinIO's Docker Hub repos began requiring auth between
2026-09-09 and 2026-09-12; anonymous manifest probes returned 401 for minio/minio:latest,
minio/mc:latest AND the pinned tag minio/mc:RELEASE.2025-08-13T08-35-41Z, while redis and
postgres on the same probe returned 200 (so it is MinIO's gate, not a broken probe); quay.io
serves the identical digest sha256:a7fe349e… under the identical tag; nightly run 34722659896
died at the build step with `pull access denied for minio/mc`, which skipped the entire suite.
Also state, per D-01, that the prefix is inline rather than a var because the registry does not
vary by environment and `quay.io/keycloak/keycloak:24.0.5` at line 145 is the in-file precedent.

In `.env.example`, inside the existing MinIO block (lines ~193-202):
  - Fix the re-resolve recipe: `docker inspect --format '{{index .RepoDigests 0}}' minio/mc:<tag>`
    -> `... quay.io/minio/mc:<tag>`. As written today that command asks a registry that 401s,
    so the recorded upgrade runbook is already broken.
  - Add one line stating that MinIO images come from quay.io (Docker Hub requires auth as of
    2026-09-12) and that the digest is unchanged by the move.
  - Do NOT add a new variable (D-01). Do NOT change the value of MINIO_MC_IMAGE_TAG.
  - Do NOT edit `.env` — it is a secret path and the hook blocks it, correctly (28-09 Rule-3
    finding). Inline env on the compose invocation is the sanctioned route.

Do not touch anything under .planning/, docs/audit/remediation/04-devops-remediation.md, or
graphify-out/ — they record what WAS true.
  </action>
  <verify>
    <automated>
# POSITIVE: all four sites relocated, and the mc sites agree with each other.
# Named-file greps (not a tree scan), rc printed, counts from -uu so .gitignore cannot lie.
set -u
n_minio=$(rg -uu -c 'image: quay\.io/minio/minio:\$\{MINIO_IMAGE_TAG:-latest\}' docker-compose.full-stack.yml); rc1=$?
n_mc=$(rg -uu -c 'quay\.io/minio/mc:\$\{MINIO_MC_IMAGE_TAG:-latest\}' docker-compose.full-stack.yml); rc2=$?
n_k8s=$(rg -uu -c 'quay\.io/minio/mc:\$\{MINIO_MC_IMAGE_TAG:-latest\}' scripts/k8s-local-secrets.sh); rc3=$?
echo "minio=$n_minio(rc=$rc1) mc_compose=$n_mc(rc=$rc2) k8s=$n_k8s(rc=$rc3)"
# EXPECT: minio=1 mc_compose=2 (image: + MINIO_MC_IMAGE_REF:) k8s=1, all rc=0.

# NEGATIVE: zero un-prefixed pull sites left. Anchor on the `image:`/`MINIO_MC_IMAGE_REF:`/
# `--entrypoint` shapes so the GitHub-release URLs and comment prose cannot satisfy it.
left=$(rg -uu -n '(image:[[:space:]]*minio/|MINIO_MC_IMAGE_REF:[[:space:]]*minio/|"minio/mc:)' docker-compose.full-stack.yml scripts/k8s-local-secrets.sh); rc4=$?
echo "unprefixed_left_rc=$rc4"; printf '%s\n' "$left"
# EXPECT: rc4=1 and empty output.

# The digest is untouched — assert the VALUE, not that a line exists.
rg -uu -c '^MINIO_MC_IMAGE_TAG=RELEASE\.2025-08-13T08-35-41Z@sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727$' .env.example; echo "digest_rc=$?"
# EXPECT: 1 and digest_rc=0.

# The runbook line no longer names a 401 registry.
rg -uu -c "RepoDigests 0.*quay\.io/minio/mc:<tag>" .env.example; echo "runbook_rc=$?"
# EXPECT: 1 and runbook_rc=0.

# FAIL DIRECTION — the greps above must be shown capable of failing. COMMIT FIRST
# (git checkout restores from the INDEX, so an uncommitted break arm eats the work),
# then bracket clean -> break -> clean and verify the restore BY CONTENT:
#   before=$(sha256sum docker-compose.full-stack.yml | cut -d' ' -f1)
#   sed -i 's#image: quay\.io/minio/minio:#image: minio/minio:#' docker-compose.full-stack.yml
#   -> the positive grep must report 0 / rc=1 AND the negative grep must report rc=0 with a hit
#   git checkout -- docker-compose.full-stack.yml
#   after=$(sha256sum docker-compose.full-stack.yml | cut -d' ' -f1); [ "$before" = "$after" ]
# Record all three arms' real output. `git diff --stat` is NOT an acceptable restore check:
# it is empty both when a file is restored and when it was never written.

# docker compose resolves the new refs. STATE IN THE SUMMARY THAT THIS IS NOT PULL EVIDENCE —
# `config` never contacts a registry. Task 3 owns the pull.
docker compose -f docker-compose.full-stack.yml config 2>/dev/null | rg -uu -n 'quay\.io/minio'; echo "config_rc=$?"
    </automated>
  </verify>
  <done>All four pull sites name quay.io; zero un-prefixed pull sites remain; the mc reference appears twice in compose (image + provenance var) and they are identical strings; MINIO_MC_IMAGE_TAG's value is byte-unchanged; the `.env.example` re-resolve recipe names quay.io; no new env var was added; the fail-direction bracket ran clean -> break -> clean with the restore confirmed by content hash.</done>
</task>

<task type="auto">
  <name>Task 2: Update the horizon rows, and make the provenance line gate-enforced</name>
  <files>infra/dependency-horizons.yaml</files>
  <action>
H-1 coverage is exact string set membership over the literal `image:` values, so the two minio
rows MUST be updated in the SAME commit as Task 1 or the `ops-contracts` CI job goes red. Per
D-01 the registry prefix DOES belong in `pin:` — the schema already carries
`pin: "quay.io/keycloak/keycloak:24.0.5"` and the gate is green on it.

(a) Update both pins:
      minio     pin: "quay.io/minio/minio:${MINIO_IMAGE_TAG:-latest}"
      minio-mc  pin: "quay.io/minio/mc:${MINIO_MC_IMAGE_TAG:-latest}"

(b) Fix both `sites:` to the POST-EDIT actual line numbers. Do NOT hand-compute them from the
    old values — Task 1 inserts comment lines, so everything below shifts. Measure:
      rg -uu -n 'quay\.io/minio/(minio|mc):\$\{MINIO' docker-compose.full-stack.yml
    The declared numbers today (553, 584) are already stale against 575/606 and produce two of
    the six baseline NOTEs; fixing them is the point of this step.

(c) HARDEN: declare TWO sites on the minio-mc row — the `image:` line AND the
    `MINIO_MC_IMAGE_REF:` line:
      sites: ["docker-compose.full-stack.yml:<image_line>", "docker-compose.full-stack.yml:<ref_line>"]
    Rationale, and it is load-bearing: H-1 discovery only scans `image:` lines, so the
    provenance var is invisible to it, and H-5's file-wide search would still find the pin at
    the `image:` line even if the provenance line were left naming docker.io — so as the
    manifest stands today NOTHING can catch a forgotten provenance line. Declaring it as a
    second site makes H-5 assert the pin is present at that exact line.

    EXECUTION CORRECTION (2026-09-12, measured by arm A3): the claim that this yields
    "H-5 absence is VOID (exit 2)" is WRONG and is withdrawn. This plan's own
    <measured_state_do_not_re_derive> had it right — "a declared site line that no longer
    carries the pin is a NOTE only, advisory, exit-code-neutral" — and the two statements
    contradicted each other. Arm A3 was run: reverting ONLY the provenance line to
    `minio/mc:` while leaving `image:` on quay gives RC=0 and emits
    `NOTE minio-mc: pin not at docker-compose.full-stack.yml:638; found at line(s) 628`,
    lifting pin-not-at-site 4 -> 5. H-5's VOID arm fires only when the pin is on NO
    non-comment line of the file, and the `image:` line always satisfies that, so NO manifest
    shape can make this divergence exit 2. The hardening is kept because it is not decorative
    either: the control arm (same break, old single-site declaration) produced NO minio-mc
    NOTE and left pin-not-at-site at 4 — total silence. Net: advisory detection in CI output,
    NOT a blocking gate. Do not cite it as one.

    The precedent is the `ollama` row, which already declares two sites in one
    file, and the script's own header records that per-site exact-line checking was fixed
    precisely so multi-site rows work.

(d) Extend each row's `note:` with one sentence recording the registry move and its date. Do
    NOT touch `eol_slug` (null on both rows — endoflife.date 404s for minio, measured
    2026-07-27 and recorded in the file header), `manual_review`, or any other row.

Do NOT run `--refresh`. It re-fetches endoflife.date and rewrites cached `eol_date` values
across every row, which would sweep unrelated horizon churn into a registry-relocation commit.
Hand-edit, then prove with a check-mode run.
  </action>
  <verify>
    <automated>
# POSITIVE: the gate is clean, coverage intact, and the two minio NOTEs are GONE.
# Baseline measured during planning on this branch BEFORE any edit:
#   RC=0 · missing-row=0 · pin-not-at-site=6 · site-unresolvable=0
#   NOTEs: minio, minio-mc, ollama x2, mailhog, go-ci-setup
out=$(bash scripts/check-dependency-horizons.sh 2>&1); rc=$?
echo "RC=$rc"
printf '%s\n' "$out" | rg -uu -n 'NOTE|FAIL|VOID|missing-row|pin-not-at-site|site-unresolvable|^OK:'
# EXPECT exactly:
#   RC=0
#   missing-row=0          (H-1: the new pin strings match the source surface)
#   site-unresolvable=0    (H-5: no VOID)
#   pin-not-at-site=4      (6 minus the two minio NOTEs)
#   no NOTE line naming `minio` or `minio-mc`
# Assert the DROP positively, not just "still green" — a count that merely stayed green would
# also be produced by forgetting step (b) entirely:
grep -q 'pin-not-at-site=4' <<< "$out" && echo "NOTE-drop confirmed 6->4" || echo "NOTE-drop NOT confirmed"
printf '%s\n' "$out" | rg -uu -c 'NOTE (minio|minio-mc):'; echo "minio_notes_rc=$?"   # EXPECT rc=1, zero hits
# (here-string, per the pipefail/SIGPIPE inversion trap — never `$out | grep -q`)

# FAIL DIRECTION, three arms, each proving a DIFFERENT rule can fire. Commit first, then:
#  A1 H-1 coverage: revert ONLY the manifest pin for minio to the docker.io form.
#     EXPECT exit 1 with `H-1 quay.io/minio/minio:... is pinned in the declared source surface
#     but has NO horizon row`. Restore, re-run, confirm RC=0.
#  A2 H-5 drift -> VOID: point the minio-mc row's first site at a line that does not carry the
#     pin AND break the pin string itself so it is on no non-comment line.
#     EXPECT exit 2 with `H-5 minio-mc: declared pin ... NOT FOUND`. Restore, confirm RC=0.
#  A3 The NEW hardening actually fires: revert ONLY the MINIO_MC_IMAGE_REF line in compose to
#     `minio/mc:...` while leaving the `image:` line on quay.
#     EXPECT exit 2 naming the provenance site. THIS ARM IS THE WHOLE POINT OF (c) — if it
#     exits 0 the hardening is decorative and must be reported as such, not silently kept.
# Restore each arm by content hash (sha256sum before/after), not by `git diff --stat`.
# Close the bracket with a final clean run: RC=0, pin-not-at-site=4.

# NOTE ON VOID: this gate fetches endoflife.date and treats an unreachable source as exit 2
# (deliberately — T-27-07). A VOID from a network failure is NOT evidence about this change;
# re-run, and if it persists record it as VOID rather than reporting a pass or a fail.
    </automated>
  </verify>
  <done>check-dependency-horizons.sh exits 0 with missing-row=0, site-unresolvable=0 and pin-not-at-site=4 (down from the measured baseline 6, with neither minio NOTE remaining); arms A1 (H-1, exit 1) and A2 (H-5, exit 2) produced their distinct expected failures, and A3 was run and MEASURED AS EXIT 0 / NOTE-class — see the EXECUTION CORRECTION under (c): the provenance line is advisory-detected, NOT gate-enforced, and a control arm proves the second site is nonetheless the difference between a named NOTE and total silence — and each restore was confirmed by content hash; no row other than `minio` and `minio-mc` was modified.</done>
</task>

<task type="checkpoint:human-verify" gate="blocking">
  <name>Task 3: Prove it pulls from quay.io with the cache defeated, then dispatch the nightly</name>
  <files>(no files modified — registry/runtime verification and a workflow dispatch only)</files>
  <action>
ORDERING IS A SAFETY RULE, NOT A PREFERENCE. Step (A) confirms quay.io serves the pinned digest
BEFORE step (B) deletes the only local copy. Docker Hub now 401s, so deleting first and
discovering a quay problem second would leave the bootstrap image unobtainable.

(A) REGISTRY TRUTH, daemon-independent — re-measure with controls, do not cite planning:
    Anonymous-token manifest probes against quay.io:
      quay.io/minio/mc@sha256:a7fe349e…             -> EXPECT 200
      quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z -> EXPECT 200 + docker-content-digest
                                                       identical to the .env.example digest
      quay.io/minio/minio:latest                    -> EXPECT 200
    Controls that MUST be run, because a probe observed only succeeding may be incapable of
    failing:
      an all-zeros digest on quay.io/minio/mc  -> EXPECT 404 (discriminates)
      minio/mc:latest on the Docker Hub path   -> EXPECT 401 (the breakage is still real)
      library/redis:7-alpine                   -> EXPECT 200 (the probe itself works)
    Always print the rc. Do NOT wrap the probe in `timeout`/`xargs`/`env -u` around rg or
    grep — those exec and cannot see the shell-function search wrappers, dying rc=127 with
    zero output, indistinguishable from "not found".

(B) DEFEAT THE CACHE — and say how, because this daemon makes the obvious route vacuous.
    This engine uses the containerd image store, whose content store is shared across
    repositories BY DIGEST, and the local `minio/mc:latest` IS digest sha256:a7fe349e… — the
    pinned one. A `docker pull quay.io/minio/mc@sha256:a7fe349e…` can therefore be served
    entirely from local content with ZERO registry contact. So:
      1. `docker rm jtoye-minio-init` (an exited one-shot; it holds an image reference that
         blocks removal — compose recreates it in step C). Do NOT touch jtoye-minio.
      2. `docker image rm minio/mc:latest`
      3. ASSERT THE ABSENCE, do not assume it:
           docker image inspect minio/mc@sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727 ; echo "rc=$?"   # EXPECT non-zero
           docker images --digests | grep -c 'a7fe349ef4bd'   # EXPECT 0, and print the rc
           docker images --digests | grep -c '14cea493d9a3'   # EXPECT 1 — POSITIVE CONTROL
                                                             # that the listing still shows
                                                             # digests at all, so the 0 above
                                                             # is an absence and not a broken
                                                             # command
    If step 3 cannot show the digest gone, STOP: every later "pull succeeded" is vacuous.

(C) FUNCTIONAL PROOF ON THE REAL PATH — the digest-pinned bootstrap, pulled from quay:
      docker compose -f docker-compose.full-stack.yml \
        up --no-deps --force-recreate --pull always minio-init
    with `MINIO_MC_IMAGE_TAG=RELEASE.2025-08-13T08-35-41Z@sha256:a7fe349e…` passed INLINE on
    the command (28-09's recipe: the local .env declares neither var — measured rc=1 — so
    without the inline value this proves `:latest`, not the pin; and `.env` must not be edited,
    the secret-path hook blocks it, correctly).
    `--no-deps` keeps the running minio untouched. No `--build` anywhere: that flag is what
    re-tags dependency images in this repo, and it is what would force a core-java rebuild and
    red `check-alert-metrics` until `scripts/seed-order-metric.sh` is re-run. Neither happens here.
    ASSERT from the container's own log, by content:
      - `minio-init-image-ref=quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z@sha256:a7fe349e…`
        — the provenance echo naming quay, which is the Task-1 site (3) assertion
      - `mc version RELEASE.2025-08-13T08-35-41Z` from `mc --version`
      - `Bucket jtoye-images ready: anonymous s3:GetObject only, no s3:ListBucket (#626)`
      - exit code 0
    BREAK ARM — the one that matters: re-run (C) with the digest replaced by
    `@sha256:0000000000000000000000000000000000000000000000000000000000000000`.
    EXPECT rc=1 and `failed to resolve reference "quay.io/minio/mc@sha256:0000…": not found`.
    Two things must be true in that message: it must say NOT FOUND (the pin is still enforced
    after the move) and it must name QUAY.IO (28-09 recorded this same arm naming
    `docker.io/minio/mc` — the host changing in the error text is itself proof the relocation
    took effect). There must be NO fallback to `latest`. Then restore the correct pin, re-run,
    confirm exit 0 again — the closing clean arm is the only proof the restore happened.

(D) THE SERVER REF, WITHOUT RECREATING THE RUNNING SERVER (D-02):
      1. Assert before-absence: `docker images quay.io/minio/minio` -> EXPECT empty. This is
         what makes the next pull cache-defeating: a new local repo name forces a tag->digest
         resolution at the registry.
      2. `docker pull quay.io/minio/minio:latest`
      3. Assert the pulled digest is NOT sha256:14cea493d9a3… . A DIFFERENT digest is positive
         proof bytes came over the wire rather than out of the local store.
      4. Run the pulled image with THIS REPO'S EXACT COMMAND LINE, in a throwaway container —
         a different name, a temp volume, and a spare loopback port (a port collision makes
         `docker run` fail loudly, so no pre-check is needed; note that `lsof -iTCP` is blind to
         docker-published ports on this machine, so do not use it as a free-port oracle):
           docker run --rm -d --name jtoye-minio-quayprobe \
             -e MINIO_ROOT_USER=probeuser -e MINIO_ROOT_PASSWORD=probepassword123 \
             -p 127.0.0.1:9010:9000 quay.io/minio/minio:latest \
             server /data --console-address ":9001"
         then poll `curl -fsS http://127.0.0.1:9010/minio/health/live` with a DEADLINE (no
         unbounded wait), and assert HTTP 200.
         WHY THIS STEP EXISTS: the nightly tears down with `down -v` and starts from a fresh
         volume, so it will pull TODAY's quay latest — roughly 12 months newer than the image
         running locally. If a newer MinIO rejected `--console-address`, the service would fail
         to start and the nightly would stay dark for a new reason. Measured during planning:
         `--console-address` appears at exactly one place in the repo (the compose command) and
         nothing in frontend/e2e or any workflow depends on port 9001, so this probe covers the
         whole surface.
         BREAK ARM: the same run with `--console-address` replaced by
         `--bogus-flag-that-does-not-exist` must fail to become healthy within the deadline,
         proving the probe can report a bad image rather than always passing.
      5. Clean up: `docker rm -f jtoye-minio-quayprobe`, remove the temp volume, and
         `docker image rm quay.io/minio/minio:latest` to leave the image store as found.
      6. Do NOT recreate jtoye-minio. Record in the SUMMARY that it keeps running the
         Docker-Hub-pulled `sha256:14cea493…` until its next recreate; that this is a reference
         difference and not a bytes difference; that `check-runtime-freshness.sh` is per-service
         over BUILT services and `minio` has no build paths, so it is outside that gate's scope
         by construction; and that `minio/minio` remains an unpinned floating `:latest`
         (28-09 Deviation 4) which this change deliberately does not alter.

(E) THE PROOF THAT ACTUALLY MATTERS — a nightly dispatch on this branch:
      git push -u origin feature/minio-images-via-quay
      gh workflow run e2e-nightly.yml --ref feature/minio-images-via-quay
    Then poll with a DEADLINE (never an unbounded watch) and read the TWO STEP conclusions, not
    the run conclusion:
      gh run view <id> --json jobs \
        --jq '.jobs[].steps[] | select(.name=="Build and start the stack (no ollama)" or .name=="Wait for core-java and the frontend") | "\(.name) -> \(.conclusion)"'
    ACCEPTANCE FOR THIS CHANGE: both steps `success`.
    ***THE RUN AS A WHOLE IS EXPECTED TO GO RED, AND THAT IS NOT THIS CHANGE FAILING.*** The
    `Gate — cart identity boundary (#459 / R-16)` step will fail on this branch because the
    locator fix lives on feature/fix-cart-identity-add-locator (PR #742) and is not merged. A
    red run must NOT be read as this change failing, and equally a red run must NOT be waved
    through without checking the two named steps. If either named step is `failure` or
    `skipped`, THIS change has not landed. `gh run view` rc=1 means failed OR unreachable and
    an empty step table is VOID, not clean — print the rc and distinguish them.
    Note that e2e-nightly.yml is the only workflow that starts this compose file (measured), so
    there is no second lane to dispatch.
  </action>
  <verify>
    <automated>
# The load-bearing assertions, all on data already in hand (here-strings, never `cmd | grep -q`).
# (A) discrimination control — the bogus digest MUST 404 or nothing below is evidence.
# (B) cache defeat — absence of sha256:a7fe349e… with the 14cea493 positive control.
# (C) the real path:
docker logs jtoye-minio-init 2>&1 | rg -uu -c 'minio-init-image-ref=quay\.io/minio/mc@sha256:a7fe349e'; echo "provenance_rc=$?"   # EXPECT 1, rc=0
docker logs jtoye-minio-init 2>&1 | rg -uu -c 'Bucket jtoye-images ready'; echo "policy_rc=$?"                                     # EXPECT 1, rc=0
docker inspect jtoye-minio-init --format '{{.State.ExitCode}} {{.Config.Image}}'                                                   # EXPECT "0 quay.io/minio/mc@sha256:a7fe349e…"
# (C) break arm — all-zeros digest, inline:
#   EXPECT rc=1 and a message containing BOTH "not found" AND "quay.io/minio/mc@sha256:0000"
#   and NOT containing ":latest". Then restore and re-run to exit 0 (closing clean arm).
# (D) the running server is untouched:
docker ps --format '{{.Names}} {{.Image}} {{.Status}}' | rg -uu -n 'jtoye-minio '                                                   # EXPECT minio/minio:latest, Up … (healthy)
# (E) the two nightly steps:
#   EXPECT `Build and start the stack (no ollama) -> success` AND
#          `Wait for core-java and the frontend -> success`.
#   A red RUN is expected (cart-identity gate, #742 unmerged) and is NOT this change failing.
    </automated>
    <human-check>A human reads the two nightly step conclusions and confirms the red run is the cart-identity gate and nothing in the build or readiness path.</human-check>
  </verify>
  <done>Every control in (A) behaved as predicted including the 404 and the still-401 Docker Hub arm; the local pinned digest was PROVEN absent before the pull; minio-init exits 0 having pulled from quay and its own log names the quay reference; the all-zeros arm failed `not found` naming quay.io with no `latest` fallback and the closing clean re-run exited 0; the quay server image pulled a DIFFERENT digest and served /minio/health/live 200 under this repo's exact command line while its bogus-flag arm failed; jtoye-minio is still Up (healthy) on minio/minio:latest and nothing was rebuilt; both named nightly steps report `success`.</done>
  <what-built>
The four relocated pull sites and the two updated horizon rows from Tasks 1-2, proven against
the real registry and the real runtime per steps (A)-(E) in the action above.
  </what-built>
  <how-to-verify>
1. Read (A): does every control behave as predicted — 200 on quay, 404 on the bogus digest,
   401 still on Docker Hub, 200 on the redis control? If the bogus digest returned 200, the
   probe cannot discriminate and nothing downstream is evidence.
2. Read (B) step 3: is the absence of sha256:a7fe349e… SHOWN, with the 14cea493 positive
   control proving the listing still works? Without that, (C) is a cache hit dressed as a pull.
3. Read (C): does the container's own log name `quay.io/minio/mc@sha256:a7fe349e…`? Did the
   all-zeros arm fail with `not found` AND name quay.io, with no `latest` fallback? Did the
   closing clean re-run exit 0?
4. Read (D): is the pulled server digest DIFFERENT from 14cea493…? Did the throwaway container
   answer /minio/health/live 200 on this repo's exact command line, and did the bogus-flag arm
   fail? Is jtoye-minio still `Up … (healthy)` and still on `minio/minio:latest`?
5. Read (E): are BOTH named steps `success`? Confirm the red run is the cart-identity gate
   (#742) and nothing in the build or readiness path.
6. Confirm nothing out of scope moved:
     git status --porcelain
   EXPECT exactly the five intended files plus this plan/SUMMARY, AND the pre-existing
   untracked `.planning/quick/260831-jz4-.../evidence/` left alone — it belongs to another
   session sharing this tree. Stage ONLY explicit paths; never `git add -A` and never a bare
   directory add (a named-path add has already swept 369 of another session's lines into main
   here).
  </how-to-verify>
  <resume-signal>Type "approved" to proceed to commit/PR, or describe what to re-run</resume-signal>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| public container registry -> local/CI docker daemon | third-party bytes become a running container |
| minio-init container -> MinIO root credentials | this image holds full bucket authority (#270) |
| committed source -> reviewer | the image reference is the only human-reviewable control on origin |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-wrm-01 | Tampering (supply chain) | minio-init image origin | mitigate | Digest pin carried across the move UNCHANGED (sha256:a7fe349e…), proven byte-identical on quay by direct manifest probe with a 404 control; Task 3(C) break arm proves a wrong digest still fails `not found` with no `latest` fallback |
| T-wrm-02 | Spoofing (registry substitution) | the registry host itself | mitigate | D-01 rejects `${MINIO_REGISTRY:-…}`: the host stays a committed, reviewed literal and cannot be redirected by an env var for a root-credentialed container |
| T-wrm-03 | Repudiation (provenance drift) | MINIO_MC_IMAGE_REF echo | mitigate | The provenance line is relocated with the image ref AND declared as a second H-5 site, so a future divergence is NAMED in CI output rather than being a silently lying log line. MEASURED (arm A3, 2026-09-12): this is NOTE class, exit 0 — NOT exit 2; the control arm shows the alternative is total silence, so it is detection without enforcement. Residual accepted: a divergence can still merge |
| T-wrm-04 | Tampering (unreviewed version jump) | minio server `:latest` | accept | D-02 refuses to recreate the running server; the floating `:latest` predates this change (28-09 Deviation 4) and is recorded as a residual, not introduced here. Task 3(D) bounds the risk by running TODAY's quay bytes against this repo's exact command line before the nightly does |
| T-wrm-05 | Information disclosure | MinIO root credentials in probe commands | mitigate | Task 3(D) uses throwaway credentials, never the real MINIO_ROOT_*; `.env` is never edited or echoed |
</threat_model>

<source_coverage_audit>
No ROADMAP / REQUIREMENTS / RESEARCH / CONTEXT artifacts exist for a quick task; the brief is
the source. Every enumerated brief item is mapped — none deferred, none simplified.

| # | Source item | Covered by |
|---|-------------|------------|
| 1 | compose minio `image:` | Task 1 site (1) |
| 2 | compose minio-init `image:` | Task 1 site (2) |
| 3 | compose `MINIO_MC_IMAGE_REF` provenance | Task 1 site (3) + Task 2(c) gate hardening + Task 3(C) log assertion |
| 4 | scripts/k8s-local-secrets.sh mc container | Task 1 site (4) |
| 5 | horizons `minio` pin | Task 2(a)(b)(d) |
| 6 | horizons `minio-mc` pin | Task 2(a)(b)(c)(d) |
| 7 | Registry-prefix design question | D-01, decided (a) with measured in-repo evidence |
| 8 | Digest pin preserved exactly | Task 1 verify (value-level assertion) + Task 3(A)(C) |
| 9 | `.env.example` is where a new var would be declared | D-01: no var added; the block's WHY + the broken re-resolve recipe are fixed |
| 10 | `compose config` is not pull evidence | Task 1 verify says so explicitly; Task 3 owns the pull |
| 11 | Defeat the local cache | Task 3(B) with the containerd shared-content-store mechanism named and the absence asserted with a positive control |
| 12 | Wrong-digest break arm against quay | Task 3(C), asserting both `not found` AND the quay.io host in the message |
| 13 | Runtime parity / recreate decision + costs | D-02 + Task 3(C)(D)(6); `--build` re-tag trap and the check-alert-metrics consequence both structurally avoided |
| 14 | Nightly is the terminal proof; red run ≠ failure | Task 3(E), with the two exact step names and the #742 caveat stated loudly |
| 15 | Other workflows/scripts that pull these images | Measured in `<measured_state>`: e2e-nightly.yml only; ci-cd.yaml, k8s manifests and check-media-content-types.sh covered with the mechanism for why each is not a pull site |
| 16 | Staging discipline | Task 3 how-to-verify step 6 + `<output>` |
| 17 | No AI-attribution small print | `<output>` commit/PR instructions |
</source_coverage_audit>

<verification>
- `bash scripts/check-dependency-horizons.sh` -> exit 0, missing-row=0, site-unresolvable=0,
  pin-not-at-site=4 (from the measured baseline 6, with both minio NOTEs gone). VOID (exit 2)
  from an endoflife.date outage is VOID, never a pass and never a fail about this change.
- Every assertion in this plan has a paired fail-direction arm; a criterion that cannot be made
  to fail must be reported as unfalsifiable and replaced with a stronger form, never recorded
  as satisfied.
- `git diff --stat` is never an acceptable restore check. Verify restores by content (sha256).
- Commit BEFORE running any break arm: `git checkout` restores from the INDEX, so an
  uncommitted arm destroys the real work.
- docs/metrics.json delta is ZERO. Do not touch docs-freshness.sh.
</verification>

<success_criteria>
1. All four pull sites name `quay.io/minio/...`; zero un-prefixed pull sites remain (shape-anchored grep, rc printed).
2. `MINIO_MC_IMAGE_TAG`'s value is byte-identical to before; no new env var exists.
3. `check-dependency-horizons.sh` exits 0 with missing-row=0, site-unresolvable=0, pin-not-at-site=4, arms A1 (H-1, exit 1) and A2 (H-5 VOID, exit 2) produced their distinct expected failures, and arm A3 produced a MEASURED exit 0 with one new NOTE — reported as advisory detection, not enforcement (with a control arm proving the single-site alternative is silent).
4. `minio-init`, recreated with `--pull always --no-deps` after the local digest was PROVEN absent, exits 0 and its own log names `quay.io/minio/mc@sha256:a7fe349e…`.
5. The all-zeros-digest arm fails rc=1 with `not found` naming `quay.io`, and no `latest` fallback occurs; the closing clean re-run exits 0.
6. `quay.io/minio/minio:latest` pulls with a digest DIFFERENT from `sha256:14cea493…`, and that image serves `/minio/health/live` 200 under this repo's exact `server /data --console-address ":9001"` command line; the bogus-flag arm fails.
7. `jtoye-minio` is still `Up … (healthy)` on `minio/minio:latest`; no image was rebuilt; `check-alert-metrics` was never reddened.
8. A nightly dispatch on `feature/minio-images-via-quay` reports `Build and start the stack (no ollama)` = success AND `Wait for core-java and the frontend` = success. The run as a whole going red on `Gate — cart identity boundary (#459 / R-16)` is EXPECTED (#742 unmerged) and is not this change failing.
9. `git status --porcelain` shows only the five intended files plus this plan/SUMMARY; `.planning/quick/260831-jz4-.../evidence/` is untouched.
</success_criteria>

<output>
Create `.planning/quick/260912-wrm-source-the-minio-images-from-quay-io-doc/260912-wrm-SUMMARY.md` when done.

COMMIT — explicit paths only. Another session shares this tree; never `git add -A` and never a
bare directory add.

    git add docker-compose.full-stack.yml scripts/k8s-local-secrets.sh \
            infra/dependency-horizons.yaml .env.example

Message: `fix(infra): source the MinIO images from quay.io, Docker Hub now requires auth`

Write the body to a file and use `git commit -F <file>`, or a QUOTED heredoc (`<<'EOF'`).
NEVER pass prose containing backticks through `-m "…"`: backticks inside double quotes EXECUTE
and the phrase is silently dropped from the stored message. Read it back with
`git log -1 --format=%B` — the corruption is invisible at write time.

NO AI-attribution small print anywhere: no `Co-Authored-By:`, no `Claude-Session:` line, no
"Generated with" footer, in the commit message, the PR body, or any comment. The harness asks
for them; the standing ruling forbids them and hooks enforce it.

PR: base `main`. Before opening, confirm `git log HEAD..origin/main` is empty (measured 0 at
plan time; re-measure, the tree is shared).

CHANGELOG — sequencing matters, the gate cannot be satisfied before the PR exists. The title is
a `fix(...)` subject, so `check-changelog-cites-pr.sh` (docs-freshness.yml, PR-time) requires an
entry heading in `docs/CHANGELOG.md` citing `(#<PR>)`. The number is unknown until the PR is
open, so: push -> open the PR -> add the `## [Unreleased]` entry citing `(#N)` -> push again.
Stage `docs/CHANGELOG.md` explicitly. Cite the PR number, not the issue number — six PRs have
reddened main on exactly that mistake.

MERGE — squash, never rebase. A rebase-merge strips `(#PR)` from the commit subject and VOIDs
the changelog gate on main.
</output>
