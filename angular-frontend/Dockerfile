# STAGE 1: Builder
FROM node:22.22.3-alpine AS builder
WORKDIR /build

# Cache npm install in its own layer, invalidated only by package*.json
# changes -- same reasoning as java-backend/Dockerfile's separate pom.xml
# COPY before the rest of the source.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# STAGE 2: Runtime
FROM nginx:1.27-alpine AS runtime

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
# NOT VERIFIED BY A BUILD: the container this was written in has the Docker
# CLI but no daemon. Verify with
#   docker build -t magti-fe . && docker run --rm -p 8080:8080 magti-fe
# and check the page serves. If the entrypoint fails on a path not covered
# below, the purpose-built alternative is to base this stage on
# nginxinc/nginx-unprivileged:1.27-alpine, which needs none of these chowns
# and already defaults to port 8080.
RUN chown -R nginx:nginx /var/cache/nginx /var/run /etc/nginx/conf.d /usr/share/nginx/html \
    && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid

USER nginx

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O- http://localhost:8080/ || exit 1
