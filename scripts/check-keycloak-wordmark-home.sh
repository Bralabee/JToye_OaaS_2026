#!/usr/bin/env bash
# check-keycloak-wordmark-home.sh — does the J'Toye wordmark on every Keycloak page
# link home, in BOTH realm templates, and does every render site substitute its href?
#
# WHY THIS EXISTS (owner ruling 2026-11-02)
#
#   A tester who registered on the storefront landed on Keycloak's "Email verification"
#   page and had no way back to the site: the header wordmark was a <span>, not a link.
#   The `jtoye` login theme carries no FTL override for the header (three FTL overrides exist for
#   accessibility, #848, none of them touches it; see
#   infra/keycloak/themes/jtoye/login/theme.properties), so the header text comes from
#   each realm's displayNameHtml. The fix makes that value an anchor to the
#   environment's public app origin:
#
#     <a href="${PUBLIC_APP_ORIGIN}" style="...">J'Toye</a>
#
#   Keycloak's kcSanitize keeps the tag, the href and the style (adding rel="nofollow");
#   measured on quay.io/keycloak/keycloak:26.8.1 on the login, register, reset and
#   email-verification pages, recorded in
#   .planning/quick/261102-7lu-fix-keycloak-wordmark-links-home/evidence/.
#
#   Two ways it regresses silently, both checked here:
#     1. a template edit turns the anchor back into a span, or drops the placeholder
#        for a literal host (every environment would then link to one place);
#     2. a render site's envsubst allow-list omits PUBLIC_APP_ORIGIN. envsubst leaves an
#        unlisted name untouched, so the realm would ship href="${PUBLIC_APP_ORIGIN}" — a
#        relative link to a path that does not exist — and nothing would fail.
#   The staging render (infra/keycloak/staging-realm.jq) is checked by the assertion
#   suite of scripts/staging-realm-import.sh, which refuses a realm whose header does
#   not hold href="https://<staging host>", on the rendered file and on the live read-back.
#
# WHAT IT ASSERTS
#   templates   infra/keycloak/realm-export.template.json and
#               infra/keycloak/realm-export-customers.template.json each hold exactly ONE
#               displayNameHtml line, whose value starts <a href=\"${PUBLIC_APP_ORIGIN}\",
#               ends </a>, and carries no second href.
#   renderers   in docker-compose.full-stack.yml, infra/docker-compose.yml and
#               infra/docker-compose.hostnet.yml, every envsubst invocation that renders a
#               realm-export template lists $$PUBLIC_APP_ORIGIN, and the file passes a
#               PUBLIC_APP_ORIGIN environment entry to the render service.
#
# Text-level on purpose: the customer template is not JSON until rendered.
# Static: reads files only, no stack, no network.
#
# USAGE
#   scripts/check-keycloak-wordmark-home.sh              # check this tree
#   WORDMARK_ROOT=/path scripts/check-keycloak-wordmark-home.sh   # check another tree
#   scripts/check-keycloak-wordmark-home.sh --selftest   # planted trees, both directions
#
# Exit codes: 0 = every template and render site links home, 1 = a defect (named),
#             2 = VOID (an input is missing, or a file holds no render site to check).

set -uo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
DEFAULT_ROOT="$(cd "$(dirname "$SCRIPT_PATH")/.." && pwd)"

TEMPLATES=(infra/keycloak/realm-export.template.json infra/keycloak/realm-export-customers.template.json)
RENDER_SITES=(docker-compose.full-stack.yml infra/docker-compose.yml infra/docker-compose.hostnet.yml)
WANT_PREFIX='<a href=\"${PUBLIC_APP_ORIGIN}\"'

check_tree() {
  local root="$1" defects=0 t f lines value n sites missing
  for t in "${TEMPLATES[@]}"; do
    f="$root/$t"
    [ -r "$f" ] || { echo "VOID: cannot read $t" >&2; return 2; }
    lines=$(command grep -E '^[[:space:]]*"displayNameHtml"[[:space:]]*:' "$f" || true)
    n=$(printf '%s' "$lines" | command grep -c . || true)
    if [ "$n" -ne 1 ]; then
      echo "DEFECT: $t holds $n displayNameHtml line(s), expected exactly 1"
      defects=$((defects + 1)); continue
    fi
    value=$(printf '%s\n' "$lines" | sed -E 's/^[[:space:]]*"displayNameHtml"[[:space:]]*:[[:space:]]*"(.*)"[[:space:]]*,?[[:space:]]*$/\1/')
    case "$value" in
      "$WANT_PREFIX"*'</a>') ;;
      *) echo "DEFECT: $t displayNameHtml does not link home: $value"
         echo "        expected a value starting $WANT_PREFIX and ending </a>"
         defects=$((defects + 1)); continue ;;
    esac
    n=$(printf '%s' "$value" | command grep -o 'href=' | command grep -c . || true)
    if [ "$n" -ne 1 ]; then
      echo "DEFECT: $t displayNameHtml carries $n href attributes, expected 1"
      defects=$((defects + 1)); continue
    fi
    echo "  ok    $t: $value"
  done

  for t in "${RENDER_SITES[@]}"; do
    f="$root/$t"
    [ -r "$f" ] || { echo "VOID: cannot read $t" >&2; return 2; }
    # One record per envsubst line that renders a realm template: the envsubst line
    # and the line after it (where the < template redirect sits) are joined.
    sites=$(awk '
      /envsubst \x27/ { pending = $0; next }
      pending != "" { if ((pending " " $0) ~ /realm-export[a-z-]*\.template\.json/) print pending; pending = "" }
    ' "$f")
    n=$(printf '%s' "$sites" | command grep -c . || true)
    if [ "$n" -eq 0 ]; then
      echo "VOID: $t holds no envsubst line rendering a realm template; nothing to check (moved or renamed?)" >&2
      return 2
    fi
    missing=$(printf '%s\n' "$sites" | command grep -vF '$$PUBLIC_APP_ORIGIN' || true)
    if [ -n "$missing" ]; then
      echo "DEFECT: $t: $(printf '%s\n' "$missing" | command grep -c .) of $n realm envsubst line(s) omit \$\$PUBLIC_APP_ORIGIN; the realm would ship a literal placeholder href:"
      printf '%s\n' "$missing" | sed 's/^[[:space:]]*/        /'
      defects=$((defects + 1))
    fi
    if ! command grep -qE '^[[:space:]]+PUBLIC_APP_ORIGIN:[[:space:]]' "$f"; then
      echo "DEFECT: $t passes no PUBLIC_APP_ORIGIN environment entry to its render service"
      defects=$((defects + 1))
    fi
    [ "$defects" -gt 0 ] || echo "  ok    $t: $n realm envsubst line(s) list \$\$PUBLIC_APP_ORIGIN"
  done

  if [ "$defects" -gt 0 ]; then
    echo "FAIL: $defects wordmark-home defect(s); the Keycloak header would not link home (owner ruling 2026-11-02)." >&2
    return 1
  fi
  echo "PASS: both realm templates link the wordmark home and every compose render site substitutes the origin."
  return 0
}

