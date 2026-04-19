#!/usr/bin/env bash
# seal-secrets.sh
#
# Batch converter: plaintext K8s Secret manifests → SealedSecret manifests.
#
# Reads a multi-document YAML file containing one or more `kind: Secret`
# objects, seals each with `kubeseal`, and writes one
# `<name>.sealed.yaml` per secret into the output directory.
#
# Requires:
#   * kubeseal CLI (install: brew install kubeseal  OR  see
#     https://github.com/bitnami-labs/sealed-secrets/releases)
#   * Either a cert file (--cert) OR live cluster access (kubeseal
#     --fetch-cert from the default kubecontext).
#   * yq (v4+) to split multi-document YAML. Install: brew install yq
#
# Usage:
#   ./k8s/scripts/seal-secrets.sh \
#     --cert /tmp/sealed-secrets-pub.pem \
#     --namespace jtoye-production \
#     --input  /tmp/plaintext-secrets.yaml \
#     --output k8s/production/sealed-secrets/
#
# See docs/runbooks/sealed-secrets.md §3b for the full workflow.

set -euo pipefail

CERT=""
NAMESPACE=""
INPUT=""
OUTPUT=""

usage() {
    cat <<EOF
Usage: $0 --cert <pub.pem> --namespace <ns> --input <plain.yaml> --output <dir>

Options:
  --cert       Path to the sealed-secrets controller public key
               (fetch with: kubeseal --fetch-cert > pub.pem).
  --namespace  Target namespace for the resulting SealedSecrets.
  --input      Path to a plaintext YAML file containing one or more
               'kind: Secret' documents (separated by '---').
  --output     Directory where <name>.sealed.yaml files are written.
               Created if it does not exist.
  -h, --help   Show this help.

Example:
  $0 --cert /tmp/pub.pem --namespace jtoye-production \\
     --input  /tmp/plain.yaml \\
     --output k8s/production/sealed-secrets/
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --cert)      CERT="$2";      shift 2;;
        --namespace) NAMESPACE="$2"; shift 2;;
        --input)     INPUT="$2";     shift 2;;
        --output)    OUTPUT="$2";    shift 2;;
        -h|--help)   usage;          exit 0;;
        *)
            echo "ERROR: unknown arg: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

# Validate args
if [[ -z "$CERT" || -z "$NAMESPACE" || -z "$INPUT" || -z "$OUTPUT" ]]; then
    echo "ERROR: all of --cert, --namespace, --input, --output are required." >&2
    usage >&2
    exit 2
fi

# Validate dependencies
for dep in kubeseal yq; do
    if ! command -v "$dep" > /dev/null; then
        echo "ERROR: required dependency '$dep' is not on PATH." >&2
        echo "  kubeseal: https://github.com/bitnami-labs/sealed-secrets/releases" >&2
        echo "  yq:       https://github.com/mikefarah/yq/releases" >&2
        exit 3
    fi
done

# Validate input paths
if [[ ! -r "$CERT" ]]; then
    echo "ERROR: cert file not readable: $CERT" >&2
    exit 4
fi
if [[ ! -r "$INPUT" ]]; then
    echo "ERROR: input file not readable: $INPUT" >&2
    exit 4
fi

mkdir -p "$OUTPUT"

# Count documents
DOC_COUNT=$(yq 'di' "$INPUT" 2>/dev/null | tail -n 1)
if [[ -z "$DOC_COUNT" ]]; then
    DOC_COUNT=0
fi
TOTAL_DOCS=$((DOC_COUNT + 1))

echo "[seal-secrets] Input:     $INPUT ($TOTAL_DOCS documents)"
echo "[seal-secrets] Namespace: $NAMESPACE"
echo "[seal-secrets] Cert:      $CERT"
echo "[seal-secrets] Output:    $OUTPUT"
echo

SEALED_COUNT=0
SKIPPED_COUNT=0

# Iterate over each document in the multi-doc YAML
for i in $(seq 0 "$DOC_COUNT"); do
    KIND=$(yq "select(di == $i) | .kind" "$INPUT")
    if [[ "$KIND" != "Secret" ]]; then
        echo "[seal-secrets] Skipping doc #$i (kind=$KIND — not a Secret)"
        SKIPPED_COUNT=$((SKIPPED_COUNT + 1))
        continue
    fi

    NAME=$(yq "select(di == $i) | .metadata.name" "$INPUT")
    if [[ -z "$NAME" || "$NAME" == "null" ]]; then
        echo "ERROR: doc #$i has kind=Secret but no metadata.name — refusing to seal." >&2
        exit 5
    fi

    OUT_FILE="$OUTPUT/${NAME}.sealed.yaml"

    # Extract the single document + override namespace
    TMP_IN=$(mktemp)
    trap 'rm -f "$TMP_IN"' EXIT
    yq "select(di == $i) | .metadata.namespace = \"$NAMESPACE\"" "$INPUT" > "$TMP_IN"

    echo "[seal-secrets] Sealing $NAME → $OUT_FILE"
    kubeseal --format=yaml --cert="$CERT" < "$TMP_IN" > "$OUT_FILE"

    # Sanity-check the output
    OUT_KIND=$(yq '.kind' "$OUT_FILE")
    if [[ "$OUT_KIND" != "SealedSecret" ]]; then
        echo "ERROR: kubeseal produced unexpected kind=$OUT_KIND for $NAME" >&2
        exit 6
    fi

    SEALED_COUNT=$((SEALED_COUNT + 1))
    rm -f "$TMP_IN"
    trap - EXIT
done

echo
echo "[seal-secrets] DONE: sealed=$SEALED_COUNT skipped=$SKIPPED_COUNT"
echo "[seal-secrets] REMEMBER: shred the plaintext input now:"
echo "    shred -u '$INPUT'"
