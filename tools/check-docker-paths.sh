#!/bin/sh
#
# Does the image copy a path the build actually produces?
#
# WHY THIS EXISTS. Renaming the published archive with `distributionBaseName`
# also renamed the directory `installDist` writes to — build/install/server
# became build/install/musicd-share-card-server. The old directory was still
# sitting in the build tree locally, so everything kept working here while the
# image build failed in CI with "not found". A one-line path that two different
# files have to agree about, where one of them cannot be built on this machine,
# is exactly the thing to check mechanically.
#
# Run it after :server:installDist.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
FAILED=0

grep -oE 'COPY --from=build /src/[^ ]+' Dockerfile | while read -r _ _ path; do
  local_path="${path#/src/}"
  if [ -e "$local_path" ]; then
    echo "ok   the image copies $local_path, which the build produced"
  else
    echo "FAIL the image copies $local_path, which the build did not produce"
    echo "     what is actually there:"
    ls -1 "$(dirname "$local_path")" 2>/dev/null | sed 's/^/       /' || echo "       (nothing)"
    exit 1
  fi
done || FAILED=1

[ "$FAILED" = "0" ] || exit 1
echo ""
echo "Dockerfile paths check passed."
