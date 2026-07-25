#!/usr/bin/env bash
# check-env-contract.sh — the two-direction core-java env contract gate.
#
# WHY THIS EXISTS (Phase 26, D-07 / D-08 — the DEF-4 and DEF-6 bug classes)
#
#   DEF-4 (both sides of it). k8s/base/core-java-deployment.yaml injected the
#   RabbitMQ credential under the env name RABBITMQ_USERNAME — mirroring the
#   SECRET KEY instead of the Spring placeholder. No application*.yml has ever
#   read that name, so the injected value reached nothing and the primary AMQP
#   pool silently fell back to its literal Spring default while still using the
#   secret's real password. The pool connected, so nothing looked broken. Two
#   more injected envs (the STOMP relay login/passcode) had the identical shape.
#
#   DEF-6 (the local-default class). Thirteen further placeholders that NO
#   manifest supplied at all, each carrying a LOCAL-ONLY default: media uploads
#   resolved to a dev MinIO endpoint with a dev access key, notification email
#   resolved to a loopback relay, and every production unsubscribe link and
#   Stripe Connect vendor return pointed at http://localhost:3000.
#
#   Both classes survived every review, every CI gate and a live cluster
#   rehearsal for the same reason: a wrong-or-missing env NAME resolves to a
#   working-looking default, and nothing ever compared the two sides. This gate
#   compares them. A one-time fix without a gate is a fix that returns.
#
# THE TWO DIRECTIONS
#
#   (a) INJECTED-BUT-UNREAD. Every env NAME injected by
#       k8s/base/core-java-deployment.yaml must appear as an uppercase ${}
#       placeholder in some core-java/src/main/resources/application*.yml, or be
#       on the direction-(a) allowlist with a reason. This direction IS DEF-4:
#       a manifest feeding an env that no config reads.
#
#   (b) EXPECTED-BUT-UNSUPPLIED. Every uppercase ${} placeholder Spring reads
#       must either be injected by the manifest, or have a default that is safe
#       outside a developer laptop:
#         - no default at all + not injected -> FAIL (would hard-fail boot);
#         - ANY default in the local-only word list + not injected -> FAIL
#           (the DEF-6 shape);
#         - anything else -> pass by rule (a safe non-local default), counted so
#           the size of the un-supplied inventory stays visible.
#
# ALLOWLIST HYGIENE IS PART OF THE GATE (D-08 says "reasoned", not "listed")
#   - an entry with a blank / whitespace-only reason FAILS;
#   - a duplicate entry FAILS;
#   - an entry that is no longer needed FAILS as STALE — the variable is now
#     injected, or is now read, or no longer has a local-only default, or has
#     disappeared from the config entirely.
#   So the allowlist cannot rot into a permanent excuse-store; it stays a
#   reviewed inventory that a human signed off on for a stated reason.
#
# COVERAGE LIMITATION — core-java ONLY.
#   This gate reads k8s/base/core-java-deployment.yaml and
#   core-java/src/main/resources/application*.yml. It does NOT cover edge-go
#   (Go `os.Getenv`) or the frontend (Next.js `process.env`), each of which needs
#   its own parser. That extension is a recorded deferred idea
#   ("Env-contract gate coverage for edge-go and frontend",
#   .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/26-CONTEXT.md
#   <deferred>). Do not assume wider coverage than core-java.
#
# TEST-COUNT NOTE
#   A bash gate under k8s/scripts/ contributes 0 to docs/metrics.json:
#   scripts/docs-freshness.sh counts only Java @Test methods, Go Test* funcs,
#   Jest/vitest it()/test() blocks and Playwright test() blocks — no bash. That
#   is deliberate. A JUnit equivalent would add +N to metrics.json and force a
#   --write reconcile of a documented cross-branch merge-conflict hotspot in the
#   same PR, for no extra assurance.
#
# PARSING NOTES (each one is a real trap found in the actual files)
#   1. Injected envs are matched anchored to the env-list item indent
#      (`^\s+- name: NAME$`, all-uppercase rest-of-line). The container name,
#      port names and HPA metric names are lowercase, so they do not match.
#   2. The placeholder regex tolerates ONE level of nesting. A naive
#      `\$\{([A-Z_]+):([^}]*)\}` mis-terminates on the real nested defaults in
#      application.yml (the two expected-issuer chains and the four STOMP
#      credential chains), truncating the name/default split. Nested INNER
#      placeholders are recorded too, so a name that appears only as a fallback
#      inside another placeholder still counts as read.
#   3. The uppercase filter IS the env-vs-property discriminator:
#      ${spring.application.name} and ${jtoye.security...} are Spring PROPERTY
#      references, not env vars, and [A-Z0-9_]+ excludes them.
#   4. FULL-LINE YAML comments are stripped before extraction. A comment that
#      merely MENTIONS a placeholder would otherwise make direction (a) believe
#      a dead env is read — application.yml really does contain the text
#      `${RABBITMQ_USER:guest}` inside an explanatory comment, i.e. exactly the
#      masking case. Trailing (same-line) comments are deliberately NOT stripped:
#      a `#` can legitimately appear inside a quoted value, and no placeholder
#      currently appears in a trailing comment in any application*.yml.
#   5. One name can carry SEVERAL different defaults across profiles (real cases
#      exist). Defaults are collected as a SET per name and the local-only rule
#      trips if ANY member matches — matched per-default, never against a joined
#      string, because an anchored test on a joined string misses the member.
#   6. Local-only means bare words as much as URLs. `minioadmin` and a bare-word
#      broker default are the DEF-4/DEF-6 signature; a URL-only regex misses
#      both.
#
# Requires: bash >= 4.3 (associative arrays + namerefs), GNU grep with -P (PCRE),
#           sed, find. ubuntu-latest (the CI runner) ships bash 5.x.
# Exit codes: 0 = contract holds, 1 = violation, 2 = parse/tooling failure.
#
# Usage: ./k8s/scripts/check-env-contract.sh
#   (run from anywhere; paths resolve relative to the repo root)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

