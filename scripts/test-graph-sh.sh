#!/usr/bin/env bash
# test-graph-sh.sh — the acceptance arms for scripts/graph.sh, run against a throwaway clone.
#
# Run it before bumping GRAPH_VALIDATED_VERSION in scripts/graph.conf, and after any change to
# graph.sh. It needs graphify installed (it builds real graphs, ~2 minutes) and so does not run
# in CI. It never touches a checkout or the real graph cache: everything happens in a temp
# clone with its own GRAPH_CACHE_ROOT, removed on exit.
#
#   scripts/test-graph-sh.sh                    arms against this checkout's graph.sh
#   scripts/test-graph-sh.sh --wrapper FILE     arms against FILE instead (a deliberately
#                                               broken copy proves the arms can fail)
#
# Exit 0 = every arm passed; 1 = at least one failed; 2 = could not run (VOID).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
wrapper="$ROOT/scripts/graph.sh"
if [ "${1:-}" = --wrapper ] && [ -n "${2:-}" ]; then wrapper=$(readlink -f "$2"); fi
[ -f "$wrapper" ] || { echo "VOID: no wrapper at $wrapper" >&2; exit 2; }
command -v graphify >/dev/null 2>&1 || { echo "VOID: graphify is not installed" >&2; exit 2; }

