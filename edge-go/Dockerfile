# Multi-stage Dockerfile for edge-go service
# Optimized for production: minimal size (<15MB), security, fast startup

# Stage 1: Build stage
FROM golang:1.22-alpine AS builder

WORKDIR /app

# Install build dependencies
RUN apk add --no-cache git ca-certificates

# Copy go mod files and download dependencies (cached layer)
COPY go.mod go.sum ./
RUN go mod download && go mod verify

# Copy source code
COPY . .

# Build static binary with optimizations
# CGO_ENABLED=0: Static linking (no C dependencies)
# -ldflags="-s -w": Strip debug symbols for smaller binary
# -trimpath: Remove file system paths for reproducible builds
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -ldflags="-s -w" -trimpath \
    -o edge ./cmd/edge

# Stage 2: Runtime stage (scratch = minimal image)
FROM scratch

# Copy CA certificates for HTTPS requests to Keycloak/Core API
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/

# Copy binary from builder
COPY --from=builder /app/edge /edge

# Expose application port
EXPOSE 8080

# Health check (note: scratch doesn't have curl, health is via /health endpoint)
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD ["/edge", "health-check"]

# Start the application
ENTRYPOINT ["/edge"]

# Labels for image metadata
LABEL org.opencontainers.image.title="J'Toye OaaS Edge Service"
LABEL org.opencontainers.image.description="Go API Gateway with rate limiting and circuit breakers"
LABEL org.opencontainers.image.vendor="J'Toye"
LABEL org.opencontainers.image.source="https://github.com/jtoye/oaas"

# Expected size: 10-15MB (ultra-lightweight)
