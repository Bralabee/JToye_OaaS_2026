#!/usr/bin/env bash
#
# Gate: no runtime may override strict scoping back to false (D-06, Phase 37-04, threat T-37-07).
#
# D-06 (owner ruling, rated costly): ungranted means no access. jtoye.access.strict-scoping
# defaults to true in application.yml, and compose and the k8s overlays must not override it back
# to false. Setting it false makes every ungranted staff login an implicit tenant-wide
# GROUP_ADMIN again (UXT-004) and silently re-escalates everyone the flip scoped, so reverting it
# needs an owner ruling, never a config edit. This gate makes "never a config edit" a property CI
# re-proves on every PR instead of a sentence in a comment.
#
# WHAT IT READS
#
#   Required (each must exist, mention strict scoping, and state a TRUE value):
#     core-java/src/main/resources/application.yml   strict-scoping: ACCESS_STRICT_SCOPING placeholder, default true
#     docker-compose.full-stack.yml                  ACCESS_STRICT_SCOPING placeholder, default true
#     .env.example                                   ACCESS_STRICT_SCOPING=true
#     k8s/base/configmap.yaml                        access.strict-scoping: "true"
#     k8s/goldens/staging.yaml, production.yaml      the rendered configmap value, "true"
#   Scanned (any value found must be true; absence is fine):
#     core-java/src/main/resources/application*.{yml,yaml,properties}
#     docker-compose*.{yml,yaml} at the root and under infra/, infra/.env.example
#     every *.yml / *.yaml / *.env / *.properties under k8s/base, k8s/local, k8s/staging,
#     k8s/production and k8s/goldens (the four overlay directories must exist)
#
# WHAT COUNTS AS A VALUE
#
#   Every non-comment line naming the key in any spelling Spring would bind it from
#   (strict-scoping, ACCESS_STRICT_SCOPING, JTOYE_ACCESS_STRICT_SCOPING, JTOYE_ACCESS_STRICTSCOPING)
#   is classified:
#     - a placeholder default   ACCESS_STRICT_SCOPING:true}  /  ACCESS_STRICT_SCOPING:-true}
#     - a direct assignment     KEY: value  /  KEY=value  (quotes stripped, trailing " # comment" dropped)
#     - a k8s env entry         - name: ACCESS_STRICT_SCOPING  followed by  value: ...
#   A value passes only if it is one Spring reads as TRUE: true, on, yes or 1 (any case). This is an
#   allow-list on purpose: Spring also reads off, no and 0 as false, and an empty value is not
#   true either, so a deny-list of the word "false" would fail OPEN on every other spelling.
#   References (configMapKeyRef "key: access.strict-scoping", "- name:" with valueFrom, and a
#   placeholder with no default) carry no value and are not judged.
#   Full-line comments are skipped, so a comment that explains the history does not trip the gate.
#
# Exit codes:
#   0  every value found is true, and every required file states a true value
#   1  a non-true value somewhere, or a required file that mentions the key but states no true
#      value (the default was dropped) — each named with file:line
#   2  VOID — the root, a required file or an overlay directory is missing, or a required file
#      never mentions strict scoping at all. "Found nothing" is never "clean".
#   A run with both a violation and a VOID exits 1: a definite violation is reported as one.
#
# HAZARDS DODGED (both recorded failure modes in this repo):
#   - `cmd | grep -q X` under pipefail inverts on match (SIGPIPE -> 141). Nothing here pipes into
#     grep; lines are classified with bash's own [[ =~ ]].
#   - A gate that forbids a value must name it. This script lives in scripts/, which it does not
#     scan, so its own text cannot trip it.
#
# Usage: scripts/check-strict-scoping-default.sh [ROOT]   (ROOT defaults to the repository root,
#        so a copied tree can be checked for the break arms)

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${1:-$(cd -- "$SCRIPT_DIR/.." && pwd)}"

shopt -s nullglob globstar

fails=0
voids=0
checked_files=0
checked_values=0

fail() { echo "FAIL: $*"; fails=$((fails + 1)); }
void() { echo "VOID: $*"; voids=$((voids + 1)); }

echo "Strict-scoping default gate (D-06)"
echo "  root: $ROOT"

if [ ! -d "$ROOT" ]; then
  echo "VOID: root directory not found: $ROOT"
  exit 2
fi

KEY_RE='(strict-scoping|strict_scoping|STRICT_SCOPING|STRICTSCOPING|strictScoping)'
PLACEHOLDER_RE='\$\{ACCESS_STRICT_SCOPING:-?([^}]*)\}'
DIRECT_RE="${KEY_RE}[\"']?[[:space:]]*[:=][[:space:]]*(.*)$"
ENVNAME_RE="name:[[:space:]]*[\"']?(JTOYE_)?ACCESS_STRICT_?SCOPING[\"']?[[:space:]]*$"
VALUE_RE='^[[:space:]]*value:[[:space:]]*(.*)$'
NEXT_ITEM_RE='^[[:space:]]*-[[:space:]]'

# Normalise a raw value: drop a trailing " # comment", trim, strip one layer of quotes.
normalise() {
  local v="$1"
  v="${v%%[[:space:]]#*}"
  v="${v#"${v%%[![:space:]]*}"}"
  v="${v%"${v##*[![:space:]]}"}"
  v="${v#[\"\']}"
  v="${v%[\"\']}"
  printf '%s' "$v"
}

is_true() {
  local v
  v="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  case "$v" in
    true|on|yes|1) return 0 ;;
    *) return 1 ;;
  esac
}

