#!/usr/bin/env bash
#
# Gate: every retention period this platform PUBLISHES as Automated has a real enforcement
# site in code or config, holding the declared value, with a consumer that actually reads it.
#
# docs/retention-manifest.json is the source of truth for a legally operative document (the
# published retention schedule, LGL-01 / D-07). A published period that quietly diverges from
# what the system does is not a documentation defect — it is a false statement in a document a
# regulator, a consumer or a procurement questionnaire will rely on.
#
# The motivating case is measured, not hypothetical. `cleanup.orphaned-image-days: 7` sat in
# application.yml with a plausible value and ZERO consumers; two-arm verified on 2026-08-16
# against an identically shaped live control:
#
#     orphaned-image-days  java consumers:  rc=1  out=''
#     stale-draft-hours    java consumers:  rc=0  out='.../ScheduledCleanupService.java:32'
#
# Same flags, same globs, same directory — so the empty result was a fact about the code, not
# about the search. Publishing it as a 7-day automated deletion would have been a lie. THAT is
# why this gate asserts a CONSUMER and not merely a config key: a gate that only checked
# "the key exists at the declared value" would have passed on it.
#
# WHAT IT ASSERTS
#
#   1. Existence  — every Automated row's enforced_by.path exists AND holds the declared key,
#                   anchored (not a loose substring), AND its consumer file exists and contains
#                   the token proving it reads the key.
#   2. Value      — the number at that site, CONVERTED into the manifest's published unit,
#                   equals the manifest's number. 259200000 ms must compare equal to 72 hours.
#                   The claim-gate engine has no unit transform (kind is only json|regex, shape
#                   only int|semver), so this script owns the conversion; without it the two
#                   values would silently never compare and the gate would be decorative.
#   3. Coherence  — every flat top-level claim key equals its row's period_value. The flat keys
#                   exist so the claim-gate engine's top-level has($k) lookup can read them
#                   (it errors on a bare array: "Cannot check whether array has a string key").
#                   Two copies of one number in one file is itself a drift surface, so it is
#                   gated rather than trusted.
#   4. Negative   — R-6: no prune, purge or delete path exists against notification_suppression
#                   or marketing_opt_in. A GDPR/PECR opt-out that expires resurrects a
#                   suppressed recipient. This assertion carries a POSITIVE CONTROL: the same
#                   scan shape must find the prune path that DOES exist for webhook_delivery,
#                   or a zero result is a statement about the search rather than about the code.
#
# Exit codes:
#   0  every published Automated period is enforced as declared
#   1  a published period has no enforcement site, no consumer, or the wrong value
#   2  VOID — the gate could not scan: missing jq, a missing/unparseable/empty manifest, no
#      Automated rows, an unreadable enforcement site, ZERO numeric comparisons performed, or a
#      dead positive control
#
# 2 is load-bearing and is not a pass. "I found nothing" must never render as "there is
# nothing" — that is the shape that made a deny-list guard in this repo unable to fire at all.
# A run that performs zero value comparisons has proven nothing, so it VOIDs rather than
# reporting clean.
#
# THREE HAZARDS THIS SCRIPT HAS TO DODGE, all recorded failure modes in this repository:
#
#   1. SELF-MATCH. A gate that names a token can fire on its own definition. The negative scan
#      (assertion 4) is scoped to core-java/src/main/java by ABSOLUTE path, and this script does
#      not live there — which is why this header may discuss notification_suppression freely and
#      the gate stays green.
#
#   2. `cmd | grep -q X` under `set -o pipefail` INVERTS on match: grep exits at the first hit,
#      the writer takes SIGPIPE, and pipefail promotes it to 141 — so a guard written that way
#      fails OPEN on exactly the case it exists to catch. Here-strings only. And `grep -c` exits
#      1 on a zero count, i.e. on the DESIRED state of an absence check, so counts are always
#      captured with `|| true` and compared with `grep -qx 0 <<< "$out"`.
#
#   3. An exit code read after an intervening command reports the WRONG command's status. Every
#      rc in this script is captured on the same statement as its command.
#
# Deliberately NOT gated: R-5 (the customer access cookie). It IS enforced, but the number lives
# in the Keycloak realm, not in this repository. A gate against a value this repo does not own
# becomes a lie the first time the realm changes, so R-5 is published descriptively and classed
# Operational. Operational rows are counted and reported, never value-checked.

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

