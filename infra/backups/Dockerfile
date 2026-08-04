# JToye OaaS PostgreSQL backup image (#90 P1-8).
#
# Purpose-built so the CronJob needs NO runtime package installs (the previous
# postgres:15-alpine + `apk add aws-cli` failed inside the default-deny
# NetworkPolicy). Debian base (bookworm) also gives GNU coreutils/grep, so the
# script's `date -d` / `grep -oE` work under `set -euo pipefail`.
#
# Build:  docker build -t ghcr.io/bralabee/jtoye-pg-backup:15 infra/backups
# The tag must match the image referenced in k8s/base/pg-backup-cronjob.yaml.
FROM postgres:18-bookworm

# pg_dump / pg_restore ship with the postgres base. Add aws-cli (Debian v1) for
# the S3 transfer and CA certs for TLS to real S3. --no-install-recommends keeps
# the layer small; clean apt lists so no cache bloat.
RUN apt-get update \
 && apt-get install -y --no-install-recommends awscli ca-certificates \
 && rm -rf /var/lib/apt/lists/*

COPY k8s-backup.sh /usr/local/bin/backup
RUN chmod 0755 /usr/local/bin/backup

# Run unprivileged (matches the CronJob securityContext runAsUser: 1000). aws-cli
# reads credentials from the environment, so a real home dir is unnecessary;
# point HOME at /tmp (the only writable path) to avoid config-dir warnings.
ENV HOME=/tmp
USER 1000:1000

ENTRYPOINT ["/usr/local/bin/backup"]
