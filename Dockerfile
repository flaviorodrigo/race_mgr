# Race Manager + AMS2 dedicated server (run under Wine) in one container.
# Race Manager is the main process: it launches the game server itself (`wine DedicatedServerCmd.exe`).
# By default the game server files are NOT in the image: mount them at /home/steam/ams2-server
# (see docker/download-server.sh). To have the build download them into the image instead, build the
# `with-server` target (see docker/build-with-server.sh); the last stage keeps the plain image the default.

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
FROM scottyhardy/docker-wine:latest@sha256:c61cfc490aaee10b96782b88733a0f3d0e7b425d93b4e96617d9ab9a2b6bcfd2 AS runtime

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
COPY --chown=steam:steam docker/entrypoint.sh docker/seed-server.sh /app/
RUN chmod +x /app/entrypoint.sh /app/seed-server.sh

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


# ---- Optional: download the AMS2 dedicated server during the build (target: with-server).
# SteamCMD logs in anonymously by default, which is enough for the dedicated server. To use a Steam account
# instead, set the STEAM_USERNAME build argument and give the password (and a Steam Guard code, if the account has
# one) as BuildKit secrets: they are never written to an image layer. Only the server files leave this stage.
# Keep the resulting image local: the server files belong to Reiza.
FROM steamcmd/steamcmd:latest@sha256:6e273dd38cbf5042bfd1cdaa01564fe991a765d4bd70479fc7f5ddb46d890ab8 AS ams2-download
ARG STEAM_USERNAME=anonymous
RUN --mount=type=secret,id=steam_password,required=false \
    --mount=type=secret,id=steam_guard_code,required=false \
    set -eu; \
    user="${STEAM_USERNAME:-anonymous}"; \
    if [ "$user" = anonymous ]; then \
        set -- +login anonymous; \
    else \
        [ -s /run/secrets/steam_password ] || { echo "STEAM_USERNAME is set: give its password as the steam_password secret" >&2; exit 1; }; \
        guard=""; [ -s /run/secrets/steam_guard_code ] && guard="$(cat /run/secrets/steam_guard_code)"; \
        set -- +login "$user" "$(cat /run/secrets/steam_password)" $guard; \
    fi; \
    steamcmd +@sSteamCmdForcePlatformType windows +force_install_dir /server "$@" +app_update 1338040 validate +quit; \
    # SteamCMD exits 0 even when the login or download failed, so check for the program itself
    test -f /server/DedicatedServerCmd.exe || { echo "The download failed (see the SteamCMD output above)" >&2; exit 1; }

# A stage of its own so a build can swap it for a folder: --build-context ams2-files=./some-folder
FROM scratch AS ams2-files
COPY --from=ams2-download /server /

FROM runtime AS with-server
USER root
COPY --from=ams2-files --chown=steam:steam / /opt/ams2-server
COPY --chown=steam:steam sample_files/server.cfg /opt/ams2-sample/server.cfg
USER steam
# On start the entrypoint copies these into the mounted /home/steam/ams2-server (see docker/seed-server.sh)
ENV RM_SEED_SERVER_FROM=/opt/ams2-server \
    RM_SEED_SERVER_SAMPLE_CFG=/opt/ams2-sample/server.cfg

# ---- Default target: the plain image (the game server files are mounted), same as before the stages above existed
FROM runtime