# Overridable so the VOID direction is testable against an empty or absent file without
# inventing a second code path. Defaults to the real manifest.
MANIFEST="${MANIFEST:-$REPO_ROOT/docs/retention-manifest.json}"

# Scoped by absolute path so the negative scan cannot match this script (hazard 1).
JAVA_SRC="${JAVA_SRC:-$REPO_ROOT/core-java/src/main/java}"

fail() { echo "FAIL: $*" >&2; exit 1; }
void() { echo "VOID: $*" >&2; exit 2; }

echo "Retention-enforcement gate"
echo "  manifest   : ${MANIFEST#"$REPO_ROOT"/}"

command -v jq >/dev/null 2>&1 || void "jq is not installed — the manifest cannot be read, so nothing can be asserted"

[ -f "$MANIFEST" ] || void "manifest not found: $MANIFEST"
[ -r "$MANIFEST" ] || void "manifest is not readable: $MANIFEST"
[ -s "$MANIFEST" ] || void "manifest is empty: $MANIFEST — refusing to report clean over nothing"

jq -e . "$MANIFEST" >/dev/null 2>&1 || void "manifest is not parseable JSON: $MANIFEST"

jq -e 'has("rows") and (.rows | type == "array")' "$MANIFEST" >/dev/null 2>&1 \
    || void "manifest has no .rows array — the shape this gate reads is {flat claim keys, rows: [...]}"

ROW_COUNT="$(jq -r '.rows | length' "$MANIFEST" 2>/dev/null)"
case "$ROW_COUNT" in
    ''|*[!0-9]*) void "could not read a row count out of $MANIFEST" ;;
esac
echo "  rows       : ${ROW_COUNT}"
[ "$ROW_COUNT" -gt 0 ] || void "manifest declares zero rows — refusing to report clean over an empty scan"

AUTOMATED_COUNT="$(jq -r '[.rows[] | select(.enforcement == "Automated")] | length' "$MANIFEST" 2>/dev/null)"
OPERATIONAL_COUNT="$(jq -r '[.rows[] | select(.enforcement == "Operational")] | length' "$MANIFEST" 2>/dev/null)"
case "$AUTOMATED_COUNT" in
    ''|*[!0-9]*) void "could not count Automated rows in $MANIFEST" ;;
esac
echo "  automated  : ${AUTOMATED_COUNT}"
echo "  operational: ${OPERATIONAL_COUNT}"
[ "$AUTOMATED_COUNT" -gt 0 ] || void "manifest declares zero Automated rows — there is nothing to enforce, so this scan proves nothing"

# Every enforcement class must be one of exactly two values. An unrecognised class would slip
# past every check below without ever being examined.
BAD_CLASS="$(jq -r '[.rows[] | select(.enforcement != "Automated" and .enforcement != "Operational") | .id] | join(", ")' "$MANIFEST" 2>/dev/null)"
[ -z "$BAD_CLASS" ] || fail "row(s) carry an enforcement class that is neither Automated nor Operational: $BAD_CLASS"

# A placeholder in a retention schedule is a defect, not a draft.
PLACEHOLDER="$(jq -r '[.rows[] | select(.period_value == null or .period_value == "" or (.period_value | ascii_upcase? // "") == "TBD") | .id] | join(", ")' "$MANIFEST" 2>/dev/null)"
[ -z "$PLACEHOLDER" ] || fail "row(s) publish an empty, null or TBD retention period: $PLACEHOLDER"

# ---- unit conversion -----------------------------------------------------------------------
# Everything reduces to seconds. This is the transform the claim-gate engine cannot do, and
# without it 259200000 and 72 would silently never compare.
to_seconds() { # <value> <unit> ; echoes seconds, or nothing on an unconvertible unit
    local v="$1" u="$2"
    case "$u" in
        milliseconds) [ $((v % 1000)) -eq 0 ] || return 1; echo $((v / 1000)) ;;
        seconds)      echo "$v" ;;
        minutes)      echo $((v * 60)) ;;
        hours)        echo $((v * 3600)) ;;
        days)         echo $((v * 86400)) ;;
        *)            return 1 ;;
    esac
}

