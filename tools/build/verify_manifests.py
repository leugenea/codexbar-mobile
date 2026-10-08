"""Fail closed on permissions, cleartext-off and single-process launcher policy."""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"
PACKAGE = "io.github.leugenea.codexbarmobile"
MAIN_ACTIVITY = PACKAGE + ".MainActivity"
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


def check_merge_policy(manifest, path: Path) -> None:
    for node in manifest.iter():
        if node.get(TOOLS + "node") in {"remove", "removeAll", "replace"}:
            raise ValueError(f"Destructive manifest merge directive: {path}")
        for directive in ("remove", "replace"):
            attributes = node.get(TOOLS + directive)
            if attributes is None:
                continue
            names = {name.strip().split(":")[-1] for name in attributes.split(",")}
            if (node.tag in {"permission", "uses-permission", "uses-permission-sdk-23"}
                    or names & {"usesCleartextTraffic", "networkSecurityConfig", "launchMode", "process"}):
                raise ValueError(f"Protected manifest policy merge directive: {path}")
        if node.get(ANDROID + "networkSecurityConfig") is not None:
            raise ValueError(f"Cleartext policy override is forbidden: {path}")


def check_source_manifests(root: Path) -> dict:
    # App variants must inherit the reviewed main policy, not merge an overlay.
    overlays = sorted(path for path in (root / "app/src").glob("*/AndroidManifest.xml")
                      if path.parent.name not in {"main", "test", "androidTest"})
    if overlays:
        raise ValueError(f"App manifest source-set overlays are forbidden: {overlays}")
    return check_manifest(root / "app/src/main/AndroidManifest.xml", SOURCE_PERMISSIONS)


def activity_name(name: str) -> str:
    if name.startswith("."):
        return PACKAGE + name
    return name if "." in name else PACKAGE + "." + name


def check_process_owner(application, path: Path) -> None:
    if any(node.get(ANDROID + "process") is not None for node in application.iter()):
        raise ValueError(f"App components must use the default single process: {path}")
    activities = [node for node in application.findall("activity")
                  if activity_name(node.get(ANDROID + "name", "")) == MAIN_ACTIVITY]
    if len(activities) != 1 or activities[0].get(ANDROID + "launchMode") != "singleTask":
        raise ValueError(f"MainActivity must declare singleTask launch mode: {path}")


def check_manifest(path: Path, expected=APP_PERMISSIONS) -> dict:
    manifest = ET.parse(path).getroot()
    if manifest.tag != "manifest":
        raise ValueError(f"Not an Android manifest: {path}")
    check_merge_policy(manifest, path)
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
    check_process_owner(applications[0], path)
    return {"manifest": str(path), "requestedPermissions": permissions, "cleartextPermitted": False,
            "mainActivityLaunchMode": "singleTask", "defaultProcessOnly": True}


def xmltree_elements(xmltree: str, element: str) -> list[str]:
    lines = xmltree.splitlines()
    blocks = []
    for index, line in enumerate(lines):
        match = re.fullmatch(r"([ \t]*)E: " + re.escape(element) + r" \(line=\d+\)", line)
        if not match:
            continue
        indent = len(match[1])
        attributes = []
        for following in lines[index + 1:]:
            if following.strip() and len(following) - len(following.lstrip()) <= indent:
                break
            if following.lstrip().startswith("E:"):
                break
            attributes.append(following)
        blocks.append("\n".join(attributes))
    return blocks


def check_apk_process_owner(xmltree: str) -> None:
    if re.search(r"(?m)^\s*A: android:process\(", xmltree):
        raise ValueError("APK app components must use the default single process")
    activities = []
    for block in xmltree_elements(xmltree, "activity"):
        names = re.findall(r'android:name\([^\n]*?\)="([^"]+)"', block)
        if names and activity_name(names[0]) == MAIN_ACTIVITY:
            activities.append(block)
    mode = r"(?m)^\s*A: android:launchMode\([^\n]*?\)=\(type 0x10\)0x2[ \t]*$"
    if len(activities) != 1 or len(re.findall(mode, activities[0])) != 1:
        raise ValueError("APK MainActivity must declare singleTask launch mode")


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
    check_apk_process_owner(xmltree)
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


def check_installed_path(text: str) -> None:
    paths = text.splitlines()
    pattern = (r"package:/data/app/(?:[^/\s]+/)*" + re.escape(PACKAGE)
               + r"-[^/\s]+/(?:base|split_[^/\s]+)\.apk")
    if not paths or any(not re.fullmatch(pattern, path) for path in paths):
        raise ValueError("Missing or wrong installed package path")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-permissions", type=Path)
    parser.add_argument("--apk-xmltree", type=Path)
    parser.add_argument("--installed-path", type=Path)
    parser.add_argument("--installed-package", type=Path)
    args = parser.parse_args()
    if args.apk_permissions or args.apk_xmltree or args.installed_path or args.installed_package:
        if bool(args.apk_permissions) != bool(args.apk_xmltree):
            parser.error("APK permissions and XML tree are required together")
        if args.installed_package and not (args.apk_permissions and args.installed_path):
            parser.error("Installed dump requires the APK observations and installed path")
        if args.apk_permissions:
            check_apk_dump(args.apk_permissions.read_text(), args.apk_xmltree.read_text())
        if args.installed_path:
            check_installed_path(args.installed_path.read_text())
        if args.installed_package:
            check_installed_dump(args.installed_package.read_text())
        print("Supplied APK/path/installed observations verified; installed cleartext policy "
              "is asserted by native tests")
        return
    receipt = [check_source_manifests(ROOT)]
    for variant in ("debug", "release"):
        path = (ROOT / "app/build/intermediates/merged_manifests" / variant
                / f"process{variant.title()}Manifest/AndroidManifest.xml")
        receipt.append(check_manifest(path))
    destination = ROOT / "evidence/verified-manifests.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(receipt, indent=2) + "\n")
    print("Source and merged debug/release manifests: exact permissions; cleartext disabled; "
          "singleTask launcher in the default process")


if __name__ == "__main__":
    main()