DEPLOYMENT="$REPO_ROOT/k8s/base/core-java-deployment.yaml"
APP_DIR="$REPO_ROOT/core-java/src/main/resources"

fail() { echo "FAIL: $*" >&2; exit 1; }
parse_fail() { echo "PARSE ERROR: $*" >&2; exit 2; }

# ===========================================================================
# ALLOWLIST — direction (a): injected by k8s, read by no application*.yml.
#
# Format: NAME|reason. A blank reason, a duplicate, or an entry that is no
# longer needed (not injected any more, or now genuinely read) fails the gate.
# NEVER widen this list to make the gate pass: if the gate is right and the
# manifest is wrong, fix the manifest.
# ===========================================================================
ALLOW_INJECTED_UNREAD=(
  'SPRING_PROFILES_ACTIVE|Spring relaxed-binding environment variable, not a ${} placeholder. Spring Boot binds it directly onto spring.profiles.active before any property source is read, so it correctly appears in no application*.yml. It is load-bearing (26-CONTEXT.md D-10 keeps every k8s environment on the prod profile) and must not be removed to satisfy direction (a).'
)

# ===========================================================================
# ALLOWLIST — direction (b): read by Spring, supplied by no manifest, and the
# default is local-only (or absent). Each entry is a REVIEWED omission.
#
# Format: NAME|reason. Same hygiene rules as above. An entry here that becomes
# manifest-supplied, or whose defaults stop being local-only, fails as STALE so
# the inventory cannot silently rot.
# ===========================================================================
ALLOW_UNSUPPLIED_LOCAL_DEFAULT=(
  'OLLAMA_URL|Reviewed omission: there is no in-cluster Ollama, and the media vision stage is advisory-only behind jtoye.media.vision.enabled, which defaults false (Phase 24 IMG-03 — a vision failure never rejects an upload, it only flags for review). Supplying this would point core-java at a host that does not exist; leaving the unreachable default keeps the stage inert, which is the intended k8s behaviour.'
  'ZIPKIN_ENDPOINT|Reviewed omission: no in-cluster Zipkin/OTLP collector is deployed, and Micrometer tracing export is best-effort — spans are dropped silently and no request path degrades. Revisit when an observability phase actually adds a collector; until then a supplied-but-wrong endpoint would be worse than an unreachable default.'
  'CUSTOMER_KC_ISSUER_URI|Reviewed omission, explicitly deferred in 26-CONTEXT.md <deferred> ("Customer-storefront realm in k8s"). The whole customer-storefront realm is unconfigured in EVERY k8s environment, so supplying only this one issuer would half-wire it and make a broken realm look configured. Belongs with the storefront/CID work, not with this phase.'
)

