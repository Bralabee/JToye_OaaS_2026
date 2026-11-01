#!/usr/bin/env bash
# usage: rp-count-1071.sh <overlay-dir> — per-Ingress Referrer-Policy line counts in a kustomize render
set -uo pipefail
out=$(kubectl kustomize "$1" 2>&1); rc=$?
if [ "$rc" -ne 0 ]; then echo "VOID kustomize rc=$rc"; exit 2; fi
total=$(command grep -c -F 'Referrer-Policy' <<< "$out")
awk -v o="$1" '
  function flush() { if (kind == "Ingress") { printf "%s Ingress %s Referrer-Policy=%d\n", o, name, n; ing++; if (name=="jtoye-ingress"||name=="jtoye-sse-ingress") app+=n } }
  /^---$/ { flush(); kind=""; name=""; n=0; inmeta=0; next }
  /^kind: / { kind=$2 }
  /^metadata:/ { inmeta=1; next }
  inmeta && /^  name: / && name=="" { name=$2 }
  /^[a-z]/ && !/^metadata:/ { inmeta=0 }
  /Referrer-Policy/ { n++ }
  END { flush(); printf "%s TOTAL_INGRESS_DOCS=%d APP_HOST_INGRESS_REFERRER_LINES=%d\n", o, ing, app }
' <<< "$out"
echo "$1 kustomize_rc=$rc whole_render_Referrer-Policy_lines=$total"
