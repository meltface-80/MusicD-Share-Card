# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build ----
#
# THE ANDROID MODULE IS LEFT OUT, and that is the whole reason this stage is
# small. -Psharecard.serverOnly=true drops `:app` from the build (see
# settings.gradle.kts), so no Android Gradle Plugin and no SDK is fetched to
# produce a program that could not run on a phone anyway.
#
# app/ is still copied, twice and deliberately: build.gradle.kts because the
# version this reports comes from it and there must be ONE version, and
# src/main/assets because that is the web app itself. The page is not copied
# into this module — it is put on its classpath — so there is one card and not
# two that drift.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY core core
COPY server server
COPY app/build.gradle.kts app/build.gradle.kts
COPY app/src/main/assets app/src/main/assets

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -Psharecard.serverOnly=true :server:installDist

# -------------------------------------------------------------- runtime ----
FROM eclipse-temurin:17-jre

# NOT ROOT. This process binds a LAN port, answers anything on the network and
# writes a Discord webhook URL to disk; none of that wants uid 0 behind it. The
# cost is that a bind mount created by hand is owned by somebody else on a first
# run — so the server SAYS SO, by uid, with the chown to run, rather than
# quietly forgetting everything on each restart.
RUN useradd --system --uid 10001 --create-home --home-dir /home/sharecard sharecard

COPY --from=build /src/server/build/install/server /opt/sharecard

ENV SHARECARD_DATA=/data \
    SHARECARD_PORT=8747 \
    LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

RUN mkdir -p /data && chown sharecard:sharecard /data
VOLUME ["/data"]
USER sharecard

# The port is fixed rather than picked, because the URL is meant to be TYPED
# into another device's browser. EXPOSE documents it; with host networking —
# which is what discovery needs, see the README — it is not what publishes it.
EXPOSE 8747

# /api/health IS THE ONE ROUTE THAT TOUCHES NO NETWORK, which is exactly what a
# check running every thirty seconds for the life of the container must not do:
# this app's standing rule is that it never polls the household. Written with
# bash's own /dev/tcp so the image needs no curl.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${SHARECARD_PORT:-8747}; \
        printf "GET /api/health HTTP/1.0\r\n\r\n" >&3; grep -q "\"ok\":true" <&3'

ENTRYPOINT ["/opt/sharecard/bin/server"]
