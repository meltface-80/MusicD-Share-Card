#!/bin/sh
# A CACHE MAY NEVER BREAK THE THING IT CACHES.
#
# WHY THIS EXISTS. `docker/build-push-action` was given `cache-to: type=gha,
# mode=max` and nothing else, so a refusal from the GitHub Actions cache service
# ended the whole step:
#
#   #45 ERROR: failed to reserve cache
#   ERROR: failed to build: failed to solve: failed to reserve cache
#
# That was AFTER the image had been built for both architectures and pushed to
# ghcr.io — every real output of the job had succeeded, the push of `:latest`
# and `:1.0.15` included, and the job went red for a layer cache it could not
# write. Reported as a failing check on a commit whose sibling push build was
# green on the same SHA.
#
# The cause is not the code and cannot be fixed in it: a run can be handed a
# READ-SCOPED token, and the runner prints exactly that at the top of its log —
#
#   Cache mode: read
#   GitHub Actions runtime token ACs
#   refs/heads/main: read
#
# — which is what a re-run of a pull request's run gets once that pull request
# has merged. Nothing in this repository decides that, so the only thing to do
# with it is survive it.
#
# This is the TtlCache rule one layer out, and it is worth saying in the same
# words: a full disk, a file written by a version that shaped it differently, a
# permission that changed underneath — every one of those has to end as "we did
# not remember that one", never as a failure of the work.
#
# The INVARIANT is asserted, not the line: every cache export in the workflow
# must be non-fatal, so an exporter added later for some other job is covered
# without this check being edited. A cache-to that must be allowed to fail the
# build would be a deliberate decision, and it should be an awkward one.
set -eu

WORKFLOW="${1:-.github/workflows/build.yml}"
fail=0

say() { printf '%s\n' "$*"; }
bad() { printf 'FAIL %s\n' "$*"; fail=1; }

[ -f "$WORKFLOW" ] || { say "cannot find $WORKFLOW"; exit 1; }

# Comments are stripped first. This file quotes `cache-to: type=gha,mode=max`
# above on purpose, and a check that reads prose is a check that lies — the
# lesson is already in CLAUDE.md, collected twice.
exports=$(sed 's/#.*//' "$WORKFLOW" | grep -n 'cache-to:' || true)

if [ -z "$exports" ]; then
  bad "no cache-to: in $WORKFLOW at all. If the layer cache was removed
     deliberately then this check has nothing left to protect and should go
     with it — but do not delete it to make a red build green."
else
  # `while` in a pipeline runs in a subshell, so counting the offenders here
  # rather than setting a flag inside a loop is not tidiness — a flag set in
  # there does not survive the pipe, and the check passes having found them.
  offenders=$(printf '%s\n' "$exports" | grep -v 'ignore-error=true' || true)
  if [ -n "$offenders" ]; then
    bad "a cache export can fail the build:
$offenders
     Add ignore-error=true. A refused cache write must be a warning; the image
     it would have sped up has already been built."
  else
    say "ok   all $(printf '%s\n' "$exports" | wc -l | tr -d ' ') cache export(s) are non-fatal"
  fi
fi

if [ "$fail" -ne 0 ]; then
  printf '\nCI cache checks FAILED.\n'
  exit 1
fi
printf '\nCI cache checks passed.\n'
