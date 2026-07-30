#!/usr/bin/env bash
# check-claims.sh — thin entrypoint: run the vendored claim-gate engine on this repo.
#
# The engine is VENDORED (scripts/gates/claim-gate.sh) rather than sourced from
# ~/dotfiles, because CI runs in a fresh runner that has only this repository. Its
# canonical home is ~/dotfiles/gates/; refresh with ~/dotfiles/gates/install.sh.
#
# Rules live in scripts/gates/claims.manifest — add a row, not a script.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec bash "$ROOT/scripts/gates/claim-gate.sh" -r "$ROOT" -m "$ROOT/scripts/gates/claims.manifest" "$@"