# ---- value extraction ----------------------------------------------------------------------
# Returns the integer held at a site, or exits VOID/FAIL with the row named.
#
# yaml-env-default: `  key: ${ENV_VAR:24}` or `  key: 24`. The key is anchored to the start of
# the line so a mention inside a comment or a longer key cannot satisfy it. More than one match
# is an ambiguity, not a pick-the-first: two lines declaring the same key means the gate does
# not know which one the application reads.
extract_yaml() { # <file> <key> <row-id>
    local file="$1" key="$2" id="$3" hits count line rhs
    hits="$(grep -nE "^[[:space:]]*${key}:" "$file" || true)"
    if [ -z "$hits" ]; then
        fail "[$id] ${file#"$REPO_ROOT"/} does not declare '${key}' — the published period has no enforcement site. THIS IS THE R-10 CASE: a period nobody enforces must not be published."
    fi
    count="$(wc -l <<< "$hits")"
    [ "$count" -eq 1 ] || fail "[$id] '${key}' is declared ${count} times in ${file#"$REPO_ROOT"/} — ambiguous, so this gate cannot say which value the application reads"
    line="${hits#*:}"
    rhs="${line##*:}"          # survives the ENV-default form, whose default follows the LAST colon
    if [[ "$rhs" =~ ^[^0-9]*([0-9]+) ]]; then
        echo "${BASH_REMATCH[1]}"
    else
        void "[$id] could not read an integer out of ${file#"$REPO_ROOT"/} for '${key}' (line: ${line})"
    fi
}

# ts-const-expr: `export const REFRESH_MAX_AGE = 60 * 60 * 24 * 30`. The right-hand side is an
# arithmetic expression, so it is charset-validated to digits, '*', '+' and spaces BEFORE being
# evaluated — an unvalidated eval of file contents is a code-execution surface, not a shortcut.
extract_ts_const() { # <file> <key> <row-id>
    local file="$1" key="$2" id="$3" hits count line rhs
    hits="$(grep -nE "^[[:space:]]*export[[:space:]]+const[[:space:]]+${key}[[:space:]]*=" "$file" || true)"
    if [ -z "$hits" ]; then
        fail "[$id] ${file#"$REPO_ROOT"/} does not export a const '${key}' — the published period has no enforcement site."
    fi
    count="$(wc -l <<< "$hits")"
    [ "$count" -eq 1 ] || fail "[$id] const '${key}' is declared ${count} times in ${file#"$REPO_ROOT"/} — ambiguous"
    line="${hits#*:}"
    rhs="${line#*=}"
    rhs="${rhs%%;*}"
    rhs="${rhs%%//*}"
    if [[ ! "$rhs" =~ ^[0-9\*\+[:space:]]+$ ]]; then
        void "[$id] the value of '${key}' in ${file#"$REPO_ROOT"/} is not a plain arithmetic expression (got: ${rhs}) — refusing to evaluate it"
    fi
    echo $(( rhs ))
}

# ---- assertions 1-3: every Automated row ---------------------------------------------------
NUMERIC_CHECKS=0
LITERAL_CHECKS=0
NEGATIVE_CHECKS=0

