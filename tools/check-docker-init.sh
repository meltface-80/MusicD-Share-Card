#!/bin/sh
# PID 1 MUST REAP, AND A JVM WILL NOT.
#
# WHY THIS EXISTS. `launch.sh` execs the Gradle start script, which execs java,
# so with nothing in front of it the JVM is PID 1 inside the container. The
# HEALTHCHECK is `runc exec`ed in every thirty seconds and the helper that
# starts it exits at once, so each finished check is reparented to PID 1 — and
# a JVM reaps only the children it forked itself. Every check therefore stayed
# in the process table as a zombie, for ever.
#
# Found in an htop off a real host, not here: nineteen defunct `bash` entries
# under the java process after about ten minutes, one per interval, their PIDs
# climbing. NOTHING VISIBLE BREAKS until the pid limit is reached and the
# container cannot fork at all, and `restart: unless-stopped` resets the count
# on every restart, so the ordinary way to find this is not to.
#
# The INVARIANT is that nothing may hand PID 1 straight to the launcher. What a
# static check can assert, and all it can assert without a Docker daemon — there
# is none in this working environment, which is why the Dockerfile has always
# been checked mechanically rather than run:
#
#   1. something stands in front of the launcher in ENTRYPOINT,
#   2. that something is an init, and it is installed in the image,
#   3. the ENTRYPOINT still ends at the launcher,
#   4. Compose asks for an init of its own,
#   5. tini specifically is told not to complain when it is nested under one.
#
# Whether the reaping then actually happens is the first real `docker run`, and
# that is said rather than implied.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DOCKERFILE="${1:-Dockerfile}"
COMPOSE="${2:-docker-compose.yml}"
LAUNCHER="/opt/sharecard/launch.sh"

# NAMED, NOT PATTERN MATCHED. "the first word is not the launcher" would pass
# for ENTRYPOINT ["/bin/sh", "-c", ...], which reaps nothing at all — so an
# init is one of these until somebody deliberately adds another. Swapping the
# init out should be an awkward act; that is the point of the list.
INITS="tini dumb-init docker-init catatonit s6-svscan"

fail=0
say() { printf '%s\n' "$*"; }
bad() { printf 'FAIL %s\n' "$*"; fail=1; }

[ -f "$DOCKERFILE" ] || { say "cannot find $DOCKERFILE"; exit 1; }
[ -f "$COMPOSE" ] || { say "cannot find $COMPOSE"; exit 1; }

# Comments are stripped from both files first. Each of them quotes the broken
# form on purpose, and a scan that reads prose is a scan that lies — the lesson
# is in CLAUDE.md, collected twice before this.
df=$(sed 's/#.*//' "$DOCKERFILE")
cf=$(sed 's/#.*//' "$COMPOSE")

entry=$(printf '%s\n' "$df" | grep -E '^[[:space:]]*ENTRYPOINT' || true)
entries=$(printf '%s\n' "$entry" | grep -c . || true)

if [ "$entries" -eq 0 ]; then
  # A scan that quietly matches nothing is how a check becomes decoration.
  bad "no ENTRYPOINT in $DOCKERFILE at all. Then PID 1 is whatever the base
     image declares, and nothing here can say whether it reaps."
elif [ "$entries" -gt 1 ]; then
  bad "$entries ENTRYPOINT lines in $DOCKERFILE. The last one wins, which is
     not a thing to leave a reader to work out:
$entry"
else
  first=$(printf '%s' "$entry" | sed -n 's/.*\[[[:space:]]*"\([^"]*\)".*/\1/p')
  if [ -z "$first" ]; then
    bad "cannot read the exec form out of: $entry
     ENTRYPOINT must be the JSON array form. The shell form wraps the whole
     thing in /bin/sh -c, which does not reap either."
  else
    base=${first##*/}
    case " $INITS " in
      *" $base "*)
        say "ok   PID 1 is $base, which reaps"
        # An ENTRYPOINT naming a binary the image does not install is a
        # container that will not start at all — and that is the one failure
        # here a careful reader can still miss, because the line looks right.
        if printf '%s\n' "$df" | grep -v 'ENTRYPOINT' | grep -q "$base"; then
          say "ok   $base is installed by the image"
        else
          bad "ENTRYPOINT runs $first and nothing in $DOCKERFILE installs
     $base. The container would not start."
        fi
        ;;
      *)
        if [ "$first" = "$LAUNCHER" ]; then
          bad "ENTRYPOINT hands PID 1 straight to $LAUNCHER, which execs the
     start script, which execs java. A JVM does not reap orphans, and the
     healthcheck is reparented to PID 1 every thirty seconds — so each one
     stays behind as a zombie until the container cannot fork. Put an init
     in front of it."
        else
          bad "ENTRYPOINT runs $first, which is not a known init
     ($INITS). If it really does reap orphans and forward signals, add it to
     INITS in this file — deliberately, and in the same change."
        fi
        ;;
    esac

    if printf '%s' "$entry" | grep -q "$LAUNCHER"; then
      say "ok   the ENTRYPOINT still ends at $LAUNCHER"
    else
      bad "the ENTRYPOINT no longer names $LAUNCHER, so the rollback the
     launcher exists for is not running: $entry"
    fi

    # tini is silent as PID 1 and WARNS when it is not, which it is not the
    # moment Compose's init: true puts Docker's own init above it. The warning
    # lands in the log this app tells people to read for their PIN. The env var
    # rather than -s: -s is parsed inside tini's #ifndef TINI_MINIMAL block and
    # would be passed through to the launcher by a minimal build.
    if [ "${base:-}" = "tini" ] && printf '%s\n' "$cf" | grep -qE '^[[:space:]]*init:[[:space:]]*true'; then
      if printf '%s\n' "$df" | grep -qE '^[[:space:]]*ENV[[:space:]]+TINI_SUBREAPER='; then
        say "ok   tini will not warn about being nested under Docker's init"
      else
        bad "tini runs under Compose's init: true, so it is PID 2 and will
     print 'Tini is not running as PID 1' on every start. Set
     ENV TINI_SUBREAPER=1 in $DOCKERFILE (not -s; see the note here)."
      fi
    fi
  fi
fi

if printf '%s\n' "$cf" | grep -qE '^[[:space:]]*init:[[:space:]]*true'; then
  say "ok   $COMPOSE asks Docker for an init"
else
  bad "$COMPOSE does not set init: true. The image's own init covers this
     today, but an entrypoint: override here would remove it and take the
     reaping with it, silently."
fi

# Not a failure, and it is the reason the rest of this file is not optional: a
# healthcheck is what makes the reparenting CONSTANT rather than occasional.
if printf '%s\n' "$df" | grep -q 'HEALTHCHECK'; then
  say "note the HEALTHCHECK is what reparents a process to PID 1 every 30s"
else
  say "note no HEALTHCHECK — but docker exec reparents the same way, so the"
  say "     init above is still what keeps the process table from filling"
fi

if [ "$fail" -ne 0 ]; then
  printf '\nContainer init checks FAILED.\n'
  exit 1
fi
printf '\nContainer init checks passed.\n'
