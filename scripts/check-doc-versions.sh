#!/usr/bin/env bash
#
# check-doc-versions.sh — keep the documented dependency versions honest.
#
# CLAUDE.md and AGENTS.md hand-maintain a "Technology Stack" list of library
# versions. Nothing verified it, so it drifted with every dependabot merge:
# by 2026-07-29 there were 15 stale claims in CLAUDE.md and 17 in AGENTS.md,
# and the two files had drifted APART (AGENTS.md said Spring Boot 3.4.2 against
# a real 3.5.16). This gate compares every claim against the real build files.
#
# Usage:
#   scripts/check-doc-versions.sh            # check mode (CI)
#   scripts/check-doc-versions.sh --list     # print resolved actuals and exit
#
# Exit codes:
#   0  every claim in every doc matches the build files
#   1  at least one claim is stale  (the drift this gate exists to catch)
#   2  VOID — missing/unreadable input, an unresolvable actual version, or a
#      doc that yielded ZERO claims. "Found nothing" is never "found nothing
#      wrong": a doc whose stack section was deleted must fail, not pass.
#
# ALL occurrences of a claim are checked, not the first. That is deliberate —
# the drift that motivated this gate included "Spring Boot Gradle Plugin 3.4.2"
# sitting three lines below a correct "Spring Boot 3.5.16", which a first-match
# check reports as clean.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Docs carrying version claims. Add to this list, never inline a path below.
#
# STACK.md is the UPSTREAM SOURCE: AGENTS.md's stack section is a generated
# block (`<!-- GSD:stack-start source:codebase/STACK.md -->`), so gating only
# the generated files would be theatre — the next GSD regeneration would copy
# the stale versions straight back in and turn this gate red for a reason
# nobody would expect. Fix the source and the derived files together.
#
# .planning/PROJECT.md is deliberately NOT gated: line ~113 is an explicitly
# dated historical record ("Spring Boot 3.4.2 … Verified 2026-04-18 post-v2.1")
# that is CORRECT as history and must not be rewritten to match today's tree.
DOCS=(CLAUDE.md AGENTS.md .planning/codebase/STACK.md)

GRADLE="core-java/build.gradle.kts"
PKG="frontend/package.json"
GOMOD="edge-go/go.mod"

void() { echo "VOID: $*" >&2; exit 2; }

for f in "$GRADLE" "$PKG" "$GOMOD"; do
	[ -r "$ROOT/$f" ] || void "cannot read $f — cannot resolve actual versions"
done

# --- resolving the ACTUAL versions ------------------------------------------

# Gradle coordinate "group:artifact:version" -> version.
# Takes the segment after the LAST colon; taking the first digit-run instead
# would grab the "4j" inside resilience4j/bucket4j and report a false drift.
g() {
	command grep -oE "\"$1:[0-9][^\"]*\"" "$GRADLE" | head -1 | tr -d '"' | sed 's/.*://'
}

# npm dependency -> version, with any ^ or ~ range prefix stripped.
n() {
	command grep -oE "\"$1\": \"[^\"]*\"" "$PKG" | head -1 |
		sed -E 's/.*": "//; s/"$//; s/^[\^~]//'
}

boot_version() {
	command grep -oE 'id\("org.springframework.boot"\) version "[^"]*"' "$GRADLE" |
		head -1 | command grep -oE '"[0-9][0-9.]*"$' | tr -d '"'
}

gin_version() {
	command grep -oE 'github.com/gin-gonic/gin v[0-9][0-9.]*' "$GOMOD" |
		head -1 | sed 's/.* v//'
}

# --- the claim table ---------------------------------------------------------
#
# Each row: label | ERE matching the doc claim | actual version.
# The ERE MUST end at the version so the last whitespace-delimited token of a
# match is the claimed version (leading non-digits are stripped, so "v1.12.0"
# and "(2.49.2" both normalise correctly).
SPECS=(
	"Spring Boot|Spring Boot[ A-Za-z]*:? ?[0-9]+\.[0-9]+\.[0-9]+|$(boot_version)"
	"Testcontainers|Testcontainers [0-9]+\.[0-9]+\.[0-9]+|$(g 'org.testcontainers:testcontainers')"
	"MapStruct|MapStruct [0-9]+\.[0-9]+\.[0-9]+|$(g 'org.mapstruct:mapstruct')"
	"PostgreSQL JDBC|PostgreSQL JDBC( Driver)? [0-9]+\.[0-9]+\.[0-9]+|$(g 'org.postgresql:postgresql')"
	"AWS SDK v2|AWS SDK v2 (BOM |\()[0-9]+\.[0-9]+\.[0-9]+|$(g 'software.amazon.awssdk:bom')"
	"Resilience4j|Resilience4j( Spring Boot 3 Starter)? [0-9]+\.[0-9]+\.[0-9]+|$(g 'io.github.resilience4j:resilience4j-spring-boot3')"
	"Stripe Java SDK|Stripe Java SDK [0-9]+\.[0-9]+\.[0-9]+|$(g 'com.stripe:stripe-java')"
	"Bucket4j|Bucket4j( core)? [0-9]+\.[0-9]+\.[0-9]+|$(g 'com.bucket4j:bucket4j-core')"
	"OpenPDF|OpenPDF [0-9]+\.[0-9]+\.[0-9]+|$(g 'com.github.librepdf:openpdf')"
	"SpringDoc|SpringDoc OpenAPI [0-9]+\.[0-9]+\.[0-9]+|$(g 'org.springdoc:springdoc-openapi-starter-webmvc-ui')"
	"Spring StateMachine|Spring State Machine [0-9]+\.[0-9]+\.[0-9]+|$(g 'org.springframework.statemachine:spring-statemachine-starter')"
	"Next.js|Next\.js:? [0-9]+\.[0-9]+\.[0-9]+|$(n next)"
	"React Hook Form|React Hook Form [0-9]+\.[0-9]+\.[0-9]+|$(n react-hook-form)"
	"Next-Auth|Next-Auth [0-9]+\.[0-9]+\.[0-9]+-beta\.[0-9]+|$(n next-auth)"
	"TailwindCSS|TailwindCSS [0-9]+\.[0-9]+\.[0-9]+|$(n tailwindcss)"
	"Zod|Zod [0-9]+\.[0-9]+\.[0-9]+|$(n zod)"
	"Jest|Jest [0-9]+\.[0-9]+\.[0-9]+|$(n jest)"
	"Playwright|@playwright/test [0-9]+\.[0-9]+\.[0-9]+|$(n '@playwright/test')"
	"Axios|Axios [0-9]+\.[0-9]+\.[0-9]+|$(n axios)"
	"Framer Motion|(Framer Motion|framer-motion) [0-9]+\.[0-9]+\.[0-9]+|$(n framer-motion)"
	"Recharts|Recharts [0-9]+\.[0-9]+\.[0-9]+|$(n recharts)"
	"Gin|Gin v[0-9]+\.[0-9]+\.[0-9]+|$(gin_version)"
)