while IFS=$'\t' read -r id path key kind unit period_value period_unit claim_key consumer_path consumer_token; do
    [ -n "$id" ] || continue

    [ "$path" != "-" ] \
        || fail "[$id] is Automated but names no enforced_by.path. Automated means a gate can read the value out of a named file; if it cannot, the row is Operational."
    ABS_PATH="$REPO_ROOT/$path"
    [ -e "$ABS_PATH" ] || fail "[$id] enforcement site does not exist: $path"
    [ -r "$ABS_PATH" ] || void "[$id] enforcement site exists but is not readable: $path"

    # The consumer is the assertion that catches the R-10 class: a config key with a plausible
    # value and nothing reading it. Existence of the key alone would have passed on it.
    if [ "$consumer_path" != "-" ]; then
        [ "$consumer_token" != "-" ] || void "[$id] declares a consumer path with no must_contain token — an existence-only consumer check proves nothing about whether the value is read"
        ABS_CONSUMER="$REPO_ROOT/$consumer_path"
        [ -e "$ABS_CONSUMER" ] || fail "[$id] declared consumer does not exist: $consumer_path — a period whose reader is gone is not enforced"
        [ -r "$ABS_CONSUMER" ] || void "[$id] declared consumer exists but is not readable: $consumer_path"
        c_hits="$(grep -cF -- "$consumer_token" "$ABS_CONSUMER" || true)"
        if grep -qx 0 <<< "$c_hits"; then
            fail "[$id] ${consumer_path} does not contain '${consumer_token}' — NOTHING READS the published period. This is exactly the shape of the dead key that motivated this gate."
        fi
    fi

    case "$kind" in
        yaml-env-default|ts-const-expr)
            if [ "$kind" = "yaml-env-default" ]; then
                site_value="$(extract_yaml "$ABS_PATH" "$key" "$id")" || exit $?
            else
                site_value="$(extract_ts_const "$ABS_PATH" "$key" "$id")" || exit $?
            fi
            case "$period_value" in
                ''|*[!0-9]*) fail "[$id] declares kind=${kind} (a numeric site) but its published period '${period_value}' is not a number" ;;
            esac
            site_seconds="$(to_seconds "$site_value" "$unit")" \
                || void "[$id] cannot convert ${site_value} ${unit} to seconds — unknown or non-integral unit"
            pub_seconds="$(to_seconds "$period_value" "$period_unit")" \
                || void "[$id] cannot convert the published ${period_value} ${period_unit} to seconds — unknown unit"
            if [ "$site_seconds" != "$pub_seconds" ]; then
                fail "[$id] published ${period_value} ${period_unit} (= ${pub_seconds}s) but ${path} holds ${site_value} ${unit} (= ${site_seconds}s) for '${key}'"
            fi
            NUMERIC_CHECKS=$((NUMERIC_CHECKS + 1))
            echo "  OK  $id  ${period_value} ${period_unit} == ${site_value} ${unit} at ${path}:${key}"
            ;;
        literal)
            l_hits="$(grep -cF -- "$key" "$ABS_PATH" || true)"
            if grep -qx 0 <<< "$l_hits"; then
                fail "[$id] ${path} no longer contains '${key}' — the published behaviour is not what the code does"
            fi
            LITERAL_CHECKS=$((LITERAL_CHECKS + 1))
            echo "  OK  $id  '${key}' present at ${path} (${l_hits} occurrence(s))"
            ;;
        negative-assertion)
            n_hits="$(grep -cF -- "$key" "$ABS_PATH" || true)"
            if grep -qx 0 <<< "$n_hits"; then
                fail "[$id] ${path} no longer states '${key}' — the deliberate no-expiry rule has lost its written anchor"
            fi
            NEGATIVE_CHECKS=$((NEGATIVE_CHECKS + 1))
            echo "  OK  $id  negative-assertion anchor present at ${path}"
            ;;
        *)
            void "[$id] unknown enforced_by.kind '${kind}' — this gate does not know how to check it, so it must not report clean"
            ;;
    esac

    # Assertion 3 — the flat claim key the claim-gate engine reads must agree with the row.
    if [ "$claim_key" != "-" ]; then
        flat="$(jq -r --arg k "$claim_key" 'if has($k) then (.[$k]|tostring) else "__ABSENT__" end' "$MANIFEST" 2>/dev/null)"
        [ "$flat" != "__ABSENT__" ] || fail "[$id] names claim_key '${claim_key}' but the manifest has no such top-level key — the claim-gate engine would VOID on it"
        [ "$flat" = "$period_value" ] || fail "[$id] top-level '${claim_key}' = ${flat} but the row publishes ${period_value} — the manifest disagrees with itself"
    fi
