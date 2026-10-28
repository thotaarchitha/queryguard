# syntax=docker/dockerfile:1.7
# Build from the repo root: docker build -f proxy/Dockerfile .
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=locked \
    ./gradlew :proxy:installDist --no-daemon -q -x test

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S qg && adduser -S qg -G qg
COPY --from=build /src/proxy/build/install/proxy /app
COPY infra/rules/default-rules.yml /etc/queryguard/rules.yml
ENV QG_RULES_FILE=/etc/queryguard/rules.yml \
    JAVA_OPTS="-XX:MaxRAMPercentage=70"
USER qg
EXPOSE 6432 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8081/health/ready >/dev/null || exit 1
ENTRYPOINT ["/app/bin/proxy"]
