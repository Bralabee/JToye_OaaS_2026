#!/bin/sh
# Prometheus entrypoint wrapper (phase 27, plan 27-00)
#
# Prometheus has no native env-var substitution in scrape configs, so we render the
# template at container start using sed — the same mechanism, for the same reason, as
# ../alertmanager/entrypoint.sh. One idiom for the whole monitoring directory.
#
# Ordering:
#   1. Apply the dev/compose default and VALIDATE it (fail fast on a bad value)
#   2. Render template via sed (pipe delimiter because values may contain `/`)
#   3. Run `promtool check config` BEFORE exec (fail fast on malformed YAML or an
#      unresolvable rule_files reference)
#   4. exec prometheus, forwarding the flags from the compose `command:` list
#
# WHY THE PORT IS INJECTED. The core-java actuator port differs by runtime:
#   prod/k8s    -> 9091, a separate management port (application-prod.yml:106-107)
#   dev/compose -> 9090, the app port, actuator opted in via
#                  MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE
# The template previously hardcoded the prod value, so the compose target was DOWN and
# eight alerts were blind. Hardcoding the dev value instead would only move which
# runtime is wrong. See prometheus.yml.tmpl's core-java job for the full note.

set -eu

# Default is the dev/compose value: this Prometheus serves the compose runtime (there
# are no Prometheus manifests under k8s/). Override via .env for any runtime whose
# core-java exposes actuator elsewhere.
: "${CORE_JAVA_METRICS_PORT:=9090}"

# Validate on load. An unset-but-defaulted value is fine; a malformed one is not, and a
# bad substitution must fail HERE with a clear error rather than surface later as a
# permanently-down scrape target that looks like an application fault.
if ! echo "${CORE_JAVA_METRICS_PORT}" | grep -qE '^[0-9]{1,5}$' \
   || [ "${CORE_JAVA_METRICS_PORT}" -lt 1 ] \
   || [ "${CORE_JAVA_METRICS_PORT}" -gt 65535 ]; then
  echo "FATAL: CORE_JAVA_METRICS_PORT must be an integer 1-65535, got '${CORE_JAVA_METRICS_PORT}'" >&2
  exit 1
fi

TEMPLATE="/etc/prometheus/prometheus.yml.tmpl"
RENDERED="/etc/prometheus/prometheus.yml"

[ -r "${TEMPLATE}" ] || { echo "FATAL: template not readable: ${TEMPLATE}" >&2; exit 1; }

sed \
  -e "s|__CORE_JAVA_METRICS_PORT__|${CORE_JAVA_METRICS_PORT}|g" \
  "${TEMPLATE}" > "${RENDERED}"

# No placeholder may survive the render. Catches a renamed placeholder in the template
# that the sed line above was not updated for — which would otherwise start cleanly and
# scrape a host literally named "__SOMETHING__".
if grep -q '__[A-Z0-9_]\{1,\}__' "${RENDERED}"; then
  echo "FATAL: unrendered placeholder(s) remain in ${RENDERED}:" >&2
  grep -o '__[A-Z0-9_]\{1,\}__' "${RENDERED}" | sort -u >&2
  exit 1
fi

echo "prometheus entrypoint: rendered with CORE_JAVA_METRICS_PORT=${CORE_JAVA_METRICS_PORT}"

/bin/promtool check config "${RENDERED}"

exec /bin/prometheus "$@"
