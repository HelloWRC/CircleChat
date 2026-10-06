# syntax=docker/dockerfile:1

FROM node:24-bookworm-slim AS frontend

WORKDIR /build/src/client

RUN npm install --global pnpm@11.7.0

COPY src/client/package.json src/client/pnpm-lock.yaml src/client/pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/pnpm/store \
    pnpm install --frozen-lockfile --store-dir /pnpm/store

COPY src/client/ ./
ENV VITE_API_BASE_URL=/api
RUN pnpm build

FROM eclipse-temurin:25-jdk-noble AS backend

WORKDIR /build

COPY gradlew build.gradle settings.gradle ./
COPY gradle/ ./gradle/
COPY src/main/ ./src/main/
COPY --from=frontend /build/src/client/dist/ ./src/client/dist/

# processResources still embeds the frontend; Node.js is only needed above.
RUN --mount=type=cache,target=/root/.gradle \
    sh ./gradlew --no-daemon bootJar -x buildFrontend -x pnpmInstall

FROM eclipse-temurin:25-jre-noble AS runtime

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 circlechat \
    && useradd --system --uid 10001 --gid circlechat --no-create-home circlechat

WORKDIR /app

COPY --from=backend --chown=circlechat:circlechat /build/build/libs/*.jar ./app.jar

USER circlechat
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
