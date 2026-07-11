#!/usr/bin/env bash
# check-no-plaintext-secrets.sh — regression gate for issue #100 (P2-9).
#
# Asserts that `kubectl kustomize` output for k8s/base and EVERY overlay:
#   1. builds successfully (each overlay owns its own namespace resource, so
#      each must be validated separately — base alone is not enough);
#   2. emits ZERO top-level `kind: Secret` objects. Committed manifests must
#      never ship K8s Secrets (base64 == plaintext): secrets reach the cluster
#      out-of-band via SealedSecrets (kind: SealedSecret is allowed — it is
#      ciphertext) or `kubectl create secret`. This blanket ban is what
#      prevents the original bug class: a placeholder/plaintext Secret
#      template sitting in the live resources list;
#   3. contains no REPLACE_WITH_* placeholder anywhere in the build output,
#      except the known non-secret deployment.timestamp annotation the
#      overlays stamp at deploy time.
#
# Requires: kubectl (client-side only — no cluster access needed).
# Exit codes: 0 = clean, 1 = violation found, 2 = build/tooling failure.
#
# Usage: ./k8s/scripts/check-no-plaintext-secrets.sh
#   (run from anywhere; paths resolve relative to the repo root)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
K8S_DIR="$REPO_ROOT/k8s"

if ! command -v kubectl > /dev/null; then
    echo "ERROR: kubectl not on PATH (client-side 'kubectl kustomize' is required)." >&2
    exit 2
fi

# Discover every kustomization root under k8s/ (base + all env overlays).
# Layout today: k8s/base, k8s/staging, k8s/production. New overlays are
# picked up automatically.
mapfile -t TARGETS < <(find "$K8S_DIR" -maxdepth 2 -name 'kustomization.yaml' -printf '%h\n' | sort)

if [[ ${#TARGETS[@]} -eq 0 ]]; then
    echo "ERROR: no kustomization.yaml found under $K8S_DIR" >&2
    exit 2
fi

OUT="$(mktemp)"
ERR="$(mktemp)"
trap 'rm -f "$OUT" "$ERR"' EXIT

FAIL=0

for dir in "${TARGETS[@]}"; do
    rel="${dir#"$REPO_ROOT"/}"
    dir_fail=0

    if ! kubectl kustomize "$dir" > "$OUT" 2> "$ERR"; then
        echo "FAIL [$rel]: kubectl kustomize build failed:" >&2
        cat "$ERR" >&2
        exit 2
    fi

    resources=$(grep -c '^kind:' "$OUT" || true)

    # Gate 2: no top-level Secret objects at all. `^kind:` anchors to column 0,
    # i.e. document top-level fields only. SealedSecret does not match.
    if grep -q '^kind: Secret[[:space:]]*$' "$OUT"; then
        echo "FAIL [$rel]: kustomize output contains committed 'kind: Secret' object(s):" >&2
        # kustomize output orders top-level keys apiVersion, kind, metadata —
        # so `kind: Secret` precedes the doc's 2-space-indented metadata.name.
        awk '
            /^---[[:space:]]*$/           { in_secret = 0 }
            /^kind: Secret[[:space:]]*$/  { in_secret = 1 }
            /^  name: / && in_secret      { print "  - Secret: " $2; in_secret = 0 }
        ' "$OUT" | sort -u >&2
        dir_fail=1
    fi

    # Gate 3 (defence-in-depth): placeholder material anywhere in the build,
    # excluding the known non-secret deployment.timestamp annotation.
    if grep -n 'REPLACE_WITH' "$OUT" | grep -v 'deployment\.timestamp' > /dev/null; then
        echo "FAIL [$rel]: kustomize output contains REPLACE_WITH placeholder outside deployment.timestamp:" >&2
        grep -n 'REPLACE_WITH' "$OUT" | grep -v 'deployment\.timestamp' >&2
        dir_fail=1
    fi

    if [[ $dir_fail -eq 0 ]]; then
        echo "OK   [$rel]: build succeeded, $resources resources, 0 plaintext Secrets"
    else
        FAIL=1
    fi
done

if [[ $FAIL -ne 0 ]]; then
    echo >&2
    echo "Plaintext Secret material must never be a live kustomize resource (issue #100)." >&2
    echo "Ship secrets via SealedSecrets (docs/runbooks/sealed-secrets.md) or create them" >&2
    echo "out-of-band per k8s/QUICK_START.md Step 1." >&2
    exit 1
fi

echo "All kustomize builds are free of plaintext Secret material."
