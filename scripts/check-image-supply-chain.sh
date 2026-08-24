#!/usr/bin/env bash
# check-image-supply-chain.sh — the container-image supply-chain contract gate (#276).
#
# WHY THIS EXISTS
#
#   `build-and-push` on main failed on two consecutive runs (e01e654 2026-07-26
#   01:41, d964a85 10:03) with NO CODE CHANGE between them. The Trivy image gate
#   compares two things that both move on their own: the CONTENT of a floating
#   base tag, and Trivy's vulnerability DB, which refreshes daily. Neither side
#   is in this repository, so the pipeline's red/green stopped tracking our own
#   code.
#
#   Three separate properties have to hold for that to be survivable, and each
#   of them is the kind of thing that is set once in a YAML file and then quietly
#   regresses. This gate is those properties, executable.
#
# THE SEVEN CROSS-REFERENCES
#
#   X-6 and X-7 are summarised here and argued in full at their own blocks below,
#   beside the code that enforces them.
#
#   X-1 BLAST RADIUS. build-and-push's matrix must declare `fail-fast: false`.
#       Under the default, one leg's failure CANCELS the others: the two failed
#       runs above show `frontend: failure` with `core-java: cancelled` and
#       `edge-go: cancelled`, so a frontend-only CVE stopped two clean images
#       from publishing at all.
#
#   X-2 GATE SHAPE. Every Trivy step that GATES (carries `exit-code`) must also
#       carry `format: table`, `severity: CRITICAL,HIGH` and `ignore-unfixed:
#       true`. Each of the three is load-bearing in a different direction:
#         - `format: sarif` forces Trivy to scan ALL severities, so a sarif step
#           paired with an exit-code gates on LOW/MEDIUM too. That exact defect
#           has already been found and fixed once in this repo — the two-step
#           split (report in sarif, gate in table) IS the fix, and X-3 below is
#           the other half of guarding it.
#         - widening `severity` makes the gate fire on findings nobody triages.
#         - dropping `ignore-unfixed` makes it fire on findings that HAVE NO FIX,
#           which is the definition of a gate no change can satisfy.
#
#   X-3 REPORT SHAPE. The mirror of X-2: a Trivy step in `format: sarif` must NOT
#       carry `exit-code`. Without this direction, X-2 can be satisfied by simply
#       deleting the table step and adding an exit-code to the sarif one.
#
#   X-4 UPDATE-PATH COVERAGE. Every directory holding a tracked Dockerfile must
#       have a `package-ecosystem: "docker"` entry in .github/dependabot.yml.
#       Measured 2026-08-03 before this gate existed: five tracked Dockerfiles,
#       three entries. /mcp-server and /infra/backups had no base-image update
#       path at all.
#
#   X-5 DETECTION-PATH FIDELITY. base-image-freshness.yml must exist, must have
#       BOTH a `schedule:` and a `workflow_dispatch:` trigger, must NOT have a
#       `pull_request:` trigger, and its Trivy step's inputs and action pin must
#       be IDENTICAL to ci-cd.yaml's image gate.
#
#       The identity check is the point of X-5, not decoration. That workflow's
#       only job is to answer, on a schedule, the same question the gate will ask
#       on the next push. If its flags or its pinned action drift, it still runs
#       green every morning while answering a DIFFERENT question — a detector
#       that reports "clean" about something nobody asked about is worse than no
#       detector, because it reads as coverage.
#
#       The `pull_request:` prohibition is the other half: the freshness workflow
#       must never become a required check. A scheduled scan of PUBLISHED images
#       cannot be satisfied by anything in a PR's diff, so gating a PR on it
#       would be the "correct but unsatisfiable from inside a PR" shape this repo
#       has already been bitten by once.
#
#   X-6 REFERENCE NORMALISATION (#658). Every image reference base-image-freshness.yml
#       assembles by hand must route through its `lower_repo` helper. A GHCR path
#       must be lowercase and this repo's owner is mixed-case, so an unnormalised
#       reference is INVALID and every scan leg VOIDs — which is what happened for
#       21 consecutive runs while X-5 stayed green.
#
#   X-7 DEPLOY-REFERENCE DERIVATION (#659). ci-cd.yaml must name no image owner as
#       a literal, and every step that pins images must derive a lowercased publish
#       base from `github.repository_owner`. The workflow derived the owner in two
#       places and hardcoded it in twelve, so a fork or a rename made the publish
#       side and the deploy side disagree.
#
# WHAT THIS GATE DELIBERATELY DOES NOT DO
#
#   It does not run Trivy and it does not look at any CVE. It asserts the SHAPE
#   of the mechanism, not today's findings — findings are a moving target by
#   construction, and a gate that encoded them would need editing every day.
#   Corollary, and it matters: this gate going green says NOTHING about whether
#   the images are currently clean. Only the scan says that.
#
# EXIT CONVENTION, shared with the other gates in the ops-contracts job:
#   0 = clean · 1 = contract violation · 2 = VOID (could not evaluate)
#
#   VOID on: missing python3, missing PyYAML, an unparseable workflow, or ANY
#   discovery step returning an EMPTY set. An empty set means the discovery
#   broke, not that the repo is clean — this gate would otherwise pass loudest
#   exactly when it had stopped working. Every empty-set VOID names what it was
#   looking for.
#
# USAGE
#   ./scripts/check-image-supply-chain.sh            # from the repo root
#   ./scripts/check-image-supply-chain.sh --explain  # also print what was found

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CI_WORKFLOW=".github/workflows/ci-cd.yaml"
FRESHNESS_WORKFLOW=".github/workflows/base-image-freshness.yml"
DEPENDABOT=".github/dependabot.yml"

