"""Require nonempty passing JUnit reports; application test discovery belongs to Gradle."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def verify_reports(directory: Path, kind: str) -> dict:
    reports = sorted(directory.rglob("*.xml"))
    if not reports:
        raise ValueError(f"No {kind} JUnit XML under {directory}")
    cases = []
    for path in reports:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise ValueError(f"{path}: malformed JUnit XML") from error
        if root.tag not in ("testsuite", "testsuites"):
            raise ValueError(f"{path}: not JUnit XML")
        for node in root.iter():
            if node.tag in ("testsuites", "testsuite"):
                for attr in ("failures", "errors", "skipped"):
                    if int(node.get(attr, "0")) != 0:
                        raise ValueError(f"{path}: nonzero {attr}")
            if node.tag in ("failure", "error", "skipped"):
                raise ValueError(f"{path}: failed/errored/skipped tests")
            if node.tag == "testcase":
                cases.append({"class": node.get("classname"), "name": node.get("name"), "report": str(path)})
    if not cases:
        raise ValueError(f"No executed {kind} tests")
    return {"kind": kind, "testCount": len(cases), "reports": [str(p) for p in reports], "testcases": cases}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("jvm", "native"))
    args = parser.parse_args()
    directory = ROOT / ("app/build/test-results/testDebugUnitTest" if args.kind == "jvm"
                        else "app/build/outputs/androidTest-results/connected")
    receipt = verify_reports(directory, args.kind)
    destination = ROOT / "evidence" / ("native" if args.kind == "native" else "jvm")
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "executed-tests.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"{args.kind}: {receipt['testCount']} executed tests; no failures/errors/skips")


if __name__ == "__main__":
    main()