# judge FILE LINE_NO VALUE KIND -> records a pass (sets file_true) or a FAIL
judge() {
  local rel="$1" n="$2" v="$3" kind="$4"
  checked_values=$((checked_values + 1))
  if is_true "$v"; then
    file_true=$((file_true + 1))
  else
    fail "$rel:$n $kind value '$v' is not true (D-06: strict scoping must not be overridden to false)"
  fi
}

# scan FILE REQUIRED(0|1)
scan() {
  local f="$1" required="$2"
  local rel="${f#"$ROOT"/}"
  if [ ! -f "$f" ]; then
    if [ "$required" = 1 ]; then void "required file missing: $rel"; fi
    return 0
  fi
  checked_files=$((checked_files + 1))
  local -a lines
  mapfile -t lines < "$f"
  local mentions=0 i n line rest val j look
  file_true=0
  for i in "${!lines[@]}"; do
    line="${lines[$i]}"
    n=$((i + 1))
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ $KEY_RE ]] || continue
    mentions=$((mentions + 1))

    if [[ "$line" =~ $ENVNAME_RE ]]; then
      # A k8s env entry: judge a literal "value:" in the same list item; valueFrom is a reference.
      for ((j = i + 1; j < ${#lines[@]} && j <= i + 4; j++)); do
        look="${lines[$j]}"
        [[ "$look" =~ $NEXT_ITEM_RE ]] && break
        if [[ "$look" =~ $VALUE_RE ]]; then
          judge "$rel" "$((j + 1))" "$(normalise "${BASH_REMATCH[1]}")" "env"
          break
        fi
      done
      continue
    fi

    if [[ "$line" =~ $PLACEHOLDER_RE ]]; then
      judge "$rel" "$n" "$(normalise "${BASH_REMATCH[1]}")" "placeholder-default"
      continue
    fi

    if [[ "$line" =~ $DIRECT_RE ]]; then
      rest="${BASH_REMATCH[2]}"
      val="$(normalise "$rest")"
      if [[ "$val" == '${'* ]]; then
        continue   # a placeholder with no default: a reference, the default lives in application.yml
      fi
      judge "$rel" "$n" "$val" "assignment"
      continue
    fi
    # Anything else that names the key (configMapKeyRef "key: access.strict-scoping", a prose line
    # in a non-comment context) carries no value to judge.
  done

  if [ "$required" = 1 ]; then
    if [ "$mentions" -eq 0 ]; then
      void "$rel never mentions strict scoping — the key was deleted or the file is not the one this gate must read"
    elif [ "$file_true" -eq 0 ]; then
      fail "$rel mentions strict scoping but states no true value (the true default was dropped)"
    fi
  fi
  if [ "$mentions" -gt 0 ] || [ "$required" = 1 ]; then
    echo "  read: $rel (mentions=$mentions, true values=$file_true)"
  fi
}

REQUIRED=(
  "core-java/src/main/resources/application.yml"
  "docker-compose.full-stack.yml"
  ".env.example"
  "k8s/base/configmap.yaml"
  "k8s/goldens/staging.yaml"
  "k8s/goldens/production.yaml"
)
for r in "${REQUIRED[@]}"; do
  scan "$ROOT/$r" 1
done

for d in k8s/base k8s/local k8s/staging k8s/production; do
  [ -d "$ROOT/$d" ] || void "overlay directory missing: $d"
done

is_required() {
  local rel="$1" r
  for r in "${REQUIRED[@]}"; do [ "$r" = "$rel" ] && return 0; done
  return 1
}

OPTIONAL=(
  "$ROOT"/core-java/src/main/resources/application*.yml
  "$ROOT"/core-java/src/main/resources/application*.yaml
  "$ROOT"/core-java/src/main/resources/application*.properties
  "$ROOT"/docker-compose*.yml
  "$ROOT"/docker-compose*.yaml
  "$ROOT"/infra/docker-compose*.yml
  "$ROOT"/infra/docker-compose*.yaml
  "$ROOT"/infra/.env.example
)
for d in k8s/base k8s/local k8s/staging k8s/production k8s/goldens; do
  OPTIONAL+=("$ROOT/$d"/**/*.yml "$ROOT/$d"/**/*.yaml "$ROOT/$d"/**/*.env "$ROOT/$d"/**/*.properties)
done
for f in "${OPTIONAL[@]}"; do
  is_required "${f#"$ROOT"/}" && continue
  scan "$f" 0
done

echo "  files read: $checked_files, values judged: $checked_values, FAIL: $fails, VOID: $voids"
if [ "$checked_values" -eq 0 ] && [ "$voids" -eq 0 ]; then
  echo "VOID: no strict-scoping value was judged anywhere — refusing to report clean over an empty scan"
  exit 2
fi
if [ "$fails" -gt 0 ]; then
  echo "RESULT: FAIL — strict scoping is overridden or its true default is missing (D-06 needs an owner ruling to revert)"
  exit 1
fi
if [ "$voids" -gt 0 ]; then
  echo "RESULT: VOID — a file this gate must read is missing or never mentions strict scoping"
  exit 2
fi
echo "RESULT: PASS — every runtime says strict scoping is ON (D-06)"
exit 0
