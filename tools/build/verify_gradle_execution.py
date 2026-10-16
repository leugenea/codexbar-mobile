"""Require real execution of Android verification/observation tasks from full Gradle logs."""
import argparse
from pathlib import Path
import re

COMMON = ('verifyResolvedToolchain', 'prepareDebugCoverage', 'testDebugUnitTest')
LINT = ('lintAnalyzeDebug', 'lintAnalyzeDebugUnitTest', 'lintAnalyzeDebugAndroidTest',
        'lintReportDebug', 'lintDebug')
NATIVE = ('connectedDebugAndroidTest', 'collectDebugCoverageInputs', 'jacocoDebugReport',
          'jacocoDebugCoverageVerification')
RELEASE_LINT = ('lintAnalyzeRelease', 'lintReportRelease', 'lintRelease')
REQUIRED = {'build': COMMON + LINT + RELEASE_LINT, 'native': COMMON + NATIVE}
STATES = {'SUCCESS', 'FAILED', 'FROM_CACHE', 'UP_TO_DATE', 'SKIPPED', 'NO_SOURCE'}


def outcomes(log, prefix='GATE_TASK_OUTCOME'):
    observed = {}
    for line in log.splitlines():
        if not line.startswith(prefix + ' '):
            continue
        fields = line.split()
        if len(fields) != 3 or fields[2] not in STATES:
            raise ValueError(f'Malformed gate outcome: {line}')
        _, path, state = fields
        if path in observed:
            raise ValueError(f'Duplicate gate outcome: {path}')
        observed[path] = state
    return observed


def suppressed_vital(path, state, observed):
    match = re.fullmatch(r':app:lintVital(Report)?([A-Z].*)', path)
    if state != 'SKIPPED' or not match:
        return False
    partner = ':app:lint' + (match[1] or '') + match[2]
    return observed.get(path) == 'SKIPPED' and observed.get(partner) == 'SUCCESS'


def reject_reused_headers(log, protected, observed):
    # A stale/success receipt cannot hide a reuse banner in the full --info log.
    for path, state in re.findall(r'^> Task (:\S+) (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)$', log, re.M):
        if (path in protected or path.startswith(':app:lint')) and not suppressed_vital(path, state, observed):
            raise ValueError(f'Gate task header shows reuse/skip: {path} {state}')



def permitted_outcome(path, state, observed, graph_exit):
    return (state == 'SUCCESS' or (graph_exit != 0 and state == 'FAILED')
            or suppressed_vital(path, state, observed))

def validate(log, graph, graph_exit=0):
    observed = outcomes(log)
    required = {':app:' + name for name in REQUIRED[graph]}
    # A failed collection may legitimately prevent report/verification from running.
    # This exception never grants permission to reuse a gate or retry a failed suite.
    if graph_exit != 0 and graph == 'native' and observed.get(':app:collectDebugCoverageInputs') == 'FAILED':
        required -= {':app:jacocoDebugReport', ':app:jacocoDebugCoverageVerification'}
    missing = required - observed.keys()
    if missing:
        raise ValueError(f'Missing gate outcomes: {sorted(missing)}')
    for path, state in observed.items():
        if not permitted_outcome(path, state, observed, graph_exit):
            raise ValueError(f'Gate did not execute successfully: {path} {state}')
    reject_reused_headers(log, required | observed.keys(), observed)
    return observed


COMPILERS = ('compileDebugKotlin', 'compileReleaseKotlin', 'compileDebugUnitTestKotlin',
             'compileDebugAndroidTestKotlin')


def validate_compilation(log, graph_exit=0):
    if graph_exit != 0:
        raise ValueError(f'CodeQL compilation graph failed: exit {graph_exit}')
    observed = outcomes(log, 'COMPILER_TASK_OUTCOME')
    for name in COMPILERS:
        path = ':app:' + name
        if observed.get(path) != 'SUCCESS':
            raise ValueError(f'CodeQL requires successful terminal compilation outcome: {path} {observed.get(path)}')
        # --info can repeat plain task headers during one execution. Any failed,
        # skipped or reused header still contradicts the successful terminal state.
        states = re.findall(r'^> Task ' + re.escape(path) + r'(?:[ \t]+([^\n]*))?$', log, re.M)
        if not states or any(state.strip() for state in states):
            raise ValueError(f'CodeQL compilation headers contradict execution: {path} {states}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('graph', choices=(*REQUIRED, 'codeql'))
    parser.add_argument('log', type=Path)
    parser.add_argument('--graph-exit', type=int, default=0)
    args = parser.parse_args()
    if args.graph == 'codeql':
        validate_compilation(args.log.read_text(), args.graph_exit)
        print('CodeQL Kotlin compilation tasks executed without reuse/skip')
        return
    observed = validate(args.log.read_text(), args.graph, args.graph_exit)
    print(f'Gradle gate execution verified ({args.graph}, graph exit {args.graph_exit}): {observed}')


if __name__ == '__main__':
    main()
