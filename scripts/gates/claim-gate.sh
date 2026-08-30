#!/usr/bin/env bash
#
# claim-gate.sh — assert that every value a document CLAIMS matches its source of truth.
#
# A generic engine for one recurring gate shape. It was extracted from four bespoke
# scripts in JToye_OaaS_2026 that had independently converged on the same design, and it
# exists so the NEXT project gets the gate by writing a rule table instead of a script.
#
# ── THE FAILURE IT PREVENTS ──────────────────────────────────────────────────────────
#
# Docs quote numbers: test counts, versions, schema revisions, dependency pins. The
# number goes stale, and nothing notices, because the thing that could notice never
# opens the document. Two measured instances from one repo:
#
#   README.md advertised "Total: 921 logical test invocations" and, in the very next
#   block, that those counts were "guarded by the docs-freshness CI gate ... which fails
#   the build if these numbers drift". The tree stood at 1851. The gate was green on
#   every commit for months. It had never opened README.md.
#
#   The project version sat at 2.1.0 through TWO releases (v2.1, v2.2) and into the v2.3
#   milestone: build.gradle.kts 2.1.0, frontend/package.json 2.1.0, README badge 2.2,
#   README heading v2.1.0, latest tag v2.2. Four different answers to one question,
#   because nothing compared the sites to each other.
#
# A doc that NAMES its guardian is not thereby guarded. This engine is the guardian.
#
# ── WHAT IT ENFORCES ─────────────────────────────────────────────────────────────────
#
#   M-1  Every declared rule must match at least once in its file. A rule that matches
#        NOTHING is a FAILURE, not a pass. This is the load-bearing rule: without it,
#        deleting the sentence silently satisfies the gate, and an assertion that cannot
#        fail is not evidence. (An already-zero grep is the classic vacuous check.)
#   M-2  Every value a rule captures must equal its source of truth.
#
# ── FAIL-CLOSED ──────────────────────────────────────────────────────────────────────
#
# Exit 2 (VOID), never 0, on: missing jq or a grep without -P; a manifest that is absent,
# empty, or has no rules; a declared source or consumer file that does not exist; a
# source value that is absent or the wrong shape; a grep -P error; or ZERO comparisons
# performed. "I could not check it" must never render as "I checked it and it was fine".
#
#   Exit 0 = every claim matches · 1 = a claim disagrees or vanished · 2 = VOID
#
# Drift (1) outranks nothing; VOID (2) is reported only if there is no outright drift, so
# a real disagreement is never masked by an unrelated unreadable file.
#
# ── MANIFEST FORMAT ──────────────────────────────────────────────────────────────────
#
# TAB-separated. Tabs, not spaces, because extraction patterns routinely contain spaces
# ("Backend \(Java\): \K[0-9]+") and any space-delimited format would split them.
# Blank lines and #-comments are ignored.
#
#   source <name> <kind> <file> <shape> [<extractor>]
#   rule   <source> <key> <file> <label> <pattern>
#
#   kind      json   read <file> as JSON; each rule's <key> is a top-level field
#             regex  read one value from <file> using <extractor>; rules pass key "-"
#             count  the value IS the number of LINES in <file> matching <extractor>;
#                    rules pass key "-". Shape is always int.
#
#             WHY count EXISTS (added 2026-08-25). Every number this repo's own docs
#             got wrong was a COUNT of something in the tree — arms in a selftest,
#             entries in an array, numbered checks in a hook — and `regex` cannot
#             express one: it reads the FIRST match and stops. So those numbers were
#             unbindable, and each was re-audited by eye every session until it drifted.
#             Measured the day this was added: "the five SYMLINKED shell/git files"
#             (six since 2026-08-18), "6-check local gate" (seven since PR #129),
#             "hermetic 27-arm proof" (the suite prints 55).
#
#             It counts matching LINES, not occurrences — two matches on one line count
#             once. Pick an anchored extractor (`^arm `) so the two cannot differ.
#             ZERO matches is a VOID, never the value 0: a count of zero is far more
#             often a wrong extractor than a real answer, and a gate that reports 0
#             confidently is the vacuous-check failure this engine exists to distrust.
#
#             A count whose truth needs the thing RUN (a suite whose arms sit in loops,
#             so call-sites != executed arms) is not a count source. Do not bind those;
#             quote them as a dated observation of a specific run instead.
#   shape     int    value must be a plain integer
#             semver value must be X.Y.Z
#             any    value must merely be non-empty
#   extractor A PCRE whose MATCH is the value. Use \K and lookahead so the match is the
#             bare value, not the surrounding text.
#   pattern   A PCRE whose MATCH is the claimed value, same \K convention.
#             OR "jq:<path>" to read the claim from a JSON consumer by path instead.
#
#             The jq: form is not a convenience — it is REQUIRED for structured files.
#             npm's package-lock.json records the package's own version at BOTH .version
#             and .packages[""].version, and also carries a "version" field for every
#             dependency. A PCRE like '"version":\s*"\K[0-9.]+' matches hundreds of
#             dependency versions and would report drift on all of them. Address the two
#             real sites by path: jq:.version and jq:.packages[""].version . A jq: rule
#             still obeys M-1 — a path that resolves to null or is absent FAILS, so
#             deleting the field cannot dodge the gate.
#
# Match backticks and other quoting-hostile characters with "." rather than escaping
# them — it keeps manifests readable and avoids a shell/PCRE double-escaping trap.
#
# ── USAGE ────────────────────────────────────────────────────────────────────────────
#
#   claim-gate.sh                          # uses ./claims.manifest, root = git toplevel
#   claim-gate.sh -m path/to.manifest      # explicit manifest
#   claim-gate.sh -r /path/to/repo         # explicit root that paths resolve against
#   claim-gate.sh --list                   # print the resolved rule table, check nothing
#
# ── PROVING IT WORKS ─────────────────────────────────────────────────────────────────
#
# Run gates/selftest.sh. It drives this engine against gates/fixtures/ in BOTH
# directions and fails if any deliberately-broken input is accepted. An engine observed
# only passing is exactly the thing this engine exists to distrust.
set -uo pipefail