# Every actual must resolve, or the comparison is meaningless rather than clean.
for spec in "${SPECS[@]}"; do
	label="${spec%%|*}"
	actual="${spec##*|}"
	[ -n "$actual" ] || void "could not resolve the real version for '$label' from the build files"
done

if [ "${1:-}" = "--list" ]; then
	for spec in "${SPECS[@]}"; do
		printf '  %-22s %s\n' "${spec%%|*}" "${spec##*|}"
	done
	exit 0
fi

# --- compare -----------------------------------------------------------------

total_drift=0
total_checked=0

for doc in "${DOCS[@]}"; do
	[ -r "$ROOT/$doc" ] || void "cannot read $doc"
	echo "== $doc =="
	doc_checked=0
	doc_drift=0
	absent=""

	for spec in "${SPECS[@]}"; do
		label="${spec%%|*}"
		rest="${spec#*|}"
		claim_re="${rest%|*}"
		actual="${spec##*|}"

		# ALL matches, not head -1. Normalise each to a bare version.
		#
		# CASE-INSENSITIVE (-i), issue #346. It was case-SENSITIVE until 2026-07-30,
		# and that silently un-enforced a claim: .planning/codebase/STACK.md:110
		# writes `axios 1.19.0` lowercase while this table says `Axios`, so the gate
		# printed "(not claimed in this doc: … Axios)" and never checked it — while
		# enforcing the identical fact in CLAUDE.md and AGENTS.md, which capitalise
		# it. The unchecked claim was genuinely stale at 1.15.0.
		#
		# The "(not claimed…)" line is the gate honestly reporting a hole, and
		# nothing acted on it. That is the shape of every blind spot found this week.
		#
		# WIDENING WAS MEASURED, NOT ASSUMED — -i widens every row at once, so the
		# per-doc claim counts were compared before and after on an IDENTICAL tree:
		#     CLAUDE.md 28 -> 28 · AGENTS.md 28 -> 28 · STACK.md 25 -> 28
		#
		# Three new claims in STACK.md, not the one I predicted, and predicting wrong
		# is exactly why this is measured. All three were real and all three were
		# unenforced:
		#     :110  axios 1.19.0            lowercase label  (was stale at 1.15.0)
		#     :112  recharts 3.8.1          lowercase label  — STALE, actual 3.10.1
		#     :112  framer-motion 12.23.26  npm-name form    — STALE, actual 12.43.0
		# The recharts claim sat four lines below a CORRECT `Recharts 3.10.1` at :67,
		# so the doc contradicted itself and the gate could see only the right half.
		#
		# framer-motion needed more than -i: the doc writes the npm package name
		# while this table carries the display name, so its row now accepts both.
		# No other row matched anything it did not match before — a larger delta
		# would have meant a rule had started matching prose.
		mapfile -t claims < <(
			command grep -oiE "$claim_re" "$doc" 2>/dev/null |
				awk '{print $NF}' | sed -E 's/^[^0-9]*//'
		)

		if [ "${#claims[@]}" -eq 0 ]; then
			absent="$absent $label"
			continue
		fi

		for claimed in "${claims[@]}"; do
			doc_checked=$((doc_checked + 1))
			if [ "$claimed" != "$actual" ]; then
				printf '  DRIFT  %-22s doc=%-16s actual=%s\n' "$label" "$claimed" "$actual"
				doc_drift=$((doc_drift + 1))
			fi
		done
	done

	# A doc that matched nothing is VOID, not clean — this is the vacuity guard.
	[ "$doc_checked" -gt 0 ] || void "$doc yielded ZERO version claims — the stack section is missing or its format changed"

	[ -n "$absent" ] && echo "  (not claimed in this doc:$absent)"
	echo "  checked=$doc_checked drift=$doc_drift"
	total_checked=$((total_checked + doc_checked))
	total_drift=$((total_drift + doc_drift))
done

[ "$total_checked" -gt 0 ] || void "nothing was checked across ${#DOCS[@]} doc(s)"

if [ "$total_drift" -ne 0 ]; then
	echo
	echo "FAIL: $total_drift stale version claim(s) across ${#DOCS[@]} doc(s) ($total_checked checked)."
	echo "Update the docs to match the build files (or the build files, if the doc is the intent)."
	exit 1
fi

echo
echo "PASS: all $total_checked version claim(s) across ${#DOCS[@]} doc(s) match the build files."
