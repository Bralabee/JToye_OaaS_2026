#!/usr/bin/env bash
# ci-docs-only.sh — classify a list of changed paths as "documentation only" or not.
#
# WHAT IT IS FOR
#
#   ci-cd.yaml's `changes` job feeds it the paths a push or pull request touched, and the
#   expensive jobs (Integration Tests, Build and Push Images, Deploy to Staging/Production,
#   Staging Edge Probe) are skipped when - and only when - it answers `docs_only=true`. A
#   merge that changes only documentation then costs the cheap gates, not ~72 minutes and a
#   staging redeploy.
#
# THE ONE RULE: FAIL CLOSED
#
#   `docs_only=true` is printed only when EVERY path matches the allow-list below. Anything
#   else prints `docs_only=false`: an unknown path, a path that fails the hygiene check, an
#   empty list, a blank line, an unreadable input. "Found nothing" is never "docs only" - an
#   empty list is VOID (exit 2) and is treated as code. The cost of a wrong `false` is one
#   slow CI run. The cost of a wrong `true` is code merged, imaged and shipped untested, so
#   the two errors are not weighed equally and every doubt resolves to `false`.
#
# USAGE
#
#   scripts/ci-docs-only.sh                  paths on stdin, one per line
#   scripts/ci-docs-only.sh --file PATH      paths from a file ('-' = stdin)
#   scripts/ci-docs-only.sh --selftest       prove the classifier can say both answers
#
#   Output (stdout, exactly one line):  docs_only=true | docs_only=false
#   Reasons go to stderr.
#   Exit: 0 = answered (true or false), 2 = VOID (empty/unreadable input, bad usage) - the
#   answer printed is still `docs_only=false`, and a caller must read BOTH the line and the
#   exit code: `docs_only=true` with a non-zero exit is not a thing this script emits.
#
#   Feed it paths with the rename-expanded form, `git diff --name-only --no-renames`: with
#   rename detection ON a `core-java/src/Foo.java -> docs/foo.md` move lists only the NEW
#   path, which would classify as documentation while a source file was deleted.
#
# THE ALLOW-LIST (a path must match one of these AND survive every exclusion below)
#
#   .planning/**             any file under the planning tree
#   docs/**/*.md             markdown under docs/ (NOT any other file type under docs/)
#   *.md                     top-level markdown (README.md, CHANGELOG-style files, AGENTS.md ...)
#   **/README.md             a README anywhere outside the exclusions
#
# THE EXCLUSIONS (evaluated first; each was found by searching, not assumed)
#
#   - Hygiene. The path must be only [A-Za-z0-9._/-], no empty / '.' / '..' component, no
#     leading or trailing '/'. That rejects git's quoted spelling of an odd path, a CR from
#     a CRLF file, a tab, a traversal like docs/../core-java/..., and any non-ASCII name.
#   - Any `src` path component. core-java/src is COPYed into the core-java image (and
#     core-java/src/main/resources/geo/SOURCE.md is read by PostcodeCentroidImporter at
#     startup), core-java/src/test/** holds fixtures whose bytes the tests assert on.
#   - Any non-.md file under docs/: docs/api/openapi-snapshot.json is read by
#     OpenApiSnapshotTest, the edge contract test and the OpenAPI gate; docs/metrics.json and
#     docs/retention-manifest.json are asserted on by gates and Jest; docs/config/*.sql and
#     docs/ops/*.yaml are tooling inputs. They fall out of the allow-list by not being .md.
#   - EXCLUDED_EXACT below: docs/webhooks.md is READ by WebhookSignatureVectorTest, which
#     asserts the document publishes the exact secret, timestamp, payload and signature the
#     test pins. Editing it can fail that test, so an edit to it is not "only documentation".
#   - README.md under a Docker build-context directory (DOCKER_CONTEXT_DIRS below). edge-go,
#     frontend and mcp-server build with `COPY . .`, so their README.md IS copied into the
#     builder stage; infra/backups is the pg-backup context; core-java builds from the repo
#     root but is a Dockerfile directory, kept in the set rather than argued about.
#     scripts/check-runtime-freshness.sh treats any file in these contexts as an image input,
#     so calling such a README "docs only" would contradict that gate.
#
# WHAT THIS DOES NOT DECIDE
#
#   It decides only whether the EXPENSIVE jobs may be skipped. The gates that parse docs -
#   docs-freshness, check-doc-metrics.sh, check-doc-citations.sh, check-changelog-contract.sh,
#   the handoff / dispositions / pentest-triage checks - are in jobs and workflows that still
#   run on every change, so a doc edit that breaks one of them still goes red.
#
# KEEPING THE EXCLUSIONS HONEST
#
#   A doc a test starts to read silently becomes a "docs only" edit that can break that test
#   without running it. --selftest therefore scans the test trees for docs/*.md and .planning/
#   paths that sit on a line with a file-read API and asserts each classifies as NOT docs-only,
#   and asserts every tracked Dockerfile's directory is in DOCKER_CONTEXT_DIRS. The scan is a
#   heuristic (it sees a literal path on a read line, not a path assembled from segments), so
#   it narrows the gap rather than closing it: the exclusion list is the authority.
#
# Exit codes: 0 = answered / selftest passed, 1 = selftest failed, 2 = VOID / usage.