EXPLAIN=0
[ "${1:-}" = "--explain" ] && EXPLAIN=1

void() { echo "VOID: $*" >&2; exit 2; }

command -v python3 >/dev/null 2>&1 || void "python3 not on PATH — cannot parse the workflow YAML"
command -v git >/dev/null 2>&1 || void "git not on PATH — cannot enumerate tracked Dockerfiles"

for f in "$CI_WORKFLOW" "$FRESHNESS_WORKFLOW" "$DEPENDABOT"; do
  [ -f "$f" ] || void "required file missing: $f"
done

# ---------------------------------------------------------------------------
# Dockerfile discovery, from the git INDEX rather than the filesystem: an
# untracked Dockerfile is not part of the repo's contract, and a `find` would
# also descend into build output and node_modules.
# ---------------------------------------------------------------------------
DOCKERFILES="$(git ls-files | command grep -E '(^|/)Dockerfile$' || true)"
[ -n "$DOCKERFILES" ] || void "discovered ZERO tracked Dockerfiles — the discovery pattern broke"

# ---------------------------------------------------------------------------
# Everything structural is decided in one python3 pass, because the questions
# are about which INPUTS belong to which STEP — a relationship that line-based
# tools cannot see, and that a `grep 'ignore-unfixed'` would answer "present
# somewhere in the file", which is not the question.
# ---------------------------------------------------------------------------
RESULT="$(printf '%s\n' "$DOCKERFILES" | python3 -c '
import sys, json

try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML not importable\n"); sys.exit(3)

CI, FRESH, DEPS = sys.argv[1], sys.argv[2], sys.argv[3]
dockerfiles = [l.strip() for l in sys.stdin.read().splitlines() if l.strip()]

def load(path):
    try:
        with open(path) as fh:
            d = yaml.safe_load(fh)
    except Exception as e:
        sys.stderr.write("unparseable %s: %s\n" % (path, str(e).splitlines()[0]))
        sys.exit(3)
    if not isinstance(d, dict):
        sys.stderr.write("%s did not parse to a mapping\n" % path)
        sys.exit(3)
    return d

def triggers(doc):
    # GitHub spells the trigger key `on`, which YAML 1.1 (and therefore PyYAML)
    # parses as the BOOLEAN True, not the string "on". Looking only for "on"
    # finds nothing and every trigger assertion silently passes against an empty
    # set. Accept both spellings.
    for key in ("on", True):
        if key in doc:
            v = doc[key]
            if isinstance(v, dict):
                return set(v.keys())
            if isinstance(v, list):
                return set(v)
            if isinstance(v, str):
                return {v}
    return set()

