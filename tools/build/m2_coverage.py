"""Fail-closed M2 coverage/evidence contracts. Never substitutes fixtures for runtime data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

BUILD = Path("app/build")
COVERAGE = BUILD / "m2-coverage"
PHASES = {"jvm": "testDebugUnitTest", "instrumentation": "connectedDebugAndroidTest",
          "report": "jacocoDebugReport", "verification": "jacocoDebugCoverageVerification"}
IDENTITY_KEYS = ("checkoutSha", "sourceHeadSha", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def current_identity():
    checkout = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    provenance = Path("evidence/provenance.json")
    identity = json.loads(provenance.read_text()) if provenance.exists() else {"checkoutSha": checkout, "sourceHeadSha": checkout}
    if identity["checkoutSha"] != checkout:
        raise ValueError("Coverage provenance checkout differs from actual HEAD")
    for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
        if identity.get(key) != os.environ.get(key):
            raise ValueError(f"Coverage provenance differs from current {key}")
    return {key: identity.get(key) for key in IDENTITY_KEYS}


def prepare():
    # This task runs before both suites in ONE task graph. No cached/stale datasets survive.
    for path in (COVERAGE, BUILD / "outputs/unit_test_code_coverage/debugUnitTest",
                 BUILD / "outputs/code_coverage/debugAndroidTest", BUILD / "reports/jacoco/jacocoDebugReport",
                 BUILD / "test-results/testDebugUnitTest", BUILD / "outputs/androidTest-results/connected"):
        shutil.rmtree(path, ignore_errors=False) if path.exists() else None
    COVERAGE.mkdir(parents=True)
    write_json(COVERAGE / "context.json", dict(current_identity(), startedMillis=time.time_ns() // 1_000_000))


def write_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")


def validate_inputs(context, inventory, now, identity):
    if any(context.get(k) != identity.get(k) for k in IDENTITY_KEYS):
        raise ValueError("Stale/wrong-SHA/run/attempt coverage context")
    if not isinstance(context.get("startedMillis"), int) or context["startedMillis"] > now:
        raise ValueError("Invalid coverage start time")
    classes = inventory.get("classes", [])
    if not classes or len({c["name"] for c in classes}) != len(classes):
        raise ValueError("Empty/duplicate production class inventory")
    expected = {c["name"]: c for c in classes}
    if sum(c["instructions"] for c in classes) <= 0:
        raise ValueError("Zero instruction denominator")
    datasets = inventory.get("datasets", [])
    if not any(d["kind"] == "jvm" for d in datasets) or not any(d["kind"] == "native" for d in datasets):
        raise ValueError("Missing JVM exec or native ec dataset")
    probe_counts = {}
    for dataset in datasets:
        if dataset["modifiedMillis"] < context["startedMillis"] or not dataset["bytes"]:
            raise ValueError(f"Stale/empty execution data: {dataset['path']}")
        if not dataset.get("sessions") or any(s["startMillis"] < context["startedMillis"] - 2000 or
                                               s["dumpMillis"] < s["startMillis"] for s in dataset["sessions"]):
            raise ValueError(f"Stale/invalid JaCoCo session: {dataset['path']}")
        matched = 0
        for record in dataset["records"]:
            if record["name"] in expected:
                if record["classId"] != expected[record["name"]]["classId"]:
                    raise ValueError(f"Class-ID mismatch: {record['name']} in {dataset['path']}")
                key = record["classId"]
                if key in probe_counts and probe_counts[key] != record["probes"]:
                    raise ValueError(f"Incompatible probe counts: {record['name']}")
                probe_counts[key] = record["probes"]
                matched += 1
        if not matched:
            raise ValueError(f"No compatible production records: {dataset['path']}")
    return expected


def check_inputs():
    context = json.loads((COVERAGE / "context.json").read_text())
    inventory = json.loads((COVERAGE / "inventory.json").read_text())
    validate_inputs(context, inventory, time.time_ns() // 1_000_000, current_identity())
    for cls in inventory["classes"]:
        if digest(cls["path"]) != cls["sha256"]:
            raise ValueError(f"Class snapshot hash mismatch: {cls['name']}")
    inventory["context"] = context
    inventory["files"] = {d["path"]: digest(d["path"]) for d in inventory["datasets"]}
    inventory["files"].update({c["path"]: digest(c["path"]) for c in inventory["classes"]})
    write_json(COVERAGE / "validated-inputs.json", inventory)


def instruction_counts(xml, expected):
    root = ET.fromstring(xml)
    if root.tag != "report":
        raise ValueError("Not JaCoCo XML")
    classes = root.findall("./package/class")
    represented = {c.get("name") for c in classes}
    # JaCoCo writes every analyzed class, including zero-instruction classes;
    # it only omits their zero-valued counters.
    required = set(expected)
    if represented != required or len(represented) != len(classes):
        raise ValueError(f"Report class inventory mismatch: missing={sorted(required - represented)}, extra={sorted(represented - required)}")
    def counts(node, *, allow_zero=False):
        counters = node.findall("counter[@type='INSTRUCTION']")
        if not counters and allow_zero:
            return 0, 0
        if len(counters) != 1:
            raise ValueError("Missing/duplicate INSTRUCTION counter")
        missed, covered = (int(counters[0].get(k)) for k in ("missed", "covered"))
        if min(missed, covered) < 0:
            raise ValueError("Negative instruction counter")
        return missed, covered
    missed, covered = counts(root)
    class_counts = [counts(c, allow_zero=expected[c.get("name")]["instructions"] == 0) for c in classes]
    if any(sum(counter) != expected[c.get("name")]["instructions"] for c, counter in zip(classes, class_counts)):
        raise ValueError("Changed per-class instruction denominator")
    if (missed, covered) != tuple(sum(c[i] for c in class_counts) for i in (0, 1)):
        raise ValueError("Report total differs from class instruction counters")
    if missed + covered != sum(c["instructions"] for c in expected.values()) or not missed + covered:
        raise ValueError("Zero/changed instruction denominator")
    if covered * 10 < (covered + missed) * 9:
        raise ValueError(f"INSTRUCTION coverage below 90%: covered={covered}, missed={missed}")
    return {"covered": covered, "missed": missed, "ratio": covered / (covered + missed)}


def check_report():
    inventory = json.loads((COVERAGE / "validated-inputs.json").read_text())
    expected = validate_inputs(inventory["context"], inventory, time.time_ns() // 1_000_000, current_identity())
    for path, checksum in inventory["files"].items():
        if digest(path) != checksum:
            raise ValueError(f"Coverage inputs changed after class-ID validation: {path}")
    report = BUILD / "reports/jacoco/jacocoDebugReport/jacocoDebugReport.xml"
    if report.stat().st_mtime_ns // 1_000_000 < inventory["context"]["startedMillis"]:
        raise ValueError("Stale coverage report")
    result = instruction_counts(report.read_bytes(), expected)
    write_json(COVERAGE / "verification.json", dict(result, context=inventory["context"], reportSha256=digest(report)))
    print(json.dumps(result, sort_keys=True))


def phase_outcomes(log):
    # --console=plain plus an afterTask listener records real task outcomes, not start banners.
    observed = {}
    for path, state in re.findall(r"^M2_TASK_OUTCOME (\S+) (SUCCESS|FAILED|SKIPPED|NO_SOURCE|UP_TO_DATE|FROM_CACHE)$", log, re.M):
        if path in observed:
            raise ValueError(f"Duplicate task outcome: {path}")
        observed[path] = state
    return {phase: observed.get(":app:" + task, "NOT_RUN") for phase, task in PHASES.items()}


def result_truth_table(needs):
    mandatory = {"checkpoint", "native"}
    if set(needs) != mandatory or any(needs[name].get("result") != "success" for name in mandatory):
        raise ValueError(f"Mandatory M2 jobs did not all succeed: {needs}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("prepare", "inputs", "report", "phases", "result"))
    parser.add_argument("--exit", type=int)
    args = parser.parse_args()
    if args.mode == "prepare":
        prepare()
    elif args.mode == "inputs":
        check_inputs()
    elif args.mode == "report":
        check_report()
    elif args.mode == "result":
        result_truth_table(json.loads(os.environ["M2_NEEDS"]))
    else:
        log = Path("evidence/native/strict-connected.log")
        data = phase_outcomes(log.read_text(errors="replace") if log.exists() else "")
        data.update(boot="SUCCESS" if Path("evidence/native/boot-success.txt").exists() else "NOT_SUCCESS",
                    graphExit=None, scriptExit=args.exit, identity=current_identity(),
                    phaseGateCodes={phase: 0 if state == "SUCCESS" else 1 if state == "FAILED" else None
                                for phase, state in data.items()})
        # Keep the graph's exit when the all-outcome workflow step refreshes the receipt.
        exit_receipt = Path("evidence/native/graph-exit-status.txt")
        if exit_receipt.exists():
            data["graphExit"] = int(exit_receipt.read_text().strip().split("=", 1)[1])
        script_receipt = Path("evidence/native/exit-status.txt")
        if args.exit is None and script_receipt.exists():
            data["scriptExit"] = int(script_receipt.read_text().strip().split("=", 1)[1])
        write_json("evidence/native/m2-phase-outcomes.json", data)
        if args.exit == 0 and (data["boot"] != "SUCCESS" or any(data[p] != "SUCCESS" for p in PHASES)):
            raise ValueError(f"Zero script exit without genuine success of all phases: {data}")


if __name__ == "__main__":
    main()
