# Build from the repo root: docker build -f proxy/Dockerfile .
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle :proxy:installDist --no-daemon -q

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S qg && adduser -S qg -G qg
COPY --from=build /src/proxy/build/install/proxy /app
USER qg
EXPOSE 6432
ENTRYPOINT ["/app/bin/proxy"]
