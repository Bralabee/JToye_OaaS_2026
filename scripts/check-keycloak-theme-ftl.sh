#!/usr/bin/env bash
# check-keycloak-theme-ftl.sh — the four FTL overrides of the jtoye login theme, and the realm
# settings that go with them, cannot rot or regress unnoticed (#847, #848).
#
# WHY THIS EXISTS
#
#   The jtoye theme was CSS-only on purpose: a forked template pins us to one Keycloak version's
#   markup. #848 (UXT-079, SW-MRC-12, SW-SAM-10) needed what CSS cannot do — a <main> landmark, a
#   <title> that names the page, no positive tabindex, the password rules and a privacy link on
#   the register page — so four templates are now overridden (template.ftl, login.ftl,
#   register.ftl, user-profile-commons.ftl). The recorded reason against forking was the SILENT rot at the next upgrade. This
#   gate makes that rot loud instead of forbidding the fork:
#
#     1. every override opens with `JTOYE-BASE-KEYCLOAK: <version>`, and that version must equal
#        the Keycloak image pinned in compose and in the k8s component. Bumping Keycloak without
#        re-deriving the overrides (recipe: infra/keycloak/README.md, "FTL overrides") fails here;
#     2. the accessibility properties the overrides exist for are still in them (a re-derivation
#        that drops one fails): <main>, a banner <header>, the page-naming <title>, no positive
#        tabindex, the register page's password rules and privacy link;
#     3. the password rules sentence in messages_en.properties is DERIVED from the realm
#        passwordPolicy (length, upperCase, lowerCase, digits, specialChars, notUsername) and
#        compared whole, in BOTH realm templates, so dropping or adding any rule cannot leave the
#        page telling people the old one. A policy term the sentence cannot express fails closed;
#     4. the user-initiated action-token lifespan is at least 30 minutes in both realm templates
#        and the customer realm's verify-email override (quoted or numeric JSON) is at least 12
#        hours (#847: the reset and verification links expired after 5 minutes);
#     5. every file of the theme that Keycloak needs is mounted by the k8s component (the
#        ConfigMap keys AND the Deployment volume items), so a template added under infra/ cannot
#        be forgotten on staging. Byte parity of the copies is scripts/check-k8s-copy-parity.sh.
#
# Static: reads files only. Text-level because the customer realm template is not JSON until
# rendered.
#
# USAGE
#   scripts/check-keycloak-theme-ftl.sh              # check this tree
#   THEME_FTL_ROOT=/path scripts/check-keycloak-theme-ftl.sh
#   scripts/check-keycloak-theme-ftl.sh --selftest   # planted trees, both directions
#
# Exit codes: 0 = clean, 1 = a defect (named), 2 = VOID (an input is missing or empty).

set -uo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
DEFAULT_ROOT="$(cd "$(dirname "$SCRIPT_PATH")/.." && pwd)"

THEME=infra/keycloak/themes/jtoye/login
FTL=(template.ftl login.ftl register.ftl user-profile-commons.ftl)
IMAGE_FILES=(docker-compose.full-stack.yml infra/docker-compose.yml infra/docker-compose.hostnet.yml k8s/components/keycloak/keycloak.yaml)
REALMS=(infra/keycloak/realm-export.template.json infra/keycloak/realm-export-customers.template.json)
CUSTOMER_REALM=infra/keycloak/realm-export-customers.template.json
MESSAGES=$THEME/messages/messages_en.properties
K8S_YAML=k8s/components/keycloak/keycloak.yaml
K8S_KUSTOMIZATION=k8s/components/keycloak/kustomization.yaml

MIN_USER_LIFESPAN=1800
MIN_VERIFY_LIFESPAN=43200

