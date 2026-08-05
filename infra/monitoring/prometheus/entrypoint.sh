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
# WHY THE PORTS ARE INJECTED. Both scraped J'Toye services expose metrics on a port
# that differs by runtime:
#
#   core-java   prod/k8s    -> 9091, a separate management port (application-prod.yml:106-107)
#               dev/compose -> 9090, the app port, actuator opted in via
#                              MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE
#   edge-go     dev/compose -> 9101, a separate management listener, because
#                              EDGE_MANAGEMENT_PORT is set on the edge-go service
#                              (issue #550 — /metrics must not be on the published
#                              app port 8089, which is bound on all interfaces)
#               k8s         -> 8080, the app port; EDGE_MANAGEMENT_PORT is unset there
#                              and no in-cluster Prometheus exists yet (DPLY-03)
#
# The template previously hardcoded core-java's prod value, so the compose target was
# DOWN and eight alerts were blind. Hardcoding either value would only move which
# runtime is wrong. See the two job blocks in prometheus.yml.tmpl for the full notes.

set -eu

# Defaults are the dev/compose values: this Prometheus serves the compose runtime
# (there are no Prometheus manifests under k8s/). Override via .env for any runtime
# whose services expose metrics elsewhere.
#
# EDGE_GO_METRICS_PORT must match EDGE_MANAGEMENT_PORT on the edge-go service in
# docker-compose.full-stack.yml. Both are interpolated from this one .env key, and the
# default below is the third and last copy of the literal — keep them in step.
: "${CORE_JAVA_METRICS_PORT:=9090}"
: "${EDGE_GO_METRICS_PORT:=9101}"

# Validate on load. An unset-but-defaulted value is fine; a malformed one is not, and a
# bad substitution must fail HERE with a clear error rather than surface later as a
# permanently-down scrape target that looks like an application fault.
#
# `case` rather than a grep pipeline: this runs under busybox ash inside the Prometheus
# image, where a here-string is unavailable, and a piped grep is the shape that inverts
# under pipefail. A glob test needs no subprocess and cannot fail open. The length guard
# is load-bearing — `[ "$v" -gt 65535 ]` errors out on a value wider than an int64
# instead of rejecting it.
validate_port() {
  _name="$1"
  _value="$2"
  case "${_value}" in
    ''|*[!0-9]*)
      echo "FATAL: ${_name} must be an integer 1-65535, got '${_value}'" >&2
      exit 1
      ;;
  esac
  if [ "${#_value}" -gt 5 ] || [ "${_value}" -lt 1 ] || [ "${_value}" -gt 65535 ]; then
    echo "FATAL: ${_name} must be an integer 1-65535, got '${_value}'" >&2
    exit 1
  fi
}

validate_port CORE_JAVA_METRICS_PORT "${CORE_JAVA_METRICS_PORT}"
validate_port EDGE_GO_METRICS_PORT "${EDGE_GO_METRICS_PORT}"

# Paths and binaries are overridable ONLY so this script can be exercised outside the
# image (render + validation + the placeholder assertion, against a stub promtool).
# The container supplies none of them and gets the defaults below.
TEMPLATE="${TEMPLATE:-/etc/prometheus/prometheus.yml.tmpl}"
RENDERED="${RENDERED:-/etc/prometheus/prometheus.yml}"
PROMTOOL_BIN="${PROMTOOL_BIN:-/bin/promtool}"
PROMETHEUS_BIN="${PROMETHEUS_BIN:-/bin/prometheus}"

[ -r "${TEMPLATE}" ] || { echo "FATAL: template not readable: ${TEMPLATE}" >&2; exit 1; }

sed \
  -e "s|__CORE_JAVA_METRICS_PORT__|${CORE_JAVA_METRICS_PORT}|g" \
  -e "s|__EDGE_GO_METRICS_PORT__|${EDGE_GO_METRICS_PORT}|g" \
  "${TEMPLATE}" > "${RENDERED}"

# No placeholder may survive the render. Catches a renamed placeholder in the template
# that the sed line above was not updated for — which would otherwise start cleanly and
# scrape a host literally named "__SOMETHING__".
if grep -q '__[A-Z0-9_]\{1,\}__' "${RENDERED}"; then
  echo "FATAL: unrendered placeholder(s) remain in ${RENDERED}:" >&2
  grep -o '__[A-Z0-9_]\{1,\}__' "${RENDERED}" | sort -u >&2
  exit 1
fi

echo "prometheus entrypoint: rendered with CORE_JAVA_METRICS_PORT=${CORE_JAVA_METRICS_PORT} EDGE_GO_METRICS_PORT=${EDGE_GO_METRICS_PORT}"

"${PROMTOOL_BIN}" check config "${RENDERED}"

exec "${PROMETHEUS_BIN}" "$@"
