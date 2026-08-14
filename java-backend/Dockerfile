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

# APP_ENV is set here, not left to the deployment manifest (audit OPUS5
# SEC-01): the image itself must be safe before any manifest is written. With
# APP_ENV=production the password-less dev login is off unconditionally, and
# ProductionSafetyGuard refuses to boot unless SECRET_KEY, COOKIE_SECURE and
# ORACLE_DB_PASSWORD are all overridden with real values -- so a
# half-configured deployment crash-loops loudly instead of coming up with the
# admin bypass open. Same fail-safe posture as the root docker-compose.yml,
# which falls back to APP_ENV=production when .env is missing.
ENV APP_ENV=production
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD wget -q -O- http://localhost:8080/api/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
