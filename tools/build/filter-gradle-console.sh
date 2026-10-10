#!/usr/bin/env bash
# Console-only filter: callers tee the complete --info stream to evidence first.
# Match entire known informational lines, never warning/error/task/outcome prefixes.
set -uo pipefail
noise="^[[:space:]]*(Caching disabled for task '[^']+' because:|Build cache is disabled|Caching has not been enabled for the task|Loading library manifest /.*\/AndroidManifest\.xml|Merging library manifest /.*\/AndroidManifest\.xml)[[:space:]]*$"
# Hold only transform headers until their reason arrives. Keep the entire block
# for every other reason, and keep incomplete headers at EOF. Preserve CRLF.
awk -v noise="$noise" '
function emit(line) { print line; fflush() }
BEGIN { transform = "^Caching disabled for [[:alnum:]_$]+Transform: .+ because:$" }
{
    current = $0
    sub(/\r$/, "", current)
    if (pending != "") {
        if (current == "  Caching not enabled.") {
            pending = ""
            next
        }
        emit(pending)
        pending = ""
        if (current !~ transform) {
            emit($0)
            next
        }
    }
    if (current ~ transform) {
        pending = $0
        next
    }
    if ($0 !~ noise) emit($0)
}
END { if (pending != "") emit(pending) }
'
