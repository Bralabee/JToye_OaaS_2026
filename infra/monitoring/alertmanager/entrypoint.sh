#!/bin/sh
# Alertmanager entrypoint wrapper (phase 9)
#
# Alertmanager has no native env-var substitution, so we render the template
# at container start using sed. SMTP credentials (if any) are sensitive —
# NEVER use `set -x` here (it would leak secrets into container logs).
#
# Ordering:
#   1. Apply safe defaults (dev-friendly: Mailhog, no auth, no TLS)
#   2. Render template via sed (pipe delimiter because paths may contain `/`)
#   3. Run `amtool check-config` BEFORE exec (fail fast on malformed YAML)
#   4. Exec alertmanager with the rendered config

set -eu

: "${ALERTMANAGER_SMTP_SMARTHOST:=mailhog:1025}"
: "${ALERTMANAGER_SMTP_FROM:=alerts@jtoye.local}"
: "${ALERTMANAGER_SMTP_TO:=ops@jtoye.local}"
: "${ALERTMANAGER_SMTP_REQUIRE_TLS:=false}"

TEMPLATE="/etc/alertmanager/alertmanager.yml.tmpl"
RENDERED="/etc/alertmanager/alertmanager.yml"

sed \
  -e "s|__SMTP_SMARTHOST__|${ALERTMANAGER_SMTP_SMARTHOST}|g" \
  -e "s|__SMTP_FROM__|${ALERTMANAGER_SMTP_FROM}|g" \
  -e "s|__SMTP_TO__|${ALERTMANAGER_SMTP_TO}|g" \
  -e "s|__SMTP_REQUIRE_TLS__|${ALERTMANAGER_SMTP_REQUIRE_TLS}|g" \
  "${TEMPLATE}" > "${RENDERED}"

/bin/amtool check-config "${RENDERED}"

exec /bin/alertmanager \
  --config.file="${RENDERED}" \
  --storage.path=/alertmanager \
  --web.external-url=http://localhost:9093
