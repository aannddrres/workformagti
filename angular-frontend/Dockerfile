# STAGE 1: Builder
FROM node:22.22.3-alpine AS builder
WORKDIR /build

# Cache npm install in its own layer, invalidated only by package*.json
# changes -- same reasoning as java-backend/Dockerfile's separate pom.xml
# COPY before the rest of the source.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Which angular.json configuration to build. "production" for every real
# deployment.
ARG BUILD_CONFIGURATION=production
RUN npm run build -- --configuration "$BUILD_CONFIGURATION"

# STAGE 2: Runtime
FROM nginx:1.30.5-alpine3.24@sha256:bf3201ab56f23e5954646379c775d511fc466e9f11376d9725361064ad07ed35 AS runtime

# The pinned base still contains libexpat 2.8.4-r0; Alpine has 2.8.5-r0.
# Apply current security fixes, then let the CI image scan catch new ones.
RUN apk upgrade --no-cache

# Default assumes a Kubernetes Service named "portal-backend" listening on
# 8080 (java-backend/Dockerfile's EXPOSE) -- override both at `docker run`/
# Kubernetes Deployment env time once IT confirms the real Service name.
ENV BACKEND_HOST=portal-backend
ENV BACKEND_PORT=8080

COPY --from=builder /build/dist/angular-frontend/browser /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

# Audit PR-07: this image had no USER directive, so nginx ran as root while
# the backend image correctly drops to appuser. Many clusters enforce
# runAsNonRoot, where that is not a hardening nicety but a pod that refuses
# to start.
#
# Nothing blocked it: nginx.conf.template:8 already listens on 8080, not 80,
# so no privileged port is involved. What is needed is write access to the
# three paths nginx and the official entrypoint touch at runtime --
# /var/cache/nginx (proxy/temp buffers), /var/run (pid file), and
# /etc/nginx/conf.d (where docker-entrypoint.sh renders the template through
# envsubst). The `nginx` user already exists in this base image.
# Verified on 2026-09-24 with the built runtime image: the non-root nginx
# served the full Playwright suite and upload boundary checks on an isolated
# Oracle-backed test stack. CI repeats the shipping-image path on Linux.
RUN chown -R nginx:nginx /var/cache/nginx /var/run /etc/nginx/conf.d /usr/share/nginx/html \
    && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid

USER nginx

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O- http://127.0.0.1:8080/ || exit 1
