#!/usr/bin/env bash
#
# check-env-example-contract.sh — .env.example must offer a line for every variable
# scripts/verify-env.sh REQUIRES.
#
# WHY THIS EXISTS (QA run 20260902-134741, finding INT-16).
#
# `cp .env.example .env && bash scripts/verify-env.sh` is the repo's own documented
# first-run sequence (README § Quick Start). It could not pass: verify-env.sh has listed
# KC_ADMIN_PASSWORD in REQUIRED_VARS since the infra/ stack started guarding it with
# ${KC_ADMIN_PASSWORD:?}, and .env.example never carried the key. A fresh clone hit
# "Required variable KC_ADMIN_PASSWORD is unset or empty" with no line to fill in.
#
# It survived because CI cannot see it. .github/workflows/e2e-nightly.yml reads the
# credential list FROM `verify-env.sh --list-required` and APPENDS generated values after
# `cp .env.example .env` — so CI manufactures every required variable and the template's
# omission is invisible there. The two artefacts are only ever compared by a human.
#
# WHAT IT ASSERTS — set CONTAINMENT, in one direction, deliberately:
#
#   every name in `verify-env.sh --list-required`  =>  has an assignment line in .env.example
#
# The reverse direction is NOT asserted. .env.example legitimately carries many keys that
# are not credentials (ports, feature flags, URLs), so requiring equality would either red
# the gate or push non-secrets into a security preflight's required list — which is the
# deny-list-fails-open shape that #438/#439 were about.
#
# It checks PRESENCE OF A LINE, not the value. `CHANGE_ME` is the correct template value
# and verify-env.sh's own weak-value check is what rejects it at run time. The distinction
# is the point: "unset" means the reader has nothing to fill in, "weak" means they have not
# filled it in yet.
#
# Usage:
#   scripts/check-env-example-contract.sh                  # check mode (CI)
#   ENV_EXAMPLE=/path/to/copy scripts/check-env-example-contract.sh   # check a copy (break arm)
#
# Exit codes:
#   0  every required variable has a line in the template
#   1  at least one required variable has no line  (the drift this gate exists to catch)
#   2  VOID — missing/unreadable input, or an EMPTY required list. "Found nothing" is never
#      "found nothing wrong": if --list-required ever returns zero names (renamed flag,
#      moved array, a `set -e` abort) this gate must fail loudly, not pass vacuously.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENV_EXAMPLE="${ENV_EXAMPLE:-$ROOT/.env.example}"
VERIFY="$ROOT/scripts/verify-env.sh"

void() { echo "VOID: $*" >&2; exit 2; }

[ -r "$ENV_EXAMPLE" ] || void "cannot read $ENV_EXAMPLE"
[ -r "$VERIFY" ] || void "cannot read scripts/verify-env.sh — the required list has no source"

# Capture rc on the SAME line. A non-zero rc here is VOID, not "no required variables".
required_raw=$(bash "$VERIFY" --list-required 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || void "scripts/verify-env.sh --list-required exited $rc — the interface changed"

mapfile -t REQUIRED < <(printf '%s\n' "$required_raw" | command grep -E '^[A-Z][A-Z0-9_]*$')
[ "${#REQUIRED[@]}" -gt 0 ] || void "verify-env.sh --list-required yielded ZERO names — a comparison against an empty list cannot fail"

MISSING=()
for v in "${REQUIRED[@]}"; do
	# An assignment line for $v: optional leading whitespace, optional `export`, then NAME=.
	# A COMMENTED line (`# KC_ADMIN_PASSWORD=`) deliberately does NOT count — a reader who
	# copies the template gets no variable from it, which is the defect being gated.
	if command grep -qE "^[[:space:]]*(export[[:space:]]+)?${v}=" "$ENV_EXAMPLE"; then
		continue
	fi
	MISSING+=("$v")
done

echo "check-env-example-contract  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  template : ${ENV_EXAMPLE#"$ROOT"/}"
echo "  required : ${#REQUIRED[@]} name(s) from scripts/verify-env.sh --list-required"

if [ "${#MISSING[@]}" -gt 0 ]; then
	echo "FAIL: ${#MISSING[@]} required variable(s) have no line in ${ENV_EXAMPLE#"$ROOT"/}:" >&2
	for m in "${MISSING[@]}"; do echo "        $m" >&2; done
	echo "      A reader who runs 'cp .env.example .env' has nothing to fill in for these," >&2
	echo "      so the documented first-run sequence fails on the repo's own preflight." >&2
	echo "      Add each name with its value on the SAME line and no trailing comment —" >&2
	echo "      'VAR=  # text' resolves to the COMMENT TEXT as the value." >&2
	exit 1
fi

echo "  missing  : 0"
echo "PASS: all ${#REQUIRED[@]} required variable(s) have a line in the template."
