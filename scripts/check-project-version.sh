#!/usr/bin/env bash
#
# check-project-version.sh — one project version, asserted across every site that claims it.
#
# WHY THIS EXISTS. The project version sat at 2.1.0 through the v2.1 AND v2.2 releases and into
# the v2.3 milestone, because nothing compared the sites to each other. Measured 2026-07-30 before
# the bump: build.gradle.kts 2.1.0, frontend/package.json 2.1.0, README badge 2.2, README
# "Current Version: v2.1.0", latest git tag v2.2, active milestone v2.3 — four different answers
# to one question. This is the same defect class as check-doc-metrics.sh (a doc claim nothing
# read), one layer over.
#
# SOURCE OF TRUTH: build.gradle.kts `version = "X.Y.Z"`. Everything else is asserted against it.
#
# WHAT IT ENFORCES. For each declared site: the version it claims must equal the Gradle version
#   V-1  the site must EXIST and its pattern must match at least once — a rule that matches
#        nothing FAILS, so deleting the badge or the heading cannot silently dodge the gate.
#   V-2  every captured version must equal the Gradle version.
#   V-3  frontend/package-lock.json must agree with frontend/package.json at BOTH of the two
#        places npm records it (top level and packages[""]), or `npm ci` reinstalls a lie.
#
# DELIBERATELY OUT OF SCOPE, each for a stated reason — not an oversight:
#   * k8s/base/*-deployment.yaml image tags. An inert placeholder: both deploy jobs run
#     `kustomize edit set image ...:${{ github.sha }}` and a premortem guard FAILS the job if the
#     static default survives to `kubectl apply`. `type=semver` only fires on a v* tag push, so no
#     version-numbered image is ever pushed. Tracking it here would force a goldens regeneration
#     to point at a tag that does not exist in the registry.
#   * mcp-server/package.json. `@jtoye/mcp-server` is a separate private lineage at 0.x that has
#     never been 2.x; forcing it to the platform version would assert a history it does not have.
#   * edge-go `// @version 1.0` in main.go — that is the OpenAPI *spec* version, not the product's.
#
# FAIL-CLOSED. A missing file, an unreadable Gradle version, a non-semver value, a grep -P error,
# or zero comparisons performed => exit 2 (VOID), never 0. "Could not check" is never "fine".
#
# Usage:
#   scripts/check-project-version.sh
#
# Falsification (run BOTH directions before trusting this gate):
#   sed -i 's/"version": "2.3.0"/"version": "2.2.0"/' frontend/package.json
#   scripts/check-project-version.sh; echo $?     # expect 1
#   git checkout frontend/package.json
#   scripts/check-project-version.sh; echo $?     # expect 0
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VOID=0; FAIL=0; CHECKED=0
void() { printf 'VOID: %s\n' "$1" >&2; VOID=1; }
fail() { printf 'FAIL: %s\n' "$1" >&2; FAIL=1; }

printf 'x' | grep -qP 'x' 2>/dev/null || { void "grep does not support -P (PCRE)"; exit 2; }
command -v jq >/dev/null 2>&1 || { void "jq is not installed — cannot read package.json/lock"; exit 2; }

# ---- source of truth -------------------------------------------------------
[ -f build.gradle.kts ] || { void "build.gradle.kts is missing — no source of truth"; exit 2; }
EXPECTED=$(grep -oP '^\s*version\s*=\s*"\K[0-9]+\.[0-9]+\.[0-9]+(?=")' build.gradle.kts | head -1)
if [ -z "$EXPECTED" ]; then
	void "could not read a semver 'version = \"X.Y.Z\"' from build.gradle.kts"
	exit 2
fi
case "$EXPECTED" in
	[0-9]*.[0-9]*.[0-9]*) ;;
	*) void "Gradle version '$EXPECTED' is not X.Y.Z"; exit 2 ;;
esac