VERSION="1.1.1"   # bump on any behaviour change; install.sh stamps it into vendored copies
#                  1.1.1 (2026-08-25): a pattern beginning with `-` was unwritable —
#                  grep parsed it as options, VOIDed, and the rule could never be used.
#                  1.1.0 (2026-08-25): kind=count. The bump matters for a reason the
#                  drift report showed on the very next push: with both sides still at
#                  1.0.0, --check-all printed "DRIFT (vendored v1.0.0, canonical v1.0.0)",
#                  which reads like a false alarm. The hash does the detecting; the
#                  version is what makes the report legible.

MANIFEST=""
ROOT=""
LIST_ONLY=0

while [ $# -gt 0 ]; do
	case "$1" in
		-m|--manifest) MANIFEST="${2:-}"; shift 2 ;;
		-r|--root)     ROOT="${2:-}"; shift 2 ;;
		--list)        LIST_ONLY=1; shift ;;
		--version)     printf 'claim-gate %s\n' "$VERSION"; exit 0 ;;
		-h|--help)     sed -n '2,80p' "$0"; exit 0 ;;
		*) printf 'VOID: unknown argument: %s\n' "$1" >&2; exit 2 ;;
	esac
done

VOID=0; FAIL=0; CHECKED=0; RULES_SEEN=0
void() { printf 'VOID: %s\n' "$1" >&2; VOID=1; }
fail() { printf 'FAIL: %s\n' "$1" >&2; FAIL=1; }

# ── preflight: a missing tool is VOID, never a silent skip ───────────────────────────
command -v jq >/dev/null 2>&1 || { void "jq is not installed"; exit 2; }
printf 'x' | grep -qP 'x' 2>/dev/null || { void "grep does not support -P (PCRE)"; exit 2; }

if [ -z "$ROOT" ]; then
	ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || ROOT=""
	[ -z "$ROOT" ] && ROOT="$PWD"
fi
[ -d "$ROOT" ] || { void "root is not a directory: $ROOT"; exit 2; }
cd "$ROOT" || { void "cannot cd to root: $ROOT"; exit 2; }

