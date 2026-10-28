FROM node:24-bookworm-slim AS web
WORKDIR /src/web
COPY web/package*.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS server
WORKDIR /src
COPY gradlew gradlew.bat build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY server/ server/
RUN chmod +x gradlew && ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=server /src/build/install/reviq/ ./
COPY --from=web /src/web/dist/ ./web/
ENV BIND_HOST=0.0.0.0 PORT=8088 WEB_ROOT=/app/web
USER 10001:10001
EXPOSE 8088
ENTRYPOINT ["/app/bin/reviq"]
