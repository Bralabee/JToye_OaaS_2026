#!/usr/bin/env bash
# check-runtime-freshness.sh — assert the RUNNING compose runtime was actually
# built from the source tree you are looking at.
#
# WHY THIS EXISTS (the failure it was written for, 2026-07-26)
#   Phase 26 restored the canonical compose runtime with
#     docker compose start core-java frontend edge-go mcp-server
#   `start` starts EXISTING containers. It does not build. The phase had changed
#   core-java/src/main/resources/application.yml, so the core-java that came back
#   up was serving a jar from before that change. Every gate in the project was
#   green: unit tests, integrationTest, code review, secure-phase, docs-freshness,
#   and a live runtime check that asserted HTTP 200 plus a page title. An HTTP 200
#   and a page title are BYTE-IDENTICAL from a stale image and a current one, so
#   none of them could have caught it. A human caught it by eye.
#
#   This gate closes that hole by comparing the thing no HTTP response can hide:
#   WHEN the running image was produced, against WHEN its build inputs last
#   changed in git.
#
# THE TWO STALENESS MODES, BOTH CHECKED
#   A. IMAGE NOT REBUILT — the image tag predates the newest commit touching the
#      paths that image is built FROM. `docker compose start`, or editing source
#      and forgetting `--build`, produces this.
#   B. CONTAINER NOT RECREATED — the image WAS rebuilt, but the running container
#      still holds the previous image ID because only `start`/`restart` was run.
#      The tag looks current; the process is not. Mode B is invisible to mode A's
#      timestamp check, which is why both are here.
#
# WHY `.Metadata.LastTagTime` AND NOT `.Created` (measured on this host)
#   Docker PRESERVES the original `.Created` when a rebuild is a full cache hit,
#   and `docker pull` preserves the registry manifest's `.Created` too. Measured
#   2026-07-26 with all four app images correctly rebuilt at ~01:44 UTC:
#     core-java  .Created=2026-07-25T21:09:50+01:00  .LastTagTime=2026-07-26 01:44:00 +0000
#     edge-go    .Created=2026-07-14T13:26:08+01:00  .LastTagTime=2026-07-26 01:43:59 +0000
#   edge-go's `.Created` is TWELVE DAYS older than the rebuild that produced the
#   running image. A `.Created` gate has a twelve-day false-positive window on a
#   service with no source changes — and a gate that cries wolf gets ignored,
#   which is worse than no gate. `.LastTagTime` is when THIS daemon last bound
#   this tag, which is exactly "when did this runtime's image get produced here".
#
# WHY THE COMPARISON IS PER-SERVICE AND NOT "newest commit anywhere"
#   An old image is NOT automatically stale. edge-go's image legitimately predates
#   this phase because zero Go files changed in it. Comparing every image against
#   the repo's newest commit would flag edge-go forever. So each service is
#   compared only against the newest commit touching ITS OWN build inputs, which
#   are derived from its compose `build.context` plus the host-side COPY/ADD
#   operands of its Dockerfile. This is the load-bearing correctness requirement.
#
# WHY `git log --full-history` AND NOT PLAIN `git log`
#   Plain `git log -- <paths>` applies history simplification: when a merge is
#   TREESAME to one parent it follows that parent and never reports the merge. If
#   you merge origin/main and take main's version of a path wholesale, the newest
#   reported commit is main's ORIGINAL commit date — which can be days before the
#   change actually arrived on this branch. An image built in that gap then
#   falsely passes. `--full-history` reports the merge itself, so the bar is when
#   the content arrived HERE. Measured difference on this branch for core-java:
#   simplified says 486f0b4 @ 2026-07-25T17:57:34+01:00; --full-history says
#   e638956 @ 2026-07-26T02:42:54+01:00 — a 7h45m understatement of the bar.
#   The error direction matters: --full-history can only over-report (you rebuild
#   when you did not strictly need to), simplification can under-report (you ship
#   a stale runtime believing a gate cleared it).
#
# NO HARDCODED SERVICE LIST OR PATHS (GLOBAL_RULE_6 / ARCHITECTURE_RULE_8)
#   The service set, each service's build context and each service's Dockerfile
#   all come from `docker compose config --format json`. The build INPUTS come
#   from parsing that Dockerfile. Add a fifth built service to the compose file
#   and this gate covers it with no edit here.
#
# .dockerignore IS APPLIED, BUT ONLY WHERE IT IS UNAMBIGUOUS (changed 2026-08-07)
#   A file excluded from the build context cannot be copied into the image, so a
#   change to it cannot make the runtime stale. Counting it as a build input makes
#   this gate demand a rebuild that provably cannot change the artifact.
#
#   This was previously refused outright, on the grounds that translating ignore
#   patterns into git pathspec exclusions "risks excluding MORE than intended, and
#   an over-broad exclusion is a FALSE NEGATIVE — the gate reporting fresh while
#   the runtime is stale". That reasoning still holds and is now the design
#   constraint rather than the conclusion: dockerignore_excludes() translates only
#   what it can translate exactly, and everything else stays in the input set.
#   A '!' re-include voids the whole file; a glob, a '..' or the Dockerfile itself
#   is skipped and PRINTED. Every refusal falls back to the old over-reporting
#   behaviour, so the failure direction is unchanged.
#
#   What this bought, measured on this repo: a commit touching only
#   frontend/e2e/kitchen-flow.spec.ts flagged the frontend runtime as DRIFT and
#   prescribed a rebuild, though `e2e/` is now .dockerignore'd and the runner stage
#   copies only .next/standalone, .next/static and public. The residual the old
#   note described (frontend/.gitignore, mcp-server/.gitignore — the only two
#   git-TRACKED files inside the four contexts that were .dockerignore'd) is also
#   gone, since both are exact literal patterns.
#
# FAIL CLOSED — "found nothing" IS NEVER "clean"
#   Missing docker, missing git, missing compose file, zero built services
#   discovered, zero running containers among them, an image that cannot be
#   inspected, an unparseable timestamp, an unsupported Dockerfile COPY form, or
#   an empty git result all exit 2 (VOID), never 0.
#
#   PER SERVICE, NOT JUST IN AGGREGATE. If ANY built service cannot be verified —
#   no container, or a container not in state 'running' — the whole run is VOID
#   (exit 2). Until 2026-07-27 this fired only when EVERY service was
#   unverifiable, so stopping one of four printed `PASS: 3 ... (1 unverified)`
#   and exited 0. A green exit from this gate now means every built service was
#   checked, which is what its name has always implied.
#
# Requires: bash, git, docker (with the compose plugin), jq, awk, date (GNU).
# Reads only. Never stops, starts, builds, tags or removes anything.
#
# Exit codes: 0 = every running built service is fresh
#             1 = drift — a runtime does not match the source tree
#             2 = parse or tooling failure (the assertion is VOID, not passing)
#
# Usage:
#   scripts/check-runtime-freshness.sh
#   scripts/check-runtime-freshness.sh --compose-file docker-compose.full-stack.yml

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# The canonical local dev + E2E runtime (CLAUDE.md "Runtime & deploy topology").
# Overridable so the gate can be falsified against a fixture without a stack.
COMPOSE_FILE="${RUNTIME_FRESHNESS_COMPOSE_FILE:-docker-compose.full-stack.yml}"

