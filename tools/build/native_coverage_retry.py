"""One bounded retry for a proven native coverage transport failure, not failing tests."""
import argparse
import json
from pathlib import Path
import shutil
import time

from coverage_gate import BUILD, COVERAGE, IDENTITY_KEYS, current_identity, digest, phase_outcomes, write_json
from verify_test_reports import verify_reports


# Save everything that prepare() deletes before a second, independent task graph.
# The failed graph's inputs must never be combined with the retry's compilation.
SNAPSHOT_PATHS = (
    "coverage-gate", "outputs/unit_test_code_coverage/debugUnitTest",
    "outputs/code_coverage/debugAndroidTest", "reports/jacoco/jacocoDebugReport",
    "test-results/testDebugUnitTest", "outputs/androidTest-results/connected",
    "reports/androidTests", "outputs/connected_android_test_additional_output",
)


def require_transport_failure(status, log, context, failure, identity, now):
    if status != 1:
        raise ValueError("Not an ordinary Gradle failure (success/timeout/signal cannot retry)")
    expected = {"jvm": "SUCCESS", "instrumentation": "SUCCESS", "inputs": "FAILED",
                "report": "NOT_RUN", "verification": "NOT_RUN"}
    if phase_outcomes(log) != expected:
        raise ValueError("Retry requires passing suites and failure only at coverage collection")
    # EOF alone could mean a producer/parser defect. Require the observed ADB
    # disconnect at collection too, rather than retrying every invalid dataset.
    collection = log.rsplit("Collecting code coverage data.", 1)
    if len(collection) != 2 or "adb: device offline" not in collection[1]:
        raise ValueError("No ADB-offline evidence at the coverage collection boundary")
    if any(context.get(key) != identity.get(key) for key in IDENTITY_KEYS):
        raise ValueError("Wrong checkout/run/attempt for retry")
    started = context.get("startedMillis")
    if not isinstance(started, int) or started > now:
        raise ValueError("Invalid retry context start time")
    if failure.get("kind") != "native" or failure.get("reason") not in ("empty", "truncated"):
        raise ValueError("Not empty/truncated native collection data")
    native_root = BUILD / "outputs/code_coverage/debugAndroidTest/connected"
    path = Path(failure["path"])
    if not path.resolve().is_relative_to(native_root.resolve()) or path.suffix != ".ec":
        raise ValueError("Not a native coverage output path")
    size = path.stat().st_size
    if failure["bytes"] != size or failure["sha256"] != digest(path):
        raise ValueError("Failed collection data changed before retry")
    if (failure["reason"] == "empty") != (size == 0):
        raise ValueError("Collection failure reason disagrees with file size")
    if failure["modifiedMillis"] < started or path.stat().st_mtime_ns // 1_000_000 < started:
        raise ValueError("Stale native coverage cannot retry")
    for kind, directory in (("jvm", BUILD / "test-results/testDebugUnitTest"),
                            ("native", BUILD / "outputs/androidTest-results/connected")):
        verify_reports(directory, kind)
    return failure["reason"]


def archive_failed_graph(destination):
    for relative in SNAPSHOT_PATHS:
        source = BUILD / relative
        if source.exists():
            shutil.copytree(source, destination / "app/build" / relative)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exit", type=int, required=True)
    args = parser.parse_args()
    evidence = Path("evidence/native")
    decision = {"retry": False, "graphExit": args.exit, "maxAttempts": 2}
    try:
        context = json.loads((COVERAGE / "context.json").read_text())
        failure = json.loads((COVERAGE / "collection-failure.json").read_text())
        log = (evidence / "strict-connected.log").read_text(errors="replace")
        reason = require_transport_failure(args.exit, log, context, failure,
                                           current_identity(), time.time_ns() // 1_000_000)
        archive_failed_graph(evidence / "attempt-1")
        decision.update(retry=True, reason=f"ADB-offline/{reason}-native-coverage", context=context,
                        phases=phase_outcomes(log), failure=failure)
    except (OSError, ValueError, KeyError, TypeError) as error:
        decision["reason"] = str(error)
    write_json(evidence / "retry-decision.json", decision)
    print(json.dumps(decision, sort_keys=True))
    raise SystemExit(0 if decision["retry"] else 1)


if __name__ == "__main__":
    main()
