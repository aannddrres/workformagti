# STAGE 1: Builder
FROM node:22-alpine AS builder
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

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O- http://localhost:8080/ || exit 1
