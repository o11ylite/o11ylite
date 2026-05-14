# O11yLite Production Dockerfile
#
# Multi-stage build:
# 1. frontend-build: Compile TypeScript/React with Vite
# 2. backend-build: Build Clojure uberjar
# 3. runtime: Final image with s6-overlay, Caddy, and Java runtime

# =============================================================================
# Stage 1: Frontend Build
# =============================================================================
FROM node:24-alpine AS frontend-build

WORKDIR /app/frontend

# Install dependencies first (better layer caching)
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

# Copy source and build
COPY frontend/ ./
RUN npm run build

# =============================================================================
# Stage 2: Backend Build
# =============================================================================
# Use non-Alpine image because grpc-java plugin requires glibc
FROM clojure:temurin-21-tools-deps AS backend-build

# Application version baked into the uberjar (override at build time)
ARG VERSION=dev

# Install build dependencies for protobuf compilation
RUN apt-get update && apt-get install -y --no-install-recommends make curl unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app/backend

# Copy deps.edn and Makefile first for dependency caching
COPY backend/deps.edn backend/build.clj backend/Makefile ./

# Download dependencies
RUN clojure -P && clojure -P -T:build/task

# Download OpenTelemetry protos and compile to Java classes
# Run as separate make invocations because PROTO_FILES is evaluated at parse time
RUN make otel-proto-download && make proto-compile

# Copy source code and resources
COPY backend/src ./src
COPY backend/resources ./resources

# Copy frontend manifest to backend resources for Inertia integration
COPY --from=frontend-build /app/frontend/dist/.vite ./resources/.vite

# Build uberjar with version baked in
RUN clojure -J-Do11ylite.version=${VERSION} -T:build/task uberjar

# =============================================================================
# Stage 3: Runtime Image
# =============================================================================
# Use Debian-based image for DuckDB glibc compatibility
FROM eclipse-temurin:25.0.3_9-jre-noble AS runtime

# s6-overlay version
ARG S6_OVERLAY_VERSION=3.2.0.2
ARG TARGETARCH

# Install s6-overlay (architecture-aware)
ADD https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-noarch.tar.xz /tmp
RUN apt-get update && apt-get install -y --no-install-recommends xz-utils curl \
    && if [ "$TARGETARCH" = "arm64" ]; then \
         curl -sSL -o /tmp/s6-overlay-arch.tar.xz \
           "https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-aarch64.tar.xz"; \
       else \
         curl -sSL -o /tmp/s6-overlay-arch.tar.xz \
           "https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-x86_64.tar.xz"; \
       fi \
    && tar -C / -Jxpf /tmp/s6-overlay-noarch.tar.xz \
    && tar -C / -Jxpf /tmp/s6-overlay-arch.tar.xz \
    && rm /tmp/s6-overlay-*.tar.xz \
    && apt-get purge -y xz-utils curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

# Install Caddy
RUN apt-get update && apt-get install -y --no-install-recommends caddy \
    && rm -rf /var/lib/apt/lists/*

# Create app user and directories (pin UID/GID so Helm fsGroup can reference a stable value)
RUN groupadd -r -g 998 o11ylite && useradd -r -u 998 -g o11ylite o11ylite \
    && mkdir -p /app /data /config \
    && chown -R o11ylite:o11ylite /app /data /config

LABEL org.opencontainers.image.source=https://github.com/o11ylite/o11ylite

WORKDIR /app

# Copy backend uberjar
COPY --from=backend-build /app/backend/target/o11ylite-backend-standalone.jar /app/o11ylite.jar

# Copy frontend static assets for Caddy to serve
COPY --from=frontend-build /app/frontend/dist /app/frontend

# Copy Caddyfile
COPY docker/Caddyfile /etc/caddy/Caddyfile

# Copy s6-overlay service definitions
COPY docker/s6-rc.d /etc/s6-overlay/s6-rc.d

# Environment configuration
ENV O11YLITE_DATA_PATH=/data \
    O11YLITE_WEB_PORT=3000 \
    O11YLITE_WEB_HOST=0.0.0.0 \
    O11YLITE_OTEL_GRPC_PORT=4317 \
    O11YLITE_ASSET_BASE_URL=/frontend \
    O11YLITE_FRONTEND_MANIFEST_PATH=.vite/manifest.json \
    # s6-overlay configuration
    S6_KILL_GRACETIME=10000 \
    S6_BEHAVIOUR_IF_STAGE2_FAILS=2

EXPOSE 80

# Data volume
VOLUME ["/data"]

# s6-overlay entrypoint (must be PID 1)
# Note: Use `docker stop` to gracefully stop the container (responds to SIGTERM).
# Ctrl-C (SIGINT) is not supported by s6-overlay by design.
ENTRYPOINT ["/init"]