# expected_hint POLICY — the rules sentence the register page must show for a Keycloak passwordPolicy.
# Prints the sentence; returns 1 (and prints why on stderr) for a policy it cannot express, so an
# unknown or multi-count term fails closed instead of being silently left out of the sentence.
expected_hint() {
  local policy="$1" term name n len="" parts=() notuser="" out i last
  while IFS= read -r term; do
    [ -n "$term" ] || continue
    name=${term%%(*}; n=${term#*\(}; n=${n%\)}
    case "$name" in
      length) len="$n" ;;
      upperCase|lowerCase|digits|specialChars)
        [ "$n" = "1" ] || { echo "$name($n): the rules sentence says \"a\"/\"an\", so only a count of 1 can be expressed" >&2; return 1; } ;;
      notUsername) notuser=1 ;;
      *) echo "policy term '$term' has no wording in the rules sentence" >&2; return 1 ;;
    esac
  done < <(printf '%s' "$policy" | command grep -oE '[A-Za-z]+(\([0-9]+\))?' | command grep -vx 'and')
  [ -n "$len" ] || { echo "the policy has no length(N)" >&2; return 1; }
  case " $policy " in *upperCase*) parts+=("an uppercase letter") ;; esac
  case " $policy " in *lowerCase*) parts+=("a lowercase letter") ;; esac
  case " $policy " in *digits*) parts+=("a number") ;; esac
  case " $policy " in *specialChars*) parts+=("a special character") ;; esac
  out="At least $len characters"
  if [ "${#parts[@]}" -eq 1 ]; then out="$out, with ${parts[0]}"
  elif [ "${#parts[@]}" -gt 1 ]; then
    last=${parts[$((${#parts[@]} - 1))]}
    out="$out, with "
    for ((i = 0; i < ${#parts[@]} - 1; i++)); do
      [ "$i" -eq 0 ] || out="$out, "
      out="$out${parts[$i]}"
    done
    out="$out and $last"
  fi
  out="$out."
  [ -z "$notuser" ] || out="$out It cannot be the same as your email address."
  printf '%s' "$out"
}

check_tree() {
  local root="$1" defects=0 f v pinned img n pol want got
  d() { echo "DEFECT: $*"; defects=$((defects + 1)); }

  # ---- 1. the pinned Keycloak version ----------------------------------------------------------
  pinned=""
  for f in "${IMAGE_FILES[@]}"; do
    [ -r "$root/$f" ] || { echo "VOID: cannot read $f" >&2; return 2; }
    img=$(command grep -oE 'image:[[:space:]]*quay\.io/keycloak/keycloak:[0-9][0-9.]*' "$root/$f" | sed -E 's/.*://' | sort -u)
    n=$(printf '%s' "$img" | command grep -c . || true)
    if [ "$n" -ne 1 ]; then d "$f pins $n Keycloak versions ($(printf '%s' "$img" | tr '\n' ' ')), expected exactly 1"; continue; fi
    if [ -z "$pinned" ]; then pinned="$img"
    elif [ "$pinned" != "$img" ]; then d "$f pins Keycloak $img but another file pins $pinned"; fi
  done
  [ -n "$pinned" ] || { echo "VOID: no pinned Keycloak version found" >&2; return 2; }

  for f in "${FTL[@]}"; do
    [ -r "$root/$THEME/$f" ] || { echo "VOID: cannot read $THEME/$f" >&2; return 2; }
    v=$(command grep -oE 'JTOYE-BASE-KEYCLOAK:[[:space:]]*[0-9][0-9.]*' "$root/$THEME/$f" | sed -E 's/.*:[[:space:]]*//' | head -1)
    if [ -z "$v" ]; then d "$THEME/$f carries no JTOYE-BASE-KEYCLOAK marker"
    elif [ "$v" != "$pinned" ]; then
      d "$THEME/$f was derived from Keycloak $v but the pinned image is $pinned: re-derive it from the $pinned base (infra/keycloak/README.md, \"FTL overrides\")"
    fi
  done

  # ---- 2. the accessibility properties the overrides exist for --------------------------------
  for f in "${FTL[@]}"; do
    if command grep -qE 'tabindex="?[1-9]' "$root/$THEME/$f"; then
      d "$THEME/$f has a positive tabindex ($(command grep -noE 'tabindex="?[1-9][0-9]*"?' "$root/$THEME/$f" | head -3 | tr '\n' ' '))"
    fi
  done
  command grep -q '<main[ >]' "$root/$THEME/template.ftl" || d "$THEME/template.ftl has no <main> landmark"
  command grep -q '</main>' "$root/$THEME/template.ftl" || d "$THEME/template.ftl never closes its <main>"
  command grep -q '<header id="kc-header"' "$root/$THEME/template.ftl" || d "$THEME/template.ftl lost the banner <header id=\"kc-header\">"
  command grep -q '<title><#if jtPageHeadingText' "$root/$THEME/template.ftl" || d "$THEME/template.ftl <title> no longer names the page"
  command grep -q 'id="kc-password-policy"' "$root/$THEME/register.ftl" || d "$THEME/register.ftl no longer shows the password rules"
  command grep -q 'aria-describedby="kc-password-policy' "$root/$THEME/register.ftl" || d "$THEME/register.ftl: the password field is not described by the rules"
  command grep -q 'registerPrivacyNotice' "$root/$THEME/register.ftl" || d "$THEME/register.ftl no longer links the privacy notice"
  # #848: the register page's profile fields (email, first and last name) are rendered by
  # user-profile-commons.ftl; each control must say it is required and name its error.
  command grep -q 'aria-required="true"' "$root/$THEME/user-profile-commons.ftl" || d "$THEME/user-profile-commons.ftl no longer marks required profile fields aria-required"
  command grep -q 'aria-describedby="${jtIds' "$root/$THEME/user-profile-commons.ftl" || d "$THEME/user-profile-commons.ftl no longer ties a field's error to it with aria-describedby"
  [ "$(command grep -c '<@jtRequiredAndDescribed attribute=attribute/>' "$root/$THEME/user-profile-commons.ftl")" -ge 3 ] || d "$THEME/user-profile-commons.ftl: the input, textarea and select tags no longer all call jtRequiredAndDescribed"

  # ---- 3. the rules sentence is derived from the realm policy --------------------------------
  [ -r "$root/$MESSAGES" ] || { echo "VOID: cannot read $MESSAGES" >&2; return 2; }
  got=$(command grep -E '^passwordPolicyHint=' "$root/$MESSAGES" | head -1 | sed -E 's/^passwordPolicyHint=//')
  [ -n "$got" ] || d "$MESSAGES holds no passwordPolicyHint"
  command grep -qE '^registerPrivacyNotice=.*<a href="\{0\}">' "$root/$MESSAGES" || d "$MESSAGES holds no registerPrivacyNotice linking {0}"
  for f in "${REALMS[@]}"; do
    [ -r "$root/$f" ] || { echo "VOID: cannot read $f" >&2; return 2; }
    pol=$(command grep -E '^[[:space:]]*"passwordPolicy"' "$root/$f" | sed -E 's/^[^:]*:[[:space:]]*"(.*)"[[:space:]]*,?[[:space:]]*$/\1/')
    if [ -z "$pol" ]; then d "$f has no passwordPolicy"
    elif ! want=$(expected_hint "$pol" 2>&1); then d "$f passwordPolicy \"$pol\" cannot be expressed in the rules sentence: $want"
    elif [ -n "$got" ] && [ "$want" != "$got" ]; then d "$f passwordPolicy \"$pol\" requires the sentence \"$want\" but $MESSAGES tells people \"$got\""; fi

    # ---- 4. action-token lifespans --------------------------------------------------------------
    want=$(command grep -E '^[[:space:]]*"actionTokenGeneratedByUserLifespan"[[:space:]]*:' "$root/$f" | command grep -oE ':[[:space:]]*[0-9]+' | command grep -oE '[0-9]+' | head -1)
    if [ -z "$want" ]; then d "$f sets no actionTokenGeneratedByUserLifespan (Keycloak's default is 300 s)"
    elif [ "$want" -lt "$MIN_USER_LIFESPAN" ]; then d "$f actionTokenGeneratedByUserLifespan is $want s, below $MIN_USER_LIFESPAN s (#847)"; fi
  done
  want=$(command grep -E '"actionTokenGeneratedByUserLifespan\.verify-email"' "$root/$CUSTOMER_REALM" | command grep -oE ':[[:space:]]*"?[0-9]+"?' | command grep -oE '[0-9]+' | head -1)
  if [ -z "$want" ]; then d "$CUSTOMER_REALM sets no actionTokenGeneratedByUserLifespan.verify-email override"
  elif [ "$want" -lt "$MIN_VERIFY_LIFESPAN" ]; then d "$CUSTOMER_REALM verify-email link lifespan is $want s, below $MIN_VERIFY_LIFESPAN s (#847)"; fi

  # ---- 5. the k8s component mounts every FTL --------------------------------------------------
  [ -r "$root/$K8S_KUSTOMIZATION" ] || { echo "VOID: cannot read $K8S_KUSTOMIZATION" >&2; return 2; }
  for f in "${FTL[@]}"; do
    command grep -qE "^[[:space:]]*-[[:space:]]+theme/${f}[[:space:]]*\$" "$root/$K8S_KUSTOMIZATION" || d "$K8S_KUSTOMIZATION: the keycloak-theme ConfigMap does not list theme/$f"
    command grep -qE "^[[:space:]]*-[[:space:]]+key:[[:space:]]+${f}[[:space:]]*\$" "$root/$K8S_YAML" || d "$K8S_YAML: the keycloak-theme volume does not mount key $f"
  done

  if [ "$defects" -gt 0 ]; then
    echo "FAIL: $defects defect(s) in the jtoye Keycloak theme / realm contract (#847, #848)." >&2
    return 1
  fi
  echo "PASS: ${#FTL[@]} FTL overrides derived from the pinned Keycloak $pinned, accessible by construction, policy text agrees with the realms, lifespans >= ${MIN_USER_LIFESPAN}s/${MIN_VERIFY_LIFESPAN}s, mounted in k8s."
  return 0
}

selftest() {
  local t fails=0 out rc f
  t=$(umask 077 && mktemp -d "${TMPDIR:-/tmp}/jtoye-ftl-st.XXXXXXXX") || { echo "VOID: mktemp -d failed" >&2; return 2; }
  # shellcheck disable=SC2064
  trap "rm -rf '$t'" RETURN

  plant() { # $1 dir — a minimal clean tree
    local r="$1" x
    mkdir -p "$r/$THEME/messages" "$r/infra/keycloak" "$r/k8s/components/keycloak" "$r/infra"
    printf '<#-- JTOYE-BASE-KEYCLOAK: 26.8.1 -->\n<title><#if jtPageHeadingText?has_content>x</#if></title>\n<header id="kc-header">\n<main id="kc-main">\n</main>\n' > "$r/$THEME/template.ftl"
    printf '<#-- JTOYE-BASE-KEYCLOAK: 26.8.1 -->\n<input id="username">\n' > "$r/$THEME/login.ftl"
    printf '<#-- JTOYE-BASE-KEYCLOAK: 26.8.1 -->\n<input id="password" aria-describedby="kc-password-policy">\n<span id="kc-password-policy">x</span>\n${msg("registerPrivacyNotice")}\n' > "$r/$THEME/register.ftl"
    printf '<#-- JTOYE-BASE-KEYCLOAK: 26.8.1 -->\n<input aria-required="true">\naria-describedby="${jtIds?join(%s)}"\n<@jtRequiredAndDescribed attribute=attribute/>\n<@jtRequiredAndDescribed attribute=attribute/>\n<@jtRequiredAndDescribed attribute=attribute/>\n' "' '" > "$r/$THEME/user-profile-commons.ftl"
    printf 'passwordPolicyHint=At least 12 characters, with an uppercase letter, a lowercase letter, a number and a special character. It cannot be the same as your email address.\nregisterPrivacyNotice=See our <a href="{0}">privacy notice</a>.\n' > "$r/$MESSAGES"
    for x in "${REALMS[@]}"; do
      printf '{\n  "passwordPolicy" : "length(12) and digits(1) and upperCase(1) and lowerCase(1) and specialChars(1) and notUsername",\n  "actionTokenGeneratedByUserLifespan" : 3600,\n  "attributes" : {\n    "actionTokenGeneratedByUserLifespan.verify-email" : "86400"\n  }\n}\n' > "$r/$x"
    done
    for x in "${IMAGE_FILES[@]}"; do
      mkdir -p "$r/$(dirname "$x")"
      printf '    image: quay.io/keycloak/keycloak:26.8.1\n' > "$r/$x"
    done
    {
      printf 'configMapGenerator:\n  - name: keycloak-theme\n    files:\n'
      for x in "${FTL[@]}"; do printf '      - theme/%s\n' "$x"; done
    } > "$r/$K8S_KUSTOMIZATION"
    for x in "${FTL[@]}"; do printf '          - key: %s\n            path: %s\n' "$x" "$x"; done >> "$r/$K8S_YAML"
    printf '    image: quay.io/keycloak/keycloak:26.8.1\n' | cat - "$r/$K8S_YAML" > "$r/yaml.tmp" && mv "$r/yaml.tmp" "$r/$K8S_YAML"
  }
  arm() { # $1 name, $2 want rc, $3 want text ("" = none), $4 dir
    out=$(THEME_FTL_ROOT="$4" bash "$SCRIPT_PATH" 2>&1); rc=$?
    if [ "$rc" -ne "$2" ]; then echo "FAIL $1 rc=$rc (want $2): $(printf '%s' "$out" | head -c 400 | tr '\n' ' ')"; fails=$((fails + 1)); return; fi
    if [ -n "$3" ] && ! command grep -qF -- "$3" <<<"$out"; then echo "FAIL $1 output lacks '$3': $(printf '%s' "$out" | head -c 300 | tr '\n' ' ')"; fails=$((fails + 1)); return; fi
    echo "PASS $1 rc=$rc"
  }

  plant "$t/clean"; arm clean 0 "PASS:" "$t/clean"

  plant "$t/bump"
  for f in "${IMAGE_FILES[@]}"; do sed -i 's/26\.8\.1/26.9.0/' "$t/bump/$f"; done
  arm keycloak-bumped-ftl-stale 1 "re-derive it" "$t/bump"

  plant "$t/split"; sed -i 's/26\.8\.1/26.9.0/' "$t/split/${IMAGE_FILES[0]}"
  arm image-pins-disagree 1 "but another file pins" "$t/split"

  plant "$t/nomarker"; sed -i '/JTOYE-BASE-KEYCLOAK/d' "$t/nomarker/$THEME/login.ftl"
  arm marker-missing 1 "no JTOYE-BASE-KEYCLOAK marker" "$t/nomarker"

  plant "$t/tab"; printf '<input tabindex="3">\n' >> "$t/tab/$THEME/login.ftl"
  arm positive-tabindex 1 "positive tabindex" "$t/tab"

  plant "$t/tabneg"; printf '<ul tabindex="-1">\n' >> "$t/tabneg/$THEME/template.ftl"
  arm negative-tabindex-allowed 0 "PASS:" "$t/tabneg"

  plant "$t/nomain"; sed -i '/<main/d;/<\/main>/d' "$t/nomain/$THEME/template.ftl"
  arm no-main 1 "no <main> landmark" "$t/nomain"

  plant "$t/notitle"; sed -i 's/jtPageHeadingText/x/' "$t/notitle/$THEME/template.ftl"
  arm title-not-page 1 "no longer names the page" "$t/notitle"

  plant "$t/nopolicy"; sed -i '/kc-password-policy">/d' "$t/nopolicy/$THEME/register.ftl"
  arm rules-removed 1 "no longer shows the password rules" "$t/nopolicy"

  plant "$t/noreq"; sed -i 's/aria-required="true"//' "$t/noreq/$THEME/user-profile-commons.ftl"
  arm profile-fields-lose-required 1 "no longer marks required profile fields aria-required" "$t/noreq"

  plant "$t/nodesc"; sed -i 's/aria-describedby="${jtIds/x="${jtIds/' "$t/nodesc/$THEME/user-profile-commons.ftl"
  arm profile-fields-lose-error-link 1 "aria-describedby" "$t/nodesc"

  plant "$t/nocall"; sed -i '0,/<@jtRequiredAndDescribed attribute=attribute\/>/{//d}' "$t/nocall/$THEME/user-profile-commons.ftl"
  arm profile-field-type-drops-call 1 "no longer all call jtRequiredAndDescribed" "$t/nocall"

  plant "$t/noprivacy"; sed -i '/registerPrivacyNotice/d' "$t/noprivacy/$THEME/register.ftl"
  arm privacy-removed 1 "no longer links the privacy notice" "$t/noprivacy"

  plant "$t/polchg"; sed -i 's/length(12)/length(14)/' "$t/polchg/${REALMS[1]}"
  arm policy-length-changed-text-stale 1 "requires the sentence" "$t/polchg"

  plant "$t/nospecial"; sed -i 's/ and specialChars(1)//' "$t/nospecial/${REALMS[1]}"
  arm policy-drops-specialChars-text-stale 1 "At least 12 characters, with an uppercase letter, a lowercase letter and a number." "$t/nospecial"

  plant "$t/noupper"; sed -i 's/ and upperCase(1)//' "$t/noupper/${REALMS[0]}"
  arm policy-drops-upperCase-text-stale 1 "requires the sentence" "$t/noupper"

  plant "$t/nonotuser"; sed -i 's/ and notUsername//' "$t/nonotuser/${REALMS[1]}"
  arm policy-drops-notUsername-text-stale 1 "requires the sentence" "$t/nonotuser"

  plant "$t/addrule"; sed -i 's/ and notUsername/ and notUsername and notEmail/' "$t/addrule/${REALMS[1]}"
  arm policy-adds-unknown-term-fails-closed 1 "has no wording in the rules sentence" "$t/addrule"

  plant "$t/count2"; sed -i 's/digits(1)/digits(2)/' "$t/count2/${REALMS[1]}"
  arm policy-count-2-fails-closed 1 "only a count of 1 can be expressed" "$t/count2"

  plant "$t/hintedit"; sed -i 's/a special character/a symbol/' "$t/hintedit/$MESSAGES"
  arm hint-reworded-without-policy 1 "requires the sentence" "$t/hintedit"

  plant "$t/short"; sed -i 's/"actionTokenGeneratedByUserLifespan" : 3600/"actionTokenGeneratedByUserLifespan" : 300/' "$t/short/${REALMS[1]}"
  arm reset-lifespan-300 1 "below 1800" "$t/short"

  plant "$t/unset"; sed -i '/"actionTokenGeneratedByUserLifespan" :/d' "$t/unset/${REALMS[0]}"
  arm lifespan-unset 1 "sets no actionTokenGeneratedByUserLifespan" "$t/unset"

  plant "$t/verify"; sed -i 's/"86400"/"600"/' "$t/verify/${REALMS[1]}"
  arm verify-lifespan-short 1 "verify-email link lifespan" "$t/verify"

  plant "$t/vnum"; sed -i 's/"86400"/86400/' "$t/vnum/${REALMS[1]}"
  arm verify-lifespan-numeric-json-accepted 0 "PASS:" "$t/vnum"

  plant "$t/vnumshort"; sed -i 's/"86400"/600/' "$t/vnumshort/${REALMS[1]}"
  arm verify-lifespan-numeric-short 1 "verify-email link lifespan is 600" "$t/vnumshort"

  plant "$t/noverify"; sed -i '/verify-email/d' "$t/noverify/${REALMS[1]}"
  arm verify-override-missing 1 "no actionTokenGeneratedByUserLifespan.verify-email" "$t/noverify"

  plant "$t/nok8scm"; sed -i '/theme\/register.ftl/d' "$t/nok8scm/$K8S_KUSTOMIZATION"
  arm k8s-configmap-missing-ftl 1 "does not list theme/register.ftl" "$t/nok8scm"

  plant "$t/nok8svol"; sed -i '/key: login.ftl/,+1d' "$t/nok8svol/$K8S_YAML"
  arm k8s-volume-missing-ftl 1 "does not mount key login.ftl" "$t/nok8svol"

  plant "$t/void"; rm -f "$t/void/$THEME/register.ftl"
  arm missing-input-void 2 "VOID" "$t/void"

  [ "$fails" -eq 0 ]
}

case "${1:-}" in
  --selftest) selftest; exit $? ;;
  "") ;;
  *) echo "USAGE ERROR: unknown argument '$1' (only --selftest)" >&2; exit 2 ;;
esac

ROOT="${THEME_FTL_ROOT:-$DEFAULT_ROOT}"
echo "check-keycloak-theme-ftl  (root: $ROOT)"
check_tree "$ROOT"
exit $?
