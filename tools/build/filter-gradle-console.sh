#!/usr/bin/env bash
# Console-only filter: callers tee the complete --info stream to evidence first.
# Use the same hosted Python interpreter as the evidence/retry tools.
set -uo pipefail
exec python3 -B "$(dirname "${BASH_SOURCE[0]}")/filter_gradle_console.py"
