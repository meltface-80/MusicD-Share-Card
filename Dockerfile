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

# PID 1 MUST REAP, AND A JVM WILL NOT.
#
# launch.sh execs the start script and that execs java, so with nothing in
# front of it THE JVM IS PID 1. The HEALTHCHECK below is `runc exec`ed into the
# container every thirty seconds, and the helper that starts it exits at once —
# so every check is REPARENTED TO PID 1 when it finishes. The JVM only reaps
# the children it forked itself, so each finished check then sits in the
# process table as a zombie, for ever.
#
# SEEN ON A REAL HOST, which is the only reason this was found at all: nineteen
# defunct `bash` entries under the java process after about ten minutes, one
# per interval, PIDs climbing. Nothing is broken by it until the pid limit is
# reached and the container can no longer fork anything — and `restart:
# unless-stopped` hides even that by resetting the count on every restart.
# A leak whose only symptom is a process table nobody looks at.
#
# tini is PID 1 instead: it reaps orphans and forwards signals to the launcher
# below it, and exits with the launcher's own status so the rollback in
# launch.sh and the restart policy both behave exactly as before.
#
# IT IS IN THE IMAGE AND NOT ONLY IN COMPOSE, because the README's `docker run`
# names no flag and somebody pulling this image has read neither file.
# docker-compose.yml asks for `init: true` as well; see the note there.
#
# THE ENV VAR AND NOT THE `-s` FLAG. Both ask tini to register as a child
# subreaper, which is what stops it WARNING that it is not PID 1 — and it is
# not, the moment docker-compose.yml's `init: true` puts Docker's own init
# above it. That warning is three lines into the log this README tells people
# to read for their PIN, on every start, about nothing being wrong.
#
# The two are not equally safe to reach for. `-s` is parsed inside tini's
# `#ifndef TINI_MINIMAL` block, so a build made with TINI_MINIMAL passes `-s`
# STRAIGHT THROUGH to the launcher and on to the server; TINI_SUBREAPER is read
# by `parse_env`, which is compiled either way. Ubuntu's own tini 0.19.0 does
# accept `-s` — measured, not assumed — so this is a hazard the package avoids
# today rather than one it has. The env var costs nothing and cannot be wrong
# if that ever changes, which is the only reason to prefer it.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tini \
 && rm -rf /var/lib/apt/lists/*
ENV TINI_SUBREAPER=1

# THE DIRECTORY IS NAMED AFTER THE DISTRIBUTION, NOT THE MODULE, and that
# caught me out: setting `distributionBaseName` so the published archive is
# called musicd-share-card-server-<version>.zip ALSO moved installDist from
# build/install/server to build/install/musicd-share-card-server. The old path
# lingered in the build directory here, so a stale copy kept running and kept
# looking fine; in a clean image build this COPY would simply have failed.
COPY --from=build /src/server/build/install/musicd-share-card-server /opt/sharecard
# The launcher decides which build runs: the one in this image, or a newer one
# the app has downloaded into /data. See the script for the rollback.
COPY server/docker/launch.sh /opt/sharecard/launch.sh
RUN chmod +x /opt/sharecard/launch.sh

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

# tini, never the launcher directly — see the reaping note above. The `--`
# ends tini's own arguments, so nothing in the launcher's line can ever be
# eaten as one of tini's.
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/sharecard/launch.sh"]
