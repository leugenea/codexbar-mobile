"""Write local SDK metadata from Google's matching remote package records.

Mirrors repository 32.3.1 InstallerUtil.writePackageXml: preserve type-details,
revision, display name and license, not remote archives/channelRef. No SDK payload
or source.properties is edited. This bounded adapter installs only the M0 pins.
"""
from copy import deepcopy
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

COMMON = "http://schemas.android.com/repository/android/common/02"
SDK = "http://schemas.android.com/sdk/android/repo/repository2/04"
GENERIC = "http://schemas.android.com/repository/android/generic/02"
XSI = "http://www.w3.org/2001/XMLSchema-instance"


def local_package_xml(repository, package_path, selected):
    if repository.tag != "{" + SDK + "}sdk-repository":
        raise ValueError("Unexpected SDK repository schema")
    matches = [p for p in repository.findall("remotePackage") if p.get("path") == package_path]
    if len(matches) != 1:
        raise ValueError("Missing/duplicate selected SDK package: " + package_path)
    remote = matches[0]
    revision = remote.find("revision")
    numbers = [revision.findtext(n) for n in ("major", "minor", "micro", "preview")]
    expected = selected["revision"].split(".")
    if numbers[:len(expected)] != expected or any(n not in (None, "0") for n in numbers[len(expected):]):
        raise ValueError("SDK package revision differs from M0")
    wanted = [a for a in selected["archives"] if a["os"] in ("all", "linux")]
    actual = [a for a in remote.findall("archives/archive") if a.findtext("host-os", "all") in ("all", "linux")]
    if len(wanted) != 1 or len(actual) != 1:
        raise ValueError("Missing/ambiguous selected SDK archive")
    complete = actual[0].find("complete")
    checksum = complete.find("checksum")
    if (complete.findtext("url") != wanted[0]["archive"] or
            checksum.get("type") != wanted[0]["checksumAlgorithm"] or
            checksum.text != wanted[0]["checksum"]):
        raise ValueError("SDK archive identity differs from M0")
    details = remote.find("type-details")
    if package_path == "platforms;android-37.0":
        fields = {"api-level": "37.0", "codename": "", "extension-level": "22", "base-extension": "true"}
        if (details.get("{" + XSI + "}type") != "sdk:platformDetailsType" or
                any(details.findtext(k) != v for k, v in fields.items()) or
                details.find("layoutlib").get("api") != "15" or
                any(details.find(k) is not None for k in ("beta-api-level", "beta-number", "canary-number"))):
            raise ValueError("SDK platform metadata differs from the selected 37.0 release")
    elif package_path != "build-tools;36.0.0" or details.get("{" + XSI + "}type") != "generic:genericDetailsType":
        raise ValueError("Unsupported selected SDK package/type")
    license_ref = remote.find("uses-license").get("ref")
    licenses = [l for l in repository.findall("license") if l.get("id") == license_ref]
    if len(licenses) != 1:
        raise ValueError("Missing/duplicate SDK license record")
    ET.register_namespace("common", COMMON)
    ET.register_namespace("xsi", XSI)
    # QName values in xsi:type need explicit bindings; ElementTree does not add them.
    output = ET.Element("{" + COMMON + "}repository", {"xmlns:sdk": SDK, "xmlns:generic": GENERIC})
    output.append(deepcopy(licenses[0]))
    local = ET.SubElement(output, "localPackage", {"path": package_path})
    for tag in ("type-details", "revision", "display-name", "uses-license", "dependencies"):
        child = remote.find(tag)
        if child is not None:
            local.append(deepcopy(child))
    ET.indent(output)
    return ET.tostring(output, encoding="utf-8", xml_declaration=True) + b"\n"


def main(sdk_root, repository_file, baseline_file, evidence_dir):
    repository = ET.parse(repository_file).getroot()
    baseline = json.loads(Path(baseline_file).read_text())
    prepared = []
    for package_path, selected in baseline["sdkPackages"].items():
        directory = Path(sdk_root).joinpath(*package_path.split(";"))
        props = dict(line.split("=", 1) for line in (directory / "source.properties").read_text().splitlines()
                     if "=" in line and not line.startswith("#"))
        if props.get("Pkg.Revision") != selected["revision"]:
            raise ValueError("Installed SDK revision differs from M0")
        if package_path.startswith("platforms;") and props.get("AndroidVersion.ApiLevel") != "37.0":
            raise ValueError("Installed SDK API differs from selected platform")
        prepared.append((directory, local_package_xml(repository, package_path, selected)))
    for directory, data in prepared:
        (directory / "package.xml").write_bytes(data)
        name = "platform" if directory.parent.name == "platforms" else "build-tools"
        Path(evidence_dir, name + "-package.xml").write_bytes(data)


if __name__ == "__main__":
    main(*sys.argv[1:])
