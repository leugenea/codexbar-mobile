# Code quality: Kotlin complexity erosion and duplication

The **Code metrics** workflow runs on every pull request, every push to `main`
(including documentation-only pushes), and manual dispatch. It measures
handwritten production Kotlin under `app/src/main/**/*.kt`; no Gradle, JVM,
Android SDK, or emulator is needed.

The two analyzer jobs are **informational**. Metric values never fail a job;
installation errors, unreadable sources, Kotlin parse errors, empty scans,
and missing or malformed analyzer output fail closed instead of becoming a
fake zero. These jobs are not included in the required **Android CI result**.
There are **no PR complexity or clone gates yet**, and no hotspot ranking.

The tooling and counting contract are adapted from MIT-licensed
`leugenea/qmix` at `0cc3f1eb8bfb1902ac8e2fae7fba729ef53cc9f0`.

## Scope and exclusions

Both analyzers use `tools/metrics/erosion.py`'s production-file discovery.
Only `.kt` files below `app/src/main/` are selected. Exclusions are:

- `test`, `tests`, `androidTest`, `build`, `generated`, `third_party`, and
  `vendor` path components;
- names ending in `Test.kt`, `.generated.kt`, or `_generated.kt`;
- files whose first 20 lines contain both `Code generated` and `DO NOT EDIT`.

Other source sets, resources, tooling, and build output are outside the scope.
`.jscpd.json` restricts formats to Kotlin and mirrors the path/name exclusions;
explicit source enumeration also excludes generated headers. Discovery errors
are failures. Declaration-only Kotlin files are valid and may yield no
function rows; selected-file and files-with-functions counts expose that
rather than confusing declarations with omitted analysis. A missing or empty
production corpus is an error.

## Complexity erosion

**Code erosion (Kotlin complexity)** uses the repository-owned
`kotlin_complexity.py` backed by **tree-sitter 0.25.2** and
**tree-sitter-kotlin 1.1.0**. These are the only Python runtime dependencies,
with Linux x86_64 wheel hashes in `.github/requirements-metrics.txt`.
CI selects Python 3.13; the listed wheels also support Python 3.11–3.14.

For each function:

- **CCN** is the source-level cyclomatic complexity defined below.
- **NLOC** is the number of nonblank physical declaration lines after masking
  comments and nested named function declarations.
- **Mass** is `CCN × sqrt(NLOC)`.
- **Erosion** is `sum(mass where CCN > 10) / sum(all mass)`, displayed as a
  percentage. The threshold is strictly greater than 10. Zero mass gives 0%.

The report also gives the count of functions with CCN > 10 and the top five
functions by mass (not just high-CCN functions). JSON `schema_version: 2`
contains `languages.Kotlin`, all `functions` with analyzer/identity/location,
and `top_five.Kotlin`. JSON stores erosion as an unrounded fraction; Markdown
formats it as a percentage. Do not scrape the rounded summary for comparisons.

### Kotlin counting contract

The counter reports every body-bearing `function_declaration`, including
expression bodies, local named functions, anonymous-object methods, extension
functions, and overloads. Bodyless declarations have no row. Getters, setters,
constructors, initializers, and lambdas have no standalone metric rows.

CCN is **1 plus** one for each `if`, `for`, `while`, `do-while`, `catch`,
non-`else` `when` entry, `&&`, `||`, and `?:`. Comma-separated `when`
alternatives count once; safe calls (`?.`) are not decisions. Decisions in
lambdas, including Compose content lambdas, belong to the enclosing function.
Nested named functions have their own rows and do not add decisions to their
parent's CCN.

NLOC counts the whole declaration, not only its body. Comments and nested
named function declarations, including their terminating semicolons, are
masked bytewise while preserving line breaks. Parent code sharing a line
with a child still counts. This is a source-line policy, not a compiler or
bytecode measurement.

Function IDs have the form `relative/file.kt::link.link...`, in lexical
nesting order. Named classes/objects contribute their name (an unnamed
companion is `Companion`); function links contain name, optional receiver,
and parameter types: `name@Receiver(T1,T2)`. Other lexical links distinguish
property/parameter defaults, accessors, initializers, enum entries, anonymous
objects/functions, lambdas, control-flow scopes, and call arguments. Secondary
constructors qualify nested functions without creating a metric row.
Comments and whitespace outside escaped identifiers are removed from type
and call-target text. Parameter names, return types, and source positions are
not part of the function signature. Identical scopes/signatures receive
source-order `#2`, `#3`, ... suffixes. Moving lines or reordering distinct
named siblings preserves IDs; identical scopes and positional arguments
remain order-dependent. `owner` displays only named class/object nesting.
The script docstring and fixture tests specify the full ID grammar.

Invalid UTF-8, unreadable files, or syntax `ERROR`/`MISSING` terminate analysis
with file/line diagnostics. The sole grammar exception is the invisible
`_class_member_semi` before `}` in an otherwise valid single-line class or
interface body, including one nested in a multiline class.

## Duplication

**Code duplication (jscpd)** uses the standalone **jscpd v5.3.2** Linux x64
binary, not npm. The release archive is verified **before extraction** with
SHA-256:

```text
97259f222ea7f6d51a0f2faa98ed5889430233e0fb8d2c129f23d84aff4b93b2
```

`.jscpd.json` sets a **10-line minimum** and preserves jscpd's defaults of
**50 tokens** and **mild** matching. `--threshold 100` prevents metric values
from failing the job; `--fail-on-empty` and validated report output prevent
empty or malformed scans from passing.

