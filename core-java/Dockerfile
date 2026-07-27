# Multi-stage Dockerfile for core-java service
# Optimized for production: small size, security, fast startup

# Stage 1: Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradlew.bat .
COPY gradle gradle/
COPY build.gradle.kts ./
COPY settings.gradle.kts ./
COPY core-java/build.gradle.kts core-java/

# Download dependencies (cached layer)
RUN ./gradlew core-java:dependencies --no-daemon || true

# Copy source code
COPY core-java/src core-java/src

# Build the application (skip tests for faster builds - tests run in CI)
RUN ./gradlew :core-java:bootJar -x test --no-daemon

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre-alpine

# Install curl for health checks, plus musl-native cwebp/dwebp/gif2webp for the
# Phase 24 WebP transcode step (IMG-02). scrimage-webp's BUNDLED cwebp binaries
# are glibc-linked and will NOT exec on this Alpine (musl) image — the apk
# package is musl-native. The JVM is pointed at it via the
# -Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin flag in JAVA_OPTS below.
#
# The `apk upgrade` of libexpat + p11-kit is a security fix, not a convenience.
# eclipse-temurin:21-jre-alpine ships Alpine 3.23 packages predating their fixes,
# which the CI Trivy image gate (.github/workflows/ci-cd.yaml, fail on fixable
# CRITICAL/HIGH) rejects:
#   libexpat      2.8.1-r0  -> 2.8.2-r0   CVE-2026-56131, CVE-2026-56407, CVE-2026-56408
#   p11-kit(-trust) 0.25.5-r2 -> 0.26.2-r0  CVE-2026-2100
# Both fixed versions were confirmed available in the Alpine 3.23 `main` repo
# before this was written. The packages are NAMED deliberately — a blanket
# `apk upgrade` would silently move all ~85 packages on every rebuild, making the
# image non-reproducible and any future CVE regression untraceable. When the
# Temurin base is respun with these fixes this line becomes a cheap no-op; drop
# it only after confirming the base image ships >= the versions above.
RUN apk add --no-cache curl libwebp-tools \
 && apk upgrade --no-cache libexpat p11-kit p11-kit-trust

WORKDIR /app

# Create non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring

# Copy JAR from builder stage
# Note: Using build-local directory as configured in build.gradle.kts
COPY --from=builder /app/core-java/build-local/libs/*.jar app.jar

# Change ownership to non-root user
RUN chown -R spring:spring /app

# Switch to non-root user
USER spring:spring

# Expose application port
EXPOSE 9090

# Health check for Kubernetes liveness/readiness probes
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:9090/actuator/health || exit 1

# JVM tuning for containerized environments
# - UseContainerSupport: Respect container CPU/memory limits
# - MaxRAMPercentage: Use up to 75% of container memory for heap
# - UseG1GC: Low-pause garbage collector (good for APIs)
# - UseStringDeduplication: Reduce memory usage for duplicate strings
#
# NOTE (Issue #78 [P0-2]): do NOT set -Dspring.profiles.active here. Docker
# expands ${SPRING_PROFILES_ACTIVE:-...} at BUILD time (when the var is empty),
# baking a literal profile into the image; a -D system property then OVERRIDES
# the runtime SPRING_PROFILES_ACTIVE env var (system props outrank env vars in
# Spring), silently defeating the profile set by compose/k8s. Spring Boot reads
# the SPRING_PROFILES_ACTIVE environment variable natively, so the profile flows
# from the runtime environment with no -D needed.
#
# Phase 24 (IMG-02): -Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin makes
# scrimage-webp delegate WebP encode/decode to the musl-native system
# cwebp/dwebp installed by `apk add libwebp-tools` above, instead of the
# glibc-linked binaries scrimage bundles (which cannot exec on Alpine/musl).
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0 \
               -XX:+UseG1GC \
               -XX:MaxGCPauseMillis=200 \
               -XX:+UnlockExperimentalVMOptions \
               -XX:+UseStringDeduplication \
               -Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin \
               -Djava.security.egd=file:/dev/./urandom"

# Start the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# Labels for image metadata
LABEL org.opencontainers.image.title="J'Toye OaaS Core API"
LABEL org.opencontainers.image.description="Spring Boot 3 backend with multi-tenant isolation"
LABEL org.opencontainers.image.vendor="J'Toye"
LABEL org.opencontainers.image.source="https://github.com/jtoye/oaas"
