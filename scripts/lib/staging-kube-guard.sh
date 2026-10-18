#!/usr/bin/env bash
# staging-kube-guard.sh — RED skeleton (plan 29-03 Task 3). Usage check only;
# the employer-context, identity-file, server-url and kubelogin checks are not
# written yet, so the bootstrap selftest must report them FAIL.

staging_kube_guard() {
  local ctx="${1:-}"
  if [ -z "$ctx" ]; then
    echo "USAGE ERROR: --context is required" >&2
    return 2
  fi
  return 0
}
