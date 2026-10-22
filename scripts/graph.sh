#!/usr/bin/env bash
# graph.sh — answer code-structure questions from a graphify graph that is provably fresh.
#
# WHY THIS EXISTS
#
#   grep answers "where does this string appear", never "who calls this" or "what breaks if
#   this changes". graphify (PyPI `graphifyy`) answers those from a tree-sitter graph, but used
#   raw it fails quietly in four ways, all measured on this repo 2026-09-23:
#     - a stale graph (built by an older graphify, 14 commits back) MISSED JwtTenantFilter's
#       TenantContext.set() — the production writer of the tenant — at every depth;
#     - an unknown symbol exits 0 with "No unique node match", which reads as "no impact";
#     - an ambiguous name is resolved silently: `affected Tenant` returned 80 dependents of a
#       node it never named, out of three candidates;
#     - an in-place `graphify update` writes ~200 MB into the working tree and takes 27-46 s on
#       EVERY run.
#   This wrapper closes each one: it builds from a `git archive HEAD` export OUTSIDE the tree,
#   rebuilds exactly when the commit, the graphify version or the pathspec changes, stamps
#   every answer with its provenance, and exits non-zero whenever it cannot answer.
#
# USAGE
#
#   scripts/graph.sh affected <Symbol|node-id> [--depth 1|2]   who depends on it (default depth 1)
#   scripts/graph.sh explain  <Symbol|node-id>                  the node and its neighbours
#   scripts/graph.sh path     <A> <B> [--undirected]            shortest path, within one service
#   scripts/graph.sh god-nodes [--top N]                        most-connected nodes
#   scripts/graph.sh status                                     stamp + freshness, never builds
#   scripts/graph.sh rebuild                                    force a rebuild now
#
#   The first line of every answer is the stamp: commit, graphify version, node count, and how
#   many uncommitted files the graph does NOT contain (it is built from HEAD).
#   Every symbol is resolved once and its node's source file is printed before the answer, so
#   a fuzzy match to the wrong node is visible.
#   For security-relevant impact, also run `git grep -w <Symbol>`: the graph has no
#   cross-service edges (HTTP boundaries are invisible) and links by identifier only.
#
# EXIT CODES
#
#   0 answered   2 refused (unknown subcommand or bad arguments; nothing executed)
#   3 SYMBOL NOT IN GRAPH or AMBIGUOUS SYMBOL   4 SUSPICIOUS SHRINK (old graph kept)
#   5 precondition missing (graphify, its [sql] extra, git)   6 build or query failed
#
# INVARIANTS
#
#   - Nothing is written inside the repository. The graph lives under GRAPH_CACHE_ROOT.
#   - No LLM or network spend: only `update` and read-only queries run, with every *_API_KEY,
#     *_AUTH_TOKEN, AWS_*, OLLAMA_* and AZURE_OPENAI_* variable removed from graphify's
#     environment. `label`, `cluster-only`, `query`, `watch`, `hook`, `install`, `add`, `clone`,
#     `prs` and `merge-*` are refused — `hook install` would write the machine's GLOBAL git hooks.
#   - Never `--no-cluster`: that graph makes `affected` return 0 files, silently.
#   - At most one rebuild per (HEAD, graphify version, pathspec); concurrent callers share it.
#
# Configuration: scripts/graph.conf (every key overridable from the environment).
# Plan and measurements: ~/GRAPH-TOOLING-PLAN.md (approved 2026-09-23), Step 2.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=graph.conf
. "$SCRIPT_DIR/graph.conf"

die() { local rc=$1; shift; printf 'graph.sh: %s\n' "$*" >&2; exit "$rc"; }
note() { printf 'graph.sh: %s\n' "$*" >&2; }

usage() {
  sed -n '/^# USAGE$/,/^# EXIT CODES$/p' "${BASH_SOURCE[0]}" | sed '1d;$d;s/^# \{0,1\}//' >&2
  exit 2
}

