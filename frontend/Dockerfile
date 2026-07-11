# Multi-stage Dockerfile for frontend (Next.js 16)
# Optimized for production: standalone build, minimal size

# Stage 1: Builder
FROM node:26-alpine AS builder

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

# Build args for public environment variables (embedded at build time)
ARG NEXT_PUBLIC_API_URL
ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}

# Phase 18: customer identity realm base URL. NEXT_PUBLIC_* is inlined into the
# browser bundle at build time, so customer-auth.ts targets jtoye-customers.
ARG NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL
ENV NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL=${NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL}

RUN npm run build

# Stage 2: Runner
FROM node:26-alpine AS runner

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
