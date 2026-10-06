"""Fail closed on INTERNET in the source and actual merged debug app manifests."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def check_manifest(path: Path) -> dict:
    manifest = ET.parse(path).getroot()
    if manifest.tag != "manifest":
        raise ValueError(f"Not an Android manifest: {path}")
    permissions = [node.get(ANDROID + "name") for node in manifest
                   if node.tag in ("uses-permission", "uses-permission-sdk-23")]
    if "android.permission.INTERNET" in permissions:
        raise ValueError(f"Offline M1 APK requests INTERNET: {path}")
    return {"manifest": str(path), "requestedPermissions": permissions}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    paths = (ROOT / "app/src/main/AndroidManifest.xml",
             ROOT / "app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
    receipt = [check_manifest(path) for path in paths]
    destination = ROOT / "evidence/verified-manifests.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(receipt, indent=2) + "\n")
    print("Source and actual merged debug app manifests: no INTERNET permission")


if __name__ == "__main__":
    main()