# ── argument validation first: a refused command must execute nothing ──
cmd=${1:-}; [ $# -gt 0 ] && shift
depth=1; top_n=10; undirected=0; args=()
case "$cmd" in
  affected|explain)
    [ $# -ge 1 ] || usage
    args=("$1"); shift
    while [ $# -gt 0 ]; do
      if [ "$cmd" = affected ] && [ "$1" = --depth ] && [ $# -ge 2 ]; then depth=$2; shift 2
      else die 2 "unexpected argument for $cmd: $1"; fi
    done
    case "$depth" in 1|2) ;; *) die 2 "--depth must be 1 or 2 (got: $depth)";; esac ;;
  path)
    [ $# -ge 2 ] || usage
    args=("$1" "$2"); shift 2
    while [ $# -gt 0 ]; do
      case "$1" in --undirected) undirected=1; shift ;; *) die 2 "unexpected argument for path: $1";; esac
    done ;;
  god-nodes)
    while [ $# -gt 0 ]; do
      if [ "$1" = --top ] && [ $# -ge 2 ]; then top_n=$2; shift 2
      else die 2 "unexpected argument for god-nodes: $1"; fi
    done
    [[ "$top_n" =~ ^[1-9][0-9]?$ ]] || die 2 "--top must be 1..99 (got: $top_n)" ;;
  status|rebuild)
    [ $# -eq 0 ] || die 2 "$cmd takes no arguments" ;;
  ''|-h|--help|help) usage ;;
  *) die 2 "refused: '$cmd' is not an allowed subcommand (affected, explain, path, god-nodes, status, rebuild)" ;;
esac

# ── preconditions (exit 5) ──
command -v git >/dev/null 2>&1 || die 5 "git not found"
top=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null) || die 5 "not inside a git checkout: $SCRIPT_DIR"
gbin=$(type -P "$GRAPHIFY_BIN") || die 5 "graphify not found ($GRAPHIFY_BIN); install: uv tool install \"graphifyy[sql]\""
# The [sql] extra: without tree-sitter-sql every .sql file yields zero nodes behind a warning at
# exit 0. Probed in the venv the binary actually runs from, located through its shebang.
venv=$(head -1 "$(readlink -f "$gbin")" | sed -n 's|^#!\(.*\)/bin/python[0-9.]*$|\1|p')
[ -n "$venv" ] && compgen -G "$venv/lib/python*/site-packages/tree_sitter_sql/__init__.py" >/dev/null \
  || die 5 "graphify's [sql] extra is missing (or its venv could not be located from $gbin); repair: uv tool install \"graphifyy[sql]\""
ver=$("$gbin" --version 2>/dev/null | awk 'NR==1{print $2}')
[ -n "$ver" ] || die 5 "could not read the graphify version from: $gbin --version"
[ "$ver" = "$GRAPH_VALIDATED_VERSION" ] \
  || note "WARNING: graphify $ver is not the validated $GRAPH_VALIDATED_VERSION; answers are unproven until the acceptance arms are re-run on it"

head_sha=$(git -C "$top" rev-parse HEAD 2>/dev/null) || die 5 "no HEAD commit in $top"
read -r -a pathspec <<< "$GRAPH_PATHSPEC"
spec_sha=$(printf '%s' "$GRAPH_PATHSPEC" | sha1sum | cut -c1-12)
key="$GRAPH_CACHE_ROOT/$(basename "$top")-$(printf '%s' "$top" | sha1sum | cut -c1-8)"
mkdir -p "$key" || die 5 "cannot create the graph cache: $key"
graph="$key/graph/graph.json"

# graphify's environment: no credentials, so nothing can reach a paid backend.
scrub=()
while IFS= read -r v; do
  case "$v" in *_API_KEY|*_AUTH_TOKEN|AWS_*|OLLAMA_*|AZURE_OPENAI_*) scrub+=(-u "$v") ;; esac
done < <(compgen -e)
gfy() { env "${scrub[@]}" "$gbin" "$@"; }