printf 'check-project-version  (%s)\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '  source of truth : build.gradle.kts = %s\n' "$EXPECTED"

# ---- RULES: <file>|<label>|<PCRE whose MATCH is the claimed version> -------
# README's badge escapes the semver dots for shields.io only in the label text, not the version,
# so a plain X.Y.Z match works; the `--dev` suffix is matched-around, not captured.
RULES=$(cat <<'RULES_EOF'
frontend/package.json|npm package version|^\s*"version":\s*"\K[0-9]+\.[0-9]+\.[0-9]+(?=")
README.md|version badge|badge/version-\K[0-9]+\.[0-9]+\.[0-9]+(?=(--dev)?-)
README.md|Current Version heading|### Current Version: \K[0-9]+\.[0-9]+\.[0-9]+
README.md|artifact-version sentence|artifact version \(`build\.gradle\.kts`, `frontend/package\.json`\) is \*\*\K[0-9]+\.[0-9]+\.[0-9]+
RULES_EOF
)
[ -n "$RULES" ] || { void "the rule table is empty — nothing would be checked"; exit 2; }

while IFS='|' read -r file label pat; do
	[ -z "${file:-}" ] && continue
	if [ ! -f "$file" ]; then void "$file: declared in the rule table but missing"; continue; fi

	found=$(grep -ohP "$pat" "$file" 2>/dev/null)
	grc=$?
	if [ "$grc" -gt 1 ]; then void "$file [$label]: grep -P errored (rc=$grc)"; continue; fi

	# V-1: the claim must exist.
	if [ -z "$found" ]; then
		fail "$file [$label]: rule matched NOTHING — the claim was removed or reworded. Pattern: $pat"
		continue
	fi

	# V-2: every captured version must equal the Gradle version.
	while read -r got; do
		[ -z "$got" ] && continue
		CHECKED=$((CHECKED + 1))
		[ "$got" != "$EXPECTED" ] && fail "$file [$label]: claims $got, build.gradle.kts says $EXPECTED"
	done <<< "$found"
done <<< "$RULES"

# ---- V-3: the npm lockfile records the version TWICE; both must agree ------
LOCK=frontend/package-lock.json
PKG=frontend/package.json
if [ ! -f "$LOCK" ]; then
	void "$LOCK is missing — npm ci would not be reproducible"
else
	jq -e . "$LOCK" >/dev/null 2>&1 || void "$LOCK is not parseable JSON"
	pkgv=$(jq -r '.version // "__ABSENT__"' "$PKG" 2>/dev/null)
	for path in '.version' '.packages[""].version'; do
		lockv=$(jq -r "$path // \"__ABSENT__\"" "$LOCK" 2>/dev/null)
		if [ "$lockv" = "__ABSENT__" ] || [ -z "$lockv" ]; then
			void "$LOCK $path: absent — cannot compare"
			continue
		fi
		CHECKED=$((CHECKED + 1))
		[ "$lockv" != "$pkgv" ] && fail "$LOCK $path = $lockv but $PKG = $pkgv (npm ci would install a different version)"
		[ "$lockv" != "$EXPECTED" ] && fail "$LOCK $path = $lockv but build.gradle.kts = $EXPECTED"
	done
fi

printf '  claims compared : %s\n' "$CHECKED"

# A run that compared nothing is not a pass, however green it looks.
[ "$CHECKED" -eq 0 ] && void "0 claims compared — the gate cannot have verified anything"

if [ "$FAIL" -ne 0 ]; then
	echo "FAIL: project version disagrees across sites (see above). build.gradle.kts is the source of truth." >&2
	exit 1
fi
if [ "$VOID" -ne 0 ]; then
	echo "VOID: the gate could not complete its checks — treat as unverified, not as a pass." >&2
	exit 2
fi
printf 'PASS: all %s project-version claim(s) agree with build.gradle.kts (%s).\n' "$CHECKED" "$EXPECTED"
