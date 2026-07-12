#!/usr/bin/env bash
#
# openapi-gate.sh — issue #97 AC3: CI fails on unreviewed OpenAPI changes.
#
# Compares the reviewed snapshot (docs/api/openapi-snapshot.json) against the
# spec regenerated from source (core-java/build-local/openapi/openapi-current.json,
# written by `./gradlew :core-java:generateOpenApiSpec`):
#
#   byte-identical              -> pass
#   breaking change  (oasdiff)  -> FAIL with the breaking-change report
#   non-breaking drift          -> FAIL telling the author to regenerate
#
# Intentional-change workflow: when you MEAN to change the API surface, run
#   ./gradlew :core-java:updateOpenApiSnapshot
# and commit the docs/api/openapi-snapshot.json diff in the SAME PR — the
# snapshot diff is what reviewers approve; this gate then goes green.
#
# Local usage (exactly what CI runs):
#   ./gradlew :core-java:generateOpenApiSpec
#   OASDIFF=/path/to/oasdiff scripts/openapi-gate.sh   # or have oasdiff on PATH
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SNAPSHOT="$ROOT/docs/api/openapi-snapshot.json"
CURRENT="$ROOT/core-java/build-local/openapi/openapi-current.json"
OASDIFF="${OASDIFF:-oasdiff}"

if [ ! -f "$SNAPSHOT" ]; then
	echo "ERROR: reviewed snapshot missing at $SNAPSHOT" >&2
	echo "Generate it with: ./gradlew :core-java:updateOpenApiSnapshot (and commit it)" >&2
	exit 1
fi
if [ ! -f "$CURRENT" ]; then
	echo "ERROR: regenerated spec missing at $CURRENT" >&2
	echo "Generate it with: ./gradlew :core-java:generateOpenApiSpec" >&2
	exit 1
fi

if cmp -s "$SNAPSHOT" "$CURRENT"; then
	echo "OK: OpenAPI spec matches the reviewed snapshot (docs/api/openapi-snapshot.json)."
	exit 0
fi

echo "OpenAPI drift: /v3/api-docs no longer matches docs/api/openapi-snapshot.json."
echo

# Classify the drift. `oasdiff breaking --fail-on ERR` exits non-zero when
# definite breaking changes exist (removed paths, narrowed types, new required
# params, ...). Potentially-breaking (WARN) and additive changes fall through
# to the non-breaking branch below — which also fails, just with a gentler
# instruction, so NO drift ever slips through unreviewed.
if ! "$OASDIFF" breaking "$SNAPSHOT" "$CURRENT" --fail-on ERR; then
	echo
	echo "ERROR: BREAKING OpenAPI change relative to the reviewed snapshot (report above)." >&2
	echo "If this break is intentional and reviewed, regenerate the snapshot in THIS PR:" >&2
	echo "    ./gradlew :core-java:updateOpenApiSnapshot && git add docs/api/openapi-snapshot.json" >&2
	exit 1
fi

echo "Non-breaking drift — changelog:"
"$OASDIFF" changelog "$SNAPSHOT" "$CURRENT" || true
echo
echo "ERROR: the OpenAPI spec changed (non-breaking drift). Regenerate the snapshot in this PR" >&2
echo "so reviewers see the contract change:" >&2
echo "    ./gradlew :core-java:updateOpenApiSnapshot && git add docs/api/openapi-snapshot.json" >&2
exit 1
