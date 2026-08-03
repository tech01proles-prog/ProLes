# ═══════════════════════════════════════════════════════
# STAGE 1: Build Backend (Gradle)
# ═══════════════════════════════════════════════════════
FROM gradle:8.8-jdk21 AS builder
WORKDIR /app

COPY gradle.properties /home/gradle/.gradle/gradle.properties
COPY build.gradle.kts settings.gradle.kts ./
RUN gradle dependencies --no-daemon || true

COPY src ./src
COPY serviceAccountKey.json /app/serviceAccountKey.json

RUN gradle buildFatJar --no-daemon --console=plain \
    -Dorg.gradle.daemon=false \
    -Dorg.gradle.parallel=false \
    -Dorg.gradle.workers.max=1 \
    -Dorg.gradle.jvmargs="-Xmx1500m -XX:MaxMetaspaceSize=384m" \
    -Dkotlin.compiler.execution.strategy=in-process

# ═══════════════════════════════════════════════════════
# STAGE 2: Production Runtime
# ═══════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /app/build/libs/*-all.jar app.jar
COPY --from=builder /app/serviceAccountKey.json /app/serviceAccountKey.json

# 🎯 Копируем ПРЕДВАРИТЕЛЬНО СОБРАННЫЙ фронтенд
# (создан скриптом build-frontend.sh)
COPY dist /app/web-client/dist

RUN apk add --no-cache curl

EXPOSE 8080

ENV JAVA_OPTS="-Xmx1g -XX:MaxMetaspaceSize=512m -XX:+UseG1GC"

HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]