fail() { echo "FAIL: $*" >&2; exit 1; }
parse_fail() { echo "PARSE ERROR: $*" >&2; exit 2; }

usage() {
    sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        --compose-file)
            [ $# -ge 2 ] || parse_fail "--compose-file needs a value"
            COMPOSE_FILE="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) parse_fail "unknown argument: $1 (try --help)" ;;
    esac
done

# ---------------------------------------------------------------------------
# Tooling preconditions — every one of these is exit 2, not a silent pass
# ---------------------------------------------------------------------------
require_tool() { # <binary> <why>
    command -v "$1" >/dev/null 2>&1 || parse_fail "$1 not found on PATH — $2"
}
require_tool git    "the freshness bar is a git commit time"
require_tool docker "the running runtime can only be read from the docker daemon"
require_tool jq     "compose config/ps are consumed as JSON"
require_tool awk    "Dockerfile COPY operands and the commit maximum are parsed with awk"
require_tool date   "docker timestamps are normalised to epoch seconds with date -d"

docker compose version >/dev/null 2>&1 \
    || parse_fail "the docker compose plugin is unavailable (docker compose version failed)"

git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1 \
    || parse_fail "$REPO_ROOT is not a git work tree"

case "$COMPOSE_FILE" in
    /*) COMPOSE_PATH="$COMPOSE_FILE" ;;
    *)  COMPOSE_PATH="$REPO_ROOT/$COMPOSE_FILE" ;;
esac
[ -f "$COMPOSE_PATH" ] || parse_fail "compose file not found: $COMPOSE_PATH"

# ---------------------------------------------------------------------------
# Timestamp normalisation
#
# The two docker fields print in DIFFERENT formats and, on this host, appear an
# hour apart for the same instant because one is rendered in the local zone and
# the other in UTC:
#   .Created     2026-07-26T02:44:19.203355985+01:00      (RFC3339, local offset)
#   .LastTagTime 2026-07-26 01:44:20.43262562 +0000 UTC   (Go time.String())
# Comparing these as strings, or deriving an epoch without dropping Go's trailing
# " UTC" (which date(1) rejects outright), is silently off by the UTC offset. Both
# sides go through here so every comparison happens in epoch seconds.
# ---------------------------------------------------------------------------
to_epoch() { # <raw-timestamp> <description>
    local raw="$1" desc="$2" cleaned epoch
    cleaned="${raw% UTC}"
    [ -n "$cleaned" ] || parse_fail "empty timestamp for $desc"
    epoch="$(date -d "$cleaned" +%s 2>/dev/null)" \
        || parse_fail "could not parse $desc timestamp: '$raw'"
    [[ "$epoch" =~ ^-?[0-9]+$ ]] \
        || parse_fail "non-numeric epoch for $desc from '$raw' (got '$epoch')"
    printf '%s\n' "$epoch"
}

human() { # <epoch> -> stable UTC rendering, so two zones never read as two times
    date -u -d "@$1" '+%Y-%m-%d %H:%M:%S UTC'
}

# ---------------------------------------------------------------------------
# Build inputs of a Dockerfile: the host-side operands of its COPY/ADD lines.
#
# `COPY --from=<stage>` is EXCLUDED — it copies from an earlier build stage, not
# from the host, so it is not a source-tree input. The final operand of each
# instruction is the in-image destination and is dropped.
#
# Forms this parser cannot resolve (line continuations, the JSON exec form, and
# build-arg interpolation in a path) are reported and made FATAL by the caller
# rather than skipped: a skipped COPY is an invisible hole in the input set, and
# an invisible hole is how a stale image passes.
# ---------------------------------------------------------------------------
# shellcheck disable=SC2016  # single quotes are REQUIRED: $0/$1/args are awk's,
# and letting the shell expand them would silently empty the parser.
COPY_AWK='
{
  line = $0
  sub(/\r$/, "", line)
  if (line ~ /^[[:space:]]*#/) next
  if (line !~ /^[[:space:]]*(COPY|ADD)[[:space:]]/) next
  if (line ~ /\\[[:space:]]*$/)                { print "line-continuation: " line > "/dev/stderr"; bad=1; next }
  if (line ~ /^[[:space:]]*(COPY|ADD)[[:space:]]*\[/) { print "json-exec-form: " line > "/dev/stderr"; bad=1; next }
  n = split(line, tok, /[[:space:]]+/)
  start = (tok[1] == "" ? 2 : 1)
  fromStage = 0; argc = 0
  delete args
  for (i = start + 1; i <= n; i++) {
    t = tok[i]
    if (t == "") continue
    if (t ~ /^--/) { if (t ~ /^--from=/) fromStage = 1; continue }
    args[++argc] = t
  }
  if (fromStage) next
  if (argc < 2) { print "too-few-operands: " line > "/dev/stderr"; bad=1; next }
  for (i = 1; i < argc; i++) {
    if (args[i] ~ /\$/) { print "build-arg-in-path: " line > "/dev/stderr"; bad=1; continue }
    print args[i]
  }
}
END { if (bad) exit 3 }
'

# ---------------------------------------------------------------------------
# .dockerignore -> git pathspec exclusions, CONSERVATIVELY.
#
# A file excluded from the build context cannot be copied by any COPY, so it
# cannot change the image. Counting it as a build input makes the gate report
# DRIFT over a change that provably cannot reach the runtime — measured on this
# repo 2026-08-07, when a commit touching ONLY frontend/e2e/kitchen-flow.spec.ts
# (a Playwright spec; the runner stage copies only .next/standalone, .next/static
# and public) flagged the frontend as stale and prescribed a rebuild that could
# not change a byte of the served bundle.
#
# The header above used to say applying .dockerignore was refused outright,
# because "translating ignore patterns into git pathspec exclusions risks
# excluding MORE than intended, and an over-broad exclusion is a FALSE NEGATIVE".
# That reasoning is correct and is preserved here as the design constraint — it
# is answered by refusing to translate anything ambiguous, rather than by
# refusing to translate at all:
#
#   * ANY '!' re-include line voids the WHOLE file. A negation re-admits paths an
#     earlier pattern excluded, so no pattern can be honoured in isolation.
#   * Any pattern containing * ? [ ] or .. is SKIPPED, not guessed at.
#   * The Dockerfile is never excluded even if named. It is read by the builder
#     as the recipe rather than copied from the context, so ignoring it would not
#     stop it being a build input — and hiding a changed recipe is precisely the
#     false negative this gate exists to prevent.
#
# Every one of those falls back to today's behaviour: the path stays IN the input
# set and the gate over-reports. Over-reporting costs an unnecessary rebuild;
# under-reporting ships a stale runtime behind a green gate. The asymmetry is why
# the skip list is silent-by-default but the refusals are printed.
#
# Patterns are anchored at the context root, matching Docker's own semantics:
# `temp` excludes <context>/temp, never <context>/sub/temp.
# ---------------------------------------------------------------------------
# Emits one TAB-separated record per line, so the caller gets BOTH results out of
# one call without a global:
#   X <TAB> :(exclude)<path>     a pathspec exclusion to apply
#   N <TAB> <text>               a refusal the caller must PRINT
#
# The two-channel shape is deliberate. The first version of this set a global from
# inside the function and the caller read it after `mapfile -t x < <(fn ...)` —
# but process substitution runs the function in a SUBSHELL, so the global never
# reached the parent and every refusal was silently dropped. The gate looked
# clean precisely because the part that reports doubt could not speak. Keeping the
# notes in the return stream makes that unrepresentable.
dockerignore_excludes() { # <context_abs> <context_rel ('.' if repo root)>
    local ctx_abs="$1" ctx_rel="$2"
    local file="$ctx_abs/.dockerignore"
    local raw line p
    local -a pats=() skipped=()

    [ -f "$file" ] || return 0

    while IFS= read -r raw || [ -n "$raw" ]; do
        line="${raw%$'\r'}"
        line="${line#"${line%%[![:space:]]*}"}"
        line="${line%"${line##*[![:space:]]}"}"
        [ -n "$line" ] || continue
        case "$line" in
            '#'*) continue ;;
            '!'*)
                printf 'N\t%s\n' "contains a '!' re-include ($line) — NO exclusion applied from this file; every path stays a build input"
                return 0 ;;
        esac
        pats+=("$line")
    done < "$file"

    for p in "${pats[@]+"${pats[@]}"}"; do
        p="${p#/}"; p="${p%/}"
        [ -n "$p" ] || continue
        case "$p" in
            *'*'*|*'?'*|*'['*|*']'*|*'..'*) skipped+=("$p"); continue ;;
            Dockerfile|Dockerfile.*)        skipped+=("$p (build recipe)"); continue ;;
        esac
        if [ "$ctx_rel" = "." ]; then
            printf 'X\t:(exclude)%s\n' "$p"
        else
            printf 'X\t:(exclude)%s/%s\n' "$ctx_rel" "$p"
        fi
    done

    if [ "${#skipped[@]}" -gt 0 ]; then
        printf 'N\t%s\n' "kept as build inputs (not translatable exactly): ${skipped[*]}"
    fi
    return 0
}

# ---------------------------------------------------------------------------
# Service discovery — the compose file is the config layer, so it is the only
# place the service set, its build context and its Dockerfile are stated.
# ---------------------------------------------------------------------------
CONFIG_JSON="$(docker compose -f "$COMPOSE_PATH" config --format json 2>/dev/null)" \
    || parse_fail "docker compose config failed for $COMPOSE_PATH"
[ -n "$CONFIG_JSON" ] || parse_fail "docker compose config produced no output for $COMPOSE_PATH"

# One TAB-separated row per service that declares a build stanza.
BUILT_SERVICES="$(
    jq -r '
      (.services // {}) | to_entries
      | map(select(.value.build != null))
      | .[]
      | [ .key,
          (.value.build.context    // "MISSING"),
          (.value.build.dockerfile // "Dockerfile") ]
      | @tsv
    ' <<< "$CONFIG_JSON"
)" || parse_fail "could not extract build stanzas from the compose config JSON"

[ -n "$BUILT_SERVICES" ] || parse_fail \
    "discovered ZERO services with a build: stanza in $COMPOSE_PATH. A gate that finds nothing must never report clean — if this compose file genuinely builds nothing, this gate does not apply to it."

# Running containers of THIS compose project only, so a cohabiting stack on the
# same daemon can neither satisfy nor break the assertion.
PS_JSON="$(docker compose -f "$COMPOSE_PATH" ps --all --format json 2>/dev/null)" \
    || parse_fail "docker compose ps failed for $COMPOSE_PATH"
# compose emits one JSON object per line; -s makes that an array either way.
PS_ROWS="$(jq -rs 'map([ (.Service // ""), (.Name // ""), (.State // "") ] | @tsv) | .[]' <<< "${PS_JSON:-}")" \
    || parse_fail "could not parse docker compose ps JSON for $COMPOSE_PATH"

echo "Runtime freshness gate"
echo "  compose file : $COMPOSE_PATH"
echo "  repo root    : $REPO_ROOT"
echo "  HEAD         : $(git -C "$REPO_ROOT" rev-parse --short HEAD) ($(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD))"
echo

VERIFIED=0
DRIFTED=0
SKIPPED=0
SKIP_REPORT=""
DRIFT_REPORT=""

while IFS=$'\t' read -r service context dockerfile; do
    [ -n "$service" ] || continue
    [ "$context" != "MISSING" ] || parse_fail "service '$service' has a build stanza with no context"

    # --- is it running? A stopped service is UNVERIFIED, never a pass. --------
    container=""
    state=""
    while IFS=$'\t' read -r ps_service ps_name ps_state; do
        if [ "$ps_service" = "$service" ]; then
            container="$ps_name"; state="$ps_state"; break
        fi
    done <<< "$PS_ROWS"

    if [ -z "$container" ]; then
        SKIPPED=$((SKIPPED + 1))
        SKIP_REPORT+="  $service — no container exists for this service in the project (nothing to verify)"$'\n'
        continue
    fi
    # Allow-list of exactly one state, deliberately: 'restarting', 'paused',
    # 'dead' and anything docker adds later are NOT provably the runtime you are
    # about to trust, so they resolve to UNVERIFIED rather than to a pass. Case
    # is folded only so a cosmetic change in compose's JSON cannot silently VOID
    # the whole gate.
    if [ "${state,,}" != "running" ]; then
        SKIPPED=$((SKIPPED + 1))
        SKIP_REPORT+="  $service — container '$container' is '$state', not 'running' (nothing to verify)"$'\n'
        continue
    fi

    # --- what the container is actually executing ----------------------------
    image_ref="$(docker inspect "$container" --format '{{.Config.Image}}' 2>/dev/null)" \
        || parse_fail "could not inspect container '$container' of service '$service'"
    [ -n "$image_ref" ] || parse_fail "container '$container' reports an empty image reference"

    container_image_id="$(docker inspect "$container" --format '{{.Image}}' 2>/dev/null)" \
        || parse_fail "could not read the image ID of container '$container'"

    tag_image_id="$(docker image inspect "$image_ref" --format '{{.Id}}' 2>/dev/null)" \
        || parse_fail "image '$image_ref' (service '$service') cannot be inspected — the tag the running container was created from no longer resolves on this daemon, so its freshness is unknowable"

    last_tag_raw="$(docker image inspect "$image_ref" --format '{{.Metadata.LastTagTime}}' 2>/dev/null)" \
        || parse_fail "could not read .Metadata.LastTagTime of image '$image_ref' (service '$service')"
    image_epoch=""
    image_epoch="$(to_epoch "$last_tag_raw" "image '$image_ref' .Metadata.LastTagTime")"

    # --- build inputs: context + the Dockerfile's host-side COPY operands ----
    case "$context" in
        /*) context_abs="$context" ;;
        *)  context_abs="$REPO_ROOT/$context" ;;
    esac
    case "$dockerfile" in
        /*) dockerfile_abs="$dockerfile" ;;
        *)  dockerfile_abs="$context_abs/$dockerfile" ;;
    esac
    [ -f "$dockerfile_abs" ] || parse_fail \
        "service '$service' names dockerfile '$dockerfile' but $dockerfile_abs does not exist"

    copy_srcs=""
    copy_srcs="$(awk "$COPY_AWK" "$dockerfile_abs")" || parse_fail \
        "$dockerfile_abs contains a COPY/ADD form this gate cannot resolve into source paths (reported above). Resolve it explicitly rather than letting an unparsed COPY become an invisible hole in service '$service' build inputs."

    # Repo-relative pathspecs. The Dockerfile itself is a build input: change the
    # build recipe without rebuilding and the runtime is just as stale.
    declare -a paths=()
    add_path() { # <absolute path> -> append as a repo-relative git pathspec
        local abs="$1" rel
        abs="${abs%/}"
        if [ "$abs" = "$REPO_ROOT" ]; then
            # The build context IS the repo root (core-java builds from `.`).
            # '.' is the whole-repo pathspec relative to `git -C "$REPO_ROOT"`.
            paths+=(".")
            return 0
        fi
        rel="${abs#"$REPO_ROOT"/}"
        # Unchanged prefix means the path lies outside the work tree, so git
        # cannot date it. Skipping it is safe ONLY because an empty final input
        # set is a hard parse_fail below.
        [ "$rel" != "$abs" ] || return 0
        [ -n "$rel" ] && paths+=("$rel")
        return 0
    }
    add_path "$dockerfile_abs"
    while IFS= read -r src; do
        [ -n "$src" ] || continue
        case "$src" in
            .|./) add_path "$context_abs" ;;
            /*)   add_path "$src" ;;
            *)    add_path "$context_abs/${src#./}" ;;
        esac
    done <<< "$copy_srcs"

    if [ "${#paths[@]}" -eq 0 ]; then
        parse_fail "service '$service' resolved to ZERO in-repo build paths (context '$context_abs', dockerfile '$dockerfile'). An empty input set would compare the image against nothing and report clean."
    fi

    # Deduplicate, preserving order, so the reported pathspec is readable.
    declare -a uniq_paths=()
    for p in "${paths[@]}"; do
        seen=0
        for q in "${uniq_paths[@]+"${uniq_paths[@]}"}"; do
            if [ "$p" = "$q" ]; then seen=1; break; fi
        done
        if [ "$seen" -eq 0 ]; then uniq_paths+=("$p"); fi
    done

    # --- subtract what the build context excludes ----------------------------
    # A .dockerignore'd file is not in the context, so no COPY can carry it into
    # the image and a change to it cannot make the runtime stale. See the
    # dockerignore_excludes() header for why this is conservative by construction.
    if [ "$context_abs" = "$REPO_ROOT" ]; then
        context_rel="."
    else
        context_rel="${context_abs#"$REPO_ROOT"/}"
    fi
    # The while-read runs in THIS shell (only the function is in the subshell), so
    # both arrays are populated in the parent. See dockerignore_excludes' header
    # for why the notes travel in the return stream rather than in a global.
    declare -a di_excludes=()
    while IFS=$'\t' read -r di_kind di_val; do
        case "$di_kind" in
            X) di_excludes+=("$di_val") ;;
            N) printf '  %-12s note   .dockerignore %s\n' "$service" "$di_val" ;;
        esac
    done < <(dockerignore_excludes "$context_abs" "$context_rel")

    # --- newest commit touching those paths ---------------------------------
    # awk (not `sort -rn | head -1`) computes the maximum: under `set -o pipefail`
    # a downstream `head` that exits early makes the writer take SIGPIPE and
    # promotes the pipeline to 141. awk consumes the whole stream, so it cannot.
    # The maximum is taken rather than the first row because committer dates are
    # not monotonic across rebases and cherry-picks.
    commit_row=""
    commit_row="$(
        git -C "$REPO_ROOT" log --full-history --format='%ct%x09%h%x09%cI%x09%s' \
            -- "${uniq_paths[@]}" "${di_excludes[@]+"${di_excludes[@]}"}" \
        | awk -F'\t' 'NR==1 || $1+0 > max+0 { max=$1; line=$0 } END { if (NR) print line }'
    )" || parse_fail "git log failed for service '$service' (pathspec: ${uniq_paths[*]} ${di_excludes[*]+${di_excludes[*]}})"

    [ -n "$commit_row" ] || parse_fail \
        "no commit in HEAD's history touches ANY build path of service '$service' (pathspec: ${uniq_paths[*]}). An empty git result cannot be read as fresh — the pathspec is wrong or the paths are untracked."

    IFS=$'\t' read -r commit_epoch commit_short commit_iso commit_subject <<< "$commit_row"
    [[ "$commit_epoch" =~ ^[0-9]+$ ]] \
        || parse_fail "unparseable commit epoch '$commit_epoch' for service '$service'"

    # --- the two assertions -------------------------------------------------
    service_drift=""
    service_modes=""
    if [ "$image_epoch" -lt "$commit_epoch" ]; then
        service_modes+="image-not-rebuilt "
        service_drift+="    [image-not-rebuilt] image '$image_ref' was tagged $(human "$image_epoch"), but its build inputs changed later:"$'\n'
        service_drift+="                        commit $commit_short  $commit_iso  $commit_subject"$'\n'
        service_drift+="                        build paths: ${uniq_paths[*]}"$'\n'
    fi
    if [ "$container_image_id" != "$tag_image_id" ]; then
        service_modes+="container-not-recreated "
        service_drift+="    [container-not-recreated] container '$container' is executing image ${container_image_id:0:19}, but the tag '$image_ref' now points at ${tag_image_id:0:19}."$'\n'
        service_drift+="                        The image was rebuilt and the container was only started/restarted, so the new image is not running."$'\n'
    fi

    if [ -n "$service_drift" ]; then
        DRIFTED=$((DRIFTED + 1))
        DRIFT_REPORT+="  $service (container '$container')"$'\n'"$service_drift"
        # The one-line summary names WHICH mode(s) fired. It must not assert the
        # timestamp inequality unconditionally: a container-not-recreated-only
        # drift has a perfectly current tag time, and printing "tagged X < commit Y"
        # there would be a literally false statement in the gate's own output.
        printf '  %-12s DRIFT  [%s]  image tagged %s / newest build-input commit %s (%s)\n' \
            "$service" "${service_modes% }" "$(human "$image_epoch")" \
            "$commit_short" "$(human "$commit_epoch")"
    else
        VERIFIED=$((VERIFIED + 1))
        printf '  %-12s FRESH  image tagged %s >= newest build-input commit %s (%s)\n' \
            "$service" "$(human "$image_epoch")" "$commit_short" "$(human "$commit_epoch")"
    fi

    unset paths uniq_paths di_excludes
done <<< "$BUILT_SERVICES"

echo

SKIP_SUFFIX=""
if [ -n "$SKIP_REPORT" ]; then
    echo "SKIPPED (UNVERIFIED — these services were NOT proven fresh):"
    printf '%s' "$SKIP_REPORT"
    echo
    SKIP_SUFFIX=", listed above"
fi

if [ "$VERIFIED" -eq 0 ] && [ "$DRIFTED" -eq 0 ]; then
    parse_fail "not one built service was verifiable — $SKIPPED skipped, 0 checked. A runtime that is not running cannot be proven fresh, so this assertion is VOID, not passing."
fi

# FAIL CLOSED PER SERVICE, not just in aggregate (27-00 Task 6, AC-6.12).
#
# This block previously fired only when ZERO built services were verifiable. Stop ONE of four
# and the gate printed `PASS: 3 ... match (1 unverified)` and exited 0 — measured 2026-07-27 by
# stopping the core-java container. That is the exact shape this gate exists to kill: an
# unproven service reported inside a pass, where "we could not check it" is rendered
# indistinguishable from "we checked it and it was fine". The header two hundred lines above
# already promised "found nothing IS NEVER clean"; per service, it was not true.
#
# A skipped service is not a failure (nothing proves it stale) and not a pass (nothing proves it
# fresh) — it is precisely VOID, so it exits 2. Drift still takes precedence: a runtime KNOWN to
# be stale is a stronger statement than one that could not be evaluated, so exit 1 wins below.
#
# No bypass flag is offered on purpose. A `--allow-unverified` switch is how a check earns a
# `|| true`. If you are deliberately running a subset of the stack, scope the gate to what you
# are running with --compose-file; otherwise VOID is the honest answer.
#
# Verified safe to tighten: on a healthy full stack SKIPPED is 0 (4 built services, all
# running), so this does NOT turn a correct tree red — checked before the change, because an
# expected-0 that is 1 on a correct tree is how a "fix" causes an outage. The runtime half of
# this pair is deliberately not wired into CI (a runner has no containers), so nothing in
# .github/workflows changes behaviour either.
if [ "$SKIPPED" -gt 0 ] && [ "$DRIFTED" -eq 0 ]; then
    parse_fail "$SKIPPED of $((VERIFIED + SKIPPED)) built service(s) could not be verified$SKIP_SUFFIX — $VERIFIED checked. A service that is not running cannot be proven fresh, so this assertion is VOID, not passing. Start the missing service(s), or scope the gate with --compose-file if you are deliberately running a subset."
fi

if [ "$DRIFTED" -gt 0 ]; then
    echo "DRIFT DETAIL:"
    printf '%s' "$DRIFT_REPORT"
    echo
    echo "Rebuild the named services from the current tree, then RECREATE their containers:"
    echo "  docker compose -f ${COMPOSE_FILE} up -d --build <service>..."
    echo "\`docker compose start\` and \`restart\` will NOT fix this — neither builds, and neither"
    echo "replaces a container that is holding an older image ID."
    fail "$DRIFTED of $((VERIFIED + DRIFTED)) running built service(s) do not match the source tree ($SKIPPED unverified$SKIP_SUFFIX)."
fi

echo "PASS: $VERIFIED running built service(s) match the source tree ($SKIPPED unverified$SKIP_SUFFIX)."
