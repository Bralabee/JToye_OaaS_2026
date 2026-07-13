# Multi-stage Dockerfile for the MCP server (Node 20, TypeScript ESM)
# Mirrors frontend/Dockerfile's canonical node:20-alpine builder->runner shape.
# Divergence from the frontend analog: plain `tsc -> dist/` output (no Next.js
# standalone bundle) and EXPOSE 9100 (not 3000).

# Stage 1: Builder — compile TypeScript to dist/
FROM node:20-alpine AS builder

WORKDIR /app

# Install ALL dependencies (incl. dev: typescript) from the lockfile. Copying the
# manifest first lets Docker cache this layer until dependencies change.
COPY package.json package-lock.json* ./
RUN npm ci

# Copy source (node_modules is excluded via .dockerignore) and compile tsc -> dist/.
COPY . .
RUN npm run build

# Stage 2: Runner — production runtime, non-root, prod deps only
FROM node:20-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production

# Create non-root user (matches the frontend uid 1001 convention).
RUN addgroup --system --gid 1001 nodejs && \
    adduser --system --uid 1001 nodejs

# Install ONLY production dependencies from the same lockfile.
COPY package.json package-lock.json* ./
RUN npm ci --omit=dev

# Copy the compiled output from the builder stage.
COPY --from=builder --chown=nodejs:nodejs /app/dist ./dist

# Switch to the non-root user.
USER nodejs

# The MCP server listens on 9100 (index.ts: MCP_PORT default 9100).
EXPOSE 9100

# Liveness probe hits the server's own GET /health, which does NOT call core.
# Honors MCP_PORT (index.ts binds it, default 9100) — a hardcoded 9100 would
# leave an MCP_PORT-overridden container permanently unhealthy.
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD node -e "require('http').get('http://localhost:' + (process.env.MCP_PORT || 9100) + '/health', (r) => {process.exit(r.statusCode === 200 ? 0 : 1)}).on('error', () => process.exit(1))"

# Start the stateless Streamable HTTP host.
CMD ["node", "dist/index.js"]

# Labels for image metadata
LABEL org.opencontainers.image.title="J'Toye OaaS MCP Server"
LABEL org.opencontainers.image.description="Read-only Model Context Protocol server — Bearer pass-through over the core REST API"
LABEL org.opencontainers.image.vendor="J'Toye"
LABEL org.opencontainers.image.source="https://github.com/jtoye/oaas"
