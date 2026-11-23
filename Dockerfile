# Race Manager + AMS2 dedicated server (run under Wine) in one container.
# Race Manager is the main process: it launches the game server itself (`wine DedicatedServerCmd.exe`).
# The game server files are NOT baked into the image; mount them at /home/steam/ams2-server
# (see docker/download-server.sh).

# ---- Build the jar (frontend + backend). The Windows-only artifacts are skipped.
FROM eclipse-temurin:21-jdk@sha256:8ad118b146aa92da815b576441acd6b42b38236797ad0b6edc8c200b85bb0734 AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline -P '!windows-dist' || true
COPY src src
RUN ./mvnw -B -DskipTests -P '!windows-dist' package \
 && cp target/racemgr-*.jar /racemgr.jar

# ---- Runtime: Wine base image + Java 21 (JRE copied from the Temurin image)
FROM scottyhardy/docker-wine:latest@sha256:c61cfc490aaee10b96782b88733a0f3d0e7b425d93b4e96617d9ab9a2b6bcfd2

COPY --from=eclipse-temurin:21-jre@sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH

RUN apt-get update \
 && apt-get install -y --no-install-recommends tini curl ca-certificates \
 && apt-get clean && rm -rf /var/lib/apt/lists/*

# UID 1000 so a bind-mounted ./ams2-server owned by the usual first host user just works
RUN useradd -m -u 1000 steam \
 && mkdir -p /home/steam/ams2-server /app \
 && chown -R steam:steam /home/steam /app

COPY --from=build --chown=steam:steam /racemgr.jar /app/racemgr.jar
COPY --chown=steam:steam docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

USER steam
WORKDIR /home/steam

ENV WINEPREFIX=/home/steam/.wine \
    WINEARCH=win64 \
    WINEDEBUG=-all \
    RM_PATH_TO_DEDICATED_SERVER=/home/steam/ams2-server \
    RM_GAMESERVER_HOST=127.0.0.1 \
    RM_GAMESERVER_PORT=9000 \
    RM_GAMESERVER_LAUNCH_CMD="wine DedicatedServerCmd.exe" \
    RM_PORT=8080 \
    RM_BIND_ADDRESS=0.0.0.0

# 0.0.0.0 is the container-internal bind; the compose file publishes the port on 127.0.0.1 only.
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:${RM_PORT}/api > /dev/null || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "/app/entrypoint.sh"]
