#!/usr/bin/env bash
# Console-only filter: callers tee the complete --info stream to evidence first.
# Match entire known informational lines, never warning/error/task/outcome prefixes.
set -uo pipefail
noise="^[[:space:]]*(Caching disabled for task '[^']+' because:|Build cache is disabled|Caching has not been enabled for the task|Loading library manifest /.*\/AndroidManifest\.xml|Merging library manifest /.*\/AndroidManifest\.xml)[[:space:]]*$"
grep --line-buffered -Ev "$noise"
status=$?
# grep returns 1 when all input was noise (or empty); that is successful filtering.
if (( status == 1 )); then exit 0; fi
exit "$status"