selftest() {
  local t fails=0 out rc
  t=$(umask 077 && mktemp -d "${TMPDIR:-/tmp}/jtoye-wordmark-st.XXXXXXXX") || { echo "VOID: mktemp -d failed" >&2; return 2; }
  # shellcheck disable=SC2064
  trap "rm -rf '$t'" RETURN

  plant() { # $1 dir — a minimal clean tree
    mkdir -p "$1/infra/keycloak"
    local f
    for f in "${TEMPLATES[@]}"; do
      printf '{\n  "realm" : "x",\n  "displayNameHtml" : "<a href=\\"${PUBLIC_APP_ORIGIN}\\" style=\\"color:#3A0B0D\\">J'"'"'Toye</a>",\n  "loginTheme" : "jtoye"\n}\n' > "$1/$f"
    done
    for f in "${RENDER_SITES[@]}"; do
      printf "services:\n  render:\n    environment:\n      PUBLIC_APP_ORIGIN: x\n    entrypoint:\n      - |\n        envsubst '\$\$KEYCLOAK_CLIENT_SECRET \$\$PUBLIC_APP_ORIGIN' \\\\\n          < /keycloak/realm-export.template.json > /keycloak/realm-export.json\n" > "$1/$f"
    done
  }
  arm() { # $1 name, $2 want rc, $3 want text ("" = none), $4 dir
    out=$(WORDMARK_ROOT="$4" bash "$SCRIPT_PATH" 2>&1); rc=$?
    if [ "$rc" -ne "$2" ]; then echo "FAIL $1 rc=$rc (want $2): $(printf '%s' "$out" | head -c 300 | tr '\n' ' ')"; fails=$((fails + 1)); return; fi
    if [ -n "$3" ] && ! command grep -qF -- "$3" <<<"$out"; then echo "FAIL $1 output lacks '$3'"; fails=$((fails + 1)); return; fi
    echo "PASS $1 rc=$rc"
  }

  plant "$t/clean";   arm clean 0 "PASS:" "$t/clean"

  plant "$t/span"
  sed -i 's#<a href=\\"${PUBLIC_APP_ORIGIN}\\" style=\\"color:\#3A0B0D\\">J'"'"'Toye</a>#<span style=\\"color:\#3A0B0D\\">J'"'"'Toye</span>#' "$t/span/${TEMPLATES[1]}"
  arm span-not-anchor 1 "does not link home" "$t/span"

  plant "$t/literal"
  sed -i 's#\${PUBLIC_APP_ORIGIN}#https://app.example#' "$t/literal/${TEMPLATES[0]}"
  arm literal-host 1 "does not link home" "$t/literal"

  plant "$t/twohref"
  sed -i 's#J'"'"'Toye</a>#<a href=\\"x\\">J'"'"'Toye</a>#' "$t/twohref/${TEMPLATES[0]}"
  arm second-href 1 "href attributes" "$t/twohref"

  plant "$t/nolist"
  sed -i 's# \$\$PUBLIC_APP_ORIGIN##' "$t/nolist/${RENDER_SITES[2]}"
  arm envsubst-omits-origin 1 "omit" "$t/nolist"

  plant "$t/noenv"
  sed -i '/PUBLIC_APP_ORIGIN: x/d' "$t/noenv/${RENDER_SITES[1]}"
  arm no-env-entry 1 "no PUBLIC_APP_ORIGIN environment entry" "$t/noenv"

  plant "$t/nosite"
  printf 'services: {}\n' > "$t/nosite/${RENDER_SITES[0]}"
  arm no-render-site-void 2 "VOID" "$t/nosite"

  plant "$t/missing"
  rm -f "$t/missing/${TEMPLATES[1]}"
  arm missing-template-void 2 "VOID" "$t/missing"

  [ "$fails" -eq 0 ]
}

case "${1:-}" in
  --selftest) selftest; exit $? ;;
  "") ;;
  *) echo "USAGE ERROR: unknown argument '$1' (only --selftest)" >&2; exit 2 ;;
esac

ROOT="${WORDMARK_ROOT:-$DEFAULT_ROOT}"
echo "check-keycloak-wordmark-home  (root: $ROOT)"
check_tree "$ROOT"
exit $?