The normalized JSON has `schema_version: 1`, retains native `statistics`
and `duplicates`, and converts absolute clone paths to repository-relative
POSIX paths. Kotlin statistics must be present and agree with overall totals;
a missing format is an error, not a zero. Percentages use percent units:
`1.5` means 1.5%. Clone rows retain format, lines, tokens, fragment, and both
one-based file ranges. The denominator is jscpd-analyzed lines; duplicated
lines count one fragment per clone. The Markdown table shows duplication
percentage and clone count, followed by the five largest clones by lines,
then tokens and source path.

## Run locally

From the repository root, with `uv` and Linux x86_64 Python installed:

```sh
set -euo pipefail
uv venv -p /usr/bin/python3.13 .venv
uv pip install -p .venv/bin/python --require-hashes --no-deps -r .github/requirements-metrics.txt
.venv/bin/python -B -m unittest discover -s tools/metrics -p 'test_*.py' -v

out=$(mktemp -d "${TMPDIR:?}/code-metrics.XXXXXX")
mkdir -p "$out/erosion" "$out/duplication/raw" "$out/jscpd"
.venv/bin/python tools/metrics/erosion.py \
  --output "$out/erosion/report.json" --summary "$out/erosion/report.md"

curl --fail --location --retry 3 \
  https://github.com/kucherenko/jscpd/releases/download/v5.3.2/jscpd-linux-x64-gnu.tar.gz \
  --output "$out/jscpd/jscpd-linux-x64-gnu.tar.gz"
archive="$out/jscpd/jscpd-linux-x64-gnu.tar.gz"
printf '%s  %s\n' \
  '97259f222ea7f6d51a0f2faa98ed5889430233e0fb8d2c129f23d84aff4b93b2' "$archive" | sha256sum -c -
tar -xzf "$archive" -C "$out/jscpd"

# Bash: preserve filenames, including whitespace, with NUL delimiters.
.venv/bin/python tools/metrics/jscpd_report.py sources . > "$out/duplication/sources"
mapfile -d '' -t sources < "$out/duplication/sources"
(( ${#sources[@]} > 0 ))
"$out/jscpd/jscpd" --config .jscpd.json --absolute \
  --reporters json --threshold 100 --fail-on-empty \
  --output "$out/duplication/raw" "${sources[@]}"
.venv/bin/python tools/metrics/jscpd_report.py report \
  "$out/duplication/raw/jscpd-report.json" \
  "$out/duplication/report.json" "$out/duplication/report.md"
printf 'Reports: %s\n' "$out"
```

Run the commands in **Bash with `set -euo pipefail`** and set `TMPDIR` to an
existing writable scratch directory first. No generated reports belong in the
repository. To check benchmark conversion without writing remote history:

```sh
.venv/bin/python tools/metrics/benchmark_metrics.py \
  --input "$out/erosion/report.json" --output "$out/erosion/benchmark.json"
.venv/bin/python tools/metrics/benchmark_metrics.py --kind duplication \
  --input "$out/duplication/report.json" --output "$out/duplication/benchmark.json"
```

## Artifacts, history, and chart

Each successful analyzer appends `report.md` to its job summary and uploads
`report.json` plus `report.md` as **code-erosion** or **code-duplication**.
The same unprivileged behavior applies to fork PRs; no PR comment publisher
or write token is used for analyzer jobs.

On **main pushes only**, the serialized **Publish code metric history** job
writes two `customSmallerIsBetter` series to `gh-pages:dev/bench/data.js`:

| Series | Percentage metric (`%`) | Count metric |
| --- | --- | --- |
| Code erosion | `Kotlin erosion` | `Kotlin CCN > 10` (`functions`) |
| Code duplication | `Duplication` | `Duplication clones` (`clones`) |

Values retain full precision. PR runs and manual dispatch never write history.
If only one analyzer succeeds, only its valid series is recorded; the other
job remains visibly red. Only history has `contents: write`. Main runs have
unique upstream concurrency groups; history and Pages retain pending jobs
with `queue: max` and no cancellation (GitHub's queue limit is 100).

**Chart:** <https://leugenea.github.io/codexbar-mobile/dev/bench/> after the
first successful main deployment. Before that run, the maintainer must create
an orphan `gh-pages` branch and select **Settings → Pages → Build and
deployment → Source: GitHub Actions**. The **Deploy code metric chart** job
checks out `gh-pages`, adds `.nojekyll`, uploads its snapshot, and deploys it
with `contents: read`, `pages: write`, and `id-token: write` in the
`github-pages` environment. A branch push made with `GITHUB_TOKEN` does not
itself trigger branch-based Pages builds. To republish an edited history
branch, rerun only the Pages job of a successful main run; the upload/deploy
artifact name includes the run attempt to support reruns.

## Interpretation and alerts

An increase strictly greater than 10% relative to the previous point
(`alert-threshold: 110%`) emits a **commit comment**, not a merge gate:
`comment-on-alert: true`, `fail-on-alert: false`. Zero followed by zero does
not alert; zero followed by a positive value does. Small counts make a single
new clone or high-CCN function a large relative change. Read the source report
before acting.

Erosion is mass-weighted, so it can fall because simple code was added even
when a complex function did not improve. Duplication depends on tokenization
and the line/token minimum, not semantic equivalence. Neither score measures
correctness or mandates a refactor. Review meaningful source changes rather
than splitting functions or removing useful repeated code solely to improve
numbers. Changing analyzers, counting rules, exclusions, or metric names can
break comparability; review those changes as methodology changes.
