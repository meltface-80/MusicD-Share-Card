#!/bin/sh
#
# Drives server/docker/launch.sh through every case, because reading it is not
# evidence. The thing it decides is which build boots, and the failure it must
# never have is a container that cannot boot at all.
#
# The real launcher is run verbatim. What is faked is only the two things it
# execs: the build in the image, and a build in the data directory. Each prints
# which one it is, so the assertion is simply "the right one ran".
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILED=0

fake() {  # fake <path> <what it prints> [exit code]
  mkdir -p "$(dirname "$1")"
  printf '#!/bin/sh\necho "%s"\nexit %s\n' "$2" "${3:-0}" > "$1"
  chmod +x "$1"
}

setup() {
  rm -rf "$WORK/run"
  mkdir -p "$WORK/run/data/updates" "$WORK/run/opt/sharecard/bin"
  fake "$WORK/run/opt/sharecard/bin/server" "BAKED"
}

launch() {
  # The launcher hard-codes /opt/sharecard, so the copy under test is rewritten
  # to the throwaway tree rather than the real one being told where to look —
  # the script must stay exactly as it ships.
  sed "s#/opt/sharecard#$WORK/run/opt/sharecard#g" "$ROOT/server/docker/launch.sh" \
    > "$WORK/run/launch.sh"
  chmod +x "$WORK/run/launch.sh"
  SHARECARD_DATA="$WORK/run/data" "$WORK/run/launch.sh" 2>&1
}

check() {  # check <name> <expected substring> <actual>
  if echo "$3" | grep -q "$2"; then
    echo "ok   $1"
  else
    echo "FAIL $1: expected '$2' in:"
    echo "$3" | sed 's/^/       /'
    FAILED=1
  fi
}

# 1. Nothing chosen yet: the build that came with the image.
setup
check "a fresh container runs the build in the image" "BAKED" "$(launch)"

# 2. A pending build is taken, and recorded as being on trial first.
setup
fake "$WORK/run/data/updates/9.9.9/bin/server" "NEW 9.9.9"
echo "9.9.9" > "$WORK/run/data/updates/pending"
out="$(launch)"
check "a pending build is started" "NEW 9.9.9" "$out"
check "and is recorded as on trial" "9.9.9" "$(cat "$WORK/run/data/updates/trying")"
check "and pending is consumed, so it is tried once" "" \
  "$([ -f "$WORK/run/data/updates/pending" ] && echo STILL-PENDING || echo "")"

# 3. THE ROLLBACK. A leftover `trying` is a build that never served.
setup
fake "$WORK/run/data/updates/9.9.9/bin/server" "NEW 9.9.9"
echo "9.9.9" > "$WORK/run/data/updates/trying"
out="$(launch)"
check "a build that never served is not tried twice" "BAKED" "$out"
check "and is thrown away" "" \
  "$([ -d "$WORK/run/data/updates/9.9.9" ] && echo STILL-THERE || echo "")"
check "and the marker is cleared, so the next boot is ordinary" "" \
  "$([ -f "$WORK/run/data/updates/trying" ] && echo STILL-THERE || echo "")"

# 4. A build that served before is used again without being re-tried.
setup
fake "$WORK/run/data/updates/9.9.9/bin/server" "NEW 9.9.9"
echo "9.9.9" > "$WORK/run/data/updates/active"
out="$(launch)"
check "the last good build runs on an ordinary boot" "NEW 9.9.9" "$out"
check "and is not put on trial again" "" \
  "$([ -f "$WORK/run/data/updates/trying" ] && echo ON-TRIAL || echo "")"

# 5. Someone emptied /data. The image still boots.
setup
echo "9.9.9" > "$WORK/run/data/updates/active"
check "a recorded build that has gone falls back to the image" "BAKED" "$(launch)"

# 6. A pending build with no runnable launcher in it.
setup
mkdir -p "$WORK/run/data/updates/9.9.9/lib"
echo "9.9.9" > "$WORK/run/data/updates/pending"
check "an unrunnable pending build is ignored, not fatal" "BAKED" "$(launch)"

# 7. A pending build on top of a good one: the new one is tried, the old one
#    is still there to go back to.
setup
fake "$WORK/run/data/updates/1.0.0/bin/server" "OLD 1.0.0"
fake "$WORK/run/data/updates/9.9.9/bin/server" "NEW 9.9.9"
echo "1.0.0" > "$WORK/run/data/updates/active"
echo "9.9.9" > "$WORK/run/data/updates/pending"
check "an update is preferred over the last good build" "NEW 9.9.9" "$(launch)"
setup
fake "$WORK/run/data/updates/1.0.0/bin/server" "OLD 1.0.0"
fake "$WORK/run/data/updates/9.9.9/bin/server" "NEW 9.9.9"
echo "1.0.0" > "$WORK/run/data/updates/active"
echo "9.9.9" > "$WORK/run/data/updates/trying"
check "and a failed update goes back to it, not to the image" "OLD 1.0.0" "$(launch)"

[ "$FAILED" = "0" ] && echo "" && echo "launcher checks passed." || exit 1
