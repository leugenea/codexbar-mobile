"""Write source/run identity or a manifest of actual M1 evidence bytes; no synthetic run data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
from typing import Any

DIRECTORIES = ("evidence", "build/reports", "app/build/reports", "app/build/test-results",
               "app/build/outputs/apk", "app/build/outputs/androidTest-results",
               "app/build/intermediates/merged_manifests")


def provenance() -> dict:
    keys = ("GITHUB_SHA", "GITHUB_REF", "GITHUB_EVENT_NAME", "GITHUB_RUN_ID",
            "GITHUB_RUN_ATTEMPT", "GITHUB_REPOSITORY", "RUNNER_OS", "RUNNER_ARCH")
    data: dict[str, Any] = {key: os.environ[key] for key in keys}
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    pr = event.get("pull_request")
    data["sourceHeadSha"] = pr["head"]["sha"] if pr else data["GITHUB_SHA"]
    data["baseSha"] = pr["base"]["sha"] if pr else None
    data["checkoutSha"] = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    data["verificationMetadataSha256"] = hashlib.sha256(Path("gradle/verification-metadata.xml").read_bytes()).hexdigest()
    data["integrityProvenanceSha256"] = hashlib.sha256(Path("docs/build/m1-integrity.json").read_bytes()).hexdigest()
    return data


def artifact_manifest() -> dict:
    destination = Path("evidence/artifact-files.sha256.json")
    paths = {path for directory in DIRECTORIES for path in Path(directory).rglob("*")
             if path.is_file() and path != destination}
    return {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(paths)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("provenance", "manifest"))
    args = parser.parse_args()
    Path("evidence").mkdir(exist_ok=True)
    if args.mode == "provenance":
        data = provenance()
        Path("evidence/provenance.json").write_text(json.dumps(data, indent=2) + "\n")
        Path("evidence/checkout-sha.txt").write_text(data["checkoutSha"] + "\n")
    else:
        Path("evidence/artifact-files.sha256.json").write_text(json.dumps(artifact_manifest(), indent=2) + "\n")


if __name__ == "__main__":
    main()