T=$(mktemp -d "${TMPDIR:-/tmp}/graph-arms.XXXXXX") || { echo "VOID: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$T"' EXIT
git clone -q "$ROOT" "$T/repo" || { echo "VOID: clone failed" >&2; exit 2; }
git -C "$T/repo" config user.email arms@localhost && git -C "$T/repo" config user.name arms
cp "$wrapper" "$T/repo/scripts/graph.sh" && cp "$ROOT/scripts/graph.conf" "$T/repo/scripts/graph.conf"
export GRAPH_CACHE_ROOT="$T/cache"
G="$T/repo/scripts/graph.sh"

fails=0
pass() { printf 'PASS  %s\n' "$*"; }
fail() { printf 'FAIL  %s\n' "$*"; fails=$((fails + 1)); }
# run ARGS... -> sets $out (stdout+stderr), $rc and $ms
run() { local s; s=$(date +%s%N); out=$("$@" 2>&1); rc=$?; ms=$(( ($(date +%s%N) - s) / 1000000 )); }
rebuilds() { grep -c 'rebuilding graph' <<< "$1"; }
tree_hash() { git -C "$T/repo" status --porcelain --ignored | sha1sum | cut -c1-12; }

# A stand-in graphify that records any execution. Its shebang names a python with no
# tree-sitter-sql, so the [sql] probe must refuse it before it could ever run.
fake="$T/fake-graphify"; marker="$T/fake-ran"
printf '#!/usr/bin/python3\nopen("%s", "w").write("ran")\n' "$marker" > "$fake"; chmod +x "$fake"

# (e) refused subcommands exit 2 and execute nothing
for sub in label cluster-only query watch hook install add clone prs merge-graphs; do
  run env GRAPHIFY_BIN="$fake" "$G" "$sub" x
  [ "$rc" -eq 2 ] && [ ! -e "$marker" ] && pass "(e) '$sub' refused rc=2, nothing executed" \
    || fail "(e) '$sub': rc=$rc, executed=$([ -e "$marker" ] && echo yes || echo no)"
done
# (f) graphify absent, and present without its [sql] extra: exit 5, nothing executed
run env GRAPHIFY_BIN=no-such-graphify-binary "$G" affected TenantContext
[ "$rc" -eq 5 ] && pass "(f) graphify absent -> rc=5" || fail "(f) graphify absent -> rc=$rc: $out"
# (f2) the install it prints is PINNED to the validated version: an unpinned one takes the newest
# release, which these arms have not validated
pin=$(. "$T/repo/scripts/graph.conf"; printf '%s' "$GRAPH_VALIDATED_VERSION")
grep -qF "graphifyy[sql]==$pin" <<< "$out" && pass "(f2) the install command is pinned to $pin" \
  || fail "(f2) the install command is not pinned to '$pin': $(tail -1 <<< "$out")"
run env GRAPHIFY_BIN="$fake" "$G" affected TenantContext
[ "$rc" -eq 5 ] && [ ! -e "$marker" ] && pass "(f) [sql] extra missing -> rc=5, nothing executed" \
  || fail "(f) [sql] extra missing -> rc=$rc: $out"

# first build; (h) the clone's tree is byte-for-byte unchanged by it; (i) the recall probe
before=$(tree_hash)
run "$G" affected TenantContext
[ "$rc" -eq 0 ] && [ "$(rebuilds "$out")" -eq 1 ] && pass "first query built once (rc=0, ${ms} ms)" \
  || fail "first query: rc=$rc rebuilds=$(rebuilds "$out")"
grep -q -E '^- .*core-java/src/main/java/uk/jtoye/core/security/JwtTenantFilter\.java:L[0-9]+' <<< "$out" \
  && pass "(i) affected TenantContext includes security/JwtTenantFilter.java" \
  || fail "(i) recall probe: JwtTenantFilter.java missing from affected TenantContext"
grep -q '^graph: commit [0-9a-f]\{12\} · graphify ' <<< "$out" && pass "provenance stamp is printed" \
  || fail "provenance stamp missing"
after=$(tree_hash)
[ "$before" = "$after" ] && pass "(h) git status --porcelain --ignored unchanged ($before)" \
  || fail "(h) the build changed the clone's tree: $before -> $after"

# (a) a fresh graph answers without rebuilding
run "$G" affected TenantContext
[ "$rc" -eq 0 ] && [ "$(rebuilds "$out")" -eq 0 ] && pass "(a) fresh graph: no rebuild, rc=0, ${ms} ms" \
  || fail "(a) fresh graph: rc=$rc rebuilds=$(rebuilds "$out")"
[ "$ms" -le 3000 ] && pass "(a) answered within 3 s (${ms} ms)" || fail "(a) took ${ms} ms (> 3000)"

# (d) unknown and ambiguous symbols exit 3, never an empty answer
run "$G" affected NoSuchSymbolXyz123
[ "$rc" -eq 3 ] && grep -q 'SYMBOL NOT IN GRAPH' <<< "$out" && pass "(d) unknown symbol -> rc=3 SYMBOL NOT IN GRAPH" \
  || fail "(d) unknown symbol -> rc=$rc: $(tail -1 <<< "$out")"
run "$G" affected Tenant
[ "$rc" -eq 3 ] && grep -q 'AMBIGUOUS SYMBOL' <<< "$out" && pass "(d) ambiguous symbol -> rc=3 AMBIGUOUS SYMBOL" \
  || fail "(d) ambiguous 'Tenant' -> rc=$rc: $(tail -1 <<< "$out")"
run "$G" explain NoSuchSymbolXyz123
[ "$rc" -eq 3 ] && pass "(d) explain unknown symbol -> rc=3" || fail "(d) explain unknown -> rc=$rc"

# (b) a new commit triggers a rebuild; (j) two concurrent callers share exactly one
git -C "$T/repo" commit -q --allow-empty -m "arms: new HEAD"
"$G" god-nodes --top 1 > "$T/c1" 2>&1 & p1=$!
"$G" god-nodes --top 1 > "$T/c2" 2>&1 & p2=$!
wait "$p1"; r1=$?; wait "$p2"; r2=$?
n=$(( $(rebuilds "$(cat "$T/c1")") + $(rebuilds "$(cat "$T/c2")") ))
[ "$r1" -eq 0 ] && [ "$r2" -eq 0 ] && [ "$n" -eq 1 ] && pass "(b)+(j) new commit: one rebuild across two concurrent calls" \
  || fail "(b)+(j) new commit: rc=$r1/$r2 rebuilds=$n"
run "$G" god-nodes --top 1
[ "$(rebuilds "$out")" -eq 0 ] && pass "(b) after it, the same HEAD does not rebuild" || fail "(b) rebuilt again at the same HEAD"

# (c) a graphify version change triggers a rebuild
stamp=$(ls "$GRAPH_CACHE_ROOT"/*/stamp)
sed -i 's/^version=.*/version=0.0.0-arms/' "$stamp"
run "$G" god-nodes --top 1
[ "$rc" -eq 0 ] && [ "$(rebuilds "$out")" -eq 1 ] && grep -q 'graphify 0.0.0-arms ->' <<< "$out" \
  && pass "(c) version change -> one rebuild, reason named" || fail "(c) version change: rc=$rc rebuilds=$(rebuilds "$out")"

# (g) a forced shrink exits 4 and keeps the previous graph, which still answers
gsum=$(sha1sum "$(dirname "$stamp")/graph/graph.json" | cut -c1-12); ssum=$(sha1sum "$stamp" | cut -c1-12)
run env GRAPH_PATHSPEC="mcp-server" "$G" god-nodes --top 1
[ "$rc" -eq 4 ] && grep -q 'SUSPICIOUS SHRINK' <<< "$out" && pass "(g) forced shrink -> rc=4 SUSPICIOUS SHRINK" \
  || fail "(g) forced shrink -> rc=$rc: $(tail -1 <<< "$out")"
[ "$(sha1sum "$(dirname "$stamp")/graph/graph.json" | cut -c1-12)" = "$gsum" ] && [ "$(sha1sum "$stamp" | cut -c1-12)" = "$ssum" ] \
  && pass "(g) previous graph and stamp kept byte-identical" || fail "(g) previous graph or stamp changed"
run "$G" affected TenantContext
[ "$rc" -eq 0 ] && [ "$(rebuilds "$out")" -eq 0 ] && pass "(g) kept graph still answers" || fail "(g) kept graph: rc=$rc"
# (g2) a REAL shrink has a way out: `rebuild --accept-shrink` takes it, and only that does
grep -q 'rebuild --accept-shrink' <<< "$(env GRAPH_PATHSPEC="mcp-server" "$G" god-nodes --top 1 2>&1)" \
  && pass "(g2) the shrink refusal names its way out" || fail "(g2) the shrink refusal does not name --accept-shrink"
run env GRAPH_PATHSPEC="mcp-server" "$G" rebuild --accept-shrink
[ "$rc" -eq 0 ] && pass "(g2) rebuild --accept-shrink takes the smaller graph (rc=0)" || fail "(g2) rebuild --accept-shrink -> rc=$rc: $(tail -1 <<< "$out")"
run env GRAPH_PATHSPEC="mcp-server" "$G" god-nodes --top 1
[ "$rc" -eq 0 ] && [ "$(rebuilds "$out")" -eq 0 ] && pass "(g2) the accepted graph then answers without rebuilding" \
  || fail "(g2) after accepting: rc=$rc rebuilds=$(rebuilds "$out")"
run "$G" rebuild --bogus
[ "$rc" -eq 2 ] && pass "(g2) rebuild refuses an unknown flag (rc=2)" || fail "(g2) rebuild --bogus -> rc=$rc"

# (v) view: three self-contained pages from the fresh graph, written outside the repository.
# GRAPH_PATHSPEC=mcp-server keeps the graph accepted above, so these arms do not rebuild.
export GRAPH_PATHSPEC="mcp-server"
run "$G" view --out "$T/views"
okv=1; for f in graph.html tree.html callflow.html; do [ -s "$T/views/$f" ] || okv=0; done
[ "$rc" -eq 0 ] && [ "$okv" -eq 1 ] && [ "$(rebuilds "$out")" -eq 0 ] \
  && pass "(v) view --out writes graph.html, tree.html, callflow.html (rc=0, no rebuild)" \
  || fail "(v) view --out: rc=$rc pages=$okv rebuilds=$(rebuilds "$out"): $(tail -1 <<< "$out")"
[ "$okv" -eq 1 ] && [ ! -e "$T/views/graph.json" ] && pass "(v) the ~50 MB graph copy is removed afterwards" \
  || fail "(v) graph.json left in the view folder"
before=$(tree_hash)
run "$G" view --out "$T/repo/view-inside"
[ "$rc" -eq 2 ] && [ ! -e "$T/repo/view-inside" ] && [ "$(tree_hash)" = "$before" ] \
  && pass "(v) --out inside the checkout refused rc=2, nothing created" \
  || fail "(v) --out inside the checkout: rc=$rc created=$([ -e "$T/repo/view-inside" ] && echo yes || echo no)"
run "$G" view
[ "$rc" -eq 0 ] && compgen -G "$GRAPH_CACHE_ROOT/*/view/graph.html" >/dev/null \
  && pass "(v) default folder is <cache>/view" || fail "(v) default view: rc=$rc: $(tail -1 <<< "$out")"
run "$G" view --node-limit 0
[ "$rc" -eq 2 ] && grep -q -- '--node-limit must be' <<< "$out" && pass "(v) view refuses --node-limit 0 (rc=2)" || fail "(v) --node-limit 0 -> rc=$rc"
run "$G" view --out "$T/views"
[ "$rc" -eq 0 ] && [ -f "$T/views/.graph-sh-view" ] && [ -s "$T/views/graph.html" ] \
  && pass "(v) a second view replaces an earlier view's folder (rc=0)" || fail "(v) re-view into the same folder: rc=$rc: $(tail -1 <<< "$out")"
mkdir -p "$T/userdir" && printf 'mine\n' > "$T/userdir/graph.html"; usum=$(sha1sum "$T/userdir/graph.html" | cut -c1-12)
run "$G" view --out "$T/userdir"
[ "$rc" -eq 2 ] && [ "$(sha1sum "$T/userdir/graph.html" | cut -c1-12)" = "$usum" ] && [ "$(ls -A "$T/userdir" | wc -l)" -eq 1 ] \
  && pass "(v) a non-empty folder view did not make is refused rc=2, its files untouched" \
  || fail "(v) non-empty user folder: rc=$rc, file $( [ "$(sha1sum "$T/userdir/graph.html" 2>/dev/null | cut -c1-12)" = "$usum" ] && echo kept || echo CHANGED)"
# a refused view executes nothing: on a STALE graph the inside-checkout refusal comes before any rebuild
git -C "$T/repo" commit -q --allow-empty -m "arms: stale for view"
run "$G" view --out "$T/repo/view-inside"
[ "$rc" -eq 2 ] && [ "$(rebuilds "$out")" -eq 0 ] && [ ! -e "$T/repo/view-inside" ] \
  && pass "(v) refused --out on a stale graph: rc=2, no rebuild, nothing created" \
  || fail "(v) refused --out on a stale graph: rc=$rc rebuilds=$(rebuilds "$out")"
unset GRAPH_PATHSPEC

echo "graphify $(graphify --version 2>/dev/null | awk '{print $2}') · wrapper $wrapper · failed arms: $fails"
[ "$fails" -eq 0 ]
