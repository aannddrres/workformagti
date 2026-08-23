# STAGE 1: Builder
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# Cache Maven dependencies in their own layer, invalidated only by pom.xml
# changes -- same reasoning as the root Dockerfile's separate
# requirements.txt COPY before the rest of the source.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline -B

COPY src/ src/
RUN ./mvnw -q -DskipTests package -B

# STAGE 2: Runtime
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -g 1000 appgroup && adduser -D -u 1000 -G appgroup appuser

WORKDIR /app

RUN mkdir -p /app/uploads /app/logs && chown -R appuser:appgroup /app

COPY --from=builder --chown=appuser:appgroup /build/target/portal-backend-*.jar app.jar

USER appuser

# The image ships as production (SEC-01). This used to be absent entirely
# while application.yml defaulted app-env to "development", so the shipping
# image booted with the password-less dev login enabled and a Deployment
# manifest that merely forgot APP_ENV was enough to expose it. Stated here so
# the image is safe before any manifest is written; application.yml now
# defaults the same way, and this makes it visible in `docker inspect`.
#
# Consequence, by design: this container will NOT start until SECRET_KEY,
# ORACLE_DB_PASSWORD and COOKIE_SECURE=true are supplied. ProductionSafetyGuard
# rejects blank/short/placeholder secrets, the shipped dev database passwords,
# an insecure cookie, and any attempt to enable the dev login here. To run the
# image for local experimentation, pass APP_ENV=development explicitly.
ENV APP_ENV=production
# PR-11: -XX:+UseContainerSupport is on by default in JDK 21 and only makes
# the JVM READ the cgroup limit; on its own it still sizes the heap at 1/4 of
# it, so three quarters of whatever memory limit the Deployment sets goes
# unused. MaxRAMPercentage is the setting that actually spends it.
#
# 75% leaves room for what lives outside the heap -- metaspace, thread
# stacks, and the direct buffers PDFBox and POI use while building an export,
# which is the one workload here that allocates in bulk.
#
# ExitOnOutOfMemoryError because a JVM that has OOMed is not reliably alive:
# it is far better for Kubernetes to restart the pod than for it to keep
# passing the /api/health check while failing real requests.
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

# Liveness, deliberately -- NOT /api/health/ready. Docker's only response to a
# failed healthcheck is to mark the container unhealthy (and restart it under
# an orchestrator), and restarting the application because Oracle is
# unreachable turns a database outage into a crash loop. Readiness belongs on
# a Kubernetes readinessProbe pointed at /api/health/ready, which returns 503
# and takes the pod out of the load balancer without killing it (RTA-009).
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD wget -q -O- http://localhost:8080/api/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