done < <(jq -r '
    # EVERY field is sentinelled to "-" when absent or empty, and that is load-bearing rather
    # than tidy: `IFS=$'"'"'\t'"'"' read` treats TAB as IFS WHITESPACE, so two consecutive tabs
    # collapse into one delimiter and every field after an empty one shifts left by a column.
    # Caught in the first run of this gate — an empty period_unit on R-6 slid its consumer path
    # into the wrong variable and produced a confidently wrong FAIL message.
    def nz: if (. == null or . == "") then "-" else (. | tostring) end;
    .rows[] | select(.enforcement == "Automated") |
    [ (.id | nz),
      (.enforced_by.path | nz),
      (.enforced_by.key | nz),
      (.enforced_by.kind | nz),
      (.enforced_by.unit | nz),
      (.period_value | nz),
      (.period_unit | nz),
      (.claim_key | nz),
      (.enforced_by.consumer.path | nz),
      (.enforced_by.consumer.must_contain | nz)
    ] | @tsv' "$MANIFEST")

# A run that compared no numbers has proven nothing about any period. Reporting that clean is
# the vacuous shape this gate exists to refuse.
[ "$NUMERIC_CHECKS" -gt 0 ] \
    || void "zero value comparisons were performed — every Automated row is existence-only, so no published number was verified against its source"

# ---- assertion 4: the R-6 negative, with a live positive control ----------------------------
#
# Scoped to $JAVA_SRC by absolute path, so this script cannot match its own definition.
[ -d "$JAVA_SRC" ] || void "java source root not found: $JAVA_SRC"

SUPPRESSION_REPO="$JAVA_SRC/uk/jtoye/core/notification/consent/NotificationSuppressionRepository.java"
OPTIN_REPO="$JAVA_SRC/uk/jtoye/core/notification/consent/MarketingOptInRepository.java"
CONTROL_REPO="$JAVA_SRC/uk/jtoye/core/webhook/WebhookDeliveryRepository.java"

for f in "$SUPPRESSION_REPO" "$OPTIN_REPO" "$CONTROL_REPO"; do
    [ -f "$f" ] || void "the negative assertion's scan targets have moved: ${f#"$REPO_ROOT"/} is missing, so a zero result would be a fact about the search"
done

# One pattern, applied identically to the subjects and to the control. Deliberately no {n,m}
# quantifier and no literal brace anywhere: `grep` is ugrep on some developer machines, where a
# brace in a pattern is a metacharacter, so a pattern carrying one can silently match nothing.
PRUNE_PATTERN='(delete|prune|purge)[A-Za-z]*[[:space:]]*\('

CONTROL_HITS="$(grep -cE "$PRUNE_PATTERN" "$CONTROL_REPO" || true)"
if grep -qx 0 <<< "$CONTROL_HITS"; then
    void "POSITIVE CONTROL IS DEAD: the prune-path pattern found nothing in ${CONTROL_REPO#"$REPO_ROOT"/}, which DOES have one. A zero result on the subjects would therefore be a statement about this search, not about the code."
fi
echo "  control    : prune-path pattern finds ${CONTROL_HITS} hit(s) in the webhook delivery repository — the scan is live"

for subject in "$SUPPRESSION_REPO" "$OPTIN_REPO"; do
    hits="$(grep -nE "$PRUNE_PATTERN" "$subject" || true)"
    if [ -n "$hits" ]; then
        echo "  ${subject#"$REPO_ROOT"/}:${hits}" >&2
        fail "[R-6] a prune, purge or delete path now exists against a consent store. A GDPR/PECR opt-out that expires resurrects a suppressed recipient — V54 states the rule and threat T-22-02-04 is what it mitigates. These tables are bounded by their UNIQUE key, never by time."
    fi
done

# The same question asked of the whole source tree, so a prune path added OUTSIDE the two
# repository interfaces is caught too. The control above already proved this pattern shape is
# live, and the scan is not truncated — a truncating filter used to prove absence manufactures
# that absence.
CROSS_HITS="$(grep -rnE '(suppressionRepository|optInRepository|marketingOptInRepository|notificationSuppressionRepository)\.(delete|prune|purge|remove)[A-Za-z]*[[:space:]]*\(' "$JAVA_SRC" || true)"
if [ -n "$CROSS_HITS" ]; then
    echo "$CROSS_HITS" >&2
    fail "[R-6] a call site deletes from a consent store. See above."
fi

echo "  OK  R-6  no prune, purge or delete path exists against either consent store"

echo "PASS: ${ROW_COUNT} published retention row(s) — ${AUTOMATED_COUNT} Automated (${NUMERIC_CHECKS} value comparison(s), ${LITERAL_CHECKS} literal, ${NEGATIVE_CHECKS} negative), ${OPERATIONAL_COUNT} Operational (described, deliberately not gated)."