set -uo pipefail
export LC_ALL=C

SELF="${BASH_SOURCE[0]}"
REPO_ROOT="${DOCS_ONLY_ROOT:-$(cd "$(dirname "$SELF")/.." && pwd)}"

# Paths that look like documentation but are read by something the skipped jobs run.
EXCLUDED_EXACT=(
  "docs/webhooks.md"                 # WebhookSignatureVectorTest asserts its contents
  "docs/api/openapi-snapshot.json"   # OpenApiSnapshotTest + OpenAPI gate + edge contract test
)

# Directories holding a Dockerfile. A README.md beneath any of them is a build input.
DOCKER_CONTEXT_DIRS=(
  "core-java"
  "edge-go"
  "frontend"
  "mcp-server"
  "infra/backups"
)

void() { echo "VOID: $*" >&2; echo "docs_only=false"; exit 2; }

# is_doc_path PATH -> 0 if the single path is documentation-only, 1 otherwise (reason on stderr
# only when ci_docs_only_verbose is set, so the selftest's bulk loops stay quiet).
is_doc_path() {
  local p="$1" x d
  [ -n "$p" ] || return 1
  [[ "$p" =~ ^[A-Za-z0-9._/-]+$ ]] || return 1
  case "$p" in /*|*/) return 1 ;; esac
  case "/$p/" in *//*|*/./*|*/../*) return 1 ;; esac
  case "/$p/" in */src/*) return 1 ;; esac
  for x in "${EXCLUDED_EXACT[@]}"; do
    [ "$p" = "$x" ] && return 1
  done
  case "$p" in
    .planning/*) return 0 ;;
    docs/*.md)   return 0 ;;
  esac
  if [[ "$p" == */README.md ]]; then
    for d in "${DOCKER_CONTEXT_DIRS[@]}"; do
      [[ "$p" == "$d"/* ]] && return 1
    done
    return 0
  fi
  if [[ "$p" != */* && "$p" == *.md ]]; then
    return 0
  fi
  return 1
}

# classify_file FILE -> prints the answer line; returns the exit code (0 answered, 2 void).
classify_file() {
  local f="$1" line n=0 bad="" failed=0
  [ -f "$f" ] && [ -r "$f" ] || { echo "VOID: input '$f' is not a readable file" >&2; echo "docs_only=false"; return 2; }
  if [ ! -s "$f" ]; then
    echo "VOID: the changed-path list is empty; an empty list proves nothing and is treated as code" >&2
    echo "docs_only=false"; return 2
  fi
  # bash drops NUL bytes silently; a list containing one is not a list we understand.
  local raw stripped
  raw=$(wc -c < "$f") || { echo "VOID: could not size '$f'" >&2; echo "docs_only=false"; return 2; }
  stripped=$(tr -d '\000' < "$f" | wc -c) || { echo "VOID: could not scan '$f'" >&2; echo "docs_only=false"; return 2; }
  if [ "$raw" != "$stripped" ]; then
    echo "NOT docs-only: the list contains NUL bytes" >&2; echo "docs_only=false"; return 0
  fi
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n + 1))
    if ! is_doc_path "$line"; then
      # A flag, not "$bad is non-empty": a BLANK line is the offender in that case and the
      # offender is the empty string.
      failed=1
      bad="$line"
      break
    fi
  done < "$f"
  if [ "$failed" -ne 0 ] || [ "$n" -eq 0 ]; then
    printf 'NOT docs-only: line %d is not an allowed documentation path: %q\n' "$n" "$bad" >&2
    echo "docs_only=false"; return 0
  fi
  echo "docs-only: all $n changed path(s) are allowed documentation paths" >&2
  echo "docs_only=true"; return 0
}

