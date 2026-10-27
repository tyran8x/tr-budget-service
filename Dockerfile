# ==============================================================================
# STAGE 1: BUILD APPLICATION
# ==============================================================================
FROM maven:3.10.0-eclipse-temurin-25-alpine AS builder
LABEL maintainer="tyran8x" \
      org.opencontainers.image.authors="tyran8x@gmail.com"

WORKDIR /app

COPY settings.xml pom.xml ./

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s settings.xml dependency:go-offline dependency:resolve-plugins

COPY src ./src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s settings.xml package spring-boot:repackage -DskipTests && \
    java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination target/extracted

# ==============================================================================
# STAGE 2: RUNTIME IMAGE
# ==============================================================================
FROM eclipse-temurin:25-jre-alpine
LABEL maintainer="tyran8x" \
      org.opencontainers.image.authors="tyran8x@gmail.com"

ENV APP_HOME=/app \
    TZ=Asia/Ho_Chi_Minh \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

WORKDIR $APP_HOME

RUN adduser -D -h ${APP_HOME} -s /bin/sh appuser && \
    apk update && apk add --no-cache curl tzdata && \
    rm -rf /var/cache/apk/* && \
    mkdir -p /opt/logs && \
    chown -R appuser:appuser ${APP_HOME} /opt/logs

COPY --from=builder --chown=appuser:appuser /app/target/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appuser /app/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appuser /app/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appuser /app/target/extracted/application/ ./

EXPOSE 8503

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8503/actuator/health || exit 1

USER appuser

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
