#!/bin/sh
#
# Which build runs, and what happens when an update will not start.
#
# A CONTAINER THAT CANNOT BOOT IS WORSE THAN ONE THAT IS OUT OF DATE, so the
# whole of this file is arranged around never being stuck. Three markers in the
# data directory, and the image's own build underneath all of them:
#
# The builds themselves sit in $UPDATES/versions/<version>, one level below the
# markers, so that a version named after a marker cannot collide with it.
#
#   pending  written by the app once a download is unpacked and verified
#   trying   written HERE, before running a pending build, and never cleared
#            here — only a build that gets far enough to serve clears it
#   active   written by the app once it is serving, meaning "this one is good"
#
# So a build that crashes on startup leaves `trying` behind, the next boot sees
# it, throws that version away and falls back. The worst case is the build that
# came with the image, which is known to run because it is what shipped.
set -eu

DATA="${SHARECARD_DATA:-/data}"
UPDATES="$DATA/updates"
VERSIONS="$UPDATES/versions"
BAKED="/opt/sharecard/bin/server"

say() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) I Launcher     $*"; }

mkdir -p "$UPDATES" 2>/dev/null || true

# A leftover `trying` means the last boot ran an update that never served.
if [ -f "$UPDATES/trying" ]; then
  bad="$(cat "$UPDATES/trying" 2>/dev/null || true)"
  say "update $bad did not start; going back to the previous build"
  rm -f "$UPDATES/trying"
  [ -n "$bad" ] && rm -rf "$VERSIONS/$bad"
fi

# A pending build gets exactly one attempt, recorded before it is taken.
if [ -f "$UPDATES/pending" ]; then
  want="$(cat "$UPDATES/pending" 2>/dev/null || true)"
  rm -f "$UPDATES/pending"
  if [ -n "$want" ] && [ -x "$VERSIONS/$want/bin/server" ]; then
    say "starting update $want"
    echo "$want" > "$UPDATES/trying"
    exec "$VERSIONS/$want/bin/server" "$@"
  fi
  say "pending update $want is not runnable; ignoring it"
fi

# The last build that proved it works.
if [ -f "$UPDATES/active" ]; then
  have="$(cat "$UPDATES/active" 2>/dev/null || true)"
  if [ -n "$have" ] && [ -x "$VERSIONS/$have/bin/server" ]; then
    exec "$VERSIONS/$have/bin/server" "$@"
  fi
  say "recorded build $have has gone; falling back to the one in the image"
fi

exec "$BAKED" "$@"
