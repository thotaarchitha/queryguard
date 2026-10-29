# syntax=docker/dockerfile:1.7
# Build from the repo root: docker build -f admin-api/Dockerfile .
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=locked \
    ./gradlew :admin-api:bootJar --no-daemon -q -x test

FROM eclipse-temurin:21-jre-alpine
# fixed numeric uid: Kubernetes runAsNonRoot can only verify a number
RUN addgroup -S -g 10001 qg && adduser -S -u 10001 -G qg qg
WORKDIR /app
COPY --from=build /src/admin-api/build/libs/admin-api.jar /app/admin-api.jar
COPY infra/rules/default-rules.yml /etc/queryguard/rules.yml
ENV QG_RULES_FILE=/etc/queryguard/rules.yml
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD ["wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8080/actuator/health/readiness"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/admin-api.jar"]
