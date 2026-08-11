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

# All values below are dev-only defaults baked into application.yml itself
# (see PortalProperties/application.yml) -- a real deployment overrides
# every one of these via env vars, exactly like the root Dockerfile's
# Python app does for APP_ENV/SECRET_KEY/POSTGRES_PASSWORD. ProductionSafetyGuard
# refuses to boot if APP_ENV=production is set without also overriding
# SECRET_KEY/COOKIE_SECURE.
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD wget -q -O- http://localhost:8080/api/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
