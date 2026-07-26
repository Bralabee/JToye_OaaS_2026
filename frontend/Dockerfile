# Multi-stage Dockerfile for frontend (Next.js 16)
# Optimized for production: standalone build, minimal size

# Stage 1: Builder
FROM node:20-alpine AS builder

WORKDIR /app

# Install all dependencies (including dev) from the lockfile. Copying the
# manifest first lets Docker cache this layer until dependencies change.
COPY package.json package-lock.json* ./
RUN npm ci

# Copy source (node_modules is excluded via .dockerignore)
COPY . .

# Build Next.js app with standalone output.
# This creates a minimal production build with only necessary files.
ENV NEXT_TELEMETRY_DISABLED=1

# Build args for public environment variables (embedded at build time).
#
# CR-02 (Phase 26 code review) — WHY THE `ENV X=${X}` LINE IS LOAD-BEARING AND
# DANGEROUS, and why the guard below exists.
#
# Next inlines `process.env.NEXT_PUBLIC_*` at build time ONLY for the names that
# are PRESENT in the build environment. A name that is absent survives in the
# output as a literal `process.env.NAME` lookup, which the Node/edge runtime then
# resolves from the CONTAINER's environment. Verified directly on two built
# images (frontend/.next/server/edge/chunks/[root-of-the-server]*.js):
#
#   build-arg supplied   -> apiOrigin:"http://localhost:9090"            (inlined)
#   no ARG/ENV declared  -> keycloakOrigin:process.env.NEXT_PUBLIC_KEYCLOAK_URL||""
#                                                                        (runtime)
#
# So `ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}` with NO `--build-arg` is
# strictly WORSE than not declaring it: it makes the name present-and-EMPTY, so
# Next inlines `""` and FREEZES it. That is what CI shipped — no build-args at all
# — and the consequence was observed on an image built exactly as CI builds it:
#
#   connect-src 'self' https://api.stripe.com https://*.stripe.com
#   form-action 'self'
#
# `CSP_REPORT_ONLY` is unset in k8s, so that policy is ENFORCING: every dashboard
# XHR, every SSE stream open and the KDS wss://.../ws handshake are refused by the
# browser, while pods stay READY and the server-side smoke tests pass.
#
# The guard below therefore refuses to produce an image at all rather than bake an
# origin that cannot work (ARCHITECTURE_RULE_8: validate required values on load,
# fail fast with a clear error; GLOBAL_RULE_6: no environment-varying literal).
ARG NEXT_PUBLIC_API_URL
ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}

# Phase 18: customer identity realm base URL. NEXT_PUBLIC_* is inlined into the
# browser bundle at build time, so customer-auth.ts targets jtoye-customers.
ARG NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL
ENV NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL=${NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL}

# Phase 21 (ONBD-05/03, QA ONB-1): onboarding support channel + review SLA are
# NEXT_PUBLIC_* and therefore inlined at BUILD time — a runtime `environment:`
# value cannot reach the client bundle. Without these baked in, a REJECTED/SUSPENDED
# vendor's support link degrades to a dead-end "contact your account manager" with
# no channel. The code still degrades gracefully when they are genuinely absent.
ARG NEXT_PUBLIC_SUPPORT_EMAIL
ENV NEXT_PUBLIC_SUPPORT_EMAIL=${NEXT_PUBLIC_SUPPORT_EMAIL}
ARG NEXT_PUBLIC_SUPPORT_URL
ENV NEXT_PUBLIC_SUPPORT_URL=${NEXT_PUBLIC_SUPPORT_URL}
ARG NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS
ENV NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS=${NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS}