if [ -z "$MANIFEST" ]; then
	for cand in claims.manifest scripts/gates/claims.manifest gates/claims.manifest; do
		[ -f "$cand" ] && { MANIFEST="$cand"; break; }
	done
fi
[ -n "$MANIFEST" ] || { void "no manifest found (looked for claims.manifest, scripts/gates/claims.manifest, gates/claims.manifest)"; exit 2; }
[ -f "$MANIFEST" ] || { void "manifest does not exist: $MANIFEST"; exit 2; }

# ── pass 1: read source declarations and resolve each to a concrete value ────────────
# Parallel arrays rather than associative ones: bash 3 (macOS default) has no declare -A,
# and a portability engine that only runs on this machine defeats its own purpose.
SRC_NAMES=(); SRC_KINDS=(); SRC_FILES=(); SRC_SHAPES=(); SRC_EXTRACT=(); SRC_VALUES=()

src_index() { # <name> -> echoes index or empty
	local want="$1" i=0
	while [ "$i" -lt "${#SRC_NAMES[@]}" ]; do
		[ "${SRC_NAMES[$i]}" = "$want" ] && { printf '%s' "$i"; return 0; }
		i=$((i + 1))
	done
	return 1
}

shape_ok() { # <value> <shape>
	case "$2" in
		int)    case "$1" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac ;;
		semver) printf '%s' "$1" | grep -qP '^[0-9]+\.[0-9]+\.[0-9]+$' ;;
		any)    [ -n "$1" ] ;;
		*)      return 1 ;;
	esac
}

LINENO_M=0
while IFS= read -r raw || [ -n "$raw" ]; do
	LINENO_M=$((LINENO_M + 1))
	case "$raw" in ''|\#*) continue ;; esac
	IFS=$'\t' read -r kind f1 f2 f3 f4 f5 <<< "$raw"
	[ "$kind" = "source" ] || continue

	name="$f1"; skind="$f2"; sfile="$f3"; shape="${f4:-any}"; extract="${f5:-}"
	if [ -z "$name" ] || [ -z "$skind" ] || [ -z "$sfile" ]; then
		void "manifest:$LINENO_M source line is missing fields (need: source<TAB>name<TAB>kind<TAB>file<TAB>shape[<TAB>extractor])"
		continue
	fi
	if src_index "$name" >/dev/null 2>&1; then
		void "manifest:$LINENO_M source '$name' declared more than once"
		continue
	fi
	if [ ! -f "$sfile" ]; then
		void "source '$name': file does not exist: $sfile"
		SRC_NAMES+=("$name"); SRC_KINDS+=("$skind"); SRC_FILES+=("$sfile")
		SRC_SHAPES+=("$shape"); SRC_EXTRACT+=("$extract"); SRC_VALUES+=("__VOID__")
		continue
	fi

	value="__VOID__"
	case "$skind" in
		json)
			if ! jq -e . "$sfile" >/dev/null 2>&1; then
				void "source '$name': $sfile is not parseable JSON"
			else
				value="__JSON__"   # per-rule lookup; the file itself is the value carrier
			fi
			;;
		regex)
			if [ -z "$extract" ]; then
				void "source '$name': kind=regex needs an extractor pattern"
			else
				# `-e` and `--` are load-bearing, not style. Without them a pattern or
				# filename that begins with `-` is parsed as an option bundle: grep
				# exits 2, the branch below reports "grep -P errored", and the rule is
				# unwritable — it fails CLOSED, which is why this stayed hidden. A
				# leading `-` is ordinary in real claims (a negative number, a CLI flag
				# quoted in a doc, `--version` output).
				#
				# The rc is captured off grep itself rather than through `| head -1`.
				# `set -o pipefail` above happens to make the pipeline's rc grep's, so
				# the old form worked — but only by depending on an option set far away
				# from here. Relax pipefail and the check silently becomes dead code.
				v=$(grep -ohP -e "$extract" -- "$sfile" 2>/dev/null); grc=$?
				v=$(printf '%s\n' "$v" | head -1)
				if [ "$grc" -gt 1 ]; then
					void "source '$name': grep -P errored (rc=$grc) on extractor: $extract"
				elif [ -z "$v" ]; then
					void "source '$name': extractor matched nothing in $sfile"
				elif ! shape_ok "$v" "$shape"; then
					void "source '$name': value '$v' is not shape=$shape"
				else
					value="$v"
				fi
			fi
			;;
		count)
			if [ -z "$extract" ]; then
				void "source '$name': kind=count needs an extractor pattern"
			else
				# grep -c exits 1 on zero matches and >1 on a bad pattern. Both are
				# non-zero, so the rc is read BEFORE deciding — a bare `|| void` here
				# would report an uncompilable PCRE as "matched nothing", which is the
				# wrong reason and sends the reader to the wrong file.
				v=$(grep -cP -e "$extract" -- "$sfile" 2>/dev/null); grc=$?
				if [ "$grc" -gt 1 ]; then
					void "source '$name': grep -P errored (rc=$grc) on extractor: $extract"
				elif [ -z "$v" ] || [ "$v" = "0" ]; then
					void "source '$name': extractor matched no line in $sfile (a count of 0 is treated as a broken extractor, never as the value)"
				elif ! shape_ok "$v" int; then
					void "source '$name': count '$v' is not an integer"
				else
					value="$v"
				fi
			fi
			;;
		*) void "source '$name': unknown kind '$skind' (expected json, regex or count)" ;;
	esac

	SRC_NAMES+=("$name"); SRC_KINDS+=("$skind"); SRC_FILES+=("$sfile")
	SRC_SHAPES+=("$shape"); SRC_EXTRACT+=("$extract"); SRC_VALUES+=("$value")
