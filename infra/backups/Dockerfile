# JToye OaaS PostgreSQL backup image (#90 P1-8; Phase 36 BLOB-06).
#
# Purpose-built so the CronJob needs NO runtime package installs (a runtime
# install fails inside the default-deny NetworkPolicy). Debian base (bookworm)
# also gives GNU coreutils/grep, so the script works under `set -euo pipefail`.
#
# Destination: Azure Blob Storage, through blobctl (infra/backups/blobctl), a
# small static Go binary built in stage 1 and copied into the runtime stage.
# blobctl uploads and never overwrites (If-None-Match: *), and has no delete
# code path at all. It authenticates with the ONE STORAGE_AUTH_MODE switch:
#   workload-identity  staging/production. AKS Workload Identity projects a
#                      federated token (AZURE_FEDERATED_TOKEN_FILE) and the pod
#                      gets AZURE_CLIENT_ID / AZURE_TENANT_ID; no account key,
#                      SAS or connection string is stored anywhere (D-02).
#   connection-string  local + nightly only, against Azurite, and only in the
#                      emulator form (UseDevelopmentStorage=true;...).
# The previous object-store CLI (a Debian package) is gone: the trust surface is
# one statically linked binary whose module is `go mod verify`-checked below.
#
# Build (context = infra/backups):
#   docker build -t ghcr.io/bralabee/jtoye-pg-backup:15-blob infra/backups
# The tag must match the image referenced in k8s/base/pg-backup-cronjob.yaml.
# (D-10: the contents changed — the AWS CLI out, blobctl in — so the tag moved
# from :15 to :15-blob; the CronJob, this line, the horizons pin and the goldens
# moved together in one change. `15` still names the postgres major, which
# scripts/check-postgres-major-parity.sh reads from each of those sites.)

# --- Stage 1: build blobctl ---------------------------------------------------
# Go version must match infra/backups/blobctl/go.mod's go directive (1.27.0),
# edge-go/Dockerfile's builder and the CI setup-go pins, and the Go rows in
# infra/dependency-horizons.yaml (`golang` lists this line as a site, and
# `go-toolchain` lists blobctl/go.mod) — bump all of them in lockstep. This
# stage is build-only; nothing from it but the binary reaches the runtime image.
FROM golang:1.27-alpine AS builder

WORKDIR /src

# Module files first so the dependency layer caches; verify the downloaded
# modules against go.sum before any source is compiled.
COPY blobctl/go.mod blobctl/go.sum ./
RUN go mod download && go mod verify

COPY blobctl/ ./
# Static (no libc dependency on the Debian runtime), stripped, path-free.
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /out/blobctl .

# --- Stage 2: runtime ---------------------------------------------------------
# The base major is governed by scripts/gates/postgres-major-parity.conf (the
# dump tooling must not be newer than the server). Do not change it here.
FROM postgres:15-bookworm

# pg_dump / pg_restore ship with the postgres base. CA certs are for TLS to the
# Blob endpoint and the Entra token endpoint. --no-install-recommends keeps the
# layer small; clean apt lists so no cache bloat.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates \
 && rm -rf /var/lib/apt/lists/*

COPY --from=builder /out/blobctl /usr/local/bin/blobctl
COPY k8s-backup.sh /usr/local/bin/backup
RUN chmod 0755 /usr/local/bin/backup /usr/local/bin/blobctl

# Run unprivileged (matches the CronJob securityContext runAsUser: 1000).
# blobctl reads its configuration from the environment, so a real home dir is
# unnecessary; point HOME at /tmp (the only writable path).
ENV HOME=/tmp
USER 1000:1000

ENTRYPOINT ["/usr/local/bin/backup"]