# --- selftest ------------------------------------------------------------------------------

ST_FAILS=0
ST_TOTAL=0
ST_TMP=""

st_cleanup() { [ -n "$ST_TMP" ] && rm -rf "$ST_TMP"; }

# st_case NAME EXPECT_OUT EXPECT_RC PRINTF-ARGS...: runs the real CLI on a file built from the
# printf arguments, so an arm exercises argument handling and the exit code, not just the
# function. The captured rc is read on the same statement as the command.
st_case() {
  local name="$1" want_out="$2" want_rc="$3"; shift 3
  local f="$ST_TMP/case.$ST_TOTAL" out rc
  ST_TOTAL=$((ST_TOTAL + 1))
  printf "$@" > "$f"
  out=$(bash "$SELF" --file "$f" 2>/dev/null); rc=$?
  if [ "$out" = "$want_out" ] && [ "$rc" = "$want_rc" ]; then
    echo "  ok   $name  ($out, rc=$rc)"
  else
    echo "  FAIL $name  (wanted '$want_out' rc=$want_rc, got '$out' rc=$rc)" >&2
    ST_FAILS=$((ST_FAILS + 1))
  fi
}

st_check() { # NAME CONDITION-RESULT(0=holds) DETAIL
  ST_TOTAL=$((ST_TOTAL + 1))
  if [ "$2" = 0 ]; then echo "  ok   $1"; else echo "  FAIL $1  ($3)" >&2; ST_FAILS=$((ST_FAILS + 1)); fi
}

# scan_read_sites: stdin = `git grep -n` output (file:line:text). Emits each docs/*.md or
# .planning/ path that appears on a line which also carries a file-read API.
scan_read_sites() {
  local line text
  while IFS= read -r line; do
    text="${line#*:}"; text="${text#*:}"
    if [[ "$text" =~ (resolve|Path\.of|Paths\.get|readFile|ReadFile|readFileSync|readdirSync|existsSync|path\.join|filepath\.Join|os\.Open|Files\.) ]]; then
      grep -o -E '(docs/[A-Za-z0-9_./-]+\.md|\.planning/[A-Za-z0-9_./-]+)' <<< "$text"
    fi
  done
}