# ---------------------------------------------------------------------------
# CR-02 fail-fast gate. Runs BEFORE `next build`, because once next build has run
# the empty value is already inlined and no later check can undo it.
#
# REQUIRED vs OPTIONAL is not invented here — it mirrors the classification already
# reasoned out in frontend/lib/env-validation.ts:
#   requiredEnvVars  -> NEXT_PUBLIC_API_URL          (every browser fetch + the CSP)
#   compose `:?` + scripts/k8s-local-up.sh `die`
#                    -> NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL
#   optionalEnvVars  -> NEXT_PUBLIC_SUPPORT_EMAIL / _SUPPORT_URL /
#                       _ONBOARDING_REVIEW_SLA_DAYS  (resolveSupportChannel()
#                       degrades to plain copy, so absence is a soft misconfig —
#                       deliberately NOT gated here, per WR-03 of the Phase 21 review)
#
# NEXT_PUBLIC_KEYCLOAK_URL is deliberately NOT listed and deliberately has no
# ARG/ENV line: leaving it absent at build time is what keeps it RUNTIME-resolvable
# (proven above), which is how docker-compose supplies it today via `environment:`.
# Declaring an ARG/ENV for it would freeze it to "" exactly as happened to
# NEXT_PUBLIC_API_URL — do not "tidy" it into the block above.
#
# Both existing build paths already satisfy this gate, so it changes nothing for
# them: docker-compose.full-stack.yml passes NEXT_PUBLIC_API_URL literally and
# marks NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL required with `:?`, and
# scripts/k8s-local-up.sh reads api.url out of the rendered ConfigMap (PARSE ERROR
# if absent) and `die`s on an empty NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL.
#
# RUN is shell-form, so `$VAR` is expanded by /bin/sh from the layer's environment
# (Docker does no substitution in RUN) — the values are not embedded in the build
# command or the image history by this step.
# ---------------------------------------------------------------------------
RUN set -eu; \
    missing=""; \
    [ -n "${NEXT_PUBLIC_API_URL:-}" ] || missing="${missing} NEXT_PUBLIC_API_URL"; \
    [ -n "${NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL:-}" ] || missing="${missing} NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL"; \
    if [ -n "${missing}" ]; then \
      echo "" >&2; \
      echo "FATAL: refusing to build the frontend image — required NEXT_PUBLIC_* build-arg(s) are empty:" >&2; \
      for v in ${missing}; do echo "         --build-arg ${v}=<value>   (missing or empty)" >&2; done; \
      echo "" >&2; \
      echo "       These are INLINED into the bundle by 'next build' and CANNOT be corrected by a" >&2; \
      echo "       runtime env var afterwards. An empty NEXT_PUBLIC_API_URL bakes apiOrigin:\"\" into" >&2; \
      echo "       frontend/middleware.ts, so the ENFORCING Content-Security-Policy built by" >&2; \
      echo "       frontend/lib/security-headers.ts omits the API and websocket origins entirely:" >&2; \
      echo "           connect-src 'self' https://api.stripe.com https://*.stripe.com" >&2; \
      echo "       The pod would still pass every readiness probe and every server-side smoke test" >&2; \
      echo "       while the browser refused every API call. A build that cannot produce a working" >&2; \
      echo "       CSP must not produce an image (CR-02 / ARCHITECTURE_RULE_8)." >&2; \
      echo "" >&2; \
      echo "       CI supplies these from repo variables — see the frontend build-args in" >&2; \
      echo "       .github/workflows/ci-cd.yaml (job build-and-push)." >&2; \
      echo "" >&2; \
      exit 1; \
    fi; \
    echo "OK: required NEXT_PUBLIC_* build-args present (API_URL + CUSTOMER_KEYCLOAK_URL)"

RUN npm run build

# Stage 2: Runner
FROM node:20-alpine AS runner

# ---------------------------------------------------------------------------
# Trivy image gate (.github/workflows/ci-cd.yaml:721 — CRITICAL,HIGH,
# ignore-unfixed) fails on this base image with TWO independent finding groups.
# Both come from the base, NOT from the application: `tar` and `sigstore` have
# ZERO entries in frontend/package-lock.json, and Trivy reports the app's own
# node_modules as 0 vulnerabilities.
#
#   1. Alpine OS packages — libcrypto3 / libssl3 CVE-2026-45447 (OpenSSL heap
#      use-after-free), 3.5.6-r0, fixed in 3.5.7-r0.
#   2. npm's OWN bundled dependencies under /usr/local/lib/node_modules/npm —
#      16 findings incl. tar CVE-2026-59873 (CRITICAL, 6.2.1 -> 7.5.19), plus
#      sigstore, minimatch, brace-expansion, glob, cross-spawn.
#
# npm is build-time only. This stage runs `node server.js` and a `node -e`
# healthcheck, so nothing here invokes npm or npx — removing it deletes the
# entire finding class rather than chasing each CVE, and shrinks the image.
#
# Measured against the gate's exact flags (severity CRITICAL,HIGH,
# ignore-unfixed, exit-code 1). BOTH halves are required — neither alone passes:
#
#   node:20-alpine as-is        -> exit 1, 2 finding groups
#   apk upgrade only            -> exit 1, 1 group (npm's bundle remains)
#   rm -rf npm only             -> exit 1, 1 group (openssl remains)
#   both (this change)          -> exit 0, 0 findings
#
# Do not "tidy" either half away: each was shown to be load-bearing by removing
# it and watching the gate fail.
# ---------------------------------------------------------------------------
RUN apk --no-cache upgrade libcrypto3 libssl3 \
 && rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx

WORKDIR /app

# Set NODE_ENV to production
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

# Create non-root user
RUN addgroup --system --gid 1001 nodejs && \
    adduser --system --uid 1001 nextjs

# Copy standalone build (Next.js 16 optimization)
# This includes only necessary files, significantly reducing image size
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=builder --chown=nextjs:nodejs /app/public ./public

# Switch to non-root user
USER nextjs

# Expose port
EXPOSE 3000

# Set port environment variable
ENV PORT=3000
ENV HOSTNAME="0.0.0.0"

# Health check
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD node -e "require('http').get('http://localhost:3000/api/health', (r) => {process.exit(r.statusCode === 200 ? 0 : 1)})"

# Start Next.js server
CMD ["node", "server.js"]

# Labels for image metadata
LABEL org.opencontainers.image.title="J'Toye OaaS Frontend"
LABEL org.opencontainers.image.description="Next.js 16 frontend with NextAuth.js authentication"
LABEL org.opencontainers.image.vendor="J'Toye"
LABEL org.opencontainers.image.source="https://github.com/jtoye/oaas"
