# Build context: repository root. Built for linux/amd64 by scripts/build-bundle.sh.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml backend/pom.xml
COPY zones.geojson zones.geojson
COPY backend/src backend/src
RUN --mount=type=cache,target=/root/.m2 mvn -f backend/pom.xml -B -q -DskipTests package

FROM eclipse-temurin:21.0.6_7-jre-jammy
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
COPY --from=build /src/backend/target/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
# No curl in the image: the health check talks HTTP over bash's /dev/tcp.
HEALTHCHECK --interval=5s --timeout=3s --start-period=60s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /health HTTP/1.0\r\n\r\n" >&3 && grep -q "\"UP\"" <&3'
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