done < "$MANIFEST"

[ "${#SRC_NAMES[@]}" -gt 0 ] || { void "manifest declares no sources"; exit 2; }

printf 'claim-gate %s  (%s)\n' "$VERSION" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '  manifest : %s\n' "$MANIFEST"
i=0
while [ "$i" -lt "${#SRC_NAMES[@]}" ]; do
	shown="${SRC_VALUES[$i]}"
	[ "$shown" = "__JSON__" ] && shown="(json: per-key lookup)"
	printf '  source   : %-12s %-6s %-28s %s\n' "${SRC_NAMES[$i]}" "${SRC_KINDS[$i]}" "${SRC_FILES[$i]}" "$shown"
	i=$((i + 1))
done

# ── pass 2: evaluate rules ───────────────────────────────────────────────────────────
DOCS_SEEN=""
LINENO_M=0
while IFS= read -r raw || [ -n "$raw" ]; do
	LINENO_M=$((LINENO_M + 1))
	case "$raw" in ''|\#*) continue ;; esac
	IFS=$'\t' read -r kind f1 f2 f3 f4 f5 <<< "$raw"
	[ "$kind" = "rule" ] || continue
	RULES_SEEN=$((RULES_SEEN + 1))

	sname="$f1"; key="$f2"; doc="$f3"; label="$f4"; pat="$f5"
	if [ -z "$sname" ] || [ -z "$doc" ] || [ -z "$pat" ]; then
		void "manifest:$LINENO_M rule is missing fields (need: rule<TAB>source<TAB>key<TAB>file<TAB>label<TAB>pattern)"
		continue
	fi
	if [ "$LIST_ONLY" = "1" ]; then
		printf '  rule     : %-12s %-26s %-30s %s\n' "$sname" "${key:--}" "$doc" "$label"
		continue
	fi

	idx=$(src_index "$sname" 2>/dev/null) || { void "manifest:$LINENO_M rule references undeclared source '$sname'"; continue; }
	case " $DOCS_SEEN " in *" $doc "*) ;; *) DOCS_SEEN="$DOCS_SEEN $doc" ;; esac

	# Resolve the expected value for THIS rule.
	expected="${SRC_VALUES[$idx]}"
	if [ "$expected" = "__VOID__" ]; then
		void "$doc [$label]: source '$sname' could not be resolved (see above)"
		continue
	fi
	if [ "$expected" = "__JSON__" ]; then
		if [ -z "$key" ] || [ "$key" = "-" ]; then
			void "$doc [$label]: source '$sname' is json, so the rule needs a key"
			continue
		fi
		expected=$(jq -r --arg k "$key" 'if has($k) then (.[$k]|tostring) else "__ABSENT__" end' "${SRC_FILES[$idx]}" 2>/dev/null)
		if [ "$expected" = "__ABSENT__" ] || [ -z "$expected" ]; then
			void "$doc [$label]: key '$key' absent from ${SRC_FILES[$idx]}"
			continue
		fi
		if ! shape_ok "$expected" "${SRC_SHAPES[$idx]}"; then
			void "$doc [$label]: ${SRC_FILES[$idx]} key '$key' = '$expected' is not shape=${SRC_SHAPES[$idx]}"
			continue
		fi
	fi

	if [ ! -f "$doc" ]; then
		void "$doc [$label]: declared in the manifest but the file does not exist"
		continue
	fi

	# Assign on its own line: `found=$(...)` then `rc=$?` — never after an echo, which
	# would report the ECHO's status and make every failure read as success.
	case "$pat" in
		jq:*)
			jqpath="${pat#jq:}"
			if ! jq -e . "$doc" >/dev/null 2>&1; then
				void "$doc [$label]: not parseable JSON, cannot evaluate '$jqpath'"
				continue
			fi
			# `// "__ABSENT__"` catches both a missing path and an explicit null, so a
			# deleted field is an M-1 failure rather than an empty-string pass.
			found=$(jq -r "($jqpath) // \"__ABSENT__\"" "$doc" 2>/dev/null)
			jrc=$?
			if [ "$jrc" -ne 0 ]; then
				void "$doc [$label]: jq errored (rc=$jrc) on path: $jqpath"
				continue
			fi
			[ "$found" = "__ABSENT__" ] && found=""
			;;
		*)
			# -e / -- for the same reason as the source extractor above: a claim whose
			# pattern legitimately starts with `-` must be expressible.
			found=$(grep -ohP -e "$pat" -- "$doc" 2>/dev/null)
			grc=$?
			if [ "$grc" -gt 1 ]; then
				void "$doc [$label]: grep -P errored (rc=$grc) on pattern: $pat"
				continue
			fi
			;;
	esac

	# M-1 — the claim must exist. A rule that matches nothing is a vacuous assertion.
	if [ -z "$found" ]; then
		fail "$doc [$label]: rule matched NOTHING — the claim was removed or reworded. Pattern: $pat"
		continue
	fi

	# M-2 — every captured value must equal the source of truth.
	while IFS= read -r got; do
		[ -z "$got" ] && continue
		CHECKED=$((CHECKED + 1))
		if [ "$got" != "$expected" ]; then
			fail "$doc [$label]: claims '$got', $sname says '$expected'"
		fi
	done <<< "$found"