# Bare words and hostnames that are only ever correct on a developer laptop.
LOCAL_ONLY_WORDS=(
  localhost
  127.0.0.1
  0.0.0.0
  minioadmin
  guest
  mailhog
  host.docker.internal
)

# ---------------------------------------------------------------------------
# Tooling preflight
# ---------------------------------------------------------------------------
if (( ${BASH_VERSINFO[0]:-0} < 4 )) \
   || { (( ${BASH_VERSINFO[0]:-0} == 4 )) && (( ${BASH_VERSINFO[1]:-0} < 3 )); }; then
    parse_fail "bash >= 4.3 is required (associative arrays + namerefs); found ${BASH_VERSION:-unknown}"
fi

if ! printf 'probe\n' | grep -qP 'pro\w+' 2> /dev/null; then
    parse_fail "GNU 'grep -P' (PCRE) is required — the placeholder regex needs a non-capturing group and a lookahead. On ubuntu-latest (the CI runner) and any GNU grep this is available; on BSD/macOS grep it is not."
fi

[[ -f "$DEPLOYMENT" ]] || parse_fail "manifest not found: $DEPLOYMENT"
[[ -d "$APP_DIR" ]]    || parse_fail "config directory not found: $APP_DIR"

mapfile -t APP_FILES < <(find "$APP_DIR" -maxdepth 1 -type f -name 'application*.yml' | sort)
(( ${#APP_FILES[@]} > 0 )) || parse_fail "no application*.yml found under $APP_DIR"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------------------
# Direction-(a) input: env names injected by the manifest
# ---------------------------------------------------------------------------
mapfile -t INJECTED_NAMES < <(
    grep -oP '^\s+- name: \K[A-Z0-9_]+(?=\s*$)' "$DEPLOYMENT" | sort -u
)
(( ${#INJECTED_NAMES[@]} > 0 )) \
    || parse_fail "extracted 0 injected env names from $DEPLOYMENT — the '- name: NAME' shape changed and this gate is now blind. Fix the parser, do not delete the gate."

declare -A IS_INJECTED=()
for n in "${INJECTED_NAMES[@]}"; do IS_INJECTED["$n"]=1; done

# ---------------------------------------------------------------------------
# Direction-(b) input: uppercase ${} placeholders across all profiles
# ---------------------------------------------------------------------------
STRIPPED="$TMP/application-all.stripped.yml"
for f in "${APP_FILES[@]}"; do
    # Strip FULL-LINE comments only (see PARSING NOTES 4).
    sed -E 's/^[[:space:]]*#.*$//' "$f"
done > "$STRIPPED"

# Tolerates one level of nesting in the default (PARSING NOTES 2).
PLACEHOLDER_RE='\$\{([A-Z0-9_]+)(?::((?:[^{}]|\$\{[^}]*\})*))?\}'
# Inner (non-nested) form, used to recover names that only appear as a fallback.
INNER_RE='\$\{[A-Z0-9_]+(?::[^{}]*)?\}'

mapfile -t PLACEHOLDER_MATCHES < <(grep -ohP "$PLACEHOLDER_RE" "$STRIPPED" | sort -u)
(( ${#PLACEHOLDER_MATCHES[@]} > 0 )) \
    || parse_fail "extracted 0 \${} placeholders from ${#APP_FILES[@]} application*.yml file(s) — the extraction regex is broken and this gate is now blind. Fix the parser, do not delete the gate."

declare -A IS_READ=()      # name -> 1 : appears as a placeholder somewhere
declare -A HAS_NODEF=()    # name -> 1 : at least one occurrence has NO default
declare -A DEFAULT_SET=()  # name -> newline-joined set of observed defaults

record_one() {
    # record_one '${NAME}' | '${NAME:default}'
    local m="$1" body name def
    body="${m:2}"        # strip the leading '${'
    body="${body%\}}"    # strip the trailing '}'

    if [[ "$body" == *:* ]]; then
        name="${body%%:*}"
        def="${body#*:}"
    else
        name="$body"
        def=""
    fi

    [[ "$name" =~ ^[A-Z0-9_]+$ ]] \
        || parse_fail "extracted a non-env placeholder name '$name' from '$m' — the regex and the uppercase filter disagree."

    IS_READ["$name"]=1

    if [[ "$body" == *:* ]]; then
        # Set semantics: never append the same default twice (PARSING NOTES 5).
        if [[ $'\n'"${DEFAULT_SET["$name"]-}" != *$'\n'"$def"$'\n'* ]]; then
            DEFAULT_SET["$name"]="${DEFAULT_SET["$name"]-}$def"$'\n'
        fi
    else
        HAS_NODEF["$name"]=1
    fi
}

for m in "${PLACEHOLDER_MATCHES[@]}"; do
    record_one "$m"
    # One level of nesting: recover the inner placeholder(s) from the default,
    # so a name used only as a fallback still counts as read.
    if [[ "$m" == *':${'* ]]; then
        while IFS= read -r inner; do
            [[ -n "$inner" ]] && record_one "$inner"
        done < <(printf '%s\n' "${m#*:}" | grep -ohP "$INNER_RE" || true)
    fi
done

mapfile -t PLACEHOLDER_NAMES < <(printf '%s\n' "${!IS_READ[@]}" | sort)

# ---------------------------------------------------------------------------
# Local-only default detection
#   Word-boundary match so `localhost` hits inside http://localhost:3000 and a
#   bare-word credential hits as a whole value, without matching a longer word
#   that merely contains it.
# ---------------------------------------------------------------------------
matched_local_default() {
    # matched_local_default <name> -> echoes "word<TAB>default" of the FIRST
    # local-only member found, returns 1 if none.
    local name="$1" def word esc
    while IFS= read -r def; do
        [[ -n "$def" ]] || continue
        for word in "${LOCAL_ONLY_WORDS[@]}"; do
            esc="${word//./\\.}"
            if grep -qE "(^|[^[:alnum:]])${esc}([^[:alnum:]]|\$)" <<< "$def"; then
                printf '%s\t%s\n' "$word" "$def"
                return 0
            fi
        done
    done <<< "${DEFAULT_SET["$name"]-}"
    return 1
}

# ---------------------------------------------------------------------------
# Allowlist parsing + hygiene
# ---------------------------------------------------------------------------
declare -A ALLOW_A=() ALLOW_B=()
HYGIENE_ERRORS=()

parse_allowlist() {
    # parse_allowlist <name-of-target-map> <label> <entry>...
    local -n MAP="$1"
    local label="$2"; shift 2
    local entry name reason
    for entry in "$@"; do
        if [[ "$entry" != *"|"* ]]; then
            HYGIENE_ERRORS+=("$label: malformed entry (no '|' separator): '$entry'")
            continue
        fi
        name="${entry%%|*}"
        reason="${entry#*|}"
        if [[ ! "$name" =~ ^[A-Z0-9_]+$ ]]; then
            HYGIENE_ERRORS+=("$label: entry name '$name' is not an uppercase env name")
            continue
        fi
        if [[ -z "${reason//[[:space:]]/}" ]]; then
            HYGIENE_ERRORS+=("$label: entry '$name' has a blank reason. D-08 requires a REASONED allowlist — an unexplained entry is indistinguishable from a forgotten defect.")
            continue
        fi
        if [[ -n "${MAP["$name"]-}" ]]; then
            HYGIENE_ERRORS+=("$label: duplicate entry '$name'")
            continue
        fi
        MAP["$name"]="$reason"
    done
}

parse_allowlist ALLOW_A 'allowlist (a)' "${ALLOW_INJECTED_UNREAD[@]}"
parse_allowlist ALLOW_B 'allowlist (b)' "${ALLOW_UNSUPPLIED_LOCAL_DEFAULT[@]}"

# Staleness: an allowlist entry that is no longer needed is a defect, because it
# hides the fact that the reviewed inventory has moved on.
for name in $(printf '%s\n' "${!ALLOW_A[@]}" | sort); do
    if [[ -z "${IS_INJECTED["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("allowlist (a): STALE entry '$name' — no manifest injects that env any more, so the exemption is dead. Remove the entry.")
    elif [[ -n "${IS_READ["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("allowlist (a): STALE entry '$name' — some application*.yml now reads it as a \${} placeholder, so it is no longer an injected-but-unread env. Remove the entry.")
    fi
done

for name in $(printf '%s\n' "${!ALLOW_B[@]}" | sort); do
    if [[ -z "${IS_READ["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("allowlist (b): STALE entry '$name' — no application*.yml reads that placeholder any more, so the exemption is dead. Remove the entry.")
    elif [[ -n "${IS_INJECTED["$name"]-}" ]]; then
        HYGIENE_ERRORS+=("allowlist (b): STALE entry '$name' — a manifest now SUPPLIES it, so it is no longer an unsupplied omission. Remove the entry rather than leaving a standing excuse for a variable that is already fixed.")
    elif [[ -z "${HAS_NODEF["$name"]-}" ]] && ! matched_local_default "$name" > /dev/null; then
        HYGIENE_ERRORS+=("allowlist (b): STALE entry '$name' — its default(s) are no longer local-only and it is not default-less, so it would pass by rule without an exemption. Remove the entry.")
    fi
done

# ---------------------------------------------------------------------------
# Direction (a): injected but unread
# ---------------------------------------------------------------------------
A_VIOLATIONS=()
A_ALLOWED=0
A_OK=0
for name in "${INJECTED_NAMES[@]}"; do
    if [[ -n "${IS_READ["$name"]-}" ]]; then
        (( ++A_OK ))
    elif [[ -n "${ALLOW_A["$name"]-}" ]]; then
        (( ++A_ALLOWED ))
    else
        A_VIOLATIONS+=("$name")
    fi
done

# ---------------------------------------------------------------------------
# Direction (b): expected but unsupplied
# ---------------------------------------------------------------------------
B_NODEF_VIOLATIONS=()
B_LOCAL_VIOLATIONS=()
B_ALLOWED=0
B_SUPPLIED=0
B_PASS_BY_RULE=0
for name in "${PLACEHOLDER_NAMES[@]}"; do
    if [[ -n "${IS_INJECTED["$name"]-}" ]]; then
        (( ++B_SUPPLIED ))
        continue
    fi

    hit=""
    if [[ -n "${HAS_NODEF["$name"]-}" ]]; then
        if [[ -n "${ALLOW_B["$name"]-}" ]]; then
            (( ++B_ALLOWED ))
        else
            B_NODEF_VIOLATIONS+=("$name")
        fi
        continue
    fi

    if hit="$(matched_local_default "$name")"; then
        if [[ -n "${ALLOW_B["$name"]-}" ]]; then
            (( ++B_ALLOWED ))
        else
            B_LOCAL_VIOLATIONS+=("$name	${hit}")
        fi
        continue
    fi

    (( ++B_PASS_BY_RULE ))
done

# ---------------------------------------------------------------------------
# Classification summary — printed before any verdict so a reviewer sees the
# shape of the inventory without reading the code.
# ---------------------------------------------------------------------------
echo "core-java env contract (D-07 / D-08)"
echo "  manifest : k8s/base/core-java-deployment.yaml"
echo "  config   : ${#APP_FILES[@]} application*.yml file(s) under core-java/src/main/resources/"
echo
echo "Direction (a) — injected by k8s, read by no application*.yml (the DEF-4 shape):"
printf '  %-42s %d\n' 'injected env names'                "${#INJECTED_NAMES[@]}"
printf '  %-42s %d\n' 'read by some application*.yml'     "$A_OK"
printf '  %-42s %d\n' 'allowlisted (reasoned omission)'   "$A_ALLOWED"
printf '  %-42s %d\n' 'VIOLATIONS'                        "${#A_VIOLATIONS[@]}"
echo
echo "Direction (b) — expected by Spring, supplied by no manifest (the DEF-6 shape):"
printf '  %-42s %d\n' 'distinct ${} placeholders'         "${#PLACEHOLDER_NAMES[@]}"
printf '  %-42s %d\n' 'supplied by the manifest'          "$B_SUPPLIED"
printf '  %-42s %d\n' 'allowlisted (reasoned omission)'   "$B_ALLOWED"
printf '  %-42s %d\n' 'pass by rule (safe non-local default)' "$B_PASS_BY_RULE"
printf '  %-42s %d\n' 'VIOLATIONS (no default at all)'    "${#B_NODEF_VIOLATIONS[@]}"
printf '  %-42s %d\n' 'VIOLATIONS (local-only default)'   "${#B_LOCAL_VIOLATIONS[@]}"
echo

# ---------------------------------------------------------------------------
# Verdict
# ---------------------------------------------------------------------------
VIOLATION=0

if (( ${#HYGIENE_ERRORS[@]} > 0 )); then
    echo "ALLOWLIST HYGIENE — the allowlist itself is not in a reviewable state:" >&2
    for e in "${HYGIENE_ERRORS[@]}"; do echo "  - $e" >&2; done
    echo >&2
    VIOLATION=1
fi

if (( ${#A_VIOLATIONS[@]} > 0 )); then
    echo "DIRECTION (a) VIOLATION — env injected by the manifest but read by NO application*.yml:" >&2
    for name in "${A_VIOLATIONS[@]}"; do
        echo "  - $name" >&2
    done
    echo >&2
    echo "  This is exactly DEF-4: a manifest feeding an env that no config reads." >&2
    echo "  The injected value reaches NOTHING and Spring silently uses its own" >&2
    echo "  literal default instead — which is why the class survived review, CI and a" >&2
    echo "  live rehearsal. Fix the env NAME in k8s/base/core-java-deployment.yaml to" >&2
    echo "  match the \${PLACEHOLDER} the config actually reads (do NOT rename the" >&2
    echo "  secret key), or add the placeholder to application*.yml. Only add an" >&2
    echo "  allowlist entry if the env is genuinely Spring-native (relaxed binding)." >&2
    echo >&2
    VIOLATION=1
fi

if (( ${#B_NODEF_VIOLATIONS[@]} > 0 )); then
    echo "DIRECTION (b) VIOLATION — placeholder with NO default that no manifest supplies:" >&2
    for name in "${B_NODEF_VIOLATIONS[@]}"; do
        echo "  - $name" >&2
    done
    echo >&2
    echo "  Spring cannot resolve these, so the container hard-fails at boot." >&2
    echo "  Supply them from app-config or a Secret in k8s/base/core-java-deployment.yaml." >&2
    echo >&2
    VIOLATION=1
fi

if (( ${#B_LOCAL_VIOLATIONS[@]} > 0 )); then
    echo "DIRECTION (b) VIOLATION — placeholder whose default is LOCAL-ONLY and that no manifest supplies:" >&2
    while IFS=$'\t' read -r name word def; do
        echo "  - $name  (default: '$def'  — local-only token: '$word')" >&2
    done < <(printf '%s\n' "${B_LOCAL_VIOLATIONS[@]}")
    echo >&2
    echo "  This is the DEF-6 shape: outside a developer laptop the default is wrong," >&2
    echo "  and the failure is SILENT — media writes go nowhere, email goes to a" >&2
    echo "  loopback relay, a production link points at localhost. Either supply the" >&2
    echo "  value from app-config / a Secret, or add an ALLOWLIST entry WITH A REASON" >&2
    echo "  so the omission becomes a reviewed inventory instead of a surprise." >&2
    echo "  Never widen the allowlist just to make this gate pass." >&2
    echo >&2
    VIOLATION=1
fi

if (( VIOLATION != 0 )); then
    fail "the core-java env contract is broken — see the violations above. Fix the manifest or the config; only widen an allowlist when the omission is genuinely reviewed and you can state why."
fi

echo "PASS: ${#INJECTED_NAMES[@]} injected env names all read by application*.yml (${A_ALLOWED} reasoned exemption(s)); ${#PLACEHOLDER_NAMES[@]} placeholders carry no unsupplied local-only or missing default (${B_ALLOWED} reasoned exemption(s))."
