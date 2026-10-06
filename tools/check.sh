#!/usr/bin/env bash
# Quick repository gate; no Gradle build, SDK installation or emulator.
# Local prerequisites: Python 3.13 venv outside the repository, actionlint and
# ShellCheck on PATH. For example (uv, on Linux x86_64):
#   uv venv -p /usr/bin/python3.13 /path/to/venv
#   uv pip install --python /path/to/venv/bin/python --require-hashes --no-deps \
#     -r .github/requirements-policy.txt -r tools/research/requirements.txt \
#     -r .github/requirements-metrics.txt
#   CHECK_PYTHON=/path/to/venv/bin/python bash tools/check.sh
# ACTIONLINT/SHELLCHECK may select exact binaries; neither check can be skipped.
# DEPENDABOT_SCHEMA may supply an offline copy; its pinned checksum is verified.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
python=${CHECK_PYTHON:-python3}
actionlint=${ACTIONLINT:-actionlint}
shellcheck=${SHELLCHECK:-shellcheck}
for executable in "$python" "$actionlint" "$shellcheck"; do
  command -v "$executable" >/dev/null || { printf 'Required executable missing: %s\n' "$executable" >&2; exit 1; }
done
"$python" -c 'import yaml, jsonschema, tree_sitter, tree_sitter_kotlin' || {
  printf '%s\n' 'Install all three hash-pinned requirements files into CHECK_PYTHON venv (see tools/check.sh).' >&2
  exit 1
}
"$actionlint" --version
"$shellcheck" --version
for suite in tools/build tools/research tools/metrics tools/policy; do
  "$python" -B -m unittest discover -s "$suite" -p 'test_*.py' -v
done
schema_args=()
if [[ -n ${DEPENDABOT_SCHEMA:-} ]]; then
  schema_args=(--schema "$DEPENDABOT_SCHEMA")
fi
"$python" -B tools/policy/repository_policy.py "${schema_args[@]}"
# Include staged and unstaged new sources during local development, but exclude
# ignored generated artifacts. NUL delimiters preserve spaces in filenames.
mapfile -d '' -t shell_sources < <(git ls-files --cached --others --exclude-standard -z -- '*.sh' gradlew)
(( ${#shell_sources[@]} > 0 ))
for source in "${shell_sources[@]}"; do
  bash -n "$source"
done
"$shellcheck" --external-sources "${shell_sources[@]}"
"$python" -B - <<'PY'
import ast
from pathlib import Path
import subprocess
paths = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z', '--', '*.py'])
for path in paths.decode().split('\0'):
    if path:
        ast.parse(Path(path).read_text(), filename=path)
PY
# Explicit ShellCheck binary prevents actionlint silently omitting shell lint.
# actionlint 1.7.12 predates GitHub's queue key; ignore only that diagnostic.
"$actionlint" -shellcheck "$shellcheck" \
  -ignore 'unexpected key "queue" for "concurrency" section'
printf '%s\n' 'All repository checks passed.'