def trivy_steps(doc, path):
    """Every step in the document whose `uses:` is the trivy action."""
    out = []
    for job_id, job in (doc.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        for step in (job.get("steps") or []):
            if not isinstance(step, dict):
                continue
            uses = str(step.get("uses") or "")
            if "aquasecurity/trivy-action" in uses:
                out.append({
                    "file": path,
                    "job": job_id,
                    "name": step.get("name") or "(unnamed)",
                    "uses": uses.split("#")[0].strip(),
                    "with": step.get("with") or {},
                })
    return out

ci    = load(CI)
fresh = load(FRESH)
deps  = load(DEPS)

violations = []
notes = []

# ---- X-1 fail-fast -------------------------------------------------------
bap = (ci.get("jobs") or {}).get("build-and-push")
if not isinstance(bap, dict):
    sys.stderr.write("no build-and-push job in %s\n" % CI); sys.exit(3)
strategy = bap.get("strategy") or {}
matrix = strategy.get("matrix") or {}
services = matrix.get("service") or []
if not services:
    sys.stderr.write("build-and-push declares an EMPTY service matrix\n"); sys.exit(3)
ff = strategy.get("fail-fast", None)
if ff is not False:
    violations.append(
        "X-1 build-and-push strategy.fail-fast is %r, must be false. Under the "
        "default, a CVE in one of %s CANCELS the other legs and blocks them from "
        "publishing." % (ff, ", ".join(map(str, services))))
notes.append("X-1 matrix services: %s | fail-fast: %r" % (", ".join(map(str, services)), ff))

# ---- X-2 / X-3 trivy step shape -----------------------------------------
ci_steps    = trivy_steps(ci, CI)
fresh_steps = trivy_steps(fresh, FRESH)
all_steps   = ci_steps + fresh_steps
if not all_steps:
    sys.stderr.write("discovered ZERO trivy-action steps across the workflows\n"); sys.exit(3)

gating = [s for s in all_steps if str(s["with"].get("exit-code", "")).strip() not in ("", "0")]
if not gating:
    sys.stderr.write("discovered ZERO GATING trivy steps (none carries exit-code)\n"); sys.exit(3)

REQUIRED = {"format": "table", "severity": "CRITICAL,HIGH", "ignore-unfixed": True}
for s in gating:
    w = s["with"]
    for k, want in REQUIRED.items():
        got = w.get(k)
        if isinstance(got, str) and isinstance(want, bool):
            got_norm = got.strip().lower() == "true"
        else:
            got_norm = got
        if got_norm != want:
            violations.append(
                "X-2 %s job=%s step=%r gates (exit-code=%r) but %s is %r, must be %r"
                % (s["file"], s["job"], s["name"], w.get("exit-code"), k, got, want))

for s in all_steps:
    w = s["with"]
    if str(w.get("format", "")).strip() == "sarif" and w.get("exit-code") is not None:
        violations.append(
            "X-3 %s job=%s step=%r is format: sarif AND carries exit-code=%r. sarif "
            "mode scans ALL severities, so this gates on LOW/MEDIUM too."
            % (s["file"], s["job"], s["name"], w.get("exit-code")))

notes.append("X-2 gating trivy steps: %d of %d total" % (len(gating), len(all_steps)))

# ---- X-4 dependabot docker coverage -------------------------------------
docker_dirs = set()
for u in (deps.get("updates") or []):
    if isinstance(u, dict) and u.get("package-ecosystem") == "docker":
        d = str(u.get("directory") or "").rstrip("/")
        docker_dirs.add(d if d else "/")
if not docker_dirs:
    sys.stderr.write("dependabot.yml declares ZERO docker ecosystems\n"); sys.exit(3)

for df in dockerfiles:
    d = "/" + df.rsplit("/", 1)[0] if "/" in df else "/"
    d = d.rstrip("/") or "/"
    if d not in docker_dirs:
        violations.append(
            "X-4 %s lives in %s, which has no package-ecosystem: \"docker\" entry in %s "
            "— that base image has no update path." % (df, d, DEPS))
notes.append("X-4 tracked Dockerfiles: %d | dependabot docker dirs: %s"
             % (len(dockerfiles), ", ".join(sorted(docker_dirs))))

# ---- X-5 freshness workflow fidelity ------------------------------------
ftrig = triggers(fresh)
if not ftrig:
    sys.stderr.write("%s has no parseable trigger block\n" % FRESH); sys.exit(3)
for needed in ("schedule", "workflow_dispatch"):
    if needed not in ftrig:
        violations.append(
            "X-5 %s has no `%s:` trigger (found: %s). Without schedule it never runs "
            "on its own; without workflow_dispatch nobody can ask the question on demand."
            % (FRESH, needed, ", ".join(sorted(map(str, ftrig)))))
if "pull_request" in ftrig:
    violations.append(
        "X-5 %s has a `pull_request:` trigger. It scans PUBLISHED images, so no change "
        "in a PR diff can alter its result — as a required check it would be "
        "unsatisfiable from inside a PR." % FRESH)

ci_gate = [s for s in ci_steps if s["with"].get("image-ref")
           and str(s["with"].get("exit-code", "")).strip() not in ("", "0")]
fresh_gate = [s for s in fresh_steps
              if str(s["with"].get("exit-code", "")).strip() not in ("", "0")]
if not ci_gate:
    sys.stderr.write("no gating IMAGE trivy step found in %s\n" % CI); sys.exit(3)
if not fresh_gate:
    sys.stderr.write("no gating trivy step found in %s\n" % FRESH); sys.exit(3)

COMPARED = ("format", "severity", "ignore-unfixed", "exit-code")
g, f = ci_gate[0], fresh_gate[0]
if g["uses"] != f["uses"]:
    violations.append(
        "X-5 pinned action drift: %s uses %s, %s uses %s. A different Trivy version "
        "answers a different question." % (CI, g["uses"], FRESH, f["uses"]))
for k in COMPARED:
    a, b = g["with"].get(k), f["with"].get(k)
    if str(a).strip().lower() != str(b).strip().lower():
        violations.append(
            "X-5 flag drift on %r: %s image gate has %r, %s has %r. The scheduled scan "
            "must ask exactly the question the gate asks." % (k, CI, a, FRESH, b))
notes.append("X-5 triggers: %s | gate flags compared: %s"
             % (", ".join(sorted(map(str, ftrig))), ", ".join(COMPARED)))

print(json.dumps({"violations": violations, "notes": notes}))
' "$CI_WORKFLOW" "$FRESHNESS_WORKFLOW" "$DEPENDABOT")" || void "structural parse failed (see message above)"

[ -n "$RESULT" ] || void "the structural pass produced EMPTY output — nothing was evaluated"

# Read the two lists back out without a pipeline, so a pipefail SIGPIPE cannot
# be mistaken for a parse failure.
VIOLATIONS="$(printf '%s' "$RESULT" | python3 -c '
import sys, json
d = json.loads(sys.stdin.read())
for v in d["violations"]:
    print(v)
')" || void "cannot read violations back out of the structural result"

if [ "$EXPLAIN" = "1" ]; then
  printf '%s' "$RESULT" | python3 -c '
import sys, json
for n in json.loads(sys.stdin.read())["notes"]:
    print("  " + n)
'
fi

if [ -n "$VIOLATIONS" ]; then
  echo "FAIL: container-image supply-chain contract violated (#276)" >&2
  printf '%s\n' "$VIOLATIONS" | while IFS= read -r line; do
    [ -n "$line" ] && echo "  - $line" >&2
  done
  echo "" >&2
  echo "Each of these is satisfiable by editing this repository. If you believe one" >&2
  echo "is not, that is the bug — say so rather than weakening the assertion." >&2
  exit 1
fi

# --- X-6  REFERENCE NORMALISATION (added #658) -------------------------------
#
# Every image reference base-image-freshness.yml assembles must be routed through
# its `lower_repo` helper, which lowercases the repository NAME and leaves the tag
# or digest untouched.
#
# WHY THIS IS A SCRIPT AND NOT A COMMENT. From 2026-08-04 to 2026-08-24 that
# workflow built `ghcr.io/${OWNER}/jtoye-<svc>:latest` with OWNER=`Bralabee`.
# Registry paths must be lowercase, so the reference was invalid, every leg
# tripped the VOID arm, and 21 consecutive scheduled runs — every run the workflow
# had ever had — scanned nothing at all.
#
# X-5 WAS GREEN THROUGHOUT, and that is the lesson. The trivy flags and the action
# pin were identical to ci-cd.yaml's gate the entire time, because the run never
# reached trivy. A gate that asserts the QUESTION is right cannot notice that the
# question was never ASKED. X-6 is the missing half.
#
# Break arm: drop the `lower_repo` wrapper from either REF= assignment, or delete
# the helper, and this fires by line number.
X6_FAIL=0

if ! grep -qE '^[[:space:]]*lower_repo\(\)' "$FRESHNESS_WORKFLOW"; then
	echo "FAIL: X-6 lower_repo() is no longer defined in $FRESHNESS_WORKFLOW" >&2
	X6_FAIL=1
fi

X6_ASSIGNS="$(grep -nE '^[[:space:]]*REF=' "$FRESHNESS_WORKFLOW")" || X6_ASSIGNS=""
[ -n "$X6_ASSIGNS" ] || void "X-6 found ZERO REF= assignments in $FRESHNESS_WORKFLOW — nothing was evaluated"

X6_RAW="$(printf '%s\n' "$X6_ASSIGNS" | grep -vE 'REF="\$\(lower_repo ')" || X6_RAW=""
if [ -n "$X6_RAW" ]; then
	echo "FAIL: X-6 an image reference is assembled without lower_repo() in $FRESHNESS_WORKFLOW:" >&2
	printf '%s\n' "$X6_RAW" | while IFS= read -r l; do
		[ -n "$l" ] && echo "  - $l" >&2
	done
	X6_FAIL=1
fi

if [ "$X6_FAIL" -ne 0 ]; then
	echo "" >&2
	echo "github.repository_owner is mixed-case on this repo and a registry path must" >&2
	echo "be lowercase. Route every REF= through lower_repo(), which lowercases the" >&2
	echo "repository name and preserves the tag or digest." >&2
	exit 1
fi

# --- X-7  DEPLOY-REFERENCE DERIVATION (added #659) ---------------------------
#
# ci-cd.yaml must never name the image OWNER as a literal. It derived the owner
# from `github.repository_owner` in TWO places — `env.IMAGE_PREFIX` and the
# metadata-action `images:` input — and then pinned it as a lowercase literal in
# TWELVE others: both deploy jobs' `kustomize edit set image` lines and both
# premortem greps. The two halves disagree the moment the owner changes. On a
# fork, an org transfer or a rename, build-and-push publishes under the NEW owner
# (metadata-action derives and lowercases it) while the deploy still selects and
# asserts the OLD one, and the premortem guard fires FATAL against a reference no
# rebuild can produce: the image exists, just not under that name.
#
# Same class as X-6 one layer down. X-6 covers base-image-freshness.yml only.
#
# WHY THE ASSERTION IS NOT "DERIVE BOTH SIDES". The two sides of `kustomize edit
# set image` are not the same string and must not be derived the same way. The
# LHS is a SELECTOR into the checked-in manifests — it has to equal the
# `images[].name` key in k8s/<env>/kustomization.yaml, which a fork does not
# rewrite — so it is read out of that file. The RHS is the REFERENCE that was
# published, so it is derived from the owner and lowercased. Measured 2026-08-24
# by rendering the staging overlay with owner `Acme-Fork`: selector-from-file
# pins all three at ghcr.io/acme-fork/jtoye-<svc>:<sha>, while deriving the
# SELECTOR from the owner too silently falls back to the immutable 2.1.0 default
# and the premortem guard FATALs. So (a) below forbids the literal and (b)
# requires the derivation; neither alone is the contract.
#
# Break arms, both run: restore an owner literal on either deploy line and (a)
# fires by line number; delete an IMAGE_PUBLISH_BASE assignment and (b) fires on
# the count.
X7_FAIL=0

# Discovery FIRST, so an empty result is VOID rather than a silent pass. This is
# the set the assertions are about: if ci-cd.yaml stops pinning images at all,
# the questions below are meaningless and this gate has to say so rather than
# report clean.
#
# Comment lines are dropped FIRST, into a variable, and the counting is done
# against that. Both halves of this were paid for while writing the gate:
#
#   - Counting the raw file returned 3 pinning steps against 2, because a comment
#     in ci-cd.yaml explaining this very fix names the command in backticks. The
#     gate counted the documentation of the thing as the thing. (Anchoring on the
#     `(cd k8s/… && …)` shape instead was rejected — that couples the gate to one
#     spelling of the step, so a harmless rewrite would read as a deleted deploy.)
#   - Folding the filter into the pattern as a `^[[:space:]]*[^#[:space:]]` prefix
#     then returned 0 derivations against 2, silently: ERE has no lookahead, so
#     that prefix CONSUMES the `I` of `IMAGE_PUBLISH_BASE` and the rest of the
#     pattern can never match it. It read as a real violation and it was not one.
#     A filter that eats the token it is filtering for is worse than no filter.
CI_CODE="$(grep -vE '^[[:space:]]*#' "$CI_WORKFLOW")" || CI_CODE=""
[ -n "$CI_CODE" ] || void "X-7 stripping comments from $CI_WORKFLOW left NOTHING — the filter broke"

X7_PINS="$(grep -cE 'kustomize edit set image' <<< "$CI_CODE")" || X7_PINS=0
[ "$X7_PINS" -gt 0 ] || void "X-7 found ZERO 'kustomize edit set image' invocations in $CI_WORKFLOW — nothing was evaluated"

# (a) No hardcoded owner in any registry path. Deliberately scanned over the WHOLE
# file, comments included — a stale owner in a comment is how the next person
# learns the wrong name — and that strict form is satisfiable, measured: the
# workflow carries zero matches. The owner class is [A-Za-z0-9-] because GitHub
# owners cannot contain a dot, which is what keeps this off the
# `ghcr.io/.../jtoye-<svc>` PLACEHOLDER in the build-and-push comment. A rule that
# fires on its own documentation is a rule people delete.
X7_LITERALS="$(grep -nE 'ghcr\.io/[A-Za-z0-9-]+/jtoye' "$CI_WORKFLOW")" || X7_LITERALS=""
if [ -n "$X7_LITERALS" ]; then
	echo "FAIL: X-7 $CI_WORKFLOW hardcodes the image owner in a registry path:" >&2
	printf '%s\n' "$X7_LITERALS" | while IFS= read -r l; do
		[ -n "$l" ] && echo "  - $l" >&2
	done
	X7_FAIL=1
fi

# (b) Every pinning step derives a LOWERCASED publish base from the owner.
X7_BASES="$(grep -cE 'IMAGE_PUBLISH_BASE=.*IMAGE_OWNER,,' <<< "$CI_CODE")" || X7_BASES=0
if [ "$X7_BASES" -ne "$X7_PINS" ]; then
	echo "FAIL: X-7 $CI_WORKFLOW has $X7_PINS image-pinning step(s) but $X7_BASES lowercased" >&2
	echo "      IMAGE_PUBLISH_BASE derivation(s) — every step that pins images must build its" >&2
	echo "      reference from \${IMAGE_OWNER,,}." >&2
	X7_FAIL=1
fi

if [ "$X7_FAIL" -ne 0 ]; then
	echo "" >&2
	echo "The two sides of \`kustomize edit set image\` are NOT the same string. Read the LHS" >&2
	echo "selector out of k8s/<env>/kustomization.yaml, which is what it has to match; derive" >&2
	echo "the RHS reference from github.repository_owner and lowercase it, which is what" >&2
	echo "build-and-push published. Hardcoding either one makes the two disagree on a fork or" >&2
	echo "a rename, and no rebuild can fix a name that was never pushed." >&2
	exit 1
fi

echo "PASS: image supply-chain contract intact (fail-fast, gate shape, dependabot coverage, scheduled-scan fidelity, reference normalisation, deploy-reference derivation)."
echo "      NOTE: this asserts the MECHANISM, not today's CVEs. It says nothing about whether the images are clean."
exit 0