selftest() {
  command -v git >/dev/null 2>&1 || void "git not found; the selftest's tree arms need it"
  ST_TMP=$(mktemp -d) || void "mktemp failed"
  trap st_cleanup EXIT
  echo "== classifier arms (real CLI, stdout + exit code) =="

  # True arms: without them every false arm could be passing because the script only says false.
  st_case "planning-only -> true"                    "docs_only=true"  0 '.planning/STATE.md\n.planning/phases/29-x/29-01-SUMMARY.md\n'
  st_case "planning non-md (json, png) -> true"      "docs_only=true"  0 '.planning/config.json\n.planning/ui-reviews/a.png\n'
  st_case "docs md + top-level md -> true"           "docs_only=true"  0 'docs/runbooks/staging-operations.md\nREADME.md\nCHANGELOG.md\n'
  st_case "deep docs md -> true"                     "docs_only=true"  0 'docs/a/b/c/d.md\n'
  st_case "outside-context README -> true"           "docs_only=true"  0 'qa/README.md\nk8s/base/networkpolicies/README.md\n'
  st_case "no trailing newline, still true"          "docs_only=true"  0 'docs/guides/x.md'

  # False arms: each one is a way this gate could be fooled into skipping real work.
  st_case "mixed doc + code -> false"                "docs_only=false" 0 'docs/runbooks/x.md\ncore-java/src/main/java/A.java\n'
  st_case "code first, docs after -> false"          "docs_only=false" 0 'core-java/src/main/java/A.java\ndocs/runbooks/x.md\n'
  st_case "empty file -> VOID, false"                "docs_only=false" 2 ''
  st_case "only a newline -> false"                  "docs_only=false" 0 '\n'
  st_case "blank line between docs -> false"         "docs_only=false" 0 'docs/a.md\n\ndocs/b.md\n'
  st_case "openapi-snapshot.json -> false"           "docs_only=false" 0 'docs/api/openapi-snapshot.json\n'
  st_case "docs/metrics.json -> false"               "docs_only=false" 0 'docs/metrics.json\n'
  st_case "docs/retention-manifest.json -> false"    "docs_only=false" 0 'docs/retention-manifest.json\n'
  st_case "any non-md under docs -> false"           "docs_only=false" 0 'docs/config/create-test-tenants.sql\n'
  st_case "docs/webhooks.md (read by a test) -> false" "docs_only=false" 0 'docs/webhooks.md\n'
  st_case "src md (packaged SOURCE.md) -> false"     "docs_only=false" 0 'core-java/src/main/resources/geo/SOURCE.md\n'
  st_case "src README -> false"                      "docs_only=false" 0 'core-java/src/test/resources/geo/README.md\n'
  # The two arms above are ALSO refused by other rules (not on the allow-list; a Docker-context
  # directory), so they would stay green if the src exclusion were deleted. These three are
  # refused by NOTHING but the src rule: each is otherwise a path the allow-list accepts.
  st_case "README in a src tree outside any context -> false" "docs_only=false" 0 'qa/src/README.md\n'
  st_case "docs md in a src dir -> false"            "docs_only=false" 0 'docs/guides/src/x.md\n'
  st_case ".planning file in a src dir -> false"     "docs_only=false" 0 '.planning/phases/x/src/y.txt\n'
  st_case "workflow change -> false"                 "docs_only=false" 0 '.github/workflows/ci-cd.yaml\n'
  st_case "workflow next to docs -> false"           "docs_only=false" 0 'docs/x.md\n.github/workflows/ci-cd.yaml\n'
  st_case "script change -> false"                   "docs_only=false" 0 'scripts/check-doc-metrics.sh\n'
  st_case "script under .planning-lookalike -> false" "docs_only=false" 0 'planning/x.sh\n'
  st_case "frontend README (COPY . .) -> false"      "docs_only=false" 0 'frontend/README.md\n'
  st_case "edge-go README (COPY . .) -> false"       "docs_only=false" 0 'edge-go/README.md\n'
  st_case "mcp-server README -> false"               "docs_only=false" 0 'mcp-server/README.md\n'
  st_case "pg-backup context README -> false"        "docs_only=false" 0 'infra/backups/README.md\n'
  st_case "md in a code dir that is not README -> false" "docs_only=false" 0 'frontend/scripts/x/NOTES.md\n'
  st_case "nested non-docs md -> false"              "docs_only=false" 0 'k8s/DEPLOYMENT.md\n'
  st_case "lower-case readme -> false"               "docs_only=false" 0 'qa/readme.md\n'
  st_case ".mdx is not .md -> false"                 "docs_only=false" 0 'docs/a.mdx\n'
  st_case "traversal out of docs -> false"           "docs_only=false" 0 'docs/../core-java/build.gradle.kts.md\n'
  st_case "traversal out of .planning -> false"      "docs_only=false" 0 '.planning/../scripts/x.sh\n'
  st_case "absolute path -> false"                   "docs_only=false" 0 '/docs/a.md\n'
  st_case "git-quoted path -> false"                 "docs_only=false" 0 '"docs/a\\nb.md"\n'
  st_case "CRLF line ending -> false"                "docs_only=false" 0 'docs/a.md\r\n'
  st_case "tab in path -> false"                     "docs_only=false" 0 'docs/a\tb.md\n'
  st_case "space in path -> false"                   "docs_only=false" 0 'docs/a b.md\n'
  st_case "non-ASCII path -> false"                  "docs_only=false" 0 'docs/caf\303\251.md\n'
  # bash's read silently DROPS a NUL, so 'docs/a<NUL>.md' reads back as the perfectly good
  # 'docs/a.md'. Only the explicit NUL check refuses it (the old arm joined two paths into
  # nonsense that failed for other reasons, and stayed green with the check deleted).
  st_case "NUL byte that would vanish into a valid path -> false" "docs_only=false" 0 'docs/a\0.md\n'
  st_case "directory path with slash -> false"       "docs_only=false" 0 'docs/\n'
  st_case "bare .planning (not a file inside) -> false" "docs_only=false" 0 '.planning\n'

  # Input handling: an unreadable source is VOID, never "true".
  local out rc
  out=$(bash "$SELF" --file "$ST_TMP/does-not-exist" 2>/dev/null); rc=$?
  ST_TOTAL=$((ST_TOTAL + 1))
  if [ "$out" = "docs_only=false" ] && [ "$rc" = 2 ]; then echo "  ok   missing input file -> VOID, false"; else echo "  FAIL missing input file (got '$out' rc=$rc)" >&2; ST_FAILS=$((ST_FAILS + 1)); fi
  out=$(bash "$SELF" --file "$ST_TMP" 2>/dev/null); rc=$?
  ST_TOTAL=$((ST_TOTAL + 1))
  if [ "$out" = "docs_only=false" ] && [ "$rc" = 2 ]; then echo "  ok   directory as input -> VOID, false"; else echo "  FAIL directory as input (got '$out' rc=$rc)" >&2; ST_FAILS=$((ST_FAILS + 1)); fi
  out=$(printf 'docs/a.md\n' | bash "$SELF" 2>/dev/null); rc=$?
  ST_TOTAL=$((ST_TOTAL + 1))
  if [ "$out" = "docs_only=true" ] && [ "$rc" = 0 ]; then echo "  ok   stdin path -> true"; else echo "  FAIL stdin path (got '$out' rc=$rc)" >&2; ST_FAILS=$((ST_FAILS + 1)); fi
  out=$(bash "$SELF" --bogus 2>/dev/null); rc=$?
  ST_TOTAL=$((ST_TOTAL + 1))
  if [ "$out" = "docs_only=false" ] && [ "$rc" = 2 ]; then echo "  ok   unknown argument -> VOID, false"; else echo "  FAIL unknown argument (got '$out' rc=$rc)" >&2; ST_FAILS=$((ST_FAILS + 1)); fi

  echo "== tree arms (read the real repository at $REPO_ROOT) =="
  git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1 || void "$REPO_ROOT is not a git work tree; the tree arms cannot run"

  # 1. Every tracked path in a src tree is NOT docs-only, individually.
  local p n_src=0 leaked=""
  while IFS= read -r p; do
    case "/$p/" in */src/*) ;; *) continue ;; esac
    n_src=$((n_src + 1))
    is_doc_path "$p" && leaked="$p"
  done < <(git -C "$REPO_ROOT" ls-files)
  st_check "every tracked src/** path classifies as code ($n_src paths)" "$([ "$n_src" -gt 0 ] && [ -z "$leaked" ]; echo $?)" "leaked='$leaked' n=$n_src"

  # 2. Every tracked non-.md file under docs/ is NOT docs-only.
  local n_docs_non_md=0 leaked2=""
  while IFS= read -r p; do
    case "$p" in docs/*.md) continue ;; docs/*) ;; *) continue ;; esac
    n_docs_non_md=$((n_docs_non_md + 1))
    is_doc_path "$p" && leaked2="$p"
  done < <(git -C "$REPO_ROOT" ls-files)
  st_check "every tracked non-.md file under docs/ classifies as code ($n_docs_non_md paths)" "$([ "$n_docs_non_md" -gt 0 ] && [ -z "$leaked2" ]; echo $?)" "leaked='$leaked2' n=$n_docs_non_md"

  # 3. The whole tracked tree is NOT docs-only (the allow-list is not accidentally everything).
  git -C "$REPO_ROOT" ls-files > "$ST_TMP/all-tracked.txt"
  out=$(bash "$SELF" --file "$ST_TMP/all-tracked.txt" 2>/dev/null); rc=$?
  st_check "the whole tracked tree classifies as code" "$([ "$out" = "docs_only=false" ] && [ "$rc" = 0 ]; echo $?)" "got '$out' rc=$rc"

  # 4. Every tracked Dockerfile's directory is a declared build-context directory.
  local df dir listed d missing=""
  while IFS= read -r df; do
    dir="$(dirname "$df")"
    listed=1
    for d in "${DOCKER_CONTEXT_DIRS[@]}"; do [ "$d" = "$dir" ] && listed=0; done
    [ "$listed" = 0 ] || missing="$missing $dir"
  done < <(git -C "$REPO_ROOT" ls-files | grep -E '(^|/)Dockerfile[^/]*$')
  st_check "every Dockerfile directory is in DOCKER_CONTEXT_DIRS" "$([ -z "$missing" ]; echo $?)" "missing:$missing"

  # 5. A README in each declared context directory classifies as code.
  local bad_ctx=""
  for d in "${DOCKER_CONTEXT_DIRS[@]}"; do
    is_doc_path "$d/README.md" && bad_ctx="$bad_ctx $d"
  done
  st_check "README.md in every build-context directory classifies as code" "$([ -z "$bad_ctx" ]; echo $?)" "docs-only for:$bad_ctx"

  # 6. Tests that read a doc by literal path: the read-site scanner first proves it can see one
  # (a control on the instrument), then every path it finds in the real tree must be code.
  local ctl
  ctl=$(printf '%s\n' 'core-java/src/test/X.java:7:        Path doc = repoRoot().resolve("docs/webhooks.md");' | scan_read_sites)
  st_check "read-site scanner control: sees a resolve(\"docs/...md\") line" "$([ "$ctl" = "docs/webhooks.md" ]; echo $?)" "got '$ctl'"
  ctl=$(printf '%s\n' 'core-java/src/test/X.java:7:        // see docs/webhooks.md for the vectors' | scan_read_sites)
  st_check "read-site scanner control: ignores a comment-only mention" "$([ -z "$ctl" ]; echo $?)" "got '$ctl'"

  local hits="" hit unclassified=""
  hits=$(git -C "$REPO_ROOT" grep -I -n -E '(docs/[A-Za-z0-9_./-]+\.md|\.planning/[A-Za-z0-9_./-]+)' -- \
    ':(glob)**/src/test/**' ':(glob)**/__tests__/**' ':(glob)**/e2e/**' ':(glob)**/*_test.go' \
    ':(glob)**/*.test.ts' ':(glob)**/*.test.tsx' ':(glob)**/*.spec.ts' ':(glob)**/*.test.mjs' 2>/dev/null | scan_read_sites | sort -u)
  local n_hits=0
  while IFS= read -r hit; do
    [ -n "$hit" ] || continue
    n_hits=$((n_hits + 1))
    is_doc_path "$hit" && unclassified="$unclassified $hit"
  done <<< "$hits"
  st_check "every doc a test reads by literal path classifies as code ($n_hits found)" "$([ -z "$unclassified" ]; echo $?)" "read by a test yet docs-only, add to EXCLUDED_EXACT:$unclassified"

  echo
  if [ "$ST_FAILS" -eq 0 ]; then
    echo "ci-docs-only selftest: $ST_TOTAL arms, all passed"
    return 0
  fi
  echo "ci-docs-only selftest: $ST_FAILS of $ST_TOTAL arms FAILED" >&2
  return 1
}

# --- main ----------------------------------------------------------------------------------

FILE=""
MODE=classify
while [ "$#" -gt 0 ]; do
  case "$1" in
    --selftest) MODE=selftest ;;
    --file)
      shift
      [ "$#" -gt 0 ] || void "--file needs a path (or '-' for stdin)"
      FILE="$1"
      ;;
    -h|--help) sed -n '2,/^# Exit codes/p' "$SELF" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) void "unknown argument '$1'" ;;
  esac
  shift
done

if [ "$MODE" = selftest ]; then
  selftest
  exit $?
fi

if [ -z "$FILE" ] || [ "$FILE" = "-" ]; then
  if [ -t 0 ]; then
    void "no --file given and stdin is a terminal; refusing to wait for input"
  fi
  TMP_IN=$(mktemp) || void "mktemp failed"
  trap 'rm -f "$TMP_IN"' EXIT
  cat > "$TMP_IN" || void "could not read stdin"
  FILE="$TMP_IN"
fi

classify_file "$FILE"
exit $?
