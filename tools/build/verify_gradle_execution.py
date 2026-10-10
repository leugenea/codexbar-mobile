"""Require real execution of Android verification/observation tasks from full Gradle logs."""
import argparse
from pathlib import Path
import re

COMMON = ('verifyResolvedToolchain', 'prepareDebugCoverage', 'testDebugUnitTest')
LINT = ('lintAnalyzeDebug', 'lintAnalyzeDebugUnitTest', 'lintAnalyzeDebugAndroidTest',
        'lintReportDebug', 'lintDebug')
NATIVE = ('connectedDebugAndroidTest', 'collectDebugCoverageInputs', 'jacocoDebugReport',
          'jacocoDebugCoverageVerification')
REQUIRED = {'build': COMMON + LINT, 'native': COMMON + NATIVE}
STATES = {'SUCCESS', 'FAILED', 'FROM_CACHE', 'UP_TO_DATE', 'SKIPPED', 'NO_SOURCE'}


def outcomes(log):
    observed = {}
    for line in log.splitlines():
        if not line.startswith('GATE_TASK_OUTCOME '):
            continue
        fields = line.split()
        if len(fields) != 3 or fields[2] not in STATES:
            raise ValueError(f'Malformed gate outcome: {line}')
        _, path, state = fields
        if path in observed:
            raise ValueError(f'Duplicate gate outcome: {path}')
        observed[path] = state
    return observed


def reject_reused_headers(log, protected):
    # A stale/success receipt cannot hide a reuse banner in the full --info log.
    for path, state in re.findall(r'^> Task (:\S+) (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)$', log, re.M):
        if path in protected or path.startswith(':app:lint'):
            raise ValueError(f'Gate task header shows reuse/skip: {path} {state}')


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
        if state != 'SUCCESS' and not (graph_exit != 0 and state == 'FAILED'):
            raise ValueError(f'Gate did not execute successfully: {path} {state}')
    reject_reused_headers(log, required | observed.keys())
    return observed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('graph', choices=REQUIRED)
    parser.add_argument('log', type=Path)
    parser.add_argument('--graph-exit', type=int, default=0)
    args = parser.parse_args()
    observed = validate(args.log.read_text(), args.graph, args.graph_exit)
    print(f'Gradle gate execution verified ({args.graph}, graph exit {args.graph_exit}): {observed}')


if __name__ == '__main__':
    main()
