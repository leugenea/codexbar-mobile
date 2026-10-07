"""Fail closed on the exact app permission allowlist and cleartext-off policy."""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "io.github.leugenea.codexbarmobile"
RECEIVER_PERMISSION = PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
SOURCE_PERMISSIONS = frozenset({"android.permission.INTERNET"})
# androidx.core 1.18.0 already contributes this signature-only receiver permission.
APP_PERMISSIONS = SOURCE_PERMISSIONS | {RECEIVER_PERMISSION}


def check_permissions(permissions, expected=APP_PERMISSIONS) -> list[str]:
    actual = set(permissions)
    if actual != expected:
        raise ValueError(f"Permission allowlist mismatch: missing={sorted(expected - actual)}; "
                         f"unexpected={sorted(actual - expected, key=str)}")
    return sorted(actual)


def check_manifest(path: Path, expected=APP_PERMISSIONS) -> dict:
    manifest = ET.parse(path).getroot()
    if manifest.tag != "manifest":
        raise ValueError(f"Not an Android manifest: {path}")
    nodes = [node for node in manifest if node.tag in ("uses-permission", "uses-permission-sdk-23")]
    if any(node.get(ANDROID + "maxSdkVersion") is not None for node in nodes):
        raise ValueError(f"Conditional manifest permission: {path}")
    permissions = check_permissions((node.get(ANDROID + "name") for node in nodes), expected)
    declarations = manifest.findall("permission")
    expected_declarations = {RECEIVER_PERMISSION} if expected == APP_PERMISSIONS else set()
    if ({node.get(ANDROID + "name") for node in declarations} != expected_declarations
            or any(node.get(ANDROID + "protectionLevel") != "signature" for node in declarations)):
        raise ValueError(f"Permission declarations must preserve the signature-only receiver: {path}")
    applications = manifest.findall("application")
    if (len(applications) != 1 or applications[0].get(ANDROID + "usesCleartextTraffic") != "false"
            or applications[0].get(ANDROID + "networkSecurityConfig") is not None):
        raise ValueError(f"Cleartext must be explicitly disabled without a policy override: {path}")
    return {"manifest": str(path), "requestedPermissions": permissions, "cleartextPermitted": False}


def check_apk_dump(permissions: str, xmltree: str) -> list[str]:
    if not re.search(r"(?m)^package: " + re.escape(PACKAGE) + r"\s*$", permissions):
        raise ValueError("Missing APK package identity")
    names = []
    for line in permissions.splitlines():
        if line.startswith("uses-permission"):
            match = re.fullmatch(r"uses-permission(?:-sdk-23)?: name='([^']+)'(?: maxSdkVersion='\d+')?", line)
            if not match or "maxSdkVersion" in line:
                raise ValueError("Malformed or conditional APK permission")
            names.append(match[1])
    result = check_permissions(names)
    declarations = []
    # aapt XML-tree elements are nested by indentation; read only each declaration.
    lines = xmltree.splitlines()
    for index, line in enumerate(lines):
        match = re.fullmatch(r"([ \t]*)E: permission \(line=\d+\)", line)
        if not match:
            continue
        indent = len(match[1])
        attributes = []
        for following in lines[index + 1:]:
            if following.strip() and len(following) - len(following.lstrip()) <= indent:
                break
            attributes.append(following)
        block = "\n".join(attributes)
        name = re.findall(r'android:name\([^\n]*?\)="([^"]+)"', block)
        signature = re.findall(r"android:protectionLevel\([^\n]*?\)=\(type 0x11\)0x2[ \t]*$", block, re.M)
        if name != [RECEIVER_PERMISSION] or len(signature) != 1:
            raise ValueError("APK receiver permission must remain signature-only")
        declarations.append(name[0])
    if declarations != [RECEIVER_PERMISSION]:
        raise ValueError("APK receiver permission declaration missing or unexpected")
    cleartext = re.findall(r"(?m)^\s*A: android:usesCleartextTraffic\([^\n]*", xmltree)
    if (len(cleartext) != 1 or not re.search(r"=\(type 0x12\)0x0\s*$", cleartext[0])
            or "android:networkSecurityConfig(" in xmltree):
        raise ValueError("APK cleartext policy must be explicitly false without an override")
    return result


def check_installed_dump(text: str) -> list[str]:
    if len(re.findall(r"(?m)^\s*Package \[" + re.escape(PACKAGE) + r"\]", text)) != 1:
        raise ValueError("Missing installed package identity")
    sections = re.findall(r"(?m)^([ \t]*)requested permissions:[ \t]*$", text)
    if len(sections) != 1:
        raise ValueError("Missing or ambiguous installed requested permissions")
    lines = text.splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == "requested permissions:")
    indent = len(sections[0])
    names = []
    for line in lines[start + 1:]:
        if not line.strip():
            continue
        if len(line) - len(line.lstrip()) <= indent:
            break
        names.append(line.strip())
    result = check_permissions(names)
    # dumpsys does not expose FLAG_USES_CLEARTEXT_TRAFFIC in its flag-name table.
    # Installed cleartext/protection-level observations are asserted by androidTest.
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-permissions", type=Path)
    parser.add_argument("--apk-xmltree", type=Path)
    parser.add_argument("--installed-package", type=Path)
    args = parser.parse_args()
    if args.apk_permissions or args.apk_xmltree or args.installed_package:
        if not (args.apk_permissions and args.apk_xmltree and args.installed_package):
            parser.error("APK permissions, XML tree and installed package dump are required together")
        check_apk_dump(args.apk_permissions.read_text(), args.apk_xmltree.read_text())
        check_installed_dump(args.installed_package.read_text())
        print("APK and installed package: exact permission allowlist; APK cleartext disabled; "
              "installed cleartext policy is asserted by native tests")
        return
    paths = (ROOT / "app/src/main/AndroidManifest.xml",
             ROOT / "app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
    receipt = [check_manifest(paths[0], SOURCE_PERMISSIONS), check_manifest(paths[1])]
    destination = ROOT / "evidence/verified-manifests.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(receipt, indent=2) + "\n")
    print("Source and merged debug app manifests: exact permission allowlist; cleartext disabled")


if __name__ == "__main__":
    main()