stamp_get() { [ -f "$key/stamp" ] && sed -n "s/^$1=//p" "$key/stamp" | head -1; }

# Why the graph cannot answer for this HEAD, or nothing if it can.
stale_reason() {
  [ -f "$key/stamp" ] && [ -f "$graph" ] || { echo "no graph built yet"; return; }
  local r=()
  [ "$(stamp_get head)" = "$head_sha" ] || r+=("commit $(stamp_get head | cut -c1-12) -> ${head_sha:0:12}")
  [ "$(stamp_get version)" = "$ver" ] || r+=("graphify $(stamp_get version) -> $ver")
  [ "$(stamp_get pathspec)" = "$spec_sha" ] || r+=("pathspec changed")
  [ ${#r[@]} -eq 0 ] || { local IFS=','; echo "${r[*]}"; }
}

rebuild() {
  local force=${1:-0} why old new rc nodes
  exec 9>"$key/lock"
  flock 9 || die 6 "could not lock $key/lock"
  # Re-checked under the lock: a concurrent caller may have built this HEAD while we waited.
  why=$(stale_reason)
  if [ -z "$why" ] && [ "$force" -eq 0 ]; then exec 9>&-; return 0; fi
  note "rebuilding graph (${why:-forced}) from ${head_sha:0:12} ..."
  rm -rf "$key/src" "$key/next" "$key/src.tar"
  mkdir -p "$key/src" "$key/next" || die 6 "cannot create build directories under $key"
  git -C "$top" archive --format=tar -o "$key/src.tar" "$head_sha" -- "${pathspec[@]}" \
    || die 6 "git archive failed for pathspec: $GRAPH_PATHSPEC"
  tar -x -C "$key/src" -f "$key/src.tar" || die 6 "could not unpack the export"
  rm -f "$key/src.tar"
  # Carry the AST cache forward (content-addressed per graphify version) so a rebuild re-parses
  # only what changed; drop other versions' entries, which can never be read again.
  if [ -d "$key/graph/cache" ]; then
    cp -a "$key/graph/cache" "$key/next/cache" || die 6 "could not copy the AST cache"
    /usr/bin/find "$key/next/cache/" -mindepth 2 -maxdepth 2 -path '*/ast/*' ! -name "v$ver-*" -exec rm -rf {} +
  fi
  ( cd "$key/src" && GRAPHIFY_OUT="$key/next" GRAPHIFY_FORCE=1 gfy update . ) > "$key/build.log" 2>&1
  rc=$?
  [ $rc -eq 0 ] || die 6 "graphify update failed (rc=$rc); log: $key/build.log"
  nodes=$(sed -n 's/.*Rebuilt: \([0-9][0-9]*\) nodes.*/\1/p' "$key/build.log" | tail -1)
  [ -n "$nodes" ] && [ -f "$key/next/graph.json" ] \
    || die 6 "build reported no node count or wrote no graph.json; log: $key/build.log"
  old=$(stamp_get nodes); old=${old:-0}
  if [ "$nodes" -eq 0 ] || { [ "$old" -gt 0 ] && [ $((nodes * 2)) -lt "$old" ]; }; then
    rm -rf "$key/next" "$key/src"
    die 4 "SUSPICIOUS SHRINK: $nodes nodes against $old before; the previous graph is kept"
  fi
  rm -rf "$key/prev"
  [ -d "$key/graph" ] && mv "$key/graph" "$key/prev"
  mv "$key/next" "$key/graph" || die 6 "could not install the new graph"
  rm -rf "$key/prev" "$key/src"
  { echo "head=$head_sha"; echo "version=$ver"; echo "pathspec=$spec_sha"; echo "nodes=$nodes"
    echo "built=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$key/stamp.tmp" && mv "$key/stamp.tmp" "$key/stamp"
  note "rebuilt: $nodes nodes"
  exec 9>&-
}

print_stamp() {
  local dirty
  dirty=$(git -C "$top" status --porcelain 2>/dev/null | grep -c .)
  printf 'graph: commit %s · graphify %s · %s nodes · built %s · %s uncommitted file(s) not in graph\n' \
    "$(stamp_get head | cut -c1-12)" "$(stamp_get version)" "$(stamp_get nodes)" "$(stamp_get built)" "$dirty"
}

# Resolve a symbol to exactly one node id through `explain`, which refuses an ambiguous name
# (`affected` would silently pick one). Prints the resolved node to stderr; id on stdout.
resolve() {
  local q=$1 out rc id
  out=$(gfy explain "$q" --graph "$graph" 2>&1); rc=$?
  if grep -q '^Ambiguous:' <<< "$out"; then
    printf '%s\n' "$out" >&2
    die 3 "AMBIGUOUS SYMBOL: '$q' — pass one of the ids above"
  fi
  grep -q -E "^No node matching|^No unique node match" <<< "$out" && die 3 "SYMBOL NOT IN GRAPH: '$q'"
  [ $rc -eq 0 ] || die 6 "graphify explain failed (rc=$rc): $(head -3 <<< "$out")"
  id=$(sed -n 's/^  ID: *//p' <<< "$out" | head -1)
  [ -n "$id" ] || die 6 "could not read a node id from graphify explain for '$q'"
  printf 'resolved: %s -> %s\n' "$q" "$(sed -n 's/^  Source: *//p' <<< "$out" | head -1)" >&2
  printf '%s\n' "$id"
}

run_query() {
  local out rc
  out=$(gfy "$@" --graph "$graph" 2>&1); rc=$?
  [ $rc -eq 0 ] || die 6 "graphify $1 failed (rc=$rc): $(head -3 <<< "$out")"
  grep -q -E "^No node matching|^No unique node match" <<< "$out" && die 3 "SYMBOL NOT IN GRAPH: $(head -1 <<< "$out")"
  [ -n "$out" ] || die 6 "graphify $1 returned nothing"
  printf '%s\n' "$out"
}

case "$cmd" in
  status)
    why=$(stale_reason)
    if [ -f "$key/stamp" ]; then print_stamp; fi
    echo "cache: $key"
    if [ -n "$why" ]; then echo "state: STALE ($why); the next query rebuilds (~35 s)"; else echo "state: fresh"; fi
    exit 0 ;;
  rebuild) rebuild 1; print_stamp; exit 0 ;;
esac

[ -z "$(stale_reason)" ] || rebuild 0
print_stamp
case "$cmd" in
  affected)
    id=$(resolve "${args[0]}") || exit $?
    [ "$depth" = 2 ] && note "depth 2 is noisy (a hub's dependents' dependents); prefer depth 1"
    run_query affected "$id" --depth "$depth" ;;
  explain)
    # explain IS the resolver: one call, its own Source line names the node it chose.
    out=$(gfy explain "${args[0]}" --graph "$graph" 2>&1); rc=$?
    if grep -q '^Ambiguous:' <<< "$out"; then
      printf '%s\n' "$out" >&2; die 3 "AMBIGUOUS SYMBOL: '${args[0]}' — pass one of the ids above"
    fi
    grep -q -E "^No node matching|^No unique node match" <<< "$out" && die 3 "SYMBOL NOT IN GRAPH: '${args[0]}'"
    [ $rc -eq 0 ] && [ -n "$out" ] || die 6 "graphify explain failed (rc=$rc): $(head -3 <<< "$out")"
    printf '%s\n' "$out" ;;
  path)
    # Resolved first because graphify's own path matcher only WARNS on an ambiguous name.
    a=$(resolve "${args[0]}") || exit $?
    b=$(resolve "${args[1]}") || exit $?
    if [ "$undirected" -eq 1 ]; then run_query path "$a" "$b" --undirected; else run_query path "$a" "$b"; fi ;;
  god-nodes)
    run_query god-nodes --top "$top_n" ;;
esac