done < "$MANIFEST"

if [ "$LIST_ONLY" = "1" ]; then
	printf '  %s rule(s) declared.\n' "$RULES_SEEN"
	exit 0
fi

DOC_COUNT=$(printf '%s' "$DOCS_SEEN" | wc -w | tr -d ' ')
printf '  rules    : %s across %s doc(s)\n' "$RULES_SEEN" "$DOC_COUNT"
printf '  claims   : %s compared\n' "$CHECKED"

[ "$RULES_SEEN" -eq 0 ] && void "manifest declares no rules — nothing would be checked"
[ "$CHECKED" -eq 0 ] && [ "$FAIL" -eq 0 ] && void "0 claims compared — the gate cannot have verified anything"

# Drift outranks VOID: never mask a real disagreement behind an unrelated unreadable file.
if [ "$FAIL" -ne 0 ]; then
	echo "FAILED: claim(s) disagree with their source of truth (see above). Fix the doc, or the source, never the gate." >&2
	exit 1
fi
if [ "$VOID" -ne 0 ]; then
	echo "VOID: the gate could not complete its checks (see above) — treat as unverified, not as a pass." >&2
	exit 2
fi
printf 'PASS: all %s claim(s) across %s doc(s) match their source of truth.\n' "$CHECKED" "$DOC_COUNT"
