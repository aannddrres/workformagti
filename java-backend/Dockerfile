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
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD wget -q -O- http://localhost:8080/api/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
